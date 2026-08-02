<template>
  <el-drawer :model-value="modelValue" title="任务管理器" size="760px" class="jdbc-task-manager-drawer"
             @open="load" @update:model-value="$emit('update:modelValue', $event)">
    <div class="jdbc-manager-content">
      <div class="jdbc-manager-toolbar">
        <div class="jdbc-summary" aria-label="JDBC连接汇总">
          <strong>{{ filteredConnections.length }} 个连接</strong>
          <span v-if="busyCount">{{ busyCount }} 个占用</span>
          <span v-if="abnormalCount" class="is-warning">{{ abnormalCount }} 个异常</span>
        </div>
        <div class="jdbc-manager-filters">
          <el-input v-model="filterText" clearable :prefix-icon="Search" placeholder="筛选连接或编辑器"
                    aria-label="筛选JDBC连接" />
          <el-button text :icon="Refresh" aria-label="刷新JDBC连接" :loading="loading" @click="load()">刷新</el-button>
        </div>
      </div>

      <div v-loading="loading && !connections.length" class="jdbc-connection-list" aria-label="JDBC连接列表">
        <el-empty v-if="!loading && !filteredConnections.length" :image-size="72"
                  description="当前工作区没有查询连接" />
        <article v-for="connection in filteredConnections" :key="connection.connectionId"
                 class="jdbc-connection-row" :class="`state-${connection.state}`">
          <div class="jdbc-connection-main">
            <div class="jdbc-connection-title">
              <strong :title="connection.profileName">{{ connection.profileName }}</strong>
              <el-tag size="small" effect="plain" :type="stateTagType(connection.state)">
                {{ stateLabel(connection.state) }}
              </el-tag>
              <span class="jdbc-provider">{{ providerLabel(connection.providerId) }}</span>
            </div>
            <div class="jdbc-connection-detail">
              <code :title="connection.connectionId">#{{ shortId(connection.connectionId) }}</code>
              <span v-if="connection.editorTitle">{{ connection.editorTitle }}</span>
              <span v-if="connection.transactionDirty" class="transaction-mark">未提交事务</span>
              <span>{{ activityLabel(connection) }}</span>
              <span v-if="connection.lastProbeLatencyMs != null">探活 {{ connection.lastProbeLatencyMs }} ms</span>
            </div>
            <p v-if="connection.message" class="jdbc-connection-message" :title="connection.message">
              {{ connection.message }}
            </p>
          </div>
          <div class="jdbc-connection-actions">
            <el-tooltip :content="probeTooltip(connection)" placement="top">
              <span>
                <el-button size="small" :icon="Connection" :loading="actionId === `probe:${connection.connectionId}`"
                           :disabled="!canProbe(connection) || actionId !== ''"
                           :aria-label="`探活 ${connection.profileName}`" @click="probe(connection)">探活</el-button>
              </span>
            </el-tooltip>
            <el-button size="small" type="danger" plain :icon="SwitchButton"
                       :loading="actionId === `abort:${connection.connectionId}`"
                       :disabled="!canAbort(connection) || actionId !== ''"
                       :aria-label="`强制断开 ${connection.profileName}`" @click="abortConnection(connection)">
              强制断开
            </el-button>
          </div>
        </article>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Connection, Refresh, Search, SwitchButton } from "@element-plus/icons-vue";
import { rpc } from "../bridge/rpc";
import type { JdbcConnectionSnapshot, JdbcConnectionState } from "../types";

const props = defineProps<{ modelValue: boolean }>();
defineEmits<{ "update:modelValue": [value: boolean] }>();

const connections = ref<JdbcConnectionSnapshot[]>([]);
const loading = ref(false);
const filterText = ref("");
const actionId = ref("");
let refreshTimer: number | undefined;
const disposeChanged = rpc.on("jdbc.connections.changed", () => {
  if (!props.modelValue) return;
  if (refreshTimer !== undefined) window.clearTimeout(refreshTimer);
  refreshTimer = window.setTimeout(() => { void load(false); }, 120);
});

const filteredConnections = computed(() => {
  const query = filterText.value.trim().toLocaleLowerCase();
  const values = query ? connections.value.filter((item) =>
    `${item.profileName} ${item.providerId} ${item.editorTitle ?? ""} ${item.connectionId}`
      .toLocaleLowerCase().includes(query)) : connections.value;
  return [...values].sort((left, right) => stateRank(left.state) - stateRank(right.state)
    || right.lastActiveAt - left.lastActiveAt);
});
const busyCount = computed(() => connections.value.filter((item) =>
  item.state === "busy" || item.state === "transaction").length);
const abnormalCount = computed(() => connections.value.filter((item) =>
  ["unresponsive", "error", "disconnected"].includes(item.state)).length);

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
    const response = await rpc.request<{ connections: JdbcConnectionSnapshot[] }>("jdbc.connections.list");
    connections.value = response.connections ?? [];
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    loading.value = false;
  }
}

async function probe(connection: JdbcConnectionSnapshot): Promise<void> {
  actionId.value = `probe:${connection.connectionId}`;
  try {
    const response = await rpc.request<{ connection: JdbcConnectionSnapshot }>("jdbc.connections.probe", {
      connectionId: connection.connectionId, stateVersion: connection.stateVersion
    }, 5_000);
    replace(response.connection);
    ElMessage.success(response.connection.state === "idle" ? "连接探活成功" : response.connection.message || "探活已完成");
  } catch (error) {
    ElMessage.error(errorMessage(error));
    await load(false);
  } finally {
    actionId.value = "";
  }
}

async function abortConnection(connection: JdbcConnectionSnapshot): Promise<void> {
  try {
    await ElMessageBox.confirm(abortMessage(connection), "强制断开 JDBC 连接", {
      type: "error", confirmButtonText: "强制断开", cancelButtonText: "取消",
      confirmButtonClass: "el-button--danger", dangerouslyUseHTMLString: false
    });
  } catch {
    return;
  }
  actionId.value = `abort:${connection.connectionId}`;
  try {
    const response = await rpc.request<{ accepted: boolean; connection: JdbcConnectionSnapshot }>(
      "jdbc.connections.abort", { connectionId: connection.connectionId, stateVersion: connection.stateVersion });
    replace(response.connection);
    ElMessage.warning("已提交强制断开请求");
  } catch (error) {
    ElMessage.error(errorMessage(error));
    await load(false);
  } finally {
    actionId.value = "";
  }
}

function replace(value: JdbcConnectionSnapshot): void {
  const index = connections.value.findIndex((item) => item.connectionId === value.connectionId);
  connections.value = index < 0 ? [...connections.value, value]
    : connections.value.map((item, current) => current === index ? value : item);
}

function canProbe(connection: JdbcConnectionSnapshot): boolean { return connection.state === "idle"; }
function canAbort(connection: JdbcConnectionSnapshot): boolean {
  return connection.state !== "disconnected" && connection.state !== "aborting";
}
function probeTooltip(connection: JdbcConnectionSnapshot): string {
  if (connection.state === "idle") return "调用 JDBC isValid(2) 检查连接";
  if (connection.state === "transaction") return "事务固定连接不能并发探活";
  if (connection.state === "busy") return "执行中的连接不能并发探活";
  return "当前连接状态不能探活";
}
function abortMessage(connection: JdbcConnectionSnapshot): string {
  const name = `${connection.profileName}（#${shortId(connection.connectionId)}）`;
  if (connection.transactionOperationActive) {
    return `${name} 正在提交或回滚。强制断开后数据库端最终结果可能无法确认，请重新查询核实。是否继续？`;
  }
  if (connection.state === "busy" || connection.state === "transaction" || connection.transactionDirty) {
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
function stateRank(state: JdbcConnectionState): number {
  if (state === "busy" || state === "transaction" || state === "probing" || state === "aborting") return 0;
  if (state === "unresponsive" || state === "error") return 1;
  if (state === "idle") return 2;
  return 3;
}
function providerLabel(providerId: string): string {
  if (providerId === "mysql") return "MySQL";
  if (providerId === "oracle") return "Oracle";
  if (providerId === "oceanbase-oracle") return "OceanBase Oracle";
  return providerId;
}
function shortId(value: string): string { return value.replaceAll("-", "").slice(-8); }
function activityLabel(connection: JdbcConnectionSnapshot): string {
  const value = connection.disconnectedAt ?? connection.lastActiveAt;
  return `${connection.disconnectedAt ? "断开" : "活动"}于 ${new Date(value).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" })}`;
}
function errorMessage(error: unknown): string { return error instanceof Error ? error.message : String(error); }
</script>

<style scoped>
.jdbc-manager-content { display: flex; flex-direction: column; gap: 12px; min-height: 0; height: 100%; }
.jdbc-manager-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.jdbc-summary { display: flex; align-items: baseline; gap: 10px; color: var(--db-muted); font-size: 11px; white-space: nowrap; }
.jdbc-summary strong { color: var(--db-text); font-size: 13px; }
.jdbc-summary .is-warning { color: var(--el-color-warning); }
.jdbc-manager-filters { display: flex; align-items: center; gap: 6px; }
.jdbc-manager-filters .el-input { width: 230px; }
.jdbc-connection-list { flex: 1; min-height: 180px; overflow: auto; display: flex; flex-direction: column; gap: 8px; }
.jdbc-connection-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 12px 14px;
  border: 1px solid var(--db-border-soft); border-radius: 12px; background: color-mix(in srgb, var(--db-surface) 86%, transparent); }
.jdbc-connection-row.state-unresponsive, .jdbc-connection-row.state-error { border-color: color-mix(in srgb, var(--el-color-warning) 45%, var(--db-border-soft)); }
.jdbc-connection-row.state-disconnected { opacity: .72; }
.jdbc-connection-main { min-width: 0; display: flex; flex-direction: column; gap: 5px; }
.jdbc-connection-title, .jdbc-connection-detail { min-width: 0; display: flex; align-items: center; gap: 8px; }
.jdbc-connection-title strong { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }
.jdbc-provider, .jdbc-connection-detail { color: var(--db-muted); font-size: 11px; }
.jdbc-connection-detail code { color: var(--db-text-secondary); font-size: 10px; }
.transaction-mark { color: var(--el-color-warning); }
.jdbc-connection-message { margin: 0; max-width: 460px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  color: var(--db-text-secondary); font-size: 11px; }
.jdbc-connection-actions { flex: 0 0 auto; display: flex; align-items: center; gap: 6px; }
@media (max-width: 720px) {
  .jdbc-manager-toolbar, .jdbc-connection-row { align-items: stretch; flex-direction: column; }
  .jdbc-manager-filters .el-input { flex: 1; width: auto; }
  .jdbc-connection-actions { justify-content: flex-end; }
}
</style>
