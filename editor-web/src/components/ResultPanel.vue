<template>
  <section class="result-panel fill">
    <template v-if="resultTabs.length">
      <div class="result-header">
        <el-tabs v-model="activeIndex" class="result-tabs" @tab-remove="closeResultTab"
                 @tab-click="emitResultTabClick" @wheel="emitTabsWheel">
          <el-tab-pane v-for="(tab, index) in resultTabs" :key="tab.key" :name="tab.key"
                       :closable="tab.execution.temporary === true && !tab.execution.busy" :label="tab.result?.errorMessage
                         ? `错误 ${index + 1}` : `结果 ${index + 1}`" />
        </el-tabs>
        <div class="result-meta" aria-live="polite">
          <span>{{ summary }}</span>
          <el-tag v-if="execution?.historical" size="small" type="info" effect="plain">断线前快照</el-tag>
          <el-tag v-if="activeResult?.truncated" size="small" type="warning" effect="plain">已截断</el-tag>
        </div>
        <div class="result-actions" aria-label="结果操作">
          <template v-if="showResultEditActions">
            <Transition name="result-edit-actions">
              <div v-if="resultEditUnlocked" class="result-edit-operations"
                   role="group" aria-label="结果编辑操作">
                <span class="result-edit-operation">
                  <el-tooltip :content="applyResultChangesTooltip">
                    <el-button text :icon="CircleCheck" aria-label="应用更改"
                               :type="canApplyResultChanges ? 'success' : 'default'"
                               :disabled="!canApplyResultChanges" @click="$emit('apply-result-changes')" />
                  </el-tooltip>
                </span>
                <span class="result-edit-operation">
                  <el-tooltip content="撤销最后一项本地草稿">
                    <el-button text :icon="RefreshLeft" aria-label="撤销结果草稿"
                               :disabled="editDraftCount === 0" @click="undoResultDraft" />
                  </el-tooltip>
                </span>
                <span class="result-edit-operation">
                  <el-tooltip content="新增一条本地草稿记录">
                    <el-button text :icon="Plus" aria-label="新增行"
                               :disabled="!activeResult?.mutationTarget?.insertSupported"
                               @click="addResultRow" />
                  </el-tooltip>
                </span>
                <span class="result-edit-operation">
                  <el-tooltip content="将所选记录标记为待删除">
                    <el-button text :icon="Minus" aria-label="删除行"
                               :disabled="!canDeleteSelectedRows" @click="deleteSelectedResultRows" />
                  </el-tooltip>
                </span>
                <span class="result-edit-operation">
                  <el-tooltip content="查看旧值、新值和参数化 SQL">
                    <el-button text :icon="Document" aria-label="变更清单"
                               :disabled="editDraftCount + editAppliedCount === 0" @click="openChangesDialog" />
                  </el-tooltip>
                </span>
              </div>
            </Transition>
            <el-tooltip :content="resultEditTooltip">
              <el-button text class="result-edit-mode" :type="resultEditUnlocked ? 'primary' : 'default'"
                         :icon="EditPen" :disabled="!canToggleResultEdit"
                         :aria-pressed="resultEditUnlocked" aria-label="切换结果编辑模式"
                         @click="$emit('toggle-result-edit')">
                {{ resultEditUnlocked ? "编辑中" : "编辑模式" }}
              </el-button>
            </el-tooltip>
            <span class="result-action-divider" aria-hidden="true" />
          </template>
          <el-tooltip v-if="showRestoreLayout" :content="restoreLayoutTitle">
            <el-button text :icon="RefreshLeft" aria-label="复原列布局" @click="restoreLayout" />
          </el-tooltip>
          <el-select v-model="selectedColumnIndices" multiple filterable clearable collapse-tags collapse-tags-tooltip
                     fit-input-width
                     :max-collapse-tags="1" :filter-method="filterColumns" placeholder="筛选字段" size="small"
                     aria-label="筛选展示字段">
            <el-option v-for="column in filteredColumnOptions" :key="column.index" :label="column.label" :value="column.index">
              <div class="column-option">
                <span :title="column.label">{{ column.label }}</span>
                <small v-if="optionDetail(column)" :title="optionDetail(column)">{{ optionDetail(column) }}</small>
              </div>
            </el-option>
          </el-select>
          <Transition name="single-record-navigation">
            <div v-if="singleRecordMode" class="single-record-navigation" role="group" aria-label="单记录导航">
              <el-tooltip content="上一条记录">
                <el-button text :icon="ArrowLeft" aria-label="上一条记录"
                           :disabled="!canNavigatePrevious" @click="navigateSingleRecord(-1)" />
              </el-tooltip>
              <el-tooltip content="下一条记录">
                <el-button text :icon="ArrowRight" aria-label="下一条记录"
                           :disabled="!canNavigateNext" @click="navigateSingleRecord(1)" />
              </el-tooltip>
            </div>
          </Transition>
          <el-tooltip :content="singleRecordTitle">
            <el-button text class="single-record-button" :icon="Postcard"
                       :type="singleRecordMode ? 'primary' : 'default'"
                       :disabled="!canViewSingleRecord"
                       :aria-label="singleRecordMode ? '返回结果表格' : '单个记录查看'"
                       :aria-pressed="singleRecordMode"
                       @click="toggleSingleRecordView" />
          </el-tooltip>
          <div class="record-compare-control" role="group" aria-label="比较记录">
            <el-tooltip :content="recordComparisonTitle">
              <el-button text class="record-compare-button" :icon="ScaleToOriginal"
                         :type="recordComparisonEnabled ? 'primary' : 'default'"
                         :disabled="!canToggleRecordComparison"
                         :aria-pressed="recordComparisonEnabled" aria-label="比较记录"
                         @click="toggleRecordComparison" />
            </el-tooltip>
            <el-dropdown trigger="click" :disabled="singleRecordMode || !activeResult?.columns.length"
                         @command="recordComparisonCommand">
              <el-button text class="record-compare-options" :icon="ArrowDown" aria-label="比较记录选项" />
              <template #dropdown>
                <el-dropdown-menu class="record-compare-menu">
                  <el-dropdown-item command="highlight-identical">
                    <el-icon><Check v-if="settings.compareHighlightMode === 'identical'" /></el-icon>高亮显示相同
                  </el-dropdown-item>
                  <el-dropdown-item command="highlight-different">
                    <el-icon><Check v-if="settings.compareHighlightMode === 'different'" /></el-icon>高亮显示差异
                  </el-dropdown-item>
                  <el-dropdown-item divided command="scope-column">
                    <el-icon><Check v-if="settings.compareScope === 'column'" /></el-icon>比较单列
                  </el-dropdown-item>
                  <el-dropdown-item command="scope-record">
                    <el-icon><Check v-if="settings.compareScope === 'record'" /></el-icon>比较完整记录
                  </el-dropdown-item>
                  <el-dropdown-item divided command="toggle-case-sensitive">
                    <el-icon><Check v-if="settings.compareCaseSensitive" /></el-icon>区分大小写
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
          <el-tooltip :content="copySelectionTitle">
            <el-button text :icon="CopyDocument" :aria-label="copySelectionTitle" :disabled="!hasDataSelection" @click="copyCurrentSelection()" />
          </el-tooltip>
          <div class="result-export-control">
            <el-dropdown :disabled="!activeResult?.columns.length || serverExportBlocked" @command="exportCommand">
              <el-button text :icon="Download" aria-label="导出结果" title="导出结果" />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="csv">导出为 CSV</el-dropdown-item>
                  <el-dropdown-item command="excel">导出为 Excel</el-dropdown-item>
                  <el-dropdown-item command="sql" :disabled="!canExportAllSql">导出为 SQL 文件</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-popconfirm v-model:visible="exportPromptVisible"
                           title="当前结果已截断，请选择导出范围"
                           confirm-button-text="导出可见数据" cancel-button-text="导出全部数据"
                           :hide-icon="true" @confirm="exportPromptDecision('visible')"
                           @cancel="exportPromptDecision('full')">
              <template #reference><span class="result-export-popconfirm-anchor" aria-hidden="true" /></template>
            </el-popconfirm>
          </div>
        </div>
      </div>
      <div v-if="showExecutionLoading" class="result-loading" role="status" aria-live="polite"
           :aria-label="resultLoadingAnimation === 'sql-timeline' ? undefined : '正在执行 SQL'">
        <SqlExecutionTimelineLoader v-if="resultLoadingAnimation === 'sql-timeline'"
                                    :stage="timelineStage" :started-at="executionStartedAt"
                                    :color="resultSelectionBorder" />
        <WavePhysicsLoader v-else-if="resultLoadingAnimation === 'wave-physics'" :theme="app.theme" />
        <img v-else class="result-loading__image" :src="executionLoadingImage" alt="" aria-hidden="true" />
        <SqlExecutionTimer v-if="resultLoadingAnimation !== 'sql-timeline'" :started-at="executionStartedAt" />
      </div>
      <el-alert v-else-if="activeResult?.errorMessage" :title="activeResult.errorMessage" type="error" show-icon :closable="false" />
      <div v-else-if="activeResult?.columns.length" ref="tableHost" class="table-host" tabindex="0"
           @keydown="tableKeydown" @pointermove="autoScrollSelection">
        <ResultSingleRecordView v-if="singleRecordMode && selectedRecordRow" ref="singleRecordView"
                                :columns="visibleColumnOptions" :row="selectedRecordRow"
                                :buffer-screens="settings.scrollOptimizationBufferScreens"
                                :copy-separator="settings.copySeparator"
                                :layout-scope="settings.columnLayoutScope"
                                :execution-id="execution?.executionId || ''"
                                :editor-id="execution?.editorId || ''"
                                :result-index="activeResult?.resultIndex ?? 0"
                                :header-sorting-enabled="settings.headerSortingEnabled"
                                :header-filtering-enabled="settings.headerFilteringEnabled"
                                :zebra-stripes-enabled="settings.zebraStripesEnabled"
                                :initial-selection="singleRecordInitialSelection"
                                :editing-column-index="editingCell?.rowIndex === selectedRecordRow.sourceIndex
                                  ? editingCell.columnIndex : undefined"
                                :editing-value="editingCell?.value" :cell-states="resultCellStates"
                                @cell-contextmenu="handleSingleRecordContextmenu"
                                @row-contextmenu="handleSingleRecordRowContextmenu"
                                @selection-change="handleSingleRecordSelectionChange"
                                @layout-dirty="singleRecordLayoutDirty = $event"
                                @copy-text="copyText"
                                @cell-dblclick="handleSingleRecordDoubleClick"
                                @update:editing-value="updateEditingValue"
                                @commit-edit="commitCellEdit" @cancel-edit="cancelCellEdit" />
        <ResultVirtualGrid v-else ref="virtualGrid"
                           :rows="displayRows" :columns="virtualColumns" :header-height="headerHeight"
                           :buffer-screens="settings.scrollOptimizationBufferScreens"
                           :selection-mode="selectionMode" :cell-range="cellRange"
                           :selected-cell-keys="selectedCellKeys"
                           :focused-cell-key="focusedCellKey"
                           :selected-column-sources="selectedColumnSources"
                           :selected-row-sources="selectedRowSources"
                           :zebra-stripes-enabled="settings.zebraStripesEnabled"
                           :comparison-cell-keys="recordComparisonCellKeys"
                           :editing-cell="editingCell" :editing-value="editingCell?.value"
                           :cell-states="resultCellStates"
                           :row-classes="resultRowClasses"
                           :has-footer="!!sumSummary"
                           @cell-pointerdown="startCellSelection" @cell-pointerenter="extendCellSelection"
                           @cell-contextmenu="openCellMenu" @cell-dblclick="handleCellDoubleClick"
                           @select-all="selectAllResult"
                           @update:editing-value="updateEditingValue"
                           @commit-edit="commitCellEdit" @cancel-edit="cancelCellEdit"
                           @row-pointerdown="selectResultRow" @row-pointerenter="extendRowSelection"
                           @row-contextmenu="openRowMenu">
          <template #footer>
            <ResultSummaryFooter v-if="sumSummary" :total="sumSummary.total" :count="sumSummary.count"
                                 @close="sumSummary = undefined" />
          </template>
        </ResultVirtualGrid>
      </div>
      <el-result v-else icon="success" title="语句执行完成" :sub-title="`影响行数：${activeResult?.updateCount ?? 0}`" />
    </template>
    <div v-else-if="showExecutionLoading" class="result-loading" role="status" aria-live="polite"
         :aria-label="resultLoadingAnimation === 'sql-timeline' ? undefined : '正在执行 SQL'">
      <SqlExecutionTimelineLoader v-if="resultLoadingAnimation === 'sql-timeline'"
                                  :stage="timelineStage" :started-at="executionStartedAt"
                                  :color="resultSelectionBorder" />
      <WavePhysicsLoader v-else-if="resultLoadingAnimation === 'wave-physics'" :theme="app.theme" />
      <img v-else class="result-loading__image" :src="executionLoadingImage" alt="" aria-hidden="true" />
      <SqlExecutionTimer v-if="resultLoadingAnimation !== 'sql-timeline'" :started-at="executionStartedAt" />
    </div>
    <el-empty v-else class="result-empty" description="执行查询后在这里查看结果">
      <template #image><el-icon><DataAnalysis /></el-icon></template>
    </el-empty>
    <span v-if="announceTimelineSuccess" class="sql-timeline-success-announcement" role="status" aria-live="polite">
      Success: result set received
    </span>
    <ResultHeaderContextMenu :visible="headerMenu.visible" :x="headerMenu.x" :y="headerMenu.y"
                             :can-copy-data="canCopyHeaderData" :can-in="canCopyHeaderIn"
                             :can-move-left="canMoveSelectionLeft"
                             :can-move-right="canMoveSelectionRight" :can-sum="canSumHeaderData"
                             :can-export-csv="canExportHeader" :can-export-excel="canExportHeader"
                             :can-export-sql="canExportHeaderSql"
                             @close="closeHeaderMenu"
                             @command="headerMenuCommand" />
    <ResultDataContextMenu :visible="dataMenu.visible" :x="dataMenu.x" :y="dataMenu.y" :mode="dataMenu.mode"
                           :can-in="canCopyIn" :can-select="canCopySelect" :can-equals="canCopyEquals"
                           :can-insert="canCopyInsert" :can-update="canCopyUpdate"
                           :can-delete="canCopyDelete" :can-compare="canCompareCells" :can-sum="canSumCells"
                           :can-set-null="canSetSelectedCellNull"
                           :can-export-csv="canExportDataSelection" :can-export-excel="canExportDataSelection"
                           :can-export-sql="canExportDataSelectionSql"
                           :show-clone="resultEditUnlocked" :can-clone="canCloneSelectedRows"
                           :clone-busy="cloneBusy"
                           @close="closeDataMenu" @command="dataMenuCommand" />
    <ResultValueDialog v-model="valueDialog.visible" :value="valueDialog.value" />
    <ResultValueCompareDialog v-model="compareDialog" :left="compareValues.left" :right="compareValues.right"
                              :theme="app.theme" />
    <ResultLargeValueDialog v-if="largeValueEditor && execution && activeResult"
                            v-model="largeValueEditor.visible" :family="largeValueEditor.family"
                            :value="activeResult.rows[largeValueEditor.rowIndex]?.[largeValueEditor.columnIndex] ?? null"
                            :max-bytes="settings.maxResultLobBytes" :editor-id="execution.editorId"
                            :execution-id="execution.executionId" :result-index="activeResult.resultIndex"
                            :row-id="resultRowId(largeValueEditor.rowIndex)"
                            :column-index="largeValueEditor.columnIndex"
                            :column-name="activeResult.columns[largeValueEditor.columnIndex]"
                            @save="saveLargeValueDraft" />
    <el-dialog v-model="changesDialogVisible" title="结果变更清单" width="760px" append-to-body>
      <div class="result-change-summary">
        <span>目标表：{{ activeResult?.mutationTarget?.qualifiedName || "—" }}</span>
        <span>锁策略：{{ activeResult?.mutationTarget?.lockMode || "WAIT" }}</span>
        <span>草稿 {{ editDraftCount }} 项，已应用 {{ editAppliedCount }} 项</span>
      </div>
      <el-table :data="changeListRows" max-height="360" empty-text="没有结果变更">
        <el-table-column prop="kindLabel" label="操作" width="82" />
        <el-table-column prop="rowLabel" label="记录" width="88" />
        <el-table-column prop="changes" label="旧值 → 新值" min-width="210" show-overflow-tooltip />
        <el-table-column prop="sql" label="参数化 SQL" min-width="290" show-overflow-tooltip />
      </el-table>
      <el-alert v-if="previewError" class="result-change-preview-error" :title="previewError"
                type="warning" :closable="false" show-icon />
      <template #footer><el-button @click="changesDialogVisible = false">关闭</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, h, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import {
  ArrowDown, ArrowLeft, ArrowRight, Check, CircleCheck, CopyDocument, DataAnalysis, Document, Download, EditPen,
  Minus, Plus, Postcard, RefreshLeft, ScaleToOriginal
} from "@element-plus/icons-vue";
import type { TabsPaneContext } from "element-plus";
import type { QueryColumn, QueryExecutionState, QueryResult, ResultExportFormat, ResultExportRequest,
  ExecutionTimelineStage, SelectedResultColumn } from "../types";
import { matchesColumnQuery, resultColumnOptions, type ColumnOption } from "../columnFilter";
import { autoColumnWidth, clampColumnWidth, columnIdentityKeys, defaultColumnWidth, moveColumnsToEdge, sameColumnSet,
  type ColumnEdge, type DropSide } from "../columnLayout";
import { useColumnLayoutStore } from "../stores/columnLayout";
import { useSettingsStore } from "../stores/settings";
import { useAppStore } from "../stores/app";
import { useQueryStore } from "../stores/query";
import { useResultEditStore, type ResultMutationValue } from "../stores/resultEdits";
import { resultColumnRemarksText, resultCopyText, type ResultCopyMode } from "../resultCopy";
import { writeClipboardText } from "../clipboard";
import { cellSelectionKey, copyCellSql, copyEqualsSql, copyGrid, copyInPredicate, copyRowSql, copySelectSql, normalizeRange, selectRows,
  sumDecimalValues, visibleRows, type CellPoint, type CellRange, type DecimalSumResult,
  type ResultFilter, type ResultSort, type ResultSqlColumn, type SelectedCell, type SelectedRowColumns, type ViewRow } from "../resultGrid";
import type { ResultGridScrollPosition, ResultVirtualColumn } from "../resultVirtualGrid";
import { comparisonCellKeys, type ResultCompareHighlightMode, type ResultCompareScope } from "../resultCompare";
import ResultHeaderContextMenu, { type HeaderMenuCommand } from "./ResultHeaderContextMenu.vue";
import ResultHeaderTools from "./ResultHeaderTools.vue";
import ResultDataContextMenu, { type DataMenuCommand } from "./ResultDataContextMenu.vue";
import ResultSingleRecordView, { type SingleRecordSelectionState } from "./ResultSingleRecordView.vue";
import ResultVirtualGrid from "./ResultVirtualGrid.vue";
import ResultSummaryFooter from "./ResultSummaryFooter.vue";
import ResultValueDialog from "./ResultValueDialog.vue";
import ResultValueCompareDialog from "./ResultValueCompareDialog.vue";
import ResultLargeValueDialog from "./ResultLargeValueDialog.vue";
import SqlExecutionTimer from "./SqlExecutionTimer.vue";
import SqlExecutionTimelineLoader from "./SqlExecutionTimelineLoader.vue";
import WavePhysicsLoader from "./WavePhysicsLoader.vue";
import { shortcutTooltip } from "../shortcuts";
import { rpc } from "../bridge/rpc";

const props = withDefaults(defineProps<{
  execution?: QueryExecutionState;
  executions?: QueryExecutionState[];
  activeResultIndex: string | number;
  executing?: boolean;
  executionStartedAt?: number;
  executionTimelineStage?: ExecutionTimelineStage;
  showResultEditActions?: boolean;
  resultEditUnlocked?: boolean;
  canToggleResultEdit?: boolean;
  resultEditTooltip?: string;
  canApplyResultChanges?: boolean;
  applyResultChangesTooltip?: string;
}>(), { executing: false, showResultEditActions: false, resultEditUnlocked: false,
  canToggleResultEdit: false, resultEditTooltip: "当前结果不可编辑",
  canApplyResultChanges: false, applyResultChangesTooltip: "没有待应用的本地草稿" });
const emit = defineEmits<{
  "export-result": [request: ResultExportRequest];
  "close-result": [executionId: string];
  "tabs-wheel": [event: WheelEvent];
  "result-tab-click": [tabKey: string | number];
  "update:active-result-index": [resultIndex: string | number];
  "selected-column": [column: SelectedResultColumn | undefined];
  "selected-row-count": [count: number];
  "selected-status-text": [text: string];
  "toggle-result-edit": [];
  "apply-result-changes": [];
  "update-compare-highlight-mode": [value: ResultCompareHighlightMode];
  "update-compare-scope": [value: ResultCompareScope];
  "update-compare-case-sensitive": [value: boolean];
}>();
const columnLayouts = useColumnLayoutStore();
const settings = useSettingsStore();
const app = useAppStore();
const queries = useQueryStore();
const resultEdits = useResultEditStore();
const tableHost = ref<HTMLElement>();

function emitTabsWheel(event: WheelEvent): void {
  emit("tabs-wheel", event);
}

function emitResultTabClick(tab: TabsPaneContext): void {
  const tabKey = tab.paneName;
  if (tabKey !== undefined) emit("result-tab-click", tabKey);
}
const virtualGrid = ref<{
  getScrollPosition: () => ResultGridScrollPosition;
  setScrollPosition: (position: ResultGridScrollPosition) => void;
  scrollCellIntoView: (rowIndex: number, columnIndex: number) => void;
}>();
const singleRecordView = ref<{
  getCopyText: (includeHeaders?: boolean) => string | undefined;
  getSelectionState: () => SingleRecordSelectionState;
  clearSelection: () => void;
  handleKeydown: (event: KeyboardEvent) => void;
  isLayoutDirty: () => boolean;
  resetLayout: () => void;
}>();
const activeLayout = ref<{ layoutKey: string; viewKey: string; identities: string[] }>();
const dropTarget = ref<{ identity: string; side: DropSide }>();
const resizing = ref<{ identity: string; startX: number; startWidth: number }>();
const headerMenu = ref({ visible: false, x: 0, y: 0 });
const dataMenu = ref<{ visible: boolean; x: number; y: number; mode: "cells" | "rows" }>(
  { visible: false, x: 0, y: 0, mode: "cells" });
const exportPromptVisible = ref(false);
const pendingExportFormat = ref<ResultExportFormat>();
// A synthetic metadata cell in the single-record adapter can still use the
// ordinary data menu for copying, but it must not expose SQL-generation
// commands that would silently target the source value column.
const singleRecordSqlAllowed = ref(true);
const singleRecordSelection = ref<SingleRecordSelectionState>();
const singleRecordSourceIndex = ref<number>();
const singleRecordInitialSelection = ref<{ mode: "cells" | "rows"; fields: number[] }>({ mode: "cells", fields: [] });
const singleRecordEntryMode = ref<"cells" | "rows">("cells");
const singleRecordEntryColumns = ref<number[]>([]);
const singleRecordLayoutDirty = ref(false);
const singleRecordSyntheticColumns: QueryColumn[] = ["字段名", "字段值", "字段备注", "字段类型"].map((label) => ({
  label, name: label, remarks: "", catalog: "", schema: "", table: "", typeName: "VARCHAR", jdbcType: 12
}));

function resetStoredSingleRecordLayout(): void {
  const currentExecution = execution.value;
  const result = activeResult.value;
  if (!currentExecution || !result) return;
  const synthetic: QueryResult = {
    resultIndex: result.resultIndex, sql: "", type: "QUERY", columns: singleRecordSyntheticColumns.map((column) => column.label),
    columnDetails: singleRecordSyntheticColumns, rows: [], updateCount: -1, truncated: false, durationMs: 0, complete: true
  };
  const state = columnLayouts.ensure({
    scope: settings.columnLayoutScope, executionId: currentExecution.executionId, editorId: currentExecution.editorId,
    result: synthetic, defaultWidths: [180, 300, 220, 160], namespace: "single-record"
  });
  columnLayouts.reset(state.layoutKey, state.viewKey, state.identities, [180, 300, 220, 160]);
}
let dragPreview: HTMLElement | undefined;
let measureContext: CanvasRenderingContext2D | null | undefined;
let singleRecordReturnPosition: ResultGridScrollPosition | undefined;
const activeIndex = computed({
  get: () => props.activeResultIndex,
  set: (value: string | number) => {
    const tab = resultTabs.value.find((item) => item.key === String(value));
    emit("update:active-result-index", !props.executions?.length && visibleExecutions.value.length === 1
      ? tab?.result?.resultIndex ?? value : value);
  }
});
const selectedColumns = ref<Record<string, number[]>>({});
interface FieldVisibilityTemplate {
  identities: string[];
  /** Undefined means every identity in the matching field set is visible. */
  visibleIdentities?: string[];
}
const fieldVisibilityTemplates = ref<Record<string, FieldVisibilityTemplate>>({});
const columnQuery = ref("");
const sorts = ref<Record<string, ResultSort | undefined>>({});
const filters = ref<Record<string, ResultFilter[]>>({});
const cellRange = ref<CellRange>();
const cellAnchor = ref<CellPoint>();
const selectedCells = ref<SelectedCell[]>([]);
const focusedCell = ref<{ sourceRow: number; sourceColumn: number }>();
const selectedColumnIndex = ref<number>();
const selectingCells = ref(false);
const selectingRows = ref(false);
const selectingColumns = ref(false);
const columnSelectionAnchor = ref<string>();
const columnSelectionBase = ref<string[]>([]);
const columnSelectionMode = ref<"replace" | "add" | "remove">("replace");
const columnPointerState = ref<{ identity: string; wasSelected: boolean }>();
const suppressNextColumnClick = ref(false);
const rowDragAnchor = ref<number>();
const rowDragBase = ref<number[]>([]);
const rowDragMode = ref<"replace" | "add" | "remove">("replace");
const selectedRowSources = ref<number[]>([]);
const rowAnchor = ref<number>();
const selectionMode = ref<"cells" | "rows" | "columns">("cells");
const singleRecordMode = ref(false);
const valueDialog = ref<{ visible: boolean; value: string | null }>({ visible: false, value: null });
const compareDialog = ref(false);
const recordComparisonEnabled = ref(false);
const largeValueEditor = ref<{ visible: boolean; rowIndex: number; columnIndex: number;
  family: "raw" | "clob" | "blob" }>();
const cloneBusy = ref(false);
const editingCell = ref<{
  rowIndex: number;
  columnIndex: number;
  value: string | null;
  valueAtOpen: string | null;
}>();
const sumSummary = ref<{ total: string; count: number }>();
const executionLoadingImage = computed(() => app.theme === "dark"
  ? "/assets/branding/dbstudio-sql-loading-v4-dark.webp"
  : "/assets/branding/dbstudio-sql-loading-v4.webp");
const resultLoadingAnimation = computed(() => settings.colorSchemes[app.theme].result.loadingAnimation);
const resultSelectionBorder = computed(() => settings.colorSchemes[app.theme].result.selectionBorder);
const timelineStage = computed<ExecutionTimelineStage>(() => props.executionTimelineStage
  ?? (props.executing ? "thinking" : "preparing-result"));
const visibleExecutions = computed(() => props.executions?.length ? props.executions
  : props.execution ? [props.execution] : []);
interface ResultTab { key: string; execution: QueryExecutionState; result?: QueryExecutionState["results"][number]; }
const resultTabs = computed<ResultTab[]>(() => visibleExecutions.value.flatMap((item) => item.results.length
  ? item.results.map((result) => ({ key: result.resultIndex === 0 ? item.executionId : `${item.executionId}:${result.resultIndex}`,
    execution: item, result }))
  : item.busy ? [{ key: item.executionId, execution: item }] : []));
const activeTab = computed(() => resultTabs.value.find((item) => item.key === String(activeIndex.value))
  ?? (visibleExecutions.value.length === 1
    ? resultTabs.value.find((item) => item.result?.resultIndex === Number(activeIndex.value)) : undefined)
  ?? resultTabs.value[0]);
const execution = computed(() => activeTab.value?.execution);
const showExecutionLoading = computed(() => props.executions?.length
  ? Boolean((props.executing && !activeTab.value?.execution.busy)
    || (activeTab.value?.execution.busy && !activeTab.value?.result))
  : Boolean(props.executing && (!props.execution?.busy || props.execution.results.length === 0)));
const activeResult = computed(() => activeTab.value?.result);
const announceTimelineSuccess = computed(() => resultLoadingAnimation.value === "sql-timeline"
  && props.executionTimelineStage === "success" && Boolean(activeResult.value));
const activeEditSession = computed(() => {
  const currentExecution = execution.value;
  const result = activeResult.value;
  return currentExecution && result
    ? resultEdits.session(currentExecution.editorId, currentExecution.executionId, result.resultIndex)
    : undefined;
});
const resultEditUnlocked = computed(() => activeEditSession.value?.unlocked === true);
const editDraftCount = computed(() => activeEditSession.value
  ? resultEdits.operations(activeEditSession.value).length : 0);
const editAppliedCount = computed(() => {
  const current = activeEditSession.value;
  if (!current) return 0;
  return current.inserts.filter((item) => item.status === "applied").length
    + current.deletes.filter((item) => item.status === "applied").length
    + new Set(current.cells.filter((cell) => cell.appliedMutation || cell.confirmedValue !== cell.originalValue)
      .map((cell) => cell.rowId || cell.rowIndex)).size;
});
const resultRowClasses = computed<Record<number, string>>(() => {
  const currentExecution = execution.value;
  const result = activeResult.value;
  if (!currentExecution || !result) return {};
  const values: Record<number, string> = {};
  for (let rowIndex = 0; rowIndex < result.rows.length; rowIndex++) {
    const rowId = result.rowIds?.[rowIndex];
    const inserted = activeEditSession.value?.inserts.find((item) => item.rowId === rowId);
    if (inserted) values[rowIndex] = inserted.status === "applied" ? "result-row-inserted-applied" : "result-row-inserted";
    if (resultEdits.isDeleted(currentExecution.editorId, currentExecution.executionId, result.resultIndex, rowId)) {
      values[rowIndex] = "result-row-deleted";
    }
  }
  return values;
});
const canDeleteSelectedRows = computed(() => Boolean(resultEditUnlocked.value
  && activeResult.value?.mutationTarget?.deleteSupported && selectedRowSources.value.length));
const canCloneSelectedRows = computed(() => {
  const currentExecution = execution.value;
  const result = activeResult.value;
  if (!currentExecution || !result || !resultEditUnlocked.value || !result.mutationTarget?.insertSupported
      || cloneBusy.value || !selectedRowsInDisplayOrder.value.length) return false;
  return selectedRowsInDisplayOrder.value.every((row) => !resultEdits.isDeleted(
    currentExecution.editorId, currentExecution.executionId, result.resultIndex, resultRowId(row.sourceIndex)));
});
const changesDialogVisible = ref(false);
const changePreviews = ref<Array<{ operationId: string; sql: string; binds: string[] }>>([]);
const previewError = ref("");
const changeListRows = computed(() => {
  const current = activeEditSession.value;
  const result = activeResult.value;
  if (!current || !result) return [];
  const previews = new Map(changePreviews.value.map((item) => [item.operationId, item]));
  return resultEdits.operations(current, false).map((operation) => {
    const preview = previews.get(operation.operationId);
    const cells = operation.kind === "update" ? current.cells.filter((cell) =>
      (operation.rowId ? cell.rowId === operation.rowId : cell.rowIndex === operation.rowIndex)) : [];
    const changes = operation.kind === "insert"
      ? operation.values.map((item) => `${result.columns[item.columnIndex]}=${mutationValueText(item.value)}`).join("，")
      : operation.kind === "delete" ? "整行删除"
        : cells.map((cell) => `${result.columns[cell.columnIndex]}: ${cell.originalValue ?? "NULL"} → ${cell.draftValue ?? "NULL"}`).join("，");
    const insertOrigin = operation.kind === "insert"
      ? current.inserts.find((item) => item.operationId === operation.operationId)?.origin : undefined;
    return { operationId: operation.operationId,
      kindLabel: operation.kind === "insert" ? insertOrigin === "clone" ? "克隆新增" : "新增"
        : operation.kind === "delete" ? "删除" : "更新",
      rowLabel: operation.kind === "insert" ? insertOrigin === "clone" ? "克隆行" : "新增行"
        : `第 ${operation.rowIndex + 1} 行`, changes,
      sql: preview ? `${preview.sql}  [${preview.binds.join(", ")}]` : "已应用到当前事务" };
  });
});
const resultCellStates = computed<Record<string, "pending" | "posted" | "error">>(() => {
  const currentExecution = execution.value;
  const result = activeResult.value;
  if (!currentExecution || !result) return {};
  const values: Record<string, "pending" | "posted" | "error"> = {};
  for (const cell of activeEditSession.value?.cells ?? []) {
    const state = resultEdits.cellState(currentExecution.editorId, currentExecution.executionId,
      result.resultIndex, cell.rowIndex, cell.columnIndex);
    if (state) values[`${cell.rowIndex}:${cell.columnIndex}`] = state;
  }
  return values;
});
const headerHeight = computed(() => settings.showColumnRemarksInHeader ? 48 : 32);
const resultKey = computed(() => `${execution.value?.executionId ?? "result"}:${activeResult.value?.resultIndex ?? 0}`);
function fieldVisibilityTemplateKey(currentExecution = execution.value, result = activeResult.value): string | undefined {
  if (settings.columnLayoutScope !== "editor" || currentExecution?.temporary === true
      || result?.sourceStartOffset === undefined) return undefined;
  return `${currentExecution.editorId}:${result.sourceStartOffset}:${result.resultIndex}`;
}

function rememberFieldVisibility(selected: number[]): void {
  const currentExecution = execution.value;
  const result = activeResult.value;
  const active = activeLayout.value;
  if (!currentExecution || !result || !active) return;
  const identities = selected.map((index) => active.identities[index])
    .filter((identity): identity is string => !!identity);
  selectedColumns.value = { ...selectedColumns.value, [resultKey.value]: selected };
  const templateKey = fieldVisibilityTemplateKey(currentExecution, result);
  if (!templateKey) return;
  const visibleIdentities = identities.length && identities.length < active.identities.length ? identities : undefined;
  fieldVisibilityTemplates.value = { ...fieldVisibilityTemplates.value,
    [templateKey]: { identities: [...active.identities], visibleIdentities } };
}

function inheritedFieldVisibility(currentExecution: QueryExecutionState, result: QueryResult,
                                  identities: string[]): number[] | undefined {
  const templateKey = fieldVisibilityTemplateKey(currentExecution, result);
  if (!templateKey) return undefined;
  const template = fieldVisibilityTemplates.value[templateKey];
  if (!template || !sameColumnSet(template.identities, identities)) return undefined;
  if (!template.visibleIdentities) return [];
  const visible = new Set(template.visibleIdentities);
  return identities.map((identity, index) => visible.has(identity) ? index : -1).filter((index) => index >= 0);
}
const activeSort = computed(() => sorts.value[resultKey.value]);
const activeFilters = computed(() => filters.value[resultKey.value] ?? []);
const selectedColumnIndices = computed<number[]>({
  get: () => selectedColumns.value[resultKey.value] ?? [],
  set: (value) => rememberFieldVisibility(value)
});
const columnOptions = computed(() => resultColumnOptions(activeResult.value?.columns ?? [], activeResult.value?.columnDetails));
const filteredColumnOptions = computed(() => columnOptions.value.filter((column) => matchesColumnQuery(column, columnQuery.value)));
const selectedVisibleColumnOptions = computed(() => {
  if (!selectedColumnIndices.value.length) return columnOptions.value;
  const selected = new Set(selectedColumnIndices.value);
  return columnOptions.value.filter((column) => selected.has(column.index));
});
const defaultWidths = computed(() => columnOptions.value.map((column) => defaultColumnWidth(column.label)));
const currentIdentities = computed(() => activeLayout.value?.identities
  ?? columnIdentityKeys(activeResult.value?.columns ?? [], activeResult.value?.columnDetails));
const visibleIdentities = computed(() => selectedVisibleColumnOptions.value.map((column) => currentIdentities.value[column.index]));
const visibleColumnOptions = computed(() => {
  const active = activeLayout.value;
  if (!active) return selectedVisibleColumnOptions.value;
  const byIdentity = new Map(selectedVisibleColumnOptions.value.map((column) => [currentIdentities.value[column.index], column]));
  return columnLayouts.displayedOrder(active.layoutKey, active.viewKey, visibleIdentities.value,
    selectedColumnIndices.value.length > 0).map((identity) => byIdentity.get(identity)).filter((column): column is ColumnOption => !!column);
});
const selectedColumnSources = computed(() => {
  if (selectionMode.value !== "columns" || !activeLayout.value) return [];
  const selected = new Set(columnLayouts.view(activeLayout.value.viewKey).selected);
  return visibleColumnOptions.value
    .filter((column) => selected.has(currentIdentities.value[column.index]))
    .map((column) => column.index);
});
const summary = computed(() => {
  const result = activeResult.value;
  if (!result) return "";
  if (execution.value?.busy) return "正在执行…";
  if (!result.columns.length) return `${result.updateCount} 行受影响 · ${result.durationMs} ms`;
  return displayRows.value.length === result.rows.length ? `${result.rows.length} 行 · ${result.durationMs} ms`
    : `显示 ${displayRows.value.length} / 已加载 ${result.rows.length} 行 · ${result.durationMs} ms`;
});

watch(() => execution.value?.executionId, () => {
  editingCell.value = undefined;
  clearSelection();
  sumSummary.value = undefined;
  compareDialog.value = false;
  recordComparisonEnabled.value = false;
  valueDialog.value.visible = false;
  sorts.value = {}; filters.value = {};
  columnQuery.value = "";
  exportPromptVisible.value = false;
  pendingExportFormat.value = undefined;
  singleRecordLayoutDirty.value = false;
  closeHeaderMenu(); closeDataMenu();
});
watch(activeIndex, () => {
  editingCell.value = undefined;
  clearSelection(); sumSummary.value = undefined; compareDialog.value = false; recordComparisonEnabled.value = false;
  valueDialog.value.visible = false;
  columnQuery.value = ""; closeHeaderMenu(); closeDataMenu();
  exportPromptVisible.value = false;
  pendingExportFormat.value = undefined;
  singleRecordLayoutDirty.value = false;
});
watch(() => activeResult.value?.columnDetails, () => emitSelectedColumn());
watch([
  () => execution.value?.executionId,
  () => execution.value?.editorId,
  () => activeResult.value?.resultIndex,
  () => activeResult.value?.columns,
  () => activeResult.value?.columnDetails,
  () => settings.columnLayoutScope
], activateLayout, { immediate: true });
watch([() => activeLayout.value?.viewKey, () => selectedColumnIndices.value.join(",")], syncVisibleFilter);
watch(() => visibleColumnOptions.value.map((column) => column.index).join(","), () => {
  reconcileViewState(); detachCellRange(); reconcileSelectedColumn();
});
watch(() => settings.headerSortingEnabled, (enabled) => { if (!enabled) { sorts.value = {}; detachCellRange(); } });
watch(() => settings.headerFilteringEnabled, (enabled) => {
  if (!enabled) { filters.value = {}; detachCellRange(); sumSummary.value = undefined; }
});
const displayRows = computed(() => visibleRows(activeResult.value?.rows ?? [], columnOptions.value,
  settings.headerSortingEnabled ? activeSort.value : undefined,
  settings.headerFilteringEnabled ? activeFilters.value : []));
const selectedCellKeySet = computed(() => new Set(selectedCells.value.map((cell) =>
  cellSelectionKey(cell.sourceRow, cell.sourceColumn))));
const selectedCellKeys = computed(() => [...selectedCellKeySet.value]);
const focusedCellKey = computed(() => focusedCell.value
  ? cellSelectionKey(focusedCell.value.sourceRow, focusedCell.value.sourceColumn)
  : undefined);
const selectedCellsInView = computed<SelectedCell[]>(() => {
  const rowPositions = new Map(displayRows.value.map((row, index) => [row.sourceIndex, index]));
  const columnPositions = new Map(visibleColumnOptions.value.map((column, index) => [column.index, index]));
  return selectedCells.value.map((cell) => {
    const row = rowPositions.get(cell.sourceRow);
    const column = columnPositions.get(cell.sourceColumn);
    if (row === undefined || column === undefined) return undefined;
    return {
      row,
      sourceRow: cell.sourceRow,
      column,
      sourceColumn: cell.sourceColumn,
      value: activeResult.value?.rows[cell.sourceRow]?.[cell.sourceColumn] ?? null
    };
  }).filter((cell): cell is SelectedCell => !!cell);
});
const recordComparisonAnchor = computed(() => {
  if (singleRecordMode.value || !focusedCell.value) return undefined;
  const rowVisible = displayRows.value.some((row) => row.sourceIndex === focusedCell.value?.sourceRow);
  const columnVisible = visibleColumnOptions.value.some((column) => column.index === focusedCell.value?.sourceColumn);
  return rowVisible && columnVisible ? focusedCell.value : undefined;
});
const canToggleRecordComparison = computed(() => recordComparisonEnabled.value
  || Boolean(recordComparisonAnchor.value && displayRows.value.length > 1));
const recordComparisonCellKeys = computed(() => {
  const anchor = recordComparisonAnchor.value;
  if (!recordComparisonEnabled.value || !anchor) return [];
  return comparisonCellKeys(displayRows.value, visibleColumnOptions.value.map((column) => column.index), anchor, {
    highlightMode: settings.compareHighlightMode,
    scope: settings.compareScope,
    caseSensitive: settings.compareCaseSensitive
  });
});
watch([recordComparisonAnchor, () => displayRows.value.length], ([anchor, rowCount]) => {
  if (recordComparisonEnabled.value && (!anchor || rowCount < 2)) recordComparisonEnabled.value = false;
});
watch(() => activeResult.value?.rows, (rows, previous) => {
  if (previous && rows !== previous) sumSummary.value = undefined;
});
const virtualColumns = computed<ResultVirtualColumn[]>(() => visibleColumnOptions.value.map((column, visibleIndex) => {
  const identity = currentIdentities.value[column.index];
  const headerRenderer = () => renderHeader(column, identity);
  return {
    key: `c${column.index}`,
    label: column.label,
    sourceIndex: column.index,
    visibleIndex,
    width: resultColumnWidth(column, identity),
    headerRenderer,
    headerCellRenderer: headerRenderer,
    cellRenderer: ({ rowData, rowIndex }) => virtualCellRenderer(rowData, rowIndex, column, visibleIndex)
  };
}));

function virtualCellRenderer(rowData: ViewRow, rowIndex: number, column: ColumnOption, visiblePosition: number) {
  const cellData = rowData.cells[column.index] ?? null;
  const selected = selectedCellKeySet.value.has(cellSelectionKey(rowData.sourceIndex, column.index));
  const focused = focusedCellKey.value === cellSelectionKey(rowData.sourceIndex, column.index);
  if (editingCell.value?.rowIndex === rowData.sourceIndex
      && editingCell.value.columnIndex === column.index) {
    return h("input", {
      class: "result-cell-editor",
      value: editingCell.value.value ?? "",
      "aria-label": "编辑结果值",
      autofocus: true,
      onInput: (event: Event) => updateEditingValue((event.target as HTMLInputElement).value),
      onKeydown: (event: KeyboardEvent) => {
        if (event.key === "Enter") { event.preventDefault(); commitCellEdit("enter"); }
        else if (event.key === "Escape") { event.preventDefault(); cancelCellEdit(); }
      },
      onBlur: () => commitCellEdit("blur")
    });
  }
  const editState = resultCellStates.value[`${rowData.sourceIndex}:${column.index}`];
  return h("span", {
    class: ["result-cell", cellData === null ? "null-value" : cellData.startsWith?.("0x") ? "binary-value" : "",
      selectionMode.value === "columns" && isColumnSelected(column.index) ? "column-selected" : "",
      selectionMode.value === "cells" && selected ? "selected" : "",
      selectionMode.value === "cells" && focused ? "focused" : "",
      editState === "pending" ? "result-cell-pending" : "",
      editState === "posted" ? "result-cell-posted" : "",
      editState === "error" ? "result-cell-error" : ""],
    title: cellData !== null && cellData.length >= 40 ? cellData : undefined,
    onPointerdown: (event: PointerEvent) => startCellSelection(event, rowIndex, visiblePosition),
    onPointerenter: () => extendCellSelection(rowIndex, visiblePosition),
    onContextmenu: (event: MouseEvent) => openCellMenu(event, rowIndex, visiblePosition, rowData),
    onDblclick: () => handleCellDoubleClick(rowIndex, visiblePosition, rowData)
  }, cellData === null ? "NULL" : cellData);
}

function resultColumnWidth(column: ColumnOption, identity: string): number {
  const stored = activeLayout.value ? columnLayouts.layout(activeLayout.value.layoutKey) : undefined;
  return stored?.widths[identity] ?? defaultColumnWidth(column.label);
}

function isColumnSelected(sourceIndex: number): boolean {
  const active = activeLayout.value;
  return selectionMode.value === "columns" && !!active
    && columnLayouts.view(active.viewKey).selected.includes(currentIdentities.value[sourceIndex]);
}

function clearCellAndRowSelection(): void {
  window.removeEventListener("pointerup", finishCellSelection);
  window.removeEventListener("pointerup", finishRowSelection);
  selectingCells.value = false;
  selectingRows.value = false;
  cellRange.value = undefined;
  cellAnchor.value = undefined;
  selectedCells.value = [];
  focusedCell.value = undefined;
  selectedRowSources.value = [];
  rowAnchor.value = undefined;
  selectedColumnIndex.value = undefined;
  emit("selected-column", undefined);
}

function selectAllResult(): void {
  if (singleRecordMode.value) return;
  clearColumnHeaderSelection();
  clearCellAndRowSelection();
  selectionMode.value = "rows";
  selectedRowSources.value = displayRows.value.map((row) => row.sourceIndex);
  rowAnchor.value = selectedRowSources.value[0];
  tableHost.value?.focus({ preventScroll: true });
}

function activateColumnSelection(): void {
  clearCellAndRowSelection();
  selectionMode.value = "columns";
}

function clearColumnHeaderSelection(): void {
  const active = activeLayout.value;
  if (active) columnLayouts.clearSelection(active.viewKey);
  selectingColumns.value = false;
  columnSelectionAnchor.value = undefined;
  columnSelectionBase.value = [];
  columnPointerState.value = undefined;
  suppressNextColumnClick.value = false;
  window.removeEventListener("pointerup", finishColumnSelection);
}

function activateLayout(): void {
  const result = activeResult.value;
  const currentExecution = execution.value;
  if (!result || !currentExecution) { activeLayout.value = undefined; return; }
  activeLayout.value = columnLayouts.ensure({
    scope: settings.columnLayoutScope, executionId: currentExecution.executionId, editorId: currentExecution.editorId,
    result, defaultWidths: columnOptions.value.map((column) => defaultColumnWidth(column.label))
  });
  const key = resultKey.value;
  if (!Object.prototype.hasOwnProperty.call(selectedColumns.value, key)) {
    const inherited = inheritedFieldVisibility(currentExecution, result, activeLayout.value.identities);
    selectedColumns.value = { ...selectedColumns.value, [key]: inherited ?? [] };
  }
  syncVisibleFilter();
}

function syncVisibleFilter(): void {
  const active = activeLayout.value;
  if (!active) return;
  columnLayouts.setFilter(active.viewKey, visibleIdentities.value, selectedColumnIndices.value.length > 0);
}

function renderHeader(column: ColumnOption, identity: string) {
  const active = activeLayout.value;
  const view = active ? columnLayouts.view(active.viewKey) : undefined;
  const selected = !!view?.selected.includes(identity);
  const order = visibleColumnOptions.value.map((item) => currentIdentities.value[item.index]);
  const firstSelected = view?.selected.find((item) => order.includes(item));
  const drop = dropTarget.value?.identity === identity ? dropTarget.value.side : undefined;
  return h("div", {
    class: ["result-column-header", selected ? "selected" : "", drop ? `drop-${drop}` : ""],
    style: { flex: "1 1 auto", alignSelf: "stretch", width: "100%", minWidth: 0 },
    role: "button", tabindex: 0, draggable: !resizing.value,
    "aria-selected": String(selected), "aria-label": `列 ${column.label}`,
    title: settings.copyHeaderOnDoubleClick
      ? "单击选择；拖动框选；已选列再次拖动改变位置；双击复制列名；右键打开菜单"
      : "单击选择；拖动框选；已选列再次拖动改变位置；右键打开菜单",
    onPointerdown: (event: PointerEvent) => startColumnSelection(event, identity),
    onPointerenter: () => extendColumnSelection(identity),
    onClick: (event: MouseEvent) => selectColumnHeader(event, identity),
    onKeydown: (event: KeyboardEvent) => keyboardSelectHeader(event, identity),
    onContextmenu: (event: MouseEvent) => openHeaderMenu(event, identity),
    onDragstart: (event: DragEvent) => startColumnDrag(event, identity),
    onDragover: (event: DragEvent) => overColumn(event, identity),
    onDrop: (event: DragEvent) => dropColumn(event, identity),
    onDragend: endColumnDrag
  }, [
    h("span", {
      class: "result-column-labels",
      onDblclick: (event: MouseEvent) => copyDoubleClickedHeader(event, column)
    }, [
      h("span", { class: "result-column-title" }, column.label),
      settings.showColumnRemarksInHeader && column.remarks
        ? h("span", { class: "result-column-remarks", title: column.remarks }, column.remarks) : undefined
    ]),
    selected && view && view.selected.length > 1 && firstSelected === identity
      ? h("span", { class: "column-selection-count" }, `${view.selected.length}列`) : undefined,
    h(ResultHeaderTools, {
      column, columnIndex: column.index,
      sort: activeSort.value?.columnIndex === column.index ? activeSort.value : undefined,
      filter: activeFilters.value.find((item) => item.columnIndex === column.index),
      sortingEnabled: settings.headerSortingEnabled,
      filteringEnabled: settings.headerFilteringEnabled,
      onSort: () => cycleSort(column.index),
      onApply: (filter: ResultFilter) => applyFilter(filter),
      onClear: () => clearFilter(column.index)
    }),
    h("span", {
      class: "column-resize-handle", role: "separator", tabindex: 0,
      "aria-label": `调整 ${column.label} 列宽`, title: "拖动调整列宽，双击自动匹配",
      onClick: (event: MouseEvent) => event.stopPropagation(),
      onPointerdown: (event: PointerEvent) => startColumnResize(event, identity),
      onDblclick: (event: MouseEvent) => fitColumnWidth(event, column, identity),
      onKeydown: (event: KeyboardEvent) => keyboardResizeColumn(event, column, identity)
    })
  ]);
}

function selectColumnHeader(event: MouseEvent, identity: string): void {
  const active = activeLayout.value;
  if (!active || resizing.value) return;
  if (suppressNextColumnClick.value) {
    suppressNextColumnClick.value = false;
    return;
  }
  activateColumnSelection();
  const order = visibleColumnOptions.value.map((column) => currentIdentities.value[column.index]);
  columnLayouts.choose(active.viewKey, order, identity, event.ctrlKey || event.metaKey, event.shiftKey);
}

function startColumnSelection(event: PointerEvent, identity: string): void {
  if (event.button !== 0 || resizing.value) return;
  const active = activeLayout.value;
  if (!active) return;
  tableHost.value?.focus({ preventScroll: true });
  const view = columnLayouts.view(active.viewKey);
  const wasSelected = view.selected.includes(identity);
  columnPointerState.value = { identity, wasSelected };
  if (wasSelected) return;
  event.preventDefault();
  const order = visibleColumnOptions.value.map((column) => currentIdentities.value[column.index]);
  const previousSelected = [...view.selected];
  const previousAnchor = view.anchor;
  clearCellAndRowSelection();
  selectionMode.value = "columns";
  columnSelectionBase.value = previousSelected;
  columnSelectionMode.value = event.ctrlKey || event.metaKey
    ? previousSelected.includes(identity) ? "remove" : "add" : "replace";
  columnSelectionAnchor.value = event.shiftKey && previousAnchor && order.includes(previousAnchor)
    ? previousAnchor : identity;
  applyColumnSelection(identity);
  suppressNextColumnClick.value = true;
  selectingColumns.value = true;
  window.removeEventListener("pointerup", finishColumnSelection);
  window.addEventListener("pointerup", finishColumnSelection, { once: true });
}

function applyColumnSelection(identity: string): void {
  const active = activeLayout.value;
  const anchor = columnSelectionAnchor.value;
  if (!active || !anchor) return;
  const order = visibleColumnOptions.value.map((column) => currentIdentities.value[column.index]);
  const start = order.indexOf(anchor); const end = order.indexOf(identity);
  if (start < 0 || end < 0) return;
  const range = order.slice(Math.min(start, end), Math.max(start, end) + 1);
  const base = new Set(columnSelectionBase.value);
  if (columnSelectionMode.value === "add") range.forEach((item) => base.add(item));
  else if (columnSelectionMode.value === "remove") range.forEach((item) => base.delete(item));
  else {
    base.clear();
    range.forEach((item) => base.add(item));
  }
  columnLayouts.setSelection(active.viewKey, order.filter((item) => base.has(item)), anchor);
}

function extendColumnSelection(identity: string): void {
  if (!selectingColumns.value) return;
  applyColumnSelection(identity);
}

function finishColumnSelection(): void {
  selectingColumns.value = false;
  columnPointerState.value = undefined;
  window.removeEventListener("pointerup", finishColumnSelection);
  window.setTimeout(() => { suppressNextColumnClick.value = false; }, 0);
}

function keyboardSelectHeader(event: KeyboardEvent, identity: string): void {
  if (event.key === "ContextMenu" || (event.shiftKey && event.key === "F10")) {
    event.preventDefault();
    openHeaderMenu(event as unknown as MouseEvent, identity, event.currentTarget as HTMLElement);
    return;
  }
  if (event.key !== " " && event.key !== "Enter") return;
  event.preventDefault();
  selectColumnHeader(event as unknown as MouseEvent, identity);
}

function openHeaderMenu(event: MouseEvent, identity: string, keyboardTarget?: HTMLElement): void {
  const active = activeLayout.value;
  if (!active) return;
  event.preventDefault();
  clearCellAndRowSelection();
  selectionMode.value = "columns";
  const view = columnLayouts.view(active.viewKey);
  if (!view.selected.includes(identity)) columnLayouts.selectOnly(active.viewKey, identity);
  const bounds = keyboardTarget?.getBoundingClientRect();
  const requestedX = bounds ? bounds.left + 16 : event.clientX;
  const requestedY = bounds ? bounds.bottom : event.clientY;
  headerMenu.value = {
    visible: true,
    x: Math.max(8, Math.min(requestedX, window.innerWidth - 188)),
    // Reserve the fully expanded copy submenu height so opening it never leaves the viewport.
    y: Math.max(8, Math.min(requestedY, window.innerHeight - 392))
  };
}

function closeHeaderMenu(): void { headerMenu.value = { ...headerMenu.value, visible: false }; }

function selectedOrderedColumns(): ColumnOption[] {
  const active = activeLayout.value;
  if (!active) return [];
  const selected = new Set(columnLayouts.view(active.viewKey).selected);
  return visibleColumnOptions.value.filter((column) => selected.has(currentIdentities.value[column.index]));
}

function canExportSqlColumns(indices: number[]): boolean {
  const target = activeResult.value?.mutationTarget;
  const reason = target?.reasonCode?.trim() ?? "";
  if (serverExportBlocked.value || !target || !indices.length || !target.qualifiedName.trim()
      || (reason && !["FOR_UPDATE_REQUIRED", "NO_SAFE_ROW_KEY", "NON_TRANSACTIONAL_TABLE"].includes(reason))) return false;
  const mapped = new Map((target.columns ?? []).map((column) => [column.resultIndex, column]));
  return indices.every((index) => {
    const column = mapped.get(index);
    return !!column?.name?.trim() && !!column.quotedName?.trim();
  });
}

const canCopyHeaderData = computed(() => selectedOrderedColumns().length > 0 && displayRows.value.length > 0);
const serverExportBlocked = computed(() => {
  const currentExecution = execution.value;
  return !currentExecution || currentExecution.historical === true || resultEdits.hasChanges(currentExecution.editorId);
});
const canExportHeader = computed(() => !serverExportBlocked.value && selectedOrderedColumns().length > 0);
const canExportHeaderSql = computed(() => canExportSqlColumns(selectedOrderedColumns().map((column) => column.index)));
const canExportAllSql = computed(() => canExportSqlColumns(
  activeResult.value?.columns.map((_column, index) => index) ?? []));
const canCopyHeaderIn = computed(() => selectionMode.value === "columns" && !!headerInPredicate());
const canMoveSelectionLeft = computed(() => canMoveSelection("left"));
const canMoveSelectionRight = computed(() => canMoveSelection("right"));

function canMoveSelection(edge: ColumnEdge): boolean {
  const active = activeLayout.value;
  if (!active) return false;
  const order = visibleColumnOptions.value.map((column) => currentIdentities.value[column.index]);
  const selected = columnLayouts.view(active.viewKey).selected;
  return moveColumnsToEdge(order, selected, edge) !== order;
}

function headerMenuCommand(command: HeaderMenuCommand): void {
  if (command === "export-csv" || command === "export-excel" || command === "export-sql") {
    emitVisibleExport(command.replace("export-", "") as ResultExportFormat,
      displayRows.value.map((row) => row.sourceIndex), selectedOrderedColumns().map((column) => column.index));
    return;
  }
  if (command === "copy-headers-with-remarks") {
    void copySelectedColumnRemarks();
    return;
  }
  if (command === "sum") {
    applySum(headerSumResult.value);
    return;
  }
  if (command === "move-left" || command === "move-right") {
    moveSelectedColumns(command === "move-left" ? "left" : "right");
    return;
  }
  if (command === "copy-in") {
    const text = headerInPredicate();
    if (text) void copyText(text, "已复制 IN 语句");
    return;
  }
  const mode: ResultCopyMode = command === "copy-headers" ? "headers"
    : command === "copy-data" ? "data" : "headers-and-data";
  void copySelectedColumns(mode);
}

function moveSelectedColumns(edge: ColumnEdge): void {
  const active = activeLayout.value;
  if (!active) return;
  const order = visibleColumnOptions.value.map((column) => currentIdentities.value[column.index]);
  columnLayouts.moveToEdge(active.layoutKey, active.viewKey, order, edge, selectedColumnIndices.value.length > 0);
}

async function copySelectedColumns(mode: ResultCopyMode): Promise<void> {
  const columns = selectedOrderedColumns();
  if (!columns.length) return;
  const text = resultCopyText(columns.map((column) => ({ label: column.label, index: column.index })),
    displayRows.value.map((row) => row.cells), mode, settings.copySeparator);
  await copyText(text, mode === "headers" ? "已复制列名" : mode === "data" ? "已复制列数据" : "已复制列名和数据");
}

async function copySelectedColumnRemarks(): Promise<void> {
  const columns = selectedOrderedColumns();
  if (!columns.length) return;
  const text = resultColumnRemarksText(columns.map((column) => ({
    label: column.label,
    remarks: column.remarks,
  })), settings.copySeparator);
  await copyText(text, "已复制列名和注释");
}

function copyDoubleClickedHeader(event: MouseEvent, column: ColumnOption): void {
  event.preventDefault(); event.stopPropagation();
  if (!settings.copyHeaderOnDoubleClick) return;
  void copyText(resultCopyText([{ label: column.label, index: column.index }], [], "headers", settings.copySeparator), "已复制列名");
}

function startColumnDrag(event: DragEvent, identity: string): void {
  const active = activeLayout.value;
  if (!active || resizing.value || !event.dataTransfer) { event.preventDefault(); return; }
  if (columnPointerState.value?.identity === identity && !columnPointerState.value.wasSelected) {
    event.preventDefault();
    endColumnDrag();
    return;
  }
  if (!columnPointerState.value && !columnLayouts.view(active.viewKey).selected.includes(identity)) {
    activateColumnSelection();
    columnLayouts.selectOnly(active.viewKey, identity);
  }
  const count = columnLayouts.view(active.viewKey).selected.length;
  event.dataTransfer.effectAllowed = "move";
  event.dataTransfer.setData("text/plain", identity);
  dragPreview = document.createElement("div");
  dragPreview.className = "column-drag-preview";
  dragPreview.textContent = count > 1 ? `移动 ${count} 列` : "移动列";
  document.body.appendChild(dragPreview);
  event.dataTransfer.setDragImage(dragPreview, 12, 12);
}

function overColumn(event: DragEvent, identity: string): void {
  if (!activeLayout.value || !event.dataTransfer) return;
  event.preventDefault();
  event.dataTransfer.dropEffect = "move";
  const bounds = (event.currentTarget as HTMLElement).getBoundingClientRect();
  dropTarget.value = { identity, side: event.clientX < bounds.left + bounds.width / 2 ? "before" : "after" };
}

function dropColumn(event: DragEvent, identity: string): void {
  event.preventDefault();
  const active = activeLayout.value;
  const side = dropTarget.value?.identity === identity ? dropTarget.value.side : "before";
  if (active) {
    const order = visibleColumnOptions.value.map((column) => currentIdentities.value[column.index]);
    columnLayouts.reorder(active.layoutKey, active.viewKey, order, identity, side, selectedColumnIndices.value.length > 0);
  }
  endColumnDrag();
}

function endColumnDrag(): void {
  dropTarget.value = undefined;
  columnPointerState.value = undefined;
  dragPreview?.remove();
  dragPreview = undefined;
}

function startColumnResize(event: PointerEvent, identity: string): void {
  const active = activeLayout.value;
  if (!active) return;
  event.preventDefault(); event.stopPropagation();
  const stored = columnLayouts.layout(active.layoutKey);
  resizing.value = { identity, startX: event.clientX, startWidth: stored?.widths[identity] ?? 120 };
  window.addEventListener("pointermove", resizeColumn);
  window.addEventListener("pointerup", finishColumnResize, { once: true });
}

function resizeColumn(event: PointerEvent): void {
  const active = activeLayout.value;
  const state = resizing.value;
  if (!active || !state) return;
  columnLayouts.setWidth(active.layoutKey, state.identity,
    clampColumnWidth(state.startWidth + event.clientX - state.startX));
}

function finishColumnResize(): void {
  window.removeEventListener("pointermove", resizeColumn);
  resizing.value = undefined;
}

function fitColumnWidth(event: MouseEvent, column: ColumnOption, identity: string): void {
  event.preventDefault(); event.stopPropagation();
  const active = activeLayout.value;
  const rows = activeResult.value?.rows;
  if (!active || !rows) return;
  columnLayouts.setWidth(active.layoutKey, identity,
    autoColumnWidth(column.label, rows, column.index, measureText));
}

function keyboardResizeColumn(event: KeyboardEvent, column: ColumnOption, identity: string): void {
  const active = activeLayout.value;
  if (!active) return;
  if (event.key === "Enter") {
    fitColumnWidth(event as unknown as MouseEvent, column, identity);
    return;
  }
  if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
  event.preventDefault(); event.stopPropagation();
  const width = columnLayouts.layout(active.layoutKey)?.widths[identity] ?? defaultColumnWidth(column.label);
  columnLayouts.setWidth(active.layoutKey, identity,
    clampColumnWidth(width + (event.key === "ArrowRight" ? 10 : -10)));
}

function measureText(text: string): number {
  if (measureContext === undefined) {
    const canvas = document.createElement("canvas");
    measureContext = canvas.getContext("2d");
  }
  if (!measureContext) return Array.from(text).length * 8;
  measureContext.font = tableHost.value ? getComputedStyle(tableHost.value).font : "12px sans-serif";
  return measureContext.measureText(text).width;
}

const showRestoreLayout = computed(() => !!activeLayout.value
  && ((selectedColumnIndices.value.length > 0
      && selectedColumnIndices.value.length < activeLayout.value.identities.length)
    || columnLayouts.dirty(activeLayout.value.layoutKey, activeLayout.value.identities, defaultWidths.value)
    || singleRecordLayoutDirty.value));
const restoreLayoutTitle = computed(() =>
  shortcutTooltip("复原列顺序、宽度、字段和单记录视图", "result.restoreLayout", settings.shortcuts));

function restoreLayout(): void {
  if (!showRestoreLayout.value) return;
  const active = activeLayout.value;
  if (active && columnLayouts.dirty(active.layoutKey, active.identities, defaultWidths.value)) {
    columnLayouts.reset(active.layoutKey, active.viewKey, active.identities, defaultWidths.value);
  }
  rememberFieldVisibility([]);
  sorts.value = { ...sorts.value, [resultKey.value]: undefined };
  filters.value = { ...filters.value, [resultKey.value]: [] };
  sumSummary.value = undefined;
  if (singleRecordView.value) singleRecordView.value.resetLayout();
  else if (singleRecordLayoutDirty.value) resetStoredSingleRecordLayout();
  singleRecordLayoutDirty.value = false;
  ElMessage.success("已复原列布局");
}

function filterColumns(query: string): void { columnQuery.value = query; }
function optionDetail(column: ColumnOption): string {
  const values: string[] = [];
  if (column.name && column.name !== column.label) values.push(column.name);
  if (column.remarks) values.push(column.remarks);
  return values.join(" · ");
}

function cycleSort(columnIndex: number): void {
  const current = activeSort.value;
  const next: ResultSort | undefined = current?.columnIndex !== columnIndex
    ? { columnIndex, direction: "asc" }
    : current.direction === "asc" ? { columnIndex, direction: "desc" } : undefined;
  sorts.value = { ...sorts.value, [resultKey.value]: next };
  detachCellRange();
}

function applyFilter(filter: ResultFilter): void {
  const next = activeFilters.value.filter((item) => item.columnIndex !== filter.columnIndex);
  next.push(filter);
  filters.value = { ...filters.value, [resultKey.value]: next };
  detachCellRange();
  sumSummary.value = undefined;
}

function clearFilter(columnIndex: number): void {
  filters.value = { ...filters.value,
    [resultKey.value]: activeFilters.value.filter((item) => item.columnIndex !== columnIndex) };
  detachCellRange();
  sumSummary.value = undefined;
}

function reconcileViewState(): void {
  const visible = new Set(visibleColumnOptions.value.map((column) => column.index));
  const sort = activeSort.value;
  if (sort && !visible.has(sort.columnIndex)) sorts.value = { ...sorts.value, [resultKey.value]: undefined };
  const nextFilters = activeFilters.value.filter((filter) => visible.has(filter.columnIndex));
  if (nextFilters.length !== activeFilters.value.length) filters.value = { ...filters.value, [resultKey.value]: nextFilters };
}

function clearSelection(): void {
  clearCellAndRowSelection();
  clearColumnHeaderSelection();
  selectionMode.value = "cells";
  singleRecordSqlAllowed.value = true;
  singleRecordMode.value = false;
  singleRecordReturnPosition = undefined;
  singleRecordSourceIndex.value = undefined;
  singleRecordSelection.value = undefined;
}

function detachCellRange(): void {
  selectingCells.value = false;
  cellRange.value = undefined;
  cellAnchor.value = undefined;
  window.removeEventListener("pointerup", finishCellSelection);
}

function startCellSelection(event: PointerEvent, row: number, column: number): void {
  if (event.button !== 0) return;
  event.preventDefault();
  tableHost.value?.focus();
  clearColumnHeaderSelection();
  selectionMode.value = "cells";
  selectedRowSources.value = [];
  rowAnchor.value = undefined;
  const point = { row, column };
  if (event.ctrlKey || event.metaKey) {
    const selected = selectedCellAt(row, column);
    if (!selected) return;
    const key = cellSelectionKey(selected.sourceRow, selected.sourceColumn);
    selectedCells.value = selectedCellKeySet.value.has(key)
      ? selectedCells.value.filter((cell) => cellSelectionKey(cell.sourceRow, cell.sourceColumn) !== key)
      : [...selectedCells.value, selected];
    const nextFocus = selectedCells.value.some((cell) =>
      cell.sourceRow === selected.sourceRow && cell.sourceColumn === selected.sourceColumn)
      ? selected : selectedCellsInView.value.at(-1);
    focusedCell.value = nextFocus
      ? { sourceRow: nextFocus.sourceRow, sourceColumn: nextFocus.sourceColumn }
      : undefined;
    cellAnchor.value = nextFocus ? { row: nextFocus.row, column: nextFocus.column } : undefined;
    cellRange.value = undefined;
    selectingCells.value = false;
    if (selectedCells.value.length) selectStatusColumn(column);
    else {
      selectedColumnIndex.value = undefined;
      emit("selected-column", undefined);
    }
    return;
  }
  focusCellAt(point);
  if (event.shiftKey && cellAnchor.value) {
    cellRange.value = { start: cellAnchor.value, end: point };
  } else {
    cellAnchor.value = point;
    cellRange.value = { start: point, end: point };
  }
  selectedCells.value = cellsInRange(cellRange.value);
  selectingCells.value = true;
  selectStatusColumn(column);
  window.removeEventListener("pointerup", finishCellSelection);
  window.addEventListener("pointerup", finishCellSelection, { once: true });
}

function extendCellSelection(row: number, column: number): void {
  if (!selectingCells.value || !cellAnchor.value) return;
  const point = { row, column };
  cellRange.value = { start: cellAnchor.value, end: point };
  selectedCells.value = cellsInRange(cellRange.value);
  focusCellAt(point);
}

function finishCellSelection(): void {
  selectingCells.value = false;
  window.removeEventListener("pointerup", finishCellSelection);
}

function selectResultRow(event: PointerEvent, sourceIndex: number): void {
  if (event.button !== 0) return;
  event.preventDefault(); event.stopPropagation();
  tableHost.value?.focus();
  clearColumnHeaderSelection();
  selectionMode.value = "rows";
  selectedColumnIndex.value = undefined;
  emit("selected-column", undefined);
  cellRange.value = undefined; cellAnchor.value = undefined; selectedCells.value = [];
  focusedCell.value = undefined;
  const order = displayRows.value.map((row) => row.sourceIndex);
  const before = [...selectedRowSources.value];
  const rangeAnchor = event.shiftKey && rowAnchor.value !== undefined ? rowAnchor.value : sourceIndex;
  const toggle = event.ctrlKey || event.metaKey;
  rowDragBase.value = toggle ? before : [];
  rowDragMode.value = toggle ? (before.includes(sourceIndex) ? "remove" : "add") : "replace";
  rowDragAnchor.value = rangeAnchor;
  const selected = selectRows(order, before,
    rowAnchor.value, sourceIndex, event.ctrlKey || event.metaKey, event.shiftKey);
  selectedRowSources.value = selected.selected;
  rowAnchor.value = selected.anchor;
  selectingRows.value = true;
  window.removeEventListener("pointerup", finishRowSelection);
  window.addEventListener("pointerup", finishRowSelection, { once: true });
}

function extendRowSelection(sourceIndex: number): void {
  const anchor = rowDragAnchor.value;
  if (!selectingRows.value || anchor === undefined) return;
  const order = displayRows.value.map((row) => row.sourceIndex);
  const start = order.indexOf(anchor); const end = order.indexOf(sourceIndex);
  if (start < 0 || end < 0) return;
  const range = new Set(order.slice(Math.min(start, end), Math.max(start, end) + 1));
  const base = new Set(rowDragBase.value);
  if (rowDragMode.value === "add") range.forEach((value) => base.add(value));
  else if (rowDragMode.value === "remove") range.forEach((value) => base.delete(value));
  else { base.clear(); range.forEach((value) => base.add(value)); }
  selectedRowSources.value = order.filter((value) => base.has(value));
}

function finishRowSelection(): void {
  selectingRows.value = false;
  window.removeEventListener("pointerup", finishRowSelection);
}

function openCellMenu(event: MouseEvent, row: number, column: number, rowData: ViewRow): void {
  openCellMenuWithSqlPermission(event, row, column, rowData, true);
}

function openCellMenuWithSqlPermission(event: MouseEvent, row: number, column: number,
                                       rowData: ViewRow, sqlAllowed: boolean): void {
  event.preventDefault(); event.stopPropagation();
  singleRecordSqlAllowed.value = sqlAllowed;
  if (selectionMode.value === "rows" && selectedRowSources.value.includes(rowData.sourceIndex)) {
    openDataMenu(event, "rows");
    return;
  }
  clearColumnHeaderSelection();
  selectionMode.value = "cells";
  selectedRowSources.value = [];
  selectStatusColumn(column);
  const sourceColumn = visibleColumnOptions.value[column]?.index;
  const key = sourceColumn === undefined ? "" : cellSelectionKey(rowData.sourceIndex, sourceColumn);
  if (!selectedCellKeySet.value.has(key)) {
    const point = { row, column };
    cellAnchor.value = point; cellRange.value = { start: point, end: point };
    selectedCells.value = cellsInRange(cellRange.value);
  }
  focusCellAt({ row, column });
  openDataMenu(event, "cells");
}

function openRowMenu(event: MouseEvent, sourceIndex: number): void {
  event.preventDefault(); event.stopPropagation();
  singleRecordSqlAllowed.value = true;
  clearColumnHeaderSelection();
  selectionMode.value = "rows";
  selectedColumnIndex.value = undefined;
  emit("selected-column", undefined);
  cellRange.value = undefined; cellAnchor.value = undefined; selectedCells.value = [];
  focusedCell.value = undefined;
  if (!selectedRowSources.value.includes(sourceIndex)) {
    selectedRowSources.value = [sourceIndex]; rowAnchor.value = sourceIndex;
  }
  openDataMenu(event, "rows");
}

function selectedCellAt(row: number, column: number): SelectedCell | undefined {
  const viewRow = displayRows.value[row];
  const viewColumn = visibleColumnOptions.value[column];
  if (!viewRow || !viewColumn) return undefined;
  return {
    row,
    sourceRow: viewRow.sourceIndex,
    column,
    sourceColumn: viewColumn.index,
    value: viewRow.cells[viewColumn.index] ?? null
  };
}

function focusCellAt(point: CellPoint): SelectedCell | undefined {
  const cell = selectedCellAt(point.row, point.column);
  focusedCell.value = cell
    ? { sourceRow: cell.sourceRow, sourceColumn: cell.sourceColumn }
    : undefined;
  return cell;
}

function focusedCellInView(): SelectedCell | undefined {
  const focused = focusedCell.value;
  if (focused) {
    const row = displayRows.value.findIndex((item) => item.sourceIndex === focused.sourceRow);
    const column = visibleColumnOptions.value.findIndex((item) => item.index === focused.sourceColumn);
    if (row >= 0 && column >= 0) return selectedCellAt(row, column);
  }
  return selectedCellsInView.value.at(-1);
}

function cellsInRange(range: CellRange | undefined): SelectedCell[] {
  if (!range) return [];
  const normalized = normalizeRange(range);
  const result: SelectedCell[] = [];
  for (let row = normalized.start.row; row <= normalized.end.row; row++) {
    for (let column = normalized.start.column; column <= normalized.end.column; column++) {
      const cell = selectedCellAt(row, column);
      if (cell) result.push(cell);
    }
  }
  return result;
}

function selectStatusColumn(column: number): void {
  selectedColumnIndex.value = visibleColumnOptions.value[column]?.index;
  emitSelectedColumn();
}

function reconcileSelectedColumn(): void {
  const selected = selectedColumnIndex.value;
  if (selected === undefined || visibleColumnOptions.value.some((column) => column.index === selected)) return;
  const fallback = selectedCellsInView.value.at(-1)?.sourceColumn;
  selectedColumnIndex.value = fallback;
  if (fallback === undefined) emit("selected-column", undefined);
  else emitSelectedColumn();
}

function emitSelectedColumn(): void {
  const index = selectedColumnIndex.value;
  if (index === undefined) return;
  const column = columnOptions.value.find((item) => item.index === index);
  if (!column) { emit("selected-column", undefined); return; }
  emit("selected-column", {
    label: column.label, name: column.name, remarks: column.remarks, typeName: column.typeName,
    catalog: column.catalog, schema: column.schema, table: column.table
  });
}

function openDataMenu(event: MouseEvent, mode: "cells" | "rows"): void {
  dataMenu.value = { visible: true, mode, x: event.clientX, y: event.clientY };
}

function closeDataMenu(): void { dataMenu.value = { ...dataMenu.value, visible: false }; }

const selectedCellBounds = computed(() => {
  const cells = selectedCellsInView.value;
  if (selectionMode.value !== "cells" || !cells.length) {
    return { columns: [] as ColumnOption[], rows: [] as ViewRow[], complete: false };
  }
  const startRow = Math.min(...cells.map((cell) => cell.row));
  const endRow = Math.max(...cells.map((cell) => cell.row));
  const startColumn = Math.min(...cells.map((cell) => cell.column));
  const endColumn = Math.max(...cells.map((cell) => cell.column));
  const rows = displayRows.value.slice(startRow, endRow + 1);
  const columns = visibleColumnOptions.value.slice(startColumn, endColumn + 1);
  const selected = new Set(cells.map((cell) => cellSelectionKey(cell.sourceRow, cell.sourceColumn)));
  const complete = rows.length * columns.length === cells.length
    && rows.every((row) => columns.every((column) =>
      selected.has(cellSelectionKey(row.sourceIndex, column.index))));
  return { columns, rows, complete };
});
const selectedCellInPredicateSelection = computed(() => {
  const cells = selectedCellsInView.value;
  if (selectionMode.value !== "cells" || !cells.length) return undefined;

  const columnsByRow = new Map<number, Set<number>>();
  for (const cell of cells) {
    const columns = columnsByRow.get(cell.sourceRow) ?? new Set<number>();
    columns.add(cell.sourceColumn);
    columnsByRow.set(cell.sourceRow, columns);
  }
  const firstColumns = columnsByRow.values().next().value as Set<number> | undefined;
  if (!firstColumns?.size) return undefined;

  // A tuple IN predicate is only unambiguous when every selected row has the
  // same set of selected fields. The fields themselves may be non-contiguous.
  for (const columns of columnsByRow.values()) {
    if (columns.size !== firstColumns.size || [...firstColumns].some((column) => !columns.has(column))) {
      return undefined;
    }
  }

  const columns = visibleColumnOptions.value.filter((column) => firstColumns.has(column.index));
  const rows = displayRows.value.filter((row) => columnsByRow.has(row.sourceIndex));
  return columns.length && rows.length ? { columns, rows } : undefined;
});
const selectedRowsInDisplayOrder = computed(() => {
  const selected = new Set(selectedRowSources.value);
  return displayRows.value.filter((row) => selected.has(row.sourceIndex));
});
const selectedRecordRow = computed<ViewRow | undefined>(() => {
  if (singleRecordMode.value && singleRecordSourceIndex.value !== undefined) {
    return displayRows.value.find((row) => row.sourceIndex === singleRecordSourceIndex.value);
  }
  if (selectionMode.value === "rows") {
    return selectedRowsInDisplayOrder.value.length === 1 ? selectedRowsInDisplayOrder.value[0] : undefined;
  }
  const sourceRows = new Set(selectedCellsInView.value.map((cell) => cell.sourceRow));
  if (sourceRows.size !== 1) return undefined;
  const sourceIndex = sourceRows.values().next().value;
  return displayRows.value.find((row) => row.sourceIndex === sourceIndex);
});
const selectedRecordPosition = computed(() => {
  const row = selectedRecordRow.value;
  return row ? displayRows.value.findIndex((item) => item.sourceIndex === row.sourceIndex) : -1;
});
const canViewSingleRecord = computed(() => selectedRecordRow.value !== undefined);
const singleRecordTitle = computed(() => shortcutTooltip(
  singleRecordMode.value ? "返回结果表格" : "单个记录查看",
  "result.toggleSingleRecord",
  settings.shortcuts,
));
const recordComparisonTitle = computed(() => shortcutTooltip(
  recordComparisonEnabled.value ? "关闭记录比较" : "比较记录",
  "result.toggleRecordComparison",
  settings.shortcuts,
));
const canNavigatePrevious = computed(() => singleRecordMode.value && selectedRecordPosition.value > 0);
const canNavigateNext = computed(() => singleRecordMode.value
  && selectedRecordPosition.value >= 0 && selectedRecordPosition.value < displayRows.value.length - 1);
const selectedRowCount = computed(() => selectionMode.value === "cells"
  ? new Set(selectedCellsInView.value.map((cell) => cell.sourceRow)).size
  : selectionMode.value === "rows" ? selectedRowsInDisplayOrder.value.length : 0);
const hasDataSelection = computed(() => singleRecordMode.value
  ? Boolean(singleRecordSelection.value?.hasSelection)
  : selectionMode.value === "cells"
    ? selectedCellsInView.value.length > 0
    : selectionMode.value === "rows" ? selectedRowsInDisplayOrder.value.length > 0
      : selectedOrderedColumns().length > 0 && displayRows.value.length > 0);
const copySelectionTitle = computed(() => shortcutTooltip(
  singleRecordMode.value && singleRecordSelection.value?.hasSelection ? "复制单记录选区"
    : selectionMode.value === "rows" ? "复制选中行" : selectionMode.value === "columns" ? "复制选中列" : "复制选中单元格",
  "result.copySelection",
  settings.shortcuts,
));

const selectedStatusText = computed(() => singleRecordMode.value
  ? singleRecordSelection.value?.hasSelection ? singleRecordSelection.value.statusText : ""
  : selectedRowCount.value > 0 ? `已选中 ${selectedRowCount.value} 行` : "");
watch([selectedRowCount, selectedStatusText], ([count, text]) => {
  emit("selected-row-count", count);
  emit("selected-status-text", text);
}, { immediate: true });
watch(selectedRecordRow, (row) => {
  if (!row && singleRecordMode.value) {
    const fallback = displayRows.value[0];
    if (fallback) singleRecordSourceIndex.value = fallback.sourceIndex;
    else void leaveSingleRecordView();
  }
  else if (!row) singleRecordReturnPosition = undefined;
});

async function leaveSingleRecordView(): Promise<void> {
  const position = singleRecordReturnPosition;
  const sourceIndex = singleRecordSourceIndex.value;
  const entryMode = singleRecordEntryMode.value;
  const entryColumns = [...singleRecordEntryColumns.value];
  singleRecordMode.value = false;
  singleRecordSqlAllowed.value = true;
  singleRecordReturnPosition = undefined;
  singleRecordSourceIndex.value = undefined;
  singleRecordSelection.value = undefined;
  await nextTick();
  if (position) restoreScrollPosition(position);
  if (sourceIndex === undefined) return;
  const targetPosition = displayRows.value.findIndex((row) => row.sourceIndex === sourceIndex);
  if (targetPosition < 0) return;
  if (entryMode === "rows") {
    clearColumnHeaderSelection(); cellRange.value = undefined; cellAnchor.value = undefined;
    selectedCells.value = []; focusedCell.value = undefined; selectedRowSources.value = [sourceIndex];
    selectionMode.value = "rows"; rowAnchor.value = sourceIndex;
    return;
  }
  clearColumnHeaderSelection(); selectedRowSources.value = []; selectionMode.value = "cells";
  const nextCells = entryColumns.map((sourceColumn) => {
    const column = visibleColumnOptions.value.findIndex((item) => item.index === sourceColumn);
    return column < 0 ? undefined : selectedCellAt(targetPosition, column);
  }).filter((cell): cell is SelectedCell => !!cell);
  selectedCells.value = nextCells;
  const first = nextCells[0];
  cellRange.value = first ? { start: { row: first.row, column: first.column }, end: { row: first.row, column: first.column } } : undefined;
  cellAnchor.value = first ? { row: first.row, column: first.column } : undefined;
  focusedCell.value = first ? { sourceRow: first.sourceRow, sourceColumn: first.sourceColumn } : undefined;
}

function toggleSingleRecordView(): void {
  if (singleRecordMode.value) { void leaveSingleRecordView(); return; }
  if (!selectedRecordRow.value) return;
  recordComparisonEnabled.value = false;
  singleRecordReturnPosition = currentScrollPosition();
  singleRecordSourceIndex.value = selectedRecordRow.value.sourceIndex;
  singleRecordEntryMode.value = selectionMode.value === "rows" ? "rows" : "cells";
  singleRecordEntryColumns.value = selectionMode.value === "cells"
    ? [...new Set(selectedCellsInView.value.map((cell) => cell.sourceColumn))]
    : visibleColumnOptions.value.map((column) => column.index);
  singleRecordInitialSelection.value = {
    mode: singleRecordEntryMode.value,
    fields: singleRecordEntryMode.value === "rows"
      ? visibleColumnOptions.value.map((column) => column.index) : singleRecordEntryColumns.value
  };
  singleRecordSelection.value = undefined;
  singleRecordSqlAllowed.value = selectionMode.value === "cells";
  singleRecordMode.value = true;
}

function toggleRecordComparison(): void {
  if (recordComparisonEnabled.value) {
    recordComparisonEnabled.value = false;
    return;
  }
  if (canToggleRecordComparison.value) recordComparisonEnabled.value = true;
}

type RecordComparisonCommand = "highlight-identical" | "highlight-different" | "scope-column"
  | "scope-record" | "toggle-case-sensitive";
function recordComparisonCommand(command: RecordComparisonCommand): void {
  if (command === "highlight-identical" || command === "highlight-different") {
    emit("update-compare-highlight-mode", command === "highlight-identical" ? "identical" : "different");
    return;
  }
  if (command === "scope-column" || command === "scope-record") {
    emit("update-compare-scope", command === "scope-column" ? "column" : "record");
    return;
  }
  emit("update-compare-case-sensitive", !settings.compareCaseSensitive);
}

function navigateSingleRecord(delta: -1 | 1): void {
  const position = selectedRecordPosition.value;
  const target = displayRows.value[position + delta];
  if (!singleRecordMode.value || position < 0 || !target) return;
  singleRecordSourceIndex.value = target.sourceIndex;
}

function selectedCopyText(includeHeaders = false): string {
  if (singleRecordMode.value) {
    const text = singleRecordView.value?.getCopyText(includeHeaders);
    if (text !== undefined) return text;
  }
  if (selectionMode.value === "rows") {
    return copyGrid(visibleColumnOptions.value, selectedRowsInDisplayOrder.value, includeHeaders, settings.copySeparator);
  }
  if (selectionMode.value === "columns") {
    const columns = selectedOrderedColumns();
    return copyGrid(columns, displayRows.value, includeHeaders, settings.copySeparator);
  }
  const bounds = selectedCellBounds.value;
  const selected = new Set(selectedCellsInView.value.map((cell) =>
    cellSelectionKey(cell.sourceRow, cell.sourceColumn)));
  const sparseRows = bounds.rows.map((row) => ({
    sourceIndex: row.sourceIndex,
    cells: row.cells.map((value, sourceColumn) =>
      selected.has(cellSelectionKey(row.sourceIndex, sourceColumn)) ? value : "")
  }));
  return copyGrid(bounds.columns, sparseRows, includeHeaders, settings.copySeparator);
}

async function copyCurrentSelection(includeHeaders = false): Promise<void> {
  if (!hasDataSelection.value) return;
  await copyText(selectedCopyText(includeHeaders), includeHeaders ? "已复制列名和数据"
    : selectionMode.value === "rows" ? "已复制选中行"
      : selectionMode.value === "columns" ? "已复制选中列" : "已复制选中单元格");
}

function inPredicate(): string | undefined {
  const selection = selectedCellInPredicateSelection.value;
  if (!selection) return undefined;
  return copyInPredicate(selection.columns.map((column) => ({ index: column.index,
    label: column.label, jdbcType: column.jdbcType ?? 12 })), selection.rows,
    activeResult.value?.dialectId);
}

function headerInPredicate(): string | undefined {
  const columns = selectedOrderedColumns();
  return copyInPredicate(columns.map((column) => ({ index: column.index,
    label: column.label, jdbcType: column.jdbcType ?? 12 })), displayRows.value,
    activeResult.value?.dialectId);
}

function rowSql(mode: "insert" | "update" | "delete"): string | undefined {
  return copyRowSql(mode, activeResult.value?.mutationTarget,
    visibleColumnOptions.value.map((column) => column.index), selectedRowsInDisplayOrder.value,
    activeResult.value?.dialectId);
}

const selectedCellSqlRows = computed<SelectedRowColumns[]>(() => {
  if (singleRecordMode.value) {
    const row = selectedRecordRow.value;
    const cells = selectedSingleRecordCells.value;
    return row && cells.length ? [{ row, columnIndices: [...new Set(cells.map((cell) => cell.sourceColumn))] }] : [];
  }
  if (selectionMode.value !== "cells" || !selectedCellsInView.value.length) return [];
  const columnsByRow = new Map<number, Set<number>>();
  for (const cell of selectedCellsInView.value) {
    const columns = columnsByRow.get(cell.sourceRow) ?? new Set<number>();
    columns.add(cell.sourceColumn);
    columnsByRow.set(cell.sourceRow, columns);
  }
  return displayRows.value.filter((row) => columnsByRow.has(row.sourceIndex)).map((row) => ({
    row, columnIndices: [...(columnsByRow.get(row.sourceIndex) ?? [])]
  }));
});

function cellSql(mode: "update" | "delete"): string | undefined {
  return copyCellSql(mode, activeResult.value?.mutationTarget, selectedCellSqlRows.value,
    activeResult.value?.dialectId);
}

function equalityColumns(): ResultSqlColumn[] {
  return visibleColumnOptions.value.map((column) => ({ index: column.index, name: column.name, label: column.label,
    jdbcType: column.jdbcType ?? 12 }));
}

function equalsSql(): string | undefined {
  return copyEqualsSql(equalityColumns(), selectedCellSqlRows.value, activeResult.value?.dialectId);
}

function selectSql(): string | undefined {
  return copySelectSql(activeResult.value?.mutationTarget, selectedCellSqlRows.value,
    activeResult.value?.dialectId, visibleColumnOptions.value.map((column) => column.index));
}

const selectedSingleRecordCells = computed<SelectedCell[]>(() => {
  const state = singleRecordSelection.value;
  const row = selectedRecordRow.value;
  if (!singleRecordMode.value || !state?.sourceCells.length || !row) return [];
  return state.sourceCells.map((cell) => ({
    row: singleRecordRowPosition(), sourceRow: row.sourceIndex,
    column: visibleColumnOptions.value.findIndex((column) => column.index === cell.sourceColumn),
    sourceColumn: cell.sourceColumn, value: cell.value
  })).filter((cell) => cell.column >= 0);
});

function singleRecordInPredicate(): string | undefined {
  const sourceCells = selectedSingleRecordCells.value;
  const row = selectedRecordRow.value;
  if (!row || !sourceCells.length) return undefined;
  const selected = new Set(sourceCells.map((cell) => cell.sourceColumn));
  const columns = visibleColumnOptions.value.filter((column) => selected.has(column.index))
    .map((column) => ({ index: column.index, label: column.label, jdbcType: column.jdbcType ?? 12 }));
  return copyInPredicate(columns, [row], activeResult.value?.dialectId);
}

function singleRecordCellSql(mode: "update" | "delete"): string | undefined {
  const row = selectedRecordRow.value;
  const cells = selectedSingleRecordCells.value;
  if (!row || !cells.length) return undefined;
  return copyCellSql(mode, activeResult.value?.mutationTarget, [{ row,
    columnIndices: [...new Set(cells.map((cell) => cell.sourceColumn))] }], activeResult.value?.dialectId);
}

const canCopyIn = computed(() => singleRecordMode.value
  ? Boolean(singleRecordSelection.value?.sqlAllowed && singleRecordInPredicate())
  : singleRecordSqlAllowed.value && selectionMode.value === "cells" && !!inPredicate());
const canCopySelect = computed(() => singleRecordMode.value
  ? Boolean(singleRecordSelection.value?.sqlAllowed && selectSql())
  : singleRecordSqlAllowed.value && selectionMode.value === "cells" && !!selectSql());
const canCopyEquals = computed(() => singleRecordMode.value
  ? Boolean(singleRecordSelection.value?.sqlAllowed && equalsSql())
  : singleRecordSqlAllowed.value && selectionMode.value === "cells" && !!equalsSql());
const canCopyInsert = computed(() => !singleRecordMode.value && singleRecordSqlAllowed.value
  && selectionMode.value === "rows" && !!rowSql("insert"));
const canCopyUpdate = computed(() => singleRecordMode.value
  ? Boolean(singleRecordSelection.value?.sqlAllowed && singleRecordCellSql("update"))
  : singleRecordSqlAllowed.value && (selectionMode.value === "rows"
    ? !!rowSql("update") : selectionMode.value === "cells" && !!cellSql("update")));
const canCopyDelete = computed(() => singleRecordMode.value
  ? Boolean(singleRecordSelection.value?.sqlAllowed && singleRecordCellSql("delete"))
  : singleRecordSqlAllowed.value && (selectionMode.value === "rows"
    ? !!rowSql("delete") : selectionMode.value === "cells" && !!cellSql("delete")));
const compareValues = computed(() => ({
  left: (singleRecordMode.value ? selectedSingleRecordCells.value : selectedCellsInView.value)[0]?.value ?? null,
  right: (singleRecordMode.value ? selectedSingleRecordCells.value : selectedCellsInView.value)[1]?.value ?? null
}));
const canCompareCells = computed(() =>
  (singleRecordMode.value ? singleRecordSelection.value?.mode === "cells" : selectionMode.value === "cells")
  && (singleRecordMode.value ? selectedSingleRecordCells.value.length : selectedCellsInView.value.length) === 2);
const cellSumResult = computed(() =>
  sumDecimalValues((singleRecordMode.value ? selectedSingleRecordCells.value : selectedCellsInView.value).map((cell) => cell.value)));
const canSumCells = computed(() => (singleRecordMode.value ? singleRecordSelection.value?.mode === "cells" : selectionMode.value === "cells")
  && (singleRecordMode.value ? selectedSingleRecordCells.value.length : selectedCellsInView.value.length) >= 2
  && cellSumResult.value.valid && cellSumResult.value.count > 0);
const canSetSelectedCellNull = computed(() => resultEditUnlocked.value
  && (singleRecordMode.value ? selectedSingleRecordCells.value.length : selectedCellsInView.value.length) > 0
  && (singleRecordMode.value ? selectedSingleRecordCells.value : selectedCellsInView.value)
    .every((cell) => isCellEditable(cell.sourceRow, cell.sourceColumn)));
const headerSumResult = computed(() => sumDecimalValues(selectedOrderedColumns().flatMap((column) =>
  displayRows.value.map((row) => row.cells[column.index] ?? null))));
const canSumHeaderData = computed(() => selectedOrderedColumns().length > 0
  && headerSumResult.value.valid && headerSumResult.value.count > 0);
const exportDataRows = computed(() => {
  if (singleRecordMode.value) return [] as number[];
  if (selectionMode.value === "rows") return selectedRowsInDisplayOrder.value.map((row) => row.sourceIndex);
  if (selectionMode.value !== "cells" || !selectedCellBounds.value.complete) return [] as number[];
  return selectedCellBounds.value.rows.map((row) => row.sourceIndex);
});
const exportDataColumns = computed(() => {
  if (singleRecordMode.value) return [] as number[];
  if (selectionMode.value === "rows") return visibleColumnOptions.value.map((column) => column.index);
  if (selectionMode.value !== "cells" || !selectedCellBounds.value.complete) return [] as number[];
  return selectedCellBounds.value.columns.map((column) => column.index);
});
const canExportDataSelection = computed(() => !serverExportBlocked.value
  && exportDataRows.value.length > 0 && exportDataColumns.value.length > 0);
const canExportDataSelectionSql = computed(() => canExportDataSelection.value
  && canExportSqlColumns(exportDataColumns.value));

function dataMenuCommand(command: DataMenuCommand): void {
  if (command === "export-csv" || command === "export-excel" || command === "export-sql") {
    emitVisibleExport(command.replace("export-", "") as ResultExportFormat,
      exportDataRows.value, exportDataColumns.value);
    return;
  }
  if (command === "clone") {
    void cloneSelectedResultRows();
    return;
  }
  if (command === "set-null") {
    setSelectedCellsNull();
    return;
  }
  if (command === "compare") {
    if (canCompareCells.value) compareDialog.value = true;
    return;
  }
  if (command === "sum") {
    applySum(cellSumResult.value);
    return;
  }
  if (command === "copy-data") { void copyCurrentSelection(); return; }
  if (command === "copy-all") { void copyCurrentSelection(true); return; }
  if (command === "copy-select") {
    const text = selectSql();
    if (text) void copyText(text, "已复制 SELECT 语句");
    return;
  }
  if (command === "copy-equals") {
    const text = equalsSql();
    if (text) void copyText(text, "已复制 = 语句");
    return;
  }
  if (command === "copy-in") {
    const text = singleRecordMode.value ? singleRecordInPredicate() : inPredicate();
    if (text) void copyText(text, "已复制 IN 语句"); return;
  }
  const mode = command.replace("copy-", "") as "insert" | "update" | "delete";
  const text = singleRecordMode.value
    ? mode === "insert" ? undefined : singleRecordCellSql(mode)
    : mode === "insert" ? rowSql(mode) : selectionMode.value === "cells" ? cellSql(mode) : rowSql(mode);
  if (text) void copyText(text, `已复制 ${mode.toUpperCase()} 语句`);
}

function applySum(result: DecimalSumResult): void {
  if (!result.valid) {
    ElMessage.warning(`无法求和：包含非数字值${result.invalidValue ? `“${result.invalidValue}”` : ""}`);
    return;
  }
  if (!result.count) {
    ElMessage.warning("没有可求和的数字");
    return;
  }
  sumSummary.value = { total: result.total, count: result.count };
}

function openCellValue(_row: number, column: number, rowData: ViewRow): void {
  const sourceColumn = visibleColumnOptions.value[column]?.index;
  if (sourceColumn === undefined) return;
  valueDialog.value = { visible: true, value: rowData.cells[sourceColumn] ?? null };
}

function handleCellDoubleClick(row: number, column: number, rowData: ViewRow): void {
  const sourceColumn = visibleColumnOptions.value[column]?.index;
  if (sourceColumn === undefined) return;
  if (resultEditUnlocked.value) {
    startEditCell(rowData.sourceIndex, sourceColumn);
    return;
  }
  openCellValue(row, column, rowData);
}

function handleSingleRecordDoubleClick(fieldIndex: number): void {
  if (resultEditUnlocked.value) {
    if (singleRecordSourceIndex.value !== undefined) startEditCell(singleRecordSourceIndex.value, fieldIndex);
    return;
  }
  valueDialog.value = { visible: true, value: activeResult.value?.rows[singleRecordSourceIndex.value ?? -1]?.[fieldIndex] ?? null };
}

function singleRecordRowPosition(): number {
  const row = selectedRecordRow.value;
  return row ? displayRows.value.findIndex((item) => item.sourceIndex === row.sourceIndex) : -1;
}

function singleRecordColumnPosition(fieldIndex: number): number {
  return visibleColumnOptions.value.findIndex((column) => column.index === fieldIndex);
}

function handleSingleRecordSelectionChange(state: SingleRecordSelectionState): void {
  singleRecordSelection.value = state;
  singleRecordSqlAllowed.value = state.sqlAllowed;
}

function handleSingleRecordContextmenu(event: MouseEvent, fieldIndex: number, _columnIndex: number,
                                       isValueCell: boolean): void {
  if (!isValueCell) singleRecordSqlAllowed.value = false;
  event.preventDefault(); event.stopPropagation(); openDataMenu(event, "cells");
}

function handleSingleRecordRowContextmenu(event: MouseEvent, _fieldIndex: number): void {
  event.preventDefault(); event.stopPropagation(); singleRecordSqlAllowed.value = false;
  openDataMenu(event, "rows");
}

function resultRowId(rowIndex: number): string {
  const result = activeResult.value;
  return result?.rowIds?.[rowIndex] ?? `${execution.value?.executionId ?? "result"}:${result?.resultIndex ?? 0}:${rowIndex}`;
}

function mutationValueText(value: { kind: string; value?: string }): string {
  if (value.kind === "null") return "NULL";
  if (value.kind === "default") return "DEFAULT";
  if (value.kind === "largeValueToken") return "<大字段草稿>";
  return value.value ?? "";
}

function currentDraftMutation(rowIndex: number, columnIndex: number): ResultMutationValue | undefined {
  const current = activeEditSession.value;
  const rowId = resultRowId(rowIndex);
  const inserted = current?.inserts.find((item) => item.rowId === rowId && item.status === "draft");
  const insertedValue = inserted?.values.find((item) => item.columnIndex === columnIndex)?.value;
  if (insertedValue) return { ...insertedValue };
  const cell = current?.cells.find((item) => (item.rowId ? item.rowId === rowId : item.rowIndex === rowIndex)
    && item.columnIndex === columnIndex);
  if (!cell) return undefined;
  if (cell.draftMutation) return { ...cell.draftMutation };
  if (cell.draftValue !== cell.confirmedValue) {
    return cell.draftValue === null ? { kind: "null" } : { kind: "text", value: cell.draftValue };
  }
  return undefined;
}

async function cleanupPreparedCloneValues(editorId: string, executionId: string, resultIndex: number,
                                          values: Array<{ columnIndex: number; token: string }>): Promise<void> {
  await Promise.allSettled(values.map((value) => rpc.deleteResultLargeValueDraft(
    editorId, executionId, resultIndex, value.columnIndex, value.token)));
}

async function cloneSelectedResultRows(): Promise<void> {
  const currentExecution = execution.value;
  const result = activeResult.value;
  const target = result?.mutationTarget;
  if (!currentExecution || !result || !target || !canCloneSelectedRows.value || cloneBusy.value) return;
  const selected = selectedRowsInDisplayOrder.value.map((row) => ({
    sourceIndex: row.sourceIndex, cells: [...row.cells]
  }));
  const editableColumns = target.columns.filter((column) => !column.generated && column.editable !== false);
  const seeds = selected.map((row) => ({
    rowId: `draft:${crypto.randomUUID()}`,
    sourceIndex: row.sourceIndex,
    row: Array.from({ length: result.columns.length }, () => null as string | null),
    values: [] as Array<{ columnIndex: number; value: ResultMutationValue }>
  }));
  const largeSources: Array<{ cloneId: string; columnIndex: number;
    source: { kind: "row"; rowId: string } | { kind: "draft"; token: string } }> = [];

  for (let seedIndex = 0; seedIndex < seeds.length; seedIndex++) {
    const seed = seeds[seedIndex];
    const source = selected[seedIndex];
    for (const column of editableColumns) {
      const columnIndex = column.resultIndex;
      const mutation = currentDraftMutation(source.sourceIndex, columnIndex);
      const large = column.typeFamily === "raw" || column.typeFamily === "clob" || column.typeFamily === "blob";
      if (large && !mutation && source.cells[columnIndex] !== null) {
        largeSources.push({ cloneId: seed.rowId, columnIndex,
          source: { kind: "row", rowId: resultRowId(source.sourceIndex) } });
        seed.row[columnIndex] = source.cells[columnIndex] ?? null;
        continue;
      }
      if (large && mutation?.kind === "largeValueToken") {
        largeSources.push({ cloneId: seed.rowId, columnIndex,
          source: { kind: "draft", token: mutation.value } });
        seed.row[columnIndex] = source.cells[columnIndex] ?? null;
        continue;
      }
      const value = mutation ?? (source.cells[columnIndex] === null
        ? { kind: "null" } as const
        : { kind: "text", value: source.cells[columnIndex] as string } as const);
      seed.values.push({ columnIndex, value: { ...value } });
      seed.row[columnIndex] = value.kind === "text" ? value.value : null;
    }
  }

  cloneBusy.value = true;
  let prepared: Array<{ cloneId: string; columnIndex: number; token: string; size: number;
    typeFamily: string }> = [];
  try {
    if (largeSources.length) {
      prepared = (await rpc.cloneResultLargeValues(currentExecution.editorId, currentExecution.executionId,
        result.resultIndex, largeSources)).values;
    }
    const preparedByCell = new Map(prepared.map((item) => [`${item.cloneId}:${item.columnIndex}`, item]));
    for (const source of largeSources) {
      const value = preparedByCell.get(`${source.cloneId}:${source.columnIndex}`);
      if (!value) throw new Error("服务端未返回完整的大字段克隆结果");
      const seed = seeds.find((item) => item.rowId === source.cloneId);
      seed?.values.push({ columnIndex: source.columnIndex,
        value: { kind: "largeValueToken", value: value.token } });
    }
    if (execution.value?.executionId !== currentExecution.executionId
        || activeResult.value?.resultIndex !== result.resultIndex || !resultEditUnlocked.value) {
      throw new Error("结果编辑状态已经变化，请重新选择需要克隆的行");
    }
    const firstRowIndex = activeResult.value.rows.length;
    const editableIndices = editableColumns.map((column) => column.resultIndex);
    for (let index = 0; index < seeds.length; index++) {
      const seed = seeds[index];
      seed.values.sort((left, right) => left.columnIndex - right.columnIndex);
      const rowIndex = firstRowIndex + index;
      resultEdits.addInsert(currentExecution.editorId, currentExecution.executionId, result.resultIndex,
        seed.rowId, rowIndex, editableIndices, { origin: "clone", values: seed.values });
      queries.appendDraftRow(currentExecution.editorId, result.resultIndex, seed.rowId, seed.row, currentExecution.executionId);
    }
    selectedRowSources.value = seeds.map((_, index) => firstRowIndex + index);
    selectionMode.value = "rows";
    rowAnchor.value = selectedRowSources.value[0];
    ElMessage.success(`已克隆 ${seeds.length} 行`);
  } catch (error) {
    await cleanupPreparedCloneValues(currentExecution.editorId, currentExecution.executionId, result.resultIndex, prepared);
    ElMessage.error(error instanceof Error ? error.message : String(error));
  } finally {
    cloneBusy.value = false;
  }
}

function addResultRow(): void {
  const currentExecution = execution.value;
  const result = activeResult.value;
  const target = result?.mutationTarget;
  if (!currentExecution || !result || !resultEditUnlocked.value || !target?.insertSupported) return;
  const rowId = `draft:${crypto.randomUUID()}`;
  const rowIndex = result.rows.length;
  const editable = target.columns.filter((column) => !column.generated && (column.editable !== false || column.autoIncrement))
    .map((column) => column.resultIndex);
  resultEdits.addInsert(currentExecution.editorId, currentExecution.executionId, result.resultIndex, rowId, rowIndex, editable);
  queries.appendDraftRow(currentExecution.editorId, result.resultIndex, rowId,
    Array.from({ length: result.columns.length }, () => null), currentExecution.executionId);
  selectedRowSources.value = [rowIndex];
  selectionMode.value = "rows";
  if (editable.length) nextTick(() => startEditCell(rowIndex, editable[0]));
}

function deleteSelectedResultRows(): void {
  const currentExecution = execution.value;
  const result = activeResult.value;
  if (!currentExecution || !result || !canDeleteSelectedRows.value) return;
  const selected = [...selectedRowSources.value].sort((left, right) => right - left);
  for (const rowIndex of selected) {
    const rowId = resultRowId(rowIndex);
    const insertedLargeValues = activeEditSession.value?.inserts.find((item) => item.rowId === rowId)?.values
      .flatMap((item) => item.value.kind === "largeValueToken"
        ? [{ columnIndex: item.columnIndex, token: item.value.value }] : []) ?? [];
    const action = resultEdits.markDelete(currentExecution.editorId, currentExecution.executionId, result.resultIndex,
      rowId, rowIndex, result.rows[rowIndex] ?? []);
    if (action === "cancelled-insert") {
      queries.removeRowById(currentExecution.editorId, result.resultIndex, rowId, currentExecution.executionId);
      for (const value of insertedLargeValues) void rpc.deleteResultLargeValueDraft(
        currentExecution.editorId, currentExecution.executionId, result.resultIndex, value.columnIndex, value.token);
    }
  }
  clearSelection();
}

function undoResultDraft(): void {
  const currentExecution = execution.value;
  const result = activeResult.value;
  if (!currentExecution || !result) return;
  const undone = resultEdits.undo(currentExecution.editorId, currentExecution.executionId, result.resultIndex);
  if (!undone) return;
  if (undone.kind === "cell") queries.updateCells(currentExecution.editorId, result.resultIndex, [{
    rowIndex: undone.rowIndex, columnIndex: undone.columnIndex, value: undone.value
  }], currentExecution.executionId);
  if (undone.kind === "cell" && undone.largeValueToken) void rpc.deleteResultLargeValueDraft(
    currentExecution.editorId, currentExecution.executionId, result.resultIndex, undone.columnIndex, undone.largeValueToken);
  else if (undone.kind === "insert") {
    queries.removeRowById(currentExecution.editorId, result.resultIndex, undone.rowId, currentExecution.executionId);
    for (const value of undone.largeValues) void rpc.deleteResultLargeValueDraft(
      currentExecution.editorId, currentExecution.executionId, result.resultIndex, value.columnIndex, value.token);
  }
}

async function openChangesDialog(): Promise<void> {
  const currentExecution = execution.value;
  const result = activeResult.value;
  const current = activeEditSession.value;
  if (!currentExecution || !result || !current) return;
  changesDialogVisible.value = true;
  previewError.value = "";
  const operations = resultEdits.operations(current);
  if (!operations.length) { changePreviews.value = []; return; }
  try {
    const response = await rpc.request<{ previews: Array<{ operationId: string; sql: string; binds: string[] }> }>(
      "query.previewChanges", { editorId: currentExecution.editorId, executionId: currentExecution.executionId,
        resultIndex: result.resultIndex,
        operations: operations.map(({ sequence: _sequence, ...operation }) => operation) });
    changePreviews.value = response.previews;
  } catch (error) {
    previewError.value = error instanceof Error ? error.message : String(error);
  }
}

function startEditCell(rowIndex: number, columnIndex: number): void {
  const result = activeResult.value;
  if (!resultEditUnlocked.value || !result) return;
  if (!isCellEditable(rowIndex, columnIndex)) {
    ElMessage.warning("该字段类型或行唯一键不支持直接修改");
    return;
  }
  const family = result.mutationTarget?.columns.find((item) => item.resultIndex === columnIndex)?.typeFamily;
  if (family === "raw" || family === "clob" || family === "blob") {
    largeValueEditor.value = { visible: true, rowIndex, columnIndex, family };
    return;
  }
  const value = result.rows[rowIndex]?.[columnIndex] ?? null;
  editingCell.value = { rowIndex, columnIndex, value, valueAtOpen: value };
}

function saveLargeValueDraft(value: ResultMutationValue): void {
  const edit = largeValueEditor.value;
  const currentExecution = execution.value;
  const result = activeResult.value;
  if (!edit || !currentExecution || !result) return;
  const current = result.rows[edit.rowIndex]?.[edit.columnIndex] ?? null;
  const rowId = resultRowId(edit.rowIndex);
  const previousMutation = activeEditSession.value?.inserts.find((item) => item.rowId === rowId)?.values
    .find((item) => item.columnIndex === edit.columnIndex)?.value
    ?? activeEditSession.value?.cells.find((item) => item.rowId === rowId
      && item.columnIndex === edit.columnIndex)?.draftMutation;
  if (value.kind === "text") {
    resultEdits.stage(currentExecution.editorId, currentExecution.executionId, result.resultIndex,
      edit.rowIndex, edit.columnIndex, current, value.value, rowId);
    queries.updateCells(currentExecution.editorId, result.resultIndex, [{ rowIndex: edit.rowIndex,
      columnIndex: edit.columnIndex, value: value.value }], currentExecution.executionId);
  } else {
    resultEdits.stageMutation(currentExecution.editorId, currentExecution.executionId, result.resultIndex,
      edit.rowIndex, edit.columnIndex, current, value, rowId);
  }
  if (previousMutation?.kind === "largeValueToken"
      && (value.kind !== "largeValueToken" || value.value !== previousMutation.value)) {
    void rpc.deleteResultLargeValueDraft(currentExecution.editorId, currentExecution.executionId,
      result.resultIndex, edit.columnIndex, previousMutation.value);
  }
}

function updateEditingValue(value: string): void {
  if (editingCell.value) editingCell.value.value = value;
}

function commitCellEdit(reason: "enter" | "blur" | "viewport" = "blur"): void {
  const edit = editingCell.value;
  const currentExecution = execution.value;
  const result = activeResult.value;
  if (!edit || !currentExecution || !result) return;
  editingCell.value = undefined;
  if (reason === "enter") restoreTableFocusAfterEdit();
  if (edit.value === edit.valueAtOpen) return;
  resultEdits.stage(currentExecution.editorId, currentExecution.executionId, result.resultIndex,
    edit.rowIndex, edit.columnIndex, edit.valueAtOpen, edit.value, resultRowId(edit.rowIndex));
  queries.updateCells(currentExecution.editorId, result.resultIndex, [{
    rowIndex: edit.rowIndex, columnIndex: edit.columnIndex, value: edit.value
  }], currentExecution.executionId);
}

function restoreTableFocusAfterEdit(): void {
  void nextTick(() => {
    if (editingCell.value) return;
    tableHost.value?.focus({ preventScroll: true });
  });
}

function cancelCellEdit(): void { editingCell.value = undefined; }

function setSelectedCellsNull(): void {
  const currentExecution = execution.value;
  const result = activeResult.value;
  if (!currentExecution || !result || !canSetSelectedCellNull.value) return;
  const updates: Array<{ rowIndex: number; columnIndex: number; value: null }> = [];
  const cells = singleRecordMode.value ? selectedSingleRecordCells.value : selectedCellsInView.value;
  for (const cell of cells) {
    const current = result.rows[cell.sourceRow]?.[cell.sourceColumn] ?? null;
    if (current === null) continue;
    resultEdits.stage(currentExecution.editorId, currentExecution.executionId, result.resultIndex,
      cell.sourceRow, cell.sourceColumn, current, null, resultRowId(cell.sourceRow));
    updates.push({ rowIndex: cell.sourceRow, columnIndex: cell.sourceColumn, value: null });
  }
  queries.updateCells(currentExecution.editorId, result.resultIndex, updates, currentExecution.executionId);
}

function isCellEditable(rowIndex: number, columnIndex: number): boolean {
  const result = activeResult.value;
  const target = result?.mutationTarget;
  const row = result?.rows[rowIndex];
  const column = target?.columns.find((item) => item.resultIndex === columnIndex);
  if (!(target?.mode === "editable" || target?.editableForUpdate) || !row || !column || column.editable === false
      || !editableJdbcType(column.jdbcType)) return false;
  if (resultEdits.isDeleted(execution.value?.editorId ?? "", execution.value?.executionId ?? "",
      result?.resultIndex ?? -1, resultRowId(rowIndex))) return false;
  return Boolean(target.updateSupported !== false || activeEditSession.value?.inserts
    .some((item) => item.rowId === resultRowId(rowIndex) && item.status === "draft"));
}

function editableJdbcType(jdbcType: number): boolean {
  return !new Set([1111, 2000, 2002, 2003, 2006, 2009])
    .has(jdbcType);
}

function tableKeydown(event: KeyboardEvent): void {
  const target = event.target as HTMLElement | null;
  if (target?.matches("input, textarea, select, [contenteditable='true']")) return;
  if (singleRecordMode.value) {
    if (event.key === "Escape") {
      event.preventDefault(); singleRecordView.value?.clearSelection(); closeDataMenu(); closeHeaderMenu(); return;
    }
    if ((event.ctrlKey || event.metaKey) && event.key.toLocaleLowerCase() === "c" && hasDataSelection.value) {
      event.preventDefault(); void copyCurrentSelection(); return;
    }
    singleRecordView.value?.handleKeydown(event);
    return;
  }
  if (moveFocusedCell(event)) return;
  if (event.key === "Escape") { clearSelection(); closeDataMenu(); return; }
  if ((event.key === "Enter" || event.key === "F2") && resultEditUnlocked.value) {
    const selected = selectedCellsInView.value[0];
    if (selected) {
      event.preventDefault();
      startEditCell(selected.sourceRow, selected.sourceColumn);
    }
    return;
  }
  if ((event.ctrlKey || event.metaKey) && event.key.toLocaleLowerCase() === "c" && hasDataSelection.value) {
    event.preventDefault(); void copyCurrentSelection();
  }
}

function moveFocusedCell(event: KeyboardEvent): boolean {
  const movement: Record<string, CellPoint> = {
    ArrowUp: { row: -1, column: 0 }, ArrowDown: { row: 1, column: 0 },
    ArrowLeft: { row: 0, column: -1 }, ArrowRight: { row: 0, column: 1 }
  };
  const delta = movement[event.key];
  if (!delta || event.ctrlKey || event.metaKey || event.altKey
      || singleRecordMode.value || selectionMode.value !== "cells") return false;
  const active = focusedCellInView();
  if (!active || !displayRows.value.length || !visibleColumnOptions.value.length) return false;
  const targetPoint = {
    row: Math.max(0, Math.min(displayRows.value.length - 1, active.row + delta.row)),
    column: Math.max(0, Math.min(visibleColumnOptions.value.length - 1, active.column + delta.column))
  };
  event.preventDefault();
  event.stopPropagation();
  if (event.shiftKey) {
    const anchor = cellAnchor.value
      && cellAnchor.value.row >= 0 && cellAnchor.value.row < displayRows.value.length
      && cellAnchor.value.column >= 0 && cellAnchor.value.column < visibleColumnOptions.value.length
      ? cellAnchor.value : { row: active.row, column: active.column };
    cellAnchor.value = anchor;
    cellRange.value = { start: anchor, end: targetPoint };
  } else {
    cellAnchor.value = targetPoint;
    cellRange.value = { start: targetPoint, end: targetPoint };
  }
  selectedCells.value = cellsInRange(cellRange.value);
  focusCellAt(targetPoint);
  selectStatusColumn(targetPoint.column);
  void nextTick(() => scrollCellIntoView(targetPoint));
  return true;
}

function scrollCellIntoView(point: CellPoint): void {
  virtualGrid.value?.scrollCellIntoView(point.row, point.column);
}

function autoScrollSelection(event: PointerEvent): void {
  if ((!selectingCells.value && !selectingRows.value) || !tableHost.value) return;
  const bounds = tableHost.value.getBoundingClientRect();
  const edge = 26;
  const delta = { left: 0, top: 0 };
  if (event.clientY < bounds.top + edge) delta.top -= 18;
  else if (event.clientY > bounds.bottom - edge) delta.top += 18;
  if (event.clientX < bounds.left + edge) delta.left -= 18;
  else if (event.clientX > bounds.right - edge) delta.left += 18;
  if (!delta.left && !delta.top) return;
  const scroller = tableHost.value.querySelector<HTMLElement>(".result-virtual-grid__viewport");
  if (scroller) {
    scroller.scrollTop += delta.top;
    scroller.scrollLeft += delta.left;
  }
}

function currentScrollPosition(): ResultGridScrollPosition {
  return virtualGrid.value?.getScrollPosition() ?? { left: 0, top: 0 };
}

function restoreScrollPosition(position: ResultGridScrollPosition): void {
  virtualGrid.value?.setScrollPosition(position);
}

async function copyText(text: string, successMessage: string): Promise<void> {
  try {
    await writeClipboardText(text);
    ElMessage.success(successMessage);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "复制失败");
  }
}

function closeResultTab(key: string | number): void {
  const tab = resultTabs.value.find((item) => item.key === String(key));
  if (tab?.execution.temporary) emit("close-result", tab.execution.executionId);
}

function emitVisibleExport(format: ResultExportFormat, rows: number[], columns: number[]): void {
  const currentExecution = execution.value;
  const result = activeResult.value;
  if (serverExportBlocked.value || !result?.columns.length || !columns.length) return;
  emit("export-result", {
    format, scope: "visible", editorId: currentExecution.editorId,
    executionId: currentExecution.executionId, resultIndex: result.resultIndex,
    rowIndices: rows, columnIndices: columns
  });
}

function exportCommand(command: string): void {
  if (!["csv", "excel", "sql"].includes(command)) return;
  if (serverExportBlocked.value) return;
  const format = command as ResultExportFormat;
  if (format === "sql" && !canExportAllSql.value) return;
  if (activeResult.value?.truncated) {
    pendingExportFormat.value = format;
    exportPromptVisible.value = true;
    return;
  }
  emitVisibleExport(format, displayRows.value.map((row) => row.sourceIndex),
    visibleColumnOptions.value.map((column) => column.index));
}

function exportPromptDecision(scope: "visible" | "full"): void {
  const format = pendingExportFormat.value;
  pendingExportFormat.value = undefined;
  exportPromptVisible.value = false;
  const currentExecution = execution.value;
  const result = activeResult.value;
  if (!format || !currentExecution || !result || serverExportBlocked.value) return;
  if (scope === "visible") {
    emitVisibleExport(format, displayRows.value.map((row) => row.sourceIndex),
      visibleColumnOptions.value.map((column) => column.index));
    return;
  }
  if (format === "sql" && !canExportAllSql.value) return;
  emit("export-result", { format, scope: "full", editorId: currentExecution.editorId,
    executionId: currentExecution.executionId, resultIndex: result.resultIndex });
}

defineExpose({
  restoreLayout,
  toggleSingleRecordView,
  toggleRecordComparison,
  copyCurrentSelection,
});

onBeforeUnmount(() => {
  finishColumnResize(); finishCellSelection(); finishRowSelection(); finishColumnSelection(); endColumnDrag(); closeHeaderMenu(); closeDataMenu();
  window.removeEventListener("pointerup", finishCellSelection);
  window.removeEventListener("pointerup", finishRowSelection);
});
</script>

<style scoped>
.result-panel { position: relative; display: flex; flex-direction: column; background: var(--db-content); }
.result-export-control { position: relative; display: inline-flex; align-items: center; }
.result-export-popconfirm-anchor { position: absolute; right: 0; top: 50%; width: 1px; height: 1px; }
.result-loading {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  overflow: hidden;
  color: var(--db-muted);
  font-size: 12px;
}
.result-loading__image {
  width: 160px;
  max-width: 45%;
  max-height: calc(100% - 32px);
  object-fit: contain;
}
.sql-timeline-success-announcement {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
.result-header {
  min-height: 38px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 7px 0 10px;
  border-bottom: 1px solid var(--db-border-soft);
  background: var(--db-panel-soft);
}
.result-tabs { min-width: 100px; max-width: 38%; }
.result-meta { display: inline-flex; align-items: center; gap: 7px; color: var(--db-muted); font-size: 11px; white-space: nowrap; }
.result-actions { margin-left: auto; display: inline-flex; align-items: center; gap: 2px; }
.result-actions .el-select { width: 210px; }
.result-actions :deep(.el-button) { width: 28px; min-height: 28px; padding: 0; }
.result-actions :deep(.result-edit-mode) { width: auto; padding: 0 8px; font-size: 11px; }
.result-actions :deep(.el-dropdown) { display: inline-flex; }
.single-record-navigation { display: inline-flex; align-items: center; gap: 1px; }
.record-compare-control { display: inline-flex; align-items: center; gap: 0; }
.result-actions :deep(.record-compare-button) { border-radius: 6px 0 0 6px; }
.result-actions :deep(.record-compare-options) { width: 18px; border-radius: 0 6px 6px 0; }
.result-actions :deep(.record-compare-options .el-icon) { margin: 0; font-size: 10px; }
:global(.record-compare-menu .el-dropdown-menu__item .el-icon) { width: 14px; margin-right: 6px; }
.single-record-navigation-enter-active {
  animation: single-record-navigation-in 380ms cubic-bezier(.22, 1.35, .36, 1) both;
}
@keyframes single-record-navigation-in {
  0% { opacity: 0; transform: translateX(8px) scale(.78); }
  65% { opacity: 1; transform: translateX(-2px) scale(1.08); }
  84% { transform: translateX(1px) scale(.98); }
  100% { opacity: 1; transform: translateX(0) scale(1); }
}
.result-edit-operations {
  width: 148px;
  max-width: 148px;
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 2px;
  overflow: hidden;
  transform-origin: right center;
}
.result-edit-operation { width: 28px; display: inline-flex; flex: none; }
.result-edit-actions-enter-active {
  animation: result-edit-actions-expand 504ms cubic-bezier(.22, 1.35, .36, 1) both;
}
.result-edit-actions-enter-active .result-edit-operation {
  animation: result-edit-operation-in 360ms cubic-bezier(.22, 1.35, .36, 1) both;
}
.result-edit-actions-enter-active .result-edit-operation:nth-child(5) { animation-delay: 0ms; }
.result-edit-actions-enter-active .result-edit-operation:nth-child(4) { animation-delay: var(--duration-stagger); }
.result-edit-actions-enter-active .result-edit-operation:nth-child(3) {
  animation-delay: calc(var(--duration-stagger) + var(--duration-stagger));
}
.result-edit-actions-enter-active .result-edit-operation:nth-child(2) {
  animation-delay: calc(var(--duration-stagger) + var(--duration-stagger) + var(--duration-stagger));
}
.result-edit-actions-enter-active .result-edit-operation:nth-child(1) {
  animation-delay: calc(var(--duration-stagger) + var(--duration-stagger) + var(--duration-stagger) + var(--duration-stagger));
}
.result-edit-actions-leave-active {
  transition: max-width var(--duration-quick) var(--ease-smooth-out);
}
.result-edit-actions-leave-active .result-edit-operation {
  animation: result-edit-operation-out var(--duration-quick) var(--ease-smooth-out) both;
}
.result-edit-actions-leave-to { max-width: 0; }
@keyframes result-edit-actions-expand {
  0% { max-width: 0; }
  68% { max-width: 148px; }
  84% { transform: scaleX(1.025); }
  100% { max-width: 148px; transform: scaleX(1); }
}
@keyframes result-edit-operation-in {
  0% { opacity: 0; transform: translateX(12px) scale(.82); }
  68% { opacity: 1; transform: translateX(-2px) scale(1.08); }
  84% { transform: translateX(1px) scale(.98); }
  100% { opacity: 1; transform: translateX(0) scale(1); }
}
@keyframes result-edit-operation-out {
  from { opacity: 1; transform: translateX(0) scale(1); }
  to { opacity: 0; transform: translateX(8px) scale(.88); }
}
.result-action-divider { width: 1px; height: 18px; margin: 0 4px; background: var(--db-border-soft); }
.result-change-summary { display: flex; gap: 18px; margin-bottom: 12px; color: var(--db-muted); font-size: 12px; }
.result-change-preview-error { margin-top: 12px; }
.column-option {
  width: 100%;
  min-width: 0;
  display: flex;
  align-items: baseline;
  gap: 10px;
  overflow: hidden;
}
.column-option span {
  min-width: 0;
  max-width: 55%;
  flex: 0 0 auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.column-option span:only-child { max-width: 100%; }
.column-option small {
  min-width: 0;
  flex: 1 1 0;
  overflow: hidden;
  color: var(--db-muted);
  font-size: 10px;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.table-host {
  flex: 1;
  min-height: 0;
  outline: none;
  font-family: var(--db-result-font-family);
  font-size: var(--db-result-font-size);
  font-weight: var(--db-result-cell-font-weight);
  font-style: var(--db-result-cell-font-style);
  font-variant-numeric: tabular-nums;
  background: var(--db-result-bg);
}
.table-host:focus-visible { box-shadow: inset 0 0 0 1px var(--db-accent); }
:deep(.result-cell-pending) {
  background: color-mix(in srgb, var(--db-warning) 20%, transparent);
  box-shadow: inset 3px 0 0 var(--db-warning);
}
:deep(.result-cell-posted) {
  background: color-mix(in srgb, var(--db-accent) 14%, transparent);
  box-shadow: inset 3px 0 0 var(--db-accent);
}
:deep(.result-cell-error) {
  background: color-mix(in srgb, var(--el-color-danger) 13%, transparent);
  box-shadow: inset 0 0 0 1px var(--el-color-danger);
}
:deep(.result-cell-editor) {
  box-sizing: border-box;
  width: 100%;
  height: 26px;
  border: 1px solid var(--db-result-selection-border);
  border-radius: 3px;
  outline: 0;
  background: var(--db-result-bg) !important;
  color: var(--db-result-cell-color) !important;
  font-family: var(--db-result-font-family) !important;
  font-size: var(--db-result-font-size) !important;
  font-weight: var(--db-result-cell-font-weight) !important;
  font-style: var(--db-result-cell-font-style) !important;
}
:deep(.result-virtual-grid__viewport) {
  font-family: var(--db-result-font-family);
  font-size: var(--db-result-font-size);
  font-weight: var(--db-result-cell-font-weight);
  font-style: var(--db-result-cell-font-style);
  font-variant-numeric: tabular-nums;
}
.result-empty { flex: 1; }
.result-empty :deep(.el-empty__image) { width: auto; height: auto; }
.result-empty :deep(.el-empty__image .el-icon) {
  width: 44px; height: 44px; border-radius: 13px; background: var(--db-control-bg);
  color: var(--db-text-secondary); font-size: 22px; box-shadow: inset 0 0 0 1px var(--db-border-soft);
}
:deep(.result-tabs .el-tabs__content) { display: none; }
:deep(.result-tabs .el-tabs__header) { height: 37px; }
:deep(.result-tabs .el-tabs__nav-wrap::after) { display: none; }
:deep(.result-tabs .el-tabs__item) { height: 37px; padding: 0 10px; font-size: 12px; }
:deep(.result-cell) {
  display: block;
  width: calc(100% - 6px);
  height: 27px;
  margin: 2px 3px;
  padding: 0 7px;
  overflow: hidden;
  border-radius: 6px;
  font-family: var(--db-result-font-family);
  font-size: var(--db-result-font-size);
  font-weight: var(--db-result-cell-font-weight);
  font-style: var(--db-result-cell-font-style);
  color: var(--db-result-cell-color);
  font-variant-numeric: tabular-nums;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 27px;
  cursor: cell;
  user-select: none;
}
:deep(.result-cell.selected) { outline: 1.5px solid var(--db-result-selection-border); background: var(--db-result-selection-bg); }
:deep(.result-cell.column-selected) { background: var(--db-result-selection-bg); }
:deep(.result-cell.focused) {
  outline: 2px solid var(--db-result-selection-border);
  outline-offset: -1px;
}
:deep(.result-cell.null-value) {
  color: var(--db-result-null-color) !important;
  font-weight: var(--db-result-null-font-weight) !important;
  font-style: var(--db-result-null-font-style) !important;
}
:deep(.result-cell.binary-value) {
  color: var(--db-result-binary-color) !important;
  font-weight: var(--db-result-binary-font-weight) !important;
  font-style: var(--db-result-binary-font-style) !important;
}
:deep(.result-row-number) {
  position: relative; display: block; width: 100%; height: 32px; margin: 0; border: 0; border-radius: 0;
  background: var(--db-row-gutter-bg);
  color: var(--db-result-row-number-color); font-family: var(--db-result-font-family); font-size: var(--db-result-font-size);
  font-weight: var(--db-result-row-number-font-weight); font-style: var(--db-result-row-number-font-style); line-height: 32px; text-align: center; user-select: none; cursor: default;
}
:deep(.result-row-number-header) { background: var(--db-result-header-bg); color: var(--db-result-header-color); font-weight: var(--db-result-header-font-weight); cursor: pointer; }
:deep(.result-row-number:not(.result-row-number-header)) { cursor: pointer; }
:deep(.result-virtual-grid__row:hover .result-row-number) { color: var(--db-text-secondary); }
:deep(.result-row-number.selected) { outline: 0; background: var(--db-row-gutter-bg); color: var(--db-accent); font-weight: 600; }
:deep(.result-row-number.selected::before) {
  content: ""; position: absolute; top: 5px; bottom: 5px; left: 0; width: 2px;
  border-radius: 0 2px 2px 0; background: var(--db-accent);
}

@media (max-width: 1080px) {
  .result-meta { display: none; }
  .result-tabs { max-width: 45%; }
}
@media (prefers-reduced-motion: reduce) {
  .single-record-navigation-enter-active { animation: none !important; }
  .result-edit-actions-enter-active,
  .result-edit-actions-leave-active {
    animation: none !important;
    transition: max-width 100ms linear, opacity 100ms linear !important;
  }
  .result-edit-actions-enter-active .result-edit-operation,
  .result-edit-actions-leave-active .result-edit-operation { animation: none !important; }
  .result-edit-actions-enter-from,
  .result-edit-actions-leave-to { max-width: 0; opacity: 0; }
}
</style>

<style>
.result-column-header {
  position: relative; display: flex; width: 100%; height: 100%; align-items: center; gap: 5px;
  padding: 0 10px; outline: none; user-select: none; cursor: grab;
  color: var(--db-result-header-color);
  font-family: var(--db-result-font-family);
  font-size: var(--db-result-font-size);
  font-weight: var(--db-result-header-font-weight);
  font-style: var(--db-result-header-font-style);
  background: var(--db-result-header-bg);
}
.result-column-labels {
  display: flex; min-width: 24px; flex: 1; flex-direction: column; justify-content: center;
  overflow: hidden; line-height: 15px;
}
.result-column-header:active { cursor: grabbing; }
.result-column-header.selected { background: var(--db-result-selection-bg); color: var(--db-result-selection-border); }
.result-column-header:focus-visible { box-shadow: inset 0 0 0 1.5px var(--db-accent); }
.result-column-header.drop-before::before, .result-column-header.drop-after::after {
  position: absolute; z-index: 2; top: 2px; bottom: 2px; width: 2px; border-radius: 2px;
  background: var(--db-accent); content: "";
}
.result-column-header.drop-before::before { left: 0; }
.result-column-header.drop-after::after { right: 0; }
.result-column-title, .result-column-remarks { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.result-column-title { font-size: var(--db-result-font-size); font-weight: var(--db-result-header-font-weight); font-style: var(--db-result-header-font-style); }
.result-column-remarks { color: var(--db-muted); font-size: 10px; font-weight: 400; }
.column-selection-count {
  flex: none; padding: 1px 5px; border-radius: 8px; background: var(--db-accent); color: #fff; font-size: 9px;
}
.column-resize-handle {
  position: absolute; z-index: 3; top: 3px; right: -3px; bottom: 3px; width: 7px; cursor: col-resize;
}
.column-resize-handle::after {
  position: absolute; top: 4px; right: 3px; bottom: 4px; width: 1px; background: var(--db-border-soft); content: "";
}
.column-drag-preview {
  position: fixed; top: -1000px; left: -1000px; padding: 5px 9px; border: 1px solid var(--db-border-soft);
  border-radius: 8px; background: var(--db-content); color: var(--db-text); font: 12px/1.2 sans-serif;
  box-shadow: 0 6px 18px rgba(0,0,0,.16);
}
</style>
