<template>
  <span class="sql-execution-timer" :class="`is-${duration.level}`" aria-hidden="true">
    <span>正在执行 SQL(</span>
    <Transition name="clock-flip">
      <span v-if="duration.showMinutes" class="sql-execution-timer__segment">
        <span class="sql-execution-timer__flip sql-execution-timer__flip--minutes">
          <Transition name="clock-flip">
            <span :key="duration.minutes" class="sql-execution-timer__number"
                  :data-value="duration.minutes">{{ duration.minutes }}</span>
          </Transition>
        </span><span>m&nbsp;</span>
      </span>
    </Transition>
    <Transition name="clock-flip">
      <span v-if="duration.showSeconds" class="sql-execution-timer__segment">
        <span class="sql-execution-timer__flip sql-execution-timer__flip--seconds">
          <Transition name="clock-flip">
            <span :key="duration.seconds" class="sql-execution-timer__number"
                  :data-value="duration.seconds">{{ duration.seconds }}</span>
          </Transition>
        </span><span>s&nbsp;</span>
      </span>
    </Transition>
    <span class="sql-execution-timer__milliseconds">{{ duration.milliseconds }}</span><span>ms)</span>
  </span>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { executionDurationParts } from "../executionDuration";

const props = defineProps<{ startedAt?: number }>();
const elapsedMs = ref(0);
let frame: number | undefined;
let fallbackStartedAt = Date.now();

const duration = computed(() => executionDurationParts(elapsedMs.value));

function stopTimer(): void {
  if (frame !== undefined) cancelAnimationFrame(frame);
  frame = undefined;
}

function updateTimer(): void {
  elapsedMs.value = Math.max(0, Date.now() - (props.startedAt ?? fallbackStartedAt));
  frame = requestAnimationFrame(updateTimer);
}

watch(() => props.startedAt, () => {
  stopTimer();
  fallbackStartedAt = Date.now();
  updateTimer();
}, { immediate: true });

onBeforeUnmount(stopTimer);
</script>

<style scoped>
.sql-execution-timer {
  display: inline-flex;
  align-items: baseline;
  color: var(--db-text-secondary);
  font-variant-numeric: tabular-nums;
  transition: color 180ms ease;
  white-space: nowrap;
}
.sql-execution-timer.is-warning { color: var(--db-warning); }
.sql-execution-timer.is-danger { color: var(--db-danger); }
.sql-execution-timer__segment { display: inline-flex; align-items: baseline; }
.sql-execution-timer__flip {
  display: inline-grid;
  overflow: hidden;
  perspective: 80px;
  line-height: 1.2;
  text-align: right;
  vertical-align: baseline;
}
.sql-execution-timer__flip--minutes { min-width: 1ch; }
.sql-execution-timer__flip--seconds { min-width: 2ch; }
.sql-execution-timer__number { grid-area: 1 / 1; transform-origin: center center; }
.sql-execution-timer__milliseconds {
  display: inline-block;
  min-width: 3ch;
  text-align: right;
}
.clock-flip-enter-active,
.clock-flip-leave-active { transition: transform 150ms cubic-bezier(.2, .75, .25, 1), opacity 120ms ease; }
.clock-flip-enter-from { opacity: 0; transform: rotateX(-88deg) translateY(35%); }
.clock-flip-leave-to { opacity: 0; transform: rotateX(88deg) translateY(-35%); }

@media (prefers-reduced-motion: reduce) {
  .sql-execution-timer { transition: none; }
  .clock-flip-enter-active,
  .clock-flip-leave-active { transition: none; }
}
</style>
