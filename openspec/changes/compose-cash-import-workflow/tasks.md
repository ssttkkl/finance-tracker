## 1. 思考

- [x] 1.1 通过 `grill-me → grilling` 明确实现文件 → 账户映射 → 草稿优先核对 → 一次确认 → 实际结果；导入核对只读。读取现金导入合同、账本展示合同、当前 change、项目 UI 规则和 Compose 实现。
- [x] 1.2 确认不改后端、DTO、Repository 公共合同、数据库、路由、账户创建命令和新建账户的退出补偿；在来源组下拉菜单内创建账户，并沿用旧 Web 名称类型推断。
- [x] 1.3 Cross-platform Impact Check：`commonMain` 的页面结构、文案、状态、确认与错误语义影响 Web、Android、iOS。文件选择控件保留平台适配；Native 仅执行编译与单元测试，不宣称设备 QA。

## 2. 计划

- [x] 2.1 更新 proposal、design 与两个 delta，记录全屏对话框、三步信息结构、文件解析状态、账户预填回选、只读核对与收据结果。
- [x] 2.2 记录已删除的冗余标题、账户编辑入口和导入内编辑能力；保留 Compose FinanceTheme、现有 M3 组件和项目 token。
- [x] 2.3 保留 `average-snail` 新增的安全诊断展示合同；不把目标分支旧核对编辑器及其 UI 动作带回只读导入。

## 3. 任务拆分与一致性

- [x] 3.1 对齐账户创建、文件状态、步骤导航、默认全部与草稿优先、只读详情、实际收据和退出恢复场景。
- [x] 3.2 Web 回归覆盖文件状态、账户创建和回选、确认范围、结果收据及错误诊断；断言核对详情不出现输入和编辑操作。

## 4. 构建

- [x] 4.1 将账单导入改为账本上的 Material 3 全屏对话框；保留后台账本、会话恢复、关闭保护和结果返回。步骤统一为“上一步／下一步”，每步只保留简短业务说明。
- [x] 4.2 分离文件选择和来源账户映射；文件上传期间显示行内假进度与 loading，移除立即生效；文件解析完成前不能进入映射。
- [x] 4.3 每个来源账户下拉框提供“＋新建账户”。预填来源名称和账单币种；名称包含“花呗”或“信用卡”时创建为 `loan`，其余为 `cash`；保存后返回并选中，缺失币种只显示提示。历史推断位于 `web/src/pages/CashImportPage.tsx`。
- [x] 4.4 核对默认查看全部，已加载草稿排前；筛选只改变查看范围。详情保持只读，整份计划一次确认，实际结果读取收据。
- [x] 4.5 保留 `average-snail` 的 `CashImportDiagnostic` 只读诊断、账户和文件错误状态；不新增行级恢复操作。

## 5. 审查

- [ ] 5.1 用户反馈后的最终产品、工程、失败／恢复与差异复核；当前第一版已进行合并范围和主流程检查，完整独立复核留待确认后的最终迭代。
- [ ] 5.2 用户确认方向后的 Hallmark audit 与独立设计 QA；首轮截图人工检查未发现阻止演示的问题，不据此宣称最终设计 QA 通过。

## 6. 测试与 QA

- [x] 6.1 运行 `:shared:wasmJsTest`、`:shared:testAndroidHostTest`、`:shared:iosSimulatorArm64Test`、`:shared:compileAndroidMain`、`:shared:compileKotlinIosSimulatorArm64`；全部通过。未运行设备／模拟器 QA。
- [x] 6.2 `npm run test:node` 3 项通过；真实 Chromium 针对账户新建、默认全部与收据、单条诊断、多条相关诊断运行 4 项，全部通过。
- [x] 6.3 真实 Chromium 从账本打开导入，走文件、账户映射与核对步骤。1440×900 检查桌面三步与文件解析进度，390×844 检查窄屏文件进度和核对；390 px 下 `scrollWidth=390`，没有横向溢出。1440 px 进度轨道实测为 x=336–1104（768 dp），文件行内容列 x=360–1080（720 dp）。页面异常 0；当前构建仅有 Wasm memory 弃用控制台提示。
- [x] 6.4 无存储行为变化，双后端、性能和安全专项不适用；跨端共享编译／测试已执行，未运行 Native 设备 QA。
- [ ] 6.5 第一版 Compose 演示已启动，等待用户确认；确认后按反馈重启最新演示并执行最终全量审查。此检查点不代表 change 完成或可归档。

## 7. 发布

- [ ] 7.1 用户确认方向后的文档收尾：复核 README、导入流程、功能地图和词表；本轮已更新功能地图、词表及工程约束。
- [x] 7.2 已按用户明确授权合并到 `average-snail`；本地合并提交，不推送、不部署。

## 8. 反思

- [ ] 8.1 用户确认和最终 QA 后记录防复发回归及未解决合同缺口；同步最终规格，不提前归档。

## 第一版演示检查点

按用户要求，完成 Compose 第一版、跨端编译和代表 Web 视觉校验后立即开放演示，不为非阻断的细节修整延迟确认。目标分支新增的只读导入诊断已保留；其旧编辑器行为因与已确认的只读范围冲突而不恢复。

仍未宣称完成的事项：完整 E2E 回归、所有详情／关系组合的演示验收、CMP-10623 导致嵌套账户表单关闭后的语义镜像刷新，以及用户确认后的独立设计 QA。账户表单创建 E2E 目前在表单保存返回后使用页面实际按钮位置继续，键盘／读屏行为需在最终迭代复核。

## 当前审查与验证

验证时间：2026-10-27 02:03 CST。合并候选以 `HEAD=0e36af1b25941ee47d1b8cbb228ac702f9e7e6a1`（`average-snail`）为基线，合入 `MERGE_HEAD=477f6d001af72e38e07aa73af0df874cb78cc869`（`infamous-seahorse`）；比较基线 `fc546128f234ceb00ce8e7b390372b3d0803c29c`。用户明确授权合入目标分支。本轮没有推送或部署。

| 检查 | 命令／范围 | 结果 |
| --- | --- | --- |
| 共享 Compose 编译与单测 | 在 `compose/` 执行 `FT_COMPOSE_DEMO_MODE=true FT_API_ORIGIN='' ./gradlew :shared:wasmJsTest :shared:testAndroidHostTest :shared:iosSimulatorArm64Test :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64 --no-configuration-cache --console=plain` | BUILD SUCCESSFUL；Wasm、Android Host、iOS Simulator 单测及 Android／iOS 共享代码编译通过 |
| OpenSpec 与 Node 工具 | `openspec validate compose-cash-import-workflow --strict`；在 `compose/qa/web/` 执行 `npm run test:node` | change 有效；Node 3 项通过 |
| Chromium 流程 | 在 `compose/qa/web/` 执行 `FT_COMPOSE_PREVIEW_PORT=5227 npm run test:e2e -- --grep "creating an account from its mapping menu|cash import reviews all items|import diagnostics show" --reporter=line` | 4 项通过。覆盖信用卡名称推断、CNY/USD 预填、创建后回选、预览账户选择、整份确认与实际收据、单条及多条错误定位；错误诊断测试的预期提交拒绝除外无控制台／网络错误 |
| 离线 Compose 演示构建 | `FT_COMPOSE_PREVIEW_PORT=5233 npm run demo:offline` | `:webApp:wasmJsBrowserDistribution` BUILD SUCCESSFUL；仅合成内存数据，未连接真实 API |
| Web 视觉复核 | Chromium 1440×900 与 390×844；从账本入口打开导入，添加内置演示账单，查看映射与核对；访问 `192.168.1.12:5234` 返回 200 | 全屏对话框、步骤文案、操作图标、账户选择与核对内容可见；手机文件进度轨道贴满视口，页面无横向溢出。截图保存在 `/tmp/average-snail-import-{fullscreen-overlay,files-added-1440,mapping-1440,review-1440,progress-1440,files-390,review-390}.png`；账户预填及回选截图为 `/tmp/cash-import-account-form-prefill.png`、`/tmp/cash-import-account-after-save.png`。演示页面控制台有一条既有 Wasm memory 弃用提示，无页面脚本异常 |
| 差异格式检查 | `git diff --cached --check` | 通过；文档回写后再次运行 |

Cross-platform Impact Check：Web、Android、iOS 共用 UI 状态、文案、步骤、账户映射、确认及错误语义；Web 已执行真实浏览器主流程，Android／iOS 执行编译和单元测试，未执行设备 QA。双后端及存储专项不适用。最终 E2E 回归、用户确认后的独立设计 QA 和文档收尾仍待完成。
