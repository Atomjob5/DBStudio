import { expect, test, type Page } from "@playwright/test";

async function connectMock(page: Page): Promise<void> {
  await expect(page.getByRole("heading", { name: "连接数据库", exact: true })).toBeVisible();
  await page.getByRole("menuitem", { name: "本地开发库 127.0.0.1", exact: true }).click();
  await expect(page.getByPlaceholder("例如：本地开发库", { exact: true })).toHaveValue("本地开发库");
  await page.getByRole("button", { name: "连接", exact: true }).click();
  await expect(page.getByText("查询 1", { exact: true })).toBeVisible();
}

test.beforeEach(async ({ page }) => {
  await page.goto("/?mock=1");
});

test("connects and renders a streamed query result with the development bridge", async ({ page }) => {
  await connectMock(page);
  await page.getByRole("button", { name: "执行", exact: true }).click();
  await expect(page.getByText("200 行 · 38 ms", { exact: true })).toBeVisible();
  await expect(page.getByRole("cell", { name: "Apple Studio 1 ✨", exact: true })).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
});

test("selects result headers, reorders columns and resizes with the header handle", async ({ page }) => {
  await connectMock(page);
  await page.getByRole("button", { name: "执行", exact: true }).click();
  await expect(page.getByText("200 行 · 38 ms", { exact: true })).toBeVisible();

  const id = page.getByRole("button", { name: "列 id", exact: true });
  const name = page.getByRole("button", { name: "列 name", exact: true });
  await id.click();
  await name.click({ modifiers: ["ControlOrMeta"] });
  await expect(id).toHaveAttribute("aria-selected", "true");
  await expect(name).toHaveAttribute("aria-selected", "true");
  await expect(page.getByText("2列", { exact: true })).toBeVisible();

  await name.click();
  const idBounds = await id.boundingBox();
  if (!idBounds) throw new Error("Drop target is not measurable");
  const dataTransfer = await page.evaluateHandle(() => new DataTransfer());
  await name.dispatchEvent("dragstart", { dataTransfer });
  await id.dispatchEvent("dragover", { dataTransfer, clientX: idBounds.x + 2 });
  await id.dispatchEvent("drop", { dataTransfer, clientX: idBounds.x + 2 });
  const headers = page.locator(".result-column-title");
  await expect(headers.first()).toHaveText("name");

  const handle = page.getByRole("separator", { name: "调整 name 列宽", exact: true });
  const before = await name.boundingBox();
  const bounds = await handle.boundingBox();
  if (!before || !bounds) throw new Error("Column header is not measurable");
  await page.mouse.move(bounds.x + 1, bounds.y + bounds.height / 2);
  await page.mouse.down();
  await page.mouse.move(bounds.x + 81, bounds.y + bounds.height / 2);
  await page.mouse.up();
  const after = await name.boundingBox();
  expect(after?.width ?? 0).toBeGreaterThan(before.width + 60);
  await handle.dblclick();
  const fitted = await name.boundingBox();
  expect(fitted?.width ?? 0).toBeGreaterThan(72);
  expect(fitted?.width ?? 1000).toBeLessThanOrEqual(600);
});

test("supports Apple appearance, system theme settings and compact windows", async ({ page }) => {
  await expect(page).toHaveScreenshot("apple-connection-light.png");
  await connectMock(page);
  await page.getByRole("button", { name: "执行", exact: true }).click();
  await expect(page.getByText("200 行 · 38 ms", { exact: true })).toBeVisible();
  await expect(page).toHaveScreenshot("apple-workspace-light.png");

  await page.getByRole("button", { name: "切换界面主题", exact: true }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.mouse.move(720, 420);
  await expect(page).toHaveScreenshot("apple-workspace-dark.png");

  await page.getByRole("button", { name: "更多操作", exact: true }).click();
  await page.getByRole("menuitem", { name: "设置", exact: true }).click();
  await expect(page.getByRole("heading", { name: "设置", exact: true })).toBeVisible();
  await expect(page.getByRole("radio", { name: "跟随系统", exact: true })).toBeVisible();
  await expect(page.getByRole("radio", { name: "当前结果集", exact: true })).toBeChecked();
  await page.getByRole("button", { name: "Close this dialog", exact: true }).click();

  await page.getByRole("button", { name: "更多操作", exact: true }).click();
  await page.getByRole("menuitem", { name: "查询历史", exact: true }).click();
  await expect(page.getByRole("heading", { name: "查询历史", exact: true })).toBeVisible();
  await expect(page.getByPlaceholder("筛选 SQL 或状态", { exact: true })).toBeVisible();
  await expect(page).toHaveScreenshot("apple-history-dark.png");
  await page.getByRole("button", { name: "Close this dialog", exact: true }).click();

  await page.getByRole("button", { name: "更多操作", exact: true }).click();
  await page.getByRole("menuitem", { name: "导入 CSV / TSV", exact: true }).click();
  const csvDialog = page.getByRole("dialog", { name: "导入 CSV / TSV", exact: true });
  await expect(csvDialog).toBeVisible();
  await expect(page.getByText("文件内容只在本机解析，不会上传到网络", { exact: true })).toBeVisible();
  await expect(page).toHaveScreenshot("apple-csv-dark.png");
  await csvDialog.getByRole("button", { name: "取消", exact: true }).click();
  await expect(csvDialog).toBeHidden();

  await page.setViewportSize({ width: 1024, height: 640 });
  await expect(page.locator("html")).toHaveJSProperty("scrollWidth", 1024);
  await expect(page).toHaveScreenshot("apple-workspace-compact-dark.png");
});

test("follows the system color scheme and reduces nonessential motion", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "dark", reducedMotion: "reduce" });
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  const duration = await page.locator(".connection-pill").evaluate((element) => getComputedStyle(element).transitionDuration);
  expect(Number.parseFloat(duration)).toBeLessThan(0.01);
});
