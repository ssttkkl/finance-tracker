## Purpose
User description: "从 main 恢复投资事件领域模型与文件/手动导入。main 已有完整投资体系（SWAP/DEPOSIT/WITHDRAW/DIVIDEND/CHECKIN、多券商解析），但产品化迁移过程中仅保留了 DFZQ 单一 PoC；本 feature 将其恢复到 PostgreSQL-only + 双 DB + 关系架构中，覆盖多券商 PDF/CSV 解析与投资事件领域模型。买入卖出统一用 SWAP 表示（现金↔资产交换），手续费通过 commission 字段记录。 本能力的行为契约由迁移后的需求与场景持续维护。

## ADDED Requirements

### Requirement: Import DFZQ broker statement directly
系统 MUST 作为投资账户用户，我希望能够直接导入东方证券（DFZQ）的 PDF 对账单到 Finance Tracker，系统自动解析并创建投资事件记录，这样我就不需要手动逐笔输入交易或先转换为 CSV 预览文件。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: Restore full investment event types from main
系统 MUST 作为维护者，我希望当前分支支持完整投资事件类型（SWAP/DEPOSIT/WITHDRAW/DIVIDEND/CHECKIN），以便覆盖证券、加密货币、预测市场的完整生命周期操作，而不仅仅是当前的简化 PoC。SWAP 事件用于表示资产交换（包括买入=现金→资产、卖出=资产→现金、币币交换），手续费通过 commission 字段与 commission_asset 记录。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: Import exchange trades via API — **DEFERRED → 012**
> **Living Spec 2026-07-23**: Removed from 009 acceptance. Aligns with > `docs/productization-refactor-plan.md`: exchange/Polymarket **Connector auto-sync** > belongs to **`012-investment-connector-sync`**. Historical draft acceptance text > retained below only as handoff notes for 012 — **not required to complete 009**. 作为加密货币投资者，我希望能够通过 API 同步交易所（如 Binance、OKX）的历史交易记录到 Finance Tracker……（完整验收见未来 `openspec/specs/012-…`）。 **009 status**: Out of scope. CLI may still list `binance`/`okx` as reserved source names; MUST fail clearly if invoked until 012 implements them. ---

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: Import Polymarket activities — **DEFERRED → 012**
系统 MUST > **Living Spec 2026-07-23**: Activity/API **trade sync** → **012**. Polymarket **live quotes** > (gamma-api) → **011-asset-valuation-quote**, not this feature. 作为预测市场投资者，我希望能够同步 Polymarket 账户的交易活动……（完整验收见未来 `openspec/specs/012-…`）。 **009 status**: Out of scope. Reserved CLI source `polymarket` must not be claimed complete under 009. ---。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: Import Interactive BrokersActivity CSV
系统 MUST 作为投资账户用户，我希望能够直接导入 Interactive Brokers（盈透证券）Activity Statement 的 **Transaction History CSV** 到 Finance Tracker，系统按统一投资事件模型记账（SWAP/DEPOSIT/DIVIDEND/WITHDRAW/CHECKIN），并在导入后用对账单「期末现金」做现金 CHECKIN，以便美股/多币种证券账户与 DFZQ 共用同一导入与投影链路。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: Import Charles SchwabTransaction History CSV
系统 MUST 作为投资账户用户，我希望能够直接导入 Charles Schwab（嘉信理财）Transaction History CSV 到 Finance Tracker，系统按统一投资事件记账（SWAP/DEPOSIT/DIVIDEND/WITHDRAW/CHECKIN），并用最新一行「余额」做现金 CHECKIN，以便美股嘉信账户与 DFZQ/IBKR 共用同一导入链路。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 功能需求基线
系统 MUST 保持以下迁移前功能需求；后续行为变更必须通过 OpenSpec change 明确修改。

- - **FR-001**: 系统 MUST 支持通过 `ft import <file> --source dfzq --account <account_name>` 直接导入 DFZQ PDF 对账单，解析为投资事件并保存到 `investment_events` 表，整个过程（batch → raw_records → investment_events → snapshot update）在一个数据库事务中完成（参考 007 的导入契约）。
- - **FR-002**: 系统 MUST 使用 `raw_records.source_identity`（基于文件哈希与记录业务键如日期+ticker+金额组合）进行幂等去重；重复导入同一对账单时，系统 MUST 拒绝重复记录并返回幂等结果，不创建重复的 `investment_events` 或修改快照。
- - **FR-003**: 系统 MUST 支持完整投资事件类型：SWAP（资产交换，用于替代传统 BUY/SELL 操作）、DEPOSIT（入金）、WITHDRAW（出金）、DIVIDEND（分红）、CHECKIN（快照核对），每种事件类型 MUST 在 `domain/investment_projection.py` 中有明确的快照应用逻辑（apply_investment_event）。说明：买入表示为现金→资产 SWAP，卖出表示为资产→现金 SWAP；手续费通过 commission 字段记录而非独立 FEE action。
- - **FR-004**: 系统 MUST 恢复 main 分支的快照验证逻辑（`_validate_security_snapshot_finite`），在每次快照更新后检查：持仓数量非负、现金非 NaN/Infinity、总市值有限；验证失败时 MUST 拒绝整个导入事务并报告具体异常字段。
- - **FR-005**: 投资事件 MUST 链接 `raw_record_id`（外键到 `raw_records` 表），保持来源审计链；手动创建的投资事件（如 `ft stock buy` CLI 命令）的 `raw_record_id` 为 NULL，但仍需记录 created_at 与 revision。
- - **FR-006**: 系统 MUST 采用单行 SWAP 模式（保留当前分支的 from/to 统一 schema），SWAP 事件用于替代传统 BUY/SELL 操作（买入视为现金→资产 SWAP，卖出视为资产→现金 SWAP）。释放成本通过快照中保留的成本基础信息与 from_amount 计算。
- - **FR-007**: 系统 MUST 采用 commission 字段处理手续费，commission 作为所有交易事件（BUY/SELL/SWAP/DEPOSIT/WITHDRAW）的附加属性。系统 MUST 提供 commission_asset 字段标识手续费单位（可能与交易主币种不同，如用 BNB 支付手续费）。本 feature 不引入独立 FEE action；独立费用（如提币费、账户管理费）可在后续 feature 中按需扩展。
- - **FR-008** *(DEFERRED → 012)*: ~~交易所 ccxt 交易同步~~ — **not required for 009 completion**. Superseded by productization plan: implement under `012-investment-connector-sync`.
- - **FR-009** *(DEFERRED → 012)*: ~~Polymarket Activity API 交易同步~~ — **not required for 009**. Quotes/valuation for Polymarket markets → **011**; activity import → **012**.
- - **FR-010** *(DEFERRED → 012)*: ~~交易所/Polymarket API 凭据存储~~ — **not required for 009**. Documented long-term under 012.
- - **FR-011**: 双后端（PostgreSQL 与 SQLite）MUST 对相同**文件**导入输入（DFZQ PDF / IBKR CSV / Schwab CSV）产生等价的投资事件（数量、金额精度、ticker、快照持仓一致），满足 Constitution IV 的行为等价要求；schema 迁移、事务原子性、幂等判断、快照验证逻辑 MUST 在两个后端保持一致。
- - **FR-012**: 系统 MUST 在 DFZQ 解析失败时（如券商格式变化、PDF 损坏、PDF 处理工具缺失）报告具体失败位置（页码、行号）与原始文本片段，不得静默跳过或猜测数据。
- - **FR-013**: 系统 MUST 拒绝将投资对账单导入非投资账户（account.type 不为 'security' 或 'crypto'），并明确提示账户类型不匹配错误。
- - **FR-014**: 系统 MUST 支持通过 `ft import <file> --source ibkr --account <account_name>` 导入 Interactive Brokers Activity Statement 风格的 Transaction History CSV，解析为投资事件并写入 `investment_events`，batch → raw_records → events → snapshot 在同一事务中完成；`raw_records.source_type` MUST 为 `ibkr_csv`。
- - **FR-015**: 对 IBKR 权益「买/卖」，系统 MUST 采用 **总额 + commission** 费用合同：SWAP 现金部分 = `abs(总额)`，`commission = abs(佣金)`（空佣金视为 0），`commission_asset` = 账户/总结基础货币小写 ticker；MUST NOT 在现金部分已使用 `abs(净额)` 时再写入非零 commission（双计费禁止）。投影后单笔现金影响 MUST 等于该行 `净额` 的绝对值方向一致结果。
- - **FR-016**: IBKR 非权益类型映射 MUST 为：`存款`→`deposit`，`股息`→`dividend`（现金分红），`外国预扣税`→`withdraw`，`借方利息`→`withdraw`，`外汇交易组成部分`→`swap`。FX 规则：代码 `BASE.QUOTE`（如 `USD.HKD`）；左侧资产数量 = abs(数量)，右侧资产数量 = abs(数量)×价格（Price Currency）；买卖方向由数量/净额符号决定（买左/卖右或相反须与样本一致并单测锁定）；若该行 `净额 == 总额`（佣金已嵌在总额内），MUST `commission=0` 且佣金写入 note——**不得**再对 commission 字段扣减。无法解析 pair 或缺少数量/价格 MUST fail-closed。未知 `交易类型` MUST fail-closed。验收以 base 货币现金 CHECKIN 为准；非 base 货币仓位（如 hkd）允许非零残差，不得为对齐而发明金额。
- - **FR-017**: IBKR 导入 MUST 在流水事件之后追加 **一条** base 货币现金 CHECKIN，金额取自 CSV「总结」`期末现金`；本 CSV 无持仓成本表时 MUST NOT 发明持仓 CHECKIN。`source_identity` MUST 使用稳定业务键（见 research.md `ibkr:…` 配方）。
- - **FR-018**: 系统 MUST 支持通过 `ft import <file> --source schwab --account <account_name>` 导入 Charles Schwab Transaction History 风格 CSV，解析为投资事件并写入 `investment_events`，batch → raw_records → events → snapshot 在同一事务中完成；`raw_records.source_type` MUST 为 `schwab_csv`。
- - **FR-019**: 对 Schwab TRD，系统 MUST 采用 **金额 + 杂费** 费用合同：SWAP 现金部分 = `abs(金额)`；`commission = abs(杂费) + abs(佣金)`（空/`-` 视为 0），`commission_asset = usd`（或账户 base）；MUST NOT 以 `abs(金额+杂费)` 为现金部分同时写入非零 commission。投影后单笔现金影响 MUST 等于该行 `金额 + 杂费`（与余额差分一致）。
- - **FR-020**: Schwab 非 TRD 映射 MUST 为：`WIN` 入金→`deposit`；`DOI` 金额>0（股息）→`dividend`，金额<0（利息等）→`withdraw`；`JRN` 金额<0→`withdraw`，金额>0 或说明含 REFUND→`deposit`。未知 `类型` 或 TRD 说明无法解析 BOT/SOLD MUST fail-closed。
- - **FR-021**: Schwab 导入 MUST 在流水后追加 **一条** USD 现金 CHECKIN，金额 = 文件中按时间最新一行的 `余额`；无持仓表时 MUST NOT 发明持仓 CHECKIN。`source_identity` 优先 `schwab:{参照号码}:{类型}`（见 research.md）。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：对该能力进行修改时，验证结果 MUST 覆盖迁移后的功能需求清单。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 可度量验收结果
系统 MUST 继续满足以下可度量结果；它们是迁移后的验收回归基线。

- - **SC-001**: 用户能够在 5 分钟内完成 DFZQ PDF 对账单的首次导入（包括账户创建、文件上传、解析、验证、快照生成），系统自动识别交易类型并更新持仓，无需手动逐笔输入或 CSV 预览中转。
- - **SC-002**: 双后端（PostgreSQL 与 SQLite）对相同 DFZQ 对账单的导入结果 100% 一致（投资事件数量、金额、ticker、快照持仓、幂等判断结果），通过自动化契约测试矩阵验证（参考 002 双数据库运行时的测试策略）。
- - **SC-003**: **009 完成定义** = 文件导入源 **DFZQ + IBKR + Schwab** 可用，且 US2 事件回放/校验达标，双后端契约对上述文件源通过。不得以“未做 ccxt/Polymarket”判定 009 未完成。ccxt/Polymarket **同步** 归 **012**；Polymarket **取价** 归 **011**。
- - **SC-008**: 用 `tests/fixtures/ibkr/transactions_1y_sample.csv` 导入后：权益费双计 = 0；快照 base 现金在 CHECKIN 后等于 总结.期末现金（允许 ≤0.01 仅当样本含科学计数法尾差时文档化）；开放持仓股数与离线回放一致；重复导入 count=0。
- - **SC-009**: 用 `tests/fixtures/schwab/transaction_history_sample.csv` 导入后：权益费双计 = 0；快照 USD 现金 = 最新行余额 `2865.36`；开放持仓 AVGO 7、MSFT 5；重复导入 count=0。
- - **SC-004**: 重复导入相同对账单或交易记录时，系统 100% 幂等（通过 source_identity 去重），不创建重复的投资事件，不修改已有快照，用户可安全重试导入而不担心重复记账。
- - **SC-005**: 投资事件回放逻辑（apply_investment_event）覆盖 5 种事件类型（SWAP/DEPOSIT/WITHDRAW/DIVIDEND/CHECKIN），每种类型有单元测试验证快照计算正确性（持仓增减、现金变动、手续费扣除）。说明：SWAP 统一表示资产交换（包括买入、卖出、币币交换），commission 字段处理手续费。
- - **SC-006**: 快照验证逻辑（_validate_security_snapshot_finite）在每次导入后运行，能够检测并拒绝异常快照（负数持仓、NaN 现金、Infinity 市值），防止数据损坏传播，100% 覆盖边界情况的集成测试通过。
- - **SC-007**: 导入失败时（解析错误、验证失败、数据库约束冲突），系统事务回滚，不发布部分事实（no partial facts），用户收到明确错误消息（包含失败位置、原始数据片段、建议修复方案），可操作性评分 ≥ 4/5（用户评估）。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：运行该能力的验收矩阵时，结果 MUST 满足迁移后的成功标准。
- THEN 系统满足该条件，并保留可复核的验证证据。
