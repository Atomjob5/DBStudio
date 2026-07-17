<template>
  <el-container class="app-shell fill" v-loading="app.loading">
    <el-header class="app-toolbar glass-surface" height="48px" aria-label="应用工具栏">
      <el-button class="connection-pill" :class="{ connected: app.connected }" :icon="Connection"
                 :aria-label="app.connected ? `当前连接：${app.connectedProfile?.name}` : '连接数据库'"
                 @click="connectionDialog = true">
        <span class="connection-name">{{ app.connectedProfile?.name ?? "连接数据库" }}</span>
        <span class="connection-indicator" aria-hidden="true" />
      </el-button>

      <div class="toolbar-cluster file-actions" aria-label="文件操作">
        <el-tooltip content="新建查询 · ⌘/Ctrl N" placement="bottom">
          <el-button text :icon="Plus" aria-label="新建查询" :disabled="!app.connected" @click="newEditor()" />
        </el-tooltip>
        <el-tooltip content="打开 SQL · ⌘/Ctrl O" placement="bottom">
          <el-button text :icon="FolderOpened" aria-label="打开 SQL 文件" :disabled="!app.connected" @click="openFile" />
        </el-tooltip>
        <el-dropdown trigger="click" :disabled="!app.connected" @command="openRecent">
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
          <el-button text :icon="Select" aria-label="提交事务" :disabled="!editors.active" @click="commitActive" />
        </el-tooltip>
        <el-tooltip content="回滚事务 · ⌘/Ctrl Alt R" placement="bottom">
          <el-button text :icon="RefreshLeft" aria-label="回滚事务" :disabled="!editors.active" @click="rollbackActive" />
        </el-tooltip>
      </div>

      <span class="toolbar-spacer" />
      <el-dropdown @command="dataCommand">
        <el-button text circle :icon="MoreFilled" aria-label="更多操作" />
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="import" :icon="Upload" :disabled="!app.connected">导入 CSV / TSV</el-dropdown-item>
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
      <el-splitter class="workbench" lazy>
        <el-splitter-panel v-model:size="leftWidth" :min="210" :max="420" collapsible>
          <ObjectExplorer v-if="app.connected" ref="objectExplorer" :connection-name="app.connectedProfile?.name"
                          @open="openObject" @definition="openDefinition" />
          <el-empty v-else class="workspace-empty" description="连接数据库后浏览对象">
            <template #image><el-icon><Coin /></el-icon></template>
            <el-button type="primary" round @click="connectionDialog = true">连接数据库</el-button>
          </el-empty>
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
                  <el-button round :disabled="!app.connected" @click="newEditor()">新建查询</el-button>
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
      <span class="status-item"><i class="status-dot" :class="app.connected ? 'online' : 'offline'" />{{ app.status }}</span>
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
        {{ editors.active?.busy ? "正在执行" : app.connected ? "自动提交关闭" : "离线" }}
      </span>
    </el-footer>
  </el-container>

  <ConnectionDialog v-model="connectionDialog" :providers="connections.providers" :profiles="connections.profiles" @connected="connected" />
  <HistoryDrawer v-model="historyDrawer" @open="openHistory" />
  <SettingsDrawer v-model="settingsDrawer" :theme="app.themePreference" :resolved-theme="app.theme" :max-rows="settings.maxResultRows"
                  :stream-batch-rows="settings.streamBatchRows" @update:theme="updateTheme" @update:max-rows="updateMaxRows"
                  @update:stream-batch-rows="updateStreamBatchRows" />
  <CsvImportDialog v-model="csvDialog" @imported="objectExplorer?.refresh()" />
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage, ElMessageBox, ElNotification } from "element-plus";
import {
  ArrowDown,
  ArrowRightBold,
  Bottom,
  Clock,
  Close,
  Coin,
  Connection,
  DArrowRight,
  Document,
  DocumentChecked,
  FolderOpened,
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
import { applyDocumentTheme } from "./theme";
import { openRecentSql, openSqlFile, recentSqlFiles, saveSqlFile } from "./files/browserFiles";
import type { BootstrapResponse, EditorTab, HistoryEntry, MetadataNode, QueryResult, SavedProfile, Suggestion, ThemePreference } from "./types";

const app = useAppStore(); const connections = useConnectionStore(); const metadata = useMetadataStore();
const editors = useEditorStore(); const queries = useQueryStore(); const settings = useSettingsStore();
const connectionDialog = ref(false); const historyDrawer = ref(false); const settingsDrawer = ref(false); const csvDialog = ref(false);
const leftWidth = ref(248); const editorHeight = ref("62%");
const objectExplorer = ref<InstanceType<typeof ObjectExplorer>>();
const monacoEditor = ref<{ getValue(key?: string): string; setValue(value: string, key?: string): void }>();
const recentHandles = new Map<string, FileSystemFileHandle>();
const activeExecution = computed(() => editors.activeId ? queries.executions[editors.activeId] : undefined);
const activeResultIndex = ref(0);
const activeResult = computed(() => activeExecution.value?.results.find((result) => result.resultIndex === activeResultIndex.value)
  ?? activeExecution.value?.results[0]);
const resultLoading = ref<{ editorId: string; resultIndex: number; mode: "next" | "all" }>();
const activeResultLoading = computed(() => resultLoading.value?.editorId === editors.activeId
  ? { resultIndex: resultLoading.value.resultIndex, mode: resultLoading.value.mode } : undefined);
const canLoadMore = computed(() => Boolean(activeResult.value?.columns.length && activeResult.value.complete
  && activeResult.value.truncated && !activeExecution.value?.busy && !resultLoading.value));
const nextPageTooltip = computed(() => resultLoadTooltip("next"));
const allRowsTooltip = computed(() => resultLoadTooltip("all"));
const canExecute = computed(() => Boolean(editors.active && !editors.active.busy));
const disposers: Array<() => void> = [];
const colorSchemeQuery = window.matchMedia?.("(prefers-color-scheme: dark)");
let layoutSaveTimer: number | undefined;

onMounted(async () => {
  app.setSystemTheme(colorSchemeQuery?.matches ? "dark" : "light");
  colorSchemeQuery?.addEventListener?.("change", systemThemeChanged);
  installEventHandlers();
  app.loading = true;
  try {
    const data = await rpc.request<BootstrapResponse>("app.bootstrap");
    connections.initialize(data.providers, data.profiles);
    settings.initialize(data.settings, data.recentFiles);
    for (const recent of await recentSqlFiles().catch(() => [])) {
      recentHandles.set(recent.name, recent.handle);
      if (!settings.recentFiles.includes(recent.name)) settings.recentFiles.push(recent.name);
    }
    app.applyBootstrap(data);
    leftWidth.value = Number(data.settings["layout.leftWidth"] ?? 248);
    editorHeight.value = data.settings["layout.editorHeight"] ?? "62%";
    if (!app.connected) connectionDialog.value = true;
    else await prepareConnectedWorkspace();
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
});

watch(() => app.theme, (theme) => applyDocumentTheme(theme), { immediate: true });
watch(() => activeExecution.value?.executionId, () => {
  activeResultIndex.value = activeExecution.value?.results[0]?.resultIndex ?? 0;
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
}

async function connected(profile: SavedProfile): Promise<void> {
  app.connectedProfile = profile; app.status = `已连接 ${profile.name}`; connections.upsert(profile);
  editors.clear(); queries.clear(); metadata.clear();
  await prepareConnectedWorkspace();
}

async function prepareConnectedWorkspace(): Promise<void> {
  const suggestions = await rpc.request<Suggestion[]>("sql.complete", { prefix: "" });
  metadata.addSuggestions(suggestions);
  await newEditor();
  await nextTick(); objectExplorer.value?.refresh();
}

async function newEditor(content = "", filePath?: string, title?: string, fileHandle?: FileSystemFileHandle): Promise<EditorTab | undefined> {
  if (!app.connected) { connectionDialog.value = true; return; }
  const created = await rpc.request<{ id: string; title: string }>("editor.create", {});
  const tab: EditorTab = { id: created.id, title: title ?? created.title, content, filePath, fileHandle, dirty: Boolean(content && !filePath), transactionDirty: false, busy: false };
  editors.add(tab); return tab;
}

function markActiveDirty(): void { if (editors.active) editors.patch(editors.active.id, { dirty: true }); }
async function formatActive(): Promise<void> {
  const tab = editors.active; if (!tab) return;
  const result = await rpc.request<{ text: string }>("sql.format", { text: monacoEditor.value?.getValue(tab.id) ?? tab.content });
  monacoEditor.value?.setValue(result.text, tab.id);
  editors.patch(tab.id, { dirty: true });
}
function executeFromEditor(scope: "current" | "script", selectedText: string, cursorOffset: number): void { void executeActive(scope, selectedText, cursorOffset); }
function executeCommand(command: string): void {
  if (command === "current" || command === "script") void executeActive(command);
}
async function executeActive(scope: "current" | "script", selectedText = "", cursorOffset = 0): Promise<void> {
  const tab = editors.active; if (!tab || tab.busy) return;
  editors.patch(tab.id, { busy: true }); app.status = "正在执行…";
  try {
    const response = await rpc.request<{ executionId: string }>("query.execute", { editorId: tab.id, text: monacoEditor.value?.getValue(tab.id) ?? tab.content, selectedText, cursorOffset, scope, stopOnError: true });
    queries.start(tab.id, response.executionId);
  } catch (error) { editors.patch(tab.id, { busy: false }); ElMessage.error(message(error)); }
}
async function cancelActive(): Promise<void> { if (editors.active) await rpc.request("query.cancel", { editorId: editors.active.id }); }
async function commitActive(): Promise<void> { if (editors.active) await rpc.request("transaction.commit", { editorId: editors.active.id }); }
async function rollbackActive(): Promise<void> { if (editors.active) await rpc.request("transaction.rollback", { editorId: editors.active.id }); }

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

function handleShortcut(event: KeyboardEvent): void {
  const shortcut = event.metaKey || event.ctrlKey;
  if (!shortcut) return;
  const key = event.key.toLowerCase();
  let action: (() => void) | undefined;
  if (event.altKey && key === "c") action = () => { void commitActive().catch(reportError); };
  else if (event.altKey && key === "r") action = () => { void rollbackActive().catch(reportError); };
  else if (event.shiftKey && key === "c") action = () => { connectionDialog.value = true; };
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
  }
  editors.remove(id); return true;
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
  const result = await rpc.request<{ sql: string }>("metadata.generateQuery", node);
  const tab = await newEditor(result.sql); if (execute && tab) { editors.activeId = tab.id; await executeActive("script"); }
}
async function openDefinition(node: MetadataNode): Promise<void> {
  const result = await rpc.request<{ definition: string }>("metadata.definition", node);
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
function dataCommand(command: string): void {
  if (command === "import") csvDialog.value = true;
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
.connection-pill {
  max-width: 190px;
  padding: 0 12px;
  border-radius: 999px;
  background: var(--db-control-bg);
}
.connection-pill.connected { background: var(--db-accent-soft); border-color: transparent; }
.connection-name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.connection-indicator {
  width: 7px;
  height: 7px;
  margin-left: 2px;
  border-radius: 50%;
  background: var(--db-muted);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--db-muted) 13%, transparent);
}
.connection-pill.connected .connection-indicator {
  background: var(--db-success);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--db-success) 15%, transparent);
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
.workspace { padding: 8px 8px 6px; min-height: 0; overflow: hidden; }
.workbench {
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
  .connection-pill { max-width: 130px; }
  .toolbar-cluster { gap: 0; }
}

@media (max-width: 980px) {
  .connection-pill { width: 34px; padding: 0; }
  .connection-name,
  .connection-indicator { display: none; }
  .workspace { padding-inline: 6px; }
}
</style>
