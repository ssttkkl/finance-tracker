## 1. 思考与计划一致性

- [x] 1.1 读取项目上下文、领域词表、相关主规格、活动变更、关系实现、投资导入器与本地 SQLite 的去标识化统计，确认这是 A 类持久化与财务语义变更。
- [x] 1.2 更新领域词表，并建立 proposal、delta 规格与设计，明确 `action` 是投资事件的记录类型而非第二个业务维度。
- [x] 1.3 在实现前复核本 change 的所有 artifact，固定 `record_type` / `record_subtype` 枚举、历史回填映射和不支持来源行的失败关闭合同。

## 2. 失败测试与迁移契约

- [x] 2.1 先添加 SQLite 与真实 PostgreSQL 迁移测试：`investment_events.action` 不再存在，`record_type` 与 `record_subtype` 可读写，历史行的金额、币种、来源行快照、幂等键和快照重放结果保持一致。
- [x] 2.2 先添加投资导入与重放失败测试：利息、税费、外汇净额、奖励和出金冲回不得生成 `deposit` / `withdraw`；外部出入金与内部子账户调拨必须得到不同子类型。
- [x] 2.3 先添加 SQLite 与真实 PostgreSQL 资金调拨关系契约测试，覆盖唯一自动确认、多候选待审核、端点占用、重复扫描、人工确认/驳回、跨工作区拒绝和来源无关规则。
- [x] 2.4 先添加收支投影与 Web/API 契约测试，覆盖已确认资金调拨显示为 `bank_security_transfer`、不计入收支，以及未确认或驳回候选不改变投影。

## 3. 投资事件规范化与持久化

- [x] 3.1 新增双后端 Alembic 迁移：将 `investment_events.action` 重命名为 `record_type`，增加受约束的 `record_subtype`，并维护模型、索引、仓储、DTO、查询、CLI/API 序列化与精确十进制合同。
- [x] 3.2 在投资领域集中定义并验证投资事件记录类型与记录子类型；让 `deposit` / `withdraw` 只接受 `external_funding` 或 `subaccount_transfer`，其他记录类型拒绝不合法子类型。
- [x] 3.3 将投资命令、导入服务、持仓重放、财富事实、运行时查询和测试从 `action` 迁移为 `record_type`，不改变既有资产组成、成本和快照计算。
- [x] 3.4 修订 DFZQ、盈立、IBKR、Schwab 等导入器的规范化映射；将利息、税费、外汇净额、奖励和出金冲回落为非出入金语义，并为未知语义失败关闭。
- [x] 3.5 实现可审计历史回填入口与测试夹具：从 `source_payload` 重算规范类型，不丢失来源、幂等身份或 Decimal 精度；真实 `.ft` 数据迁移仅在获得明确授权后执行。

## 4. 现金—投资资金调拨关系

- [x] 4.1 新增双后端 `cash_investment_funding_relations` 持久化模型、Alembic 迁移、复合外键、活动唯一约束、状态字段和受限证据字段。
- [x] 4.2 实现来源无关的候选索引与匹配器，只消费规范字段、账户类型、精确金额、币种和 `Asia/Shanghai` 业务日。
- [x] 4.3 实现关系应用服务与仓储：幂等扫描、待审核候选更新、自动确认、人工确认/驳回、端点互斥、人工决定保护和事务内投影维护。
- [x] 4.4 提供可发现的 CLI/API 查询与决定入口，并以字段白名单返回关系证据，不暴露来源行快照、账号或任意原始备注。

## 5. 收支投影与兼容行为

- [x] 5.1 扩展收支投影输入与摘要，使已确认资金调拨的唯一现金端点成为 `internal_transfer(bank_security_transfer)`，同时保持每条有效现金流水恰好归属一个投影。
- [x] 5.2 在关系变化、现金流水逻辑删除和全量重建时原子维护受影响投影；确保未确认、被拒绝或已取代关系不改变收支、收入或月度汇总。
- [x] 5.3 更新收支账本响应与证据详情，展示银证转账及最小化关系证据，并保持已有现金—现金关系和 Web 契约兼容。

## 6. 审查

- [x] 6.1 完成产品与范围复核，确认仅配对外部出入金，不把内部子账户调拨或现金调整扩展到本次范围。
- [x] 6.2 完成工程与安全复核，检查迁移回滚、精确 Decimal、工作区隔离、外键、唯一约束、敏感来源数据和失败关闭路径。
- [x] 6.3 完成最终 diff 复核，检查 artifact、主规格同步需求、测试遗漏、公共契约兼容性和无关变更。

## 7. 测试、QA 与交付证据

- [x] 7.1 运行受影响单元、集成和双后端 PostgreSQL 契约矩阵，并记录命令、`HEAD`、比较基线、时长和结果。
- [x] 7.2 运行收支投影与 Web API 测试、生产构建、Playwright 主流程/空状态/错误状态/键盘/响应式检查；UI 有改动时运行 `$hallmark audit` 并修复所有 critical 与 major finding。未改动前端视觉组件；按适用 Web QA 运行 Vitest、Playwright 主流程和生产预览。
- [ ] 7.3 运行 `openspec validate --all --strict`、`openspec doctor`、完整回归、相称构建和 `git diff --check`；对未运行项目记录不适用理由、残余风险和补跑条件。严格校验、构建和差异检查已通过；真实 PostgreSQL 受影响矩阵已通过，完整回归仍受既有 SQLite 性能门槛失败影响。
- [x] 7.4 记录发布准备与回滚步骤：真实 `.ft` SQLite 迁移、PostgreSQL 迁移、投影重建和关系重扫均需用户明确授权后执行。

## 8. 反思

- [x] 8.1 记录导入规范化、跨账本关系和数据迁移中的可复用决策或防复发测试；重复出现的来源语义歧义更新词表和相关主规格。

## 审查与验证证据（2026-08-04）

- 实施起点 `HEAD`：`4fa34f0f95d69d04de3819bd0a07298b37113c78`；比较基线：`8c18ed7ecff6b31cd5adcc18becb4e4e09035f55`。后续合并 `origin/refactor/web` 的 `e119eec` 后，将迁移链调整为对方账号属性 `20260804_21`、投资记录类型 `20260804_22`、资金调拨关系 `20260804_23`，避免重复 revision ID；运行时 `SCHEMA_REVISION` 同步为 `20260804_23`。
- 产品与范围复核：通过。自动扫描仅接受 `deposit(external_funding)` 与 `withdraw(external_funding)`；`subaccount_transfer`、利息、税费、外汇净额、奖励、出金退款和未分类现金调整均不能进入候选。
- 工程与安全复核：通过，未发现阻断性 finding。金额比较使用 `Decimal`；模型与迁移均有记录类型/子类型检查约束；关系表以工作区复合外键和已确认端点唯一索引隔离端点；关系响应仅返回白名单证据，不复制来源行快照、账号或备注。受影响真实 PostgreSQL 矩阵已通过。
- 最终 diff 复核：通过，未发现阻断性 finding。`action` 仅保留为原始账单解析字段或来源行快照键，不再是投资事件持久化/API 字段；未实现的稳定参考号承诺已从 change 工件移除。
- SQLite 受影响矩阵：`uv run pytest tests/test_investment_record_type.py tests/test_investment_record_type_migration.py tests/test_cash_investment_funding_relations.py tests/unit/importers/test_dfzq_event_mapping.py tests/unit/importers/test_ibkr_map.py tests/unit/importers/test_schwab_map.py tests/unit/importers/test_usmart_hk.py tests/test_relational_cash_projection_evidence.py tests/test_application_web_queries.py -q`：`67 passed, 7 skipped`，6.89 s。
- 本机真实 PostgreSQL（PostgreSQL `17.10`，专用 `finance_tracker_test`）受影响矩阵：`FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' uv run pytest tests/test_investment_record_type.py tests/test_investment_record_type_migration.py tests/test_cash_investment_funding_relations.py tests/unit/importers/test_dfzq_event_mapping.py tests/unit/importers/test_ibkr_map.py tests/unit/importers/test_schwab_map.py tests/unit/importers/test_usmart_hk.py tests/test_relational_cash_projection_evidence.py tests/test_application_web_queries.py -q -rs`：`74 passed`，10.69 s。此前首次真实 PostgreSQL 运行发现迁移夹具将 SQLite 布尔字面量 `1` 写入 PostgreSQL `BOOLEAN` 列；已按方言改为 `TRUE` / `1`，迁移测试随后 `2 passed`，关系、投影与查询测试随后 `40 passed`。
- `uv build`、`uv run python -m compileall -q src`、`git diff --check`：通过。
- `openspec validate --all --strict`：`31 passed`；`openspec doctor`：通过。
- `cd web && npm test`：`35 passed`；`npm run build`：通过；`npm run test:e2e`：`3 passed`；`FT_PREVIEW_WEB_PORT=5174 npm run test:preview`：`1 passed`。端口 `5173` 已被既有开发服务器占用，未改动该进程。
- 本机 PostgreSQL 完整回归：`FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' FT_REQUIRE_TEST_POSTGRES=1 uv run pytest -q`：首次结果为 `1320 passed, 10 skipped, 5 failed`，816.80 s。两项迁移回滚失败暴露了新迁移将空字符串与 PostgreSQL `NUMERIC` 比较的方言错误；一项源摘要测试在比较前后没有实际改变关系。已将回滚改为按行使用既有方向规则映射，并修正测试在写入关系前后比较摘要。`tests/test_investment_record_type_migration.py tests/test_alembic_migration.py tests/test_cash_projection_migration.py tests/integration/test_cash_projection_concurrency.py` 随后为 `29 passed`，20.00 s；`uv run pytest -q --ignore=tests/test_wealth_performance.py` 为 `1323 passed, 10 skipped`，333.72 s。
- 合并后适配复核：对方账号属性只服务于既有收支账户间转账匹配，本 change 的收支—投资配对仍不消费该来源字段。复核发现收支投影迁移测试有 3 处未关闭的临时连接，其中 PostgreSQL 会保留 `idle in transaction` 并阻塞后续 DDL；已改为上下文托管连接。`tests/test_cash_projection_migration.py tests/test_alembic_migration.py tests/test_investment_record_type_migration.py tests/test_cash_investment_funding_relations.py tests/test_counterparty_account_attrs.py tests/contract/test_dual_backend_counterparty_account_transfer_matching.py` 为 `71 passed`，13.39 s；合并后完整功能回归 `uv run pytest -q --ignore=tests/test_wealth_performance.py` 为 `1358 passed, 10 skipped`，310.95 s。
- 仍未通过的完整回归项仅为 `tests/test_wealth_performance.py::test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets` 的两个后端参数：SQLite 冷重建 p95 为 `6.899861208 s`，超过 `5 s` 门槛；PostgreSQL 冷重建 p95 为 `14.162753041 s`，超过 `6.5 s` 门槛。该夹具不含投资事件或资金调拨关系；不得通过放宽门槛掩盖此风险。完整回归质量门槛尚未关闭，补跑条件为优化财富事实重建后在同一命令下完整复跑。
- 发布与回滚：未运行真实 `.ft` SQLite 迁移、PostgreSQL 迁移、投影重建或关系扫描。发布前须先备份 SQLite 主文件及同名 `-wal`、`-shm` 文件；获用户明确授权后再执行迁移、重建与扫描。回滚使用已校验备份，不删除来源行快照或投资事件。
