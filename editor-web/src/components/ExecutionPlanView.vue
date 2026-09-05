<template>
  <section class="plan-view" aria-label="执行计划">
    <el-alert v-if="plan.warning" :title="plan.warning" type="info" :closable="false" />
    <pre v-if="mode === 'text'" class="plan-text" tabindex="0">{{ plan.rawText }}</pre>
    <div v-else class="plan-tree">
      <div class="plan-table-scroll"><table aria-label="计划算子">
        <thead><tr><th>算子</th><th>对象</th><th>访问方式</th><th>索引</th><th>估算行数</th><th>成本</th></tr></thead>
        <tbody><tr v-for="row in visible" :key="row.node.id" tabindex="0"
                   :class="{ selected: selected === row.node.id }" :aria-selected="selected === row.node.id"
                   @click="selected = row.node.id" @keydown.enter="selected = row.node.id">
          <td :style="{ paddingLeft: `${row.depth * 18 + 8}px` }">
            <button v-if="children.has(row.node.id)" class="plan-expand" :aria-expanded="!collapsed.has(row.node.id)"
                    :aria-label="`${collapsed.has(row.node.id) ? '展开' : '折叠'} ${row.node.operation}`"
                    @click.stop="toggle(row.node.id)">{{ collapsed.has(row.node.id) ? '+' : '−' }}</button>
            <span v-else class="plan-expand"></span>{{ row.node.operation }}
            <small v-if="marker(row.node)" :title="`依据：${row.node.operation} ${row.node.access || ''}`">{{ marker(row.node) }}</small>
          </td>
          <td>{{ display(row.node.object) }}</td><td>{{ display(row.node.access) }}</td><td>{{ display(row.node.index) }}</td>
          <td>{{ display(row.node.estimatedRows) }}</td><td>{{ display(row.node.cost) }}</td>
        </tr></tbody>
      </table></div>
      <div v-if="selectedNode" class="plan-details" aria-label="算子详情">
        <strong>{{ selectedNode.operation }} · {{ display(selectedNode.object) }}</strong>
        <p>条件：{{ display(selectedNode.condition) }}</p>
        <dl><template v-for="(value, key) in selectedNode.details" :key="key"><dt>{{ key }}</dt><dd>{{ display(value) }}</dd></template></dl>
      </div>
    </div>
  </section>
</template>
<script setup lang="ts">
import { computed, toRefs } from "vue";
import { planViewState } from "../planViewState";
import type { ExecutionPlan, ExecutionPlanNode } from "../types";
const props = defineProps<{ plan: ExecutionPlan }>();
const { mode, collapsed, selected } = toRefs(planViewState(props.plan));
const selectedNode = computed(() => props.plan.nodes.find(node => node.id === selected.value));
const children = computed(() => new Set(props.plan.nodes.map(node => node.parentId).filter(id => id !== null)));
const visible = computed(() => {
  const byId = new Map(props.plan.nodes.map(node => [node.id, node]));
  return props.plan.nodes.flatMap(node => {
    let depth = 0, parent = node.parentId;
    const seen = new Set([node.id]);
    while (parent !== null && byId.has(parent) && !seen.has(parent)) {
      if (collapsed.value.has(parent)) return [];
      seen.add(parent); depth++; parent = byId.get(parent)!.parentId;
    }
    return [{ node, depth }];
  });
});
function toggle(id: string): void {
  const next = new Set(collapsed.value);
  if (next.has(id)) next.delete(id); else next.add(id);
  collapsed.value = next;
}
function display(value: string | null | undefined): string { return value == null || value === "" ? "—" : value; }
function marker(node: ExecutionPlanNode): string {
  const operation = node.operation.toUpperCase();
  if (node.access === "ALL" || /TABLE.*FULL/.test(`${operation} ${node.access || ''}`)) return "全表扫描";
  if (node.access === "index" || /INDEX.*FULL/.test(`${operation} ${node.access || ''}`)) return "全索引扫描";
  if (/NESTED[ _]LOOP/.test(operation)) return "嵌套循环";
  return "";
}
</script>
<style scoped>
.plan-view { display:flex; flex-direction:column; flex:1; min-height:0; overflow:hidden; color:var(--db-text); }
.plan-text { flex:1; overflow:auto; margin:0; padding:12px; font:12px/1.6 monospace; white-space:pre; user-select:text; -webkit-user-select:text; cursor:text; }
.plan-tree { display:flex; flex-direction:column; flex:1; min-height:0; }
.plan-table-scroll { flex:1; min-height:80px; overflow:auto; }
table { border-collapse:collapse; min-width:100%; font-size:12px; white-space:nowrap; }
th,td { padding:7px 12px; text-align:left; border-bottom:1px solid var(--db-border-soft); }
th { position:sticky; top:0; background:var(--db-panel-soft); }
tr.selected { background:var(--db-accent-soft); }
tr:focus-visible { outline:2px solid var(--db-accent); outline-offset:-2px; }
.plan-expand { display:inline-block; width:22px; border:0; background:transparent; color:inherit; cursor:pointer; }
small { margin-left:10px; color:var(--db-muted); }
.plan-details { max-height:35%; overflow:auto; padding:10px 14px; border-top:1px solid var(--db-border-soft); font-size:12px; }
.plan-details p,dd { white-space:pre-wrap; overflow-wrap:anywhere; }
dl { display:grid; grid-template-columns:minmax(100px,auto) 1fr; gap:5px 16px; } dd { margin:0; } dt { color:var(--db-muted); }
</style>
