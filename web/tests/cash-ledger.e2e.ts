import { expect, test, type Page } from "@playwright/test";

const account = { id: 101, name: "日常账户", type: "cash", active: true, currencies: ["CNY"] };
const foodCategory = {
  id: "food",
  parent_id: null,
  name: "餐饮",
  description: null,
  path: [{ id: "food", name: "餐饮" }],
  depth: 1,
  sort_order: 1,
  revision: 1,
};
const dailyCategory = {
  id: "daily",
  parent_id: null,
  name: "日用",
  description: null,
  path: [{ id: "daily", name: "日用" }],
  depth: 1,
  sort_order: 2,
  revision: 1,
};
const incomeCategory = {
  id: "income",
  parent_id: null,
  name: "收入",
  description: null,
  path: [{ id: "income", name: "收入" }],
  depth: 1,
  sort_order: 3,
  revision: 1,
};
const transferCategory = {
  id: "transfer",
  parent_id: null,
  name: "转账",
  description: null,
  path: [{ id: "transfer", name: "转账" }],
  depth: 1,
  sort_order: 4,
  revision: 1,
};
const filter_options = {
  categories: [foodCategory, dailyCategory, incomeCategory, transferCategory],
  currencies: ["CNY", "USD"],
  economic_types: [{ economic_type: "expense", transfer_subtypes: [] }],
};
const item = (id: string, counterparty: string) => ({ projection_id: `cash:${id}`, occurred_at: "2026-07-03T09:00:00+08:00", account, counterparty, category: foodCategory, category_id: foodCategory.id, amount: "-12.5", currency: "CNY", note: `备注${id}`, source_type: "fixture", source_types: ["fixture"], record_id: `cash-${id}`, economic_type: "expense", transfer_subtype: null, composition: ["payment_mirror"], member_count: 2, accepted_relation_summary: [{ kind: "payment_mirror", subtype: "", count: 1 }], visible: true, hidden_reason: null });

function crossCurrencyTransfer(accountName: string) {
  return {
    ...item("cross-currency", "跨币种内部转账"), account: { ...account, name: accountName }, category: transferCategory, category_id: transferCategory.id, amount: "0", economic_type: "internal_transfer", transfer_subtype: "ordinary_transfer",
    transfer: {
      from_account: { ...account, name: accountName }, from_amount: "-12345678901234567890.123456", from_currency: "USD",
      to_account: { ...account, id: 102, name: "长期资产配置账户", type: "investment" }, to_amount: "98765432109876543210.654321", to_currency: "CNY",
    },
  };
}

type LedgerItem = ReturnType<typeof item> | ReturnType<typeof crossCurrencyTransfer>;

const authSession = {
  user: { email: "e2e@example.com" },
  active_workspace_id: "workspace-e2e",
  workspaces: [{ id: "workspace-e2e", name: "E2E 账本", role: "editor" }],
};

async function mockLedger(page: Page, failOnce = false, firstCounterparty = "第一笔", firstItem: LedgerItem = item("1", firstCounterparty)) {
  let failed = false;
  await page.route("**/api/v1/**", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith("/auth/session")) return route.fulfill({ json: authSession });
    if (url.pathname.endsWith("/accounts")) return route.fulfill({ json: { items: [account] } });
    if (url.pathname.includes("/evidence/")) return route.fulfill({ json: { projection_version: 1, projection: item("1", "第一笔"), root_record: null, members: [], accepted_relations: [], inactive_relation_hints: [], refund_timeline: [] } });
    const cursor = url.searchParams.get("cursor");
    if (cursor === "page-2" && failOnce && !failed) { failed = true; return route.abort(); }
    const data = cursor === "page-2" ? { items: [item("2", "第二笔")], next_cursor: "page-3" } : cursor === "page-3" ? { items: [item("3", "第三笔")], next_cursor: null } : { items: [firstItem], next_cursor: "page-2" };
    return route.fulfill({ json: { projection_version: 1, ...data, page_size: 50, filters: {}, filter_options } });
  });
}

async function openFilters(page: Page) { await page.locator("details.filters > summary").click(); }

test("默认折叠筛选，主列表追加三批且保留已加载流水", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockLedger(page); await page.goto("/");
  const filters = page.locator("details.filters");
  await expect(filters).not.toHaveAttribute("open", "");
  await expect(page.getByText("第一笔")).toBeVisible();
  await expect(page.getByText("第二笔")).toBeVisible();
  await expect(page.getByText("第一笔")).toBeVisible();
  await expect(page.getByText("第三笔")).toBeVisible();
  await expect(page.getByText("第二笔")).toBeVisible();
  await expect(page.getByText("第一笔")).toBeVisible();
});

test("追加失败保留当前列表，并通过键盘重试", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockLedger(page, true); await page.goto("/");
  await page.locator(".load-more-control").scrollIntoViewIfNeeded();
  const retry = page.getByRole("button", { name: "重试加载更多" });
  if (await retry.count() === 0) await page.getByRole("button", { name: "加载更多" }).click();
  await expect(retry).toBeVisible();
  await expect(page.getByText("第一笔")).toBeVisible();
  await retry.focus(); await page.keyboard.press("Enter");
  await expect(page.getByText("第二笔")).toBeVisible();
  await expect(page.locator(".cash-row .economic-type").first()).toContainText("消费");
  await expect(page.getByText("导入渠道：fixture")).toHaveCount(0);
  expect(await page.locator("body").evaluate((body) => body.scrollWidth <= window.innerWidth)).toBeTruthy();
});

test("筛选后从首批重新读取，且所有规定视口无横向溢出", async ({ page }) => {
  await mockLedger(page);
  for (const viewport of [{ width: 320, height: 844 }, { width: 375, height: 844 }, { width: 414, height: 844 }, { width: 768, height: 1024 }, { width: 1024, height: 768 }, { width: 1440, height: 900 }]) {
    await page.setViewportSize(viewport); await page.goto("/"); await expect(page.getByText("第一笔")).toBeVisible();
    await expect(page.getByLabel("已合并").first()).toBeVisible();
    await openFilters(page); await page.getByLabel("分类").selectOption("餐饮");
    await expect(page.getByText("第一笔")).toBeVisible();
    expect(await page.locator("body").evaluate((body) => body.scrollWidth <= window.innerWidth)).toBeTruthy();
  }
});

test("超长交易信息不会挤出宽屏表格的后续列", async ({ page }) => {
  const longCounterparty = "PAYWARDTRADINGLTDADD2429WICKHAMSCAYIITORTOLAVGI110VIRGINISLANDSBRITISH".repeat(3);
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockLedger(page, false, longCounterparty);
  await page.goto("/");
  await expect(page.getByText(longCounterparty)).toBeVisible();

  const tableWrap = page.locator(".table-wrap");
  await expect(tableWrap).toBeVisible();
  const layout = await tableWrap.evaluate((wrapper) => {
    const bounds = wrapper.getBoundingClientRect();
    const laterColumns = ["td.category", "td.economic-type", "td.amount", "td.action"]
      .map((selector) => wrapper.querySelector(selector)?.getBoundingClientRect())
      .filter((rect): rect is DOMRect => Boolean(rect));
    const transactionText = wrapper.querySelector<HTMLElement>(".counterparty-primary");
    return {
      scrollWidth: wrapper.scrollWidth,
      clientWidth: wrapper.clientWidth,
      laterColumnsStayInBounds: laterColumns.every((rect) => rect.left >= bounds.left && rect.right <= bounds.right),
      transactionTextStaysInBounds: transactionText ? transactionText.getBoundingClientRect().right <= bounds.right : false,
    };
  });

  expect(layout.scrollWidth).toBe(layout.clientWidth);
  expect(layout.laterColumnsStayInBounds).toBe(true);
  expect(layout.transactionTextStaysInBounds).toBe(true);
});

test("超长账户和跨币种金额在宽屏表格内换行且不覆盖查看操作", async ({ page }) => {
  const longAccountName = "汇丰银行香港特别行政区美元长期资产配置账户".repeat(3);
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockLedger(page, false, "跨币种内部转账", crossCurrencyTransfer(longAccountName));
  await page.goto("/");

  const accountCell = page.locator("td.account").first();
  const amountCell = page.locator("td.amount").first();
  const amountValue = amountCell.locator(".amount-value");
  const evidenceButton = page.getByRole("button", { name: "查看跨币种内部转账的收支详情" });
  await expect(accountCell).toContainText(longAccountName);
  await expect(evidenceButton).toBeVisible();

  const layout = await page.locator(".table-wrap").evaluate((wrapper) => {
    const account = wrapper.querySelector("td.account");
    const amount = wrapper.querySelector("td.amount");
    const amountValue = wrapper.querySelector("td.amount .amount-value");
    const action = wrapper.querySelector("td.action");
    const button = wrapper.querySelector("td.action button");
    if (!account || !amount || !amountValue || !action || !button) throw new Error("宽屏账本测试夹具不完整");
    const amountRect = amountValue.getBoundingClientRect();
    const actionRect = action.getBoundingClientRect();
    const buttonRect = button.getBoundingClientRect();
    return {
      accountTextOverflow: getComputedStyle(account).textOverflow,
      accountWhiteSpace: getComputedStyle(account).whiteSpace,
      amountWhiteSpace: getComputedStyle(amount).whiteSpace,
      accountFitsCell: account.scrollWidth <= account.clientWidth,
      amountStaysBeforeAction: amountRect.right <= actionRect.left,
      evidenceButtonInActionCell: buttonRect.left >= actionRect.left && buttonRect.right <= actionRect.right,
    };
  });

  expect(layout.accountTextOverflow).toBe("clip");
  expect(layout.accountWhiteSpace).toBe("normal");
  expect(layout.amountWhiteSpace).toBe("normal");
  expect(layout.accountFitsCell).toBe(true);
  expect(layout.amountStaysBeforeAction).toBe(true);
  expect(layout.evidenceButtonInActionCell).toBe(true);
});

test("新建流水沿用信息抽屉，不提供收入支出切换并保留零金额类型", async ({ page }) => {
  const options = {
    record_types: [
      { value: "expense", label: "消费", subtypes: [{ value: "not_applicable", label: "—" }] },
      { value: "income", label: "收入", subtypes: [{ value: "not_applicable", label: "—" }] },
    ],
    relation_types: [{ value: "payment_mirror", label: "同笔支付" }, { value: "transfer_pair", label: "个人转账" }],
  };
  let createdBody: Record<string, unknown> | undefined;
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname.endsWith("/auth/session")) return route.fulfill({ json: authSession });
    if (url.pathname.endsWith("/accounts")) return route.fulfill({ json: { items: [{ ...account, currencies: ["CNY", "USD"] }] } });
    if (url.pathname.endsWith("/cash-ledger/options")) return route.fulfill({ json: options });
    if (url.pathname.endsWith("/cash-records") && request.method() === "GET") return route.fulfill({ json: { items: [] } });
    if (url.pathname.endsWith("/cash-records") && request.method() === "POST") {
      createdBody = request.postDataJSON() as Record<string, unknown>;
      return route.fulfill({ status: 201, json: { record: { id: "new-1", ...createdBody, account_id: 101, account_type: "cash", source_type: "manual", record_id: "manual-new-1", counterparty_account: "", counterparty_account_attrs: [] }, relations: [], options } });
    }
    return route.fulfill({ json: { projection_version: 1, items: [item("1", "第一笔")], next_cursor: null, page_size: 50, filters: {}, filter_options } });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "新建流水" }).click();
  const drawer = page.getByRole("dialog", { name: "新建流水" });
  await expect(drawer).toBeVisible();
  await expect(drawer.getByLabel("流水类型")).toBeVisible();
  await expect(drawer.getByLabel("收入支出")).toHaveCount(0);
  await drawer.getByLabel("账户").selectOption({ label: "日常账户" });
  await drawer.getByLabel("币种").selectOption("CNY");
  await drawer.getByRole("textbox", { name: "金额" }).fill("0");
  await drawer.getByLabel("流水类型").selectOption("expense");
  await drawer.getByLabel("交易对方").fill("余额校准");
  await drawer.getByRole("button", { name: "保存" }).click();
  await expect(page.getByRole("dialog", { name: "新建流水" })).toHaveCount(0);
  expect(createdBody).toMatchObject({ amount: "0", currency: "CNY", record_type: "expense" });
});

test("详情切换编辑、维护关联流水并在删除前展示影响确认", async ({ page }) => {
  const options = {
    record_types: [{ value: "expense", label: "消费", subtypes: [{ value: "not_applicable", label: "—" }] }],
    relation_types: [{ value: "payment_mirror", label: "同笔支付" }, { value: "refund_offset", label: "退款冲销" }],
  };
  const root = { id: "1001", occurred_at: "2026-07-03T09:00:00+08:00", account, counterparty: "咖啡店", category: foodCategory, category_id: foodCategory.id, note: "午间消费", amount: "-12.50", currency: "CNY", source_type: "alipay", record_id: "cash-1", source_snapshot: null };
  const related = { ...root, id: "1002", counterparty: "咖啡店", amount: "-12.50", source_type: "wechat", record_id: "cash-2" };
  const detail = { record: { ...root, account_name: account.name, account_id: account.id, account_type: account.type, record_type: "expense", record_subtype: "not_applicable", counterparty_account: "", counterparty_account_attrs: [] }, relations: [{ id: "relation-1", kind: "payment_mirror", label: "同笔支付", subtype: "", status: "accepted", primary_record: { ...root, account_name: account.name, account_id: account.id, account_type: account.type, record_type: "expense", record_subtype: "not_applicable", counterparty_account: "", counterparty_account_attrs: [] }, secondary_record: { ...related, account_name: account.name, account_id: account.id, account_type: account.type, record_type: "expense", record_subtype: "not_applicable", counterparty_account: "", counterparty_account_attrs: [] } }], options };
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname.endsWith("/auth/session")) return route.fulfill({ json: authSession });
    if (url.pathname.endsWith("/accounts")) return route.fulfill({ json: { items: [{ ...account, currencies: ["CNY"] }] } });
    if (url.pathname.includes("/evidence/")) return route.fulfill({ json: { projection_version: 1, projection: { ...item("1", "咖啡店"), composition: ["payment_mirror"], member_count: 2, source_types: ["alipay", "wechat"] }, root_record: root, members: [{ ...root, roles: ["root"] }, { ...related, roles: ["mirror"] }], accepted_relations: [detail.relations[0]], inactive_relation_hints: [], refund_timeline: [] } });
    if (url.pathname.endsWith("/cash-ledger/options")) return route.fulfill({ json: options });
    if (url.pathname.endsWith("/cash-records") && request.method() === "GET") return route.fulfill({ json: url.searchParams.has("exclude_id") ? { items: [detail.record] } : detail });
    if (url.pathname.endsWith("/cash-records/1001") && request.method() === "GET") return route.fulfill({ json: detail });
    if (url.pathname.endsWith("/cash-relations/relation-1") && request.method() === "PUT") return route.fulfill({ json: { ...detail, relations: [{ ...detail.relations[0], kind: "refund_offset", label: "退款冲销" }] } });
    if (url.pathname.endsWith("/cash-records/1001") && request.method() === "DELETE") return route.fulfill({ json: { deleted: true, related_count: 1 } });
    return route.fulfill({ json: { projection_version: 1, items: [item("1", "咖啡店")], next_cursor: null, page_size: 50, filters: {}, filter_options } });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "查看咖啡店的收支详情" }).click();
  const evidence = page.getByRole("dialog", { name: "收支详情" });
  await expect(evidence).toContainText("已合并");
  await evidence.getByRole("button", { name: "编辑", exact: true }).click();
  const drawer = page.getByRole("dialog", { name: "编辑收支详情" });
  await expect(drawer).toBeVisible();
  await expect(drawer).toContainText("同笔支付");
  await drawer.getByRole("button", { name: "更改类型" }).click();
  const relation = drawer.locator("li").first();
  await relation.getByLabel("更改关联类型").selectOption("refund_offset");
  await relation.getByRole("button", { name: "保存" }).click();
  await expect(page.getByRole("dialog", { name: "收支详情" })).toBeVisible();
  await page.getByRole("dialog", { name: "收支详情" }).getByRole("button", { name: "编辑", exact: true }).click();
  await page.getByRole("dialog", { name: "编辑收支详情" }).getByRole("button", { name: "删除流水" }).click();
  const confirmation = page.getByRole("alertdialog", { name: "删除流水确认" });
  await expect(confirmation).toContainText("已添加关联流水");
  await confirmation.getByRole("button", { name: "只删除当前流水并解散关联" }).click();
  await expect(page.getByRole("dialog", { name: "收支详情" })).toHaveCount(0);
});

test("查看抽屉原位切换编辑，不重复读取当前流水并立即显示表单", async ({ page }) => {
  const options = {
    record_types: [{ value: "expense", label: "消费", subtypes: [{ value: "not_applicable", label: "—" }] }],
    relation_types: [{ value: "payment_mirror", label: "同笔支付" }],
  };
  const root = { id: "1001", occurred_at: "2026-07-03T09:00:00+08:00", account, counterparty: "咖啡店", category: foodCategory, category_id: foodCategory.id, note: "午间消费", amount: "-12.50", currency: "CNY", source_type: "alipay", record_id: "cash-1", source_snapshot: null };
  const record = { ...root, account_name: account.name, account_id: account.id, account_type: account.type, record_type: "expense", record_subtype: "not_applicable", counterparty_account: "", counterparty_account_attrs: [] };
  const detail = { record, relations: [], options };
  let detailRequests = 0;
  let optionsRequests = 0;

  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname.endsWith("/auth/session")) return route.fulfill({ json: authSession });
    if (url.pathname.endsWith("/accounts")) return route.fulfill({ json: { items: [{ ...account, currencies: ["CNY"] }] } });
    if (url.pathname.includes("/evidence/")) return route.fulfill({ json: { projection_version: 1, projection: { ...item("1", "咖啡店"), member_count: 1, composition: [], source_types: ["alipay"] }, root_record: root, members: [{ ...root, roles: ["root"] }], accepted_relations: [], inactive_relation_hints: [], refund_timeline: [] } });
    if (url.pathname.endsWith("/cash-ledger/options")) { optionsRequests += 1; return route.fulfill({ json: options }); }
    if (url.pathname.endsWith("/cash-records/1001") && request.method() === "GET") { detailRequests += 1; return route.fulfill({ json: detail }); }
    if (url.pathname.endsWith("/cash-records") && request.method() === "GET") return route.fulfill({ json: { items: [] } });
    return route.fulfill({ json: { projection_version: 1, items: [item("1", "咖啡店")], next_cursor: null, page_size: 50, filters: {}, filter_options } });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "查看咖啡店的收支详情" }).click();
  const evidence = page.getByRole("dialog", { name: "收支详情" });
  await evidence.getByRole("button", { name: "编辑", exact: true }).click();
  const dialog = page.locator(".evidence-panel");
  await expect(dialog).toHaveAttribute("aria-label", "编辑收支详情");
  await dialog.evaluate((node) => node.setAttribute("data-dialog-identity", "cash-record"));
  await expect(page.locator('[data-dialog-identity="cash-record"]')).toHaveAttribute("aria-label", "编辑收支详情");
  await expect(page.getByRole("dialog", { name: "编辑收支详情" }).getByLabel("交易对方")).toHaveValue("咖啡店");
  await expect(page.getByRole("dialog", { name: "新建流水" })).toHaveCount(0);
  expect(detailRequests).toBe(0);
  const editor = page.getByRole("dialog", { name: "编辑收支详情" });
  expect(optionsRequests).toBe(1);
  await editor.getByRole("button", { name: "返回", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "收支详情" })).toBeVisible();
  await page.getByRole("button", { name: "编辑", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "编辑收支详情" })).toBeVisible();
  expect(optionsRequests).toBe(1);
});

test("关联流水从统一入口搜索已有流水并直接确认", async ({ page }) => {
  const options = {
    record_types: [{ value: "expense", label: "消费", subtypes: [{ value: "not_applicable", label: "—" }] }],
    relation_types: [{ value: "payment_mirror", label: "同笔支付" }],
  };
  const root = { id: "1001", occurred_at: "2026-07-03T09:00:00+08:00", account, counterparty: "咖啡店", category: foodCategory, category_id: foodCategory.id, note: "午间消费", amount: "-12.50", currency: "CNY", source_type: "alipay", record_id: "cash-1", source_snapshot: null };
  const candidate = (id: string, counterparty: string) => ({ ...root, id, counterparty, account_name: account.name, account_id: account.id, account_type: account.type, record_type: "income", record_subtype: "not_applicable", counterparty_account: "", counterparty_account_attrs: [] });
  const relationBodies: Record<string, unknown>[] = [];
  const candidateRequests: string[] = [];

  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname.endsWith("/auth/session")) return route.fulfill({ json: authSession });
    if (url.pathname.endsWith("/accounts")) return route.fulfill({ json: { items: [{ ...account, currencies: ["CNY"] }] } });
    if (url.pathname.includes("/evidence/")) return route.fulfill({ json: { projection_version: 1, projection: { ...item("1", "咖啡店"), member_count: 1, composition: [], source_types: ["alipay"] }, root_record: root, members: [{ ...root, roles: ["root"] }], accepted_relations: [], inactive_relation_hints: [], refund_timeline: [] } });
    if (url.pathname.endsWith("/cash-ledger/options")) return route.fulfill({ json: options });
    if (url.pathname.endsWith("/cash-records") && request.method() === "GET") {
      candidateRequests.push(request.url());
      return route.fulfill({ json: url.searchParams.has("cursor")
        ? { items: [candidate("2002", "工资转入二")], next_cursor: null }
        : { items: [candidate("2001", "工资转入一")], next_cursor: "candidate-page-2" } });
    }
    if (url.pathname.endsWith("/cash-relations") && request.method() === "POST") {
      relationBodies.push(request.postDataJSON() as Record<string, unknown>);
      return route.fulfill({ json: { record: candidate("1001", "咖啡店"), relations: [], options } });
    }
    return route.fulfill({ json: { projection_version: 1, items: [item("1", "咖啡店")], next_cursor: null, page_size: 50, filters: {}, filter_options } });
  });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await expect(page.getByRole("button", { name: "查看咖啡店的收支详情" })).toBeHidden();
  await page.locator(".cash-row", { hasText: "咖啡店" }).click();
  const detail = page.getByRole("dialog", { name: "收支详情" });
  expect(candidateRequests).toHaveLength(0);

  await detail.getByRole("button", { name: "添加关联" }).click();

  const editor = page.getByRole("dialog", { name: "编辑收支详情" });
  await expect(editor.getByRole("searchbox", { name: "搜索流水" })).toBeVisible();
  await expect.poll(() => candidateRequests.length).toBe(1);
  expect(new URL(candidateRequests[0]).searchParams.get("limit")).toBe("20");
  expect(new URL(candidateRequests[0]).searchParams.get("exclude_id")).toBe("1001");
  expect(new URL(candidateRequests[0]).searchParams.get("date_from")).toBe("2026-06-30");
  expect(new URL(candidateRequests[0]).searchParams.get("date_to")).toBe("2026-07-06");
  await expect(editor.getByLabel("保存方式")).toHaveCount(0);
  await expect(editor.getByText("稍后确认")).toHaveCount(0);
  await expect(editor.getByRole("button", { name: "新建流水" })).toHaveCount(0);
  await expect(editor.locator('input[type="radio"]')).toHaveCount(0);
  const expectedLocalTime = await page.evaluate((value) => new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value)), candidate("2001", "工资转入一").occurred_at);
  await expect(editor.getByRole("radio", { name: /工资转入一/ })).toContainText(expectedLocalTime);
  const search = editor.getByRole("searchbox", { name: "搜索流水" });
  await search.fill("工资");
  await expect.poll(() => candidateRequests.some((value) => new URL(value).searchParams.get("query") === "工资")).toBe(true);
  await editor.getByRole("button", { name: "下一页" }).click();
  await expect(editor.getByRole("radio", { name: /工资转入二/ })).toBeVisible();
  await editor.getByRole("radio", { name: /工资转入二/ }).click();
  await editor.getByRole("button", { name: "添加关联" }).click();

  await expect.poll(() => relationBodies).toEqual([{
    primary_fact_id: "1001",
    secondary_fact_id: "2002",
    kind: "payment_mirror",
    status: "accepted",
  }]);
  expect(await page.locator("body").evaluate((body) => body.scrollWidth <= window.innerWidth)).toBeTruthy();
});

test("独立导入处理页面扫描账户并完成四步确认", async ({ page }) => {
  let committed = false;
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname.endsWith("/auth/session")) return route.fulfill({ json: authSession });
    if (url.pathname.endsWith("/accounts")) return route.fulfill({ json: { items: [account] } });
    if (url.pathname.endsWith("/cash-import/scan")) return route.fulfill({ json: { contract: "cash-account-mapping-v1", channel: "icbc-asia", channel_label: "工银亚洲", file: { name: "statement.csv", digest: "digest-1" }, digest: "digest-1", accounts: [account], groups: [{ group_id: "group-1", display_name: "工银亚洲账户", masked_evidence: "账户尾号：1234", currencies: ["CNY"], row_count: 1, suggestion: { account_id: account.id, account, missing_currencies: [], mapping_revision: null } }] } });
    if (url.pathname.endsWith("/cash-import/preview")) return route.fulfill({ json: { channel: "icbc-asia", channel_label: "工银亚洲", file: { name: "statement.csv", digest: "digest-1" }, columns: ["occurred_at", "amount", "currency", "account_name", "counterparty", "counterparty_account", "record_type", "record_subtype", "category", "note", "channel", "status"], items: [{ record_id: "row-1", occurred_at: "2026-07-03T09:00", counterparty: "咖啡店", counterparty_account: "", amount: "-12.50", currency: "CNY", account_name: "日常账户", record_type: "consumption", record_subtype: "not_applicable", category: "餐饮", note: "", channel: "icbc-asia", status: "new", message: "" }], summary: { total: 1, new: 1, existing: 0, unsupported: 0 }, relations: [] } });
    if (url.pathname.endsWith("/cash-import/commit")) { committed = true; return route.fulfill({ json: { message: "ok", new_rows: 1, updated_rows: 0 } }); }
    return route.fulfill({ json: { projection_version: 1, items: [item("1", "第一笔")], next_cursor: null, page_size: 50, filters: {}, filter_options } });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "导入账单" }).click();
  await expect(page).toHaveURL(/\/cash-import$/);
  await page.locator('input[type="file"]').setInputFiles({ name: "statement.csv", mimeType: "text/csv", buffer: Buffer.from("fixture") });
  await expect(page.getByRole("heading", { name: "映射账户" })).toBeVisible();
  await page.screenshot({ path: "/tmp/cash-import-production-1440.png", fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: "/tmp/cash-import-production-390.png", fullPage: true });
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.getByRole("button", { name: "确认映射", exact: true }).click();
  await expect(page.getByRole("heading", { name: "核对流水" })).toBeVisible();
  await expect(page.getByText("全部", { exact: true })).toBeVisible();
  const previewActions = page.locator(".import-preview-stage .stage-actions-top");
  await expect(previewActions).toBeVisible();
  expect((await previewActions.boundingBox())!.y).toBeLessThan((await page.locator(".standard-table-wrap").boundingBox())!.y);
  await expect(page.locator(".import-preview-stage .stage-actions")).toHaveCount(0);
  await page.getByRole("button", { name: "下一步", exact: true }).click();
  const relationActions = page.locator("[aria-labelledby=import-relations-heading] .stage-actions-top");
  await expect(relationActions).toBeVisible();
  expect((await relationActions.boundingBox())!.y).toBeLessThan((await page.locator(".import-empty-state").boundingBox())!.y);
  await expect(page.locator("[aria-labelledby=import-relations-heading] .stage-actions")).toHaveCount(0);
  await page.getByRole("button", { name: "确认导入" }).click();
  await expect.poll(() => committed).toBe(true);
  await expect(page.getByRole("heading", { name: "导入完成" })).toBeVisible();
});

test("导入处理页面在四个目标宽度不产生页面级横向滚动", async ({ page }) => {
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname.endsWith("/auth/session")) return route.fulfill({ json: authSession });
    if (url.pathname.endsWith("/accounts")) return route.fulfill({ json: { items: [account] } });
    if (url.pathname.endsWith("/cash-import/scan")) return route.fulfill({ json: { contract: "cash-account-mapping-v1", channel: "icbc-asia", channel_label: "工银亚洲", file: { name: "statement.csv", digest: "digest-1" }, digest: "digest-1", accounts: [account], groups: [{ group_id: "group-1", display_name: "工银亚洲账户", masked_evidence: "账户尾号：1234", currencies: ["CNY"], row_count: 1, suggestion: { account_id: account.id, account, missing_currencies: [], mapping_revision: null } }] } });
    return route.fulfill({ json: { projection_version: 1, items: [], next_cursor: null, page_size: 50, filters: {}, filter_options } });
  });
  for (const width of [320, 375, 414, 768]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/cash-import");
    await page.locator('input[type="file"]').setInputFiles({ name: "statement.csv", mimeType: "text/csv", buffer: Buffer.from("fixture") });
    await expect(page.getByRole("heading", { name: "映射账户" })).toBeVisible();
    expect(await page.locator("body").evaluate((body) => body.scrollWidth <= window.innerWidth)).toBeTruthy();
  }
});

test.describe("浏览器本地时区", () => {
  test.use({ timezoneId: "America/Los_Angeles" });

  test("列表请求发送浏览器 IANA 时区", async ({ page }) => {
    const requests: string[] = [];
    page.on("request", (request) => requests.push(request.url()));
    await mockLedger(page);
    await page.goto("/");
    await expect(page.getByText("第一笔")).toBeVisible();

    const projectionRequest = requests.find((url) => url.includes("/cash-projections"));
    expect(projectionRequest).toBeDefined();
    expect(new URL(projectionRequest!).searchParams.get("timezone")).toBe("America/Los_Angeles");
  });
});
