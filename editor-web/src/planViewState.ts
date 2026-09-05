import { reactive } from "vue";
import type { ExecutionPlan } from "./types";

const views = new WeakMap<ExecutionPlan, { mode: string; collapsed: Set<string>; selected: string | undefined }>();
/** State survives editor switches, and is collected when its result is discarded. */
export function planViewState(plan: ExecutionPlan) {
  let state = views.get(plan);
  if (!state) {
    state = reactive({ mode: plan.nodes.length ? "tree" : "text", collapsed: new Set<string>(), selected: plan.nodes[0]?.id });
    views.set(plan, state);
  }
  return state;
}
