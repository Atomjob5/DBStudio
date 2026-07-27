import { describe, expect, it } from "vitest";
import {
  clampScroll, columnMetrics, visibleColumnRange, visibleRowRange
} from "./resultVirtualGrid";

describe("result virtual grid calculations", () => {
  it("keeps only visible rows plus the configured overscan", () => {
    expect(visibleRowRange(200, 32, 0, 320, 32, 4)).toEqual({ start: 0, end: 13 });
    expect(visibleRowRange(200, 32, 640, 320, 32, 4)).toEqual({ start: 16, end: 33 });
    expect(visibleRowRange(5, 32, 999, 320, 32, 4)).toEqual({ start: 5, end: 5 });
  });

  it("finds visible variable-width columns and preserves the full width", () => {
    const widths = [100, 120, 80, 200, 90];
    const metrics = columnMetrics(widths);
    expect(metrics).toEqual({ offsets: [0, 100, 220, 300, 500], totalWidth: 590 });
    expect(visibleColumnRange(widths, metrics, 0, 254, 34, 0)).toEqual({ start: 0, end: 2 });
    expect(visibleColumnRange(widths, metrics, 225, 254, 34, 1)).toEqual({ start: 1, end: 5 });
  });

  it("clamps restored scroll targets", () => {
    expect(clampScroll(-5, 1000, 300)).toBe(0);
    expect(clampScroll(900, 1000, 300)).toBe(700);
  });
});
