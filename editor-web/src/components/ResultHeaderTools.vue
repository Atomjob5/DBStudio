<template>
  <span class="result-header-tools" @pointerdown.stop @click.stop @dblclick.stop @dragstart.prevent>
    <IconTooltip v-if="sortingEnabled" :content="sortTitle" placement="top">
      <el-button text circle size="small" :class="{ active: !!sort }" :icon="sortIcon"
                 :aria-label="sortTitle" @click="$emit('sort')" />
    </IconTooltip>
    <el-popover v-if="filteringEnabled" v-model:visible="visible" trigger="click" placement="bottom-end"
                :width="250" popper-class="result-filter-popover">
      <template #reference>
        <IconTooltip :content="`筛选 ${column.label}`" placement="top" :disabled="visible">
          <el-button text circle size="small" :class="{ active: !!filter }" :icon="Filter"
                     :aria-label="`筛选 ${column.label}`" />
        </IconTooltip>
      </template>
      <div class="result-filter-editor" @keydown.capture="filterEditorKeydown">
        <strong>筛选 {{ column.label }}</strong>
        <el-select v-model="operator" size="small" aria-label="筛选条件" :teleported="false">
          <el-option v-for="option in operatorOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-select v-if="category === 'boolean' && needsValue" v-model="value" size="small" aria-label="筛选值"
                   :teleported="false">
          <el-option label="TRUE" value="true" /><el-option label="FALSE" value="false" />
        </el-select>
        <el-input v-else-if="needsValue" v-model="value" size="small" clearable aria-label="筛选值"
                  :placeholder="category === 'number' ? '输入数字' : category === 'date' ? '输入日期或时间' : '输入文本'"
                  @keyup.enter="apply" />
        <footer><el-button size="small" :disabled="!filter" @click="clear">清除</el-button>
          <el-button type="primary" size="small" :disabled="needsValue && !value" @click="apply">应用</el-button>
        </footer>
      </div>
    </el-popover>
  </span>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { Filter, Sort, SortDown, SortUp } from "@element-plus/icons-vue";
import type { QueryColumn } from "../types";
import { filterCategory, type FilterOperator, type ResultFilter, type ResultSort } from "../resultGrid";
import IconTooltip from "./IconTooltip.vue";

const props = defineProps<{ column: QueryColumn; columnIndex: number; sort?: ResultSort; filter?: ResultFilter;
  sortingEnabled: boolean; filteringEnabled: boolean }>();
const emit = defineEmits<{ sort: []; apply: [filter: ResultFilter]; clear: [] }>();
const visible = ref(false); const operator = ref<FilterOperator>("contains"); const value = ref("");
const category = computed(() => filterCategory(props.column));
const needsValue = computed(() => operator.value !== "null" && operator.value !== "not-null");
const sortIcon = computed(() => props.sort?.direction === "asc" ? SortUp : props.sort?.direction === "desc" ? SortDown : Sort);
const sortTitle = computed(() => props.sort?.direction === "asc" ? `按 ${props.column.label} 降序`
  : props.sort?.direction === "desc" ? `取消 ${props.column.label} 排序` : `按 ${props.column.label} 升序`);
const operatorOptions = computed(() => {
  const common = [{ value: "eq", label: "等于" }, { value: "neq", label: "不等于" }];
  const contains = [{ value: "contains", label: "包含" }];
  const nulls = [{ value: "null", label: "为空（NULL）" }, { value: "not-null", label: "非空" }];
  if (category.value === "text") return [
    { value: "contains", label: "包含" }, { value: "not-contains", label: "不包含" }, ...common,
    { value: "starts", label: "开头是" }, { value: "ends", label: "结尾是" }, ...nulls
  ];
  if (category.value === "boolean") return [...contains, ...common, ...nulls];
  return [...contains, ...common, { value: "gt", label: "大于" }, { value: "gte", label: "大于等于" },
    { value: "lt", label: "小于" }, { value: "lte", label: "小于等于" }, ...nulls];
});

watch(visible, (open) => {
  if (!open) return;
  operator.value = props.filter?.operator ?? (category.value === "text" ? "contains" : "eq");
  value.value = props.filter?.value ?? "";
});
function apply(): void {
  emit("apply", { columnIndex: props.columnIndex, operator: operator.value, value: value.value });
  visible.value = false;
}
function clear(): void { emit("clear"); visible.value = false; }
function filterEditorKeydown(event: KeyboardEvent): void {
  if (event.key !== "Escape" && event.key !== "Esc") return;
  event.preventDefault();
  event.stopPropagation();
  visible.value = false;
}
</script>

<style scoped>
.result-header-tools{display:inline-flex;flex:none;align-items:center;margin-left:auto}
.result-header-tools :deep(.el-button){width:22px;height:22px;margin:0;padding:0;color:var(--db-muted)}
.result-header-tools :deep(.el-button.active){color:var(--db-accent);background:var(--db-accent-soft)}
.result-filter-editor{display:flex;flex-direction:column;gap:9px}.result-filter-editor strong{font-size:12px}
.result-filter-editor footer{display:flex;justify-content:flex-end;gap:6px;margin-top:2px}
</style>
