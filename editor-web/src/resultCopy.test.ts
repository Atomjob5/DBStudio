import { describe, expect, it } from "vitest";
import { resultColumnRemarksText, resultCopyText, separatorCharacter } from "./resultCopy";

describe("result copy formatting", () => {
  const columns = [{ label: "display_id", index: 0 }, { label: "name", index: 2 }];
  const rows = [["1", "ignored", "Alice, Inc."], [null, "ignored", "line\n\"two\""]];

  it("copies headers and loaded rows in the supplied display order without changing values", () => {
    expect(resultCopyText(columns, rows, "headers-and-data", "comma"))
      .toBe('display_id,name\n1,Alice, Inc.\nNULL,line\n"two"');
    expect(resultCopyText(columns, rows, "data", "semicolon"))
      .toBe('1;Alice, Inc.\nNULL;line\n"two"');
  });

  it("copies JSON values byte-for-byte", () => {
    const json = '{"source":"oracle-docker","row":2}';
    expect(resultCopyText([{ label: "payload", index: 0 }], [[json]], "data", "comma"))
      .toBe(json);
  });

  it("supports all separator presets and empty loaded data", () => {
    expect(separatorCharacter("comma")).toBe(",");
    expect(separatorCharacter("tab")).toBe("\t");
    expect(separatorCharacter("semicolon")).toBe(";");
    expect(separatorCharacter("pipe")).toBe("|");
    expect(resultCopyText(columns, [], "headers-and-data", "pipe")).toBe("display_id|name");
    expect(resultCopyText(columns, [], "data", "comma")).toBe("");
  });

  it("copies column labels with SQL-escaped remarks using every separator preset", () => {
    const remarkedColumns = [
      { label: "col1", remarks: "列1（1：启用，0：停用）" },
      { label: "col2", remarks: '客户"名称(enum)' },
      { label: "col3", remarks: "   " },
      { label: "col4", remarks: "（仅枚举说明）" },
    ];
    expect(resultColumnRemarksText(remarkedColumns, "comma"))
      .toBe('col1 as "列1",col2 as "客户""名称",col3,col4');
    expect(resultColumnRemarksText(remarkedColumns, "tab"))
      .toBe('col1 as "列1"\tcol2 as "客户""名称"\tcol3\tcol4');
    expect(resultColumnRemarksText(remarkedColumns, "semicolon"))
      .toBe('col1 as "列1";col2 as "客户""名称";col3;col4');
    expect(resultColumnRemarksText(remarkedColumns, "pipe"))
      .toBe('col1 as "列1"|col2 as "客户""名称"|col3|col4');
  });
});
