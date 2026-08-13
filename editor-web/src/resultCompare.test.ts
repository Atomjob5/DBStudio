import { describe, expect, it } from "vitest";
import { comparisonCellKeys, resultValuesEqual } from "./resultCompare";

const rows = [
  { sourceIndex: 0, cells: ["Alpha", null, "same"] },
  { sourceIndex: 1, cells: ["alpha", null, "different"] },
  { sourceIndex: 2, cells: ["Beta", "NULL", "same"] }
];

describe("result record comparison", () => {
  it("compares nulls and text with optional case sensitivity", () => {
    expect(resultValuesEqual(null, null, true)).toBe(true);
    expect(resultValuesEqual(null, "NULL", false)).toBe(false);
    expect(resultValuesEqual("Alpha", "alpha", false)).toBe(true);
    expect(resultValuesEqual("Alpha", "alpha", true)).toBe(false);
    expect(resultValuesEqual(" Alpha", "Alpha", false)).toBe(false);
  });

  it("highlights matching cells across the complete visible record and excludes the anchor", () => {
    expect(comparisonCellKeys(rows, [0, 1, 2], { sourceRow: 0, sourceColumn: 0 }, {
      highlightMode: "identical", scope: "record", caseSensitive: false
    })).toEqual(["1:0", "1:1", "2:2"]);
  });

  it("limits difference comparison to the current column", () => {
    expect(comparisonCellKeys(rows, [0, 1, 2], { sourceRow: 0, sourceColumn: 0 }, {
      highlightMode: "different", scope: "column", caseSensitive: false
    })).toEqual(["2:0"]);
  });
});
