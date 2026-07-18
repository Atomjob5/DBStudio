import { defineStore } from "pinia";
import { ref } from "vue";
import type { ColumnLayoutScope } from "../columnLayout";

export const useSettingsStore = defineStore("settings", () => {
  const maxResultRows = ref(1000);
  const streamBatchRows = ref(100);
  const columnLayoutScope = ref<ColumnLayoutScope>("result");
  const recentFiles = ref<string[]>([]);

  function initialize(settings: Record<string, string>, recent: string[]): void {
    const parsed = Number.parseInt(settings["result.maxRows"] ?? "1000", 10);
    maxResultRows.value = Number.isFinite(parsed) ? parsed : 1000;
    const batch = Number.parseInt(settings["result.streamBatchRows"] ?? "100", 10);
    streamBatchRows.value = Number.isFinite(batch) ? batch : 100;
    columnLayoutScope.value = settings["result.columnLayoutScope"] === "editor" ? "editor" : "result";
    recentFiles.value = recent;
  }

  return { maxResultRows, streamBatchRows, columnLayoutScope, recentFiles, initialize };
});
