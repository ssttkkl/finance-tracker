## Why

收支账本的经济类型筛选目前把消费、收入、个人转账和银证转账写死在前端，无法反映当前账本实际存在的内部转账子类型。使用者无法从筛选控件理解类型层级，也无法在新增子类型后直接筛选。

## What Changes

- 列表读取返回由当前活动投影数据集聚合出的经济类型—子类型筛选树。
- 增加规范的 `transfer_subtype` 查询参数，使子类型可与其父级经济类型一同筛选并进入版本化 cursor。
- 将收支账本的经济类型筛选改为原生分组选择控件，选择项和可用子类型均来自后端返回数据。
- 保留旧的 `economic_type=bank_security_transfer` 查询语义，兼容现有书签、调用方和测试。
- 不新增经济类型、数据库字段、迁移、资金调拨匹配规则或原始账单数据写入。

## Capabilities

### New Capabilities

- 无。

### Modified Capabilities

- `020-cash-ledger-browser-web`：将经济类型筛选改为数据库驱动的一级类型—子类型树，并扩展版本化读取筛选契约。

## Impact

- 影响收支投影查询 DTO、关系型读取实现和 `/api/v1/cash-projections` 查询参数与响应结构。
- 影响 `CashFilters` 前端类型、筛选控件、筛选摘要、请求参数及其前端测试。
- 需要 SQLite 与本机 PostgreSQL 的同一查询契约测试、前端组件测试、生产预览和响应式视觉验证。
- 无数据库结构变更；部署回滚只需回退应用版本，既有 `bank_security_transfer` 查询继续有效。
