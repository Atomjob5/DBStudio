<template>
  <div class="result-virtual-grid"
       :class="{ 'is-seeking': seeking, 'is-scrollbar-dragging': scrollbarDragging }"
       role="table" :aria-rowcount="rows.length" :aria-colcount="columns.length + 1"
       :aria-busy="seeking">
    <div class="result-virtual-grid__header" role="row" :style="headerStyle">
      <span class="result-row-number result-row-number-header result-virtual-grid__gutter"
            role="columnheader" title="单击或拖动行号选择整行">#</span>
      <div class="result-virtual-grid__header-viewport" :style="headerViewportStyle">
        <div class="result-virtual-grid__header-canvas" :style="headerCanvasStyle">
          <div v-for="entry in visibleColumns" :key="entry.slot"
               class="result-virtual-grid__header-cell" role="columnheader"
               :data-grid-column="entry.column.visibleIndex"
               :style="headerCellStyle(entry.index)">
            <span v-if="seeking" class="result-virtual-grid__seek-header" :title="entry.column.label">
              {{ entry.column.label }}
            </span>
            <StableHeaderRenderer v-else :renderer="entry.column.headerRenderer" />
          </div>
        </div>
      </div>
    </div>
    <div ref="viewport" class="result-virtual-grid__viewport" role="rowgroup"
         @scroll="handleScroll" @pointerdown.capture="detectScrollbarPointerDown"
         @pointerdown="delegatePointerDown"
         @pointerover="delegatePointerOver" @dblclick="delegateDoubleClick"
         @contextmenu="delegateContextMenu">
      <div class="result-virtual-grid__canvas" :style="canvasStyle">
        <div v-for="entry in visibleRows" :key="entry.slot"
             class="result-virtual-grid__row" role="row"
             :class="[{ 'result-row-selected': selectionMode === 'rows' && selectedRowSet.has(entry.row.sourceIndex) },
                       rowClasses?.[entry.row.sourceIndex]]"
             :style="rowStyle(entry.index)">
          <span class="result-row-number result-virtual-grid__gutter"
                :class="{ selected: selectionMode === 'rows' && selectedRowSet.has(entry.row.sourceIndex) }"
                role="rowheader" data-grid-kind="row" :data-grid-source="entry.row.sourceIndex"
                style="position: sticky; width: 34px"
                :title="`选择第 ${entry.row.sourceIndex + 1} 行；按住拖动可连续选择多行`">
            {{ entry.row.sourceIndex + 1 }}
          </span>
          <span v-for="columnEntry in visibleColumns" :key="columnEntry.slot"
                class="result-cell result-virtual-grid__cell"
                :class="seeking ? 'result-virtual-grid__cell--seeking' : cellClasses(entry.index, columnEntry.column)"
                role="cell" data-grid-kind="cell" :data-grid-row="entry.index"
                :data-grid-column="columnEntry.column.visibleIndex"
                :style="cellStyle(columnEntry.index)"
                :title="seeking ? undefined : cellTitle(entry.row, columnEntry.column)">
            <span v-if="seeking" class="result-virtual-grid__seek-bar" aria-hidden="true" />
            <input v-else-if="isEditing(entry.row, columnEntry.column)" v-focus
                   class="result-cell-editor" :value="editingValue ?? ''"
                   aria-label="编辑结果值"
                   @pointerdown.stop @dblclick.stop
                   @input="$emit('update:editing-value', ($event.target as HTMLInputElement).value)"
                   @keydown.enter.prevent.stop="$emit('commit-edit', 'enter')"
                   @keydown.esc.prevent.stop="$emit('cancel-edit')"
                   @blur="$emit('commit-edit', 'blur')" />
            <template v-else>{{ cellText(entry.row, columnEntry.column) }}</template>
          </span>
        </div>
      </div>
    </div>
    <span v-if="seeking" class="result-virtual-grid__seek-status" role="status">快速定位中…</span>
    <div v-if="hasFooter" class="result-virtual-grid__footer"><slot name="footer" /></div>
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import type { PropType, VNodeChild } from "vue";
import type { CellRange, ViewRow } from "../resultGrid";
import { normalizeRange } from "../resultGrid";
import {
  clampScroll, columnMetrics, containsRange, visibleColumnRange, visibleRowRange,
  type ResultGridScrollPosition, type ResultVirtualColumn, type VirtualRange
} from "../resultVirtualGrid";

const ROW_HEIGHT = 32;
const GUTTER_WIDTH = 34;
const SCROLLBAR_HIT_SIZE = 8;
const MAX_RENDERED_CELLS = 800;
const SEEK_JUMP_VIEWPORT_RATIO = 0.75;
const SEEK_SETTLE_MS = 80;

const StableHeaderRenderer = defineComponent({
  name: "StableHeaderRenderer",
  props: {
    renderer: { type: Function as PropType<() => VNodeChild>, required: true }
  },
  setup(componentProps) { return () => componentProps.renderer(); }
});

const props = defineProps<{
  rows: ViewRow[];
  columns: ResultVirtualColumn[];
  headerHeight: number;
  bufferScreens: number;
  selectionMode: "cells" | "rows" | "columns";
  cellRange?: CellRange;
  selectedCellKeys?: string[];
  focusedCellKey?: string;
  selectedColumnSources?: number[];
  selectedRowSources: number[];
  hasFooter?: boolean;
  editingCell?: { rowIndex: number; columnIndex: number };
  editingValue?: string | null;
  cellStates?: Record<string, "pending" | "posted" | "error">;
  rowClasses?: Record<number, string>;
}>();

const emit = defineEmits<{
  "cell-pointerdown": [event: PointerEvent, rowIndex: number, columnIndex: number];
  "cell-pointerenter": [rowIndex: number, columnIndex: number];
  "cell-contextmenu": [event: MouseEvent, rowIndex: number, columnIndex: number, row: ViewRow];
  "cell-dblclick": [rowIndex: number, columnIndex: number, row: ViewRow];
  "update:editing-value": [value: string];
  "commit-edit": [reason: "enter" | "blur" | "viewport"];
  "cancel-edit": [];
  "row-pointerdown": [event: PointerEvent, sourceIndex: number];
  "row-pointerenter": [sourceIndex: number];
  "row-contextmenu": [event: MouseEvent, sourceIndex: number];
}>();

const viewport = ref<HTMLElement>();
const rowRange = ref<VirtualRange>({ start: 0, end: 0 });
const columnRange = ref<VirtualRange>({ start: 0, end: 0 });
const rowSlots = ref<RenderSlot[]>([]);
const columnSlots = ref<RenderSlot[]>([]);
const seeking = ref(false);
const scrollbarDragging = ref(false);
const scrollLeft = ref(0);
const verticalScrollbarWidth = ref(0);
const columnWidths = computed(() => props.columns.map((column) => column.width));
const metrics = computed(() => columnMetrics(columnWidths.value));
const selectedRowSet = computed(() => new Set(props.selectedRowSources));
const selectedCellSet = computed(() => new Set(props.selectedCellKeys ?? []));
const selectedColumnSet = computed(() => new Set(props.selectedColumnSources ?? []));
const normalizedSelection = computed(() => props.cellRange ? normalizeRange(props.cellRange) : undefined);
const visibleRows = computed(() => rowSlots.value
  .filter((entry) => entry.index < props.rows.length)
  .map((entry) => ({ ...entry, row: props.rows[entry.index] })));
const visibleColumns = computed(() => columnSlots.value
  .filter((entry) => entry.index < props.columns.length)
  .map((entry) => ({ ...entry, column: props.columns[entry.index] })));
const canvasStyle = computed(() => ({
  width: `${GUTTER_WIDTH + metrics.value.totalWidth}px`,
  minWidth: "100%",
  height: `${props.rows.length * ROW_HEIGHT}px`,
  minHeight: "100%"
}));
const headerStyle = computed(() => ({
  height: `${props.headerHeight}px`
}));
const headerViewportStyle = computed(() => ({ right: `${verticalScrollbarWidth.value}px` }));
const headerCanvasStyle = computed(() => ({
  width: `${metrics.value.totalWidth}px`,
  height: `${props.headerHeight}px`,
  transform: `translate3d(${-scrollLeft.value}px, 0, 0)`
}));

let refreshFrame = 0;
let settleTimer = 0;
let resizeObserver: ResizeObserver | undefined;
let lastPointerKey = "";
let lastObservedScrollLeft = 0;
let editingCommitRequested = false;

interface RenderSlot {
  index: number;
  slot: number;
}

function reuseRenderSlots(current: RenderSlot[], range: VirtualRange): RenderSlot[] {
  const start = Math.max(0, range.start);
  const end = Math.max(start, range.end);
  const currentByIndex = new Map(current.map((entry) => [entry.index, entry.slot]));
  const kept: RenderSlot[] = [];
  const usedSlots = new Set<number>();
  for (let index = start; index < end; index++) {
    const slot = currentByIndex.get(index);
    if (slot === undefined) continue;
    kept.push({ index, slot });
    usedSlots.add(slot);
  }
  const capacity = Math.max(end - start,
    current.reduce((maximum, entry) => Math.max(maximum, entry.slot + 1), 0));
  const availableSlots: number[] = [];
  for (let slot = 0; slot < capacity; slot++) {
    if (!usedSlots.has(slot)) availableSlots.push(slot);
  }
  let availableIndex = 0;
  const result = [...kept];
  for (let index = start; index < end; index++) {
    if (currentByIndex.has(index)) continue;
    result.push({ index, slot: availableSlots[availableIndex++] });
  }
  return result.sort((left, right) => left.index - right.index);
}

function rangesFor(element: HTMLElement, bufferScreens: number): {
  rows: VirtualRange;
  columns: VirtualRange;
} {
  return {
    rows: visibleRowRange(props.rows.length, ROW_HEIGHT, element.scrollTop,
      element.clientHeight, 0, bufferScreens),
    columns: visibleColumnRange(columnWidths.value, metrics.value,
      element.scrollLeft, element.clientWidth, GUTTER_WIDTH, bufferScreens)
  };
}

function budgetedRangesFor(element: HTMLElement, maximumBufferScreens: number): {
  rows: VirtualRange;
  columns: VirtualRange;
} {
  const maximum = Math.max(0, maximumBufferScreens);
  const visible = rangesFor(element, 0);
  if (maximum === 0) return visible;
  const requested = rangesFor(element, maximum);
  if (renderedCellCount(requested) <= MAX_RENDERED_CELLS) return requested;
  let low = 0;
  let high = maximum;
  let best = visible;
  for (let attempt = 0; attempt < 8; attempt++) {
    const candidateBuffer = (low + high) / 2;
    const candidate = rangesFor(element, candidateBuffer);
    if (renderedCellCount(candidate) <= MAX_RENDERED_CELLS) {
      best = candidate;
      low = candidateBuffer;
    } else {
      high = candidateBuffer;
    }
  }
  return best;
}

function renderedCellCount(ranges: { rows: VirtualRange; columns: VirtualRange }): number {
  return Math.max(0, ranges.rows.end - ranges.rows.start)
    * Math.max(0, ranges.columns.end - ranges.columns.start);
}

function applyWindow(nextRows: VirtualRange, nextColumns: VirtualRange): void {
  commitEditingCellOutside(nextRows, nextColumns);
  if (!sameRange(rowRange.value, nextRows)) {
    rowSlots.value = reuseRenderSlots(rowSlots.value, nextRows);
    rowRange.value = nextRows;
  }
  if (!sameRange(columnRange.value, nextColumns)) {
    columnSlots.value = reuseRenderSlots(columnSlots.value, nextColumns);
    columnRange.value = nextColumns;
  }
}

function commitEditingCellOutside(nextRows: VirtualRange, nextColumns: VirtualRange): void {
  const editing = props.editingCell;
  if (!editing || editingCommitRequested
      || nextRows.end <= nextRows.start || nextColumns.end <= nextColumns.start) return;
  const rowIndex = props.rows.findIndex((row) => row.sourceIndex === editing.rowIndex);
  const columnIndex = props.columns.findIndex((column) => column.sourceIndex === editing.columnIndex);
  if (rowIndex >= nextRows.start && rowIndex < nextRows.end
      && columnIndex >= nextColumns.start && columnIndex < nextColumns.end) return;
  editingCommitRequested = true;
  emit("commit-edit", "viewport");
}

function refreshWindow(bufferScreens = seeking.value ? 0 : props.bufferScreens): void {
  refreshFrame = 0;
  const element = viewport.value;
  if (!element) return;
  const next = budgetedRangesFor(element, bufferScreens);
  applyWindow(next.rows, next.columns);
}

function scheduleWindowRefresh(): void {
  if (!refreshFrame) refreshFrame = requestFrame(() => refreshWindow());
}

function startOrContinueSeeking(firstJump: boolean): void {
  if (settleTimer) window.clearTimeout(settleTimer);
  if (firstJump) {
    seeking.value = true;
    if (refreshFrame) {
      cancelFrame(refreshFrame);
      refreshFrame = 0;
    }
    refreshWindow(0);
  } else {
    scheduleWindowRefresh();
  }
  settleTimer = window.setTimeout(finishSeeking, SEEK_SETTLE_MS);
}

function finishSeeking(): void {
  settleTimer = 0;
  if (!seeking.value) return;
  seeking.value = false;
  const element = viewport.value;
  if (element) lastObservedScrollLeft = element.scrollLeft;
  if (refreshFrame) {
    cancelFrame(refreshFrame);
    refreshFrame = 0;
  }
  refreshWindow(props.bufferScreens);
}

function cancelSeeking(): void {
  if (settleTimer) {
    window.clearTimeout(settleTimer);
    settleTimer = 0;
  }
  if (refreshFrame) {
    cancelFrame(refreshFrame);
    refreshFrame = 0;
  }
  seeking.value = false;
}

function handleScroll(): void {
  const element = viewport.value;
  if (!element) return;
  scrollLeft.value = element.scrollLeft;
  const horizontalJump = element.clientWidth > 0
    && Math.abs(element.scrollLeft - lastObservedScrollLeft)
      >= element.clientWidth * SEEK_JUMP_VIEWPORT_RATIO;
  lastObservedScrollLeft = element.scrollLeft;
  if (horizontalJump || seeking.value) {
    startOrContinueSeeking(horizontalJump && !seeking.value);
    return;
  }
  const visible = rangesFor(element, 0);
  if (!containsRange(rowRange.value, visible.rows)
      || !containsRange(columnRange.value, visible.columns)) {
    if (refreshFrame) {
      cancelFrame(refreshFrame);
      refreshFrame = 0;
    }
    refreshWindow();
    return;
  }
  const safe = budgetedRangesFor(element, props.bufferScreens / 2);
  if (!containsRange(rowRange.value, safe.rows)
      || !containsRange(columnRange.value, safe.columns)) {
    scheduleWindowRefresh();
  }
}

function sameRange(left: VirtualRange, right: VirtualRange): boolean {
  return left.start === right.start && left.end === right.end;
}

function rowStyle(index: number): Record<string, string> {
  return {
    top: `${index * ROW_HEIGHT}px`,
    width: `${GUTTER_WIDTH + metrics.value.totalWidth}px`,
    height: `${ROW_HEIGHT}px`
  };
}

function headerCellStyle(index: number): Record<string, string> {
  return {
    left: `${metrics.value.offsets[index]}px`,
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
  const row = props.rows[rowIndex];
  const value = row ? cellValue(row, column) : null;
  const range = normalizedSelection.value;
  const selectedByIdentity = !!row && selectedCellSet.value.has(`${row.sourceIndex}:${column.sourceIndex}`);
  const selectedByRange = !!range
    && rowIndex >= range.start.row && rowIndex <= range.end.row
    && column.visibleIndex >= range.start.column && column.visibleIndex <= range.end.column;
  const selected = props.selectionMode === "cells" && (selectedByIdentity || selectedByRange);
  const selectedColumn = props.selectionMode === "columns" && selectedColumnSet.value.has(column.sourceIndex);
  const focused = props.selectionMode === "cells" && !!row
    && props.focusedCellKey === `${row.sourceIndex}:${column.sourceIndex}`;
  const state = row ? props.cellStates?.[`${row.sourceIndex}:${column.sourceIndex}`] : undefined;
  return [value === null ? "null-value" : "", value?.startsWith("0x") ? "binary-value" : "",
    selectedColumn && "column-selected", selected && "selected", focused && "focused", state === "pending" && "result-cell-pending",
    state === "posted" && "result-cell-posted", state === "error" && "result-cell-error"];
}

function isEditing(row: ViewRow, column: ResultVirtualColumn): boolean {
  return props.editingCell?.rowIndex === row.sourceIndex
    && props.editingCell?.columnIndex === column.sourceIndex;
}

const vFocus = {
  mounted(element: HTMLInputElement) { element.focus(); element.select(); }
};

function delegatedTarget(event: Event): HTMLElement | undefined {
  const target = event.target instanceof Element ? event.target.closest<HTMLElement>("[data-grid-kind]") : null;
  return target && viewport.value?.contains(target) ? target : undefined;
}

function detectScrollbarPointerDown(event: PointerEvent): void {
  const element = viewport.value;
  if (event.button !== 0 || !element || !isScrollbarHit(element, event)) return;
  scrollbarDragging.value = true;
  lastPointerKey = "";
  window.removeEventListener("pointerup", finishScrollbarDrag, true);
  window.removeEventListener("pointercancel", finishScrollbarDrag, true);
  window.addEventListener("pointerup", finishScrollbarDrag, { capture: true, once: true });
  window.addEventListener("pointercancel", finishScrollbarDrag, { capture: true, once: true });
}

function isScrollbarHit(element: HTMLElement, event: PointerEvent): boolean {
  const bounds = element.getBoundingClientRect();
  const nativeVerticalWidth = Math.max(0, element.offsetWidth - element.clientWidth);
  const nativeHorizontalHeight = Math.max(0, element.offsetHeight - element.clientHeight);
  const verticalSize = Math.max(SCROLLBAR_HIT_SIZE, nativeVerticalWidth);
  const horizontalSize = Math.max(SCROLLBAR_HIT_SIZE, nativeHorizontalHeight);
  const vertical = element.scrollHeight > element.clientHeight
    && event.clientX >= bounds.right - verticalSize && event.clientX <= bounds.right;
  const horizontal = element.scrollWidth > element.clientWidth
    && event.clientY >= bounds.bottom - horizontalSize && event.clientY <= bounds.bottom;
  return vertical || horizontal;
}

function finishScrollbarDrag(): void {
  scrollbarDragging.value = false;
  lastPointerKey = "";
  window.removeEventListener("pointerup", finishScrollbarDrag, true);
  window.removeEventListener("pointercancel", finishScrollbarDrag, true);
}

function interactionsSuspended(): boolean {
  return seeking.value || scrollbarDragging.value;
}

function delegatePointerDown(event: PointerEvent): void {
  if (interactionsSuspended()) return;
  const target = delegatedTarget(event);
  if (!target) return;
  if (target.dataset.gridKind === "row") {
    emit("row-pointerdown", event, Number(target.dataset.gridSource));
    return;
  }
  emit("cell-pointerdown", event, Number(target.dataset.gridRow), Number(target.dataset.gridColumn));
}

function delegatePointerOver(event: PointerEvent): void {
  if (interactionsSuspended()) return;
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
  if (interactionsSuspended()) return;
  const target = delegatedTarget(event);
  if (!target) return;
  if (target.dataset.gridKind === "row") {
    emit("row-contextmenu", event, Number(target.dataset.gridSource));
    return;
  }
  const rowIndex = Number(target.dataset.gridRow);
  emit("cell-contextmenu", event, rowIndex, Number(target.dataset.gridColumn), props.rows[rowIndex]);
}

function delegateDoubleClick(event: MouseEvent): void {
  if (interactionsSuspended()) return;
  const target = delegatedTarget(event);
  if (!target || target.dataset.gridKind !== "cell") return;
  const rowIndex = Number(target.dataset.gridRow);
  emit("cell-dblclick", rowIndex, Number(target.dataset.gridColumn), props.rows[rowIndex]);
}

function getScrollPosition(): ResultGridScrollPosition {
  return { left: viewport.value?.scrollLeft ?? 0, top: viewport.value?.scrollTop ?? 0 };
}

function setScrollPosition(position: ResultGridScrollPosition): void {
  const element = viewport.value;
  if (!element) return;
  cancelSeeking();
  element.scrollLeft = clampScroll(position.left, GUTTER_WIDTH + metrics.value.totalWidth, element.clientWidth);
  element.scrollTop = clampScroll(position.top, props.rows.length * ROW_HEIGHT, element.clientHeight);
  scrollLeft.value = element.scrollLeft;
  lastObservedScrollLeft = element.scrollLeft;
  updateViewportMeasurements();
  refreshWindow();
}

function scrollCellIntoView(rowIndex: number, columnIndex: number): void {
  const element = viewport.value;
  const column = props.columns[columnIndex];
  if (!element || rowIndex < 0 || rowIndex >= props.rows.length || !column) return;
  const rowTop = rowIndex * ROW_HEIGHT;
  const rowBottom = rowTop + ROW_HEIGHT;
  const columnLeft = metrics.value.offsets[columnIndex];
  const columnRight = columnLeft + column.width;
  const availableWidth = Math.max(0, element.clientWidth - GUTTER_WIDTH);
  let nextTop = element.scrollTop;
  let nextLeft = element.scrollLeft;
  if (rowTop < nextTop) nextTop = rowTop;
  else if (rowBottom > nextTop + element.clientHeight) nextTop = rowBottom - element.clientHeight;
  if (column.width > availableWidth || columnLeft < nextLeft) nextLeft = columnLeft;
  else if (columnRight > nextLeft + availableWidth) nextLeft = columnRight - availableWidth;
  if (nextTop === element.scrollTop && nextLeft === element.scrollLeft) return;
  setScrollPosition({ left: nextLeft, top: nextTop });
}

function normalizeScrollPosition(): void {
  const element = viewport.value;
  if (!element) return;
  cancelSeeking();
  element.scrollLeft = clampScroll(element.scrollLeft,
    GUTTER_WIDTH + metrics.value.totalWidth, element.clientWidth);
  element.scrollTop = clampScroll(element.scrollTop, props.rows.length * ROW_HEIGHT, element.clientHeight);
  scrollLeft.value = element.scrollLeft;
  lastObservedScrollLeft = element.scrollLeft;
  updateViewportMeasurements();
  refreshWindow();
}

function updateViewportMeasurements(): void {
  const element = viewport.value;
  if (!element) return;
  verticalScrollbarWidth.value = element.offsetWidth > 0
    ? Math.max(0, element.offsetWidth - element.clientWidth)
    : 0;
}

function handleViewportResize(): void {
  updateViewportMeasurements();
  scheduleWindowRefresh();
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
  () => props.headerHeight, () => props.bufferScreens], () => void nextTick(normalizeScrollPosition));
watch(() => props.editingCell, () => { editingCommitRequested = false; });

onMounted(() => {
  const element = viewport.value;
  if (typeof ResizeObserver !== "undefined" && element) {
    resizeObserver = new ResizeObserver(handleViewportResize);
    resizeObserver.observe(element);
  }
  updateViewportMeasurements();
  scrollLeft.value = element?.scrollLeft ?? 0;
  lastObservedScrollLeft = element?.scrollLeft ?? 0;
  refreshWindow();
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  if (refreshFrame) cancelFrame(refreshFrame);
  if (settleTimer) window.clearTimeout(settleTimer);
  finishScrollbarDrag();
});

defineExpose({ getScrollPosition, setScrollPosition, scrollCellIntoView });
</script>

<style scoped>
.result-virtual-grid {
  position: relative;
  display: flex;
  width: 100%;
  height: 100%;
  min-height: 0;
  flex-direction: column;
}
.result-virtual-grid__viewport {
  position: relative;
  width: 100%;
  min-height: 0;
  flex: 1;
  overflow: auto;
  contain: layout paint style;
  scrollbar-width: thin;
}
.result-virtual-grid__canvas { position: relative; }
.result-virtual-grid__header {
  position: relative;
  z-index: 5;
  width: 100%;
  flex: none;
  overflow: hidden;
  background: var(--db-table-header);
  color: var(--db-text-secondary);
  font-weight: 600;
}
.result-virtual-grid__header-viewport {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 34px;
  overflow: hidden;
}
.result-virtual-grid__header-canvas {
  position: relative;
  transform-origin: left top;
  will-change: transform;
}
.result-virtual-grid__header-cell {
  position: absolute;
  top: 0;
  overflow: hidden;
}
.result-virtual-grid__seek-header {
  box-sizing: border-box;
  display: flex;
  width: 100%;
  height: 100%;
  padding: 0 10px;
  align-items: center;
  overflow: hidden;
  color: var(--db-text-secondary);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 1.2;
}
.result-virtual-grid__row {
  position: absolute;
  left: 0;
  border-bottom: 1px solid var(--db-border-soft);
}
.result-virtual-grid__row:hover { background: var(--db-accent-soft); }
.result-virtual-grid.is-seeking .result-virtual-grid__row:hover,
.result-virtual-grid.is-scrollbar-dragging .result-virtual-grid__row:hover { background: transparent; }
.result-virtual-grid__row.result-row-selected,
.result-virtual-grid__row.result-row-selected:hover { background: var(--db-accent-soft); }
.result-virtual-grid__row.result-row-inserted { background: color-mix(in srgb, var(--el-color-success) 10%, transparent); }
.result-virtual-grid__row.result-row-inserted-applied { background: color-mix(in srgb, var(--el-color-success) 6%, transparent); }
.result-virtual-grid__row.result-row-deleted { background: color-mix(in srgb, var(--el-color-danger) 9%, transparent); opacity: .72; }
.result-virtual-grid__row.result-row-deleted .result-cell { text-decoration: line-through; }
.result-virtual-grid.is-scrollbar-dragging .result-virtual-grid__row { pointer-events: none; }
.result-virtual-grid__gutter {
  position: sticky;
  z-index: 3;
  left: 0;
  width: 34px;
}
.result-virtual-grid__header .result-virtual-grid__gutter {
  position: absolute;
  z-index: 6;
  top: 0;
  left: 0;
  width: 34px;
  background: color-mix(in srgb, var(--db-content) 94%, var(--db-muted) 6%);
}
.result-virtual-grid__row .result-virtual-grid__gutter {
  position: sticky;
  z-index: 3;
  left: 0;
  background: color-mix(in srgb, var(--db-content) 97%, var(--db-muted) 3%);
}
.result-virtual-grid__row:hover .result-virtual-grid__gutter,
.result-virtual-grid__row.result-row-selected .result-virtual-grid__gutter {
  background: color-mix(in srgb, var(--db-content) 90%, var(--db-accent) 10%);
}
.result-virtual-grid.is-seeking .result-virtual-grid__row:hover .result-virtual-grid__gutter,
.result-virtual-grid.is-scrollbar-dragging .result-virtual-grid__row:hover .result-virtual-grid__gutter {
  background: color-mix(in srgb, var(--db-content) 97%, var(--db-muted) 3%);
}
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
.result-virtual-grid__cell--seeking {
  pointer-events: none;
}
.result-virtual-grid__seek-bar {
  display: block;
  width: min(68%, 92px);
  height: 8px;
  margin: 9px 7px;
  border-radius: 5px;
  background: color-mix(in srgb, var(--db-muted) 16%, transparent);
}
.result-virtual-grid__seek-status {
  position: absolute;
  z-index: 9;
  right: 14px;
  bottom: 12px;
  padding: 4px 9px;
  border: 1px solid var(--db-border-soft);
  border-radius: 9px;
  background: color-mix(in srgb, var(--db-content) 92%, transparent);
  color: var(--db-muted);
  box-shadow: var(--db-shadow-sm);
  font-size: 10px;
  pointer-events: none;
}
.result-cell-editor {
  box-sizing: border-box;
  width: 100%;
  height: 26px;
  border: 1px solid var(--db-accent);
  border-radius: 3px;
  outline: 0;
  background: var(--db-content);
  color: var(--db-text);
  font: inherit;
}
.result-cell-pending {
  background: color-mix(in srgb, var(--db-warning) 20%, transparent);
  box-shadow: inset 3px 0 0 var(--db-warning);
}
.result-cell-posted {
  background: color-mix(in srgb, var(--db-accent) 14%, transparent);
  box-shadow: inset 3px 0 0 var(--db-accent);
}
.result-cell-error {
  background: color-mix(in srgb, var(--el-color-danger) 13%, transparent);
  box-shadow: inset 0 0 0 1px var(--el-color-danger);
}
.result-virtual-grid__footer { flex: none; }
.result-virtual-grid__viewport::-webkit-scrollbar { width: 8px; height: 8px; }
.result-virtual-grid__viewport::-webkit-scrollbar-thumb {
  border: 2px solid transparent;
  border-radius: 8px;
  background: color-mix(in srgb, var(--db-muted) 48%, transparent);
  background-clip: padding-box;
}
</style>
