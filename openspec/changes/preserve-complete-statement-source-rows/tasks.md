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

## 11. 全量账本重建复核

- [x] 11.1 先增加盈立账单产品档案失败回归：以 PDF 正文的交易布局区分保证金与日内融，不得依赖文件名、账号或账单后缀。
- [x] 11.2 修复产品档案解析后，在隔离 SQLite 从工作区、账户和账户别名重建全部现金与投资事实，运行关系扫描、资金调拨扫描、投影重建和完整性核验。
- [x] 11.3 在用户授权的真实 `.ft` 账本创建可恢复备份，原子发布已验证的全量重建库，并记录未自动确认的出入金关系。

## 12. 投资来源行快照完整性

- [x] 12.1 更新 proposal、delta 规格和设计，明确四个现有投资导入器以解析器显式原始快照为唯一来源，汇总与零持仓快照只保存可归属的来源文本单元。
- [x] 12.2 先增加 SQLite 失败回归，覆盖东方证券、IBKR、Charles Schwab 与盈立的流水、现金快照和持仓快照；断言保留完整来源行且不含标准化或编排字段。
- [x] 12.3 重构投资导入编排为只接受 `_source_payload`，并修正四个导入器的 CSV、PDF、余额和持仓来源快照。
- [x] 12.4 在 SQLite 与本机 PostgreSQL 运行导入、幂等和来源快照契约矩阵；用只读解析实际投资账单抽样确认快照键集合。
- [x] 12.5 完成产品/范围、工程与安全、最终 diff 独立复核，并记录验证命令、比较基线、结果和历史快照不回写的边界。

## 13. Charles Schwab 机构名称资金调拨确认

- [x] 13.1 更新 proposal、delta 规格、主规格和设计，纳入 Charles Schwab 的受控机构名称、手续费差额边界和银证转账列表标记。
- [x] 13.2 先增加 SQLite/PostgreSQL 失败回归，覆盖 USD `8,000` 银行转出与 USD `7,980` 嘉信外部入金。
- [x] 13.3 为 `schwab_csv` 实现受控机构名称候选，不读取或写死收款账号。
- [x] 13.4 增加收支账本列表和收支详情失败回归，确认单成员的 `bank_security_transfer` 显示“银证转账”和“银证转账关系”。
- [x] 13.5 完成范围、工程、安全和最终 diff 复核，并运行 SQLite/PostgreSQL 契约矩阵、OpenSpec 校验、前端构建与受影响回归。
- [x] 13.6 在用户授权的真实 `.ft` 账本创建可恢复备份，重扫资金调拨关系并核验投影、外键和完整性。

## 14. 银证转账双端展示与筛选

- [x] 14.1 更新 proposal、design、主规格和词表，明确银证转账的双端金额和 `transfer_subtype` 筛选合同；不新增数据库字段或经济类型。
- [x] 14.2 先增加 SQLite/PostgreSQL 失败回归，覆盖现金入金与投资出金两个方向、列表/详情 DTO 和 HTTP 序列化。
- [x] 14.3 在关系型读取层复用 `CashTransferDTO` 返回已确认资金调拨的两端账户、金额和币种，并实现专用筛选。
- [x] 14.4 为收支账本筛选加入“银证转账”，复用内部转账双端金额展示。
- [x] 14.5 完成范围、工程与最终 diff 复核，运行受影响 SQLite/PostgreSQL、前端、生产预览、视觉与 OpenSpec 验证，并记录结果。

## 15. 投资来源说明与交易总费用

- [x] 15.1 更新词表、proposal、delta 规格、主规格和设计，定义 `note`、业务行标识和交易总费用的边界；真实 `.ft` 账本不在本轮写入范围。
- [x] 15.2 先增加失败回归测试，覆盖四个账单导入器、CCXT、Polymarket 的来源说明边界，以及东方证券买入、卖出和回购的全部费用拆分。
- [x] 15.3 收紧导入器与同步器映射：移除派生费用、快照和技术标识文案；以来源行快照确定盈立现金流水的业务行标识。
- [x] 15.4 修正东方证券交易总费用拆分，并在 SQLite 与本机 PostgreSQL 运行导入、幂等与余额回放契约矩阵。
- [x] 15.5 完成产品/范围、工程和最终 diff 独立复核；运行 OpenSpec 严格校验、受影响测试、构建和 `git diff --check`，记录比较基线、HEAD、命令、结果与残余风险。

## 16. 已授权真实账本全量重建

- [x] 16.1 在可恢复备份基础上创建隔离候选库，保留工作区、账户与别名，按外键顺序清空现金、投资、关系和派生读模型。
- [x] 16.2 从固定的现金、投资原始账单和已上传的 Charles Schwab CSV 重导，按账单正文识别盈立产品档案。
- [x] 16.3 在候选库重建关系与投影，核验导入统计、资金调拨、外键、完整性、东方证券总费用和来源说明边界。
- [x] 16.4 将已验证候选库以同文件系统原子替换发布到 `~/.ft/finance-tracker.db`，重启后端并记录备份、统计、回滚位置和残余风险。

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
- 全量重建复核：通过。初次隔离重建发现盈立日内融账单因文本档案误判而全部路由到保证金账户，且历史工行借记卡 PDF 误用通用解析器会少 10 条。前者改为识别 PDF 正文的“交易明细／开仓记录”结构，后者在重建编排中按 PDF 正文选择 `icbc` 或 `icbc-debit`；两项均不读取文件名或账号。复跑后现金、投资、关系和投影统计回到既有基线，未发现阻断性、严重或中等 finding。
- 投资来源快照产品/范围独立复核：通过。范围仅涵盖东方证券、IBKR、Charles Schwab 与盈立的导入事实及其汇总快照；来源 CSV 自带的同名列仍完整保留，禁止的是解析、映射或编排新增的字段。未保存来源文件、路径或文件级元数据，也未回写历史快照。
- 投资来源快照工程与安全独立复核：修复 1 个中等 finding：真实盈立纵向持仓布局将原始文本单元列表传给仅接受字符串的快照构造函数，导致导入失败。构造函数现接受原始字符串或文本单元列表，并有回归测试。四个解析器均由编排层显式快照门禁保护；真实账单仅以临时解密文件只读解析，未写入数据库或测试夹具。
- 投资来源快照最终 diff 独立复核：通过。实现、delta 规格和测试一致；已修正文案，明确“不得写入标准化 `amount` 等字段”不排除来源 CSV 自带的同名列。未发现 critical、major 或其他中等 finding。残余风险是本地没有 Charles Schwab 原始账单，嘉信仅由逐行来源 CSV 夹具和双后端契约覆盖。
- Charles Schwab 机构名称资金调拨产品/范围复核：通过。关系只连接 `funding(external)` 与方向、业务日窗口唯一的收支转账；USD `20` 差额不产生手续费关系或额外端点。列表和收支详情只读取持久化的 `transfer_subtype=bank_security_transfer`，不会按机构名称、账号、备注或金额在浏览器中重判。
- Charles Schwab 机构名称资金调拨工程与安全复核：通过。`schwab_csv` 的受控名称为 `Charles Schwab`，与既有 IBKR、东方证券和盈立规则隔离；候选证据不含对方账号、来源行快照或自由备注。单成员投影在列表和详情一致显示专用标记，普通内部转账仍显示“个人转账”。
- Charles Schwab 机构名称资金调拨最终 diff 复核：通过。先前端失败回归后实施，SQLite/PostgreSQL、生产预览、全量前端和多视口视觉回归均通过；未发现 critical、major 或 minor finding。`hallmark` 可执行程序未安装，按其 `audit` 规则人工审查 `Workbench / Ledger Grid` 的列表和详情变更，未发现紫色渐变、卡片嵌套、悬停专属关键操作、令牌漂移、可点击文本换行或响应式溢出。残余风险是实际嘉信账单在本机没有原始文件，仍由上传 CSV、真实 SQLite 和双后端夹具覆盖。
- 银证转账双端展示与筛选产品/范围复核：通过。银证转账仍是 `internal_transfer(bank_security_transfer)`，不改变投影净额、收支汇总或个人转账行为；筛选只收窄到该子类型，不新增第四种经济类型或数据库字段。
- 银证转账双端展示与筛选工程复核：通过。读取层只沿投影持久化的已确认、活跃 `funding_relation_id` 关联收支现金流水和投资事件，按关系方向组装既有 `CashTransferDTO`；未读取机构名称、账号、来源快照或自由备注，且无关证据详情不增加资金调拨读取。
- 银证转账双端展示与筛选 UI 审计：通过。`hallmark` 可执行程序未安装，按 `hallmark audit web/src` 的 `Workbench / modern-minimal` 规则人工审查；`0 critical · 0 major · 0 minor`。新增筛选项沿用现有控件，跨币种金额沿用转账格式；生产预览在 `390 px` 核验无横向溢出。
- 银证转账双端展示与筛选最终 diff 复核：通过。双向资金调拨、HTTP 序列化、游标绑定筛选、普通个人转账和非转账详情路径均有回归覆盖；未发现阻断性、中等或低严重度 finding。残余风险是完整 Python 回归仍受已有性能用例执行时间限制，未在本轮重跑；本轮变更已通过受影响的 SQLite/PostgreSQL 契约矩阵。
- 投资来源说明与交易总费用产品/范围独立复核：通过。范围只收紧投资事件的来源说明并修正东方证券已来源明确的交易总费用；不新增字段、迁移、关系或真实账本写入。复核覆盖四个账单导入器与两个 API 同步器。发现 1 个低严重度问题：东方证券映射器在直接调用且缺少 `action_raw` 时会将归一化 `action` 写入 `note`；已改为仅使用来源动作或来源备注，并增加回归测试。未发现阻断性、严重或中等 finding。
- 投资来源说明与交易总费用工程与安全独立复核：通过。费用使用 `Decimal` 逐项取绝对值相加；任一来源费用为负或买入无法安全拆分时失败保守为净额现金部分和零 `commission`。盈立现金流水幂等键使用排序 JSON 的 SHA-256 来源快照摘要，不再读取展示 `note`；同步器不再把哈希或交易 ID 写入用户说明。未发现来源快照外溢、隐私泄露或 SQLite/PostgreSQL 语义分歧。
- 投资来源说明与交易总费用最终 diff 独立复核：通过。规格、词表、导入器、同步器、单元回归和双后端契约一致；东方证券买入、卖出和回购均覆盖全部费用与现金回放，四个账单导入器均覆盖来源说明和空快照说明。未发现阻断性、严重、中等或低严重度 finding。残余风险见验证记录。

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
- 全量重建比较基线与 `HEAD`：`aaa80fcdcc615775b305d36da878a369ccc8db98`。先在隔离 SQLite 从工作区、账户和账户别名重建：现金 `11,460` 条（支付宝 `3,059`、微信 `3,331`、工行信用卡 `2,847`、工行借记卡 `1,205`、建行 `952`、工银亚洲 `66`），投资 `1,010` 条（东方证券 `497`、IBKR `36`、盈立证券 `370`、盈立证券日内融 `107`）。执行 `ft relations check`、`ft funding-relations scan` 和 `ft projections rebuild` 后，资金调拨为 `39` 条已确认与 `3` 条待审核，收支投影为 `8,196` 条、`11,460` 个成员；所有关系投资端均为 `funding(external)`，已确认端点均无重复，`foreign_key_check` 为空且 `integrity_check=ok`。
- 全量重建自动化证据：`FT_TEST_POSTGRES_URL=postgresql+psycopg://huangwenlong@127.0.0.1:5432/finance_tracker_test FT_REQUIRE_TEST_POSTGRES=1 uv run pytest -q tests/unit/importers/test_usmart_hk.py tests/integration/test_usmart_hk_import.py tests/contract/test_dual_backend_usmart_hk.py tests/test_cash_investment_funding_relations.py`：`38 passed`。`uv run python -m compileall -q src`、`uv build`、`openspec validate preserve-complete-statement-source-rows --strict`、`openspec doctor` 和 `git diff --check`：通过。
- 全量重建真实账本：创建并校验仅所有者可读的 `~/.ft/backups/finance-tracker.before-full-rebuild-20260805T223205+0800.db` 后，将已验证的 SQLite 主库原子替换为 `~/.ft/finance-tracker.db`。发布后再次运行资金调拨扫描、投影状态、外键与完整性检查，结果与隔离库一致。未自动确认的关系均为 IBKR USD 入金：`2026-06-15` `7,329`、`2026-06-23` `7,469`、`2026-07-08` `4,757`；每条都有唯一同币种同金额银行转出，但对手方为本人姓名，未命中受控机构名称，故保持待审核。回滚可使用该备份执行 SQLite `.restore`；本次未触发恢复。
- 投资来源快照比较基线与 `HEAD`：`aaa80fcdcc615775b305d36da878a369ccc8db98`。`FT_TEST_POSTGRES_URL=postgresql+psycopg://huangwenlong@127.0.0.1:5432/finance_tracker_test FT_REQUIRE_TEST_POSTGRES=1 uv run pytest -q tests/integration/test_dfzq_import.py tests/integration/test_ibkr_import.py tests/integration/test_schwab_import.py tests/integration/test_usmart_hk_import.py tests/contract/test_dual_backend_dfzq.py tests/contract/test_dual_backend_ibkr.py tests/contract/test_dual_backend_schwab.py tests/contract/test_dual_backend_usmart_hk.py tests/unit/importers/test_usmart_hk.py`：`37 passed`。`uv run python -m compileall -q src`、`uv build`、`openspec validate preserve-complete-statement-source-rows --strict`、`openspec validate --all --strict`、`openspec doctor` 和 `git diff --check`：通过。
- 投资来源快照实际账单只读抽样：以临时解密文件解析 `~/.ft/bills` 中东方证券 `1` 份、IBKR `2` 份和盈立 `16` 份账单，分别得到 `497`、`39` 和 `478` 条带快照事件；每条快照均只含 `原始文本单元` 且所有单元为非空原始字符串。盈立样本覆盖 `9` 份保证金和 `7` 份日内融布局。嘉信在该目录没有原始账单，已由夹具逐行等值断言和 SQLite/PostgreSQL 契约覆盖。该检查没有调用导入服务、没有写入 `.ft` 数据库，也没有修改既有投资事件的历史 `source_payload`。
- Charles Schwab 机构名称资金调拨比较基线与 `HEAD`：`194f589`。`FT_TEST_POSTGRES_URL=postgresql+psycopg://huangwenlong@127.0.0.1:5432/finance_tracker_test FT_REQUIRE_TEST_POSTGRES=1 uv run pytest -q tests/test_cash_investment_funding_relations.py tests/test_relational_cash_projection_evidence.py`：`25 passed`。`cd web && npm test`：`37 passed`；`VITE_FT_API_ORIGIN=http://127.0.0.1:8866 npm run build`：通过；`FT_PREVIEW_WEB_PORT=5181 npm run test:preview`：`1 passed`；`npm run test:visual`：`10 passed`，覆盖 `1440`、`1024`、`768` 和 `390 px` 视口。
- Charles Schwab 机构名称资金调拨最终校验：`openspec validate preserve-complete-statement-source-rows --strict`、`openspec validate --all --strict`、`openspec doctor`、`uv run python -m compileall -q src`、`uv build` 和 `git diff --check`：通过。项目未配置独立 Python 静态类型检查器。
- Charles Schwab 机构名称资金调拨真实账本：在用户授权下先创建并校验权限为 `600` 的 `~/.ft/backups/finance-tracker.before-schwab-funding-scan-20260806T131553+0800.db`，再重扫真实 SQLite。现金流水 `11443`（USD `-8,000`）与嘉信投资事件 `1011`（USD `7,980`、`funding(external)`）已确认为关系 `43`，原因为 `unique_institution_name_candidate`；当前激活投影为 `internal_transfer(bank_security_transfer)`、净额 `0 USD`。当前共有 `40` 条已确认、`3` 条待审核关系；`PRAGMA foreign_key_check` 为空且 `PRAGMA integrity_check=ok`。回滚可用 SQLite `.restore` 还原上述备份；本次未触发恢复。
- 银证转账双端展示与筛选比较基线与 `HEAD`：`194f589`。`FT_TEST_POSTGRES_URL=postgresql+psycopg://huangwenlong@127.0.0.1:5432/finance_tracker_test FT_REQUIRE_TEST_POSTGRES=1 uv run pytest -q tests/test_cash_investment_funding_relations.py tests/test_relational_cash_projection_evidence.py tests/test_application_web_queries.py tests/contract/test_web_api.py`：`82 passed`。`cd web && npm test`：`38 passed`；`FT_PREVIEW_WEB_PORT=5181 npm run test:preview`：`2 passed`，包括 `390 px` 跨币种双端金额；`npm run test:visual`：`10 passed`；`VITE_FT_API_ORIGIN=http://127.0.0.1:8866 npm run build`：通过。
- 银证转账双端展示与筛选最终校验：`openspec validate preserve-complete-statement-source-rows --strict`、`openspec validate --all --strict`、`openspec doctor`、`uv run python -m compileall -q src`、`uv build` 和 `git diff --check`：通过。项目未配置独立 Python 静态类型检查器。
- 银证转账双端展示与筛选真实账本读取：以后端只读连接 `sqlite+pysqlite:////Users/huangwenlong/.ft/finance-tracker.db` 查询 `economic_type=bank_security_transfer`，投影版本 `3` 返回 `40` 条；IBKR 示例为 `10,000 HKD → 1,275.5 USD`，嘉信示例为 `8,000 USD → 7,980 USD`，均保留 `internal_transfer(bank_security_transfer)` 与投影净额 `0`。`http://127.0.0.1:8001` 对 `http://127.0.0.1:5174` 返回 CORS 允许头；本检查未写入账本。
- 投资来源说明与交易总费用比较基线：`e119eecc7dada466a2340cd04c3043061c404c4f`；验证时 `HEAD`：`5a17223d51a4bc17b6c093cbbf83dafa688d432a`。`FT_TEST_POSTGRES_URL=postgresql+psycopg://huangwenlong@127.0.0.1:5432/finance_tracker_test FT_REQUIRE_TEST_POSTGRES=1 uv run pytest -q tests/test_dfzq.py tests/unit/importers/test_dfzq_parser.py tests/unit/importers/test_dfzq_event_mapping.py tests/unit/importers/test_ibkr_map.py tests/unit/importers/test_schwab_map.py tests/unit/importers/test_usmart_hk.py tests/unit/test_ccxt_connector.py tests/unit/test_polymarket_connector.py tests/contract/test_dual_backend_dfzq.py tests/contract/test_dual_backend_ibkr.py tests/contract/test_dual_backend_schwab.py tests/contract/test_dual_backend_usmart_hk.py`：`137 passed`，覆盖 SQLite 和本机 PostgreSQL 的导入、幂等与东方证券现金回放。
- 投资来源说明与交易总费用最终校验：`uv run pytest -q --ignore=tests/test_wealth_performance.py`：`1261 passed, 141 skipped, 1 warning`；`uv run python -m compileall -q src`、`uv build`、`openspec validate preserve-complete-statement-source-rows --strict`、`openspec validate --all --strict`、`openspec doctor` 和 `git diff --check` 均通过。项目未配置独立 Python 静态类型检查器。
- 未通过项与残余风险：完整 SQLite 回归在 `tests/test_wealth_performance.py::test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets[sqlite]` 的 10 万事实冷重建 p95 为 `6.388426 s`，高于本机 `5 s` 性能门槛；此前已通过 `1030` 项、跳过 `141` 项，失败与本轮导入器、金额口径和持久化改动无交集，未视为通过。将所有 PostgreSQL 用例在单次运行中会争用同一 `finance_tracker_test` schema，故该完整矩阵未视为通过；本轮以独立的专用 PostgreSQL 契约组证明受影响合同。真实 `~/.ft` 账本未读取或写入；需要重建历史投资事件时仍须取得用户明确授权并先创建可恢复备份。
- 已授权真实账本全量重建：以 SQLite `.backup` 创建并校验 `/Users/huangwenlong/.ft/backups/finance-tracker.before-rebuild-20260806T171445+0800.db`（权限 `600`、完整性 `ok`）后，在隔离候选库清空现金、投资、关系和派生读模型，从 17 个账户、3 份支付宝 CSV、3 份微信 XLSX、3 份工行 PDF、2 份建行 XLS、6 份工银亚洲 CSV、1 份东方证券 PDF、2 份 IBKR CSV、1 份 Charles Schwab CSV 与 16 份盈立 PDF 重导。盈立以正文路由为 9 份保证金和 7 份日内融，未依赖文件名；候选库导入现金 `11,460` 条、投资 `1,047` 条（东方证券 `497`、IBKR `36`、嘉信 `37`、盈立证券 `370`、盈立证券日内融 `107`）。
- 已授权真实账本全量重建验证与发布：候选库及正式库均为 revision `20260805_24`、`foreign_key_check` 为空、`integrity_check=ok`。重建关系后资金调拨为 `40` 条 `accepted`、`3` 条 `pending_review`，所有投资端均为 `funding(external)` 且已确认端点无重复；收支投影为 `8,197` 条、`11,460` 个成员。所有投资 `snapshot` 的 `note` 为空；`1,010` 条 PDF 来源快照均只含非空 `原始文本单元`；37 条嘉信来源快照精确保留 CSV 的 8 个原始字段；东方证券 440 笔交易的 `commission` 与来源手续费、印花税、过户费总和逐笔一致，合计 `1,225.84`。财富与估值读模型在重建前备份中均为空，故未生成推断估值。
- 已授权真实账本发布与回滚：候选库经 SQLite `.backup` 生成同目录发布文件后原子替换 `/Users/huangwenlong/.ft/finance-tracker.db`，权限为 `600`。替换前的 `finance-tracker.db-wal` 和 `finance-tracker.db-shm` 已移动至 `/Users/huangwenlong/.ft/backups/finance-tracker.prepublish-20260806T172905+0800.db-wal` 与 `.db-shm`，未删除；候选目录已移入系统废纸篓。后端在 `http://127.0.0.1:8001` 监听且 `/openapi.json` 返回 `200`。回滚时先停止后端，再对正式库执行 SQLite `.restore` 使用上述 `before-rebuild` 备份。残余业务状态仅为 3 条待审核资金调拨候选，未自动确认。

## 发布准备与反思

- 已在用户明确授权下读取、备份并重建 `~/.ft`。发布前创建可恢复副本，再以该数据库 URL 执行 `alembic upgrade head`，验证运行时版本、导入统计、投影、关系、外键和完整性；回滚使用 SQLite `.restore` 还原已验证备份。
- 防复发规则：来源行快照由解析边界创建，标准行只用于账户映射、分类和关系处理；任何新现金解析器必须在源列无法唯一表达时拒绝导入，且不得以标准化字段或历史推断填补快照。投资账单重建必须从账单正文识别产品档案，不得以文件名路由账户；`source_payload` 仅排除来源显式声明的编排键，不能按键名前缀批量剔除原始列。
