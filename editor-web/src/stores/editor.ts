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

  function move(id: string, targetIndex: number): boolean {
    const sourceIndex = tabs.value.findIndex((item) => item.id === id);
    if (sourceIndex < 0) return false;
    const index = Math.max(0, Math.min(targetIndex, tabs.value.length - 1));
    if (sourceIndex === index) return false;
    const [tab] = tabs.value.splice(sourceIndex, 1);
    tabs.value.splice(index, 0, tab);
    return true;
  }

  function clear(): void {
    tabs.value = [];
    activeId.value = "";
  }

  return { tabs, activeId, active, add, remove, move, patch, clear };
});
