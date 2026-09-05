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
- [ ] 6.2 创建聚焦提交并推送到用于 PR 的分支，创建目标为 `refactor/web` 的 PR
- [ ] 6.3 等待并处理 PR 检查；获得合并授权后合并到 `refactor/web`，记录合并提交和观察项

## 验证记录

- 基线：本次实现提交为 `62c56e0`，随后已合入最新 `origin/refactor/web`（合并提交 `1104467`）；当前工作分支为 `fix-icbc-013958-pdf-recognition-failure`，PR 基线为 `origin/refactor/web` `6ddea6b`。
- 失败回归与受影响矩阵（本次最终功能修复后）：`.venv/bin/python -m pytest -p no:cacheprovider tests/test_convert.py tests/test_complete_statement_source_payload.py tests/test_statement_account_mapping.py tests/test_cash_import_wizard.py tests/test_transaction_relations_payment_mirror.py tests/test_transaction_relations_refund.py tests/test_transaction_relations_cross_batch.py tests/test_import_relation_planning.py` → `380 passed, 13 skipped`。
- 全量回归：`PYTHONDONTWRITEBYTECODE=1 .venv/bin/python -m pytest -p no:cacheprovider` 在更新旧工行哈希断言前为 `1552 passed, 192 skipped, 2 failed`（工行旧断言和一个基线查询失败）；更新断言后，受影响矩阵为 `380 passed, 13 skipped`，并单独复现基线失败 `tests/test_application_queries.py::test_list_accounts_values_cash_and_investment_with_cost_fallback`（`Broker=25` 对比期望 `29`）。该失败不涉及本变更文件，因此不能宣称全量无失败。
- 真实账单双顺序重放：使用 `.ft/bills` 中 3 份微信、3 份支付宝和 1 份工行 PDF，在全新临时 SQLite 与临时导入会话内执行「微信/支付宝后工行」和「工行后微信/支付宝」。两种顺序均成功，导入行数分别为 `1170,1176,985,1367,664,1028,1206` 与逆序对应值；事实数均为 `7596`，活动关系均为 `948`（`919 accepted`、`29 pending_review`），投影成员均为 `7596`，投影条目均为 `6677`，业务事实集合与活动关系集合 `delta=0`。密码只在进程内传递，未写入仓库或日志。
- 受影响关系测试已覆盖人工拒绝保护、人工选择覆盖系统边、工行等强候选待审核、退款标题/对方账号证据冲突和缓存 ID 字符串化；真实重放验证两种导入顺序收敛到同一结果。
- 编译与格式、OpenSpec 和构建：最终 artifact 更新后补跑 `python -m compileall -q src tests`、`git diff --check`、`openspec validate order-independent-icbc-import --type change --strict`、`openspec doctor`、`uv build`，结果必须按实际输出回写。
- PostgreSQL：环境未配置 `FT_TEST_POSTGRES_URL`，因此 PostgreSQL 契约矩阵未运行；补跑条件为配置指向名称以 `_test` 结尾的专用数据库后，执行同一受影响矩阵。
- 浏览器 QA：本次相对 `origin/refactor/web` 没有 Web UI、路由、交互或用户可见文案文件变更，故不适用真实浏览器 QA 与 Hallmark `audit`；此前密码入口相关 UI 已在基线中，不在本次 diff 内。
- 回滚：无数据库迁移；回滚应用提交即可，不删除或覆盖既有来源快照、业务事实或关系历史。发布后观察工行账户映射组数量、活动镜像待审核数及投影构建失败日志。
- PostgreSQL：环境未配置 `FT_TEST_POSTGRES_URL`，因此 PostgreSQL 契约矩阵未运行；补跑条件为配置指向名称以 `_test` 结尾的专用数据库后，执行同一受影响矩阵。
- 浏览器 QA：本次相对 `origin/refactor/web` 没有 Web UI、路由、交互或用户可见文案文件变更，故不适用真实浏览器 QA 与 Hallmark `audit`；此前密码入口相关 UI 已在基线中，不在本次 diff 内。
- 最终门禁：`python -m compileall -q src tests`、`uv build`、`git diff --check`、`openspec validate order-independent-icbc-import --type change --strict`、`openspec doctor` → 全部通过。
- 真实账单双顺序重放（最终修复后）：使用 `.ft/bills` 中 3 份微信、3 份支付宝和 1 份工行 PDF，在全新临时 SQLite 与临时导入会话内分别执行「微信/支付宝后工行」和「工行后微信/支付宝」。两种顺序均成功，事实数均为 `7590`，活动关系均为 `948` 且均为已确认，投影成员均为 `7590`，投影条目均为 `6642`；业务事实集合 `fact_delta=0`、活动关系集合 `active_relation_delta=0`，两种顺序的历史 superseded 记录差异为 `12`，属于保留审计链的预期差异。密码只在进程内传递，未写入仓库或日志。
