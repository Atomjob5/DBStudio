<template>
  <el-footer class="status-bar" :class="{ 'alignment-pending': resultContentOffset <= 0 }"
             height="24px" :style="statusBarStyle" aria-live="polite">
    <div class="status-execution-zone">
      <span class="status-item execution-status">
        <Loading v-if="busy" class="is-loading" />
        <span>{{ primaryStatusText }}</span>
      </span>
    </div>

    <div class="status-result-zone">
      <div v-if="!planActive" class="status-result-actions" role="toolbar" aria-label="结果操作工具栏">
        <span class="auto-refresh-trigger" @contextmenu.prevent.stop="autoRefreshMenuVisible = true">
          <IconTooltip :content="autoRefreshTooltip" placement="top">
            <el-button text :icon="RefreshRight" aria-label="切换定时刷新"
                       :aria-pressed="autoRefreshEnabled" :disabled="!canAutoRefresh"
                       :class="{ 'is-auto-refresh-enabled': autoRefreshEnabled }"
                       @click.stop="$emit('toggle-auto-refresh')" />
          </IconTooltip>
          <div v-if="autoRefreshMenuVisible" class="auto-refresh-menu" role="menu"
               aria-label="定时刷新周期" @click.stop @contextmenu.prevent>
            <button v-for="seconds in AUTO_REFRESH_PRESETS" :key="seconds" type="button" role="menuitem"
                    @click="selectAutoRefreshInterval(seconds)">
              <el-icon><Check v-if="autoRefreshIntervalSeconds === seconds" /></el-icon>
              <span>{{ seconds }} 秒</span>
            </button>
            <button class="custom-interval" type="button" role="menuitem" @click="openCustomAutoRefreshInterval">
              <el-icon><Check v-if="!AUTO_REFRESH_PRESETS.includes(autoRefreshIntervalSeconds)" /></el-icon>
              <span>自定义…</span>
            </button>
          </div>
        </span>
        <IconTooltip :content="nextPageTooltip" placement="top">
          <el-button text :icon="ArrowDown" aria-label="下一页数据" :disabled="!canLoadMore"
                     :loading="loadingMode === 'next'" @click="$emit('load-next')" />
        </IconTooltip>
        <IconTooltip :content="allRowsTooltip" placement="top">
          <el-button text :icon="DArrowRight" style="rotate: 90deg;" aria-label="获取全部数据"
                     :disabled="!canLoadMore" :loading="loadingMode === 'all'" @click="$emit('load-all')" />
        </IconTooltip>
      </div>
      <button v-if="visibleColumnRemarks" class="selected-column-remarks" type="button"
              aria-label="查看完整字段备注" @dblclick="remarksDialog = true" @keydown="remarksKeydown">
        {{ selectedColumn?.remarks }}
      </button>
    </div>

    <div class="status-system-zone">
      <el-popover v-if="sortedSystemItems.length" placement="top-end" trigger="hover" :width="360"
                  popper-class="status-system-popover"
                  @show="systemPopoverVisible = true" @hide="systemPopoverVisible = false">
        <template #reference>
          <button class="system-status-summary" type="button" aria-label="系统状态和通知"
                  @focus="systemPopoverVisible = true" @blur="systemPopoverVisible = false">
            <component :is="systemIcon(currentSystemItem)"
                       :class="{ 'is-loading': currentSystemItem?.tone === 'running' }" />
            <span>{{ currentSystemItem?.message }}</span>
            <small v-if="sortedSystemItems.length > 1">
              {{ currentSystemItemIndex + 1 }}/{{ sortedSystemItems.length }}
            </small>
          </button>
        </template>
        <div class="status-system-list" aria-label="所有系统状态和通知">
          <div v-for="item in sortedSystemItems" :key="item.id"
               class="status-system-row" :class="item.tone">
            <component :is="systemIcon(item)" :class="{ 'is-loading': item.tone === 'running' }" />
            <div><strong>{{ item.label }}</strong><span>{{ item.message }}</span></div>
            <el-button v-if="item.dismissible" text size="small" aria-label="关闭系统消息"
                       @click="$emit('dismiss-task', item.id)">关闭</el-button>
          </div>
        </div>
      </el-popover>
    </div>

    <el-dialog v-model="remarksDialog" title="字段备注" width="520px" append-to-body>
      <dl v-if="selectedColumn" class="column-remarks-detail">
        <dt>字段</dt><dd>{{ selectedColumn.label }}</dd>
        <dt>完整路径</dt><dd>{{ columnPath }}</dd>
        <dt>类型</dt><dd>{{ selectedColumn.typeName || "—" }}</dd>
        <dt>备注</dt><dd class="full-remarks">{{ selectedColumn.remarks }}</dd>
      </dl>
      <template #footer>
        <el-button @click="remarksDialog = false">关闭</el-button>
        <el-button type="primary" :icon="CopyDocument" @click="copyRemarks">复制备注</el-button>
      </template>
    </el-dialog>
  </el-footer>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  ArrowDown,
  Check,
  CircleCheckFilled,
  CopyDocument,
  DArrowRight,
  InfoFilled,
  Loading,
  RefreshRight,
  WarningFilled
} from "@element-plus/icons-vue";
import { writeClipboardText } from "../clipboard";
import IconTooltip from "./IconTooltip.vue";
import type { SelectedResultColumn, StatusBarSystemItem } from "../types";

const props = defineProps<{
  planActive?: boolean;
  executionText: string;
  busy: boolean;
  selectedRowCount: number;
  selectedStatusText?: string;
  resultContentOffset: number;
  selectedColumn?: SelectedResultColumn;
  showSelectedColumnRemarks: boolean;
  systemItems: StatusBarSystemItem[];
  canLoadMore: boolean;
  canAutoRefresh: boolean;
  autoRefreshEnabled: boolean;
  autoRefreshIntervalSeconds: number;
  autoRefreshTooltip: string;
  loadingMode?: "next" | "all";
  nextPageTooltip: string;
  allRowsTooltip: string;
}>();
const emit = defineEmits<{
  "load-next": [];
  "load-all": [];
  "dismiss-task": [id: string];
  "toggle-auto-refresh": [];
  "update-auto-refresh-interval": [seconds: number];
}>();

const remarksDialog = ref(false);
const autoRefreshMenuVisible = ref(false);
const systemPopoverVisible = ref(false);
const currentSystemItemIndex = ref(0);
const MIN_EXECUTION_ZONE_WIDTH = 240;
const reducedMotion = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
let rotationTimer: number | undefined;
const AUTO_REFRESH_PRESETS = [5, 10, 30, 60];

const statusBarStyle = computed(() => ({
  "--status-result-offset": `${Math.max(MIN_EXECUTION_ZONE_WIDTH, props.resultContentOffset)}px`
}));
const primaryStatusText = computed(() => props.busy
  ? props.executionText
  : props.selectedStatusText || (props.selectedRowCount > 0 ? `已选中 ${props.selectedRowCount} 行` : props.executionText));
const visibleColumnRemarks = computed(() => props.showSelectedColumnRemarks && Boolean(props.selectedColumn?.remarks));
const columnPath = computed(() => props.selectedColumn
  ? [props.selectedColumn.catalog, props.selectedColumn.schema, props.selectedColumn.table, props.selectedColumn.name]
    .filter(Boolean).join(".") || props.selectedColumn.name
  : "");
const sortedSystemItems = computed(() => [...props.systemItems].sort((left, right) => {
  const rank = { error: 0, warning: 1, running: 2, neutral: 3, success: 4 };
  return rank[left.tone] - rank[right.tone] || right.updatedAt - left.updatedAt;
}));
const currentSystemItem = computed(() =>
  sortedSystemItems.value[Math.min(currentSystemItemIndex.value, sortedSystemItems.value.length - 1)]);

watch([
  () => sortedSystemItems.value.map((item) => `${item.id}:${item.updatedAt}:${item.message}`).join("|"),
  systemPopoverVisible
], scheduleRotation, { immediate: true });
watch(visibleColumnRemarks, (visible) => { if (!visible) remarksDialog.value = false; });
onBeforeUnmount(stopRotation);
onMounted(() => window.addEventListener("click", closeAutoRefreshMenu));
onBeforeUnmount(() => window.removeEventListener("click", closeAutoRefreshMenu));

function scheduleRotation(): void {
  stopRotation();
  if (currentSystemItemIndex.value >= sortedSystemItems.value.length) currentSystemItemIndex.value = 0;
  if (reducedMotion || systemPopoverVisible.value || sortedSystemItems.value.length < 2) return;
  rotationTimer = window.setInterval(() => {
    currentSystemItemIndex.value = (currentSystemItemIndex.value + 1) % sortedSystemItems.value.length;
  }, 3_000);
}

function stopRotation(): void {
  if (rotationTimer !== undefined) window.clearInterval(rotationTimer);
  rotationTimer = undefined;
}

function systemIcon(item?: StatusBarSystemItem) {
  if (item?.tone === "error" || item?.tone === "warning") return WarningFilled;
  if (item?.tone === "running") return Loading;
  if (item?.tone === "success") return CircleCheckFilled;
  return InfoFilled;
}

function remarksKeydown(event: KeyboardEvent): void {
  if (event.key !== "Enter" && event.key !== " ") return;
  event.preventDefault();
  remarksDialog.value = true;
}

async function copyRemarks(): Promise<void> {
  if (!props.selectedColumn?.remarks) return;
  try {
    await writeClipboardText(props.selectedColumn.remarks);
    ElMessage.success("已复制字段备注");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "复制失败");
  }
}

function closeAutoRefreshMenu(): void {
  autoRefreshMenuVisible.value = false;
}

function selectAutoRefreshInterval(seconds: number): void {
  closeAutoRefreshMenu();
  emit("update-auto-refresh-interval", seconds);
}

async function openCustomAutoRefreshInterval(): Promise<void> {
  closeAutoRefreshMenu();
  try {
    const response = await ElMessageBox.prompt("请输入 1 到 3600 之间的整数秒数", "自定义定时刷新周期", {
      inputValue: String(props.autoRefreshIntervalSeconds),
      inputValidator: (value) => {
        const seconds = Number(value);
        return /^\d+$/.test(value) && Number.isInteger(seconds) && seconds >= 1 && seconds <= 3600
          ? true : "刷新周期必须是 1 到 3600 之间的整数";
      },
      confirmButtonText: "保存",
      cancelButtonText: "取消"
    });
    emit("update-auto-refresh-interval", Number(response.value));
  } catch {
    // The prompt was cancelled.
  }
}
</script>

<style scoped>
.status-bar {
  display: grid;
  min-width: 0;
  grid-template-columns: minmax(0, var(--status-result-offset)) minmax(0, 1fr) auto;
  align-items: center;
  padding: 0;
  color: var(--db-text-secondary);
  font-size: 11px;
  background: transparent;
}
.status-bar.alignment-pending {
  grid-template-columns: minmax(0, 1fr) 0 auto;
}
.status-bar.alignment-pending .status-result-zone {
  visibility: hidden;
  overflow: hidden;
  padding: 0;
  border-left: 0;
}
.status-execution-zone,
.status-result-zone,
.status-system-zone {
  min-width: 0;
  height: 100%;
  display: flex;
  align-items: center;
}
.status-execution-zone {
  gap: 8px;
  overflow: hidden;
  padding: 0 10px;
}
.status-result-zone {
  gap: 8px;
  padding: 0 8px;
  border-left: 1px solid var(--db-border-soft);
}
.auto-refresh-trigger {
  position: relative;
  display: inline-flex;
}
.is-auto-refresh-enabled {
  color: var(--db-accent);
  background: var(--db-accent-soft);
}
.auto-refresh-menu {
  position: absolute;
  bottom: calc(100% + 6px);
  left: 0;
  z-index: 3000;
  display: flex;
  min-width: 112px;
  padding: 5px;
  flex-direction: column;
  border: 1px solid var(--db-border);
  border-radius: 8px;
  background: var(--db-surface-raised);
  box-shadow: var(--el-box-shadow-light);
}
.auto-refresh-menu button {
  display: grid;
  min-height: 30px;
  padding: 0 10px 0 6px;
  grid-template-columns: 18px 1fr;
  align-items: center;
  border: 0;
  border-radius: 7px;
  color: var(--db-text);
  background: transparent;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.auto-refresh-menu button:hover,
.auto-refresh-menu button:focus-visible {
  color: var(--db-text);
  background: var(--db-accent-soft);
  outline: none;
}
.auto-refresh-menu .custom-interval {
  margin-top: 4px;
  border-top: 1px solid var(--db-border-soft);
  border-radius: 0 0 7px 7px;
}
.status-system-zone {
  max-width: min(38vw, 480px);
  padding: 0 10px 0 8px;
  border-left: 1px solid var(--db-border-soft);
}
.status-item {
  display: inline-flex;
  min-width: 0;
  flex: none;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}
.execution-status {
  flex: 1;
  overflow: hidden;
}
.execution-status span {
  overflow: hidden;
  text-overflow: ellipsis;
}
.status-item svg,
.system-status-summary svg {
  width: 12px;
  height: 12px;
  flex: none;
}
.is-loading { animation: rotating 1.4s linear infinite; }
.selected-column-remarks,
.system-status-summary {
  min-width: 0;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
}
.selected-column-remarks {
  flex: 1;
  overflow: hidden;
  padding: 1px 0 1px 8px;
  border-left: 1px solid var(--db-border-soft);
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: default;
}
.selected-column-remarks:focus-visible,
.system-status-summary:focus-visible {
  outline: 1px solid var(--db-accent);
  outline-offset: 2px;
}
.system-status-summary {
  display: inline-flex;
  min-width: 0;
  max-width: 100%;
  align-items: center;
  gap: 6px;
  cursor: default;
}
.system-status-summary span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.system-status-summary small { color: var(--db-muted); }
.status-result-actions {
  height: 18px;
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 2px;
}
.status-result-actions :deep(.el-button) {
  width: 20px;
  height: 20px;
  min-height: 20px;
  margin: 0;
  padding: 0;
  border-radius: 5px;
}
.status-result-actions :deep(.el-button .el-icon) { width: 12px; height: 12px; }
.result-action-divider {
  width: 1px;
  height: 12px;
  margin: 0 3px;
  background: var(--db-border-soft);
}
.column-remarks-detail {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  margin: 0;
  gap: 10px 14px;
}
.column-remarks-detail dt { color: var(--db-muted); }
.column-remarks-detail dd { min-width: 0; margin: 0; overflow-wrap: anywhere; }
.column-remarks-detail .full-remarks { max-height: 260px; overflow: auto; white-space: pre-wrap; }
</style>

<style>
.status-system-list {
  display: flex;
  max-height: 300px;
  flex-direction: column;
  gap: 8px;
  overflow: auto;
}
.status-system-row {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  gap: 8px;
}
.status-system-row > svg { width: 13px; height: 13px; flex: none; margin-top: 2px; }
.status-system-row > div { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 2px; }
.status-system-row strong { font-size: 11px; }
.status-system-row span { color: var(--db-text-secondary); font-size: 11px; overflow-wrap: anywhere; }
.status-system-row.error > svg,
.status-system-row.warning > svg { color: var(--db-warning); }
.status-system-row.success > svg { color: var(--db-success); }
.status-system-row.running > svg { color: var(--db-accent); }
.status-system-row.neutral > svg { color: var(--db-muted); }
.status-system-row .is-loading { animation: rotating 1.4s linear infinite; }
</style>
