import { describe, expect, it } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import SettingsDrawer from "./SettingsDrawer.vue";

describe("SettingsDrawer compact result settings", () => {
  function mountDrawer() {
    return mount(SettingsDrawer, {
      props: {
        modelValue: true,
        theme: "system",
        resolvedTheme: "light",
        maxRows: 1000,
        streamBatchRows: 100,
        clobMaxCharacters: 10_000,
        maxLobBytes: 268_435_456,
        columnLayoutScope: "result",
        copyHeaderOnDoubleClick: true,
        copySeparator: "comma",
        headerSortingEnabled: true,
        headerFilteringEnabled: true,
        showColumnRemarksInHeader: false,
        zebraStripesEnabled: false,
        scrollOptimizationBufferScreens: 1,
        showSelectedColumnRemarks: true,
        maxActiveSessions: 10,
        autoCommit: false,
        idleTimeoutMinutes: 10,
        transactionDisconnectRollbackMinutes: 10,
        completionCacheSize: "1.2 MB",
        completionCacheEnvironmentCount: 3,
        completionCacheLoadingCount: 1,
        completionCandidateLimit: 100,
        completionPreciseMatchingEnabled: false,
        canClearCompletionCaches: true,
        minimapEnabled: true,
        wordWrapEnabled: false,
        rainbowBracketsEnabled: false,
        sqlDiagnosticsEnabled: true,
        dangerousStatementWarningEnabled: true,
        continueOnError: false,
        executionWarningMinutes: [1, 5]
      },
      global: { plugins: [ElementPlus] }
    });
  }

  it("renders compact connection and result settings without the old alert", async () => {
    const wrapper = mountDrawer();
    await flushPromises();
    expect(wrapper.findAll(".compact-setting-row")).toHaveLength(27);
    expect(wrapper.findComponent({ name: "ElAlert" }).exists()).toBe(false);
    const separator = wrapper.findAllComponents({ name: "ElRadioGroup" })
      .find((group) => group.props("modelValue") === "comma");
    expect(separator).toBeDefined();
    expect(separator!.findAllComponents({ name: "ElRadioButton" }).map((button) => button.props("value")))
      .toEqual(["comma", "tab", "semicolon", "pipe"]);
    wrapper.unmount();
  });

  it("renders full-width navigation rows with arrows and emits their existing actions", async () => {
    const wrapper = mountDrawer();
    await flushPromises();
    const rows = wrapper.findAll("button.navigation-setting-row");

    expect(rows).toHaveLength(3);
    expect(rows.map((row) => row.text())).toEqual(["配置配色方案", "配置快捷键", "管理 SQL 片段"]);
    expect(rows.every((row) => row.attributes("type") === "button")).toBe(true);
    expect(rows.every((row) => row.find(".setting-label > span").exists())).toBe(true);
    expect(rows.every((row) => row.find(".navigation-setting-arrow").exists())).toBe(true);
    expect(wrapper.findAll(".navigation-setting-predecessor")).toHaveLength(1);
    expect(wrapper.text()).not.toContain("0项");

    await rows[0].trigger("click");
    await rows[1].trigger("click");
    await rows[2].trigger("click");
    expect(wrapper.emitted("openAppearance")).toHaveLength(1);
    expect(wrapper.emitted("openShortcuts")).toHaveLength(1);
    expect(wrapper.emitted("openCompletionSnippets")).toHaveLength(1);
    wrapper.unmount();
  });

  it("provides concise tooltips and emits the existing setting updates", async () => {
    const wrapper = mountDrawer();
    await flushPromises();
    const tooltips = wrapper.findAllComponents({ name: "ElTooltip" });
    const tooltipText = tooltips.map((tooltip) => String(tooltip.props("content"))).join("\n");
    for (const text of ["完整导出不受影响", "事件触发更频繁", "CLOB 最多读取的字符数", "控制可视区域四周", "字段集合完全一致",
      "自动转义", "IndexedDB", "每条DML成功执行即提交", "未包含 WHERE", "400 毫秒",
      "执行选中的多条 SQL 或整个脚本时"]) {
      expect(tooltipText).toContain(text);
    }

    expect(wrapper.get('[data-testid="completion-cache-stats"]').text()).toContain("约 1.2 MB · 3个环境 · 1项加载中");
    await wrapper.get('button[aria-label="清理全部补全缓存"]').trigger("click");

    const numbers = wrapper.findAllComponents({ name: "ElInputNumber" });
    numbers[0].vm.$emit("update:modelValue", 12);
    numbers[1].vm.$emit("update:modelValue", 15);
    numbers[2].vm.$emit("update:modelValue", 20);
    numbers[3].vm.$emit("update:modelValue", 250);
    numbers[4].vm.$emit("update:modelValue", 2500);
    numbers[5].vm.$emit("update:modelValue", 50);
    numbers[6].vm.$emit("update:modelValue", 22000);
    const switches = wrapper.findAllComponents({ name: "ElSwitch" });
    expect(switches[2].props("modelValue")).toBe(false);
    switches[2].vm.$emit("update:modelValue", true);
    switches[2].vm.$emit("update:modelValue", false);
    expect(wrapper.emitted("update:rainbowBracketsEnabled")).toEqual([[true], [false]]);
    switches[0].vm.$emit("update:modelValue", false);
    switches[1].vm.$emit("update:modelValue", true);
    switches[3].vm.$emit("update:modelValue", false);
    switches[4].vm.$emit("update:modelValue", false);
    switches[5].vm.$emit("update:modelValue", true);
    switches[6].vm.$emit("update:modelValue", true);
    switches[7].vm.$emit("update:modelValue", true);
    switches[8].vm.$emit("update:modelValue", false);
    switches[9].vm.$emit("update:modelValue", false);
    switches[10].vm.$emit("update:modelValue", false);
    switches[11].vm.$emit("update:modelValue", true);
    switches[12].vm.$emit("update:modelValue", true);
    switches[13].vm.$emit("update:modelValue", false);
    wrapper.findAllComponents({ name: "ElRadioGroup" })
      .find((group) => group.props("modelValue") === "result")!.vm.$emit("update:modelValue", "editor");
    wrapper.findAllComponents({ name: "ElRadioGroup" })
      .find((group) => group.props("modelValue") === "comma")!.vm.$emit("update:modelValue", "pipe");

    expect(wrapper.emitted("update:maxActiveSessions")?.[0]).toEqual([12]);
    expect(wrapper.emitted("update:idleTimeoutMinutes")?.[0]).toEqual([15]);
    expect(wrapper.emitted("update:transactionDisconnectRollbackMinutes")?.[0]).toEqual([20]);
    expect(wrapper.emitted("update:completionCandidateLimit")?.[0]).toEqual([250]);
    expect(wrapper.emitted("update:maxRows")?.[0]).toEqual([2500]);
    expect(wrapper.emitted("update:streamBatchRows")?.[0]).toEqual([50]);
    expect(wrapper.emitted("update:clobMaxCharacters")?.[0]).toEqual([22000]);
    expect(wrapper.emitted("update:autoCommit")?.[0]).toEqual([true]);
    expect(wrapper.emitted("update:completionPreciseMatchingEnabled")?.[0]).toEqual([true]);
    expect(wrapper.emitted("update:minimapEnabled")?.[0]).toEqual([false]);
    expect(wrapper.emitted("update:wordWrapEnabled")?.[0]).toEqual([true]);
    expect(wrapper.emitted("update:sqlDiagnosticsEnabled")?.[0]).toEqual([false]);
    expect(wrapper.emitted("update:dangerousStatementWarningEnabled")?.[0]).toEqual([false]);
    expect(wrapper.emitted("update:continueOnError")?.[0]).toEqual([true]);
    expect(wrapper.emitted("update:copyHeaderOnDoubleClick")?.[0]).toEqual([false]);
    expect(wrapper.emitted("update:headerSortingEnabled")?.[0]).toEqual([false]);
    expect(wrapper.emitted("update:headerFilteringEnabled")?.[0]).toEqual([false]);
    expect(wrapper.emitted("update:showColumnRemarksInHeader")?.[0]).toEqual([true]);
    expect(wrapper.emitted("update:zebraStripesEnabled")?.[0]).toEqual([true]);
    expect(wrapper.emitted("update:showSelectedColumnRemarks")?.[0]).toEqual([false]);
    expect(wrapper.emitted("update:columnLayoutScope")?.[0]).toEqual(["editor"]);
    expect(wrapper.emitted("update:copySeparator")?.[0]).toEqual(["pipe"]);
    expect(wrapper.emitted("clearCompletionCaches")).toHaveLength(1);
    await wrapper.findAll(".navigation-setting-row")[1].trigger("click");
    expect(wrapper.emitted("openShortcuts")).toHaveLength(1);
    await wrapper.findAll(".navigation-setting-row")[2].trigger("click");
    expect(wrapper.emitted("openCompletionSnippets")).toHaveLength(1);
    wrapper.unmount();
  });

  it("disables clearing when the page has no completion cache or loading task", async () => {
    const wrapper = mountDrawer();
    await flushPromises();
    await wrapper.setProps({ completionCacheSize: "0 B", completionCacheEnvironmentCount: 0,
      completionCacheLoadingCount: 0, canClearCompletionCaches: false });
    expect(wrapper.get('button[aria-label="清理全部补全缓存"]').attributes("disabled")).toBeDefined();
    wrapper.unmount();
  });

  it("always shows and updates the virtual-grid buffer setting", async () => {
    const wrapper = mountDrawer();
    await flushPromises();
    expect(wrapper.find('input[aria-label="预渲染缓冲"]').exists()).toBe(true);
    expect(wrapper.text()).toContain("预渲染缓冲");

    await wrapper.setProps({ scrollOptimizationBufferScreens: 2 });
    const buffer = wrapper.findAllComponents({ name: "ElInputNumber" }).at(-1);
    expect(buffer?.props("modelValue")).toBe(2);
    expect(buffer?.props("min")).toBe(0.5);
    expect(buffer?.props("max")).toBe(3);
    buffer?.vm.$emit("update:modelValue", 1.5);
    expect(wrapper.emitted("update:scrollOptimizationBufferScreens")?.[0]).toEqual([1.5]);

    expect(wrapper.findAllComponents({ name: "ElInputNumber" }).at(-1)?.props("modelValue")).toBe(2);
    wrapper.unmount();
  });

  it("uses a single-line multi-select for SQL execution warning thresholds", async () => {
    const wrapper = mountDrawer();
    await flushPromises();

    const select = wrapper.findAllComponents({ name: "ElSelect" })
      .find((item) => item.classes().includes("execution-warning-select"))!;
    expect(select.exists()).toBe(true);
    expect(select.props("multiple")).toBe(true);
    expect(select.props("filterable")).toBe(true);
    expect(select.props("allowCreate")).toBe(true);
    expect(select.props("defaultFirstOption")).toBe(true);
    expect(select.props("reserveKeyword")).toBe(false);
    expect(select.props("collapseTags")).toBe(true);
    expect(select.props("maxCollapseTags")).toBe(1);
    expect(select.props("collapseTagsTooltip")).not.toBe(true);
    expect(select.props("modelValue")).toEqual(["1", "5"]);
    expect(wrapper.findAllComponents({ name: "ElOption" }).map((option) => option.props("label")))
      .toEqual(["1 分钟", "5 分钟"]);

    const model = wrapper.vm as unknown as { executionWarningSelectValues: string[] };
    model.executionWarningSelectValues = ["30", "20", "30"];
    expect(wrapper.emitted("update:executionWarningMinutes")?.[0]).toEqual([[20, 30]]);
    model.executionWarningSelectValues = ["0", "1.5", "abc"];
    expect(wrapper.emitted("update:executionWarningMinutes")).toHaveLength(1);
    model.executionWarningSelectValues = [];
    expect(wrapper.emitted("update:executionWarningMinutes")?.[1]).toEqual([[]]);
    wrapper.unmount();
  });
});
