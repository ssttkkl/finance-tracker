# 现金导入并发追加合并

## 1. 思考

- [x] 1.1 阅读 `openspec/project-context.md`、导入主规格、活动 change、`DOMAIN_GLOSSARY.md`，以及 `cash_import_context.py`、`parsed_import_staging.py`、客户端导入任务状态与测试。
- [x] 1.2 根因确认：客户端 `canEdit` 在 busy 时为假导致按钮禁用；服务端 `add_files` 要求修订完全相等，add-add 并发必然产生 `session_changed`。
- [x] 1.3 与用户确认 A 类范围：服务端合并、客户端 3 并发、任意第一步状态可追加、失败隔离、按最高 revision 合并。

## 2. 计划

- [x] 2.1 产出 `proposal.md`、`design.md` 与两个能力 delta。
- [x] 2.2 明确事务边界：解析在事务外，合并与 CAS 在短事务内；SQLite/PostgreSQL 同一 Application Service；无 schema 迁移；回滚只涉及服务端与客户端逻辑。

## 3. 任务拆分

- [x] 3.1 后端失败测试先行：并发追加、旧修订、未来修订、幂等、摘要冲突、上限、关闭/提交、其他操作严格。
- [x] 3.2 客户端失败测试先行：按文件队列、3 并发、首会话串行、失败隔离、乱序合并、上限禁用。
- [x] 3.3 UI 测试：上传中“添加文件”可点击，达到上限时禁用。

## 4. 构建

- [x] 4.1 后端：为 `add_files` 合并语义编写失败合同测试。
- [x] 4.2 后端：实现 `add_files` 有界 CAS 合并循环与幂等处理；保持其他文件操作严格校验。
- [x] 4.3 客户端：为上传队列与状态合并编写失败 ViewModel 测试。
- [x] 4.4 客户端：实现按文件队列、3 并发、首会话串行、失败隔离、最高 revision 合并。
- [x] 4.5 UI：解耦 adding 与 busy，按已上传 + 待上传计算 20 份上限，保留待上传源文件。
- [x] 4.6 文档：更新导入契约、流程与功能地图中的并发导入说明。
- [x] 4.7 按演示反馈补本地/网络失败文件重试及删除，失败文件阻止下一步；先验证失败回归，再修复并复核。

## 5. 审查

- [x] 5a 独立工程、产品范围和最终 diff 复核：只修改文件显示、追加合并、客户端队列及失败操作；无数据库 schema、金额、路由、权限或依赖变更。
- [x] 5b `$codebase-degradation-review` 调度独立 `$contract-design-review` 与 `$ui-reuse-review`。服务端合并、幂等、上限、权限重验、关闭及 CAS 边界通过。
- [x] 已关闭的主要 finding：只读入口、恢复期创建竞态、旧响应覆盖、失败文件消失、未保存账户选择被覆盖、第二次追加绕过保存、首文件本地失败清队列、创建结果未知再次创建、取消后队列重启、预览输入错误无限重试，以及超限重复 ref 产生重复失败行。对应失败回归及修复已验证。
- [x] UI 复用：沿用 Material 3、FinanceTheme 与原通用删除按钮；新增 Snackbar 使用原生 host，无控件包装或 token 分叉。
- [x] 最终契约一致性复审：活动 delta 的密码/失败文件继续规则已统一，两个 change 的重叠 requirement 正文相同，独立复核确认阻断关闭。
- [x] 最终 Hallmark 截图复审：独立复核 `review-notice-final/` 确认删除后过时 Snackbar 消失、下一步恢复；重试后的导航由真实浏览器回归验证。唯一过时提示 finding 已关闭，0 critical、0 major、0 minor；范围为 390 px Web 与共享代码。
- 完整流程反馈审查（只读建议，未实施）：覆盖文件选择、上传/重试/删除、账户选择/创建、核对/详情/分页、结果恢复和退出。文件选择器仅有通用 `onError` 分支，没有实际打不开的复现证据，不列为可重试的业务场景，不新增阻断或要求用户重试；取消选择保持无提示返回。正常界面不应产生的选择数量组合应由选择控件限制，不另设业务重试流程。忙碌退出时只有确认按钮的提示弹窗可改为 Snackbar，因为没有需要用户选择的决定。超过剩余容量可补一次批次汇总，保留逐文件结果。删除、账户读取、核对生成失败可补即时反馈，但不移除错误的归属信息及刷新入口。
- 独立审查边界：删除和生成核对列表的响应丢失可能已经变更会话，必须保留 `needsContextRefresh`；账户写入未知、提交未知、初始加载阻断、文件/字段错误、预览失效、分页重试和结果摘要维持可持续查看的界面。成功上传、删除和账户回填已有直接反馈，无需逐项弹成功提示。若实施更多 Snackbar，须区分各消息的失效条件，并由最上层可见模态容器承载，避免当前 `fileStepNotice` 变化误清其他消息或提示被子弹层遮挡。此次为共享代码和既有规格审查，未新增交互或扩大设备 QA。

### 技术异常边界专项审查

- 范围：当前工作树的现金导入客户端、共享 API／错误映射、账户恢复，以及独立上下文检查的后端解析、资金扫描、会话、核对和提交边界。用户确认的审查标准为“内部异常保留诊断并停止当前操作，最外层允许安全兜底”；只显示统一错误本身不是缺陷。以下待修问题属于异常吞没、错误归因或故障后继续处理，本轮未修改实现；前述 UI 交互复审结论不覆盖这些异常分类问题。故障注入基线为 `6c436265904da59a75ece6722f3f48a95e345f4e`。
- Major：`cash_import_funding.py:257/340` 捕获整行扫描／分摊过程的 `ValueError` 和 `ArithmeticError`，内部转换结果缺少 `source_display_name`、算法除零也成为 `funding_unreadable`，用户看到“资金信息无法识别，已跳过”。应在原始输入转换处处理专用的格式异常，内部不变量和算法异常直接抛出并停止当前操作。
- Major：`cash_import_context.py:174` 和 `cash_statement_document.py:256` 将解析器任意异常变为可重试文件错误；客户端 `CashImportContextApi.kt:132` 将任意读取异常变为 `import_file_read_failed` 并继续下一份。`BoundedImportFileReading.web.kt:56` 还会丢弃原异常类型。应区分实际文件 I/O／格式失败与程序错误，后者原样传播；只改外层捕获无法消除内层包装。
- Major：`cash_import_routes.py:108/131/149`、`cash_plan_routes.py:137/149` 将任意 Bug 返回为 `503`、`retryable=true`；客户端 `FinanceApiClient.kt:97`、`CashImportLifecycleRepositoryImpl.kt:37`、`SessionMappers.kt:51` 又把反序列化／映射异常包装为普通 API／领域失败。应保留已知拒绝和明确暂时故障，程序错误进入统一内部错误边界，保留调用栈并返回 `500`；界面可以显示“请稍后再试”，但不能据此声明可恢复或自动重试。
- Major：`cash_import_plans.py:247` 的重复内部操作引用、缺失详情等错误被映射为 `plan_build_failed`；`CashImportItemsPagingSource.kt:26/41` 将损坏的分页合同或任意 `Throwable` 转为列表重试；`ImportTaskUiState.kt:219/226/233/235` 将不匹配的收据／未知协议状态转为普通结果未知。结构校验须保留，失败应报告协议／程序错误；仍保留可能已经提交的原命令和幂等键，不能因此重新写入。
- Major：`CashImportTaskViewModel.kt:209/504/516` 与 `ImportAccountViewModel.kt:62/79/174/288/345` 的异常转换需区分边界职责。把程序错误变成文件失败并继续队列、丢失原诊断属于问题；外层停止当前操作后显示页面错误可以保留。账户未决状态只能依据实际写入确定性产生，不能代替程序异常诊断；内部状态编辑、账户恢复／撤销代码 Bug 应中止当前操作。
- Major：`CashImportTaskScreen.kt:140` 捕获辅助读取／映射异常后只保留 `auxiliaryFailed = true`，丢失原异常及诊断。阻断页面与“导入时发生异常，请返回后重试”文案可以保留，需补足外层错误报告，不应为了直接抛出而让页面白屏。
- 非 finding：`cash_commit_outcome.py:29` 的提交边界停止当前操作，通过 `report_cash_failure` 记录原异常类型、调用栈及原因链，并保留提交确定性，属于允许的外层兜底。统一安全错误与原幂等键核实路径应保留，不能仅因文案含“重试”就移除。
- Major：`CashImportTaskViewModel.kt:220` 直接吞掉保存会话恢复指针的所有异常；`ImportRecoveryStore.kt:65`、`ImportAccountRecovery.kt:58`、`parsed_import_staging.py:735/751/759` 也将程序错误归为存储不可用。应缩窄存储异常转换并传播内部错误；不能移除提交前保存恢复键的安全门禁。真实 R2 故障未实测，此项以静态调用链为证据。
- 证据：独立后端上下文用内存 stub／有效合成 XLSX 注入 `AssertionError`、`ValueError`、`ZeroDivisionError`，验证解析失败文件、资金行跳过、可重试 503、内部计划结构错误和提交前错误分类。未连接业务数据库。主上下文运行现有 4 项定向 Kotlin 测试全部通过，确认现有测试确实接受“程序异常降为文件错误”“损坏恢复数据转为领域失败”“无关账户收据留下待核实状态”；取消异常传播对照通过。日志为 `/tmp/ft-cash-import-qa-20261102/exception-audit-existing-tests.log`。通过代表复现当前行为，不代表该设计符合本轮要求。
- 保留边界：密码／格式／容量、权限和账户资格、事实修订冲突，以及真实网络中断后的原幂等键核实。提交已成功后的清理失败不能谎报业务提交失败。未发现当前行保存／关系保存因任意异常继续发布局部成功；词典中的历史失败文案本身不构成可达证据。
- 未覆盖：全部银行转换器、投资导入、真实 R2／数据库故障及原生设备交互。主规格中“转换器内部异常成为文件错误”等表述与用户确认的内部异常边界存在冲突；实施时须先回写相关规格和设计，不能只删除提示或捕获。本轮只更新审查记录、UI 规范与 Compose 技能，不新增实现或宣布技术异常专项通过。

## 6. 测试与 QA

- [x] 完整后端回归：`PYTHONPATH=tests:.:src uv run python scripts/run_backend_tests.py --shard-index 0 --shard-count 1` → 4625 passed、1446 skipped、73 performance deselected、5 subtests passed。未配置 PG 的 skip 不计通过，另跑真实双后端矩阵。
- [x] 专用 UTF8 PostgreSQL 16 库 `ft_cash_import_utf8_test`，显式 `FT_TEST_POSTGRES_URL=postgresql+psycopg://huangwenlong@127.0.0.1:55432/ft_cash_import_utf8_test`，运行 `test_parsed_cash_import_context.py`、`test_cash_import_session_api.py`、`test_cash_import_choice_recovery.py` → 140 passed。测试库由执行者手动创建，未自动探测；测试结束后仅清理本次临时实例。
- [x] 性能专项：同一显式 PG URL，`PYTHONPATH=tests:.:src uv run pytest -q -m performance tests/test_cash_import_session_performance.py` → SQLite/PG 共 2 passed。覆盖 1k 会话发布和元数据读取预算；未改业务预览/确认算法，不额外运行其余全库性能负载。
- [x] 安全专项：同合同矩阵覆盖解析后权限撤销、会话归属、未来修订、关闭/提交、20 份上限、摘要冲突；诊断及密码字段隔离在完整回归通过。文件名文本依用户决定保真，不作为账户身份证据。
- [x] `uv run python -m compileall -q src` 通过。仓库未配置独立 Python 静态类型工具；Kotlin 类型检查由编译执行。
- [x] `cd compose && ./gradlew :shared:testAndroidHostTest :shared:compileKotlinIosSimulatorArm64` → 471 tests、0 failures/errors/skips，Android 共享编译与 iOS 编译通过；Web production Wasm 构建通过。
- [x] 真实 Chrome/Playwright：390×844（密码页390×900）、`http://127.0.0.1:5187`。初始失败、关闭重进、恢复指针、加载中共 6 条主流程通过；原有移除/重试相关 4 条通过；本轮密码 Snackbar、失败 Snackbar/删除、同 ref 重试成功、加载阻断 4 条通过。测试使用合成数据，预期 500 不算意外错误；无新增控制台/静态资源错误。
- [x] 最新密码、删除后 Snackbar 消失、重试成功三条受影响冒烟通过；固定截图 `review-notice-final/`。
- [x] OpenSpec strict 28 passed、doctor 和当前 diff 检查通过；归档后再校验主规格。
- Cross-platform Impact Check：Web、Android、iOS 共用 commonMain 的队列、失败操作、Snackbar 与文案；默认原生范围仅编译与单测，未执行设备 QA。额外视口/主题、真实读屏设备未覆盖，不宣称通过。

## 7. 发布

- [x] 文档收尾核对：导入流程、协议、功能地图、活动规格、词表及 OpenSpec apply 参考已对齐；README/配置/迁移及其他 references 无新命令、依赖或概念，无需修改。页面数量与入口不变。
- [x] 用户授权同一分支 commit/push，并将文件名、并发追加、加载阻断合为一个 PR 到 main；没有 merge/deploy 授权。
- [x] 提交推送并创建 PR（本次交付）；最新演示确认与三项归档待办，等待 PR 检查。
- 最新代码的离线演示位于 `http://127.0.0.1:5186/`，已验证导入入口及文件页面可打开，用户确认待完成；完整流程 Snackbar 审查建议尚未纳入实现范围。
- 回滚：同时恢复服务端严格追加与客户端串行策略、文件名处理及原加载失败呈现；无数据库迁移，已有临时会话按 TTL 过期。观察并发文件计数、逐文件失败和修订冲突，不记录真实文件名或内容。

## 验证证据

- 工作分支 `fix-import-original-filename-display`；HEAD/比较基线 `6c436265904da59a75ece6722f3f48a95e345f4e`，验证对象为该基线上的本次工作树 diff；2026-11-02，Node 26.8.1、OpenSpec 1.7.0、Python 3.11.12。
- 本机日志及截图：`/tmp/ft-cash-import-qa-20261102/`；完整后端 `/tmp/cash-import-backend-regression.log`；双后端 `dual-backend.log`；性能 `session-performance.log`；最新共享 `retry-compose-final.log`；浏览器 `retry-browser-notice-final.log`，固定图 `review-notice-final/`。
- 失败回归：原并发追加合同先红后绿；独立评审 3 项 ViewModel 回归 `client-red.log` 为 3 failed；同 ref 重试 `retry-red.log` 为 1 failed；超限重试 `retry-capacity-red.log` 为 1 failed。均因缺少目标行为失败，再完成修复。
