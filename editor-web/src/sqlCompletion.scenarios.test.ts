import { describe, expect, it } from "vitest";
import { resolveCompletion } from "./sqlCompletion";
import { candidatesOfKind, columnLabels, completeAt, mysqlIndex, objectLabels,
  oracleIndex, sqlAtCursor } from "./completion/testFixtures";

describe("SQL completion object contexts", () => {
  it("uses short labels for default objects and qualified labels for other namespaces", () => {
    const items = completeAt("select * from |").items;
    expect(items.find((item) => item.documentationPath === "sales.orders"))
      .toMatchObject({ displayLabel: "orders", insertText: "orders", kind: "table" });
    expect(items.find((item) => item.documentationPath === "sales.customers"))
      .toMatchObject({ displayLabel: "customers", insertText: "customers", kind: "view" });
    expect(items.find((item) => item.documentationPath === "archive.orders_history"))
      .toMatchObject({ displayLabel: "archive.orders_history", insertText: "archive.orders_history" });
  });

  it.each([
    "select * from sales.ord|",
    "select * from orders o join ord|",
    "select * from orders o, ord|",
    "update ord|",
    "insert into ord|",
    "delete from ord|",
    "merge into ord|",
    "merge into orders o using ord|"
  ])("filters table candidates in %s", (sql) => {
    expect(objectLabels(sql)).toContain("orders");
    expect(objectLabels(sql).every((label) => label.toLocaleLowerCase().includes("ord"))).toBe(true);
  });

  it("restricts qualified object completion to the selected namespace", () => {
    expect(objectLabels("select * from archive.|")).toEqual(["orders", "orders_history"]);
    expect(objectLabels("select * from empty.|")).toEqual([]);
    expect(objectLabels("select * from missing.|")).toEqual([]);
  });

  it("matches names without changing their database spelling", () => {
    expect(objectLabels("select * from sel|")).toContain("SELECT");
    expect(objectLabels("select * from 用户|")).toContain("用户表");
    expect(completeAt("select * from sales.|").items.every((item) => !/["`]/.test(item.insertText))).toBe(true);
  });
});

describe("SQL completion column contexts", () => {
  it.each([
    "select o.| from orders o",
    "select * from orders o where o.|",
    "select * from orders o join customers c on o.| = c.id",
    "select customer_id from orders o group by o.|",
    "select customer_id, count(*) from orders o group by customer_id having o.|",
    "select * from orders o order by o.|",
    "update orders o set o.|",
    "update orders o set total = 1 returning o.|",
    "delete from orders o where o.|",
    "merge into orders o using customers c on o.| = c.id"
  ])("completes the orders source in %s", (sql) => {
    expect(columnLabels(sql)).toEqual(["created_at", "customer_id", "id", "total"]);
  });

  it("completes unqualified fields for a single source", () => {
    expect(columnLabels("select | from orders")).toEqual(["created_at", "customer_id", "id", "total"]);
    expect(columnLabels("select * from orders where |")).toEqual(["created_at", "customer_id", "id", "total"]);
  });

  it("supports alias, table, and schema.table qualifiers", () => {
    expect(columnLabels("select * from orders alias_name where alias_name.|")).toContain("customer_id");
    expect(columnLabels("select * from orders where orders.|")).toContain("customer_id");
    expect(columnLabels("select * from sales.orders where sales.orders.|")).toContain("customer_id");
  });

  it("inserts qualifiers for every source in an unqualified multi-table context", () => {
    const labels = columnLabels("select * from orders o join customers c on o.customer_id = c.id where |");
    expect(labels).toEqual(expect.arrayContaining([
      "o.created_at", "o.customer_id", "o.id", "o.total", "c.id", "c.name", "c.status"
    ]));
    expect(labels.filter((label) => label.endsWith(".id"))).toEqual(expect.arrayContaining(["o.id", "c.id"]));
  });

  it("filters the active identifier and inserts only the remaining contextual name", () => {
    const items = candidatesOfKind(completeAt("select o.cus| from orders o").items, "column");
    expect(items).toHaveLength(1);
    expect(items[0]).toMatchObject({ displayLabel: "customer_id", insertText: "customer_id",
      documentationPath: "sales.orders.customer_id", typeName: "bigint", remarks: "客户编号" });
  });

  it("completes target columns inside INSERT column lists", () => {
    expect(columnLabels("insert into orders (|")).toEqual(["created_at", "customer_id", "id", "total"]);
  });

  it("distinguishes INSERT/MERGE source aliases from their target tables", () => {
    expect(columnLabels("insert into orders (id) select c.| from customers c"))
      .toEqual(["id", "name", "status"]);
    expect(columnLabels("merge into orders o using customers c on c.| = o.customer_id"))
      .toEqual(["id", "name", "status"]);
  });

  it("does not scan unrelated fields for unknown semantic targets", () => {
    expect(columnLabels("select * from orders o where missing.|")).toEqual([]);
    expect(columnLabels("select * from missing where |")).toEqual([]);
    expect(columnLabels("select * from missing_schema.orders o where o.|")).toEqual([]);
  });

  it("never adds identifier quotes for Oracle candidates", () => {
    const items = candidatesOfKind(completeAt("select c.| from CBSAC.CUSTOMERS c", {
      index: oracleIndex, providerId: "oracle" }).items, "column");
    expect(items.map((item) => item.displayLabel)).toEqual(["CUSTOMER_NAME", "ID", "STATUS"]);
    expect(items.every((item) => !/["`]/.test(item.insertText))).toBe(true);
  });

  it("matches mixed-case SQL and identifiers case-insensitively", () => {
    expect(columnLabels("SeLeCt O.| FrOm OrDeRs O WhErE O.ID > 0"))
      .toEqual(["created_at", "customer_id", "id", "total"]);
  });
});

describe("SQL completion cursor and scope handling", () => {
  it("uses a source declared after the cursor", () => {
    expect(columnLabels("select a.|* from CBSAC.CUSTOMERS a where a.ID <= 20", {
      index: oracleIndex, providerId: "oracle" })).toEqual(["CUSTOMER_NAME", "ID", "STATUS"]);
    expect(columnLabels("select a.CUS| from CBSAC.CUSTOMERS a where a.ID <= 20", {
      index: oracleIndex, providerId: "oracle" })).toEqual(["CUSTOMER_NAME"]);
  });

  it("uses the nearest alias and keeps visible correlated outer sources", () => {
    expect(columnLabels("select * from orders a where exists (select 1 from customers a where a.|)"))
      .toEqual(["id", "name", "status"]);
    expect(columnLabels("select (select a.| from customers c) from orders a"))
      .toEqual(["created_at", "customer_id", "id", "total"]);
  });

  it("does not leak aliases from sibling subqueries", () => {
    expect(columnLabels("select (select x.| from orders a), (select 1 from customers x) from orders z"))
      .toEqual([]);
  });

  it.each(["union", "minus", "except", "intersect"])("isolates the current %s branch", (operator) => {
    expect(columnLabels(`select a.| from orders a ${operator} select a.id from customers a`))
      .toEqual(["created_at", "customer_id", "id", "total"]);
    expect(columnLabels(`select a.id from orders a ${operator} select a.| from customers a`))
      .toEqual(["id", "name", "status"]);
  });

  it("isolates the current statement despite strings and comments containing semicolons", () => {
    expect(columnLabels("select ';' from customers x; select o.| from orders o; select 3"))
      .toEqual(["created_at", "customer_id", "id", "total"]);
    expect(columnLabels("select 1 /* ; from customers x */; select o.| from orders o"))
      .toEqual(["created_at", "customer_id", "id", "total"]);
  });

  it("derives CTE and derived-table outputs", () => {
    expect(columnLabels("with recent(order_id, amount) as (select id, total from orders) select r.| from recent r"))
      .toEqual(["amount", "order_id"]);
    expect(columnLabels("with recent as (select id as order_id, total from orders) select r.| from recent r"))
      .toEqual(["order_id", "total"]);
    expect(columnLabels("select d.| from (select o.id as order_id, o.total from orders o) d"))
      .toEqual(["order_id", "total"]);
    expect(columnLabels("select d.| from (select o.* from orders o) d"))
      .toEqual(["created_at", "customer_id", "id", "total"]);
  });

  it("tolerates incomplete trailing syntax without throwing or scanning all fields", () => {
    expect(() => completeAt("select o.| from orders o where (")).not.toThrow();
    expect(columnLabels("select missing.| from orders o where (")).toEqual([]);
    expect(() => completeAt("select 'unfinished|")).not.toThrow();
    expect(() => completeAt("select /* unfinished|")).not.toThrow();
  });
});

describe("SQL completion ranking and safe fallback", () => {
  it("prioritizes the default namespace and exact names", () => {
    const items = completeAt("select * from ord|").items.filter((item) => item.kind === "table");
    expect(items[0]?.documentationPath).toBe("sales.orders");
    const exact = completeAt("select * from orders|").items.filter((item) => item.kind === "table");
    expect(exact[0]?.documentationPath).toBe("sales.orders");
    expect(exact.findIndex((item) => item.documentationPath === "archive.orders"))
      .toBeLessThan(exact.findIndex((item) => item.documentationPath === "archive.orders_history"));
  });

  it("clamps limits and marks truncated results incomplete", () => {
    const low = completeAt("select * from orders where |", { limit: 1 });
    expect(low.items).toHaveLength(10);
    expect(low.incomplete).toBe(true);
    const high = completeAt("select * from orders where |", { limit: 5000 });
    expect(high.items.length).toBeLessThanOrEqual(1000);
  });

  it("returns safe keywords without a metadata index", () => {
    const cursor = sqlAtCursor("select |");
    const result = resolveCompletion(undefined, { providerId: "mysql", ...cursor, limit: 100 });
    expect(result.items.length).toBeGreaterThan(0);
    expect(result.items.every((item) => item.kind === "keyword")).toBe(true);
  });

  it.todo("completes SELECT projection aliases in ORDER BY");
  it.todo("completes common columns inside JOIN USING parentheses");
  it.todo("infers recursive CTE self-reference fields without an explicit column list");
  it.todo("models LATERAL, table functions, Oracle database links, and synonyms");
  it.todo("models PL/SQL variables, records, cursors, ROWTYPE, and package members");
});
