import { defineStore } from "pinia";
import { computed, ref } from "vue";
import type { ConnectionCatalog, ConnectionEnvironment, ConnectionSystem, ProviderInfo, SavedProfile } from "../types";

export const useConnectionStore = defineStore("connection", () => {
  const providers = ref<ProviderInfo[]>([]);
  const profiles = ref<SavedProfile[]>([]);
  const systems = ref<ConnectionSystem[]>([]);
  const environments = ref<ConnectionEnvironment[]>([]);

  function initialize(providerValues: ProviderInfo[], profileValues: SavedProfile[],
                      systemValues: ConnectionSystem[] = [], environmentValues: ConnectionEnvironment[] = []): void {
    providers.value = providerValues;
    profiles.value = profileValues;
    systems.value = systemValues;
    environments.value = environmentValues;
  }

  function applyCatalog(catalog: ConnectionCatalog): void {
    systems.value = [...catalog.systems].sort(byName);
    environments.value = [...catalog.environments].sort(byName);
    profiles.value = [...catalog.profiles].sort(byName);
  }

  function upsert(profile: SavedProfile): void {
    const index = profiles.value.findIndex((item) => item.id === profile.id);
    if (index >= 0) profiles.value[index] = profile;
    else profiles.value.push(profile);
    profiles.value.sort((a, b) => a.name.localeCompare(b.name));
  }

  const cascaderOptions = computed(() => systems.value.map((system) => ({
    value: system.id, label: system.name, selectable: false,
    children: environments.value.filter((environment) => environment.systemId === system.id).map((environment) => ({
      value: environment.id, label: environment.name, selectable: false,
      children: profiles.value.filter((profile) => profile.environmentId === environment.id).map((profile) => ({
        value: `${profile.id}@${profile.revision}`,
        label: `${environment.name} / ${profile.name}`,
        menuLabel: profile.name,
        profileId: profile.id,
        leaf: true
      }))
    }))
  })));

  function pathFor(profile: SavedProfile | undefined): string {
    if (!profile) return "未选择链接";
    const environment = environments.value.find((item) => item.id === profile.environmentId);
    const system = systems.value.find((item) => item.id === environment?.systemId);
    return [system?.name, environment?.name, profile.name].filter(Boolean).join(" / ");
  }

  function current(profileId: string): SavedProfile | undefined { return profiles.value.find((item) => item.id === profileId); }

  function byName(left: { name: string }, right: { name: string }): number { return left.name.localeCompare(right.name, "zh-CN"); }

  return { providers, profiles, systems, environments, cascaderOptions, initialize, applyCatalog, upsert, pathFor, current };
});
