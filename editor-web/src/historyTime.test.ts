import { describe, expect, it } from "vitest";
import { formatHistoryTime } from "./historyTime";

describe("Beijing query history time", () => {
  it.each([
    ["2026-09-05T09:59:19.145408Z", "2026-09-05 17:59:19"],
    ["2026-12-31T16:00:00Z", "2027-01-01 00:00:00"],
    ["2026-09-05T17:59:19+08:00", "2026-09-05 17:59:19"],
    [null, "—"], [undefined, "—"], ["", "—"], ["  ", "—"], ["invalid", "—"]
  ])("formats %s as %s", (value, expected) => {
    expect(formatHistoryTime(value)).toBe(expected);
  });
});
