## 1. 思考

- [x] 1.1 按 `import/cash-import-session`、`import/statement-import`、`cash-ledger/cash-ledger-browser` 与 `shared/cross-platform-presentation` 阅读主规格、项目上下文、当前实现和相关测试；该变更涉及财务来源身份、API 与多模块，按 A 类执行。
- [x] 1.2 确认范围与边界：微信交易单号必填且固定范围跨账号匹配；不兼容读取未绑定旧身份；昵称仅作本次分组；支付宝按账号分组；开关默认开启，冲突清空；不新增存量迁移。关键产品决策已由用户逐项确认。

## 2. 计划

- [x] 2.1 建立本 change 的 proposal、四项行为 delta 与 design；记录 API 变更、分组身份、选择簇条件、回滚方式和风险。
- [x] 2.2 明确本次不新增独立 UI 系统：沿用 Compose Material 3 token 和组件，账号标题 `titleMedium`、资金方式 `titleSmall`、辅助信息 `bodySmall`、开关说明 `bodyLarge`。

## 3. 任务拆分

- [x] 3.1 先写解析缺少微信交易号、未绑定历史身份误采用、微信／支付宝分组、同名选择冲突及密码按钮语义的回归测试，再实现合同。
- [x] 3.2 完成跨平台影响分析：Web、Android、iOS 共享 API、账户选择、分组文案和操作语义；本次改动共享 Compose presentation，不改变页面数量。

## 4. 构建

- [x] 4.1 后端移除 `source_scope_label` 与文件级微信来源设置，固定微信 `source_scope_key`，拒绝空交易单号，并移除未绑定微信旧记录的兼容身份恢复路径。
- [x] 4.2 为微信昵称和支付宝脱敏账号生成会话级分组，缺少账号时以文件分组；来源账号选择仍保留每个原始引用。
- [x] 4.3 Compose 默认启用跨渠道同名选择共享；来源组显示分离，选择冲突时清空，关闭后各组独立；密码按钮显示向右箭头和“提交”。
- [x] 4.4 更新接口 DTO、测试、用户文档、功能地图、词表及现金导入技能参考。

## 5. 审查

- [x] 5.1 产品与工程复核账号可变性、幂等、绑定边界、跨渠道选择扇出及冲突恢复；无未关闭 P1/P2 finding。发现未绑定微信旧记录仍会由旧 `record_id` 或时间进入候选后，先加失败回归，再删除该兼容读取。
- [x] 5.2 按 `docs/ui-design-rules.md`、Compose Material 3 组件与 Typography 规范检查账户分组、状态、命中区、间距、文字层级和密码按钮；Hallmark runtime 不可用，按其 audit 指引完成人工替代复核，未发现 critical/major finding。
- [x] 5.3 以最新 `origin/main` 为基线复核最终差异、功能地图、链接、术语和无冲突 OpenSpec 文件；delta 与主规格一致，归档前后相关文档链接均指向有效路径。

## 6. 测试与 QA

- [ ] 6.1 当前最新主线上的 SQLite 与显式专用 PostgreSQL 双后端矩阵：`tests/contract/test_cash_draft_source_identity.py`、`test_cash_import_account_mappings.py`、`test_cash_import_business_plan.py`、`test_cash_import_choice_recovery.py`、`test_cash_import_operations.py`、`test_cash_import_session_api.py`、`test_cash_mutation_business_plan.py`、`test_cash_source_storage.py`、`test_parsed_cash_import_context.py`、`test_cash_import_commit.py`、`test_cash_mutation_commit.py`、`test_cash_hotpath_equivalence.py`、`test_cash_import_funding.py`、`test_cash_source_scope.py`、`test_cash_statement_document.py`、`test_cash_statement_rows.py`、`test_parsed_import_staging.py`、`test_bank_statement_rows.py`、`test_cash_statement_effects.py`、`test_icbc_pdf_statement.py`、`test_runtime_docs.py`。专用库名 `ft_cash_import_pr_20261029_test`，显式设置 `FT_TEST_POSTGRES_URL` 与 `FT_REQUIRE_TEST_POSTGRES=1`；测试不得自动探测数据库。本轮曾启动该矩阵，按用户 2026-10-29 指示在约 19% 处中断；没有通过或失败结论，用户要求本地暂不跑单测。
- [ ] 6.2 Compose 共享层、Android Host 单测和 iOS SimulatorArm64 编译：`cd compose && ./gradlew :shared:wasmJsTest :shared:compileAndroidMain :shared:testAndroidHostTest :shared:compileKotlinIosSimulatorArm64 --no-configuration-cache --console=plain`。本轮切换至最新 `origin/main` 后未重跑；先前同一实现树上的构建和测试结果不作为当前 HEAD 验收证据。
- [ ] 6.3 真实 Chromium 在 390 × 900 视口完成密码提交和来源账号分组流程，检查同昵称、不同昵称、支付宝脱敏分组、开关默认值及冲突清空；按当前源码重启离线 Compose 演示并检查健康状态。本轮未重跑；先前同一实现树的浏览器检查与用户演示确认均早于更新父基线，不作为当前 HEAD 的新 QA 证据。
- [x] 6.4 执行 `openspec validate --all --strict`、`openspec doctor`、`uv run python -m compileall -q src/ft`、`git diff --check` 和文档／链接／术语复核；没有配置运行的设备 QA 不记为通过。

## 7. 发布

- [x] 7.1 核对 README、现金导入流程与契约、功能地图、词表、API 参考、导入技能及 OpenSpec；未改变 README。增量文档与规格已同步至最终代码合同。
- [x] 7.2 按用户授权提交代码、推送分支并创建 PR：代码提交 `0ae724fbdd4cb44baba3f89f88a7b4c9b6e2e806`，分支 `feat/cash-import-account-grouping` 已推送至 `origin`，PR [#103](https://github.com/ssttkkl/finance-tracker/pull/103) 目标为 `main`。未合并、未部署、未写真实账本；创建 PR 时远端 CI 正在运行。

## 8. 反思

- [x] 8.1 记录可复用的来源身份边界与防复发测试；回归覆盖未绑定微信行不得由旧 ID／来源行／时间／金额／位置自动采用。该测试在本轮未重跑，见 6.1。

## 当前审查与验证

验证基线：`origin/main=19d0b8862b04ca010b9c4cca82db326f3d9cdc7c`，代码提交前执行命令时的 `HEAD` 为该基线，工作树内容随后原样提交为 `0ae724fbdd4cb44baba3f89f88a7b4c9b6e2e806`。早先实现树以 `0c038a75e34264bd6d4aa5cce2070727df9d9ff7` 为基线；远端新增的 `#102` 只清理旧客户端并归档既有 change。本轮不把早先基线上的检查结果宣称为当前树新验证。

当前树已执行：`uv run python -m compileall -q src/ft`（10:10，通过）、`openspec validate --all --strict`（归档后 24 项通过，0 失败）、`openspec doctor`（根目录检查通过）、`git diff --check`（通过）、归档任务链接检查（通过）；最终一轮于 2026-10-29 10:09–10:10 Asia/Shanghai 完成。归档前曾有 25 项通过，其中包含本 change。delta 已合并到对应四项主规格，逐项比对需求后完全一致。change 按用户要求归档至 `openspec/changes/archive/2026-10-29-cash-import-source-account-grouping/`，保留 6.1–6.3 的未完成状态。PR #103 创建时 GitHub 后端与 Compose CI 均处于运行中，尚无结果。当前 SQLite／PostgreSQL 单测矩阵、Compose 测试／编译及更新父基线后的浏览器 QA 未完成；本轮按用户指示中断单测并直接交付，不以此前基线上的结果代替当前 HEAD 结果。确切补跑条件：用户要求补验，或 PR CI 失败后需要本地复现。未执行原生设备 QA。微信身份相关检查仅使用合成去标识化数据；未访问或修改真实账本。
