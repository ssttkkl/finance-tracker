## 背景

生产 Web 构建的现金浏览走 `LocalFirstFinanceRepository`：会话恢复时读取 `/api/v1/ledger/version` 与 `/api/v1/ledger/snapshot`，列表从本地规范事实派生。E2E 旧夹具只 mock `/api/v1/cash-projections`，因此本地恢复后账本为空；且旧断言直接使用 `projection_id`（如 `draft-11`），派生后的稳定对象 ID 为 `cash-entry:<entry_id>`。

## 方案

1. 在 `compose/qa/web/tests/compose-fixtures.ts` 增加 `snapshotFixture(entries, options)`，输入保持接近现有行数据（occurred_at、amount、currency、account、category、note、counterparty、state、issues、relations），输出完整 `LedgerSnapshotDto` JSON。
2. `installComposeApiFixtures` 增加 `snapshot` 选项；未提供时按默认单条支出构造，保证既有非数据用例可用。
3. 各 E2E 文件把自定义 `/cash-projections` 路由替换为设置 `snapshot`；记录 ID 选择器改为 `cash-entry:<entry_id>`，并在需要时转义选择器。
4. 逐文件运行、修正断言；不引入产品代码分支或测试专用开关。

## 风险

- 派生结果与旧投影字段并非一一对应（如 `statistics_eligible`、`confirmation_status`、`attention_count`），需要从规范事实和 issue／关系数据重新表达。
- 旧用例的部分断言可能依赖已退役语义；确实不再成立的用例按新行为更新，并在 tasks 记录。
