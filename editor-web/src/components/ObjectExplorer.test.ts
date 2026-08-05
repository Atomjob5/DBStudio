import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus from "element-plus";
import ObjectExplorer from "./ObjectExplorer.vue";
import { useMetadataStore } from "../stores/metadata";
import type { CompletionCacheSummary, MetadataNode } from "../types";

const rpcRequest = vi.hoisted(() => vi.fn());
vi.mock("../bridge/rpc", () => ({ rpc: { request: rpcRequest } }));

describe("ObjectExplorer completion refresh boundary", () => {
  let wrapper: VueWrapper;

  beforeEach(() => {
    setActivePinia(createPinia());
    rpcRequest.mockReset();
    rpcRequest.mockResolvedValue([{ id: "catalog:sales", label: "sales", kind: "catalog", leaf: false } satisfies MetadataNode]);
    wrapper = mount(ObjectExplorer, {
      props: { connectionName: "DEV / 业务库", editorId: "editor-1", treeCacheKey: "system-1:environment-dev" },
      global: { plugins: [ElementPlus] }
    });
  });

  afterEach(() => wrapper.unmount());

  it("does not incrementally mutate completion cache state while the tree loads", async () => {
    const metadata = useMetadataStore();
    const summary: CompletionCacheSummary = { providerId: "mysql", sourceProfileId: "profile-1",
      generatedAt: "2026-07-19T00:00:00Z", selectedNamespaceKeys: ["catalog:sales"],
      objectCount: 1, columnCount: 2, estimatedBytes: 128 };
    metadata.beginCompletion("system:dev", "DEV", "load-1", "profile-1");
    metadata.completeCompletion("system:dev", "load-1", summary);
    metadata.activate("system-1:environment-dev", "system:dev");

    await flushPromises();

    expect(metadata.completionFor("system:dev")?.summary).toEqual(summary);
    expect(rpcRequest).toHaveBeenCalledWith("metadata.children", { kind: "root", editorId: "editor-1" });
  });

  it("emits a force-refresh request only for explicit refresh actions", async () => {
    await flushPromises();
    expect(wrapper.emitted("refresh")).toBeUndefined();

    await wrapper.find('button[aria-label="刷新对象树"]').trigger("click");

    expect(wrapper.emitted("refresh")).toHaveLength(1);
  });

  it("reuses the environment tree cache after unmounting and remounting", async () => {
    await flushPromises();
    expect(rpcRequest).toHaveBeenCalledTimes(1);
    wrapper.unmount();
    rpcRequest.mockClear();

    wrapper = mount(ObjectExplorer, {
      props: { connectionName: "DEV / 业务库", editorId: "editor-2", treeCacheKey: "system-1:environment-dev" },
      global: { plugins: [ElementPlus] }
    });
    await flushPromises();

    expect(rpcRequest).not.toHaveBeenCalled();
  });

  it("rebuilds for another environment and refreshes only after an explicit request", async () => {
    await flushPromises();
    const metadata = useMetadataStore();
    metadata.setTreeChildren("system-1:environment-sit", "__root__", [
      { id: "catalog:sit", label: "sit", kind: "catalog", leaf: false }
    ]);
    const callsBeforeSwitch = rpcRequest.mock.calls.length;

    await wrapper.setProps({ editorId: "editor-2", treeCacheKey: "system-1:environment-sit" });
    await flushPromises();
    expect(rpcRequest).toHaveBeenCalledTimes(callsBeforeSwitch);
    expect(wrapper.text()).toContain("sit");

    await wrapper.find('button[aria-label="刷新对象树"]').trigger("click");
    await flushPromises();
    expect(rpcRequest).toHaveBeenCalledTimes(callsBeforeSwitch + 1);
    expect(rpcRequest).toHaveBeenLastCalledWith("metadata.children", { kind: "root", editorId: "editor-2" });
  });
});
