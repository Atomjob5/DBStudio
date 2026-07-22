import { describe, expect, it } from "vitest";
import { completionDocumentation, truncateCompletionComment } from "./presentation";

describe("completion presentation", () => {
  it("truncates by Unicode code point without cutting emoji", () => {
    const comment = `${"注".repeat(79)}😀尾部`;
    expect(Array.from(truncateCompletionComment(comment))).toHaveLength(81);
    expect(truncateCompletionComment(comment)).toBe(`${"注".repeat(79)}😀…`);
  });

  it("keeps the full qualified path, type and comment in documentation", () => {
    const documentation = completionDocumentation({ label: "id", qualifiedLabel: "sales.orders.id",
      insertText: "`id`", kind: "column", typeName: "BIGINT", remarks: "完整字段注释" });
    expect(documentation).toContain("sales.orders.id");
    expect(documentation).toContain("BIGINT");
    expect(documentation).toContain("完整字段注释");
  });
});
