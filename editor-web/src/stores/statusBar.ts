import { defineStore } from "pinia";
import { shallowRef } from "vue";
import type { StatusBarTask } from "../types";

const SUCCESS_RETENTION_MS = 3_000;

export const useStatusBarStore = defineStore("statusBar", () => {
  const tasks = shallowRef<Record<string, StatusBarTask>>({});
  const removalTimers = new Map<string, number>();

  function start(id: string, kind: string, message?: string): void {
    clearRemoval(id);
    const now = Date.now();
    const current = tasks.value[id];
    tasks.value = { ...tasks.value, [id]: {
      id, kind, label: taskLabel(kind), message: message || taskStartMessage(kind), state: "running",
      startedAt: current?.startedAt ?? now, updatedAt: now
    } };
  }

  function progress(id: string, kind: string, message: string, completed?: number, total?: number): void {
    const current = tasks.value[id];
    if (!current) start(id, kind, message);
    const value = tasks.value[id];
    tasks.value = { ...tasks.value, [id]: { ...value, message: message || value.message,
      completed, total, updatedAt: Date.now() } };
  }

  function complete(id: string, kind: string, error?: string, message?: string): void {
    const current = tasks.value[id];
    if (!current) start(id, kind);
    const value = tasks.value[id];
    const failed = Boolean(error);
    tasks.value = { ...tasks.value, [id]: { ...value,
      state: failed ? "error" : "success",
      message: error || message || (failed ? "后台任务失败" : `${value.label}已完成`),
      dismissible: failed, updatedAt: Date.now()
    } };
    if (!failed) removalTimers.set(id, window.setTimeout(() => dismiss(id), SUCCESS_RETENTION_MS));
  }

  function dismiss(id: string): void {
    clearRemoval(id);
    const { [id]: _removed, ...remaining } = tasks.value;
    tasks.value = remaining;
  }

  function clear(): void {
    removalTimers.forEach((timer) => window.clearTimeout(timer));
    removalTimers.clear();
    tasks.value = {};
  }

  function clearRemoval(id: string): void {
    const timer = removalTimers.get(id);
    if (timer !== undefined) window.clearTimeout(timer);
    removalTimers.delete(id);
  }

  return { tasks, start, progress, complete, dismiss, clear };
});

function taskLabel(kind: string): string {
  if (kind === "csv.import") return "数据导入";
  if (kind === "csv.export") return "数据导出";
  return "后台任务";
}

function taskStartMessage(kind: string): string {
  return kind === "csv.import" ? "正在导入数据…" : kind === "csv.export" ? "正在导出数据…" : "正在处理…";
}
