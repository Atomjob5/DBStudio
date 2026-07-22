import type { SqlCompletionDialect } from "./sqlDialect";

export interface SqlToken {
  kind: "word" | "number" | "symbol";
  value: string;
  lower: string;
  start: number;
  end: number;
  depth: number;
  quoted: boolean;
}

/** Tokenizes only the current top-level statement while retaining absolute source offsets. */
export function lexCurrentStatement(sql: string, dialect: SqlCompletionDialect): SqlToken[] {
  let tokens: SqlToken[] = [];
  let index = 0;
  let depth = 0;
  while (index < sql.length) {
    const character = sql[index];
    const next = sql[index + 1] ?? "";
    if (/\s/.test(character)) { index += 1; continue; }
    if (character === "-" && next === "-") { index = skipLine(sql, index + 2); continue; }
    if (dialect.hashComments && character === "#") { index = skipLine(sql, index + 1); continue; }
    if (character === "/" && next === "*") { index = skipBlockComment(sql, index + 2); continue; }
    if (character === "'") { index = skipString(sql, index, "'", dialect.backslashEscapes); continue; }
    if (character === "\"" && !dialect.doubleQuoteIdentifiers) {
      index = skipString(sql, index, "\"", dialect.backslashEscapes);
      continue;
    }
    if (character === "\"" && dialect.doubleQuoteIdentifiers) {
      const quoted = readQuotedIdentifier(sql, index, "\"");
      tokens.push(wordToken(quoted.value, index, quoted.end, depth, true));
      index = quoted.end;
      continue;
    }
    if (character === "`" && dialect.backtickIdentifiers) {
      const quoted = readQuotedIdentifier(sql, index, "`");
      tokens.push(wordToken(quoted.value, index, quoted.end, depth, true));
      index = quoted.end;
      continue;
    }
    if (isWordStart(character)) {
      const start = index++;
      while (index < sql.length && isWordPart(sql[index])) index += 1;
      tokens.push(wordToken(sql.slice(start, index), start, index, depth, false));
      continue;
    }
    if (/\d/.test(character)) {
      const start = index++;
      while (index < sql.length && /[\d.eE+-]/.test(sql[index])) index += 1;
      const value = sql.slice(start, index);
      tokens.push({ kind: "number", value, lower: value, start, end: index, depth, quoted: false });
      continue;
    }
    if (character === "(") {
      tokens.push(symbolToken(character, index, depth));
      depth += 1;
      index += 1;
      continue;
    }
    if (character === ")") {
      depth = Math.max(0, depth - 1);
      tokens.push(symbolToken(character, index, depth));
      index += 1;
      continue;
    }
    if (character === ";" && depth === 0) {
      tokens = [];
      index += 1;
      continue;
    }
    tokens.push(symbolToken(character, index, depth));
    index += 1;
  }
  return tokens;
}

export function activeParenthesisDepth(tokens: SqlToken[]): number {
  let depth = 0;
  for (const token of tokens) {
    if (token.value === "(") depth += 1;
    else if (token.value === ")") depth = Math.max(0, depth - 1);
  }
  return depth;
}

function skipLine(sql: string, start: number): number {
  const newline = sql.indexOf("\n", start);
  return newline < 0 ? sql.length : newline + 1;
}

function skipBlockComment(sql: string, start: number): number {
  const end = sql.indexOf("*/", start);
  return end < 0 ? sql.length : end + 2;
}

function skipString(sql: string, start: number, quote: string, backslashEscapes: boolean): number {
  let index = start + 1;
  while (index < sql.length) {
    if (backslashEscapes && sql[index] === "\\") { index += 2; continue; }
    if (sql[index] === quote && sql[index + 1] === quote) { index += 2; continue; }
    if (sql[index] === quote) return index + 1;
    index += 1;
  }
  return sql.length;
}

function readQuotedIdentifier(sql: string, start: number, quote: string): { value: string; end: number } {
  let value = "";
  let index = start + 1;
  while (index < sql.length) {
    if (sql[index] === quote && sql[index + 1] === quote) { value += quote; index += 2; continue; }
    if (sql[index] === quote) return { value, end: index + 1 };
    value += sql[index++];
  }
  return { value, end: sql.length };
}

function isWordStart(value: string): boolean {
  return /[A-Za-z_$#\u0080-\uFFFF]/.test(value);
}

function isWordPart(value: string): boolean {
  return /[\w$#\u0080-\uFFFF]/.test(value);
}

function wordToken(value: string, start: number, end: number, depth: number, quoted: boolean): SqlToken {
  return { kind: "word", value, lower: value.toLocaleLowerCase(), start, end, depth, quoted };
}

function symbolToken(value: string, start: number, depth: number): SqlToken {
  return { kind: "symbol", value, lower: value, start, end: start + 1, depth, quoted: false };
}
