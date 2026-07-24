# Research: 017-asset-valuation-quote

## R1 — 估值入口放在哪一层？

**Decision**: 新增 transport-neutral `ValuationService`（Application）+ 领域 DTO/状态枚举；行情供应商仅通过 port 注入。CLI/组合查询只编排，不直连 yfinance/HTTP。

**Rationale**: Constitution V 要求领域与供应商边界解耦；FR-013/FR-015 要求可假源测试并迁移现有 `get_prices` 双轨。

**Alternatives considered**:
- 仅增强 `MarketDataProvider.get_prices`：无法表达类型/状态/市值合同，组合层仍各自猜语义。
- 把状态塞进财富 `WealthStatus` 服务：与 `003` 边界估值耦合，超出本 feature 非目标。

## R2 — 状态枚举与财富域关系

**Decision**: 对外状态字面量与财富公开状态对齐：`complete` | `stale` | `partial` | `unsupported`。定义在 `ft.domain.valuation`（或共享小模块），**不**强制 import 财富 Application。新鲜度阈值复用 `valuation_freshness` 的意图：

| asset_kind | freshness（超过则 stale） | maximum（超过则 partial，价不可用） |
|------------|---------------------------|-------------------------------------|
| crypto | 24h | 7d |
| security | 5d | 30d |
| prediction_market | 5d | 30d |
| cash | n/a（恒 complete） | n/a |

**Rationale**: Phase 2 UI 与财富 coverage 认知一致；SC/FR 要求可测阈值。

**Alternatives considered**:
- 仅 boolean `ok`：无法区分 stale vs unsupported。
- 直接复用 `WealthStatus` 枚举类：可接受但造成 valuation→wealth 依赖方向怪异；优先同名字面量、独立枚举。

## R3 — 账本 ticker 与外部行情符号

**Decision**:
1. **输入**以账本/持仓标识为主（与投资投影一致）：`aapl.us`、`00700.hk` / `0700.hk`、`600519.sh`、`159740.sz`、`btc`、`pm:{slug}:{yes|no}`、现金 `usd`/`hkd`/`cny`…
2. **证券 → yfinance** 映射层（adapter 内）：
   - `*.us` → 裸大写代码（去 `.us`）
   - `*.hk` → 去掉多余前导零后格式化为 yfinance 港股（参考 `references/yfinance-ticker-format.md`：约 4 位 + `.HK`）；映射失败 → `unsupported`
   - `*.sh` → `{code}.SS`；`*.sz` → `{code}.SZ`（后缀大写）
   - 已是 yfinance 形态的输入：确定性归一后请求
3. **加密**：仅 `CRYPTO_IDS`（及 plan 冻结的同表扩展）小写代码 → CoinGecko id；未映射 → `unsupported`
4. **预测市场**：`pm:` 前缀（大小写不敏感归一为小写 slug + yes/no）
5. **现金**：ISO 货币 ticker（小写或大写归一）或调用方显式 `asset_kind=cash` → 单价 1，不访问网络

**Rationale**: 投资导入已用 `normalize_equity_ticker` 写入 `code.us/hk/sh/sz`；旧 `market_data._normalize_ticker` 偏向 yfinance 输入，必须在 adapter 桥接，避免污染领域层。

**Alternatives considered**:
- 要求调用方直接传 yfinance 符号：破坏持仓一致性。
- 运行时猜 CoinGecko id：不可审计，拒绝。

## R4 — 批量部分成功 vs 异常

**Decision**:
- **输入级非法**（空标识、数量 NaN/Inf、未知 `asset_kind` 枚举）：该次 `quote` / 整批校验失败 → 抛稳定应用错误码（fail-closed），不写账本。
- **单项可识别但无价**（网络、空行情、非有限价）：该项 `status=partial`，`unit_price=None`。
- **不支持矩阵**：`unsupported`。
- 批量：逐项结果；不因单项 partial/unsupported 失败整批。

**Rationale**: FR-011、SC-002/003。

## R5 — 是否落库 / 与 valuation_observations

**Decision**: v1 **不落库**、不写 `valuation_observations`、不触发财富 rebuild。

**Rationale**: Spec 非目标与 Assumptions；实时价是读模型旁路，不是正式事实。

## R6 — 报价币种与 FX

**Decision**: 返回 **源报价币种**（crypto：USD；prediction：合约价单位，通常无 ISO，用 `USD` 或 `CONTRACT` 在 contract 写死为源语义字段 `quote_currency`；security：跟随行情源惯例，adapter 在可知时填 ISO，未知可填空并在 reason 说明但不阻断 complete 若有价）。**不做** v1 展示币种 FX 折算。

**Rationale**: Spec Assumptions；避免伪精确。

**Alternatives considered**: 强制全部折 CNY — 需要可靠 FX，超出范围。

## R7 — 观测时间缺失

**Decision**: 源提供时间戳则用（转 aware UTC 或保留 offset）；否则用 **成功解析响应的进程时钟（aware UTC）** 作为 `observed_at`，再跑 freshness。

**Rationale**: Spec Assumptions；保证 stale 可测。

## R8 — 组合查询迁移策略

**Decision**:
1. 引入 `ValuationService` + `CompositeQuoteProvider`（security/crypto/pm/cash 路由）。
2. `PortfolioQueryService` 与 `FinanceQueryService` 改为依赖 `ValuationService`（或兼容 protocol：`quote_many`）；删除对「仅 dict 价格、无状态」路径的**唯一**依赖。
3. 保留短暂 adapter：`MarketDataProvider` 可实现为委托 `ValuationService` 的薄封装（仅返回有价 map）**或**直接删除并由 wiring 替换；tests/`fakes` 同步假估值服务。
4. 无价持仓：保留数量与成本；`current_price`/`market_value` 为 `None`，并在 DTO **新增**可选 `quote_status` / `quote_reason`（最小字段扩展，contract 定义）。

**Rationale**: FR-015、SC-006；避免双轨。

## R9 — CLI

**Decision**: 可选 `ft quote`（或 `ft valuation`）只读：打印 identity、kind、price、currency、status。非 P1 阻塞；若实现则走同一 Application。

**Rationale**: FR-018 可选。

## R10 — 双后端与测试矩阵

**Decision**: 无 schema/migration。等价性 = **同一假源下 ValuationService 输出一致**（与 `FT_DATABASE_URL` 无关）。仍在 wiring 冒烟中于 SQLite 与（若有）PostgreSQL 进程各跑一次组合查询消费路径，证明注入不依赖方言。真实网络测试 mark 为 optional/skip。

**Rationale**: Constitution IV：无持久化分叉则矩阵聚焦「用户可见估值行为」；不伪造 PG 专用价表。

## R11 — 与路线图编号

**Decision**: 实施权威目录为 `specs/017-asset-valuation-quote/`。路线图中的 `011-asset-valuation-quote` 视为同名产品意图；合入后更新 roadmap 交叉链接（文档任务，可放 Polish）。

**Rationale**: 011 已被 usmart 占用。
