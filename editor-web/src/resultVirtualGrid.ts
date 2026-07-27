import type { Component } from "vue";

export interface VirtualRange {
  start: number;
  end: number;
}

export interface ColumnMetrics {
  offsets: number[];
  totalWidth: number;
}

export interface WheelDelta {
  x: number;
  y: number;
}

export interface ResultVirtualColumn {
  key: string;
  sourceIndex: number;
  visibleIndex: number;
  width: number;
  headerRenderer: Component;
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
                                viewportHeight: number, headerHeight: number, overscan = 4): VirtualRange {
  if (rowCount <= 0 || rowHeight <= 0) return { start: 0, end: 0 };
  const visibleHeight = Math.max(0, viewportHeight - headerHeight);
  const first = Math.floor(Math.max(0, scrollTop) / rowHeight);
  const last = Math.ceil((Math.max(0, scrollTop) + visibleHeight) / rowHeight);
  const start = Math.min(rowCount, Math.max(0, first - Math.max(0, overscan)));
  return {
    start,
    end: Math.max(start, Math.min(rowCount, last + Math.max(0, overscan)))
  };
}

export function visibleColumnRange(widths: number[], metrics: ColumnMetrics, scrollLeft: number,
                                   viewportWidth: number, gutterWidth: number, overscan = 2): VirtualRange {
  if (!widths.length) return { start: 0, end: 0 };
  const left = Math.max(0, scrollLeft);
  const right = left + Math.max(0, viewportWidth - gutterWidth);
  let first = firstColumnEndingAfter(widths, metrics.offsets, left);
  let last = first;
  while (last < widths.length && metrics.offsets[last] < right) last++;
  first = Math.max(0, first - Math.max(0, overscan));
  last = Math.min(widths.length, last + Math.max(0, overscan));
  return { start: first, end: Math.max(first, last) };
}

function firstColumnEndingAfter(widths: number[], offsets: number[], position: number): number {
  let low = 0;
  let high = widths.length;
  while (low < high) {
    const middle = Math.floor((low + high) / 2);
    if (offsets[middle] + widths[middle] > position) high = middle;
    else low = middle + 1;
  }
  return Math.min(widths.length - 1, low);
}

export function normalizedWheelDelta(deltaX: number, deltaY: number, deltaMode: number,
                                     shiftKey: boolean, lineHeight: number,
                                     pageWidth: number, pageHeight: number): WheelDelta {
  const multiplierX = deltaMode === 1 ? lineHeight : deltaMode === 2 ? pageWidth : 1;
  const multiplierY = deltaMode === 1 ? lineHeight : deltaMode === 2 ? pageHeight : 1;
  let x = deltaX * multiplierX;
  let y = deltaY * multiplierY;
  if (shiftKey && Math.abs(x) < Math.abs(y)) {
    x = y;
    y = 0;
  }
  return { x, y };
}

export function shouldAnimateWheel(deltaMode: number, delta: WheelDelta): boolean {
  return deltaMode !== 0 || Math.max(Math.abs(delta.x), Math.abs(delta.y)) >= 40;
}

export function easeOutCubic(progress: number): number {
  const bounded = Math.max(0, Math.min(1, progress));
  return 1 - Math.pow(1 - bounded, 3);
}

export function clampScroll(value: number, contentSize: number, viewportSize: number): number {
  return Math.max(0, Math.min(value, Math.max(0, contentSize - viewportSize)));
}
