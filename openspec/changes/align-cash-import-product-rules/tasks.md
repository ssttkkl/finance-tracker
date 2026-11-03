## 1. 思考

- [x] 1.1 阅读产品／UI 规范、项目上下文、主规格和 active changes，使用 openspec-explore 与 grill-me → grilling 明确已确认规则。
- [x] 1.2 独立只读审查后端与共享 UI，按可达分支核实异常吞没、重复写入与反馈缺口。

## 2. 计划

- [x] 2.1 建立 A 类 proposal、完整 requirement delta 和 design，保留原三项 change。
- [x] 2.2 用户确认恢复账单原始备注并展示内容；“保留为空”明确授权长期保护，后续编辑可改变选择。
- [x] 2.3 完成触及 UI 的官方组件核对与 Hallmark 预检记录。

  Hallmark 设计方向预检记入 `design.md`。官方组件核验 2026-11-03 用无头 Chrome 读取 M3 官网渲染正文完成，页面与采用规则：`Dialogs`（多步骤任务用 full-screen、标题区仅关闭 X、未保存关闭先用基本对话框确认、确认动作写明结果）、`Snackbar`（单动作、带动作保持到用户处理、一次一条）、`Buttons`（次要动作用 text button）、`Progress indicators`（时长不确定用不定进度）。偏差（full-screen 用于非 compact 断点）与理由同记 `design.md` 的 UI 策略段。

## 3. 任务拆分

- [x] 3.1 后端输入／程序错误边界与客户端原命令身份分别安排失败回归。
- [x] 3.2 备注按公共对象展示来源返回原文值，安排清空确认、同值幂等与双后端回归。

## 4. 构建

- [x] 4.1 先故障注入解析器、XLSX／转换器、资金扫描、R2 和 HTTP，确认失败回归，再收窄异常处理。
- [x] 4.2 先复现账户未知币种更新和选择刷新问题，修复未决目标保护、读取回填和原请求重试入口。
- [x] 4.3 保留客户端原异常／诊断，修复队列、分页、恢复保存和账户循环发生内部错误后继续的路径。
- [x] 4.4 修复伪进度、空密码请求、退出确认、继续编辑、相关反馈和可访问性。
- [x] 4.4a 落实 Snackbar 选型定向复核：选择器失败、忙碌退出、账户选择临时读取失败替换原提示，超限追加补批次通知；验证不积压、不覆盖旧选择和子表单可见。
- [x] 4.5 备注决定明确后以失败回归实现弹窗及幂等，不迁移旧标记、不改变分类空值规则。
- [x] 4.6 相称编译及代表 Web 检查后立即提供最新演示，等待用户反馈。

4.1–4.5（含 4.4a）已在当次工作树实现；受影响回归与定向回归通过，四项阻断修复见第 6 阶段。4.6：离线 Compose Web 演示 `http://127.0.0.1:5186/`（`start-compose-offline-demo.mjs`，`fd50da05` 构建）经用户确认。

## 5. 审查

- [x] 5.1 独立产品／工程／安全和最终 diff 审查，按发现修复后复审。
- [x] 5.2 调用 codebase-degradation-review 调度契约／UI 复用审查；Hallmark audit 与独立设计复核见 6.3。

5b 基线 `4bb005c2`（比较 `3b55d704`），由独立只读上下文分别执行契约设计评审与 UI 复用评审（读 `.agents/skills/contract-design-review`、`.agents/skills/ui-reuse-review`）。

契约设计评审（`src/` diff + Kotlin／协议／契约测试）：确认 `original_note`／`original_note_token` 绑定展示来源及其修订、add 修订下界未外溢、`raise failure from error` 保留原因链均合理并保留。
- 高：路由级 500/503 分类与 `app.py` 全局 503 对同类 `DBAPIError` 语义分叉（`cash_import_routes.py:53-72` vs `app.py:350-361`）。处置：属跨路由错误契约统一，超出本 change，记为待办小 change，不在本次展开。
- 中：文件名原样展示后，用户写进文件名的密码值可能进入会话／响应（`cash_import_context.py:72-74`）。处置：本次已由用户确认按原样展示，记为已接受残余风险，不改。
- 中：`restore_note_token` 声明可空但 `_input` 拒绝显式 null（`cash_mutation_plans.py:80`）。已修：显式 null 视为未携带，补 `test_restore_note_token_explicit_null_is_treated_as_absent`。
- 中：无外键保证的 root 流水缺失时只读详情整体 422（`cash_entries.py:112` 抛 `entry_changed`）。已修：`original_note` 无来源返回 `("", None)`，只读展示为空、仅提交才校验，补 `test_missing_root_source_reads_as_empty_original_note`。
- 低：`plan_build_failed`（500 不可重试）文案仍引导重试（`cash_plan_routes.py:40`）。已修文案。
- 低：错误码集合未声明为契约（code 即契约）。处置：记为待办，本次保持两端映射同步。

UI 复用评审（`compose/shared` diff）：确认 Snackbar host 跟随可见容器、`ImportTaskNotice` 首次出现不下沉、沿用既有文案键均合理。
- 高：阻塞失败屏重复实现既有 `StateMessage`（`CashImportTaskScreen.kt:334-337`）。已修：改用 `StateMessage`，保留 `cash.import.blocked` testTag。
- 高：单选行／单选弹窗第 5 次重复（`AssociationRadio` 为 private）。处置：与 UI 规范 §2.9「不新增控件包装」权衡，本次不抽公共组件，记为待办并在下次出现时下沉。
- 中：灰色可点击按钮配色两处写法不同（`CashImportTaskScreen.kt:420`、`CashImportAccountForm.kt:56`）。已修：统一为 `ButtonDefaults.buttonColors(...)`，不新增包装。
- 中：`import_payload_invalid` 与程序错误共用「系统错误」文案，丢失“返回后重试”指引。已修：新增 `cash_import_payload_invalid`（4 份 locale）并映射。
- 中：可重试码集合第三次硬编码。已修：抽出 `cashImportRecoverableCode`。
- 低：`CashImportAccountForm` 参数膨胀、notice stringly-typed。处置：记为观察项。

Hallmark audit 与独立设计复核见 6.3。

审查基线：`3b55d704`，比较当前工作树与 HEAD；初次只读审查未执行新增 QA。后端主要发现：_parse 与 XLSX 任意捕获、行转换宽捕获、资金 ValueError/ArithmeticError 跳过、R2 任意错误当不可用、内部计划错误及 HTTP 503 归因。客户端主要发现：原请求重试隐藏、ensureCurrencies 新键重复、读取失败选旧缓存、刷新覆写本次选择、内部错误失去原因并继续队列／账户、分页合同错误普通重试、恢复保存吞没。UI 还包括伪进度、空密码请求、继续编辑关闭表单、退出多确认、活动按钮对比度及完整文件名可读性。处理位置为第 4 阶段对应任务，均未宣称修复或通过。

## 6. 测试与 QA

Snackbar 定向审查由独立 `ui_final_review` 完成，范围涵盖文件选择、容量、忙碌退出、账户选择读取和移除失败。前四项需要短反馈；文件移除的未执行证明不足，保留刷新保护。静态代码已替换选择器和忙碌弹窗、增加容量通知，并通过本次读取回调避免采用缓存账户；最终 Web 验证进行中。选择器原生错误回调已改接 Snackbar，但向浏览器 `HTMLInputElement.click` 注入任意 JS 异常会触发 FileKit/Wasm 协程内部错误而不经过其错误回调，该注入不作为本层已通过证据，不扩展为第三方框架改造。

后端独立复核的四项阻断已在当前工作树修复并各有失败回归，待独立复审确认：

1. 坏 XLSX 的包/单元格错误归因：`tests/test_cash_statement_document.py::test_missing_workbook_package_parts_are_input_errors`、`::test_invalid_raw_workbook_cell_is_an_input_error`；内部 `AssertionError` 仍原样传播（`::test_workbook_internal_failure_is_not_a_file_format_error`）。
2. 原始备注绑定展示来源及其修订：`tests/contract/test_cash_entry_editing.py::test_public_original_note_comes_from_display_source_not_opened_member`、`::test_restore_note_rechecks_source_of_another_member`（预览与提交均复验来源，`original_note_token` 绑定来源 id、来源修订与备注值）。
3. 真实数据库连接故障判 503：`_storage_unavailable` 对指向 port 1 的真实 PG `OperationalError` 返回真、对程序性 `DBAPIError` 返回假（探针实测），schema/syntax/Integrity 仍 500。
4. 同值补丁在规划边界归一化且显式覆盖与预览一致：`::test_reimport_explicit_note_matches_preview_after_commit`、`::test_same_value_public_save_does_not_create_hidden_protection`、`::test_draft_same_note_protection_depends_on_actual_source_change`。

定向回归 9 passed / 9 skipped；相关契约文件合计 213 passed / 149 skipped（`tests/contract/test_cash_import_plan_api.py` 等）。该只读复核目标回归为 308 passed / 116 skipped，未配置 PostgreSQL 不计通过。四项修复尚未经独立复审，未据此宣称第 4 阶段完成。

- [x] 6.1 运行受影响 Python 回归、完整非性能回归、类型／构建检查及相称故障专项。

  受影响回归 213 passed / 149 skipped，四项阻断定向 9 passed / 9 skipped（另行独立复审四项均确认为真实修复）。完整非性能回归、类型与构建由 PR CI 执行并通过：run 41489012978 的 SQLite 8 分片、PostgreSQL 8 分片与 Compose job 全绿（`fd50da05` + 评审修复）。故障专项（坏 XLSX 包/单元格、真实 PG 连接故障、显式同值覆盖）由定向回归与独立探针覆盖。

- [x] 6.2 人工准备 `_test` 专用 PostgreSQL 库并显式运行同一合同矩阵；未配置的 skip 不算通过。

  已在 `ft_cash_import_review_test`（`FT_TEST_POSTGRES_URL=postgresql+psycopg://huangwenlong@127.0.0.1:55432/ft_cash_import_review_test`）运行 6 个受影响契约文件：419 passed；`postgres_cash_web_runtime` 13 项在同会话多文件运行时报 `relation "workspaces" does not exist`，单独重跑该组通过（30 passed），属夹具 schema 交互、非代码缺陷。同一合同矩阵在 CI 的真实 PostgreSQL 分片（8/8）全绿，作为通过证据；本地异常组按夹具问题记录。

- [x] 6.5 性能门禁按 runner 重标预算。

  两次 CI 运行（`41489012978` 首跑与重跑）各 3 例失败但失败项不同：类别删除 504／502 ms（预算 500 ms，0.4–0.8%）、导入来源更新 18.3／17.5 s（预算 15 s）、投影或用户工作区 p95 轮换失败。本机同一套用例全绿且有余量：导入来源更新 8.45 s（预算 15 s）、提交 1.77 s、预览 0.24 s，类别删除 10 passed。失败路径含本次未改动的类别删除与只读 p95，幅度在噪声量级，判定为预算按更快的机器标定、托管 ARM runner 无余量，而非本 change 的代码回归。经用户确认按 CI 实测重标：`P95_CATEGORY_DELETE_NS` 500→800 ms、`IMPORT_COMMIT_BUDGET_NS` 15→25 s、`READ_P95_BUDGET_NS` 100→200 ms、`ACCESS_P95_BUDGET_NS` 250→500 ms，并在常量处记录标定依据；上限仍用于拦截数量级回归，runner 或数据规模变化时需重新标定。重标后未再取得一轮 CI 结果即交付，该门禁的最新结论需看推送后的运行。

- [x] 6.3 共享 Kotlin 单测、Android/iOS 编译和 Wasm 构建，真实 Chrome 代表视口演示／关键失败分支。

  离线 Compose Web 演示经 `:webApp:wasmJsBrowserDistribution` production 构建成功（`fd50da05` 后按最新工作树重建）；`./gradlew :shared:testAndroidHostTest :shared:compileKotlinIosSimulatorArm64` BUILD SUCCESSFUL。演示经用户确认。

  实机浏览器冒烟（Playwright 驱动系统 Chrome，无头；生产 wasm `http://127.0.0.1:5186/cash-import`，zh-CN／浅色）：390×844 与 1280×900 各走通「首屏 → 添加文件 → 账户映射 → 预览确认」，无水平溢出、无 4xx/5xx、控制台仅一条 Kotlin/Wasm `memory` 弃用告警；截图 `/private/tmp/align-audit/{m390,d1280}-{1-initial,2-files,3-mapping,4-review}.png`。

  夹具 e2e（`compose-cash-import.e2e.ts`，真实 Chrome + API 路由夹具）：`41 passed · 0 failed`。基线 HEAD `fd50da05`（暂存本轮 UI 改动后重跑同一文件）为 `6 failed`；本轮修复的 5 例为权威未提交拒绝只保留返回、诊断只读区按钮数量、桌面宽度用例按阻断规则拆分、空密码不发请求后补密码的重试用例、F-03 阻塞文案回归。`account selection read failure` 一例按 CMP-10623（弹层关闭后镜像语义树停留旧节点）改为：截图记录 Snackbar 与保留选择、断言不触发预览，读取失败回调由 `CashImportTaskViewModelTest` 覆盖；截图中 Snackbar 文案为“暂时无法读取账户。”并带“重试”。

  设计门禁：渲染页审查由独立视觉上下文在真实 Chrome 截图上执行多轮（read_image + 像素采样），未运行 `$hallmark audit` 命令行，按其渲染检查项替代。
  - 第 1 轮 `0 critical · 7 major · 7 minor`；第 2 轮复核 5 项解决、2 项部分（步骤条与正文不同轴、待确认行无可见补齐入口）；第 3 轮复审 `0 critical · 0 new major · 2 major 残留`；第 4 轮（步骤条去掉多余内边距与正文同轴、桌面行补“补齐信息”）复审 `0 critical · 0 major · 0 new minor`，通过设计 QA。
  - 已修 major：移动端核对行无字段名（加发生时间／账户／分类／流水类型，账户与分类拆行）；月汇总“无收支”与“金额未知”冲突（无已定价收支时不再显示“无收支”）；文件行名称与移除控件错位、且移除仅图标（同行垂直居中 + “✕ 移除文件”可见文字与无障碍名）；活动描边控件 1.27:1（新增 `financeInteractiveBorder`，`outline` 实测 rgb(113,113,122)、约 4.6:1，覆盖 `OutlinedButton` 与未选中 `FilterChip`）；桌面第 3 步与步骤条不同轴（核对步骤的步骤条、说明与列表共用 24 px 左边界）；金额未知用主色加粗且无补齐出口（中性色 + “补齐信息”文字动作）。
  - 记录未修 minor：分组标题 24／40 两档左边界、状态徽标占用流水类型列、桌面首屏中部空白、重复文案与句末标点不一致、分组标题与字段值同名重复。
  未覆盖（残余风险）：动效、读屏顺序、键盘焦点环与焦点恢复、深色主题与其他窗口宽度、原生端交互。

- [x] 6.4 OpenSpec strict／doctor、术语与链接核对、git diff --check。

  `openspec validate --all --strict` 29 passed / 0 failed；`openspec doctor` ok；`git diff --check` 无问题；术语／链接按目标范围抽查，随 PR CI 复核。

跨端：共享状态、文案与组件影响 Web/Android/iOS，Web 用代表视口真实操作，Native 仅编译与单测；不扩展默认设备 QA。既有 `/tmp/ft-cash-import-qa-20261102/` 记录仅证明此前实现，不作为本次新改动通过的证据。

## 7. 发布

- [x] 7.1 按最终实现更新流程、协议参考、功能地图、词表及技能实施指引，准确登记残余缺口。
- [x] 7.2 完成审查与适用验证后按同分支授权提交、推送并提一个 PR 到 main，包含此前三项导入修复；排除 `.commandcode/`。不合并、不部署。

7.1：`docs/import/cash-import-flow.md`、`docs/import/cash-import-redesign-contracts.md`、`docs/feature-map.md`、`docs/import/feature-map.md`、`DOMAIN_GLOSSARY.md`、产品／UI 规范同组维护，残余缺口见第 6 阶段。7.2：本次按用户授权在当前分支提交、推送并创建合并 PR 到 `main`，不合并、不部署，等待 PR 检查。

回滚恢复本次代码与规格，不删除原提交恢复键或既有人工选择。最终交付前需更新实际 HEAD、时间、命令结果与当前风险。
