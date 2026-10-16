## 1. 思考与范围确认

- [x] 1.1 读取主规格、项目上下文、术语词表、UI 规则和当前映射实现；由使用者确认 Web/Native 菜单、摘要、共享草稿编辑及验证范围。
- [x] 1.2 完成 Cross-platform Impact Check：记录 Web、Native、shared 与旧 React 的影响和验证边界。

## 2. 计划与规格

- [x] 2.1 创建独立 change，记录已确认合同、目标、非目标、信息顺序、移除的重复字段、Hallmark 方向、自查及回滚策略。
- [x] 2.2 添加跨端 presentation delta，覆盖弹出菜单、现有账户只读摘要、新账户摘要、共享草稿提交和取消语义。
- [x] 2.3 运行 `openspec validate compose-cash-import-account-mapping-redesign --strict`、`openspec validate --all --strict` 和 `openspec doctor`；修复本 change 引入的问题。

## 3. 失败用例先行

- [x] 3.1 扩展 Playwright 导入扫描 fixture，提供多个来源组、现有账户及币种缺失场景，并新增回归：下拉菜单覆盖卡片且展开前后卡片矩形不变。
- [x] 3.2 更新回归：所有账户菜单项和已选控件展示名称、类型、币种；现有账户和共享草稿不再在控件下重复展示摘要。
- [x] 3.3 新增回归：共享草稿通过 Material 3 对话框编辑后所有引用行同步；取消编辑不改变共享草稿。
- [x] 3.4 在当前 Web 演示流程上运行上述用例，确认它们因待实现行为失败，并记录使用的构建/预览版本与结果。
- [x] 3.5 新增回归：选择「创建新账户」后控件继续显示固定标签，当前行不列出自己的草稿选项；下方仅创建行显示推导摘要和「修改」，再次选择时不重置草稿。
- [x] 3.6 在 Compact Web 上运行更新后的回归并确认旧实现失败，记录新合同的红灯证据后进入实现。

基线结果：2026-10-16，在 HEAD `6b24df13100a91dd69afe5d8fba36f2f71703e3e` 执行 `npm run test:e2e -- tests/compose-pages.e2e.ts -g "F-05 账户映射菜单、摘要和共享草稿在三种窗口保持一致"`。Playwright Chromium 使用 `390×844` 首次迭代进入 `/w/workspace-1/cash-import`；用例因预期的新语义定位 `import.mapping.card.existing-group` 尚不存在而失败。页面证据已确认 fixture 进入映射步骤、显示三个来源组和「对应账户」控件；失败符合旧实现缺少映射卡片语义标识的预期。Playwright 每次运行均通过仓库预览脚本执行生产 Wasm freshness/build gate；无测试完成结论。

展示合同扩展后的红灯结果：2026-10-16，在 `HEAD` `6b24df13100a91dd69afe5d8fba36f2f71703e3e` 的脏工作树（此前已有部分 UI 实现）运行同一 Playwright 命令。预览脚本完成生产 Wasm freshness/build gate；Chromium 在 `390×844` 的第一组账户菜单断言失败，菜单只有占位、账户名称和创建选项，缺少「现金账户」和币种；后续创建账户/共享草稿断言因此未执行。该次仅作为更新合同的失败基线，不算验证通过。

## 4. Compose 实现

- [x] 4.1 重排映射行内容：Compact 纵向呈现来源和目标选择；Regular/Wide 保留来源与目标并列。仅创建新账户的行在下方呈现推导摘要。
- [x] 4.2 为页面目标账户选择器启用 Web 锚定菜单；编辑模态复用次级页面 overlay host，账户类型选择器使用 Material 3 `ExposedDropdownMenuBox` / `ExposedDropdownMenu` 跨端弹出。
- [x] 4.3 现有账户与共享新账户草稿的下拉选项及已选控件显示名称、类型、币种；不在下方重复显示账户摘要。现有账户继续显示币种缺失提示。
- [x] 4.4 「创建新账户」保持固定选中标签，排除当前行草稿选项；创建行下方显示推导名称、类型、来源币种和「修改」。复用共享草稿时在已选控件显示信息并保留编辑入口。
- [x] 4.5 增加新账户 Material 3 样式与对话框语义的模态面板：本地编辑名称和类型，币种只读；「完成」复用 `updateNewAccount` 同步共享草稿，「取消」丢弃局部编辑。
- [x] 4.6 在四份 Compose 字符串资源中补齐一致的对话框标题、编辑标签和操作标签，并运行新增浏览器回归直至通过。

## 5. 范围与设计复核

- [x] 5.1 复核 diff 与 OpenSpec 合同，确认未改变映射选项、币种扩充、预览/确认时机、业务层、API、数据库或旧 React 页面。
- [x] 5.2 做用户可见文案必要性与术语搜索；逐项复核摘要、选择器、模态的无障碍名称和稳定语义 ID。
- [x] 5.3 按 Hallmark `audit` 技能审查实际 Compose 页面；记录范围、critical/major/minor finding、处置理由和结论。未发现命名反模式，critical 0、major 0、minor 0；预留空间用于账户类型菜单展开，菜单不遮名称字段或底部操作。
- [x] 5.4 独立对照 OpenSpec 与浏览器截图复核信息层级、遮挡、换行、颜色 token 和三尺寸结构；补齐菜单位置与对话框焦点发现后回归复核。

Hallmark 审查范围：`CashImportScreen.kt` 的账户映射卡片、账户选择菜单和新账户编辑模态，以及 `SecondaryPageContainer.kt` 的 overlay host；对照 Compact、Regular、Wide 下默认映射、创建摘要、共享草稿和编辑菜单截图。页面围绕账户映射任务组织，无居中 Hero/三列功能卡模板、卡片套卡片、一次性颜色、无意义装饰或可见重复摘要。名称、类型、币种沿用 `FinanceTheme`/Material 3 角色；模态中预留的纵向空间只承担类型菜单弹出层，Escape/遮罩关闭和底部操作均可用。审查结论：0 critical · 0 major · 0 minor；无需修复。

## 6. Web 浏览器 QA

- [x] 6.1 运行仓库 Compose Web Playwright 用例，并确认启动器使用当前代码构建产物；记录浏览器、URL、HEAD、基线、命令和执行时间。
- [x] 6.2 在 Chrome `390×844`（COMPACT）、`768×1024`（REGULAR）、`1440×900`（WIDE）走通新建映射、完整账户菜单/已选值、币种缺失、共享草稿、完成/取消编辑、类型菜单 Escape、模态 Escape 和遮罩关闭路径。
- [x] 6.3 保存每尺寸的默认映射、账户/共享草稿菜单、创建新账户摘要、编辑对话框及账户类型菜单截图到 `compose/qa/openspec/changes/compose-cash-import-account-mapping-redesign/screenshots/`；记录控制台/网络错误、卡片矩形比较、溢出与无障碍结果。
- [x] 6.4 运行受影响 Playwright 回归、生产 Compose Web 构建新鲜度门禁及 `git diff --check`；按用户范围不运行 Android/iOS，也不运行非 Web 测试。

浏览器证据（2026-10-16 18:16 CST）：基线 HEAD 与最终 HEAD 均为 `6b24df13100a91dd69afe5d8fba36f2f71703e3e`，测试在该 HEAD 的工作树改动上运行；浏览器 Chrome/Chromium，Playwright freshness/build gate 使用动态本地端口，页面路径 `/w/workspace-1/cash-import`，测试结束后预览进程退出。命令：`npm run test:e2e -- tests/compose-pages.e2e.ts -g "F-05 账户映射菜单、摘要和共享草稿在三种窗口保持一致"`；最终结果 1 passed（26.7s），生产 Wasm freshness/build gate 通过。视口：390×844 COMPACT、768×1024 REGULAR、1440×900 WIDE。检查现有账户完整选项与已选值、币种缺失提示、创建账户固定选项与推断摘要、共享草稿复用与完成后同步、取消/Escape 丢弃、类型菜单 Escape 只收菜单、模态 Escape/遮罩关闭、目标卡片菜单前后几何不变、菜单位于名称与底部操作之间，以及页面无水平溢出。浏览器 console errors、failed requests、静态 HTTP 错误均为空；Wasm 响应类型为 `application/wasm`。Webpack 标记大型 Wasm/Skiko 资源并输出 4 条构建警告；本次未改动构建配置或资源。截图在上述目录，共 28 张：三个尺寸各九种回归场景，另有 `compact-mapping.png`。

## 7. 演示评审与发布准备

- [x] 7.1 在 Compose QA 演示流程提供 Compact、Regular、Wide 实际页面与截图供评审；记录演示 URL、状态和证据。本 change 是导入账户映射的局部表单交互调整，不属于重大页面重构。
- [x] 7.2 记录发布/回滚准备：本 change 无数据迁移；回滚范围为共享 Compose 页面、字符串资源、Playwright 用例、功能地图与 OpenSpec。
- [x] 7.3 2026-10-16 18:16 CST 最终复跑 `openspec validate compose-cash-import-account-mapping-redesign --strict`、`openspec validate --all --strict`（33 passed）和 `openspec doctor`；`git diff --check` 通过。最终 HEAD/基线均为 `6b24df13100a91dd69afe5d8fba36f2f71703e3e`，未提交；Native 运行时验证按用户确认范围不执行。

## 8. 反思

- [x] 8.1 记录复发防护结论：共享 Compose 的 Web anchored menu 与 Native Material 3 menu 在跨端合同中共用值/标签语义；三尺寸 Web 回归覆盖菜单位置、模态边界、动作可见性与关闭路径。

## 9. 编辑弹窗高度修正

- [x] 9.1 更新 proposal、design 与 presentation delta：共享 Compose 的所有 `ChoicePicker` 统一遵循锚定弹出菜单合同；导入编辑类型字段复用该组件；弹窗高度随表单内容。
- [x] 9.2 扩展 Playwright 回归，验证共享 ChoicePicker 的锚定字段、弹出菜单、布局尺寸稳定，以及编辑对话框收缩和类型菜单关闭顺序；先记录旧实现失败。
- [x] 9.3 以只读文本框锚定的 Material 3 菜单实现共享 `ChoicePicker`，替换全部平台分支和自定义菜单路径；编辑账户类型改回复用 `ChoicePicker`；移除固定高度和加权空白。
- [x] 9.4 在 Compact、Regular、Wide 覆盖账本、流水编辑、分类、导入、工作区与投资中的代表性选择字段，重跑受影响 Web 流程并更新截图；不运行 Native。
- [x] 9.5 对最终 Compose 页面执行 Hallmark audit 或按技能参考文档完成替代人工审查；记录影响页面、finding、处置和结论。

实施边界：共享 `ChoicePicker` 的全部 35 个调用点统一变更；FAB、导航和命令型动作菜单保持原实现。`ChoicePicker` 原有标签、选项、语义 ID、禁用状态和选择回调必须保留。Native 运行时 QA 根据使用者此前确认仅验证 Web 的范围不执行。

## 10. 导入页夜间主题与操作图标

- [x] 10.1 扩展 proposal、design、presentation delta 和功能地图：导入全流程沿用当前 FinanceTheme；主要操作保留标签并补充图标；主页导入 FAB 使用向内收件语义图标。记录 Web/Native/shared 影响及仅 Web 验证边界。
- [x] 10.2 在 Playwright 中新增夜间主题与主要操作标签/截图回归；对图标方向通过浅色/夜间浏览器截图人工审查，不依赖 Compose 合并语义后的图标子节点 ID；先记录当前界面的失败证据。
- [x] 10.3 移除导入路由和导入页的强制浅色主题、浅色专属 token；以当前 Material 3/FinanceTheme 角色实现主要操作按钮的图文组合，并将主页导入菜单图标替换为收件/导入语义图标。
- [x] 10.4 在 Chrome 浅色与夜间模式下覆盖 COMPACT（390×844）、REGULAR（768×1024）、WIDE（1440×900）的文件选择、映射、编辑弹窗、预览/关系审查和完成状态；记录对比度、操作标签/图标、溢出、控制台/网络错误并保存截图。
- [x] 10.5 更新 F-05 功能地图；对最终浅色/夜间 Compose 页面按 Hallmark audit 或按技能参考文件完成替代人工审查，记录 finding 和处置；执行适用 OpenSpec 校验及 `git diff --check`，不运行 Android/iOS。

### Cross-platform Impact Check

Cross-platform Impact Check：Web 改动影响 Compose Web 的账单导入所有阶段、弹出层及收支账本导入 FAB，并执行浅色/夜间三尺寸浏览器 QA；Native 共享相同 `commonMain` 主题和图标实现、保持既有平台行为，但依使用者范围不执行 Native 运行时验证；Shared 改动涉及 `App.kt`、`CashImportScreen.kt` 与 `CashLedgerScreen.kt`，不改变导入状态、业务流程或数据；Legacy React/Expo 保持不变。

新增导入返回刷新需求后的 Cross-platform Impact Check：Web 与 Native 共用成功标记、返回路由和 `CashLedgerViewModel` 刷新版本，因此两端成功确认后回到账本均会重新读取当前列表、汇总和引用；只运行用户指定的 Web 浏览器回归，Native 运行时 QA 不在范围内；Legacy React/Expo 不变。

新增连续滚动需求后的 Cross-platform Impact Check：Web、Android 与 iOS 共用 `commonMain` 分页源、`LazyPagingItems`、服务端游标、筛选/刷新与错误语义；Compose Web 会在 Chrome 三种窗口验证滚动续页、追加重试与重置；按使用者要求不运行 Native 运行时 QA。Paging Compose 是共享客户端依赖且官方提供 KMP Web/iOS 目标；不改旧 React、API 或服务端分页合同。

新增桌面表格对齐需求后的 Cross-platform Impact Check：Web、Android 与 iOS 共用 `CashLedgerScreen` 行顺序、交易对方/备注文案结构和响应式规则；Web QA 检查 `COMPACT`、`REGULAR`、`WIDE`，Native 运行时验证依使用者明确范围不执行；旧 React Web 仅作列顺序与比例参考，不修改其实现。

## 11. 导入成功后刷新收支账本

- [x] 11.1 在 proposal、design 和 `cash-ledger/cash-ledger-browser` delta 中记录成功后返回时刷新流水、汇总及引用数据、保留当前筛选；未确认离开不刷新。记录 shared/Web/Native 影响和仅 Web 验证范围。
- [x] 11.2 扩展 Playwright 完整导入流程，使确认导入后返回列表出现仅在提交后提供的新流水，并记录当前实现的失败结果。
- [x] 11.3 用成功完成回调建立一次性返回刷新信号；重载当前筛选、汇总和账户/分类引用；删除导入后仅为焦点恢复而存在的特殊处理。
- [x] 11.4 在 Chrome Web 中确认成功后返回可见新流水、筛选保留且列表/汇总重新加载；确认未确认离开不会触发成功返回刷新。
- [x] 11.5 更新功能地图和浏览器证据，完成 OpenSpec 校验、`git diff --check` 与最终范围审查；不运行 Android/iOS。

夜间主题/图标回归红灯（2026-10-16 18:52 CST）：在 HEAD `6b24df13100a91dd69afe5d8fba36f2f71703e3e` 的工作树改动上，从 `compose/qa/web/` 运行 Playwright Chromium。夜间文件选择页截图像素平均亮度为 253（验收上限 100），证明导入路由强制浅色主题；主页「导入账单」旧图标资源是 `ic_file_upload`，方向表现为导出。初版测试尝试定位 FAB 内图标的独立语义 ID，但 Compose 将其合并到菜单项语义节点，因此没有把此定位失败当作视觉证据；后续以按钮标签断言和真实截图复核图标方向。第一次启动被当前新账户下拉的键事件导入与作用域调用编译错误挡住，修复后预览通过生产 Wasm freshness/build gate。

## 12. 收支账本连续滚动加载

- [x] 12.1 将自动游标续页合同、筛选/刷新重置、追加错误重试和 Web/Native/shared 影响写入 proposal、账本 delta、design 与 Cross-platform Impact Check；确认 Paging Compose 稳定版提供项目所需 KMP 目标，并登记新增客户端依赖风险。
- [x] 12.2 增加多页账单 API fixture 与 Playwright 回归：滚至接近末尾自动请求下一页、页面顺序/去重、追加失败保留已有行并重试、切换筛选回到顶部；先记录旧界面的失败结果。
- [x] 12.3 在 `commonMain` 接入 Paging Compose 分页流与游标 `PagingSource`，保留筛选隔离、投影版本失效恢复、月度汇总、导入成功/账本写入后的刷新语义。
- [x] 12.4 将账本外层滚动与流水渲染改为单个 `LazyColumn`/`LazyPagingItems`，将筛选、月度摘要、紧凑卡片/桌面表格行、首屏状态与分页尾部放入同一虚拟列表，移除正常状态下的手动「加载更多」按钮。
- [x] 12.5 更新或替换依赖旧 ViewModel `rows`/`loadMore` 状态的实现验证，确保首次加载/重试、筛选变化、成功导入返回、记录编辑/分类/删除后刷新不会混入旧页或丢失月度汇总。
- [x] 12.6 运行 Compose Web Playwright 与生产 Wasm freshness/build gate；在 Chrome `390×844`、`768×1024`、`1440×900` 验证正常续页、追加失败重试、筛选重置、布局溢出与导入返回刷新，保存截图/诊断证据；不运行 Android/iOS。
- [x] 12.7 更新 F-05 功能地图与最终人工 Hallmark 审查记录，复核与 OpenSpec 一致、依赖/变更范围、`git diff --check`、`openspec validate --all --strict`、`openspec doctor` 及未运行 Native 的残余风险。

## 13. 收支账本表格列与交易信息

- [x] 13.1 将旧 React Web 桌面列顺序、选择模式列宽、交易对方/备注两行规则及 Compact 呈现补入 proposal、design、cash-ledger delta、Cross-platform Impact Check 和 F-05 功能地图。
- [x] 13.2 添加 Web 回归，覆盖三种窗口的交易对方/备注顺序、Regular/Wide 表头列顺序及无横向溢出；先在旧实现记录失败结果。
- [x] 13.3 将 Regular/Wide 表格列顺序和比例对齐旧 React Web；同一交易信息列依次显示交易对方及备注，Compact 卡片也将备注紧接交易对方。
- [x] 13.4 使用 Chrome 在 `390×844`、`768×1024`、`1440×900` 复核三类布局、列位置与行内容；保存最终截图及无溢出/控制台/网络证据。
- [x] 13.5 对照最终页面完成 Hallmark audit 或技能参考文档替代人工审查，记录 finding/处置，执行 OpenSpec、`git diff --check` 和最终差异复核；不运行 Android/iOS。

## 最终验收证据（2026-10-17）

- HEAD 与比较基线均为 `6b24df13100a91dd69afe5d8fba36f2f71703e3e`；本 change 未提交。Chrome/Chromium 通过仓库 Playwright 启动器运行生产 Compose Web 预览，使用动态本地端口并在结束后关闭；页面为 `/w/workspace-1/` 与 `/w/workspace-1/cash-import`，主要视口为 `390×844`、`768×1024`、`1440×900`。
- 最终完整命令 `npm run test:e2e`（目录 `compose/qa/web`）通过 `67 passed (8.0m)`，包含当前 Wasm freshness/build gate。覆盖账本筛选和详情、六页面 ChoicePicker 三尺寸、自动续页/顺序去重/追加失败重试/筛选重置、交易对方与备注两行顺序、Regular/Wide 表头列序和 5/12/15/24/12/11/13/8% 比例、导入浅色/夜间三尺寸完整流程、提交后账本/汇总/账户/分类刷新及未确认离开不刷新。
- 最终截图回归：`FT_CAPTURE_COMPOSE_SCREENSHOTS=1 npm run test:e2e -- tests/compose-pages.e2e.ts -g '各尺寸账单行按交易对方、备注顺序显示|Regular 与 Wide 表格列顺序和 Web 保持一致'`，`2 passed (8.6s)`。交易行截图为 `screenshots/ledger-counterparty-note-{compact,regular,wide}.png`；桌面表格截图为 `screenshots/ledger-table-columns-{regular,wide}.png`。导入浅/夜间六组流程截图位于 `screenshots/` 与 `screenshots/dark/`。
- Compose Web QA 启动器测试 `npm run test:node` 通过 3 项。完整浏览器套件对 console error、failed request、静态 HTTP error 和 Wasm MIME 作断言；全部通过，无浏览器错误、失败请求或静态资源错误，Wasm 响应为 `application/wasm`。生产打包报告 4 条大型 Wasm/Skiko 资源警告；它们是体积提示，不影响预览或回归。
- 基线红灯证据（临时 detached worktree，随后已清理）：旧实现缺少自动 cursor 请求和追加失败重试；交易信息没有备注第二行；表头仍为旧列标签；确认导入后回到账本没有重新读取 projection。夜间文件选择页修复前标题区域最大亮度仅 25（目标需大于 160），修复后 `F-05 账单导入文件选择页适配夜间主题` 与浅/夜全流程回归通过。
- Hallmark：按仓库 `.agents/skills/hallmark` 的 audit 参考清单人工复核收支账本、流水详情、新建/编辑、导入各阶段，以及账本、流水、分类、导入、工作区和投资中的代表性 `ChoicePicker`；检查 compact/regular/wide、浅/夜主题、信息层级、列布局、文字对比、操作语义、溢出和状态。发现的夜间文件选择标题低对比已修复并加入亮度回归；其余页面无未处置的 critical、major 或 minor finding。该项按技能参考文档驱动的人工 audit 记录。
- Cross-platform Impact：所有代码使用共享 `commonMain`，因此 Native 逻辑和呈现也受影响；按用户明确要求，本 change 只完成 Compose Web 验证，未运行 Android/iOS。未改旧 React 页面、API、数据模型或迁移。
- 旧 React `web/` 的 Vitest/build 补充检查未能运行：此 worktree 未安装该目录依赖，`npm test` 和 `npm run build` 分别因 `vitest`、`tsc` 命令缺失未启动。`npm ci` 在 npm 11.19.0 与 npm 10.9.2 下均被现有 lockfile 的 Rollup 平台可选依赖版本问题阻断；`npm ci --install-strategy=nested` 报告 lockfile 的 `@rollup/rollup-linux-loong64-musl` / `riscv64-gnu` 版本与 `4.63.5` 不一致。未改旧 Web lockfile；Compose Web 的生产 Wasm 构建及 67 项 Playwright 回归已通过。
- OpenSpec：`openspec --version` 为 `1.7.0`；`openspec validate compose-cash-import-account-mapping-redesign --strict` 通过，`openspec validate --all --strict` 为 33 passed，`openspec doctor` 报 Root / OpenSpec root 正常，`git diff --check` 通过。
- 发布准备：无数据迁移。回滚范围为本 change 的共享 Compose 实现、字符串资源、依赖声明、Web QA、功能地图与 OpenSpec；未提交、未推送。

### 提交前复验（2026-10-17）

- 首次提交后的全量复跑在旧 ChoicePicker 测试定位上遇到语义镜像节点仍可见的问题；该 DOM 镜像位于透明无指针层，不能用 `toBeHidden()` 判断 Material 菜单的实际画面状态。将回归改为选择后截图，再按 Escape 并比较画面；三尺寸截图变化率低于 0.01，且未捕获 `RootNodeOwner is already disposed` 页面异常。选择回调顺序保持原实现。
- 关系筛选宽度用例暴露文件读取异步竞态：两个文件的行在列表出现前，脚本已派发“下一步”点击。回归等待两个文件名显示且按钮启用后再扫描；目标用例通过。
- 修订后于 `HEAD` `1c16dea`（比较基线 `6b24df1`，测试文件包含工作树回归修订）运行 `npm run test:e2e`（`compose/qa/web`），Playwright Chrome/Chromium 生产预览最终 `67 passed (6.8m)`，包含生产 Wasm freshness/build gate；覆盖全部 67 项及 COMPACT/REGULAR/WIDE 交互。未运行 Android/iOS，符合使用者限定的 Web 验证范围。
- 复验后运行 `openspec validate --all --strict`（32 passed）、`openspec doctor` 与 `git diff --check`，均通过。

## 14. 规格同步与归档

- [x] 14.1 将账本成功导入返回刷新、自动游标续页、桌面列顺序及交易信息两行规则同步到 `cash-ledger/cash-ledger-browser` 主规格；将共享 ChoicePicker、账户映射摘要、导入主要操作图标同步到 `shared/cross-platform-presentation` 主规格。
- [x] 14.2 修订账单导入的完整主要求及 delta：移除“强制浅色”合同，统一为跟随当前 FinanceTheme，并保留已有布局、关系与模态交互场景；新增夜间主题可读性场景。
- [x] 14.3 严格校验变更与全体 OpenSpec（33 passed）、运行 `openspec doctor` 和 `git diff --check`；全部通过。
- [x] 14.4 将 change 归档至 `openspec/changes/archive/2026-10-17-compose-cash-import-account-mapping-redesign/`，并更新 `docs/feature-map.md` 的证据链接。
- [x] 14.5 归档后复核：`openspec validate --all --strict` 通过（32 passed），`openspec doctor` 与 `git diff --check` 通过；61 项任务全部完成，功能地图 3 处归档证据链接有效，活动 change 目录已移除。
