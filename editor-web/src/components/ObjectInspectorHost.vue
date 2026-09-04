<template>
  <Teleport to="body">
    <section v-for="windowState in windows" :key="windowState.id" class="object-inspector"
             :class="{ pinned: windowState.pinned }" :style="windowStyle(windowState)"
             tabindex="-1" @pointerdown="bringToFront(windowState)" @mouseenter="windowState.hovered = true"
             @mouseleave="windowState.hovered = false" @focusin="windowState.focused = true"
             @focusout="windowState.focused = false">
      <header class="inspector-titlebar" @pointerdown="startMove($event, windowState)">
        <div class="object-title">
          <span class="object-kind">{{ windowState.object?.type === 'VIEW' ? 'V' : 'T' }}</span>
          <span class="object-name" :title="windowState.object?.qualifiedName || windowState.reference.objectName">
            {{ windowState.object?.qualifiedName || windowState.reference.objectName }}
          </span>
          <small :title="windowState.connectionDisplay">{{ windowState.connectionDisplay }}</small>
        </div>
        <div class="title-actions" @pointerdown.stop @click.stop>
          <el-popover v-model:visible="windowState.opacityOpen" placement="bottom-end" :width="208"
                      trigger="click" :teleported="true" popper-class="object-inspector-opacity-popover">
            <template #reference>
              <button class="icon-button" :class="{ active: windowState.opacityOpen }" title="窗口透明度"
                      aria-label="窗口透明度" :aria-expanded="windowState.opacityOpen">
                <el-icon :size="15"><View /></el-icon>
              </button>
            </template>
            <div class="opacity-popover" @pointerdown.stop>
              <div class="opacity-popover__header"><span>窗口透明度</span><strong>{{ opacity }}%</strong></div>
              <el-slider :model-value="opacity" :min="1" :max="100" :show-tooltip="false"
                         aria-label="窗口透明度" @update:model-value="changeOpacity" @change="saveOpacity" />
            </div>
          </el-popover>
          <button class="icon-button" :class="{ active: windowState.pinned }"
                  :title="windowState.pinned ? '取消钉住' : '钉住窗口'"
                  :aria-label="windowState.pinned ? '取消钉住' : '钉住窗口'"
                  @click="togglePin(windowState)">
            <el-icon :size="15"><LocationFilled /></el-icon>
          </button>
          <button class="icon-button" title="关闭" aria-label="关闭" @click="close(windowState.id)">
            <el-icon :size="15"><Close /></el-icon>
          </button>
        </div>
      </header>

      <nav class="inspector-tabs">
        <button v-for="tab in tabs" :key="tab.key" :class="{ active: windowState.activeTab === tab.key }"
                :disabled="windowState.object?.type === 'VIEW' && (tab.key === 'indexes' || tab.key === 'partitions')"
                :title="windowState.object?.type === 'VIEW' && (tab.key === 'indexes' || tab.key === 'partitions') ? '视图不适用' : ''"
                @click="activate(windowState, tab.key)">{{ tab.label }}</button>
      </nav>

      <main class="inspector-content">
        <div v-if="windowState.error" class="section-message error">{{ windowState.error }}</div>
        <div v-else-if="windowState.loading[windowState.activeTab]" class="section-message">正在读取对象结构…</div>
        <template v-else-if="windowState.activeTab === 'columns'">
          <div class="grid-scroll"><table class="structure-grid" :style="{ width: gridTableWidth(windowState, 'columns') }">
            <colgroup><col v-for="(header, index) in gridColumns('columns')" :key="header.key" :style="{ width: `${columnWidth(windowState, 'columns', index)}px` }" /><col class="grid-trailing-gutter" aria-hidden="true" /></colgroup>
            <thead><tr><th v-for="(header, index) in gridColumns('columns')" :key="header.key" :class="header.align"
                  :style="{ width: `${columnWidth(windowState, 'columns', index)}px` }">
              <span class="grid-header-label">{{ header.label }}</span>
              <span class="grid-column-resize-handle" role="separator" tabindex="0"
                    :aria-label="`调整 ${header.label} 列宽`" title="拖动调整列宽，双击自动匹配"
                    @pointerdown.stop.prevent="startColumnResize($event, windowState, 'columns', index)"
                    @dblclick.stop="fitColumnWidth($event, windowState, 'columns', index)"
                    @keydown="keyboardResizeColumn($event, windowState, 'columns', index)" />
            </th><th class="grid-trailing-gutter" aria-hidden="true"></th></tr></thead>
            <tbody><tr v-for="column in windowState.columns" :key="column.ordinal"><td class="text-left" :title="column.name">{{ column.name }}</td><td class="text-left" :title="column.typeName">{{ column.typeName }}</td>
              <td class="text-right" :title="cellTitle(column.length)">{{ column.length || '' }}</td><td class="text-right" :title="precision(column)">{{ precision(column) }}</td><td class="text-center">{{ column.nullable ? '是' : '否' }}</td>
              <td class="text-left" :title="cellTitle(column.defaultValue)">{{ column.defaultValue ?? '' }}</td><td class="text-center">{{ column.primaryKey ? '是' : '' }}</td>
              <td class="text-center">{{ column.autoIncrement ? '自增' : column.generated ? '生成' : '' }}</td><td class="text-left" :title="cellTitle(column.remarks)">{{ column.remarks }}</td><td class="grid-trailing-gutter" aria-hidden="true"></td></tr></tbody></table></div>
        </template>
        <template v-else-if="windowState.activeTab === 'indexes'">
          <div class="grid-scroll"><table class="structure-grid" :style="{ width: gridTableWidth(windowState, 'indexes') }">
            <colgroup><col v-for="(header, index) in gridColumns('indexes')" :key="header.key" :style="{ width: `${columnWidth(windowState, 'indexes', index)}px` }" /><col class="grid-trailing-gutter" aria-hidden="true" /></colgroup>
            <thead><tr><th v-for="(header, index) in gridColumns('indexes')" :key="header.key" :class="header.align"
                  :style="{ width: `${columnWidth(windowState, 'indexes', index)}px` }">
              <span class="grid-header-label">{{ header.label }}</span>
              <span class="grid-column-resize-handle" role="separator" tabindex="0"
                    :aria-label="`调整 ${header.label} 列宽`" title="拖动调整列宽，双击自动匹配"
                    @pointerdown.stop.prevent="startColumnResize($event, windowState, 'indexes', index)"
                    @dblclick.stop="fitColumnWidth($event, windowState, 'indexes', index)"
                    @keydown="keyboardResizeColumn($event, windowState, 'indexes', index)" />
            </th><th class="grid-trailing-gutter" aria-hidden="true"></th></tr></thead>
            <tbody><tr v-for="index in windowState.indexes" :key="index.name"><td class="text-left" :title="cellTitle(index.name)">{{ index.name }}</td><td class="text-center">{{ index.primary ? '是' : '' }}</td>
              <td class="text-center">{{ index.unique ? '是' : '否' }}</td><td class="text-left" :title="cellTitle(index.type)">{{ index.type }}</td><td class="text-left" :title="cellTitle(index.status)">{{ index.status }}</td><td class="text-center">{{ index.visible ? '是' : '否' }}</td>
              <td class="text-center">{{ index.partitioned ? '是' : '' }}</td><td class="text-left" :title="cellTitle(index.tablespace)">{{ index.tablespace }}</td><td class="text-left" :title="cellTitle(index.columns.map(indexColumnLabel).join(', '))">{{ index.columns.map(indexColumnLabel).join(', ') }}</td><td class="grid-trailing-gutter" aria-hidden="true"></td></tr></tbody></table></div>
        </template>
        <template v-else-if="windowState.activeTab === 'partitions'">
          <div class="grid-scroll"><table class="structure-grid" :style="{ width: gridTableWidth(windowState, 'partitions') }">
            <colgroup><col v-for="(header, index) in gridColumns('partitions')" :key="header.key" :style="{ width: `${columnWidth(windowState, 'partitions', index)}px` }" /><col class="grid-trailing-gutter" aria-hidden="true" /></colgroup>
            <thead><tr><th v-for="(header, index) in gridColumns('partitions')" :key="header.key" :class="header.align"
                  :style="{ width: `${columnWidth(windowState, 'partitions', index)}px` }">
              <span class="grid-header-label">{{ header.label }}</span>
              <span class="grid-column-resize-handle" role="separator" tabindex="0"
                    :aria-label="`调整 ${header.label || '展开'} 列宽`" title="拖动调整列宽，双击自动匹配"
                    @pointerdown.stop.prevent="startColumnResize($event, windowState, 'partitions', index)"
                    @dblclick.stop="fitColumnWidth($event, windowState, 'partitions', index)"
                    @keydown="keyboardResizeColumn($event, windowState, 'partitions', index)" />
            </th><th class="grid-trailing-gutter" aria-hidden="true"></th></tr></thead>
            <tbody><template v-for="partition in windowState.partitions" :key="partition.id">
              <tr><td class="text-center"><button v-if="partition.hasSubpartitions" class="expand" :aria-label="windowState.expanded.has(partition.id) ? '收起子分区' : '展开子分区'" @click="togglePartition(windowState, partition)">{{ windowState.expanded.has(partition.id) ? '−' : '+' }}</button></td>
                <td class="text-left" :title="cellTitle(partition.name)">{{ partition.name }}</td><td class="text-right">{{ partition.position }}</td><td class="text-left" :title="cellTitle(partition.method)">{{ partition.method }}</td><td class="text-left" :title="cellTitle(partition.expression)">{{ partition.expression }}</td><td class="text-left" :title="cellTitle(partition.boundary)">{{ partition.boundary }}</td>
                <td class="text-left" :title="cellTitle(partition.tablespace)">{{ partition.tablespace }}</td><td class="text-right">{{ partition.estimatedRows ?? '' }}</td><td class="text-right">{{ formatBytes(partition.dataBytes) }}</td><td class="grid-trailing-gutter" aria-hidden="true"></td></tr>
              <tr v-for="sub in windowState.subpartitions[partition.id] || []" v-show="windowState.expanded.has(partition.id)" :key="sub.id" class="subpartition">
                <td class="text-center"></td><td class="text-left" :title="cellTitle(sub.name)">↳ {{ sub.name }}</td><td class="text-right">{{ sub.position }}</td><td class="text-left" :title="cellTitle(sub.method)">{{ sub.method }}</td><td class="text-left" :title="cellTitle(sub.expression)">{{ sub.expression }}</td><td class="text-left" :title="cellTitle(sub.boundary)">{{ sub.boundary }}</td>
                <td class="text-left" :title="cellTitle(sub.tablespace)">{{ sub.tablespace }}</td><td class="text-right">{{ sub.estimatedRows ?? '' }}</td><td class="text-right">{{ formatBytes(sub.dataBytes) }}</td><td class="grid-trailing-gutter" aria-hidden="true"></td></tr>
            </template></tbody></table></div>
          <button v-if="windowState.partitionNext" class="load-more" @click="loadPartitions(windowState, true)">继续加载分区</button>
        </template>
        <template v-else>
          <div class="ddl-toolbar"><span :class="{ complete: windowState.ddlComplete, incomplete: !windowState.ddlComplete }">
            {{ windowState.ddlComplete ? 'DDL 已完整加载' : windowState.ddlLoading ? '正在流式加载…' : 'DDL 不完整' }} · {{ windowState.ddl.length.toLocaleString() }} 字符</span>
            <button v-if="windowState.ddlLoading" @click="cancelDdl(windowState)">取消</button>
            <button :disabled="!windowState.ddlComplete" @click="copyDdl(windowState)">复制全部</button></div>
          <ObjectDdlViewer :value="windowState.ddl" />
        </template>
        <div v-if="windowState.warning" class="section-warning">{{ windowState.warning }}</div>
      </main>
      <i v-for="edge in resizeEdges" :key="edge" class="resize-handle" :class="`resize-${edge}`"
         @pointerdown.stop.prevent="startResize($event, windowState, edge)" />
    </section>
  </Teleport>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive } from "vue";
import { Close, LocationFilled, View } from "@element-plus/icons-vue";
import { rpc } from "../bridge/rpc";
import ObjectDdlViewer from "./ObjectDdlViewer.vue";
import type { ObjectColumnInfo, ObjectDescriptor, ObjectIndexColumnInfo, ObjectIndexInfo,
  ObjectPartitionInfo, ObjectSectionPage, SqlObjectReference } from "../types";

type Tab = "columns" | "indexes" | "partitions" | "ddl";
type GridTab = Exclude<Tab, "ddl">;
type GridAlignment = "text-left" | "text-center" | "text-right";
interface GridColumnDefinition { key: string; label: string; align: GridAlignment; defaultWidth: number; }
const gridColumnDefinitions: Record<GridTab, GridColumnDefinition[]> = {
  columns: [
    { key: "name", label: "字段名", align: "text-left", defaultWidth: 150 },
    { key: "type", label: "类型", align: "text-left", defaultWidth: 145 },
    { key: "length", label: "长度", align: "text-right", defaultWidth: 80 },
    { key: "precision", label: "精度", align: "text-right", defaultWidth: 80 },
    { key: "nullable", label: "可空", align: "text-center", defaultWidth: 70 },
    { key: "default", label: "默认值", align: "text-left", defaultWidth: 180 },
    { key: "primary", label: "主键", align: "text-center", defaultWidth: 70 },
    { key: "generated", label: "生成属性", align: "text-center", defaultWidth: 110 },
    { key: "remarks", label: "备注", align: "text-left", defaultWidth: 240 },
  ],
  indexes: [
    { key: "name", label: "索引名", align: "text-left", defaultWidth: 130 },
    { key: "primary", label: "主键", align: "text-center", defaultWidth: 70 },
    { key: "unique", label: "唯一", align: "text-center", defaultWidth: 70 },
    { key: "type", label: "类型", align: "text-left", defaultWidth: 110 },
    { key: "status", label: "状态", align: "text-left", defaultWidth: 100 },
    { key: "visible", label: "可见", align: "text-center", defaultWidth: 70 },
    { key: "partitioned", label: "分区", align: "text-center", defaultWidth: 70 },
    { key: "tablespace", label: "表空间", align: "text-left", defaultWidth: 150 },
    { key: "columns", label: "字段/表达式", align: "text-left", defaultWidth: 240 },
  ],
  partitions: [
    { key: "expand", label: "", align: "text-center", defaultWidth: 42 },
    { key: "name", label: "分区名", align: "text-left", defaultWidth: 150 },
    { key: "position", label: "位置", align: "text-right", defaultWidth: 70 },
    { key: "method", label: "方式", align: "text-left", defaultWidth: 110 },
    { key: "expression", label: "表达式", align: "text-left", defaultWidth: 180 },
    { key: "boundary", label: "边界", align: "text-left", defaultWidth: 220 },
    { key: "tablespace", label: "表空间", align: "text-left", defaultWidth: 150 },
    { key: "estimatedRows", label: "估算行数", align: "text-right", defaultWidth: 100 },
    { key: "dataBytes", label: "数据大小", align: "text-right", defaultWidth: 110 },
  ],
};
const GRID_MIN_WIDTH = 56;
const GRID_MAX_WIDTH = 640;
const GRID_TRAILING_GUTTER_WIDTH = 48;
export interface ObjectInspectorOpenRequest {
  reference: SqlObjectReference; editorId: string; modelKey: string; connectionDisplay: string;
  x: number; y: number;
}
interface InspectorState {
  id: string; key: string; reference: SqlObjectReference; editorId: string; sourceModelKey: string;
  connectionDisplay: string; x: number; y: number; width: number; height: number; z: number;
  pinned: boolean; hovered: boolean; focused: boolean; opacityOpen: boolean; activeTab: Tab; object?: ObjectDescriptor;
  columns: ObjectColumnInfo[]; indexes: ObjectIndexInfo[]; partitions: ObjectPartitionInfo[];
  subpartitions: Record<string, ObjectPartitionInfo[]>; expanded: Set<string>; partitionNext: string;
  columnWidths: Record<GridTab, number[]>;
  loaded: Record<Tab, boolean>; loading: Record<Tab, boolean>; error: string; warning: string;
  ddl: string; ddlComplete: boolean; ddlLoading: boolean; ddlAbort?: AbortController; sourceReleased: boolean;
}
interface ColumnResizeState {
  state: InspectorState;
  tab: GridTab;
  index: number;
  startX: number;
  startWidth: number;
}
const props = defineProps<{ opacity: number }>();
const emit = defineEmits<{ "update:opacity": [value: number]; "save-opacity": [value: number] }>();
const windows = reactive<InspectorState[]>([]); let zIndex = 3000;
let columnResizeState: ColumnResizeState | undefined;
const tabs = [{ key: "columns", label: "列" }, { key: "indexes", label: "索引" },
  { key: "partitions", label: "分区" }, { key: "ddl", label: "查看 SQL" }] as const;
const resizeEdges = ["n", "ne", "e", "se", "s", "sw", "w", "nw"] as const;

function createColumnWidths(): Record<GridTab, number[]> {
  return {
    columns: gridColumnDefinitions.columns.map(column => column.defaultWidth),
    indexes: gridColumnDefinitions.indexes.map(column => column.defaultWidth),
    partitions: gridColumnDefinitions.partitions.map(column => column.defaultWidth),
  };
}

function open(request: ObjectInspectorOpenRequest): void {
  const key = `${request.editorId}\0${request.reference.catalog || ''}\0${request.reference.schema || ''}\0${request.reference.objectName}`.toLocaleLowerCase();
  const existing = windows.find(item => item.key === key);
  if (existing) { bringToFront(existing); existing.x = clamp(request.x, 0, innerWidth - existing.width); existing.y = clamp(request.y, 0, innerHeight - existing.height); return; }
  const preview = windows.find(item => !item.pinned); if (preview) close(preview.id);
  const state = reactive<InspectorState>({ id: crypto.randomUUID(), key, reference: request.reference,
    editorId: request.editorId, sourceModelKey: request.modelKey, connectionDisplay: request.connectionDisplay,
    x: clamp(request.x + 12, 0, Math.max(0, innerWidth - 720)), y: clamp(request.y + 12, 0, Math.max(0, innerHeight - 480)),
    width: Math.min(720, innerWidth), height: Math.min(480, innerHeight), z: ++zIndex, pinned: false,
    hovered: false, focused: false, opacityOpen: false, activeTab: "columns", columns: [], indexes: [], partitions: [],
    subpartitions: {}, expanded: new Set(), partitionNext: "", columnWidths: createColumnWidths(),
    loaded: { columns: false, indexes: false, partitions: false, ddl: false },
    loading: { columns: false, indexes: false, partitions: false, ddl: false }, error: "", warning: "", ddl: "",
    ddlComplete: false, ddlLoading: false, sourceReleased: false });
  windows.push(state); void loadSection(state, "columns");
}
function close(id: string): void { const index = windows.findIndex(item => item.id === id); if (index < 0) return;
  windows[index].ddlAbort?.abort(); windows.splice(index, 1); }
function closeTransient(): void { const preview = windows.find(item => !item.pinned); if (preview) close(preview.id); }
function sourceReleased(modelKey: string): void { windows.filter(item => item.sourceModelKey === modelKey).forEach(item => {
  item.sourceReleased = true; if (!item.pinned) close(item.id);
}); }
function bringToFront(state: InspectorState): void { state.z = ++zIndex; }
function togglePin(state: InspectorState): void { state.pinned = !state.pinned; }
function activate(state: InspectorState, tab: Tab): void { state.activeTab = tab; state.error = ""; state.warning = "";
  if (!state.loaded[tab] && !state.loading[tab]) void loadSection(state, tab); }
async function loadSection(state: InspectorState, tab: Tab): Promise<void> {
  if (state.sourceReleased && !state.loaded[tab]) { state.error = "来源连接已释放，无法继续加载"; return; }
  if (tab === "ddl") { await loadDdl(state); return; }
  state.loading[tab] = true; state.error = "";
  try {
    const result = await rpc.request<ObjectSectionPage<ObjectColumnInfo | ObjectIndexInfo | ObjectPartitionInfo>>("metadata.objectSection", {
      editorId: state.editorId, catalog: state.reference.catalog || "", schema: state.reference.schema || "",
      name: state.reference.objectName, section: tab, pageSize: 200,
    });
    state.object = result.object; state.warning = result.warning || "";
    if (tab === "columns") state.columns = result.items as ObjectColumnInfo[];
    else if (tab === "indexes") state.indexes = result.items as ObjectIndexInfo[];
    else { state.partitions = result.items as ObjectPartitionInfo[]; state.partitionNext = result.nextPageToken || ""; }
    state.loaded[tab] = true;
  } catch (error) { state.error = error instanceof Error ? error.message : String(error); }
  finally { state.loading[tab] = false; }
}
async function loadPartitions(state: InspectorState, append: boolean): Promise<void> {
  state.loading.partitions = true;
  try { const result = await rpc.request<ObjectSectionPage<ObjectPartitionInfo>>("metadata.objectSection", {
    editorId: state.editorId, catalog: state.object?.catalog || state.reference.catalog || "", schema: state.object?.schema || state.reference.schema || "",
    name: state.object?.name || state.reference.objectName, section: "partitions", pageToken: append ? state.partitionNext : "", pageSize: 200 });
    state.partitions = append ? [...state.partitions, ...result.items] : result.items; state.partitionNext = result.nextPageToken || "";
  } catch (error) { state.error = error instanceof Error ? error.message : String(error); }
  finally { state.loading.partitions = false; }
}
async function togglePartition(state: InspectorState, partition: ObjectPartitionInfo): Promise<void> {
  if (state.expanded.has(partition.id)) { state.expanded.delete(partition.id); return; }
  state.expanded.add(partition.id); if (state.subpartitions[partition.id]) return;
  try { const result = await rpc.request<ObjectSectionPage<ObjectPartitionInfo>>("metadata.objectSection", {
    editorId: state.editorId, catalog: state.object?.catalog || "", schema: state.object?.schema || "", name: state.object?.name || state.reference.objectName,
    section: "subpartitions", parentPartition: partition.name, pageSize: 500 }); state.subpartitions[partition.id] = result.items;
  } catch (error) { state.warning = error instanceof Error ? error.message : String(error); }
}
async function loadDdl(state: InspectorState): Promise<void> {
  state.loading.ddl = state.ddlLoading = true; state.ddl = ""; state.ddlComplete = false; state.error = "";
  const controller = new AbortController(); state.ddlAbort = controller;
  try { await rpc.streamObjectDdl({ editorId: state.editorId, catalog: state.object?.catalog || state.reference.catalog || "",
    schema: state.object?.schema || state.reference.schema || "", name: state.object?.name || state.reference.objectName }, event => {
      if (event.type === "begin") state.object = event.object;
      else if (event.type === "chunk") state.ddl += event.text;
      else if (event.type === "complete") { state.ddlComplete = true; state.loaded.ddl = true; }
      else if (event.type === "error") state.error = event.message;
    }, controller.signal); }
  catch (error) { if (!controller.signal.aborted) state.error = error instanceof Error ? error.message : String(error); }
  finally { state.loading.ddl = state.ddlLoading = false; state.ddlAbort = undefined; }
}
function cancelDdl(state: InspectorState): void { state.ddlAbort?.abort(); state.ddlLoading = false; state.ddlComplete = false; }
async function copyDdl(state: InspectorState): Promise<void> { if (state.ddlComplete) await navigator.clipboard.writeText(state.ddl); }
function gridColumns(tab: GridTab): GridColumnDefinition[] { return gridColumnDefinitions[tab]; }
function columnWidth(state: InspectorState, tab: GridTab, index: number): number {
  return state.columnWidths[tab][index] ?? gridColumnDefinitions[tab][index].defaultWidth;
}
function gridTableWidth(state: InspectorState, tab: GridTab): string {
  const total = state.columnWidths[tab].reduce((sum, width) => sum + width, 0);
  // The table's existing min-width: 100% supplies the viewport side of
  // max(100%, total + trailing gutter) while the explicit width reserves the gutter.
  return `${total + GRID_TRAILING_GUTTER_WIDTH}px`;
}
function cellTitle(value: unknown): string | undefined { return value == null ? undefined : String(value); }
function startColumnResize(event: PointerEvent, state: InspectorState, tab: GridTab, index: number): void {
  finishColumnResize();
  columnResizeState = { state, tab, index, startX: event.clientX, startWidth: columnWidth(state, tab, index) };
  window.addEventListener("pointermove", resizeColumn, true);
  window.addEventListener("pointerup", finishColumnResize, { once: true, capture: true });
  window.addEventListener("pointercancel", finishColumnResize, { once: true, capture: true });
}
function resizeColumn(event: PointerEvent): void {
  const active = columnResizeState;
  if (!active) return;
  active.state.columnWidths[active.tab][active.index] = clamp(active.startWidth + event.clientX - active.startX,
    GRID_MIN_WIDTH, GRID_MAX_WIDTH);
}
function finishColumnResize(): void {
  window.removeEventListener("pointermove", resizeColumn, true);
  window.removeEventListener("pointerup", finishColumnResize, true);
  window.removeEventListener("pointercancel", finishColumnResize, true);
  columnResizeState = undefined;
}
function gridCellValue(state: InspectorState, tab: GridTab, row: unknown, index: number): string {
  if (tab === "columns") {
    const column = row as ObjectColumnInfo;
    switch (index) {
      case 0: return column.name;
      case 1: return column.typeName;
      case 2: return cellTitle(column.length) || "";
      case 3: return precision(column);
      case 4: return column.nullable ? "是" : "否";
      case 5: return cellTitle(column.defaultValue) || "";
      case 6: return column.primaryKey ? "是" : "";
      case 7: return column.autoIncrement ? "自增" : column.generated ? "生成" : "";
      default: return column.remarks;
    }
  }
  if (tab === "indexes") {
    const indexInfo = row as ObjectIndexInfo;
    switch (index) {
      case 0: return indexInfo.name;
      case 1: return indexInfo.primary ? "是" : "";
      case 2: return indexInfo.unique ? "是" : "否";
      case 3: return indexInfo.type;
      case 4: return indexInfo.status;
      case 5: return indexInfo.visible ? "是" : "否";
      case 6: return indexInfo.partitioned ? "是" : "";
      case 7: return indexInfo.tablespace;
      default: return indexInfo.columns.map(indexColumnLabel).join(", ");
    }
  }
  const partition = row as ObjectPartitionInfo;
  switch (index) {
    case 0: return "";
    case 1: return partition.name;
    case 2: return cellTitle(partition.position) || "";
    case 3: return partition.method;
    case 4: return partition.expression;
    case 5: return partition.boundary;
    case 6: return partition.tablespace;
    case 7: return cellTitle(partition.estimatedRows) || "";
    default: return formatBytes(partition.dataBytes);
  }
}
function gridColumnValues(state: InspectorState, tab: GridTab, index: number): string[] {
  let rows: unknown[];
  if (tab === "columns") rows = state.columns;
  else if (tab === "indexes") rows = state.indexes;
  else rows = state.partitions.concat(Object.values(state.subpartitions).flat());
  return rows.map(row => gridCellValue(state, tab, row, index));
}
let gridMeasureContext: CanvasRenderingContext2D | null | undefined;
function measureGridText(value: string): number {
  if (typeof document === "undefined") return Array.from(value).length * 8;
  if (gridMeasureContext === undefined) {
    try { gridMeasureContext = document.createElement("canvas").getContext("2d"); }
    catch (_) { gridMeasureContext = null; }
  }
  if (!gridMeasureContext) return Array.from(value).length * 8;
  gridMeasureContext.font = getComputedStyle(document.body).font || "12px sans-serif";
  return gridMeasureContext.measureText(value).width;
}
function fitColumnWidth(event: Event, state: InspectorState, tab: GridTab, index: number): void {
  event.preventDefault(); event.stopPropagation();
  const header = gridColumnDefinitions[tab][index];
  const widest = Math.max(measureGridText(header.label), ...gridColumnValues(state, tab, index).map(measureGridText));
  state.columnWidths[tab][index] = clamp(Math.ceil(widest + 28), GRID_MIN_WIDTH, GRID_MAX_WIDTH);
}
function keyboardResizeColumn(event: KeyboardEvent, state: InspectorState, tab: GridTab, index: number): void {
  if (event.key === "Enter") { fitColumnWidth(event, state, tab, index); return; }
  if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
  event.preventDefault(); event.stopPropagation();
  state.columnWidths[tab][index] = clamp(columnWidth(state, tab, index) + (event.key === "ArrowRight" ? 10 : -10),
    GRID_MIN_WIDTH, GRID_MAX_WIDTH);
}
function changeOpacity(value: number | number[]): void {
  const next = Array.isArray(value) ? value[0] : value;
  if (Number.isFinite(next)) emit("update:opacity", Math.max(1, Math.min(100, Number(next))));
}
function saveOpacity(): void { emit("save-opacity", props.opacity); }
function precision(column: ObjectColumnInfo): string { return column.precision ? `${column.precision}${column.scale ? `,${column.scale}` : ''}` : ""; }
function indexColumnLabel(column: ObjectIndexColumnInfo): string { return `${column.expression || column.name}${column.direction ? ` ${column.direction}` : ''}`; }
function formatBytes(value: number | null): string { if (value == null) return ""; if (value < 1024) return `${value} B`;
  return value < 1048576 ? `${(value / 1024).toFixed(1)} KiB` : `${(value / 1048576).toFixed(1)} MiB`; }
function windowStyle(state: InspectorState): Record<string, string | number> { const activeOpacity = state.hovered || state.focused ? Math.max(props.opacity, 60) : props.opacity;
  return { left: `${state.x}px`, top: `${state.y}px`, width: `${state.width}px`, height: `${state.height}px`, zIndex: state.z, opacity: activeOpacity / 100 }; }
function startMove(event: PointerEvent, state: InspectorState): void { if ((event.target as HTMLElement).closest("button,input,label")) return;
  startPointer(event, (dx, dy) => { state.x = clamp(state.x + dx, 0, innerWidth - 80); state.y = clamp(state.y + dy, 0, innerHeight - 40); }); }
function startResize(event: PointerEvent, state: InspectorState, edge: string): void { startPointer(event, (dx, dy) => {
  if (edge.includes("e")) state.width = Math.max(420, Math.min(innerWidth - state.x, state.width + dx));
  if (edge.includes("s")) state.height = Math.max(260, Math.min(innerHeight - state.y, state.height + dy));
  if (edge.includes("w")) { const width = Math.max(420, state.width - dx); state.x += state.width - width; state.width = width; }
  if (edge.includes("n")) { const height = Math.max(260, state.height - dy); state.y += state.height - height; state.height = height; }
}); }
function startPointer(event: PointerEvent, update: (dx: number, dy: number) => void): void { let x = event.clientX, y = event.clientY;
  const move = (next: PointerEvent) => { const dx = next.clientX - x, dy = next.clientY - y; x = next.clientX; y = next.clientY; update(dx, dy); };
  const up = () => { window.removeEventListener("pointermove", move); window.removeEventListener("pointerup", up); };
  window.addEventListener("pointermove", move); window.addEventListener("pointerup", up, { once: true }); }
function clamp(value: number, min: number, max: number): number { return Math.max(min, Math.min(Math.max(min, max), value)); }
function onKey(event: KeyboardEvent): void {
  if (event.key !== "Escape") return;
  const opacityWindow = windows.find(item => item.opacityOpen);
  if (opacityWindow) { opacityWindow.opacityOpen = false; event.stopPropagation(); return; }
  closeTransient();
}
function onDocumentPointer(event: PointerEvent): void {
  const target = event.target as HTMLElement;
  if (!target.closest(".object-inspector") && !target.closest(".object-inspector-opacity-popover")) closeTransient();
}
onMounted(() => {
  window.addEventListener("keydown", onKey);
  document.addEventListener("pointerdown", onDocumentPointer, true);
});
onBeforeUnmount(() => {
  finishColumnResize();
  window.removeEventListener("keydown", onKey);
  document.removeEventListener("pointerdown", onDocumentPointer, true);
  windows.forEach(item => item.ddlAbort?.abort());
});
defineExpose({ open, closeTransient, sourceReleased });
</script>

<style scoped>
.object-inspector {
  position: fixed;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-sizing: border-box;
  color: var(--db-text);
  background: var(--db-panel);
  border: 1px solid var(--db-border);
  border-radius: 12px;
  box-shadow: 0 18px 54px rgba(0, 0, 0, .28);
  transition: opacity .12s ease;
  font-family: inherit;
  font-size: 12px;
  line-height: 1.35;
}
.object-inspector button,
.object-inspector input { font: inherit; }
.inspector-titlebar {
  box-sizing: border-box;
  display: flex;
  min-height: 38px;
  height: 38px;
  flex: none;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 8px 0 10px;
  background: var(--db-panel-soft);
  border-bottom: 1px solid var(--db-border-soft);
  cursor: move;
  user-select: none;
}
.object-title {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 7px;
  overflow: hidden;
  font-size: 14px;
  font-weight: 650;
  letter-spacing: -.01em;
}
.object-kind {
  display: grid;
  width: 21px;
  height: 21px;
  flex: none;
  place-items: center;
  border-radius: 6px;
  background: var(--db-accent);
  color: #fff;
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0;
}
.object-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.object-title small {
  min-width: 0;
  max-width: 130px;
  overflow: hidden;
  color: var(--db-text-secondary);
  font-size: 11px;
  font-weight: 400;
  letter-spacing: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.title-actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 2px;
}
.icon-button {
  display: inline-grid;
  width: 26px;
  height: 26px;
  flex: none;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--db-text-secondary);
  cursor: pointer;
  line-height: 1;
  transition: color 120ms var(--db-ease), background-color 120ms var(--db-ease);
}
.icon-button:hover,
.icon-button:focus-visible,
.icon-button.active {
  background: var(--db-control-hover);
  color: var(--db-accent);
}
.inspector-tabs {
  display: flex;
  min-height: 32px;
  height: 32px;
  flex: none;
  align-items: stretch;
  overflow-x: auto;
  padding: 0 6px;
  border-bottom: 1px solid var(--db-border-soft);
  scrollbar-width: none;
}
.inspector-tabs::-webkit-scrollbar { display: none; }
.inspector-tabs button {
  position: relative;
  box-sizing: border-box;
  min-width: max-content;
  height: 32px;
  padding: 0 12px;
  border: 0;
  background: transparent;
  color: var(--db-text-secondary);
  cursor: pointer;
  font-size: 12px;
  font-weight: 500;
  line-height: 32px;
  transition: color 120ms var(--db-ease), background-color 120ms var(--db-ease);
}
.inspector-tabs button:hover:not(:disabled) { color: var(--db-text); background: var(--db-control-bg); }
.inspector-tabs button.active { color: var(--db-accent); font-weight: 650; }
.inspector-tabs button.active::after {
  position: absolute;
  right: 10px;
  bottom: 0;
  left: 10px;
  height: 2px;
  border-radius: 2px 2px 0 0;
  background: var(--db-accent);
  content: "";
}
.inspector-tabs button:disabled { color: var(--db-muted); cursor: not-allowed; opacity: .56; }
.inspector-content {
  position: relative;
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  background: var(--db-panel);
}
.grid-scroll { min-height: 0; flex: 1; overflow: auto; }
table {
  width: max-content;
  min-width: 100%;
  table-layout: fixed;
  border-collapse: separate;
  border-spacing: 0;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.structure-grid th,
.structure-grid td { max-width: none; }
.grid-trailing-gutter {
  min-width: 48px;
  padding: 0;
  border-right: 0;
  cursor: default;
  pointer-events: none;
  user-select: none;
  -webkit-user-select: none;
}
th,
td {
  box-sizing: border-box;
  max-width: 360px;
  height: 32px;
  padding: 0 10px;
  overflow: hidden;
  border-right: 1px solid var(--db-border-soft);
  border-bottom: 1px solid var(--db-border-soft);
  text-overflow: ellipsis;
  vertical-align: middle;
  white-space: nowrap;
}
th {
  position: sticky;
  z-index: 1;
  top: 0;
  height: 30px;
  background: var(--db-panel-soft);
  color: var(--db-text-secondary);
  font-size: 11px;
  font-weight: 600;
}
.structure-grid th { padding-right: 17px; user-select: none; -webkit-user-select: none; }
.structure-grid td { cursor: text; user-select: text; -webkit-user-select: text; }
.grid-header-label { display: block; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.grid-column-resize-handle {
  position: absolute;
  z-index: 2;
  top: 2px;
  right: -3px;
  bottom: 2px;
  width: 7px;
  cursor: col-resize;
  outline: none;
}
.grid-column-resize-handle::after {
  position: absolute;
  top: 4px;
  right: 3px;
  bottom: 4px;
  width: 1px;
  background: var(--db-border-soft);
  content: "";
}
.grid-column-resize-handle:hover::after,
.grid-column-resize-handle:focus-visible::after { width: 2px; background: var(--db-accent); }
tbody tr:hover td { background: var(--db-accent-soft); }
.text-left { text-align: left; }
.text-center { text-align: center; }
.text-right { text-align: right; }
.subpartition td { color: var(--db-text-secondary); background: color-mix(in srgb, var(--db-panel) 82%, var(--db-control-hover)); }
.expand {
  display: inline-grid;
  width: 20px;
  height: 20px;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 5px;
  background: var(--db-control-hover);
  color: var(--db-text-secondary);
  cursor: pointer;
  font-size: 13px;
  line-height: 1;
}
.expand:hover { color: var(--db-accent); background: var(--db-accent-soft); }
.section-message { display: grid; height: 100%; place-items: center; color: var(--db-text-secondary); font-size: 11px; }
.section-message.error { color: var(--el-color-danger); }
.section-warning { padding: 6px 10px; background: var(--el-color-warning-light-9); color: var(--el-color-warning-dark-2); font-size: 11px; }
.load-more { margin: 8px auto; padding: 5px 11px; border: 0; border-radius: 7px; background: var(--db-control-hover); color: var(--db-text); cursor: pointer; font-size: 11px; }
.load-more:hover { background: var(--db-accent-soft); color: var(--db-accent); }
.ddl-toolbar {
  display: flex;
  height: 34px;
  flex: none;
  align-items: center;
  gap: 7px;
  padding: 0 10px;
  border-bottom: 1px solid var(--db-border-soft);
  font-size: 11px;
}
.ddl-toolbar span { min-width: 0; margin-right: auto; overflow: hidden; color: var(--db-text-secondary); text-overflow: ellipsis; white-space: nowrap; }
.ddl-toolbar .complete { color: var(--el-color-success); }
.ddl-toolbar .incomplete { color: var(--el-color-warning); }
.ddl-toolbar button { min-height: 25px; padding: 0 9px; border: 0; border-radius: 6px; background: var(--db-control-hover); color: var(--db-text); cursor: pointer; font-size: 11px; }
.ddl-toolbar button:hover { background: var(--db-accent-soft); color: var(--db-accent); }
.ddl-toolbar button:disabled { cursor: not-allowed; opacity: .4; }
.ddl-viewer { box-sizing: border-box; width: 100%; min-height: 0; flex: 1; resize: none; overflow: auto; padding: 12px; border: 0; outline: 0; background: var(--db-editor-bg); color: var(--db-text); font: 12px/1.55 "SF Mono", Menlo, Consolas, monospace; white-space: pre; }
.resize-handle { position: absolute; z-index: 5; }
.resize-n,
.resize-s { right: 8px; left: 8px; height: 6px; cursor: ns-resize; }
.resize-n { top: -2px; }
.resize-s { bottom: -2px; }
.resize-e,
.resize-w { top: 8px; bottom: 8px; width: 6px; cursor: ew-resize; }
.resize-e { right: -2px; }
.resize-w { left: -2px; }
.resize-ne,
.resize-nw,
.resize-se,
.resize-sw { width: 10px; height: 10px; }
.resize-ne { top: -2px; right: -2px; cursor: nesw-resize; }
.resize-nw { top: -2px; left: -2px; cursor: nwse-resize; }
.resize-se { right: -2px; bottom: -2px; cursor: nwse-resize; }
.resize-sw { bottom: -2px; left: -2px; cursor: nesw-resize; }
.opacity-popover__header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 4px; color: var(--db-text-secondary); font-size: 11px; }
.opacity-popover__header strong { color: var(--db-text); font-size: 12px; font-weight: 650; font-variant-numeric: tabular-nums; }
.opacity-popover :deep(.el-slider) { margin: 0 5px; }
:global(.object-inspector-opacity-popover.el-popper) { z-index: 4000 !important; padding: 10px 12px !important; border-color: var(--db-border) !important; border-radius: 9px !important; background: var(--db-panel) !important; color: var(--db-text) !important; box-shadow: var(--db-shadow-lg) !important; }
:global(.object-inspector-opacity-popover .el-popper__arrow::before) { border-color: var(--db-border) !important; background: var(--db-panel) !important; }
</style>
