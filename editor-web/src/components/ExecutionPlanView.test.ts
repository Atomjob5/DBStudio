import { describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import ExecutionPlanView from "./ExecutionPlanView.vue";
import type { ExecutionPlan } from "../types";
const copy = vi.hoisted(() => vi.fn(async () => {}));
vi.mock("../clipboard", () => ({ writeClipboardText: copy }));

function plan(): ExecutionPlan {
  return { sql: "SELECT * FROM t", providerId: "mysql", rawText: "full original plan", warning: "", nodes: [
    { id: "0", parentId: null, operation: "nested_loop", object: null, access: null, index: null,
      estimatedRows: null, cost: null, condition: null, details: {} },
    { id: "1", parentId: "0", operation: "table", object: "t", access: "ALL", index: null,
      estimatedRows: "0", cost: "12.3", condition: "t.id > 1", details: { filter: "t.id > 1" } }] };
}
describe("execution plan view", () => {
  it("shows zero separately from missing metrics and preserves expansion and selection across remounts", async () => {
    const value = plan();
    const wrapper = mount(ExecutionPlanView, { props: { plan: value }, global: { plugins: [ElementPlus] } });
    expect(wrapper.findAll("tbody tr")).toHaveLength(2);
    expect(wrapper.text()).toContain("全表扫描");
    expect(wrapper.text()).toContain("嵌套循环");
    expect(wrapper.findAll("tbody tr")[0].text()).toContain("—");
    expect(wrapper.findAll("tbody tr")[1].findAll("td")[4].text()).toBe("0");
    await wrapper.findAll("tbody tr")[1].trigger("click");
    expect(wrapper.get('[aria-label="算子详情"]').text()).toContain("t.id > 1");
    await wrapper.get('[aria-label="折叠 nested_loop"]').trigger("click");
    expect(wrapper.findAll("tbody tr")).toHaveLength(1);
    wrapper.unmount();
    const reopened = mount(ExecutionPlanView, { props: { plan: value }, global: { plugins: [ElementPlus] } });
    expect(reopened.findAll("tbody tr")).toHaveLength(1);
    expect(reopened.get('[aria-label="算子详情"]').text()).toContain("t.id > 1");
    reopened.unmount();
  });
  it("falls back to original text and copies it without truncation", async () => {
    const value = { ...plan(), nodes: [], warning: "无法解析计划格式" };
    const wrapper = mount(ExecutionPlanView, { props: { plan: value }, global: { plugins: [ElementPlus] } });
    expect(wrapper.get("pre").text()).toBe(value.rawText);
    expect(wrapper.text()).toContain(value.warning);
    await wrapper.findAll("button").find(button => button.text() === "复制完整计划")!.trigger("click");
    expect(copy).toHaveBeenCalledWith(value.rawText);
    wrapper.unmount();
  });
});
