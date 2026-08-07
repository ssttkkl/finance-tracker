## 1. 思考与计划

- [x] 1.1 以只读 SQLite 探索量化对方账号覆盖、关系证据和同金额候选歧义。
- [x] 1.2 完成提案、delta 规格、设计和本人账户标识术语，明确不自动推断账号。

## 2. 测试与数据契约

- [x] 2.1 先添加领域失败回归：完整账号命中、唯一尾号命中、已登记不匹配、尾号冲突和缺失账号的兼容行为。
- [x] 2.2 添加关系服务 SQLite/PostgreSQL 契约，验证别名工作区隔离和关系证据不回显账号原文。
- [x] 2.3 先添加支付镜像失败回归：平台支付方式完整账号、由完整账号推导的唯一尾号、尾号冲突和跨账户拒绝。
- [x] 2.4 添加待配对候选持久化失败回归：有序候选写入、非候选拒绝、确认/驳回清空，以及 SQLite/PostgreSQL 迁移默认值。

## 3. 实施

- [x] 3.1 为 `FactView` 和 `RelationEvidence` 加入受控对方账号匹配信息；新库关系证据缺少该字段时失败关闭，不读取旧 JSON 格式。
- [x] 3.2 实现完整账号与唯一尾号的规范化、候选筛选和不匹配排除，不改变来源行快照。
- [x] 3.3 将账户别名索引传入转账关系阶段，并更新 CLI/操作文档以登记本人账户标识。
- [x] 3.4 将原始 `payment_method` 传入支付镜像阶段，并用完整账号和唯一尾号验证既有同账户候选，不向关系证据回显账号。
- [x] 3.5 收敛 `transaction_relations`：删除 `evidence_json`、`confidence`、`later_marker` 和 `later` 命令；待配对只以空的对侧端点表示，账号命中只用于内存候选筛选。
- [x] 3.6 为待配对关系增加 `candidate_fact_ids` 列，并在创建、人工确认、驳回、替换与关系读取路径落实候选约束。

## 4. 审查

- [x] 4.1 完成产品/范围、工程和安全复核，重点检查不完整别名表、尾号冲突、账号暴露和既有规则兼容。结论：仅显式别名参与单向筛选；完整账号优先、尾号冲突降级、无别名保持兼容；关系证据仅保存 `exact`/`tail`，未发现阻断性问题。
- [x] 4.2 完成范围化最终 diff 复核，确认没有真实账号、真实账单或 `~/.ft` 数据被写入仓库。结论：改动限定于关系候选、别名校验、CLI 帮助、文档、规格与去标识化测试；`git diff --check` 通过。
- [x] 4.3 完成关系持久化收敛复核。结论：端点、状态、`rule_id` 与人工决定仍有运行时消费者；过程 JSON、置信度和稍后标记无消费者，已删除；退款额度改为单次匹配内存值。
- [x] 4.4 复核候选列表只保存账本记录 ID、不包含来源账号或规则过程数据，并确认人工确认无法绕过候选集合。结论：候选列只在关系模型、仓库和关系服务中读写；投影、Web 查询和 CLI 不读取该列；非候选确认由服务拒绝。

## 5. 测试与 QA

- [x] 5.1 运行新增回归、受影响关系/CLI 测试和 SQLite/PostgreSQL 契约矩阵。`FT_TEST_POSTGRES_URL=postgresql+psycopg://huangwenlong@127.0.0.1:5432/finance_tracker_test uv run pytest -q tests/test_transaction_relations_transfer.py tests/contract/test_dual_backend_counterparty_account_transfer_matching.py tests/test_cli.py tests/test_icbc_asia_current_account.py`：65 passed。另串行运行 contract（80 passed, 8 skipped）、unit（177 passed）与 integration（69 passed, 1 skipped）。本轮删除旧库兼容路径后，`uv run pytest -q tests/test_alembic_migration.py tests/test_transaction_relations_transfer.py tests/test_relations_index_injection.py tests/contract/test_dual_backend_counterparty_account_transfer_matching.py`：36 passed，3 skipped；注入本地 PostgreSQL URL 后 `tests/test_alembic_migration.py tests/contract/test_dual_backend_counterparty_account_transfer_matching.py`：12 passed。本轮支付镜像完整账号优化：`uv run pytest -q tests/test_transaction_relations_payment_mirror.py tests/test_transaction_relations_aliases.py tests/test_transaction_relations_transfer.py tests/contract/test_dual_backend_counterparty_account_transfer_matching.py`：55 passed，5 skipped；注入本地 PostgreSQL URL 后同一契约：6 passed。
- [x] 5.3 关系持久化收敛验证：`uv run pytest -q tests/test_alembic_migration.py tests/test_016_migration_parity.py tests/test_transaction_relations_*.py tests/test_relations_*.py tests/contract/test_dual_backend_counterparty_account_transfer_matching.py tests/contract/test_dual_backend_icbc_refund_pairing.py tests/contract/test_cash_projection_parity.py tests/test_application_cash_projection_evidence.py tests/integration/test_web_sqlite.py`：148 passed，36 skipped，包含投影外键引用关系表的 SQLite 迁移回归。注入本地 PostgreSQL URL 后迁移与契约集合：41 passed。真实 `~/.ft` 已先备份为 `finance-tracker.db.before-relation-persistence-simplify-20260803-234234`，升级至 `20260803_17` 后完整性、外键检查和关系读取均通过。
- [x] 5.2 运行相称构建、`compileall`、`git diff --check`、OpenSpec 严格校验和完整 Python 回归。已完成 `uv build`、`uv run python -m compileall -q src`、`git diff --check`、`openspec validate --all --strict`。完整 `uv run pytest -q` 结果为 1,161 passed、115 skipped、1 failed：`tests/test_wealth_performance.py::test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets[sqlite]` 的冷重建 p95 为 5.418 秒，超过 5 秒预算；该财富性能路径不涉及本变更。用户已于 2026-08-03 明确豁免此 10 万事实性能门禁，本变更不再以它阻塞；后续若修改财富重建路径，须在稳定会话补跑 `uv run pytest -q tests/test_wealth_performance.py`。
- [x] 5.4 运行待配对候选的 SQLite/PostgreSQL 迁移与关系服务契约，以及受影响的投影回归。SQLite 集合 `149 passed, 36 skipped`；`FT_TEST_POSTGRES_URL=postgresql+psycopg://huangwenlong@127.0.0.1:5432/finance_tracker_test` 集合 `80 passed`。真实 `~/.ft` 已备份为 `finance-tracker.db.before-open-leg-candidates-20260804-000636`，升级至 `20260803_18` 并重扫；8 条待配对退款关系中 4 条保存候选，共 12 个候选 ID。

## 6. 发布准备

- [x] 6.1 记录新库部署与回滚方式，以及用户显式登记别名的操作边界。`20260803_16` 建立 `counterparty_account` 列，`20260803_17` 删除关系过程证据列；部署前备份数据库，回滚时恢复备份。用户以 `ft relations alias-add --type account_identifier|card_tail --value … --account …` 显式登记别名。

## 7. 反思

- [x] 7.1 记录“对方账号仅在显式本人账户标识下作为单向候选筛选条件”的防回归规则。完整账号、唯一尾号、冲突、不匹配、缺失字段、工作区隔离和隐私边界均由领域与双后端回归覆盖。

## 8. 同步与归档

- [x] 8.1 同步 delta 主规格、严格校验并归档已完成变更。
