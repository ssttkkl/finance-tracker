## MODIFIED Requirements

### Requirement: 投资经济事实的范围约束
系统 MUST 只允许 `funding(external|subaccount)`、`trade(security|fx|repo)`、`income(dividend_cash|dividend_stock|interest|reward)`、`expense(commission|tax|interest|handling_fee|penalty)`、`reversal(expense_tax|expense_interest|expense_commission|expense_handling_fee|expense_penalty|funding_withdrawal)`、`subscription(ipo_debit|ipo_refund)`、`adjustment(fx_net|manual|unclassified)` 与 `snapshot(cash|position)`。方向 MUST 由 `from_*` / `to_*` 资产组成表达。仅 `funding(external)` 可进入现金—投资资金调拨候选；`funding(subaccount)`、收入、支出、冲回、认购、调整和快照 MUST 不得进入该候选。无法安全归类且无法从可审计原始账单重建的历史现金变化 MUST 为 `adjustment(unclassified)`。

#### Scenario: 费用、税费与冲回不成为资金调拨
- **WHEN** 来源明确表示税费返还、融资利息、融券罚息、股息代收费、IPO认购手续费、平台费返还或佣金返还
- **THEN** 系统 MUST 分别记录为 `reversal(expense_tax)`、`expense(interest)`、`expense(penalty)`、`expense(handling_fee)`、`expense(handling_fee)`、`reversal(expense_handling_fee)` 或 `reversal(expense_commission)`，且关系扫描不得把它们作为现金—投资资金调拨候选

#### Scenario: 来源动作可审计时不保留未分类调整
- **WHEN** 原始投资账单直接提供可映射到外部出入金、投资账户内部调拨、利息或税费的原始动作
- **THEN** 重导结果 MUST 使用相应的 `funding`、`income` 或 `expense` 语义，不得因为旧事件曾为 `deposit`、`withdraw` 或 `adjustment(unclassified)` 而保留未分类调整

## ADDED Requirements

### Requirement: 已知机构名称的外部出入金配对
系统 MUST 仅为 `funding(external)` 使用受控机构名称作为资金调拨的附加确认信号。入金只可匹配发生在此前 7 个 `Asia/Shanghai` 自然日内的收支支出，出金只可匹配发生在其后 7 个自然日内的收支收入。东方证券的“银行转证券／证券转银行”、IBKR 的 `Interactive Brokers`、Charles Schwab 的 `Charles Schwab` 与盈立证券的简繁体名称可以作为受控机构名称；收款账号、本人名称、自由备注和仅金额相等不得成为该信号。机构名称命中且候选唯一时，即使金额或币种不同，系统 MUST 确认关系并仅保存受限匹配证据，不推导或拆分手续费。

#### Scenario: IBKR 跨币种入金
- **WHEN** 一笔 `ibkr_csv` 的 USD 外部入金在此前 7 日内仅有一笔 HKD `transfer_out`，且对手方包含 `Interactive Brokers`
- **THEN** 系统 MUST 确认该银行转账与投资事件的资金调拨关系，并保存机构名称、方向和业务日窗口证据，不保存对方账号或原始备注

#### Scenario: Charles Schwab 含银行手续费的入金
- **WHEN** 一笔 `schwab_csv` 的 USD `7,980` 外部入金在同日仅有一笔 USD `8,000` 的 `transfer_out`，且该流水对手方包含 `Charles Schwab`
- **THEN** 系统 MUST 确认该银行转账与投资事件的资金调拨关系，并只记录机构名称、方向和业务日窗口证据；不得为 USD `20` 差额创建手续费关系或推导费用事实

#### Scenario: 金额相同但未命中机构名称
- **WHEN** 一笔外部入金仅匹配到收款人为本人或普通个人的同币种同金额 `transfer_out`
- **THEN** 系统 MUST 保留待审核候选，不得因为金额相同自动确认
