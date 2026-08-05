## ADDED Requirements

### Requirement: 投资费用和资金供给的来源语义规范化
系统 MUST 在投资导入边界使用来源直接提供的原始动作、旗标、方向和结构化备注归一 `record_type` 与 `record_subtype`，并将用于决定语义的原始字段完整保留在 `source_payload`。导入器不得仅按金额方向把现金变化写为 `funding(external)`。

#### Scenario: 东方证券资金、内部调拨、利息和税费
- **WHEN** 东方证券来源动作分别为“银行转证券”或“证券转银行”、“OTC资金划入”或“OTC资金划出”、“利息归本”与“股息红利差异扣税”
- **THEN** 导入器 MUST 分别输出 `funding(external)`、`funding(subaccount)`、`income(interest)` 与 `expense(tax)`，并保留未归一的原始动作

#### Scenario: 盈立费用与税费冲回
- **WHEN** 盈立来源旗标或备注明确表示税费返还、融券罚息、股息代收费、IPO认购手续费、平台费返还或佣金返还
- **THEN** 导入器 MUST 分别输出 `reversal(expense_tax)`、`expense(penalty)`、`expense(handling_fee)`、`expense(handling_fee)`、`reversal(expense_handling_fee)` 或 `reversal(expense_commission)`；只有明确的入金、出金、提取、EDDA入金或 EDDA出金才可输出 `funding(external)`

#### Scenario: 不明确的来源现金变化失败关闭
- **WHEN** 来源现金变化不满足已定义的原始动作、旗标和结构化备注规则
- **THEN** 新导入 MUST 失败关闭且不得创建投资事件；仅无法再取得原始账单的历史事实可保留 `adjustment(unclassified)`，且不得参与资金调拨扫描
