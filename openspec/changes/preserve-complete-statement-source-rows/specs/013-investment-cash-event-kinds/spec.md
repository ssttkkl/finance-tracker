## MODIFIED Requirements

### Requirement: 投资经济事实的范围约束
系统 MUST 只允许 `funding(external|subaccount)`、`trade(security|fx|repo)`、`income(dividend_cash|dividend_stock|interest|reward)`、`expense(commission|tax|interest|handling_fee|penalty)`、`reversal(expense_tax|expense_interest|expense_commission|expense_handling_fee|expense_penalty|funding_withdrawal)`、`subscription(ipo_debit|ipo_refund)`、`adjustment(fx_net|manual|unclassified)` 与 `snapshot(cash|position)`。方向 MUST 由 `from_*` / `to_*` 资产组成表达。仅 `funding(external)` 可进入现金—投资资金调拨候选；`funding(subaccount)`、收入、支出、冲回、认购、调整和快照 MUST 不得进入该候选。无法安全归类且无法从可审计原始账单重建的历史现金变化 MUST 为 `adjustment(unclassified)`。

#### Scenario: 费用、税费与冲回不成为资金调拨
- **WHEN** 来源明确表示税费返还、融资利息、融券罚息、股息代收费、IPO认购手续费、平台费返还或佣金返还
- **THEN** 系统 MUST 分别记录为 `reversal(expense_tax)`、`expense(interest)`、`expense(penalty)`、`expense(handling_fee)`、`expense(handling_fee)`、`reversal(expense_handling_fee)` 或 `reversal(expense_commission)`，且关系扫描不得把它们作为现金—投资资金调拨候选

#### Scenario: 来源动作可审计时不保留未分类调整
- **WHEN** 原始投资账单直接提供可映射到外部出入金、投资账户内部调拨、利息或税费的原始动作
- **THEN** 重导结果 MUST 使用相应的 `funding`、`income` 或 `expense` 语义，不得因为旧事件曾为 `deposit`、`withdraw` 或 `adjustment(unclassified)` 而保留未分类调整
