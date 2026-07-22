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

export interface LexedSqlStatement {
  start: number;
  end: number;
  tokens: SqlToken[];
  beforeTokens: SqlToken[];
}

/** Locates and tokenizes the complete statement containing a UTF-16 cursor offset. */
export function lexStatementAt(sql: string, cursorOffset: number,
                               dialect: SqlCompletionDialect): LexedSqlStatement {
  const cursor = Math.max(0, Math.min(sql.length, cursorOffset));
  const bounds = statementBounds(sql, cursor, dialect);
  return {
    ...bounds,
    tokens: lexRange(sql, bounds.start, bounds.end, dialect),
    beforeTokens: lexRange(sql, bounds.start, cursor, dialect)
  };
}

/** Backward-compatible helper for callers that complete only at the end of their input. */
export function lexCurrentStatement(sql: string, dialect: SqlCompletionDialect): SqlToken[] {
  return lexStatementAt(sql, sql.length, dialect).beforeTokens;
}

export function activeParenthesisDepth(tokens: SqlToken[]): number {
  let depth = 0;
  for (const token of tokens) {
    if (token.value === "(") depth += 1;
    else if (token.value === ")") depth = Math.max(0, depth - 1);
  }
  return depth;
}

function statementBounds(sql: string, cursor: number, dialect: SqlCompletionDialect): { start: number; end: number } {
  let start = 0;
  let end = sql.length;
  let depth = 0;
  let index = 0;
  while (index < sql.length) {
    const character = sql[index];
    const next = sql[index + 1] ?? "";
    if (character === "-" && next === "-") { index = skipLine(sql, index + 2, sql.length); continue; }
    if (dialect.hashComments && character === "#") { index = skipLine(sql, index + 1, sql.length); continue; }
    if (character === "/" && next === "*") { index = skipBlockComment(sql, index + 2, sql.length); continue; }
    if (character === "'") { index = skipString(sql, index, "'", dialect.backslashEscapes, sql.length); continue; }
    if (character === "\"" && !dialect.doubleQuoteIdentifiers) {
      index = skipString(sql, index, "\"", dialect.backslashEscapes, sql.length);
      continue;
    }
    if (character === "\"" && dialect.doubleQuoteIdentifiers) {
      index = readQuotedIdentifier(sql, index, "\"", sql.length).end;
      continue;
    }
    if (character === "`" && dialect.backtickIdentifiers) {
      index = readQuotedIdentifier(sql, index, "`", sql.length).end;
      continue;
    }
    if (character === "(") depth += 1;
    else if (character === ")") depth = Math.max(0, depth - 1);
    else if (character === ";" && depth === 0) {
      if (index < cursor) start = index + 1;
      else { end = index; break; }
    }
    index += 1;
  }
  return { start, end };
}

function lexRange(sql: string, start: number, end: number, dialect: SqlCompletionDialect): SqlToken[] {
  const tokens: SqlToken[] = [];
  let index = start;
  let depth = 0;
  while (index < end) {
    const character = sql[index];
    const next = index + 1 < end ? sql[index + 1] : "";
    if (/\s/.test(character)) { index += 1; continue; }
    if (character === "-" && next === "-") { index = skipLine(sql, index + 2, end); continue; }
    if (dialect.hashComments && character === "#") { index = skipLine(sql, index + 1, end); continue; }
    if (character === "/" && next === "*") { index = skipBlockComment(sql, index + 2, end); continue; }
    if (character === "'") { index = skipString(sql, index, "'", dialect.backslashEscapes, end); continue; }
    if (character === "\"" && !dialect.doubleQuoteIdentifiers) {
      index = skipString(sql, index, "\"", dialect.backslashEscapes, end);
      continue;
    }
    if (character === "\"" && dialect.doubleQuoteIdentifiers) {
      const quoted = readQuotedIdentifier(sql, index, "\"", end);
      tokens.push(wordToken(quoted.value, index, quoted.end, depth, true));
      index = quoted.end;
      continue;
    }
    if (character === "`" && dialect.backtickIdentifiers) {
      const quoted = readQuotedIdentifier(sql, index, "`", end);
      tokens.push(wordToken(quoted.value, index, quoted.end, depth, true));
      index = quoted.end;
      continue;
    }
    if (isWordStart(character)) {
      const wordStart = index++;
      while (index < end && isWordPart(sql[index])) index += 1;
      tokens.push(wordToken(sql.slice(wordStart, index), wordStart, index, depth, false));
      continue;
    }
    if (/\d/.test(character)) {
      const numberStart = index++;
      while (index < end && /[\d.eE+-]/.test(sql[index])) index += 1;
      const value = sql.slice(numberStart, index);
      tokens.push({ kind: "number", value, lower: value, start: numberStart, end: index, depth, quoted: false });
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
    tokens.push(symbolToken(character, index, depth));
    index += 1;
  }
  return tokens;
}

function skipLine(sql: string, start: number, limit: number): number {
  const newline = sql.indexOf("\n", start);
  return newline < 0 || newline >= limit ? limit : newline + 1;
}

function skipBlockComment(sql: string, start: number, limit: number): number {
  const end = sql.indexOf("*/", start);
  return end < 0 || end + 2 > limit ? limit : end + 2;
}

function skipString(sql: string, start: number, quote: string, backslashEscapes: boolean, limit: number): number {
  let index = start + 1;
  while (index < limit) {
    if (backslashEscapes && sql[index] === "\\") { index = Math.min(limit, index + 2); continue; }
    if (sql[index] === quote && index + 1 < limit && sql[index + 1] === quote) { index += 2; continue; }
    if (sql[index] === quote) return index + 1;
    index += 1;
  }
  return limit;
}

function readQuotedIdentifier(sql: string, start: number, quote: string,
                              limit: number): { value: string; end: number } {
  let value = "";
  let index = start + 1;
  while (index < limit) {
    if (sql[index] === quote && index + 1 < limit && sql[index + 1] === quote) {
      value += quote;
      index += 2;
      continue;
    }
    if (sql[index] === quote) return { value, end: index + 1 };
    value += sql[index++];
  }
  return { value, end: limit };
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
