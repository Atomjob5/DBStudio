import { describe, expect, it } from "vitest";
import { completionDialect } from "./sqlDialect";
import { activeParenthesisDepth, lexStatementAt } from "./sqlLexer";

describe("SQL completion lexer", () => {
  it("selects only the statement containing the cursor", () => {
    const sql = "select 1; select a.* from orders a; select 3";
    const cursor = sql.indexOf("a.*") + 2;
    const statement = lexStatementAt(sql, cursor, completionDialect("mysql"));
    expect(sql.slice(statement.start, statement.end).trim()).toBe("select a.* from orders a");
    expect(statement.beforeTokens.map((token) => token.lower)).toEqual(["select", "a", "."]);
    expect(statement.tokens.map((token) => token.lower)).toContain("orders");
  });

  it.each([
    ["select ';' as value; select a.| from orders a", "mysql"],
    ["select \"semi;colon\" from dual; select a.| from \"ORD;ERS\" a", "oracle"],
    ["select 1 /* ; from fake */; select a.| from orders a", "mysql"],
    ["select 1 -- ; from fake\n; select a.| from orders a", "mysql"],
    ["select concat('a;','b') from orders; select a.| from orders a", "mysql"]
  ])("ignores semicolons inside lexical or nested structures: %s", (marked, providerId) => {
    const cursor = marked.indexOf("|");
    const sql = marked.replace("|", "");
    const statement = lexStatementAt(sql, cursor, completionDialect(providerId));
    expect(statement.beforeTokens.at(-1)?.value).toBe(".");
    expect(statement.tokens.some((token) => token.lower === "from")).toBe(true);
    expect(statement.tokens.some((token) => token.lower === "dual")).toBe(false);
  });

  it("handles MySQL hash comments but treats hash as an Oracle identifier character", () => {
    const mysql = lexStatementAt("select 1 # from fake\n from orders", 36, completionDialect("mysql"));
    expect(mysql.tokens.map((token) => token.lower)).not.toContain("fake");
    const oracle = lexStatementAt("select #name from orders", 24, completionDialect("oracle"));
    expect(oracle.tokens.map((token) => token.value)).toContain("#name");
  });

  it("preserves quoted identifier contents for each dialect", () => {
    const mysql = lexStatementAt("select `odd.name` from `order``items`", 39, completionDialect("mysql"));
    expect(mysql.tokens.filter((token) => token.quoted).map((token) => token.value))
      .toEqual(["odd.name", "order`items"]);
    const oracle = lexStatementAt('select "Odd.Name" from "Order""Items"', 37, completionDialect("oracle"));
    expect(oracle.tokens.filter((token) => token.quoted).map((token) => token.value))
      .toEqual(["Odd.Name", 'Order"Items']);
  });

  it("handles doubled quotes and MySQL backslash escapes inside strings", () => {
    const mysql = lexStatementAt("select 'it\\'s;ok', o.id from orders o", 41, completionDialect("mysql"));
    expect(mysql.tokens.map((token) => token.lower)).toContain("orders");
    const oracle = lexStatementAt("select 'it''s;ok', o.id from orders o", 41, completionDialect("oracle"));
    expect(oracle.tokens.map((token) => token.lower)).toContain("orders");
  });

  it("tolerates unfinished strings, comments, and parentheses", () => {
    expect(() => lexStatementAt("select 'unfinished", 18, completionDialect("mysql"))).not.toThrow();
    expect(() => lexStatementAt("select /* unfinished", 20, completionDialect("mysql"))).not.toThrow();
    const parenthesized = lexStatementAt("select (select 1", 16, completionDialect("mysql"));
    expect(activeParenthesisDepth(parenthesized.beforeTokens)).toBe(1);
  });

  it("uses UTF-16 offsets consistently around supplementary Unicode characters", () => {
    const sql = "select '😀', a.* from orders a";
    const cursor = sql.indexOf("a.*") + 2;
    expect(cursor).toBe("select '😀', a.".length);
    const statement = lexStatementAt(sql, cursor, completionDialect("mysql"));
    expect(statement.beforeTokens.at(-1)?.end).toBe(cursor);
  });
});
