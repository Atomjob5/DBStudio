<template>
  <Teleport to="body">
    <section v-for="windowState in windows" :key="windowState.id" class="object-inspector"
             :class="{ pinned: windowState.pinned }" :style="windowStyle(windowState)"
             tabindex="-1" @pointerdown="bringToFront(windowState)" @mouseenter="windowState.hovered = true"
             @mouseleave="windowState.hovered = false" @focusin="windowState.focused = true"
             @focusout="windowState.focused = false">
      <header class="inspector-titlebar" @pointerdown="startMove($event, windowState)">
        <div class="object-title"><span class="object-kind">{{ windowState.object?.type === 'VIEW' ? 'V' : 'T' }}</span>
          <span>{{ windowState.object?.qualifiedName || windowState.reference.objectName }}</span>
          <small>{{ windowState.connectionDisplay }}</small></div>
        <div class="title-actions" @pointerdown.stop>
          <label title="窗口透明度"><span>◐</span><input type="range" min="1" max="100" :value="opacity"
            @input="changeOpacity($event)" @change="saveOpacity" /></label>
          <button :class="{ active: windowState.pinned }" :title="windowState.pinned ? '取消钉住' : '钉住窗口'"
                  @click="togglePin(windowState)">⌖</button>
          <button title="关闭" @click="close(windowState.id)">×</button>
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
          <div class="grid-scroll"><table><thead><tr><th>字段名</th><th>类型</th><th>长度</th><th>精度</th><th>可空</th><th>默认值</th><th>主键</th><th>生成属性</th><th>备注</th></tr></thead>
            <tbody><tr v-for="column in windowState.columns" :key="column.ordinal"><td>{{ column.name }}</td><td>{{ column.typeName }}</td>
              <td>{{ column.length || '' }}</td><td>{{ precision(column) }}</td><td>{{ column.nullable ? '是' : '否' }}</td>
              <td>{{ column.defaultValue ?? '' }}</td><td>{{ column.primaryKey ? '是' : '' }}</td>
              <td>{{ column.autoIncrement ? '自增' : column.generated ? '生成' : '' }}</td><td>{{ column.remarks }}</td></tr></tbody></table></div>
        </template>
        <template v-else-if="windowState.activeTab === 'indexes'">
          <div class="grid-scroll"><table><thead><tr><th>索引名</th><th>主键</th><th>唯一</th><th>类型</th><th>状态</th><th>可见</th><th>分区</th><th>表空间</th><th>字段/表达式</th></tr></thead>
            <tbody><tr v-for="index in windowState.indexes" :key="index.name"><td>{{ index.name }}</td><td>{{ index.primary ? '是' : '' }}</td>
              <td>{{ index.unique ? '是' : '否' }}</td><td>{{ index.type }}</td><td>{{ index.status }}</td><td>{{ index.visible ? '是' : '否' }}</td>
              <td>{{ index.partitioned ? '是' : '' }}</td><td>{{ index.tablespace }}</td><td>{{ index.columns.map(indexColumnLabel).join(', ') }}</td></tr></tbody></table></div>
        </template>
        <template v-else-if="windowState.activeTab === 'partitions'">
          <div class="grid-scroll"><table><thead><tr><th></th><th>分区名</th><th>位置</th><th>方式</th><th>表达式</th><th>边界</th><th>表空间</th><th>估算行数</th><th>数据大小</th></tr></thead>
            <tbody><template v-for="partition in windowState.partitions" :key="partition.id">
              <tr><td><button v-if="partition.hasSubpartitions" class="expand" @click="togglePartition(windowState, partition)">{{ windowState.expanded.has(partition.id) ? '−' : '+' }}</button></td>
                <td>{{ partition.name }}</td><td>{{ partition.position }}</td><td>{{ partition.method }}</td><td>{{ partition.expression }}</td><td>{{ partition.boundary }}</td>
                <td>{{ partition.tablespace }}</td><td>{{ partition.estimatedRows ?? '' }}</td><td>{{ formatBytes(partition.dataBytes) }}</td></tr>
              <tr v-for="sub in windowState.subpartitions[partition.id] || []" v-show="windowState.expanded.has(partition.id)" :key="sub.id" class="subpartition">
                <td></td><td>↳ {{ sub.name }}</td><td>{{ sub.position }}</td><td>{{ sub.method }}</td><td>{{ sub.expression }}</td><td>{{ sub.boundary }}</td>
                <td>{{ sub.tablespace }}</td><td>{{ sub.estimatedRows ?? '' }}</td><td>{{ formatBytes(sub.dataBytes) }}</td></tr>
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
  pinned: boolean; hovered: boolean; focused: boolean; activeTab: Tab; object?: ObjectDescriptor;
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
    hovered: false, focused: false, activeTab: "columns", columns: [], indexes: [], partitions: [],
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
function changeOpacity(event: Event): void { emit("update:opacity", Number((event.target as HTMLInputElement).value)); }
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
function onKey(event: KeyboardEvent): void { if (event.key === "Escape") closeTransient(); }
function onDocumentPointer(event: PointerEvent): void {
  if (!(event.target as HTMLElement).closest(".object-inspector")) closeTransient();
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
.object-inspector { position: fixed; display:flex; flex-direction:column; overflow:hidden; color:var(--db-text); background:var(--db-panel); border:1px solid var(--db-border); border-radius:12px; box-shadow:0 18px 54px rgba(0,0,0,.28); transition:opacity .12s ease; }
.inspector-titlebar { height:42px; flex:none; display:flex; align-items:center; justify-content:space-between; padding:0 8px 0 12px; background:var(--db-panel-soft); border-bottom:1px solid var(--db-border); cursor:move; user-select:none; }
.object-title { min-width:0; display:flex; align-items:center; gap:8px; font-weight:650; } .object-title>span:nth-child(2){overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.object-title small{font-weight:400;color:var(--db-text-secondary);white-space:nowrap}.object-kind{display:grid;place-items:center;width:22px;height:22px;border-radius:6px;background:var(--db-accent);color:#fff;font-size:12px}
.title-actions{display:flex;align-items:center;gap:4px}.title-actions label{display:flex;align-items:center;gap:4px}.title-actions input{width:70px}.title-actions button,.ddl-toolbar button,.expand,.load-more{border:0;border-radius:6px;background:transparent;color:inherit;cursor:pointer}.title-actions button{width:28px;height:28px;font-size:18px}.title-actions button:hover,.title-actions button.active{background:var(--db-control-hover);color:var(--db-accent)}
.inspector-tabs{display:flex;flex:none;height:38px;padding:0 10px;border-bottom:1px solid var(--db-border)}.inspector-tabs button{position:relative;padding:0 16px;border:0;background:transparent;color:var(--db-text-secondary);cursor:pointer}.inspector-tabs button.active{color:var(--db-accent);font-weight:650}.inspector-tabs button.active:after{content:"";position:absolute;left:12px;right:12px;bottom:0;height:2px;background:var(--db-accent)}.inspector-tabs button:disabled{opacity:.38;cursor:not-allowed}
.inspector-content{position:relative;display:flex;flex-direction:column;min-height:0;flex:1;background:var(--db-panel)}.grid-scroll{min-height:0;flex:1;overflow:auto}table{border-collapse:separate;border-spacing:0;width:max-content;min-width:100%;font-size:12px}th,td{padding:7px 10px;border-right:1px solid var(--db-border);border-bottom:1px solid var(--db-border);white-space:nowrap;max-width:360px;overflow:hidden;text-overflow:ellipsis}th{position:sticky;top:0;z-index:1;text-align:left;background:var(--db-panel-soft);color:var(--db-text-secondary)}tbody tr:hover td{background:var(--db-control-hover)}.subpartition td{color:var(--db-text-secondary);background:color-mix(in srgb,var(--db-panel) 82%,var(--db-control-hover))}.expand{width:22px;height:22px;background:var(--db-control-hover)}
.section-message{display:grid;place-items:center;height:100%;color:var(--db-text-secondary)}.section-message.error{color:var(--el-color-danger)}.section-warning{padding:6px 10px;background:var(--el-color-warning-light-9);color:var(--el-color-warning-dark-2);font-size:12px}.load-more{margin:8px auto;padding:6px 12px;background:var(--db-control-hover)}
.ddl-toolbar{height:36px;flex:none;display:flex;align-items:center;gap:8px;padding:0 10px;border-bottom:1px solid var(--db-border);font-size:12px}.ddl-toolbar span{margin-right:auto;color:var(--db-text-secondary)}.ddl-toolbar .complete{color:var(--el-color-success)}.ddl-toolbar .incomplete{color:var(--el-color-warning)}.ddl-toolbar button{padding:5px 10px;background:var(--db-control-hover)}.ddl-toolbar button:disabled{opacity:.4;cursor:not-allowed}.ddl-viewer{box-sizing:border-box;flex:1;min-height:0;width:100%;resize:none;border:0;outline:0;padding:12px;background:var(--db-editor-bg);color:var(--db-text);font:12px/1.55 "SF Mono",Menlo,Consolas,monospace;white-space:pre;overflow:auto}
.resize-handle{position:absolute;z-index:5}.resize-n,.resize-s{left:8px;right:8px;height:6px;cursor:ns-resize}.resize-n{top:-2px}.resize-s{bottom:-2px}.resize-e,.resize-w{top:8px;bottom:8px;width:6px;cursor:ew-resize}.resize-e{right:-2px}.resize-w{left:-2px}.resize-ne,.resize-nw,.resize-se,.resize-sw{width:10px;height:10px}.resize-ne{right:-2px;top:-2px;cursor:nesw-resize}.resize-nw{left:-2px;top:-2px;cursor:nwse-resize}.resize-se{right:-2px;bottom:-2px;cursor:nwse-resize}.resize-sw{left:-2px;bottom:-2px;cursor:nesw-resize}
</style>
