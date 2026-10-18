## 1. 思考与审查基线

- [x] 1.1 对照 [Material 3 Dialog 指南](https://m3.material.io/components/dialogs/guidelines) 与 Compose Material 3 实现文档，从视觉设计、业务行为两方面盘点共享 Compose 中标准对话框、自定义 Dialog、对话框式 Popup 的任务、动作、关闭路径、主题角色和响应式边界。账单详情/编辑、新增关联遵守已确认的产品尺寸合同，其余只读详情和短确认按 M3 基础对话框规则处理；账单确认/错误作为唯一活动 AlertDialog 暂时替代原任务面板。其余重点包括标题与操作语义、取消可用、Escape 按菜单到父任务的顺序处理、请求中防重复提交且失败可见。M3 指南页面经浏览器读取时返回 JavaScript 壳；同时对照 Compose 官方 Dialog 文档、M3 surface 角色指南和实际 `commonMain`/Web 截图。第二轮发现及处置见 4.1。
- [x] 1.2 Cross-platform Impact Check：Web、Android、iOS 使用同一 `commonMain` 实现，三端受同一响应式呈现和业务语义影响；本 change 的运行验证只覆盖 Web（按用户要求），其余平台保留共享代码但不做设备运行验证。
- [x] 1.3 按 M3 Side Sheet specs 与用户补充合同复核账单详情/编辑/关联面板、所有非 AlertDialog 关闭入口、底部操作顺序和 AlertDialog 图标；首轮发现 2 项 major 与 1 项 minor，最终浏览器行为复核另发现 Side Sheet 拖动关闭未生效，再修正并完成三尺寸回归，finding 与处置见 4.1。

## 2. 规格与设计

- [x] 2.1 创建并修订 proposal、`shared/design-system` delta spec 与设计说明，记录视觉/行为验收、窗口变体、未提交工作丢弃、信息架构、移除的冗余入口、Hallmark 方向与实现边界。
- [x] 2.2 运行 `openspec validate compose-material3-dialog-conformance --strict`，确认规格覆盖审查 finding、验收场景和跨端不变量。

## 3. 测试先行与实现

- [x] 3.1 先补 Web 回归用例：日期取消不写回、日期确认提交、基础弹窗中的日期选择器内联展开、Compact 账单详情/编辑均全屏、Regular/Wide 详情/编辑为右侧全高同一面板、关联创建独立任务、删除范围选择及关系/丢弃/错误确认作为唯一活动 AlertDialog 并在取消/知悉后恢复原任务、导入 Compact 全屏上的账户编辑/丢弃确认/提交错误、导入 Regular/Wide 状态切换、取消保留草稿、560 dp 宽度上限、请求中确认/取消可用且不重复提交、关闭面板后错误仍可见、筛选即时应用、Back/Escape、亮/暗主题；确认新增行为断言先复现旧问题。
- [x] 3.2 修正 `FinanceTheme` 对话框形状角色和自定义账本/筛选/投资容器；使用 M3 Surface 角色、单一 scrim、28 dp Dialog 圆角、560 dp 基础 Dialog 最大宽度与稳定的标题/正文/操作区。
- [x] 3.3 修正日期选择确认/取消语义、日期字段清除入口、工作区删除危险色及流水删除范围选择；保持既有领域操作和结果。
- [x] 3.4 修正导入流程与新账户编辑：`COMPACT` 全屏、`REGULAR`/`WIDE` 限宽基础对话框；所有尺寸的任务操作固定在底部，主要操作最右；Compact 在全屏任务上打开基础丢弃确认和账户编辑，Regular/Wide 在当前基础对话框内切换状态；账户映射卡纵向排列信息以适配 560 dp 宽度并完整展示选中账户的名称、类型和币种；保留取消草稿和共享账户草稿同步。
- [x] 3.5 将只读投资事件和持仓详情改为三尺寸共享的基础对话框（不超过 560 dp），固定标题与关闭操作、正文滚动；复用现有详情/错误/重试状态。
- [x] 3.6 更新 `docs/feature-map.md` 与浏览器 QA 记录路径。
- [x] 3.7 在所有模态容器内将日期/时间选择改为当前任务中的内联状态，保留暂存确认语义并防止相邻字段同时展开；删除请求中保留确认/取消入口，并保证关闭后仍可看到异步失败。
- [x] 3.8 对分类、工作区等危险请求在用户已关闭确认框后才返回的异步失败，以所属页面的单操作基础错误对话框反馈；原确认框仍打开时在当前任务中显示错误。
- [x] 3.9 Regular/Wide 账单详情面板填满视口高度；仅逻辑起始侧保留 Regular 16 dp/Wide 56 dp 留白，逻辑末端与视口齐平，最大宽度为 560 dp。浏览器几何断言覆盖三类尺寸。
- [x] 3.10 让账单详情中的直接操作菜单在 Web 上接入 Escape 弹层栈，确保第一次 Escape 只收起菜单、保留对话框；复核共享 `ChoicePicker` 已有的菜单 Escape 处理。
- [x] 3.11 将投资事件与持仓只读详情在 Compact/Regular/Wide 统一为居中基础对话框，并固定唯一的“关闭”知悉操作。
- [x] 3.12 给“取消关联”与“解散关联”增加准确说明影响、保留流水的 M3 确认弹窗；确认前不得调用现有关联移除接口，弹窗显示时暂时隐藏详情面板，取消恢复原详情。
- [x] 3.13 为账本详细筛选中的锚定选项菜单建立菜单优先的 Escape 关闭语义；Web Compact 禁用底层 Bottom Sheet 默认 Escape 解散，使用弹层栈优先级保证先关菜单再关面板，保留 Native Material 默认返回行为。
- [x] 3.14 将 Compact 流水编辑与独立关系创建的确认/取消动作固定在全屏面板底部；详情的更多/编辑操作固定在页脚并按主次排序，详情与编辑复用面板，关系创建独立任务；删除和关系移除使用唯一活动 M3 AlertDialog，取消返回详情、确认才调用既有操作。
- [x] 3.15 修正过时的浏览器断言：详细筛选日期在日历“完成”时立即更新查询，日历“取消”或 Escape 不提交临时日期；筛选面板关闭保留已应用筛选。基础确认状态以真实可见画面和具体操作结果为准，避免将 Compose Web 语义镜像残留视为弹窗未打开。
- [x] 3.16 复核全部自定义 Dialog 的安全区与可访问标题；将流水关联创建的 Regular/Wide 确认/取消动作固定在对话框底部，并确保保存、删除、关联等主要动作按写入权限控制且请求中仅由业务锁拦截重复提交。
- [x] 3.17 按具体失败对象修正分类和工作区删除错误弹窗标题，保留原错误消息及确认关闭行为。
- [x] 3.18 按 M3 弹窗层级修正次级状态：导入任务按 Compact 覆盖确认与 Regular/Wide 原容器切换处理；现金流水的丢弃、影响确认、删除、关系移除和错误知悉以 M3 AlertDialog 独立显示，暂时隐藏原面板并在取消/知悉后恢复任务与草稿。
- [x] 3.19 确保 Compact 流水编辑中的日期/时间选择器以内联状态展开；保存或关联失败后仍可见错误并保留有效重试入口。
- [x] 3.20 修正较宽窗口基础任务容器：短内容按内容收缩，超出高度上限后只滚动正文；标题/正文/操作区符合 M3 结构，不重复提供标题栏关闭与取消按钮，Escape、遮罩及当前“取消/返回”按草稿/请求状态执行。
- [x] 3.21 将流水详情的编辑和更多操作固定在模态底部，并在 390/768/1440 px 浏览器回归中检查操作位置和弹窗滚动行为。
- [x] 3.22 保证新账户编辑在 Compact、Regular、Wide 三种呈现中，名称为空时“应用”不可提交；恢复有效名称后可继续应用。
- [x] 3.23 现金流水详情与编辑共享同一个模态容器：Compact 均全屏，Regular/Wide 均为右侧全高面板；编辑只替换原面板内容。新增关联为独立 M3 任务，使用相同断点尺寸。删除、关系移除、放弃、影响确认和错误使用唯一活动 M3 AlertDialog，取消/知悉后恢复精确的详情、编辑或关联任务状态。
- [x] 3.24 流水面板的任务状态变化同步更新 Web 可访问语义标题和控件；通过状态切换回归确认已关闭状态的控件不再作为活动模态内容暴露。
- [x] 3.25 撤销第二轮把 Compact 只读流水详情改为基础对话框的产品结论；按用户确认恢复详情/编辑共用全屏合同，并确保 Regular/Wide 右侧面板从标题到页脚填满可用高度。所有 M3 确认/错误弹窗显示时，底层账单任务面板暂时不渲染。
- [x] 3.26 为导入阶段和模态子任务切换更新可访问语义子树，确保视觉已进入新阶段时，辅助技术和 Web 语义层也只暴露当前阶段的标题与控件。
- [x] 3.27 当账户名称为空时，在 Compact/Regular/Wide 编辑对话框中同时禁用应用操作并暴露 disabled 语义，恢复有效名称后再启用。
- [x] 3.28 落实 M3 Side Sheet 与用户补充操作合同：Regular/Wide 账单模态面板使用 `surfaceContainerLow`、Large shape、零 elevation、末端贴边与可关闭 scrim；沿末端拖动时面板跟手移动，达阈值后滑出屏幕，未达阈值则回弹；Compact/Regular/Wide 编辑与关联操作固定在底部；详情编辑主操作最右；所有非 AlertDialog 模态左上提供关闭；AlertDialog 操作只显示文字。

## 4. 审查

- [x] 4.1 第二遍人工复核用户任务、影响范围、跨端一致性、账单详情/编辑/关联创建的响应式合同、Regular/Wide 全高右侧面板、标题栏关闭与任务内取消是否重复、滚动正文与固定操作区、模态状态变化后的无障碍标题/控件、关闭/确认/错误路径、取消动作可用性、日期暂存语义、各任务至多一个活动模态、删除范围、关系移除影响确认、异步请求、权限变化和导入草稿处置以及最终 diff；finding 与处置回写本文件。
- [x] 4.4 依据 [Material 3 Side Sheet specs](https://m3.material.io/components/side-sheets/specs)、[Material 3 Dialog specs](https://m3.material.io/components/dialogs/specs) 与 Hallmark `audit` skill instructions 复核最终 Compose UI、390/768/1440 截图、层级与关闭/操作合同；按严重度记录的问题均已处置，最终无未解决 finding。

### 审查发现与处置

- `major`（2026-10-18，导入映射信息可见性）：Regular/Wide 导入对话框中的来源账户与目标账户选择并排展示，ChoicePicker 仅 190 dp，名称、类型和币种无法完整扫读。将映射卡改为纵向布局，选择框使用对话框完整内容宽度；增加三尺寸浏览器宽度断言，三尺寸断言与浅色/深色账户编辑全流程已通过，截图复核完成。
- `major`（已关闭）：Regular/Wide 流水编辑中的“放弃修改”状态把一行正文和操作行放进 `fillMaxSize` 的滚动正文，导致短任务仍占满基础对话框最大高度，且操作跟随正文而非固定操作区。将操作移至 `CashRecordModalPanel` 固定页脚，正文按内容收缩；同样将编辑、影响确认、删除、关联移除和独立关系创建的宽屏操作统一固定在页脚。三尺寸截图与交互回归通过。
- `major`：流水面板切换任务后，Compose Web 语义树仍暴露上一个任务的标题和控件。按 `sizeClass + title` 重建面板语义子树，并新增状态切换浏览器回归用例。
- `major`：Compact 导入请求中点关闭会在全屏任务上再叠一层信息 `AlertDialog`，与任务合同不符，也把“继续处理”与主任务分离。改为在现有全屏任务内容区显示等待信息和继续入口，并覆盖扫描完成后的丢弃确认。
- `minor`：流水关联创建状态的面板标题在正文重复一次。移除正文重复标题，保留固定面板标题和正文分隔。
- `minor`：Regular/Wide 流水详情进入编辑/删除/关联任务时同时显示标题关闭和任务内取消。隐藏重复关闭，只读详情保留标题关闭，Compact 保留全屏关闭。
- `minor`：关联创建和删除范围用例依赖视口外的语义代理和不存在的 Web `aria-checked` 属性。测试改为滚动实际面板正文，再以可见选择状态截图变化验证范围切换；API 调用仍用于确认操作边界。
- `minor`：日期筛选关闭后，Compose Web 保留隐藏的语义镜像根节点，导致对话框根 testTag 仍存在；这不是可见面板未关闭。日期用例改为验证面板内容语义节点已移除、截图变化和筛选请求结果，不再用根节点计数判断打开状态。
- `major`（已由用户修正）：第二轮视觉与业务复核曾建议将 Compact 只读流水详情改为基础对话框，并把删除/关系确认嵌入详情面板。用户明确账单详情应 Compact 全屏、Regular/Wide 右侧全高；编辑与详情共用容器，关联创建独立 M3 任务，删除/关系移除/放弃/错误使用独立 M3 AlertDialog。后续实施和测试以此最终产品合同为准。
- `major`（2026-10-18 全高面板几何复核）：Regular/Wide 面板原实现把 16/56 dp 留白同时加在四边，导致顶部和底部没有贴合视口；宽度也因 92% 系数降至约 515 dp。按用户确认的右侧全高合同，修正为上下贴合视口、只保留水平留白并使用 560 dp 最大宽度；390/768/1440 几何断言与截图通过。
- `major`（2026-10-18 Web 基线复审）：Compact 导入进入“关系配对”后，标题栏“确认导入”绑定到 `runPrimaryAction`；该阶段分支为空，实际点击不提交。全流程浏览器用例停留在关系配对页，已确认是产品业务缺陷，需改为调用 `commitImport()`，并验证成功状态、重复提交保护和失败重试。
- `major`（2026-10-18 M3 语义状态复审）：导入步骤从映射切换到核对后，Web 截图显示了“核对流水”和核对内容，但语义快照仍只有前一阶段的账户类型按钮，导致辅助技术无法感知当前阶段。按 `stage + modal subtask` 为 `SecondaryPageContainer` 提供状态 key，重建当前阶段语义子树，同时保留 ViewModel 中的已提交状态和暂存数据。
- `minor`（2026-10-18 键盘层级复核）：账户类型菜单第一次 Escape 已视觉关闭，账户编辑仍留在 Compact 全屏导入任务上；第二次 Escape 返回映射阶段。Playwright 不能稳定定位编辑关闭后的 compact 页头动作（Compose Web 语义镜像时序），回归用例改为按可见页头动作实际坐标继续执行，并仍断言预览、关系配对和导入 API 结果。
- `major`（2026-10-18 无效表单状态复核）：Compact 新账户编辑的空名称按钮视觉灰显，但 Web 语义仍把它暴露为可用 `button`，辅助技术无法获知提交不可用。Regular/Wide 操作也显式同步 `disabled()` 语义，并为 Compact/Regular/Wide 增加可见操作状态断言；“应用”保持禁止，恢复名称后才可继续。
- `major`（2026-10-18 Web 基线复审，已关闭）：Regular 夜间主题文件选择页的捕获画面中，上传区标题、格式说明和按钮文字未呈现为可辨识的亮色文字；亮度断言最大值为 41。语义快照仍有这些文字，已修复并在 768 px 夜间主题文件选择页回归通过。
- `minor`（2026-10-18 账单详情语义回归，已复核）：错误知悉按钮点击后，先等待 Compose Web 画面更新再断言 AlertDialog 语义节点移除；390/768/1440 px 浅色流程全部通过。此前直接在点击后立即计数造成时序断言失败，实际弹窗已从画面消失；调整后显式检查了渲染差异与语义节点移除。后续暗色保存错误与重试流程单项补跑通过（9.5 秒）；此前的超时未复现。
- `minor`（由用户产品合同修正）：Web 测试曾将 Compact 只读详情的居中基础对话框当作目标；该断言已撤销，改为验证流水详情与编辑共用 Compact 全屏容器，Regular/Wide 共用右侧全高面板。
- `minor`（Web 测试交互驱动不足）：编辑草稿与关联影响用例未能清空 Compose Web `contenteditable` 输入，导致备注被保留为旧文本、金额被追加为 `28.501.00`；修正测试输入驱动后再验证草稿保留、影响确认与防重复提交，不以这两次运行判定业务实现通过或失败。
- `minor`（Web 导航测试驱动，已关闭）：完整回归中投资事件页面的浏览器后退断言一次未离开当前路由；将强制点击导航语义代理改为点击其渲染坐标，隔离用例连续 3 次通过。未发现导航实现变化或浏览器控制台错误。
- `minor`（Web 语义代理断言失准，已关闭）：详细筛选、ChoicePicker、批量分类、批量删除、导入丢弃提示与关系阶段用例有的定位到隐藏语义副本、有的断言旧 testTag/已移除页脚。页面快照显示部分目标状态已可见；测试改用实际可见界面、请求结果和操作后状态，不使用语义根节点计数作为唯一关闭证据。账户筛选与菜单选择后收起状态均在最终全量 Web 回归中通过。
- `major`（全量回归中的非对话框项，已关闭）：账本自动分页用例最初未找到下一月分组；修正测试滚动容器后，追加下一页与追加失败重试均在最终三尺寸 Web 全量回归中通过。
- `major`（2026-10-18，Material 3 Side Sheet 几何与层级，已关闭）：Regular/Wide 账单任务虽从逻辑末端打开，但仍把边缘留白施加到四边、收缩高度并使用基础 Dialog 的 `surfaceContainerHigh`/圆角层级，因而不像 M3 Modal Side Sheet。将面板改为仅逻辑起始侧留白、末端贴边且全高，使用 `surfaceContainerLow`、`MaterialTheme.shapes.large`、0 dp elevation 和单一 scrim；补入遮罩点击与水平拖动关闭，并在 768/1440 px 验证几何与行为。
- `major`（2026-10-18，Side Sheet 拖动关闭的 Web 手势，已关闭）：最终浏览器复核确认 Compose Web 的旧 `draggable` 实现收到鼠标拖动仍未移动或关闭面板。改用 Foundation `detectHorizontalDragGestures`，沿逻辑末端累计偏移；超过 120 dp 后以 180 ms 动画滑出完整面板宽度再退出，未达阈值则动画回弹。失败轨迹确认旧实现未触发；最终浏览器用截图差异断言拖动中面板实际移动，单项与最终九项 Chromium 回归均通过。
- `major`（2026-10-18，任务操作和关闭入口，已关闭）：Compact 编辑/关联的提交动作曾留在标题区，其他自定义模态也未统一在左上提供关闭；将任务动作固定到底栏，次要动作在左、主要动作最右，所有非 AlertDialog 在左上关闭。详情页脚将“更多操作”放左、“编辑”放最右。覆盖账单详情/编辑/关联、导入、筛选、DatePicker、投资详情，并增加几何回归断言。
- `minor`（2026-10-18，AlertDialog 操作图标，已关闭）：关系移除的危险确认按钮带 `link_off` 图标，不符合用户补充的 AlertDialog 纯文字操作合同；移除图标并保留危险色、文字标签及原确认行为。关联确认三尺寸截图证实操作区纯文字且取消靠左、确认最右。
- `minor`（2026-10-18，Compose Web 语义镜像时序，已关闭）：流水详情遮罩关闭和投资 DatePicker 关闭后，Compose Web 会短暂保留不可见/旧状态语义节点；移除以语义节点计数作为唯一关闭证据，改用截图/背景像素确认模态视觉关闭，并在随后页面内容断言前重载同一路由以重建语义镜像。真实 UI 关闭路径通过。
- `minor`（2026-10-18，关联区取消后的回归预期，已关闭）：Side Sheet 关闭拖动修正后的完整组回归暴露一条既有关联用例假设错误：取消移除确认应恢复原先已展开的关联卡，测试却再次点击折叠按钮并期待仍展开。按“恢复原任务状态”的合同改为直接断言已展开内容恢复；不改业务行为。
- 2026-10-18 全量 Web Playwright 基线：`cd compose/qa/web && FT_CAPTURE_COMPOSE_SCREENSHOTS=1 npm run test:e2e`，生产预览启动器使用新构建/新服务；78 项中 64 通过、14 失败，失败项与分类见上。Node 输出 `NO_COLOR env is ignored due to FORCE_COLOR` 警告；Playwright 没有报告浏览器控制台错误。截图输出位于 `compose/qa/openspec/changes/compose-cash-ledger-redesign/screenshots/current-review-20261017/` 与 `compose/qa/openspec/changes/compose-cash-import-account-mapping-redesign/screenshots/`。
- 本轮严格校验：`openspec validate compose-material3-dialog-conformance --strict` 通过；`git diff --check` 通过。最终严格校验与浏览器回归结果见 5.5；全量回归中出现的测试驱动问题均做单项补跑。
- 复核方式：完成实现后第二遍对照最终 diff、规格和浏览器截图，并依仓库本地 Hallmark skill 的 `audit` 流程审阅反 slop 清单；详情子合同记录见 4.3，整个 change 的逐页结论与评分见 4.2。
- [x] 4.2 最终 UI 形成后依 Hallmark skill `audit` 对照 OpenSpec、共享 Compose 实现和三尺寸截图；范围覆盖账本筛选/列表、详情/编辑/关联、导入、投资详情、分类与工作区确认。六项评分：理念 4、层级 4、执行 4、具体性 4、克制 5、变化 3；0 critical、0 major、0 minor。无待修复 finding，任务层级、M3 表面与主题 token 一致；Regular/Wide 全高留白由产品合同要求。结论：接近可交付，当前范围无 Hallmark finding。
- [x] 4.3 账单详情子合同的 Hallmark 专项审计：按 `.agents/skills/hallmark/references/verbs/audit.md` 的 Hallmark `audit` skill 流程（不走 CLI）复核 Compose 实现及 390/768/1440 px 浅色/暗色截图。视觉审计结果：0 critical、0 major、0 minor；摘要、信息卡、关联卡和中性全高面板层级清楚；无卡片套卡片、重复帮助文案或自造颜色。另按 M3 Side Sheet 行为复核发现拖动手势未接入 Web 鼠标拖动，已依 4.1 修复并通过浏览器回归。此项说明账单详情/编辑/关联子范围，不替代 4.2 对整个 change 的审计。

## 5. Web 验证与无障碍

- [x] 5.1 执行 OpenSpec strict 校验、Compose Web 生产构建、Node/Playwright QA 和 `git diff --check`；记录命令、结果、当前 HEAD、基线和执行时间。本包没有 Vitest 脚本，Node QA 覆盖其构建器测试。
- [x] 5.2 使用真实浏览器验证主流程、确认/取消/Back/Escape、日期错误或空值、加载/错误状态、菜单层级、滚动与键盘交互；在 390/768/1440 px 与亮/暗主题截图，覆盖 Compact 全屏流水详情和编辑、Regular/Wide 右侧全高面板与布局切换、独立关系创建、唯一活动 M3 确认/错误弹窗、只读投资详情、宽屏边缘留白、筛选菜单优先 Escape 与关闭后异步错误。
- [x] 5.3 将浏览器、URL、视口、操作步骤、截图路径和控制台/网络错误写入本 tasks；Web 之外的平台标注不运行的原因与残余风险。
- [x] 5.4 完成现金流水详情子合同 Web QA（2026-10-18，结束于 07:20 UTC；`HEAD`=`70f0283550e2c8e8925818bfec9143bad5905810`，比较基线 `origin/main` 同一提交，工作树另有未提交改动）。
  - 浏览器：Playwright 默认 Chromium；Compose Web 生产预览 `http://127.0.0.1:<运行器分配端口>/w/workspace-1`；视口 390×844、768×1024、1440×1000。
  - 浅色执行：`FT_CAPTURE_COMPOSE_SCREENSHOTS=1 npm run test:e2e -- --grep 'F-04 (编辑流水在详情面板内切换|Compact 与较宽窗口丢弃流水编辑前会确认|保存处理中保留可用操作|新增、详情和编辑面板适配|关联影响确认在请求中保持|流水删除确认在三类窗口|流水删除进行中确认保持|已关联流水的解散关联与删除选项)'`；7 项通过，错误知悉项最初因直接计数时序断言失败，改为先等画面更新、再验证弹窗截图差异与语义节点移除，随后单项三尺寸通过。之后详情/编辑与关联拆分截图回归 2 项通过；补充错误路径显式节点断言后该项三尺寸通过。
  - 暗色执行：`FT_COMPOSE_COLOR_SCHEME=dark FT_CAPTURE_COMPOSE_SCREENSHOTS=1 npm run test:e2e -- --grep 'F-04 新增、详情和编辑面板适配|F-04 已关联流水的解散关联与删除选项使用危险色'`，2 项通过；另一次关联提交任务尺寸回归 1 项通过。暗色错误确认流程已于 2026-10-18 另行补跑通过（9.5 秒）。
  - Node QA：`cd compose/qa/web && npm run test:node`，3 项通过；本包没有 Vitest 脚本或 Vitest 用例，故使用仓库现有 Node 构建器测试。
  - 截图：浅色详情/编辑/删除确认/关联展开/独立关联面板在 `compose/qa/openspec/changes/compose-material3-dialog-conformance/screenshots/`；暗色详情/编辑/关联展开/独立关联面板在其 `dark/` 子目录。
  - 生产预览每次重新执行 `:webApp:wasmJsBrowserDistribution --no-configuration-cache`，缺失或过期时加 `--rerun-tasks`。Node 仅提示 `NO_COLOR` 与 `FORCE_COLOR` 并存；Playwright 未报告浏览器控制台/网络错误。按用户要求未运行 Android/iOS；跨端共享代码仍有未验证风险。

- [x] 5.5 最终 Web 回归与审查补跑（2026-10-18，`HEAD`=`70f0283550e2c8e8925818bfec9143bad5905810`，`origin/main` 同一提交；实现留在未提交工作树）。`FT_CAPTURE_COMPOSE_SCREENSHOTS=1 npm run test:e2e` 使用 Playwright Chromium 与生产预览，78 项中 77 项通过；唯一失败为 F-09 投资事件浏览器后退用例，强制点击语义代理后一次仍停留于目标 URL。改为按渲染坐标点击后，隔离该用例连续 3 次通过（30.8 秒）。上次批量删除用例的测试驱动修正已在此次全量中通过。导入浅色/深色的 390/768/1440 全流程通过（46.6 秒）；此前暗色保存错误与重试流程补跑通过（9.5 秒）。78 项均有成功证据，但本轮全量没有单次 78/78 绿灯。验证命令：仓库根目录 `openspec validate compose-material3-dialog-conformance --strict`、`git diff --check`；`compose/qa/web` 下 `npm run test:node`（3/3）、完整 `npm run test:e2e`（77/78）及 F-09 单项 `--repeat-each=3`（3/3）。浏览器为 Chromium，生产预览地址 `http://127.0.0.1:<运行器动态端口>/w/workspace-1`，视口 390×844、768×1024、1440×1000，覆盖浅/深主题。截图位于 `compose/qa/openspec/changes/compose-material3-dialog-conformance/screenshots/`、`compose/qa/openspec/changes/compose-cash-ledger-redesign/screenshots/current-review-20261017/` 和 `compose/qa/openspec/changes/compose-cash-import-account-mapping-redesign/screenshots/`。Playwright 无意外控制台/网络错误；Node 输出 `NO_COLOR` 与 `FORCE_COLOR` 并存警告，受控 503 来自错误流程夹具。Playwright WebServer 通过 `start-compose-demo.mjs` 执行当前 Compose Web 生产构建；依输入指纹决定复用或强制重跑 Gradle。按用户要求未运行 Android/iOS，仍保留共享 Compose 代码未在设备验证的风险。

- [x] 5.6 当前离线演示复核（2026-10-18，Compose Wasm 生产包，`FT_COMPOSE_DEMO_MODE=true`）：`start-compose-offline-demo.mjs` 检测到旧产物后执行完整 Gradle 构建；本机预览 `http://127.0.0.1:5220/w/demo-workspace/`，内网预览 `http://192.168.1.11:52916/w/demo-workspace/`。用 Playwright Chromium、`zh-CN` locale 在 390×844、768×1024、1440×1000 依次打开流水详情、切换编辑、Escape 返回详情、打开独立关联任务，三尺寸全部通过。截图分别保存在 `screenshots/offline-demo-20261018/compose-demo-{compact,regular,wide}-{detail,edit,relation}.png`。直接访问 QA 专用 `workspace-1` 不属于离线演示种子；改用实际演示工作区 `demo-workspace` 后列表正常显示。内网代理与预览均返回 HTTP 200；页面正常渲染，Kotlin/Wasm 每次启动输出一次 `wasmExports.memory` 弃用提示，不影响交互。演示进程和内网代理保持运行供用户复核。
- [x] 5.7 执行本轮 M3 Side Sheet / 操作区 / 关闭入口修正后的三尺寸真实浏览器 QA；覆盖详情/编辑/关联、scrim 与拖动关闭、导入关闭和底部操作、筛选与 DatePicker 左上关闭、投资详情左上关闭、AlertDialog 纯文字操作；更新截图及 console/network 结果。
  - 2026-10-18 最终组回归（截至 14:56 UTC）：`cd compose/qa/web && FT_COMPOSE_PREVIEW_PORT=53186 FT_CAPTURE_COMPOSE_SCREENSHOTS=1 npm run test:e2e -- --grep 'F-03 详细筛选按字段语义分组并适配窗口宽度|F-04 编辑流水在详情面板内切换|F-04 新增、详情和编辑面板适配|F-04 右侧详情面板点击遮罩后关闭|F-04 Regular/Wide 详情 Side Sheet 可沿末端方向拖动关闭|F-04 已关联流水的解散关联与删除选项使用危险色|F-05 cash-import uses the Material 3 dialog variant|F-08 持仓可查看估值详情|F-09 投资事件可加载下一页并查看证据'`，9/9 通过，约 2 分钟。Playwright Chromium 使用当前 Compose Web 生产预览 `http://127.0.0.1:53186/w/workspace-1`；覆盖 390×844、768×1024、1440×1000，导入页另覆盖 1440×900，筛选还覆盖 320/375/414 px。详情/编辑/关联在 3 尺寸检查，侧边面板在 768/1440 检查点击遮罩、低于阈值后回弹、超阈值拖动跟手并滑出关闭；DatePicker 与投资详情逐尺寸检查关闭按钮位置。导入检查全尺寸左上关闭和取消/继续固定底栏且主操作在右。最终 SideSheet、筛选、日期选择、关联确认截图位于 `compose/qa/openspec/changes/compose-material3-dialog-conformance/screenshots/`；导入首屏截图位于 `compose/qa/web/test-results/evidence/compose-import-file-{390,768,1440}.png`。
  - 过程处置：旧 `draggable` 的 Web 拖动回归未通过，换用 `detectHorizontalDragGestures` 与完整宽度滑出/阈值回弹动画后，Regular/Wide 单项及拖动中截图位移断言通过；后续整组运行暴露一处回归用例错误地假设取消关联确认会折叠已展开区域，改为验证取消后原展开状态恢复，最终九项整组通过。
  - 浏览器诊断：通过用例的 `afterEach` 对 console error 作零错误断言；未观察到意外控制台或网络失败。Node/WebServer 只提示 `NO_COLOR` 与 `FORCE_COLOR` 并存的环境警告。`npm run test:node` 3/3 通过；`openspec validate compose-material3-dialog-conformance --strict` 通过；最终 `git diff --check` 结果记录于此项更新后复跑。生产预览由 `start-compose-demo.mjs` 按当前输入指纹构建，没有复用旧 bundle。
  - 本轮 `HEAD`=`70f0283550e2c8e8925818bfec9143bad5905810`，比较基线 `origin/main` 相同；改动仍在工作树，未提交。按用户要求只验证 Web，Android/iOS 未运行；因 `commonMain` 共享实现，Native 运行表现仍未由本轮设备 QA 覆盖。
  - 2026-10-18 提交前复验（15:22 UTC，基准 `HEAD`=`70f0283550e2c8e8925818bfec9143bad5905810`）：`cd compose/qa/web && FT_COMPOSE_PREVIEW_PORT=53186 npm run test:e2e -- --grep 'F-03 详细筛选按字段语义分组并适配窗口宽度|F-04 编辑流水在详情面板内切换|F-04 新增、详情和编辑面板适配|F-04 右侧详情面板点击遮罩后关闭|F-04 Regular/Wide 详情 Side Sheet 可沿末端方向拖动关闭|F-04 已关联流水的解散关联与删除选项使用危险色|F-05 cash-import uses the Material 3 dialog variant|F-08 持仓可查看估值详情|F-09 投资事件可加载下一页并查看证据'` 最终 9/9 通过（约 1.8 分钟）。先前同组一次运行 8/9，唯一失败是取消关系确认后查找折叠按钮；该用例隔离重跑 1/1 通过，随后同组重跑 9/9，未复现，不需代码修复。`npm run test:node` 3/3、`openspec validate compose-material3-dialog-conformance --strict` 与 `git diff --check` 均通过；浏览器仅有 `NO_COLOR`/`FORCE_COLOR` 环境警告。此次复跑未启用截图捕获，既有三尺寸证据保留自上述最终截图回归。

## 6. 发布准备

- [ ] 6.1 按用户授权将本 change 直接提交并推送到 `main`；提交前检查 `git status`、`git diff --check`、影响范围与验证结果，并在完成后记录提交 ID 和推送结果。重大 UI change 仍须等待用户复核 Compose 演示结果后才能标记完成。
- [ ] 6.2 等待用户复核 Compose 演示结果后再将 change 标记完成；演示需覆盖 `COMPACT`、`REGULAR`、`WIDE`。
- [x] 6.3 无数据迁移；回滚通过还原本次提交完成。发布后重点观察 Compact 全屏详情与独立关联任务、Regular/Wide 全高面板，以及删除/关系移除确认和异步错误知悉路径。

## 7. 反思

- [x] 7.1 共性缺陷包括自定义容器重复 scrim、全屏留空、重复退出入口；Compose Web 语义镜像可能落后于实际画面，固定坐标会因响应式重排失准。后续优先使用 Material 3 标准组件、明确任务/确认层级、视觉截图断言，并使用构建输入指纹防止陈旧演示产物。
