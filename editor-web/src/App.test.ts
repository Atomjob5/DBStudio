import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { defineComponent, h, nextTick } from "vue";
import { createPinia, setActivePinia } from "pinia";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElMessage, ElMessageBox } from "element-plus";
import App from "./App.vue";
import { useConnectionStore } from "./stores/connection";
import { useEditorStore } from "./stores/editor";
import { useMetadataStore } from "./stores/metadata";
import { useQueryStore } from "./stores/query";
import { useResultEditStore } from "./stores/resultEdits";
import { useSettingsStore } from "./stores/settings";
import { cloneColorSchemes, DEFAULT_COLOR_SCHEMES, serializeColorSchemeSettings } from "./appearance";
import type {
  CompletionCacheSummary,
  CompletionNamespaceDescriptor,
  SavedProfile,
  SqlEditorSelectionAction,
  SqlTransformTarget,
} from "./types";

const rpcMock = vi.hoisted(() => ({
  request: vi.fn(),
  ensureOperational: vi.fn(async () => undefined),
  ready: vi.fn(async () => undefined),
  listWorkspaces: vi.fn(async () => [{ id: "workspace-1", name: "测试空间", createdAt: "2026-01-01", updatedAt: "2026-01-01",
    state: "available", recoveryState: "none", unsavedEditorCount: 0, transactionCount: 0 }]),
  openWorkspace: vi.fn(async () => ({ workspaceId: "workspace-1", recoveryDecisionRequired: false, editors: [] })),
  closeWorkspace: vi.fn(async () => undefined),
  saveEditorDraft: vi.fn(async () => undefined),
  deleteResultLargeValueDraft: vi.fn(async () => undefined),
  listeners: new Map<string, Set<(payload: unknown) => void>>()
}));
const rpcRequest = rpcMock.request;
const completionMock = vi.hoisted(() => ({
  inspect: vi.fn(), refresh: vi.fn(), stats: vi.fn(), clear: vi.fn(), complete: vi.fn(),
  resolveResultColumnRemarks: vi.fn(), enrichQuery: vi.fn(), invalidateStructure: vi.fn()
}));
vi.mock("./bridge/rpc", () => ({
  rpc: {
    transportState: "ready",
    activeWorkspaceId: "workspace-1",
    activeClientId: "client-1",
    ready: rpcMock.ready,
    listWorkspaces: rpcMock.listWorkspaces,
    createWorkspace: vi.fn(), renameWorkspace: vi.fn(), deleteWorkspace: vi.fn(),
    openWorkspace: rpcMock.openWorkspace, closeWorkspace: rpcMock.closeWorkspace,
    finalizeWorkspace: vi.fn(async () => undefined),
    resolveWorkspaceRecovery: vi.fn(),
    request: rpcMock.request,
    saveEditorDraft: rpcMock.saveEditorDraft,
    ensureOperational: rpcMock.ensureOperational,
    deleteResultLargeValueDraft: rpcMock.deleteResultLargeValueDraft,
    on: vi.fn((type: string, handler: (payload: unknown) => void) => {
      const listeners = rpcMock.listeners.get(type) ?? new Set();
      listeners.add(handler); rpcMock.listeners.set(type, listeners);
      return () => listeners.delete(handler);
    }),
    uploadCsv: vi.fn(),
    downloadCsv: vi.fn()
  }
}));
vi.mock("./completion/client", () => ({ completionClient: completionMock }));

const captureSqlTransformTarget = vi.fn<(key?: string) => SqlTransformTarget | undefined>();
const applySqlTransform = vi.fn();
const runSelectionAction = vi.fn<(action: SqlEditorSelectionAction) => boolean>();
const highlightExecutionSource = vi.fn().mockReturnValue("highlighted");
const clearResultHighlight = vi.fn();
const MonacoEditorStub = defineComponent({
  name: "MonacoEditor",
  props: { initialValue: { type: String, default: "" } },
  emits: ["execute", "selection-change"],
  setup(props, { emit, expose }) {
    expose({
      getValue: () => props.initialValue,
      setValue: () => undefined,
      triggerExecute: (scope: "current" | "script" | "current-new-tab") => emit("execute", scope, "", 0),
      triggerCompletion: () => undefined,
      captureSqlTransformTarget,
      applySqlTransform,
      runSelectionAction,
      getModelVersion: () => 1,
      highlightExecutionSource,
      clearResultHighlight,
    });
    return () => h("div", { class: "monaco-editor-stub" });
  },
});

describe("App result loading status toolbar", () => {
  let wrapper: VueWrapper;

  beforeEach(async () => {
    setActivePinia(createPinia());
    rpcRequest.mockReset();
    captureSqlTransformTarget.mockReset();
    applySqlTransform.mockReset().mockReturnValue("applied");
    runSelectionAction.mockReset().mockReturnValue(true);
    highlightExecutionSource.mockReset().mockReturnValue("highlighted");
    clearResultHighlight.mockReset();
    completionMock.inspect.mockReset().mockResolvedValue(undefined);
    completionMock.refresh.mockReset().mockResolvedValue(completionSummary("profile-completion"));
    completionMock.stats.mockReset().mockResolvedValue({ environmentCount: 0, suggestionCount: 0, estimatedBytes: 0 });
    completionMock.clear.mockReset().mockResolvedValue(undefined);
    completionMock.complete.mockReset().mockResolvedValue({ items: [], incomplete: false });
    completionMock.resolveResultColumnRemarks.mockReset().mockResolvedValue([]);
    completionMock.enrichQuery.mockReset().mockResolvedValue(undefined);
    completionMock.invalidateStructure.mockReset().mockResolvedValue(undefined);
    rpcMock.ensureOperational.mockClear();
    rpcMock.saveEditorDraft.mockReset().mockResolvedValue(undefined);
    rpcMock.deleteResultLargeValueDraft.mockClear();
    rpcMock.listeners.clear();
    rpcRequest.mockImplementation(async (type: string, payload: Record<string, unknown>) => {
      if (type === "app.bootstrap") return { providers: [], profiles: [], recentFiles: [], settings: {} };
      if (type === "editor.create") return { id: "bootstrap-editor", title: "查询 1", connectionState: "unbound" };
      if (type === "metadata.completionNamespaces") return completionNamespaces();
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
          ConnectionDialog: true, ConnectionManagerPanel: true, CsvImportDialog: true, HistoryDrawer: true, MonacoEditor: MonacoEditorStub,
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

  afterEach(() => {
    ElMessageBox.close();
    document.body.querySelectorAll(".el-overlay").forEach((element) => element.remove());
    wrapper.unmount();
    vi.restoreAllMocks();
  });

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
    editors.add({ id: "editor-1", title: "查询 1", content: "", dirty: false, transactionDirty: false, busy: false,
      executionPhase: "idle", transactionOperation: "idle", connectionState: "unbound" });
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

    expect(rpcRequest).toHaveBeenCalledWith("query.fetchRows", expect.objectContaining({
      editorId: "editor-1", resultIndex: 1, offset: 1, limit: 1000,
      executionId: expect.any(String)
    }), 120_000);
    expect(wrapper.find(".result-data-toolbar").exists()).toBe(false);
  });

  it("reveals a result source only after an explicit result-tab click", async () => {
    const editors = useEditorStore();
    const queries = useQueryStore();
    editors.add({ id: "editor-with-results", title: "查询 2", content: "SELECT 1;\nSELECT 2;", dirty: false,
      transactionDirty: false, busy: false, executionPhase: "idle", transactionOperation: "idle",
      connectionState: "unbound" });
    queries.start("editor-with-results", "execution-source");
    queries.addResult("editor-with-results", { resultIndex: 0, sql: "SELECT 1", type: "QUERY", columns: ["id"],
      rows: [["1"]], updateCount: -1, truncated: false, durationMs: 1, complete: true,
      sourceStartOffset: 0, sourceEndOffset: 8 });
    queries.addResult("editor-with-results", { resultIndex: 1, sql: "SELECT 2", type: "QUERY", columns: ["id"],
      rows: [["2"]], updateCount: -1, truncated: false, durationMs: 1, complete: true,
      sourceStartOffset: 9, sourceEndOffset: 17 });
    queries.complete("editor-with-results", { durationMs: 2 });
    await flushPromises();
    highlightExecutionSource.mockClear();

    const resultTabs = wrapper.findAll(".result-tabs .el-tabs__item");
    await resultTabs[1].trigger("click");
    await flushPromises();
    expect(highlightExecutionSource.mock.calls.some((call) => call.at(-1)?.reveal === true)).toBe(true);

    highlightExecutionSource.mockClear();
    await resultTabs[1].trigger("click");
    await flushPromises();
    expect(highlightExecutionSource.mock.calls.some((call) => call.at(-1)?.reveal === true)).toBe(true);

    editors.add({ id: "other-editor", title: "查询 3", content: "SELECT 3", dirty: false, transactionDirty: false,
      busy: false, executionPhase: "idle", transactionOperation: "idle", connectionState: "unbound" });
    await flushPromises();
    highlightExecutionSource.mockClear();
    editors.activeId = "editor-with-results";
    await flushPromises();
    expect(highlightExecutionSource.mock.calls.length).toBeGreaterThan(0);
    expect(highlightExecutionSource.mock.calls.every((call) => call.at(-1)?.reveal === false)).toBe(true);
  });

  it("shows the editor tab context menu in the specified order", async () => {
    expect(wrapper.get(".editor-tabs .el-dropdown").attributes("draggable")).toBe("true");
    const label = wrapper.get(".editor-tab-label");
    await label.trigger("contextmenu");
    await nextTick();

    const labels = new Set(["关闭窗口", "关闭其它窗口", "关闭所有窗口", "复制窗口", "重命名"]);
    const items = Array.from(document.body.querySelectorAll<HTMLElement>(".el-dropdown-menu__item"))
      .filter((item) => labels.has(item.textContent?.trim() ?? ""));
    expect(items.map((item) => item.textContent?.trim())).toEqual([
      "关闭窗口", "关闭其它窗口", "关闭所有窗口", "复制窗口", "重命名"
    ]);
    expect(items[1]?.classList.contains("is-disabled")).toBe(true);
  });

  it("duplicates only the latest SQL text into a new unbound editor", async () => {
    const editors = useEditorStore();
    const sourceHandle = { name: "source.sql" } as FileSystemFileHandle;
    editors.patch("bootstrap-editor", {
      content: "select * from source_table",
      dirty: true,
      filePath: "source.sql",
      fileHandle: sourceHandle,
      connection: completionProfile(),
      connectionState: "active",
      transactionDirty: true,
      transactionState: "active"
    });
    await nextTick();
    rpcRequest.mockImplementation(async (type: string) => {
      if (type === "editor.create") return { id: "copied-editor", title: "查询 2", connectionState: "unbound" };
      return {};
    });

    const vm = wrapper.vm as unknown as { handleEditorTabCommand: (command: string, id: string) => Promise<void> };
    await vm.handleEditorTabCommand("duplicate", "bootstrap-editor");

    expect(rpcRequest).toHaveBeenCalledWith("editor.create", {});
    const copied = editors.tabs.find((tab) => tab.id === "copied-editor");
    expect(copied).toMatchObject({
      title: "查询 2",
      content: "select * from source_table",
      dirty: true,
      transactionDirty: false,
      connectionState: "unbound"
    });
    expect(copied?.connection).toBeUndefined();
    expect(copied?.filePath).toBeUndefined();
    expect(copied?.fileHandle).toBeUndefined();
    expect(editors.activeId).toBe("copied-editor");
  });

  it("renames a tab without changing its dirty or file state and persists immediately", async () => {
    const editors = useEditorStore();
    const fileHandle = { name: "source.sql" } as FileSystemFileHandle;
    editors.patch("bootstrap-editor", { title: "查询 1", content: "select 1", dirty: false,
      filePath: "source.sql", fileHandle });
    await nextTick();
    const promptSpy = vi.spyOn(ElMessageBox, "prompt")
      .mockResolvedValue({ value: "  临时核对  ", action: "confirm" } as never);

    const vm = wrapper.vm as unknown as { handleEditorTabCommand: (command: string, id: string) => Promise<void> };
    await vm.handleEditorTabCommand("rename", "bootstrap-editor");

    expect(editors.tabs[0]).toMatchObject({ title: "临时核对", dirty: false, filePath: "source.sql", fileHandle });
    const validator = promptSpy.mock.calls[0]?.[2]?.inputValidator;
    expect(typeof validator === "function" ? validator("   ") : undefined).toBe("名称不能为空");
    expect(rpcMock.saveEditorDraft).toHaveBeenCalledWith("bootstrap-editor",
      expect.objectContaining({ title: "临时核对", sqlText: "select 1", dirty: false }), false);
  });

  it("restores the previous title when rename persistence fails", async () => {
    const editors = useEditorStore();
    rpcMock.saveEditorDraft.mockRejectedValueOnce(new Error("draft failed"));
    vi.spyOn(ElMessageBox, "prompt").mockResolvedValue({ value: "新名称", action: "confirm" } as never);
    const messageSpy = vi.spyOn(ElMessage, "error").mockImplementation(() => undefined as never);

    const vm = wrapper.vm as unknown as { handleEditorTabCommand: (command: string, id: string) => Promise<void> };
    await vm.handleEditorTabCommand("rename", "bootstrap-editor");

    expect(editors.tabs[0]?.title).toBe("查询 1");
    expect(messageSpy).toHaveBeenCalled();
  });

  it("closes other tabs in order and activates the context-menu target", async () => {
    const editors = useEditorStore();
    editors.add({ id: "target-editor", title: "目标", content: "", dirty: false, transactionDirty: false, busy: false,
      executionPhase: "idle", transactionOperation: "idle", connectionState: "unbound" });
    editors.add({ id: "last-editor", title: "末尾", content: "", dirty: false, transactionDirty: false, busy: false,
      executionPhase: "idle", transactionOperation: "idle", connectionState: "unbound" });
    rpcRequest.mockImplementation(async (type: string, payload: Record<string, unknown>) => {
      if (type === "editor.close" && payload.action === "check") return { requiresTransactionDecision: false };
      return {};
    });
    const vm = wrapper.vm as unknown as { handleEditorTabCommand: (command: string, id: string) => Promise<void> };
    await vm.handleEditorTabCommand("close-others", "target-editor");

    expect(rpcRequest.mock.calls.filter(([type]) => type === "editor.close").map(([, payload]) =>
      [(payload as Record<string, unknown>).editorId, (payload as Record<string, unknown>).action])).toEqual([
      ["bootstrap-editor", "check"], ["bootstrap-editor", "close"],
      ["last-editor", "check"], ["last-editor", "close"]
    ]);
    expect(editors.tabs.map((tab) => tab.id)).toEqual(["target-editor"]);
    expect(editors.activeId).toBe("target-editor");
  });

  it("closes all tabs without creating a replacement editor", async () => {
    const editors = useEditorStore();
    editors.add({ id: "second-editor", title: "查询 2", content: "", dirty: false, transactionDirty: false, busy: false,
      executionPhase: "idle", transactionOperation: "idle", connectionState: "unbound" });
    rpcRequest.mockImplementation(async (type: string, payload: Record<string, unknown>) => {
      if (type === "editor.close" && payload.action === "check") return { requiresTransactionDecision: false };
      return {};
    });
    const createCallsBefore = rpcRequest.mock.calls.filter(([type]) => type === "editor.create").length;

    const vm = wrapper.vm as unknown as { handleEditorTabCommand: (command: string, id: string) => Promise<void> };
    await vm.handleEditorTabCommand("close-all", "bootstrap-editor");

    expect(editors.tabs).toHaveLength(0);
    expect(editors.activeId).toBe("");
    expect(rpcRequest.mock.calls.filter(([type]) => type === "editor.create")).toHaveLength(createCallsBefore);
  });

  it("stops closing later tabs when a close confirmation is cancelled", async () => {
    const editors = useEditorStore();
    editors.add({ id: "dirty-editor", title: "待确认", content: "update t set a = 1", dirty: true,
      transactionDirty: false, busy: false, executionPhase: "idle", transactionOperation: "idle", connectionState: "unbound" });
    editors.add({ id: "later-editor", title: "后续", content: "", dirty: false, transactionDirty: false, busy: false,
      executionPhase: "idle", transactionOperation: "idle", connectionState: "unbound" });
    editors.add({ id: "target-editor", title: "保留", content: "", dirty: false, transactionDirty: false, busy: false,
      executionPhase: "idle", transactionOperation: "idle", connectionState: "unbound" });
    rpcRequest.mockImplementation(async (type: string, payload: Record<string, unknown>) => {
      if (type === "editor.close" && payload.action === "check") return { requiresTransactionDecision: false };
      return {};
    });

    const vm = wrapper.vm as unknown as { handleEditorTabCommand: (command: string, id: string) => Promise<void> };
    const closing = vm.handleEditorTabCommand("close-others", "target-editor");
    await flushPromises();
    const dialog = document.body.querySelector(".el-message-box");
    expect(dialog).not.toBeNull();
    (dialog?.querySelector(".el-message-box__headerbtn") as HTMLButtonElement | null)?.click();
    await closing;

    expect(editors.tabs.map((tab) => tab.id)).toEqual(["dirty-editor", "later-editor", "target-editor"]);
    expect(editors.activeId).toBe("dirty-editor");
    expect(rpcRequest.mock.calls.filter(([type]) => type === "editor.close").map(([, payload]) =>
      (payload as Record<string, unknown>).editorId)).toEqual(["bootstrap-editor", "bootstrap-editor"]);
  });

  it("shows result post errors and retains the local draft for correction", async () => {
    const editors = useEditorStore();
    const queries = useQueryStore();
    const edits = useResultEditStore();
    editors.patch("bootstrap-editor", {
      connection: completionProfile(), connectionState: "active",
      transactionDirty: true, transactionState: "active"
    });
    queries.start("bootstrap-editor", "execution-edit");
    queries.addResult("bootstrap-editor", {
      resultIndex: 0, sql: "select id, order_status from orders for update", type: "QUERY",
      columns: ["id", "order_status"], rows: [["1", "NEW"]],
      mutationTarget: {
        qualifiedName: "`orders`", editableForUpdate: true,
        columns: [
          { resultIndex: 0, name: "id", quotedName: "`id`", jdbcType: -5 },
          { resultIndex: 1, name: "order_status", quotedName: "`order_status`", jdbcType: 12 }
        ],
        uniqueKeys: [{ name: "PRIMARY", primary: true, resultColumnIndices: [0] }]
      },
      updateCount: -1, truncated: false, durationMs: 3, complete: true
    });
    queries.complete("bootstrap-editor", { durationMs: 3 });
    edits.setUnlocked("bootstrap-editor", "execution-edit", 0, true);
    edits.stage("bootstrap-editor", "execution-edit", 0, 0, 1, "NEW", "UNKNOWN");
    const errorMessage = "确认结果修改失败：order_status 不在允许的枚举值中";
    rpcRequest.mockRejectedValueOnce(new Error(errorMessage));
    const error = vi.spyOn(ElMessage, "error").mockImplementation(() => undefined as never);
    await nextTick();

    const post = wrapper.get('button[aria-label="应用更改"]');
    expect(post.classes()).toContain("el-button--success");
    await post.trigger("click");
    await flushPromises();

    expect(error).toHaveBeenCalledWith(errorMessage);
    expect(edits.hasPending("bootstrap-editor")).toBe(true);
    expect(rpcRequest).toHaveBeenCalledWith("query.applyChanges", {
      editorId: "bootstrap-editor", executionId: "execution-edit", resultIndex: 0,
      operations: [{ operationId: "update:row:0", kind: "update", rowId: undefined, rowIndex: 0,
        values: [{ columnIndex: 1, value: { kind: "text", value: "UNKNOWN" } }] }]
    }, 30_000);
  });

  it("applies local result drafts before executing without committing the transaction", async () => {
    const edits = seedEditableResult(true);
    rpcRequest.mockImplementation(async (type: string) => {
      if (type === "query.applyChanges") return {
        appliedOperationIds: ["update:row-1"], hiddenOperationIds: [],
        rowPatches: [{ operationId: "update:row-1", kind: "update", rowIndex: 0,
          rowId: "row-1", row: ["1", "after"] }]
      };
      if (type === "query.execute") return { executionId: "execution-next" };
      return {};
    });

    const execution = executeFromApp(wrapper);
    await expectResultDecision("1 项修改尚未应用");
    clickMessageBoxButton("应用");
    await execution;
    await flushPromises();

    const requestTypes = rpcRequest.mock.calls.map(([type]) => type);
    expect(requestTypes.indexOf("query.applyChanges")).toBeLessThan(requestTypes.indexOf("query.execute"));
    expect(requestTypes).not.toContain("transaction.commit");
    const executePayload = rpcRequest.mock.calls.find(([type]) => type === "query.execute")?.[1];
    expect(executePayload).not.toHaveProperty("resultTransactionAction");
    expect(edits.hasChanges("bootstrap-editor")).toBe(false);
    expect(useEditorStore().active).toMatchObject({ transactionDirty: true, resultChangesDirty: false });
  });

  it("ignores local drafts, cleans large values and executes without applying them", async () => {
    const edits = seedEditableResult(true);
    edits.stageMutation("bootstrap-editor", "execution-edit", 0, 0, 1, "before",
      { kind: "largeValueToken", value: "cell-token" }, "row-1");
    edits.addInsert("bootstrap-editor", "execution-edit", 0, "draft:new", 1, [0, 1]);
    edits.stageMutation("bootstrap-editor", "execution-edit", 0, 1, 1, null,
      { kind: "largeValueToken", value: "insert-token" }, "draft:new");
    useQueryStore().appendDraftRow("bootstrap-editor", 0, "draft:new", [null, null]);
    rpcRequest.mockImplementation(async (type: string) => type === "query.execute"
      ? { executionId: "execution-next" } : {});

    const execution = executeFromApp(wrapper);
    await expectResultDecision("2 项修改尚未应用");
    clickMessageBoxButton("忽略");
    await execution;
    await flushPromises();

    expect(rpcRequest.mock.calls.map(([type]) => type)).not.toContain("query.applyChanges");
    expect(rpcRequest).toHaveBeenCalledWith("query.execute", expect.any(Object));
    expect(rpcMock.deleteResultLargeValueDraft).toHaveBeenCalledTimes(2);
    expect(edits.hasChanges("bootstrap-editor")).toBe(false);
    expect(useEditorStore().active?.transactionDirty).toBe(true);
  });

  it("keeps local drafts and does not execute when result confirmation is cancelled", async () => {
    const edits = seedEditableResult(true);
    const execution = executeFromApp(wrapper);
    await expectResultDecision("似乎还有数据修改后没有应用，请先确认");
    clickMessageBoxButton("取消");
    await execution;
    await flushPromises();

    expect(rpcRequest.mock.calls.map(([type]) => type)).not.toContain("query.execute");
    expect(edits.hasPending("bootstrap-editor")).toBe(true);
    expect(useEditorStore().active?.busy).toBe(false);
  });

  it("does not execute when applying drafts fails", async () => {
    const edits = seedEditableResult(true);
    rpcRequest.mockImplementation(async (type: string) => {
      if (type === "query.applyChanges") throw new Error("应用失败");
      if (type === "query.execute") return { executionId: "must-not-run" };
      return {};
    });
    const error = vi.spyOn(ElMessage, "error").mockImplementation(() => undefined as never);

    const execution = executeFromApp(wrapper);
    await expectResultDecision("未应用的数据修改");
    clickMessageBoxButton("应用");
    await execution;
    await flushPromises();

    expect(error).toHaveBeenCalledWith("应用失败");
    expect(rpcRequest.mock.calls.map(([type]) => type)).not.toContain("query.execute");
    expect(edits.hasPending("bootstrap-editor")).toBe(true);
  });

  it("executes directly when result changes are already applied", async () => {
    const edits = seedEditableResult(true);
    edits.markPosted("bootstrap-editor", "execution-edit", 0);
    useEditorStore().patch("bootstrap-editor", { resultChangesDirty: true });
    rpcRequest.mockImplementation(async (type: string) => type === "query.execute"
      ? { executionId: "execution-next" } : {});

    await executeFromApp(wrapper);
    await flushPromises();

    expect(document.body.querySelector(".result-transaction-decision")).toBeNull();
    expect(rpcRequest).toHaveBeenCalledWith("query.execute", expect.not.objectContaining({
      resultTransactionAction: expect.anything()
    }));
    expect(rpcRequest.mock.calls.map(([type]) => type)).not.toContain("transaction.commit");
    expect(useEditorStore().active?.transactionDirty).toBe(true);
  });

  it("executes directly when the result has no modifications", async () => {
    seedEditableResult(false);
    rpcRequest.mockImplementation(async (type: string) => type === "query.execute"
      ? { executionId: "execution-next" } : {});

    await executeFromApp(wrapper);
    await flushPromises();

    expect(document.body.querySelector(".result-transaction-decision")).toBeNull();
    expect(rpcRequest).toHaveBeenCalledWith("query.execute", expect.any(Object));
  });

  it("keeps edit mode open until pending drafts are applied or undone", async () => {
    const edits = seedEditableResult(true);
    const warning = vi.spyOn(ElMessage, "warning").mockImplementation(() => undefined as never);
    await nextTick();

    await wrapper.get('button[aria-label="切换结果编辑模式"]').trigger("click");
    expect(warning).toHaveBeenCalledWith("仍有未应用的修改，请先应用或撤销后再退出编辑模式");
    expect(edits.session("bootstrap-editor", "execution-edit", 0)?.unlocked).toBe(true);
    expect(wrapper.find('[aria-label="结果编辑操作"]').exists()).toBe(true);

    await wrapper.get('button[aria-label="撤销结果草稿"]').trigger("click");
    await wrapper.get('button[aria-label="切换结果编辑模式"]').trigger("click");
    expect(edits.session("bootstrap-editor", "execution-edit", 0)?.unlocked).toBe(false);
    expect(wrapper.find('[aria-label="结果编辑操作"]').exists()).toBe(false);
  });

  it("allows edit mode to close after changes are applied without changing the transaction", async () => {
    const edits = seedEditableResult(true);
    edits.markPosted("bootstrap-editor", "execution-edit", 0);
    useEditorStore().patch("bootstrap-editor", { resultChangesDirty: true });
    await nextTick();

    await wrapper.get('button[aria-label="切换结果编辑模式"]').trigger("click");
    expect(edits.session("bootstrap-editor", "execution-edit", 0)?.unlocked).toBe(false);
    expect(useEditorStore().active).toMatchObject({ transactionDirty: true, resultChangesDirty: true });
    expect(rpcRequest.mock.calls.map(([type]) => type)).not.toContain("transaction.commit");
    expect(rpcRequest.mock.calls.map(([type]) => type)).not.toContain("transaction.rollback");
  });

  it("runs result edit and single-record toolbar actions through configurable shortcuts", async () => {
    const settings = useSettingsStore();
    const edits = seedEditableResult(false);
    edits.setUnlocked("bootstrap-editor", "execution-edit", 0, false);
    settings.setShortcut("result.toggleEditMode", "F9");
    settings.setShortcut("result.toggleSingleRecord", "F10");
    await nextTick();
    const tooltipContents = wrapper.findAllComponents({ name: "ElTooltip" })
      .map((tooltip) => String(tooltip.props("content")));
    expect(tooltipContents.some((content) => content.includes("进入结果编辑模式") && content.includes("F9"))).toBe(true);
    expect(tooltipContents.some((content) => content.includes("单个记录查看") && content.includes("F10"))).toBe(true);

    document.body.dispatchEvent(new KeyboardEvent("keydown", {
      key: "F9", code: "F9", bubbles: true, cancelable: true,
    }));
    await nextTick();
    expect(edits.session("bootstrap-editor", "execution-edit", 0)?.unlocked).toBe(true);

    const table = wrapper.findComponent({ name: "ResultVirtualGrid" });
    table.vm.$emit("cell-pointerdown", {
      button: 0, preventDefault: vi.fn(), stopPropagation: vi.fn(),
      ctrlKey: false, metaKey: false, shiftKey: false,
    }, 0, 0);
    window.dispatchEvent(new Event("pointerup"));
    await nextTick();

    document.body.dispatchEvent(new KeyboardEvent("keydown", {
      key: "F10", code: "F10", bubbles: true, cancelable: true,
    }));
    await nextTick();
    expect(wrapper.findComponent({ name: "ResultSingleRecordView" }).exists()).toBe(true);

    document.body.dispatchEvent(new KeyboardEvent("keydown", {
      key: "F10", code: "F10", bubbles: true, cancelable: true,
    }));
    await nextTick();
    expect(wrapper.findComponent({ name: "ResultSingleRecordView" }).exists()).toBe(false);
  });

  it("persists virtual-grid render buffering and rolls it back when saving fails", async () => {
    const settings = useSettingsStore();
    const vm = wrapper.vm as unknown as {
      updateScrollOptimizationBufferScreens: (value: number) => Promise<void>;
    };
    rpcRequest.mockResolvedValueOnce({});
    await vm.updateScrollOptimizationBufferScreens(1.5);
    expect(settings.scrollOptimizationBufferScreens).toBe(1.5);
    expect(rpcRequest).toHaveBeenLastCalledWith("settings.update", {
      key: "result.scrollOptimizationBufferScreens", value: "1.5"
    });

    rpcRequest.mockRejectedValueOnce(new Error("save failed"));
    await vm.updateScrollOptimizationBufferScreens(2);
    expect(settings.scrollOptimizationBufferScreens).toBe(1.5);
  });

  it("persists editor display settings and rolls back failed saves", async () => {
    const settings = useSettingsStore();
    const vm = wrapper.vm as unknown as {
      updateMinimapEnabled: (value: boolean) => Promise<void>;
      updateWordWrapEnabled: (value: boolean) => Promise<void>;
    };

    rpcRequest.mockResolvedValueOnce({});
    await vm.updateMinimapEnabled(false);
    expect(settings.minimapEnabled).toBe(false);
    expect(rpcRequest).toHaveBeenLastCalledWith("settings.update", {
      key: "editor.minimapEnabled", value: "false"
    });

    rpcRequest.mockResolvedValueOnce({});
    await vm.updateWordWrapEnabled(true);
    expect(settings.wordWrapEnabled).toBe(true);
    expect(rpcRequest).toHaveBeenLastCalledWith("settings.update", {
      key: "editor.wordWrapEnabled", value: "true"
    });

    rpcRequest.mockRejectedValueOnce(new Error("save failed"));
    await vm.updateMinimapEnabled(true);
    expect(settings.minimapEnabled).toBe(false);
  });

  it("applies color schemes immediately and rolls back a failed save", async () => {
    const settings = useSettingsStore();
    const vm = wrapper.vm as unknown as {
      saveColorSchemes: (value: typeof DEFAULT_COLOR_SCHEMES) => Promise<void>;
    };
    const next = cloneColorSchemes(DEFAULT_COLOR_SCHEMES);
    next.light.editor.background = "#123456";
    next.light.result.selectionBorder = "#654321";

    rpcRequest.mockResolvedValueOnce({});
    await vm.saveColorSchemes(next);
    expect(settings.colorSchemes.light.editor.background).toBe("#123456");
    expect(rpcRequest).toHaveBeenLastCalledWith("settings.update", {
      key: "appearance.colorSchemes", value: serializeColorSchemeSettings(next),
    });

    const saved = cloneColorSchemes(settings.colorSchemes);
    const failed = cloneColorSchemes(saved);
    failed.light.editor.background = "#ABCDEF";
    rpcRequest.mockRejectedValueOnce(new Error("save failed"));
    await vm.saveColorSchemes(failed);
    expect(settings.colorSchemes).toEqual(saved);
  });

  it("formats a selection and compacts the whole document from icon toolbar buttons", async () => {
    const editors = useEditorStore();
    const formatButton = wrapper.get('button[aria-label="格式化 SQL"]');
    const compactButton = wrapper.get('button[aria-label="压缩 SQL"]');
    expect(formatButton.attributes("disabled")).toBeDefined();
    expect(compactButton.attributes("disabled")).toBeDefined();

    editors.patch("bootstrap-editor", {
      connection: completionProfile(),
      connectionState: "active",
      busy: false,
      executionPhase: "idle",
    });
    await nextTick();
    expect(formatButton.attributes("disabled")).toBeUndefined();
    expect(compactButton.attributes("disabled")).toBeUndefined();

    const selectedTarget: SqlTransformTarget = {
      modelKey: "bootstrap-editor",
      text: "select  1",
      range: { startLineNumber: 2, startColumn: 1, endLineNumber: 2, endColumn: 10 },
      versionId: 7,
      selected: true,
      cursorOffset: 18,
    };
    captureSqlTransformTarget.mockReturnValueOnce(selectedTarget);
    rpcRequest.mockResolvedValueOnce({ text: "SELECT 1" });
    await formatButton.trigger("click");
    await flushPromises();
    expect(rpcRequest).toHaveBeenLastCalledWith("sql.format", {
      editorId: "bootstrap-editor",
      text: "select  1",
    });
    expect(applySqlTransform).toHaveBeenLastCalledWith(selectedTarget, "SELECT 1");
    expect(editors.active?.dirty).toBe(true);

    const documentTarget: SqlTransformTarget = {
      modelKey: "bootstrap-editor",
      text: "SELECT *\nFROM orders",
      range: { startLineNumber: 1, startColumn: 1, endLineNumber: 2, endColumn: 12 },
      versionId: 8,
      selected: false,
      cursorOffset: 8,
    };
    captureSqlTransformTarget.mockReturnValueOnce(documentTarget);
    rpcRequest.mockResolvedValueOnce({ text: "SELECT * FROM orders" });
    await compactButton.trigger("click");
    await flushPromises();
    expect(rpcRequest).toHaveBeenLastCalledWith("sql.compact", {
      editorId: "bootstrap-editor",
      text: "SELECT *\nFROM orders",
    });
    expect(applySqlTransform).toHaveBeenLastCalledWith(documentTarget, "SELECT * FROM orders");
  });

  it("enables selection-only editor actions locally and keeps their toolbar order", async () => {
    const actionButtons = wrapper.findAll(".editor-actions button");
    expect(actionButtons.map((button) => button.attributes("aria-label"))).toEqual([
      "格式化 SQL",
      "压缩 SQL",
      "转换大写",
      "转换小写",
      "单行注释",
      "全部注释",
    ]);
    expect(wrapper.findAll(".editor-actions .el-divider--vertical")).toHaveLength(2);

    const localActionLabels = ["转换大写", "转换小写", "单行注释", "全部注释"];
    for (const label of localActionLabels) {
      expect(wrapper.get(`button[aria-label="${label}"]`).attributes("disabled")).toBeDefined();
    }

    wrapper.getComponent(MonacoEditorStub).vm.$emit("selection-change", true);
    await nextTick();
    expect(wrapper.get('button[aria-label="格式化 SQL"]').attributes("disabled")).toBeDefined();
    expect(wrapper.get('button[aria-label="压缩 SQL"]').attributes("disabled")).toBeDefined();
    for (const label of localActionLabels) {
      expect(wrapper.get(`button[aria-label="${label}"]`).attributes("disabled")).toBeUndefined();
      expect(wrapper.get(`button[aria-label="${label}"]`).find("svg").exists()).toBe(true);
      await wrapper.get(`button[aria-label="${label}"]`).trigger("click");
    }
    expect(runSelectionAction.mock.calls.map(([action]) => action)).toEqual([
      "uppercase",
      "lowercase",
      "lineComment",
      "blockComment",
    ]);
    expect(rpcRequest.mock.calls.some(([type]) => type === "sql.format" || type === "sql.compact")).toBe(false);

    wrapper.getComponent(MonacoEditorStub).vm.$emit("selection-change", false);
    await nextTick();
    for (const label of localActionLabels) {
      expect(wrapper.get(`button[aria-label="${label}"]`).attributes("disabled")).toBeDefined();
    }
  });

  it("runs editor display and compact actions through configurable shortcuts", async () => {
    const settings = useSettingsStore();
    const editors = useEditorStore();
    editors.patch("bootstrap-editor", {
      connection: completionProfile(),
      connectionState: "active",
      busy: false,
      executionPhase: "idle",
    });
    settings.setShortcut("editor.toggleMinimap", "Alt+M");
    settings.setShortcut("editor.toggleWordWrap", "Alt+R");
    settings.setShortcut("editor.compact", "F6");
    rpcRequest.mockResolvedValue({});

    document.body.dispatchEvent(new KeyboardEvent("keydown", {
      key: "m", code: "KeyM", altKey: true, bubbles: true, cancelable: true
    }));
    await flushPromises();
    expect(settings.minimapEnabled).toBe(false);
    expect(rpcRequest).toHaveBeenCalledWith("settings.update", {
      key: "editor.minimapEnabled", value: "false"
    });

    document.body.dispatchEvent(new KeyboardEvent("keydown", {
      key: "r", code: "KeyR", altKey: true, bubbles: true, cancelable: true
    }));
    await flushPromises();
    expect(settings.wordWrapEnabled).toBe(true);

    const target: SqlTransformTarget = {
      modelKey: "bootstrap-editor",
      text: "SELECT\n1",
      range: { startLineNumber: 1, startColumn: 1, endLineNumber: 2, endColumn: 2 },
      versionId: 4,
      selected: false,
      cursorOffset: 3,
    };
    captureSqlTransformTarget.mockReturnValueOnce(target);
    rpcRequest.mockResolvedValueOnce({ text: "SELECT 1" });
    document.body.dispatchEvent(new KeyboardEvent("keydown", {
      key: "F6", code: "F6", bubbles: true, cancelable: true
    }));
    await flushPromises();
    expect(rpcRequest).toHaveBeenCalledWith("sql.compact", {
      editorId: "bootstrap-editor", text: "SELECT\n1"
    });
  });

  it("reserves plain result-grid arrows before configurable global shortcuts", async () => {
    const settings = useSettingsStore();
    settings.minimapEnabled = true;
    settings.setShortcut("editor.toggleMinimap", "ArrowRight");
    const host = document.createElement("div");
    host.className = "table-host";
    document.body.appendChild(host);

    const plainArrow = new KeyboardEvent("keydown", {
      key: "ArrowRight", code: "ArrowRight", bubbles: true, cancelable: true
    });
    host.dispatchEvent(plainArrow);
    await flushPromises();
    expect(settings.minimapEnabled).toBe(true);
    expect(plainArrow.defaultPrevented).toBe(false);

    settings.setShortcut("editor.toggleMinimap", "Mod+ArrowRight");
    const modifiedArrow = new KeyboardEvent("keydown", {
      key: "ArrowRight", code: "ArrowRight", ctrlKey: true, bubbles: true, cancelable: true
    });
    host.dispatchEvent(modifiedArrow);
    await flushPromises();
    expect(settings.minimapEnabled).toBe(false);
    expect(modifiedArrow.defaultPrevented).toBe(true);
    host.remove();
  });

  it("runs configurable selection actions only while SQL text is selected", async () => {
    const settings = useSettingsStore();
    settings.setShortcut("editor.uppercase", "F2");
    settings.setShortcut("editor.lowercase", "F3");
    settings.setShortcut("editor.toggleLineComment", "F4");
    settings.setShortcut("editor.toggleBlockComment", "F6");
    await nextTick();
    const tooltips = wrapper.findAllComponents({ name: "ElTooltip" })
      .map((tooltip) => String(tooltip.props("content")));
    expect(tooltips).toContain("转换大写 · F2");
    expect(tooltips).toContain("转换小写 · F3");
    expect(tooltips).toContain("单行注释 · F4");
    expect(tooltips).toContain("全部注释 · F6");

    document.body.dispatchEvent(new KeyboardEvent("keydown", {
      key: "F2", code: "F2", bubbles: true, cancelable: true,
    }));
    expect(runSelectionAction).not.toHaveBeenCalled();

    wrapper.getComponent(MonacoEditorStub).vm.$emit("selection-change", true);
    await nextTick();
    for (const key of ["F2", "F3", "F4", "F6"]) {
      document.body.dispatchEvent(new KeyboardEvent("keydown", {
        key, code: key, bubbles: true, cancelable: true,
      }));
    }
    await nextTick();
    expect(runSelectionAction.mock.calls.map(([action]) => action)).toEqual([
      "uppercase",
      "lowercase",
      "lineComment",
      "blockComment",
    ]);

    wrapper.getComponent(MonacoEditorStub).vm.$emit("selection-change", false);
    await nextTick();
    document.body.dispatchEvent(new KeyboardEvent("keydown", {
      key: "F2", code: "F2", bubbles: true, cancelable: true,
    }));
    expect(runSelectionAction).toHaveBeenCalledTimes(4);
  });

  it("does not overwrite or dirty an editor after a stale transform response", async () => {
    const editors = useEditorStore();
    editors.patch("bootstrap-editor", {
      connection: completionProfile(),
      connectionState: "active",
      dirty: false,
      busy: false,
      executionPhase: "idle",
    });
    await nextTick();
    const target: SqlTransformTarget = {
      modelKey: "bootstrap-editor",
      text: "select 1",
      range: { startLineNumber: 1, startColumn: 1, endLineNumber: 1, endColumn: 9 },
      versionId: 3,
      selected: false,
      cursorOffset: 4,
    };
    captureSqlTransformTarget.mockReturnValueOnce(target);
    applySqlTransform.mockReturnValueOnce("stale");
    rpcRequest.mockResolvedValueOnce({ text: "SELECT 1" });

    await wrapper.get('button[aria-label="格式化 SQL"]').trigger("click");
    await flushPromises();

    expect(editors.active?.dirty).toBe(false);
    expect(applySqlTransform).toHaveBeenCalledWith(target, "SELECT 1");
  });

  it("persists SQL completion matching and snippets with confirmed-value rollback", async () => {
    const settings = useSettingsStore();
    const vm = wrapper.vm as unknown as {
      updateCompletionPreciseMatchingEnabled: (value: boolean) => void;
      updateCompletionSnippets: (value: Array<{ id: string; trigger: string;
        remarks: string; sql: string }>) => void;
    };
    rpcRequest.mockResolvedValueOnce({});
    vm.updateCompletionPreciseMatchingEnabled(true);
    expect(settings.completionPreciseMatchingEnabled).toBe(true);
    await flushPromises();
    expect(rpcRequest).toHaveBeenCalledWith("settings.update", {
      key: "editor.completionPreciseMatchingEnabled", value: "true"
    });

    rpcRequest.mockRejectedValueOnce(new Error("save failed"));
    vm.updateCompletionPreciseMatchingEnabled(false);
    expect(settings.completionPreciseMatchingEnabled).toBe(false);
    await flushPromises();
    expect(settings.completionPreciseMatchingEnabled).toBe(true);

    const snippet = { id: "5d652bad-8dce-4b56-9c94-d62d74a74576",
      trigger: "sf", remarks: "通用查询", sql: "select * from" };
    rpcRequest.mockResolvedValueOnce({});
    vm.updateCompletionSnippets([snippet]);
    expect(settings.completionSnippets).toEqual([snippet]);
    await flushPromises();
    expect(rpcRequest).toHaveBeenCalledWith("settings.update", {
      key: "editor.completionSnippets",
      value: JSON.stringify([snippet])
    });

    rpcRequest.mockRejectedValueOnce(new Error("save failed"));
    vm.updateCompletionSnippets([{ ...snippet, remarks: "修改后" }]);
    expect(settings.completionSnippets[0].remarks).toBe("修改后");
    await flushPromises();
    expect(settings.completionSnippets).toEqual([snippet]);
  });

  it("uses F8 and F7 for execution, preserves editing shortcuts, and suppresses dangerous legacy keys", async () => {
    const editors = useEditorStore();
    editors.patch("bootstrap-editor", {
      content: "select 1",
      connection: completionProfile(),
      connectionState: "active",
      busy: false,
      executionPhase: "idle",
    });
    rpcRequest.mockImplementation(async (type: string) => {
      if (type === "query.execute") return { executionId: `execution-${rpcRequest.mock.calls.length}` };
      return {};
    });
    await nextTick();
    expect(useSettingsStore().shortcuts["query.executeCurrent"]).toBe("F8");
    expect(useSettingsStore().shortcuts["query.executeCurrentNewTab"]).toBeNull();
    expect((wrapper.vm as unknown as { canExecute: boolean }).canExecute).toBe(true);

    const f8Event = new KeyboardEvent("keydown", { key: "F8", code: "F8", bubbles: true, cancelable: true });
    document.body.dispatchEvent(f8Event);
    await flushPromises();
    expect(rpcRequest).toHaveBeenCalledWith("query.execute", expect.objectContaining({
      editorId: "bootstrap-editor", scope: "current",
    }));

    editors.patch("bootstrap-editor", { busy: false, activeExecutionId: undefined, executionPhase: "idle" });
    document.body.dispatchEvent(new KeyboardEvent("keydown", { key: "F7", code: "F7", bubbles: true, cancelable: true }));
    await flushPromises();
    expect(rpcRequest).toHaveBeenCalledWith("query.execute", expect.objectContaining({
      editorId: "bootstrap-editor", scope: "script",
    }));

    const executionCalls = () => rpcRequest.mock.calls.filter(([type]) => type === "query.execute").length;
    const previousCount = executionCalls();
    document.body.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", code: "Enter", metaKey: true, bubbles: true, cancelable: true }));
    await flushPromises();
    expect(executionCalls()).toBe(previousCount);

    editors.patch("bootstrap-editor", { busy: false, activeExecutionId: undefined, executionPhase: "idle" });
    const settings = useSettingsStore();
    settings.setShortcut("query.executeCurrent", "F9");
    document.body.dispatchEvent(new KeyboardEvent("keydown", { key: "F8", code: "F8", bubbles: true, cancelable: true }));
    await flushPromises();
    expect(executionCalls()).toBe(previousCount);
    document.body.dispatchEvent(new KeyboardEvent("keydown", { key: "F9", code: "F9", bubbles: true, cancelable: true }));
    await flushPromises();
    expect(executionCalls()).toBe(previousCount + 1);

    expect(document.body.dispatchEvent(new KeyboardEvent("keydown", {
      key: "c", code: "KeyC", metaKey: true, bubbles: true, cancelable: true,
    }))).toBe(true);
    expect(document.body.dispatchEvent(new KeyboardEvent("keydown", {
      key: "F5", code: "F5", bubbles: true, cancelable: true,
    }))).toBe(false);
  });

  it("executes the current statement in a new result tab without replacing the original result", async () => {
    const connections = useConnectionStore();
    const editors = useEditorStore();
    const queries = useQueryStore();
    const profile = completionProfile();
    connections.initialize([], [profile], [{ id: "system-1", name: "核心系统", revision: "1" }],
      [{ id: "environment-dev", systemId: "system-1", name: "DEV", revision: "1" }]);
    editors.patch("bootstrap-editor", { content: "SELECT 1;\nSELECT 2;", connection: profile,
      connectionState: "active", busy: false, executionPhase: "idle" });
    queries.start("bootstrap-editor", "execution-original");
    queries.addResult("bootstrap-editor", { resultIndex: 0, sql: "SELECT 1", type: "QUERY", columns: ["id"],
      rows: [["1"]], updateCount: -1, truncated: false, durationMs: 1, complete: true });
    queries.complete("bootstrap-editor", { durationMs: 1 });
    Object.assign(wrapper.findComponent({ name: "MonacoEditor" }).vm, {
      getValue: () => "SELECT 1;\nSELECT 2;"
    });
    rpcRequest.mockImplementation(async (type: string) => {
      if (type === "query.execute") return { executionId: "execution-temporary" };
      if (type === "metadata.completionNamespaces") return completionNamespaces();
      return {};
    });

    await (wrapper.vm as unknown as {
      executeCurrentInNewTab: (selectedText: string, cursorOffset: number) => Promise<void>
    }).executeCurrentInNewTab("", 12);
    await flushPromises();

    expect(rpcRequest).toHaveBeenCalledWith("query.execute", expect.objectContaining({
      editorId: "bootstrap-editor", text: "SELECT 1;\nSELECT 2;", scope: "current", cursorOffset: 12,
      resultPresentation: "append"
    }));
    expect(editors.activeId).toBe("bootstrap-editor");
    expect(queries.executionList("bootstrap-editor").map((item) => item.executionId))
      .toEqual(["execution-original", "execution-temporary"]);
  });

  it("shows a warning and resets loading when risk SQL requires a second execution", async () => {
    const editors = useEditorStore();
    editors.patch("bootstrap-editor", { content: "UPDATE orders SET status = 'closed'", connection: completionProfile(),
      connectionState: "active", busy: false, executionPhase: "idle" });
    rpcRequest.mockRejectedValueOnce(Object.assign(new Error("risk"), { code: "RISK_REEXECUTION_REQUIRED" }));
    const warning = vi.spyOn(ElMessage, "warning").mockImplementation(() => undefined as never);
    const error = vi.spyOn(ElMessage, "error").mockImplementation(() => undefined as never);

    await executeFromApp(wrapper);

    expect(warning).toHaveBeenCalledWith("当前语句未包含 WHERE，可能影响大量数据；请再次执行以继续");
    expect(error).not.toHaveBeenCalled();
    expect(editors.active).toMatchObject({ busy: false, executionPhase: "idle", executionStartedAt: undefined });
  });

  it("applies a changed shortcut immediately and rolls back a failed save", async () => {
    const settings = useSettingsStore();
    const vm = wrapper.vm as unknown as {
      updateShortcutBinding: (actionId: "query.executeCurrent", binding: string | null) => void;
    };
    rpcRequest.mockRejectedValueOnce(new Error("save failed"));
    vm.updateShortcutBinding("query.executeCurrent", "F9");
    expect(settings.shortcuts["query.executeCurrent"]).toBe("F9");
    await nextTick();
    expect(wrapper.findAllComponents({ name: "ElTooltip" })
      .some((tooltip) => String(tooltip.props("content")).includes("F9"))).toBe(true);
    await flushPromises();
    expect(settings.shortcuts["query.executeCurrent"]).toBe("F8");
    expect(rpcRequest).toHaveBeenCalledWith("settings.update", {
      key: "keyboard.shortcuts",
      value: expect.stringContaining('"query.executeCurrent":"F9"'),
    });
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
    editors.add({ id: "editor-1", title: "查询 1", content: "", dirty: false, transactionDirty: false, busy: false,
      executionPhase: "idle", transactionOperation: "idle", connectionState: "unbound" });
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

  it("replaces execute with cancel while loading a page and interrupts the registered operation", async () => {
    let finishRequest: ((value: unknown) => void) | undefined;
    rpcRequest.mockImplementation(async (type: string) => {
      if (type === "query.cancel") return { cancelled: true };
      if (type !== "query.fetchRows") return {};
      return await new Promise((resolve) => { finishRequest = resolve; });
    });
    const editors = useEditorStore();
    const queries = useQueryStore();
    editors.add({ id: "editor-1", title: "查询 1", content: "", dirty: false, transactionDirty: false, busy: false,
      executionPhase: "idle", transactionOperation: "idle", connectionState: "unbound" });
    queries.start("editor-1", "execution-1");
    queries.addResult("editor-1", { resultIndex: 0, sql: "select 1", type: "QUERY", columns: ["id"], rows: [["1"]],
      updateCount: -1, truncated: true, durationMs: 3, complete: true });
    queries.complete("editor-1", { durationMs: 3 });
    await nextTick();

    await wrapper.get('button[aria-label="下一页数据"]').trigger("click");
    await flushPromises();
    const pageCall = rpcRequest.mock.calls.find(([type]) => type === "query.fetchRows");
    const executionId = String(pageCall?.[1]?.executionId);
    let cancel = wrapper.get('button[aria-label="取消执行"]');
    expect(cancel.classes()).toContain("el-button--warning");
    expect(cancel.attributes("disabled")).toBeDefined();

    rpcMock.listeners.get("query.pageStarted")?.forEach((listener) => listener({
      editorId: "editor-1", executionId, resultIndex: 0
    }));
    await nextTick();
    cancel = wrapper.get('button[aria-label="取消执行"]');
    expect(cancel.attributes("disabled")).toBeUndefined();
    await cancel.trigger("click");
    await flushPromises();
    expect(rpcRequest).toHaveBeenCalledWith("query.cancel", { editorId: "editor-1", executionId });
    expect(wrapper.get('button[aria-label="取消执行"]').classes()).toContain("is-loading");

    finishRequest?.({ executionId, resultIndex: 0, offset: 1, rows: [["partial"]],
      hasMore: true, nextOffset: 2, cancelled: true });
    await flushPromises();
    expect(queries.executions["editor-1"].results[0].rows).toEqual([["1"]]);
    expect(wrapper.find('button[aria-label="取消执行"]').exists()).toBe(false);
    expect(wrapper.find(".execute-control.el-dropdown").exists()).toBe(true);

    await wrapper.get('button[aria-label="下一页数据"]').trigger("click");
    await flushPromises();
    const latestPageCall = rpcRequest.mock.calls.filter(([type]) => type === "query.fetchRows").at(-1);
    const keyboardExecutionId = String(latestPageCall?.[1]?.executionId);
    rpcMock.listeners.get("query.pageStarted")?.forEach((listener) => listener({
      editorId: "editor-1", executionId: keyboardExecutionId, resultIndex: 0
    }));
    await nextTick();
    document.body.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", shiftKey: true, bubbles: true }));
    await flushPromises();
    expect(rpcRequest).toHaveBeenCalledWith("query.cancel", {
      editorId: "editor-1", executionId: keyboardExecutionId
    });
    finishRequest?.({ executionId: keyboardExecutionId, resultIndex: 0, offset: 1, rows: [],
      hasMore: true, nextOffset: 1, cancelled: true });
    await flushPromises();
  });

  it("stops fetching all after cancellation and retains every completed batch", async () => {
    let pageRequestCount = 0;
    let finishSecondPage: ((value: unknown) => void) | undefined;
    rpcRequest.mockImplementation(async (type: string, payload: Record<string, unknown>) => {
      if (type === "query.cancel") return { cancelled: false };
      if (type !== "query.fetchRows") return {};
      pageRequestCount++;
      if (pageRequestCount === 1) {
        return { executionId: payload.executionId, resultIndex: 0, offset: 1, rows: [["2"]],
          hasMore: true, nextOffset: 2, cancelled: false };
      }
      return await new Promise((resolve) => { finishSecondPage = resolve; });
    });
    const editors = useEditorStore();
    const queries = useQueryStore();
    editors.add({ id: "editor-1", title: "查询 1", content: "", dirty: false, transactionDirty: false, busy: false,
      executionPhase: "idle", transactionOperation: "idle", connectionState: "unbound" });
    queries.start("editor-1", "execution-1");
    queries.addResult("editor-1", { resultIndex: 0, sql: "select 1", type: "QUERY", columns: ["id"], rows: [["1"]],
      updateCount: -1, truncated: true, durationMs: 3, complete: true });
    queries.complete("editor-1", { durationMs: 3 });
    await nextTick();

    await wrapper.get('button[aria-label="获取全部数据"]').trigger("click");
    await flushPromises();
    const pageCall = rpcRequest.mock.calls.find(([type]) => type === "query.fetchRows");
    const executionId = String(pageCall?.[1]?.executionId);
    rpcMock.listeners.get("query.pageStarted")?.forEach((listener) => listener({
      editorId: "editor-1", executionId, resultIndex: 0
    }));
    await nextTick();
    await wrapper.get('button[aria-label="取消执行"]').trigger("click");
    await flushPromises();
    finishSecondPage?.({ executionId, resultIndex: 0, offset: 2, rows: [["3"]],
      hasMore: true, nextOffset: 3, cancelled: false });
    await flushPromises();

    expect(pageRequestCount).toBe(2);
    expect(queries.executions["editor-1"].results[0].rows).toEqual([["1"], ["2"], ["3"]]);
    expect(wrapper.find('button[aria-label="取消执行"]').exists()).toBe(false);
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
      executionPhase: "idle", transactionOperation: "idle", connection: profile, connectionState: "active" });
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
      if (type === "metadata.completionNamespaces") return completionNamespaces(profile.id);
      return {};
    });

    const vm = wrapper.vm as unknown as { connectionSelectionChanged: (value: string) => Promise<void>;
      completeSchemaSelection: (value: CompletionNamespaceDescriptor[]) => void };
    await vm.connectionSelectionChanged(`${profile.id}@${profile.revision}`);
    await flushPromises();
    vm.completeSchemaSelection(completionNamespaces(profile.id).namespaces);
    await flushPromises();
    expect(completionMock.refresh).toHaveBeenCalledTimes(1);

    editors.add({ id: "editor-same-environment", title: "查询 2", content: "", dirty: false,
      transactionDirty: false, busy: false, executionPhase: "idle", transactionOperation: "idle",
      connectionState: "unbound" });
    await vm.connectionSelectionChanged(`${profile.id}@${profile.revision}`);
    await flushPromises();
    expect(completionMock.refresh).toHaveBeenCalledTimes(1);

    editors.activeId = "bootstrap-editor";
    await nextTick();
    expect(completionMock.refresh).toHaveBeenCalledTimes(1);
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
      if (type === "metadata.completionNamespaces") return completionNamespaces(profile.id);
      return {};
    });

    const vm = wrapper.vm as unknown as { profileSaved: (value: SavedProfile) => Promise<void>;
      completeSchemaSelection: (value: CompletionNamespaceDescriptor[]) => void };
    await vm.profileSaved(profile);
    await flushPromises();
    vm.completeSchemaSelection(completionNamespaces(profile.id).namespaces);
    await flushPromises();

    expect(completionMock.refresh).toHaveBeenCalledWith(expect.objectContaining({
      body: expect.objectContaining({ profileId: profile.id })
    }));
    expect(useMetadataStore().completionFor("system-1:environment-dev:mysql")?.hasSnapshot).toBe(true);
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
    const cacheKey = "system-1:environment-dev:mysql";
    metadata.beginCompletion(cacheKey, "DEV", "initial", profile.id);
    metadata.completeCompletion(cacheKey, "initial", completionSummary(profile.id));
    completionMock.refresh.mockResolvedValueOnce(completionSummary(profile.id, 2, 5));
    rpcRequest.mockImplementation(async (type: string) => {
      if (type === "metadata.completionNamespaces") return completionNamespaces(profile.id);
      return {};
    });

    const vm = wrapper.vm as unknown as { refreshCompletionFromObjectExplorer: () => void;
      completeSchemaSelection: (value: CompletionNamespaceDescriptor[]) => void };
    vm.refreshCompletionFromObjectExplorer();
    await flushPromises();
    vm.completeSchemaSelection(completionNamespaces(profile.id).namespaces);
    await flushPromises();

    expect(completionMock.refresh).toHaveBeenCalledWith(expect.objectContaining({
      body: expect.objectContaining({ editorId: "bootstrap-editor" })
    }));
    expect(metadata.completionFor(cacheKey)?.summary?.objectCount).toBe(2);
  });

  it("在全局状态栏展示当前环境的补全进度、成功和失败状态", async () => {
    await flushPromises();
    const connections = useConnectionStore();
    const editors = useEditorStore();
    const metadata = useMetadataStore();
    const profile = completionProfile();
    connections.initialize([], [profile], [{ id: "system-1", name: "核心系统", revision: "1" }],
      [{ id: "environment-dev", systemId: "system-1", name: "DEV", revision: "1" }]);
    editors.patch("bootstrap-editor", { connection: profile, connectionState: "active" });
    await nextTick();

    metadata.beginCompletion("system-1:environment-dev", "DEV", "status-load", profile.id);
    metadata.updateProgress({ loadId: "status-load", phase: "loading", completed: 37, total: 240,
      message: "sales.orders", environmentId: "environment-dev" });
    await nextTick();
    expect(wrapper.find(".system-status-summary").text()).toContain("37/240 · sales.orders");

    metadata.completeCompletion("system-1:environment-dev", "status-load", completionSummary(profile.id));
    await nextTick();
    expect(wrapper.find(".system-status-summary").text()).toContain("补全已更新 · 1 项");

    metadata.beginCompletion("system-1:environment-dev", "DEV", "failed-load", profile.id, true);
    metadata.failCompletion("system-1:environment-dev", "failed-load", "连接失败");
    await nextTick();
    expect(wrapper.find(".system-status-summary").text()).toContain("连接失败");
  });

  it("uses the completion worker to enrich result remarks without requesting metadata", async () => {
    const connections = useConnectionStore();
    const editors = useEditorStore();
    const queries = useQueryStore();
    const profile = completionProfile();
    connections.initialize([], [profile], [{ id: "system-1", name: "核心系统", revision: "1" }],
      [{ id: "environment-dev", systemId: "system-1", name: "DEV", revision: "1" }]);
    editors.patch("bootstrap-editor", { connection: profile, connectionState: "active" });
    completionMock.resolveResultColumnRemarks.mockResolvedValueOnce([{ index: 0, remarks: "订单编号" }]);
    rpcRequest.mockClear();

    rpcMock.listeners.get("query.started")?.forEach((listener) => listener({ editorId: "bootstrap-editor", executionId: "execution-remarks" }));
    rpcMock.listeners.get("query.resultMeta")?.forEach((listener) => listener({ editorId: "bootstrap-editor", resultIndex: 0,
      sql: "select id from orders", type: "QUERY", columns: ["id"], columnDetails: [
        { label: "id", name: "id", remarks: "", catalog: "sales", schema: "", table: "orders", typeName: "BIGINT" }
      ], rows: [], updateCount: -1, truncated: false, durationMs: 0, complete: false }));
    await flushPromises();

    expect(completionMock.resolveResultColumnRemarks).toHaveBeenCalledWith(
      "system-1:environment-dev:mysql", "mysql", "select id from orders", [{
        index: 0, catalog: "sales", schema: "", table: "orders", name: "id"
      }]);
    expect(queries.executions["bootstrap-editor"].results[0].columnDetails?.[0].remarks).toBe("订单编号");
    expect(rpcRequest).not.toHaveBeenCalledWith("metadata.completionNamespaces", expect.anything(), expect.anything());
  });

  it("ignores rows and completion events from a retired JDBC execution", async () => {
    const queries = useQueryStore();
    queries.start("bootstrap-editor", "execution-current");

    rpcMock.listeners.get("query.resultMeta")?.forEach((listener) => listener({
      editorId: "bootstrap-editor", executionId: "execution-retired", resultIndex: 0,
      sql: "select 'old'", type: "QUERY", columns: ["value"], rows: [],
      updateCount: -1, truncated: false, durationMs: 0, complete: false
    }));
    expect(queries.executions["bootstrap-editor"].results).toHaveLength(0);

    rpcMock.listeners.get("query.resultMeta")?.forEach((listener) => listener({
      editorId: "bootstrap-editor", executionId: "execution-current", resultIndex: 0,
      sql: "select 'new'", type: "QUERY", columns: ["value"], rows: [],
      updateCount: -1, truncated: false, durationMs: 0, complete: false
    }));
    rpcMock.listeners.get("query.rows")?.forEach((listener) => listener({
      editorId: "bootstrap-editor", executionId: "execution-retired", resultIndex: 0, rows: [["old"]]
    }));
    rpcMock.listeners.get("query.resultComplete")?.forEach((listener) => listener({
      editorId: "bootstrap-editor", executionId: "execution-retired", resultIndex: 0, durationMs: 99
    }));
    expect(queries.executions["bootstrap-editor"].results[0].rows).toEqual([]);
    expect(queries.executions["bootstrap-editor"].results[0].complete).toBe(false);

    rpcMock.listeners.get("query.rows")?.forEach((listener) => listener({
      editorId: "bootstrap-editor", executionId: "execution-current", resultIndex: 0, rows: [["new"]]
    }));
    expect(queries.executions["bootstrap-editor"].results[0].rows).toEqual([["new"]]);
  });

  it("retires result editing and marks the transaction unknown after a task-manager abort", async () => {
    const editors = useEditorStore();
    const queries = useQueryStore();
    const edits = useResultEditStore();
    editors.patch("bootstrap-editor", { busy: true, activeExecutionId: "execution-aborted",
      executionPhase: "running", transactionDirty: true, transactionState: "active",
      resultChangesDirty: true, connectionState: "active" });
    queries.start("bootstrap-editor", "execution-aborted");
    queries.addResult("bootstrap-editor", { resultIndex: 0, sql: "select 1 for update", type: "QUERY",
      columns: ["value"], rows: [["1"]], updateCount: -1, truncated: false,
      durationMs: 1, complete: true });
    edits.setUnlocked("bootstrap-editor", "execution-aborted", 0, true);

    rpcMock.listeners.get("jdbc.connectionAborted")?.forEach((listener) => listener({
      editorId: "bootstrap-editor", executionId: "execution-aborted", transactionLost: true,
      resultChangesLost: true, message: "连接已被任务管理器强制断开"
    }));
    await nextTick();

    expect(editors.active).toMatchObject({ busy: false, transactionDirty: false,
      transactionState: "lost", connectionState: "ready" });
    expect(queries.executions["bootstrap-editor"].historical).toBe(true);
    expect(edits.session("bootstrap-editor", "execution-aborted", 0)).toBeUndefined();
  });

  it("resolves Oracle result remarks when JDBC omits table and schema metadata", async () => {
    const connections = useConnectionStore();
    const editors = useEditorStore();
    const queries = useQueryStore();
    const profile: SavedProfile = { ...completionProfile(), providerId: "oceanbase-oracle" };
    connections.initialize([], [profile], [{ id: "system-1", name: "核心系统", revision: "1" }],
      [{ id: "environment-dev", systemId: "system-1", name: "DEV", revision: "1" }]);
    editors.patch("bootstrap-editor", { connection: profile, connectionState: "active" });
    completionMock.resolveResultColumnRemarks.mockResolvedValueOnce([{ index: 0, remarks: "客户编号" }]);

    rpcMock.listeners.get("query.started")?.forEach((listener) => listener({
      editorId: "bootstrap-editor", executionId: "execution-oracle-remarks"
    }));
    rpcMock.listeners.get("query.resultMeta")?.forEach((listener) => listener({
      editorId: "bootstrap-editor", resultIndex: 0,
      sql: "select * from CBSAC.CUSTOMERS a", type: "QUERY", columns: ["ID"], columnDetails: [
        { label: "ID", name: "ID", remarks: "", catalog: "", schema: "", table: "", typeName: "NUMBER" }
      ], rows: [], updateCount: -1, truncated: false, durationMs: 0, complete: false
    }));
    await flushPromises();

    expect(completionMock.resolveResultColumnRemarks).toHaveBeenCalledWith(
      "system-1:environment-dev:oceanbase-oracle", "oceanbase-oracle",
      "select * from CBSAC.CUSTOMERS a", [
        { index: 0, catalog: "", schema: "", table: "", name: "ID" }
      ]);
    expect(queries.executions["bootstrap-editor"].results[0].columnDetails?.[0].remarks).toBe("客户编号");
  });

  it("requests deferred Oracle column types only after a query result succeeds", async () => {
    const connections = useConnectionStore();
    const editors = useEditorStore();
    const profile: SavedProfile = {
      ...completionProfile(),
      providerId: "oracle",
      name: "Oracle业务库"
    };
    connections.initialize([], [profile], [{ id: "system-1", name: "核心系统", revision: "1" }],
      [{ id: "environment-dev", systemId: "system-1", name: "DEV", revision: "1" }]);
    editors.patch("bootstrap-editor", {
      connection: profile, connectionState: "active", busy: true, executionPhase: "starting"
    });

    rpcMock.listeners.get("query.started")?.forEach((listener) => listener({
      editorId: "bootstrap-editor",
      executionId: "execution-structure"
    }));
    rpcMock.listeners.get("query.resultMeta")?.forEach((listener) => listener({
      editorId: "bootstrap-editor",
      resultIndex: 0,
      sql: "select count(*) from CBSAC.CUSTOMERS",
      type: "QUERY",
      columns: ["COUNT(*)"],
      columnDetails: [{
        label: "COUNT(*)", name: "COUNT(*)", remarks: "", catalog: "", schema: "",
        table: "", typeName: "NUMBER"
      }],
      rows: [],
      updateCount: -1,
      truncated: false,
      durationMs: 0,
      complete: false
    }));
    rpcMock.listeners.get("query.resultComplete")?.forEach((listener) => listener({
      editorId: "bootstrap-editor",
      resultIndex: 0,
      durationMs: 12
    }));
    rpcMock.listeners.get("query.executionComplete")?.forEach((listener) => listener({
      editorId: "bootstrap-editor", executionId: "execution-structure",
      cancelled: false, failed: false, durationMs: 12, transactionDirty: false
    }));
    await flushPromises();

    expect(completionMock.enrichQuery).toHaveBeenCalledWith({
      cacheKey: "system-1:environment-dev:oracle",
      providerId: "oracle",
      workspaceId: "workspace-1",
      clientId: "client-1",
      editorId: "bootstrap-editor",
      sql: "select count(*) from CBSAC.CUSTOMERS",
      columns: [{
        label: "COUNT(*)", name: "COUNT(*)", remarks: "", catalog: "", schema: "",
        table: "", typeName: "NUMBER"
      }]
    });
  });

  it("does not request deferred Oracle types for failed results or DML", async () => {
    const connections = useConnectionStore();
    const editors = useEditorStore();
    const profile: SavedProfile = { ...completionProfile(), providerId: "oracle" };
    connections.initialize([], [profile], [{ id: "system-1", name: "核心系统", revision: "1" }],
      [{ id: "environment-dev", systemId: "system-1", name: "DEV", revision: "1" }]);
    editors.patch("bootstrap-editor", {
      connection: profile, connectionState: "active", busy: true, executionPhase: "starting"
    });
    rpcMock.listeners.get("query.started")?.forEach((listener) => listener({
      editorId: "bootstrap-editor", executionId: "execution-no-structure"
    }));
    for (const result of [
      { resultIndex: 0, sql: "select * from CBSAC.CUSTOMERS", type: "QUERY", errorMessage: "ORA-00942" },
      { resultIndex: 1, sql: "update CBSAC.CUSTOMERS set NAME='x'", type: "UPDATE" }
    ]) {
      rpcMock.listeners.get("query.resultMeta")?.forEach((listener) => listener({
        editorId: "bootstrap-editor", columns: [], columnDetails: [], rows: [],
        updateCount: result.type === "UPDATE" ? 1 : -1, truncated: false, durationMs: 0,
        complete: false, ...result
      }));
      rpcMock.listeners.get("query.resultComplete")?.forEach((listener) => listener({
        editorId: "bootstrap-editor", resultIndex: result.resultIndex,
        errorMessage: result.errorMessage
      }));
    }
    rpcMock.listeners.get("query.executionComplete")?.forEach((listener) => listener({
      editorId: "bootstrap-editor", executionId: "execution-no-structure",
      cancelled: false, failed: true, durationMs: 9, transactionDirty: false
    }));
    await flushPromises();
    expect(completionMock.enrichQuery).not.toHaveBeenCalled();
  });

  it("invalidates only the affected Oracle structure after successful DDL", async () => {
    const connections = useConnectionStore();
    const editors = useEditorStore();
    const profile: SavedProfile = { ...completionProfile(), providerId: "oracle" };
    connections.initialize([], [profile], [{ id: "system-1", name: "核心系统", revision: "1" }],
      [{ id: "environment-dev", systemId: "system-1", name: "DEV", revision: "1" }]);
    editors.patch("bootstrap-editor", {
      connection: profile, connectionState: "active", busy: true, executionPhase: "starting"
    });
    rpcMock.listeners.get("query.started")?.forEach((listener) => listener({
      editorId: "bootstrap-editor", executionId: "execution-ddl"
    }));
    rpcMock.listeners.get("query.resultMeta")?.forEach((listener) => listener({
      editorId: "bootstrap-editor", resultIndex: 0,
      sql: "alter table CBSAC.CUSTOMERS add CREATED_AT timestamp",
      type: "DDL", columns: [], columnDetails: [], rows: [], updateCount: 0,
      truncated: false, durationMs: 0, complete: false
    }));
    rpcMock.listeners.get("query.resultComplete")?.forEach((listener) => listener({
      editorId: "bootstrap-editor", resultIndex: 0, durationMs: 9
    }));
    rpcMock.listeners.get("query.executionComplete")?.forEach((listener) => listener({
      editorId: "bootstrap-editor", executionId: "execution-ddl",
      cancelled: false, failed: false, durationMs: 9, transactionDirty: false
    }));
    await flushPromises();

    expect(completionMock.invalidateStructure).toHaveBeenCalledWith(
      "system-1:environment-dev:oracle",
      "oracle",
      "alter table CBSAC.CUSTOMERS add CREATED_AT timestamp"
    );
    expect(completionMock.enrichQuery).not.toHaveBeenCalled();
  });

  it("keeps the left execution status scoped to the active editor", async () => {
    const editors = useEditorStore();
    const queries = useQueryStore();
    editors.add({ id: "active-editor", title: "当前", content: "", dirty: false, transactionDirty: false,
      busy: false, executionPhase: "idle", transactionOperation: "idle", connectionState: "unbound" });
    editors.add({ id: "background-editor", title: "后台", content: "", dirty: false, transactionDirty: false,
      busy: false, executionPhase: "idle", transactionOperation: "idle", connectionState: "unbound" });
    editors.activeId = "active-editor";
    queries.start("active-editor", "active-execution");
    queries.complete("active-editor", { durationMs: 12, failed: false, cancelled: false });
    queries.start("background-editor", "background-execution");
    queries.complete("background-editor", { durationMs: 99, failed: true, cancelled: false });
    await nextTick();

    expect(wrapper.get(".execution-status").text()).toContain("执行完成 · 12 ms");
    expect(wrapper.get(".execution-status").text()).not.toContain("99");
    wrapper.findComponent({ name: "ResultPanel" }).vm.$emit("selected-row-count", 3);
    await nextTick();
    expect(wrapper.get(".status-execution-zone").text()).toBe("已选中 3 行");
    expect(wrapper.get(".status-execution-zone").text()).not.toContain("执行完成");
    expect(wrapper.get(".status-system-zone").text()).toContain("未选择链接");
  });

  it("shows the configured auto-commit mode in the system status", async () => {
    const connections = useConnectionStore();
    const editors = useEditorStore();
    const settings = useSettingsStore();
    const profile = completionProfile();
    connections.initialize([], [profile], [{ id: "system-1", name: "核心系统", revision: "1" }],
      [{ id: "environment-dev", systemId: "system-1", name: "DEV", revision: "1" }]);
    editors.patch("bootstrap-editor", { connection: profile, connectionState: "active" });
    settings.autoCommit = true;
    await nextTick();

    expect(wrapper.get(".status-system-zone").text()).toContain("链接正常 · 自动提交开启");
  });

  it("replaces execute with a yellow cancel button and keeps it until matching completion", async () => {
    const editors = useEditorStore();
    const profile = completionProfile();
    editors.patch("bootstrap-editor", { connection: profile, connectionState: "active" });
    Object.assign(wrapper.findComponent({ name: "MonacoEditor" }).vm, { getValue: () => "" });
    let finishExecute: ((value: { executionId: string }) => void) | undefined;
    rpcRequest.mockImplementation(async (type: string) => {
      if (type === "query.execute") {
        return await new Promise<{ executionId: string }>((resolve) => { finishExecute = resolve; });
      }
      if (type === "query.cancel") return { cancelled: true };
      return {};
    });

    const vm = wrapper.vm as unknown as { executeActive: (scope: "current") => Promise<void> };
    expect(editors.active).toMatchObject({ connectionState: "active", busy: false, executionPhase: "idle" });
    expect(wrapper.findComponent({ name: "ResultPanel" }).props("executing")).toBe(false);
    const executing = vm.executeActive("current");
    await flushPromises();
    expect(editors.active).toMatchObject({ busy: true, executionPhase: "starting" });
    expect(editors.active?.executionStartedAt).toEqual(expect.any(Number));
    expect(wrapper.findComponent({ name: "ResultPanel" }).props("executing")).toBe(true);
    expect(wrapper.findComponent({ name: "ResultPanel" }).props("executionStartedAt"))
      .toBe(editors.active?.executionStartedAt);
    expect(wrapper.find(".result-loading").exists()).toBe(true);

    let cancel = wrapper.get('button[aria-label="取消执行"]');
    expect(cancel.classes()).toContain("el-button--warning");
    expect(cancel.attributes("disabled")).toBeDefined();
    expect(wrapper.find(".execute-control.el-dropdown").exists()).toBe(false);

    rpcMock.listeners.get("query.started")?.forEach((listener) => listener({
      editorId: "bootstrap-editor", executionId: "execution-current"
    }));
    await nextTick();
    cancel = wrapper.get('button[aria-label="取消执行"]');
    expect(cancel.attributes("disabled")).toBeUndefined();
    await cancel.trigger("click");
    await flushPromises();
    expect(rpcRequest).toHaveBeenCalledWith("query.cancel", {
      editorId: "bootstrap-editor", executionId: "execution-current"
    });
    expect(editors.active?.executionPhase).toBe("cancelling");
    expect(wrapper.get('button[aria-label="取消执行"]').classes()).toContain("el-button--warning");

    rpcMock.listeners.get("query.executionComplete")?.forEach((listener) => listener({
      editorId: "bootstrap-editor", executionId: "stale-execution", cancelled: true,
      failed: false, durationMs: 1, transactionDirty: false
    }));
    await nextTick();
    expect(editors.active?.busy).toBe(true);

    rpcMock.listeners.get("query.executionComplete")?.forEach((listener) => listener({
      editorId: "bootstrap-editor", executionId: "execution-current", cancelled: true,
      failed: false, durationMs: 2, transactionDirty: false
    }));
    finishExecute?.({ executionId: "execution-current" });
    await executing;
    await nextTick();
    expect(editors.active).toMatchObject({ busy: false, executionPhase: "idle" });
    expect(editors.active?.executionStartedAt).toBeUndefined();
    expect(wrapper.findComponent({ name: "ResultPanel" }).props("executing")).toBe(false);
    expect(wrapper.find(".result-loading").exists()).toBe(false);
    expect(wrapper.find('button[aria-label="取消执行"]').exists()).toBe(false);
    expect(wrapper.find(".execute-control.el-dropdown").exists()).toBe(true);
  });

  it("shows a compact success-danger transaction group only for an active transaction", async () => {
    const editors = useEditorStore();
    const profile = completionProfile();
    editors.patch("bootstrap-editor", {
      connection: profile, connectionState: "active", transactionDirty: false, transactionState: "none"
    });
    await nextTick();
    expect(wrapper.find(".query-actions").exists()).toBe(false);

    editors.patch("bootstrap-editor", { transactionDirty: true, transactionState: "active" });
    await nextTick();
    const group = wrapper.get(".query-actions");
    const commit = group.get('button[aria-label="提交事务"]');
    const rollback = group.get('button[aria-label="回滚事务"]');
    expect(commit.classes()).toContain("el-button--success");
    expect(rollback.classes()).toContain("el-button--danger");
    expect(commit.attributes("disabled")).toBeUndefined();
    expect(rollback.attributes("disabled")).toBeUndefined();

    editors.patch("bootstrap-editor", { busy: true, executionPhase: "running", activeExecutionId: "execution-dml" });
    await nextTick();
    expect(wrapper.find(".query-actions").exists()).toBe(true);
    expect(group.get('button[aria-label="提交事务"]').attributes("disabled")).toBeDefined();
    expect(group.get('button[aria-label="回滚事务"]').attributes("disabled")).toBeDefined();

    editors.patch("bootstrap-editor", {
      busy: false, executionPhase: "idle", activeExecutionId: undefined,
      transactionDirty: false, transactionState: "none"
    });
    await nextTick();
    expect(wrapper.find(".query-actions").exists()).toBe(false);
  });

  it("确认后只清理补全缓存并使迟到快照失效", async () => {
    await flushPromises();
    const metadata = useMetadataStore();
    metadata.setRoots([{ id: "catalog", label: "eastwealthcrawler", kind: "catalog", leaf: false }], "profile@1");
    metadata.activate("profile@1", "system-1:environment-dev");
    metadata.beginCompletion("system-1:environment-dev", "DEV", "load-before-clear", "profile-1");
    metadata.completeCompletion("system-1:environment-dev", "load-before-clear", completionSummary("profile-1"));
    metadata.applyPersistentStats({ environmentCount: 1, suggestionCount: 1, estimatedBytes: 120 });
    metadata.beginCompletion("system-1:environment-dev", "DEV", "load-refresh", "profile-1", true);
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue({ value: "", action: "confirm" } as never);

    const vm = wrapper.vm as unknown as { clearCompletionCaches: () => Promise<void> };
    await vm.clearCompletionCaches();
    await nextTick();

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining("1个环境"), "清理补全缓存", expect.any(Object));
    expect(completionMock.clear).toHaveBeenCalledTimes(1);
    expect(metadata.roots).toHaveLength(0);
    expect(metadata.completeCompletion("system-1:environment-dev", "load-refresh", completionSummary("profile-1"))).toBe(false);
  });
});

function seedEditableResult(withDraft: boolean) {
  const editors = useEditorStore();
  const queries = useQueryStore();
  const edits = useResultEditStore();
  editors.patch("bootstrap-editor", {
    connection: completionProfile(), connectionState: "active",
    transactionDirty: true, transactionState: "active", resultChangesDirty: false,
    busy: false, executionPhase: "idle", transactionOperation: "idle"
  });
  queries.start("bootstrap-editor", "execution-edit");
  queries.addResult("bootstrap-editor", {
    resultIndex: 0, sql: "select id, name from sample for update", type: "QUERY",
    columns: ["id", "name"], rows: [["1", "before"]], rowIds: ["row-1"],
    mutationTarget: {
      qualifiedName: "`sample`", editableForUpdate: true, mode: "editable",
      columns: [
        { resultIndex: 0, name: "id", quotedName: "`id`", jdbcType: -5 },
        { resultIndex: 1, name: "name", quotedName: "`name`", jdbcType: 12 }
      ],
      uniqueKeys: [{ name: "PRIMARY", primary: true, resultColumnIndices: [0] }]
    },
    updateCount: -1, truncated: false, durationMs: 3, complete: true
  });
  queries.complete("bootstrap-editor", { durationMs: 3 });
  edits.setUnlocked("bootstrap-editor", "execution-edit", 0, true);
  if (withDraft) edits.stage("bootstrap-editor", "execution-edit", 0,
    0, 1, "before", "after", "row-1");
  return edits;
}

function executeFromApp(wrapper: VueWrapper): Promise<void> {
  return (wrapper.vm as unknown as {
    executeActive: (scope: "current" | "script") => Promise<void>
  }).executeActive("current");
}

async function expectResultDecision(text: string): Promise<void> {
  await flushPromises();
  await nextTick();
  const dialogs = document.body.querySelectorAll(".el-message-box");
  const dialog = dialogs.item(dialogs.length - 1);
  expect(dialog?.textContent).toContain(text);
}

function clickMessageBoxButton(label: string): void {
  const dialogs = document.body.querySelectorAll(".el-message-box");
  const dialog = dialogs.item(dialogs.length - 1);
  const button = Array.from(dialog?.querySelectorAll<HTMLButtonElement>("button") ?? [])
    .find((value) => value.textContent?.trim() === label);
  expect(button).toBeDefined();
  button?.click();
}

function completionProfile(): SavedProfile {
  return { id: "profile-completion", providerId: "mysql", name: "业务库", settings: {}, rememberPassword: false,
    environmentId: "environment-dev", revision: "1" };
}

function completionSummary(sourceProfileId: string, objectCount = 1, columnCount = 0): CompletionCacheSummary {
  return { providerId: "mysql", sourceProfileId, generatedAt: "2026-07-19T00:00:00Z",
    selectedNamespaceKeys: ["catalog:sales"], objectCount, columnCount, estimatedBytes: 120 };
}

function completionNamespaces(sourceProfileId = "profile-completion") {
  return { providerId: "mysql", sourceProfileId, namespaces: [
    { key: "catalog:sales", catalog: "sales", schema: "", label: "sales", kind: "catalog" as const,
      current: true, system: false }
  ] };
}
