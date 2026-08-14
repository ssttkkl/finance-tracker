import { expect, test, type Page } from "@playwright/test";

const account = { id: 101, name: "日常账户", type: "cash", active: true, currencies: ["CNY"] };
type TestCategory = {
  id: string;
  parent_id: string | null;
  name: string;
  description: string | null;
  path: { id: string; name: string }[];
  depth: number;
  sort_order: number;
  revision: number;
};

const category = (id: string, name: string, parent: { id: string; name: string } | null = null, revision = 1): TestCategory => ({
  id,
  parent_id: parent?.id ?? null,
  name,
  description: null,
  path: parent ? [{ id: parent.id, name: parent.name }, { id, name }] : [{ id, name }],
  depth: parent ? 2 : 1,
  sort_order: 1,
  revision,
});

const ledgerOptions = {
  record_types: [{ value: "expense", label: "消费", subtypes: [{ value: "not_applicable", label: "—" }] }],
  relation_types: [],
};

function projection(id: string, counterparty: string, currentCategory: ReturnType<typeof category> | null) {
  return {
    projection_id: `cash:${id}`,
    occurred_at: "2026-08-13T09:00:00+08:00",
    account,
    counterparty,
    category: currentCategory,
    category_id: currentCategory?.id ?? null,
    amount: "-12.50",
    currency: "CNY",
    note: "",
    source_type: "fixture",
    source_types: ["fixture"],
    record_id: `record-${id}`,
    economic_type: "expense",
    transfer_subtype: null,
    composition: [],
    member_count: 1,
    accepted_relation_summary: [],
    visible: true,
    hidden_reason: null,
  };
}

function recordFrom(item: ReturnType<typeof projection>) {
  return {
    id: item.record_id,
    occurred_at: item.occurred_at,
    account,
    account_name: account.name,
    account_id: account.id,
    account_type: account.type,
    amount: item.amount,
    currency: item.currency,
    counterparty: item.counterparty,
    counterparty_account: "",
    counterparty_account_attrs: [],
    note: item.note,
    category: item.category,
    category_id: item.category_id,
    record_type: "expense",
    record_subtype: "not_applicable",
    source_type: item.source_type,
    record_id: item.record_id,
    source_snapshot: null,
  };
}

function evidenceFor(item: ReturnType<typeof projection>, version: number) {
  const record = recordFrom(item);
  return {
    projection_version: version,
    projection: item,
    root_record: record,
    members: [{ ...record, roles: ["root"] }],
    accepted_relations: [],
    inactive_relation_hints: [],
    refund_timeline: [],
  };
}

type Fixture = {
  categories: ReturnType<typeof category>[];
  directoryRevision: number;
  projections: ReturnType<typeof projection>[];
  projectionVersion: number;
  directUsage: Record<string, number>;
  createBodies: Record<string, unknown>[];
  updateBodies: Record<string, unknown>[];
  deleteBodies: Record<string, unknown>[];
  recordBodies: Record<string, unknown>[];
  batchBodies: Record<string, unknown>[];
};

async function installFixture(page: Page, fixture: Fixture) {
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();
    const json = (value: unknown, status = 200) => route.fulfill({ status, json: value });

    if (path.endsWith("/auth/session")) return json({
      user: { email: "e2e@example.com" },
      active_workspace_id: "workspace-e2e",
      workspaces: [{ id: "workspace-e2e", name: "E2E 账本", role: "editor" }],
    });
    if (path.endsWith("/accounts")) return json({ items: [account] });
    if (path.endsWith("/cash-ledger/options")) return json(ledgerOptions);
    if (path.endsWith("/cash-categories") && method === "GET") return json({ revision: fixture.directoryRevision, items: fixture.categories });
    if (path.endsWith("/cash-categories") && method === "POST") {
      const body = request.postDataJSON() as Record<string, unknown>;
      fixture.createBodies.push(body);
      const id = `created-${fixture.categories.length + 1}`;
      const parentId = typeof body.parent_id === "string" ? body.parent_id : null;
      const parent = parentId ? fixture.categories.find((item) => item.id === parentId) ?? null : null;
      const created = category(id, String(body.name), parent ? { id: parent.id, name: parent.name } : null, 1);
      created.description = typeof body.description === "string" ? body.description : null;
      fixture.categories.push(created);
      fixture.directoryRevision += 1;
      return json(created, 201);
    }
    const categoryMatch = path.match(/\/cash-categories\/([^/]+)$/);
    if (categoryMatch && method === "PATCH") {
      const id = decodeURIComponent(categoryMatch[1]);
      const body = request.postDataJSON() as Record<string, unknown>;
      fixture.updateBodies.push(body);
      const current = fixture.categories.find((item) => item.id === id);
      if (!current) return json({ error: { code: "not_found" } }, 404);
      current.name = String(body.name);
      current.description = typeof body.description === "string" ? body.description : null;
      current.revision += 1;
      fixture.directoryRevision += 1;
      return json(current);
    }
    const impactMatch = path.match(/\/cash-categories\/([^/]+)\/deletion-impact$/);
    if (impactMatch && method === "GET") {
      const id = decodeURIComponent(impactMatch[1]);
      const current = fixture.categories.find((item) => item.id === id);
      const childCount = fixture.categories.filter((item) => item.parent_id === id).length;
      return json({ category_id: id, revision: fixture.directoryRevision, category_revision: current?.revision ?? 1, child_count: childCount, direct_usage_count: fixture.directUsage[id] ?? 0 });
    }
    if (categoryMatch && method === "DELETE") {
      const id = decodeURIComponent(categoryMatch[1]);
      fixture.deleteBodies.push(request.postDataJSON() as Record<string, unknown>);
      fixture.categories = fixture.categories.filter((item) => item.id !== id);
      fixture.projections = fixture.projections.map((item) => item.category_id === id ? { ...item, category: null, category_id: null } : item);
      fixture.directoryRevision += 1;
      return json({ category_id: id, cleared_transaction_count: fixture.directUsage[id] ?? 0, revision: fixture.directoryRevision });
    }
    if (path.endsWith("/cash-projections/categories") && method === "PUT") {
      const body = request.postDataJSON() as { projection_ids: string[]; projection_version: number; category_id: string | null };
      fixture.batchBodies.push(body);
      if (body.projection_version !== fixture.projectionVersion) return json({ error: { code: "projection.version_conflict" } }, 409);
      const target = fixture.categories.find((item) => item.id === body.category_id) ?? null;
      fixture.projections = fixture.projections.map((item) => body.projection_ids.includes(item.projection_id) ? { ...item, category: target, category_id: target?.id ?? null } : item);
      fixture.projectionVersion += 1;
      return json({ projection_version: fixture.projectionVersion, projection_count: body.projection_ids.length, updated_transaction_count: body.projection_ids.length, category_id: body.category_id });
    }
    const evidenceMatch = path.match(/\/evidence\/cash-projections\/([^/]+)$/);
    if (evidenceMatch && method === "GET") {
      const item = fixture.projections.find((value) => value.projection_id === decodeURIComponent(evidenceMatch[1]));
      return item ? json(evidenceFor(item, fixture.projectionVersion)) : json({ error: { code: "not_found" } }, 404);
    }
    const recordMatch = path.match(/\/cash-records\/([^/]+)$/);
    if (recordMatch && method === "PUT") {
      const id = decodeURIComponent(recordMatch[1]);
      const body = request.postDataJSON() as Record<string, unknown>;
      fixture.recordBodies.push(body);
      const index = fixture.projections.findIndex((item) => item.record_id === id);
      if (index < 0) return json({ error: { code: "not_found" } }, 404);
      const target = fixture.categories.find((item) => item.id === body.category_id) ?? null;
      fixture.projections[index] = { ...fixture.projections[index], category: target, category_id: target?.id ?? null };
      fixture.projectionVersion += 1;
      return json({ record: recordFrom(fixture.projections[index]), relations: [], options: ledgerOptions });
    }
    if (recordMatch && method === "GET") {
      const item = fixture.projections.find((value) => value.record_id === decodeURIComponent(recordMatch[1]));
      return item ? json({ record: recordFrom(item), relations: [], options: ledgerOptions }) : json({ error: { code: "not_found" } }, 404);
    }
    if (path.endsWith("/cash-projections") && method === "GET") {
      const categoryId = url.searchParams.get("category_id");
      const uncategorized = url.searchParams.get("uncategorized") === "true";
      const items = fixture.projections.filter((item) => uncategorized ? item.category_id === null : !categoryId || item.category_id === categoryId);
      return json({ projection_version: fixture.projectionVersion, items, next_cursor: null, page_size: 50, filters: {}, filter_options: { categories: fixture.categories, currencies: ["CNY"], economic_types: [{ economic_type: "expense", transfer_subtypes: [] }] } });
    }
    return json({ error: { code: "unexpected_request", path, method } }, 500);
  });
}

function fixture(): Fixture {
  const dining = category("dining", "餐饮");
  const lunch = category("lunch", "工作餐", { id: dining.id, name: dining.name });
  const transit = category("transit", "交通");
  return {
    categories: [dining, lunch, transit],
    directoryRevision: 3,
    projections: [projection("001", "咖啡店", dining), projection("002", "午餐", lunch), projection("003", "地铁", transit)],
    projectionVersion: 7,
    directUsage: { dining: 2, transit: 1 },
    createBodies: [],
    updateBodies: [],
    deleteBodies: [],
    recordBodies: [],
    batchBodies: [],
  };
}

test("分类管理在列表末尾创建、编辑，并阻止删除含子分类的父项", async ({ page }) => {
  const state = fixture();
  await installFixture(page, state);
  await page.goto("/cash-categories");

  const tree = page.getByRole("tree", { name: "收支分类目录" });
  await expect(tree).toBeVisible();
  await expect(tree.locator(":scope > li").last()).toContainText("新建一级分类");
  await expect(page.locator(".page-header").getByRole("button")).toHaveCount(0);

  await tree.getByRole("button", { name: "新建一级分类" }).click();
  const editor = page.getByRole("region", { name: "分类编辑" });
  await editor.getByLabel("分类名称").fill("居住");
  await editor.getByLabel("分类描述").fill("房租和水电");
  await editor.getByRole("button", { name: "创建分类" }).click();
  await expect(tree).toContainText("居住");
  await expect(tree.locator(":scope > li").last()).toContainText("新建一级分类");
  expect(state.createBodies).toEqual([{ name: "居住", description: "房租和水电", parent_id: null, expected_revision: 3 }]);

  await tree.getByRole("treeitem").filter({ hasText: "居住" }).click();
  await editor.getByLabel("分类名称").fill("居住费用");
  await editor.getByRole("button", { name: "保存" }).click();
  await expect(tree).toContainText("居住费用");
  expect(state.updateBodies).toEqual([{ name: "居住费用", description: "房租和水电", parent_id: null, expected_revision: 4 }]);

  await tree.getByRole("treeitem").filter({ hasText: /^餐饮/ }).click();
  await editor.getByRole("button", { name: "删除" }).click();
  const confirmation = page.getByRole("alertdialog", { name: "删除分类确认" });
  await expect(confirmation).toContainText("请先处理子分类。");
  await expect(confirmation.getByRole("button", { name: "删除" })).toBeDisabled();
  expect(state.deleteBodies).toEqual([]);
});

test("分类管理使用工作台布局而不是浏览器默认控件样式", async ({ page }, testInfo) => {
  const state = fixture();
  await installFixture(page, state);
  await page.goto("/cash-categories");

  await expect(page.locator(".category-layout")).toBeVisible();
  await expect(page.locator(".category-directory")).toHaveCSS("border-style", "solid");
  await expect(page.locator(".category-tree")).toHaveCSS("list-style-type", "none");
  await expect(page.locator(".category-editor")).toBeVisible();

  const addRoot = page.getByRole("button", { name: "新建一级分类" });
  await expect(addRoot).toHaveCSS("border-style", "none");
  await expect(addRoot).toHaveCSS("min-height", "56px");
  await expect(addRoot).toHaveCSS("display", "flex");
  expect(await page.locator(".category-layout").evaluate((element) => getComputedStyle(element).display)).toBe("grid");
  expect(await page.locator("body").evaluate((body) => body.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath("cash-categories-1440.png"), fullPage: false });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/cash-categories");
  await expect(page.locator(".category-layout")).toBeVisible();
  await expect(page.getByRole("button", { name: "新建一级分类" })).toBeVisible();
  await expect(page.locator(".category-editor")).toBeVisible();
  expect(await page.locator("body").evaluate((body) => body.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath("cash-categories-390.png"), fullPage: false });

  await page.getByRole("button", { name: "打开菜单" }).click();
  await page.screenshot({ path: testInfo.outputPath("cash-categories-nav-390.png"), fullPage: false });
});

test("分类管理确认删除已使用叶子分类时显示影响并刷新目录", async ({ page }) => {
  const state = fixture();
  await installFixture(page, state);
  await page.goto("/cash-categories");

  const tree = page.getByRole("tree", { name: "收支分类目录" });
  await tree.getByRole("treeitem").filter({ hasText: /^交通/ }).click();
  await page.getByRole("region", { name: "分类编辑" }).getByRole("button", { name: "删除" }).click();
  const confirmation = page.getByRole("alertdialog", { name: "删除分类确认" });
  await expect(confirmation).toContainText("有 1 笔流水会改为无分类。");
  await confirmation.getByRole("button", { name: "删除" }).click();
  await expect(tree).not.toContainText("交通");
  expect(state.deleteBodies).toEqual([{ expected_revision: 3, expected_category_revision: 1, expected_usage_count: 1, confirmed: true }]);
});

test("账本在真实浏览器中按分类筛选、编辑详情分类，并以键盘批量修改", async ({ page }) => {
  const state = fixture();
  await installFixture(page, state);
  await page.goto("/");

  const filters = page.getByRole("group", { name: "账本筛选工具" });
  await filters.locator("summary").click();
  await filters.getByLabel("分类").selectOption("dining");
  await expect(page.getByRole("button", { name: "查看咖啡店的收支详情" })).toBeVisible();
  await expect(page.getByRole("button", { name: "查看午餐的收支详情" })).toHaveCount(0);
  await filters.getByLabel("分类").selectOption("");
  await expect(page.getByRole("button", { name: "查看午餐的收支详情" })).toBeVisible();

  await page.getByRole("button", { name: "查看咖啡店的收支详情" }).click();
  const detail = page.getByRole("dialog", { name: "收支详情" });
  await detail.getByRole("button", { name: "编辑", exact: true }).click();
  const editor = page.getByRole("dialog", { name: "编辑收支详情" });
  await editor.getByLabel("分类").selectOption("lunch");
  await editor.getByRole("button", { name: "保存" }).click();
  await expect(page.getByRole("dialog", { name: "收支详情" })).toContainText("餐饮 / 工作餐");
  expect(state.recordBodies).toEqual([expect.objectContaining({ category_id: "lunch", projection_version: 7 })]);
  await page.getByRole("dialog", { name: "收支详情" }).getByRole("button", { name: "关闭收支详情", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "收支详情" })).toHaveCount(0);

  const coffee = page.getByLabel("选择咖啡店");
  await coffee.focus();
  await page.keyboard.press("Space");
  await page.getByLabel("选择午餐").check();
  const toolbar = page.getByRole("toolbar", { name: "批量操作" });
  await expect(toolbar).toContainText("已选 2 项");
  await expect(toolbar).toBeVisible();
  await expect.poll(async () => toolbar.evaluate((element) => getComputedStyle(element).position)).toBe("fixed");
  const toolbarBox = await toolbar.boundingBox();
  expect(toolbarBox).not.toBeNull();
  expect(toolbarBox!.y + toolbarBox!.height).toBeLessThanOrEqual(page.viewportSize()!.height);
  await toolbar.getByRole("button", { name: "修改分类" }).click();
  const batch = page.getByRole("dialog", { name: "修改分类" });
  await batch.getByLabel("分类", { exact: true }).selectOption("transit");
  await batch.getByRole("button", { name: "保存" }).click();
  await expect(page.getByRole("toolbar", { name: "批量操作" })).toHaveCount(0);
  await expect(page.getByRole("row", { name: /咖啡店/ })).toContainText("交通");
  await expect(page.getByRole("row", { name: /午餐/ })).toContainText("交通");
  expect(state.batchBodies).toEqual([{ projection_ids: ["cash:001", "cash:002"], projection_version: 8, category_id: "transit" }]);
});

test("批量分类遇到版本失效时清空选择、刷新列表并要求重新选择", async ({ page }) => {
  const state = fixture();
  await installFixture(page, state);
  await page.goto("/");

  await page.getByLabel("选择咖啡店").check();
  await page.getByLabel("选择午餐").check();
  state.projectionVersion += 1;
  await page.getByRole("toolbar", { name: "批量操作" }).getByRole("button", { name: "修改分类" }).click();
  const batch = page.getByRole("dialog", { name: "修改分类" });
  await batch.getByLabel("分类", { exact: true }).selectOption("transit");
  await batch.getByRole("button", { name: "保存" }).click();
  await expect(page.getByRole("alert")).toContainText("列表已更新，请重新选择记录。");
  await expect(page.getByRole("toolbar", { name: "批量操作" })).toHaveCount(0);
  expect(state.batchBodies).toEqual([{ projection_ids: ["cash:001", "cash:002"], projection_version: 7, category_id: "transit" }]);
});

test("窄屏分类管理保持列表末尾入口和无横向滚动", async ({ page }) => {
  const state = fixture();
  await installFixture(page, state);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/cash-categories");

  const tree = page.getByRole("tree", { name: "收支分类目录" });
  await expect(tree.locator(":scope > li").last()).toContainText("新建一级分类");
  await tree.getByRole("treeitem").filter({ hasText: /^餐饮/ }).click();
  await expect(page.getByRole("region", { name: "分类编辑" })).toBeVisible();
  expect(await page.locator("body").evaluate((body) => body.scrollWidth <= window.innerWidth)).toBe(true);
});

test("窄屏导航默认收起，并在打开后完整展示账本层级", async ({ page }) => {
  const state = fixture();
  await installFixture(page, state);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/cash-categories");

  const navigation = page.getByRole("navigation", { name: "主要导航" });
  await expect(navigation).toBeHidden();
  await page.getByRole("button", { name: "打开菜单" }).click();
  await expect(navigation).toBeVisible();
  await expect(navigation).toContainText("收支账本");
  await expect(navigation).toContainText("分类管理");
  await expect(navigation).toContainText("投资账本");
  expect(await page.locator("body").evaluate((body) => body.scrollWidth <= window.innerWidth)).toBe(true);
});

test("侧栏点击路由时只保留当前页面并打开对应账本", async ({ page }) => {
  const state = fixture();
  await installFixture(page, state);
  await page.goto("/cash-categories");

  const navigation = page.getByRole("navigation", { name: "主要导航" });
  await navigation.getByRole("link", { name: "收支账本" }).click();
  await expect(page.getByRole("heading", { name: "收支账本", level: 1 })).toBeVisible();
  await expect(navigation.getByRole("link", { name: "收支账本" })).toHaveAttribute("aria-current", "page");
  await expect(navigation.locator("[aria-current='page']")).toHaveCount(1);

  await page.goto("/cash-categories");
  await navigation.getByRole("link", { name: "投资事件" }).click();
  await expect(page.getByRole("heading", { name: "投资事件", level: 1 })).toBeVisible();
  await expect(navigation.getByRole("link", { name: "投资事件" })).toHaveAttribute("aria-current", "page");
  await expect(navigation.getByRole("link", { name: "分类管理" })).not.toHaveAttribute("aria-current");
  await expect(navigation.locator("[aria-current='page']")).toHaveCount(1);
});

test("暗色模式下侧栏导航文字保持可读", async ({ page }, testInfo) => {
  const state = fixture();
  await installFixture(page, state);
  await page.emulateMedia({ colorScheme: "dark" });
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/cash-categories");
  await expect(page.getByRole("navigation", { name: "主要导航" })).toBeVisible();

  const colors = await page.locator(".sidebar strong, .sidebar > nav a").evaluateAll((elements) => elements.map((element) => {
    const color = getComputedStyle(element).color;
    const canvas = document.createElement("canvas");
    const context = canvas.getContext("2d");
    if (!context) return color;
    context.fillStyle = color;
    return context.fillStyle;
  }));
  expect(colors).toHaveLength(7);
  expect(colors.every((color) => {
    const lightness = color.match(/oklch\((\d+(?:\.\d+)?)/)?.[1];
    return lightness !== undefined && Number(lightness) > 0.6;
  })).toBe(true);
  await page.screenshot({ path: testInfo.outputPath("sidebar-dark-1440.png"), fullPage: false });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/cash-categories");
  await page.getByRole("button", { name: "打开菜单" }).click();
  await expect(page.getByRole("navigation", { name: "主要导航" })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("sidebar-dark-390.png"), fullPage: false });
});

test("批量操作栏在当前视口底部保持可见", async ({ page }, testInfo) => {
  const state = fixture();
  await installFixture(page, state);
  await page.goto("/");
  await page.getByLabel("选择咖啡店").check();
  await page.getByLabel("选择午餐").check();

  const toolbar = page.getByRole("toolbar", { name: "批量操作" });
  await expect(toolbar).toBeVisible();
  const box = await toolbar.boundingBox();
  expect(box).not.toBeNull();
  expect(box!.y + box!.height).toBeLessThanOrEqual(page.viewportSize()!.height);
  expect(await toolbar.evaluate((element) => getComputedStyle(element).position)).toBe("fixed");
  await expect(toolbar.getByRole("button", { name: "修改分类" })).toBeInViewport();
  await expect(toolbar.getByRole("button", { name: "取消选择" })).toBeInViewport();
  await page.screenshot({ path: testInfo.outputPath(`batch-toolbar-${page.viewportSize()!.width}.png`), fullPage: false });
});

test("390 px 窄屏批量操作栏保持按钮可达", async ({ page }, testInfo) => {
  const state = fixture();
  await installFixture(page, state);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page.getByLabel("选择咖啡店").check();
  await page.getByLabel("选择午餐").check();

  const toolbar = page.getByRole("toolbar", { name: "批量操作" });
  await expect(toolbar).toBeVisible();
  const box = await toolbar.boundingBox();
  expect(box).not.toBeNull();
  expect(box!.y + box!.height).toBeLessThanOrEqual(844);
  expect(await toolbar.evaluate((element) => getComputedStyle(element).position)).toBe("fixed");
  await expect(toolbar.getByRole("button", { name: "修改分类" })).toBeInViewport();
  await expect(toolbar.getByRole("button", { name: "取消选择" })).toBeInViewport();
  expect(await page.locator("body").evaluate((body) => body.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath(`batch-toolbar-${page.viewportSize()!.width}.png`), fullPage: false });
});

test("表头全选框与行选择框尺寸和横向位置一致", async ({ page }) => {
  const state = fixture();
  await installFixture(page, state);
  await page.goto("/");

  const headerCheckbox = page.getByLabel("选择当前已加载记录");
  const rowCheckbox = page.getByLabel("选择咖啡店");
  const headerBox = await headerCheckbox.boundingBox();
  const rowBox = await rowCheckbox.boundingBox();
  expect(headerBox).not.toBeNull();
  expect(rowBox).not.toBeNull();
  expect(headerBox!.width).toBe(rowBox!.width);
  expect(headerBox!.height).toBe(rowBox!.height);
  expect(Math.abs((headerBox!.x + headerBox!.width / 2) - (rowBox!.x + rowBox!.width / 2))).toBeLessThanOrEqual(1);
});

test("桌面和窄屏选择框视觉状态保持一致", async ({ page }, testInfo) => {
  const state = fixture();
  await installFixture(page, state);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  const rowCheckbox = page.getByLabel("选择咖啡店");
  const rowBox = await rowCheckbox.boundingBox();
  expect(rowBox).not.toBeNull();
  expect(rowBox!.width).toBe(18);
  expect(rowBox!.height).toBe(18);
  expect(await page.locator("body").evaluate((body) => body.scrollWidth <= window.innerWidth)).toBe(true);
  await page.getByLabel("选择咖啡店").check();
  await page.screenshot({ path: testInfo.outputPath("cash-selection-390.png"), fullPage: false });

  await page.setViewportSize({ width: 1440, height: 900 });
  await page.reload();
  await page.getByLabel("选择咖啡店").check();
  await page.screenshot({ path: testInfo.outputPath("cash-selection-1440.png"), fullPage: false });
});
