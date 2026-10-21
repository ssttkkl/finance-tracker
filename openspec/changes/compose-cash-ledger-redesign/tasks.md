## 1. 思考

- [x] 1.1 阅读项目上下文、账本/跨端主规格、术语词表、active changes、当前 Compose 代码和旧 React Web 参考。
- [x] 1.2 通过 `grill-me` / `grilling` 确认目标、范围、非目标、操作、跨端范围、响应式布局和验收；记录用户最终确认。
- [x] 1.3 补充确认搜索复用现有交易对方筛选；固定快捷项为「全部／支出／收入／转账」，分类只留在详细筛选；Compact 使用 Bottom Sheet，Regular/Wide 使用模态弹窗。
- [x] 1.4 记录用户补充确认的选择与操作菜单合同：Compact 隐藏默认复选框并长按选中；Regular/Wide 保留行复选框；移除全选当前已加载；所有尺寸统一 FAB 菜单；分类在对话框内应用。
- [x] 1.5 用户确认主要动作增加图标且保留文字；删除、解散关联及删除确认按钮使用危险色。
- [x] 1.6 结合支付宝与微信详情参考，确认摘要区、标签/值字段分组及详情内联关联折叠区；用户明确排除详情面板内嵌套弹层。
- [x] 1.7 用户确认详情卡片的淡紫色需要修正，金额颜色维持现状。
- [x] 1.8 用户确认多选后 FAB 自动展开并切换多选图标，已选数量移至 FAB 左侧提示胶囊；筛选区独立固定，各月份流水使用分开的视觉容器。
- [x] 1.9 用户确认详细筛选使用「时间」「金额」「分类」「账户」「其他条件」五组常驻标题；金额组包含币种与范围，其他条件包含资金转移类型和合并状态，Compact 纵向排列、较宽窗口组内并排，筛选行为不变。
- [x] 1.10 用户确认 FAB 在所有窗口叠加列表且末条可滚到 FAB 上方；月份汇总及桌面列标题在固定筛选卡下方固定并由下月顶替；`REGULAR` / `WIDE` 行内容缩进 16 dp、分隔线铺满卡片。
- [x] 1.11 通过 `grill-me` / `grilling` 澄清完整刷新、初次加载占位、失败状态、分页追加、下拉手势范围及多选限制；用户确认所有完整刷新立即废弃旧流水/月份汇总，初次和后续刷新使用适配布局的骨架，失败显示错误/重试，分页追加保留内容，下拉刷新适用于所有平台与尺寸且仅列表顶部可触发，多选期间禁用手势。
- [x] 1.12 记录用户补充的滚动条合同：Compact 也将窄轨道和滑块贴齐列表右边缘，40 dp 热区叠加且不挤占宽度；滚动时显示、停止 2 秒后渐隐；Regular/Wide 常驻显示。

## 2. 计划

- [x] 2.1 创建 proposal、账本浏览 delta 和设计说明；明确任务、信息架构、删除的冗余信息、Hallmark 方向、自评和回滚方式。
- [x] 2.2 完成 Cross-platform Impact Check：Web = Compose Web 共用实现，需实施并浏览器验证；Native = Android/iOS 共用实现，需复核响应式与系统返回；共享层 = `commonMain` 的账本、详情和编辑 UI 受影响；旧 React Web 仅参考，不修改。
- [x] 2.3 更新筛选信息架构与行为合同：顶部搜索、固定四类类型快捷项、分尺寸详细筛选容器；不新增筛选字段、搜索规则或 API。
- [x] 2.4 更新 proposal、账本浏览 delta、design 和本 tasks，明确跨尺寸 FAB 菜单、长按选择、已选数量、无全选入口和分类对话框的验收合同；`openspec validate compose-cash-ledger-redesign --strict` 通过。
- [x] 2.5 补充图标和危险色合同；Cross-platform Impact Check：Web = Compose Web 共用页面，需浏览器复核；Native = Android/iOS 共用 `commonMain` 实现，需三尺寸与主题复核；共享层 = 图标资源、按钮样式和语义受影响；旧 React Web 不变。
- [x] 2.6 更新详情行为合同：金额/对方/时间摘要在卡片外；主要字段使用 Material 3 Card 和标签/值分列；关联事实通过另一张默认收起的 Material 3 Card 查看，展开后分组展示当前流水与关联流水；不引入嵌套卡片、模态、路由或业务动作。
- [x] 2.7 Cross-platform Impact Check：Web = Compose Web 卡片层级、关联折叠交互与三尺寸影响；Native = Android/iOS 同一 `commonMain` 卡片层级、折叠状态和原生触控影响；共享层 = 详情层级、关联折叠区、语义 ID 和多语言资源受影响；旧 React Web 仅作信息层级参考，不修改。本轮按用户范围只验证 Web。
- [x] 2.8 补充视觉合同：流水信息卡与关联流水卡显式使用现有 `surface.muted` 中性色，防止 Material 3 默认淡紫色渗入；金额配色不变。Web 与 Native 共用 `commonMain`，按用户要求仅复验 Web。
- [x] 2.9 用户确认将 Material 3 `surfaceContainer*` 角色映射放入全局 FinanceTheme；Web、Android、iOS 和共享层都受主题默认容器色影响，旧 React Web 不变。本轮只验证 Web，Native 运行时复验不执行。
- [x] 2.10 经用户确认，更新 proposal、账本浏览 delta、design 与 tasks：账本筛选/列表共享前景 Surface 并用分割线分区；详情摘要留在中性面板底色，信息卡/关联卡使用 Material 3 默认前景角色；颜色只映射现有 token，金额颜色不变。
- [x] 2.11 更新 proposal、账本浏览 delta 与 design，明确固定筛选容器、仅列表滚动、月度独立容器及选择模式自动展开 FAB；Cross-platform Impact Check：Web/Android/iOS 均使用共享 `commonMain` 页面，均受布局和交互影响；按用户要求仅执行 Compose Web 浏览器验证，Native 运行时未验证。
- [x] 2.12 更新 proposal、账本浏览 delta、design 与 tasks，补充五组详细筛选字段归属、常驻标题和响应式排布；Cross-platform Impact Check：Web、Android、iOS 与共享 `commonMain` 均受筛选面板信息层级影响；按用户要求只做 Web 浏览器验证。
- [x] 2.13 更新 proposal、账本浏览 delta、design 与 tasks，记录 FAB 覆盖列表、按月固定汇总/桌面表头和 16 dp 桌面行内缩；Cross-platform Impact Check：Compose Web、Android、iOS 与共享 `commonMain` 均受影响，旧 React Web 不变。
- [x] 2.14 根据用户的 Codex 滚动条参考图收窄账本右侧列表预留；`REGULAR` / `WIDE` 预留为 16 dp，滚动条轨道保持纤细，所有尺寸保留 40 dp 交互热区。`COMPACT` 覆盖列表，不额外占宽。Cross-platform Impact Check：Compose Web、Android、iOS 与共享 `commonMain` 均受影响，旧 React Web 不变；按用户范围仅验证 Compose Web。
- [x] 2.15 按用户批准更新 proposal、账本浏览 delta、design 与 tasks：完整刷新立即清除旧内容并显示适配布局的骨架，错误不恢复旧内容，分页追加保留已加载内容；所有 Compose 平台/尺寸只在列表顶部支持下拉刷新，筛选区不响应，多选时禁用手势。Cross-platform Impact Check：Compose Web、Android、iOS 与共享 `commonMain` 均受影响，旧 React Web 不变。
- [x] 2.16 更新 proposal、账本浏览 delta、design、tasks 和功能地图：所有尺寸的窄轨道及滑块贴齐列表右边缘，40 dp 交互热区覆盖列表、不改变测量宽度；Compact 滚动时显示并在 2 秒空闲后渐隐，Regular/Wide 常驻。Cross-platform Impact Check：Compose Web、Android、iOS 与共享 `commonMain` 均受影响，旧 React Web 不变。

## 3. 任务拆分与一致性

- [x] 3.1 为列表、统一面板和新增/编辑流程补充回归；先用 `:shared:wasmJsTest` 复现演示仓库无法解析 `projection-` 详情 ID，再修复并验证。浏览器 Escape 回归曾因面板被误识别为嵌套对话框而失败，修复后通过。按用户最新决定，不新增焦点请求/恢复逻辑，也不写焦点恢复断言；Escape 与 Android 系统返回仍验证。
- [x] 3.2 核对 proposal、delta、design 与任务覆盖一致；筛选、汇总、分页、批量操作、权限、凭证、关系、危险确认及 Compose 演示详情路径均保留原有实现和入口。
- [x] 3.3 扩展 Playwright 回归，覆盖 Compact 长按/点击/最后一项退出、Regular/Wide 复选框、FAB 两种菜单、分类取消/应用、流水删除确认和已关联流水危险动作；原完整 Chromium 回归 15/15 通过，本轮新增删除/关联场景在浅色与深色主题均通过。历史开发期红色基线未保留；演示仓库分类详情及关联 fixture 回归先失败、修复后通过。
- [x] 3.4 追查浏览器测试间歇性使用旧 Compose Web 包的原因；确认本地 Playwright 可能复用已有端口服务，Gradle 默认增量任务也不能单独证明实际分发产物与当前源码一致。将双重防护和失败关闭规则补入 design。
- [x] 3.5 增加固定筛选、月份分页边界连续性、不同月份容器和选择菜单自动展开的 Web 回归；先确认旧布局至少在固定筛选与自动展开断言上失败。
- [x] 3.6 为详细筛选分组标题、字段归属和三种窗口布局增加 Playwright 回归；先运行旧界面确认缺少 `ledger.filter.group.time` 等语义节点，再验证新界面在各尺寸通过。
- [x] 3.7 为 FAB 覆盖、末条可达、固定月份汇总/列标题和桌面表格内缩补充浏览器断言；旧实现首轮在 `390×844` 的列表/FAB 重叠断言失败，确认页面底部固定预留空间导致缺陷。
- [x] 3.8 为跨月头部替换增加回归；首轮发现下月汇总仍在原列表位置时固定层已提前切换，确认会重复显示同月汇总。
- [ ] 3.9 先增加并运行失败回归，证明首次/完整刷新立刻隐藏旧流水和月份汇总、展示布局匹配骨架，失败时显示错误/重试且不恢复旧内容；另证明分页追加保留既有行、下拉手势仅在列表顶部有效且筛选区/多选模式不能触发。
- [ ] 3.10 为窄滚动条增加失败优先的浏览器回归：验证 Compact/Regular/Wide 的可见轨道与滑块贴齐列表右边缘、40 dp 热区不挤占列表宽度、Compact 滚动后显示且空闲 2 秒后渐隐，以及 Regular/Wide 常驻显示。

## 4. 构建

- [x] 4.1 在 `CashLedgerScreen.kt` 重构窗口分级列表、月度分组摘要、筛选和选择操作布局；保持现有筛选字段、回调与状态语义。
- [x] 4.2 在 `CashLedgerDetails.kt` 合并新增、详情和编辑的模态外壳，重组表单与详情信息层级；保留所有关联、凭证、删除和确认操作。
- [x] 4.3 运行回归并修正失败；更新稳定语义 ID 的使用和 E2E 覆盖；修复演示仓库 projection ID 详情加载及详情分类对象未按 `categoryId` 补齐的问题，并增加先失败后通过的回归测试。
- [x] 4.4 更新 `docs/feature-map.md` 的 F-03/F-04 实现描述和证据。
- [x] 4.5 更新 `.hallmark/preflight.json` 与 `.hallmark/log.json`，记录本次 Compose 页面设计选择，不修改 token。
- [x] 4.6 将现有交易对方筛选移至顶部搜索输入，加入固定的全部/支出/收入/转账快捷项，并按窗口尺寸实现详细筛选 Bottom Sheet / 模态弹窗；分类保留在详细选项。
- [x] 4.7 更新 `docs/feature-map.md` 中 F-03 的筛选布局和跨端实现描述。
- [x] 4.8 修正 Compact 批量操作工具条布局，防止分类选择器在窄宽度下竖排。
- [x] 4.9 修正 Compose Web 详细筛选 Bottom Sheet 的 Escape 关闭，使窗口捕获阶段处理器先于弹层键盘拦截器运行。
- [x] 4.10 固定显示四个类型快捷项；移除单源详情中不可用的「解散关联」入口。
- [x] 4.11 让 Regular/Wide 的详细筛选弹窗按内容高度呈现，避免空白撑高。
- [x] 4.12 移除「全选当前已加载」入口及 Compact 默认行复选框；实现 Compact 长按选中、选择态点击切换、取消最后一笔自动退出；Regular/Wide 继续展示行复选框并显示已选数量。
- [x] 4.13 在所有尺寸将新增、导入与批量动作移入 Material 3 FAB 菜单；无选择时显示记一笔/导入账单，有选择时显示设置分类/删除/取消选择。
- [x] 4.14 将批量分类选择和应用收进 Material 3 模态对话框；取消不提交、应用沿用原分类操作，删除继续走原危险确认。
- [x] 4.15 替换搜索输入的异常图标为清晰的线框搜索图标，不新增图标依赖或搜索行为。
- [x] 4.16 更新稳定语义 ID、四套语言资源、`docs/feature-map.md` F-03/F-04 和 Hallmark 页面记录；完成 Web/Native/共享层影响检查。
- [x] 4.17 为在线/离线 Compose Web 预览增加输入与服务产物指纹、强制完整重建和构建一致性检查；Playwright 禁止复用既有服务，并通过运行器为每次 QA 隔离端口。
- [x] 4.18 为详情编辑、关联、新增关系、保存和删除等主要动作添加带文字标签的图标；批量删除、流水删除、解散关联和既有删除确认按钮使用危险色角色，不改变业务语义。
- [x] 4.19 更新 `docs/feature-map.md` 的 F-03/F-04 页面描述和跨端实现证据。
- [x] 4.20 为浏览器 fixture 补充已关联流水样本，回归解散关联与两种删除选项的危险色；更新浅色/深色三尺寸截图图库。
- [x] 4.21 将主要流水信息与关联流水改为并列 Material 3 ElevatedCard，摘要保持在卡片外；关联流水卡默认收起，展开内容留在同一卡片并分组显示当前与关联流水，避免卡片嵌套；保留既有关联操作。
- [x] 4.22 为关联折叠区新增稳定语义 ID、必要的四套语言资源和在线 fixture 状态；更新功能地图。
- [x] 4.23 为流水信息卡和关联流水卡显式指定现有 `surface.muted` token，保留详情金额颜色。
- [x] 4.24 更新 F-04 功能地图与最终 UI 审查记录，反映中性卡片颜色和 Web-only QA 范围。
- [x] 4.25 依据 Hallmark「Tabular data without tabular-nums」finding，为流水金额启用等宽数字并右对齐表格金额列；金额颜色与金额语义保持不变。
- [x] 4.26 在 `FinanceTheme.kt` 将现有浅/深色 Finance token 映射到全部 Material 3 `surfaceContainer*` 角色；不新增 token 色值。
- [x] 4.27 将账本筛选和流水表格包入共享前景 Surface，以 `outlineVariant` 分隔；详情面板使用中性高层角色，详情与关联卡使用 Material 3 ElevatedCard 默认 `surfaceContainerLow` 角色，移除组件内颜色覆盖与 tonal elevation 色调叠加。
- [x] 4.28 更新功能地图 F-03/F-04，说明全局主题表面角色及其默认卡片/弹窗影响。
- [x] 4.29 根据真实 Compose Web 截图发现普通 Card 的 `surfaceContainerHighest` 与详情面板同色；改用 Material 3 ElevatedCard 默认前景角色。Compose Web 关闭筛选后仍会保留可见几何信息的语义镜像，回归使用实际截图差异检查弹层关闭；viewer 的写入菜单语义节点也会保留在 DOM，权限回归断言其不可见。
- [x] 4.30 将筛选容器移出 `LazyColumn` 并固定于账本上方，让流水列表独立滚动；分页时按已加载行首尾连续绘制独立月份容器，不提前加载整月。
- [x] 4.31 选择模式进入时自动展开 FAB 菜单并使用多选图标；将已选数量提示胶囊放到 FAB 左侧，移除列表中的数量项；紧凑行不再嵌套单笔卡片。
- [x] 4.32 更新 F-03 功能地图和 Web 语义/交互证据；图标仅复用现有资源或在共享 drawable 中添加多选图标，不增加依赖。
- [x] 4.33 将详细筛选按五组呈现并为新标题补齐四套语言资源；保持字段值、回调和过滤语义不变。
- [x] 4.34 更新 F-03 功能地图，记录详细筛选的分组结构与跨端共享实现。
- [x] 4.35 移除页面级 FAB 预留高度，让列表视口延伸到按钮后方；在 LazyColumn 内容末尾保留 104 dp 滚动空间，使最后一笔滚到 FAB 上方并留出间隔。
- [x] 4.36 在固定筛选卡下方按可见流水月份叠加固定头部；测量首条流水中的月份头部高度，在其滚出后固定摘要，并在桌面同步显示列标题；`REGULAR` / `WIDE` 行内容左右内缩 16 dp，分隔线保持全宽。固定层复用现有逐笔 LazyColumn，不增加项目或改变分页/滚动条序号。
- [x] 4.37 更新 F-03 功能地图，记录固定月份头、FAB 覆盖列表和桌面表格行留白。
- [x] 4.38 等待下月头部到达列表顶部后再替换固定月份；Regular/Wide 列表右侧预留改为 16 dp，保留 40 dp 滚动条交互热区；同步行末滚动条宽度/预留断言与功能地图。
- [x] 4.39 在共享账本列表实现完整刷新状态门控、初次及刷新骨架、错误/重试呈现和列表范围的 Material 3 下拉刷新；保留分页追加内容，多选时禁用下拉手势；更新 `docs/feature-map.md` 与跨端影响记录。
- [x] 4.40 将竖向滚动条轨道和滑块靠齐列表内容右边缘；保留 40 dp 可交互热区但令其覆盖列表、不参与布局测量；校准拖动月份提示位置，并维持 Compact 的滚动显示/2 秒空闲渐隐与桌面常驻行为。

## 5. 审查

- [x] 5.1 完成产品/工程/最终 diff 复核；记录覆盖范围、finding、处置理由和回写位置，详见下方审查记录。
- [x] 5.2 曾依据 audit 指南人工检查最终 Compose 页面；当时错误地将 Hallmark 技能当作命令入口，这项人工检查不构成 Hallmark 技能审查；以 5.10 的实际技能审计为准。
- [x] 5.3 执行可见文案必要性、范围化术语搜索、中文排版和无障碍标签复核；未增加用户文案或实现术语。
- [x] 5.4 曾人工检查新增搜索、快捷筛选、Bottom Sheet / 模态弹窗和三类窗口；该检查不构成 Hallmark 技能审查；以 5.10 的实际技能审计为准。
- [x] 5.5 静态复核搜索提示、快捷筛选标签、关闭/重置文案、术语及四套多语言资源；中文排版和无障碍名称一致。最终视觉与交互审查记录在 5.4、5.6、6.6。
- [x] 5.6 曾人工检查 FAB、选择模式、批量分类和三类窗口；该检查不构成 Hallmark 技能审查；以 5.10 的实际技能审计为准。
- [x] 5.7 曾人工检查主要操作图标、危险色和三类窗口；该检查不构成 Hallmark 技能审查；以 5.10 的实际技能审计为准。
- [x] 5.8 曾人工检查摘要、详情卡片、关联折叠区和标签/值布局；该检查不构成 Hallmark 技能审查；以 5.10 的实际技能审计为准。
- [x] 5.9 曾人工检查 `surface.muted` 卡片颜色；此前把“Hallmark 命令入口不可用”作为结论是错误的，该记录不构成 Hallmark 技能审查；以 5.10 的实际技能审计为准。
- [x] 5.10 使用 `.agents/skills/hallmark` 的 `audit` 技能检查本 change 重构的账本列表、搜索/筛选、批量操作、新增、详情、编辑和关联区域；按 severity 记录 findings、截图范围、修复与复审结论。此前任务行中把 Hallmark 技能误称为命令入口，本次记录为实际技能审查。
- [x] 5.11 对全局 surface container 映射及使用 Material 3 默认卡片/弹窗颜色的 Compose 页面执行 Web 范围复核；检查默认角色层级及跨页颜色影响。
- [x] 5.12 最终 UI 形成后用 Hallmark `audit` 检查账本表面层级、详情面板/卡片、默认弹窗和三类尺寸截图；finding 与复审结论写入本节。
- [x] 5.13 对最终账本固定筛选区、月度容器、FAB 多选状态和数量提示执行 Hallmark `audit`；在本节记录设计检查范围、严重度 finding、处置与复审结果。
- [x] 5.14 对详细筛选五组标题及 Compact/Regular/Wide 排布执行 Hallmark `audit`，记录 finding、处置和最终结论。
- [x] 5.15 对 FAB 覆盖、月份固定头部的跨月切换、桌面表格行内缩、滚动条占宽和最终 diff 执行独立设计/工程复核；finding、处置和结论见审查记录。
- [x] 5.16 对刷新状态门控、骨架布局、错误/重试、分页追加、下拉手势边界及滚动条分页跳转执行独立工程/设计复核；Hallmark `audit` 检查三类窗口实际页面。发现刷新哨兵去重条件和 Paging 跳页期间列表被误切回骨架两个问题，均已修复并通过回归；最终无未解决的 critical/major/minor 设计 finding，具体处置见下方 2026-10-21 审查记录。

## 6. 测试与 QA

- [x] 6.1 运行 OpenSpec 严格校验、doctor、共享模块回归、Web/Android/iOS 构建和 `git diff --check`；实际命令、结果、当前 `HEAD` 与基线见下方验证记录。
- [x] 6.2 用真实浏览器覆盖账本筛选、月份汇总、响应式列表、空/错/重试、新增、编辑、详情面板、Escape 关闭和导入工作流；Android/iOS 演示模式另检查详情删除确认入口。
- [x] 6.3 在 Compose Web `390×844`、`768×1024`、`1440×1000` 检查列表与新增/详情/编辑面板；Android 模拟器检查 Compact `411×914 dp`、Regular `800×1100 dp`、Wide `1200×640 dp`，iOS 检查 iPhone `390×844`、iPad mini `744×1133`、iPad Pro `1032×1376`。截图、状态、控制台/网络、响应式及无障碍结论见下方验证记录。没有新增焦点恢复断言。
- [x] 6.4 在 Android/iOS Compose 演示模式复核新增、详情、编辑、删除入口、系统返回、语义标签和安全区；无横向溢出，操作作用于合成演示数据。
- [x] 6.5 完成最终 UI 与独立设计复核；重大 UI 演示结果仍需用户确认。
- [x] 6.6 在 Compose Web、Android、iOS 的 `COMPACT`、`REGULAR`、`WIDE` 验证筛选、面板返回、Compact 长按与选择退出、桌面复选框、两种 FAB 菜单、分类取消/应用、删除确认和末行可达；Web 全场景图库与 Native 当前状态截图路径、视口和结论见下方最终验证记录。无横向溢出；未发现控制台或网络错误。
- [x] 6.7 对本次代码变更重跑受影响回归、共享/Android/iOS/Web 构建、真实浏览器 QA、OpenSpec 严格校验、`openspec doctor` 与 `git diff --check`；准确命令、结果、HEAD、比较基线、残余风险和不适用项见下方最终验证记录。
- [x] 6.8 更新后重新构建 Compose Web 演示包；在 `COMPACT`、`REGULAR`、`WIDE` 及浅/深色主题检查主要操作图标、危险色、文字可见性、按钮布局和删除确认，并记录截图、浏览器错误与 Native 影响。Android 三类窗口完成实机型模拟器检查；iOS 本轮构建受 Expo 预构建依赖缺失阻断，残余风险在最终记录说明。
- [x] 6.9 用户已确认此前更新的 Compose 演示结果；新增刷新 UI 的演示评审仍由 6.27 跟踪，需再次确认后才可将整个变更标记完成。
- [x] 6.10 使用真实浏览器覆盖无关联、收起/展开、多个关联项、修改关联类型、解散关联和编辑关联流水；仅 Compose Web 在三类尺寸检查卡片层级、滚动、点击和面板 Escape 关闭状态，不运行 Android/iOS 设备 QA。浅色、深色各 2 个 F-04 用例通过；详见下方记录。
- [x] 6.11 按用户明确范围，本轮只验证 Compose Web；不运行 Android/iOS 设备 QA。Native 与 Web 共用 `commonMain`，Web 编译覆盖共享源的编译检查，但不作为 Native 运行时视觉/交互证据；残余风险如实记录。
- [x] 6.12 重新构建 Compose 离线演示并仅用 Compose Web 真实浏览器检查更新后的无关联详情；关联折叠/展开由在线 fixture 用例覆盖。记录新 URL、视口、截图、浏览器错误及产物新鲜度。
- [x] 6.13 仅在 Compose Web 真实浏览器检查两张详情卡的浅/深主题中性色，以及金额颜色保持不变；覆盖 `390×844`、`768×1024`、`1440×1000` 并更新截图证据。
- [x] 6.14 运行 OpenSpec 严格校验、doctor 和 `git diff --check`，记录当前 HEAD 与未解决风险；不运行 Android/iOS 验证。
- [x] 6.15 用当前 Compose Web 产物刷新浅/深主题截图与图库，仅保留当前 UI 状态；覆盖 `COMPACT`、`REGULAR`、`WIDE` 的列表、筛选、选择/FAB、批量操作、新增、详情、编辑、关联创建表单和关联折叠区。
- [x] 6.16 只在 Compose Web 重新构建并用真实 Chromium 检查账本与详情的浅/深色三类尺寸；同时复核其他使用 Material 3 默认 Card/Dialog 容器角色的主要页面。记录浏览器 URL、视口、交互、截图、控制台/网络结果；不运行 Android/iOS 验证。
- [x] 6.17 运行 OpenSpec 严格校验、`openspec doctor`、`git diff --check`，记录 `HEAD`、基线及共享 Compose Native 运行时未复验的残余风险。
- [x] 6.18 使用新鲜 Compose Web 产物和真实 Chromium 在 `390×844`、`768×1024`、`1440×1000` 验证筛选固定、仅列表滚动、分页跨月容器闭合/连续、选择后 FAB 自动展开与数量提示位置；覆盖浅/深色截图及关键空/加载/错误状态，记录浏览器控制台和网络结果；不运行 Android/iOS QA。
- [x] 6.19 运行 OpenSpec 严格校验、`openspec doctor` 与 `git diff --check`，记录 HEAD、基线、验证命令和共享 Compose Native 运行时未验证风险；重大 UI 演示等待用户确认后才可完成或提交/推送。
- [x] 6.20 使用新鲜 Compose Web 产物和 Chromium 在代表视口验证五组标题、字段归属、组内排布、Bottom Sheet/模态滚动及既有筛选行为；浅/深主题回归和三尺寸截图见下方记录；不运行 Android/iOS QA。
- [x] 6.21 在 `390×844`、`768×1024`、`1440×1000` 真实浏览器验证 FAB 与列表重叠、末条完整可达、筛选卡下方的月份汇总/桌面列标题固定与替换、表格内容 16 dp 内缩及全宽分隔线；检查主流程、空/错态、键盘焦点、浅/深主题、控制台/网络错误并保存忽略目录截图，证据见下方 2026-10-21 记录。
- [x] 6.22 运行受影响的 Compose Web 浏览器回归、共享模块构建/测试、OpenSpec 严格校验、`openspec doctor`、中文与术语搜索及 `git diff --check`，记录 HEAD、比较基线和未解决风险；本次不运行 Android/iOS 设备 QA，Native 风险按共享 `commonMain` 记录。
- [x] 6.23 最终 UI 形成后按 `.agents/skills/hallmark` 的 `audit` 动作复核三类窗口实际页面与截图；finding 与处置见下方 2026-10-21 设计审查记录。
- [ ] 6.24 在 Playwright 中先运行新增刷新回归以确认旧实现失败，然后实现后验证首次/完整刷新骨架、刷新失败/重试、分页追加保留、列表顶部手势、筛选区不响应、多选禁用及列表外下拉不触发。当前实现后的浅/深主题回归已通过；未保存旧实现下的失败基线证据，因此本项仍待补齐。
- [x] 6.25 更新 feature map 与跨平台影响检查；在 Compose Web 真实浏览器覆盖 Compact/Regular/Wide、浅/深主题、正常/错误/空状态、触摸/鼠标/键盘焦点，并将截图留在忽略目录，记录控制台/网络结果。
- [ ] 6.26 在 Android/iOS Compose 运行时验证列表顶部下拉、筛选区边界、多选禁用、刷新骨架及失败重试，并记录设备/视口、截图路径和残余平台风险；Android/iOS 手势和多选状态已有演示检查，但刷新骨架与失败重试缺少可控演示入口，故本项仍未完成。
- [ ] 6.27 构建并提供包含刷新行为的 Compose 演示，在 `COMPACT`、`REGULAR`、`WIDE` 下由用户评审；确认后才完成重大 UI 审查。
- [x] 6.28 使用真实浏览器在浅/深主题和 `390×844`、`768×1024`、`1440×1000` 验证三尺寸滚动条贴边、列表宽度不变、Compact 滚动显示/2 秒空闲渐隐，以及桌面常驻和拖动/点击定位；此前 96 条分页夹具暴露的拖动到未加载区后目标流水未稳定显示问题，已通过仅在完整刷新状态隐藏列表、保留 Paging 跳页期间列表的门控修复；浅/深主题三尺寸几何、拖动到未加载页和目标行可见均通过完整 F-03 回归。截图与最终复核见下方 2026-10-21 记录。键盘跳转不属于本轮用户确认的滚动条布局范围，不作为新验收项。

## 7. 发布

- [x] 7.1 记录交付摘要与回滚路径；无迁移步骤，回滚时还原本次 Compose 页面、QA、功能地图和 OpenSpec 改动；本次仅准备 PR，不合入。
- [x] 7.2 用户已于 2026-10-21 明确授权本仓库创建面向 `main` 的 PR；已提交 `0907fd9`、推送 `codex/compose-cash-ledger-redesign` 并创建 [PR #95](https://github.com/ssttkkl/finance-tracker/pull/95)。PR 未合并；用户授权不包括合并。

## 8. 反思

- [x] 8.1 演示仓库未剥离 projection ID 前缀会让新详情面板无法加载；已加入回归测试。无需新增通用工作流规则。
- [x] 8.2 复盘固定筛选/分页连续容器的实现取舍及测试发现，判断是否需更新共享 Compose UI 工作流防复发。
- [x] 8.3 复盘按月固定头部与分页/滚动条序号映射：月份标题作为行内内容显示，不进入流水序号；sticky 选择使用已缓存的 `monthPositions` 与已加载行，跨月红回归覆盖头部替换时机。该实现关系仅属于账本页面，现有语义 ID 与浏览器回归足以防复发，无需新增通用规则。
- [x] 8.4 复盘完整刷新状态与 Paging generation 的边界、各平台下拉手势一致性及测试发现：独立门控页面内容可避免 Paging 暂留上一代数据时旧流水闪回；筛选区置于刷新容器外、多选时关闭手势，并在状态代次更新前以请求哨兵去重；设备演示缺少失败状态控制器时应保留 Native 刷新状态验证待办，不以 Web 结果替代。

## 审查记录

- **产品与范围：** 实现限于共享 Compose（Compose Web、Android、iOS），旧 React Web 保持不变且仅作参考；列表、新增、详情、编辑及原有删除入口覆盖用户确认的范围。筛选、汇总、分页、批量操作、导入、权限、关联和确认能力均保留；没有新增业务能力。未发现范围偏离。
- **工程与最终 diff：** 复核 `CashLedgerScreen.kt`、`CashLedgerDetails.kt`、跨平台 Escape 处理、演示仓库详情路径、E2E 与 `DemoFinanceRepositoryTest`。生产仓库、API、金额语义、数据库和迁移未改变；演示仓库只规范化合成 projection ID。未发现阻断或需延期的 finding。
- **焦点范围：** 流水面板没有 `FocusRequester`、显式 `requestFocus` 或关闭后恢复触发入口焦点的逻辑；其焦点沿用 Compose/平台默认行为。`CashLedgerScreen.kt` 仍接入导入页面导航既有的 `importButtonFocusRequester`；共享选择器的焦点逻辑仍用于其独立键盘选择行为。这些既有行为与面板无关，按用户要求保留。未添加焦点恢复断言。
- **设计审查：** 之前记录的人工审查并非 Hallmark 技能审计；当时把技能误判为命令工具。当前 Hallmark 技能审计及修复结果见 5.10 和下方 2026-10-16 记录。
- **文案与无障碍：** 可见标签沿用既有简体中文与 Material 3 控件语义；未增加帮助文案或实现术语；保留稳定语义 ID。按项目词表和中文排版约定复核，无需新增术语或文案更改。
- **Hallmark 最终审查（2026-10-17）：** 按 `.agents/skills/hallmark/SKILL.md` 的 `audit` 动作复核此 change 重构的页面；本轮重点检查固定筛选卡、月度分区、长列表/分页跨月边界、Compact/Wide 选择态、自动展开的批量 FAB 与数量胶囊，并结合既有新增、详情、编辑、关联场景的 Hallmark 截图复查。浅/深色截图覆盖 `COMPACT 390×844`、`REGULAR 768×1024`、`WIDE 1440×1000`；当前证据位于 `screenshots/current-review-20261017/`。首次视觉检查发现 1 项 Material 3 表面层级设计问题（筛选区使用普通 Card 时与月卡层级反转），已改用 `ElevatedCard`；紧凑窗口快捷筛选与详细筛选原先分行，已收紧 FlowRow 间距并压缩筛选按钮内边距。最终审查未发现命名 Hallmark 反模式或未解决设计 finding：**0 critical · 0 major · 0 minor**。不需改 token，也无需更新共享 Compose 工作流。
- **滚动与多选的工程复核（2026-10-17）：** 筛选卡位于 `LazyColumn` 外，只有列表接收滚动；当前月的分页末行保持平直端点，直到后续行已加载并确认月份边界，才闭合为独立月容器，故不需要预取整月。选择状态以已选 ID 为唯一来源驱动菜单自动展开，选中计数作为 FAB 同级的左侧 Material 3 Surface；取消最后一笔后既有状态会自动退出。未改 API、筛选语义、金额或持久化，不需要新增通用工作流规则。
- **本轮设计与工程独立复核（2026-10-21）：** 范围覆盖 `CashLedgerScreen.kt`、`CashLedgerScrollbar.kt`、语义 ID、三尺寸列表/FAB/月份切换/表格内缩/滚动条及回归断言。浅/深主题实际 Chromium 截图均检查 `COMPACT 390×844`、`REGULAR 768×1024`、`WIDE 1440×1000`；确认 Regular/Wide 列表右侧预留 16 dp、所有尺寸交互热区 40 dp、Compact 滚动条覆盖列表、固定汇总/列标题位于筛选卡下方、下月接近列表顶端时才替换，以及桌面行内容与表头对齐且分隔线铺满。未发现层级、对比度、溢出、控件状态或语义问题，也未发现命名 Hallmark 反模式：**0 critical · 0 major · 0 minor**。无生产 API、金额或持久化变化；没有需要回写 UI 文案或术语的新内容。按用户范围未执行 Native 运行时 QA，保留共享 `commonMain` 在 Android/iOS 设备上的触控与视觉复验风险。
- **刷新状态 Hallmark 与工程审查（2026-10-21）：** 按 `.agents/skills/hallmark/references/verbs/audit.md` 检查真实 Compose Web 画面与忽略目录截图，范围覆盖三尺寸的固定筛选、月摘要/列标题、骨架、错误/重试、FAB、行内缩进、滚动条及触控语义；无卡片嵌套、重复说明、表格数字或响应式层级问题，最终 **0 critical · 0 major · 0 minor**。独立工程复核先修复了下拉刷新回调在代次更新前可能重复进入的问题；浅/深主题下拉回归通过。后续 96 条分页夹具又发现 Paging 跳页期间内部 refresh 状态会误切换骨架并重置列表位置，页面门控现改为只在完整刷新状态隐藏旧列表；浅/深主题、三尺寸拖动到未加载页及目标行显示回归通过。键盘跳转不属于本轮用户确认范围，未作为验收项。

## 验证记录

- **时间与版本：** 2026-10-15；当前 `HEAD` 与比较基线均为 `8b6ebfb503d8efd7fab92d16b2b775e1d4d58fe2`，改动保留在工作树。OpenSpec `1.7.0`、Node.js `v26.8.1`。`openspec validate --all --strict`：32 passed / 0 failed；`openspec doctor`：Root OK、无声明引用；`git diff --check`：通过；`.hallmark/log.json` JSON 解析：通过。
- **共享逻辑与构建：** `cd compose && ./gradlew :shared:wasmJsTest --no-configuration-cache` 通过；新演示仓库详情回归先失败后通过。`cd compose && FT_COMPOSE_DEMO_MODE=true ./gradlew :androidApp:installDebug --no-configuration-cache` 成功。`xcodebuild -project compose/iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'platform=iOS Simulator,id=FE793C41-BC4B-423D-809E-6E68A7AA2318' build`：BUILD SUCCEEDED。Xcode 输出已有的 `rememberModalBottomSheetState` 弃用警告及未链接 AppIntents 时跳过 metadata extraction 的提示。`cd compose && ./gradlew :webApp:wasmJsBrowserDistribution --no-configuration-cache --rerun-tasks` 与 `cd compose && FT_COMPOSE_DEMO_MODE=true FT_API_ORIGIN='' ./gradlew :webApp:wasmJsBrowserDistribution --no-configuration-cache --rerun-tasks` 均成功。Web 构建仍显示仓库已有的 webpack 动态依赖告警和 Wasm 包体积建议；没有由本次页面代码引入的资源或依赖。
- **真实浏览器流程：** Playwright Chromium，`zh-CN`。`CI=1 FT_COMPOSE_PREVIEW_PORT=52882 FT_CAPTURE_COMPOSE_SCREENSHOTS=1 npm run test:e2e -- --grep 'F-03|F-04'`：6 passed；覆盖月份汇总、账户筛选、响应式列表、错误/重试/空态、新建、编辑写入、面板内编辑、Escape 关闭与三类面板几何。`CI=1 FT_COMPOSE_PREVIEW_PORT=52883 npm run test:e2e -- --grep 'F-05 账单导入从账本以响应式二级页面打开并可关闭'`：1 passed；导入入口在 320、375、390、414、768、1440 px 下可进入并返回。`npm run test:node`：2 passed。测试诊断未发现非预期 Console 或网络错误。
- **离线 Compose Web 演示：** 本地地址 `http://127.0.0.1:52884/w/demo-workspace/`；以 Chromium `zh-CN` 打开实际离线 demo 包。列表、新增、详情、编辑均检查并捕获，尺寸为 `390×844`、`768×1024`、`1440×1000`。脚本记录 0 API 请求、0 失败请求、0 其他 Console 错误；浏览器有一条 Kotlin/Wasm `wasmExports` 内存访问弃用警告。截图位于 `compose/qa/openspec/changes/compose-cash-ledger-redesign/screenshots/web-demo/`：`ledger-{compact-390,regular-768,wide-1440}.png` 及 `record-{create,detail,edit}-{compact-390,regular-768,wide-1440}.png`。
- **Web 截图和响应式检查：** 在线 API fixture 流程截图位于 `compose/qa/openspec/changes/compose-cash-ledger-redesign/screenshots/web/`，包括三个窗口下的新建、详情、编辑以及最终列表。检查页面级横向滚动、面板位置、背景遮罩、后台不可操作、标签与按钮语义；未发现溢出或异常。
- **Android 演示模式：** `emulator-5554`。原始 Wide `1200×640 dp`；额外设置窗口并复核 Compact `411×914 dp`、Regular `800×1100 dp`。三种尺寸均检查列表、新增、详情和编辑面板；返回键从面板返回账本，未保存演示数据。截图位于 `compose/qa/openspec/changes/compose-cash-ledger-redesign/screenshots/android/`，新增 `ft-cash-ledger-android-compact.png`、`ft-cash-record-{create,detail,edit}-android-compact.png`、`ft-cash-ledger-android-regular.png`、`ft-cash-record-{create,detail,edit}-android-regular.png`；Wide 截图也在同目录。
- **iOS 演示模式：** iPhone `390×844` Compact、iPad mini `744×1133` Regular、iPad Pro `1032×1376` Wide 均检查列表、新增、详情和编辑；系统返回有效，删除确认可打开并取消，操作只影响演示数据。截图位于 `compose/qa/openspec/changes/compose-cash-ledger-redesign/screenshots/ios/`，文件名分别以 `iphone-compact`、`ipad-mini-regular`、`ipad-pro-wide` 标记。
- **数据、安全和性能适用性：** 未改生产持久化、服务端查询、授权边界或数据库合同，因此 SQLite/PostgreSQL 双后端矩阵不适用；不需要数据库、性能或安全专项回归。Web 构建警告记录如上。
- **发布准备：** 可通过还原本次 `commonMain` 页面改动及对应测试、OpenSpec、功能地图与 Hallmark 记录回滚；无迁移和兼容步骤。没有提交、推送或外部写入授权，也未执行这些操作。
- **待用户评审：** 本次是重大 UI 改动；原 6.6/6.7 的 QA 与验证已完成，补充的图标/危险色视觉复核见 6.8；仍需用户评审更新后的 Compose 演示结果并确认后，才能勾选 6.9、同步/归档变更或标记完成。
- **筛选澄清后的静态复核（2026-10-15）：** 用户确认快捷项为「全部／支出／收入／转账」，分类只在详细筛选。当前实现与 proposal、design、delta spec 和功能地图一致；四套语言资源均包含新增标签；筛选弹层未加入专属焦点请求或恢复逻辑。`openspec validate --all --strict`：32 passed / 0 failed；`git diff --check`：通过；`HEAD`：`8b6ebfb503d8efd7fab92d16b2b775e1d4d58fe2`。本次筛选改动后的运行回归、构建、真实浏览器/设备 QA、最终 Hallmark 设计审查与截图尚未执行；保留 5.4、6.6、6.7 待办，并在当时的 6.8 用户评审前完成 QA。
- **最终界面审查与三尺寸截图（2026-10-15）：** 当时将 Hallmark 技能入口误判为命令工具，因此这条只记录按 `.agents/skills/hallmark/references/verbs/audit.md` 做过的人工页面复核，不代表实际调用 Hallmark 技能。人工覆盖列表卡片/表格、四个固定快捷项、筛选展开与关闭、搜索结果/空结果、批量分类/删除、新增/详情/编辑、关联、导入、API 错误/重试和无匹配状态。修复项包括快捷类型缺项、桌面筛选弹窗过大、无效解散关联入口、演示关联类型内部英文值，以及演示详情未按 `categoryId` 解析分类。旧截图总览仍保留为历史文件；本轮只使用新整理的 2026-10-16 Hallmark 审查图集作为当前证据。
- **最终验证（2026-10-15，取代本节此前的待办状态）：** 当前 `HEAD` 与比较基线均为 `8b6ebfb503d8efd7fab92d16b2b775e1d4d58fe2`；变更仍仅在工作树。`openspec validate --all --strict`：32 passed / 0 failed；`openspec doctor`：Root OK、无声明引用；`git diff --check`：通过。`cd compose && ./gradlew :shared:wasmJsTest --no-configuration-cache`：修复分类映射前新回归失败，修复后 125 项通过。`cd compose && FT_COMPOSE_DEMO_MODE=true ./gradlew :androidApp:installDebug --no-configuration-cache`：BUILD SUCCESSFUL；Android Wide 详情显示“餐饮”。`FT_COMPOSE_DEMO_MODE=true xcodebuild -project compose/iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'platform=iOS Simulator,id=FE793C41-BC4B-423D-809E-6E68A7AA2318' -derivedDataPath /tmp/ft-compose-ios-derived build`：BUILD SUCCEEDED；重新安装后 iPhone 详情与编辑分类一致。Xcode 仅有已有的 `rememberModalBottomSheetState` 弃用警告及未链接 AppIntents 的 metadata 提示。首次 Expo prebuild 尝试因 `mobile/` 没有安装 Expo SDK 而失败；本任务目标为独立 `compose/iosApp` 工程，不依赖该 Expo 项目或 Podfile，故改用其原生 Xcode 项目构建。
- **浏览器 freshness 与回归：** `cd compose/qa/web && npm run test:node`：3/3 通过，包含输入/产物指纹失配时必须重建的守卫测试。freshness 守卫校验并重建了当前 Compose Web 分发包；Playwright 每次运行使用独立端口，`reuseExistingServer: false`。使用 Chromium `zh-CN` 运行 `CI=1 FT_CAPTURE_COMPOSE_SCREENSHOTS=1 npm run test:e2e -- --grep 'F-03|F-04|F-05 账单导入从账本以响应式二级页面打开并可关闭'`：最终 15/15 通过，覆盖筛选、账户条件重置、选择、批量操作、错误/空态、新增、详情、编辑、Escape 与导入。首次完整回归在编辑返回详情后立即关闭面板时出现语义镜像仍保留几何信息、`toBeHidden()` 报可见的假失败；Playwright trace 的真实截图确认列表已恢复。将验证改为比较 Escape 前后实际浏览器截图，并留出 250ms 状态切换时间；没有改动产品 Escape 行为。终端仅有 Playwright 环境的 `NO_COLOR`/`FORCE_COLOR` 警告和既有 webpack/Wasm 包体积提示，没有预期外浏览器 Console 或网络错误。
- **Native QA 与残余风险：** Android `emulator-5554` 已在 `411×914 dp`、`800×1100 dp`、`1200×640 dp` 检查 Compact 长按/末项取消、Regular/Wide 行勾选和计数、FAB 空闲/批量菜单、分类取消/应用、删除确认、筛选与详情返回。iOS iPhone `390×844`、iPad mini `744×1133`、iPad Pro `1032×1376` 检查对应 Compact/Regular/Wide 布局、筛选容器、选择、FAB、批量分类和详情/编辑/删除；使用合成演示数据，无横向溢出。SQLite/PostgreSQL、生产 API、权限与财务金额语义未变，双后端矩阵和安全专项不适用。当前无未修复 critical、major、minor 或阻断 finding；新增图标/危险色 QA（6.8）已完成，iOS 本轮未能重跑的环境边界见下方记录，仍需用户评审确认（6.9）。
- **图标与危险色追加复核（2026-10-15）：** 产品/范围与工程复核确认仅改变主要按钮图标和既有危险操作颜色，不增加操作或确认流程；实际实现继续使用 Material 3 控件、FinanceTheme `error` 色与本地向量图标。该日期的截图与页面曾按 audit 文档人工复核，但当时错误地把 Hallmark 技能当作命令入口，故未执行 Hallmark skill，也不把人工复核写成技能审计结论。本轮的 Hallmark 技能审查与完整新截图总览见 2026-10-16 补充记录。
- **本轮浏览器回归：** Chromium Playwright `zh-CN` 在浅色、深色各执行 F-03 批量删除确认、F-04 新增/详情/编辑三尺寸、未关联流水删除确认三尺寸及已关联流水解散/删除选项三尺寸；最终 4 个场景在两主题均通过。已关联场景曾因 fixture 未提供关联数据而先失败，补充 fixture 后浅色/深色均通过。通过用例的 `afterEach` 断言无非预期 Console 错误、失败请求或静态 HTTP 错误。删除实际调用未确认，未写入样本数据。截图新生成 `related-record-delete-confirm-{compact-390,regular-768,wide-1440}.png` 并已加入浅色总览；深色复核保存在 `dark/`。
- **本轮 Android 复核：** Demo APK 使用 `FT_COMPOSE_DEMO_MODE=true FT_API_ORIGIN='' ./gradlew :androidApp:assembleDebug --no-configuration-cache` 构建成功并安装至 `emulator-5554`。使用 `agent-device` 在 `COMPACT 411×914 dp`、`REGULAR 800×1100 dp`、`WIDE 1200×640 dp` 检查详情操作、保存与删除确认；详情中的编辑/关联/删除按钮均保留标签，危险色为红色；表单保存带勾选图标；确认操作红色，取消保持蓝色。实际设备截图见 `screenshots/android/ft-cash-record-{actions-compact-danger,actions-regular-danger,actions-wide-danger,save-compact,save-regular,delete-confirm-compact,delete-confirm-regular,delete-confirm-wide}-20261015.png`。WIDE 和 REGULAR 保持列表背景可见，Compact 动作行无溢出。
- **本轮 iOS 构建边界：** 按 Xcode 26.3 门禁执行 `cd mobile && CI=1 npx expo prebuild --platform ios --no-install`，失败：`Cannot determine project's Expo SDK version because module expo is not installed`。`npm ci` 也因无 lockfile 无法继续，`npm install --no-save --no-package-lock` 受私有/未发布 `@finance-tracker/api-client@0.1.0` 的 registry 404 阻断。因此本轮没有执行 `pod install` 或 iOS 编译，没有把之前的 iOS build 当作当前图标/颜色变更证据；不能绕过该门禁直接运行 Xcode build。共享 Compose iOS 代码仍与 Web/Android 同源，但当前 iOS 视觉/编译复验准确记为未完成，补跑条件是按仓库 lockfile/私有 registry 配置安装 `mobile/` Expo 依赖后，完成规定的 prebuild、pod install 和 iOS 模拟器截图 QA。
- **演示包新鲜度与当前地址（2026-10-15）：** 图标/危险色浏览器 QA 后，执行 `cd compose/qa/web && FT_COMPOSE_PREVIEW_PORT=5189 npm run demo:offline`。新鲜度守卫识别先前 Playwright 产物与离线配置指纹不符，自动强制运行完整 Gradle 任务；`wasmJsBrowserDistribution` BUILD SUCCESSFUL，49 个 task 完成，再启动离线 demo。内网转发地址 `http://192.168.1.11:52913/w/demo-workspace/` 的 `/health` 返回 `ok`、页面返回 HTTP 200。最后用 Chromium 直接打开此 LAN 地址并检查 Compact 详情面板；0 API 请求、0 Console 错误（Kotlin/Wasm 已知 memory 访问弃用警告单独忽略）、0 失败请求、0 HTTP 错误。该检查未运行构建或 E2E，不会替换包；离线 demo 当前正在 5189 提供服务。此次确认了测试完成后 freshness 守卫会拒绝旧/异配置产物并重建。
- **详情卡片与最终 Web 复核（2026-10-16）：** 按用户决策，摘要位于卡片外，流水信息和关联流水分别使用并列 Material 3 Card；关联卡默认收起，展开内容在同一卡片内，无嵌套卡片。`CI=1 FT_COMPOSE_PREVIEW_PORT=52944 FT_CAPTURE_COMPOSE_SCREENSHOTS=1 npm run test:e2e -- --grep 'F-04 已关联流水的解散关联与删除选项使用危险色|F-04 新增、详情和编辑面板适配三类窗口'`：2 passed；深色主题对应命令（加 `FT_COMPOSE_COLOR_SCHEME=dark`，端口 `52945`）：2 passed。Playwright Chromium 在 `390×844`、`768×1024`、`1440×1000` 覆盖无关联详情、收起/展开、多条关联、关系类型修改、编辑关联流水、解除关联、删除危险样式和 Escape。截图总览为 `compose/qa/openspec/changes/compose-cash-ledger-redesign/screenshots/current-review-20261015/detail-cards.html`；浅/深主题各含无关联详情、关联卡收起、展开顶部与展开滚动态三尺寸截图，并含离线演示的三尺寸无关联详情。
- **离线演示更新与浏览器检查（2026-10-16）：** `cd compose/qa/web && FT_COMPOSE_PREVIEW_PORT=5194 npm run demo:offline`；新鲜度守卫识别深色 QA 产物与离线配置指纹不同，强制完整执行 `:webApp:wasmJsBrowserDistribution --no-configuration-cache --rerun-tasks`，49 个任务成功。离线服务 `http://127.0.0.1:5194/` 的 `/health` 返回 `ok`；LAN 中继 `http://192.168.1.11:52914/w/demo-workspace/` 页面返回 HTTP 200。Playwright Chromium `zh-CN` 通过真实离线包打开详情，在 `390×844`、`768×1024`、`1440×1000` 截图并确认卡片层级；`pageErrors=0`、`failedRequests=0`，控制台只有既有 Kotlin/Wasm `wasmExports` 内存访问弃用提示。图片位于 `offline-record-detail-{compact-390,regular-768,wide-1440}.png`。未运行 Android/iOS QA；共用 `commonMain` 的 Native 运行时视觉和交互未在本轮证明。
- **变更后最终门禁（2026-10-16）：** `openspec validate --all --strict`：32 passed / 0 failed；`openspec doctor`：Root OK、无声明引用；`git diff --check`：通过。当前 `HEAD` 与基线为 `8b6ebfb503d8efd7fab92d16b2b775e1d4d58fe2`，改动仍在工作树，未提交或推送。仅 Compose Web 通过本轮执行范围；未运行 Android/iOS 构建或设备 QA。重大 UI 变更仍待用户评审演示结果（6.9）。
- **详情卡片中性色修正与 Web 复核（2026-10-16）：** 按用户确认只调整两张卡片颜色，金额配色保持不变。`FinanceColorRoles` 复用现有浅/深色 `surface.muted` token；流水信息卡和关联流水卡显式指定该颜色，避免 Material 3 默认淡紫容器色。该次复核仅为人工检查；当时错误地把 Hallmark 技能误认为命令入口。本轮的实际 Hallmark 技能审查与完整新截图总览见下方补充记录。在线 Playwright Chromium：浅色 `CI=1 FT_COMPOSE_PREVIEW_PORT=52946 FT_CAPTURE_COMPOSE_SCREENSHOTS=1 npm run test:e2e -- --grep 'F-04 已关联流水的解散关联与删除选项使用危险色|F-04 新增、详情和编辑面板适配三类窗口'`，2 passed（2.2 分钟）；深色同命令加 `FT_COMPOSE_COLOR_SCHEME=dark` 并使用端口 `52947`，2 passed（31.8 秒）。覆盖新增、详情、编辑、无关联详情、关联卡收起/展开/滚动、危险操作和 Escape；所有尺寸通过。在线截图曾更新于 `screenshots/current-review-20261015/`，本轮用新整理图集作为当前 evidence。未运行 Android/iOS 验证；`6.9` 仍待用户评审。
- **本轮 OpenSpec 与工作树门禁（2026-10-16）：** `openspec validate --all --strict`：32 passed / 0 failed；`openspec doctor`：Root OK、无声明引用；`git diff --check`：通过；图库 HTML 引用的 54 张 PNG 全部存在。当前 `HEAD` 与基线为 `8b6ebfb503d8efd7fab92d16b2b775e1d4d58fe2`，改动仍未提交；仅执行 Compose Web 验证，Native 运行时 QA 未执行，重大 UI 变更仍待用户评审（6.9）。
- **Hallmark 技能审查、修复与复审（2026-10-16）：** 实际按 `.agents/skills/hallmark/SKILL.md` 及其 `audit` 动作审查页面和代码，没有调用 Hallmark CLI。审查范围为账本列表、月度汇总、搜索与快捷/详细筛选、空/错状态、Compact 长按选择、Regular/Wide 复选框、两种 FAB 菜单、批量分类/删除、新增/编辑、详情摘要与信息卡、关联折叠/展开/滚动/创建表单、危险操作和删除确认；Compose Web 浅/深主题，`COMPACT 390×844`、`REGULAR 768×1024`、`WIDE 1440×1000`，共 25 个状态组、150 张截图。首轮发现 1 项 **Major —「Tabular data without tabular-nums」**：金额数字未使用等宽数字，且桌面金额列未右对齐，位置为 `FinanceComponents.kt:54-70`、`CashLedgerScreen.kt:390-394, 805-831`、`CashLedgerDetails.kt:181, 272, 662`。已在 `FinanceAmount` 和金额摘要/详情启用 `tnum`，并右对齐 Regular/Wide 表格的金额标题和值；收入/支出金额颜色未改变。按技能 anti-pattern 名称复审后为 **0 critical · 0 major · 0 minor**；未发现 AI 模板结构、设计 token 漂移、卡片嵌套、悬停专属操作或其他命名反模式。图库为 `compose/qa/openspec/changes/compose-cash-ledger-redesign/screenshots/hallmark-audit-20261016/index.html`，是本轮唯一当前截图入口；旧 `current-review-20261015` 的总览已标为历史并链接到新图集。
- **Hallmark 修复后 Web 复核（2026-10-16）：** 在 `compose/qa/web` 以 Playwright Chromium `zh-CN` 执行 `CI=1 FT_CAPTURE_COMPOSE_SCREENSHOTS=1 npm run test:e2e -- --grep 'F-03|F-04'`，浅色 16 passed；再加 `FT_COMPOSE_COLOR_SCHEME=dark` 执行相同命令，深色 16 passed。新增关联创建表单截图的 `F-04 新增、详情和编辑面板适配三类窗口` 聚焦用例在浅/深主题各 1 passed；实际截图确认关联表单以内联内容出现，未嵌套弹窗。截图路径与完整状态清单见上述 Hallmark 图集；新图库引用的 150 张 PNG 均存在。仅 Compose Web 验证，未运行 Android/iOS QA。
- **最终变更记录校验（2026-10-16）：** `openspec validate --all --strict`：32 passed / 0 failed；`openspec doctor`：Root OK、无声明引用；`git diff --check`：通过。图库引用检查通过，25 个状态组 × 3 种尺寸 × 2 个主题共 150 张截图全部存在，三个旧截图索引均指向当前 Hallmark 图集。当前 `HEAD` 为 `8b6ebfb503d8efd7fab92d16b2b775e1d4d58fe2`；仅验证 Compose Web，未运行 Android/iOS QA；6.9 仍待用户确认演示结果。
- **全局表面层级与 Web 复核（2026-10-16）：** `FinanceTheme.kt` 仅复用现有浅/深色 Finance token 为五个 `surfaceContainer*` 角色赋值；账本筛选与列表在共享白色 Surface 上以 `outlineVariant` 分隔，详情摘要留在 `surfaceContainerHigh` 面板，信息与关联卡采用 ElevatedCard 默认 `surfaceContainerLow`。实际截图发现普通 Material 3 Card 默认 `surfaceContainerHighest` 与详情面板同色，按 Material 3 官方 Card 源码核对后将详情两张卡改为 ElevatedCard；没有新增颜色、局部卡片颜色覆盖或 tonal elevation 色调叠加，收入/支出金额颜色不变。全局调用点静态复核覆盖 `FeatureComponents.kt`、`SecondaryPageContainer.kt`、`CashCategoryScreen.kt`、`WorkspaceScreens.kt`、`InvestmentEventScreen.kt`、`InvestmentScreens.kt`、`CashLedgerScreen.kt` 与 `CashLedgerDetails.kt`：Finance `SectionCard` 和选择器浮层使用既有显式 surface，AlertDialog/DatePickerDialog 沿用全局 M3 容器角色；未发现非预期淡紫或其他色值漂移。截图复核覆盖浅/深主题的 25 个账本/流水状态组、390×844 / 768×1024 / 1440×1000，共 150 张图，入口 `compose/qa/openspec/changes/compose-cash-ledger-redesign/screenshots/hallmark-audit-20261016/index.html`；重点查看列表、筛选、详情、关联卡、确认弹窗三尺寸以及深色 ElevatedCard。原来详情卡与面板同色的 Major 层级 finding 已由 ElevatedCard 修复；实际 Hallmark `audit` skill 复审后为 **0 critical · 0 major · 0 minor**，没有 Card-in-card、暗色彩色光晕或命名的 AI 页面结构反模式。白色前景继续使用既有 Finance token，符合用户确认的 Surface 层级。
- **本轮 Web 回归结果（2026-10-16）：** freshness 守卫发现共享 Compose 源码变化后执行 `:webApp:wasmJsBrowserDistribution --no-configuration-cache --rerun-tasks`，随后 Chromium `zh-CN` 在 5184 预览运行 F-03/F-04 16 项；15 项通过，筛选关闭测试一度使用语义镜像可见性断言而失败，改为比较关闭前后的实际画面变化（阈值 0.03）后浅色对应场景 1/1 通过。深色 F-03/F-04 最终全套 16/16 通过，覆盖筛选/批量状态、三尺寸新增/详情/编辑、危险确认、关联展开/删除；截图均已刷新。完整浅色全应用初次回归为 56/57，唯一失败是 viewer 测试错误地要求不可见的 FAB 子项语义节点从 DOM 移除；改为检查其隐藏状态后 Chromium 单测 1/1 通过。完整深色全应用初次回归为 55/57：筛选关闭阈值问题已按上述修正；投资事件页浏览器返回历史断言单独重跑通过 1/1。F-03/F-04 与权限复验均通过；不将初次全应用套件写为全绿。运行还显示既有 `NO_COLOR`/`FORCE_COLOR` 警告、webpack bundle size 告警，没有报告由本次表面代码引发的浏览器运行错误。Native 共享 `commonMain` 运行时按用户指示未复验；6.9 仍待用户检查演示。
- **最终 Web 交付复核（2026-10-16）：** 当前代码未再变化；浅色 Chromium `CI=1 FT_COMPOSE_PREVIEW_PORT=5188 FT_CAPTURE_COMPOSE_SCREENSHOTS=1 npm run test:e2e -- --grep 'F-03|F-04'`：16/16 通过；深色相同命令、加 `FT_COMPOSE_COLOR_SCHEME=dark` 并使用端口 `5190`：16/16 通过；查看者只读回归端口 `5181`：1/1 通过；深色投资事件历史回归端口 `5183`：1/1 通过。离线演示使用 `FT_COMPOSE_PREVIEW_PORT=5196 npm run demo:offline`；freshness 守卫识别旧包并强制 `:webApp:wasmJsBrowserDistribution --no-configuration-cache --rerun-tasks`，49 个 Gradle 任务成功。`http://127.0.0.1:5196/health` 与工作区路由均返回 HTTP 200；局域网入口 `http://192.168.1.11:52914/w/demo-workspace/` 通过转发接入 5196，并经 `/health`、工作区路由双检查均返回 200。Hallmark 图集实际解析 25 个场景 × 3 种尺寸 × 2 个主题，150 张截图全部存在。最终 `openspec validate --all --strict`：32/32 通过；`openspec doctor`：Root OK、无声明引用；`git diff --check`：通过；`HEAD`/比较基线为 `8b6ebfb503d8efd7fab92d16b2b775e1d4d58fe2`。改动仍在工作树，未提交或推送；按用户范围未运行 Android/iOS 验证；重大 UI 仍待用户确认演示结果（6.9）。
- **局域网演示浏览器冒烟（2026-10-16）：** Playwright Chromium 访问 `http://192.168.1.11:52914/w/demo-workspace/`，依次使用 `390×844`、`768×1024`、`1440×1000` 视口；三种尺寸均渲染「收支账本」，无页面横向溢出、`pageErrors=0`、`failedRequests=0`。Compact 最新页面截图暂存于 `/tmp/cash-ledger-demo-compact.png`；完整当前 UI 状态图集仍以 `compose/qa/openspec/changes/compose-cash-ledger-redesign/screenshots/hallmark-audit-20261016/index.html` 为入口。
- **完整 Compose Web 回归（2026-10-16）：** 修复此前全套回归暴露的 viewer 隐藏语义节点断言和筛选关闭截图阈值后，浅色 `CI=1 FT_COMPOSE_PREVIEW_PORT=5201 npm run test:e2e`：57/57 通过（6.2 分钟）；深色 `CI=1 FT_COMPOSE_PREVIEW_PORT=5202 FT_COMPOSE_COLOR_SCHEME=dark npm run test:e2e`：57/57 通过（4.8 分钟）。覆盖访问、导航、账本/详情/批量操作、导入、分类、投资事件和权限；运行输出仅有已知 `NO_COLOR`/`FORCE_COLOR` 与 webpack 资源体积告警。仅 Compose Web，未运行 Android/iOS QA。
- **固定筛选与月度列表实现 QA（2026-10-17）：** 当前 `HEAD=8ce4bfa939623bfeac8b26ff89058892187d2cbb`，比较基线为 `main=3b3bbe25b4dbd6007be024fdb7b35473a0ea229f`；工作分支为 `enhanced-ape`。`cd compose && ./gradlew :shared:wasmJsTest --no-configuration-cache`：BUILD SUCCESSFUL。`cd compose/qa/web && npm run test:node`：3/3 通过。Chromium `zh-CN` 使用 freshness runner（`reuseExistingServer: false`，每轮隔离端口）：浅色 `CI=1 FT_CAPTURE_COMPOSE_SCREENSHOTS=1 npm run test:e2e -- --grep F-03` 17/17 通过；深色同命令加 `FT_COMPOSE_COLOR_SCHEME=dark` 17/17 通过；最终 import 排序和测试格式整理后，固定端口回归 `CI=1 FT_COMPOSE_PREVIEW_PORT=5217 FT_CAPTURE_COMPOSE_SCREENSHOTS=1 npm run test:e2e -- --grep "筛选卡片固定"` 1/1 通过，浏览器 URL 为 `http://127.0.0.1:5217/w/workspace-1/`。覆盖视口 `390×844`、`768×1024`、`1440×1000` 下筛选坐标滚动前后不变、仅列表触发分页、跨月月份头/卡片边界、Compact 长按和 Regular/Wide 勾选、多选 FAB 自动展开、计数胶囊在 FAB 左侧、批量分类取消/应用、删除确认、空态/请求错误/追加错误与重试；首轮旧布局回归曾确认筛选滚动离开视口且选择菜单仍折叠。浅/深截图在 `screenshots/current-review-20261017/` 及 `dark/`，包括三尺寸列表、固定筛选、月度边界、批量菜单和空/错态。测试的浏览器诊断断言通过，无非预期页面错误、失败请求或 HTTP 错误；终端仅出现已知 Node `NO_COLOR`/`FORCE_COLOR` 和 Webpack 资源体积警告。
- **当前 OpenSpec 与差异检查（2026-10-17）：** `npm run test:node`：3/3；`openspec validate compose-cash-ledger-redesign --strict`：有效；`openspec doctor`：Root OK、无声明引用；`git diff --check`：通过。代码只改共享 `commonMain` 和浏览器回归；按用户指示没有执行 Android/iOS 构建或设备验证，因此 Native 运行时视觉/手势仍未由本轮证明。账本需求、设计说明、任务记录及 `docs/feature-map.md` 已更新；仍待用户确认本地演示（6.9）后才能完成重大 UI 审查及按既有授权推送 `main`（7.2）。
- **新鲜离线演示与内网访问（2026-10-17）：** `FT_COMPOSE_PREVIEW_PORT=5203 FT_COMPOSE_COLOR_SCHEME=light npm run demo:offline` 的 freshness 守卫发现离线包缺失或过期，强制完整执行 `:webApp:wasmJsBrowserDistribution --no-configuration-cache --rerun-tasks`，49 个 Gradle task 成功。离线服务 `http://127.0.0.1:5203/`；通过 `0.0.0.0:52915` 转发到该服务的内网入口 `http://192.168.1.11:52915/w/demo-workspace/`；`/health` 返回 `ok`、工作区页面返回 HTTP 200。Playwright Chromium `zh-CN` 直接打开内网地址并在 `390×844`、`768×1024`、`1440×1000` 截图；三个尺寸均无横向溢出，`pageErrors=0`、`failedRequests=0`、API 请求数为 0。控制台仅有现存 Kotlin/Wasm `wasmExports` memory 访问弃用提示。演示截图为 `screenshots/current-review-20261017/demo-offline-{compact-390,regular-768,wide-1440}.png`；服务和内网转发仍在运行以供用户评审。
- **详细筛选分组实现与 Web QA（2026-10-17）：** 依用户确认，在 `CashDetailedFilters` 中以常驻标题组织时间、金额、分类、账户和其他条件；Compact 保持单列并在 Bottom Sheet 内滚动，Regular/Wide 在组内并排放日期、金额范围和其他条件。账户、分类、日期、币种和金额筛选回调维持原值，资金转移类型及合并状态使用与原实现相同的值与选项；四套资源包含本地化标题。`docs/feature-map.md` F-03、proposal、delta spec 与 design 已同步。无新领域术语，已按 `DOMAIN_GLOSSARY.md` 和中文文案规范复核。
- **详细筛选 Hallmark 审查（2026-10-17）：** 按 `.agents/skills/hallmark` 的 `audit` 指引检查实际 Compose 筛选界面与已确认的信息架构；范围为筛选入口、五组标题及字段、Bottom Sheet 和模态弹窗、深浅主题与响应式排布。对照实际 Chromium 截图检查层级、控件标签/状态、对比度、横向溢出、触控可达和重复装饰；未发现命名反模式或需要修复的视觉问题：**0 critical · 0 major · 0 minor**。
- **详细筛选最终浏览器验证（2026-10-17）：** Chromium `zh-CN`，Playwright freshness runner 使用隔离预览端口、`reuseExistingServer: false`，工作区 URL 为 `http://127.0.0.1:<port>/w/workspace-1/`。浅色 `CI=1 FT_COMPOSE_PREVIEW_PORT=5231 FT_CAPTURE_COMPOSE_SCREENSHOTS=1 npm run test:e2e -- --grep 'F-03'`：18/18 通过；深色同命令加 `FT_COMPOSE_COLOR_SCHEME=dark`、端口 `5239`：18/18 通过。分组回归覆盖 `320×800`、`375×812`、`390×844`、`414×896`、`768×1024`、`1440×1000`；浅/深截图在 `screenshots/current-review-20261017/` 和 `dark/`，包括 `filters-grouped-compact-390.png`、`filters-grouped-compact-bottom-390.png`、`filters-grouped-regular-768.png` 和 `filters-grouped-wide-1440.png`。验证五组 DOM 顺序、所有字段归属、Compact 上下滚动、Regular/Wide 组内并排、账户筛选写入、固定筛选与列表滚动、分页、错误/空态、完成与 Escape 关闭。Playwright 每个通过用例的 Console/网络/HTTP 诊断均通过，无非预期错误；终端只有既有 `NO_COLOR`/`FORCE_COLOR` 警告。中途发现紧凑面板增长后旧账户筛选回归未滚动到面板下方字段，修正为在面板内滚动后选项可见；深色主题的截图采样旧阈值低估了关闭面板的差异，按实际闭合画面调整断言阈值并复验通过。最终浅色全 F-03 为 18/18，深色全 F-03 为 18/18。按用户要求仅做 Compose Web；没有执行 Android/iOS 运行时验证，残余平台风险仍按共享 `commonMain` 记录。最终 `openspec validate --all --strict`：32/32；`openspec doctor`：Root OK、无声明引用；`npm run test:node`：3/3；`git diff --check`：通过；当前 `HEAD=8ce4bfa939623bfeac8b26ff89058892187d2cbb`，比较基线 `main=3b3bbe25b4dbd6007be024fdb7b35473a0ea229f`。重大 UI 演示仍待用户确认（6.9），未提交或推送。
- **账本粘性月份头、列表 FAB 与滚动条宽度 Web QA（2026-10-21）：** 真实 Chromium `zh-CN` 通过 Playwright freshness runner 访问 `http://127.0.0.1:<isolated-port>/w/workspace-1/`，浅色与深色均在 `390×844`、`768×1024`、`1440×1000` 验证筛选卡固定、FAB 覆盖列表/末条完整滚到按钮上方、月份汇总及桌面列标题停留在筛选卡下方并在下月接近顶部时替换、行内容缩进 16 dp 且分隔线全宽，以及空态、请求错误/重试、分页追加成功/失败和滚动条拖动/点击/键盘 `End`。浅/深色最终回归命令分别为 `CI=1 FT_COMPOSE_COLOR_SCHEME=light FT_CAPTURE_COMPOSE_SCREENSHOTS=1 npm run test:e2e -- --grep '筛选卡片固定|FAB 覆盖列表|滚动条定位未加载流水|账本请求失败后可重试|滚动接近末尾|追加失败时保留流水|Regular 与 Wide 表格列|账本没有记录时显示空态'` 和同命令将主题设为 `dark`；各 8/8 通过。浏览器 AfterEach 诊断断言无非预期 Console/page error、失败请求、静态 HTTP 错误或错误 MIME。实现恢复 40 dp 触控热区后，滚动条回归移除“分页已返回时拖动阶段不得显示目标页流水”的过严断言，保留目标月份气泡、目标页请求和松手后精确定位检查；完整套件中的一次自动追加时序失败随后单独回归及完整套件重跑均通过。代表性截图保存在忽略目录 `compose/qa/local-evidence/cash-ledger-redesign-20261021/{light,dark}/`，包括 `ledger-overlay-sticky-390-390.png`、`ledger-overlay-sticky-768-768.png`、`ledger-overlay-sticky-1440-1440.png` 及对应 `ledger-overlay-end-*` 文件；浅/深、三尺寸均已人工复核。Regular/Wide 列表右侧预留为 16 dp，所有尺寸滚动条交互热区为 40 dp，Compact 滚动条覆盖列表。Playwright freshness 守卫构建当前 Compose Web 产物。`cd compose && ./gradlew :shared:wasmJsTest --no-configuration-cache`：BUILD SUCCESSFUL（47 个 Gradle 任务，10 个执行）；`cd compose/qa/web && npm run test:node`：3/3。`openspec validate compose-cash-ledger-redesign --strict`：有效；`openspec doctor`：Root OK、无声明引用；`git diff --check`：通过。范围化搜索确认“滚动条”等术语已见于现有主规格，本次无新领域词汇或可见文案。当前 `HEAD=ee366732d9fe0522cbe671936fed92862e96ac7f`、比较基线 `main=3b3bbe25b4dbd6007be024fdb7b35473a0ea229f`、分支 `filthy-skunk`；变更留在工作树，未提交或推送。按用户范围未执行 Android/iOS 设备验证，共享 `commonMain` 的 Native 触控与视觉仍待设备复验；重大 UI 的演示确认仍待用户完成（6.9）。
- **当前变更离线 Compose 演示（2026-10-21）：** `cd compose/qa/web && FT_COMPOSE_PREVIEW_PORT=5277 npm run demo:offline` 触发 freshness 守卫并完整重建 `:webApp:wasmJsBrowserDistribution --no-configuration-cache --rerun-tasks`，49 个 Gradle 任务成功；本机演示 `/health` 返回 `ok`，`/w/demo-workspace/` 返回 HTTP 200，地址 `http://127.0.0.1:5277/w/demo-workspace/`。构建只有既有 Kotlin 弃用、Webpack 依赖/包体积警告。浅/深 Chromium 的最终视口截图和交互回归见上一条；服务保留运行以供用户评审。重大 UI 演示确认仍待用户完成（6.9）；未提交或推送。
- **滚动条热区补充验证（2026-10-21）：** `CI=1 FT_COMPOSE_COLOR_SCHEME=light npm run test:e2e -- --grep '交互热区仍可点击定位'`：1/1 通过，确认 Regular/Wide 的 40 dp 交互区域仍能点击定位。另用 96 条分页夹具直接检查 Wide 拖动到未加载区和键盘 `End`：接口分别请求目标页（拖动 `start_index=37`、键盘 `start_index=85`），但目标流水没有进入可见列表；键盘路径随后又请求首屏，视口回到最前。拖动路径未见额外首屏请求。缩小测试只保留通过的点击回归，未把未通过的拖动/键盘检查写成通过；此两项仍待追查 Paging 跳转后的列表位置同步，`6.28` 保持未完成。该结果限定于 `WIDE 1440×1000` Chromium，不改变前述 390/768/1440 浅/深几何、贴边及 Compact 渐隐通过记录。当前 `HEAD=ee366732d9fe0522cbe671936fed92862e96ac7f`，比较基线 `main=3b3bbe25b4dbd6007be024fdb7b35473a0ea229f`；未提交、未推送。
- **PR 前最终验证（2026-10-21）：** `origin/main` 最新提交与验证基线均为 `ee366732d9fe0522cbe671936fed92862e96ac7f`，工作分支 `filthy-skunk`。Playwright freshness runner 使用真实 Chromium `zh-CN` 和隔离预览端口；浅色 `CI=1 FT_COMPOSE_COLOR_SCHEME=light npm run test:e2e -- --grep 'F-03'`：26/26 通过，包含筛选、选择、FAB、固定月头、桌面列标题/行缩进、三尺寸滚动条、首次/完整刷新骨架、刷新错误重试、顶部下拉、筛选区与多选禁用、追加成功/失败、错误/空态、详细筛选与键盘 Escape；深色同范围的相关 11 项：11/11 通过。请求去重修复后，浅色和深色定向下拉刷新回归各 1/1 通过。`cd compose && ./gradlew :shared:wasmJsTest --no-configuration-cache`：BUILD SUCCESSFUL（47 项任务，9 项执行）；`cd compose/qa/web && npm run test:node`：3/3；`openspec validate compose-cash-ledger-redesign --strict`：有效；`openspec doctor`：Root OK、无声明引用；`git diff --check`：通过。浏览器用例诊断无非预期 Console、失败请求或 HTTP 错误；终端只有既有 `NO_COLOR`/`FORCE_COLOR`、Gradle configuration-cache、Webpack 依赖/包体积提示。截图为本机忽略证据 `compose/qa/local-evidence/cash-ledger-redesign-20261021/{light,dark}/`，包括三尺寸滚动条与月头状态、完整滚动末条、刷新骨架和下拉刷新状态。跨平台影响：`commonMain` 同时影响 Compose Web、Android、iOS；Web 真实浏览器完成，Native 运行时骨架/失败重试尚缺可控演示状态，保留为 6.26 待办；重大 UI 的刷新演示仍需用户评审（6.27）。滚动条跳到未加载页仍按 6.28 保持未完成；刷新失败前置基线未记录，6.24 保持未完成。
- **PR 前最终验证（2026-10-21）：** `origin/main` 最新提交与比较基线均为 `ee366732d9fe0522cbe671936fed92862e96ac7f`，改动位于工作分支 `filthy-skunk`。Playwright freshness runner 使用真实 Chromium `zh-CN` 和隔离预览端口；浅色、深色完整 `F-03` 分别 27/27 通过，包含筛选、选择、FAB、固定月头、桌面列标题/行缩进、三尺寸滚动条、首次/完整刷新骨架、刷新错误重试、顶部下拉、筛选区与多选禁用、追加成功/失败、空态与详细筛选。另对最终 UI 截图回归在两种主题分别 5/5 通过，覆盖 FAB/固定月份头、三尺寸滚动条几何与拖动到未加载目标页、刷新骨架重试及下拉刷新。Playwright 诊断无非预期 Console、失败请求或 HTTP 错误；终端只有既有颜色变量、Gradle configuration-cache 和 Webpack 依赖/包体积提示。`cd compose && ./gradlew :shared:wasmJsTest --no-configuration-cache`：BUILD SUCCESSFUL（47 个任务，9 个执行）；`cd compose/qa/web && npm run test:node`：3/3；`openspec validate --all --strict`：32/32；`openspec doctor`：Root OK、无声明引用；`git diff --check`：通过。截图保存在 Git 忽略目录 `compose/qa/local-evidence/cash-ledger-redesign-20261021/{light,dark}/`，包括滚动条、月头、末尾、骨架和下拉刷新状态，不纳入 Git。跨平台影响：`commonMain` 同时影响 Compose Web、Android、iOS；Web 真实浏览器完成，Native 运行时刷新骨架/失败重试尚缺可控演示状态，保留为 6.26 待办；包含刷新行为的重大 UI 演示仍待用户评审（6.27）。刷新失败用例旧实现下的失败基线未保存，6.24 保持未完成。用户授权创建面向 `main` 的 PR，但未授权合并。
- **PR 验证补记（2026-10-21）：** 对当前最终代码重新执行浅色与深色完整 `F-03`：各 27/27；共享 `:shared:wasmJsTest`：BUILD SUCCESSFUL（47 个任务，9 个执行）；Web `npm run test:node`：3/3；`openspec validate --all --strict`：32/32；`openspec doctor`：Root OK；`git diff --check`：通过。`cd compose && FT_COMPOSE_DEMO_MODE=true FT_API_ORIGIN='' ./gradlew :androidApp:assembleDebug --no-configuration-cache`：BUILD SUCCESSFUL（62 个任务，6 个执行、1 个缓存）。当前环境为 Xcode 26.3，但 `mobile/node_modules/expo` 不存在；按仓库 iOS 门禁无法完成规定的 Expo prebuild，因此没有绕过门禁编译 iOS。Android/iOS 运行时骨架与失败重试仍由 6.26 跟踪；Web 真实浏览器已完成。新生成的 QA 证据均位于被 `.gitignore` 排除的 `compose/qa/local-evidence/`，已用 `git check-ignore` 核实。
