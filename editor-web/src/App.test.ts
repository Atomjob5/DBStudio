import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import { createPinia, setActivePinia } from "pinia";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElMessageBox } from "element-plus";
import App from "./App.vue";
import { useConnectionStore } from "./stores/connection";
import { useEditorStore } from "./stores/editor";
import { useMetadataStore } from "./stores/metadata";
import { useQueryStore } from "./stores/query";
import type { CompletionSnapshot, SavedProfile } from "./types";

const rpcMock = vi.hoisted(() => ({
  request: vi.fn(),
  ensureOperational: vi.fn(async () => undefined),
  ready: vi.fn(async () => undefined),
  listWorkspaces: vi.fn(async () => [{ id: "workspace-1", name: "测试空间", createdAt: "2026-01-01", updatedAt: "2026-01-01",
    state: "available", recoveryState: "none", unsavedEditorCount: 0, transactionCount: 0 }]),
  openWorkspace: vi.fn(async () => ({ workspaceId: "workspace-1", recoveryDecisionRequired: false, editors: [] })),
  closeWorkspace: vi.fn(async () => undefined),
  listeners: new Map<string, Set<(payload: unknown) => void>>()
}));
const rpcRequest = rpcMock.request;
vi.mock("./bridge/rpc", () => ({
  rpc: {
    transportState: "ready",
    ready: rpcMock.ready,
    listWorkspaces: rpcMock.listWorkspaces,
    createWorkspace: vi.fn(), renameWorkspace: vi.fn(), deleteWorkspace: vi.fn(),
    openWorkspace: rpcMock.openWorkspace, closeWorkspace: rpcMock.closeWorkspace,
    finalizeWorkspace: vi.fn(async () => undefined),
    resolveWorkspaceRecovery: vi.fn(),
    request: rpcMock.request,
    saveEditorDraft: vi.fn(async () => undefined),
    ensureOperational: rpcMock.ensureOperational,
    on: vi.fn((type: string, handler: (payload: unknown) => void) => {
      const listeners = rpcMock.listeners.get(type) ?? new Set();
      listeners.add(handler); rpcMock.listeners.set(type, listeners);
      return () => listeners.delete(handler);
    }),
    uploadCsv: vi.fn(),
    downloadCsv: vi.fn()
  }
}));

describe("App result loading status toolbar", () => {
  let wrapper: VueWrapper;

  beforeEach(async () => {
    setActivePinia(createPinia());
    rpcRequest.mockReset();
    rpcMock.ensureOperational.mockClear();
    rpcMock.listeners.clear();
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
          ObjectExplorer: true, SettingsDrawer: true, WorkspaceChooser: true
        }
      }
    });
    await flushPromises();
    const vm = wrapper.vm as unknown as { openWorkspace: (workspace: unknown) => Promise<void> };
    await vm.openWorkspace({ id: "workspace-1", name: "测试空间", createdAt: "2026-01-01", updatedAt: "2026-01-01",
      state: "available", recoveryState: "none", unsavedEditorCount: 0, transactionCount: 0 });
    await flushPromises();
  });

  afterEach(() => { wrapper.unmount(); vi.restoreAllMocks(); });

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

  it("工作空间恢复时只恢复编辑器内容并清空旧结果", async () => {
    await flushPromises();
    const connections = useConnectionStore();
    const editors = useEditorStore();
    const queries = useQueryStore();
    const profile = completionProfile();
    connections.initialize([], [profile], [{ id: "system-1", name: "核心系统", revision: "1" }],
      [{ id: "environment-dev", systemId: "system-1", name: "DEV", revision: "1" }]);
    editors.patch("bootstrap-editor", { connection: profile, connectionState: "active", busy: true, transactionDirty: true });
    queries.start("bootstrap-editor", "execution-before-disconnect");
    queries.addResult("bootstrap-editor", { resultIndex: 0, sql: "select 1", type: "QUERY", columns: ["id"],
      rows: [["1"]], updateCount: -1, truncated: true, durationMs: 2, complete: false });
    const vm = wrapper.vm as unknown as { bootstrapWorkspace: (editors: unknown[]) => Promise<void> };
    await vm.bootstrapWorkspace([{ id: "bootstrap-editor", title: "查询 1", content: "select 1", dirty: true,
      sortOrder: 0, active: true, transactionState: "auto-rolled-back", connectionState: "credentials-required",
      connection: profile }]);
    await nextTick();

    expect(editors.active).toMatchObject({ busy: false, transactionDirty: false,
      connectionState: "credentials-required", content: "select 1", transactionState: "auto-rolled-back" });
    expect(queries.executions["bootstrap-editor"]).toBeUndefined();
    expect(wrapper.find('button[aria-label="下一页数据"]').attributes("disabled")).toBeDefined();
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

  it("首次绑定补载环境快照，同环境标签切换和再次绑定不重复请求", async () => {
    await flushPromises();
    const connections = useConnectionStore();
    const editors = useEditorStore();
    const profile = completionProfile();
    connections.initialize([], [profile], [{ id: "system-1", name: "核心系统", revision: "1" }],
      [{ id: "environment-dev", systemId: "system-1", name: "DEV", revision: "1" }]);
    rpcRequest.mockImplementation(async (type: string) => {
      if (type === "editor.bind") return { connection: profile, connectionState: "suspended" };
      if (type === "metadata.completionSnapshot") return completionSnapshot(profile.id);
      return {};
    });

    const vm = wrapper.vm as unknown as { connectionSelectionChanged: (value: string) => Promise<void> };
    await vm.connectionSelectionChanged(`${profile.id}@${profile.revision}`);
    await flushPromises();
    expect(rpcRequest.mock.calls.filter(([type]) => type === "metadata.completionSnapshot")).toHaveLength(1);

    editors.add({ id: "editor-same-environment", title: "查询 2", content: "", dirty: false,
      transactionDirty: false, busy: false, connectionState: "unbound" });
    await vm.connectionSelectionChanged(`${profile.id}@${profile.revision}`);
    await flushPromises();
    expect(rpcRequest.mock.calls.filter(([type]) => type === "metadata.completionSnapshot")).toHaveLength(1);

    editors.activeId = "bootstrap-editor";
    await nextTick();
    expect(rpcRequest.mock.calls.filter(([type]) => type === "metadata.completionSnapshot")).toHaveLength(1);
  });

  it("保存新链接后在目标环境没有快照时后台加载补全", async () => {
    await flushPromises();
    const connections = useConnectionStore();
    const profile = completionProfile();
    const systems = [{ id: "system-1", name: "核心系统", revision: "1" }];
    const environments = [{ id: "environment-dev", systemId: "system-1", name: "DEV", revision: "1" }];
    connections.initialize([], [], systems, environments);
    rpcRequest.mockImplementation(async (type: string) => {
      if (type === "connection.catalog") return { systems, environments, profiles: [profile] };
      if (type === "metadata.completionSnapshot") return completionSnapshot(profile.id);
      return {};
    });

    const vm = wrapper.vm as unknown as { profileSaved: (value: SavedProfile) => Promise<void> };
    await vm.profileSaved(profile);
    await flushPromises();

    expect(rpcRequest).toHaveBeenCalledWith("metadata.completionSnapshot",
      expect.objectContaining({ profileId: profile.id }), 300_000);
    expect(useMetadataStore().completionFor("system-1:environment-dev")?.hasSnapshot).toBe(true);
  });

  it("数据库对象手动刷新会强制原子重建当前环境补全", async () => {
    await flushPromises();
    const connections = useConnectionStore();
    const editors = useEditorStore();
    const metadata = useMetadataStore();
    const profile = completionProfile();
    connections.initialize([], [profile], [{ id: "system-1", name: "核心系统", revision: "1" }],
      [{ id: "environment-dev", systemId: "system-1", name: "DEV", revision: "1" }]);
    editors.patch("bootstrap-editor", { connection: profile, connectionState: "suspended" });
    await nextTick();
    metadata.beginCompletion("system-1:environment-dev", "DEV", "initial", profile.id);
    metadata.completeCompletion("system-1:environment-dev", "initial", completionSnapshot(profile.id));
    rpcRequest.mockImplementation(async (type: string) => {
      if (type === "metadata.completionSnapshot") return completionSnapshot(profile.id, "customers");
      return {};
    });

    const vm = wrapper.vm as unknown as { refreshCompletionFromObjectExplorer: () => void };
    vm.refreshCompletionFromObjectExplorer();
    await flushPromises();

    expect(rpcRequest).toHaveBeenCalledWith("metadata.completionSnapshot",
      expect.objectContaining({ editorId: "bootstrap-editor" }), 300_000);
    expect(metadata.suggestions.map((item) => item.label)).toEqual(["customers"]);
  });

  it("在全局状态栏展示当前环境的补全进度、成功和失败状态", async () => {
    await flushPromises();
    const connections = useConnectionStore();
    const editors = useEditorStore();
    const metadata = useMetadataStore();
    const profile = completionProfile();
    connections.initialize([], [profile], [{ id: "system-1", name: "核心系统", revision: "1" }],
      [{ id: "environment-dev", systemId: "system-1", name: "DEV", revision: "1" }]);
    editors.patch("bootstrap-editor", { connection: profile, connectionState: "suspended" });
    await nextTick();

    metadata.beginCompletion("system-1:environment-dev", "DEV", "status-load", profile.id);
    metadata.updateProgress({ loadId: "status-load", phase: "loading", completed: 37, total: 240,
      message: "sales.orders", environmentId: "environment-dev" });
    await nextTick();
    expect(wrapper.find(".completion-status").text()).toContain("37/240 · sales.orders");

    metadata.completeCompletion("system-1:environment-dev", "status-load", completionSnapshot(profile.id));
    await nextTick();
    expect(wrapper.find(".completion-status").text()).toContain("补全已更新 · 1 项");

    metadata.beginCompletion("system-1:environment-dev", "DEV", "failed-load", profile.id, true);
    metadata.failCompletion("system-1:environment-dev", "failed-load", "连接失败");
    await nextTick();
    expect(wrapper.find(".completion-status").text()).toContain("补全加载失败");
    expect(wrapper.find(".completion-status").attributes("title")).toBe("连接失败");
  });

  it("确认后只清理补全缓存并使迟到快照失效", async () => {
    await flushPromises();
    const metadata = useMetadataStore();
    metadata.setRoots([{ id: "catalog", label: "eastwealthcrawler", kind: "catalog", leaf: false }], "profile@1");
    metadata.activate("profile@1", "system-1:environment-dev");
    metadata.beginCompletion("system-1:environment-dev", "DEV", "load-before-clear", "profile-1");
    metadata.completeCompletion("system-1:environment-dev", "load-before-clear", completionSnapshot("profile-1"));
    metadata.beginCompletion("system-1:environment-dev", "DEV", "load-refresh", "profile-1", true);
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue({ value: "", action: "confirm" } as never);

    const vm = wrapper.vm as unknown as { clearCompletionCaches: () => Promise<void> };
    await vm.clearCompletionCaches();
    await nextTick();

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining("1个环境"), "清理补全缓存", expect.any(Object));
    expect(metadata.suggestions).toEqual([]);
    expect(metadata.roots).toHaveLength(1);
    expect(metadata.completeCompletion("system-1:environment-dev", "load-refresh", completionSnapshot("profile-1", "late"))).toBe(false);
  });
});

function completionProfile(): SavedProfile {
  return { id: "profile-completion", providerId: "mysql", name: "业务库", settings: {}, rememberPassword: false,
    environmentId: "environment-dev", revision: "1" };
}

function completionSnapshot(sourceProfileId: string, label = "orders"): CompletionSnapshot {
  return { providerId: "mysql", sourceProfileId, generatedAt: "2026-07-19T00:00:00Z",
    suggestions: [{ id: `table:${label}`, label, insertText: `\`${label}\``, detail: label, kind: "table" }] };
}
