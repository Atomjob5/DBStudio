import { describe, expect, it } from "vitest";
import {
  applyPreset,
  COLOR_SCHEME_PRESETS,
  DEFAULT_COLOR_SCHEMES,
  cloneColorSchemes,
  isValidColorSchemeSettings,
  parseColorSchemeSettings,
  serializeColorSchemeSettings,
} from "./appearance";

describe("color scheme settings", () => {
  it("provides six independent presets for each appearance mode", () => {
    expect(COLOR_SCHEME_PRESETS.filter((item) => item.mode === "light")).toHaveLength(6);
    expect(COLOR_SCHEME_PRESETS.filter((item) => item.mode === "dark")).toHaveLength(6);
    expect(new Set(COLOR_SCHEME_PRESETS.map((item) => item.id)).size).toBe(12);
    for (const preset of COLOR_SCHEME_PRESETS) expect(isValidColorSchemeSettings({
      version: 1,
      light: preset.mode === "light" ? preset.scheme : DEFAULT_COLOR_SCHEMES.light,
      dark: preset.mode === "dark" ? preset.scheme : DEFAULT_COLOR_SCHEMES.dark,
    })).toBe(true);
  });

  it("copies only the selected mode when applying a preset", () => {
    const original = cloneColorSchemes(DEFAULT_COLOR_SCHEMES);
    const next = applyPreset(original, "dark", "dracula");
    expect(next.dark.presetId).toBe("dracula");
    expect(next.dark.editor.background).toBe("#282A36");
    expect(next.light).toEqual(original.light);
    next.dark.editor.keyword.color = "#123456";
    expect(original.dark.editor.keyword.color).not.toBe("#123456");
  });

  it("round-trips valid settings and falls back for invalid payloads", () => {
    const encoded = serializeColorSchemeSettings(DEFAULT_COLOR_SCHEMES);
    expect(parseColorSchemeSettings(encoded)).toEqual(DEFAULT_COLOR_SCHEMES);
    expect(parseColorSchemeSettings("not-json")).toEqual(DEFAULT_COLOR_SCHEMES);
    expect(parseColorSchemeSettings(JSON.stringify({ version: 1 }))).toEqual(DEFAULT_COLOR_SCHEMES);
    const invalid = cloneColorSchemes(DEFAULT_COLOR_SCHEMES);
    invalid.light.editor.fontSize = 25;
    expect(isValidColorSchemeSettings(invalid)).toBe(false);
    const unknown = cloneColorSchemes(DEFAULT_COLOR_SCHEMES) as typeof DEFAULT_COLOR_SCHEMES & { light: typeof DEFAULT_COLOR_SCHEMES.light & { extra?: boolean } };
    unknown.light.extra = true;
    expect(isValidColorSchemeSettings(unknown)).toBe(false);
  });
});
