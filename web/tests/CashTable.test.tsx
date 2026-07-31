import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { CashTable } from "../src/components/CashTable";

const projection = (projection_id: string, kind: string, note = "") => ({
  projection_id, occurred_at: "2026-07-03T09:00:00+08:00", account: { id: 101, name: "日常账户", type: "cash", active: true },
  counterparty: `交易对方${projection_id}`, category: "餐饮", amount: "-12.50", currency: "CNY", note, economic_type: "expense" as const,
  transfer_subtype: null, composition: [kind], member_count: 2, accepted_relation_summary: [{ kind, subtype: "", count: 1 }], source_type: "fixture", record_id: `cash-${projection_id}`, visible: true, hidden_reason: null,
});

it("在交易对方后展示备注，并且不把关系摘要作为列表内容", () => {
  render(<CashTable items={[projection("1", "payment_mirror", "午间消费"), projection("2", "refund_offset"), projection("3", "unknown_kind")]} onEvidence={() => undefined} />);

  expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["发生时间", "账户", "交易对方", "备注", "分类", "金额", "来源", "操作"]);
  expect(screen.getByRole("table")).toHaveClass("cash-table");
  expect(screen.getByRole("cell", { name: "交易对方1" })).toHaveAttribute("headers", "cash-column-counterparty");
  expect(screen.getAllByText("-12.50 CNY")[0]).toHaveAttribute("data-direction", "支出");
  expect(screen.getByRole("row", { name: /交易对方1/ })).toHaveAttribute("data-projection-id", "1");
  expect(screen.getByText("午间消费")).toHaveAttribute("headers", "cash-column-note");
  expect(screen.queryByRole("columnheader", { name: "组成方式" })).not.toBeInTheDocument();
  expect(screen.getByText("午间消费")).toBeInTheDocument();
  expect(screen.getAllByText("未提供")).toHaveLength(2);
  expect(screen.queryByText(/同笔支付关系|退款冲销关系|未识别的关系类型/)).not.toBeInTheDocument();
});
