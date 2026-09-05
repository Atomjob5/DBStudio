import type { QueryColumn, QueryMutationKey, QueryMutationTarget } from "./types";
import { separatorCharacter, type CopySeparator } from "./resultCopy";
import { ResultRowsSnapshot, snapshotFor, viewFor } from "./resultRows";

export type SortDirection = "asc" | "desc";
export type FilterOperator = "contains" | "not-contains" | "eq" | "neq" | "starts" | "ends"
  | "gt" | "gte" | "lt" | "lte" | "null" | "not-null";
export type FilterCategory = "text" | "number" | "date" | "boolean";

export interface ResultSort { columnIndex: number; direction: SortDirection }
export interface ResultFilter { columnIndex: number; operator: FilterOperator; value: string }
export interface ViewRow { sourceIndex: number; cells: Array<string | null>; sortKey?: PreparedValue | null }
export interface CellPoint { row: number; column: number }
export interface CellRange { start: CellPoint; end: CellPoint }
export interface SelectedCell {
  row: number;
  sourceRow: number;
  column: number;
  sourceColumn: number;
  value: string | null;
}
export interface DecimalSumResult {
  valid: boolean;
  total: string;
  count: number;
  invalidValue?: string;
}

const NUMBER_TYPES = new Set([-6, 5, 4, -5, 6, 7, 8, 2, 3]);
const BOOLEAN_TYPES = new Set([-7, 16]);
const DATE_TYPES = new Set([91, 92, 93, 2013, 2014]);
const BINARY_TYPES = new Set([-2, -3, -4, 2004]);
// Constructing an Intl.Collator is relatively expensive. These are kept at
// module scope because all grid comparisons use the same locale/options.
const TEXT_COLLATOR = new Intl.Collator(undefined, { numeric: true, sensitivity: "base" });
const FALLBACK_COLLATOR = new Intl.Collator(undefined, { numeric: true });

interface PreparedValue {
  raw: string;
  lower?: string;
  decimal?: { negative: boolean; integer: string; fraction: string };
  date?: number;
  boolean?: number;
}

export function filterCategory(column?: QueryColumn): FilterCategory {
  const type = column?.jdbcType ?? 12;
  if (NUMBER_TYPES.has(type)) return "number";
  if (BOOLEAN_TYPES.has(type)) return "boolean";
  if (DATE_TYPES.has(type)) return "date";
  return "text";
}

export function visibleRows(rows: Array<Array<string | null>>, columns: QueryColumn[],
                            sort: ResultSort | undefined, filters: ResultFilter[]): ViewRow[] {
  const indexed = rows.map((cells, sourceIndex) => ({ sourceIndex, cells }));
  const filterKeys = filters.map((filter) => prepareValue(filter.value, columns[filter.columnIndex]));
  const filtered = filters.length ? indexed.filter((row) => filters.every((filter, filterIndex) =>
    matchesPrepared(row.cells[filter.columnIndex] ?? null, filter, columns[filter.columnIndex],
      filterKeys[filterIndex]))) : indexed;
  if (!sort) return viewFor(ResultRowsSnapshot.from(filtered));
  const column = columns[sort.columnIndex];
  const prepared = filtered.map((row) => ({ ...row,
    sortKey: prepareValue(row.cells[sort.columnIndex] ?? null, column) }));
  prepared.sort((left, right) => {
    const leftValue = left.cells[sort.columnIndex] ?? null;
    const rightValue = right.cells[sort.columnIndex] ?? null;
    // NULL is always placed last, independently of the selected direction.
    if (leftValue === null || rightValue === null) {
      if (leftValue === rightValue) return left.sourceIndex - right.sourceIndex;
      return leftValue === null ? 1 : -1;
    }
    const compared = comparePrepared(left.sortKey as PreparedValue,
      right.sortKey as PreparedValue, column);
    return compared === 0 ? left.sourceIndex - right.sourceIndex
      : sort.direction === "asc" ? compared : -compared;
  });
  return viewFor(ResultRowsSnapshot.from(prepared));
}

/** Incrementally extends an existing display index with one result batch. */
export function appendVisibleRows(previous: ViewRow[], appendedRows: Array<Array<string | null>>,
                                  sourceOffset: number, columns: QueryColumn[],
                                  sort: ResultSort | undefined, filters: ResultFilter[]): ViewRow[] {
  if (!appendedRows.length) return previous;
  if (!sort && !filters.length) {
    return viewFor(snapshotFor(previous).append(
      appendedRows.map((cells, index) => ({ sourceIndex: sourceOffset + index, cells }))));
  }
  const batch = visibleRows(appendedRows, columns, sort, filters)
    .map((row) => ({ ...row, sourceIndex: row.sourceIndex + sourceOffset }));
  if (!sort) return viewFor(snapshotFor(previous).append(batch));
  const merged: ViewRow[] = [];
  let left = 0; let right = 0;
  while (left < previous.length && right < batch.length) {
    if (compareViewRows(previous[left], batch[right], sort, columns[sort.columnIndex]) <= 0) {
      merged.push(previous[left++]);
    } else {
      merged.push(batch[right++]);
    }
  }
  merged.push(...previous.slice(left), ...batch.slice(right));
  return viewFor(ResultRowsSnapshot.from(merged));
}

function compareViewRows(left: ViewRow, right: ViewRow, sort: ResultSort, column?: QueryColumn): number {
  const leftValue = left.cells[sort.columnIndex] ?? null;
  const rightValue = right.cells[sort.columnIndex] ?? null;
  if (leftValue === null || rightValue === null) {
    if (leftValue === rightValue) return left.sourceIndex - right.sourceIndex;
    return leftValue === null ? 1 : -1;
  }
  const compared = comparePrepared(
    (left.sortKey ?? prepareValue(leftValue, column)) as PreparedValue,
    (right.sortKey ?? prepareValue(rightValue, column)) as PreparedValue, column);
  return compared === 0 ? left.sourceIndex - right.sourceIndex
    : sort.direction === "asc" ? compared : -compared;
}

export function compareValues(left: string | null, right: string | null, column?: QueryColumn): number {
  if (left === null) return right === null ? 0 : 1;
  if (right === null) return -1;
  return comparePrepared(prepareValue(left, column) as PreparedValue,
    prepareValue(right, column) as PreparedValue, column);
}

export function matchesFilter(value: string | null, filter: ResultFilter, column?: QueryColumn): boolean {
  if (filter.operator === "null") return value === null;
  if (filter.operator === "not-null") return value !== null;
  if (value === null) return false;
  return matchesPrepared(value, filter, column, prepareValue(filter.value, column));
}

function matchesPrepared(value: string | null, filter: ResultFilter, column: QueryColumn | undefined,
                         expectedKey: PreparedValue | null | undefined): boolean {
  if (filter.operator === "null") return value === null;
  if (filter.operator === "not-null") return value !== null;
  if (value === null) return false;
  const expected = filter.value;
  const lowerValue = value.toLocaleLowerCase();
  const lowerExpected = expectedKey?.lower ?? expected.toLocaleLowerCase();
  if (filter.operator === "contains") return lowerValue.includes(lowerExpected);
  if (filter.operator === "not-contains") return !lowerValue.includes(lowerExpected);
  if (filter.operator === "starts") return lowerValue.startsWith(lowerExpected);
  if (filter.operator === "ends") return lowerValue.endsWith(lowerExpected);
  const compared = comparePrepared(prepareValue(value, column) as PreparedValue,
    (expectedKey ?? prepareValue(expected, column)) as PreparedValue, column);
  if (filter.operator === "eq") return compared === 0;
  if (filter.operator === "neq") return compared !== 0;
  if (filter.operator === "gt") return compared > 0;
  if (filter.operator === "gte") return compared >= 0;
  if (filter.operator === "lt") return compared < 0;
  return compared <= 0;
}

function prepareValue(value: string | null, column?: QueryColumn): PreparedValue | null {
  if (value === null) return null;
  const category = filterCategory(column);
  if (category === "number") return { raw: value, decimal: decimalParts(value) };
  if (category === "date") {
    const date = Date.parse(value);
    return { raw: value, date: Number.isFinite(date) ? date : undefined };
  }
  if (category === "boolean") return { raw: value, boolean: booleanValue(value) };
  return { raw: value, lower: value.toLocaleLowerCase() };
}

function comparePrepared(left: PreparedValue, right: PreparedValue, column?: QueryColumn): number {
  const category = filterCategory(column);
  if (category === "number") {
    return left.decimal && right.decimal
      ? compareDecimalParts(left.decimal, right.decimal) : FALLBACK_COLLATOR.compare(left.raw, right.raw);
  }
  if (category === "date") {
    return left.date !== undefined && right.date !== undefined
      ? left.date - right.date : TEXT_COLLATOR.compare(left.raw, right.raw);
  }
  if (category === "boolean") return (left.boolean ?? 0) - (right.boolean ?? 0);
  return TEXT_COLLATOR.compare(left.raw, right.raw);
}

export function normalizeRange(range: CellRange): CellRange {
  return { start: { row: Math.min(range.start.row, range.end.row), column: Math.min(range.start.column, range.end.column) },
    end: { row: Math.max(range.start.row, range.end.row), column: Math.max(range.start.column, range.end.column) } };
}

export function inRange(range: CellRange | undefined, row: number, column: number): boolean {
  if (!range) return false;
  const value = normalizeRange(range);
  return row >= value.start.row && row <= value.end.row && column >= value.start.column && column <= value.end.column;
}

export function cellSelectionKey(sourceRow: number, sourceColumn: number): string {
  return `${sourceRow}:${sourceColumn}`;
}

export function formatJsonValue(value: string | null): string | undefined {
  if (value === null) return undefined;
  try {
    const parsed: unknown = JSON.parse(value);
    if (parsed === null || typeof parsed !== "object") return undefined;
    return JSON.stringify(parsed, null, 2);
  } catch {
    return undefined;
  }
}

export function comparableValue(value: string | null): string {
  if (value === null) return "NULL";
  return formatJsonValue(value) ?? value;
}

export function sumDecimalValues(values: Array<string | null>): DecimalSumResult {
  const parsed: Array<{ coefficient: bigint; scale: number }> = [];
  for (const value of values) {
    if (value === null || value.trim() === "") continue;
    const decimal = exactDecimal(value);
    if (!decimal) return { valid: false, total: "", count: parsed.length, invalidValue: value };
    parsed.push(decimal);
  }
  if (!parsed.length) return { valid: true, total: "0", count: 0 };
  const scale = Math.max(...parsed.map((value) => value.scale));
  const total = parsed.reduce((sum, value) =>
    sum + value.coefficient * powerOfTen(scale - value.scale), 0n);
  return { valid: true, total: formatExactDecimal(total, scale), count: parsed.length };
}

export function selectRows(order: number[], current: number[], anchor: number | undefined, clicked: number,
                           toggle: boolean, range: boolean): { selected: number[]; anchor: number } {
  if (range && anchor !== undefined && order.includes(anchor)) {
    const start = order.indexOf(anchor); const end = order.indexOf(clicked);
    return { selected: order.slice(Math.min(start, end), Math.max(start, end) + 1), anchor };
  }
  if (toggle) {
    const selected = current.includes(clicked) ? current.filter((value) => value !== clicked)
      : order.filter((value) => current.includes(value) || value === clicked);
    return { selected, anchor: clicked };
  }
  return { selected: [clicked], anchor: clicked };
}

export function copyGrid(columns: Array<{ label: string; index: number }>, rows: ViewRow[],
                         includeHeaders: boolean, separator: CopySeparator): string {
  const delimiter = separatorCharacter(separator);
  const line = (values: Array<string | null | undefined>) => values.map((value) =>
    value === null || value === undefined ? "NULL" : value).join(delimiter);
  const output: string[] = [];
  if (includeHeaders) output.push(line(columns.map((column) => column.label)));
  for (const row of rows) output.push(line(columns.map((column) => row.cells[column.index])));
  return output.join("\n");
}

export function copyInPredicate(columns: Array<{ index: number; label?: string; quotedLabel?: string; jdbcType: number }>,
                                rows: ViewRow[], dialectId = "mysql"): string | undefined {
  if (!columns.length || !rows.length) return undefined;
  if (columns.length === 1) {
    const column = columns[0]; const values: string[] = []; let hasNull = false;
    const label = unquoteIdentifier(column.label ?? column.quotedLabel ?? "");
    if (!label) return undefined;
    for (const row of rows) {
      const value = row.cells[column.index];
      if (value === null || value === undefined) hasNull = true;
      else values.push(sqlLiteral(value, column.jdbcType, dialectId));
    }
    const unique = [...new Set(values)];
    const parts: string[] = [];
    if (unique.length) parts.push(`${label} IN (${unique.join(", ")})`);
    if (hasNull) parts.push(`${label} IS NULL`);
    return parts.length > 1 ? `(${parts.join(" OR ")})` : parts[0];
  }
  if (rows.some((row) => columns.some((column) => row.cells[column.index] == null))) return undefined;
  const labels = columns.map((column) => unquoteIdentifier(column.label ?? column.quotedLabel ?? ""));
  if (labels.some((label) => !label)) return undefined;
  const tuples = rows.map((row) => `(${columns.map((column) =>
    sqlLiteral(row.cells[column.index] as string, column.jdbcType, dialectId)).join(", ")})`);
  return `(${labels.join(", ")}) IN (${[...new Set(tuples)].join(", ")})`;
}

export type RowSqlMode = "insert" | "update" | "delete";

export function copyRowSql(mode: RowSqlMode, target: QueryMutationTarget | undefined,
                           visibleColumnIndices: number[], rows: ViewRow[], dialectId = "mysql"): string | undefined {
  if (!target || !rows.length) return undefined;
  const visible = target.columns.filter((column) => visibleColumnIndices.includes(column.resultIndex));
  const tableName = mode === "insert" ? target.qualifiedName : unquoteIdentifier(target.qualifiedName);
  if (mode === "insert") {
    if (!visible.length) return undefined;
    return rows.map((row) => `INSERT INTO ${target.qualifiedName} (${visible.map((column) => unquoteIdentifier(column.name)).join(", ")}) VALUES (${visible.map((column) => sqlLiteral(row.cells[column.resultIndex] ?? null, column.jdbcType, dialectId)).join(", ")});`).join("\n");
  }
  const key = chooseKey(target.uniqueKeys, rows);
  if (!key) return undefined;
  const keyIndices = new Set(key.resultColumnIndices);
  const setters = visible.filter((column) => !keyIndices.has(column.resultIndex));
  if (mode === "update" && !setters.length) return undefined;
  const byIndex = new Map(target.columns.map((column) => [column.resultIndex, column]));
  return rows.map((row) => {
    const where = key.resultColumnIndices.map((index) => {
      const column = byIndex.get(index) as QueryMutationTarget["columns"][number];
      return `${unquoteIdentifier(column.name)} = ${sqlLiteral(row.cells[index] ?? null, column.jdbcType, dialectId)}`;
    }).join(" AND ");
    if (mode === "delete") return `DELETE FROM ${tableName} WHERE ${where};`;
    return `UPDATE ${tableName} SET ${setters.map((column) => `${unquoteIdentifier(column.name)} = ${sqlLiteral(row.cells[column.resultIndex] ?? null, column.jdbcType, dialectId)}`).join(", ")} WHERE ${where};`;
  }).join("\n");
}

export interface SelectedRowColumns {
  row: ViewRow;
  columnIndices: number[];
}

export interface ResultSqlColumn {
  index: number;
  name?: string;
  label?: string;
  jdbcType: number;
}

/** Generate one equality predicate per selected column, retaining sparse selections. */
export function copyEqualsSql(columns: ResultSqlColumn[], selectedRows: SelectedRowColumns[],
                              dialectId = "mysql"): string | undefined {
  return copySelectedConditions(columns, selectedRows, dialectId);
}

/**
 * Generate one condition per selected column. Values are deduplicated after
 * conversion to SQL literals, while preserving the first-seen row order.
 */
export function copySelectedConditions(columns: ResultSqlColumn[], selectedRows: SelectedRowColumns[],
                                       dialectId = "mysql"): string | undefined {
  if (!columns.length || !selectedRows.length) return undefined;
  const byIndex = new Map(columns.map((column) => [column.index, column]));
  const groups = new Map<number, { column: ResultSqlColumn; values: string[]; hasNull: boolean }>();
  for (const { row, columnIndices } of selectedRows) {
    const indices = [...new Set(columnIndices)];
    if (!indices.length) return undefined;
    for (const index of indices) {
      const column = byIndex.get(index);
      if (!column) return undefined;
      const group = groups.get(index) ?? { column, values: [], hasNull: false };
      const value = row.cells[index] ?? null;
      if (value === null) group.hasNull = true;
      else group.values.push(sqlLiteral(value, column.jdbcType, dialectId));
      groups.set(index, group);
    }
  }
  if (!groups.size) return undefined;
  const predicates: string[] = [];
  const emitted = new Set<number>();
  for (const column of columns) {
    if (!groups.has(column.index) || emitted.has(column.index)) continue;
    emitted.add(column.index);
    const group = groups.get(column.index) as { column: ResultSqlColumn; values: string[]; hasNull: boolean };
    const name = unquoteIdentifier(group.column.name || group.column.label || "").trim();
    if (!name) return undefined;
    const unique = [...new Set(group.values)];
    if (!unique.length) {
      predicates.push(`${name} IS NULL`);
      continue;
    }
    const condition = unique.length === 1 ? `${name} = ${unique[0]}`
      : `${name} IN (${unique.join(", ")})`;
    predicates.push(group.hasNull ? `(${condition} OR ${name} IS NULL)` : condition);
  }
  return predicates.length ? predicates.join(" AND ") : undefined;
}

/** Generate one SELECT statement using the resolved result target. */
export function copySelectSql(target: QueryMutationTarget | undefined,
                              selectedRows: SelectedRowColumns[], dialectId = "mysql",
                              columnOrder?: number[]): string | undefined {
  if (!target || !target.qualifiedName.trim() || !selectedRows.length
      || ["AMBIGUOUS_PROJECTION", "TARGET_OWNER_UNRESOLVED"].includes(target.reasonCode?.trim() ?? "")) return undefined;
  const targetColumns = target.columns.map((column) => ({
    index: column.resultIndex, name: column.name, jdbcType: column.jdbcType
  }));
  const orderedColumns = columnOrder?.length ? [...targetColumns].sort((left, right) => {
    const leftPosition = columnOrder.indexOf(left.index);
    const rightPosition = columnOrder.indexOf(right.index);
    return (leftPosition < 0 ? columnOrder.length : leftPosition)
      - (rightPosition < 0 ? columnOrder.length : rightPosition);
  }) : targetColumns;
  const conditions = copySelectedConditions(orderedColumns, selectedRows, dialectId);
  if (!conditions) return undefined;
  const table = unquoteIdentifier(target.qualifiedName).trim();
  if (!table) return undefined;
  return `SELECT * FROM ${table} WHERE ${conditions};`;
}

/** Generate DML for cell selections, retaining the selected columns per row. */
export function copyCellSql(mode: "update" | "delete", target: QueryMutationTarget | undefined,
                            selectedRows: SelectedRowColumns[], dialectId = "mysql"): string | undefined {
  if (!target || !selectedRows.length) return undefined;
  const byIndex = new Map(target.columns.map((column) => [column.resultIndex, column]));
  if (mode === "update" && selectedRows.some(({ columnIndices }) =>
    !columnIndices.length || columnIndices.some((index) => !byIndex.has(index)))) return undefined;
  const statements: string[] = [];
  for (const { row, columnIndices } of selectedRows) {
    const key = chooseKey(target.uniqueKeys, [row]);
    if (!key) return undefined;
    const where = key.resultColumnIndices.map((index) => {
      const column = byIndex.get(index);
      if (!column) return undefined;
      return `${unquoteIdentifier(column.name)} = ${sqlLiteral(row.cells[index] ?? null, column.jdbcType, dialectId)}`;
    });
    if (where.some((part) => part === undefined)) return undefined;
    if (mode === "delete") {
      statements.push(`DELETE FROM ${unquoteIdentifier(target.qualifiedName)} WHERE ${where.join(" AND ")};`);
      continue;
    }
    const setters = [...new Set(columnIndices)].map((index) => {
      const column = byIndex.get(index);
      if (!column) return undefined;
      return `${unquoteIdentifier(column.name)} = ${sqlLiteral(row.cells[index] ?? null, column.jdbcType, dialectId)}`;
    });
    if (setters.some((part) => part === undefined) || !setters.length) return undefined;
    statements.push(`UPDATE ${unquoteIdentifier(target.qualifiedName)} SET ${setters.join(", ")} WHERE ${where.join(" AND ")};`);
  }
  return statements.join("\n");
}

function chooseKey(keys: QueryMutationKey[], rows: ViewRow[]): QueryMutationKey | undefined {
  return [...keys].sort((left, right) => Number(right.primary) - Number(left.primary)
    || left.resultColumnIndices.length - right.resultColumnIndices.length || left.name.localeCompare(right.name))
    .find((key) => rows.every((row) => key.resultColumnIndices.every((index) => row.cells[index] != null)));
}

export function sqlLiteral(value: string | null, jdbcType: number, dialectId = "mysql"): string {
  if (value === null) return "NULL";
  const oracle = dialectId === "oracle" || dialectId === "oceanbase-oracle";
  if (BINARY_TYPES.has(jdbcType) && /^0x[0-9a-f]+$/i.test(value)) {
    return oracle ? `HEXTORAW('${value.slice(2)}')` : value;
  }
  if (NUMBER_TYPES.has(jdbcType) && /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i.test(value)) return value;
  if (BOOLEAN_TYPES.has(jdbcType)) {
    if (/^(?:true|1)$/i.test(value)) return oracle ? "1" : "TRUE";
    if (/^(?:false|0)$/i.test(value)) return oracle ? "0" : "FALSE";
  }
  if (oracle && jdbcType === 91 && /^\d{4}-\d{2}-\d{2}$/.test(value)) return `DATE '${value}'`;
  if (oracle && [92, 93, 2013, 2014].includes(jdbcType) && /^\d{4}-\d{2}-\d{2}[ T]/.test(value)) {
    return `TIMESTAMP '${value.replace("T", " ")}'`;
  }
  return `'${value.replace(/\\/g, "\\\\").replace(/'/g, "''")}'`;
}

function unquoteIdentifier(value: string): string {
  return value.replace(/[\[\]`\"]/g, "");
}

function booleanValue(value: string): number { return /^(?:true|1|yes|y)$/i.test(value.trim()) ? 1 : 0; }

function compareDecimalParts(left: { negative: boolean; integer: string; fraction: string },
                             right: { negative: boolean; integer: string; fraction: string }): number {
  if (left.negative !== right.negative) return left.negative ? -1 : 1;
  let compared = left.integer.length - right.integer.length;
  if (!compared) compared = left.integer.localeCompare(right.integer);
  if (!compared) compared = left.fraction.padEnd(Math.max(left.fraction.length, right.fraction.length), "0")
    .localeCompare(right.fraction.padEnd(Math.max(left.fraction.length, right.fraction.length), "0"));
  return left.negative ? -compared : compared;
}

function decimalParts(value: string): { negative: boolean; integer: string; fraction: string } | undefined {
  const match = /^\s*([+-])?(\d*)(?:\.(\d*))?(?:e([+-]?\d+))?\s*$/i.exec(value);
  if (!match || (!match[2] && !match[3]) || match[4]) return undefined;
  return { negative: match[1] === "-", integer: (match[2] || "0").replace(/^0+(?=\d)/, ""),
    fraction: (match[3] || "").replace(/0+$/, "") };
}

function exactDecimal(value: string): { coefficient: bigint; scale: number } | undefined {
  const match = /^\s*([+-])?(\d*)(?:\.(\d*))?(?:e([+-]?\d+))?\s*$/i.exec(value);
  if (!match || (!match[2] && !match[3])) return undefined;
  const fraction = match[3] || "";
  const exponent = Number(match[4] || 0);
  if (!Number.isSafeInteger(exponent) || Math.abs(exponent) > 10_000) return undefined;
  const digits = `${match[2] || "0"}${fraction}`.replace(/^0+(?=\d)/, "") || "0";
  let coefficient = BigInt(digits);
  if (match[1] === "-") coefficient = -coefficient;
  const scale = fraction.length - exponent;
  if (scale >= 0) return { coefficient, scale };
  return { coefficient: coefficient * powerOfTen(-scale), scale: 0 };
}

function powerOfTen(exponent: number): bigint {
  return 10n ** BigInt(exponent);
}

function formatExactDecimal(coefficient: bigint, scale: number): string {
  const negative = coefficient < 0n;
  const digits = (negative ? -coefficient : coefficient).toString().padStart(scale + 1, "0");
  if (!scale) return `${negative ? "-" : ""}${digits}`;
  const integer = digits.slice(0, -scale) || "0";
  const fraction = digits.slice(-scale);
  return `${negative ? "-" : ""}${integer}.${fraction}`;
}
