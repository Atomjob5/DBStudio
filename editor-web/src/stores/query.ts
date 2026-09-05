import { defineStore } from "pinia";
import { shallowRef } from "vue";
import type { QueryExecutionState, QueryResult } from "../types";
import { ResultRowsSnapshot, rowsView, snapshotFor, viewFor } from "../resultRows";

export const useQueryStore = defineStore("query", () => {
  // Result rows are immutable array-compatible views. Vue observes only the
  // batch-level result replacement while each append shares complete chunks.
  const executions = shallowRef<Record<string, QueryExecutionState>>({});
  const retained = shallowRef<Record<string, QueryExecutionState[]>>({});

  function executionList(editorId: string): QueryExecutionState[] {
    const current = executions.value[editorId];
    return [...(retained.value[editorId] ?? []), ...(current ? [current] : [])];
  }

  function execution(editorId: string, executionId?: string): QueryExecutionState | undefined {
    const current = executions.value[editorId];
    if (!executionId || current?.executionId === executionId) return current;
    return (retained.value[editorId] ?? []).find((item) => item.executionId === executionId);
  }

  function start(editorId: string, executionId: string, presentation: "replace" | "append" = "replace",
                 displayType: "data" | "execution-plan" = "data"): void {
    if (executions.value[editorId]?.executionId === executionId) return;
    const previous = executionList(editorId);
    retained.value = { ...retained.value, [editorId]: presentation === "append" ? previous : [] };
    executions.value = { ...executions.value, [editorId]: {
      executionId, editorId, results: [], busy: true, cancelled: false, failed: false, durationMs: 0,
      temporary: presentation === "append", displayType
    } };
  }

  function addResult(editorId: string, result: QueryResult, executionId?: string): void {
    const execution = executionFor(editorId, executionId);
    if (!execution) {
      if (import.meta.env.DEV) console.warn(`Ignoring late query result for ${editorId}`);
      return;
    }
    replaceExecution(editorId, { ...execution, results: [...execution.results, normalizeResult(result)] });
  }

  function appendRows(editorId: string, resultIndex: number, rows: Array<Array<string | null>>,
                      executionIdOrRowIds?: string | string[], rowIds: string[] = []): void {
    const executionId = typeof executionIdOrRowIds === "string" ? executionIdOrRowIds : undefined;
    const resolvedRowIds = Array.isArray(executionIdOrRowIds) ? executionIdOrRowIds : rowIds;
    const execution = executionFor(editorId, executionId);
    const result = execution?.results.find((item) => item.resultIndex === resultIndex);
    if (!execution || !result) { warnLate(editorId, resultIndex); return; }
    const ids = resolvedRowIds.length === rows.length ? resolvedRowIds
      : rows.map(() => crypto.randomUUID());
    const currentRows = snapshotFor(result.rows);
    const currentIds = result.rowIds ? snapshotFor(result.rowIds) : ResultRowsSnapshot.from(
      Array.from({ length: result.rows.length }, () => crypto.randomUUID()));
    const updated = { ...result, rows: viewFor(currentRows.append(rows)), rowIds: viewFor(currentIds.append(ids)) };
    replaceExecution(editorId, { ...execution, results: execution.results.map((item) => item === result ? updated : item) });
  }

  function completeResult(editorId: string, resultIndex: number, values: Partial<QueryResult>, executionId?: string): void {
    const execution = executionFor(editorId, executionId);
    const result = execution?.results.find((item) => item.resultIndex === resultIndex);
    if (!execution || !result) { warnLate(editorId, resultIndex); return; }
    const updated = normalizeResult({ ...result, ...values, complete: true });
    replaceExecution(editorId, { ...execution, results: execution.results.map((item) => item === result ? updated : item) });
  }

  function updateCells(editorId: string, resultIndex: number,
                       cells: Array<{ rowIndex: number; columnIndex: number; value: string | null }>,
                       executionId?: string): void {
    const execution = executionFor(editorId, executionId);
    const result = execution?.results.find((item) => item.resultIndex === resultIndex);
    if (!execution || !result || !cells.length) return;
    const updates = new Map<number, Array<string | null>>();
    for (const cell of cells) {
      const source = result.rows[cell.rowIndex];
      if (cell.rowIndex < 0 || cell.rowIndex >= result.rows.length
          || cell.columnIndex < 0 || cell.columnIndex >= (source?.length ?? 0)) continue;
      const row = updates.get(cell.rowIndex) ?? [...source];
      row[cell.columnIndex] = cell.value;
      updates.set(cell.rowIndex, row);
    }
    const updated = { ...result, rows: viewFor(snapshotFor(result.rows).update(updates)) };
    replaceExecution(editorId, { ...execution,
      results: execution.results.map((item) => item === result ? updated : item) });
  }

  function appendDraftRow(editorId: string, resultIndex: number, rowId: string,
                          row: Array<string | null>, executionId?: string): void {
    const execution = executionFor(editorId, executionId);
    const result = execution?.results.find((item) => item.resultIndex === resultIndex);
    if (!execution || !result) return;
    const currentIds = result.rowIds ? snapshotFor(result.rowIds) : ResultRowsSnapshot.from(
      Array.from({ length: result.rows.length }, () => crypto.randomUUID()));
    const updated = { ...result, rows: viewFor(snapshotFor(result.rows).append([row])),
      rowIds: viewFor(currentIds.append([rowId])) };
    replaceExecution(editorId, { ...execution,
      results: execution.results.map((item) => item === result ? updated : item) });
  }

  function removeRowById(editorId: string, resultIndex: number, rowId: string, executionId?: string): void {
    const execution = executionFor(editorId, executionId);
    const result = execution?.results.find((item) => item.resultIndex === resultIndex);
    if (!execution || !result) return;
    const index = (result.rowIds ?? []).indexOf(rowId);
    if (index < 0) return;
    const updated = { ...result, rows: viewFor(snapshotFor(result.rows).remove(index)),
      rowIds: viewFor(snapshotFor(result.rowIds ?? []).remove(index)) };
    replaceExecution(editorId, { ...execution,
      results: execution.results.map((item) => item === result ? updated : item) });
  }

  function applyResultPatches(editorId: string, resultIndex: number,
                              patches: Array<{ kind: "update" | "insert" | "delete"; rowId: string;
                                rowIndex: number; row: Array<string | null>; clientRowId?: string }>,
                              executionId?: string): void {
    const execution = executionFor(editorId, executionId);
    const result = execution?.results.find((item) => item.resultIndex === resultIndex);
    if (!execution || !result) return;
    const rows = result.rows.map((row) => [...row]);
    const rowIds = [...(result.rowIds ?? result.rows.map(() => crypto.randomUUID()))];
    for (const patch of patches) {
      if (patch.kind === "delete") {
        const index = rowIds.indexOf(patch.rowId) >= 0 ? rowIds.indexOf(patch.rowId)
          : patch.clientRowId ? rowIds.indexOf(patch.clientRowId) : -1;
        if (index >= 0) { rows.splice(index, 1); rowIds.splice(index, 1); }
      } else if (patch.kind === "update") {
        const index = rowIds.indexOf(patch.rowId);
        if (index >= 0) rows[index] = [...patch.row];
      } else {
        const draftIndex = patch.clientRowId ? rowIds.indexOf(patch.clientRowId) : -1;
        if (draftIndex >= 0) { rows[draftIndex] = [...patch.row]; rowIds[draftIndex] = patch.rowId; }
        else { rows.push([...patch.row]); rowIds.push(patch.rowId); }
      }
    }
    const updated = { ...result, rows: rowsView(rows), rowIds: rowsView(rowIds) };
    replaceExecution(editorId, { ...execution,
      results: execution.results.map((item) => item === result ? updated : item) });
  }

  function replaceResultSnapshot(editorId: string, resultIndex: number,
                                 rows: Array<Array<string | null>>, rowIds: string[], executionId?: string): void {
    const execution = executionFor(editorId, executionId);
    const result = execution?.results.find((item) => item.resultIndex === resultIndex);
    if (!execution || !result) return;
    const updated = { ...result, rows: rowsView(rows), rowIds: rowsView(rowIds) };
    replaceExecution(editorId, { ...execution,
      results: execution.results.map((item) => item === result ? updated : item) });
  }

  function applyColumnRemarks(editorId: string, executionId: string, resultIndex: number,
                              remarks: Array<{ index: number; remarks: string }>): void {
    const execution = executionFor(editorId, executionId);
    const result = execution?.results.find((item) => item.resultIndex === resultIndex);
    if (!execution || execution.executionId !== executionId || !result?.columnDetails || !remarks.length) return;
    const byIndex = new Map(remarks.map((item) => [item.index, item.remarks]));
    const columnDetails = result.columnDetails.map((column, index) => column.remarks || !byIndex.get(index)
      ? column : { ...column, remarks: byIndex.get(index) as string });
    const updated = { ...result, columnDetails };
    replaceExecution(editorId, { ...execution, results: execution.results.map((item) => item === result ? updated : item) });
  }

  function complete(editorId: string, values: Partial<QueryExecutionState>, executionId?: string): void {
    const execution = executionFor(editorId, executionId);
    if (!execution) { if (import.meta.env.DEV) console.warn(`Ignoring late query completion for ${editorId}`); return; }
    replaceExecution(editorId, { ...execution, ...values, busy: false });
  }

  function executionFor(editorId: string, executionId?: string): QueryExecutionState | undefined {
    return execution(editorId, executionId);
  }

  function replaceExecution(editorId: string, execution: QueryExecutionState): void {
    if (executions.value[editorId]?.executionId === execution.executionId) {
      executions.value = { ...executions.value, [editorId]: execution };
      return;
    }
    const current = retained.value[editorId] ?? [];
    if (!current.some((item) => item.executionId === execution.executionId)) return;
    retained.value = { ...retained.value, [editorId]: current.map((item) =>
      item.executionId === execution.executionId ? execution : item) };
  }

  function clearEditor(editorId: string): void {
    const { [editorId]: _removed, ...remaining } = executions.value;
    executions.value = remaining;
    const { [editorId]: _retained, ...remainingRetained } = retained.value;
    retained.value = remainingRetained;
  }

  function markHistorical(editorId: string, executionId?: string): void {
    const execution = executionFor(editorId, executionId);
    if (!execution) return;
    replaceExecution(editorId, { ...execution, busy: false, historical: true,
      results: execution.results.map((result) => ({ ...result, complete: true })) });
  }

  function warnLate(editorId: string, resultIndex: number): void {
    if (import.meta.env.DEV) console.warn(`Ignoring late query rows for ${editorId}/${resultIndex}`);
  }

  function normalizeResult(result: QueryResult): QueryResult {
    const rows = Array.isArray(result.rows) ? viewFor(snapshotFor(result.rows)) : rowsView<Array<string | null>>([]);
    const rowIds = result.rowIds && result.rowIds.length === rows.length
      ? viewFor(snapshotFor(result.rowIds))
      : rowsView(Array.from({ length: rows.length }, () => crypto.randomUUID()));
    return { ...result, rows, rowIds };
  }

  function removeExecution(editorId: string, executionId: string): void {
    const current = executions.value[editorId];
    if (current?.executionId === executionId) {
      const previous = retained.value[editorId] ?? [];
      const next = previous[previous.length - 1];
      const remaining = previous.slice(0, -1);
      const { [editorId]: _removed, ...other } = executions.value;
      executions.value = next ? { ...other, [editorId]: next } : other;
      retained.value = { ...retained.value, [editorId]: remaining };
      return;
    }
    retained.value = { ...retained.value, [editorId]: (retained.value[editorId] ?? [])
      .filter((item) => item.executionId !== executionId) };
  }

  function clear(): void { executions.value = {}; retained.value = {}; }
  return { executions, start, addResult, appendRows, completeResult, updateCells, appendDraftRow,
    removeRowById, applyResultPatches, replaceResultSnapshot, applyColumnRemarks,
    complete, markHistorical, clearEditor, clear, execution, executionList, removeExecution };
});
