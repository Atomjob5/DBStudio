import { beforeEach, describe, expect, it } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useAppStore } from "./app";
import { useConnectionStore } from "./connection";
import { useEditorStore } from "./editor";
import { useQueryStore } from "./query";
import { useSettingsStore } from "./settings";

beforeEach(() => setActivePinia(createPinia()));

describe("application stores", () => {
  it("applies theme and connection state from bootstrap", () => {
    const app = useAppStore();
    app.applyBootstrap({
      providers: [], profiles: [], recentFiles: [], settings: { "ui.theme": "light" },
      connectedProfile: { id: "p1", providerId: "mysql", name: "开发库", settings: {}, rememberPassword: false }
    });
    expect(app.theme).toBe("light");
    expect(app.connected).toBe(true);
    expect(app.status).toContain("开发库");
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
    const tab = (id: string) => ({ id, title: id, content: "", dirty: false, transactionDirty: false, busy: false });
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

  it("updates saved connection profiles without duplicates", () => {
    const connections = useConnectionStore();
    connections.initialize([], []);
    const first = { id: "p1", providerId: "mysql", name: "B", settings: {}, rememberPassword: false };
    connections.upsert(first);
    connections.upsert({ ...first, name: "A" });
    expect(connections.profiles).toHaveLength(1);
    expect(connections.profiles[0].name).toBe("A");
  });

  it("initializes independent result limit and streaming batch settings", () => {
    const settings = useSettingsStore();
    settings.initialize({ "result.maxRows": "2500", "result.streamBatchRows": "75", "result.columnLayoutScope": "editor" }, []);
    expect(settings.maxResultRows).toBe(2500);
    expect(settings.streamBatchRows).toBe(75);
    expect(settings.columnLayoutScope).toBe("editor");
    settings.initialize({ "result.columnLayoutScope": "legacy" }, []);
    expect(settings.columnLayoutScope).toBe("result");
  });
});
