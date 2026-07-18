export interface RpcError {
  code: string;
  message: string;
  details?: unknown;
  requestId?: string;
}

type EventListener = (payload: unknown) => void;
export type MockRequestHandler = (type: string, payload: Record<string, unknown>, emit: (type: string, payload: unknown) => void) => unknown | Promise<unknown>;

function newWorkspaceId(): string {
  const stored = window.sessionStorage.getItem("dbstudio.workspaceId");
  if (stored) return stored;
  const value = crypto.randomUUID();
  window.sessionStorage.setItem("dbstudio.workspaceId", value);
  return value;
}

export class RpcClient {
  private readonly listeners = new Map<string, Set<EventListener>>();
  private readonly workspaceId: string;
  private readonly readyPromise: Promise<void>;
  private socket?: WebSocket;
  private reconnectTimer?: number;
  private reconnectAttempt = 0;
  private closing = false;
  private readonly executions = new Map<string, string>();

  constructor(private readonly mock?: MockRequestHandler) {
    this.workspaceId = newWorkspaceId();
    this.readyPromise = mock ? Promise.resolve() : this.initialize();
  }

  async request<T>(type: string, payload: unknown = {}, timeoutMs = 30_000): Promise<T> {
    await this.readyPromise;
    const body = (payload ?? {}) as Record<string, unknown>;
    if (this.mock) return await this.mock(type, body, (eventType, eventPayload) => this.emit(eventType, eventPayload)) as T;
    const route = this.route(type, body);
    const result = await this.fetchJson<T>(route.path, route.method, route.body, timeoutMs);
    if (type === "query.execute") {
      const executionId = (result as { executionId?: string }).executionId;
      if (executionId && typeof body.editorId === "string") this.executions.set(body.editorId, executionId);
    }
    return result;
  }

  on(type: string, listener: EventListener): () => void {
    const group = this.listeners.get(type) ?? new Set<EventListener>();
    group.add(listener);
    this.listeners.set(type, group);
    return () => group.delete(listener);
  }

  async uploadCsv(file: File): Promise<{ uploadId: string; name: string; delimiter: string }> {
    await this.readyPromise;
    const data = new FormData();
    data.append("file", file, file.name);
    return this.fetchJson(`/api/v1/workspaces/${this.workspaceId}/csv/uploads`, "POST", data, 120_000);
  }

  downloadCsv(kind: "loaded" | "full", editorId: string, resultIndex: number): void {
    const query = new URLSearchParams({ editorId, resultIndex: String(resultIndex) });
    const anchor = document.createElement("a");
    anchor.href = `/api/v1/workspaces/${this.workspaceId}/csv/export/${kind}?${query}`;
    anchor.download = kind === "full" ? "dbstudio-full-result.csv" : "dbstudio-result.csv";
    anchor.style.display = "none";
    document.body.append(anchor);
    anchor.click();
    anchor.remove();
  }

  private async initialize(): Promise<void> {
    const fragment = new URLSearchParams(window.location.hash.replace(/^#/, ""));
    const token = fragment.get("token");
    if (token) {
      await this.fetchJson("/api/v1/auth/exchange", "POST", { token }, 15_000, false);
      window.history.replaceState(null, "", `${window.location.pathname}${window.location.search}`);
    }
    await this.fetchJson(`/api/v1/workspaces/${this.workspaceId}`, "PUT", {}, 15_000, false);
    await this.connectEvents();
  }

  private connectEvents(): Promise<void> {
    return new Promise((resolve, reject) => {
      const protocol = window.location.protocol === "https:" ? "wss" : "ws";
      const socket = new WebSocket(`${protocol}://${window.location.host}/api/v1/events?workspaceId=${encodeURIComponent(this.workspaceId)}`);
      this.socket = socket;
      const initialTimer = window.setTimeout(() => reject(new Error("事件连接超时")), 10_000);
      socket.onopen = () => {
        window.clearTimeout(initialTimer);
        this.reconnectAttempt = 0;
        resolve();
      };
      socket.onmessage = (event) => {
        try {
          const message = JSON.parse(String(event.data)) as { version: number; type: string; payload?: unknown };
          if (message.version === 1 && message.type) this.emit(message.type, message.payload);
        } catch { /* Ignore malformed local events. */ }
      };
      socket.onerror = () => { /* onclose owns retry behavior */ };
      socket.onclose = () => {
        window.clearTimeout(initialTimer);
        if (!this.closing) this.scheduleReconnect();
      };
    });
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer !== undefined) return;
    const delay = Math.min(5_000, 250 * 2 ** this.reconnectAttempt++);
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = undefined;
      void this.connectEvents().catch(() => this.scheduleReconnect());
    }, delay);
  }

  private emit(type: string, payload: unknown): void {
    this.listeners.get(type)?.forEach((listener) => listener(payload));
    this.listeners.get("*")?.forEach((listener) => listener({ version: 1, type, payload }));
  }

  private route(type: string, body: Record<string, unknown>): { path: string; method: string; body?: unknown } {
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
      case "metadata.definition": return { path: `${ws}/metadata/definition`, method: "POST", body };
      case "metadata.generateQuery": return { path: `${ws}/metadata/query`, method: "POST", body };
      case "editor.create": return { path: `${ws}/editors`, method: "POST", body };
      case "editor.bind": return { path: `${ws}/editors/${editorId}/connection`, method: "PUT", body };
      case "editor.unbind": return { path: `${ws}/editors/${editorId}/connection?transactionAction=${encodeURIComponent(String(body.transactionAction ?? ""))}`, method: "DELETE" };
      case "editor.close": return { path: `${ws}/editors/${editorId}/close`, method: "POST", body };
      case "query.execute": return { path: `${ws}/editors/${editorId}/executions`, method: "POST", body };
      case "query.fetchRows": return {
        path: `${ws}/editors/${editorId}/results/${encodeURIComponent(String(body.resultIndex ?? 0))}/page`,
        method: "POST", body
      };
      case "query.cancel": {
        const executionId = this.executions.get(String(body.editorId ?? "")) ?? "none";
        return { path: `${ws}/executions/${encodeURIComponent(executionId)}`, method: "DELETE" };
      }
      case "transaction.commit": return { path: `${ws}/editors/${editorId}/transaction/commit`, method: "POST", body: {} };
      case "transaction.rollback": return { path: `${ws}/editors/${editorId}/transaction/rollback`, method: "POST", body: {} };
      case "sql.format": return { path: `${ws}/sql/format`, method: "POST", body };
      case "sql.complete": return { path: `${ws}/sql/completions?prefix=${encodeURIComponent(String(body.prefix ?? ""))}&editorId=${encodeURIComponent(String(body.editorId ?? ""))}`, method: "GET" };
      case "history.list": return { path: `/api/v1/history?limit=${encodeURIComponent(String(body.limit ?? 200))}`, method: "GET" };
      case "settings.get": return { path: "/api/v1/settings", method: "GET" };
      case "settings.update": return { path: "/api/v1/settings", method: "PUT", body };
      case "csv.preview": return { path: `${ws}/csv/preview`, method: "POST", body };
      case "csv.import": return { path: `${ws}/csv/imports`, method: "POST", body };
      case "app.closeDecision": return body.allow
        ? { path: "/api/v1/shutdown", method: "POST", body: {} }
        : { path: `${ws}`, method: "PUT", body: {} };
      default: throw new Error(`尚未实现的本地 API：${type}`);
    }
  }

  private async fetchJson<T>(path: string, method: string, body?: unknown, timeoutMs = 30_000, authenticated = true): Promise<T> {
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), timeoutMs);
    try {
      const multipart = body instanceof FormData;
      const response = await fetch(path, {
        method,
        credentials: "same-origin",
        cache: "no-store",
        headers: multipart || body === undefined ? undefined : { "Content-Type": "application/json" },
        body: method === "GET" || body === undefined ? undefined : multipart ? body : JSON.stringify(body),
        signal: controller.signal
      });
      const data = response.status === 204 ? {} : await response.json().catch(() => ({}));
      if (!response.ok) {
        const failure = data as RpcError;
        const error = new Error(failure.message || `本地 API 请求失败（${response.status}）`);
        Object.assign(error, { code: failure.code, details: failure.details, requestId: failure.requestId });
        throw error;
      }
      return data as T;
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") throw new Error(`请求超时：${path}`);
      throw error;
    } finally {
      window.clearTimeout(timer);
    }
  }
}

let mockHandler: MockRequestHandler | undefined;
if (import.meta.env.DEV && new URLSearchParams(window.location.search).has("mock")) {
  const module = await import("./mock");
  mockHandler = module.developmentMockRequest;
}
if (import.meta.env.MODE === "test" && !mockHandler) mockHandler = async () => ({});

export const rpc = new RpcClient(mockHandler);
