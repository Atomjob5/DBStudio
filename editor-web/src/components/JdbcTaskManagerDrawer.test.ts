import { afterEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessageBox } from "element-plus";
import JdbcTaskManagerDrawer from "./JdbcTaskManagerDrawer.vue";
import { rpc } from "../bridge/rpc";
import type { JdbcConnectionSlotSnapshot, JdbcExecutionSummary } from "../types";

const now = Date.now();
const connected: JdbcConnectionSlotSnapshot = {
  slotId: "slot-1", slotNumber: 1, stateVersion: 4, state: "idle", physicalConnected: true,
  overLimit: false, historyCount: 1, connectionId: "00000000-0000-0000-0000-00000000abcd",
  profileId: "profile-1", profileName: "开发库", providerId: "mysql", databaseName: "orders",
  workspaceId: "workspace-1", workspaceName: "订单工作区", editorId: "editor-1", editorTitle: "查询 1",
  transactionDirty: false, createdAt: now - 60_000, lastActiveAt: now - 1_000,
  lastExecutionAt: now - 2_000
};
const empty = (number: number): JdbcConnectionSlotSnapshot => ({
  slotId: `slot-${number}`, slotNumber: number, stateVersion: 1, state: "idle",
  physicalConnected: false, overLimit: false, historyCount: 0, transactionDirty: false
});
const busy: JdbcConnectionSlotSnapshot = {
  ...connected, slotId: "slot-2", slotNumber: 2, stateVersion: 7, state: "busy",
  profileName: "Oracle订单库", providerId: "oracle", databaseName: "FREEPDB1", schemaName: "CBSAC",
  executionId: "execution-1", transactionDirty: true
};
const execution: JdbcExecutionSummary = {
  executionId: "execution-1", workspaceId: "workspace-1", workspaceName: "订单工作区",
  editorId: "editor-1", editorTitle: "查询 1", profileId: "profile-1", profileName: "开发库",
  providerId: "mysql", databaseName: "orders", startedAt: now - 2_000, completedAt: now - 1_950,
  durationMs: 50, status: "success", rowCount: 1
};

afterEach(() => vi.restoreAllMocks());

function mountDrawer(values: JdbcConnectionSlotSnapshot[] = [connected, busy, ...Array.from({ length: 8 }, (_, index) => empty(index + 3))]) {
  vi.spyOn(rpc, "request").mockImplementation(async (type: string) => {
    if (type === "jdbc.connections.list") return {
      maximum: 10, activeCount: 2, overLimitCount: 0, slots: values, generatedAt: now
    } as never;
    if (type === "jdbc.connections.executions") return { executions: [execution] } as never;
    if (type === "jdbc.connections.execution") return {
      execution: { ...execution, sql: "select * from orders for update" }
    } as never;
    return {} as never;
  });
  return mount(JdbcTaskManagerDrawer, {
    props: { modelValue: true }, global: { plugins: [ElementPlus], stubs: { teleport: true } }
  });
}

describe("JdbcTaskManagerDrawer", () => {
  it("shows ten stable lazy slots in number order and disables physical actions for empty slots", async () => {
    const wrapper = mountDrawer();
    wrapper.findComponent({ name: "ElDrawer" }).vm.$emit("open");
    await flushPromises();

    expect(wrapper.findComponent({ name: "ElDrawer" }).props("size")).toBe("760px");
    expect(wrapper.findAll(".jdbc-slot-row")).toHaveLength(10);
    expect(wrapper.findAll(".jdbc-slot-number").map((item) => item.text()).slice(0, 3))
      .toEqual(["线程 01", "线程 02", "线程 03"]);
    expect(wrapper.text()).toContain("10 个线程");
    expect(wrapper.text()).toContain("2 个已连接");
    expect(wrapper.text()).toContain("订单工作区");
    expect(wrapper.text()).toContain("最近执行");
    expect(wrapper.get('[aria-label="探活线程 3"]').attributes("disabled")).toBeDefined();
    expect(wrapper.get('[aria-label="强制断开线程 3"]').attributes("disabled")).toBeDefined();
  });

  it("probes only a connected idle slot with the slot state version", async () => {
    const wrapper = mountDrawer();
    wrapper.findComponent({ name: "ElDrawer" }).vm.$emit("open"); await flushPromises();
    const request = vi.mocked(rpc.request);
    request.mockResolvedValueOnce({ slot: { ...connected, stateVersion: 5, lastProbeLatencyMs: 9 } } as never);

    expect(wrapper.get('[aria-label="探活线程 2"]').attributes("disabled")).toBeDefined();
    await wrapper.get('[aria-label="探活线程 1"]').trigger("click"); await flushPromises();
    expect(request).toHaveBeenCalledWith("jdbc.connections.probe", {
      slotId: connected.slotId, stateVersion: 4
    }, 5_000);
    expect(wrapper.text()).toContain("探活 9 ms");
  });

  it("warns before aborting a busy slot and carries the slot version", async () => {
    const wrapper = mountDrawer();
    wrapper.findComponent({ name: "ElDrawer" }).vm.$emit("open"); await flushPromises();
    const confirm = vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    const request = vi.mocked(rpc.request);
    request.mockResolvedValueOnce({ accepted: true, slot: { ...busy, state: "aborting", stateVersion: 8 } } as never);

    await wrapper.get('[aria-label="强制断开线程 2"]').trigger("click"); await flushPromises();
    expect(confirm.mock.calls[0][0]).toContain("全部未提交修改");
    expect(request).toHaveBeenCalledWith("jdbc.connections.abort", {
      slotId: busy.slotId, stateVersion: 7
    });
    expect(wrapper.text()).toContain("正在断开");
  });

  it("loads recent executions on expansion, opens full SQL and clears expired data", async () => {
    const wrapper = mountDrawer();
    wrapper.findComponent({ name: "ElDrawer" }).vm.$emit("open"); await flushPromises();
    await wrapper.get('[aria-label="展开线程 1"]').trigger("click"); await flushPromises();
    expect(vi.mocked(rpc.request)).toHaveBeenCalledWith("jdbc.connections.executions", { slotId: "slot-1" });
    expect(wrapper.text()).toContain("查询 1");
    expect(wrapper.text()).toContain("50 ms");

    await wrapper.get('[aria-label="查看SQL execution-1"]').trigger("click"); await flushPromises();
    expect(vi.mocked(rpc.request)).toHaveBeenCalledWith("jdbc.connections.execution", {
      slotId: "slot-1", executionId: "execution-1"
    });
    expect(wrapper.get('[aria-label="完整SQL内容"]').text()).toContain("select * from orders for update");

    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    vi.mocked(rpc.request).mockResolvedValueOnce({ clearedExecutions: 1 } as never);
    await wrapper.get('[aria-label="清理过期数据"]').trigger("click"); await flushPromises();
    expect(vi.mocked(rpc.request)).toHaveBeenCalledWith("jdbc.connections.cleanup");
  });
});
