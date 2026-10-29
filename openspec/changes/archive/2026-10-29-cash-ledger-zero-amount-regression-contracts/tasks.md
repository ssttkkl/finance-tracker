# 零元筛选后的既有投影回归合同

## 1. 思考

- [x] 1.1 检查 PR #105 的失败清单和后端分片日志。根因是旧用例默认查询净额为零的全额退款、零额转账；新规格默认隐藏这些列表项，但投影与详情证据仍保留。
- [x] 1.2 定为 C 类局部测试修正。保持筛选默认开启和财务投影行为；只调整验证零元对象可读取／维护的既有测试条件，并在适合的用例中明确验证默认隐藏。
- [x] 1.3 任务范围仅限后端合同测试分类及本记录；不改生产代码、用户行为、CI 工作流或性能断言。识别并将误落入普通分片的固定负载延迟预算用例标记为 `performance`，使其进入既有专项任务。

## 2. 计划

- [x] 2.1 验收：全额退款、跨币种转账、普通转账、证券资金调拨及退款关系在默认过滤下不进入列表；显式关闭筛选后仍能读取相同零元投影与关联证据。
- [x] 2.2 回滚：还原本记录与测试修改即可；无生产代码、数据或迁移影响。

## 3. 任务拆分

- [x] 3.1 将旧测试中验证零元结果的查询改为显式 `hide_zero_amount=False`；涉及 SQLite 与 PostgreSQL 共用合同的测试保持两后端覆盖。
- [x] 3.2 确认不会改变非零结果、金额计算、关系确认、详情证据或筛选默认值的测试断言。
- [x] 3.3 发现 `tests/test_cash_source_batch.py` 有 4 个含 `< 2s` 固定负载延迟预算的用例未标 `performance`，普通分片因此会受机器负载影响；只补正确标记，不改固定输入、线性空间断言或延迟阈值。

## 4. 构建

- [x] 4.1 在本地重现 PR 中 8 个失败参数化用例；将验证零元投影读取的查询改为显式 `hide_zero_amount=False`，并在迁移、个人购汇和退款关系用例中增加／保留默认隐藏断言。无生产实现改动。
- [x] 4.2 为 4 个固定负载延迟预算用例补 `pytest.mark.performance`，不改测试断言，正确纳入既有性能专项任务并从普通分片排除。

## 5. 审查

- [x] 5.1 复核变更只明确 opt out 用于读取零元投影及证据；覆盖原有关系、迁移、API 和子类型合同。相关用例同时验证默认隐藏，未放宽金额或关系断言。
- [x] 5.2 `git diff --check` 通过；差异涉及 7 个后端测试文件及本记录，无生产代码或 CI 工作流变化。

## 6. 测试与 QA

- [x] 6.1 2026-10-29 16:33 Asia/Shanghai 运行受影响用例及来源批次合同，使用 `-m 'not performance'`：50 passed、6 skipped（本机未配置 PostgreSQL）、4 deselected（即该文件中 4 个性能标记用例）。不运行性能测试。
- [x] 6.2 2026-10-29 检查 PR #105 非性能 CI：PR Checks run `40843162709` 在代码 HEAD `f4271633ea952722ff86e63b30a73e962e84bc5f` 的 SQLite、PostgreSQL 与 Compose client checks 全部通过；Compose shared/native run `40843162588` 完成 Web、Android 与 iOS simulator checks 并通过。PR Checks 唯一失败项为 `Backend (Performance)`，按用户范围排除，不分析或修复该 job。
- [x] 6.3 2026-10-29 16:34 Asia/Shanghai 执行 `openspec validate --all --strict`（26 passed、0 failed）、`openspec doctor`（根目录正常）与 `git diff --check`（通过）。

## 7. 发布

- [x] 7.1 测试修正已提交并推送到 PR #105 同一分支 `unarmed-deer`；代码 HEAD `f4271633ea952722ff86e63b30a73e962e84bc5f` 的全部非性能 checks 通过。不合并 PR。
- [x] 7.2 已归档至 `openspec/changes/archive/2026-10-29-cash-ledger-zero-amount-regression-contracts/`；不合并 PR。

## 8. 反思

- [x] 8.1 默认隐藏结果与检查零元投影及证据使用不同查询条件；固定负载延迟预算测试归入既有性能分类，避免普通 CI 误报与列表行为断言混淆。

## 验证证据

- 比较基线：原 PR head `cb2a284779ad2e75369a7be6893aca28cba008dd`，PR #105 base `89682e5237e7bbe341ffc4b79ec84c2e8825a88c`。
- 远端非性能验证：PR Checks run `40843162709`（代码 HEAD `f4271633ea952722ff86e63b30a73e962e84bc5f`，2026-10-29 08:34:54–08:58:54 UTC）中 SQLite、PostgreSQL 及 Compose client 全部通过；Compose shared/native run `40843162588`（同一 HEAD，2026-10-29 08:34:53–09:05:19 UTC）通过。PR Checks run 的唯一失败 job 是 `Backend (Performance)`，按用户要求排除。
- 远端失败证据：Actions run `40841186785` 的 SQLite shard 0、1、3、4、5 与 PostgreSQL shard 0、4 报错；失败断言均为旧测试按默认筛选读取净额 0 投影。性能 job 按用户要求排除。
- 本地修复验证命令：`PYTHONPATH=tests:.:src uv run pytest -q -m 'not performance' tests/contract/test_cash_full_refund_visibility.py::test_upgrade_converts_active_and_staging_refunds_without_rebuilding_sources tests/test_transaction_relations_projection.py::test_personal_fx_relation_check_replaces_income_and_expense_projections tests/contract/test_web_api.py::test_projection_api_returns_visible_internal_transfer tests/contract/test_cash_maintenance_api.py::test_joint_current_relation_confirmation_is_one_http_task_and_keeps_zero_result tests/test_application_web_queries.py::test_projection_filter_options_and_subtype_filter_follow_active_visible_data tests/test_application_web_queries.py::test_projection_page_includes_visible_internal_transfer_and_filter_option tests/test_relational_cash_projection_evidence.py::test_evidence_exposes_only_whitelisted_funding_relation_fields tests/test_cash_source_batch.py`：50 passed、6 skipped、4 deselected，0 failures；未运行性能测试。该次工作树 `HEAD` 为 `d78397f26ae6442b2bf68d1d05b20d8cae4c6000`；`openspec validate --all --strict` 26 passed；doctor 与 `git diff --check` 通过。
