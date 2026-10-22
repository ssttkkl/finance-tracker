# Compose 功能地图

> 盘点日期：2026-10-22。本文记录当前 Compose Multiplatform 页面级功能、共享实现证据和已知覆盖缺口。行为事实以 `openspec/specs/` 与代码为准。

Compose Web、Android、iOS 共用 `compose/shared` 中的页面、状态和业务流程。Web、Android、iOS 工程入口分别位于 `compose/webApp`、`compose/androidApp` 和 `compose/iosApp`。旧 `web/src`、`mobile/src` 与 TypeScript shared packages 不属于当前客户端主线。

## 页面级功能

| ID | 页面级功能 | Compose 共享实现证据 | 当前实现 |
|---|---|---|---|
| F-01 | 登录与注册 | [`AccessScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt)、[`AuthResponsiveShell.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AuthResponsiveShell.kt) | 登录、注册与认证状态恢复 |
| F-02 | 工作区选择与创建 | [`App.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/app/App.kt)、[`AccessScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt) | 选择、创建、进入失败恢复 |
| F-03 | 收支账本 | [`CashLedgerScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt)、[`CashLedgerScrollbar.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScrollbar.kt)、[`CashProjectionPagingSource.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashProjectionPagingSource.kt)、[`CashProjectionApi.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/data/CashProjectionApi.kt)、[`web_queries.py`](../src/ft/application/web_queries.py)、[`web_queries.py`](../src/ft/adapters/relational/web_queries.py)、[`routes.py`](../src/ft/web/routes.py) | 搜索、筛选、绝对序号分页、流水列表和操作入口；按当前筛选结果准确计数并以稳定顺序读取指定流水位置，返回月份序号区间；完整刷新立即隐藏旧流水和汇总，按窗口显示骨架并支持列表顶部下拉刷新，分页追加保留现有流水；Compact 窄滚动条在滚动时显示、停止 2 秒后渐隐，轨道和滑块贴列表右边缘并覆盖列表、不挤占宽度，交互热区为 40 dp；Regular/Wide 月份表格容器及吸顶栏右边缘与筛选卡对齐，行内容左右内缩 16 dp；纤细滚动条贴近行内容右边界并常驻显示，40 dp 交互热区覆盖列表且不缩窄内容；月份摘要及桌面表格列标题在筛选卡下方固定并随下月到达顶部切换；FAB 覆盖列表且末行可达；Regular/Wide 行内容内缩 16 dp、分隔线全宽；详细筛选关闭入口在左；Regular/Wide Basic Dialog 宽 280–560 dp、28 dp 圆角、动态高度，标题/正文间距 16 dp、正文/操作间距 24 dp，操作组靠右，Compact Bottom Sheet 外框与行为不变
| F-04 | 现金流水 | [`CashLedgerDetails.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerDetails.kt)、[`CashLedgerViewModel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerViewModel.kt)、[`FinanceDtos.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/data/FinanceDtos.kt)、[`CashEvidenceAndRecordMappers.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/data/mapping/CashEvidenceAndRecordMappers.kt)、[`cash_ledger.py`](../src/ft/application/cash_ledger.py) | 新建、详情、编辑、删除及关系查看；详情 DTO 按端点既有 JSON 类型解析 ID，完整映射分类路径、组成项关联 ID 与精确关联金额；详情/编辑共享 Compact 全屏任务与 Regular/Wide 末端分离的模态 Side Sheet，使用 `surfaceContainerLow`、Large shape、零 elevation；保持已批准宽度，Side Sheet 按 16 dp 外侧留白和 24 dp 内容边距布局，底部操作左对齐且主操作最左，非 AlertDialog 关闭入口在左
| F-05 | 现金账单导入 | [`CashImportScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportScreen.kt)、[`ImportWorkflow.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/ImportWorkflow.kt) | 多文件扫描、账户映射、预览、关系审核与确认；Compact Full-screen Dialog 使用 56 dp 标题栏、正文顶部/左右 24 dp 和 56 dp 底栏；Regular/Wide Side Sheet 保留既有宽度比例，操作左对齐且主操作最左 |
| F-06 | 工作区邀请 | [`WorkspaceScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/WorkspaceScreens.kt)、[`AppRouting.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/core/AppRouting.kt) | 邀请预览、接受与取消 |
| F-07 | 收支分类管理（管理页内） | [`CashCategoryScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryScreen.kt)、[`CashCategoryViewModel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryViewModel.kt) | 管理页内嵌分类目录、搜索、排序和删除确认；Compact 全屏、Regular/Wide 对话框编辑；旧分类链接定位管理页分类分组 |
| F-08 | 投资持仓 | [`InvestmentScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentScreens.kt)、[`InvestmentDisplayLogic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentDisplayLogic.kt) | 账户筛选、估值、表现和持仓详情；Basic Dialog 宽 280–560 dp、28 dp 圆角、四周 24 dp 留白；标题区自适应，标题/正文间距 16 dp、正文/操作间距 24 dp，左侧关闭入口为产品例外 |
| F-09 | 投资事件 | [`InvestmentScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentScreens.kt)、[`InvestmentDisplayLogic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentDisplayLogic.kt) | 事件筛选、分页和证据详情；Basic Dialog 宽 280–560 dp、28 dp 圆角、四周 24 dp 留白；标题区自适应，标题/正文间距 16 dp、正文/操作间距 24 dp，左侧关闭入口为产品例外 |
| F-10 | 管理（工作区与收支分类） | [`WorkspaceScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/WorkspaceScreens.kt) | 以 M3 文字角色、平面分组和细分隔呈现；两级统一管理：工作区资料→基本信息/危险操作、访问权限→成员与权限/邀请成员、收支账本→收支分类；Regular/Wide 右侧目录、Compact Toolbar 全屏目录，共享两级同页定位与当前项，投资无占位；实现见 [`ManagementLayout.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/ManagementLayout.kt)、[`ManagementNavigation.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/ManagementNavigation.kt)，验证记录见 [`tasks.md`](../openspec/changes/archive/2026-10-21-unify-management-settings/tasks.md) |

以上 10 项按用户任务统计；F-07 嵌入 F-10 后仍是可独立完成的分类任务，不因合并入口减少统计数量。以上各项均有共享 Compose 页面实现，并由 Compose Web、Android、iOS 三个目标平台承载。共享代码存在不代表每个 target 的设备 QA 均已覆盖。

## 当前已知覆盖缺口

- F-07 / F-10：本次管理导航、目录和分类模态的 Web、Native 新验证记录于 [`tasks.md`](../openspec/changes/archive/2026-10-21-unify-management-settings/tasks.md)；Android 390 / 768 / 1440 dp 已通过目录、分类模态保存及旧链接定位；iOS 本轮仅共享编译，实际 iPhone/iPad 新布局仍待验收。

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
| F-03 | [`CashLedgerScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt)、[`CashLedgerScrollbar.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScrollbar.kt)、[`CashProjectionPagingSource.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashProjectionPagingSource.kt)、[`CashProjectionApi.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/data/CashProjectionApi.kt)、[`FinanceComponents.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/FinanceComponents.kt) | 同上 | 顶部搜索复用交易对方筛选，类型快捷项固定为全部、支出、收入和转账；详细筛选分为时间、金额、分类、账户、其他条件五组，金额组包含币种和金额范围，其他条件包含资金转移类型和合并状态；`COMPACT` 按组纵向排列，`REGULAR` / `WIDE` 在组内并排显示可配对字段。详细筛选在 `COMPACT` 使用 Bottom Sheet，`REGULAR` / `WIDE` 使用非全屏 Dialog；Basic Dialog 宽 280–560 dp、28 dp 圆角、动态高度；标题/正文间距 16 dp、正文/操作间距 24 dp，操作组靠右且关闭入口在左；Compact Bottom Sheet 外框与行为保持不变；筛选区以独立 Material 3 前景卡固定在页面顶部，只有其下方的流水列表滚动；所有尺寸的 FAB 叠加在列表视口上，末条保留滚动空间以完整显示在按钮上方；每个月份形成独立前景容器，月份汇总在筛选卡下方的列表视口顶部固定并由下月替换，`REGULAR` / `WIDE` 的表头同步固定，`COMPACT` 只固定汇总；`REGULAR` / `WIDE` 月份容器及吸顶栏右边缘与筛选卡对齐，每行内容向内缩 16 dp 且分隔线保持全宽。跨页记录仍逐笔懒加载，按相邻月份边界连续绘制。页面底色作为下层背景，筛选卡与月份容器之间留有间隔。Compact 默认隐藏复选框并以长按进入选择模式，Regular/Wide 保留行复选框，所有窗口移除全选入口。选中后 FAB 菜单自动展开并切换多选图标，已选数量以提示胶囊显示在 FAB 左侧。新增、导入及批量动作统一收进 Material 3 FAB 菜单；批量分类通过模态对话框应用，删除沿用现有确认；批量删除入口与确认使用危险色。主要操作保留文字并配有动作图标。接近已加载列表末尾时自动追加游标页；追加失败保留已有流水并提供重试。Regular/Wide 表格按旧 React Web 的选择、日期、账户、交易信息、分类、流水类型、金额、操作顺序和 5/12/15/24/12/11/13/8% 宽度比例排列；交易信息单元格先显示交易对方、再显示备注，Compact 行也让备注紧接交易对方。全局 FinanceTheme 将现有 token 映射到 `surfaceContainer*`，影响所有 Compose 平台使用 Material 3 默认容器色的组件；旧 React Web 不变。按钮与文本输入直接使用 Material 3，FinanceDataTable/Amount 等展示辅助函数保留，Registry 见 [`docs/design-system/registry.yaml`](design-system/registry.yaml)；详情卡片与关联流水折叠设计见 [`compose-cash-ledger-redesign/tasks.md`](../openspec/changes/compose-cash-ledger-redesign/tasks.md)，ChoicePicker、自动分页、表格列和 Web QA 见 [`compose-cash-import-account-mapping-redesign/tasks.md`](../openspec/changes/archive/2026-10-17-compose-cash-import-account-mapping-redesign/tasks.md) |
| F-04 | [`CashLedgerScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt)、[`CashLedgerDetails.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerDetails.kt)、[`CashLedgerViewModel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerViewModel.kt)、[`FinanceDtos.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/data/FinanceDtos.kt)、[`CashEvidenceAndRecordMappers.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/data/mapping/CashEvidenceAndRecordMappers.kt)、[`cash_ledger.py`](../src/ft/application/cash_ledger.py) | 同上 | 详情与编辑共用模态信息面板；详情 DTO 按端点 JSON 类型解码 ID，并保留分类路径、组成项关联 ID 和精确关联金额；`COMPACT` 均全屏，`REGULAR` / `WIDE` 均为右侧全高面板，编辑在详情面板内替换布局；新增关联是独立 M3 模态任务，使用 Compact 全屏、Regular/Wide 右侧全高面板，取消或完成返回详情；Wide 自定义定位面板距屏幕边缘 56 dp；详情、编辑和关联任务仅当前一个面板可见；删除、关系移除、放弃编辑、影响确认和错误知悉使用 M3 AlertDialog，出现时暂时隐藏所属面板，取消/知悉后恢复原任务与草稿，不嵌套活动模态；任务标题变化时更新模态语义子树；金额与币种构成摘要视觉重心，交易对方使用次级文字；类型和发生时间移入下方平铺字段组，主信息直接沿面板 24 dp 内容边界排列，不再使用卡片外框、阴影或替代背景块；主要字段值采用 `bodyLarge`、字段名采用 `bodyMedium` 并按首行基线对齐，受限宽度或大字体时标签在上、值在下；关联流水继续使用 Material 3 ElevatedCard 默认前景角色 `surfaceContainerLow`，名称和金额在容纳不下时重排；全局 FinanceTheme 从现有浅/深色 token 映射 `surfaceContainer*`，不在组件内重复指定容器颜色；关联流水卡默认收起、展开内容留在同一卡片内；详情正文滚动时编辑与更多操作固定在面板底部；Side Sheet 内容左右 24 dp、标题区顶部 24 dp、关闭图标 24 dp；底部操作区总高 112 dp（16/72/24 dp）、按钮垂直居中，操作左对齐且主操作最左；Compact 全屏标题栏为 56 dp、正文顶部/左右 24 dp、底部操作栏 56 dp；关闭/拖动行为保持不变；编辑、关联、保存和删除按钮保留文字并显示动作图标，删除和解散关联使用危险色；模态内 Escape 先关闭最上层菜单，再按任务规则退出当前面板；详细行为和三尺寸 Web 证据见 [`compose-material3-dialog-conformance/tasks.md`](../openspec/changes/compose-material3-dialog-conformance/tasks.md)；本轮共享 Compose 排版、Web/Native 验证与实际客户端待验情况见 [`refine-cash-record-detail-layout/tasks.md`](../openspec/changes/archive/2026-10-22-refine-cash-record-detail-layout/tasks.md)（Side Sheet 详情顶部 24 dp 留白随正文滚动；Web 和 Android 三窗口 QA 已通过，iOS 实际 UI 待补验；用户已确认演示并授权合并；页面数不变） |
| F-05 | [`App.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/app/App.kt)、[`CashImportScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportScreen.kt)、[`CashLedgerScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt)、[`SecondaryPageContainer.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/SecondaryPageContainer.kt)、[`FinanceTheme.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/core/FinanceTheme.kt)、[`ImportWorkflow.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/ImportWorkflow.kt)、[`cash_ledger.py`](../src/ft/application/cash_ledger.py)、[`relations.py`](../src/ft/application/relations.py) | 同上 | 文件选择、扫描、映射、预览、关系审查和确认已实现；待分摊流水保留在预览中，金额不完整时不进入关系匹配；跨来源退款只在对应关系唯一且金额精确相同或退款侧已镜像时替代系统自动银行退款，并先释放旧关系额度再保存新关系及取代链；人工决定保持不变；从收支账本打开，不占一级导航；Compact 全屏返回，Regular 按横向窗口等级使用贴右全高、占当前视口宽度 90% 的模态侧边面板，Wide 占 70%，正文独立滚动；各尺寸阶段标题栏左上都有关闭入口，所有阶段操作固定在底部；Compact 保持既有操作顺序，Regular/Wide Side Sheet 操作左对齐且主要提交最左；Compact Full-screen Dialog 标题栏为 56 dp、正文顶部/左右 24 dp、底栏 56 dp；Regular/Wide Side Sheet 保留既有宽度比例与关闭行为，关系配对阶段会实际提交导入；阶段切换同步更新模态语义标题和当前控件；未提交工作关闭前先确认；处理中关闭提示在现有任务容器中展示，不叠加额外信息弹窗；多阶段操作的确认按钮在处理时继续可用但防止重复请求，错误反馈在 Compact 使用 M3 AlertDialog，在 Regular/Wide 保留在当前侧板中；新账户名称为空时各尺寸均禁用“应用”；账户映射菜单项与已选控件展示名称、类型和币种；选择「创建新账户」后控件保留固定标签，并在创建行下方显示推导信息与修改入口；复用共享草稿时信息只显示在已选控件，修改同步到全部引用行；共享 Compose ChoicePicker 使用 Material 3 锚定弹出菜单，Web 与 Native 共用实现，编辑新账户时 Escape 先收起账户类型菜单、下一次 Escape 才关闭编辑；待配对流水候选通过同一 ChoicePicker 呈现；导入各步骤继承 FinanceTheme 浅色/夜间模式，主要操作按钮保留文字并显示图标，账本主页导入 FAB 使用收件箱图标；成功导入返回时账本自动刷新当前筛选下的流水、汇总及账户/分类引用；批量扫描、密码重试和关闭清理共用导入会话；无 API 后端演示提供内置合成账单并复用同一流程。相关设计验收历史见 [`cash-import-candidate-overlay`](../openspec/changes/archive/2026-10-14-cash-import-candidate-overlay/tasks.md)；当前账户映射、导入主题与返回刷新证据见 [`compose-cash-import-account-mapping-redesign/tasks.md`](../openspec/changes/archive/2026-10-17-compose-cash-import-account-mapping-redesign/tasks.md)。 |
| F-06 | [`WorkspaceScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/WorkspaceScreens.kt)、[`AppRouting.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/core/AppRouting.kt) | 同上 | 邀请预览、接受、取消及内部 URI 路由已实现；未登录邀请沿用认证入口卡片，已登录邀请保留 Scaffold 标题与接受流程；本轮 Web/Native QA 见 `app-shell-entry-presentation/tasks.md`；HTTPS Universal Links 仍需域名和签名配置 |
| F-07 | [`CashCategoryScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryScreen.kt)、[`CashCategoryViewModel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryViewModel.kt)、[`CashCategoryUseCases.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/domain/CashCategoryUseCases.kt) | 同上 | 分类目录、层级编辑、搜索、排序和删除确认已实现；分类页面已迁移至 Domain Repository、用例和 StateFlow ViewModel，Wasm 单测通过；Web Chrome 与 Android 搜索/创建 QA 通过；iPad 11 已验无结果搜索和创建，其他操作仍需补验 |
| F-08 | [`InvestmentScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentScreens.kt)、[`InvestmentDisplayLogic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentDisplayLogic.kt) | 同上 | 持仓筛选、估值和表现已实现；估值详情为 Compact/Regular/Wide 共用、限宽 560 dp 的非全屏基础对话框；28 dp 圆角、四周 24 dp 留白；标题区自适应，标题/正文间距 16 dp、正文/操作间距 24 dp，左侧关闭入口为产品例外；正文滚动与关闭知悉操作分区清楚；Web E2E 与 Android compact/regular/wide 主题布局 QA 通过；iPad 11 已验估值/详情，iPad 13 wide 已验深色布局 |
| F-09 | [`InvestmentScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentEventScreen.kt)、[`InvestmentDisplayLogic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentDisplayLogic.kt) | 同上 | 事件筛选、分页和证据详情已实现；证据详情为 Compact/Regular/Wide 共用、限宽 560 dp 的非全屏基础对话框；28 dp 圆角、四周 24 dp 留白；标题区自适应，标题/正文间距 16 dp、正文/操作间距 24 dp，左侧关闭入口为产品例外；正文滚动与关闭知悉操作分区清楚；Web E2E 与 Android 详情/分页 QA 通过；iPad 11 已验详情与加载更多 |
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
