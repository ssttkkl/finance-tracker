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

审查覆盖产品范围、财务和权限、复制／JSON 协议、可靠来源身份、完整候选和图、ORM 会话与查询批次、分类保护及当前 diff。独立只读上下文完成最终复核，无未处理阻断。P2：可靠编号快路径遗漏文件级证据边界；改为仅精确非分页证据通过，未知或分页证据回完整算法，以 `SourceDocumentEvidence` 携带无效 coverage 的失败反例验证。原通用退款匹配不限制来源；全负消费裁剪依赖显式退款种子条件，以相同账户、明确其他来源的正退款和强文本信号做完整候选的最终计划差分。金额、时间、币种自定义转换和容器／字符串子类保留原路径。小批对象读取覆盖会话身份、待写／过期成员、缺对象／跨工作区及 400／401 批次；单笔投影按正负零、资金关联、待确认状态与通用算法逐字段比较。最终复核未发现 UI、预算、财务规则或权限扩大。

## 6. 测试与 QA

- [x] 6.1 SQLite 开发回归后，人工准备 PostgreSQL 16 专用 `_test` 库，完成同服务完整常规回归及全部原性能矩阵。
- [x] 6.2 完成 Python 编译、安装包构建、工作流 YAML、OpenSpec strict／doctor、文档与 diff 检查。

Cross-platform Impact Check：Web、Native 及共享 presentation 没有页面、文案、交互或契约变化。浏览器、Hallmark、Native 编译和设备 QA 不适用；不据此宣称 UI 验收。项目未配置独立 Python 类型检查命令，执行源码编译、完整回归和安装包构建。

## 7. 发布准备

- [ ] 7.1 核对文档、配置／迁移、功能地图与词表，记录交付证据、回滚和观察项；用户已明确授权将本次修复提交并推送至 `ssttkkl/finance-tracker` 的 `average-snail` 分支。

README 增补现行分片和复现命令，项目 apply skill 同步后端验证边界；分类性能 delta 已同步主规格。现有 docs、references、API／命令、配置保持原业务合同。没有新增 UI、用户能力或术语，功能地图页面数、覆盖、入口和 TODO 不变，词表无需新增。既有万行预览待办按当前证据收尾，其余 active changes 保留。

部署仅新增 `20261028_51` 分类索引，schema 从 `20261023_50` 升级；不修改财务数据和既有约束。回滚撤回代码并降级至 `20261023_50`，仅删除该索引；分类写入失败仍整次事务回滚。未部署、未操作真实财务数据。本地性能结果不能替代 GitHub runner 验证；获准推送后观察原性能门禁及全部分片汇总结果。

## 8. 反思

- [x] 8.1 当前模型夹具必须包含稳定收支对象及真实成员；公共值和来源值分别断言。性能优化保留固定工作量和原预算，用剖析定位开销，以复制隔离、候选超集和财务边界反例约束快路径。测试辅助查询复用已持锁事务，测试模块自行初始化专库；CI 收集完整测试后按文件分片，任何分片失败／取消／跳过均不得使原检查名变绿。

## 当前证据

执行日期：2026-10-28。验证基线与当前 HEAD 为 `013f0683058b6b9d455b8b796257280558d94414` 加本轮修复。源码／测试内容清单为仓库外 `validated-source.json`，清单摘要 `8a34fa692c63531b22240f3edf334b76f164c9505c40c579324bace3b011c7f1`。机器重启清理了原 `/tmp` 工作树、未提交修改及本机日志，旧结果不作为当前源码证据。恢复位置为 `/Users/huangwenlong/.paseo/worktrees/1zalea72/pr101-check-fixes`，证据根目录 `/Users/huangwenlong/.cache/ft-pr101`；原工作树用户改动保持原位。用户授权本仓库 `average-snail` 分支的提交和推送，不含合并或部署。

Python 使用 `/Users/huangwenlong/.paseo/worktrees/1zalea72/average-snail/.venv/bin/python`，`PYTHONPATH=tests:.:src`、`PYTHONDONTWRITEBYTECODE=1`，pytest 缓存和构建产物位于上述仓库外目录。人工准备 PostgreSQL 16.14，端口 55481、用户 `ft_pr101_test`，显式设置 `FT_REQUIRE_TEST_POSTGRES=1` 和 `FT_TEST_POSTGRES_URL=postgresql+psycopg://ft_pr101_test@127.0.0.1:55481/<专用_test库>`；测试不启动或探测数据库。

| 范围 | 实际命令与当前结果 |
| --- | --- |
| 失败回归 | `python -m pytest -q tests/test_cash_hotpath_equivalence.py` 的投影、空图、可靠编号与资金解析成本约束先失败；小批对象读取与候选裁剪、文件证据和已知非分页证据另有先失败反例。`red.log`、`lookup-red.log`、`candidate-red.log`、`file-evidence-red.log`、`known-evidence-red.log`。未知证据及 ORM 夹具修正后以完整回归为准，不把夹具缺陷计为业务失败 |
| 双后端相关边界 | 相同服务在 SQLite／真实 PostgreSQL 的对象、导入业务、关系上下文、导入操作及分类回归 243 passed；独立审查追加边界 17 passed；纯领域和来源快路径 91 passed。存在用例交叠，不相加作覆盖数。`dual-affected.log`、`review-boundaries.log`、`provider-final-green.log`；后续完整回归覆盖最终源码 |
| 完整常规回归 | `python scripts/run_backend_tests.py --shard-index <0..7> --shard-count 8`，4 个执行进程分别使用独立 `ft_pr101_shard<N>_test`；8 片全部退出 0，**4987 passed、2 skipped，另 5 subtests passed**，共 4989 个主节点。同一收集指纹 `ab251be9aab81cf1ff3f0f8ae3c20b9a746482ce185bd6a9be202ed5ee48babd`。两个 skip 分别需要真实 PDF、`FT_TEST_SQLITE_SOURCE` 真实数据库副本，不计通过；本任务使用合成数据，不读取真实财务数据。结果 `regression-results.json`、`regression-<N>.xml`、`regression-<N>.log` |
| 针对性性能 | 原导入、当前关系、HTTP 维护及分类性能 28 passed，130.68 s；SQLite／PostgreSQL 万行预览 3.028／3.083 s，PostgreSQL 关系选择提交 P95 54.6 ms。计时中本任务没有并行运行其他测试或构建；随后仅收紧和完善来源快路径证据判断，最终性能以完整矩阵为准。`target-performance.log` |
| 完整原性能矩阵 | `python -m pytest -q -s -m performance --tb=short`，专库 `ft_pr101_test`；65 passed、4 failed、无 skip，841.19 s。SQLite 重建 10.318 s／10 s、PostgreSQL 旧关系维护及草稿保存、SQLite 投资查询超限；主机可见其他构建及多组 Python 测试并行负载。本任务未改投资查询，不能据此直接归因为负载或把失败计通过。同源码／预算单独复测全部 4 passed，161.99 s；SQLite 重建 P95 6.076 s，PostgreSQL 草稿保存并读取 P95 61.4 ms。完整 69 个原性能场景均有通过证据，不宣称单次完整矩阵全绿；未调整任何预算、工作量或校验。`full-performance.log`、`performance-recheck.log` |
| 构建／编译 | `uv build --offline --out-dir <证据根>/dist`，wheel 中全部 11 个本轮受影响运行模块与工作树逐字节一致；`PYTHONPYCACHEPREFIX=<证据根>/pycache python -m compileall -q src migrations scripts/run_backend_tests.py` 通过。`build.log`。本轮没有 schema／迁移变更；完整回归仍覆盖原分类索引迁移合同 |
| 规格／文档／diff | `openspec validate --all --strict` 36 passed、`openspec doctor` 正常、`git diff --check` 通过。README、docs、references、接口／命令、功能地图与词表已核对：没有新增页面、客户端能力、用户术语或公共协议，无需改变当前业务说明；项目 apply skill 同步内部优化边界。`openspec.log`、`doctor.log` |

## 当前远端状态

`013f068` 的 20 个常规及 Compose 检查成功，性能任务 `122608030082` 为 63 passed／6 failed：PostgreSQL 分类删除 513.8 ms／500 ms、关系选择提交 P95 153.7 ms／100 ms、千行来源更新 15.85 s／15 s、SQLite／PostgreSQL 万行预览 8.20／8.32 s／5 s、PostgreSQL HTTP 维护 P95 103.3 ms／100 ms。远端通过前保留 active change，相关待办不标完成。
