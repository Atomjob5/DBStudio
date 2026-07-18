import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElTree } from "element-plus";
import ConnectionManagerPanel from "./ConnectionManagerPanel.vue";

const rpcRequest = vi.hoisted(() => vi.fn());
vi.mock("../bridge/rpc", () => ({ rpc: { request: rpcRequest } }));

const systems = [
  { id: "system-a", name: "订单系统", revision: "1" },
  { id: "system-b", name: "资金系统", revision: "1" }
];
const environments = [
  { id: "environment-dev", systemId: "system-a", name: "DEV", revision: "1" },
  { id: "environment-sit", systemId: "system-b", name: "SIT", revision: "1" }
];
const profiles = [
  { id: "profile-a", providerId: "mysql", name: "订单库", environmentId: "environment-dev", revision: "1",
    settings: { host: "127.0.0.1" }, rememberPassword: false },
  { id: "profile-b", providerId: "mysql", name: "资金库", environmentId: "environment-sit", revision: "1",
    settings: { host: "127.0.0.1" }, rememberPassword: false }
];

function node(data: Record<string, unknown>): { data: Record<string, unknown> } { return { data }; }

describe("ConnectionManagerPanel", () => {
  let wrapper: VueWrapper;

  beforeEach(() => {
    rpcRequest.mockReset();
    rpcRequest.mockResolvedValue({});
    wrapper = mount(ConnectionManagerPanel, {
      attachTo: document.body,
      props: { systems, environments, profiles },
      global: { plugins: [ElementPlus] }
    });
  });

  afterEach(() => {
    wrapper.unmount();
    document.body.innerHTML = "";
  });

  it("仅允许链接跨环境拖拽，且可以以链接作为放置目标", () => {
    const tree = wrapper.findComponent(ElTree);
    const allowDrag = tree.props("allowDrag") as (value: unknown) => boolean;
    const allowDrop = tree.props("allowDrop") as (source: unknown, target: unknown, type: string) => boolean;
    const sourceProfile = { kind: "profile", id: "profile-a", environmentId: "environment-dev" };

    expect(allowDrag(node(sourceProfile))).toBe(true);
    expect(allowDrag(node({ kind: "environment", id: "environment-dev" }))).toBe(false);
    expect(allowDrop(node(sourceProfile), node({ kind: "environment", id: "environment-sit" }), "inner")).toBe(true);
    expect(allowDrop(node(sourceProfile), node({ kind: "profile", id: "profile-b", environmentId: "environment-sit" }), "prev")).toBe(true);
    expect(allowDrop(node(sourceProfile), node({ kind: "environment", id: "environment-dev" }), "inner")).toBe(false);
    expect(allowDrop(node(sourceProfile), node({ kind: "system", id: "system-b" }), "inner")).toBe(false);
  });

  it("拖到其他链接时移动到目标所属环境", async () => {
    const tree = wrapper.findComponent(ElTree);
    (tree.vm as { $emit: (...args: unknown[]) => void }).$emit("node-drop",
      node({ kind: "profile", id: "profile-a", environmentId: "environment-dev" }),
      node({ kind: "profile", id: "profile-b", environmentId: "environment-sit" }), "before", {});
    await flushPromises();

    expect(rpcRequest).toHaveBeenCalledWith("connection.profile.move", {
      id: "profile-a", environmentId: "environment-sit"
    });
    expect(wrapper.emitted("changed")).toHaveLength(1);
  });

  it("不再显示悬浮菜单按钮，右键仍可打开节点菜单", async () => {
    expect(wrapper.find(".node-more").exists()).toBe(false);
    await wrapper.find(".catalog-node").trigger("contextmenu", { button: 2 });
    await flushPromises();
    expect(document.body.textContent).toContain("新增环境");
    expect(document.body.textContent).toContain("重命名");
  });
});
