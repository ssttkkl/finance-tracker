import type { Page } from "@playwright/test";

export type ComposeSession = {
  user: { email: string };
  active_workspace_id: string | null;
  workspaces: Array<{ id: string; name: string; role: "admin" | "editor" | "viewer" }>;
};

type FixtureOptions = {
  seedToken?: boolean;
  initialSession?: ComposeSession | null;
  loginSession?: ComposeSession;
  loginError?: boolean;
  inaccessibleWorkspaceIds?: string[];
  workspaceSelectDelayMs?: number;
  cashPageFailures?: number;
  cashPageEmpty?: boolean;
  cashPaginatedPageCount?: number;
  cashCrossMonthBoundary?: boolean;
  cashAppendFailures?: number;
  cashTransferSubtypes?: string[];
};

export type ComposeApiFixtures = {
  calls: Array<{ method: string; path: string; body: string | null }>;
  state: {
    session: ComposeSession | null;
    categoryItems: Array<Record<string, unknown>>;
    workspaceName: string;
    cashProjectionPageIds: string[][];
    recordWrites: Array<Record<string, unknown>>;
    classificationWrites: Array<Record<string, unknown>>;
    projectionDeletes: Array<Record<string, unknown>>;
  };
};

export type BrowserDiagnostics = {
  consoleErrors: string[];
  diagnosticLogs: string[];
  failedRequests: string[];
  expectedBrowserMessages: string[];
  expectedApiErrors: string[];
  staticHttpErrors: string[];
  wasmContentTypes: string[];
  settle(): Promise<void>;
};

export function captureBrowserDiagnostics(page: Page, options: { expectedErrorStatuses?: number[] } = {}): BrowserDiagnostics {
  const consoleErrors: string[] = [];
  const diagnosticLogs: string[] = [];
  const failedRequests: string[] = [];
  const expectedBrowserMessages: string[] = [];
  const expectedApiErrors: string[] = [];
  const staticHttpErrors: string[] = [];
  const wasmContentTypes: string[] = [];
  const pendingChecks: Promise<void>[] = [];
  const expectedErrorStatuses = new Set([401, 503, ...(options.expectedErrorStatuses ?? [])]);
  const wasmMemoryDeprecation = "Accessing `memory` via `wasmExports` is deprecated. Use `kotlin.wasm.unsafe.wasmMemory` or update dependencies. Read more: https://kotl.in/vr3szr";
  const recordMessage = (message: string) => {
    if (message.startsWith("[Finance Tracker diagnostic] ")) {
      diagnosticLogs.push(message);
      return;
    }
    const failedResourceStatus = /Failed to load resource: the server responded with a status of (\d+) \(/.exec(message);
    if (/WebGL: INVALID_ENUM: getParameter: invalid parameter name, WEBGL_debug_renderer_info not enabled/.test(message)
      || message === wasmMemoryDeprecation) {
      expectedBrowserMessages.push(message);
    } else if (failedResourceStatus && expectedErrorStatuses.has(Number(failedResourceStatus[1]))) {
      expectedBrowserMessages.push(message);
    } else {
      consoleErrors.push(message);
    }
  };

  page.on("console", (message) => {
    if (message.type() === "error") recordMessage(message.text());
  });
  page.on("pageerror", (error) => recordMessage(error.message));
  page.on("requestfailed", (request) => {
    const error = request.failure()?.errorText ?? "unknown request failure";
    if (/aborted|cancelled|canceled/i.test(error)) return;
    const failure = `${request.url()}: ${error}`;
    failedRequests.push(failure);
  });
  page.on("response", (response) => {
    const pathname = new URL(response.url()).pathname;
    if (pathname.startsWith("/api/")) {
      if (response.status() >= 400) expectedApiErrors.push(`${response.status()} ${pathname}`);
      return;
    }
    if (pathname.endsWith(".wasm")) {
      pendingChecks.push(response.headerValue("content-type").then((value) => {
        wasmContentTypes.push(value ?? "missing");
      }));
    } else if (response.status() >= 400) {
      staticHttpErrors.push(`${response.status()} ${response.url()}`);
    }
  });

  return {
    consoleErrors,
    diagnosticLogs,
    failedRequests,
    expectedBrowserMessages,
    expectedApiErrors,
    staticHttpErrors,
    wasmContentTypes,
    settle: async () => { await Promise.all(pendingChecks); },
  };
}

export const activeSession: ComposeSession = {
  user: { email: "owner@example.com" },
  active_workspace_id: "workspace-1",
  workspaces: [{ id: "workspace-1", name: "家庭账本", role: "admin" }],
};

export const noWorkspaceSession: ComposeSession = {
  user: { email: "owner@example.com" },
  active_workspace_id: null,
  workspaces: [],
};

export const cashAccount = { id: 1, name: "日常账户", type: "cash", active: true, currencies: ["CNY"] };
export const investmentAccount = { id: 9, name: "证券账户", type: "investment", active: true, currencies: ["USD"] };

export async function typeIntoComposeInput(
  page: Page,
  selector: string,
  value: string,
  options: { replace?: boolean } = {},
): Promise<void> {
  const input = page.locator(`${selector}:visible`).last();
  await input.scrollIntoViewIfNeeded();
  if (await input.evaluate((element: HTMLElement) => element.isContentEditable)) {
    await input.click({ force: true });
    await input.focus();
    if (options.replace) {
      await page.keyboard.press("ControlOrMeta+A");
      await page.keyboard.press("Backspace");
    }
    if (value) await page.keyboard.type(value, { delay: 100 });
  } else {
    let box = await input.boundingBox();
    for (let attempt = 0; !box && attempt < 10; attempt += 1) {
      await page.waitForTimeout(100);
      box = await input.boundingBox();
    }
    if (!box) throw new Error(`Missing Compose input bounds: ${selector}`);
    await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
    if (options.replace) {
      await page.keyboard.press("ControlOrMeta+A");
      await page.keyboard.press("Backspace");
    }
    if (value) await page.keyboard.type(value, { delay: 100 });
    await page.waitForTimeout(200);
  }
}

const categoryItems: Array<Record<string, unknown>> = [
  { id: "food", parent_id: null, name: "餐饮", path: [], depth: 1, sort_order: 0, revision: 1 },
  {
    id: "coffee",
    parent_id: "food",
    name: "咖啡",
    path: [{ id: "food", name: "餐饮" }],
    depth: 2,
    sort_order: 0,
    revision: 1,
  },
];

const cashProjection = {
  projection_id: "projection-1",
  occurred_at: "2026-09-24T10:30:00+08:00",
  account: cashAccount,
  counterparty: "咖啡店",
  category: categoryItems[1],
  note: "拿铁",
  amount: "-28.50",
  currency: "CNY",
  economic_type: "expense",
  member_count: 1,
  accepted_relation_summary: [],
  source_types: [],
  record_id: "record-1",
  transaction_id: "record-1",
  visible: true,
};

const cashRecord = {
  id: "record-1",
  occurred_at: "2026-09-24T10:30:00+08:00",
  amount: "28.50",
  currency: "CNY",
  counterparty: "咖啡店",
  counterparty_account: "",
  note: "拿铁",
  category: categoryItems[1],
  category_id: "coffee",
  record_type: "consumption",
  record_subtype: "not_applicable",
  account_name: "日常账户",
  account_id: 1,
  account_type: "cash",
};

const investmentEvent = {
  event_id: "event-1",
  occurred_at: "2026-09-24T10:30:00+08:00",
  account: investmentAccount,
  record_type: "trade",
  record_subtype: "buy",
  currency: "USD",
  note: "买入苹果",
  from_asset: { ticker: "USD", amount: "220" },
  to_asset: { ticker: "AAPL", amount: "2" },
  commission: { amount: "1", asset: "USD" },
  record_id: "investment-record-1",
  relations: [],
};

const portfolio = {
  accounts: [{
    name: investmentAccount.name,
    currency: "USD",
    positions: [{
      ticker: "AAPL",
      display_name: "Apple",
      shares: "2",
      total_cost: "200",
      cost_currency: "USD",
      current_price: "110",
      market_value: "220",
      profit: "20",
      quote_status: "complete",
      quote_currency: "USD",
      quote_observed_at: "2026-09-24T10:30:00+08:00",
      quote_session: "regular",
      usd_market_value: "220",
      period_profit: "8",
      period_profit_rate: "0.04",
    }],
  }],
  total_market_value: "220",
  total_profit: "20",
  total_profit_rate: "0.10",
  period_profit: "8",
  period_profit_rate: "0.04",
};

const workspaceMembers = (name: string) => ({
  workspace: { id: "workspace-1", name },
  members: [
    { user_id: "owner-id", email: "owner@example.com", role: "admin", is_self: true },
    { user_id: "member-id", email: "member@example.com", role: "editor", is_self: false },
  ],
});

export async function installComposeApiFixtures(
  page: Page,
  options: FixtureOptions = {},
): Promise<ComposeApiFixtures> {
  const initialSession = options.initialSession === undefined ? activeSession : options.initialSession;
  const seedToken = options.seedToken ?? initialSession !== null;
  const calls: ComposeApiFixtures["calls"] = [];
  const writes: Array<Record<string, unknown>> = [];
  const classificationWrites: Array<Record<string, unknown>> = [];
  const projectionDeletes: Array<Record<string, unknown>> = [];
  let cashPageRequests = 0;
  let cashAppendFailuresRemaining = 0;
  const cashProjectionPageIds: string[][] = [];
  const state = {
    session: initialSession,
    categoryItems: categoryItems.map((item) => ({ ...item })),
    workspaceName: initialSession?.workspaces.find((item) => item.id === initialSession.active_workspace_id)?.name ?? "家庭账本",
    cashProjectionPageIds,
    recordWrites: writes,
    classificationWrites,
    projectionDeletes,
  };

  await page.addInitScript((hasToken) => {
    if (hasToken) window.localStorage.setItem("finance-tracker:session-token", "compose-e2e-token");
  }, seedToken);

  await page.route(/\/api\/v1\//, async (route) => {
    const request = route.request();
    const requestUrl = new URL(request.url());
    const { pathname, searchParams } = requestUrl;
    const method = request.method();
    const body = request.postData();
    calls.push({ method, path: `${pathname}${requestUrl.search}`, body });
    const json = (value: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(value) });

    if (pathname === "/api/v1/auth/session" && method === "GET") {
      return state.session ? json(state.session) : json({ error: { code: "authentication_required" } }, 401);
    }
    if (pathname === "/api/v1/auth/logout" && method === "POST") {
      state.session = null;
      return json({ ok: true });
    }
    if (["/api/v1/auth/login", "/api/v1/auth/register"].includes(pathname) && method === "POST") {
      if (options.loginError) return json({ error: { code: "invalid_credentials" } }, 401);
      const session = options.loginSession ?? activeSession;
      state.session = session;
      return json({ access_token: "compose-e2e-token", ...session });
    }
    if (pathname === "/api/v1/auth/workspaces" && method === "POST") {
      const workspaceName = String(JSON.parse(body ?? "{}").name ?? "新工作区");
      state.workspaceName = workspaceName;
      state.session = {
        user: { email: "owner@example.com" },
        active_workspace_id: "workspace-created",
        workspaces: [{ id: "workspace-created", name: workspaceName, role: "admin" }],
      };
      return json(state.session);
    }
    if (pathname.endsWith("/select") && method === "POST") {
      const workspaceId = pathname.split("/").at(-2) ?? "workspace-1";
      if (options.workspaceSelectDelayMs) {
        await new Promise((resolve) => setTimeout(resolve, options.workspaceSelectDelayMs));
      }
      if (options.inaccessibleWorkspaceIds?.includes(workspaceId)) {
        return json({ error: { code: "workspace_access_denied" } }, 403);
      }
      const currentSession = state.session ?? activeSession;
      const selectedWorkspace = currentSession.workspaces.some((workspace) => workspace.id === workspaceId);
      state.session = {
        ...currentSession,
        active_workspace_id: workspaceId,
        workspaces: selectedWorkspace
          ? currentSession.workspaces
          : [...currentSession.workspaces, {
            id: workspaceId,
            name: state.workspaceName,
            role: currentSession.user.email.startsWith("viewer") ? "viewer" : "admin",
          }],
      };
      return json(state.session);
    }
    if (pathname === "/api/v1/auth/workspace" && method === "GET") return json(workspaceMembers(state.workspaceName));
    if (pathname === "/api/v1/auth/workspace" && method === "PUT") {
      state.workspaceName = String(JSON.parse(body ?? "{}").name ?? state.workspaceName);
      if (state.session) state.session = { ...state.session, workspaces: state.session.workspaces.map((item) => ({ ...item, name: state.workspaceName })) };
      return json(state.session ?? activeSession);
    }
    if (pathname === "/api/v1/auth/workspace" && method === "DELETE") {
      state.session = noWorkspaceSession;
      return json(noWorkspaceSession);
    }
    if (pathname === "/api/v1/auth/invitations" && method === "POST") return json({ token: "invite-e2e-token" });
    if (/^\/api\/v1\/auth\/invitations\/[^/]+$/.test(pathname) && method === "GET") {
      return json({ workspace: { name: "共享账本" }, role: "editor", valid: true });
    }
    if (/^\/api\/v1\/auth\/invitations\/[^/]+\/accept$/.test(pathname) && method === "POST") {
      state.session = activeSession;
      return json(activeSession);
    }
    if (/^\/api\/v1\/auth\/members\/[^/]+$/.test(pathname) && method === "PUT") return json({});
    if (/^\/api\/v1\/auth\/members\/[^/]+$/.test(pathname) && method === "DELETE") return json({ ok: true });

    if (pathname === "/api/v1/accounts" && method === "GET") {
      return json({ items: searchParams.get("view") === "investment" ? [investmentAccount] : [cashAccount] });
    }
    if (pathname === "/api/v1/cash-ledger/options" && method === "GET") {
      return json({
        record_types: [
          { value: "consumption", label: "支出", subtypes: [{ value: "not_applicable", label: "普通支出" }] },
          { value: "income", label: "收入", subtypes: [] },
        ],
        relation_types: [
          { value: "payment_mirror", label: "同笔支付" },
          { value: "refund_offset", label: "退款冲销" },
        ],
      });
    }
    if (pathname === "/api/v1/cash-categories" && method === "GET") return json({ revision: 2, items: state.categoryItems });
    if (pathname === "/api/v1/cash-categories" && method === "POST") {
      const values = JSON.parse(body ?? "{}");
      const parent = state.categoryItems.find((item) => item.id === values.parent_id);
      const item = {
        id: "category-created",
        parent_id: values.parent_id ?? null,
        name: values.name,
        description: values.description,
        path: parent ? [...((parent.path as unknown[]) ?? []), { id: parent.id, name: parent.name }] : [],
        depth: parent ? Number(parent.depth) + 1 : 1,
        sort_order: 2,
        revision: 3,
      };
      state.categoryItems = [...state.categoryItems, item];
      return json(item);
    }
    if (/^\/api\/v1\/cash-categories\/[^/]+\/deletion-impact$/.test(pathname) && method === "GET") {
      return json({ category_id: pathname.split("/").at(-2), revision: 3, category_revision: 2, child_count: 0, direct_usage_count: 0 });
    }
    if (/^\/api\/v1\/cash-categories\/[^/]+$/.test(pathname) && method === "PATCH") return json({});
    if (/^\/api\/v1\/cash-categories\/[^/]+\/reorder$/.test(pathname) && method === "POST") return json({});
    if (/^\/api\/v1\/cash-categories\/[^/]+$/.test(pathname) && method === "DELETE") {
      return json({ category_id: pathname.split("/").at(-1), cleared_transaction_count: 0, revision: 4 });
    }

    if (pathname === "/api/v1/cash-projections" && method === "GET") {
      cashPageRequests++;
      if (cashPageRequests <= (options.cashPageFailures ?? 0)) {
        return json({ error: { code: "temporarily_unavailable" } }, 503);
      }
      if (options.cashPaginatedPageCount) {
        const cursor = searchParams.get("cursor");
        const pageNumber = cursor ? Number(cursor.replace("cash-scroll-page-", "")) : 1;
        if (pageNumber === 1) cashAppendFailuresRemaining = options.cashAppendFailures ?? 0;
        if (pageNumber > 1 && cashAppendFailuresRemaining > 0) {
          cashAppendFailuresRemaining--;
          return json({ error: { code: "temporarily_unavailable" } }, 503);
        }
        const economicType = searchParams.get("economic_type") === "income" ? "income" : "expense";
        const items = Array.from({ length: 24 }, (_, index) => ({
          ...cashProjection,
          projection_id: pageNumber === 1 && index === 1
            ? "cash-scroll-1-1"
            : pageNumber > 1 && index === 0
              ? "cash-scroll-1-24"
              : `cash-scroll-${pageNumber}-${index + 1}`,
          occurred_at: options.cashCrossMonthBoundary && pageNumber === 1
            ? `2026-10-${String(24 - index).padStart(2, "0")}T10:30:00+08:00`
            : options.cashCrossMonthBoundary && pageNumber > 1
              ? `2026-09-${String(24 - index).padStart(2, "0")}T10:30:00+08:00`
              : pageNumber === 1 && index === 1
            ? "2026-09-24T10:30:00+08:00"
            : pageNumber > 1 && index === 0
              ? "2026-09-01T10:30:00+08:00"
              : `2026-09-${String(24 - index).padStart(2, "0")}T10:30:00+08:00`,
          counterparty: pageNumber === 1 && index === 1
            ? "重复流水"
            : pageNumber > 1 && index === 0
              ? "第1页流水24"
              : `第${pageNumber}页流水${String(index + 1).padStart(2, "0")}`,
          amount: economicType === "income" ? "28.50" : "-28.50",
          economic_type: economicType,
        }));
        cashProjectionPageIds.push(items.map((item) => item.projection_id));
        return json({
          projection_version: 1,
          items: options.cashPageEmpty ? [] : items,
          next_cursor: pageNumber < options.cashPaginatedPageCount ? `cash-scroll-page-${pageNumber + 1}` : null,
          page_size: items.length,
          filter_options: {
            categories: state.categoryItems,
            currencies: ["CNY"],
            economic_types: [
              { economic_type: "expense", transfer_subtypes: [] },
              { economic_type: "income", transfer_subtypes: [] },
              { economic_type: "internal_transfer", transfer_subtypes: options.cashTransferSubtypes ?? [] },
            ],
          },
          monthly_summaries: options.cashPageEmpty
            ? []
            : options.cashCrossMonthBoundary && pageNumber === 1
              ? ["2026-10", "2026-09"].map((month) => ({
                month,
                currencies: [{ currency: "CNY", income: economicType === "income" ? "28.50" : "0", expense: economicType === "expense" ? "28.50" : "0" }],
              }))
              : [{
                month: "2026-09",
                currencies: [{ currency: "CNY", income: economicType === "income" ? "28.50" : "0", expense: economicType === "expense" ? "28.50" : "0" }],
              }],
        });
      }
      return json({
        projection_version: 1,
        items: options.cashPageEmpty ? [] : [cashProjection],
        next_cursor: null,
        page_size: 20,
        filter_options: {
          categories: state.categoryItems,
          currencies: ["CNY"],
          economic_types: [
            { economic_type: "expense", transfer_subtypes: [] },
            { economic_type: "internal_transfer", transfer_subtypes: options.cashTransferSubtypes ?? [] },
          ],
        },
        monthly_summaries: options.cashPageEmpty ? [] : [{ month: "2026-09", currencies: [{ currency: "CNY", income: "0", expense: "28.50" }] }],
      });
    }
    if (pathname === "/api/v1/cash-projections/categories" && method === "PUT") {
      const values = JSON.parse(body ?? "{}") as Record<string, unknown>;
      classificationWrites.push(values);
      return json({
        projection_version: 1,
        projection_count: Array.isArray(values.projection_ids) ? values.projection_ids.length : 0,
        updated_transaction_count: Array.isArray(values.projection_ids) ? values.projection_ids.length : 0,
        category_id: values.category_id ?? null,
      });
    }
    if (pathname === "/api/v1/cash-projections/delete-impact" && method === "POST") {
      return json({ projection_count: 1, transaction_count: 1, relation_group_count: 0 });
    }
    if (pathname === "/api/v1/cash-projections" && method === "DELETE") {
      const values = JSON.parse(body ?? "{}") as Record<string, unknown>;
      projectionDeletes.push(values);
      return json({ projection_count: 1, transaction_count: 1, relation_group_count: 0, deleted: true, projection_version: 2 });
    }
    if (pathname === "/api/v1/cash-records" && method === "POST") {
      const values = JSON.parse(body ?? "{}");
      writes.push(values);
      return json({ record: { ...cashRecord, ...values, id: "record-created" } });
    }
    if (pathname === "/api/v1/cash-records/record-1" && method === "GET") {
      return json({ transaction_id: "record-1", state: "posted", row_revision: 1, record_origin: "import",
        confirmation_status: "confirmed", fields: cashRecord, editable_fields: [], issues: [], relations: [],
        entry: { entry_id: "entry-1", revision: 1, fields: { note: cashRecord.note, counterparty: cashRecord.counterparty,
          category_id: cashRecord.category_id, counterparty_account: "", counterparty_account_attrs: [] },
          manual_fields: [], editable_fields: ["note", "counterparty", "category_id", "counterparty_account", "counterparty_account_attrs"],
          supports_transfer_edit: false } });
    }
    if (pathname === "/api/v1/evidence/cash-projections/projection-1" && method === "GET") {
      return json({
        projection_version: 1,
        projection: cashProjection,
        root_record: { ...cashRecord, id: "record-1" },
        members: [],
        accepted_relations: [],
        inactive_relation_hints: [],
        refund_timeline: [],
      });
    }

    if (pathname === "/api/v1/investment-portfolio" && method === "GET") return json(portfolio);
    if (pathname === "/api/v1/investment-portfolio/stream" && method === "GET") return route.fulfill({ status: 204 });
    if (pathname === "/api/v1/investment-portfolio/refresh" && method === "POST") return json({ ok: true });
    if (pathname === "/api/v1/investment-events" && method === "GET") {
      const isSecondPage = searchParams.get("cursor") === "event-page-2";
      const event = { ...investmentEvent, event_id: isSecondPage ? "event-2" : investmentEvent.event_id, note: isSecondPage ? "第二条投资事件" : investmentEvent.note };
      return json({ data_version: 1, items: [event], next_cursor: isSecondPage ? null : "event-page-2", page_size: 1, filters: {} });
    }
    if (/^\/api\/v1\/evidence\/investment-events\/[^/]+$/.test(pathname) && method === "GET") {
      return json({ data_version: 1, event: investmentEvent, source_snapshot: {}, relations: [] });
    }

    return json({ error: { code: "not_found" } }, 404);
  });

  return { calls, state };
}
