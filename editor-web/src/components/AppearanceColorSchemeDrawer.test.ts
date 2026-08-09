import { describe, expect, it } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import AppearanceColorSchemeDrawer from "./AppearanceColorSchemeDrawer.vue";
import { cloneColorSchemes, DEFAULT_COLOR_SCHEMES } from "../appearance";

describe("AppearanceColorSchemeDrawer", () => {
  function mountDrawer() {
    return mount(AppearanceColorSchemeDrawer, {
      props: { modelValue: true, schemes: cloneColorSchemes(DEFAULT_COLOR_SCHEMES) },
      global: { plugins: [ElementPlus], stubs: { teleport: true } },
    });
  }

  it("shows six presets and applies a preset only to the selected mode draft", async () => {
    const wrapper = mountDrawer();
    await flushPromises();
    expect(wrapper.findAll(".appearance-preset")).toHaveLength(6);
    await wrapper.findAll(".appearance-preset")[2].trigger("click");
    expect(wrapper.find(".appearance-preset.active").text()).toContain("Solarized Light");
    expect(wrapper.get(".appearance-preview").attributes("style")).toContain("#FDF6E3");
    (wrapper.vm as unknown as { mode: "light" | "dark" }).mode = "dark";
    await flushPromises();
    expect(wrapper.findAll(".appearance-preset")).toHaveLength(6);
    expect(wrapper.find(".appearance-preview").attributes("style")).not.toContain("#FDF6E3");
    wrapper.unmount();
  });

  it("previews edits and emits a cloned value on save or cancel", async () => {
    const wrapper = mountDrawer();
    await flushPromises();
    const background = wrapper.get('input[aria-label="背景"]');
    await background.setValue("#123456");
    expect(wrapper.get(".appearance-preview").attributes("style")).toContain("#123456");
    await wrapper.get(".appearance-drawer-footer .el-button--primary").trigger("click");
    const saved = wrapper.emitted("save")?.[0]?.[0] as typeof DEFAULT_COLOR_SCHEMES;
    expect(saved.light.editor.background).toBe("#123456");
    expect(saved.light.presetId).toBe("custom");
    saved.light.editor.background = "#FFFFFF";
    expect((wrapper.props("schemes") as typeof DEFAULT_COLOR_SCHEMES).light.editor.background).toBe("#FFFFFF");
    await wrapper.get(".appearance-drawer-footer .el-button").trigger("click");
    expect(wrapper.emitted("cancel")).toHaveLength(1);
    wrapper.unmount();
  });
});
