import type { QueryColumn } from "./types";

export type ColumnLayoutScope = "result" | "editor";
export type DropSide = "before" | "after";
export type ColumnEdge = "left" | "right";

export interface HeaderSelection {
  selected: string[];
  anchor?: string;
}

export function clampColumnWidth(width: number, min = 72, max = 800): number {
  return Math.max(min, Math.min(max, Math.round(width)));
}

export function defaultColumnWidth(label: string): number {
  return clampColumnWidth(Array.from(label).length * 12 + 56, 120, 320);
}

export function columnIdentityKeys(columns: string[], details?: QueryColumn[]): string[] {
  const occurrences = new Map<string, number>();
  return columns.map((label, index) => {
    const detail = details?.[index];
    const base = JSON.stringify([
      detail?.catalog ?? "", detail?.schema ?? "", detail?.table ?? "", detail?.name ?? label,
      detail?.label ?? label, detail?.typeName ?? ""
    ]);
    const occurrence = occurrences.get(base) ?? 0;
    occurrences.set(base, occurrence + 1);
    return JSON.stringify([base, occurrence]);
  });
}

export function sameColumnSet(left: string[], right: string[]): boolean {
  if (left.length !== right.length) return false;
  const sortedLeft = [...left].sort();
  const sortedRight = [...right].sort();
  return sortedLeft.every((value, index) => value === sortedRight[index]);
}

export function selectHeader(order: string[], current: string[], anchor: string | undefined,
                             clicked: string, modifiers: { toggle: boolean; range: boolean }): HeaderSelection {
  if (modifiers.range && anchor && order.includes(anchor)) {
    const start = order.indexOf(anchor);
    const end = order.indexOf(clicked);
    const range = order.slice(Math.min(start, end), Math.max(start, end) + 1);
    return { selected: modifiers.toggle ? order.filter((key) => current.includes(key) || range.includes(key)) : range, anchor };
  }
  if (modifiers.toggle) {
    const selected = current.includes(clicked) ? current.filter((key) => key !== clicked) : order.filter((key) => current.includes(key) || key === clicked);
    return { selected, anchor: clicked };
  }
  return { selected: [clicked], anchor: clicked };
}

export function reorderColumns(order: string[], selected: string[], target: string, side: DropSide): string[] {
  const selectedSet = new Set(selected);
  if (!selectedSet.size || selectedSet.has(target) || !order.includes(target)) return order;
  const moving = order.filter((key) => selectedSet.has(key));
  const remaining = order.filter((key) => !selectedSet.has(key));
  const targetIndex = remaining.indexOf(target);
  if (targetIndex < 0) return order;
  const insertAt = targetIndex + (side === "after" ? 1 : 0);
  const next = [...remaining.slice(0, insertAt), ...moving, ...remaining.slice(insertAt)];
  return next.every((key, index) => key === order[index]) ? order : next;
}

export function moveColumnsToEdge(order: string[], selected: string[], edge: ColumnEdge): string[] {
  const selectedSet = new Set(selected);
  if (!selectedSet.size) return order;
  const moving = order.filter((key) => selectedSet.has(key));
  if (!moving.length) return order;
  const remaining = order.filter((key) => !selectedSet.has(key));
  const next = edge === "left" ? [...moving, ...remaining] : [...remaining, ...moving];
  return next.every((key, index) => key === order[index]) ? order : next;
}

export function sampledRowIndices(rowCount: number, maximum = 1000): number[] {
  if (rowCount <= 0) return [];
  if (rowCount <= maximum) return Array.from({ length: rowCount }, (_, index) => index);
  if (maximum <= 1) return [0];
  return Array.from({ length: maximum }, (_, index) => Math.floor(index * (rowCount - 1) / (maximum - 1)));
}

export function autoColumnWidth(label: string, rows: Array<Array<string | null>>, columnIndex: number,
                                measure: (text: string) => number): number {
  let width = measure(label);
  for (const rowIndex of sampledRowIndices(rows.length)) {
    const value = rows[rowIndex]?.[columnIndex];
    width = Math.max(width, measure(value === null || value === undefined ? "NULL" : value));
  }
  return clampColumnWidth(width + 36, 72, 600);
}
