import { beforeEach, describe, expect, it, vi } from "vitest";
import { computed, defineComponent, nextTick } from "vue";
import { createPinia, setActivePinia } from "pinia";
import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import ResultPanel from "./ResultPanel.vue";
import { useAppStore } from "../stores/app";
import { useQueryStore } from "../stores/query";
import { useSettingsStore } from "../stores/settings";
import type { Column } from "element-plus";
import type { VNode } from "vue";

const clipboardWrite = vi.hoisted(() => vi.fn(() => Promise.resolve()));
vi.mock("../clipboard", () => ({ writeClipboardText: clipboardWrite }));

describe("ResultPanel streaming rendering", () => {
  beforeEach(() => { setActivePinia(createPinia()); clipboardWrite.mockClear(); });

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

  it("shows the SQL animation until the first result metadata arrives", async () => {
    const app = useAppStore();
    const previousExecution = {
      executionId: "execution-previous", editorId: "editor-1", busy: false, cancelled: false,
      failed: false, durationMs: 8,
      results: [{ resultIndex: 0, sql: "select 1", type: "QUERY", columns: ["value"], rows: [["1"]],
        updateCount: -1, truncated: false, durationMs: 7, complete: true }]
    };
    const wrapper = mount(ResultPanel, {
      props: { activeResultIndex: 0, execution: previousExecution, executing: true },
      global: { plugins: [ElementPlus] }
    });

    const loading = wrapper.get(".result-loading");
    expect(loading.attributes("role")).toBe("status");
    expect(loading.attributes("aria-live")).toBe("polite");
    expect(loading.text()).toContain("正在执行 SQL");
    expect(loading.get("img").attributes()).toMatchObject({
      src: "/assets/branding/dbstudio-sql-loading-v4.webp",
      alt: "",
      "aria-hidden": "true"
    });
    app.setThemePreference("dark");
    await nextTick();
    expect(loading.get("img").attributes("src"))
      .toBe("/assets/branding/dbstudio-sql-loading-v4-dark.webp");
    app.setThemePreference("light");
    await nextTick();
    expect(loading.get("img").attributes("src"))
      .toBe("/assets/branding/dbstudio-sql-loading-v4.webp");
    expect(wrapper.findComponent({ name: "ElTableV2" }).exists()).toBe(false);
    expect(wrapper.text()).not.toContain("执行查询后在这里查看结果");

    await wrapper.setProps({
      execution: {
        executionId: "execution-new", editorId: "editor-1", busy: true, cancelled: false,
        failed: false, durationMs: 0, results: []
      }
    });
    expect(wrapper.find(".result-loading").exists()).toBe(true);

    await wrapper.setProps({
      execution: {
        executionId: "execution-new", editorId: "editor-1", busy: true, cancelled: false,
        failed: false, durationMs: 0,
        results: [{ resultIndex: 0, sql: "select 2", type: "QUERY", columns: ["value"], rows: [],
          updateCount: -1, truncated: false, durationMs: 0, complete: false }]
      }
    });
    expect(wrapper.find(".result-loading").exists()).toBe(false);
    expect(wrapper.text()).toContain("结果 1");
    expect(wrapper.text()).toContain("正在执行");
    expect(wrapper.findComponent({ name: "ElTableV2" }).exists()).toBe(true);

    await wrapper.setProps({
      executing: false,
      execution: {
        executionId: "execution-new", editorId: "editor-1", busy: false, cancelled: true,
        failed: false, durationMs: 1, results: []
      }
    });
    expect(wrapper.find(".result-loading").exists()).toBe(false);
    expect(wrapper.text()).toContain("执行查询后在这里查看结果");
  });

  it("keeps the legacy table by default and switches to the optimized grid without losing data", async () => {
    const settings = useSettingsStore();
    const execution = {
      executionId: "execution-scroll", editorId: "editor-1", busy: false, cancelled: false,
      failed: false, durationMs: 8,
      results: [{ resultIndex: 0, sql: "select id, name", type: "QUERY", columns: ["id", "name"],
        rows: [["1", "Apple"], ["2", "Banana"]], updateCount: -1, truncated: false, durationMs: 7, complete: true }]
    };
    const wrapper = mount(ResultPanel, {
      props: { activeResultIndex: 0, execution }, global: { plugins: [ElementPlus] }
    });
    expect(wrapper.findComponent({ name: "ElTableV2" }).exists()).toBe(true);
    expect(wrapper.findComponent({ name: "ResultVirtualGrid" }).exists()).toBe(false);

    settings.scrollOptimizationEnabled = true;
    await nextTick();
    expect(wrapper.findComponent({ name: "ElTableV2" }).exists()).toBe(false);
    const grid = wrapper.findComponent({ name: "ResultVirtualGrid" });
    expect(grid.exists()).toBe(true);
    expect(grid.props("rows")).toHaveLength(2);
    expect(grid.props("bufferScreens")).toBe(1);

    settings.scrollOptimizationBufferScreens = 1.5;
    await nextTick();
    expect(grid.props("bufferScreens")).toBe(1.5);

    settings.scrollOptimizationEnabled = false;
    await nextTick();
    expect(wrapper.findComponent({ name: "ElTableV2" }).exists()).toBe(true);
    expect(wrapper.text()).toContain("2 行 · 7 ms");
    wrapper.unmount();
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

  it("labels historical results and disables server export", async () => {
    const wrapper = mount(ResultPanel, {
      props: { activeResultIndex: 0, execution: {
        executionId: "historical", editorId: "editor-1", busy: false, cancelled: false, failed: false,
        durationMs: 8, historical: true,
        results: [{ resultIndex: 0, sql: "select 1", type: "QUERY", columns: ["id"], rows: [["1"]],
          updateCount: -1, truncated: true, durationMs: 7, complete: true }]
      } }, global: { plugins: [ElementPlus] }
    });
    expect(wrapper.text()).toContain("断线前快照");
    expect(wrapper.findComponent({ name: "ElDropdown" }).props("disabled")).toBe(true);
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
      .toEqual(["#", "id", "customer_name", "amount"]);

    (select.props("filterMethod") as (query: string) => void)("订单 金额");
    await nextTick();
    expect(wrapper.findAllComponents({ name: "ElOption" }).map((option) => option.props("label"))).toEqual(["amount"]);

    select.vm.$emit("update:modelValue", [2, 0]);
    await nextTick();
    expect((table.props("columns") as Array<{ title: string }>).map((column) => column.title)).toEqual(["#", "id", "amount"]);
    select.vm.$emit("update:modelValue", []);
    await nextTick();
    expect((table.props("columns") as Array<{ title: string }>).map((column) => column.title))
      .toEqual(["#", "id", "customer_name", "amount"]);
  });

  it("views one selected record vertically with every source column and disables for cross-row selections", async () => {
    const result = {
      resultIndex: 0, sql: "select id, name, note from sample", type: "QUERY",
      columns: ["id", "name", "note"],
      columnDetails: [
        { label: "id", name: "id", remarks: "编号", catalog: "db", schema: "", table: "sample", typeName: "NUMBER" },
        { label: "name", name: "name", remarks: "名称", catalog: "db", schema: "", table: "sample", typeName: "VARCHAR2" },
        { label: "note", name: "note", remarks: "备注", catalog: "db", schema: "", table: "sample", typeName: "VARCHAR2" }
      ],
      rows: [["1", "Apple", null], ["2", "Banana", "yellow"]],
      updateCount: -1, truncated: false, durationMs: 7, complete: true
    };
    const execution = {
      executionId: "execution-single-record", editorId: "editor-1", busy: false, cancelled: false,
      failed: false, durationMs: 8, results: [result]
    };
    const wrapper = mount(ResultPanel, {
      props: { activeResultIndex: 0, execution }, global: { plugins: [ElementPlus] }
    });
    const button = () => wrapper.get("button.single-record-button");
    const table = () => wrapper.findComponent({ name: "ElTableV2" });
    const columns = () => table().props("columns") as Column[];
    const rows = () => table().props("data") as Array<{ sourceIndex: number; cells: Array<string | null> }>;
    const cell = (row: number, column: number) =>
      columns()[column + 1].cellRenderer?.({ rowData: rows()[row], rowIndex: row } as never) as VNode;

    expect(button().attributes("disabled")).toBeDefined();
    expect(button().attributes("aria-label")).toBe("单个记录查看");
    expect(button().text()).toBe("");
    expect(button().find("svg").exists()).toBe(true);
    const select = wrapper.findComponent({ name: "ElSelect" });
    select.vm.$emit("update:modelValue", [1]);
    await nextTick();
    cell(0, 0).props?.onPointerdown({
      button: 0, preventDefault: vi.fn(), ctrlKey: false, metaKey: false, shiftKey: false
    });
    window.dispatchEvent(new Event("pointerup"));
    await nextTick();
    expect(button().attributes("disabled")).toBeUndefined();

    await button().trigger("click");
    await nextTick();
    expect(table().exists()).toBe(false);
    const singleRecord = wrapper.findComponent({ name: "ResultSingleRecordView" });
    expect(singleRecord.exists()).toBe(true);
    expect(singleRecord.props("columns").map((column: { label: string }) => column.label))
      .toEqual(["id", "name", "note"]);
    expect(singleRecord.props("row")).toEqual({ sourceIndex: 0, cells: ["1", "Apple", null] });
    expect(button().attributes("aria-pressed")).toBe("true");
    expect(button().attributes("aria-label")).toBe("返回结果表格");

    await wrapper.setProps({ execution: {
      ...execution,
      results: [{ ...result, columnDetails: [
        result.columnDetails[0],
        { ...result.columnDetails[1], remarks: "商品名称" },
        result.columnDetails[2]
      ] }]
    } });
    await nextTick();
    expect(wrapper.findComponent({ name: "ResultSingleRecordView" }).props("columns")[1])
      .toMatchObject({ remarks: "商品名称" });

    await button().trigger("click");
    await nextTick();
    expect(table().exists()).toBe(true);
    expect(button().attributes("disabled")).toBeUndefined();

    cell(1, 0).props?.onPointerdown({
      button: 0, preventDefault: vi.fn(), ctrlKey: true, metaKey: false, shiftKey: false
    });
    await nextTick();
    expect(button().attributes("disabled")).toBeDefined();

    const rowSelector = columns()[0];
    const secondRow = rowSelector.cellRenderer?.({ rowData: rows()[1] } as never) as VNode;
    secondRow.props?.onPointerdown({
      button: 0, preventDefault: vi.fn(), stopPropagation: vi.fn(),
      ctrlKey: false, metaKey: false, shiftKey: false
    });
    window.dispatchEvent(new Event("pointerup"));
    await nextTick();
    expect(button().attributes("disabled")).toBeUndefined();
    await button().trigger("click");
    await nextTick();
    expect(wrapper.findComponent({ name: "ResultSingleRecordView" }).props("row"))
      .toEqual({ sourceIndex: 1, cells: ["2", "Banana", "yellow"] });

    await wrapper.setProps({ execution: {
      ...execution, executionId: "execution-single-record-next"
    } });
    await nextTick();
    expect(wrapper.findComponent({ name: "ResultSingleRecordView" }).exists()).toBe(false);
    expect(button().attributes("disabled")).toBeDefined();
  });

  it("enables single-record view for an optimized-grid cell selection", async () => {
    useSettingsStore().scrollOptimizationEnabled = true;
    const wrapper = mount(ResultPanel, {
      props: { activeResultIndex: 0, execution: {
        executionId: "execution-single-record-virtual", editorId: "editor-1", busy: false,
        cancelled: false, failed: false, durationMs: 8,
        results: [{ resultIndex: 0, sql: "select id", type: "QUERY", columns: ["id"],
          rows: [["1"]], updateCount: -1, truncated: false, durationMs: 7, complete: true }]
      } },
      global: { plugins: [ElementPlus] }
    });
    const button = () => wrapper.get("button.single-record-button");
    expect(button().attributes("disabled")).toBeDefined();

    wrapper.findComponent({ name: "ResultVirtualGrid" }).vm.$emit("cell-pointerdown",
      { button: 0, preventDefault: vi.fn(), ctrlKey: false, metaKey: false, shiftKey: false }, 0, 0);
    window.dispatchEvent(new Event("pointerup"));
    await nextTick();
    expect(button().attributes("disabled")).toBeUndefined();

    await button().trigger("click");
    await nextTick();
    expect(wrapper.findComponent({ name: "ResultVirtualGrid" }).exists()).toBe(false);
    expect(wrapper.findComponent({ name: "ResultSingleRecordView" }).props("row"))
      .toEqual({ sourceIndex: 0, cells: ["1"] });
  });

  it("shows a bounded second header line and emits the physical column selected by a cell", async () => {
    const settings = useSettingsStore();
    const remarks = "这是一段非常长的字段备注，用于验证表头不会因为备注内容持续变宽或换行导致结果集布局变形";
    const wrapper = mount(ResultPanel, { props: { activeResultIndex: 0, execution: {
      executionId: "execution-remarks", editorId: "editor-1", busy: false, cancelled: false,
      failed: false, durationMs: 4,
      results: [{ resultIndex: 0, sql: "select id from orders", type: "QUERY", columns: ["id"],
        columnDetails: [{ label: "id", name: "id", remarks, catalog: "sales", schema: "", table: "orders", typeName: "BIGINT" }],
        rows: [["1"]], updateCount: -1, truncated: false, durationMs: 3, complete: true }]
    } }, global: { plugins: [ElementPlus] } });
    const table = () => wrapper.findComponent({ name: "ElTableV2" });
    expect(table().props("headerHeight")).toBe(32);
    const width = (table().props("columns") as Column[])[1].width;

    settings.showColumnRemarksInHeader = true;
    await nextTick();
    expect(table().props("headerHeight")).toBe(48);
    const column = (table().props("columns") as Column[])[1];
    expect(column.width).toBe(width);
    const header = column.headerCellRenderer?.({} as never) as VNode;
    const labels = (header.children as VNode[])[0];
    const remark = (labels.children as VNode[])[1];
    expect(remark.children).toBe(remarks);
    expect(remark.props?.title).toBe(remarks);

    const row = (table().props("data") as Array<{ sourceIndex: number; cells: string[] }>)[0];
    const cell = column.cellRenderer?.({ rowData: row, rowIndex: 0 } as never) as VNode;
    cell.props?.onPointerdown({ button: 0, preventDefault: vi.fn() });
    expect(wrapper.emitted("selected-column")?.at(-1)?.[0]).toEqual({
      label: "id", name: "id", remarks, typeName: "BIGINT", catalog: "sales", schema: "", table: "orders"
    });
  });

  it("refreshes the header and selected column when remarks arrive asynchronously", async () => {
    const settings = useSettingsStore();
    settings.showColumnRemarksInHeader = true;
    const result = {
      resultIndex: 0, sql: "select * from CBSAC.CUSTOMERS", type: "QUERY", columns: ["ID"],
      columnDetails: [{
        label: "ID", name: "ID", remarks: "", catalog: "", schema: "", table: "", typeName: "NUMBER"
      }],
      rows: [["1"]], updateCount: -1, truncated: false, durationMs: 3, complete: true
    };
    const execution = {
      executionId: "execution-async-remarks", editorId: "editor-1", busy: false, cancelled: false,
      failed: false, durationMs: 4, results: [result]
    };
    const wrapper = mount(ResultPanel, {
      props: { activeResultIndex: 0, execution }, global: { plugins: [ElementPlus] }
    });
    let table = wrapper.findComponent({ name: "ElTableV2" });
    let column = (table.props("columns") as Column[])[1];
    const row = (table.props("data") as Array<{ sourceIndex: number; cells: string[] }>)[0];
    const cell = column.cellRenderer?.({ rowData: row, rowIndex: 0 } as never) as VNode;
    cell.props?.onPointerdown({ button: 0, preventDefault: vi.fn() });
    expect(wrapper.emitted("selected-column")?.at(-1)?.[0]).toMatchObject({ remarks: "" });

    await wrapper.setProps({ execution: {
      ...execution,
      results: [{ ...result, columnDetails: [{ ...result.columnDetails[0], remarks: "客户编号" }] }]
    } });
    await nextTick();

    table = wrapper.findComponent({ name: "ElTableV2" });
    column = (table.props("columns") as Column[])[1];
    const header = column.headerCellRenderer?.({} as never) as VNode;
    const labels = (header.children as VNode[])[0];
    expect((labels.children as VNode[])[1].children).toBe("客户编号");
    expect(wrapper.emitted("selected-column")?.at(-1)?.[0]).toMatchObject({ remarks: "客户编号" });
    header.props?.onContextmenu({ preventDefault: vi.fn(), clientX: 20, clientY: 30 });
    await nextTick();
    wrapper.findComponent({ name: "ResultHeaderContextMenu" }).vm
      .$emit("command", "copy-headers-with-remarks");
    await flushPromises();
    expect(clipboardWrite).toHaveBeenLastCalledWith('ID as "客户编号"');
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
    expect((table.props("columns") as Array<{ title: string }>).map((column) => column.title)).toEqual(["#", "id"]);

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
    expect((table.props("columns") as Array<{ title: string }>).map((column) => column.title)).toEqual(["#", "id"]);

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
    const dataColumns = () => columns().slice(1);
    const header = (index: number) => dataColumns()[index].headerCellRenderer?.({} as never) as VNode;

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
    expect(dataColumns().map((column) => column.title)).toEqual(["name", "id", "amount"]);
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
    expect(dataColumns()[0].width).toBe(220);

    await wrapper.find('button[aria-label="复原列布局"]').trigger("click");
    await nextTick();
    expect(dataColumns().map((column) => column.title)).toEqual(["id", "name", "amount"]);
    expect(dataColumns()[1].width).toBe(120);
  });

  it("copies headers and loaded data from the context menu and moves selected columns to an edge", async () => {
    const settings = useSettingsStore();
    settings.copySeparator = "comma";
    const wrapper = mount(ResultPanel, {
      props: { activeResultIndex: 0, execution: {
        executionId: "execution-copy", editorId: "editor-1", busy: false, cancelled: false, failed: false, durationMs: 8,
        results: [{ resultIndex: 0, sql: "select", type: "QUERY", columns: ["id", "name", "amount"],
          columnDetails: [
            { label: "id", name: "id", remarks: "订单编号（主键）", catalog: "db", schema: "", table: "sample", typeName: "BIGINT" },
            { label: "name", name: "name", remarks: "", catalog: "db", schema: "", table: "sample", typeName: "VARCHAR" },
            { label: "amount", name: "amount", remarks: '金额"含税(enum)', catalog: "db", schema: "", table: "sample", typeName: "DECIMAL" },
          ],
          rows: [["1", "Apple, Inc.", "12.30"]], updateCount: -1, truncated: false, durationMs: 7, complete: true }]
      } }, global: { plugins: [ElementPlus] }
    });
    const columns = () => wrapper.findComponent({ name: "ElTableV2" }).props("columns") as Column[];
    const dataColumns = () => columns().slice(1);
    const header = (index: number) => dataColumns()[index].headerCellRenderer?.({} as never) as VNode;

    header(0).props?.onClick({ ctrlKey: false, metaKey: false, shiftKey: false });
    header(2).props?.onClick({ ctrlKey: true, metaKey: false, shiftKey: false });
    await nextTick();
    header(0).props?.onContextmenu({ preventDefault: vi.fn(), clientX: 20, clientY: 30 });
    await nextTick();
    const menu = wrapper.findComponent({ name: "ResultHeaderContextMenu" });
    expect(menu.props("visible")).toBe(true);
    menu.vm.$emit("command", "copy-all");
    await flushPromises();
    expect(clipboardWrite).toHaveBeenLastCalledWith("id,amount\n1,12.30");

    menu.vm.$emit("command", "copy-headers-with-remarks");
    await flushPromises();
    expect(clipboardWrite).toHaveBeenLastCalledWith('id as "订单编号",amount as "金额""含税"');

    menu.vm.$emit("command", "move-right");
    await nextTick();
    expect(dataColumns().map((column) => column.title)).toEqual(["name", "id", "amount"]);

    header(0).props?.onContextmenu({ preventDefault: vi.fn(), clientX: 20, clientY: 30 });
    menu.vm.$emit("command", "copy-headers");
    await flushPromises();
    expect(clipboardWrite).toHaveBeenLastCalledWith("name");
  });

  it("copies selected virtual-grid headers with remarks and the configured separator", async () => {
    const settings = useSettingsStore();
    settings.scrollOptimizationEnabled = true;
    settings.copySeparator = "pipe";
    const wrapper = mount(ResultPanel, {
      props: { activeResultIndex: 0, execution: {
        executionId: "execution-virtual-copy", editorId: "editor-1", busy: false,
        cancelled: false, failed: false, durationMs: 8,
        results: [{ resultIndex: 0, sql: "select", type: "QUERY", columns: ["ID", "NAME"],
          columnDetails: [
            { label: "ID", name: "ID", remarks: "编号（主键）", catalog: "", schema: "", table: "", typeName: "NUMBER" },
            { label: "NAME", name: "NAME", remarks: "   ", catalog: "", schema: "", table: "", typeName: "VARCHAR" },
          ],
          rows: [["1", "Apple"]], updateCount: -1, truncated: false, durationMs: 7, complete: true }]
      } }, global: { plugins: [ElementPlus] }
    });
    const grid = wrapper.findComponent({ name: "ResultVirtualGrid" });
    const columns = grid.props("columns") as Array<{ headerRenderer: () => VNode }>;
    const first = columns[0].headerRenderer();
    const second = columns[1].headerRenderer();
    first.props?.onClick({ ctrlKey: false, metaKey: false, shiftKey: false });
    second.props?.onClick({ ctrlKey: true, metaKey: false, shiftKey: false });
    first.props?.onContextmenu({ preventDefault: vi.fn(), clientX: 20, clientY: 30 });
    await nextTick();
    wrapper.findComponent({ name: "ResultHeaderContextMenu" }).vm
      .$emit("command", "copy-headers-with-remarks");
    await flushPromises();
    expect(clipboardWrite).toHaveBeenLastCalledWith('ID as "编号"|NAME');
  });

  it("copies only a double-clicked title when enabled and keeps resize double click independent", async () => {
    const settings = useSettingsStore();
    const wrapper = mount(ResultPanel, {
      props: { activeResultIndex: 0, execution: {
        executionId: "execution-double", editorId: "editor-1", busy: false, cancelled: false, failed: false, durationMs: 8,
        results: [{ resultIndex: 0, sql: "select id as alias_id", type: "QUERY", columns: ["alias_id"],
          rows: [["1"]], updateCount: -1, truncated: false, durationMs: 7, complete: true }]
      } }, global: { plugins: [ElementPlus] }
    });
    const column = (wrapper.findComponent({ name: "ElTableV2" }).props("columns") as Column[])[1];
    const header = column.headerCellRenderer?.({} as never) as VNode;
    const children = header.children as VNode[];
    children[0].props?.onDblclick({ preventDefault: vi.fn(), stopPropagation: vi.fn() });
    await flushPromises();
    expect(clipboardWrite).toHaveBeenLastCalledWith("alias_id");

    const resizeHandle = children[children.length - 1];
    resizeHandle.props?.onDblclick({ preventDefault: vi.fn(), stopPropagation: vi.fn() });
    await nextTick();
    expect(clipboardWrite).toHaveBeenCalledTimes(1);

    settings.copyHeaderOnDoubleClick = false;
    const updatedHeader = (wrapper.findComponent({ name: "ElTableV2" }).props("columns") as Column[])[1]
      .headerCellRenderer?.({} as never) as VNode;
    (updatedHeader.children as VNode[])[0].props?.onDblclick({ preventDefault: vi.fn(), stopPropagation: vi.fn() });
    await flushPromises();
    expect(clipboardWrite).toHaveBeenCalledTimes(1);
  });

  it("sorts and filters loaded rows and clears state when each setting is disabled", async () => {
    const settings = useSettingsStore();
    const wrapper = mount(ResultPanel, { props: { activeResultIndex: 0, execution: {
      executionId: "execution-sort", editorId: "editor-1", busy: false, cancelled: false, failed: false, durationMs: 4,
      results: [{ resultIndex: 0, sql: "select id, name", type: "QUERY", columns: ["id", "name"],
        columnDetails: [
          { label: "id", name: "id", remarks: "", catalog: "db", schema: "", table: "sample", typeName: "BIGINT", jdbcType: -5, quotedLabel: "`id`" },
          { label: "name", name: "name", remarks: "", catalog: "db", schema: "", table: "sample", typeName: "VARCHAR", jdbcType: 12, quotedLabel: "`name`" }
        ], rows: [["10", "ten"], ["2", "two"], [null, "none"]], updateCount: -1, truncated: false, durationMs: 3, complete: true }]
    } }, global: { plugins: [ElementPlus] } });
    const table = () => wrapper.findComponent({ name: "ElTableV2" });
    const header = (table().props("columns") as Column[])[1].headerCellRenderer?.({} as never) as VNode;
    const tools = (header.children as VNode[]).find((child) => child?.props?.columnIndex === 0) as VNode;
    tools.props?.onSort(); await nextTick();
    expect((table().props("data") as Array<{ cells: Array<string | null> }>).map((row) => row.cells[0]))
      .toEqual(["2", "10", null]);
    tools.props?.onApply({ columnIndex: 0, operator: "gt", value: "2" }); await nextTick();
    expect((table().props("data") as Array<{ cells: Array<string | null> }>).map((row) => row.cells[0])).toEqual(["10"]);

    settings.headerSortingEnabled = false; await nextTick();
    expect((table().props("data") as Array<{ cells: Array<string | null> }>).map((row) => row.cells[0])).toEqual(["10"]);
    settings.headerFilteringEnabled = false; await nextTick();
    expect((table().props("data") as Array<{ cells: Array<string | null> }>).map((row) => row.cells[0]))
      .toEqual(["10", "2", null]);
  });

  it("copies a rectangular cell selection and generates safe SQL for selected rows", async () => {
    const wrapper = mount(ResultPanel, { props: { activeResultIndex: 0, execution: {
      executionId: "execution-select", editorId: "editor-1", busy: false, cancelled: false, failed: false, durationMs: 4,
      results: [{ resultIndex: 0, sql: "select id, name from sample", type: "QUERY", columns: ["id", "name"],
        columnDetails: [
          { label: "id", name: "id", remarks: "", catalog: "db", schema: "", table: "sample", typeName: "BIGINT", jdbcType: -5, quotedLabel: "`id`" },
          { label: "name", name: "name", remarks: "", catalog: "db", schema: "", table: "sample", typeName: "VARCHAR", jdbcType: 12, quotedLabel: "`name`" }
        ], mutationTarget: { qualifiedName: "`db`.`sample`", columns: [
          { resultIndex: 0, name: "id", quotedName: "`id`", jdbcType: -5 },
          { resultIndex: 1, name: "name", quotedName: "`name`", jdbcType: 12 }
        ], uniqueKeys: [{ name: "PRIMARY", primary: true, resultColumnIndices: [0] }] },
        rows: [["1", "Apple"], ["2", "Banana"], ["3", "Cherry"]], updateCount: -1, truncated: false, durationMs: 3, complete: true }]
    } }, global: { plugins: [ElementPlus] } });
    const table = wrapper.findComponent({ name: "ElTableV2" });
    const columns = table.props("columns") as Column[];
    const rows = table.props("data") as Array<{ sourceIndex: number; cells: string[] }>;
    const first = columns[1].cellRenderer?.({ rowData: rows[0], rowIndex: 0 } as never) as VNode;
    const last = columns[2].cellRenderer?.({ rowData: rows[1], rowIndex: 1 } as never) as VNode;
    first.props?.onPointerdown({ button: 0, preventDefault: vi.fn() });
    last.props?.onPointerenter(); window.dispatchEvent(new Event("pointerup"));
    await wrapper.get(".table-host").trigger("keydown", { metaKey: true, key: "c" });
    await flushPromises();
    expect(clipboardWrite).toHaveBeenLastCalledWith("1,Apple\n2,Banana");
    expect(wrapper.emitted("selected-row-count")?.at(-1)).toEqual([2]);

    const rowNumber = columns[0];
    expect(rowNumber.width).toBe(34);
    expect(rowNumber.minWidth).toBe(34);
    expect(rowNumber.maxWidth).toBe(34);
    const rowOne = rowNumber.cellRenderer?.({ rowData: rows[0] } as never) as VNode;
    const rowThree = rowNumber.cellRenderer?.({ rowData: rows[2] } as never) as VNode;
    rowOne.props?.onPointerdown({ button: 0, preventDefault: vi.fn(), stopPropagation: vi.fn(), ctrlKey: false, metaKey: false, shiftKey: false });
    rowThree.props?.onPointerenter();
    window.dispatchEvent(new Event("pointerup"));
    await wrapper.get(".table-host").trigger("keydown", { metaKey: true, key: "c" });
    await flushPromises();
    expect(clipboardWrite).toHaveBeenLastCalledWith("1,Apple\n2,Banana\n3,Cherry");
    expect(wrapper.emitted("selected-row-count")?.at(-1)).toEqual([3]);

    rowThree.props?.onContextmenu({ preventDefault: vi.fn(), stopPropagation: vi.fn(), clientX: 20, clientY: 30 });
    await nextTick();
    const menu = wrapper.findComponent({ name: "ResultDataContextMenu" });
    expect(menu.props("mode")).toBe("rows");
    expect(menu.props("canUpdate")).toBe(true);
    menu.vm.$emit("command", "copy-update"); await flushPromises();
    expect(clipboardWrite).toHaveBeenLastCalledWith(
      "UPDATE `db`.`sample` SET `name` = 'Apple' WHERE `id` = 1;\nUPDATE `db`.`sample` SET `name` = 'Banana' WHERE `id` = 2;\nUPDATE `db`.`sample` SET `name` = 'Cherry' WHERE `id` = 3;");
  });

  it("supports sparse cell comparison, exact sums, value viewing and complete row highlighting", async () => {
    const wrapper = mount(ResultPanel, {
      props: { activeResultIndex: 0, execution: {
        executionId: "execution-tools", editorId: "editor-1", busy: false, cancelled: false,
        failed: false, durationMs: 4,
        results: [{ resultIndex: 0, sql: "select id, amount, payload", type: "QUERY",
          columns: ["id", "amount", "payload"],
          columnDetails: [
            { label: "id", name: "id", remarks: "", catalog: "db", schema: "", table: "sample", typeName: "DECIMAL", jdbcType: 3 },
            { label: "amount", name: "amount", remarks: "", catalog: "db", schema: "", table: "sample", typeName: "DECIMAL", jdbcType: 3 },
            { label: "payload", name: "payload", remarks: "", catalog: "db", schema: "", table: "sample", typeName: "JSON", jdbcType: 12 }
          ],
          rows: [["1.20", "10", "{\"a\":1}"], ["20", "2.30", "{\"a\":2}"]],
          updateCount: -1, truncated: false, durationMs: 3, complete: true }]
      } },
      global: {
        plugins: [ElementPlus],
        stubs: { ResultValueCompareDialog: true }
      }
    });
    const table = () => wrapper.findComponent({ name: "ElTableV2" });
    const columns = () => table().props("columns") as Column[];
    const rows = () => table().props("data") as Array<{ sourceIndex: number; cells: string[] }>;
    const cell = (row: number, column: number) =>
      columns()[column + 1].cellRenderer?.({ rowData: rows()[row], rowIndex: row } as never) as VNode;

    cell(0, 0).props?.onPointerdown({
      button: 0, preventDefault: vi.fn(), ctrlKey: false, metaKey: false, shiftKey: false
    });
    window.dispatchEvent(new Event("pointerup"));
    cell(1, 1).props?.onPointerdown({
      button: 0, preventDefault: vi.fn(), ctrlKey: true, metaKey: false, shiftKey: false
    });
    await nextTick();
    await wrapper.get(".table-host").trigger("keydown", { metaKey: true, key: "c" });
    await flushPromises();
    expect(clipboardWrite).toHaveBeenLastCalledWith("1.20,\n,2.30");

    cell(1, 1).props?.onContextmenu({
      preventDefault: vi.fn(), stopPropagation: vi.fn(), clientX: 20, clientY: 30
    });
    await nextTick();
    const menu = wrapper.findComponent({ name: "ResultDataContextMenu" });
    expect(menu.props("canCompare")).toBe(true);
    expect(menu.props("canSum")).toBe(true);
    menu.vm.$emit("command", "sum");
    await nextTick();
    expect(wrapper.findComponent({ name: "ResultSummaryFooter" }).props()).toMatchObject({
      total: "3.50", count: 2
    });
    expect(table().props("footerHeight")).toBe(32);

    menu.vm.$emit("command", "compare");
    await nextTick();
    expect(wrapper.findComponent({ name: "ResultValueCompareDialog" }).props()).toMatchObject({
      modelValue: true, left: "1.20", right: "2.30"
    });

    cell(0, 2).props?.onDblclick();
    await nextTick();
    expect(wrapper.findComponent({ name: "ResultValueDialog" }).props()).toMatchObject({
      modelValue: true, value: "{\"a\":1}"
    });

    const rowClass = table().props("rowClass") as (params: { rowData: { sourceIndex: number } }) => string;
    const rowNumber = columns()[0].cellRenderer?.({ rowData: rows()[0] } as never) as VNode;
    rowNumber.props?.onPointerdown({
      button: 0, preventDefault: vi.fn(), stopPropagation: vi.fn(),
      ctrlKey: false, metaKey: false, shiftKey: false
    });
    expect(rowClass({ rowData: rows()[0] })).toBe("result-row-selected");
    expect(rowClass({ rowData: rows()[1] })).toBe("");
  });

  it("sums selected headers across the current filtered rows and clears stale totals", async () => {
    const result = {
      resultIndex: 0, sql: "select amount", type: "QUERY", columns: ["amount"],
      columnDetails: [{
        label: "amount", name: "amount", remarks: "", catalog: "db", schema: "", table: "sample",
        typeName: "DECIMAL", jdbcType: 3
      }],
      rows: [["1.20"], ["2.30"], [null]], updateCount: -1, truncated: false, durationMs: 3, complete: true
    };
    const execution = {
      executionId: "execution-header-sum", editorId: "editor-1", busy: false, cancelled: false,
      failed: false, durationMs: 4, results: [result]
    };
    const wrapper = mount(ResultPanel, {
      props: { activeResultIndex: 0, execution }, global: { plugins: [ElementPlus] }
    });
    const table = () => wrapper.findComponent({ name: "ElTableV2" });
    const header = () => (table().props("columns") as Column[])[1]
      .headerCellRenderer?.({} as never) as VNode;
    header().props?.onClick({ ctrlKey: false, metaKey: false, shiftKey: false });
    header().props?.onContextmenu({ preventDefault: vi.fn(), clientX: 20, clientY: 30 });
    await nextTick();
    const menu = wrapper.findComponent({ name: "ResultHeaderContextMenu" });
    expect(menu.props("canSum")).toBe(true);
    menu.vm.$emit("command", "sum");
    await nextTick();
    expect(wrapper.findComponent({ name: "ResultSummaryFooter" }).props()).toMatchObject({
      total: "3.50", count: 2
    });

    const tools = (header().children as VNode[]).find((child) => child?.props?.columnIndex === 0) as VNode;
    tools.props?.onApply({ columnIndex: 0, operator: "gt", value: "1.20" });
    await nextTick();
    expect(wrapper.findComponent({ name: "ResultSummaryFooter" }).exists()).toBe(false);
    header().props?.onContextmenu({ preventDefault: vi.fn(), clientX: 20, clientY: 30 });
    menu.vm.$emit("command", "sum");
    await nextTick();
    expect(wrapper.findComponent({ name: "ResultSummaryFooter" }).props()).toMatchObject({
      total: "2.30", count: 1
    });

    await wrapper.setProps({ execution: {
      ...execution, results: [{ ...result, rows: [...result.rows, ["5.00"]] }]
    } });
    await nextTick();
    expect(wrapper.findComponent({ name: "ResultSummaryFooter" }).exists()).toBe(false);
  });
});
