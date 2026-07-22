import { buildCompletionIndex, resolveCompletion } from "../sqlCompletion";
import type { CompletionIndex } from "../sqlCompletion";
import type { CompletionCandidate, CompletionSnapshot } from "../types";

export const mysqlSnapshot: CompletionSnapshot = {
  formatVersion: 1,
  providerId: "mysql",
  sourceProfileId: "profile-mysql",
  generatedAt: "2026-07-22T00:00:00Z",
  defaultNamespaceKey: "catalog:sales",
  selectedNamespaceKeys: ["catalog:sales", "catalog:archive", "catalog:empty"],
  namespaces: [
    { key: "catalog:sales", catalog: "sales", schema: "", label: "sales", objects: [
      { name: "orders", kind: "table", remarks: "订单", columns: [
        { name: "id", typeName: "bigint", remarks: "订单编号" },
        { name: "customer_id", typeName: "bigint", remarks: "客户编号" },
        { name: "total", typeName: "decimal(18,2)", remarks: "订单金额" },
        { name: "created_at", typeName: "datetime", remarks: "创建时间" }
      ] },
      { name: "customers", kind: "view", remarks: "客户视图", columns: [
        { name: "id", typeName: "bigint", remarks: "客户编号" },
        { name: "name", typeName: "varchar(100)", remarks: "客户名称" },
        { name: "status", typeName: "varchar(20)", remarks: "客户状态" }
      ] },
      { name: "SELECT", kind: "table", remarks: "保留字表", columns: [
        { name: "FROM", typeName: "varchar(20)", remarks: "保留字字段" }
      ] },
      { name: "用户表", kind: "table", remarks: "中文对象", columns: [
        { name: "用户编号", typeName: "bigint", remarks: "中文字段" }
      ] }
    ] },
    { key: "catalog:archive", catalog: "archive", schema: "", label: "archive", objects: [
      { name: "orders", kind: "table", remarks: "归档订单", columns: [
        { name: "id", typeName: "bigint", remarks: "归档编号" },
        { name: "archived_at", typeName: "datetime", remarks: "归档时间" }
      ] },
      { name: "orders_history", kind: "table", remarks: "历史订单", columns: [
        { name: "id", typeName: "bigint", remarks: "历史编号" }
      ] }
    ] },
    { key: "catalog:empty", catalog: "empty", schema: "", label: "empty", objects: [] }
  ]
};

export const oracleSnapshot: CompletionSnapshot = {
  ...mysqlSnapshot,
  providerId: "oracle",
  sourceProfileId: "profile-oracle",
  defaultNamespaceKey: "schema:CBSAC",
  selectedNamespaceKeys: ["schema:CBSAC", "schema:REPORT"],
  namespaces: [
    { key: "schema:CBSAC", catalog: "", schema: "CBSAC", label: "CBSAC", objects: [
      { name: "CUSTOMERS", kind: "table", remarks: "客户", columns: [
        { name: "ID", typeName: "NUMBER", remarks: "客户编号" },
        { name: "CUSTOMER_NAME", typeName: "VARCHAR2(100)", remarks: "客户名称" },
        { name: "STATUS", typeName: "VARCHAR2(20)", remarks: "状态" }
      ] },
      { name: "ORDERS", kind: "table", remarks: "订单", columns: [
        { name: "ID", typeName: "NUMBER", remarks: "订单编号" },
        { name: "CUSTOMER_ID", typeName: "NUMBER", remarks: "客户编号" },
        { name: "TOTAL", typeName: "NUMBER(18,2)", remarks: "金额" }
      ] }
    ] },
    { key: "schema:REPORT", catalog: "", schema: "REPORT", label: "REPORT", objects: [
      { name: "CUSTOMERS", kind: "view", remarks: "报表客户", columns: [
        { name: "ID", typeName: "NUMBER", remarks: "客户编号" }
      ] }
    ] }
  ]
};

export const mysqlIndex = buildCompletionIndex(mysqlSnapshot);
export const oracleIndex = buildCompletionIndex(oracleSnapshot);

export interface CursorSql {
  sql: string;
  cursorOffset: number;
  prefix: string;
}

export function sqlAtCursor(markedSql: string): CursorSql {
  const cursorOffset = markedSql.indexOf("|");
  if (cursorOffset < 0 || cursorOffset !== markedSql.lastIndexOf("|")) {
    throw new Error("SQL测试用例必须包含且只能包含一个光标标记 | ");
  }
  const sql = `${markedSql.slice(0, cursorOffset)}${markedSql.slice(cursorOffset + 1)}`;
  const prefix = sql.slice(0, cursorOffset).match(/[A-Za-z0-9_$#\u0080-\uFFFF]+$/u)?.[0] ?? "";
  return { sql, cursorOffset, prefix };
}

export function completeAt(markedSql: string, options: {
  index?: CompletionIndex;
  providerId?: string;
  prefix?: string;
  limit?: number;
} = {}) {
  const cursor = sqlAtCursor(markedSql);
  return resolveCompletion(options.index ?? mysqlIndex, {
    providerId: options.providerId ?? "mysql",
    sql: cursor.sql,
    cursorOffset: cursor.cursorOffset,
    prefix: options.prefix ?? cursor.prefix,
    limit: options.limit ?? 100
  });
}

export function candidatesOfKind(items: CompletionCandidate[], kind: CompletionCandidate["kind"]): CompletionCandidate[] {
  return items.filter((item) => item.kind === kind);
}

export function columnLabels(markedSql: string, options: Parameters<typeof completeAt>[1] = {}): string[] {
  return candidatesOfKind(completeAt(markedSql, options).items, "column").map((item) => item.displayLabel);
}

export function objectLabels(markedSql: string, options: Parameters<typeof completeAt>[1] = {}): string[] {
  return completeAt(markedSql, options).items
    .filter((item) => item.kind === "table" || item.kind === "view")
    .map((item) => item.displayLabel);
}
