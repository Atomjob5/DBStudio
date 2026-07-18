import { defineStore } from "pinia";
import { computed, ref } from "vue";
import { normalizeThemePreference, resolveTheme } from "../theme";
import type { BootstrapResponse, ResolvedTheme, ThemePreference } from "../types";

export const useAppStore = defineStore("app", () => {
  const initialized = ref(false);
  const loading = ref(false);
  const themePreference = ref<ThemePreference>("system");
  const systemTheme = ref<ResolvedTheme>("light");
  const theme = computed(() => resolveTheme(themePreference.value, systemTheme.value));
  const status = ref("正在启动…");

  function applyBootstrap(data: BootstrapResponse): void {
    themePreference.value = normalizeThemePreference(data.settings["ui.theme"]);
    initialized.value = true;
    status.value = "未选择链接";
  }

  function setThemePreference(preference: ThemePreference): void {
    themePreference.value = preference;
  }

  function setSystemTheme(theme: ResolvedTheme): void {
    systemTheme.value = theme;
  }

  return {
    initialized, loading, themePreference, systemTheme, theme, status,
    applyBootstrap, setThemePreference, setSystemTheme
  };
});
