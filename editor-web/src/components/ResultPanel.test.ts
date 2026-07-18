import { beforeEach, describe, expect, it, vi } from "vitest";
import { computed, defineComponent, nextTick } from "vue";
import { createPinia, setActivePinia } from "pinia";
import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import ResultPanel from "./ResultPanel.vue";
import { useQueryStore } from "../stores/query";
import { useSettingsStore } from "../stores/settings";
import type { Column } from "element-plus";
import type { VNode } from "vue";

describe("ResultPanel streaming rendering", () => {
  beforeEach(() => setActivePinia(createPinia()));

  it("renders metadata, batches and completion after immutable store updates", async () => {
    const queries = useQueryStore();
    const Harness = defineComponent({
      components: { ResultPanel },
      setup() { return { execution: computed(() => queries.executions["editor-1"]) }; },
      template: '<ResultPanel :execution="execution" :active-result-index="0" />'
    });
    const wrapper = mount(Harness, { global: { plugins: [ElementPlus] } });
    expect(wrapper.text()).toContain("执行查询后在这里查看结果");

    queries.start("editor-1", "execution-1");
    queries.addResult("editor-1", {
      resultIndex: 0, sql: "select 1", type: "QUERY", columns: ["value"], rows: [],
      updateCount: -1, truncated: false, durationMs: 0, complete: false
    });
    await nextTick();
    expect(wrapper.text()).toContain("结果 1");
    expect(wrapper.text()).toContain("正在执行");

    queries.appendRows("editor-1", 0, [["1"], ["2"]]);
    queries.completeResult("editor-1", 0, { durationMs: 7, truncated: false });
    queries.complete("editor-1", { durationMs: 8, failed: false, cancelled: false });
    await nextTick();
    expect(wrapper.text()).toContain("2 行 · 7 ms");
    expect(wrapper.text()).not.toContain("执行查询后在这里查看结果");
  });

  it("removes the local data toolbar and synchronizes the active result", async () => {
    const wrapper = mount(ResultPanel, {
      props: { activeResultIndex: 0, execution: {
        executionId: "execution-1", editorId: "editor-1", busy: false, cancelled: false, failed: false, durationMs: 8,
        results: [
          { resultIndex: 0, sql: "select 1", type: "QUERY", columns: ["id"], rows: [["1"]],
            updateCount: -1, truncated: true, durationMs: 7, complete: true },
          { resultIndex: 1, sql: "select 2", type: "QUERY", columns: ["id"], rows: [["2"]],
            updateCount: -1, truncated: false, durationMs: 8, complete: true }
        ]
      } },
      global: { plugins: [ElementPlus] }
    });
    expect(wrapper.find(".result-data-toolbar").exists()).toBe(false);
    await wrapper.findAll(".el-tabs__item")[1].trigger("click");
    expect(wrapper.emitted("update:active-result-index")?.[0]).toEqual([1]);
  });

  it("filters options by metadata and only renders selected columns in source order", async () => {
    const wrapper = mount(ResultPanel, {
      props: { activeResultIndex: 0, execution: {
        executionId: "execution-filter", editorId: "editor-1", busy: false, cancelled: false, failed: false, durationMs: 8,
        results: [{ resultIndex: 0, sql: "select * from sample", type: "QUERY",
          columns: ["id", "customer_name", "amount"],
          columnDetails: [
            { label: "id", name: "id", remarks: "订单编号", catalog: "db", schema: "", table: "sample", typeName: "BIGINT" },
            { label: "customer_name", name: "customer_name", remarks: "客户名称", catalog: "db", schema: "", table: "sample", typeName: "VARCHAR" },
            { label: "amount", name: "amount", remarks: "订单金额", catalog: "db", schema: "", table: "sample", typeName: "DECIMAL" }
          ],
          rows: [["1", "Apple", "12.30"]], updateCount: -1, truncated: false, durationMs: 7, complete: true }]
      } },
      global: { plugins: [ElementPlus] }
    });
    const select = wrapper.findComponent({ name: "ElSelect" });
    const table = wrapper.findComponent({ name: "ElTableV2" });
    expect((table.props("columns") as Array<{ title: string }>).map((column) => column.title))
      .toEqual(["id", "customer_name", "amount"]);

    (select.props("filterMethod") as (query: string) => void)("订单 金额");
    await nextTick();
    expect(wrapper.findAllComponents({ name: "ElOption" }).map((option) => option.props("label"))).toEqual(["amount"]);

    select.vm.$emit("update:modelValue", [2, 0]);
    await nextTick();
    expect((table.props("columns") as Array<{ title: string }>).map((column) => column.title)).toEqual(["id", "amount"]);
    select.vm.$emit("update:modelValue", []);
    await nextTick();
    expect((table.props("columns") as Array<{ title: string }>).map((column) => column.title))
      .toEqual(["id", "customer_name", "amount"]);
  });

  it("keeps selections per result, supports duplicate labels and resets for a new execution", async () => {
    const result = (resultIndex: number, rows: string[][]) => ({
      resultIndex, sql: "select a.id, b.id", type: "QUERY", columns: ["id", "id"],
      columnDetails: [
        { label: "id", name: "id", remarks: "主表编号", catalog: "db", schema: "", table: "a", typeName: "BIGINT" },
        { label: "id", name: "id", remarks: "明细编号", catalog: "db", schema: "", table: "b", typeName: "BIGINT" }
      ],
      rows, updateCount: -1, truncated: true, durationMs: 7, complete: true
    });
    const execution = {
      executionId: "execution-a", editorId: "editor-1", busy: false, cancelled: false,
      failed: false, durationMs: 8, results: [result(0, [["1", "11"]]), result(1, [["2", "22"]])]
    };
    const wrapper = mount(ResultPanel, {
      props: { activeResultIndex: 0, execution }, global: { plugins: [ElementPlus] }
    });

    let select = wrapper.findComponent({ name: "ElSelect" });
    select.vm.$emit("update:modelValue", [1]);
    await nextTick();
    let table = wrapper.findComponent({ name: "ElTableV2" });
    expect((table.props("columns") as Array<{ dataKey: number }>).map((column) => column.dataKey)).toEqual([1]);

    await wrapper.setProps({ activeResultIndex: 1 });
    select = wrapper.findComponent({ name: "ElSelect" });
    expect(select.props("modelValue")).toEqual([]);
    select.vm.$emit("update:modelValue", [0]);
    await wrapper.setProps({ activeResultIndex: 0 });
    expect(wrapper.findComponent({ name: "ElSelect" }).props("modelValue")).toEqual([1]);

    execution.results[0] = result(0, [["1", "11"], ["3", "33"]]);
    await wrapper.setProps({ execution: { ...execution, results: [...execution.results] } });
    table = wrapper.findComponent({ name: "ElTableV2" });
    expect(table.props("data")).toHaveLength(2);
    expect((table.props("columns") as Array<{ dataKey: number }>)[0].dataKey).toBe(1);

    await wrapper.setProps({ execution: { ...execution, executionId: "execution-b" } });
    await nextTick();
    expect(wrapper.findComponent({ name: "ElSelect" }).props("modelValue")).toEqual([]);
  });

  it("selects multiple headers, drags them as a group, resizes and restores editor layout", async () => {
    useSettingsStore().columnLayoutScope = "editor";
    const wrapper = mount(ResultPanel, {
      props: { activeResultIndex: 0, execution: {
        executionId: "execution-layout", editorId: "editor-1", busy: false, cancelled: false, failed: false, durationMs: 8,
        results: [{ resultIndex: 0, sql: "select id, name, amount", type: "QUERY",
          columns: ["id", "name", "amount"], rows: [["1", "Apple", "12.30"]],
          updateCount: -1, truncated: false, durationMs: 7, complete: true }]
      } }, global: { plugins: [ElementPlus] }
    });
    const table = () => wrapper.findComponent({ name: "ElTableV2" });
    const columns = () => table().props("columns") as Column[];
    const header = (index: number) => columns()[index].headerCellRenderer?.({} as never) as VNode;

    header(0).props?.onClick({ ctrlKey: false, metaKey: false, shiftKey: false });
    header(2).props?.onClick({ ctrlKey: true, metaKey: false, shiftKey: false });
    await nextTick();
    expect(String(header(0).props?.class)).toContain("selected");
    expect(String(header(2).props?.class)).toContain("selected");

    const dataTransfer = { effectAllowed: "", dropEffect: "", setData: vi.fn(), setDragImage: vi.fn() };
    header(0).props?.onDragstart({ dataTransfer, preventDefault: vi.fn() });
    const target = header(1);
    const dragEvent = { dataTransfer, clientX: 90, preventDefault: vi.fn(),
      currentTarget: { getBoundingClientRect: () => ({ left: 0, width: 100 }) } };
    target.props?.onDragover(dragEvent);
    target.props?.onDrop(dragEvent);
    await nextTick();
    expect(columns().map((column) => column.title)).toEqual(["name", "id", "amount"]);
    expect(wrapper.find('button[aria-label="复原列布局"]').exists()).toBe(true);

    const resizedHeader = header(0);
    const headerChildren = resizedHeader.children as VNode[];
    const resizeHandle = headerChildren[headerChildren.length - 1];
    resizeHandle.props?.onPointerdown({ clientX: 0, preventDefault: vi.fn(), stopPropagation: vi.fn() });
    const move = new Event("pointermove") as Event & { clientX: number };
    Object.defineProperty(move, "clientX", { value: 100 });
    window.dispatchEvent(move);
    window.dispatchEvent(new Event("pointerup"));
    await nextTick();
    expect(columns()[0].width).toBe(220);

    await wrapper.find('button[aria-label="复原列布局"]').trigger("click");
    await nextTick();
    expect(columns().map((column) => column.title)).toEqual(["id", "name", "amount"]);
    expect(columns()[1].width).toBe(120);
  });
});
