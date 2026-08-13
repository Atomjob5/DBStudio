import { cellSelectionKey, type ViewRow } from "./resultGrid";

export type ResultCompareHighlightMode = "identical" | "different";
export type ResultCompareScope = "column" | "record";

export interface ResultCompareOptions {
  highlightMode: ResultCompareHighlightMode;
  scope: ResultCompareScope;
  caseSensitive: boolean;
}

export interface ResultCompareAnchor {
  sourceRow: number;
  sourceColumn: number;
}

export function resultValuesEqual(left: string | null, right: string | null,
                                  caseSensitive: boolean): boolean {
  if (left === null || right === null) return left === right;
  return caseSensitive ? left === right : left.toLocaleLowerCase() === right.toLocaleLowerCase();
}

export function comparisonCellKeys(rows: ViewRow[], visibleColumns: number[], anchor: ResultCompareAnchor,
                                   options: ResultCompareOptions): string[] {
  const baseline = rows.find((row) => row.sourceIndex === anchor.sourceRow);
  if (!baseline) return [];
  const columns = options.scope === "column" ? [anchor.sourceColumn] : visibleColumns;
  const keys: string[] = [];
  for (const row of rows) {
    if (row.sourceIndex === anchor.sourceRow) continue;
    for (const column of columns) {
      const equal = resultValuesEqual(row.cells[column] ?? null, baseline.cells[column] ?? null,
        options.caseSensitive);
      if ((options.highlightMode === "identical") === equal) {
        keys.push(cellSelectionKey(row.sourceIndex, column));
      }
    }
  }
  return keys;
}
