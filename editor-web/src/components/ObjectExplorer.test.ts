import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus from "element-plus";
import ObjectExplorer from "./ObjectExplorer.vue";
import { useMetadataStore } from "../stores/metadata";
import type { CompletionSnapshot, MetadataNode, Suggestion } from "../types";

const rpcRequest = vi.hoisted(() => vi.fn());
vi.mock("../bridge/rpc", () => ({ rpc: { request: rpcRequest } }));

describe("ObjectExplorer completion refresh boundary", () => {
  let wrapper: VueWrapper;

  beforeEach(() => {
    setActivePinia(createPinia());
    rpcRequest.mockReset();
    rpcRequest.mockResolvedValue([{ id: "catalog:sales", label: "sales", kind: "catalog", leaf: false } satisfies MetadataNode]);
    wrapper = mount(ObjectExplorer, {
      props: { connectionName: "DEV / 业务库", editorId: "editor-1", connectionKey: "profile-1@1" },
      global: { plugins: [ElementPlus] }
    });
  });

  afterEach(() => wrapper.unmount());

  it("does not incrementally mutate completion suggestions while the tree loads", async () => {
    const metadata = useMetadataStore();
    const existing: Suggestion = { id: "table:orders", label: "orders", insertText: "`orders`", detail: "orders", kind: "table" };
    const snapshot: CompletionSnapshot = { providerId: "mysql", sourceProfileId: "profile-1",
      generatedAt: "2026-07-19T00:00:00Z", suggestions: [existing] };
    metadata.beginCompletion("system:dev", "DEV", "load-1", "profile-1");
    metadata.completeCompletion("system:dev", "load-1", snapshot);
    metadata.activate("profile-1@1", "system:dev");

    await flushPromises();

    expect(metadata.suggestions).toEqual([existing]);
    expect(rpcRequest).toHaveBeenCalledWith("metadata.children", { kind: "root", editorId: "editor-1" });
  });

  it("emits a force-refresh request only for explicit refresh actions", async () => {
    await flushPromises();
    expect(wrapper.emitted("refresh")).toBeUndefined();

    await wrapper.find('button[aria-label="刷新对象树"]').trigger("click");

    expect(wrapper.emitted("refresh")).toHaveLength(1);
  });
});
