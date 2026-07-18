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

test("copies result headers and loaded rows from the header context menu", async ({ page, context }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"], { origin: "http://127.0.0.1:4173" });
  await connectMock(page);
  await page.getByRole("button", { name: "执行", exact: true }).click();
  await expect(page.getByText("200 行 · 38 ms", { exact: true })).toBeVisible();

  const id = page.getByRole("button", { name: "列 id", exact: true });
  const name = page.getByRole("button", { name: "列 name", exact: true });
  await name.locator(".result-column-title").dblclick();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe("name");

  await id.click();
  await name.click({ modifiers: ["ControlOrMeta"] });
  await name.click({ button: "right" });
  const copy = page.getByRole("menuitem", { name: "复制", exact: true });
  await copy.hover();
  const copyAll = page.getByRole("menuitem", { name: "复制列名和数据", exact: true });
  await expect(copyAll).toBeVisible();
  const rootMenuBounds = await page.locator(".result-header-context-menu").boundingBox();
  const copyMenuBounds = await page.locator(".el-popper.result-header-context-submenu").boundingBox();
  if (!rootMenuBounds || !copyMenuBounds) throw new Error("Header menus are not measurable");
  expect(copyMenuBounds.x).toBeGreaterThanOrEqual(rootMenuBounds.x + rootMenuBounds.width - 2);
  await copyAll.click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText()))
    .toContain("id,name\n1,Apple Studio 1 ✨\n2,Apple Studio 2 ✨");

  await id.click();
  await id.click({ button: "right" });
  await page.getByRole("menuitem", { name: "移动到最右", exact: true }).click();
  await expect(page.locator(".result-column-title").first()).toHaveText("name");

  const viewport = page.viewportSize();
  if (!viewport) throw new Error("Viewport is unavailable");
  await id.evaluate((element, point) => element.dispatchEvent(new MouseEvent("contextmenu", {
    bubbles: true, cancelable: true, clientX: point.x, clientY: point.y
  })), { x: viewport.width - 2, y: viewport.height - 2 });
  await expect.poll(async () => {
    const bounds = await page.locator(".result-header-context-menu").boundingBox();
    if (!bounds) return Number.POSITIVE_INFINITY;
    return Math.max(bounds.x + bounds.width - (viewport.width - 7),
      bounds.y + bounds.height - (viewport.height - 7));
  }).toBeLessThanOrEqual(0);
  await page.keyboard.press("Escape");
  await expect(page.locator(".result-header-context-menu")).toBeHidden();
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
  await expect(page.getByText("双击表头复制列名", { exact: true })).toBeVisible();
  await expect(page.getByText("复制多列时的分隔符", { exact: true })).toBeVisible();
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
