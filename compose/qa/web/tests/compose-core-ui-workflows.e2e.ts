import { test, expect, type Page } from "@playwright/test";
import { activeSession, clickStableComposeTarget, installComposeApiFixtures, typeIntoComposeInput } from "./compose-fixtures";

const byId = (page: Page, id: string) => page.locator(`[id="${id}"]:visible`).last();
const click = (page: Page, id: string) => clickStableComposeTarget(page, byId(page, id));

test("首笔记账内创建账户后保留金额并选中新账户", async ({ page, baseURL }, info) => {
  const email = `first-booking-${crypto.randomUUID()}@example.invalid`;
  const authentication = await page.request.post(`${baseURL}/api/v1/auth/register`, {
    data: { email, password: "Synthetic test password" },
  });
  expect(authentication.status()).toBe(200);
  const { access_token: token } = await authentication.json();
  const workspaceResponse = await page.request.post(`${baseURL}/api/v1/auth/workspaces`, {
    headers: { Authorization: `Bearer ${token}` }, data: { name: "首笔记账测试" },
  });
  expect(workspaceResponse.status()).toBe(200);
  const { active_workspace_id: workspace } = await workspaceResponse.json();
  await page.addInitScript(value => localStorage.setItem("finance-tracker:session-token", value), token);
  await page.goto(`/w/${workspace}/`);
  await click(page, "ledger.empty-create");
  await typeIntoComposeInput(page, '[id="cash.create.amount"]', "15.80");
  await click(page, "cash.create.new-account");
  await typeIntoComposeInput(page, '[id="cash.import.account-form.name"]', "日常现金");
  await click(page, "cash.import.account-form.save");
  await expect(byId(page, "cash.create.amount")).toHaveText("15.80");
  await expect(byId(page, "cash.create.account_id")).toHaveText("日常现金");
  await page.screenshot({ path: info.outputPath("first-booking-account.png") });
  const write = page.waitForResponse(response => response.request().method() === "POST" &&
    response.url().includes("/api/v1/cash-records"));
  await click(page, "cash.create.save");
  const saved = await write;
  expect(saved.ok(), await saved.text()).toBe(true);
  await expect.poll(async () => {
    const response = await page.request.get(`${baseURL}/api/v1/ledger/snapshot?workspace_id=${workspace}`, {
      headers: { Authorization: `Bearer ${token}`, "X-FT-Workspace-ID": workspace },
    });
    return (await response.json()).facts.cash_transactions[0]?.amount;
  }).toBe("-15.80");
});

test("移除成员在确认前不写入，邀请防重复且显示生成角色", async ({ page }, info) => {
  await page.setViewportSize({ width: 1440, height: 950 });
  const fixtures = await installComposeApiFixtures(page);
  let invitations = 0;
  await page.route(/\/api\/v1\/auth\/invitations(?:\?.*)?$/, async route => {
    invitations++;
    expect(route.request().postDataJSON().role).toBe("editor");
    await new Promise(resolve => setTimeout(resolve, 900));
    expect(new URL(route.request().url()).searchParams.get("workspace_id")).toBe("workspace-1");
    await route.fulfill({ json: { token: "synthetic-invitation", role: "editor", expires_in_days: 7 } });
  });
  await page.goto("/w/workspace-1/workspace-management");
  await click(page, "management.directory.item.members");
  await clickStableComposeTarget(page, page.getByRole("button", { name: "移除", exact: true }).last());
  const modal = byId(page, "workspace-management.member-confirmation.dialog");
  await expect(modal.getByRole("button", { name: "移除", exact: true })).toBeVisible();
  expect(fixtures.calls.filter(call => call.method === "DELETE" && call.path.includes("/auth/members/"))).toHaveLength(0);
  await page.screenshot({ path: info.outputPath("member-removal-confirmation.png") });
  await page.keyboard.press("Escape");
  expect(fixtures.calls.filter(call => call.method === "DELETE" && call.path.includes("/auth/members/"))).toHaveLength(0);

  await expect(modal).toHaveCount(0);
  await clickStableComposeTarget(page, page.getByRole("button", { name: "移除", exact: true }).last());
  const removed = page.waitForResponse(response => response.request().method() === "DELETE" &&
    new URL(response.url()).pathname === "/api/v1/auth/members/member-id");
  await clickStableComposeTarget(page, modal.getByRole("button", { name: "移除", exact: true }));
  const removal = await removed;
  expect(removal.ok()).toBe(true);
  expect(new URL(removal.url()).searchParams.get("workspace_id")).toBe("workspace-1");
  await page.goto("/w/workspace-1/workspace-management");
  await click(page, "management.directory.item.invitations");
  const create = byId(page, "workspace.management.invite");
  await clickStableComposeTarget(page, create);
  await expect(create).toBeDisabled();
  await expect(byId(page, "workspace-management.generated-invitation-role")).toHaveText("此链接授予「可编辑」权限");
  expect(invitations).toBe(1);
  await page.screenshot({ path: info.outputPath("invitation-actual-role.png") });
});

test("已登录的非成员从邀请链接进入，失效时引导索取新链接", async ({ page }) => {
  const fixtures = await installComposeApiFixtures(page, { initialSession: activeSession, inaccessibleWorkspaceIds: ["inviting-workspace"] });
  await page.route("**/api/v1/auth/invitations/expired-at-accept/accept", route => route.fulfill({
    status: 410, json: { error: { code: "invitation_expired" } },
  }));
  await page.goto("/w/inviting-workspace/workspace-management?invite=expired-at-accept");
  const identity = byId(page, "invitation.current-email");
  await expect(identity).toHaveText("使用 owner@example.com 加入");
  expect(fixtures.calls.some(call => call.path.includes("/inviting-workspace/select"))).toBe(false);
  await click(page, "invitation.accept");
  await expect(page.getByText("请邀请人发一条新链接。", { exact: true })).toBeVisible();
  await expect(byId(page, "invitation.accept")).toHaveCount(0);
});

test("投资流水区分空账本与筛选无结果，并能清除条件", async ({ page }) => {
  await installComposeApiFixtures(page);
  await page.route("**/api/v1/investment-events?*", route => route.fulfill({
    json: { data_version: 1, items: [], next_cursor: null, page_size: 50, filters: {} },
  }));
  await page.goto("/w/workspace-1/investment-events");
  await expect(page.getByText("暂无投资流水。", { exact: true })).toBeVisible();
  const search = page.getByRole("textbox", { name: "标的代码" }).last();
  await clickStableComposeTarget(page, search);
  await page.keyboard.type("AAPL");
  await expect(page.getByText("没有符合筛选条件的流水。", { exact: true })).toBeVisible();
  await click(page, "investment-events.clear-filters");
  await expect(page.getByText("暂无投资流水。", { exact: true })).toBeVisible();
});

test("接受邀请时可换账号并继续原链接", async ({ page }) => {
  const fixtures = await installComposeApiFixtures(page, {
    loginSession: { user: { email: "new-member@example.invalid" }, active_workspace_id: null, workspaces: [] },
  });
  await page.goto("/w/inviting-workspace/workspace-management?invite=change-account");
  await expect(byId(page, "invitation.current-email")).toHaveText("使用 owner@example.com 加入");
  await click(page, "invitation.switch-account");
  await typeIntoComposeInput(page, '[id="auth.email"]', "new-member@example.invalid");
  await typeIntoComposeInput(page, '[id="auth.password"]', "Synthetic test password", { masked: true });
  await click(page, "auth.submit");
  await expect(byId(page, "invitation.current-email")).toHaveText("使用 new-member@example.invalid 加入");
  expect(fixtures.calls.some(call => call.path.endsWith("/invitations/change-account/accept"))).toBe(false);
  await click(page, "invitation.accept");
  await expect.poll(() => fixtures.calls.some(call => call.path.endsWith("/invitations/change-account/accept"))).toBe(true);
});

test("从分类更多操作可以一次移到指定同级分类前", async ({ page, baseURL }, info) => {
  await page.setViewportSize({ width: 1440, height: 950 });
  const email = `category-position-${crypto.randomUUID()}@example.invalid`;
  const authentication = await page.request.post(`${baseURL}/api/v1/auth/register`, {
    data: { email, password: "Synthetic test password" },
  });
  expect(authentication.ok()).toBe(true);
  const { access_token: token } = await authentication.json();
  const headers = { Authorization: `Bearer ${token}` };
  const workspaceResponse = await page.request.post(`${baseURL}/api/v1/auth/workspaces`, {
    headers, data: { name: "分类顺序测试" },
  });
  expect(workspaceResponse.ok()).toBe(true);
  const { active_workspace_id: workspace } = await workspaceResponse.json();
  const scopedHeaders = { ...headers, "X-FT-Workspace-ID": workspace };
  const ids: string[] = [];
  let revision = 0;
  for (const name of ["餐饮", "购物", "交通"]) {
    const response = await page.request.post(`${baseURL}/api/v1/cash-categories`, {
      headers: scopedHeaders, data: { name, expected_revision: revision },
    });
    expect(response.ok(), await response.text()).toBe(true);
    const created = await response.json();
    ids.push(created.id);
    revision = created.revision;
  }
  await page.addInitScript(value => localStorage.setItem("finance-tracker:session-token", value), token);
  await page.goto(`/w/${workspace}/workspace-management`);
  await click(page, "management.directory.item.categories");
  await click(page, `cash-category.more.${ids[2]}`);
  await click(page, `cash-category.order.${ids[2]}`);
  await click(page, `cash-category.order-position.before:${ids[0]}`);
  const move = page.waitForResponse(response => response.request().method() === "POST" && response.url().endsWith("/reorder"));
  await click(page, "cash-category.order.confirm");
  const saved = await move;
  expect(saved.ok(), await saved.text()).toBe(true);
  expect(saved.request().postDataJSON()).toMatchObject({ direction: "before_target", target_id: ids[0], expected_revision: revision });
  const directory = await page.request.get(`${baseURL}/api/v1/cash-categories`, { headers: scopedHeaders });
  expect((await directory.json()).items.map((item: { name: string }) => item.name)).toEqual(["交通", "餐饮", "购物"]);
  await page.screenshot({ path: info.outputPath("category-new-order.png") });
});

test("关闭 Web 详情、导入和分类弹窗后主画布保持可交互", async ({ page }, info) => {
  await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/w/workspace-1/");
  await expect(byId(page, "ledger.screen")).toBeVisible();

  const row = page.locator('[id="ledger.record-cash-entry:2001"]:visible').last();
  await clickStableComposeTarget(page, row);
  await click(page, "ledger.record-panel.close");
  await expect(byId(page, "ledger.screen")).toBeVisible();

  await click(page, "ledger.actions-fab");
  await click(page, "ledger.import");
  await click(page, "cash.import.close");
  await expect(byId(page, "ledger.screen")).toBeVisible();

  await clickStableComposeTarget(page, page.locator('[id="ledger.record-checkbox-cash-entry:2001"]:visible').last());
  await click(page, "ledger.batch-category");
  await expect(byId(page, "ledger.category-dialog")).toBeVisible();
  await click(page, "ledger.category.cancel");
  await expect(byId(page, "ledger.screen")).toBeVisible();

  await page.setViewportSize({ width: 390, height: 844 });
  await click(page, "ledger.more-filters");
  await click(page, "ledger.filter.apply");
  await expect(byId(page, "ledger.screen")).toBeVisible();
  await page.screenshot({ path: info.outputPath("web-modal-close-ledger.png") });
});

test("现金金额错误显示、聚焦并在修正后清除", async ({ page }, info) => {
  await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/w/workspace-1/");
  await expect(page.locator('[id="session.connection"]:visible')).toHaveCount(0);
  await click(page, "ledger.actions-fab");
  await click(page, "ledger.add");
  await typeIntoComposeInput(page, '[id="cash.create.amount"]', "abc");
  await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
  const save = byId(page, "cash.create.save");
  await expect(save).toBeEnabled();
  await clickStableComposeTarget(page, save);

  const summary = byId(page, "cash.create.field-errors");
  await expect(summary).toBeVisible();
  await expect(summary).toContainText("金额格式无效");
  await expect.poll(() => page.evaluate(() => {
    let active = document.activeElement;
    while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
    return active instanceof HTMLInputElement && active.classList.contains("compose-backing-field") ? active.value : null;
  })).toBe("abc");

  await typeIntoComposeInput(page, '[id="cash.create.note"]', "只改备注");
  await expect(summary).toContainText("金额格式无效");

  await clickStableComposeTarget(page, byId(page, "cash.create.amount"));
  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.press("Backspace");
  await page.keyboard.insertText("12");
  await expect(byId(page, "cash.create.amount")).toHaveText("12");
  await expect(summary).toHaveCount(0);
  await page.screenshot({ path: info.outputPath("cash-field-error-focus-clear.png") });
});
