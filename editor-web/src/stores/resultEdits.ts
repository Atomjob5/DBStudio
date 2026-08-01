import { defineStore } from "pinia";
import { ref } from "vue";

export type ResultMutationValue =
  | { kind: "text"; value: string }
  | { kind: "null" }
  | { kind: "default" }
  | { kind: "largeValueToken"; value: string };

export interface ResultCellEdit {
  rowId?: string;
  rowIndex: number;
  columnIndex: number;
  originalValue: string | null;
  confirmedValue: string | null;
  draftValue: string | null;
  draftMutation?: ResultMutationValue;
  appliedMutation?: boolean;
  error?: string;
  sequence?: number;
}

export interface ResultInsertEdit {
  operationId: string;
  rowId: string;
  rowIndex: number;
  values: Array<{ columnIndex: number; value: ResultMutationValue }>;
  status: "draft" | "applied";
  sequence: number;
}

export interface ResultDeleteEdit {
  operationId: string;
  rowId: string;
  rowIndex: number;
  row: Array<string | null>;
  status: "draft" | "applied";
  sequence: number;
}

export interface ResultEditOperation {
  operationId: string;
  kind: "update" | "insert" | "delete";
  rowId?: string;
  rowIndex: number;
  values: Array<{ columnIndex: number; value: ResultMutationValue }>;
  sequence: number;
}

export interface ResultEditSession {
  editorId: string;
  executionId: string;
  resultIndex: number;
  unlocked: boolean;
  cells: ResultCellEdit[];
  inserts: ResultInsertEdit[];
  deletes: ResultDeleteEdit[];
  nextSequence: number;
}

export type UndoResult =
  | { kind: "cell"; rowIndex: number; columnIndex: number; value: string | null; largeValueToken?: string }
  | { kind: "insert"; rowId: string; largeValues: Array<{ columnIndex: number; token: string }> }
  | { kind: "delete"; rowId: string }
  | undefined;

export interface DiscardedResultDrafts {
  cells: Array<{ executionId: string; resultIndex: number; rowIndex: number;
    columnIndex: number; value: string | null }>;
  inserts: Array<{ executionId: string; resultIndex: number; rowId: string }>;
  largeValues: Array<{ executionId: string; resultIndex: number; columnIndex: number; token: string }>;
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
        editorId, executionId, resultIndex, unlocked: false, cells: [], inserts: [], deletes: [], nextSequence: 1
      } };
    }
    return sessions.value[id];
  }

  function setUnlocked(editorId: string, executionId: string, resultIndex: number, unlocked: boolean): void {
    const current = session(editorId, executionId, resultIndex, true);
    if (current) current.unlocked = unlocked;
  }

  function stage(editorId: string, executionId: string, resultIndex: number, rowIndex: number,
                 columnIndex: number, currentValue: string | null, value: string | null,
                 rowId?: string): void {
    const current = session(editorId, executionId, resultIndex, true);
    if (!current) return;
    const inserted = rowId ? current.inserts.find((item) => item.rowId === rowId && item.status === "draft") : undefined;
    if (inserted) {
      const next: ResultMutationValue = value === null ? { kind: "null" } : { kind: "text", value };
      const existing = inserted.values.find((item) => item.columnIndex === columnIndex);
      if (existing) existing.value = next;
      else inserted.values.push({ columnIndex, value: next });
      inserted.sequence = current.nextSequence++;
      return;
    }
    let cell = current.cells.find((item) => (rowId ? item.rowId === rowId : item.rowIndex === rowIndex)
      && item.columnIndex === columnIndex);
    if (!cell) {
      cell = { rowId, rowIndex, columnIndex, originalValue: currentValue,
        confirmedValue: currentValue, draftValue: value, sequence: current.nextSequence++ };
      current.cells.push(cell);
    } else {
      cell.draftValue = value;
      cell.draftMutation = undefined;
      cell.appliedMutation = false;
      cell.error = undefined;
      cell.sequence = current.nextSequence++;
    }
    if (cell.draftValue === cell.confirmedValue && cell.confirmedValue === cell.originalValue) {
      current.cells = current.cells.filter((item) => item !== cell);
    }
  }

  function stageMutation(editorId: string, executionId: string, resultIndex: number, rowIndex: number,
                         columnIndex: number, currentValue: string | null, value: ResultMutationValue,
                         rowId?: string): void {
    const current = session(editorId, executionId, resultIndex, true);
    if (!current) return;
    const inserted = rowId ? current.inserts.find((item) => item.rowId === rowId && item.status === "draft") : undefined;
    if (inserted) {
      const existing = inserted.values.find((item) => item.columnIndex === columnIndex);
      if (existing) existing.value = value;
      else inserted.values.push({ columnIndex, value });
      inserted.sequence = current.nextSequence++;
      return;
    }
    let cell = current.cells.find((item) => (rowId ? item.rowId === rowId : item.rowIndex === rowIndex)
      && item.columnIndex === columnIndex);
    if (!cell) {
      cell = { rowId, rowIndex, columnIndex, originalValue: currentValue,
        confirmedValue: currentValue, draftValue: currentValue, sequence: current.nextSequence++ };
      current.cells.push(cell);
    }
    cell.draftMutation = value;
    cell.appliedMutation = false;
    cell.error = undefined;
    cell.sequence = current.nextSequence++;
  }

  function addInsert(editorId: string, executionId: string, resultIndex: number, rowId: string,
                     rowIndex: number, editableColumnIndices: number[]): ResultInsertEdit | undefined {
    const current = session(editorId, executionId, resultIndex, true);
    if (!current) return undefined;
    const inserted: ResultInsertEdit = {
      operationId: crypto.randomUUID(), rowId, rowIndex, status: "draft", sequence: current.nextSequence++,
      values: editableColumnIndices.map((columnIndex) => ({ columnIndex, value: { kind: "default" } }))
    };
    current.inserts.push(inserted);
    return inserted;
  }

  function markDelete(editorId: string, executionId: string, resultIndex: number, rowId: string,
                      rowIndex: number, row: Array<string | null>): "deleted" | "cancelled-insert" {
    const current = session(editorId, executionId, resultIndex, true);
    if (!current) return "deleted";
    const inserted = current.inserts.find((item) => item.rowId === rowId && item.status === "draft");
    if (inserted) {
      current.inserts = current.inserts.filter((item) => item !== inserted);
      current.cells = current.cells.filter((item) => item.rowId !== rowId);
      return "cancelled-insert";
    }
    const existing = current.deletes.find((item) => item.rowId === rowId);
    if (!existing) current.deletes.push({ operationId: crypto.randomUUID(), rowId, rowIndex,
      row: [...row], status: "draft", sequence: current.nextSequence++ });
    return "deleted";
  }

  function isDeleted(editorId: string, executionId: string, resultIndex: number, rowId?: string): boolean {
    return Boolean(rowId && session(editorId, executionId, resultIndex)?.deletes
      .some((item) => item.rowId === rowId));
  }

  function cellState(editorId: string, executionId: string, resultIndex: number,
                     rowIndex: number, columnIndex: number): "pending" | "posted" | "error" | undefined {
    const cell = session(editorId, executionId, resultIndex)?.cells.find(
      (item) => item.rowIndex === rowIndex && item.columnIndex === columnIndex);
    if (!cell) return undefined;
    if (cell.error) return "error";
    return cell.draftMutation || cell.draftValue !== cell.confirmedValue ? "pending"
      : cell.appliedMutation || cell.confirmedValue !== cell.originalValue ? "posted" : undefined;
  }

  function operations(current: ResultEditSession, pendingOnly = true): ResultEditOperation[] {
    const result: ResultEditOperation[] = [];
    const deleted = new Set(current.deletes.map((item) => item.rowId));
    const grouped = new Map<string, ResultCellEdit[]>();
    for (const cell of current.cells) {
      if ((!cell.draftMutation && cell.draftValue === cell.confirmedValue)
          || cell.rowId && deleted.has(cell.rowId)) continue;
      const identity = cell.rowId || `row:${cell.rowIndex}`;
      grouped.set(identity, [...(grouped.get(identity) ?? []), cell]);
    }
    for (const [identity, cells] of grouped) result.push({
      operationId: `update:${identity}`, kind: "update", rowId: cells[0].rowId,
      rowIndex: cells[0].rowIndex, sequence: Math.min(...cells.map((item) => item.sequence ?? 0)),
      values: cells.map((cell) => ({ columnIndex: cell.columnIndex,
        value: cell.draftMutation ?? (cell.draftValue === null
          ? { kind: "null" } : { kind: "text", value: cell.draftValue }) }))
    });
    for (const insert of current.inserts) if (!pendingOnly || insert.status === "draft") result.push({
      operationId: insert.operationId, kind: "insert", rowId: insert.rowId, rowIndex: insert.rowIndex,
      sequence: insert.sequence, values: insert.values.map((item) => ({ ...item }))
    });
    for (const deletion of current.deletes) if (!pendingOnly || deletion.status === "draft") result.push({
      operationId: deletion.operationId, kind: "delete", rowId: deletion.rowId,
      rowIndex: deletion.rowIndex, sequence: deletion.sequence, values: []
    });
    return result.sort((left, right) => left.sequence - right.sequence);
  }

  function pending(editorId?: string): ResultCellEdit[] {
    return Object.values(sessions.value).filter((item) => !editorId || item.editorId === editorId)
      .flatMap((item) => item.cells.filter((cell) => Boolean(cell.draftMutation)
        || cell.draftValue !== cell.confirmedValue));
  }
  function pendingOperationCount(editorId?: string): number {
    return Object.values(sessions.value).filter((item) => !editorId || item.editorId === editorId)
      .reduce((count, item) => count + operations(item).length, 0);
  }
  function appliedOperationCount(editorId?: string): number {
    return Object.values(sessions.value).filter((item) => !editorId || item.editorId === editorId)
      .reduce((count, item) => count + item.inserts.filter((value) => value.status === "applied").length
        + item.deletes.filter((value) => value.status === "applied").length
        + new Set(item.cells.filter((cell) => cell.appliedMutation || cell.confirmedValue !== cell.originalValue)
          .map((cell) => cell.rowId || cell.rowIndex)).size, 0);
  }
  function hasPending(editorId: string): boolean { return pendingOperationCount(editorId) > 0; }
  function hasChanges(editorId: string): boolean {
    return Object.values(sessions.value).some((item) => item.editorId === editorId && (
      item.inserts.length > 0 || item.deletes.length > 0 || item.cells.some(
        (cell) => Boolean(cell.draftMutation || cell.appliedMutation)
          || cell.draftValue !== cell.originalValue || cell.confirmedValue !== cell.originalValue)));
  }

  function markPosted(editorId: string, executionId: string, resultIndex: number,
                      operationIds?: string[]): void {
    const current = session(editorId, executionId, resultIndex);
    if (!current) return;
    const applied = new Set(operationIds ?? operations(current).map((item) => item.operationId));
    for (const cell of current.cells) if (applied.has(`update:${cell.rowId || `row:${cell.rowIndex}`}`)) {
      cell.confirmedValue = cell.draftValue;
      cell.appliedMutation = Boolean(cell.draftMutation) || cell.appliedMutation;
      cell.draftMutation = undefined;
      cell.error = undefined;
    }
    for (const insert of current.inserts) if (applied.has(insert.operationId)) insert.status = "applied";
    for (const deletion of current.deletes) if (applied.has(deletion.operationId)) deletion.status = "applied";
  }

  function setOperationError(editorId: string, executionId: string, resultIndex: number,
                             operationId: string, columnIndex: number | undefined, message: string): void {
    const current = session(editorId, executionId, resultIndex);
    if (!current || !operationId.startsWith("update:")) return;
    const identity = operationId.slice("update:".length);
    for (const cell of current.cells) if ((cell.rowId || `row:${cell.rowIndex}`) === identity
      && (columnIndex === undefined || cell.columnIndex === columnIndex)) cell.error = message;
  }

  function undo(editorId: string, executionId: string, resultIndex: number): UndoResult {
    const current = session(editorId, executionId, resultIndex);
    if (!current) return undefined;
    const candidates: Array<{ type: "cell" | "insert" | "delete"; sequence: number; value: any }> = [
      ...current.cells.filter((item) => Boolean(item.draftMutation) || item.draftValue !== item.confirmedValue)
        .map((value) => ({ type: "cell" as const, sequence: value.sequence ?? 0, value })),
      ...current.inserts.filter((item) => item.status === "draft")
        .map((value) => ({ type: "insert" as const, sequence: value.sequence, value })),
      ...current.deletes.filter((item) => item.status === "draft")
        .map((value) => ({ type: "delete" as const, sequence: value.sequence, value }))
    ].sort((left, right) => right.sequence - left.sequence);
    const latest = candidates[0];
    if (!latest) return undefined;
    if (latest.type === "cell") {
      const cell = latest.value as ResultCellEdit;
      const largeValueToken = cell.draftMutation?.kind === "largeValueToken"
        ? cell.draftMutation.value : undefined;
      cell.draftMutation = undefined;
      cell.draftValue = cell.confirmedValue;
      if (cell.confirmedValue === cell.originalValue) current.cells = current.cells.filter((item) => item !== cell);
      return { kind: "cell", rowIndex: cell.rowIndex, columnIndex: cell.columnIndex,
        value: cell.draftValue, largeValueToken };
    }
    if (latest.type === "insert") {
      const insert = latest.value as ResultInsertEdit;
      current.inserts = current.inserts.filter((item) => item !== insert);
      return { kind: "insert", rowId: insert.rowId,
        largeValues: insert.values.flatMap((item) => item.value.kind === "largeValueToken"
          ? [{ columnIndex: item.columnIndex, token: item.value.value }] : []) };
    }
    const deletion = latest.value as ResultDeleteEdit;
    current.deletes = current.deletes.filter((item) => item !== deletion);
    return { kind: "delete", rowId: deletion.rowId };
  }

  function discardPending(editorId: string): DiscardedResultDrafts {
    const discarded: DiscardedResultDrafts = { cells: [], inserts: [], largeValues: [] };
    for (const current of Object.values(sessions.value)) {
      if (current.editorId !== editorId) continue;
      for (const cell of current.cells) {
        if (!cell.draftMutation && cell.draftValue === cell.confirmedValue) continue;
        discarded.cells.push({ executionId: current.executionId, resultIndex: current.resultIndex,
          rowIndex: cell.rowIndex, columnIndex: cell.columnIndex, value: cell.confirmedValue });
        if (cell.draftMutation?.kind === "largeValueToken") discarded.largeValues.push({
          executionId: current.executionId, resultIndex: current.resultIndex,
          columnIndex: cell.columnIndex, token: cell.draftMutation.value
        });
        cell.draftMutation = undefined;
        cell.draftValue = cell.confirmedValue;
        cell.error = undefined;
      }
      current.cells = current.cells.filter((cell) => cell.appliedMutation
        || cell.confirmedValue !== cell.originalValue);
      for (const insert of current.inserts.filter((item) => item.status === "draft")) {
        discarded.inserts.push({ executionId: current.executionId,
          resultIndex: current.resultIndex, rowId: insert.rowId });
        for (const value of insert.values) if (value.value.kind === "largeValueToken") {
          discarded.largeValues.push({ executionId: current.executionId,
            resultIndex: current.resultIndex, columnIndex: value.columnIndex, token: value.value.value });
        }
      }
      current.inserts = current.inserts.filter((item) => item.status === "applied");
      current.deletes = current.deletes.filter((item) => item.status === "applied");
    }
    return discarded;
  }

  function restore(editorId: string, mode: "confirmed" | "original"):
    Array<{ resultIndex: number; rowIndex: number; columnIndex: number; value: string | null }> {
    const restored: Array<{ resultIndex: number; rowIndex: number; columnIndex: number; value: string | null }> = [];
    for (const current of Object.values(sessions.value)) {
      if (current.editorId !== editorId) continue;
      for (const cell of current.cells) restored.push({ resultIndex: current.resultIndex,
        rowIndex: cell.rowIndex, columnIndex: cell.columnIndex,
        value: mode === "confirmed" ? cell.confirmedValue : cell.originalValue });
    }
    return restored;
  }

  function finishEditor(editorId: string): void {
    sessions.value = Object.fromEntries(Object.entries(sessions.value)
      .filter(([, current]) => current.editorId !== editorId));
  }
  function clear(): void { sessions.value = {}; }

  return { sessions, session, setUnlocked, stage, stageMutation, addInsert, markDelete, isDeleted, cellState,
    operations, pending, pendingOperationCount, appliedOperationCount, hasPending, hasChanges,
    markPosted, setOperationError, undo, discardPending, restore, finishEditor, clear };
});
