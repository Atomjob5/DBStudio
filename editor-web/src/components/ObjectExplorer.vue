<template>
  <section class="object-explorer fill">
    <header class="explorer-header">
      <div class="explorer-title">
        <strong>数据库对象</strong>
        <span>{{ connectionName ?? "当前连接" }}</span>
      </div>
      <el-tooltip content="刷新对象树 · ⌘/Ctrl R">
        <el-button text circle :icon="Refresh" size="small" aria-label="刷新对象树"
                   :loading="completionLoading" @click="refresh" />
      </el-tooltip>
    </header>
    <div class="tree-search">
      <el-input v-model="filterText" :prefix-icon="Search" clearable size="small" placeholder="筛选已加载对象" aria-label="筛选数据库对象" />
    </div>
    <el-tree :key="treeKey" ref="treeRef" class="object-tree" node-key="id" lazy :load="loadNode" :props="treeProps"
             :filter-node-method="filterNode" highlight-current :expand-on-click-node="false" @node-dblclick="doubleClick">
      <template #default="{ data }">
        <el-dropdown trigger="contextmenu" @command="(command: string) => onCommand(command, data)">
          <span class="tree-node" :title="data.detail || data.label">
            <el-icon><component :is="iconFor(data)" /></el-icon>
            <span>{{ data.label }}</span>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="open" :disabled="!isTableLike(data)">打开表数据</el-dropdown-item>
              <el-dropdown-item command="query" :disabled="!isTableLike(data)">生成 SELECT</el-dropdown-item>
              <el-dropdown-item command="definition" :disabled="data.kind !== 'object'">查看定义</el-dropdown-item>
              <el-dropdown-item command="refresh">刷新</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </template>
    </el-tree>
  </section>
</template>

<script setup lang="ts">
import { ref, watch } from "vue";
import { Coin, Collection, Document, Folder, FolderOpened, Grid, Refresh, Search, Tickets } from "@element-plus/icons-vue";
import type { ElTree, LoadFunction, TreeNodeData } from "element-plus";
import { rpc } from "../bridge/rpc";
import { useMetadataStore } from "../stores/metadata";
import type { MetadataNode } from "../types";

const emit = defineEmits<{
  open: [node: MetadataNode, execute: boolean];
  definition: [node: MetadataNode];
  refresh: [];
}>();
const props = defineProps<{ connectionName?: string; editorId: string; connectionKey: string; completionLoading?: boolean }>();
const metadata = useMetadataStore();
const treeRef = ref<InstanceType<typeof ElTree>>();
const treeKey = ref(0);
const filterText = ref("");
const treeProps = { label: "label", children: "children", isLeaf: "leaf" };

const loadNode: LoadFunction = async (node, resolve) => {
  try {
    const data = node.data as unknown as MetadataNode;
    const payload = node.level === 0 ? { kind: "root", editorId: props.editorId } : { ...data, editorId: props.editorId };
    const nodes = await rpc.request<MetadataNode[]>("metadata.children", payload);
    if (node.level === 0) metadata.roots = nodes;
    resolve(nodes);
  } catch { resolve([]); }
};

function resetTree(): void {
  metadata.clearTree(props.connectionKey);
  treeKey.value++;
}
function refresh(): void { resetTree(); emit("refresh"); }

watch(filterText, (value) => treeRef.value?.filter(value.trim()));
function filterNode(value: string, treeData: TreeNodeData): boolean {
  if (!value) return true;
  const data = treeData as MetadataNode;
  const haystack = `${data.label} ${data.detail ?? ""} ${data.objectType ?? ""}`.toLocaleLowerCase();
  return haystack.includes(value.toLocaleLowerCase());
}

function isTableLike(node: MetadataNode): boolean { return node.kind === "object" && ["TABLE", "VIEW"].includes(node.objectType ?? ""); }
function doubleClick(_data: MetadataNode, node: { data: MetadataNode }): void { if (isTableLike(node.data)) emit("open", node.data, true); }
function onCommand(command: string, node: MetadataNode): void {
  if (command === "open") emit("open", node, true);
  else if (command === "query") emit("open", node, false);
  else if (command === "definition") emit("definition", node);
  else if (command === "refresh") refresh();
}
function iconFor(node: MetadataNode): unknown {
  if (node.kind === "catalog" || node.kind === "schema") return Coin;
  if (node.kind === "group") return Folder;
  if (node.kind === "column") return Tickets;
  if (node.objectType === "TABLE") return Grid;
  if (node.objectType === "VIEW") return Collection;
  if (node.kind === "object") return Document;
  return FolderOpened;
}

defineExpose({ refresh, resetTree });
</script>

<style scoped>
.object-explorer { display: flex; flex-direction: column; background: var(--db-panel-soft); }
.explorer-header {
  min-height: 52px;
  padding: 9px 8px 7px 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.explorer-title { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.explorer-title strong { color: var(--db-text); font-size: 13px; font-weight: 650; letter-spacing: -0.01em; }
.explorer-title span { overflow: hidden; color: var(--db-muted); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.tree-search { padding: 0 8px 8px; border-bottom: 1px solid var(--db-border-soft); }
.tree-search :deep(.el-input__wrapper) { border-radius: 8px; background: var(--db-content); }
.object-tree { flex: 1; overflow: auto; background: transparent; padding: 5px 6px 8px; }
.tree-node { min-width: 0; display: inline-flex; align-items: center; gap: 6px; width: 100%; overflow: hidden; }
.tree-node span:last-child { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tree-node .el-icon { color: var(--db-text-secondary); }
:deep(.el-tree-node__content) {
  height: 28px;
  margin: 1px 0;
  border-radius: 7px;
  color: var(--db-text-secondary);
  transition: color 120ms var(--db-ease), background-color 120ms var(--db-ease);
}
:deep(.el-tree-node__content:hover) { background: var(--db-control-hover); color: var(--db-text); }
:deep(.el-tree-node.is-current > .el-tree-node__content) { background: var(--db-accent-soft); color: var(--db-text); }
:deep(.el-tree-node.is-current > .el-tree-node__content .el-icon) { color: var(--db-accent); }
</style>
