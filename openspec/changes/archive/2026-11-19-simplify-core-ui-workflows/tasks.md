## 1. 思考

- [x] 对照共享客户端、活动变更、词表和主规格，定位八项走查结果、对应失败路径和新增的首次工作区名称要求。涉及多任务、权限、现金恢复和双后端，按 A 类。用户确认本批排除投资持仓和找回密码。

## 2. 计划

- [x] 对齐提案、规格和设计。沿用现有页面和组件，确定各操作的正常、失败和恢复合同。
- [x] 复核边界、设计和全部 delta 一致性。

## 3. 任务拆分

- [x] 按受影响任务划分实现与验证。先锁定复现用例，再修复，逐步记录证据。

## 4. 构建

- [x] 先复现自动记账建账、成员确认、邀请保护、导入结果、筛选、关联及排序缺陷。
- [x] 实现和验证首次工作区名称的本地化、可编辑和保留输入。
- [x] 实现首次记账内新建账户、保留首次填写。
- [x] 实现成员权限确认、邀请并发保护、链接绑定角色与接受身份切换。
- [x] 实现导入收据待确认范围、自动更新及结果到列表入口。
- [x] 实现账本筛选和联合关联入口清理、同级分类排序和投资流水空态。

## 5. 审查

- [x] 独立完成产品、工程、最终 diff 和适用安全复核。按契约设计、UI 复用和 Hallmark 复核。
- [x] Web、Android 和 iOS 共用交互、状态和文案。验证邀请身份切换的路由和平台返回。服务端和本地账本共享合同按受影响范围验证。

工程、契约与 UI 独立复核覆盖账户、分类、邀请、导入与账本。改用明确的同级目标排序协议，保留工作区及修订保护，防止旧服务错执行相邻移动。收据按实际变化的稳定流水定位，支持当前条目重新分组，排除重复事实。提交原请求与后台收尾分开，账户重连保留原材料，已提交导入恢复需本机收据证明。修复冷启动误切邀请工作区、接受时失效、权限写入反馈、筛选空态及账户目录未就绪等路径。

Hallmark 审计覆盖本次表单、确认层和结果状态。复用 FinanceTheme、确认对话框、可滚动单选列表、筛选 Chip、状态消息、Snackbar 和进度控件。核对 Material 3 Dialogs、Chips、Progress indicators、Text fields、Radio buttons 正文。去除导入整理动作和不能提交的联合改选入口。布局和焦点沿用现有缩放与键盘路径。工作区默认名存在账户长名称降级到“我的工作区”的边界。

## 6. 测试与 QA

- [x] 受影响 Compose 目标编译、单测、真实 Web 冒烟与首次演示确认。最终重点复验受影响流程。
- [x] SQLite 与专用 PostgreSQL 集成验证排序和导入收据。
- [x] 完整回归、OpenSpec strict、doctor、diff 检查与当前证据。

验证基线为 `a44f3620`，在当前隔离分支执行。Chrome 访问本机 Compose Web QA 构建，使用 390、768 与 1440 px 代表宽度。临时 SQLite 与 PostgreSQL 仅用于虚构用户和账本。原生设备交互不在默认验证范围。

用户已确认首次工作区名称与首笔记账演示方向。验证记录于 2026-11-19。

- Chrome Playwright 通过中文和英文名称预填、用户编辑后提交、主动清空校验、首次记账内建账户并保存、成员确认、邀请权限与身份、投资筛选、分类一次排序，以及已保存导入故障后自动更新。分类结果以真实 Kotlin API 顺序校验。
- Gradle `:backend:test` 完成。通过 `:backend:test --tests '*CashImportWriteSetSubmissionTest*' --tests '*CategoryPositionContractTest*'` 额外确认 SQLite 和专用 PostgreSQL 的 134 个用例通过，无跳过。后端 codegen、ktlint 和 detekt 通过。
- Gradle `:business-contracts:jvmTest`、`:business-domain:jvmTest` 以及 `:shared:testAndroidHostTest` 的 1090 项共享回归通过。后者排除依赖独立历史客户端合同夹具的 `Kotlin*IntegrationTest`，当前未生成所需夹具。现金、邀请和排序的真实调用通过 Web 及双库补证。
- `:webApp:wasmJsBrowserDevelopmentWebpack`、`:androidApp:assembleDebug`、`:shared:compileKotlinIosSimulatorArm64` 及 Web Node 35 个测试通过。OpenSpec strict、doctor 和 diff 检查通过。

## 7. 发布

- [x] 核对功能地图、规格引用、用户决策沉淀和回滚边界。首次工作区名称是单项业务行为，加入工作区入口规格。其余遵循既有反馈、真实状态和技术恢复规则。无需新增通用规范。
- [x] 同步、归档、按既有授权提交和推送。
