import { describe, expect, it } from "vitest";
import { analyzeCompletionContext, resolveCompletionSuggestions } from "./sqlCompletion";
import type { Suggestion } from "./types";

const suggestions: Suggestion[] = [
  column("sales_order", "order_id", "eastwealthcrawler"),
  column("sales_order", "customer_id", "eastwealthcrawler"),
  column("sales_order_item", "order_id", "eastwealthcrawler"),
  column("sales_order_item", "product_id", "eastwealthcrawler"),
  column("sales_order", "order_id", "archive"),
  column("customers", "customer_id", "eastwealthcrawler")
];

describe("context aware SQL completion", () => {
  it("returns only fields from the table represented by an alias", () => {
    expect(labels("select * from sales_order a join sales_order_item b on a.order_id=b.order_id where a.order", "eastwealthcrawler"))
      .toEqual(["order_id", "customer_id"]);
    expect(labels("select * from sales_order a join sales_order_item b on a.order_id=b.order_id where b.", "eastwealthcrawler"))
      .toEqual(["order_id", "product_id"]);
  });

  it("supports AS aliases, direct table names and strict catalog qualifiers", () => {
    expect(labels("select * from sales_order AS a where a.", "eastwealthcrawler")).toEqual(["order_id", "customer_id"]);
    expect(labels("select * from sales_order where sales_order.", "eastwealthcrawler")).toEqual(["order_id", "customer_id"]);
    expect(labels("select archive.sales_order.")).toEqual(["order_id"]);
  });

  it("keeps catalog-specific candidates when no default catalog disambiguates a table", () => {
    const matches = resolveCompletionSuggestions("select * from sales_order a where a.", suggestions);
    expect(matches.filter((item) => item.label === "order_id").map((item) => item.catalog))
      .toEqual(["eastwealthcrawler", "archive"]);
  });

  it("uses the nearest nested scope when aliases shadow each other", () => {
    const sql = "select * from sales_order a where exists (select 1 from sales_order_item a where a.";
    expect(labels(sql, "eastwealthcrawler")).toEqual(["order_id", "product_id"]);
  });

  it("resolves outer aliases from a nested correlated subquery", () => {
    const sql = "select * from sales_order a where exists (select 1 from sales_order_item b where a.";
    expect(labels(sql, "eastwealthcrawler")).toEqual(["order_id", "customer_id"]);
  });

  it("resolves explicit and inferred CTE projection fields", () => {
    expect(labels("with recent(id, buyer) as (select order_id, customer_id from sales_order) select * from recent r where r.", "eastwealthcrawler"))
      .toEqual(["id", "buyer"]);
    expect(labels("with recent as (select order_id as id, customer_id buyer from sales_order) select * from recent r where r.", "eastwealthcrawler"))
      .toEqual(["id", "buyer"]);
  });

  it("resolves statically known derived table fields", () => {
    expect(labels("select * from (select order_id as id, product_id from sales_order_item) x where x.", "eastwealthcrawler"))
      .toEqual(["id", "product_id"]);
  });

  it("ignores strings, comments and earlier statements", () => {
    const sql = "select 'from sales_order fake where fake.'; -- sales_order_item z\nselect * from `sales_order` a where a.";
    expect(labels(sql, "eastwealthcrawler")).toEqual(["order_id", "customer_id"]);
  });

  it("does not fall back to every database field for an unknown qualifier", () => {
    expect(labels("select * from sales_order a where missing.", "eastwealthcrawler")).toEqual([]);
  });

  it("keeps the complete environment snapshot for unqualified completion", () => {
    expect(resolveCompletionSuggestions("select * from sales_order where order", suggestions, "eastwealthcrawler"))
      .toBe(suggestions);
    expect(analyzeCompletionContext("select * from sales_order where order", suggestions).qualifier).toEqual([]);
  });
});

function labels(sql: string, defaultCatalog = ""): string[] {
  return resolveCompletionSuggestions(sql, suggestions, defaultCatalog).map((item) => item.label);
}

function column(objectName: string, label: string, catalog: string): Suggestion {
  return {
    id: `${catalog}:column:${objectName}:${label}`,
    label,
    insertText: `\`${label}\``,
    detail: `${catalog}.${objectName}`,
    kind: "column",
    catalog,
    objectName
  };
}
