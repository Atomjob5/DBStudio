import { computed, shallowRef } from "vue";
import { defineStore } from "pinia";
import type { CompletionCache, CompletionCacheStats, CompletionProgress, CompletionSnapshot, MetadataNode } from "../types";

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
  const completionStats = computed<CompletionCacheStats>(() => Object.values(completionCaches.value).reduce((stats, cache) => {
    if (cache.state === "loading") stats.loadingCount += 1;
    if (!cache.hasSnapshot) return stats;
    stats.environmentCount += 1;
    stats.suggestionCount += cache.suggestions.length;
    stats.estimatedBytes += serializedUtf8Size(cache.suggestions);
    return stats;
  }, { environmentCount: 0, suggestionCount: 0, estimatedBytes: 0, loadingCount: 0 }));
  const canClearCompletions = computed(() => Object.keys(completionCaches.value).length > 0);

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

  function clearCompletions(): CompletionCacheStats {
    const released = { ...completionStats.value };
    completionCaches.value = {};
    return released;
  }

  return { activeTreeKey, activeCompletionKey, roots, suggestions, completionCaches, completionStats, canClearCompletions,
    activate, setRoots, clearTree, completionFor, beginCompletion, updateProgress,
    completeCompletion, failCompletion, dismissNotice, statusFor, clearCompletions, clearAll };
});

export function serializedUtf8Size(value: unknown): number {
  const serialized = JSON.stringify(value);
  return new TextEncoder().encode(serialized).byteLength;
}

export function formatCompletionBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return "0 B";
  const units = ["B", "KB", "MB", "GB"];
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  const value = bytes / 1024 ** index;
  const digits = index === 0 || value >= 10 ? 0 : 1;
  return `${value.toFixed(digits)} ${units[index]}`;
}
