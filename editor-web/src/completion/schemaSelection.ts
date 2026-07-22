import type { CompletionNamespaceDescriptor } from "../types";

export function initialCompletionNamespaceKeys(namespaces: CompletionNamespaceDescriptor[],
                                               previousKeys: string[] | undefined,
                                               refresh: boolean): string[] {
  if (!refresh) return namespaces.filter((item) => !item.system).map((item) => item.key);
  const available = new Set(namespaces.map((item) => item.key));
  return (previousKeys ?? []).filter((key) => available.has(key));
}
