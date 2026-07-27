# Research: 017-asset-valuation-quote

## R1 — 产品主目标

**Decision**: **P0** = 组合/持仓市值路径消费统一估值（本币 + 可选 `display_currency` 折算）。原子 `quote`/`quote_many` = **P1 支撑能力**，不是独立 MVP 终点。

**Rationale**: Living Spec 2026-07-25 用户决议。

## R2 — 本币 vs 展示币

**Decision**:
- **本币模式**（`display_currency is None`）：每条持仓 `unit_price`/`market_value`/`quote_currency` 为行情计价货币；不跨币加总为单一「总资产」除非调用方自行按币种分组。
- **展示币模式**（传入 ISO 展示货币）：保留本币字段；另输出 `display_currency`、`display_market_value`、`fx_rate`（quote per 1 unit of base=本币计价货币？约定：**1 单位 quote_currency 资产市值 × rate = display**，其中 `rate` = 多少 *display* 单位 per 1 *quote_currency*）、`fx_status`/`fx_reason`。
- 异币且 FX 缺失：本币仍完整；展示市值 `None`；`fx_reason=fx_unavailable`；**禁止** rate=1。

**Rationale**: 用户明确「可分币种、也可统一指定货币」。

## R3 — 估值入口分层

**Decision**:
1. `ValuationService`：原子 quote（无 FX）。
2. `PortfolioQueryService`（或 `PortfolioValuationService` 薄封装）：加载持仓 → 推断 kind → `quote_many` → 可选 FX 折算 → DTO。

**Alternatives**: 仅增强 `get_prices` — 拒绝。

## R4 — 状态与 freshness

**Decision**: 与财富公开字面量对齐：`complete|stale|partial|unsupported`。

| kind | freshness | maximum |
|------|-----------|---------|
| crypto | 24h | 7d |
| security | 5d | 30d |
| prediction_market | 5d | 30d |
| cash | n/a | n/a |

Beyond maximum → partial，清空单价。

## R5 — 账本 ticker 与外部符号 / kind 推断（组合）

**Kind 推断表（组合路径，确定性）**:

| 条件 | AssetKind |
|------|-----------|
| ticker 大小写不敏感 ∈ 账户 `base_currencies` / configured cash 集 | `cash` |
| 归一后以 `pm:` 开头且匹配 `pm:slug:yes|no` | `prediction_market` |
| 小写代码 ∈ `CRYPTO_IDS` | `crypto` |
| 其余非空 | `security`（映射失败则 quote → unsupported） |

**证券 → yfinance**:
- `*.us` → 裸大写
- `*.hk` → yfinance 港股格式（去多余前导零 + `.HK`）
- `*.sh` → `.SS`；`*.sz` → `.SZ`

**加密**: 仅 `CRYPTO_IDS`。  
**PM**: 小写归一。  
**现金**: 价 1。

## R6 — 报价币种

**Decision**:
- cash: 自身 ISO
- crypto: `USD`
- prediction_market: **`USD`**（v1 名义锚，冻结）
- security: 适配器可知则 ISO；未知可不填但不阻断有价 complete（reason 可记 `currency_unspecified`）— 组合折算时若缺 ISO 则 **无法 FX** → 展示腿 partial

## R7 — FX

**Decision**: Port `FxRateProvider.get_mid(base, quote, day=None) -> Decimal | None`；生产可用 Frankfurter「今日/最近营业日」；测试注入。  
`rate` 语义：**display 单位 per 1 单位 quote_currency（本币计价货币）**。  
`display_market_value = market_value * rate`。  
不写账本。

**Alternatives**: 原子 quote 内折算 — 拒绝（组合专属）。

## R8 — 批量与异常

同前：输入非法整批/请求失败；源失败 → 项级 partial；组合不因单项失败崩溃。

## R9 — 不落库

实时价与 FX 均不写正式事实 / 默认不写 valuation_observations。

## R10 — DTO

`PortfolioPositionDTO` 扩展：
- `quote_status`, `quote_reason`
- `quote_currency`（本币）
- `display_currency`, `display_market_value`, `fx_rate`, `fx_status`, `fx_reason`（仅展示模式填）

`get_portfolio(display_currency: str | None = None)`。

## R11 — 双后端

无 schema。假源+假 FX 输出一致。可选 wiring 冒烟。

## R12 — 编号

实施权威 `017`；路线图旧 011 名交叉链接。
