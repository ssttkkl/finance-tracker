## 1. 思考

- [x] 1.1 通过项目 `grill-me → grilling` 确认修复 PR #101 全部失败／取消检查、保留业务和性能标准、不改 UI；完成规格、词表、失败日志与代码调查，按持久化和性能风险分为 A 类。

## 2. 计划

- [x] 2.1 在隔离 worktree 形成 proposal、delta、design，明确来源隔离、当前修订、完整图和原性能预算；实现选择原位回写。

## 3. 任务拆分

- [x] 3.1 对齐分类集合写入、当前模型夹具、导入／关系性能、CI 超时、索引迁移及文档收尾；先安排失败反例。

## 4. 构建

- [x] 4.1 复现分类成本和成员边界失败，补迁移回归，再实现有界集合读取、集合删除和索引。
- [x] 4.2 校准备注、合法关系及公共编辑性能夹具，保留来源／修订断言和固定工作量。
- [ ] 4.3 优化重复规范化、复制、序列化和候选计算；保留全部安全校验、候选超集及来源创建方式约束，补父／子编号、复制协议和 JSON 预算反例。
- [x] 4.4 先验证分片互斥完整、空分片／失败传播，再实施常规 CI 分片与原检查名汇总。

## 5. 审查

- [x] 5.1 完成独立产品／工程／安全和最终 diff 复核；阻断 finding 已修复并重审，当前无未处理阻断项。

审查覆盖产品范围、财务和权限、复制／JSON 协议、可靠来源身份、完整候选和图、ORM 会话与查询批次、分类保护及当前 diff。独立只读上下文最终复核无未处理阻断。可靠来源快路径限定精确非分页证据；未知或分页证据回完整算法。负消费候选裁剪以完整候选最终计划差分约束，退款种子仍执行原通用匹配。小批对象读取保留待写／旧成员、缺对象、跨工作区、禁用 autoflush 和批界合同。JSON 预算与原算法比较错误、剩余预算和协议回调，未知深度类型直接回原算法。组成项／资金外连接覆盖同父多个组成项、有效／历史资金、无资金组成项及 501 父批界。投影写后保留完整对象版本与损坏成员检查。未改变 UI、预算、财务规则或权限。

## 6. 测试与 QA

- [ ] 6.1 SQLite 开发回归后，人工准备 PostgreSQL 16 专用 `_test` 库，完成同服务完整常规回归及全部原性能矩阵。
- [ ] 6.2 完成 Python 编译、安装包构建、工作流 YAML、OpenSpec strict／doctor、文档与 diff 检查。

Cross-platform Impact Check：Web、Native 及共享 presentation 没有页面、文案、交互或契约变化。浏览器、Hallmark、Native 编译和设备 QA 不适用；不据此宣称 UI 验收。项目未配置独立 Python 类型检查命令，执行源码编译、完整回归和安装包构建。

## 7. 发布准备

- [ ] 7.1 核对文档、配置／迁移、功能地图与词表，记录交付证据、回滚和观察项；用户已明确授权将本次修复提交并推送至 `ssttkkl/finance-tracker` 的 `average-snail` 分支。

README 增补现行分片和复现命令，项目 apply skill 同步后端验证边界；分类性能 delta 已同步主规格。现有 docs、references、API／命令、配置保持原业务合同。没有新增 UI、用户能力或术语，功能地图页面数、覆盖、入口和 TODO 不变，词表无需新增。既有万行预览待办按当前证据收尾，其余 active changes 保留。

部署仅新增 `20261028_51` 分类索引，schema 从 `20261023_50` 升级；不修改财务数据和既有约束。回滚撤回代码并降级至 `20261023_50`，仅删除该索引；分类写入失败仍整次事务回滚。未部署、未操作真实财务数据。本地性能结果不能替代 GitHub runner 验证；获准推送后观察原性能门禁及全部分片汇总结果。

## 8. 反思

- [x] 8.1 当前模型夹具必须包含稳定收支对象及真实成员；公共值和来源值分别断言。性能优化保留固定工作量和原预算，用剖析定位开销，以复制隔离、候选超集和财务边界反例约束快路径。测试辅助查询复用已持锁事务，测试模块自行初始化专库；CI 收集完整测试后按文件分片，任何分片失败／取消／跳过均不得使原检查名变绿。

## 当前证据

执行日期：2026-10-28。当前工作树基于 `221a1270df2543629187e30d30eb5e6704ef67b9`，运行源码比较基线为 `bfacce02ffb411bba3270ce1ed53bbc48bd7dfd9`。隔离工作树 `/Users/huangwenlong/.paseo/worktrees/1zalea72/pr101-check-fixes`，证据根目录 `/Users/huangwenlong/.cache/ft-pr101`；原工作树的用户改动保持原位。用户授权同仓库 `ssttkkl/finance-tracker` 的 `average-snail` 分支提交和推送，不含合并或部署。

Python 使用 `/Users/huangwenlong/.paseo/worktrees/1zalea72/average-snail/.venv/bin/python`，设置 `PYTHONPATH=tests:.:src`、`PYTHONDONTWRITEBYTECODE=1`，缓存与构建产物在仓库外。人工准备 PostgreSQL 16.14，端口 55481、用户 `ft_pr101_test`，显式设置 `FT_REQUIRE_TEST_POSTGRES=1` 和 `FT_TEST_POSTGRES_URL=postgresql+psycopg://ft_pr101_test@127.0.0.1:55481/<专用_test库>`；测试不启动或探测数据库。

| 范围 | 当前证据 |
| --- | --- |
| 失败回归 | `runner-hotpath-red.log`：空容器重复递归、同文件摘要重复计算先失败。`posting-cache-red.log`：成功财务校验未复用先失败。`plan-lifetime-red.log`、`final-graph-red.log`：双后端准备图和最终图保活先失败 |
| 普通协议／只读差分 | `runner-pure-final.log`、`runner-boundaries-green.log`：预算、复制、只读详情、财务校验、字段子类和 Mapping.get 异常；当前新增生命周期改动后补跑完整回归 |
| 生命周期／公开形状 | `plan-lifetime-green.log`：第一段释放及导入业务／公共编辑合同 114 passed。`final-graph-green.log`：双后端弱引用释放与最终数组形状 2 passed |
| 原四项性能 | `runner-fix-target5.log`：SQLite／PostgreSQL 关系与万行预览 4 passed；关系选择提交 P95 18.8／51.6 ms，万行预览 2.497／2.429 s。原预算、工作量和全部输出断言不变，本任务未并行其他测试／构建 |
| 独立复核 | 产品范围、工程、财务／权限、协议、查询与生命周期最终 diff 复核无未处理阻断。Mapping.get、普通字典自定义 key 赋值、索引键子类协议 finding 已修复并补差分；全局 GC 暂停方案未实施。review 上下文只读，未声称运行测试 |
| 完整常规回归／性能 | 当前源码正在运行，不引用旧源码通过结果作为当前验证 |
| 编译／构建／规格／文档 | 当前源码待运行；临时 runner profiling job 和脚本已删除，工作流恢复后与 `bfacce0` 完全相同 |

## 当前远端状态

基线 `221a127` 的原检查仅 Backend (Performance) 失败，其余原 20 项成功；原性能预算、runner 和任务工作量不变。实际 runner 剖析已完成，临时诊断成功不计验收。当前源码须在获准推送后另行检查全部原门禁；本地通过不冒充 GitHub runner 通过。
