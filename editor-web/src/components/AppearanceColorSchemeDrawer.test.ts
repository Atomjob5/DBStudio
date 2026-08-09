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

  it("exposes every editor color and binds every text style to the preview", async () => {
    const wrapper = mountDrawer();
    await flushPromises();

    for (const label of ["行号", "当前行号", "光标"]) {
      expect(wrapper.find(`input[aria-label="${label}"]`).exists()).toBe(true);
    }
    await wrapper.get('input[aria-label="行号"]').setValue("#101010");
    await wrapper.get('input[aria-label="当前行号"]').setValue("#202020");
    await wrapper.get('input[aria-label="光标"]').setValue("#303030");
    await wrapper.get('input[aria-label="普通标识符颜色"]').setValue("#445566");
    await wrapper.get('input[aria-label="字符串颜色"]').setValue("#556677");
    await wrapper.get('input[aria-label="数字颜色"]').setValue("#667788");
    await wrapper.get('input[aria-label="注释颜色"]').setValue("#778899");
    await wrapper.get('input[aria-label="引用标识符颜色"]').setValue("#8899AA");

    const style = wrapper.get(".appearance-preview").attributes("style");
    expect(style).toContain("--preview-editor-line-number: #101010");
    expect(style).toContain("--preview-editor-active-line-number: #202020");
    expect(style).toContain("--preview-editor-cursor: #303030");
    expect(wrapper.findAll(".preview-line")).toHaveLength(4);
    expect(wrapper.find(".preview-current-line").exists()).toBe(true);
    expect(wrapper.find(".preview-selection").exists()).toBe(true);
    expect(wrapper.find(".preview-cursor").exists()).toBe(true);
    expect(wrapper.findAll(".preview-result-row-number")).toHaveLength(3);

    for (const token of ["keyword", "identifier", "string", "number", "comment", "quoted"]) {
      expect(wrapper.find(`.preview-${token}`).exists()).toBe(true);
    }
    for (const selector of [".preview-result-cell", ".preview-result-header", ".preview-null", ".preview-binary", ".preview-result-row-number"]) {
      expect(wrapper.find(selector).exists()).toBe(true);
    }
    wrapper.unmount();
  });
});
