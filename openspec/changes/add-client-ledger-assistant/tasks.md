## 1. 思考

- [x] 确认首批 5 个读工具与 1 个写工具；涉及共享导航、三端本地历史、推理代理及财务授权，按 A 类设计。

## 2. 计划

- [x] 用户已确认完整设计、聊天主区原型与验收范围。Koog 1.3.0 的独立进程恢复实测通过后进入正式接入。

- [x] 用户确认附件固定 30 天及直接保存，按 B 类实施，无历史迁移或宽限期。

## 3. 任务拆分

- [x] 覆盖重启、取消并追加输入、重复确认及写入结果未知。Koog 独立进程探针已验证基础恢复，取消续跑的响应丢失分支重复发起推理，正式接入须验证重载后显示「未送达」，仅点「重试」才重新推理。
- 验证于 2026-11-27（UTC+8），基线 `7428120a`。使用 JVM 21、Koog 1.3.0 发布物、确定性模型替身及临时 SQLite。执行 `compose/gradlew -p /tmp/koog-check.Fc77/probe --offline installDist` 和 `bash /tmp/koog-check.Fc77/probe/run-scenarios.sh`。待确认时退出，取消恢复沿用原调用 ID 和原输入，初始推理后仅请求一次，零写入。确认 36 元后模拟提交成功但进程退出，恢复按原 ID 取收据，单次写入、单条记录、单次续跑。证据位于 `/tmp/koog-check.Fc77/probe/scenarios.log` 和 `run.bBb1cU/`。
- 将同一探针依次以 `prepare`、`cancel-and-crash`、`cancel-recover` 启动三次，退出码依次为 71、73、0。第二次在收到模型请求后、保存响应前退出，第三次再次发送相同取消结果和用户输入。计数及进程记录位于 `/tmp/koog-check.Fc77/probe/lost-continuation.OYjHe0/`。这项验证使用临时账本，正式共享写入、确认与发送并发以及三端接入仍待验证。

- [x] 修复已复现的对话定位与暗色新对话前景色。旧版首次发送停留在 `/assistant`，刷新丢失选择。390×844 的暗色空白页标题继承了深色背景上的黑色前景。证据见 `/tmp/ledger-route-audit/before.log` 和 `dark-before.png`。

- 核对提示的显示范围属于 C 类局部修复。验收覆盖空白与仅输入时无提示和占位，首条保存后至回复到达前显示，以及重开已有对话后仍显示。

- [x] QuickJS 核心运行门槛先行。固定候选依赖，三端以同一脚本执行真实异步账本读取，核对 UTF-8 CSV 字节、同步死循环、等待宿主、迟到响应和下次运行。
- [x] 服务端能力协商、OpenAPI 和完整工具调用参数检查。
- [x] 接入原生 QuickJS 适配及正式 Web 分发所用的隔离 Worker。
- [x] 构建本机账本只读 SDK 宿主和 CSV 解析／生成。
- [x] 客户端协商、脚本宿主、文件事务、附件、预览与导出。
- [x] 三币种确认退款对照、三端 QuickJS 堆采样、原生系统保存字节和 SQLite 空间不足验收。
- [x] 真实模型在宽窄屏导出并下载 CSV；核对内嵌技能示例的日期、时区和签名。
- [ ] 原生正式应用从对话到系统保存、应用进程内存和发行验收。

- [x] 附件跨截止、重放不续期、临时文件独立及整卡保存。
- [x] 共用历史菜单首图标，核对右键、长按、侧栏和页头入口、操作对象及禁用删除。
- [x] 按单条消息选择用户和助手文字，验证跨段落、鼠标和触屏复制及多行输入编辑。
- [x] 共用菜单打开目标对话的文件面板，核对非当前历史、保留输入、倒序同名附件、空态、过期及直接保存。
- [x] 有正文的已完成或中断回复显示一次复制和本地时间，核对原始 Markdown、卡片顺序、剪贴板反馈、中断恢复、跨午夜和跨年昨天。

## 4. 构建

- [x] 接入鉴权代理、设备历史、共享工具与恢复，并连接 Compose 对话页、A2UI 和现有详情。

- 推理代理已用原工作区鉴权接入，配置见 [Compose README](../../../compose/README.md#账本助手)。工具目录及系统指令由后端生成，接口允许仅查看成员。新增收支收据在 SQLite 和 PostgreSQL 上返回实际记录 ID，并可按原键读回。
- Web、Android 和 iOS 共用对话页、侧栏入口、Koog 检查点、A2UI 卡片动作和加密设备历史。Android 的 Koog Kotlin 路径使用 kotlinx.serialization，排除不用的 Jackson 适配器以保留 API 24 支持。应用级打包与受影响流程验收见第 6 阶段。

- 内置技能随客户端登记，Koog 在现有回合内取用技能、运行脚本和发送附件。预览沿 A2UI 业务卡与 M3 对话框，Native 用 FileKit 保存，Web 用 FileKit 下载。

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

- 工具行视觉调整按 B 类执行。独立审查核对 FinanceTheme 前景色、键盘焦点、原工具操作与结果顺序。相邻有可见结果卡时保留原边界，空目录和详情入口与原渲染共用解析，历史详情仍取原有首个对象。鼠标和触屏共用紧凑行高及点击区域，键盘保留同一行的可见焦点。视觉自检核对默认、悬停、焦点与工具详情。

- 对话地址和暗色空白页按 C 类修复。独立复核确认地址先于本机数据准备保持，跨工作区历史沿既有会话切换，切换期间用会话加载状态。首次保存回调验证原导航入口仍活跃，删除与缺失替换地址。暗色标题使用 `onBackground`，输入、提示和示例沿原组件及配对前景。

- 核对提示独立审查通过。已有消息布局复用原提示样式和输入组件，空白页完全移除提示节点和间距。Material 3 Applying type 的 Label 角色继续用于已有对话的次级提示。仅影响共享呈现，持久化与推理调用按原路径。

- 技能协商独立复核覆盖鉴权范围、旧目录、禁止注入和 OpenAPI。已统一协商版缓冲响应与 SSE 的非空调用 ID 检查，并保留旧数组的缓冲响应参数行为。

- 运行时适配独立复核修复两项阻断。共享派发顺序和调用数，拒绝仍有未等待宿主调用时发布结果；Web 在传输前限制字节与在途请求。依赖锁定实际进口模块版本，原生截止涵盖引擎启动与等待。

- 只读 SDK 独立复核确认普通工具查询与详情输出兼容。脚本桥以字符串传递精确 ID 和版本，分页使用同一预期版本。文件事务须复核原会话和账本版本，并保护直接写入 CSV 的表格内容。Worker 回调完成后释放 Promise 句柄，以便分页及时释放已消费数据。

- 技能集成触发契约和 UI 复用独立审查。契约复核确认附件提交时检查源文件未过期、原对话修订和只读账本版本，回滚同时撤销对话与文件。TTL 接入前台状态并定期清理。重试使用原消息、规范路径和规范化字节定位同一文件版本。长值 CSV 的共享列宽、按视口约束预览、可访问名称、失败播报和关闭后焦点已修复并复核。Hallmark 审计保留既有聊天列及清楚的文件操作层级，无新增常驻说明或重复卡片。未用原生读屏复核。

- 独立契约复核确认附件截止同事务写入消息和新正文表，临时续期不触及附件、旧预览正文保留、过期重放拒绝。独立 UI 复核发现禁用卡仍带保存角色，改为独立的静态 OutlinedCard 分支后通过。菜单首图标和删除的 Material 3 状态、单消息选区与其它动作的层级经只读复核，无主要阻断。Hallmark 范围为附件卡、菜单项及选区，核对视觉主次、触点和可读状态。

- 回复操作和对话文件的独立契约检查确认时间随会话事务保存，旧字段缺失仍可解码，查看文件绑定菜单所选对话 ID，面板只读已加载的消息。独立 UI 复用与 Hallmark 复核覆盖复制、红色中断提示及重试、文件菜单、底部面板和附件卡。中断重试使用独立 M3 按钮。复制反馈保持动作名称和图标，用统一的 Material 3 Snackbar 播报成功或失败，遮罩保留指针关闭并退出焦点与读屏动作树。共享 Compose 影响 Web、Android、iOS，Web 调用异步浏览器剪贴板及根弹层，原生复用现有系统剪贴板和 M3 底部面板。服务端协议与账本存储无变化。

- 复制 Snackbar 为 B 类共享呈现调整。独立 UI 复用及 Hallmark 复核修复了跨对话遗留提示和相同结果反复播报。当前提示及协程随对话切换释放，连续同文案合并，不同文案取消旧提示并显示最新结果。提示位于聊天页上方，沿用 M3 的无障碍播报，输入区保留原布局。影响 Web、Android、iOS 的共享界面，剪贴板底层、服务端及本机数据合同沿用既有实现。

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

- 2026-11-28，工具行基于 `b17595ea`，旧版间距参考 `94ef0219`。Chrome 1440×900、390×844 验证无可见卡的连续摘要行高及行距均为 32 px，原来行距 72 px。同一 390×844 视口的鼠标和触摸模式测得相同行距。宽窄屏只在悬停行改变图标与文字颜色，行位置不动。Enter／Space、Escape、点击关闭和触屏逐条点选两项均走对应工具详情，零额外推理及写入。空查询结果仍成组，有卡时仍为工具 A、卡 A、工具 B、正文。Web 与 Android／iOS 共用分组及焦点，保持同一点击尺寸。开发构建实测见 `/tmp/ledger-tool-spacing-route-dev.log`，截图见 http://127.0.0.1:18805/compact-tools/ 。

- 2026-11-28，以 `a9ce210a` 为地址与暗色修复基线。Chrome 开发构建在 1440×900、390×844 用两条真实本机历史验证首次保存、页头新建、切换历史、刷新、直达、浏览器前进后退、重新登录、未知 ID 和删除回退。每个视口只发送两次推理，恢复各路径零额外推理。跨工作区前进后退恢复有权限的工作区，借用另一工作区 ID 回退。证据见 `/tmp/ledger-route-audit/route-final-dev.log` 与 `route-cross-current.log`。同视口深浅色及修正前对照见 http://127.0.0.1:18805/assistant-route/ 。Android 导航定向测试 10 项通过，iOS 模拟器目标编译通过，没有运行原生设备交互。

- 最终 `:webApp:wasmJsBrowserDistribution`、Android 导航定向测试及 iOS 模拟器编译通过。Chrome 从正式产物复跑相同路由流程，延迟工作区切换期间显示「正在恢复会话」，未出现空白页或错误提示。也复跑统一 32 px 工具行、像素悬停差分、两行触屏逐条打开、可见卡顺序和空结果。18804 服务的 JS 与 Wasm SHA-256 均与正式产物相同，三个新增图标均返回 200。证据见 `/tmp/ledger-assistant-final-distribution.log`、`/tmp/ledger-route-audit/route-release.log`、`18804-assets.log`、`/tmp/ledger-tool-spacing-release.log`、`ledger-tool-empty-release.log`、`ledger-tool-card-release.log` 和 `ledger-tool-hover-pixels.log`。

- 核对提示以 `91adac86` 为基线。正式 Web 构建在 Chrome 1440×900、390×844 验证空白及仅输入时无提示节点，标题到输入框的间距均由 45 px 减至 22 px。拦住首轮模型回复时，已保存的消息与输入框上方的提示同时可见。重载已有对话仍保留提示，再次新建立即隐藏。每个视口只有一次主动发送产生推理。Android 和 iOS 模拟器目标编译通过，Web 与两原生端共享此呈现条件。18804 的 JS 和 Wasm 与正式产物一致。证据见 `/tmp/ledger-assistant-notice-before.log`、`ledger-assistant-notice-after.log`、`ledger-assistant-notice-build.log`、`ledger-assistant-notice-preview.log`，截图见 http://127.0.0.1:18805/assistant-notice/ 。

- 运行时探针以 `c260f656` 为范围基线，2026-11-28。独立工程在 `/Volumes/NO NAME/offload/hl/ledger-quickjs-probe`，同一 `report.js` 经实际安装的 `ft cash summary` 异步读取隔离 SQLite 账本，三端输出相同的 51 字节中文 CSV，十进制字符串 `-88` 原样保留。Native 使用 Maven `quickjs-kt 1.0.19`，引擎报告 2026-06-04。Android API 36 arm64 运行 instrumentation APK，iOS 18.3 arm64 模拟器运行 `:iosSimulatorArm64Test`，Web 使用 Chrome 独立 Worker 和 npm `quickjs-emscripten 0.32.0` 标准同步 Wasm。
- Android、iOS、Web 同步死循环分别在 202、203、218 ms 中断，计时窗口内主循环分别回调 18、20、21 次。Android／iOS 宿主等待约 302／309 ms 取消，Web 使用 300 ms 截止。Native 验证 host 协程取消及迟到完成被拒，Web 丢弃旧请求回调，三端中断后都继续得到 42 并导出真实 CSV。Native Promise 的兑现值经受限宿主回调接收，Web 显式驱动 QuickJS 待执行任务。详见探针目录的 `android-isolation-metrics.log`、`build/test-results/iosSimulatorArm64Test/`、`web-metrics.log`。
- Android 探针包约 8.2 MiB，iOS 模拟器测试可执行文件约 9.7 MiB，独立 Web 探针首次运行下载 Worker 85,802 字节和 Wasm 503,134 字节。QuickJS 导出结束时采样内存为 Android 76,801、iOS 66,457、Web 53,968 字节，运行上限为 32 MiB。采样值不作为峰值，正式产品体积和峰值仍待验证。

- 技能协商基线 `e129fe97`，2026-11-28（UTC+8）。新增目录协商用例先失败。后端定向测试 `:backend:test --tests '*LedgerAssistantInferenceTest' --tests '*OpenApiContractTest'` 共 24 项通过，无跳过。分片用例覆盖完整对象参数、未协商时拒绝及数组参数拒绝。OpenSpec 严格校验通过。客户端继续发送旧数组，当前交付不影响 Web、Android 或 iOS 的用户流程。

- 运行时适配基线 `c2afd9b4`。Android 主机桥接用例先编译失败，补实现后通过。Android API 36 和 iOS 18.3 模拟器以应用适配源码验证中文异步返回、宿主错误、同批次串行和未等待调用拒绝。正式 Web Wasm 构建包含 Worker 和引擎，Chrome 从该分发目录加载，验证异步返回、同步中断和再运行。入口分离的引擎与 Worker 合计 555 KB 左右；18804 的 Wasm MIME 为 `application/wasm`。见 `/tmp/ledger-skill-native-android-device.log`、探针 `TEST-iosSimulatorArm64Test.ProductScriptAdapterProbe.xml`、`/tmp/ledger-skill-adapter-final.log` 和 `/tmp/ledger-skill-worker-production-smoke.log`。运行时尚未在对话启用。

- 只读 SDK 与 CSV 基线 `9e41f833`。回归用例先失败，Android 主机定向桥接、只读和 CSV 4 项通过，覆盖大整数、精确金额、UTF-8、公式防护、引号、逗号、换行及读取后的版本变化。见 `/tmp/ledger-skill-shared-sdk-tests.log`。重建后用 Chrome 复跑 Worker 异步读取、中断和再次运行，见 `/tmp/ledger-skill-worker-release-handles-smoke.log`。

- 技能接线基线 `6e06a90d`，2026-11-28（UTC+8）。`:shared:testAndroidHostTest --tests '*LedgerAssistant*'` 共 10 项通过，无跳过。SQLite 试验覆盖临时文件和附件隔离、到期、同事务写入后失败回滚、过期源和账本版本拒绝。Android APK、iOS 模拟器和 iOS 真机目标编译、正式 Web Wasm 分发均通过。正式 Worker 增加 52,839 字节 JS 和 503,134 字节 Wasm，按需加载。Android arm64 包含 983,704 字节 QuickJS 原生文件；iOS arm64 发布物内原生库为 945,544 字节。这些是组件制品实测，不作为整包净增量或峰值内存。
- Web 真实 Chromium 在 1440×900 和 390×844 经真实工作区认证、正式推理代理及局部模型替身完成 CSV 和二次 Markdown，费用精度和 CSV 51 字节逐字节核对，重载零推理，完整流程零账本写入。专门注入附件提交后的断流，重载后使用换 ID 的工具调用手动重试，仍一条用户消息及一张附件。宽表长前列不改变后续列对齐，390×844 局部横滚可见末列，Escape 后 Enter 从原按钮重开。
- 证据见 `/tmp/ledger-skill-release-final.log`、`/tmp/ledger-skill-ios-arm64.log`、`/tmp/ledger-skill-release-http-qa.log`、`/tmp/ledger-skill-release-recovery-qa.log`。互动演示端口 18815，截图端口 18816。18804 继续提供正式构建并直连已恢复的真实模型，18815 演示使用本机模型替身。两端浏览器入口、Worker JS 和 Wasm 已按字节对照正式分发目录。三端共用运行时合同，Native 运行时用模拟器脚本验收。


- 专项验收基线 `1b2cf0f2`（2026-11-28，UTC+8）。Android 主机使用真正的本机账本投影读取器与脚本宿主比较月份完整摘要和逐笔投影金额。合成交易时刻为 UTC 3 月 31 日 16:15，上海时区进入 4 月。CNY 支出 88.25 经确认退款 8.25 后净支出 80，USD 为 12.34，JPY 为 900。三行投影与账本一致，退款未另算收入。SQLDelight SQLite 用 `max_page_count` 在附件插入位置触发 `SQLITE_FULL`（13），失败后对话保持原修订、附件及收据均不存在。放宽限制后原提交可重试，并读回对话、收据和相同附件。SQLite 自动回滚时外围驱动可能返回「no transaction is active」，测试在原插入处观察错误码。独立只读评审无阻断。Gradle 定向测试日志为 `/tmp/ledger-skill-specialist-qa.log`。
- Android API 36 模拟器、iOS 18.3 模拟器、Chrome Worker 用相同 QuickJS 负载测量引擎报告的 `memory_used_size`。32 MiB 堆上限，空上下文先建立空数组，再逐批分配及填充 4 个 1 MiB Uint8Array，保留到总计 20 MiB。每批完成后采样并另采释放后样本。基线／最高采样／释放后字节，Android 72,052／21,049,220／72,244，iOS 62,316／21,038,652／62,476，Web 52,731／21,027,963／52,923。这是隔离引擎的采样峰值，不覆盖采样间瞬时分配、进程 RSS、Wasm 线性内存预留及产品 UI／账本缓存。日志为 `/tmp/ledger-skill-heap-{android,ios,web}.log`。
- 原生系统保存使用独立夹具，Android API 36 从真实 SAF 保存面板选定 Downloads，iOS 18.3 从真实 UIDocumentPicker 选定本机文件夹，分别沿目标 URI／作用域 URL 写入和读回。实际目标各 51 字节，`cmp` 一致，SHA-256 为 `6caf31520b72570f85830288a649ba542ba45fb7681bc4521a31f9bc32067bd7`，文件见 `/tmp/ledger-native-save-qa/{android,ios}/exported.csv`。覆盖系统面板和文件写入链路，正式应用的 Compose 附件卡到 FileKit 调用仍需真机或模拟器端到端复核。未在设备上占满磁盘，第三方文件提供商及 App Store 外部审核未验证。


- 2026-11-29（UTC+8），从 `9bc703bc` 执行 `:webApp:wasmJsBrowserDistribution`，18804 实际提供的 JS 和主 Wasm 与本次构建逐字节一致。Chrome 390×844、1440×900 各用一个新对话，向真实 Mistral／DeepSeek `deepseek-v4-1-flash-1017` 发送同一句「请把 2026 年 11 月当前账本的支出按月和币种导出成 CSV 文件，发给我。即使没有流水也请发送只有列标题的文件。」两次均经历技能读取、脚本执行、附件发送，分别产生 6 次 HTTP 200 推理响应。两个视口均打开预览、下载 28 字节 UTF-8 CSV，并在刷新后恢复附件卡，刷新未新增推理。该月 QA 账本无明细，金额对照见三币种退款夹具。构建与两次实测见 `/tmp/ledger-skill-real-provider/9bc703bc-{dist,390,1440}.log`。
- 该措辞在基线 `1b2cf0f2` 有一次 140 秒内未产出附件，另一次工具报错后发送成功但浏览器验收超时，见同目录的 `18804-browser.log`、`18804-browser-trace.log`。本次两次通过覆盖这一复现条件，更广泛措辞的成功率尚未测量。技能示例的签名、日期和时区合同见 `/tmp/ledger-skill-examples-js.log`；Android 主机助手定向测试 12 项通过，无失败或跳过，见 `/tmp/ledger-skill-examples-qa.log`。

- 基线 `9bc703bc`（2026-11-29，UTC+8）。附件：SQLite 测试从旧版截止仍可读取复现，现验证临时文件活动续期、期限前最后一毫秒可读、重放不续期、截止时拒绝重放、清理后正文无残行且保留消息。Chrome 1440×900、390×844 整卡直接下载并核对 CSV，桌面 Tab 聚焦及 Enter 下载，推进浏览器时钟后原页显示过期、点击不下载、刷新仍保留文件名，脚本读取旧引用失败且文件列表为空，零账本写入。正文回收由前台周期及文件操作触发，原生系统保存面板跨截止仍未实机验收。日志 `/tmp/ledger-attachment-ttl-red.log`、`/tmp/ledger-attachment-ttl-browser-final.log`。
- 菜单：Chrome 同两个视口覆盖右键／长按、侧栏更多、页头更多、置顶后的划线图钉、重命名、切换和删除，非当前操作不切走当前对话，两视口分别三次推理、零账本写入。删除按钮文字与图标共用 M3 状态色。实际入口日志为 `/tmp/ledger-assistant-menu-icons-rerun.log`。取消置顶图标用 Google Material Symbols Outlined 的 `keep_off`，其余复用项目图标。
- 文字选择：Chrome 1440×900 跨段鼠标拖选和用户气泡的部分文字均复制到实际剪贴板；390×844 用触控仿真长按并拖动选择手柄跨段，通过菜单「复制」核对相同范围文本。两视口验证历史回复、原代码块复制、输入换行后替换；桌面复跑链接单击。流式及失败回退同一共享容器，未专门注入流中剪贴板竞态，未做原生文字手势。见 `/tmp/ledger-assistant-selection-final.log`。
- Android 主机助手定向和 APK、iOS 模拟器编译、Web Wasm 开发构建通过。仅刷新 18815 隔离演示前端，原后端与模型保留。Chrome 从实际 HTTPS 模型替身经四次 200 生成并点击下载 51 字节月报。未动 18804／18806。日志 `/tmp/ledger-assistant-attachment-menu-selection-build.log`、`/tmp/ledger-assistant-native-final-qa.log`、`/tmp/ledger-attachment-live-preview-qa.log`。截图见 http://127.0.0.1:18816/ 的 attachment-30d、history-icons、text-selection 目录。

- 回复与文件面板，2026-11-29（UTC+8），基线 `812fb6fc`。Android 主机测试覆盖 1 月 1 日看前一日为昨天、闰日、同年及跨年、多段原文、待确认及空正文过滤、附件倒序与同名。测试先因缺少行为入口失败。Android Debug APK、iOS 模拟器共享编译、Web Wasm 开发 Webpack 通过，见 `/tmp/ledger-assistant-reply-files-final-android-web.log`、`/tmp/ledger-assistant-reply-files-final-ios.log`。命令在 `compose/` 使用既有本机依赖仓库配置运行 `:shared:testAndroidHostTest`（`*AssistantReplyCopyTest`、`*AssistantConversationFilesTest`、`*LedgerAssistant*`）、`:androidApp:assembleDebug`、`:shared:compileKotlinIosSimulatorArm64`、`:webApp:wasmJsBrowserDevelopmentWebpack`。
- Chrome 隔离预览 18812 用 1440×900 亮色鼠标和 390×844 暗色触控核验回复。两段 Markdown 实际剪贴板含原标记且排除工具和业务卡，部分回复只复制已收到的原文。中断行四项横向顺序及红色渲染经截图核对。刷新保持原时间和原用户消息，未自动请求，手动重试后只有该轮原位置的最终正文。推进本地日历后显示昨天。既有 Markdown 选区、代码复制、编辑输入均复测。日志 `/tmp/ledger-reply-actions-browser-final.log` 和 `/tmp/ledger-reply-actions-selection-regression.log`。
- Chrome 18812 两视口覆盖页头空态、桌面右键与手机长按打开 B 的文件，正在输入的 A 及 URL 保留。两份同名附件按最近发送在前，直接下载并逐字节核对。原页跨过 30 天截止，两张卡保留文件名及过期状态，点击无下载。关闭按钮及桌面 Escape 返回。每个视口均仅有夹具生成对话的 6 次推理及 0 次写入。见 `/tmp/ledger-conversation-files-browser.log`。当前 18815 隔离预览重新通过 51 字节月报下载，见 `/tmp/ledger-assistant-reply-files-live.log`。截图见 http://127.0.0.1:18816/ 的 reply-copy、conversation-files。原生系统剪贴板和文件选择仅编译核对，设备交互未运行。

- 复制 Snackbar 验收基线 `ea7e3de2`，2026-11-29（UTC+8）。Android 回复复制主机测试、Debug APK、iOS 模拟器共享编译和 Web Wasm 开发构建通过，见 `/tmp/ledger-copy-snackbar-final-build.log`。Chrome 18812 在 1440×900 亮色鼠标和 390×844 暗色触控核对完整与中断回复的真实剪贴板、相同提示合并、权限策略拒绝后「无法复制」、恢复复制后「已复制」、图标像素、输入及切换清除。记录 `/tmp/ledger-copy-snackbar-browser-final.log`，截图见 http://127.0.0.1:18816/copy-snackbar/。18815 更新后用模型替身生成月报，成功下载 51 字节附件并验证复制原文及 Snackbar，见 `/tmp/ledger-copy-snackbar-live.log`。原生设备读屏和剪贴板实机交互未运行。

## 7. 发布

- [x] 更新功能地图及相关文档，记录用户决策反思。
- [ ] PR 前同步主规格并归档。

- 本次用户明确以既有 AppShell 为导航基线，新版 HTML 原型定义多行聊天及真实工具记录的显示，工程主题定义具体样式。决定适用于本助手，已记入本 change，无需改动通用 UI 规范。投递、断流和手动重试语义，以及本机历史菜单与写入期间的删除保护均适用于本助手，保留在本 change。回滚代码时可继续读取原消息字段，新增设备历史属性均有默认值；已记账流水及原键收据继续由现有账本接口持有。

- 对话附件与本机临时文件的边界源自用户确认，仅适用于账本助手，无需新增通用规范。发布升级顺序为先让服务端接受两种协议，再启用客户端技能。回退需要保留附件读取兼容层或先核定设备缓存迁移。旧二进制未验证读取含新增附件字段的设备缓存。

- 回复操作和文件面板的取舍仅适用于本助手。沿用当前 change 记录，不新增全局 UI 规则。回复时间字段可缺省，回退时沿用既有本地消息及附件。
