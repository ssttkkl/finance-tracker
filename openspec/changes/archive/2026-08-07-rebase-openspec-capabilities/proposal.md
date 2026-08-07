## Why

当前 OpenSpec 主规格沿用了 Spec Kit 的顺序编号和一次性 feature 名称，把历史变更误建模为长期能力。结果是同一领域行为分散在多个主规格中，已废弃、尚未实现和仅属于迁移过程的内容也进入了当前行为事实源；后续变更难以判断应修改哪个 capability。

本变更重新建立按稳定领域能力组织的主规格基线，使 `openspec/specs/` 只描述已经实现的当前行为，并让后续 OpenSpec 变更通过 delta 规格持续修改同一 capability。

## What Changes

- **BREAKING（规划契约）**：退役 `001-*` 至 `025-*` 等按顺序 feature 命名的主规格标识，建立无编号、按领域能力命名的主规格；不改变产品运行时接口和账务结果。
- 将数据库运行、账户、导入、账本记录、现金流水分类、投资事件、交易关系、估值、同步、财富归因和账本浏览分别收敛为稳定 capability。
- 从主规格移除已被后续行为推翻的 PostgreSQL-only 要求、尚未实现的投资账本视图、一次性迁移步骤、内部重构结构和通用占位场景；对应历史继续保存在既有变更归档及 `legacy/` 目录。
- 先校准现有 active change 与主规格的同步状态：完成并归档已经交付的变更，只保留尚未实现的投资账本浏览变更，并将其 delta 路径改为新的 capability 名称。
- 用可验证的行为场景替代“迁移前规格所描述的有效业务上下文”“功能需求基线”“可度量验收结果”等迁移占位内容。
- 更新 `openspec/MIGRATION.md`，记录旧 feature 规格到新 capability 的逐项映射、未实现行为的去向和历史保留策略。

### Scope

- 重组 OpenSpec 主规格和仍在进行中的 delta 规格。
- 校准 active change、变更归档、迁移清单和规格引用。
- 以当前实现、测试和既有归档证据确认主规格内容。

### Non-goals

- 不修改 Python、Web、数据库 schema、迁移脚本或用户可见行为。
- 不重写既有 `openspec/changes/archive/` 和 `legacy/` 历史证据。
- 不把尚未实现的投资账本视图伪装成当前能力。
- 不提交、推送、创建 PR、合并或部署。

## Capabilities

### New Capabilities

- `runtime-database`: PostgreSQL 与 SQLite 的显式选择、等价行为、事务和安全边界。
- `multi-currency-accounts`: 账户、分币种余额、账户标识和跨币种操作合同。
- `statement-import`: 账单路由、完整性、行级幂等、来源行快照和失败原子性。
- `ledger-records`: 现金流水与投资事件共享的账本记录、公共标识、溯源和引用完整性。
- `cash-record-classification`: 现金流水标准记录类型、子类型及来源字段分类边界。
- `investment-event-model`: 投资事件记录类型、资产方向、费用、本位资产和成本语义。
- `investment-statement-import`: 券商文件导入、来源动作映射、核对和来源专用约束。
- `transaction-relations`: 同笔支付、转账、还款和退款关系的扫描、审查、持久化与投影语义。
- `portfolio-valuation`: 行情报价、汇率折算、估值状态、批量编排和有界预算。
- `investment-connector-sync`: 外部投资数据连接器、凭据、增量游标和同步幂等。
- `wealth-attribution`: 财富变化分解、覆盖状态、趋势、证据和可重建读模型。
- `cash-ledger-browser`: 收支投影、筛选、分页、证据详情、可访问性和浏览器展示行为。
- `time-semantics`: 后端 UTC 数据边界、来源时间解释、日期筛选和浏览器本地时区展示。

### Modified Capabilities

- `cash-investment-funding-relations`: 把已由本地时区 change 取代的固定 `Asia/Shanghai` 业务日窗口统一为 UTC，消除同一主规格内的时间合同矛盾。

`counterparty-account-transfer-matching` 和 `icbc-asia-current-account-import` 已按稳定能力命名，完成 active change 同步后继续作为独立主规格。

## Impact

- 影响 `openspec/specs/`、`openspec/changes/`、`openspec/MIGRATION.md` 及其中的 capability 引用。
- 产品代码、数据库、API、CLI 和 Web 运行时不发生变化；主要风险是遗漏当前行为、保留已废弃行为或提前发布尚未实现的行为。
- 迁移必须保留旧规格到新 requirement 的追踪表，并以 OpenSpec 严格校验、语义扫雷、现有测试和最终 diff 复核证明没有改变财务语义。

### Migration and rollback

- 迁移时先物化新 capability，再在同一受审查 diff 中退役旧编号主规格；任何 requirement 无法确认去向时停止退役对应旧规格。
- 既有归档和 `legacy/` 目录保持不变，可作为回查依据。
- 回滚只需恢复本变更前的 `openspec/specs/`、active change 路径和迁移清单；不涉及运行时数据回滚。
