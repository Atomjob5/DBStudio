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
    expect(wrapper.get(".status-execution-zone").text()).toContain("执行完成 · 12 ms");
    expect(wrapper.get(".status-execution-zone").text()).toContain("已选中 3 行");
    expect(wrapper.get(".status-execution-zone").text()).not.toContain("自动提交关闭");
    expect(wrapper.get(".status-result-zone").find('[aria-label="下一页数据"]').exists()).toBe(true);
    expect(wrapper.get(".status-system-zone").text()).toContain("链接正常 · 自动提交关闭");
    await wrapper.setProps({ resultContentOffset: 56 });
    expect(wrapper.get(".status-bar").attributes("style")).toContain("--status-result-offset: 240px");
    await wrapper.setProps({ resultContentOffset: 0 });
    expect(wrapper.get(".status-bar").classes()).toContain("alignment-pending");
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
