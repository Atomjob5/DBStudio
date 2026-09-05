<template>
  <el-drawer :model-value="modelValue" title="查询历史" size="62%" class="history-drawer" @open="load"
             @update:model-value="$emit('update:modelValue', $event)">
    <div class="history-content">
      <div class="history-toolbar">
        <div><strong>最近执行</strong><span>双击一条记录可在新标签中打开</span></div>
        <el-input v-model="filterText" :prefix-icon="Search" clearable placeholder="筛选 SQL 或状态" aria-label="筛选查询历史" />
      </div>
      <el-table v-loading="loading" :data="pagedEntries" class="history-table" @row-dblclick="open">
        <el-table-column prop="executedAt" label="执行时间（北京时间）" width="210">
          <template #default="{ row }"><span class="history-time">{{ formatHistoryTime(row.executedAt) }}</span></template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="105">
          <template #default="{ row }"><el-tag :type="row.status === 'SUCCESS' ? 'success' : 'danger'" size="small" effect="plain">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="durationMs" label="耗时" width="100"><template #default="{ row }">{{ row.durationMs }} ms</template></el-table-column>
        <el-table-column prop="rowCount" label="行数" width="90" />
        <el-table-column prop="sql" label="SQL" show-overflow-tooltip />
        <el-table-column width="76"><template #default="{ row }"><el-button link type="primary" @click="open(row)">打开</el-button></template></el-table-column>
      </el-table>
      <div class="history-footer">
        <span>共 {{ filteredEntries.length }} 条</span>
        <el-pagination v-model:current-page="page" :page-size="pageSize" :total="filteredEntries.length"
                       layout="prev, pager, next" small background />
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { Search } from "@element-plus/icons-vue";
import { rpc } from "../bridge/rpc";
import { formatHistoryTime } from "../historyTime";
import type { HistoryEntry } from "../types";

defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; open: [entry: HistoryEntry] }>();
const loading = ref(false);
const entries = ref<HistoryEntry[]>([]);
const page = ref(1);
const filterText = ref("");
const pageSize = 50;
const filteredEntries = computed(() => {
  const value = filterText.value.trim().toLocaleLowerCase();
  return value ? entries.value.filter((entry) => `${entry.sql} ${entry.status}`.toLocaleLowerCase().includes(value)) : entries.value;
});
const pagedEntries = computed(() => filteredEntries.value.slice((page.value - 1) * pageSize, page.value * pageSize));
watch(filterText, () => { page.value = 1; });
async function load(): Promise<void> { loading.value = true; page.value = 1; try { entries.value = await rpc.request<HistoryEntry[]>("history.list", { limit: 1000 }); } finally { loading.value = false; } }
function open(entry: unknown): void { emit("open", entry as HistoryEntry); emit("update:modelValue", false); }
</script>

<style scoped>
.history-content { display: flex; flex-direction: column; gap: 10px; height: 100%; min-height: 0; }
.history-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 18px; }
.history-toolbar > div { display: flex; flex-direction: column; gap: 2px; }
.history-toolbar strong { font-size: 13px; font-weight: 650; }
.history-toolbar span { color: var(--db-muted); font-size: 11px; }
.history-toolbar .el-input { width: 260px; }
.history-table { flex: 1; min-height: 0; border: 1px solid var(--db-border-soft); border-radius: 12px; overflow: hidden; }
.history-time { white-space: nowrap; font-variant-numeric: tabular-nums; }
.history-footer { min-height: 30px; display: flex; align-items: center; justify-content: space-between; color: var(--db-muted); font-size: 11px; }
</style>
