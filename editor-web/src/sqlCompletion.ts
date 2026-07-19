import type { CompletionContext, CompletionScope, CompletionSource, Suggestion } from "./types";

interface SqlToken {
  kind: "word" | "symbol";
  value: string;
  lower: string;
  start: number;
  end: number;
  depth: number;
}

const sourceTerminators = new Set([
  "where", "on", "join", "left", "right", "inner", "outer", "cross", "full", "straight_join",
  "group", "order", "having", "limit", "union", "except", "intersect", "window", "qualify",
  "set", "values", "returning", "for", "lock"
]);
const aliasTerminators = new Set([...sourceTerminators, "from", "as", "using", "and", "or", "when", "then", "else", "end"]);

export function resolveCompletionSuggestions(
  sqlBeforeCursor: string,
  suggestions: Suggestion[],
  defaultCatalog = ""
): Suggestion[] {
  const context = analyzeCompletionContext(sqlBeforeCursor, suggestions, defaultCatalog);
  if (context.qualifier.length === 0) return suggestions;
  if (context.qualifier.length >= 2) {
    return physicalColumns(suggestions, context.qualifier[context.qualifier.length - 1], context.qualifier[context.qualifier.length - 2]);
  }

  const qualifier = context.qualifier[0].toLocaleLowerCase();
  let scope = context.scope;
  while (scope) {
    const source = scope.sources.find((item) => item.alias.toLocaleLowerCase() === qualifier)
      ?? scope.sources.find((item) => item.name.toLocaleLowerCase() === qualifier);
    if (source) return suggestionsForSource(source, suggestions, defaultCatalog);
    scope = scope.parent;
  }
  return [];
}

export function analyzeCompletionContext(
  sqlBeforeCursor: string,
  suggestions: Suggestion[] = [],
  defaultCatalog = ""
): CompletionContext {
  const tokens = lex(sqlBeforeCursor);
  const statementStart = lastStatementStart(tokens);
  const statementTokens = tokens.slice(statementStart);
  const qualifier = readQualifier(statementTokens);
  if (qualifier.length === 0) return { qualifier, defaultCatalog };

  const currentDepth = unmatchedOpenDepth(statementTokens);
  const ctes = parseCtes(statementTokens, suggestions, defaultCatalog);
  const starts = activeScopeStarts(statementTokens, currentDepth);
  let parent: CompletionScope | undefined;
  for (let depth = 0; depth <= currentDepth; depth += 1) {
    const selectIndex = lastTokenIndex(statementTokens, starts[depth] ?? 0, depth, "select");
    if (selectIndex < 0) continue;
    parent = {
      depth,
      sources: parseSources(statementTokens, selectIndex, statementTokens.length, depth, ctes, suggestions, defaultCatalog),
      parent
    };
  }
  return { qualifier, defaultCatalog, scope: parent };
}

function lex(sql: string): SqlToken[] {
  const tokens: SqlToken[] = [];
  let index = 0;
  let depth = 0;
  while (index < sql.length) {
    const char = sql[index];
    if (/\s/.test(char)) { index += 1; continue; }
    if (char === "-" && sql[index + 1] === "-") { index = skipLine(sql, index + 2); continue; }
    if (char === "#") { index = skipLine(sql, index + 1); continue; }
    if (char === "/" && sql[index + 1] === "*") {
      const end = sql.indexOf("*/", index + 2);
      index = end < 0 ? sql.length : end + 2;
      continue;
    }
    if (char === "'" || char === "\"") { index = skipQuoted(sql, index, char); continue; }
    if (char === "`") {
      const start = index;
      let value = "";
      index += 1;
      while (index < sql.length) {
        if (sql[index] === "`" && sql[index + 1] === "`") { value += "`"; index += 2; continue; }
        if (sql[index] === "`") { index += 1; break; }
        value += sql[index++];
      }
      tokens.push(wordToken(value, start, index, depth));
      continue;
    }
    if (/[A-Za-z_$\u0080-\uFFFF]/.test(char)) {
      const start = index++;
      while (index < sql.length && /[\w$\u0080-\uFFFF]/.test(sql[index])) index += 1;
      tokens.push(wordToken(sql.slice(start, index), start, index, depth));
      continue;
    }
    if (char === "(") {
      tokens.push(symbolToken(char, index, depth));
      depth += 1;
      index += 1;
      continue;
    }
    if (char === ")") {
      depth = Math.max(0, depth - 1);
      tokens.push(symbolToken(char, index, depth));
      index += 1;
      continue;
    }
    tokens.push(symbolToken(char, index, depth));
    index += 1;
  }
  return tokens;
}

function skipLine(sql: string, start: number): number {
  const newline = sql.indexOf("\n", start);
  return newline < 0 ? sql.length : newline + 1;
}

function skipQuoted(sql: string, start: number, quote: string): number {
  let index = start + 1;
  while (index < sql.length) {
    if (sql[index] === "\\") { index += 2; continue; }
    if (sql[index] === quote && sql[index + 1] === quote) { index += 2; continue; }
    if (sql[index] === quote) return index + 1;
    index += 1;
  }
  return sql.length;
}

function wordToken(value: string, start: number, end: number, depth: number): SqlToken {
  return { kind: "word", value, lower: value.toLocaleLowerCase(), start, end, depth };
}

function symbolToken(value: string, start: number, depth: number): SqlToken {
  return { kind: "symbol", value, lower: value, start, end: start + 1, depth };
}

function lastStatementStart(tokens: SqlToken[]): number {
  for (let index = tokens.length - 1; index >= 0; index -= 1) {
    if (tokens[index].value === ";" && tokens[index].depth === 0) return index + 1;
  }
  return 0;
}

function unmatchedOpenDepth(tokens: SqlToken[]): number {
  let depth = 0;
  tokens.forEach((token) => {
    if (token.value === "(") depth += 1;
    else if (token.value === ")") depth = Math.max(0, depth - 1);
  });
  return depth;
}

function activeScopeStarts(tokens: SqlToken[], currentDepth: number): number[] {
  const starts = new Array<number>(currentDepth + 1).fill(0);
  const stack: number[] = [];
  tokens.forEach((token, index) => {
    if (token.value === "(") stack.push(index + 1);
    else if (token.value === ")") stack.pop();
  });
  stack.forEach((start, index) => { starts[index + 1] = start; });
  return starts;
}

function lastTokenIndex(tokens: SqlToken[], start: number, depth: number, value: string): number {
  for (let index = tokens.length - 1; index >= start; index -= 1) {
    if (tokens[index].depth === depth && tokens[index].lower === value) return index;
  }
  return -1;
}

function readQualifier(tokens: SqlToken[]): string[] {
  if (tokens.length === 0) return [];
  let dot = tokens.length - 1;
  if (tokens[dot].value !== ".") {
    if (tokens[dot].kind !== "word" || dot === 0 || tokens[dot - 1].value !== ".") return [];
    dot -= 1;
  }
  const qualifier: string[] = [];
  let index = dot - 1;
  while (index >= 0 && tokens[index].kind === "word") {
    qualifier.unshift(tokens[index].value);
    if (index < 2 || tokens[index - 1].value !== ".") break;
    index -= 2;
  }
  return qualifier.slice(-2);
}

function parseCtes(tokens: SqlToken[], suggestions: Suggestion[], defaultCatalog: string): Map<string, CompletionSource> {
  const ctes = new Map<string, CompletionSource>();
  let index = tokens.findIndex((token) => token.depth === 0 && token.kind === "word");
  if (index < 0 || tokens[index].lower !== "with") return ctes;
  index += 1;
  if (tokens[index]?.lower === "recursive") index += 1;
  while (index < tokens.length) {
    const nameToken = tokens[index];
    if (!nameToken || nameToken.kind !== "word" || nameToken.depth !== 0) break;
    const name = nameToken.value;
    index += 1;
    let explicitColumns: string[] = [];
    if (tokens[index]?.value === "(") {
      const close = matchingClose(tokens, index);
      if (close < 0) break;
      explicitColumns = tokens.slice(index + 1, close).filter((token) => token.kind === "word" && token.depth === 1).map((token) => token.value);
      index = close + 1;
    }
    if (tokens[index]?.lower !== "as" || tokens[index + 1]?.value !== "(") break;
    const open = index + 1;
    const close = matchingClose(tokens, open);
    if (close < 0) break;
    const columns = explicitColumns.length > 0
      ? explicitColumns
      : projectedColumns(tokens, open + 1, close, 1, ctes, suggestions, defaultCatalog);
    ctes.set(name.toLocaleLowerCase(), { kind: "cte", name, alias: name, columns });
    index = close + 1;
    if (tokens[index]?.value !== ",") break;
    index += 1;
  }
  return ctes;
}

function parseSources(
  tokens: SqlToken[], start: number, end: number, depth: number,
  ctes: Map<string, CompletionSource>, suggestions: Suggestion[], defaultCatalog: string
): CompletionSource[] {
  const sources: CompletionSource[] = [];
  let expectSource = false;
  for (let index = start + 1; index < end; index += 1) {
    const token = tokens[index];
    if (token.depth !== depth) continue;
    if (token.lower === "from" || token.lower === "join" || token.value === "," && expectSource === false && sources.length > 0) {
      expectSource = true;
      continue;
    }
    if (!expectSource) continue;
    if (sourceTerminators.has(token.lower)) { expectSource = token.lower === "join"; continue; }
    if (token.value === "(") {
      const close = matchingClose(tokens, index);
      if (close < 0 || close >= end) break;
      const aliasResult = readAlias(tokens, close + 1, depth);
      const alias = aliasResult.alias || "derived";
      const columns = projectedColumns(tokens, index + 1, close, depth + 1, ctes, suggestions, defaultCatalog);
      sources.push({ kind: "derived", name: alias, alias, columns });
      index = aliasResult.nextIndex - 1;
      expectSource = false;
      continue;
    }
    if (token.kind !== "word") continue;
    const names = [token.value];
    let cursor = index + 1;
    while (tokens[cursor]?.value === "." && tokens[cursor + 1]?.kind === "word") {
      names.push(tokens[cursor + 1].value);
      cursor += 2;
    }
    const name = names[names.length - 1];
    const catalog = names.length > 1 ? names[names.length - 2] : undefined;
    const aliasResult = readAlias(tokens, cursor, depth);
    const alias = aliasResult.alias || name;
    const cte = names.length === 1 ? ctes.get(name.toLocaleLowerCase()) : undefined;
    sources.push(cte ? { ...cte, alias } : { kind: "physical", name, alias, catalog });
    index = aliasResult.nextIndex - 1;
    expectSource = false;
  }
  return sources;
}

function readAlias(tokens: SqlToken[], start: number, depth: number): { alias?: string; nextIndex: number } {
  let index = start;
  if (tokens[index]?.lower === "as" && tokens[index]?.depth === depth) index += 1;
  const token = tokens[index];
  if (token?.kind === "word" && token.depth === depth && !aliasTerminators.has(token.lower)) {
    return { alias: token.value, nextIndex: index + 1 };
  }
  return { nextIndex: start };
}

function projectedColumns(
  tokens: SqlToken[], start: number, end: number, depth: number,
  ctes: Map<string, CompletionSource>, suggestions: Suggestion[], defaultCatalog: string
): string[] {
  const select = tokens.findIndex((token, index) => index >= start && index < end && token.depth === depth && token.lower === "select");
  if (select < 0) return [];
  let from = end;
  for (let index = select + 1; index < end; index += 1) {
    if (tokens[index].depth === depth && tokens[index].lower === "from") { from = index; break; }
  }
  const segments: SqlToken[][] = [];
  let segment: SqlToken[] = [];
  for (let index = select + 1; index < from; index += 1) {
    const token = tokens[index];
    if (token.value === "," && token.depth === depth) { if (segment.length) segments.push(segment); segment = []; }
    else segment.push(token);
  }
  if (segment.length) segments.push(segment);
  const sources = from < end ? parseSources(tokens, select, end, depth, ctes, suggestions, defaultCatalog) : [];
  const columns: string[] = [];
  segments.forEach((part) => {
    const atDepth = part.filter((token) => token.depth === depth);
    const asIndex = atDepth.findIndex((token) => token.lower === "as");
    if (asIndex >= 0 && atDepth[asIndex + 1]?.kind === "word") { columns.push(atDepth[asIndex + 1].value); return; }
    const last = atDepth[atDepth.length - 1];
    if (last?.kind === "word" && atDepth.length > 1 && atDepth[atDepth.length - 2]?.value !== ".") {
      columns.push(last.value);
      return;
    }
    if (last?.value === "*" && atDepth.length >= 2) {
      const qualifierToken = atDepth.length >= 3 && atDepth[atDepth.length - 2]?.value === "."
        ? atDepth[atDepth.length - 3]
        : undefined;
      const selected = qualifierToken?.kind === "word"
        ? sources.filter((item) => item.alias.toLocaleLowerCase() === qualifierToken.value.toLocaleLowerCase())
        : sources;
      selected.forEach((source) => columns.push(...suggestionsForSource(source, suggestions, defaultCatalog).map((item) => item.label)));
      return;
    }
    if (last?.kind === "word") columns.push(last.value);
  });
  return uniqueNames(columns);
}

function matchingClose(tokens: SqlToken[], openIndex: number): number {
  if (tokens[openIndex]?.value !== "(") return -1;
  let level = 0;
  for (let index = openIndex; index < tokens.length; index += 1) {
    if (tokens[index].value === "(") level += 1;
    else if (tokens[index].value === ")") {
      level -= 1;
      if (level === 0) return index;
    }
  }
  return -1;
}

function suggestionsForSource(source: CompletionSource, suggestions: Suggestion[], defaultCatalog: string): Suggestion[] {
  if (source.kind !== "physical") {
    return uniqueNames(source.columns ?? []).map((column) => ({
      id: `${source.kind}:${source.alias.toLocaleLowerCase()}:${column.toLocaleLowerCase()}`,
      label: column,
      insertText: quoteIdentifier(column),
      detail: `${source.kind === "cte" ? "CTE" : "派生表"} ${source.alias}`,
      kind: "column" as const,
      objectName: source.name
    }));
  }
  return physicalColumns(suggestions, source.name, source.catalog || defaultCatalog || undefined);
}

function physicalColumns(suggestions: Suggestion[], table: string, catalog?: string): Suggestion[] {
  const tableName = table.toLocaleLowerCase();
  const catalogName = catalog?.toLocaleLowerCase();
  const matches = suggestions.filter((item) => item.kind === "column"
    && item.objectName?.toLocaleLowerCase() === tableName
    && (!catalogName || item.catalog?.toLocaleLowerCase() === catalogName));
  const seen = new Set<string>();
  return matches.filter((item) => {
    const name = catalogName
      ? item.label.toLocaleLowerCase()
      : `${item.catalog?.toLocaleLowerCase() ?? ""}:${item.label.toLocaleLowerCase()}`;
    if (seen.has(name)) return false;
    seen.add(name);
    return true;
  });
}

function uniqueNames(values: string[]): string[] {
  const seen = new Set<string>();
  return values.filter((value) => {
    const key = value.toLocaleLowerCase();
    if (!value || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function quoteIdentifier(value: string): string {
  return `\`${value.replace(/`/g, "``")}\``;
}
