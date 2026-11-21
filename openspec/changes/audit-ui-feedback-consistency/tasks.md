# 全页面反馈与操作一致性修复

## 1. 思考

- [x] 1.1 已读 `docs/shared/ui-design-rules.md`、`openspec/specs/shared/design-system/spec.md` 及现金账本、导入、工作区、投资相关合同。
- [x] 1.2 以 6 个只读子审查覆盖认证/工作区、账本、详情/关联、导入、分类/投资、共享壳与通用组件；逐条复核关键 finding，撤销两项误报：`showSnackbar` 带 action 时默认 `Indefinite`；导入超限只显示一次 Snackbar 是 `cash-import-session` 明文特例。
- [x] 1.3 按 B 类处理：影响多个页面但每项为局部反馈/布局/无障碍修复，不改写入协议与数据模型。

## 2. 计划

- [x] 2.1 目标：修复 blocker/major 的反馈归属、操作层级与无障碍问题；非目标：重做视觉、改后端/接口、重设写入恢复协议、补齐设备 QA。
- [x] 2.2 验收：受影响页面可编译；关键交互在 Web 代表视口可走通；计划确认、导入空态、账本加载态、关联对话框、认证忙碌态和分类删除确认具备可验证行为。

## 3. 任务拆分

- [x] 3.1 详情计划确认：`CashCurrentRecordPanel` 非编辑/非关联态渲染计划确认与固定操作栏。
- [x] 3.2 导入：空文件主操作、主操作图标、核对空态、单一筛选 chip、动作提示可关闭与切换清理、容量文案；超限预览按用户决策改为文件区常驻原因，更新 `cash-import-session` delta 替代 Snackbar-only。
- [x] 3.3 账本：加载中骨架、金额筛选原因、批量选择无障碍入口、紧凑底部弹层关闭入口。
- [x] 3.4 关联/详情：对话框 pane title 与安全区、移除关联危险色、进度控件名称。
- [x] 3.5 认证/工作区：忙碌按钮保留名称、安全区避让、移除成员危险色、管理页重复错误收敛。
- [x] 3.6 分类/投资：删除确认点名、禁用删除中性色、投资筛选空态与清除动作。

## 4. 构建

- [x] 4.1 已修改 `CashCurrentRecordPanel`、`CashImportTaskScreen/Preparation/Preview/Processing/ReviewList`、`CashLedgerScreen`、`CashDetailRelations`、`CashCategoryScreen`、`InvestmentScreens`、`AccessScreens`、`AuthResponsiveShell`、`WorkspaceScreens`、`WorkspaceManagementViewModel`、`ManagementLayout`、`SessionConnection`、`FeatureComponents` 及中英文文案资源；未新增组件依赖或业务写入路径。

## 5. 审查

- [x] 5.1 复核最终 diff：计划确认只复用既有 `CashMaintenanceConfirmation` 与固定操作栏；没有新增 Snackbar 唯一载体、重复主操作或第二个视觉系统；未改变写入请求、鉴权、存储与导入预算。
- [x] 5.2 已由独立上下文执行 `$ui-reuse-review`（基线 `HEAD=907fe4c2`，21 个改动文件）。主要发现：进度语义手写且把“Applying…”用于只读加载；忙碌按钮三处重复；关联弹窗自建模态头；错误文案用中文字符串拼接；`ManagementLayout` 的 `blocked` 与调用方判定重复；计划确认条件算了三处；金额错误提示三处；`AuthFormContent` 的字段错误在父子各门控一次。已修：linear 进度改复用 `TaskProgressIndicator`；只读加载改用 `copy_fcabadb2a7`；新增 `BusyButtonLabel` 替换三处；关联弹窗头改 `ModalHeader`；账本错误文案改参数化资源 `ledger_action_failed`；删除管理页死分支；计划条件收敛为 `planReady`；金额提示提 `invalidAmountSupportingText`；`AuthFormContent` 改为直接传 feedback、删除 `showFieldErrors`。未采纳：圆形进度未统一为共享组件（保留各调用点尺寸/修饰差异）；关联宽选择弹窗未并入 `CashRecordModalPanel`（需要侧板设计，已列入未修项）。契约专项不适用：未改数据库、DTO、请求/响应或后端接口。
- [x] 5.3 独立设计复核结论 `0 critical · 5 major · 4 minor`。已修 major：分类管理不再被 workspace 失败态连带阻塞；关联移除改 `TextButton + error` 文字，不用错误填充；计划确认按钮接入 `BusyButtonLabel`；导入待核对数归零时保留“全部”出口；投资清除筛选升为 `FilledTonalButton`。已修 minor：选择项可访问名称补日期/金额；导入提示按 `source` 清理；去掉“查看全部待处理”的对勾。未采纳 minor：投资账户/币种空态的逐条件清除动作（需要按生效条件生成清除目标，留待表格响应式方案一起处理）。

## 6. 测试与 QA

- [x] 6.1 `./gradlew :shared:wasmJsTest --no-configuration-cache`：BUILD SUCCESSFUL（最终一轮 `:shared:wasmJsTest :webApp:wasmJsBrowserDistribution` 合并 1m50s，2026-11-21）。
- [x] 6.2 `./gradlew :webApp:wasmJsBrowserDistribution --no-configuration-cache`：BUILD SUCCESSFUL（最终一轮合并构建 1m50s）。
- [x] 6.3 Web 冒烟：Chrome for Testing（Playwright chromium-1234），1440×900，URL `http://127.0.0.1:5186/`（demo/offline）。账本 14 行可见；打开详情；持仓、投资事件、管理工作区均可加载；`failedRequests` 为空，控制台仅有既有 `wasmExports` 弃用提示。截图在仓库外 `/tmp/page-{ledger,holdings,events,management}.png`。弹层关闭后画布空白在干净 `HEAD=907fe4c2` worktree 同样复现，确认是既有 Web 渲染问题，不是本次改动引入；因此未宣称弹层关闭视觉通过。
- [x] 6.4 未运行：Android/iOS 设备或模拟器交互（默认范围只做共享编译/单测）；非 demo API 环境的真实后端联调；弹层关闭的单独 Web 渲染排查；超限预览常驻原因的实机触发（demo 无超限状态）。残余风险：上述既有 Web 弹层问题、未触发的详情计划确认真实交互（demo 无可用非关联计划）、以及未覆盖的导入/分类次要状态。

### 审查发现处置

已完成（blocker/major，且可安全落地）：

| 问题 | 处置 |
|---|---|
| 详情中问题处理/草稿保存生成计划后无确认入口 | 非编辑、非关联态统一渲染计划确认与固定底栏 |
| 导入无文件时唯一动作是弱描边、禁用“下一步”占主位 | 空文件时“添加文件”提升为 Filled，隐藏“下一步” |
| 导入动作提示会漂入账户表单/处理明细 | 进入子任务时清理主任务提示；带动作提示补关闭入口 |
| 导入主操作缺规格要求图标 | 确认导入、结果页动作补 `ic_check`/`ic_arrow_forward` |
| 账本筛选/完整刷新时闪现假空状态 | 首屏 `refresh` 加载中显示骨架，不显示空态 |
| 金额筛选错误只靠颜色 | 最小/最大金额补字段旁原因 |
| 批量选择 Checkbox 无可访问名称 | 补 `contentDescription`；长按入口仍按规格保留 |
| 紧凑筛选底部弹层多余关闭图标 | 移除自定义关闭入口，保留拖拽/遮罩/返回 |
| 关联弹窗无 pane title、未避让安全区、标题留白 16dp | 补 `paneTitle`、`safeDrawing`，compact 改为 24dp |
| 移除关联最终确认仍是普通主色 | 最终确认使用 error/onError，取消保持普通色 |
| 未命名进度控件 | 账本追加、导入预览/处理/核对、关联证据/搜索补无障碍名称 |
| 认证/接受邀请忙碌时丢失业务名称 | 保留“登录/注册”“接受邀请”文字并内联 spinner |
| 认证壳和安全区 | 认证滚动容器补 `safeDrawing` + `imePadding` |
| 登录字段错误只在底部 | 登录与注册统一字段错误态 + 首错聚焦；底部只留跨字段/服务端错误 |
| 管理工作区加载/失败按分区重复渲染 | 页面级只渲染一次状态，分区内容在未就绪时跳过 |
| 移除成员无危险色 | 仅在可用时使用错误色，禁用保持中性 |
| 分类删除确认不点名对象 | 所有影响分支先显示“确认删除「名称」” |
| 有子分类的禁用删除仍是红字 | 禁用时使用 `LocalContentColor` 中性色 |
| 投资筛选无匹配时显示“当前没有持仓” | 区分筛选空态并提供“清除筛选” |
| 改名保存中静默无效、刷新覆盖输入 | 独立 `savingName` 状态、保存按钮内联进度；`load()` 保留未保存草稿 |

未在本轮修改（需要单独产品或架构决策，已记录）：

- 工作区创建/成员写入结果未知时的自动核实与恢复协议（涉及后端幂等/刷新合同）。
- 首次上传建单结果未知时的恢复入口（`uploadCreationUnknown` 已有保护，但没有安全恢复动作）。
- 关联选择弹窗宽度超过 560dp 的基本对话框上限（需要改侧板/宽容器设计）。
- 现金新建/编辑/草稿的字段级错误映射与首错定位（需要跨多个输入组件重构）。
- 批量分类进度从页面顶部移动到逐项对象（需要调整 ViewModel 进度模型）。
- 投资表格金额列对齐/等宽数字与 1024dp 断点（需要表格响应式方案）。
- 投资术语统一等 minor 项。
- Web 弹层关闭后画布空白：在干净 `HEAD=907fe4c2` 上复现，属于既有 Compose Web `Dialog` 卸载/重绘问题；本轮不把它混入 UI 反馈修复，已另建 `fix-web-modal-close-blank`。

## 7. 发布

- [x] 7.1 功能地图无需新增页面或能力，未改现状说明。用户本轮明确 OpenSpec spec/change 的明文特例不自动生效，需逐条交用户判断；据此把导入超限从 Snackbar-only 改为文件区常驻原因，更新 `cash-import-session` delta，并把“spec/change 明文特例需交用户判断”写入 `AGENTS.md`。除此之外无新增规范条目。
- [x] 7.2 已提交 `06882f0f`、`9de8d50c`、`955d0222` 并推送 `origin/civil-seal`（新分支，已设置 upstream）。用户复核 A1–A3、O1–O19 后决定全部保留、不回退；前后对比材料在 `compose/build/ui-compare/`（构建产物，不提交）。

## 8. 追加范围（未落地）

以下四项已登记到本 change，但当前不实现、不提交；待用户明确开始后再进入实施与验证。

- [ ] 8.1 导入核对空态细分：区分“本次没有可核对记录”和“当前筛选没有记录”，后者保留清除筛选出口。
- [ ] 8.2 账户映射可访问名称：来源账户标题合并进“目标账户”选择控件的可访问名称，读屏能区分每个来源账户。
- [ ] 8.3 批量分类弹窗影响文案：展示选中记录数、目标分类及不可修改/跳过原因，提交前可确认影响。
- [ ] 8.4 追加失败提示位置：把 `appendError` 从页面顶部移到列表末尾，提供行内重试，不与整页加载失败混淆。
