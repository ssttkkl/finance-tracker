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
- [x] 4.4.1 完成新建议证据／持久角色的共享校验、旧新确认图闭包及保守配对提交分组；保留既有特殊关系类型。
- [ ] 4.4.2 完成父记录及组成项关系结构／候选迁移、类型更新调用、联合草稿成员完整性及最终财务图和组保存点接入。
- [ ] 4.5 为草稿不入统计、唯一当前归属、待确认可见性和局部投影写失败测试；调整投影读写和余额边界。
- [ ] 4.6 为独立账户创建修改、CAS、修订链及幂等删除写失败测试；实现普通账户接口与收据。
- [x] 4.6.1 完成账户版本列／迁移和统一 ORM 修订、稳定 ID、独立 CAS 应用命令、同事务白名单收据及删除引用／旧余额保护。
- [ ] 4.6.2 接入按 ID 的账户 API、CLI／Agent 及 Compose 普通账户表单和连续补偿链，关闭过渡期旧名称写入口。
- [ ] 4.7 为一次解析、不保留原文件、部分文件／行错误、TTL 与权限写失败测试；实现解析会话及临时存储。
- [x] 4.7.1 保留平台原始出现、文件声明及逐行错误，复用既有原生转换语义，校准真实平台表头。
- [ ] 4.7.2 实现仅解析内容的暂存、会话修订、文件替换／密码重传及工作区同步边界，关闭旧原文件会话入口时一起验收。
- [x] 4.7.2.1 完成解析 DTO 的单对象存储、内存／R2 条件替换、固定 TTL、取消清理和基础隔离验证。
- [x] 4.7.2.2 接入文件应用服务、工作区锁和消费收据校验；实现无人访问的过期对象有界清扫及部署运行条件，不能只依赖读取时清理。
- [x] 4.7.2.2.1 完成逐文件应用服务、锁外解析／锁内权限及收据重验、替换命名空间和预算隔离。
- [x] 4.7.2.2.2 接入无人访问的过期对象有界清扫、启动／关闭生命周期和运行配置；最终会话 API 与旧原文件入口切换仍在 4.7.2／4.11。
- [ ] 4.7.3 银行来源迁入同一解析边界；验证原始出现、来源账户／范围、逐条失败和一次读取。
- [ ] 4.8 为不可变计划、预计投影列表、分页／详情、计划替换和发布消费竞争写失败测试；实现计划与目标 DTO。
- [ ] 4.9 为逐行保存点、关系组隔离、缓存回滚、收据同事务、重复键／上下文消费和未知结果写失败测试；实现联合提交。
- [x] 4.9.1 实现同事务收据／逐项结果、用户幂等与会话／编辑上下文消费唯一性；将 PostgreSQL 工作区锁提前到 UoW 入口。
- [ ] 4.9.2 接入完整应用命令、逐行与关系组保存点、结果恢复和收据分页 API；最终切换时迁入并移除旧导入收据。
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

- 4.7 平台原始读取小步（HEAD `d275c6d`，基线相同）：新增不去重的 CSV／XLSX 文档对象，精确保留文本、编号前导零、多行字段及重复出现，提取本人账号与显式覆盖声明；结构坏行单独留安全原因。新模块缺失及逐项边界回归先失败，再实现；`uv run pytest tests/test_cash_statement_document.py tests/test_cash_source_matching.py tests/test_cash_record_policy.py -q --disable-warnings --tb=short` → 136 passed（0.22 s），OpenSpec strict、diff 空白检查通过。
- 本小步独立工程／安全复核：坏行误授完整范围、矛盾声明取首项、稀疏索引绕过预算、维度缩小藏行及关系目标改名绕过 XML 预检均采纳，补失败回归后关闭。XML 部件、非空目录项和真实坐标限制已写回 design；独立再跑 36 项读取测试通过，范围内无遗留阻断。尚未接入原生分类、银行解析、会话或提交，4.7 保持未完成。
- 真实账单只读校准（未入库）：6 份微信共 3,974 次、6 份支付宝共 3,813 次原始出现，12 份声明笔数均与观测一致，结构错误为 0，范围／筛选可解析；微信没有可靠本人账号，支付宝 6 份可提取。过程只输出汇总，不保存文件正文或标识。纯读取不涉及持久化，本步双后端不适用；会话、应用服务、真实入库、Compose 和客户端 QA 均仍待对应任务。

- 4.7.1 逐条归一（HEAD `91f47d3`，基线相同）：从旧转换器抽取两段共享来源函数，不调用跨行去重／配对；新边界把未知金额／时间／方向留空并附原因，意外适配器异常只跳该行。`note` 使用未截断的来源备注／描述，商品字段加入同一原文规则；可靠编号只用渠道交易号。金额取绝对值与变号改用 Decimal 的精确复制，新增 38 位数回归，避免默认上下文舍入。
- 4.7.1 独立工程复核无阻断，相关纯测试 379 passed、1 项既有真实 PDF 测试跳过；执行者的来源／转换／双后端类型及旧导入矩阵 431 passed、同一 PDF 跳过，不将该 skip 算后端验证。12 份真实账单对比旧转换：7,737 条候选的金额、时间、资金归属提示、类型完全一致；另 48 条未支付关闭和 2 条还款失败保留规则跳过原因。没有将真实数据写入账本或 Git。
- 4.7.1 完整 SQLite 回归沿前述同一命令，仅排除两个已复现的废弃 Expo 断言：1677 passed、196 skipped、53 deselected（176.09 s），日志 `/tmp/ft-import-native-regression.log`。尚未切换应用入口或实现银行边界，不据此关闭 4.7／6.1／6.3；没有 UI 改动，浏览器及 Native 不适用于该小步。当前验证后继续开发仅解析内容的会话。

- 4.7.2.1 基础暂存（HEAD `9749cfa`，基线相同）：新接口只接收解析文件 DTO；单一 JSON 原子携带文件、输入和当前计划，创建与替换分别使用不存在条件及 ETag 条件。内存／模拟 R2 共用并发、所有者隔离、固定 TTL、取消防复活、大小／文件数、未知写结果与不续期合同。初始模块缺失先失败，随后分别补跨 TTL、循环 JSON、坏嵌套文档、关闭流及 Unicode 安全错误回归。
- 4.7.2.1 独立工程／安全复核采纳四类 finding：写前不完整校验会破坏旧会话、递归校验晚于复制、关闭流异常泄漏、代理字符编码异常；均补失败回归并关闭。最终独立 46 passed；执行者 `PYTHONPATH=tests:.:src uv run pytest tests/test_parsed_import_staging.py tests/test_cash_import_staging.py tests/test_cash_statement_rows.py tests/test_cash_statement_document.py tests/test_cash_import_session_service.py tests/test_cash_source_matching.py tests/test_cash_record_policy.py -q --disable-warnings --tb=short` → 223 passed（0.37 s）。R2 条件能力查询官方合同，当前 botocore 模型确认支持 `IfMatch/IfNoneMatch`；没有连接或写入真实 R2，不将模拟证据声称为 live QA。
- 本步不写业务数据库，双后端不适用；没有用户可见入口修改，跨端及浏览器 QA 不适用。工作区同步、收据判断、文件应用服务、后台过期清扫和旧原文件路径关闭均尚未完成，4.7.2 保持未完成；对象 CAS 不作为联合提交并发正确性的证据。

- 4.9.1 收据／同步基础（HEAD `64c599d`，基线相同）：新增用户范围的命令收据和分页逐项结果，事务归调用方；当前会话和维护上下文只能消费一次，目标对象删除不会被永久结果引用阻塞。新模块缺失先失败；PostgreSQL 独立复现第二写者提前进入（1 failed／SQLite 1 passed），将工作区锁移至 UoW 入口后双后端通过。迁移 40 只增加新表并保留旧结果，已有正式数据无重写；有收据时拒绝有损降级，运行期同步要求 schema `20261021_40`。
- 4.9.1 独立工程复核的两项 P2（投影引用对象与正式 JSON 合同不符、默认结果分页需要整份排序）均采纳，补失败回归并同步修正 ORM／迁移；另补畸形枚举、跨工作区 FK、旧收据往返保留和拒绝有损降级。复核增量 SQLite 24 passed，无遗留阻断。
- 4.9.1 验证：显式专用 PostgreSQL、`PGOPTIONS='-c lock_timeout=2000 -c statement_timeout=15000'`，`tests/contract` 加 PostgreSQL 适配／运行时／导入及 Alembic → 317 passed、无 skip（85.14 s）。随后最终增量 `tests/contract/test_cash_mutation_receipts.py tests/test_alembic_migration.py tests/test_runtime_docs.py` → 68 passed、无 skip（17.96 s），包含两个后端；SQLite 查询计划证明默认分页不使用临时排序。
- 同时完整 SQLite 回归（沿前述命令及两项已知 Expo 排除）→ 1741 passed、214 skipped、53 deselected（188.98 s），日志 `/tmp/ft-import-receipts-regression.log`；完整收集之后新增的合同与输入修复以上述最终矩阵另验。曾误指定不存在的 `test_postgres_repositories.py`，命令未收集测试，已改用实际 `test_postgres_adapter.py` 运行上述矩阵，不计前者为证据。仍未实现联合提交应用路径或未知结果 API，4.9 保持未完成；无 UI 修改，不适用浏览器／Native QA。

- 4.7.2.2.1 文件会话服务（HEAD `9dbd6df`，基线相同）：新模块缺失先失败，再通过同一应用服务验证两个后端。覆盖逐文件失败、密码重传、锁外解析、权限撤销、解析中会话被消费、收据优先恢复、替换命名空间、单文件／总预算、取消及过期修订；重传不重读其他文件。自动来源识别复用同一次表头读取，歧义表头拒绝；超限文件大小截断的回归先失败再修复，失败元数据保留观测大小。
- 本步独立工程／安全复核：授权、收据与 CAS、TTL、解析失败隔离、文件替换与自动识别，无阻断发现；独立 SQLite／纯测试 97 passed，未访问 PostgreSQL 或真实 R2。执行者显式专用 PostgreSQL 执行 `tests/contract/test_parsed_cash_import_context.py tests/test_parsed_import_staging.py tests/test_cash_statement_document.py tests/test_cash_statement_rows.py` → 136 passed、无 skip（7.56 s）；OpenSpec strict、diff 空白检查通过。后端内部服务尚未暴露 HTTP 或 Compose，Web、Native、共享 presentation 无行为变化，浏览器／Hallmark 最终审查不适用于本步。后台清扫、银行来源和完整计划／提交仍未完成，不勾选整体 4.7。

- 4.4.1 角色与分组（HEAD `152ac1c`，基线相同）：新模块缺失先失败；33 项纯测试覆盖新建议逐端证据、既有角色与来源解耦、类型兼容、原有特殊关系、草稿端点拒绝、旧新闭包、退款钻石及联合草稿。独立工程复核重新检查既有发现器，33 passed、无阻断；确认完整联合成员加载和最终财务校验仍属后续接入，不以保守分组替代它们。
- 本步定向 `tests/test_cash_relation_policy.py tests/test_transaction_relations_transfer.py tests/test_platform_refund_matchers.py tests/test_cash_projection.py tests/test_transaction_relations_projection.py tests/test_cash_record_policy.py` → 140 passed、2 项 PostgreSQL 参数因该次未配置连接而 skipped（1.25 s），OpenSpec strict 通过；随后显式配置专用 PostgreSQL 补跑同一组，不把 skip 计为通过。首次误指定不存在的 `test_platform_refund.py` 未收集测试，不计为验证。纯领域策略不写库、不改变 Web／Native／共享 presentation；浏览器及最终 UI audit 不适用于本步，4.4 整体与调用入口仍未完成。
- 上述同一命令显式配置专用 PostgreSQL 后 → 142 passed、无 skip（2.08 s），补齐既有投影的两项后端参数；最终 diff 空白检查通过。

- 4.7.2.2.2 无人访问清扫（HEAD `8871394`，基线相同）：先以缺少清扫接口／worker 的失败用例驱动实现，内存和模拟 R2 分页删除固定到期对象；R2 仅列私有解析前缀并读元数据，不下载正文或触碰其他键。服务生命周期强制启动／停止清扫，首轮不可用则启动失败；运行故障只记录安全健康码，保留进度后重试。运行配置、权限、网络超时及停机期间的物理删除边界写入临时存储说明，未声称真实 R2 部署验证。
- 独立安全／生命周期复核发现 P2：首轮远程清扫持有启动锁，使停止信号无法传达。回归先失败，再将远程清扫移到短锁外，并用启动完成事件和停止计数防止关闭后创建 worker；重新复核关闭该发现，无新增问题，独立 75 passed。执行者的新暂存／清扫增量同为 75 passed，包含实际 TestClient 启动／关闭及启动失败回归。
- 显式专用 PostgreSQL 的暂存、清扫、文件会话应用服务、旧会话及配置定向矩阵 → 130 passed、无 skip（8.21 s）。完整 SQLite 回归沿前述命令及两项已知 Expo 排除 → 1824 passed、232 skipped、53 deselected（190.90 s），日志 `/tmp/ft-import-context-cleanup-regression.log`；完整收集后的生命周期修复由上述 75 项增量重验，不把后端 skip 算作通过。没有修改用户可见 UI 或文案，Web／Native／共享 presentation 无变化，浏览器／Hallmark audit 不适用于本步。旧原文件路径、银行和最终 API／Compose 仍未切换，4.7 和整体回归验收保持未完成。

- 4.6.1 账户命令基础（HEAD `57bd3a6`，基线相同）：新模块及版本列缺失先失败；迁移 41 保留正式值和引用，SQLite 另保留已删 ID 高水位（包含空账户表），已有账户修订推进则拒绝有损降级。统一 ORM 版本列覆盖旧普通重命名、停用和导入币种增补；新命令在工作区／账户锁内重验成员、CAS 并先查收据。创建立即提交，删除须先停用；引用、草稿组成项及非零旧快照阻止补偿删除；修改配置不删已有余额。
- 独立工程／数据安全复核采纳 P1 加密账户快照归属及 P2 异常桶误当无余额：`crypto/security` 共用 `accounts.security` 定位，GET 和 DELETE 共用保守删除检查，字符串／列表／`null` 均不能绕过。回归分别先失败（含 `null` 残余分支）再修复，定向重新复核 4 passed，P1／P2 清零。账户收据只允许普通账户结果字段，并拒绝来源正文及 ID／修订不一致。
- 账户及迁移／收据初次显式双后端矩阵 → 117 passed、无 skip（31.80 s）；扩展 `tests/contract`、PostgreSQL 导入／适配／运行时及 Alembic → 404 passed、无 skip（117.34 s）。随后新增边界用例及修复单独重验，不把前一矩阵当成最终增量验收。
- 完整 SQLite 回归沿前述命令及两项既有 Expo 排除 → 1851 passed、256 skipped、53 deselected（215.33 s），日志 `/tmp/ft-import-account-cas-regression.log`；收集之后的加密账户／异常结构修复由账户与收据增量另验。当前仍是后端内部能力，对外账户 API、CLI 和 Compose 尚未切换，不勾选 4.6 整体；没有 UI／用户文案修改，Web、Native、共享 presentation 无行为变化，浏览器与最终 Hallmark audit 不适用于本步。
- 最终账户／收据／迁移／运行文档矩阵显式配置同一专用 PostgreSQL → 134 passed、无 skip（38.55 s），覆盖全部上述增量和双后端；OpenSpec strict、`git diff --check` 通过。
