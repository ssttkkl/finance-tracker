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
    expect(await screen.findByText("已识别为支付宝账单")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "继续查看预览 →" }));
    expect(await screen.findByRole("heading", { name: "导入预览" })).toBeInTheDocument();
    expect(screen.getByText("总记录数")).toBeInTheDocument();
    expect(screen.getByText("交易对方")).toBeInTheDocument();
    expect(screen.queryByText("交易对方原始列")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "下一步：查看配对 →" }));
    expect(await screen.findByText("待手动配对")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "暂不处理" }));
    fireEvent.click(screen.getByRole("button", { name: "确认导入" }));
    expect(await screen.findByRole("heading", { name: "导入已完成" })).toBeInTheDocument();
    await waitFor(() => expect(fetch.mock.calls.some(([input]) => String(input).includes("/cash-import/commit"))).toBe(true));
    const commitRequest = fetch.mock.calls.find(([input]) => String(input).includes("/cash-import/commit"));
    expect(String(commitRequest?.[0])).toContain("preview_digest=digest-1");
    expect(String(commitRequest?.[0])).toContain("relations=%5B%5D");
  });
});
