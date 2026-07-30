import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { CashTable } from "../src/components/CashTable";

const projection = (projection_id: string, kind: string) => ({
  projection_id, occurred_at: "2026-07-03T09:00:00+08:00", account: { id: 101, name: "日常账户", type: "cash", active: true },
  counterparty: `交易对方${projection_id}`, category: "餐饮", amount: "-12.50", currency: "CNY", note: "", economic_type: "expense" as const,
  transfer_subtype: null, composition: [kind], member_count: 2, accepted_relation_summary: [{ kind, subtype: "", count: 1 }], source_type: "fixture", record_id: `cash-${projection_id}`, visible: true, hidden_reason: null,
});

it("只将已采用关系摘要映射为规范中文，并安全回退未知枚举", () => {
  render(<CashTable items={[projection("1", "payment_mirror"), projection("2", "refund_offset"), projection("3", "unknown_kind")]} onEvidence={() => undefined} />);

  expect(screen.getByText("同笔支付关系（1）")).toBeInTheDocument();
  expect(screen.getByText("退款冲销关系（1）")).toBeInTheDocument();
  expect(screen.getByText("未识别的关系类型（1）")).toBeInTheDocument();
  expect(screen.queryByText(/pending_review|accepted|unknown_kind/)).not.toBeInTheDocument();
});
