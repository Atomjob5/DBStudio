import { describe, expect, it } from "vitest";
import { executionDurationParts, executionDurationText } from "./executionDuration";

describe("execution duration", () => {
  it.each([
    [123, "123ms"],
    [1_234, "1s 234ms"],
    [61_234, "1m 1s 234ms"],
    [3_661_234, "61m 1s 234ms"]
  ])("formats %i milliseconds", (elapsed, expected) => {
    expect(executionDurationText(elapsed)).toBe(expected);
  });

  it.each([
    [9_999, "normal"],
    [10_000, "warning"],
    [59_999, "warning"],
    [60_000, "danger"]
  ] as const)("classifies %i milliseconds as %s", (elapsed, expected) => {
    expect(executionDurationParts(elapsed).level).toBe(expected);
  });

  it("clamps invalid and negative elapsed times", () => {
    expect(executionDurationText(-1)).toBe("0ms");
    expect(executionDurationText(Number.NaN)).toBe("0ms");
  });
});
