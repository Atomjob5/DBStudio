import { defineStore } from "pinia";
import { ref } from "vue";
import type { ColumnLayoutScope } from "../columnLayout";
import type { CopySeparator } from "../resultCopy";

export const useSettingsStore = defineStore("settings", () => {
  const maxResultRows = ref(1000);
  const streamBatchRows = ref(100);
  const columnLayoutScope = ref<ColumnLayoutScope>("result");
  const copyHeaderOnDoubleClick = ref(true);
  const copySeparator = ref<CopySeparator>("comma");
  const maxActiveSessions = ref(10);
  const idleTimeoutMinutes = ref(10);
  const recentFiles = ref<string[]>([]);

  function initialize(settings: Record<string, string>, recent: string[]): void {
    const parsed = Number.parseInt(settings["result.maxRows"] ?? "1000", 10);
    maxResultRows.value = Number.isFinite(parsed) ? parsed : 1000;
    const batch = Number.parseInt(settings["result.streamBatchRows"] ?? "100", 10);
    streamBatchRows.value = Number.isFinite(batch) ? batch : 100;
    columnLayoutScope.value = settings["result.columnLayoutScope"] === "editor" ? "editor" : "result";
    copyHeaderOnDoubleClick.value = settings["result.copyHeaderOnDoubleClick"] !== "false";
    const separator = settings["result.copySeparator"];
    copySeparator.value = separator === "tab" || separator === "semicolon" || separator === "pipe" ? separator : "comma";
    const maximum = Number.parseInt(settings["connection.maxActiveSessions"] ?? "10", 10);
    maxActiveSessions.value = Number.isFinite(maximum) ? maximum : 10;
    const idle = Number.parseInt(settings["connection.idleTimeoutMinutes"] ?? "10", 10);
    idleTimeoutMinutes.value = Number.isFinite(idle) ? idle : 10;
    recentFiles.value = recent;
  }

  return { maxResultRows, streamBatchRows, columnLayoutScope, copyHeaderOnDoubleClick, copySeparator,
    maxActiveSessions, idleTimeoutMinutes,
    recentFiles, initialize };
});
