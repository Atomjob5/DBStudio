import { beforeEach, describe, expect, it } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useAppStore } from "./app";
import { useConnectionStore } from "./connection";
import { useEditorStore } from "./editor";
import { formatCompletionBytes, serializedUtf8Size, useMetadataStore } from "./metadata";
import { useQueryStore } from "./query";
import { useSettingsStore } from "./settings";
import type { CompletionSnapshot, Suggestion } from "../types";

beforeEach(() => setActivePinia(createPinia()));

describe("application stores", () => {
  it("applies theme and starts without a workspace-wide connection", () => {
    const app = useAppStore();
    app.applyBootstrap({
      providers: [], profiles: [], recentFiles: [], settings: { "ui.theme": "light" }
    });
    expect(app.theme).toBe("light");
    expect(app.status).toBe("未选择链接");
  });

  it("follows the system theme until the user selects an override", () => {
    const app = useAppStore();
    app.setSystemTheme("dark");
    app.applyBootstrap({ providers: [], profiles: [], recentFiles: [], settings: { "ui.theme": "system" } });
    expect(app.themePreference).toBe("system");
    expect(app.theme).toBe("dark");

    app.setSystemTheme("light");
    expect(app.theme).toBe("light");
    app.setThemePreference("dark");
    expect(app.theme).toBe("dark");
    app.setSystemTheme("light");
    expect(app.theme).toBe("dark");
  });

  it("defaults invalid or missing saved themes to system", () => {
    const app = useAppStore();
    app.applyBootstrap({ providers: [], profiles: [], recentFiles: [], settings: { "ui.theme": "legacy" } });
    expect(app.themePreference).toBe("system");
  });

  it("keeps editor selection valid when a tab closes", () => {
    const editors = useEditorStore();
    const tab = (id: string) => ({ id, title: id, content: "", dirty: false, transactionDirty: false, busy: false, connectionState: "unbound" as const });
    editors.add(tab("one"));
    editors.add(tab("two"));
    editors.remove("two");
    expect(editors.activeId).toBe("one");
  });

  it("does not erase fast query results when the start response arrives late", () => {
    const queries = useQueryStore();
    queries.start("editor-1", "execution-1");
    queries.addResult("editor-1", {
      resultIndex: 0, sql: "select 1", type: "QUERY", columns: ["value"], rows: [],
      updateCount: -1, truncated: false, durationMs: 1, complete: false
    });
    queries.appendRows("editor-1", 0, [["1"]]);
    queries.start("editor-1", "execution-1");
    expect(queries.executions["editor-1"].results[0].rows).toEqual([["1"]]);
  });

  it("replaces execution, results and result references for every streamed update", () => {
    const queries = useQueryStore();
    queries.start("editor-1", "execution-1");
    const started = queries.executions["editor-1"];
    queries.addResult("editor-1", {
      resultIndex: 0, sql: "select 1", type: "QUERY", columns: ["value"], rows: [],
      updateCount: -1, truncated: false, durationMs: 0, complete: false
    });
    const withResult = queries.executions["editor-1"];
    expect(withResult).not.toBe(started);
    expect(withResult.results).not.toBe(started.results);
    const result = withResult.results[0];

    queries.appendRows("editor-1", 0, [["1"]]);
    const withRows = queries.executions["editor-1"];
    expect(withRows).not.toBe(withResult);
    expect(withRows.results).not.toBe(withResult.results);
    expect(withRows.results[0]).not.toBe(result);
    expect(withRows.results[0].rows).not.toBe(result.rows);

    queries.completeResult("editor-1", 0, { durationMs: 5 });
    const resultComplete = queries.executions["editor-1"];
    expect(resultComplete.results[0]).not.toBe(withRows.results[0]);
    queries.complete("editor-1", { durationMs: 6 });
    expect(queries.executions["editor-1"]).not.toBe(resultComplete);
  });

  it("marks disconnected results as historical snapshots without dropping loaded rows", () => {
    const queries = useQueryStore();
    queries.start("editor-1", "execution-1");
    queries.addResult("editor-1", {
      resultIndex: 0, sql: "select 1", type: "QUERY", columns: ["value"], rows: [["1"]],
      updateCount: -1, truncated: true, durationMs: 1, complete: false
    });
    const before = queries.executions["editor-1"];
    queries.markHistorical("editor-1");
    const historical = queries.executions["editor-1"];
    expect(historical).not.toBe(before);
    expect(historical).toMatchObject({ busy: false, historical: true });
    expect(historical.results[0]).toMatchObject({ rows: [["1"]], complete: true });

    queries.start("editor-1", "execution-2");
    expect(queries.executions["editor-1"].historical).toBeUndefined();
  });

  it("updates saved connection profiles without duplicates", () => {
    const connections = useConnectionStore();
    connections.initialize([], []);
    const first = { id: "p1", providerId: "mysql", name: "B", settings: {}, rememberPassword: false, environmentId: "e1", revision: "1" };
    connections.upsert(first);
    connections.upsert({ ...first, name: "A" });
    expect(connections.profiles).toHaveLength(1);
    expect(connections.profiles[0].name).toBe("A");
  });

  it("builds the system, environment and connection cascader hierarchy", () => {
    const connections = useConnectionStore();
    const profile = { id: "p1", providerId: "mysql", name: "开发库", settings: {}, rememberPassword: false,
      environmentId: "e1", revision: "7" };
    connections.initialize([], [profile], [{ id: "s1", name: "核心系统", revision: "1" }],
      [{ id: "e1", systemId: "s1", name: "DEV", revision: "2" }]);

    expect(connections.cascaderOptions[0].children[0].children[0]).toMatchObject({
      value: "p1@7", label: "DEV / 开发库", menuLabel: "开发库", leaf: true
    });
    expect(connections.pathFor(profile)).toBe("核心系统 / DEV / 开发库");
  });

  it("distinguishes connections with the same name by environment", () => {
    const connections = useConnectionStore();
    const dev = { id: "p-dev", providerId: "mysql", name: "业务库", settings: {}, rememberPassword: false,
      environmentId: "e-dev", revision: "1" };
    const sit = { ...dev, id: "p-sit", environmentId: "e-sit" };
    connections.initialize([], [dev, sit], [{ id: "s1", name: "核心系统", revision: "1" }], [
      { id: "e-dev", systemId: "s1", name: "DEV", revision: "1" },
      { id: "e-sit", systemId: "s1", name: "SIT", revision: "1" }
    ]);

    const environmentOptions = connections.cascaderOptions[0].children;
    expect(environmentOptions[0].children[0]).toMatchObject({ label: "DEV / 业务库", menuLabel: "业务库" });
    expect(environmentOptions[1].children[0]).toMatchObject({ label: "SIT / 业务库", menuLabel: "业务库" });
  });

  it("shares completion context by system and environment instead of connection revision", () => {
    const connections = useConnectionStore();
    const first = { id: "p-1", providerId: "mysql", name: "主库", settings: {}, rememberPassword: false,
      environmentId: "e-dev", revision: "1" };
    const second = { ...first, id: "p-2", name: "只读库", revision: "8" };
    const sit = { ...first, id: "p-3", environmentId: "e-sit" };
    connections.initialize([], [first, second, sit], [{ id: "s-order", name: "订单系统", revision: "1" }], [
      { id: "e-dev", systemId: "s-order", name: "DEV", revision: "1" },
      { id: "e-sit", systemId: "s-order", name: "SIT", revision: "1" }
    ]);

    expect(connections.completionContext(first)?.key).toBe("s-order:e-dev");
    expect(connections.completionContext(second)?.key).toBe("s-order:e-dev");
    expect(connections.completionContext(sit)?.key).toBe("s-order:e-sit");
  });

  it("deduplicates completion loads and atomically replaces an environment snapshot", () => {
    const metadata = useMetadataStore();
    const key = "system-1:environment-dev";
    const oldSuggestion = suggestion("old", "orders");
    const newSuggestion = suggestion("new", "customers");

    expect(metadata.beginCompletion(key, "DEV", "load-1", "profile-1")).toBe(true);
    expect(metadata.beginCompletion(key, "DEV", "load-duplicate", "profile-2")).toBe(false);
    expect(metadata.completeCompletion(key, "load-1", snapshot("profile-1", oldSuggestion))).toBe(true);
    metadata.activate("profile-1@1", key);
    expect(metadata.suggestions).toEqual([oldSuggestion]);

    expect(metadata.beginCompletion(key, "DEV", "load-2", "profile-2", true)).toBe(true);
    expect(metadata.suggestions).toEqual([oldSuggestion]);
    expect(metadata.completeCompletion(key, "stale-load", snapshot("profile-2", newSuggestion))).toBe(false);
    expect(metadata.suggestions).toEqual([oldSuggestion]);
    expect(metadata.completeCompletion(key, "load-2", snapshot("profile-2", newSuggestion))).toBe(true);
    expect(metadata.suggestions).toEqual([newSuggestion]);
    expect(metadata.completionFor(key)?.sourceProfileId).toBe("profile-2");
  });

  it("keeps the last successful completion snapshot when a manual refresh fails", () => {
    const metadata = useMetadataStore();
    const key = "system-1:environment-dev";
    const existing = suggestion("column-1", "order_id");
    metadata.beginCompletion(key, "DEV", "load-1", "profile-1");
    metadata.completeCompletion(key, "load-1", snapshot("profile-1", existing));

    metadata.beginCompletion(key, "DEV", "load-2", "profile-1", true);
    metadata.failCompletion(key, "load-2", "connection failed");

    expect(metadata.completionFor(key)).toMatchObject({ state: "error", hasSnapshot: true, error: "connection failed" });
    expect(metadata.completionFor(key)?.suggestions).toEqual([existing]);
    expect(metadata.beginCompletion(key, "DEV", "load-3", "profile-1")).toBe(false);
  });

  it("isolates environment completion snapshots and activates the selected editor environment", () => {
    const metadata = useMetadataStore();
    const dev = suggestion("dev", "dev_table");
    const sit = suggestion("sit", "sit_table");
    metadata.beginCompletion("system:dev", "DEV", "load-dev", "profile-dev");
    metadata.completeCompletion("system:dev", "load-dev", snapshot("profile-dev", dev));
    metadata.beginCompletion("system:sit", "SIT", "load-sit", "profile-sit");
    metadata.completeCompletion("system:sit", "load-sit", snapshot("profile-sit", sit));

    metadata.activate("profile-dev@1", "system:dev");
    expect(metadata.suggestions).toEqual([dev]);
    metadata.activate("profile-sit@1", "system:sit");
    expect(metadata.suggestions).toEqual([sit]);
  });

  it("estimates serialized UTF-8 completion cache space across environments", () => {
    const metadata = useMetadataStore();
    const chinese = suggestion("中文😀", "订单😀");
    const english = suggestion("english", "orders");
    metadata.beginCompletion("system:dev", "DEV", "load-dev", "profile-dev");
    metadata.completeCompletion("system:dev", "load-dev", snapshot("profile-dev", chinese));
    metadata.beginCompletion("system:sit", "SIT", "load-sit", "profile-sit");
    metadata.completeCompletion("system:sit", "load-sit", snapshot("profile-sit", english));

    expect(metadata.completionStats).toEqual({
      environmentCount: 2,
      suggestionCount: 2,
      estimatedBytes: serializedUtf8Size([chinese]) + serializedUtf8Size([english]),
      loadingCount: 0
    });
    expect(formatCompletionBytes(1229)).toBe("1.2 KB");
  });

  it("counts a preserved snapshot after refresh failure and excludes empty loading caches", () => {
    const metadata = useMetadataStore();
    const existing = suggestion("old", "订单");
    metadata.beginCompletion("system:dev", "DEV", "load-1", "profile-1");
    metadata.completeCompletion("system:dev", "load-1", snapshot("profile-1", existing));
    metadata.beginCompletion("system:dev", "DEV", "load-2", "profile-1", true);
    metadata.beginCompletion("system:sit", "SIT", "load-3", "profile-2");
    metadata.failCompletion("system:dev", "load-2", "failed");

    expect(metadata.completionStats).toEqual({
      environmentCount: 1,
      suggestionCount: 1,
      estimatedBytes: serializedUtf8Size([existing]),
      loadingCount: 1
    });
  });

  it("clears only completion caches and ignores responses from invalidated loads", () => {
    const metadata = useMetadataStore();
    const root = { id: "catalog", label: "db", kind: "catalog" as const, leaf: false };
    metadata.setRoots([root], "profile@1");
    metadata.activate("profile@1", "system:dev");
    metadata.beginCompletion("system:dev", "DEV", "load-1", "profile-1");
    metadata.updateProgress({ loadId: "load-1", phase: "loading", completed: 1, total: 2, message: "orders" });

    const released = metadata.clearCompletions();

    expect(released.loadingCount).toBe(1);
    expect(metadata.roots).toEqual([root]);
    expect(metadata.suggestions).toEqual([]);
    expect(metadata.canClearCompletions).toBe(false);
    expect(metadata.completeCompletion("system:dev", "load-1", snapshot("profile-1", suggestion("late", "late_table")))).toBe(false);
    metadata.updateProgress({ loadId: "load-1", phase: "loading", completed: 2, total: 2, message: "late" });
    expect(metadata.completionCaches).toEqual({});
  });

  it("initializes independent result limit and streaming batch settings", () => {
    const settings = useSettingsStore();
    settings.initialize({ "result.maxRows": "2500", "result.streamBatchRows": "75", "result.columnLayoutScope": "editor",
      "result.copyHeaderOnDoubleClick": "false", "result.copySeparator": "tab",
      "connection.maxActiveSessions": "12", "connection.idleTimeoutMinutes": "30" }, []);
    expect(settings.maxResultRows).toBe(2500);
    expect(settings.streamBatchRows).toBe(75);
    expect(settings.columnLayoutScope).toBe("editor");
    expect(settings.copyHeaderOnDoubleClick).toBe(false);
    expect(settings.copySeparator).toBe("tab");
    expect(settings.maxActiveSessions).toBe(12);
    expect(settings.idleTimeoutMinutes).toBe(30);
    settings.initialize({ "result.columnLayoutScope": "legacy", "result.copySeparator": "legacy" }, []);
    expect(settings.columnLayoutScope).toBe("result");
    expect(settings.copyHeaderOnDoubleClick).toBe(true);
    expect(settings.copySeparator).toBe("comma");
    expect(settings.maxActiveSessions).toBe(10);
    expect(settings.idleTimeoutMinutes).toBe(10);
  });
});

function suggestion(id: string, label: string): Suggestion {
  return { id, label, insertText: `\`${label}\``, detail: label, kind: "table" };
}

function snapshot(sourceProfileId: string, ...suggestions: Suggestion[]): CompletionSnapshot {
  return { providerId: "mysql", sourceProfileId, generatedAt: "2026-07-19T00:00:00Z", suggestions };
}
