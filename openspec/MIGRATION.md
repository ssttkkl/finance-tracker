# OpenSpec 迁移清单

- Spec Kit 初始迁移日期：2026-08-01
- PR28 补充迁移日期：2026-08-03
- capability 基线重整日期：2026-08-07

## 当前主规格

当前的 24 份主规格按 8 个业务模块组织，入口为 [业务能力规格导航](specs/README.md)。目录结构为
`openspec/specs/<module>/<capability>/spec.md`，完整能力 ID 包含模块前缀。

## 2026-09-29 业务目录重组

本次只调整结构、导航和引用，不改变需求与场景语义。旧子能力名保留，历史归档及 `legacy/` 原文不变。
以下旧 ID 均指向重组前的 `openspec/specs/<旧 ID>/spec.md`，链接给出当前事实源。

| 旧 ID | 当前完整能力 ID 与主规格 |
|-------|-------------------------|
| `cash-ledger-browser` | [`cash-ledger/cash-ledger-browser`](specs/cash-ledger/cash-ledger-browser/spec.md) |
| `cash-category-management` | [`cash-ledger/cash-category-management`](specs/cash-ledger/cash-category-management/spec.md) |
| `cash-record-classification` | [`cash-ledger/cash-record-classification`](specs/cash-ledger/cash-record-classification/spec.md) |
| `transaction-relations` | [`cash-ledger/transaction-relations`](specs/cash-ledger/transaction-relations/spec.md) |
| `counterparty-account-transfer-matching` | [`cash-ledger/counterparty-account-transfer-matching`](specs/cash-ledger/counterparty-account-transfer-matching/spec.md) |
| `statement-import` | [`import/statement-import`](specs/import/statement-import/spec.md) |
| `cash-import-session` | [`import/cash-import-session`](specs/import/cash-import-session/spec.md) |
| `icbc-asia-current-account-import` | [`import/icbc-asia-current-account-import`](specs/import/icbc-asia-current-account-import/spec.md) |
| `investment-statement-import` | [`import/investment-statement-import`](specs/import/investment-statement-import/spec.md) |
| `investment-connector-sync` | [`import/investment-connector-sync`](specs/import/investment-connector-sync/spec.md) |
| `investment-event-model` | [`investment/investment-event-model`](specs/investment/investment-event-model/spec.md) |
| `portfolio-valuation` | [`investment/portfolio-valuation`](specs/investment/portfolio-valuation/spec.md) |
| `cash-investment-funding-relations` | [`investment/cash-investment-funding-relations`](specs/investment/cash-investment-funding-relations/spec.md) |
| `multi-currency-accounts` | [`accounts/multi-currency-accounts`](specs/accounts/multi-currency-accounts/spec.md) |
| `workspace-entry` | [`workspace/workspace-entry`](specs/workspace/workspace-entry/spec.md) |
| `workspace-context-routing` | [`workspace/workspace-context-routing`](specs/workspace/workspace-context-routing/spec.md) |
| `workspace-management` | [`workspace/workspace-management`](specs/workspace/workspace-management/spec.md) |
| `user-workspace-access` | [`authentication/user-workspace-access`](specs/authentication/user-workspace-access/spec.md) |
| `mobile-login-api-origin` | [`authentication/mobile-login-api-origin`](specs/authentication/mobile-login-api-origin/spec.md) |
| `wealth-attribution` | [`wealth/wealth-attribution`](specs/wealth/wealth-attribution/spec.md) |
| `ledger-records` | [`shared/ledger-records`](specs/shared/ledger-records/spec.md) |
| `time-semantics` | [`shared/time-semantics`](specs/shared/time-semantics/spec.md) |
| `runtime-database` | [`shared/runtime-database`](specs/shared/runtime-database/spec.md) |
| `cross-platform-presentation` | [`shared/cross-platform-presentation`](specs/shared/cross-platform-presentation/spec.md) |

### 未完成变更

未完成变更的 22 份 delta 同步改为 `specs/<module>/<capability>/spec.md`，proposal 的能力 ID 与目录一致。
本次没有同步 delta 正文到主规格，没有归档任何变更，也没有将规划能力标为已实现。

| 尚无主规格的能力 | delta 完整能力 ID |
|------------------|------------------|
| 支付组成项 | `cash-ledger/cash-transaction-components` |
| 投资账本浏览 | `investment/investment-ledger-browser` |
| Expo 客户端 | `shared/expo-client` |
| 无 CLI 运行入口 | `shared/cli-free-runtime` |
| Compose Multiplatform 客户端 | `shared/compose-multiplatform-client` |
| Native CI 打包 | `shared/native-ci-packaging` |

`cash-ledger-crud-import` 中遗留的 `020-cash-ledger-browser-web` delta 改为
`cash-ledger/cash-ledger-browser`，对应下方既有的旧 feature 映射；proposal 中的
`004-mapping-import-open-currency` 和 `005-multi-currency-accounts` 分别引用
`import/statement-import` 与 `accounts/multi-currency-accounts`。这里只修正归属，不修改需求正文。

### 验证与审查证据

- 执行日期：2026-09-29（Asia/Shanghai）。比较基线与当前 `HEAD` 均为 `67e26d7c1363ce31e18f0a0d749255a1c87ad6d7`，分支为 `refactor/kmp`。
- 文档比较另保留实施前工作树快照，以区分已有的 Compose、设计文件和变更记录编辑；本次未提交或推送。
- 环境：`openspec --version` 为 `1.7.0`，`node --version` 为 `v24.16.0`。

| 实际检查 | 结果 |
|----------|------|
| `openspec validate --all --strict --json`，迁移前后各执行 | 均为 34/34 通过：24 份主规格、10 个未完成变更；既有长需求提示不影响有效性 |
| `openspec doctor` | 根目录有效，无声明的外部引用 |
| `openspec list --specs --json` 与逐项 `openspec show <module>/<capability> --json` | 恰好发现并读取 24 个新 ID，无残留平铺主规格 |
| 对 10 个未完成变更逐项执行 `openspec instructions specs --change <name> --json` | `existingOutputPaths` 与 22 份实际 delta 路径一致 |
| `python3 /tmp/ft-spec-reorg-check.py`，使用本次实施前工作树快照 | 24 份主规格与 22 份 delta 正文逐字节一致；180 条需求、440 个场景完整；624 个归档及 legacy 文件原样保留；88 个新增导航与迁移链接有效 |
| 对受影响当前文档执行 Python 相对链接检查 | 20 个指向主规格的链接有效 |
| `node --input-type=module` 只读调用本机 OpenSpec `findSpecUpdates` | 22 个同步目标均保留完整模块路径，其中 16 个对应现有主规格，6 个新能力没有提前创建主规格；未执行同步或归档写入 |
| `git diff --check` | 通过 |

临时校验脚本、快照和运行数据保存在仓库外，不作为项目运行依赖。一次性校验脚本最初将
`openspec list --specs --json` 的返回值当成数组；读取实际的 `specs` 字段后完整重跑通过。

独立复核覆盖目录归属、完整能力 ID、导航、当前引用和历史保留。按严重程度记录：

- 阻断性问题：无。
- 低严重度：部分未完成变更的维护文字仍使用短能力 ID；已修正
  `investment-ledger-browser` 的 proposal/design/tasks 与 `cross-platform-experience/tasks.md`，复核通过。
- 已排除的误报：财富规格链接经检查存在，无需额外修改。
- 既有范围差异：`cash-ledger-crud-import` 的 proposal 提及导入与账户能力，但现有 delta 只有现金账本；
  本次仅依据迁移表更新这些引用，不补写需求。后续继续实施该变更时，须核对 proposal 与 delta 的范围一致性。

最终范围复核确认仅迁移规格、修正引用并增加导航与目录约定；中文术语与排版已复核。
本次无产品代码、界面、持久化或业务语义变化，因此应用测试、构建、浏览器 QA 与双后端矩阵不适用。
回退时按上方映射移回主规格与 delta，并反向恢复本次引用和导航改动；须保留工作树中原有的其他编辑。

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
- 当前行为只写入 `openspec/specs/<module>/<capability>/spec.md`；未完成行为只写入 active change 的 delta spec。
- 同一能力的后续变化继续修改同一个 capability，不为每次迭代新建平行主规格。
- 主规格只保留可观察行为、错误边界和可验证场景；内部类名、一次性迁移步骤和研究记录进入 change 的 design、tasks 或 `legacy/`。
- 禁止“迁移前规格所描述的有效业务上下文”“功能需求基线”“可度量验收结果”等无法独立验证的占位内容。
- 归档前先同步 delta 并逐 requirement 复核；没有实现和验证证据的 change 不得归档，也不得提前写入主规格。
