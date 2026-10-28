## 1. 思考

- [x] 1.1 通过项目 `grill-me → grilling` 确认修复 PR #101 全部失败／取消检查、保留业务和性能标准、不改 UI；完成规格、词表、失败日志与代码调查，按持久化和性能风险分为 A 类。

## 2. 计划

- [x] 2.1 在隔离 worktree 形成 proposal、delta、design，明确来源隔离、当前修订、完整图和原性能预算；实现选择原位回写。

## 3. 任务拆分

- [x] 3.1 对齐分类集合写入、当前模型夹具、导入／关系性能、CI 超时、索引迁移及文档收尾；先安排失败反例。

## 4. 构建

- [x] 4.1 复现分类成本和成员边界失败，补迁移回归，再实现有界集合读取、集合删除和索引。
- [x] 4.2 校准备注、合法关系及公共编辑性能夹具，保留来源／修订断言和固定工作量。
- [x] 4.3 优化重复规范化、复制、序列化和候选计算；保留全部安全校验、候选超集及来源创建方式约束，补父／子编号、复制协议和 JSON 预算反例。
- [x] 4.4 先验证分片互斥完整、空分片／失败传播，再实施常规 CI 分片与原检查名汇总。

## 5. 审查

- [x] 5.1 完成独立产品／工程／安全和最终 diff 复核；阻断 finding 已修复并重审，当前无未处理阻断项。

审查覆盖完整成员归属、来源与公共属性隔离、JSON／复制边界、候选类型超集、父／子编号命名空间、迁移回滚、专库隔离及 CI 汇总失败传播。P1：分类逐项查询、删除触发全量重建、遗漏成员和创建方式误判已修复。P2：索引缺失、旧夹具、映射测试状态依赖、嵌套 UOW 自锁、CI 超时，以及字符串子类键／自定义复制 memo 的兼容缺口已以失败回归修复。精确普通容器复制快路径遇到未知类型时整体回退标准深复制，不混用复制协议；独立复核无新增 finding。

## 6. 测试与 QA

- [x] 6.1 SQLite 开发回归后，人工准备 PostgreSQL 16 专用 `_test` 库，完成同服务完整常规回归及全部原性能矩阵。
- [x] 6.2 完成 Python 编译、安装包构建、工作流 YAML、OpenSpec strict／doctor、文档与 diff 检查。

Cross-platform Impact Check：Web、Native 及共享 presentation 没有页面、文案、交互或契约变化。浏览器、Hallmark、Native 编译和设备 QA 不适用；不据此宣称 UI 验收。项目未配置独立 Python 类型检查命令，执行源码编译、完整回归和安装包构建。

## 7. 发布准备

- [x] 7.1 核对文档、配置／迁移、功能地图与词表，记录交付证据、回滚和观察项；用户已明确授权将本次修复提交并推送至 `ssttkkl/finance-tracker` 的 `average-snail` 分支。

README 增补现行分片和复现命令，项目 apply skill 同步后端验证边界；分类性能 delta 已同步主规格。现有 docs、references、API／命令、配置保持原业务合同。没有新增 UI、用户能力或术语，功能地图页面数、覆盖、入口和 TODO 不变，词表无需新增。既有万行预览待办按当前证据收尾，其余 active changes 保留。

部署仅新增 `20261028_51` 分类索引，schema 从 `20261023_50` 升级；不修改财务数据和既有约束。回滚撤回代码并降级至 `20261023_50`，仅删除该索引；分类写入失败仍整次事务回滚。未部署、未操作真实财务数据。本地性能结果不能替代 GitHub runner 验证；获准推送后观察原性能门禁及全部分片汇总结果。

## 8. 反思

- [x] 8.1 当前模型夹具必须包含稳定收支对象及真实成员；公共值和来源值分别断言。性能优化保留固定工作量和原预算，用剖析定位开销，以复制隔离、候选超集和财务边界反例约束快路径。测试辅助查询复用已持锁事务，测试模块自行初始化专库；CI 收集完整测试后按文件分片，任何分片失败／取消／跳过均不得使原检查名变绿。

## 当前证据

执行时间：2026-10-28T01:51:24+08:00。当前 HEAD／比较基线均为 `06dcadd5559f45ea15724ea331e21d05f2500cef` 加本次未提交修复，目标 `ssttkkl/finance-tracker` PR #101 的 `average-snail`。最初隔离基线 `4ad7223` 另含无关 UI 工作流约定，交付分支排除该已有文档提交；最终完整回归、构建和性能验证基于当前基线。实施位置 `/tmp/ft-pr101-fix`，原工作树未提交内容保持原位。本记录保留提交前验证树与命令证据；用户已授权提交并推送，GitHub checks 以交付提交的新一轮结果为准。

| 范围 | 当前命令、结果及证据 |
| --- | --- |
| 测试先行 | 分类预算／完整成员、父子编号碰撞、分片失败关闭、字符串子类键和自定义 memo 反例先失败后修复；`/tmp/ft-pr101-category-red.log`、`/tmp/ft-pr101-member-red.log`、`/tmp/ft-pr101-origin-red.log`、`/tmp/ft-pr101-shard-red.log`、`/tmp/ft-pr101-component-key-red.log`、`/tmp/ft-pr101-copy-protocol-red.log` |
| 完整常规回归 | 8 个分片各使用人工准备的 `ft_pr101_shard<N>_test`，设置 `FT_TEST_POSTGRES_URL=postgresql+psycopg://ft_pr101_test@127.0.0.1:55481/<专库>`、`FT_REQUIRE_TEST_POSTGRES=1`；执行 `PYTHONPATH=tests:.:src .venv/bin/python scripts/run_backend_tests.py --shard-index <0..7> --shard-count 8` → **4860 passed、2 skipped**，覆盖 4862 个非性能节点，skip 不计为通过。同一收集指纹 `41c019c48845658dfc9f3239878d4d7d95d1b6061ea3c2c1f33d780c7f480835`；清单 `/tmp/ft-pr101-final-regression-results.json`，日志 `/tmp/ft-pr101-final-postgresql-{0..7}.log` |
| 完整性能矩阵 | 人工准备 `ft_pr101_test` 专库，显式设置上述 PostgreSQL URL 与必需开关；`PYTHONPATH=tests:.:src .venv/bin/python -m pytest -q -s -m performance --tb=short` → **69 passed、无 skip**，625.92 s。SQLite／PostgreSQL 万行预览 4.637／4.647 s，原 5 s／256 MiB 预算通过；万行重建 P95 4.120／4.602 s，原 10 s 预算通过。全部查询次数、P95、耗时和内存门槛保持原值；`/tmp/ft-pr101-performance-complete.log`。本轮没有并行运行其他测试 |
| 索引迁移 | SQLite `tests/test_cash_category_migration.py` 纳入完整回归；真实 PostgreSQL 独立 `ft_pr101_index_test` 显式 upgrade→downgrade→upgrade，索引正确、公共值与修订不变；`/tmp/ft-pr101-pg-index-migration.log` |
| 构建／工作流 | `.venv/bin/python -m compileall -q src migrations scripts/run_backend_tests.py`、`uv build --offline --out-dir /tmp/ft-pr101-dist` 通过，wheel 内 `ft/copying.py` 与当前源码一致。YAML 解析、两后端各 8 分片、`always()` 汇总和严格 `success` 断言通过；`/tmp/ft-pr101-build.log` |
| 规格／文档／diff | `openspec validate --all --strict`、`openspec doctor`、链接／范围／术语核对和 `git diff --check` 通过；归档后再次执行规格与 diff 检查。`/tmp/ft-pr101-openspec.log`、`/tmp/ft-pr101-doctor.log` |

真实 PostgreSQL 不由测试自动启动或探测；补跑前须人工准备名称以 `_test` 结尾的专库并显式配置 URL。日志、性能剖析、构建产物和测试数据库均留在仓库外。GitHub 新检查需在获得推送授权后触发，不将本地通过写成远端通过。
