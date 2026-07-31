# 任务：工行卡退货退款信号

**输入**：`spec.md`、`plan.md`、`research.md`、`data-model.md`、`contracts/icbc-credit-refund-signal.md`

**实施原则**：所有可执行变更先写会因目标行为缺失而失败的测试，再写最小实现。该功能不读取、不回填、不迁移历史账本记录；用户需要重新导入受影响账单。

## 阶段 1：基础确认

- [X] T001 确认独立分支 `fix/icbc-credit-refund-signal`、干净基线和 Spec Kit 版本 `0.12.17`；不触碰主工作树已有变更。
- [X] T002 完成术语登记，使用 `DOMAIN_GLOSSARY.md` 中的“账单摘要”“退款信号”“来源行快照”。
- [X] T003 完成 `specify → clarify → plan`，并记录工程方案评审结论；明确不实现历史兼容分支。

## 阶段 2：用户故事 1——重新导入后识别工行卡退货（P1）

**目标**：工行信用卡或借记卡原生“摘要=退货”的正数行在重新导入后，进入既有退款关系第 D 阶段；对手方和备注文本不必出现退款关键词。

**独立验收**：去标识化的 `+272.00` 工行信用卡或借记卡退货行导入后，来源行快照分别保留 `summary=退货` 与 `refund_signal=icbc_credit_return` / `icbc_debit_return`；关系检查针对同卡、同币种和时间窗口内的 `-272.00` 消费返回唯一 `refund_offset` 候选。

### 先行测试（必须先让测试失败）

- [X] T004 [P] [US1] 在 `tests/test_convert.py` 增加工行信用卡和借记卡行级回归测试：`摘要=退货` 的解析结果保留 `summary`、来源专用 `refund_signal`，而 `摘要=退款` 不生成正式信号。
- [X] T005 [P] [US1] 在 `tests/test_statement_import_mapping.py` 增加正式导入快照测试：两类工行来源输出的字段进入 `source_payload`，非退货正数行不带退款信号。
- [X] T006 [P] [US1] 在 `tests/test_relations_index_injection.py` 增加候选索引回归测试：两类工行来源行快照含对应正式信号时能进入退款桶；其他来源、缺失信号和畸形 payload 不能进入退款桶。
- [X] T007 [P] [US1] 在 `tests/test_transaction_relations_refund.py` 增加规则回归测试：无“退款/退货”文本但有两类工行结构化信号的正数事实可生成既有第 D 阶段候选；只有 `summary` 或 `offset_source` 的记录不兼容；普通工资收入和 P2P 排除不被放宽。
- [X] T008 [US1] 在 `tests/test_import_scan_refund_boundary.py` 增加 SQLite 关系服务集成测试：重新导入生成的来源快照可被关系检查消费，并且重复执行不产生重复 `refund_offset`。
- [X] T009 [P] [US1] 在 `tests/contract/test_dual_backend_icbc_credit_refund_signal.py` 使用项目现有双后端 fixture，分别验证信用卡与借记卡在 SQLite 和真实 `FT_TEST_POSTGRES_URL` 上得到等价退款候选、状态和幂等结果；未配置真实 PostgreSQL 时显式 skip 并记录补跑命令。

### 最小实现

- [X] T010 [US1] 在 `src/ft/convert.py` 的工行信用卡和借记卡原始行输出中保留原生摘要为 `summary`，仅对正数 `summary=退货` 输出对应的正式信号；不改变现有 `_refund_signal` 转换跟踪和账务字段。
- [X] T011 [US1] 在 `src/ft/adapters/statement_import.py` 的现金行元数据保留路径中传递 `summary` 与 `refund_signal`，使既有整行 JSON 快照保存它们；不添加历史回填或兼容读取。
- [X] T012 [US1] 在 `src/ft/domain/relations/refund/signals.py` 增加来源受限的事实级退款信号判定：仅接受两类工行来源、正数现金事实和对应正式 `refund_signal`；只有 `summary` 或历史字段的记录均返回 false。
- [X] T013 [US1] 将 `RefundTextGates.has_refund_signal` 在 `src/ft/domain/relations/core/types.py` 的注入接口调整为接收 `FactView`，并更新 `FactCandidateIndex` 的退款入桶和种子门控调用。
- [X] T014 [US1] 在 `src/ft/domain/relations/refund/match.py` 使用同一事实级退款信号判定替换正数退款种子门控；保留文本退款信号、P2P 排除、商户/订单匹配和现有第 D 阶段阈值。
- [X] T015 [US1] 在 `src/ft/domain/relations/pipeline.py`、Diamond 兜底与需要的关系种子辅助处复用事实级门控，确保关系扫描入口、候选索引和最终判定一致；不改变导入时关系写入边界。
- [X] T016 [US1] 删除或拒绝任何 `offset_source` 历史兼容读取实现，补充断言确保新规则不依赖该字段。

**检查点**：T004–T009 在实现前至少有目标行为失败；T010–T016 完成后 US1 的解析、快照、索引、关系和幂等测试全部通过。

## 阶段 2 补充：工行借记卡来源规则收敛

- [X] T024 [US1] 根据用户澄清更新 Living Spec、Plan、Data Model 和 Contract：工行借记卡与信用卡采用相同的 `summary=退货` 精确条件，但使用来源专用正式信号；不新增历史兼容读取。
- [X] T025 [US1] 先行添加借记卡解析、导入映射、候选索引、第 D 阶段和双后端契约测试；实现前目标测试结果为 `5 failed, 2 passed`，失败均对应借记卡字段或关系门控缺失。
- [X] T026 [US1] 实现借记卡 `summary` / `refund_signal` 传播，并让事实级关系门控识别 `icbc_debit_return`；工行信用卡与借记卡均禁止仅凭文本、旧 `offset_source` 或仅有 `summary` 的历史快照进入新信号路径。
- [X] T027 [US1] 运行补充受影响测试和双后端契约测试，确认两类工行来源的解析、快照、索引、关系和幂等行为。

## 阶段 3：规格一致性与交付门禁

- [X] T017 [P] 完成 Spec/Plan/Tasks 手工一致性复核；当前宿主未暴露 `$speckit-analyze` Skill 调用入口，已核对范围、需求覆盖、无兼容决策和 HIGH/CRITICAL 风险，未发现阻断项。
- [X] T018 [P] 运行新增测试、受影响测试和完整 `uv run pytest -q`；完整套件结果为 `1049 passed, 104 skipped, 1 failed`，唯一失败是既有 `tests/test_wealth_performance.py::test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets[sqlite]` 的性能预算波动（5.70s > 5.00s），不涉及本次变更；受影响套件已重新通过。
- [X] T019 [P] 运行类型检查、lint、构建和 `git diff --check`；`git diff --check` 与 `python -m compileall -q src tests` 通过；当前虚拟环境缺少 `ruff`、`mypy`、`build`，对应命令无法运行，需在完整开发环境补跑。
- [X] T020 [P] 运行 SQLite/PostgreSQL 关系契约测试：SQLite 通过，PostgreSQL 因未设置 `FT_TEST_POSTGRES_URL` 显式 skip；未以 SQLite 结果冒充 PostgreSQL 通过。
- [X] T021 [P] 完成短范围 gstack `/review` 主审查及借记卡补充范围复核：逐项检查来源范围、金额/关系安全、字段传播、候选索引、Diamond 入口、测试和无兼容分支；未发现阻断性问题；两个只读 specialist 代理在等待窗口内未返回，未将其视为通过证据。
- [X] T022 [P] 完成适用性检查：本功能无 Web、UI、路由、外部回调、部署或独立性能目标，因此 `/qa`、Hallmark、`/benchmark`、`/cso` 不适用；完整套件已有独立性能测试失败，已按原样记录，不归因于本功能。
- [X] T023 完成 Spec/Plan/Tasks 与代码的手工收敛复核；规格、数据模型、合同、测试和实现均已对齐，未发现追加任务。
- [X] T028 完成借记卡规则补充后的二次手工收敛复核；确认正式信号值、来源范围、精确摘要条件和无兼容决策一致，未发现追加任务。

## 任务依赖与执行顺序

```text
T001–T003
   ↓
T004–T009（先行测试，可按文件并行）
   ↓
T010–T016（最小实现；T012 先于 T013–T015）
   ↓
T024–T026（用户澄清后的借记卡规则补充）
   ↓
T017
   ↓
T018–T022（验证可并行，但最终结果汇总到 T023）
   ↓
T023 → T028
```

T004–T009 之间仅在不同测试文件写入时可并行；T010–T016 共享关系接口和数据流，按编号顺序实施，避免候选索引与最终判定短暂分叉。

## 验证证据

完成后在对应任务下记录：命令或 skill、结果、执行时间、当前 HEAD、比较基线（如适用）和未解决风险。不得使用计划、旧结果或未运行的 PostgreSQL 结果替代实际证据。

### 实际执行记录

- 执行时间：2026-08-01 03:20–03:42（Asia/Shanghai）；当前 HEAD：`6a62a1e`（本地未提交工作树）；比较基线：`refactor/web` 工作树基线。
- 原补充测试：先行运行结果为 `5 failed, 2 passed`；实现后目标测试为 `7 passed`。
- 受影响测试：`uv run pytest -q tests/test_convert.py tests/test_statement_import_mapping.py tests/test_postgres_statement_import.py tests/test_relations_index_injection.py tests/test_transaction_relations_refund.py tests/test_import_scan_refund_boundary.py tests/test_import_no_relation_write.py tests/test_mirror_business_day_diamond.py tests/contract/test_dual_backend_icbc_credit_refund_signal.py`，结果 `287 passed, 3 skipped in 3.58s`。
- 真实样本校准：`_prepare_convert_rows` 解析工行信用卡 PDF 得到 347 行、22 条 `refund_signal=icbc_credit_return`；截图对应 `2026-05-25 19:13:04` 行为 `272.00 / summary=退货`。
- 全量测试：`uv run pytest -q` 得到 `1049 passed, 104 skipped, 1 failed in 238.84s`；失败仅为既有 `tests/test_wealth_performance.py::test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets[sqlite]` 的 SQLite 性能预算波动（本次为 `5.70s > 5.00s`），不涉及退款变更回归。
- 静态检查：`git diff --check`、`python -m compileall -q src tests` 通过；`ruff`、`mypy`、`python -m build` 因当前环境未安装对应工具失败。
- 双后端：新增契约测试覆盖信用卡与借记卡；SQLite 均 `passed`，PostgreSQL 均 `skipped`（未配置 `FT_TEST_POSTGRES_URL`）；补跑命令为 `FT_TEST_POSTGRES_URL=... uv run pytest -q tests/contract/test_dual_backend_icbc_credit_refund_signal.py`，URL 必须指向专用 `_test` 数据库。
