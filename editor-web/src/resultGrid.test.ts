import { describe, expect, it } from "vitest";
import type { QueryColumn, QueryMutationTarget } from "./types";
import { copyGrid, copyInPredicate, copyRowSql, selectRows, visibleRows } from "./resultGrid";

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

  it("supports row toggle/range selection and escaped delimited copies", () => {
    expect(selectRows([4, 2, 8], [4], 4, 8, false, true).selected).toEqual([4, 2, 8]);
    expect(selectRows([4, 2, 8], [4, 8], 8, 4, true, false).selected).toEqual([8]);
    expect(copyGrid([{ label: "name", index: 1 }], [{ sourceIndex: 0, cells: ["1", "A,\"B\""] }],
      true, "comma")).toBe('name\n"A,""B"""');
  });

  it("generates IN predicates with correct NULL semantics", () => {
    const rows = [{ sourceIndex: 0, cells: ["1", "A", null] }, { sourceIndex: 1, cells: [null, "B", null] }];
    expect(copyInPredicate([{ index: 0, quotedLabel: "`id`", jdbcType: -5 }], rows))
      .toBe("(`id` IN (1) OR `id` IS NULL)");
    expect(copyInPredicate([
      { index: 0, quotedLabel: "`id`", jdbcType: -5 }, { index: 1, quotedLabel: "`name`", jdbcType: 12 }
    ], rows)).toBeUndefined();
  });

  it("uses hidden primary-key columns for UPDATE and DELETE", () => {
    const target: QueryMutationTarget = { qualifiedName: "`db`.`orders`", columns: [
      { resultIndex: 0, name: "id", quotedName: "`id`", jdbcType: -5 },
      { resultIndex: 1, name: "name", quotedName: "`name`", jdbcType: 12 }
    ], uniqueKeys: [{ name: "PRIMARY", primary: true, resultColumnIndices: [0] }] };
    const rows = [{ sourceIndex: 0, cells: ["7", "O'Reilly"] }];
    expect(copyRowSql("update", target, [1], rows))
      .toBe("UPDATE `db`.`orders` SET `name` = 'O''Reilly' WHERE `id` = 7;");
    expect(copyRowSql("delete", target, [1], rows))
      .toBe("DELETE FROM `db`.`orders` WHERE `id` = 7;");
    expect(copyRowSql("insert", target, [1], rows))
      .toBe("INSERT INTO `db`.`orders` (`name`) VALUES ('O''Reilly');");
  });
});
