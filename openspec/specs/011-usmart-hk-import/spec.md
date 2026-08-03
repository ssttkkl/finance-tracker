# uSmart HK (盈立证券香港) Monthly Statement Import

## Purpose
User description: "盈立证券香港（usmart-hk）月结单 PDF 导入器；密码保护 PDF；字段普查基于真实 2026-06 月结单。 本能力的行为契约由迁移后的需求与场景持续维护。

## Requirements

### Requirement: Import uSmart HK monthly PDF
系统 MUST 作为持有盈立证券香港（uSmart Securities Limited）保证金账户的用户，我希望能用 `ft import` 直接导入加密月结单 PDF，系统自动解析交易明细、非交易资金与期末结余/持仓核对，写入统一投资事件并更新证券账户快照，这样我不必手录美股/港股成交与费用。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: Multi-market header and empty sections
系统 MUST 作为同一盈立账户同时持有港股/美股（及可能 A 股通）的用户，我希望月结单中某一市场无交易或字段为 `--` 时导入仍成功，有数据的市场照常记账与 CHECKIN。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: Non-trade cash movements
系统 MUST 作为用户，我希望月结单「资金出入」中的入金/出金/IPO 退款/融资利息等非成交资金被正确记账；**换汇**记为现金↔现金 `swap`；**转账/日内融调拨**只用既有 `withdraw` / `deposit`（按金额符号），不引入新事件类型。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 功能需求基线
系统 MUST 保持以下迁移前功能需求；后续行为变更必须通过 OpenSpec change 明确修改。

- - **FR-001**: 系统 MUST 支持通过
- - **FR-002**: `raw_records.source_type` MUST 为 `usmart_hk_pdf`。幂等 MUST 遵循 010：仅 `source_type` + `source_identity`（workspace 内）决定是否已入账；MUST NOT 仅因 `source_digest` 已有完成 batch 而跳过 novel 行。
- - **FR-003**: 解密与文本提取 MUST 使用项目既有安全 PDF 路径（密码经 password-file / 临时文件，不进入 argv 明文策略与 DFZQ 一致）；提取编码使用 UTF-8 replace，禁止将 PDF 当文本直接打开。
- - **FR-004**: 解析 MUST 覆盖并区分至少以下区段：页眉市场汇总、交易明细（订单组）、持仓明细、资金出入；证券提存为空 MUST 可接受。区段标题/CJK 兼容字形变体（如 `⾦`/`金`）MUST 可识别或在预处理中规范化。
- - **FR-005**: 交易明细 MUST 以 **订单组** 为记账单元：同一组内多笔 fill 合并数量与成交金额；组级字段「交易金额」「变动金额合计」及费用明细用于费用合同。MUST NOT 默认按 fill 各记一笔并分摊费用（除非未来 Living Spec 明确变更）。
- - **FR-006**: 权益买卖费用合同 MUST 为 **gross + commission**（对齐 IBKR 语义，非 DFZQ peel；**分侧**）：
- - **FR-007**: 证券 ticker 规范化 MUST 稳定可测：美股 MUST 加 `.us`（如 `mrvl.us`）；港股 MUST 加 `.hk`（如 `00700.hk`）；与 IBKR/Schwab/DFZQ 共用 `ticker_normalize` 约定；中文名可进 note。市场字段（美股/港股/A股通）MUST 进入 payload 或 note 以便审计。
- - **FR-008**: 资金出入映射 MUST 至少包括：
- - **FR-009**: 导入 MUST 在流水后追加：
- - **FR-010**: `source_identity` MUST 稳定、跨文件一致，推荐配方（实施锁定于 research.md）：
- - **FR-011**: 投资账户类型门禁、快照有限性验证、`raw_record_id` 审计链、单行 SWAP + commission 模型 MUST 复用 009；本 feature **不**引入独立 FEE/BUY/SELL/`transfer` 等新 action。
- - **FR-012**: 双后端（PostgreSQL 与 SQLite）对相同 uSmart 夹具输入 MUST 产生等价投资结果（Constitution IV）。
- - **FR-013**: 单元测试 MUST 使用 **去标识** 文本夹具（不得提交真实 PDF、真实姓名、真实地址、真实完整账号）；本地 `exports/` 仅开发校准。
- - **FR-014**: 解析/映射失败 MUST 事务回滚、无 partial facts，错误信息含区段/上下文片段与建议（密码、工具、格式）。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：对该能力进行修改时，验证结果 MUST 覆盖迁移后的功能需求清单。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 可度量验收结果
系统 MUST 继续满足以下可度量结果；它们是迁移后的验收回归基线。

- - **SC-001**: 用户能在 5 分钟内完成首次 uSmart HK 月结单导入（建 security 账户、password-file、import、核对），无需手录成交。
- - **SC-002**: 校准样本（2026-06 结构）导入后：USD 期末现金 = **4750.17**，HKD 期末现金 = **2021.09**（CHECKIN 后）；持仓股数 **00700.hk=100、mrvl.us=3、spcx.us=5**；权益费双计笔数 = **0**。
- - **SC-003**: 同一业务行重复导入 novel 事件数 = **0**；重叠文件仅追加新 identity 行（010）。
- - **SC-004**: PostgreSQL 与 SQLite 对同一去标识夹具导入结果 100% 业务字段一致（事件数、金额、ticker、CHECKIN）。
- - **SC-005**: 交易订单组 100% 有对应 SWAP 或显式失败；无未计数的 silent skip。
- - **SC-006**: 资金出入中成交镜像行 100% 不产生第二套事件；非交易映射表内标志 100% 按 FR-008 入账（换汇=swap，转账/日内融=withdraw|deposit，出金=withdraw）。
- - **SC-007**: 导入失败路径（错密码、未知标志、费用不平衡、换汇无法配对、缺工具）100% 回滚且错误可操作（用户可据消息修复后重试）。
- - **SC-008**: 校准样本中换汇配对后 USD/HKD 在 CHECKIN 后等于页眉期末结余；「转入到日内融账户」记为 withdraw（样本负额），无 `transfer` action、无虚构余额项 ticker。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：运行该能力的验收矩阵时，结果 MUST 满足迁移后的成功标准。
- THEN 系统满足该条件，并保留可复核的验证证据。

## Source
完整迁移来源与原始验证证据：[011-usmart-hk-import/spec.md](../../changes/archive/2026-08-01-011-usmart-hk-import/legacy/011-usmart-hk-import/spec.md)。
本文件是 OpenSpec 的行为导向投影；实现细节、研究记录和历史任务保留在对应 change 的 `legacy/` 目录。
