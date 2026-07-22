import { GenericSQL, MySQL } from "dt-sql-parser";
import type {
  CompletionCandidate,
  CompletionNamespaceSnapshot,
  CompletionObjectSnapshot,
  CompletionResult,
  CompletionSnapshot
} from "./types";

type SqlParser = MySQL | GenericSQL;
type ParserSyntax = { syntaxContextType?: string; wordRanges?: Array<{ text?: string }> };
type ParserEntity = {
  entityContextType?: string;
  text?: string;
  isAccessible?: boolean;
  _alias?: { text?: string };
  position?: { endTokenIndex?: number };
};
type ParserToken = { tokenIndex: number; text?: string; channel?: number };

interface IndexedNamespace {
  snapshot: CompletionNamespaceSnapshot;
  objects: Map<string, CompletionObjectSnapshot>;
}

interface IndexedSource {
  namespace: IndexedNamespace;
  object: CompletionObjectSnapshot;
  alias: string;
}

export interface CompletionIndex {
  snapshot: CompletionSnapshot;
  namespaces: Map<string, IndexedNamespace>;
  defaultNamespace?: IndexedNamespace;
}

export interface CompletionRequest {
  providerId: string;
  sql: string;
  prefix: string;
  limit: number;
}

const HEAL_IDENTIFIER = "__dbstudio_completion__";

export function buildCompletionIndex(snapshot: CompletionSnapshot): CompletionIndex {
  const namespaces = new Map<string, IndexedNamespace>();
  let defaultNamespace: IndexedNamespace | undefined;
  for (const value of snapshot.namespaces) {
    const indexed: IndexedNamespace = {
      snapshot: value,
      objects: new Map(value.objects.map((object) => [normalize(object.name), object]))
    };
    for (const name of [value.key, value.label, value.catalog, value.schema]) {
      if (name) namespaces.set(normalize(name), indexed);
    }
    if (value.key === snapshot.defaultNamespaceKey) defaultNamespace = indexed;
  }
  return { snapshot, namespaces, defaultNamespace };
}

export function resolveCompletion(index: CompletionIndex | undefined, request: CompletionRequest): CompletionResult {
  const limit = Math.max(10, Math.min(1000, request.limit || 100));
  const parser = parserFor(request.providerId);
  const position = caretPosition(request.sql);
  let syntax: ParserSyntax[] = [];
  let keywords: string[] = [];
  let entities: ParserEntity[] = [];
  let tokens: ParserToken[] = [];
  try {
    const suggestions = parser.getSuggestionAtCaretPosition(request.sql, position);
    syntax = (suggestions?.syntax ?? []) as ParserSyntax[];
    keywords = (suggestions?.keywords ?? []) as string[];
    const healing = healStatement(parser, request.sql);
    const healedSql = healing.sql;
    entities = (parser.getAllEntities(healedSql, healing.caret) ?? []) as ParserEntity[];
    tokens = parser.getAllTokens(healedSql) as ParserToken[];
  } catch {
    // Keep parser-provided statement starters as the only safe fallback for a broken statement.
    try { keywords = (parser.getSuggestionAtCaretPosition("", { lineNumber: 1, column: 1 })?.keywords ?? []) as string[]; }
    catch { keywords = []; }
  }

  const qualifier = syntaxQualifier(syntax)
    || tokenQualifier(tokens, needsHealing(request.sql), request.prefix);
  const tableContext = syntax.some((item) => item.syntaxContextType === "table" || item.syntaxContextType === "view");
  const hasAccessibleSources = entities.some((entity) => entity.entityContextType === "table" && entity.isAccessible);
  const columnContext = syntax.some((item) => item.syntaxContextType === "column")
    || (!tableContext && hasAccessibleSources);
  const candidates: CompletionCandidate[] = [];

  if (index && tableContext) {
    if (qualifier) {
      const namespace = index.namespaces.get(normalize(qualifier));
      if (namespace) candidates.push(...objectCandidates(index, namespace, true));
    } else {
      for (const namespace of uniqueNamespaces(index)) {
        candidates.push(namespaceCandidate(index, namespace));
        candidates.push(...objectCandidates(index, namespace, false));
      }
    }
  } else if (index && columnContext) {
    const sources = resolveSources(index, entities, tokens);
    const selected = qualifier
      ? sources.filter((source) => normalize(source.alias) === normalize(qualifier)
        || normalize(source.object.name) === normalize(qualifier))
      : sources;
    if (selected.length) candidates.push(...columnCandidates(selected, Boolean(qualifier), request.providerId));
  }

  candidates.push(...keywordCandidates(keywords));
  const prefix = normalize(request.prefix);
  const filtered = deduplicate(candidates)
    .filter((candidate) => !prefix || normalize(candidate.label).startsWith(prefix)
      || normalize(lastIdentifier(candidate.qualifiedLabel)).startsWith(prefix)
      || normalize(unquote(candidate.insertText)).startsWith(prefix))
    .sort((left, right) => compareCandidates(left, right, prefix));
  return { items: filtered.slice(0, limit), incomplete: filtered.length > limit };
}

function namespaceCandidate(index: CompletionIndex, namespace: IndexedNamespace): CompletionCandidate {
  return {
    label: namespace.snapshot.label,
    qualifiedLabel: namespace.snapshot.label,
    insertText: quoteIdentifier(namespace.snapshot.label, index.snapshot.providerId),
    kind: "schema",
    remarks: "",
    typeName: "SCHEMA"
  };
}

function parserFor(providerId: string): SqlParser {
  return normalize(providerId).includes("mysql") && !normalize(providerId).includes("oracle")
    ? new MySQL()
    : new GenericSQL({ diagnostics: false });
}

function caretPosition(sql: string): { lineNumber: number; column: number } {
  const lines = sql.split("\n");
  return { lineNumber: lines.length, column: (lines.at(-1)?.length ?? 0) + 1 };
}

function needsHealing(sql: string): boolean {
  return /\.\s*$/.test(sql);
}

function healStatement(parser: SqlParser, sql: string): { sql: string; caret: { lineNumber: number; column: number } } {
  const atCaret = needsHealing(sql) ? `${sql}${HEAL_IDENTIFIER}` : sql;
  const tokens = parser.getAllTokens(atCaret) as ParserToken[];
  let parentheses = 0;
  for (const token of tokens) {
    if (token.channel !== 0) continue;
    if (token.text === "(") parentheses += 1;
    else if (token.text === ")") parentheses = Math.max(0, parentheses - 1);
  }
  return { sql: `${atCaret}${")".repeat(parentheses)}`, caret: caretPosition(atCaret) };
}

function syntaxQualifier(syntax: ParserSyntax[]): string {
  for (const item of syntax) {
    if (!item.wordRanges?.length) continue;
    const words = item.wordRanges.map((word) => word.text ?? "").filter((word) => word && word !== ".");
    if (item.wordRanges.some((word) => word.text === ".") && words.length) return unquote(words.at(-1) ?? "");
  }
  return "";
}

function resolveSources(index: CompletionIndex, entities: ParserEntity[], tokens: ParserToken[]): IndexedSource[] {
  const result: IndexedSource[] = [];
  for (const entity of entities) {
    if (entity.entityContextType !== "table" || !entity.isAccessible || !entity.text) continue;
    const path = splitIdentifierPath(entity.text).filter((part) => normalize(part) !== normalize(HEAL_IDENTIFIER));
    if (!path.length) continue;
    const objectName = path.at(-1) ?? "";
    const namespace = path.length > 1 ? index.namespaces.get(normalize(path.at(-2) ?? "")) : index.defaultNamespace;
    const object = namespace?.objects.get(normalize(objectName));
    if (!namespace || !object) continue;
    const alias = unquote(entity._alias?.text || aliasFromTokens(entity, tokens) || object.name);
    result.push({ namespace, object, alias });
  }
  return result;
}

function tokenQualifier(tokens: ParserToken[], trailingDot: boolean, prefix: string): string {
  const visible = tokens.filter((token) => token.channel === 0 && token.text);
  let dotIndex = -1;
  const healingIndex = visible.findIndex((token) => token.text === HEAL_IDENTIFIER);
  if (trailingDot && healingIndex > 1 && visible[healingIndex - 1]?.text === ".") {
    dotIndex = healingIndex - 1;
  } else if (prefix && normalize(visible.at(-1)?.text ?? "") === normalize(prefix) && visible.at(-2)?.text === ".") {
    dotIndex = visible.length - 2;
  }
  return dotIndex > 0 ? unquote(visible[dotIndex - 1].text ?? "") : "";
}

function aliasFromTokens(entity: ParserEntity, tokens: ParserToken[]): string {
  const end = entity.position?.endTokenIndex;
  if (end === undefined) return "";
  const following = tokens.filter((token) => token.channel === 0 && token.tokenIndex > end && token.text);
  let candidate = following[0]?.text ?? "";
  if (normalize(candidate) === "as") candidate = following[1]?.text ?? "";
  if (!candidate || ALIAS_BOUNDARIES.has(normalize(candidate)) || /^[,;()=.]+$/.test(candidate)) return "";
  return unquote(candidate);
}

const ALIAS_BOUNDARIES = new Set([
  "where", "join", "left", "right", "full", "inner", "cross", "on", "using", "group", "order",
  "having", "limit", "offset", "fetch", "union", "minus", "except", "intersect", "connect", "start"
]);

function objectCandidates(index: CompletionIndex, namespace: IndexedNamespace, qualifiedContext: boolean): CompletionCandidate[] {
  const isDefault = namespace === index.defaultNamespace;
  return namespace.snapshot.objects.map((object) => ({
    label: object.name,
    qualifiedLabel: `${namespace.snapshot.label}.${object.name}`,
    insertText: qualifiedContext || isDefault
      ? quoteIdentifier(object.name, index.snapshot.providerId)
      : `${quoteIdentifier(namespace.snapshot.label, index.snapshot.providerId)}.${quoteIdentifier(object.name, index.snapshot.providerId)}`,
    kind: object.kind,
    remarks: object.remarks,
    typeName: object.kind.toUpperCase()
  }));
}

function columnCandidates(sources: IndexedSource[], qualifiedContext: boolean, providerId: string): CompletionCandidate[] {
  const counts = new Map<string, number>();
  for (const source of sources) {
    for (const column of source.object.columns) {
      const key = normalize(column.name);
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
  }
  const result: CompletionCandidate[] = [];
  for (const source of sources) {
    for (const column of source.object.columns) {
      const duplicate = (counts.get(normalize(column.name)) ?? 0) > 1;
      const columnText = quoteIdentifier(column.name, providerId);
      result.push({
        label: column.name,
        qualifiedLabel: `${source.namespace.snapshot.label}.${source.object.name}.${column.name}`,
        insertText: qualifiedContext ? columnText
          : duplicate ? `${quoteIdentifier(source.alias, providerId)}.${columnText}` : columnText,
        kind: "column",
        remarks: column.remarks,
        typeName: column.typeName
      });
    }
  }
  return result;
}

function keywordCandidates(keywords: string[]): CompletionCandidate[] {
  return keywords.map((keyword) => ({
    label: keyword,
    qualifiedLabel: keyword,
    insertText: keyword,
    kind: "keyword" as const,
    remarks: "",
    typeName: "KEYWORD"
  }));
}

function quoteIdentifier(value: string, providerId: string): string {
  const quote = normalize(providerId).includes("mysql") && !normalize(providerId).includes("oracle") ? "`" : "\"";
  return `${quote}${value.replaceAll(quote, quote + quote)}${quote}`;
}

function uniqueNamespaces(index: CompletionIndex): IndexedNamespace[] {
  return [...new Set(index.snapshot.namespaces.map((namespace) => index.namespaces.get(normalize(namespace.key))))]
    .filter((value): value is IndexedNamespace => Boolean(value));
}

function splitIdentifierPath(value: string): string[] {
  const result: string[] = [];
  let current = "";
  let quote = "";
  for (const character of value) {
    if ((character === "`" || character === "\"") && (!quote || quote === character)) {
      quote = quote ? "" : character;
      continue;
    }
    if (character === "." && !quote) {
      if (current) result.push(current);
      current = "";
    } else current += character;
  }
  if (current) result.push(current);
  return result;
}

function deduplicate(values: CompletionCandidate[]): CompletionCandidate[] {
  const seen = new Set<string>();
  return values.filter((value) => {
    const key = `${value.kind}\u0000${normalize(value.qualifiedLabel)}\u0000${normalize(value.insertText)}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function compareCandidates(left: CompletionCandidate, right: CompletionCandidate, prefix: string): number {
  const leftName = normalize(left.label);
  const rightName = normalize(right.label);
  const leftExact = prefix && leftName === prefix ? 0 : 1;
  const rightExact = prefix && rightName === prefix ? 0 : 1;
  if (leftExact !== rightExact) return leftExact - rightExact;
  const priority: Record<CompletionCandidate["kind"], number> = { column: 0, table: 1, view: 2, schema: 3, keyword: 4 };
  return priority[left.kind] - priority[right.kind]
    || leftName.localeCompare(rightName, undefined, { sensitivity: "base" })
    || left.qualifiedLabel.localeCompare(right.qualifiedLabel, undefined, { sensitivity: "base" });
}

function lastIdentifier(value: string): string {
  return value.split(".").at(-1) ?? value;
}

function unquote(value: string): string {
  const trimmed = value.trim();
  if ((trimmed.startsWith("`") && trimmed.endsWith("`"))
    || (trimmed.startsWith("\"") && trimmed.endsWith("\""))) return trimmed.slice(1, -1);
  return trimmed;
}

function normalize(value: string): string {
  return unquote(value).toLocaleLowerCase();
}
