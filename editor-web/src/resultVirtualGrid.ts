import type { VNodeChild } from "vue";
import type { ViewRow } from "./resultGrid";

export interface VirtualRange {
  start: number;
  end: number;
}

export interface ColumnMetrics {
  offsets: number[];
  totalWidth: number;
}

export interface ResultVirtualColumn {
  key: string;
  label: string;
  sourceIndex: number;
  visibleIndex: number;
  width: number;
  headerRenderer: () => VNodeChild;
  readonly?: boolean;
  cellClass?: string;
  /** Optional renderer retained for integrations that need to synthesize grid events. */
  cellRenderer?: (context: { rowData: ViewRow; rowIndex: number }) => VNodeChild;
  headerCellRenderer?: () => VNodeChild;
}

export interface ResultGridScrollPosition {
  left: number;
  top: number;
}

export function columnMetrics(widths: number[]): ColumnMetrics {
  const offsets = new Array<number>(widths.length);
  let totalWidth = 0;
  widths.forEach((width, index) => {
    offsets[index] = totalWidth;
    totalWidth += Math.max(0, width);
  });
  return { offsets, totalWidth };
}

export function visibleRowRange(rowCount: number, rowHeight: number, scrollTop: number,
                                viewportHeight: number, headerHeight: number,
                                bufferScreens = 0): VirtualRange {
  if (rowCount <= 0 || rowHeight <= 0) return { start: 0, end: 0 };
  const visibleHeight = Math.max(0, viewportHeight - headerHeight);
  const bufferHeight = visibleHeight * Math.max(0, bufferScreens);
  const top = Math.max(0, scrollTop - bufferHeight);
  const bottom = Math.max(0, scrollTop) + visibleHeight + bufferHeight;
  const start = Math.min(rowCount, Math.floor(top / rowHeight));
  return {
    start,
    end: Math.max(start, Math.min(rowCount, Math.ceil(bottom / rowHeight)))
  };
}

export function visibleColumnRange(widths: number[], metrics: ColumnMetrics, scrollLeft: number,
                                   viewportWidth: number, gutterWidth: number,
                                   bufferScreens = 0): VirtualRange {
  if (!widths.length) return { start: 0, end: 0 };
  const visibleWidth = Math.max(0, viewportWidth - gutterWidth);
  const bufferWidth = visibleWidth * Math.max(0, bufferScreens);
  const left = Math.max(0, scrollLeft - bufferWidth);
  const right = Math.max(0, scrollLeft) + visibleWidth + bufferWidth;
  const first = firstColumnEndingAfter(widths, metrics.offsets, left);
  const last = firstColumnStartingAtOrAfter(metrics.offsets, right);
  return { start: first, end: Math.max(first, last) };
}

export function containsRange(container: VirtualRange, range: VirtualRange): boolean {
  return container.start <= range.start && container.end >= range.end;
}

function firstColumnEndingAfter(widths: number[], offsets: number[], position: number): number {
  let low = 0;
  let high = widths.length;
  while (low < high) {
    const middle = Math.floor((low + high) / 2);
    if (offsets[middle] + widths[middle] > position) high = middle;
    else low = middle + 1;
  }
  return Math.min(widths.length, low);
}

function firstColumnStartingAtOrAfter(offsets: number[], position: number): number {
  let low = 0;
  let high = offsets.length;
  while (low < high) {
    const middle = Math.floor((low + high) / 2);
    if (offsets[middle] >= position) high = middle;
    else low = middle + 1;
  }
  return low;
}

export function clampScroll(value: number, contentSize: number, viewportSize: number): number {
  return Math.max(0, Math.min(value, Math.max(0, contentSize - viewportSize)));
}
