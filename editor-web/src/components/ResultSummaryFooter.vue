<template>
  <div class="result-summary-footer" role="status" aria-live="polite">
    <span>合计</span>
    <strong>{{ total }}</strong>
    <small>{{ count }} 个有效值</small>
    <button type="button" aria-label="关闭合计" title="关闭合计" @click="$emit('close')">×</button>
  </div>
</template>

<script setup lang="ts">
defineProps<{ total: string; count: number }>();
defineEmits<{ close: [] }>();
</script>

<style scoped>
.result-summary-footer {
  box-sizing: border-box;
  display: flex;
  width: 100%;
  height: 32px;
  align-items: center;
  gap: 9px;
  padding: 0 10px;
  border-top: 1px solid var(--db-border-soft);
  background: var(--db-panel-soft);
  color: var(--db-text-secondary);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.result-summary-footer strong { color: var(--db-text); font-size: 12px; font-weight: 600; }
.result-summary-footer small { color: var(--db-muted); font-size: 10px; }
.result-summary-footer button {
  display: grid;
  width: 22px;
  height: 22px;
  margin-left: auto;
  padding: 0;
  place-items: center;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--db-muted);
  cursor: pointer;
}
.result-summary-footer button:hover { background: var(--db-control-hover); color: var(--db-text); }
</style>
