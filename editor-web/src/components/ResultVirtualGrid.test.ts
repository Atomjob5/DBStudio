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
    vi.stubGlobal("matchMedia", () => ({
      matches: false, media: "", onchange: null, addListener: vi.fn(), removeListener: vi.fn(),
      addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn()
    }));
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

  it("animates coarse wheel input but leaves precise input native", async () => {
    let time = 0;
    const callbacks = new Map<number, FrameRequestCallback>();
    let id = 0;
    vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => {
      callbacks.set(++id, callback);
      return id;
    });
    vi.spyOn(window, "cancelAnimationFrame").mockImplementation((frame) => { callbacks.delete(frame); });
    vi.spyOn(performance, "now").mockImplementation(() => time);

    const wrapper = mount(ResultVirtualGrid, {
      props: { rows, columns, headerHeight: 32, selectionMode: "cells", selectedRowSources: [] }
    });
    const viewport = wrapper.get(".result-virtual-grid__viewport").element as HTMLElement;
    Object.defineProperties(viewport, {
      clientWidth: { configurable: true, value: 514 },
      clientHeight: { configurable: true, value: 320 }
    });

    const coarse = new WheelEvent("wheel", { deltaY: 96, deltaMode: 0, cancelable: true });
    viewport.dispatchEvent(coarse);
    expect(coarse.defaultPrevented).toBe(true);
    expect(viewport.scrollTop).toBe(0);
    time = 60;
    callbacks.get(Math.max(...callbacks.keys()))?.(time);
    expect(viewport.scrollTop).toBeGreaterThan(0);
    time = 120;
    callbacks.get(Math.max(...callbacks.keys()))?.(time);
    expect(viewport.scrollTop).toBe(96);

    const before = viewport.scrollTop;
    const precise = new WheelEvent("wheel", { deltaY: 4, deltaMode: 0, cancelable: true });
    viewport.dispatchEvent(precise);
    expect(precise.defaultPrevented).toBe(false);
    expect(viewport.scrollTop).toBe(before);
    wrapper.unmount();
  });

  it("retargets consecutive wheel input and cancels the active frame when unmounted", () => {
    let time = 0;
    const callbacks = new Map<number, FrameRequestCallback>();
    let id = 0;
    const request = vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => {
      callbacks.set(++id, callback);
      return id;
    });
    const cancel = vi.spyOn(window, "cancelAnimationFrame").mockImplementation((frame) => {
      callbacks.delete(frame);
    });
    vi.spyOn(performance, "now").mockImplementation(() => time);
    const runLatestFrame = () => {
      const frame = Math.max(...callbacks.keys());
      const callback = callbacks.get(frame);
      callbacks.delete(frame);
      callback?.(time);
    };

    const wrapper = mount(ResultVirtualGrid, {
      props: { rows, columns, headerHeight: 32, selectionMode: "cells", selectedRowSources: [] }
    });
    const viewport = wrapper.get(".result-virtual-grid__viewport").element as HTMLElement;
    Object.defineProperties(viewport, {
      clientWidth: { configurable: true, value: 514 },
      clientHeight: { configurable: true, value: 320 }
    });

    viewport.dispatchEvent(new WheelEvent("wheel", { deltaY: 96, cancelable: true }));
    time = 40;
    runLatestFrame();
    const firstProgress = viewport.scrollTop;
    viewport.dispatchEvent(new WheelEvent("wheel", { deltaY: 96, cancelable: true }));
    expect(request).toHaveBeenCalledTimes(2);
    expect(viewport.scrollTop).toBe(firstProgress);

    time = 100;
    runLatestFrame();
    time = 160;
    runLatestFrame();
    expect(viewport.scrollTop).toBe(192);

    viewport.dispatchEvent(new WheelEvent("wheel", { deltaY: 96, cancelable: true }));
    wrapper.unmount();
    expect(cancel).toHaveBeenCalled();
  });

  it("respects reduced motion while retaining the virtual grid", () => {
    vi.stubGlobal("matchMedia", () => ({
      matches: true, media: "", onchange: null, addListener: vi.fn(), removeListener: vi.fn(),
      addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn()
    }));
    const animation = vi.spyOn(window, "requestAnimationFrame");
    const wrapper = mount(ResultVirtualGrid, {
      props: { rows, columns, headerHeight: 32, selectionMode: "cells", selectedRowSources: [] }
    });
    const viewport = wrapper.get(".result-virtual-grid__viewport").element as HTMLElement;
    const wheel = new WheelEvent("wheel", { deltaY: 96, deltaMode: 0, cancelable: true });
    viewport.dispatchEvent(wheel);
    expect(wheel.defaultPrevented).toBe(false);
    expect(animation).not.toHaveBeenCalled();
    expect(wrapper.find(".result-virtual-grid__canvas").exists()).toBe(true);
    wrapper.unmount();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });
});
