import { describe, expect, it } from "vitest";
import { resolveSqlObjectReference } from "./objectReference";

function at(sql: string, text: string, provider = "mysql") {
  const offset = sql.lastIndexOf(text) + Math.floor(text.length / 2);
  return resolveSqlObjectReference(sql, offset, provider, { catalog: "default_db", schema: "DEFAULT_SCHEMA" });
}

describe("resolveSqlObjectReference", () => {
  it("resolves qualified sources and highlights only the object segment", () => {
    const sql = "select * from sales.orders o";
    const result = at(sql, "orders");
    expect(result).toMatchObject({ schema: "sales", objectName: "orders", alias: "o", viaAlias: false });
    expect(sql.slice(result!.range.start, result!.range.end)).toBe("orders");
  });

  it("maps aliases to their physical tables", () => {
    const sql = "select o.id from sales.orders o join customers c on c.id=o.customer_id";
    expect(resolveSqlObjectReference(sql, sql.lastIndexOf("o.id"), "mysql"))
      .toMatchObject({ schema: "sales", objectName: "orders", viaAlias: true });
    expect(resolveSqlObjectReference(sql, sql.lastIndexOf("c.id"), "mysql", { catalog: "default_db" }))
      .toMatchObject({ catalog: "default_db", objectName: "customers", viaAlias: true });
  });

  it.each([
    "insert into app.orders(id) values (1)",
    "update app.orders set id=1",
    "delete from app.orders where id=1",
    "merge into app.orders o using app.stage s on (o.id=s.id)"
  ])("resolves DML sources: %s", sql => {
    expect(at(sql, "orders", "oracle")).toMatchObject({ schema: "app", objectName: "orders" });
  });

  it("supports quoted identifiers", () => {
    expect(at('select * from "Odd Schema"."Order Items" x', "Order Items", "oracle"))
      .toMatchObject({ schema: "Odd Schema", objectName: "Order Items", quoted: true });
    expect(at("select * from `odd-db`.`order-items`", "order-items"))
      .toMatchObject({ schema: "odd-db", objectName: "order-items", quoted: true });
  });

  it("ignores comments, strings, columns, CTEs and derived tables", () => {
    expect(at("select 'from fake' value from real_table", "fake")).toBeNull();
    expect(at("select id /* from fake */ from real_table", "fake")).toBeNull();
    expect(at("with recent as (select * from orders) select * from recent", "recent", "oracle")).toBeNull();
    expect(at("select * from (select * from orders) x", "x", "oracle")).toBeNull();
    expect(at("select customer_name from orders", "customer_name")).toBeNull();
  });
});
