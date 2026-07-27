<template>
  <div ref="viewport" class="result-virtual-grid__viewport" role="table"
       :aria-rowcount="rows.length" :aria-colcount="columns.length + 1"
       @scroll="scheduleWindowRefresh" @pointerdown="delegatePointerDown"
       @pointerover="delegatePointerOver" @contextmenu="delegateContextMenu">
    <div class="result-virtual-grid__canvas" :style="canvasStyle">
      <div class="result-virtual-grid__header" role="row" :style="headerStyle">
        <span class="result-row-number result-row-number-header result-virtual-grid__gutter"
              role="columnheader" title="单击或拖动行号选择整行"
              style="position: sticky; width: 34px">#</span>
        <div v-for="entry in visibleColumns" :key="entry.column.key"
             class="result-virtual-grid__header-cell" role="columnheader"
             :data-grid-column="entry.column.visibleIndex"
             :style="headerCellStyle(entry.index)">
          <component :is="entry.column.headerRenderer" />
        </div>
      </div>

      <div v-for="entry in visibleRows" :key="entry.row.sourceIndex"
           class="result-virtual-grid__row" role="row" :style="rowStyle(entry.index)">
        <span class="result-row-number result-virtual-grid__gutter"
              :class="{ selected: selectionMode === 'rows' && selectedRowSet.has(entry.row.sourceIndex) }"
              role="rowheader" data-grid-kind="row" :data-grid-source="entry.row.sourceIndex"
              style="position: sticky; width: 34px"
              :title="`选择第 ${entry.row.sourceIndex + 1} 行；按住拖动可连续选择多行`">
          {{ entry.row.sourceIndex + 1 }}
        </span>
        <span v-for="columnEntry in visibleColumns" :key="columnEntry.column.key"
              class="result-cell result-virtual-grid__cell"
              :class="cellClasses(entry.index, columnEntry.column)"
              role="cell" data-grid-kind="cell" :data-grid-row="entry.index"
              :data-grid-column="columnEntry.column.visibleIndex"
              :style="cellStyle(columnEntry.index)"
              :title="cellTitle(entry.row, columnEntry.column)">
          {{ cellText(entry.row, columnEntry.column) }}
        </span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import type { CellRange, ViewRow } from "../resultGrid";
import { normalizeRange } from "../resultGrid";
import {
  clampScroll, columnMetrics, visibleColumnRange, visibleRowRange,
  type ResultGridScrollPosition, type ResultVirtualColumn, type VirtualRange
} from "../resultVirtualGrid";

const ROW_HEIGHT = 32;
const GUTTER_WIDTH = 34;

const props = defineProps<{
  rows: ViewRow[];
  columns: ResultVirtualColumn[];
  headerHeight: number;
  selectionMode: "cells" | "rows";
  cellRange?: CellRange;
  selectedRowSources: number[];
}>();

const emit = defineEmits<{
  "cell-pointerdown": [event: PointerEvent, rowIndex: number, columnIndex: number];
  "cell-pointerenter": [rowIndex: number, columnIndex: number];
  "cell-contextmenu": [event: MouseEvent, rowIndex: number, columnIndex: number, row: ViewRow];
  "row-pointerdown": [event: PointerEvent, sourceIndex: number];
  "row-pointerenter": [sourceIndex: number];
  "row-contextmenu": [event: MouseEvent, sourceIndex: number];
}>();

const viewport = ref<HTMLElement>();
const rowRange = ref<VirtualRange>({ start: 0, end: 0 });
const columnRange = ref<VirtualRange>({ start: 0, end: 0 });
const metrics = computed(() => columnMetrics(props.columns.map((column) => column.width)));
const selectedRowSet = computed(() => new Set(props.selectedRowSources));
const normalizedSelection = computed(() => props.cellRange ? normalizeRange(props.cellRange) : undefined);
const visibleRows = computed(() => rangeEntries(props.rows, rowRange.value));
const visibleColumns = computed(() => rangeEntries(props.columns, columnRange.value)
  .map((entry) => ({ index: entry.index, column: entry.value })));
const canvasStyle = computed(() => ({
  width: `${GUTTER_WIDTH + metrics.value.totalWidth}px`,
  minWidth: "100%",
  height: `${props.headerHeight + props.rows.length * ROW_HEIGHT}px`,
  minHeight: "100%"
}));
const headerStyle = computed(() => ({
  width: `${GUTTER_WIDTH + metrics.value.totalWidth}px`,
  height: `${props.headerHeight}px`
}));

let refreshFrame = 0;
let resizeObserver: ResizeObserver | undefined;
let lastPointerKey = "";

function rangeEntries<T>(values: T[], range: VirtualRange): Array<{ index: number; value: T; row: T }> {
  const entries: Array<{ index: number; value: T; row: T }> = [];
  const start = Math.max(0, Math.min(values.length, range.start));
  const end = Math.max(start, Math.min(values.length, range.end));
  for (let index = start; index < end; index++) {
    entries.push({ index, value: values[index], row: values[index] });
  }
  return entries;
}

function refreshWindow(): void {
  refreshFrame = 0;
  const element = viewport.value;
  if (!element) return;
  const nextRows = visibleRowRange(props.rows.length, ROW_HEIGHT, element.scrollTop,
    element.clientHeight, props.headerHeight);
  const nextColumns = visibleColumnRange(props.columns.map((column) => column.width), metrics.value,
    element.scrollLeft, element.clientWidth, GUTTER_WIDTH);
  if (!sameRange(rowRange.value, nextRows)) rowRange.value = nextRows;
  if (!sameRange(columnRange.value, nextColumns)) columnRange.value = nextColumns;
}

function scheduleWindowRefresh(): void {
  if (!refreshFrame) refreshFrame = requestFrame(refreshWindow);
}

function sameRange(left: VirtualRange, right: VirtualRange): boolean {
  return left.start === right.start && left.end === right.end;
}

function rowStyle(index: number): Record<string, string> {
  return {
    top: `${props.headerHeight + index * ROW_HEIGHT}px`,
    width: `${GUTTER_WIDTH + metrics.value.totalWidth}px`,
    height: `${ROW_HEIGHT}px`
  };
}

function headerCellStyle(index: number): Record<string, string> {
  return {
    left: `${GUTTER_WIDTH + metrics.value.offsets[index]}px`,
    width: `${props.columns[index].width}px`,
    height: `${props.headerHeight}px`
  };
}

function cellStyle(index: number): Record<string, string> {
  return {
    left: `${GUTTER_WIDTH + metrics.value.offsets[index]}px`,
    width: `${Math.max(0, props.columns[index].width - 6)}px`
  };
}

function cellValue(row: ViewRow, column: ResultVirtualColumn): string | null {
  return row.cells[column.sourceIndex] ?? null;
}

function cellText(row: ViewRow, column: ResultVirtualColumn): string {
  return cellValue(row, column) ?? "NULL";
}

function cellTitle(row: ViewRow, column: ResultVirtualColumn): string | undefined {
  const value = cellValue(row, column);
  return value !== null && value.length >= 40 ? value : undefined;
}

function cellClasses(rowIndex: number, column: ResultVirtualColumn): Array<string | false> {
  const value = props.rows[rowIndex] ? cellValue(props.rows[rowIndex], column) : null;
  const range = normalizedSelection.value;
  const selected = props.selectionMode === "cells" && !!range
    && rowIndex >= range.start.row && rowIndex <= range.end.row
    && column.visibleIndex >= range.start.column && column.visibleIndex <= range.end.column;
  return [value === null ? "null-value" : "", value?.startsWith("0x") ? "binary-value" : "", selected && "selected"];
}

function delegatedTarget(event: Event): HTMLElement | undefined {
  const target = event.target instanceof Element ? event.target.closest<HTMLElement>("[data-grid-kind]") : null;
  return target && viewport.value?.contains(target) ? target : undefined;
}

function delegatePointerDown(event: PointerEvent): void {
  const target = delegatedTarget(event);
  if (!target) return;
  if (target.dataset.gridKind === "row") {
    emit("row-pointerdown", event, Number(target.dataset.gridSource));
    return;
  }
  emit("cell-pointerdown", event, Number(target.dataset.gridRow), Number(target.dataset.gridColumn));
}

function delegatePointerOver(event: PointerEvent): void {
  const target = delegatedTarget(event);
  if (!target) return;
  const key = target.dataset.gridKind === "row"
    ? `r:${target.dataset.gridSource}`
    : `c:${target.dataset.gridRow}:${target.dataset.gridColumn}`;
  if (key === lastPointerKey) return;
  lastPointerKey = key;
  if (target.dataset.gridKind === "row") {
    emit("row-pointerenter", Number(target.dataset.gridSource));
    return;
  }
  emit("cell-pointerenter", Number(target.dataset.gridRow), Number(target.dataset.gridColumn));
}

function delegateContextMenu(event: MouseEvent): void {
  const target = delegatedTarget(event);
  if (!target) return;
  if (target.dataset.gridKind === "row") {
    emit("row-contextmenu", event, Number(target.dataset.gridSource));
    return;
  }
  const rowIndex = Number(target.dataset.gridRow);
  emit("cell-contextmenu", event, rowIndex, Number(target.dataset.gridColumn), props.rows[rowIndex]);
}

function getScrollPosition(): ResultGridScrollPosition {
  return { left: viewport.value?.scrollLeft ?? 0, top: viewport.value?.scrollTop ?? 0 };
}

function setScrollPosition(position: ResultGridScrollPosition): void {
  const element = viewport.value;
  if (!element) return;
  element.scrollLeft = clampScroll(position.left, GUTTER_WIDTH + metrics.value.totalWidth, element.clientWidth);
  element.scrollTop = clampScroll(position.top,
    props.headerHeight + props.rows.length * ROW_HEIGHT, element.clientHeight);
  refreshWindow();
}

function normalizeScrollPosition(): void {
  const element = viewport.value;
  if (!element) return;
  element.scrollLeft = clampScroll(element.scrollLeft,
    GUTTER_WIDTH + metrics.value.totalWidth, element.clientWidth);
  element.scrollTop = clampScroll(element.scrollTop,
    props.headerHeight + props.rows.length * ROW_HEIGHT, element.clientHeight);
  refreshWindow();
}

function requestFrame(callback: FrameRequestCallback): number {
  return typeof window.requestAnimationFrame === "function"
    ? window.requestAnimationFrame(callback)
    : window.setTimeout(() => callback(performance.now()), 16);
}

function cancelFrame(frame: number): void {
  if (typeof window.cancelAnimationFrame === "function") window.cancelAnimationFrame(frame);
  else window.clearTimeout(frame);
}

watch([() => props.rows.length, () => props.columns.map((column) => `${column.key}:${column.width}`).join(","),
  () => props.headerHeight], () => void nextTick(normalizeScrollPosition));

onMounted(() => {
  const element = viewport.value;
  if (typeof ResizeObserver !== "undefined" && element) {
    resizeObserver = new ResizeObserver(scheduleWindowRefresh);
    resizeObserver.observe(element);
  }
  refreshWindow();
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  if (refreshFrame) cancelFrame(refreshFrame);
});

defineExpose({ getScrollPosition, setScrollPosition });
</script>

<style scoped>
.result-virtual-grid__viewport {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: auto;
  contain: layout paint style;
  scrollbar-width: thin;
}
.result-virtual-grid__canvas { position: relative; }
.result-virtual-grid__header {
  position: sticky;
  z-index: 5;
  top: 0;
  background: var(--db-table-header);
  color: var(--db-text-secondary);
  font-weight: 600;
}
.result-virtual-grid__header-cell {
  position: absolute;
  top: 0;
  overflow: hidden;
}
.result-virtual-grid__row {
  position: absolute;
  left: 0;
  border-bottom: 1px solid var(--db-border-soft);
}
.result-virtual-grid__row:hover { background: var(--db-accent-soft); }
.result-virtual-grid__gutter {
  position: sticky;
  z-index: 3;
  left: 0;
  width: 34px;
}
.result-virtual-grid__header .result-virtual-grid__gutter { z-index: 6; }
.result-virtual-grid__gutter::after {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: 1px;
  background: var(--db-row-gutter-divider);
  content: "";
  pointer-events: none;
}
.result-virtual-grid__cell {
  position: absolute;
  top: 2px;
}
.result-virtual-grid__viewport::-webkit-scrollbar { width: 8px; height: 8px; }
.result-virtual-grid__viewport::-webkit-scrollbar-thumb {
  border: 2px solid transparent;
  border-radius: 8px;
  background: color-mix(in srgb, var(--db-muted) 48%, transparent);
  background-clip: padding-box;
}
</style>
