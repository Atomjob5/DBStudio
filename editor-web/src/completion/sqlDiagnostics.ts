import type { LocalSqlDiagnostics, SqlDiagnostic, SqlQuickFix } from "../types";
import {
  createCompletionIndex,
  diagnosticNameCandidates,
  normalize,
  parseCtes,
  parseSources,
  sourceColumnNames,
  statementKind,
} from "../sqlCompletion";
import type { CompletionIndex, CompletionSource } from "../sqlCompletion";
import { completionDialect } from "./sqlDialect";
import { lexStatementAt, scanSqlStructure } from "./sqlLexer";
import type { SqlStatementRange, SqlToken } from "./sqlLexer";

const COLUMN_CLAUSES = new Set(["select", "where", "on", "group", "order", "having", "set", "returning"]);
const CLAUSE_WORDS = new Set(["select", "from", "where", "on", "group", "order", "having", "set", "returning"]);
const JOIN_END_WORDS = new Set(["join", "where", "group", "order", "having", "limit", "offset", "fetch",
  "union", "minus", "except", "intersect", "returning", "connect", "start", "for"]);

interface DiagnosticScope {
  depth: number;
  sources: CompletionSource[];
}

/** Runs document-local structural and metadata diagnostics without touching the network. */
export function resolveLocalSqlDiagnostics(index: CompletionIndex | undefined, providerId: string,
                                           sql: string): LocalSqlDiagnostics {
  const dialect = completionDialect(providerId);
  const scan = scanSqlStructure(sql, dialect);
  const diagnostics: SqlDiagnostic[] = [];
  const quickFixes: SqlQuickFix[] = [];

  for (const issue of scan.issues) {
    const code = issue.kind === "unclosed-comment" ? "SQL_UNCLOSED_COMMENT"
      : issue.kind === "unclosed-string" ? "SQL_UNCLOSED_STRING" : "SQL_UNMATCHED_PARENTHESIS";
    const message = issue.kind === "unclosed-comment" ? "块注释缺少结束符 */"
      : issue.kind === "unclosed-string" ? "字符串或引用标识符未闭合" : "括号不匹配";
    diagnostics.push(diagnostic(code, "syntax", "error", message, issue.start, issue.end));
    if (issue.closer) quickFixes.push({
      diagnosticCode: code,
      title: `补齐 ${issue.closer}`,
      isPreferred: true,
      edits: [{ startOffset: issue.insertOffset, endOffset: issue.insertOffset, text: issue.closer }],
    });
  }

  const completionIndex = index ?? createCompletionIndex("", []);
  for (const range of scan.statements) {
    diagnoseStatement(completionIndex, Boolean(index), providerId, sql, range, diagnostics, quickFixes);
  }
  return { diagnostics: deduplicateDiagnostics(diagnostics), quickFixes: deduplicateFixes(quickFixes) };
}

function diagnoseStatement(index: CompletionIndex, metadataAvailable: boolean, providerId: string, sql: string,
                           range: SqlStatementRange, diagnostics: SqlDiagnostic[], quickFixes: SqlQuickFix[]): void {
  const dialect = completionDialect(providerId);
  const cursor = Math.max(range.start, range.end - 1);
  const statement = lexStatementAt(sql, cursor, dialect);
  const tokens = statement.tokens.filter((token) => token.start >= range.start && token.end <= range.end);
  if (!tokens.length) return;
  const rootKind = statementKind(tokens, 0, 0);
  if (!rootKind) return;

  const rootCtes = parseCtes(index, tokens, 0, 0, new Map());
  const scopes = statementScopes(index, tokens, rootCtes);
  diagnoseJoins(tokens, rootKind, rootCtes, diagnostics);
  if (!metadataAvailable) return;
  diagnoseObjects(sql, scopes, diagnostics, quickFixes);
  diagnoseQualifiedColumns(sql, tokens, scopes, diagnostics, quickFixes);
  diagnoseAmbiguousColumns(sql, providerId, tokens, scopes, diagnostics, quickFixes);
}

function statementScopes(index: CompletionIndex, tokens: SqlToken[], rootCtes: Map<string, CompletionSource>): DiagnosticScope[] {
  const depths = new Set(tokens.filter((token) => token.kind === "word" && !token.quoted
    && ["select", "insert", "update", "delete", "merge"].includes(token.lower)).map((token) => token.depth));
  if (!depths.size) depths.add(0);
  return [...depths].sort((left, right) => left - right).map((depth) => {
    const local = depth === 0 ? rootCtes : parseCtes(index, tokens, 0, depth, rootCtes);
    return { depth, sources: parseSources(index, tokens, 0, tokens.length, depth,
      new Map([...rootCtes, ...local])) };
  });
}

function diagnoseObjects(sql: string, scopes: DiagnosticScope[], diagnostics: SqlDiagnostic[],
                         quickFixes: SqlQuickFix[]): void {
  for (const scope of scopes) {
    for (const source of scope.sources) {
      if (source.kind !== "physical" || !source.namespace || source.object
          || source.objectStart === undefined || source.objectEnd === undefined) continue;
      diagnostics.push(diagnostic("SQL_UNKNOWN_OBJECT", "semantic", "warning",
        `未在 ${source.namespace.snapshot.label} 中找到对象 ${source.name}`,
        source.objectStart, source.objectEnd));
      const candidates = diagnosticNameCandidates(
        source.namespace.sortedObjects.map((value) => value.snapshot.name), source.name, 3);
      candidates.forEach((candidate) => quickFixes.push({
        diagnosticCode: "SQL_UNKNOWN_OBJECT",
        title: `替换为 ${candidate}`,
        isPreferred: candidates.length === 1,
        edits: [{ startOffset: source.objectStart!, endOffset: source.objectEnd!,
          text: replacementLike(sql, source.objectStart!, source.objectEnd!, candidate) }],
      }));
    }
  }
}

function diagnoseQualifiedColumns(sql: string, tokens: SqlToken[], scopes: DiagnosticScope[],
                                  diagnostics: SqlDiagnostic[], quickFixes: SqlQuickFix[]): void {
  for (let cursor = 0; cursor + 2 < tokens.length; cursor += 1) {
    const qualifier = tokens[cursor];
    const dot = tokens[cursor + 1];
    const column = tokens[cursor + 2];
    if (qualifier.kind !== "word" || dot.value !== "." || column.kind !== "word"
        || qualifier.depth !== column.depth) continue;
    if (insideSourceName(qualifier, scopes)) continue;
    const sources = sourcesAt(scopes, qualifier.depth)
      .filter((source) => normalize(source.alias) === normalize(qualifier.value)
        || normalize(source.name) === normalize(qualifier.value));
    if (sources.length !== 1) continue;
    const names = sourceColumnNames(sources[0]);
    if (!names.length || names.some((name) => normalize(name) === normalize(column.value))) continue;
    diagnostics.push(diagnostic("SQL_UNKNOWN_QUALIFIED_COLUMN", "semantic", "warning",
      `数据源 ${qualifier.value} 中不存在字段 ${column.value}`, column.start, column.end));
    const candidates = diagnosticNameCandidates(names, column.value, 3);
    candidates.forEach((candidate) => quickFixes.push({
      diagnosticCode: "SQL_UNKNOWN_QUALIFIED_COLUMN",
      title: `替换为 ${qualifier.value}.${candidate}`,
      isPreferred: candidates.length === 1,
      edits: [{ startOffset: column.start, endOffset: column.end,
        text: replacementLike(sql, column.start, column.end, candidate) }],
    }));
  }
}

function diagnoseAmbiguousColumns(sql: string, providerId: string, tokens: SqlToken[], scopes: DiagnosticScope[],
                                  diagnostics: SqlDiagnostic[], quickFixes: SqlQuickFix[]): void {
  const keywords = new Set(completionDialect(providerId).keywords.map((value) => value.toLocaleLowerCase()));
  for (let cursor = 0; cursor < tokens.length; cursor += 1) {
    const token = tokens[cursor];
    if (token.kind !== "word" || !COLUMN_CLAUSES.has(clauseAt(tokens, cursor, token.depth))
        || (!token.quoted && keywords.has(token.lower)) || tokens[cursor - 1]?.value === "."
        || tokens[cursor + 1]?.value === "." || tokens[cursor + 1]?.value === "("
        || tokens[cursor - 1]?.lower === "as" || insideSource(token, scopes)
        || looksLikeProjectionAlias(tokens, cursor)) continue;
    const matches = sourcesAt(scopes, token.depth).filter((source) => sourceColumnNames(source)
      .some((name) => normalize(name) === normalize(token.value)));
    const unique = uniqueSources(matches);
    if (unique.length < 2) continue;
    diagnostics.push(diagnostic("SQL_AMBIGUOUS_COLUMN", "semantic", "warning",
      `字段 ${token.value} 可来自多个数据源，请明确限定`, token.start, token.end));
    for (const source of unique) {
      const qualifier = quoteIdentifierIfNeeded(source.alias, providerId);
      quickFixes.push({
        diagnosticCode: "SQL_AMBIGUOUS_COLUMN",
        title: `限定为 ${qualifier}.${token.value}`,
        isPreferred: false,
        edits: [{ startOffset: token.start, endOffset: token.end,
          text: `${qualifier}.${sql.slice(token.start, token.end)}` }],
      });
    }
  }
}

function diagnoseJoins(tokens: SqlToken[], rootKind: string, ctes: Map<string, CompletionSource>,
                       diagnostics: SqlDiagnostic[]): void {
  if (!["select", "update", "delete"].includes(rootKind)) return;
  for (let cursor = 0; cursor < tokens.length; cursor += 1) {
    const join = tokens[cursor];
    if (join.kind !== "word" || join.quoted || join.lower !== "join") continue;
    const preceding = previousWordsAtDepth(tokens, cursor, join.depth, 4);
    if (preceding.includes("cross") || preceding.includes("natural")) continue;
    const sourceIndex = nextAtDepth(tokens, cursor + 1, join.depth);
    const source = tokens[sourceIndex];
    if (!source || source.value === "(" || source.kind !== "word"
        || ctes.has(normalize(source.value))) continue;
    let condition = false;
    for (let look = sourceIndex + 1; look < tokens.length; look += 1) {
      const token = tokens[look];
      if (token.depth !== join.depth || token.kind !== "word" || token.quoted) continue;
      if (token.lower === "on" || token.lower === "using") { condition = true; break; }
      if (JOIN_END_WORDS.has(token.lower)) break;
    }
    if (!condition) diagnostics.push(diagnostic("SQL_JOIN_CONDITION_MISSING", "risk", "warning",
      "JOIN 缺少 ON 或 USING 条件，可能产生笛卡尔积", join.start, join.end));
  }
}

function clauseAt(tokens: SqlToken[], end: number, depth: number): string {
  let clause = "";
  for (let cursor = 0; cursor <= end; cursor += 1) {
    const token = tokens[cursor];
    if (token.depth !== depth || token.kind !== "word" || token.quoted) continue;
    if (CLAUSE_WORDS.has(token.lower)) clause = token.lower;
    else if (token.lower === "join") clause = "from";
  }
  return clause;
}

function looksLikeProjectionAlias(tokens: SqlToken[], cursor: number): boolean {
  const token = tokens[cursor];
  if (clauseAt(tokens, cursor, token.depth) !== "select") return false;
  const next = nextAtDepth(tokens, cursor + 1, token.depth);
  if (next >= 0 && tokens[next].value !== "," && tokens[next].lower !== "from") return false;
  const previous = previousAtDepth(tokens, cursor - 1, token.depth);
  return previous >= 0 && tokens[previous].kind === "word" && tokens[previous].value !== ".";
}

function insideSource(token: SqlToken, scopes: DiagnosticScope[]): boolean {
  return scopes.some((scope) => scope.depth === token.depth && scope.sources.some((source) =>
    source.start !== undefined && source.end !== undefined && token.start >= source.start && token.end <= source.end));
}

function insideSourceName(token: SqlToken, scopes: DiagnosticScope[]): boolean {
  return scopes.some((scope) => scope.depth === token.depth && scope.sources.some((source) =>
    source.nameStart !== undefined && source.nameEnd !== undefined
    && token.start >= source.nameStart && token.end <= source.nameEnd));
}

function sourcesAt(scopes: DiagnosticScope[], depth: number): CompletionSource[] {
  return scopes.find((scope) => scope.depth === depth)?.sources ?? [];
}

function uniqueSources(values: CompletionSource[]): CompletionSource[] {
  const seen = new Set<string>();
  return values.filter((source) => {
    const key = `${normalize(source.alias)}\u0000${normalize(source.name)}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function previousWordsAtDepth(tokens: SqlToken[], start: number, depth: number, maximum: number): string[] {
  const result: string[] = [];
  for (let cursor = start - 1; cursor >= 0 && result.length < maximum; cursor -= 1) {
    const token = tokens[cursor];
    if (token.depth !== depth) continue;
    if (token.value === "," || token.lower === "from" || token.lower === "join") break;
    if (token.kind === "word" && !token.quoted) result.push(token.lower);
  }
  return result;
}

function nextAtDepth(tokens: SqlToken[], start: number, depth: number): number {
  for (let cursor = start; cursor < tokens.length; cursor += 1) if (tokens[cursor].depth === depth) return cursor;
  return -1;
}

function previousAtDepth(tokens: SqlToken[], start: number, depth: number): number {
  for (let cursor = start; cursor >= 0; cursor -= 1) if (tokens[cursor].depth === depth) return cursor;
  return -1;
}

function replacementLike(sql: string, start: number, end: number, value: string): string {
  const existing = sql.slice(start, end);
  if (existing.startsWith("`") && existing.endsWith("`")) return `\`${value.replaceAll("`", "``")}\``;
  if (existing.startsWith("\"") && existing.endsWith("\"")) return `\"${value.replaceAll("\"", "\"\"")}\"`;
  return value;
}

function quoteIdentifierIfNeeded(value: string, providerId: string): string {
  if (/^[A-Za-z_$#][\w$#]*$/.test(value)) return value;
  return completionDialect(providerId).backtickIdentifiers
    ? `\`${value.replaceAll("`", "``")}\`` : `\"${value.replaceAll("\"", "\"\"")}\"`;
}

function diagnostic(code: string, category: SqlDiagnostic["category"], severity: SqlDiagnostic["severity"],
                    message: string, startOffset: number, endOffset: number): SqlDiagnostic {
  return { code, category, severity, message, startOffset, endOffset: Math.max(startOffset + 1, endOffset) };
}

function deduplicateDiagnostics(values: SqlDiagnostic[]): SqlDiagnostic[] {
  const seen = new Set<string>();
  return values.filter((value) => {
    const key = `${value.code}\u0000${value.startOffset}\u0000${value.endOffset}\u0000${value.message}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function deduplicateFixes(values: SqlQuickFix[]): SqlQuickFix[] {
  const seen = new Set<string>();
  return values.filter((value) => {
    const key = `${value.diagnosticCode}\u0000${value.title}\u0000${JSON.stringify(value.edits)}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}
