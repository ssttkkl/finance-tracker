## 1. 解析与关系安全

- [x] 1.1 为工行信用卡与借记卡 PDF 的“转账”摘要提取、来源快照和 `record_type` 标准化添加失败回归。
- [x] 1.2 删除错误的工行关系桥接实现，恢复正确分类后的普通转账关系路径。
- [x] 1.3 为购汇双向唯一、空/冲突来源和后到 `fx_in` 有界重评添加失败回归并实现规则。
- [x] 1.4 为日期精度、人工绑定、端点占用和竞争 pending 添加失败回归并完成规则。

## 2. 证据与受控重建

- [x] 2.1 扩展关系证据安全白名单和 Web 证据详情展示，覆盖允许字段与未知字段过滤。
- [x] 2.2 在隔离 SQLite 从 `.ft/bills` 重建并导入工行账单，验证信用卡与借记卡“转账”摘要标准化、目标关系和投影。
- [x] 2.3 完成完整现金账单重建、关系/投影重建、重跑幂等和事实/identity 对账：隔离库现金事实与来源 identity 均为 11,394，和业务库一致；`integrity_check=ok`，关系检查与投影重建成功，支付宝与微信重导入均为幂等。东方证券 PDF 的 497 条 `dfzq_pdf` 投资事实不属于本次现金账本与购汇规格的验收范围，按用户决定不纳入重建或业务库替换前置条件。

## 3. 验证与交付证据

- [x] 3.1 运行受影响的解析、关系、投影和 API 测试（最终 SQLite 回归：`294 passed, 25 skipped`）。
- [x] 3.2 运行 SQLite 与本地真实 PostgreSQL 契约、前端 Vitest、Web QA、完整测试、OpenSpec `validate --all --strict`、`doctor`、`git diff --check` 与范围化 gstack `/review`：SQLite 受影响回归 `294 passed, 25 skipped`；本地 PostgreSQL `finance_tracker_test` 为 `47 passed`；Vitest `32 passed` 且生产构建通过；本地 `http://127.0.0.1:5173` 收支账本、证据详情打开/关闭及控制台均正常。全量 Python 为 `1139 passed, 113 skipped, 1 failed`；唯一失败是既有 SQLite 财富冷重建 P95 `6.56s > 5s`，按用户决定不作为本变更的完成阻断项。
- [x] 3.3 于 2026-08-03 15:23:23 CST 在 `HEAD=3ed6dbafa42ebaff9d3721c0a259b92031418cad`、比较基线 `origin/main` 的合并基线 `8c18ed7ecff6b31cd5adcc18becb4e4e09035f55` 记录验证结果和风险处置。业务库替换尚未授权且未执行；东方证券投资事实和既有财富性能门槛按用户明确决定不阻断本购汇变更的完成与归档。
