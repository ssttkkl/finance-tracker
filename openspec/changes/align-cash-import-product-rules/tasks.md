## 1. 思考

- [x] 1.1 阅读产品／UI 规范、项目上下文、主规格和 active changes，使用 openspec-explore 与 grill-me → grilling 明确已确认规则。
- [x] 1.2 独立只读审查后端与共享 UI，按可达分支核实异常吞没、重复写入与反馈缺口。

## 2. 计划

- [x] 2.1 建立 A 类 proposal、完整 requirement delta 和 design，保留原三项 change。
- [x] 2.2 用户确认恢复账单原始备注并展示内容；“保留为空”明确授权长期保护，后续编辑可改变选择。
- [ ] 2.3 完成触及 UI 的官方组件核对与 Hallmark 预检记录。

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
- [ ] 4.6 相称编译及代表 Web 检查后立即提供最新演示，等待用户反馈。

4.1–4.5（含 4.4a）已在当次工作树实现；受影响回归与定向回归通过，四项阻断修复见第 6 阶段。4.6 待最终演示。

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

- [ ] 6.1 运行受影响 Python 回归、完整非性能回归、类型／构建检查及相称故障专项。

  已跑受影响回归（`tests/contract/test_cash_import_plan_api.py`、`test_cash_entry_editing.py`、`test_cash_statement_document.py`、`test_cash_import_session_api.py`、`test_parsed_cash_import_context.py`、`test_cash_import_multipart.py`）213 passed / 149 skipped，四项阻断定向 9 passed / 9 skipped。完整非性能回归与故障专项在用户要求下中止，交由 PR CI 执行。

- [ ] 6.2 人工准备 `_test` 专用 PostgreSQL 库并显式运行同一合同矩阵；未配置的 skip 不算通过。

  已在 `ft_cash_import_review_test`（`FT_TEST_POSTGRES_URL=postgresql+psycopg://huangwenlong@127.0.0.1:55432/ft_cash_import_review_test`）运行 6 个受影响契约文件：419 passed；其中 `postgres_cash_web_runtime` 13 项（2 failed + 11 errors）报 `relation "workspaces" does not exist`，单独重跑该组通过（30 passed），疑为会话内 PostgreSQL 夹具 schema 交互，需重跑主矩阵确认。未配置的 skip 不计通过。

- [ ] 6.3 共享 Kotlin 单测、Android/iOS 编译和 Wasm 构建，真实 Chrome 代表视口演示／关键失败分支。

  `compose` 下 `./gradlew :shared:testAndroidHostTest :shared:compileKotlinIosSimulatorArm64` BUILD SUCCESSFUL（相关任务 up-to-date，未强制重跑）。Wasm 构建与真实浏览器演示未重跑，随 PR CI 与后续演示执行。

- [x] 6.4 OpenSpec strict／doctor、术语与链接核对、git diff --check。

  `openspec validate --all --strict` 29 passed / 0 failed；`openspec doctor` ok；`git diff --check` 无问题；术语／链接按目标范围抽查，随 PR CI 复核。

跨端：共享状态、文案与组件影响 Web/Android/iOS，Web 用代表视口真实操作，Native 仅编译与单测；不扩展默认设备 QA。既有 `/tmp/ft-cash-import-qa-20261102/` 记录仅证明此前实现，不作为本次新改动通过的证据。

## 7. 发布

- [x] 7.1 按最终实现更新流程、协议参考、功能地图、词表及技能实施指引，准确登记残余缺口。
- [x] 7.2 完成审查与适用验证后按同分支授权提交、推送并提一个 PR 到 main，包含此前三项导入修复；排除 `.commandcode/`。不合并、不部署。

7.1：`docs/import/cash-import-flow.md`、`docs/import/cash-import-redesign-contracts.md`、`docs/feature-map.md`、`docs/import/feature-map.md`、`DOMAIN_GLOSSARY.md`、产品／UI 规范同组维护，残余缺口见第 6 阶段。7.2：本次按用户授权在当前分支提交、推送并创建合并 PR 到 `main`，不合并、不部署，等待 PR 检查。

回滚恢复本次代码与规格，不删除原提交恢复键或既有人工选择。最终交付前需更新实际 HEAD、时间、命令结果与当前风险。
