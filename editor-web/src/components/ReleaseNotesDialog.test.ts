import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import { mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus from "element-plus";
import ReleaseNotesDialog from "./ReleaseNotesDialog.vue";
import { CURRENT_RELEASE, type ReleaseNotesRelease } from "../releaseNotes";

const MERGED_RELEASES: ReleaseNotesRelease[] = [
  {
    ...CURRENT_RELEASE,
    version: "1.2.0",
    publishedAt: "2026 年 10 月",
    summary: "补充执行和对象浏览体验。",
    items: CURRENT_RELEASE.items.slice(0, 2).map((item) => ({ ...item, id: `1.2.0-${item.id}` }))
  },
  {
    ...CURRENT_RELEASE,
    version: "1.3.0",
    publishedAt: "2026 年 11 月",
    summary: "让编辑和结果反馈更可靠。",
    items: CURRENT_RELEASE.items.slice(2, 4).map((item) => ({ ...item, id: `1.3.0-${item.id}` }))
  }
];

const lenisMock = vi.hoisted(() => ({
  virtualScroll: undefined as ((data: { deltaY: number }) => void) | undefined,
  destroyed: vi.fn()
}));

vi.mock("lenis", () => ({
  default: class FakeLenis {
    private readonly wrapper: HTMLElement;

    constructor(options: { wrapper: HTMLElement }) {
      this.wrapper = options.wrapper;
    }

    get scroll(): number {
      return this.wrapper.scrollTop;
    }

    raf(): void { /* The test drives scrollTop directly. */ }

    on(event: string, callback: (data: { deltaY: number }) => void): () => void {
      if (event === "virtual-scroll") lenisMock.virtualScroll = callback;
      return () => {
        if (event === "virtual-scroll") lenisMock.virtualScroll = undefined;
      };
    }

    destroy(): void {
      lenisMock.destroyed();
    }
  }
}));

type ResizeCallback = (entries: ResizeObserverEntry[], observer: ResizeObserver) => void;

describe("ReleaseNotesDialog", () => {
  let resizeCallback: ResizeCallback | undefined;
  let resizeCallbacks: ResizeCallback[] = [];
  let frameCallback: FrameRequestCallback | undefined;
  let matchReducedMotion = false;
  let wrapper: VueWrapper | undefined;

  beforeEach(() => {
    lenisMock.virtualScroll = undefined;
    lenisMock.destroyed.mockReset();
    resizeCallbacks = [];
    vi.stubGlobal("ResizeObserver", class {
      constructor(callback: ResizeCallback) { resizeCallback = callback; resizeCallbacks.push(callback); }
      observe = vi.fn();
      disconnect = vi.fn();
    });
    vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => {
      frameCallback = callback;
      return 1;
    });
    vi.spyOn(window, "cancelAnimationFrame").mockImplementation(() => undefined);
    vi.spyOn(window, "matchMedia").mockImplementation(() => ({
      matches: matchReducedMotion,
      media: "(prefers-reduced-motion: reduce)",
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn()
    } as unknown as MediaQueryList));
  });

  afterEach(() => {
    wrapper?.unmount();
    wrapper = undefined;
    document.body.querySelectorAll(".el-overlay").forEach((element) => element.remove());
    resizeCallback = undefined;
    resizeCallbacks = [];
    frameCallback = undefined;
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  function bodyCards(): HTMLElement[] {
    return Array.from(document.body.querySelectorAll<HTMLElement>(".release-notes-card"));
  }

  function runFrame(): void {
    const callback = frameCallback;
    frameCallback = undefined;
    callback?.(0);
  }

  function refreshTransforms(): void {
    resizeCallbacks.forEach((callback) => callback([] as ResizeObserverEntry[], {} as ResizeObserver));
    runFrame();
  }

  function applyLayout(): HTMLElement {
    const viewport = document.body.querySelector(".release-notes-scroll") as HTMLElement;
    const end = document.body.querySelector(".release-notes-stack-end") as HTMLElement;
    Object.defineProperty(viewport, "clientHeight", { configurable: true, value: 400 });
    Object.defineProperty(viewport, "scrollHeight", { configurable: true, value: 4_500 });
    Array.from(document.body.querySelectorAll<HTMLElement>(".release-notes-card")).forEach((card, index) => {
      Object.defineProperty(card, "offsetTop", { configurable: true, value: 80 + index * 400 });
    });
    Object.defineProperty(end, "offsetTop", { configurable: true, value: 3_280 });
    resizeCallbacks.forEach((callback) => callback([] as ResizeObserverEntry[], {} as ResizeObserver));
    runFrame();
    return viewport;
  }

  async function mountOpen(releases: ReleaseNotesRelease[] = [CURRENT_RELEASE]): Promise<HTMLElement> {
    wrapper = mount(ReleaseNotesDialog, {
      props: { modelValue: true, releases },
      global: { plugins: [ElementPlus] },
      attachTo: document.body
    });
    await nextTick();
    await nextTick();
    return applyLayout();
  }

  it("renders fixed intro/outro cards and uses the reference stack transforms", async () => {
    const viewport = await mountOpen();
    const cards = bodyCards();
    expect(cards).toHaveLength(CURRENT_RELEASE.items.length + 2);
    expect(cards[0].textContent).toContain("更新日志");
    expect(cards.at(-1)?.textContent).toContain("继续下滑开始使用");
    expect(document.body.querySelector(".release-notes-footer")).toBeNull();
    Object.defineProperty(viewport, "scrollTop", { configurable: true, writable: true, value: 100 });
    viewport.dispatchEvent(new Event("scroll"));
    refreshTransforms();
    expect(document.body.querySelector(".release-notes-card")?.getAttribute("style")).toContain("scale(0.85)");
    expect(document.body.querySelector(".release-notes-dialog")?.getAttribute("style")).not.toContain("background");

    Object.defineProperty(viewport, "scrollTop", { configurable: true, writable: true, value: 800 });
    viewport.dispatchEvent(new Event("scroll"));
    runFrame();
    expect(cards[1].getAttribute("style")).toContain("translate3d");
    expect(cards[1].getAttribute("style")).toContain("scale(");
    expect(cards[1].getAttribute("style")).not.toContain("filter");

    Object.defineProperty(viewport, "scrollTop", { configurable: true, writable: true, value: 2_900 });
    refreshTransforms();
    expect(cards.at(-1)?.getAttribute("style")).toContain("scale(1.06)");
    const stableStyle = cards[2].getAttribute("style");
    refreshTransforms();
    expect(cards[2].getAttribute("style")).toBe(stableStyle);
  });

  it("merges multiple releases into one ordered stack with version labels", async () => {
    await mountOpen(MERGED_RELEASES);
    const cards = bodyCards();
    const itemCount = MERGED_RELEASES.reduce((total, release) => total + release.items.length, 0);
    expect(cards).toHaveLength(itemCount + 2);
    expect(cards[0].textContent).toContain("v1.2.0 → v1.3.0");
    expect(cards[0].textContent).toContain("共 2 个版本、4 项更新");
    expect(cards[0].textContent).toContain("更新日志");
    expect(cards.at(-1)?.textContent).toContain("继续下滑开始使用");
    expect(cards.slice(1, -1).map((card) => card.querySelector(".release-notes-card-category")?.textContent)).toEqual([
      "v1.2.0 · 执行控制",
      "v1.2.0 · 对象浏览",
      "v1.3.0 · 交互细节",
      "v1.3.0 · 结果操作"
    ]);
  });

  it("requires an additional downward gesture after the last card before dismissing", async () => {
    const viewport = await mountOpen();
    expect(wrapper?.emitted("dismiss")).toBeUndefined();

    Object.defineProperty(viewport, "scrollTop", { configurable: true, writable: true, value: 2_900 });
    viewport.dispatchEvent(new Event("scroll"));
    refreshTransforms();
    lenisMock.virtualScroll?.({ deltaY: 64 });
    expect(wrapper?.emitted("dismiss")).toBeUndefined();
    lenisMock.virtualScroll?.({ deltaY: 32 });
    expect(wrapper?.emitted("dismiss")).toHaveLength(1);
    expect(wrapper?.emitted("update:modelValue")).toEqual([[false]]);
  });

  it("resets completion intent when scrolling upward and cleans up the stack", async () => {
    const viewport = await mountOpen();
    Object.defineProperty(viewport, "scrollTop", { configurable: true, writable: true, value: 2_900 });
    viewport.dispatchEvent(new Event("scroll"));
    refreshTransforms();
    lenisMock.virtualScroll?.({ deltaY: 64 });
    lenisMock.virtualScroll?.({ deltaY: -8 });
    lenisMock.virtualScroll?.({ deltaY: 64 });
    expect(wrapper?.emitted("dismiss")).toBeUndefined();

    await wrapper?.setProps({ modelValue: false });
    expect(lenisMock.destroyed).toHaveBeenCalled();
    expect(window.cancelAnimationFrame).toHaveBeenCalled();
    wrapper?.unmount();
    expect(document.body.querySelector(".release-notes-dialog")).toBeNull();
  });

  it("falls back to a readable list when reduced motion is requested", async () => {
    matchReducedMotion = true;
    await mountOpen();
    const cards = bodyCards();
    expect(cards).toHaveLength(CURRENT_RELEASE.items.length + 2);
    expect(cards[0].getAttribute("style")).not.toContain("transform:");
    expect(cards[0].getAttribute("style")).not.toContain("filter:");
    expect(document.body.querySelector(".release-notes-scroll")).toBeTruthy();
  });
});
