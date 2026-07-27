import { expect, test, type Page } from "@playwright/test";

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
}

async function dismissCompletionSchemaDialog(page: Page): Promise<void> {
  const schemaDialog = page.getByRole("dialog", { name: "选择 SQL 补全 Schema", exact: true });
  await schemaDialog.waitFor({ state: "visible", timeout: 1_000 }).catch(() => undefined);
  if (await schemaDialog.isVisible()) {
    await schemaDialog.getByRole("button", { name: "取消", exact: true }).click();
    await expect(schemaDialog).toBeHidden();
  }
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

test("enables the optimized 200 by 30 result grid with native wheel scrolling", async ({ page }) => {
  await connectMock(page);
  await dismissCompletionSchemaDialog(page);
  const editor = page.locator(".monaco-editor .view-lines");
  await editor.click();
  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.type("select /*wide_result*/ 1");
  await page.getByRole("button", { name: "执行", exact: true }).click();
  await expect(page.getByText("200 行 · 38 ms", { exact: true })).toBeVisible();
  await expect(page.locator(".el-table-v2")).toBeVisible();
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
  const legacyScroller = page.locator(".el-table-v2__main .el-table-v2__body");
  await legacyScroller.hover();
  await page.mouse.wheel(0, 320);
  await page.mouse.wheel(480, 0);
  await page.waitForTimeout(50);

  await page.getByRole("button", { name: "更多操作", exact: true }).click();
  await page.getByRole("menuitem", { name: "设置", exact: true }).click();
  const settings = page.getByRole("dialog", { name: "设置", exact: true });
  const scrollOptimization = settings.locator(".compact-setting-row").filter({ hasText: "滚动优化" });
  await scrollOptimization.scrollIntoViewIfNeeded();
  await scrollOptimization.locator(".el-switch").click();
  await settings.getByRole("button", { name: "Close this dialog", exact: true }).click();

  const grid = page.locator(".result-virtual-grid__viewport");
  await expect(grid).toBeVisible();
  await expect(page.locator(".el-table-v2")).toHaveCount(0);
  const optimizedTypography = await resultTypography(page);
  expect(optimizedTypography).toEqual(legacyTypography);
  await expect.poll(() => grid.evaluate((element) => ({
    top: Math.round(element.scrollTop), left: Math.round(element.scrollLeft)
  }))).toEqual({ top: 320, left: 480 });
  await grid.evaluate((element) => {
    element.scrollTop = 1600;
    element.scrollLeft = 1200;
    element.dispatchEvent(new Event("scroll"));
  });
  await page.waitForTimeout(50);
  const rendered = await grid.evaluate((element) => ({
    rows: element.querySelectorAll(".result-virtual-grid__row").length,
    headers: element.querySelectorAll(".result-virtual-grid__header-cell").length,
    cells: element.querySelectorAll(".result-virtual-grid__cell").length,
    top: element.scrollTop
  }));
  expect(rendered.rows).toBeLessThan(24);
  expect(rendered.headers).toBeLessThan(18);
  expect(rendered.cells).toBeLessThan(420);
  const alignment = await grid.evaluate((element) => {
    const viewport = element.getBoundingClientRect();
    const header = element.querySelector(".result-virtual-grid__header")!.getBoundingClientRect();
    const headerCell = element.querySelector(".result-virtual-grid__header-cell")!.getBoundingClientRect();
    const cell = element.querySelector(".result-virtual-grid__cell")!.getBoundingClientRect();
    const gutter = element.querySelector(
      ".result-virtual-grid__row .result-virtual-grid__gutter")!.getBoundingClientRect();
    return {
      headerTop: Math.round(header.top),
      viewportTop: Math.round(viewport.top),
      headerCellLeft: Math.round(headerCell.left),
      cellLeft: Math.round(cell.left),
      gutterLeft: Math.round(gutter.left),
      viewportLeft: Math.round(viewport.left)
    };
  });
  expect(alignment.headerTop).toBe(alignment.viewportTop);
  expect(alignment.gutterLeft).toBe(alignment.viewportLeft);
  expect(alignment.cellLeft - alignment.headerCellLeft).toBe(3);

  await grid.hover();
  await page.mouse.wheel(0, 96);
  await expect.poll(() => grid.evaluate((element) => element.scrollTop)).toBeGreaterThan(rendered.top);
  const optimizedPosition = await grid.evaluate((element) => ({
    top: element.scrollTop, left: element.scrollLeft
  }));

  await page.getByRole("button", { name: "更多操作", exact: true }).click();
  await page.getByRole("menuitem", { name: "设置", exact: true }).click();
  const reopenedSettings = page.getByRole("dialog", { name: "设置", exact: true });
  const reopenedOptimization = reopenedSettings.locator(".compact-setting-row").filter({ hasText: "滚动优化" });
  await reopenedOptimization.scrollIntoViewIfNeeded();
  await reopenedOptimization.locator(".el-switch").click();
  await reopenedSettings.getByRole("button", { name: "Close this dialog", exact: true }).click();
  await expect(page.locator(".el-table-v2")).toBeVisible();

  await page.getByRole("button", { name: "更多操作", exact: true }).click();
  await page.getByRole("menuitem", { name: "设置", exact: true }).click();
  const finalSettings = page.getByRole("dialog", { name: "设置", exact: true });
  const finalOptimization = finalSettings.locator(".compact-setting-row").filter({ hasText: "滚动优化" });
  await finalOptimization.scrollIntoViewIfNeeded();
  await finalOptimization.locator(".el-switch").click();
  await finalSettings.getByRole("button", { name: "Close this dialog", exact: true }).click();
  await expect.poll(() => page.locator(".result-virtual-grid__viewport").evaluate((element) => ({
    top: Math.round(element.scrollTop), left: Math.round(element.scrollLeft)
  }))).toEqual({ top: Math.round(optimizedPosition.top), left: Math.round(optimizedPosition.left) });
});

test("shares completion cache across editors and refreshes it only from the object explorer", async ({ page }) => {
  await connectMock(page);
  await expect(page.locator(".completion-status")).toContainText("补全已更新");
  const completionRequests = () => page.evaluate(() =>
    (window as Window & { __DBSTUDIO_MOCK_COUNTS__?: Record<string, number> })
      .__DBSTUDIO_MOCK_COUNTS__?.["metadata.completionSnapshot"] ?? 0);
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
  await expect.poll(completionRequests).toBe(2);
  await expect(page.locator(".completion-status")).toContainText("补全已更新");
});

test("filters duplicate column names by the SQL alias at the cursor", async ({ page }) => {
  await connectMock(page);
  await expect(page.locator(".completion-status")).toContainText("补全已更新");
  const editor = page.locator(".monaco-editor .view-lines");
  await editor.click();
  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.type("select * from sales_order a join sales_order_item b on a.order_id=b.order_id where a.order");
  await page.keyboard.press("Control+Space");

  const widget = page.locator(".suggest-widget.visible");
  await expect(widget).toBeVisible();
  const orderId = widget.locator(".monaco-list-row").filter({ hasText: "order_id" });
  await expect(orderId).toHaveCount(1);
  await expect(orderId).toContainText("sales_order");
  await expect(orderId).not.toContainText("sales_order_item");
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
  await page.getByRole("button", { name: "执行", exact: true }).click();
  await expect(page.getByText("200 行 · 38 ms", { exact: true })).toBeVisible();

  const sort = page.getByRole("button", { name: "按 id 升序", exact: true });
  await sort.click();
  await page.getByRole("button", { name: "按 id 降序", exact: true }).click();
  await expect(page.locator(".result-cell").filter({ hasText: /^200$/ }).first()).toBeVisible();

  await page.getByRole("button", { name: "筛选 id", exact: true }).click();
  await page.locator(".result-filter-popover .el-select").first().click();
  await page.getByRole("option", { name: "包含", exact: true }).click();
  await page.getByRole("textbox", { name: "筛选值", exact: true }).fill("00");
  await page.getByRole("button", { name: "应用", exact: true }).click();
  await expect(page.getByText("显示 2 / 已加载 200 行 · 38 ms", { exact: true })).toBeVisible();

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
  await page.keyboard.press("ControlOrMeta+C");
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText()))
    .toBe("1,Apple Studio 1 ✨\n2,Apple Studio 2 ✨");

  await secondName.click({ button: "right" });
  await page.getByRole("menuitem", { name: "复制", exact: true }).hover();
  await page.getByRole("menuitem", { name: "复制为 IN 语句", exact: true }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText()))
    .toContain("(`id`, `name`) IN ((1, 'Apple Studio 1 ✨'), (2, 'Apple Studio 2 ✨'))");

  const rowNumbers = page.locator(".result-row-number:not(.result-row-number-header)");
  await expect.poll(async () => {
    const gutter = page.locator(".el-table-v2__left").first();
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
    .toContain("UPDATE `demo`.`sample` SET `name` = 'Apple Studio 1 ✨' WHERE `id` = 1;");
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
  await expect(compactRows).toHaveCount(16);
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
  await page.getByRole("button", { name: "Close this dialog", exact: true }).click();
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
});
