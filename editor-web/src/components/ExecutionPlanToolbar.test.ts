import { afterEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import ExecutionPlanToolbar from "./ExecutionPlanToolbar.vue";
import { planViewState } from "../planViewState";
import type { ExecutionPlan } from "../types";

const copy = vi.hoisted(() => vi.fn(async () => {}));
vi.mock("../clipboard", () => ({ writeClipboardText: copy }));

function plan(): ExecutionPlan {
  return {
    sql: "SELECT * FROM t", providerId: "mysql", rawText: "original plan", warning: "",
    nodes: [
      { id: "0", parentId: null, operation: "query_block", object: null, access: null, index: null,
        estimatedRows: null, cost: null, condition: null, details: {} },
      { id: "1", parentId: "0", operation: "table", object: "t", access: "ALL", index: null,
        estimatedRows: "1", cost: "1", condition: null, details: {} },
    ],
  };
}

afterEach(() => { copy.mockClear(); });

describe("execution plan toolbar", () => {
  it("shows the two tree actions only in tree mode and reuses the 1000ms tooltip delay", async () => {
    const value = plan();
    const wrapper = mount(ExecutionPlanToolbar, { props: { plan: value }, global: { plugins: [ElementPlus] } });
    expect(wrapper.find(".execution-plan-burst").exists()).toBe(true);
    expect(wrapper.findAll("button").map(button => button.attributes("aria-label"))).toEqual([
      "展开全部", "折叠全部", "切换到原始文本", "复制完整计划",
    ]);
    expect(wrapper.findAllComponents({ name: "IconTooltip" }).every(item => item.props("showAfter") === 1000)).toBe(true);
    expect(wrapper.get('button[aria-label="切换到原始文本"]').attributes("aria-pressed")).toBe("true");

    planViewState(value).collapsed = new Set(["0", "1"]);
    await wrapper.get('button[aria-label="展开全部"]').trigger("click");
    expect(planViewState(value).collapsed.size).toBe(0);
    await wrapper.get('button[aria-label="折叠全部"]').trigger("click");
    expect(planViewState(value).collapsed).toEqual(new Set(["0", "1"]));

    await wrapper.get('button[aria-label="切换到原始文本"]').trigger("click");
    expect(planViewState(value).mode).toBe("text");
    expect(wrapper.get('button[aria-label="切换到树形表格"]').attributes("aria-pressed")).toBe("false");
    await wrapper.get('button[aria-label="切换到树形表格"]').trigger("click");
    expect(planViewState(value).mode).toBe("tree");
  });

  it("copies the full raw plan from the stable header action", async () => {
    const value = plan();
    const wrapper = mount(ExecutionPlanToolbar, { props: { plan: value }, global: { plugins: [ElementPlus] } });
    await wrapper.get('button[aria-label="复制完整计划"]').trigger("click");
    expect(copy).toHaveBeenCalledWith(value.rawText);
  });
});
