import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useStatusBarStore } from "./statusBar";

describe("status bar background tasks", () => {
  beforeEach(() => { setActivePinia(createPinia()); vi.useFakeTimers(); });
  afterEach(() => vi.useRealTimers());

  it("tracks start, progress, success retention and persistent errors", () => {
    const store = useStatusBarStore();
    store.start("one", "csv.import");
    store.progress("one", "csv.import", "已导入 20 行", 20, 100);
    expect(store.tasks.one).toMatchObject({ state: "running", completed: 20, total: 100 });
    store.complete("one", "csv.import", undefined, "已处理 100 行");
    expect(store.tasks.one.state).toBe("success");
    vi.advanceTimersByTime(3_000);
    expect(store.tasks.one).toBeUndefined();

    store.complete("two", "csv.import", "导入失败");
    vi.advanceTimersByTime(10_000);
    expect(store.tasks.two).toMatchObject({ state: "error", message: "导入失败", dismissible: true });
    store.dismiss("two");
    expect(store.tasks.two).toBeUndefined();
  });
});
