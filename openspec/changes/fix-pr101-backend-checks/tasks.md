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

审查覆盖产品范围、财务和权限、复制／JSON 协议、可靠来源身份、完整候选和图、ORM 会话与查询批次、分类保护及当前 diff。独立只读上下文最终复核无未处理阻断。可靠来源快路径限定精确非分页证据；未知或分页证据回完整算法。负消费候选裁剪以完整候选最终计划差分约束，退款种子仍执行原通用匹配。小批对象读取保留待写／旧成员、缺对象、跨工作区、禁用 autoflush 和批界合同。JSON 预算与原算法比较错误、剩余预算和协议回调，未知深度类型直接回原算法。组成项／资金外连接覆盖同父多个组成项、有效／历史资金、无资金组成项及 501 父批界。投影写后保留完整对象版本与损坏成员检查。未改变 UI、预算、财务规则或权限。

## 6. 测试与 QA

- [ ] 6.1 SQLite 开发回归后，人工准备 PostgreSQL 16 专用 `_test` 库，完成同服务完整常规回归及全部原性能矩阵。
- [x] 6.2 完成 Python 编译、安装包构建、工作流 YAML、OpenSpec strict／doctor、文档与 diff 检查。

Cross-platform Impact Check：Web、Native 及共享 presentation 没有页面、文案、交互或契约变化。浏览器、Hallmark、Native 编译和设备 QA 不适用；不据此宣称 UI 验收。项目未配置独立 Python 类型检查命令，执行源码编译、完整回归和安装包构建。

## 7. 发布准备

- [ ] 7.1 核对文档、配置／迁移、功能地图与词表，记录交付证据、回滚和观察项；用户已明确授权将本次修复提交并推送至 `ssttkkl/finance-tracker` 的 `average-snail` 分支。

README 增补现行分片和复现命令，项目 apply skill 同步后端验证边界；分类性能 delta 已同步主规格。现有 docs、references、API／命令、配置保持原业务合同。没有新增 UI、用户能力或术语，功能地图页面数、覆盖、入口和 TODO 不变，词表无需新增。既有万行预览待办按当前证据收尾，其余 active changes 保留。

部署仅新增 `20261028_51` 分类索引，schema 从 `20261023_50` 升级；不修改财务数据和既有约束。回滚撤回代码并降级至 `20261023_50`，仅删除该索引；分类写入失败仍整次事务回滚。未部署、未操作真实财务数据。本地性能结果不能替代 GitHub runner 验证；获准推送后观察原性能门禁及全部分片汇总结果。

## 8. 反思

- [x] 8.1 当前模型夹具必须包含稳定收支对象及真实成员；公共值和来源值分别断言。性能优化保留固定工作量和原预算，用剖析定位开销，以复制隔离、候选超集和财务边界反例约束快路径。测试辅助查询复用已持锁事务，测试模块自行初始化专库；CI 收集完整测试后按文件分片，任何分片失败／取消／跳过均不得使原检查名变绿。

## 当前证据

执行日期：2026-10-28。当前验证基线及 HEAD 为 `c8032531124024108b7687179aaa5c5c5b904b98` 加本轮修复，包含合并后的详情关联实现。隔离工作树 `/Users/huangwenlong/.paseo/worktrees/1zalea72/pr101-check-fixes`，证据根目录 `/Users/huangwenlong/.cache/ft-pr101`；原工作树的用户改动保持原位。用户授权同仓库 `average-snail` 分支的提交和推送，不含合并或部署。

Python 使用 `/Users/huangwenlong/.paseo/worktrees/1zalea72/average-snail/.venv/bin/python`，设置 `PYTHONPATH=tests:.:src`、`PYTHONDONTWRITEBYTECODE=1`，缓存与构建产物在仓库外。人工准备 PostgreSQL 16.14，端口 55481、用户 `ft_pr101_test`，显式设置 `FT_REQUIRE_TEST_POSTGRES=1` 和 `FT_TEST_POSTGRES_URL=postgresql+psycopg://ft_pr101_test@127.0.0.1:55481/<专用_test库>`；测试不启动或探测数据库。

| 范围 | 实际命令与当前结果 |
| --- | --- |
| 失败回归 | JSON 预算普通容器的重复递归成本先失败，原算法差分保持 603 个语义用例；自定义深度回调先以 2 次／原 1 次失败，再补精确深度类型保护。组成项／资金及重复父读取成本先失败。`budget-red.log`、`budget-depth-red.log`、`relation-query-red.log`；夹具错误不计业务失败 |
| JSON 预算 | `python -m pytest -q tests/test_json_budget_equivalence.py tests/test_parsed_import_staging.py`，770 passed；`budget-green.log` |
| 双后端关系边界 | `python -m pytest -q tests/contract/test_cash_relation_context.py tests/contract/test_cash_current_relation_plan.py tests/contract/test_cash_projection_graph_reads.py tests/contract/test_cash_projection_parity.py tests/contract/test_cash_current_relation_commit.py`，SQLite／真实 PostgreSQL 219 passed、150.75 s；`relation-final-green.log` |
| 完整常规回归 | `python scripts/run_backend_tests.py --shard-index <0..7> --shard-count 8`，4 个进程各用独立 `ft_pr101_shard<N>_test`；正在运行，不能计通过。`current-regression-summary.log`、`current-regression-<N>.log`／XML |
| 原性能矩阵 | 当前最终源码尚待隔离运行完整 69 个原性能场景，不能沿用旧 HEAD 结果计通过；`python -m pytest -q -s -m performance --tb=short` |
| 构建／编译 | `uv build --offline --out-dir <证据根>/current-dist` 和 `PYTHONPYCACHEPREFIX=<证据根>/pycache python -m compileall -q src migrations scripts/run_backend_tests.py` 通过；`current-build.log`。本轮不新增 schema／迁移 |
| 规格／文档／diff | `openspec validate --all --strict` 37 passed、`openspec doctor` 正常、`git diff --check` 通过；`current-openspec.log`、`current-doctor.log`。README、docs、references、接口／命令、配置／迁移、功能地图与词表已核对；没有新增页面、客户端能力、用户术语或公共协议，现行说明不需改变。项目 apply skill 同步优化边界 |

## 当前远端状态

当前 PR HEAD `c8032531124024108b7687179aaa5c5c5b904b98` 的 20 项常规及 Compose 检查成功，性能任务 `122663957369` 为 66 passed／3 failed：PostgreSQL 关系选择提交 P95 157.2 ms／100 ms、SQLite／PostgreSQL 万行预览 6.348／6.471 s／5 s。原检查被后续推送取消不计代码失败。新修复尚未推送，远端通过前保留 active change，相关待办不标完成。本地计时和旧 HEAD 完整回归不替代当前 GitHub checks。
