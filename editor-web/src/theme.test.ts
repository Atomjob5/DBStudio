import { describe, expect, it } from "vitest";
import { applyDocumentTheme, normalizeThemePreference, resolveTheme } from "./theme";

describe("Apple appearance theme", () => {
  it("normalizes persisted preferences", () => {
    expect(normalizeThemePreference("system")).toBe("system");
    expect(normalizeThemePreference("dark")).toBe("dark");
    expect(normalizeThemePreference(undefined)).toBe("system");
  });

  it("resolves the system appearance without overriding explicit choices", () => {
    expect(resolveTheme("system", "dark")).toBe("dark");
    expect(resolveTheme("light", "dark")).toBe("light");
  });

  it("synchronizes document classes and color-scheme", () => {
    applyDocumentTheme("dark");
    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(document.documentElement.classList.contains("dark")).toBe(true);
    expect(document.documentElement.style.colorScheme).toBe("dark");
  });
});
