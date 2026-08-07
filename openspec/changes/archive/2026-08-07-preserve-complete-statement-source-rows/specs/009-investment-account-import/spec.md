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

### Requirement: 投资导入完整来源行快照
系统 MUST 要求东方证券、IBKR、Charles Schwab 与盈立证券的每条投资流水和每条账单汇总 `snapshot` 都由解析器显式提供来源行快照。具有唯一原始表头的 CSV 必须保留全部原始列名和值，包括空列；不能唯一表达表头的多区段 CSV 与 PDF 必须保存可归属该业务行或汇总值的全部原始文本单元。`source_payload` 不得新增或替换为归一后的动作、`record_type`、`record_subtype`、标的、金额、币种、费用、账户、幂等身份、产品档案或任何映射和编排字段；来源 CSV 本身具有同名列时，仍必须保留其原始列名和值。导入编排 MUST 只持久化解析器提供的快照；快照缺失、为空、非 JSON 对象或包含私有编排键时 MUST 拒绝整批导入。

#### Scenario: CSV 流水与汇总快照
- **WHEN** 用户导入 IBKR 或 Charles Schwab CSV
- **THEN** 每个流水事件 MUST 保存该来源行的完整原始列值或原始文本单元，期末现金 `snapshot` MUST 保存产生该余额的原始汇总行或最新余额行，且不得新增标准化交易类型、证券代码、金额或映射备注；来源行自身的同名列仍保留原值

#### Scenario: PDF 流水与汇总快照
- **WHEN** 用户导入东方证券或盈立 PDF
- **THEN** 每个流水事件 MUST 保存对应业务块的原始文本单元；现金、持仓和零持仓 `snapshot` MUST 保存可归属其数值或期末缺席事实的原始汇总文本单元，且不得伪造 `CHECKIN`、`action_raw`、旗标、标的、数量或日期字段

### Requirement: 投资事件来源说明与交易总费用
系统 MUST 将投资事件 `note` 限定为来源提供的、可供使用者阅读的描述、备注、动作或旗标。来源没有此类说明的 API 同步事件、交易分组事件和汇总 `snapshot` MUST 写入空字符串；`note` MUST NOT 写入 `record_id`、交易哈希、系统 `checkin` 文案或从金额字段拼接的费用摘要。导入器可以在解析阶段使用来源描述、动作或旗标归一 `record_type` 与 `record_subtype`，但映射完成后的 `note` MUST 不参与分类、关系匹配、投影计算或业务行标识生成。

对东方证券以“总发生金额”表达净现金变化的交易，导入器 MUST 将可直接识别的手续费、印花税和过户费的绝对值之和作为交易总费用写入 `commission`，并使用现金币种作为 `commission_asset`。买入时 `from_amount + commission`、卖出时 `to_amount - commission` MUST 等于 `abs(总发生金额)`；因此拆分费用不得改变逐笔现金净变化。费用缺失、为负或买入时交易总费用不小于净额而无法安全拆分时，导入器 MUST 保留净额现金部分并将 `commission=0`，不得发明费用。

#### Scenario: 东方证券买入拆分全部费用
- **WHEN** 一笔东方证券买入的“总发生金额”为 `-1011.53`，手续费为 `5.00`，印花税为 `6.40`，过户费为 `0.13`
- **THEN** 新投资事件 MUST 记录 `commission=11.53`、现金币种 `commission_asset` 和 `from_amount=1000.00`，且投影现金流出仍为 `1011.53`

#### Scenario: 东方证券卖出拆分全部费用
- **WHEN** 一笔东方证券卖出的“总发生金额”为 `1011.53`，手续费为 `5.00`，印花税为 `6.40`，过户费为 `0.13`
- **THEN** 新投资事件 MUST 记录 `commission=11.53`、现金币种 `commission_asset` 和 `to_amount=1023.06`，且投影现金流入仍为 `1011.53`

#### Scenario: 无来源说明的同步或汇总事件
- **WHEN** CCXT、Polymarket、交易分组或账单汇总生成的投资事件没有来源提供的可读说明
- **THEN** 该事件的 `note` MUST 为空字符串，技术标识仍由 `record_id` 和 `source_payload` 保存，且相同来源行重复导入的幂等结果不变
