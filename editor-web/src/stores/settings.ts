import { defineStore } from "pinia";
import { ref } from "vue";
import type { ColumnLayoutScope } from "../columnLayout";
import type { CopySeparator } from "../resultCopy";
import type { SqlCompletionSnippet } from "../types";
import { parseSqlCompletionSnippets } from "../completion/snippets";
import {
  DEFAULT_SHORTCUT_BINDINGS,
  parseShortcutBindings,
  type ShortcutActionId,
  type ShortcutBinding,
  type ShortcutBindings,
} from "../shortcuts";

export const useSettingsStore = defineStore("settings", () => {
  const maxResultRows = ref(1000);
  const streamBatchRows = ref(100);
  const maxResultLobBytes = ref(268_435_456);
  const columnLayoutScope = ref<ColumnLayoutScope>("result");
  const copyHeaderOnDoubleClick = ref(true);
  const copySeparator = ref<CopySeparator>("comma");
  const headerSortingEnabled = ref(true);
  const headerFilteringEnabled = ref(true);
  const showColumnRemarksInHeader = ref(false);
  const scrollOptimizationBufferScreens = ref(1);
  const showSelectedColumnRemarks = ref(true);
  const maxActiveSessions = ref(10);
  const autoCommit = ref(false);
  const idleTimeoutMinutes = ref(10);
  const transactionDisconnectRollbackMinutes = ref(10);
  const completionCandidateLimit = ref(100);
  const completionPreciseMatchingEnabled = ref(false);
  const completionSnippets = ref<SqlCompletionSnippet[]>([]);
  const minimapEnabled = ref(true);
  const wordWrapEnabled = ref(false);
  const dangerousStatementWarningEnabled = ref(true);
  const objectInspectorOpacity = ref(100);
  const shortcuts = ref<ShortcutBindings>({ ...DEFAULT_SHORTCUT_BINDINGS });
  const shortcutRecordingActive = ref(false);
  const recentFiles = ref<string[]>([]);

  function initialize(settings: Record<string, string>, recent: string[]): void {
    const parsed = Number.parseInt(settings["result.maxRows"] ?? "1000", 10);
    maxResultRows.value = Number.isFinite(parsed) ? parsed : 1000;
    const batch = Number.parseInt(settings["result.streamBatchRows"] ?? "100", 10);
    streamBatchRows.value = Number.isFinite(batch) ? batch : 100;
    const lobBytes = Number.parseInt(settings["result.edit.maxLobBytes"] ?? "268435456", 10);
    maxResultLobBytes.value = Number.isFinite(lobBytes) ? lobBytes : 268_435_456;
    columnLayoutScope.value = settings["result.columnLayoutScope"] === "editor" ? "editor" : "result";
    copyHeaderOnDoubleClick.value = settings["result.copyHeaderOnDoubleClick"] !== "false";
    const separator = settings["result.copySeparator"];
    copySeparator.value = separator === "tab" || separator === "semicolon" || separator === "pipe" ? separator : "comma";
    headerSortingEnabled.value = settings["result.headerSortingEnabled"] !== "false";
    headerFilteringEnabled.value = settings["result.headerFilteringEnabled"] !== "false";
    showColumnRemarksInHeader.value = settings["result.showColumnRemarksInHeader"] === "true";
    const bufferScreens = Number.parseFloat(settings["result.scrollOptimizationBufferScreens"] ?? "1");
    scrollOptimizationBufferScreens.value = Number.isFinite(bufferScreens)
      && bufferScreens >= 0.5 && bufferScreens <= 3 && Number.isInteger(bufferScreens * 2)
      ? bufferScreens : 1;
    showSelectedColumnRemarks.value = settings["statusBar.showSelectedColumnRemarks"] !== "false";
    const maximum = Number.parseInt(settings["connection.maxActiveSessions"] ?? "10", 10);
    maxActiveSessions.value = Number.isFinite(maximum) ? maximum : 10;
    autoCommit.value = settings["connection.autoCommit"] === "true";
    const idle = Number.parseInt(settings["connection.idleTimeoutMinutes"] ?? "10", 10);
    idleTimeoutMinutes.value = Number.isFinite(idle) ? idle : 10;
    const transactionTimeout = Number.parseInt(settings["connection.transactionDisconnectRollbackMinutes"] ?? "10", 10);
    transactionDisconnectRollbackMinutes.value = Number.isFinite(transactionTimeout) ? transactionTimeout : 10;
    const completionLimit = Number.parseInt(settings["editor.completionCandidateLimit"] ?? "100", 10);
    completionCandidateLimit.value = Number.isFinite(completionLimit) ? Math.max(10, Math.min(1000, completionLimit)) : 100;
    completionPreciseMatchingEnabled.value = settings["editor.completionPreciseMatchingEnabled"] === "true";
    completionSnippets.value = parseSqlCompletionSnippets(settings["editor.completionSnippets"]);
    minimapEnabled.value = settings["editor.minimapEnabled"] !== "false";
    wordWrapEnabled.value = settings["editor.wordWrapEnabled"] === "true";
    dangerousStatementWarningEnabled.value = settings["editor.dangerousStatementWarningEnabled"] !== "false";
    const inspectorOpacity = Number.parseInt(settings["editor.objectInspectorOpacity"] ?? "100", 10);
    objectInspectorOpacity.value = Number.isFinite(inspectorOpacity)
      ? Math.max(1, Math.min(100, inspectorOpacity)) : 100;
    shortcuts.value = parseShortcutBindings(settings["keyboard.shortcuts"]);
    recentFiles.value = recent;
  }

  function setShortcuts(value: ShortcutBindings): void {
    shortcuts.value = { ...value };
  }

  function setShortcut(actionId: ShortcutActionId, binding: ShortcutBinding): void {
    shortcuts.value = { ...shortcuts.value, [actionId]: binding };
  }

  function resetShortcuts(): void {
    shortcuts.value = { ...DEFAULT_SHORTCUT_BINDINGS };
  }

  function setCompletionSnippets(value: SqlCompletionSnippet[]): void {
    completionSnippets.value = value.map((item) => ({ ...item }));
  }

  return { maxResultRows, streamBatchRows, maxResultLobBytes, columnLayoutScope, copyHeaderOnDoubleClick, copySeparator,
    headerSortingEnabled, headerFilteringEnabled, showColumnRemarksInHeader, showSelectedColumnRemarks,
    scrollOptimizationBufferScreens,
    maxActiveSessions, autoCommit, idleTimeoutMinutes, transactionDisconnectRollbackMinutes,
    completionCandidateLimit, completionPreciseMatchingEnabled, completionSnippets,
    minimapEnabled, wordWrapEnabled, dangerousStatementWarningEnabled, objectInspectorOpacity,
    shortcuts, shortcutRecordingActive,
    recentFiles, initialize, setCompletionSnippets, setShortcuts, setShortcut, resetShortcuts };
});
