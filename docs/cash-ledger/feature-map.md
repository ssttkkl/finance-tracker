# 现金账本模块

[返回产品总览](../feature-map.md) · [跨端覆盖与待办](../feature-map.md#跨端覆盖与待办)

关系治理未新增页面：后端已完成三类关系硬约束、派生业务标签和稳定收支引用的本轮实现与验证，主动搜索返回所选关系类型下的现金部分选择。Compose 共享层已同步新合同，删除关系子类型选择并保留现有 UI；共享测试、Android／iOS 编译、E2E 和真实浏览器冒烟已通过，用户已确认首版演示，独立契约／设计复核已完成，视觉 Hallmark 复核与最终 QA 未完成；后端验证不代替前端验收，验证记录见[归档变更](../../openspec/changes/archive/2026-10-30-govern-cash-relation-model/tasks.md)，后续界面修复见 [UI 审查修复](../../openspec/changes/fix-cash-ui-audit-findings/proposal.md)。

本机关系治理演示包含覆盖 P01–P26 的合成场景、导入样例和操作索引；API 专属路径保持实际接口，故障模式仅在演示服务启用，未新增产品页面。扩充数据与浏览器核对范围见上述任务证据，不代表所有分支最终验收通过。Hallmark 审查发现的既有界面问题登记在 [UI 审查修复](../../openspec/changes/fix-cash-ui-audit-findings/proposal.md)，本轮不修。

本模块包含 **3 项页面级功能**：F-03 收支账本、F-04 收支详情与记账、F-07 收支分类管理。F-04 由详情面板／表单承载，F-07 嵌入管理页；均保留独立任务编号。

## F-03 收支账本

**入口：** `/w/<workspaceId>/`，当前工作区默认账本。

**主路径：** 打开收支列表 → 搜索／筛选 → 连续浏览、分页或按序号定位 → 打开一笔收支进入 F-04。

**分支与操作：**

| 任务 | 可用路径 |
|---|---|
| 搜索与快捷筛选 | 按交易对方搜索；快捷项顺序为待确认、分割线、全部／支出／收入／转账；待确认有结果或已选中时显示；筛选入口固定右侧并垂直居中，其余选项按可用空间自动换行 |
| 详细筛选 | 时间、币种和金额（含默认开启的“隐藏0元流水”）、分类、账户、转账细分、确认状态（全部／待确认／已确认）、收支状态（全部／草稿／正式）、流水组成（单笔、支付镜像、退款抵扣、合并） |
| 新增 | 从新增菜单进入普通记账／转账 F-04，或账单导入 F-05 |
| 批量维护 | 选择多笔收支后批量分类或确认删除 |
| 刷新与恢复 | 筛选变化后从新结果浏览；整页读取失败可重新加载；追加分页失败保留已加载内容并可重试 |

“隐藏0元流水”按最终显示金额精确过滤零元结果，金额未知的草稿仍可查看；新开账本和重置筛选时开启，本次打开期间可切换。筛选结果同步影响列表及其数量，服务端分页游标绑定当前筛选值。无法识别的字段显示为未知值，不作为筛选项。当前筛选结果按准确总数、稳定顺序和绝对序号读取，并返回月份序号区间；读取版本变化时清理旧选择。

### 页面呈现

筛选卡固定在顶部，只有下方列表滚动；搜索下方的快捷项在左侧可用空间内自动换行，“待确认＋分割线＋全部”保持同组，“筛选”入口固定在右侧并相对快捷项整体垂直居中。空间足够时自然显示一行，页面不横向滚动。完整刷新隐藏旧流水与汇总并显示骨架，支持列表顶部下拉刷新。月份汇总吸顶并由下月替换，`REGULAR`／`WIDE` 表头同步吸顶，`COMPACT` 只固定汇总。跨页记录逐笔懒加载，临近末尾自动追加。

`COMPACT` 长按进入选择模式，`REGULAR`／`WIDE` 保留行复选框，各窗口均无全选入口。选中后 FAB 菜单展开并显示数量，新增、导入和批量动作共用该菜单；删除入口及确认使用危险色。FAB 覆盖列表，末条保留可达空间。

详细筛选按时间、金额、分类、账户和其他条件分组；`COMPACT` 纵向排列并使用 Bottom Sheet，`REGULAR`／`WIDE` 在组内并排显示可配对字段并使用非全屏 Dialog。桌面表格依次显示选择、日期、账户、交易信息、分类、流水类型、金额和操作；交易对方后显示备注，窄屏卡片保持同一信息关系。

窄屏滚动条滚动时显示、停止 2 秒后渐隐；桌面滚动条常驻。滚动条均覆盖列表而不挤占内容宽度，交互热区为 40 dp。桌面月份容器和吸顶栏与筛选卡右侧对齐，行内容内缩 16 dp、分隔线全宽。列宽、Dialog 尺寸和间距等实现细节见下方设计与任务证据，具体 UI 规范见 [UI 设计规范](../shared/ui-design-rules.md)。

### 实现与验证证据

- [`CashLedgerScreen.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt)
- [`CashLedgerScrollbar.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScrollbar.kt)
- [`CashProjectionPagingSource.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashProjectionPagingSource.kt)
- [`CashProjectionApi.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/data/CashProjectionApi.kt)
- [`web_queries.py`](../../src/ft/application/web_queries.py)
- [`web_queries.py`](../../src/ft/adapters/relational/web_queries.py)
- [`routes.py`](../../src/ft/web/routes.py)
- [`FinanceComponents.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/FinanceComponents.kt)
- [`docs/shared/design-system/registry.yaml`](../shared/design-system/registry.yaml)
- [`compose-cash-ledger-redesign/tasks.md`](../../openspec/changes/archive/2026-10-21-compose-cash-ledger-redesign/tasks.md)
- [`compose-cash-import-account-mapping-redesign/tasks.md`](../../openspec/changes/archive/2026-10-17-compose-cash-import-account-mapping-redesign/tasks.md)

## F-04 收支详情与记账

**入口：** 从 F-03 打开详情面板或新增表单，不是独立路由。

**查看主线：** 打开一笔收支 → 查看摘要、关联流水、账单原文和待处理事项 → 关闭返回账本。关联流水和账单原文直接展示；详情摘要与导入预览共用平面字段。

**业务路径与限制：**

| 任务 | 可用路径与边界 |
|---|---|
| 普通记账／转账创建 | 选择模式 → 填写必填项并通过校验 → 保存；模式切换保留既有输入，不以草稿绕过必填校验 |
| 编辑收支 | 导入收支只改允许共用的字段；普通手工收支可改完整业务字段；完整转账在同一任务保存双端，缺少完整组能力时只改公共字段 |
| 维护导入草稿 | 已有导入草稿可保存字段或分摊；普通保存仍为草稿，补齐后明确转为正式收支；保护成员身份与金额精度 |
| 处理关系建议 | 对符合资格的导入或历史正式收支，接受已选完整方案或拒绝整组；单方案默认选中，有备选时先单选完整方案 |
| 搜索关联流水 | 选择关系类型 → 搜索并单选可用的对侧流水；建议搜索锁定关系类型；有备选时可切换搜索并返回保留原方案，无备选直接搜索 |
| 移除／解除关系 | 查看影响范围 → 确认；拒绝／移除在原确认页完成一次最终确认，冲突后可返回选择 |
| 处理来源差异 | 选择采用来源或保留当前值 |
| 处理身份问题 | 核对候选后明确合并 |
| 删除 | 核对并确认删除范围 |

只读角色只能查看，其他操作同时受工作区角色和流水来源资格限制。普通手工及手工转账不能对外关联。联合方案改选目前只支持搜索候选，不能提交。

**取消与恢复：** 关闭未保存内容时可取消关闭并继续编辑；保存失败留在当前任务重试；结果未知时核实原提交；事实已变化则刷新并重新选择。

### 页面呈现

关联成员按实际关系标注原文，完整建议与已有关联流水共用基础信息布局，备注带标签。有效待确认关系显示无按钮警示，接受／核对与拒绝在弹窗处理；添加关联先通过单选分段按钮选择类型。桌面复用表格，`COMPACT` 复用卡片，不新增关系一级入口。

### 实现与验证证据

当前详情、来源原文、草稿字段／分摊保存和明确转正式已通过 Web 1440 px 合成账本真实 API 冒烟；普通记账／转账创建、普通金额及完整双端编辑、公共整笔编辑、来源采用和身份合并均有真实 API 冒烟证据。公共整笔编辑使用真实修订，保留来源字段和原文。旧直接编辑／任意关联页面及客户端 API 已移除。

关联 v6 及走查修复通过 22 项 Chromium 合成 API 流程、421 项共享单测及相称跨端编译；代表视口反馈验证、非性能回归和 SQLite／真实 PostgreSQL 契约有记录。复杂浏览器保存、最终演示及完整最终验收仍未完成，不能据入口接通或历史截图认定最终交付。

- [`CashCurrentRecordPanel.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCurrentRecordPanel.kt)
- [`CashRecordSummary.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashRecordSummary.kt)
- [`CashLedgerViewModel.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerViewModel.kt)
- [现金流水](cash-ledger.md)
- [`CashLedgerScreen.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt)
- [`维护任务`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashRecordTaskViewModel.kt)
- [关联实施记录](../../openspec/changes/archive/2026-10-28-cash-record-detail-relations-redesign/tasks.md)
- [`维护实施记录`](../../openspec/changes/archive/2026-10-28-compose-cash-ledger-maintenance/tasks.md#当前审查与验证)
- [操作流程](cash-ledger.md#5-操作流程及作用范围)
- [关联共享详情](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashDetailRelations.kt)

## F-07 收支分类管理

**入口：** F-10 管理页的收支账本分组；`/w/<workspaceId>/cash-categories` 深链接定位本分组，无独立主导航。

**业务路径：**

- 浏览目录 → 搜索／排序 → 查看目标分类。
- 新建一级／子分类，或编辑名称、说明和父级，调整同级顺序。
- 删除前查看影响；有子分类先处理子分类，已有收支引用时确认清除引用后删除，也可取消。

**失败与恢复：** 目录读取失败可重试；保存、排序或删除失败显示错误并保留当前上下文。

### 实现与验证证据

分类已接入 Domain Repository、用例和 StateFlow ViewModel，Wasm 单测有通过记录。`COMPACT` 全屏编辑，`REGULAR`／`WIDE` 对话框编辑。Web Chrome 与 Android 搜索／创建 QA 通过，Android 新管理导航覆盖 390／768／1440 dp 的目录、分类保存和分类深链接定位。iPad 11 已验无结果搜索与创建；iOS 新布局仅共享编译，完整操作集仍需设备验证。

- [`CashCategoryScreen.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryScreen.kt)
- [`CashCategoryViewModel.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryViewModel.kt)
- [`CashCategoryUseCases.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/domain/CashCategoryUseCases.kt)
- [管理导航与分类模态验证](../../openspec/changes/archive/2026-10-21-unify-management-settings/tasks.md)

## 现金客户端接入证据

| 归档变更 | 功能边界 | 实现与验证状态 |
|---|---|---|
| [账本零元筛选与快捷筛选布局](../../openspec/changes/archive/2026-10-29-cash-ledger-zero-amount-filter/proposal.md) | 按最终显示金额精确隐藏零元结果，保留金额未知草稿；待确认数与分页共享筛选条件；窄宽度快捷项自动换行 | 已接服务端、Compose 共享筛选及重置行为；SQLite／PostgreSQL 合同、Android Host／iOS Simulator 共享测试和 Chrome 390×844／768×1024 交互检查通过。原生设备交互未运行 |
| [账单导入](../../openspec/changes/archive/2026-10-28-compose-cash-import-workflow/proposal.md) | 文件／账户准备、统一核对、确认、真实结果、未知核实与退出恢复 | 共享全屏对话框已接入上述流程；用户已确认账户分组与密码按钮方向。来源账号分组、同名合并选择、Material 3 排版和验证状态见 [变更任务记录](../../openspec/changes/archive/2026-10-29-cash-import-source-account-grouping/tasks.md)。 |
| [账本与补处理](../../openspec/changes/archive/2026-10-28-compose-cash-ledger-maintenance/proposal.md) | 当前列表／详情、草稿、来源问题、已保存关系组、分类与删除 | 当前列表／详情、状态筛选和草稿维护已接入；来源问题已接线；关系完整组、公共冲突及解除继承已通过真实 API 验收，最终演示待确认 |
| [记账与编辑](../../openspec/changes/archive/2026-10-28-compose-cash-entry-editor/proposal.md) | 公共属性整笔编辑、普通手动记账、同入口转账及双端编辑 | 公共整笔编辑及创建方式权限已接入；普通记账、转账创建及模式输入保留已接入；完整转账在同表单编辑双端，缺少完整组能力时只改公共字段 |

上表只索引实现与验证记录，当前行为以主规格和共享 Compose 页面为准，未完成项统一见 [总览待办](../feature-map.md#跨端覆盖与待办)。

## 相关规格

- [收支账本](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt)
- [收支详情与编辑](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCurrentRecordPanel.kt)
- [分类管理](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryScreen.kt)
- [账本浏览规格](../../openspec/specs/cash-ledger/cash-ledger-browser/spec.md)
- [流水分类规格](../../openspec/specs/cash-ledger/cash-record-classification/spec.md)
- [分类管理规格](../../openspec/specs/cash-ledger/cash-category-management/spec.md)
- [对方账户转账匹配规格](../../openspec/specs/cash-ledger/counterparty-account-transfer-matching/spec.md)
- [交易关系规格](../../openspec/specs/cash-ledger/transaction-relations/spec.md)
