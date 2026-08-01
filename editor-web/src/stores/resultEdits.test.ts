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

  it("builds ordered insert update and delete operations with typed values", () => {
    const store = useResultEditStore();
    store.addInsert("editor-1", "execution-1", 0, "draft:new", 2, [0, 1]);
    store.stage("editor-1", "execution-1", 0, 2, 1, null, "created", "draft:new");
    store.stage("editor-1", "execution-1", 0, 0, 1, "before", "after", "row-1");
    store.markDelete("editor-1", "execution-1", 0, "row-2", 1, ["2", "remove"]);

    const session = store.session("editor-1", "execution-1", 0)!;
    expect(store.operations(session).map((operation) => operation.kind)).toEqual(["insert", "update", "delete"]);
    expect(store.operations(session)[0].values).toEqual([
      { columnIndex: 0, value: { kind: "default" } },
      { columnIndex: 1, value: { kind: "text", value: "created" } }
    ]);
    expect(store.pendingOperationCount("editor-1")).toBe(3);
    expect(store.undo("editor-1", "execution-1", 0)).toEqual({ kind: "delete", rowId: "row-2" });
    expect(store.operations(session).map((operation) => operation.kind)).toEqual(["insert", "update"]);
  });

  it("keeps large value tokens opaque and returns them for cleanup when undone", () => {
    const store = useResultEditStore();
    store.stageMutation("editor-1", "execution-1", 0, 0, 2, "preview",
      { kind: "largeValueToken", value: "opaque-token" }, "row-1");
    const session = store.session("editor-1", "execution-1", 0)!;
    expect(store.operations(session)[0].values[0].value).toEqual(
      { kind: "largeValueToken", value: "opaque-token" });
    expect(store.undo("editor-1", "execution-1", 0)).toEqual({
      kind: "cell", rowIndex: 0, columnIndex: 2, value: "preview", largeValueToken: "opaque-token"
    });

    store.addInsert("editor-1", "execution-1", 0, "draft:new", 1, [0, 2]);
    store.stageMutation("editor-1", "execution-1", 0, 1, 2, null,
      { kind: "largeValueToken", value: "insert-token" }, "draft:new");
    expect(store.undo("editor-1", "execution-1", 0)).toEqual({
      kind: "insert", rowId: "draft:new",
      largeValues: [{ columnIndex: 2, token: "insert-token" }]
    });
  });

  it("discards only local drafts while preserving applied transaction changes", () => {
    const store = useResultEditStore();
    store.stage("editor-1", "execution-1", 0, 0, 1, "before", "applied", "row-1");
    store.markPosted("editor-1", "execution-1", 0);
    store.stageMutation("editor-1", "execution-1", 0, 0, 1, "applied",
      { kind: "largeValueToken", value: "cell-token" }, "row-1");
    store.addInsert("editor-1", "execution-1", 0, "draft:new", 1, [0, 2]);
    store.stageMutation("editor-1", "execution-1", 0, 1, 2, null,
      { kind: "largeValueToken", value: "insert-token" }, "draft:new");
    store.markDelete("editor-1", "execution-1", 0, "row-2", 2, ["2", "remove"]);
    store.stage("editor-2", "execution-2", 0, 0, 0, "other", "pending");

    expect(store.discardPending("editor-1")).toEqual({
      cells: [{ executionId: "execution-1", resultIndex: 0,
        rowIndex: 0, columnIndex: 1, value: "applied" }],
      inserts: [{ executionId: "execution-1", resultIndex: 0, rowId: "draft:new" }],
      largeValues: [
        { executionId: "execution-1", resultIndex: 0, columnIndex: 1, token: "cell-token" },
        { executionId: "execution-1", resultIndex: 0, columnIndex: 2, token: "insert-token" }
      ]
    });
    expect(store.hasPending("editor-1")).toBe(false);
    expect(store.hasChanges("editor-1")).toBe(true);
    expect(store.cellState("editor-1", "execution-1", 0, 0, 1)).toBe("posted");
    expect(store.session("editor-1", "execution-1", 0)?.inserts).toEqual([]);
    expect(store.session("editor-1", "execution-1", 0)?.deletes).toEqual([]);
    expect(store.hasPending("editor-2")).toBe(true);
  });
});
