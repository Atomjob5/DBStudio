import { beforeEach, describe, expect, it } from "vitest";
import { computed, defineComponent, nextTick } from "vue";
import { createPinia, setActivePinia } from "pinia";
import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import ResultPanel from "./ResultPanel.vue";
import { useQueryStore } from "../stores/query";

describe("ResultPanel streaming rendering", () => {
  beforeEach(() => setActivePinia(createPinia()));

  it("renders metadata, batches and completion after immutable store updates", async () => {
    const queries = useQueryStore();
    const Harness = defineComponent({
      components: { ResultPanel },
      setup() { return { execution: computed(() => queries.executions["editor-1"]) }; },
      template: '<ResultPanel :execution="execution" :active-result-index="0" />'
    });
    const wrapper = mount(Harness, { global: { plugins: [ElementPlus] } });
    expect(wrapper.text()).toContain("执行查询后在这里查看结果");

    queries.start("editor-1", "execution-1");
    queries.addResult("editor-1", {
      resultIndex: 0, sql: "select 1", type: "QUERY", columns: ["value"], rows: [],
      updateCount: -1, truncated: false, durationMs: 0, complete: false
    });
    await nextTick();
    expect(wrapper.text()).toContain("结果 1");
    expect(wrapper.text()).toContain("正在执行");

    queries.appendRows("editor-1", 0, [["1"], ["2"]]);
    queries.completeResult("editor-1", 0, { durationMs: 7, truncated: false });
    queries.complete("editor-1", { durationMs: 8, failed: false, cancelled: false });
    await nextTick();
    expect(wrapper.text()).toContain("2 行 · 7 ms");
    expect(wrapper.text()).not.toContain("执行查询后在这里查看结果");
  });

  it("removes the local data toolbar and synchronizes the active result", async () => {
    const wrapper = mount(ResultPanel, {
      props: { activeResultIndex: 0, execution: {
        executionId: "execution-1", editorId: "editor-1", busy: false, cancelled: false, failed: false, durationMs: 8,
        results: [
          { resultIndex: 0, sql: "select 1", type: "QUERY", columns: ["id"], rows: [["1"]],
            updateCount: -1, truncated: true, durationMs: 7, complete: true },
          { resultIndex: 1, sql: "select 2", type: "QUERY", columns: ["id"], rows: [["2"]],
            updateCount: -1, truncated: false, durationMs: 8, complete: true }
        ]
      } },
      global: { plugins: [ElementPlus] }
    });
    expect(wrapper.find(".result-data-toolbar").exists()).toBe(false);
    await wrapper.findAll(".el-tabs__item")[1].trigger("click");
    expect(wrapper.emitted("update:active-result-index")?.[0]).toEqual([1]);
  });
});
