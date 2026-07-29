import { describe, expect, it } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import SqlSnippetSettingsDrawer from "./SqlSnippetSettingsDrawer.vue";
import type { SqlCompletionSnippet } from "../types";

const snippets: SqlCompletionSnippet[] = [
  { id: "5d652bad-8dce-4b56-9c94-d62d74a74576", trigger: "sf",
    remarks: "通用查询", sql: "select * from" },
  { id: "dbf3fc10-3b72-41b8-a83f-9f786314303e", trigger: "cnt",
    remarks: "统计", sql: "select count(*) from" },
];

describe("SqlSnippetSettingsDrawer", () => {
  function mountDrawer() {
    return mount(SqlSnippetSettingsDrawer, {
      props: { modelValue: true, snippets, saving: false },
      global: { plugins: [ElementPlus], stubs: { teleport: true } },
    });
  }

  it("edits and removes persisted snippets", async () => {
    const wrapper = mountDrawer();
    await flushPromises();
    expect(wrapper.text()).toContain("sf");
    expect(wrapper.text()).toContain("通用查询");

    await wrapper.findAll("button").find((button) => button.text() === "编辑")!.trigger("click");
    const inputs = wrapper.findAll("input");
    await inputs[1].setValue("常用查询");
    await wrapper.findAll("button").find((button) => button.text() === "保存")!.trigger("click");
    expect(wrapper.emitted("updateSnippets")?.[0]?.[0]).toEqual([
      { ...snippets[0], remarks: "常用查询" },
      snippets[1],
    ]);

    await wrapper.get('button[aria-label="删除片段sf"]').trigger("click");
    expect(wrapper.emitted("updateSnippets")?.[1]?.[0]).toEqual([snippets[1]]);
    wrapper.unmount();
  });

  it("keeps the editor open when the case-insensitive trigger conflicts", async () => {
    const wrapper = mountDrawer();
    await flushPromises();
    await wrapper.findAll("button").filter((button) => button.text() === "编辑")[1].trigger("click");
    await wrapper.findAll("input")[0].setValue("SF");
    await wrapper.findAll("button").find((button) => button.text() === "保存")!.trigger("click");
    expect(wrapper.text()).toContain("该提示词已存在");
    expect(wrapper.emitted("updateSnippets")).toBeUndefined();
    wrapper.unmount();
  });
});
