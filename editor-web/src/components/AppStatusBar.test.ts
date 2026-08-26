import { afterEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import AppStatusBar from "./AppStatusBar.vue";
import type { StatusBarSystemItem } from "../types";

const clipboardWrite = vi.hoisted(() => vi.fn(() => Promise.resolve()));
vi.mock("../clipboard", () => ({ writeClipboardText: clipboardWrite }));

const baseProps = {
  executionText: "执行完成 · 12 ms",
  busy: false,
  selectedRowCount: 0,
  resultContentOffset: 312,
  showSelectedColumnRemarks: true,
  systemItems: [] as StatusBarSystemItem[],
  canLoadMore: false,
  canAutoRefresh: false,
  autoRefreshEnabled: false,
  autoRefreshIntervalSeconds: 10,
  autoRefreshTooltip: "定时刷新已关闭 · 每 10 秒",
  nextPageTooltip: "没有更多数据",
  allRowsTooltip: "没有更多数据"
};

describe("AppStatusBar", () => {
  afterEach(() => { vi.useRealTimers(); clipboardWrite.mockClear(); });

  it("keeps SQL state on the left, result controls in the aligned center and system state on the right", async () => {
    const wrapper = mount(AppStatusBar, { props: { ...baseProps, selectedRowCount: 3,
      systemItems: [{ id: "session", label: "数据库会话", message: "链接正常 · 自动提交关闭",
        tone: "success", updatedAt: 1 }] },
    global: { plugins: [ElementPlus], stubs: { teleport: true } } });
    expect(wrapper.get(".status-bar").attributes("style")).toContain("--status-result-offset: 312px");
    expect(wrapper.get(".status-execution-zone").text()).toBe("已选中 3 行");
    expect(wrapper.find(".selected-row-count").exists()).toBe(false);
    expect(wrapper.get(".status-execution-zone").text()).not.toContain("自动提交关闭");
    expect(wrapper.get(".status-result-zone").find('[aria-label="下一页数据"]').exists()).toBe(true);
    expect(wrapper.get(".status-system-zone").text()).toContain("链接正常 · 自动提交关闭");
    await wrapper.setProps({ resultContentOffset: 56 });
    expect(wrapper.get(".status-bar").attributes("style")).toContain("--status-result-offset: 240px");
    await wrapper.setProps({ resultContentOffset: 0 });
    expect(wrapper.get(".status-bar").classes()).toContain("alignment-pending");
    wrapper.unmount();
  });

  it("prioritizes running state, then selection, then execution duration", async () => {
    const wrapper = mount(AppStatusBar, {
      props: { ...baseProps, selectedRowCount: 3, busy: true, executionText: "正在加载下一页数据…" },
      global: { plugins: [ElementPlus], stubs: { teleport: true } }
    });
    expect(wrapper.get(".execution-status").text()).toBe("正在加载下一页数据…");
    expect(wrapper.get(".execution-status").find(".is-loading").exists()).toBe(true);

    await wrapper.setProps({ busy: false });
    expect(wrapper.get(".execution-status").text()).toBe("已选中 3 行");
    expect(wrapper.get(".execution-status").find(".is-loading").exists()).toBe(false);

    await wrapper.setProps({ selectedRowCount: 0 });
    expect(wrapper.get(".execution-status").text()).toBe("正在加载下一页数据…");
    wrapper.unmount();
  });

  it("keeps result editing actions out of the global status bar", () => {
    const wrapper = mount(AppStatusBar, {
      props: baseProps,
      global: { plugins: [ElementPlus], stubs: { teleport: true } }
    });
    expect(wrapper.find('[aria-label="切换结果编辑模式"]').exists()).toBe(false);
    expect(wrapper.find('[aria-label="应用更改"]').exists()).toBe(false);
  });

  it("places auto refresh before next page and keeps left click separate from the context menu", async () => {
    const wrapper = mount(AppStatusBar, {
      props: { ...baseProps, canAutoRefresh: true },
      global: { plugins: [ElementPlus], stubs: { teleport: true } }
    });
    const toolbarButtons = wrapper.get('[aria-label="结果操作工具栏"]').findAll("button");
    expect(toolbarButtons[0].attributes("aria-label")).toBe("切换定时刷新");
    expect(toolbarButtons[1].attributes("aria-label")).toBe("下一页数据");
    expect(toolbarButtons[2].attributes("aria-label")).toBe("获取全部数据");

    await toolbarButtons[0].trigger("click");
    expect(wrapper.emitted("toggle-auto-refresh")).toHaveLength(1);
    expect(wrapper.find('[role="menu"]').exists()).toBe(false);

    await wrapper.get(".auto-refresh-trigger").trigger("contextmenu");
    expect(wrapper.get('[role="menu"]').text()).toContain("5 秒");
    expect(wrapper.get('[role="menu"]').text()).toContain("自定义");
    expect(wrapper.emitted("toggle-auto-refresh")).toHaveLength(1);
    await wrapper.findAll('[role="menuitem"]')[2].trigger("click");
    expect(wrapper.emitted("update-auto-refresh-interval")?.at(-1)).toEqual([30]);
    wrapper.unmount();
  });

  it("shows and announces the enabled state", () => {
    const wrapper = mount(AppStatusBar, {
      props: { ...baseProps, canAutoRefresh: true, autoRefreshEnabled: true,
        autoRefreshIntervalSeconds: 60, autoRefreshTooltip: "定时刷新已开启 · 每 60 秒" },
      global: { plugins: [ElementPlus], stubs: { teleport: true } }
    });
    const button = wrapper.get('[aria-label="切换定时刷新"]');
    expect(button.attributes("aria-pressed")).toBe("true");
    expect(button.classes()).toContain("is-auto-refresh-enabled");
    wrapper.unmount();
  });

  it("shows an ellipsized remark without hover metadata and opens the full dialog only on explicit activation", async () => {
    const remarks = "很长的字段备注".repeat(30);
    const wrapper = mount(AppStatusBar, { props: { ...baseProps, selectedColumn: {
      label: "订单编号", name: "id", remarks, typeName: "BIGINT", catalog: "sales", schema: "", table: "orders"
    } }, global: { plugins: [ElementPlus], stubs: { teleport: true } } });
    const button = wrapper.get(".selected-column-remarks");
    expect(button.attributes("title")).toBeUndefined();
    expect(wrapper.find(".column-remarks-detail").exists()).toBe(false);

    await button.trigger("dblclick");
    expect(wrapper.text()).toContain("sales.orders.id");
    expect(wrapper.text()).toContain(remarks);
    await wrapper.findAll("button").find((value) => value.text().includes("复制备注"))!.trigger("click");
    await flushPromises();
    expect(clipboardWrite).toHaveBeenCalledWith(remarks);
    wrapper.unmount();
  });

  it("hides selected remarks when disabled and supports keyboard activation", async () => {
    const wrapper = mount(AppStatusBar, { props: { ...baseProps, showSelectedColumnRemarks: false, selectedColumn: {
      label: "id", name: "id", remarks: "订单编号", typeName: "BIGINT", catalog: "sales", schema: "", table: "orders"
    } }, global: { plugins: [ElementPlus], stubs: { teleport: true } } });
    expect(wrapper.find(".selected-column-remarks").exists()).toBe(false);
    await wrapper.setProps({ showSelectedColumnRemarks: true });
    await wrapper.get(".selected-column-remarks").trigger("keydown", { key: "Enter" });
    expect(wrapper.text()).toContain("完整路径");
    wrapper.unmount();
  });

  it("shows a selected field remark that is filled after the result is rendered", async () => {
    const selectedColumn = {
      label: "ID", name: "ID", remarks: "", typeName: "NUMBER",
      catalog: "", schema: "", table: ""
    };
    const wrapper = mount(AppStatusBar, {
      props: { ...baseProps, selectedColumn },
      global: { plugins: [ElementPlus], stubs: { teleport: true } }
    });
    expect(wrapper.find(".selected-column-remarks").exists()).toBe(false);

    await wrapper.setProps({ selectedColumn: { ...selectedColumn, remarks: "客户编号" } });
    expect(wrapper.get(".selected-column-remarks").text()).toBe("客户编号");
    wrapper.unmount();
  });

  it("rotates multiple system items every three seconds and pauses while the popover is shown", async () => {
    vi.useFakeTimers();
    const systemItems: StatusBarSystemItem[] = [
      { id: "one", label: "导入一", message: "任务一", tone: "running", updatedAt: 1 },
      { id: "two", label: "导入二", message: "任务二", tone: "running", updatedAt: 2 }
    ];
    const wrapper = mount(AppStatusBar, { props: { ...baseProps, systemItems },
      global: { plugins: [ElementPlus], stubs: { teleport: true } } });
    expect(wrapper.get(".system-status-summary").text()).toContain("任务二");
    vi.advanceTimersByTime(3_000); await wrapper.vm.$nextTick();
    expect(wrapper.get(".system-status-summary").text()).toContain("任务一");

    wrapper.findComponent({ name: "ElPopover" }).vm.$emit("show");
    await wrapper.vm.$nextTick();
    vi.advanceTimersByTime(6_000); await wrapper.vm.$nextTick();
    expect(wrapper.get(".system-status-summary").text()).toContain("任务一");
    expect(wrapper.findAll(".status-system-row")).toHaveLength(2);
    wrapper.unmount();
  });
});
