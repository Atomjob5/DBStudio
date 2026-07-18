import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import { createPinia, setActivePinia } from "pinia";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus from "element-plus";
import App from "./App.vue";
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
});
