import { describe, expect, it } from "vitest";
import { createCompletionIndex, upsertCompletionObjects, upsertCompletionSynonyms } from "../sqlCompletion";
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
    expect(result.diagnostics.find((value) => value.code === "SQL_UNKNOWN_OBJECT")?.message)
      .toContain("当前已加载");
    expect(result.quickFixes.filter((value) => value.diagnosticCode === "SQL_UNKNOWN_OBJECT").length)
      .toBeLessThanOrEqual(3);

    const unavailable = createCompletionIndex("", []);
    expect(resolveLocalSqlDiagnostics(unavailable, "mysql", "select * from ord").diagnostics).toEqual([]);
  });

  it.each(["oracle", "oceanbase-oracle", "mysql"])(
    "accepts native relations without using a broad prefix whitelist for %s", (providerId) => {
      const index = providerId === "mysql" ? mysqlIndex : oracleIndex;
      expect(resolveLocalSqlDiagnostics(index, providerId, "select * from dual").diagnostics).toEqual([]);
      if (providerId !== "mysql") {
        expect(resolveLocalSqlDiagnostics(index, providerId, "select * from all_tables").diagnostics).toEqual([]);
        expect(resolveLocalSqlDiagnostics(index, providerId, "select * from CBSAC.ALL_TABLES").diagnostics)
          .toContainEqual(expect.objectContaining({ code: "SQL_UNKNOWN_OBJECT" }));
        expect(resolveLocalSqlDiagnostics(index, providerId, "select * from all_tablse").diagnostics)
          .toContainEqual(expect.objectContaining({ code: "SQL_UNKNOWN_OBJECT" }));
        expect(resolveLocalSqlDiagnostics(index, providerId, 'select * from "ALL_TABLES"').diagnostics).toEqual([]);
        expect(resolveLocalSqlDiagnostics(index, providerId, 'select * from "dual"').diagnostics)
          .toContainEqual(expect.objectContaining({ code: "SQL_UNKNOWN_OBJECT" }));
      }
    });

  it("does not apply Oracle native relation compatibility to other dialects", () => {
    expect(resolveLocalSqlDiagnostics(oracleIndex, "generic", "select * from all_tables").diagnostics)
      .toContainEqual(expect.objectContaining({ code: "SQL_UNKNOWN_OBJECT" }));
  });

  it("respects Oracle quoted object and schema case", () => {
    expect(resolveLocalSqlDiagnostics(oracleIndex, "oracle", 'select * from "ORDERS"').diagnostics)
      .toEqual([]);
    expect(resolveLocalSqlDiagnostics(oracleIndex, "oracle", 'select * from "orders"').diagnostics)
      .toContainEqual(expect.objectContaining({ code: "SQL_UNKNOWN_OBJECT" }));
    expect(resolveLocalSqlDiagnostics(oracleIndex, "oracle", 'select * from "cbsac".ORDERS').diagnostics)
      .toEqual([]);
    expect(resolveLocalSqlDiagnostics(oracleIndex, "oracle", 'select c."ID" from CBSAC.CUSTOMERS c').diagnostics)
      .toEqual([]);
    expect(resolveLocalSqlDiagnostics(oracleIndex, "oracle", 'select c."id" from CBSAC.CUSTOMERS c').diagnostics)
      .toContainEqual(expect.objectContaining({ code: "SQL_UNKNOWN_QUALIFIED_COLUMN" }));
  });

  it("does not infer object absence from a partial metadata stream", () => {
    const index = createCompletionIndex("schema:APP", [
      { key: "schema:APP", catalog: "", schema: "APP", label: "APP" }
    ], { coverage: { objects: "partial", columns: "partial", synonyms: "partial" } });
    expect(resolveLocalSqlDiagnostics(index, "oracle", "select * from missing_table").diagnostics).toEqual([]);
  });

  it("does not infer missing fields from comment-only metadata", () => {
    const index = createCompletionIndex("schema:APP", [
      { key: "schema:APP", catalog: "", schema: "APP", label: "APP" }
    ], { coverage: { objects: "complete", columns: "partial", synonyms: "complete" } });
    upsertCompletionObjects(index, [{ namespaceKey: "schema:APP", catalog: "", schema: "APP",
      name: "ORDERS", kind: "table", remarks: "" }]);
    // A partial column list can contain real comments while still omitting a valid column.
    expect(resolveLocalSqlDiagnostics(index, "oracle", "select o.maybe_valid from APP.ORDERS o").diagnostics)
      .toEqual([]);
  });

  it("resolves private/public synonyms and suppresses unresolved synonym targets", () => {
    const index = createCompletionIndex("schema:APP", [
      { key: "schema:APP", catalog: "", schema: "APP", label: "APP" },
      { key: "schema:BASE", catalog: "", schema: "BASE", label: "BASE" }
    ]);
    upsertCompletionObjects(index, [{ namespaceKey: "schema:BASE", catalog: "", schema: "BASE",
      name: "ORDERS", kind: "table", remarks: "" }]);
    upsertCompletionSynonyms(index, [{ namespaceKey: "schema:APP", name: "ORDERS_ALIAS",
      targetNamespaceKey: "schema:BASE", targetSchema: "BASE", targetName: "ORDERS" }]);
    expect(resolveLocalSqlDiagnostics(index, "oracle", "select * from orders_alias").diagnostics).toEqual([]);
    expect(resolveLocalSqlDiagnostics(index, "oracle", "select * from APP.ORDERS_ALIAS").diagnostics).toEqual([]);

    upsertCompletionSynonyms(index, [{ namespaceKey: "public", name: "REMOTE_ORDERS",
      targetSchema: "BASE", targetName: "ORDERS", databaseLink: "REMOTE" , isPublic: true }]);
    expect(resolveLocalSqlDiagnostics(index, "oracle", "select * from remote_orders").diagnostics).toEqual([]);

    upsertCompletionSynonyms(index, [
      { namespaceKey: "schema:APP", name: "LOOP_A", targetSchema: "APP", targetName: "LOOP_B" },
      { namespaceKey: "schema:APP", name: "LOOP_B", targetSchema: "APP", targetName: "LOOP_A" }
    ]);
    expect(resolveLocalSqlDiagnostics(index, "oracle", "select * from loop_a").diagnostics).toEqual([]);
    expect(resolveLocalSqlDiagnostics(index, "oracle", "select * from missing_table@REMOTE_DB").diagnostics)
      .toEqual([]);
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

  it("does not flag columns merged by USING or NATURAL JOIN", () => {
    for (const sql of [
      "select id from orders o join customers c using (id)",
      "select id from orders o natural join customers c"
    ]) {
      expect(resolveLocalSqlDiagnostics(mysqlIndex, "mysql", sql).diagnostics
        .some((value) => value.code === "SQL_AMBIGUOUS_COLUMN")).toBe(false);
    }
    const threeWay = resolveLocalSqlDiagnostics(mysqlIndex, "mysql",
      "select id from orders o join customers c using (id) join customers c2 on c2.id = c.id");
    expect(threeWay.diagnostics
      .some((value) => value.code === "SQL_AMBIGUOUS_COLUMN")).toBe(true);
  });

  it("keeps pseudo columns, sequence attributes and table functions opaque", () => {
    for (const sql of [
      "select rownum from dual",
      "select s.nextval from dual",
      "select o.rowid from CBSAC.ORDERS o",
      "select * from table(get_orders()) o"
    ]) {
      expect(resolveLocalSqlDiagnostics(oracleIndex, "oracle", sql).diagnostics).toEqual([]);
    }
  });

  it("does not misdiagnose CTEs or reliably parsed derived tables", () => {
    for (const sql of [
      "with c as (select id from customers) select c.id from c",
      "select d.id from (select id from orders) d",
      "select d.id from (with x as (select id from orders) select id from x union all select id from x) d",
      "select d.id from (with recursive x(id) as (select id from orders union all select id from x) select id from x) d",
      "select d.id from orders o join lateral (select id from customers) d on d.id = o.id",
      "select a.id, b.id from (with x as (select id from orders) select id from x) a "
        + "join (with y as (select id from customers) select id from y) b on a.id = b.id",
    ]) expect(resolveLocalSqlDiagnostics(mysqlIndex, "mysql", sql).diagnostics).toEqual([]);
  });

  it("keeps UNION branches in separate query scopes", () => {
    const sql = "select id from orders o union all select id from customers c";
    expect(resolveLocalSqlDiagnostics(mysqlIndex, "mysql", sql).diagnostics
      .some((value) => value.code === "SQL_AMBIGUOUS_COLUMN")).toBe(false);
  });

  it("does not let a USING clause in one UNION branch hide ambiguity in another", () => {
    const sql = "select id from orders o join customers c on o.id = c.id "
      + "union all select id from orders o2 join customers c2 using (id)";
    const ambiguous = resolveLocalSqlDiagnostics(mysqlIndex, "mysql", sql).diagnostics
      .filter((value) => value.code === "SQL_AMBIGUOUS_COLUMN");
    expect(ambiguous).toHaveLength(1);
  });

  it("does not treat recursive CTE self references as physical objects", () => {
    const sql = "with recursive numbers(id) as (select 1 union all select id from numbers) "
      + "select id from numbers";
    expect(resolveLocalSqlDiagnostics(mysqlIndex, "mysql", sql).diagnostics
      .some((value) => value.code === "SQL_UNKNOWN_OBJECT")).toBe(false);
  });

  it("does not leak aliases between same-depth subqueries", () => {
    const sql = "select (select x.total from orders x), (select x.total) from customers x";
    expect(resolveLocalSqlDiagnostics(mysqlIndex, "mysql", sql).diagnostics)
      .toContainEqual(expect.objectContaining({ code: "SQL_UNKNOWN_QUALIFIED_COLUMN" }));
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
