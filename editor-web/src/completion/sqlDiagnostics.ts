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

// These are fixed, documented relations supplied by the SQL engine. They are
// deliberately listed by exact name: a misspelled ALL_* or V$* object must
// still be diagnosed when the loaded dictionary is complete.
const ORACLE_NATIVE_RELATIONS = new Set([
  "dual", "all_tables", "all_tab_columns", "all_objects", "all_views", "all_users", "all_synonyms",
  "all_constraints", "all_cons_columns", "all_sequences", "all_indexes", "all_procedures",
  "all_source", "all_arguments", "all_dependencies", "all_tab_comments", "all_col_comments",
  "all_tab_privs", "all_tab_privs_made", "all_tab_privs_recd", "all_col_privs",
  "all_col_privs_made", "all_col_privs_recd", "all_role_privs", "all_sys_privs", "all_db_links",
  "all_errors", "all_libraries", "all_java_classes", "all_java_methods",
  "user_tables", "user_tab_columns", "user_objects", "user_views", "user_synonyms", "user_sequences",
  "user_indexes", "user_constraints", "user_cons_columns", "user_tab_comments", "user_col_comments",
  "user_tab_privs", "user_tab_privs_made", "user_tab_privs_recd", "user_col_privs",
  "user_col_privs_made", "user_col_privs_recd", "user_role_privs", "user_sys_privs", "user_db_links",
  "user_errors", "user_source",
  "dba_tables", "dba_tab_columns", "dba_objects", "dba_views", "dba_users", "dba_synonyms",
  "dba_sequences", "dba_indexes", "dba_constraints", "dba_cons_columns", "dba_tab_comments",
  "dba_col_comments", "dba_tab_privs", "dba_tab_privs_made", "dba_tab_privs_recd", "dba_col_privs",
  "dba_col_privs_made", "dba_col_privs_recd", "dba_role_privs", "dba_sys_privs", "dba_db_links",
  "dba_errors", "dba_source", "v$session", "v$instance", "v$database", "v$parameter", "v$sql", "v$process",
  "v$log", "v$version", "v$nls_parameters", "gv$session", "gv$instance", "gv$database", "gv$parameter",
  "gv$sql", "gv$process", "gv$log", "gv$version", "gv$nls_parameters"
]);
const MYSQL_NATIVE_RELATIONS = new Set(["dual"]);
const ORACLE_PSEUDO_COLUMNS = new Set([
  "rownum", "rowid", "urowid", "ora_rowscn", "versions_startscn", "versions_endscn",
  "versions_xid", "versions_operation", "level", "connect_by_isleaf", "connect_by_iscycle",
  "connect_by_root", "scn_to_timestamp", "timestamp_to_scn"
]);
const ORACLE_SEQUENCE_ATTRIBUTES = new Set(["nextval", "currval"]);

interface DiagnosticScope {
  depth: number;
  start: number;
  end: number;
  startOffset: number;
  endOffset: number;
  blockStartOffset: number;
  blockEndOffset: number;
  sources: CompletionSource[];
  ctes: Map<string, CompletionSource>;
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
  diagnoseObjects(sql, providerId, index, scopes, diagnostics, quickFixes);
  if (index.coverage.columns !== "complete") return;
  diagnoseQualifiedColumns(sql, providerId, tokens, scopes, diagnostics, quickFixes);
  diagnoseAmbiguousColumns(sql, providerId, tokens, scopes, diagnostics, quickFixes);
}

function statementScopes(index: CompletionIndex, tokens: SqlToken[], rootCtes: Map<string, CompletionSource>): DiagnosticScope[] {
  const statementKinds = new Set(["select", "insert", "update", "delete", "merge"]);
  const depths = new Set(tokens.filter((token) => token.kind === "word" && !token.quoted
    && statementKinds.has(token.lower)).map((token) => token.depth));
  if (!depths.size) depths.add(0);
  const result: DiagnosticScope[] = [];
  for (const depth of [...depths].sort((left, right) => left - right)) {
    const starts = tokens.map((token, cursor) => ({ token, cursor }))
      .filter(({ token }) => token.depth === depth && token.kind === "word" && !token.quoted
        && statementKinds.has(token.lower)).map(({ cursor }) => cursor);
    if (!starts.length) starts.push(0);
    for (let cursor = 0; cursor < starts.length; cursor += 1) {
      const start = starts[cursor];
      const end = starts[cursor + 1] ?? tokens.length;
      const parent = [...result].reverse().find((scope) => scope.depth < depth
        && tokenInBlock(scope, tokens[start]?.start ?? 0));
      const inherited = parent?.ctes ?? rootCtes;
      const local = depth === 0 ? rootCtes
        : parseCtes(index, tokens, cteStartForScope(tokens, start, depth), depth, inherited);
      const availableCtes = depth === 0 ? rootCtes : new Map([...inherited, ...local]);
      const sources = parseSources(index, tokens, start, end, depth,
        availableCtes);
      // Keep empty query blocks in the scope map as well. They prevent a
      // sibling subquery with no FROM clause from inheriting its neighbour's
      // aliases through the fallback path in sourcesAt().
      const block = queryBlockBounds(tokens, start, depth, end);
      result.push({ depth, start, end,
        startOffset: tokens[start]?.start ?? 0,
        endOffset: tokens[Math.max(start, end - 1)]?.end ?? tokens[start]?.end ?? 0,
        blockStartOffset: block.start, blockEndOffset: block.end,
        sources, ctes: availableCtes });
    }
  }
  return result;
}

function queryBlockBounds(tokens: SqlToken[], start: number, depth: number, end: number): { start: number; end: number } {
  const stack: number[] = [];
  for (let cursor = 0; cursor < start; cursor += 1) {
    if (tokens[cursor].value === "(") stack.push(cursor);
    else if (tokens[cursor].value === ")") stack.pop();
  }
  const open = depth > 0 ? stack[depth - 1] : -1;
  const blockStart = open >= 0 ? tokens[open].end : tokens[start]?.start ?? 0;
  let blockEnd = tokens[Math.max(start, end - 1)]?.end ?? blockStart;
  if (open >= 0) {
    let nesting = 0;
    for (let cursor = open; cursor < tokens.length; cursor += 1) {
      if (tokens[cursor].value === "(") nesting += 1;
      else if (tokens[cursor].value === ")" && --nesting === 0) {
        blockEnd = tokens[cursor].end;
        break;
      }
    }
  }
  return { start: blockStart, end: blockEnd };
}

function cteStartForScope(tokens: SqlToken[], start: number, depth: number): number {
  const stack: number[] = [];
  for (let cursor = 0; cursor < start; cursor += 1) {
    if (tokens[cursor].value === "(") stack.push(cursor);
    else if (tokens[cursor].value === ")") stack.pop();
  }
  const blockStart = depth > 0 ? (stack[depth - 1] ?? -1) + 1 : 0;
  for (let cursor = blockStart; cursor < start; cursor += 1) {
    const token = tokens[cursor];
    if (token.depth === depth && token.kind === "word" && !token.quoted && token.lower === "with") return cursor;
  }
  return start;
}

function diagnoseObjects(sql: string, providerId: string, index: CompletionIndex, scopes: DiagnosticScope[],
                         diagnostics: SqlDiagnostic[], quickFixes: SqlQuickFix[]): void {
  for (const scope of scopes) {
    for (const source of scope.sources) {
      if (source.kind !== "physical" || !source.namespace || source.object
          || source.objectStart === undefined || source.objectEnd === undefined) continue;
      if (source.resolution !== "unresolved"
          || isNativeRelation(providerId, source.name, source.quoted, source.namespaceName === undefined)) continue;
      if (index.coverage.objects !== "complete") continue;
      diagnostics.push(diagnostic("SQL_UNKNOWN_OBJECT", "semantic", "warning",
        `当前已加载的 ${source.namespace.snapshot.label} 元数据中未找到对象 ${source.name}`,
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

function isNativeRelation(providerId: string, name: string, quoted = false, unqualified = true): boolean {
  // Native dictionary names are reached through public synonyms when they are
  // unqualified. An explicit schema is a request for that schema only and
  // must be checked against the loaded object/synonym directory.
  if (!unqualified) return false;
  const normalizedProvider = providerId.toLocaleLowerCase();
  const mysql = normalizedProvider.includes("mysql") && !normalizedProvider.includes("oracle");
  const oracle = normalizedProvider === "oracle" || normalizedProvider === "oceanbase-oracle";
  if (!mysql && !oracle) return false;
  // Oracle folds unquoted identifiers to upper case. A quoted uppercase
  // dictionary name therefore still denotes the native relation, while
  // quoted lowercase/mixed-case names are distinct user identifiers. MySQL
  // backtick identifiers remain case-insensitive for this compatibility
  // check.
  if (quoted && oracle && name !== name.toLocaleUpperCase()) return false;
  const values = mysql ? MYSQL_NATIVE_RELATIONS : ORACLE_NATIVE_RELATIONS;
  return values.has(normalize(name));
}

function diagnoseQualifiedColumns(sql: string, providerId: string, tokens: SqlToken[], scopes: DiagnosticScope[],
                                  diagnostics: SqlDiagnostic[], quickFixes: SqlQuickFix[]): void {
  for (let cursor = 0; cursor + 2 < tokens.length; cursor += 1) {
    const qualifier = tokens[cursor];
    const dot = tokens[cursor + 1];
    const column = tokens[cursor + 2];
    if (qualifier.kind !== "word" || dot.value !== "." || column.kind !== "word"
        || qualifier.depth !== column.depth) continue;
    if (insideSourceName(qualifier, scopes)) continue;
    if (isDialectSpecialColumn(providerId, column)) continue;
    const sources = qualifiedSourcesAt(scopes, qualifier.depth, qualifier.start, qualifier.value);
    if (sources.length !== 1) continue;
    const names = sourceColumnNames(sources[0]);
    if (!names.length || names.some((name) => columnNameMatches(name, column))) continue;
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
        || looksLikeProjectionAlias(tokens, cursor) || isDialectSpecialColumn(providerId, token)) continue;
    const matches = sourcesAt(scopes, token.depth, token.start).filter((source) => sourceColumnNames(source)
      .some((name) => columnNameMatches(name, token)));
    const unique = uniqueSources(matches);
    if (unique.length < 2) continue;
    const scope = scopes.find((value) => value.depth === token.depth && tokenInScope(value, token.start));
    if (mergedJoinColumn(tokens, token.depth, token.value, unique, scope?.startOffset, scope?.endOffset)) continue;
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

function isDialectSpecialColumn(providerId: string, token: SqlToken): boolean {
  if (token.quoted) return false;
  const normalizedProvider = providerId.toLocaleLowerCase();
  if (normalizedProvider.includes("mysql") && !normalizedProvider.includes("oracle")) {
    return token.lower === "_rowid";
  }
  if (normalizedProvider !== "oracle" && normalizedProvider !== "oceanbase-oracle") return false;
  return ORACLE_PSEUDO_COLUMNS.has(token.lower) || ORACLE_SEQUENCE_ATTRIBUTES.has(token.lower);
}

function columnNameMatches(name: string, token: SqlToken): boolean {
  return token.quoted ? name === token.value : normalize(name) === normalize(token.value);
}

function mergedJoinColumn(tokens: SqlToken[], depth: number, name: string,
                          sources: CompletionSource[], scopeStart?: number, scopeEnd?: number): boolean {
  const normalized = normalize(name);
  for (let cursor = 0; cursor < tokens.length; cursor += 1) {
    const token = tokens[cursor];
    if (token.depth !== depth || token.kind !== "word" || token.quoted) continue;
    if ((scopeStart !== undefined && token.start < scopeStart)
        || (scopeEnd !== undefined && token.end > scopeEnd)) continue;
    if (token.lower === "using" && tokens[cursor + 1]?.value === "(") {
      let nesting = 0;
      let close = -1;
      for (let index = cursor + 1; index < tokens.length; index += 1) {
        if (tokens[index].value === "(") nesting += 1;
        else if (tokens[index].value === ")") {
          nesting -= 1;
          if (nesting === 0) { close = index; break; }
        }
      }
      if (close > cursor + 1
          && tokens.slice(cursor + 2, close).some((value) => normalize(value.value) === normalized)
          && mergedPairOnly(joinPair(tokens[cursor], sources, "using"), sources)) return true;
    }
    if (token.lower === "natural" && tokens[cursor + 1]?.lower === "join") {
      const pair = joinPair(token, sources, "natural");
      if (!mergedPairOnly(pair, sources)) continue;
      const common = sourceColumnNames(pair[0]).filter((column) => sourceColumnNames(pair[1])
        .some((value) => normalize(value) === normalize(column)));
      if (common.some((value) => normalize(value) === normalized)) return true;
    }
  }
  return false;
}

function mergedPairOnly(pair: CompletionSource[], sources: CompletionSource[]): boolean {
  return pair.length === 2 && sources.length === 2 && pair.every((value) => sources.includes(value));
}

function joinPair(token: SqlToken, sources: CompletionSource[], kind: "using" | "natural"): CompletionSource[] {
  const ordered = sources.filter((source) => source.start !== undefined)
    .sort((left, right) => left.start! - right.start!);
  if (kind === "natural") {
    const left = ordered.filter((source) => source.start! < token.start).at(-1);
    const right = ordered.find((source) => source.start! > token.end);
    return left && right ? [left, right] : [];
  }
  let rightIndex = -1;
  for (let index = ordered.length - 1; index >= 0; index -= 1) {
    if (ordered[index].start! < token.start) {
      rightIndex = index;
      break;
    }
  }
  return rightIndex > 0 ? [ordered[rightIndex - 1], ordered[rightIndex]] : [];
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
  if (previous < 0 || tokens[previous].value === "." || tokens[previous].value === ",") return false;
  if (tokens[previous].kind === "word"
      && (tokens[previous].lower === "select" || ["distinct", "all", "unique"].includes(tokens[previous].lower))) {
    return false;
  }
  return true;
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

function sourcesAt(scopes: DiagnosticScope[], depth: number, offset?: number): CompletionSource[] {
  const matches = scopes.filter((scope) => scope.depth === depth
    && (offset === undefined || tokenInScope(scope, offset)));
  if (matches.length) return matches[0].sources;
  return [];
}

/** Resolves a qualifier in the nearest query block, then permits correlation
 * to an outer block only when that block has no shadowing alias. */
function qualifiedSourcesAt(scopes: DiagnosticScope[], depth: number, offset: number,
                            qualifier: string): CompletionSource[] {
  const normalized = normalize(qualifier);
  for (let currentDepth = depth; currentDepth >= 0; currentDepth -= 1) {
    const blockSources = scopes.filter((scope) => scope.depth === currentDepth
      && tokenInScope(scope, offset)).flatMap((scope) => scope.sources);
    const matches = blockSources.filter((source) => normalize(source.alias) === normalized
      || normalize(source.name) === normalized);
    if (matches.length) return uniqueSources(matches);
  }
  return [];
}

function tokenInScope(scope: DiagnosticScope, offset: number): boolean {
  return offset >= scope.startOffset && offset <= scope.endOffset;
}

function tokenInBlock(scope: DiagnosticScope, offset: number): boolean {
  return offset >= scope.blockStartOffset && offset <= scope.blockEndOffset;
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
