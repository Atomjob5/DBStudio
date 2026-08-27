<template>
  <div class="wave-physics-loader" :class="{ compact, dark: theme === 'dark' }" aria-hidden="true">
    <div class="wave-physics-loader__stage">
      <i v-for="index in BAR_COUNT" :key="index" ref="bars" class="wave-physics-loader__bar" />
      <i ref="ball" class="wave-physics-loader__ball" />
    </div>
  </div>
</template>

<script setup lang="ts">
/*
 * Adapted for Vue from Amicro's Wave Physics Loader.
 * https://github.com/Subhan-code/Amicro--Micro-transitions-
 * Copyright (c) 2026 SYED SUBHAN UDDIN, licensed under the MIT License.
 * See editor-web/THIRD_PARTY_NOTICES.md.
 */
import { onBeforeUnmount, onMounted, ref, watch } from "vue";

const props = withDefaults(defineProps<{ theme?: "light" | "dark"; compact?: boolean }>(), {
  theme: "light",
  compact: false,
});

const BAR_COUNT = 15;
const BAR_WIDTH = 12;
const BAR_GAP = 8;
const BASE_HEIGHT = 16;
const WAVE_HEIGHT = 48;
const MAX_BOUNCE = 60;
const BOUNCES = 4;
const DURATION = 4000;
const bars = ref<HTMLElement[]>([]);
const ball = ref<HTMLElement>();
let frame: number | undefined;
let startedAt = 0;
let reducedMotion: MediaQueryList | undefined;

function render(progress: number): void {
  const triangle = progress < .5 ? progress * 2 : (1 - progress) * 2;
  const ballIndex = triangle * (BAR_COUNT - 1);
  let bounceFraction = (triangle * BOUNCES) % 1;
  if (triangle === 0 || triangle === 1) bounceFraction = 0;
  const bounceHeight = 4 * bounceFraction * (1 - bounceFraction);
  const contact = Math.max(0, 1 - bounceHeight * 2);
  const isDark = props.theme === "dark";

  bars.value.forEach((bar, index) => {
    const distance = Math.abs(index - ballIndex);
    const wave = distance < 3 ? Math.cos((distance / 3) * Math.PI / 2) : 0;
    const indent = distance < 1.5 ? Math.cos((distance / 1.5) * Math.PI / 2) * contact * 20 : 0;
    const low = isDark ? [39, 39, 42] : [228, 228, 231];
    const high = isDark ? [228, 228, 231] : [39, 39, 42];
    const color = low.map((channel, colorIndex) => Math.round(channel + wave * (high[colorIndex] - channel)));
    bar.style.height = `${Math.max(4, BASE_HEIGHT + wave * WAVE_HEIGHT - indent)}px`;
    bar.style.backgroundColor = `rgb(${color.join(", ")})`;
  });

  if (ball.value) {
    const y = BASE_HEIGHT + WAVE_HEIGHT - contact * 20 + bounceHeight * MAX_BOUNCE;
    ball.value.style.transform = `translate(${ballIndex * (BAR_WIDTH + BAR_GAP)}px, -${y}px) scale(${1 + contact * .25}, ${1 - contact * .3})`;
  }
}

function tick(timestamp: number): void {
  if (!startedAt) startedAt = timestamp;
  render(((timestamp - startedAt) % DURATION) / DURATION);
  frame = requestAnimationFrame(tick);
}

function syncMotionPreference(): void {
  if (frame !== undefined) cancelAnimationFrame(frame);
  frame = undefined;
  startedAt = 0;
  if (reducedMotion?.matches) render(.25);
  else frame = requestAnimationFrame(tick);
}

watch(() => props.theme, () => render(reducedMotion?.matches ? .25 : 0));
onMounted(() => {
  reducedMotion = window.matchMedia?.("(prefers-reduced-motion: reduce)");
  reducedMotion?.addEventListener?.("change", syncMotionPreference);
  syncMotionPreference();
});
onBeforeUnmount(() => {
  if (frame !== undefined) cancelAnimationFrame(frame);
  reducedMotion?.removeEventListener?.("change", syncMotionPreference);
});
</script>

<style scoped>
.wave-physics-loader { position: relative; width: min(292px, 72%); height: 128px; }
.wave-physics-loader__stage {
  position: absolute; bottom: 0; left: 0; display: flex; width: 292px; height: 128px; align-items: flex-end; gap: 8px;
  transform: scale(var(--wave-loader-scale, 1)); transform-origin: left bottom;
}
.wave-physics-loader__bar { display: block; width: 12px; height: 16px; flex: none; border-radius: 999px; transform-origin: bottom; }
.wave-physics-loader__ball {
  position: absolute; bottom: 0; left: 0; z-index: 1; display: block; width: 12px; height: 12px;
  border-radius: 50%; background: #18181b; box-shadow: 0 1px 2px rgb(0 0 0 / 18%); transform-origin: bottom center;
}
.wave-physics-loader.dark .wave-physics-loader__ball { background: #fff; box-shadow: 0 1px 2px rgb(0 0 0 / 45%); }
.wave-physics-loader.compact { --wave-loader-scale: .42; width: 123px; height: 54px; }
@media (max-width: 640px) { .wave-physics-loader:not(.compact) { --wave-loader-scale: .78; width: 228px; height: 100px; } }
</style>
