import { describe, expect, it } from "vitest";
import {
  compileSqlSnippet,
  matchingSnippetCandidate,
  matchingSnippetCandidates,
  parseSqlCompletionSnippets,
  serializeSqlCompletionSnippets,
  validateSqlCompletionSnippet,
  validateSqlCompletionSnippets,
} from "./snippets";
import type { SqlCompletionSnippet } from "../types";

const snippet: SqlCompletionSnippet = {
  id: "5d652bad-8dce-4b56-9c94-d62d74a74576",
  trigger: "sf",
  remarks: "通用查询",
  sql: "select *\nfrom",
};

describe("SQL completion snippets", () => {
  it("matches a non-empty trigger prefix, ignores case and keeps the full trigger as filter text", () => {
    expect(matchingSnippetCandidate([snippet], "")).toBeUndefined();
    expect(matchingSnippetCandidate([snippet], "s")).toMatchObject({
      displayLabel: "sf",
      filterText: "sf",
      insertText: "select *\nfrom",
      kind: "snippet",
    });
    expect(matchingSnippetCandidate([snippet], "SF")).toMatchObject({
      displayLabel: "sf",
      filterText: "sf",
      insertText: "select *\nfrom",
      kind: "snippet",
      remarks: "通用查询",
    });
  });

  it("returns every shared-prefix snippet and ranks an exact trigger first", () => {
    const exact = { ...snippet, id: "exact", trigger: "s", sql: "select 1" };
    const another = { ...snippet, id: "another", trigger: "sales", sql: "select 2" };
    expect(matchingSnippetCandidates([snippet, exact, another], "s").map((item) => item.displayLabel))
      .toEqual(["s", "sf", "sales"]);
  });

  it("compiles variables into ordered Monaco placeholders with linked repetitions", () => {
    const result = compileSqlSnippet(
      "select '${column_value}', ${第二列}, ${column_value}, ${ID}, ${id}",
    );
    expect(result).toEqual({
      ok: true,
      compilation: {
        insertText: "select '${1}', ${2}, ${1}, ${3}, ${4}$0",
        variables: ["column_value", "第二列", "ID", "id"],
        hasVariables: true,
      },
    });
  });

  it("preserves multiline SQL and escapes Monaco snippet metacharacters outside variables", () => {
    const result = compileSqlSnippet("select $price, $$, C:\\tmp}\nwhere id = ${id}");
    expect(result).toEqual({
      ok: true,
      compilation: {
        insertText: "select \\$price, \\$\\$, C:\\\\tmp\\}\nwhere id = ${1}$0",
        variables: ["id"],
        hasVariables: true,
      },
    });
    expect(compileSqlSnippet("select $price, C:\\tmp}")).toEqual({
      ok: true,
      compilation: {
        insertText: "select \\$price, C:\\\\tmp\\}",
        variables: [],
        hasVariables: false,
      },
    });
  });

  it("reports invalid variable syntax and its exact source position", () => {
    const invalid = [
      ["${}", 2, "不能为空"],
      ["${1name}", 2, "必须以"],
      ["${name-value}", 6, "只能包含"],
      ["${na me}", 4, "只能包含"],
      ["${outer${inner}}", 7, "不能嵌套"],
      ["${missing", 0, "缺少结束符"],
    ] as const;
    for (const [sql, offset, message] of invalid) {
      const result = compileSqlSnippet(sql);
      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.error.offset).toBe(offset);
        expect(result.error.message).toContain(message);
      }
    }
  });

  it("round-trips valid snippets and rejects malformed persisted settings", () => {
    const serialized = serializeSqlCompletionSnippets([snippet]);
    expect(parseSqlCompletionSnippets(serialized)).toEqual([snippet]);
    expect(parseSqlCompletionSnippets("not-json")).toEqual([]);
    expect(parseSqlCompletionSnippets('[{"id":"bad","trigger":"sf","remarks":"","sql":"select 1"}]'))
      .toEqual([]);
  });

  it("keeps historical snippets with invalid variable syntax but excludes them from completion", () => {
    const invalidSnippet = { ...snippet, sql: "select ${}" };
    expect(parseSqlCompletionSnippets(JSON.stringify([invalidSnippet]))).toEqual([invalidSnippet]);
    expect(matchingSnippetCandidate([invalidSnippet], "sf")).toBeUndefined();
    expect(validateSqlCompletionSnippet(invalidSnippet)).toContain("第10个字符");
  });

  it("validates trigger characters, duplicate triggers and collection IDs", () => {
    expect(validateSqlCompletionSnippet({ ...snippet, trigger: "查询_1$" })).toBeUndefined();
    expect(validateSqlCompletionSnippet({ ...snippet, trigger: "select all" })).toContain("只能包含");
    expect(validateSqlCompletionSnippet({
      ...snippet,
      id: "dbf3fc10-3b72-41b8-a83f-9f786314303e",
      trigger: "SF",
    }, [snippet])).toBe("该提示词已存在");
    expect(validateSqlCompletionSnippets([snippet, { ...snippet }])).toContain("ID");
  });
});
