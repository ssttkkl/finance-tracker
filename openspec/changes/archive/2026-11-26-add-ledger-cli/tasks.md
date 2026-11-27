## 1. 思考

- [x] 1.1 核对领域词表、项目上下文、功能地图、现金导入与缓存主规格，以及现有 Gradle 模块、认证、账本快照、工作区路由。A 类变更涉及多端共享规则、敏感数据落盘和并发写入。选型与验证边界见 design.md。

## 2. 计划

- [x] 2.1 确认 Kotlin/JS、Node.js 和 npm 分发。注册与工作区管理、客户端已用账户操作、收支与现金导入纳入一期；支持断网重启和本地准备，每条命令按需判断联网。
- [x] 2.2 建立共享账本操作、CLI 行为与工作区管理守卫三份 delta，明确预期范围、离线授权、实际收据与部署顺序。CLI 自身无图形页面；共享客户端层会影响 Web、Android 和 iOS，按实际改动验证。

## 3. 任务拆分

- [x] 3.1 将命令输入输出、跨进程离线读取、同会话切换、财务结果对齐、导入恢复与发包分别映射至第 4、6 阶段。每个切片先写能重现缺口的失败测试，再实现并记录证据。

## 4. 构建

- [x] 4.1 建立 JS Node 可执行模块与 npm bin。通过真实子进程验证帮助、版本、无 TTY 缺项、JSON、退出码和凭据输入。
- [x] 4.1a 在公共 Kotlin 源集交付可直接调用的操作接口，先用月度汇总验证与 Node/终端的依赖边界，再将 4.2–4.6 的业务命令接到同一操作层，复用真实的会话和导入生命周期。
- [x] 4.2 打通登录、选工作区、取快照、退出进程、断网后新进程读同一笔收支及账单来源。实现服务地址、原凭据、身份与工作区隔离、密钥与缓存生命周期、同步时间和错误区分。
- [x] 4.3 实现管理写入事务守卫与兼容测试，接入工作区、成员、邀请与账户命令。覆盖切换后旧范围写入、撤权、只读角色和匿名预览邀请。
- [x] 4.4 复用共享收支查询与写入，覆盖时区、分页、精确汇总、转账两端、公共字段、分类、批量操作、草稿、关联和来源处理，验证未决写入与同值独立记账。
- [x] 4.5 接入完整现金文件准备与账户选择，覆盖多文件修改、CSV/XLSX 本地解析、PDF/XLS 在线解析、补币种、退出补偿及离线恢复。
- [x] 4.6 接入整批核对、确认、冻结写集与实际结果。验证核对版本冲突、并发进程、响应丢失、重复导入和成功后重启整理。用现有夹具对比两种入口的业务结果。

### 4.3 后端守卫

五个管理写入接受可选 `workspace_id`，并在事务内锁定前后核对原会话。范围冲突返回 409 `workspace_conflict`。Compose 既有省略范围的请求以及显式选择、接受邀请沿原合同执行。后端守卫提交为 `175003ae`。

## 5. 审查

- [x] 5.1 独立上下文按产品编码规范复核产品范围、CLI 参数与错误、凭据与文件、缓存范围、金额精度、并发、写入恢复和兼容性。
- [x] 5.2 独立执行 `codebase-degradation-review` 并应用 `contract-design-review`。检查现有 HTTP 调用兼容、错误分类、共享传输类型和存储写入职责。图形页面、交互和组件无变更，UI 专项审查不适用。

阻断项已修复并复核。后端锁内重新验证预期工作区，旧调用仍可省略范围。缓存只有明确传输不可达时可降级，同令牌的迟到读取受存储代次保护，明确撤权或失效使旧材料不可读取。账户补偿核对连续修订，只撤回本批次的已知效果。登记账户操作与冻结或删除批次在同一存储事务中互斥。收据查询失败报告结果待核实，只有提交接口明确拒绝基线时可重算。分类部分更新保留省略字段，账户余额读取前后核对 ID 和修订。独立复核通过 SQLite 测试服务实测原键拒绝、等价重算、下一尝试收据丢失及按原键恢复，核对陈旧确认没有再次发出 POST。未覆盖服务端事务锁等待期间的确定性交错。

## 6. 测试与 QA

- [x] 6.1 执行 OpenSpec 严格校验、受影响测试、完整回归、构建和静态检查，未通过及补跑条件见下。工作区守卫和存储合同在 SQLite 与专用真实 PostgreSQL 验证。
- [x] 6.2 运行 CLI 子进程、断网重启、权限／密钥、故障注入、双进程及真实夹具矩阵。覆盖完整快照和大账单资源边界。共享层抽取按受影响流程执行 Web 浏览器冒烟及 Android、iOS 编译与单测。
- [x] 6.2a 用相同夹具从独立 Kotlin 调用方与生产 CLI 入口分别读取月度汇总、核对现金导入并恢复提交，比较精确金额、范围、版本、待处理原因和实际收据语义；检查 Compose 目标可编译依赖该层。
- [x] 6.3 从 npm 压缩包在空目录安装并执行帮助、登录、账本查询与现金导入；确认最终依赖清单、最低 Node 版本、经验证平台与限制。

核对日期为 2026-11-26，比较基线为 `595aeeb5`，实测实现提交为 `41b29f1b`。后端守卫 `175003ae` 的 `./gradlew :backend:test --tests '*WorkspaceExpectedScopeHttpTest' --tests '*OpenApiContractTest' --offline` 在临时 SQLite 与真实 PostgreSQL 17.12 专用 `ft_ledger_cli_scope_test` 通过。修复前 SQLite 在错范围改名返回 200，修复后五种旧范围管理请求返回 409，两个工作区名称、成员和邀请数量保持，匹配范围的 viewer 收到 403，兼容调用成功。

- 在 `compose/` 执行 `./gradlew :cli:stageNpm :client-ledger:jvmTest --offline`，五项共享操作测试通过。Kotlin 调用以相同的快照和支付宝夹具验证 `-12.340000000000000001`、来源与核对项，并在冻结请求后模拟断线，恢复同一收据且无第二次 POST。
- macOS ARM64 与 Node.js 26.2.0 执行 `node --test cli/test/entry.test.cjs cli/test/offline.test.cjs cli/test/server.test.cjs`，显式设置 `FT_TEST_POSTGRES_URL` 后四项通过、零跳过。覆盖安装、原进程退出后的离线收支与来源、同步时间、密钥与撤权、CSV／XLSX／PDF／旧 XLS、有界文件、部分失败、只读邀请、原收据恢复、手工保存进程被终止、同值独立写入、分类与维护、补币种和按修订撤回。批次冻结的双库回归修复前错误地发送了导入请求，修复后账户命令未核实期间零导入 POST。确认回包丢失后，查询接口返回错误时保留结果待核实，成功查询返回原收据，实际账本仅有一笔导入，余额为 `-13.00`。
- `npm pack ./cli/build/npm --pack-destination ./cli/build` 生成 2,099,717 字节的私有安装包 `finance-tracker-cli-0.1.0.tgz`，SHA-1 为 `9457448beaba89fe019cedc3219bea83fa501901`。包内含 Node bin 和 Kotlin 生产模块，安装无额外 npm 依赖。端到端测试采用外部密钥注入，尚未用有解锁授权的 macOS 登录钥匙串实测。Linux 和 Windows 未实际安装验证。
- `./gradlew :backend:test --offline` 记录 1,533 项，零失败、302 项按环境跳过。上面的真实 PostgreSQL 矩阵单独执行，后端全套结果只计实际运行项目。`./gradlew :business-contracts:jvmTest :business-domain:jvmTest :shared:iosSimulatorArm64Test --offline` 分别通过 34、915 和 1,017 项。
- `./gradlew :shared:testAndroidHostTest --tests '*LocalCash*' --tests '*LedgerSession*' --tests '*LedgerSnapshot*' --tests '*CashLedgerCalendarTest' --tests '*CashLedgerDateBoundsTest' --tests '*MaintenanceSavePlanApiTest' --tests '*ImportTaskViewModelTest' --offline` 受影响 Android 宿主范围通过 434 项。完整 Android 宿主运行 1,137 项，17 项历史 HTTP 消费者因缺少历史输入与实际宿主地址失败，另有 10 项跳过。补跑须提供 `FT_KOTLIN_CLIENT_CONTRACT_DIR`、`FT_KOTLIN_INVESTMENT_READ_CONTRACT_DIR`、`FT_KOTLIN_PORTFOLIO_CONTRACT_DIR` 和 `FT_KOTLIN_PORTFOLIO_HOST_FILE`，并核对历史响应可用于当前 API。现无可用的 `manual-cash-responses.json`，未复活退役路由或以旧 oracle 替代当前证据。Android 和 iOS 未执行设备交互。
- 后端、业务契约和业务领域的 `ktlintCheck`，后端 `detektMain`、`detektTest` 与两个业务模块的 `detektMetadataCommonMain` 均通过。业务领域的 `runKtlintCheckOverCommonTestSourceSet` 初次耗尽 Java 堆，用仓库外 Gradle init script 将该任务的 `workerMaxHeapSize` 设为 `768m` 后重跑通过，仓库构建设置无变化。CLI 安装包与公共模块以编译、实际运行和差异检查验证。
- 在 `compose/qa/web` 执行 `npm run test:node`，44 项通过。`npm run test:e2e -- compose-cash-month-summary.e2e.ts compose-local-first-cash.e2e.ts compose-local-import.e2e.ts` 通过 13 项。Chrome 158.0.8127.37，预览 `http://127.0.0.1:5198` 连本机 Kotlin 后端及隔离 SQLite，视口包括 390×844、768×844、1440×844 和 1440×1100。覆盖月度汇总、收支及离线 Worker、本地账户、登录后断网核对、冲突重核、已提交后 503、重开结果和冷启动。修正既有冒烟测试仍断言已退役的手动整理按钮，保留原故障注入，并核对刷新后没有重发写集。用例断言相关流程无非预期浏览器、资源或 API 错误，截图在 Playwright `test-results`。
- 只依赖 `:client-ledger` 的临时 Kotlin/JVM 调用模块实际编译，直接读取月度版本和恢复结果的权威收据。业务领域类型随公开接口导出。重跑 `./gradlew :webApp:wasmJsBrowserDistribution :cli:stageNpm :client-ledger:jvmTest --offline --no-configuration-cache`，打包任务复用相同输入，167 个浏览器文件的 SHA-256 全部一致。
- `openspec validate --all --strict` 在归档前通过 33 项、同步归档后通过 34 项。`openspec doctor`、`git diff --check`、新增文档链接和锚点检查均通过。三份主规格逐项比对归档增量，原工作区要求保留。

## 7. 发布

- [x] 7.1 核对 README、命令示例、功能地图、术语和现金导入 Agent 指引。用户选择将有类型的共享账本操作和 CLI 一并交付，内置 AI 单独设计。此边界及 Kotlin/JS 选型适用于本变更，无需新增通用规则。
- [x] 7.2 同步共享账本操作、账本 CLI 和工作区管理三份增量，更新规格导航并归档 change。交付为当前分支和本地 npm 安装包。

兼容顺序为先上线支持新守卫的服务端，再使用 CLI。出现故障时保留原服务地址、状态目录、密钥与待核实操作 ID，使用支持该存储格式的版本恢复原收据。可从终端卸载命令，保留加密恢复材料。后续使用中观察会话冲突、收据待核实及密钥库不可用，其中待核实操作需保留原身份与请求。
