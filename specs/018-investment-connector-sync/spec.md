# Feature Specification: 投资连接器同步

**Feature Branch**: `018-investment-connector-sync`

**Created**: 2026-07-26

**Status**: Draft

**Input**: Phase 1 投资连接器同步（investment-connector-sync）。通过 exchange/Polymarket 等 Connector 自动拉取私有交易历史并映射到统一投资事件模型。

**Extends / 关系**:

- **Implements**: 路线图 Phase 1「`investment-connector-sync`」（历史编号 012）。
- **Extends**: `009` 投资事件模型（swap/deposit/withdraw/dividend/checkin）、`010` 行级幂等（`source_type` × `record_id`）。
- **Consumes**: `017` 统一估值（ValuationService）— 同步后用户可立即通过估值查看组合市值。
- **Does not supersede**: 文件导入链（009/011/012-cost/013-cash/014-015-016 仍是文件导入的正式路径）。
- **Non-goals**: 定时调度/Worker、Web UI、Secret vault/加密存储、通用 Connector 平台层、行情/FX 自动更新（已有 017）。

## Context

Phase 1 文件导入链已完成（DFZQ/IBKR/Schwab/uSmart），行级幂等（`source_type` × `record_id`）与投影机制已就绪。CLI 已预留 `binance`/`okx`/`polymarket` 源名但未接线。

旧 worktrees 有基于 CSV 文件架构的 ccxt + Polymarket Activity API 同步实现（`crypto-account`/`p0-polymarket-sync`），验证了映射规则与分页逻辑，但依赖已删除的 CSV 账本和 `credentials.yaml`，不可直接复用。

本 feature 将交易所与预测市场 API 同步接入当前的 PostgreSQL/SQLite 双数据库架构，复用已有 Application Service、投资事件模型和行幂等机制，交付手动触发的 CLI 命令。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 同步加密交易所交易历史 (Priority: P1) 🎯 主交付

作为加密货币投资者，我希望通过 CLI 命令将交易所（如 Binance、Kraken、OKX）的私有交易与资金账本活动自动拉取并导入 Finance Tracker，系统保留交易所 ID 作为幂等键并映射为统一投资事件，这样我不再需要手动导出 CSV 并逐个导入。

**Why this priority**: 加密交易所是最常见的 API 同步场景，ccxt 库已作为依赖安装，旧 worktree 验证了映射规则可行。文件导出对高频交易者不可持续，API 同步是产品刚需。

**Independent Test**: 使用 mock ccxt client（或 sandbox 模式）模拟 `fetch_my_trades` 与 `fetch_ledger` 返回，对含 BUY/SELL、入金、出金、奖励、staking、内部转账和独立手续费的列表执行 `ft sync --source binance --account 币安`，验证产生正确投资事件、幂等、快照一致；未知或错误记录必须使整次同步失败。

**Acceptance Scenarios**:

1. **Given** 用户已配置 Binance API 凭据且有 `crypto` 类型账户「币安」，**When** 执行 `ft sync --source binance --account 币安`，**Then** 系统通过 ccxt `fetch_my_trades` 分页拉取全部交易，每笔 BUY（以 USDT 报价）映射为 `swap(from=usdt, to=<base>, from_amount=cost, to_amount=amount)`，SELL 反向；`source_type=binance_api`，`record_id=<trade_id>`；投影后快照与预期一致；事务提交。
2. **Given** 交易对为 crypto-to-crypto（如 ETH/BTC），**When** 同步，**Then** 映射为 `swap(from=btc, to=eth)` 或反向，`commission_asset` 为实际扣费币种（如 BNB），金额精确。
3. **Given** 用户再次执行同一命令，**When** 系统检查 `source_type=binance_api` × `record_id=<trade_id>`，**Then** 已存在的交易跳过，仅导入新交易；结果幂等。
4. **Given** API 返回无新交易或空列表，**When** 同步，**Then** 报告「无新交易」，不修改快照。
5. **Given** 在 PostgreSQL 与 SQLite 上用同一 mock 数据同步，**When** 比较结果，**Then** 事件数量、金额（Decimal）、ticker、快照持仓一致。
6. **Given** 交易所返回非成交 ledger 活动，**When** 同步，**Then** `deposit` 映射为 `deposit`，`withdrawal` 映射为 `withdraw`，`staking`/`reward`/`credit`/`rollover` 映射为 `dividend`，账户内 `transfer`/`derivativescrossexchangetransfer` 映射为不改变持仓的 `transfer` 审计事件；每条均使用 ledger entry ID 作为幂等键并保存原始 payload。
7. **Given** 任一交易或 ledger entry 含非零手续费，**When** 同步，**Then** 费用金额和币种必须精确保存；无法解析金额、缺少非零费用币种或不支持的 ledger 类型时，整次同步 fail-closed，绝不将费用归零或跳过记录。

---

### User Story 2 - 同步 Polymarket 交易活动 (Priority: P1)

作为预测市场投资者，我希望通过 CLI 命令将 Polymarket 的公开交易活动自动拉取并导入 Finance Tracker，系统将 BUY/SELL 映射为 swap（`pm:<slug>:<yes|no>` ↔ USD），保留 Activity ID 作为幂等键。

**Why this priority**: Polymarket 是当前唯一支持的预测市场平台，017 已有 Polymarket 估值 adapter（gamma-api）。交易活动没有文件导出功能，API 是唯一获取途径。

**Independent Test**: 使用 mock Activity API 返回，对含 BUY/SELL 的 activity 列表执行 `ft sync --source polymarket --account Polymarket`，验证 swap 事件正确、幂等、USD 金额 = `usdcSize`。

**Acceptance Scenarios**:

1. **Given** 用户已配置 Polymarket 钱包地址且有 `security` 类型账户「Polymarket」，**When** 执行 `ft sync --source polymarket --account Polymarket`，**Then** 系统通过公开 Activity API 分页拉取全部 TRADE 活动，BUY 映射为 `swap(from=usd, to=pm:<slug>:<outcome>, from_amount=usdcSize, to_amount=size)`，SELL 反向；`source_type=polymarket_api`，`record_id=<activity_id>`；投影后快照一致。
2. **Given** Activity 类型为 `REDEEM`，**When** 同步，**Then** 将已结算的结果仓位映射为 `swap(from=pm:<slug>:<outcome>, to=usd, from_amount=size, to_amount=usdcSize)`，并以 `transactionHash` 作为幂等键。
3. **Given** Activity 类型为 `YIELD`，**When** 同步，**Then** 将其映射为 USD `dividend`，金额为 `usdcSize`，并以 `transactionHash` 作为幂等键。
4. **Given** Activity 类型不属于 `TRADE`、`REDEEM` 或 `YIELD`，**When** 同步，**Then** 静默跳过该活动，不产生事件。
5. **Given** Activity 缺少映射所需字段或 `transactionHash`，**When** 同步，**Then** 整批 fail-closed：回滚事务并报告具体缺失字段与异常条目原始数据。
6. **Given** 用户再次同步，**When** 检查幂等键，**Then** 已存在活动跳过。
7. **Given** 双数据库同一 mock 数据，**When** 比较，**Then** 事件与快照一致。

---

### User Story 3 - 凭据配置与安全 (Priority: P2)

作为用户，我希望在一个受保护的本地配置文件中管理交易所 API 密钥和 Polymarket 钱包地址，系统在同步前验证凭据存在且格式正确，不会将密钥写入日志或仓库文件。

**Why this priority**: 凭据是 API 同步的前提，但不需要复杂的 vault 或加密系统——本地 YAML 文件加上文件权限即可满足单用户场景。

**Independent Test**: 在无凭据、缺字段、格式错误的情况下执行 sync 命令，验证错误信息清晰且不泄漏密钥值。

**Acceptance Scenarios**:

1. **Given** 凭据文件 `~/.ft/credentials.yaml` 存在且包含正确的 `binance.api_key` + `binance.api_secret`，**When** 同步 binance，**Then** 正常连接。
2. **Given** 凭据文件不存在，**When** 同步任何源，**Then** 报告错误并给出示例配置格式，不崩溃。
3. **Given** 凭据文件缺少必填字段（如只有 `api_key` 无 `api_secret`），**When** 同步，**Then** 报告具体缺失字段名，不泄漏已有字段的值。
4. **Given** 凭据文件存在，**When** 系统首次访问，**Then** 自动确保 `credentials.yaml` 在 `~/.ft/.gitignore` 中，且文件权限为 `0600`。
5. **Given** Polymarket 配置只需 `wallet`（公开地址）或 `proxy_wallet`，**When** 同步 polymarket，**Then** 系统自动解析 proxy wallet（若只提供 login 地址）。

---

### User Story 4 - 增量游标优化 (Priority: P3)

作为高频交易用户，我希望系统记住上次同步位置，下次同步时只拉取新交易，而不是每次全量分页，以减少 API 调用次数和等待时间。

**Why this priority**: 全量分页对新用户首次同步是必要的，但日常使用时增量游标显著减少 API 调用。作为优化项，可在 P1/P2 完成后实施。

**Independent Test**: 首次全量同步后，记录游标值；添加新 mock 交易后再次同步，验证只拉取游标之后的交易。

**Acceptance Scenarios**:

1. **Given** 用户首次同步，**When** 完成，**Then** 系统将最后一笔交易的时间戳或 ID 记录为该 `(account, source_type)` 的同步游标，持久化到数据库。
2. **Given** 存在游标，**When** 再次同步，**Then** 系统从游标位置开始分页拉取，减少 API 调用。
3. **Given** 用户显式传入 `--full` 参数，**When** 同步，**Then** 忽略游标，全量重新拉取（幂等保证不重复）。
4. **Given** 游标所指向的交易已被交易所删除或 ID 不可用，**When** 同步，**Then** 回退到全量拉取并警告用户。

---

### User Story 5 - 及时查看已导入持仓 (Priority: P1)

作为用户，我希望运行 `ft stock list` 后能在可预期的时间内看到已导入的全部非零持仓，即使某个行情供应商超时、返回空数据或打印诊断信息，也不能让终端长期空白或隐藏账本持仓。

**Why this priority**: 连接器已将真实交易导入账本；持仓查询是验证导入结果的直接入口。逐项同步行情请求会让拥有多个预测市场合约的账户在渲染前等待很久，破坏 CLI 的核心可用性。

**Independent Test**: 使用含多个持仓的 repository 和会阻塞/抛错的 quote provider 执行 `ft stock list`，验证在全局查询预算内输出每个非零持仓；未取得行情的项目显示 `partial`/`N/A`，且 stdout/stderr 不包含供应商的原始诊断文本。

**Acceptance Scenarios**:

1. **Given** 账本快照含多个证券、加密资产或 Polymarket 合约，**When** 用户运行 `ft stock list`，**Then** 命令输出所有非零持仓及其账户，不因行情请求数量而省略或等待全部单项请求完成。
2. **Given** 任一行情供应商超时、失败或返回空结果，**When** 组合查询继续执行，**Then** 对应持仓显示 `N/A` 与明确的 partial 状态，其余持仓仍照常输出，且进程成功退出。
3. **Given** 行情供应商产生第三方诊断输出，**When** 用户运行持仓命令，**Then** CLI 只输出 Finance Tracker 定义的表格和错误合同，不透传未结构化供应商日志。
4. **Given** 同一快照分别存于 PostgreSQL 与 SQLite，**When** 使用受控 provider 查询持仓，**Then** 非零持仓集合及每项状态在两个后端一致。

---

### User Story 6 - 校准 Polymarket 当前现金 (Priority: P1)

作为 Polymarket 用户，我希望同步导入成交和派息后读取 funder 地址当前的 pUSD 余额并校准 USD 现金，这样账本不会因 Activity API 未包含入金而显示错误的负现金。

**Independent Test**: 用 mock Polygon RPC 返回 funder 当前 pUSD `balanceOf`，执行同步；验证生成 USD `checkin`，该事件替换现金但不改变市场仓位，SQLite 与 PostgreSQL 快照一致。

**Acceptance Scenarios**:

1. **Given** 用户已配置 Polymarket `proxy_wallet`，**When** 执行 `ft sync --source polymarket --account Polymarket`，**Then** 系统对 Polygon pUSD 合约 `0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB` 调用 `balanceOf(funder)`，按 6 decimals 精确生成 USD `checkin`。
2. **Given** 活动导入后账本 USD 与当前链上余额不一致，**When** 应用该 checkin，**Then** USD 现金被替换为 pUSD 当前余额；既有 `pm:<slug>:<outcome>` 仓位和其成本不变。
3. **Given** 同一 pUSD 余额读取 block 的同步重跑，**When** 检查幂等键，**Then** `checkin:<block_number>` 只导入一次；后续确认 block 的 checkin 可替换此前现金。
4. **Given** Activity API、当前 block、block timestamp 或 `balanceOf` 无法读取或安全解析，**When** 同步，**Then** 整批 fail-closed：不得写入任何活动、checkin、快照或游标。
5. **Given** 同一 mock Activity API 与 Polygon RPC 数据分别同步到 SQLite 和真实 PostgreSQL，**When** 比较，**Then** checkin 金额、payload、幂等键和快照完全等价。

---

### Edge Cases

- **API 限流/网络错误**：同步过程中遇到 429 或网络超时时，系统 MUST 重试（指数退避，最多 3 次）；超过重试次数后 MUST fail-closed：本次同步的事件、快照和游标均不得写入，并报告失败点与已拉取但未提交的条目数。
- **交易所返回异常数据**：trade 或 ledger entry 缺少 ID、无法映射的类型、金额/时间戳/手续费无效、非零手续费缺少币种时，整批 MUST fail-closed 并报告异常条目原始数据，不静默跳过、归零或推进游标。
- **交易与 ledger 重叠**：交易本身以 `fetch_my_trades` 的 trade ID 为唯一规范来源；对应的 ledger `trade` 分录仅用于确认覆盖，不另建第二套单边事件，避免一笔成交被双重记账。所有非 trade ledger entry 必须逐条导入或失败。
- **Polymarket 未支持活动**：仅 `TRADE`、`REDEEM` 与 `YIELD` 具有本 feature 定义的账务映射；其他类型在保留 API 可重拉性的前提下静默跳过。
- **Polymarket 链上范围**：本 feature 不扫描 `Transfer` 历史、不导入或推断入金/出金；仅对当前 funder 读取 pUSD `balanceOf` 并生成现金 checkin。
- **账户类型不匹配**：用户尝试将 exchange sync 导入非 `crypto` 账户或将 Polymarket sync 导入非 `security`/`crypto` 账户时，MUST 拒绝并明确提示。
- **凭据轮换**：用户更新 API 密钥后重新同步，系统 MUST 正常工作——幂等键与凭据无关。
- **并发同步**：同一账户同时运行两次 sync 时，数据库事务级别保证写入原子性；第二次在提交阶段检测到冲突时 MUST 失败并提示重试。
- **时区处理**：交易所返回 UTC 毫秒时间戳，系统 MUST 使用 UTC 存储、按用户本地时区展示（`UTC+8` 为默认展示偏移）。
- **PostgreSQL 与 SQLite 差异**：
  - **等价行为**：同一 mock 数据同步后，事件数量、金额（Decimal 精确）、ticker、快照持仓、幂等判断 MUST 一致。
  - **允许差异**：事务隔离实现（PG SERIALIZABLE vs SQLite WAL+IMMEDIATE）、并发写性能、`id` 具体值可不同。
  - **禁止行为**：不得因后端差异改变映射逻辑或幂等判断。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 提供 `ConnectorPort` 接口（domain port），定义 `fetch_trades(cursor?) → (events, next_cursor)` 签名，使不同数据源的 connector 可独立实现和测试。
- **FR-002**: 系统 MUST 提供 `CcxtExchangeConnector` adapter，通过 ccxt 的 `fetch_my_trades`（全量所有交易对）和 `fetch_ledger`（全量资金账本）拉取私有活动，支持首批 Binance、Kraken、OKX。任一 provider 不支持其中一个必需接口、任一分页失败或任何返回记录无法安全映射时，MUST fail-closed。
- **FR-003**: ccxt trade MUST 映射为投资事件：`BUY(base/quote)` → `swap(from=quote, to=base)`，`SELL` 反向；`commission`/`commission_asset` 保留原始扣费币种与金额。缺失费用表示 0；已出现但无法解析的费用或非零费用缺少币种 MUST 报错，禁止归零。
- **FR-003a**: 非 trade ledger entry MUST 逐条映射：`deposit` → `deposit`、`withdrawal` → `withdraw`、`staking`/`reward`/`credit`/`rollover` → `dividend`、`transfer`/`derivativescrossexchangetransfer` → `transfer`（仅审计、不改变该账户快照）；所有非零 ledger fee 另建 `fee` 事件。每个主事件使用 ledger ID，每个费用事件使用 `<ledger_id>:fee` 作为 `record_id`。
- **FR-004**: 系统 MUST 提供 `PolymarketConnector` adapter，通过公开 Activity API 分页拉取活动：`TRADE` 映射为 `swap(pm:<slug>:<outcome> ↔ usd)`，`REDEEM` 映射为 `swap(pm:<slug>:<outcome> → usd)`，`YIELD` 映射为 USD `dividend`；其他活动类型跳过。
- **FR-005**: 每条同步记录 MUST 携带 `source_type`（如 `binance_api`/`kraken_api`/`polymarket_api`）和 `record_id`（交易所 trade ID / Polymarket activity ID），复用 `010` 的幂等机制。
- **FR-006**: 同步 MUST 在单个 UnitOfWork 事务内完成：幂等检查 → 映射 → 投影 → 快照验证 → 游标更新 → 提交。任一条交易或 ledger 映射、校验或分页异常时 MUST 使本次同步整体 fail-closed（回滚全部事件、快照和游标并报告异常条目）。对于大量记录，MUST 按可配置大小（默认 500）分块处理以控制内存和校验粒度，但分块不得产生部分提交。
- **FR-007**: 每条同步事件 MUST 在 `source_payload` 中保存 API 原始响应的关键字段（trade dict / activity dict 的 JSON），确保来源可追溯。
- **FR-008**: 系统 MUST 提供 CLI 命令 `ft sync --source <provider> --account <name>` 用于手动触发同步。
- **FR-009**: 凭据 MUST 从 `~/.ft/credentials.yaml` 加载，按 provider 分段存储；文件权限 MUST 自动设为 `0600`，且 MUST 确保在 `~/.ft/.gitignore` 中。
- **FR-010**: 凭据缺失或格式错误时，MUST 给出包含示例配置的可操作错误信息，MUST NOT 在错误信息、日志或仓库文件中暴露密钥值。
- **FR-011**: API 调用遇到可重试错误（网络超时、429）时，MUST 指数退避重试（最多 3 次）；超过重试次数后 MUST 报告本次已拉取但未提交的条目数与失败点，且本次已同步条目数为 0。
- **FR-012**: 同步的投资事件 MUST 通过现有 `apply_investment_event` 投影更新快照，并通过 `validate_investment_snapshot` 校验；校验失败时 MUST 回滚该批事务。
- **FR-013**: 系统 MUST 在 PostgreSQL 和 SQLite 上产生等价的同步结果（事件数量、金额精度、ticker、快照持仓）。
- **FR-014**: Polymarket connector 凭据仅需公开 `wallet` 地址或 `proxy_wallet` 地址；若提供 login 地址，系统 MUST 自动解析 proxy wallet。
- **FR-015**: 增量游标 MUST 持久化到专用 `sync_cursors` 表（复合键 `workspace_id` + `account_id` + `source_type` → `cursor_value` + `updated_at`），同步成功后更新，下次同步默认从游标位置继续。交易与 ledger 都必须从同一时间游标拉取；任一端失败不得更新游标。
- **FR-016**: CLI MUST 支持 `--full` 参数强制全量重新拉取，忽略已有游标。
- **FR-017**: 本 feature MUST NOT 引入定时调度、Worker、Web UI、加密凭据存储或通用 Connector 平台层。
- **FR-018**: Ticker 规范化 MUST 复用现有 `schema.py` 的 `CRYPTO_IDS` 映射和 `importers/ticker_normalize.py` 的逻辑，确保同一资产在文件导入和 API 同步中使用相同 ticker。
- **FR-019**: `ft stock list` MUST 在固定的、可测试的总行情读取预算内渲染所有账本中的非零持仓；预算耗尽、单项超时、空行情或 provider 异常只使该项报价为 partial/N/A，MUST NOT 阻塞、隐藏其他持仓或让命令以未结构化第三方输出结束。该读取策略不得写入账本或改变 SQLite/PostgreSQL 的持仓集合。
- **FR-020**: `PolymarketConnector` MUST 在 Activity API 之外读取 funder/proxy 地址当前 Polygon pUSD ERC-20 `balanceOf`；pUSD 固定为 CLOB V2 合约 `0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB`、6 decimals。它 MUST 映射为 USD `checkin`（`to_ticker=usd`、`to_amount=balance`），不得扫描 `Transfer` 历史、导入或推断入金/出金。
- **FR-021**: 当前 pUSD checkin MUST 在读取到的确认 block 上完成，以 `checkin:<block_number>` 作稳定 record ID，并在 `source_payload` 保留 token、wallet、balance_base_units、block_number、block_timestamp。相同 block 重跑幂等，后续 block 的 checkin 使用既有替换语义更新 USD 现金。
- **FR-022**: Activity API、当前 block、block timestamp 或 `balanceOf` 读取失败 MUST fail-closed；Activity、checkin、快照和游标均不得部分写入。现有 Polymarket 时间游标保持纯 Activity timestamp 格式，无需链上复合游标。

### Key Entities

- **ConnectorPort**: 领域层接口，定义 connector 的 `fetch_trades` 能力和返回的标准化交易事件格式。
- **SyncCursor**: 增量同步游标，持久化于专用 `sync_cursors` 表，复合键 `(workspace_id, account_id, source_type)`，记录上次同步的最后交易时间戳或 ID。
- **Polymarket pUSD Checkin**: funder 在确认 Polygon block 的 pUSD `balanceOf` 观察值；映射为 USD `checkin` 并替换现金，不代表一笔入金或出金。
- **CredentialProvider**: 凭据加载能力，从本地配置文件读取并验证 provider 凭据。
- **InvestmentEvent (existing)**: 复用 009 定义的统一投资事件 dict，并扩展既有投影以支持 `transfer` 审计 action；含 `action`/`from_ticker`/`to_ticker`/`from_amount`/`to_amount`/`commission`/`commission_asset`/`source_type`/`record_id`/`source_payload`。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 用户在 3 分钟内完成首次 exchange 同步配置（凭据 + 命令执行）并看到导入结果。
- **SC-002**: 同步 1000 笔交易的端到端耗时不超过 30 秒（不含网络延迟）。
- **SC-003**: 重复同步同一数据源时，已存在交易、ledger 主事件和 ledger fee 事件 100% 被幂等跳过，无重复事件。
- **SC-004**: 同步后的投资事件可通过 `017` 估值系统直接获得组合市值与状态。
- **SC-005**: 凭据错误或 API 异常时，错误信息足够用户自行诊断和修复，无需查看源码。
- **SC-006**: PostgreSQL 与 SQLite 上同一 mock 数据的同步结果完全等价（事件数、金额、ticker、快照）。
- **SC-007**: 不引入定时调度、Web UI、加密存储或 Connector 平台层；范围审查 0 越界。
- **SC-008**: 对含至少 16 个预测市场合约的组合，`ft stock list` 在 5 秒内产生包含全部非零持仓的首个且完整的 CLI 表格；行情未取得的项目明确显示为 N/A/partial，且无第三方诊断泄漏。
- **SC-009**: SQLite 和真实 PostgreSQL 的同一 mock 数据中，pUSD checkin 的 6-decimal 金额、source payload、record ID 和同步后 USD 快照 100% 一致；同一 block 重跑不新增事件。

## Clarifications

### Session 2026-07-26

- Q: 同步交易所时拉取范围——全量所有交易对 vs 用户指定 symbols？ → A: 全量拉取所有交易对（默认行为），不提供 `--symbols` 过滤。
- Q: 单条异常交易的处理策略——整批 fail-closed vs 单条跳过？ → A: 整批 fail-closed，任一条异常则整个同步事务回滚并报告异常条目详情。
- Q: SyncCursor 持久化方式——专用表 vs 复用 import_batches vs 通用 KV？ → A: 新建 `sync_cursors` 专用表（workspace_id + account_id + source_type → cursor_value）。
- Q: API 分页中途失败时是否保留此前批次？ → A: 不保留。先完整拉取并校验，再在同一事务内处理所有分块；任一分页、映射或校验失败均回滚本次全部事件、快照和游标。
- Q: Polymarket 的 `REDEEM` 与 `YIELD` 如何入账？ → A: `REDEEM` 作为 `pm:<slug>:<outcome> → usd` 的 `swap`，`YIELD` 作为 USD `dividend`；二者均用 `transactionHash` 幂等，其他未定义活动类型跳过。
- Q: 加密交易所的非成交活动与异常手续费如何处理？ → A: 导入所有可识别 ledger 活动；交易、入金、出金、奖励/利息、内部转账和独立手续费均需保留。未知类型、缺字段或异常费用必须整次 fail-closed，禁止静默跳过或将费用归零。
- Q: Polymarket 是否导入历史出入金？ → A: 不导入。每次同步仅读取 funder 当前 pUSD `balanceOf`，写 USD `checkin` 校准现金；该观察值不是入金/出金记录。

## Dependencies

- `009-investment-account-import`：投资事件模型（swap/deposit/withdraw/dividend/checkin）。
- `010-row-idempotent-import`：行级幂等（`source_type` × `record_id`）。
- `016-bigint-surrogate-ids`：当前 schema baseline（Alembic `20260724_09`）。
- `017-asset-valuation-quote`：同步后估值消费路径。
- ccxt ≥ 4.0.0（已在 `pyproject.toml` 中声明）。

## Assumptions

- 用户的交易所账户已开通 API 访问权限（read-only 即可）。
- ccxt 库对目标交易所的 `fetch_my_trades` 接口稳定可用。
- Polymarket Activity API 保持当前的公开无认证访问模式。
- 单用户本地运行场景——凭据文件权限 (`0600`) 足以保护密钥安全。
- 交易所返回的 trade ID 在该交易所内全局唯一且不可变。
- 网络环境可能需要代理（系统已支持 `HTTPS_PROXY` / `HTTP_PROXY` 环境变量）。
- 首批验证的交易所为 Binance/Kraken/OKX，但 ccxt connector 应对任意 ccxt 支持的交易所通用。
