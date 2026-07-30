import { beforeEach, describe, expect, it } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useResultEditStore } from "./resultEdits";

describe("result edit store", () => {
  beforeEach(() => setActivePinia(createPinia()));

  it("separates local drafts from posted uncommitted values", () => {
    const store = useResultEditStore();
    store.setUnlocked("editor-1", "execution-1", 0, true);
    store.stage("editor-1", "execution-1", 0, 2, 1, "before", "draft");

    expect(store.session("editor-1", "execution-1", 0)?.unlocked).toBe(true);
    expect(store.cellState("editor-1", "execution-1", 0, 2, 1)).toBe("pending");
    expect(store.hasPending("editor-1")).toBe(true);

    store.markPosted("editor-1", "execution-1", 0);
    expect(store.cellState("editor-1", "execution-1", 0, 2, 1)).toBe("posted");
    expect(store.hasPending("editor-1")).toBe(false);
    expect(store.hasChanges("editor-1")).toBe(true);

    store.stage("editor-1", "execution-1", 0, 2, 1, "draft", "second");
    expect(store.restore("editor-1", "confirmed")).toEqual([{
      resultIndex: 0, rowIndex: 2, columnIndex: 1, value: "draft"
    }]);
    expect(store.restore("editor-1", "original")).toEqual([{
      resultIndex: 0, rowIndex: 2, columnIndex: 1, value: "before"
    }]);
  });

  it("drops a reverted clean draft and clears only the selected editor", () => {
    const store = useResultEditStore();
    store.stage("editor-1", "execution-1", 0, 0, 0, "1", "2");
    store.stage("editor-1", "execution-1", 0, 0, 0, "2", "1");
    store.stage("editor-2", "execution-2", 0, 0, 0, "a", "b");

    expect(store.hasChanges("editor-1")).toBe(false);
    store.finishEditor("editor-1");
    expect(store.hasChanges("editor-2")).toBe(true);
  });
});
