# Tasks: 关系配对使用正式记录类型

**变更等级**: A 类完整 Feature。原因：改变财务关系匹配和持久化关系结果。

## Phase 1：规格与门禁

- [X] T001 完成 `spec.md`、`research.md`、`plan.md`、`data-model.md`、`contracts/` 和 `quickstart.md`。
- [X] T002 完成 Spec Kit analyze：已检查 `check-prerequisites.sh --json --require-tasks --include-tasks` 输出，确认类型闸门、角色、异常边界、测试和重建对比均有覆盖；未发现 CRITICAL/HIGH 缺口。

## Phase 2：失败测试

- [X] T003 [P] 增加 `FactView.record_type` 传递和类型角色谓词测试。
- [X] T004 [P] 增加“文本/summary 不能绕过 record_type”的退款、转账、还款和镜像测试。
- [X] T005 [P] 增加同账户退款候选索引测试，以及微信负向原消费 `refund` 特例测试。
- [X] T006 运行新增测试；实现前 7 条门禁测试失败，原因是关系层没有 `FactView.record_type`，实现后转绿。

## Phase 3：实现

- [X] T007 为 `FactView` 和 `_fact_view_from_row` 接入 `record_type`。
- [X] T008 新增类型角色谓词，移除 Phase C 的 summary 一级类型判断和退款/还款文本一级类型判断；关系层不再从 `raw_payload.record_type` 回退读取。
- [X] T009 修改 Phase A/B/C/D 及候选索引使用正式类型闸门，保留商户、订单、P2P 子类等二级证据；退款候选生成即限制为同账户。
- [X] T010 更新受影响关系测试 fixture 显式提供 `record_type`，不再用文本推导测试中的一级类型。

## Phase 4：验证与重建

- [X] T011 验证记录：定向关系测试 `110 passed, 6 skipped`；完整 `uv run pytest -q` 为 `1083 passed, 105 skipped`；`uv run python -m compileall -q src tests` 和 `git diff --check` 通过。`npm run build` 已执行但被既有 `web/tests/CashTable.test.tsx:37` 的 `onEvidence` 参数类型错误阻断，与本 Feature 无关。
- [X] T012 SQLite/真实 PostgreSQL 契约矩阵：`uv run pytest -q tests/contract/test_dual_backend_record_type.py tests/contract/test_dual_backend_icbc_refund_pairing.py` 为 `2 passed, 2 skipped`；因 `FT_TEST_POSTGRES_URL` 未设置，真实 PostgreSQL 按补跑命令跳过。
- [X] T013 在数据库副本上完成旧/新规则关系重建，对比关系数量、类型、状态、端点和规则差异；证据已写入 `research.md`，正式库未写入。
- [X] T014 收敛检查和短范围人工代码审查完成：类型来源、同账户退款候选、负向退款原消费特例和无兼容回退均已核对；未解决风险为既有 Web 构建类型错误及未配置真实 PostgreSQL。

## Phase 5：撤销与提现类型边界

- [X] T015 更新关系角色合同：`reversal` 不进入退款关系，提现类型不伪装成普通 `transfer_out`。
- [X] T016 保留支付平台提现到账的专用 `transfer_pair` 路径，并增加正式类型回归测试。
- [X] T017 运行关系回归、SQLite/真实 PostgreSQL 合同矩阵、全量测试和收敛检查；全量 `uv run pytest -q` 为 `1091 passed, 105 skipped`，合同测试 SQLite 通过、真实 PostgreSQL 因未配置 `FT_TEST_POSTGRES_URL` 跳过；当前业务库未做隐式重建。

## Phase 6：提现方向拆分

- [X] T018 更新关系角色合同，使用 `withdrawal_out` 和 `withdrawal_in` 明确提现配对方向；提现出账仅作为专用出账种子，提现入账仅作为专用正向对侧。
- [X] T019 增加提现出账 → 入账的关系回归测试，并确认普通转账路径不会接收提现类型；`tests/test_record_type_relation_gates.py` 已覆盖。
- [X] T020 运行关系回归、双后端合同、全量测试和收敛检查；关系/合同定向测试通过，全量 `uv run pytest -q` 为 `1098 passed, 108 skipped, 1 warning`，`compileall` 和 `git diff --check` 通过；重建后关系表为 2,600 条，投影状态为 `ready`。

## Phase 7：P0 路线收紧与 P2P 撤销语义

- [X] T021 以 Living Spec 更新 `spec.md`、`plan.md`、`research.md`、`data-model.md`、`contracts/record-type-relations.md`、`quickstart.md` 和 `DOMAIN_GLOSSARY.md`：普通转账、提现到账、信用账户还款互斥；P2P 退回归 `transfer_reversal`；本轮只使用临时重建库。
- [X] T022 [P] [US2] 在 `tests/test_record_type.py` 与 `tests/test_platform_refund_matchers.py` 先增加 P2P 退回归 `transfer_reversal`、不生成消费退款配对，以及建行 `无卡自助交易` / `无卡支付` 归 `consumption` 的失败回归测试。实现前定向组出现 11 个预期失败；实现后同一组为 `84 passed, 2 skipped`。
- [X] T023 [P] [US2] 在 `tests/test_record_type_relation_gates.py`、`tests/test_transfer_phase_c.py` 和 `tests/test_transaction_relations_payment_mirror.py` 先增加三条路线类型矩阵、提现不得配对平台余额、普通转账不得候选信用账户入账、日期型无同笔证据不得自动确认的失败回归测试。受影响关系回归组为 `138 passed, 9 skipped`。
- [X] T024 [US2] 修改 `src/ft/domain/record_type.py`、`src/ft/domain/relations/core/record_types.py`、`src/ft/domain/relations/core/types.py`、`src/ft/domain/relations/{mirror,refund,transfer}/` 和 `src/ft/domain/platform_refund.py`，按合同实现导入分类与关系路线闸门。
- [X] T025 [US2] 运行新增回归测试，确认实现前失败、实现后通过，并复核 P2P `transfer_reversal` 不会进入 `refund_offset`。临时全量库中 8 条 `transfer_reversal` 参与任何关系的数量为 0。
- [X] T026 [US2] 用 `.ft/bills` 在临时 SQLite 库全量重导入、重建关系和收支投影；对比关系类型、状态、端点与旧库，业务库不得被替换。导入 11,394 条现金流水和 497 条证券流水；第二次全量关系扫描后统计稳定，收支投影为 `ready`，包含 8,328 个投影和 11,394 个成员。
- [X] T027 运行 SQLite 与真实 PostgreSQL 契约矩阵、受影响测试、完整 `uv run pytest -q`、`compileall`、构建和 `git diff --check`；未配置真实 PostgreSQL 时记录补跑命令。最终关系回归组为 `117 passed, 2 skipped`，完整测试为 `1113 passed, 109 skipped, 1 warning`，迁移与双后端契约组为 `34 passed, 6 skipped`，`uv run python -m compileall -q src tests` 与 `git diff --check` 通过。Web 的 `npm run build` 在 `web/` 目录被既有的 `web/tests/CashTable.test.tsx:37`（`onEvidence` 参数数量）阻断；真实 PostgreSQL 因未配置 `FT_TEST_POSTGRES_URL` 跳过，补跑命令为 `FT_TEST_POSTGRES_URL='<PostgreSQL URL>' uv run pytest -q tests/contract/test_dual_backend_record_type.py tests/test_postgres_statement_import.py`。
- [X] T028 运行 `$speckit-analyze`、`$speckit-converge` 和范围化 gstack `/review`；Web QA 与 Hallmark 审计不适用，因为本轮不改 Web 行为或样式。分析覆盖 US1–US3、路线合同、迁移和 A 类门禁，未发现 CRITICAL/HIGH 缺口；收敛检查没有追加任务。审查发现并已补齐 4 个正式类型绕过点：平台提现入账信号、银行提现对侧筛选、确定性支付镜像分组和平台退款硬键提议；相应回归已纳入 T027 的测试结果。

## Phase 8：多候选部分退款最近匹配

- [X] T029 将多候选部分退款的边界写入 `spec.md`、`plan.md` 和本任务记录：只比较正式强匹配候选，退款额严格小于剩余金额，选择最近且唯一者，并列、全额或超额保持待审核。
- [X] T030 [P] [US3] 先增加失败回归测试：多个强候选中最近且唯一的部分退款自动配对；全额退款多候选不自动配对；最近时间并列保持待审核；同一消费在同一扫描中可接收不超过剩余金额的连续部分退款。首批新增测试先得到 3 条预期失败，随后补充的“唯一标题精确但全额多候选”边界测试也先失败，修复后退款关系定向组通过。
- [X] T031 [US3] 在 `src/ft/domain/relations/refund/match.py` 实现多候选部分退款的唯一最近候选选择，并记录候选证据；不改变普通全额退款多候选的待审核语义。
- [X] T032 [US3] 在 `src/ft/domain/relations/pipeline.py` 和应用层同步同一扫描内的退款剩余金额与占用状态，避免合法的后续部分退款被前一笔退款永久排除；补充关系占用和剩余金额回归测试。
- [X] T033 [US3] 运行退款/关系受影响测试、完整测试、SQLite 临时库重建、`compileall`、`git diff --check` 和范围化 gstack `/review`；受影响关系组 `134 passed, 7 skipped`，完整测试 `1118 passed, 110 skipped, 1 warning`，编译和 diff 检查通过。清空关系后的临时库连续三轮关系扫描稳定为 `refund_offset` 310 自动确认、46 待审核，其中 120 条带 `partial_nearest_unique`；支付镜像 2,799/161，转账关系 42/2；收支投影状态为 `ready`，8,243 个投影、11,394 个成员。真实 PostgreSQL 因未配置 `FT_TEST_POSTGRES_URL` 跳过；Web 构建仍受既有 `web/tests/CashTable.test.tsx:37` 类型错误阻断，均与本轮逻辑无关。

## Phase 9：全额退款最近匹配与镜像事件折叠

- [X] T034 [US3] 更新 `spec.md`、`plan.md`、`research.md`、`data-model.md`、关系合同和验证指南：普通退款候选/自动窗口改为 15 天，锁定证据仍为 30 天；部分与全额退款均允许镜像折叠后的最近且唯一候选；最近并列保持 pending。
- [X] T035 [P] [US3] 先增加失败回归测试：多个镜像流水代表同一消费事件时只计一个候选；多个全额退款候选中最近经济事件唯一时自动配对；最近经济事件并列时保持 pending；部分退款规则不回退。先观察到旧实现 6 条失败，修复后退款定向组通过。
- [X] T036 [US3] 在 `src/ft/domain/relations/pipeline.py` 接入已确认 `payment_mirror` 的候选事件分组，在 `src/ft/domain/relations/refund/match.py` 实现证据优先级、全额/部分最近唯一选择和 15 天普通窗口；保持同账户、金额剩余和类型硬闸门。
- [X] T037 [US3] 更新关系证据，区分 `partial_nearest_unique` 与 `full_nearest_unique`，按经济事件记录 `candidate_count` 和代表事实 ID；补充同一扫描与跨扫描剩余金额回归。
- [X] T038 [US3] 在临时 SQLite 库用 `.ft/bills` 的全部现金账单全量重导入、重建关系与投影，核对 `自助侠` 候选、全库 `refund_offset` auto/pending、关系类型/端点稳定性和投影状态；临时库现金流水 11,394 条，关系扫描稳定为 `refund_offset` 335/12，投影为 `ready`（8,952 条、11,394 个成员），正式业务库未写入。
- [X] T039 运行受影响测试、完整测试、SQLite 契约矩阵、`compileall`、`git diff --check`、范围化 gstack `/review`、`$speckit-analyze` 和 `$speckit-converge`。新增 30 天锁定窗口回归后，定向组 `139 passed, 14 skipped`，完整测试 `1123 passed, 110 skipped, 1 warning`；真实 PostgreSQL 因未配置 `FT_TEST_POSTGRES_URL` 跳过，补跑命令为 `FT_TEST_POSTGRES_URL='<PostgreSQL URL>' uv run pytest -q tests/contract/test_dual_backend_record_type.py tests/test_postgres_statement_import.py`。Codex review 未发现本轮退款匹配实现问题；提出的旧库 `record_type` 回填、旧 `withdrawal` 升级和降级映射均属于兼容逻辑，按本 Feature 已确认的“不提供旧库兼容、重新建库导入”约束拒绝，并将“从当前 head 新建库”记录为重建前置条件。Web QA/Hallmark 不适用，因为本轮未改 Web 行为或样式。

## Phase 10：超额退款候选硬过滤

- [X] T040 [US3] 将“退款金额大于当前可退余额”的边界从“不自动确认”收紧为“候选阶段直接排除”，同步 `spec.md`、`plan.md` 和关系证据约束。
- [X] T041 [US3] 先增加失败回归测试：单个超额消费不生成退款关系；超额消费与合法消费同时存在时，超额消费不进入 `candidate_count`、候选事实 ID 或最近候选排序。两个测试先得到预期失败，再转绿。
- [X] T042 [US3] 在 `src/ft/domain/relations/refund/match.py` 前移剩余金额硬闸门，移除超额候选的双边/开放待审核路径。
- [X] T043 [US3] 运行退款关系定向测试、受影响关系测试、完整测试、`compileall`、`git diff --check` 和范围化 gstack `/review`；在临时 SQLite 重建库中核对超额候选和 pending 数量，正式业务库不写入。

  实际验证：`uv run pytest -q tests/test_transaction_relations_refund.py -k 'over_refund'` 为 `2 passed`；受影响关系组为 `140 passed, 14 skipped`；完整 `env -u FT_DATABASE_URL -u FT_WORKSPACE_ID uv run pytest -q` 为 `1124 passed, 110 skipped, 1 warning`；`uv run python -m compileall -q src tests` 与 `git diff --check` 通过。临时 SQLite `/tmp/finance-tracker-overfilter.IpBMD1/finance-tracker.db` 全量关系扫描后，`refund_offset` 为 `337 accepted / 8 pending_review`，`over_refund` 关系为 `0`，退款 pending 全部为开放待配对，收支投影为 `ready`（8,951 条投影、11,394 个成员）。范围审查未发现本次 `match.py` 硬过滤的可执行问题；完整 dirty worktree 审查另发现既有投影口径和迁移降级兼容问题，均不属于本次范围，且迁移兼容与本 Feature 的无兼容约束冲突。
