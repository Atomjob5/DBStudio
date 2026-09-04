import { defineComponent, nextTick } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ObjectInspectorHost, { type ObjectInspectorOpenRequest } from "./ObjectInspectorHost.vue";

const rpcRequest = vi.hoisted(() => vi.fn());
const streamObjectDdl = vi.hoisted(() => vi.fn());
vi.mock("../bridge/rpc", () => ({ rpc: { request: rpcRequest, streamObjectDdl } }));

const PopoverStub = defineComponent({
  name: "ElPopover",
  props: { visible: Boolean },
  emits: ["update:visible"],
  template: `<span class="popover-stub">
    <span class="popover-reference" @click="$emit('update:visible', !visible)"><slot name="reference" /></span>
    <div v-if="visible" class="object-inspector-opacity-popover"><slot /></div>
  </span>`,
});
const SliderStub = defineComponent({
  name: "ElSlider",
  props: { modelValue: { type: Number, required: true } },
  emits: ["update:modelValue", "change"],
  template: `<input class="opacity-slider" type="range" min="1" max="100" :value="modelValue"
    @input="$emit('update:modelValue', Number($event.target.value))"
    @change="$emit('change', Number($event.target.value))" />`,
});
const IconStub = defineComponent({ name: "ElIcon", template: "<span class=\"icon-stub\"><slot /></span>" });
const DdlViewerStub = defineComponent({ name: "ObjectDdlViewer", props: { value: String }, template: "<pre />" });

const request: ObjectInspectorOpenRequest = {
  reference: { catalog: "sales", schema: "", objectName: "orders", viaAlias: false, quoted: false, range: { start: 0, end: 5 } },
  editorId: "editor-1", modelKey: "model-1", connectionDisplay: "DEV / sales", x: 20, y: 20,
};

function mountHost(): VueWrapper {
  return mount(ObjectInspectorHost, {
    props: { opacity: 100 },
    global: {
      stubs: { ElPopover: PopoverStub, ElSlider: SliderStub, ElIcon: IconStub, ObjectDdlViewer: DdlViewerStub },
    },
  });
}

beforeEach(() => {
  rpcRequest.mockReset();
  streamObjectDdl.mockReset();
  rpcRequest.mockResolvedValue({ object: { type: "TABLE", name: "orders", qualifiedName: "sales.orders" }, items: [] });
});

afterEach(() => vi.restoreAllMocks());

describe("ObjectInspectorHost visual controls", () => {
  it("opens the compact opacity popover, updates live, and keeps the window on first Escape", async () => {
    const wrapper = mountHost();
    (wrapper.vm as unknown as { open: (value: ObjectInspectorOpenRequest) => void }).open(request);
    await flushPromises();

    const opacityButton = document.body.querySelector<HTMLButtonElement>('button[aria-label="窗口透明度"]')!;
    expect(opacityButton.getAttribute("aria-expanded")).toBe("false");
    opacityButton.click();
    await nextTick();
    const slider = document.body.querySelector<HTMLInputElement>(".opacity-slider")!;
    expect(slider).not.toBeNull();

    slider.value = "42";
    slider.dispatchEvent(new Event("input", { bubbles: true }));
    await nextTick();
    expect(wrapper.emitted("update:opacity")?.at(-1)).toEqual([42]);

    window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
    await nextTick();
    expect(document.body.querySelector(".object-inspector")).not.toBeNull();
    expect(document.body.querySelector(".opacity-slider")).toBeNull();

    window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
    await nextTick();
    expect(document.body.querySelector(".object-inspector")).toBeNull();
    wrapper.unmount();
  });

  it("renders consistent icon controls and an opaque panel at 100%", async () => {
    const wrapper = mountHost();
    (wrapper.vm as unknown as { open: (value: ObjectInspectorOpenRequest) => void }).open(request);
    await flushPromises();

    expect(document.body.querySelectorAll(".icon-button")).toHaveLength(3);
    expect(document.body.querySelector(".inspector-titlebar")).not.toBeNull();
    expect(document.body.querySelector(".inspector-tabs")).not.toBeNull();
    expect(document.body.querySelector(".object-inspector")).not.toBeNull();
    wrapper.unmount();
  });

  it("renders remarks, supports native cell selection, and exposes resizable columns", async () => {
    rpcRequest.mockResolvedValueOnce({
      object: { catalog: "sales", schema: "", name: "orders", type: "TABLE", remarks: "订单", qualifiedName: "sales.orders" },
      items: [{ name: "customer_name", typeName: "VARCHAR2", length: 255, precision: 0, scale: 0,
        nullable: true, defaultValue: null, primaryKey: false, autoIncrement: false, generated: false,
        remarks: "客户姓名备注", ordinal: 1 }],
    });
    const wrapper = mountHost();
    (wrapper.vm as unknown as { open: (value: ObjectInspectorOpenRequest) => void }).open(request);
    await flushPromises();

    const inspector = document.body.querySelector<HTMLElement>(".object-inspector")!;
    expect(inspector.textContent).toContain("客户姓名备注");
    expect(inspector.querySelectorAll(".grid-column-resize-handle")).toHaveLength(9);
    expect(inspector.querySelectorAll(".structure-grid col")).toHaveLength(10);
    expect(inspector.querySelectorAll(".structure-grid .grid-trailing-gutter")).toHaveLength(3);
    expect(inspector.querySelector<HTMLElement>(".structure-grid th.grid-trailing-gutter")?.getAttribute("aria-hidden")).toBe("true");
    expect(Number.parseFloat(inspector.querySelector<HTMLTableElement>(".structure-grid")?.style.width || "0")).toBe(1173);
    const remarkCell = inspector.querySelector<HTMLElement>('td[title="客户姓名备注"]')!;
    expect(remarkCell.textContent).toBe("客户姓名备注");

    const nameHandle = inspector.querySelector<HTMLElement>('.grid-column-resize-handle[aria-label="调整 字段名 列宽"]')!;
    const firstColumn = inspector.querySelector<HTMLTableColElement>("col")!;
    const originalWidth = firstColumn.style.width;
    nameHandle.dispatchEvent(new MouseEvent("pointerdown", { bubbles: true, clientX: 100 }));
    window.dispatchEvent(new MouseEvent("pointermove", { bubbles: true, clientX: 180 }));
    window.dispatchEvent(new MouseEvent("pointerup", { bubbles: true, clientX: 180 }));
    await nextTick();
    expect(firstColumn.style.width).not.toBe(originalWidth);

    nameHandle.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowLeft", bubbles: true }));
    await nextTick();
    expect(firstColumn.style.width).not.toBe(originalWidth);

    const remarkHandle = inspector.querySelector<HTMLElement>('.grid-column-resize-handle[aria-label="调整 备注 列宽"]')!;
    remarkHandle.dispatchEvent(new MouseEvent("dblclick", { bubbles: true }));
    await nextTick();
    const remarkWidth = Number.parseFloat(inspector.querySelectorAll<HTMLTableColElement>("col")[8].style.width);
    expect(remarkWidth).toBeGreaterThanOrEqual(56);
    expect(remarkWidth).toBeLessThanOrEqual(640);

    nameHandle.dispatchEvent(new MouseEvent("pointerdown", { bubbles: true, clientX: 100 }));
    window.dispatchEvent(new MouseEvent("pointermove", { bubbles: true, clientX: 2_000 }));
    window.dispatchEvent(new MouseEvent("pointerup", { bubbles: true, clientX: 2_000 }));
    await nextTick();
    expect(Number.parseFloat(firstColumn.style.width)).toBe(640);

    nameHandle.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowLeft", bubbles: true }));
    for (let index = 0; index < 100; index += 1) {
      nameHandle.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowLeft", bubbles: true }));
    }
    await nextTick();
    expect(Number.parseFloat(firstColumn.style.width)).toBe(56);
    wrapper.unmount();
  });

  it("keeps widths when switching structure tabs but resets after reopening", async () => {
    rpcRequest.mockResolvedValue({
      object: { catalog: "sales", schema: "", name: "orders", type: "TABLE", remarks: "", qualifiedName: "sales.orders" },
      items: [],
    });
    const wrapper = mountHost();
    const vm = wrapper.vm as unknown as { open: (value: ObjectInspectorOpenRequest) => void };
    vm.open(request);
    await flushPromises();
    const inspector = document.body.querySelector<HTMLElement>(".object-inspector")!;
    const handle = inspector.querySelector<HTMLElement>('.grid-column-resize-handle[aria-label="调整 字段名 列宽"]')!;
    const firstColumn = inspector.querySelector<HTMLTableColElement>("col")!;
    handle.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowRight", bubbles: true }));
    await nextTick();
    const changedWidth = firstColumn.style.width;
    const tabButtons = inspector.querySelectorAll<HTMLButtonElement>(".inspector-tabs button");
    for (const tabIndex of [1, 2]) {
      tabButtons[tabIndex].click();
      await flushPromises();
      expect(inspector.querySelectorAll(".structure-grid col")).toHaveLength(10);
      expect(inspector.querySelectorAll(".grid-column-resize-handle")).toHaveLength(9);
      expect(inspector.querySelector(".structure-grid th.grid-trailing-gutter")).not.toBeNull();
    }
    tabButtons[0].click();
    await nextTick();
    expect(inspector.querySelector<HTMLTableColElement>("col")!.style.width).toBe(changedWidth);
    await inspector.querySelector<HTMLButtonElement>('button[aria-label="关闭"]')?.click();
    vm.open(request);
    await flushPromises();
    expect(document.body.querySelector<HTMLTableColElement>(".object-inspector col")!.style.width).not.toBe(changedWidth);
    wrapper.unmount();
  });
});
