import { defineStore } from "pinia";
import { computed, ref } from "vue";
import type { EditorTab } from "../types";

export const useEditorStore = defineStore("editor", () => {
  const tabs = ref<EditorTab[]>([]);
  const activeId = ref("");
  const active = computed(() => tabs.value.find((tab) => tab.id === activeId.value));

  function add(tab: EditorTab): void {
    tabs.value.push(tab);
    activeId.value = tab.id;
  }

  function remove(id: string): void {
    const index = tabs.value.findIndex((tab) => tab.id === id);
    if (index < 0) return;
    tabs.value.splice(index, 1);
    if (activeId.value === id) activeId.value = tabs.value[Math.max(0, index - 1)]?.id ?? "";
  }

  function patch(id: string, values: Partial<EditorTab>): void {
    const tab = tabs.value.find((item) => item.id === id);
    if (tab) Object.assign(tab, values);
  }

  function clear(): void {
    tabs.value = [];
    activeId.value = "";
  }

  return { tabs, activeId, active, add, remove, patch, clear };
});
