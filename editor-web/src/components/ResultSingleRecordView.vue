<template>
  <div ref="root" class="result-single-record-view" aria-label="单个记录查看" tabindex="0"
       @pointerdown.capture="focusRoot" @pointermove="handlePointerMove" @keydown.stop="handleKeydown">
    <ResultVirtualGrid
      ref="grid"
      :rows="displayFields"
      :columns="gridColumns"
      :header-height="32"
      :buffer-screens="bufferScreens"
      :selection-mode="selectionMode"
      :selected-cell-keys="selectedCellKeys"
      :focused-cell-key="focusedCellKey"
      :selected-column-sources="selectedColumnSources"
      :selected-row-sources="selectedRowSources"
      :zebra-stripes-enabled="zebraStripesEnabled"
      :editing-cell="editingGridCell"
      :editing-value="editingValue"
      :cell-states="gridCellStates"
      @cell-pointerdown="startCellSelection"
      @cell-pointerenter="extendCellSelection"
      @cell-contextmenu="forwardCellContextmenu"
      @cell-dblclick="forwardCellDblclick"
      @row-pointerdown="startRowSelection"
      @row-pointerenter="extendRowSelection"
      @row-contextmenu="forwardRowContextmenu"
      @update:editing-value="$emit('update:editing-value', $event)"
      @commit-edit="$emit('commit-edit', $event)"
      @cancel-edit="$emit('cancel-edit')"
    >
      <template #footer><slot name="footer" /></template>
    </ResultVirtualGrid>
    <ResultHeaderContextMenu :visible="headerMenu.visible" :x="headerMenu.x" :y="headerMenu.y"
                             :can-copy-data="canCopyHeaderData" :can-in="canCopyHeaderIn"
                             :can-move-left="canMoveLeft" :can-move-right="canMoveRight"
                             :can-sum="canSumHeaderData" @close="closeHeaderMenu"
                             @command="handleHeaderCommand" />
  </div>
</template>

<script setup lang="ts">
import { computed, h, onBeforeUnmount, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { getActivePinia } from "pinia";
import type { QueryColumn, QueryResult } from "../types";
import type { ColumnOption } from "../columnFilter";
import {
  autoColumnWidth, clampColumnWidth, columnIdentityKeys, defaultColumnWidth, moveColumnsToEdge,
  reorderColumns, selectHeader, type ColumnEdge, type ColumnLayoutScope, type DropSide
} from "../columnLayout";
import { useColumnLayoutStore, type LayoutContext } from "../stores/columnLayout";
import {
  compareValues, copyGrid, copyInPredicate, matchesFilter, sumDecimalValues,
  type CellPoint, type ResultFilter, type ResultSort, type ViewRow
} from "../resultGrid";
import type { CopySeparator } from "../resultCopy";
import { resultColumnRemarksText } from "../resultCopy";
import type { ResultGridScrollPosition, ResultVirtualColumn } from "../resultVirtualGrid";
import ResultHeaderContextMenu, { type HeaderMenuCommand } from "./ResultHeaderContextMenu.vue";
import ResultHeaderTools from "./ResultHeaderTools.vue";
import ResultVirtualGrid from "./ResultVirtualGrid.vue";

export interface SingleRecordSourceCell {
  sourceColumn: number;
  value: string | null;
}

export interface SingleRecordSelectionState {
  mode: "cells" | "rows" | "columns";
  cellKeys: string[];
  rowSources: number[];
  columnSources: number[];
  focusedCellKey?: string;
  sourceCells: SingleRecordSourceCell[];
  selectedFieldIndices: number[];
  hasSelection: boolean;
  sqlAllowed: boolean;
  statusText: string;
}

const SYNTHETIC_LABELS = ["字段名", "字段值", "字段备注", "字段类型"];
const SYNTHETIC_DEFAULT_WIDTHS = [180, 300, 220, 160];
const SYNTHETIC_QUERY_COLUMNS: QueryColumn[] = SYNTHETIC_LABELS.map((label, index) => ({
  label, name: label, remarks: "", catalog: "", schema: "", table: "",
  typeName: index === 1 ? "VARCHAR" : "VARCHAR", jdbcType: 12
}));

const props = withDefaults(defineProps<{
  columns: ColumnOption[];
  row: ViewRow;
  bufferScreens?: number;
  copySeparator?: CopySeparator;
  layoutScope?: ColumnLayoutScope;
  executionId?: string;
  editorId?: string;
  resultIndex?: number;
  headerSortingEnabled?: boolean;
  headerFilteringEnabled?: boolean;
  zebraStripesEnabled?: boolean;
  initialSelection?: { mode: "cells" | "rows"; fields: number[] };
  editingColumnIndex?: number;
  editingValue?: string | null;
  cellStates?: Record<string, "pending" | "posted" | "error">;
}>(), {
  bufferScreens: 1, copySeparator: "comma", layoutScope: "result", executionId: "", editorId: "",
  resultIndex: 0, headerSortingEnabled: true, headerFilteringEnabled: true,
  initialSelection: () => ({ mode: "cells", fields: [] }),
});

const emit = defineEmits<{
  "cell-contextmenu": [event: MouseEvent, fieldIndex: number, columnIndex: number, isValueCell: boolean];
  "cell-dblclick": [fieldIndex: number];
  "row-contextmenu": [event: MouseEvent, fieldIndex: number];
  "selection-change": [state: SingleRecordSelectionState];
  "copy-text": [text: string, message: string];
  "layout-dirty": [dirty: boolean];
  "update:editing-value": [value: string];
  "commit-edit": [reason: "enter" | "blur" | "viewport"];
  "cancel-edit": [];
}>();

interface SingleRecordLayout {
  sourceOrder: string[];
  order: string[];
  widths: Record<string, number>;
}

interface SingleRecordLayoutView {
  selected: string[];
  anchor?: string;
  customSort?: ResultSort;
  customFilters?: ResultFilter[];
}

interface ColumnLayoutAdapter {
  ensure(context: LayoutContext): { layoutKey: string; viewKey: string; identities: string[] };
  layout(layoutKey: string): SingleRecordLayout | undefined;
  view(viewKey: string): SingleRecordLayoutView;
  displayedOrder(layoutKey: string, viewKey: string, identities: string[], filtered: boolean): string[];
  customSort(viewKey: string): ResultSort | undefined;
  customFilters(viewKey: string): ResultFilter[];
  setCustomSort(viewKey: string, sort: ResultSort | undefined): void;
  setCustomFilters(viewKey: string, filters: ResultFilter[]): void;
  setSelection(viewKey: string, selected: string[], anchor?: string): void;
  clearSelection(viewKey: string): void;
  choose(viewKey: string, order: string[], clicked: string, toggle: boolean, range: boolean): void;
  selectOnly(viewKey: string, identity: string): void;
  reorder(layoutKey: string, viewKey: string, order: string[], target: string, side: DropSide, filtered: boolean): void;
  moveToEdge(layoutKey: string, viewKey: string, order: string[], edge: ColumnEdge, filtered: boolean): void;
  setWidth(layoutKey: string, identity: string, width: number): void;
  dirty(layoutKey: string, identities: string[], defaultWidths: number[]): boolean;
  reset(layoutKey: string, viewKey: string, identities: string[], defaultWidths: number[]): void;
}

function createFallbackLayoutAdapter(): ColumnLayoutAdapter {
  const layouts = reactive<Record<string, SingleRecordLayout>>({});
  const views = reactive<Record<string, SingleRecordLayoutView>>({});
  const viewFor = (viewKey: string): SingleRecordLayoutView => {
    if (!views[viewKey]) views[viewKey] = { selected: [] };
    return views[viewKey];
  };
  return {
    ensure(context) {
      const identities = columnIdentityKeys(context.result.columns, context.result.columnDetails);
      const prefix = context.namespace ? `${context.namespace}:` : "";
      const scope = context.scope === "editor"
        ? `editor:${context.editorId}:${context.result.resultIndex}`
        : `result:${context.executionId}:${context.result.resultIndex}`;
      const layoutKey = `${prefix}${scope}`;
      const viewKey = layoutKey;
      const current = layouts[layoutKey];
      const same = current && current.sourceOrder.length === identities.length
        && identities.every((identity) => current.sourceOrder.includes(identity));
      if (!same) {
        const widths: Record<string, number> = {};
        identities.forEach((identity, index) => { widths[identity] = context.defaultWidths[index]; });
        layouts[layoutKey] = { sourceOrder: [...identities], order: [...identities], widths };
      }
      viewFor(viewKey);
      return { layoutKey, viewKey, identities };
    },
    layout: (layoutKey) => layouts[layoutKey],
    view: viewFor,
    displayedOrder: (layoutKey, viewKey, identities) => {
      const stored = layouts[layoutKey];
      const allowed = new Set(identities);
      return (stored?.order ?? identities).filter((identity) => allowed.has(identity));
    },
    customSort: (viewKey) => viewFor(viewKey).customSort,
    customFilters: (viewKey) => [...(viewFor(viewKey).customFilters ?? [])],
    setCustomSort: (viewKey, sort) => { viewFor(viewKey).customSort = sort; },
    setCustomFilters: (viewKey, filters) => { viewFor(viewKey).customFilters = [...filters]; },
    setSelection: (viewKey, selected, anchor) => { const view = viewFor(viewKey); view.selected = [...selected]; view.anchor = anchor; },
    clearSelection: (viewKey) => { const view = viewFor(viewKey); view.selected = []; view.anchor = undefined; },
    choose: (viewKey, order, clicked, toggle, range) => {
      const view = viewFor(viewKey);
      const next = selectHeader(order, view.selected, view.anchor, clicked, { toggle, range });
      view.selected = next.selected; view.anchor = next.anchor;
    },
    selectOnly: (viewKey, identity) => { const view = viewFor(viewKey); view.selected = [identity]; view.anchor = identity; },
    reorder: (layoutKey, viewKey, order, target, side) => {
      const view = viewFor(viewKey); const next = reorderColumns(order, view.selected, target, side);
      if (next !== order && layouts[layoutKey]) layouts[layoutKey].order = next;
    },
    moveToEdge: (layoutKey, viewKey, order, edge) => {
      const view = viewFor(viewKey); const next = moveColumnsToEdge(order, view.selected, edge);
      if (next !== order && layouts[layoutKey]) layouts[layoutKey].order = next;
    },
    setWidth: (layoutKey, identity, width) => {
      if (layouts[layoutKey]) layouts[layoutKey].widths[identity] = width;
    },
    dirty: (layoutKey, identities, widths) => {
      const stored = layouts[layoutKey];
      if (!stored) return false;
      return stored.order.some((identity, index) => identity !== identities[index])
        || identities.some((identity, index) => stored.widths[identity] !== widths[index]);
    },
    reset: (layoutKey, viewKey, identities, widths) => {
      const nextWidths: Record<string, number> = {};
      identities.forEach((identity, index) => { nextWidths[identity] = widths[index]; });
      layouts[layoutKey] = { sourceOrder: [...identities], order: [...identities], widths: nextWidths };
      views[viewKey] = { selected: [], customSort: undefined, customFilters: [] };
    }
  };
}

const grid = ref<InstanceType<typeof ResultVirtualGrid>>();
const root = ref<HTMLElement>();
const activePinia = getActivePinia();
const piniaProvided = Boolean(activePinia && (activePinia as { _a?: unknown })._a);
const columnLayouts: ColumnLayoutAdapter = activePinia && piniaProvided
  ? useColumnLayoutStore() : createFallbackLayoutAdapter();
const layoutState = ref<{ layoutKey: string; viewKey: string; identities: string[] }>();
const headerMenu = ref({ visible: false, x: 0, y: 0 });
const selectingCells = ref(false);
const selectingRows = ref(false);
const selectingColumns = ref(false);
const cellAnchor = ref<CellPoint>();
const rowAnchor = ref<number>();
const rowSelectionBase = ref<number[]>([]);
const rowSelectionMode = ref<"replace" | "add" | "remove">("replace");
const columnSelectionAnchor = ref<string>();
const columnSelectionBase = ref<string[]>([]);
const columnSelectionMode = ref<"replace" | "add" | "remove">("replace");
const suppressHeaderClick = ref(false);
const headerPointer = ref<{ identity: string; selected: boolean }>();
const resizing = ref<{ identity: string; startX: number; startWidth: number }>();
const draggingIdentity = ref<string>();
const selectionMode = ref<"cells" | "rows" | "columns">("cells");
const selectedCellKeys = ref<string[]>([]);
const selectedRowSources = ref<number[]>([]);
const selectedColumnSources = ref<number[]>([]);
const focusedCellKey = ref<string>();
const initialSelectionKey = ref("");
const localSort = ref<ResultSort>();
const localFilters = ref<ResultFilter[]>([]);

function layoutIsDirty(): boolean {
  const state = layoutState.value;
  return !!state && (columnLayouts.dirty(state.layoutKey, state.identities, defaultWidths.value)
    || !!localSort.value || localFilters.value.length > 0);
}
function emitLayoutDirty(): void { emit("layout-dirty", layoutIsDirty()); }

const syntheticResult = computed<QueryResult>(() => ({
  resultIndex: props.resultIndex, sql: "", type: "QUERY", columns: [...SYNTHETIC_LABELS],
  columnDetails: SYNTHETIC_QUERY_COLUMNS, rows: [], updateCount: -1, truncated: false, durationMs: 0, complete: true
}));
const defaultWidths = computed(() => SYNTHETIC_DEFAULT_WIDTHS.map((width, index) =>
  Math.max(defaultColumnWidth(SYNTHETIC_LABELS[index]), width)));
const layoutContext = computed(() => ({
  scope: props.layoutScope, executionId: props.executionId, editorId: props.editorId,
  result: syntheticResult.value, defaultWidths: defaultWidths.value, namespace: "single-record"
}));

const baseColumns = computed<ResultVirtualColumn[]>(() => SYNTHETIC_LABELS.map((label, sourceIndex) => ({
  key: `single-${sourceIndex}`, label, sourceIndex, visibleIndex: sourceIndex,
  width: SYNTHETIC_DEFAULT_WIDTHS[sourceIndex], readonly: sourceIndex !== 1,
  headerRenderer: () => renderHeader(sourceIndex), headerCellRenderer: () => renderHeader(sourceIndex),
})));

const gridColumns = computed<ResultVirtualColumn[]>(() => {
  const state = layoutState.value;
  const order = state ? columnLayouts.displayedOrder(state.layoutKey, state.viewKey,
    state.identities, false) : SYNTHETIC_LABELS.map((_, index) => String(index));
  const byIdentity = new Map(baseColumns.value.map((column, index) => [state?.identities[index] ?? String(index), column]));
  const stored = state ? columnLayouts.layout(state.layoutKey) : undefined;
  return order.map((identity, visibleIndex) => {
    const column = byIdentity.get(identity);
    if (!column) return undefined;
    return { ...column, visibleIndex, width: stored?.widths[identity] ?? SYNTHETIC_DEFAULT_WIDTHS[column.sourceIndex] };
  }).filter((column): column is ResultVirtualColumn => !!column);
});

const rawFields = computed<ViewRow[]>(() => props.columns.map((column) => ({
  sourceIndex: column.index,
  cells: [column.label, props.row.cells[column.index] ?? null, column.remarks || "", column.typeName || ""]
})));

const displayFields = computed<ViewRow[]>(() => {
  let fields = rawFields.value.filter((field) => props.headerFilteringEnabled
    ? localFilters.value.every((filter) => matchesFilter(field.cells[filter.columnIndex] ?? null,
      filter, SYNTHETIC_QUERY_COLUMNS[filter.columnIndex])) : true);
  if (props.headerSortingEnabled && localSort.value) {
    const sort = localSort.value;
    const column = SYNTHETIC_QUERY_COLUMNS[sort.columnIndex];
    fields = [...fields].sort((left, right) => {
      const compared = compareValues(left.cells[sort.columnIndex] ?? null, right.cells[sort.columnIndex] ?? null, column);
      return compared === 0 ? left.sourceIndex - right.sourceIndex
        : sort.direction === "asc" ? compared : -compared;
    });
  }
  return fields;
});

watch(layoutContext, () => {
  layoutState.value = columnLayouts.ensure(layoutContext.value);
  const view = columnLayouts.view(layoutState.value.viewKey);
  localSort.value = columnLayouts.customSort(layoutState.value.viewKey);
  localFilters.value = columnLayouts.customFilters(layoutState.value.viewKey);
  selectedColumnSources.value = view.selected.map((identity) => stateIdentitiesToSource(identity)).filter((source): source is number => source !== undefined);
  reconcileSelections();
  emitLayoutDirty();
}, { immediate: true });

const editingGridCell = computed(() => props.editingColumnIndex === undefined ? undefined : {
  rowIndex: props.editingColumnIndex, columnIndex: 1
});
const gridCellStates = computed(() => {
  const result: Record<string, "pending" | "posted" | "error"> = {};
  for (const field of rawFields.value) {
    const state = props.cellStates?.[`${props.row.sourceIndex}:${field.sourceIndex}`];
    if (state) result[`${field.sourceIndex}:1`] = state;
  }
  return result;
});

function keyFor(fieldIndex: number, columnIndex: number): string { return `${fieldIndex}:${columnIndex}`; }
function fieldAt(rowIndex: number): number | undefined { return displayFields.value[rowIndex]?.sourceIndex; }
function columnAt(columnIndex: number): ResultVirtualColumn | undefined { return gridColumns.value[columnIndex]; }
function stateIdentitiesToSource(identity: string): number | undefined {
  const state = layoutState.value;
  const source = state?.identities.indexOf(identity);
  return source !== undefined && source >= 0 ? source : undefined;
}
function selectedColumnIdentities(): string[] {
  const state = layoutState.value;
  if (!state) return [];
  const selected = new Set(columnLayouts.view(state.viewKey).selected);
  return gridColumns.value.filter((column) => selected.has(state.identities[column.sourceIndex]))
    .map((column) => state.identities[column.sourceIndex]);
}

function clearHeaderSelection(): void {
  const state = layoutState.value;
  if (state) columnLayouts.clearSelection(state.viewKey);
  selectedColumnSources.value = [];
}

function sourceCells(): SingleRecordSourceCell[] {
  const values: SingleRecordSourceCell[] = [];
  const add = (fieldIndex: number) => {
    const valueColumn = gridColumns.value.find((column) => column.sourceIndex === 1);
    if (valueColumn) values.push({ sourceColumn: fieldIndex, value: props.row.cells[fieldIndex] ?? null });
  };
  if (selectionMode.value === "cells") {
    for (const key of selectedCellKeys.value) {
      const [field, column] = key.split(":").map(Number);
      if (column === 1) add(field);
    }
  } else if (selectionMode.value === "columns" && selectedColumnSources.value.includes(1)) {
    displayFields.value.forEach((field) => add(field.sourceIndex));
  }
  return values;
}

function selectionState(): SingleRecordSelectionState {
  const sources = sourceCells();
  const selectedFields = selectionMode.value === "rows" ? [...selectedRowSources.value]
    : selectionMode.value === "columns" ? displayFields.value.map((field) => field.sourceIndex)
      : [...new Set(selectedCellKeys.value.map((key) => Number(key.split(":")[0])))];
  const hasSelection = selectionMode.value === "cells" ? selectedCellKeys.value.length > 0
    : selectionMode.value === "rows" ? selectedRowSources.value.length > 0 : selectedColumnSources.value.length > 0;
  const sqlAllowed = hasSelection && sources.length > 0 && (
    selectionMode.value === "cells" && selectedCellKeys.value.every((key) => Number(key.split(":")[1]) === 1)
    || selectionMode.value === "columns" && selectedColumnSources.value.every((source) => source === 1));
  const count = selectionMode.value === "cells" ? selectedCellKeys.value.length
    : selectionMode.value === "rows" ? selectedRowSources.value.length : selectedColumnSources.value.length;
  const unit = selectionMode.value === "cells" ? "个单元格" : selectionMode.value === "rows" ? "个字段" : "列";
  return { mode: selectionMode.value, cellKeys: [...selectedCellKeys.value], rowSources: [...selectedRowSources.value],
    columnSources: [...selectedColumnSources.value], focusedCellKey: focusedCellKey.value, sourceCells: sources,
    selectedFieldIndices: selectedFields, hasSelection, sqlAllowed, statusText: `已选中 ${count} ${unit}` };
}

function emitSelection(): void { emit("selection-change", selectionState()); }

function clearSelection(): void {
  selectingCells.value = false; selectingRows.value = false; selectingColumns.value = false;
  selectedCellKeys.value = []; selectedRowSources.value = [];
  clearHeaderSelection();
  focusedCellKey.value = undefined; cellAnchor.value = undefined; rowAnchor.value = undefined;
  columnSelectionAnchor.value = undefined; columnSelectionBase.value = [];
  window.removeEventListener("pointerup", finishCellSelection);
  window.removeEventListener("pointerup", finishRowSelection);
  window.removeEventListener("pointerup", finishColumnSelection);
  emitSelection();
}

function reconcileSelections(): void {
  const visibleFields = new Set(displayFields.value.map((field) => field.sourceIndex));
  selectedCellKeys.value = selectedCellKeys.value.filter((key) => visibleFields.has(Number(key.split(":")[0])));
  selectedRowSources.value = selectedRowSources.value.filter((source) => visibleFields.has(source));
  emitSelection();
}

function applyInitialSelection(): void {
  const fields = props.initialSelection?.fields ?? [];
  const key = `${props.initialSelection?.mode ?? "cells"}:${fields.join(",")}`;
  if (initialSelectionKey.value === key) return;
  initialSelectionKey.value = key;
  if (!fields.length) return;
  clearHeaderSelection();
  const visible = new Set(displayFields.value.map((field) => field.sourceIndex));
  const selected = fields.filter((field) => visible.has(field));
  if (props.initialSelection?.mode === "rows") {
    selectionMode.value = "rows"; selectedCellKeys.value = []; selectedColumnSources.value = [];
    selectedRowSources.value = selected;
  } else {
    selectionMode.value = "cells"; selectedRowSources.value = []; selectedColumnSources.value = [];
    selectedCellKeys.value = selected.map((field) => keyFor(field, 1));
    focusedCellKey.value = selectedCellKeys.value[0];
  }
  emitSelection();
}

watch([() => props.row.sourceIndex, () => props.columns.map((column) => column.index).join(","),
  () => displayFields.value.map((field) => field.sourceIndex).join(",")], () => {
  reconcileSelections(); applyInitialSelection();
}, { immediate: true });
watch([selectedCellKeys, selectedRowSources, selectedColumnSources], emitSelection, { deep: true });
watch([() => gridColumns.value.map((column) => `${column.sourceIndex}:${column.width}`).join(","), localSort, localFilters],
  emitLayoutDirty, { deep: true });

function keysInRange(start: CellPoint, end: CellPoint): string[] {
  const firstRow = Math.min(start.row, end.row); const lastRow = Math.max(start.row, end.row);
  const firstColumn = Math.min(start.column, end.column); const lastColumn = Math.max(start.column, end.column);
  const keys: string[] = [];
  for (let row = firstRow; row <= lastRow; row++) {
    const field = fieldAt(row); if (field === undefined) continue;
    for (let column = firstColumn; column <= lastColumn; column++) {
      const gridColumn = columnAt(column); if (gridColumn) keys.push(keyFor(field, gridColumn.sourceIndex));
    }
  }
  return keys;
}

function startCellSelection(event: PointerEvent, rowIndex: number, columnIndex: number): void {
  if (event.button !== 0 || resizing.value) return;
  event.preventDefault();
  const field = fieldAt(rowIndex); const column = columnAt(columnIndex);
  if (field === undefined || !column) return;
  selectionMode.value = "cells"; selectedRowSources.value = []; clearHeaderSelection();
  const point = { row: rowIndex, column: columnIndex };
  if (event.ctrlKey || event.metaKey) {
    const key = keyFor(field, column.sourceIndex);
    selectedCellKeys.value = selectedCellKeys.value.includes(key)
      ? selectedCellKeys.value.filter((item) => item !== key) : [...selectedCellKeys.value, key];
    cellAnchor.value = point; focusedCellKey.value = key; selectingCells.value = false;
  } else {
    cellAnchor.value = event.shiftKey && cellAnchor.value ? cellAnchor.value : point;
    selectedCellKeys.value = keysInRange(cellAnchor.value, point);
    focusedCellKey.value = keyFor(field, column.sourceIndex); selectingCells.value = true;
  }
  window.removeEventListener("pointerup", finishCellSelection);
  if (selectingCells.value) window.addEventListener("pointerup", finishCellSelection, { once: true });
  emitSelection();
}

function extendCellSelection(rowIndex: number, columnIndex: number): void {
  if (!selectingCells.value || !cellAnchor.value) return;
  const field = fieldAt(rowIndex); const column = columnAt(columnIndex);
  if (field === undefined || !column) return;
  selectedCellKeys.value = keysInRange(cellAnchor.value, { row: rowIndex, column: columnIndex });
  focusedCellKey.value = keyFor(field, column.sourceIndex); emitSelection();
}
function finishCellSelection(): void { selectingCells.value = false; window.removeEventListener("pointerup", finishCellSelection); }

function handlePointerMove(event: PointerEvent): void {
  if (!selectingCells.value && !selectingRows.value && !selectingColumns.value) return;
  const viewport = (grid.value?.$el as HTMLElement | undefined)?.querySelector<HTMLElement>(".result-virtual-grid__viewport");
  if (!viewport) return;
  const bounds = viewport.getBoundingClientRect();
  const edge = 28;
  const verticalStep = event.clientY < bounds.top + edge ? -18
    : event.clientY > bounds.bottom - edge ? 18 : 0;
  const horizontalStep = event.clientX < bounds.left + edge ? -18
    : event.clientX > bounds.right - edge ? 18 : 0;
  if (!verticalStep && !horizontalStep) return;
  viewport.scrollTop += verticalStep;
  viewport.scrollLeft += horizontalStep;
}

function focusRoot(event: PointerEvent): void {
  const target = event.target as HTMLElement | null;
  if (target?.matches("input, textarea, select, button, [contenteditable='true']")) return;
  root.value?.focus({ preventScroll: true });
}

function startRowSelection(event: PointerEvent, sourceIndex: number): void {
  if (event.button !== 0) return;
  event.preventDefault(); selectionMode.value = "rows"; selectedCellKeys.value = []; clearHeaderSelection();
  const order = displayFields.value.map((field) => field.sourceIndex);
  const current = new Set(selectedRowSources.value);
  rowAnchor.value = event.shiftKey && rowAnchor.value !== undefined ? rowAnchor.value : sourceIndex;
  rowSelectionBase.value = [...current];
  rowSelectionMode.value = event.ctrlKey || event.metaKey
    ? current.has(sourceIndex) ? "remove" : "add" : "replace";
  applyRowSelection(sourceIndex); selectingRows.value = true;
  window.removeEventListener("pointerup", finishRowSelection);
  window.addEventListener("pointerup", finishRowSelection, { once: true }); emitSelection();
}
function applyRowSelection(sourceIndex: number): void {
  const order = displayFields.value.map((field) => field.sourceIndex);
  const anchor = rowAnchor.value ?? sourceIndex; const start = order.indexOf(anchor); const end = order.indexOf(sourceIndex);
  if (start < 0 || end < 0) return;
  const range = order.slice(Math.min(start, end), Math.max(start, end) + 1); const base = new Set(rowSelectionBase.value);
  if (rowSelectionMode.value === "add") range.forEach((item) => base.add(item));
  else if (rowSelectionMode.value === "remove") range.forEach((item) => base.delete(item));
  else { base.clear(); range.forEach((item) => base.add(item)); }
  selectedRowSources.value = order.filter((item) => base.has(item));
}
function extendRowSelection(sourceIndex: number): void { if (selectingRows.value) { applyRowSelection(sourceIndex); emitSelection(); } }
function finishRowSelection(): void { selectingRows.value = false; window.removeEventListener("pointerup", finishRowSelection); }

function startColumnSelection(event: PointerEvent, sourceIndex: number): void {
  if (event.button !== 0 || resizing.value) return;
  const state = layoutState.value; if (!state) return;
  const identity = state.identities[sourceIndex]; const selected = columnLayouts.view(state.viewKey).selected.includes(identity);
  headerPointer.value = { identity, selected };
  if (selected) return;
  event.preventDefault(); selectionMode.value = "columns"; selectedCellKeys.value = []; selectedRowSources.value = [];
  const order = gridColumns.value.map((column) => state.identities[column.sourceIndex]);
  const view = columnLayouts.view(state.viewKey); const previous = [...view.selected];
  columnSelectionBase.value = previous; columnSelectionMode.value = event.ctrlKey || event.metaKey
    ? previous.includes(identity) ? "remove" : "add" : "replace";
  columnSelectionAnchor.value = event.shiftKey && view.anchor && order.includes(view.anchor) ? view.anchor : identity;
  applyColumnSelection(identity); selectingColumns.value = true; suppressHeaderClick.value = true;
  window.removeEventListener("pointerup", finishColumnSelection);
  window.addEventListener("pointerup", finishColumnSelection, { once: true }); emitSelection();
}
function applyColumnSelection(identity: string): void {
  const state = layoutState.value; const anchor = columnSelectionAnchor.value; if (!state || !anchor) return;
  const order = gridColumns.value.map((column) => state.identities[column.sourceIndex]);
  const start = order.indexOf(anchor); const end = order.indexOf(identity); if (start < 0 || end < 0) return;
  const range = order.slice(Math.min(start, end), Math.max(start, end) + 1); const base = new Set(columnSelectionBase.value);
  if (columnSelectionMode.value === "add") range.forEach((item) => base.add(item));
  else if (columnSelectionMode.value === "remove") range.forEach((item) => base.delete(item));
  else { base.clear(); range.forEach((item) => base.add(item)); }
  columnLayouts.setSelection(state.viewKey, order.filter((item) => base.has(item)), anchor);
  selectedColumnSources.value = gridColumns.value.filter((column) => base.has(state.identities[column.sourceIndex]))
    .map((column) => column.sourceIndex);
}
function extendColumnSelection(sourceIndex: number): void {
  if (!selectingColumns.value) return;
  const state = layoutState.value; if (!state) return;
  const identity = state.identities[sourceIndex]; if (identity) { applyColumnSelection(identity); emitSelection(); }
}
function finishColumnSelection(): void {
  selectingColumns.value = false; headerPointer.value = undefined;
  window.removeEventListener("pointerup", finishColumnSelection);
  window.setTimeout(() => { suppressHeaderClick.value = false; }, 0);
}

function renderHeader(sourceIndex: number): ReturnType<typeof h> {
  const column = baseColumns.value[sourceIndex]; const state = layoutState.value;
  const identity = state?.identities[sourceIndex] ?? String(sourceIndex);
  const selected = state ? columnLayouts.view(state.viewKey).selected.includes(identity) : false;
  const currentOrder = gridColumns.value.map((item) => item.sourceIndex);
  const selectedOrder = currentOrder.filter((item) => selectedColumnSources.value.includes(item));
  const canSort = props.headerSortingEnabled;
  return h("div", {
    class: ["single-record-header-label", selected ? "selected" : ""],
    role: "button", tabindex: 0, draggable: selected,
    "aria-selected": String(selected), "aria-label": `列 ${column.label}`,
    onPointerdown: (event: PointerEvent) => startColumnSelection(event, sourceIndex),
    onPointerenter: () => extendColumnSelection(sourceIndex),
    onClick: (event: MouseEvent) => {
      if (suppressHeaderClick.value) { suppressHeaderClick.value = false; return; }
      if (!state) return;
      selectionMode.value = "columns"; selectedCellKeys.value = []; selectedRowSources.value = [];
      columnLayouts.choose(state.viewKey, currentOrder.map((item) => state.identities[item]), identity,
        event.ctrlKey || event.metaKey, event.shiftKey);
      selectedColumnSources.value = gridColumns.value.filter((item) => columnLayouts.view(state.viewKey)
        .selected.includes(state.identities[item.sourceIndex])).map((item) => item.sourceIndex);
      emitSelection();
    },
    onContextmenu: (event: MouseEvent) => openHeaderMenu(event, sourceIndex),
    onKeydown: (event: KeyboardEvent) => {
      if (event.key === "ContextMenu" || (event.shiftKey && event.key === "F10")) {
        event.preventDefault(); openHeaderMenu(event as unknown as MouseEvent, sourceIndex); return;
      }
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault(); if (state) columnLayouts.selectOnly(state.viewKey, identity);
        selectedColumnSources.value = [sourceIndex]; selectionMode.value = "columns"; emitSelection();
      }
    },
    onDragstart: (event: DragEvent) => {
      if (!selected) { event.preventDefault(); return; }
      draggingIdentity.value = identity; event.dataTransfer?.setData("text/plain", identity);
    },
    onDragover: (event: DragEvent) => { if (draggingIdentity.value && draggingIdentity.value !== identity) event.preventDefault(); },
    onDrop: (event: DragEvent) => { event.preventDefault(); dropColumn(sourceIndex); },
    onDragend: () => { draggingIdentity.value = undefined; },
  }, [
    h("span", { class: "single-record-header-title" }, column.label),
    h(ResultHeaderTools, {
      column: SYNTHETIC_QUERY_COLUMNS[sourceIndex], columnIndex: sourceIndex,
      sort: localSort.value?.columnIndex === sourceIndex ? localSort.value : undefined,
      filter: localFilters.value.find((filter) => filter.columnIndex === sourceIndex),
      sortingEnabled: canSort, filteringEnabled: props.headerFilteringEnabled,
      onSort: () => cycleSort(sourceIndex), onApply: (filter: ResultFilter) => applyFilter(filter),
      onClear: () => clearFilter(sourceIndex)
    }),
    h("span", {
      class: "single-record-column-resize-handle", role: "separator", tabindex: 0,
      "aria-label": `调整 ${column.label} 列宽`, title: "拖动调整列宽，双击自动匹配",
      onPointerdown: (event: PointerEvent) => startResize(event, sourceIndex),
      onDblclick: (event: MouseEvent) => { event.stopPropagation(); fitWidth(sourceIndex); },
      onClick: (event: MouseEvent) => event.stopPropagation()
    }),
    selected && selectedOrder.length > 1 && selectedOrder[0] === sourceIndex
      ? h("span", { class: "single-record-column-selection-count" }, `${selectedOrder.length}列`) : undefined
  ]);
}

function dropColumn(targetSourceIndex: number): void {
  const state = layoutState.value; const target = state?.identities[targetSourceIndex];
  if (!state || !target) return;
  const order = gridColumns.value.map((column) => state.identities[column.sourceIndex]);
  const source = draggingIdentity.value; if (!source || source === target) return;
  const targetPosition = gridColumns.value.findIndex((column) => column.sourceIndex === targetSourceIndex);
  const sourcePosition = gridColumns.value.findIndex((column) => state.identities[column.sourceIndex] === source);
  const side: DropSide = sourcePosition >= 0 && sourcePosition < targetPosition ? "after" : "before";
  columnLayouts.reorder(state.layoutKey, state.viewKey, order, target, side, false);
  emitLayoutDirty();
  draggingIdentity.value = undefined;
}
function cycleSort(columnIndex: number): void {
  const current = localSort.value; const next = current?.columnIndex !== columnIndex ? { columnIndex, direction: "asc" as const }
    : current.direction === "asc" ? { columnIndex, direction: "desc" as const } : undefined;
  localSort.value = next; if (layoutState.value) columnLayouts.setCustomSort(layoutState.value.viewKey, next); emitLayoutDirty(); clearSelection();
}
function applyFilter(filter: ResultFilter): void {
  const next = localFilters.value.filter((item) => item.columnIndex !== filter.columnIndex); next.push(filter);
  localFilters.value = next; if (layoutState.value) columnLayouts.setCustomFilters(layoutState.value.viewKey, next); emitLayoutDirty(); clearSelection();
}
function clearFilter(columnIndex: number): void {
  const next = localFilters.value.filter((item) => item.columnIndex !== columnIndex);
  localFilters.value = next; if (layoutState.value) columnLayouts.setCustomFilters(layoutState.value.viewKey, next); emitLayoutDirty(); clearSelection();
}
function startResize(event: PointerEvent, sourceIndex: number): void {
  event.preventDefault(); event.stopPropagation(); const state = layoutState.value; if (!state) return;
  const identity = state.identities[sourceIndex]; const stored = columnLayouts.layout(state.layoutKey);
  resizing.value = { identity, startX: event.clientX, startWidth: stored?.widths[identity] ?? SYNTHETIC_DEFAULT_WIDTHS[sourceIndex] };
  window.addEventListener("pointermove", resizeMove); window.addEventListener("pointerup", finishResize, { once: true });
}
function resizeMove(event: PointerEvent): void {
  const state = layoutState.value; const current = resizing.value; if (!state || !current) return;
  columnLayouts.setWidth(state.layoutKey, current.identity, clampColumnWidth(current.startWidth + event.clientX - current.startX));
  emitLayoutDirty();
}
function finishResize(): void { resizing.value = undefined; window.removeEventListener("pointermove", resizeMove); }
function fitWidth(sourceIndex: number): void {
  const state = layoutState.value; if (!state) return;
  const identity = state.identities[sourceIndex];
  columnLayouts.setWidth(state.layoutKey, identity, autoColumnWidth(SYNTHETIC_LABELS[sourceIndex],
    displayFields.value.map((field) => field.cells), sourceIndex, (text) => text.length * 8));
  emitLayoutDirty();
}

function openHeaderMenu(event: MouseEvent, sourceIndex: number): void {
  event.preventDefault(); event.stopPropagation(); const state = layoutState.value; if (!state) return;
  const identity = state.identities[sourceIndex]; const view = columnLayouts.view(state.viewKey);
  if (!view.selected.includes(identity)) columnLayouts.selectOnly(state.viewKey, identity);
  selectionMode.value = "columns"; selectedCellKeys.value = []; selectedRowSources.value = [];
  selectedColumnSources.value = gridColumns.value.filter((column) => columnLayouts.view(state.viewKey)
    .selected.includes(state.identities[column.sourceIndex])).map((column) => column.sourceIndex);
  emitSelection(); headerMenu.value = { visible: true, x: event.clientX, y: event.clientY };
}
function closeHeaderMenu(): void { headerMenu.value = { ...headerMenu.value, visible: false }; }
function selectedColumns(): ResultVirtualColumn[] {
  const selected = new Set(selectedColumnSources.value); return gridColumns.value.filter((column) => selected.has(column.sourceIndex));
}
const canCopyHeaderData = computed(() => selectedColumns().length > 0 && displayFields.value.length > 0);
const canCopyHeaderIn = computed(() => selectedColumnSources.value.length === 1 && selectedColumnSources.value[0] === 1
  && displayFields.value.length > 0 && !!headerInText());
const canMoveLeft = computed(() => {
  const selected = selectedColumnSources.value; return selected.length > 0 && gridColumns.value.findIndex((column) => column.sourceIndex === selected[0]) > 0;
});
const canMoveRight = computed(() => {
  const selected = selectedColumnSources.value; return selected.length > 0 && gridColumns.value.findIndex((column) => column.sourceIndex === selected[selected.length - 1]) < gridColumns.value.length - 1;
});
const canSumHeaderData = computed(() => selectedColumnSources.value.includes(1)
  && sumDecimalValues(displayFields.value.map((field) => field.cells[1] ?? null)).valid);

function headerInText(): string | undefined {
  if (!selectedColumnSources.value.includes(1) || selectedColumnSources.value.length !== 1) return undefined;
  const selectedFields = new Set(displayFields.value.map((field) => field.sourceIndex));
  const columns = props.columns.filter((column) => selectedFields.has(column.index)).map((column) => ({
    index: column.index, label: column.label, jdbcType: column.jdbcType ?? 12
  }));
  return copyInPredicate(columns, [props.row]);
}
function handleHeaderCommand(command: HeaderMenuCommand): void {
  const columns = selectedColumns();
  if (command === "copy-headers") emit("copy-text", columns.map((column) => column.label).join("\t"), "已复制列名");
  else if (command === "copy-headers-with-remarks") emit("copy-text", resultColumnRemarksText(columns.map((column) => ({ label: column.label, remarks: "" })), props.copySeparator), "已复制列名和注释");
  else if (command === "copy-data") emit("copy-text", copyGrid(columns.map((column) => ({ label: column.label, index: column.sourceIndex })), displayFields.value, false, props.copySeparator), "已复制数据");
  else if (command === "copy-all") emit("copy-text", copyGrid(columns.map((column) => ({ label: column.label, index: column.sourceIndex })), displayFields.value, true, props.copySeparator), "已复制列名和数据");
  else if (command === "copy-in") { const text = headerInText(); if (text) emit("copy-text", text, "已复制 IN 语句"); }
  else if (command === "sum") {
    const result = sumDecimalValues(columns.flatMap((column) => displayFields.value.map((field) => field.cells[column.sourceIndex] ?? null)));
    if (!result.valid) ElMessage.warning(`无法求和：包含非数字值${result.invalidValue ? `“${result.invalidValue}”` : ""}`);
    else if (result.count) emit("copy-text", result.total, `合计 ${result.total}`);
  } else if (command === "move-left" || command === "move-right") moveSelected(command === "move-left" ? "left" : "right");
}
function moveSelected(edge: ColumnEdge): void {
  const state = layoutState.value; if (!state) return;
  const order = gridColumns.value.map((column) => state.identities[column.sourceIndex]);
  columnLayouts.moveToEdge(state.layoutKey, state.viewKey, order, edge, false);
  emitLayoutDirty();
}

function resetLayout(): void {
  const state = layoutState.value;
  if (!state) return;
  columnLayouts.reset(state.layoutKey, state.viewKey, state.identities, defaultWidths.value);
  localSort.value = undefined;
  localFilters.value = [];
  selectedColumnSources.value = [];
  clearSelection();
  emitLayoutDirty();
}

function forwardCellContextmenu(event: MouseEvent, rowIndex: number, columnIndex: number): void {
  const field = fieldAt(rowIndex); const column = columnAt(columnIndex); if (field === undefined || !column) return;
  event.preventDefault(); event.stopPropagation(); const key = keyFor(field, column.sourceIndex);
  if (!selectedCellKeys.value.includes(key) || selectionMode.value !== "cells") {
    selectionMode.value = "cells"; selectedRowSources.value = []; clearHeaderSelection();
    selectedCellKeys.value = [key]; focusedCellKey.value = key; cellAnchor.value = { row: rowIndex, column: columnIndex };
  }
  emitSelection(); emit("cell-contextmenu", event, field, column.sourceIndex, column.sourceIndex === 1);
}
function forwardCellDblclick(rowIndex: number, columnIndex: number): void {
  if (columnAt(columnIndex)?.sourceIndex === 1) { const field = fieldAt(rowIndex); if (field !== undefined) emit("cell-dblclick", field); }
}
function forwardRowContextmenu(event: MouseEvent, sourceIndex: number): void {
  event.preventDefault(); event.stopPropagation(); selectionMode.value = "rows";
  selectedCellKeys.value = []; clearHeaderSelection(); selectedRowSources.value = [sourceIndex]; emitSelection();
  emit("row-contextmenu", event, sourceIndex);
}

function getCopyText(includeHeaders = false): string | undefined {
  if (!selectionState().hasSelection) return undefined;
  let columns: ResultVirtualColumn[]; let rows = displayFields.value;
  if (selectionMode.value === "columns") columns = selectedColumns();
  else if (selectionMode.value === "rows") {
    columns = gridColumns.value;
    const selected = new Set(selectedRowSources.value);
    rows = rows.filter((row) => selected.has(row.sourceIndex));
  }
  else {
    const selected = new Set(selectedCellKeys.value); const positions = selectedCellKeys.value.map((key) => {
      const [, source] = key.split(":").map(Number); return gridColumns.value.findIndex((column) => column.sourceIndex === source);
    });
    if (!positions.length) return undefined;
    const first = Math.min(...positions); const last = Math.max(...positions); columns = gridColumns.value.slice(first, last + 1);
    const selectedFields = new Set(selectedCellKeys.value.map((key) => Number(key.split(":")[0])));
    rows = rows.filter((row) => selectedFields.has(row.sourceIndex)).map((row) => ({ sourceIndex: row.sourceIndex,
      cells: row.cells.map((value, source) => selected.has(keyFor(row.sourceIndex, source)) ? value : "") }));
  }
  if (!columns.length || !rows.length) return undefined;
  return copyGrid(columns.map((column) => ({ label: column.label, index: column.sourceIndex })), rows, includeHeaders, props.copySeparator);
}

function handleKeydown(event: KeyboardEvent): void {
  const target = event.target as HTMLElement | null;
  if (target?.matches("input, textarea, select, [contenteditable='true']")) return;
  if ((event.ctrlKey || event.metaKey) && event.key.toLocaleLowerCase() === "c") {
    const state = selectionState();
    const text = state.hasSelection ? getCopyText() : undefined;
    if (text !== undefined) {
      event.preventDefault();
      const message = state.mode === "rows" ? "已复制选中字段"
        : state.mode === "columns" ? "已复制选中列" : "已复制选中单元格";
      emit("copy-text", text, message);
    }
    return;
  }
  if (event.key === "Escape") { event.preventDefault(); clearSelection(); closeHeaderMenu(); return; }
  if (!/^Arrow(Up|Down|Left|Right)$/.test(event.key) || event.ctrlKey || event.metaKey || event.altKey) return;
  const firstKey = focusedCellKey.value || selectedCellKeys.value[0]; if (!firstKey) return;
  const [field, source] = firstKey.split(":").map(Number); const row = displayFields.value.findIndex((item) => item.sourceIndex === field);
  const column = gridColumns.value.findIndex((item) => item.sourceIndex === source); if (row < 0 || column < 0) return;
  const delta = event.key === "ArrowUp" ? { row: -1, column: 0 } : event.key === "ArrowDown" ? { row: 1, column: 0 }
    : event.key === "ArrowLeft" ? { row: 0, column: -1 } : { row: 0, column: 1 };
  const point = { row: Math.max(0, Math.min(displayFields.value.length - 1, row + delta.row)),
    column: Math.max(0, Math.min(gridColumns.value.length - 1, column + delta.column)) };
  const nextField = fieldAt(point.row); const nextColumn = columnAt(point.column); if (nextField === undefined || !nextColumn) return;
  event.preventDefault(); selectionMode.value = "cells"; selectedRowSources.value = []; clearHeaderSelection();
  if (event.shiftKey && cellAnchor.value) selectedCellKeys.value = keysInRange(cellAnchor.value, point);
  else { cellAnchor.value = point; selectedCellKeys.value = [keyFor(nextField, nextColumn.sourceIndex)]; }
  focusedCellKey.value = keyFor(nextField, nextColumn.sourceIndex); emitSelection();
}

onBeforeUnmount(() => {
  window.removeEventListener("pointerup", finishCellSelection); window.removeEventListener("pointerup", finishRowSelection);
  window.removeEventListener("pointerup", finishColumnSelection); window.removeEventListener("pointermove", resizeMove);
});

defineExpose({
  getCopyText,
  getSelectionState: selectionState,
  clearSelection,
  handleKeydown,
  isSelecting: () => selectingCells.value || selectingRows.value || selectingColumns.value,
  isLayoutDirty: layoutIsDirty,
  resetLayout,
  getScrollPosition: () => grid.value?.getScrollPosition() ?? { left: 0, top: 0 },
  setScrollPosition: (position: ResultGridScrollPosition) => grid.value?.setScrollPosition(position),
});
</script>

<style scoped>
.result-single-record-view { box-sizing: border-box; width: 100%; height: 100%; min-height: 0; outline: none; }
:deep(.single-record-header-label) { box-sizing: border-box; display: flex; width: 100%; height: 100%; padding: 0 8px; align-items: center; color: var(--db-result-header-color); font-family: var(--db-result-font-family); font-size: var(--db-result-font-size); font-weight: var(--db-result-header-font-weight); font-style: var(--db-result-header-font-style); position: relative; gap: 2px; }
:deep(.single-record-header-label.selected) { background: var(--db-accent-soft); color: var(--db-text); }
:deep(.single-record-header-title) { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
:deep(.single-record-column-resize-handle) { position: absolute; right: 0; top: 0; width: 7px; height: 100%; cursor: col-resize; z-index: 2; }
:deep(.single-record-column-selection-count) { position: absolute; right: 7px; top: 3px; font-size: 10px; color: var(--db-accent); }
:deep(.result-cell-readonly) { background: color-mix(in srgb, var(--db-result-header-bg) 72%, var(--db-result-bg) 28%); color: var(--db-result-header-color); }
:deep(.result-cell.selected), :deep(.result-cell.column-selected) { background: var(--db-result-selection-bg); color: var(--db-result-cell-color); }
</style>
