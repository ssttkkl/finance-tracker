## 1. 思考

- [x] 1.1 对照 `d07d8a43`、共享客户端主规格、活跃变更、词表与产品编码规范，盘点 Compose、CLI 正式调用链。按会话范围与恢复影响定为 A 类。

## 2. 计划

- [x] 2.1 定义共同生产操作和宿主边界。保留现有的会话、离线、Worker、财务结果与 HTTP 合同。范围和验收经用户确认。

## 3. 任务拆分

- [x] 3.1 建立下表，按受影响操作映射基线、实施与验证。每个切片先运行能分辨旧路径的失败回归。

### 生产操作矩阵

Compose 正式装配统一由 `createFinanceTrackerGraph` 提供。CLI 正式入口为 npm `bin/ft.cjs` 到 Kotlin `dispatch`。表中状态是实际生产路径。

| 业务操作 | Compose 页面、状态与仓储 | CLI handler 与公共操作 | 状态与验证 |
|---|---|---|---|
| 登录、注册、确认会话、退出 | 认证页面／`SessionCacheRepository` → `KtorSessionRepository` 与 `AuthApi` 凭据入口 | `auth` → `ClientSessionOperations.login/register/status/logout` | 已接 `ClientAccessOperations.login/register/session/logout`。宿主保留发布与加密缓存清理 |
| 工作区列表、当前工作区、选择、创建 | 工作区入口／侧栏 → `SessionCacheRepository` → `KtorSessionRepository` | `workspace` → `ClientSessionOperations.status/currentWorkspace/selectWorkspace/createWorkspace` | 已接 `ClientAccessOperations.session/selectWorkspace/createWorkspace`。切换在关闭旧缓存前固定原令牌 |
| 改名、删除、列成员、成员变更、创建邀请 | 管理页 → `WorkspaceManagementViewModel` → `SessionCacheRepository` 与 `KtorSessionRepository.boundTo` | `workspace` → `ClientSessionOperations.renameWorkspace/deleteWorkspace/members/setRole/removeMember/createInvitation` | 已接 `ClientAccessOperations` 的管理操作。页面范围固定原工作区与使用者，服务端事务继续授权 |
| 邀请预览、接受 | 邀请入口 → `KtorSessionRepository.invitationPreview`／会话切换 | `workspace` → `previewWorkspaceInvitation`／`ClientSessionOperations.acceptInvitation` | 已接 `ClientAccessOperations.previewInvitation/acceptInvitation`。预览匿名，接受固定原令牌 |
| 现金账户目录与选择资格 | 记账与导入账户选择 → `LocalCashLedgerReader`、`LocalCashImportLifecycleRepository.selectionAccount`，Web 经 `CashLedgerWorker` | `account list`、`import map`、`cash add/transfer` → `ClientSessionOperations`、`ClientCashImportOperations` 和 `ClientCashWriteOperations` | 共用 `cashAccountDirectory`、`cashAccountFact` 和 `isSelectableCashAccount` |
| 新建账户 | 记账账户表单／`CashImportAccountForm` → `ImportAccountViewModel` 与 `LocalCashImportLifecycleRepository` | `account`、`prepareImportAccounts` → `ClientCashWriteOperations.createAccount`／`ClientImportAccountOperations.create` | 已接 `ClientAccountOperations` 与 `executeAccountWrite`。原键查据、身份校验及已知失败共用 |
| 补币种与撤回 | 导入账户状态 → `ImportAccountViewModel` → `LocalCashImportLifecycleRepository` | `prepareImportAccounts` → `ClientImportAccountOperations.ensureCurrencies/undo/cancel` | 共用 `missingCashAccountCurrencies`、`executeAccountWrite`、`validateAccountWrite` 和 `compensateImportAccounts`。币种已满足时保留账户和账本修订 |
| 收支列表、月度汇总、定位 | `CashLedgerScreen` → `LocalFirstFinanceRepository` → `LocalCashLedgerReader.page`（Web 经 `CashLedgerWorker`）→ `CashLedgerOperations.page` | `cash` → `CashLedgerOperations.page`（`summary` 委托同一查询） | Web 与原生均进入共享查询。实际页面、安装包覆盖分币种精确金额、来源和游标 |
| 详情与来源 | 收支详情 → `LocalCashMaintenanceRepository` → `LocalCashLedgerReader.record/sourceRecords`（Web 经 `CashLedgerWorker`）→ `CashLedgerOperations.record/evidence` | `cash` → `CashLedgerOperations.record/evidence` | 详细事实与原始来源联验 |
| 手工记账、转账及原操作恢复 | `CashCreationPanel` → `CashCreationViewModel.save` → `KtorCashCreationRepository.saveRecovery` | `cashWrite` → `ClientCashWriteOperations.add/transfer/resume` | 共用 `freezeCashRecord/freezeCashTransfer` 和 `executeCashCommand`。原始表单输入与冻结命令同存于加密范围，回到原入口核实 |
| 编辑、保存草稿、草稿转正式、身份与关联 | 收支详情 → `CashRecordTaskViewModel` → `LocalCashMaintenanceRepository` 的维护计划、候选及提交 | `cashMaintenance`、`cashWrite` → `ClientCashMaintenanceOperations` 与 `ClientCashWriteOperations.commitMaintenance/resume` | 共用 `preview/associationTargets/resolve`、`freezeMaintenanceCommand`、`executeCashCommand` 和 `resumeCashMaintenance`。旧版加密命令从原路径恢复计划 ID |
| 分类与批量删除、分类目录 | 账本选择／管理页 → `KtorFinanceRepository`；账本目录经原生读取器或 `CashLedgerWorker` | `cashMaintenance`、`category` → `ClientCashMaintenanceOperations` 与 `cashCategoryDirectory` | 已接共同影响预览、批量操作、目录写入及快照分类读取 |
| 导入文件捕获、追加、替换、移除、取消 | `CashImportTaskScreen` → `LocalCashImportLifecycleRepository` → `LocalCashImportTask`，Web 由 `CashImportFileWorker` 捕获 | `cashImport`、`editImportFiles` → `ClientCashImportOperations` | 共用 `captureCashImportFile`、`completeCashImportFile`、`changeCashImportFiles` 与 `cancelPreparedCashImport` |
| 整批核对、明细、确认、收据与恢复 | `CashImportTaskViewModel` → `LocalCashImportLifecycleRepository` → `LocalCashImportTask`、`LedgerSessionCacheOwner`，Web Worker 负责计算 | `cashImport` → `ClientCashImportOperations.review/items/confirm/resume/result` | 共用 `calculateCashImportReview`、`freezeCashImportConfirmation`、`resumeCashImportSubmission`、`rebaseRejectedCashImport` 和 `CashImportResultContent.assemble` |
| 账户详情独立余额查询 | 现金页面仅使用账户选择和能力；没有独立对应编辑或详情入口 | `account show` | CLI 独有展示。纳入共享账户操作的能力与修订，独立命令展现不新增页面 |
| 待核实操作和本机批次独立列表 | 页面按当前任务恢复已持有的操作或批次 | `cash pending`、`import batches` | CLI 独有枚举入口。共同恢复业务仍在对应行验收 |
| 维护详情分页与计划概要 | Compose 从已打开的维护计划读取分页明细，用于提示关联、公共字段和影响 | `cash plan` 复看概要，`cash candidates` 搜寻候选，`cash edit --input` 与 `cash commit` 提交已知选择 | 共同预览、候选、提交和恢复见维护行。直接保存草稿和按上下文查计划的兼容方法没有页面调用 |
| 投资、余额校准、内置 AI | 各自既有范围 | 一期命令缺少对应业务 | 本次范围外 |

## 4. 构建

- [x] 4.1 先写失败回归，迁移会话、工作区与成员邀请并核对边界。
- [x] 4.2 迁移记账、转账、分类和收支维护，收敛确认、待核实与收据，登记真实页面调用。
- [x] 4.3 迁移账户选择、创建、补币种及有修订保护的补偿。
- [x] 4.4 迁移现金导入从文件准备到核对、冻结、确认、收据、恢复与取消。
- [x] 4.5 清理已被替代的生产路径。核对操作矩阵中的公共实现及单端入口。

邀请预览的 Android 回归先失败，原 Compose 请求携带已有登录令牌；接通后 `FinanceApiClientTest`、`InvitationViewModelTest` 与共享 JVM 测试通过。Ktor 适配器保留状态、拒绝原因与诊断。

## 5. 审查

- [x] 5.1 独立验收生产路径、产品范围、实现与最终 diff，核对权限、金额、幂等、恢复及取消。
- [x] 5.2 运行代码库防劣化与契约专项复核。图形页面复用与受影响状态经独立审查。

邀请预览经独立工程复核确认两端直达共同操作，Ktor 原请求的状态与诊断处理保持。后续签名写入迁移须先确认原工作区头和错误类型映射。 记账执行器及发送前围栏经独立复核通过。区分持久化拒绝与账本成功，保留实际 401／403；旧测试改为请求已进入网络后再切换令牌，继续核对迟到结果。

维护与 Web Worker 切片以 `2c346596` 为基线。独立复核确认动态计划读取、原计划和键的匹配、恢复时使用原会话，以及 Worker 只在需要时编码核对上下文。发现的旧版命令降级到普通收据查询、同工作区重取令牌两项已修复并重审。

导入执行切片以 `f2501787` 为基线。独立复核发现紧凑请求降级时查询错误可能被误当作写入拒绝，另发现取得权威收据后有一次会覆盖成功结果的额外本机读取。两项均修复并重审。重算先验证原会话和原收据，保留服务拒绝与本机存储诊断。

账户写入切片以 `988ee63a` 为基线。独立复核确认两端使用同一账户证明和撤回循环。畸形收据误判成功、已撤回前序阻止同批再次扩币、查据故障后补币种重发及未知诊断丢失均已修复并重审。

最终契约复核沿加密账本范围、原请求与收据、HTTP 和实际页面／CLI 入口检查。修复冷恢复被现行账户预检阻断、明确拒绝未释放原命令、查据拒绝误作写入证明、维护过期未结案、币种未规范化产生额外写入，以及选择文件绕过账户资格六类问题。新表只保存本机恢复材料，通过原范围和条件更新保护，服务端仍负责权限和实际账本事实。最终产品和差异复查未留阻断项。

界面复用与 Hallmark 核对覆盖恢复时的原保存按钮、已有表单、进度、失败状态及账户撤回弹层。布局、文案、主题与组件体系沿用原有实现。Web、Android、iOS 都经相同共享状态处理，Web 的原本地 Worker 和浏览器流程已复验，原生覆盖编译与单测。Web 个别已关闭下拉框仍残留在辅助语义层，测试经固定视口点击实际渲染的控件。

## 6. 测试与 QA

- [x] 6.1 运行 Kotlin、后端、跨端编译和受影响回归，记录当前提交、基线、精确命令与结果。
- [x] 6.2 用真实浏览器走 Compose 页面，用已安装 npm 包走 `ft`；按矩阵记录共同生产符号及权限、换区、离线和写入失败证据。
- [x] 6.3 在专用 SQLite 和真实 PostgreSQL 核对同一写入合同；严格校验 OpenSpec、静态检查和差异。

邀请预览切片于 2026-11-26 使用 Chrome 158 在 `http://127.0.0.1:5199` 点击邀请和换账号入口，`npm run test:e2e -- compose-core-ui-workflows.e2e.ts --grep '从邀请链接进入|接受邀请时'` 通过 2 项。实际 npm 安装包的 `node --test cli/test/entry.test.cjs cli/test/offline.test.cjs cli/test/server.test.cjs` 在 SQLite 通过 3 项，包含匿名预览。此切片没有存储合同变化；写入矩阵所需的双库验证见 6.3。

记账切片的 Chrome 三个回归使用预览 `http://127.0.0.1:5199` 与页面故障注入。基线会直接重发，修复前仍可在查无收据期间换令牌后重发，修复后三项均通过，覆盖单次收据、404 原样重放和令牌换代停止。Android `CashCreation*`、`SessionCacheRepositoryTest`、`LedgerReceiptMappingTest` 共 83 项通过。安装包在 SQLite 和专用 PostgreSQL 17.12 的同一子进程矩阵四项通过，覆盖服务端已拒绝而回包丢失、恢复原操作、无重发和精确金额。

认证与工作区切片先在 Android 复现原管理写入不带 `workspace_id`。迁移后同一测试核对三种写入的固定令牌和工作区。旧工作区及同工作区的旧使用者页面均不能发出邀请。现有 iOS 退出清理测试验证无效服务地址仍清除原令牌，异常会话两项测试继续证明未打开或清理缓存。Chrome 上 7 项真实页面冒烟通过，覆盖取消及确认移除、发送的工作区、邀请、只读角色及管理加载故障。已安装包在 SQLite 和 PostgreSQL 真实 HTTP 的 4 项通过。工作区与使用者的最后围栏另经 Android 和独立复核。

维护与 Worker 切片于 2026-11-26 通过受影响 Android 测试、`:shared:iosSimulatorArm64Test`、`:client-operations:jvmTest` 和 npm 安装后的 `node --test cli/test/{entry,offline,server}.test.cjs` 双库矩阵（4 项）。安装包的旧格式加密命令回归先失败于未请求原计划核实。修复后 SQLite 和 PostgreSQL 以原计划及原键取回同一收据。Chrome 158 在 390、768、1440 宽度验证月度视图，在 390 验证账本 Worker 和真实收支编辑。编辑回包丢失后，页面调用原计划核实，确认服务端只有一次写入并展示新的备注。用于真实编辑的脚本经同一已构建预览单独复跑通过。

导入执行切片于 2026-11-26 先在 Android 复现两种 GET 拒绝导致错误结果状态，包括紧凑降级期间的一次 POST、两次查询、禁止重算的回归。修复后 `./gradlew :shared:testAndroidHostTest --tests '*LocalCashImportRecoveryTest' :client-operations:jvmTest --offline` 和 `:shared:iosSimulatorArm64Test` 通过。真实 npm 安装包在 SQLite、专用 PostgreSQL 共 4 项通过。Chrome 158 使用 `http://127.0.0.1:5201` 执行首笔记账与收支编辑，以及导入的读取取消、离线确认、冲突重核、已提交后冷启动，共 5 项通过。运行采用 390 和 1440 宽度，写入带同一原始键，断网冷启动仍可展示结果。

账户写入切片于 2026-11-26 通过 `:shared:testAndroidHostTest` 的账户与会话回归、`:shared:iosSimulatorArm64Test`、共享 JVM 及安装包 SQLite／PostgreSQL 4 项。旧包会接纳缺少账户的收据，修复后保留原待核实命令并取回正确收据。两库验证同一批次两轮扩币与撤回、外部改动阻止补偿。Compose 查据 503 先失败后转绿。Chrome 在 `http://127.0.0.1:5205` 验证创建账户后继续记账、账户保存遇到中断时以同键重试。补币种与撤回的实际页面验证见后续覆盖。

最终工作差异以 `3ebf0c8f` 为基线，于 2026-11-26 验证。JDK 21、Node.js 26.8.1、Chrome 158.0.8127.37。完整 `./gradlew :backend:test --offline` 以专用 PostgreSQL 17.12 和 SQLite 执行，1,533 项通过，零跳过。

- `./gradlew :shared:testAndroidHostTest` 受影响筛选通过 265 项，覆盖手工与转账、SQLite 恢复记录、维护、账户、现金导入和会话。采用 `-Pkotlin.incremental=false --offline`，`:shared:iosSimulatorArm64Test` 的 1,021 项、`:client-operations:jvmTest` 和 `:cli:stageNpm` 通过。Web `npm run test:node` 通过 44 项。
- Chrome 在 `http://127.0.0.1:5207` 的 22 个已通过场景覆盖登录与邀请、成员和只读权限、收支汇总、Worker、真实编辑、分类及导入。账户撤回用例确认实际界面已打开后，改用渲染坐标避开既有弹层辅助语义残留。受影响流程在 `http://127.0.0.1:5209` 再通过 10 项，在 `http://127.0.0.1:5211` 通过真实补币种并撤回的用例。代表视口为 390、768、960、1100、1440 宽度。停用原账户后冷启动核实转账，返回同一收据和确切的 `-5.80`／`5.80` 两笔，未重发。导入覆盖文件读取、冲突复核及已提交后离线重新打开。浏览器预览产生既有构建体积提示。
- 在两个数据库上以 `node --test cli/test/entry.test.cjs cli/test/offline.test.cjs cli/test/server.test.cjs` 验证实际安装包，4 项通过，零跳过。包含原键收据、两个进程的账本缓存、现金导入、手工记账退出、转账双端对应余额、畸形账户权威证明、币种已满足时无账本修订，以及 `review --choices` 和 `map` 同范围资格。旧版安装包的现有币种写入和缺失账户选择均先失败，修复后两库通过。
- 完整 Android 另执行 1,143 项，其中 17 个历史合同因缺少 `FT_KOTLIN_CLIENT_CONTRACT_DIR`、`FT_KOTLIN_INVESTMENT_READ_CONTRACT_DIR` 以及当前后端兼容的 portfolio 夹具与宿主，在构造输入时失败，另有 10 项跳过。原 PR 基线记录了相同的 17 项缺口。补跑需在仓库外生成、核对当前 Ktor 真实响应并接入历史合同测试。受影响当前代码使用上述真实服务端及跨入口测试验证。原生设备交互、系统钥匙串实测及 Linux／Windows 安装未执行。

## 7. 发布

- [x] 7.1 核对功能地图、文档、历史兼容、观察项和用户决策的适用边界。
- [ ] 7.2 同步主规格并归档，准备目标为 `main` 的 PR #126 正文和验证证据。

用户确认的生产入口共享原则适用于本变更的一期现金业务，保留在对应规格。界面、终端、原生存储和调度继续适用各自边界。现有通用规范无需新增条款。文档已核对安装示例、现金账户目录、恢复入口和功能地图。回滚前在本版本核实已有的未决操作，旧版本照常读取原本机材料，新增恢复表由当前客户端读取。
