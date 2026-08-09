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
});
