## 1. 思考与计划

- [x] 1.1 核对真实账本中工行跨境汇款和工银亚洲调拨的既有分类、对方账号、币种及关系数量，确定跨币种是关系语义而不是来源分类。
- [x] 1.2 明确只增加 `record_subtype`，复用 `account_id`、`counterparty_account` 与 `account_aliases`，不增加账号键、转账种类或关系审计列。
- [x] 1.3 完成 OpenSpec 提案、delta 规格、设计和术语更新，定义导入层与关系层边界。

## 2. 失败测试

- [x] 2.1 为 `record_type` 与 `record_subtype` 合法组合、SQLite/PostgreSQL 约束和确定性迁移回填编写失败测试。
- [x] 2.2 为工行跨境汇款、工银亚洲完整/掩码子账号、明确换汇和普通转账的导入期子类型与规范对方账号编写失败测试。
- [x] 2.3 为不读取导入渠道、账单文本、来源快照或账户类型的唯一别名目标 7 天最近一对一跨境与内部调拨匹配编写失败测试。

## 3. 实现

- [x] 3.1 添加标准记录子类型枚举、组合校验、正式事实模型/仓库字段和 Alembic 迁移，保守回填历史数据；SQLite 批量重建表时同步恢复活跃事实幂等 partial index 的 `deleted_at IS NULL` 谓词。
- [x] 3.2 在导入边界生成并持久化 `record_subtype`，保留工银亚洲完整子账号、严格还原可验证掩码账号，并保持来源行快照纯原始。
- [x] 3.3 重写候选索引与转账匹配：唯一账户别名目标在 7 天内全局最近一对一分配，普通转账维持短窗口。
- [x] 3.4 将 `cross_currency_remittance` 纳入关系与收支投影子类型，保留现有待配对候选承载方式。

## 4. 审查与验证

- [x] 4.1 完成产品范围与工程复核：不增加仅作审计的列；掩码原文保留在来源行；唯一别名目标才可进入 7 天分配池；别名冲突失败关闭。实现仅消费正式字段和显式别名；跨币种无金额猜测，别名冲突不会进入长窗口。
- [x] 4.2 运行新增与受影响的导入、关系、迁移、投影测试，以及 SQLite 与本机真实 PostgreSQL 契约矩阵：SQLite 集合 `42 passed, 7 skipped` 与 `52 passed, 17 skipped`；本机 `finance_tracker_test` PostgreSQL 矩阵 `78 passed`。
- [x] 4.3 已通过功能回归分组：`405 passed, 8 skipped`、`179 passed, 13 skipped`、财富功能 `55 passed`、`tests/contract tests/unit tests/integration` 为 `265 passed, 77 skipped`；并已通过 `compileall`、`uv build`、`git diff --check` 与 `openspec validate --all --strict`（`30 passed`）。用户豁免 `tests/test_wealth_performance.py` 的 10 万事实门禁。`tests/test_cash_projection_performance.py`（10,000 条事实）已在可回传退出码的终端补跑：`3 passed, 2 skipped`，耗时 52.29 秒。
- [x] 4.4 已同步主规格，记录发布、回滚和验证证据。经授权于 2026-08-04 重建真实 `~/.ft/finance-tracker.db`：保留一致性备份 `finance-tracker.db.before-alias-target-rebuild-20260804-143451` 与替换前副本 `finance-tracker.db.pre-alias-target-rebuild-20260804-143451`；从 `~/.ft/bills` 重导后得到 11,460 条现金流水、1,010 条投资事件和 3,277 条关系，SQLite 完整性及外键检查通过，收支投影重建为 8,192 条。工银亚洲账户登记 `4240` 至 `4247` 共 8 个 `card_tail` 别名；全量扫描新增 1 条跨币种跨境汇款、4 条同账户换汇与 11 条普通转账，并消除 6 条待审核关系。uSmart 主账户与日内融账单分别路由到对应证券账户；本机 API 已在 `127.0.0.1:8000` 验证账户目录与收支投影。变更在完成 4.3 前保持活动状态，不归档。
