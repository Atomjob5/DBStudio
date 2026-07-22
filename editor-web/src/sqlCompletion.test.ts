import { describe, expect, it } from "vitest";
import { buildCompletionIndex, resolveCompletion } from "./sqlCompletion";
import type { CompletionSnapshot } from "./types";

const snapshot: CompletionSnapshot = {
  formatVersion: 1,
  providerId: "mysql",
  sourceProfileId: "profile-1",
  generatedAt: "2026-07-22T00:00:00Z",
  defaultNamespaceKey: "catalog:sales",
  selectedNamespaceKeys: ["catalog:sales", "catalog:archive"],
  namespaces: [
    { key: "catalog:sales", catalog: "sales", schema: "", label: "sales", objects: [
      { name: "orders", kind: "table", remarks: "订单", columns: [
        { name: "id", typeName: "bigint", remarks: "订单编号" },
        { name: "customer_id", typeName: "bigint", remarks: "客户编号" }
      ] },
      { name: "customers", kind: "view", remarks: "客户视图", columns: [
        { name: "id", typeName: "bigint", remarks: "客户编号" },
        { name: "name", typeName: "varchar(100)", remarks: "客户名称" }
      ] },
      { name: "sys_user", kind: "table", remarks: "用户", columns: [
        { name: "user_id", typeName: "bigint", remarks: "用户编号" },
        { name: "user_name", typeName: "varchar(100)", remarks: "用户名称" }
      ] }
    ] },
    { key: "catalog:archive", catalog: "archive", schema: "", label: "archive", objects: [
      { name: "orders_history", kind: "table", remarks: "历史订单", columns: [
        { name: "id", typeName: "bigint", remarks: "历史编号" }
      ] }
    ] }
  ]
};
const index = buildCompletionIndex(snapshot);

describe("handwritten context-aware SQL completion", () => {
  it("completes tables and views after a namespace qualifier", () => {
    expect(metadata("select * from sales.").map((item) => [item.displayLabel, item.kind]))
      .toEqual([["orders", "table"], ["sys_user", "table"], ["customers", "view"]]);
  });

  it("completes columns for a qualified and a default-namespace table", () => {
    expect(columns("select * from sales.orders where ").map((item) => item.displayLabel))
      .toEqual(["customer_id", "id"]);
    expect(columns("select * from orders where ").map((item) => item.displayLabel))
      .toEqual(["customer_id", "id"]);
  });

  it("shows and inserts only the field after an existing qualifier", () => {
    const sitdbIndex = buildCompletionIndex({ ...snapshot, defaultNamespaceKey: "catalog:sitdb",
      selectedNamespaceKeys: ["catalog:sitdb"], namespaces: [{ ...snapshot.namespaces[0],
        key: "catalog:sitdb", catalog: "sitdb", label: "sitdb" }] });
    const values = resolveCompletion(sitdbIndex, { providerId: "mysql",
      sql: "select * from sitdb.sys_user a where a.", prefix: "", limit: 100 })
      .items.filter((item) => item.kind === "column");
    expect(values.map((item) => item.displayLabel)).toEqual(["user_id", "user_name"]);
    expect(values.map((item) => item.insertText)).toEqual(["user_id", "user_name"]);
    expect(values[0]).toMatchObject({ documentationPath: "sitdb.sys_user.user_id",
      remarks: "用户编号", typeName: "bigint" });
  });

  it("qualifies every field when an unqualified context has multiple sources", () => {
    const values = columns("select * from orders a join customers b on a.customer_id=b.id where ");
    expect(values.map((item) => item.displayLabel)).toEqual(expect.arrayContaining([
      "a.customer_id", "a.id", "b.id", "b.name"
    ]));
    expect(values.every((item) => item.insertText === item.displayLabel)).toBe(true);
  });

  it("does not scan all fields for an unknown alias or table", () => {
    expect(columns("select * from orders a where missing.")).toEqual([]);
    expect(columns("select * from missing where ")).toEqual([]);
  });

  it("uses contextual object labels and never inserts identifier quotes", () => {
    const values = metadata("select * from ");
    expect(values.find((item) => item.displayLabel === "orders")?.insertText).toBe("orders");
    expect(values.find((item) => item.displayLabel === "archive.orders_history")?.insertText)
      .toBe("archive.orders_history");
    expect(complete("select * from ").filter((item) => item.kind === "schema").map((item) => item.displayLabel))
      .toEqual(["sales", "archive"]);

    const oracle = buildCompletionIndex({ ...snapshot, providerId: "oracle" });
    const oracleColumns = resolveCompletion(oracle, { providerId: "oracle",
      sql: "select * from orders o where o.", prefix: "", limit: 100 }).items;
    expect(oracleColumns.filter((item) => item.kind === "column").every((item) => !/["`]/.test(item.insertText))).toBe(true);
    expect(resolveCompletion(oracle, { providerId: "oracle",
      sql: "select * from \"sales\".\"orders\" o where o.", prefix: "", limit: 100 }).items
      .filter((item) => item.kind === "column").map((item) => item.displayLabel)).toEqual(["customer_id", "id"]);
  });

  it("uses the nearest nested alias scope", () => {
    const result = columns("select * from orders a where exists (select 1 from customers a where a.");
    expect(result.map((item) => item.displayLabel)).toEqual(["id", "name"]);
  });

  it("derives fields for CTEs and derived tables", () => {
    expect(columns("with recent(order_id) as (select id from orders) select * from recent r where r.")
      .map((item) => item.displayLabel)).toEqual(["order_id"]);
    expect(columns("select * from (select id as order_id, customer_id from orders) r where r.")
      .map((item) => item.displayLabel)).toEqual(["customer_id", "order_id"]);
  });

  it("handles update, insert, delete, and merge field contexts", () => {
    expect(columns("update orders o set o.").map((item) => item.displayLabel)).toEqual(["customer_id", "id"]);
    expect(columns("insert into orders (").map((item) => item.displayLabel)).toEqual(["customer_id", "id"]);
    expect(columns("delete from orders o where o.").map((item) => item.displayLabel)).toEqual(["customer_id", "id"]);
    expect(columns("merge into orders o using customers c on o.").map((item) => item.displayLabel))
      .toEqual(["customer_id", "id"]);
  });

  it("ignores comments and strings while resolving context", () => {
    expect(columns("select * from orders o /* from customers x */ where 'x.y' = 'x.y' and o.")
      .map((item) => item.displayLabel)).toEqual(["customer_id", "id"]);
  });

  it("filters by a partial identifier after a qualifier", () => {
    expect(resolveCompletion(index, { providerId: "mysql", sql: "select * from orders o where o.cus",
      prefix: "cus", limit: 100 }).items.filter((item) => item.kind === "column").map((item) => item.displayLabel))
      .toEqual(["customer_id"]);
  });

  it("limits candidates and marks the result incomplete", () => {
    const result = resolveCompletion(index, { providerId: "mysql", sql: "select * from orders where ", prefix: "", limit: 10 });
    expect(result.items).toHaveLength(10);
    expect(result.incomplete).toBe(true);
  });

  it("keeps a 6000-table / 110000-column snapshot hierarchical and returns only configured candidates", () => {
    const objects = Array.from({ length: 6000 }, (_, tableIndex) => ({
      name: `table_${tableIndex}`,
      kind: "table" as const,
      remarks: "",
      columns: Array.from({ length: tableIndex < 2000 ? 19 : 18 }, (_, columnIndex) => ({
        name: `column_${columnIndex}`, typeName: "VARCHAR(255)", remarks: ""
      }))
    }));
    expect(objects.reduce((count, object) => count + object.columns.length, 0)).toBe(110_000);
    const largeIndex = buildCompletionIndex({ ...snapshot, selectedNamespaceKeys: ["catalog:sales"],
      namespaces: [{ key: "catalog:sales", catalog: "sales", schema: "", label: "sales", objects }] });
    const result = resolveCompletion(largeIndex, { providerId: "mysql", sql: "select * from ", prefix: "", limit: 100 });
    expect(result.items).toHaveLength(100);
    expect(result.incomplete).toBe(true);
  });
});

function complete(sql: string) {
  return resolveCompletion(index, { providerId: "mysql", sql, prefix: "", limit: 100 }).items;
}

function metadata(sql: string) {
  return complete(sql).filter((item) => item.kind === "table" || item.kind === "view");
}

function columns(sql: string) {
  return complete(sql).filter((item) => item.kind === "column");
}
