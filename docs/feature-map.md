# Compose 功能地图

> 盘点日期：2026-10-22。本文记录当前 Compose Multiplatform 页面级功能、共享实现证据和已知覆盖缺口。行为事实以 `openspec/specs/` 与代码为准。

Compose Web、Android、iOS 共用 `compose/shared` 中的页面、状态和业务流程。Web、Android、iOS 工程入口分别位于 `compose/webApp`、`compose/androidApp` 和 `compose/iosApp`。旧 `web/src`、`mobile/src` 与 TypeScript shared packages 不属于当前客户端主线。

现金导入重设计正在 [实施变更](../openspec/changes/cash-import-lifecycle-redesign/tasks.md) 中推进。当前已加入字段／草稿约束、来源对应及当前问题、平台与 [银行表格逐行解析](../src/ft/adapters/bank_statement_document.py)、仅解析内容的会话存储及 [后台过期清扫](../src/ft/application/parsed_import_cleanup.py)、命令收据、工作区同步、[逐文件会话服务](../src/ft/application/cash_import_context.py)、[关系角色／提交分组策略](../src/ft/domain/cash_relation_policy.py)、[最终关系图校验](../src/ft/domain/cash_relation_graph.py)、[当前关系与候选存储](../src/ft/adapters/relational/cash_relations.py) 及 [局部上下文校验服务](../src/ft/application/cash_relation_context.py)；关系表使用父锚点、可空组成项和规范候选，旧服务只映射同一结构，完整组加载及无写入校验已具备，内部业务计划及联合提交已接入，新公开导入及后续维护 HTTP 已接入，旧写入口关闭及客户端消费仍待切换。[账户 CAS 命令](../src/ft/application/account_commands.py) 已通过 [按 ID 的 HTTP 接口](../src/ft/web/account_routes.py) 独立生效，旧按名称写路径已移除。新导入接口已提供，旧导入写入口仍待关闭；下表 F-03／F-04／F-05 的 Web、Android、iOS 仍按既有实现及验证状态记录。草稿维护、统一预览列表、Compose 账户独立操作及相应三端 QA 未完成，不据此增加页面数或移除既有覆盖缺口。

普通流水编辑已接入 [类型变更的局部关系维护](../src/ft/application/cash_relation_type_updates.py)：按既有角色保留或解除确认关系，检查现金—投资的现金端，重建原组全部成员；普通字段与类型更新都会使依赖旧修订的候选失效。没有新增页面或关系入口；正式字段白名单的全部旧写路径关闭、维护预览及 Compose 演示仍属上述未完成变更。

身份待处理草稿已保留输入自身证明的可靠来源键，[来源合同](../tests/contract/test_cash_draft_source_identity.py) 覆盖跨文件重复、时间变化、当前问题和候选保留、共同草稿及保存失败回滚；只有文件局部证据的旧数据仍隔离，旧未知文件覆盖证据不替已知账户授信。本步没有新增 Web、Native 或共享 presentation 入口，页面数、覆盖状态和跨端 TODO 不变；公开维护接口及客户端仍在实施，内部独立确认和合并进度见下文。

[身份独立确认合同](../tests/contract/test_cash_independent_identity.py) 已接入内部无文件维护：只清除所选当前身份问题，保留实体及绑定，可与来源采用、补字段和明确转正式共用计划；无绑定旧微信保持隔离，绑定实例及证据变化会使旧确认失效。来源或认证页证据矛盾时不提供独立动作。公开维护接口和客户端仍未切换；本步 Web、Native、共享 presentation 没有新入口或页面覆盖变化。

[身份草稿合并预览](../tests/contract/test_cash_draft_merge_plan.py) 检查双方原始固定事实与人工冲突，保留目标组成项及未采用内容，冻结双向候选和完整关联组；身份候选详情展示当前修订而不更新旧证明。自动分类保护原组人工值，源人工分类冲突须明确统一。[合并联合执行合同](../tests/contract/test_cash_draft_merge_commit.py) 已接入内部维护命令：同一单元保存目标及整笔分类、迁移绑定和当前问题／候选／锚点，最后使源草稿退出；失败保留源内容，成功收据定位目标，投影或收据失败回滚整个命令。Web、Native、共享 presentation 的流程和入口尚未切换，页面数、覆盖及 TODO 保持不变。

内部 [已有配对维护预览](../tests/contract/test_cash_current_relation_plan.py) 从当前流水读取完整决定组及已有候选，不搜索新配对；初次预览保持成员原状态，明确选择才计算预计合并或解除，候选证明变化使旧选择失效。没有组成项的草稿保留延后和清理能力。[完整组更新合同](../tests/contract/test_cash_current_relation_commit.py) 已接入专用执行器，保留原关系身份、锚点及旧停用成员，按保存点隔离关系失败；投影或收据失败整体回滚。草稿组成项与明确候选清理存在依赖时共同保存，失效选择不会因撤回补丁自动恢复。公开接口和 Web、Native、共享 presentation 尚未切换，页面数、覆盖及 TODO 保持。

F-04 的 [删除执行与合同](../tests/contract/test_cash_current_relation_delete.py) 已按父流水清理当前引用，支持无组成项草稿及反向候选；清理未确认完整组，保留其余合法确认边。原记录、组成项及删除信息保留，当前来源绑定释放，删除后重导不会复活旧身份。删除预览包含父锚点，失败共同回滚余额、引用及当前展示。现有 Web、Native 删除入口共用该后端，客户端结构及页面数不变；实际客户端删除及新导入、草稿维护的跨端 QA 仍随统一流程验收，不能把后端合同当作浏览器／设备通过。

[草稿投影的领域合同](../src/ft/domain/cash_projection.py) 及 [43 迁移](../migrations/versions/20261021_43_cash_draft_projections.py) 已支持单例、可空展示、独立统计资格及待处理计数；正式财务字段仍必填。[当前待处理信息](../src/ft/adapters/relational/cash_attention.py) 已按相关流水有界加载，问题实例与修订纳入局部上下文，删除重建会使旧确认失效；[来源读取与主页查询](../src/ft/adapters/relational/web_queries.py) 已保留无组成项草稿、未知值及待确认可见性，按稳定排序位置分页，月汇总排除草稿；读取和附属结果使用同一快照。HTTP 已转发状态筛选并返回可空值，Compose 展示和完整草稿维护尚未切换，不据此开放草稿写入口或声称主页维护已完成。派生表升级后须先显式重建，保留已有正式值及引用。

[45 迁移](../migrations/versions/20261021_45_cash_projection_ownership.py) 限制派生结果的工作区归属及唯一活动集合；全量修复发布成功后清理旧派生副本，正式源记录保持。相关 [归属和回滚合同](../tests/contract/test_cash_projection_ownership.py) 覆盖跨工作区写入、异常迁移与旧会话缓存，不新增页面或改变客户端覆盖状态。

[46 迁移](../migrations/versions/20261021_46_cash_instance_identity.py) 保持流水、支付组成项及当前关系删除后新建的实例身份不复用；[身份合同](../tests/contract/test_cash_instance_identity.py) 及 [迁移合同](../tests/contract/test_cash_instance_migration.py) 验证旧确认失效、当前引用与唯一约束保留及异常回滚。该存储保护没有新增客户端入口，统一导入及草稿维护的三端缺口保持。

F-03／F-04 的整笔分类及明确备注保存已在后端保护同值和清空，自动分类同步保留成员已有人工选择，F-07 删除分类同步保护空值。业务隐藏但仍待处理的项可按当前列表范围维护，已删除来源仍被排除；[分类保护合同](../tests/contract/test_cash_manual_classification.py) 覆盖传播、候选失效及事务回滚。Web、Native、共享 presentation 本步没有页面、文案或操作结构改动，页面数与跨端覆盖 TODO 保持；新导入及完整草稿维护仍待上述统一流程切换。

[现金余额增量](../src/ft/application/cash_balances.py) 已统一排除草稿和删除项，内部行映射保留未知值；[双后端余额合同](../tests/contract/test_cash_draft_balances.py) 覆盖删除、转正式增量、回滚及合法金额精度。旧管理报表的收支和资金流复用当前正式投影及同一读取快照，[报表合同](../tests/contract/test_cash_current_finance_report.py) 覆盖两个生产组合入口、根月份、删除边界及未就绪错误。当前 Compose 没有调用该旧报表接口，三端页面数和入口状态不变；新草稿补全及关系维护仍待统一接口接入。

全额退款及全额平台退回的 [领域规则](../src/ft/domain/cash_projection.py) 和 [48 迁移](../migrations/versions/20261021_48_cash_full_refund_visibility.py) 已保留净额 `0` 的正式列表项，没有待处理事项时也不隐藏；已有显示缓存同步转换，原流水及关系不变。[双后端合同](../tests/contract/test_cash_full_refund_visibility.py) 覆盖版本、引用、升级／降级和失败回滚。F-03／F-05 的新预览与三端实际展示验收仍在上述变更中推进，页面数和跨端缺口不变。

## 页面级功能

微信默认来源、文件多账号设置命令及旧来源读取已接入 [后端来源输入](../src/ft/application/cash_import_source_rows.py)；F-05 的文件设置入口仍待 Compose 与新会话 API 切换，不新增独立来源管理页面。[账户建议及映射发布](../src/ft/application/cash_import_account_mappings.py) 按来源范围隔离，只记住实际保存成功且有效的整组选择；单笔改账户不改变以后默认。该后端小步尚未增加新客户端入口，Web、Native 和共享 presentation 的接入及实际 QA 仍待完成。

F-05 的 [新会话 HTTP 入口](../src/ft/web/cash_import_routes.py) 已支持逐文件上传、密码重传、来源设置、移除、取消和安全会话读取；[协议与资源边界](../src/ft/web/cash_import_protocol.py) 不保留原文件，已提交结果优先按收据恢复。[计划及结果 HTTP](../src/ft/web/cash_plan_routes.py) 接入预计列表／详情、完整计划确认、持久收据与结果分页、未知结果核实和失败项重试会话。当前尚未接入 Compose，Web、Android、iOS 仍使用原流程，不据此增加共享页面数或移除三端 QA 缺口。

F-04 的 [当前维护 HTTP 合同](../src/ft/web/cash_maintenance_protocol.py) 已接入当前详情、幂等草稿保存、无文件维护计划及一次确认，替换单条详情的旧固定字段结构；正式编辑、转正式、当前问题及完整配对组复用后端统一规则。收据与读取时当前详情分别返回，草稿补齐后普通保存仍不转正式。[当前结果详情及稳定流水定位](../src/ft/adapters/relational/web_queries.py) 已接入公开读取，在同一快照返回展示、成员和安全问题，合并／拆分后读取最新结果；退出当前集的标识不保留历史映射。旧写入口关闭和 Compose 消费仍待后续完成；F-04／F-05 的页面数、三端实现状态和 QA 缺口保持。

独立的 [现金—投资资金关联](../src/ft/application/cash_investment_funding_relations.py) 在扫描和确认时只选择正式现金流水，即使草稿财务字段已完整也不占用投资端点；扫描及决定与删除、流水维护共用工作区同步边界。投资能力及其入口保留，不随旧现金关系流程一起关闭；本步无新增客户端页面或操作，三端覆盖及 QA 缺口不变。

多文件共同来源规划在后端按等价集合合并重叠出现，逐来源行保留结果及不确定性；F-05 的旧入口关闭和共享列表尚未完成，页面数量及跨端 TODO 保持不变。当前投影维护已接入完整组成项分配证明及旧、新关系闭包，解除关系后原组的其余流水也会更新；相关 [双后端合同](../tests/contract/test_cash_projection_graph_reads.py) 不替代客户端草稿维护和导入验收。
已解析行的 [资金账户桥接](../src/ft/application/cash_import_funding.py) 准备来源账户组和支付组成项，缺映射／分摊保留已知字段，不创建账户；共享边界校验转正式的同向分摊并完整读取建行附言。[单行来源解释](../src/ft/adapters/cash_statement_evidence.py) 与解析行一起暂存，保留订单、状态及银行退款线索，不使用生成备注或保存跨行配对结果。桥接已供内部业务计划及联合提交使用，F-05 页面仍待切换。
工行 PDF 迁入解析上下文时保留信用卡行级本方身份与借记卡文件级身份；同次导出的连续页面在后端自动核对，重复页与矛盾页按来源结果处理。当前 F-05 的新 PDF／密码重传入口随统一会话切换验收，不据此标记客户端覆盖完成。

跨会话的 [来源规划](../src/ft/application/cash_import_source_plan.py) 已组合当前活动绑定及 [认证空页](../src/ft/adapters/relational/cash_source_documents.py)，包含本次命中的旧候选导出；[合同测试](../tests/contract/test_cash_source_document_storage.py) 覆盖零行、命名空间、双向冲突与降级保护。预览不写业务数据；内部联合提交已保存及重验相关证据，公开 HTTP 已接入，旧写入口关闭、统一客户端列表与三端 QA 仍待完成，不增加页面或独立关系入口。

内部 [计划发布层](../src/ft/application/cash_import_plans.py) 已支持会话修订比较、不可变替换、投影项与明细分页、详情公开字段及安全问题；[发布合同](../tests/contract/test_cash_import_plan_publication.py) 使用合成业务结果验证边界。真实业务构建器和内部联合提交已接入，旧写入口及 Compose 尚未完成切换，不据此声明 F-05 新流程可用或提高三端覆盖。

[逐流水操作规划](../src/ft/application/cash_import_operations.py) 已组合真实来源、账户映射和统一字段规则，区分新增、重复、草稿、更新与跳过；明确补齐后重算账户及完整性，保留人工备注、分类和当前组成项身份。[业务计划构建器](../src/ft/application/cash_import_business_plan.py) 已生成唯一预计流水列表、完整关系组和冻结详情；配对错误独立返回，未确认关系保持分列，净额为零的全额退款继续可见。内部联合提交已消费该计划，公开 HTTP 已接入，旧写入口关闭和客户端消费仍待完成；三端页面覆盖状态及页面数不变。

内部账户重验按最终父流水及全部支付组成项使用的稳定账户和所需币种检查；名称、纯修订及无关币种变化不使有效选择失效，失去资金资格只跳过相关流水。普通账户编辑和退出补偿的并发约束保持；客户端流程仍随新 API 切换验收。

[联合提交命令](../src/ft/application/cash_import_commit.py) 已接入内部计划：逐流水保存点和完整关联组分别隔离，成功值才更新余额与相关列表，同事务保存收据和分页结果；重复提交、并发完全重复、草稿组成项替换及关系失败均有合同测试。内部 [失败项重试](../src/ft/application/cash_import_retry.py) 通过收据派生必要来源行，继承原到期时间、重验剩余次数，并在成功后撤销祖先及其他副本的正文访问。旧写入口及 Compose 尚未完成切换，F-03／F-05 的页面总数、三端状态及对齐 TODO 不变。

内部结果核实命令使用两后端的非阻塞工作区锁：在途写入返回仍待核实，锁释放后以持久收据和计划状态确定结果，连接故障不误报未提交。普通写入等待规则保持；公开核实 API 已接入，Web／Native 的恢复操作仍随统一流程交付，没有新增页面。

内部 [流水维护服务](../src/ft/application/cash_record_maintenance.py) 已通过 [共享写入器](../src/ft/application/cash_record_writer.py) 读取／保存库中草稿，无需原文件；普通保存保留草稿状态、来源及关系事项，不参与统计，收据与刷新后的当前详情分别返回。F-04／F-05 的新维护 HTTP 已接入，旧写入口关闭与三端入口仍待切换，页面数及跨端 TODO 保持。

[共享来源派生](../src/ft/adapters/cash_statement_effects.py) 已覆盖首次归一、导入人工补充和草稿来源行维护：无人工保护的备注随原文同步，金额补齐重算依赖方向的提示，失效解释清空；来源本方身份、已确认关系和绑定保持当前决定。F-04／F-05 的新维护 HTTP 已接入，共享 Compose 尚未切换，不据此提高三端覆盖或增加页面。

内部 [无文件维护计划发布层](../src/ft/application/cash_mutation_plans.py) 已接入独立编辑上下文、原到期时间、计划替换、冻结详情分页及响应丢失后的当前计划读取；[发布合同](../tests/contract/test_cash_mutation_plan_publication.py) 验证权限、消费及只读边界。[实际维护规划](../src/ft/application/cash_mutation_business_plan.py) 使用 [共享当前操作准备](../src/ft/application/cash_record_operations.py) 和导入同类预计列表，支持正式编辑、草稿转正式、整笔分类、类型拆分及保留当前问题，正式普通编辑不寻找新配对。[维护联合命令](../src/ft/application/cash_mutation_commit.py) 复用 [事务内写入](../src/ft/application/cash_plan_execution.py)，核对局部当前依赖，按整笔流水与完整配对组保存，并提供永久消费收据及结果核实。当前问题／既有关系的明确维护动作及 HTTP 已接入，F-04／F-05 的共享 Compose 入口仍待切换，不增加页面数或提高三端覆盖状态。

内部 [来源问题决定](../src/ft/application/cash_source_issue_resolutions.py) 已将采用／保留、多项问题及字段补充接入同一维护计划；草稿可明确同次转正式，单纯事项处理不制造父字段更新。[来源处理合同](../tests/contract/test_cash_source_issue_resolution.py) 覆盖人工保护、当前实例／正文并发、组成项准入及回滚。身份独立／合并及既有关系明确维护均已接入内部命令；F-04／F-05 的新维护 HTTP 已接入，三端入口仍待切换，页面数和跨端 TODO 保持。

解析暂存已区分普通导入和私有重试资料，并支持继承原到期时间；[来源子集合同](../tests/contract/test_cash_source_document_storage.py) 防止省略行被认作空页或完整导出。普通文件／计划入口拒绝私有及未授权重试上下文；收据授权派生与次数证明已接入上述内部重试命令，公共接口及客户端重试操作尚未切换，三端状态及页面数保持。

| ID | 页面级功能 | Compose 共享实现证据 | 当前实现 |
|---|---|---|---|
| F-01 | 登录与注册 | [`AccessScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt)、[`AuthResponsiveShell.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AuthResponsiveShell.kt) | 登录、注册与认证状态恢复 |
| F-02 | 工作区选择与创建 | [`App.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/app/App.kt)、[`AccessScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt) | 选择、创建、进入失败恢复 |
| F-03 | 收支账本 | [`CashLedgerScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt) | 搜索、筛选、分页、流水列表和操作入口；详细筛选弹窗/底部表单左上提供关闭入口 |
| F-04 | 现金流水 | [`CashLedgerDetails.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerDetails.kt)、[`CashLedgerViewModel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerViewModel.kt) | 新建、详情、编辑、删除及关系查看；详情/编辑共享 Compact 全屏任务与 Regular/Wide 末端贴边的全高模态 Side Sheet，使用 `surfaceContainerLow`、Large shape、零 elevation；详情主操作与编辑/关联任务操作固定在底部，非 AlertDialog 任务左上关闭 |
| F-05 | 现金账单导入 | [`CashImportScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportScreen.kt)、[`ImportWorkflow.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/ImportWorkflow.kt) | 多文件扫描、账户映射、预览、关系审核与确认 |
| F-06 | 工作区邀请 | [`WorkspaceScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/WorkspaceScreens.kt)、[`AppRouting.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/core/AppRouting.kt) | 邀请预览、接受与取消 |
| F-07 | 现金分类管理 | [`CashCategoryScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryScreen.kt)、[`CashCategoryViewModel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryViewModel.kt) | 分类目录、搜索、编辑、排序和删除确认 |
| F-08 | 投资持仓 | [`InvestmentScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentScreens.kt)、[`InvestmentDisplayLogic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentDisplayLogic.kt) | 账户筛选、估值、表现和持仓详情 |
| F-09 | 投资事件 | [`InvestmentScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentScreens.kt)、[`InvestmentDisplayLogic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentDisplayLogic.kt) | 事件筛选、分页和证据详情 |
| F-10 | 工作区管理 | [`WorkspaceScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/WorkspaceScreens.kt) | 工作区资料、成员角色、邀请和删除 |

以上 10 项均有共享 Compose 页面实现，并由 Compose Web、Android、iOS 三个目标平台承载。共享代码存在不代表每个 target 的设备 QA 均已覆盖。

## 当前已知覆盖缺口

- F-05：iOS 文件选择器选中文件后交回应用、直至完成导入的端到端流程仍需实机验证。
- F-06：HTTPS Universal Links 需要部署域名与签名配置。
- F-07：iOS 分类管理的完整操作集仍需设备验证。
- F-10：iOS 工作区删除流程仍需设备验证。
- 尚无一份覆盖全部页面、平台与窗口尺寸的完整跨平台覆盖矩阵。

“Compose 共享实现覆盖”表示页面具备可运行的 Compose 入口，并接入真实 API；仅有后端接口、共享文案、原型、规格或 legacy 端导航均不算当前主线实现。

## Compose Multiplatform 主线

F-01 至 F-10 的共享页面、状态和 API 流程已放入 `compose/shared/src/commonMain`；Compose Web、Android、iOS target 使用同一套页面实现。旧 React UI 位于 `web/src`，保持原样并仅作为迁移参考；本功能地图末尾保留其历史实现证据。各 target 的已验收范围和待补 QA 记录在下表。跨页面入口壳与已登录标题层级已由 [`app-shell-entry-presentation`](../openspec/changes/archive/2026-10-12-app-shell-entry-presentation/) 对齐：F-01/F-02/F-06 共用登录式响应布局和居中卡片，F-03 至 F-10 由共享 `DestinationScaffold` 在顶栏显示唯一页面标题；桌面侧栏默认显示图标，鼠标进入侧栏自动展开、离开后收回，鼠标点击导航产生的焦点不会阻止收回，展开时点击收起控制可立即收回；键盘焦点仍可展开侧栏，头像悬停打开账号面板且在停留期间保持稳定，Enter/Space 可从键盘焦点打开面板，Escape 可关闭并将焦点返回头像；工作区切换、管理和退出集中在侧栏底部；侧栏交互与跨端 QA 证据记录于 [`navigation-rail-collapse`](../openspec/changes/archive/2026-10-13-navigation-rail-collapse/tasks.md) 和 [`account-menu-hover-stability`](../openspec/changes/archive/2026-10-13-account-menu-hover-stability/tasks.md)。

| ID | Compose 共享实现 | Web / Android / iOS 工程入口 | 当前状态 |
|----|------------------|------------------------------|----------|
| F-01 | [`AccessScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt)、[`AuthResponsiveShell.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AuthResponsiveShell.kt) | [`webApp`](../compose/webApp/build.gradle.kts)、[`androidApp`](../compose/androidApp/src/main/kotlin/com/finance/tracker/MainActivity.kt)、[`iosApp`](../compose/iosApp/iosApp/ContentView.swift) | 登录 / 注册使用 FinanceTheme 下的标准 Material 3 浮动标签、辅助文本和控件形状，并与工作区/未登录邀请共用响应式外壳和居中卡片；间距与控件迁移见 [`auth-page-spacing-shell`](../openspec/changes/auth-page-spacing-shell/)；本轮 Compose Web Chrome、Android device QA 记录见 `app-shell-entry-presentation/tasks.md` |
| F-02 | [`App.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/app/App.kt)、[`AccessScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt) | 同上 | 工作区选择、创建和恢复已实现；首次创建与已有工作区选择共用登录式容器、账户头部和居中卡片，保留列表选择、名称校验、请求错误、重试和创建成功导航；布局与 QA 证据见 [`app-shell-entry-presentation`](../openspec/changes/archive/2026-10-12-app-shell-entry-presentation/) |
| F-03 | [`CashLedgerScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt)、[`FinanceComponents.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/FinanceComponents.kt) | 同上 | 顶部搜索复用交易对方筛选，类型快捷项固定为全部、支出、收入和转账；详细筛选分为时间、金额、分类、账户、其他条件五组，金额组包含币种和金额范围，其他条件包含资金转移类型和合并状态；`COMPACT` 按组纵向排列，`REGULAR` / `WIDE` 在组内并排显示可配对字段。详细筛选在 `COMPACT` 使用 Bottom Sheet，`REGULAR` / `WIDE` 使用模态弹窗，滚动时标题和操作区固定；筛选区以独立 Material 3 前景卡固定在页面顶部，只有其下方的流水列表滚动；每个月份形成独立前景容器，月份及收入/支出汇总与记录位于同一容器，桌面月份容器重复展示对应表头。跨页记录仍逐笔懒加载，按相邻月份边界连续绘制。页面底色作为下层背景，筛选卡与月份容器之间留有间隔。Compact 默认隐藏复选框并以长按进入选择模式，Regular/Wide 保留行复选框，所有窗口移除全选入口。选中后 FAB 菜单自动展开并切换多选图标，已选数量以提示胶囊显示在 FAB 左侧。新增、导入及批量动作统一收进 Material 3 FAB 菜单；批量分类通过模态对话框应用，删除沿用现有确认；批量删除入口与确认使用危险色。主要操作保留文字并配有动作图标。接近已加载列表末尾时自动追加游标页；追加失败保留已有流水并提供重试。Regular/Wide 表格按旧 React Web 的选择、日期、账户、交易信息、分类、流水类型、金额、操作顺序和 5/12/15/24/12/11/13/8% 宽度比例排列；交易信息单元格先显示交易对方、再显示备注，Compact 行也让备注紧接交易对方。全局 FinanceTheme 将现有 token 映射到 `surfaceContainer*`，影响所有 Compose 平台使用 Material 3 默认容器色的组件；旧 React Web 不变。按钮与文本输入直接使用 Material 3，FinanceDataTable/Amount 等展示辅助函数保留，Registry 见 [`docs/design-system/registry.yaml`](design-system/registry.yaml)；详情卡片与关联流水折叠设计见 [`compose-cash-ledger-redesign/tasks.md`](../openspec/changes/compose-cash-ledger-redesign/tasks.md)，ChoicePicker、自动分页、表格列和 Web QA 见 [`compose-cash-import-account-mapping-redesign/tasks.md`](../openspec/changes/archive/2026-10-17-compose-cash-import-account-mapping-redesign/tasks.md) |
| F-04 | [`CashLedgerScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt)、[`CashLedgerDetails.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerDetails.kt)、[`CashLedgerViewModel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerViewModel.kt) | 同上 | 详情与编辑共用模态信息面板；`COMPACT` 均全屏，`REGULAR` / `WIDE` 均为右侧全高面板，编辑在详情面板内替换布局；新增关联是独立 M3 模态任务，使用 Compact 全屏、Regular/Wide 右侧全高面板，取消或完成返回详情；Wide 自定义定位面板距屏幕边缘 56 dp；详情、编辑和关联任务仅当前一个面板可见；删除、关系移除、放弃编辑、影响确认和错误知悉使用 M3 AlertDialog，出现时暂时隐藏所属面板，取消/知悉后恢复原任务与草稿，不嵌套活动模态；任务标题变化时更新模态语义子树；金额摘要保持在中性面板底色上，主要字段和关联流水分别使用 Material 3 ElevatedCard 默认前景角色 `surfaceContainerLow`；全局 FinanceTheme 从现有浅/深色 token 映射 `surfaceContainer*`，不在组件内重复指定容器颜色；关联流水卡默认收起、展开内容留在同一卡片内；详情正文滚动时编辑与更多操作固定在面板底部；编辑、关联、保存和删除按钮保留文字并显示动作图标，删除和解散关联使用危险色；模态内 Escape 先关闭最上层菜单，再按任务规则退出当前面板；详细行为和三尺寸 Web 证据见 [`compose-material3-dialog-conformance/tasks.md`](../openspec/changes/compose-material3-dialog-conformance/tasks.md) |
| F-05 | [`App.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/app/App.kt)、[`CashImportScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportScreen.kt)、[`CashLedgerScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt)、[`SecondaryPageContainer.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/SecondaryPageContainer.kt)、[`FinanceTheme.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/core/FinanceTheme.kt)、[`ImportWorkflow.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/ImportWorkflow.kt)、[`cash_ledger.py`](../src/ft/application/cash_ledger.py)、[`relations.py`](../src/ft/application/relations.py) | 同上 | 文件选择、扫描、映射、预览、关系审查和确认已实现；待分摊流水保留在预览中，金额不完整时不进入关系匹配；跨来源退款只在对应关系唯一且金额精确相同或退款侧已镜像时替代系统自动银行退款，并先释放旧关系额度再保存新关系及取代链；人工决定保持不变；从收支账本打开，不占一级导航；Compact 全屏返回，Regular 按横向窗口等级使用贴右全高、占当前视口宽度 90% 的模态侧边面板，Wide 占 70%，正文独立滚动；各尺寸阶段标题栏左上都有关闭入口，所有阶段操作固定在底部且主要提交最右，关系配对阶段会实际提交导入；阶段切换同步更新模态语义标题和当前控件；未提交工作关闭前先确认；处理中关闭提示在现有任务容器中展示，不叠加额外信息弹窗；多阶段操作的确认按钮在处理时继续可用但防止重复请求，错误反馈在 Compact 使用 M3 AlertDialog，在 Regular/Wide 保留在当前侧板中；新账户名称为空时各尺寸均禁用“应用”；账户映射菜单项与已选控件展示名称、类型和币种；选择「创建新账户」后控件保留固定标签，并在创建行下方显示推导信息与修改入口；复用共享草稿时信息只显示在已选控件，修改同步到全部引用行；共享 Compose ChoicePicker 使用 Material 3 锚定弹出菜单，Web 与 Native 共用实现，编辑新账户时 Escape 先收起账户类型菜单、下一次 Escape 才关闭编辑；待配对流水候选通过同一 ChoicePicker 呈现；导入各步骤继承 FinanceTheme 浅色/夜间模式，主要操作按钮保留文字并显示图标，账本主页导入 FAB 使用收件箱图标；成功导入返回时账本自动刷新当前筛选下的流水、汇总及账户/分类引用；批量扫描、密码重试和关闭清理共用导入会话；无 API 后端演示提供内置合成账单并复用同一流程。相关设计验收历史见 [`cash-import-candidate-overlay`](../openspec/changes/archive/2026-10-14-cash-import-candidate-overlay/tasks.md)；当前账户映射、导入主题与返回刷新证据见 [`compose-cash-import-account-mapping-redesign/tasks.md`](../openspec/changes/archive/2026-10-17-compose-cash-import-account-mapping-redesign/tasks.md)。 |
| F-06 | [`WorkspaceScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/WorkspaceScreens.kt)、[`AppRouting.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/core/AppRouting.kt) | 同上 | 邀请预览、接受、取消及内部 URI 路由已实现；未登录邀请沿用认证入口卡片，已登录邀请保留 Scaffold 标题与接受流程；本轮 Web/Native QA 见 `app-shell-entry-presentation/tasks.md`；HTTPS Universal Links 仍需域名和签名配置 |
| F-07 | [`CashCategoryScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryScreen.kt)、[`CashCategoryViewModel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryViewModel.kt)、[`CashCategoryUseCases.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/domain/CashCategoryUseCases.kt) | 同上 | 分类目录、层级编辑、搜索、排序和删除确认已实现；分类页面已迁移至 Domain Repository、用例和 StateFlow ViewModel，Wasm 单测通过；Web Chrome 与 Android 搜索/创建 QA 通过；iPad 11 已验无结果搜索和创建，其他操作仍需补验 |
| F-08 | [`InvestmentScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentScreens.kt)、[`InvestmentDisplayLogic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentDisplayLogic.kt) | 同上 | 持仓筛选、估值和表现已实现；估值详情为 Compact/Regular/Wide 共用、限宽 560 dp 的基础对话框，标题/正文滚动/关闭知悉操作分区清楚；Web E2E 与 Android compact/regular/wide 主题布局 QA 通过；iPad 11 已验估值/详情，iPad 13 wide 已验深色布局 |
| F-09 | [`InvestmentScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentEventScreen.kt)、[`InvestmentDisplayLogic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentDisplayLogic.kt) | 同上 | 事件筛选、分页和证据详情已实现；证据详情为 Compact/Regular/Wide 共用、限宽 560 dp 的基础对话框，标题/正文滚动/关闭知悉操作分区清楚；Web E2E 与 Android 详情/分页 QA 通过；iPad 11 已验详情与加载更多 |
| F-10 | [`WorkspaceScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/WorkspaceScreens.kt) | 同上 | 名称、成员、角色、邀请和删除流程已实现；Web E2E 与 Android 管理流程 QA 通过；iOS 已验改名、成员角色和邀请，iPhone/iPad 11 regular/iPad 13 wide 复核了邮箱布局，删除流程仍需补验 |

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
| F-01 | 认证：登录 / 注册 | `Auth`，根入口的登录/注册模式；[`web/src/AccessApp.tsx`](../web/src/AccessApp.tsx) | `/(auth)/login`，登录/注册模式；[`mobile/src/app/(auth)/login.tsx`](<../mobile/src/app/(auth)/login.tsx>) | 双端已实现；Compose 共享认证视图按确认稿提供实时校验、注册确认密码及返回登录，详见 [`login-page-branding`](../openspec/changes/login-page-branding/) |
| F-02 | 工作区入口：选择 / 创建 / 进入失败恢复 | `Create`、工作区切换器和 `WorkspaceSelectionError`；[`web/src/AccessApp.tsx`](../web/src/AccessApp.tsx) | `/(app)/workspace`，选择、自动进入和创建；[`mobile/src/app/(app)/workspace.tsx`](<../mobile/src/app/(app)/workspace.tsx>) | 双端已实现 |
| F-03 | 收支账本：筛选、列表、空态、错误态和操作入口 | `/` 或 `/w/<workspace-id>/`；[`web/src/pages/CashLedgerPage.tsx`](../web/src/pages/CashLedgerPage.tsx) | `/(app)/ledger`；[`mobile/src/app/(app)/ledger.tsx`](<../mobile/src/app/(app)/ledger.tsx>) | 双端已实现 |
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
- 旧 React Web 的 F-04 抽屉与旧 Expo Native 的独立页面是迁移历史差异；Compose Web、Android 和 iOS 共用的当前 F-04 模态面板合同见 [本次账本重构](../openspec/changes/compose-cash-ledger-redesign/design.md)。
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
