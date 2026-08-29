import { afterEach, describe, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import { mount } from "@vue/test-utils";
import SqlExecutionTimelineLoader from "./SqlExecutionTimelineLoader.vue";

describe("SqlExecutionTimelineLoader", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("reveals only occurred stages and follows the 3x3 pixel orbit", async () => {
    const wrapper = mount(SqlExecutionTimelineLoader, { props: { stage: "thinking" } });

    expect(wrapper.findAll(".sql-execution-timeline__step")).toHaveLength(1);
    expect(wrapper.text()).toContain("Thinking");
    expect(wrapper.findAll(".pixel-orbit-loader__cell")).toHaveLength(9);
    expect(wrapper.findAll(".pixel-orbit-loader__cell")[4].classes()).toContain("is-center");
    expect(wrapper.findAll(".sql-execution-timeline__connector")).toHaveLength(0);

    await wrapper.setProps({ stage: "planning" });
    expect(wrapper.findAll(".sql-execution-timeline__step")).toHaveLength(2);
    expect(wrapper.findAll(".sql-execution-timeline__step")[0].classes()).toContain("is-complete");
    expect(wrapper.findAll(".sql-execution-timeline__step")[1].classes()).toContain("is-active");
    expect(wrapper.findAll(".sql-execution-timeline__connector")).toHaveLength(1);

    await wrapper.setProps({ stage: "success" });
    expect(wrapper.findAll(".sql-execution-timeline__step")).toHaveLength(4);
    expect(wrapper.findAll(".sql-execution-timeline__check")).toHaveLength(4);
    expect(wrapper.findAll(".pixel-orbit-loader")).toHaveLength(0);
    wrapper.unmount();
  });

  it("uses the elapsed timer inline while preparing results", async () => {
    const requestFrame = vi.fn(() => 1);
    vi.stubGlobal("requestAnimationFrame", requestFrame);
    vi.stubGlobal("cancelAnimationFrame", vi.fn());
    vi.spyOn(Date, "now").mockReturnValue(2_284);

    const wrapper = mount(SqlExecutionTimelineLoader, {
      props: { stage: "preparing-result", startedAt: 1_000 }
    });
    await nextTick();

    expect(wrapper.text().replaceAll("\u00a0", " ")).toContain("Preparing Result · 1s 284ms");
    expect(wrapper.find(".sql-execution-timer.is-inline").exists()).toBe(true);
    expect(requestFrame).toHaveBeenCalled();
    wrapper.unmount();
  });
});
