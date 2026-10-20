## 1. 思考：范围与事实

- [x] 1.1 显式执行项目 `grill-me → grilling`，将历次已确认决定和本轮授权写入 proposal；无未决业务分支，不重复询问。
- [x] 1.2 使用 `openspec-explore` 阅读项目原则、词表、目标文档、主规格、active changes、ORM、应用服务及测试，完成只读事实勘查。
- [x] 1.3 确认 A 类；记录账户独立命令、关系替换旧合同、新旧写入口和迁移的影响边界。

## 2. 计划：正式契约与切换

- [x] 2.1 创建 proposal、八项能力 delta 与 design；引用主设计及跨层契约，不创建重复产品入口。
- [x] 2.2 记录跨端影响、信息架构、删除的冗余信息、Hallmark 设计方向和自查。
- [x] 2.3 独立复核产品范围、工程、API／数据一致性和安全；修复阻断发现并记录处置。
- [x] 2.4 `openspec validate cash-import-lifecycle-redesign --strict`、文档链接／JSON 与 `git diff --check` 通过后，先提交并推送计划到 `origin/average-snail`。

## 3. 任务拆分与一致性

- [x] 3.1 将八项能力及目标文档场景对应到测试与任务；明确旧 API、CLI／技能、Compose 调用切换表。
- [ ] 3.2 准备仓库外依赖、专用 `_test` PostgreSQL 和合成夹具；真实账单仅只读，运行结果去标识化且不进 Git。
- [ ] 3.3 流程／schema／协议变化先更新 artifact；影响验收的业务歧义按具体问题澄清。

## 4. 构建：先失败测试再实现

- [x] 4.1 为统一更新白名单、固定字段、人工清空保护、账号直接覆盖及类型兼容性写失败测试；实现共享领域策略。
- [x] 4.2 为父子草稿空值、正式完整性、精度、工作区隔离和组成项归属写失败持久化测试；实现 ORM 与保留正式数据的迁移。
- [ ] 4.3 为当前来源绑定、无编号多重集合、范围不足／名称交叉反例、删除重导及草稿别名写失败测试；实现来源对应。
- [x] 4.3.1 完成单文件来源规划、同文件出现次数、认证证据、合成身份及事务内当前绑定／问题存储，验证两个后端。
- [ ] 4.3.2 新解析元数据接入，多文件共同计划不相加重复次数；真实来源重叠和旧来源保守回填在 4.7–4.9 验收后关闭 4.3。
- [ ] 4.4 为草稿关联、角色矩阵、确认关系保护、类型解除、联合退款钻石与旧闭包写失败测试；实现当前关系与候选结构及组校验。
- [ ] 4.5 为草稿不入统计、唯一当前归属、待确认可见性和局部投影写失败测试；调整投影读写和余额边界。
- [ ] 4.6 为独立账户创建修改、CAS、修订链及幂等删除写失败测试；实现普通账户接口与收据。
- [ ] 4.7 为一次解析、不保留原文件、部分文件／行错误、TTL 与权限写失败测试；实现解析会话及临时存储。
- [ ] 4.8 为不可变计划、预计投影列表、分页／详情、计划替换和发布消费竞争写失败测试；实现计划与目标 DTO。
- [ ] 4.9 为逐行保存点、关系组隔离、缓存回滚、收据同事务、重复键／上下文消费和未知结果写失败测试；实现联合提交。
- [ ] 4.10 为失败项限时派生、子集范围安全、草稿补全／转正式、问题处理与维护重预览写失败测试；实现后续维护 API。
- [ ] 4.11 用 API 权限／结构／安全错误测试驱动 FastAPI 路由切换；CLI、Agent 与技能共用新服务，关闭绕过新合同的旧入口。
- [ ] 4.12 用共享 DTO／ViewModel 测试驱动 Compose 统一列表与一次确认；实现状态标签、按需详情、主页草稿维护及账户独立表单和退出补偿。
- [ ] 4.13 更新演示场景、语义测试 ID、功能地图及相关说明；Web／Android／iOS 覆盖状态按实际证据填写。

## 5. 审查

- [ ] 5.1 独立产品／范围与工程复核：金额、来源身份、关系组、闭包、保存点、并发、迁移及 API 可发现性。
- [ ] 5.2 安全与性能复核：隔离、日志脱敏、原文件清理、受控临时存储、输入上限、无全库重配与有界分页。
- [ ] 5.3 对实际 Compose 调用 `hallmark audit`，覆盖三类窗口及异常状态，修复并重新复核 critical／major。
- [ ] 5.4 最终 diff 复核 artifact 与实现一致性、旧合同残留、遗漏测试和新增依赖；记录 finding 及处置。

## 6. 测试与 QA

- [ ] 6.1 运行受影响与完整 Python 回归；相同 Application Service 在 SQLite 与真实 PostgreSQL 跑合同矩阵，不把 skip 计入通过。
- [ ] 6.2 验证迁移升级前后正式值、来源、关系、余额及删除证据保持；验证可逆条件及拒绝有损降级。
- [ ] 6.3 以 `~/.ft/bills` 在专用测试库验证多来源重叠、重复导入、来源更新及错误恢复；报告只含汇总。
- [ ] 6.4 Compose 共享测试、Web 生产构建及相关 Native 编译；补适用 Native 客户端运行证据。
- [ ] 6.5 真实浏览器覆盖主流程、空／错误／部分完成／未知状态、键盘及 390／768／1440 px；记录 URL、步骤、截图与控制台／网络错误。
- [ ] 6.6 三尺寸 Compose 演示交付用户评审；收到用户确认前不勾选重大 UI 完成。
- [ ] 6.7 `openspec validate --all --strict`、`openspec doctor`、`git diff --check` 及适用性能／安全检查，记录未运行项与补跑条件。

## 7. 发布准备

- [ ] 7.1 每个可验证小步检查 status、diff 和验证后提交推送当前分支；授权不扩展到 PR、合并或部署。
- [ ] 7.2 完成切换矩阵、备份／迁移／回滚条件及观察项；真实数据迁移及部署等待相应授权。
- [ ] 7.3 所有验收及用户演示确认后同步主规格、更新词表并归档；未完成不得提前归档。

## 8. 反思

- [ ] 8.1 记录开发中计划缺口、反复失败及可复用约束；补回 artifacts 和防回归测试。

## 执行证据

- 2026-10-21，起始 `HEAD=5b41517`，比较基线同上；分支 `average-snail`，目标 `origin/average-snail`，用户已授权先推计划再开发。
- 环境：OpenSpec 1.7.0、Node 26.8.1、JDK 21、Xcode 26.3；已通过 `uv sync --group dev` 安装本 checkout Python 依赖；QA 依赖与新实现测试尚未运行。
- PostgreSQL：使用本机 PostgreSQL 16.14，在仓库外独立临时目录启动专用实例并创建 `ft_cash_import_test`；连接信息只保存在 `/tmp/ft-cash-import-test-env.json`，由命令包装器显式注入 `FT_TEST_POSTGRES_URL` 和 `FT_REQUIRE_TEST_POSTGRES=1`。Docker 镜像枚举出现存储 I/O 错误，不依赖该环境完成矩阵。
- Active change 处置：新合同取代 `safe-cross-source-refund-replacement` 的自动替换决定；保留确定性候选及完整图校验的算法证据。其他 active changes 的未完成验证及演示仍保持原状，不随本变更自动勾选。
- 本记录按实际小步持续补充；上述未完成项均不代表已经实现或通过。

- 计划阶段现状基线（尚无实现代码修改）：`uv run pytest tests/test_cash_import_session_service.py tests/test_cash_ledger_management.py -q --disable-warnings --maxfail=2` → 38 passed、5 skipped；随后使用上述显式 PostgreSQL 配置运行 `tests/contract/test_cash_import_dual_backend.py` → 13 passed、无 skip。新行为将另写失败测试，不把当前基线视为新设计验收。
- 计划静态检查：`openspec validate cash-import-lifecycle-redesign --strict` 通过；15 份本次文档的本地链接、围栏、空白及 8 个 JSON 示例解析通过，检查包含未跟踪文件。

- 独立计划复核（2026-10-21）：覆盖产品范围、工程、API／持久化、安全及 active changes；初次发现 P1 旧阻塞合同遗漏、P1 分类保护冲突、P1 旧 delta 回写风险、P2 来源身份与派生数据遗漏、P2 关系提交组不一致。全部采纳，修订八项 delta、design 和五项旧 proposal 回链；定向重新复核已逐项关闭，未遗留 P1/P2。该结论仅针对计划。

- 4.1 范围：新增纯领域 `cash_record_policy.py`，未切换旧应用写入口；38 项单测覆盖固定值等价、整条拒绝、人工清空保护、类型组合、草稿补全、精度及协议结构。原地输入不被修改，错误只携稳定码与字段名。独立复核的三个 P2（嵌套 JSON、已填草稿结构、原始繁体备注）已逐项补失败回归并修复。
- 本小步无数据库或 UI 修改，双后端和浏览器 QA 不适用于纯领域函数；前述现状双后端基线不作为本步新服务验收。所有应用入口接入在 4.2、4.9–4.11 完成，功能地图明确仍为重构中。

- 计划提交：`ec5756b docs: plan cash import lifecycle redesign` 已推送 `origin/average-snail`，随后才新增实现测试和代码。首个领域策略测试先因模块缺失失败；实现后发现草稿金额接受浮点，已收紧协议边界，29 项通过。该策略尚待新服务接入，不宣称旧写入口已完成切换。

- 4.1 提交前验证（HEAD `ec5756b`，基线相同）：`uv run pytest tests/test_cash_record_policy.py tests/test_record_type.py -q --disable-warnings --maxfail=1` → 72 passed；OpenSpec strict 和 diff 空白检查通过。独立定向复核三项发现均关闭。

- 4.2 开发中补计划：旧链漏掉流水到工作区账户的复合外键，新增迁移重申；SQLite 有数据 batch 重建先失败，补显式事务及外键恢复边界，往返比较保留父记录、组成项、关系、投影和账户，故意违反外键时迁移整体回滚且版本不前进。SQLite 部分唯一索引不依赖反射，显式重建删除过滤条件。
- 迁移复核发现运行期 schema 常量未推进，已补失败启动测试并更新为 `20261021_38`。当前双后端草稿／迁移／旧导入矩阵 107 passed、无 skip；完整回归按仓库 README 的 `PYTHONPATH=tests:.:src` 方式执行中（普通启动会使现有 conftest 辅助导入收集失败）。

- 4.2 最终定向验证（HEAD `bdcab8c`，基线相同）：显式配置专用 PostgreSQL 后执行 `PYTHONPATH=tests:.:src uv run pytest tests/contract/test_cash_draft_storage.py tests/test_alembic_migration.py tests/test_fact_field_unify.py tests/test_cash_record_policy.py tests/contract/test_cash_import_dual_backend.py -q --disable-warnings --maxfail=2 --tb=short` → 115 passed、无 skip。两个后端都覆盖升级后启动、草稿空值、正式父记录约束、行修订、跨工作区引用、已填数据迁移往返及拒绝有损降级；SQLite 额外验证外键错误整体回滚和不提交调用方写入。
- 4.2 独立工程复核：schema／迁移／运行时与共享转正式校验分别复核。运行期版本、组成项序号缺失／重复、汇总父账户三项发现均补失败测试并关闭；正式值求和采用 80 位 Decimal 上下文，避免合法 38 位值受默认上下文舍入。该步不暴露草稿 API 或页面，不声称统计过滤和后续维护已完成。
- 完整 SQLite CI 回归 `PYTHONPATH=tests:.:src uv run pytest tests -q -m 'not performance' --disable-warnings --tb=short` → 1548 passed、178 skipped、50 deselected、3 failed（170.97 s）。两项旧 Expo CI 断言在 `bdcab8c` 独立提取基线复现为 2 failed／2 passed，因旧工作流已移除，不在本重构恢复 Expo。另一项文档断言揭示现有文档索引缺少运行失败边界，随 schema 文档更新补齐；随后定向重跑。未将 skip 或上述完整回归计为完整通过。日志在仓库外 `/tmp/ft-import-sqlite-regression.log` 与 `/tmp/ft-import-prechange-legacy-tests.log`。
- 文档边界和旧子类型断言调整后的定向回归：`PYTHONPATH=tests:.:src uv run pytest tests/test_runtime_docs.py tests/test_cash_record_subtype.py tests/test_cash_record_policy.py -q --disable-warnings` → 63 passed；OpenSpec strict、`git diff --check` 通过。本步不含 UI，浏览器、Native 和 Hallmark 最终审查不适用，整体 UI 验收任务仍未完成。
- 4.2 提交 `1d0b62e feat(cash): persist incomplete records as drafts` 已推送当前分支。4.3 勘查覆盖六种现金来源的编号、完整账户、范围和原始备注；在 design 补齐未知范围及混合旧编号风险，先实现来源对应规划和当前绑定／问题存储，再由 4.7 新解析边界提供元数据。未改变来源分类规则，故来源 taxonomy 无新增分类单元；真实样本仍只读且不入库内夹具。

- 4.3.1 测试先行：模块／三表缺失先失败；范围筛选及边界、交叉名称、相同次数、等价更新、删除重导、别名保存点回滚和候选修订均有合成用例。可靠编号与认证证据按全部键及已知目标归并批内冲突，不能局部覆盖。无法确定来源账户时实际碰撞延后，不凭昵称猜账户，也不复制正式流水；正式固定字段冲突不消耗旧候选。
- 4.3.1 独立复核覆盖来源匹配、schema／迁移、当前问题和来源往返：已关闭不同稳定证据误匹配、混合编号候选误去重、文件别名绕过账户、非法索引阻塞整文件、仅候选变化不增修订、缺表启动未拒绝、认证行证据重载丢失及只检查优先证据八类 finding。对应反例先失败再修复；来源证据新增 `stable_evidence_key` 同步跨层表合同，不把内容散列提升为身份。
- 4.3.1 定向矩阵（HEAD `1d0b62e`，基线相同）：显式专用 PostgreSQL，`PYTHONPATH=tests:.:src uv run pytest tests/contract/test_cash_source_storage.py tests/test_cash_source_matching.py tests/test_cash_record_policy.py tests/contract/test_cash_draft_storage.py tests/test_alembic_migration.py tests/contract/test_cash_import_dual_backend.py tests/test_runtime_docs.py -q --disable-warnings --maxfail=2 --tb=short` → 200 passed、无 skip。过程中 PostgreSQL 报字符串 ID／BIGINT 比较错误，已规范化 ID 后重跑通过，未用 SQLite 成功代替该后端证据。随后两个纯匹配保护增量及全部领域策略重跑 → 99 passed。
- 性能：200 个来源出现查询上限为 12 次 SELECT，双后端通过；1,000 笔同秒同额且来源内容不同的完整重复集合初次 2.56 s 超过 2 s 预算，去掉重复范围校验并按来源快照预筛“无需修改”的边后，含该场景的 99 项纯测试合计约 0.1 s。快照预筛仅优化重复比较，不能用作业务身份。
- 完整 SQLite 回归：`PYTHONPATH=tests:.:src uv run pytest tests -q -m 'not performance' --disable-warnings --tb=short --deselect=tests/test_mobile_ci_packaging.py::test_mobile_ci_builds_release_like_artifacts_with_embedded_js --deselect=tests/test_mobile_ci_packaging.py::test_mobile_ci_docs_describe_login_origin_and_standalone_artifacts` → 1613 passed、196 skipped、53 deselected（181.47 s），仅显式排除已在旧基线复现的两项废弃 Expo CI 断言；未把其他 backend skip 算作双后端通过。后续规范化 ID 与纯匹配增量的受影响用例另跑；日志在 `/tmp/ft-import-source-regression.log`。最终 API、Compose 和草稿统计合同尚未切换，不勾选整体回归及 UI QA 完成。
- 真实样本只读勘查：仓库外账单目录共 42 个文件（含辅助文件），其中 12 份平台表格可检查表头。6 份支付宝可结构化提取账户和时间范围；6 份微信有昵称、导出类型及范围，正文“微信号”是说明文字，不能当本人微信号提取。新解析必须区分实际身份字段与说明，未知身份沿文件局部定位及后续补充规则；尚未将真实账单写入专用测试库，6.3 保持未完成。
- 4.3.1 提交前复验：上述同一双后端定向命令 → 202 passed、无 skip（26.79 s）；随后仅补未知账户已消耗候选分支的失败回归并修复，纯匹配／更新策略 → 100 passed（0.11 s）。OpenSpec strict 与 diff 空白检查通过。本步依然是未暴露的后端基础，无 Compose 文案或 UI 变化，不以其代替 4.7–4.13 入口及用户验收。
