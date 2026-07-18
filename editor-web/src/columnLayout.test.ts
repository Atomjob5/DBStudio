import { beforeEach, describe, expect, it } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import {
  autoColumnWidth, clampColumnWidth, columnIdentityKeys, reorderColumns, sampledRowIndices,
  selectHeader
} from "./columnLayout";
import { useColumnLayoutStore, type LayoutContext } from "./stores/columnLayout";
import type { QueryResult } from "./types";

describe("column layout helpers", () => {
  it("supports desktop header selection", () => {
    const order = ["a", "b", "c", "d"];
    expect(selectHeader(order, [], undefined, "b", { toggle: false, range: false })).toEqual({ selected: ["b"], anchor: "b" });
    expect(selectHeader(order, ["b"], "b", "d", { toggle: true, range: false })).toEqual({ selected: ["b", "d"], anchor: "d" });
    expect(selectHeader(order, ["b"], "b", "d", { toggle: false, range: true })).toEqual({ selected: ["b", "c", "d"], anchor: "b" });
  });

  it("moves non-contiguous selected columns as one ordered group", () => {
    expect(reorderColumns(["a", "b", "c", "d", "e"], ["b", "d"], "e", "after"))
      .toEqual(["a", "c", "e", "b", "d"]);
    expect(reorderColumns(["a", "b", "c"], ["a", "b"], "b", "before")).toEqual(["a", "b", "c"]);
  });

  it("creates distinct identities for duplicate labels", () => {
    const identities = columnIdentityKeys(["id", "id"]);
    expect(identities[0]).not.toBe(identities[1]);
  });

  it("samples at most 1000 rows and clamps manual and automatic widths", () => {
    expect(sampledRowIndices(2500)).toHaveLength(1000);
    expect(sampledRowIndices(2500)[999]).toBe(2499);
    expect(clampColumnWidth(20)).toBe(72);
    expect(clampColumnWidth(900)).toBe(800);
    expect(autoColumnWidth("金额", [[null], ["一二三四五"]], 0, (text) => Array.from(text).length * 20)).toBe(136);
    expect(autoColumnWidth("x", [["x".repeat(1000)]], 0, (text) => text.length * 10)).toBe(600);
  });
});

describe("column layout store", () => {
  beforeEach(() => setActivePinia(createPinia()));

  const result = (columns: string[]): QueryResult => ({ resultIndex: 0, sql: "select", type: "QUERY", columns,
    rows: [], updateCount: -1, truncated: false, durationMs: 0, complete: true });
  const context = (scope: "result" | "editor", executionId: string, columns = ["a", "b", "c"]): LayoutContext => ({
    scope, executionId, editorId: "editor-1", result: result(columns), defaultWidths: columns.map(() => 120)
  });

  it("keeps editor layouts only for an identical field set", () => {
    const store = useColumnLayoutStore();
    const first = store.ensure(context("editor", "execution-1"));
    store.selectOnly(first.viewKey, first.identities[0]);
    store.reorder(first.layoutKey, first.viewKey, first.identities, first.identities[2], "after", false);
    store.setWidth(first.layoutKey, first.identities[0], 240);

    const reused = store.ensure(context("editor", "execution-2"));
    expect(store.layout(reused.layoutKey)?.order).toEqual([first.identities[1], first.identities[2], first.identities[0]]);
    expect(store.layout(reused.layoutKey)?.widths[first.identities[0]]).toBe(240);

    const changed = store.ensure(context("editor", "execution-3", ["a", "b", "d"]));
    expect(store.layout(changed.layoutKey)?.order).toEqual(changed.identities);
  });

  it("isolates result layouts and filtered view ordering", () => {
    const store = useColumnLayoutStore();
    const first = store.ensure(context("result", "execution-1"));
    store.setFilter(first.viewKey, [first.identities[0], first.identities[2]], true);
    store.selectOnly(first.viewKey, first.identities[2]);
    store.reorder(first.layoutKey, first.viewKey, [first.identities[0], first.identities[2]], first.identities[0], "before", true);
    expect(store.displayedOrder(first.layoutKey, first.viewKey, [first.identities[0], first.identities[2]], true))
      .toEqual([first.identities[2], first.identities[0]]);
    expect(store.layout(first.layoutKey)?.order).toEqual(first.identities);

    store.setFilter(first.viewKey, first.identities, false);
    expect(store.displayedOrder(first.layoutKey, first.viewKey, first.identities, false)).toEqual(first.identities);
    const next = store.ensure(context("result", "execution-2"));
    expect(next.layoutKey).not.toBe(first.layoutKey);
  });
});
