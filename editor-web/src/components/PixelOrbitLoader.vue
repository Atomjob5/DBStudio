<template>
  <span class="pixel-orbit-loader" :class="{ compact }"
        :style="{ '--pixel-orbit-color': color || 'var(--db-result-selection-border)' }"
        aria-hidden="true">
    <i v-for="cell in CELLS" :key="cell.index" class="pixel-orbit-loader__cell"
       :class="{ 'is-center': cell.step < 0 }"
       :style="{ '--pixel-orbit-step': cell.step }" />
  </span>
</template>

<script setup lang="ts">
/*
 * Eight perimeter cells take turns fading in and scaling up around a 3x3
 * grid. The centre remains empty so the indicator reads as a small orbit.
 */
withDefaults(defineProps<{ color?: string; compact?: boolean }>(), { compact: false });

const PERIMETER_STEPS = [0, 1, 2, 7, -1, 3, 6, 5, 4];
const CELLS = PERIMETER_STEPS.map((step, index) => ({ index, step }));
</script>

<style scoped>
.pixel-orbit-loader {
  display: grid;
  width: 48px;
  height: 48px;
  grid-template-columns: repeat(3, 1fr);
  grid-template-rows: repeat(3, 1fr);
  gap: 4px;
  flex: none;
}
.pixel-orbit-loader__cell {
  display: block;
  min-width: 0;
  min-height: 0;
  border-radius: 3px;
  background: var(--pixel-orbit-color);
  opacity: .16;
  transform: scale(.62);
  animation: pixel-orbit-pulse 1400ms var(--ease-in-out, ease-in-out) infinite;
  animation-delay: calc(var(--pixel-orbit-step, 0) * 120ms);
  box-shadow: none;
  will-change: opacity, transform, box-shadow;
}
.pixel-orbit-loader__cell.is-center {
  visibility: hidden;
  animation: none;
}
@keyframes pixel-orbit-pulse {
  0%, 100% {
    opacity: .16;
    transform: scale(.62);
    box-shadow: none;
  }
  12% {
    opacity: 1;
    transform: scale(1);
    box-shadow: 0 0 10px color-mix(in srgb, var(--pixel-orbit-color) 55%, transparent);
  }
  26% {
    opacity: .22;
    transform: scale(.76);
    box-shadow: 0 0 2px color-mix(in srgb, var(--pixel-orbit-color) 35%, transparent);
  }
}
.pixel-orbit-loader.compact {
  width: 28px;
  height: 28px;
  gap: 2px;
}
.pixel-orbit-loader.compact .pixel-orbit-loader__cell { border-radius: 2px; }

@media (prefers-reduced-motion: reduce) {
  .pixel-orbit-loader__cell {
    animation: none !important;
    opacity: .58;
    transform: scale(.78);
    box-shadow: none;
  }
}
</style>
