import { completionDialect } from "./completion/sqlDialect";
import type { SqlCompletionDialect } from "./completion/sqlDialect";
import { activeParenthesisDepth, lexStatementAt } from "./completion/sqlLexer";
import type { SqlToken } from "./completion/sqlLexer";
import type {
  CompletionCandidate,
  CompletionCoverage,
  CompletionNamespaceSnapshot,
  CompletionObjectSnapshot,
  CompletionResult,
  CompletionSnapshot,
  CompletionSynonymSnapshot,
  QueryColumn,
  ResolvedResultColumnRemark,
  ResultColumnRemarkLookup
} from "./types";

export interface IndexedObject {
  snapshot: CompletionObjectSnapshot;
  columns: CompletionObjectSnapshot["columns"];
}

export interface IndexedNamespace {
  snapshot: CompletionNamespaceSnapshot;
  objects: Map<string, IndexedObject>;
  sortedObjects: IndexedObject[];
}

interface ResolvedSynonymObject {
  namespace: IndexedNamespace;
  object: IndexedObject;
}

export interface CompletionSource {
  kind: "physical" | "cte" | "derived";
  name: string;
  alias: string;
  namespaceName?: string;
  namespace?: IndexedNamespace;
  object?: IndexedObject;
  columns?: string[];
  start?: number;
  end?: number;
  nameStart?: number;
  nameEnd?: number;
  objectStart?: number;
  objectEnd?: number;
  resolution?: "resolved" | "unresolved" | "unconfirmed";
  quoted?: boolean;
  namespaceQuoted?: boolean;
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

interface IdentifierMatch {
  quality: number;
  span: number;
  skipped: number;
  start: number;
}

export interface CompletionIndex {
  namespaces: Map<string, IndexedNamespace>;
  namespaceValues: IndexedNamespace[];
  defaultNamespace?: IndexedNamespace;
  coverage: CompletionCoverage;
  synonyms: CompletionSynonymSnapshot[];
}

export interface CompletionObjectDelta {
  namespaceKey: string;
  catalog: string;
  schema: string;
  name: string;
  kind: "table" | "view";
  remarks: string;
}

/** Current object contents projected into the compact IndexedDB record shape. */
export interface CompletionStoredObject {
  namespaceKey: string;
  catalog: string;
  schema: string;
  name: string;
  kind: "table" | "view";
  remarks: string;
  columns: Array<{ name: string; remarks: string }>;
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
  preciseMatchingEnabled?: boolean;
}

const SOURCE_TERMINATORS = new Set([
  "where", "on", "group", "order", "having", "limit", "offset", "fetch", "union", "minus",
  "except", "intersect", "window", "qualify", "set", "values", "returning", "connect", "start",
  "for", "when", "matched"
]);

const ALIAS_TERMINATORS = new Set([
  ...SOURCE_TERMINATORS, "from", "join", "left", "right", "full", "inner", "outer", "cross",
  "straight_join", "natural", "lateral", "as", "using", "and", "or", "then", "else", "end", "into", "update", "select"
]);

const COLUMN_CLAUSES = new Set(["select", "where", "on", "group", "order", "having", "set", "returning"]);
const SET_OPERATORS = new Set(["union", "minus", "except", "intersect"]);

export interface CompletionIndexOptions {
  coverage?: Partial<CompletionCoverage>;
  synonyms?: CompletionSynonymSnapshot[];
}

const COMPLETE_COVERAGE: CompletionCoverage = {
  objects: "complete", columns: "complete", synonyms: "complete"
};

export function buildCompletionIndex(snapshot: CompletionSnapshot, options: CompletionIndexOptions = {}): CompletionIndex {
  const coverage = { ...COMPLETE_COVERAGE, ...(snapshot.coverage ?? {}), ...(options.coverage ?? {}) };
  const index = createCompletionIndex(snapshot.defaultNamespaceKey, snapshot.namespaces.map((value) => ({
    key: value.key, catalog: value.catalog, schema: value.schema, label: value.label
  })), { coverage, synonyms: [...(snapshot.synonyms ?? []), ...(options.synonyms ?? [])] });
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
                                      }>, options: CompletionIndexOptions = {}): CompletionIndex {
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
  return {
    namespaces, namespaceValues, defaultNamespace,
    coverage: { ...COMPLETE_COVERAGE, ...(options.coverage ?? {}) },
    synonyms: [...(options.synonyms ?? [])]
  };
}

export function setCompletionCoverage(index: CompletionIndex, coverage: Partial<CompletionCoverage>): void {
  index.coverage = { ...index.coverage, ...coverage };
}

export function upsertCompletionSynonyms(index: CompletionIndex, values: CompletionSynonymSnapshot[]): void {
  const byKey = new Map(index.synonyms.map((value) => [synonymKey(value), value]));
  for (const value of values) {
    if (!value || typeof value.namespaceKey !== "string" || typeof value.name !== "string"
        || typeof value.targetName !== "string") continue;
    byKey.set(synonymKey(value), { ...value });
  }
  index.synonyms = [...byKey.values()];
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

export function completionStoredObject(index: CompletionIndex, namespaceKey: string,
                                       objectName: string): CompletionStoredObject | undefined {
  const namespace = index.namespaces.get(normalize(namespaceKey));
  const object = namespace?.objects.get(normalize(objectName));
  if (!namespace || !object) return undefined;
  return {
    namespaceKey: namespace.snapshot.key,
    catalog: namespace.snapshot.catalog,
    schema: namespace.snapshot.schema,
    name: object.snapshot.name,
    kind: object.snapshot.kind,
    remarks: object.snapshot.remarks,
    columns: object.snapshot.columns.map((column) => ({ name: column.name, remarks: column.remarks }))
  };
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
      if (source.kind !== "physical") {
        if (source.resolution === "unconfirmed") unresolved = true;
        continue;
      }
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
    const oceanBaseNamespace = source.table && providerId === "oceanbase-oracle"
      && !source.schema && source.catalog
      ? resultNamespace(index, "", source.catalog) : undefined;
    const object = source.table
      ? namespace?.objects.get(normalize(source.table))
        ?? oceanBaseNamespace?.objects.get(normalize(source.table))
        ?? fallbackObject
      : fallbackObject;
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
  const preciseMatchingEnabled = request.preciseMatchingEnabled !== false;
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
    candidates.push(...tableCandidates(index, qualifier.parts, prefix, limit + 1, preciseMatchingEnabled));
  } else if (index && columnContext) {
    candidates.push(...columnCandidates(index, scope, qualifier.parts, prefix, limit + 1, preciseMatchingEnabled));
  }

  candidates.push(...keywordCandidates(dialect, tableContext ? "table" : columnContext ? "column" : clause, prefix));
  const filtered = deduplicate(candidates)
    .filter((candidate) => matchesCandidate(candidate, prefix, preciseMatchingEnabled))
    .sort((left, right) => compareCandidates(left, right, prefix, preciseMatchingEnabled));
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

export function parseCtes(index: CompletionIndex, tokens: SqlToken[], start: number, depth: number,
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
    // Register the CTE before inspecting its body so a recursive self-reference
    // is treated as a query source instead of an unknown physical table.
    const placeholder: CompletionSource = { kind: "cte", name, alias: name,
      columns: explicitColumns, start: nameToken.start, end: nameToken.end,
      nameStart: nameToken.start, nameEnd: nameToken.end };
    ctes.set(normalize(name), placeholder);
    const available = new Map([...inherited, ...ctes]);
    const columns = explicitColumns.length ? explicitColumns
      : projectedColumns(index, tokens, open + 1, close, depth + 1, available);
    ctes.set(normalize(name), { kind: "cte", name, alias: name, columns: uniqueNames(columns),
      start: nameToken.start, end: nameToken.end, nameStart: nameToken.start, nameEnd: nameToken.end });
    cursor = nextSignificant(tokens, close + 1, depth);
    if (tokens[cursor]?.value !== ",") break;
    cursor = nextSignificant(tokens, cursor + 1, depth);
  }
  return ctes;
}

export function parseSources(index: CompletionIndex, tokens: SqlToken[], start: number, end: number, depth: number,
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
    if (token.kind === "word" && !token.quoted && token.lower === "lateral") continue;
    if (token.value === "(") {
      const close = matchingClose(tokens, cursor);
      if (close < 0 || close >= end) break;
      const aliasResult = readAlias(tokens, close + 1, depth);
      const alias = aliasResult.alias || "derived";
      sources.push({ kind: "derived", name: alias, alias,
        columns: projectedColumns(index, tokens, cursor + 1, close, depth + 1, ctes),
        start: token.start, end: tokens[Math.max(close, aliasResult.nextIndex - 1)]?.end ?? tokens[close].end,
        nameStart: token.start, nameEnd: tokens[close].end });
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
    const nameEndIndex = Math.max(cursor, next - 1);
    const name = names.at(-1) ?? token.value;
    // TABLE(function(...)), JSON_TABLE(...), UNNEST(...) and vendor-specific
    // table functions are valid row sources even though they are not objects
    // in the selected schema. Keep them as opaque derived sources.
    if (tokens[next]?.value === "(" && kind !== "insert" && kind !== "merge") {
      const close = matchingClose(tokens, next);
      const aliasResult = close >= 0 ? readAlias(tokens, close + 1, depth) : { nextIndex: next + 1 };
      const alias = aliasResult.alias || name;
      sources.push({ kind: "derived", name: alias, alias, columns: [], start: token.start,
        end: tokens[Math.max(next, aliasResult.nextIndex - 1)]?.end ?? token.end,
        nameStart: token.start, nameEnd: token.end, resolution: "unconfirmed" });
      cursor = Math.max(next, aliasResult.nextIndex - 1);
      expectSource = false;
      continue;
    }
    // A database-link target is outside the local metadata snapshot. Consume
    // the link qualifier as part of the source and leave the source
    // unconfirmed, even when a local object happens to share the same name.
    let remote = false;
    if (tokens[next]?.value === "@") {
      remote = true;
      next += 1;
      if (tokens[next]?.kind === "word" && tokens[next]?.depth === depth) next += 1;
    }
    const aliasResult = readAlias(tokens, next, depth);
    const alias = aliasResult.alias || name;
    const cte = names.length === 1 ? ctes.get(normalize(name)) : undefined;
    const sourceEnd = tokens[Math.max(cursor, aliasResult.nextIndex - 1)]?.end ?? token.end;
    const sourceNameEnd = tokens[nameEndIndex]?.end ?? token.end;
    const sourceNameStart = tokens[nameEndIndex]?.start ?? token.start;
    const sourceNameQuoted = tokens[nameEndIndex]?.quoted ?? token.quoted;
    const sourceNamespaceQuoted = names.length > 1
      ? tokens[nameEndIndex - 2]?.quoted ?? false : false;
    sources.push(cte
      ? { ...cte, alias, start: token.start, end: sourceEnd, nameStart: token.start,
        nameEnd: sourceNameEnd,
        objectStart: sourceNameStart,
        objectEnd: sourceNameEnd }
      : physicalSource(index, names, alias, token.start, sourceEnd,
        token.start, sourceNameEnd, sourceNameStart, sourceNameEnd, sourceNameQuoted, remote,
        sourceNamespaceQuoted));
    cursor = aliasResult.nextIndex - 1;
    expectSource = false;
  }
  return deduplicateSources(sources);
}

function physicalSource(index: CompletionIndex, names: string[], alias: string, start?: number, end?: number,
                        nameStart?: number, nameEnd?: number, objectStart?: number,
                        objectEnd?: number, quoted = false, remote = false, namespaceQuoted = false): CompletionSource {
  const name = names.at(-1) ?? "";
  const namespaceName = names.length > 1 ? names.at(-2) : undefined;
  const namespace = namespaceName ? findNamespace(index, namespaceName, namespaceQuoted) : index.defaultNamespace;
  let resolvedNamespace = namespace;
  let object = namespace?.objects.get(normalize(name));
  if (quoted && object && object.snapshot.name !== name) object = undefined;
  let resolution: CompletionSource["resolution"] = object ? "resolved" : "unresolved";
  if (!object) {
    const synonym = names.length === 1 ? resolveSynonym(index, name, namespace, quoted)
      : resolvePrivateSynonym(index, name, namespace, quoted);
    if (synonym) {
      const resolved = resolveSynonymObject(index, synonym, new Set<string>());
      object = resolved?.object;
      resolvedNamespace = resolved?.namespace ?? namespace;
      resolution = object ? "resolved" : "unconfirmed";
    }
  }
  if (!object && !namespace) resolution = "unconfirmed";
  if (!object && index.coverage.objects !== "complete") resolution = "unconfirmed";
  if (!object && index.coverage.synonyms !== "complete") resolution = "unconfirmed";
  if (remote) { object = undefined; resolution = "unconfirmed"; }
  return { kind: "physical", name, alias, namespaceName, namespace: resolvedNamespace, start, end, nameStart, nameEnd,
    objectStart, objectEnd,
    object, resolution, quoted, namespaceQuoted };
}

function findNamespace(index: CompletionIndex, value: string, quoted: boolean): IndexedNamespace | undefined {
  if (!quoted) return index.namespaces.get(normalize(value));
  return index.namespaceValues.find((namespace) => {
    const snapshot = namespace.snapshot;
    return snapshot.schema === value || snapshot.catalog === value || snapshot.label === value;
  });
}

function synonymKey(value: CompletionSynonymSnapshot): string {
  return `${normalize(value.namespaceKey)}\u0000${normalize(value.name)}\u0000${isPublicSynonym(value) ? "public" : "private"}`;
}

function isPublicSynonym(value: CompletionSynonymSnapshot): boolean {
  return value.isPublic === true || normalize(value.namespaceKey) === "public";
}

function resolveSynonym(index: CompletionIndex, name: string, namespace?: IndexedNamespace,
                        quoted = false): CompletionSynonymSnapshot | undefined {
  const normalized = normalize(name);
  const privateSynonym = resolvePrivateSynonym(index, name, namespace, quoted);
  if (privateSynonym) return privateSynonym;
  return index.synonyms.find((value) => isPublicSynonym(value)
    && (quoted ? value.name === name : normalize(value.name) === normalized));
}

function resolvePrivateSynonym(index: CompletionIndex, name: string,
                               namespace?: IndexedNamespace, quoted = false): CompletionSynonymSnapshot | undefined {
  const normalized = normalize(name);
  const currentKeys = namespace ? [namespace.snapshot.key, namespace.snapshot.schema,
    namespace.snapshot.catalog, namespace.snapshot.label].filter(Boolean).map(normalize) : [];
  return index.synonyms.find((value) => !isPublicSynonym(value)
    && (quoted ? value.name === name : normalize(value.name) === normalized)
    && currentKeys.includes(normalize(value.namespaceKey)));
}

function resolveSynonymObject(index: CompletionIndex, synonym: CompletionSynonymSnapshot,
                              visited: Set<string>): ResolvedSynonymObject | undefined {
  if (synonym.databaseLink) return undefined;
  const key = `${normalize(synonym.namespaceKey)}\u0000${normalize(synonym.name)}`;
  if (visited.has(key)) return undefined;
  visited.add(key);
  const explicitTargetNamespace = Boolean(synonym.targetNamespaceKey || synonym.targetSchema);
  const ownerNamespace = !isPublicSynonym(synonym) && synonym.namespaceKey
    ? index.namespaces.get(normalize(synonym.namespaceKey)) : undefined;
  const targetNamespace = synonym.targetNamespaceKey
    ? index.namespaces.get(normalize(synonym.targetNamespaceKey))
    : synonym.targetSchema
      ? index.namespaces.get(normalize(synonym.targetSchema))
      : ownerNamespace ?? index.defaultNamespace;
  // A schema-qualified synonym target cannot fall back to a public synonym
  // when that target schema is not loaded. Treat the result as unconfirmed so
  // an incomplete cache cannot turn an unrelated public name into a match.
  if (explicitTargetNamespace && !targetNamespace) return undefined;
  const object = targetNamespace?.objects.get(normalize(synonym.targetName));
  if (object) return { namespace: targetNamespace!, object };
  const nested = targetNamespace && explicitTargetNamespace
    ? resolvePrivateSynonym(index, synonym.targetName, targetNamespace)
    : resolveSynonym(index, synonym.targetName, targetNamespace);
  return nested ? resolveSynonymObject(index, nested, visited) : undefined;
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

export function sourceColumnNames(source: CompletionSource): string[] {
  if (source.kind === "physical") return source.object?.columns.map((column) => column.name) ?? [];
  return source.columns ?? [];
}

function tableCandidates(index: CompletionIndex, qualifier: string[], prefix: string, maximum: number,
                         preciseMatchingEnabled: boolean): CandidateRank[] {
  if (qualifier.length > 1) return [];
  if (qualifier.length === 1) {
    const namespace = index.namespaces.get(normalize(qualifier[0]));
    return namespace ? objectCandidates(index, namespace, true, prefix, maximum, preciseMatchingEnabled) : [];
  }
  const result: CandidateRank[] = [];
  for (const namespace of index.namespaceValues) {
    if (matchesName(namespace.snapshot.label, prefix, preciseMatchingEnabled)) {
      pushBoundedCandidate(result, {
        displayLabel: namespace.snapshot.label,
        documentationPath: namespace.snapshot.label,
        insertText: namespace.snapshot.label,
        filterText: namespace.snapshot.label,
        kind: "schema",
        remarks: "",
        typeName: "SCHEMA",
        namespacePriority: namespace === index.defaultNamespace ? 0 : 1
      }, maximum, prefix, preciseMatchingEnabled);
    }
    for (const candidate of objectCandidates(index, namespace, false, prefix, maximum,
      preciseMatchingEnabled)) {
      pushBoundedCandidate(result, candidate, maximum, prefix, preciseMatchingEnabled);
    }
  }
  return result.sort((left, right) =>
    compareCandidates(left, right, prefix, preciseMatchingEnabled)).slice(0, maximum);
}

function objectCandidates(index: CompletionIndex, namespace: IndexedNamespace, qualified: boolean,
                          prefix: string, maximum: number, preciseMatchingEnabled: boolean): CandidateRank[] {
  const isDefault = namespace === index.defaultNamespace;
  const result: CandidateRank[] = [];
  for (const indexed of namespace.sortedObjects) {
    const object = indexed.snapshot;
    if (!matchesName(object.name, prefix, preciseMatchingEnabled)) continue;
    const path = `${namespace.snapshot.label}.${object.name}`;
    const contextual = qualified || isDefault ? object.name : path;
    pushBoundedCandidate(result, {
      displayLabel: contextual,
      documentationPath: path,
      insertText: contextual,
      filterText: `${object.name} ${path}`,
      kind: object.kind,
      remarks: object.remarks,
      typeName: object.kind.toUpperCase(),
      namespacePriority: isDefault ? 0 : 1
    }, maximum, prefix, preciseMatchingEnabled);
  }
  return result.sort((left, right) =>
    compareCandidates(left, right, prefix, preciseMatchingEnabled)).slice(0, maximum);
}

function columnCandidates(index: CompletionIndex, scope: CompletionScope | undefined, qualifier: string[],
                          prefix: string, maximum: number, preciseMatchingEnabled: boolean): CandidateRank[] {
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
    if (source.kind === "physical" && source.namespace && source.object) {
      for (const column of source.object.columns) {
        if (!matchesName(column.name, prefix, preciseMatchingEnabled)) continue;
        const contextual = qualifyWithAlias ? `${source.alias}.${column.name}` : column.name;
        const path = `${source.namespace.snapshot.label}.${source.object.snapshot.name}.${column.name}`;
        pushBoundedCandidate(result, { displayLabel: contextual, documentationPath: path, insertText: contextual,
          filterText: `${column.name} ${source.alias}.${column.name} ${path}`, kind: "column",
          remarks: column.remarks, typeName: column.typeName,
          namespacePriority: source.namespace === index.defaultNamespace ? 0 : 1 },
        maximum, prefix, preciseMatchingEnabled);
      }
    } else {
      for (const column of [...(source.columns ?? [])].sort(compareName)) {
        if (!matchesName(column, prefix, preciseMatchingEnabled)) continue;
        const contextual = qualifyWithAlias ? `${source.alias}.${column}` : column;
        pushBoundedCandidate(result, { displayLabel: contextual, documentationPath: `${source.alias}.${column}`,
          insertText: contextual, filterText: `${column} ${source.alias}.${column}`, kind: "column",
          remarks: "", typeName: source.kind === "cte" ? "CTE" : "DERIVED" },
        maximum, prefix, preciseMatchingEnabled);
      }
    }
  }
  return result.sort((left, right) =>
    compareCandidates(left, right, prefix, preciseMatchingEnabled)).slice(0, maximum);
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
  return values.filter((keyword) => matchesName(keyword, prefix, true)).map((keyword) => ({
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

function compareCandidates(left: CandidateRank, right: CandidateRank, prefix: string,
                           preciseMatchingEnabled = true): number {
  const leftName = normalize(lastIdentifier(left.displayLabel));
  const rightName = normalize(lastIdentifier(right.displayLabel));
  if (!preciseMatchingEnabled && prefix) {
    const leftMatch = identifierMatch(leftName, prefix, false);
    const rightMatch = identifierMatch(rightName, prefix, false);
    const matchOrder = compareIdentifierMatch(leftMatch, rightMatch);
    if (matchOrder !== 0) return matchOrder;
  }
  const leftExact = prefix && leftName === prefix ? 0 : 1;
  const rightExact = prefix && rightName === prefix ? 0 : 1;
  if (leftExact !== rightExact) return leftExact - rightExact;
  if ((left.namespacePriority ?? 1) !== (right.namespacePriority ?? 1)) {
    return (left.namespacePriority ?? 1) - (right.namespacePriority ?? 1);
  }
  const priority: Record<CompletionCandidate["kind"], number> = {
    snippet: -1, column: 0, table: 1, view: 2, schema: 3, keyword: 4
  };
  return priority[left.kind] - priority[right.kind]
    || compareName(leftName, rightName)
    || compareName(left.documentationPath, right.documentationPath);
}

function matchesCandidate(candidate: CompletionCandidate, prefix: string,
                          preciseMatchingEnabled: boolean): boolean {
  if (!prefix) return true;
  const precise = candidate.kind === "keyword" || candidate.kind === "snippet"
    ? true : preciseMatchingEnabled;
  return Boolean(identifierMatch(lastIdentifier(candidate.displayLabel), prefix, precise)
    || identifierMatch(lastIdentifier(candidate.insertText), prefix, precise)
    || candidate.filterText.split(/\s+/).some((value) =>
      identifierMatch(lastIdentifier(value), prefix, precise)));
}

function matchesName(value: string, prefix: string, preciseMatchingEnabled: boolean): boolean {
  return Boolean(identifierMatch(value, prefix, preciseMatchingEnabled));
}

function identifierMatch(value: string, prefix: string, preciseMatchingEnabled: boolean): IdentifierMatch | undefined {
  const name = Array.from(normalize(value));
  const query = Array.from(normalize(prefix));
  if (!query.length) return { quality: 3, span: 0, skipped: 0, start: 0 };
  if (query.length > name.length) return undefined;
  const normalizedName = name.join("");
  const normalizedPrefix = query.join("");
  if (normalizedName === normalizedPrefix) {
    return { quality: 0, span: query.length, skipped: 0, start: 0 };
  }
  if (normalizedName.startsWith(normalizedPrefix)) {
    return { quality: 1, span: query.length, skipped: 0, start: 0 };
  }
  if (preciseMatchingEnabled) return undefined;
  let best: IdentifierMatch | undefined;
  for (let start = 0; start < name.length; start += 1) {
    if (name[start] !== query[0]) continue;
    let nameIndex = start + 1;
    let queryIndex = 1;
    while (nameIndex < name.length && queryIndex < query.length) {
      if (name[nameIndex] === query[queryIndex]) queryIndex += 1;
      nameIndex += 1;
    }
    if (queryIndex !== query.length) continue;
    const span = nameIndex - start;
    const match = { quality: 2, span, skipped: start + span - query.length, start };
    if (compareIdentifierMatch(match, best) < 0) best = match;
  }
  return best;
}

function compareIdentifierMatch(left: IdentifierMatch | undefined,
                                right: IdentifierMatch | undefined): number {
  if (!left) return right ? 1 : 0;
  if (!right) return -1;
  return left.quality - right.quality
    || left.span - right.span
    || left.skipped - right.skipped
    || left.start - right.start;
}

function pushBoundedCandidate(values: CandidateRank[], candidate: CandidateRank, maximum: number,
                              prefix: string, preciseMatchingEnabled: boolean): void {
  values.push(candidate);
  if (values.length <= maximum * 2) return;
  values.sort((left, right) => compareCandidates(left, right, prefix, preciseMatchingEnabled));
  values.splice(maximum);
}

export function statementKind(tokens: SqlToken[], start: number, depth: number): string {
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

export function normalize(value: string): string {
  return value.trim().toLocaleLowerCase();
}

/** Applies the same identifier matching and ranking used by completion to diagnostic replacements. */
export function diagnosticNameCandidates(values: string[], input: string, maximum = 3): string[] {
  const prefix = normalize(input);
  return uniqueNames(values).filter((value) => identifierMatch(value, prefix, false))
    .sort((left, right) => compareIdentifierMatch(
      identifierMatch(left, prefix, false), identifierMatch(right, prefix, false))
      || compareName(left, right))
    .slice(0, Math.max(0, maximum));
}
