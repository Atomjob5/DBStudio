import { afterEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import ResultLargeValueDialog from "./ResultLargeValueDialog.vue";
import { rpc } from "../bridge/rpc";

async function mountDialog(family: "raw" | "clob" | "blob", value: string | null = null) {
  if (family !== "blob") vi.spyOn(rpc, "readResultLargeValue").mockResolvedValue(family === "clob"
    ? new Blob([value ?? ""]) : new Blob([new Uint8Array([1])]));
  const wrapper = mount(ResultLargeValueDialog, {
    attachTo: document.body,
    props: {
      modelValue: true,
      family,
      value,
      maxBytes: 1024,
      editorId: "editor-1",
      executionId: "execution-1",
      resultIndex: 2,
      rowId: "row-1",
      columnIndex: 3,
      columnName: "payload"
    },
    global: { plugins: [ElementPlus], stubs: { teleport: true } }
  });
  await flushPromises();
  return wrapper;
}

function button(label: string): HTMLButtonElement {
  const match = Array.from(document.body.querySelectorAll("button"))
    .find((item) => item.textContent?.trim() === label);
  if (!match) throw new Error(`missing button ${label}`);
  return match as HTMLButtonElement;
}

afterEach(() => {
  document.body.innerHTML = "";
  vi.restoreAllMocks();
});

describe("ResultLargeValueDialog", () => {
  it("validates and normalizes RAW hexadecimal drafts", async () => {
    const wrapper = await mountDialog("raw", "0x01");
    const editor = wrapper.find("textarea");
    await editor.setValue("abc");
    button("保存草稿").click();
    await wrapper.vm.$nextTick();
    expect(wrapper.emitted("save")).toBeUndefined();

    await editor.setValue("0aff");
    button("保存草稿").click();
    await wrapper.vm.$nextTick();
    expect(wrapper.emitted("save")?.at(-1)).toEqual([{ kind: "text", value: "0x0aff" }]);
    wrapper.unmount();
  });

  it("uploads BLOB files as opaque large-value tokens", async () => {
    const upload = vi.spyOn(rpc, "uploadResultLargeValue")
      .mockResolvedValue({ token: "draft-token", size: 4 });
    const wrapper = await mountDialog("blob");
    const input = wrapper.find<HTMLInputElement>('input[type="file"]');
    const file = new File([new Uint8Array([1, 2, 3, 4])], "payload.bin");
    Object.defineProperty(input.element, "files", { configurable: true, value: [file] });
    await input.trigger("change");
    button("保存草稿").click();
    await vi.waitFor(() => expect(upload).toHaveBeenCalledWith(
      "editor-1", "execution-1", 2, 3, file));
    expect(wrapper.emitted("save")?.at(-1)).toEqual([
      { kind: "largeValueToken", value: "draft-token" }
    ]);
    wrapper.unmount();
  });

  it("stores edited CLOB text through the streaming draft endpoint", async () => {
    const upload = vi.spyOn(rpc, "uploadResultLargeValue")
      .mockResolvedValue({ token: "clob-token", size: 12 });
    const wrapper = await mountDialog("clob", "完整原值");
    await wrapper.find("textarea").setValue("修改后的完整文本");
    button("保存草稿").click();
    await vi.waitFor(() => expect(upload).toHaveBeenCalled());
    const file = upload.mock.calls[0][4];
    expect(file).toBeInstanceOf(File);
    expect(await file.text()).toBe("修改后的完整文本");
    expect(wrapper.emitted("save")?.at(-1)).toEqual([
      { kind: "largeValueToken", value: "clob-token" }
    ]);
    wrapper.unmount();
  });

  it("exports an existing server-owned value", async () => {
    const download = vi.spyOn(rpc, "downloadResultLargeValue").mockResolvedValue();
    const wrapper = await mountDialog("clob", "preview");
    button("导出原值").click();
    await vi.waitFor(() => expect(download).toHaveBeenCalledWith(
      "editor-1", "execution-1", 2, "row-1", 3, "payload.txt"));
    wrapper.unmount();
  });
});
