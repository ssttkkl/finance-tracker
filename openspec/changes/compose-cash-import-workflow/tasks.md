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
- [x] 3.2 Web 回归覆盖文件状态、账户创建和回选、确认范围、结果收据及错误诊断；断言前两步不显示副标题，核对步骤保留说明，核对详情不出现输入和编辑操作。
- [x] 3.3 明确记录状态呈现：草稿与 `action=defer` 关系候选显示状态标签，普通已确认记录不显示状态标签；筛选及详情统一使用“关系待确认”。
- [x] 3.4 明确备注字段合同：账单业务备注独立于关系说明和核对原因，后两者不得覆盖或拼入备注。

## 4. 构建

- [x] 4.1 将账单导入改为账本上的 Material 3 全屏对话框；保留后台账本、会话恢复、关闭保护和结果返回。步骤统一为“上一步／下一步”；前两步不显示副标题，核对步骤保留简短业务说明。
- [x] 4.2 分离文件选择和来源账户映射；文件上传期间显示行内假进度与 loading，移除立即生效；文件解析完成前不能进入映射。
- [x] 4.3 每个来源账户下拉框提供“＋新建账户”。预填来源名称和账单币种；名称包含“花呗”或“信用卡”时创建为 `loan`，其余为 `cash`；保存后返回并选中，缺失币种只显示提示。历史推断位于 `web/src/pages/CashImportPage.tsx`。
- [x] 4.4 核对默认查看全部，已加载草稿排前；筛选只改变查看范围。详情保持只读，整份计划一次确认，实际结果读取收据。
- [x] 4.5 保留 `average-snail` 的 `CashImportDiagnostic` 只读诊断、账户和文件错误状态；不新增行级恢复操作。
- [x] 4.6 在导入核对列表为草稿和待确认关系增加醒目标签与行底色，删除正式／已确认标签及“手动配对”称呼；普通账本行保持原有状态呈现。关系状态回归覆盖 390 px 与 1440 px。
- [x] 4.7 核对行原样显示账单业务备注，并以独立“关联”和“需核对”说明展示关系摘要与核对原因；桌面断言各文本节点，窄屏断言同一可访问行完整包含三项。

## 5. 审查

- [ ] 5.1 用户反馈后的最终产品、工程、失败／恢复与差异复核；当前第一版已进行合并范围和主流程检查，完整独立复核留待确认后的最终迭代。
- [ ] 5.2 用户确认方向后的 Hallmark audit 与独立设计 QA；首轮截图人工检查未发现阻止演示的问题，不据此宣称最终设计 QA 通过。

## 6. 测试与 QA

- [x] 6.1 运行 `:shared:wasmJsTest`、`:shared:testAndroidHostTest`、`:shared:iosSimulatorArm64Test`、`:shared:compileAndroidMain`、`:shared:compileKotlinIosSimulatorArm64`；全部通过。未运行设备／模拟器 QA。
- [x] 6.2 `npm run test:node` 3 项通过；真实 Chromium 针对账户新建、默认全部与收据、单条诊断、多条相关诊断运行 4 项，全部通过。
- [x] 6.3 真实 Chromium 从账本打开导入，走文件、账户映射与核对步骤。1440×900 检查桌面三步与文件解析进度，390×844 检查窄屏文件进度和核对；390 px 下 `scrollWidth=390`，没有横向溢出。1440 px 进度轨道实测为 x=336–1104（768 dp），文件行内容列 x=360–1080（720 dp）。页面异常 0；当前构建仅有 Wasm memory 弃用控制台提示。
- [x] 6.4 无存储行为变化，双后端、性能和安全专项不适用；跨端共享编译／测试已执行，未运行 Native 设备 QA。
- [ ] 6.5 当前反馈版 Compose 演示已按修改后重新构建并启动，等待用户确认；后续每条反馈继续按相同流程校验和重启。此检查点不代表 change 完成或可归档。
- [x] 6.6 Chromium 回归检查普通已确认记录无标签、草稿与待确认关系标签及底色、关系筛选与窄屏排版；390 px、1440 px 各 1 项通过。
- [x] 6.7 Chromium 回归断言流水业务备注、关联摘要和核对原因分别呈现，桌面与窄屏各 1 项通过；修复前两视口均因缺少业务备注节点而失败。

## 7. 发布

- [ ] 7.1 用户确认方向后的文档收尾：复核 README、导入流程、功能地图和词表；本轮已更新功能地图、词表及工程约束。
- [x] 7.2 已按用户明确授权合并到 `average-snail`；本地合并提交，不推送、不部署。

## 8. 反思

- [ ] 8.1 用户确认和最终 QA 后记录防复发回归及未解决合同缺口；同步最终规格，不提前归档。

## 当前演示检查点

按用户要求，每轮反馈修改后完成相称编译和 Web 视觉校验，再重启演示供确认。本轮将账单备注、关联摘要和核对原因分开显示，状态标签与行底色保持不变。最新版离线演示地址：`http://192.168.1.12:5234/w/demo-workspace/cash-import`。

仍待确认或复核：完整 E2E 回归、所有详情／关系组合的演示验收、CMP-10623 导致嵌套账户表单关闭后的语义镜像刷新，以及用户确认后的独立设计 QA。账户表单创建 E2E 在表单保存返回后使用页面实际按钮位置继续，键盘／读屏行为需在最终迭代复核。

## 当前审查与验证

验证时间：2026-10-27 11:11 CST。当前 `HEAD=983a68a6110abae39b351e89efa7937ccbdcda99`（本地 `average-snail` 合并提交）；本轮 UI 修改尚未提交。未推送或部署。

| 检查 | 命令／范围 | 结果 |
| --- | --- | --- |
| 共享 Compose 编译 | `FT_COMPOSE_DEMO_MODE=true FT_API_ORIGIN='' ./gradlew :shared:compileKotlinWasmJs :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64 --no-configuration-cache --console=plain` | BUILD SUCCESSFUL；当前 UI 变化通过 Web、Android、iOS 共享代码编译；输出为仓库既有弃用及 Wasm interop 警告 |
| 前序跨端单测 | `:shared:wasmJsTest`、`:shared:testAndroidHostTest`、`:shared:iosSimulatorArm64Test`（本轮文案变更前） | 全部通过；本轮只改 UI 文案显示与资源，重跑了受影响的共享编译及 Web 流程 |
| 当前 Chromium 流程 | 在 `compose/qa/web/` 执行 `FT_COMPOSE_PREVIEW_PORT=5227 npm run test:e2e -- --grep "cash import reviews all items with drafts first in the main task and shows only the actual result summary" --reporter=line` | 1 项通过；检查三个步骤的说明、草稿优先列表、筛选与实际收据，无页面脚本或网络错误 |
| 关系与备注字段回归 | 在 `compose/qa/web/` 执行 `FT_COMPOSE_PREVIEW_PORT=5227 npm run test:e2e -- --grep "relationship-pending records get a vivid tag" --reporter=line` | 修复前 390 px、1440 px 均失败：行备注节点未呈现。修复后两项通过；逐项检查账单备注“待核对备注”、关联说明“同笔支付 · 正常商户”、核对原因“缺少业务字段”彼此独立。截图 `compose/qa/web/test-results/compose-cash-import.e2e.ts-94f40-d-records-stay-untagged-390/import-status-tags-390.png`、`compose/qa/web/test-results/compose-cash-import.e2e.ts-b635d--records-stay-untagged-1440/import-status-tags-1440.png` |
| 当前离线演示构建 | `FT_COMPOSE_PREVIEW_PORT=5233 npm run demo:offline` | `:webApp:wasmJsBrowserDistribution` BUILD SUCCESSFUL；进程监听 `127.0.0.1:5233`，外部 HTTP 转发监听 `0.0.0.0:5234`；演示仅使用合成内存数据，未连接真实 API |
| 当前 Web 视觉复核 | 真实 Chromium，1440×900 与 390×900；桌面检查文件及来源账户映射，手机走通文件、映射和核对 | 两种宽度均确认前两步副标题不存在、核对说明存在；无横向溢出、页面脚本错误为 0。截图：`/tmp/average-snail-import-files-1440.png`、`/tmp/average-snail-import-accounts-1440.png`、`/tmp/average-snail-import-files-390.png`、`/tmp/average-snail-import-accounts-390.png`、`/tmp/average-snail-import-review-390.png`。内网地址 `http://192.168.1.12:5234/w/demo-workspace/cash-import` 返回 HTTP 200 |
| 当前演示入口冒烟 | 真实 Chromium，1440×900 打开 `/w/demo-workspace/cash-import`；`curl` 检查本地和内网代理 | 页面显示中文文件步骤，无页面异常、无横向溢出；`127.0.0.1:5233/` 与 `192.168.1.12:5234/w/demo-workspace/cash-import` 返回 HTTP 200。Playwright headless 未触发系统文件选择器，文件选择与完整主流程由上方 E2E 浏览器回归覆盖 |
| OpenSpec 与差异格式 | `openspec validate compose-cash-import-workflow --strict`；`git diff --check` | 均通过 |

Cross-platform Impact Check：Web、Android、iOS 共用备注、关联、核对原因的显示字段和文案，Wasm、Android Main、iOS Simulator Arm64 共享代码编译通过；Web 执行真实浏览器检查，Native 未执行设备 QA。双后端及存储专项不适用。完整 E2E 回归、用户确认后的独立设计 QA 和文档收尾仍待完成。

本轮按 Hallmark `audit` 规则复核核对行和 390×900、1440×900 截图：备注明确保留账单内容，关系和核对原因分别标明；状态强调继续由现有标签与行底色承载，未额外重复状态文案。复用 FinanceTheme、Material 3 `bodySmall`、`onSurfaceVariant` 与既有布局；无 critical、major 或 minor finding。Hallmark audit 没有专用运行工具，以上为按项目 audit 参考执行的人工审查。
