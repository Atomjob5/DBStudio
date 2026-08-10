import { afterEach, describe, expect, it } from "vitest";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus from "element-plus";
import CompletionSchemaDialog from "./CompletionSchemaDialog.vue";
import type { CompletionNamespaceDescriptor } from "../types";

const namespaces: CompletionNamespaceDescriptor[] = [
  { catalog: "", schema: "APP", label: "APP", key: "APP", kind: "schema", system: false, current: true },
  { catalog: "", schema: "REPORTING", label: "REPORTING", key: "REPORTING", kind: "schema", system: false, current: false },
  { catalog: "", schema: "SYS", label: "SYS", key: "SYS", kind: "schema", system: true, current: false },
  { catalog: "", schema: "SYSTEM", label: "SYSTEM", key: "SYSTEM", kind: "schema", system: true, current: false }
];

async function mountDialog(initialSelectedKeys: string[] = []): Promise<VueWrapper> {
  const wrapper = mount(CompletionSchemaDialog, {
    attachTo: document.body,
    props: {
      modelValue: true,
      namespaces,
      initialSelectedKeys,
      refresh: true
    },
    global: { plugins: [ElementPlus], stubs: { teleport: true } }
  });
  await flushPromises();
  return wrapper;
}

function groupInput(wrapper: VueWrapper, label: string): HTMLInputElement {
  const input = wrapper.find(`label[aria-label="${label}"] input`);
  if (!input.exists()) throw new Error(`missing group checkbox ${label}`);
  return input.element as HTMLInputElement;
}

function namespaceInput(wrapper: VueWrapper, label: string): HTMLInputElement {
  const checkbox = wrapper.findAll("label.el-checkbox")
    .find((item) => item.text().includes(label) && !item.text().includes("全选"));
  if (!checkbox) throw new Error(`missing namespace checkbox ${label}`);
  return checkbox.find("input").element as HTMLInputElement;
}

afterEach(() => {
  document.body.innerHTML = "";
});

describe("CompletionSchemaDialog", () => {
  it("shows checked, unchecked, and indeterminate group selection states", async () => {
    const wrapper = await mountDialog(["APP"]);
    const business = groupInput(wrapper, "全选业务 Schema");
    const system = groupInput(wrapper, "全选系统 Schema");

    expect(business.checked).toBe(false);
    expect(business.indeterminate).toBe(true);
    expect(system.checked).toBe(false);
    expect(system.indeterminate).toBe(false);

    await wrapper.setProps({ initialSelectedKeys: ["APP", "REPORTING"] });
    await wrapper.setProps({ modelValue: false });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    expect(groupInput(wrapper, "全选业务 Schema").checked).toBe(true);
    expect(groupInput(wrapper, "全选业务 Schema").indeterminate).toBe(false);
    expect(wrapper.text()).not.toContain("首次默认选择");
    expect(wrapper.text()).not.toContain("默认不选择");
    wrapper.unmount();
  });

  it("selects and clears each group independently", async () => {
    const wrapper = await mountDialog(["APP"]);
    await wrapper.find('label[aria-label="全选系统 Schema"] input').setValue(true);
    await flushPromises();

    expect(namespaceInput(wrapper, "APP").checked).toBe(true);
    expect(namespaceInput(wrapper, "REPORTING").checked).toBe(false);
    expect(namespaceInput(wrapper, "SYS").checked).toBe(true);
    expect(namespaceInput(wrapper, "SYSTEM").checked).toBe(true);

    await wrapper.find('label[aria-label="全选系统 Schema"] input').setValue(false);
    await flushPromises();
    expect(namespaceInput(wrapper, "SYS").checked).toBe(false);
    expect(namespaceInput(wrapper, "SYSTEM").checked).toBe(false);
    expect(namespaceInput(wrapper, "APP").checked).toBe(true);
    wrapper.unmount();
  });

  it("changes only visible items when selecting a filtered group", async () => {
    const wrapper = await mountDialog(["APP", "REPORTING"]);
    const filter = wrapper.find('input[aria-label="筛选补全Schema"]');
    await filter.setValue("REPORT");
    await flushPromises();

    expect(groupInput(wrapper, "全选业务 Schema").checked).toBe(true);
    await wrapper.find('label[aria-label="全选业务 Schema"] input').setValue(false);
    await flushPromises();

    await filter.setValue("");
    await flushPromises();
    expect(namespaceInput(wrapper, "APP").checked).toBe(true);
    expect(namespaceInput(wrapper, "REPORTING").checked).toBe(false);
    expect(groupInput(wrapper, "全选业务 Schema").indeterminate).toBe(true);
    wrapper.unmount();
  });

  it("keeps the cache action disabled when all selections are cleared", async () => {
    const wrapper = await mountDialog(["APP"]);
    await wrapper.find('label[aria-label="全选业务 Schema"] input').setValue(true);
    await wrapper.find('label[aria-label="全选业务 Schema"] input').setValue(false);
    await flushPromises();

    expect(wrapper.find('[data-testid="confirm-completion-schemas"]').attributes("disabled")).toBeDefined();
    wrapper.unmount();
  });

  it("restores initial selections when reopened", async () => {
    const wrapper = await mountDialog(["APP"]);
    await wrapper.find('label[aria-label="全选业务 Schema"] input').setValue(true);
    await wrapper.setProps({ modelValue: false });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();

    expect(namespaceInput(wrapper, "APP").checked).toBe(true);
    expect(namespaceInput(wrapper, "REPORTING").checked).toBe(false);
    wrapper.unmount();
  });
});
