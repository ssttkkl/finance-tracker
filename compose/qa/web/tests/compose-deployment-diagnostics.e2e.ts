import { expect, test } from "@playwright/test";
import { captureBrowserDiagnostics, clickStableComposeTarget, typeIntoComposeInput } from "./compose-fixtures";

test("生产 Web 诊断用请求 ID 关联登录响应和本地运行条件", async ({ page, baseURL }, info) => {
  const diagnostics = captureBrowserDiagnostics(page);
  const email = `deployment-qa-${crypto.randomUUID()}@example.invalid`;
  const password = `Deployment-QA-${crypto.randomUUID()}`;
  const registered = await page.request.post(`${baseURL}/api/v1/auth/register`, { data: { email, password } });
  expect(registered.status()).toBe(200);
  const { access_token: token } = await registered.json();
  const created = await page.request.post(`${baseURL}/api/v1/auth/workspaces`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { name: "部署验证账本" },
  });
  expect(created.status()).toBe(200);
  const { active_workspace_id: workspace } = await created.json();

  await page.goto(`/w/${workspace}/`);
  const submit = page.locator('[id="auth.submit"]:visible').last();
  const submitAndReadId = async (status: number) => {
    const pending = page.waitForResponse((response) =>
      new URL(response.url()).pathname === "/api/v1/auth/login" && response.status() === status);
    await clickStableComposeTarget(page, submit);
    const response = await pending;
    const requestId = await response.headerValue("x-request-id");
    expect(requestId).toMatch(/^[0-9a-f]{8}-[0-9a-f-]{27}$/);
    return requestId;
  };

  const rejectedPassword = "Invalid-synthetic-QA-password";
  await typeIntoComposeInput(page, '[id="auth.email"]', email);
  await typeIntoComposeInput(page, '[id="auth.password"]', rejectedPassword);
  const rejectedId = await submitAndReadId(401);
  await expect(page.locator('[id="auth.error"]').last()).toContainText("邮箱或密码不正确");
  await page.screenshot({ path: info.outputPath("login-rejected.png") });

  await typeIntoComposeInput(page, '[id="auth.password"]', password, { replace: true });
  const acceptedId = await submitAndReadId(200);
  await expect(page.locator('[id="ledger.screen"]:visible').last()).toBeVisible({ timeout: 30_000 });
  await page.screenshot({ path: info.outputPath("ledger.png") });

  const raw = await page.evaluate(() => localStorage.getItem("finance-tracker:runtime-diagnostics") ?? "");
  const entries = JSON.parse(raw);
  expect(entries[0]).toMatchObject({
    kind: "startup",
    release: expect.stringMatching(/^[0-9a-f]{40}$/),
    secureContext: true,
    crossOriginIsolated: true,
    sharedArrayBuffer: true,
    webLocks: true,
    opfs: true,
  });
  expect(entries).toEqual(expect.arrayContaining([
    expect.objectContaining({ kind: "auth_response", operation: "login", status: 401, requestId: rejectedId }),
    expect.objectContaining({ kind: "auth_response", operation: "login", status: 200, requestId: acceptedId }),
  ]));
  for (const secret of [email, password, token, rejectedPassword, workspace]) expect(raw).not.toContain(secret);
  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]);
  expect(diagnostics.failedRequests).toEqual([]);
  expect(diagnostics.staticHttpErrors).toEqual([]);
});
