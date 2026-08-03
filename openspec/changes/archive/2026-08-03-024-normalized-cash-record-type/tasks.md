# Tasks

## 1. 迁移后的历史任务清单

- [X] T001 完成 `spec.md`，锁定 `record_type` 枚举、来源映射、`repayment` 独立语义、`other` 兜底和重建策略。
- [X] T002 完成 `research.md`、`plan.md`、`data-model.md`、`quickstart.md` 和 `contracts/record-type.md`。
- [X] T003 更新 `DOMAIN_GLOSSARY.md`，加入标准记录类型和还款记录类型。
- [X] T004 完成 OpenSpec 一致性分析，确认 FR/SC/任务覆盖且无 CRITICAL/HIGH；2026-08-01，`check-prerequisites.sh --json --require-tasks --include-tasks` 通过，人工复核产物链路覆盖分类、持久化、双后端、重建和回滚。
- [X] T005 [P] 在 `tests/test_record_type.py` 增加各来源分类纯函数测试，覆盖消费、转账、退款、还款、收入、投资、利息、费用、外汇和 `other`。
- [X] T006 [P] 在 `tests/test_statement_import_mapping.py` 增加导入输出包含 `record_type`、来源快照保留原生字段的测试。
- [X] T007 [P] 在 `tests/test_postgres_statement_import.py` 增加 `cash_transactions.record_type` 持久化和 `repayment` 样例测试。
- [X] T008 [P] 在 `tests/contract/test_dual_backend_record_type.py` 增加 SQLite/真实 PostgreSQL schema 与导入结果合同测试。
- [X] T009 运行 T005–T008；实现前 14 个分类/持久化断言失败，原因是输出行和模型缺少 `record_type`，实现后全部转绿。
- [X] T010 在 `src/ft/domain/record_type.py` 定义稳定类型集合和纯分类函数。
- [X] T011 在 `src/ft/convert.py` 接入来源分类，确保 `_build_output_row` 和现金解析输出包含 `record_type`。
- [X] T012 在 `src/ft/adapters/relational/models.py` 增加非空 `cash_transactions.record_type` 字段，并生成 Alembic schema 变更。
- [X] T013 在 `src/ft/adapters/relational/repositories.py` 持久化、读取和列出 `record_type`；缺失导入值不得写入空值。
- [X] T014 仅为现有手工现金写入保留 `other` 显式默认；不为旧数据库记录增加回填或关系兼容逻辑。
- [X] T015 运行新增测试、受影响导入/关系测试、完整 pytest、类型/构建检查和 `git diff --check`；Python 全量 `1076 passed, 105 skipped`，定向 49 passed/1 skipped，Web build 受既有 `tests/CashTable.test.tsx:37` 参数错误阻断。
- [X] T016 SQLite/真实 PostgreSQL 契约矩阵已执行：SQLite 通过；真实 PostgreSQL 因未设置 `FT_TEST_POSTGRES_URL` 跳过，补跑命令为 `FT_TEST_POSTGRES_URL=<dedicated_test_db> uv run pytest -q tests/contract/test_dual_backend_record_type.py`。
- [X] T017 用 `.ft/bills` 的全部现金账单导入新 SQLite，检查总数 11,394、`record_type` 非空、`other=0`、`repayment=69` 和完整分布；另保留 533 条已有投资事实以覆盖加密东方证券 PDF。
- [X] T018 已备份当前 `/Users/huangwenlong/.ft/finance-tracker.db` 为 `/Users/huangwenlong/.ft/finance-tracker.db.before-record-type-rebuild-20260801-2330`，备份与替换后的当前库均通过 `PRAGMA integrity_check`；用户退出 DBeaver 后，于 2026-08-01 完成新库原子替换。替换后当前库为 Alembic `20260801_13`，现金记录 11,394 条、投资事实 533 条，收支投影为 `ready` 且投影条目/成员均为 11,394。
- [X] T019 完成范围化差异核对、`git diff --check`、OpenSpec artifact 一致性复核和收敛检查；当前实现覆盖 spec 中的导入分类、持久化约束、SQLite 迁移、双后端契约与全量重建。Python 全量回归已通过；未解决风险为 Web 构建仍受既有 `tests/CashTable.test.tsx:37` 参数错误阻断，真实 PostgreSQL 契约因未提供 `FT_TEST_POSTGRES_URL` 未执行。
- [X] T020 将消费退款、撤销/冲正和提现边界拆分为 `refund`、`reversal` 和提现专用类型，并更新来源映射与持久化约束；新增迁移 `20260802_14`。
- [X] T021 增加撤销优先于退款、提现不归普通转账的失败回归测试及 SQLite/真实 PostgreSQL 合同测试；定向与全量测试已通过。
- [X] T022 重新用 `.ft/bills` 导入新数据库并统计方向类型分布：`withdrawal_in=2`、`withdrawal_out=58`、`refund=586`、`reversal=0`；未保留通用 `withdrawal` 类型。
- [X] T023 将提现拆分为 `withdrawal_in` 和 `withdrawal_out`，更新微信、支付宝和银行来源的方向映射、关系候选角色、SQLAlchemy 约束、Alembic `20260802_15` 迁移和运行时 schema revision。
- [X] T024 增加提现入账/出账分类、专用配对方向和 SQLite/真实 PostgreSQL 合同测试；定向分类/关系/迁移/合同测试 `44 passed, 5 skipped`，提现及转账回归 `39 passed`。
- [X] T025 在新规则验证通过后重建并替换当前业务库：现金账单 11,394 条、投资事件 533 条；关系重建 2,600 条，投影状态 `ready`。东方证券 PDF 无法解析，保留旧库中同源正式投资事件 497 条；17 份盈立证券 PDF 因缺少密码未产生事件，已记录为未解决输入问题。

## 2. 迁移确认

- [x] 2.1 保留原始任务、验证证据和未解决风险。
- [x] 2.2 将行为需求投影到 OpenSpec 主规格。
