import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElMessageBox, ElUpload } from "element-plus";
import ConnectionImportDialog from "./ConnectionImportDialog.vue";
import type { ProviderInfo } from "../types";

const rpcRequest = vi.hoisted(() => vi.fn());
const previewConnectionWorkbook = vi.hoisted(() => vi.fn());
const downloadConnectionTemplate = vi.hoisted(() => vi.fn());
vi.mock("../bridge/rpc", () => ({ rpc: {
  request: rpcRequest, previewConnectionWorkbook, downloadConnectionTemplate
} }));

const providers: ProviderInfo[] = [{ id: "mysql", displayName: "MySQL", capabilities: [], fields: [
  { key: "host", label: "主机", type: "TEXT", required: true, defaultValue: "127.0.0.1", description: "主机" },
  { key: "port", label: "端口", type: "NUMBER", required: true, defaultValue: "3306", description: "端口" },
  { key: "database", label: "数据库", type: "TEXT", required: false, defaultValue: "", description: "数据库" },
  { key: "username", label: "用户名", type: "TEXT", required: true, defaultValue: "root", description: "用户名" },
  { key: "password", label: "密码", type: "PASSWORD", required: false, defaultValue: "", description: "密码" },
  { key: "timeoutSeconds", label: "连接超时（秒）", type: "NUMBER", required: true, defaultValue: "10", description: "超时" }
] }];
const systems = [{ id: "system-a", name: "订单系统", revision: "1" }];
const environments = [{ id: "environment-a", systemId: "system-a", name: "DEV", revision: "1" }];
const profiles = [{ id: "profile-a", providerId: "mysql", name: "订单库", environmentId: "environment-a",
  revision: "1", settings: { host: "127.0.0.1" }, rememberPassword: true }];

function preview(errors: string[] = []) {
  return { filename: "connections.xlsx", summary: { total: 1, created: 1, updated: 0, invalid: errors.length,
    newSystems: 1, newEnvironments: 1 }, rows: [{
    rowId: "row-1", sourceRow: 2, profileId: "new-profile", systemName: "资金系统",
    environmentName: "SIT", name: "资金库", providerId: "mysql",
    settings: { host: "10.0.0.8", port: "3306", database: "fund", username: "root", timeoutSeconds: "10" },
    createsSystem: true, createsEnvironment: true, operation: "create", matchedProfileId: "",
    matchedRevision: "", errors, warnings: [], rememberPassword: false
  }] };
}

describe("ConnectionImportDialog", () => {
  let wrapper: VueWrapper;

  beforeEach(() => {
    rpcRequest.mockReset(); previewConnectionWorkbook.mockReset(); downloadConnectionTemplate.mockReset();
    previewConnectionWorkbook.mockResolvedValue(preview());
    downloadConnectionTemplate.mockResolvedValue(undefined);
    rpcRequest.mockImplementation((type: string) => type === "connection.test"
      ? Promise.resolve({ success: true, message: "连接成功", serverVersion: "MySQL 8" })
      : Promise.resolve({ createdSystems: 1, createdEnvironments: 1, createdProfiles: 1,
        updatedProfiles: 0, rows: [] }));
    wrapper = mount(ConnectionImportDialog, {
      attachTo: document.body,
      props: { modelValue: true, providers, systems, environments, profiles },
      global: { plugins: [ElementPlus] }
    });
  });

  afterEach(() => {
    wrapper.unmount();
    document.body.innerHTML = "";
    vi.restoreAllMocks();
  });

  it("downloads the template and previews a selected xlsx workbook", async () => {
    await button("下载导入模板").trigger("click");
    expect(downloadConnectionTemplate).toHaveBeenCalledTimes(1);

    const file = new File(["xlsx"], "connections.xlsx", { type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" });
    const upload = wrapper.findComponent(ElUpload);
    (upload.props("onChange") as (value: unknown) => void)({ name: file.name, raw: file });
    await flushPromises();
    expect(document.body.textContent).toContain("文件上传成功");
    expect(document.body.textContent).toContain("connections.xlsx");
    await button("导入并预览").trigger("click");
    await flushPromises();

    expect(previewConnectionWorkbook).toHaveBeenCalledWith(file);
    expect(document.body.textContent).toContain("资金系统");
    expect(document.body.textContent).toContain("新建系统");
    expect(document.body.textContent).toContain("新增");
  });

  it("tests rows and sends the password only in the final local import request", async () => {
    await openPreview();
    await wrapper.find('.password-cell input[type="password"]').setValue("session-secret");
    await wrapper.find('.password-cell input[type="checkbox"]').setValue(true);
    await wrapper.find('[aria-label="测试单个链接"]').trigger("click");
    await flushPromises();
    expect(document.body.textContent).toContain("连接成功");

    await button("确认导入").trigger("click");
    await flushPromises();
    const call = rpcRequest.mock.calls.find(([type]) => type === "connection.import.commit");
    expect(call).toBeTruthy();
    expect(JSON.stringify(call?.[1])).not.toContain("testPassword");
    expect(JSON.stringify(call?.[1])).toContain("session-secret");
    expect(JSON.stringify(call?.[1])).toContain('"rememberPassword":true');
    expect(document.body.textContent).toContain("数据库链接导入完成");
  });

  it("shows upload errors inline and keeps the workbook selection step active", async () => {
    const upload = wrapper.findComponent(ElUpload);
    const file = new File(["bad"], "connections.csv", { type: "text/csv" });
    (upload.props("onChange") as (value: unknown) => void)({ name: file.name, raw: file });
    await flushPromises();
    expect(document.body.textContent).toContain("仅支持 .xlsx 格式");
    expect((button("导入并预览").element as HTMLButtonElement).disabled).toBe(true);
  });

  it("requires a password before a new link can be remembered", async () => {
    await openPreview();
    await wrapper.find('.password-cell input[type="checkbox"]').setValue(true);
    await flushPromises();
    expect(document.body.textContent).toContain("勾选记住密码时必须填写密码");
    expect((button("确认导入").element as HTMLButtonElement).disabled).toBe(true);
  });

  it("inherits an existing remembered password without returning its value", async () => {
    const existing = preview();
    Object.assign(existing.rows[0], {
      profileId: "profile-a", systemName: "订单系统", environmentName: "DEV", name: "订单库",
      createsSystem: false, createsEnvironment: false, operation: "update",
      matchedProfileId: "profile-a", matchedRevision: "1", rememberPassword: true
    });
    previewConnectionWorkbook.mockResolvedValue(existing);
    await openPreview();

    expect((wrapper.find('.password-cell input[type="checkbox"]').element as HTMLInputElement).checked).toBe(true);
    expect(wrapper.find('.password-cell input[type="password"]').attributes("placeholder"))
      .toBe("留空沿用已保存密码");
    expect((wrapper.find('.password-cell input[type="password"]').element as HTMLInputElement).value).toBe("");
  });

  it("invalidates a successful connection test after the row password changes", async () => {
    await openPreview();
    await wrapper.find('[aria-label="测试单个链接"]').trigger("click");
    await flushPromises();
    expect(document.body.textContent).toContain("连接成功");

    await wrapper.find('.password-cell input[type="password"]').setValue("changed-secret");
    await flushPromises();
    expect(document.body.textContent).toContain("未测试");
  });

  it("keeps invalid rows visible and blocks final confirmation until removed or edited", async () => {
    previewConnectionWorkbook.mockResolvedValue(preview(["端口必须是大于0的整数"]));
    await openPreview();
    expect(document.body.textContent).toContain("端口必须是大于0的整数");
    expect((button("确认导入").element as HTMLButtonElement).disabled).toBe(true);
    await wrapper.find('[aria-label="删除待导入链接"]').trigger("click");
    expect(document.body.textContent).toContain("没有符合条件的待导入链接");
  });

  it("asks for confirmation when untested rows are imported", async () => {
    await openPreview();
    const confirm = vi.spyOn(ElMessageBox, "confirm")
      .mockResolvedValue({ value: "", action: "confirm" } as never);
    await button("确认导入").trigger("click");
    await flushPromises();
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining("未测试或测试失败"), "确认导入",
      expect.objectContaining({ confirmButtonText: "继续导入" }));
  });

  async function openPreview(): Promise<void> {
    const file = new File(["xlsx"], "connections.xlsx");
    const upload = wrapper.findComponent(ElUpload);
    (upload.props("onChange") as (value: unknown) => void)({ name: file.name, raw: file });
    await flushPromises();
    await button("导入并预览").trigger("click");
    await flushPromises();
  }

  function button(text: string) {
    const found = wrapper.findAll("button").find((item) => item.text().includes(text));
    if (!found) throw new Error(`Button not found: ${text}`);
    return found;
  }
});
