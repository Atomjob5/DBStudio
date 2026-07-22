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
        columnLayoutScope: "result",
        copyHeaderOnDoubleClick: true,
        copySeparator: "comma",
        headerSortingEnabled: true,
        headerFilteringEnabled: true,
        maxActiveSessions: 10,
        idleTimeoutMinutes: 10,
        transactionDisconnectRollbackMinutes: 10,
        completionCacheSize: "1.2 MB",
        completionCacheEnvironmentCount: 3,
        completionCacheLoadingCount: 1,
        canClearCompletionCaches: true
      },
      global: { plugins: [ElementPlus], stubs: { teleport: true } }
    });
  }

  it("renders compact connection and result settings without the old alert", async () => {
    const wrapper = mountDrawer();
    await flushPromises();
    expect(wrapper.findAll(".compact-setting-row")).toHaveLength(11);
    expect(wrapper.findComponent({ name: "ElAlert" }).exists()).toBe(false);
    const separator = wrapper.findAllComponents({ name: "ElRadioGroup" })
      .find((group) => group.props("modelValue") === "comma");
    expect(separator).toBeDefined();
    expect(separator!.findAllComponents({ name: "ElRadioButton" }).map((button) => button.props("value")))
      .toEqual(["comma", "tab", "semicolon", "pipe"]);
    wrapper.unmount();
  });

  it("provides concise tooltips and emits the existing setting updates", async () => {
    const wrapper = mountDrawer();
    await flushPromises();
    const tooltips = wrapper.findAllComponents({ name: "ElTooltip" });
    expect(tooltips.map((tooltip) => tooltip.props("content"))).toEqual(expect.arrayContaining([
      expect.stringContaining("完整导出不受影响"),
      expect.stringContaining("事件触发更频繁"),
      expect.stringContaining("字段集合完全一致"),
      expect.stringContaining("自动转义"),
      expect.stringContaining("UTF-8字节数估算")
    ]));

    expect(wrapper.get('[data-testid="completion-cache-stats"]').text()).toContain("约 1.2 MB · 3个环境 · 1项加载中");
    await wrapper.get('button[aria-label="清理全部补全缓存"]').trigger("click");

    const numbers = wrapper.findAllComponents({ name: "ElInputNumber" });
    numbers[0].vm.$emit("update:modelValue", 12);
    numbers[1].vm.$emit("update:modelValue", 15);
    numbers[2].vm.$emit("update:modelValue", 20);
    numbers[3].vm.$emit("update:modelValue", 2500);
    numbers[4].vm.$emit("update:modelValue", 50);
    const switches = wrapper.findAllComponents({ name: "ElSwitch" });
    switches[0].vm.$emit("update:modelValue", false);
    switches[1].vm.$emit("update:modelValue", false);
    switches[2].vm.$emit("update:modelValue", false);
    wrapper.findAllComponents({ name: "ElRadioGroup" })
      .find((group) => group.props("modelValue") === "result")!.vm.$emit("update:modelValue", "editor");
    wrapper.findAllComponents({ name: "ElRadioGroup" })
      .find((group) => group.props("modelValue") === "comma")!.vm.$emit("update:modelValue", "pipe");

    expect(wrapper.emitted("update:maxActiveSessions")?.[0]).toEqual([12]);
    expect(wrapper.emitted("update:idleTimeoutMinutes")?.[0]).toEqual([15]);
    expect(wrapper.emitted("update:transactionDisconnectRollbackMinutes")?.[0]).toEqual([20]);
    expect(wrapper.emitted("update:maxRows")?.[0]).toEqual([2500]);
    expect(wrapper.emitted("update:streamBatchRows")?.[0]).toEqual([50]);
    expect(wrapper.emitted("update:copyHeaderOnDoubleClick")?.[0]).toEqual([false]);
    expect(wrapper.emitted("update:headerSortingEnabled")?.[0]).toEqual([false]);
    expect(wrapper.emitted("update:headerFilteringEnabled")?.[0]).toEqual([false]);
    expect(wrapper.emitted("update:columnLayoutScope")?.[0]).toEqual(["editor"]);
    expect(wrapper.emitted("update:copySeparator")?.[0]).toEqual(["pipe"]);
    expect(wrapper.emitted("clearCompletionCaches")).toHaveLength(1);
    wrapper.unmount();
  });

  it("disables clearing when the page has no completion cache or loading task", async () => {
    const wrapper = mountDrawer();
    await wrapper.setProps({ completionCacheSize: "0 B", completionCacheEnvironmentCount: 0,
      completionCacheLoadingCount: 0, canClearCompletionCaches: false });
    expect(wrapper.get('button[aria-label="清理全部补全缓存"]').attributes("disabled")).toBeDefined();
    wrapper.unmount();
  });
});
