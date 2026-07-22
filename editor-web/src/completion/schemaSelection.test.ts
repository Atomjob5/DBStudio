import { describe, expect, it } from "vitest";
import { initialCompletionNamespaceKeys } from "./schemaSelection";
import type { CompletionNamespaceDescriptor } from "../types";

const namespaces: CompletionNamespaceDescriptor[] = [
  { key: "schema:APP", catalog: "", schema: "APP", label: "APP", kind: "schema", current: true, system: false },
  { key: "schema:REPORT", catalog: "", schema: "REPORT", label: "REPORT", kind: "schema", current: false, system: false },
  { key: "schema:SYS", catalog: "", schema: "SYS", label: "SYS", kind: "schema", current: false, system: true }
];

describe("completion schema selection defaults", () => {
  it("selects every regular schema but no system schema on the first cache", () => {
    expect(initialCompletionNamespaceKeys(namespaces, undefined, false)).toEqual(["schema:APP", "schema:REPORT"]);
  });

  it("keeps only the old selection on refresh and leaves newly discovered schemas unchecked", () => {
    expect(initialCompletionNamespaceKeys(namespaces, ["schema:APP", "schema:REMOVED"], true)).toEqual(["schema:APP"]);
  });
});
