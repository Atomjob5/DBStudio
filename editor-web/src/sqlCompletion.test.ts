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

describe("dt-sql-parser context aware completion", () => {
  it("completes tables and views after a namespace qualifier", () => {
    expect(metadata("select * from sales.").map((item) => [item.label, item.kind]))
      .toEqual([["orders", "table"], ["customers", "view"]]);
  });

  it("completes columns for a qualified and a default-namespace table", () => {
    expect(columns("select * from sales.orders where ").map((item) => item.label))
      .toEqual(["customer_id", "id"]);
    expect(columns("select * from orders where ").map((item) => item.label))
      .toEqual(["customer_id", "id"]);
  });

  it("resolves aliases and inserts only the field after an existing qualifier", () => {
    const values = columns("select * from sales.orders a where a.");
    expect(values.map((item) => item.label)).toEqual(["customer_id", "id"]);
    expect(values.map((item) => item.insertText)).toEqual(["`customer_id`", "`id`"]);
  });

  it("merges JOIN sources and qualifies duplicate field names", () => {
    const values = columns("select * from orders a join customers b on a.customer_id=b.id where ");
    expect(values.filter((item) => item.label === "id").map((item) => item.insertText))
      .toEqual(["`b`.`id`", "`a`.`id`"]);
  });

  it("does not scan all fields for an unknown alias", () => {
    expect(columns("select * from orders a where missing.")).toEqual([]);
  });

  it("inserts default objects unqualified and other namespace objects qualified", () => {
    const values = metadata("select * from ");
    expect(values.find((item) => item.label === "orders")?.insertText).toBe("`orders`");
    expect(values.find((item) => item.label === "orders_history")?.insertText).toBe("`archive`.`orders_history`");
    expect(complete("select * from ").filter((item) => item.kind === "schema").map((item) => item.label))
      .toEqual(["archive", "sales"]);
  });

  it("uses Oracle identifier quotes with GenericSQL", () => {
    const oracle = buildCompletionIndex({ ...snapshot, providerId: "oracle" });
    const result = resolveCompletion(oracle, { providerId: "oracle", sql: "select * from orders where ", prefix: "", limit: 100 });
    expect(result.items.find((item) => item.kind === "column")?.insertText).toMatch(/^"/);
  });

  it("resolves an Oracle alias from GenericSQL lexer tokens", () => {
    const oracle = buildCompletionIndex({ ...snapshot, providerId: "oracle" });
    const result = resolveCompletion(oracle, { providerId: "oracle", sql: "select * from orders o where o.", prefix: "", limit: 100 });
    expect(result.items.filter((item) => item.kind === "column").map((item) => item.label))
      .toEqual(["customer_id", "id"]);
  });

  it("uses the nearest nested alias scope", () => {
    const result = columns("select * from orders a where exists (select 1 from customers a where a.");
    expect(result.map((item) => item.label)).toEqual(["id", "name"]);
  });

  it("limits candidates and marks the result incomplete", () => {
    const result = resolveCompletion(index, { providerId: "mysql", sql: "select * from orders where ", prefix: "", limit: 10 });
    expect(result.items.length).toBe(10);
    expect(result.incomplete).toBe(true);
  });

  it("keeps a 6000-table / 110000-column snapshot hierarchical and returns only the configured candidates", () => {
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
