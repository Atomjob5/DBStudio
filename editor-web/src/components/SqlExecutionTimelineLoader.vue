<template>
  <div class="sql-execution-timeline" :class="{ compact }">
    <div v-for="(step, index) in steps" :key="step.id" class="sql-execution-timeline__step"
         :class="{ 'is-active': step.active, 'is-complete': step.complete }">
      <span v-if="index < steps.length - 1" class="sql-execution-timeline__connector" aria-hidden="true" />
      <span class="sql-execution-timeline__marker">
        <span v-if="step.complete" class="sql-execution-timeline__check" aria-hidden="true">✓</span>
        <PixelOrbitLoader v-else compact :color="color" />
      </span>
      <span class="sql-execution-timeline__label">
        <template v-if="step.id !== 'preparing-result' || !step.active">{{ step.label }}</template>
        <template v-else>
          <span class="sql-execution-timeline__sr-label">Preparing Result</span>
          <SqlExecutionTimer :started-at="startedAt" label="Preparing Result" inline />
        </template>
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type { ExecutionTimelineStage } from "../types";
import PixelOrbitLoader from "./PixelOrbitLoader.vue";
import SqlExecutionTimer from "./SqlExecutionTimer.vue";

const props = withDefaults(defineProps<{
  stage: ExecutionTimelineStage;
  startedAt?: number;
  color?: string;
  compact?: boolean;
}>(), { compact: false });

interface TimelineStep {
  id: "thinking" | "planning" | "preparing-result" | "success";
  label: string;
  active: boolean;
  complete: boolean;
}

const steps = computed<TimelineStep[]>(() => {
  const all: TimelineStep[] = [
    { id: "thinking", label: "Thinking", active: false, complete: false },
    { id: "planning", label: "Planning", active: false, complete: false },
    { id: "preparing-result", label: "Preparing Result", active: false, complete: false },
    { id: "success", label: "Success", active: false, complete: false },
  ];
  const current = all.findIndex((step) => step.id === props.stage);
  if (current < 0) return all.slice(0, 1);
  return all.slice(0, current + 1).map((step, index) => ({
    ...step,
    active: index === current,
    complete: props.stage === "success" || index < current,
  }));
});
</script>

<style scoped>
.sql-execution-timeline {
  display: flex;
  min-width: 214px;
  flex-direction: column;
  align-items: stretch;
  gap: 6px;
}
.sql-execution-timeline__step {
  position: relative;
  display: flex;
  min-height: 34px;
  align-items: center;
  gap: 10px;
  animation: sql-execution-timeline-step-in var(--duration-fast, 250ms) var(--ease-smooth-out, ease-out) both;
}
.sql-execution-timeline__marker {
  position: relative;
  z-index: 1;
  display: grid;
  width: 28px;
  height: 28px;
  flex: none;
  place-items: center;
  border-radius: 50%;
}
.sql-execution-timeline__check {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border: 2px solid var(--el-color-success, #21a179);
  border-radius: 50%;
  color: var(--el-color-success, #21a179);
  font-size: 16px;
  font-weight: 700;
  line-height: 1;
  animation: sql-execution-timeline-check-in var(--duration-fast, 250ms) var(--ease-smooth-out, ease-out) both;
}
.sql-execution-timeline__label {
  color: var(--db-text);
  font-size: 15px;
  line-height: 1.2;
  white-space: nowrap;
}
.sql-execution-timeline__sr-label {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
.sql-execution-timeline__step:not(.is-active) .sql-execution-timeline__label {
  color: var(--db-text-secondary);
}
.sql-execution-timeline__step.is-active .sql-execution-timeline__label {
  color: var(--db-text);
  font-weight: 600;
}
.sql-execution-timeline__connector {
  position: absolute;
  top: 28px;
  bottom: -6px;
  left: 13px;
  width: 2px;
  background: repeating-linear-gradient(to bottom, var(--db-border-soft) 0 3px, transparent 3px 6px);
}
@keyframes sql-execution-timeline-step-in {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}
@keyframes sql-execution-timeline-check-in {
  from { opacity: 0; transform: scale(.72); }
  to { opacity: 1; transform: scale(1); }
}
.sql-execution-timeline.compact {
  width: 104px;
  min-width: 104px;
  gap: 2px;
}
.sql-execution-timeline.compact .sql-execution-timeline__step {
  min-height: 17px;
  gap: 7px;
}
.sql-execution-timeline.compact .sql-execution-timeline__marker {
  width: 16px;
  height: 16px;
}
.sql-execution-timeline.compact .sql-execution-timeline__check {
  width: 14px;
  height: 14px;
  border-width: 1px;
  font-size: 9px;
}
.sql-execution-timeline.compact .sql-execution-timeline__label {
  overflow: hidden;
  font-size: 10px;
  text-overflow: ellipsis;
}
.sql-execution-timeline.compact .sql-execution-timeline__connector {
  top: 15px;
  bottom: -4px;
  left: 7px;
  width: 1px;
  background-size: 1px 4px;
}
.sql-execution-timeline.compact :deep(.pixel-orbit-loader) {
  width: 16px;
  height: 16px;
  gap: 1px;
}
.sql-execution-timeline.compact :deep(.pixel-orbit-loader__cell) { border-radius: 1px; }

@media (prefers-reduced-motion: reduce) {
  .sql-execution-timeline__step { animation: none; }
  .sql-execution-timeline__check { animation: none; }
}
</style>
