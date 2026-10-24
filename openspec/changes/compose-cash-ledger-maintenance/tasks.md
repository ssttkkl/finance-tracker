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

- [x] 4.6 按修改前视觉基线恢复内容层级、业务字段分组及主要操作位置，保持现有新协议和用户已确认流程。

## 5. 审查

- [x] 5.1 在独立上下文复核产品范围、工程、API 可发现性、恢复与隐私边界，记录 finding、处置和回写；阻断项修复后重审。
- [x] 5.2 对实际 Compose 页面执行 Hallmark audit、中文术语与可见文案必要性自检；独立设计复核清零 critical／major，不能用规划自查代替。

## 6. 测试与 QA

- [x] 6.1 在 `compose/` 执行 Wasm 和 Android 主机单测、Web 开发／QA 构建、Android 包及 iOS 共享编译，记录实际 HEAD、命令与结果。本轮 iOS 平台单测及设备／应用包 QA 按用户明确决定不执行。
- [x] 6.2 在 `compose/qa/web/` 执行 `npm run test:node` 和受影响 Playwright，在代表视口以真实 API 冒烟受影响主流程、关键交互和直接相关复现分支，记录浏览器、URL、步骤、必要截图及 console／network；不默认穷举状态、主题或三类窗口。
- [x] 6.3 按当前默认策略核对 Android、iOS 相称编译和单元测试证据；设备／模拟器交互、截图和端到端 QA 不在本次验收范围，不以未执行阻断，也不记为通过。
- [x] 6.4 使用专用 SQLite 及显式 `_test` PostgreSQL 服务验证受影响真实请求与业务结果，不自动探测数据库、不写真实账本；演示只用去标识化合成场景。不得改变万行后端独立性能验收范围。
- [ ] 6.5 在 Compose 演示模式以 Web 代表视口交付受影响流程，取得用户演示确认后才勾选 UI 完成；执行 OpenSpec strict、链接和 `git diff --check`。

## 7. 发布

- [x] 7.1 验证与审查后修订 README／业务说明／接口示例／功能地图和旧变更取代关系，原位清理过时表述，检查链接；无需修改的文档记录理由。
- [ ] 7.2 记录交付范围、依赖版本、回退和未解决风险；相称检查后按已授权仓库／分支小步提交推送，PR／合并／部署另需授权。不得通过回退恢复已关闭接口。

## 8. 反思

- [ ] 8.1 记录可复用防复发测试与必要规则，保持文档只表述当前结论；完成后再按实际依赖同步 delta，未实现内容不提前归档。

### 当前审查与验证

2026-10-25，当前验证 `HEAD=5b42f96` 加本次工作树；生产代码比较基线 `099dfaa`，用户认可的视觉基线 `8aa0b40`（详情最近布局提交 `d0b15a9`）。本小步恢复现金 UI 层级和表单布局，并按用户确认调整交互蓝；不改变 VM、请求构造、财务运算、权限、存储或依赖。完整 change 的演示确认未取得，不归档。

Cross-platform Impact Check：共享详情、编辑／创建表单、导入准备和浅深主题语义色影响 Web、Android、iOS。Web 用 Chromium 真实操作当前 Compose；主流程 1440×900，金额／币种及表单布局补验 390×844、768×900，改色补验深色 1440×900。Android 编译及主机单测；iOS 仅共享编译，本轮平台单测、设备与应用包 QA 按用户决定不执行，不记通过。

| 验证 | 当前结果与证据 |
|---|---|
| 测试先行 | 金额／币种拆行、非空账号属性缺失、重复保存栏、确认摘要重复三次及旧配色对比度分别先失败再修复。日志 `/tmp/ft-cash-ui-{restore-red,attrs-red,confirm-red,confirm-duplicate-red}.log`、`/tmp/ft-cash-interaction-blue-red.log`；永久回归位于 `compose-cash-manual.e2e.ts` 和 `FinanceTokensTest.kt` |
| 共享回归及构建 | `FT_COMPOSE_DEMO_MODE=false FT_API_ORIGIN=http://127.0.0.1:58792 ./compose/gradlew -p compose :shared:wasmJsTest :shared:testAndroidHostTest :androidApp:assembleDebug :shared:compileKotlinIosSimulatorArm64 :webApp:wasmJsBrowserDevelopmentWebpack --no-configuration-cache` 成功，1m17；Wasm 388／Android 主机 383 项，零失败、错误及 skip。日志 `/tmp/ft-cash-ui-reviewed-matrix.log` |
| 当前 HTTP 合同 Web 冒烟 | `playwright test --config=/tmp/ft-cash-current-contract.config.cjs`，Chromium、1440×900、`http://127.0.0.1:5308`，源码／产物指纹一致。entry／import／manual／relations 共 21 用例，整轮 20 通过；entry 用例因测试输入尾字未同步即保存失败，改为失焦、等待 Compose 输入同步并检查完整值后，原精确请求断言独立 1/1 通过（6.2s），未修改产品输入或放宽断言。日志 `/tmp/ft-cash-ui-final-contract.log`、`/tmp/ft-cash-ui-final-entry.log`。这些用例使用当前 HTTP 合同夹具，不称作本轮真实后端验收；之前真实 API 业务证据见下文 |
| 直接相关状态 | 实际滚动完整表单，金额与币种同排，固定保存位置不变；非法金额显示文字、保存拒绝且零请求；非空账号属性保留；出现实际影响后仅显示确认正文和一组固定操作，取消后输入仍在，再保存一次提交；同笔商户摘要严格一次。来源／成员只读、普通及转账模式留输入、忙碌防重、放弃无写入、导入异常只错误／原因／返回等原合同回归保留。fixture 浏览器无新增 console error、failed request 或静态资源 HTTP 错误 |
| 当前演示 | `FT_COMPOSE_DEMO_MODE=true FT_API_ORIGIN='' ./compose/gradlew -p compose :webApp:wasmJsBrowserDevelopmentWebpack --no-configuration-cache` 构建成功 26s，输入／产物指纹一致；`playwright test --config=/tmp/ft-cash-ui-restored.config.cjs` 7/7 passed，34.7s，日志 `/tmp/ft-cash-ui-final-demo{,-build}.log`。URL `http://127.0.0.1:5309/w/demo-workspace/`；实际导入准备→统一核对→摘要→返回、详情编辑保存、普通／转账创建及三窗口金额布局走通，完整主流程零 API 请求和 JS 异常。截图 `/tmp/ft-cash-ui-restored/offline-*-1440.png`、`booking-{compact,regular,dark}.png`、`transfer-{compact,regular,dark}.png`，确认及校验截图位于 `/tmp/ft-cash-current-contract-results/`。演示为合成内存数据，不写真实账本，不实现完整后端关系引擎，不替代真实 API 合同验收 |
| 对比度／生成 | `python3 scripts/generate_ui_tokens.py` 真实生成并重复运行字节一致；浅色交互蓝 `#0A63B8` 对白色 6.02:1；深色原蓝 `#0485F7` 对深色按钮文字 5.50:1、页面 surface 4.81:1。自动回归覆盖按钮、页面文字与焦点边界，分别至少 4.5:1、3:1；不在页面硬编码 |
| 工具／链接 | Node `npm run test:node` 3/3，日志 `/tmp/ft-cash-ui-restore-node.log`。OpenSpec strict 37/37、doctor healthy，日志 `/tmp/ft-cash-ui-restored-{specs,doctor}.json`；当前所属 13 个 Markdown 文件链接／锚点及 `git diff --check` 通过；新增文案仅记账信息／对方信息／确认保存，范围搜索无新增实现术语或教学文字 |
| 独立审查 | 工程／范围最终 diff 无新增 blocking／major，保留输入 remember、原 modal key 和全部确认门禁；不同目标／字段／影响没有被去重丢失。最终实际 10 张截图独立 Hallmark audit／设计复核 0 critical／0 major／0 minor；包括确认、详情、编辑、准备、普通／转账、compact／regular／dark。下表全部发现修复后重审关闭，不沿用旧截图结论 |
| 限制／回退 | Compose `1.12.1`／Material3 `1.12.0-alpha03` 未升级；已登记弹层关闭后语义树停更及禁用状态缺陷保留，画布操作通过不代表屏幕阅读器通过，输入 supporting text 的 DOM 镜像缺失也不宣称无障碍通过。回退只撤回本小步展示及 token，不恢复旧协议。无后端／存储改动，不重跑未受影响双库、性能与后端完整矩阵 |

当前 Hallmark audit 与独立设计 finding：

| 严重度／位置 | 发现及处置 | 当前状态 |
|---|---|---|
| major，字段与详情层级 | 金额／币种拆散、详情缺少语义色及标签值信息卡；恢复修改前邻近关系、金额／对方层级及单主题卡，所有实际可编辑字段按记账／对方分组展开 | 已修复，最终实际截图独立重审关闭 |
| major，编辑及确认 | 长表单操作滚走、额外影响埋在表单后、双保存栏及同笔摘要三次；正文独立滚动，确认替换正文及页脚，每笔摘要一次紧接实际旧／新值 | 已修复，当前合同浏览器及最终实际截图独立重审关闭 |
| major，准备页 | 文件、账户和操作散落；文件在上、来源账户集中在下，相关操作紧邻，表单上限 720 px | 已修复，最终实际截图独立重审关闭 |
| major，主题 | 浅色旧蓝白字 3.68:1；按用户确认分别配置浅深配对及焦点，保留蓝色风格、字体与组件 | 自动回归及最终深浅截图独立重审关闭 |
| minor，表单顺序 | 分配控件落在对方组之后；归回记账组，仅具当前组成权限时显示 | 已修复 |

文档收尾：三个 design／相关 delta、功能地图及 Compose Material3／OpenSpec apply 技能已原位对齐。本轮实际读取官方正文及采用规则见 [官方 Material3 页面复核](../compose-cash-ledger-maintenance/design.md#官方-material-3-页面复核)，修正失效 Expressive 链接；未造新视觉系统。README 启动命令、业务／接口文档、配置、迁移与词表不因本步改变，无新领域概念，无需重复修改。防复发由实际浏览器几何／确认／请求断言和 token 对比度回归承载。

既有业务验收：当前列表／详情／草稿／来源基础 `0e523a1`，完整候选与失效处理 `2d800f3`。合成 SQLite API `58792` 的多候选完整成员选择 1/1（9.7s），来源更新与明确身份合并 2/2（14.8s），联合必要组／人工公共冲突／明确解除继承 1/1（20.2s）；日志 `/tmp/ft-cash-relations-layout-real-web.log`、`/tmp/ft-cash-final-issues-confirmed-browser.log`、`/tmp/ft-cash-joint-real-web.log`。单候选读取失败、失效重读无决定重放及残余公共冲突回归保留。以上为既有业务证据，本轮不冒充重跑。并发仅最小原键核实及提示用户重试，不增加自动合并／重放。用户确认仍由 6.5 承载。
