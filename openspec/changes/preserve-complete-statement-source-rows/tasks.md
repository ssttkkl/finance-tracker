## 1. 思考与计划

- [x] 1.1 阅读项目上下文、OpenSpec 主规格、术语词表、现有解析器、数据库和 `~/.ft/bills`，确认支付宝字段丢失、微信与建行的现状及历史数据不可完全恢复。
- [x] 1.2 完成 proposal、delta 规格和设计，明确严格来源行合同仅适用于升级后的新导入，历史事实不伪造来源数据。
- [x] 1.3 更新术语词表中的来源行快照和对方账号定义，并完成中文文档排版复核。

## 2. 测试与数据契约

- [x] 2.1 先增加 SQLite 失败回归测试：支付宝完整原始列、空列和 `对方账号` 必须按合同写入。
- [x] 2.2 先增加微信、建行和工行来源行快照与 `counterparty_account` 的失败回归测试，覆盖提现到账卡、对方账号缺失和来源字段不外溢。
- [x] 2.3 增加 SQLite/PostgreSQL 迁移和导入契约测试，覆盖正式列、历史回填、精确金额和幂等。

## 3. 实施

- [x] 3.1 分离原始来源行与标准化导入行，确保导入编排只把原始来源行写入 `source_payload`。
- [x] 3.2 更新支付宝、微信、建行和工行解析器，完整保留来源列并按来源专用规则提取 `counterparty_account`。
- [x] 3.3 在 `cash_transactions` 模型、仓库和导出/查询契约中增加 `counterparty_account`。
- [x] 3.4 增加双后端 Alembic 迁移与运行时 revision，保守回填历史可确定值并保持无法恢复值为空。
- [x] 3.5 更新数据库结构和导入流程文档，说明来源行快照、隐私边界和历史数据限制。

## 4. 审查

- [x] 4.1 完成产品/范围复核：确认不把来源文件、推断账号或历史伪造数据带入范围。
- [x] 4.2 完成工程与安全复核：检查解析边界、迁移事务、SQLite/PostgreSQL 等价、账号日志暴露和回滚。
- [x] 4.3 完成最终范围化 diff 复核，记录 finding、结论和残余风险。

## 5. 测试与 QA

- [ ] 5.1 运行新增回归测试、受影响导入与迁移测试、真实 PostgreSQL 契约矩阵及完整 Python 回归。
- [x] 5.2 运行 OpenSpec 严格校验、类型检查、相称构建和 `git diff --check`。
- [x] 5.3 在本 change 记录实际命令、比较基线、HEAD、执行时间、结果和未运行项理由。

## 6. 发布准备

- [x] 6.1 记录迁移前备份、升级、验证和从备份回滚步骤；不执行用户 `~/.ft` 账本迁移，除非取得单独授权。

## 7. 反思

- [x] 7.1 记录本次“来源快照不等于解析快照”的防复发规则及历史不可恢复的边界。

## 8. 同步与归档

- [ ] 8.1 将已验证的 delta 规格同步到 OpenSpec 主规格，复核同步结果后归档变更。

## 审查记录

- 产品/范围复核：通过。实现只保存业务行，不保存来源文件、路径或文件级元数据；`counterparty_account` 只接收来源直接提供的值，历史缺失值保持为空。
- 工程与安全复核：通过。CSV、XLSX、XLS 和表格 PDF 在表头不唯一或行列数不一致时失败关闭；关系读取在查询时映射来源列名，不改写 `source_payload`；迁移仅从 `对方账号` 和建行 `acct_name_raw` 回填。未发现账号进入日志或真实测试夹具的路径。
- 最终范围化 diff 复核：通过。修复了来源快照成为字典后合并逻辑对不可散列值的错误，并更新了公共现金列表契约断言。未发现范围外重构。残余风险见验证记录。

## 验证记录

- 比较基线：`8c18ed7ecff6b31cd5adcc18becb4e4e09035f55`；验证时 `HEAD`：`dd77c95ad48ff737bb25afd0e78b4c800b770b7e`。
- `uv run pytest tests/test_complete_statement_source_payload.py tests/test_convert.py tests/test_postgres_statement_import.py tests/contract/test_dual_backend_record_type.py tests/contract/test_dual_backend_icbc_refund_pairing.py tests/test_alembic_migration.py -q`：247 passed，8 skipped。
- 最终补跑 `uv run pytest tests/test_complete_statement_source_payload.py tests/test_convert.py tests/test_alembic_migration.py tests/test_postgres_statement_import.py tests/test_015_idempotency.py tests/test_application_cash_projections.py tests/test_postgres_adapter.py tests/contract/test_dual_backend_record_type.py tests/contract/test_dual_backend_icbc_refund_pairing.py -q`：287 passed，8 skipped。
- 回归按目录与顶层分组完成：contract/unit 218 passed、43 skipped；integration 42 passed、28 skipped；其余已完成分组覆盖转换、导入、迁移、关系、CLI、运行时和财富功能，均通过。`uv run python -m compileall -q src` 与 `uv build` 成功；项目未配置独立静态类型检查器。
- `openspec validate preserve-complete-statement-source-rows --strict`、`openspec doctor` 和 `git diff --check`：通过。
- 本地 PostgreSQL 证据：以专用 `finance_tracker_test` 运行 `tests/test_alembic_migration.py`（8 passed）、`tests/contract/test_dual_backend_record_type.py tests/contract/test_dual_backend_icbc_refund_pairing.py`（12 passed）和 `tests/test_mapping_import_dual_backend.py`（2 passed）。各组之间重建 `public` schema，最后执行 `alembic upgrade head`；核验版本为 `20260803_16`，`cash_transactions.counterparty_account` 为非空 `character varying`，默认值为空字符串。
- 未完成：`tests/test_cash_projection_performance.py` 的固定 10 万事实性能门禁超过 2 分钟仍未完成，未将其结果视为通过。补跑条件：在允许长时间运行的环境执行该性能用例及完整 `uv run pytest -q`。
- 导入后关系扫描跟进：真实账本重建后 `transaction_relations` 为空，投影无法合并镜像关系。根因是候选索引、镜像分组、退款候选连通图和已接受关系冲突检查存在重复全量遍历，11,384 条事实的扫描无法在可接受时间内完成；同时导入流程吞掉关系扫描失败细节。修复将日期、镜像连通图与已接受关系在一次扫描内复用，不改变关系规则或自动确认阈值。
- 关系扫描验证：`uv run pytest tests/test_relations_index_injection.py tests/test_transaction_relations_payment_mirror.py tests/test_transaction_relations_cross_batch.py tests/test_transaction_relations_open_leg.py tests/test_transaction_relations_transfer.py tests/test_transaction_relations_refund.py tests/test_relations_pipeline_order.py tests/test_record_type_relation_gates.py -q` 为 `122 passed, 10 skipped`；在专用 PostgreSQL 库执行 `FT_TEST_POSTGRES_URL=... FT_REQUIRE_TEST_POSTGRES=1 uv run pytest tests/contract/test_cash_projection_parity.py tests/contract/test_dual_backend_icbc_refund_pairing.py -q` 为 `18 passed`。全量候选生成对 11,384 条事实在 2.367 秒内产生 3,250 个候选；`ft relations check` 已在真实 SQLite 账本写入 3,250 条关系，随后 `ft projections rebuild` 发布投影版本 3，8,314 条经济记录、11,384 个成员，`PRAGMA integrity_check` 返回 `ok`。

## 发布准备与反思

- 未获用户单独授权，不读取、不备份、不迁移 `~/.ft`。发布时先对精确数据库文件创建可恢复副本，再以该数据库 URL 执行 `alembic upgrade head`，验证 `alembic_version=20260803_16`、正式列存在和抽样导入；回滚使用升级前副本，或在已验证备份存在时执行 migration downgrade。
- 防复发规则：来源行快照由解析边界创建，标准行只用于账户映射、分类和关系处理；任何新现金解析器必须在源列无法唯一表达时拒绝导入，且不得以标准化字段或历史推断填补快照。
