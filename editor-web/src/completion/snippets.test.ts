import { describe, expect, it } from "vitest";
import {
  matchingSnippetCandidate,
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
  it("matches only a complete trigger and ignores case", () => {
    expect(matchingSnippetCandidate([snippet], "s")).toBeUndefined();
    expect(matchingSnippetCandidate([snippet], "SF")).toMatchObject({
      displayLabel: "sf",
      insertText: "select *\nfrom",
      kind: "snippet",
      remarks: "通用查询",
    });
  });

  it("round-trips valid snippets and rejects malformed persisted settings", () => {
    const serialized = serializeSqlCompletionSnippets([snippet]);
    expect(parseSqlCompletionSnippets(serialized)).toEqual([snippet]);
    expect(parseSqlCompletionSnippets("not-json")).toEqual([]);
    expect(parseSqlCompletionSnippets('[{"id":"bad","trigger":"sf","remarks":"","sql":"select 1"}]'))
      .toEqual([]);
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
