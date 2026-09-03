import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import IconTooltip from "./IconTooltip.vue";

describe("IconTooltip", () => {
  it("uses an intentional hover delay and closes immediately", () => {
    const wrapper = mount(IconTooltip, {
      props: { content: "单个记录查看" },
      slots: { default: "触发按钮" },
      global: { plugins: [ElementPlus] },
    });

    const tooltip = wrapper.findComponent({ name: "ElTooltip" });
    expect(tooltip.props()).toMatchObject({
      content: "单个记录查看",
      showAfter: 1000,
      hideAfter: 0,
      enterable: false,
      placement: "top",
    });
    expect(wrapper.text()).toContain("触发按钮");
  });

  it("allows placement and disabled state to be overridden", () => {
    const wrapper = mount(IconTooltip, {
      props: { content: "最近打开的 SQL 文件", placement: "bottom", disabled: true },
      global: { plugins: [ElementPlus] },
    });

    expect(wrapper.findComponent({ name: "ElTooltip" }).props()).toMatchObject({
      placement: "bottom",
      disabled: true,
    });
  });
});
