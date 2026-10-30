# Compose 功能地图

> 盘点日期：2026-10-29。本文记录 Compose 页面级功能、实现证据、跨端覆盖和当前缺口；不保留逐步开发日志。行为合同见 [OpenSpec 导航](../openspec/specs/README.md)，变更验证记录见各自 `tasks.md`。

Compose Web、Android、iOS 共用 `compose/shared`；工程入口分别为 `compose/webApp`、`compose/androidApp`、`compose/iosApp`。旧 React `web/src` 和 Expo `mobile/src` 只作历史迁移参考。

## Kotlin 后端迁移与公共业务层

当前生产后端仍为 Python，Kotlin 全量迁移进行中；完整范围与未完成项见 [迁移盘点](../openspec/changes/kotlin-backend-shared-core/inventory.md) 和 [实施证据](../openspec/changes/kotlin-backend-shared-core/tasks.md)。Kotlin 本机预览已实现认证、工作区、邀请、成员接口、分类目录读取/创建/编辑/排序/删除检查/删除、收支单项/批量分类及账户按 ID 的读写、现金/投资列表、永久收据五个 GET 、管理员投影状态查询/重建、账本类型选项、手工收支新建、完整转账创建及旧现金新增/余额校准；工作区删除仍缺完整财务数据专项，不替代生产入口。Compose 当前没有管理员投影状态入口。公共认证、账户、分类目录、收据、结果分页、诊断及选项 DTO 和角色由 Compose 与后端共同消费；分类祖先、循环及深度规则进入双方正式调用，精确分摊目前由 Compose Web、Android、iOS 使用；对应实现位于 `compose/business-contracts`、`compose/business-domain`，后端 JVM 消费这些独立模块，不依赖 Compose UI。账本类型选项通过[共用选项协议](../compose/business-contracts/src/commonMain/kotlin/com/finance/tracker/business/contracts/LedgerOptions.kt) 和现有客户端实际接通，保留原标签/顺序，未改变页面。共享金额合法范围、已知现金部分一致性另有 Python 黄金；永久收据按操作者和工作区读取、旧键查询及结果分页已接入预览，账户命令实际复用收据写入前校验与原键重放；Compose 的超 Long 计数解码缺口仍待处理。后端字段/转正式、投影及完整关系图纯规则已有原生迁移测试和差分，内部增量维护已通过双库，手工收支新建已接入同事务写入，保留明确清空的人工保护；[后端实现](../compose/backend/src/main/kotlin/com/finance/tracker/backend/ManualCashService.kt) 与[既有 Compose 创建消费者的实际回包验证](../compose/shared/src/androidHostTest/kotlin/com/finance/tracker/KotlinManualCashResponseIntegrationTest.kt) 已覆盖，F-03/F-04 的普通创建 API 可用于本机预览；转账编辑、维护、导入和 OpenAPI schema 的迁移 TODO 保留。没有新增页面或 Native 入口，不能据此宣称其他财务写入或客户端全部金额规则已迁移。

分类删除已通过当前使用量/修订检查、含已删除流水的空分类保护、拆分恢复值与全部投影清理，并由[现有 Compose 确认 API 的实际回包测试](../compose/shared/src/androidHostTest/kotlin/com/finance/tracker/KotlinCategoryDeletionResponseIntegrationTest.kt) 验证；收支单项/批量分类已验证完整成员、同值人工保护和原子回滚，并由[现有 Compose 分类 API 的实际回包测试](../compose/shared/src/androidHostTest/kotlin/com/finance/tracker/KotlinClassificationResponseIntegrationTest.kt) 验证。分类独立 move 服务已接入同事务增量维护；现有 update 保持 Python 的调用边界。

旧现金新增/余额校准的 [后端服务](../compose/backend/src/main/kotlin/com/finance/tracker/backend/LegacyCashflowService.kt) 已完成双库功能验收；[完整 HTTP 对照](../compose/backend/src/test/kotlin/com/finance/tracker/backend/LegacyCashflowHttpContractTest.kt) 核对 96 个场景及 13 张财务表，响应编码失败整笔回滚，校准仅修改指定币种余额。Web、Android、iOS 当前没有调用这两个旧接口，不新增对应页面或共享消费者；已有页面覆盖和 TODO 保留。

已实现的 Kotlin 持久化调用已迁到 jOOQ，功能在 SQLite 和 PostgreSQL 18 验证；这是后端实现变化，页面数及原有 Web/Native TODO 不变。[当前收支读取](../compose/backend/src/main/kotlin/com/finance/tracker/backend/CurrentProjectionReadService.kt) 已实现详情、按流水定位和收支证据，实际回包由 [成员消费测试](../compose/shared/src/androidHostTest/kotlin/com/finance/tracker/KotlinCurrentProjectionResponseIntegrationTest.kt) 与 [证据消费测试](../compose/shared/src/androidHostTest/kotlin/com/finance/tracker/KotlinEvidenceResponseIntegrationTest.kt) 验证。最终独立审查仍待收尾；列表、单条流水详情及维护接口未完成，不能据此将 F-03/F-04 标为 Kotlin 后端全流程可用。完整账单原文读取仍依赖单条流水详情；[来源事项选择规则](../compose/backend/src/main/kotlin/com/finance/tracker/backend/CashSourceResolutionPolicy.kt) 已有原生及 Python 差分验证，[来源正文派生](../compose/backend/src/main/kotlin/com/finance/tracker/backend/CashSourceEffects.kt) 另有六类来源的原生断言及完整差分，保留可信身份和人工备注。两类规则尚未接入详情或维护事务，F-03/F-04 的对应 TODO 保留。旧证据 DTO 不支持草稿空金额／空时间，现有来源成员 DTO 可消费这些空值。公共生成类型没有进入 Web、Android 或 iOS，Linux 尚未验证 iOS 编译。

本轮页面数仍为 10，F-01 认证、F-04 编辑分摊与 F-07 分类任务的入口、跨端覆盖和既有验收 TODO 不变。共享模块与新运行时的验证状态以本轮实施证据为准，不能把模块存在写成全部后端或所有平台已验收。其余 Kotlin HTTP、格式适配、完整导入/维护/投影/关系/投资和后台任务均未完成；原 Python 功能保留。

稳定收支对象仓库的公共人工值、拆分恢复和事务缓存，以及投影源读取、局部关系上下文、完整组校验和摘要已有双库内部验证，已接入全量重建/原子发布与新工作区初始化，管理员重建 HTTP 已通过真实双库对照，内部增量维护已通过双库；手工收支新建与转账创建已接入，转账编辑、维护和导入写入仍待迁移；没有新增 Web 或 Native 入口，F-03/F-04 的迁移 TODO 保留。 转账创建迁移证据为 [`CashTransferService.kt`](../compose/backend/src/main/kotlin/com/finance/tracker/backend/CashTransferService.kt) 与 [`Compose 实际回包消费`](../compose/shared/src/androidHostTest/kotlin/com/finance/tracker/KotlinTransferResponseIntegrationTest.kt)；Web/Native 共用现有创建消费者，不增加页面。

Kotlin 多文件来源批次规划已实现重复出现合并、最大次数、可靠快照选择和共同结果引用，见 [实现](../compose/backend/src/main/kotlin/com/finance/tracker/backend/CashSourceBatch.kt)。当前为导入内部规则，尚未接上传、暂存或提交事务；页面数仍为 10，Web、Native 和共享客户端均无变动，F-05 的 Kotlin 导入流程 TODO 保留。

Kotlin 内部来源账户建议及对应发布仓库已实现，读取不写默认，同值发布不改变修订；[资金组扫描与账户选择准备](../compose/backend/src/main/kotlin/com/finance/tracker/backend/CashImportFunding.kt) 已实现并通过完整 Python 结果对照及原生回归；上传与成功使用证明的真实接线仍待完成，F-05 页面数与两端 TODO 不变。

Kotlin 解析会话已具备编解码、内存/R2 条件对象端口、修订与原有效期、取消和公开页缓存，见 [存储实现](../compose/backend/src/main/kotlin/com/finance/tracker/backend/ParsedImportStore.kt)。R2 SDK 与清扫器已接本机运行入口，维护计划已使用同一配置存储，提交失败保留方案，提交成功后清空临时正文；上传文件准备纯层、只读来源规划/会话视图及上传 HTTP（创建/追加/替换/范围/删除/取消/读取）已实现，解析在锁外、发布与永久收据同锁；已保存导入计划的完整视图、预计收支分页、单项详情和处理明细分页也已接只读 HTTP，按当前权限、永久收据和计划快照核对。导入计划公开视图、逐流水操作规划、[只读关系候选查询](../compose/backend/src/main/kotlin/com/finance/tracker/backend/CashImportCandidateRepository.kt)、[预览准备](../compose/backend/src/main/kotlin/com/finance/tracker/backend/CashImportPreviewPreparation.kt)及条目目标/公共条目编辑纯层已实现，但关系匹配阶段、业务计划构建、预览发布/提交仍未接入，F-05 两端 TODO 保留。本片不改变 Web、Native 或共享客户端。

Kotlin 单流水维护提交已补当前账户、事项、关系上下文和收支快照重验，工作区锁后重新核对权限；失败保留原方案，来源事项和人工字段按原合同写回。本片不增加页面，Web、Native、共享客户端均无修改，F-03/F-04 的完整 Kotlin 维护流程 TODO 保留。

## 现金导入后端与客户端边界

流水模型、展示与维护流程见 [现金流水](cash-ledger.md)，账单入库过程见 [现金账单导入](cash-import-flow.md)。现金导入后端已完成开发及验收，旧公开写入口已关闭；Compose 已接入 F-03 当前状态列表、F-04 当前详情、草稿维护、公共整笔编辑、普通记账／转账创建和完整转账编辑；F-05 已切换当前会话与统一核对入口；来源／身份问题已有真实 API 最终冒烟；密码重传等分支未逐项 Web 穷举，演示确认仍待完成。共享入口由 Web、Android、iOS 共用；直接转账响应已修复列表中的精确金额序列化。默认验证按 AGENTS.md 执行 Web 冒烟及相称原生编译／单元测试，不将未执行的设备 QA 记为通过。页面数没有增加。现金详情／表单和导入准备页已按修改前的视觉基线恢复信息层级，全部可编辑字段按业务分组展开，编辑操作固定在底部，浅深主题交互蓝配对已通过对比度回归；当前演示尚待用户确认。业务规则见 [规则与设计取舍](cash-import-redesign.md)，协议见 [接口与数据契约](cash-import-redesign-contracts.md)；开发、迁移、双后端及真实账单验收集中在 [实施记录](../openspec/changes/cash-import-lifecycle-redesign/tasks.md)。

| 能力 | 后端及共享基础的实现证据 | 客户端状态 |
|---|---|---|
| 多文件解析与临时内容 | [会话服务](../src/ft/application/cash_import_context.py)、[会话 HTTP](../src/ft/web/cash_import_routes.py)、[解析及清扫配置](cash-import-r2-staging.md)；一次解析，不留原文件，单文件错误可继续 | 已接共享准备页、逐文件重选、密码重传及来源设置；合成 CSV 上传已通过 Web 实测，密码与替换分支仍待最终验收 |
| 来源对应与资金账户 | [来源规划](../src/ft/application/cash_import_source_plan.py)、[资金桥接](../src/ft/application/cash_import_funding.py)、[账户映射](../src/ft/application/cash_import_account_mappings.py)；默认微信来源、无编号出现次数、重复／更新及删除重导 | 每组映射下拉可新建账户，按旧 Web 规则从名称推断类型（名称含“花呗”或“信用卡”使用贷款账户，其余使用现金账户），预填来源名称与币种并在创建后回选；唯一身份匹配不因币种不足隐藏，点击下一步按目标账户合并并补齐缺失币种。未选账户可导入为草稿；复杂分摊与来源设置仍需专项验收 |
| 统一预览与一次确认 | [业务计划](../src/ft/application/cash_import_business_plan.py)、[联合提交](../src/ft/application/cash_import_commit.py)、[计划 HTTP](../src/ft/web/cash_plan_routes.py)；不可变计划提供按筛选的准确总数及序号分页 | 已接待确认优先、零待确认时默认全部的只读列表；复用主页流水行、Paging3 连续加载与右侧序号滚动条，详情在宽屏侧滑、小屏切入并在返回时保留列表位置和筛选；整份确认和实际收据摘要可演示。复杂分支及演示确认待完成 |
| 结果及失败恢复 | [失败项原因](../src/ft/application/cash_import_issues.py)、[命令收据](../src/ft/adapters/relational/cash_mutations.py)、[旧收据迁移](../migrations/versions/20261023_49_unify_cash_import_receipts.py)；实际结果分页、未知核实、限时失败补处理 | 已接结果摘要与统一错误；实际草稿摘要及原命令未知核实均有 Web 证据，不提供逐项失败处理入口 |
| 草稿及正式流水维护 | [当前详情／草稿保存](../src/ft/application/cash_record_maintenance.py)、[维护规划](../src/ft/application/cash_mutation_business_plan.py)、[维护提交](../src/ft/application/cash_mutation_commit.py)；创建方式权限、稳定公共属性整笔编辑、来源问题及身份合并；原子草稿仅联动实际财务修改，保留来源明确组成项 | F-04 已接当前详情、普通草稿保存、分摊编辑、明确转正式和只读账单原文；公共属性整笔编辑已接入并通过真实 API 冒烟；来源采用和明确身份合并已通过真实 API 冒烟，演示验收待完成 |
| 关系及当前展示 | [最终图校验](../src/ft/domain/cash_relation_graph.py)、[稳定对象](../src/ft/adapters/relational/cash_entries.py)、[当前查询](../src/ft/adapters/relational/web_queries.py)；待确认不合并，人工冲突延后、解除明确继承，只差量更新受影响闭包，全额退款净额 `0` 可见；列表直接返回根流水 `transaction_id`，来源编号保持独立 | 正式／草稿列表、未知值和待确认筛选已接共享入口；关系维护已展示完整候选成员，选择失效须重新核对；完整组、公共冲突及解除继承已通过真实 API 冒烟；旧任意关联写入已移除，不新增关系一级入口 |
| 统计与删除 | [余额](../src/ft/application/cash_balances.py)、[投影](../src/ft/application/cash_projections.py)、[身份迁移](../migrations/versions/20261021_46_cash_instance_identity.py)；草稿不入金额统计，删除清理当前引用并释放来源绑定 | 现有删除入口及新草稿操作须随当前合同统一验收 |
| 独立账户 | [账户命令](../src/ft/application/account_commands.py)、[账户 HTTP](../src/ft/web/account_routes.py)；按 ID、修订及幂等键立即保存，账户类型创建后固定 | 已接独立账户表单与即时保存；实际新建后取消导入再撤销账户已通过 Web 实测，未知账户及编辑分支仍待最终验收 |
| 客户端基础 | [当前领域合同](../compose/shared/src/commonMain/kotlin/com/finance/tracker/domain/CashImportLifecycle.kt)、[任务编排](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportTaskViewModel.kt)、[账户编排](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/ImportAccountViewModel.kt)、[最小恢复存储](../compose/shared/src/commonMain/kotlin/com/finance/tracker/core/ImportRecoveryStore.kt) | 任务与账户 VM、恢复存储已接真实 App／Graph 入口；Web 已有主流程证据，Android／iOS 共用入口，按默认编译／单测验证；不据共享代码宣称设备 QA 通过 |
| Agent 入口 | [导入技能](../.agents/skills/cash-statement-import/SKILL.md) 和 [API 参考](../.agents/skills/cash-statement-import/references/api-contract.md) 已切换当前后端合同 | 同一后端的操作指引，不新增页面，不替代 Compose QA |

现金—投资资金关联仍是独立能力，后端只允许导入或缺证历史的正式现金流水参与，普通手工及手工转账禁止对外关联；投资事件、账户别名、手工新建、资金调拨和余额校准不因现金导入旧入口退役而移除。真实库迁移、旧外部原文件对象清理及部署未执行，条件见实施记录。

## 现金客户端实施模块

当前现金缺口由三个前端 change 负责，处于实施阶段，覆盖共享 Compose Web、Android、iOS；页面总数与已验收覆盖不变。

| 前端 change | 功能边界 | 已有基础与待补内容 |
|---|---|---|
| [账单导入](../openspec/changes/compose-cash-import-workflow/proposal.md) | 文件／独立账户准备、统一核对、确认、真实结果、未知核实与退出恢复 | 共享全屏对话框已接文件／映射分步、逐组下拉新建账户与自动回选、只读列表／详情、独立账户、实际结果和退出保护；第一版编译及代表 Web 视觉通过，等待用户确认，复杂分支和最终 QA 待完成 |
| [账本与补处理](../openspec/changes/compose-cash-ledger-maintenance/proposal.md) | 当前列表／详情、草稿、来源问题、已保存关系组、分类与删除 | 当前列表／详情、状态筛选和草稿维护已接入；来源问题已接线；关系完整组、公共冲突及解除继承已通过真实 API 验收，最终演示待确认 |
| [记账与编辑](../openspec/changes/compose-cash-entry-editor/proposal.md) | 公共属性整笔编辑、普通手动记账、同入口转账及双端编辑 | 公共整笔编辑及创建方式权限已接入；普通记账、转账创建及模式输入保留已接入；完整转账在同表单编辑双端，缺少完整组能力时只改公共字段 |

先由账本与补处理模块冻结公共维护协议及宿主，再按明确文件归属并行实施；实际入口、相称验证及演示确认完成前，不把底层共享代码计为功能可用。UI 方向为保留现有整体外观，导入完整任务页；编辑只列可改项、固定信息放摘要；“记一笔”内切转账模式。

## 页面级功能

| ID | 页面级功能 | Compose 共享实现证据 | 当前实现 |
|---|---|---|---|
| F-01 | 登录与注册 | [`AccessScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt)、[`AuthResponsiveShell.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AuthResponsiveShell.kt) | 登录、注册与认证状态恢复 |
| F-02 | 工作区选择与创建 | [`App.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/app/App.kt)、[`AccessScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt) | 选择、创建、进入失败恢复 |
| F-03 | 收支账本 | [`CashLedgerScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt)、[`CashLedgerScrollbar.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScrollbar.kt)、[`CashProjectionPagingSource.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashProjectionPagingSource.kt)、[`CashProjectionApi.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/data/CashProjectionApi.kt)、[`web_queries.py`](../src/ft/application/web_queries.py)、[`web_queries.py`](../src/ft/adapters/relational/web_queries.py)、[`routes.py`](../src/ft/web/routes.py) | 搜索、筛选、绝对序号分页、流水列表和操作入口；按当前筛选结果准确计数并以稳定顺序读取指定流水位置，返回月份序号区间；完整刷新立即隐藏旧流水和汇总，按窗口显示骨架并支持列表顶部下拉刷新，分页追加保留现有流水；Compact 窄滚动条在滚动时显示、停止 2 秒后渐隐，轨道和滑块贴列表右边缘并覆盖列表、不挤占宽度，交互热区为 40 dp；Regular/Wide 月份表格容器及吸顶栏右边缘与筛选卡对齐，行内容左右内缩 16 dp；纤细滚动条贴近行内容右边界并常驻显示，40 dp 交互热区覆盖列表且不缩窄内容；月份摘要及桌面表格列标题在筛选卡下方固定并随下月到达顶部切换；FAB 覆盖列表且末行可达；Regular/Wide 行内容内缩 16 dp、分隔线全宽；详细筛选关闭入口在左；Regular/Wide Basic Dialog 宽 280–560 dp、28 dp 圆角、动态高度，标题/正文间距 16 dp、正文/操作间距 24 dp，操作组靠右，Compact Bottom Sheet 外框与行为不变
| F-04 | 现金流水 | [`CashCurrentRecordPanel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCurrentRecordPanel.kt)、[`CashRecordSummary.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashRecordSummary.kt)、[`CashLedgerViewModel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerViewModel.kt) | 迁移中：已使用 `CashCurrentRecordPanel` 和维护任务状态接入当前详情、草稿及分摊保存、明确转正式和只读来源；详情摘要与导入预览共用平面字段；当前详情按摘要、关联流水、账单原文排列，后两个区块直接展示；关联建议可接受完整方案或拒绝整组，正文通过“关联流水”发起搜索选择，单方案默认选中、建议搜索锁定类型／子类型；联合方案改选只可搜索、暂不允许提交；冲突后可返回选择，拒绝／移除在原确认页完成一次最终确认；公共整笔编辑、普通记账／转账创建及完整双端编辑已接入。关联 v6 及走查修复已通过 22 项 Chromium 合成 API 流程、421 项共享单测和相称跨端编译；联合方案改选暂不允许提交。来源／身份问题保留既有真实 API 冒烟证据。旧直接编辑／新增关联页面和客户端 API 已移除，当前流程见 [现金流水](cash-ledger.md) |
| F-05 | 现金账单导入 | [`CashImportTaskScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportTaskScreen.kt)、[`CashImportTaskViewModel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportTaskViewModel.kt)、[`CashImportItemsPagingSource.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportItemsPagingSource.kt)、[`CashImportReviewList.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportReviewList.kt)、[`CashImportPreviewPanel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportPreviewPanel.kt)、[`CashImportDiagnostic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportDiagnostic.kt) | 覆盖账本的全屏对话框、文件／账户分步、来源账户预填与新建、身份建议、下一步自动补币种、待确认优先筛选、主页同款流水行与 Paging3 连续分页、右侧序号滚动条；详情按摘要、关联流水、账单原文排列，候选只读且证据不足只显示待确认状态；实际收据摘要和错误诊断已接入，复杂分支及演示确认待完成 |
| F-06 | 工作区邀请 | [`WorkspaceScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/WorkspaceScreens.kt)、[`AppRouting.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/core/AppRouting.kt) | 邀请预览、接受与取消 |
| F-07 | 收支分类管理（管理页内） | [`CashCategoryScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryScreen.kt)、[`CashCategoryViewModel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryViewModel.kt) | 管理页内嵌分类目录、搜索、排序和删除确认；Compact 全屏、Regular/Wide 对话框编辑；旧分类链接定位管理页分类分组 |
| F-08 | 投资持仓 | [`InvestmentScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentScreens.kt)、[`InvestmentDisplayLogic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentDisplayLogic.kt) | 账户筛选、估值、表现和持仓详情；Basic Dialog 宽 280–560 dp、28 dp 圆角、四周 24 dp 留白；标题区自适应，标题/正文间距 16 dp、正文/操作间距 24 dp，左侧关闭入口为产品例外 |
| F-09 | 投资事件 | [`InvestmentScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentScreens.kt)、[`InvestmentDisplayLogic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentDisplayLogic.kt) | 事件筛选、分页和证据详情；Basic Dialog 宽 280–560 dp、28 dp 圆角、四周 24 dp 留白；标题区自适应，标题/正文间距 16 dp、正文/操作间距 24 dp，左侧关闭入口为产品例外 |
| F-10 | 管理（工作区与收支分类） | [`WorkspaceScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/WorkspaceScreens.kt) | 以 M3 文字角色、平面分组和细分隔呈现；两级统一管理：工作区资料→基本信息/危险操作、访问权限→成员与权限/邀请成员、收支账本→收支分类；Regular/Wide 右侧目录、Compact Toolbar 全屏目录，共享两级同页定位与当前项，投资无占位；实现见 [`ManagementLayout.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/ManagementLayout.kt)、[`ManagementNavigation.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/ManagementNavigation.kt)，验证记录见 [`tasks.md`](../openspec/changes/archive/2026-10-21-unify-management-settings/tasks.md) |

以上 10 项按用户任务统计，F-07 嵌入 F-10 后仍保留独立分类任务。各项均有共享 Compose 页面代码，并由 Compose Web、Android、iOS 三个目标平台承载；其中 F-04／F-05 演示确认及 F-05 复杂分支仍待验收，不能据旧代码存在认定完整流程可用。页面数不因迁移增加，当前没有仅一端完成本次切换。共享代码存在不代表每个 target 的设备 QA 均已覆盖。

## 当前已知覆盖缺口

F-04 开发中：导入流水自由搜索新增关联、按实际关系标注全部成员原文，以及在弹窗中完成关联操作，已接入 [共享详情](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashDetailRelations.kt) 及维护任务，影响 Web、Android、iOS。关联流水和账单原文均直接展示；单备选建议与已有关联流水共用完整基础信息布局，备注带标签；有效待确认关系展示无按钮的警示提示，接受／核对与拒绝在弹窗处理；添加关联在搜索前以单选分段按钮选择关系类型；有备选时先单选完整方案，点击“选择其他流水”切换搜索，可返回并保留原方案；无备选直接搜索；搜索单选一笔对侧流水并沿用建议类型。桌面复用表格，COMPACT 复用卡片。范围与当前验证缺口见 [详情关联实施记录](../openspec/changes/cash-record-detail-relations-redesign/tasks.md)，已完成代表视口反馈验证、非性能回归及受影响 SQLite／真实 PostgreSQL 契约；复杂浏览器保存与完整最终验收仍待完成，不计为已最终交付能力，页面数不变。

- F-04／F-05：复杂导入分支及演示确认仍需完成；当前详情、来源原文、草稿和分摊保存／转正式已通过代表视口真实 API 冒烟。关闭 Web 弹层后的无障碍树更新及禁用状态语义缺陷仍保留；不升级 Compose。以下原生设备覆盖缺口记录未验证范围，不作为默认验证门禁。

- F-07 / F-10：本次管理导航、目录和分类模态的 Web、Native 新验证记录于 [`tasks.md`](../openspec/changes/archive/2026-10-21-unify-management-settings/tasks.md)；Android 390 / 768 / 1440 dp 已通过目录、分类模态保存及旧链接定位；iOS 本轮仅共享编译，实际 iPhone/iPad 新布局仍待验收。

- F-05：iOS 文件选择器选中文件后交回应用、直至完成导入的端到端流程仍需实机验证。
- F-06：HTTPS Universal Links 需要部署域名与签名配置。
- F-07：iOS 分类管理的完整操作集仍需设备验证。
- F-10：iOS 工作区删除流程仍需设备验证。
- 尚无一份覆盖全部页面、平台与窗口尺寸的完整跨平台覆盖矩阵。

“Compose 共享实现覆盖”表示页面具备可运行的 Compose 入口，并接入真实 API；仅有后端接口、共享文案、原型、规格或 legacy 端导航均不算当前主线实现。

## Compose Multiplatform 主线

F-01 至 F-10 的共享页面及既有调用代码位于 `compose/shared/src/commonMain`；Compose Web、Android、iOS target 使用同一套页面实现，其中 F-04 当前详情、草稿维护及公共整笔编辑已接入，转账创建及完整双端编辑已接入，F-05 当前协议和主流程已接入，复杂分支及演示确认仍待完成。旧 React UI 位于 `web/src`，保持原样并仅作为迁移参考；本功能地图末尾保留其历史实现证据。各 target 的已验收范围和待补 QA 记录在下表。跨页面入口壳与已登录标题层级已由 [`app-shell-entry-presentation`](../openspec/changes/archive/2026-10-12-app-shell-entry-presentation/) 对齐：F-01/F-02/F-06 共用登录式响应布局和居中卡片，F-03 至 F-10 由共享 `DestinationScaffold` 承载；F-05 以全屏对话框提供独立标题和关闭入口，其余任务在应用顶栏显示页面标题；桌面侧栏默认显示图标，鼠标进入侧栏自动展开、离开后收回，鼠标点击导航产生的焦点不会阻止收回，展开时点击收起控制可立即收回；键盘焦点仍可展开侧栏，头像悬停打开账号面板且在停留期间保持稳定，Enter/Space 可从键盘焦点打开面板，Escape 可关闭并将焦点返回头像；工作区切换、管理和退出集中在侧栏底部；侧栏交互与跨端 QA 证据记录于 [`navigation-rail-collapse`](../openspec/changes/archive/2026-10-13-navigation-rail-collapse/tasks.md) 和 [`account-menu-hover-stability`](../openspec/changes/archive/2026-10-13-account-menu-hover-stability/tasks.md)。

| ID | Compose 共享实现 | Web / Android / iOS 工程入口 | 当前状态 |
|----|------------------|------------------------------|----------|
| F-01 | [`AccessScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt)、[`AuthResponsiveShell.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AuthResponsiveShell.kt) | [`webApp`](../compose/webApp/build.gradle.kts)、[`androidApp`](../compose/androidApp/src/main/kotlin/com/finance/tracker/MainActivity.kt)、[`iosApp`](../compose/iosApp/iosApp/ContentView.swift) | 登录 / 注册使用 FinanceTheme 下的标准 Material 3 浮动标签、辅助文本和控件形状，并与工作区/未登录邀请共用响应式外壳和居中卡片；间距与控件迁移见 [`auth-page-spacing-shell`](../openspec/changes/archive/2026-10-12-auth-page-spacing-shell/)；本轮 Compose Web Chrome、Android device QA 记录见 `app-shell-entry-presentation/tasks.md` |
| F-02 | [`App.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/app/App.kt)、[`AccessScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt) | 同上 | 工作区选择、创建和恢复已实现；首次创建与已有工作区选择共用登录式容器、账户头部和居中卡片，保留列表选择、名称校验、请求错误、重试和创建成功导航；布局与 QA 证据见 [`app-shell-entry-presentation`](../openspec/changes/archive/2026-10-12-app-shell-entry-presentation/) |
| F-03 | [`CashLedgerScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt)、[`CashLedgerScrollbar.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScrollbar.kt)、[`CashProjectionPagingSource.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashProjectionPagingSource.kt)、[`CashProjectionApi.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/data/CashProjectionApi.kt)、[`FinanceComponents.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/FinanceComponents.kt) | 同上 | 顶部搜索复用交易对方筛选，类型快捷项固定为全部、支出、收入和转账；详细筛选分为时间、金额、分类、账户、其他条件五组，金额组包含币种和金额范围，其他条件包含资金转移类型和合并状态；`COMPACT` 按组纵向排列，`REGULAR` / `WIDE` 在组内并排显示可配对字段。详细筛选在 `COMPACT` 使用 Bottom Sheet，`REGULAR` / `WIDE` 使用非全屏 Dialog；Basic Dialog 宽 280–560 dp、28 dp 圆角、动态高度；标题/正文间距 16 dp、正文/操作间距 24 dp，操作组靠右且关闭入口在左；Compact Bottom Sheet 外框与行为保持不变；筛选区以独立 Material 3 前景卡固定在页面顶部，只有其下方的流水列表滚动；所有尺寸的 FAB 叠加在列表视口上，末条保留滚动空间以完整显示在按钮上方；每个月份形成独立前景容器，月份汇总在筛选卡下方的列表视口顶部固定并由下月替换，`REGULAR` / `WIDE` 的表头同步固定，`COMPACT` 只固定汇总；`REGULAR` / `WIDE` 月份容器及吸顶栏右边缘与筛选卡对齐，每行内容向内缩 16 dp 且分隔线保持全宽。跨页记录仍逐笔懒加载，按相邻月份边界连续绘制。页面底色作为下层背景，筛选卡与月份容器之间留有间隔。Compact 默认隐藏复选框并以长按进入选择模式，Regular/Wide 保留行复选框，所有窗口移除全选入口。选中后 FAB 菜单自动展开并切换多选图标，已选数量以提示胶囊显示在 FAB 左侧。新增、导入及批量动作统一收进 Material 3 FAB 菜单；批量分类通过模态对话框应用，删除沿用现有确认；批量删除入口与确认使用危险色。主要操作保留文字并配有动作图标。接近已加载列表末尾时自动追加索引页；追加失败保留已有流水并提供重试。Regular/Wide 表格按旧 React Web 的选择、日期、账户、交易信息、分类、流水类型、金额、操作顺序和 5/12/15/24/12/11/13/8% 宽度比例排列；交易信息单元格先显示交易对方、再显示备注，Compact 行也让备注紧接交易对方。正式／草稿、未知值与待确认筛选沿用当前合同，读取版本变化清理旧选择；全局 FinanceTheme 将现有 token 映射到 `surfaceContainer*`，影响所有 Compose 平台使用 Material 3 默认容器色的组件；旧 React Web 不变。按钮与文本输入直接使用 Material 3，FinanceDataTable/Amount 等展示辅助函数保留，Registry 见 [`docs/design-system/registry.yaml`](design-system/registry.yaml)；详情卡片与关联流水折叠设计见 [`compose-cash-ledger-redesign/tasks.md`](../openspec/changes/archive/2026-10-21-compose-cash-ledger-redesign/tasks.md)，ChoicePicker、自动分页、表格列和 Web QA 见 [`compose-cash-import-account-mapping-redesign/tasks.md`](../openspec/changes/archive/2026-10-17-compose-cash-import-account-mapping-redesign/tasks.md) |
| F-04 | [`CashLedgerScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt)、[`CashCurrentRecordPanel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCurrentRecordPanel.kt)、[`CashLedgerViewModel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerViewModel.kt) | 同上 | 迁移中：[`当前详情`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCurrentRecordPanel.kt) 与 [`维护任务`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashRecordTaskViewModel.kt) 已接入共享真实入口；来源原文、草稿标量／分摊保存和明确转正式完成 Web 1440 px 合成账本真实 API 冒烟，普通保存保持草稿并保护成员身份与金额精度。来源采用、明确身份合并及关系维护保留既有真实 API 冒烟证据；关联 v6 走查修复另通过 22 项 Chromium 合成 API 流程和 421 项共享单测，验证范围见 [关联实施记录](../openspec/changes/cash-record-detail-relations-redesign/tasks.md)。旧编辑及任意关联路径已删除；普通记账／转账创建、普通金额编辑及完整双端编辑已通过真实 API Web 冒烟；公共整笔编辑使用真实修订并通过 Web 1440 px 真实 API 保存冒烟，来源字段及原文保持不变；构建、单测、独立审查及准确设备覆盖见 [`维护实施记录`](../openspec/changes/compose-cash-ledger-maintenance/tasks.md#当前审查与验证)。新建、公共属性整笔编辑、只读来源成员与删除的作用范围见 [操作流程](cash-ledger.md#5-操作流程及作用范围)；保留现有页面和历史截图不计新合同完成。 |
| F-05 | [`App.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/app/App.kt)、[`CashImportTaskScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportTaskScreen.kt)、[`CashImportPreparation.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportPreparation.kt)、[`CashImportItemsPagingSource.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportItemsPagingSource.kt)、[`CashImportReviewList.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportReviewList.kt)、[`CashImportPreviewPanel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportPreviewPanel.kt)、[`FinanceTheme.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/core/FinanceTheme.kt) | 同上 | 已接统一任务页、来源账户映射与币种补齐、待确认优先的主页同款流水行和序号分页、右侧滚动条、宽屏侧栏／窄屏详情、确认收据及错误诊断。真实 Chromium 导入套件 26 项通过；Wasm 共享测试、Android 编译和 Host 单测通过。当前离线合成演示等待用户验收；iOS 未验证，不计覆盖。 |
| F-06 | [`WorkspaceScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/WorkspaceScreens.kt)、[`AppRouting.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/core/AppRouting.kt) | 同上 | 邀请预览、接受、取消及内部 URI 路由已实现；未登录邀请沿用认证入口卡片，已登录邀请保留 Scaffold 标题与接受流程；本轮 Web/Native QA 见 `app-shell-entry-presentation/tasks.md`；HTTPS Universal Links 仍需域名和签名配置 |
| F-07 | [`CashCategoryScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryScreen.kt)、[`CashCategoryViewModel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryViewModel.kt)、[`CashCategoryUseCases.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/domain/CashCategoryUseCases.kt) | 同上 | 分类目录、层级编辑、搜索、排序和删除确认已实现；分类页面已迁移至 Domain Repository、用例和 StateFlow ViewModel，Wasm 单测通过；Web Chrome 与 Android 搜索/创建 QA 通过；iPad 11 已验无结果搜索和创建，其他操作仍需补验 |
| F-08 | [`InvestmentScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentScreens.kt)、[`InvestmentDisplayLogic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentDisplayLogic.kt) | 同上 | 持仓筛选、估值和表现已实现；估值详情为 Compact/Regular/Wide 共用、限宽 560 dp 的非全屏基础对话框；28 dp 圆角、四周 24 dp 留白；标题区自适应，标题/正文间距 16 dp、正文/操作间距 24 dp，左侧关闭入口为产品例外；正文滚动与关闭知悉操作分区清楚；Web E2E 与 Android compact/regular/wide 主题布局 QA 通过；iPad 11 已验估值/详情，iPad 13 wide 已验深色布局 |
| F-09 | [`InvestmentScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentEventScreen.kt)、[`InvestmentDisplayLogic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentDisplayLogic.kt) | 同上 | 事件筛选、分页和证据详情已实现；证据详情为 Compact/Regular/Wide 共用、限宽 560 dp 的非全屏基础对话框；28 dp 圆角、四周 24 dp 留白；标题区自适应，标题/正文间距 16 dp、正文/操作间距 24 dp，左侧关闭入口为产品例外；正文滚动与关闭知悉操作分区清楚；Web E2E 与 Android 详情/分页 QA 通过；iPad 11 已验详情与加载更多 |
| F-10 | [`WorkspaceScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/WorkspaceScreens.kt) | 同上 | 名称、成员、角色、邀请和删除流程已实现；Web E2E 与 Android 管理流程 QA 通过；iOS 已验改名、成员角色和邀请，iPhone/iPad 11 regular/iPad 13 wide 复核了邮箱布局，删除流程仍需补验 |

Compose Web 待解决缺陷：当前 `1.12.1` 关闭详情弹层后无障碍树不恢复，关联 [CMP-10623](https://youtrack.jetbrains.com/issue/CMP-10623)。保持当前依赖版本；Web 返回与无障碍 QA 保留未通过状态，Native 另行验证。

以下为旧客户端的历史验收证据，不证明新现金导入后端与 F-04／F-05 已联通。

浏览器用例位于 [`compose-access.e2e.ts`](../compose/qa/web/tests/compose-access.e2e.ts)、[`compose-pages.e2e.ts`](../compose/qa/web/tests/compose-pages.e2e.ts)、[`compose-live-demo.e2e.ts`](../compose/qa/web/tests/compose-live-demo.e2e.ts) 和 [`compose-locale.e2e.ts`](../compose/qa/web/tests/compose-locale.e2e.ts)。2026-10-17 Compose Web 生产预览完整运行 67 项 Playwright 用例并通过，包含账本三尺寸、分页和导入浅/夜间流程；诊断无浏览器 console error、failed request 或静态资源 HTTP 错误，Wasm MIME 为 `application/wasm`。详细截图、视口、命令与限制见 [`compose-cash-import-account-mapping-redesign/tasks.md`](../openspec/changes/archive/2026-10-17-compose-cash-import-account-mapping-redesign/tasks.md)。此 worktree 没有安装旧 React `web/` 的依赖；尝试 `npm ci` 时发现其 lockfile 中 Rollup 平台可选依赖缺失版本，故旧 React Vitest/build 未运行且没有改动其锁文件。该旧 React 页面仅作视觉参考，本次实现与 QA 均针对共享 Compose。Native 运行时验证不属于本 change 的用户授权范围；既有 Android/iOS 验收及待补矩阵记录保持原状。Web 字体使用同源 Noto Sans SC 资源；Compose Playwright fixture 检查 UI/API 请求，本地 registration E2E 另通过 FastAPI demo 实测，两者都不替代 FastAPI 后端契约测试。

原 `ALIGN-N-001` 至 `ALIGN-N-005` 是 Expo 时代的迁移历史记录，说明旧 Native 客户端曾缺少的功能。Compose 主线已补齐这些页面；这些条目保留用于审计旧端行为，不是当前 Compose 工作队列，也不要求恢复 Expo 页面。

## 统计口径

- 一个用户可以独立完成任务的页面或等价交互 surface 计为一个页面级功能。
- Web 的抽屉式记账详情与 Native 的独立记账页面属于同一个页面级功能，不因承载形态不同重复计数。
- 认证中的登录/注册模式、记账中的新建/查看/编辑模式、导入中的选择/映射/预览/关系/成功步骤，均属于各自页面级功能的内部状态或流程。
- 计入邀请页、工作区管理、分类管理和投资的持仓/事件页面；不把它们合并成一个泛化的“工作区”或“投资账本”页面。
- 不计入 `index.tsx` 重定向、`_layout.tsx` 路由守卫、导航 shell、加载态、错误态、确认弹窗和其他不能独立完成任务的容器。

## Legacy 页面级功能证据（迁移参考）

以下清单保留 React Web / Expo 页面入口和历史覆盖状态，帮助 Compose 实现核对已有用户行为。它不改变 Compose 主线的实现状态，也不把 legacy 代码加入根工作流。

| ID | 页面级功能 | 旧 React Web 实现 | Native 实现 | 覆盖状态 |
|----|------------|----------|-------------|----------|
| F-01 | 认证：登录 / 注册 | `Auth`，根入口的登录/注册模式；[`web/src/AccessApp.tsx`](../web/src/AccessApp.tsx) | `/(auth)/login`，登录/注册模式；[`mobile/src/app/(auth)/login.tsx`](<../mobile/src/app/(auth)/login.tsx>) | 双端已实现；Compose 共享认证视图按确认稿提供实时校验、注册确认密码及返回登录，详见 [`login-page-branding`](../openspec/changes/archive/2026-10-12-login-page-branding/) |
| F-02 | 工作区入口：选择 / 创建 / 进入失败恢复 | `Create`、工作区切换器和 `WorkspaceSelectionError`；[`web/src/AccessApp.tsx`](../web/src/AccessApp.tsx) | `/(app)/workspace`，选择、自动进入和创建；[`mobile/src/app/(app)/workspace.tsx`](<../mobile/src/app/(app)/workspace.tsx>) | 双端已实现 |
| F-03 | 收支账本：筛选、列表、空态、错误态和操作入口 | `/` 或 `/w/<workspace-id>/`；[`web/src/pages/CashLedgerPage.tsx`](../web/src/pages/CashLedgerPage.tsx) | `/(app)/ledger`；[`mobile/src/app/(app)/ledger.tsx`](<../mobile/src/app/(app)/ledger.tsx>) | Legacy 双端已实现；Compose 共享账本页面由 [`CashLedgerScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt) 与 [`CashLedgerScrollbar.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScrollbar.kt) 覆盖 Web、Android、iOS |
| F-04 | 收支流水：新建、查看、编辑、删除、凭证和关系 | Web 由收支账本打开 `RecordDrawer` / `EvidenceDetail`；[`web/src/components/RecordDrawer.tsx`](../web/src/components/RecordDrawer.tsx)、[`web/src/components/EvidenceDetail.tsx`](../web/src/components/EvidenceDetail.tsx) | `/(app)/record`，通过 `mode=create`、`mode=edit` 和流水标识承载新建、详情、编辑；[`mobile/src/app/(app)/record.tsx`](<../mobile/src/app/(app)/record.tsx>) | 双端已实现 |
| F-05 | 账单导入与关系审查：选择、扫描、映射、预览、配对、确认 | `/cash-import`；[`web/src/pages/CashImportPage.tsx`](../web/src/pages/CashImportPage.tsx) | `/(app)/import`；[`mobile/src/app/(app)/import.tsx`](<../mobile/src/app/(app)/import.tsx>) | 双端已实现 |
| F-06 | 工作区邀请：预览、登录后接受、取消和失效处理 | `?invite=<token>`；`Invite` 位于 [`web/src/AccessApp.tsx`](../web/src/AccessApp.tsx) | 无 Native 页面或入口 | 仅 Web 已实现 |
| F-07 | 收支分类管理：目录、搜索、新增、编辑、排序、删除影响确认 | `/cash-categories`；[`web/src/pages/CashCategoriesPage.tsx`](../web/src/pages/CashCategoriesPage.tsx) | 无 Native 页面或入口 | 仅 Web 已实现 |
| F-08 | 投资持仓：账户筛选、估值、表现和持仓详情 | `/investment-holdings`；`InvestmentLedgerPage(view="holdings")` 位于 [`web/src/pages/InvestmentLedgerPage.tsx`](../web/src/pages/InvestmentLedgerPage.tsx) | 无 Native 页面；Native 导航仅标记投资入口暂不可用；[`mobile/src/components/NativeShell.tsx`](../mobile/src/components/NativeShell.tsx) | 仅 Web 已实现 |
| F-09 | 投资事件：事件筛选、列表、分页和证据详情 | `/investment-events`；`InvestmentLedgerPage(view="events")` 位于 [`web/src/pages/InvestmentLedgerPage.tsx`](../web/src/pages/InvestmentLedgerPage.tsx) | 无 Native 页面；Native 导航仅标记投资入口暂不可用；[`mobile/src/components/NativeShell.tsx`](../mobile/src/components/NativeShell.tsx) | 仅 Web 已实现 |
| F-10 | 工作区管理：名称、成员角色、邀请链接和删除 | `/workspace-management`；`WorkspaceManagement` 位于 [`web/src/AccessApp.tsx`](../web/src/AccessApp.tsx) | 无 Native 页面或入口 | 仅 Web 已实现 |

共享 Compose 值选择字段由 [`FeatureComponents.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/FeatureComponents.kt) 中的 `ChoicePicker` 提供，并用于工作区、账本、流水、导入、分类和投资页面。该组件在 Web、Android 和 iOS 共用同一套锚定弹出菜单实现；命令型 FAB 与导航菜单不属于此组件。

## 跨端对齐 TODO

以下项目来自上表中“仅 Web 已实现”的页面级功能。完成前不能从功能地图中删除；应先补齐 Native 的真实页面和 API 流程，再把覆盖状态改为“双端已实现”。

| TODO | 待补齐端 | 对齐范围 | 完成条件 |
|------|----------|----------|----------|
| ALIGN-N-001 | Native | 工作区邀请预览、角色说明、登录后接受、取消、无效/过期错误和成功回到工作区 | Native 有可访问入口；邀请接受结果、角色和错误语义与 Web 一致；补充 Native 测试与设备 QA 证据 |
| ALIGN-N-002 | Native | 收支分类目录、搜索、层级编辑、新增、排序、删除影响确认和空/错误态 | Native 有分类管理页面；操作结果与 Web/API 一致；覆盖 `compact`、`regular`、`wide` 逻辑窗口 |
| ALIGN-N-003 | Native | 投资持仓列表、账户筛选、估值状态、表现范围、空/错误/加载态和详情 | Native 有持仓页面；金额、数量、币种和估值语义复用共享契约；完成相关设备和响应式验证 |
| ALIGN-N-004 | Native | 投资事件筛选、列表、分页、事件证据详情和错误恢复 | Native 有事件页面；列表字段、筛选值、详情和返回关系与 Web 一致；完成相关设备和响应式验证 |
| ALIGN-N-005 | Native | 工作区名称、成员列表、角色维护、邀请链接、权限限制、删除确认和删除后回退 | Native 有工作区管理页面；管理员/编辑者/查看者权限和危险操作语义与 Web 一致；完成设备 QA |

### 不作为 TODO 的跨端差异

- Web 使用侧边导航和 HTML 控件，Native 使用 rail、顶部菜单、原生选择器或系统文件选择器；这些属于已有 `presentation 契约` 中登记的平台差异。
- 旧 React Web 的 F-04 抽屉与旧 Expo Native 的独立页面是迁移历史差异；Compose Web、Android 和 iOS 共用的当前 F-04 模态面板合同见 [本次账本重构](../openspec/changes/archive/2026-10-21-compose-cash-ledger-redesign/design.md)。
- Native 登录页的调试地址控件是构建开关控制的 Native 专属调试能力，不是独立页面级功能。

## 维护规则

1. 所有功能改动（包括后端能力新增客户端入口，以及 Web、Native 或共享 presentation 层改动），必须在同一组改动中更新本功能地图。
2. 更新时至少检查页面总数、双端/单端覆盖状态、实现证据链接和对应的跨端对齐 TODO。
3. 新增页面级功能时，先加入清单并明确两端状态；只实现一端时必须同时新增对齐 TODO。
4. 补齐另一端后，只有在真实客户端入口、API 流程和相称验证完成后，才可将 TODO 标为完成或移除。
5. 纯文档、词表、功能地图、`AGENTS.md` 或 `README.md` 的修改不需要创建 OpenSpec 变更；涉及代码改动时仍遵守仓库的 OpenSpec 门禁。

## 盘点依据

- Web 页面入口：[`web/src/App.tsx`](../web/src/App.tsx)、[`web/src/AccessApp.tsx`](../web/src/AccessApp.tsx)、[`web/src/pages/`](../web/src/pages/)。
- Web 记账详情 surface：[`web/src/components/RecordDrawer.tsx`](../web/src/components/RecordDrawer.tsx)、[`web/src/components/EvidenceDetail.tsx`](../web/src/components/EvidenceDetail.tsx)。
- Native 页面入口：[`mobile/src/app/`](../mobile/src/app/)。
- Native 导航与暂不可用入口：[`mobile/src/components/NativeShell.tsx`](../mobile/src/components/NativeShell.tsx)。
- 跨端页面、状态和语义入口：[`packages/presentation/src/screen-contracts.ts`](../packages/presentation/src/screen-contracts.ts)、[`packages/presentation/src/semantic-ids.ts`](../packages/presentation/src/semantic-ids.ts)、[`packages/presentation/src/platform-differences.ts`](../packages/presentation/src/platform-differences.ts)。

影响实现或覆盖状态的变更必须更新本地图，并在对应 OpenSpec `tasks.md` 留下具体 QA 证据。平台特有控件可以不同，但值、标签、确认语义、错误处理和业务结果必须遵守共享行为合同。
