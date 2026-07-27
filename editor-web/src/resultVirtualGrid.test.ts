import { describe, expect, it } from "vitest";
import {
  clampScroll, columnMetrics, containsRange, visibleColumnRange, visibleRowRange
} from "./resultVirtualGrid";

describe("result virtual grid calculations", () => {
  it("buffers rows by viewport screens and clips the first and last page", () => {
    expect(visibleRowRange(200, 32, 0, 320, 32, 0.5)).toEqual({ start: 0, end: 14 });
    expect(visibleRowRange(200, 32, 640, 320, 32, 1)).toEqual({ start: 11, end: 38 });
    expect(visibleRowRange(200, 32, 0, 320, 32, 3)).toEqual({ start: 0, end: 36 });
    expect(visibleRowRange(5, 32, 999, 320, 32, 1)).toEqual({ start: 5, end: 5 });
  });

  it("buffers variable-width columns by viewport pixels and preserves the full width", () => {
    const widths = [100, 120, 80, 200, 90];
    const metrics = columnMetrics(widths);
    expect(metrics).toEqual({ offsets: [0, 100, 220, 300, 500], totalWidth: 590 });
    expect(visibleColumnRange(widths, metrics, 0, 254, 34, 0)).toEqual({ start: 0, end: 2 });
    expect(visibleColumnRange(widths, metrics, 225, 254, 34, 0.5)).toEqual({ start: 1, end: 5 });
    expect(visibleColumnRange(widths, metrics, 225, 254, 34, 1)).toEqual({ start: 0, end: 5 });
  });

  it("detects whether the rendered buffer covers a jumped viewport", () => {
    expect(containsRange({ start: 10, end: 30 }, { start: 15, end: 25 })).toBe(true);
    expect(containsRange({ start: 10, end: 30 }, { start: 5, end: 25 })).toBe(false);
    expect(containsRange({ start: 10, end: 30 }, { start: 15, end: 31 })).toBe(false);
  });

  it("clamps restored scroll targets", () => {
    expect(clampScroll(-5, 1000, 300)).toBe(0);
    expect(clampScroll(900, 1000, 300)).toBe(700);
  });
});
