<template>
  <div class="result-single-record-view" aria-label="单个记录查看">
    <ResultVirtualGrid
      ref="grid"
      :rows="fields"
      :columns="gridColumns"
     :header-height="32"
     :buffer-screens="bufferScreens"
      :selection-mode="gridSelectionMode"
     :selected-cell-keys="selectedGridCellKeys"
     :focused-cell-key="focusedGridCellKey"
      :selected-column-sources="selectedGridColumnSources"
     :selected-row-sources="selectedGridRowSources"
      :editing-cell="editingGridCell"
      :editing-value="editingValue"
      :cell-states="gridCellStates"
      @cell-pointerdown="forwardCellPointerdown"
      @cell-pointerenter="forwardCellPointerenter"
      @cell-contextmenu="forwardCellContextmenu"
      @cell-dblclick="forwardCellDblclick"
      @row-pointerdown="forwardRowPointerdown"
      @row-pointerenter="forwardRowPointerenter"
      @row-contextmenu="forwardRowContextmenu"
      @update:editing-value="$emit('update:editing-value', $event)"
      @commit-edit="$emit('commit-edit', $event)"
      @cancel-edit="$emit('cancel-edit')"
    >
      <template #footer><slot name="footer" /></template>
    </ResultVirtualGrid>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onBeforeUnmount, ref, watch } from "vue";
import type { ColumnOption } from "../columnFilter";
import { copyGrid, type CellRange, type ViewRow } from "../resultGrid";
import type { CopySeparator } from "../resultCopy";
import type { ResultVirtualColumn } from "../resultVirtualGrid";
import ResultVirtualGrid from "./ResultVirtualGrid.vue";

const props = withDefaults(defineProps<{
  columns: ColumnOption[];
  row: ViewRow;
  bufferScreens?: number;
  copySeparator?: CopySeparator;
  selectionMode?: "cells" | "rows" | "columns";
  cellRange?: CellRange;
  selectedCellKeys?: string[];
  focusedCellKey?: string;
  selectedRowSources?: number[];
  editingColumnIndex?: number;
  editingValue?: string | null;
  cellStates?: Record<string, "pending" | "posted" | "error">;
}>(), { bufferScreens: 1, copySeparator: "comma", selectionMode: "cells", selectedRowSources: () => [] });

const emit = defineEmits<{
  "cell-pointerdown": [event: PointerEvent, fieldIndex: number, columnIndex: number, isValueCell: boolean];
  "cell-pointerenter": [fieldIndex: number, columnIndex: number, isValueCell: boolean];
  "cell-contextmenu": [event: MouseEvent, fieldIndex: number, columnIndex: number, isValueCell: boolean];
  "cell-dblclick": [fieldIndex: number];
  "row-pointerdown": [event: PointerEvent, fieldIndex: number];
  "row-pointerenter": [fieldIndex: number];
  "row-contextmenu": [event: MouseEvent, fieldIndex: number];
  "selection-change": [sqlAllowed: boolean];
  "update:editing-value": [value: string];
  "commit-edit": [reason: "enter" | "blur" | "viewport"];
  "cancel-edit": [];
}>();

const grid = ref<InstanceType<typeof ResultVirtualGrid>>();
const fields = computed<ViewRow[]>(() => props.columns.map((column) => ({
  sourceIndex: column.index,
  cells: [column.label, props.row.cells[column.index] ?? null, column.remarks || "", column.typeName || ""]
})));

function header(label: string, columnIndex: number) {
  return () => h("div", {
    class: ["single-record-header-label",
      localSelectionMode.value === "columns" && localSelectedColumnSources.value.includes(columnIndex) ? "selected" : ""],
    onPointerdown: (event: PointerEvent) => startColumnSelection(event, columnIndex),
    onPointerenter: () => extendColumnSelection(columnIndex),
  }, label);
}

const gridColumns = computed<ResultVirtualColumn[]>(() => [
  { key: "field", label: "字段名", sourceIndex: 0, visibleIndex: 0, width: 180, readonly: true, headerRenderer: header("字段名", 0) },
  { key: "value", label: "字段值", sourceIndex: 1, visibleIndex: 1, width: 300, headerRenderer: header("字段值", 1) },
  { key: "remarks", label: "字段备注", sourceIndex: 2, visibleIndex: 2, width: 220, readonly: true, headerRenderer: header("字段备注", 2) },
  { key: "type", label: "字段类型", sourceIndex: 3, visibleIndex: 3, width: 160, readonly: true, headerRenderer: header("字段类型", 3) }
]);

const sourceSelectedFieldIndices = computed(() => {
  const selected = new Set(props.selectedCellKeys ?? []);
  const fieldsInSelection = fields.value.filter((field) =>
    selected.has(`${props.row.sourceIndex}:${field.sourceIndex}`)).map((field) => field.sourceIndex);
  if (props.selectionMode === "rows" && props.selectedRowSources?.includes(props.row.sourceIndex)) {
    return fields.value.map((field) => field.sourceIndex);
  }
  return fieldsInSelection;
});
const localSelectionMode = ref<"cells" | "rows" | "columns">("cells");
const localSelectedCellKeys = ref<string[]>([]);
const localSelectedRowSources = ref<number[]>([]);
const localSelectedColumnSources = ref<number[]>([]);
const localFocusedCellKey = ref<string>();
const selectionAnchor = ref<{ row: number; column: number }>();
const selecting = ref(false);

function syncSelectionFromSource(): void {
  localSelectionMode.value = props.selectionMode;
  localSelectedColumnSources.value = [];
  localFocusedCellKey.value = undefined;
  selectionAnchor.value = undefined;
  if (props.selectionMode === "rows" && props.selectedRowSources?.includes(props.row.sourceIndex)) {
    localSelectedCellKeys.value = [];
    localSelectedRowSources.value = fields.value.map((field) => field.sourceIndex);
    return;
  }
  localSelectedRowSources.value = [];
  localSelectedCellKeys.value = sourceSelectedFieldIndices.value.flatMap((fieldIndex) =>
    gridColumns.value.map((column) => `${fieldIndex}:${column.sourceIndex}`));
}
watch([() => props.row.sourceIndex, () => props.selectionMode, () => props.selectedRowSources?.join(","),
  () => props.selectedCellKeys?.join(",")],
  syncSelectionFromSource, { immediate: true });

const selectedGridCellKeys = computed(() => localSelectedCellKeys.value);
const selectedGridRowSources = computed(() => localSelectedRowSources.value);
const selectedGridColumnSources = computed(() => localSelectedColumnSources.value);
const focusedGridCellKey = computed(() => localFocusedCellKey.value);
const gridSelectionMode = computed(() => localSelectionMode.value);
const editingGridCell = computed(() => props.editingColumnIndex === undefined ? undefined : {
  rowIndex: props.editingColumnIndex,
  columnIndex: 1
});
const gridCellStates = computed(() => {
  const result: Record<string, "pending" | "posted" | "error"> = {};
  for (const field of fields.value) {
    const state = props.cellStates?.[`${props.row.sourceIndex}:${field.sourceIndex}`];
    if (state) result[`${field.sourceIndex}:1`] = state;
  }
  return result;
});

function fieldAt(rowIndex: number): number | undefined { return fields.value[rowIndex]?.sourceIndex; }
function fieldRowIndex(fieldIndex: number): number {
  return fields.value.findIndex((field) => field.sourceIndex === fieldIndex);
}
function keyFor(fieldIndex: number, columnIndex: number): string {
  return `${fieldIndex}:${columnIndex}`;
}
function keysInRange(start: { row: number; column: number }, end: { row: number; column: number }): string[] {
  const firstRow = Math.min(start.row, end.row);
  const lastRow = Math.max(start.row, end.row);
  const firstColumn = Math.min(start.column, end.column);
  const lastColumn = Math.max(start.column, end.column);
  const keys: string[] = [];
  for (let rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
    const fieldIndex = fieldAt(rowIndex);
    if (fieldIndex === undefined) continue;
    for (let columnIndex = firstColumn; columnIndex <= lastColumn; columnIndex++) {
      const column = gridColumns.value[columnIndex];
      if (column) keys.push(keyFor(fieldIndex, column.sourceIndex));
    }
  }
  return keys;
}
function setSqlPermission(): void {
  emit("selection-change", localSelectionMode.value === "cells"
    && localSelectedCellKeys.value.length > 0
    && localSelectedCellKeys.value.every((key) => Number(key.split(":")[1]) === 1));
}

function startCellSelection(event: PointerEvent, rowIndex: number, columnIndex: number): void {
  if (event.button !== 0) return;
  event.preventDefault();
  const fieldIndex = fieldAt(rowIndex);
  const column = gridColumns.value[columnIndex];
  if (fieldIndex === undefined || !column) return;
  localSelectionMode.value = "cells";
  localSelectedRowSources.value = [];
  localSelectedColumnSources.value = [];
  const point = { row: rowIndex, column: columnIndex };
  if (event.ctrlKey || event.metaKey) {
    const key = keyFor(fieldIndex, column.sourceIndex);
    localSelectedCellKeys.value = localSelectedCellKeys.value.includes(key)
      ? localSelectedCellKeys.value.filter((item) => item !== key)
      : [...localSelectedCellKeys.value, key];
    localFocusedCellKey.value = key;
    selectionAnchor.value = point;
    selecting.value = false;
  } else {
    selectionAnchor.value = event.shiftKey && selectionAnchor.value ? selectionAnchor.value : point;
    localSelectedCellKeys.value = keysInRange(selectionAnchor.value, point);
    localFocusedCellKey.value = keyFor(fieldIndex, column.sourceIndex);
    selecting.value = true;
  }
  setSqlPermission();
  window.removeEventListener("pointerup", finishSelection);
  if (selecting.value) window.addEventListener("pointerup", finishSelection, { once: true });
  emit("cell-pointerdown", event, fieldIndex, column.sourceIndex, columnIndex === 1);
}
function extendCellSelection(rowIndex: number, columnIndex: number): void {
  if (!selecting.value || !selectionAnchor.value) return;
  const fieldIndex = fieldAt(rowIndex);
  const column = gridColumns.value[columnIndex];
  if (fieldIndex === undefined || !column) return;
  localSelectedCellKeys.value = keysInRange(selectionAnchor.value, { row: rowIndex, column: columnIndex });
  localFocusedCellKey.value = keyFor(fieldIndex, column.sourceIndex);
  setSqlPermission();
  emit("cell-pointerenter", fieldIndex, column.sourceIndex, columnIndex === 1);
}
function finishSelection(): void {
  selecting.value = false;
  window.removeEventListener("pointerup", finishSelection);
}
function startColumnSelection(event: PointerEvent, columnIndex: number): void {
  if (event.button !== 0) return;
  event.preventDefault();
  const column = gridColumns.value[columnIndex];
  if (!column) return;
  localSelectionMode.value = "columns";
  localSelectedCellKeys.value = [];
  localSelectedRowSources.value = [];
  localSelectedColumnSources.value = event.ctrlKey || event.metaKey
    ? localSelectedColumnSources.value.includes(column.sourceIndex)
      ? localSelectedColumnSources.value.filter((item) => item !== column.sourceIndex)
      : [...localSelectedColumnSources.value, column.sourceIndex]
    : [column.sourceIndex];
  selecting.value = true;
  setSqlPermission();
  window.removeEventListener("pointerup", finishSelection);
  window.addEventListener("pointerup", finishSelection, { once: true });
}
function extendColumnSelection(columnIndex: number): void {
  if (localSelectionMode.value !== "columns" || !selecting.value) return;
  const start = localSelectedColumnSources.value[0];
  const end = gridColumns.value[columnIndex]?.sourceIndex;
  if (start === undefined || end === undefined) return;
  const first = Math.min(start, end); const last = Math.max(start, end);
  localSelectedColumnSources.value = gridColumns.value.map((column) => column.sourceIndex)
    .filter((source) => source >= first && source <= last);
}
function forwardCellPointerdown(event: PointerEvent, rowIndex: number, columnIndex: number): void {
  startCellSelection(event, rowIndex, columnIndex);
}
function forwardCellPointerenter(rowIndex: number, columnIndex: number): void {
  extendCellSelection(rowIndex, columnIndex);
}
function forwardCellContextmenu(event: MouseEvent, rowIndex: number, columnIndex: number): void {
  const fieldIndex = fieldAt(rowIndex);
  const column = gridColumns.value[columnIndex];
  if (fieldIndex === undefined || !column) return;
  event.preventDefault();
  const key = keyFor(fieldIndex, column.sourceIndex);
  if (!localSelectedCellKeys.value.includes(key)) {
    localSelectionMode.value = "cells";
    localSelectedRowSources.value = [];
    localSelectedColumnSources.value = [];
    localSelectedCellKeys.value = [key];
    localFocusedCellKey.value = key;
  }
  setSqlPermission();
  emit("cell-contextmenu", event, fieldIndex, column.sourceIndex, columnIndex === 1);
}
function forwardCellDblclick(rowIndex: number, columnIndex: number): void {
  if (columnIndex !== 1) return;
  const fieldIndex = fieldAt(rowIndex);
  if (fieldIndex !== undefined) emit("cell-dblclick", fieldIndex);
}
function forwardRowPointerdown(event: PointerEvent, fieldIndex: number): void {
  if (fieldRowIndex(fieldIndex) < 0 || event.button !== 0) return;
  event.preventDefault();
  localSelectionMode.value = "rows";
  localSelectedCellKeys.value = [];
  localSelectedColumnSources.value = [];
  localSelectedRowSources.value = [fieldIndex];
  setSqlPermission();
  emit("row-pointerdown", event, fieldIndex);
}
function forwardRowPointerenter(fieldIndex: number): void {
  if (localSelectionMode.value !== "rows") return;
  localSelectedRowSources.value = [fieldIndex];
  setSqlPermission();
  emit("row-pointerenter", fieldIndex);
}
function forwardRowContextmenu(event: MouseEvent, fieldIndex: number): void {
  event.preventDefault();
  localSelectionMode.value = "rows";
  localSelectedCellKeys.value = [];
  localSelectedColumnSources.value = [];
  localSelectedRowSources.value = [fieldIndex];
  setSqlPermission();
  emit("row-contextmenu", event, fieldIndex);
}

function selectedCopyRows(): ViewRow[] {
  if (localSelectionMode.value === "rows") {
    const selected = new Set(localSelectedRowSources.value);
    return fields.value.filter((field) => selected.has(field.sourceIndex));
  }
  if (localSelectionMode.value === "columns") return fields.value;
  const selectedRows = new Set(localSelectedCellKeys.value.map((key) => Number(key.split(":")[0])));
  return fields.value.filter((field) => selectedRows.has(field.sourceIndex));
}

function getCopyText(includeHeaders = false): string | undefined {
  let columns = gridColumns.value;
  let rows = selectedCopyRows();
  if (localSelectionMode.value === "columns") {
    const selected = new Set(localSelectedColumnSources.value);
    columns = columns.filter((column) => selected.has(column.sourceIndex));
  } else if (localSelectionMode.value === "cells") {
    const selected = new Set(localSelectedCellKeys.value);
    if (!selected.size) return undefined;
    const selectedColumns = localSelectedCellKeys.value.map((key) => Number(key.split(":")[1]));
    const firstColumn = Math.min(...selectedColumns);
    const lastColumn = Math.max(...selectedColumns);
    columns = columns.filter((column) => column.sourceIndex >= firstColumn && column.sourceIndex <= lastColumn);
    rows = rows.map((row) => ({
      sourceIndex: row.sourceIndex,
      cells: row.cells.map((value, columnIndex) => selected.has(keyFor(row.sourceIndex, columnIndex)) ? value : "")
    }));
  }
  if (!columns.length || !rows.length) return undefined;
  return copyGrid(columns.map((column) => ({ label: column.label, index: column.sourceIndex })),
    rows, includeHeaders, props.copySeparator);
}

onBeforeUnmount(() => window.removeEventListener("pointerup", finishSelection));

defineExpose({
  getCopyText,
  getScrollPosition: () => grid.value?.getScrollPosition() ?? { left: 0, top: 0 },
  setScrollPosition: (position: { left: number; top: number }) => grid.value?.setScrollPosition(position),
});
</script>

<style scoped>
.result-single-record-view {
  box-sizing: border-box;
  width: 100%;
  height: 100%;
  min-height: 0;
}
:deep(.single-record-header-label) {
  box-sizing: border-box;
  display: flex;
  width: 100%;
  height: 100%;
  padding: 0 10px;
  align-items: center;
  color: var(--db-text-secondary);
  font-weight: 600;
}
:deep(.single-record-header-label.selected) {
  background: var(--db-accent-soft);
  color: var(--db-text);
}
:deep(.result-cell-readonly) {
  background: color-mix(in srgb, var(--db-table-header) 72%, var(--db-content) 28%);
  color: var(--db-text-secondary);
}
:deep(.result-cell.selected),
:deep(.result-cell.column-selected) {
  background: var(--db-accent-soft);
  color: var(--db-text);
}
</style>
