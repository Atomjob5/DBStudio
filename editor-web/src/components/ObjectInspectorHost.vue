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
          <div class="grid-scroll"><table><thead><tr><th class="text-left">字段名</th><th class="text-left">类型</th><th class="text-right">长度</th><th class="text-right">精度</th><th class="text-center">可空</th><th class="text-left">默认值</th><th class="text-center">主键</th><th class="text-center">生成属性</th><th class="text-left">备注</th></tr></thead>
            <tbody><tr v-for="column in windowState.columns" :key="column.ordinal"><td class="text-left">{{ column.name }}</td><td class="text-left">{{ column.typeName }}</td>
              <td class="text-right">{{ column.length || '' }}</td><td class="text-right">{{ precision(column) }}</td><td class="text-center">{{ column.nullable ? '是' : '否' }}</td>
              <td class="text-left">{{ column.defaultValue ?? '' }}</td><td class="text-center">{{ column.primaryKey ? '是' : '' }}</td>
              <td class="text-center">{{ column.autoIncrement ? '自增' : column.generated ? '生成' : '' }}</td><td class="text-left">{{ column.remarks }}</td></tr></tbody></table></div>
        </template>
        <template v-else-if="windowState.activeTab === 'indexes'">
          <div class="grid-scroll"><table><thead><tr><th class="text-left">索引名</th><th class="text-center">主键</th><th class="text-center">唯一</th><th class="text-left">类型</th><th class="text-left">状态</th><th class="text-center">可见</th><th class="text-center">分区</th><th class="text-left">表空间</th><th class="text-left">字段/表达式</th></tr></thead>
            <tbody><tr v-for="index in windowState.indexes" :key="index.name"><td class="text-left">{{ index.name }}</td><td class="text-center">{{ index.primary ? '是' : '' }}</td>
              <td class="text-center">{{ index.unique ? '是' : '否' }}</td><td class="text-left">{{ index.type }}</td><td class="text-left">{{ index.status }}</td><td class="text-center">{{ index.visible ? '是' : '否' }}</td>
              <td class="text-center">{{ index.partitioned ? '是' : '' }}</td><td class="text-left">{{ index.tablespace }}</td><td class="text-left">{{ index.columns.map(indexColumnLabel).join(', ') }}</td></tr></tbody></table></div>
        </template>
        <template v-else-if="windowState.activeTab === 'partitions'">
          <div class="grid-scroll"><table><thead><tr><th class="text-center"></th><th class="text-left">分区名</th><th class="text-right">位置</th><th class="text-left">方式</th><th class="text-left">表达式</th><th class="text-left">边界</th><th class="text-left">表空间</th><th class="text-right">估算行数</th><th class="text-right">数据大小</th></tr></thead>
            <tbody><template v-for="partition in windowState.partitions" :key="partition.id">
              <tr><td class="text-center"><button v-if="partition.hasSubpartitions" class="expand" :aria-label="windowState.expanded.has(partition.id) ? '收起子分区' : '展开子分区'" @click="togglePartition(windowState, partition)">{{ windowState.expanded.has(partition.id) ? '−' : '+' }}</button></td>
                <td class="text-left">{{ partition.name }}</td><td class="text-right">{{ partition.position }}</td><td class="text-left">{{ partition.method }}</td><td class="text-left">{{ partition.expression }}</td><td class="text-left">{{ partition.boundary }}</td>
                <td class="text-left">{{ partition.tablespace }}</td><td class="text-right">{{ partition.estimatedRows ?? '' }}</td><td class="text-right">{{ formatBytes(partition.dataBytes) }}</td></tr>
              <tr v-for="sub in windowState.subpartitions[partition.id] || []" v-show="windowState.expanded.has(partition.id)" :key="sub.id" class="subpartition">
                <td class="text-center"></td><td class="text-left">↳ {{ sub.name }}</td><td class="text-right">{{ sub.position }}</td><td class="text-left">{{ sub.method }}</td><td class="text-left">{{ sub.expression }}</td><td class="text-left">{{ sub.boundary }}</td>
                <td class="text-left">{{ sub.tablespace }}</td><td class="text-right">{{ sub.estimatedRows ?? '' }}</td><td class="text-right">{{ formatBytes(sub.dataBytes) }}</td></tr>
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
  loaded: Record<Tab, boolean>; loading: Record<Tab, boolean>; error: string; warning: string;
  ddl: string; ddlComplete: boolean; ddlLoading: boolean; ddlAbort?: AbortController; sourceReleased: boolean;
}
const props = defineProps<{ opacity: number }>();
const emit = defineEmits<{ "update:opacity": [value: number]; "save-opacity": [value: number] }>();
const windows = reactive<InspectorState[]>([]); let zIndex = 3000;
const tabs = [{ key: "columns", label: "列" }, { key: "indexes", label: "索引" },
  { key: "partitions", label: "分区" }, { key: "ddl", label: "查看 SQL" }] as const;
const resizeEdges = ["n", "ne", "e", "se", "s", "sw", "w", "nw"] as const;

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
    subpartitions: {}, expanded: new Set(), partitionNext: "", loaded: { columns: false, indexes: false, partitions: false, ddl: false },
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
  border-collapse: separate;
  border-spacing: 0;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
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
