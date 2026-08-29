import { afterEach, describe, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import { mount } from "@vue/test-utils";
import SqlExecutionTimer from "./SqlExecutionTimer.vue";

describe("SqlExecutionTimer", () => {
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

  it("updates elapsed digits by animation frame and stops after unmount", async () => {
    let now = 1_000;
    let nextFrame: FrameRequestCallback | undefined;
    const requestFrame = vi.fn((callback: FrameRequestCallback) => {
      nextFrame = callback;
      return 7;
    });
    const cancelFrame = vi.fn();
    vi.spyOn(Date, "now").mockImplementation(() => now);
    vi.stubGlobal("requestAnimationFrame", requestFrame);
    vi.stubGlobal("cancelAnimationFrame", cancelFrame);

    const wrapper = mount(SqlExecutionTimer, { props: { startedAt: 877 } });
    expect(displayText(wrapper.text())).toBe("正在执行 SQL(123ms)");
    expect(wrapper.classes()).toContain("is-normal");

    now = 12_111;
    nextFrame?.(0);
    await nextTick();
    expect(displayText(wrapper.text())).toBe("正在执行 SQL(11s 234ms)");
    expect(wrapper.classes()).toContain("is-warning");
    expect(wrapper.get(".sql-execution-timer__flip--seconds [data-value='11']")
      .attributes("data-value")).toBe("11");

    now = 62_111;
    nextFrame?.(0);
    await nextTick();
    expect(displayText(wrapper.text())).toBe("正在执行 SQL(1m 1s 234ms)");
    expect(wrapper.classes()).toContain("is-danger");

    wrapper.unmount();
    expect(cancelFrame).toHaveBeenCalledWith(7);
  });

  it("renders the elapsed duration inline for timeline stages", async () => {
    vi.spyOn(Date, "now").mockReturnValue(2_284);
    vi.stubGlobal("requestAnimationFrame", vi.fn(() => 8));
    vi.stubGlobal("cancelAnimationFrame", vi.fn());

    const wrapper = mount(SqlExecutionTimer, {
      props: { startedAt: 1_000, label: "Preparing Result", inline: true }
    });
    await nextTick();
    expect(displayText(wrapper.text())).toBe("Preparing Result · 1s 284ms");
    expect(wrapper.classes()).toContain("is-inline");
    expect(wrapper.text()).not.toContain("(");
    wrapper.unmount();
  });
});

function displayText(value: string): string {
  return value.replaceAll("\u00a0", " ");
}
