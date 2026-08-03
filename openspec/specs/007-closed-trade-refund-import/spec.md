# Import No-Skip, Raw Payload, Unified Relation Scan

## Purpose
1. **导入默认 no-skip**：支持账单源的源交易明细默认必须导入；禁止**无文档、无计数**的静默丢行与静默失败。 本能力的行为契约由迁移后的需求与场景持续维护。

## Requirements

### Requirement: 任何账单条目不得被静默跳过
系统 MUST 作为用户，导入任意支持账单时，每一条交易明细要么进入账本，要么白名单跳过可计数，要么导入明确失败。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 导入保留原始字段供扫描
系统 MUST 作为系统，导入后每条正式事实可追溯到 RawRecord，且 payload 含该源配对所需原始字段。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 统一扫描 Phase A 平台退款
系统 MUST 作为用户，我先导入全部账单，再跑一次关系检查；支付宝/微信退款在 Phase A 按硬键建立 `refund_offset`，无需导入期写关系。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 扫描顺序 A→B→C
系统 MUST 作为用户，系统先核销平台退款，再做支付镜像，再处理银行退货与转账，减少错误 mirror。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 已支付关单入库
系统 MUST 保持迁移前该用户故事定义的可观察行为。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 关单退款不配重拍单
系统 MUST 保持迁移前该用户故事定义的可观察行为。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 微信双行与拆退
系统 MUST 保持迁移前该用户故事定义的可观察行为。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 银行退货在 Phase D
系统 MUST 保持迁移前该用户故事定义的可观察行为。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 免与转账仍由扫描
系统 MUST 保持迁移前该用户故事定义的可观察行为。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 白名单跳过可审计
系统 MUST 保持迁移前该用户故事定义的可观察行为。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 功能需求基线
系统 MUST 保持以下迁移前功能需求；后续行为变更必须通过 OpenSpec change 明确修改。

- - **FR-001**: 源交易明细默认 MUST 接纳；白名单跳过见 FR-008a/c。
- - **FR-002**: 成功批次：`源交易行 = 新发布 + 幂等 + 白名单跳过`。
- - **FR-003**: 解析/金额非法 MUST 失败关闭。
- - **FR-004**: Mapping 未命中 MUST 失败关闭。
- - **FR-005**: 同 source identity 活跃事实 → 幂等命中。
- - **FR-006**: 导入结果 MUST 暴露源行/新发布/幂等/skipped_unpaid_closed/skipped_failed_repay/失败。
- - **FR-007**: 适用于支付宝、微信、工行信用卡/借记卡、建行借记卡、东方证券及同级源。
- - **FR-008**: 已支付向关单 MUST 发布正式事实。
- - **FR-009**: MUST NOT 新增必选 `funding_status`。
- - **FR-010**: 已支付关单负向、退款正向；余额为活跃事实汇总。
- - **FR-011**: 0 元非未支付关闭 MUST 导入。
- - **FR-012**: 每条成功发布的正式事实 MUST 关联 `raw_records` 行；`payload` MUST 为 JSON 对象且含该 `source_type` 的**必填原始键**（见附录 Raw Payload 契约）。
- - **FR-013**: 支付宝 payload MUST 至少可恢复：`platform_status`（或交易状态）、完整 `txn_id`（交易订单号）、商家订单号（若有）、收/支、付款方式、商品说明、金额、时间。
- - **FR-014**: 微信 payload MUST 至少可恢复：当前状态、交易类型、收/支、支付方式、金额、时间、交易单号、商户单号（若有）、对手方/描述。
- - **FR-015**: 银行源 payload MUST 至少可恢复：足以识别消费退货/通道的原文（建行 summary/location；工行 raw 对方/摘要/退货标记等）。
- - **FR-016**: Import MUST NOT 写入 `refund_offset` / `payment_mirror` / `transfer_pair`（或任何替代「权威核销」的 offset 改写）。
- - **FR-017**: Import MUST NOT 将消费/退款 `amount` 改为净额。
- - **FR-018**: `relations check` MUST 按顺序执行：
- - **FR-019**: Phase A 支付宝匹配 MUST 使用：`==` / `startswith(origin+"_")` / `startswith(origin+"*")`；唯一才 auto-accept；标题不得优先；不得配错重拍单 B。
- - **FR-020**: Phase A 微信匹配 MUST 使用 FR-029 优先级（见下，规则同前，触发在扫描）；MUST NOT 用支付宝 txn 前缀。
- - **FR-021**: Phase A 免押→解冻：优先订单/商户键；否则同日状态对+唯一；多候选 open-leg。
- - **FR-022**: Phase D 银行退货：同卡+商户簇+金额/剩余+时间；**禁止** `refund_desc_fallback` 级 auto-accept（最多 pending/open-leg）。
- - **FR-023**: 已有活跃关系 MUST 跳过重复自动推荐。
- - **FR-024**: MUST NOT 用「退款早于任意成功消费 N 小时」替代原单或订单键。
- - **FR-025**: 核销只追加关系，不删事实、不改金额。
- - **FR-027**: 微信明细默认 MUST 导入；禁止无注释 silent continue。
- - **FR-028**: 原消费流水/退款流水识别同前（已全额退款、已退款(¥x)、对方已退还；收入退款状态/类型）。
- - **FR-029**: 匹配优先级：红包 mer=txn → 全额等额同 pay → 部分嵌 x==收入 → residual 拆退 → 转账退还；时间窗须覆盖 ≥30 天真部分退。
- - **FR-030**: 唯一（或 residual 明确同原单）→ Phase A 写 `refund_offset`（rule 如 `scan.wechat.*`）。
- - **FR-031**: 多原单 → open-leg/pending；禁止误判为缺少对侧流水。
- - **FR-032**: Phase B/C 不得破坏已 accepted 的 Phase A 边；跨源 mirror/transfer 仍可进行。
- - **FR-040**: Phase C MUST 在 Phase B 之后、Phase D 之前执行；MUST NOT 与银行退货混为同一无序阶段。
- - **FR-041**: Phase C MUST 两阶段：**(1) 源生分类闸门**；（2）池内精细配对。MUST NOT 仅因含「转账」二字进候选。详见 `attachments/transfer-source-taxonomy.md`。
- - **FR-042**: 闸门至少覆盖：支付宝提现/转账到银行卡/余利宝转出到卡/花呗还款/月付还款；微信提现已到账×零钱提现、支付成功×信用卡还款；建行 summary∈{转账支取,无卡自助,银联入账,支付机构提现,转账存入,电子汇入,银转证,证转银}（**不含** 仅「还款」+商户名）；工行提现入账与真·信用卡还款入账。
- - **FR-043**: transfer 候选排除 MUST **分层**：
- - **FR-044**: 精细优先级（唯一 auto）：
- - **FR-045**: 「提现」MUST 为强信号。
- - **FR-046**: Import MUST NOT 写 `transfer_pair`。
- - **FR-047**: 多候选 → pending/open-leg，不静默。
- - **FR-048**: 微信提现类事实：
- - **FR-051**: 支付宝 **余额宝**、**余利宝** MUST 作为独立账户名（mapping/accounts）。
- - **FR-050**: 微信 `txn_type=零钱提现`（及状态提现已到账）MUST：
- - **FR-049**: 建行 summary 仅为「还款」且 counterparty 呈商户消费态 MUST NOT 进入 credit_repayment auto；微信信用卡还款 MUST NOT 配非还款入账。
- - **FR-033**: Decimal 金额。
- - **FR-034**: workspace 隔离与幂等。
- - **FR-035**: PG/SQLite 用户可见等价（接纳集合、payload 契约字段、关系、错误合同）。
- - **FR-036**: 正式事实不可变、逻辑删除、raw→formal 链继续适用。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：对该能力进行修改时，验证结果 MUST 覆盖迁移后的功能需求清单。
- THEN 系统满足该条件，并保留可复核的验证证据。

## Source
完整迁移来源与原始验证证据：[007-closed-trade-refund-import/spec.md](../../changes/archive/2026-08-01-007-closed-trade-refund-import/legacy/007-closed-trade-refund-import/spec.md)。
本文件是 OpenSpec 的行为导向投影；实现细节、研究记录和历史任务保留在对应 change 的 `legacy/` 目录。
