# OpenSpec 迁移清单

- Spec Kit 初始迁移日期：2026-08-01
- PR28 补充迁移日期：2026-08-03
- capability 基线重整日期：2026-08-07

## 当前主规格

`openspec/specs/` 是当前行为的唯一规格事实源。主规格按稳定领域能力命名，不使用 feature 序号：

- `runtime-database`
- `multi-currency-accounts`
- `statement-import`
- `ledger-records`
- `cash-record-classification`
- `investment-event-model`
- `investment-statement-import`
- `transaction-relations`
- `portfolio-valuation`
- `investment-connector-sync`
- `wealth-attribution`
- `cash-ledger-browser`
- `time-semantics`
- `cash-investment-funding-relations`
- `counterparty-account-transfer-matching`
- `icbc-asia-current-account-import`

## Active change

- `investment-ledger-browser`：尚未实现的投资账本 Web 能力，只存在于 `openspec/changes/`；完成实现、验证并归档前不得进入主规格。

## 2026-08-07 收口的 change

- `local-timezone-data-boundary`
- `match-transfers-by-counterparty-account`
- `cash-ledger-filter-hierarchy`
- `preserve-complete-statement-source-rows`
- `rebase-openspec-capabilities`

归档目录使用 `openspec/changes/archive/2026-08-07-<change-name>/`。每个归档保留 proposal、delta specs、design、tasks 和验证证据。

## 旧 feature 到当前 capability 的映射

| 旧主规格 | 当前事实源或处理方式 |
|----------|----------------------|
| `001-postgres-only-storage` | 数据库边界进入 `runtime-database`，原始输入进入 `statement-import`；PostgreSQL-only 行为已被双后端运行时取代 |
| `002-dual-database-runtime` | `runtime-database`、`time-semantics` |
| `003-wealth-attribution-core` | `wealth-attribution`、`time-semantics` |
| `004-mapping-import-open-currency` | `statement-import`、`multi-currency-accounts` |
| `005-multi-currency-accounts` | `multi-currency-accounts` |
| `006-transaction-relations` | `transaction-relations`、`ledger-records`、`time-semantics` |
| `007-closed-trade-refund-import` | `statement-import`、`cash-record-classification`、`transaction-relations` |
| `008-relations-kind-decouple` | 用户可见合同进入 `transaction-relations`；RulePack 等内部结构只留在历史 design |
| `009-investment-account-import` | `investment-statement-import`、`investment-event-model`；旧 deferred API 说明不属于当前行为 |
| `010-row-idempotent-import` | `statement-import` |
| `011-usmart-hk-import` | `investment-statement-import` |
| `012-investment-base-currency-cost` | `investment-event-model` |
| `013-investment-cash-event-kinds` | `investment-event-model`；跨账本资金移动由 `cash-investment-funding-relations` 描述 |
| `014-fact-field-unify` | `ledger-records`、`investment-event-model`；字段迁移过程只留历史 |
| `015-inline-row-provenance` | `statement-import`、`ledger-records`；删表和本机升级步骤只留历史 |
| `016-bigint-surrogate-ids` | 公共业务身份和关系完整性进入 `ledger-records`；代理键迁移只留历史 |
| `017-asset-valuation-quote` | `portfolio-valuation` |
| `018-investment-connector-sync` | `investment-connector-sync` |
| `019-portfolio-quote-orchestration` | `portfolio-valuation` |
| `020-cash-ledger-browser-web` | `cash-ledger-browser`、`time-semantics` |
| `022-investment-ledger-browser-web` | 从主规格移除；规划内容保留为 active change `investment-ledger-browser` |
| `023-icbc-refund-pairing` | `cash-record-classification`、`transaction-relations` |
| `024-normalized-cash-record-type` | `cash-record-classification` |
| `025-record-type-relation-gates` | `transaction-relations` |
| `time-boundary-contract` | 合并进 `time-semantics`，能力特有的时间约束进入对应主规格 |

`cash-investment-funding-relations`、`counterparty-account-transfer-matching` 与 `icbc-asia-current-account-import` 已按稳定能力命名，继续保留为独立主规格。

## 历史证据

Spec Kit 迁移时共有 24 个 feature 目录：`001`–`020`、`022`–`025`，没有 `021`。原始 feature artifact 保存在 2026-08-01 和 2026-08-03 对应归档的 `legacy/` 目录；后续 change 归档继续保留行为变化和验证证据。基线重整不重写这些历史记录。

## 防复发规则

- capability 名称描述长期业务能力，不包含序号、一次 change、修复手段或重构步骤。
- 当前行为只写入 `openspec/specs/<capability>/spec.md`；未完成行为只写入 active change 的 delta spec。
- 同一能力的后续变化继续修改同一个 capability，不为每次迭代新建平行主规格。
- 主规格只保留可观察行为、错误边界和可验证场景；内部类名、一次性迁移步骤和研究记录进入 change 的 design、tasks 或 `legacy/`。
- 禁止“迁移前规格所描述的有效业务上下文”“功能需求基线”“可度量验收结果”等无法独立验证的占位内容。
- 归档前先同步 delta 并逐 requirement 复核；没有实现和验证证据的 change 不得归档，也不得提前写入主规格。
