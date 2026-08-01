import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { h, nextTick } from "vue";
import { mount } from "@vue/test-utils";
import ResultVirtualGrid from "./ResultVirtualGrid.vue";
import type { ResultVirtualColumn } from "../resultVirtualGrid";

const columns = Array.from({ length: 30 }, (_, index): ResultVirtualColumn => ({
  key: `c${index}`,
  label: `column ${index}`,
  sourceIndex: index,
  visibleIndex: index,
  width: 120,
  headerRenderer: () => h("span", `column ${index}`)
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
        selectedRowSources: [], cellRange: undefined, bufferScreens: 1
      }
    });
    const viewport = wrapper.get(".result-virtual-grid__viewport").element as HTMLElement;
    Object.defineProperties(viewport, {
      clientWidth: { configurable: true, value: 514 },
      clientHeight: { configurable: true, value: 288 }
    });
    wrapper.vm.setScrollPosition({ left: 1200, top: 1600 });
    await nextTick();

    expect(wrapper.get(".result-virtual-grid__header").element.parentElement).toBe(wrapper.element);
    expect(wrapper.get(".result-virtual-grid__viewport").element.parentElement).toBe(wrapper.element);
    expect(wrapper.get(".result-virtual-grid__header-canvas").attributes("style"))
      .toContain("translate3d(-1200px, 0, 0)");
    expect(wrapper.findAll(".result-virtual-grid__row").length).toBeLessThanOrEqual(28);
    expect(wrapper.findAll(".result-virtual-grid__header-cell").length).toBeLessThanOrEqual(13);
    expect(wrapper.findAll(".result-virtual-grid__cell").length).toBeLessThan(370);

    const cell = wrapper.get(".result-virtual-grid__cell");
    await cell.trigger("pointerdown", { button: 0 });
    await cell.trigger("dblclick");
    await cell.trigger("contextmenu");
    expect(wrapper.emitted("cell-pointerdown")).toHaveLength(1);
    expect(wrapper.emitted("cell-dblclick")).toHaveLength(1);
    expect(wrapper.emitted("cell-contextmenu")).toHaveLength(1);
    wrapper.unmount();
  });

  it("starts body rows at zero below the detached header and reserves the row gutter", async () => {
    const wrapper = mount(ResultVirtualGrid, {
      props: {
        rows: rows.slice(0, 3), columns: columns.slice(0, 3), headerHeight: 32,
        bufferScreens: 1, selectionMode: "cells", selectedRowSources: []
      }
    });
    const viewport = wrapper.get(".result-virtual-grid__viewport").element as HTMLElement;
    Object.defineProperties(viewport, {
      clientWidth: { configurable: true, value: 320 },
      clientHeight: { configurable: true, value: 96 }
    });
    wrapper.vm.setScrollPosition({ left: 0, top: 0 });
    await nextTick();

    const firstRow = wrapper.get('[data-grid-source="0"]').element.parentElement as HTMLElement;
    expect(firstRow.style.top).toBe("0px");
    expect(wrapper.get(".result-virtual-grid__canvas").attributes("style"))
      .toContain("height: 96px");
    expect(wrapper.get('[data-grid-row="0"][data-grid-column="0"]').attributes("style"))
      .toContain("left: 34px");
    wrapper.unmount();
  });

  it("ignores cell and row interactions while a native scrollbar drag crosses the grid", async () => {
    const wrapper = mount(ResultVirtualGrid, {
      props: {
        rows: rows.slice(0, 20), columns: columns.slice(0, 5), headerHeight: 32,
        bufferScreens: 1, selectionMode: "cells", selectedRowSources: []
      }
    });
    const viewport = wrapper.get(".result-virtual-grid__viewport").element as HTMLElement;
    Object.defineProperties(viewport, {
      clientWidth: { configurable: true, value: 320 },
      clientHeight: { configurable: true, value: 128 },
      offsetWidth: { configurable: true, value: 328 },
      offsetHeight: { configurable: true, value: 136 },
      scrollWidth: { configurable: true, value: 634 },
      scrollHeight: { configurable: true, value: 640 }
    });
    vi.spyOn(viewport, "getBoundingClientRect").mockReturnValue({
      x: 0, y: 32, left: 0, top: 32, right: 328, bottom: 168, width: 328, height: 136,
      toJSON: () => ({})
    });
    wrapper.vm.setScrollPosition({ left: 0, top: 0 });
    await nextTick();

    const cells = wrapper.findAll(".result-virtual-grid__cell");
    const rowNumber = wrapper.get('[data-grid-source="0"]');
    await cells[0].trigger("pointerdown", { button: 0, clientX: 326, clientY: 64 });
    await cells[1].trigger("pointerover", { clientX: 100, clientY: 64 });
    await rowNumber.trigger("pointerover", { clientX: 10, clientY: 64 });
    expect(wrapper.classes()).toContain("is-scrollbar-dragging");
    expect(wrapper.emitted("cell-pointerdown")).toBeUndefined();
    expect(wrapper.emitted("cell-pointerenter")).toBeUndefined();
    expect(wrapper.emitted("row-pointerenter")).toBeUndefined();
    window.dispatchEvent(new PointerEvent("pointerup"));
    await nextTick();

    await cells[0].trigger("pointerdown", { button: 0, clientX: 100, clientY: 64 });
    expect(wrapper.classes()).not.toContain("is-scrollbar-dragging");
    expect(wrapper.emitted("cell-pointerdown")).toHaveLength(1);

    await cells[0].trigger("pointerdown", { button: 0, clientX: 100, clientY: 166 });
    await cells[1].trigger("pointerover", { clientX: 100, clientY: 64 });
    expect(wrapper.emitted("cell-pointerdown")).toHaveLength(1);
    expect(wrapper.emitted("cell-pointerenter")).toBeUndefined();
    window.dispatchEvent(new PointerEvent("pointercancel"));
    wrapper.unmount();
  });

  it("highlights complete selected rows and identity-based sparse cells", async () => {
    const wrapper = mount(ResultVirtualGrid, {
      props: {
        rows: rows.slice(0, 3), columns: columns.slice(0, 3), headerHeight: 32,
        bufferScreens: 1, selectionMode: "rows", selectedRowSources: [1],
        selectedCellKeys: [], hasFooter: true
      },
      slots: { footer: "<div class=\"test-footer\">sum</div>" }
    });
    const viewport = wrapper.get(".result-virtual-grid__viewport").element as HTMLElement;
    Object.defineProperties(viewport, {
      clientWidth: { configurable: true, value: 514 },
      clientHeight: { configurable: true, value: 160 }
    });
    wrapper.vm.setScrollPosition({ left: 0, top: 0 });
    await nextTick();
    expect(wrapper.get('[data-grid-source="1"]').element.parentElement?.classList)
      .toContain("result-row-selected");
    expect(wrapper.find(".test-footer").exists()).toBe(true);

    await wrapper.setProps({
      selectionMode: "cells", selectedRowSources: [], selectedCellKeys: ["0:0", "2:2"]
    });
    await nextTick();
    expect(wrapper.get('[data-grid-row="0"][data-grid-column="0"]').classes()).toContain("selected");
    expect(wrapper.get('[data-grid-row="2"][data-grid-column="2"]').classes()).toContain("selected");
    expect(wrapper.get('[data-grid-row="1"][data-grid-column="1"]').classes()).not.toContain("selected");
    wrapper.unmount();
  });

  it("marks the focused cell and scrolls it fully into view outside the sticky gutter", async () => {
    const wrapper = mount(ResultVirtualGrid, {
      props: {
        rows, columns, headerHeight: 32, bufferScreens: 1, selectionMode: "cells",
        selectedRowSources: [], selectedCellKeys: ["10:4"], focusedCellKey: "10:4"
      }
    });
    const viewport = wrapper.get(".result-virtual-grid__viewport").element as HTMLElement;
    Object.defineProperties(viewport, {
      clientWidth: { configurable: true, value: 274 },
      clientHeight: { configurable: true, value: 64 }
    });
    wrapper.vm.setScrollPosition({ left: 0, top: 0 });
    wrapper.vm.scrollCellIntoView(10, 4);
    await nextTick();

    expect(wrapper.vm.getScrollPosition()).toEqual({ left: 360, top: 288 });
    const focused = wrapper.get('[data-grid-row="10"][data-grid-column="4"]');
    expect(focused.classes()).toContain("selected");
    expect(focused.classes()).toContain("focused");

    wrapper.vm.scrollCellIntoView(10, 3);
    expect(wrapper.vm.getScrollPosition()).toEqual({ left: 360, top: 288 });
    wrapper.vm.scrollCellIntoView(0, 0);
    await nextTick();
    expect(wrapper.vm.getScrollPosition()).toEqual({ left: 0, top: 0 });
    wrapper.unmount();
  });

  it("edits a virtualized cell and distinguishes pending from posted values", async () => {
    const wrapper = mount(ResultVirtualGrid, {
      props: {
        rows: rows.slice(0, 2), columns: columns.slice(0, 2), headerHeight: 32,
        bufferScreens: 1, selectionMode: "cells", selectedRowSources: [],
        editingCell: { rowIndex: 0, columnIndex: 0 }, editingValue: "changed",
        cellStates: { "0:1": "pending", "1:1": "posted" }
      }
    });
    const viewport = wrapper.get(".result-virtual-grid__viewport").element as HTMLElement;
    Object.defineProperties(viewport, {
      clientWidth: { configurable: true, value: 320 },
      clientHeight: { configurable: true, value: 160 }
    });
    wrapper.vm.setScrollPosition({ left: 0, top: 0 });
    await nextTick();

    const input = wrapper.get('[aria-label="编辑结果值"]');
    expect((input.element as HTMLInputElement).value).toBe("changed");
    await input.setValue("next");
    await input.trigger("keydown", { key: "Enter" });
    expect(wrapper.emitted("update:editing-value")?.at(-1)).toEqual(["next"]);
    expect(wrapper.emitted("commit-edit")).toHaveLength(1);
    expect(wrapper.emitted("commit-edit")?.[0]).toEqual(["enter"]);
    expect(wrapper.get('[data-grid-row="0"][data-grid-column="1"]').classes()).toContain("result-cell-pending");
    expect(wrapper.get('[data-grid-row="1"][data-grid-column="1"]').classes()).toContain("result-cell-posted");
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
        bufferScreens: 1,
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
      props: { rows, columns, headerHeight: 32, bufferScreens: 1,
        selectionMode: "cells", selectedRowSources: [] }
    });
    const viewport = wrapper.get(".result-virtual-grid__viewport").element as HTMLElement;
    const wheel = new WheelEvent("wheel", { deltaY: 96, deltaMode: 0, cancelable: true });
    viewport.dispatchEvent(wheel);
    expect(wheel.defaultPrevented).toBe(false);
    expect(wrapper.find(".result-virtual-grid__canvas").exists()).toBe(true);
    wrapper.unmount();
  });

  it("clamps stale scroll offsets when filtering reduces rows and columns", async () => {
    const wrapper = mount(ResultVirtualGrid, {
      props: { rows, columns, headerHeight: 32, bufferScreens: 1,
        selectionMode: "cells", selectedRowSources: [] }
    });
    const viewport = wrapper.get(".result-virtual-grid__viewport").element as HTMLElement;
    Object.defineProperties(viewport, {
      clientWidth: { configurable: true, value: 514 },
      clientHeight: { configurable: true, value: 288 }
    });
    wrapper.vm.setScrollPosition({ left: 2400, top: 4800 });
    await nextTick();

    await wrapper.setProps({ rows: rows.slice(0, 1), columns: columns.slice(0, 2) });
    await nextTick();
    await nextTick();

    expect(wrapper.vm.getScrollPosition()).toEqual({ left: 0, top: 0 });
    expect(wrapper.find(".result-virtual-grid__header").exists()).toBe(true);
    expect(wrapper.findAll(".result-virtual-grid__header-cell")).toHaveLength(2);
    expect(wrapper.findAll(".result-virtual-grid__row")).toHaveLength(1);
    wrapper.unmount();
  });

  it("immediately covers scrollbar jumps, cancels stale frames and reuses body slots", async () => {
    let frameSequence = 0;
    const frames = new Map<number, FrameRequestCallback>();
    const requestAnimationFrame = vi.fn((callback: FrameRequestCallback) => {
      const frame = ++frameSequence;
      frames.set(frame, callback);
      return frame;
    });
    const cancelAnimationFrame = vi.fn((frame: number) => frames.delete(frame));
    vi.stubGlobal("requestAnimationFrame", requestAnimationFrame);
    vi.stubGlobal("cancelAnimationFrame", cancelAnimationFrame);

    const wrapper = mount(ResultVirtualGrid, {
      props: { rows, columns, headerHeight: 32, bufferScreens: 1,
        selectionMode: "cells", selectedRowSources: [] }
    });
    const viewport = wrapper.get(".result-virtual-grid__viewport").element as HTMLElement;
    Object.defineProperties(viewport, {
      clientWidth: { configurable: true, value: 514 },
      clientHeight: { configurable: true, value: 288 }
    });
    wrapper.vm.setScrollPosition({ left: 1200, top: 1600 });
    await nextTick();
    const firstRows = wrapper.findAll(".result-virtual-grid__row").map((row) => row.element);
    const firstCells = wrapper.findAll(".result-virtual-grid__cell").map((cell) => cell.element);
    const firstHeaders = wrapper.findAll(".result-virtual-grid__header-cell").map((header) => header.element);

    viewport.scrollTop = 1760;
    viewport.dispatchEvent(new Event("scroll"));
    expect(requestAnimationFrame).toHaveBeenCalledTimes(1);

    viewport.scrollTop = 3200;
    viewport.scrollLeft = 2400;
    viewport.dispatchEvent(new Event("scroll"));
    expect(cancelAnimationFrame).toHaveBeenCalledTimes(1);
    await nextTick();

    expect(wrapper.find('[data-grid-row="100"]').exists()).toBe(true);
    expect(wrapper.find('[data-grid-column="20"]').exists()).toBe(true);
    expect(wrapper.findAll(".result-virtual-grid__row").every((row) => firstRows.includes(row.element))).toBe(true);
    expect(wrapper.findAll(".result-virtual-grid__cell").every((cell) => firstCells.includes(cell.element))).toBe(true);
    expect(wrapper.findAll(".result-virtual-grid__header-cell")
      .every((header) => firstHeaders.includes(header.element))).toBe(true);

    for (const callback of frames.values()) callback(performance.now());
    await nextTick();
    expect(wrapper.find('[data-grid-row="100"]').exists()).toBe(true);
    expect(wrapper.find('[data-grid-column="20"]').exists()).toBe(true);
    wrapper.unmount();
  });

  it("keeps a 50 by 300 long-text grid within the adaptive cell budget", async () => {
    const mediumColumns = Array.from({ length: 300 }, (_, index): ResultVirtualColumn => ({
      key: `medium-${index}`, label: `字段 ${index}`, sourceIndex: index, visibleIndex: index,
      width: 100, headerRenderer: () => h("span", `字段 ${index}`)
    }));
    const longValue = "用于验证宽表滚动预算与长文本提示不会导致所有行列同时挂载，并保持快速拖动后的完整内容提示";
    const mediumRows = Array.from({ length: 50 }, (_, row) => ({
      sourceIndex: row,
      cells: Array.from({ length: 300 }, (_, column) => `${row}:${column}:${longValue}`)
    }));
    const wrapper = mount(ResultVirtualGrid, {
      props: { rows: mediumRows, columns: mediumColumns, headerHeight: 32, bufferScreens: 1,
        selectionMode: "cells", selectedRowSources: [] }
    });
    const viewport = wrapper.get(".result-virtual-grid__viewport").element as HTMLElement;
    Object.defineProperties(viewport, {
      clientWidth: { configurable: true, value: 1500 },
      clientHeight: { configurable: true, value: 640 }
    });
    wrapper.vm.setScrollPosition({ left: 10_000, top: 480 });
    await nextTick();

    const renderedCells = wrapper.findAll(".result-virtual-grid__cell");
    expect(renderedCells.length).toBeGreaterThan(0);
    expect(renderedCells.length).toBeLessThanOrEqual(800);
    expect(renderedCells.some((cell) => cell.attributes("title")?.includes(longValue))).toBe(true);
    wrapper.unmount();
  });

  it("shows a bounded target placeholder for 1000-column scrollbar jumps and settles once", async () => {
    vi.useFakeTimers();
    let frameSequence = 0;
    const frames = new Map<number, FrameRequestCallback>();
    vi.stubGlobal("requestAnimationFrame", vi.fn((callback: FrameRequestCallback) => {
      const frame = ++frameSequence;
      frames.set(frame, callback);
      return frame;
    }));
    vi.stubGlobal("cancelAnimationFrame", vi.fn((frame: number) => frames.delete(frame)));
    const wideColumns = Array.from({ length: 1000 }, (_, index): ResultVirtualColumn => ({
      key: `wide-${index}`, label: `字段 ${index}`, sourceIndex: index, visibleIndex: index,
      width: 120, headerRenderer: () => h("span", `字段 ${index}`)
    }));
    const wideRows = Array.from({ length: 50 }, (_, row) => ({
      sourceIndex: row,
      cells: Array.from({ length: 1000 }, (_, column) => `R${row}C${column}`)
    }));
    const wrapper = mount(ResultVirtualGrid, {
      props: { rows: wideRows, columns: wideColumns, headerHeight: 32, bufferScreens: 1,
        selectionMode: "cells", selectedRowSources: [],
        editingCell: { rowIndex: 0, columnIndex: 0 }, editingValue: "editing" }
    });
    const viewport = wrapper.get(".result-virtual-grid__viewport").element as HTMLElement;
    Object.defineProperties(viewport, {
      clientWidth: { configurable: true, value: 1200 },
      clientHeight: { configurable: true, value: 320 }
    });
    wrapper.vm.setScrollPosition({ left: 0, top: 0 });
    await nextTick();
    expect(wrapper.findAll(".result-virtual-grid__cell").length).toBeLessThanOrEqual(800);

    viewport.scrollLeft = 118_800;
    viewport.dispatchEvent(new Event("scroll"));
    await nextTick();

    expect(wrapper.classes()).toContain("is-seeking");
    expect(wrapper.find(".result-virtual-grid__seek-status").text()).toBe("快速定位中…");
    expect(wrapper.findAll(".result-virtual-grid__cell").length).toBeLessThanOrEqual(800);
    expect(wrapper.findAll(".result-virtual-grid__cell").every((cell) => cell.text() === "")).toBe(true);
    expect(wrapper.findAll(".result-virtual-grid__seek-header").some((header) => header.text().includes("字段 99")))
      .toBe(true);
    expect(wrapper.emitted("commit-edit")).toHaveLength(1);
    expect(wrapper.emitted("commit-edit")?.[0]).toEqual(["viewport"]);

    viewport.scrollLeft = 60_000;
    viewport.dispatchEvent(new Event("scroll"));
    viewport.scrollLeft = 118_800;
    viewport.dispatchEvent(new Event("scroll"));
    expect(frames.size).toBe(1);
    for (const callback of frames.values()) callback(performance.now());
    frames.clear();
    await nextTick();
    expect(wrapper.findAll('[data-grid-column="999"]').length).toBeGreaterThan(0);

    vi.advanceTimersByTime(80);
    await nextTick();
    expect(wrapper.classes()).not.toContain("is-seeking");
    expect(wrapper.find(".result-virtual-grid__seek-status").exists()).toBe(false);
    expect(wrapper.findAll(".result-virtual-grid__cell").some((cell) => cell.text().includes("C999"))).toBe(true);
    expect(wrapper.emitted("commit-edit")).toHaveLength(1);
    wrapper.unmount();
    vi.useRealTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });
});
