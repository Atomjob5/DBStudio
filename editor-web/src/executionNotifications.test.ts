import { describe, expect, it, vi } from "vitest";
import { createExecutionNotificationScheduler, type ExecutionNotificationRequest } from "./executionNotifications";

describe("execution notification scheduler", () => {
  it("notifies only for a background completion and closes after opening the execution", () => {
    let currentTime = 1_000;
    let activeEditor = "editor-b";
    const notifications: Array<{ request: ExecutionNotificationRequest; close: ReturnType<typeof vi.fn> }> = [];
    const openExecution = vi.fn();
    const scheduler = createExecutionNotificationScheduler({
      now: () => currentTime,
      getThresholds: () => [],
      isBackground: (editorId) => editorId !== activeEditor,
      isRunning: () => false,
      getEditorTitle: () => "查询 A",
      notify: (request) => {
        const close = vi.fn();
        notifications.push({ request, close });
        return { close };
      },
      openExecution
    });

    scheduler.start({ editorId: "editor-a", executionId: "execution-1", startedAt: currentTime });
    currentTime += 123;
    expect(scheduler.complete({ editorId: "editor-a", executionId: "execution-1", cancelled: false,
      failed: false, durationMs: 123 })).toBe(true);
    expect(notifications).toHaveLength(1);
    expect(notifications[0].request.title).toBe("[查询 A]执行成功");
    expect(notifications[0].request.message).toBe("耗时 123ms，点击查看");
    expect(notifications[0].request.duration).toBe(0);

    notifications[0].request.onClick();
    expect(openExecution).toHaveBeenCalledWith("editor-a", "execution-1");
    expect(notifications[0].close).toHaveBeenCalledTimes(1);

    activeEditor = "editor-a";
    scheduler.start({ editorId: "editor-a", executionId: "execution-2", startedAt: currentTime });
    expect(scheduler.complete({ editorId: "editor-a", executionId: "execution-2", cancelled: false,
      failed: false, durationMs: 1 })).toBe(true);
    expect(notifications).toHaveLength(1);
  });

  it("fires each background threshold once and does not cancel the execution", () => {
    vi.useFakeTimers();
    try {
      let activeEditor = "editor-b";
      let running = true;
      const notifications: ExecutionNotificationRequest[] = [];
      const scheduler = createExecutionNotificationScheduler({
        getThresholds: () => [1, 2],
        isBackground: (editorId) => editorId !== activeEditor,
        isRunning: () => running,
        getEditorTitle: () => "长查询",
        notify: (request) => { notifications.push(request); return { close: vi.fn() }; },
        openExecution: vi.fn()
      });
      scheduler.start({ editorId: "editor-a", executionId: "execution-long", startedAt: Date.now() });
      vi.advanceTimersByTime(60_000);
      expect(notifications).toHaveLength(1);
      expect(notifications[0].title).toBe("[长查询]执行超时");
      expect(notifications[0].message).toContain("SQL 仍在执行");
      vi.advanceTimersByTime(60_000);
      expect(notifications).toHaveLength(2);
      vi.advanceTimersByTime(120_000);
      expect(notifications).toHaveLength(2);

      running = false;
      activeEditor = "editor-a";
      expect(scheduler.complete({ editorId: "editor-a", executionId: "execution-long", cancelled: true,
        failed: false, durationMs: 120_000 })).toBe(true);
      expect(notifications).toHaveLength(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it("reschedules future thresholds without retroactive notifications", () => {
    vi.useFakeTimers();
    try {
      let thresholdMinutes = [1];
      let running = true;
      const notifications: ExecutionNotificationRequest[] = [];
      const scheduler = createExecutionNotificationScheduler({
        getThresholds: () => thresholdMinutes,
        isBackground: () => true,
        isRunning: () => running,
        getEditorTitle: () => "查询",
        notify: (request) => { notifications.push(request); return { close: vi.fn() }; },
        openExecution: vi.fn()
      });
      scheduler.start({ editorId: "editor-a", executionId: "execution-config", startedAt: Date.now() });
      vi.advanceTimersByTime(30_000);
      thresholdMinutes = [2];
      scheduler.reschedule();
      vi.advanceTimersByTime(30_000);
      expect(notifications).toHaveLength(0);
      vi.advanceTimersByTime(60_000);
      expect(notifications).toHaveLength(1);
      running = false;
    } finally {
      vi.useRealTimers();
    }
  });
});
