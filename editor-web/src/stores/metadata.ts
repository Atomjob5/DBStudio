import { computed, ref } from "vue";
import { defineStore } from "pinia";
import type { MetadataNode, Suggestion } from "../types";

interface MetadataCache { roots: MetadataNode[]; suggestions: Suggestion[]; }

export const useMetadataStore = defineStore("metadata", () => {
  const activeKey = ref("unbound");
  const caches = ref<Record<string, MetadataCache>>({});
  const active = computed(() => cache(activeKey.value));
  const roots = computed({ get: () => active.value.roots, set: (value) => { active.value.roots = value; } });
  const suggestions = computed(() => active.value.suggestions);

  function activate(key?: string): void { activeKey.value = key || "unbound"; }
  function cache(key: string): MetadataCache {
    if (!caches.value[key]) caches.value[key] = { roots: [], suggestions: [] };
    return caches.value[key];
  }

  function remember(nodes: MetadataNode[], key = activeKey.value): void {
    const target = cache(key);
    const known = new Set(target.suggestions.map((item) => `${item.kind}:${item.label}`));
    for (const node of nodes) {
      if (!node.name || !["object", "column"].includes(node.kind)) continue;
      const kind: Suggestion["kind"] = node.kind === "column"
        ? "column"
        : node.objectType === "FUNCTION" || node.objectType === "PROCEDURE" ? "function" : "table";
      const identity = `${kind}:${node.name}`;
      if (!known.has(identity)) {
        target.suggestions.push({ label: node.name, insertText: `\`${node.name.replaceAll("`", "``")}\``, detail: node.detail ?? node.label, kind });
        known.add(identity);
      }
    }
  }

  function addSuggestions(values: Suggestion[], key = activeKey.value): void {
    const target = cache(key);
    const known = new Set(target.suggestions.map((item) => `${item.kind}:${item.label}`));
    for (const value of values) {
      const identity = `${value.kind}:${value.label}`;
      if (!known.has(identity)) { target.suggestions.push(value); known.add(identity); }
    }
  }

  function clear(key = activeKey.value): void { delete caches.value[key]; }
  function clearAll(): void { caches.value = {}; }

  return { activeKey, roots, suggestions, activate, remember, addSuggestions, clear, clearAll };
});
