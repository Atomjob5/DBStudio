import { computed, shallowRef } from "vue";
import { defineStore } from "pinia";
import type { CompletionCache, CompletionCacheStats, CompletionCacheSummary, CompletionProgress, MetadataNode } from "../types";

export const TREE_ROOT_KEY = "__root__";

type TreeCache = { children: Record<string, MetadataNode[]> };

export const useMetadataStore = defineStore("metadata", () => {
  const activeTreeKey = shallowRef("unbound");
  const activeCompletionKey = shallowRef("unbound");
  const treeCaches = shallowRef<Record<string, TreeCache>>({});
  const treeLoads = new Map<string, Promise<MetadataNode[]>>();
  const treeGenerations = new Map<string, number>();
  let treeEpoch = 0;
  const completionCaches = shallowRef<Record<string, CompletionCache>>({});
  const persistentStats = shallowRef<Omit<CompletionCacheStats, "loadingCount">>({
    environmentCount: 0, suggestionCount: 0, estimatedBytes: 0
  });
  const roots = computed({
    get: () => treeCaches.value[activeTreeKey.value]?.children[TREE_ROOT_KEY] ?? [],
    set: (value: MetadataNode[]) => setRoots(value, activeTreeKey.value)
  });
  const completionStats = computed<CompletionCacheStats>(() => ({
    ...persistentStats.value,
    loadingCount: Object.values(completionCaches.value).filter((cache) => cache.state === "loading").length
  }));
  const hasTreeCaches = computed(() => Object.values(treeCaches.value)
    .some((cache) => Object.keys(cache.children).length > 0));
  const canClearCompletions = computed(() => hasTreeCaches.value
    || persistentStats.value.environmentCount > 0
    || Object.keys(completionCaches.value).length > 0);

  function activate(treeKey?: string, completionKey?: string): void {
    activeTreeKey.value = treeKey || "unbound";
    activeCompletionKey.value = completionKey || "unbound";
  }

  function setRoots(values: MetadataNode[], key = activeTreeKey.value): void {
    setTreeChildren(key, TREE_ROOT_KEY, values);
  }

  function clearTree(key = activeTreeKey.value): void {
    treeGenerations.set(key, (treeGenerations.get(key) ?? 0) + 1);
    const next = { ...treeCaches.value };
    delete next[key];
    treeCaches.value = next;
    clearTreeLoads(key);
  }

  function treeChildren(key: string, parentKey = TREE_ROOT_KEY): MetadataNode[] | undefined {
    return treeCaches.value[key]?.children[parentKey];
  }

  function hasTreeChildren(key: string, parentKey = TREE_ROOT_KEY): boolean {
    return treeChildren(key, parentKey) !== undefined;
  }

  function setTreeChildren(key: string, parentKey: string, values: MetadataNode[]): void {
    const current = treeCaches.value[key];
    treeCaches.value = {
      ...treeCaches.value,
      [key]: {
        children: { ...(current?.children ?? {}), [parentKey]: values }
      }
    };
  }

  function treeVersion(key: string): string {
    return `${treeEpoch}:${treeGenerations.get(key) ?? 0}`;
  }

  function treeLoadKey(key: string, parentKey: string): string {
    return `${key}\u0000${parentKey}`;
  }

  function clearTreeLoads(key: string): void {
    const prefix = `${key}\u0000`;
    for (const loadKey of treeLoads.keys()) {
      if (loadKey.startsWith(prefix)) treeLoads.delete(loadKey);
    }
  }

  function loadTreeChildren(key: string, parentKey: string, loader: () => Promise<MetadataNode[]>): Promise<MetadataNode[]> {
    const cached = treeChildren(key, parentKey);
    if (cached !== undefined) return Promise.resolve(cached);
    const loadKey = treeLoadKey(key, parentKey);
    const existing = treeLoads.get(loadKey);
    if (existing) return existing;
    const version = treeVersion(key);
    const task = Promise.resolve().then(loader).then((values) => {
      if (treeVersion(key) === version) setTreeChildren(key, parentKey, values);
      return values;
    }).finally(() => {
      if (treeLoads.get(loadKey) === task) treeLoads.delete(loadKey);
    });
    treeLoads.set(loadKey, task);
    return task;
  }

  function clearTreeCaches(): void {
    treeEpoch++;
    treeGenerations.clear();
    treeCaches.value = {};
    treeLoads.clear();
  }

  function completionFor(key?: string): CompletionCache | undefined {
    return key ? completionCaches.value[key] : undefined;
  }

  function readyFromCache(key: string, label: string, summary: CompletionCacheSummary): void {
    const current = completionCaches.value[key];
    if (current?.state === "loading") return;
    completionCaches.value = { ...completionCaches.value, [key]: {
      key, label, state: "ready", hasSnapshot: true, summary,
      sourceProfileId: summary.sourceProfileId, generatedAt: summary.generatedAt
    } };
  }

  function beginCompletion(key: string, label: string, loadId: string, sourceProfileId: string, force = false): boolean {
    const current = completionCaches.value[key];
    if (current?.state === "loading") return false;
    if (!force && current?.hasSnapshot) return false;
    completionCaches.value = { ...completionCaches.value, [key]: {
      key, label, state: "loading", hasSnapshot: current?.hasSnapshot ?? false,
      summary: current?.summary, loadId, sourceProfileId, generatedAt: current?.generatedAt,
      notice: "loading", startedAt: Date.now()
    } };
    return true;
  }

  function updateProgress(progress: CompletionProgress): void {
    const entry = Object.values(completionCaches.value).find((item) => item.loadId === progress.loadId);
    if (!entry) return;
    completionCaches.value = { ...completionCaches.value, [entry.key]: { ...entry, progress } };
  }

  function completeCompletion(key: string, loadId: string, summary: CompletionCacheSummary): boolean {
    const current = completionCaches.value[key];
    if (!current || current.loadId !== loadId) return false;
    completionCaches.value = { ...completionCaches.value, [key]: {
      ...current, state: "ready", hasSnapshot: true, summary, loadId: undefined,
      sourceProfileId: summary.sourceProfileId, generatedAt: summary.generatedAt,
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

  function applyPersistentStats(stats: Omit<CompletionCacheStats, "loadingCount">): void {
    persistentStats.value = stats;
  }

  function dismissNotice(key: string): void {
    const current = completionCaches.value[key];
    if (!current?.notice) return;
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
    clearTreeCaches();
    completionCaches.value = {};
    persistentStats.value = { environmentCount: 0, suggestionCount: 0, estimatedBytes: 0 };
  }

  function clearCompletions(): CompletionCacheStats {
    const released = { ...completionStats.value };
    completionCaches.value = {};
    persistentStats.value = { environmentCount: 0, suggestionCount: 0, estimatedBytes: 0 };
    return released;
  }

  return { activeTreeKey, activeCompletionKey, roots, completionCaches, completionStats, canClearCompletions, hasTreeCaches,
    activate, setRoots, treeChildren, hasTreeChildren, setTreeChildren, loadTreeChildren, clearTree, clearTreeCaches,
    completionFor, readyFromCache, beginCompletion, updateProgress,
    completeCompletion, failCompletion, applyPersistentStats, dismissNotice, statusFor, clearCompletions, clearAll };
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
