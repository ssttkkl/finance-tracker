# 现金账本模块

`ft cash` 和 Compose 收支账本共用快照查询、分币种汇总、来源与维护操作。记账或转账提交中断后，Compose 在原工作区重新打开记账入口可核实原操作，`ft` 使用 `cash resume`。终端命令见[账本 CLI](../../compose/README.md#账本-cli)。


离线时保留缓存账本、详情和本地表单，保存、分类、删除及维护确认受连接资格限制，原角色不变；刷新保留在 FAB 菜单。连接说明与显式核验见[共享规则](../shared/feature-map.md)。

Compose Wasm 在后台从当前账本缓存生成流水列表、筛选和月度汇总，翻页复用同一版本。后台处理失败时保留当前已显示的流水，可通过现有按钮重试。当筛选或版本已变化，上一批流水仅供查看，当前查询成功后恢复选择和批量操作。

[返回产品总览](../feature-map.md) · [跨端覆盖与待办](../feature-map.md#跨端覆盖与待办)

现金收支支持三类关系硬约束、派生业务标签和稳定收支引用；主动关联搜索返回所选关系类型下可选的现金部分。实现与验证范围见 [关系治理任务](../../openspec/changes/archive/2026-10-30-govern-cash-relation-model/tasks.md) 和 [UI 审查修复](../../openspec/changes/archive/2026-10-31-fix-cash-ui-audit-findings/tasks.md)。

本机演示包含合成关系场景、导入样例和操作索引；API 专属路径保持实际接口，故障模式仅在演示服务启用，未新增产品页面。

旧物化列表、详情、证据、定位及状态／重建路由，以及服务端暂存导入 HTTP 方法已退役；规范快照、写集、永久收据与维护命令保留。物化表和内部旧编排仍待专门清理，实施证据见[离线账本任务](../../openspec/changes/offline-first-ledger-import/tasks.md)。

手工记账、转账及旧现金新增／余额校准的后端命令已直接协调规范条目，不读取或发布物化状态；原组成项、精确分币种余额、校准覆盖和投资事件保持。物化表与其余在线引用仍待清理，证据见[创建命令规范协调](../../openspec/changes/offline-first-ledger-import/tasks.md#创建命令规范协调43)。

本模块包含 **4 项页面级功能**：F-03 收支账本、F-04 收支详情与记账、F-07 收支分类管理、F-11 账本助手。F-04 由详情面板／表单承载，F-07 嵌入管理页；均保留独立任务编号。

本工作树的 UI 修复记录见 [fix-cash-ui-audit-findings](../../openspec/changes/archive/2026-10-31-fix-cash-ui-audit-findings/tasks.md) ：页面数不变，Web／Android／iOS 共用共享呈现；Web 代表视口、共享测试和相称跨端编译已验证，设备交互 QA 未执行。

离线演示 Graph 默认提供 24 条可见收支、待确认关联建议、多币种和缺项草稿；内置账单含 7 行、5 个来源账户组。Web 已完成代表视口入口与主要操作检查，Android／iOS 共用 Graph 并通过相称编译；设备 QA 未执行。本次不增加页面，具体 UI 审查与验证范围见变更任务。

现金发生时间在服务端录入、规范写集及共享记录值／行归一沿 UTC 1900 年至 2100 年（含首尾日期）校验；投资、系统时间、原始账单与筛选日期保持各自合同。普通现金、含现金端转账及现金编辑在提交前校验，端侧金额筛选限制整数 20 位／小数 18 位，完整金额汇总不受单笔输入精度截断。

共享规则覆盖来源核心判断、候选召回、稳定提案引用、关系硬约束、镜像／退款／转账配对及端侧完整导入计算，后端复用同一规则。退款商户文本由两端共同调用 [normalizeCashMerchant](../../compose/business-domain/src/commonMain/kotlin/com/finance/tracker/business/domain/LocalCashMerchant.kt)，保留原 Java word／ROOT 语义；[独立对照](../../compose/backend/src/test/kotlin/com/finance/tracker/backend/CashMerchantSharedCompatibilityTest.kt)保留原表达式，不以共享方法自证。全历史规范事实由已鉴权快照提供，三端 [浏览 owner](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/core/LocalCashLedgerOwner.kt)按固定登录范围持有加密缓存，统一筛选、游标、全局选项、显式时区日期和完整月份统计。关键词按普通文字包含及忽略大小写处理，不改变导入商户匹配。启动、写入及已提交收据恢复刷新原会话，保存失败保留材料；离线状态呈现与重新联网完整流程及整个离线导入验收仍待完成。

生产[应用接线](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/app/FinanceTrackerGraph.kt)已将在线／离线列表及完整详情统一为原会话的本地完整事实读取。原发表、关闭与刷新围栏覆盖列表、完整成员、原文、公共条目、问题／候选和来源证据；证据与全部记录在一次捕获中生成，按原绑定角色生成能力提示，实际写入仍由服务端鉴权。

[分类](../../compose/backend/src/main/kotlin/com/finance/tracker/backend/CashClassificationService.kt)、[删除](../../compose/backend/src/main/kotlin/com/finance/tracker/backend/CashDeletionService.kt)及[维护提交](../../compose/backend/src/main/kotlin/com/finance/tracker/backend/CurrentCashMaintenanceCommitWriter.kt)的实际 HTTP 路径已使用稳定规范条目及账本版本。分类不改来源值，删除按完整组清理引用／逻辑删除并退休条目；维护保留完整成员拆分、公共值、余额和永久收据的原事务边界。过期基线或工作区漂移拒绝，不换算旧物化版本。

用户刷新先同步版本与完整事实，再重载原筛选；保存后和筛选／分页只读缓存。明确断网时保留原已可读事实，已知写入后未同步的事实继续封闭；关闭或换代取消旧结果，缺少事实返回可重试错误，不请求旧投影读取。实现与受影响验证见[应用切换任务](../../openspec/changes/offline-first-ledger-import/tasks.md#应用账本与规范维护整体切换7273)。旧读取和暂存导入 HTTP 方法已退役，物化表及内部旧编排仍待清理，离线状态呈现与重新联网完整流程仍待完成，不代表整个离线导入已验收。

## F-03 收支账本

**入口：** `/w/<workspaceId>/`，当前工作区默认账本。

**主路径：** 打开收支列表 → 搜索／筛选 → 连续浏览、分页或按序号定位 → 打开一笔收支进入 F-04。

**分支与操作：**

| 任务 | 可用路径 |
|---|---|
| 搜索与快捷筛选 | 按交易对方搜索；快捷项顺序为待确认、分割线、全部类型／支出／收入／转账；待确认有结果或已选中时显示；筛选入口固定右侧并垂直居中，其余选项按可用空间自动换行 |
| 详细筛选 | 时间、币种和金额（含默认开启的“隐藏0元流水”）、分类、账户、转账细分、确认状态（全部／待确认／已确认）、收支状态（全部／草稿／正式）、流水组成（单笔、支付镜像、退款抵扣、合并） |
| 新增 | 从新增菜单进入普通记账／转账 F-04，或账单导入 F-05 |
| 批量维护 | 选择多笔收支后批量分类或确认删除 |
| 刷新与恢复 | 筛选变化后从新结果浏览；整页读取失败可重新加载；追加分页失败保留已加载内容并可重试 |

“隐藏0元流水”按最终显示金额精确过滤零元结果，金额未知的草稿仍可查看；新开账本和重置筛选时开启，本次打开期间可切换。筛选结果同步影响列表及其数量，端侧分页游标绑定当前筛选值及同一账本版本。无法识别的字段显示为未知值，不作为筛选项。当前筛选结果按准确总数、稳定顺序和绝对序号读取，并返回月份序号区间；读取版本变化时清理旧选择。

### 页面呈现

筛选卡固定在顶部，只有下方列表滚动；搜索下方的快捷项在左侧可用空间内自动换行，“待确认＋分割线＋全部类型”保持同组，“筛选”入口固定在右侧并相对快捷项整体垂直居中。空间足够时自然显示一行，页面不横向滚动。完整刷新隐藏旧流水与汇总并显示骨架，支持 FAB 菜单中的“刷新”和列表顶部下拉刷新；两者先同步账本，再保留当前筛选重建列表，同步中不能重复触发。账本、导入核对与关联搜索共用瀑布流组件的时间分组、吸顶表头、右侧滚动条和收支行；账本与导入核对启用月度收支合计。Compact 月份标题独占首行并左对齐，下方每种币种的收入和支出横向成行、整体右对齐。月份汇总吸顶并由下月替换，`REGULAR`／`WIDE` 表头同步吸顶，`COMPACT` 只固定汇总。跨页记录逐笔懒加载，临近末尾自动追加。

`COMPACT` 长按进入选择模式，`REGULAR`／`WIDE` 保留行复选框，各窗口均无全选入口。选中后 FAB 菜单展开并显示数量，新增、导入和批量动作共用该菜单；删除入口及确认使用危险色。FAB 覆盖列表，末条保留可达空间。删除前的临时读取失败使用 Snackbar。批量操作结果待核实时，在操作区域提供在线核对入口，刷新完成后重新选择。

详细筛选先编辑草稿，点击“应用”后更新列表；日历确认只回填日期，关闭面板或 Escape 放弃未应用修改。详细筛选按时间、金额、分类、账户和其他条件分组；`COMPACT` 纵向排列并使用 Bottom Sheet，`REGULAR`／`WIDE` 在组内并排显示可配对字段并使用非全屏 Dialog。桌面表格依次显示选择、日期、账户、交易信息、分类、流水类型和金额，整行可打开详情；交易对方后显示备注。类型列按主流水显示类型及适用细分，下方以有底色标签说明“草稿待补充”“配对建议”或“账单更新待核对”等待处理原因，整行使用警告底色；不以“待确认”“已合并”作为行标签。窄屏卡片保持同一信息关系。

窄屏滚动条滚动时显示、停止 2 秒后渐隐；桌面滚动条常驻。滚动条均覆盖列表而不挤占内容宽度，交互热区为 40 dp；拖动已加载内容时列表随距离连续移动，跨页仍按流水序号定位。桌面月份容器和吸顶栏与筛选卡右侧对齐，行内容内缩 16 dp、分隔线全宽。列宽、Dialog 尺寸和间距等实现细节见下方设计与任务证据，具体 UI 规范见 [UI 设计规范](../shared/ui-design-rules.md)。

### 实现与验证证据

当前导航、筛选草稿、日期、月份吸顶及编辑控件语义的回归范围见 [浏览器回归任务](../../openspec/changes/archive/2026-11-05-fix-web-regression-gates/tasks.md)。本次不增加页面或改变财务规则；Native 仅做相称编译与共享单测，未新增设备交互 QA。

- [`CashLedgerScreen.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt)
- [`CashLedgerScrollbar.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScrollbar.kt)
- [`CashProjectionPagingSource.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashProjectionPagingSource.kt)
- [`LocalFirstFinanceRepository.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/data/LocalFirstFinanceRepository.kt)
- [`LocalCashLedgerReader.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/core/LocalCashLedgerReader.kt)
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
| 普通记账／转账创建 | 选择模式 → 填写必填项并通过校验 → 保存。模式切换保留输入。账户不足时可在表单中创建，返回后自动选中新账户并保留已填内容。保存响应中断时，原保存按钮接续同一操作。 |
| 编辑收支 | 导入收支只改允许共用的字段；普通手工收支可改完整业务字段；完整转账在同一任务保存双端，缺少完整组能力时只改公共字段 |
| 维护导入草稿 | 已有导入草稿可保存字段或分摊；普通保存仍为草稿，补齐后明确转为正式收支；保护成员身份与金额精度 |
| 处理关系建议 | 对符合资格的导入或历史正式收支，接受已选完整方案或拒绝整组；单方案默认选中，有备选时先单选完整方案 |
| 搜索关联流水 | 选择关系类型 → 后端仅返回可选的对侧流水并在资格过滤后分页 → 单选目标；建议搜索锁定关系类型；允许改选的建议可切换搜索并保留原方案，无备选时直接搜索 |
| 移除／解除关系 | 查看影响范围 → 确认；拒绝／移除在原确认页完成一次最终确认，冲突后可返回选择 |
| 处理来源差异 | 选择采用来源或保留当前值 |
| 处理身份问题 | 核对候选后明确合并 |
| 删除 | 核对并确认删除范围 |

只读角色只能查看，其他操作同时受工作区角色和流水来源资格限制。普通手工及手工转账不能对外关联。联合方案可查看、采纳或忽略完整建议。

**取消与恢复：** 关闭未保存内容时可取消关闭并继续编辑；保存失败留在当前任务重试；结果未知时核实原提交；事实已变化则刷新并重新选择。

### 页面呈现

已有关联流水与账单原文共用成员卡布局：标题右侧的关系标签位于金额前，字段列表上方有分隔线；关联卡保留移除操作，原文卡保留来源字段。完整建议按记录展示基础信息。有效待确认关系显示无按钮警示，接受／核对与拒绝在弹窗处理；添加关联先通过单选分段按钮选择类型。桌面复用表格，`COMPACT` 复用卡片，不新增关系一级入口。

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
- 新建一级／子分类，或编辑名称、说明和父级。通过“调整顺序”可直接移到一个同级分类前或同级末尾。
- 删除前查看影响；有子分类先处理子分类，已有收支引用时确认清除引用后删除，也可取消。

**失败与恢复：** 目录读取失败可重试，保存和排序失败保留当前上下文。删除前的临时读取失败以 Snackbar 提示，删除结果待核实时提供在线核对入口。查看者只能浏览，创建、编辑及更多操作保持禁用；语义激活不能打开编辑窗口。

### 实现与验证证据

分类已接入 Domain Repository、用例和 StateFlow ViewModel，Wasm 单测有通过记录。`COMPACT` 全屏编辑，`REGULAR`／`WIDE` 对话框编辑。Web Chrome 与 Android 搜索／创建 QA 通过，Android 新管理导航覆盖 390／768／1440 dp 的目录、分类保存和分类深链接定位。iPad 11 已验无结果搜索与创建；iOS 新布局仅共享编译，完整操作集仍需设备验证。

- [`CashCategoryScreen.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryScreen.kt)
- [`CashCategoryViewModel.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryViewModel.kt)
- [`CashCategoryUseCases.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/domain/CashCategoryUseCases.kt)
- [查看者物理与语义入口验证](../../openspec/changes/archive/2026-11-05-fix-compose-browser-regression-gates/tasks.md)
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


## F-11 账本助手

从主导航开始新对话或打开本设备的历史对话。已有对话有本机地址，当前账号与工作区可以在这台设备上刷新或直接打开。可从侧栏或当前对话的页头操作置顶、重命名和删除历史。已写入账本的流水保留，记账结果核实期间暂停删除。助手能查当前工作区的账户、分类、收支及汇总，并从查询结果和已记录卡片打开现有收支详情。支持多行输入，助手回复以 Markdown 逐段显示。点击对应工具摘要可核对实际查询或记账状态。

记账时在对话里核对并编辑草稿，确认后才写入账本。在卡片待确认时发出新消息，会取消原卡。在首段回复前失败时，用户消息显示「未送达」。已有正文后中断时保留部分回复，并显示「回复中断」。可重试原消息，部分回复会由新回复替换。重新打开应用或恢复网络时保留历史及发送状态，推理需要手动重试。

对话可生成 CSV、Markdown 和纯文本。助手在本机读取当前账本生成文件，发出后可在对话里预览和导出，也可在后续消息里按该附件继续加工。对话与附件留在当前设备和工作区。

Web、Android 和 iOS 共用该页面及缓存逻辑。推理需要服务端配置模型，本机预览可连接合成模型或已配置的 DeepSeek。当前变更的实测范围和未完成验收见 [助手验收记录](../../openspec/changes/add-client-ledger-assistant/tasks.md)。
