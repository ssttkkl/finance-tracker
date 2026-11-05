## 1. 思考

- [x] 1.1 阅读项目上下文、产品规则、词表、当前导入规格、读取器与默认回归；按 A 类处理财务输入边界。
- [x] 1.2 真实文件 API 复现和独立只读根因核对：XLSX 完整 XML 被截断；PDF 文字与图形坐标不同导致表格转置。真实数据及明细仅在仓库外受控临时环境。
- [x] 1.3 用户明确本轮失败修复及真实导入验收是迁移完成条件；临时账号、工作区、测试资金账户和原分支提交／PR 授权延续。用户已明确纳入现有六渠道统一候选快速探测合同，读取修复与探测补齐均在本次验收。

## 2. 计划

- [x] 2.1 建立 proposal、完整 MODIFIED delta 与 design，记录输入、安全、来源保真、回滚和验收边界。
- [x] 2.2 通过 update-change 回写用户确认的六渠道探测范围、设计与完整场景，校验 artifact 一致性。

## 3. 任务拆分

- [x] 3.1 将大工作簿、自动表头、完整 XML／ZIP 安全和旋转 PDF 失败回归安排在实现前；随后保留原 golden 与预算回归。
- [x] 3.2 区分默认自包含测试、仓库外原始文件验收、SQLite／真实 PostgreSQL 和浏览器门禁，未执行项不算通过。

## 4. 构建

- [x] 4.1 自包含 XLSX 回归修复前 10 项中 8 失败：大表、空来源、隐蔽工作表、DTD、目录内容和截断 ZIP；命令 `:backend:test --tests '*CashWorkbookDocumentTest'`，基线 38c4ae48。
- [x] 4.2 完整流式 XML／ZIP 验证及空来源读取后，工作簿 13 项及原 golden 通过，包含实际解压、深 XML、隐式列和空行坐标拒绝；默认自包含回归已执行。
- [x] 4.3 新增信用卡／借记卡四方向及 CropBox 回归，两个测试方法修复前均以表头无效失败，未放宽原文和覆盖字段断言。
- [x] 4.4 统一 PDF 文字与边线坐标，新旧文档、密码和预算测试通过；探测接入后聚焦回归再次通过。
- [x] 4.5 同组更新功能地图、操作说明、六份现金格式参考及 apply／cash-statement-import skill；移除已退役 Python 读取方式和失实的静默跳过说明，不改变财务规则。

- [x] 4.6 候选调用顺序、六来源原字节、无匹配／歧义／无回退、文件错误、密码与多页首页回归通过；新探测类 10 项。零／多匹配与后续审查发现的多表头、隐式列问题均先失败再修复。
- [x] 4.7 按用户确认提高会话预算为 128 MiB、内存总量为 256 MiB，解析内容仍 25 MiB；HTTP 容量回归先 2 失败后 3 项通过，replacePlan 预算、转义大小及总量拒绝均保留旧状态。压缩及冗余清理后续评估。
- [x] 4.8 修正既有 type-resolved 静态门禁：PortfolioTimezone.local 显式 LocalDateTime、六个历史 fixture 时钟显式 Instant、一处测试改 assertNotNull。未改变计算或测试预期；历史夹具测试只编译和静态检查，不冒称执行。
- [x] 4.9 按用户明确要求，内容超限先返回文件步骤再提示，已解析文件和有效账户选择保留；实际增删、不重复冻结和旧计划禁止确认的共享回归先失败再修复；Snackbar Host 断言先红后绿，消息消费不清除文件调整能力。用户确认仅内容超限返回文件步骤；总容量不足保留原步骤重试。按用户纠正，超限原因复用现有 Snackbar 显示一次，去除顶部常驻重复提示，补先红后绿和消息生命周期回归。

## 5. 审查

- [x] 5.1 独立工程／安全上下文复核读取、来源原文、金额／时间、拒绝边界、ZIP／XML／PDF、异常、预算、复杂度及最终实现；下表原 finding 均已修复并重审，无新增阻断。
- [x] 5.2 独立上下文执行代码库防劣化及契约专项。容量错误的共享文案漏接已修复，四份 locale 通过；恢复交互的 UI 复用及最终视觉范围由下项覆盖，不把最初纯文案范围作为最终结论。
- [x] 5.3 独立工程／契约与 UI 复用专项通过：临时状态不进入 API／持久化，既有文件操作、按钮、Snackbar Host 与 token 直接复用。新 768×900 Snackbar 截图及实际流程独立复核，Hallmark audit 为 0 critical／major／minor；旧顶部错误截图不作为本次证据。

| 严重级别与范围 | Finding 及处置 | 复核证据 |
|---|---|---|
| P1／工程 | 首个匹配表头遗漏其他来源；改为有界窗口累积来源集合，覆盖顺序及 shared／inline 混合 | 新回归先红后绿，独立重审关闭 |
| P1／契约 | HTTP 容量码未接共享文案；三端共用本地化映射，明确减少文件或稍后重试 | 两种错误 Chromium 2/2，768×900 截图及独立复核通过 |
| P2／安全 | 隐式坐标绕过第 128 列；显式／隐式使用共同上限 | 真实 XML 回归先红后绿，独立重审关闭 |

独立真实验收摘要复核确认双后端结果自洽、完整分页、原键重放及源摘要未变；未读原始账单，不把部分业务未导入的收据写成全部正式入账。遗留风险为大对象峰值内存及并发负载，不由合成预算测试替代。

## 6. 测试与 QA

- [x] 6.1 默认后端 853 项、真实 PostgreSQL schema 1 项包含其中且 0 skip；codegen、contracts 3／domain 10、ktlint、detektMain／detektTest 及 installDist 通过。共享 Wasm 519 项、Android debug、iOS Simulator Arm64 编译通过。详见当前命令表；新恢复交互的共享测试、相称原生编译及两个浏览器流程已补齐。
- [x] 6.2 干净临时 SQLite 同一 HTTP 上传全部 19 份原始文件，全部就绪；完成测试账户选择、整批预览、提交、全部分页及原键重放，源文件摘要未变。收据包含已有规则下的草稿、重复、业务跳过和待确认关系，不等于全部正式入账。
- [x] 6.3 手工准备专用 PostgreSQL `_test` 库，同一 HTTP 验证全部原始文件；文件状态、预览与提交汇总、逐行原因、全部结果数及重放与 SQLite 一致。专用 PostgreSQL 运行时及本轮完成的后端临时数据已清理。
- [x] 6.4 固定最终实现的本地完整运行 184 项通过、1 项截图／trace 写入 ENOSPC（16.5 分钟）；清理本任务可重建产物后，同源码受影响用例无重试补验 1/1 通过（10 秒）。不记为单次 185 全绿。此前读取修复及容量文案快照本地 185/185；最终 Snackbar 两项无重试通过（18.9 秒）。旧远端 02514846 为 180/183，其三个问题已在 `fix-compose-browser-regression-gates` 修复及独立复核；最终 SHA 的远端完整门禁提交后核对，不能提前宣称通过。
- [x] 6.5 OpenSpec 全部严格校验 28 项、doctor、23 份变动 Markdown 的 194 个本地链接／锚点及 git diff --check 通过；性能及安全检查范围与边界见下文。

## 7. 发布

- [x] 7.1 已核对 README、规格、功能地图、词表、六份格式参考、API／命令示例、配置及两个技能。用户要求真实导入作为完成门槛、明确测试账户授权、先提高有限预算后评估优化，以及仅内容超限回文件并用 Snackbar；前两项已有事实验证与隔离规则，后两项属于本导入的已批准合同，无需新增通用规范。
- [x] 7.2 读取器、预算及恢复交互的本地实现、审查与适用验证完成；两份主规格的 3 个需求块已同步并逐块比对一致，使用 `openspec archive fix-migrated-cash-statement-parsers --skip-specs --yes --json` 归档。该标志仅避免重复应用已经同步的 delta，不跳过主规格。迁移 PR 的最终远端门禁仍按下项核对，未提前认定 CI 通过。
- [ ] 7.3 检查 status／diff／相关验证后，按既有授权提交推送至 origin/continue-kotlin-backend-refactor 并更新 PR 到 main；不合并、不 Render 部署。

本地验证证据对象为 `02514846fb5ffe90dceb90a7412bcdb53f3d5301` 加本 change 与同组浏览器门禁修复工作树，读取修复对照基线 `38c4ae488fa08efd09f141bfd54240b287dd00ec`。执行日期为 2026-11-05，仓库外 QA 目录保存脱敏摘要和命令日志，不提交真实文件、原名、账号、密码或交易明细。Cross-platform Impact：后端读取、共享文案及恢复交互影响 Web、Android、iOS；已执行共享测试、Android 构建和 iOS 编译，未执行原生设备交互，不宣称通过。

| 当前实际命令／验证入口 | 结果与边界 |
|---|---|
| 显式 `FT_TEST_POSTGRES_URL`，`./gradlew :backend:test :backend:codegenTest :backend:ktlintCheck :backend:detektMain :backend:detektTest :business-contracts:jvmTest :business-domain:jvmTest --max-workers=1` | 后端 853 通过、0 跳过；静态分析另以默认构建内存执行下行命令通过 |
| `./gradlew :backend:detektTest :backend:detektMain :backend:ktlintCheck :backend:codegenTest :business-contracts:jvmTest :business-domain:jvmTest --max-workers=1` | 通过，未用低堆内存中止的结果冒充通过 |
| `./gradlew :backend:installDist --max-workers=1` | 通过 |
| `./gradlew :shared:wasmJsTest :shared:compileKotlinIosSimulatorArm64 :androidApp:assembleDebug --max-workers=1 -Dorg.gradle.jvmargs='-Xmx1536m -Dfile.encoding=UTF-8' -Pkotlin.daemon.jvmargs=-Xmx1536m` | 519 项通过，两个原生目标编译／构建通过 |
| 两种 Python 入口及 `npm --prefix compose/qa/web run test:node` | direct／discovery 各 11 项；Node 5 项通过 |
| 原始文件同一 API 验收，SQLite 与显式专用 PostgreSQL | 两边 19 份全部就绪；预览与提交汇总一致，完整收据及重放通过，原件未改 |

性能／安全范围：工作簿实际解压和 XML 安全、PDF 隔离进程密码／超时／预算、计划与内存总量拒绝原子性已验证。整批预览约 15–22 秒、提交约 25–67 秒，测试堆 1,536 MiB，后端采样 RSS 约 1,889–1,966 MiB；不代表小内存或并发部署可运行。生产 JVM 内存须在首次发布前按实际批次验证，本轮不部署 Render。真实文件未包含工银亚洲，该来源由自包含 golden 覆盖；历史外部迁移夹具任务未运行，不计入通过数量。
