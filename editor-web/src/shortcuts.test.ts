import { describe, expect, it } from "vitest";
import {
  DEFAULT_SHORTCUT_BINDINGS,
  actionForShortcut,
  displayShortcut,
  findShortcutConflict,
  isDangerousLegacyShortcut,
  parseShortcutBindings,
  serializeShortcutBindings,
  shortcutFromKeyboardEvent,
  validateShortcutBinding,
} from "./shortcuts";

function event(key: string, options: Partial<KeyboardEvent> = {}) {
  return {
    key,
    code: options.code ?? "",
    metaKey: options.metaKey ?? false,
    ctrlKey: options.ctrlKey ?? false,
    altKey: options.altKey ?? false,
    shiftKey: options.shiftKey ?? false,
  };
}

describe("shortcuts", () => {
  it("uses only the three requested default bindings", () => {
    expect(DEFAULT_SHORTCUT_BINDINGS["query.executeCurrent"]).toBe("F8");
    expect(DEFAULT_SHORTCUT_BINDINGS["query.executeAll"]).toBe("F7");
    expect(DEFAULT_SHORTCUT_BINDINGS["query.cancel"]).toBe("Shift+Escape");
    expect(Object.values(DEFAULT_SHORTCUT_BINDINGS).filter(Boolean)).toHaveLength(3);
  });

  it("normalizes Ctrl and Command as Mod", () => {
    expect(shortcutFromKeyboardEvent(event("s", { ctrlKey: true }))).toBe("Mod+S");
    expect(shortcutFromKeyboardEvent(event("s", { metaKey: true, shiftKey: true }))).toBe("Mod+Shift+S");
  });

  it("validates function keys and Shift+Escape", () => {
    expect(validateShortcutBinding("F12")).toEqual({ valid: true, binding: "F12" });
    expect(validateShortcutBinding("Shift+Esc")).toEqual({ valid: true, binding: "Shift+Escape" });
    expect(validateShortcutBinding("Escape").valid).toBe(false);
  });

  it("rejects unsafe single keys and reserved editing shortcuts", () => {
    expect(validateShortcutBinding("A").valid).toBe(false);
    expect(validateShortcutBinding("Enter").valid).toBe(false);
    expect(validateShortcutBinding("Mod+C").valid).toBe(false);
    expect(validateShortcutBinding("Mod+V").valid).toBe(false);
  });

  it("finds conflicts without replacing the existing action", () => {
    const bindings = { ...DEFAULT_SHORTCUT_BINDINGS };
    expect(findShortcutConflict(bindings, "F8", "query.executeAll")).toBe("query.executeCurrent");
    expect(actionForShortcut(bindings, "F7")).toBe("query.executeAll");
  });

  it("does not retain duplicate bindings from malformed persisted settings", () => {
    const parsed = parseShortcutBindings(JSON.stringify({
      "query.executeCurrent": "F7",
      "query.executeAll": "F7",
    }));
    expect(Object.values(parsed).filter((binding) => binding === "F7")).toHaveLength(1);
  });

  it("parses explicit nulls and falls back for invalid or missing actions", () => {
    const parsed = parseShortcutBindings(JSON.stringify({
      "query.executeCurrent": null,
      "query.executeAll": "Mod+7",
      "query.cancel": "Escape",
    }));
    expect(parsed["query.executeCurrent"]).toBeNull();
    expect(parsed["query.executeAll"]).toBe("Mod+7");
    expect(parsed["query.cancel"]).toBe("Shift+Escape");
    expect(parsed["file.newQuery"]).toBeNull();
    expect(parseShortcutBindings("{broken")).toEqual(DEFAULT_SHORTCUT_BINDINGS);
    expect(JSON.parse(serializeShortcutBindings(parsed))["query.executeCurrent"]).toBeNull();
  });

  it("formats platform-specific Mod labels and recognizes dangerous legacy keys", () => {
    expect(displayShortcut("Mod+Shift+S", true)).toBe("⌘⇧S");
    expect(displayShortcut("Mod+Shift+S", false)).toBe("Ctrl+Shift+S");
    expect(isDangerousLegacyShortcut("F5")).toBe(true);
    expect(isDangerousLegacyShortcut("Mod+C")).toBe(false);
  });
});
