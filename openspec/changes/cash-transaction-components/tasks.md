## 1. 规格与术语

- [x] 1.1 运行 `openspec validate --all --strict`，修正 proposal、delta spec、design 和 tasks 的一致性问题。
- [x] 1.2 复核 `DOMAIN_GLOSSARY.md` 和本变更文案，统一使用“现金流水父记录”“支付组成项”“现金粒度”“账户余额分录”。
- [x] 1.3 在 `tasks.md` 记录当前 `HEAD`、比较基线、变更范围和未解决风险。

实施记录：当前 `HEAD=4f4633d`，比较基线为 `origin/refactor/web`（同一提交）；工作树保留未提交的本变更文件。OpenSpec 全量严格校验已通过。未解决风险包括旧应用服务仍使用父流水关系端点，以及 PostgreSQL 专用测试库尚未配置。

实施记录（转账端点小步）：`FactView` 及关系展开保留父级 `cash_granularity`；普通转账与个人换汇 matcher 只接受 `atomic` 组成项，aggregate 组成项首期失败关闭。验证命令：`PYTHONPATH=tests:.:src uv run pytest tests/test_source_agnostic_transfer_matching.py tests/test_transaction_relations_transfer.py tests/test_transfer_phase_c.py tests/test_transaction_relations_open_leg.py tests/test_cash_transaction_components.py -q`，结果 `36 passed, 7 skipped`；`git diff --check` 通过。完成提交后记录实际 `HEAD`。

实施记录（组成项 open-leg 与账单校准）：新增 aggregate 退款的 component 锚点回归，验证待配对关系保存 `anchor_component_id`，同时以 `anchor_record_id` 支持父流水导航。真实样本校准结果：`支付宝交易明细(20230614-20240613).csv` 共 `1368` 行，其中 `17` 行含 `&`，解析为 `11` 个单组成项、`5` 个双组成项、`1` 个三组成项，`11` 行 ready、`6` 行 requires_allocation；`支付宝交易明细(20240614-20250613).csv` 共 `664` 行，`60` 行含 `&` 且均为单账户 ready；`支付宝交易明细(20250614-20260613).csv` 共 `1028` 行，`83` 行含 `&` 且均为单账户 ready。校准仅读取 `~/.ft/bills`，未读取或提交真实账单内容。

实施记录（退款分摊额度）：新增组件退款回归，验证一笔金额 100 的支出通过 `applied_amount=30` 关联首笔退款后，后续金额 50 的退款仍可自动匹配；剩余额度和扫描/导入持久化扣减均改为读取关系 `applied_amount`，并避免部分退款的支出端 anchor 提前屏蔽剩余候选。验证命令：`PYTHONPATH=tests:.:src uv run pytest tests/test_cash_transaction_components.py tests/test_transaction_relations_open_leg.py::test_partial_refund_keeps_expense_eligible_across_scans tests/test_import_scan_refund_boundary.py::test_scan_phase_a_allows_multiple_alipay_partial_refunds -q`，结果 `7 passed, 1 skipped`；关系/投影/转账回归 `81 passed, 9 skipped`，其中 `tests/test_transaction_relations_projection.py::test_accept_rejects_a_refund_that_cannot_form_a_cash_projection` 因旧夹具仍写入父流水外键而失败，待组件测试夹具更新；`git diff --check` 通过。

实施记录（测试夹具组件化）：更新 `cash_web_runtime` 夹具，为固定种子现金流水建立 singleton `cash_transaction_components`，并在父流水 flush 后写入，修复关系模型切换到组件外键后旧测试直接引用父 ID 导致的外键失败。验证命令：`PYTHONPATH=tests:.:src uv run pytest tests/test_transaction_relations_projection.py tests/test_cash_ledger_management.py tests/test_application_cash_projections.py -q`，结果 `48 passed, 7 skipped`；完整关系/投影/转账回归 `82 passed, 9 skipped`；`git diff --check` 通过。

实施记录（资金调拨关系测试组件化）：更新现金—投资资金调拨测试夹具，为每条现金流水创建 singleton component，并将关系断言及手工关系构造统一改为 `cash_transaction_component_id`。验证命令：`PYTHONPATH=tests:.:src uv run pytest tests/test_cash_investment_funding_relations.py -q`，结果 `10 passed, 10 skipped`；PostgreSQL 因 `FT_TEST_POSTGRES_URL` 未配置而跳过，未计入通过项。

实施记录（投资账本关系夹具组件化）：更新投资账本查询和性能夹具，关系统一引用 `cash_transaction_component_id`，并为性能数据集写入 singleton `cash_transaction_components`。验证命令：`PYTHONPATH=tests:.:src uv run pytest tests/test_application_investment_web_queries.py -q`，结果 `4 passed, 1 skipped`；`PYTHONPATH=tests:.:src uv run pytest tests/test_investment_web_performance.py -q`，结果 `2 passed, 2 skipped`；PostgreSQL 因 `FT_TEST_POSTGRES_URL` 未配置而跳过，未计入通过项。

实施记录（旧版手工现金服务组件化）：`CashflowService.add_manual_transaction` 接受 `components`/`component_allocation`，新增 aggregate 父流水时按 component 写入余额快照，并返回 component 明细。验证命令：`PYTHONPATH=tests:.:src uv run pytest tests/test_application_cash_projections.py::test_manual_cash_write_persists_components_and_updates_each_component_balance tests/test_application_cash_projections.py::test_uninitialized_cash_write_commits_then_first_rebuild_publishes_it -q`，结果 `2 passed`；组件金额守恒和父流水 `cash_granularity` 已由仓储校验。

实施记录（活跃来源索引修复）：在组件重建 revision 中恢复 `uq_cash_transactions_active_source_record` 的 `deleted_at IS NULL` 谓词，允许软删除后重新导入相同来源记录。验证命令：`PYTHONPATH=tests:.:src uv run pytest tests/test_multi_currency_accounts.py::test_same_account_cny_and_jpy_add_and_checkin_do_not_clobber tests/test_015_idempotency.py::test_soft_delete_then_reimport_allows_new_active -q`，结果 `2 passed`。

实施记录（现金流 HTTP 组件输入）：`/api/v1/cashflow/add` 现在接受 `components` / `component_allocation`，按 Decimal 字符串解析组成项金额；组合流水可省略父级账户并由服务层从首个组成项推导。列表 JSON 序列化补齐递归，确保响应中的组成项 Decimal 始终输出为精确字符串。验证命令：`PYTHONPATH=tests:.:src uv run pytest tests/contract/test_cli_replacement_api.py -q`，结果 `8 passed, 1 skipped`；`git diff --check` 通过。

实施记录（支付宝组合支付导入）：来源账户扫描现在为已验证的 `&` 组成项分别建立映射组；数据库映射解析器保留一条父行并生成带账户的 `component_allocation` 草稿，金额不完整时在确认前返回 `import_component_allocation_incomplete`。修复支付宝单账户草稿使用原始支付方式而不是默认钱包，并将映射后的账户名写入 singleton component。验证命令：`PYTHONPATH=tests:.:src uv run pytest tests/test_statement_account_mapping.py tests/test_statement_import_mapping.py tests/test_cash_import_wizard.py tests/test_cash_import_session_service.py tests/test_import_scan_refund_boundary.py tests/contract/test_cash_import_dual_backend.py -q`，结果 `107 passed, 7 skipped`；`git diff --check` 通过。

实施记录（组件外键测试夹具）：为新增现金父流水的关系、投影、证据和分类维护测试补齐 singleton `cash_transaction_components`，并删除无法在新组件端点模型中表达的现金到投资旧关系夹具。验证命令：`PYTHONPATH=tests:.:src uv run pytest tests/contract/test_web_api.py::test_projection_api_returns_member_sources_in_member_order_without_duplicates tests/integration/test_web_sqlite.py::test_file_sqlite_evidence_read_uses_one_projection_snapshot tests/test_relational_cash_projection_evidence.py tests/test_relational_cash_projections.py::test_replace_dataset_bulk_writes_restricted_parent_mapping_and_preserves_roles_and_ordinals tests/test_application_web_queries.py::test_projection_page_keeps_version_and_dataset_in_one_read_snapshot tests/test_cash_category_management.py::test_relation_maintenance_adopts_display_root_category_for_all_members -q`，结果 `7 passed, 3 skipped`；`git diff --check` 通过。

实施记录（幂等来源元数据清理）：重复导入来源行不再携带 `relation_metadata` 时，现金仓储会清空旧的派生关系元数据，避免 stale hint 残留；来源快照仍保持不变。验证命令：`PYTHONPATH=tests:.:src uv run pytest tests/test_015_idempotency.py::test_relation_metadata_is_separate_and_refreshes_on_idempotent_reimport -q`，结果 `1 passed`；`git diff --check` 通过。

## 2. 失败契约测试（先红）

- [ ] 2.1 新增父流水/组成项守恒、`atomic`/`aggregate` 派生和非法输入测试，先确认当前实现失败。
- [ ] 2.2 新增支付宝 `&` 多账户预览阻塞、Decimal 分摊确认、重复确认幂等和 `source_payload` 保留测试。
- [x] 2.3 新增 component-to-component `payment_mirror`、部分/多笔 `refund_offset`、singleton `transfer_pair` 和 applied amount 超额拒绝测试。
- [ ] 2.4 新增组成项余额快照、账户筛选、父级投影展示和财富现金流来源 revision 测试。
- [ ] 2.5 新增 SQLite/PostgreSQL 同一契约矩阵测试，覆盖工作区外键、精确金额和关系端点类型。

## 3. 数据模型与持久化

- [ ] 3.1 在 `cash_transactions` 增加 `cash_granularity`，放宽 aggregate 父流水的 `account_id`，并新增父级守恒相关约束/索引。
- [ ] 3.2 新增 `cash_transaction_components` 模型、复合工作区外键、Decimal 字段、顺序唯一约束和账户索引。
- [ ] 3.3 重建现金关系模型，使现金端点为 component 外键并保存 `applied_amount`；同步投资资金关系模型。
- [ ] 3.4 更新 schema 创建、revision 触发器和测试 fixture；删除旧模型依赖，不添加兼容迁移。

## 4. 现金写入与余额

- [ ] 4.1 扩展现金仓储与 UoW，提供父流水和组成项的原子新增、更新、删除及守恒校验。
- [ ] 4.2 改造手工流水新增/编辑，使普通流水自动维护 singleton component，组合流水可增删多个 component，界面不暴露 `cash_granularity`。
- [ ] 4.3 改造余额快照、现金列表账户过滤和删除/编辑影响计算，使账户余额只读取 component。
- [ ] 4.4 更新现金事实读取边界，确保关系扫描、投影和财富读取能得到父字段与 component 分配。

## 5. 支付平台导入

- [ ] 5.1 扩展支付宝 `&` 支付方式解析，区分真实账户项与红包/优惠类非账户项，并保留原始行不变。
- [ ] 5.2 扩展导入预览数据结构，返回 component 草稿、缺失分摊金额错误和可操作的阻塞状态。
- [ ] 5.3 扩展导入确认 API/服务，接收 Decimal 分摊并在同一事务写入父流水、组成项、映射和快照。
- [x] 5.4 用 `~/.ft/bills` 的真实支付宝样本做全量导入校准，记录各 `&` 桶的解析、阻塞和成功计数。

## 6. 关系匹配与投资资金

- [ ] 6.1 将 `payment_mirror` matcher/index 从父 fact 展开为 component 视图，保留父流水的时间、商户和来源证据。
- [x] 6.2 将 `refund_offset` 剩余金额和 open-leg 锚点改为 component，支持部分退款、分次退款及平台/银行退款镜像。
- [x] 6.3 将普通 `transfer_pair` 改为 singleton component 端点；aggregate 转账首期失败关闭，换汇保留双币种语义。
- [x] 6.4 将现金—投资资金调拨候选、唯一性和投影引用改为 component 端点。
- [ ] 6.5 更新关系审查、确认、取代、幂等和跨工作区校验，确保不能混入父流水 ID。

## 7. 投影、财富与客户端

- [ ] 7.1 重写现金投影构建输入和校验：按 component 计算账户维度，按父流水聚合列表金额和证据。
- [ ] 7.2 更新 projection schema/query，使 aggregate 不伪造单一账户，支持组成项详情和账户筛选。
- [ ] 7.3 更新财富现金流读取和 source revision，组件变化可触发可重建读模型。
- [ ] 7.4 更新 Web/Native 手工流水表单、导入预览和详情展示，覆盖正常、阻塞、错误、空状态及键盘/响应式行为。
- [ ] 7.5 执行跨端影响检查；若出现 UI 结构变化，创建原型并完成 Hallmark audit。

UI 信息架构记录：Web/Native 均保留父流水作为单一展示单位，账户分配是可编辑子区域；不展示 `cash_granularity`，单项/多项分配即为用户可见语义。删除 aggregate 场景下的单一账户强制输入和粒度切换控件。原型路径为 `openspec/changes/cash-transaction-components/prototype/index.html`，状态覆盖正常、空分配、守恒错误、保存中、成功和失败；跨端共享相同的字段顺序、守恒校验和错误语义，Native 仅使用平台原生控件。

实施记录（Web 手工流水组件编辑）：`RecordDrawer` 以父流水为编辑单位，新增/删除/修改账户分配并提交 `components`；保存按钮和服务端错误均阻止金额不守恒写入，首项保留 `账户` 无障碍标签以兼容既有键盘流程。原型与 Web 构建验证：`npm run build --workspace finance-tracker-web` 通过；`npm test --workspace finance-tracker-web -- --run` 最终 `150 passed`；`npm test --workspace finance-tracker-web -- --run tests/CashLedgerPage.test.tsx` `29 passed`；`openspec validate --all --strict` 通过；`git diff --check` 通过。跨端影响：共享字段语义和守恒规则已记录，Native 编辑/导入尚待实现；真实浏览器 QA 与 Hallmark audit 尚未完成。

## 8. 审查与验证

- [ ] 8.1 运行受影响单元/集成/契约测试、类型检查、构建和 `git diff --check`，逐项记录结果。
- [ ] 8.2 在专用名称以 `_test` 结尾的 PostgreSQL 数据库配置 `FT_TEST_POSTGRES_URL`，补跑同一契约矩阵；未配置时记录为未完成。
- [ ] 8.3 使用真实浏览器验证导入预览补齐、确认、错误/空状态、组成项编辑、账户筛选和桌面/移动宽度，并记录 URL、视口、截图和控制台结果。
- [ ] 8.4 完成产品/工程/安全/最终 diff 复核，修复阻断 finding 并回写 `tasks.md`。
- [ ] 8.5 记录发布准备、重建数据库步骤、回滚方式、当前 `HEAD` 和残余风险；未经用户授权不提交或推送。
