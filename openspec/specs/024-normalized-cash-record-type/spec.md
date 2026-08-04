# 导入时生成现金流水标准记录类型

## Purpose
用户要求在导入账单原始记录时增加标准化记录类型，先支持关系配对器按类型筛选；不提供历史数据兼容逻辑，修改后用 `.ft/bills` 重建新数据库。 本能力的行为契约由迁移后的需求与场景持续维护。
## Requirements
### Requirement: 导入后每条现金流水都有标准记录类型
系统 MUST 支持以下用户目标：作为账单导入用户，我希望每条现金流水在入库时就有统一的 `record_type`，这样后续查询和关系配对不必重新猜测来源字段。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：**Given** 一条可导入的现金账单记录，**When** 导入完成，**Then** 数据库中 `record_type` 非空，并且 `source_payload` 保留用于分类的原生字段。
- THEN 系统满足该条件，并保留可复核的验证证据。

#### Scenario: 验收场景 2
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：**Given** 一条金额为 0 的记录，**When** 导入，**Then** 仍按来源业务语义分类，不额外生成零金额类型。
- THEN 系统满足该条件，并保留可复核的验证证据。

#### Scenario: 验收场景 3
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：**Given** 账单记录没有命中已知来源语义，**When** 导入，**Then** 类型为 `other`，不因为正负号猜测为转账或收入。
- THEN 系统满足该条件，并保留可复核的验证证据。

### Requirement: 主要来源使用来源原生字段分类

系统 MUST 支持以下用户目标：作为账单导入用户，我希望微信、支付宝、工行和建行账单按各自导出的交易类型、收支方向和摘要分类，尤其正确区分还款、转账和换汇。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：**Given** 微信的 `商户消费`、`扫二维码付款`、`充值` 或 `缴费`，**When** 分类，**Then** 为 `consumption`。
- THEN 系统满足该条件，并保留可复核的验证证据。

#### Scenario: 验收场景 2
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：**Given** 微信的 `转账`、`群收款` 或 `微信红包`，**When** 按 `收/支` 分类，**Then** 收入为 `transfer_in`，支出为 `transfer_out`；若来源状态表达该 P2P 交易已退回，**Then** 为 `transfer_reversal`。
- THEN 系统满足该条件，并保留可复核的验证证据。

#### Scenario: 验收场景 3
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：**Given** 支付宝的 `信用借还`，**When** 分类，**Then** 为独立的 `repayment`，不归入 `transfer_in` 或 `transfer_out`。
- THEN 系统满足该条件，并保留可复核的验证证据。

#### Scenario: 验收场景 4
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：**Given** 工行或建行摘要为还款语义，或建行摘要为 `代理收款`，**When** 分类，**Then** 为 `repayment`。
- THEN 系统满足该条件，并保留可复核的验证证据。

#### Scenario: 验收场景 5
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：**Given** 来源明确表达消费退款或退货，**When** 分类，**Then** 为 `refund`；来源明确表达撤销或冲正时，**Then** 为 `reversal`；退款正式信号仍按既有来源规则保存。
- THEN 系统满足该条件，并保留可复核的验证证据。

#### Scenario: 验收场景 6
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：**Given** 来源明确表达工资或奖金，**When** 分类，**Then** 为 `income`。
- THEN 系统满足该条件，并保留可复核的验证证据。

#### Scenario: 验收场景 7
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：**Given** 来源明确表达提现或取现，**When** 按来源方向分类，**Then** 支出为 `withdrawal_out`，收入为 `withdrawal_in`，不归入普通转账类型。
- THEN 系统满足该条件，并保留可复核的验证证据。

#### Scenario: 工行借记卡跨境汇款按转账分类
- **WHEN** 工行借记卡摘要为 `跨境汇款`
- **THEN** 系统 MUST 按来源收支方向分类为 `transfer_out` 或 `transfer_in`，不得分类为 `fx_out` 或 `fx_in`

#### Scenario: 工行借记卡购汇仍按换汇分类
- **WHEN** 工行借记卡摘要表达 `购汇`、`个人购汇`、`预约购汇`、`外汇` 或 `汇兑`
- **THEN** 系统 MUST 按来源收支方向分类为 `fx_out` 或 `fx_in`

### Requirement: 重建数据库使用新字段
系统 MUST 支持以下用户目标：作为本地账本用户，我希望修改后直接用 `.ft/bills` 全量账单构建一个新 SQLite，再替换当前数据库，不对旧错误记录提供隐式兼容。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：**Given** 新数据库已初始化，**When** 导入 `.ft/bills` 中现有现金账单，**Then** 每条现金流水都有非空 `record_type`。
- THEN 系统满足该条件，并保留可复核的验证证据。

#### Scenario: 验收场景 2
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：**Given** 全量现金账单导入成功，**When** 按 `record_type` 统计，**Then** 当前真实账单中的 `other` 为 0，`repayment` 包含支付宝 `信用借还`、微信 `信用卡还款`、工行还款类摘要和建行 `代理收款`。
- THEN 系统满足该条件，并保留可复核的验证证据。

#### Scenario: 验收场景 3
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：**Given** 当前数据库已备份，**When** 新库校验通过，**Then** 新库覆盖当前数据库；失败时保留原库和新库，不进行半成品覆盖。
- THEN 系统满足该条件，并保留可复核的验证证据。

### Requirement: 工行转账摘要的标准化存储
系统 MUST 从工行借记卡与信用卡 PDF 的账单摘要列提取来源原文，并将其同时保留在标准导入行的 `summary` 和入库事实的 `source_payload.summary`。`summary` MUST 独立于交易场所形成的 `note`；例如摘要为“转账”、交易场所为“手机银行”时，两者 MUST 分别保存。工行信用卡/借记卡摘要为“转账”或“转帐”时，系统 MUST 按来源方向分类为 `transfer_in` 或 `transfer_out`，而不得以金额正负单独推断为普通收入或消费。

#### Scenario: 信用卡转账摘要驱动标准记录类型
- **WHEN** 工行信用卡 PDF 行的摘要为“转账”、交易场所为“手机银行”且金额为正
- **THEN** 标准导入行与 `source_payload` MUST 保留 `summary="转账"` 和 `note="手机银行"`，且 `record_type` MUST 为 `transfer_in`

### Requirement: 标准记录类型历史更正通过来源账单重建
系统 MUST 对因解析缺陷丢失来源摘要的历史现金事实使用独立数据库从原始账单重建、验证和受控替换来更正 `record_type` 与来源快照。系统 MUST NOT 通过直接更新当前正式事实、伪造 `source_payload` 或仅重建投影来更正该类语义错误。

#### Scenario: 解析修复后的历史事实重建
- **WHEN** 修复后的解析器从原始账单在独立 SQLite 数据库重建现金事实
- **THEN** 系统 MUST 在替换前验证事实数量、活动 identity、来源快照、记录类型、关系和投影，且失败时 MUST 保留原库和新库

### Requirement: 个人购汇的双向安全识别
系统 MUST 仅将同一工作区中负额 `fx_out` 与正额 `fx_in` 识别为个人购汇候选。双方 MUST 币种不同、来源键非空且无冲突并一致；候选查询 MUST 按来源键和上海业务日有界。自动确认 MUST 同时满足双方均有可靠钟点、时间差不超过 60 秒、正反两侧合法候选数均为 1、两端均未参与另一条已确认 `transfer_pair`。任一条件不成立时系统 MUST NOT 自动确认；候选歧义 MUST 以 `fx_out` 为锚点创建一条开放端 `pending_review`，并保存正反候选数、稳定候选业务行标识、来源状态、时间精度与阻断原因。

#### Scenario: 双向唯一的个人购汇自动确认
- **WHEN** 同一来源键下的 `CNY -47952.10 fx_out` 与 `USD +7000 fx_in` 均有可靠钟点且相差不超过 60 秒，并且双方各自仅有此合法候选
- **THEN** 系统 MUST 创建一条双边 `accepted transfer_pair(currency_exchange)`，并保存来源、时间精度、正反候选数和端点证据

#### Scenario: 反向不唯一不自动确认
- **WHEN** 一条 `fx_in` 同时是两条或更多合法 `fx_out` 的候选，即使每条 `fx_out` 各自只有该一个入账候选
- **THEN** 系统 MUST NOT 按扫描顺序确认任一关系，并 MUST 为被触发的 `fx_out` 建立可审计待配对关系

#### Scenario: 日期精度与空来源不自动确认
- **WHEN** 任一购汇端点只有日期精度，或任一端的来源键为空、冲突或不一致
- **THEN** 系统 MUST NOT 自动确认；日期精度的同业务日合法候选其 `time_delta_seconds` MUST 为 `null`，不得伪造为 `0`

### Requirement: 入账驱动的有界关系重评
系统 MUST 支持新导入的合法 `fx_in` 反向激活有界的既有 `fx_out` 锚点重评，但关系提议 MUST 始终由 `fx_out` 发出。增量检查和全量检查对相同活跃事实 MUST 得到等价的活跃关系集合，并保持幂等。

#### Scenario: 后到购汇入账补配历史转出
- **WHEN** `fx_out` 已存在且新导入唯一合法 `fx_in`
- **THEN** 系统 MUST 重评该 `fx_out` 并在满足自动确认条件时建立一条关系，而不得要求全量手工重跑

### Requirement: 功能需求基线
系统 MUST 保持以下迁移前功能需求；后续行为变更必须通过 OpenSpec change 明确修改。

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

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：对该能力进行修改时，验证结果 MUST 覆盖迁移后的功能需求清单。
- THEN 系统满足该条件，并保留可复核的验证证据。

### Requirement: 可度量验收结果
系统 MUST 继续满足以下可度量结果；它们是迁移后的验收回归基线。

- **SC-001**: `.ft/bills` 中可导入的 11,394 条现金流水全部拥有非空 `record_type`。
- **SC-002**: 全量重建库的标准记录类型分布可查询，`other=0`，且 `repayment=69`（以当前账单全集为基线）。
- **SC-003**: 代表性样例中支付宝 `信用借还` 和建行 `代理收款` 均为 `repayment`，微信 `信用卡还款` 也为 `repayment`。
- **SC-004**: SQLite 受影响测试、全量导入校验和 schema 检查通过；PostgreSQL 契约在环境可用时通过，否则记录补跑命令。
- **SC-005**: 关系表和关系配对逻辑在本 Feature 中无行为变化，后续 Feature 可按 `record_type` 作为来源分类闸门。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：运行该能力的验收矩阵时，结果 MUST 满足迁移后的成功标准。
- THEN 系统满足该条件，并保留可复核的验证证据。

## Source
完整迁移来源与原始验证证据：[024-normalized-cash-record-type/spec.md](../../changes/archive/2026-08-03-024-normalized-cash-record-type/legacy/024-normalized-cash-record-type/spec.md)。
本文件是 OpenSpec 的行为导向投影；实现细节、研究记录和历史任务保留在对应 change 的 `legacy/` 目录。
