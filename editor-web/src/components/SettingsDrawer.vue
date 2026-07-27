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

      <section class="settings-section connection-settings">
        <div class="section-heading"><div><strong>数据库连接</strong><span>控制事务模式、活动会话与空闲回收</span></div></div>
        <el-form-item class="compact-setting-row">
          <template #label>
            <div class="setting-label"><span>自动提交事务</span>
              <el-tooltip content="开启后，每条DML成功执行即提交，无法再通过回滚按钮撤销。" placement="top">
                <el-icon class="setting-help" tabindex="0" aria-label="自动提交事务说明"><QuestionFilled /></el-icon>
              </el-tooltip>
            </div>
          </template>
          <el-switch size="small" aria-label="自动提交事务" :model-value="autoCommit"
                     @update:model-value="$emit('update:autoCommit', $event === true)" />
        </el-form-item>
        <el-form-item class="compact-setting-row">
          <template #label>
            <div class="setting-label"><span>最大活动链接数</span>
              <el-tooltip content="限制整个DBStudio进程持有的编辑器JDBC会话数量；同一链接的多个标签分别计数，保存配置不计数。" placement="top">
                <el-icon class="setting-help" tabindex="0" aria-label="最大活动链接数说明"><QuestionFilled /></el-icon>
              </el-tooltip>
            </div>
          </template>
          <el-input-number class="compact-number" size="small" :model-value="maxActiveSessions" :min="1" :max="100" controls-position="right"
                           @update:model-value="$emit('update:maxActiveSessions', $event ?? 10)" />
        </el-form-item>
        <el-form-item class="compact-setting-row">
          <template #label>
            <div class="setting-label"><span>空闲链接回收时间</span>
              <el-tooltip content="仅释放未执行查询且没有未提交事务的空闲会话，再次使用时会自动重连。" placement="top">
                <el-icon class="setting-help" tabindex="0" aria-label="空闲链接回收时间说明"><QuestionFilled /></el-icon>
              </el-tooltip>
            </div>
          </template>
          <div class="number-with-unit"><el-input-number class="compact-number" size="small" :model-value="idleTimeoutMinutes" :min="1" :max="1440" controls-position="right"
                           @update:model-value="$emit('update:idleTimeoutMinutes', $event ?? 10)" /><span>分钟</span></div>
        </el-form-item>
        <el-form-item class="compact-setting-row">
          <template #label>
            <div class="setting-label"><span>事务断连回滚时间</span>
              <el-tooltip content="浏览器或事件通道断开后，未提交事务会保留原JDBC至该时间；超时后自动回滚。Java进程退出时无法恢复事务。" placement="top">
                <el-icon class="setting-help" tabindex="0" aria-label="事务断连回滚时间说明"><QuestionFilled /></el-icon>
              </el-tooltip>
            </div>
          </template>
          <div class="number-with-unit"><el-input-number class="compact-number" size="small"
            :model-value="transactionDisconnectRollbackMinutes" :min="1" :max="1440" controls-position="right"
            @update:model-value="$emit('update:transactionDisconnectRollbackMinutes', $event ?? 10)" /><span>分钟</span></div>
        </el-form-item>
      </section>

      <section class="settings-section completion-settings">
        <div class="section-heading"><div><strong>SQL补全</strong><span>上下文建议与浏览器持久缓存</span></div></div>
        <el-form-item class="compact-setting-row">
          <template #label>
            <div class="setting-label"><span>补全候选词数量</span>
              <el-tooltip content="每次最多返回给编辑器的候选数量；继续输入会在Worker中重新筛选。" placement="top">
                <el-icon class="setting-help" tabindex="0" aria-label="补全候选词数量说明"><QuestionFilled /></el-icon>
              </el-tooltip>
            </div>
          </template>
          <el-input-number class="compact-number" size="small" :model-value="completionCandidateLimit"
                           :min="10" :max="1000" :step="10" controls-position="right"
                           @update:model-value="$emit('update:completionCandidateLimit', $event ?? 100)" />
        </el-form-item>
        <el-form-item class="compact-setting-row completion-cache-row">
          <template #label>
            <div class="setting-label"><span>补全缓存占用</span>
              <el-tooltip content="统计浏览器IndexedDB中的紧凑补全快照；清理不会影响对象树、连接或查询结果。" placement="top">
                <el-icon class="setting-help" tabindex="0" aria-label="补全缓存占用说明"><QuestionFilled /></el-icon>
              </el-tooltip>
            </div>
          </template>
          <div class="completion-cache-control">
            <span data-testid="completion-cache-stats">约 {{ completionCacheSize }} · {{ completionCacheEnvironmentCount }}个环境<span v-if="completionCacheLoadingCount"> · {{ completionCacheLoadingCount }}项加载中</span></span>
            <el-button text type="danger" size="small" :icon="Delete" aria-label="清理全部补全缓存"
                       :disabled="!canClearCompletionCaches" @click="$emit('clearCompletionCaches')">清理</el-button>
          </div>
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
            <div class="setting-label"><span>滚动优化</span>
              <el-tooltip content="减少滚动时渲染的行列数量，降低宽表和大量数据滚动时的渲染压力，适合低配置设备。" placement="top">
                <el-icon class="setting-help" tabindex="0" aria-label="滚动优化说明"><QuestionFilled /></el-icon>
              </el-tooltip>
            </div>
          </template>
          <el-switch size="small" aria-label="滚动优化" :model-value="scrollOptimizationEnabled"
                     @update:model-value="$emit('update:scrollOptimizationEnabled', $event === true)" />
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
          <template #label><div class="setting-label"><span>启用表头排序</span></div></template>
          <el-switch size="small" :model-value="headerSortingEnabled"
                     @update:model-value="$emit('update:headerSortingEnabled', $event === true)" />
        </el-form-item>
        <el-form-item class="compact-setting-row">
          <template #label><div class="setting-label"><span>启用表头筛选</span></div></template>
          <el-switch size="small" :model-value="headerFilteringEnabled"
                     @update:model-value="$emit('update:headerFilteringEnabled', $event === true)" />
        </el-form-item>
        <el-form-item class="compact-setting-row">
          <template #label>
            <div class="setting-label"><span>表头显示字段备注</span>
              <el-tooltip content="字段名下方显示一行字段备注；超长内容会省略，不影响默认列宽。" placement="top">
                <el-icon class="setting-help" tabindex="0" aria-label="表头字段备注说明"><QuestionFilled /></el-icon>
              </el-tooltip>
            </div>
          </template>
          <el-switch size="small" :model-value="showColumnRemarksInHeader"
                     @update:model-value="$emit('update:showColumnRemarksInHeader', $event === true)" />
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

      <section class="settings-section status-bar-settings">
        <div class="section-heading"><div><strong>状态栏</strong><span>控制当前结果列的辅助信息</span></div></div>
        <el-form-item class="compact-setting-row">
          <template #label>
            <div class="setting-label"><span>显示选中列字段备注</span>
              <el-tooltip content="点击结果单元格后在状态栏显示字段备注；双击备注可查看和复制完整内容。" placement="top">
                <el-icon class="setting-help" tabindex="0" aria-label="状态栏字段备注说明"><QuestionFilled /></el-icon>
              </el-tooltip>
            </div>
          </template>
          <el-switch size="small" :model-value="showSelectedColumnRemarks"
                     @update:model-value="$emit('update:showSelectedColumnRemarks', $event === true)" />
        </el-form-item>
      </section>
    </el-form>
  </el-drawer>
</template>

<script setup lang="ts">
import { Delete, Monitor, Moon, QuestionFilled, Sunny } from "@element-plus/icons-vue";
import type { ResolvedTheme, ThemePreference } from "../types";
import type { ColumnLayoutScope } from "../columnLayout";
import type { CopySeparator } from "../resultCopy";

defineProps<{ modelValue: boolean; theme: ThemePreference; resolvedTheme: ResolvedTheme; maxRows: number;
  streamBatchRows: number; columnLayoutScope: ColumnLayoutScope; copyHeaderOnDoubleClick: boolean;
  copySeparator: CopySeparator; maxActiveSessions: number; idleTimeoutMinutes: number;
  headerSortingEnabled: boolean; headerFilteringEnabled: boolean;
  showColumnRemarksInHeader: boolean; scrollOptimizationEnabled: boolean; showSelectedColumnRemarks: boolean;
  autoCommit: boolean;
  transactionDisconnectRollbackMinutes: number;
  completionCacheSize: string; completionCacheEnvironmentCount: number; completionCacheLoadingCount: number;
  completionCandidateLimit: number; canClearCompletionCaches: boolean }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; "update:theme": [value: ThemePreference];
  "update:maxRows": [value: number]; "update:streamBatchRows": [value: number];
  "update:columnLayoutScope": [value: ColumnLayoutScope]; "update:copyHeaderOnDoubleClick": [value: boolean];
  "update:copySeparator": [value: CopySeparator]; "update:maxActiveSessions": [value: number];
  "update:autoCommit": [value: boolean];
  "update:headerSortingEnabled": [value: boolean]; "update:headerFilteringEnabled": [value: boolean];
  "update:showColumnRemarksInHeader": [value: boolean]; "update:scrollOptimizationEnabled": [value: boolean];
  "update:showSelectedColumnRemarks": [value: boolean];
  "update:idleTimeoutMinutes": [value: number]; "update:transactionDisconnectRollbackMinutes": [value: number];
  "update:completionCandidateLimit": [value: number];
  clearCompletionCaches: [] }>();
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
.number-with-unit { display: flex; align-items: center; gap: 6px; color: var(--db-muted); font-size: 11px; }
.separator-options :deep(.el-radio-button__inner) { min-width: 38px; padding-inline: 8px; }
.completion-cache-control { display: flex; min-width: 0; align-items: center; justify-content: flex-end; gap: 5px; }
.completion-cache-control > span { max-width: 178px; overflow: hidden; color: var(--db-muted); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.completion-cache-control :deep(.el-button) { margin-left: 0; padding-inline: 5px; }
</style>
