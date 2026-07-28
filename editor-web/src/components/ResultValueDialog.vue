<template>
  <el-dialog :model-value="modelValue" class="result-value-dialog" width="min(760px, 86vw)"
             append-to-body destroy-on-close :show-close="true"
             @update:model-value="$emit('update:modelValue', $event)">
    <el-tabs v-if="formattedJson !== undefined" v-model="activeView" class="result-value-tabs">
      <el-tab-pane label="文本" name="text">
        <pre class="result-value-content">{{ displayValue }}</pre>
      </el-tab-pane>
      <el-tab-pane label="JSON" name="json">
        <pre class="result-value-content result-value-json">{{ formattedJson }}</pre>
      </el-tab-pane>
    </el-tabs>
    <pre v-else class="result-value-content">{{ displayValue }}</pre>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { formatJsonValue } from "../resultGrid";

const props = defineProps<{ modelValue: boolean; value: string | null }>();
defineEmits<{ "update:modelValue": [value: boolean] }>();

const activeView = ref<"text" | "json">("text");
const displayValue = computed(() => props.value === null ? "NULL" : props.value);
const formattedJson = computed(() => formatJsonValue(props.value));

watch(() => props.modelValue, (visible) => {
  if (visible) activeView.value = "text";
});
</script>

<style>
.result-value-dialog .el-dialog__header {
  height: 38px;
  padding: 0;
  border-bottom: 1px solid var(--db-border-soft);
}
.result-value-dialog .el-dialog__headerbtn { top: 0; width: 38px; height: 38px; }
.result-value-dialog .el-dialog__body { padding: 0; }
.result-value-tabs .el-tabs__header { height: 38px; margin: 0; padding: 0 16px; }
.result-value-tabs .el-tabs__item { height: 38px; font-size: 12px; }
.result-value-tabs .el-tabs__nav-wrap::after { height: 1px; background: var(--db-border-soft); }
.result-value-tabs .el-tabs__content { overflow: visible; }
.result-value-content {
  box-sizing: border-box;
  min-height: 180px;
  max-height: min(66vh, 620px);
  margin: 0;
  padding: 18px 20px;
  overflow: auto;
  color: var(--db-text);
  font: 12px/1.65 "SF Mono", Menlo, Consolas, monospace;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  user-select: text;
}
.result-value-json { white-space: pre; }
</style>
