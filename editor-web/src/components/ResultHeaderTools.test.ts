import { defineComponent, nextTick } from "vue";
import { describe, expect, it } from "vitest";
import { mount, type VueWrapper } from "@vue/test-utils";
import ResultHeaderTools from "./ResultHeaderTools.vue";
import type { QueryColumn } from "../types";

const PopoverStub = defineComponent({
  name: "ElPopover",
  props: { visible: Boolean },
  emits: ["update:visible"],
  template: '<div><slot name="reference" /><div class="popover-content"><slot /></div></div>',
});
const SelectStub = defineComponent({
  name: "ElSelect",
  props: { modelValue: { type: String, default: "" }, teleported: { type: Boolean, default: true } },
  emits: ["update:modelValue"],
  template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>',
});
const OptionStub = defineComponent({
  name: "ElOption",
  props: { label: { type: String, required: true }, value: { type: String, required: true } },
  template: '<option :value="value">{{ label }}</option>',
});
const InputStub = defineComponent({
  name: "ElInput",
  props: { modelValue: { type: String, default: "" } },
  emits: ["update:modelValue"],
  template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
});
const ButtonStub = defineComponent({
  name: "ElButton",
  props: { disabled: Boolean },
  template: '<button :disabled="disabled"><slot /></button>',
});
const TooltipStub = defineComponent({ name: "ElTooltip", template: "<slot />" });

const numberColumn: QueryColumn = {
  label: "amount",
  name: "amount",
  remarks: "金额",
  catalog: "sales",
  schema: "",
  table: "orders",
  typeName: "INTEGER",
  jdbcType: 4,
};

function mountTools(column: QueryColumn = numberColumn) {
  return mount(ResultHeaderTools, {
    props: {
      column,
      columnIndex: 0,
      sortingEnabled: true,
      filteringEnabled: true,
    },
    global: {
      stubs: {
        ElPopover: PopoverStub,
        ElSelect: SelectStub,
        ElOption: OptionStub,
        ElInput: InputStub,
        ElButton: ButtonStub,
        ElTooltip: TooltipStub,
      },
    },
  });
}

async function openFilter(wrapper: VueWrapper): Promise<void> {
  wrapper.getComponent(PopoverStub).vm.$emit("update:visible", true);
  await nextTick();
}

describe("ResultHeaderTools", () => {
  it("keeps the condition selector popper inside the filter popover", async () => {
    const wrapper = mountTools();
    await openFilter(wrapper);

    const selects = wrapper.findAllComponents(SelectStub);
    expect(selects).toHaveLength(1);
    expect(selects[0].props("teleported")).toBe(false);
    expect(wrapper.getComponent(PopoverStub).props("visible")).toBe(true);
    await wrapper.get(".result-filter-editor").trigger("keydown", { key: "Escape" });
    expect(wrapper.getComponent(PopoverStub).props("visible")).toBe(false);
    await openFilter(wrapper);
    await wrapper.get(".result-filter-editor").trigger("keydown", { key: "Esc" });
    expect(wrapper.getComponent(PopoverStub).props("visible")).toBe(false);
    wrapper.unmount();
  });

  it("keeps both boolean selectors inside the filter popover", async () => {
    const wrapper = mountTools({
      ...numberColumn,
      label: "enabled",
      name: "enabled",
      typeName: "BOOLEAN",
      jdbcType: 16,
    });
    await openFilter(wrapper);

    const selects = wrapper.findAllComponents(SelectStub);
    expect(selects).toHaveLength(2);
    expect(selects.every((select) => select.props("teleported") === false)).toBe(true);
    wrapper.unmount();
  });

  it("preserves apply and clear behavior and disables an incomplete filter", async () => {
    const wrapper = mountTools();
    await openFilter(wrapper);

    const applyButton = wrapper.findAll("button").find((button) => button.text() === "应用");
    const clearButton = wrapper.findAll("button").find((button) => button.text() === "清除");
    expect(applyButton).toBeDefined();
    expect(clearButton).toBeDefined();
    expect(applyButton!.attributes("disabled")).toBeDefined();
    expect(clearButton!.attributes("disabled")).toBeDefined();

    await wrapper.get('input[aria-label="筛选值"]').setValue("42");
    expect(applyButton!.attributes("disabled")).toBeUndefined();
    await applyButton!.trigger("click");
    expect(wrapper.emitted("apply")?.[0]).toEqual([{ columnIndex: 0, operator: "eq", value: "42" }]);

    await wrapper.setProps({ filter: { columnIndex: 0, operator: "eq", value: "42" } });
    await openFilter(wrapper);
    const enabledClearButton = wrapper.findAll("button").find((button) => button.text() === "清除");
    expect(enabledClearButton?.attributes("disabled")).toBeUndefined();
    await enabledClearButton!.trigger("click");
    expect(wrapper.emitted("clear")).toHaveLength(1);
    wrapper.unmount();
  });
});
