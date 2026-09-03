import { describe, expect, it } from "vitest";
import type { QueryColumn, QueryMutationTarget } from "./types";
import { comparableValue, copyCellSql, copyEqualsSql, copyGrid, copyInPredicate, copyRowSql, copySelectSql, formatJsonValue, selectRows,
  sqlLiteral, sumDecimalValues, visibleRows } from "./resultGrid";

const columns: QueryColumn[] = [
  { label: "id", name: "id", remarks: "", catalog: "db", schema: "", table: "orders", typeName: "BIGINT", jdbcType: -5, quotedLabel: "`id`" },
  { label: "name", name: "name", remarks: "", catalog: "db", schema: "", table: "orders", typeName: "VARCHAR", jdbcType: 12, quotedLabel: "`name`" },
  { label: "created_at", name: "created_at", remarks: "", catalog: "db", schema: "", table: "orders", typeName: "TIMESTAMP", jdbcType: 93, quotedLabel: "`created_at`" }
];

describe("result grid transformations", () => {
  it("sorts BIGINT values without losing precision, keeps NULL last and applies AND filters", () => {
    const rows = [["9007199254740993", "Alpha", "2026-01-02"], ["2", "Beta", "2026-01-01"],
      [null, "Alpine", "2026-01-03"]];
    const result = visibleRows(rows, columns, { columnIndex: 0, direction: "desc" }, [
      { columnIndex: 1, operator: "starts", value: "Al" },
      { columnIndex: 2, operator: "gte", value: "2026-01-02" }
    ]);
    expect(result.map((row) => row.sourceIndex)).toEqual([0, 2]);
  });

  it("keeps stable source order when sort values are equal", () => {
    const result = visibleRows([["1", "same", null], ["2", "same", null]], columns,
      { columnIndex: 1, direction: "asc" }, []);
    expect(result.map((row) => row.sourceIndex)).toEqual([0, 1]);
  });

  it("supports contains filtering for numeric columns by their displayed value", () => {
    const result = visibleRows([["120", "one", null], ["34", "two", null], ["21", "three", null]], columns,
      undefined, [{ columnIndex: 0, operator: "contains", value: "2" }]);
    expect(result.map((row) => row.sourceIndex)).toEqual([0, 2]);
  });

  it("supports row toggle/range selection and preserves delimited copy values", () => {
    expect(selectRows([4, 2, 8], [4], 4, 8, false, true).selected).toEqual([4, 2, 8]);
    expect(selectRows([4, 2, 8], [4, 8], 8, 4, true, false).selected).toEqual([8]);
    expect(copyGrid([{ label: "name", index: 1 }], [{ sourceIndex: 0, cells: ["1", "A,\"B\""] }],
      true, "comma")).toBe('name\nA,"B"');
  });

  it("generates IN predicates with correct NULL semantics", () => {
    const rows = [{ sourceIndex: 0, cells: ["1", "A", null] }, { sourceIndex: 1, cells: [null, "B", null] }];
    expect(copyInPredicate([{ index: 0, label: "id", jdbcType: -5 }], rows))
      .toBe("(id IN (1) OR id IS NULL)");
    expect(copyInPredicate([
      { index: 0, label: "id", jdbcType: -5 }, { index: 1, label: "name", jdbcType: 12 }
    ], rows)).toBeUndefined();
  });

  it("uses hidden primary-key columns for UPDATE and DELETE", () => {
    const target: QueryMutationTarget = { qualifiedName: "`db`.`orders`", columns: [
      { resultIndex: 0, name: "id", quotedName: "`id`", jdbcType: -5 },
      { resultIndex: 1, name: "name", quotedName: "`name`", jdbcType: 12 }
    ], uniqueKeys: [{ name: "PRIMARY", primary: true, resultColumnIndices: [0] }] };
    const rows = [{ sourceIndex: 0, cells: ["7", "O'Reilly"] }];
    expect(copyRowSql("update", target, [1], rows))
      .toBe("UPDATE db.orders SET name = 'O''Reilly' WHERE id = 7;");
    expect(copyRowSql("delete", target, [1], rows))
      .toBe("DELETE FROM db.orders WHERE id = 7;");
    expect(copyRowSql("insert", target, [1], rows))
      .toBe("INSERT INTO `db`.`orders` (name) VALUES ('O''Reilly');");

    expect(copyCellSql("update", target, [{ row: { sourceIndex: 0, cells: ["7", "O'Reilly"] },
      columnIndices: [0, 1] }]))
      .toBe("UPDATE db.orders SET id = 7, name = 'O''Reilly' WHERE id = 7;");
    expect(copyCellSql("delete", target, [{ row: { sourceIndex: 0, cells: ["7", "O'Reilly"] },
      columnIndices: [1] }]))
      .toBe("DELETE FROM db.orders WHERE id = 7;");
    expect(copyCellSql("update", target, [
      { row: { sourceIndex: 0, cells: ["7", "O'Reilly"] }, columnIndices: [1] },
      { row: { sourceIndex: 1, cells: ["8", "Second"] }, columnIndices: [0] }
    ])).toBe("UPDATE db.orders SET name = 'O''Reilly' WHERE id = 7;\nUPDATE db.orders SET id = 8 WHERE id = 8;");
  });

  it("generates aggregated equality and SELECT SQL for sparse rows with NULL semantics", () => {
    const target: QueryMutationTarget = { qualifiedName: "\"db\".\"orders\"", columns: [
      { resultIndex: 0, name: "id", quotedName: "\"id\"", jdbcType: -5 },
      { resultIndex: 1, name: "name", quotedName: "\"name\"", jdbcType: 12 },
      { resultIndex: 2, name: "note", quotedName: "\"note\"", jdbcType: 12 }
    ], uniqueKeys: [] };
    const columns = target.columns.map((column) => ({ index: column.resultIndex,
      name: column.name, jdbcType: column.jdbcType }));
    const selectedRows = [
      { row: { sourceIndex: 0, cells: ["7", "O'Reilly", null] }, columnIndices: [0, 1, 2] },
      { row: { sourceIndex: 1, cells: ["8", "Second", "memo"] }, columnIndices: [0, 2] }
    ];
    expect(copyEqualsSql(columns, selectedRows)).toBe(
      "id IN (7, 8) AND name = 'O''Reilly' AND (note = 'memo' OR note IS NULL)");
    expect(copySelectSql(target, selectedRows)).toBe(
      "SELECT * FROM db.orders WHERE id IN (7, 8) AND name = 'O''Reilly' "
      + "AND (note = 'memo' OR note IS NULL);");
    expect(copySelectSql(undefined, selectedRows)).toBeUndefined();
    expect(copySelectSql({ ...target, reasonCode: "AMBIGUOUS_PROJECTION" }, selectedRows)).toBeUndefined();
  });

  it("aggregates duplicate values and keeps single-value columns as equality", () => {
    const target: QueryMutationTarget = { qualifiedName: "`CBSAC`.`APP_CONFIG`", columns: [
      { resultIndex: 0, name: "ID", quotedName: "`ID`", jdbcType: -5 },
      { resultIndex: 1, name: "DISPLAY_NAME", quotedName: "`DISPLAY_NAME`", jdbcType: 12 },
      { resultIndex: 2, name: "STATUS", quotedName: "`STATUS`", jdbcType: 12 }
    ], uniqueKeys: [] };
    const selectedRows = [
      { row: { sourceIndex: 2, cells: ["2", "测试数据 2", "A"] }, columnIndices: [1, 2] },
      { row: { sourceIndex: 3, cells: ["3", "测试数据 3", "A"] }, columnIndices: [1, 2] }
    ];
    expect(copyEqualsSql(target.columns.map((column) => ({ index: column.resultIndex,
      name: column.name, jdbcType: column.jdbcType })), selectedRows)).toBe(
      "DISPLAY_NAME IN ('测试数据 2', '测试数据 3') AND STATUS = 'A'");
    expect(copySelectSql(target, selectedRows)).toBe(
      "SELECT * FROM CBSAC.APP_CONFIG WHERE DISPLAY_NAME IN ('测试数据 2', '测试数据 3') AND STATUS = 'A';");
    expect(copySelectSql(target, selectedRows, "mysql", [2, 1, 0])).toBe(
      "SELECT * FROM CBSAC.APP_CONFIG WHERE STATUS = 'A' AND DISPLAY_NAME IN ('测试数据 2', '测试数据 3');");
  });

  it("handles all-NULL columns and rejects unmapped SELECT columns", () => {
    const target: QueryMutationTarget = { qualifiedName: "db.orders", columns: [
      { resultIndex: 0, name: "id", quotedName: "id", jdbcType: -5 },
      { resultIndex: 1, name: "note", quotedName: "note", jdbcType: 12 }
    ], uniqueKeys: [] };
    const selectedRows = [
      { row: { sourceIndex: 0, cells: ["1", null] }, columnIndices: [1] },
      { row: { sourceIndex: 1, cells: ["2", null] }, columnIndices: [1] }
    ];
    expect(copyEqualsSql(target.columns.map((column) => ({ index: column.resultIndex,
      name: column.name, jdbcType: column.jdbcType })), selectedRows)).toBe("note IS NULL");
    expect(copySelectSql(target, [{ row: { sourceIndex: 0, cells: ["1", "x"] }, columnIndices: [2] }]))
      .toBeUndefined();
  });

  it("uses Oracle-compatible binary, date and boolean literals", () => {
    expect(sqlLiteral("0x0aff", -3, "oracle")).toBe("HEXTORAW('0aff')");
    expect(sqlLiteral("2026-07-22", 91, "oracle")).toBe("DATE '2026-07-22'");
    expect(sqlLiteral("true", 16, "oceanbase-oracle")).toBe("1");
  });

  it("formats only JSON objects and arrays for viewing and comparison", () => {
    expect(formatJsonValue('{"b":2,"a":[1]}')).toBe('{\n  "b": 2,\n  "a": [\n    1\n  ]\n}');
    expect(formatJsonValue('"text"')).toBeUndefined();
    expect(formatJsonValue("{bad json")).toBeUndefined();
    expect(comparableValue("[1,2]")).toBe("[\n  1,\n  2\n]");
    expect(comparableValue(null)).toBe("NULL");
  });

  it("sums exact decimal values without floating-point loss and rejects text", () => {
    expect(sumDecimalValues(["9007199254740993", "7"])).toEqual({
      valid: true, total: "9007199254741000", count: 2
    });
    expect(sumDecimalValues(["1.20", "2.3", null, " "])).toEqual({
      valid: true, total: "3.50", count: 2
    });
    expect(sumDecimalValues(["1e-2", "-0.005"])).toEqual({
      valid: true, total: "0.005", count: 2
    });
    expect(sumDecimalValues(["1", "not-a-number"])).toEqual({
      valid: false, total: "", count: 1, invalidValue: "not-a-number"
    });
  });
});
