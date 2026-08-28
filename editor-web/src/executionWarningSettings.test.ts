import { describe, expect, it } from "vitest";
import {
  DEFAULT_EXECUTION_WARNING_MINUTES,
  normalizeExecutionWarningMinutes,
  parseExecutionWarningMinutes,
  serializeExecutionWarningMinutes,
} from "./executionWarningSettings";

describe("SQL execution warning settings", () => {
  it("uses the default thresholds when the setting is missing or invalid", () => {
    expect(DEFAULT_EXECUTION_WARNING_MINUTES).toEqual([1, 5]);
    expect(parseExecutionWarningMinutes()).toEqual([...DEFAULT_EXECUTION_WARNING_MINUTES]);
    expect(parseExecutionWarningMinutes("not-json")).toEqual([...DEFAULT_EXECUTION_WARNING_MINUTES]);
    expect(parseExecutionWarningMinutes("{}" as string)).toEqual([...DEFAULT_EXECUTION_WARNING_MINUTES]);
    expect(parseExecutionWarningMinutes("[0, 10]")).toEqual([...DEFAULT_EXECUTION_WARNING_MINUTES]);
  });

  it("preserves an explicit empty list and normalizes valid values", () => {
    expect(parseExecutionWarningMinutes("[]")).toEqual([]);
    expect(parseExecutionWarningMinutes("[30,10,30,1]")).toEqual([1, 10, 30]);
    expect(normalizeExecutionWarningMinutes([30, "10", 10, 0, 1_441, 20])).toEqual([10, 20, 30]);
    expect(serializeExecutionWarningMinutes([30, 10, 30])).toBe("[10,30]");
  });

  it("limits normalized values to twenty entries", () => {
    const values = Array.from({ length: 25 }, (_, index) => index + 1);
    expect(normalizeExecutionWarningMinutes(values)).toEqual(values.slice(0, 20));
  });
});
