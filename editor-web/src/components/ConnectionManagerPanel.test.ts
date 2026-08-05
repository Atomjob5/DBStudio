import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElDropdown, ElDropdownItem, ElTree } from "element-plus";
import ConnectionManagerPanel from "./ConnectionManagerPanel.vue";

const rpcRequest = vi.hoisted(() => vi.fn());
const exportConnections = vi.hoisted(() => vi.fn());
vi.mock("../bridge/rpc", () => ({ rpc: { request: rpcRequest, exportConnections } }));

const providers = [{ id: "mysql", displayName: "MySQL", fields: [], capabilities: [] }];

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

async function expandFirstEnvironment(wrapper: VueWrapper): Promise<void> {
  const tree = wrapper.findComponent(ElTree);
  const root = tree.findAll(".el-tree-node")
    .find((item) => item.element.parentElement?.classList.contains("el-tree"));
  await root?.find(".el-tree-node__content").trigger("click");
  await wrapper.vm.$nextTick();
  await root?.find(".el-tree-node__children .el-tree-node__content").trigger("click");
  await wrapper.vm.$nextTick();
}

describe("ConnectionManagerPanel", () => {
  let wrapper: VueWrapper;

  beforeEach(() => {
    rpcRequest.mockReset();
    exportConnections.mockReset();
    rpcRequest.mockResolvedValue({});
    exportConnections.mockResolvedValue(undefined);
    wrapper = mount(ConnectionManagerPanel, {
      attachTo: document.body,
      props: { providers, systems, environments, profiles },
      global: { plugins: [ElementPlus] }
    });
  });

  afterEach(() => {
    wrapper.unmount();
    document.body.innerHTML = "";
  });

  it("仅允许链接跨环境拖拽，且可以以链接作为放置目标", () => {
    const tree = wrapper.findComponent(ElTree);
    expect(tree.props("defaultExpandAll")).toBe(false);
    expect(tree.props("accordion")).toBe(true);
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

  it("初始不展开系统节点，并保持同级节点手风琴行为", async () => {
    const tree = wrapper.findComponent(ElTree);
    expect(tree.props("defaultExpandAll")).toBe(false);
    expect(tree.props("accordion")).toBe(true);
    expect(tree.findAll(".el-tree-node__children")).toHaveLength(0);

    const treeData = tree.props("data") as Array<{ key: string; children?: Array<{ key: string }> }>;
    const first = treeData[0];
    const second = treeData[1];
    expect(first?.children?.length).toBeGreaterThan(0);
    expect(second?.children?.length).toBeGreaterThan(0);
    const rootNodes = tree.findAll(".el-tree-node")
      .filter((item) => item.element.parentElement?.classList.contains("el-tree"));
    await rootNodes[0]?.find(".el-tree-node__content").trigger("click");
    await wrapper.vm.$nextTick();
    expect(rootNodes[0]?.classes()).toContain("is-expanded");
    await rootNodes[1]?.find(".el-tree-node__content").trigger("click");
    await wrapper.vm.$nextTick();
    expect(rootNodes[0]?.classes()).not.toContain("is-expanded");
    expect(rootNodes[1]?.classes()).toContain("is-expanded");
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

  it("仅在链接右键菜单中按编辑、克隆、删除顺序显示克隆入口", async () => {
    await expandFirstEnvironment(wrapper);
    const contextMenus = wrapper.findAllComponents(ElDropdown)
      .filter((item) => item.props("trigger") === "contextmenu");
    const profileMenus = contextMenus.filter((item) =>
      item.findAllComponents(ElDropdownItem).some((entry) => entry.props("command") === "clone"));

    expect(profileMenus).toHaveLength(1);
    expect(profileMenus[0].findAllComponents(ElDropdownItem).map((item) => item.props("command")))
      .toEqual(["edit", "clone", "delete"]);
    expect(contextMenus.filter((item) => !profileMenus.includes(item))
      .every((item) => item.findAllComponents(ElDropdownItem)
        .every((entry) => entry.props("command") !== "clone"))).toBe(true);
  });

  it("立即克隆链接、阻止重复触发并刷新目录", async () => {
    await expandFirstEnvironment(wrapper);
    let finishClone: ((value: unknown) => void) | undefined;
    rpcRequest.mockReturnValueOnce(new Promise((resolve) => { finishClone = resolve; }));
    const profileMenu = wrapper.findAllComponents(ElDropdown)
      .find((item) => item.props("trigger") === "contextmenu" && item.text().includes("订单库"));
    expect(profileMenu).toBeTruthy();

    (profileMenu!.vm as { $emit: (event: string, value: string) => void }).$emit("command", "clone");
    await wrapper.vm.$nextTick();
    expect(rpcRequest).toHaveBeenCalledWith("connection.profile.clone", { id: "profile-a" }, 60_000);
    expect(wrapper.findAllComponents(ElDropdownItem)
      .filter((item) => item.props("command") === "clone")
      .every((item) => item.props("disabled"))).toBe(true);

    (profileMenu!.vm as { $emit: (event: string, value: string) => void }).$emit("command", "clone");
    expect(rpcRequest).toHaveBeenCalledTimes(1);
    finishClone?.({ profile: { ...profiles[0], id: "profile-clone", name: "订单库 - 副本" },
      passwordStatus: "not-remembered" });
    await flushPromises();
    expect(wrapper.emitted("changed")).toHaveLength(1);
    expect(document.body.textContent).toContain("已克隆为“订单库 - 副本”");
  });

  it("密码不可复制时提示副本需要补充密码", async () => {
    await expandFirstEnvironment(wrapper);
    rpcRequest.mockResolvedValueOnce({
      profile: { ...profiles[0], id: "profile-clone", name: "订单库 - 副本", rememberPassword: false },
      passwordStatus: "unavailable"
    });
    const profileMenu = wrapper.findAllComponents(ElDropdown)
      .find((item) => item.props("trigger") === "contextmenu" && item.text().includes("订单库"));

    (profileMenu!.vm as { $emit: (event: string, value: string) => void }).$emit("command", "clone");
    await flushPromises();

    expect(document.body.textContent).toContain("原密码不可用，请在使用前补充密码");
  });

  it("将新增、批量导入和批量导出统一放在原下拉菜单", async () => {
    expect(wrapper.find('button[aria-label="批量导入数据库链接"]').exists()).toBe(false);
    expect(wrapper.find('button[aria-label="批量导出数据库链接"]').exists()).toBe(false);
    const managerDropdown = wrapper.findAllComponents(ElDropdown)[0];
    (managerDropdown.vm as { $emit: (event: string, value: string) => void }).$emit("command", "import");
    await flushPromises();
    expect(document.body.textContent).toContain("批量导入数据库链接");
  });

  it("supports selecting individual profiles for export", async () => {
    const exportDropdown = wrapper.findAllComponents(ElDropdown)[0];
    (exportDropdown.vm as { $emit: (event: string, value: string) => void }).$emit("command", "export-select");
    await flushPromises();
    expect(wrapper.findComponent(ElTree).props("showCheckbox")).toBe(true);
    const batchItems = wrapper.findAllComponents(ElDropdownItem)
      .filter((item) => ["import", "export-select", "export-all"].includes(String(item.props("command"))));
    expect(batchItems).toHaveLength(3);
    expect(batchItems.every((item) => item.props("disabled"))).toBe(true);
    (wrapper.findComponent(ElTree).vm as { $emit: (...args: unknown[]) => void }).$emit("check", {}, {
      checkedNodes: [{ kind: "profile", id: "profile-a" }]
    });
    await flushPromises();
    const action = wrapper.findAll("button").find((button) => button.text().includes("导出选中"));
    await action?.trigger("click");
    await flushPromises();
    expect(exportConnections).toHaveBeenCalledWith("selected", ["profile-a"]);
  });
});
