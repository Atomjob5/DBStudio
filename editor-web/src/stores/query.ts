import { defineStore } from "pinia";
import { shallowRef, triggerRef } from "vue";
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
    executions.value[editorId]?.results.push(result);
    triggerRef(executions);
  }

  function appendRows(editorId: string, resultIndex: number, rows: Array<Array<string | null>>): void {
    const result = executions.value[editorId]?.results.find((item) => item.resultIndex === resultIndex);
    if (result) {
      result.rows.push(...rows);
      triggerRef(executions);
    }
  }

  function completeResult(editorId: string, resultIndex: number, values: Partial<QueryResult>): void {
    const result = executions.value[editorId]?.results.find((item) => item.resultIndex === resultIndex);
    if (result) {
      Object.assign(result, values, { complete: true });
      triggerRef(executions);
    }
  }

  function complete(editorId: string, values: Partial<QueryExecutionState>): void {
    const execution = executions.value[editorId];
    if (execution) {
      Object.assign(execution, values, { busy: false });
      triggerRef(executions);
    }
  }

  function clear(): void { executions.value = {}; }
  return { executions, start, addResult, appendRows, completeResult, complete, clear };
});
