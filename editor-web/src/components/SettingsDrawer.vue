<template>
  <el-drawer :model-value="modelValue" title="设置" size="420px" class="settings-drawer"
             @update:model-value="$emit('update:modelValue', $event)">
    <el-form label-position="top" class="settings-form">
      <section class="settings-section">
        <div class="section-heading">
          <div><strong>外观</strong><span>当前显示为{{ resolvedTheme === "dark" ? "深色" : "亮色" }}</span></div>
        </div>
        <el-form-item label="界面主题">
          <el-radio-group :model-value="theme" class="theme-options" @update:model-value="themeChanged">
            <el-radio-button value="system"><el-icon><Monitor /></el-icon>跟随系统</el-radio-button>
            <el-radio-button value="light"><el-icon><Sunny /></el-icon>亮色</el-radio-button>
            <el-radio-button value="dark"><el-icon><Moon /></el-icon>深色</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </section>

      <section class="settings-section result-settings">
        <div class="section-heading"><div><strong>结果集</strong><span>数据获取能力、列布局与复制</span></div></div>
        <el-form-item class="compact-setting-row">
          <template #label>
            <div class="setting-label"><span>单页展示行数</span>
              <el-tooltip content="限制单次查询结果集返回笔数，完整导出不受影响。" placement="top">
                <el-icon class="setting-help" tabindex="0" aria-label="单页展示行数"><QuestionFilled /></el-icon>
              </el-tooltip>
            </div>
          </template>
          <el-input-number class="compact-number" size="small" :model-value="maxRows" :min="1" :max="100000" :step="500" controls-position="right"
                           @update:model-value="$emit('update:maxRows', $event ?? 1000)" />
        </el-form-item>
        <el-form-item class="compact-setting-row">
          <template #label>
            <div class="setting-label"><span>流式推送行数</span>
              <el-tooltip content="控制每个查询结果事件传输的行数。值越小首屏越快，但事件触发更频繁。" placement="top">
                <el-icon class="setting-help" tabindex="0" aria-label="流式推送行数说明"><QuestionFilled /></el-icon>
              </el-tooltip>
            </div>
          </template>
          <el-input-number class="compact-number" size="small" :model-value="streamBatchRows" :min="1" :max="1000" :step="50" controls-position="right"
                           @update:model-value="$emit('update:streamBatchRows', $event ?? 100)" />
        </el-form-item>
        <el-form-item class="compact-setting-row">
          <template #label>
            <div class="setting-label"><span>列布局保留范围</span>
              <el-tooltip content="当前结果集会在新执行时重置；当前编辑标签仅在字段集合完全一致时复用列顺序和宽度。" placement="top">
                <el-icon class="setting-help" tabindex="0" aria-label="列布局保留范围说明"><QuestionFilled /></el-icon>
              </el-tooltip>
            </div>
          </template>
          <el-radio-group size="small" :model-value="columnLayoutScope" @update:model-value="layoutScopeChanged">
            <el-radio-button value="result">当前结果集</el-radio-button>
            <el-radio-button value="editor">当前编辑标签</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item class="compact-setting-row">
          <template #label><div class="setting-label"><span>双击表头复制列名</span></div></template>
          <el-switch size="small" :model-value="copyHeaderOnDoubleClick"
                     @update:model-value="copyHeaderToggleChanged" />
        </el-form-item>
        <el-form-item class="compact-setting-row">
          <template #label>
            <div class="setting-label"><span>多列复制分隔符</span>
              <el-tooltip content="用于右键复制多列时连接字段；包含分隔符、引号或换行的内容会自动转义。" placement="top">
                <el-icon class="setting-help" tabindex="0" aria-label="多列复制分隔符说明"><QuestionFilled /></el-icon>
              </el-tooltip>
            </div>
          </template>
          <el-radio-group class="separator-options" size="small" :model-value="copySeparator" @update:model-value="copySeparatorChanged">
            <el-radio-button value="comma" aria-label="逗号分隔符">,</el-radio-button>
            <el-radio-button value="tab" aria-label="Tab分隔符">Tab</el-radio-button>
            <el-radio-button value="semicolon" aria-label="分号分隔符">;</el-radio-button>
            <el-radio-button value="pipe" aria-label="竖线分隔符">|</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </section>
    </el-form>
  </el-drawer>
</template>

<script setup lang="ts">
import { Monitor, Moon, QuestionFilled, Sunny } from "@element-plus/icons-vue";
import type { ResolvedTheme, ThemePreference } from "../types";
import type { ColumnLayoutScope } from "../columnLayout";
import type { CopySeparator } from "../resultCopy";

defineProps<{ modelValue: boolean; theme: ThemePreference; resolvedTheme: ResolvedTheme; maxRows: number;
  streamBatchRows: number; columnLayoutScope: ColumnLayoutScope; copyHeaderOnDoubleClick: boolean;
  copySeparator: CopySeparator }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; "update:theme": [value: ThemePreference];
  "update:maxRows": [value: number]; "update:streamBatchRows": [value: number];
  "update:columnLayoutScope": [value: ColumnLayoutScope]; "update:copyHeaderOnDoubleClick": [value: boolean];
  "update:copySeparator": [value: CopySeparator] }>();
function themeChanged(value: string | number | boolean | undefined): void {
  if (value === "system" || value === "dark" || value === "light") emit("update:theme", value);
}
function layoutScopeChanged(value: string | number | boolean | undefined): void {
  if (value === "result" || value === "editor") emit("update:columnLayoutScope", value);
}
function copySeparatorChanged(value: string | number | boolean | undefined): void {
  if (value === "comma" || value === "tab" || value === "semicolon" || value === "pipe") {
    emit("update:copySeparator", value);
  }
}
function copyHeaderToggleChanged(value: string | number | boolean): void {
  emit("update:copyHeaderOnDoubleClick", value === true);
}
</script>

<style scoped>
.settings-form { display: flex; flex-direction: column; gap: 16px; }
.settings-section {
  padding: 16px;
  border: 1px solid var(--db-border-soft);
  border-radius: 14px;
  background: var(--db-content);
  background: color-mix(in srgb, var(--db-content) 82%, transparent);
}
.section-heading { margin-bottom: 15px; }
.section-heading div { display: flex; flex-direction: column; gap: 3px; }
.section-heading strong { font-size: 14px; font-weight: 650; }
.section-heading span { color: var(--db-muted); font-size: 11px; }
.theme-options { display: flex; width: 100%; }
.theme-options :deep(.el-radio-button) { flex: 1; }
.theme-options :deep(.el-radio-button__inner) { width: 100%; padding-inline: 8px; }
.theme-options .el-icon { margin-right: 5px; vertical-align: -2px; }
.settings-section :deep(.el-form-item:last-of-type) { margin-bottom: 12px; }
.result-settings .section-heading { margin-bottom: 8px; }
.compact-setting-row {
  display: grid;
  grid-template-columns: minmax(142px, 1fr) auto;
  min-height: 40px;
  margin: 0;
  padding: 4px 0;
  align-items: center;
  column-gap: 12px;
  border-bottom: 1px solid var(--db-border-soft);
}
.compact-setting-row:last-child { border-bottom: 0; }
.compact-setting-row :deep(.el-form-item__label) {
  width: auto !important;
  height: auto;
  margin: 0;
  padding: 0;
  justify-content: flex-start;
  color: var(--db-text);
  line-height: 1.25;
}
.compact-setting-row :deep(.el-form-item__content) {
  min-width: 0;
  margin: 0 !important;
  justify-content: flex-end;
  line-height: normal;
}
.setting-label { display: flex; min-width: 0; align-items: center; gap: 5px; }
.setting-label > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.setting-help {
  flex: none;
  color: var(--db-muted);
  font-size: 13px;
  cursor: help;
}
.setting-help:hover, .setting-help:focus-visible { color: var(--db-accent); outline: none; }
.compact-number { width: 132px; }
.compact-setting-row :deep(.el-radio-button__inner) { padding-inline: 9px; }
.separator-options :deep(.el-radio-button__inner) { min-width: 38px; padding-inline: 8px; }
</style>
