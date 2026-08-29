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
      version: 3,
      light: preset.mode === "light" ? preset.scheme : DEFAULT_COLOR_SCHEMES.light,
      dark: preset.mode === "dark" ? preset.scheme : DEFAULT_COLOR_SCHEMES.dark,
    })).toBe(true);
  });

  it("assigns a palette-specific comparison highlight to every preset", () => {
    const expected = {
      "vs-light": "#FFF4CE", "github-light": "#FFF8C5", "solarized-light": "#EFE4B0",
      "one-light": "#F4E7B2", "quiet-light": "#F2E6B8", "intellij-light": "#FFF1B8",
      "dark-plus": "#4B4628", "one-dark-pro": "#3F4938", dracula: "#4D4934",
      monokai: "#4A482F", nord: "#424A3A", "solarized-dark": "#3F492B",
    } as const;
    expect(Object.fromEntries(COLOR_SCHEME_PRESETS.map((preset) => [
      preset.id, preset.scheme.result.compareHighlightBackground,
    ]))).toEqual(expected);
  });

  it("copies only the selected mode when applying a preset", () => {
    const original = cloneColorSchemes(DEFAULT_COLOR_SCHEMES);
    original.dark.result.loadingAnimation = "wave-physics";
    const next = applyPreset(original, "dark", "dracula");
    expect(next.dark.presetId).toBe("dracula");
    expect(next.dark.editor.background).toBe("#282A36");
    expect(next.light).toEqual(original.light);
    expect(next.dark.result.loadingAnimation).toBe("wave-physics");
    next.dark.editor.keyword.color = "#123456";
    expect(original.dark.editor.keyword.color).not.toBe("#123456");
  });

  it("accepts and serializes the SQL Timeline loading animation", () => {
    const settings = cloneColorSchemes(DEFAULT_COLOR_SCHEMES);
    settings.light.result.loadingAnimation = "sql-timeline";
    expect(isValidColorSchemeSettings(settings)).toBe(true);
    expect(parseColorSchemeSettings(serializeColorSchemeSettings(settings)).light.result.loadingAnimation)
      .toBe("sql-timeline");
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

  it("migrates complete version 1 settings without losing customized colors", () => {
    const legacy = JSON.parse(serializeColorSchemeSettings(DEFAULT_COLOR_SCHEMES));
    legacy.version = 1;
    delete legacy.light.result.stripeBackground;
    delete legacy.light.result.compareHighlightBackground;
    delete legacy.dark.result.stripeBackground;
    delete legacy.dark.result.compareHighlightBackground;
    legacy.light.editor.background = "#123456";
    const migrated = parseColorSchemeSettings(JSON.stringify(legacy));
    expect(migrated.version).toBe(3);
    expect(migrated.light.editor.background).toBe("#123456");
    expect(migrated.light.result.stripeBackground).toBe("#F7F7F9");
    expect(migrated.dark.result.compareHighlightBackground).toBe("#554515");
    expect(migrated.light.result.loadingAnimation).toBe("dbstudio");
  });

  it("migrates version 2 settings to the DBStudio loading animation", () => {
    const legacy = JSON.parse(serializeColorSchemeSettings(DEFAULT_COLOR_SCHEMES));
    legacy.version = 2;
    delete legacy.light.result.loadingAnimation;
    delete legacy.dark.result.loadingAnimation;
    const migrated = parseColorSchemeSettings(JSON.stringify(legacy));
    expect(migrated.version).toBe(3);
    expect(migrated.light.result.loadingAnimation).toBe("dbstudio");
    expect(migrated.dark.result.loadingAnimation).toBe("dbstudio");
  });
});
