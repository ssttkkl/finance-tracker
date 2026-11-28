## 1. 思考

- [x] 确认首批 5 个读工具与 1 个写工具；涉及共享导航、三端本地历史、推理代理及财务授权，按 A 类设计。

## 2. 计划

- [x] 用户已确认完整设计、聊天主区原型与验收范围。Koog 1.3.0 的独立进程恢复实测通过后进入正式接入。

## 3. 任务拆分

- [x] 覆盖重启、取消并追加输入、重复确认及写入结果未知。Koog 独立进程探针已验证基础恢复，取消续跑的响应丢失分支重复发起推理，正式接入须验证重载后显示「未送达」，仅点「重试」才重新推理。
- 验证于 2026-11-27（UTC+8），基线 `7428120a`。使用 JVM 21、Koog 1.3.0 发布物、确定性模型替身及临时 SQLite。执行 `compose/gradlew -p /tmp/koog-check.Fc77/probe --offline installDist` 和 `bash /tmp/koog-check.Fc77/probe/run-scenarios.sh`。待确认时退出，取消恢复沿用原调用 ID 和原输入，初始推理后仅请求一次，零写入。确认 36 元后模拟提交成功但进程退出，恢复按原 ID 取收据，单次写入、单条记录、单次续跑。证据位于 `/tmp/koog-check.Fc77/probe/scenarios.log` 和 `run.bBb1cU/`。
- 将同一探针依次以 `prepare`、`cancel-and-crash`、`cancel-recover` 启动三次，退出码依次为 71、73、0。第二次在收到模型请求后、保存响应前退出，第三次再次发送相同取消结果和用户输入。计数及进程记录位于 `/tmp/koog-check.Fc77/probe/lost-continuation.OYjHe0/`。这项验证使用临时账本，正式共享写入、确认与发送并发以及三端接入仍待验证。

## 4. 构建

- [x] 接入鉴权代理、设备历史、共享工具与恢复，并连接 Compose 对话页、A2UI 和现有详情。

- 推理代理已用原工作区鉴权接入，配置见 [Compose README](../../../compose/README.md#账本助手)。工具目录及系统指令由后端生成，接口允许仅查看成员。新增收支收据在 SQLite 和 PostgreSQL 上返回实际记录 ID，并可按原键读回。
- Web、Android 和 iOS 共用对话页、侧栏入口、Koog 检查点、A2UI 卡片动作和加密设备历史。Android 的 Koog Kotlin 路径使用 kotlinx.serialization，排除不用的 Jackson 适配器以保留 API 24 支持。应用级打包与受影响流程验收见第 6 阶段。

## 5. 审查

- [x] 独立工程与范围审查、契约及 UI 复用审查、视觉审查。

- 独立契约复核覆盖推理代理、设备 HTTP 及会话范围、检查点、授权及收据恢复。发现原实现重复发送历史工具结果、不同会话可取新凭据、确认遗漏审查字段、工作区重启未核实原收据。修复对话上下文裁剪、捕获同一会话、通过共享控件显示可写字段、只读收据核实和本机卡片身份。仅用户动作可继续推理。供应商数量、读体时限与来源记录暴露边界已收紧，剩余发现需复核。
- 独立 UI 复核覆盖导航、确认卡、详情和原型。确认卡原先显示内部身份，详情缺版本围栏，汇总缺少数据卡。已复用现有记账字段、选项、金额及详情面板，补选中状态与来源版本围栏。聊天主区按新版 HTML #chat 检查正文、工具记录、卡片和输入。

- 导航复核基线为 `origin/main@5e847a7e` 的实际 AppShell。独立复核 390×844 候选抽屉确认只有主分支四个业务项，分隔线后为不可点击的「账本助手」标题、新对话及历史，仅当前历史项显示选中条。
- 聊天主区采用 FinanceTheme、Material 3 TextField／按钮、现有账户选择器和 A2UI 卡片。独立复核发现工具后的多段正文应先于业务卡，折叠状态需按对话隔离。修复后复核通过。保留投递状态播报，设备读屏未运行。
- 调用信息来自 Koog 实际调用，逐次记录只读工具和新增收支。按业务字段白名单生成参数及结果摘要。新增收支的等待、确认、拒绝及取消状态更新原位置。独立契约复核发现整轮结束前退出会漏记录，现已在只读调用前后保存状态，并通过重审。工具明细省略内部提示、凭据、游标和原始响应。
- Hallmark 最终自检覆盖宽窄屏用户气泡、正文、业务卡、输入区、等待／失败标记及历史菜单。对话先呈现用户动作和结果，工具与发送状态使用次级层级。导航与工具卡沿主项目 Material 3 主题。实际浏览器截图及独立键盘复核见第 6 阶段。
- Markdown 首次实现移除手写段落排版，复用上游 0.45.0 M3、GFM、SVG 与代码高亮扩展。独立 UI 复核确认默认组件和局部表格滚动全部保留。复核指出主题切换余留的语法配色问题，已按实际 FinanceTheme 重建代码高亮组件。契约复核确认专用图片加载器隔离账本认证、禁止重定向及磁盘缓存，旧回复按已存原文渲染。
- 流式通路独立契约复核确认后端只透传可见正文，并在收到终止帧、校验工具与参数后交给 Koog。客户端预览独立持久化，收据继续用原键核实。当前供应商流使用单行 JSON SSE，空工具数组兼容及长回复持久化负载待专项验证。
- 工具详情采用 M3 模态底部面板，原摘要位置保持稳定。Web 的 M3 Popup 初测出现关闭按钮的命中坐标错位，复用应用根级弹层后通过独立视觉及交互复核。原生使用 ModalBottomSheet。实际只读与待确认工具沿用同一可滚动内容，流水详情入口仍走既有面板。
- 浏览器验证曾发现确认动作使用初始编辑值。A2UI 回调改为读取最新值与提交资格，核对编辑金额、非法金额不发送写入、确认只写一次。
- 契约复核修复已落地。收据落地后刷新期间仍由同一命令承接后续输入，恢复校验使用共享收据规则。汇总明细只随客户端展示保存，推理上下文仅携带筛选和汇总结果。

- 侧栏图标是 B 类局部呈现调整。独立审查比对 Google Material Symbols Outlined 原路径，确认开关复用既有图标容器、主题前景色及无障碍名称。Material 3 Navigation rail 的 Overview 与 Specs 用于核对展开与收起的图标位置和状态。只涉及宽屏导航开关。

## 6. 测试与 QA

- [ ] Web 实际操作、Android 和 iOS 编译及单测、适用后端回归；记录当前证据。

- 比较基线 `ca4b6d61`，2026-11-27（UTC+8）。`:backend:test --tests '*CashCreationCommandHttpTest' --tests '*LedgerAssistantInferenceTest'` 使用独立 SQLite 与显式 `ledger_assistant_20261127_test` PostgreSQL，5 项通过，零跳过，核原键重放、流水 ID、真实记录、仅查看成员及工作区拒绝。
- Chrome 1440×900 在本机 QA 构建经真实登录、合成模型回复、正式记账接口，验证未送达、重载零推理、明确重试、把 38 改 36 后提交一次、SQLite 仅一条支出、成功卡打开现有详情。浏览器无未捕获异常，预期 503 及 Wasm 弃用提示见日志。证据位于 `/tmp/ledger-assistant-qa.7TIsiI/`。随后补跑 Chrome 同视口的并发与结果未知分支，确认排队后续输入会随原收据继续，结果丢失后重载只读收据，显式继续后仍只有一次写入。
- Chrome 390×844 在真实登录、抽屉导航和本机 HTTPS 模型替身下完成按月汇总，推理代理返回两次 200，无未捕获异常。生产供应商留存待选型后核实。
- Android API 24 配置下 `:androidApp:assembleDebug` 成功。Android 主机和 iOS 模拟器单测各两项通过，覆盖 Koog 原调用恢复和共享只读工具续跑。iOS 真机目标编译通过。Native 设备 UI 验证未运行。
- 跨端影响包含 Web 路由和宽／窄屏导航，Android／iOS 的同一共享页面、对话本机缓存和确认操作，以及共享代理、账本工具与收据。Web 实测覆盖交互，Native 以编译和单测为证。
- Chrome zh-CN 对照基线以 `5e847a7e` 独立工作树构建。宽屏 1440×900 对照原导航收合、悬浮展开、键盘焦点和账号面板。窄屏 390×844 对照原抽屉和账号／工作区面板，实际切换工作区。候选色板的亮暗模式均核对新会话、历史与结果、确认、取消、金额错误和空输入的禁用发送。
- 新增仓库 Playwright 用例 `assistant conversations sit below the existing business navigation`，在实际开发构建与生产 Wasm 构建均通过。覆盖收起态、展开或打开抽屉、四个既有业务项、非交互标题、无历史时的新对话入口及路由。
- 新版聊天主区在 Chrome zh-CN 1440×900 与 390×844 执行实际登录、键盘回车插入换行、精确比较推理请求原文、正文／卡片左右边沿及顺序、六行高度上限和后续滚动。查询汇总和新增收支均默认收起。展开、收起和取消的写入次数为零，展开与收起未增加推理请求。证据见 multiline-final.log。故障注入暂停查询工具完成后的下一次推理并重载，原查询记录和结果卡仍可打开，原消息显示「未送达」，推理计数仍为两次，零写入。证据见 multiline-trace-reload.log。
- M3 排版调整在实际 Chrome 1440×900 与 390×844 验证正文、卡片、输入与工具明细的边沿一致。查询及新增收支明细按调用名、参数、结果展开，交互不增加推理或账本写入。长回复验证空行、列表续行及代码中的字面短横线。Web、Android、iOS 真机目标编译及 Android 排版单测通过。前后图及长内容位于 http://127.0.0.1:18768/ ，原图为 /tmp/ledger-assistant-qa.7TIsiI/typography-gallery/ 。证据见 typography-measured.log、typography-long-measured.log。
- Chrome 1440×900、390×844 验证查询汇总和待确认新增收支的底部面板。桌面居中 640 dp，手机贴底全宽。关闭按钮、Escape、遮罩均还原对话坐标，关闭后 Enter 可从原按钮重开。35 行长参数可滚到结果，标题与关闭位置固定。开关零额外推理、零写入。证据见 tool-sheet-current-widths.log、tool-sheet-current-long.log，截图 http://127.0.0.1:18769/ 。持久 Playwright 用例 assistant tool sheet dismisses without moving the conversation or issuing commands 通过。原生系统返回采用 M3 原有动作，设备交互未运行。
- 本机演示为 http://127.0.0.1:18760/w/dd97ea15-a597-4750-becd-57d2776fad67/assistant ，聊天主区截图为 http://127.0.0.1:18767/ ，原图在 /tmp/ledger-assistant-qa.7TIsiI/multiline/ 。测试使用合成账本与本机 HTTPS 模型替身。导航基线为 http://127.0.0.1:18764/ 。
- 最新聊天实现通过 Web 开发构建、Android 编译与 3 项助手单测、iOS 真机目标编译及 3 项模拟器助手单测。Android 主机全量执行 1146 项，其中 17 项现有集成测试因缺 `FT_KOTLIN_CLIENT_CONTRACT_DIR` 等真实响应夹具失败，10 项跳过。补跑需要 `FT_KOTLIN_CLIENT_CONTRACT_DIR`、`FT_KOTLIN_INVESTMENT_READ_CONTRACT_DIR`、`FT_KOTLIN_PORTFOLIO_CONTRACT_DIR` 和 `FT_KOTLIN_PORTFOLIO_HOST_FILE`；助手定向单测通过。
- 后端全量测试执行 1536 项，OpenAPI 计数随新路由更新后，受影响协议与收据用例通过。原有 PostgreSQL 分类性能测试在全量中 RSS 超预算，独立运行当前分支和变更前基线均通过。全量记录仍保留这一失败。
- A2UI 0.3.0 在独立工程及正式 Wasm Web 构建使用 Maven Central 原版 ZIP。标准资源解析成功，未替换发布物。

- 当前对照重新截图后，再跑最新 Compose Web 构建的故障注入。确认中发送的「继续核对」先入队，原写入响应释放后带原收据续跑。已落账后丢弃响应的场景，重载读取原收据，未新增推理或写入，显式接续后仍只有一笔 36 元支出。排队证据见 group-queue.log，最新版响应丢失恢复证据见 durable-trace-recover.log。测试替身的预期 503 和故障注入传输错误可见，无未捕获浏览器异常。

- 2026-11-28（UTC+8），当前工作树对比 `13031a46`。Chrome zh-CN 1440×900 与 390×844，打开已有 DeepSeek 历史时推理请求为零。浏览器实际打开 Markdown 链接、加载公开图并验证窄屏长表格局部横滚。证据见 `/tmp/ledger-md-acceptance/history-after.log`、`history-mobile.log`、`gfm-interaction-final.log`。
- 同两视口经本机 SSE 故障夹具，测试用户气泡及进度先于正文、连接结束前出现正文、完成后单个气泡、首段失败、断流后重载零推理、手动重试原位替换、工具分片完成才展示确认卡且零写入。未送达标签紧贴气泡右下，重试期间消失。证据见 `/tmp/ledger-md-acceptance/stream-recheck.log` 与 `delivery-final.log`。
- 真实 DeepSeek 经 18804 相同入口及流式中转，实际 Chrome 1440×900 一次推理响应为 `text/event-stream`，可见正文比 HTTP 响应结束早 999 毫秒，无重复气泡。证据见 `/tmp/ledger-md-acceptance/live-stream-recheck.log`。复核截图入口为 http://127.0.0.1:18805/ 。专项测试 `:backend:test --tests '*LedgerAssistantInferenceTest' --tests '*OpenApiContractTest'` 的构建日志见 `/tmp/ledger-stream-final-build.log`。Android APK 与 iOS 真机和模拟器目标已编译，Android 主机和 iOS 模拟器各 4 项助手定向单测零失败，见 `/tmp/ledger-assistant-integrated-final.log` 和 `/tmp/ledger-assistant-candidate-verified.log`。菜单根级适配后的跨端编译见下条。
- 侧栏独立复核发现 Web M3 Popup 关闭后未恢复页面语义节点，键盘焦点会跳到背景。Web 菜单调整到 AppShell 根部，与导航共用目标状态，复用 M3 菜单项；原生使用 M3 DropdownMenu。独立复核确认焦点切换和 Escape 恢复通过。
- Chrome 1440×900 实测甲对话打开时右键乙，置顶和重命名不切换，重载及追加消息保留手动名称，删除非当前不动甲，页头删除当前返回新对话。Shift+F10、Tab、Enter 和 Escape 可从原历史行完成。390×844 以触屏长按进入同一菜单，实际点按历史项，验证等价的置顶、改名、重载、追加消息、删除非当前和页头删除当前。冻结已提交响应期间，页头删除不可执行，模拟丢失响应后重载核实原收据并显式续跑，写入请求总数为 1。证据见 `/tmp/ledger-md-acceptance/history-menu-hoisted.log`、`history-keyboard-hoisted.log`、`history-menu-mobile-final.log`、`history-protected-final.log`。
- 菜单最终适配通过 `:webApp:wasmJsBrowserDevelopmentWebpack`、`:shared:compileAndroidMain`、`:shared:compileKotlinIosArm64`、`:shared:compileKotlinIosSimulatorArm64`，见 `/tmp/ledger-assistant-history-hoisted.log`、`/tmp/ledger-assistant-history-hoisted-native.log`。
- 最终 `:webApp:wasmJsBrowserDistribution` 生成正式 Wasm 资源，Chrome 在 1440×900 和 390×844 从发布物复跑流式、断流、重试、待确认 A2UI 卡及 Markdown 图片、链接、局部表格滚动。两视口各 6 次故障夹具推理、零账本写入，A2UI 采用 0.3.0 正常发布资源解析，无资源 HTTP 错误和页面异常。证据见 `/tmp/ledger-assistant-history-hoisted-production.log`、`/tmp/ledger-md-acceptance/stream-production.log`（桌面）、`stream-production-mobile.log` 与 `gfm-production.log`。原生未做设备交互。

- 2026-11-28，侧栏开关基于 `94ef0219`。Chrome 1440×900 使用正式 Web 构建核对两图标资源均为 200、悬浮展开、点击收起、固定展开和 Tab 聚焦展开，开关名称分别为「展开导航」「收起导航」。`:webApp:wasmJsBrowserDistribution`、`:shared:compileAndroidMain`、`:shared:compileKotlinIosSimulatorArm64` 通过。Web 与原生宽屏共享资源和样式，窄屏抽屉合同不变。证据见 `/tmp/ledger-panel-symbols-browser-pass.log`、`/tmp/ledger-panel-symbols-build.log` 和 http://127.0.0.1:18805/panel-symbols/ 。

## 7. 发布

- [x] 更新功能地图及相关文档，记录用户决策反思。
- [ ] PR 前同步主规格并归档。

- 本次用户明确以既有 AppShell 为导航基线，新版 HTML 原型定义多行聊天及真实工具记录的显示，工程主题定义具体样式。决定适用于本助手，已记入本 change，无需改动通用 UI 规范。投递、断流和手动重试语义，以及本机历史菜单与写入期间的删除保护均适用于本助手，保留在本 change。回滚代码时可继续读取原消息字段，新增设备历史属性均有默认值；已记账流水及原键收据继续由现有账本接口持有。
