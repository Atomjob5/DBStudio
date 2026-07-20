import type { TransportState, WorkspaceOpenResponse, WorkspaceSummary } from "../types";

export interface RpcError { code: string; message: string; details?: unknown; requestId?: string; }
type EventListener = (payload: unknown) => void;
export type MockRequestHandler = (type: string, payload: Record<string, unknown>, emit: (type: string, payload: unknown) => void) => unknown | Promise<unknown>;

function clientId(): string {
  const stored = window.sessionStorage.getItem("dbstudio.clientId");
  if (stored) return stored;
  const created = crypto.randomUUID();
  window.sessionStorage.setItem("dbstudio.clientId", created);
  return created;
}

export class RpcClient {
  private static readonly PROBE_TIMEOUT_MS = 10_000;
  private readonly listeners = new Map<string, Set<EventListener>>();
  private readonly browserClientId = clientId();
  private readonly readyPromise: Promise<void>;
  private workspaceId = "";
  private socket?: WebSocket;
  private reconnectTimer?: number;
  private reconnectPromise?: Promise<void>;
  private probePromise?: Promise<void>;
  private probeResolve?: () => void;
  private probeReject?: (error: Error) => void;
  private probeDeadline?: number;
  private reconnectAttempt = 0;
  private socketSequence = 0;
  private currentState: TransportState;
  private readonly readyWaiters = new Set<{ resolve: () => void; reject: (error: Error) => void; timer: number }>();
  private closing = false;
  private readonly executions = new Map<string, string>();

  constructor(private readonly mock?: MockRequestHandler) {
    this.workspaceId = mock ? "mock-workspace" : "";
    this.currentState = mock ? "ready" : "connecting";
    this.readyPromise = mock ? Promise.resolve() : this.initializeAuthentication();
    if (!mock) {
      window.addEventListener("online", this.resumeHealthCheck);
      window.addEventListener("pageshow", this.resumeHealthCheck);
      document.addEventListener("visibilitychange", this.visibilityChanged);
    }
  }

  get transportState(): TransportState { return this.currentState; }
  get activeWorkspaceId(): string { return this.workspaceId; }
  get activeClientId(): string { return this.browserClientId; }
  ready(): Promise<void> { return this.readyPromise; }

  async listWorkspaces(): Promise<WorkspaceSummary[]> {
    await this.readyPromise;
    if (this.mock) return await this.mock("workspace.list", {}, this.emitBound) as WorkspaceSummary[];
    return this.fetchJson("/api/v1/workspaces", "GET");
  }

  async createWorkspace(name: string): Promise<WorkspaceSummary> {
    await this.readyPromise;
    if (this.mock) return await this.mock("workspace.create", { name }, this.emitBound) as WorkspaceSummary;
    return this.fetchJson("/api/v1/workspaces", "POST", { name });
  }

  async renameWorkspace(workspaceId: string, name: string): Promise<WorkspaceSummary> {
    if (this.mock) return await this.mock("workspace.rename", { workspaceId, name }, this.emitBound) as WorkspaceSummary;
    return this.fetchJson(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}`, "PATCH", { name });
  }

  async deleteWorkspace(workspaceId: string): Promise<void> {
    if (this.mock) { await this.mock("workspace.delete", { workspaceId }, this.emitBound); return; }
    await this.fetchJson(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}`, "DELETE");
  }

  async openWorkspace(workspaceId: string): Promise<WorkspaceOpenResponse> {
    await this.readyPromise;
    this.closing = false;
    this.workspaceId = workspaceId;
    this.setState("connecting");
    const response = this.mock
      ? await this.mock("workspace.open", { workspaceId, clientId: this.browserClientId }, this.emitBound) as WorkspaceOpenResponse
      : await this.fetchJson<WorkspaceOpenResponse>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/open`,
        "POST", { clientId: this.browserClientId });
    if (!this.mock) await this.connectEvents();
    this.setState("ready");
    return response;
  }

  async resolveWorkspaceRecovery(decision: "restore" | "discard"): Promise<WorkspaceOpenResponse> {
    if (!this.workspaceId) throw new Error("尚未选择工作空间");
    if (this.mock) return await this.mock("workspace.recovery", { decision }, this.emitBound) as WorkspaceOpenResponse;
    return this.fetchJson(`/api/v1/workspaces/${encodeURIComponent(this.workspaceId)}/recovery`, "POST",
      { decision });
  }

  async closeWorkspace(): Promise<void> {
    const id = this.workspaceId;
    this.closing = true;
    this.socketSequence++;
    const socket = this.socket; this.socket = undefined;
    if (socket && socket.readyState < WebSocket.CLOSING) socket.close(1000, "workspace closed");
    if (id && !this.mock) await this.fetchJson(`/api/v1/workspaces/${encodeURIComponent(id)}/close`, "POST", {});
    this.workspaceId = ""; this.closing = false; this.setState("offline");
  }

  async finalizeWorkspace(): Promise<void> {
    if (!this.workspaceId) return;
    if (this.mock) { await this.mock("workspace.finalize", {}, this.emitBound); return; }
    await this.fetchJson(`/api/v1/workspaces/${encodeURIComponent(this.workspaceId)}/finalize`, "POST", {});
  }

  dispose(): void {
    this.closing = true;
    if (this.reconnectTimer !== undefined) window.clearTimeout(this.reconnectTimer);
    this.reconnectTimer = undefined; this.socketSequence++;
    const socket = this.socket; this.socket = undefined;
    if (socket && socket.readyState < WebSocket.CLOSING) socket.close(1000, "client disposed");
    window.removeEventListener("online", this.resumeHealthCheck);
    window.removeEventListener("pageshow", this.resumeHealthCheck);
    document.removeEventListener("visibilitychange", this.visibilityChanged);
    const error = new Error("事件通道已关闭"); this.rejectProbe(error);
    for (const waiter of this.readyWaiters) { window.clearTimeout(waiter.timer); waiter.reject(error); }
    this.readyWaiters.clear(); this.setState("offline");
  }

  async ensureOperational(timeoutMs = 30_000): Promise<void> {
    await this.readyPromise;
    if (this.mock) return;
    if (!this.workspaceId) throw new Error("尚未选择工作空间");
    if (this.currentState === "ready") {
      try { await this.probe(); return; } catch { /* reconnect below */ }
    }
    this.startReconnect(); await this.waitUntilReady(timeoutMs); await this.probe();
  }

  async request<T>(type: string, payload: unknown = {}, timeoutMs = 30_000): Promise<T> {
    await this.readyPromise;
    const body = (payload ?? {}) as Record<string, unknown>;
    if (this.mock) return await this.mock(type, body, this.emitBound) as T;
    const route = this.route(type, body);
    try {
      const result = await this.fetchJson<T>(route.path, route.method, route.body, timeoutMs);
      if (type === "query.execute") {
        const executionId = (result as { executionId?: string }).executionId;
        if (executionId && typeof body.editorId === "string") this.executions.set(body.editorId, executionId);
      }
      return result;
    } catch (error) {
      const code = (error as RpcError).code;
      if ((code === "WORKSPACE_NOT_OPEN" || code === "EVENT_CHANNEL_REQUIRED") && this.workspaceId) this.startReconnect();
      throw error;
    }
  }

  on(type: string, listener: EventListener): () => void {
    const group = this.listeners.get(type) ?? new Set<EventListener>();
    group.add(listener); this.listeners.set(type, group); return () => group.delete(listener);
  }

  async uploadCsv(file: File): Promise<{ uploadId: string; name: string; delimiter: string }> {
    await this.ensureOperational();
    const data = new FormData(); data.append("file", file, file.name);
    return this.fetchJson(`/api/v1/workspaces/${this.workspaceId}/csv/uploads`, "POST", data, 120_000);
  }

  async saveEditorDraft(editorId: string, payload: Record<string, unknown>, keepalive = false): Promise<void> {
    if (!this.workspaceId) return;
    if (this.mock) { await this.mock("editor.draft", { ...payload, editorId }, this.emitBound); return; }
    await this.fetchJson(`/api/v1/workspaces/${this.workspaceId}/editors/${encodeURIComponent(editorId)}/draft`,
      "PUT", payload, 30_000, true, keepalive);
  }

  async downloadCsv(kind: "loaded" | "full", editorId: string, resultIndex: number): Promise<void> {
    await this.ensureOperational();
    const query = new URLSearchParams({ editorId, resultIndex: String(resultIndex), clientId: this.browserClientId });
    const anchor = document.createElement("a");
    anchor.href = `/api/v1/workspaces/${this.workspaceId}/csv/export/${kind}?${query}`;
    anchor.download = kind === "full" ? "dbstudio-full-result.csv" : "dbstudio-result.csv";
    anchor.style.display = "none"; document.body.append(anchor); anchor.click(); anchor.remove();
  }

  private async initializeAuthentication(): Promise<void> {
    const fragment = new URLSearchParams(window.location.hash.replace(/^#/, ""));
    const token = fragment.get("token");
    if (token) {
      await this.fetchJson("/api/v1/auth/exchange", "POST", { token }, 15_000, false);
      window.history.replaceState(null, "", `${window.location.pathname}${window.location.search}`);
    }
    this.setState("offline");
  }

  private connectEvents(): Promise<void> {
    return new Promise((resolve, reject) => {
      const sequence = ++this.socketSequence;
      const protocol = window.location.protocol === "https:" ? "wss" : "ws";
      const socket = new WebSocket(`${protocol}://${window.location.host}/api/v1/events?workspaceId=${encodeURIComponent(this.workspaceId)}&clientId=${encodeURIComponent(this.browserClientId)}`);
      const previous = this.socket; this.socket = socket;
      if (previous && previous !== socket && previous.readyState < WebSocket.CLOSING) previous.close(1000, "replaced");
      let opened = false, settled = false;
      const fail = (error: Error): void => { if (!settled) { settled = true; reject(error); } };
      const timer = window.setTimeout(() => {
        if (sequence !== this.socketSequence) return;
        fail(new Error("事件连接就绪超时"));
        if (socket.readyState < WebSocket.CLOSING) socket.close(4000, "ready timeout");
      }, 10_000);
      socket.onopen = () => { opened = true; };
      socket.onmessage = (event) => {
        if (sequence !== this.socketSequence) return;
        try {
          const message = JSON.parse(String(event.data)) as { version: number; type: string; payload?: unknown };
          if (message.version !== 1 || !message.type) return;
          if (message.type === "workspace.ready" && !settled) {
            settled = true; window.clearTimeout(timer); this.reconnectAttempt = 0; resolve();
          }
          if (message.type === "workspace.pong") this.resolveProbe();
          this.emit(message.type, message.payload);
        } catch { /* malformed local event */ }
      };
      socket.onerror = () => { /* close owns retry */ };
      socket.onclose = () => {
        window.clearTimeout(timer);
        if (sequence !== this.socketSequence) return;
        this.socket = undefined; this.rejectProbe(new Error("事件通道已断开"));
        if (!opened || !settled) fail(new Error("事件连接建立失败"));
        if (!this.closing && this.workspaceId) this.startReconnect();
      };
    });
  }

  private startReconnect(): void {
    if (this.closing || !this.workspaceId || this.reconnectTimer !== undefined || this.reconnectPromise) return;
    this.setState("reconnecting");
    const delay = Math.min(5_000, 250 * 2 ** this.reconnectAttempt++);
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = undefined;
      this.reconnectPromise = this.reconnect().finally(() => { this.reconnectPromise = undefined; });
      void this.reconnectPromise.catch(() => this.startReconnect());
    }, delay);
  }

  private async reconnect(): Promise<void> {
    const id = this.workspaceId;
    await this.fetchJson(`/api/v1/workspaces/${encodeURIComponent(id)}/open`, "POST", {
      clientId: this.browserClientId, reconnect: true
    });
    if (id !== this.workspaceId) return;
    await this.connectEvents(); this.setState("ready");
  }

  private probe(): Promise<void> {
    if (this.mock) return Promise.resolve();
    if (this.probePromise) return this.probePromise;
    const socket = this.socket;
    if (!socket || socket.readyState !== WebSocket.OPEN) return Promise.reject(new Error("事件通道尚未连接"));
    this.probePromise = new Promise<void>((resolve, reject) => {
      this.probeResolve = resolve; this.probeReject = reject;
      this.probeDeadline = window.setTimeout(() => {
        this.probeDeadline = undefined; this.rejectProbe(new Error("事件通道心跳超时"));
        if (socket === this.socket && socket.readyState < WebSocket.CLOSING) socket.close(4002, "probe timeout");
      }, RpcClient.PROBE_TIMEOUT_MS);
      try { socket.send("ping"); } catch { this.rejectProbe(new Error("事件通道心跳发送失败")); }
    }).finally(() => { this.probePromise = undefined; });
    return this.probePromise;
  }

  private resolveProbe(): void {
    if (this.probeDeadline !== undefined) window.clearTimeout(this.probeDeadline);
    this.probeDeadline = undefined;
    const resolve = this.probeResolve; this.probeResolve = undefined; this.probeReject = undefined; resolve?.();
  }
  private rejectProbe(error: Error): void {
    if (this.probeDeadline !== undefined) window.clearTimeout(this.probeDeadline);
    this.probeDeadline = undefined;
    const reject = this.probeReject; this.probeResolve = undefined; this.probeReject = undefined; reject?.(error);
  }
  private waitUntilReady(timeoutMs: number): Promise<void> {
    if (this.currentState === "ready") return Promise.resolve();
    return new Promise<void>((resolve, reject) => {
      const waiter = { resolve, reject, timer: 0 };
      waiter.timer = window.setTimeout(() => {
        this.readyWaiters.delete(waiter); this.setState("offline"); reject(new Error("等待事件通道恢复超时"));
      }, timeoutMs); this.readyWaiters.add(waiter);
    });
  }
  private setState(state: TransportState): void {
    if (this.currentState === state) return;
    this.currentState = state; this.emit("transport.state", { state });
    if (state !== "ready") return;
    for (const waiter of this.readyWaiters) { window.clearTimeout(waiter.timer); waiter.resolve(); }
    this.readyWaiters.clear();
  }
  private readonly resumeHealthCheck = (): void => {
    if (document.visibilityState === "hidden" || this.mock || !this.workspaceId) return;
    void this.ensureOperational().catch(() => this.startReconnect());
  };
  private readonly visibilityChanged = (): void => {
    if (document.visibilityState !== "hidden") this.resumeHealthCheck();
  };
  private readonly emitBound = (type: string, payload: unknown): void => this.emit(type, payload);
  private emit(type: string, payload: unknown): void {
    this.listeners.get(type)?.forEach((listener) => listener(payload));
    this.listeners.get("*")?.forEach((listener) => listener({ version: 1, type, payload }));
  }

  private route(type: string, body: Record<string, unknown>): { path: string; method: string; body?: unknown } {
    if (!this.workspaceId && !["settings.get", "settings.update", "history.list"].includes(type)) {
      throw new Error("尚未选择工作空间");
    }
    const ws = `/api/v1/workspaces/${this.workspaceId}`;
    const editorId = encodeURIComponent(String(body.editorId ?? ""));
    switch (type) {
      case "app.bootstrap": return { path: `/api/v1/bootstrap?workspaceId=${this.workspaceId}`, method: "GET" };
      case "connection.test": return { path: "/api/v1/connections/test", method: "POST", body };
      case "connection.catalog": return { path: "/api/v1/connections/catalog", method: "GET" };
      case "connection.system.create": return { path: "/api/v1/connection-systems", method: "POST", body };
      case "connection.system.update": return { path: `/api/v1/connection-systems/${encodeURIComponent(String(body.id ?? ""))}`, method: "PUT", body };
      case "connection.system.delete": return { path: `/api/v1/connection-systems/${encodeURIComponent(String(body.id ?? ""))}`, method: "DELETE" };
      case "connection.environment.create": return { path: "/api/v1/connection-environments", method: "POST", body };
      case "connection.environment.update": return { path: `/api/v1/connection-environments/${encodeURIComponent(String(body.id ?? ""))}`, method: "PUT", body };
      case "connection.environment.delete": return { path: `/api/v1/connection-environments/${encodeURIComponent(String(body.id ?? ""))}`, method: "DELETE" };
      case "connection.profile.create": return { path: `${ws}/connection-profiles`, method: "POST", body };
      case "connection.profile.update": return { path: `${ws}/connection-profiles/${encodeURIComponent(String(body.id ?? ""))}`, method: "PUT", body };
      case "connection.profile.move": return { path: `${ws}/connection-profiles/${encodeURIComponent(String(body.id ?? ""))}/location`, method: "PUT", body };
      case "connection.profile.delete": return { path: `${ws}/connection-profiles/${encodeURIComponent(String(body.id ?? ""))}`, method: "DELETE" };
      case "metadata.children": return { path: `${ws}/metadata/children`, method: "POST", body };
      case "metadata.completionSnapshot": return { path: `${ws}/metadata/completion-snapshot`, method: "POST", body };
      case "metadata.definition": return { path: `${ws}/metadata/definition`, method: "POST", body };
      case "metadata.generateQuery": return { path: `${ws}/metadata/query`, method: "POST", body };
      case "editor.create": return { path: `${ws}/editors`, method: "POST", body };
      case "editor.bind": return { path: `${ws}/editors/${editorId}/connection`, method: "PUT", body };
      case "editor.unbind": return { path: `${ws}/editors/${editorId}/connection?transactionAction=${encodeURIComponent(String(body.transactionAction ?? ""))}`, method: "DELETE" };
      case "editor.close": return { path: `${ws}/editors/${editorId}/close`, method: "POST", body };
      case "editor.draft": return { path: `${ws}/editors/${editorId}/draft`, method: "PUT", body };
      case "query.execute": return { path: `${ws}/editors/${editorId}/executions`, method: "POST", body };
      case "query.fetchRows": return { path: `${ws}/editors/${editorId}/results/${encodeURIComponent(String(body.resultIndex ?? 0))}/page`, method: "POST", body };
      case "query.cancel": return { path: `${ws}/executions/${encodeURIComponent(this.executions.get(String(body.editorId ?? "")) ?? "none")}`, method: "DELETE" };
      case "transaction.commit": return { path: `${ws}/editors/${editorId}/transaction/commit`, method: "POST", body: {} };
      case "transaction.rollback": return { path: `${ws}/editors/${editorId}/transaction/rollback`, method: "POST", body: {} };
      case "sql.format": return { path: `${ws}/sql/format`, method: "POST", body };
      case "sql.complete": return { path: `${ws}/sql/completions?prefix=${encodeURIComponent(String(body.prefix ?? ""))}&editorId=${editorId}`, method: "GET" };
      case "history.list": return { path: `/api/v1/history?limit=${encodeURIComponent(String(body.limit ?? 200))}`, method: "GET" };
      case "settings.get": return { path: "/api/v1/settings", method: "GET" };
      case "settings.update": return { path: "/api/v1/settings", method: "PUT", body };
      case "csv.preview": return { path: `${ws}/csv/preview`, method: "POST", body };
      case "csv.import": return { path: `${ws}/csv/imports`, method: "POST", body };
      case "app.closeDecision": return body.allow
        ? { path: "/api/v1/shutdown", method: "POST", body: {} }
        : { path: `${ws}/open`, method: "POST", body: { clientId: this.browserClientId } };
      default: throw new Error(`尚未实现的本地 API：${type}`);
    }
  }

  private async fetchJson<T>(path: string, method: string, body?: unknown, timeoutMs = 30_000,
                             _authenticated = true, keepalive = false): Promise<T> {
    const controller = new AbortController(); const timer = window.setTimeout(() => controller.abort(), timeoutMs);
    try {
      const multipart = body instanceof FormData;
      const headers: Record<string, string> = {};
      if (!multipart && body !== undefined) headers["Content-Type"] = "application/json";
      if (this.workspaceId) headers["X-DBStudio-Client-Id"] = this.browserClientId;
      const response = await fetch(path, { method, credentials: "same-origin", cache: "no-store", headers, keepalive,
        body: method === "GET" || body === undefined ? undefined : multipart ? body : JSON.stringify(body), signal: controller.signal });
      const data = response.status === 204 ? {} : await response.json().catch(() => ({}));
      if (!response.ok) {
        const failure = data as RpcError;
        const error = new Error(failure.message || `本地 API 请求失败（${response.status}）`);
        Object.assign(error, { code: failure.code, details: failure.details, requestId: failure.requestId }); throw error;
      }
      return data as T;
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") throw new Error(`请求超时：${path}`);
      throw error;
    } finally { window.clearTimeout(timer); }
  }
}

let mockHandler: MockRequestHandler | undefined;
if (import.meta.env.DEV && new URLSearchParams(window.location.search).has("mock")) {
  const module = await import("./mock"); mockHandler = module.developmentMockRequest;
}
if (import.meta.env.MODE === "test" && !mockHandler) mockHandler = async () => ({});
export const rpc = new RpcClient(mockHandler);
