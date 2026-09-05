## 1. 思考与范围锁定

- [x] 1.1 阅读项目上下文、领域词表、账单导入与交易关系主规格，以及现有现金导入和退款修复变更
- [x] 1.2 在最新 `origin/refactor/web`（`e910196`）上重放 `.ft/bills` 中支付宝/微信与工行借记卡的正序、逆序导入，记录工行账户分组和投影失败边界
- [x] 1.3 完成 `/grilling` 需求澄清；确认范围包含工行来源账户归组、顺序无关关系规划、内容/关系确定性和失败关闭

## 2. 失败回归测试先行

- [x] 2.1 为工行借记卡本方账号归组和渠道字段保留增加失败测试
- [x] 2.2 为已有银行退款对与同批平台退款对的消费端/退款端镜像对齐增加失败测试，并断言关系计划可构建合法收支投影
- [x] 2.3 增加正序与逆序的最小导入集成回归，比较业务行标识、事实字段、关系端点/类型/状态/规则和投影摘要

## 3. 来源账户身份修复

- [x] 3.1 在工行借记卡转换边界提取本方账号作为稳定来源账户身份，生成不含完整账号的显示证据
- [x] 3.2 保持交易渠道为 `payment_method`，保证来源行快照仍只包含 PDF 原始列和值
- [x] 3.3 运行工行解析、账户映射、来源快照和幂等测试并确认旧渠道字段语义不回归

## 4. 顺序无关关系规划

- [x] 4.1 在通用付款镜像前实现基于已确认/同批退款对的跨来源消费端与退款端对齐
- [x] 4.2 将对齐镜像纳入占用集合和上下文，避免普通镜像与钻石退款产生多个投影根
- [x] 4.3 保持候选冲突待审核、合法退款钻石、部分退款剩余金额和精确 `Decimal` 语义
- [x] 4.4 运行受影响领域/应用测试，确认同批关系不会重复持久化且重复扫描幂等

## 5. 审查与验证

- [x] 5.1 完成最新未提交 diff 的独立范围/工程/安全复核；未发现 critical，发现的 6 项 major（工行解析器优先级、业务 ID 差异、对称候选多候选保护、人工端点保护、拒绝决定时序、退款证据冲突）和 2 项 minor（旧工行映射回退、退款候选笛卡尔积）均已修复，OpenSpec 过时记录同步回写 `design.md`/本文件
- [x] 5.2 运行 SQLite 受影响矩阵、构建、类型/编译检查、`git diff --check` 和严格 OpenSpec 校验；受影响测试通过，完整回归仅保留一个已知基线查询失败，详情见验证记录
- [x] 5.3 若配置了名称以 `_test` 结尾的 `FT_TEST_POSTGRES_URL`，补跑同一导入/关系合同矩阵；当前未配置，因此准确记录为未运行
- [x] 5.4 本次相对基线没有 Web UI、路由、交互或用户可见文案改动；真实浏览器 QA 与 Hallmark `audit` 不适用，密码入口 UI 属于基线
- [x] 5.5 使用 `.ft/bills` 在全新 SQLite 库完成支付宝/微信→工行和工行→支付宝/微信两种顺序重放，比较内容与关系证据

## 6. 发布、交付与反思

- [x] 6.1 回写实际命令、结果、当前 `HEAD`、比较基线、残余风险和回滚说明
- [x] 6.2 创建聚焦提交 `62c56e0`、验证记录提交 `d2992f3`，同步最新 `refactor/web` 后推送 `fix-icbc-013958-pdf-recognition-failure`，创建 PR #77
- [x] 6.3 PR #77 无远端检查项且状态为可合并，已合入 `refactor/web`；合并提交为 `1f5f906682e862acd50f5ca97dc02916f4b868fb`。发布后观察工行来源账户映射组、待审核镜像数量和投影构建失败日志。

## 验证记录

- 基线：本次实现提交为 `62c56e0`，随后已合入最新 `origin/refactor/web`（合并提交 `1104467`）；当前工作分支为 `fix-icbc-013958-pdf-recognition-failure`，PR 基线为 `origin/refactor/web` `6ddea6b`。
- 失败回归与受影响矩阵（本次最终功能修复后）：`.venv/bin/python -m pytest -p no:cacheprovider tests/test_convert.py tests/test_complete_statement_source_payload.py tests/test_statement_account_mapping.py tests/test_cash_import_wizard.py tests/test_transaction_relations_payment_mirror.py tests/test_transaction_relations_refund.py tests/test_transaction_relations_cross_batch.py tests/test_import_relation_planning.py` → `380 passed, 13 skipped`。
- 全量回归：`PYTHONDONTWRITEBYTECODE=1 .venv/bin/python -m pytest -p no:cacheprovider` 在更新旧工行哈希断言前为 `1552 passed, 192 skipped, 2 failed`（工行旧断言和一个基线查询失败）；更新断言后，受影响矩阵为 `380 passed, 13 skipped`，并单独复现基线失败 `tests/test_application_queries.py::test_list_accounts_values_cash_and_investment_with_cost_fallback`（`Broker=25` 对比期望 `29`）。该失败不涉及本变更文件，因此不能宣称全量无失败。
- 真实账单双顺序重放：使用 `.ft/bills` 中 3 份微信、3 份支付宝和 1 份工行 PDF，在全新临时 SQLite 与临时导入会话内执行「微信/支付宝后工行」和「工行后微信/支付宝」。两种顺序均成功，导入行数分别为 `1170,1176,985,1367,664,1028,1206` 与逆序对应值；事实数均为 `7596`，活动关系均为 `948`（`919 accepted`、`29 pending_review`），投影成员均为 `7596`，投影条目均为 `6677`，业务事实集合与活动关系集合 `delta=0`。密码只在进程内传递，未写入仓库或日志。
- 受影响关系测试已覆盖人工拒绝保护、人工选择覆盖系统边、工行等强候选待审核、退款标题/对方账号证据冲突和缓存 ID 字符串化；真实重放验证两种导入顺序收敛到同一结果。
- 编译与格式、OpenSpec 和构建：最终 artifact 更新后已补跑 `python -m compileall -q src tests`、`git diff --check`、`openspec validate order-independent-icbc-import --type change --strict`、`openspec doctor`、`uv build`，结果见第 8 节。
- PostgreSQL：环境未配置 `FT_TEST_POSTGRES_URL`，因此 PostgreSQL 契约矩阵未运行；补跑条件为配置指向名称以 `_test` 结尾的专用数据库后，执行同一受影响矩阵。
- 浏览器 QA：本次相对 `origin/refactor/web` 没有 Web UI、路由、交互或用户可见文案文件变更，故不适用真实浏览器 QA 与 Hallmark `audit`；此前密码入口相关 UI 已在基线中，不在本次 diff 内。
- 回滚：无数据库迁移；回滚应用提交即可，不删除或覆盖既有来源快照、业务事实或关系历史。发布后观察工行账户映射组数量、活动镜像待审核数及投影构建失败日志。

## 7. 信用卡与借记卡规则统一扩展

- [x] 7.1 更新 delta 规格、设计和验收记录，明确两类工行仅在 PDF 提取边界分开，来源身份、敏感数据和关系语义统一
- [x] 7.2 先增加信用卡来源身份、同卡跨渠道归组、不同卡业务行隔离、缺失身份失败关闭和退款归组的失败回归
- [x] 7.3 先增加信用卡与借记卡共享的支付镜像对称候选、等强候选待审核和人工保护回归
- [x] 7.4 在信用卡/借记卡解析输出中保存完整或 PDF 明确稳定掩码来源身份、尾号字段、渠道和稳定业务行标识；缺失稳定身份时失败关闭，不增加历史工行身份兼容回退
- [x] 7.5 将工行银行侧关系规则统一覆盖 `icbc_credit` 与 `icbc_debit`，保留信用卡还款/转账分类专属语义
- [x] 7.6 使用 2 份工行信用卡 PDF、1 份工行借记卡 PDF、3 份微信和 3 份支付宝账单完成隔离 SQLite 正反顺序导入验证，记录事实、关系、投影和敏感数据结果
- [x] 7.7 补跑受影响测试、完整回归、OpenSpec、构建、差异检查及可用的 PostgreSQL 契约矩阵，并独立复核当前 diff

## 8. 本轮扩展验证记录

- 本轮扩展基线为 `a1049bc`；当前功能代码、回归测试和 OpenSpec 记录仍在工作树，尚未形成新的提交或 PR。最终 diff 另收紧了默认工行映射模板，删除按 `*` 通配的信用卡/借记卡规则。
- 受影响矩阵：`PYTHONDONTWRITEBYTECODE=1 .venv/bin/python -m pytest -p no:cacheprovider tests/test_mapping.py tests/test_convert.py tests/test_complete_statement_source_payload.py tests/test_statement_account_mapping.py tests/test_cash_import_wizard.py tests/test_transaction_relations_payment_mirror.py tests/test_transaction_relations_refund.py tests/test_transaction_relations_cross_batch.py tests/test_import_relation_planning.py tests/test_transfer_phase_c.py tests/test_transaction_relations_transfer.py tests/test_source_agnostic_transfer_matching.py` → `415 passed, 13 skipped`。
- 真实账单双顺序回放：读取仓库外 `~/.ft/bills` 中 3 份微信、3 份支付宝、2 份工行信用卡和 1 份工行借记卡文件，在两个全新隔离 SQLite 数据库中分别执行「平台后工行」和「工行后平台」。两种顺序均为 `10438` 条事实、`441` 条活动关系、`9999` 个投影成员，投影状态均为 `ready/succeeded`；事实集合、活动关系集合和投影集合均相等。运行耗时分别约 `103.895 s` 和 `110.251 s`。密码只在进程内传递，未写入仓库、日志或测试输出。
- 解析抽样：两份信用卡文件分别解析为 `2500`、`347` 条记录，借记卡文件解析为 `1206` 条记录；信用卡来源身份为完整或 PDF 稳定掩码标识，三份工行文件均无来源身份问题。
- 完整回归：`PYTHONDONTWRITEBYTECODE=1 .venv/bin/python -m pytest -p no:cacheprovider` → `1563 passed, 192 skipped, 2 failed`；失败为既有 `tests/test_application_queries.py::test_list_accounts_values_cash_and_investment_with_cost_fallback`（`Broker=25` 对比期望 `29`）和既有 `tests/test_wealth_performance.py::test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets[sqlite]`（本次冷重建 P95 为 `6.825 s`，门槛 `5 s`）。排除这两个既有失败文件后补跑 → `1559 passed, 191 skipped`；本轮相关测试无失败。
- PostgreSQL 契约矩阵：使用已存在的专用 `finance_tracker_test`（连接凭据未写入记录）执行 `FT_TEST_POSTGRES_URL=<redacted> FT_REQUIRE_TEST_POSTGRES=1 .venv/bin/python -m pytest -p no:cacheprovider tests/contract/test_cash_import_dual_backend.py tests/contract/test_dual_backend_icbc_refund_pairing.py tests/contract/test_dual_backend_counterparty_account_transfer_matching.py tests/contract/test_dual_backend_record_type.py tests/test_postgres_statement_import.py tests/test_016_migration_parity.py` → `73 passed`。
- 当前最终门禁：`openspec --version` → `1.7.0`，`node --version` → `v24.4.1`；`openspec validate order-independent-icbc-import --type change --strict`、`openspec validate --all --strict`、`openspec doctor`、`python -m compileall -q src tests`、`uv build`、`git diff --check` 均通过；默认映射模板回归已纳入本轮受影响矩阵。
- 当前真实解析复核：从仓库外 `~/.ft/bills` 识别到 2 份工行信用卡 PDF 和 1 份工行借记卡 PDF；信用卡共 `2847` 条、6 个来源账户组，借记卡 `1206` 条、1 个来源账户组，来源身份问题 `0`。未输出密码、完整账号或文件名。
- 独立最新 diff 复核：覆盖信用卡/借记卡来源身份兼容、正反顺序收敛、退款/支付镜像误配、人工决定保护、ID 类型、缓存计划、projection 一致性、性能、敏感数据和 OpenSpec 一致性；当前未发现 critical/major 阻断。残余风险为全量套件中上述两个既有基线失败，以及真实顺序回放的约 `104–110 s` 全流程耗时，未改变本轮功能正确性结论。
