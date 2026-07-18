import { defineStore } from "pinia";
import { ref } from "vue";
import {
  columnIdentityKeys, moveColumnsToEdge, reorderColumns, sameColumnSet, selectHeader,
  type ColumnEdge, type ColumnLayoutScope, type DropSide
} from "../columnLayout";
import type { QueryResult } from "../types";

interface StoredLayout {
  sourceOrder: string[];
  order: string[];
  widths: Record<string, number>;
}

interface ViewState {
  selected: string[];
  anchor?: string;
  filterSignature: string;
  filteredOrder?: string[];
}

export interface LayoutContext {
  scope: ColumnLayoutScope;
  executionId: string;
  editorId: string;
  result: QueryResult;
  defaultWidths: number[];
}

export const useColumnLayoutStore = defineStore("column-layout", () => {
  const layouts = ref<Record<string, StoredLayout>>({});
  const views = ref<Record<string, ViewState>>({});

  function keys(context: LayoutContext): { layoutKey: string; viewKey: string; identities: string[] } {
    const identities = columnIdentityKeys(context.result.columns, context.result.columnDetails);
    return {
      layoutKey: context.scope === "editor"
        ? `editor:${context.editorId}:${context.result.resultIndex}`
        : `result:${context.executionId}:${context.result.resultIndex}`,
      viewKey: `${context.executionId}:${context.result.resultIndex}`,
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

  function setFilter(viewKey: string, visibleIdentities: string[], filtered: boolean): void {
    const current = ensureView(viewKey);
    const signature = filtered ? visibleIdentities.slice().sort().join("\u0000") : "";
    if (current.filterSignature === signature) return;
    views.value = { ...views.value, [viewKey]: {
      selected: [], anchor: undefined, filterSignature: signature, filteredOrder: undefined
    } };
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
      sourceOrder: [...identities], order: [...identities], widths
    } };
    views.value = { ...views.value, [viewKey]: { selected: [], filterSignature: "" } };
  }

  function orderDirty(layoutKey: string): boolean {
    const stored = layouts.value[layoutKey];
    return !!stored && (stored.order.length !== stored.sourceOrder.length
      || stored.order.some((identity, index) => identity !== stored.sourceOrder[index]));
  }

  return { layouts, views, ensure, layout, view, setFilter, displayedOrder, choose, selectOnly,
    reorder, moveToEdge, setWidth, reset, orderDirty };
});
