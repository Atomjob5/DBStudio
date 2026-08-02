import { afterEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessageBox } from "element-plus";
import JdbcTaskManagerDrawer from "./JdbcTaskManagerDrawer.vue";
import { rpc } from "../bridge/rpc";
import type { JdbcConnectionSnapshot } from "../types";

const now = Date.now();
const idle: JdbcConnectionSnapshot = {
  connectionId: "00000000-0000-0000-0000-00000000abcd", stateVersion: 4,
  profileId: "profile-1", profileName: "开发库", providerId: "mysql", state: "idle",
  transactionDirty: false, createdAt: now - 60_000, lastActiveAt: now - 1_000
};
const busy: JdbcConnectionSnapshot = {
  ...idle, connectionId: "00000000-0000-0000-0000-00000000busy", stateVersion: 7,
  profileName: "订单库", providerId: "oracle", state: "busy", editorId: "editor-1",
  editorTitle: "查询 1", executionId: "execution-1", transactionDirty: true
};

afterEach(() => vi.restoreAllMocks());

function mountDrawer(values: JdbcConnectionSnapshot[] = [idle, busy]) {
  vi.spyOn(rpc, "request").mockImplementation(async (type: string) => {
    if (type === "jdbc.connections.list") return { connections: values } as never;
    return {} as never;
  });
  return mount(JdbcTaskManagerDrawer, {
    props: { modelValue: true },
    global: { plugins: [ElementPlus], stubs: { teleport: true } }
  });
}

describe("JdbcTaskManagerDrawer", () => {
  it("shows the 760px workspace connection panel ordered by operational priority", async () => {
    const wrapper = mountDrawer();
    wrapper.findComponent({ name: "ElDrawer" }).vm.$emit("open");
    await flushPromises();

    expect(wrapper.findComponent({ name: "ElDrawer" }).props("size")).toBe("760px");
    expect(wrapper.findAll(".jdbc-connection-row")).toHaveLength(2);
    expect(wrapper.findAll(".jdbc-connection-row")[0].text()).toContain("订单库");
    expect(wrapper.text()).toContain("执行中");
    expect(wrapper.text()).toContain("未提交事务");
    expect(wrapper.text()).toContain("2 个连接");
  });

  it("only probes idle connections and carries the observed state version", async () => {
    const wrapper = mountDrawer();
    wrapper.findComponent({ name: "ElDrawer" }).vm.$emit("open");
    await flushPromises();
    const request = vi.mocked(rpc.request);
    request.mockResolvedValueOnce({ connection: { ...idle, stateVersion: 5, lastProbeLatencyMs: 9 } } as never);

    expect(wrapper.get('[aria-label="探活 订单库"]').attributes("disabled")).toBeDefined();
    await wrapper.get('[aria-label="探活 开发库"]').trigger("click");
    await flushPromises();
    expect(request).toHaveBeenCalledWith("jdbc.connections.probe", {
      connectionId: idle.connectionId, stateVersion: 4
    }, 5_000);
    expect(wrapper.text()).toContain("探活 9 ms");
  });

  it("warns about losing the whole transaction before aborting a busy connection", async () => {
    const wrapper = mountDrawer();
    wrapper.findComponent({ name: "ElDrawer" }).vm.$emit("open");
    await flushPromises();
    const confirm = vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    const request = vi.mocked(rpc.request);
    request.mockResolvedValueOnce({ accepted: true, connection: { ...busy, state: "aborting", stateVersion: 8 } } as never);

    await wrapper.get('[aria-label="强制断开 订单库"]').trigger("click");
    await flushPromises();
    expect(confirm.mock.calls[0][0]).toContain("全部未提交修改");
    expect(request).toHaveBeenCalledWith("jdbc.connections.abort", {
      connectionId: busy.connectionId, stateVersion: 7
    });
    expect(wrapper.text()).toContain("正在断开");
  });
});
