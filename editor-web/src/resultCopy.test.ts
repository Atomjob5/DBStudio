import { describe, expect, it } from "vitest";
import { resultCopyText, separatorCharacter } from "./resultCopy";

describe("result copy formatting", () => {
  const columns = [{ label: "display_id", index: 0 }, { label: "name", index: 2 }];
  const rows = [["1", "ignored", "Alice, Inc."], [null, "ignored", "line\n\"two\""]];

  it("copies headers and loaded rows in the supplied display order with CSV escaping", () => {
    expect(resultCopyText(columns, rows, "headers-and-data", "comma"))
      .toBe('display_id,name\n1,"Alice, Inc."\nNULL,"line\n""two"""');
    expect(resultCopyText(columns, rows, "data", "semicolon"))
      .toBe('1;Alice, Inc.\nNULL;"line\n""two"""');
  });

  it("supports all separator presets and empty loaded data", () => {
    expect(separatorCharacter("comma")).toBe(",");
    expect(separatorCharacter("tab")).toBe("\t");
    expect(separatorCharacter("semicolon")).toBe(";");
    expect(separatorCharacter("pipe")).toBe("|");
    expect(resultCopyText(columns, [], "headers-and-data", "pipe")).toBe("display_id|name");
    expect(resultCopyText(columns, [], "data", "comma")).toBe("");
  });
});
