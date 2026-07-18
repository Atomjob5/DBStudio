import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import { createPinia, setActivePinia } from "pinia";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus from "element-plus";
import App from "./App.vue";
import { useConnectionStore } from "./stores/connection";
import { useEditorStore } from "./stores/editor";
import { useQueryStore } from "./stores/query";

const rpcRequest = vi.hoisted(() => vi.fn());
vi.mock("./bridge/rpc", () => ({
  rpc: {
    request: rpcRequest,
    on: vi.fn(() => () => undefined),
    uploadCsv: vi.fn(),
    downloadCsv: vi.fn()
  }
}));

describe("App result loading status toolbar", () => {
  let wrapper: VueWrapper;

  beforeEach(() => {
    setActivePinia(createPinia());
    rpcRequest.mockReset();
    rpcRequest.mockImplementation(async (type: string, payload: Record<string, unknown>) => {
      if (type === "app.bootstrap") return { providers: [], profiles: [], recentFiles: [], settings: {} };
      if (type === "editor.create") return { id: "bootstrap-editor", title: "查询 1", connectionState: "unbound" };
      if (type === "query.fetchRows") {
        const offset = Number(payload.offset);
        return { resultIndex: payload.resultIndex, offset, rows: [[String(offset + 1)]], hasMore: true, nextOffset: offset + 1 };
      }
      return {};
    });
    wrapper = mount(App, {
      global: {
        plugins: [ElementPlus],
        stubs: {
          ConnectionDialog: true, ConnectionManagerPanel: true, CsvImportDialog: true, HistoryDrawer: true, MonacoEditor: true,
          ObjectExplorer: true, SettingsDrawer: true
        }
      }
    });
  });

  afterEach(() => wrapper.unmount());

  it("keeps SVG actions in the footer and loads the selected result", async () => {
    await flushPromises();
    const nextButton = wrapper.find('button[aria-label="下一页数据"]');
    const allButton = wrapper.find('button[aria-label="获取全部数据"]');
    expect(wrapper.find('.status-bar [role="toolbar"]').exists()).toBe(true);
    expect(nextButton.exists()).toBe(true);
    expect(allButton.exists()).toBe(true);
    expect(nextButton.find("svg").exists()).toBe(true);
    expect(allButton.find("svg").exists()).toBe(true);
    expect(nextButton.attributes("disabled")).toBeDefined();

    const editors = useEditorStore();
    const queries = useQueryStore();
    editors.add({ id: "editor-1", title: "查询 1", content: "", dirty: false, transactionDirty: false, busy: false, connectionState: "unbound" });
    queries.start("editor-1", "execution-1");
    queries.addResult("editor-1", { resultIndex: 0, sql: "select 1", type: "QUERY", columns: ["id"], rows: [["1"], ["2"]],
      updateCount: -1, truncated: true, durationMs: 3, complete: true });
    queries.addResult("editor-1", { resultIndex: 1, sql: "select 2", type: "QUERY", columns: ["id"], rows: [["10"]],
      updateCount: -1, truncated: true, durationMs: 4, complete: true });
    queries.complete("editor-1", { durationMs: 5 });
    await nextTick();

    expect(nextButton.attributes("disabled")).toBeUndefined();
    await wrapper.findAll(".result-tabs .el-tabs__item")[1].trigger("click");
    await nextTick();
    await nextButton.trigger("click");
    await flushPromises();

    expect(rpcRequest).toHaveBeenCalledWith("query.fetchRows", {
      editorId: "editor-1", resultIndex: 1, offset: 1, limit: 1000
    }, 120_000);
    expect(wrapper.find(".result-data-toolbar").exists()).toBe(false);
  });

  it("disables both actions while all rows are loading", async () => {
    await flushPromises();
    let finishRequest: ((value: unknown) => void) | undefined;
    rpcRequest.mockImplementation(async (type: string, payload: Record<string, unknown>) => {
      if (type !== "query.fetchRows") return {};
      return await new Promise((resolve) => {
        finishRequest = resolve;
      });
    });

    const editors = useEditorStore();
    const queries = useQueryStore();
    editors.add({ id: "editor-1", title: "查询 1", content: "", dirty: false, transactionDirty: false, busy: false, connectionState: "unbound" });
    queries.start("editor-1", "execution-1");
    queries.addResult("editor-1", { resultIndex: 0, sql: "select 1", type: "QUERY", columns: ["id"], rows: [["1"]],
      updateCount: -1, truncated: true, durationMs: 3, complete: true });
    queries.complete("editor-1", { durationMs: 3 });
    await nextTick();

    const nextButton = wrapper.find('button[aria-label="下一页数据"]');
    const allButton = wrapper.find('button[aria-label="获取全部数据"]');
    await allButton.trigger("click");
    await nextTick();
    expect(nextButton.attributes("disabled")).toBeDefined();
    expect(allButton.attributes("disabled")).toBeDefined();
    expect(allButton.classes()).toContain("is-loading");

    finishRequest?.({ resultIndex: 0, offset: 1, rows: [], hasMore: false, nextOffset: 1 });
    await flushPromises();
  });

  it("手动折叠左侧面板后可通过 Activity Bar 单击恢复", async () => {
    await flushPromises();
    const connectionButton = wrapper.find('button[aria-label="连接管理"]');
    expect(connectionButton.classes()).toContain("active");

    const appVm = wrapper.vm as unknown as { leftWidth: number; selectTool: (tool: "connections") => void };
    appVm.leftWidth = 0;
    appVm.selectTool("connections");
    await nextTick();
    expect(appVm.leftWidth).toBe(248);
    expect(connectionButton.classes()).toContain("active");
    expect(wrapper.find("connection-manager-panel-stub").exists()).toBe(true);
  });

  it("点击当前 Activity Bar 工具可折叠并再次展开", async () => {
    await flushPromises();
    const connectionButton = wrapper.find('button[aria-label="连接管理"]');
    await connectionButton.trigger("click");
    await nextTick();
    expect(wrapper.find("connection-manager-panel-stub").exists()).toBe(false);

    await connectionButton.trigger("click");
    await nextTick();
    expect(wrapper.find("connection-manager-panel-stub").exists()).toBe(true);
    expect(connectionButton.classes()).toContain("active");
  });

  it("用环境和链接名展示连接并通过选择器背景表达状态", async () => {
    await flushPromises();
    const connections = useConnectionStore();
    const editors = useEditorStore();
    const profile = { id: "profile-1", providerId: "mysql", name: "业务库", settings: {}, rememberPassword: false,
      environmentId: "environment-dev", revision: "1" };
    connections.initialize([], [profile], [{ id: "system-1", name: "核心系统", revision: "1" }],
      [{ id: "environment-dev", systemId: "system-1", name: "DEV", revision: "1" }]);
    editors.add({ id: "connected-editor", title: "查询", content: "", dirty: false, transactionDirty: false, busy: false,
      connection: profile, connectionState: "active" });
    await nextTick();

    const selector = wrapper.find(".connection-pill-wrap");
    expect(wrapper.find(".connection-prefix").exists()).toBe(false);
    expect(wrapper.find(".connection-indicator").exists()).toBe(false);
    expect(selector.classes()).toContain("connected");
    expect(selector.classes()).not.toContain("stale");
    expect((wrapper.find('.connection-pill input').element as HTMLInputElement).value).toBe("DEV / 业务库");

    editors.patch("connected-editor", { connectionState: "suspended" });
    await nextTick();
    expect(selector.classes()).toContain("suspended");
    expect(selector.classes()).not.toContain("connected");

    editors.patch("connected-editor", { connection: undefined, connectionState: "unbound" });
    await nextTick();
    expect(selector.classes()).not.toContain("connected");
    expect(selector.classes()).not.toContain("suspended");

    editors.patch("connected-editor", { connection: { ...profile, stale: true }, connectionState: "active" });
    await nextTick();
    expect(selector.classes()).toContain("connected");
    expect(selector.classes()).toContain("stale");

    editors.patch("connected-editor", { connection: { ...profile, unavailable: true }, connectionState: "active" });
    await nextTick();
    expect(selector.classes()).toContain("stale");
  });
});
