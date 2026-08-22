import { describe, expect, it } from "vitest";
import { nextTick } from "vue";
import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import type { ColumnOption } from "../columnFilter";
import ResultSingleRecordView from "./ResultSingleRecordView.vue";

describe("ResultSingleRecordView", () => {
  const columns: ColumnOption[] = [
    {
      index: 0, label: "ID", name: "ID", remarks: "客户编号", catalog: "", schema: "",
      table: "CUSTOMERS", typeName: "NUMBER", jdbcType: 2, quotedLabel: '"ID"'
    },
    {
      index: 1, label: "NAME", name: "NAME", remarks: "客户名称", catalog: "", schema: "",
      table: "CUSTOMERS", typeName: "VARCHAR2", jdbcType: 12, quotedLabel: '"NAME"'
    }
  ];

  it("transposes one result row into a virtual field grid", async () => {
    const wrapper = mount(ResultSingleRecordView, {
      props: { columns, row: { sourceIndex: 3, cells: ["1001", null] } },
      global: { plugins: [ElementPlus] }
    });
    const grid = wrapper.findComponent({ name: "ResultVirtualGrid" });
    expect(grid.props("columns").map((column: { label: string }) => column.label))
      .toEqual(["字段名", "字段值", "字段备注", "字段类型"]);
    expect(grid.props("rows")).toEqual([
      { sourceIndex: 0, cells: ["ID", "1001", "客户编号", "NUMBER"] },
      { sourceIndex: 1, cells: ["NAME", null, "客户名称", "VARCHAR2"] }
    ]);
    expect(grid.props("columns").filter((column: { readonly?: boolean }) => column.readonly))
      .toHaveLength(3);

    await wrapper.setProps({ columns: [{ ...columns[0], remarks: "客户主键" }, columns[1]] });
    await nextTick();
    expect(grid.props("rows")[0].cells[2]).toBe("客户主键");
  });

  it("maps virtual grid editing and pending state back to the source field", async () => {
    const wrapper = mount(ResultSingleRecordView, {
      props: {
        columns: [columns[1]], row: { sourceIndex: 3, cells: ["before", "after"] },
        editingColumnIndex: 1, editingValue: "after", cellStates: { "3:1": "pending" }
      },
      global: { plugins: [ElementPlus] }
    });
    const grid = wrapper.findComponent({ name: "ResultVirtualGrid" });
    expect(grid.props("editingCell")).toEqual({ rowIndex: 1, columnIndex: 1 });
    expect(grid.props("cellStates")).toEqual({ "1:1": "pending" });
    grid.vm.$emit("cell-dblclick", 0, 1);
    await nextTick();
    expect(wrapper.emitted("cell-dblclick")).toEqual([[1]]);
    grid.vm.$emit("update:editing-value", "changed");
    expect(wrapper.emitted("update:editing-value")).toEqual([["changed"]]);
  });

  it("keeps cell, row and column selection local to the synthetic grid", async () => {
    const wrapper = mount(ResultSingleRecordView, {
      props: { columns, row: { sourceIndex: 3, cells: ["1001", "Alice"] } },
      global: { plugins: [ElementPlus] }
    });
    const grid = wrapper.findComponent({ name: "ResultVirtualGrid" });
    const pointer = { button: 0, preventDefault: () => undefined } as unknown as PointerEvent;
    grid.vm.$emit("cell-pointerdown", pointer, 0, 1);
    grid.vm.$emit("cell-pointerenter", 1, 1);
    await nextTick();
    const cellState = (wrapper.emitted("selection-change")?.at(-1)?.[0] as {
      mode: string; cellKeys: string[]; sqlAllowed: boolean; statusText: string;
    });
    expect(cellState.mode).toBe("cells");
    expect(cellState.cellKeys).toEqual(["0:1", "1:1"]);
    expect(cellState.sqlAllowed).toBe(true);
    expect(cellState.statusText).toBe("已选中 2 个单元格");

    grid.vm.$emit("cell-pointerdown", pointer, 0, 0);
    await nextTick();
    const metadataState = (wrapper.emitted("selection-change")?.at(-1)?.[0] as { cellKeys: string[]; sqlAllowed: boolean });
    expect(metadataState.cellKeys).toEqual(["0:0"]);
    expect(metadataState.sqlAllowed).toBe(false);

    await wrapper.find(".result-single-record-view").trigger("keydown", { key: "Escape" });
    const cleared = (wrapper.emitted("selection-change")?.at(-1)?.[0] as { hasSelection: boolean });
    expect(cleared.hasSelection).toBe(false);
  });

  it("copies only selected field rows in the single-record view", async () => {
    const wrapper = mount(ResultSingleRecordView, {
      props: { columns, row: { sourceIndex: 3, cells: ["1001", "Alice"] } },
      global: { plugins: [ElementPlus] }
    });
    const grid = wrapper.findComponent({ name: "ResultVirtualGrid" });
    const pointer = { button: 0, preventDefault: () => undefined } as unknown as PointerEvent;
    grid.vm.$emit("cell-pointerdown", pointer, 0, 1);
    await nextTick();
    const exposed = wrapper.vm as unknown as { getCopyText: () => string | undefined };
    expect(exposed.getCopyText()).toBe("1001");

    grid.vm.$emit("cell-pointerenter", 1, 1);
    await nextTick();
    expect(exposed.getCopyText()).toBe("1001\nAlice");
  });

  it("copies a selected cell with both Ctrl+C and Cmd+C", async () => {
    const wrapper = mount(ResultSingleRecordView, {
      props: { columns, row: { sourceIndex: 3, cells: ["1001", "Alice"] } },
      global: { plugins: [ElementPlus] }
    });
    const grid = wrapper.findComponent({ name: "ResultVirtualGrid" });
    const pointer = { button: 0, preventDefault: () => undefined } as unknown as PointerEvent;
    grid.vm.$emit("cell-pointerdown", pointer, 0, 1);
    await nextTick();
    const root = wrapper.find(".result-single-record-view");
    const ctrlEvent = new KeyboardEvent("keydown", { key: "c", ctrlKey: true, bubbles: true, cancelable: true });
    root.element.dispatchEvent(ctrlEvent);
    const metaEvent = new KeyboardEvent("keydown", { key: "c", metaKey: true, bubbles: true, cancelable: true });
    root.element.dispatchEvent(metaEvent);
    expect(ctrlEvent.defaultPrevented).toBe(true);
    expect(metaEvent.defaultPrevented).toBe(true);
    expect(wrapper.emitted("copy-text")).toEqual([
      ["1001", "已复制选中单元格"], ["1001", "已复制选中单元格"]
    ]);
  });

  it("copies selected synthetic headers, rows and metadata cells", async () => {
    const wrapper = mount(ResultSingleRecordView, {
      props: { copySeparator: "pipe", columns, row: { sourceIndex: 3, cells: ["1001", null] } },
      global: { plugins: [ElementPlus] }
    });
    const grid = wrapper.findComponent({ name: "ResultVirtualGrid" });
    const root = wrapper.find(".result-single-record-view");
    const copy = () => root.element.dispatchEvent(new KeyboardEvent("keydown", {
      key: "c", metaKey: true, bubbles: true, cancelable: true
    }));
    const columnsProp = grid.props("columns") as Array<{ headerRenderer?: () => unknown }>;
    for (const [index, expected] of [[0, "ID\nNAME"], [1, "1001\nNULL"], [2, "客户编号\n客户名称"], [3, "NUMBER\nVARCHAR2"]] as const) {
      const header = columnsProp[index].headerRenderer?.() as { props?: { onClick?: (event: MouseEvent) => void } };
      header.props?.onClick?.(new MouseEvent("click"));
      copy();
      await nextTick();
      expect(wrapper.emitted("copy-text")?.at(-1)).toEqual([expected, "已复制选中列"]);
    }

    const rowWrapper = mount(ResultSingleRecordView, {
      props: { copySeparator: "pipe", columns, row: { sourceIndex: 3, cells: ["1001", null] } },
      global: { plugins: [ElementPlus] }
    });
    const rowGrid = rowWrapper.findComponent({ name: "ResultVirtualGrid" });
    const rowRoot = rowWrapper.find(".result-single-record-view");
    const pointer = { button: 0, preventDefault: () => undefined } as unknown as PointerEvent;
    rowGrid.vm.$emit("row-pointerdown", pointer, 0);
    window.dispatchEvent(new Event("pointerup"));
    rowRoot.element.dispatchEvent(new KeyboardEvent("keydown", { key: "c", metaKey: true, bubbles: true, cancelable: true }));
    await nextTick();
    expect(rowWrapper.emitted("copy-text")?.at(-1)).toEqual([
      "ID|1001|客户编号|NUMBER", "已复制选中字段"
    ]);
    rowGrid.vm.$emit("row-pointerdown", pointer, 1);
    window.dispatchEvent(new Event("pointerup"));
    rowRoot.element.dispatchEvent(new KeyboardEvent("keydown", { key: "c", metaKey: true, bubbles: true, cancelable: true }));
    await nextTick();
    expect(rowWrapper.emitted("copy-text")?.at(-1)).toEqual([
      "NAME|NULL|客户名称|VARCHAR2", "已复制选中字段"
    ]);
    rowGrid.vm.$emit("row-pointerdown", pointer, 0);
    rowGrid.vm.$emit("row-pointerenter", 1);
    window.dispatchEvent(new Event("pointerup"));
    rowRoot.element.dispatchEvent(new KeyboardEvent("keydown", { key: "c", metaKey: true, bubbles: true, cancelable: true }));
    await nextTick();
    expect(rowWrapper.emitted("copy-text")?.at(-1)).toEqual([
      "ID|1001|客户编号|NUMBER\nNAME|NULL|客户名称|VARCHAR2", "已复制选中字段"
    ]);

    const cellPointer = { button: 0, preventDefault: () => undefined } as unknown as PointerEvent;
    for (const [row, column, expected] of [[0, 0, "ID"], [0, 2, "客户编号"], [1, 3, "VARCHAR2"]] as const) {
      grid.vm.$emit("cell-pointerdown", cellPointer, row, column);
      copy();
      await nextTick();
      expect(wrapper.emitted("copy-text")?.at(-1)).toEqual([expected, "已复制选中单元格"]);
    }
  });

  it("does not intercept copy without a selection or while editing", async () => {
    const wrapper = mount(ResultSingleRecordView, {
      props: {
        columns, row: { sourceIndex: 3, cells: ["1001", "Alice"] },
        editingColumnIndex: 1, editingValue: "Alice"
      },
      global: { plugins: [ElementPlus] }
    });
    const root = wrapper.find(".result-single-record-view");
    const emptyEvent = new KeyboardEvent("keydown", { key: "c", metaKey: true, bubbles: true, cancelable: true });
    root.element.dispatchEvent(emptyEvent);
    expect(emptyEvent.defaultPrevented).toBe(false);
    expect(wrapper.emitted("copy-text")).toBeUndefined();
    const editor = document.createElement("input");
    root.element.append(editor);
    editor.dispatchEvent(new KeyboardEvent("keydown", { key: "c", metaKey: true, bubbles: true, cancelable: true }));
    expect(wrapper.emitted("copy-text")).toBeUndefined();
  });

});
