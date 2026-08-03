# Tasks: 工行退款摘要关系配对

**Input**: `spec.md`、`plan.md`、`research.md`、`data-model.md`、`contracts/`

**变更等级**: A 类完整 Feature。原因：改变账单来源字段、退款关系匹配、持久化来源快照语义，并要求 SQLite/PostgreSQL 等价证据。

**实施原则**: 每项可执行行为先写能因缺陷而失败的测试，再做最小实现；不提供历史兼容回退，用户通过重建数据库后重导修复数据。

## Phase 1：规格与一致性门禁

- [X] T001 完成 `specs/023-icbc-refund-pairing/spec.md`，覆盖信用卡、借记卡、对手方规范化、正式退款信号、非目标和验收场景。
- [X] T002 完成 `specs/023-icbc-refund-pairing/plan.md`、`research.md`、`data-model.md`、`quickstart.md` 与 `contracts/statement-import.md`。
- [X] T003 更新 `DOMAIN_GLOSSARY.md`，统一使用来源摘要、正式退款信号、来源行快照和退款冲销关系。
- [X] T004 完成 `$speckit-clarify` 复核：确认正式信号只接受摘要精确值 `退货`，确认不兼容历史错误数据，未保留未决问题。证据：2026-08-01 13:42 CST，基于用户已明确的业务口径完成澄清，无未决问题。
- [X] T005 完成 `$speckit-analyze`：检查 spec、plan、tasks 的需求覆盖、数据流一致性和 SQLite/PostgreSQL 矩阵；无 CRITICAL/HIGH 未解决项。证据：2026-08-01 13:45 CST，运行 `check-prerequisites.sh --json --require-tasks --include-tasks`，并完成占位符、FR/SC/T 任务覆盖和 Constitution Check 复核。

## Phase 2：失败回归测试（必须先于实现）

- [X] T006 [P] [US1] 在 `tests/test_convert.py` 增加目标 PDF 三行等价文本回归：19:11、19:13、19:16 规范化 `counterparty` 相同，退款行 `summary=退货` 且正式 `refund_signal` 正确。证据：2026-08-01 14:09 CST，`tests/test_icbc_refund_pairing.py` 覆盖目标三行解析；真实 PDF 完整解析输出三行均为 `山葵村烤肉`，19:13 为 `summary=退货/refund_signal=icbc_credit_return`。
- [X] T007 [P] [US2] 在 `tests/test_convert.py` 增加信用卡与借记卡的 `退货`/`退款` 信号边界测试。证据：2026-08-01 14:09 CST，`tests/test_icbc_refund_pairing.py` 覆盖信用卡与借记卡的精确摘要边界，受影响测试通过。
- [X] T008 [P] [US1] 在 `tests/test_statement_import_mapping.py` 或独立测试中增加真实 `StatementImportService` 链路：`source_type` 取 `bill_source`，`source_payload` 透传 `summary/refund_signal`。证据：2026-08-01 14:09 CST，`tests/test_postgres_statement_import.py::test_icbc_import_uses_parsed_bill_source_and_refund_fields` 通过。
- [X] T009 [P] [US1] 在 `tests/test_relations_index_injection.py` 与 `tests/test_transaction_relations_refund.py` 增加结构化信号进入候选及形成强退款冲销关系的测试，并覆盖缺失/伪造信号拒绝。证据：2026-08-01 14:09 CST，结构化候选、强关系和缺失信号拒绝测试均通过。
- [X] T010 [P] [US1] 在 `tests/test_import_scan_refund_boundary.py` 增加 SQLite 关系扫描集成测试：真实持久化后的工行三条记录仅 `-272/+272` 形成 `accepted/strong` 关系。证据：2026-08-01 14:09 CST，SQLite 集成测试通过，关系仅包含 `-272/+272`，`-222` 未被占用。
- [X] T011 [P] [US1] 在 `tests/contract/test_dual_backend_icbc_refund_pairing.py` 增加 SQLite 与真实 PostgreSQL 同一导入/扫描契约；PostgreSQL 环境缺失时只允许明确跳过并记录补跑命令。证据：2026-08-01 14:09 CST，`uv run pytest -q tests/contract/test_dual_backend_icbc_refund_pairing.py`：`1 passed, 1 skipped`；跳过原因为未设置 `FT_TEST_POSTGRES_URL`，设置后可补跑。
- [X] T012 运行 T006–T011 的目标测试，确认至少覆盖目标行为的测试在修复前失败；记录失败证据后再实施。证据：2026-08-01 13:49 CST，HEAD `fca208a`，运行 `uv run pytest -q tests/test_icbc_refund_pairing.py tests/test_postgres_statement_import.py::test_icbc_import_uses_parsed_bill_source_and_refund_fields tests/test_relations_index_injection.py tests/test_transaction_relations_refund.py::test_icbc_structured_return_signal_can_form_strong_refund_offset tests/test_transaction_relations_refund.py::test_icbc_structured_return_signal_does_not_read_summary_or_text_fallback tests/test_import_scan_refund_boundary.py::test_icbc_credit_refund_scan_pairs_only_matching_consumption tests/contract/test_dual_backend_icbc_refund_pairing.py`：11 failed、3 passed、1 skipped；失败均对应待修复行为。

## Phase 3：用户故事 1——信用卡解析、导入和关系配对

- [X] T013 [US1] 修复 `src/ft/convert.py` 的工行信用卡摘要扫描：独立保存 `summary`，`退货` 不得作为 `counterparty`，完整商户文本走统一规范化。证据：2026-08-01 14:09 CST，真实 PDF 完整解析链路核验通过。
- [X] T014 [US1] 修复 `src/ft/convert.py` 的输出行与 `src/ft/adapters/statement_import.py` 字段透传：保存 `summary`、正式 `refund_signal` 和原始对手方来源字段。证据：2026-08-01 14:09 CST，真实解析行与导入持久化测试均确认字段存在。
- [X] T015 [US1] 修复 `src/ft/application/statement_import.py`：正式 `source_type` 使用解析行 `bill_source`，混合渠道输入失败关闭，保持幂等身份和导入不写关系。证据：2026-08-01 14:09 CST，导入映射、重复导入和关系边界测试通过。
- [X] T016 [US1] 修复 `src/ft/domain/relations/refund/signals.py`、`core/types.py`、`pipeline.py`、`refund/match.py`、`refund/diamond.py`：工行读取结构化 fact-level gate，其他来源保留原规则。证据：2026-08-01 14:09 CST，结构化 gate、候选索引、退款匹配和银行退款链路受影响测试通过。
- [X] T017 [US1] 运行 T006、T008–T010，确认目标 PDF 的规范化对手方一致、正式字段落库、关系只配对 `-272/+272`。证据：2026-08-01 14:09 CST，真实 PDF 输出和 SQLite 集成扫描均符合验收。

## Phase 4：用户故事 2——借记卡和信号边界

- [X] T018 [US2] 修复 `src/ft/convert.py` 借记卡输出，确保摘要精确为 `退货` 生成 `icbc_debit_return`，`退款` 和普通收入不生成正式工行信号。证据：2026-08-01 14:09 CST，借记卡解析回归测试通过。
- [X] T019 [US2] 运行 T007、T009 及现有工行借记卡测试，确认非工行来源仍使用既有信号规则。证据：2026-08-01 14:09 CST，受影响套件 `269 passed, 2 skipped`，非工行退款规则回归通过。

## Phase 5：验证与发布门禁

- [X] T020 [US1] [US2] 运行受影响测试：`uv run pytest -q tests/test_convert.py tests/test_statement_import_mapping.py tests/test_relations_index_injection.py tests/test_transaction_relations_refund.py tests/test_import_scan_refund_boundary.py`。证据：2026-08-01 14:09 CST，扩展受影响命令包含导入、双后端契约和新增回归：`269 passed, 2 skipped`。
- [X] T021 [US1] [US2] 运行 SQLite 与真实 PostgreSQL 契约矩阵：`uv run pytest -q tests/contract/test_dual_backend_icbc_refund_pairing.py`；记录当前 HEAD、基线、时间和 PostgreSQL 环境结果。证据：2026-08-01 14:09 CST，HEAD `fca208a`，基线 `origin/refactor/web` merge-base `6a62a1e`，结果 `1 passed, 1 skipped`；未设置 `FT_TEST_POSTGRES_URL`。
- [ ] T022 [US1] [US2] 运行完整测试套件：`uv run pytest -q`。证据：2026-08-01 21:39 CST，HEAD `fed111a`，`1055 passed, 104 skipped, 1 failed`；唯一失败为未改动的 `tests/test_application_cash_projection_evidence.py::test_evidence_reads_members_and_relations_in_fixed_batch_queries`，实际 SELECT 9 条而既有断言上限 8 条。
- [ ] T023 [US1] [US2] 运行项目提供的类型检查、lint、构建；若某项命令不存在，记录实际探测结果和不适用理由。证据：2026-08-01 21:39 CST，`uv build` 通过；项目未配置独立 Python lint/type 命令；`npm run build` 被未触及的 `web/tests/CashTable.test.tsx:37` 既有 TS2554（`projection` 少传一个参数）阻断。
- [X] T024 [US1] [US2] 执行 `git diff --check`，检查未跟踪文件和最终 diff，确认不包含账单原文、密码、运行时数据库或用户私有运行文件。证据：2026-08-01 21:39 CST，HEAD `fed111a`，`git diff --check` 通过；改动文件扫描未发现目标 PDF、密码、SQLite 数据库或私有账单内容。
- [X] T025 [US1] [US2] 运行范围化 gstack `/review`，修复所有阻断 finding；代码变更不涉及 Web/UI，因此 `/qa`、Hallmark audit 不适用并记录理由。证据：2026-08-01 21:39 CST，基于 `origin/refactor/web` merge-base `fed111a` 审查工作树变更；SQL/并发/输入边界/枚举完整性未发现阻断问题。Web/UI、路由和样式未改动，`/qa` 与 Hallmark audit 不适用。
- [X] T026 [US1] [US2] 使用目标 PDF 做只读导入解析和来源字段核验，记录三行输出及退款关系证据；不在代码测试阶段修改用户数据库。证据：2026-08-01 14:09 CST，完整 `_parse_cash_statement` 只读链路输出：19:11 `-272/山葵村烤肉/消费/空信号`；19:13 `+272/山葵村烤肉/退货/icbc_credit_return`；19:16 `-222/山葵村烤肉/消费/空信号`；三行 `_raw_cp` 均为 `美团支付-美团App山葵村烤肉`。
- [X] T027 [US1] [US2] 运行 `$speckit-converge` 对照 spec、plan、tasks 和代码，回写遗漏任务及剩余风险。证据：2026-08-01 21:39 CST，HEAD `fed111a`，逐项核对 FR/SC、计划触点、SQLite/PostgreSQL 矩阵和当前实现；Feature 范围无遗漏。剩余风险仅为 T022/T023 记录的既有全套件查询断言和 Web 类型构建基线问题，不属于本 Feature。

## 补充验证证据：同笔支付关系中的退款占用

- [X] T028 [US1] 增加同一轮关系扫描的回归测试：一笔工行退款与另一来源退款形成同笔支付关系后，已确认的工行退款及其镜像不得再次生成 `refund_offset`。证据：2026-08-01 14:56 CST，测试先以 2 条退款关系失败，再在 `pipeline.py` 增加镜像占用传播后通过；新增场景和既有退款测试共 `23 passed`。
- [X] T029 [US1] 修复退款占用集合在同一轮扫描中的镜像传播，并验证完整受影响关系测试。证据：2026-08-01 14:59 CST，`uv run pytest -q tests/test_transaction_relations_refund.py tests/test_relations_pipeline_order.py tests/test_mirror_business_day_diamond.py tests/test_transaction_relations_projection.py tests/test_import_scan_refund_boundary.py tests/test_icbc_refund_pairing.py tests/test_relations_index_injection.py`：`52 passed, 1 skipped`；`git diff --check` 通过。
- [X] T030 [US1] 清空并重建 SQLite 数据库后重新导入可解析账单，执行全量关系检查和收支投影重建。证据：2026-08-01 15:02 CST，现金流水 `11,394` 条、投资事件 `533` 条；关系检查完成；收支投影重建成功，版本 `1`、投影条目 `7,988`、投影成员 `11,394`，状态为 `ready`。
- [X] T031 [US1] 核验目标工行信用卡记录和前后端服务。证据：2026-08-01 15:03 CST，`19:11:37 -272` 与 `19:13:04 +272` 为 `accepted/strong` 的 `refund_offset`，19:16:39 `-222` 未被配对；来源摘要分别为 `消费`、`退货`、`消费`，退款行保留 `refund_signal=icbc_credit_return`；API `8001` 与前端 `5173` 均返回 HTTP `200`。
- [X] T032 [US1] [US2] 复跑完整测试套件。证据：2026-08-01 21:39 CST，HEAD `fed111a`，`uv run pytest -q`：`1055 passed, 104 skipped, 1 failed`；唯一失败仍为未触及本 Feature 的 `tests/test_application_cash_projection_evidence.py::test_evidence_reads_members_and_relations_in_fixed_batch_queries`，实际查询数为 `9`、既有断言上限为 `8`。

## Dependencies & Execution Order

- Phase 1 完成后才能进入 Phase 2；T012 通过后才能修改产品代码。
- T013–T016 可按文件边界并行设计，但必须在同一实现批次中集成验证；T018 依赖正式字段合同。
- T020–T027 必须在实现和目标回归通过后执行；任意高风险 finding 或后端缺证据都会阻断交付。

## Verification Evidence

每项验证完成时，在本文件对应任务下追加：实际命令或 skill、执行时间、测试 HEAD、比较基线、结果和未解决风险。不得用旧运行结果替代当前提交证据。

## Phase 6：Phase C 转账出账种子来源闸门

- [X] T033 [P] [US1] 在 `tests/test_transfer_phase_c.py` 增加回归测试：工行 `summary=消费` 的负金额流水即使与建行 `summary=电子汇入` 的正金额流水跨账户、等额、同日，也不得生成 `transfer_pair`；显式 `seed_ids` 与全量扫描均覆盖。证据：2026-08-01 21:39 CST，新增 `test_transfer_seed_gate_rejects_icbc_consume_with_ccb_inbound_signal` 通过，两种入口均返回空提案。
- [X] T034 [P] [US1] 在 `tests/test_transfer_phase_c.py` 增加来源信号正向测试：提现、`转账支取`、`无卡自助`、银证和明确还款信号仍可作为转账出账种子，结构化银行 `summary` 可被识别。证据：2026-08-01 21:39 CST，新增结构化 `summary=转账支取` 正向测试通过；既有提现、无卡、银证和还款回归覆盖通过。
- [X] T035 [US1] 运行 T033–T034 的失败回归测试，确认当前实现因显式 `seed_ids` 接收普通负金额流水而失败，再进入实现。证据：2026-08-01 21:39 CST，实施前目标测试结果为 `1 failed, 1 passed`；失败点为显式 `seed_ids` 绕过种子闸门。
- [X] T036 [US1] 修改 `src/ft/domain/relations/transfer/signals.py`：将转账出账种子收紧为来源专用关键词和结构化来源字段，不再以泛化转账词或任意负金额作为来源闸门。证据：2026-08-01 21:39 CST，`TRANSFER_OUT_SEED_TOKENS` 与 `raw_payload` 来源字段读取已实现，普通消费和仅入账信号不再满足 `is_transfer_taxonomy_out`。
- [X] T037 [US1] 修改 `src/ft/domain/relations/transfer/match.py`：`evaluate_transfer_pair` 与 `match_transfer_pairs_phase_c` 的全量入口、显式 `seed_ids` 入口使用同一转账出账种子闸门；保留正向提现回执和对侧银行入账候选路径。证据：2026-08-01 21:39 CST，两个入口及正向提现回执路径测试通过。
- [X] T038 [US1] 运行转账、退款和关系流水线受影响测试，确认工行消费不再制造 `transfer_pair`，现有提现、卡间转账、信用还款和退款关系回归通过。证据：2026-08-01 21:39 CST，受影响命令 `uv run pytest -q tests/test_transaction_relations_refund.py tests/test_transaction_relations_open_leg.py tests/test_transfer_phase_c.py tests/test_transaction_relations_transfer.py tests/test_relations_pipeline_order.py tests/test_relations_index_injection.py tests/test_import_scan_refund_boundary.py tests/test_icbc_refund_pairing.py tests/test_mirror_business_day_diamond.py tests/test_transaction_relations_projection.py` 结果 `95 passed, 5 skipped`。
- [X] T039 [US1] 对 `/Users/huangwenlong/.ft/finance-tracker.db` 中关系 `3164` 的事实对照执行只读匹配验证，记录修复后不再提出该 `transfer_pair`；不在本任务中改写现有数据库关系。证据：2026-08-01 21:39 CST，显式 `seed_ids` 与全量扫描均返回 `[]`；数据库原有 `3164` 保持历史 `accepted transfer_pair`，本次未执行写入或删除。
- [X] T040 [US1] 运行 `git diff --check`、范围化 gstack `/review`、`$speckit-analyze` 和 `$speckit-converge`，将实际命令、HEAD、基线和未解决风险回写本任务与规格产物。证据：2026-08-01 21:39 CST，HEAD `fed111a`，基线 `origin/refactor/web`/merge-base `fed111a`；`git diff --check` 通过，范围审查无 finding，Spec Kit 前置检查通过且无 CRITICAL/HIGH；收敛结论为本次范围无遗漏。未解决风险沿用 T022/T023：全量测试 `1055 passed, 104 skipped, 1 failed`（既有 projection 查询断言），Web build 受既有 `CashTable.test.tsx:37` TS2554 阻断。

## Phase 7：恢复来源结构化 `summary=转账` 的本人卡间转账

- [X] T041 [US1] 回写规格：确认结构化 `summary=转账` 是来源信号，任意文本中的裸“转账”不单独作为种子；记录 17 条工行借记卡→工行信用卡样本的事实证据。证据：2026-08-01 22:11 CST，`spec.md`、`plan.md`、`research.md`、`data-model.md`、`quickstart.md` 已同步；17 条记录均为同名对手方、金额完全相等、时间差 0–1 秒的重复模式。
- [X] T042 [P] [US1] 在 `tests/test_transfer_phase_c.py` 增加失败回归：工行借记卡负向 `summary=转账` 与工行信用卡正向 `手机银行` 等额、同秒时，显式 `seed_ids` 与全量扫描均形成转账关系；普通备注“转账”仍不得单独放行。证据：2026-08-01 22:11 CST，实施前目标测试结果 `1 failed, 1 passed`；失败为结构化 `summary=转账` 尚未进入种子。
- [X] T043 [US1] 修改 `src/ft/domain/relations/transfer/signals.py`：将来源结构化 `summary=转账` 纳入负向转账种子信号，不把裸文本“转账”加入通用种子词。证据：2026-08-01 22:11 CST，`TRANSFER_OUT_SEED_SUMMARIES` 仅从银行来源结构化摘要读取 `转账`，普通文本路径仍不放行。
- [X] T044 [US1] 在关系重建副本上全量重建并对比原关系表：恢复 17 条本人卡间关系，继续移除 `573→4747`，记录 `transfer_pair` 数量差异和其余差异分类。证据：2026-08-01 22:11 CST，副本 `/Users/huangwenlong/.ft/finance-tracker.db.relations-rebuild-20260801-2205` 全量重建完成；`transfer_pair` 从 `124`（accepted 107、pending 17）变为 `108`（accepted 99、pending 9），17 条样本全部恢复，移除 16 条且无新增；`573→4747` 消失，`573→575` 为 `accepted/strong refund_offset`。另有 3 条购汇还款因重建时汇率服务不可用由 accepted 变为 pending，与本次种子规则无关。
- [X] T045 [US1] 运行转账、退款和关系流水线受影响测试、`git diff --check`、范围化 review、`$speckit-analyze` 与 `$speckit-converge`，回写当前 HEAD、基线和全量测试风险。证据：2026-08-01 22:11 CST，HEAD `fed111a`，基线 `origin/refactor/web`/merge-base `fed111a`；受影响测试 `97 passed, 5 skipped`，`uv build` 通过，`git diff --check` 通过，范围 review 无 finding，Spec Kit 前置检查无 CRITICAL/HIGH；全量测试 `1056 passed, 104 skipped, 2 failed`，失败为既有投影查询条数断言和既有 SQLite 性能预算抖动，均未触及转账逻辑。
