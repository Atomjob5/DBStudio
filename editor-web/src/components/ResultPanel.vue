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
          <el-select v-model="sortColumn" clearable placeholder="排序" size="small" aria-label="选择排序列">
            <el-option v-for="(column, index) in activeResult?.columns" :key="index" :label="column" :value="index" />
          </el-select>
          <el-tooltip :content="descending ? '当前降序，点击切换升序' : '当前升序，点击切换降序'">
            <el-button text :icon="descending ? SortDown : SortUp" aria-label="切换排序方向" :disabled="sortColumn === ''"
                       @click="descending = !descending" />
          </el-tooltip>
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
      <div v-else-if="activeResult?.columns.length" class="table-host">
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
  </section>
</template>

<script setup lang="ts">
import { computed, h, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { CopyDocument, DataAnalysis, Download, SortDown, SortUp } from "@element-plus/icons-vue";
import type { Column } from "element-plus";
import type { QueryExecutionState } from "../types";

const props = defineProps<{ execution?: QueryExecutionState }>();
const emit = defineEmits<{ "export-loaded": [resultIndex: number]; "export-full": [resultIndex: number] }>();
const activeIndex = ref(0);
const sortColumn = ref<number | "">("");
const descending = ref(false);
const selectedCell = ref<{ row: number; column: number; value: string | null }>();
const activeResult = computed(() => props.execution?.results.find((item) => item.resultIndex === activeIndex.value) ?? props.execution?.results[0]);
const summary = computed(() => {
  const result = activeResult.value;
  if (!result) return "";
  if (props.execution?.busy) return "正在执行…";
  return result.columns.length ? `${result.rows.length} 行 · ${result.durationMs} ms` : `${result.updateCount} 行受影响 · ${result.durationMs} ms`;
});

watch(() => props.execution?.executionId, () => { activeIndex.value = 0; selectedCell.value = undefined; sortColumn.value = ""; });

const tableRows = computed(() => {
  const source = activeResult.value?.rows ?? [];
  if (sortColumn.value === "") return source;
  const rows = [...source];
  const index = sortColumn.value;
  return rows.sort((a, b) => {
    const left = a[index]; const right = b[index];
    const comparison = left === right ? 0 : left === null ? -1 : right === null ? 1 : left.localeCompare(right, undefined, { numeric: true });
    return descending.value ? -comparison : comparison;
  });
});

const tableColumns = computed<Column[]>(() => (activeResult.value?.columns ?? []).map((title, columnIndex) => ({
  key: `c${columnIndex}`,
  dataKey: columnIndex,
  title,
  width: Math.max(120, Math.min(320, title.length * 12 + 56)),
  cellRenderer: ({ cellData, rowIndex }: { cellData: string | null; rowIndex: number }) => h("span", {
    class: ["result-cell", cellData === null ? "null-value" : cellData.startsWith?.("0x") ? "binary-value" : "", selectedCell.value?.row === rowIndex && selectedCell.value?.column === columnIndex ? "selected" : ""],
    title: cellData !== null && cellData.length >= 40 ? cellData : undefined,
    onClick: () => { selectedCell.value = { row: rowIndex, column: columnIndex, value: cellData }; }
  }, cellData === null ? "NULL" : cellData)
})));

async function copyCell(): Promise<void> {
  const text = selectedCell.value?.value ?? "NULL";
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const input = document.createElement("textarea");
    input.value = text; document.body.append(input); input.select(); document.execCommand("copy"); input.remove();
  }
  ElMessage.success("已复制单元格");
}

function exportCommand(command: string): void {
  if (command === "loaded") emit("export-loaded", activeIndex.value);
  else if (command === "full") emit("export-full", activeIndex.value);
}
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
.result-actions .el-select { width: 118px; }
.result-actions :deep(.el-button) { width: 28px; min-height: 28px; padding: 0; }
.result-actions :deep(.el-dropdown) { display: inline-flex; }
.table-host { flex: 1; min-height: 0; }
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
