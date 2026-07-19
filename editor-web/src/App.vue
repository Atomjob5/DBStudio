<template>
  <el-container class="app-shell fill" v-loading="app.loading">
    <el-header class="app-toolbar glass-surface" height="48px" aria-label="应用工具栏">
      <el-tooltip :content="activeConnectionTooltip" placement="bottom" :disabled="connectionCascaderOpen" :show-after="600">
        <div class="connection-pill-wrap" :class="{ connected: editors.active?.connectionState === 'active', suspended: editors.active?.connectionState === 'suspended', stale: editors.active?.connection?.stale || editors.active?.connection?.unavailable }">
          <el-cascader ref="connectionCascader" class="connection-pill" :model-value="activeConnectionValue"
                       :options="connections.cascaderOptions" :props="connectionCascaderProps"
                       :show-all-levels="false" filterable clearable :disabled="!editors.active || editors.active.busy"
                       :placeholder="activeConnectionDisplay" aria-label="当前编辑标签的数据库链接"
                       @change="connectionSelectionChanged" @visible-change="connectionCascaderOpen = $event">
            <template #default="{ data }">
              <span>{{ data.menuLabel ?? data.label }}</span>
            </template>
          </el-cascader>
        </div>
      </el-tooltip>

      <div class="toolbar-cluster file-actions" aria-label="文件操作">
        <el-tooltip content="新建查询 · ⌘/Ctrl N" placement="bottom">
          <el-button text :icon="Plus" aria-label="新建查询" @click="newEditor()" />
        </el-tooltip>
        <el-tooltip content="打开 SQL · ⌘/Ctrl O" placement="bottom">
          <el-button text :icon="FolderOpened" aria-label="打开 SQL 文件" @click="openFile" />
        </el-tooltip>
        <el-dropdown trigger="click" @command="openRecent">
          <el-button text :icon="ArrowDown" aria-label="最近打开的 SQL 文件" />
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-if="!settings.recentFiles.length" disabled>没有最近文件</el-dropdown-item>
              <el-dropdown-item v-for="path in settings.recentFiles" :key="path" :command="path">{{ path }}</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-tooltip content="保存 · ⌘/Ctrl S" placement="bottom">
          <el-button text :icon="DocumentChecked" aria-label="保存 SQL" :disabled="!editors.active" @click="saveActive(false)" />
        </el-tooltip>
      </div>

      <el-dropdown class="execute-control" split-button type="primary" :icon="VideoPlay" :disabled="!canExecute"
                   @click="executeActive('current')" @command="executeCommand">
        执行
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="current"><span>执行当前语句</span><kbd>⌘↵</kbd></el-dropdown-item>
            <el-dropdown-item command="script"><span>执行整个脚本</span><kbd>F5</kbd></el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>

      <div class="toolbar-cluster query-actions" aria-label="查询控制">
        <el-tooltip content="取消执行 · Esc" placement="bottom">
          <el-button text :icon="Close" aria-label="取消执行" :disabled="!editors.active?.busy" @click="cancelActive" />
        </el-tooltip>
        <el-tooltip content="提交事务 · ⌘/Ctrl Alt C" placement="bottom">
          <el-button text :icon="Select" aria-label="提交事务" :disabled="!editors.active?.connection" @click="commitActive" />
        </el-tooltip>
        <el-tooltip content="回滚事务 · ⌘/Ctrl Alt R" placement="bottom">
          <el-button text :icon="RefreshLeft" aria-label="回滚事务" :disabled="!editors.active?.connection" @click="rollbackActive" />
        </el-tooltip>
      </div>

      <span class="toolbar-spacer" />
      <el-dropdown @command="dataCommand">
        <el-button text circle :icon="MoreFilled" aria-label="更多操作" />
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="import" :icon="Upload" :disabled="!activeConnected">导入 CSV / TSV</el-dropdown-item>
            <el-dropdown-item command="history" :icon="Clock">查询历史</el-dropdown-item>
            <el-dropdown-item divided command="settings" :icon="Setting">设置</el-dropdown-item>
            <el-dropdown-item divided command="exit" :icon="SwitchButton">退出 DBStudio</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-tooltip :content="app.theme === 'dark' ? '切换亮色主题' : '切换深色主题'">
        <el-button text circle :icon="app.theme === 'dark' ? Sunny : Moon" aria-label="切换界面主题"
                   @click="updateTheme(app.theme === 'dark' ? 'light' : 'dark')" />
      </el-tooltip>
    </el-header>

    <el-main class="workspace">
      <nav class="activity-bar" aria-label="工作区工具导航">
        <el-tooltip content="数据库对象" placement="right"><el-button text :icon="Coin" aria-label="数据库对象"
          :class="{ active: activeTool === 'objects' && panelVisible }" :aria-pressed="activeTool === 'objects' && panelVisible" @click="selectTool('objects')" /></el-tooltip>
        <el-tooltip content="连接管理" placement="right"><el-button text :icon="Connection" aria-label="连接管理"
          :class="{ active: activeTool === 'connections' && panelVisible }" :aria-pressed="activeTool === 'connections' && panelVisible" @click="selectTool('connections')" /></el-tooltip>
      </nav>
      <el-splitter class="workbench" lazy>
        <el-splitter-panel v-if="panelOpen" v-model:size="leftWidth" :min="210" :max="420" collapsible>
          <ObjectExplorer v-if="activeTool === 'objects' && editors.active?.connection" ref="objectExplorer"
                          :editor-id="editors.active.id" :connection-key="activeConnectionKey"
                          :connection-name="editors.active.connection.name" :completion-loading="activeCompletionLoading"
                          @open="openObject" @definition="openDefinition" @refresh="refreshCompletionFromObjectExplorer" />
          <el-empty v-else-if="activeTool === 'objects'" class="workspace-empty" description="当前编辑标签尚未选择数据库链接">
            <template #image><el-icon><Coin /></el-icon></template>
            <el-button type="primary" round @click="openConnectionManager">打开连接管理</el-button>
          </el-empty>
          <ConnectionManagerPanel v-else :systems="connections.systems" :environments="connections.environments"
                                  :profiles="connections.profiles" @changed="refreshConnectionCatalog"
                                  @create-profile="openCreateProfile" @edit-profile="openEditProfile" />
        </el-splitter-panel>
        <el-splitter-panel :min="500">
          <el-splitter layout="vertical" lazy>
            <el-splitter-panel v-model:size="editorHeight" :min="220">
              <section class="editor-area fill">
                <el-tabs v-if="editors.tabs.length" v-model="editors.activeId" closable class="editor-tabs"
                         @tab-remove="(name) => closeTab(String(name))">
                  <el-tab-pane v-for="tab in editors.tabs" :key="tab.id" :name="tab.id">
                    <template #label>
                      <span class="editor-tab-label"><i v-if="tab.dirty" class="dirty-dot" aria-label="未保存" />{{ tab.title }}</span>
                    </template>
                  </el-tab-pane>
                </el-tabs>
                <MonacoEditor v-if="editors.active" ref="monacoEditor" class="editor-widget" :model-key="editors.active.id"
                              :initial-value="editors.active.content" :theme="app.theme" :suggestions="metadata.suggestions"
                              @dirty="markActiveDirty" @execute="executeFromEditor" @format="formatActive" />
                <el-empty v-else class="workspace-empty" description="新建 SQL 标签开始查询">
                  <template #image><el-icon><Document /></el-icon></template>
                  <el-button round @click="newEditor()">新建查询</el-button>
                </el-empty>
              </section>
            </el-splitter-panel>
            <el-splitter-panel :min="150" collapsible>
              <ResultPanel v-model:active-result-index="activeResultIndex" :execution="activeExecution"
                           @export-loaded="exportLoaded" @export-full="exportFull" />
            </el-splitter-panel>
          </el-splitter>
        </el-splitter-panel>
      </el-splitter>
    </el-main>

    <el-footer class="status-bar" height="24px" aria-live="polite">
      <span class="status-item"><i class="status-dot" :class="activeConnectionStatusClass" />{{ app.status }}</span>
      <span v-if="completionStatus" class="status-item completion-status" :class="completionStatus.state"
            :title="completionStatus.error || completionStatusText">
        <Loading v-if="completionStatus.state === 'loading'" class="is-loading" />
        <WarningFilled v-else-if="completionStatus.state === 'error'" />
        <CircleCheckFilled v-else />
        {{ completionStatusText }}
      </span>
      <div class="status-result-actions" role="toolbar" aria-label="结果数据加载工具栏">
        <el-tooltip :content="nextPageTooltip" placement="top">
          <el-button text :icon="ArrowDown" aria-label="下一页数据" :disabled="!canLoadMore"
                     :loading="activeResultLoading?.mode === 'next'" @click="loadNextResultPage" />
        </el-tooltip>
        <el-tooltip :content="allRowsTooltip" placement="top">
          <el-button text :icon="DArrowRight" style="rotate: 90deg;" aria-label="获取全部数据" :disabled="!canLoadMore"
                     :loading="activeResultLoading?.mode === 'all'" @click="loadAllResultRows" />
        </el-tooltip>
      </div>
      <span class="status-spacer" />
      <span v-if="editors.active?.transactionDirty" class="status-item transaction-warning"><WarningFilled />未提交事务</span>
      <span class="status-item"><i class="status-dot" :class="editors.active?.busy ? 'busy' : 'neutral'" />
        {{ editors.active?.busy ? "正在执行" : activeConnected ? editors.active?.connectionState === "suspended" ? "链接已暂停" : "自动提交关闭" : "未选择链接" }}
      </span>
    </el-footer>
  </el-container>

  <ConnectionDialog v-model="connectionDialog" :providers="connections.providers" :systems="connections.systems"
                    :environments="connections.environments" :profile="editingProfile" :initial-environment-id="profileEnvironmentId"
                    @saved="profileSaved" />
  <HistoryDrawer v-model="historyDrawer" @open="openHistory" />
  <SettingsDrawer v-model="settingsDrawer" :theme="app.themePreference" :resolved-theme="app.theme" :max-rows="settings.maxResultRows"
                  :stream-batch-rows="settings.streamBatchRows" :column-layout-scope="settings.columnLayoutScope"
                  :copy-header-on-double-click="settings.copyHeaderOnDoubleClick" :copy-separator="settings.copySeparator"
                  :max-active-sessions="settings.maxActiveSessions" :idle-timeout-minutes="settings.idleTimeoutMinutes"
                  @update:theme="updateTheme" @update:max-rows="updateMaxRows"
                  @update:stream-batch-rows="updateStreamBatchRows" @update:column-layout-scope="updateColumnLayoutScope"
                  @update:copy-header-on-double-click="updateCopyHeaderOnDoubleClick"
                  @update:copy-separator="updateCopySeparator" @update:max-active-sessions="updateMaxActiveSessions"
                  @update:idle-timeout-minutes="updateIdleTimeoutMinutes" />
  <CsvImportDialog v-model="csvDialog" :editor-id="editors.active?.id" @imported="objectExplorer?.resetTree()" />
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage, ElMessageBox, ElNotification } from "element-plus";
import type { CascaderProps } from "element-plus";
import {
  ArrowDown,
  ArrowRightBold,
  Bottom,
  CircleCheckFilled,
  Clock,
  Close,
  Coin,
  Connection,
  DArrowRight,
  Document,
  DocumentChecked,
  FolderOpened,
  Loading,
  Moon,
  MoreFilled,
  Plus,
  RefreshLeft,
  Select,
  Setting,
  Sunny,
  SwitchButton,
  Upload,
  VideoPlay,
  WarningFilled
} from "@element-plus/icons-vue";
import { rpc } from "./bridge/rpc";
import ConnectionDialog from "./components/ConnectionDialog.vue";
import ConnectionManagerPanel from "./components/ConnectionManagerPanel.vue";
import CsvImportDialog from "./components/CsvImportDialog.vue";
import HistoryDrawer from "./components/HistoryDrawer.vue";
import MonacoEditor from "./components/MonacoEditor.vue";
import ObjectExplorer from "./components/ObjectExplorer.vue";
import ResultPanel from "./components/ResultPanel.vue";
import SettingsDrawer from "./components/SettingsDrawer.vue";
import { useAppStore } from "./stores/app";
import { useConnectionStore } from "./stores/connection";
import { useEditorStore } from "./stores/editor";
import { useMetadataStore } from "./stores/metadata";
import { useQueryStore } from "./stores/query";
import { useSettingsStore } from "./stores/settings";
import type { ColumnLayoutScope } from "./columnLayout";
import type { CopySeparator } from "./resultCopy";
import { applyDocumentTheme } from "./theme";
import { openRecentSql, openSqlFile, recentSqlFiles, saveSqlFile } from "./files/browserFiles";
import type { BootstrapResponse, CompletionCache, CompletionProgress, CompletionSnapshot, ConnectionCatalog, EditorConnectionBinding, EditorConnectionState, EditorTab, HistoryEntry, MetadataNode, QueryResult, SavedProfile, ThemePreference } from "./types";

const app = useAppStore(); const connections = useConnectionStore(); const metadata = useMetadataStore();
const editors = useEditorStore(); const queries = useQueryStore(); const settings = useSettingsStore();
const connectionDialog = ref(false); const historyDrawer = ref(false); const settingsDrawer = ref(false); const csvDialog = ref(false);
const leftWidth = ref(248); const lastLeftWidth = ref(248); const editorHeight = ref("62%");
const activeTool = ref<"objects" | "connections">("connections"); const panelOpen = ref(true);
const editingProfile = ref<SavedProfile>(); const profileEnvironmentId = ref("");
const connectionCascader = ref();
const connectionCascaderOpen = ref(false);
const objectExplorer = ref<InstanceType<typeof ObjectExplorer>>();
const monacoEditor = ref<{ getValue(key?: string): string; setValue(value: string, key?: string): void }>();
const recentHandles = new Map<string, FileSystemFileHandle>();
const activeExecution = computed(() => editors.activeId ? queries.executions[editors.activeId] : undefined);
const activeResultIndex = ref(0);
const activeResult = computed(() => activeExecution.value?.results.find((result) => result.resultIndex === activeResultIndex.value)
  ?? activeExecution.value?.results[0]);
const resultLoading = ref<{ editorId: string; resultIndex: number; mode: "next" | "all" }>();
const activeResultLoading = computed(() => {
  const loading = resultLoading.value;
  return loading && loading.editorId === editors.activeId
    ? { resultIndex: loading.resultIndex, mode: loading.mode }
    : undefined;
});
const canLoadMore = computed(() => Boolean(activeResult.value?.columns.length && activeResult.value.complete
  && activeResult.value.truncated && !activeExecution.value?.busy && !resultLoading.value));
const nextPageTooltip = computed(() => resultLoadTooltip("next"));
const allRowsTooltip = computed(() => resultLoadTooltip("all"));
const activeConnected = computed(() => Boolean(editors.active?.connection));
const activeConnectionKey = computed(() => editors.active?.connection ? `${editors.active.connection.id}@${editors.active.connection.revision}` : "unbound");
const activeCompletionContext = computed(() => connections.completionContext(editors.active?.connection));
const activeCompletionKey = computed(() => activeCompletionContext.value?.key ?? "unbound");
const activeCompletionLoading = computed(() => metadata.completionFor(activeCompletionKey.value)?.state === "loading");
const completionStatus = computed(() => metadata.statusFor(activeCompletionKey.value));
const completionStatusText = computed(() => completionMessage(completionStatus.value));
const activeConnectionValue = computed(() => editors.active?.connection ? `${editors.active.connection.id}@${editors.active.connection.revision}` : undefined);
const activeConnectionPath = computed(() => connections.pathFor(editors.active?.connection));
const activeConnectionDisplay = computed(() => {
  const connection = editors.active?.connection;
  if (!connection) return "选择数据库链接";
  const environment = connections.environments.find((item) => item.id === connection.environmentId);
  return [environment?.name, connection.name].filter(Boolean).join(" / ");
});
const activeConnectionTooltip = computed(() => `${activeConnectionPath.value}${editors.active?.connection?.stale
  ? " · 配置已更新，重新选择后生效" : editors.active?.connection?.unavailable ? " · 配置已删除，当前会话仍可继续使用" : ""}`);
const activeConnectionStatusClass = computed(() => !activeConnected.value ? "offline" : editors.active?.connectionState === "active" ? "online" : "neutral");
const connectionCascaderProps: CascaderProps = { emitPath: false };
const canExecute = computed(() => Boolean(editors.active?.connection && !editors.active.busy));
const panelVisible = computed(() => panelOpen.value && numericPanelWidth(leftWidth.value) > 0);
const disposers: Array<() => void> = [];
const colorSchemeQuery = window.matchMedia?.("(prefers-color-scheme: dark)");
let layoutSaveTimer: number | undefined;
const completionNoticeTimers = new Map<string, number>();

watch(leftWidth, (value) => {
  const width = numericPanelWidth(value);
  if (width > 0) lastLeftWidth.value = width;
});

onMounted(async () => {
  app.setSystemTheme(colorSchemeQuery?.matches ? "dark" : "light");
  colorSchemeQuery?.addEventListener?.("change", systemThemeChanged);
  installEventHandlers();
  app.loading = true;
  try {
    const data = await rpc.request<BootstrapResponse>("app.bootstrap");
    connections.initialize(data.providers, data.profiles, data.systems ?? [], data.environments ?? []);
    settings.initialize(data.settings, data.recentFiles);
    for (const recent of await recentSqlFiles().catch(() => [])) {
      recentHandles.set(recent.name, recent.handle);
      if (!settings.recentFiles.includes(recent.name)) settings.recentFiles.push(recent.name);
    }
    app.applyBootstrap(data);
    leftWidth.value = Number(data.settings["layout.leftWidth"] ?? 248);
    editorHeight.value = data.settings["layout.editorHeight"] ?? "62%";
    await newEditor();
    activeTool.value = editors.active?.connection ? "objects" : "connections";
  } catch (error) {
    app.status = "启动失败";
    ElNotification.error({ title: "DBStudio 启动失败", message: message(error), duration: 0 });
  } finally { app.loading = false; }
  window.addEventListener("keydown", handleShortcut);
});
onBeforeUnmount(() => {
  disposers.forEach((dispose) => dispose());
  colorSchemeQuery?.removeEventListener?.("change", systemThemeChanged);
  window.removeEventListener("keydown", handleShortcut);
  if (layoutSaveTimer !== undefined) window.clearTimeout(layoutSaveTimer);
  completionNoticeTimers.forEach((timer) => window.clearTimeout(timer));
});

watch(() => app.theme, (theme) => applyDocumentTheme(theme), { immediate: true });
watch(() => activeExecution.value?.executionId, () => {
  activeResultIndex.value = activeExecution.value?.results[0]?.resultIndex ?? 0;
});
watch(() => [editors.activeId, activeConnectionKey.value, activeCompletionKey.value] as const, () => {
  metadata.activate(activeConnectionKey.value, activeCompletionKey.value);
});

function systemThemeChanged(event: MediaQueryListEvent): void {
  app.setSystemTheme(event.matches ? "dark" : "light");
}

watch([leftWidth, editorHeight], () => {
  if (!app.initialized) return;
  if (layoutSaveTimer !== undefined) window.clearTimeout(layoutSaveTimer);
  layoutSaveTimer = window.setTimeout(() => {
    void Promise.all([
      rpc.request("settings.update", { key: "layout.leftWidth", value: String(leftWidth.value) }),
      rpc.request("settings.update", { key: "layout.editorHeight", value: String(editorHeight.value) })
    ]).catch((error) => ElMessage.error(message(error)));
  }, 400);
});

function installEventHandlers(): void {
  disposers.push(rpc.on("query.started", (raw) => {
    const data = raw as { editorId: string; executionId: string };
    queries.start(data.editorId, data.executionId);
  }));
  disposers.push(rpc.on("query.resultMeta", (raw) => {
    const data = raw as QueryResult & { editorId: string };
    queries.addResult(data.editorId, { ...data, rows: [], complete: false });
  }));
  disposers.push(rpc.on("query.rows", (raw) => {
    const data = raw as { editorId: string; resultIndex: number; rows: Array<Array<string | null>> };
    queries.appendRows(data.editorId, data.resultIndex, data.rows);
  }));
  disposers.push(rpc.on("query.resultComplete", (raw) => {
    const data = raw as { editorId: string; resultIndex: number } & Partial<QueryResult>;
    queries.completeResult(data.editorId, data.resultIndex, data);
  }));
  disposers.push(rpc.on("query.executionComplete", (raw) => {
    const data = raw as { editorId: string; executionId: string; cancelled: boolean; failed: boolean; durationMs: number; transactionDirty: boolean };
    queries.complete(data.editorId, data); editors.patch(data.editorId, { busy: false, transactionDirty: data.transactionDirty });
    app.status = `${data.cancelled ? "执行已取消" : data.failed ? "执行失败" : "执行完成"} · ${data.durationMs} ms`;
  }));
  disposers.push(rpc.on("transaction.status", (raw) => {
    const data = raw as { editorId: string; dirty: boolean; message: string };
    editors.patch(data.editorId, { transactionDirty: data.dirty }); app.status = data.message;
  }));
  disposers.push(rpc.on("task.progress", (raw) => {
    const data = raw as { message?: string };
    if (data.message) app.status = data.message;
  }));
  disposers.push(rpc.on("metadata.completionProgress", (raw) => {
    metadata.updateProgress(raw as CompletionProgress);
  }));
  disposers.push(rpc.on("editor.connectionState", (raw) => {
    const data = raw as { editorId: string; state: EditorConnectionState; message?: string };
    editors.patch(data.editorId, { connectionState: data.state });
    if (data.message && data.editorId === editors.activeId) app.status = data.message;
  }));
  disposers.push(rpc.on("connections.changed", () => { void refreshConnectionCatalog(); }));
}

async function newEditor(content = "", filePath?: string, title?: string, fileHandle?: FileSystemFileHandle): Promise<EditorTab | undefined> {
  const inherited = editors.active?.connection && connections.current(editors.active.connection.id) ? editors.active.connection.id : undefined;
  const created = await rpc.request<{ id: string; title: string; connection?: EditorConnectionBinding; connectionState: EditorConnectionState }>("editor.create", inherited ? { profileId: inherited } : {});
  const tab: EditorTab = { id: created.id, title: title ?? created.title, content, filePath, fileHandle,
    dirty: Boolean(content && !filePath), transactionDirty: false, busy: false,
    connection: created.connection, connectionState: created.connectionState ?? "unbound" };
  editors.add(tab);
  if (tab.connection) void ensureCompletionForEditor(tab);
  return tab;
}

function markActiveDirty(): void { if (editors.active) editors.patch(editors.active.id, { dirty: true }); }
async function formatActive(): Promise<void> {
  const tab = editors.active; if (!tab) return;
  if (!tab.connection) { ElMessage.warning("请先为当前编辑标签选择数据库链接"); return; }
  const result = await rpc.request<{ text: string }>("sql.format", { editorId: tab.id, text: monacoEditor.value?.getValue(tab.id) ?? tab.content });
  monacoEditor.value?.setValue(result.text, tab.id);
  editors.patch(tab.id, { dirty: true });
}
function executeFromEditor(scope: "current" | "script", selectedText: string, cursorOffset: number): void { void executeActive(scope, selectedText, cursorOffset); }
function executeCommand(command: string): void {
  if (command === "current" || command === "script") void executeActive(command);
}
async function executeActive(scope: "current" | "script", selectedText = "", cursorOffset = 0): Promise<void> {
  const tab = editors.active; if (!tab || tab.busy) return;
  if (!tab.connection) { ElMessage.warning("请先为当前编辑标签选择数据库链接"); return; }
  editors.patch(tab.id, { busy: true }); app.status = "正在执行…";
  try {
    const response = await rpc.request<{ executionId: string }>("query.execute", { editorId: tab.id, text: monacoEditor.value?.getValue(tab.id) ?? tab.content, selectedText, cursorOffset, scope, stopOnError: true });
    queries.start(tab.id, response.executionId);
  } catch (error) { editors.patch(tab.id, { busy: false }); ElMessage.error(message(error)); }
}
async function cancelActive(): Promise<void> { if (editors.active) await rpc.request("query.cancel", { editorId: editors.active.id }); }
async function commitActive(): Promise<void> { if (editors.active?.connection) await rpc.request("transaction.commit", { editorId: editors.active.id }); }
async function rollbackActive(): Promise<void> { if (editors.active?.connection) await rpc.request("transaction.rollback", { editorId: editors.active.id }); }

interface ResultPageResponse {
  resultIndex: number;
  offset: number;
  rows: Array<Array<string | null>>;
  hasMore: boolean;
  nextOffset: number;
}

async function loadNextResultPage(): Promise<void> {
  const result = activeResult.value;
  if (result) await loadResultRows(result.resultIndex, result.rows.length, false);
}

async function loadAllResultRows(): Promise<void> {
  const result = activeResult.value;
  if (result) await loadResultRows(result.resultIndex, result.rows.length, true);
}

function resultLoadTooltip(mode: "next" | "all"): string {
  if (!activeResult.value?.columns.length) return "暂无可加载的查询结果";
  if (activeExecution.value?.busy || !activeResult.value.complete) return "查询尚未完成";
  if (resultLoading.value) return resultLoading.value.mode === "next" ? "正在加载下一页数据…" : "正在获取全部数据…";
  if (!activeResult.value.truncated) return "已获取全部数据";
  return mode === "next"
    ? `下一页数据 · 最多 ${settings.maxResultRows} 行；建议查询包含稳定的 ORDER BY`
    : "获取全部数据；查询将重新执行，建议包含稳定的 ORDER BY";
}

async function loadResultRows(resultIndex: number, initialOffset: number, all: boolean): Promise<void> {
  const tab = editors.active;
  if (!tab || resultLoading.value) return;
  const mode = all ? "all" : "next";
  resultLoading.value = { editorId: tab.id, resultIndex, mode };
  let offset = initialOffset;
  const limit = all ? 5_000 : settings.maxResultRows;
  try {
    do {
      const page = await rpc.request<ResultPageResponse>("query.fetchRows", {
        editorId: tab.id, resultIndex, offset, limit
      }, 120_000);
      if (page.rows.length) queries.appendRows(tab.id, resultIndex, page.rows);
      queries.completeResult(tab.id, resultIndex, { truncated: page.hasMore });
      offset = page.nextOffset;
      app.status = page.hasMore ? `已加载 ${offset} 行` : `已获取全部 ${offset} 行`;
      if (!all || !page.hasMore || page.rows.length === 0) break;
      await nextTick();
    } while (true);
  } catch (error) {
    reportError(error);
  } finally {
    resultLoading.value = undefined;
  }
}

function selectTool(tool: "objects" | "connections"): void {
  if (activeTool.value === tool && panelVisible.value) {
    panelOpen.value = false;
    return;
  }
  expandTool(tool);
}
function openConnectionManager(): void { expandTool("connections"); }
function expandTool(tool: "objects" | "connections"): void {
  activeTool.value = tool;
  const wasUnmounted = !panelOpen.value;
  panelOpen.value = true;
  const restoreWidth = (): void => {
    if (numericPanelWidth(leftWidth.value) <= 0) leftWidth.value = lastLeftWidth.value;
  };
  if (wasUnmounted) void nextTick(restoreWidth); else restoreWidth();
}
function numericPanelWidth(value: string | number): number {
  return typeof value === "number" ? value : Number.parseFloat(value) || 0;
}
function openCreateProfile(environmentId: string): void {
  editingProfile.value = undefined; profileEnvironmentId.value = environmentId; connectionDialog.value = true;
}
function openEditProfile(profile: SavedProfile): void {
  editingProfile.value = profile; profileEnvironmentId.value = profile.environmentId; connectionDialog.value = true;
}
async function profileSaved(profile: SavedProfile): Promise<void> {
  connections.upsert(profile);
  await refreshConnectionCatalog();
  void ensureCompletionForProfile(connections.current(profile.id) ?? profile);
}

function ensureCompletionForProfile(profile: SavedProfile, force = false): Promise<void> {
  return loadCompletionSnapshot(profile, { profileId: profile.id }, force);
}

function ensureCompletionForEditor(tab: EditorTab, force = false): Promise<void> {
  if (!tab.connection) return Promise.resolve();
  return loadCompletionSnapshot(tab.connection, { editorId: tab.id }, force);
}

async function loadCompletionSnapshot(profile: SavedProfile, source: { profileId?: string; editorId?: string }, force: boolean): Promise<void> {
  const context = connections.completionContext(profile);
  if (!context) return;
  const loadId = crypto.randomUUID();
  if (!metadata.beginCompletion(context.key, context.label, loadId, profile.id, force)) return;
  try {
    const snapshot = await rpc.request<CompletionSnapshot>("metadata.completionSnapshot", { loadId, ...source }, 5 * 60_000);
    if (!metadata.completeCompletion(context.key, loadId, snapshot)) return;
    const existing = completionNoticeTimers.get(context.key);
    if (existing !== undefined) window.clearTimeout(existing);
    completionNoticeTimers.set(context.key, window.setTimeout(() => {
      metadata.dismissNotice(context.key);
      completionNoticeTimers.delete(context.key);
    }, 3_000));
  } catch (error) {
    metadata.failCompletion(context.key, loadId, message(error));
  }
}

function refreshCompletionFromObjectExplorer(): void {
  const tab = editors.active;
  if (tab?.connection) void ensureCompletionForEditor(tab, true);
}

function completionMessage(cache: CompletionCache | undefined): string {
  if (!cache) return "";
  if (cache.state === "loading") {
    const progress = cache.progress;
    if (progress?.phase === "loading" && progress.total > 0) {
      return `${cache.label} 补全信息 ${progress.completed}/${progress.total} · ${progress.message}`;
    }
    return `${cache.label} · ${progress?.message || "正在扫描可见数据库…"}`;
  }
  if (cache.state === "error") return `${cache.label} 补全加载失败 · 请刷新数据库对象重试`;
  return `${cache.label} 补全已更新 · ${cache.suggestions.length} 项`;
}
async function refreshConnectionCatalog(): Promise<void> {
  const catalog = await rpc.request<ConnectionCatalog>("connection.catalog");
  connections.applyCatalog(catalog);
  for (const tab of editors.tabs) {
    if (!tab.connection) continue;
    const current = connections.current(tab.connection.id);
    const unavailable = !current;
    const stale = Boolean(current && current.revision !== tab.connection.revision);
    const newlyChanged = (unavailable && !tab.connection.unavailable) || (stale && !tab.connection.stale);
    editors.patch(tab.id, { connection: { ...tab.connection,
      environmentId: current?.environmentId ?? tab.connection.environmentId, unavailable, stale } });
    if (newlyChanged && tab.id === editors.activeId) {
      app.status = unavailable ? "链接配置已删除，当前会话仍可继续使用" : "配置已更新，重新选择链接后生效";
      ElNotification.warning({ title: unavailable ? "链接配置已删除" : "链接配置已更新",
        message: unavailable ? "当前编辑标签继续使用原连接快照；该链接不能再绑定到新标签。" : "当前编辑标签继续使用旧配置，重新选择该链接后生效。" });
    }
  }
}

async function connectionSelectionChanged(value: unknown): Promise<void> {
  const tab = editors.active; if (!tab) return;
  const selected = typeof value === "string" ? value : "";
  if (selected === activeConnectionValue.value) return;
  const transactionAction = await transactionActionForSwitch(tab);
  if (transactionAction === "cancel") return;
  try {
    if (!selected) {
      await rpc.request("editor.unbind", { editorId: tab.id, transactionAction });
      editors.patch(tab.id, { connection: undefined, connectionState: "unbound", transactionDirty: false });
    } else {
      const profileId = selected.split("@")[0];
      let response: { connection: EditorConnectionBinding; connectionState: EditorConnectionState };
      try {
        response = await rpc.request("editor.bind", { editorId: tab.id, profileId, transactionAction }, 60_000);
      } catch (error) {
        if ((error as { code?: string }).code !== "PASSWORD_REQUIRED") throw error;
        const password = await requestConnectionPassword();
        if (!password) return;
        response = await rpc.request("editor.bind", { editorId: tab.id, profileId, transactionAction,
          password: password.password, rememberPassword: password.remember }, 60_000);
      }
      editors.patch(tab.id, { connection: response.connection, connectionState: response.connectionState,
        transactionDirty: false });
      app.status = `已绑定 ${response.connection.name}`;
      void ensureCompletionForEditor({ ...tab, connection: response.connection, connectionState: response.connectionState });
    }
    queries.clearEditor(tab.id);
    metadata.activate(activeConnectionKey.value, activeCompletionKey.value);
    await nextTick(); objectExplorer.value?.resetTree();
  } catch (error) { reportError(error); }
}

async function transactionActionForSwitch(tab: EditorTab): Promise<"commit" | "rollback" | "cancel" | ""> {
  if (!tab.transactionDirty) return "";
  try {
    await ElMessageBox({ title: "未提交事务", message: "切换数据库链接前请选择提交或回滚。", type: "warning",
      showCancelButton: true, showClose: true, distinguishCancelAndClose: true,
      confirmButtonText: "提交并切换", cancelButtonText: "回滚并切换" });
    return "commit";
  } catch (choice) { return choice === "cancel" ? "rollback" : "cancel"; }
}

async function requestConnectionPassword(): Promise<{ password: string; remember: boolean } | undefined> {
  try {
    const result = await ElMessageBox.prompt("该链接没有可用的密码，请输入后继续。", "输入数据库密码", {
      inputType: "password", confirmButtonText: "继续", cancelButtonText: "取消"
    });
    let remember = false;
    try {
      await ElMessageBox.confirm("是否将密码保存到系统密钥库？", "记住密码", {
        confirmButtonText: "记住密码", cancelButtonText: "仅本次使用", distinguishCancelAndClose: true
      }); remember = true;
    } catch (choice) { if (choice !== "cancel") return undefined; }
    return { password: result.value, remember };
  } catch { return undefined; }
}

function handleShortcut(event: KeyboardEvent): void {
  const shortcut = event.metaKey || event.ctrlKey;
  if (!shortcut) return;
  const key = event.key.toLowerCase();
  let action: (() => void) | undefined;
  if (event.altKey && key === "c") action = () => { void commitActive().catch(reportError); };
  else if (event.altKey && key === "r") action = () => { void rollbackActive().catch(reportError); };
  else if (event.shiftKey && key === "c") action = () => { if (!connections.profiles.length) openConnectionManager(); else connectionCascader.value?.focus?.(); };
  else if (key === "n") action = () => { void newEditor().catch(reportError); };
  else if (key === "o") action = () => { void openFile().catch(reportError); };
  else if (key === "s") action = () => { void saveActive(event.shiftKey).catch(reportError); };
  else if (key === "r") action = () => { objectExplorer.value?.refresh(); };
  else if (key === "escape") action = () => { void cancelActive().catch(reportError); };
  if (!action) return;
  event.preventDefault();
  event.stopPropagation();
  action();
}

async function openFile(): Promise<void> {
  const file = await openSqlFile();
  if (file) await newEditor(file.content, file.name, file.name, file.handle);
}
async function openRecent(name: string): Promise<void> {
  const handle = recentHandles.get(name);
  if (!handle) return;
  const file = await openRecentSql(handle);
  if (file) await newEditor(file.content, file.name, file.name, file.handle);
}
async function saveActive(saveAs: boolean): Promise<boolean> {
  const tab = editors.active; if (!tab) return false;
  const file = await saveSqlFile(monacoEditor.value?.getValue(tab.id) ?? tab.content, tab.title, tab.fileHandle, saveAs);
  if (!file) return false;
  editors.patch(tab.id, { filePath: file.name, fileHandle: file.handle, title: file.name, dirty: false });
  if (file.handle) { recentHandles.set(file.name, file.handle); if (!settings.recentFiles.includes(file.name)) settings.recentFiles.unshift(file.name); }
  return true;
}

async function closeTab(id: string): Promise<boolean> {
  const tab = editors.tabs.find((item) => item.id === id); if (!tab) return true;
  editors.activeId = id;
  if (tab.dirty) {
    try {
      await ElMessageBox({ title: "保存修改？", message: `${tab.title} 有未保存的修改。`, type: "warning", showCancelButton: true,
        showClose: true, distinguishCancelAndClose: true, confirmButtonText: "保存", cancelButtonText: "不保存" });
      if (!await saveActive(false)) return false;
    } catch (action) { if (action !== "cancel") return false; }
  }
  const state = await rpc.request<{ requiresTransactionDecision: boolean }>("editor.close", { editorId: id, action: "check" });
  if (state.requiresTransactionDecision) {
    let action: "commit" | "rollback";
    try {
      await ElMessageBox({ title: "未提交事务", message: "关闭前请选择提交或回滚。", type: "warning", showCancelButton: true,
        showClose: true, distinguishCancelAndClose: true, confirmButtonText: "提交", cancelButtonText: "回滚" });
      action = "commit";
    } catch (choice) { if (choice !== "cancel") return false; action = "rollback"; }
    await rpc.request("editor.close", { editorId: id, action });
  } else await rpc.request("editor.close", { editorId: id, action: "close" });
  queries.clearEditor(id); editors.remove(id); return true;
}

async function closeApplication(activeTasks = 0): Promise<void> {
  try {
    if (activeTasks > 0) {
      try {
        await ElMessageBox.confirm(`仍有 ${activeTasks} 个导入或导出任务正在运行，退出将取消任务。`, "后台任务未完成", {
          type: "warning", confirmButtonText: "仍然退出", cancelButtonText: "继续等待"
        });
      } catch {
        await rpc.request("app.closeDecision", { allow: false });
        return;
      }
    }
    for (const tab of [...editors.tabs]) if (!await closeTab(tab.id)) { await rpc.request("app.closeDecision", { allow: false }); return; }
    await rpc.request("app.closeDecision", { allow: true });
  } catch (error) {
    reportError(error);
    await rpc.request("app.closeDecision", { allow: false }).catch(() => undefined);
  }
}

async function openObject(node: MetadataNode, execute: boolean): Promise<void> {
  if (!editors.active) return;
  const result = await rpc.request<{ sql: string }>("metadata.generateQuery", { ...node, editorId: editors.active.id });
  const tab = await newEditor(result.sql); if (execute && tab) { editors.activeId = tab.id; await executeActive("script"); }
}
async function openDefinition(node: MetadataNode): Promise<void> {
  if (!editors.active) return;
  const result = await rpc.request<{ definition: string }>("metadata.definition", { ...node, editorId: editors.active.id });
  await newEditor(`${result.definition};\n`, undefined, `${node.name ?? "对象"} 定义`);
}
async function openHistory(entry: HistoryEntry): Promise<void> { await newEditor(entry.sql, undefined, "历史查询"); }

async function updateTheme(theme: ThemePreference): Promise<void> {
  app.setThemePreference(theme);
  await rpc.request("settings.update", { key: "ui.theme", value: theme });
}
async function updateMaxRows(value: number): Promise<void> {
  const previous = settings.maxResultRows; settings.maxResultRows = value;
  try { await rpc.request("settings.update", { key: "result.maxRows", value: String(value) }); }
  catch (error) { settings.maxResultRows = previous; reportError(error); }
}
async function updateStreamBatchRows(value: number): Promise<void> {
  const previous = settings.streamBatchRows; settings.streamBatchRows = value;
  try { await rpc.request("settings.update", { key: "result.streamBatchRows", value: String(value) }); }
  catch (error) { settings.streamBatchRows = previous; reportError(error); }
}
async function updateColumnLayoutScope(value: ColumnLayoutScope): Promise<void> {
  const previous = settings.columnLayoutScope; settings.columnLayoutScope = value;
  try { await rpc.request("settings.update", { key: "result.columnLayoutScope", value }); }
  catch (error) { settings.columnLayoutScope = previous; reportError(error); }
}
async function updateCopyHeaderOnDoubleClick(value: boolean): Promise<void> {
  const previous = settings.copyHeaderOnDoubleClick; settings.copyHeaderOnDoubleClick = value;
  try { await rpc.request("settings.update", { key: "result.copyHeaderOnDoubleClick", value: String(value) }); }
  catch (error) { settings.copyHeaderOnDoubleClick = previous; reportError(error); }
}
async function updateCopySeparator(value: CopySeparator): Promise<void> {
  const previous = settings.copySeparator; settings.copySeparator = value;
  try { await rpc.request("settings.update", { key: "result.copySeparator", value }); }
  catch (error) { settings.copySeparator = previous; reportError(error); }
}
async function updateMaxActiveSessions(value: number): Promise<void> {
  const previous = settings.maxActiveSessions; settings.maxActiveSessions = value;
  try { await rpc.request("settings.update", { key: "connection.maxActiveSessions", value: String(value) }); }
  catch (error) { settings.maxActiveSessions = previous; reportError(error); }
}
async function updateIdleTimeoutMinutes(value: number): Promise<void> {
  const previous = settings.idleTimeoutMinutes; settings.idleTimeoutMinutes = value;
  try { await rpc.request("settings.update", { key: "connection.idleTimeoutMinutes", value: String(value) }); }
  catch (error) { settings.idleTimeoutMinutes = previous; reportError(error); }
}
function dataCommand(command: string): void {
  if (command === "import" && editors.active?.connection) csvDialog.value = true;
  else if (command === "history") historyDrawer.value = true;
  else if (command === "exit") void closeApplication();
  else settingsDrawer.value = true;
}
async function exportLoaded(resultIndex: number): Promise<void> {
  if (!editors.active) return;
  rpc.downloadCsv("loaded", editors.active.id, resultIndex);
  ElMessage.success("已开始下载当前已加载结果");
}
async function exportFull(resultIndex: number): Promise<void> {
  if (!editors.active) return;
  rpc.downloadCsv("full", editors.active.id, resultIndex);
  ElMessage.success("已开始流式导出完整结果");
}
function reportError(error: unknown): void { ElMessage.error(message(error)); }
function message(error: unknown): string { return error instanceof Error ? error.message : String(error); }
</script>

<style scoped>
.app-shell {
  position: relative;
  isolation: isolate;
  background:
    radial-gradient(circle at 12% -20%, var(--db-bg-glow), transparent 38%),
    var(--db-bg);
}
.app-toolbar {
  position: relative;
  z-index: 4;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-bottom: 1px solid var(--db-border);
  box-shadow: 0 1px 0 rgba(255, 255, 255, 0.04);
}
.connection-pill-wrap {
  position: relative;
  isolation: isolate;
  display: inline-flex;
  align-items: center;
  flex: none;
  width: auto;
  height: 32px;
  border-radius: 10px;
  background: var(--db-control-bg);
  box-shadow: inset 0 0 0 1px var(--db-control-border);
  transition: background-color 120ms var(--db-ease), box-shadow 120ms var(--db-ease);
}
.connection-pill-wrap:hover { background: var(--db-control-hover); }
.connection-pill-wrap.connected:not(.stale) {
  box-shadow:
    inset 0 0 0 var(--db-spectrum-edge-width) color-mix(in srgb, var(--db-spectrum-cyan) 92%, var(--db-control-border)),
    inset 0 0 4px var(--db-spectrum-fallback-inner),
    0 0 0 1px var(--db-spectrum-separator),
    0 1px 2px var(--db-spectrum-shadow);
}
.connection-pill-wrap.suspended { background: var(--db-control-bg); }
.connection-pill-wrap.stale {
  background: color-mix(in srgb, var(--db-warning) 12%, var(--db-control-bg));
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--db-warning) 32%, var(--db-control-border));
}
.connection-pill {
  position: relative;
  z-index: 1;
  flex: none;
  width: clamp(200px, 21vw, 280px);
  min-width: 0;
}
.connection-pill :deep(.el-input__wrapper) {
  min-height: 32px;
  padding: 0 10px;
  border-radius: 10px;
  background: transparent;
  box-shadow: none !important;
}
.connection-pill :deep(.el-input__wrapper:hover) { background: transparent; }
.connection-pill :deep(.el-input__wrapper.is-focus) {
  background: transparent;
  box-shadow: inset 0 0 0 1.5px var(--db-accent), 0 0 0 3px var(--db-accent-soft) !important;
}
.connection-pill :deep(.el-input__inner) { min-width: 0; overflow: hidden; text-overflow: ellipsis; }

@supports ((mask-composite: exclude) or (-webkit-mask-composite: xor)) {
  .connection-pill-wrap.connected:not(.stale) {
    box-shadow:
      inset 0 0 3px var(--db-spectrum-fallback-inner),
      0 0 0 1px var(--db-spectrum-separator),
      0 1px 2px var(--db-spectrum-shadow);
  }
  .connection-pill-wrap.connected:not(.stale)::before {
    content: "";
    position: absolute;
    z-index: 2;
    inset: 0;
    padding: var(--db-spectrum-edge-width);
    border-radius: inherit;
    pointer-events: none;
    opacity: var(--db-spectrum-opacity);
    background: var(--db-spectrum-gradient);
    background-size: 220% 100%;
    -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
    -webkit-mask-composite: xor;
    mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
    mask-composite: exclude;
    animation: connection-spectrum 5.6s linear infinite;
  }
  .connection-pill-wrap.connected:not(.stale)::after {
    content: var(--db-spectrum-inner-content);
    position: absolute;
    z-index: 0;
    inset: 1px;
    padding: 1px;
    border-radius: 9px;
    pointer-events: none;
    opacity: var(--db-spectrum-inner-opacity);
    filter: blur(0.8px);
    background: var(--db-spectrum-inner-gradient);
    background-size: 220% 100%;
    -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
    -webkit-mask-composite: xor;
    mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
    mask-composite: exclude;
    animation: connection-spectrum 5.6s linear infinite;
  }
  .connection-pill-wrap.connected:not(.stale):hover::before { opacity: 1; }
  .connection-pill-wrap.connected:not(.stale):hover::after { opacity: 0.82; }
  .connection-pill-wrap.connected:not(.stale):focus-within::before {
    opacity: 0.38;
    animation-play-state: paused;
  }
  .connection-pill-wrap.connected:not(.stale):focus-within::after {
    opacity: 0.22;
    animation-play-state: paused;
  }
}

@keyframes connection-spectrum {
  0%, 100% { background-position: 0% 50%; }
  50% { background-position: 100% 50%; }
}

@media (prefers-reduced-transparency: reduce) {
  .connection-pill-wrap { background: var(--db-glass-solid); }
  .connection-pill-wrap:hover { background: var(--db-glass-solid); }
  .connection-pill-wrap.stale {
    background: color-mix(in srgb, var(--db-warning) 12%, var(--db-glass-solid));
  }
}
.toolbar-cluster {
  display: inline-flex;
  align-items: center;
  gap: 1px;
  height: 34px;
  padding: 2px;
  border: 1px solid var(--db-border-soft);
  border-radius: 10px;
  background: var(--db-control-bg);
  background: color-mix(in srgb, var(--db-control-bg) 70%, transparent);
}
.toolbar-cluster :deep(.el-button) { width: 28px; min-height: 28px; padding: 0; border-radius: 7px; }
.toolbar-cluster :deep(.el-dropdown) { display: inline-flex; }
.execute-control { flex: none; }
.execute-control :deep(.el-button-group > .el-button:first-child) { min-width: 82px; border-radius: 10px 0 0 10px; }
.execute-control :deep(.el-button-group > .el-button:last-child) { border-radius: 0 10px 10px 0; }
.app-toolbar :deep(.el-divider--vertical) { margin: 0 2px; border-color: var(--db-border); }
.app-toolbar :deep(kbd),
:global(.el-dropdown-menu kbd) {
  margin-left: 22px;
  color: var(--db-muted);
  font: 11px/1.2 "SF Mono", Menlo, monospace;
}
.toolbar-spacer, .status-spacer { flex: 1; }
.workspace { display: flex; gap: 6px; padding: 8px 8px 6px 0; min-height: 0; overflow: hidden; }
.activity-bar { width: 40px; flex:none; display:flex; flex-direction:column; align-items:center; gap:4px; padding:5px 3px; border:1px solid var(--db-border); border-left:0; border-radius:0 11px 11px 0; background:var(--db-panel-soft); box-shadow:var(--db-shadow-sm); }
.activity-bar :deep(.el-button) { position:relative; width:32px; height:32px; margin:0; padding:0; border-radius:8px; color:var(--db-text-secondary); }
.activity-bar :deep(.el-button:hover) { background:var(--db-control-hover); color:var(--db-text); }
.activity-bar :deep(.el-button.active) { background:var(--db-accent-soft); color:var(--db-accent); }
.activity-bar :deep(.el-button.active::before) { content:""; position:absolute; left:-4px; width:2px; height:18px; border-radius:2px; background:var(--db-accent); }
.workbench {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--db-border);
  border-radius: 12px;
  background: var(--db-content);
  box-shadow: var(--db-shadow-sm);
}
.workbench :deep(.el-splitter-bar__dragger::before) { background: var(--db-border); }
.workbench :deep(.el-splitter-bar__collapse-icon) {
  border-color: var(--db-border);
  background: var(--db-surface-raised);
  color: var(--db-text-secondary);
}
.editor-area { display: flex; flex-direction: column; background: var(--db-editor-bg); }
.editor-tabs {
  flex: none;
  height: 36px;
  padding: 0 6px;
  background: var(--db-panel-soft);
  border-bottom: 1px solid var(--db-border-soft);
}
.editor-tab-label { display: inline-flex; align-items: center; gap: 6px; max-width: 180px; }
.dirty-dot { width: 6px; height: 6px; flex: none; border-radius: 50%; background: var(--db-accent); }
.editor-widget { flex: 1; min-height: 0; }
.workspace-empty { height: 100%; background: var(--db-panel-soft); }
.workspace-empty :deep(.el-empty__image) { width: auto; height: auto; }
.workspace-empty :deep(.el-empty__image .el-icon) {
  width: 44px;
  height: 44px;
  border-radius: 13px;
  background: var(--db-control-bg);
  color: var(--db-text-secondary);
  font-size: 22px;
  box-shadow: inset 0 0 0 1px var(--db-border-soft);
}
.status-bar {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 0 10px;
  color: var(--db-text-secondary);
  font-size: 11px;
  background: transparent;
}
.status-item { display: inline-flex; align-items: center; gap: 6px; white-space: nowrap; }
.status-item svg { width: 12px; height: 12px; }
.completion-status {
  min-width: 0;
  max-width: min(42vw, 520px);
  overflow: hidden;
  padding-left: 10px;
  border-left: 1px solid var(--db-border-soft);
  text-overflow: ellipsis;
}
.completion-status.loading { color: var(--db-accent); }
.completion-status.error { color: var(--db-warning); }
.completion-status.ready { color: var(--db-success); }
.completion-status .is-loading { animation: rotating 1.4s linear infinite; }
.status-result-actions {
  height: 18px;
  display: inline-flex;
  align-items: center;
  gap: 1px;
  padding-left: 7px;
  border-left: 1px solid var(--db-border-soft);
}
.status-result-actions :deep(.el-button) { width: 20px; height: 20px; min-height: 20px; padding: 0; border-radius: 5px; }
.status-result-actions :deep(.el-button .el-icon) { width: 12px; height: 12px; }
.status-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--db-muted); }
.status-dot.online { background: var(--db-success); }
.status-dot.busy { background: var(--db-accent); box-shadow: 0 0 0 3px var(--db-accent-soft); }
.status-dot.offline { background: var(--db-muted); }
.transaction-warning { color: var(--db-warning); font-weight: 550; }
:deep(.editor-tabs .el-tabs__content) { display: none; }
:deep(.editor-tabs .el-tabs__header) { height: 35px; background: transparent; }
:deep(.editor-tabs .el-tabs__nav-wrap::after) { display: none; }
:deep(.editor-tabs .el-tabs__item) {
  height: 34px;
  margin: 1px 2px 0;
  padding: 0 12px;
  border-radius: 8px 8px 0 0;
}
:deep(.editor-tabs .el-tabs__item.is-active) { background: var(--db-content); }
:deep(.editor-tabs .el-tabs__active-bar) { height: 2px; }

@media (max-width: 1080px) {
  .app-toolbar { gap: 5px; padding-inline: 7px; }
  .connection-pill { width: 220px; }
  .toolbar-cluster { gap: 0; }
}

@media (max-width: 980px) {
  .connection-pill { width: 200px; }
  .workspace { padding-right: 6px; padding-left: 0; }
}

@media (prefers-reduced-motion: reduce) {
  .connection-pill-wrap.connected:not(.stale)::before,
  .connection-pill-wrap.connected:not(.stale)::after {
    animation: none !important;
    background-position: 50% 50%;
  }
}
</style>
