<template>
  <el-drawer :model-value="modelValue" title="任务管理器" size="760px" class="jdbc-task-manager-drawer"
             @open="load" @update:model-value="$emit('update:modelValue', $event)">
    <div class="jdbc-manager-content">
      <div class="jdbc-manager-toolbar">
        <div class="jdbc-summary" aria-label="JDBC线程汇总">
          <strong>{{ slots.length }} 个线程</strong>
          <span>{{ activeCount }} 个已连接</span>
          <span v-if="overLimitCount" class="is-warning">{{ overLimitCount }} 个超额占用</span>
        </div>
        <div class="jdbc-manager-actions">
          <el-input v-model="filterText" clearable :prefix-icon="Search" placeholder="筛选线程、连接或编辑器"
                    aria-label="筛选JDBC线程" />
          <el-button text :icon="Delete" aria-label="清理过期数据" :disabled="actionId !== ''"
                     @click="cleanup">清理过期数据</el-button>
          <el-button text :icon="Refresh" aria-label="刷新JDBC线程" :loading="loading" @click="load()">刷新</el-button>
        </div>
      </div>

      <div v-loading="loading && !slots.length" class="jdbc-slot-list" aria-label="JDBC连接线程列表">
        <el-empty v-if="!loading && !filteredSlots.length" :image-size="72" description="没有符合条件的连接线程" />
        <article v-for="slot in filteredSlots" :key="slot.slotId" class="jdbc-slot-row"
                 :class="[`state-${slot.state}`, { 'is-expanded': expandedIds.has(slot.slotId) }]">
          <div class="jdbc-slot-header">
            <button class="jdbc-slot-toggle" type="button" :aria-expanded="expandedIds.has(slot.slotId)"
                    :aria-label="`${expandedIds.has(slot.slotId) ? '收起' : '展开'}线程 ${slot.slotNumber}`"
                    @click="toggle(slot)">
              <el-icon class="jdbc-slot-arrow"><ArrowRight /></el-icon>
              <span class="jdbc-slot-number">线程 {{ String(slot.slotNumber).padStart(2, "0") }}</span>
            </button>
            <div class="jdbc-slot-main">
              <div class="jdbc-slot-title">
                <strong :title="slot.profileName || '未建立物理连接'">{{ slot.profileName || "未建立物理连接" }}</strong>
                <el-tag size="small" effect="plain" :type="stateTagType(slot.state)">
                  {{ stateLabel(slot.state) }}
                </el-tag>
                <el-tag v-if="slot.overLimit" size="small" effect="plain" type="warning">超额</el-tag>
                <span v-if="slot.providerId" class="jdbc-provider">{{ providerLabel(slot.providerId) }}</span>
              </div>
              <div class="jdbc-slot-detail">
                <code v-if="slot.connectionId" :title="slot.connectionId">#{{ shortId(slot.connectionId) }}</code>
                <span v-else>尚未创建 JDBC 连接</span>
                <span v-if="databaseLabel(slot)">{{ databaseLabel(slot) }}</span>
                <span v-if="slot.workspaceName">{{ slot.workspaceName }}</span>
                <span v-if="slot.editorTitle" :title="slot.editorId || ''">{{ slot.editorTitle }}</span>
                <span v-if="slot.transactionDirty" class="transaction-mark">未提交事务</span>
              </div>
              <div class="jdbc-slot-detail">
                <span>{{ lastExecutionLabel(slot) }}</span>
                <span v-if="slot.lastProbeLatencyMs != null">探活 {{ slot.lastProbeLatencyMs }} ms</span>
                <span v-if="slot.historyCount">最近 {{ slot.historyCount }} 笔</span>
              </div>
              <p v-if="slot.message" class="jdbc-slot-message" :title="slot.message">{{ slot.message }}</p>
            </div>
            <div class="jdbc-slot-actions" @click.stop>
              <el-tooltip :content="probeTooltip(slot)" placement="top">
                <span>
                  <el-button size="small" :icon="Connection" :loading="actionId === `probe:${slot.slotId}`"
                             :disabled="!canProbe(slot) || actionId !== ''"
                             :aria-label="`探活线程 ${slot.slotNumber}`" @click="probe(slot)">探活</el-button>
                </span>
              </el-tooltip>
              <el-button size="small" type="danger" plain :icon="SwitchButton"
                         :loading="actionId === `abort:${slot.slotId}`"
                         :disabled="!canAbort(slot) || actionId !== ''"
                         :aria-label="`强制断开线程 ${slot.slotNumber}`" @click="abortSlot(slot)">
                强制断开
              </el-button>
            </div>
          </div>

          <div v-if="expandedIds.has(slot.slotId)" class="jdbc-execution-panel">
            <div v-if="historyLoadingIds.has(slot.slotId)" class="jdbc-history-loading">正在加载执行记录…</div>
            <el-empty v-else-if="!(histories[slot.slotId]?.length)" :image-size="48" description="暂无 SQL 执行记录" />
            <div v-else class="jdbc-execution-list" :aria-label="`线程 ${slot.slotNumber} SQL执行记录`">
              <div v-for="execution in histories[slot.slotId]" :key="execution.executionId"
                   class="jdbc-execution-row">
                <div class="jdbc-execution-time">{{ formatTime(execution.startedAt) }}</div>
                <el-tag size="small" effect="plain" :type="executionTagType(execution.status)">
                  {{ executionStateLabel(execution.status) }}
                </el-tag>
                <div class="jdbc-execution-context">
                  <strong :title="execution.editorId">{{ execution.editorTitle }}</strong>
                  <span>{{ execution.workspaceName }} · {{ execution.editorId }}</span>
                  <span>{{ executionDatabaseLabel(execution) }}</span>
                  <span v-if="execution.message" class="is-error" :title="execution.message">{{ execution.message }}</span>
                </div>
                <div class="jdbc-execution-duration">
                  {{ execution.durationMs == null ? "执行中" : `${execution.durationMs} ms` }}
                </div>
                <el-button link type="primary" :aria-label="`查看SQL ${execution.executionId}`"
                           @click="viewSql(slot, execution)">查看 SQL</el-button>
              </div>
            </div>
          </div>
        </article>
      </div>
    </div>

    <el-dialog v-model="sqlDialogVisible" title="完整 SQL" width="720px" append-to-body
               class="jdbc-sql-dialog" @closed="sqlDetail = null">
      <div v-loading="sqlLoading" class="jdbc-sql-content">
        <div v-if="sqlDetail" class="jdbc-sql-meta">
          <span>{{ sqlDetail.workspaceName }}</span><span>{{ sqlDetail.editorTitle }}</span>
          <span>{{ formatTime(sqlDetail.startedAt) }}</span>
        </div>
        <pre v-if="sqlDetail" aria-label="完整SQL内容">{{ sqlDetail.sql }}</pre>
      </div>
    </el-dialog>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { ArrowRight, Connection, Delete, Refresh, Search, SwitchButton } from "@element-plus/icons-vue";
import { rpc } from "../bridge/rpc";
import type { JdbcConnectionSlotSnapshot, JdbcConnectionSlotsResponse, JdbcConnectionState,
  JdbcExecutionDetail, JdbcExecutionState, JdbcExecutionSummary } from "../types";

const props = defineProps<{ modelValue: boolean }>();
defineEmits<{ "update:modelValue": [value: boolean] }>();

const slots = ref<JdbcConnectionSlotSnapshot[]>([]);
const histories = reactive<Record<string, JdbcExecutionSummary[]>>({});
const expandedIds = reactive(new Set<string>());
const historyLoadingIds = reactive(new Set<string>());
const loading = ref(false);
const filterText = ref("");
const actionId = ref("");
const activeCount = ref(0);
const overLimitCount = ref(0);
const sqlDialogVisible = ref(false);
const sqlLoading = ref(false);
const sqlDetail = ref<JdbcExecutionDetail | null>(null);
let refreshTimer: number | undefined;

const disposeChanged = rpc.on("jdbc.connections.changed", () => {
  if (!props.modelValue) return;
  if (refreshTimer !== undefined) window.clearTimeout(refreshTimer);
  refreshTimer = window.setTimeout(() => { void refreshVisible(); }, 120);
});

const filteredSlots = computed(() => {
  const query = filterText.value.trim().toLocaleLowerCase();
  const values = query ? slots.value.filter((slot) =>
    `${slot.slotNumber} ${slot.profileName ?? ""} ${slot.providerId ?? ""} ${slot.workspaceName ?? ""} ${slot.editorTitle ?? ""} ${slot.editorId ?? ""} ${slot.connectionId ?? ""}`
      .toLocaleLowerCase().includes(query)) : slots.value;
  return [...values].sort((left, right) => left.slotNumber - right.slotNumber);
});

watch(() => props.modelValue, (visible) => {
  if (!visible && refreshTimer !== undefined) window.clearTimeout(refreshTimer);
});
onBeforeUnmount(() => {
  disposeChanged();
  if (refreshTimer !== undefined) window.clearTimeout(refreshTimer);
});

async function load(showLoading = true): Promise<void> {
  if (showLoading) loading.value = true;
  try {
    const response = await rpc.request<JdbcConnectionSlotsResponse>("jdbc.connections.list");
    slots.value = response.slots ?? [];
    activeCount.value = response.activeCount ?? 0;
    overLimitCount.value = response.overLimitCount ?? 0;
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    loading.value = false;
  }
}

async function refreshVisible(): Promise<void> {
  await load(false);
  await Promise.all([...expandedIds].map((slotId) => loadExecutions(slotId, false)));
}

async function toggle(slot: JdbcConnectionSlotSnapshot): Promise<void> {
  if (expandedIds.has(slot.slotId)) { expandedIds.delete(slot.slotId); return; }
  expandedIds.add(slot.slotId);
  await loadExecutions(slot.slotId, true);
}

async function loadExecutions(slotId: string, showLoading: boolean): Promise<void> {
  if (showLoading) historyLoadingIds.add(slotId);
  try {
    const response = await rpc.request<{ executions: JdbcExecutionSummary[] }>(
      "jdbc.connections.executions", { slotId });
    histories[slotId] = response.executions ?? [];
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    historyLoadingIds.delete(slotId);
  }
}

async function probe(slot: JdbcConnectionSlotSnapshot): Promise<void> {
  actionId.value = `probe:${slot.slotId}`;
  try {
    const response = await rpc.request<{ slot: JdbcConnectionSlotSnapshot }>("jdbc.connections.probe", {
      slotId: slot.slotId, stateVersion: slot.stateVersion
    }, 5_000);
    replace(response.slot);
    ElMessage.success(response.slot.state === "idle" ? "连接探活成功" : response.slot.message || "探活已完成");
  } catch (error) {
    ElMessage.error(errorMessage(error)); await load(false);
  } finally {
    actionId.value = "";
  }
}

async function abortSlot(slot: JdbcConnectionSlotSnapshot): Promise<void> {
  try {
    await ElMessageBox.confirm(abortMessage(slot), "强制断开 JDBC 连接", {
      type: "error", confirmButtonText: "强制断开", cancelButtonText: "取消",
      confirmButtonClass: "el-button--danger", dangerouslyUseHTMLString: false
    });
  } catch { return; }
  actionId.value = `abort:${slot.slotId}`;
  try {
    const response = await rpc.request<{ accepted: boolean; slot: JdbcConnectionSlotSnapshot }>(
      "jdbc.connections.abort", { slotId: slot.slotId, stateVersion: slot.stateVersion });
    replace(response.slot); ElMessage.warning("已提交强制断开请求");
  } catch (error) {
    ElMessage.error(errorMessage(error)); await load(false);
  } finally {
    actionId.value = "";
  }
}

async function cleanup(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      "将清除所有线程中已完成的 SQL 记录，并重置已断开或异常的空线程。活动连接和运行中的 SQL 不受影响。",
      "清理过期数据", { type: "warning", confirmButtonText: "清理", cancelButtonText: "取消" });
  } catch { return; }
  actionId.value = "cleanup";
  try {
    const response = await rpc.request<{ clearedExecutions: number }>("jdbc.connections.cleanup");
    await refreshVisible(); ElMessage.success(`已清理 ${response.clearedExecutions ?? 0} 条执行记录`);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    actionId.value = "";
  }
}

async function viewSql(slot: JdbcConnectionSlotSnapshot, execution: JdbcExecutionSummary): Promise<void> {
  sqlDialogVisible.value = true; sqlLoading.value = true; sqlDetail.value = null;
  try {
    const response = await rpc.request<{ execution: JdbcExecutionDetail }>("jdbc.connections.execution", {
      slotId: slot.slotId, executionId: execution.executionId
    });
    sqlDetail.value = response.execution;
  } catch (error) {
    sqlDialogVisible.value = false; ElMessage.error(errorMessage(error));
  } finally {
    sqlLoading.value = false;
  }
}

function replace(value: JdbcConnectionSlotSnapshot): void {
  slots.value = slots.value.map((slot) => slot.slotId === value.slotId ? value : slot);
}
function canProbe(slot: JdbcConnectionSlotSnapshot): boolean { return slot.physicalConnected && slot.state === "idle"; }
function canAbort(slot: JdbcConnectionSlotSnapshot): boolean {
  return slot.physicalConnected && slot.state !== "disconnected" && slot.state !== "aborting";
}
function probeTooltip(slot: JdbcConnectionSlotSnapshot): string {
  if (!slot.physicalConnected) return "该线程尚未建立物理连接";
  if (slot.state === "idle") return "调用 JDBC isValid(2) 检查连接";
  if (slot.state === "transaction") return "事务固定连接不能并发探活";
  if (slot.state === "busy") return "执行中的连接不能并发探活";
  return "当前连接状态不能探活";
}
function abortMessage(slot: JdbcConnectionSlotSnapshot): string {
  const name = `线程 ${slot.slotNumber}${slot.profileName ? ` · ${slot.profileName}` : ""}`;
  if (slot.transactionOperationActive) return `${name} 正在提交或回滚。强制断开后数据库端最终结果可能无法确认，请重新查询核实。是否继续？`;
  if (slot.state === "busy" || slot.state === "transaction" || slot.transactionDirty) {
    return `${name} 正在执行 SQL 或持有事务。强制断开会终止当前 SQL，并可能丢失该连接上的全部未提交修改。是否继续？`;
  }
  return `${name} 将被立即从连接池中丢弃，下次执行 SQL 时会重新建立连接。是否继续？`;
}
function stateLabel(state: JdbcConnectionState): string {
  return ({ idle: "空闲", busy: "执行中", transaction: "事务占用", probing: "探活中",
    unresponsive: "无响应", aborting: "正在断开", disconnected: "已断开", error: "异常" })[state];
}
function stateTagType(state: JdbcConnectionState): "success" | "warning" | "danger" | "info" | "primary" {
  if (state === "idle") return "success";
  if (state === "busy" || state === "probing") return "primary";
  if (state === "transaction" || state === "unresponsive" || state === "aborting") return "warning";
  if (state === "error") return "danger";
  return "info";
}
function executionStateLabel(state: JdbcExecutionState): string {
  return ({ running: "执行中", success: "成功", failed: "失败", cancelled: "已取消",
    "connection-aborted": "连接已断开" })[state];
}
function executionTagType(state: JdbcExecutionState): "success" | "warning" | "danger" | "info" | "primary" {
  if (state === "success") return "success";
  if (state === "running") return "primary";
  if (state === "cancelled") return "warning";
  return "danger";
}
function providerLabel(providerId: string): string {
  if (providerId === "mysql") return "MySQL";
  if (providerId === "oracle") return "Oracle";
  if (providerId === "oceanbase-oracle") return "OceanBase Oracle";
  return providerId;
}
function databaseLabel(slot: JdbcConnectionSlotSnapshot): string {
  const values = [slot.databaseName, slot.schemaName].filter(Boolean); return values.join(" / ");
}
function executionDatabaseLabel(execution: JdbcExecutionSummary): string {
  return [providerLabel(execution.providerId), execution.profileName, execution.databaseName, execution.schemaName]
    .filter(Boolean).join(" · ");
}
function lastExecutionLabel(slot: JdbcConnectionSlotSnapshot): string {
  if (slot.lastExecutionAt != null) return `最近执行 ${formatTime(slot.lastExecutionAt)}`;
  if (slot.disconnectedAt != null) return `断开于 ${formatTime(slot.disconnectedAt)}`;
  return slot.physicalConnected ? "尚未执行 SQL" : "等待按需使用";
}
function formatTime(value: number): string {
  return new Date(value).toLocaleString([], { month: "2-digit", day: "2-digit", hour: "2-digit",
    minute: "2-digit", second: "2-digit" });
}
function shortId(value: string): string { return value.replaceAll("-", "").slice(-8); }
function errorMessage(error: unknown): string { return error instanceof Error ? error.message : String(error); }
</script>

<style scoped>
:global(.jdbc-task-manager-drawer .el-drawer__body) { min-height: 0; overflow: hidden; }
.jdbc-manager-content { display: flex; flex-direction: column; gap: 12px; min-height: 0; height: 100%; }
.jdbc-manager-toolbar { flex-shrink: 0; display: flex; align-items: center; justify-content: space-between; gap: 14px; }
.jdbc-summary { display: flex; align-items: baseline; gap: 10px; color: var(--db-muted); font-size: 11px; white-space: nowrap; }
.jdbc-summary strong { color: var(--db-text); font-size: 13px; }
.jdbc-summary .is-warning { color: var(--el-color-warning); }
.jdbc-manager-actions { min-width: 0; display: flex; align-items: center; gap: 4px; }
.jdbc-manager-actions .el-input { width: 218px; }
.jdbc-slot-list { flex: 1; min-height: 0; overflow: auto; display: flex; flex-direction: column; gap: 7px; }
.jdbc-slot-row { flex: 0 0 auto; border: 1px solid var(--db-border-soft); border-radius: 12px;
  background: color-mix(in srgb, var(--db-surface) 88%, transparent); overflow: hidden; }
.jdbc-slot-row.state-unresponsive, .jdbc-slot-row.state-error { border-color: color-mix(in srgb, var(--el-color-warning) 48%, var(--db-border-soft)); }
.jdbc-slot-row.state-disconnected { opacity: .78; }
.jdbc-slot-header { min-height: 76px; display: flex; align-items: center; gap: 10px; padding: 9px 12px; }
.jdbc-slot-toggle { flex: 0 0 auto; display: flex; align-items: center; gap: 5px; padding: 4px 0; border: 0;
  color: var(--db-text-secondary); background: transparent; cursor: pointer; font: inherit; }
.jdbc-slot-arrow { transition: transform var(--duration-fast) var(--ease-smooth-out); }
.is-expanded .jdbc-slot-arrow { transform: rotate(90deg); }
.jdbc-slot-number { min-width: 54px; font-size: 11px; font-weight: 650; }
.jdbc-slot-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.jdbc-slot-title, .jdbc-slot-detail { min-width: 0; display: flex; align-items: center; gap: 7px; }
.jdbc-slot-title strong { max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }
.jdbc-provider, .jdbc-slot-detail { color: var(--db-muted); font-size: 11px; }
.jdbc-slot-detail code { color: var(--db-text-secondary); font-size: 10px; }
.transaction-mark { color: var(--el-color-warning); }
.jdbc-slot-message { margin: 0; max-width: 420px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  color: var(--db-text-secondary); font-size: 11px; }
.jdbc-slot-actions { flex: 0 0 auto; display: flex; align-items: center; gap: 5px; }
.jdbc-execution-panel { border-top: 1px solid var(--db-border-soft); padding: 8px 12px 10px 92px;
  background: color-mix(in srgb, var(--db-surface-muted) 72%, transparent); }
.jdbc-history-loading { padding: 18px; color: var(--db-muted); font-size: 11px; text-align: center; }
.jdbc-execution-list { display: flex; flex-direction: column; }
.jdbc-execution-row { min-height: 54px; display: grid; grid-template-columns: 108px 78px minmax(0, 1fr) 72px 58px;
  align-items: center; gap: 8px; border-bottom: 1px solid var(--db-border-soft); }
.jdbc-execution-row:last-child { border-bottom: 0; }
.jdbc-execution-time, .jdbc-execution-duration { color: var(--db-muted); font-size: 10px; white-space: nowrap; }
.jdbc-execution-context { min-width: 0; display: flex; flex-direction: column; gap: 1px; }
.jdbc-execution-context strong, .jdbc-execution-context span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.jdbc-execution-context strong { font-size: 11px; }
.jdbc-execution-context span { color: var(--db-muted); font-size: 10px; }
.jdbc-execution-context .is-error { color: var(--el-color-danger); }
.jdbc-sql-content { min-height: 180px; }
.jdbc-sql-meta { display: flex; gap: 12px; margin-bottom: 8px; color: var(--db-muted); font-size: 11px; }
.jdbc-sql-content pre { min-height: 180px; max-height: 55vh; margin: 0; padding: 12px; overflow: auto;
  border: 1px solid var(--db-border-soft); border-radius: 10px; color: var(--db-text);
  background: var(--db-surface-muted); font: 12px/1.6 var(--db-mono, monospace); white-space: pre-wrap; word-break: break-word; }
@media (max-width: 720px) {
  .jdbc-manager-toolbar { align-items: stretch; flex-direction: column; }
  .jdbc-manager-actions .el-input { flex: 1; width: auto; }
  .jdbc-slot-header { align-items: flex-start; flex-wrap: wrap; }
  .jdbc-slot-actions { margin-left: 72px; }
  .jdbc-execution-panel { padding-left: 12px; }
  .jdbc-execution-row { grid-template-columns: 96px 72px minmax(0, 1fr); }
}
</style>
