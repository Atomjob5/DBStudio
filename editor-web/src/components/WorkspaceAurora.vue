<template>
  <canvas ref="canvas" class="workspace-aurora" :class="{ 'is-fallback': fallback }" aria-hidden="true" />
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";

type Rgb = [number, number, number];

const canvas = ref<HTMLCanvasElement>();
const fallback = ref(false);

let context: CanvasRenderingContext2D | null = null;
let resizeObserver: ResizeObserver | undefined;
let themeObserver: MutationObserver | undefined;
let animationFrame: number | undefined;
let reducedMotionQuery: MediaQueryList | undefined;
let reducedMotionListener: ((event: MediaQueryListEvent) => void) | undefined;
let visibilityListener: (() => void) | undefined;
let resizeListener: (() => void) | undefined;
let width = 0;
let height = 0;
let pixelRatio = 1;
let lastFrame = 0;
let isVisible = true;
let reducedMotion = false;
let palette = {
  base: "#f5f5f7",
  blue: "#0071e3",
  cyan: "#64d2ff",
  violet: "#af52de",
  indigo: "#5e5ce6"
};

function readCssVariable(name: string, fallbackValue: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallbackValue;
}

function parseRgb(value: string): Rgb | undefined {
  const hex = value.match(/^#([\da-f]{3}|[\da-f]{6})$/i)?.[1];
  if (hex) {
    const normalized = hex.length === 3 ? hex.split("").map((part) => `${part}${part}`).join("") : hex;
    return [
      Number.parseInt(normalized.slice(0, 2), 16),
      Number.parseInt(normalized.slice(2, 4), 16),
      Number.parseInt(normalized.slice(4, 6), 16)
    ];
  }
  const rgb = value.match(/^rgba?\(([^)]+)\)$/i)?.[1];
  if (!rgb) return undefined;
  const channels = rgb.split(/[\s,/]+/).slice(0, 3).map(Number);
  return channels.length === 3 && channels.every(Number.isFinite) ? channels as Rgb : undefined;
}

function withAlpha(color: string, alpha: number): string {
  const rgb = parseRgb(color);
  return rgb ? `rgba(${rgb[0]}, ${rgb[1]}, ${rgb[2]}, ${alpha})` : color;
}

function readPalette(): void {
  palette = {
    base: readCssVariable("--db-bg", "#f5f5f7"),
    blue: readCssVariable("--db-accent", "#0071e3"),
    cyan: readCssVariable("--db-spectrum-inner-cyan", "#64d2ff"),
    violet: readCssVariable("--db-result-null-color", "#af52de"),
    indigo: readCssVariable("--db-spectrum-inner-blue", "#5e5ce6")
  };
}

function resizeCanvas(): void {
  const element = canvas.value;
  if (!element || !context) return;
  const bounds = element.getBoundingClientRect();
  width = Math.max(1, bounds.width);
  height = Math.max(1, bounds.height);
  pixelRatio = Math.min(window.devicePixelRatio || 1, 1.5);
  const pixelWidth = Math.max(1, Math.round(width * pixelRatio));
  const pixelHeight = Math.max(1, Math.round(height * pixelRatio));
  if (element.width !== pixelWidth || element.height !== pixelHeight) {
    element.width = pixelWidth;
    element.height = pixelHeight;
  }
  context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
  renderFrame(reducedMotion ? 0 : lastFrame / 1000);
}

function drawRibbon(color: string, index: number, time: number, opacity: number): void {
  if (!context) return;
  const baseY = height * (0.08 + index * 0.07);
  const amplitude = height * (0.055 + index * 0.012);
  const thickness = height * (0.17 + index * 0.025);
  const phase = index * 1.8;
  const frequency = (Math.PI * 2) / Math.max(width * (0.72 + index * 0.1), 1);
  const path = new Path2D();
  const segments = 48;
  const step = width / segments;
  const wave = (x: number, offset: number): number => baseY + offset
    + Math.sin(x * frequency + time * (0.34 + index * 0.07) + phase) * amplitude
    + Math.sin(x * frequency * 1.9 - time * 0.18 + phase * 0.7) * amplitude * 0.28;

  path.moveTo(-step, wave(-step, -thickness * 0.36));
  for (let point = 0; point <= segments + 1; point += 1) {
    const x = point * step - step;
    path.lineTo(x, wave(x, -thickness * 0.36));
  }
  for (let point = segments + 1; point >= 0; point -= 1) {
    const x = point * step - step;
    path.lineTo(x, wave(x, thickness * 0.64));
  }
  path.closePath();

  const gradient = context.createLinearGradient(0, baseY - amplitude, 0, baseY + thickness + amplitude);
  gradient.addColorStop(0, withAlpha(color, 0));
  gradient.addColorStop(0.14, withAlpha(color, opacity * 0.42));
  gradient.addColorStop(0.5, withAlpha(color, opacity));
  gradient.addColorStop(0.82, withAlpha(color, opacity * 0.22));
  gradient.addColorStop(1, withAlpha(color, 0));
  context.fillStyle = gradient;
  context.filter = `blur(${Math.max(7, Math.min(22, width * 0.012))}px)`;
  context.fill(path);
  context.filter = "none";
}

function drawGlow(color: string, x: number, y: number, radius: number, opacity: number): void {
  if (!context) return;
  const gradient = context.createRadialGradient(x, y, 0, x, y, radius);
  gradient.addColorStop(0, withAlpha(color, opacity));
  gradient.addColorStop(0.36, withAlpha(color, opacity * 0.48));
  gradient.addColorStop(0.72, withAlpha(color, opacity * 0.12));
  gradient.addColorStop(1, withAlpha(color, 0));
  context.fillStyle = gradient;
  context.fillRect(0, 0, width, height);
}

function renderFrame(time: number): void {
  if (!context || !width || !height) return;
  context.save();
  context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
  context.globalCompositeOperation = "source-over";
  context.globalAlpha = 1;
  context.fillStyle = palette.base;
  context.fillRect(0, 0, width, height);

  const intensity = reducedMotion ? 0.82 : 1;
  const radius = Math.max(width, height) * 0.58;
  drawGlow(palette.blue, width * (0.12 + Math.sin(time * 0.12) * 0.045), height * 0.1, radius, 0.2 * intensity);
  drawGlow(palette.cyan, width * (0.48 + Math.sin(time * 0.1 + 1.2) * 0.08), height * 0.08, radius * 0.9, 0.15 * intensity);
  drawGlow(palette.violet, width * (0.84 + Math.sin(time * 0.14 + 2.3) * 0.07), height * 0.12, radius * 0.88, 0.18 * intensity);
  drawGlow(palette.indigo, width * (0.66 + Math.sin(time * 0.08 + 0.4) * 0.08), height * 0.25, radius * 0.75, 0.1 * intensity);

  drawRibbon(palette.blue, 0, time, 0.34 * intensity);
  drawRibbon(palette.cyan, 1, time, 0.27 * intensity);
  drawRibbon(palette.violet, 2, time, 0.31 * intensity);
  drawRibbon(palette.indigo, 3, time, 0.2 * intensity);

  const fade = context.createLinearGradient(0, height * 0.18, 0, height);
  fade.addColorStop(0, withAlpha(palette.base, 0));
  fade.addColorStop(0.42, withAlpha(palette.base, 0.08));
  fade.addColorStop(0.72, withAlpha(palette.base, 0.58));
  fade.addColorStop(1, withAlpha(palette.base, 0.94));
  context.fillStyle = fade;
  context.fillRect(0, 0, width, height);

  const vignette = context.createLinearGradient(0, 0, width, 0);
  vignette.addColorStop(0, withAlpha(palette.base, 0.18));
  vignette.addColorStop(0.18, withAlpha(palette.base, 0));
  vignette.addColorStop(0.82, withAlpha(palette.base, 0));
  vignette.addColorStop(1, withAlpha(palette.base, 0.18));
  context.fillStyle = vignette;
  context.fillRect(0, 0, width, height);
  context.restore();
}

function animate(timestamp: number): void {
  animationFrame = undefined;
  if (!isVisible || reducedMotion || !context) return;
  if (timestamp - lastFrame >= 1000 / 30) {
    lastFrame = timestamp;
    renderFrame(timestamp / 1000);
  }
  animationFrame = requestAnimationFrame(animate);
}

function stopAnimation(): void {
  if (animationFrame !== undefined) cancelAnimationFrame(animationFrame);
  animationFrame = undefined;
}

function startAnimation(): void {
  stopAnimation();
  if (!context || !isVisible) return;
  if (reducedMotion) {
    renderFrame(0);
    return;
  }
  animationFrame = requestAnimationFrame(animate);
}

function handleReducedMotion(event?: MediaQueryListEvent): void {
  reducedMotion = event?.matches ?? reducedMotionQuery?.matches ?? false;
  startAnimation();
}

onMounted(() => {
  const element = canvas.value;
  if (!element) return;
  context = element.getContext("2d");
  if (!context || typeof Path2D === "undefined") {
    fallback.value = true;
    return;
  }

  readPalette();
  reducedMotionQuery = window.matchMedia?.("(prefers-reduced-motion: reduce)");
  reducedMotion = reducedMotionQuery?.matches ?? false;
  if (reducedMotionQuery) {
    reducedMotionListener = (event) => handleReducedMotion(event);
    reducedMotionQuery.addEventListener?.("change", reducedMotionListener);
    if (!reducedMotionQuery.addEventListener) reducedMotionQuery.addListener?.(reducedMotionListener);
  }

  resizeListener = () => resizeCanvas();
  const host = element.parentElement;
  if (typeof ResizeObserver !== "undefined" && host) {
    resizeObserver = new ResizeObserver(resizeListener);
    resizeObserver.observe(host);
  } else {
    window.addEventListener("resize", resizeListener);
  }

  if (typeof MutationObserver !== "undefined") {
    themeObserver = new MutationObserver(() => {
      readPalette();
      renderFrame(reducedMotion ? 0 : lastFrame / 1000);
    });
    themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
  }

  isVisible = !document.hidden;
  visibilityListener = () => {
    isVisible = !document.hidden;
    if (isVisible) startAnimation();
    else stopAnimation();
  };
  document.addEventListener("visibilitychange", visibilityListener);

  resizeCanvas();
  startAnimation();
});

onBeforeUnmount(() => {
  stopAnimation();
  resizeObserver?.disconnect();
  themeObserver?.disconnect();
  if (resizeListener) window.removeEventListener("resize", resizeListener);
  if (visibilityListener) document.removeEventListener("visibilitychange", visibilityListener);
  if (reducedMotionQuery && reducedMotionListener) {
    reducedMotionQuery.removeEventListener?.("change", reducedMotionListener);
    reducedMotionQuery.removeListener?.(reducedMotionListener);
  }
  context = null;
});
</script>

<style scoped>
.workspace-aurora {
  position: absolute;
  z-index: 0;
  inset: 0;
  display: block;
  width: 100%;
  height: 100%;
  pointer-events: none;
  background:
    radial-gradient(ellipse at 16% 12%, color-mix(in srgb, var(--db-accent) 24%, transparent), transparent 42%),
    radial-gradient(ellipse at 84% 14%, color-mix(in srgb, var(--db-result-null-color) 20%, transparent), transparent 44%),
    var(--db-bg);
}

.workspace-aurora.is-fallback {
  opacity: 1;
}
</style>
