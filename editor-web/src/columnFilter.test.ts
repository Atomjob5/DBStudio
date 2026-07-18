import { describe, expect, it } from "vitest";
import { matchesColumnQuery, resultColumnOptions } from "./columnFilter";
import type { QueryColumn } from "./types";

const column: QueryColumn = {
  label: "order_id", name: "order_id", remarks: "订单 商品 编号",
  catalog: "db", schema: "", table: "orders", typeName: "BIGINT"
};

describe("result column filter", () => {
  it("matches names and remarks without case sensitivity", () => {
    expect(matchesColumnQuery(column, "ORDER")).toBe(true);
    expect(matchesColumnQuery(column, "商品")).toBe(true);
    expect(matchesColumnQuery(column, "missing")).toBe(false);
  });

  it("uses spaces as AND and commas as OR", () => {
    expect(matchesColumnQuery(column, "order 商品")).toBe(true);
    expect(matchesColumnQuery(column, "order missing")).toBe(false);
    expect(matchesColumnQuery(column, "order missing,编号")).toBe(true);
    expect(matchesColumnQuery(column, "missing，商品")).toBe(true);
    expect(matchesColumnQuery(column, " , ， ")).toBe(true);
  });

  it("falls back to legacy column labels when details are absent", () => {
    expect(resultColumnOptions(["id"])[0]).toMatchObject({ index: 0, label: "id", name: "id", remarks: "" });
  });
});
