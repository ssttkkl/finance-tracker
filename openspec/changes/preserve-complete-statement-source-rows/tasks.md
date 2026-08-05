## 1. 思考与计划

- [x] 1.1 阅读项目上下文、OpenSpec 主规格、术语词表、现有解析器、数据库和 `~/.ft/bills`，确认支付宝字段丢失、微信与建行的现状及历史数据不可完全恢复。
- [x] 1.2 完成 proposal、delta 规格和设计，明确严格来源行合同仅适用于升级后的新导入，历史事实不伪造来源数据。
- [x] 1.3 更新术语词表中的来源行快照和对方账号定义，并完成中文文档排版复核。
- [x] 1.4 清点工银亚洲 CLI、映射、正式流水、分类和关系渠道中的旧键，确认旧键参与活跃业务行唯一性。

## 2. 测试与数据契约

- [x] 2.1 先增加 SQLite 失败回归测试：支付宝完整原始列、空列和 `对方账号` 必须按合同写入。
- [x] 2.2 先增加微信、建行和工行来源行快照与 `counterparty_account` 的失败回归测试，覆盖提现到账卡、对方账号缺失和来源字段不外溢。
- [x] 2.3 增加 SQLite/PostgreSQL 迁移和导入契约测试，覆盖正式列、历史回填、精确金额和幂等。
- [x] 2.4 增加工银亚洲新渠道键、业务行键迁移和活跃冲突失败关闭的回归测试。

## 3. 实施

- [x] 3.1 分离原始来源行与标准化导入行，确保导入编排只把原始来源行写入 `source_payload`。
- [x] 3.2 更新支付宝、微信、建行和工行解析器，完整保留来源列并按来源专用规则提取 `counterparty_account`。
- [x] 3.3 在 `cash_transactions` 模型、仓库和导出/查询契约中增加 `counterparty_account`。
- [x] 3.4 增加双后端 Alembic 迁移与运行时 revision，保守回填历史可确定值并保持无法恢复值为空。
- [x] 3.5 更新数据库结构和导入流程文档，说明来源行快照、隐私边界和历史数据限制。
- [x] 3.6 将工银亚洲 CLI、解析、分类、关系渠道和映射键收敛为 `icbc-asia` / `icbc_asia`，并添加 `20260804_20` 迁移。

## 4. 审查

- [x] 4.1 完成产品/范围复核：确认不把来源文件、推断账号或历史伪造数据带入范围。
- [x] 4.2 完成工程与安全复核：检查解析边界、迁移事务、SQLite/PostgreSQL 等价、账号日志暴露和回滚。
- [x] 4.3 完成最终范围化 diff 复核，记录 finding、结论和残余风险。

## 5. 测试与 QA

- [ ] 5.1 运行新增回归测试、受影响导入与迁移测试、真实 PostgreSQL 契约矩阵及完整 Python 回归。
- [x] 5.2 运行 OpenSpec 严格校验、类型检查、相称构建和 `git diff --check`。
- [x] 5.3 在本 change 记录实际命令、比较基线、HEAD、执行时间、结果和未运行项理由。

## 6. 发布准备

- [x] 6.1 记录迁移前备份、升级、验证和从备份回滚步骤；仅在取得用户明确授权后执行真实 `~/.ft` 账本迁移与重建。

## 7. 反思

- [x] 7.1 记录本次“来源快照不等于解析快照”的防复发规则及历史不可恢复的边界。

## 8. 同步与归档

- [ ] 8.1 将已验证的 delta 规格同步到 OpenSpec 主规格，复核同步结果后归档变更。

## 9. 投资事件分类修复与重建

- [x] 9.1 更新术语词表、proposal、delta 规格和设计，记录外部出入金、投资账户内部调拨、费用、冲回与未分类调整的边界。
- [x] 9.2 先增加失败回归测试：东方证券原始动作溯源、盈立税费返还、罚息、代收费、认购手续费和平台费返还的分类，以及资金调拨候选排除。
- [x] 9.3 扩展投资事件记录子类型与 SQLite/PostgreSQL 迁移约束；对可由来源快照确定的历史盈立事件执行前向重分类。
- [x] 9.4 修正东方证券和盈立导入映射，并保证重导事件保存完整原始动作或旗标。
- [x] 9.5 在隔离 SQLite 与本机 PostgreSQL 执行导入、迁移、幂等、候选资格和事件回放契约矩阵。
- [x] 9.6 完成产品/范围、工程、安全和最终 diff 独立复核；记录按严重级别排序的 finding、结论和残余风险。
- [x] 9.7 在用户明确授权的真实 `.ft` 账本上创建并校验备份，清空投资事件、现金—投资资金调拨关系和依赖投资读模型，从指定原始投资账单重导，重建读模型并扫描候选。
- [x] 9.8 记录真实账本操作的精确命令、数据库版本、导入统计、验证结果、回滚位置和未解决风险。

## 10. 机构名称资金调拨确认

- [x] 10.1 更新术语词表、proposal、delta 规格、主规格和设计，明确单向 7 日窗口、受控机构名称、金额币种差异与候选替代边界。
- [x] 10.2 先增加 SQLite/PostgreSQL 失败回归：IBKR 跨币种机构名称、东方证券机构名称优先、反向日期拦截和既有系统候选升级。
- [x] 10.3 实现受控机构名称候选、优先级、确认校验与系统候选归档，不改变人工决定和已确认关系。
- [x] 10.4 完成范围、工程、安全和最终 diff 复核；运行 SQLite/PostgreSQL 契约矩阵、OpenSpec 校验、构建与受影响回归。
- [x] 10.5 在用户授权的真实账本备份后重扫资金调拨关系，记录确认、待审核与归档统计及完整性检查。

## 审查记录

- 产品/范围复核：通过。实现只保存业务行，不保存来源文件、路径或文件级元数据；`counterparty_account` 只接收来源直接提供的值，历史缺失值保持为空。
- 工程与安全复核：通过。CSV、XLSX、XLS 和表格 PDF 在表头不唯一或行列数不一致时失败关闭；关系读取在查询时映射来源列名，不改写 `source_payload`；迁移仅从 `对方账号` 和建行 `acct_name_raw` 回填。未发现账号进入日志或真实测试夹具的路径。
- 最终范围化 diff 复核：通过。修复了来源快照成为字典后合并逻辑对不可散列值的错误，并更新了公共现金列表契约断言。未发现范围外重构。残余风险见验证记录。
- 投资分类产品/范围复核：通过。外部出入金仅保留 `funding(external)`；投资账户内部调拨、费用、冲回、认购、调整和快照均被排除在资金调拨候选外。`D11` 以账单正文的 `margin` 档案路由，未按文件名猜测账户。
- 投资分类工程与安全复核：已修复 1 个中等 finding：盈立解析器的 `_profile`、`_id_seq` 等编排元数据曾可能进入 `source_payload`。现仅排除各来源显式声明的元数据键，避免丢弃潜在以下划线开头的原始列；SQLite 与 PostgreSQL 契约均覆盖该规则。未发现 critical 或 major finding。
- 投资分类最终 diff 复核：通过。`20260805_24` 的双后端约束、历史前向重分类、导入映射、候选门禁、运行时 revision 和主规格一致；真实重建保持证券快照等价。残余风险是完整 Python 回归的性能用例超过交互执行窗口，未将其视为通过。
- 机构名称资金调拨产品/范围复核：通过。关系仅连接收支现金流水与 `funding(external)` 投资事件；费用、税费和利息保持独立经济事实，金额或币种差异不生成第三个关系端点或手续费拆分。
- 机构名称资金调拨工程与安全复核：通过。机构名称由投资导入渠道选择受控列表，未读取或持久化收款账号、本人名称或原始备注；网页证据只允许受控的匹配键，跨币种 IBKR 样例验证未泄露投资来源快照。
- 机构名称资金调拨最终 diff 复核：通过。单向 7 日窗口、候选唯一性、端点互斥和旧系统候选归档与规格一致；未发现阻断性、严重或中等 finding。残余风险是受控机构名称扩展时必须同步增加双后端回归和真实数据核验。

## 验证记录

- 比较基线：`8c18ed7ecff6b31cd5adcc18becb4e4e09035f55`；验证时 `HEAD`：`dd77c95ad48ff737bb25afd0e78b4c800b770b7e`。
- `uv run pytest tests/test_complete_statement_source_payload.py tests/test_convert.py tests/test_postgres_statement_import.py tests/contract/test_dual_backend_record_type.py tests/contract/test_dual_backend_icbc_refund_pairing.py tests/test_alembic_migration.py -q`：247 passed，8 skipped。
- 最终补跑 `uv run pytest tests/test_complete_statement_source_payload.py tests/test_convert.py tests/test_alembic_migration.py tests/test_postgres_statement_import.py tests/test_015_idempotency.py tests/test_application_cash_projections.py tests/test_postgres_adapter.py tests/contract/test_dual_backend_record_type.py tests/contract/test_dual_backend_icbc_refund_pairing.py -q`：287 passed，8 skipped。
- 回归按目录与顶层分组完成：contract/unit 218 passed、43 skipped；integration 42 passed、28 skipped；其余已完成分组覆盖转换、导入、迁移、关系、CLI、运行时和财富功能，均通过。`uv run python -m compileall -q src` 与 `uv build` 成功；项目未配置独立静态类型检查器。
- `openspec validate preserve-complete-statement-source-rows --strict`、`openspec doctor` 和 `git diff --check`：通过。
- 本地 PostgreSQL 证据：以专用 `finance_tracker_test` 运行 `tests/test_alembic_migration.py`（8 passed）、`tests/contract/test_dual_backend_record_type.py tests/contract/test_dual_backend_icbc_refund_pairing.py`（12 passed）和 `tests/test_mapping_import_dual_backend.py`（2 passed）。各组之间重建 `public` schema，最后执行 `alembic upgrade head`；核验版本为 `20260803_16`，`cash_transactions.counterparty_account` 为非空 `character varying`，默认值为空字符串。
- 工银亚洲渠道收敛：`FT_TEST_POSTGRES_URL=postgresql+psycopg://huangwenlong@127.0.0.1:5432/finance_tracker_test FT_REQUIRE_TEST_POSTGRES=1 uv run pytest -q tests/test_icbc_asia_current_account.py tests/test_cash_record_subtype.py tests/test_cli.py tests/test_alembic_migration.py tests/test_statement_import_mapping.py tests/test_postgres_statement_import.py`：101 passed。另在专用 PostgreSQL schema 从 `20260804_19` 插入旧 `icbc_asia_current_account` 事实，升级到 `20260804_20` 后核验渠道与业务行键均为 `icbc_asia` 前缀；随后重建该专用 schema 至 head。
- 工银亚洲渠道收敛的构建证据：`uv run python -m compileall -q src`、`uv build`、`git diff --check`、`openspec validate --all --strict` 和 `openspec doctor` 通过。完整 Python 回归（跳过 `tests/test_wealth_performance.py`）被执行环境在完成前中止，未视为通过；补跑条件是在无前台时限环境执行该命令。
- 未完成：`tests/test_cash_projection_performance.py` 的固定 10 万事实性能门禁超过 2 分钟仍未完成，未将其结果视为通过。补跑条件：在允许长时间运行的环境执行该性能用例及完整 `uv run pytest -q`。
- 导入后关系扫描跟进：真实账本重建后 `transaction_relations` 为空，投影无法合并镜像关系。根因是候选索引、镜像分组、退款候选连通图和已接受关系冲突检查存在重复全量遍历，11,384 条事实的扫描无法在可接受时间内完成；同时导入流程吞掉关系扫描失败细节。修复将日期、镜像连通图与已接受关系在一次扫描内复用，不改变关系规则或自动确认阈值。
- 关系扫描验证：`uv run pytest tests/test_relations_index_injection.py tests/test_transaction_relations_payment_mirror.py tests/test_transaction_relations_cross_batch.py tests/test_transaction_relations_open_leg.py tests/test_transaction_relations_transfer.py tests/test_transaction_relations_refund.py tests/test_relations_pipeline_order.py tests/test_record_type_relation_gates.py -q` 为 `122 passed, 10 skipped`；在专用 PostgreSQL 库执行 `FT_TEST_POSTGRES_URL=... FT_REQUIRE_TEST_POSTGRES=1 uv run pytest tests/contract/test_cash_projection_parity.py tests/contract/test_dual_backend_icbc_refund_pairing.py -q` 为 `18 passed`。全量候选生成对 11,384 条事实在 2.367 秒内产生 3,250 个候选；`ft relations check` 已在真实 SQLite 账本写入 3,250 条关系，随后 `ft projections rebuild` 发布投影版本 3，8,314 条经济记录、11,384 个成员，`PRAGMA integrity_check` 返回 `ok`。
- 投资分类比较基线与 `HEAD`：`39abd11`。隔离 SQLite 使用 `sqlite3 ~/.ft/finance-tracker.db .backup` 创建副本，升级 `20260804_23 → 20260805_24` 后清空投资事件、资金调拨关系和现金投影数据集，按原始账单重导并重建读模型：共 `1,010` 条事件（东方证券 `497`、IBKR `36`、盈立证券 `370`、盈立证券日内融 `107`）；盈立 9 份 `margin` 账单路由至保证金账户、7 份 `day` 账单路由至日内融账户。`PRAGMA foreign_key_check` 为空、`integrity_check=ok`、证券快照与重建前等价。
- 投资分类自动化证据：`uv run pytest -q tests/integration/test_usmart_hk_import.py tests/integration/test_dfzq_import.py tests/unit/importers/test_usmart_hk.py tests/test_investment_record_type.py tests/test_alembic_migration.py` 为 `53 passed, 2 skipped`；本机专用 PostgreSQL `finance_tracker_test` 运行 `tests/contract/test_dual_backend_usmart_hk.py tests/contract/test_dual_backend_dfzq.py tests/test_investment_record_type_migration.py tests/test_cash_investment_funding_relations.py` 为 `19 passed`。`uv build`、`openspec validate --all --strict`、`openspec doctor` 和 `git diff --check` 通过；项目未配置独立静态类型检查器。
- 真实账本发布证据：用户明确授权后，先以 SQLite `.backup` 创建并校验 `~/.ft/backups/finance-tracker.before-investment-rebuild-20260805T155012+0800.db`，再升级真实库、清空投资事件及依赖投影、从原始账单重导、重建财富与现金投影并扫描关系。发布后为 `20260805_24`、`1,010` 条投资事件、`0` 条 `adjustment(unclassified)`、`62` 条资金调拨关系（`21` 条 `accepted`、`41` 条 `pending_review`）；全部关系端点均为 `funding(external)`，477 条盈立来源快照均不含编排元数据，`PRAGMA foreign_key_check` 为空且 `integrity_check=ok`。失败恢复命令为对该数据库执行 SQLite `.restore` 使用上述备份；本次未触发恢复。
- 完整 `uv run pytest -q` 在交互执行器中受性能用例和重复会话干扰，未获得可归档的最终退出码，未视为通过。补跑条件：在无前台时限、单一进程环境运行完整回归；本次受影响改动已由上述 SQLite、PostgreSQL 和真实账本矩阵覆盖。
- 机构名称资金调拨比较基线：`e119eecc7dada466a2340cd04c3043061c404c4f`；验证时 `HEAD`：`2c959a3e0f4758a6fc8e83a784217d105ca6702f`。`FT_TEST_POSTGRES_URL=postgresql+psycopg://huangwenlong@127.0.0.1:5432/finance_tracker_test FT_REQUIRE_TEST_POSTGRES=1 uv run pytest -q tests/test_cash_investment_funding_relations.py`：19 passed。补充网页证据契约后，以相同本机 PostgreSQL 配置运行 `tests/test_cash_investment_funding_relations.py tests/test_relational_cash_projection_evidence.py`：23 passed；`uv run pytest -q tests/test_application_web_queries.py tests/contract/test_web_api.py`：49 passed、5 skipped。`openspec validate preserve-complete-statement-source-rows --strict`、`openspec doctor`、`uv run python -m compileall -q src`、`uv build` 和 `git diff --check`：通过。
- 机构名称资金调拨真实账本：先校验并将 `~/.ft/backups/finance-tracker.before-institution-funding-scan-20260805T215553+0800.db` 收紧为仅所有者可读，再执行 `FT_DATABASE_URL=sqlite+pysqlite:////Users/huangwenlong/.ft/finance-tracker.db FT_WORKSPACE_ID=default uv run ft funding-relations scan`。扫描后有 39 条已确认（21 条 `unique_strong_candidate`、18 条 `unique_institution_name_candidate`）、3 条待审核、21 条 `no_longer_candidate` 已驳回；东方证券 33 条已确认，IBKR 1 条机构名称确认和 3 条待审核，盈立证券 5 条机构名称确认。已确认现金端和投资端重复数均为 0，`PRAGMA foreign_key_check` 为空且 `PRAGMA integrity_check=ok`。回滚使用该备份执行 SQLite `.restore`；本次未触发恢复。

## 发布准备与反思

- 已在用户明确授权下读取、备份并重建 `~/.ft`。发布前创建可恢复副本，再以该数据库 URL 执行 `alembic upgrade head`，验证运行时版本、导入统计、投影、关系、外键和完整性；回滚使用 SQLite `.restore` 还原已验证备份。
- 防复发规则：来源行快照由解析边界创建，标准行只用于账户映射、分类和关系处理；任何新现金解析器必须在源列无法唯一表达时拒绝导入，且不得以标准化字段或历史推断填补快照。投资账单重建必须从账单正文识别产品档案，不得以文件名路由账户；`source_payload` 仅排除来源显式声明的编排键，不能按键名前缀批量剔除原始列。
