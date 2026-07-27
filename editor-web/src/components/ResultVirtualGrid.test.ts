import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { defineComponent, markRaw, nextTick } from "vue";
import { mount } from "@vue/test-utils";
import ResultVirtualGrid from "./ResultVirtualGrid.vue";
import type { ResultVirtualColumn } from "../resultVirtualGrid";

const columns = Array.from({ length: 30 }, (_, index): ResultVirtualColumn => ({
  key: `c${index}`,
  sourceIndex: index,
  visibleIndex: index,
  width: 120,
  headerRenderer: markRaw(defineComponent({ template: `<span>column</span>` }))
}));
const rows = Array.from({ length: 200 }, (_, row) => ({
  sourceIndex: row,
  cells: Array.from({ length: 30 }, (_, column) => `${row}:${column}`)
}));

describe("ResultVirtualGrid", () => {
  beforeEach(() => {
    vi.stubGlobal("ResizeObserver", class {
      observe(): void {}
      disconnect(): void {}
    });
  });

  it("bounds rendered rows and columns and keeps delegated interactions", async () => {
    const wrapper = mount(ResultVirtualGrid, {
      props: {
        rows, columns, headerHeight: 32, selectionMode: "cells",
        selectedRowSources: [], cellRange: undefined
      }
    });
    const viewport = wrapper.get(".result-virtual-grid__viewport").element as HTMLElement;
    Object.defineProperties(viewport, {
      clientWidth: { configurable: true, value: 514 },
      clientHeight: { configurable: true, value: 320 }
    });
    wrapper.vm.setScrollPosition({ left: 1200, top: 1600 });
    await nextTick();

    expect(wrapper.findAll(".result-virtual-grid__row").length).toBeLessThanOrEqual(18);
    expect(wrapper.findAll(".result-virtual-grid__header-cell").length).toBeLessThanOrEqual(8);
    expect(wrapper.findAll(".result-virtual-grid__cell").length).toBeLessThan(150);

    const cell = wrapper.get(".result-virtual-grid__cell");
    await cell.trigger("pointerdown", { button: 0 });
    await cell.trigger("contextmenu");
    expect(wrapper.emitted("cell-pointerdown")).toHaveLength(1);
    expect(wrapper.emitted("cell-contextmenu")).toHaveLength(1);
    wrapper.unmount();
  });

  it("keeps mixed database values compact, classed and safely truncated", async () => {
    const longJson = '{"订单序号":1,"商品名称":"适合验证长文本省略与完整内容提示的测试商品"}';
    const mixedRows = [{
      sourceIndex: 0,
      cells: ["中文内容", "12345.67", "2026-07-27 09:30:00", longJson, null, "0xA1B2C3"]
    }];
    const wrapper = mount(ResultVirtualGrid, {
      props: {
        rows: mixedRows,
        columns: columns.slice(0, 6),
        headerHeight: 32,
        selectionMode: "cells",
        selectedRowSources: []
      }
    });
    const viewport = wrapper.get(".result-virtual-grid__viewport").element as HTMLElement;
    Object.defineProperties(viewport, {
      clientWidth: { configurable: true, value: 900 },
      clientHeight: { configurable: true, value: 96 }
    });
    wrapper.vm.setScrollPosition({ left: 0, top: 0 });
    await nextTick();

    const cells = wrapper.findAll(".result-virtual-grid__cell");
    expect(cells.map((cell) => cell.text())).toEqual([
      "中文内容", "12345.67", "2026-07-27 09:30:00", longJson, "NULL", "0xA1B2C3"
    ]);
    expect(cells[3].attributes("title")).toBe(longJson);
    expect(cells[4].classes()).toContain("null-value");
    expect(cells[5].classes()).toContain("binary-value");
    wrapper.unmount();
  });

  it("leaves wheel input to the browser's native scrolling", () => {
    const wrapper = mount(ResultVirtualGrid, {
      props: { rows, columns, headerHeight: 32, selectionMode: "cells", selectedRowSources: [] }
    });
    const viewport = wrapper.get(".result-virtual-grid__viewport").element as HTMLElement;
    const wheel = new WheelEvent("wheel", { deltaY: 96, deltaMode: 0, cancelable: true });
    viewport.dispatchEvent(wheel);
    expect(wheel.defaultPrevented).toBe(false);
    expect(wrapper.find(".result-virtual-grid__canvas").exists()).toBe(true);
    wrapper.unmount();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });
});
