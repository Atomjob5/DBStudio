import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import ResultHeaderContextMenu from "./ResultHeaderContextMenu.vue";

describe("ResultHeaderContextMenu", () => {
  it("offers and emits the copy-column-remarks command", async () => {
    const wrapper = mount(ResultHeaderContextMenu, {
      props: {
        visible: true,
        x: 20,
        y: 30,
        canCopyData: true,
        canIn: true,
        canMoveLeft: true,
        canMoveRight: true,
        canSum: true,
      },
      global: { plugins: [ElementPlus], stubs: { teleport: true } },
    });
    expect(wrapper.text()).toContain("复制列名和注释");
    wrapper.findComponent({ name: "ElMenu" }).vm.$emit("select", "copy-headers-with-remarks");
    expect(wrapper.emitted("command")?.[0]).toEqual(["copy-headers-with-remarks"]);
    expect(wrapper.emitted("close")).toHaveLength(1);
  });

  it("offers field visibility commands and disables excluding all fields", () => {
    const wrapper = mount(ResultHeaderContextMenu, {
      props: {
        visible: true,
        x: 20,
        y: 30,
        canCopyData: true,
        canMoveLeft: true,
        canMoveRight: true,
        canSum: true,
        canFilterOnly: true,
        canFilterExclude: false,
      },
      global: { plugins: [ElementPlus], stubs: { teleport: true } },
    });
    expect(wrapper.text()).toContain("筛选");
    expect(wrapper.text()).toContain("只展示选中字段");
    expect(wrapper.text()).toContain("不展示选中字段");
    const exclude = wrapper.findAllComponents({ name: "ElMenuItem" })
      .find((item) => item.props("index") === "filter-exclude");
    expect(exclude?.props("disabled")).toBe(true);
    wrapper.findComponent({ name: "ElMenu" }).vm.$emit("select", "filter-only");
    expect(wrapper.emitted("command")?.at(-1)).toEqual(["filter-only"]);
  });
});
