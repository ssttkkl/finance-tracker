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

- [ ] 7.1 核对文档、配置／迁移、功能地图与词表，记录交付证据、回滚和观察项；用户已明确授权将本次修复提交并推送至 `ssttkkl/finance-tracker` 的 `average-snail` 分支。

README 增补现行分片和复现命令，项目 apply skill 同步后端验证边界；分类性能 delta 已同步主规格。现有 docs、references、API／命令、配置保持原业务合同。没有新增 UI、用户能力或术语，功能地图页面数、覆盖、入口和 TODO 不变，词表无需新增。既有万行预览待办按当前证据收尾，其余 active changes 保留。

部署仅新增 `20261028_51` 分类索引，schema 从 `20261023_50` 升级；不修改财务数据和既有约束。回滚撤回代码并降级至 `20261023_50`，仅删除该索引；分类写入失败仍整次事务回滚。未部署、未操作真实财务数据。本地性能结果不能替代 GitHub runner 验证；获准推送后观察原性能门禁及全部分片汇总结果。

## 8. 反思

- [x] 8.1 当前模型夹具必须包含稳定收支对象及真实成员；公共值和来源值分别断言。性能优化保留固定工作量和原预算，用剖析定位开销，以复制隔离、候选超集和财务边界反例约束快路径。测试辅助查询复用已持锁事务，测试模块自行初始化专库；CI 收集完整测试后按文件分片，任何分片失败／取消／跳过均不得使原检查名变绿。

## 当前证据

执行日期：2026-10-28。当前验证 HEAD／比较基线为 `5f41438646a2a4b31eec3b8d2c3f5d7eed67faa6` 加当前修复，目标仍为 `ssttkkl/finance-tracker` PR #101 的 `average-snail`。隔离实施位置 `/tmp/ft-pr101-fix`；原工作树的未提交 UI、文档及导入工作保持原位。用户已明确授权本仓库该分支的提交及推送，不含合并或部署。

所有 Python 验证使用 `/Users/huangwenlong/.paseo/worktrees/1zalea72/average-snail/.venv/bin/python`，设置 `PYTHONPATH=tests:.:src`、`PYTHONDONTWRITEBYTECODE=1`、`PYTEST_ADDOPTS='-o cache_dir=/tmp/ft-pr101-pytest-cache'`。真实 PostgreSQL 使用人工准备的 16.14 专用 `_test` 库，显式设置 `FT_TEST_POSTGRES_URL=postgresql+psycopg://ft_pr101_test@127.0.0.1:55481/<专库>` 和 `FT_REQUIRE_TEST_POSTGRES=1`；测试不启动或探测数据库。运行缓存、日志、构建产物及合成数据库均在仓库外。

| 范围 | 当前命令、结果及证据 |
| --- | --- |
| 测试先行与根因 | 分类、成员、父子编号、分片及复制协议反例先失败；Linux run `40614965247` 的原性能任务 63 passed／6 failed。追加同事务重复读取及发布重复读源反例先失败，再实现有界复用；`/tmp/ft-pr101-fresh-red.log`、`/tmp/ft-pr101-publish-count-red.log`。发现阶段优化使无匹配工作的旧异常注入不可达，补真实正向银行候选并保持异常传播及全表回滚断言；8 例双后端通过，`/tmp/ft-pr101-discovery-injection-final-green.log` |
| 完整常规回归 | `python scripts/run_backend_tests.py --shard-index <0..7> --shard-count 8`，各片使用独立 `ft_pr101_shard<N>_test`，源码冻结后顺序接续性能矩阵启动；**4920 passed、2 skipped**，覆盖全部 4922 个普通节点，skip 不计通过；同一收集指纹 `20aa02cfc2a0853833b35a537e10a30c393c6119d395f72a46c58e34b9588985`。夹具修复后仅重跑受影响完整分片 2、6，其他 6 片源码、依赖和用例未变。清单 `/tmp/ft-pr101-linux-regression-results.json`；日志 `/tmp/ft-pr101-linux-postgresql-{0,1,3,4,5,7}.log` 与 `/tmp/ft-pr101-linux-postgresql-rerun-{2,6}.log` |
| 完整原性能矩阵 | `python -m pytest -q -s -m performance --tb=short`，专库 `ft_pr101_test` → **69 passed、无 skip**，523.69 s。SQLite／PostgreSQL 万行完整预览 3.627／3.820 s，原 5 s／256 MiB；万行重建 P95 3.070／3.560 s，原 10 s；当前关系选择加提交 P95 18.3／56.7 ms，原 100 ms。全部原预算、工作量、runner 和校验保留；计时未并行运行其他测试或构建。运行时源码此后未变，后续只修复故障注入夹具。`/tmp/ft-pr101-linux-full-performance.log` |
| 受影响边界 | 双后端相关回归 349 passed；暂存及发布 217 passed；追加来源证明／数值／匹配及暂存 217 passed；取消旧快照 2 passed。各命令有交叠，不相加作覆盖数。`/tmp/ft-pr101-linux-affected.log`、`/tmp/ft-pr101-snapshot-final-green.log`、`/tmp/ft-pr101-linux-proof-final.log`。跨仓库、跨事务、数量不一致、DTO 嵌套变异、旧快照 CAS／取消／归属／TTL 均有反例；独立最终复核无未处理阻断 |
| 构建／迁移 | `PYTHONPYCACHEPREFIX=/tmp/ft-pr101-linux-pycache python -m compileall -q src migrations scripts/run_backend_tests.py`、`uv build --offline --out-dir /tmp/ft-pr101-linux-dist` 通过，wheel 的 16 个受影响源码模块逐字节一致。`/tmp/ft-pr101-linux-build.log`。本轮不新增 schema／迁移；首个修复的分类索引已执行双后端迁移与真实 PostgreSQL upgrade→downgrade→upgrade，当前完整回归继续覆盖迁移合同 |
| 规格／文档／diff | `openspec validate --all --strict` 36 passed、`openspec doctor` 正常，`git diff --check` 通过；提交前再次核对最新文档与链接。`/tmp/ft-pr101-linux-openspec.log`、`/tmp/ft-pr101-linux-doctor.log` |

## 当前远端状态

当前远端结果仍对应 `5f41438`：16 个普通后端分片、两个汇总及两个 Compose 检查通过；性能任务 `122575186639` 有 6 项超时，日志 `/tmp/ft-pr101-ci-performance-failed.log`。本轮未改业务规则、性能标准、UI、依赖或 runner；提交后须检查新 HEAD 的全部 GitHub checks，不能以本地通过替代远端通过。远端门禁通过前保留 active change，万行预览待办不标完成。

README、docs、references、接口／命令示例、功能地图和词表已按最终实现核对：没有新增页面、客户端能力、用户术语或公共协议，现有业务说明保持；项目 apply skill 原位说明内部暂存及重建复用边界。回滚只需撤回本轮纯实现优化；首个修复的索引降级仍只删除索引，不改变财务数据。本次没有合并、部署或访问真实财务数据。
