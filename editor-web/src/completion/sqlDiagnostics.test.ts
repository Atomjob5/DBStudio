import { describe, expect, it } from "vitest";
import { createCompletionIndex, upsertCompletionObjects } from "../sqlCompletion";
import { mysqlIndex, oracleIndex } from "./testFixtures";
import { resolveLocalSqlDiagnostics } from "./sqlDiagnostics";

describe("local SQL diagnostics", () => {
  it.each(["mysql", "oracle", "oceanbase-oracle"])("reports structural errors for %s", (providerId) => {
    const result = resolveLocalSqlDiagnostics(mysqlIndex, providerId, "select (1, '未闭合😀");
    expect(result.diagnostics.map((value) => value.code)).toEqual(expect.arrayContaining([
      "SQL_UNCLOSED_STRING", "SQL_UNMATCHED_PARENTHESIS"
    ]));
    expect(result.quickFixes.some((value) => value.edits[0]?.text === "'")).toBe(true);
    expect(result.quickFixes.some((value) => value.edits[0]?.text === ")")).toBe(true);
  });

  it.each(["'", "\"", "`"])('recognizes a lone trailing %s as unclosed', (quote) => {
    const providerId = quote === "`" ? "mysql" : "oracle";
    const result = resolveLocalSqlDiagnostics(undefined, providerId, `select ${quote}`);
    expect(result.diagnostics).toContainEqual(expect.objectContaining({ code: "SQL_UNCLOSED_STRING" }));
    expect(result.quickFixes).toContainEqual(expect.objectContaining({
      diagnosticCode: "SQL_UNCLOSED_STRING",
      edits: [expect.objectContaining({ text: quote })],
    }));
  });

  it("understands MySQL comments/backticks and Oracle alternative quotes", () => {
    expect(resolveLocalSqlDiagnostics(mysqlIndex, "mysql",
      "select `id`, '# still text' from orders # ( ignored\nwhere id = 1").diagnostics).toEqual([]);
    expect(resolveLocalSqlDiagnostics(oracleIndex, "oracle",
      "select q'[it's; text with ( ]' from CBSAC.CUSTOMERS").diagnostics).toEqual([]);
    expect(resolveLocalSqlDiagnostics(oracleIndex, "oceanbase-oracle",
      "select /* unfinished").diagnostics[0]?.code).toBe("SQL_UNCLOSED_COMMENT");
  });

  it.each([
    ["mysql", mysqlIndex, "select o.id from orders o"],
    ["oracle", oracleIndex, "select c.ID from CBSAC.CUSTOMERS c"],
    ["oceanbase-oracle", oracleIndex, "select c.ID from CBSAC.CUSTOMERS c"],
  ] as const)("does not report valid %s metadata", (providerId, index, sql) => {
    expect(resolveLocalSqlDiagnostics(index, providerId, sql).diagnostics).toEqual([]);
  });

  it("reports unknown objects only in a loaded namespace and offers at most three replacements", () => {
    const result = resolveLocalSqlDiagnostics(mysqlIndex, "mysql", "select * from ord");
    expect(result.diagnostics).toContainEqual(expect.objectContaining({ code: "SQL_UNKNOWN_OBJECT" }));
    expect(result.quickFixes.filter((value) => value.diagnosticCode === "SQL_UNKNOWN_OBJECT").length)
      .toBeLessThanOrEqual(3);

    const unavailable = createCompletionIndex("", []);
    expect(resolveLocalSqlDiagnostics(unavailable, "mysql", "select * from ord").diagnostics).toEqual([]);
  });

  it("marks a unique same-namespace replacement as preferred", () => {
    const result = resolveLocalSqlDiagnostics(mysqlIndex, "mysql", "select * from archive.orders_h");
    const fixes = result.quickFixes.filter((value) => value.diagnosticCode === "SQL_UNKNOWN_OBJECT");
    expect(fixes).toEqual([expect.objectContaining({ title: "替换为 orders_history", isPreferred: true })]);

    const column = resolveLocalSqlDiagnostics(mysqlIndex, "mysql", "select o.created from orders o");
    expect(column.quickFixes.filter((value) => value.diagnosticCode === "SQL_UNKNOWN_QUALIFIED_COLUMN"))
      .toEqual([expect.objectContaining({ title: "替换为 o.created_at", isPreferred: true })]);
  });

  it.each(["mysql", "oracle", "oceanbase-oracle"])(
    "reports unknown qualified and ambiguous columns for %s", (providerId) => {
      const index = providerId === "mysql" ? mysqlIndex : oracleIndex;
      const sql = providerId === "mysql"
        ? "select o.created, id from orders o join customers c on o.customer_id = c.id"
        : "select o.CUSTOM, ID from CBSAC.ORDERS o join CBSAC.CUSTOMERS c on o.CUSTOMER_ID = c.ID";
      const result = resolveLocalSqlDiagnostics(index, providerId, sql);
      expect(result.diagnostics.map((value) => value.code)).toEqual(expect.arrayContaining([
        "SQL_UNKNOWN_QUALIFIED_COLUMN", "SQL_AMBIGUOUS_COLUMN"
      ]));
      expect(result.quickFixes.filter((value) => value.diagnosticCode === "SQL_AMBIGUOUS_COLUMN"))
        .toHaveLength(2);
    });

  it("suppresses field diagnostics until a field list is available", () => {
    const index = createCompletionIndex("schema:APP", [
      { key: "schema:APP", catalog: "", schema: "APP", label: "APP" }
    ]);
    upsertCompletionObjects(index, [{ namespaceKey: "schema:APP", catalog: "", schema: "APP",
      name: "ORDERS", kind: "table", remarks: "" }]);
    expect(resolveLocalSqlDiagnostics(index, "oracle", "select o.MISSING from APP.ORDERS o").diagnostics)
      .toEqual([]);
  });

  it("warns only for ordinary joins without conditions", () => {
    const missing = resolveLocalSqlDiagnostics(mysqlIndex, "mysql",
      "select * from orders o join customers c");
    expect(missing.diagnostics)
      .toContainEqual(expect.objectContaining({ code: "SQL_JOIN_CONDITION_MISSING" }));
    expect(missing.quickFixes).toEqual([]);
    for (const sql of [
      "select * from orders o cross join customers c",
      "select * from orders o natural join customers c",
      "with c as (select id from customers) select * from orders o join c",
      "select * from orders o join (select id from customers) c",
    ]) {
      expect(resolveLocalSqlDiagnostics(mysqlIndex, "mysql", sql).diagnostics
        .some((value) => value.code === "SQL_JOIN_CONDITION_MISSING")).toBe(false);
    }
  });

  it("does not misdiagnose CTEs or reliably parsed derived tables", () => {
    for (const sql of [
      "with c as (select id from customers) select c.id from c",
      "select d.id from (select id from orders) d",
    ]) expect(resolveLocalSqlDiagnostics(mysqlIndex, "mysql", sql).diagnostics).toEqual([]);
  });

  it("marks exact UTF-16 offsets and generates safe edits", () => {
    const sql = "select '中文😀';\nselect o.created from orders o";
    const result = resolveLocalSqlDiagnostics(mysqlIndex, "mysql", sql);
    const diagnostic = result.diagnostics.find((value) => value.code === "SQL_UNKNOWN_QUALIFIED_COLUMN");
    expect(diagnostic?.startOffset).toBe(sql.indexOf("created"));
    const fixes = result.quickFixes.filter((value) => value.diagnosticCode === "SQL_UNKNOWN_QUALIFIED_COLUMN");
    expect(fixes.length).toBeLessThanOrEqual(3);
    for (const fix of fixes) expect(fix.edits[0]).toEqual(expect.objectContaining({
      startOffset: sql.indexOf("created"), endOffset: sql.indexOf("created") + "created".length
    }));
  });
});
