import { normalizeExecutionWarningMinutes } from "./executionWarningSettings";
import { executionDurationText } from "./executionDuration";

export type ExecutionNotificationType = "success" | "error" | "info" | "warning";

export interface ExecutionNotificationRequest {
  title: string;
  message: string;
  type: ExecutionNotificationType;
  duration: 0;
  position: "top-right";
  showClose: true;
  onClick: () => void;
}

export interface ExecutionNotificationHandle {
  close(): void;
}

export interface ExecutionNotificationSchedulerOptions {
  now?: () => number;
  setTimeout?: (callback: () => void, delayMs: number) => number;
  clearTimeout?: (timer: number) => void;
  getThresholds: () => readonly number[];
  isBackground: (editorId: string) => boolean;
  isRunning: (editorId: string, executionId: string) => boolean;
  getEditorTitle: (editorId: string) => string | undefined;
  notify: (request: ExecutionNotificationRequest) => ExecutionNotificationHandle;
  openExecution: (editorId: string, executionId: string) => void;
}

export interface ExecutionStartedInput {
  editorId: string;
  executionId: string;
  startedAt?: number;
  editorTitle?: string;
}

export interface ExecutionCompletedInput {
  editorId: string;
  executionId: string;
  cancelled: boolean;
  failed: boolean;
  durationMs?: number;
  terminationReason?: string;
}

interface ExecutionRecord {
  editorId: string;
  executionId: string;
  startedAt: number;
  editorTitle?: string;
  consumedThresholds: Set<number>;
  configuredThresholds: Set<number>;
  timers: Map<number, number>;
}

const MINUTE_MS = 60_000;

export function createExecutionNotificationScheduler(options: ExecutionNotificationSchedulerOptions) {
  const now = options.now ?? (() => Date.now());
  const setTimeout = options.setTimeout ?? ((callback, delayMs) => window.setTimeout(callback, delayMs));
  const clearTimeout = options.clearTimeout ?? ((timer) => window.clearTimeout(timer));
  const records = new Map<string, ExecutionRecord>();

  function thresholds(): number[] {
    return normalizeExecutionWarningMinutes(options.getThresholds());
  }

  function clearTimers(record: ExecutionRecord): void {
    record.timers.forEach((timer) => clearTimeout(timer));
    record.timers.clear();
  }

  function titleFor(record: ExecutionRecord): string {
    return options.getEditorTitle(record.editorId) ?? record.editorTitle ?? "SQL编辑器";
  }

  function openAndClose(record: ExecutionRecord, request: Omit<ExecutionNotificationRequest, "onClick">): void {
    let handle: ExecutionNotificationHandle | undefined;
    let closeRequested = false;
    const close = (): void => {
      closeRequested = true;
      handle?.close();
    };
    handle = options.notify({ ...request, onClick: () => {
      try {
        options.openExecution(record.editorId, record.executionId);
      } finally {
        close();
      }
    }});
    if (closeRequested) handle.close();
  }

  function notifyTimeout(record: ExecutionRecord): void {
    const elapsed = Math.max(0, now() - record.startedAt);
    openAndClose(record, {
      title: `[${titleFor(record)}]执行超时`,
      message: `耗时 ${executionDurationText(elapsed)}，SQL 仍在执行，点击查看`,
      type: "warning", duration: 0, position: "top-right", showClose: true
    });
  }

  function notifyCompletion(record: ExecutionRecord, input: ExecutionCompletedInput): void {
    const duration = Number.isFinite(input.durationMs) && (input.durationMs ?? 0) >= 0
      ? input.durationMs as number : Math.max(0, now() - record.startedAt);
    const cancelled = input.cancelled;
    const failed = input.failed || input.terminationReason === "connection-aborted";
    const status = cancelled ? "执行已取消" : failed ? "执行失败" : "执行成功";
    const type: ExecutionNotificationType = cancelled ? "info" : failed ? "error" : "success";
    openAndClose(record, {
      title: `[${titleFor(record)}]${status}`,
      message: `耗时 ${executionDurationText(duration)}，点击查看`,
      type, duration: 0, position: "top-right", showClose: true
    });
  }

  function thresholdReached(record: ExecutionRecord, threshold: number): void {
    record.timers.delete(threshold);
    if (!records.has(record.executionId) || record.consumedThresholds.has(threshold)) return;
    record.consumedThresholds.add(threshold);
    if (!options.isRunning(record.editorId, record.executionId) || !options.isBackground(record.editorId)) return;
    notifyTimeout(record);
  }

  function schedule(record: ExecutionRecord): void {
    clearTimers(record);
    const configured = new Set(thresholds());
    const previouslyConfigured = record.configuredThresholds;
    record.configuredThresholds = configured;
    const elapsed = Math.max(0, now() - record.startedAt);
    for (const threshold of configured) {
      if (record.consumedThresholds.has(threshold)) continue;
      const dueMs = threshold * MINUTE_MS;
      if (dueMs <= elapsed) {
        if (previouslyConfigured.has(threshold)) thresholdReached(record, threshold);
        else record.consumedThresholds.add(threshold);
        continue;
      }
      const timer = setTimeout(() => thresholdReached(record, threshold), dueMs - elapsed);
      record.timers.set(threshold, timer);
    }
  }

  function start(input: ExecutionStartedInput): void {
    if (records.has(input.executionId)) return;
    const record: ExecutionRecord = {
      editorId: input.editorId,
      executionId: input.executionId,
      startedAt: Number.isFinite(input.startedAt) ? input.startedAt as number : now(),
      editorTitle: input.editorTitle,
      consumedThresholds: new Set(),
      configuredThresholds: new Set(),
      timers: new Map()
    };
    records.set(record.executionId, record);
    schedule(record);
  }

  function complete(input: ExecutionCompletedInput): boolean {
    const record = records.get(input.executionId);
    if (!record) return false;
    clearTimers(record);
    records.delete(input.executionId);
    if (options.isBackground(record.editorId)) notifyCompletion(record, input);
    return true;
  }

  function abort(editorId: string, executionId: string, terminationReason = "connection-aborted"): boolean {
    const record = records.get(executionId);
    if (!record || record.editorId !== editorId) return false;
    return complete({ editorId, executionId, cancelled: false, failed: true,
      durationMs: Math.max(0, now() - record.startedAt), terminationReason });
  }

  function clearExecution(executionId: string): void {
    const record = records.get(executionId);
    if (!record) return;
    clearTimers(record);
    records.delete(executionId);
  }

  function clear(): void {
    records.forEach(clearTimers);
    records.clear();
  }

  function reschedule(): void {
    records.forEach(schedule);
  }

  return { start, complete, abort, clearExecution, clear, reschedule,
    has: (executionId: string): boolean => records.has(executionId), size: () => records.size };
}
