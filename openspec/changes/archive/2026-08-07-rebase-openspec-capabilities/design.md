## Context

见 `proposal.md`。当前 `openspec/specs/` 有 27 个主规格，其中 24 个来自 Spec Kit 顺序 feature。OpenSpec 严格校验可以确认标题和场景结构，但无法判断主规格是否描述已实现行为、是否彼此矛盾，也无法自动把 requirement 跨 capability 移动。

当前还存在 5 个 active change：

- `local-timezone-data-boundary`、`cash-ledger-filter-hierarchy` 和 `match-transfers-by-counterparty-account` 的任务已完成。
- `preserve-complete-statement-source-rows` 只剩完整验证和同步归档。
- `022-investment-ledger-browser-web` 的实现任务为 0/10，且 Web 代码中没有投资账本页面；它的 ADDED requirements 却已出现在主规格。

部分完成 change 的 delta 已被提前同步到主规格，因此归档前必须逐 requirement 比较，不能机械重复追加。

## Goals / Non-Goals

**Goals:**

- 让每个主规格对应一个稳定、聚焦、可持续演进的领域能力。
- 让主规格只包含当前已实现且可验证的行为。
- 保留旧 feature、归档和 `legacy/` 证据的可追踪性。
- 使 active change 在重整后继续指向正确 capability，不丢失任务和设计上下文。
- 以当前代码和测试为行为基线，不借规格重整改变账务结果。

**Non-Goals:**

- 不重写历史归档内容或伪造历史完成状态。
- 不增加投资账本 Web、数据库迁移或产品行为。
- 不以代码模块一一对应 capability；外部合同和独立演进边界优先于目录结构。

## Decisions

### 1. Capability 以稳定行为边界命名

新主规格使用无编号 kebab-case 名称。名称描述长期存在的业务能力，不描述一次 feature、修复、重构手段或迁移序号。

替代方案是只移除数字前缀并保留所有旧目录。该方案仍会留下 `postgres-only-storage`、`relations-kind-decouple`、`fact-field-unify` 等历史变化名称，无法解决相互矛盾和同一领域多事实源问题，因此不采用。

### 2. 先收口已交付 change，再建立新基线

重整前先处理已完成或接近完成的 active change：

1. 对已完成 change 比较 delta 和主规格；同步缺失内容，已提前同步的相同内容视为 no-op，然后归档。
2. 为 `preserve-complete-statement-source-rows` 运行剩余验证，再同步和归档。
3. 保留未实现的投资账本 change，不把它同步到主规格。

这样新 capability 从一个明确的行为切点生成，避免迁移过程中还要同时合并多组旧路径 delta。

### 3. 逐 requirement 决定去向，不拼接旧文件

旧规格中的内容按以下类型处理：

| 类型 | 处理方式 |
|------|----------|
| 当前可观察行为 | 重写为目标 capability 的 requirement 和具体场景 |
| 已被后续 change 推翻 | 从主规格移除，历史只留在归档 |
| 尚未实现或标记 deferred | 只留在相应 active/future change |
| 内部类名、表清单、重构手段 | 移入历史 design，目标主规格不保留 |
| 一次性迁移和本机升级步骤 | 保留在历史 change 的 design/tasks/legacy |
| 通用迁移占位 requirement/scenario | 删除，以当前可执行场景替代 |

### 4. 旧规格到新 capability 的映射

| 旧主规格 | 目标 capability 或处理方式 |
|----------|----------------------------|
| `001-postgres-only-storage` | 当前数据库边界进入 `runtime-database`；原始输入进入 `statement-import`；PostgreSQL-only 行为退役 |
| `002-dual-database-runtime` | `runtime-database`、`time-semantics` |
| `003-wealth-attribution-core` | `wealth-attribution`、`time-semantics` |
| `004-mapping-import-open-currency` | `statement-import`、`multi-currency-accounts` |
| `005-multi-currency-accounts` | `multi-currency-accounts` |
| `006-transaction-relations` | `transaction-relations`、`ledger-records`、`time-semantics` |
| `007-closed-trade-refund-import` | `statement-import`、`cash-record-classification`、`transaction-relations` |
| `008-relations-kind-decouple` | 用户可见确定性并入 `transaction-relations`；RulePack 等内部结构只留历史 design |
| `009-investment-account-import` | `investment-statement-import`、`investment-event-model`；deferred API 同步不进入当前规格 |
| `010-row-idempotent-import` | `statement-import` |
| `011-usmart-hk-import` | `investment-statement-import` |
| `012-investment-base-currency-cost` | `investment-event-model` |
| `013-investment-cash-event-kinds` | `investment-event-model`；跨账本资金移动继续由 `cash-investment-funding-relations` 描述 |
| `014-fact-field-unify` | 可观察公共合同进入 `ledger-records`、`investment-event-model`；字段迁移过程留历史 |
| `015-inline-row-provenance` | `statement-import`、`ledger-records`；删表和本机升级步骤留历史 |
| `016-bigint-surrogate-ids` | 公共业务身份和关系完整性进入 `ledger-records`；代理键迁移留历史 |
| `017-asset-valuation-quote` | `portfolio-valuation` |
| `018-investment-connector-sync` | `investment-connector-sync` |
| `019-portfolio-quote-orchestration` | `portfolio-valuation` |
| `020-cash-ledger-browser-web` | `cash-ledger-browser`、`time-semantics` |
| `022-investment-ledger-browser-web` | 从主规格退役；active change 改指向 `investment-ledger-browser` |
| `023-icbc-refund-pairing` | `cash-record-classification`、`transaction-relations` |
| `024-normalized-cash-record-type` | `cash-record-classification` |
| `025-record-type-relation-gates` | `transaction-relations` |
| `cash-investment-funding-relations` | 保留独立主规格 |
| `counterparty-account-transfer-matching` | 保留独立主规格 |
| `icbc-asia-current-account-import` | 保留独立主规格 |
| active `time-boundary-contract` | `time-semantics`，并把资金调拨等能力特有边界写入对应目标规格；`cash-investment-funding-relations` 的固定上海业务日窗口改为 UTC |

### 5. 新基线使用一次受审查的原子切换

OpenSpec 1.7.0 的 delta 合并按相同 capability 路径工作，不能原子表达“从旧 capability 移动到新 capability 并删除旧目录”。因此本变更采用以下受控方式：

1. 以本 change 的 ADDED delta 规格生成新 capability 主规格。
2. 校验新主规格并完成旧 requirement 追踪复核。
3. 在同一工作树 diff 中删除旧编号主规格目录。
4. 保留 3 个已稳定命名的现有主规格。
5. 归档时确认新 delta 已同步，并把旧目录退役作为本 change 的显式迁移任务和审查证据。

替代方案是让 archive 将旧规格删至空文件。OpenSpec 严格校验要求有效主规格，空 capability 也不能表达当前行为，因此不采用。

### 6. 投资账本 change 只更新规划标识

`022-investment-ledger-browser-web` 更名为描述性 change `investment-ledger-browser`，其 delta capability 同步改为 `investment-ledger-browser`。原草案的事件浏览、筛选、证据、持仓、估值局部失败和双后端范围保持不变；迁移占位 requirement 改写为具体场景，原 10 项未完成任务按八阶段门禁重新组织且全部保持未完成。新 capability 只有在实现、验证并归档该 change 后才进入主规格。

## Risks / Trade-offs

- [遗漏当前行为] → 为每个旧规格建立映射，按 requirement 标题复核，并保留无法确认时停止退役的门禁。
- [提前发布计划行为] → 检查主规格对应实现和测试；投资账本 Web 明确只保留 active delta。
- [active delta 指向已退役目录] → 归档已交付 change，唯一保留的 active change 在原子切换中更新路径。
- [规格过度合并] → 对独立外部合同和隐私边界保留 `icbc-asia-current-account-import`、`counterparty-account-transfer-matching` 与 `cash-investment-funding-relations`。
- [实现细节丢失] → 不删除归档或 `legacy/`，并在迁移清单保留旧 ID 到新 capability 的链接。
- [仅结构校验通过但语义仍差] → 增加占位文案、future-only 行为、active/main 双写和编号目录的语义检查。

## Migration Plan

1. 记录当前 `HEAD`、active change 状态和旧主规格清单。
2. 验证并归档已交付 active change；保留投资账本 active change。
3. 校验本 change 的 13 份新 capability delta 规格。
4. 物化新主规格，保留 3 份稳定命名主规格。
5. 更新投资账本 active change 的名称、proposal、delta 路径、design 和 tasks 引用。
6. 删除旧编号主规格，更新 `openspec/MIGRATION.md` 和仓库内活动引用。
7. 运行 OpenSpec 严格校验、doctor、语义扫雷、现有测试、`git diff --check` 和最终 diff 复核。
8. 同步并归档本 change。

回滚时恢复迁移前的主规格目录、active change 路径和 `openspec/MIGRATION.md`。代码和数据库没有变化，不需要数据回滚。

## Open Questions

无。新的 capability 边界、历史保留方式和未实现投资账本的处理均在本设计中确定。
