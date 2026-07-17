import { defineStore } from "pinia";
import { ref } from "vue";
import type { ProviderInfo, SavedProfile } from "../types";

export const useConnectionStore = defineStore("connection", () => {
  const providers = ref<ProviderInfo[]>([]);
  const profiles = ref<SavedProfile[]>([]);

  function initialize(providerValues: ProviderInfo[], profileValues: SavedProfile[]): void {
    providers.value = providerValues;
    profiles.value = profileValues;
  }

  function upsert(profile: SavedProfile): void {
    const index = profiles.value.findIndex((item) => item.id === profile.id);
    if (index >= 0) profiles.value[index] = profile;
    else profiles.value.push(profile);
    profiles.value.sort((a, b) => a.name.localeCompare(b.name));
  }

  return { providers, profiles, initialize, upsert };
});
