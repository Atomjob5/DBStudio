import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import WorkspaceAurora from "./WorkspaceAurora.vue";

type ObserverCallback = (entries: unknown[], observer: unknown) => void;

function createGradient(): CanvasGradient {
  return { addColorStop: vi.fn() } as unknown as CanvasGradient;
}

function createContext(): CanvasRenderingContext2D & { fillRect: ReturnType<typeof vi.fn> } {
  return {
    fillRect: vi.fn(),
    fill: vi.fn(),
    save: vi.fn(),
    restore: vi.fn(),
    setTransform: vi.fn(),
    createLinearGradient: vi.fn(() => createGradient()),
    createRadialGradient: vi.fn(() => createGradient()),
    globalAlpha: 1,
    globalCompositeOperation: "source-over",
    fillStyle: "#000"
  } as unknown as CanvasRenderingContext2D & { fillRect: ReturnType<typeof vi.fn> };
}

describe("WorkspaceAurora", () => {
  let context: CanvasRenderingContext2D & { fillRect: ReturnType<typeof vi.fn> };
  let requestFrame: ReturnType<typeof vi.fn>;
  let cancelFrame: ReturnType<typeof vi.fn>;
  let resizeCallback: ObserverCallback | undefined;
  let themeCallback: ObserverCallback | undefined;
  let matchMediaValue = false;
  let motionChangeListener: ((event: MediaQueryListEvent) => void) | undefined;
  let canvasContextSpy: ReturnType<typeof vi.spyOn>;
  let boundsSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    context = createContext();
    requestFrame = vi.fn(() => 1);
    cancelFrame = vi.fn();
    vi.stubGlobal("requestAnimationFrame", requestFrame);
    vi.stubGlobal("cancelAnimationFrame", cancelFrame);
    vi.stubGlobal("Path2D", class {
      moveTo(): void { /* canvas path stub */ }
      lineTo(): void { /* canvas path stub */ }
      closePath(): void { /* canvas path stub */ }
    });
    vi.stubGlobal("ResizeObserver", class {
      constructor(callback: ObserverCallback) { resizeCallback = callback; }
      observe = vi.fn();
      disconnect = vi.fn();
    });
    vi.stubGlobal("MutationObserver", class {
      constructor(callback: ObserverCallback) { themeCallback = callback; }
      observe = vi.fn();
      disconnect = vi.fn();
    });
    vi.spyOn(window, "matchMedia").mockImplementation(() => ({
      matches: matchMediaValue,
      media: "(prefers-reduced-motion: reduce)",
      onchange: null,
      addEventListener: vi.fn((event: string, listener: EventListenerOrEventListenerObject) => {
        if (event === "change") motionChangeListener = listener as (event: MediaQueryListEvent) => void;
      }),
      removeEventListener: vi.fn(),
      addListener: vi.fn((listener: (event: MediaQueryListEvent) => void) => { motionChangeListener = listener; }),
      removeListener: vi.fn()
    } as unknown as MediaQueryList));
    canvasContextSpy = vi.spyOn(HTMLCanvasElement.prototype, "getContext")
      .mockReturnValue(context);
    boundsSpy = vi.spyOn(HTMLCanvasElement.prototype, "getBoundingClientRect")
      .mockReturnValue({ width: 640, height: 480, top: 0, left: 0, right: 640, bottom: 480, x: 0, y: 0, toJSON: () => ({}) } as DOMRect);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    resizeCallback = undefined;
    themeCallback = undefined;
    motionChangeListener = undefined;
    document.documentElement.removeAttribute("data-theme");
  });

  it("draws a sized decorative canvas and starts a throttled animation", () => {
    const wrapper = mount(WorkspaceAurora);
    const element = wrapper.get("canvas").element as HTMLCanvasElement;

    expect(element.getAttribute("aria-hidden")).toBe("true");
    expect(element.width).toBe(640);
    expect(element.height).toBe(480);
    expect(context.fillRect).toHaveBeenCalled();
    expect(requestFrame).toHaveBeenCalledTimes(1);
    expect(resizeCallback).toBeDefined();
    expect(canvasContextSpy).toHaveBeenCalledWith("2d");
    expect(boundsSpy).toHaveBeenCalled();

    resizeCallback?.([], {});
    expect(context.fillRect.mock.calls.length).toBeGreaterThan(1);

    wrapper.unmount();
    expect(cancelFrame).toHaveBeenCalled();
  });

  it("redraws when the document theme changes and pauses while hidden", () => {
    const addEventListener = vi.spyOn(document, "addEventListener");
    const removeEventListener = vi.spyOn(document, "removeEventListener");
    const wrapper = mount(WorkspaceAurora);
    const beforeTheme = context.fillRect.mock.calls.length;

    document.documentElement.dataset.theme = "dark";
    themeCallback?.([], {});
    expect(context.fillRect.mock.calls.length).toBeGreaterThan(beforeTheme);

    const visibility = addEventListener.mock.calls.find(([type]) => type === "visibilitychange")?.[1];
    expect(visibility).toBeDefined();
    Object.defineProperty(document, "hidden", { configurable: true, value: true });
    (visibility as EventListener)(new Event("visibilitychange"));
    expect(cancelFrame).toHaveBeenCalled();

    Object.defineProperty(document, "hidden", { configurable: true, value: false });
    (visibility as EventListener)(new Event("visibilitychange"));
    expect(requestFrame.mock.calls.length).toBeGreaterThan(1);

    wrapper.unmount();
    expect(removeEventListener.mock.calls.some(([type]) => type === "visibilitychange")).toBe(true);
  });

  it("renders one static frame and does not schedule animation when motion is reduced", () => {
    matchMediaValue = true;
    const wrapper = mount(WorkspaceAurora);

    expect(context.fillRect).toHaveBeenCalled();
    expect(requestFrame).not.toHaveBeenCalled();
    motionChangeListener?.({ matches: false } as MediaQueryListEvent);
    expect(requestFrame).toHaveBeenCalledTimes(1);

    wrapper.unmount();
  });

  it("falls back to CSS when a 2D context is unavailable", async () => {
    canvasContextSpy.mockReturnValue(null);
    const wrapper = mount(WorkspaceAurora);
    await wrapper.vm.$nextTick();

    expect(wrapper.get("canvas").classes()).toContain("is-fallback");
    expect(requestFrame).not.toHaveBeenCalled();
    wrapper.unmount();
  });
});
