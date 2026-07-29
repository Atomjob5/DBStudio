import type { CompletionCandidate, SqlCompletionSnippet } from "../types";

export const MAX_SQL_SNIPPETS = 100;
export const MAX_SQL_SNIPPET_TRIGGER_LENGTH = 64;
export const MAX_SQL_SNIPPET_REMARKS_LENGTH = 200;
export const MAX_SQL_SNIPPET_LENGTH = 64 * 1024;
export const MAX_SQL_SNIPPETS_SETTING_LENGTH = 256 * 1024;

const TRIGGER_PATTERN = /^[\p{L}\p{N}_$]+$/u;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const VARIABLE_FIRST_CHARACTER_PATTERN = /^[\p{L}_]$/u;
const VARIABLE_CHARACTER_PATTERN = /^[\p{L}\p{N}_]$/u;

export interface SqlSnippetCompilation {
  insertText: string;
  variables: string[];
  hasVariables: boolean;
}

export interface SqlSnippetSyntaxError {
  message: string;
  offset: number;
}

export type SqlSnippetCompilationResult =
  | { ok: true; compilation: SqlSnippetCompilation }
  | { ok: false; error: SqlSnippetSyntaxError };

export function normalizeSnippetTrigger(value: string): string {
  return value.trim().toLocaleLowerCase();
}

export function validateSqlCompletionSnippet(
  value: SqlCompletionSnippet,
  siblings: SqlCompletionSnippet[] = [],
): string | undefined {
  const baseError = validateSqlCompletionSnippetBase(value, siblings);
  if (baseError) return baseError;
  const compilation = compileSqlSnippet(value.sql);
  if (!compilation.ok) return formatSqlSnippetSyntaxError(compilation.error);
  return undefined;
}

export function compileSqlSnippet(value: string): SqlSnippetCompilationResult {
  const variables: string[] = [];
  const placeholderByVariable = new Map<string, number>();
  const fail = (message: string, sourceOffset: number): SqlSnippetCompilationResult =>
    syntaxFailure(message, Array.from(value.slice(0, sourceOffset)).length);
  let insertText = "";
  let literalStart = 0;
  let index = 0;

  while (index < value.length) {
    if (value[index] !== "$" || value[index + 1] !== "{") {
      index += 1;
      continue;
    }

    insertText += escapeMonacoSnippetText(value.slice(literalStart, index));
    const variableStart = index;
    const nameStart = index + 2;
    const closingBrace = value.indexOf("}", nameStart);
    if (closingBrace < 0) {
      return fail("变量缺少结束符 }", variableStart);
    }
    const nestedVariable = value.indexOf("${", nameStart);
    if (nestedVariable >= 0 && nestedVariable < closingBrace) {
      return fail("变量不能嵌套", nestedVariable);
    }
    const name = value.slice(nameStart, closingBrace);
    if (!name) return fail("变量名不能为空", nameStart);

    let characterOffset = 0;
    let characterIndex = 0;
    for (const character of name) {
      const valid = characterIndex === 0
        ? VARIABLE_FIRST_CHARACTER_PATTERN.test(character)
        : VARIABLE_CHARACTER_PATTERN.test(character);
      if (!valid) {
        return fail(characterIndex === 0
          ? "变量名必须以字母或下划线开头"
          : "变量名只能包含字母、数字和下划线", nameStart + characterOffset);
      }
      characterOffset += character.length;
      characterIndex += 1;
    }

    let placeholder = placeholderByVariable.get(name);
    if (placeholder === undefined) {
      variables.push(name);
      placeholder = variables.length;
      placeholderByVariable.set(name, placeholder);
    }
    insertText += "${" + placeholder + "}";
    index = closingBrace + 1;
    literalStart = index;
  }

  insertText += escapeMonacoSnippetText(value.slice(literalStart));
  if (variables.length) insertText += "$0";
  return {
    ok: true,
    compilation: {
      insertText,
      variables,
      hasVariables: variables.length > 0,
    },
  };
}

export function sqlSnippetSyntaxError(value: string): SqlSnippetSyntaxError | undefined {
  const result = compileSqlSnippet(value);
  return result.ok ? undefined : result.error;
}

export function formatSqlSnippetSyntaxError(error: SqlSnippetSyntaxError): string {
  return `SQL片段变量语法无效（第${error.offset + 1}个字符）：${error.message}`;
}

function validateSqlCompletionSnippetBase(
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
      snippets.push(normalized);
    }
    if (validateSqlCompletionSnippetsInternal(snippets, false)) return [];
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
  return validateSqlCompletionSnippetsInternal(value, true);
}

function validateSqlCompletionSnippetsInternal(
  value: SqlCompletionSnippet[],
  validateVariableSyntax: boolean,
): string | undefined {
  if (value.length > MAX_SQL_SNIPPETS) return `SQL片段不能超过${MAX_SQL_SNIPPETS}条`;
  const ids = new Set<string>();
  for (const snippet of value) {
    if (!UUID_PATTERN.test(snippet.id) || ids.has(snippet.id)) return "SQL片段ID无效或重复";
    const error = validateVariableSyntax
      ? validateSqlCompletionSnippet(snippet, value)
      : validateSqlCompletionSnippetBase(snippet, value);
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
  return matchingSnippetCandidates(snippets, activeWord)[0];
}

export function matchingSnippetCandidates(
  snippets: SqlCompletionSnippet[],
  activeWord: string,
): CompletionCandidate[] {
  const normalizedWord = normalizeSnippetTrigger(activeWord);
  if (!normalizedWord) return [];
  return snippets
    .map((snippet, index) => ({
      snippet,
      index,
      normalizedTrigger: normalizeSnippetTrigger(snippet.trigger),
    }))
    .filter(({ snippet, normalizedTrigger }) =>
      normalizedTrigger.startsWith(normalizedWord) && compileSqlSnippet(snippet.sql).ok)
    .sort((left, right) => {
      const leftExact = left.normalizedTrigger === normalizedWord;
      const rightExact = right.normalizedTrigger === normalizedWord;
      return leftExact === rightExact ? left.index - right.index : leftExact ? -1 : 1;
    })
    .map(({ snippet }) => ({
      displayLabel: snippet.trigger,
      documentationPath: snippet.trigger,
      insertText: snippet.sql,
      filterText: snippet.trigger,
      kind: "snippet",
      remarks: snippet.remarks,
      typeName: "SQL片段",
    }));
}

function isSnippetRecord(value: unknown): value is SqlCompletionSnippet {
  if (!value || typeof value !== "object") return false;
  const record = value as Record<string, unknown>;
  return typeof record.id === "string" && UUID_PATTERN.test(record.id)
    && typeof record.trigger === "string"
    && typeof record.remarks === "string"
    && typeof record.sql === "string";
}

function escapeMonacoSnippetText(value: string): string {
  return value.replace(/[\\$}]/g, "\\$&");
}

function syntaxFailure(message: string, offset: number): SqlSnippetCompilationResult {
  return { ok: false, error: { message, offset } };
}
