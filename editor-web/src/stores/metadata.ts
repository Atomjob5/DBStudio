import { defineStore } from "pinia";
import { ref } from "vue";
import type { MetadataNode, Suggestion } from "../types";

export const useMetadataStore = defineStore("metadata", () => {
  const roots = ref<MetadataNode[]>([]);
  const suggestions = ref<Suggestion[]>([]);

  function remember(nodes: MetadataNode[]): void {
    const known = new Set(suggestions.value.map((item) => `${item.kind}:${item.label}`));
    for (const node of nodes) {
      if (!node.name || !["object", "column"].includes(node.kind)) continue;
      const kind: Suggestion["kind"] = node.kind === "column"
        ? "column"
        : node.objectType === "FUNCTION" || node.objectType === "PROCEDURE" ? "function" : "table";
      const key = `${kind}:${node.name}`;
      if (!known.has(key)) {
        suggestions.value.push({ label: node.name, insertText: `\`${node.name.replaceAll("`", "``")}\``, detail: node.detail ?? node.label, kind });
        known.add(key);
      }
    }
  }

  function addSuggestions(values: Suggestion[]): void {
    const known = new Set(suggestions.value.map((item) => `${item.kind}:${item.label}`));
    for (const value of values) {
      const key = `${value.kind}:${value.label}`;
      if (!known.has(key)) { suggestions.value.push(value); known.add(key); }
    }
  }

  function clear(): void {
    roots.value = [];
    suggestions.value = [];
  }

  return { roots, suggestions, remember, addSuggestions, clear };
});
