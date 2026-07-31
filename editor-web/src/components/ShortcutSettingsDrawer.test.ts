import { describe, expect, it } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import ShortcutSettingsDrawer from "./ShortcutSettingsDrawer.vue";
import { DEFAULT_SHORTCUT_BINDINGS } from "../shortcuts";

function mountDrawer() {
  return mount(ShortcutSettingsDrawer, {
    props: {
      modelValue: true,
      bindings: { ...DEFAULT_SHORTCUT_BINDINGS },
    },
    global: { plugins: [ElementPlus], stubs: { teleport: true } },
  });
}

describe("ShortcutSettingsDrawer", () => {
  it("groups every stable action and records a valid shortcut", async () => {
    const wrapper = mountDrawer();
    await flushPromises();
    expect(wrapper.findAll(".shortcut-settings-section")).toHaveLength(6);
    expect(wrapper.findAll(".shortcut-group")).toHaveLength(5);
    expect(wrapper.findAll(".shortcut-row")).toHaveLength(31);
    expect(wrapper.get(".shortcut-overview").text()).toContain("录制说明");
    expect(wrapper.text()).toContain("主工具栏");
    expect(wrapper.text()).toContain("工作区侧栏");
    expect(wrapper.text()).toContain("SQL 编辑器");
    expect(wrapper.text()).toContain("文件、查询、事务与全局操作 · 13 项");
    expect(wrapper.text()).toContain("对象与连接面板 · 3 项");
    expect(wrapper.text()).toContain("编辑、排版与补全 · 9 项");
    expect(wrapper.text()).toContain("结果复制、布局与导出 · 4 项");
    expect(wrapper.text()).toContain("结果数据加载 · 2 项");
    expect(wrapper.text()).toContain("切换 Minimap");
    expect(wrapper.text()).toContain("切换自动换行");
    expect(wrapper.text()).toContain("压缩 SQL");
    expect(wrapper.text()).toContain("转换大写");
    expect(wrapper.text()).toContain("转换小写");
    expect(wrapper.text()).toContain("单行注释");
    expect(wrapper.text()).toContain("全部注释");

    const recorder = wrapper.get('button[aria-label="录制新建查询快捷键"]');
    await recorder.trigger("click");
    expect(wrapper.emitted("recording")?.[0]).toEqual([true]);
    expect(wrapper.get(".shortcut-row.recording").text()).toContain("请按下快捷键…");
    await recorder.trigger("keydown", { key: "F9", code: "F9" });
    expect(wrapper.emitted("updateBinding")?.[0]).toEqual(["file.newQuery", "F9"]);
    expect(wrapper.emitted("recording")?.at(-1)).toEqual([false]);
    await wrapper.get(".shortcut-reset-button").trigger("click");
    expect(wrapper.emitted("resetDefaults")).toHaveLength(1);
    wrapper.unmount();
  });

  it("rejects reserved and conflicting shortcuts without replacing bindings", async () => {
    const wrapper = mountDrawer();
    await flushPromises();
    const recorder = wrapper.get('button[aria-label="录制新建查询快捷键"]');
    await recorder.trigger("click");
    await recorder.trigger("keydown", { key: "c", code: "KeyC", metaKey: true });
    expect(wrapper.text()).toContain("系统编辑快捷键");
    expect(wrapper.get(".shortcut-feedback").classes()).toContain("shortcut-feedback");
    expect(wrapper.getComponent({ name: "ElAlert" }).props("type")).toBe("warning");
    expect(wrapper.get(".shortcut-row.recording").classes()).toContain("recording");
    expect(wrapper.emitted("updateBinding")).toBeUndefined();

    await recorder.trigger("keydown", { key: "F8", code: "F8" });
    expect(wrapper.text()).toContain("执行当前语句");
    expect(wrapper.emitted("updateBinding")).toBeUndefined();
    wrapper.unmount();
  });

  it("uses Escape to cancel recording and Delete to clear a binding", async () => {
    const wrapper = mountDrawer();
    await flushPromises();
    const recorder = wrapper.get('button[aria-label="录制执行当前语句快捷键"]');
    await recorder.trigger("click");
    await recorder.trigger("keydown", { key: "Escape", code: "Escape" });
    expect(wrapper.emitted("updateBinding")).toBeUndefined();
    expect(wrapper.emitted("recording")?.at(-1)).toEqual([false]);

    await recorder.trigger("click");
    await recorder.trigger("keydown", { key: "Delete", code: "Delete" });
    expect(wrapper.emitted("updateBinding")?.[0]).toEqual(["query.executeCurrent", null]);
    wrapper.unmount();
  });

  it("disables reset, recording and clear controls while saving", async () => {
    const wrapper = mountDrawer();
    await wrapper.setProps({ saving: true });
    await flushPromises();

    expect(wrapper.get(".shortcut-reset-button").attributes("disabled")).toBeDefined();
    expect(wrapper.findAll(".shortcut-recorder")).toHaveLength(31);
    expect(wrapper.findAll(".shortcut-recorder").every((control) => control.attributes("disabled") !== undefined)).toBe(true);
    expect(wrapper.findAll(".shortcut-clear-button").every((control) => control.attributes("disabled") !== undefined)).toBe(true);
    wrapper.unmount();
  });
});
