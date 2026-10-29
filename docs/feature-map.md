# Compose 功能地图

> 盘点日期：2026-10-29。本文记录 Compose 页面级功能、实现证据、跨端覆盖和当前缺口；不保留逐步开发日志。行为合同见 [OpenSpec 导航](../openspec/specs/README.md)，变更验证记录见各自 `tasks.md`。

Compose Web、Android、iOS 共用 `compose/shared`；工程入口分别为 `compose/webApp`、`compose/androidApp`、`compose/iosApp`。

## 现金导入后端与客户端边界

流水模型、展示与维护流程见 [现金流水](cash-ledger.md)，账单入库过程见 [现金账单导入](cash-import-flow.md)。现金导入后端已完成开发及验收，旧公开写入口已关闭；Compose 已接入 F-03 当前状态列表、F-04 当前详情、草稿维护、公共整笔编辑、普通记账／转账创建和完整转账编辑；F-05 已接入当前会话和统一核对入口。账户准备按来源渠道及账号分组，微信组名为“微信 - 昵称”，支付宝组名为“支付宝 - 脱敏账号”；默认开启的跨渠道同名开关只共享目标选择，来源组仍分别显示，冲突时清空选择。微信交易使用固定 `source_scope_key` 和非空交易单号，不再要求来源名称，也不保存跨导入账户映射；密码重传显示向右箭头和“提交”。本次改动及验证状态见 [账户分组变更任务](../openspec/changes/archive/2026-10-29-cash-import-source-account-grouping/tasks.md)。共享入口由 Web、Android、iOS 共用；默认验证按 `AGENTS.md` 执行相称浏览器流程、原生编译和单测，不将未执行的设备 QA 记为通过。页面数没有增加。业务规则见 [规则与设计取舍](cash-import-redesign.md)，协议见 [接口与数据契约](cash-import-redesign-contracts.md)；既有后端及历史账单验收见 [现金导入归档](../openspec/changes/archive/2026-10-28-cash-import-lifecycle-redesign/tasks.md)。

| 能力 | 后端及共享基础的实现证据 | 客户端状态 |
|---|---|---|
| 多文件解析与临时内容 | [会话服务](../src/ft/application/cash_import_context.py)、[会话 HTTP](../src/ft/web/cash_import_routes.py)、[解析及清扫配置](cash-import-r2-staging.md)；一次解析，不留原文件，单文件错误可继续 | 已接共享准备页、逐文件重选与密码重传；微信来源名称设置入口及 `source_scope_label` 会话字段已移除。密码提交和文件分组回归见本次变更任务。 |
| 来源对应与资金账户 | [来源规划](../src/ft/application/cash_import_source_plan.py)、[资金桥接](../src/ft/application/cash_import_funding.py)、[账户映射](../src/ft/application/cash_import_account_mappings.py)；微信固定范围加必有交易号，其他来源仍按来源账户隔离 | 微信昵称、支付宝脱敏账号仅作本次分组；同账号资金方式共组，不同账号分开。跨渠道同名组默认共享本次目标账户选择，选择冲突清空，关闭开关后独立选择。账户新建、币种补齐和预览行为沿用当前合同。 |
| 统一预览与一次确认 | [业务计划](../src/ft/application/cash_import_business_plan.py)、[联合提交](../src/ft/application/cash_import_commit.py)、[计划 HTTP](../src/ft/web/cash_plan_routes.py)；不可变计划提供按筛选的准确总数及序号分页 | 已接待确认优先、零待确认时默认全部的只读列表；复用主页流水行、Paging3 连续加载与右侧序号滚动条，详情在宽屏侧滑、小屏切入并在返回时保留列表位置和筛选；整份确认和实际收据摘要可演示。 |
| 结果及失败恢复 | [失败项原因](../src/ft/application/cash_import_issues.py)、[命令收据](../src/ft/adapters/relational/cash_mutations.py)、[旧收据迁移](../migrations/versions/20261023_49_unify_cash_import_receipts.py)；实际结果分页、未知核实、限时失败补处理 | 已接结果摘要与统一错误；实际草稿摘要及原命令未知核实均有 Web 证据，不提供逐项失败处理入口 |
| 草稿及正式流水维护 | [当前详情／草稿保存](../src/ft/application/cash_record_maintenance.py)、[维护规划](../src/ft/application/cash_mutation_business_plan.py)、[维护提交](../src/ft/application/cash_mutation_commit.py)；创建方式权限、稳定公共属性整笔编辑、来源问题及身份合并；原子草稿仅联动实际财务修改，保留来源明确组成项 | F-04 已接当前详情、普通草稿保存、分摊编辑、明确转正式和只读账单原文；公共属性整笔编辑已接入并通过真实 API 冒烟；来源采用和明确身份合并已通过真实 API 冒烟，演示验收待完成 |
| 关系及当前展示 | [最终图校验](../src/ft/domain/cash_relation_graph.py)、[稳定对象](../src/ft/adapters/relational/cash_entries.py)、[当前查询](../src/ft/adapters/relational/web_queries.py)；待确认不合并，人工冲突延后、解除明确继承，只差量更新受影响闭包，全额退款净额 `0` 可见；列表直接返回根流水 `transaction_id`，来源编号保持独立 | 正式／草稿列表、未知值和待确认筛选已接共享入口；关系维护已展示完整候选成员，选择失效须重新核对；完整组、公共冲突及解除继承已通过真实 API 冒烟；旧任意关联写入已移除，不新增关系一级入口 |
| 统计与删除 | [余额](../src/ft/application/cash_balances.py)、[投影](../src/ft/application/cash_projections.py)、[身份迁移](../migrations/versions/20261021_46_cash_instance_identity.py)；草稿不入金额统计，删除清理当前引用并释放来源绑定 | 现有删除入口及新草稿操作须随当前合同统一验收 |
| 独立账户 | [账户命令](../src/ft/application/account_commands.py)、[账户 HTTP](../src/ft/web/account_routes.py)；按 ID、修订及幂等键立即保存，账户类型创建后固定 | 已接独立账户表单与即时保存；实际新建后取消导入再撤销账户已通过 Web 实测，未知账户及编辑分支仍待最终验收 |
| 客户端基础 | [当前领域合同](../compose/shared/src/commonMain/kotlin/com/finance/tracker/domain/CashImportLifecycle.kt)、[任务编排](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportTaskViewModel.kt)、[账户编排](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/ImportAccountViewModel.kt)、[最小恢复存储](../compose/shared/src/commonMain/kotlin/com/finance/tracker/core/ImportRecoveryStore.kt) | 任务与账户 VM、恢复存储已接真实 App／Graph 入口；Web 已有主流程证据，Android／iOS 共用入口，按默认编译／单测验证；不据共享代码宣称设备 QA 通过 |
| Agent 入口 | [导入技能](../.agents/skills/cash-statement-import/SKILL.md) 和 [API 参考](../.agents/skills/cash-statement-import/references/api-contract.md) 已切换当前后端合同 | 同一后端的操作指引，不新增页面，不替代 Compose QA |

现金—投资资金关联仍是独立能力，后端只允许导入或缺证历史的正式现金流水参与，普通手工及手工转账禁止对外关联；投资事件、账户别名、手工新建、资金调拨和余额校准不因现金导入旧入口退役而移除。真实库迁移、旧外部原文件对象清理及部署未执行，条件见实施记录。

## 现金客户端实施模块

本节通过三个已归档的 Compose change 记录现金客户端范围、实现证据和验证缺口。三端共用页面实现；任务记录保留未执行的演示、专项验证和设备 QA 项，页面总数与已验收覆盖不变。

| 归档变更 | 功能边界 | 实现与验证状态 |
|---|---|---|
| [账单导入](../openspec/changes/archive/2026-10-28-compose-cash-import-workflow/proposal.md) | 文件／账户准备、统一核对、确认、真实结果、未知核实与退出恢复 | 共享全屏对话框已接入上述流程；用户已确认账户分组与密码按钮方向。来源账号分组、同名合并选择、Material 3 排版和验证状态见[变更任务记录](../openspec/changes/archive/2026-10-29-cash-import-source-account-grouping/tasks.md)。 |
| [账本与补处理](../openspec/changes/archive/2026-10-28-compose-cash-ledger-maintenance/proposal.md) | 当前列表／详情、草稿、来源问题、已保存关系组、分类与删除 | 当前列表／详情、状态筛选和草稿维护已接入；来源问题已接线；关系完整组、公共冲突及解除继承已通过真实 API 验收，最终演示待确认 |
| [记账与编辑](../openspec/changes/archive/2026-10-28-compose-cash-entry-editor/proposal.md) | 公共属性整笔编辑、普通手动记账、同入口转账及双端编辑 | 公共整笔编辑及创建方式权限已接入；普通记账、转账创建及模式输入保留已接入；完整转账在同表单编辑双端，缺少完整组能力时只改公共字段 |

三个归档变更分别记录账本维护、记账编辑和账单导入的 Compose 实现范围。当前行为以主规格和共享 Compose 页面为准；上表给出入口证据，归档任务文件保留实际验证命令、未覆盖项和风险。

## 页面级功能

| ID | 页面级功能 | Compose 共享实现证据 | 当前实现 |
|---|---|---|---|
| F-01 | 登录与注册 | [`AccessScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt)、[`AuthResponsiveShell.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AuthResponsiveShell.kt) | 登录、注册与认证状态恢复 |
| F-02 | 工作区选择与创建 | [`App.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/app/App.kt)、[`AccessScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt) | 选择、创建、进入失败恢复 |
| F-03 | 收支账本 | [`CashLedgerScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt)、[`CashLedgerScrollbar.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScrollbar.kt)、[`CashProjectionPagingSource.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashProjectionPagingSource.kt)、[`CashProjectionApi.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/data/CashProjectionApi.kt)、[`web_queries.py`](../src/ft/application/web_queries.py)、[`web_queries.py`](../src/ft/adapters/relational/web_queries.py)、[`routes.py`](../src/ft/web/routes.py) | 搜索、筛选、绝对序号分页、流水列表和操作入口；按当前筛选结果准确计数并以稳定顺序读取指定流水位置，返回月份序号区间；完整刷新立即隐藏旧流水和汇总，按窗口显示骨架并支持列表顶部下拉刷新，分页追加保留现有流水；Compact 窄滚动条在滚动时显示、停止 2 秒后渐隐，轨道和滑块贴列表右边缘并覆盖列表、不挤占宽度，交互热区为 40 dp；Regular/Wide 月份表格容器及吸顶栏右边缘与筛选卡对齐，行内容左右内缩 16 dp；纤细滚动条贴近行内容右边界并常驻显示，40 dp 交互热区覆盖列表且不缩窄内容；月份摘要及桌面表格列标题在筛选卡下方固定并随下月到达顶部切换；FAB 覆盖列表且末行可达；Regular/Wide 行内容内缩 16 dp、分隔线全宽；详细筛选关闭入口在左；Regular/Wide Basic Dialog 宽 280–560 dp、28 dp 圆角、动态高度，标题/正文间距 16 dp、正文/操作间距 24 dp，操作组靠右，Compact Bottom Sheet 外框与行为不变
| F-04 | 现金流水 | [`CashCurrentRecordPanel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCurrentRecordPanel.kt)、[`CashRecordSummary.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashRecordSummary.kt)、[`CashLedgerViewModel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerViewModel.kt) | 迁移中：已使用 `CashCurrentRecordPanel` 和维护任务状态接入当前详情、草稿及分摊保存、明确转正式和只读来源；详情摘要与导入预览共用平面字段；当前详情按摘要、关联流水、账单原文排列，后两个区块直接展示；关联建议可接受完整方案或拒绝整组，正文通过“关联流水”发起搜索选择，单方案默认选中、建议搜索锁定类型／子类型；联合方案改选只可搜索、暂不允许提交；冲突后可返回选择，拒绝／移除在原确认页完成一次最终确认；公共整笔编辑、普通记账／转账创建及完整双端编辑已接入。关联 v6 及走查修复已通过 22 项 Chromium 合成 API 流程、421 项共享单测和相称跨端编译；联合方案改选暂不允许提交。来源／身份问题保留既有真实 API 冒烟证据。旧直接编辑／新增关联页面和客户端 API 已移除，当前流程见 [现金流水](cash-ledger.md) |
| F-05 | 现金账单导入 | [`CashImportTaskScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportTaskScreen.kt)、[`CashImportTaskViewModel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportTaskViewModel.kt)、[`CashImportItemsPagingSource.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportItemsPagingSource.kt)、[`CashImportReviewList.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportReviewList.kt)、[`CashImportPreviewPanel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportPreviewPanel.kt)、[`CashImportDiagnostic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportDiagnostic.kt) | 统一任务页已接入按渠道和账号分组、默认开启的跨渠道同名合并、密码提交、账户币种补齐、待确认优先流水列表、确认收据及错误诊断。分组与密码操作的验证范围及未完成项见[变更任务](../openspec/changes/archive/2026-10-29-cash-import-source-account-grouping/tasks.md)；不据共享代码宣称设备 QA 通过。|
| F-06 | 工作区邀请 | [`WorkspaceScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/WorkspaceScreens.kt)、[`AppRouting.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/core/AppRouting.kt) | 邀请预览、接受与取消 |
| F-07 | 收支分类管理（管理页内） | [`CashCategoryScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryScreen.kt)、[`CashCategoryViewModel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryViewModel.kt) | 管理页内嵌分类目录、搜索、排序和删除确认；Compact 全屏、Regular/Wide 对话框编辑；旧分类链接定位管理页分类分组 |
| F-08 | 投资持仓 | [`InvestmentScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentScreens.kt)、[`InvestmentDisplayLogic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentDisplayLogic.kt) | 账户筛选、估值、表现和持仓详情；Basic Dialog 宽 280–560 dp、28 dp 圆角、四周 24 dp 留白；标题区自适应，标题/正文间距 16 dp、正文/操作间距 24 dp，左侧关闭入口为产品例外 |
| F-09 | 投资事件 | [`InvestmentScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentScreens.kt)、[`InvestmentDisplayLogic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentDisplayLogic.kt) | 事件筛选、分页和证据详情；Basic Dialog 宽 280–560 dp、28 dp 圆角、四周 24 dp 留白；标题区自适应，标题/正文间距 16 dp、正文/操作间距 24 dp，左侧关闭入口为产品例外 |
| F-10 | 管理（工作区与收支分类） | [`WorkspaceScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/WorkspaceScreens.kt) | 以 M3 文字角色、平面分组和细分隔呈现；两级统一管理：工作区资料→基本信息/危险操作、访问权限→成员与权限/邀请成员、收支账本→收支分类；Regular/Wide 右侧目录、Compact Toolbar 全屏目录，共享两级同页定位与当前项，投资无占位；实现见 [`ManagementLayout.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/ManagementLayout.kt)、[`ManagementNavigation.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/ManagementNavigation.kt)，验证记录见 [`tasks.md`](../openspec/changes/archive/2026-10-21-unify-management-settings/tasks.md) |

以上 10 项按用户任务统计，F-07 嵌入 F-10 后仍保留独立分类任务。各项均有共享 Compose 页面代码，并由 Compose Web、Android、iOS 三个目标平台承载；F-05 账户分组与密码操作已有用户确认，相关最终验收证据见本次变更任务，不能据旧代码存在认定完整流程可用。页面数不因迁移增加，当前没有仅一端完成本次切换。共享代码存在不代表每个 target 的设备 QA 均已覆盖。

## 当前已知覆盖缺口

F-04 当前覆盖：导入流水自由搜索新增关联、按实际关系标注全部成员原文，以及在弹窗中完成关联操作，已接入 [共享详情](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashDetailRelations.kt) 及维护入口，影响 Web、Android、iOS。关联流水和账单原文均直接展示；单备选建议与已有关联流水共用完整基础信息布局，备注带标签；有效待确认关系展示无按钮的警示提示，接受／核对与拒绝在弹窗处理；添加关联在搜索前以单选分段按钮选择关系类型；有备选时先单选完整方案，点击“选择其他流水”切换搜索，可返回并保留原方案；无备选直接搜索；搜索单选一笔对侧流水并沿用建议类型。桌面复用表格，COMPACT 复用卡片。范围与当前验证缺口见 [详情关联实施记录](../openspec/changes/archive/2026-10-28-cash-record-detail-relations-redesign/tasks.md)，已完成代表视口反馈验证、非性能回归及受影响 SQLite／真实 PostgreSQL 契约；复杂浏览器保存与完整最终验收仍待完成，不计为已最终交付能力，页面数不变。

- F-04：当前详情、来源原文、草稿和分摊保存／转正式已通过代表视口真实 API 冒烟。F-05：用户已确认账户分组与密码操作方向；本次实现及验证状态见[变更任务](../openspec/changes/archive/2026-10-29-cash-import-source-account-grouping/tasks.md)。关闭 Web 弹层后的无障碍树更新及禁用状态语义缺陷仍保留；不升级 Compose。原生设备 QA 缺口登记在下方，未运行时不记为通过。

- F-07 / F-10：本次管理导航、目录和分类模态的 Web、Native 新验证记录于 [`tasks.md`](../openspec/changes/archive/2026-10-21-unify-management-settings/tasks.md)；Android 390 / 768 / 1440 dp 已通过目录、分类模态保存及旧链接定位；iOS 本轮仅共享编译，实际 iPhone/iPad 新布局仍待验收。

- F-05：iOS 文件选择器选中文件后交回应用、直至完成导入的端到端流程仍需实机验证。
- F-06：HTTPS Universal Links 需要部署域名与签名配置。
- F-07：iOS 分类管理的完整操作集仍需设备验证。
- F-10：iOS 工作区删除流程仍需设备验证。
- 尚无一份覆盖全部页面、平台与窗口尺寸的完整跨平台覆盖矩阵。

“Compose 共享实现覆盖”表示页面具备可运行的 Compose 入口，并接入真实 API；仅有后端接口、共享文案、原型、规格或不可达导航均不算当前主线实现。

## Compose Multiplatform 主线

F-01 至 F-10 的共享页面及既有调用代码位于 `compose/shared/src/commonMain`；Compose Web、Android、iOS target 使用同一套页面实现，其中 F-04 当前详情、草稿维护及公共整笔编辑已接入，转账创建及完整双端编辑已接入，F-05 当前协议和主流程已接入，用户确认的分组及密码操作验收见当前 change tasks。各 target 的已验收范围和待补 QA 记录在下表。跨页面入口壳与已登录标题层级已由 [`app-shell-entry-presentation`](../openspec/changes/archive/2026-10-12-app-shell-entry-presentation/) 对齐：F-01/F-02/F-06 共用登录式响应布局和居中卡片，F-03 至 F-10 由共享 `DestinationScaffold` 承载；F-05 以全屏对话框提供独立标题和关闭入口，其余任务在应用顶栏显示页面标题；桌面侧栏默认显示图标，鼠标进入侧栏自动展开、离开后收回，鼠标点击导航产生的焦点不会阻止收回，展开时点击收起控制可立即收回；键盘焦点仍可展开侧栏，头像悬停打开账号面板且在停留期间保持稳定，Enter/Space 可从键盘焦点打开面板，Escape 可关闭并将焦点返回头像；工作区切换、管理和退出集中在侧栏底部；侧栏交互与跨端 QA 证据记录于 [`navigation-rail-collapse`](../openspec/changes/archive/2026-10-13-navigation-rail-collapse/tasks.md) 和 [`account-menu-hover-stability`](../openspec/changes/archive/2026-10-13-account-menu-hover-stability/tasks.md)。

| ID | Compose 共享实现 | Web / Android / iOS 工程入口 | 当前状态 |
|----|------------------|------------------------------|----------|
| F-01 | [`AccessScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt)、[`AuthResponsiveShell.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AuthResponsiveShell.kt) | [`webApp`](../compose/webApp/build.gradle.kts)、[`androidApp`](../compose/androidApp/src/main/kotlin/com/finance/tracker/MainActivity.kt)、[`iosApp`](../compose/iosApp/iosApp/ContentView.swift) | 登录 / 注册使用 FinanceTheme 下的标准 Material 3 浮动标签、辅助文本和控件形状，并与工作区/未登录邀请共用响应式外壳和居中卡片；间距与控件迁移见 [`auth-page-spacing-shell`](../openspec/changes/archive/2026-10-12-auth-page-spacing-shell/)；本轮 Compose Web Chrome、Android device QA 记录见 `app-shell-entry-presentation/tasks.md` |
| F-02 | [`App.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/app/App.kt)、[`AccessScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt) | 同上 | 工作区选择、创建和恢复已实现；首次创建与已有工作区选择共用登录式容器、账户头部和居中卡片，保留列表选择、名称校验、请求错误、重试和创建成功导航；布局与 QA 证据见 [`app-shell-entry-presentation`](../openspec/changes/archive/2026-10-12-app-shell-entry-presentation/) |
| F-03 | [`CashLedgerScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt)、[`CashLedgerScrollbar.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScrollbar.kt)、[`CashProjectionPagingSource.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashProjectionPagingSource.kt)、[`CashProjectionApi.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/data/CashProjectionApi.kt)、[`FinanceComponents.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/FinanceComponents.kt) | 同上 | 顶部搜索复用交易对方筛选，类型快捷项固定为全部、支出、收入和转账；详细筛选分为时间、金额、分类、账户、其他条件五组，金额组包含币种和金额范围，其他条件包含资金转移类型和合并状态；`COMPACT` 按组纵向排列，`REGULAR` / `WIDE` 在组内并排显示可配对字段。详细筛选在 `COMPACT` 使用 Bottom Sheet，`REGULAR` / `WIDE` 使用非全屏 Dialog；Basic Dialog 宽 280–560 dp、28 dp 圆角、动态高度；标题/正文间距 16 dp、正文/操作间距 24 dp，操作组靠右且关闭入口在左；Compact Bottom Sheet 外框与行为保持不变；筛选区以独立 Material 3 前景卡固定在页面顶部，只有其下方的流水列表滚动；所有尺寸的 FAB 叠加在列表视口上，末条保留滚动空间以完整显示在按钮上方；每个月份形成独立前景容器，月份汇总在筛选卡下方的列表视口顶部固定并由下月替换，`REGULAR` / `WIDE` 的表头同步固定，`COMPACT` 只固定汇总；`REGULAR` / `WIDE` 月份容器及吸顶栏右边缘与筛选卡对齐，每行内容向内缩 16 dp 且分隔线保持全宽。跨页记录仍逐笔懒加载，按相邻月份边界连续绘制。页面底色作为下层背景，筛选卡与月份容器之间留有间隔。Compact 默认隐藏复选框并以长按进入选择模式，Regular/Wide 保留行复选框，所有窗口移除全选入口。选中后 FAB 菜单自动展开并切换多选图标，已选数量以提示胶囊显示在 FAB 左侧。新增、导入及批量动作统一收进 Material 3 FAB 菜单；批量分类通过模态对话框应用，删除沿用现有确认；批量删除入口与确认使用危险色。主要操作保留文字并配有动作图标。接近已加载列表末尾时自动追加索引页；追加失败保留已有流水并提供重试。Regular/Wide 表格按选择、日期、账户、交易信息、分类、流水类型、金额、操作顺序排列，列宽依次为 5%、12%、15%、24%、12%、11%、13% 和 8%；交易信息单元格先显示交易对方、再显示备注，Compact 行也让备注紧接交易对方。正式／草稿、未知值与待确认筛选沿用当前合同，读取版本变化清理旧选择；全局 FinanceTheme 将现有 token 映射到 `surfaceContainer*`，影响所有 Compose 平台使用 Material 3 默认容器色的组件；Web、Android、iOS 共用 Compose 实现。按钮与文本输入直接使用 Material 3，FinanceDataTable/Amount 等展示辅助函数保留，Registry 见 [`docs/design-system/registry.yaml`](design-system/registry.yaml)；详情卡片与关联流水折叠设计见 [`compose-cash-ledger-redesign/tasks.md`](../openspec/changes/archive/2026-10-21-compose-cash-ledger-redesign/tasks.md)，ChoicePicker、自动分页、表格列和 Web QA 见 [`compose-cash-import-account-mapping-redesign/tasks.md`](../openspec/changes/archive/2026-10-17-compose-cash-import-account-mapping-redesign/tasks.md) |
| F-04 | [`CashLedgerScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt)、[`CashCurrentRecordPanel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCurrentRecordPanel.kt)、[`CashLedgerViewModel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerViewModel.kt) | 同上 | 迁移中：[`当前详情`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCurrentRecordPanel.kt) 与 [`维护任务`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashRecordTaskViewModel.kt) 已接入共享真实入口；来源原文、草稿标量／分摊保存和明确转正式完成 Web 1440 px 合成账本真实 API 冒烟，普通保存保持草稿并保护成员身份与金额精度。来源采用、明确身份合并及关系维护保留既有真实 API 冒烟证据；关联 v6 走查修复另通过 22 项 Chromium 合成 API 流程和 421 项共享单测，验证范围见 [关联实施记录](../openspec/changes/archive/2026-10-28-cash-record-detail-relations-redesign/tasks.md)。旧编辑及任意关联路径已删除；普通记账／转账创建、普通金额编辑及完整双端编辑已通过真实 API Web 冒烟；公共整笔编辑使用真实修订并通过 Web 1440 px 真实 API 保存冒烟，来源字段及原文保持不变；构建、单测、独立审查及准确设备覆盖见 [`维护实施记录`](../openspec/changes/archive/2026-10-28-compose-cash-ledger-maintenance/tasks.md#当前审查与验证)。新建、公共属性整笔编辑、只读来源成员与删除的作用范围见 [操作流程](cash-ledger.md#5-操作流程及作用范围)；保留现有页面和历史截图不计新合同完成。 |
| F-05 | [`App.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/app/App.kt)、[`CashImportTaskScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportTaskScreen.kt)、[`CashImportPreparation.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportPreparation.kt)、[`CashImportItemsPagingSource.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportItemsPagingSource.kt)、[`CashImportReviewList.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportReviewList.kt)、[`CashImportPreviewPanel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportPreviewPanel.kt)、[`FinanceTheme.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/core/FinanceTheme.kt) | 同上 | 同一共享流程覆盖按渠道和账号分组的账户选择、默认开启的跨渠道同名合并、密码提交、币种补齐、只读流水核对和确认结果；Material 3 排版、验证范围和未完成项见[变更任务](../openspec/changes/archive/2026-10-29-cash-import-source-account-grouping/tasks.md)。未执行原生设备 QA。|
| F-06 | [`WorkspaceScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/WorkspaceScreens.kt)、[`AppRouting.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/core/AppRouting.kt) | 同上 | 邀请预览、接受、取消及内部 URI 路由已实现；未登录邀请沿用认证入口卡片，已登录邀请保留 Scaffold 标题与接受流程；本轮 Web/Native QA 见 `app-shell-entry-presentation/tasks.md`；HTTPS Universal Links 仍需域名和签名配置 |
| F-07 | [`CashCategoryScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryScreen.kt)、[`CashCategoryViewModel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryViewModel.kt)、[`CashCategoryUseCases.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/domain/CashCategoryUseCases.kt) | 同上 | 分类目录、层级编辑、搜索、排序和删除确认已实现；分类页面已迁移至 Domain Repository、用例和 StateFlow ViewModel，Wasm 单测通过；Web Chrome 与 Android 搜索/创建 QA 通过；iPad 11 已验无结果搜索和创建，其他操作仍需补验 |
| F-08 | [`InvestmentScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentScreens.kt)、[`InvestmentDisplayLogic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentDisplayLogic.kt) | 同上 | 持仓筛选、估值和表现已实现；估值详情为 Compact/Regular/Wide 共用、限宽 560 dp 的非全屏基础对话框；28 dp 圆角、四周 24 dp 留白；标题区自适应，标题/正文间距 16 dp、正文/操作间距 24 dp，左侧关闭入口为产品例外；正文滚动与关闭知悉操作分区清楚；Web E2E 与 Android compact/regular/wide 主题布局 QA 通过；iPad 11 已验估值/详情，iPad 13 wide 已验深色布局 |
| F-09 | [`InvestmentScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentEventScreen.kt)、[`InvestmentDisplayLogic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentDisplayLogic.kt) | 同上 | 事件筛选、分页和证据详情已实现；证据详情为 Compact/Regular/Wide 共用、限宽 560 dp 的非全屏基础对话框；28 dp 圆角、四周 24 dp 留白；标题区自适应，标题/正文间距 16 dp、正文/操作间距 24 dp，左侧关闭入口为产品例外；正文滚动与关闭知悉操作分区清楚；Web E2E 与 Android 详情/分页 QA 通过；iPad 11 已验详情与加载更多 |
| F-10 | [`WorkspaceScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/WorkspaceScreens.kt) | 同上 | 名称、成员、角色、邀请和删除流程已实现；Web E2E 与 Android 管理流程 QA 通过；iOS 已验改名、成员角色和邀请，iPhone/iPad 11 regular/iPad 13 wide 复核了邮箱布局，删除流程仍需补验 |

Compose Web 待解决缺陷：当前 `1.12.1` 关闭详情弹层后无障碍树不恢复，关联 [CMP-10623](https://youtrack.jetbrains.com/issue/CMP-10623)。保持当前依赖版本；Web 返回与无障碍 QA 保留未通过状态，Native 另行验证。

Compose Web 浏览器 QA 记录如下；历史验收结果不替代各功能当前记录的验收缺口。

浏览器用例位于 [`compose-access.e2e.ts`](../compose/qa/web/tests/compose-access.e2e.ts)、[`compose-pages.e2e.ts`](../compose/qa/web/tests/compose-pages.e2e.ts)、[`compose-live-demo.e2e.ts`](../compose/qa/web/tests/compose-live-demo.e2e.ts) 和 [`compose-locale.e2e.ts`](../compose/qa/web/tests/compose-locale.e2e.ts)。2026-10-17 Compose Web 生产预览完整运行 67 项 Playwright 用例并通过，包含账本三尺寸、分页和导入浅/夜间流程；诊断无浏览器 console error、failed request 或静态资源 HTTP 错误，Wasm MIME 为 `application/wasm`。详细截图、视口、命令与限制见 [`compose-cash-import-account-mapping-redesign/tasks.md`](../openspec/changes/archive/2026-10-17-compose-cash-import-account-mapping-redesign/tasks.md)。Web 字体使用同源 Noto Sans SC 资源；Compose Playwright fixture 检查 UI/API 请求，本地 registration E2E 另通过 FastAPI demo 实测，两者都不替代 FastAPI 后端契约测试。

## 统计口径

- 一个用户可以独立完成任务的页面或等价交互 surface 计为一个页面级功能。
- 同一业务任务在不同窗口或平台采用不同承载形态时，仍计为一个页面级功能。
- 认证中的登录/注册模式、记账中的新建/查看/编辑模式、导入中的选择/映射/预览/关系/成功步骤，均属于各自页面级功能的内部状态或流程。
- 计入邀请页、工作区管理、分类管理和投资的持仓/事件页面；不把它们合并成一个泛化的“工作区”或“投资账本”页面。
- 不计入重定向、路由守卫、导航壳、加载态、错误态、确认弹窗和其他不能独立完成任务的容器。

## 跨端呈现

Compose Web、Android 和 iOS 共用页面行为、文案、状态、语义与业务结果。导航、文件选择器和窗口呈现可以不同；差异及共同不变量以 [跨端展示规格](../openspec/specs/shared/cross-platform-presentation/spec.md) 和各业务主规格为准。

功能地图只记录当前 Compose target 的页面、实现证据和验收缺口。一个页面标为已实现，必须有真实页面入口和对应 API 流程；未完成的平台任务应登记为具体缺口，不以占位页或历史实现代替。

## 维护规则

1. 所有功能改动（包括后端能力新增客户端入口，以及 Web、Native 或共享 presentation 层改动），必须在同一组改动中更新本功能地图。
2. 更新时至少检查页面总数、Web/Android/iOS 覆盖状态、实现证据链接和对应的验收缺口。
3. 新增页面级功能时，先加入清单并明确两端状态；只实现一端时必须同时新增对齐 TODO。
4. 补齐某个 target 后，只有在真实页面入口、API 流程和相称验证完成后，才可将对应缺口标为完成或移除。
5. 纯文档、词表、功能地图、`AGENTS.md` 或 `README.md` 的修改不需要创建 OpenSpec 变更；涉及代码改动时仍遵守仓库的 OpenSpec 门禁。

## 盘点依据

- Compose 页面入口：[`App.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/app/App.kt)、[`CashLedgerScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt)、[`WorkspaceScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/WorkspaceScreens.kt)、[`InvestmentScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentScreens.kt)。
- Compose Web 目标和浏览器 QA：[`compose/webApp`](../compose/webApp/)、[`compose/qa/web/tests`](../compose/qa/web/tests/)。
- 跨端页面、状态、响应式和语义合同：[跨端展示规格](../openspec/specs/shared/cross-platform-presentation/spec.md)。

影响实现或覆盖状态的变更必须更新本地图，并在对应 OpenSpec `tasks.md` 留下具体 QA 证据。平台特有控件可以不同，但值、标签、确认语义、错误处理和业务结果必须遵守共享行为合同。
