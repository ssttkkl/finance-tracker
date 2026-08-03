# 投资连接器同步

## Purpose
Phase 1 投资连接器同步（investment-connector-sync）。通过 exchange/Polymarket 等 Connector 自动拉取私有交易历史并映射到统一投资事件模型。 本能力的行为契约由迁移后的需求与场景持续维护。

## Requirements

### Requirement: 同步加密交易所交易历史🎯 主交付
系统 MUST 作为加密货币投资者，我希望通过 CLI 命令将交易所（如 Binance、Kraken、OKX）的私有交易与资金账本活动自动拉取并导入 Finance Tracker，系统保留交易所 ID 作为幂等键并映射为统一投资事件，这样我不再需要手动导出 CSV 并逐个导入。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 同步 Polymarket 交易活动
系统 MUST 作为预测市场投资者，我希望通过 CLI 命令将 Polymarket 的公开交易活动自动拉取并导入 Finance Tracker，系统将 BUY/SELL 映射为 swap（`pm:<slug>:<yes|no>` ↔ USD），保留 Activity ID 作为幂等键。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 凭据配置与安全
系统 MUST 作为用户，我希望在一个受保护的本地配置文件中管理交易所 API 密钥和 Polymarket 钱包地址，系统在同步前验证凭据存在且格式正确，不会将密钥写入日志或仓库文件。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 增量游标优化
系统 MUST 作为高频交易用户，我希望系统记住上次同步位置，下次同步时只拉取新交易，而不是每次全量分页，以减少 API 调用次数和等待时间。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 及时查看已导入持仓
系统 MUST 作为用户，我希望运行 `ft stock list` 后能在可预期的时间内看到已导入的全部非零持仓，即使某个行情供应商超时、返回空数据或打印诊断信息，也不能让终端长期空白或隐藏账本持仓。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 校准 Polymarket 当前现金
系统 MUST 作为 Polymarket 用户，我希望同步导入成交和派息后读取 funder 地址当前的 pUSD 余额并校准 USD 现金，这样账本不会因 Activity API 未包含入金而显示错误的负现金。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 功能需求基线
系统 MUST 保持以下迁移前功能需求；后续行为变更必须通过 OpenSpec change 明确修改。

- - **FR-001**: 系统 MUST 提供 `ConnectorPort` 接口（domain port），定义 `fetch_trades(cursor?) → (events, next_cursor)` 签名，使不同数据源的 connector 可独立实现和测试。
- - **FR-002**: 系统 MUST 提供 `CcxtExchangeConnector` adapter，通过 ccxt 的 `fetch_my_trades`（全量所有交易对）和 `fetch_ledger`（全量资金账本）拉取私有活动，支持首批 Binance、Kraken、OKX。任一 provider 不支持其中一个必需接口、任一分页失败或任何返回记录无法安全映射时，MUST fail-closed。
- - **FR-003**: ccxt trade MUST 映射为投资事件：`BUY(base/quote)` → `swap(from=quote, to=base)`，`SELL` 反向；`commission`/`commission_asset` 保留原始扣费币种与金额。缺失费用表示 0；已出现但无法解析的费用或非零费用缺少币种 MUST 报错，禁止归零。
- - **FR-004**: 系统 MUST 提供 `PolymarketConnector` adapter，通过公开 Activity API 分页拉取活动：`TRADE` 映射为 `swap(pm:<slug>:<outcome> ↔ usd)`，`REDEEM` 映射为 `swap(pm:<slug>:<outcome> → usd)`，`YIELD` 映射为 USD `dividend`；其他活动类型跳过。
- - **FR-005**: 每条同步记录 MUST 携带 `source_type`（如 `binance_api`/`kraken_api`/`polymarket_api`）和 `record_id`（交易所 trade ID / Polymarket activity ID），复用 `010` 的幂等机制。
- - **FR-006**: 同步 MUST 在单个 UnitOfWork 事务内完成：幂等检查 → 映射 → 投影 → 快照验证 → 游标更新 → 提交。任一条交易或 ledger 映射、校验或分页异常时 MUST 使本次同步整体 fail-closed（回滚全部事件、快照和游标并报告异常条目）。对于大量记录，MUST 按可配置大小（默认 500）分块处理以控制内存和校验粒度，但分块不得产生部分提交。
- - **FR-007**: 每条同步事件 MUST 在 `source_payload` 中保存 API 原始响应的关键字段（trade dict / activity dict 的 JSON），确保来源可追溯。
- - **FR-008**: 系统 MUST 提供 CLI 命令 `ft sync --source <provider> --account <name>` 用于手动触发同步。
- - **FR-009**: 凭据 MUST 从 `~/.ft/credentials.yaml` 加载，按 provider 分段存储；文件权限 MUST 自动设为 `0600`，且 MUST 确保在 `~/.ft/.gitignore` 中。
- - **FR-010**: 凭据缺失或格式错误时，MUST 给出包含示例配置的可操作错误信息，MUST NOT 在错误信息、日志或仓库文件中暴露密钥值。
- - **FR-011**: API 调用遇到可重试错误（网络超时、429）时，MUST 指数退避重试（最多 3 次）；超过重试次数后 MUST 报告本次已拉取但未提交的条目数与失败点，且本次已同步条目数为 0。
- - **FR-012**: 同步的投资事件 MUST 通过现有 `apply_investment_event` 投影更新快照，并通过 `validate_investment_snapshot` 校验；校验失败时 MUST 回滚该批事务。
- - **FR-013**: 系统 MUST 在 PostgreSQL 和 SQLite 上产生等价的同步结果（事件数量、金额精度、ticker、快照持仓）。
- - **FR-014**: Polymarket connector 凭据仅需公开 `wallet` 地址或 `proxy_wallet` 地址；若提供 login 地址，系统 MUST 自动解析 proxy wallet。
- - **FR-015**: 增量游标 MUST 持久化到专用 `sync_cursors` 表（复合键 `workspace_id` + `account_id` + `source_type` → `cursor_value` + `updated_at`），同步成功后更新，下次同步默认从游标位置继续。交易与 ledger 都必须从同一时间游标拉取；任一端失败不得更新游标。
- - **FR-016**: CLI MUST 支持 `--full` 参数强制全量重新拉取，忽略已有游标。
- - **FR-017**: 本 feature MUST NOT 引入定时调度、Worker、Web UI、加密凭据存储或通用 Connector 平台层。
- - **FR-018**: Ticker 规范化 MUST 复用现有 `schema.py` 的 `CRYPTO_IDS` 映射和 `importers/ticker_normalize.py` 的逻辑，确保同一资产在文件导入和 API 同步中使用相同 ticker。
- - **FR-019**: `ft stock list` MUST 在固定的、可测试的总行情读取预算内渲染所有账本中的非零持仓；预算耗尽、单项超时、空行情或 provider 异常只使该项报价为 partial/N/A，MUST NOT 阻塞、隐藏其他持仓或让命令以未结构化第三方输出结束。该读取策略不得写入账本或改变 SQLite/PostgreSQL 的持仓集合。
- - **FR-020**: `PolymarketConnector` MUST 在 Activity API 之外读取 funder/proxy 地址当前 Polygon pUSD ERC-20 `balanceOf`；pUSD 固定为 CLOB V2 合约 `0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB`、6 decimals。它 MUST 映射为 USD `checkin`（`to_ticker=usd`、`to_amount=balance`），不得扫描 `Transfer` 历史、导入或推断入金/出金。
- - **FR-021**: 当前 pUSD checkin MUST 在读取到的确认 block 上完成，以 `checkin:<block_number>` 作稳定 record ID，并在 `source_payload` 保留 token、wallet、balance_base_units、block_number、block_timestamp。相同 block 重跑幂等，后续 block 的 checkin 使用既有替换语义更新 USD 现金。
- - **FR-022**: Activity API、当前 block、block timestamp 或 `balanceOf` 读取失败 MUST fail-closed；Activity、checkin、快照和游标均不得部分写入。现有 Polymarket 时间游标保持纯 Activity timestamp 格式，无需链上复合游标。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：对该能力进行修改时，验证结果 MUST 覆盖迁移后的功能需求清单。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 可度量验收结果
系统 MUST 继续满足以下可度量结果；它们是迁移后的验收回归基线。

- - **SC-001**: 用户在 3 分钟内完成首次 exchange 同步配置（凭据 + 命令执行）并看到导入结果。
- - **SC-002**: 同步 1000 笔交易的端到端耗时不超过 30 秒（不含网络延迟）。
- - **SC-003**: 重复同步同一数据源时，已存在交易、ledger 主事件和 ledger fee 事件 100% 被幂等跳过，无重复事件。
- - **SC-004**: 同步后的投资事件可通过 `017` 估值系统直接获得组合市值与状态。
- - **SC-005**: 凭据错误或 API 异常时，错误信息足够用户自行诊断和修复，无需查看源码。
- - **SC-006**: PostgreSQL 与 SQLite 上同一 mock 数据的同步结果完全等价（事件数、金额、ticker、快照）。
- - **SC-007**: 不引入定时调度、Web UI、加密存储或 Connector 平台层；范围审查 0 越界。
- - **SC-008**: 对含至少 16 个预测市场合约的组合，`ft stock list` 在 5 秒内产生包含全部非零持仓的首个且完整的 CLI 表格；行情未取得的项目明确显示为 N/A/partial，且无第三方诊断泄漏。
- - **SC-009**: SQLite 和真实 PostgreSQL 的同一 mock 数据中，pUSD checkin 的 6-decimal 金额、source payload、record ID 和同步后 USD 快照 100% 一致；同一 block 重跑不新增事件。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：运行该能力的验收矩阵时，结果 MUST 满足迁移后的成功标准。
- THEN 系统满足该条件，并保留可复核的验证证据。

## Source
完整迁移来源与原始验证证据：[018-investment-connector-sync/spec.md](../../changes/archive/2026-08-01-018-investment-connector-sync/legacy/018-investment-connector-sync/spec.md)。
本文件是 OpenSpec 的行为导向投影；实现细节、研究记录和历史任务保留在对应 change 的 `legacy/` 目录。
