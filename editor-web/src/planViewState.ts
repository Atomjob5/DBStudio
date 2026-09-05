import { reactive, toRaw } from "vue";
import type { ExecutionPlan } from "./types";

const views = new WeakMap<ExecutionPlan, { mode: string; collapsed: Set<string>; selected: string | undefined }>();
/** State survives editor switches, and is collected when its result is discarded. */
export function planViewState(plan: ExecutionPlan) {
  // Props may be reactive proxies while tests and callers hold the raw plan object.
  // Normalize the key so both access paths share the same per-plan view state.
  const key = toRaw(plan) as ExecutionPlan;
  let state = views.get(key);
  if (!state) {
    state = reactive({ mode: plan.nodes.length ? "tree" : "text", collapsed: new Set<string>(), selected: plan.nodes[0]?.id });
    views.set(key, state);
  }
  return state;
}
