import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useAppStore } from "./app";
import { useConnectionStore } from "./connection";
import { useEditorStore } from "./editor";
import { formatCompletionBytes, useMetadataStore } from "./metadata";
import { useQueryStore } from "./query";
import { useSettingsStore } from "./settings";
import type { CompletionCacheSummary, MetadataNode } from "../types";

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
    const tab = (id: string) => ({ id, title: id, content: "", dirty: false, transactionDirty: false, busy: false,
      executionPhase: "idle" as const, transactionOperation: "idle" as const,
      connectionState: "unbound" as const });
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

  it("applies asynchronous remarks only to the matching execution", () => {
    const queries = useQueryStore();
    const result = {
      resultIndex: 0, sql: "select * from CBSAC.CUSTOMERS", type: "QUERY", columns: ["ID"],
      columnDetails: [{
        label: "ID", name: "ID", remarks: "", catalog: "", schema: "", table: "", typeName: "NUMBER"
      }],
      rows: [] as string[][], updateCount: -1, truncated: false, durationMs: 0, complete: false
    };
    queries.start("editor-1", "execution-1");
    queries.addResult("editor-1", result);
    queries.start("editor-1", "execution-2");
    queries.addResult("editor-1", result);

    queries.applyColumnRemarks("editor-1", "execution-1", 0, [{ index: 0, remarks: "旧备注" }]);
    expect(queries.executions["editor-1"].results[0].columnDetails?.[0].remarks).toBe("");
    queries.applyColumnRemarks("editor-1", "execution-2", 0, [{ index: 0, remarks: "客户编号" }]);
    expect(queries.executions["editor-1"].results[0].columnDetails?.[0].remarks).toBe("客户编号");
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

  it("shares completion context by system, environment and provider instead of connection revision", () => {
    const connections = useConnectionStore();
    const first = { id: "p-1", providerId: "mysql", name: "主库", settings: {}, rememberPassword: false,
      environmentId: "e-dev", revision: "1" };
    const second = { ...first, id: "p-2", name: "只读库", revision: "8" };
    const sit = { ...first, id: "p-3", environmentId: "e-sit" };
    const oracle = { ...first, id: "p-4", providerId: "oracle" };
    connections.initialize([], [first, second, sit, oracle], [{ id: "s-order", name: "订单系统", revision: "1" }], [
      { id: "e-dev", systemId: "s-order", name: "DEV", revision: "1" },
      { id: "e-sit", systemId: "s-order", name: "SIT", revision: "1" }
    ]);

    expect(connections.completionContext(first)?.key).toBe("s-order:e-dev:mysql");
    expect(connections.completionContext(second)?.key).toBe("s-order:e-dev:mysql");
    expect(connections.completionContext(sit)?.key).toBe("s-order:e-sit:mysql");
    expect(connections.completionContext(oracle)?.key).toBe("s-order:e-dev:oracle");
  });

  it("deduplicates completion loads and atomically replaces an environment snapshot", () => {
    const metadata = useMetadataStore();
    const key = "system-1:environment-dev";
    const oldSummary = summary("profile-1", 1, 2, 100);
    const newSummary = summary("profile-2", 2, 4, 180);

    expect(metadata.beginCompletion(key, "DEV", "load-1", "profile-1")).toBe(true);
    expect(metadata.beginCompletion(key, "DEV", "load-duplicate", "profile-2")).toBe(false);
    expect(metadata.completeCompletion(key, "load-1", oldSummary)).toBe(true);
    expect(metadata.completionFor(key)?.summary).toEqual(oldSummary);

    expect(metadata.beginCompletion(key, "DEV", "load-2", "profile-2", true)).toBe(true);
    expect(metadata.completionFor(key)?.summary).toEqual(oldSummary);
    expect(metadata.completeCompletion(key, "stale-load", newSummary)).toBe(false);
    expect(metadata.completionFor(key)?.summary).toEqual(oldSummary);
    expect(metadata.completeCompletion(key, "load-2", newSummary)).toBe(true);
    expect(metadata.completionFor(key)?.summary).toEqual(newSummary);
    expect(metadata.completionFor(key)?.sourceProfileId).toBe("profile-2");
  });

  it("keeps the last successful completion snapshot when a manual refresh fails", () => {
    const metadata = useMetadataStore();
    const key = "system-1:environment-dev";
    const existing = summary("profile-1", 1, 2, 100);
    metadata.beginCompletion(key, "DEV", "load-1", "profile-1");
    metadata.completeCompletion(key, "load-1", existing);

    metadata.beginCompletion(key, "DEV", "load-2", "profile-1", true);
    metadata.failCompletion(key, "load-2", "connection failed");

    expect(metadata.completionFor(key)).toMatchObject({ state: "error", hasSnapshot: true, error: "connection failed" });
    expect(metadata.completionFor(key)?.summary).toEqual(existing);
    expect(metadata.beginCompletion(key, "DEV", "load-3", "profile-1")).toBe(false);
  });

  it("hydrates persistent IndexedDB statistics without serializing snapshots on the main thread", () => {
    const metadata = useMetadataStore();
    metadata.applyPersistentStats({ environmentCount: 2, suggestionCount: 110_000, estimatedBytes: 52_000_000 });
    expect(metadata.completionStats).toEqual({
      environmentCount: 2,
      suggestionCount: 110_000,
      estimatedBytes: 52_000_000,
      loadingCount: 0
    });
    expect(formatCompletionBytes(1229)).toBe("1.2 KB");
  });

  it("counts loading caches independently from persistent snapshots", () => {
    const metadata = useMetadataStore();
    metadata.applyPersistentStats({ environmentCount: 1, suggestionCount: 3, estimatedBytes: 99 });
    metadata.beginCompletion("system:sit", "SIT", "load-3", "profile-2");

    expect(metadata.completionStats).toEqual({
      environmentCount: 1,
      suggestionCount: 3,
      estimatedBytes: 99,
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
    expect(metadata.canClearCompletions).toBe(true);
    metadata.clearTreeCaches();
    expect(metadata.canClearCompletions).toBe(false);
    expect(metadata.completeCompletion("system:dev", "load-1", summary("profile-1", 1, 1, 100))).toBe(false);
    metadata.updateProgress({ loadId: "load-1", phase: "loading", completed: 2, total: 2, message: "late" });
    expect(metadata.completionCaches).toEqual({});
  });

  it("caches empty and non-empty lazy branches, isolates environments, and deduplicates loads", async () => {
    const metadata = useMetadataStore();
    const loader = vi.fn(async () => [] as never[]);
    const key = "system-1:environment-dev";
    const first = metadata.loadTreeChildren(key, "catalog:sales", loader);
    const second = metadata.loadTreeChildren(key, "catalog:sales", loader);

    await expect(Promise.all([first, second])).resolves.toEqual([[], []]);
    expect(loader).toHaveBeenCalledTimes(1);
    expect(metadata.hasTreeChildren(key, "catalog:sales")).toBe(true);
    expect(metadata.treeChildren(key, "catalog:sales")).toEqual([]);

    metadata.setTreeChildren(key, "__root__", [{ id: "sales", label: "sales", kind: "catalog", leaf: false }]);
    metadata.setTreeChildren("system-1:environment-sit", "__root__", []);
    metadata.activate(key);
    expect(metadata.roots).toHaveLength(1);
    metadata.activate("system-1:environment-sit");
    expect(metadata.roots).toEqual([]);
  });

  it("does not let a response started before a tree clear repopulate the cache", async () => {
    const metadata = useMetadataStore();
    let resolveLoad!: (nodes: [{ id: string; label: string; kind: "catalog"; leaf: boolean }]) => void;
    const pending = new Promise<[{ id: string; label: string; kind: "catalog"; leaf: boolean }]>((resolve) => {
      resolveLoad = resolve;
    });
    const request = metadata.loadTreeChildren("system-1:environment-dev", "__root__", () => pending);
    metadata.clearTree("system-1:environment-dev");
    resolveLoad([{ id: "late", label: "late", kind: "catalog", leaf: false }]);
    await request;

    expect(metadata.hasTreeChildren("system-1:environment-dev", "__root__")).toBe(false);
  });

  it("invalidates all environments and ignores late responses after a global clear", async () => {
    const metadata = useMetadataStore();
    let resolveLoad!: (nodes: MetadataNode[]) => void;
    const pending = new Promise<MetadataNode[]>((resolve) => { resolveLoad = resolve; });
    const request = metadata.loadTreeChildren("system-1:environment-dev", "__root__", () => pending);
    metadata.setTreeChildren("system-1:environment-sit", "__root__", []);
    metadata.clearTreeCaches();
    resolveLoad([]);
    await request;

    expect(metadata.hasTreeCaches).toBe(false);
    expect(metadata.hasTreeChildren("system-1:environment-dev")).toBe(false);
    expect(metadata.hasTreeChildren("system-1:environment-sit")).toBe(false);
  });

  it("initializes independent result limit and streaming batch settings", () => {
    const settings = useSettingsStore();
    settings.initialize({ "result.maxRows": "2500", "result.streamBatchRows": "75", "result.columnLayoutScope": "editor",
      "result.copyHeaderOnDoubleClick": "false", "result.copySeparator": "tab",
      "result.showColumnRemarksInHeader": "true",
      "result.scrollOptimizationBufferScreens": "1.5",
      "statusBar.showSelectedColumnRemarks": "false",
      "connection.autoCommit": "true",
      "connection.maxActiveSessions": "12", "connection.idleTimeoutMinutes": "30",
      "editor.completionCandidateLimit": "250",
      "editor.completionPreciseMatchingEnabled": "true",
      "editor.minimapEnabled": "false",
      "editor.wordWrapEnabled": "true",
      "editor.dangerousStatementWarningEnabled": "false",
      "editor.completionSnippets": "[{\"id\":\"5d652bad-8dce-4b56-9c94-d62d74a74576\","
        + "\"trigger\":\"sf\",\"remarks\":\"通用查询\",\"sql\":\"select * from\"}]",
      "keyboard.shortcuts": "{\"query.executeCurrent\":null,\"query.executeAll\":\"Mod+7\"}" }, []);
    expect(settings.maxResultRows).toBe(2500);
    expect(settings.streamBatchRows).toBe(75);
    expect(settings.columnLayoutScope).toBe("editor");
    expect(settings.copyHeaderOnDoubleClick).toBe(false);
    expect(settings.copySeparator).toBe("tab");
    expect(settings.showColumnRemarksInHeader).toBe(true);
    expect(settings.scrollOptimizationBufferScreens).toBe(1.5);
    expect(settings.showSelectedColumnRemarks).toBe(false);
    expect(settings.autoCommit).toBe(true);
    expect(settings.maxActiveSessions).toBe(12);
    expect(settings.idleTimeoutMinutes).toBe(30);
    expect(settings.completionCandidateLimit).toBe(250);
    expect(settings.completionPreciseMatchingEnabled).toBe(true);
    expect(settings.minimapEnabled).toBe(false);
    expect(settings.wordWrapEnabled).toBe(true);
    expect(settings.dangerousStatementWarningEnabled).toBe(false);
    expect(settings.completionSnippets).toEqual([{
      id: "5d652bad-8dce-4b56-9c94-d62d74a74576",
      trigger: "sf",
      remarks: "通用查询",
      sql: "select * from"
    }]);
    expect(settings.shortcuts["query.executeCurrent"]).toBeNull();
    expect(settings.shortcuts["query.executeAll"]).toBe("Mod+7");
    expect(settings.shortcuts["query.cancel"]).toBe("Shift+Escape");
    settings.initialize({ "result.columnLayoutScope": "legacy", "result.copySeparator": "legacy" }, []);
    expect(settings.columnLayoutScope).toBe("result");
    expect(settings.copyHeaderOnDoubleClick).toBe(true);
    expect(settings.copySeparator).toBe("comma");
    expect(settings.showColumnRemarksInHeader).toBe(false);
    expect(settings.scrollOptimizationBufferScreens).toBe(1);
    expect(settings.showSelectedColumnRemarks).toBe(true);
    expect(settings.autoCommit).toBe(false);
    expect(settings.maxActiveSessions).toBe(10);
    expect(settings.idleTimeoutMinutes).toBe(10);
    expect(settings.completionCandidateLimit).toBe(100);
    expect(settings.completionPreciseMatchingEnabled).toBe(false);
    expect(settings.minimapEnabled).toBe(true);
    expect(settings.wordWrapEnabled).toBe(false);
    expect(settings.dangerousStatementWarningEnabled).toBe(true);
    expect(settings.completionSnippets).toEqual([]);
    expect(settings.shortcuts["query.executeCurrent"]).toBe("F8");
    expect(settings.shortcuts["query.executeAll"]).toBe("F7");

    settings.initialize({ "result.scrollOptimizationBufferScreens": "1.25" }, []);
    expect(settings.scrollOptimizationBufferScreens).toBe(1);
  });
});

function summary(sourceProfileId: string, objectCount: number, columnCount: number, estimatedBytes: number): CompletionCacheSummary {
  return { providerId: "mysql", sourceProfileId, generatedAt: "2026-07-19T00:00:00Z",
    selectedNamespaceKeys: ["catalog:sales"], objectCount, columnCount, estimatedBytes };
}
