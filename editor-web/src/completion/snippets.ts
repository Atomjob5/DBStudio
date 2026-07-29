import type { CompletionCandidate, SqlCompletionSnippet } from "../types";

export const MAX_SQL_SNIPPETS = 100;
export const MAX_SQL_SNIPPET_TRIGGER_LENGTH = 64;
export const MAX_SQL_SNIPPET_REMARKS_LENGTH = 200;
export const MAX_SQL_SNIPPET_LENGTH = 64 * 1024;
export const MAX_SQL_SNIPPETS_SETTING_LENGTH = 256 * 1024;

const TRIGGER_PATTERN = /^[\p{L}\p{N}_$]+$/u;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function normalizeSnippetTrigger(value: string): string {
  return value.trim().toLocaleLowerCase();
}

export function validateSqlCompletionSnippet(
  value: SqlCompletionSnippet,
  siblings: SqlCompletionSnippet[] = [],
): string | undefined {
  const trigger = value.trigger.trim();
  if (!trigger) return "请输入提示词";
  if (Array.from(trigger).length > MAX_SQL_SNIPPET_TRIGGER_LENGTH) {
    return `提示词不能超过${MAX_SQL_SNIPPET_TRIGGER_LENGTH}个字符`;
  }
  if (!TRIGGER_PATTERN.test(trigger)) return "提示词只能包含字母、数字、下划线和 $";
  if (Array.from(value.remarks).length > MAX_SQL_SNIPPET_REMARKS_LENGTH) {
    return `备注不能超过${MAX_SQL_SNIPPET_REMARKS_LENGTH}个字符`;
  }
  if (!value.sql.trim()) return "请输入 SQL 片段";
  if (value.sql.length > MAX_SQL_SNIPPET_LENGTH) return "单条 SQL 片段不能超过 64 KiB";
  if (siblings.some((item) => item.id !== value.id
    && normalizeSnippetTrigger(item.trigger) === normalizeSnippetTrigger(trigger))) {
    return "该提示词已存在";
  }
  return undefined;
}

export function parseSqlCompletionSnippets(value: string | undefined): SqlCompletionSnippet[] {
  if (!value) return [];
  try {
    if (value.length > MAX_SQL_SNIPPETS_SETTING_LENGTH) return [];
    const parsed: unknown = JSON.parse(value);
    if (!Array.isArray(parsed) || parsed.length > MAX_SQL_SNIPPETS) return [];
    const snippets: SqlCompletionSnippet[] = [];
    for (const item of parsed) {
      if (!isSnippetRecord(item)) return [];
      const normalized = { ...item, trigger: item.trigger.trim() };
      if (validateSqlCompletionSnippet(normalized, snippets)) return [];
      snippets.push(normalized);
    }
    if (validateSqlCompletionSnippets(snippets)) return [];
    return snippets;
  } catch {
    return [];
  }
}

export function serializeSqlCompletionSnippets(value: SqlCompletionSnippet[]): string {
  return JSON.stringify(value.map((item) => ({
    id: item.id,
    trigger: item.trigger.trim(),
    remarks: item.remarks,
    sql: item.sql,
  })));
}

export function validateSqlCompletionSnippets(value: SqlCompletionSnippet[]): string | undefined {
  if (value.length > MAX_SQL_SNIPPETS) return `SQL片段不能超过${MAX_SQL_SNIPPETS}条`;
  const ids = new Set<string>();
  for (const snippet of value) {
    if (!UUID_PATTERN.test(snippet.id) || ids.has(snippet.id)) return "SQL片段ID无效或重复";
    const error = validateSqlCompletionSnippet(snippet, value);
    if (error) return error;
    ids.add(snippet.id);
  }
  if (serializeSqlCompletionSnippets(value).length > MAX_SQL_SNIPPETS_SETTING_LENGTH) {
    return "SQL片段设置总大小不能超过 256 KiB";
  }
  return undefined;
}

export function matchingSnippetCandidate(
  snippets: SqlCompletionSnippet[],
  activeWord: string,
): CompletionCandidate | undefined {
  if (!activeWord) return undefined;
  const snippet = snippets.find((item) =>
    normalizeSnippetTrigger(item.trigger) === normalizeSnippetTrigger(activeWord));
  if (!snippet) return undefined;
  return {
    displayLabel: snippet.trigger,
    documentationPath: snippet.trigger,
    insertText: snippet.sql,
    filterText: activeWord,
    kind: "snippet",
    remarks: snippet.remarks,
    typeName: "SQL片段",
  };
}

function isSnippetRecord(value: unknown): value is SqlCompletionSnippet {
  if (!value || typeof value !== "object") return false;
  const record = value as Record<string, unknown>;
  return typeof record.id === "string" && UUID_PATTERN.test(record.id)
    && typeof record.trigger === "string"
    && typeof record.remarks === "string"
    && typeof record.sql === "string";
}
