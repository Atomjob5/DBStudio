import { describe, expect, it, vi } from "vitest";
import { registerCompletionShortcut } from "./monacoCommands";

describe("completion Monaco commands", () => {
  it("registers F6 and invokes Monaco's suggestion action", () => {
    let handler: (() => void) | undefined;
    const trigger = vi.fn();
    const editor = {
      addCommand: vi.fn((keybinding: number, value: () => void) => { handler = value; return "completion.f6"; }),
      trigger
    };

    registerCompletionShortcut(editor, 64);
    expect(editor.addCommand).toHaveBeenCalledWith(64, expect.any(Function));
    handler?.();
    expect(trigger).toHaveBeenCalledWith("keyboard", "editor.action.triggerSuggest", {});
  });
});
