## 1. 思考与计划

- [x] 1.1 以只读 SQLite 探索量化对方账号覆盖、关系证据和同金额候选歧义。
- [x] 1.2 完成提案、delta 规格、设计和本人账户标识术语，明确不自动推断账号。

## 2. 测试与数据契约

- [x] 2.1 先添加领域失败回归：完整账号命中、唯一尾号命中、已登记不匹配、尾号冲突和缺失账号的兼容行为。
- [x] 2.2 添加关系服务 SQLite/PostgreSQL 契约，验证别名工作区隔离和关系证据不回显账号原文。

## 3. 实施

- [x] 3.1 为 `FactView` 和 `RelationEvidence` 加入受控对方账号匹配信息及 JSON 兼容读取。
- [x] 3.2 实现完整账号与唯一尾号的规范化、候选筛选和不匹配排除，不改变来源行快照。
- [x] 3.3 将账户别名索引传入转账关系阶段，并更新 CLI/操作文档以登记本人账户标识。

## 4. 审查

- [x] 4.1 完成产品/范围、工程和安全复核，重点检查不完整别名表、尾号冲突、账号暴露和既有规则兼容。结论：仅显式别名参与单向筛选；完整账号优先、尾号冲突降级、无别名保持兼容；关系证据仅保存 `exact`/`tail`，未发现阻断性问题。
- [x] 4.2 完成范围化最终 diff 复核，确认没有真实账号、真实账单或 `~/.ft` 数据被写入仓库。结论：改动限定于关系候选、别名校验、CLI 帮助、文档、规格与去标识化测试；`git diff --check` 通过。

## 5. 测试与 QA

- [x] 5.1 运行新增回归、受影响关系/CLI 测试和 SQLite/PostgreSQL 契约矩阵。`FT_TEST_POSTGRES_URL=postgresql+psycopg://huangwenlong@127.0.0.1:5432/finance_tracker_test uv run pytest -q tests/test_transaction_relations_transfer.py tests/contract/test_dual_backend_counterparty_account_transfer_matching.py tests/test_cli.py tests/test_icbc_asia_current_account.py`：65 passed。另串行运行 contract（80 passed, 8 skipped）、unit（177 passed）与 integration（69 passed, 1 skipped）。
- [ ] 5.2 运行相称构建、`compileall`、`git diff --check`、OpenSpec 严格校验和完整 Python 回归。已完成 `uv build`、`uv run python -m compileall -q src`、`git diff --check`、`openspec validate --all --strict` 和除固定 10 万事实财富性能门禁外的完整 Python 分组回归；该门禁在本机运行超过 2 分钟未得结论，未计为通过。补跑条件：在无执行时限的本地会话串行运行 `uv run pytest -q tests/test_wealth_performance.py`。

## 6. 发布准备

- [x] 6.1 记录无需迁移的部署与回滚方式，以及用户显式登记别名的操作边界。无需数据迁移；部署后用户以 `ft relations alias-add --type account_identifier|card_tail --value … --account …` 显式登记。回滚时停止使用新别名类型和证据字段，不修改既有事实、关系或别名。

## 7. 反思

- [x] 7.1 记录“对方账号仅在显式本人账户标识下作为单向关系证据”的防回归规则。完整账号、唯一尾号、冲突、不匹配、缺失字段、工作区隔离和隐私脱敏均由领域与双后端回归覆盖。

## 8. 同步与归档

- [ ] 8.1 同步 delta 主规格、严格校验并归档已完成变更。
