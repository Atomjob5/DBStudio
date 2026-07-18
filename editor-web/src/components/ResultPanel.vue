<template>
  <section class="result-panel fill">
    <template v-if="execution?.results.length">
      <div class="result-header">
        <el-tabs v-model="activeIndex" class="result-tabs">
          <el-tab-pane v-for="result in execution.results" :key="result.resultIndex" :name="result.resultIndex"
                       :label="result.errorMessage ? `错误 ${result.resultIndex + 1}` : `结果 ${result.resultIndex + 1}`" />
        </el-tabs>
        <div class="result-meta" aria-live="polite">
          <span>{{ summary }}</span>
          <el-tag v-if="activeResult?.truncated" size="small" type="warning" effect="plain">已截断</el-tag>
        </div>
        <div class="result-actions" aria-label="结果操作">
          <el-tooltip v-if="showRestoreLayout" content="复原列顺序和宽度">
            <el-button text :icon="RefreshLeft" aria-label="复原列布局" @click="restoreLayout" />
          </el-tooltip>
          <el-select v-model="selectedColumnIndices" multiple filterable clearable collapse-tags collapse-tags-tooltip
                     :max-collapse-tags="1" :filter-method="filterColumns" placeholder="筛选字段" size="small"
                     aria-label="筛选展示字段">
            <el-option v-for="column in filteredColumnOptions" :key="column.index" :label="column.label" :value="column.index">
              <div class="column-option">
                <span>{{ column.label }}</span>
                <small v-if="optionDetail(column)">{{ optionDetail(column) }}</small>
              </div>
            </el-option>
          </el-select>
          <el-tooltip content="复制选中单元格">
            <el-button text :icon="CopyDocument" aria-label="复制选中单元格" :disabled="!selectedCell" @click="copyCell" />
          </el-tooltip>
          <el-dropdown :disabled="!activeResult?.columns.length" @command="exportCommand">
            <el-button text :icon="Download" aria-label="导出结果" title="导出结果" />
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="loaded">导出已加载行</el-dropdown-item>
                <el-dropdown-item command="full">重新执行并完整导出</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
      <el-alert v-if="activeResult?.errorMessage" :title="activeResult.errorMessage" type="error" show-icon :closable="false" />
      <div v-else-if="activeResult?.columns.length" ref="tableHost" class="table-host">
        <el-auto-resizer v-slot="{ width, height }">
          <el-table-v2 :columns="tableColumns" :data="tableRows" :width="width" :height="height"
                       :row-height="32" :header-height="32" fixed />
        </el-auto-resizer>
      </div>
      <el-result v-else icon="success" title="语句执行完成" :sub-title="`影响行数：${activeResult?.updateCount ?? 0}`" />
    </template>
    <el-empty v-else class="result-empty" description="执行查询后在这里查看结果">
      <template #image><el-icon><DataAnalysis /></el-icon></template>
    </el-empty>
    <ResultHeaderContextMenu :visible="headerMenu.visible" :x="headerMenu.x" :y="headerMenu.y"
                             :can-copy-data="canCopyHeaderData" :can-move-left="canMoveSelectionLeft"
                             :can-move-right="canMoveSelectionRight" @close="closeHeaderMenu"
                             @command="headerMenuCommand" />
  </section>
</template>

<script setup lang="ts">
import { computed, h, onBeforeUnmount, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { CopyDocument, DataAnalysis, Download, RefreshLeft } from "@element-plus/icons-vue";
import type { Column } from "element-plus";
import type { QueryExecutionState } from "../types";
import { matchesColumnQuery, resultColumnOptions, type ColumnOption } from "../columnFilter";
import { autoColumnWidth, clampColumnWidth, columnIdentityKeys, defaultColumnWidth, moveColumnsToEdge,
  type ColumnEdge, type DropSide } from "../columnLayout";
import { useColumnLayoutStore } from "../stores/columnLayout";
import { useSettingsStore } from "../stores/settings";
import { resultCopyText, type ResultCopyMode } from "../resultCopy";
import { writeClipboardText } from "../clipboard";
import ResultHeaderContextMenu, { type HeaderMenuCommand } from "./ResultHeaderContextMenu.vue";

const props = defineProps<{
  execution?: QueryExecutionState;
  activeResultIndex: number;
}>();
const emit = defineEmits<{
  "export-loaded": [resultIndex: number];
  "export-full": [resultIndex: number];
  "update:active-result-index": [resultIndex: number];
}>();
const columnLayouts = useColumnLayoutStore();
const settings = useSettingsStore();
const tableHost = ref<HTMLElement>();
const activeLayout = ref<{ layoutKey: string; viewKey: string; identities: string[] }>();
const dropTarget = ref<{ identity: string; side: DropSide }>();
const resizing = ref<{ identity: string; startX: number; startWidth: number }>();
const headerMenu = ref({ visible: false, x: 0, y: 0 });
let dragPreview: HTMLElement | undefined;
let measureContext: CanvasRenderingContext2D | null | undefined;
const activeIndex = computed({
  get: () => props.activeResultIndex,
  set: (value: number) => emit("update:active-result-index", value)
});
const selectedColumns = ref<Record<string, number[]>>({});
const columnQuery = ref("");
const selectedCell = ref<{ row: number; column: number; value: string | null }>();
const activeResult = computed(() => props.execution?.results.find((item) => item.resultIndex === activeIndex.value) ?? props.execution?.results[0]);
const resultKey = computed(() => String(activeResult.value?.resultIndex ?? 0));
const selectedColumnIndices = computed<number[]>({
  get: () => selectedColumns.value[resultKey.value] ?? [],
  set: (value) => { selectedColumns.value = { ...selectedColumns.value, [resultKey.value]: value }; }
});
const columnOptions = computed(() => resultColumnOptions(activeResult.value?.columns ?? [], activeResult.value?.columnDetails));
const filteredColumnOptions = computed(() => columnOptions.value.filter((column) => matchesColumnQuery(column, columnQuery.value)));
const selectedVisibleColumnOptions = computed(() => {
  if (!selectedColumnIndices.value.length) return columnOptions.value;
  const selected = new Set(selectedColumnIndices.value);
  return columnOptions.value.filter((column) => selected.has(column.index));
});
const defaultWidths = computed(() => columnOptions.value.map((column) => defaultColumnWidth(column.label)));
const currentIdentities = computed(() => activeLayout.value?.identities
  ?? columnIdentityKeys(activeResult.value?.columns ?? [], activeResult.value?.columnDetails));
const visibleIdentities = computed(() => selectedVisibleColumnOptions.value.map((column) => currentIdentities.value[column.index]));
const visibleColumnOptions = computed(() => {
  const active = activeLayout.value;
  if (!active) return selectedVisibleColumnOptions.value;
  const byIdentity = new Map(selectedVisibleColumnOptions.value.map((column) => [currentIdentities.value[column.index], column]));
  return columnLayouts.displayedOrder(active.layoutKey, active.viewKey, visibleIdentities.value,
    selectedColumnIndices.value.length > 0).map((identity) => byIdentity.get(identity)).filter((column): column is ColumnOption => !!column);
});
const summary = computed(() => {
  const result = activeResult.value;
  if (!result) return "";
  if (props.execution?.busy) return "正在执行…";
  return result.columns.length ? `${result.rows.length} 行 · ${result.durationMs} ms` : `${result.updateCount} 行受影响 · ${result.durationMs} ms`;
});

watch(() => props.execution?.executionId, () => {
  selectedCell.value = undefined;
  selectedColumns.value = {};
  columnQuery.value = "";
  closeHeaderMenu();
});
watch(activeIndex, () => { selectedCell.value = undefined; columnQuery.value = ""; closeHeaderMenu(); });
watch([
  () => props.execution?.executionId,
  () => props.execution?.editorId,
  () => activeResult.value?.resultIndex,
  () => activeResult.value?.columns,
  () => activeResult.value?.columnDetails,
  () => settings.columnLayoutScope
], activateLayout, { immediate: true });
watch([() => activeLayout.value?.viewKey, () => selectedColumnIndices.value.join(",")], syncVisibleFilter);

const tableRows = computed(() => activeResult.value?.rows ?? []);

const tableColumns = computed<Column[]>(() => visibleColumnOptions.value.map((column) => ({
  ...columnDefinition(column),
})));

function columnDefinition(column: ColumnOption): Column {
  const identity = currentIdentities.value[column.index];
  const stored = activeLayout.value ? columnLayouts.layout(activeLayout.value.layoutKey) : undefined;
  return {
  key: `c${column.index}`,
  dataKey: column.index,
  title: column.label,
  width: stored?.widths[identity] ?? defaultColumnWidth(column.label),
  minWidth: 72,
  maxWidth: 800,
  headerCellRenderer: () => renderHeader(column, identity),
  cellRenderer: ({ cellData, rowIndex }: { cellData: string | null; rowIndex: number }) => h("span", {
    class: ["result-cell", cellData === null ? "null-value" : cellData.startsWith?.("0x") ? "binary-value" : "", selectedCell.value?.row === rowIndex && selectedCell.value?.column === column.index ? "selected" : ""],
    title: cellData !== null && cellData.length >= 40 ? cellData : undefined,
    onClick: () => { selectedCell.value = { row: rowIndex, column: column.index, value: cellData }; }
  }, cellData === null ? "NULL" : cellData)
  };
}

function activateLayout(): void {
  const result = activeResult.value;
  const execution = props.execution;
  if (!result || !execution) { activeLayout.value = undefined; return; }
  activeLayout.value = columnLayouts.ensure({
    scope: settings.columnLayoutScope, executionId: execution.executionId, editorId: execution.editorId,
    result, defaultWidths: result.columns.map((label) => defaultColumnWidth(label))
  });
  syncVisibleFilter();
}

function syncVisibleFilter(): void {
  const active = activeLayout.value;
  if (!active) return;
  columnLayouts.setFilter(active.viewKey, visibleIdentities.value, selectedColumnIndices.value.length > 0);
}

function renderHeader(column: ColumnOption, identity: string) {
  const active = activeLayout.value;
  const view = active ? columnLayouts.view(active.viewKey) : undefined;
  const selected = !!view?.selected.includes(identity);
  const order = visibleColumnOptions.value.map((item) => currentIdentities.value[item.index]);
  const firstSelected = view?.selected.find((item) => order.includes(item));
  const drop = dropTarget.value?.identity === identity ? dropTarget.value.side : undefined;
  return h("div", {
    class: ["result-column-header", selected ? "selected" : "", drop ? `drop-${drop}` : ""],
    style: { flex: "1 1 auto", alignSelf: "stretch", width: "100%", minWidth: 0 },
    role: "button", tabindex: 0, draggable: !resizing.value,
    "aria-selected": String(selected), "aria-label": `列 ${column.label}`,
    title: settings.copyHeaderOnDoubleClick
      ? "单击选择；双击复制列名；右键打开菜单；拖动改变位置"
      : "单击选择；右键打开菜单；拖动改变位置",
    onClick: (event: MouseEvent) => selectColumnHeader(event, identity),
    onKeydown: (event: KeyboardEvent) => keyboardSelectHeader(event, identity),
    onContextmenu: (event: MouseEvent) => openHeaderMenu(event, identity),
    onDragstart: (event: DragEvent) => startColumnDrag(event, identity),
    onDragover: (event: DragEvent) => overColumn(event, identity),
    onDrop: (event: DragEvent) => dropColumn(event, identity),
    onDragend: endColumnDrag
  }, [
    h("span", {
      class: "result-column-title",
      onDblclick: (event: MouseEvent) => copyDoubleClickedHeader(event, column)
    }, column.label),
    selected && view && view.selected.length > 1 && firstSelected === identity
      ? h("span", { class: "column-selection-count" }, `${view.selected.length}列`) : undefined,
    h("span", {
      class: "column-resize-handle", role: "separator", tabindex: 0,
      "aria-label": `调整 ${column.label} 列宽`, title: "拖动调整列宽，双击自动匹配",
      onClick: (event: MouseEvent) => event.stopPropagation(),
      onPointerdown: (event: PointerEvent) => startColumnResize(event, identity),
      onDblclick: (event: MouseEvent) => fitColumnWidth(event, column, identity),
      onKeydown: (event: KeyboardEvent) => keyboardResizeColumn(event, column, identity)
    })
  ]);
}

function selectColumnHeader(event: MouseEvent, identity: string): void {
  const active = activeLayout.value;
  if (!active || resizing.value) return;
  const order = visibleColumnOptions.value.map((column) => currentIdentities.value[column.index]);
  columnLayouts.choose(active.viewKey, order, identity, event.ctrlKey || event.metaKey, event.shiftKey);
}

function keyboardSelectHeader(event: KeyboardEvent, identity: string): void {
  if (event.key === "ContextMenu" || (event.shiftKey && event.key === "F10")) {
    event.preventDefault();
    openHeaderMenu(event as unknown as MouseEvent, identity, event.currentTarget as HTMLElement);
    return;
  }
  if (event.key !== " " && event.key !== "Enter") return;
  event.preventDefault();
  selectColumnHeader(event as unknown as MouseEvent, identity);
}

function openHeaderMenu(event: MouseEvent, identity: string, keyboardTarget?: HTMLElement): void {
  const active = activeLayout.value;
  if (!active) return;
  event.preventDefault();
  const view = columnLayouts.view(active.viewKey);
  if (!view.selected.includes(identity)) columnLayouts.selectOnly(active.viewKey, identity);
  const bounds = keyboardTarget?.getBoundingClientRect();
  const requestedX = bounds ? bounds.left + 16 : event.clientX;
  const requestedY = bounds ? bounds.bottom : event.clientY;
  headerMenu.value = {
    visible: true,
    x: Math.max(8, Math.min(requestedX, window.innerWidth - 188)),
    // Reserve the fully expanded copy submenu height so opening it never leaves the viewport.
    y: Math.max(8, Math.min(requestedY, window.innerHeight - 360))
  };
}

function closeHeaderMenu(): void { headerMenu.value = { ...headerMenu.value, visible: false }; }

function selectedOrderedColumns(): ColumnOption[] {
  const active = activeLayout.value;
  if (!active) return [];
  const selected = new Set(columnLayouts.view(active.viewKey).selected);
  return visibleColumnOptions.value.filter((column) => selected.has(currentIdentities.value[column.index]));
}

const canCopyHeaderData = computed(() => (activeResult.value?.rows.length ?? 0) > 0);
const canMoveSelectionLeft = computed(() => canMoveSelection("left"));
const canMoveSelectionRight = computed(() => canMoveSelection("right"));

function canMoveSelection(edge: ColumnEdge): boolean {
  const active = activeLayout.value;
  if (!active) return false;
  const order = visibleColumnOptions.value.map((column) => currentIdentities.value[column.index]);
  const selected = columnLayouts.view(active.viewKey).selected;
  return moveColumnsToEdge(order, selected, edge) !== order;
}

function headerMenuCommand(command: HeaderMenuCommand): void {
  if (command === "move-left" || command === "move-right") {
    moveSelectedColumns(command === "move-left" ? "left" : "right");
    return;
  }
  const mode: ResultCopyMode = command === "copy-headers" ? "headers"
    : command === "copy-data" ? "data" : "headers-and-data";
  void copySelectedColumns(mode);
}

function moveSelectedColumns(edge: ColumnEdge): void {
  const active = activeLayout.value;
  if (!active) return;
  const order = visibleColumnOptions.value.map((column) => currentIdentities.value[column.index]);
  columnLayouts.moveToEdge(active.layoutKey, active.viewKey, order, edge, selectedColumnIndices.value.length > 0);
}

async function copySelectedColumns(mode: ResultCopyMode): Promise<void> {
  const columns = selectedOrderedColumns();
  if (!columns.length) return;
  const text = resultCopyText(columns.map((column) => ({ label: column.label, index: column.index })),
    activeResult.value?.rows ?? [], mode, settings.copySeparator);
  await copyText(text, mode === "headers" ? "已复制列名" : mode === "data" ? "已复制列数据" : "已复制列名和数据");
}

function copyDoubleClickedHeader(event: MouseEvent, column: ColumnOption): void {
  event.preventDefault(); event.stopPropagation();
  if (!settings.copyHeaderOnDoubleClick) return;
  void copyText(resultCopyText([{ label: column.label, index: column.index }], [], "headers", settings.copySeparator), "已复制列名");
}

function startColumnDrag(event: DragEvent, identity: string): void {
  const active = activeLayout.value;
  if (!active || resizing.value || !event.dataTransfer) { event.preventDefault(); return; }
  if (!columnLayouts.view(active.viewKey).selected.includes(identity)) columnLayouts.selectOnly(active.viewKey, identity);
  const count = columnLayouts.view(active.viewKey).selected.length;
  event.dataTransfer.effectAllowed = "move";
  event.dataTransfer.setData("text/plain", identity);
  dragPreview = document.createElement("div");
  dragPreview.className = "column-drag-preview";
  dragPreview.textContent = count > 1 ? `移动 ${count} 列` : "移动列";
  document.body.appendChild(dragPreview);
  event.dataTransfer.setDragImage(dragPreview, 12, 12);
}

function overColumn(event: DragEvent, identity: string): void {
  if (!activeLayout.value || !event.dataTransfer) return;
  event.preventDefault();
  event.dataTransfer.dropEffect = "move";
  const bounds = (event.currentTarget as HTMLElement).getBoundingClientRect();
  dropTarget.value = { identity, side: event.clientX < bounds.left + bounds.width / 2 ? "before" : "after" };
}

function dropColumn(event: DragEvent, identity: string): void {
  event.preventDefault();
  const active = activeLayout.value;
  const side = dropTarget.value?.identity === identity ? dropTarget.value.side : "before";
  if (active) {
    const order = visibleColumnOptions.value.map((column) => currentIdentities.value[column.index]);
    columnLayouts.reorder(active.layoutKey, active.viewKey, order, identity, side, selectedColumnIndices.value.length > 0);
  }
  endColumnDrag();
}

function endColumnDrag(): void {
  dropTarget.value = undefined;
  dragPreview?.remove();
  dragPreview = undefined;
}

function startColumnResize(event: PointerEvent, identity: string): void {
  const active = activeLayout.value;
  if (!active) return;
  event.preventDefault(); event.stopPropagation();
  const stored = columnLayouts.layout(active.layoutKey);
  resizing.value = { identity, startX: event.clientX, startWidth: stored?.widths[identity] ?? 120 };
  window.addEventListener("pointermove", resizeColumn);
  window.addEventListener("pointerup", finishColumnResize, { once: true });
}

function resizeColumn(event: PointerEvent): void {
  const active = activeLayout.value;
  const state = resizing.value;
  if (!active || !state) return;
  columnLayouts.setWidth(active.layoutKey, state.identity,
    clampColumnWidth(state.startWidth + event.clientX - state.startX));
}

function finishColumnResize(): void {
  window.removeEventListener("pointermove", resizeColumn);
  resizing.value = undefined;
}

function fitColumnWidth(event: MouseEvent, column: ColumnOption, identity: string): void {
  event.preventDefault(); event.stopPropagation();
  const active = activeLayout.value;
  const rows = activeResult.value?.rows;
  if (!active || !rows) return;
  columnLayouts.setWidth(active.layoutKey, identity,
    autoColumnWidth(column.label, rows, column.index, measureText));
}

function keyboardResizeColumn(event: KeyboardEvent, column: ColumnOption, identity: string): void {
  const active = activeLayout.value;
  if (!active) return;
  if (event.key === "Enter") {
    fitColumnWidth(event as unknown as MouseEvent, column, identity);
    return;
  }
  if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
  event.preventDefault(); event.stopPropagation();
  const width = columnLayouts.layout(active.layoutKey)?.widths[identity] ?? defaultColumnWidth(column.label);
  columnLayouts.setWidth(active.layoutKey, identity,
    clampColumnWidth(width + (event.key === "ArrowRight" ? 10 : -10)));
}

function measureText(text: string): number {
  if (measureContext === undefined) {
    const canvas = document.createElement("canvas");
    measureContext = canvas.getContext("2d");
  }
  if (!measureContext) return Array.from(text).length * 8;
  measureContext.font = tableHost.value ? getComputedStyle(tableHost.value).font : "12px sans-serif";
  return measureContext.measureText(text).width;
}

const showRestoreLayout = computed(() => settings.columnLayoutScope === "editor"
  && !!activeLayout.value && columnLayouts.orderDirty(activeLayout.value.layoutKey));

function restoreLayout(): void {
  const active = activeLayout.value;
  if (!active) return;
  columnLayouts.reset(active.layoutKey, active.viewKey, active.identities, defaultWidths.value);
  ElMessage.success("已复原列布局");
}

function filterColumns(query: string): void { columnQuery.value = query; }
function optionDetail(column: ColumnOption): string {
  const values: string[] = [];
  if (column.name && column.name !== column.label) values.push(column.name);
  if (column.remarks) values.push(column.remarks);
  return values.join(" · ");
}

async function copyCell(): Promise<void> {
  const text = selectedCell.value?.value ?? "NULL";
  await copyText(text, "已复制单元格");
}

async function copyText(text: string, successMessage: string): Promise<void> {
  try {
    await writeClipboardText(text);
    ElMessage.success(successMessage);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "复制失败");
  }
}

function exportCommand(command: string): void {
  const resultIndex = activeResult.value?.resultIndex;
  if (resultIndex === undefined) return;
  if (command === "loaded") emit("export-loaded", resultIndex);
  else if (command === "full") emit("export-full", resultIndex);
}

onBeforeUnmount(() => { finishColumnResize(); endColumnDrag(); closeHeaderMenu(); });
</script>

<style scoped>
.result-panel { display: flex; flex-direction: column; background: var(--db-content); }
.result-header {
  min-height: 38px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 7px 0 10px;
  border-bottom: 1px solid var(--db-border-soft);
  background: var(--db-panel-soft);
}
.result-tabs { min-width: 100px; max-width: 38%; }
.result-meta { display: inline-flex; align-items: center; gap: 7px; color: var(--db-muted); font-size: 11px; white-space: nowrap; }
.result-actions { margin-left: auto; display: inline-flex; align-items: center; gap: 2px; }
.result-actions .el-select { width: 210px; }
.result-actions :deep(.el-button) { width: 28px; min-height: 28px; padding: 0; }
.result-actions :deep(.el-dropdown) { display: inline-flex; }
.column-option { min-width: 0; display: flex; align-items: baseline; justify-content: space-between; gap: 14px; }
.column-option span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.column-option small { overflow: hidden; color: var(--db-muted); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.table-host { flex: 1; min-height: 0; }
:deep(.el-table-v2__header-cell) { padding: 0; }
.result-empty { flex: 1; }
.result-empty :deep(.el-empty__image) { width: auto; height: auto; }
.result-empty :deep(.el-empty__image .el-icon) {
  width: 44px; height: 44px; border-radius: 13px; background: var(--db-control-bg);
  color: var(--db-text-secondary); font-size: 22px; box-shadow: inset 0 0 0 1px var(--db-border-soft);
}
:deep(.result-tabs .el-tabs__content) { display: none; }
:deep(.result-tabs .el-tabs__header) { height: 37px; }
:deep(.result-tabs .el-tabs__nav-wrap::after) { display: none; }
:deep(.result-tabs .el-tabs__item) { height: 37px; padding: 0 10px; font-size: 12px; }
:deep(.result-cell) {
  display: block;
  width: calc(100% - 6px);
  height: 27px;
  margin: 2px 3px;
  padding: 0 7px;
  overflow: hidden;
  border-radius: 6px;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 27px;
  cursor: default;
}
:deep(.result-cell.selected) { outline: 1.5px solid var(--db-accent); background: var(--db-accent-soft); }

@media (max-width: 1080px) {
  .result-meta { display: none; }
  .result-tabs { max-width: 45%; }
}
</style>

<style>
.result-column-header {
  position: relative; display: flex; width: 100%; height: 100%; align-items: center; gap: 5px;
  padding: 0 10px; outline: none; user-select: none; cursor: grab;
}
.result-column-header:active { cursor: grabbing; }
.result-column-header.selected { background: var(--db-accent-soft); color: var(--db-accent); }
.result-column-header:focus-visible { box-shadow: inset 0 0 0 1.5px var(--db-accent); }
.result-column-header.drop-before::before, .result-column-header.drop-after::after {
  position: absolute; z-index: 2; top: 2px; bottom: 2px; width: 2px; border-radius: 2px;
  background: var(--db-accent); content: "";
}
.result-column-header.drop-before::before { left: 0; }
.result-column-header.drop-after::after { right: 0; }
.result-column-title { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.column-selection-count {
  flex: none; padding: 1px 5px; border-radius: 8px; background: var(--db-accent); color: #fff; font-size: 9px;
}
.column-resize-handle {
  position: absolute; z-index: 3; top: 3px; right: -3px; bottom: 3px; width: 7px; cursor: col-resize;
}
.column-resize-handle::after {
  position: absolute; top: 4px; right: 3px; bottom: 4px; width: 1px; background: var(--db-border-soft); content: "";
}
.column-drag-preview {
  position: fixed; top: -1000px; left: -1000px; padding: 5px 9px; border: 1px solid var(--db-border-soft);
  border-radius: 8px; background: var(--db-content); color: var(--db-text); font: 12px/1.2 sans-serif;
  box-shadow: 0 6px 18px rgba(0,0,0,.16);
}
</style>
