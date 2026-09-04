<template>
  <el-dialog
    :model-value="modelValue"
    class="release-notes-dialog"
    modal-class="release-notes-overlay"
    width="100vw"
    top="0"
    append-to-body
    :close-on-click-modal="false"
    :close-on-press-escape="true"
    :show-close="false"
    :destroy-on-close="true"
    aria-label="版本更新说明"
    @update:model-value="modelValueChanged"
  >
    <section class="release-notes-surface" aria-label="版本更新详情">
      <div
        ref="scrollViewport"
        class="release-notes-scroll"
        role="region"
        aria-label="滚动查看更新项"
        tabindex="0"
        @scroll.passive="handleNativeScroll"
        @wheel.passive="handleWheel"
        @keydown="handleKeydown"
        @touchstart.passive="handleTouchStart"
        @touchmove.passive="handleTouchMove"
      >
        <div ref="stackContent" class="release-notes-stack-inner">
          <article
            v-for="(card, index) in displayCards"
            :key="card.id"
            :ref="(element) => setCardRef(index, element)"
            class="release-notes-card"
            :class="[`release-notes-card--${card.tone}`, `release-notes-card--${card.kind}`]"
          >
            <div class="release-notes-card-glow" aria-hidden="true" />
            <div class="release-notes-card-index" aria-hidden="true">{{ String(index + 1).padStart(2, "0") }}</div>
            <div class="release-notes-card-copy">
              <p class="release-notes-card-category">{{ card.category }}</p>
              <h2 v-if="card.kind === 'intro'">{{ card.title }}</h2>
              <h3 v-else>{{ card.title }}</h3>
              <p>{{ card.description }}</p>
              <p v-if="card.kind === 'finish'" class="release-notes-card-finish-hint">继续向下滚动</p>
            </div>
            <span class="release-notes-card-icon" aria-hidden="true">
              <component :is="iconComponents[card.icon]" />
            </span>
          </article>
          <div ref="endMarker" class="release-notes-stack-end" aria-hidden="true" />
        </div>
      </div>
    </section>
  </el-dialog>
</template>

<script setup lang="ts">
import Lenis, { type VirtualScrollData } from "lenis";
import {
  ArrowDown,
  CircleCheckFilled,
  CopyDocument,
  DataAnalysis,
  DocumentChecked,
  MagicStick,
  Timer
} from "@element-plus/icons-vue";
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type Component, type ComponentPublicInstance } from "vue";
import { compareReleaseVersions, type ReleaseNoteIcon, type ReleaseNoteItem, type ReleaseNoteTone, type ReleaseNotesRelease } from "../releaseNotes";

type DisplayCardIcon = ReleaseNoteIcon | "intro" | "finish";
type DisplayCardKind = "intro" | "release" | "finish";

interface DisplayCard {
  id: string;
  category: string;
  title: string;
  description: string;
  tone: ReleaseNoteTone;
  icon: DisplayCardIcon;
  kind: DisplayCardKind;
}

interface CardTransform {
  translateY: number;
  scale: number;
}

const props = defineProps<{ modelValue: boolean; releases: ReleaseNotesRelease[] }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; dismiss: [] }>();

const displayCards = computed<DisplayCard[]>(() => {
  const releases = [...props.releases].sort((left, right) => compareReleaseVersions(left.version, right.version));
  const firstRelease = releases[0];
  const latestRelease = releases.at(-1);
  if (!firstRelease || !latestRelease) return [];
  const releaseCards = releases.flatMap((release) => release.items.map((item: ReleaseNoteItem): DisplayCard => ({
    ...item,
    id: `${release.version}-${item.id}`,
    category: `v${release.version} · ${item.category}`,
    kind: "release"
  })));
  const versionLabel = releases.length > 1
    ? `DBSTUDIO · v${firstRelease.version} → v${latestRelease.version}`
    : `DBSTUDIO · v${firstRelease.version}`;
  const introDescription = releases.length > 1
    ? `${firstRelease.publishedAt} 至 ${latestRelease.publishedAt} · 共 ${releases.length} 个版本、${releaseCards.length} 项更新。向下滚动查看全部变化。`
    : `${firstRelease.publishedAt} · ${firstRelease.summary}`;
  return [
    {
      id: `${firstRelease.version}-${latestRelease.version}-intro`,
      category: versionLabel,
      title: "更新日志",
      description: introDescription,
      tone: "blue",
      icon: "intro",
      kind: "intro"
    },
    ...releaseCards,
    {
      id: `${firstRelease.version}-${latestRelease.version}-finish`,
      category: "准备好了",
      title: "继续下滑开始使用",
      description: "再向下滚动一点，就可以回到工作区。",
      tone: "emerald",
      icon: "finish",
      kind: "finish"
    }
  ];
});

const iconComponents: Record<DisplayCardIcon, Component> = {
  intro: DocumentChecked,
  continue: CircleCheckFilled,
  structure: DataAnalysis,
  tooltip: MagicStick,
  copy: CopyDocument,
  editor: DocumentChecked,
  timeline: Timer,
  finish: ArrowDown
};

const ITEM_DISTANCE = 100;
const ITEM_SCALE = 0.03;
const ITEM_STACK_DISTANCE = 30;
const STACK_POSITION = "20%";
const SCALE_END_POSITION = "10%";
const BASE_SCALE = 0.85;
const LENIS_DURATION = 1.2;
const COMPLETION_INTENT_DISTANCE = 96;

const scrollViewport = ref<HTMLElement>();
const stackContent = ref<HTMLElement>();
const endMarker = ref<HTMLElement>();
const cardRefs = ref<Array<HTMLElement | undefined>>([]);
const reducedMotion = ref(false);

let reducedMotionQuery: MediaQueryList | undefined;
let resizeObserver: ResizeObserver | undefined;
let lenis: Lenis | undefined;
let removeLenisVirtualScroll: (() => void) | undefined;
let animationFrame: number | undefined;
let transformFrame: number | undefined;
let isUpdating = false;
let isDismissing = false;
let lastScrollTop = 0;
let lastCardReady = false;
let completionIntentDistance = 0;
let touchY: number | undefined;
const cardOffsets: number[] = [];
const lastTransforms = new Map<number, CardTransform>();

function parsePercentage(value: string | number, containerHeight: number): number {
  if (typeof value === "string" && value.includes("%")) return (parseFloat(value) / 100) * containerHeight;
  return Number(value);
}

function calculateProgress(scrollTop: number, start: number, end: number): number {
  if (scrollTop < start) return 0;
  if (scrollTop > end) return 1;
  return end <= start ? 1 : (scrollTop - start) / (end - start);
}

function setCardRef(index: number, element: Element | ComponentPublicInstance | null): void {
  if (element instanceof HTMLElement) cardRefs.value[index] = element;
  else if (element === null) cardRefs.value[index] = undefined;
}

function measureLayout(): void {
  cardOffsets.length = 0;
  cardRefs.value.forEach((card) => cardOffsets.push(card?.offsetTop ?? 0));
}

function currentScrollTop(): number {
  return lenis?.scroll ?? scrollViewport.value?.scrollTop ?? 0;
}

function clearCardTransforms(): void {
  cardRefs.value.forEach((card) => {
    if (!card) return;
    card.style.removeProperty("transform");
    card.style.removeProperty("-webkit-transform");
    card.style.removeProperty("filter");
    card.style.removeProperty("will-change");
  });
  lastTransforms.clear();
}

function updateCardTransforms(): void {
  const viewport = scrollViewport.value;
  if (!viewport || reducedMotion.value || cardRefs.value.length === 0 || isUpdating) return;

  isUpdating = true;
  const scrollTop = currentScrollTop();
  const containerHeight = viewport.clientHeight || 1;
  const stackPositionPx = parsePercentage(STACK_POSITION, containerHeight);
  const scaleEndPositionPx = parsePercentage(SCALE_END_POSITION, containerHeight);
  const endElementTop = endMarker.value?.offsetTop ?? viewport.scrollHeight;
  const pinEnd = endElementTop - containerHeight / 2;
  let finalCardReady = false;

  cardRefs.value.forEach((card, index) => {
    if (!card) return;
    const cardTop = cardOffsets[index] ?? card.offsetTop;
    const triggerStart = cardTop - stackPositionPx - ITEM_STACK_DISTANCE * index;
    const triggerEnd = cardTop - scaleEndPositionPx;
    const scaleProgress = calculateProgress(scrollTop, triggerStart, triggerEnd);
    const targetScale = BASE_SCALE + index * ITEM_SCALE;
    const translateY = scrollTop >= triggerStart
      ? Math.min(scrollTop, pinEnd) - cardTop + stackPositionPx + ITEM_STACK_DISTANCE * index
      : 0;
    const nextTransform: CardTransform = {
      translateY: Math.round(translateY * 100) / 100,
      scale: Math.round((1 - scaleProgress * (1 - targetScale)) * 1000) / 1000
    };
    const previous = lastTransforms.get(index);
    const changed = !previous
      || Math.abs(previous.translateY - nextTransform.translateY) > 0.1
      || Math.abs(previous.scale - nextTransform.scale) > 0.001;
    if (changed) {
      card.style.transform = `translate3d(0, ${nextTransform.translateY}px, 0) scale(${nextTransform.scale})`;
      lastTransforms.set(index, nextTransform);
    }
    if (index === cardRefs.value.length - 1) finalCardReady = scrollTop >= triggerEnd;
  });

  if (scrollTop < lastScrollTop - 0.5) completionIntentDistance = 0;
  lastScrollTop = scrollTop;
  lastCardReady = finalCardReady;
  isUpdating = false;
}

function queueTransformUpdate(): void {
  if (transformFrame !== undefined) return;
  if (typeof window.requestAnimationFrame !== "function") {
    updateCardTransforms();
    return;
  }
  transformFrame = window.requestAnimationFrame(() => {
    transformFrame = undefined;
    updateCardTransforms();
  });
}

function resetCompletionIntent(): void {
  completionIntentDistance = 0;
}

function isViewportAtEnd(): boolean {
  const viewport = scrollViewport.value;
  if (!viewport || viewport.scrollHeight <= viewport.clientHeight) return false;
  return viewport.scrollTop + viewport.clientHeight >= viewport.scrollHeight - 1;
}

function recordCompletionIntent(deltaY: number): void {
  if (deltaY <= 0) {
    resetCompletionIntent();
    return;
  }
  if (!lastCardReady && !isViewportAtEnd()) return;
  lastCardReady = true;
  completionIntentDistance += deltaY;
  if (completionIntentDistance >= COMPLETION_INTENT_DISTANCE) dismiss();
}

function handleLenisVirtualScroll(data: VirtualScrollData): void {
  recordCompletionIntent(data.deltaY);
}

function handleNativeScroll(): void {
  if (!lenis) queueTransformUpdate();
}

function handleWheel(event: WheelEvent): void {
  if (!lenis) recordCompletionIntent(event.deltaY);
}

function handleTouchStart(event: TouchEvent): void {
  if (lenis) return;
  touchY = event.touches[0]?.clientY;
}

function handleTouchMove(event: TouchEvent): void {
  if (lenis) return;
  const nextY = event.touches[0]?.clientY;
  if (touchY === undefined || nextY === undefined) return;
  recordCompletionIntent(touchY - nextY);
  touchY = nextY;
}

function handleKeydown(event: KeyboardEvent): void {
  if (event.key === "Escape") {
    event.preventDefault();
    dismiss();
    return;
  }
  if (["ArrowUp", "PageUp", "Home"].includes(event.key)) {
    resetCompletionIntent();
    return;
  }
  if (["ArrowDown", "PageDown", "End", " "].includes(event.key)) {
    recordCompletionIntent(event.key === "PageDown" || event.key === "End" ? 96 : 32);
  }
}

function startAnimationLoop(): void {
  if (reducedMotion.value || !lenis || typeof window.requestAnimationFrame !== "function") return;
  const tick = (time: number) => {
    if (!lenis) return;
    lenis.raf(time);
    updateCardTransforms();
    animationFrame = window.requestAnimationFrame(tick);
  };
  animationFrame = window.requestAnimationFrame(tick);
}

function setupStack(): void {
  const viewport = scrollViewport.value;
  const content = stackContent.value;
  if (!viewport || !content) return;
  teardownStack();
  measureLayout();
  cardRefs.value.forEach((card, index) => {
    if (!card) return;
    card.style.marginBottom = index < cardRefs.value.length - 1 ? `${ITEM_DISTANCE}px` : "0";
    card.style.transformOrigin = "top center";
    card.style.backfaceVisibility = "hidden";
    card.style.perspective = "1000px";
    card.style.webkitPerspective = "1000px";
    if (!reducedMotion.value) {
      card.style.willChange = "transform";
      card.style.transform = "translateZ(0)";
    }
  });

  resizeObserver = typeof ResizeObserver === "undefined" ? undefined : new ResizeObserver(() => {
    measureLayout();
    queueTransformUpdate();
  });
  resizeObserver?.observe(viewport);
  resizeObserver?.observe(content);
  cardRefs.value.forEach((card) => card && resizeObserver?.observe(card));

  if (reducedMotion.value) {
    clearCardTransforms();
    viewport.addEventListener("scroll", handleNativeScroll, { passive: true });
    return;
  }

  lenis = new Lenis({
    wrapper: viewport,
    content,
    duration: LENIS_DURATION,
    easing: (time) => Math.min(1, 1.001 - 2 ** (-10 * time)),
    smoothWheel: true,
    syncTouch: true,
    syncTouchLerp: 0.075,
    touchMultiplier: 2,
    wheelMultiplier: 1,
    gestureOrientation: "vertical",
    infinite: false,
    autoRaf: false
  });
  removeLenisVirtualScroll = lenis.on("virtual-scroll", handleLenisVirtualScroll);
  startAnimationLoop();
  updateCardTransforms();
}

function teardownStack(): void {
  scrollViewport.value?.removeEventListener("scroll", handleNativeScroll);
  removeLenisVirtualScroll?.();
  removeLenisVirtualScroll = undefined;
  if (animationFrame !== undefined) {
    window.cancelAnimationFrame(animationFrame);
    animationFrame = undefined;
  }
  if (transformFrame !== undefined) {
    window.cancelAnimationFrame(transformFrame);
    transformFrame = undefined;
  }
  lenis?.destroy();
  lenis = undefined;
  resizeObserver?.disconnect();
  resizeObserver = undefined;
  clearCardTransforms();
  cardOffsets.length = 0;
  lastScrollTop = 0;
  lastCardReady = false;
  resetCompletionIntent();
  touchY = undefined;
}

function modelValueChanged(value: boolean): void {
  if (value) {
    emit("update:modelValue", true);
    return;
  }
  dismiss();
}

function dismiss(): void {
  if (isDismissing) return;
  isDismissing = true;
  emit("update:modelValue", false);
  emit("dismiss");
}

function syncReducedMotion(): void {
  reducedMotion.value = reducedMotionQuery?.matches === true;
  if (props.modelValue) void nextTick().then(setupStack);
}

watch(() => props.modelValue, (visible) => {
  if (visible) {
    isDismissing = false;
    void nextTick().then(setupStack);
  } else {
    teardownStack();
  }
});

watch(() => props.releases, () => {
  if (props.modelValue) void nextTick().then(setupStack);
}, { deep: true });

onMounted(() => {
  reducedMotionQuery = typeof window.matchMedia === "function"
    ? window.matchMedia("(prefers-reduced-motion: reduce)")
    : undefined;
  reducedMotion.value = reducedMotionQuery?.matches === true;
  reducedMotionQuery?.addEventListener?.("change", syncReducedMotion);
  if (props.modelValue) void nextTick().then(setupStack);
});

onBeforeUnmount(() => {
  teardownStack();
  reducedMotionQuery?.removeEventListener?.("change", syncReducedMotion);
});
</script>

<style scoped>
:global(.el-overlay.release-notes-overlay) {
  --release-notes-glass-tint: rgba(235, 240, 247, 0.10);
  --release-notes-glass-highlight: rgba(255, 255, 255, 0.06);
  background-color: var(--release-notes-glass-tint);
  background-image: linear-gradient(135deg, var(--release-notes-glass-highlight), rgba(255, 255, 255, 0.015) 46%, transparent 72%);
  backdrop-filter: blur(12px) saturate(118%);
  -webkit-backdrop-filter: blur(12px) saturate(118%);
  transition: opacity var(--duration-fast) var(--ease-smooth-out);
}

:global(html[data-theme="dark"] .el-overlay.release-notes-overlay) {
  --release-notes-glass-tint: rgba(4, 7, 12, 0.20);
  --release-notes-glass-highlight: rgba(255, 255, 255, 0.035);
  background-image: linear-gradient(135deg, var(--release-notes-glass-highlight), rgba(255, 255, 255, 0.01) 46%, transparent 72%);
  backdrop-filter: blur(12px) saturate(112%);
  -webkit-backdrop-filter: blur(12px) saturate(112%);
}

:global(.el-overlay.release-notes-overlay.dialog-fade-leave-active) {
  transition-duration: var(--duration-quick);
}

:global(.release-notes-dialog.el-dialog) {
  display: flex;
  width: 100vw !important;
  height: 100vh;
  max-height: none;
  margin: 0;
  padding: 0;
  overflow: visible;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  transition: transform var(--duration-fast) var(--ease-smooth-out), opacity var(--duration-fast) var(--ease-smooth-out);
}

:global(.release-notes-dialog.dialog-fade-leave-active) {
  transition-duration: var(--duration-quick);
}

:global(.release-notes-dialog .el-dialog__header),
:global(.release-notes-dialog .el-dialog__footer) {
  display: none;
}

:global(.release-notes-dialog .el-dialog__body) {
  display: flex;
  width: 100%;
  height: 100%;
  min-height: 0;
  padding: 0;
  overflow: visible;
}

.release-notes-surface {
  width: min(760px, calc(100vw - 48px));
  height: min(820px, calc(100vh - 48px));
  margin: auto;
  overflow: hidden;
  outline: none;
}

.release-notes-scroll {
  width: 100%;
  height: 100%;
  overflow-x: visible;
  overflow-y: auto;
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
  scrollbar-width: none;
  transform: translateZ(0);
  will-change: scroll-position;
}

.release-notes-scroll::-webkit-scrollbar { display: none; }

.release-notes-stack-inner {
  position: relative;
  min-height: 100%;
  padding: 20vh 0 calc(50vh + 96px);
  transform: translateZ(0);
}

.release-notes-stack-end {
  width: 100%;
  height: 1px;
}

.release-notes-card {
  --release-notes-card-shadow: 0 8px 18px rgba(0, 0, 0, 0.10), 0 1px 2px rgba(0, 0, 0, 0.08);
  --release-notes-card-edge: rgba(255, 255, 255, 0.06);
  position: relative;
  display: flex;
  min-height: 300px;
  align-items: center;
  gap: 24px;
  box-sizing: border-box;
  overflow: hidden;
  padding: 46px 48px;
  border: 1px solid color-mix(in srgb, var(--card-accent) 32%, transparent);
  border-radius: 40px;
  background: var(--card-bg);
  box-shadow: var(--release-notes-card-shadow), inset 0 1px 0 var(--release-notes-card-edge);
  transform-origin: top center;
  backface-visibility: hidden;
  transform: translateZ(0);
}

:global(html[data-theme="dark"] .release-notes-card) {
  --release-notes-card-shadow: 0 8px 18px rgba(0, 0, 0, 0.18), 0 1px 2px rgba(0, 0, 0, 0.12);
  --release-notes-card-edge: rgba(255, 255, 255, 0.045);
}

.release-notes-card-glow {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: radial-gradient(circle at 86% 18%, color-mix(in srgb, var(--card-accent) 25%, transparent), transparent 44%);
  opacity: 0.88;
}

.release-notes-card-index,
.release-notes-card-copy,
.release-notes-card-icon {
  position: relative;
  z-index: 1;
}

.release-notes-card-index {
  align-self: flex-start;
  min-width: 34px;
  color: var(--card-accent);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  font-weight: 750;
  letter-spacing: 0.08em;
}

.release-notes-card-copy { min-width: 0; }

.release-notes-card-category {
  margin: 0 0 14px;
  color: var(--card-accent);
  font-size: 12px;
  font-weight: 720;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.release-notes-card h2,
.release-notes-card h3 {
  margin: 0 0 13px;
  color: var(--card-text);
  font-size: clamp(24px, 3.2vw, 34px);
  font-weight: 720;
  letter-spacing: -0.035em;
  line-height: 1.2;
}

.release-notes-card-copy > p:not(.release-notes-card-category):not(.release-notes-card-finish-hint) {
  max-width: 560px;
  margin: 0;
  color: var(--card-muted);
  font-size: 14px;
  line-height: 1.75;
}

.release-notes-card-finish-hint {
  margin: 21px 0 0;
  color: var(--card-accent);
  font-size: 12px;
  font-weight: 680;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.release-notes-card-icon {
  display: grid;
  width: 82px;
  height: 82px;
  flex: none;
  margin-left: auto;
  place-items: center;
  border: 1px solid color-mix(in srgb, var(--card-accent) 38%, transparent);
  border-radius: 24px;
  color: var(--card-accent);
  background: color-mix(in srgb, var(--card-accent) 13%, transparent);
}

.release-notes-card-icon svg { width: 36px; height: 36px; }

.release-notes-card--intro { min-height: 340px; }
.release-notes-card--finish { min-height: 300px; }
.release-notes-card--emerald { --card-accent: #43ba87; --card-bg: color-mix(in srgb, #1c4d3a 90%, var(--db-content)); --card-text: #f1fff7; --card-muted: #c2e6d3; }
.release-notes-card--blue { --card-accent: #67aaf5; --card-bg: color-mix(in srgb, #1d3e68 90%, var(--db-content)); --card-text: #f2f7ff; --card-muted: #c3d6ee; }
.release-notes-card--amber { --card-accent: #f4bb61; --card-bg: color-mix(in srgb, #5b401d 90%, var(--db-content)); --card-text: #fff9ed; --card-muted: #ebd8b1; }
.release-notes-card--purple { --card-accent: #ba9cff; --card-bg: color-mix(in srgb, #40316b 90%, var(--db-content)); --card-text: #faf7ff; --card-muted: #d9ccef; }
.release-notes-card--rose { --card-accent: #f08ca8; --card-bg: color-mix(in srgb, #5f2f46 90%, var(--db-content)); --card-text: #fff5f8; --card-muted: #edc5d2; }
.release-notes-card--cyan { --card-accent: #58d4dc; --card-bg: color-mix(in srgb, #1c525d 90%, var(--db-content)); --card-text: #efffff; --card-muted: #bee5e7; }

@media (max-width: 640px) {
  .release-notes-surface { width: calc(100vw - 24px); height: calc(100vh - 24px); }
  .release-notes-stack-inner { padding-top: 14vh; }
  .release-notes-card { min-height: 360px; gap: 16px; padding: 32px 25px; border-radius: 30px; }
  .release-notes-card--intro { min-height: 380px; }
  .release-notes-card-icon { position: absolute; right: 25px; bottom: 25px; width: 60px; height: 60px; border-radius: 18px; }
  .release-notes-card-icon svg { width: 27px; height: 27px; }
  .release-notes-card-copy { padding-right: 4px; }
  .release-notes-card-copy > p:not(.release-notes-card-category):not(.release-notes-card-finish-hint) { padding-bottom: 58px; }
}

@media (prefers-reduced-motion: reduce) {
  :global(.el-overlay.release-notes-overlay),
  :global(.release-notes-dialog.el-dialog) { transition: none; }
  .release-notes-scroll { scroll-behavior: auto; transform: none; will-change: auto; }
  .release-notes-stack-inner { transform: none; }
  .release-notes-card { transform: none; will-change: auto; }
}
</style>
