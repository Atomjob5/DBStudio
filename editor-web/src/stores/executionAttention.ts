import { defineStore } from "pinia";
import { ref } from "vue";

export type ExecutionAttentionOutcome = "success" | "error";

/** Session-only execution outcomes that have not yet been seen in their editor. */
export const useExecutionAttentionStore = defineStore("execution-attention", () => {
  const unread = ref<Record<string, Record<string, ExecutionAttentionOutcome>>>({});

  function markUnread(editorId: string, executionId: string, outcome: ExecutionAttentionOutcome): void {
    if (!editorId || !executionId) return;
    const current = unread.value[editorId] ?? {};
    unread.value = { ...unread.value, [editorId]: { ...current, [executionId]: outcome } };
  }

  function clearExecution(editorId: string, executionId: string): void {
    const current = unread.value[editorId];
    if (!current || !(executionId in current)) return;
    const { [executionId]: _removed, ...remaining } = current;
    const next = { ...unread.value };
    if (Object.keys(remaining).length) next[editorId] = remaining;
    else delete next[editorId];
    unread.value = next;
  }

  function markViewed(editorId: string): void {
    if (!(editorId in unread.value)) return;
    const { [editorId]: _removed, ...remaining } = unread.value;
    unread.value = remaining;
  }

  function outcome(editorId: string): ExecutionAttentionOutcome | undefined {
    const values = Object.values(unread.value[editorId] ?? {});
    if (values.includes("error")) return "error";
    if (values.includes("success")) return "success";
    return undefined;
  }

  function clearEditor(editorId: string): void {
    markViewed(editorId);
  }

  function clear(): void {
    unread.value = {};
  }

  return { unread, markUnread, clearExecution, markViewed, outcome, clearEditor, clear };
});
