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
        copySeparator: "comma"
      },
      global: { plugins: [ElementPlus], stubs: { teleport: true } }
    });
  }

  it("renders five compact rows and four separator buttons without the old alert", async () => {
    const wrapper = mountDrawer();
    await flushPromises();
    expect(wrapper.findAll(".compact-setting-row")).toHaveLength(5);
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
      expect.stringContaining("界面更新次数越多"),
      expect.stringContaining("字段集合完全一致"),
      expect.stringContaining("自动转义")
    ]));

    const numbers = wrapper.findAllComponents({ name: "ElInputNumber" });
    numbers[0].vm.$emit("update:modelValue", 2500);
    numbers[1].vm.$emit("update:modelValue", 50);
    wrapper.findComponent({ name: "ElSwitch" }).vm.$emit("update:modelValue", false);
    wrapper.findAllComponents({ name: "ElRadioGroup" })
      .find((group) => group.props("modelValue") === "result")!.vm.$emit("update:modelValue", "editor");
    wrapper.findAllComponents({ name: "ElRadioGroup" })
      .find((group) => group.props("modelValue") === "comma")!.vm.$emit("update:modelValue", "pipe");

    expect(wrapper.emitted("update:maxRows")?.[0]).toEqual([2500]);
    expect(wrapper.emitted("update:streamBatchRows")?.[0]).toEqual([50]);
    expect(wrapper.emitted("update:copyHeaderOnDoubleClick")?.[0]).toEqual([false]);
    expect(wrapper.emitted("update:columnLayoutScope")?.[0]).toEqual(["editor"]);
    expect(wrapper.emitted("update:copySeparator")?.[0]).toEqual(["pipe"]);
    wrapper.unmount();
  });
});
