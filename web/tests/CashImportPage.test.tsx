import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CashImportPage } from "../src/pages/CashImportPage";

const item = {
  record_id: "row-1", occurred_at: "2026-08-12T09:24:00+08:00", amount: "-12.50", currency: "CNY",
  account_name: "支付宝余额", counterparty: "咖啡店", counterparty_account: "",
  record_type: "consumption", record_subtype: "not_applicable", category: "餐饮", note: "拿铁",
  channel: "alipay", status: "new" as const, message: "",
};
const columns = ["occurred_at", "amount", "currency", "account_name", "counterparty", "counterparty_account", "record_type", "record_subtype", "category", "note", "channel", "status"];
const preview = {
  channel: "alipay", channel_label: "支付宝", file: { name: "statement.csv", digest: "digest-1" }, columns,
  items: [item], summary: { total: 1, new: 1, existing: 0, unsupported: 0 },
  relations: [{
    id: "relation-1", kind: "payment_mirror", label: "同笔支付", subtype: "", status: "pending_review" as const,
    automatic: false, rule_id: "mirror.v1", reason: "候选不唯一",
    primary: { ...item, preview: true }, secondary: null,
    candidates: [{ ...item, record_id: "existing-1", preview: false, fact_id: 42 }],
  }],
};

const automaticRelation = {
  id: "relation-auto", kind: "payment_mirror", label: "同笔支付", subtype: "", status: "accepted" as const,
  automatic: true, rule_id: "mirror.v1", reason: "唯一匹配",
  primary: { ...item, record_id: "row-auto", preview: true },
  secondary: { ...item, record_id: "existing-auto", preview: false, fact_id: 43 },
  candidates: [],
};

function previewWithRelations(count = 2) {
  return {
    ...preview,
    relations: [automaticRelation, ...Array.from({ length: Math.max(0, count - 1) }, (_, index) => ({
      ...preview.relations[0],
      id: `relation-manual-${index + 1}`,
      primary: { ...item, record_id: `row-manual-${index + 1}`, preview: true },
    }))],
  };
}

function response(value: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json" } }));
}

beforeEach(() => vi.stubEnv("VITE_FT_API_ORIGIN", "http://127.0.0.1:8000"));
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

describe("CashImportPage", () => {
  it("自动识别渠道、只展示标准字段，并允许跳过手动配对后确认", async () => {
    const fetch = vi.fn((input: string) => input.includes("/detect")
      ? response({ channel: "alipay", channel_label: "支付宝", file: { name: "statement.csv", digest: "digest-1" }, digest: "digest-1", row_count: 1 })
      : input.includes("/preview")
        ? response(preview)
        : response({ message: "导入完成", new_rows: 1, updated_rows: 0, channel: "alipay", digest: "digest-1" }));
    vi.stubGlobal("fetch", fetch);
    render(<CashImportPage onBack={vi.fn()} />);

    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    const file = new File(["standardized"], "statement.csv", { type: "text/csv" });
    fireEvent.change(document.querySelector<HTMLInputElement>('input[type="file"]')!, { target: { files: [file] } });
    expect(await screen.findByText("支付宝账单")).toBeInTheDocument();
    fireEvent.click(await screen.findByRole("button", { name: /^核对流水$/ }));
    expect(await screen.findByRole("heading", { name: "核对流水" })).toBeInTheDocument();
    expect(screen.getByText("全部")).toBeInTheDocument();
    expect(screen.getByText("交易对方")).toBeInTheDocument();
    expect(screen.queryByText("交易对方原始列")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "上一步" })).toBeInTheDocument();
    const previewStage = screen.getByRole("heading", { name: "核对流水" }).closest("section")!;
    const previewActions = previewStage.querySelector(".stage-actions-top")!;
    const previewTable = previewStage.querySelector(".standard-table-wrap")!;
    expect(previewActions.compareDocumentPosition(previewTable) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(previewStage.querySelectorAll(".stage-actions")).toHaveLength(0);

    fireEvent.click(screen.getByRole("button", { name: /^下一步$/ }));
    expect(screen.getAllByText("待处理").length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: "上一步" })).toBeInTheDocument();
    const relationsStage = screen.getByRole("heading", { name: "配对" }).closest("section")!;
    const relationsActions = relationsStage.querySelector(".stage-actions-top")!;
    const relationToolbar = relationsStage.querySelector(".relation-toolbar")!;
    expect(relationsActions.compareDocumentPosition(relationToolbar) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(relationsStage.querySelectorAll(".stage-actions")).toHaveLength(0);
    fireEvent.change(screen.getAllByRole("combobox")[1], { target: { value: "skip" } });
    fireEvent.click(screen.getByRole("button", { name: "确认导入" }));
    expect(await screen.findByRole("heading", { name: "导入完成" })).toBeInTheDocument();
    await waitFor(() => expect(fetch.mock.calls.some(([input]) => String(input).includes("/cash-import/commit"))).toBe(true));
    const commitRequest = fetch.mock.calls.find(([input]) => String(input).includes("/cash-import/commit"));
    expect(String(commitRequest?.[0])).toContain("preview_digest=digest-1");
    expect(String(commitRequest?.[0])).toContain("relations=%5B%5D");
  });

  it("分页展示关系、允许修改非自动类型，并能拒绝后撤销", async () => {
    const relationPreview = previewWithRelations(21);
    const fetch = vi.fn((input: string) => input.includes("/detect")
      ? response({ channel: "alipay", channel_label: "支付宝", file: { name: "statement.csv", digest: "digest-1" }, digest: "digest-1", row_count: 1 })
      : input.includes("/preview")
        ? response(relationPreview)
        : response({ message: "导入完成", new_rows: 1, updated_rows: 0, channel: "alipay", digest: "digest-1" }));
    vi.stubGlobal("fetch", fetch);
    render(<CashImportPage onBack={vi.fn()} />);

    const file = new File(["standardized"], "statement.csv", { type: "text/csv" });
    fireEvent.change(document.querySelector<HTMLInputElement>('input[type="file"]')!, { target: { files: [file] } });
    await screen.findByText("支付宝账单");
    fireEvent.click(screen.getByRole("button", { name: /^核对流水$/ }));
    fireEvent.click(await screen.findByRole("button", { name: /^下一步$/ }));

    expect(screen.getAllByRole("button", { name: "拒绝配对" })).toHaveLength(20);
    expect(screen.getByText("第 1 / 2 页")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "上一步" })).toBeInTheDocument();
    const relationsStage = screen.getByRole("heading", { name: "配对" }).closest("section")!;
    const relationsActions = relationsStage.querySelector(".stage-actions-top")!;
    const relationTable = relationsStage.querySelector(".relation-table-wrap")!;
    expect(relationsActions.compareDocumentPosition(relationTable) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(relationsStage.querySelectorAll(".stage-actions")).toHaveLength(0);

    const typeSelect = screen.getAllByRole("combobox")[1];
    fireEvent.change(typeSelect, { target: { value: "refund_offset" } });
    expect(typeSelect).toHaveValue("refund_offset");

    fireEvent.click(screen.getAllByRole("button", { name: "拒绝配对" })[0]);
    expect(screen.getAllByRole("button", { name: "撤销拒绝" })).toHaveLength(1);
    expect(screen.getByText("已拒绝")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "撤销拒绝" }));
    expect(screen.getAllByRole("button", { name: "拒绝配对" })).toHaveLength(20);

    fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    expect(screen.getByText("第 2 / 2 页")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "拒绝配对" })).toHaveLength(1);
  });

  it("确认时提交已拒绝的关系决定", async () => {
    const relationPreview = previewWithRelations(1);
    const fetch = vi.fn((input: string) => input.includes("/detect")
      ? response({ channel: "alipay", channel_label: "支付宝", file: { name: "statement.csv", digest: "digest-1" }, digest: "digest-1", row_count: 1 })
      : input.includes("/preview")
        ? response(relationPreview)
        : response({ message: "导入完成", new_rows: 1, updated_rows: 0, channel: "alipay", digest: "digest-1" }));
    vi.stubGlobal("fetch", fetch);
    render(<CashImportPage onBack={vi.fn()} />);

    const file = new File(["standardized"], "statement.csv", { type: "text/csv" });
    fireEvent.change(document.querySelector<HTMLInputElement>('input[type="file"]')!, { target: { files: [file] } });
    await screen.findByText("支付宝账单");
    fireEvent.click(screen.getByRole("button", { name: /^核对流水$/ }));
    fireEvent.click(await screen.findByRole("button", { name: /^下一步$/ }));
    fireEvent.click(screen.getAllByRole("button", { name: "拒绝配对" })[0]);
    fireEvent.click(screen.getByRole("button", { name: "确认导入" }));
    await screen.findByRole("heading", { name: "导入完成" });

    const commitRequest = fetch.mock.calls.find(([input]) => String(input).includes("/cash-import/commit"));
    expect(String(commitRequest?.[0])).toContain(encodeURIComponent('"status":"rejected"'));
  });

  it("加密 PDF 要求输入密码，并通过请求头重试而不放进 URL", async () => {
    let detectCalls = 0;
    const fetch = vi.fn((input: string, init?: RequestInit) => {
      if (input.includes("/detect")) {
        detectCalls += 1;
        if (detectCalls === 1) return response({ error: { code: "import_password_required" } }, 400);
        expect(input).not.toContain("correct-password");
        expect((init?.headers as Record<string, string>)["X-FT-Statement-Password"]).toBe("correct-password");
        return response({ channel: "icbc", channel_label: "工行信用卡", file: { name: "locked.pdf", digest: "digest-1" }, digest: "digest-1", row_count: 1 });
      }
      return response({ message: "ok", new_rows: 1, updated_rows: 0 });
    });
    vi.stubGlobal("fetch", fetch);
    render(<CashImportPage onBack={vi.fn()} />);

    const file = new File(["encrypted"], "locked.pdf", { type: "application/pdf" });
    fireEvent.change(document.querySelector<HTMLInputElement>('input[type="file"]')!, { target: { files: [file] } });
    expect(await screen.findByLabelText("账单密码")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("账单密码"), { target: { value: "correct-password" } });
    fireEvent.click(screen.getByRole("button", { name: "重新识别" }));
    expect(await screen.findByText("工行信用卡账单")).toBeInTheDocument();
    expect(screen.queryByText("correct-password")).not.toBeInTheDocument();
  });

  it("预览阶段密码失效时回到选择文件并清空密码", async () => {
    let detectCalls = 0;
    const fetch = vi.fn((input: string, init?: RequestInit) => {
      if (input.includes("/detect")) {
        detectCalls += 1;
        if (detectCalls === 1) return response({ error: { code: "import_password_required" } }, 400);
        expect((init?.headers as Record<string, string>)["X-FT-Statement-Password"]).toBe("wrong-password");
        return response({ channel: "icbc", channel_label: "工行信用卡", file: { name: "locked.pdf", digest: "digest-1" }, digest: "digest-1", row_count: 1 });
      }
      if (input.includes("/preview")) return response({ error: { code: "import_password_invalid" } }, 400);
      return response({ message: "ok", new_rows: 1, updated_rows: 0 });
    });
    vi.stubGlobal("fetch", fetch);
    render(<CashImportPage onBack={vi.fn()} />);

    const file = new File(["encrypted"], "locked.pdf", { type: "application/pdf" });
    fireEvent.change(document.querySelector<HTMLInputElement>('input[type="file"]')!, { target: { files: [file] } });
    const passwordInput = await screen.findByLabelText("账单密码");
    fireEvent.change(passwordInput, { target: { value: "wrong-password" } });
    fireEvent.click(screen.getByRole("button", { name: "重新识别" }));
    fireEvent.click(await screen.findByRole("button", { name: /^核对流水$/ }));

    expect(await screen.findByRole("heading", { name: "选择文件" })).toBeInTheDocument();
    expect(screen.getByLabelText("账单密码")).toHaveValue("");
    expect(screen.getByText("账单密码错误，请重试。")).toBeInTheDocument();
    expect(screen.queryByText("账单预览失败，请重试。")).not.toBeInTheDocument();
  });

  it("确认阶段需要密码时回到选择文件并重新要求密码", async () => {
    let detectCalls = 0;
    const fetch = vi.fn((input: string, init?: RequestInit) => {
      if (input.includes("/detect")) {
        detectCalls += 1;
        if (detectCalls === 1) return response({ error: { code: "import_password_required" } }, 400);
        expect((init?.headers as Record<string, string>)["X-FT-Statement-Password"]).toBe("correct-password");
        return response({ channel: "alipay", channel_label: "支付宝", file: { name: "locked.pdf", digest: "digest-1" }, digest: "digest-1", row_count: 1 });
      }
      if (input.includes("/preview")) return response(preview);
      if (input.includes("/commit")) return response({ error: { code: "import_password_required" } }, 400);
      return response({ message: "ok" });
    });
    vi.stubGlobal("fetch", fetch);
    render(<CashImportPage onBack={vi.fn()} />);

    const file = new File(["encrypted"], "locked.pdf", { type: "application/pdf" });
    fireEvent.change(document.querySelector<HTMLInputElement>('input[type="file"]')!, { target: { files: [file] } });
    const passwordInput = await screen.findByLabelText("账单密码");
    fireEvent.change(passwordInput, { target: { value: "correct-password" } });
    fireEvent.click(screen.getByRole("button", { name: "重新识别" }));
    fireEvent.click(await screen.findByRole("button", { name: /^核对流水$/ }));
    fireEvent.click(await screen.findByRole("button", { name: /^下一步$/ }));
    fireEvent.click(screen.getByRole("button", { name: "确认导入" }));

    expect(await screen.findByRole("heading", { name: "选择文件" })).toBeInTheDocument();
    expect(screen.getByLabelText("账单密码")).toHaveValue("");
    expect(screen.getByText("请输入账单密码。")).toBeInTheDocument();
    expect(screen.queryByText("确认导入失败，请重试。")).not.toBeInTheDocument();
  });
});
