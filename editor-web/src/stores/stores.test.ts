import { beforeEach, describe, expect, it } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useAppStore } from "./app";
import { useConnectionStore } from "./connection";
import { useEditorStore } from "./editor";
import { useQueryStore } from "./query";

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

  it("updates saved connection profiles without duplicates", () => {
    const connections = useConnectionStore();
    connections.initialize([], []);
    const first = { id: "p1", providerId: "mysql", name: "B", settings: {}, rememberPassword: false };
    connections.upsert(first);
    connections.upsert({ ...first, name: "A" });
    expect(connections.profiles).toHaveLength(1);
    expect(connections.profiles[0].name).toBe("A");
  });
});
