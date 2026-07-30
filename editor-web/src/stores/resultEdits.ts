import { defineStore } from "pinia";
import { ref } from "vue";

export interface ResultCellEdit {
  rowIndex: number;
  columnIndex: number;
  originalValue: string | null;
  confirmedValue: string | null;
  draftValue: string | null;
}

export interface ResultEditSession {
  editorId: string;
  executionId: string;
  resultIndex: number;
  unlocked: boolean;
  cells: ResultCellEdit[];
}

export const useResultEditStore = defineStore("result-edits", () => {
  const sessions = ref<Record<string, ResultEditSession>>({});

  function key(editorId: string, executionId: string, resultIndex: number): string {
    return `${editorId}:${executionId}:${resultIndex}`;
  }

  function session(editorId: string, executionId: string, resultIndex: number,
                   create = false): ResultEditSession | undefined {
    const id = key(editorId, executionId, resultIndex);
    if (!sessions.value[id] && create) {
      sessions.value = { ...sessions.value, [id]: {
        editorId, executionId, resultIndex, unlocked: false, cells: []
      } };
    }
    return sessions.value[id];
  }

  function setUnlocked(editorId: string, executionId: string, resultIndex: number, unlocked: boolean): void {
    const current = session(editorId, executionId, resultIndex, true);
    if (!current) return;
    current.unlocked = unlocked;
  }

  function stage(editorId: string, executionId: string, resultIndex: number, rowIndex: number,
                 columnIndex: number, currentValue: string | null, value: string | null): void {
    const current = session(editorId, executionId, resultIndex, true);
    if (!current) return;
    let cell = current.cells.find((item) => item.rowIndex === rowIndex && item.columnIndex === columnIndex);
    if (!cell) {
      cell = { rowIndex, columnIndex, originalValue: currentValue,
        confirmedValue: currentValue, draftValue: value };
      current.cells.push(cell);
    } else {
      cell.draftValue = value;
    }
    if (cell.draftValue === cell.confirmedValue && cell.confirmedValue === cell.originalValue) {
      current.cells = current.cells.filter((item) => item !== cell);
    }
  }

  function cellState(editorId: string, executionId: string, resultIndex: number,
                     rowIndex: number, columnIndex: number): "pending" | "posted" | undefined {
    const cell = session(editorId, executionId, resultIndex)?.cells.find(
      (item) => item.rowIndex === rowIndex && item.columnIndex === columnIndex);
    if (!cell) return undefined;
    return cell.draftValue !== cell.confirmedValue ? "pending"
      : cell.confirmedValue !== cell.originalValue ? "posted" : undefined;
  }

  function pending(editorId?: string): ResultCellEdit[] {
    return Object.values(sessions.value)
      .filter((item) => !editorId || item.editorId === editorId)
      .flatMap((item) => item.cells.filter((cell) => cell.draftValue !== cell.confirmedValue));
  }

  function hasPending(editorId: string): boolean { return pending(editorId).length > 0; }
  function hasChanges(editorId: string): boolean {
    return Object.values(sessions.value).some((item) => item.editorId === editorId && item.cells.some(
      (cell) => cell.draftValue !== cell.originalValue || cell.confirmedValue !== cell.originalValue));
  }

  function markPosted(editorId: string, executionId: string, resultIndex: number): void {
    const current = session(editorId, executionId, resultIndex);
    if (!current) return;
    for (const cell of current.cells) cell.confirmedValue = cell.draftValue;
  }

  function restore(editorId: string, mode: "confirmed" | "original"):
    Array<{ resultIndex: number; rowIndex: number; columnIndex: number; value: string | null }> {
    const restored: Array<{ resultIndex: number; rowIndex: number; columnIndex: number; value: string | null }> = [];
    for (const current of Object.values(sessions.value)) {
      if (current.editorId !== editorId) continue;
      for (const cell of current.cells) restored.push({
        resultIndex: current.resultIndex, rowIndex: cell.rowIndex, columnIndex: cell.columnIndex,
        value: mode === "confirmed" ? cell.confirmedValue : cell.originalValue
      });
    }
    return restored;
  }

  function finishEditor(editorId: string): void {
    sessions.value = Object.fromEntries(Object.entries(sessions.value)
      .filter(([, current]) => current.editorId !== editorId));
  }

  function clear(): void { sessions.value = {}; }

  return { sessions, session, setUnlocked, stage, cellState, pending, hasPending, hasChanges,
    markPosted, restore, finishEditor, clear };
});
