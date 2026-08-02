import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { RpcClient } from "./rpc";

class FakeWebSocket {
  static instances: FakeWebSocket[] = [];
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSING = 2;
  static readonly CLOSED = 3;
  readyState = FakeWebSocket.CONNECTING;
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onerror: (() => void) | null = null;
  onclose: (() => void) | null = null;
  sent: string[] = [];

  constructor(readonly url: string) { FakeWebSocket.instances.push(this); }
  open(): void { this.readyState = FakeWebSocket.OPEN; this.onopen?.(); }
  message(type: string, payload: unknown = {}): void {
    this.onmessage?.({ data: JSON.stringify({ version: 1, type, payload }) });
  }
  send(value: string): void {
    if (this.readyState !== FakeWebSocket.OPEN) throw new Error("closed");
    this.sent.push(value);
  }
  close(): void {
    if (this.readyState === FakeWebSocket.CLOSED) return;
    this.readyState = FakeWebSocket.CLOSED;
    this.onclose?.();
  }
}

async function flushAsync(): Promise<void> {
  for (let index = 0; index < 8; index++) await Promise.resolve();
}

function jsonResponse(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json" } });
}

beforeEach(() => {
  window.sessionStorage.clear();
  window.history.replaceState(null, "", "/");
  FakeWebSocket.instances = [];
  vi.stubGlobal("WebSocket", FakeWebSocket);
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("RpcClient mock transport", () => {
  it("routes a request through the injected development transport", async () => {
    const transport = vi.fn(async (type: string, payload: Record<string, unknown>) => ({ type, payload }));
    const client = new RpcClient(transport);
    await expect(client.request("app.bootstrap", { ready: true })).resolves.toEqual({
      type: "app.bootstrap", payload: { ready: true }
    });
    expect(transport).toHaveBeenCalledOnce();
  });

  it("propagates transport errors", async () => {
    const client = new RpcClient(async () => { throw Object.assign(new Error("尚未连接数据库"), { code: "NOT_CONNECTED" }); });
    await expect(client.request("metadata.children")).rejects.toMatchObject({ message: "尚未连接数据库", code: "NOT_CONNECTED" });
  });

  it("dispatches event messages independently", async () => {
    const client = new RpcClient(async (_type, _payload, emit) => { emit("query.started", { executionId: "execution-1" }); return {}; });
    const listener = vi.fn();
    client.on("query.started", listener);
    await client.request("query.execute");
    expect(listener).toHaveBeenCalledWith({ executionId: "execution-1" });
  });
});

describe("RpcClient websocket recovery", () => {
  async function open(client: RpcClient): Promise<FakeWebSocket> {
    const opening = client.openWorkspace("workspace");
    await flushAsync();
    const socket = FakeWebSocket.instances.at(-1)!;
    socket.open(); socket.message("workspace.ready", { workspaceId: "workspace" });
    await opening;
    return socket;
  }

  it("does not create an event channel before a workspace is selected", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({})));
    const client = new RpcClient(); await client.ready();
    expect(FakeWebSocket.instances).toHaveLength(0);
    client.dispose();
  });

  it("probes the event channel and accepts pong as a healthy response", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => String(input).includes("/workspaces/")
      ? jsonResponse({ workspaceId: "workspace", recoveryDecisionRequired: false, editors: [] })
      : jsonResponse({})));
    const client = new RpcClient();
    const socket = await open(client);

    const operational = client.ensureOperational();
    await flushAsync();
    expect(socket.sent).toContain("ping");
    socket.message("workspace.pong", { serverTime: 1 });
    await expect(operational).resolves.toBeUndefined();
    expect(client.transportState).toBe("ready");
    client.dispose();
  });

  it("uses the exact execution id for cancellation and rejects a missing id locally", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => String(input).includes("/open")
      ? jsonResponse({ workspaceId: "workspace", recoveryDecisionRequired: false, editors: [] })
      : jsonResponse({ cancelled: true }));
    vi.stubGlobal("fetch", fetchMock);
    const client = new RpcClient();
    await open(client);

    await expect(client.request("query.cancel", {
      editorId: "editor-1", executionId: "execution-42"
    })).resolves.toEqual({ cancelled: true });
    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining("/executions/execution-42"),
      expect.objectContaining({ method: "DELETE" }));
    await expect(client.request("query.cancel", { editorId: "editor-1" }))
      .rejects.toThrow("尚未取得当前执行编号");
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes("/executions/none"))).toBe(false);
    client.dispose();
  });

  it("routes JDBC task manager operations with an optimistic state version", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => String(input).includes("/open")
      ? jsonResponse({ workspaceId: "workspace", recoveryDecisionRequired: false, editors: [] })
      : jsonResponse({ accepted: true, connections: [] }));
    vi.stubGlobal("fetch", fetchMock);
    const client = new RpcClient();
    await open(client);

    await client.request("jdbc.connections.list");
    await client.request("jdbc.connections.executions", { slotId: "slot/a" });
    await client.request("jdbc.connections.execution", { slotId: "slot/a", executionId: "execution/1" });
    await client.request("jdbc.connections.probe", { slotId: "slot/a", stateVersion: 12 });
    await client.request("jdbc.connections.abort", { slotId: "slot/a", stateVersion: 13 });
    await client.request("jdbc.connections.cleanup");

    expect(fetchMock).toHaveBeenCalledWith(expect.stringMatching(/\/api\/v1\/jdbc-connections$/),
      expect.objectContaining({ method: "GET" }));
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining("/jdbc-connections/slot%2Fa/executions/execution%2F1"),
      expect.objectContaining({ method: "GET" }));
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining("/jdbc-connections/slot%2Fa/probe"),
      expect.objectContaining({ method: "POST", body: JSON.stringify({ stateVersion: 12 }) }));
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining("/jdbc-connections/slot%2Fa/abort"),
      expect.objectContaining({ method: "POST", body: JSON.stringify({ stateVersion: 13 }) }));
    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining("/jdbc-connections/expired"),
      expect.objectContaining({ method: "DELETE" }));
    client.dispose();
  });

  it("routes SQL compaction through the active workspace", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => String(input).includes("/open")
      ? jsonResponse({ workspaceId: "workspace", recoveryDecisionRequired: false, editors: [] })
      : jsonResponse({ text: "SELECT 1" }));
    vi.stubGlobal("fetch", fetchMock);
    const client = new RpcClient();
    await open(client);

    await expect(client.request("sql.compact", {
      editorId: "editor-1",
      text: "SELECT\n1",
    })).resolves.toEqual({ text: "SELECT 1" });
    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining("/workspaces/workspace/sql/compact"),
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ editorId: "editor-1", text: "SELECT\n1" }),
      }));
    client.dispose();
  });

  it("routes result changes with the server-owned result index", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => String(input).includes("/open")
      ? jsonResponse({ workspaceId: "workspace", recoveryDecisionRequired: false, editors: [] })
      : jsonResponse({ resultChangesDirty: true }));
    vi.stubGlobal("fetch", fetchMock);
    const client = new RpcClient();
    await open(client);

    const body = {
      editorId: "editor-1", executionId: "execution-1", resultIndex: 3,
      rows: [{ rowIndex: 2, cells: [{ columnIndex: 1, value: "changed" }] }]
    };
    await expect(client.request("query.applyChanges", body))
      .resolves.toEqual({ resultChangesDirty: true });
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining("/editors/editor-1/results/3/changes"),
      expect.objectContaining({ method: "POST", body: JSON.stringify(body) }));
    client.dispose();
  });

  it("routes parameterized result change previews without moving values into the URL", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => String(input).includes("/open")
      ? jsonResponse({ workspaceId: "workspace", recoveryDecisionRequired: false, editors: [] })
      : jsonResponse({ previews: [{ operationId: "update:row-1", sql: "UPDATE t SET name = ? WHERE id = ?" }] }));
    vi.stubGlobal("fetch", fetchMock);
    const client = new RpcClient();
    await open(client);
    const body = { editorId: "editor-1", executionId: "execution-1", resultIndex: 4,
      operations: [{ operationId: "update:row-1", kind: "update", rowId: "row-1",
        values: [{ columnIndex: 1, value: { kind: "text", value: "secret-value" } }] }] };

    await client.request("query.previewChanges", body);

    const call = fetchMock.mock.calls.find(([input]) => String(input).includes("/changes/preview"));
    expect(call?.[0]).not.toContain("secret-value");
    expect(call?.[1]).toMatchObject({ method: "POST", body: JSON.stringify(body) });
    client.dispose();
  });

  it("routes batch large-value cloning with opaque row and draft sources", async () => {
    const response = { values: [{ cloneId: "draft:clone", columnIndex: 2,
      token: "new-token", size: 4, typeFamily: "blob" }] };
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => String(input).includes("/open")
      ? jsonResponse({ workspaceId: "workspace", recoveryDecisionRequired: false, editors: [] })
      : jsonResponse(response));
    vi.stubGlobal("fetch", fetchMock);
    const client = new RpcClient();
    await open(client);
    const sources = [
      { cloneId: "draft:clone", columnIndex: 2, source: { kind: "row" as const, rowId: "row-1" } },
      { cloneId: "draft:clone-2", columnIndex: 2,
        source: { kind: "draft" as const, token: "source-token" } }
    ];

    await expect(client.cloneResultLargeValues("editor-1", "execution-1", 3, sources)).resolves.toEqual(response);

    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining("/editors/editor-1/results/3/large-value-clones"),
      expect.objectContaining({ method: "POST", body: JSON.stringify({
        editorId: "editor-1", executionId: "execution-1", resultIndex: 3, sources
      }) }));
    client.dispose();
  });

  it("routes the confirmed connection import through the active workspace", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => String(input).includes("/open")
      ? jsonResponse({ workspaceId: "workspace", recoveryDecisionRequired: false, editors: [] })
      : jsonResponse({ createdProfiles: 1, updatedProfiles: 0 }));
    vi.stubGlobal("fetch", fetchMock);
    const client = new RpcClient();
    await open(client);

    const body = { rows: [{ rowId: "row-1", operation: "create" }] };
    await expect(client.request("connection.import.commit", body))
      .resolves.toEqual({ createdProfiles: 1, updatedProfiles: 0 });
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining("/workspaces/workspace/connection-imports"),
      expect.objectContaining({ method: "POST", body: JSON.stringify(body) }));
    client.dispose();
  });

  it("routes connection cloning without sending connection settings or passwords", async () => {
    const response = { profile: { id: "profile-copy", name: "订单库 - 副本" },
      passwordStatus: "copied" };
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => String(input).includes("/open")
      ? jsonResponse({ workspaceId: "workspace", recoveryDecisionRequired: false, editors: [] })
      : jsonResponse(response));
    vi.stubGlobal("fetch", fetchMock);
    const client = new RpcClient();
    await open(client);

    await expect(client.request("connection.profile.clone", { id: "profile-source" }))
      .resolves.toEqual(response);
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining("/workspaces/workspace/connection-profiles/profile-source/clone"),
      expect.objectContaining({ method: "POST", body: undefined }));
    client.dispose();
  });

  it("rejects a socket closed before opening and reconnects with exponential backoff", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => String(input).includes("/workspaces/")
      ? jsonResponse({ workspaceId: "workspace", recoveryDecisionRequired: false, editors: [] })
      : jsonResponse({}));
    vi.stubGlobal("fetch", fetchMock);
    const client = new RpcClient();
    const first = await open(client);
    first.close();
    await flushAsync();
    expect(client.transportState).toBe("reconnecting");

    await vi.advanceTimersByTimeAsync(250);
    await flushAsync();
    expect(FakeWebSocket.instances).toHaveLength(2);
    FakeWebSocket.instances[1].open();
    FakeWebSocket.instances[1].message("workspace.ready", {});
    await flushAsync();
    expect(client.transportState).toBe("ready");
    client.dispose();
  });

  it("closes a half-open channel after ten seconds and waits for its replacement", async () => {
    vi.useFakeTimers();
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => String(input).includes("/workspaces/")
      ? jsonResponse({ workspaceId: "workspace", recoveryDecisionRequired: false, editors: [] })
      : jsonResponse({})));
    const client = new RpcClient();
    const socket = await open(client);
    const operational = client.ensureOperational();
    await flushAsync();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(socket.readyState).toBe(FakeWebSocket.CLOSED);
    expect(client.transportState).toBe("reconnecting");
    await vi.advanceTimersByTimeAsync(250);
    await flushAsync();
    FakeWebSocket.instances[1].open();
    FakeWebSocket.instances[1].message("workspace.ready", {});
    await flushAsync();
    FakeWebSocket.instances[1].message("workspace.pong", {});
    await expect(operational).resolves.toBeUndefined();
    expect(client.transportState).toBe("ready");
    client.dispose();
  });
});
