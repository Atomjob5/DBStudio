<template>
  <div class="result-single-record-view" aria-label="单个记录查看">
    <el-table :data="fields" height="100%" border row-key="index" class="result-single-record-table">
      <el-table-column prop="label" label="字段名" min-width="180" show-overflow-tooltip />
      <el-table-column label="字段值" min-width="280">
        <template #default="{ row: field }">
          <span class="single-record-value" :class="{ 'null-value': field.value === null }"
                :title="valueTitle(field.value)">
            {{ field.value === null ? "NULL" : field.value }}
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="remarks" label="字段备注" min-width="220" show-overflow-tooltip />
      <el-table-column prop="typeName" label="字段类型" width="150" show-overflow-tooltip />
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type { ColumnOption } from "../columnFilter";
import type { ViewRow } from "../resultGrid";

const props = defineProps<{
  columns: ColumnOption[];
  row: ViewRow;
}>();

const fields = computed(() => props.columns.map((column) => ({
  index: column.index,
  label: column.label,
  value: props.row.cells[column.index] ?? null,
  remarks: column.remarks,
  typeName: column.typeName
})));

function valueTitle(value: string | null): string | undefined {
  return value !== null && value.length >= 40 ? value : undefined;
}
</script>

<style scoped>
.result-single-record-view {
  box-sizing: border-box;
  width: 100%;
  height: 100%;
  min-height: 0;
  padding: 6px;
}
.result-single-record-table { width: 100%; height: 100%; }
.single-record-value {
  display: block;
  overflow: hidden;
  color: var(--db-text);
  font-variant-numeric: tabular-nums;
  text-overflow: ellipsis;
  white-space: nowrap;
  user-select: text;
}
:deep(.el-table) {
  --el-table-border-color: var(--db-border-soft);
  --el-table-header-bg-color: var(--db-table-header);
  --el-table-header-text-color: var(--db-text-secondary);
  --el-table-row-hover-bg-color: var(--db-accent-soft);
  --el-table-text-color: var(--db-text);
  background: var(--db-content);
  font-family: inherit;
  font-size: 12px;
}
:deep(.el-table th.el-table__cell) { font-weight: 600; }
:deep(.el-table td.el-table__cell),
:deep(.el-table th.el-table__cell) { padding: 6px 0; }
</style>
