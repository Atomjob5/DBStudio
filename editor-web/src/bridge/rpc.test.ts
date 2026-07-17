import { describe, expect, it, vi } from "vitest";
import { RpcClient } from "./rpc";

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
