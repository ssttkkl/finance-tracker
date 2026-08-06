import { expect, test, type Page } from "@playwright/test";

const account = { id: 101, name: "日常账户", type: "cash", active: true };
const filter_options = {
  categories: ["餐饮", "日用", "收入"],
  currencies: ["CNY", "USD"],
  economic_types: [{ economic_type: "expense", transfer_subtypes: [] }],
};
const item = (id: string, counterparty: string) => ({ projection_id: `cash:${id}`, occurred_at: "2026-07-03T09:00:00+08:00", account, counterparty, category: "餐饮", amount: "-12.5", currency: "CNY", note: `备注${id}`, source_type: "fixture", source_types: ["fixture"], record_id: `cash-${id}`, economic_type: "expense", transfer_subtype: null, composition: ["payment_mirror"], member_count: 2, accepted_relation_summary: [{ kind: "payment_mirror", subtype: "", count: 1 }], visible: true, hidden_reason: null });

function crossCurrencyTransfer(accountName: string) {
  return {
    ...item("cross-currency", "跨币种内部转账"), account: { ...account, name: accountName }, category: "转账", amount: "0", economic_type: "internal_transfer", transfer_subtype: "ordinary_transfer",
    transfer: {
      from_account: { ...account, name: accountName }, from_amount: "-12345678901234567890.123456", from_currency: "USD",
      to_account: { ...account, id: 102, name: "长期资产配置账户", type: "investment" }, to_amount: "98765432109876543210.654321", to_currency: "CNY",
    },
  };
}

type LedgerItem = ReturnType<typeof item> | ReturnType<typeof crossCurrencyTransfer>;

async function mockLedger(page: Page, failOnce = false, firstCounterparty = "第一笔", firstItem: LedgerItem = item("1", firstCounterparty)) {
  let failed = false;
  await page.route("**/api/v1/**", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith("/accounts")) return route.fulfill({ json: { items: [account] } });
    if (url.pathname.includes("/evidence/")) return route.fulfill({ json: { projection_version: 1, projection: item("1", "第一笔"), root_record: null, members: [], accepted_relations: [], inactive_relation_hints: [], refund_timeline: [] } });
    const cursor = url.searchParams.get("cursor");
    if (cursor === "page-2" && failOnce && !failed) { failed = true; return route.abort(); }
    const data = cursor === "page-2" ? { items: [item("2", "第二笔")], next_cursor: "page-3" } : cursor === "page-3" ? { items: [item("3", "第三笔")], next_cursor: null } : { items: [firstItem], next_cursor: "page-2" };
    return route.fulfill({ json: { projection_version: 1, ...data, page_size: 50, filters: {}, filter_options } });
  });
}

async function openFilters(page: Page) { await page.locator("details.filters > summary").click(); }

test("默认折叠筛选，自动或手动连续加载三批且不重复请求", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockLedger(page); await page.goto("/");
  const filters = page.locator("details.filters");
  await expect(filters).not.toHaveAttribute("open", "");
  await expect(page.getByText("第一笔")).toBeVisible();
  await expect(page.getByText("第三笔")).toBeVisible();
  await expect(page.getByText("已显示全部记录。")).toBeVisible();
  await expect(page.getByRole("button", { name: "上一页" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "下一页" })).toHaveCount(0);
});

test("追加失败保留已加载记录，并通过键盘回退重试", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockLedger(page, true); await page.goto("/");
  const retry = page.getByRole("button", { name: "重试加载更多" });
  await expect(retry).toBeVisible();
  await expect(page.getByText("第一笔")).toBeVisible();
  await retry.focus(); await page.keyboard.press("Enter");
  await expect(page.getByText("第二笔")).toBeVisible();
  await expect(page.getByText("经济类型：消费").first()).toBeVisible();
  await expect(page.getByText("导入渠道：fixture")).toHaveCount(0);
  expect(await page.locator("body").evaluate((body) => body.scrollWidth <= window.innerWidth)).toBeTruthy();
});

test("筛选后从首批重新读取，且所有规定视口无横向溢出", async ({ page }) => {
  await mockLedger(page);
  for (const viewport of [{ width: 320, height: 844 }, { width: 375, height: 844 }, { width: 414, height: 844 }, { width: 768, height: 1024 }, { width: 1024, height: 768 }, { width: 1440, height: 900 }]) {
    await page.setViewportSize(viewport); await page.goto("/"); await expect(page.getByText("第一笔")).toBeVisible();
    await expect(page.getByLabel("关系投影").first()).toBeVisible();
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

  const tableWrap = page.locator(".table-wrap");
  await expect(tableWrap).toBeVisible();
  const layout = await tableWrap.evaluate((wrapper) => {
    const bounds = wrapper.getBoundingClientRect();
    const laterColumns = ["td.source", "td.economic-type", "td.amount", "td.action"]
      .map((selector) => wrapper.querySelector(selector)?.getBoundingClientRect())
      .filter((rect): rect is DOMRect => Boolean(rect));
    const transactionText = wrapper.querySelector<HTMLElement>(".counterparty-primary");
    return {
      scrollWidth: wrapper.scrollWidth,
      clientWidth: wrapper.clientWidth,
      laterColumnsStayInBounds: laterColumns.every((rect) => rect.left >= bounds.left && rect.right <= bounds.right),
      transactionTextIsClipped: transactionText ? getComputedStyle(transactionText).overflow === "hidden" && transactionText.scrollWidth > transactionText.clientWidth : false,
    };
  });

  expect(layout.scrollWidth).toBe(layout.clientWidth);
  expect(layout.laterColumnsStayInBounds).toBe(true);
  expect(layout.transactionTextIsClipped).toBe(true);
});

test("超长账户和跨币种金额在宽屏表格内换行且不覆盖查看操作", async ({ page }) => {
  const longAccountName = "汇丰银行香港特别行政区美元长期资产配置账户".repeat(3);
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockLedger(page, false, "跨币种内部转账", crossCurrencyTransfer(longAccountName));
  await page.goto("/");

  const accountCell = page.locator("td.account").first();
  const amountCell = page.locator("td.amount").first();
  const amountValue = amountCell.locator(".amount-value");
  const evidenceButton = page.getByRole("button", { name: "查看跨币种内部转账的证据详情" });
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
