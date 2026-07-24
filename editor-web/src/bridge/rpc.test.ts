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
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => String(input).includes("/open")
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
