import { expect, test, type Page } from "@playwright/test";

const completionRequestCounts = new WeakMap<Page, number>();

async function installCompletionSnapshotRoute(page: Page): Promise<void> {
  completionRequestCounts.set(page, 0);
  await page.route("**/api/v1/workspaces/*/metadata/completion-snapshot", async (route) => {
    completionRequestCounts.set(page, (completionRequestCounts.get(page) ?? 0) + 1);
    const tableNames = ["customer", "order_item", "product", "sales_order", "sales_order_item"];
    const columns: Record<string, string[]> = {
      sales_order: ["order_id", "customer_id", "created_at"],
      sales_order_item: ["order_id", "product_id", "quantity"]
    };
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        formatVersion: 1,
        providerId: "mysql",
        sourceProfileId: "profile-dev",
        generatedAt: new Date().toISOString(),
        defaultNamespaceKey: "catalog:eastwealthcrawler",
        selectedNamespaceKeys: ["catalog:eastwealthcrawler"],
        namespaces: [{
          key: "catalog:eastwealthcrawler",
          catalog: "eastwealthcrawler",
          schema: "",
          label: "eastwealthcrawler",
          objects: tableNames.map((name) => ({
            name,
            kind: "table",
            remarks: "",
            columns: (columns[name] ?? []).map((column) => ({
              name: column,
              typeName: "VARCHAR",
              remarks: ""
            }))
          }))
        }]
      })
    });
  });
}

async function ensureMockWorkspace(page: Page): Promise<void> {
  const picker = page.getByRole("main", { name: "选择工作空间" });
  const selector = page.locator(".connection-pill input");
  await expect(picker.or(selector)).toBeVisible();
  if (!(await picker.isVisible())) return;
  await page.getByRole("button", { name: "创建第一个工作空间" }).click();
  await page.getByPlaceholder("例如：订单系统开发").fill("Playwright 工作空间");
  await page.getByRole("button", { name: "创建", exact: true }).click();
  await expect(selector).toBeVisible();
}

async function connectMock(page: Page): Promise<void> {
  const selector = page.locator(".connection-pill input");
  await expect(selector).toBeVisible();
  await selector.click();
  const dropdown = page.locator(".el-cascader__dropdown:visible");
  await dropdown.getByText("核心系统", { exact: true }).click();
  await dropdown.getByText("DEV", { exact: true }).click();
  await dropdown.getByText("本地开发库", { exact: true }).click();
  await expect(selector).toHaveValue("DEV / 本地开发库");
  await initializeCompletionSchemaDialog(page);
}

async function initializeCompletionSchemaDialog(page: Page): Promise<void> {
  const schemaDialog = page.getByRole("dialog", { name: "选择 SQL 补全 Schema", exact: true });
  await schemaDialog.waitFor({ state: "visible", timeout: 1_000 }).catch(() => undefined);
  if (await schemaDialog.isVisible()) {
    await schemaDialog.getByRole("button", { name: "开始缓存", exact: true }).click();
    await expect(schemaDialog).toBeHidden();
  }
}

async function dismissCompletionSchemaDialog(page: Page): Promise<void> {
  const schemaDialog = page.getByRole("dialog", { name: "选择 SQL 补全 Schema", exact: true });
  await schemaDialog.waitFor({ state: "visible", timeout: 1_000 }).catch(() => undefined);
  if (await schemaDialog.isVisible()) {
    await schemaDialog.getByRole("button", { name: "取消", exact: true }).click();
    await expect(schemaDialog).toBeHidden();
  }
}

async function stageForUpdateDraft(page: Page, value: string): Promise<void> {
  const editor = page.locator(".monaco-editor .view-lines");
  await editor.click();
  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.insertText("select id, name from sample for update");
  await page.getByRole("button", { name: "执行", exact: true }).click();
  await expect(page.getByText("200 行 · 38 ms", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "切换结果编辑模式", exact: true }).click();
  await page.locator(".result-cell").filter({ hasText: /^Apple Studio 1 ✨$/ }).first().dblclick();
  const cellEditor = page.getByRole("textbox", { name: "编辑结果值", exact: true });
  await cellEditor.fill(value);
  await cellEditor.press("Enter");
  await expect(page.locator(".result-cell-pending").filter({ hasText: value })).toBeVisible();
}

async function replaceSql(page: Page, sql: string): Promise<void> {
  const editor = page.locator(".monaco-editor .view-lines");
  await editor.click();
  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.insertText(sql);
}

async function expectCompletionUpdated(page: Page): Promise<void> {
  await page.getByRole("button", { name: "系统状态和通知", exact: true }).hover();
  await expect(page.getByLabel("所有系统状态和通知")).toContainText("补全已更新");
  await page.mouse.move(720, 420);
}

async function resultTypography(page: Page) {
  return page.locator(".table-host").evaluate((host) => {
    const cell = getComputedStyle(host.querySelector(".result-cell")!);
    const title = getComputedStyle(host.querySelector(".result-column-title")!);
    return {
      cellFamily: cell.fontFamily,
      cellSize: cell.fontSize,
      cellWeight: cell.fontWeight,
      cellVariant: cell.fontVariantNumeric,
      cellHeight: cell.height,
      cellLineHeight: cell.lineHeight,
      cellOverflow: cell.overflow,
      cellTextOverflow: cell.textOverflow,
      cellWhiteSpace: cell.whiteSpace,
      titleFamily: title.fontFamily,
      titleSize: title.fontSize,
      titleWeight: title.fontWeight
    };
  });
}

test.beforeEach(async ({ page }) => {
  await installCompletionSnapshotRoute(page);
  await page.goto("/?mock=1");
  await ensureMockWorkspace(page);
});

test("keeps the activity bar flush, restores a collapsed panel and renders a compact connection selector", async ({ page }) => {
  const activityBar = page.locator(".activity-bar");
  await expect(page.locator(".connection-manager")).toBeVisible();
  const activityStyle = await activityBar.evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    const style = getComputedStyle(element);
    return { left: Math.round(bounds.left), topLeft: style.borderTopLeftRadius, bottomLeft: style.borderBottomLeftRadius };
  });
  expect(activityStyle).toEqual({ left: 0, topLeft: "0px", bottomLeft: "0px" });
  await expect(page.getByRole("button", { name: "节点操作", exact: true })).toHaveCount(0);

  await page.locator(".connection-manager").getByText("核心系统", { exact: true }).click({ button: "right" });
  await expect(page.getByRole("menuitem", { name: "新增环境", exact: true })).toBeVisible();
  await page.keyboard.press("Escape");

  const connectionActivity = page.getByRole("button", { name: "连接管理", exact: true });
  const collapse = page.locator(".workbench > .el-splitter-bar .el-splitter-bar__collapse-icon").first();
  await collapse.click();
  await expect(connectionActivity).toHaveAttribute("aria-pressed", "false");
  await connectionActivity.click();
  await expect(connectionActivity).toHaveAttribute("aria-pressed", "true");
  expect((await page.locator(".connection-manager").boundingBox())?.width ?? 0).toBeGreaterThan(200);

  await expect(page.locator(".connection-prefix")).toHaveCount(0);
  await expect(page.locator(".connection-indicator")).toHaveCount(0);
  const pillParts = await page.locator(".connection-pill-wrap").evaluate((element) => {
    const pill = element.getBoundingClientRect();
    const cascader = element.querySelector(".connection-pill")?.getBoundingClientRect();
    return cascader ? {
      pillLeft: pill.left, pillRight: pill.right, pillWidth: pill.width,
      cascaderLeft: cascader.left, cascaderRight: cascader.right, cascaderWidth: cascader.width
    } : null;
  });
  expect(pillParts).not.toBeNull();
  expect(pillParts!.cascaderLeft).toBeCloseTo(pillParts!.pillLeft, 1);
  expect(pillParts!.cascaderRight).toBeCloseTo(pillParts!.pillRight, 1);
  expect(pillParts!.cascaderWidth).toBeCloseTo(pillParts!.pillWidth, 1);
});

test("previews connection workbook imports in a responsive confirmation dialog", async ({ page }) => {
  await page.getByRole("button", { name: "新增或批量管理数据库链接" }).click();
  await page.getByRole("menuitem", { name: "批量导入链接" }).click();
  const dialog = page.getByRole("dialog", { name: "批量导入数据库链接" });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("button", { name: "下载导入模板" })).toBeVisible();
  await dialog.locator('input[type="file"]').setInputFiles({
    name: "connections.xlsx",
    mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    buffer: Buffer.from("mock workbook")
  });
  await expect(dialog.getByText("文件上传成功", { exact: true })).toBeVisible();
  await expect(dialog.getByText(/connections\.xlsx/)).toBeVisible();
  await dialog.getByRole("button", { name: "导入并预览" }).click();
  await expect(dialog.getByText("导入示例系统", { exact: true })).toBeVisible();
  await expect(dialog.getByText("新建系统 · 新建环境", { exact: true })).toBeVisible();
  await expect(dialog.getByText("新增", { exact: true })).toBeVisible();
  await expect(dialog.getByLabel("导入链接密码")).toBeVisible();
  expect(await dialog.locator(".row-actions").evaluate((element) => getComputedStyle(element).flexWrap)).toBe("nowrap");
  const bounds = await dialog.boundingBox();
  expect(bounds).not.toBeNull();
  expect(bounds!.x).toBeGreaterThanOrEqual(0);
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(1440);

  await dialog.getByRole("button", { name: "编辑导入信息" }).click();
  const drawer = page.getByRole("dialog", { name: "修改待导入链接" });
  await expect(drawer.getByText("密码", { exact: true })).toBeVisible();
  await expect(drawer.getByPlaceholder("输入密码")).toBeVisible();
  await expect(drawer.getByText("使用系统密钥库记住密码", { exact: true })).toBeVisible();
});

test("clones a database connection immediately from its context menu", async ({ page }) => {
  const manager = page.locator(".connection-manager");
  await manager.getByText("本地开发库", { exact: true }).click({ button: "right" });
  await expect(page.getByRole("menuitem", { name: "克隆链接", exact: true })).toBeVisible();
  await page.getByRole("menuitem", { name: "克隆链接", exact: true }).click();

  await expect(manager.getByText("本地开发库 - 副本", { exact: true })).toBeVisible();
  await expect(page.getByText("已克隆为“本地开发库 - 副本”", { exact: true })).toBeVisible();
});

test("connects and renders a streamed query result with the development bridge", async ({ page }) => {
  await connectMock(page);
  await expect(page.locator('.connection-pill input')).toHaveValue("DEV / 本地开发库");
  await expect(page.locator(".connection-pill-wrap")).toHaveClass(/suspended/);
  expect(await page.locator(".connection-pill-wrap").evaluate((element) => getComputedStyle(element, "::before").animationName)).toBe("none");
  await page.locator(".connection-pill-wrap").hover();
  await expect(page.getByText("核心系统 / DEV / 本地开发库", { exact: true })).toBeVisible();
  await page.setViewportSize({ width: 1024, height: 640 });
  const selectorLayout = await page.locator(".connection-pill").evaluate((element) => {
    const input = element.querySelector("input")?.getBoundingClientRect();
    const suffix = element.querySelector(".el-input__suffix")?.getBoundingClientRect();
    return input && suffix ? { inputLeft: input.left, inputRight: input.right, suffixLeft: suffix.left, suffixRight: suffix.right } : null;
  });
  expect(selectorLayout).not.toBeNull();
  expect(selectorLayout!.inputLeft).toBeLessThan(selectorLayout!.inputRight);
  expect(selectorLayout!.inputRight).toBeLessThanOrEqual(selectorLayout!.suffixLeft);
  expect(selectorLayout!.suffixRight).toBeLessThanOrEqual(1024);
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.getByRole("button", { name: "执行", exact: true }).click();
  await expect(page.getByText("200 行 · 38 ms", { exact: true })).toBeVisible();
  await expect(page.getByRole("cell", { name: "Apple Studio 1 ✨", exact: true })).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await expect(page.locator(".connection-pill-wrap")).toHaveClass(/connected/);
  const spectrum = await page.locator(".connection-pill-wrap").evaluate((element) => {
    const style = getComputedStyle(element, "::before");
    const inner = getComputedStyle(element, "::after");
    const wrapper = getComputedStyle(element);
    return {
      animationName: style.animationName,
      animationDuration: style.animationDuration,
      opacity: style.opacity,
      edgeWidth: style.paddingTop,
      colors: style.backgroundImage,
      innerAnimationName: inner.animationName,
      innerDuration: inner.animationDuration,
      innerOpacity: inner.opacity,
      innerFilter: inner.filter,
      innerWidth: inner.paddingTop,
      innerColors: inner.backgroundImage,
      separator: wrapper.boxShadow,
      green: getComputedStyle(document.documentElement).getPropertyValue("--db-spectrum-green").trim(),
      cyan: getComputedStyle(document.documentElement).getPropertyValue("--db-spectrum-cyan").trim(),
      blue: getComputedStyle(document.documentElement).getPropertyValue("--db-spectrum-blue").trim(),
      orange: getComputedStyle(document.documentElement).getPropertyValue("--db-spectrum-orange").trim()
    };
  });
  expect(spectrum.animationName).toContain("connection-spectrum");
  expect(spectrum.animationDuration).toBe("5.6s");
  expect(Number(spectrum.opacity)).toBe(1);
  expect(spectrum.edgeWidth).toBe("1px");
  expect(spectrum.colors).toContain("rgb(255, 138, 0)");
  expect(spectrum.innerAnimationName).toContain("connection-spectrum");
  expect(spectrum.innerDuration).toBe("5.6s");
  expect(Number(spectrum.innerOpacity)).toBeCloseTo(0.64, 2);
  expect(spectrum.innerFilter).toBe("blur(0.8px)");
  expect(spectrum.innerWidth).toBe("1px");
  expect(spectrum.innerColors).toContain("rgb(255, 159, 10)");
  expect(spectrum.separator).not.toBe("none");
  expect(spectrum).toMatchObject({ green: "#00a63e", cyan: "#00a7c4", blue: "#006eff", orange: "#ff8a00" });
  await expect(page.locator(".connection-pill-wrap")).toHaveScreenshot("connection-spectrum-light-1440.png");
  await page.setViewportSize({ width: 1024, height: 640 });
  await expect(page.locator(".connection-pill-wrap")).toHaveScreenshot("connection-spectrum-light-1024.png");
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.locator(".connection-pill-wrap").hover();
  expect(Number(await page.locator(".connection-pill-wrap").evaluate((element) => getComputedStyle(element, "::before").opacity))).toBe(1);
  await page.locator(".connection-pill-wrap").evaluate((element) => element.classList.add("stale"));
  expect(await page.locator(".connection-pill-wrap").evaluate((element) => getComputedStyle(element, "::before").animationName)).toBe("none");
  await page.locator(".connection-pill-wrap").evaluate((element) => element.classList.remove("stale"));
  await page.locator(".connection-pill input").focus();
  const focusedSpectrum = await page.locator(".connection-pill-wrap").evaluate((element) => {
    const style = getComputedStyle(element, "::before");
    const inner = getComputedStyle(element, "::after");
    return { playState: style.animationPlayState, opacity: style.opacity,
      innerPlayState: inner.animationPlayState, innerOpacity: inner.opacity };
  });
  expect(focusedSpectrum.playState).toBe("paused");
  expect(Number(focusedSpectrum.opacity)).toBeCloseTo(0.38, 2);
  expect(focusedSpectrum.innerPlayState).toBe("paused");
  expect(Number(focusedSpectrum.innerOpacity)).toBeCloseTo(0.22, 2);
  await page.keyboard.press("Escape");

  await page.getByRole("button", { name: "切换界面主题" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  const darkSpectrum = await page.locator(".connection-pill-wrap").evaluate((element) => ({
    green: getComputedStyle(document.documentElement).getPropertyValue("--db-spectrum-green").trim(),
    cyan: getComputedStyle(document.documentElement).getPropertyValue("--db-spectrum-cyan").trim(),
    blue: getComputedStyle(document.documentElement).getPropertyValue("--db-spectrum-blue").trim(),
    opacity: getComputedStyle(document.documentElement).getPropertyValue("--db-spectrum-opacity").trim(),
    edgeWidth: getComputedStyle(element, "::before").paddingTop,
    edge: getComputedStyle(element, "::before").backgroundImage,
    innerContent: getComputedStyle(element, "::after").content
  }));
  expect(darkSpectrum).toMatchObject({ green: "#30d158", cyan: "#64d2ff", blue: "#0a84ff", opacity: "0.76" });
  expect(darkSpectrum.edgeWidth).toBe("1.5px");
  expect(darkSpectrum.edge).toContain("linear-gradient");
  expect(darkSpectrum.innerContent).toBe("none");
});

test("manages global JDBC slots, execution history, probing, abort and cleanup", async ({ page }) => {
  await connectMock(page);
  await replaceSql(page, "select id, name from sample for update");
  await page.getByRole("button", { name: "执行", exact: true }).click();
  await expect(page.getByText("200 行 · 38 ms", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "更多操作", exact: true }).click();
  const menuItems = page.getByRole("menuitem");
  await expect(menuItems.filter({ hasText: "任务管理器" })).toBeVisible();
  const labels = await menuItems.allTextContents();
  const historyIndex = labels.findIndex((value) => value.includes("查询历史"));
  const taskIndex = labels.findIndex((value) => value.includes("任务管理器"));
  const settingsIndex = labels.findIndex((value) => value.includes("设置"));
  expect(taskIndex).toBeGreaterThan(historyIndex);
  expect(taskIndex).toBeLessThan(settingsIndex);
  await menuItems.filter({ hasText: "任务管理器" }).click();

  const drawer = page.getByRole("dialog", { name: "任务管理器", exact: true });
  await expect(drawer).toBeVisible();
  expect((await drawer.boundingBox())?.width).toBeCloseTo(760, 0);
  await expect(drawer.locator(".jdbc-slot-row")).toHaveCount(10);
  await expect(drawer).toContainText("10 个槽位");
  const idleRow = drawer.locator(".jdbc-slot-row.state-idle").filter({ hasText: "本地开发库" }).first();
  await idleRow.getByRole("button", { name: /探活/ }).click();
  await expect(idleRow).toContainText("探活 8 ms");

  const transactionRow = drawer.locator(".jdbc-slot-row.state-transaction");
  await expect(transactionRow).toContainText("未提交事务");
  await transactionRow.getByRole("button", { name: /展开槽位/ }).click();
  await expect(transactionRow).toContainText("当前查询");
  await transactionRow.getByRole("button", { name: /查看SQL/ }).click();
  const sqlDialog = page.getByRole("dialog", { name: "完整 SQL", exact: true });
  await expect(sqlDialog.getByLabel("完整SQL内容")).toContainText("select id, name from sample for update");
  await page.keyboard.press("Escape");
  await transactionRow.getByRole("button", { name: /强制断开/ }).click();
  const confirmation = page.getByRole("dialog", { name: "强制断开 JDBC 连接", exact: true });
  await expect(confirmation).toContainText("全部未提交修改");
  await confirmation.getByRole("button", { name: "强制断开", exact: true }).click();
  await expect(page.getByText("JDBC 连接已强制断开", { exact: true })).toBeVisible();
  await expect(drawer.locator(".jdbc-slot-row.state-disconnected")).toHaveCount(1);

  await drawer.getByRole("button", { name: "清理过期数据", exact: true }).click();
  const cleanup = page.getByRole("dialog", { name: "清理过期数据", exact: true });
  await cleanup.getByRole("button", { name: "清理", exact: true }).click();
  await expect(drawer.locator(".jdbc-slot-row.state-disconnected")).toHaveCount(0);

  await page.keyboard.press("Escape");
  await expect(drawer).toBeHidden();
  await page.getByRole("button", { name: "执行", exact: true }).click();
  await expect(page.getByText("200 行 · 38 ms", { exact: true })).toBeVisible();
});

test("uses the unified 200 by 30 virtual result grid with native wheel scrolling", async ({ page }) => {
  await connectMock(page);
  await dismissCompletionSchemaDialog(page);
  const editor = page.locator(".monaco-editor .view-lines");
  await editor.click();
  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.type("select /*wide_result*/ 1");
  await page.getByRole("button", { name: "执行", exact: true }).click();
  await expect(page.getByText("200 行 · 38 ms", { exact: true })).toBeVisible();
  await expect(page.locator(".result-virtual-grid")).toBeVisible();
  const legacyTypography = await resultTypography(page);
  expect(legacyTypography).toMatchObject({
    cellSize: "12px",
    cellWeight: "400",
    cellVariant: "tabular-nums",
    cellHeight: "27px",
    cellLineHeight: "27px",
    cellOverflow: "hidden",
    cellTextOverflow: "ellipsis",
    cellWhiteSpace: "nowrap",
    titleSize: "12px",
    titleWeight: "600"
  });
  expect(legacyTypography.cellFamily).toContain("-apple-system");
  expect(legacyTypography.titleFamily).toBe(legacyTypography.cellFamily);
  const resultScroller = page.locator(".result-virtual-grid__viewport");
  await page.locator(".result-cell").filter({ hasText: /^1$/ }).first().click();
  await expect(page.locator(".result-cell.focused")).toHaveText("1");
  await page.keyboard.press("ArrowRight");
  await expect(page.locator(".result-cell.focused")).toHaveText("R1 C2");
  await page.keyboard.press("Shift+ArrowDown");
  await expect(page.locator(".result-cell.selected")).toHaveCount(2);
  await expect(page.locator(".result-cell.focused")).toHaveText("R2 C2");
  await page.keyboard.press("ArrowDown");
  for (let index = 0; index < 20; index++) await page.keyboard.press("ArrowDown");
  for (let index = 0; index < 15; index++) await page.keyboard.press("ArrowRight");
  await expect(page.locator(".result-cell.focused")).toHaveText("R23 C17");
  const legacyFocusVisibility = await page.locator(".result-cell.focused").evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    const viewport = element.closest(".result-virtual-grid__viewport")!.getBoundingClientRect();
    return bounds.top >= viewport.top && bounds.bottom <= viewport.bottom
      && bounds.left >= viewport.left + 34 && bounds.right <= viewport.right;
  });
  expect(legacyFocusVisibility).toBe(true);
  for (let index = 0; index < 22; index++) await page.keyboard.press("ArrowUp");
  for (let index = 0; index < 16; index++) await page.keyboard.press("ArrowLeft");
  await expect(page.locator(".result-cell.focused")).toHaveText("1");
  await resultScroller.hover();
  await page.mouse.wheel(0, 320);
  await page.mouse.wheel(480, 0);
  await page.waitForTimeout(50);

  await page.getByRole("button", { name: "更多操作", exact: true }).click();
  await page.getByRole("menuitem", { name: "设置", exact: true }).click();
  const settings = page.getByRole("dialog", { name: "设置", exact: true });
  await expect(settings.getByText("预渲染缓冲", { exact: true })).toBeVisible();
  await expect(settings.getByRole("spinbutton", { name: "预渲染缓冲", exact: true })).toHaveValue("1.0");
  await settings.getByRole("button", { name: "Close this dialog", exact: true }).click();
  await expect(settings).toBeHidden();

  const grid = page.locator(".result-virtual-grid__viewport");
  await expect(grid).toBeVisible();
  await expect(page.locator(".result-virtual-grid")).toHaveCount(1);
  const optimizedTypography = await resultTypography(page);
  expect(optimizedTypography).toEqual(legacyTypography);
  await expect.poll(() => grid.evaluate((element) => ({
    top: Math.round(element.scrollTop), left: Math.round(element.scrollLeft)
  }))).toEqual({ top: 320, left: 480 });
  const navigationTarget = await grid.evaluate((element) => {
    const viewport = element.getBoundingClientRect();
    const cell = [...element.querySelectorAll<HTMLElement>(".result-virtual-grid__cell")].find((item) => {
      const bounds = item.getBoundingClientRect();
      return bounds.top >= viewport.top && bounds.bottom <= viewport.bottom
        && bounds.left >= viewport.left + 34 && bounds.right <= viewport.right;
    });
    if (!cell) throw new Error("No virtual cell is fully visible for keyboard navigation");
    return { row: cell.dataset.gridRow!, column: cell.dataset.gridColumn! };
  });
  await grid.locator(`[data-grid-row="${navigationTarget.row}"][data-grid-column="${navigationTarget.column}"]`).click();
  await page.keyboard.press("ArrowRight");
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Shift+ArrowRight");
  await expect(page.locator(".result-virtual-grid .result-cell.focused")).toHaveCount(1);
  await expect(page.locator(".result-virtual-grid .result-cell.selected")).toHaveCount(2);
  for (let index = 0; index < 18; index++) await page.keyboard.press("ArrowDown");
  for (let index = 0; index < 10; index++) await page.keyboard.press("ArrowRight");
  await expect.poll(() => page.locator(".result-virtual-grid .result-cell.focused").evaluate((element) => {
      const bounds = element.getBoundingClientRect();
      const viewport = element.closest(".result-virtual-grid__viewport")!.getBoundingClientRect();
      return bounds.top >= viewport.top && bounds.bottom <= viewport.bottom
        && bounds.left >= viewport.left + 34 && bounds.right <= viewport.right;
    })).toBe(true);
  await grid.evaluate((element) => {
    element.scrollTop = 1600;
    element.scrollLeft = 1200;
    element.dispatchEvent(new Event("scroll"));
  });
  await page.waitForTimeout(50);
  const rendered = await grid.evaluate((element) => {
    const root = element.parentElement!;
    const headers = [...root.querySelectorAll<HTMLElement>(".result-virtual-grid__header-cell")];
    const widths = headers.map((header) => header.getBoundingClientRect().width);
    return {
      rows: element.querySelectorAll(".result-virtual-grid__row").length,
      headers: headers.length,
      cells: element.querySelectorAll(".result-virtual-grid__cell").length,
      renderedWidth: widths.reduce((sum, width) => sum + width, 0),
      maximumColumnWidth: Math.max(0, ...widths),
      bodyHeight: element.clientHeight,
      bodyWidth: Math.max(0, element.clientWidth - 34),
      top: element.scrollTop
    };
  });
  expect(rendered.rows).toBeLessThanOrEqual(Math.ceil(rendered.bodyHeight * 3 / 32) + 2);
  expect(rendered.renderedWidth).toBeLessThanOrEqual(
    rendered.bodyWidth * 3 + rendered.maximumColumnWidth * 2 + 1);
  expect(rendered.cells).toBe(rendered.rows * rendered.headers);

  const optimizedGrid = page.locator(".result-virtual-grid");
  await grid.evaluate(async (element) => {
    const maximumLeft = Math.max(0, element.scrollWidth - element.clientWidth);
    element.scrollLeft = 0;
    element.dispatchEvent(new Event("scroll"));
    await Promise.resolve();
    element.scrollLeft = maximumLeft;
    element.dispatchEvent(new Event("scroll"));
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
  });
  await expect(optimizedGrid).toHaveClass(/is-seeking/);
  const seekState = await grid.evaluate((element) => {
    const viewport = element.getBoundingClientRect();
    const visibleCells = [...element.querySelectorAll<HTMLElement>(".result-virtual-grid__cell")]
      .filter((cell) => {
        const bounds = cell.getBoundingClientRect();
        return bounds.bottom > viewport.top && bounds.top < viewport.bottom
          && bounds.right > viewport.left + 34 && bounds.left < viewport.right;
      });
    return {
      visibleCells: visibleCells.length,
      placeholders: visibleCells.filter((cell) => cell.querySelector(".result-virtual-grid__seek-bar")).length,
      populated: visibleCells.filter((cell) => (cell.textContent ?? "").trim().length > 0).length,
      targetColumnVisible: visibleCells.some((cell) => cell.dataset.gridColumn === "29")
    };
  });
  expect(seekState.visibleCells).toBeGreaterThan(0);
  expect(seekState.placeholders).toBe(seekState.visibleCells);
  expect(seekState.populated).toBe(0);
  expect(seekState.targetColumnVisible).toBe(true);
  await expect(optimizedGrid).not.toHaveClass(/is-seeking/);
  await expect.poll(() => grid.evaluate((element) => [...element.querySelectorAll<HTMLElement>(
    '.result-virtual-grid__cell[data-grid-column="29"]')]
    .some((cell) => (cell.textContent ?? "").trim().length > 0))).toBe(true);

  const cdp = await page.context().newCDPSession(page);
  await cdp.send("Emulation.setCPUThrottlingRate", { rate: 4 });
  const jumpCoverage = await grid.evaluate(async (element) => {
    const maximumTop = Math.max(0, element.scrollHeight - element.clientHeight);
    const maximumLeft = Math.max(0, element.scrollWidth - element.clientWidth);
    const targets = [[0.15, 0.1], [0.85, 0.75], [0.35, 0.95], [0.7, 0.25]];
    const samples: number[] = [];
    for (const [topRatio, leftRatio] of targets) {
      element.scrollTop = maximumTop * topRatio;
      element.scrollLeft = maximumLeft * leftRatio;
      element.dispatchEvent(new Event("scroll"));
      await Promise.resolve();
      await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
      const viewport = element.getBoundingClientRect();
      samples.push([...element.querySelectorAll(".result-virtual-grid__cell")]
        .map((cell) => cell.getBoundingClientRect())
        .filter((bounds) => bounds.bottom > viewport.top && bounds.top < viewport.bottom
          && bounds.right > viewport.left + 34 && bounds.left < viewport.right).length);
    }
    return samples;
  });
  await cdp.send("Emulation.setCPUThrottlingRate", { rate: 1 });
  expect(jumpCoverage.every((visibleCells) => visibleCells > 0)).toBe(true);
  await expect(optimizedGrid).not.toHaveClass(/is-seeking/);
  const alignment = await grid.evaluate((element) => {
    const root = element.parentElement!;
    const viewport = element.getBoundingClientRect();
    const header = root.querySelector(".result-virtual-grid__header")!.getBoundingClientRect();
    const headerCell = root.querySelector(".result-virtual-grid__header-cell")!.getBoundingClientRect();
    const cell = element.querySelector(".result-virtual-grid__cell")!.getBoundingClientRect();
    const gutterElement = element.querySelector<HTMLElement>(
      ".result-virtual-grid__row .result-virtual-grid__gutter")!;
    const gutter = gutterElement.getBoundingClientRect();
    return {
      headerBottom: Math.round(header.bottom),
      viewportTop: Math.round(viewport.top),
      headerCellLeft: Math.round(headerCell.left),
      cellLeft: Math.round(cell.left),
      gutterLeft: Math.round(gutter.left),
      viewportLeft: Math.round(viewport.left),
      gutterBackground: getComputedStyle(gutterElement).backgroundColor
    };
  });
  expect(alignment.headerBottom).toBe(alignment.viewportTop);
  expect(alignment.gutterLeft).toBe(alignment.viewportLeft);
  expect(alignment.cellLeft - alignment.headerCellLeft).toBe(3);
  expect(alignment.gutterBackground).not.toMatch(/rgba\([^)]*,\s*0?\.\d+\)/);
  expect(alignment.gutterBackground).not.toContain(" / ");

  const selectedBeforeScrollbarDrag = await optimizedGrid.locator(".result-cell.selected").count();
  const scrollbarDragPoints = await grid.evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    return {
      verticalStart: { x: bounds.right - 3, y: bounds.top + bounds.height / 2 },
      horizontalStart: { x: bounds.left + bounds.width / 2, y: bounds.bottom - 3 },
      body: { x: bounds.left + 80, y: bounds.top + 48 }
    };
  });
  await page.mouse.move(scrollbarDragPoints.verticalStart.x, scrollbarDragPoints.verticalStart.y);
  await page.mouse.down();
  await page.mouse.move(scrollbarDragPoints.body.x, scrollbarDragPoints.body.y);
  await page.mouse.up();
  await page.mouse.move(scrollbarDragPoints.horizontalStart.x, scrollbarDragPoints.horizontalStart.y);
  await page.mouse.down();
  await page.mouse.move(scrollbarDragPoints.body.x, scrollbarDragPoints.body.y);
  await page.mouse.up();
  await expect(optimizedGrid).not.toHaveClass(/is-scrollbar-dragging/);
  await expect(optimizedGrid.locator(".result-cell.selected")).toHaveCount(selectedBeforeScrollbarDrag);

  const optimizedMenuPoint = await grid.evaluate((element) => {
    const viewport = element.getBoundingClientRect();
    const cell = [...element.querySelectorAll(".result-virtual-grid__cell")]
      .map((item) => item.getBoundingClientRect())
      .find((bounds) => bounds.left >= viewport.left + 34 && bounds.right <= viewport.right
        && bounds.top >= viewport.top && bounds.bottom <= viewport.bottom);
    if (!cell) throw new Error("No optimized result cell is fully visible");
    return { x: cell.left + cell.width / 2, y: cell.top + cell.height / 2 };
  });
  await page.mouse.click(optimizedMenuPoint.x, optimizedMenuPoint.y, { button: "right" });
  const optimizedMenuBounds = await page.locator(".result-data-context-menu").boundingBox();
  if (!optimizedMenuBounds) throw new Error("Optimized result context menu is not measurable");
  expect(Math.abs(optimizedMenuBounds.x - optimizedMenuPoint.x)).toBeLessThanOrEqual(2);
  expect(Math.abs(optimizedMenuBounds.y - optimizedMenuPoint.y)).toBeLessThanOrEqual(2);
  await page.keyboard.press("Escape");
  await expect(page.locator(".result-data-context-menu")).toBeHidden();

  const fieldSelector = page.locator(".result-actions .el-select");
  await fieldSelector.click();
  const fieldDropdown = page.locator(".el-select__popper:visible");
  await expect(fieldDropdown).toBeVisible();
  const selectorBounds = await fieldSelector.boundingBox();
  const dropdownBounds = await fieldDropdown.boundingBox();
  if (!selectorBounds || !dropdownBounds) throw new Error("Field selector dropdown is not measurable");
  expect(Math.abs(dropdownBounds.width - selectorBounds.width)).toBeLessThanOrEqual(2);
  const longDetail = fieldDropdown.locator(".column-option small").first();
  await expect(longDetail).toHaveAttribute("title",
    "用于验证字段筛选宽度约束的超长中文注释 Long field remark that must never expand the selector dropdown");
  expect(await longDetail.evaluate((element) => {
    const style = getComputedStyle(element);
    return {
      clipped: element.scrollWidth > element.clientWidth,
      overflow: style.overflow,
      textOverflow: style.textOverflow,
      whiteSpace: style.whiteSpace
    };
  })).toEqual({ clipped: true, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" });
  await page.getByRole("option", { name: /column_1/ }).first().click();
  await page.keyboard.press("Escape");
  await expect(optimizedGrid.locator(".result-column-title")).toHaveCount(1);
  await expect(optimizedGrid.locator(".result-column-title")).toHaveText("column_1");
  await expect(optimizedGrid.locator(".result-virtual-grid__header")).toBeVisible();
  await expect.poll(() => grid.evaluate((element) => element.scrollLeft)).toBe(0);

  await page.setViewportSize({ width: 1024, height: 640 });
  await fieldSelector.click();
  const compactSelectorBounds = await fieldSelector.boundingBox();
  const compactDropdownBounds = await fieldDropdown.boundingBox();
  if (!compactSelectorBounds || !compactDropdownBounds) {
    throw new Error("Compact field selector dropdown is not measurable");
  }
  expect(Math.abs(compactDropdownBounds.width - compactSelectorBounds.width)).toBeLessThanOrEqual(2);
  expect(compactDropdownBounds.x + compactDropdownBounds.width).toBeLessThanOrEqual(1024);
  await page.getByRole("option", { name: /column_1/ }).first().click();
  await page.keyboard.press("Escape");
  await page.setViewportSize({ width: 1440, height: 900 });
  await expect(optimizedGrid.locator(".result-column-title").first()).toHaveText("column_1");

  const virtualFilterButton = page.getByRole("button", { name: "筛选 column_1", exact: true });
  const virtualFilterPopover = page.locator(".result-filter-popover:visible").filter({ hasText: "筛选 column_1" });
  await virtualFilterButton.click();
  await virtualFilterPopover.locator(".el-select").first().click();
  await page.getByRole("option", { name: "大于", exact: true }).click();
  await page.waitForTimeout(250);
  await expect(virtualFilterPopover).toBeVisible();
  await expect(virtualFilterPopover.getByRole("textbox", { name: "筛选值", exact: true })).toBeVisible();
  await page.getByRole("textbox", { name: "筛选值", exact: true }).fill("199");
  await page.getByRole("button", { name: "应用", exact: true }).click();
  await expect(virtualFilterPopover).toHaveCount(0);
  await expect(page.getByText("显示 1 / 已加载 200 行 · 38 ms", { exact: true })).toBeVisible();
  await expect(optimizedGrid.locator(".result-virtual-grid__header")).toBeVisible();
  await expect(optimizedGrid.locator(".result-column-title").first()).toHaveText("column_1");
  await expect.poll(() => grid.evaluate((element) => element.scrollTop)).toBe(0);

  await virtualFilterButton.click();
  await virtualFilterPopover.getByRole("button", { name: "清除", exact: true }).click();
  await expect(virtualFilterPopover).toHaveCount(0);
  await expect(page.getByText("200 行 · 38 ms", { exact: true })).toBeVisible();

  const nativeScrollStart = await grid.evaluate((element) => element.scrollTop);
  await grid.hover();
  await page.mouse.wheel(0, 96);
  await expect.poll(() => grid.evaluate((element) => element.scrollTop)).toBeGreaterThan(nativeScrollStart);
  const optimizedPosition = await grid.evaluate((element) => ({
    top: element.scrollTop, left: element.scrollLeft
  }));

  await page.getByRole("button", { name: "更多操作", exact: true }).click();
  await page.getByRole("menuitem", { name: "设置", exact: true }).click();
  const reopenedSettings = page.getByRole("dialog", { name: "设置", exact: true });
  await expect(reopenedSettings.getByText("预渲染缓冲", { exact: true })).toBeVisible();
  await reopenedSettings.getByRole("button", { name: "Close this dialog", exact: true }).click();
  await expect(page.locator(".result-virtual-grid")).toBeVisible();

  await page.getByRole("button", { name: "更多操作", exact: true }).click();
  await page.getByRole("menuitem", { name: "设置", exact: true }).click();
  const finalSettings = page.getByRole("dialog", { name: "设置", exact: true });
  await expect(finalSettings.getByText("预渲染缓冲", { exact: true })).toBeVisible();
  await finalSettings.getByRole("button", { name: "Close this dialog", exact: true }).click();
  await expect.poll(() => page.locator(".result-virtual-grid__viewport").evaluate((element) => ({
    top: Math.round(element.scrollTop), left: Math.round(element.scrollLeft)
  }))).toEqual({ top: Math.round(optimizedPosition.top), left: Math.round(optimizedPosition.left) });
});

test("shares completion cache across editors and refreshes it only from the object explorer", async ({ page }) => {
  await connectMock(page);
  await expectCompletionUpdated(page);
  const completionRequests = () => completionRequestCounts.get(page) ?? 0;
  await expect.poll(completionRequests).toBe(1);

  await page.getByRole("button", { name: "新建查询", exact: true }).click();
  await expect(page.locator(".editor-tabs .el-tabs__item")).toHaveCount(2);
  await expect.poll(completionRequests).toBe(1);

  await page.getByRole("button", { name: "更多操作", exact: true }).click();
  await page.getByRole("menuitem", { name: "设置", exact: true }).click();
  await expect(page.getByTestId("completion-cache-stats")).toContainText("1个环境");
  await page.getByRole("button", { name: "清理全部补全缓存", exact: true }).click();
  const clearDialog = page.getByRole("dialog", { name: "清理补全缓存", exact: true });
  await expect(clearDialog.getByText("对象树、数据库连接和查询结果不会受到影响", { exact: false })).toBeVisible();
  await clearDialog.getByRole("button", { name: "清理", exact: true }).click();
  await expect(clearDialog).toBeHidden();
  await expect(page.getByTestId("completion-cache-stats")).toContainText("约 0 B · 0个环境");
  await page.waitForTimeout(150);
  await expect.poll(completionRequests).toBe(1);
  await page.getByRole("dialog", { name: "设置", exact: true }).getByRole("button", { name: "Close this dialog", exact: true }).click();

  await page.mouse.move(720, 420);
  await page.getByRole("button", { name: "数据库对象", exact: true })
    .evaluate((element) => (element as HTMLButtonElement).click());
  await expect(page.getByRole("button", { name: "刷新对象树", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "刷新对象树", exact: true }).click();
  const refreshDialog = page.getByRole("dialog", { name: "刷新 SQL 补全缓存", exact: true });
  await expect(refreshDialog).toBeVisible();
  await refreshDialog.getByText("eastwealthcrawler", { exact: true }).click();
  await refreshDialog.getByRole("button", { name: "开始缓存", exact: true }).click();
  await expect.poll(completionRequests).toBe(2);
  await expectCompletionUpdated(page);
});

test("edits a FOR UPDATE result in two stages before committing", async ({ page }) => {
  await connectMock(page);
  await dismissCompletionSchemaDialog(page);
  const editor = page.locator(".monaco-editor .view-lines");
  await editor.click();
  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.insertText("select id, name from sample for update");
  await page.getByRole("button", { name: "执行", exact: true }).click();
  await expect(page.getByText("200 行 · 38 ms", { exact: true })).toBeVisible();

  const unlock = page.getByRole("button", { name: "切换结果编辑模式", exact: true });
  const post = page.getByRole("button", { name: "应用更改", exact: true });
  await expect(unlock).toBeEnabled();
  await expect(post).toHaveCount(0);
  await unlock.click();
  await expect(unlock).toHaveAttribute("aria-pressed", "true");
  await expect(post).toBeDisabled();
  await expect(page.getByRole("button", { name: "新增行", exact: true })).toBeEnabled();
  const editOperations = page.getByRole("group", { name: "结果编辑操作", exact: true });
  await expect.poll(() => editOperations.evaluate((element) => getComputedStyle(element).animationName))
    .toBe("none");
  const editActionPositions = await Promise.all([
    "应用更改", "撤销结果草稿", "新增行", "删除行", "变更清单", "切换结果编辑模式"
  ].map(async (label) => (await page.getByRole("button", { name: label, exact: true }).boundingBox())?.x));
  expect(editActionPositions.every((value) => value !== undefined)).toBe(true);
  expect(editActionPositions).toEqual([...editActionPositions].sort((left, right) => Number(left) - Number(right)));
  await expect(page.locator(".result-header")).toHaveScreenshot("result-edit-actions-expanded-light.png");

  await page.locator(".result-cell").filter({ hasText: /^Apple Studio 1 ✨$/ }).first().dblclick();
  const cellEditor = page.getByRole("textbox", { name: "编辑结果值", exact: true });
  await expect(cellEditor).toBeVisible();
  await cellEditor.fill("Edited locally");
  await cellEditor.press("Enter");
  await expect(page.locator(".result-cell-pending").filter({ hasText: "Edited locally" })).toBeVisible();
  await page.keyboard.press("ArrowLeft");
  await expect(page.locator(".result-cell.focused")).toHaveText("1");
  await page.keyboard.press("Enter");
  await expect(cellEditor).toBeVisible();
  await expect(cellEditor).toHaveValue("1");
  await cellEditor.press("Enter");
  await expect(post).toBeEnabled();

  await unlock.click();
  await expect(page.getByText("仍有未应用的修改，请先应用或撤销后再退出编辑模式", { exact: true })).toBeVisible();
  await expect(unlock).toHaveAttribute("aria-pressed", "true");

  await page.getByRole("button", { name: "变更清单", exact: true }).click();
  const changes = page.getByRole("dialog", { name: "结果变更清单", exact: true });
  await expect(changes).toContainText("UPDATE `demo`.`sample` SET `name` = ? WHERE `id` = ?");
  await changes.getByRole("button", { name: "关闭", exact: true }).click();

  await post.click();
  await expect(page.locator(".result-cell-posted").filter({ hasText: "Edited locally" })).toBeVisible();
  await expect(post).toBeDisabled();
  await unlock.click();
  await expect(unlock).toHaveAttribute("aria-pressed", "false");
  await expect(page.getByRole("group", { name: "结果编辑操作", exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "提交事务", exact: true }).click();
  await expect(page.locator(".result-cell-pending, .result-cell-posted")).toHaveCount(0);
  await expect(unlock).toBeDisabled();
});

test("clones all selected result rows as local insert drafts", async ({ page }) => {
  await connectMock(page);
  await dismissCompletionSchemaDialog(page);
  await replaceSql(page, "select id, name from sample for update");
  await page.getByRole("button", { name: "执行", exact: true }).click();
  await expect(page.getByText("200 行 · 38 ms", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "切换结果编辑模式", exact: true }).click();

  const rowNumbers = page.locator(".result-row-number:not(.result-row-number-header)");
  const first = await rowNumbers.nth(0).boundingBox();
  const second = await rowNumbers.nth(1).boundingBox();
  if (!first || !second) throw new Error("Result row numbers are not measurable");
  await page.mouse.move(first.x + first.width / 2, first.y + first.height / 2);
  await page.mouse.down();
  await page.mouse.move(second.x + second.width / 2, second.y + second.height / 2, { steps: 3 });
  await page.mouse.up();
  await rowNumbers.nth(1).click({ button: "right" });
  const clone = page.getByRole("menuitem", { name: "克隆", exact: true });
  await expect(clone).toBeEnabled();
  await clone.click();

  await expect(page.getByText("202 行 · 38 ms", { exact: true })).toBeVisible();
  await expect(page.locator(".execution-status")).toHaveText("已选中 2 行");
  await page.getByRole("button", { name: "变更清单", exact: true }).click();
  const changes = page.getByRole("dialog", { name: "结果变更清单", exact: true });
  await expect(changes.getByText("克隆新增", { exact: true })).toHaveCount(2);
  await expect(changes).toContainText("INSERT INTO `demo`.`sample`");
  await changes.getByRole("button", { name: "关闭", exact: true }).click();

  await page.getByRole("button", { name: "撤销结果草稿", exact: true }).click();
  await expect(page.getByText("201 行 · 38 ms", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "撤销结果草稿", exact: true }).click();
  await expect(page.getByText("200 行 · 38 ms", { exact: true })).toBeVisible();
});

test("applies pending result edits into the transaction before executing more SQL", async ({ page }) => {
  await connectMock(page);
  await dismissCompletionSchemaDialog(page);
  await stageForUpdateDraft(page, "Apply before next query");
  await replaceSql(page, "select 1");

  await page.getByRole("button", { name: "执行", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "未应用的数据修改", exact: true });
  await expect(dialog).toContainText("似乎还有数据修改后没有应用，请先确认");
  await expect(dialog).toContainText("不会提交事务");
  await dialog.getByRole("button", { name: "应用", exact: true }).click();

  await expect(dialog).toBeHidden();
  await expect(page.locator(".result-cell-pending, .result-cell-posted")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "提交事务", exact: true })).toBeEnabled();
  await expect(page.getByRole("button", { name: "回滚事务", exact: true })).toBeEnabled();
});

test("ignores pending result edits and executes more SQL without applying them", async ({ page }) => {
  await connectMock(page);
  await dismissCompletionSchemaDialog(page);
  await stageForUpdateDraft(page, "Ignore before next query");
  await replaceSql(page, "select 1");

  await page.getByRole("button", { name: "执行", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "未应用的数据修改", exact: true });
  await dialog.getByRole("button", { name: "忽略", exact: true }).click();

  await expect(dialog).toBeHidden();
  await expect(page.getByText("Ignore before next query", { exact: true })).toBeHidden();
  await expect(page.locator(".result-cell-pending, .result-cell-posted")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "提交事务", exact: true })).toBeEnabled();
});

test("cancels SQL execution while preserving pending result edits", async ({ page }) => {
  await connectMock(page);
  await dismissCompletionSchemaDialog(page);
  await stageForUpdateDraft(page, "Keep pending result");
  await replaceSql(page, "select 1");

  await page.getByRole("button", { name: "执行", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "未应用的数据修改", exact: true });
  await dialog.getByRole("button", { name: "取消", exact: true }).click();

  await expect(dialog).toBeHidden();
  await expect(page.locator(".result-cell-pending").filter({ hasText: "Keep pending result" })).toBeVisible();
  await expect(page.getByRole("button", { name: "应用更改", exact: true })).toBeEnabled();
});

test("filters duplicate column names by the SQL alias at the cursor", async ({ page }) => {
  await connectMock(page);
  await expectCompletionUpdated(page);
  const editor = page.locator(".monaco-editor .view-lines");
  await editor.click();
  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.type("select * from sales_order a join sales_order_item b on a.order_id=b.order_id where a.order");
  await page.keyboard.press("Control+Space");

  const widget = page.locator(".suggest-widget.visible");
  await expect(widget).toBeVisible();
  const orderId = widget.locator(".monaco-list-row").filter({ hasText: "order_id" });
  await expect(orderId).toHaveCount(1);
  await expect(widget.locator(".monaco-list-row").filter({ hasText: "sales_order_item" })).toHaveCount(0);
});

test("transforms and comments only the selected SQL with Monaco undo support", async ({ page, context }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"], { origin: "http://127.0.0.1:4173" });
  const uppercase = page.getByRole("button", { name: "转换大写", exact: true });
  const lowercase = page.getByRole("button", { name: "转换小写", exact: true });
  const lineComment = page.getByRole("button", { name: "单行注释", exact: true });
  const blockComment = page.getByRole("button", { name: "全部注释", exact: true });
  const localActions = [uppercase, lowercase, lineComment, blockComment];
  for (const action of localActions) await expect(action).toBeDisabled();

  const editor = page.locator(".monaco-editor .view-lines");
  await editor.click();
  await page.keyboard.press("ControlOrMeta+A");
  const originalSql = "select 'MiXeD' -- Note\n\nfrom Orders";
  await page.keyboard.insertText(originalSql);
  for (const action of localActions) await expect(action).toBeDisabled();

  await page.keyboard.press("ControlOrMeta+A");
  for (const action of localActions) await expect(action).toBeEnabled();

  const expectEditorText = async (text: string) => {
    await page.keyboard.press("ControlOrMeta+A");
    await page.keyboard.press("ControlOrMeta+C");
    await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe(text);
  };

  await uppercase.click();
  const uppercaseSql = "SELECT 'MIXED' -- NOTE\n\nFROM ORDERS";
  await expectEditorText(uppercaseSql);
  await uppercase.click();
  await expectEditorText(uppercaseSql);
  await page.keyboard.press("ControlOrMeta+Z");
  await expectEditorText(originalSql);

  await uppercase.click();
  await expectEditorText(uppercaseSql);
  await lowercase.click();
  const plainSql = "select 'mixed' -- note\n\nfrom orders";
  await expectEditorText(plainSql);

  await lineComment.click();
  await expectEditorText("-- select 'mixed' -- note\n\n-- from orders");
  await lineComment.click();
  await expectEditorText(plainSql);

  await blockComment.click();
  await expectEditorText(`/* ${plainSql} */`);
  await blockComment.click();
  await expectEditorText(plainSql);

  await blockComment.click();
  await expectEditorText(`/* ${plainSql} */`);
  await page.keyboard.press("ControlOrMeta+Z");
  await expectEditorText(plainSql);

  await page.keyboard.press("ArrowRight");
  for (const action of localActions) await expect(action).toBeDisabled();
});

test("uses a static spectrum edge when reduced motion is enabled", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.reload();
  await ensureMockWorkspace(page);
  await connectMock(page);
  await page.getByRole("button", { name: "执行", exact: true }).click();
  await expect(page.getByText("200 行 · 38 ms", { exact: true })).toBeVisible();
  const motion = await page.locator(".connection-pill-wrap").evaluate((element) => {
    const style = getComputedStyle(element, "::before");
    const inner = getComputedStyle(element, "::after");
    return { animationName: style.animationName, background: style.backgroundImage,
      innerAnimationName: inner.animationName, innerBackground: inner.backgroundImage };
  });
  expect(motion.animationName).toBe("none");
  expect(motion.background).toContain("linear-gradient");
  expect(motion.innerAnimationName).toBe("none");
  expect(motion.innerBackground).toContain("linear-gradient");
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

test("sorts, filters, selects cells and copies safe row SQL", async ({ page, context }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"], { origin: "http://127.0.0.1:4173" });
  await connectMock(page);
  await dismissCompletionSchemaDialog(page);
  await page.getByRole("button", { name: "执行", exact: true }).click();
  await expect(page.getByText("200 行 · 38 ms", { exact: true })).toBeVisible();

  const sort = page.getByRole("button", { name: "按 id 升序", exact: true });
  await sort.click();
  await page.getByRole("button", { name: "按 id 降序", exact: true }).click();
  await expect(page.locator(".result-cell").filter({ hasText: /^200$/ }).first()).toBeVisible();

  const idFilterButton = page.getByRole("button", { name: "筛选 id", exact: true });
  const idFilterPopover = page.locator(".result-filter-popover:visible").filter({ hasText: "筛选 id" });
  await idFilterButton.click();
  await expect(idFilterPopover).toBeVisible();
  await idFilterPopover.locator(".el-select").first().click();
  await page.getByRole("option", { name: "包含", exact: true }).click();
  await page.waitForTimeout(250);
  await expect(idFilterPopover).toBeVisible();
  await expect(idFilterPopover.getByRole("textbox", { name: "筛选值", exact: true })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(idFilterPopover).toHaveCount(0);

  await idFilterButton.click();
  await idFilterPopover.locator(".el-select").first().click();
  await page.getByRole("option", { name: "包含", exact: true }).click();
  await page.waitForTimeout(250);
  await page.getByRole("textbox", { name: "筛选值", exact: true }).fill("00");
  await page.getByRole("button", { name: "应用", exact: true }).click();
  await expect(idFilterPopover).toHaveCount(0);
  await expect(page.getByText("显示 2 / 已加载 200 行 · 38 ms", { exact: true })).toBeVisible();

  await idFilterButton.click();
  await expect(idFilterPopover).toBeVisible();
  await page.getByRole("tab", { name: "结果 1", exact: true }).click();
  await expect(idFilterPopover).toHaveCount(0);

  await idFilterButton.click();
  await idFilterPopover.getByRole("button", { name: "清除", exact: true }).click();
  await expect(idFilterPopover).toHaveCount(0);
  await expect(page.getByText("200 行 · 38 ms", { exact: true })).toBeVisible();

  // Re-execution resets sort/filter and gives the selection tests a predictable row order.
  await page.getByRole("button", { name: "执行", exact: true }).click();
  await expect(page.getByText("200 行 · 38 ms", { exact: true })).toBeVisible();
  const firstId = page.locator(".result-cell").filter({ hasText: /^1$/ }).first();
  const secondName = page.locator(".result-cell").filter({ hasText: /^Apple Studio 2 ✨$/ }).first();
  const start = await firstId.boundingBox(); const end = await secondName.boundingBox();
  if (!start || !end) throw new Error("Result cells are not measurable");
  await page.mouse.move(start.x + start.width / 2, start.y + start.height / 2);
  await page.mouse.down();
  await page.mouse.move(end.x + end.width / 2, end.y + end.height / 2, { steps: 5 });
  await page.mouse.up();
  await expect(page.locator(".execution-status")).toHaveText("已选中 2 行");
  await page.keyboard.press("ControlOrMeta+C");
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText()))
    .toBe("1,Apple Studio 1 ✨\n2,Apple Studio 2 ✨");

  const dataMenuPoint = { x: end.x + end.width / 2, y: end.y + end.height / 2 };
  await page.mouse.click(dataMenuPoint.x, dataMenuPoint.y, { button: "right" });
  const dataMenuBounds = await page.locator(".result-data-context-menu").boundingBox();
  if (!dataMenuBounds) throw new Error("Result context menu is not measurable");
  expect(Math.abs(dataMenuBounds.x - dataMenuPoint.x)).toBeLessThanOrEqual(2);
  expect(Math.abs(dataMenuBounds.y - dataMenuPoint.y)).toBeLessThanOrEqual(2);
  await page.getByRole("menuitem", { name: "复制", exact: true }).hover();
  await page.getByRole("menuitem", { name: "复制为 IN 语句", exact: true }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText()))
    .toContain("(id, name) IN ((1, 'Apple Studio 1 ✨'), (2, 'Apple Studio 2 ✨'))");

  const rowNumbers = page.locator(".result-row-number:not(.result-row-number-header)");
  await expect.poll(async () => {
    const gutter = page.locator(".result-virtual-grid__gutter").first();
    return gutter.evaluate((element) => {
      const style = getComputedStyle(element);
      const divider = getComputedStyle(element, "::after");
      return { shadow: style.boxShadow, divider: divider.width, width: element.getBoundingClientRect().width };
    });
  }).toEqual({ shadow: "none", divider: "1px", width: 34 });
  const firstRowNumber = await rowNumbers.nth(0).boundingBox();
  const thirdRowNumber = await rowNumbers.nth(2).boundingBox();
  if (!firstRowNumber || !thirdRowNumber) throw new Error("Row numbers are not measurable");
  await page.mouse.move(firstRowNumber.x + firstRowNumber.width / 2, firstRowNumber.y + firstRowNumber.height / 2);
  await page.mouse.down();
  await page.mouse.move(thirdRowNumber.x + thirdRowNumber.width / 2, thirdRowNumber.y + thirdRowNumber.height / 2, { steps: 5 });
  await page.mouse.up();
  await expect.poll(() => rowNumbers.nth(0).evaluate((element) => {
    const style = getComputedStyle(element, "::before");
    return { width: style.width, color: style.backgroundColor, outline: getComputedStyle(element).outlineStyle };
  })).toEqual({ width: "2px", color: "rgb(0, 113, 227)", outline: "none" });
  await page.keyboard.press("ControlOrMeta+C");
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText()))
    .toBe("1,Apple Studio 1 ✨\n2,Apple Studio 2 ✨\n3,Apple Studio 3 ✨");
  await rowNumbers.nth(2).click({ button: "right" });
  await page.getByRole("menuitem", { name: "复制", exact: true }).hover();
  await page.getByRole("menuitem", { name: "复制为 UPDATE 语句", exact: true }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText()))
    .toContain("UPDATE `demo`.`sample` SET name = 'Apple Studio 1 ✨' WHERE id = 1;");
  await page.locator(".table-host").focus();
  await page.keyboard.press("Escape");
  await expect(page.locator(".execution-status")).toHaveText("执行完成 · 38 ms");
});

test("supports Apple appearance, system theme settings and compact windows", async ({ page }) => {
  test.setTimeout(45_000);
  await expect(page).toHaveScreenshot("apple-connection-light.png");
  await connectMock(page);
  await dismissCompletionSchemaDialog(page);
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
  await expect(page.getByText("多列复制分隔符", { exact: true })).toBeVisible();
  const compactRows = page.locator(".settings-drawer .compact-setting-row");
  await expect(compactRows).toHaveCount(20);
  expect(await compactRows.evaluateAll((rows) => rows.every((row) => {
    const style = getComputedStyle(row);
    const label = row.querySelector(".el-form-item__label")?.getBoundingClientRect();
    const control = row.querySelector(".el-form-item__content")?.getBoundingClientRect();
    return style.display === "grid" && row.scrollWidth <= row.clientWidth
      && !!label && !!control && Math.abs((label.top + label.bottom) / 2 - (control.top + control.bottom) / 2) <= 2;
  }))).toBe(true);
  for (const label of ["逗号分隔符", "Tab分隔符", "分号分隔符", "竖线分隔符"]) {
    await expect(page.getByRole("radio", { name: label, exact: true })).toBeVisible();
  }
  await page.locator(".settings-drawer .separator-options .el-radio-button").filter({ hasText: "|" }).click();
  await expect(page.getByRole("radio", { name: "竖线分隔符", exact: true })).toBeChecked();
  await page.getByLabel("流式推送行数说明", { exact: true }).hover();
  await expect(page.getByRole("tooltip").filter({ hasText: "值越小首屏越快" })).toBeVisible();

  await page.getByRole("button", { name: "配置快捷键", exact: true }).click();
  const shortcutDrawer = page.locator(".shortcut-settings-drawer");
  await expect(shortcutDrawer).toBeVisible();
  await expect(shortcutDrawer.getByRole("heading", { name: "快捷键", exact: true })).toBeVisible();
  await expect(shortcutDrawer.locator(".shortcut-group")).toHaveCount(5);
  await expect(shortcutDrawer.locator(".shortcut-row")).toHaveCount(31);
  await expect(shortcutDrawer.getByText("文件、查询、事务与全局操作 · 13 项", { exact: true })).toBeVisible();
  await expect(shortcutDrawer.getByText("结果数据加载 · 2 项", { exact: true })).toBeVisible();
  const shortcutBounds = await shortcutDrawer.boundingBox();
  if (!shortcutBounds) throw new Error("Shortcut settings drawer is not measurable");
  expect(Math.round(shortcutBounds.width)).toBe(520);
  expect(await shortcutDrawer.evaluate((drawer) => {
    const body = drawer.querySelector(".el-drawer__body");
    const rows = [...drawer.querySelectorAll<HTMLElement>(".shortcut-row")];
    return !!body && body.scrollWidth <= body.clientWidth
      && rows.every((row) => row.scrollWidth <= row.clientWidth);
  })).toBe(true);
  await expect(shortcutDrawer).toHaveScreenshot("apple-shortcuts-dark.png");
  await shortcutDrawer.getByRole("button", { name: "Close this dialog", exact: true }).click();
  await expect(shortcutDrawer).toBeHidden();
  await expect(page.getByRole("heading", { name: "设置", exact: true })).toBeVisible();

  await page.locator(".settings-drawer").getByRole("button", { name: "Close this dialog", exact: true }).click();
  await expect(page.getByRole("heading", { name: "设置", exact: true })).toBeHidden();

  await page.getByRole("button", { name: "更多操作", exact: true }).hover();
  await expect(page.getByRole("menuitem", { name: "查询历史", exact: true })).toBeVisible();
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
  await ensureMockWorkspace(page);
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  const duration = await page.locator(".connection-pill").evaluate((element) => getComputedStyle(element).transitionDuration);
  expect(Number.parseFloat(duration)).toBeLessThan(0.01);

  await connectMock(page);
  await dismissCompletionSchemaDialog(page);
  await replaceSql(page, "select id, name from sample for update");
  await page.getByRole("button", { name: "执行", exact: true }).click();
  await expect(page.getByText("200 行 · 38 ms", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "切换结果编辑模式", exact: true }).click();
  const editMotion = await page.getByRole("group", { name: "结果编辑操作", exact: true })
    .evaluate((element) => {
      const style = getComputedStyle(element);
      return { animationName: style.animationName, transitionDuration: style.transitionDuration };
    });
  expect(editMotion.animationName).toBe("none");
  expect(editMotion.transitionDuration.split(", ").every((value) => Number.parseFloat(value) <= 0.1)).toBe(true);
});
