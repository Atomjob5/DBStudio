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

      <section class="settings-section">
        <div class="section-heading"><div><strong>查询结果</strong><span>控制内存中的最大显示量</span></div></div>
        <el-form-item label="每个结果集最大展示行数">
          <el-input-number :model-value="maxRows" :min="1" :max="100000" :step="500" controls-position="right"
                           @update:model-value="$emit('update:maxRows', $event ?? 1000)" />
        </el-form-item>
        <el-form-item label="每批流式推送行数">
          <el-input-number :model-value="streamBatchRows" :min="1" :max="1000" :step="50" controls-position="right"
                           @update:model-value="$emit('update:streamBatchRows', $event ?? 100)" />
        </el-form-item>
        <el-alert title="较小批次首屏更快，但会增加事件和界面更新次数。完整导出不受这些设置限制。"
                  type="info" show-icon :closable="false" />
      </section>
    </el-form>
  </el-drawer>
</template>

<script setup lang="ts">
import { Monitor, Moon, Sunny } from "@element-plus/icons-vue";
import type { ResolvedTheme, ThemePreference } from "../types";

defineProps<{ modelValue: boolean; theme: ThemePreference; resolvedTheme: ResolvedTheme; maxRows: number; streamBatchRows: number }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; "update:theme": [value: ThemePreference]; "update:maxRows": [value: number]; "update:streamBatchRows": [value: number] }>();
function themeChanged(value: string | number | boolean | undefined): void {
  if (value === "system" || value === "dark" || value === "light") emit("update:theme", value);
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
</style>
