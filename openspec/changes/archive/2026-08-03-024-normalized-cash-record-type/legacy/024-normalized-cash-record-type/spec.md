# Feature Specification: 导入时生成现金流水标准记录类型

**Feature Branch**: `024-normalized-cash-record-type`

**Created**: 2026-08-01

**Status**: Draft

**Input**: 用户要求在导入账单原始记录时增加标准化记录类型，先支持关系配对器按类型筛选；不提供历史数据兼容逻辑，修改后用 `.ft/bills` 重建新数据库。

## Context

当前现金流水只有 `category`，它表达金额方向（`expense`/`income`），不能区分消费、转账、提现、退款、撤销、还款、投资等来源业务语义。导入器已经读取各来源原生字段，但这些字段主要留在 `source_payload`，关系配对器只能重复解释来源文本。

本 Feature 只完成导入阶段的标准记录类型和持久化字段。退款、转账和还款的关系扫描改造属于后续 Feature。

## User Scenarios & Testing

### User Story 1 - 导入后每条现金流水都有标准记录类型（Priority: P1）

作为账单导入用户，我希望每条现金流水在入库时就有统一的 `record_type`，这样后续查询和关系配对不必重新猜测来源字段。

**Independent Test**: 用各来源最小样例导入 SQLite，检查 `cash_transactions.record_type` 和来源行快照中的原生字段。

**Acceptance Scenarios**:

1. **Given** 一条可导入的现金账单记录，**When** 导入完成，**Then** 数据库中 `record_type` 非空，并且 `source_payload` 保留用于分类的原生字段。
2. **Given** 一条金额为 0 的记录，**When** 导入，**Then** 仍按来源业务语义分类，不额外生成零金额类型。
3. **Given** 账单记录没有命中已知来源语义，**When** 导入，**Then** 类型为 `other`，不因为正负号猜测为转账或收入。

### User Story 2 - 主要来源使用来源原生字段分类（Priority: P1）

作为账单导入用户，我希望微信、支付宝、工行和建行账单按各自导出的交易类型、收支方向和摘要分类，尤其正确区分还款和转账。

**Independent Test**: 对真实账单全集运行纯解析分类统计，并检查代表性原始行。

**Acceptance Scenarios**:

1. **Given** 微信的 `商户消费`、`扫二维码付款`、`充值` 或 `缴费`，**When** 分类，**Then** 为 `consumption`。
2. **Given** 微信的 `转账`、`群收款` 或 `微信红包`，**When** 按 `收/支` 分类，**Then** 收入为 `transfer_in`，支出为 `transfer_out`；若来源状态表达该 P2P 交易已退回，**Then** 为 `transfer_reversal`。
3. **Given** 支付宝的 `信用借还`，**When** 分类，**Then** 为独立的 `repayment`，不归入 `transfer_in` 或 `transfer_out`。
4. **Given** 工行或建行摘要为还款语义，或建行摘要为 `代理收款`，**When** 分类，**Then** 为 `repayment`。
5. **Given** 来源明确表达消费退款或退货，**When** 分类，**Then** 为 `refund`；来源明确表达撤销或冲正时，**Then** 为 `reversal`；退款正式信号仍按既有来源规则保存。
6. **Given** 来源明确表达工资或奖金，**When** 分类，**Then** 为 `income`。
7. **Given** 来源明确表达提现或取现，**When** 按来源方向分类，**Then** 支出为 `withdrawal_out`，收入为 `withdrawal_in`，不归入普通转账类型。

### User Story 3 - 重建数据库使用新字段（Priority: P1）

作为本地账本用户，我希望修改后直接用 `.ft/bills` 全量账单构建一个新 SQLite，再替换当前数据库，不对旧错误记录提供隐式兼容。

**Independent Test**: 备份当前数据库，初始化新数据库并全量导入现金账单，检查总行数、类型分布和 schema。

**Acceptance Scenarios**:

1. **Given** 新数据库已初始化，**When** 导入 `.ft/bills` 中现有现金账单，**Then** 每条现金流水都有非空 `record_type`。
2. **Given** 全量现金账单导入成功，**When** 按 `record_type` 统计，**Then** 当前真实账单中的 `other` 为 0，`repayment` 包含支付宝 `信用借还`、微信 `信用卡还款`、工行还款类摘要和建行 `代理收款`。
3. **Given** 当前数据库已备份，**When** 新库校验通过，**Then** 新库覆盖当前数据库；失败时保留原库和新库，不进行半成品覆盖。

## Standard Record Type Values

现金流水的 `record_type` 取以下稳定枚举值：

| 值 | 含义 |
|---|---|
| `consumption` | 消费、充值、缴费和其他购买型支出 |
| `refund` | 消费退款、退货 |
| `reversal` | 撤销交易、冲正等非消费退款的撤销记录 |
| `withdrawal_in` | 提现资金进入当前账户的记录，例如正金额 `支付机构提现` |
| `withdrawal_out` | 提现、取现和银行取款导致当前账户资金流出的记录 |
| `transfer_in` | 转账类收入、收款、红包收入等转入 |
| `transfer_out` | 转账类支出、转出等普通转账出账 |
| `repayment` | 信用账户或贷款账户还款，独立于转入/转出 |
| `income` | 工资、奖金、利息之外的普通收入 |
| `investment_in` | 投资产品或基金赎回、卖出等资金流入 |
| `investment_out` | 投资产品或基金购买、转入等资金流出 |
| `interest` | 利息或收益发放 |
| `fee` | 手续费、管理费等费用 |
| `fx_in` | 售汇、换汇等外汇资金流入 |
| `fx_out` | 购汇、换汇等外汇资金流出 |
| `other` | 已导入但尚未有明确来源语义的兜底类型 |

`category` 继续表达现金金额方向；`record_type` 不替换 `category`，也不因金额为 0 单独增加类型。

## Source Mapping

分类优先使用来源原生字段，优先级为：撤销/冲正信号 → 消费退款信号 → 还款信号 → 提现信号 → 投资、利息、费用和外汇信号 → 转入/转出信号 → 消费信号 → 普通收入 → `other`。

| 导入渠道 | 原生字段/值 | 标准记录类型 |
|---|---|---|
| 微信 | `商户消费`、`扫二维码付款`、`充值`、`零钱充值`、`缴费` | `consumption` |
| 微信 | `转账`、`群收款`、`微信红包`、`二维码收款`，按 `收/支` | 收入 `transfer_in`，支出 `transfer_out`；退回/退款状态为 `transfer_reversal` |
| 微信 | `零钱提现`，按 `收/支` | 收入 `withdrawal_in`，支出 `withdrawal_out` |
| 微信 | `信用卡还款` | `repayment` |
| 微信 | 交易类型或状态表达退款 | `refund` |
| 支付宝 | `信用借还` | `repayment` |
| 支付宝 | `转账红包`，按来源方向 | `transfer_in`/`transfer_out`；退回/退款状态为 `transfer_reversal` |
| 支付宝 | 账户提现、明确提现或转出到银行卡，按来源方向 | 收入 `withdrawal_in`，支出 `withdrawal_out` |
| 支付宝 | `充值缴费`及普通购买型交易 | `consumption` |
| 支付宝 | `投资理财`中的买入/转入、卖出/转出 | `investment_out`/`investment_in` |
| 支付宝 | `投资理财`中的收益发放、账户结息 | `interest` |
| 工行信用卡/借记卡 | `summary=退货`、退款来源信号 | `refund` |
| 工行信用卡/借记卡 | `summary=撤销交易`、冲正等撤销来源信号 | `reversal` |
| 工行信用卡/借记卡 | `summary`为还款、购汇还款、自动还款等 | `repayment` |
| 工行信用卡/借记卡 | `summary=转账`、汇入等，按金额方向 | `transfer_in`/`transfer_out` |
| 工行信用卡/借记卡 | 提现、取现、预约取现、银行取款等，按金额方向 | 收入 `withdrawal_in`，支出 `withdrawal_out` |
| 工行信用卡/借记卡 | 工资、奖金 | `income` |
| 工行信用卡/借记卡 | 利息、手续费、购汇/售汇 | `interest`/`fee`/`fx_out`/`fx_in` |
| 建行借记卡 | `消费`、`充值`、`缴费`、无卡/有卡消费 | `consumption` |
| 建行借记卡 | 退款、退货、消费退货 | `refund` |
| 建行借记卡 | 冲正、撤销 | `reversal` |
| 建行借记卡 | `还款`、`代理收款` | `repayment` |
| 建行借记卡 | 转账、转账支取、银转证/证转银、汇入、存现 | 按来源方向为 `transfer_in`/`transfer_out` |
| 建行借记卡 | 取现、ATM 取款、支付机构提现、无卡取款，按金额方向 | 收入 `withdrawal_in`，支出 `withdrawal_out` |
| 建行借记卡 | `无卡自助交易`、`无卡支付` | `consumption` |
| 建行借记卡 | 基金购买/赎回 | `investment_out`/`investment_in` |
| 建行借记卡 | 利息、账户管理费 | `interest`/`fee` |

## Edge Cases

- `record_type` 是持久化正式字段；现金导入不得写入空值。
- `other` 只作为未知来源语义的显式兜底，不允许用“金额为负所以是转出”替代来源字段分类。
- `repayment` 不并入 `transfer_in` 或 `transfer_out`；`withdrawal_in` 和 `withdrawal_out` 也不并入普通转账，支付平台提现由专用提现关系规则按出账 → 入账配对。
- `refund` 只表示消费退款；一般撤销和冲正使用 `reversal`，P2P 转账、红包、群收款的退回使用 `transfer_reversal`，两者都不能进入消费退款关系。
- 退款的 `record_type=refund` 与正式退款信号是两个概念；本 Feature 不放宽工行正式退款信号规则。
- 本 Feature 不向旧数据库记录回填、猜测或兼容旧分类；旧数据修复路径是重建新数据库。
- SQLite 和 PostgreSQL 的字段、非空约束、枚举值和导入事务行为必须等价；允许差异仅限方言和测试环境启动方式。

## Requirements

- **FR-001**: 现金账单导入 MUST 为每条输出记录生成非空 `record_type`。
- **FR-002**: `record_type` MUST 使用本 Feature 定义的稳定枚举值。
- **FR-003**: 导入分类 MUST 优先读取来源原生交易类型、方向、摘要和正式信号，不得用金额正负单独推断转账或还款。
- **FR-004**: `repayment` MUST 独立于 `transfer_in` 和 `transfer_out`；`withdrawal_in` 和 `withdrawal_out` MUST 独立于普通转账类型。
- **FR-005**: 支付宝 `信用借还`、微信 `信用卡还款`、工行还款类摘要以及建行 `代理收款` MUST 分类为 `repayment`。
- **FR-006**: 充值和缴费 MUST 分类为 `consumption`；工资和奖金 MUST 分类为 `income`；消费退款 MUST 分类为 `refund`；撤销和冲正 MUST 分类为 `reversal`；提现和取现按方向 MUST 分类为 `withdrawal_in` 或 `withdrawal_out`。
- **FR-007**: `record_type` MUST 持久化到 `cash_transactions`，并在现金流水内部读取结果中可见。
- **FR-008**: `source_payload` MUST 继续保留分类所用原生字段，且不以 `record_type` 替换原始字段。
- **FR-009**: 未知来源语义 MUST 分类为 `other`，不得静默丢弃。
- **FR-010**: 导入关系扫描和配对规则 MUST NOT 在本 Feature 中改变。
- **FR-011**: 新数据库全量导入 `.ft/bills` 后，当前真实现金账单的 `other` MUST 为 0。
- **FR-012**: SQLite 与真实 PostgreSQL MUST 对 `record_type` schema 和导入结果提供等价契约；没有 PostgreSQL 环境时必须记录明确阻断原因。

## Key Entities

- **标准记录类型**：现金流水导入时生成的统一业务分类字段。
- **现金流水**：保存 `category`、`record_type`、来源渠道、业务行标识和来源行快照的现金账本记录。
- **来源行快照**：保留微信、支付宝、银行账单原生字段的 JSON 数据。

## Success Criteria

- **SC-001**: `.ft/bills` 中可导入的 11,394 条现金流水全部拥有非空 `record_type`。
- **SC-002**: 全量重建库的标准记录类型分布可查询，`other=0`，且 `repayment=69`（以当前账单全集为基线）。
- **SC-003**: 代表性样例中支付宝 `信用借还` 和建行 `代理收款` 均为 `repayment`，微信 `信用卡还款` 也为 `repayment`。
- **SC-004**: SQLite 受影响测试、全量导入校验和 schema 检查通过；PostgreSQL 契约在环境可用时通过，否则记录补跑命令。
- **SC-005**: 关系表和关系配对逻辑在本 Feature 中无行为变化，后续 Feature 可按 `record_type` 作为来源分类闸门。

## Assumptions

- 当前 `.ft/bills` 中现金来源为微信、支付宝、工行信用卡、工行借记卡和建行借记卡；投资账单仍走既有投资导入链路。
- 用户明确接受重建数据库，不要求旧数据库记录保留或自动回填新字段。
- `category` 仍供既有余额和投影逻辑使用，直到后续 Feature 显式改造关系配对器和投影器。
