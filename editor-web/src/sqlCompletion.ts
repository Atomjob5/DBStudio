import { completionDialect } from "./completion/sqlDialect";
import type { SqlCompletionDialect } from "./completion/sqlDialect";
import { activeParenthesisDepth, lexStatementAt } from "./completion/sqlLexer";
import type { SqlToken } from "./completion/sqlLexer";
import type {
  CompletionCandidate,
  CompletionNamespaceSnapshot,
  CompletionObjectSnapshot,
  CompletionResult,
  CompletionSnapshot,
  QueryColumn,
  ResolvedResultColumnRemark,
  ResultColumnRemarkLookup
} from "./types";

interface IndexedObject {
  snapshot: CompletionObjectSnapshot;
  columns: CompletionObjectSnapshot["columns"];
}

interface IndexedNamespace {
  snapshot: CompletionNamespaceSnapshot;
  objects: Map<string, IndexedObject>;
  sortedObjects: IndexedObject[];
}

interface CompletionSource {
  kind: "physical" | "cte" | "derived";
  name: string;
  alias: string;
  namespaceName?: string;
  namespace?: IndexedNamespace;
  object?: IndexedObject;
  columns?: string[];
}

interface CompletionScope {
  depth: number;
  start: number;
  sources: CompletionSource[];
  parent?: CompletionScope;
}

interface Qualifier {
  parts: string[];
  start: number;
}

interface CandidateRank extends CompletionCandidate {
  namespacePriority?: number;
}

export interface CompletionIndex {
  namespaces: Map<string, IndexedNamespace>;
  namespaceValues: IndexedNamespace[];
  defaultNamespace?: IndexedNamespace;
}

export interface CompletionObjectDelta {
  namespaceKey: string;
  catalog: string;
  schema: string;
  name: string;
  kind: "table" | "view";
  remarks: string;
}

export interface CompletionPhysicalTable {
  schema: string;
  table: string;
}

export interface CompletionRequest {
  providerId: string;
  sql: string;
  cursorOffset?: number;
  prefix: string;
  limit: number;
}

const SOURCE_TERMINATORS = new Set([
  "where", "on", "group", "order", "having", "limit", "offset", "fetch", "union", "minus",
  "except", "intersect", "window", "qualify", "set", "values", "returning", "connect", "start",
  "for", "when", "matched"
]);

const ALIAS_TERMINATORS = new Set([
  ...SOURCE_TERMINATORS, "from", "join", "left", "right", "full", "inner", "outer", "cross",
  "straight_join", "as", "using", "and", "or", "then", "else", "end", "into", "update", "select"
]);

const COLUMN_CLAUSES = new Set(["select", "where", "on", "group", "order", "having", "set", "returning"]);
const SET_OPERATORS = new Set(["union", "minus", "except", "intersect"]);

export function buildCompletionIndex(snapshot: CompletionSnapshot): CompletionIndex {
  const index = createCompletionIndex(snapshot.defaultNamespaceKey, snapshot.namespaces.map((value) => ({
    key: value.key, catalog: value.catalog, schema: value.schema, label: value.label
  })));
  const values: CompletionObjectDelta[] = [];
  for (const namespace of snapshot.namespaces) {
    for (const object of namespace.objects) {
      values.push({ namespaceKey: namespace.key, catalog: namespace.catalog, schema: namespace.schema,
        name: object.name, kind: object.kind, remarks: object.remarks });
    }
  }
  upsertCompletionObjects(index, values);
  for (const namespace of snapshot.namespaces) {
    for (const object of namespace.objects) {
      mergeCompletionColumns(index, namespace.key, object.name, object.columns);
    }
  }
  return index;
}

export function createCompletionIndex(defaultNamespaceKey: string,
                                      namespaceDescriptors: Array<{
                                        key: string;
                                        catalog: string;
                                        schema: string;
                                        label: string;
                                      }>): CompletionIndex {
  const namespaces = new Map<string, IndexedNamespace>();
  const namespaceValues: IndexedNamespace[] = [];
  let defaultNamespace: IndexedNamespace | undefined;
  for (const value of namespaceDescriptors) {
    const snapshot: CompletionNamespaceSnapshot = { ...value, objects: [] };
    const indexed: IndexedNamespace = {
      snapshot,
      objects: new Map(),
      sortedObjects: []
    };
    namespaceValues.push(indexed);
    for (const name of [value.key, value.label, value.catalog, value.schema]) {
      if (name) namespaces.set(normalize(name), indexed);
    }
    if (value.key === defaultNamespaceKey) defaultNamespace = indexed;
  }
  namespaceValues.sort((left, right) => compareName(left.snapshot.label, right.snapshot.label));
  return { namespaces, namespaceValues, defaultNamespace };
}

export function upsertCompletionObjects(index: CompletionIndex, values: CompletionObjectDelta[]): void {
  for (const value of values) {
    const namespace = index.namespaces.get(normalize(value.namespaceKey));
    if (!namespace) continue;
    const key = normalize(value.name);
    const existing = namespace.objects.get(key);
    if (existing) {
      existing.snapshot.kind = value.kind;
      existing.snapshot.remarks = value.remarks;
      continue;
    }
    const snapshot: CompletionObjectSnapshot = {
      name: value.name, kind: value.kind, remarks: value.remarks, columns: []
    };
    const object: IndexedObject = { snapshot, columns: [] };
    namespace.objects.set(key, object);
    insertSortedObject(namespace.sortedObjects, object);
  }
}

export function mergeCompletionColumns(index: CompletionIndex, namespaceKey: string, objectName: string,
                                       columns: CompletionObjectSnapshot["columns"]): void {
  const object = index.namespaces.get(normalize(namespaceKey))?.objects.get(normalize(objectName));
  if (!object) return;
  const byName = new Map(object.columns.map((column) => [normalize(column.name), column]));
  for (const column of columns) {
    const existing = byName.get(normalize(column.name));
    if (existing) {
      existing.remarks = column.remarks;
      if (column.typeName) existing.typeName = column.typeName;
    } else {
      byName.set(normalize(column.name), { ...column });
    }
  }
  object.columns = [...byName.values()].sort((left, right) => compareName(left.name, right.name));
  object.snapshot.columns = object.columns;
}

export function applyCompletionStructure(index: CompletionIndex, schema: string, table: string,
                                         columns: Array<{ name: string; typeName: string; ordinal: number }>): boolean {
  const namespace = resultNamespace(index, "", schema);
  const object = namespace?.objects.get(normalize(table));
  if (!namespace || !object) return false;
  const remarks = new Map(object.columns.map((column) => [normalize(column.name), column.remarks]));
  object.columns = [...columns].sort((left, right) => left.ordinal - right.ordinal)
    .map((column) => ({ name: column.name, typeName: column.typeName,
      remarks: remarks.get(normalize(column.name)) ?? "" }))
    .sort((left, right) => compareName(left.name, right.name));
  object.snapshot.columns = object.columns;
  return true;
}

export function resetCompletionStructure(index: CompletionIndex, schema: string, table: string,
                                         columns: Array<{ name: string; remarks: string }>): boolean {
  const namespace = resultNamespace(index, "", schema);
  const object = namespace?.objects.get(normalize(table));
  if (!namespace || !object) return false;
  object.columns = columns.map((column) => ({
    name: column.name,
    typeName: "",
    remarks: column.remarks
  })).sort((left, right) => compareName(left.name, right.name));
  object.snapshot.columns = object.columns;
  return true;
}

export function resolveSinglePhysicalTable(index: CompletionIndex, providerId: string, sql: string,
                                           resultColumns: QueryColumn[]): CompletionPhysicalTable | undefined {
  const dialect = completionDialect(providerId);
  const statement = lexStatementAt(sql, sql.length, dialect);
  const ctes = collectCteNames(statement.tokens);
  const resolved = new Map<string, CompletionPhysicalTable>();
  let unresolved = false;

  for (const column of resultColumns) {
    if (!column.table) continue;
    const namespace = resultNamespace(index, column.catalog, column.schema);
    const object = namespace?.objects.get(normalize(column.table));
    if (!namespace || !object) continue;
    rememberPhysicalTable(resolved, namespace, object);
  }

  const cteSources = new Map<string, CompletionSource>();
  for (const name of ctes) cteSources.set(name, { kind: "cte", name, alias: name, columns: [] });
  const queryDepths = new Set(statement.tokens
    .filter((token) => token.kind === "word" && !token.quoted && token.lower === "select")
    .map((token) => token.depth));
  for (const depth of queryDepths) {
    for (const source of parseSources(index, statement.tokens, 0, statement.tokens.length, depth, cteSources)) {
      if (source.kind !== "physical") continue;
      if (!source.namespace || !source.object) {
        unresolved = true;
        continue;
      }
      rememberPhysicalTable(resolved, source.namespace, source.object);
    }
  }
  if (unresolved || resolved.size !== 1) return undefined;
  return resolved.values().next().value;
}

export function resolveChangedPhysicalTable(index: CompletionIndex, providerId: string,
                                            sql: string): CompletionPhysicalTable | undefined {
  const tokens = lexStatementAt(sql, sql.length, completionDialect(providerId)).tokens;
  const root = tokens.filter((token) => token.depth === 0);
  if (!root.length) return undefined;
  let tableKeyword = -1;
  const operation = root[0].quoted ? "" : root[0].lower;
  if (operation === "alter" || operation === "drop" || operation === "truncate" || operation === "create") {
    tableKeyword = root.findIndex((token, index) => index > 0 && !token.quoted && token.lower === "table");
  }
  if (tableKeyword < 0) return undefined;
  const source = root[tableKeyword + 1];
  if (source?.kind !== "word") return undefined;
  const names = [source.value];
  let cursor = tableKeyword + 2;
  while (root[cursor]?.value === "." && root[cursor + 1]?.kind === "word") {
    names.push(root[cursor + 1].value);
    cursor += 2;
  }
  const name = names.at(-1) ?? "";
  const namespace = names.length > 1
    ? index.namespaces.get(normalize(names.at(-2) ?? ""))
    : index.defaultNamespace;
  const object = namespace?.objects.get(normalize(name));
  if (!namespace || !object) return undefined;
  const resolved = new Map<string, CompletionPhysicalTable>();
  rememberPhysicalTable(resolved, namespace, object);
  return resolved.values().next().value;
}

function insertSortedObject(values: IndexedObject[], object: IndexedObject): void {
  let low = 0;
  let high = values.length;
  while (low < high) {
    const middle = (low + high) >>> 1;
    if (compareName(values[middle].snapshot.name, object.snapshot.name) <= 0) low = middle + 1;
    else high = middle;
  }
  values.splice(low, 0, object);
}

function collectCteNames(tokens: SqlToken[]): Set<string> {
  const names = new Set<string>();
  for (let index = 0; index < tokens.length; index += 1) {
    if (tokens[index].lower !== "with" || tokens[index].quoted) continue;
    const depth = tokens[index].depth;
    let cursor = nextSignificant(tokens, index + 1, depth);
    if (tokens[cursor]?.lower === "recursive") cursor = nextSignificant(tokens, cursor + 1, depth);
    while (cursor >= 0 && tokens[cursor]?.kind === "word" && tokens[cursor]?.depth === depth) {
      names.add(normalize(tokens[cursor].value));
      cursor = nextSignificant(tokens, cursor + 1, depth);
      if (tokens[cursor]?.value === "(") {
        const close = matchingClose(tokens, cursor);
        if (close < 0) break;
        cursor = nextSignificant(tokens, close + 1, depth);
      }
      if (tokens[cursor]?.lower !== "as") break;
      const open = nextSignificant(tokens, cursor + 1, depth);
      if (tokens[open]?.value !== "(") break;
      const close = matchingClose(tokens, open);
      if (close < 0) break;
      cursor = nextSignificant(tokens, close + 1, depth);
      if (tokens[cursor]?.value !== ",") break;
      cursor = nextSignificant(tokens, cursor + 1, depth);
    }
  }
  return names;
}

function rememberPhysicalTable(values: Map<string, CompletionPhysicalTable>, namespace: IndexedNamespace,
                               object: IndexedObject): void {
  const schema = namespace.snapshot.schema || namespace.snapshot.catalog;
  const key = `${normalize(schema)}\u0000${normalize(object.snapshot.name)}`;
  values.set(key, { schema, table: object.snapshot.name });
}

export function resolveResultColumnRemarks(index: CompletionIndex | undefined, providerId: string, sql: string,
                                           columns: ResultColumnRemarkLookup[]): ResolvedResultColumnRemark[] {
  if (!index) return [];
  const resolved: ResolvedResultColumnRemark[] = [];
  const fallback = oracleCompatible(providerId) && safelyProjectsPhysicalColumns(sql, providerId)
    ? resolveSinglePhysicalTable(index, providerId, sql, [])
    : undefined;
  const fallbackNamespace = fallback ? resultNamespace(index, "", fallback.schema) : undefined;
  const fallbackObject = fallbackNamespace?.objects.get(normalize(fallback?.table ?? ""));
  for (const source of columns) {
    if (!source.name) continue;
    const namespace = source.table ? resultNamespace(index, source.catalog, source.schema) : undefined;
    const object = source.table ? namespace?.objects.get(normalize(source.table)) : fallbackObject;
    const column = object?.columns.find((value) => normalize(value.name) === normalize(source.name));
    if (column?.remarks) resolved.push({ index: source.index, remarks: column.remarks });
  }
  return resolved;
}

function safelyProjectsPhysicalColumns(sql: string, providerId: string): boolean {
  const dialect = completionDialect(providerId);
  const tokens = lexStatementAt(sql, sql.length, dialect).tokens;
  if (!tokens.length || tokens.some((token) => token.kind === "word" && !token.quoted
    && SET_OPERATORS.has(token.lower))) return false;
  const selects = tokens.map((token, index) => ({ token, index }))
    .filter(({ token }) => token.kind === "word" && !token.quoted && token.lower === "select");
  if (!selects.length) return false;
  return selects.every(({ token, index }) => directProjectionList(tokens, index, token.depth, dialect));
}

function directProjectionList(tokens: SqlToken[], select: number, depth: number,
                              dialect: SqlCompletionDialect): boolean {
  const from = firstTokenIndex(tokens, select + 1, tokens.length, depth, "from");
  if (from < 0) return false;
  const segments: SqlToken[][] = [];
  let segment: SqlToken[] = [];
  for (let cursor = select + 1; cursor < from; cursor += 1) {
    const token = tokens[cursor];
    if (token.depth !== depth) continue;
    if (token.value === ",") {
      if (!segment.length) return false;
      segments.push(segment);
      segment = [];
    } else {
      segment.push(token);
    }
  }
  if (segment.length) segments.push(segment);
  return segments.length > 0 && segments.every((value) => directProjection(value, dialect));
}

function directProjection(tokens: SqlToken[], dialect: SqlCompletionDialect): boolean {
  let expression = [...tokens];
  while (expression[0]?.kind === "word" && !expression[0].quoted
    && ["distinct", "all", "unique"].includes(expression[0].lower)) expression = expression.slice(1);
  const as = expression.findIndex((token) => token.kind === "word" && !token.quoted && token.lower === "as");
  if (as >= 0) {
    if (as !== expression.length - 2 || expression[as + 1]?.kind !== "word") return false;
    expression = expression.slice(0, as);
  } else if (expression.length > 1 && expression.at(-1)?.kind === "word"
    && directColumnExpression(expression.slice(0, -1), dialect)) {
    expression = expression.slice(0, -1);
  }
  return directColumnExpression(expression, dialect);
}

function directColumnExpression(tokens: SqlToken[], dialect: SqlCompletionDialect): boolean {
  if (tokens.length === 1) return physicalIdentifier(tokens[0], dialect) || tokens[0].value === "*";
  if (tokens.length < 3 || tokens.length % 2 === 0) return false;
  return tokens.every((token, index) => index % 2 === 0
    ? physicalIdentifier(token, dialect) || index === tokens.length - 1 && token.value === "*"
    : token.value === ".");
}

function physicalIdentifier(token: SqlToken, dialect: SqlCompletionDialect): boolean {
  return token.kind === "word" && (token.quoted
    || !dialect.keywords.some((keyword) => keyword.toLocaleLowerCase() === token.lower));
}

function oracleCompatible(providerId: string): boolean {
  return providerId === "oracle" || providerId === "oceanbase-oracle";
}

function resultNamespace(index: CompletionIndex, catalog: string, schema: string): IndexedNamespace | undefined {
  if (!catalog && !schema) return index.defaultNamespace;
  const matches = index.namespaceValues.filter((namespace) => {
    const value = namespace.snapshot;
    return (!catalog || normalize(value.catalog) === normalize(catalog))
      && (!schema || normalize(value.schema) === normalize(schema));
  });
  return matches.length === 1 ? matches[0] : undefined;
}

export function resolveCompletion(index: CompletionIndex | undefined, request: CompletionRequest): CompletionResult {
  const limit = Math.max(10, Math.min(1000, request.limit || 100));
  const dialect = completionDialect(request.providerId);
  const cursorOffset = Math.max(0, Math.min(request.sql.length, request.cursorOffset ?? request.sql.length));
  const statement = lexStatementAt(request.sql, cursorOffset, dialect);
  const tokens = withoutActivePrefix(statement.beforeTokens, request.prefix, cursorOffset);
  const qualifier = readQualifier(tokens);
  const beforeQualifier = qualifier.parts.length ? tokens.slice(0, qualifier.start) : tokens;
  const tableContext = isTablePosition(beforeQualifier);
  const scope = index ? buildActiveScope(index, statement.tokens, statement.beforeTokens, cursorOffset) : undefined;
  const insertColumns = isInsertColumnPosition(tokens);
  const clause = activeClause(tokens, scope?.depth ?? activeParenthesisDepth(tokens));
  const columnContext = !tableContext && (insertColumns || qualifier.parts.length > 0
    || Boolean(scope?.sources.length && COLUMN_CLAUSES.has(clause)));
  const prefix = normalize(request.prefix);
  const candidates: CandidateRank[] = [];

  if (index && tableContext) {
    candidates.push(...tableCandidates(index, qualifier.parts, prefix, limit + 1));
  } else if (index && columnContext) {
    candidates.push(...columnCandidates(index, scope, qualifier.parts, prefix, limit + 1));
  }

  candidates.push(...keywordCandidates(dialect, tableContext ? "table" : columnContext ? "column" : clause, prefix));
  const filtered = deduplicate(candidates)
    .filter((candidate) => matchesPrefix(candidate, prefix))
    .sort((left, right) => compareCandidates(left, right, prefix));
  return { items: filtered.slice(0, limit), incomplete: filtered.length > limit };
}

function withoutActivePrefix(tokens: SqlToken[], prefix: string, cursorOffset: number): SqlToken[] {
  if (!prefix) return tokens;
  const last = tokens.at(-1);
  if (last?.kind === "word" && last.end === cursorOffset && normalize(last.value) === normalize(prefix)) {
    return tokens.slice(0, -1);
  }
  return tokens;
}

function readQualifier(tokens: SqlToken[]): Qualifier {
  if (tokens.at(-1)?.value !== ".") return { parts: [], start: tokens.length };
  const parts: string[] = [];
  let index = tokens.length - 2;
  let start = tokens.length - 1;
  while (index >= 0 && tokens[index].kind === "word") {
    parts.unshift(tokens[index].value);
    start = index;
    if (index < 2 || tokens[index - 1].value !== "." || tokens[index - 2].kind !== "word") break;
    index -= 2;
  }
  return { parts: parts.slice(-2), start: Math.max(start, 0) };
}

function isTablePosition(tokens: SqlToken[]): boolean {
  const last = tokens.at(-1);
  if (!last) return false;
  const depth = activeParenthesisDepth(tokens);
  if (last.value === ",") return activeClause(tokens, depth) === "from";
  if (last.kind !== "word") return false;
  if (last.lower === "from" || last.lower === "join") return true;
  const statement = statementKind(tokens, 0, last.depth);
  if (last.lower === "update") return statement === "update";
  if (last.lower === "into") return statement === "insert" || statement === "merge";
  return last.lower === "using" && statement === "merge";
}

function isInsertColumnPosition(tokens: SqlToken[]): boolean {
  if (statementKind(tokens, 0, 0) !== "insert") return false;
  const stack: number[] = [];
  tokens.forEach((token, index) => {
    if (token.value === "(") stack.push(index);
    else if (token.value === ")") stack.pop();
  });
  const open = stack.at(-1);
  if (open === undefined) return false;
  let cursor = open - 1;
  if (tokens[cursor]?.kind !== "word") return false;
  cursor -= 1;
  while (cursor >= 1 && tokens[cursor]?.value === "." && tokens[cursor - 1]?.kind === "word") cursor -= 2;
  return tokens[cursor]?.lower === "into";
}

function activeClause(tokens: SqlToken[], preferredDepth: number): string {
  let clause = "";
  let depth = preferredDepth;
  if (!tokens.some((token) => token.depth === depth && token.kind === "word")) {
    depth = Math.max(0, depth - 1);
  }
  for (const token of tokens) {
    if (token.depth !== depth || token.kind !== "word" || token.quoted) continue;
    if (token.lower === "select" || token.lower === "from" || token.lower === "where" || token.lower === "on"
      || token.lower === "group" || token.lower === "order" || token.lower === "having" || token.lower === "set"
      || token.lower === "returning" || token.lower === "values") clause = token.lower;
    else if (token.lower === "join") clause = "from";
  }
  return clause;
}

function buildActiveScope(index: CompletionIndex, tokens: SqlToken[], beforeTokens: SqlToken[],
                          cursorOffset: number): CompletionScope | undefined {
  const activeDepth = activeParenthesisDepth(beforeTokens);
  const ranges = activeScopeRanges(tokens, beforeTokens, activeDepth);
  let parent: CompletionScope | undefined;
  let inheritedCtes = new Map<string, CompletionSource>();
  for (let depth = 0; depth <= activeDepth; depth += 1) {
    const range = ranges[depth] ?? { start: 0, end: tokens.length };
    const localCtes = parseCtes(index, tokens, range.start, depth, inheritedCtes);
    inheritedCtes = new Map([...inheritedCtes, ...localCtes]);
    const branchStart = lastSetOperatorBefore(tokens, range.start, range.end, depth, cursorOffset) + 1;
    const branchEnd = firstSetOperatorAfter(tokens, branchStart, range.end, depth, cursorOffset);
    const select = lastTokenIndexBefore(tokens, branchStart, branchEnd, depth, "select", cursorOffset);
    if (depth > 0 && select < 0) continue;
    const scopeStart = select >= 0 ? select : branchStart;
    const sources = parseSources(index, tokens, scopeStart, branchEnd, depth, inheritedCtes);
    if (depth === 0 || select >= 0) parent = { depth, start: scopeStart, sources, parent };
  }
  return parent;
}

function activeScopeRanges(tokens: SqlToken[], beforeTokens: SqlToken[], currentDepth: number): Array<{ start: number; end: number }> {
  const ranges: Array<{ start: number; end: number }> = [{ start: 0, end: tokens.length }];
  const stack: SqlToken[] = [];
  for (const token of beforeTokens) {
    if (token.value === "(") stack.push(token);
    else if (token.value === ")") stack.pop();
  }
  for (let depth = 1; depth <= currentDepth; depth += 1) {
    const openOffset = stack[depth - 1]?.start;
    const open = openOffset === undefined ? -1 : tokens.findIndex((token) => token.start === openOffset && token.value === "(");
    if (open < 0) {
      ranges.push({ start: 0, end: tokens.length });
      continue;
    }
    const close = matchingClose(tokens, open);
    ranges.push({ start: open + 1, end: close < 0 ? tokens.length : close });
  }
  return ranges;
}

function parseCtes(index: CompletionIndex, tokens: SqlToken[], start: number, depth: number,
                   inherited: Map<string, CompletionSource>): Map<string, CompletionSource> {
  const ctes = new Map<string, CompletionSource>();
  let cursor = nextWordIndex(tokens, start, depth);
  if (cursor < 0 || tokens[cursor].lower !== "with") return ctes;
  cursor = nextSignificant(tokens, cursor + 1, depth);
  if (tokens[cursor]?.lower === "recursive") cursor = nextSignificant(tokens, cursor + 1, depth);
  while (cursor >= 0 && cursor < tokens.length) {
    const nameToken = tokens[cursor];
    if (nameToken?.kind !== "word" || nameToken.depth !== depth) break;
    const name = nameToken.value;
    cursor = nextSignificant(tokens, cursor + 1, depth);
    let explicitColumns: string[] = [];
    if (tokens[cursor]?.value === "(") {
      const close = matchingClose(tokens, cursor);
      if (close < 0) break;
      explicitColumns = tokens.slice(cursor + 1, close)
        .filter((token) => token.kind === "word" && token.depth === depth + 1)
        .map((token) => token.value);
      cursor = nextSignificant(tokens, close + 1, depth);
    }
    if (tokens[cursor]?.lower !== "as") break;
    const open = nextSignificant(tokens, cursor + 1, depth);
    if (tokens[open]?.value !== "(") break;
    const close = matchingClose(tokens, open);
    if (close < 0) break;
    const available = new Map([...inherited, ...ctes]);
    const columns = explicitColumns.length ? explicitColumns
      : projectedColumns(index, tokens, open + 1, close, depth + 1, available);
    ctes.set(normalize(name), { kind: "cte", name, alias: name, columns: uniqueNames(columns) });
    cursor = nextSignificant(tokens, close + 1, depth);
    if (tokens[cursor]?.value !== ",") break;
    cursor = nextSignificant(tokens, cursor + 1, depth);
  }
  return ctes;
}

function parseSources(index: CompletionIndex, tokens: SqlToken[], start: number, end: number, depth: number,
                      ctes: Map<string, CompletionSource>): CompletionSource[] {
  const sources: CompletionSource[] = [];
  const kind = statementKind(tokens, start, depth);
  let sourceList = false;
  let expectSource = false;
  for (let cursor = start; cursor < end; cursor += 1) {
    const token = tokens[cursor];
    if (token.depth !== depth) continue;
    if (token.kind === "word" && !token.quoted) {
      const introducer = token.lower === "from" || token.lower === "join"
        || token.lower === "update" && kind === "update"
        || token.lower === "into" && (kind === "insert" || kind === "merge")
        || token.lower === "using" && kind === "merge";
      if (introducer) { sourceList = true; expectSource = true; continue; }
      if (SOURCE_TERMINATORS.has(token.lower)) { sourceList = false; expectSource = false; continue; }
    }
    if (token.value === "," && sourceList) { expectSource = true; continue; }
    if (!expectSource) continue;
    if (token.value === "(") {
      const close = matchingClose(tokens, cursor);
      if (close < 0 || close >= end) break;
      const aliasResult = readAlias(tokens, close + 1, depth);
      const alias = aliasResult.alias || "derived";
      sources.push({ kind: "derived", name: alias, alias,
        columns: projectedColumns(index, tokens, cursor + 1, close, depth + 1, ctes) });
      cursor = aliasResult.nextIndex - 1;
      expectSource = false;
      continue;
    }
    if (token.kind !== "word") continue;
    const names = [token.value];
    let next = cursor + 1;
    while (tokens[next]?.value === "." && tokens[next + 1]?.kind === "word"
      && tokens[next]?.depth === depth && tokens[next + 1]?.depth === depth) {
      names.push(tokens[next + 1].value);
      next += 2;
    }
    const name = names.at(-1) ?? token.value;
    const aliasResult = readAlias(tokens, next, depth);
    const alias = aliasResult.alias || name;
    const cte = names.length === 1 ? ctes.get(normalize(name)) : undefined;
    sources.push(cte ? { ...cte, alias } : physicalSource(index, names, alias));
    cursor = aliasResult.nextIndex - 1;
    expectSource = false;
  }
  return deduplicateSources(sources);
}

function physicalSource(index: CompletionIndex, names: string[], alias: string): CompletionSource {
  const name = names.at(-1) ?? "";
  const namespaceName = names.length > 1 ? names.at(-2) : undefined;
  const namespace = namespaceName ? index.namespaces.get(normalize(namespaceName)) : index.defaultNamespace;
  return { kind: "physical", name, alias, namespaceName, namespace,
    object: namespace?.objects.get(normalize(name)) };
}

function readAlias(tokens: SqlToken[], start: number, depth: number): { alias?: string; nextIndex: number } {
  let cursor = nextSignificant(tokens, start, depth);
  const original = cursor < 0 ? start : cursor;
  if (tokens[cursor]?.lower === "as" && !tokens[cursor]?.quoted) cursor = nextSignificant(tokens, cursor + 1, depth);
  const token = tokens[cursor];
  if (token?.kind === "word" && token.depth === depth
    && (token.quoted || !ALIAS_TERMINATORS.has(token.lower))) {
    return { alias: token.value, nextIndex: cursor + 1 };
  }
  return { nextIndex: original };
}

function projectedColumns(index: CompletionIndex, tokens: SqlToken[], start: number, end: number, depth: number,
                          ctes: Map<string, CompletionSource>): string[] {
  const select = firstTokenIndex(tokens, start, end, depth, "select");
  if (select < 0) return [];
  let from = end;
  for (let cursor = select + 1; cursor < end; cursor += 1) {
    if (tokens[cursor].depth === depth && tokens[cursor].lower === "from") { from = cursor; break; }
  }
  const sources = from < end ? parseSources(index, tokens, select, end, depth, ctes) : [];
  const segments: SqlToken[][] = [];
  let segment: SqlToken[] = [];
  for (let cursor = select + 1; cursor < from; cursor += 1) {
    const token = tokens[cursor];
    if (token.value === "," && token.depth === depth) { if (segment.length) segments.push(segment); segment = []; }
    else segment.push(token);
  }
  if (segment.length) segments.push(segment);
  const result: string[] = [];
  for (const value of segments) {
    const visible = value.filter((token) => token.depth === depth);
    const asIndex = visible.findIndex((token) => token.lower === "as" && !token.quoted);
    if (asIndex >= 0 && visible[asIndex + 1]?.kind === "word") {
      result.push(visible[asIndex + 1].value);
      continue;
    }
    const last = visible.at(-1);
    if (last?.kind === "word" && visible.length > 1 && visible.at(-2)?.value !== "."
      && !isKeyword(last.lower)) {
      result.push(last.value);
      continue;
    }
    if (last?.value === "*") {
      const qualifier = visible.at(-2)?.value === "." && visible.at(-3)?.kind === "word"
        ? visible.at(-3)?.value : "";
      const selected = qualifier ? sources.filter((source) => same(source.alias, qualifier) || same(source.name, qualifier)) : sources;
      for (const source of selected) result.push(...sourceColumnNames(source));
      continue;
    }
    if (last?.kind === "word" && (visible.length === 1 || visible.at(-2)?.value === ".")) result.push(last.value);
  }
  return uniqueNames(result);
}

function sourceColumnNames(source: CompletionSource): string[] {
  if (source.kind === "physical") return source.object?.columns.map((column) => column.name) ?? [];
  return source.columns ?? [];
}

function tableCandidates(index: CompletionIndex, qualifier: string[], prefix: string, maximum: number): CandidateRank[] {
  if (qualifier.length > 1) return [];
  if (qualifier.length === 1) {
    const namespace = index.namespaces.get(normalize(qualifier[0]));
    return namespace ? objectCandidates(index, namespace, true, prefix, maximum) : [];
  }
  const result: CandidateRank[] = [];
  for (const namespace of index.namespaceValues) {
    if (matchesName(namespace.snapshot.label, prefix)) {
      result.push({
        displayLabel: namespace.snapshot.label,
        documentationPath: namespace.snapshot.label,
        insertText: namespace.snapshot.label,
        filterText: namespace.snapshot.label,
        kind: "schema",
        remarks: "",
        typeName: "SCHEMA",
        namespacePriority: namespace === index.defaultNamespace ? 0 : 1
      });
    }
    result.push(...objectCandidates(index, namespace, false, prefix, maximum));
  }
  return result;
}

function objectCandidates(index: CompletionIndex, namespace: IndexedNamespace, qualified: boolean,
                          prefix: string, maximum: number): CandidateRank[] {
  const isDefault = namespace === index.defaultNamespace;
  const result: CandidateRank[] = [];
  for (const indexed of namespace.sortedObjects) {
    const object = indexed.snapshot;
    if (!matchesName(object.name, prefix)) continue;
    const path = `${namespace.snapshot.label}.${object.name}`;
    const contextual = qualified || isDefault ? object.name : path;
    result.push({
      displayLabel: contextual,
      documentationPath: path,
      insertText: contextual,
      filterText: `${object.name} ${path}`,
      kind: object.kind,
      remarks: object.remarks,
      typeName: object.kind.toUpperCase(),
      namespacePriority: isDefault ? 0 : 1
    });
    if (result.length >= maximum) break;
  }
  return result;
}

function columnCandidates(index: CompletionIndex, scope: CompletionScope | undefined, qualifier: string[],
                          prefix: string, maximum: number): CandidateRank[] {
  let sources: CompletionSource[] = [];
  if (qualifier.length >= 2) {
    const namespace = index.namespaces.get(normalize(qualifier.at(-2) ?? ""));
    const object = namespace?.objects.get(normalize(qualifier.at(-1) ?? ""));
    if (namespace && object) sources = [{ kind: "physical", name: object.snapshot.name,
      alias: object.snapshot.name, namespaceName: namespace.snapshot.label, namespace, object }];
  } else if (qualifier.length === 1) {
    sources = findQualifiedSources(scope, qualifier[0]);
  } else if (scope) {
    sources = scope.sources;
  }
  const resolved = sources.filter((source) => source.kind !== "physical" || Boolean(source.object));
  if (!resolved.length) return [];
  const explicitlyQualified = qualifier.length > 0;
  const qualifyWithAlias = !explicitlyQualified && resolved.length > 1;
  const result: CandidateRank[] = [];
  for (const source of resolved) {
    let sourceMatches = 0;
    if (source.kind === "physical" && source.namespace && source.object) {
      for (const column of source.object.columns) {
        if (!matchesName(column.name, prefix)) continue;
        const contextual = qualifyWithAlias ? `${source.alias}.${column.name}` : column.name;
        const path = `${source.namespace.snapshot.label}.${source.object.snapshot.name}.${column.name}`;
        result.push({ displayLabel: contextual, documentationPath: path, insertText: contextual,
          filterText: `${column.name} ${source.alias}.${column.name} ${path}`, kind: "column",
          remarks: column.remarks, typeName: column.typeName,
          namespacePriority: source.namespace === index.defaultNamespace ? 0 : 1 });
        sourceMatches += 1;
        if (sourceMatches >= maximum) break;
      }
    } else {
      for (const column of [...(source.columns ?? [])].sort(compareName)) {
        if (!matchesName(column, prefix)) continue;
        const contextual = qualifyWithAlias ? `${source.alias}.${column}` : column;
        result.push({ displayLabel: contextual, documentationPath: `${source.alias}.${column}`,
          insertText: contextual, filterText: `${column} ${source.alias}.${column}`, kind: "column",
          remarks: "", typeName: source.kind === "cte" ? "CTE" : "DERIVED" });
        sourceMatches += 1;
        if (sourceMatches >= maximum) break;
      }
    }
  }
  return result;
}

function findQualifiedSources(scope: CompletionScope | undefined, qualifier: string): CompletionSource[] {
  let current = scope;
  while (current) {
    const matches = current.sources.filter((source) => same(source.alias, qualifier) || same(source.name, qualifier));
    if (matches.length) return matches;
    current = current.parent;
  }
  return [];
}

function keywordCandidates(dialect: SqlCompletionDialect, context: string, prefix: string): CandidateRank[] {
  const values = context === "table" ? []
    : context === "column" || COLUMN_CLAUSES.has(context) ? dialect.expressionKeywords
      : context === "from" ? dialect.sourceKeywords : dialect.statementKeywords;
  return values.filter((keyword) => matchesName(keyword, prefix)).map((keyword) => ({
    displayLabel: keyword,
    documentationPath: keyword,
    insertText: keyword,
    filterText: keyword,
    kind: "keyword" as const,
    remarks: "",
    typeName: "KEYWORD",
    namespacePriority: 2
  }));
}

function deduplicate(values: CandidateRank[]): CandidateRank[] {
  const seen = new Set<string>();
  return values.filter((value) => {
    const key = `${value.kind}\u0000${normalize(value.documentationPath)}\u0000${normalize(value.insertText)}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function deduplicateSources(values: CompletionSource[]): CompletionSource[] {
  const seen = new Set<string>();
  return values.filter((value) => {
    const key = `${value.kind}\u0000${normalize(value.alias)}\u0000${normalize(value.namespaceName ?? "")}\u0000${normalize(value.name)}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function compareCandidates(left: CandidateRank, right: CandidateRank, prefix: string): number {
  const leftName = normalize(lastIdentifier(left.displayLabel));
  const rightName = normalize(lastIdentifier(right.displayLabel));
  const leftExact = prefix && leftName === prefix ? 0 : 1;
  const rightExact = prefix && rightName === prefix ? 0 : 1;
  if (leftExact !== rightExact) return leftExact - rightExact;
  if ((left.namespacePriority ?? 1) !== (right.namespacePriority ?? 1)) {
    return (left.namespacePriority ?? 1) - (right.namespacePriority ?? 1);
  }
  const priority: Record<CompletionCandidate["kind"], number> = { column: 0, table: 1, view: 2, schema: 3, keyword: 4 };
  return priority[left.kind] - priority[right.kind]
    || compareName(leftName, rightName)
    || compareName(left.documentationPath, right.documentationPath);
}

function matchesPrefix(candidate: CompletionCandidate, prefix: string): boolean {
  if (!prefix) return true;
  return normalize(lastIdentifier(candidate.displayLabel)).startsWith(prefix)
    || normalize(lastIdentifier(candidate.insertText)).startsWith(prefix)
    || candidate.filterText.split(/\s+/).some((value) => normalize(lastIdentifier(value)).startsWith(prefix));
}

function matchesName(value: string, prefix: string): boolean {
  return !prefix || normalize(value).startsWith(prefix);
}

function statementKind(tokens: SqlToken[], start: number, depth: number): string {
  for (let cursor = start; cursor < tokens.length; cursor += 1) {
    const token = tokens[cursor];
    if (token.depth !== depth || token.kind !== "word" || token.quoted) continue;
    if (token.lower === "with" || token.lower === "recursive") continue;
    if (["select", "insert", "update", "delete", "merge"].includes(token.lower)) return token.lower;
  }
  return "";
}

function matchingClose(tokens: SqlToken[], openIndex: number): number {
  if (tokens[openIndex]?.value !== "(") return -1;
  let level = 0;
  for (let cursor = openIndex; cursor < tokens.length; cursor += 1) {
    if (tokens[cursor].value === "(") level += 1;
    else if (tokens[cursor].value === ")" && --level === 0) return cursor;
  }
  return -1;
}

function nextWordIndex(tokens: SqlToken[], start: number, depth: number): number {
  for (let cursor = start; cursor < tokens.length; cursor += 1) {
    if (tokens[cursor].depth === depth && tokens[cursor].kind === "word") return cursor;
  }
  return -1;
}

function nextSignificant(tokens: SqlToken[], start: number, depth: number): number {
  for (let cursor = start; cursor < tokens.length; cursor += 1) {
    if (tokens[cursor].depth === depth) return cursor;
  }
  return -1;
}

function lastSetOperatorBefore(tokens: SqlToken[], start: number, end: number, depth: number,
                               cursorOffset: number): number {
  for (let cursor = Math.min(end, tokens.length) - 1; cursor >= start; cursor -= 1) {
    const token = tokens[cursor];
    if (token.start >= cursorOffset) continue;
    if (token.depth === depth && token.kind === "word" && !token.quoted && SET_OPERATORS.has(token.lower)) return cursor;
  }
  return start - 1;
}

function firstSetOperatorAfter(tokens: SqlToken[], start: number, end: number, depth: number,
                               cursorOffset: number): number {
  for (let cursor = start; cursor < end; cursor += 1) {
    const token = tokens[cursor];
    if (token.start < cursorOffset) continue;
    if (token.depth === depth && token.kind === "word" && !token.quoted && SET_OPERATORS.has(token.lower)) return cursor;
  }
  return end;
}

function lastTokenIndexBefore(tokens: SqlToken[], start: number, end: number, depth: number,
                              value: string, cursorOffset: number): number {
  for (let cursor = Math.min(end, tokens.length) - 1; cursor >= start; cursor -= 1) {
    const token = tokens[cursor];
    if (token.start < cursorOffset && token.depth === depth && token.lower === value && !token.quoted) return cursor;
  }
  return -1;
}

function firstTokenIndex(tokens: SqlToken[], start: number, end: number, depth: number, value: string): number {
  for (let cursor = start; cursor < end; cursor += 1) {
    if (tokens[cursor].depth === depth && tokens[cursor].lower === value && !tokens[cursor].quoted) return cursor;
  }
  return -1;
}

function uniqueNames(values: string[]): string[] {
  const seen = new Set<string>();
  return values.filter((value) => {
    const key = normalize(value);
    if (!key || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function isKeyword(value: string): boolean {
  return ALIAS_TERMINATORS.has(value) || SOURCE_TERMINATORS.has(value)
    || ["select", "distinct", "all", "case", "when", "then", "else", "end"].includes(value);
}

function lastIdentifier(value: string): string {
  return value.split(".").at(-1) ?? value;
}

function same(left: string, right: string): boolean {
  return normalize(left) === normalize(right);
}

function compareName(left: string, right: string): number {
  return left.localeCompare(right, undefined, { sensitivity: "base" });
}

function normalize(value: string): string {
  return value.trim().toLocaleLowerCase();
}
