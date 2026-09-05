<template>
  <WorkspaceChooser v-if="!workspaceOpened" :workspaces="workspaceCatalog" :loading="workspaceLoading"
                    @open="openWorkspace" @create="createWorkspace" @rename="renameWorkspace"
                    @delete="deleteWorkspace" @refresh="refreshWorkspaces" />
  <el-container v-else class="app-shell fill" v-loading="app.loading">
    <el-header class="app-toolbar glass-surface" height="48px" aria-label="应用工具栏">
      <el-tooltip :content="activeConnectionTooltip" placement="bottom" :disabled="connectionCascaderOpen" :show-after="600">
        <div class="connection-pill-wrap" :class="{ connected: ['ready','active'].includes(editors.active?.connectionState ?? ''), suspended: editors.active?.connectionState === 'suspended', stale: editors.active?.connection?.stale || editors.active?.connection?.unavailable }">
          <el-cascader ref="connectionCascader" class="connection-pill" :model-value="activeConnectionValue"
                       :options="connections.cascaderOptions" :props="connectionCascaderProps"
                       :show-all-levels="false" filterable clearable :disabled="!editors.active || activeDatabaseBusy || app.transportState !== 'ready'"
                       :placeholder="activeConnectionDisplay" aria-label="当前编辑标签的数据库链接"
                       @change="connectionSelectionChanged" @visible-change="connectionCascaderOpen = $event">
            <template #default="{ data }">
              <span>{{ data.menuLabel ?? data.label }}</span>
            </template>
          </el-cascader>
        </div>
      </el-tooltip>

      <div class="toolbar-cluster file-actions" aria-label="文件操作">
        <IconTooltip :content="actionTooltip('新建查询', 'file.newQuery')" placement="bottom">
          <el-button text :icon="Plus" aria-label="新建查询" @click="newEditor()" />
        </IconTooltip>
        <IconTooltip :content="actionTooltip('打开 SQL', 'file.openSql')" placement="bottom">
          <el-button text :icon="FolderOpened" aria-label="打开 SQL 文件" @click="openFile" />
        </IconTooltip>
        <el-dropdown trigger="click" @command="openRecent">
          <IconTooltip content="最近打开的 SQL 文件" placement="bottom">
            <el-button text :icon="ArrowDown" aria-label="最近打开的 SQL 文件" />
          </IconTooltip>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-if="!settings.recentFiles.length" disabled>没有最近文件</el-dropdown-item>
              <el-dropdown-item v-for="path in settings.recentFiles" :key="path" :command="path">{{ path }}</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <IconTooltip :content="actionTooltip('保存', 'file.saveSql')" placement="bottom">
          <el-button text :icon="DocumentChecked" aria-label="保存 SQL" :disabled="!editors.active" @click="saveActive(false)" />
        </IconTooltip>
      </div>

      <div class="toolbar-cluster editor-actions" aria-label="SQL 编辑操作">
        <IconTooltip :content="actionTooltip('格式化 SQL', 'editor.format')" placement="bottom">
          <el-button text :icon="MagicStick" aria-label="格式化 SQL" :disabled="!canTransformSql"
                     @click="requestSqlTransform('format')" />
        </IconTooltip>
        <IconTooltip :content="actionTooltip('压缩 SQL', 'editor.compact')" placement="bottom">
          <el-button text :icon="Fold" aria-label="压缩 SQL" :disabled="!canTransformSql"
                     @click="requestSqlTransform('compact')" />
        </IconTooltip>
        <el-divider direction="vertical" />
        <IconTooltip :content="actionTooltip('转换大写', 'editor.uppercase')" placement="bottom">
          <el-button text :icon="Top" aria-label="转换大写" :disabled="!canEditSelection"
                     @click="runEditorSelectionAction('uppercase')" />
        </IconTooltip>
        <IconTooltip :content="actionTooltip('转换小写', 'editor.lowercase')" placement="bottom">
          <el-button text :icon="Bottom" aria-label="转换小写" :disabled="!canEditSelection"
                     @click="runEditorSelectionAction('lowercase')" />
        </IconTooltip>
        <el-divider direction="vertical" />
        <IconTooltip :content="actionTooltip('单行注释', 'editor.toggleLineComment')" placement="bottom">
          <el-button text :icon="ChatDotSquare" aria-label="单行注释" :disabled="!canEditSelection"
                     @click="runEditorSelectionAction('lineComment')" />
        </IconTooltip>
        <IconTooltip :content="actionTooltip('全部注释', 'editor.toggleBlockComment')" placement="bottom">
          <el-button text :icon="ChatLineSquare" aria-label="全部注释" :disabled="!canEditSelection"
                     @click="runEditorSelectionAction('blockComment')" />
        </IconTooltip>
      </div>

      <el-tooltip v-if="activeExecutionRunning" :content="cancelExecutionTooltip" placement="bottom">
        <el-button class="execute-control cancel-execution-control" type="warning" :icon="Close"
                   aria-label="取消执行" :loading="activeCancellationPhase === 'cancelling'"
                   :disabled="!canCancelExecution" @click="cancelActive">
          {{ activeCancellationPhase === "cancelling" ? "正在取消…" : "取消执行" }}
        </el-button>
      </el-tooltip>
      <el-tooltip v-else :content="actionTooltip('执行当前语句', 'query.executeCurrent')" placement="bottom">
        <el-dropdown class="execute-control" split-button type="primary" :icon="VideoPlay" :disabled="!canExecute"
                     @click="triggerEditorExecution('current')" @command="executeCommand">
          执行
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="current"><span>执行当前语句</span><kbd v-if="settings.shortcuts['query.executeCurrent']">{{ displayShortcut(settings.shortcuts["query.executeCurrent"]) }}</kbd></el-dropdown-item>
              <el-dropdown-item command="current-new-tab"><span>在新结果集执行当前语句</span><kbd v-if="settings.shortcuts['query.executeCurrentNewTab']">{{ displayShortcut(settings.shortcuts["query.executeCurrentNewTab"]) }}</kbd></el-dropdown-item>
              <el-dropdown-item command="script"><span>执行整个脚本</span><kbd v-if="settings.shortcuts['query.executeAll']">{{ displayShortcut(settings.shortcuts["query.executeAll"]) }}</kbd></el-dropdown-item>
              <el-dropdown-item command="explain" divided :disabled="!canExplain"><span>查看执行计划</span><kbd v-if="settings.shortcuts['query.explain']">{{ displayShortcut(settings.shortcuts['query.explain']) }}</kbd></el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-tooltip>

      <Transition name="transaction-actions">
        <el-button-group v-if="hasActiveTransaction" class="query-actions" aria-label="事务操作">
          <IconTooltip :content="actionTooltip('提交事务', 'transaction.commit')" placement="bottom">
            <el-button type="success" :icon="Select" aria-label="提交事务"
                       :loading="editors.active?.transactionOperation === 'committing'"
                       :disabled="!canOperateTransaction" @click="commitActive" />
          </IconTooltip>
          <IconTooltip :content="actionTooltip('回滚事务', 'transaction.rollback')" placement="bottom">
            <el-button type="danger" :icon="RefreshLeft" aria-label="回滚事务"
                       :loading="editors.active?.transactionOperation === 'rolling-back'"
                       :disabled="!canOperateTransaction" @click="rollbackActive" />
          </IconTooltip>
        </el-button-group>
      </Transition>

      <span class="toolbar-spacer" />
      <el-dropdown @command="dataCommand">
        <IconTooltip content="更多操作" placement="bottom">
          <el-button text circle :icon="MoreFilled" aria-label="更多操作" />
        </IconTooltip>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="import" :icon="Upload" :disabled="!activeConnected || app.transportState !== 'ready'"><span>导入 CSV / TSV</span><kbd v-if="settings.shortcuts['data.import']">{{ displayShortcut(settings.shortcuts["data.import"]) }}</kbd></el-dropdown-item>
            <el-dropdown-item command="history" :icon="Clock"><span>查询历史</span><kbd v-if="settings.shortcuts['history.open']">{{ displayShortcut(settings.shortcuts["history.open"]) }}</kbd></el-dropdown-item>
            <el-dropdown-item command="tasks" :icon="Monitor"><span>任务管理器</span></el-dropdown-item>
            <el-dropdown-item divided command="settings" :icon="Setting"><span>设置</span><kbd v-if="settings.shortcuts['settings.open']">{{ displayShortcut(settings.shortcuts["settings.open"]) }}</kbd></el-dropdown-item>
            <el-dropdown-item divided command="exit" :icon="SwitchButton"><span>退出 DBStudio</span><kbd v-if="settings.shortcuts['app.exit']">{{ displayShortcut(settings.shortcuts["app.exit"]) }}</kbd></el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <IconTooltip :content="actionTooltip(app.theme === 'dark' ? '切换亮色主题' : '切换深色主题', 'ui.toggleTheme')" placement="bottom">
        <el-button text circle :icon="app.theme === 'dark' ? Sunny : Moon" aria-label="切换界面主题"
                   @click="updateTheme(app.theme === 'dark' ? 'light' : 'dark')" />
      </IconTooltip>
    </el-header>

    <el-main class="workspace">
      <nav class="activity-bar" aria-label="工作区工具导航">
        <IconTooltip :content="actionTooltip('数据库对象', 'workspace.objects')" placement="right"><el-button text :icon="Coin" aria-label="数据库对象"
          :class="{ active: activeTool === 'objects' && panelVisible }" :aria-pressed="activeTool === 'objects' && panelVisible" @click="selectTool('objects')" /></IconTooltip>
        <IconTooltip :content="actionTooltip('连接管理', 'workspace.connections')" placement="right"><el-button text :icon="Connection" aria-label="连接管理"
          :class="{ active: activeTool === 'connections' && panelVisible }" :aria-pressed="activeTool === 'connections' && panelVisible" @click="selectTool('connections')" /></IconTooltip>
      </nav>
      <el-splitter class="workbench" lazy>
        <el-splitter-panel v-if="panelOpen" v-model:size="leftWidth" :min="210" :max="420" collapsible>
          <ObjectExplorer v-if="activeTool === 'objects' && activeConnected && editors.active?.connection" ref="objectExplorer"
                          :editor-id="editors.active.id" :tree-cache-key="activeObjectTreeKey"
                          :connection-name="editors.active.connection.name" :completion-loading="activeCompletionLoading"
                          @open="openObject" @definition="openDefinition" @refresh="refreshCompletionFromObjectExplorer" />
          <el-empty v-else-if="activeTool === 'objects'" class="workspace-empty" description="当前编辑标签尚未选择数据库链接">
            <template #image><el-icon><Coin /></el-icon></template>
            <el-button type="primary" round @click="openConnectionManager">打开连接管理</el-button>
          </el-empty>
          <ConnectionManagerPanel v-else :providers="connections.providers" :systems="connections.systems" :environments="connections.environments"
                                  :profiles="connections.profiles" @changed="refreshConnectionCatalog"
                                  @create-profile="openCreateProfile" @edit-profile="openEditProfile" />
        </el-splitter-panel>
        <el-splitter-panel :min="500">
          <div ref="resultContentPanel" class="result-content-panel">
            <el-splitter class="result-content-splitter" layout="vertical" lazy>
              <el-splitter-panel v-model:size="editorHeight" :min="220">
                <section class="editor-area fill">
                  <el-tabs v-if="editors.tabs.length" v-model="editors.activeId" closable class="editor-tabs"
                           @tab-remove="(name) => closeTab(String(name))">
                    <el-tab-pane v-for="tab in editors.tabs" :key="tab.id" :name="tab.id">
                      <template #label>
                        <el-dropdown trigger="contextmenu" :disabled="editorTabMenuBusy"
                                     :class="{
                            'editor-tab-dragging': editorDrag?.sourceId === tab.id,
                            'editor-tab-drop-before': editorDrag?.targetId === tab.id && editorDrag.position === 'before',
                            'editor-tab-drop-after': editorDrag?.targetId === tab.id && editorDrag.position === 'after'
                          }" draggable="true"
                                @dragstart="startEditorTabDrag($event, tab.id)"
                                @dragover="dragOverEditorTab($event, tab.id)"
                                @drop="dropEditorTab($event, tab.id)"
                                @dragend="endEditorTabDrag"
                                     @command="(command) => handleEditorTabCommand(String(command), tab.id)">
                          <span class="editor-tab-label">
                            <i v-if="editorTabIndicator(tab)" class="dirty-dot"
                               :class="`editor-tab-status-${editorTabIndicator(tab)}`"
                               :aria-label="editorTabIndicatorLabel(tab)"
                               :title="editorTabIndicatorLabel(tab)" />
                            <span class="editor-tab-title">{{ tab.title }}</span>
                          </span>
                          <template #dropdown>
                            <el-dropdown-menu>
                              <el-dropdown-item command="close" :disabled="editorTabMenuBusy">关闭窗口</el-dropdown-item>
                              <el-dropdown-item command="close-others"
                                                :disabled="editorTabMenuBusy || editors.tabs.length <= 1">关闭其它窗口</el-dropdown-item>
                              <el-dropdown-item command="close-all" :disabled="editorTabMenuBusy">关闭所有窗口</el-dropdown-item>
                              <el-dropdown-item command="duplicate" divided :disabled="editorTabMenuBusy">复制窗口</el-dropdown-item>
                              <el-dropdown-item command="rename" :disabled="editorTabMenuBusy">重命名</el-dropdown-item>
                            </el-dropdown-menu>
                          </template>
                        </el-dropdown>
                      </template>
                    </el-tab-pane>
                  </el-tabs>
                  <MonacoEditor v-if="editors.active" ref="monacoEditor" class="editor-widget" :model-key="editors.active.id"
                                :initial-value="editors.active.content" :theme="app.theme" :appearance="activeColorScheme"
                                :completion-key="activeCompletionKey" :provider-id="editors.active.connection?.providerId || 'generic'"
                                :completion-revision="activeCompletionRevision"
                                :completion-metadata-ready="activeCompletionMetadataReady"
                                :completion-candidate-limit="settings.completionCandidateLimit"
                                :completion-precise-matching-enabled="settings.completionPreciseMatchingEnabled"
                                :completion-snippets="settings.completionSnippets"
                                :minimap-enabled="settings.minimapEnabled"
                                :word-wrap-enabled="settings.wordWrapEnabled"
                                :diagnostics-enabled="settings.sqlDiagnosticsEnabled"
                                :dangerous-statement-warning-enabled="settings.dangerousStatementWarningEnabled"
                                :editor-id="editors.active.id" :connection-display="activeConnectionDisplay"
                                :default-catalog="editors.active.connection?.settings.database || editors.active.connection?.settings.catalog"
                                :default-schema="editors.active.connection?.settings.schema"
                                :object-inspector-opacity="settings.objectInspectorOpacity"
                                @dirty="markEditorDirty" @execute="executeFromEditor"
                                @selection-change="editorHasSelection = $event"
                                @update:object-inspector-opacity="settings.objectInspectorOpacity = $event"
                                @save-object-inspector-opacity="updateObjectInspectorOpacity" />
                  <el-empty v-else class="workspace-empty" description="新建 SQL 标签开始查询">
                    <template #image><el-icon><Document /></el-icon></template>
                    <el-button round @click="newEditor()">新建查询</el-button>
                  </el-empty>
                </section>
              </el-splitter-panel>
              <el-splitter-panel :min="150" collapsible>
                <ResultPanel ref="resultPanel" v-model:active-result-index="activeResultIndex" :executions="activeExecutions"
                             :executing="editors.active?.busy === true"
                             :execution-started-at="editors.active?.executionStartedAt"
                             :execution-timeline-stage="editors.active?.executionTimelineStage"
                             :show-result-edit-actions="showResultEditActions"
                             :result-edit-unlocked="resultEditUnlocked"
                             :can-toggle-result-edit="canToggleResultEdit"
                             :result-edit-tooltip="resultEditTooltip"
                             :can-apply-result-changes="canPostResultChanges"
                             :apply-result-changes-tooltip="postResultChangesTooltip"
                             @export-result="exportResult" @close-result="closeTemporaryResult"
                             @result-tab-click="handleResultTabClick"
                             @tabs-wheel="handleResultTabsWheel"
                             @toggle-result-edit="toggleResultEdit" @apply-result-changes="postActiveResultChanges"
                             @selected-column="selectedResultColumn = $event"
                             @selected-row-count="selectedResultRowCount = $event"
                             @selected-status-text="selectedResultStatusText = $event"
                             @update-compare-highlight-mode="updateCompareHighlightMode"
                             @update-compare-scope="updateCompareScope"
                             @update-compare-case-sensitive="updateCompareCaseSensitive" />
              </el-splitter-panel>
            </el-splitter>
          </div>
        </el-splitter-panel>
      </el-splitter>
    </el-main>

    <AppStatusBar :execution-text="activeExecutionText" :busy="activeDatabaseBusy"
                  :plan-active="activeExecution?.displayType === 'execution-plan'"
                  :selected-row-count="selectedResultRowCount" :result-content-offset="resultContentOffset"
                  :selected-status-text="selectedResultStatusText"
                  :selected-column="selectedResultColumn"
                  :show-selected-column-remarks="settings.showSelectedColumnRemarks" :system-items="systemStatusItems"
                  :can-load-more="canLoadMore" :loading-mode="activeResultLoading?.mode"
                  :can-auto-refresh="canToggleAutoRefresh" :auto-refresh-enabled="autoRefreshEnabled"
                  :auto-refresh-interval-seconds="settings.autoRefreshIntervalSeconds"
                  :auto-refresh-tooltip="autoRefreshTooltip"
                  :next-page-tooltip="nextPageTooltip" :all-rows-tooltip="allRowsTooltip"
                  @toggle-auto-refresh="toggleAutoRefresh"
                  @update-auto-refresh-interval="updateAutoRefreshInterval"
                  @load-next="loadNextResultPage" @load-all="loadAllResultRows" @dismiss-task="dismissStatusTask" />
  </el-container>

  <ConnectionDialog v-model="connectionDialog" :providers="connections.providers" :systems="connections.systems"
                    :environments="connections.environments" :profile="editingProfile" :initial-environment-id="profileEnvironmentId"
                    @saved="profileSaved" />
  <HistoryDrawer v-model="historyDrawer" @open="openHistory" />
  <JdbcTaskManagerDrawer v-model="jdbcTaskManagerDrawer" />
  <SettingsDrawer v-model="settingsDrawer" :theme="app.themePreference" :resolved-theme="app.theme" :max-rows="settings.maxResultRows"
                  :stream-batch-rows="settings.streamBatchRows" :clob-max-characters="settings.clobMaxCharacters"
                  :max-lob-bytes="settings.maxResultLobBytes"
                  :column-layout-scope="settings.columnLayoutScope"
                  :copy-header-on-double-click="settings.copyHeaderOnDoubleClick" :copy-separator="settings.copySeparator"
                  :header-sorting-enabled="settings.headerSortingEnabled" :header-filtering-enabled="settings.headerFilteringEnabled"
                  :show-column-remarks-in-header="settings.showColumnRemarksInHeader"
                  :zebra-stripes-enabled="settings.zebraStripesEnabled"
                  :scroll-optimization-buffer-screens="settings.scrollOptimizationBufferScreens"
                  :show-selected-column-remarks="settings.showSelectedColumnRemarks"
                  :max-active-sessions="settings.maxActiveSessions" :auto-commit="settings.autoCommit"
                  :idle-timeout-minutes="settings.idleTimeoutMinutes"
                  :transaction-disconnect-rollback-minutes="settings.transactionDisconnectRollbackMinutes"
                  :completion-candidate-limit="settings.completionCandidateLimit"
                  :completion-precise-matching-enabled="settings.completionPreciseMatchingEnabled"
                  :minimap-enabled="settings.minimapEnabled" :word-wrap-enabled="settings.wordWrapEnabled"
                  :sql-diagnostics-enabled="settings.sqlDiagnosticsEnabled"
                  :dangerous-statement-warning-enabled="settings.dangerousStatementWarningEnabled"
                  :continue-on-error="settings.continueOnError"
                  :execution-warning-minutes="settings.executionWarningMinutes"
                  :completion-cache-size="completionCacheSize" :completion-cache-environment-count="metadata.completionStats.environmentCount"
                  :completion-cache-loading-count="metadata.completionStats.loadingCount" :can-clear-completion-caches="metadata.canClearCompletions"
                  @update:theme="updateTheme" @update:max-rows="updateMaxRows" @update:max-lob-bytes="updateMaxLobBytes"
                  @update:stream-batch-rows="updateStreamBatchRows" @update:clob-max-characters="updateClobMaxCharacters"
                  @update:column-layout-scope="updateColumnLayoutScope"
                  @update:copy-header-on-double-click="updateCopyHeaderOnDoubleClick"
                  @update:header-sorting-enabled="updateHeaderSortingEnabled"
                  @update:header-filtering-enabled="updateHeaderFilteringEnabled"
                  @update:show-column-remarks-in-header="updateShowColumnRemarksInHeader"
                  @update:zebra-stripes-enabled="updateZebraStripesEnabled"
                  @update:scroll-optimization-buffer-screens="updateScrollOptimizationBufferScreens"
                  @update:show-selected-column-remarks="updateShowSelectedColumnRemarks"
                  @update:copy-separator="updateCopySeparator" @update:max-active-sessions="updateMaxActiveSessions"
                  @update:auto-commit="updateAutoCommit"
                  @update:idle-timeout-minutes="updateIdleTimeoutMinutes"
                  @update:transaction-disconnect-rollback-minutes="updateTransactionDisconnectRollbackMinutes"
                  @update:completion-candidate-limit="updateCompletionCandidateLimit"
                  @update:completion-precise-matching-enabled="updateCompletionPreciseMatchingEnabled"
                  @update:minimap-enabled="updateMinimapEnabled"
                  @update:word-wrap-enabled="updateWordWrapEnabled"
                  @update:sql-diagnostics-enabled="updateSqlDiagnosticsEnabled"
                  @update:dangerous-statement-warning-enabled="updateDangerousStatementWarningEnabled"
                  @update:continue-on-error="updateContinueOnError"
                  @update:execution-warning-minutes="updateExecutionWarningMinutes"
                  @clear-completion-caches="clearCompletionCaches" @open-shortcuts="openShortcutSettings"
                  @open-appearance="appearanceDrawer = true"
                  @open-completion-snippets="openCompletionSnippetSettings" />
  <AppearanceColorSchemeDrawer v-model="appearanceDrawer" :schemes="settings.colorSchemes" :active-mode="app.theme"
                               :saving="appearanceSaving"
                               @save="saveColorSchemes" />
  <ShortcutSettingsDrawer v-model="shortcutDrawer" :bindings="settings.shortcuts" :saving="shortcutSaving"
                          @update-binding="updateShortcutBinding" @reset-defaults="resetShortcutBindings"
                          @recording="settings.shortcutRecordingActive = $event" />
  <SqlSnippetSettingsDrawer v-model="completionSnippetDrawer" :snippets="settings.completionSnippets"
                            :saving="completionSnippetSaving" @update-snippets="updateCompletionSnippets" />
  <CompletionSchemaDialog v-model="completionSchemaDialog" :namespaces="completionSchemaNamespaces"
                          :initial-selected-keys="completionSchemaInitialKeys" :refresh="completionSchemaRefresh"
                          @confirm="completeSchemaSelection" @cancel="cancelSchemaSelection" />
  <CsvImportDialog v-model="csvDialog" :editor-id="editors.active?.id" />
  <ReleaseNotesDialog v-model="releaseNotesVisible" :releases="pendingReleaseNotes" @dismiss="dismissReleaseNotes" />
</template>

<script setup lang="ts">
import { computed, h, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElButton, ElMessage, ElMessageBox, ElNotification } from "element-plus";
import type { CascaderProps } from "element-plus";
import {
  ArrowDown,
  Bottom, ChatDotSquare,
  ChatLineRound, ChatLineSquare,
  Clock,
  Close,
  Coin,
  Comment,
  Connection,
  Document,
  DocumentChecked,
  Fold,
  FolderOpened,
  MagicStick,
  Monitor,
  Moon,
  MoreFilled,
  Plus,
  RefreshLeft,
  Select,
  Setting,
  Sunny,
  SwitchButton,
  Top,
  Upload,
  VideoPlay
} from "@element-plus/icons-vue";
import { rpc } from "./bridge/rpc";
import ConnectionDialog from "./components/ConnectionDialog.vue";
import ConnectionManagerPanel from "./components/ConnectionManagerPanel.vue";
import CompletionSchemaDialog from "./components/CompletionSchemaDialog.vue";
import CsvImportDialog from "./components/CsvImportDialog.vue";
import AppearanceColorSchemeDrawer from "./components/AppearanceColorSchemeDrawer.vue";
import HistoryDrawer from "./components/HistoryDrawer.vue";
import IconTooltip from "./components/IconTooltip.vue";
import JdbcTaskManagerDrawer from "./components/JdbcTaskManagerDrawer.vue";
import MonacoEditor from "./components/MonacoEditor.vue";
import ObjectExplorer from "./components/ObjectExplorer.vue";
import ResultPanel from "./components/ResultPanel.vue";
import ReleaseNotesDialog from "./components/ReleaseNotesDialog.vue";
import SettingsDrawer from "./components/SettingsDrawer.vue";
import ShortcutSettingsDrawer from "./components/ShortcutSettingsDrawer.vue";
import SqlSnippetSettingsDrawer from "./components/SqlSnippetSettingsDrawer.vue";
import AppStatusBar from "./components/AppStatusBar.vue";
import WorkspaceChooser from "./components/WorkspaceChooser.vue";
import { useAppStore } from "./stores/app";
import { useConnectionStore } from "./stores/connection";
import { useEditorStore } from "./stores/editor";
import { useExecutionAttentionStore, type ExecutionAttentionOutcome } from "./stores/executionAttention";
import { formatCompletionBytes, useMetadataStore } from "./stores/metadata";
import { useQueryStore } from "./stores/query";
import { useResultEditStore, type ResultEditSession } from "./stores/resultEdits";
import { useSettingsStore } from "./stores/settings";
import { useStatusBarStore } from "./stores/statusBar";
import type { ColumnLayoutScope } from "./columnLayout";
import type { CopySeparator } from "./resultCopy";
import type { ResultCompareHighlightMode, ResultCompareScope } from "./resultCompare";
import { applyColorSchemeCss, cloneColorSchemes, serializeColorSchemeSettings, type ColorSchemeSettings } from "./appearance";
import { applyDocumentTheme } from "./theme";
import { openRecentSql, openSqlFile, recentSqlFiles, saveSqlFile } from "./files/browserFiles";
import { completionClient } from "./completion/client";
import { initialCompletionNamespaceKeys } from "./completion/schemaSelection";
import { serializeSqlCompletionSnippets } from "./completion/snippets";
import { createExecutionNotificationScheduler } from "./executionNotifications";
import { normalizeExecutionWarningMinutes, serializeExecutionWarningMinutes } from "./executionWarningSettings";
import { getPendingReleaseNotes, markReleaseNotesBatchSeen, RELEASE_NOTES_RELEASES, type ReleaseNotesRelease } from "./releaseNotes";
import {
  DEFAULT_SHORTCUT_BINDINGS,
  actionForShortcut,
  displayShortcut,
  isDangerousLegacyShortcut,
  serializeShortcutBindings,
  shortcutFromKeyboardEvent,
  shortcutTooltip,
  type ShortcutActionId,
  type ShortcutBinding,
  type ShortcutBindings,
} from "./shortcuts";
import type { BootstrapResponse, CompletionCache, CompletionNamespaceDescriptor, CompletionNamespacesResponse, CompletionProgress, ConnectionCatalog, EditorConnectionBinding, EditorConnectionState, EditorTab, ExecutionTimelineStage, HistoryEntry, MetadataNode, QueryExecutionSource, QueryResult, RecoveredEditor, ResultExportRequest, SavedProfile, SelectedResultColumn, SqlCompletionSnippet, SqlEditorSelectionAction, SqlTransformApplyResult, SqlTransformTarget, StatusBarSystemItem, ThemePreference, TransportState, WorkspaceOpenResponse, WorkspaceSummary } from "./types";

const app = useAppStore(); const connections = useConnectionStore(); const metadata = useMetadataStore();
const editors = useEditorStore(); const executionAttention = useExecutionAttentionStore();
const queries = useQueryStore(); const settings = useSettingsStore();
const resultEdits = useResultEditStore();
const statusBar = useStatusBarStore();
const connectionDialog = ref(false); const historyDrawer = ref(false); const jdbcTaskManagerDrawer = ref(false);
const settingsDrawer = ref(false);
const appearanceDrawer = ref(false); const appearanceSaving = ref(false);
const shortcutDrawer = ref(false); const shortcutSaving = ref(false); const csvDialog = ref(false);
const completionSnippetDrawer = ref(false); const completionSnippetSaving = ref(false);
const editorTabMenuBusy = ref(false);
const leftWidth = ref(248); const lastLeftWidth = ref(248); const editorHeight = ref("62%");
const activeTool = ref<"objects" | "connections">("connections"); const panelOpen = ref(true);
const editingProfile = ref<SavedProfile>(); const profileEnvironmentId = ref("");
const connectionCascader = ref();
const connectionCascaderOpen = ref(false);
const objectExplorer = ref<InstanceType<typeof ObjectExplorer>>();
const monacoEditor = ref<{
  getValue(key?: string): string | undefined;
  setValue(value: string, key?: string): void;
  triggerExecute(scope: "current" | "script" | "current-new-tab" | "explain"): void;
  triggerCompletion(): void;
  runSelectionAction(action: SqlEditorSelectionAction): boolean;
  captureSqlTransformTarget(key?: string): SqlTransformTarget | undefined;
  applySqlTransform(target: SqlTransformTarget, replacement: string): SqlTransformApplyResult;
  registerExecutionSources(executionId: string, sources: QueryExecutionSource[], key?: string): void;
  releaseExecutionSources(executionId: string): void;
  releaseEditorSources(key?: string): void;
  releaseModel(key: string): void;
  highlightExecutionSource(executionId: string, sql: string, startOffset?: number, endOffset?: number,
                            options?: { reveal?: boolean }): "highlighted" | "stale" | "missing";
  clearResultHighlight(): void;
}>();
const editorHasSelection = ref(false);
const activeColorScheme = computed(() => settings.colorSchemes[app.theme]);
const editorDrag = ref<{ sourceId: string; targetId?: string; position: "before" | "after" }>();
const resultPanel = ref<{
  restoreLayout(): void;
  toggleSingleRecordView(): void;
  toggleRecordComparison(): void;
  copyCurrentSelection(): Promise<void>;
}>();
const resultContentPanel = ref<HTMLElement>();
const resultContentOffset = ref(0);
const recentHandles = new Map<string, FileSystemFileHandle>();
const workspaceOpened = ref(false);
const workspaceCatalog = ref<WorkspaceSummary[]>([]);
const workspaceLoading = ref(false);
const currentWorkspace = ref<WorkspaceSummary>();
const releaseNotesVisible = ref(false);
const pendingReleaseNotes = ref<ReleaseNotesRelease[]>([]);
const releaseNotesDismissedInSession = new Set<string>();
const completionSchemaDialog = ref(false);
const completionSchemaNamespaces = ref<CompletionNamespaceDescriptor[]>([]);
const completionSchemaInitialKeys = ref<string[]>([]);
const completionSchemaRefresh = ref(false);
const resultExecutionDecisionPending = ref(false);
interface SchemaSelectionRequest {
  namespaces: CompletionNamespaceDescriptor[];
  initialKeys: string[];
  refresh: boolean;
  resolve: (value: CompletionNamespaceDescriptor[] | undefined) => void;
}
const schemaSelectionQueue: SchemaSelectionRequest[] = [];
let activeSchemaSelection: SchemaSelectionRequest | undefined;
const completionLoads = new Map<string, Promise<void>>();
const draftSaveTimers = new Map<string, number>();
const draftSaveQueues = new Map<string, Promise<boolean>>();
const draftPendingEditors = new Set<string>();
const draftGenerations = new Map<string, number>();
let draftWorkspaceEpoch = 0;
const EXECUTION_TIMELINE_STAGE_DURATION_MS = 400;
const executionTimelineStageTimers = new Map<string, number>();
interface ExecutionTimelineSession {
  attemptId: number;
  executionId?: string;
  resultMetaReceived?: boolean;
  stageEnteredAt: number;
  pendingStages: Array<"planning" | "preparing-result">;
}
const executionTimelineSessions = new Map<string, ExecutionTimelineSession>();
let nextExecutionTimelineAttemptId = 0;
const activeExecutions = computed(() => editors.activeId ? queries.executionList(editors.activeId) : []);
const activeResultIndex = ref<string | number>(0);
const canExplain = computed(() => canExecute.value && Boolean(connections.providers.find(
  provider => provider.id === editors.active?.connection?.providerId)?.capabilities.includes("EXPLAIN_PLAN")));
const activeExecution = computed(() => {
  const key = String(activeResultIndex.value);
  return activeExecutions.value.find((execution) => execution.executionId === key
    || execution.results.some((result) => `${execution.executionId}:${result.resultIndex}` === key))
    ?? queries.executions[editors.activeId ?? ""];
});
const activeResult = computed(() => activeExecution.value?.results.find((result) =>
  activeExecution.value && (`${activeExecution.value.executionId}:${result.resultIndex}` === String(activeResultIndex.value)
    || (activeExecution.value.executionId === String(activeResultIndex.value) && result.resultIndex === 0)
    || (activeExecutions.value.length === 1 && result.resultIndex === activeResultIndex.value)))
  ?? activeExecution.value?.results[0]);

type EditorTabIndicator = "dirty" | "running" | ExecutionAttentionOutcome;

const executionTimelineRank: Record<ExecutionTimelineStage, number> = {
  thinking: 0, planning: 1, "preparing-result": 2, success: 3
};

function clearExecutionTimelineStageTimer(editorId: string): void {
  const timer = executionTimelineStageTimers.get(editorId);
  if (timer !== undefined) window.clearTimeout(timer);
  executionTimelineStageTimers.delete(editorId);
}

function beginExecutionTimeline(editorId: string): ExecutionTimelineSession {
  clearExecutionTimelineStageTimer(editorId);
  const session = {
    attemptId: ++nextExecutionTimelineAttemptId,
    stageEnteredAt: Date.now(),
    pendingStages: [] as Array<"planning" | "preparing-result">
  };
  executionTimelineSessions.set(editorId, session);
  return session;
}

function clearExecutionTimelineSession(editorId: string, attemptId?: number): void {
  const session = executionTimelineSessions.get(editorId);
  if (session && attemptId !== undefined && session.attemptId !== attemptId) return;
  executionTimelineSessions.delete(editorId);
  clearExecutionTimelineStageTimer(editorId);
}

function bindExecutionTimelineExecution(editorId: string, attemptId: number, executionId: string): boolean {
  const session = executionTimelineSessions.get(editorId);
  if (!session || session.attemptId !== attemptId) return false;
  if (session.executionId && session.executionId !== executionId) {
    return false;
  }
  session.executionId = executionId;
  return true;
}

function matchExecutionTimelineEvent(editorId: string, executionId?: string): boolean {
  if (!executionId) return true;
  const session = executionTimelineSessions.get(editorId);
  return !session?.executionId || session.executionId === executionId;
}

function restoreExecutionTimelineSession(editorId: string, executionId?: string): void {
  const previous = executionTimelineSessions.get(editorId);
  if (previous?.executionId && executionId && previous.executionId !== executionId) {
    clearExecutionTimelineStageTimer(editorId);
    executionTimelineSessions.set(editorId, {
      attemptId: ++nextExecutionTimelineAttemptId,
      executionId,
      stageEnteredAt: Date.now(),
      pendingStages: []
    });
    editors.patch(editorId, { executionTimelineStage: "preparing-result" });
    return;
  }
  const session = previous ?? {
    attemptId: ++nextExecutionTimelineAttemptId,
    stageEnteredAt: Date.now(),
    pendingStages: []
  };
  if (executionId) session.executionId = executionId;
  executionTimelineSessions.set(editorId, session);
}

function executionTimelineUsesPacing(): boolean {
  return typeof window.matchMedia !== "function"
    || !window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

function advanceExecutionTimeline(editorId: string, stage: ExecutionTimelineStage, executionId?: string): boolean {
  const tab = editors.tabs.find((item) => item.id === editorId);
  if (!tab || (executionId && tab.activeExecutionId && tab.activeExecutionId !== executionId)
      || !matchExecutionTimelineEvent(editorId, executionId)) return false;
  const current = tab.executionTimelineStage;
  if (current && executionTimelineRank[stage] <= executionTimelineRank[current]) return false;
  editors.patch(editorId, { executionTimelineStage: stage });
  return true;
}

function advanceExecutionTimelineQueue(editorId: string, attemptId: number, executionId: string): void {
  const session = executionTimelineSessions.get(editorId);
  const tab = editors.tabs.find((item) => item.id === editorId);
  if (!session || session.attemptId !== attemptId || session.resultMetaReceived
      || !tab || (tab.activeExecutionId && tab.activeExecutionId !== executionId)) {
    clearExecutionTimelineStageTimer(editorId);
    return;
  }
  if (session.executionId && session.executionId !== executionId) return;
  const nextStage = session.pendingStages[0];
  if (!nextStage) {
    clearExecutionTimelineStageTimer(editorId);
    return;
  }
  const current = tab.executionTimelineStage;
  if (current && executionTimelineRank[nextStage] <= executionTimelineRank[current]) {
    session.pendingStages.shift();
    advanceExecutionTimelineQueue(editorId, attemptId, executionId);
    return;
  }

  const elapsed = Math.max(0, Date.now() - session.stageEnteredAt);
  const requiredDuration = executionTimelineUsesPacing() ? EXECUTION_TIMELINE_STAGE_DURATION_MS : 0;
  const remaining = Math.max(0, requiredDuration - elapsed);
  if (remaining > 0) {
    clearExecutionTimelineStageTimer(editorId);
    let timer: number;
    timer = window.setTimeout(() => {
      if (executionTimelineStageTimers.get(editorId) === timer) executionTimelineStageTimers.delete(editorId);
      advanceExecutionTimelineQueue(editorId, attemptId, executionId);
    }, remaining);
    executionTimelineStageTimers.set(editorId, timer);
    return;
  }

  clearExecutionTimelineStageTimer(editorId);
  session.pendingStages.shift();
  editors.patch(editorId, { executionTimelineStage: nextStage });
  session.stageEnteredAt = Date.now();
  if (requiredDuration === 0) {
    void nextTick().then(() => advanceExecutionTimelineQueue(editorId, attemptId, executionId));
    return;
  }
  advanceExecutionTimelineQueue(editorId, attemptId, executionId);
}

function queueExecutionTimelineStages(editorId: string, executionId: string, attemptId: number): void {
  const session = executionTimelineSessions.get(editorId);
  if (!session || session.attemptId !== attemptId || session.resultMetaReceived
      || (session.executionId && session.executionId !== executionId)) return;
  session.executionId = executionId;
  for (const stage of ["planning", "preparing-result"] as const) {
    if (!session.pendingStages.includes(stage)) session.pendingStages.push(stage);
  }
  advanceExecutionTimelineQueue(editorId, attemptId, executionId);
}

function executionRequestFailureKey(editorId: string): string {
  return `request:${editorId}`;
}

function editorTabIndicator(tab: EditorTab): EditorTabIndicator | undefined {
  if (tab.busy || tab.executionPhase !== "idle") return "running";
  const outcome = executionAttention.outcome(tab.id);
  if (outcome) return outcome;
  return tab.dirty ? "dirty" : undefined;
}

function editorTabIndicatorLabel(tab: EditorTab): string {
  switch (editorTabIndicator(tab)) {
    case "running": return "SQL 正在执行";
    case "success": return "SQL 执行成功，待查看";
    case "error": return "SQL 执行失败，待查看";
    case "dirty": return "未保存";
    default: return "";
  }
}

function syncResultHighlight(mode: "passive" | "explicit" = "passive"): void {
  const editorId = editors.activeId;
  const execution = activeExecution.value;
  const result = activeResult.value;
  if (!editorId || !execution || execution.editorId !== editorId || !result) {
    monacoEditor.value?.clearResultHighlight?.();
    return;
  }
  const reveal = mode === "explicit";
  const outcome = monacoEditor.value?.highlightExecutionSource?.(execution.executionId, result.sql,
    result.sourceStartOffset, result.sourceEndOffset, { reveal });
  if (outcome === "highlighted" || mode !== "explicit") return;
  const sourceWasProvided = result.sourceStartOffset !== undefined && result.sourceEndOffset !== undefined;
  if (outcome !== "stale" && !(outcome === "missing" && sourceWasProvided)) return;
  ElMessage.warning("执行后 SQL 已修改，无法定位原语句");
}

function handleResultTabClick(tabKey: string | number): void {
  activeResultIndex.value = tabKey;
  void nextTick().then(() => syncResultHighlight("explicit"));
}

function openExecutionResult(editorId: string, executionId: string): void {
  if (!editors.tabs.some((tab) => tab.id === editorId)) return;
  editors.activeId = editorId;
  void nextTick().then(() => {
    if (!editors.tabs.some((tab) => tab.id === editorId)
        || !queries.execution(editorId, executionId)) return;
    activeResultIndex.value = executionId;
    return nextTick().then(() => syncResultHighlight("explicit"));
  });
}
interface ResultLoadingState {
  editorId: string;
  resultIndex: number;
  resultExecutionId: string;
  mode: "next" | "all";
  executionId: string;
  phase: "starting" | "running" | "cancelling";
  cancelRequested: boolean;
}
interface PendingResultBatch {
  editorId: string;
  resultIndex: number;
  operationId: string;
  resultExecutionId: string;
  rows: Array<Array<string | null>>;
  rowIds: string[];
  nextOffset?: number;
}
const pendingResultBatches = new Map<string, PendingResultBatch>();
const seenPageBatches = new Map<string, Set<number>>();
const pendingLoadAll = new Map<string, { resolve: () => void; reject: (error: unknown) => void }>();
const resultReloadRequired = new Set<string>();
let pendingRowsFrame: number | undefined;
let pendingRowsTimer: number | undefined;
interface AutoRefreshState {
  editorId: string;
  executionId: string;
  resultIndex: number;
  sql: string;
  sourceStartOffset: number;
  refreshing: boolean;
  epoch: number;
}
const resultLoading = ref<ResultLoadingState>();
const autoRefreshState = ref<AutoRefreshState>();
let autoRefreshTimer: number | undefined;
let autoRefreshEpoch = 0;
const selectedResultColumn = ref<SelectedResultColumn>();
const selectedResultRowCount = ref(0);
const selectedResultStatusText = ref("");

function pendingResultKey(editorId: string, operationId: string, resultIndex?: number): string {
  return `${editorId}:${operationId}:${resultIndex === undefined ? "*" : resultIndex}`;
}

function resultReloadKey(editorId: string, resultExecutionId: string, resultIndex: number): string {
  return `${editorId}:${resultExecutionId}:${resultIndex}`;
}

function activeResultReloadKey(): string | undefined {
  const tab = editors.active;
  const execution = activeExecution.value;
  const result = activeResult.value;
  return tab && execution && result ? resultReloadKey(tab.id, execution.executionId, result.resultIndex) : undefined;
}

function enqueueResultRows(editorId: string, resultIndex: number, rows: Array<Array<string | null>>,
                           rowIds: string[] | undefined, operationId: string,
                           resultExecutionId: string, offset?: number, publishImmediately = false): void {
  if (!rows.length) return;
  const result = queries.execution(editorId, resultExecutionId)?.results
    .find((item) => item.resultIndex === resultIndex);
  const key = pendingResultKey(editorId, operationId, resultIndex);
  const pending = pendingResultBatches.get(key);
  // Publish the first visible batch immediately so the result panel can render
  // while later batches are coalesced into one frame.
  if (!pending && result && (publishImmediately || (offset === undefined && result.rows.length === 0))) {
    queries.appendRows(editorId, resultIndex, rows, resultExecutionId, rowIds);
    return;
  }
  const ids = rowIds && rowIds.length === rows.length ? rowIds : rows.map(() => crypto.randomUUID());
  if (pending) {
    pending.rows.push(...rows); pending.rowIds.push(...ids);
    if (offset !== undefined) pending.nextOffset = offset + rows.length;
  } else {
    pendingResultBatches.set(key, { editorId, resultIndex, operationId, resultExecutionId,
      rows: [...rows], rowIds: [...ids], nextOffset: offset === undefined ? undefined : offset + rows.length });
  }
  schedulePendingRowsFlush();
}

function schedulePendingRowsFlush(): void {
  if (pendingRowsFrame === undefined) {
    const requestFrame = window.requestAnimationFrame;
    pendingRowsFrame = requestFrame ? requestFrame(() => {
      pendingRowsFrame = undefined;
      flushPendingResultBatches();
    }) : window.setTimeout(() => {
      pendingRowsFrame = undefined;
      flushPendingResultBatches();
    }, 0);
  }
  if (pendingRowsTimer === undefined) {
    pendingRowsTimer = window.setTimeout(() => {
      pendingRowsTimer = undefined;
      if (pendingRowsFrame !== undefined) {
        window.cancelAnimationFrame?.(pendingRowsFrame);
        window.clearTimeout(pendingRowsFrame);
        pendingRowsFrame = undefined;
      }
      flushPendingResultBatches();
    }, 50);
  }
}

function flushPendingResultBatches(editorId?: string, operationId?: string, resultIndex?: number): void {
  for (const [key, pending] of [...pendingResultBatches]) {
    if (editorId !== undefined && pending.editorId !== editorId) continue;
    if (operationId !== undefined && pending.operationId !== operationId) continue;
    if (resultIndex !== undefined && pending.resultIndex !== resultIndex) continue;
    pendingResultBatches.delete(key);
    if (pending.rows.length) queries.appendRows(pending.editorId, pending.resultIndex, pending.rows,
      pending.resultExecutionId, pending.rowIds);
  }
  if (!pendingResultBatches.size && pendingRowsTimer !== undefined) {
    window.clearTimeout(pendingRowsTimer);
    pendingRowsTimer = undefined;
  }
}

function clearPendingResultBatches(editorId?: string, operationId?: string, resultIndex?: number): void {
  for (const [key, pending] of [...pendingResultBatches]) {
    if (editorId !== undefined && pending.editorId !== editorId) continue;
    if (operationId !== undefined && pending.operationId !== operationId) continue;
    if (resultIndex !== undefined && pending.resultIndex !== resultIndex) continue;
    pendingResultBatches.delete(key);
    seenPageBatches.delete(key);
  }
  for (const key of [...seenPageBatches.keys()]) {
    const matchesEditor = editorId === undefined || key.startsWith(`${editorId}:`);
    const matchesOperation = operationId === undefined || key.includes(`:${operationId}:`);
    const matchesResult = resultIndex === undefined || key.endsWith(`:${resultIndex}`);
    if (matchesEditor && matchesOperation && matchesResult) seenPageBatches.delete(key);
  }
  if (!pendingResultBatches.size) {
    if (pendingRowsFrame !== undefined) {
      window.cancelAnimationFrame?.(pendingRowsFrame);
      window.clearTimeout(pendingRowsFrame);
      pendingRowsFrame = undefined;
    }
    if (pendingRowsTimer !== undefined) {
      window.clearTimeout(pendingRowsTimer);
      pendingRowsTimer = undefined;
    }
  }
}

function rejectPendingLoadAll(editorId: string, executionId?: string, error?: Error): void {
  for (const [id, pending] of [...pendingLoadAll]) {
    if (!executionId || id === executionId) {
      pendingLoadAll.delete(id);
      pending.reject(error ?? new Error("结果加载已结束"));
    }
  }
  clearPendingResultBatches(editorId, executionId);
}
const activeResultLoading = computed(() => {
  const loading = resultLoading.value;
  return loading && loading.editorId === editors.activeId ? loading : undefined;
});
const canLoadMore = computed(() => Boolean(activeResult.value?.columns.length && activeResult.value.complete
  && activeResult.value.truncated && !activeExecution.value?.busy && !activeExecution.value?.historical
  && !resultLoading.value && !resultReloadRequired.has(activeResultReloadKey() ?? "")
  && app.transportState === "ready"
  && !(editors.active && (resultEdits.hasChanges(editors.active.id)
    || editors.active.resultChangesDirty))));
const nextPageTooltip = computed(() => actionTooltip(resultLoadTooltip("next"), "result.loadNext"));
const allRowsTooltip = computed(() => actionTooltip(resultLoadTooltip("all"), "result.loadAll"));
const activeConnected = computed(() => Boolean(editors.active?.connection && editors.active.connectionState !== "unbound"));
const autoRefreshEnabled = computed(() => Boolean(autoRefreshState.value));
const canStartAutoRefresh = computed(() => Boolean(activeResult.value?.columns.length && activeResult.value.complete
  && !activeResult.value.errorMessage && !activeExecution.value?.failed
  && !activeExecution.value?.busy && !activeExecution.value?.historical && !resultLoading.value
  && app.transportState === "ready" && activeConnected.value
  && editors.active?.connectionState !== "credentials-required"
  && editors.active?.connectionState !== "unavailable"
  && !(editors.active && (resultEdits.hasChanges(editors.active.id) || editors.active.resultChangesDirty))));
const canToggleAutoRefresh = computed(() => autoRefreshEnabled.value || canStartAutoRefresh.value);
const autoRefreshTooltip = computed(() => {
  const seconds = settings.autoRefreshIntervalSeconds;
  if (autoRefreshEnabled.value) return `定时刷新已开启 · 每 ${seconds} 秒；左键关闭，右键设置周期`;
  if (!activeResult.value?.columns.length) return `当前没有可定时刷新的查询结果；右键设置周期（${seconds} 秒）`;
  if (activeExecution.value?.historical) return `断线前结果不能定时刷新；右键设置周期（${seconds} 秒）`;
  if (editors.active && (resultEdits.hasChanges(editors.active.id) || editors.active.resultChangesDirty)) {
    return `请先应用或撤销结果修改；右键设置周期（${seconds} 秒）`;
  }
  if (app.transportState !== "ready" || !activeConnected.value
      || editors.active?.connectionState === "credentials-required"
      || editors.active?.connectionState === "unavailable") {
    return `数据库连接可用后才能定时刷新；右键设置周期（${seconds} 秒）`;
  }
  if (activeDatabaseBusy.value) return `数据库正忙；右键设置周期（${seconds} 秒）`;
  return `定时刷新已关闭 · 每 ${seconds} 秒；左键开启，右键设置周期`;
});
const activeCompletionContext = computed(() => connections.completionContext(editors.active?.connection));
const activeCompletionKey = computed(() => activeCompletionContext.value?.key ?? "unbound");
const activeCompletionRevision = computed(() => {
  const cache = metadata.completionFor(activeCompletionKey.value);
  return [cache?.state ?? "empty", cache?.generatedAt ?? "", cache?.summary?.objectCount ?? 0,
    cache?.summary?.columnCount ?? 0, cache?.summary?.warning ?? ""].join(":");
});
const activeCompletionMetadataReady = computed(() => {
  const cache = metadata.completionFor(activeCompletionKey.value);
  return cache?.state === "ready" && !cache.summary?.warning;
});
const activeObjectTreeKey = computed(() => {
  const environmentId = editors.active?.connection?.environmentId;
  const systemId = connections.environments.find((item) => item.id === environmentId)?.systemId;
  return systemId && environmentId ? `${systemId}:${environmentId}` : "unbound";
});
const activeCompletionLoading = computed(() => metadata.completionFor(activeCompletionKey.value)?.state === "loading");
const completionCacheSize = computed(() => formatCompletionBytes(metadata.completionStats.estimatedBytes));
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
const connectionCascaderProps: CascaderProps = { emitPath: false };
const activeDatabaseBusy = computed(() => Boolean(editors.active?.busy || activeResultLoading.value));
const canExecute = computed(() => Boolean(activeConnected.value && !activeDatabaseBusy.value
  && !resultExecutionDecisionPending.value && app.transportState === "ready"));
const canTransformSql = computed(() => Boolean(activeConnected.value && !activeDatabaseBusy.value
  && app.transportState === "ready"));
const canEditSelection = computed(() => Boolean(editors.active && editorHasSelection.value));
const activeExecutionRunning = computed(() => Boolean(activeDatabaseBusy.value
  || (editors.active?.executionPhase && editors.active.executionPhase !== "idle")));
const activeCancellationPhase = computed(() => activeResultLoading.value?.phase
  ?? editors.active?.executionPhase ?? "idle");
const canCancelExecution = computed(() => app.transportState === "ready"
  && (activeResultLoading.value
    ? activeResultLoading.value.phase === "running"
    : Boolean(editors.active?.busy && editors.active.activeExecutionId
      && editors.active.executionPhase === "running")));
const cancelExecutionTooltip = computed(() => actionTooltip(activeCancellationPhase.value === "starting"
  ? "正在启动执行，获取执行编号后即可取消"
  : activeCancellationPhase.value === "cancelling" ? "已发送取消请求，正在等待数据库响应"
    : app.transportState !== "ready" ? "事件通道恢复后可取消执行" : "取消执行", "query.cancel"));
const hasActiveTransaction = computed(() => Boolean(editors.active?.transactionDirty
  && editors.active.transactionState === "active"));
const canOperateTransaction = computed(() => Boolean(hasActiveTransaction.value && !activeDatabaseBusy.value
  && editors.active?.executionPhase === "idle" && editors.active.transactionOperation === "idle"
  && activeConnected.value && app.transportState === "ready"));
const showResultEditActions = computed(() => Boolean(activeResult.value?.mutationTarget));
const activeResultEditSession = computed(() => {
  const tab = editors.active;
  const execution = activeExecution.value;
  const result = activeResult.value;
  return tab && execution && result
    ? resultEdits.session(tab.id, execution.executionId, result.resultIndex)
    : undefined;
});
const resultEditUnlocked = computed(() => activeResultEditSession.value?.unlocked === true);
const canToggleResultEdit = computed(() => Boolean(showResultEditActions.value
  && (activeResult.value?.mutationTarget?.mode === "editable"
    || activeResult.value?.mutationTarget?.editableForUpdate === true)
  && !settings.autoCommit && canOperateTransaction.value
  && !activeExecution.value?.historical));
const resultEditTooltip = computed(() => {
  const label = settings.autoCommit
    ? "请关闭自动提交后重新执行 FOR UPDATE"
    : activeExecution.value?.historical ? "断线前结果不可编辑"
      : activeResult.value?.mutationTarget?.mode !== "editable"
        && activeResult.value?.mutationTarget?.editableForUpdate !== true
        ? activeResult.value?.mutationTarget?.reason || "当前查询形态不支持编辑"
        : !hasActiveTransaction.value ? "事务已结束，请重新执行 FOR UPDATE"
          : resultEditUnlocked.value ? "退出编辑模式（不会提交或释放数据库锁）" : "进入结果编辑模式";
  return actionTooltip(label, "result.toggleEditMode");
});
const canPostResultChanges = computed(() => Boolean(editors.active && canOperateTransaction.value
  && activeResultEditSession.value && resultEdits.operations(activeResultEditSession.value).length > 0));
const postResultChangesTooltip = computed(() => canPostResultChanges.value
  ? "应用当前结果的草稿更改到未提交事务" : "没有待应用的本地草稿");
const transportStatusText = computed(() => app.transportState === "recovering" ? "正在恢复浏览器工作区…"
  : app.transportState === "connecting" ? "正在建立事件通道…"
    : app.transportState === "offline" ? "事件通道不可用" : "事件通道重连中…");
const connectionSessionText = computed(() => editors.active?.connectionState === "unbound" && editors.active?.connection ? "链接配置不可用"
    : !activeConnected.value ? "未选择链接"
    : editors.active?.connectionState === "credentials-required" ? "需要重新输入密码"
      : editors.active?.connectionState === "suspended" ? "链接已暂停"
        : `链接正常 · 自动提交${settings.autoCommit ? "开启" : "关闭"}`);
const activeExecutionText = computed(() => {
  const execution = activeExecution.value;
  if (activeResultLoading.value?.phase === "cancelling") return "正在取消…";
  if (activeResultLoading.value) return activeResultLoading.value.mode === "next"
    ? "正在加载下一页数据…" : "正在获取全部数据…";
  if (editors.active?.executionPhase === "cancelling") return "正在取消…";
  if (editors.active?.busy || execution?.busy) return "正在执行…";
  if (!execution) return "尚未执行 SQL";
  return `${execution.cancelled ? "执行已取消" : execution.failed ? "执行失败" : "执行完成"} · ${execution.durationMs} ms`;
});
const activeTransactionText = computed(() => editors.active?.transactionState === "disconnected-protected" ? "事务断连保护中"
  : editors.active?.transactionDirty ? "未提交事务"
    : editors.active?.transactionState === "auto-rolled-back" ? "上次事务已回滚"
      : editors.active?.transactionState === "lost" ? "上次事务状态未知" : undefined);
const systemStatusItems = computed<StatusBarSystemItem[]>(() => {
  const connectionState = editors.active?.connectionState;
  const values: StatusBarSystemItem[] = [{
    id: "system:session",
    label: "数据库会话",
    message: connectionSessionText.value,
    tone: connectionState === "unbound" && editors.active?.connection ? "error"
      : connectionState === "credentials-required" || connectionState === "suspended" ? "warning"
        : activeConnected.value ? "success" : "neutral",
    updatedAt: 0
  }];
  if (activeTransactionText.value) {
    values.push({ id: "system:transaction", label: "事务状态", message: activeTransactionText.value,
      tone: "warning", updatedAt: 1 });
  }
  for (const task of Object.values(statusBar.tasks)) {
    values.push({ id: task.id, label: task.label, message: task.message, tone: task.state,
      updatedAt: task.updatedAt, dismissible: task.dismissible });
  }
  for (const cache of Object.values(metadata.completionCaches)) {
    if (!cache.notice) continue;
    values.push({ id: `completion:${cache.key}`, label: `${cache.label} SQL补全`,
      message: cache.error || completionMessage(cache),
      tone: cache.state === "loading" ? "running" : cache.state === "error" ? "error" : "success",
      updatedAt: cache.startedAt ?? 0, dismissible: cache.state === "error" });
  }
  if (app.transportState !== "ready") values.push({ id: "system:transport", label: "事件通道",
    message: transportStatusText.value, tone: app.transportState === "offline" ? "error" : "running",
    updatedAt: Number.MAX_SAFE_INTEGER });
  return values;
});
const panelVisible = computed(() => panelOpen.value && numericPanelWidth(leftWidth.value) > 0);
const disposers: Array<() => void> = [];
const colorSchemeQuery = window.matchMedia?.("(prefers-color-scheme: dark)");
let layoutSaveTimer: number | undefined;
let resultContentResizeObserver: ResizeObserver | undefined;
const completionNoticeTimers = new Map<string, number>();
const executionNotifications = createExecutionNotificationScheduler({
  now: () => Date.now(),
  setTimeout: (callback, delayMs) => window.setTimeout(callback, delayMs),
  clearTimeout: (timer) => window.clearTimeout(timer),
  getThresholds: () => settings.executionWarningMinutes,
  isBackground: (editorId) => editors.activeId !== editorId,
  isRunning: (editorId, executionId) => {
    const tab = editors.tabs.find((item) => item.id === editorId);
    return Boolean(tab?.busy && tab.activeExecutionId === executionId);
  },
  getEditorTitle: (editorId) => editors.tabs.find((item) => item.id === editorId)?.title,
  notify: (request) => ElNotification(request),
  openExecution: openExecutionResult
});
let confirmedShortcutBindings: ShortcutBindings = { ...DEFAULT_SHORTCUT_BINDINGS };
let shortcutSaveQueue: Promise<void> = Promise.resolve();
let shortcutSaveEpoch = 0;
let shortcutSaveCount = 0;
let confirmedCompletionPreciseMatchingEnabled = false;
let completionPreciseMatchingSaveQueue: Promise<void> = Promise.resolve();
let confirmedCompletionSnippets: SqlCompletionSnippet[] = [];
let completionSnippetSaveQueue: Promise<void> = Promise.resolve();
let completionSnippetSaveEpoch = 0;
let completionSnippetSaveCount = 0;
let confirmedExecutionWarningMinutes = [...settings.executionWarningMinutes];
let executionWarningSaveQueue: Promise<void> = Promise.resolve();
let executionWarningSaveEpoch = 0;

watch(leftWidth, (value) => {
  const width = numericPanelWidth(value);
  if (width > 0) lastLeftWidth.value = width;
});
watch(resultContentPanel, async () => {
  await nextTick();
  observeResultContentPanel();
}, { flush: "post" });

onMounted(async () => {
  window.addEventListener("keydown", handleShortcut, true);
  if (typeof ResizeObserver !== "undefined") {
    resultContentResizeObserver = new ResizeObserver(measureResultContentOffset);
  }
  observeResultContentPanel();
  app.setSystemTheme(colorSchemeQuery?.matches ? "dark" : "light");
  colorSchemeQuery?.addEventListener?.("change", systemThemeChanged);
  installEventHandlers();
  workspaceLoading.value = true;
  try {
    await rpc.ready();
    await refreshWorkspaces();
  } catch (error) {
    app.status = "启动失败";
    ElNotification.error({ title: "DBStudio 启动失败", message: message(error), duration: 0 });
  } finally { workspaceLoading.value = false; }
  window.addEventListener("pagehide", flushDrafts);
  window.addEventListener("resize", measureResultContentOffset);
});
onBeforeUnmount(() => {
  disposers.forEach((dispose) => dispose());
  resultContentResizeObserver?.disconnect();
  colorSchemeQuery?.removeEventListener?.("change", systemThemeChanged);
  window.removeEventListener("keydown", handleShortcut, true);
  window.removeEventListener("pagehide", flushDrafts);
  window.removeEventListener("resize", measureResultContentOffset);
  if (layoutSaveTimer !== undefined) window.clearTimeout(layoutSaveTimer);
  clearAutoRefreshTimer();
  clearPendingResultBatches();
  resultReloadRequired.clear();
  for (const pending of pendingLoadAll.values()) pending.reject(new Error("结果加载已结束"));
  pendingLoadAll.clear();
  executionNotifications.clear();
  executionAttention.clear();
  completionNoticeTimers.forEach((timer) => window.clearTimeout(timer));
  draftSaveTimers.forEach((timer) => window.clearTimeout(timer));
  draftSaveTimers.clear();
  draftWorkspaceEpoch += 1;
  draftPendingEditors.clear();
  draftGenerations.clear();
  draftSaveQueues.clear();
  executionTimelineStageTimers.forEach((timer) => window.clearTimeout(timer));
  executionTimelineStageTimers.clear();
  executionTimelineSessions.clear();
});

watch(() => app.theme, (theme) => applyDocumentTheme(theme), { immediate: true });
watch([() => app.theme, () => settings.colorSchemes], ([theme, schemes]) => {
  applyColorSchemeCss(theme, schemes[theme]);
}, { deep: true, immediate: true });
watch(activeExecutions, (items) => {
  const key = String(activeResultIndex.value);
  const selectedExists = items.some((execution) => execution.executionId === key
    || execution.results.some((result) => `${execution.executionId}:${result.resultIndex}` === key)
    || (items.length === 1 && execution.results.some((result) => result.resultIndex === activeResultIndex.value)));
  if (!selectedExists) activeResultIndex.value = items.length ? items[items.length - 1].executionId : 0;
  selectedResultColumn.value = undefined;
  selectedResultRowCount.value = 0;
  selectedResultStatusText.value = "";
});
watch(() => [editors.activeId, activeObjectTreeKey.value, activeCompletionKey.value] as const, () => {
  metadata.activate(activeObjectTreeKey.value, activeCompletionKey.value);
});
watch(() => editors.activeId, (current, previous) => {
  if (current) executionAttention.markViewed(current);
  if (autoRefreshState.value && current !== autoRefreshState.value.editorId) {
    stopAutoRefresh("已切换查询标签", true);
  }
  editorHasSelection.value = false;
  selectedResultColumn.value = undefined;
  selectedResultRowCount.value = 0;
  selectedResultStatusText.value = "";
  if (previous) void persistDraftById(previous, true);
  if (current) scheduleDraft(current);
  void nextTick().then(() => syncResultHighlight());
});
watch(activeResultIndex, () => {
  if (autoRefreshState.value && !autoRefreshMatchesActiveResult()) {
    stopAutoRefresh("已切换结果页签", true);
  }
  selectedResultColumn.value = undefined;
  selectedResultRowCount.value = 0;
  selectedResultStatusText.value = "";
  void nextTick().then(() => syncResultHighlight());
});
watch(() => [activeExecution.value?.executionId, activeResult.value?.resultIndex,
  activeResult.value?.sourceStartOffset, activeResult.value?.sourceEndOffset,
  activeResult.value?.sql] as const, () => void nextTick().then(() => syncResultHighlight()));
watch(() => settings.showSelectedColumnRemarks, (enabled) => {
  if (!enabled) selectedResultColumn.value = undefined;
});
watch(() => settings.executionWarningMinutes, () => executionNotifications.reschedule(), { deep: true });
watch(() => {
  const state = autoRefreshState.value;
  if (!state) return undefined;
  const tab = editors.tabs.find((item) => item.id === state.editorId);
  return [app.transportState, tab?.connectionState, tab?.resultChangesDirty,
    resultEdits.hasChanges(state.editorId)] as const;
}, (status) => {
  if (!autoRefreshState.value || !status) return;
  const [transport, connectionState, resultChangesDirty, pendingChanges] = status;
  if (resultChangesDirty || pendingChanges) stopAutoRefresh("结果存在待应用的修改", true);
  else if (transport !== "ready" || connectionState === "unbound" || connectionState === "unavailable"
      || connectionState === "credentials-required") stopAutoRefresh("数据库连接已不可用", true);
});
watch(settingsDrawer, (open) => {
  if (!open) {
    shortcutDrawer.value = false;
    completionSnippetDrawer.value = false;
    appearanceDrawer.value = false;
  }
});

function systemThemeChanged(event: MediaQueryListEvent): void {
  app.setSystemTheme(event.matches ? "dark" : "light");
}

function resultContentElement(): HTMLElement | undefined {
  return resultContentPanel.value;
}

function observeResultContentPanel(): void {
  resultContentResizeObserver?.disconnect();
  const element = resultContentElement();
  if (!element) {
    resultContentOffset.value = 0;
    return;
  }
  resultContentResizeObserver?.observe(element);
  measureResultContentOffset();
}

function measureResultContentOffset(): void {
  const element = resultContentElement();
  resultContentOffset.value = element ? Math.max(0, Math.round(element.getBoundingClientRect().left)) : 0;
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
  app.setTransportState(rpc.transportState);
  disposers.push(rpc.on("transport.state", (raw) => {
    const state = (raw as { state: TransportState }).state;
    const previous = app.transportState;
    app.setTransportState(state);
    let interruptedResultLoad = false;
    if (state !== "ready" && resultLoading.value?.mode === "all") {
      const loading = resultLoading.value;
      resultLoading.value = { ...loading, phase: "cancelling", cancelRequested: true };
      resultReloadRequired.add(resultReloadKey(loading.editorId, loading.resultExecutionId, loading.resultIndex));
      rejectPendingLoadAll(loading.editorId, loading.executionId,
        new Error("事件通道已断开，请重新执行查询"));
      interruptedResultLoad = true;
    }
    if (interruptedResultLoad) app.status = "事件通道已断开，本次获取已停止，请重新执行查询";
    else if (state === "reconnecting") app.status = "事件通道重连中…";
    else if (state === "recovering") app.status = "正在恢复浏览器工作区…";
    else if (state === "offline") app.status = "事件通道暂时不可用";
    else if (state === "ready" && previous === "reconnecting") app.status = "事件通道已恢复";
  }));
  disposers.push(rpc.on("workspace.ready", (raw) => {
    const data = raw as { editors?: Array<{ editorId: string; busy: boolean; transactionDirty: boolean;
      resultChangesDirty?: boolean;
      transactionState: EditorTab["transactionState"]; connectionState: EditorConnectionState;
      activeExecutionId?: string | null }> };
    for (const state of data.editors ?? []) {
      const tab = editors.tabs.find((item) => item.id === state.editorId); if (!tab) continue;
      const previousExecutionId = tab.activeExecutionId;
      if (tab.busy && !state.busy) queries.markHistorical(tab.id);
      if (state.busy) restoreExecutionTimelineSession(tab.id, state.activeExecutionId ?? undefined);
      else clearExecutionTimelineSession(tab.id);
      editors.patch(tab.id, { busy: state.busy, transactionDirty: state.transactionDirty,
        resultChangesDirty: state.resultChangesDirty,
        transactionState: state.transactionState, connectionState: state.connectionState,
        activeExecutionId: state.activeExecutionId ?? undefined,
        executionPhase: state.busy && state.activeExecutionId ? "running" : state.busy ? "starting" : "idle",
        executionStartedAt: state.busy ? tab.executionStartedAt ?? Date.now() : undefined,
        executionTimelineStage: state.busy
          ? tab.executionTimelineStage ?? (state.activeExecutionId ? "preparing-result" : "thinking")
          : undefined,
        transactionOperation: "idle" });
      if (state.busy && state.activeExecutionId) {
        executionNotifications.start({ editorId: tab.id, executionId: state.activeExecutionId,
          startedAt: tab.executionStartedAt, editorTitle: tab.title });
      } else if (previousExecutionId) {
        executionNotifications.clearExecution(previousExecutionId);
      }
    }
  }));
  disposers.push(rpc.on("query.started", (raw) => {
    const data = raw as { editorId: string; executionId: string; resultPresentation?: "replace" | "append";
      statements?: QueryExecutionSource[]; displayType?: "data" | "execution-plan" };
    const tab = editors.tabs.find((item) => item.id === data.editorId);
    if (tab?.activeExecutionId && tab.activeExecutionId !== data.executionId) return;
    const timelineSession = executionTimelineSessions.get(data.editorId);
    if (tab?.busy && tab.executionPhase !== "cancelling" && timelineSession) {
      if (timelineSession.executionId && timelineSession.executionId !== data.executionId) return;
      if (!timelineSession.executionId && !tab.activeExecutionId
          && queries.execution(data.editorId, data.executionId)) return;
      timelineSession.executionId = data.executionId;
    }
    executionAttention.clearExecution(data.editorId, executionRequestFailureKey(data.editorId));
    executionAttention.clearExecution(data.editorId, data.executionId);
    const presentation = data.resultPresentation ?? "replace";
    const pendingLoading = resultLoading.value;
    if (pendingLoading?.editorId === data.editorId) {
      rejectPendingLoadAll(data.editorId, pendingLoading.executionId,
        new Error("结果执行已被替换"));
      resultLoading.value = undefined;
    }
    for (const key of [...resultReloadRequired]) if (key.startsWith(`${data.editorId}:`)) resultReloadRequired.delete(key);
    clearPendingResultBatches(data.editorId);
    const autoRefresh = autoRefreshState.value;
    if (autoRefresh?.refreshing && autoRefresh.editorId === data.editorId && presentation === "replace") {
      autoRefresh.executionId = data.executionId;
      autoRefresh.resultIndex = 0;
    }
    if (presentation === "replace") {
      resultEdits.finishEditor(data.editorId);
      for (const execution of queries.executionList(data.editorId)) {
        monacoEditor.value?.releaseExecutionSources?.(execution.executionId);
      }
    }
    queries.start(data.editorId, data.executionId, presentation, data.displayType);
    if (editors.activeId === data.editorId) activeResultIndex.value = data.executionId;
    if (data.statements?.length) {
      monacoEditor.value?.registerExecutionSources?.(data.executionId, data.statements, data.editorId);
    }
    if (tab) {
      executionNotifications.start({ editorId: data.editorId, executionId: data.executionId,
        startedAt: tab.executionStartedAt, editorTitle: tab.title });
    }
    if (tab?.busy && tab.executionPhase !== "cancelling") {
      editors.patch(data.editorId, { activeExecutionId: data.executionId,
        executionStartedAt: tab.executionStartedAt ?? Date.now(), executionPhase: "running",
        resultChangesDirty: presentation === "replace" ? false : tab.resultChangesDirty,
        transactionState: presentation === "replace" ? "none" : tab.transactionState });
    }
  }));
  disposers.push(rpc.on("query.pageStarted", (raw) => {
    const data = raw as { editorId: string; executionId: string; resultExecutionId?: string; resultIndex: number };
    const loading = resultLoading.value;
    if (!loading || loading.editorId !== data.editorId || loading.executionId !== data.executionId
      || loading.resultIndex !== data.resultIndex
      || (data.resultExecutionId && loading.resultExecutionId !== data.resultExecutionId)) return;
    if (loading.phase === "cancelling") {
      void rpc.request("query.cancel", { editorId: loading.editorId, executionId: loading.executionId }).catch(() => { });
      return;
    }
    resultLoading.value = { ...loading, phase: "running" };
  }));
  disposers.push(rpc.on("query.pageRows", (raw) => {
    const data = raw as { editorId: string; executionId: string; resultExecutionId: string;
      resultIndex: number; offset: number; rows: Array<Array<string | null>>; rowIds?: string[] };
    const loading = resultLoading.value;
    if (!loading || loading.editorId !== data.editorId || loading.executionId !== data.executionId
      || loading.resultIndex !== data.resultIndex || loading.resultExecutionId !== data.resultExecutionId) return;
    if (loading.phase === "cancelling"
        && resultReloadRequired.has(resultReloadKey(loading.editorId, loading.resultExecutionId, loading.resultIndex))) return;
    if (!queries.execution(data.editorId, data.resultExecutionId)) return;
    const key = pendingResultKey(data.editorId, data.executionId, data.resultIndex);
    const seen = seenPageBatches.get(key) ?? new Set<number>();
    if (seen.has(data.offset)) return;
    const pending = pendingResultBatches.get(key);
    const currentRows = queries.execution(data.editorId, data.resultExecutionId)?.results
      .find((result) => result.resultIndex === data.resultIndex)?.rows.length ?? 0;
    const expected = currentRows + (pending?.rows.length ?? 0);
    if (data.offset !== expected) {
      resultReloadRequired.add(resultReloadKey(loading.editorId, loading.resultExecutionId, loading.resultIndex));
      resultLoading.value = { ...loading, phase: "cancelling", cancelRequested: true };
      rejectPendingLoadAll(data.editorId, data.executionId,
        new Error("结果加载位置已变化，请重新执行查询"));
      void rpc.request("query.cancel", { editorId: data.editorId, executionId: data.executionId }).catch(() => { });
      ElMessage.warning("结果加载位置已变化，请重新执行查询");
      return;
    }
    const publishImmediately = seen.size === 0;
    seen.add(data.offset); seenPageBatches.set(key, seen);
    enqueueResultRows(data.editorId, data.resultIndex, data.rows, data.rowIds,
      data.executionId, data.resultExecutionId, data.offset, publishImmediately);
  }));
  disposers.push(rpc.on("query.pageComplete", (raw) => {
    const data = raw as { editorId: string; executionId: string; resultExecutionId: string;
      resultIndex: number; nextOffset: number; complete?: boolean; cancelled?: boolean; errorMessage?: string };
    const loading = resultLoading.value;
    if (!loading || loading.editorId !== data.editorId || loading.executionId !== data.executionId
      || loading.resultIndex !== data.resultIndex || loading.resultExecutionId !== data.resultExecutionId) return;
    const reloadKey = resultReloadKey(loading.editorId, loading.resultExecutionId, loading.resultIndex);
    if (loading.phase === "cancelling" && resultReloadRequired.has(reloadKey)) {
      clearPendingResultBatches(data.editorId, data.executionId, data.resultIndex);
      resultLoading.value = undefined;
      return;
    }
    if (!queries.execution(data.editorId, data.resultExecutionId)) {
      rejectPendingLoadAll(data.editorId, data.executionId,
        new Error("查询结果已关闭，请重新执行查询"));
      resultLoading.value = undefined;
      return;
    }
    flushPendingResultBatches(data.editorId, data.executionId, data.resultIndex);
    seenPageBatches.delete(pendingResultKey(data.editorId, data.executionId, data.resultIndex));
    const hasMore = Boolean(data.cancelled || data.errorMessage || data.complete === false);
    if (!hasMore) resultReloadRequired.delete(reloadKey);
    queries.completeResult(data.editorId, data.resultIndex, { truncated: hasMore }, data.resultExecutionId);
    resultLoading.value = undefined;
    const pending = pendingLoadAll.get(data.executionId);
    if (pending) {
      pendingLoadAll.delete(data.executionId);
      if (data.errorMessage) pending.reject(new Error(data.errorMessage)); else pending.resolve();
    }
    app.status = data.errorMessage ? `获取全部结果失败：${data.errorMessage}`
      : data.cancelled ? `数据加载已取消 · 已保留 ${data.nextOffset} 行`
        : `已获取全部 ${data.nextOffset} 行`;
  }));
  disposers.push(rpc.on("query.resultMeta", (raw) => {
    const data = raw as QueryResult & { editorId: string; executionId?: string };
    if (data.executionId && !queries.execution(data.editorId, data.executionId)) return;
    const tab = editors.tabs.find((item) => item.id === data.editorId);
    if (data.executionId && tab?.activeExecutionId && tab.activeExecutionId !== data.executionId) return;
    const timelineSession = executionTimelineSessions.get(data.editorId);
    if (tab?.busy && data.executionId && !matchExecutionTimelineEvent(data.editorId, data.executionId)) return;
    if (tab?.busy && data.executionId && timelineSession && !timelineSession.executionId && !tab.activeExecutionId) return;
    if (tab?.busy && timelineSession && data.executionId) {
      timelineSession.executionId ??= data.executionId;
    }
    if (tab?.busy && tab.executionPhase !== "cancelling"
        && (!data.executionId || tab.activeExecutionId === data.executionId)) {
      const firstResultMeta = !timelineSession?.resultMetaReceived;
      if (timelineSession) {
        timelineSession.resultMetaReceived = true;
        timelineSession.pendingStages = [];
      }
      clearExecutionTimelineStageTimer(data.editorId);
      if (firstResultMeta) advanceExecutionTimeline(data.editorId, "success", data.executionId);
    }
    queries.addResult(data.editorId, { ...data, rows: [], rowIds: [], complete: false }, data.executionId);
    void resolveResultColumnRemarks(data.editorId, data.executionId, data.resultIndex, data.sql, data.columnDetails);
  }));
  disposers.push(rpc.on("query.rows", (raw) => {
    const data = raw as { editorId: string; executionId?: string; resultIndex: number;
      rows: Array<Array<string | null>>; rowIds?: string[] };
    if (data.executionId && !queries.execution(data.editorId, data.executionId)) return;
    const tab = editors.tabs.find((item) => item.id === data.editorId);
    if (data.executionId && tab?.busy && tab.activeExecutionId && tab.activeExecutionId !== data.executionId) return;
    enqueueResultRows(data.editorId, data.resultIndex, data.rows, data.rowIds,
      data.executionId ?? "", data.executionId ?? "");
  }));
  disposers.push(rpc.on("query.resultComplete", (raw) => {
    const data = raw as { editorId: string; executionId?: string; resultIndex: number } & Partial<QueryResult>;
    if (data.executionId && !queries.execution(data.editorId, data.executionId)) return;
    const tab = editors.tabs.find((item) => item.id === data.editorId);
    if (data.executionId && tab?.busy && tab.activeExecutionId && tab.activeExecutionId !== data.executionId) return;
    flushPendingResultBatches(data.editorId, data.executionId ?? "", data.resultIndex);
    if (data.errorMessage) {
      clearExecutionTimelineSession(data.editorId);
      if (tab?.executionTimelineStage === "success") editors.patch(data.editorId, { executionTimelineStage: undefined });
    }
    queries.completeResult(data.editorId, data.resultIndex, data, data.executionId);
  }));
  disposers.push(rpc.on("query.executionComplete", (raw) => {
    const data = raw as { editorId: string; executionId: string; cancelled: boolean; failed: boolean;
      durationMs: number; transactionDirty: boolean; resultChangesDirty?: boolean; terminationReason?: string };
    flushPendingResultBatches(data.editorId, data.executionId);
    const tab = editors.tabs.find((item) => item.id === data.editorId);
    executionNotifications.complete(data);
    if (!tab || tab.activeExecutionId !== data.executionId) return;
    const outcome: ExecutionAttentionOutcome = data.cancelled || data.failed
      || data.terminationReason === "connection-aborted" ? "error" : "success";
    if (editors.activeId === data.editorId) executionAttention.clearExecution(data.editorId, data.executionId);
    else executionAttention.markUnread(data.editorId, data.executionId, outcome);
    const completedResults = queries.execution(data.editorId, data.executionId)?.results
      .filter((result) => result.complete && !result.errorMessage) ?? [];
    clearExecutionTimelineStageTimer(data.editorId);
    const timelineSession = executionTimelineSessions.get(data.editorId);
    if (!timelineSession || !timelineSession.executionId || timelineSession.executionId === data.executionId) {
      executionTimelineSessions.delete(data.editorId);
    }
    queries.complete(data.editorId, data, data.executionId);
    if (data.terminationReason === "connection-aborted") queries.markHistorical(data.editorId, data.executionId);
    editors.patch(data.editorId, {
      busy: false, transactionDirty: data.transactionDirty, resultChangesDirty: data.resultChangesDirty,
      transactionState: data.transactionDirty ? "active" : "none",
      activeExecutionId: undefined, executionStartedAt: undefined, executionPhase: "idle",
      executionTimelineStage: undefined
    });
    if (!data.cancelled && !data.failed) {
      for (const result of completedResults) void enrichCompletionStructure(data.editorId, data.executionId, result.resultIndex);
    }
    scheduleDraft(data.editorId);
    app.status = data.terminationReason === "connection-aborted" ? "连接已被任务管理器强制断开"
      : `${data.cancelled ? "执行已取消" : data.failed ? "执行失败" : "执行完成"} · ${data.durationMs} ms`;
    const autoRefresh = autoRefreshState.value;
    if (autoRefresh?.refreshing && autoRefresh.editorId === data.editorId
        && autoRefresh.executionId === data.executionId) {
      const queryResult = completedResults.find((result) => result.columns.length > 0);
      if (data.cancelled || data.failed || data.terminationReason === "connection-aborted" || !queryResult) {
        stopAutoRefresh(data.cancelled ? "执行已取消"
          : data.terminationReason === "connection-aborted" ? "数据库连接已断开" : "执行失败", true);
      } else {
        autoRefresh.resultIndex = queryResult.resultIndex;
        autoRefresh.refreshing = false;
        scheduleAutoRefresh();
      }
    }
  }));
  disposers.push(rpc.on("jdbc.connectionAborted", (raw) => {
    const data = raw as { editorId: string; executionId?: string; transactionLost?: boolean;
      resultChangesLost?: boolean; message?: string };
    const abortedExecutionId = data.executionId
      ?? editors.tabs.find((item) => item.id === data.editorId)?.activeExecutionId;
    const tab = editors.tabs.find((item) => item.id === data.editorId);
    if (abortedExecutionId) executionNotifications.abort(data.editorId, abortedExecutionId);
    if (abortedExecutionId && tab) {
      if (editors.activeId === data.editorId) executionAttention.clearExecution(data.editorId, abortedExecutionId);
      else executionAttention.markUnread(data.editorId, abortedExecutionId, "error");
    }
    if (autoRefreshState.value?.editorId === data.editorId) stopAutoRefresh("数据库连接已断开", true);
    resultEdits.finishEditor(data.editorId);
    queries.markHistorical(data.editorId);
    const loading = resultLoading.value?.editorId === data.editorId ? resultLoading.value : undefined;
    if (loading) resultReloadRequired.add(resultReloadKey(loading.editorId, loading.resultExecutionId, loading.resultIndex));
    if (loading) resultLoading.value = undefined;
    rejectPendingLoadAll(data.editorId, undefined, new Error(data.message ?? "数据库连接已断开"));
    clearExecutionTimelineSession(data.editorId);
    editors.patch(data.editorId, { busy: false, activeExecutionId: undefined, executionStartedAt: undefined,
      executionPhase: "idle",
      executionTimelineStage: undefined,
      transactionOperation: "idle", transactionDirty: false, resultChangesDirty: false,
      transactionState: data.transactionLost ? "lost" : "none", connectionState: "ready" });
    scheduleDraft(data.editorId);
    ElNotification.warning({ title: "JDBC 连接已强制断开",
      message: data.message ?? "旧连接已丢弃，下次执行时将建立新连接" });
  }));
  disposers.push(rpc.on("transaction.status", (raw) => {
    const data = raw as { editorId: string; dirty: boolean; resultChangesDirty?: boolean; message: string };
    editors.patch(data.editorId, { transactionDirty: data.dirty, resultChangesDirty: data.resultChangesDirty,
      transactionState: data.dirty ? "active" : "none" });
    scheduleDraft(data.editorId); app.status = data.message;
  }));
  disposers.push(rpc.on("task.started", (raw) => {
    const data = raw as { taskId: string; kind: string; message?: string };
    if (data.taskId) statusBar.start(data.taskId, data.kind || "background", data.message);
  }));
  disposers.push(rpc.on("task.progress", (raw) => {
    const data = raw as { taskId: string; kind?: string; message?: string; rows?: number; completed?: number; total?: number };
    if (data.taskId) statusBar.progress(data.taskId, data.kind || "background", data.message || "正在处理…",
      data.completed ?? data.rows, data.total);
  }));
  disposers.push(rpc.on("task.completed", (raw) => {
    const data = raw as { taskId: string; kind: string; result?: { rows?: number }; error?: { message?: string } };
    if (!data.taskId) return;
    const success = data.result?.rows === undefined ? undefined : `已处理 ${data.result.rows} 行`;
    statusBar.complete(data.taskId, data.kind || "background", data.error?.message, success);
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
  disposers.push(rpc.on("transaction.autoRolledBack", (raw) => {
    const data = raw as { message?: string };
    for (const tab of editors.tabs) if (tab.transactionDirty) {
      editors.patch(tab.id, { transactionDirty: false, transactionState: "auto-rolled-back" });
      scheduleDraft(tab.id);
    }
    ElNotification.warning({ title: "事务已自动回滚", message: data.message ?? "断连事务已超过保护时间并自动回滚" });
  }));
}

async function refreshWorkspaces(): Promise<void> {
  workspaceLoading.value = true;
  try { workspaceCatalog.value = await rpc.listWorkspaces(); }
  finally { workspaceLoading.value = false; }
}

function showReleaseNotesIfNeeded(): void {
  const pending = getPendingReleaseNotes(RELEASE_NOTES_RELEASES)
    .filter((release) => !releaseNotesDismissedInSession.has(release.version));
  pendingReleaseNotes.value = pending;
  if (!pending.length) {
    releaseNotesVisible.value = false;
    return;
  }
  void nextTick().then(() => {
    if (workspaceOpened.value && pendingReleaseNotes.value.length > 0) releaseNotesVisible.value = true;
  });
}

function dismissReleaseNotes(): void {
  const pending = pendingReleaseNotes.value;
  pending.forEach((release) => releaseNotesDismissedInSession.add(release.version));
  markReleaseNotesBatchSeen(pending);
  pendingReleaseNotes.value = [];
  releaseNotesVisible.value = false;
}

async function createWorkspace(name: string): Promise<void> {
  try {
    const created = await rpc.createWorkspace(name);
    await refreshWorkspaces();
    await openWorkspace(created);
  } catch (error) { reportError(error); }
}

async function renameWorkspace(workspace: WorkspaceSummary, name: string): Promise<void> {
  try { await rpc.renameWorkspace(workspace.id, name); await refreshWorkspaces(); }
  catch (error) { reportError(error); }
}

async function deleteWorkspace(workspace: WorkspaceSummary): Promise<void> {
  try {
    await ElMessageBox.confirm(`删除“${workspace.name}”？该空间保存的编辑器检查点和恢复草稿将不再可用。`, "删除工作空间", {
      type: "warning", confirmButtonText: "删除", cancelButtonText: "取消"
    });
    await rpc.deleteWorkspace(workspace.id); await refreshWorkspaces();
  } catch (error) { if (error !== "cancel" && error !== "close") reportError(error); }
}

async function openWorkspace(workspace: WorkspaceSummary): Promise<void> {
  workspaceLoading.value = true;
  try {
    let opened = await rpc.openWorkspace(workspace.id);
    if (opened.recoveryDecisionRequired) {
      const decision = await askRecoveryDecision(opened);
      if (decision === "return") {
        await rpc.closeWorkspace(); await refreshWorkspaces(); return;
      }
      opened = await rpc.resolveWorkspaceRecovery(decision);
    }
    currentWorkspace.value = opened.workspace ?? workspace;
    workspaceOpened.value = true;
    await bootstrapWorkspace(opened.editors ?? []);
    if (opened.transactionRolledBack || opened.processRestarted && (opened.recovery?.transactionCount ?? 0) > 0) {
      ElNotification.warning({ title: "事务未恢复", message: "上次未提交事务已随数据库连接断开而回滚；SQL内容已恢复。", duration: 0 });
    }
    showReleaseNotesIfNeeded();
  } catch (error) {
    await rpc.closeWorkspace().catch(() => undefined);
    if ((error as { code?: string }).code === "WORKSPACE_IN_USE") await refreshWorkspaces();
    reportError(error);
  } finally { workspaceLoading.value = false; }
}

async function askRecoveryDecision(opened: WorkspaceOpenResponse): Promise<"restore" | "discard" | "return"> {
  const summary = opened.recovery;
  const details = `${summary?.unsavedEditorCount ?? 0} 个未保存标签${summary?.transactionCount ? `，${summary.transactionCount} 个事务状态` : ""}`;
  try {
    await ElMessageBox({
      title: "似乎上次还有些东西遗漏了，是否要恢复？",
      message: `检测到 ${details}。恢复只包含编辑器内容，不恢复查询结果。`, type: "warning",
      showCancelButton: true, showClose: true, distinguishCancelAndClose: true,
      confirmButtonText: "恢复上次内容", cancelButtonText: "放弃并打开", closeOnClickModal: false
    });
    return "restore";
  } catch (choice) { return choice === "cancel" ? "discard" : "return"; }
}

async function bootstrapWorkspace(recovered: RecoveredEditor[]): Promise<void> {
  app.loading = true;
  draftWorkspaceEpoch += 1;
  draftSaveTimers.forEach((timer) => window.clearTimeout(timer));
  draftSaveTimers.clear();
  draftPendingEditors.clear();
  draftGenerations.clear();
  draftSaveQueues.clear();
  try {
    const data = await rpc.request<BootstrapResponse>("app.bootstrap");
    connections.initialize(data.providers, data.profiles, data.systems ?? [], data.environments ?? []);
    settings.initialize(data.settings, data.recentFiles);
    confirmedShortcutBindings = { ...settings.shortcuts };
    confirmedCompletionPreciseMatchingEnabled = settings.completionPreciseMatchingEnabled;
    confirmedCompletionSnippets = settings.completionSnippets.map((item) => ({ ...item }));
    confirmedExecutionWarningMinutes = [...settings.executionWarningMinutes];
    void refreshCompletionStats();
    for (const recent of await recentSqlFiles().catch(() => [])) {
      recentHandles.set(recent.name, recent.handle);
      if (!settings.recentFiles.includes(recent.name)) settings.recentFiles.push(recent.name);
    }
    app.applyBootstrap(data);
    leftWidth.value = Number(data.settings["layout.leftWidth"] ?? 248);
    editorHeight.value = data.settings["layout.editorHeight"] ?? "62%";
    for (const tab of editors.tabs) {
      for (const execution of queries.executionList(tab.id)) {
        monacoEditor.value?.releaseExecutionSources?.(execution.executionId);
      }
    }
    executionNotifications.clear();
    executionAttention.clear();
    executionTimelineStageTimers.forEach((timer) => window.clearTimeout(timer));
    executionTimelineStageTimers.clear();
    executionTimelineSessions.clear();
    editors.clear(); queries.clear(); resultEdits.clear(); statusBar.clear(); resultLoading.value = undefined;
    clearPendingResultBatches();
    resultReloadRequired.clear();
    for (const pending of pendingLoadAll.values()) pending.reject(new Error("工作区已切换"));
    pendingLoadAll.clear();
    monacoEditor.value?.clearResultHighlight?.();
    for (const value of [...recovered].sort((left, right) => left.sortOrder - right.sortOrder)) {
      editors.add({ id: value.id, title: value.title, content: value.content, filePath: value.filePath,
        dirty: value.dirty, transactionDirty: value.transactionState === "active" || value.transactionState === "disconnected-protected",
        transactionState: value.transactionState, busy: false, executionPhase: "idle",
        transactionOperation: "idle", connection: value.connection,
        connectionState: value.connectionState });
    }
    const active = recovered.find((item) => item.active);
    if (active) editors.activeId = active.id;
    if (!editors.tabs.length) await newEditor();
    else if (editors.active?.connection) void ensureCompletionForEditor(editors.active);
    activeTool.value = editors.active?.connection ? "objects" : "connections";
    app.status = recovered.length ? `已打开 ${currentWorkspace.value?.name ?? "工作空间"} · 已恢复编辑器内容` : `已打开 ${currentWorkspace.value?.name ?? "工作空间"}`;
  } finally { app.loading = false; }
}

async function resolveResultColumnRemarks(editorId: string, executionId: string | undefined, resultIndex: number, sql: string,
                                          columnDetails: QueryResult["columnDetails"]): Promise<void> {
  const resolvedExecutionId = executionId ?? queries.executions[editorId]?.executionId;
  const tab = editors.tabs.find((item) => item.id === editorId);
  const context = connections.completionContext(tab?.connection);
  if (!resolvedExecutionId || !tab?.connection || !context
      || !columnDetails?.some((column) => !column.remarks && column.name)) return;
  try {
    const resolved = await completionClient.resolveResultColumnRemarks(context.key, tab.connection.providerId,
      sql, columnDetails.flatMap((column, index) => column.remarks || !column.name ? [] : [{
        index, catalog: column.catalog, schema: column.schema, table: column.table, name: column.name
      }]));
    queries.applyColumnRemarks(editorId, resolvedExecutionId, resultIndex, resolved);
  } catch {
    // 字段备注是可选展示信息；缓存不可用或损坏不能影响查询结果。
  }
}

async function enrichCompletionStructure(editorId: string, executionId: string, resultIndex: number): Promise<void> {
  const tab = editors.tabs.find((item) => item.id === editorId);
  const result = queries.execution(editorId, executionId)?.results.find((item) => item.resultIndex === resultIndex);
  const context = connections.completionContext(tab?.connection);
  if (!tab?.connection || !context || !result?.complete || result.errorMessage) return;
  if (tab.connection.providerId !== "oracle" && tab.connection.providerId !== "oceanbase-oracle") return;
  try {
    if (result.type.toUpperCase() === "DDL") {
      await completionClient.invalidateStructure(context.key, tab.connection.providerId, result.sql);
      return;
    }
    if (result.type.toUpperCase() !== "QUERY") return;
    await completionClient.enrichQuery({
      cacheKey: context.key,
      providerId: tab.connection.providerId,
      workspaceId: rpc.activeWorkspaceId,
      clientId: rpc.activeClientId,
      editorId,
      sql: result.sql,
      columns: result.columnDetails ?? []
    });
  } catch {
    // 字段类型是可延迟补充信息；失败不能影响查询结果或已有字段备注。
  }
}

function scheduleDraft(editorId: string): void {
  if (!workspaceOpened.value) return;
  draftPendingEditors.add(editorId);
  draftGenerations.set(editorId, (draftGenerations.get(editorId) ?? 0) + 1);
  const previous = draftSaveTimers.get(editorId);
  if (previous !== undefined) window.clearTimeout(previous);
  draftSaveTimers.set(editorId, window.setTimeout(() => {
    draftSaveTimers.delete(editorId); void persistDraftById(editorId, false);
  }, 1_000));
}

function editorContent(tab: EditorTab): string {
  return monacoEditor.value?.getValue(tab.id) ?? tab.content;
}

async function persistDraftById(editorId: string, immediate: boolean, keepalive = false): Promise<boolean> {
  if (!workspaceOpened.value) return false;
  const tab = editors.tabs.find((item) => item.id === editorId); if (!tab) return false;
  const timer = draftSaveTimers.get(editorId);
  if (timer !== undefined) { window.clearTimeout(timer); draftSaveTimers.delete(editorId); }
  const workspaceId = rpc.activeWorkspaceId;
  const workspaceEpoch = draftWorkspaceEpoch;
  const generation = draftGenerations.get(editorId) ?? 0;
  const sqlText = editorContent(tab);
  if (immediate) editors.patch(editorId, { content: sqlText });
  const payload = { title: tab.title, sqlText,
    sortOrder: editors.tabs.findIndex((item) => item.id === editorId), fileName: tab.filePath,
    filePath: tab.filePath, profileId: tab.connection?.id, dirty: tab.dirty, active: editors.activeId === editorId
  };
  const saveCurrent = async (): Promise<boolean> => {
    if (!workspaceOpened.value || draftWorkspaceEpoch !== workspaceEpoch
        || rpc.activeWorkspaceId !== workspaceId) return false;
    try {
      await rpc.saveEditorDraft(editorId, payload, keepalive);
      if (draftGenerations.get(editorId) === generation) draftPendingEditors.delete(editorId);
      return true;
    } catch (error) {
      if (immediate) reportError(error);
      return false;
    }
  };
  const previous = draftSaveQueues.get(editorId);
  const save = previous ? previous.catch(() => false).then(saveCurrent) : saveCurrent();
  draftSaveQueues.set(editorId, save);
  try {
    return await save;
  } finally {
    if (draftSaveQueues.get(editorId) === save) draftSaveQueues.delete(editorId);
  }
}

function flushDrafts(): void {
  for (const editorId of [...draftPendingEditors]) void persistDraftById(editorId, false, true);
}

async function newEditor(content = "", filePath?: string, title?: string, fileHandle?: FileSystemFileHandle,
                         inheritActiveConnection = true): Promise<EditorTab | undefined> {
  await rpc.ensureOperational();
  const inherited = inheritActiveConnection && editors.active?.connection && connections.current(editors.active.connection.id)
    ? editors.active.connection.id : undefined;
  const created = await rpc.request<{ id: string; title: string; connection?: EditorConnectionBinding; connectionState: EditorConnectionState }>("editor.create", inherited ? { profileId: inherited } : {});
  const tab: EditorTab = { id: created.id, title: title ?? created.title, content, filePath, fileHandle,
    dirty: Boolean(content && !filePath), transactionDirty: false, busy: false,
    executionPhase: "idle", transactionOperation: "idle",
    connection: created.connection, connectionState: created.connectionState ?? "unbound" };
  editors.add(tab);
  scheduleDraft(tab.id);
  if (tab.connection) void ensureCompletionForEditor(tab);
  return tab;
}

function markEditorDirty(change?: { editorId: string; content: string }): void {
  const tab = change?.editorId
    ? editors.tabs.find((item) => item.id === change.editorId)
    : editors.active;
  if (!tab) return;
  const content = change?.content ?? editorContent(tab);
  editors.patch(tab.id, { content, dirty: true }); scheduleDraft(tab.id);
  void nextTick().then(() => syncResultHighlight());
}
function runEditorSelectionAction(action: SqlEditorSelectionAction): void {
  if (!canEditSelection.value) return;
  monacoEditor.value?.runSelectionAction(action);
}
function requestSqlTransform(mode: "format" | "compact"): void {
  if (!canTransformSql.value) return;
  void transformActiveSql(mode).catch(reportError);
}
async function transformActiveSql(mode: "format" | "compact"): Promise<void> {
  const tab = editors.active;
  if (!tab) return;
  if (!tab.connection) { ElMessage.warning("请先为当前编辑标签选择数据库链接"); return; }
  const target = monacoEditor.value?.captureSqlTransformTarget(tab.id);
  if (!target) return;
  const result = await rpc.request<{ text: string }>(`sql.${mode}`, {
    editorId: tab.id,
    text: target.text,
  });
  const applied = monacoEditor.value?.applySqlTransform(target, result.text) ?? "missing";
  if (applied === "stale" || applied === "missing") {
    ElMessage.warning("SQL 内容已发生变化，请重试");
    return;
  }
  if (applied === "unchanged") return;
  editors.patch(tab.id, { dirty: true });
  scheduleDraft(tab.id);
}
function toggleResultEdit(): void {
  const tab = editors.active;
  const execution = activeExecution.value;
  const result = activeResult.value;
  if (!tab || !execution || !result || !canToggleResultEdit.value) return;
  if (resultEditUnlocked.value && activeResultEditSession.value
      && resultEdits.operations(activeResultEditSession.value).length > 0) {
    ElMessage.warning("仍有未应用的修改，请先应用或撤销后再退出编辑模式");
    return;
  }
  resultEdits.setUnlocked(tab.id, execution.executionId, result.resultIndex, !resultEditUnlocked.value);
}
async function postActiveResultChanges(): Promise<void> {
  const session = activeResultEditSession.value;
  if (!session || !canPostResultChanges.value) return;
  try {
    await postResultEditSession(session);
  } catch (error) {
    app.status = "应用结果更改失败";
    const details = (error as { details?: { operationId?: string; columnIndex?: number } }).details;
    if (details?.operationId) resultEdits.setOperationError(session.editorId, session.executionId,
      session.resultIndex, details.operationId, details.columnIndex, message(error));
    ElMessage.error(message(error));
  }
}
async function postResultEditSession(session: ResultEditSession): Promise<void> {
  const operations = resultEdits.operations(session);
  if (!operations.length) return;
  const clientRowIds = new Map(operations.map((operation) => [operation.operationId, operation.rowId]));
  await rpc.ensureOperational();
  const response = await rpc.request<{
    appliedOperationIds: string[];
    hiddenOperationIds?: string[];
    rowPatches: Array<{ operationId: string; kind: "update" | "insert" | "delete";
      rowIndex: number; rowId: string; row: Array<string | null> }>;
  }>("query.applyChanges", {
    editorId: session.editorId,
    executionId: session.executionId,
    resultIndex: session.resultIndex,
    operations: operations.map(({ sequence: _sequence, ...operation }) => operation)
  }, 30_000);
  queries.applyResultPatches(session.editorId, session.resultIndex, response.rowPatches.map((patch) => ({
    ...patch, clientRowId: clientRowIds.get(patch.operationId)
  })), session.executionId);
  resultEdits.markPosted(session.editorId, session.executionId, session.resultIndex,
    response.appliedOperationIds);
  editors.patch(session.editorId, {
    transactionDirty: true, transactionState: "active", resultChangesDirty: true
  });
  app.status = response.hiddenOperationIds?.length
    ? `${response.hiddenOperationIds.length} 项更改已写入事务，但不再符合原查询条件`
    : "结果更改已应用，等待提交或回滚事务";
}
async function postEditorPendingChanges(editorId: string): Promise<void> {
  const sessions = Object.values(resultEdits.sessions)
    .filter((session) => session.editorId === editorId && resultEdits.operations(session).length > 0);
  for (const session of sessions) await postResultEditSession(session);
}
function restoreResultEditValues(editorId: string, mode: "confirmed" | "original"): void {
  const byResult = new Map<string, Array<{ rowIndex: number; columnIndex: number; value: string | null }>>();
  for (const cell of resultEdits.restoreWithExecution(editorId, mode)) {
    const key = `${cell.executionId}:${cell.resultIndex}`;
    const cells = byResult.get(key) ?? [];
    cells.push(cell);
    byResult.set(key, cells);
  }
  for (const [key, cells] of byResult) {
    const [executionId, rawResultIndex] = key.split(":");
    queries.updateCells(editorId, Number(rawResultIndex), cells, executionId);
  }
}
async function resultChangesActionForExecution(editorId: string): Promise<"apply" | "ignore" | "cancel"> {
  return new Promise((resolve) => {
    let settled = false;
    const choose = (action: "apply" | "ignore" | "cancel"): void => {
      if (settled) return;
      settled = true;
      resolve(action);
      ElMessageBox.close();
    };
    void ElMessageBox({
      title: "未应用的数据修改",
      message: h("div", { class: "result-transaction-decision" }, [
        h("p", "似乎还有数据修改后没有应用，请先确认"),
        h("p", { class: "result-transaction-decision__hint" },
          `${resultEdits.pendingOperationCount(editorId)} 项修改尚未应用；应用只会写入当前事务，不会提交事务。`),
        h("div", { class: "result-transaction-decision__actions" }, [
          h(ElButton, { type: "primary", onClick: () => choose("apply") }, () => "应用"),
          h(ElButton, { type: "warning", onClick: () => choose("ignore") }, () => "忽略"),
          h(ElButton, { onClick: () => choose("cancel") }, () => "取消")
        ])
      ]),
      type: "warning",
      showConfirmButton: false,
      showCancelButton: false,
      showClose: false,
      closeOnClickModal: false,
      closeOnPressEscape: false
    }).catch(() => choose("cancel"));
  });
}

async function discardEditorPendingChanges(editorId: string): Promise<void> {
  const discarded = resultEdits.discardPending(editorId);
  const cellsByResult = new Map<string, Array<{ rowIndex: number; columnIndex: number; value: string | null }>>();
  for (const cell of discarded.cells) {
    const key = `${cell.executionId}:${cell.resultIndex}`;
    const cells = cellsByResult.get(key) ?? [];
    cells.push({ rowIndex: cell.rowIndex, columnIndex: cell.columnIndex, value: cell.value });
    cellsByResult.set(key, cells);
  }
  for (const [key, cells] of cellsByResult) {
    const [executionId, rawResultIndex] = key.split(":");
    queries.updateCells(editorId, Number(rawResultIndex), cells, executionId);
  }
  for (const insert of discarded.inserts) queries.removeRowById(editorId, insert.resultIndex, insert.rowId, insert.executionId);
  await Promise.all(discarded.largeValues.map((value) => rpc.deleteResultLargeValueDraft(
    editorId, value.executionId, value.resultIndex, value.columnIndex, value.token).catch(() => undefined)));
}

function toggleAutoRefresh(): void {
  if (autoRefreshState.value) {
    stopAutoRefresh();
    return;
  }
  const tab = editors.active;
  const execution = activeExecution.value;
  const result = activeResult.value;
  if (!tab || !execution || !result || !canStartAutoRefresh.value) return;
  autoRefreshEpoch += 1;
  autoRefreshState.value = {
    editorId: tab.id,
    executionId: execution.executionId,
    resultIndex: result.resultIndex,
    sql: result.sql,
    sourceStartOffset: result.sourceStartOffset ?? 0,
    refreshing: false,
    epoch: autoRefreshEpoch
  };
  scheduleAutoRefresh();
  app.status = `定时刷新已开启 · 每 ${settings.autoRefreshIntervalSeconds} 秒`;
}

function clearAutoRefreshTimer(): void {
  if (autoRefreshTimer !== undefined) window.clearTimeout(autoRefreshTimer);
  autoRefreshTimer = undefined;
}

function stopAutoRefresh(reason?: string, notify = false): void {
  if (!autoRefreshState.value) return;
  clearAutoRefreshTimer();
  autoRefreshEpoch += 1;
  autoRefreshState.value = undefined;
  app.status = reason ? `定时刷新已停止 · ${reason}` : "定时刷新已关闭";
  if (reason && notify) ElMessage.info(`定时刷新已停止：${reason}`);
}

function scheduleAutoRefresh(): void {
  clearAutoRefreshTimer();
  const state = autoRefreshState.value;
  if (!state || state.refreshing) return;
  autoRefreshTimer = window.setTimeout(() => {
    autoRefreshTimer = undefined;
    void runAutoRefresh(state.epoch);
  }, settings.autoRefreshIntervalSeconds * 1_000);
}

function autoRefreshMatchesActiveResult(): boolean {
  const state = autoRefreshState.value;
  if (!state || editors.activeId !== state.editorId || activeExecution.value?.executionId !== state.executionId) return false;
  if (!activeResult.value) return state.refreshing;
  return activeResult.value.resultIndex === state.resultIndex;
}

async function runAutoRefresh(epoch: number): Promise<void> {
  const state = autoRefreshState.value;
  if (!state || state.epoch !== epoch) return;
  if (!autoRefreshMatchesActiveResult()) {
    stopAutoRefresh("已切换结果页签", true);
    return;
  }
  const tab = editors.tabs.find((item) => item.id === state.editorId);
  if (!tab || !tab.connection || app.transportState !== "ready"
      || tab.connectionState === "unbound" || tab.connectionState === "unavailable"
      || tab.connectionState === "credentials-required") {
    stopAutoRefresh("数据库连接已不可用", true);
    return;
  }
  if (resultEdits.hasChanges(tab.id) || tab.resultChangesDirty) {
    stopAutoRefresh("结果存在待应用的修改", true);
    return;
  }
  if (tab.busy || resultLoading.value) {
    scheduleAutoRefresh();
    return;
  }
  state.refreshing = true;
  clearAutoRefreshTimer();
  const timelineSession = beginExecutionTimeline(tab.id);
  editors.patch(tab.id, { busy: true, activeExecutionId: undefined,
    executionStartedAt: Date.now(), executionPhase: "starting", executionTimelineStage: "thinking" });
  app.status = "正在定时刷新…";
  try {
    await rpc.ensureOperational();
    const response = await rpc.request<{ executionId: string }>("query.execute", {
      editorId: tab.id,
      text: monacoEditor.value?.getValue(tab.id) ?? tab.content,
      selectedText: state.sql,
      cursorOffset: 0,
      selectionStartOffset: state.sourceStartOffset,
      scope: "current",
      stopOnError: true,
      resultPresentation: "replace"
    });
    if (!bindExecutionTimelineExecution(tab.id, timelineSession.attemptId, response.executionId)) return;
    const currentState = autoRefreshState.value;
    if (currentState?.epoch === epoch) {
      currentState.executionId = response.executionId;
      currentState.resultIndex = 0;
    }
    resultEdits.finishEditor(tab.id);
    editors.patch(tab.id, { resultChangesDirty: false });
    queries.start(tab.id, response.executionId, "replace");
    if (queries.execution(tab.id, response.executionId)?.busy) {
      executionNotifications.start({ editorId: tab.id, executionId: response.executionId,
        startedAt: tab.executionStartedAt, editorTitle: tab.title });
    }
    if (editors.activeId === tab.id) activeResultIndex.value = response.executionId;
    const currentTab = editors.tabs.find((item) => item.id === tab.id);
    if (currentTab?.busy && currentTab.executionPhase !== "cancelling") {
      editors.patch(tab.id, { activeExecutionId: response.executionId, executionPhase: "running" });
      queueExecutionTimelineStages(tab.id, response.executionId, timelineSession.attemptId);
    }
  } catch (error) {
    const currentTab = editors.tabs.find((item) => item.id === tab.id);
    const ownsTimelineSession = executionTimelineSessions.get(tab.id)?.attemptId === timelineSession.attemptId;
    if (!currentTab?.activeExecutionId && ownsTimelineSession) {
      clearExecutionTimelineSession(tab.id, timelineSession.attemptId);
      editors.patch(tab.id, { busy: false, activeExecutionId: undefined,
        executionStartedAt: undefined, executionPhase: "idle", executionTimelineStage: undefined });
    }
    if (autoRefreshState.value?.epoch === epoch) stopAutoRefresh("执行失败", true);
    reportError(error);
  }
}

type EditorExecutionScope = "current" | "script" | "current-new-tab" | "explain";

function executeFromEditor(scope: EditorExecutionScope, selectedText: string, cursorOffset: number,
                           selectionStartOffset = 0): void {
  if (scope === "explain") {
    if (canExplain.value) void executeActive("current", selectedText, cursorOffset, false, "append", selectionStartOffset, true);
    return;
  }
  if (scope === "current-new-tab") {
    void executeCurrentInNewTab(selectedText, cursorOffset, selectionStartOffset);
    return;
  }
  void executeActive(scope, selectedText, cursorOffset, false, "replace", selectionStartOffset);
}
function triggerEditorExecution(scope: EditorExecutionScope): void {
  if (!canExecute.value) return;
  if (scope === "explain" && !canExplain.value) return;
  if (typeof monacoEditor.value?.triggerExecute === "function") {
    monacoEditor.value.triggerExecute(scope);
    return;
  }
  if (scope === "explain") void executeActive("current", "", 0, false, "append", 0, true);
  else if (scope === "current-new-tab") void executeCurrentInNewTab();
  else void executeActive(scope);
}
function executeCommand(command: string): void {
  if (command === "explain") triggerEditorExecution("explain");
  if (command === "current" || command === "script") triggerEditorExecution(command);
  if (command === "current-new-tab") triggerEditorExecution(command);
}
async function executeCurrentInNewTab(selectedText = "", cursorOffset = 0, selectionStartOffset = 0): Promise<void> {
  const source = editors.active;
  if (!source || source.busy || resultLoading.value?.editorId === source.id) return;
  if (!source.connection || source.connectionState === "unbound") {
    ElMessage.warning(source.connection ? "原数据库链接已不可用，请重新选择链接" : "请先为当前编辑标签选择数据库链接");
    return;
  }
  try {
    await rpc.ensureOperational();
    if (!await ensureEditorCredentials(source)) return;
    await executeActive("current", selectedText, cursorOffset, false, "append", selectionStartOffset);
  } catch (error) {
    ElMessage.error(message(error));
  }
}
async function executeActive(scope: "current" | "script", selectedText = "", cursorOffset = 0,
                             recoveryRetried = false, presentation: "replace" | "append" = "replace",
                             selectionStartOffset = 0, explain = false): Promise<void> {
  const tab = editors.active;
  if (!tab || tab.busy || resultLoading.value?.editorId === tab.id) return;
  if (!tab.connection || tab.connectionState === "unbound") {
    ElMessage.warning(tab.connection ? "原数据库链接已不可用，请重新选择链接" : "请先为当前编辑标签选择数据库链接");
    return;
  }
  const requestFailureId = executionRequestFailureKey(tab.id);
  executionAttention.clearExecution(tab.id, requestFailureId);
  let executionAttempted = false;
  let timelineSession: ExecutionTimelineSession | undefined;
  try {
    await rpc.ensureOperational();
    if (!await ensureEditorCredentials(tab)) return;
    if (presentation === "replace" && resultEdits.hasPending(tab.id)) {
      if (resultExecutionDecisionPending.value) return;
      resultExecutionDecisionPending.value = true;
      try {
        const action = await resultChangesActionForExecution(tab.id);
        if (action === "cancel") return;
        if (action === "apply") await postEditorPendingChanges(tab.id);
        else await discardEditorPendingChanges(tab.id);
      } finally {
        resultExecutionDecisionPending.value = false;
      }
    }
    timelineSession = beginExecutionTimeline(tab.id);
    editors.patch(tab.id, { busy: true, activeExecutionId: undefined,
      executionStartedAt: Date.now(), executionPhase: "starting", executionTimelineStage: "thinking" }); app.status = "正在执行…";
    executionAttempted = true;
    const response = await rpc.request<{ executionId: string }>(explain ? "query.explain" : "query.execute", {
      editorId: tab.id, text: monacoEditor.value?.getValue(tab.id) ?? tab.content,
      selectedText, cursorOffset, selectionStartOffset, scope,
      stopOnError: !settings.continueOnError, resultPresentation: presentation
    });
    if (!bindExecutionTimelineExecution(tab.id, timelineSession.attemptId, response.executionId)) return;
    if (presentation === "replace") {
      resultEdits.finishEditor(tab.id);
      editors.patch(tab.id, { resultChangesDirty: false });
    }
    queries.start(tab.id, response.executionId, presentation, explain ? "execution-plan" : "data");
    if (queries.execution(tab.id, response.executionId)?.busy) {
      executionNotifications.start({ editorId: tab.id, executionId: response.executionId,
        startedAt: tab.executionStartedAt, editorTitle: tab.title });
    }
    if (editors.activeId === tab.id) activeResultIndex.value = response.executionId;
    const current = editors.tabs.find((item) => item.id === tab.id);
    if (current?.busy && current.executionPhase !== "cancelling") {
      editors.patch(tab.id, { activeExecutionId: response.executionId, executionPhase: "running" });
      queueExecutionTimelineStages(tab.id, response.executionId, timelineSession.attemptId);
    }
  } catch (error) {
    const current = editors.tabs.find((item) => item.id === tab.id);
    const ownsTimelineSession = !timelineSession
      || executionTimelineSessions.get(tab.id)?.attemptId === timelineSession.attemptId;
    if (!current?.activeExecutionId && ownsTimelineSession) {
      clearExecutionTimelineSession(tab.id, timelineSession?.attemptId);
      editors.patch(tab.id, { busy: false, activeExecutionId: undefined,
        executionStartedAt: undefined, executionPhase: "idle", executionTimelineStage: undefined });
    }
    if ((error as { code?: string }).code === "WORKSPACE_RECOVERED_RETRY_REQUIRED" && !recoveryRetried) {
      if (editors.activeId !== tab.id) return;
      await executeActive(scope, selectedText, cursorOffset, true, presentation, selectionStartOffset, explain);
      return;
    }
    if ((error as { code?: string }).code === "RISK_REEXECUTION_REQUIRED") {
      ElMessage.warning("当前语句未包含 WHERE，可能影响大量数据；请再次执行以继续");
      return;
    }
    if (executionAttempted && current) {
      if (editors.activeId === tab.id) executionAttention.clearExecution(tab.id, requestFailureId);
      else executionAttention.markUnread(tab.id, requestFailureId, "error");
    }
    ElMessage.error(message(error));
  }
}
async function ensureEditorCredentials(tab: EditorTab): Promise<boolean> {
  if (tab.connectionState !== "credentials-required") return true;
  const password = await requestConnectionPassword();
  if (!password || !tab.connection) return false;
  const response = await rpc.request<{ connection: EditorConnectionBinding; connectionState: EditorConnectionState }>(
    "editor.bind", { editorId: tab.id, profileId: tab.connection.id, password: password.password,
      rememberPassword: password.remember, transactionAction: "" }, 60_000);
  editors.patch(tab.id, { connection: response.connection, connectionState: response.connectionState });
  scheduleDraft(tab.id);
  return true;
}
async function cancelActive(): Promise<void> {
  const loading = activeResultLoading.value;
  if (loading) {
    if (loading.phase === "cancelling") return;
    resultLoading.value = { ...loading, phase: "cancelling", cancelRequested: true };
    try {
      await rpc.ensureOperational();
      await rpc.request<{ cancelled: boolean }>("query.cancel", {
        editorId: loading.editorId, executionId: loading.executionId
      });
    } catch (error) {
      ElMessage.error(message(error));
    }
    return;
  }
  const tab = editors.active;
  if (!tab?.busy || !tab.activeExecutionId || tab.executionPhase !== "running") return;
  const executionId = tab.activeExecutionId;
  clearExecutionTimelineStageTimer(tab.id);
  editors.patch(tab.id, { executionPhase: "cancelling" });
  try {
    await rpc.ensureOperational();
    const response = await rpc.request<{ cancelled: boolean }>("query.cancel", {
      editorId: tab.id, executionId
    });
    if (!response.cancelled) throw new Error("当前执行已经结束或无法取消");
  } catch (error) {
    const current = editors.tabs.find((item) => item.id === tab.id);
    if (current?.busy && current.activeExecutionId === executionId) {
      editors.patch(tab.id, { executionPhase: "running" });
      const timelineSession = executionTimelineSessions.get(tab.id);
      if (timelineSession?.executionId === executionId) {
        advanceExecutionTimelineQueue(tab.id, timelineSession.attemptId, executionId);
      }
    }
    ElMessage.error(message(error));
  }
}
async function commitActive(): Promise<void> {
  const tab = editors.active;
  if (!tab?.connection || !canOperateTransaction.value) return;
  if (resultEdits.hasPending(tab.id)) {
    ElMessage.warning("结果中有尚未应用的本地草稿，请先点击“应用更改”");
    return;
  }
  editors.patch(tab.id, { transactionOperation: "committing" });
  try {
    await rpc.ensureOperational();
    const response = await rpc.request<{ dirty: boolean; message: string }>("transaction.commit", { editorId: tab.id });
    resultEdits.finishEditor(tab.id);
    editors.patch(tab.id, { transactionDirty: response.dirty, resultChangesDirty: false,
      transactionState: response.dirty ? "active" : "none" });
    app.status = response.message;
  } catch (error) {
    ElMessage.error(message(error));
  } finally {
    editors.patch(tab.id, { transactionOperation: "idle" });
  }
}
async function rollbackActive(): Promise<void> {
  const tab = editors.active;
  if (!tab?.connection || !canOperateTransaction.value) return;
  editors.patch(tab.id, { transactionOperation: "rolling-back" });
  try {
    await rpc.ensureOperational();
    const response = await rpc.request<{ dirty: boolean; message: string;
      resultSnapshots?: Array<{ executionId: string; resultIndex: number; rows: Array<Array<string | null>>; rowIds: string[] }> }>(
      "transaction.rollback", { editorId: tab.id });
    if (response.resultSnapshots?.length) for (const snapshot of response.resultSnapshots) {
      queries.replaceResultSnapshot(tab.id, snapshot.resultIndex, snapshot.rows, snapshot.rowIds, snapshot.executionId);
    } else restoreResultEditValues(tab.id, "original");
    resultEdits.finishEditor(tab.id);
    editors.patch(tab.id, { transactionDirty: response.dirty, resultChangesDirty: false,
      transactionState: response.dirty ? "active" : "none" });
    app.status = response.message;
  } catch (error) {
    ElMessage.error(message(error));
  } finally {
    editors.patch(tab.id, { transactionOperation: "idle" });
  }
}

interface ResultPageResponse {
  executionId?: string;
  resultIndex: number;
  offset: number;
  rows: Array<Array<string | null>>;
  rowIds?: string[];
  hasMore: boolean;
  nextOffset: number;
  cancelled?: boolean;
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
  if (activeExecution.value?.historical) return "断线前快照不能继续加载数据";
  if (app.transportState !== "ready") return "事件通道恢复后才能加载数据";
  if (resultReloadRequired.has(activeResultReloadKey() ?? "")) return "事件通道已断开，请重新执行查询";
  if (activeExecution.value?.busy || !activeResult.value.complete) return "查询尚未完成";
  if (resultLoading.value) return resultLoading.value.mode === "next" ? "正在加载下一页数据…" : "正在获取全部数据…";
  if (!activeResult.value.truncated) return "已获取全部数据";
  return mode === "next"
    ? `下一页数据 · 最多 ${settings.maxResultRows} 行；建议查询包含稳定的 ORDER BY`
    : "获取全部数据；保持一次查询持续读取，建议包含稳定的 ORDER BY";
}

async function loadResultRows(resultIndex: number, initialOffset: number, all: boolean): Promise<void> {
  const tab = editors.active;
  const resultExecutionId = activeExecution.value?.executionId;
  if (!tab || !resultExecutionId || resultLoading.value || activeExecution.value?.historical
      || resultReloadRequired.has(resultReloadKey(tab.id, resultExecutionId, resultIndex))) return;
  const mode = all ? "all" : "next";
  const executionId = crypto.randomUUID();
  const loading: ResultLoadingState = {
    editorId: tab.id, resultIndex, resultExecutionId, mode, executionId, phase: "starting", cancelRequested: false
  };
  resultLoading.value = loading;
  let offset = initialOffset;
  const limit = settings.maxResultRows;
  try {
    await rpc.ensureOperational();
    if (resultLoading.value?.executionId !== executionId || resultLoading.value.cancelRequested) return;
    if (all) {
      const completion = new Promise<void>((resolve, reject) => {
        pendingLoadAll.set(executionId, { resolve, reject });
      });
      await rpc.request("query.loadAll", {
        editorId: tab.id, resultIndex, offset, executionId, resultExecutionId,
        batchRows: settings.streamBatchRows
      }, 120_000);
      await completion;
      return;
    }
    do {
      if (resultLoading.value?.executionId !== executionId || resultLoading.value.cancelRequested) break;
      const page = await rpc.request<ResultPageResponse>("query.fetchRows", {
        editorId: tab.id, resultIndex, offset, limit, executionId, resultExecutionId
      }, 120_000);
      const current = resultLoading.value;
      if (!current || current.executionId !== executionId) break;
      if (page.cancelled) {
        resultLoading.value = { ...current, phase: "cancelling", cancelRequested: true };
        app.status = `数据加载已取消 · 已保留 ${offset} 行`;
        break;
      }
      if (page.rows.length) queries.appendRows(tab.id, resultIndex, page.rows, resultExecutionId, page.rowIds);
      queries.completeResult(tab.id, resultIndex, { truncated: page.hasMore }, resultExecutionId);
      offset = page.nextOffset;
      app.status = page.hasMore ? `已加载 ${offset} 行` : `已获取全部 ${offset} 行`;
      if (current.cancelRequested) {
        app.status = `数据加载已取消 · 已保留 ${offset} 行`;
        break;
      }
      if (!all || !page.hasMore || page.rows.length === 0) break;
      await nextTick();
    } while (!resultLoading.value?.cancelRequested);
  } catch (error) {
    const current = resultLoading.value;
    if (all && current?.executionId === executionId && !current.cancelRequested) {
      resultLoading.value = { ...current, phase: "cancelling", cancelRequested: true };
      void rpc.request("query.cancel", { editorId: tab.id, executionId }).catch(() => { });
    }
    if (!current?.cancelRequested) reportError(error);
  } finally {
    pendingLoadAll.delete(executionId);
    if (resultLoading.value?.executionId === executionId) resultLoading.value = undefined;
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

function loadCompletionSnapshot(profile: SavedProfile, source: { profileId?: string; editorId?: string }, force: boolean): Promise<void> {
  const context = connections.completionContext(profile);
  if (!context) return Promise.resolve();
  const running = completionLoads.get(context.key);
  if (running) return running;
  const task = performCompletionLoad(profile, source, context, force).finally(() => {
    if (completionLoads.get(context.key) === task) completionLoads.delete(context.key);
  });
  completionLoads.set(context.key, task);
  return task;
}

async function performCompletionLoad(profile: SavedProfile, source: { profileId?: string; editorId?: string },
                                     context: { key: string; label: string }, force: boolean): Promise<void> {
  let cached = metadata.completionFor(context.key)?.summary;
  if (!cached) {
    cached = await completionClient.inspect(context.key, profile.providerId).catch(() => undefined);
    if (cached) metadata.readyFromCache(context.key, context.label, cached);
  }
  if (cached && !force) return;

  let namespaces: CompletionNamespaceDescriptor[];
  try {
    const response = await rpc.request<CompletionNamespacesResponse>("metadata.completionNamespaces", source, 60_000);
    namespaces = response.namespaces;
  } catch (error) {
    ElMessage.error(message(error));
    return;
  }
  const initialKeys = initialCompletionNamespaceKeys(namespaces, cached?.selectedNamespaceKeys, force);
  const selected = await requestSchemaSelection(namespaces, initialKeys, force);
  if (!selected) return;

  const loadId = crypto.randomUUID();
  if (!metadata.beginCompletion(context.key, context.label, loadId, profile.id, true)) return;
  try {
    const summary = await completionClient.refresh({
      cacheKey: context.key,
      providerId: profile.providerId,
      workspaceId: rpc.activeWorkspaceId,
      clientId: rpc.activeClientId,
      body: { loadId, ...source, selectedNamespaces: selected.map((item) => ({ catalog: item.catalog, schema: item.schema })) }
    });
    if (!metadata.completeCompletion(context.key, loadId, summary)) return;
    if (summary.warning) ElMessage.warning(summary.warning);
    await refreshCompletionStats();
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

function requestSchemaSelection(namespaces: CompletionNamespaceDescriptor[], initialKeys: string[], refresh: boolean): Promise<CompletionNamespaceDescriptor[] | undefined> {
  return new Promise((resolve) => {
    schemaSelectionQueue.push({ namespaces, initialKeys, refresh, resolve });
    showNextSchemaSelection();
  });
}

function showNextSchemaSelection(): void {
  if (activeSchemaSelection || !schemaSelectionQueue.length) return;
  activeSchemaSelection = schemaSelectionQueue.shift();
  if (!activeSchemaSelection) return;
  completionSchemaNamespaces.value = activeSchemaSelection.namespaces;
  completionSchemaInitialKeys.value = activeSchemaSelection.initialKeys;
  completionSchemaRefresh.value = activeSchemaSelection.refresh;
  completionSchemaDialog.value = true;
}

function completeSchemaSelection(namespaces: CompletionNamespaceDescriptor[]): void {
  settleSchemaSelection(namespaces);
}

function cancelSchemaSelection(): void {
  settleSchemaSelection(undefined);
}

function settleSchemaSelection(namespaces: CompletionNamespaceDescriptor[] | undefined): void {
  const request = activeSchemaSelection;
  if (!request) return;
  activeSchemaSelection = undefined;
  completionSchemaDialog.value = false;
  request.resolve(namespaces);
  window.setTimeout(showNextSchemaSelection, 0);
}

async function refreshCompletionStats(): Promise<void> {
  try { metadata.applyPersistentStats(await completionClient.stats()); }
  catch { /* IndexedDB unavailable: completion requests will report a concrete error when used. */ }
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
  const count = (cache.summary?.objectCount ?? 0) + (cache.summary?.columnCount ?? 0);
  return `${cache.label} 补全已更新 · ${count} 项`;
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
    await rpc.ensureOperational();
    if (!selected) {
      await rpc.request("editor.unbind", { editorId: tab.id, transactionAction });
      editors.patch(tab.id, { connection: undefined, connectionState: "unbound", transactionDirty: false, transactionState: "none" });
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
        transactionDirty: false, transactionState: "none" });
      app.status = `已绑定 ${response.connection.name}`;
      void ensureCompletionForEditor({ ...tab, connection: response.connection, connectionState: response.connectionState });
    }
    if (transactionAction === "rollback") restoreResultEditValues(tab.id, "original");
    if (transactionAction) resultEdits.finishEditor(tab.id);
    editors.patch(tab.id, { resultChangesDirty: false });
    for (const execution of queries.executionList(tab.id)) {
      monacoEditor.value?.releaseExecutionSources?.(execution.executionId);
    }
    queries.clearEditor(tab.id);
    scheduleDraft(tab.id);
    metadata.activate(activeObjectTreeKey.value, activeCompletionKey.value);
  } catch (error) { reportError(error); }
}

async function transactionActionForSwitch(tab: EditorTab): Promise<"commit" | "rollback" | "cancel" | ""> {
  if (!tab.transactionDirty) return "";
  try {
    await ElMessageBox({ title: "未提交事务", message: "切换数据库链接前请选择提交或回滚。", type: "warning",
      showCancelButton: true, showClose: true, distinguishCancelAndClose: true,
      confirmButtonText: "提交并切换", cancelButtonText: "回滚并切换" });
    if (resultEdits.hasPending(tab.id)) await postEditorPendingChanges(tab.id);
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
  if (settings.shortcutRecordingActive || event.isComposing) return;
  const target = event.target instanceof Element ? event.target : null;
  if (target?.closest(".table-host") && ["ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight"].includes(event.key)
      && !event.ctrlKey && !event.metaKey && !event.altKey) return;
  const binding = shortcutFromKeyboardEvent(event);
  if (!binding) return;
  const actionId = actionForShortcut(settings.shortcuts, binding);
  if (actionId) {
    event.preventDefault();
    event.stopPropagation();
    if (actionId !== "query.cancel" && hasBlockingShortcutLayer()) return;
    if (!event.repeat) runShortcutAction(actionId);
    return;
  }
  if (isDangerousLegacyShortcut(binding)) {
    event.preventDefault();
    event.stopPropagation();
  }
}

function runShortcutAction(actionId: ShortcutActionId): void {
  if (!workspaceOpened.value) return;
  if (activeExecution.value?.displayType === "execution-plan" && actionId.startsWith("result.")) return;
  if (actionId === "file.newQuery") { void newEditor().catch(reportError); return; }
  if (actionId === "file.openSql") { void openFile().catch(reportError); return; }
  if (actionId === "file.saveSql") {
    if (editors.active) void saveActive(false).catch(reportError);
    return;
  }
  if (actionId === "query.executeCurrent") { triggerEditorExecution("current"); return; }
  if (actionId === "query.executeCurrentNewTab") { triggerEditorExecution("current-new-tab"); return; }
  if (actionId === "query.executeAll") { triggerEditorExecution("script"); return; }
  if (actionId === "query.explain") { triggerEditorExecution("explain"); return; }
  if (actionId === "query.cancel") {
    if (canCancelExecution.value) void cancelActive();
    return;
  }
  if (actionId === "transaction.commit") {
    if (canOperateTransaction.value) void commitActive().catch(reportError);
    return;
  }
  if (actionId === "transaction.rollback") {
    if (canOperateTransaction.value) void rollbackActive().catch(reportError);
    return;
  }
  if (actionId === "data.import") {
    if (activeConnected.value && app.transportState === "ready") csvDialog.value = true;
    return;
  }
  if (actionId === "history.open") { historyDrawer.value = true; return; }
  if (actionId === "settings.open") { settingsDrawer.value = true; return; }
  if (actionId === "ui.toggleTheme") {
    void updateTheme(app.theme === "dark" ? "light" : "dark").catch(reportError);
    return;
  }
  if (actionId === "app.exit") { void closeApplication(); return; }
  if (actionId === "workspace.objects") { selectTool("objects"); return; }
  if (actionId === "workspace.connections") { selectTool("connections"); return; }
  if (actionId === "workspace.refreshObjects") {
    if (activeTool.value === "objects" && panelVisible.value) objectExplorer.value?.refresh();
    return;
  }
  if (actionId === "editor.toggleMinimap") {
    void updateMinimapEnabled(!settings.minimapEnabled);
    return;
  }
  if (actionId === "editor.toggleWordWrap") {
    void updateWordWrapEnabled(!settings.wordWrapEnabled);
    return;
  }
  if (actionId === "editor.format") {
    requestSqlTransform("format");
    return;
  }
  if (actionId === "editor.compact") {
    requestSqlTransform("compact");
    return;
  }
  if (actionId === "editor.uppercase") {
    runEditorSelectionAction("uppercase");
    return;
  }
  if (actionId === "editor.lowercase") {
    runEditorSelectionAction("lowercase");
    return;
  }
  if (actionId === "editor.toggleLineComment") {
    runEditorSelectionAction("lineComment");
    return;
  }
  if (actionId === "editor.toggleBlockComment") {
    runEditorSelectionAction("blockComment");
    return;
  }
  if (actionId === "editor.complete") {
    if (editors.active && activeCompletionKey.value !== "unbound") monacoEditor.value?.triggerCompletion();
    return;
  }
  if (actionId === "result.toggleEditMode") { toggleResultEdit(); return; }
  if (actionId === "result.toggleSingleRecord") { resultPanel.value?.toggleSingleRecordView(); return; }
  if (actionId === "result.toggleRecordComparison") { resultPanel.value?.toggleRecordComparison(); return; }
  if (actionId === "result.restoreLayout") { resultPanel.value?.restoreLayout(); return; }
  if (actionId === "result.copySelection") {
    void resultPanel.value?.copyCurrentSelection().catch(reportError);
    return;
  }
  if (actionId === "result.loadNext") {
    if (canLoadMore.value) void loadNextResultPage();
    return;
  }
  if (actionId === "result.loadAll" && canLoadMore.value) void loadAllResultRows();
}

function hasBlockingShortcutLayer(): boolean {
  if (connectionDialog.value || historyDrawer.value || settingsDrawer.value || shortcutDrawer.value
      || csvDialog.value || completionSchemaDialog.value) return true;
  return Array.from(document.querySelectorAll<HTMLElement>(".el-overlay"))
    .some((overlay) => {
      const style = getComputedStyle(overlay);
      return overlay.style.display !== "none" && style.display !== "none" && style.visibility !== "hidden";
    });
}

function actionTooltip(label: string, actionId: ShortcutActionId): string {
  return shortcutTooltip(label, actionId, settings.shortcuts);
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
  const sameFile = !saveAs && tab.fileHandle !== undefined && file.handle === tab.fileHandle;
  editors.patch(tab.id, { filePath: file.name, fileHandle: file.handle,
    title: sameFile ? tab.title : file.name, dirty: false });
  await persistDraftById(tab.id, true);
  if (file.handle) { recentHandles.set(file.name, file.handle); if (!settings.recentFiles.includes(file.name)) settings.recentFiles.unshift(file.name); }
  return true;
}

function startEditorTabDrag(event: DragEvent, id: string): void {
  if (editorTabMenuBusy.value) return;
  editorDrag.value = { sourceId: id, position: "after" };
  event.dataTransfer?.setData("text/plain", id);
  if (event.dataTransfer) event.dataTransfer.effectAllowed = "move";
}

function dragOverEditorTab(event: DragEvent, id: string): void {
  const drag = editorDrag.value;
  if (!drag || drag.sourceId === id) return;
  event.preventDefault();
  if (event.dataTransfer) event.dataTransfer.dropEffect = "move";
  const element = event.currentTarget as HTMLElement;
  const tabElement = element.closest<HTMLElement>(".el-tabs__item") ?? element;
  const bounds = tabElement.getBoundingClientRect();
  const position = event.clientX < bounds.left + bounds.width / 2 ? "before" : "after";
  editorDrag.value = { ...drag, targetId: id, position };
  const nav = element.closest(".editor-tabs")?.querySelector<HTMLElement>(".el-tabs__nav-scroll");
  const navBounds = nav?.getBoundingClientRect();
  if (nav && navBounds) {
    const edge = 28;
    if (event.clientX < navBounds.left + edge) nav.parentElement?.querySelector<HTMLElement>(".el-tabs__nav-prev")?.click();
    else if (event.clientX > navBounds.right - edge) nav.parentElement?.querySelector<HTMLElement>(".el-tabs__nav-next")?.click();
  }
}

function dropEditorTab(event: DragEvent, id: string): void {
  const drag = editorDrag.value;
  if (!drag || drag.sourceId === id) return;
  event.preventDefault();
  const targetIndex = editors.tabs.findIndex((tab) => tab.id === id);
  const sourceIndex = editors.tabs.findIndex((tab) => tab.id === drag.sourceId);
  if (targetIndex < 0 || sourceIndex < 0) { endEditorTabDrag(); return; }
  let destination = drag.position === "after" ? targetIndex + 1 : targetIndex;
  if (sourceIndex < destination) destination -= 1;
  if (editors.move(drag.sourceId, destination)) {
    void persistEditorOrder();
  }
  endEditorTabDrag();
}

function endEditorTabDrag(): void {
  editorDrag.value = undefined;
}

async function persistEditorOrder(): Promise<void> {
  await Promise.all(editors.tabs.map((tab) => persistDraftById(tab.id, true)));
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
  const loading = resultLoading.value?.editorId === id ? resultLoading.value : undefined;
  if (loading) {
    clearPendingResultBatches(id, loading.executionId, loading.resultIndex);
    resultReloadRequired.delete(resultReloadKey(loading.editorId, loading.resultExecutionId, loading.resultIndex));
    rejectPendingLoadAll(id, loading.executionId, new Error("编辑器已关闭"));
    resultLoading.value = undefined;
    try {
      await rpc.ensureOperational();
      await rpc.request("query.cancel", { editorId: id, executionId: loading.executionId });
    } catch { /* editor close still releases the active JDBC session */ }
  }
  const state = await rpc.request<{ requiresTransactionDecision: boolean }>("editor.close", { editorId: id, action: "check" });
  if (state.requiresTransactionDecision) {
    let action: "commit" | "rollback";
    try {
      await ElMessageBox({ title: "未提交事务", message: "关闭前请选择提交或回滚。", type: "warning", showCancelButton: true,
        showClose: true, distinguishCancelAndClose: true, confirmButtonText: "提交", cancelButtonText: "回滚" });
      action = "commit";
    } catch (choice) { if (choice !== "cancel") return false; action = "rollback"; }
    if (action === "commit" && resultEdits.hasPending(id)) await postEditorPendingChanges(id);
    await rpc.request("editor.close", { editorId: id, action });
  } else await rpc.request("editor.close", { editorId: id, action: "close" });
  for (const execution of queries.executionList(id)) {
    monacoEditor.value?.releaseExecutionSources?.(execution.executionId);
    executionNotifications.clearExecution(execution.executionId);
  }
  if (tab.activeExecutionId) executionNotifications.clearExecution(tab.activeExecutionId);
  executionAttention.clearEditor(id);
  for (const key of [...resultReloadRequired]) if (key.startsWith(`${id}:`)) resultReloadRequired.delete(key);
  clearExecutionTimelineSession(id);
  resultEdits.finishEditor(id); queries.clearEditor(id); editors.remove(id);
  void nextTick().then(() => monacoEditor.value?.releaseModel?.(id));
  return true;
}

type EditorTabMenuCommand = "close" | "close-others" | "close-all" | "duplicate" | "rename";

async function closeEditorTabs(ids: string[]): Promise<boolean> {
  for (const id of ids) {
    if (!editors.tabs.some((tab) => tab.id === id)) continue;
    if (!await closeTab(id)) return false;
  }
  return true;
}

async function duplicateEditorTab(id: string): Promise<void> {
  const tab = editors.tabs.find((item) => item.id === id);
  if (!tab) return;
  const content = monacoEditor.value?.getValue(id) ?? tab.content;
  await newEditor(content, undefined, undefined, undefined, false);
}

async function renameEditorTab(id: string): Promise<void> {
  const tab = editors.tabs.find((item) => item.id === id);
  if (!tab) return;
  const previousTitle = tab.title;
  try {
    const result = await ElMessageBox.prompt("请输入新的窗口名称", "重命名", {
      inputValue: previousTitle,
      inputValidator: (value) => Boolean(value.trim()) || "名称不能为空",
      confirmButtonText: "确定",
      cancelButtonText: "取消"
    });
    const title = result.value.trim();
    if (title === previousTitle) return;
    editors.patch(id, { title });
    if (!await persistDraftById(id, true)) editors.patch(id, { title: previousTitle });
  } catch (action) {
    if (action !== "cancel" && action !== "close") reportError(action);
  }
}

async function handleEditorTabCommand(rawCommand: string, id: string): Promise<void> {
  if (editorTabMenuBusy.value) return;
  const command = rawCommand as EditorTabMenuCommand;
  if (!["close", "close-others", "close-all", "duplicate", "rename"].includes(command)) return;
  editorTabMenuBusy.value = true;
  try {
    if (command === "close") {
      await closeTab(id);
      return;
    }
    if (command === "close-others") {
      const others = editors.tabs.filter((tab) => tab.id !== id).map((tab) => tab.id);
      if (await closeEditorTabs(others) && editors.tabs.some((tab) => tab.id === id)) editors.activeId = id;
      return;
    }
    if (command === "close-all") {
      await closeEditorTabs(editors.tabs.map((tab) => tab.id));
      return;
    }
    if (command === "duplicate") {
      await duplicateEditorTab(id);
      return;
    }
    await renameEditorTab(id);
  } catch (error) {
    reportError(error);
  } finally {
    editorTabMenuBusy.value = false;
  }
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
    const discardDrafts = new Set<string>();
    for (const tab of [...editors.tabs]) {
      editors.activeId = tab.id;
      if (tab.dirty) {
        try {
          await ElMessageBox({ title: "保存修改？", message: `${tab.title} 有未保存的修改。`, type: "warning",
            showCancelButton: true, showClose: true, distinguishCancelAndClose: true,
            confirmButtonText: "保存", cancelButtonText: "不保存" });
          if (!await saveActive(false)) { await rpc.request("app.closeDecision", { allow: false }); return; }
        } catch (choice) {
          if (choice !== "cancel") { await rpc.request("app.closeDecision", { allow: false }); return; }
          discardDrafts.add(tab.id);
        }
      }
      if (tab.transactionDirty) {
        try {
          await ElMessageBox({ title: "未提交事务", message: `${tab.title} 仍有未提交事务。`, type: "warning",
            showCancelButton: true, showClose: true, distinguishCancelAndClose: true,
            confirmButtonText: "提交", cancelButtonText: "回滚" });
          if (resultEdits.hasPending(tab.id)) await postEditorPendingChanges(tab.id);
          await commitActive();
        } catch (choice) {
          if (choice !== "cancel") { await rpc.request("app.closeDecision", { allow: false }); return; }
          await rollbackActive();
        }
      }
    }
    draftSaveTimers.forEach((timer) => window.clearTimeout(timer)); draftSaveTimers.clear();
    for (const tab of editors.tabs) {
      if (discardDrafts.has(tab.id)) continue;
      if (!await persistDraftById(tab.id, true)) {
        await rpc.request("app.closeDecision", { allow: false });
        return;
      }
    }
    executionNotifications.clear();
    await rpc.finalizeWorkspace();
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
async function saveColorSchemes(value: ColorSchemeSettings): Promise<void> {
  const previous = cloneColorSchemes(settings.colorSchemes);
  settings.setColorSchemes(value);
  appearanceSaving.value = true;
  try {
    await rpc.request("settings.update", {
      key: "appearance.colorSchemes",
      value: serializeColorSchemeSettings(value),
    });
    appearanceDrawer.value = false;
    ElMessage.success("配色方案已保存");
  } catch (error) {
    settings.setColorSchemes(previous);
    reportError(error);
  } finally {
    appearanceSaving.value = false;
  }
}
async function updateMaxRows(value: number): Promise<void> {
  const previous = settings.maxResultRows; settings.maxResultRows = value;
  try { await rpc.request("settings.update", { key: "result.maxRows", value: String(value) }); }
  catch (error) { settings.maxResultRows = previous; reportError(error); }
}
async function updateAutoRefreshInterval(value: number): Promise<void> {
  if (!Number.isInteger(value) || value < 1 || value > 3600) return;
  const previous = settings.autoRefreshIntervalSeconds;
  settings.autoRefreshIntervalSeconds = value;
  if (autoRefreshState.value) scheduleAutoRefresh();
  try {
    await rpc.request("settings.update", { key: "result.autoRefreshIntervalSeconds", value: String(value) });
    app.status = `定时刷新周期已设置为 ${value} 秒`;
  } catch (error) {
    settings.autoRefreshIntervalSeconds = previous;
    if (autoRefreshState.value) scheduleAutoRefresh();
    reportError(error);
  }
}
async function updateMaxLobBytes(value: number): Promise<void> {
  const previous = settings.maxResultLobBytes; settings.maxResultLobBytes = value;
  try { await rpc.request("settings.update", { key: "result.edit.maxLobBytes", value: String(value) }); }
  catch (error) { settings.maxResultLobBytes = previous; reportError(error); }
}
async function updateStreamBatchRows(value: number): Promise<void> {
  const previous = settings.streamBatchRows; settings.streamBatchRows = value;
  try { await rpc.request("settings.update", { key: "result.streamBatchRows", value: String(value) }); }
  catch (error) { settings.streamBatchRows = previous; reportError(error); }
}
async function updateClobMaxCharacters(value: number): Promise<void> {
  const previous = settings.clobMaxCharacters; settings.clobMaxCharacters = value;
  try { await rpc.request("settings.update", { key: "result.clobMaxCharacters", value: String(value) }); }
  catch (error) { settings.clobMaxCharacters = previous; reportError(error); }
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
async function updateHeaderSortingEnabled(value: boolean): Promise<void> {
  const previous = settings.headerSortingEnabled; settings.headerSortingEnabled = value;
  try { await rpc.request("settings.update", { key: "result.headerSortingEnabled", value: String(value) }); }
  catch (error) { settings.headerSortingEnabled = previous; reportError(error); }
}
async function updateHeaderFilteringEnabled(value: boolean): Promise<void> {
  const previous = settings.headerFilteringEnabled; settings.headerFilteringEnabled = value;
  try { await rpc.request("settings.update", { key: "result.headerFilteringEnabled", value: String(value) }); }
  catch (error) { settings.headerFilteringEnabled = previous; reportError(error); }
}
async function updateShowColumnRemarksInHeader(value: boolean): Promise<void> {
  const previous = settings.showColumnRemarksInHeader; settings.showColumnRemarksInHeader = value;
  try { await rpc.request("settings.update", { key: "result.showColumnRemarksInHeader", value: String(value) }); }
  catch (error) { settings.showColumnRemarksInHeader = previous; reportError(error); }
}
async function updateZebraStripesEnabled(value: boolean): Promise<void> {
  const previous = settings.zebraStripesEnabled; settings.zebraStripesEnabled = value;
  try { await rpc.request("settings.update", { key: "result.zebraStripesEnabled", value: String(value) }); }
  catch (error) { settings.zebraStripesEnabled = previous; reportError(error); }
}
async function updateCompareHighlightMode(value: ResultCompareHighlightMode): Promise<void> {
  const previous = settings.compareHighlightMode; settings.compareHighlightMode = value;
  try { await rpc.request("settings.update", { key: "result.compareHighlightMode", value }); }
  catch (error) { settings.compareHighlightMode = previous; reportError(error); }
}
async function updateCompareScope(value: ResultCompareScope): Promise<void> {
  const previous = settings.compareScope; settings.compareScope = value;
  try { await rpc.request("settings.update", { key: "result.compareScope", value }); }
  catch (error) { settings.compareScope = previous; reportError(error); }
}
async function updateCompareCaseSensitive(value: boolean): Promise<void> {
  const previous = settings.compareCaseSensitive; settings.compareCaseSensitive = value;
  try { await rpc.request("settings.update", { key: "result.compareCaseSensitive", value: String(value) }); }
  catch (error) { settings.compareCaseSensitive = previous; reportError(error); }
}
async function updateScrollOptimizationBufferScreens(value: number): Promise<void> {
  const previous = settings.scrollOptimizationBufferScreens; settings.scrollOptimizationBufferScreens = value;
  try { await rpc.request("settings.update", { key: "result.scrollOptimizationBufferScreens", value: String(value) }); }
  catch (error) { settings.scrollOptimizationBufferScreens = previous; reportError(error); }
}
async function updateShowSelectedColumnRemarks(value: boolean): Promise<void> {
  const previous = settings.showSelectedColumnRemarks; settings.showSelectedColumnRemarks = value;
  try { await rpc.request("settings.update", { key: "statusBar.showSelectedColumnRemarks", value: String(value) }); }
  catch (error) { settings.showSelectedColumnRemarks = previous; reportError(error); }
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
async function updateAutoCommit(value: boolean): Promise<void> {
  const previous = settings.autoCommit; settings.autoCommit = value;
  try { await rpc.request("settings.update", { key: "connection.autoCommit", value: String(value) }); }
  catch (error) { settings.autoCommit = previous; reportError(error); }
}
async function updateIdleTimeoutMinutes(value: number): Promise<void> {
  const previous = settings.idleTimeoutMinutes; settings.idleTimeoutMinutes = value;
  try { await rpc.request("settings.update", { key: "connection.idleTimeoutMinutes", value: String(value) }); }
  catch (error) { settings.idleTimeoutMinutes = previous; reportError(error); }
}
async function updateTransactionDisconnectRollbackMinutes(value: number): Promise<void> {
  const previous = settings.transactionDisconnectRollbackMinutes;
  settings.transactionDisconnectRollbackMinutes = value;
  try { await rpc.request("settings.update", { key: "connection.transactionDisconnectRollbackMinutes", value: String(value) }); }
  catch (error) { settings.transactionDisconnectRollbackMinutes = previous; reportError(error); }
}
async function updateCompletionCandidateLimit(value: number): Promise<void> {
  const normalized = Math.max(10, Math.min(1000, Math.round(value)));
  const previous = settings.completionCandidateLimit;
  settings.completionCandidateLimit = normalized;
  try { await rpc.request("settings.update", { key: "editor.completionCandidateLimit", value: String(normalized) }); }
  catch (error) { settings.completionCandidateLimit = previous; reportError(error); }
}
async function updateMinimapEnabled(value: boolean): Promise<void> {
  const previous = settings.minimapEnabled; settings.minimapEnabled = value;
  try { await rpc.request("settings.update", { key: "editor.minimapEnabled", value: String(value) }); }
  catch (error) { settings.minimapEnabled = previous; reportError(error); }
}
async function updateWordWrapEnabled(value: boolean): Promise<void> {
  const previous = settings.wordWrapEnabled; settings.wordWrapEnabled = value;
  try { await rpc.request("settings.update", { key: "editor.wordWrapEnabled", value: String(value) }); }
  catch (error) { settings.wordWrapEnabled = previous; reportError(error); }
}
async function updateSqlDiagnosticsEnabled(value: boolean): Promise<void> {
  const previous = settings.sqlDiagnosticsEnabled; settings.sqlDiagnosticsEnabled = value;
  try { await rpc.request("settings.update", { key: "editor.sqlDiagnosticsEnabled", value: String(value) }); }
  catch (error) { settings.sqlDiagnosticsEnabled = previous; reportError(error); }
}
async function updateObjectInspectorOpacity(value: number): Promise<void> {
  const normalized = Math.max(1, Math.min(100, Math.round(value)));
  settings.objectInspectorOpacity = normalized;
  try { await rpc.request("settings.update", { key: "editor.objectInspectorOpacity", value: String(normalized) }); }
  catch (error) { reportError(error); }
}
async function updateDangerousStatementWarningEnabled(value: boolean): Promise<void> {
  const previous = settings.dangerousStatementWarningEnabled;
  settings.dangerousStatementWarningEnabled = value;
  try {
    await rpc.request("settings.update", {
      key: "editor.dangerousStatementWarningEnabled", value: String(value)
    });
  } catch (error) {
    settings.dangerousStatementWarningEnabled = previous;
    reportError(error);
  }
}
async function updateContinueOnError(value: boolean): Promise<void> {
  const previous = settings.continueOnError;
  settings.continueOnError = value;
  try {
    await rpc.request("settings.update", {
      key: "editor.continueOnError", value: String(value)
    });
  } catch (error) {
    settings.continueOnError = previous;
    reportError(error);
  }
}
function updateExecutionWarningMinutes(value: number[]): void {
  const candidate = normalizeExecutionWarningMinutes(value);
  settings.executionWarningMinutes = candidate;
  const epoch = ++executionWarningSaveEpoch;
  executionWarningSaveQueue = executionWarningSaveQueue.then(async () => {
    try {
      await rpc.request("settings.update", {
        key: "editor.executionWarningMinutes",
        value: serializeExecutionWarningMinutes(candidate),
      });
      if (epoch === executionWarningSaveEpoch) {
        confirmedExecutionWarningMinutes = [...candidate];
      }
    } catch (error) {
      if (epoch !== executionWarningSaveEpoch) return;
      settings.executionWarningMinutes = [...confirmedExecutionWarningMinutes];
      reportError(error);
    }
  });
}
function updateCompletionPreciseMatchingEnabled(value: boolean): void {
  settings.completionPreciseMatchingEnabled = value;
  const candidate = value;
  completionPreciseMatchingSaveQueue = completionPreciseMatchingSaveQueue.then(async () => {
    try {
      await rpc.request("settings.update", {
        key: "editor.completionPreciseMatchingEnabled",
        value: String(candidate),
      });
      confirmedCompletionPreciseMatchingEnabled = candidate;
    } catch (error) {
      if (settings.completionPreciseMatchingEnabled === candidate) {
        settings.completionPreciseMatchingEnabled = confirmedCompletionPreciseMatchingEnabled;
      }
      reportError(error);
    }
  });
}
function openShortcutSettings(): void {
  completionSnippetDrawer.value = false;
  shortcutDrawer.value = true;
}
function openCompletionSnippetSettings(): void {
  shortcutDrawer.value = false;
  completionSnippetDrawer.value = true;
}
function updateCompletionSnippets(value: SqlCompletionSnippet[]): void {
  const next = value.map((item) => ({ ...item }));
  settings.setCompletionSnippets(next);
  persistCompletionSnippets(next);
}
function persistCompletionSnippets(value: SqlCompletionSnippet[]): void {
  const candidate = value.map((item) => ({ ...item }));
  const epoch = completionSnippetSaveEpoch;
  completionSnippetSaveCount += 1;
  completionSnippetSaving.value = true;
  completionSnippetSaveQueue = completionSnippetSaveQueue.then(async () => {
    if (epoch !== completionSnippetSaveEpoch) return;
    try {
      await rpc.request("settings.update", {
        key: "editor.completionSnippets",
        value: serializeSqlCompletionSnippets(candidate),
      });
      if (epoch === completionSnippetSaveEpoch) {
        confirmedCompletionSnippets = candidate.map((item) => ({ ...item }));
      }
    } catch (error) {
      if (epoch !== completionSnippetSaveEpoch) return;
      completionSnippetSaveEpoch += 1;
      settings.setCompletionSnippets(confirmedCompletionSnippets);
      reportError(error);
    }
  }).finally(() => {
    completionSnippetSaveCount = Math.max(0, completionSnippetSaveCount - 1);
    completionSnippetSaving.value = completionSnippetSaveCount > 0;
  });
}
function updateShortcutBinding(actionId: ShortcutActionId, binding: ShortcutBinding): void {
  if (settings.shortcuts[actionId] === binding) return;
  const next: ShortcutBindings = { ...settings.shortcuts, [actionId]: binding };
  settings.setShortcuts(next);
  persistShortcutBindings(next);
}
function resetShortcutBindings(): void {
  const next: ShortcutBindings = { ...DEFAULT_SHORTCUT_BINDINGS };
  settings.setShortcuts(next);
  persistShortcutBindings(next);
}
function persistShortcutBindings(bindings: ShortcutBindings): void {
  const candidate: ShortcutBindings = { ...bindings };
  const epoch = shortcutSaveEpoch;
  shortcutSaveCount++;
  shortcutSaving.value = true;
  shortcutSaveQueue = shortcutSaveQueue.then(async () => {
    if (epoch !== shortcutSaveEpoch) return;
    try {
      await rpc.request("settings.update", {
        key: "keyboard.shortcuts",
        value: serializeShortcutBindings(candidate),
      });
      if (epoch === shortcutSaveEpoch) confirmedShortcutBindings = { ...candidate };
    } catch (error) {
      if (epoch !== shortcutSaveEpoch) return;
      shortcutSaveEpoch++;
      settings.setShortcuts(confirmedShortcutBindings);
      reportError(error);
    }
  }).finally(() => {
    shortcutSaveCount = Math.max(0, shortcutSaveCount - 1);
    shortcutSaving.value = shortcutSaveCount > 0;
  });
}
async function clearCompletionCaches(): Promise<void> {
  const stats = { ...metadata.completionStats };
  const size = formatCompletionBytes(stats.estimatedBytes);
  try {
    await ElMessageBox.confirm(
      `将清理约 ${size}（${stats.environmentCount}个环境）的补全缓存和已加载的对象树缓存。数据库连接和查询结果不会受到影响。`,
      "清理补全缓存",
      { type: "warning", confirmButtonText: "清理", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  completionNoticeTimers.forEach((timer) => window.clearTimeout(timer));
  completionNoticeTimers.clear();
  await completionClient.clear();
  metadata.clearCompletions();
  metadata.clearTreeCaches();
  objectExplorer.value?.resetTree();
  ElMessage.success(`已清理约 ${size} 的补全缓存和已加载对象树`);
}
function dismissStatusTask(id: string): void {
  if (id.startsWith("completion:")) metadata.dismissNotice(id.slice("completion:".length));
  else statusBar.dismiss(id);
}
function dataCommand(command: string): void {
  if (command === "import" && editors.active?.connection) csvDialog.value = true;
  else if (command === "history") historyDrawer.value = true;
  else if (command === "tasks") jdbcTaskManagerDrawer.value = true;
  else if (command === "exit") void closeApplication();
  else settingsDrawer.value = true;
}
async function closeTemporaryResult(executionId: string): Promise<void> {
  const tab = editors.active;
  if (!tab) return;
  const hasPending = Object.values(resultEdits.sessions).some((session) => session.editorId === tab.id
    && session.executionId === executionId && resultEdits.operations(session).length > 0);
  if (hasPending) {
    ElMessage.warning("该结果还有未应用的本地草稿，请先应用或撤销后再关闭");
    return;
  }
  const loading = resultLoading.value;
  if (loading?.editorId === tab.id && activeExecution.value?.executionId === executionId) {
    clearPendingResultBatches(tab.id, loading.executionId, loading.resultIndex);
    resultReloadRequired.delete(resultReloadKey(loading.editorId, loading.resultExecutionId, loading.resultIndex));
    rejectPendingLoadAll(tab.id, loading.executionId, new Error("结果已关闭"));
    resultLoading.value = undefined;
    try {
      await rpc.ensureOperational();
      await rpc.request("query.cancel", { editorId: tab.id, executionId: loading.executionId });
    } catch { /* closing the result remains authoritative */ }
  }
  try {
    await rpc.ensureOperational();
    await rpc.request("query.closeResult", { editorId: tab.id, executionId });
    const previous = queries.executionList(tab.id);
    const closedIndex = previous.findIndex(item => item.executionId === executionId);
    const wasActive = editors.activeId === tab.id && activeExecution.value?.executionId === executionId;
    monacoEditor.value?.releaseExecutionSources?.(executionId);
    resultEdits.finishExecution(tab.id, executionId);
    queries.removeExecution(tab.id, executionId);
    const remaining = queries.executionList(tab.id);
    for (const key of [...resultReloadRequired]) if (key.startsWith(`${tab.id}:${executionId}:`)) resultReloadRequired.delete(key);
    if (wasActive) {
      const neighbor = remaining[Math.min(closedIndex, remaining.length - 1)];
      const index = closedIndex < remaining.length ? 0 : neighbor?.results.at(-1)?.resultIndex ?? 0;
      activeResultIndex.value = neighbor ? index === 0 ? neighbor.executionId : `${neighbor.executionId}:${index}` : 0;
    }
  } catch (error) {
    ElMessage.error(message(error));
  }
}

function handleResultTabsWheel(event: WheelEvent): void {
  if (event.shiftKey || event.deltaY === 0 || event.deltaX !== 0) return;
  const root = (event.target instanceof HTMLElement ? event.target.closest(".result-tabs") : null)
    ?? event.currentTarget as HTMLElement | null;
  const nav = root?.querySelector<HTMLElement>(".el-tabs__nav");
  if (!nav) return;
  const translated = new WheelEvent("wheel", {
    bubbles: true, cancelable: true, deltaX: event.deltaY, deltaY: 0,
    deltaMode: event.deltaMode, ctrlKey: event.ctrlKey, metaKey: event.metaKey,
    altKey: event.altKey, shiftKey: false,
  });
  nav.dispatchEvent(translated);
  if (translated.defaultPrevented) event.preventDefault();
}
async function exportResult(request: ResultExportRequest): Promise<void> {
  if (!editors.active || activeExecution.value?.historical) {
    ElMessage.warning(request.scope === "full" ? "断线前快照不能重新执行完整导出" : "断线前快照不能通过服务端导出，可继续复制已加载内容");
    return;
  }
  if (resultEdits.hasChanges(editors.active.id) || editors.active.resultChangesDirty) {
    ElMessage.warning("请先确认并提交或回滚结果修改后再导出");
    return;
  }
  try {
    await rpc.downloadResultExport({ ...request, executionId: request.executionId, resultIndex: request.resultIndex });
    ElMessage.success(request.scope === "full" ? "已开始流式导出完整结果" : "已开始下载当前可见结果");
  } catch (error) {
    reportError(error);
  }
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
.cancel-execution-control {
  width: 116px;
  min-height: 32px;
  color: #fff;
  border-color: var(--db-warning);
  background: var(--db-warning);
}
.cancel-execution-control:hover,
.cancel-execution-control:focus-visible {
  color: #fff;
  border-color: color-mix(in srgb, var(--db-warning) 84%, #fff);
  background: color-mix(in srgb, var(--db-warning) 84%, #fff);
}
.cancel-execution-control.is-disabled,
.cancel-execution-control.is-disabled:hover,
.cancel-execution-control.is-loading {
  color: #fff;
  border-color: var(--db-warning);
  background: var(--db-warning);
  opacity: 0.58;
}
.query-actions {
  display: inline-flex;
  flex: none;
  width: 64px;
  max-width: 64px;
  height: 34px;
  overflow: hidden;
  border-radius: 10px;
  transform-origin: left center;
  will-change: max-width, opacity, transform;
}
.query-actions :deep(.el-button) {
  width: 32px;
  height: 34px;
  min-height: 34px;
  margin: 0;
  padding: 0;
  color: #fff;
}
.query-actions :deep(.el-button--success),
.query-actions :deep(.el-button--success:hover),
.query-actions :deep(.el-button--success:focus-visible),
.query-actions :deep(.el-button--success.is-disabled) {
  color: #fff;
  border-color: var(--db-success);
  background: var(--db-success);
}
.query-actions :deep(.el-button--danger),
.query-actions :deep(.el-button--danger:hover),
.query-actions :deep(.el-button--danger:focus-visible),
.query-actions :deep(.el-button--danger.is-disabled) {
  color: #fff;
  border-color: var(--db-danger);
  background: var(--db-danger);
}
.query-actions :deep(.el-button.is-disabled) { opacity: 0.58; }
.transaction-actions-enter-active { animation: transaction-actions-in 360ms cubic-bezier(.22, 1.35, .36, 1); }
.transaction-actions-leave-active {
  transition: max-width var(--duration-quick) var(--ease-smooth-out),
              opacity var(--duration-quick) var(--ease-smooth-out),
              transform var(--duration-quick) var(--ease-smooth-out);
}
.transaction-actions-leave-to {
  max-width: 0;
  opacity: 0;
  transform: translateX(-10px) scale(.94);
}
@keyframes transaction-actions-in {
  0% { max-width: 0; opacity: 0; transform: translateX(-12px) scale(.9); }
  68% { max-width: 68px; opacity: 1; transform: translateX(3px) scale(1.04); }
  84% { max-width: 62px; transform: translateX(-1px) scale(.99); }
  100% { max-width: 64px; opacity: 1; transform: translateX(0) scale(1); }
}
.app-toolbar :deep(.el-divider--vertical) { margin: 0 2px; border-color: var(--db-border); }
.app-toolbar :deep(kbd),
:global(.el-dropdown-menu kbd) {
  margin-left: 22px;
  color: var(--db-muted);
  font: 11px/1.2 "SF Mono", Menlo, monospace;
}
.toolbar-spacer { flex: 1; }
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
.result-content-panel,
.result-content-splitter {
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
}
.editor-area { display: flex; flex-direction: column; background: var(--db-editor-bg); }
.editor-tabs {
  flex: none;
  height: 36px;
  padding: 0 6px;
  background: var(--db-panel-soft);
  border-bottom: 1px solid var(--db-border-soft);
}
.editor-tabs :deep(.el-tabs__item) { display: inline-flex; align-items: center; padding: 0; }
.editor-tabs :deep(.el-dropdown) {
  position: relative;
  display: flex;
  align-items: center;
  align-self: stretch;
  min-width: 0;
  max-width: 204px;
  padding: 0 12px;
  cursor: grab;
  user-select: none;
}
.editor-tabs :deep(.el-dropdown.editor-tab-dragging) { opacity: .45; cursor: grabbing; }
.editor-tabs :deep(.el-dropdown.editor-tab-drop-before)::before,
.editor-tabs :deep(.el-dropdown.editor-tab-drop-after)::after {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 2px;
  border-radius: 2px;
  background: var(--db-accent);
  content: "";
}
.editor-tabs :deep(.el-dropdown.editor-tab-drop-before)::before { left: 0; }
.editor-tabs :deep(.el-dropdown.editor-tab-drop-after)::after { right: 0; }
.editor-tab-label { display: flex; align-items: center; gap: 6px; width: 100%; min-width: 0; }
.editor-tab-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dirty-dot { width: 6px; height: 6px; flex: none; border-radius: 50%; background: var(--db-accent); }
.dirty-dot.editor-tab-status-dirty { background: var(--db-accent); }
.dirty-dot.editor-tab-status-running { background: var(--db-warning); }
.dirty-dot.editor-tab-status-success { background: var(--db-success); }
.dirty-dot.editor-tab-status-error { background: var(--db-danger); }
.editor-widget { flex: 1; min-height: 0; }
:global(.result-transaction-decision p) { margin: 0 0 16px; }
:global(.result-transaction-decision__hint) {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
:global(.result-transaction-decision__actions) {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
:global(.result-transaction-decision__actions .el-button) { margin-left: 0; }
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
  .transaction-actions-enter-active,
  .transaction-actions-leave-active { animation: none; transition: opacity 100ms linear; }
  .transaction-actions-enter-from,
  .transaction-actions-leave-to { opacity: 0; transform: none; }
}
</style>
