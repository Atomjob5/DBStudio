import { defineStore } from "pinia";
import { ref } from "vue";
import {
  columnIdentityKeys, moveColumnsToEdge, reorderColumns, sameColumnSet, selectHeader,
  type ColumnEdge, type ColumnLayoutScope, type DropSide
} from "../columnLayout";
import type { QueryResult } from "../types";
import type { ResultFilter, ResultSort } from "../resultGrid";

interface StoredLayout {
  sourceOrder: string[];
  order: string[];
  widths: Record<string, number>;
  /** Undefined means every source column is visible. */
  visibleIdentities?: string[];
}

interface ViewState {
  selected: string[];
  anchor?: string;
  filterSignature: string;
  filteredOrder?: string[];
  customSort?: ResultSort;
  customFilters?: ResultFilter[];
}

export interface LayoutContext {
  scope: ColumnLayoutScope;
  executionId: string;
  editorId: string;
  result: QueryResult;
  defaultWidths: number[];
  /** Optional namespace for an adapter layout that must not collide with result columns. */
  namespace?: string;
}

export const useColumnLayoutStore = defineStore("column-layout", () => {
  const layouts = ref<Record<string, StoredLayout>>({});
  const views = ref<Record<string, ViewState>>({});

  function keys(context: LayoutContext): { layoutKey: string; viewKey: string; identities: string[] } {
    const identities = columnIdentityKeys(context.result.columns, context.result.columnDetails);
    const prefix = context.namespace ? `${context.namespace}:` : "";
    const scopeKey = context.scope === "editor"
      ? `editor:${context.editorId}:${context.result.resultIndex}`
      : `result:${context.executionId}:${context.result.resultIndex}`;
    return {
      layoutKey: `${prefix}${scopeKey}`,
      viewKey: context.namespace && context.scope === "editor"
        ? `${prefix}editor:${context.editorId}:${context.result.resultIndex}`
        : `${prefix}${context.executionId}:${context.result.resultIndex}`,
      identities
    };
  }

  function ensure(context: LayoutContext): { layoutKey: string; viewKey: string; identities: string[] } {
    const resolved = keys(context);
    const existing = layouts.value[resolved.layoutKey];
    if (!existing || !sameColumnSet(existing.sourceOrder, resolved.identities)) {
      const widths: Record<string, number> = {};
      resolved.identities.forEach((identity, index) => { widths[identity] = context.defaultWidths[index]; });
      layouts.value = { ...layouts.value, [resolved.layoutKey]: {
        sourceOrder: [...resolved.identities], order: [...resolved.identities], widths
      } };
    } else if (context.scope === "editor") {
      layouts.value = { ...layouts.value, [resolved.layoutKey]: { ...existing, sourceOrder: [...resolved.identities] } };
    }
    ensureView(resolved.viewKey);
    return resolved;
  }

  function ensureView(viewKey: string): ViewState {
    let view = views.value[viewKey];
    if (!view) {
      view = { selected: [], filterSignature: "" };
      views.value = { ...views.value, [viewKey]: view };
    }
    return view;
  }

  function layout(layoutKey: string): StoredLayout | undefined { return layouts.value[layoutKey]; }
  function view(viewKey: string): ViewState { return ensureView(viewKey); }

  function setVisible(layoutKey: string, sourceOrder: string[], visibleIdentities: string[] | undefined): void {
    const stored = layouts.value[layoutKey];
    if (!stored) return;
    const visible = visibleIdentities
      ? sourceOrder.filter((identity) => visibleIdentities.includes(identity))
      : undefined;
    const previous = stored.visibleIdentities;
    if ((previous === undefined && visible === undefined)
        || (previous !== undefined && visible !== undefined
          && previous.length === visible.length && previous.every((identity, index) => identity === visible[index]))) return;
    layouts.value = { ...layouts.value, [layoutKey]: { ...stored, visibleIdentities: visible } };
  }

  function visibleIdentities(layoutKey: string): string[] | undefined {
    const visible = layouts.value[layoutKey]?.visibleIdentities;
    return visible ? [...visible] : undefined;
  }

  function setFilter(viewKey: string, visibleIdentities: string[], filtered: boolean): void {
    const current = ensureView(viewKey);
    const signature = filtered ? visibleIdentities.slice().sort().join("\u0000") : "";
    if (current.filterSignature === signature) return;
    views.value = { ...views.value, [viewKey]: {
      selected: [], anchor: undefined, filterSignature: signature, filteredOrder: undefined
    } };
  }

  function customSort(viewKey: string): ResultSort | undefined {
    return ensureView(viewKey).customSort;
  }

  function customFilters(viewKey: string): ResultFilter[] {
    return [...(ensureView(viewKey).customFilters ?? [])];
  }

  function setCustomSort(viewKey: string, sort: ResultSort | undefined): void {
    const current = ensureView(viewKey);
    views.value = { ...views.value, [viewKey]: { ...current, customSort: sort } };
  }

  function setCustomFilters(viewKey: string, filters: ResultFilter[]): void {
    const current = ensureView(viewKey);
    views.value = { ...views.value, [viewKey]: { ...current, customFilters: [...filters] } };
  }

  function displayedOrder(layoutKey: string, viewKey: string, visibleIdentities: string[], filtered: boolean): string[] {
    const stored = layouts.value[layoutKey];
    if (!stored) return visibleIdentities;
    const visible = new Set(visibleIdentities);
    const base = stored.order.filter((identity) => visible.has(identity));
    const currentView = ensureView(viewKey);
    return filtered && currentView.filteredOrder ? currentView.filteredOrder.filter((identity) => visible.has(identity)) : base;
  }

  function choose(viewKey: string, order: string[], clicked: string, toggle: boolean, range: boolean): void {
    const current = ensureView(viewKey);
    const next = selectHeader(order, current.selected, current.anchor, clicked, { toggle, range });
    views.value = { ...views.value, [viewKey]: { ...current, ...next } };
  }

  function selectOnly(viewKey: string, identity: string): void {
    const current = ensureView(viewKey);
    views.value = { ...views.value, [viewKey]: { ...current, selected: [identity], anchor: identity } };
  }

  function clearSelection(viewKey: string): void {
    const current = ensureView(viewKey);
    if (!current.selected.length && current.anchor === undefined) return;
    views.value = { ...views.value, [viewKey]: { ...current, selected: [], anchor: undefined } };
  }

  function setSelection(viewKey: string, selected: string[], anchor?: string): void {
    const current = ensureView(viewKey);
    views.value = { ...views.value, [viewKey]: { ...current, selected: [...selected], anchor } };
  }

  function reorder(layoutKey: string, viewKey: string, visibleOrder: string[], target: string,
                   side: DropSide, filtered: boolean): void {
    const currentView = ensureView(viewKey);
    const next = reorderColumns(visibleOrder, currentView.selected, target, side);
    if (next === visibleOrder) return;
    if (filtered) {
      views.value = { ...views.value, [viewKey]: { ...currentView, filteredOrder: next } };
      return;
    }
    const stored = layouts.value[layoutKey];
    if (stored) layouts.value = { ...layouts.value, [layoutKey]: { ...stored, order: next } };
  }

  function setWidth(layoutKey: string, identity: string, width: number): void {
    const stored = layouts.value[layoutKey];
    if (!stored) return;
    layouts.value = { ...layouts.value, [layoutKey]: {
      ...stored, widths: { ...stored.widths, [identity]: width }
    } };
  }

  function moveToEdge(layoutKey: string, viewKey: string, visibleOrder: string[], edge: ColumnEdge,
                      filtered: boolean): void {
    const currentView = ensureView(viewKey);
    const next = moveColumnsToEdge(visibleOrder, currentView.selected, edge);
    if (next === visibleOrder) return;
    if (filtered) {
      views.value = { ...views.value, [viewKey]: { ...currentView, filteredOrder: next } };
      return;
    }
    const stored = layouts.value[layoutKey];
    if (stored) layouts.value = { ...layouts.value, [layoutKey]: { ...stored, order: next } };
  }

  function reset(layoutKey: string, viewKey: string, identities: string[], defaultWidths: number[]): void {
    const widths: Record<string, number> = {};
    identities.forEach((identity, index) => { widths[identity] = defaultWidths[index]; });
    layouts.value = { ...layouts.value, [layoutKey]: {
      sourceOrder: [...identities], order: [...identities], widths, visibleIdentities: undefined
    } };
    views.value = { ...views.value, [viewKey]: { selected: [], filterSignature: "", customSort: undefined, customFilters: [] } };
  }

  function orderDirty(layoutKey: string): boolean {
    const stored = layouts.value[layoutKey];
    return !!stored && (stored.order.length !== stored.sourceOrder.length
      || stored.order.some((identity, index) => identity !== stored.sourceOrder[index]));
  }

  function dirty(layoutKey: string, identities: string[], defaultWidths: number[]): boolean {
    const stored = layouts.value[layoutKey];
    if (!stored) return false;
    if (orderDirty(layoutKey)) return true;
    if (identities.some((identity, index) => stored.widths[identity] !== defaultWidths[index])) return true;
    if (stored.visibleIdentities !== undefined
        && (stored.visibleIdentities.length !== identities.length
          || stored.visibleIdentities.some((identity, index) => identity !== identities[index]))) return true;
    return false;
  }

  return { layouts, views, ensure, layout, view, setVisible, visibleIdentities, setFilter, customSort, customFilters,
    setCustomSort, setCustomFilters, displayedOrder, choose, selectOnly, clearSelection, setSelection,
    reorder, moveToEdge, setWidth, reset, orderDirty, dirty };
});
