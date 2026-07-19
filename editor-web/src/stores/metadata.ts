import { computed, shallowRef } from "vue";
import { defineStore } from "pinia";
import type { CompletionCache, CompletionProgress, CompletionSnapshot, MetadataNode } from "../types";

const emptySuggestions: CompletionCache["suggestions"] = [];

export const useMetadataStore = defineStore("metadata", () => {
  const activeTreeKey = shallowRef("unbound");
  const activeCompletionKey = shallowRef("unbound");
  const treeCaches = shallowRef<Record<string, MetadataNode[]>>({});
  const completionCaches = shallowRef<Record<string, CompletionCache>>({});
  const roots = computed({
    get: () => treeCaches.value[activeTreeKey.value] ?? [],
    set: (value: MetadataNode[]) => setRoots(value, activeTreeKey.value)
  });
  const suggestions = computed(() => completionCaches.value[activeCompletionKey.value]?.suggestions ?? emptySuggestions);

  function activate(treeKey?: string, completionKey?: string): void {
    activeTreeKey.value = treeKey || "unbound";
    activeCompletionKey.value = completionKey || "unbound";
  }

  function setRoots(values: MetadataNode[], key = activeTreeKey.value): void {
    treeCaches.value = { ...treeCaches.value, [key]: values };
  }

  function clearTree(key = activeTreeKey.value): void {
    const next = { ...treeCaches.value };
    delete next[key];
    treeCaches.value = next;
  }

  function completionFor(key?: string): CompletionCache | undefined {
    return key ? completionCaches.value[key] : undefined;
  }

  function beginCompletion(key: string, label: string, loadId: string, sourceProfileId: string, force = false): boolean {
    const current = completionCaches.value[key];
    if (current?.state === "loading") return false;
    if (!force && current?.hasSnapshot) return false;
    completionCaches.value = { ...completionCaches.value, [key]: {
      key, label, state: "loading", suggestions: current?.suggestions ?? [],
      hasSnapshot: current?.hasSnapshot ?? false, loadId, sourceProfileId,
      generatedAt: current?.generatedAt, notice: "loading", startedAt: Date.now()
    } };
    return true;
  }

  function updateProgress(progress: CompletionProgress): void {
    const entry = Object.values(completionCaches.value).find((item) => item.loadId === progress.loadId);
    if (!entry) return;
    completionCaches.value = { ...completionCaches.value, [entry.key]: { ...entry, progress } };
  }

  function completeCompletion(key: string, loadId: string, snapshot: CompletionSnapshot): boolean {
    const current = completionCaches.value[key];
    if (!current || current.loadId !== loadId) return false;
    completionCaches.value = { ...completionCaches.value, [key]: {
      ...current, state: "ready", suggestions: [...snapshot.suggestions], hasSnapshot: true,
      loadId: undefined, sourceProfileId: snapshot.sourceProfileId, generatedAt: snapshot.generatedAt,
      progress: undefined, error: undefined, notice: "success"
    } };
    return true;
  }

  function failCompletion(key: string, loadId: string, error: string): boolean {
    const current = completionCaches.value[key];
    if (!current || current.loadId !== loadId) return false;
    completionCaches.value = { ...completionCaches.value, [key]: {
      ...current, state: "error", loadId: undefined, progress: undefined, error, notice: "error"
    } };
    return true;
  }

  function dismissNotice(key: string): void {
    const current = completionCaches.value[key];
    if (!current || current.notice !== "success") return;
    completionCaches.value = { ...completionCaches.value, [key]: { ...current, notice: undefined } };
  }

  function statusFor(preferredKey?: string): CompletionCache | undefined {
    const preferred = preferredKey ? completionCaches.value[preferredKey] : undefined;
    if (preferred?.notice) return preferred;
    return Object.values(completionCaches.value)
      .filter((item) => Boolean(item.notice))
      .sort((left, right) => (right.startedAt ?? 0) - (left.startedAt ?? 0))[0];
  }

  function clearAll(): void {
    treeCaches.value = {};
    completionCaches.value = {};
  }

  return { activeTreeKey, activeCompletionKey, roots, suggestions, completionCaches,
    activate, setRoots, clearTree, completionFor, beginCompletion, updateProgress,
    completeCompletion, failCompletion, dismissNotice, statusFor, clearAll };
});
