## 1. 思考

- [x] 1.1 通过 `grill-me → grilling` 确认保留现有外观、原详情容器和在流水内处理关系；对照当前列表、详情与维护协议核实缺口。

## 2. 计划

- [x] 2.1 完成 proposal、delta 与 design，明确列表／详情／补处理边界、公共基础、文件所有权及三模块依赖。
- [x] 2.2 完成独立规划复核及 OpenSpec strict，确认旧 UI delta 不与新责任重复；只声明规划就绪。

## 3. 任务拆分与一致性

- [x] 3.1 先补当前读取、`entry`／来源权限、维护所有意图、公共冲突／继承、草稿保存、计划／收据／核实的协议失败测试；验证 null、清空、修订、字符串金额和工作区隔离。
- [x] 3.2 完成公共基础与宿主：
  - [x] 3.2.1 当前 DTO／domain／Repository、读取及维护 API、公共冲突和定位引用通过测试与构建。
  - [x] 3.2.2 接入编辑宿主和稳定定位行为，冻结集中声明的文件归属。
- [x] 3.3 提交公共基础小步，让导入和编辑模块基于同一版本开发；合成合同夹具、语义 ID／文案与最终 App 集成接口分别登记所有者。

## 4. 构建

- [x] 4.1 以失败测试接列表状态／未知值／统计／待确认快捷筛选和快照翻页；保留原筛选、月汇总及批量选择行为。
- [x] 4.2 以失败测试切换当前详情，整笔公共值总览、待处理区域和折叠只读来源，移除来源成员编辑入口。
- [x] 4.3 以失败测试实现草稿普通保存、明确转正式、来源采用／保留及实际身份问题处理；不自动转正式或换目标。
- [x] 4.4 以失败测试实现已有完整关系组确认／延后／拒绝／解除、公共冲突选择及解除继承；移除任意关联搜索和直接关系写入。
- [x] 4.5 接入编辑器插槽、当前分类／删除影响及保存后的稳定定位，集成合成演示；删除无人使用的旧详情和维护适配，未实现动作保持准确受限状态。

- [x] 4.6 按修改前视觉基线恢复金额摘要、业务字段分组及主要操作位置，保持现有新协议和用户已确认流程。

### 4.7 草稿已填资金校验

- [x] 4.7.1 思考／澄清：用户确认草稿已填资金矛盾直接拒绝，合法缺项继续可保存；复用现有全部字段业务分组与详情容器。
- [x] 4.7.2 计划：补 delta 与 design，读取 UI 规则和现有 token；Material 3 Text fields Overview／Specs 正文已由真实浏览器读取，Hallmark 预检不新增层级、组件或视觉样式。
- [x] 4.7.3 测试先行：后端矛盾／坏账户先红；共享表单校验辅助函数缺失编译先红，补联动原子现金部分与矛盾分摊的单测。
- [x] 4.7.4 构建：共享草稿输入仅发送真实修改，校验时模拟后端原子字段联动；错误在当前区域提示并保留输入，不生成补救事项。共享失败派生 API 退役，原命令核实保留。
- [x] 4.7.5 审查：最终 Compose、文字必要性、术语及 Hallmark audit，独立复核清零阻断项。
- [x] 4.7.6 验证：Web 1440×900 浏览器冒烟；Android／iOS 相称编译单测，设备 QA 不运行；后端证据见 lifecycle 4.28。
- [x] 4.7.7 发布：功能地图、文档和技能原位核对，保留整体演示确认待办，按已有授权提交推送同分支。
- [x] 4.7.8 反思：表单与后端共同拒绝明确矛盾，避免修复导入表单却遗漏主页草稿入口。

### 4.8 共享主页与导入预计详情摘要

- [x] 4.8.1 思考／澄清：用户确认两处详情表达同一收支对象，要求主页详情采用导入预览的平面字段排版；状态与操作随“待写入／已保存”保持各自语义。
- [x] 4.8.2 计划：更新详情要求和文件归属；正式读取 Material 3 Cards Guidelines／Specs 与 Grids & Spacing 正文，采用相关内容靠近、用留白分组、不强制包入卡片的规则。Cards Overview 页面未加载正文，不作为决策依据。
- [x] 4.8.3 测试先行：主页详情回归已增加平面字段、草稿提示和账单原文断言；当前正式 QA 构建在旧代码上有效失败，指出“流水信息”仍出现 1 次。离线演示包因其内存登录绕过 API 夹具，失败不计入测试证据；切换正式 QA 启动器后确认红测。
- [x] 4.8.4 构建：主页和导入预览共用 `CashRecordSummary`、`CashRecordStatusBanner` 与账单原文折叠入口；删除导入页重复摘要与原文标题；两处详情均取消“流水信息”卡片，导入差异／关联与主页维护操作仍归各自页面。
- [x] 4.8.5 审查：按 Hallmark audit 参考执行人工视觉审查。390 px 截图确认金额、对方、时间和标签值层级清楚；768／1440 px 与首页列表配合正常；长字段可读、按钮语义可操作；无卡片重复层级、裁切或新增装饰。未发现 critical／major；官方 M3 卡片与间距正文支持按留白组织摘要。
- [x] 4.8.6 验证：Web Chromium 正式 QA 主页详情及导入只读详情用例最终均通过；首轮导入原文检查使用了变更后失效的文案定位，改用稳定 testTag 后 390／768／1440 px 均通过。Android 强制重跑共享代码编译与 Host 单测通过；iOS 按用户决定不验证。命令、HEAD、浏览器、视口、截图和限制见下方“4.8 共享详情验证”。
- [x] 4.8.7 发布准备：已同步导入 workflow、maintenance 规格与功能地图；OpenSpec strict 35/35、diff 检查通过。已把实际主页 Web 截图留在忽略的 QA 结果目录供确认；重大 UI 的演示接受尚待用户确认，故不标记整体 change 完成或归档。
- [x] 4.8.8 反思：两处页面表达同一业务对象，共用稳定的显示摘要和原文折叠；预计投影／已保存记录的数据映射及各自状态、差异、关系和维护操作仍分开。移除“流水信息”标题资源，不再维护两套摘要布局。

### 4.9 收支详情的关联流水与原文层级

- [x] 4.9.1 思考／澄清：用户确认详情顺序为摘要 → 关联流水 → 账单原文；关联流水默认展开，账单原文默认折叠。待处理警示紧接摘要；导入详情只读显示候选，主页可接受一个完整方案或拒绝整组；证据不足仅显示待确认状态。
- [x] 4.9.2 测试先行：浏览器回归覆盖已保存多成员、单笔无候选、多候选、整组拒绝后刷新及接受完整方案；证据不足时隐藏通用错误的回归先失败后通过。关系计划 SQLite 契约覆盖拒绝后重读。
- [x] 4.9.3 实现关联区的实际成员和候选分组；候选标识清楚，接受一个完整方案、整组拒绝、暂不处理；隐藏无内容的关联区。
- [x] 4.9.4 将“账单原文”移到关联区后，默认折叠并显示实际来源成员原始字段；导入预计详情保持同一信息顺序且候选只读。`insufficient_evidence` 只显示关系待确认状态，不映射为通用导入错误。
- [x] 4.9.5 对照 390 px 全屏和 1440 px 右侧详情完成 Web 冒烟、可见文字自检、Hallmark audit 人工替代及截图复核；已修复证据不足候选被误报为通用导入错误的问题，无未解决 critical／major finding。重大 UI 的用户演示验收仍单列于 6.5。

### 4.10 已保存关联流水详情加载修复

- [x] 4.10.1 思考／澄清：用户要求尝试修复真实工行账单 QA 中记录的问题。已保存的两成员投影在账本详情持续转圈；读取合同本应展示实际成员。根因是列表摘要版本与详情引用相同就跳过证据请求，导致证据一直为空。保持现有详情信息架构和 Compose 版本；不改财务写入、关系算法或数据库。
- [x] 4.10.2 计划：使用现有来源证据接口，证据缺失时即使列表摘要版本相同也读取；读取失败时在关联区显示原有错误提示及重试操作，不无限转圈。先用版本匹配的两成员夹具复现，再修复共享 Compose 页面；Web 实际浏览器检查列表打开、成员展示和失败重试。Android／iOS 只做相称编译与单测，不执行设备 QA。
- [x] 4.10.3 测试先行：版本相同的列表投影打开两成员详情时，Chromium 回归先因缺少成员而失败；证据接口首读 503 时原位重试回归先因无重试入口而失败。两项均使用去标识化 API 夹具，无真实账本写入。
- [x] 4.10.4 构建：证据为空时按详情引用读取，即使列表摘要版本相同也不跳过；失败时在关联区显示原有来源读取错误与重试，不更改成员和原文布局。
- [x] 4.10.5 审查：对照 UI 规范、已读 Material 3 列表／加载指示器正文和真实详情截图检查关联成员、加载失败、重试及文字必要性；用户确认修复版页面。按 Hallmark audit 参考人工审查及范围 diff 复核，0 critical、0 major、0 minor；Hallmark 专用运行工具不可用，未声称执行了工具审计。
- [x] 4.10.6 验证：Web 当前详情与导入浏览器回归 37/37、Node 3/3、Wasm 单测及 Android 主机编译／单测通过；真实账本详情证据 GET 200、2 条成员显示。OpenSpec strict 35/35、diff 检查通过。iOS 按用户要求未运行。
- [x] 4.10.7 发布准备：README 无入口变动、`docs/cash-ledger/cash-ledger.md` 和功能地图已描述当前关联展示，本次不新增页面或覆盖声明；演示 `http://127.0.0.1:5187/health` 返回 200，用户已确认此修复版详情。整体 change 的其他演示与验收仍按原任务保持待办；未获 `develop` 的提交推送授权，本次保留工作树。
- [x] 4.10.8 反思：浏览器回归固定“列表摘要版本与详情引用相同，证据仍为空”场景，禁止用投影摘要代替来源证据；读取失败另固定原位重试。Compose 1.12.1 的弹层关闭后无障碍树停更发生于框架语义镜像，局部重组／自动化坐标点击不能恢复屏幕阅读器语义；整页刷新会中断导入任务，本次不作为产品修复，继续保留 CMP-10623 缺陷与失败回归。

当前证据：2026-10-27 19:42 CST，`HEAD=1ee935eb88e1c3bac05685feec4d032221a2fcb3`，比较基线同该提交，工作树含此前未提交的关联／导入 UI 改动。`cd compose/qa/web && FT_COMPOSE_PREVIEW_PORT=5336 npm run test:e2e -- tests/compose-cash-detail.e2e.ts tests/compose-cash-import.e2e.ts --reporter=line`：Chromium 37/37 通过（包括两成员详情、首次来源请求 503 后重试、文件 422 文案）。`cd compose/qa/web && npm run test:node`：3/3；`cd compose && ./gradlew :shared:wasmJsTest :shared:compileAndroidMain :shared:testAndroidHostTest --no-configuration-cache --console=plain`：BUILD SUCCESSFUL，74 个任务。`openspec validate --all --strict --json`：35/35；`git diff --check` 通过。真实 Chromium 1440×1000 通过 `http://127.0.0.1:5187/` 打开本机账本“出入境证件费”：来源证据 GET 200、2 条成员显示、8 秒无转圈，页面错误 0；忽略目录外的受控截图见 `~/.ft/reports/icbc-import-live-20261027-fix-check/persisted-formal-relation-after-fix.png`。未进行财务写入。用户已确认修复版详情。Cross-platform Impact Check：`commonMain` 的证据加载和失败／重试状态影响 Web、Android、iOS，共用同一表现合同，无平台专用分支；Web 已验证，Android 编译与主机单测通过，按用户决定不运行 iOS 或原生设备 QA。Compose 版本及已知无障碍树缺陷不变。

#### 4.9 关联详情验证

2026-10-27，验证对象 `HEAD=1ee935eb88e1c3bac05685feec4d032221a2fcb3`，与当前提交基线相同。Web Chromium 在 390×900 和 1440×1000 覆盖主页与导入详情：顺序为摘要 → 关联流水 → 账单原文；多成员／候选时关联区默认展开，原文默认折叠；单笔无候选隐藏关联区；候选均标明且导入只读。`insufficient_evidence` 先因红色通用错误文案断言失败，修复后主页及导入候选回归 3/3 通过。整组接受浏览器回归 1/1 通过；SQLite 关系计划契约 28 passed，28 个 PostgreSQL 专用用例因未配置专用库而跳过。截图位于忽略目录 `compose/qa/web/test-results/compose-cash-detail.e2e.ts-F-04-当前详情：候选嵌入关联区并可打开整组处理/current-detail-relation-candidate-1440.png`、`compose/qa/web/test-results/compose-cash-import.e2e.ts-21472-ly-in-the-import-detail-390/import-relation-detail-390.png`、`compose/qa/web/test-results/compose-cash-import.e2e.ts-18ce4-y-in-the-import-detail-1440/import-relation-detail-1440.png`。Android 编译与 Host 单测见本轮 QA 命令；iOS 按用户明确要求不验证。Hallmark runtime 不可用，按 `audit` 参考完成截图人工复核，未调用 Hallmark 工具；发现的重复错误提示已修复，复核未发现其他 critical／major/minor finding。


Cross-platform Impact Check：Web、Android、iOS 的共享校验、错误状态、DTO 和能力接口均受影响，无平台分支或新依赖。当前基线 `0930b73`。新增错误状态 Web 冒烟 1/1、共享 Wasm／Android／iOS 编译与单测通过，独立设计复核及 Hallmark audit 为 0 critical／0 major／0 minor；实际命令、计数与截图见 [lifecycle 4.28](../2026-10-28-cash-import-lifecycle-redesign/tasks.md#428-人工输入与原子提交收紧)。已登记禁用控件无障碍语义缺陷按用户要求保留，对应断言不记通过；整体演示确认仍未完成，不关闭 change。

#### 4.8 共享详情验证

2026-10-27 16:17 CST，验证对象 `HEAD=1ee935eb88e1c3bac05685feec4d032221a2fcb3`，与本次开始时的提交基线相同；工作树还包含本任务以外的既有改动，未将其纳入本次验证范围。Cross-platform Impact Check：共享 Compose 摘要、草稿提示和原文折叠影响 Web、Android、iOS；无平台分支或新依赖。iOS 按用户明确决定不验证。

| 检查 | 结果与证据 |
|---|---|
| Web 浏览器 | Chromium，`http://127.0.0.1:5242`—`5248`，浅色主题及 API 夹具。主页摘要、草稿／原文、长文本／只读来源、错误重试、导入详情与分页返回 14 个不同用例最终全通过，覆盖 390×1000、768×1000、1440×1000；导入来源断言修正稳定定位后在三个视口分别重跑通过。主页编辑、导入来源展开可操作；有诊断断言的流程未发现控制台或失败请求错误。忽略目录截图：`compose/qa/web/test-results/visual-review/home-detail-390.png`、`home-detail-1440.png`、`import-detail-1440.png`，不提交。 |
| Compose 共享与 Android | `./compose/gradlew -p compose :shared:wasmJsTest --no-configuration-cache --rerun-tasks`：47 个任务执行，成功；`./compose/gradlew -p compose :shared:compileAndroidMain :shared:testAndroidHostTest --no-configuration-cache --rerun-tasks`：35 个任务执行，成功。既有 Kotlin 警告，无新增编译错误。 |
| 规格及工作树 | `openspec validate --all --strict`：35/35；7 份本次相关 Markdown 的本地目标链接检查及 `git diff --check` 通过。无数据库／服务端合同变化。 |
| 设计复核与遗留 | 按已读 Hallmark audit 参考完成人工替代审查，未调用不可用的独立 Hallmark 工具；截图对照主页和导入预览层级、间距、颜色、文案、响应式、按钮语义及长文本，没有 critical／major finding。用户尚未确认演示；不归档，不把该等待记作实现或验证失败。 |

## 5. 审查

- [x] 5.1 在独立上下文复核产品范围、工程、API 可发现性、恢复与隐私边界，记录 finding、处置和回写；阻断项修复后重审。
- [x] 5.2 对实际 Compose 页面执行 Hallmark audit、中文术语与可见文案必要性自检；独立设计复核清零 critical／major，不能用规划自查代替。

## 6. 测试与 QA

- [x] 6.1 在 `compose/` 执行 Wasm 和 Android 主机单测、Web 开发／QA 构建、Android 包及 iOS 共享编译，记录实际 HEAD、命令与结果。本轮 iOS 平台单测及设备／应用包 QA 按用户明确决定不执行。
- [x] 6.2 在 `compose/qa/web/` 执行 `npm run test:node`（3 passed）和现金详情／导入文案 Playwright（相关 10 项通过），核对关联成员读取失败提示、账单原文和导入结果文案；完整流程与页面 QA 证据仍按本 change 既有记录，不扩为未执行状态、主题或视口的穷举。
- [x] 6.3 按当前默认策略核对 Android、iOS 相称编译和单元测试证据；设备／模拟器交互、截图和端到端 QA 不在本次验收范围，不以未执行阻断，也不记为通过。
- [x] 6.4 使用专用 SQLite 及显式 `_test` PostgreSQL 服务验证受影响真实请求与业务结果，不自动探测数据库、不写真实账本；演示只用去标识化合成场景。不得改变万行后端独立性能验收范围。
- [ ] 6.5 在 Compose 演示模式以 Web 代表视口交付受影响流程，取得用户演示确认后才勾选 UI 完成；执行 OpenSpec strict、链接和 `git diff --check`。

## 7. 发布

- [x] 7.1 验证与审查后修订现金业务说明、词表和活动 change；详情文案统一使用“收支记录／关联流水／账单原文”，更新账户操作提示，未触及归档 OpenSpec。
- [ ] 7.2 记录交付范围、依赖版本、回退和未解决风险；相称检查后按已授权仓库／分支小步提交推送，PR／合并／部署另需授权。不得通过回退恢复已关闭接口。

## 8. 反思

- [ ] 8.1 记录可复用防复发测试与必要规则，保持文档只表述当前结论；完成后再按实际依赖同步 delta，未实现内容不提前归档。

### 当前审查与验证

2026-10-28 23:43 CST 状态更新：用户确认变更已完成并要求归档，已按确认归档。本轮只改文档与规格，没有运行代码测试或构建；静态检查、当前 HEAD／比较基线及 3 项未勾选验证的记录见 [现金导入生命周期归档证据](../2026-10-28-cash-import-lifecycle-redesign/tasks.md#执行证据)。

2026-10-25，验证对象为 `average-snail` 的当前合并树：提交前 `HEAD=bb66b38`，合入 `origin/main=adcb081`，共同基线 `5b41517`。保留用户认可的视觉基线 `8aa0b40` 和本分支恢复后的信息卡、业务分组及交互蓝，接入主分支索引滚动、刷新、管理导航和 M3 容器测量；不恢复旧详情／任意关系写入，不升级依赖。完整 change 尚未取得用户演示确认，不归档。

Cross-platform Impact Check：共享列表、详情／编辑宿主、创建表单、索引读取和导航影响 Web、Android、iOS。Web 用 Chromium 在 `http://127.0.0.1:5308` 验证当前 HTTP 合同夹具和受影响主分支流程，代表视口 1440×900，直接相关响应式补验 390×844、768×1024、1440×1000；现金文案反馈版离线演示使用 `http://127.0.0.1:5359/w/demo-workspace/cash-import` 的合成内存数据。Android 编译、主机单测及设备测试源码编译；iOS 仅共享编译，本轮平台单测、应用包及设备 QA 按用户决定不执行，不记通过。

| 验证 | 当前结果与证据 |
|---|---|
| 测试先行 | 同版本删除非根来源后的月份缓存双库回归先失败 2/2；opaque 来源组成项映射先出现 JSON 解码失败；账户任务主操作顺序先出现浏览器几何断言失败。永久回归位于 `test_cash_projection_lifecycle_reads.py`、`FinanceModelMapperTest.kt` 及 `compose-cash-import.e2e.ts`。未放宽读取／金额／请求断言 |
| 共享回归及构建 | Wasm 401／Android 主机 396 项，零失败、错误或 skip；最终账户表单修复后 Web、Android Debug 包、Android 设备测试源码和 iOS Simulator Arm64 共享编译通过，1m13。命令见下文，日志 `/tmp/ft-main-merge-account-matrix.log`；演示构建采用相同任务并设置 `FT_COMPOSE_DEMO_MODE=true FT_API_ORIGIN=''` |
| 当前 HTTP 合同 Web 冒烟 | entry／import／manual／relations 21/21，2.0m，日志 `/tmp/ft-main-merge-contract-final.log`。覆盖统一核对／摘要结果、实际影响确认／取消保输入、普通及转账模式、防重／退出、当前关系成员及安全重读。最终账户局部布局另以三窗口回归 3/3，12.4s，日志 `/tmp/ft-main-merge-account-api.log`；源码／产物指纹一致，不重跑未受影响流程。使用当前 HTTP 合同夹具，不称为真实后端端到端验收 |
| 主分支受影响 Web 冒烟 | 当前详情、管理导航／旧分类链接、FAB／吸顶／行内缩、索引跳转、重载／错误及下拉共 12/12，1.6m；下拉触控额外连续三轮 3/3，1.0m。日志 `/tmp/ft-main-merge-extra-final-reviewed.log`、`/tmp/ft-main-merge-refresh-final.log`。原鼠标后切 CDP 触控序列不稳定，当前用全触控完成选择／取消／下拉并保留原请求、非顶部与多选断言；该测试序列局限不认定为上游缺陷，不增加产品兼容路径 |
| 后端回归 | SQLite／显式专用 PostgreSQL 受影响全集 294/294，392.00s，日志 `/tmp/ft-main-merge-backend-reviewed.log`；最后冷页单次聚合调整再执行全部查询及生命周期读取 96/96，118.96s，日志 `/tmp/ft-main-merge-cold-read-reviewed.log`，包含新服务中页、空筛选、越界空页及同版本软删除。全仓 4770 项的无关扩展未完成，不宣称全仓回归通过 |
| 读取性能 | 固定万行同服务热读预算及索引查询计划 4/4，121.54s；SQLite 浅／中／深索引页 p95 32.0／61.6／84.0ms，PostgreSQL 61.2／69.9／80.6ms；原 100ms 门槛未放宽。日志 `/tmp/ft-main-merge-read-performance-direct.log`。冷请求聚合以双库功能合同验证，不宣称 HTTP 冷请求延迟已通过同一性能预算；未扩入独立导入写性能待办 |
| 当前演示 | 最终源码离线构建及产物指纹一致；原流程与新增账户三窗口共 10/10，45.8s，日志 `/tmp/ft-main-merge-demo-reviewed.log`。真实走通导入准备→核对→结果、详情编辑保存、普通／转账创建及账户表单；截图 `/tmp/ft-cash-ui-restored/*.png`，确认图位于 `/tmp/ft-cash-current-contract-results/`。只使用合成内存数据，主流程零 API 请求和 JS 异常，不写真实账本，不替代后端合同；用户尚未确认 |
| 工具与文档 | 现金用语调整后 Node 3/3；OpenSpec strict 35/35，非归档 Markdown 链接／锚点、三份中文 Compose 资源 XML 和词表表格均通过；可见文案自检不含模型／实现名。当前术语版离线演示 5359 返回 HTTP 200，等待用户确认 |
| 独立审查 | 当前协议／工程、冷请求分支及 delta 回查无 blocking／major。来源 ID 自动合并退化、旧平铺及任意关系操作、账户操作区遗漏均已修复并重审关闭。当前实际普通／转账、详情／编辑、导入、确认及账户三窗口截图的 Hallmark audit／独立设计复核为 0 critical／0 major／0 minor。保留字段分组、配色与唯一主要操作；不将规划自查算实际审查 |
| 限制与回退 | Compose `1.12.1`／Material3 `1.12.0-alpha03` 不升级。已登记弹层后无障碍树停更、禁用语义及输入镜像缺陷仍保留，画布交互通过不代表屏幕阅读器通过。回退只撤销本次集成，不恢复退役写入口；未合入 main、未部署、未归档 |

当前范围 finding 与处置：

| 严重度／位置 | 处置与回写 | 状态 |
|---|---|---|
| major，索引读取与月份元数据 | 保留 `sort_at`、正式／草稿和统计资格；完整连续月份段与精确删除来源签名进入有界聚合缓存。携版本冷页一次 SQL 聚合数量，空页不伪装不可用；双库及性能证据见上表 | 已修复并独立重审关闭 |
| major，来源 DTO 映射 | 组成项／来源流水标识恢复字符串直传，保留 main 组成项与双端字段；opaque 标识回归先失败再通过 | 已修复并独立重审关闭 |
| major，独立账户侧板操作区 | 主操作在 Regular／Wide 左侧，Compact 保留取消／保存，移除额外水平内缩；浏览器先失败，三窗口及实际截图重审通过 | 已修复并独立重审关闭 |
| P2，UI delta 取代关系 | 用完整 `MODIFIED` 保留 main 有效测量，移除旧平铺详情、成员编辑、独立关系创建及冲突的忙碌／退出断言 | 独立回查无阻断同步项 |
| 测试局限，混合输入序列 | 鼠标后切 CDP 触控未确认可稳定执行；全触控及独立鼠标流程分别验证，不把工具序列问题当上游缺陷或增加产品兼容路径 | 保留准确限制 |

实际编译及单测命令：

```sh
FT_COMPOSE_DEMO_MODE=false FT_API_ORIGIN=http://127.0.0.1:58792 ./compose/gradlew -p compose :shared:wasmJsTest :shared:testAndroidHostTest :androidApp:assembleDebug :shared:compileKotlinIosSimulatorArm64 :shared:compileAndroidDeviceTest :webApp:wasmJsBrowserDevelopmentWebpack --no-configuration-cache
```

后端受影响全集使用人工准备的 `_test` 数据库，测试进程顺序运行，不自动探测／启动数据库：

```sh
FT_TEST_POSTGRES_URL='postgresql+psycopg://ft_entry_test@127.0.0.1:55439/ft_cash_entry_test' FT_REQUIRE_POSTGRES=1 PYTHONPATH=tests:tests/contract uv run pytest tests/test_application_web_queries.py tests/test_cash_ledger_management.py tests/contract/test_web_api.py tests/test_alembic_migration.py tests/test_runtime_docs.py tests/test_ci_workflow_contracts.py tests/contract/test_cash_entry_editing.py tests/contract/test_cash_current_projection_api.py tests/contract/test_cash_projection_lifecycle_reads.py tests/contract/test_cash_projection_graph_reads.py tests/contract/test_cash_projection_parity.py -q --disable-warnings --tb=short
```

Web 实际命令使用本机 QA 配置及合成凭据，缓存和截图均不进入 Git：

```sh
npx --prefix compose/qa/web playwright test --config /tmp/ft-cash-current-contract.config.cjs
npx --prefix compose/qa/web playwright test --config /tmp/ft-cash-current-contract.config.cjs --grep 'import account task'
npx --prefix compose/qa/web playwright test --config /tmp/ft-main-merge-extra.config.cjs --grep 'F-04 当前详情|管理共用目录|旧分类链接|F-03 (滚动条拖动后定位到未加载流水|首次和完整重载|下拉刷新|FAB 覆盖列表)'
npx --prefix compose/qa/web playwright test --config /tmp/ft-main-merge-extra.config.cjs --grep '下拉刷新只响应' --repeat-each 3
npx --prefix compose/qa/web playwright test --config /tmp/ft-cash-ui-restored.config.cjs
```

最后冷请求分支重验的实际后端命令为同一数据库配置下的 `uv run pytest tests/test_application_web_queries.py tests/contract/test_cash_projection_lifecycle_reads.py -q --disable-warnings --tb=short`。性能专项为同一配置下的 `uv run pytest tests/test_cash_projection_performance.py::test_fixed_10k_cash_read_paths_meet_100ms_budget tests/test_cash_projection_performance.py::test_fixed_10k_cash_page_lookup_plans_use_pagination_indexes -q -s --disable-warnings --tb=short`。

文档收尾范围：当前列表协议／UI delta、三个 change、功能地图、UI 规范、AGENTS 和 apply 技能已对齐；主分支归档保留历史事实，不把历史截图当当前完成证据。现金账本说明补索引、未知月份和统计资格；README 保持现金流程主入口及主分支投资架构入口，修复失效链接。不新增领域概念、依赖或迁移，无需另改金额、记账与权限合同。完整 UI 的 6.5／7.2／8.1 保持未完成，用户确认后再收尾。
