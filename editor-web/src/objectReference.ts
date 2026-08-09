import { completionDialect } from "./completion/sqlDialect";
import { lexStatementAt, type SqlToken } from "./completion/sqlLexer";
import type { SqlObjectReference } from "./types";

interface Source {
  parts: SqlToken[];
  alias?: SqlToken;
}

const SOURCE_START = new Set(["from", "join", "update", "using"]);
const SOURCE_END = new Set([
  "where", "set", "values", "on", "group", "order", "having", "returning", "limit", "offset",
  "fetch", "union", "minus", "intersect", "when", "connect", "start"
]);
const RESERVED_ALIAS = new Set([
  ...SOURCE_START, ...SOURCE_END, "left", "right", "full", "inner", "outer", "cross", "natural",
  "into", "as", "select", "insert", "delete", "merge", "and", "or"
]);

/**
 * Resolves a hovered table/view (or its alias) without consulting completion metadata.
 * Comments and strings are absent from the lexer token stream, so they can never resolve.
 */
export function resolveSqlObjectReference(
  sql: string,
  offset: number,
  providerId: string,
  defaults: { catalog?: string; schema?: string } = {}
): SqlObjectReference | null {
  const statement = lexStatementAt(sql, offset, completionDialect(providerId));
  const hovered = statement.tokens.find(token => token.kind === "word" && offset >= token.start && offset <= token.end);
  if (!hovered) return null;
  const ctes = collectCtes(statement.tokens);
  const sources = collectSources(statement.tokens, ctes);
  const source = sources.find(candidate => candidate.parts.at(-1) === hovered
    || candidate.alias?.lower === hovered.lower);
  if (!source) return null;

  const names = source.parts.map(token => token.value);
  const objectName = names.at(-1)!;
  const namespace = names.length > 1 ? names.at(-2) : undefined;
  const catalog = names.length > 2 ? names.at(-3) : defaults.catalog;
  const schema = names.length > 1 ? namespace : defaults.schema;
  const viaAlias = source.alias?.lower === hovered.lower && source.parts.at(-1) !== hovered;
  return {
    catalog,
    schema,
    objectName,
    alias: source.alias?.value,
    viaAlias,
    quoted: source.parts.some(token => token.quoted),
    range: { start: hovered.start, end: hovered.end }
  };
}

function collectCtes(tokens: SqlToken[]): Set<string> {
  const names = new Set<string>();
  for (let index = 0; index + 2 < tokens.length; index += 1) {
    const token = tokens[index];
    if (token.kind !== "word") continue;
    const previous = tokens[index - 1];
    const next = tokens[index + 1];
    const after = next?.lower === "as" ? tokens[index + 2] : undefined;
    if ((previous?.lower === "with" || previous?.value === ",") && next?.lower === "as" && after?.value === "(") {
      names.add(token.lower);
    }
  }
  return names;
}

function collectSources(tokens: SqlToken[], ctes: Set<string>): Source[] {
  const result: Source[] = [];
  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index];
    let begins = SOURCE_START.has(token.lower);
    if (token.lower === "into") {
      const prior = previousWord(tokens, index);
      begins = prior?.lower === "insert" || prior?.lower === "merge";
    }
    if (token.lower === "from" && previousWord(tokens, index)?.lower === "delete") begins = true;
    if (!begins) continue;
    const parsed = readSource(tokens, index + 1, ctes);
    if (parsed.source) result.push(parsed.source);
    index = Math.max(index, parsed.end - 1);
  }
  return result;
}

function readSource(tokens: SqlToken[], start: number, ctes: Set<string>): { source?: Source; end: number } {
  let index = start;
  if (tokens[index]?.value === "(") return { end: skipParenthesized(tokens, index) };
  if (tokens[index]?.kind !== "word") return { end: index + 1 };
  const parts: SqlToken[] = [tokens[index++]];
  while (tokens[index]?.value === "." && tokens[index + 1]?.kind === "word" && parts.length < 3) {
    parts.push(tokens[index + 1]);
    index += 2;
  }
  if (ctes.has(parts.at(-1)!.lower)) return { end: index };
  if (tokens[index]?.lower === "as") index += 1;
  const alias = tokens[index]?.kind === "word" && !RESERVED_ALIAS.has(tokens[index].lower) ? tokens[index++] : undefined;
  return { source: { parts, alias }, end: index };
}

function skipParenthesized(tokens: SqlToken[], start: number): number {
  const depth = tokens[start].depth;
  let index = start + 1;
  while (index < tokens.length && !(tokens[index].value === ")" && tokens[index].depth === depth)) index += 1;
  return index + 1;
}

function previousWord(tokens: SqlToken[], index: number): SqlToken | undefined {
  for (let cursor = index - 1; cursor >= 0; cursor -= 1) {
    if (tokens[cursor].kind === "word") return tokens[cursor];
  }
  return undefined;
}
