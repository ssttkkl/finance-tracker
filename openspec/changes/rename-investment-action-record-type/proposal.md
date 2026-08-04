## Why

投资事件用 `action` 表示业务记录类型，而现金流水使用 `record_type`，两个账本的命名和配对语义不一致。现有 `deposit` / `withdraw` 还混入利息、税费和外汇净额调整，不能安全地用于收支账户与投资账户的资金调拨匹配。

## What Changes

- **BREAKING** 将 `investment_events.action` 重命名为 `record_type`，并在数据库、领域对象、导入合同、CLI/API 查询与测试中统一使用新名称。
- 为投资事件增加 `record_subtype`，使 `deposit` / `withdraw` 只能表达 `external_funding` 或 `subaccount_transfer`；费用、税费、外汇调整、奖励和出金冲回使用非出入金记录类型或子类型。
- 新增收支账户现金流水与投资事件之间的资金调拨关系及审查流程。扫描规则只消费规范化记录类型、记录子类型、方向、精确金额、币种和业务日，不绑定银行或券商名称。
- 已确认的外部资金调拨将对应现金流水归类为不计收支的银证转账投影，同时保留两端来源和关系证据。
- 对现有 SQLite 与 PostgreSQL 数据执行可审计迁移：保留来源行快照，回填新字段，纠正不符合出入金语义的历史投资事件，并提供可恢复的回滚步骤。

## Capabilities

### New Capabilities

- `cash-investment-funding-relations`：扫描、审查和持久化收支账户与投资账户之间的外部资金调拨关系。

### Modified Capabilities

- `009-investment-account-import`：投资导入以 `record_type` 和 `record_subtype` 表达资金移动语义，并保留来源溯源。
- `013-investment-cash-event-kinds`：投资现金事件以 `record_type` 取代 `action`，并约束出入金、内部子账户调拨和非资金调拨事件的语义。
- `020-cash-ledger-browser-web`：已确认的收支—投资资金调拨不得作为消费或收入计入收支账本。

## Impact

- 受影响持久化：`investment_events`、新的资金调拨关系表、SQLite 与 PostgreSQL Alembic 迁移和现有本地数据库回填。
- 受影响领域与应用层：投资导入、投资持仓重放、关系扫描与审查、收支投影、CLI/Web 查询契约。
- 受影响导入器：东方证券、盈立、IBKR 及未来导入器；各导入器只负责从原生字段归一语义，匹配器不依赖导入渠道。
- 需要双后端迁移与契约矩阵、历史数据回填验证、收支投影重建和安全审查。
