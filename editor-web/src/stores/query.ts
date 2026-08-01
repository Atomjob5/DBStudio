import { defineStore } from "pinia";
import { shallowRef } from "vue";
import type { QueryExecutionState, QueryResult } from "../types";

export const useQueryStore = defineStore("query", () => {
  // Result cells remain plain arrays. Vue observes only batch-level mutations.
  const executions = shallowRef<Record<string, QueryExecutionState>>({});

  function start(editorId: string, executionId: string): void {
    if (executions.value[editorId]?.executionId === executionId) return;
    executions.value = { ...executions.value, [editorId]: {
      executionId, editorId, results: [], busy: true, cancelled: false, failed: false, durationMs: 0
    } };
  }

  function addResult(editorId: string, result: QueryResult): void {
    const execution = executions.value[editorId];
    if (!execution) {
      if (import.meta.env.DEV) console.warn(`Ignoring late query result for ${editorId}`);
      return;
    }
    replaceExecution(editorId, { ...execution, results: [...execution.results, result] });
  }

  function appendRows(editorId: string, resultIndex: number, rows: Array<Array<string | null>>,
                      rowIds: string[] = []): void {
    const execution = executions.value[editorId];
    const result = execution?.results.find((item) => item.resultIndex === resultIndex);
    if (!execution || !result) { warnLate(editorId, resultIndex); return; }
    const ids = rowIds.length === rows.length ? rowIds
      : rows.map(() => crypto.randomUUID());
    const updated = { ...result, rows: [...result.rows, ...rows], rowIds: [...(result.rowIds ?? []), ...ids] };
    replaceExecution(editorId, { ...execution, results: execution.results.map((item) => item === result ? updated : item) });
  }

  function completeResult(editorId: string, resultIndex: number, values: Partial<QueryResult>): void {
    const execution = executions.value[editorId];
    const result = execution?.results.find((item) => item.resultIndex === resultIndex);
    if (!execution || !result) { warnLate(editorId, resultIndex); return; }
    const updated = { ...result, ...values, complete: true };
    replaceExecution(editorId, { ...execution, results: execution.results.map((item) => item === result ? updated : item) });
  }

  function updateCells(editorId: string, resultIndex: number,
                       cells: Array<{ rowIndex: number; columnIndex: number; value: string | null }>): void {
    const execution = executions.value[editorId];
    const result = execution?.results.find((item) => item.resultIndex === resultIndex);
    if (!execution || !result || !cells.length) return;
    const rows = [...result.rows];
    const copied = new Map<number, Array<string | null>>();
    for (const cell of cells) {
      if (cell.rowIndex < 0 || cell.rowIndex >= rows.length
          || cell.columnIndex < 0 || cell.columnIndex >= rows[cell.rowIndex].length) continue;
      let row = copied.get(cell.rowIndex);
      if (!row) {
        row = [...rows[cell.rowIndex]];
        copied.set(cell.rowIndex, row);
        rows[cell.rowIndex] = row;
      }
      row[cell.columnIndex] = cell.value;
    }
    const updated = { ...result, rows };
    replaceExecution(editorId, { ...execution,
      results: execution.results.map((item) => item === result ? updated : item) });
  }

  function appendDraftRow(editorId: string, resultIndex: number, rowId: string,
                          row: Array<string | null>): void {
    const execution = executions.value[editorId];
    const result = execution?.results.find((item) => item.resultIndex === resultIndex);
    if (!execution || !result) return;
    const updated = { ...result, rows: [...result.rows, row], rowIds: [...(result.rowIds ?? []), rowId] };
    replaceExecution(editorId, { ...execution,
      results: execution.results.map((item) => item === result ? updated : item) });
  }

  function removeRowById(editorId: string, resultIndex: number, rowId: string): void {
    const execution = executions.value[editorId];
    const result = execution?.results.find((item) => item.resultIndex === resultIndex);
    if (!execution || !result) return;
    const index = (result.rowIds ?? []).indexOf(rowId);
    if (index < 0) return;
    const updated = { ...result, rows: result.rows.filter((_, rowIndex) => rowIndex !== index),
      rowIds: (result.rowIds ?? []).filter((_, rowIndex) => rowIndex !== index) };
    replaceExecution(editorId, { ...execution,
      results: execution.results.map((item) => item === result ? updated : item) });
  }

  function applyResultPatches(editorId: string, resultIndex: number,
                              patches: Array<{ kind: "update" | "insert" | "delete"; rowId: string;
                                rowIndex: number; row: Array<string | null>; clientRowId?: string }>): void {
    const execution = executions.value[editorId];
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
    const updated = { ...result, rows, rowIds };
    replaceExecution(editorId, { ...execution,
      results: execution.results.map((item) => item === result ? updated : item) });
  }

  function replaceResultSnapshot(editorId: string, resultIndex: number,
                                 rows: Array<Array<string | null>>, rowIds: string[]): void {
    const execution = executions.value[editorId];
    const result = execution?.results.find((item) => item.resultIndex === resultIndex);
    if (!execution || !result) return;
    const updated = { ...result, rows: rows.map((row) => [...row]), rowIds: [...rowIds] };
    replaceExecution(editorId, { ...execution,
      results: execution.results.map((item) => item === result ? updated : item) });
  }

  function applyColumnRemarks(editorId: string, executionId: string, resultIndex: number,
                              remarks: Array<{ index: number; remarks: string }>): void {
    const execution = executions.value[editorId];
    const result = execution?.results.find((item) => item.resultIndex === resultIndex);
    if (!execution || execution.executionId !== executionId || !result?.columnDetails || !remarks.length) return;
    const byIndex = new Map(remarks.map((item) => [item.index, item.remarks]));
    const columnDetails = result.columnDetails.map((column, index) => column.remarks || !byIndex.get(index)
      ? column : { ...column, remarks: byIndex.get(index) as string });
    const updated = { ...result, columnDetails };
    replaceExecution(editorId, { ...execution, results: execution.results.map((item) => item === result ? updated : item) });
  }

  function complete(editorId: string, values: Partial<QueryExecutionState>): void {
    const execution = executions.value[editorId];
    if (!execution) { if (import.meta.env.DEV) console.warn(`Ignoring late query completion for ${editorId}`); return; }
    replaceExecution(editorId, { ...execution, ...values, busy: false });
  }

  function replaceExecution(editorId: string, execution: QueryExecutionState): void {
    executions.value = { ...executions.value, [editorId]: execution };
  }

  function clearEditor(editorId: string): void {
    const { [editorId]: _removed, ...remaining } = executions.value;
    executions.value = remaining;
  }

  function markHistorical(editorId: string): void {
    const execution = executions.value[editorId];
    if (!execution) return;
    replaceExecution(editorId, { ...execution, busy: false, historical: true,
      results: execution.results.map((result) => ({ ...result, complete: true })) });
  }

  function warnLate(editorId: string, resultIndex: number): void {
    if (import.meta.env.DEV) console.warn(`Ignoring late query rows for ${editorId}/${resultIndex}`);
  }

  function clear(): void { executions.value = {}; }
  return { executions, start, addResult, appendRows, completeResult, updateCells, appendDraftRow,
    removeRowById, applyResultPatches, replaceResultSnapshot, applyColumnRemarks,
    complete, markHistorical, clearEditor, clear };
});
