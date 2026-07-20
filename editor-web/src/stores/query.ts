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

  function appendRows(editorId: string, resultIndex: number, rows: Array<Array<string | null>>): void {
    const execution = executions.value[editorId];
    const result = execution?.results.find((item) => item.resultIndex === resultIndex);
    if (!execution || !result) { warnLate(editorId, resultIndex); return; }
    const updated = { ...result, rows: [...result.rows, ...rows] };
    replaceExecution(editorId, { ...execution, results: execution.results.map((item) => item === result ? updated : item) });
  }

  function completeResult(editorId: string, resultIndex: number, values: Partial<QueryResult>): void {
    const execution = executions.value[editorId];
    const result = execution?.results.find((item) => item.resultIndex === resultIndex);
    if (!execution || !result) { warnLate(editorId, resultIndex); return; }
    const updated = { ...result, ...values, complete: true };
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
  return { executions, start, addResult, appendRows, completeResult, complete, markHistorical, clearEditor, clear };
});
