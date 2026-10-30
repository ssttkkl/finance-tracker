# 全量迁移盘点

基线：`0c038a75e34264bd6d4aa5cce2070727df9d9ff7`。以下列出迁移验收范围；「未迁移」不代表原功能不可用，原 Python 运行时保留。完整 HTTP 方法与路径见 [接口矩阵](routes.md)。当前已验收 40/86 个 HTTP 操作，其余 46 项继续迁移。按用户要求，本轮只以功能迁移和功能测试验收，性能测试不阻断本轮功能交付；原预算和未通过证据保留。

## 模块与合同

| 能力 | 当前实现入口 | 共享核心 / 服务端责任 | Kotlin 状态 |
|---|---|---|---|
| 登录、注册、会话、工作区、邀请、成员 | `application/access.py`、`web/access_routes.py` | 共用 Role/DTO；Argon2、SHA256、会话 30 天、邀请 7 天一次性、最后管理员及删除留服务端 | 已实现全部 15 个 HTTP 接口并通过匿名双库对照；完整财务数据删除与锁等待期间撤权专项待验证，4.3 未完成 |
| 账户生命周期、币种、余额 | `account_commands.py`、`accounts.py`、`cash_balances.py` | 共用账户列表和命令 DTO；权限、账户修订、引用/余额检查、snapshot、收据原子写入留服务端 | 稳定 ID 读写及现金/投资列表已通过匿名双库、原生规则和实际 Compose 回包验证；余额/估值、旧写入适配、底层 CAS 和历史迁移等仍待迁，4.4 未完成 |
| 分类树、批量/单项分类 | `cash_categories.py`、`cash_classification.py` | 共用目录 DTO、真实祖先解析、五层/循环/子树约束；同级名称 casefold、目录锁/修订、entry/投影同步留服务端 | 已实现目录读取、创建、编辑、排序、删除检查及原子删除；删除同步 entry/恢复值/事实/候选/全部投影。单项/批量分类已完成当前数据集、完整成员、人工保护及原子写入；独立 move 服务已接增量维护，update 保持 Python 调用边界，完整目录性能仍待迁，4.5 保持未完成 |
| 现金事实、草稿、手工记录、转账、删除 | `cash_record_policy.py`、`cash_ledger.py`、`cash_record_maintenance.py`、`cash_record_writer.py`、`cash_record_deletion.py`、`cash_manual_*`、`cash_draft_merge*` | 共用精确金额/类型/分摊/完整性；当前账户资格、完整组、来源固定字段、修订、事务留服务端 | 字段/转正式纯规则 26 个原定义已迁并复核，动态子类 2 项 pending；账本类型选项 GET 已双端消费，余额快照/组成项增量通过双库原生测试，手工新建已完成严格公开输入、完整现金部分/余额/稳定收支/投影同事务写入及现有 Compose 消费；完整转账创建已通过双库同事务事件/关系/余额/投影与既有 Compose 消费；旧现金新增/余额校准已通过双库 96 组 HTTP/13 表全列、原生断言、锁等待权限及真实 Netty；来源事项选择纯规则已完成原生及 708 组 Python 差分，来源正文效果另有六类来源原生断言及 2,208 组完整差分；均未接入详情/维护事务，独立复核未完成；转账编辑/维护/导入和 OpenAPI schema 仍待迁移 |
| 现金维护计划、确认、结果未知核实 | `cash_mutation_*`、`cash_plan_execution.py`、`cash_commit_outcome.py` | 先共用值合同与客户端请求校验；纯效果计算按真实双端需求接入；CAS/TTL/owner、当前事实证明、永久幂等收据和回滚留服务端 | 永久收据五个 GET 已通过双库和实际 Compose 解码；超 Long 客户端计数限制保留。仓库原键重放/上下文唯一和写前校验已迁并由账户命令复用；已有维护预览/读取及正式编辑、草稿保存/转正式的提交与原键核实接入共享解析存储，双库测试覆盖数据库提交失败保留方案、提交后临时清理失败仍返回原收据及永久消费优先；单流水提交已在工作区锁后重建并核对冻结操作及依赖；完整维护/取消、关系/多成员操作、原测试迁移和历史迁移仍未完成 |
| 文件准备与来源账户选择 | `cash_import_context.py`、`statement_account_mapping.py`、`cash_import_account_mappings.py`、`cash_import_funding.py` | 共用已解码行和来源账户规则；来源完整性和实际映射写入留服务端 | 来源账户扫描与内部建议已实现；导入资金组扫描/选择纯准备已实现并通过 115 组完整 Python 结果及原生边界回归；上传文件准备纯层已实现并对照 20 个文件名、5 个选择失效和 36 个关系证明用例；只读来源规划仓库和会话视图已实现并按 SQLite/PostgreSQL 匿名种子对照。上传工作区事务、权限复核、永久收据与 HTTP 仍待迁移，原组合测试定义未计完成 |
| 导入来源计划、业务计划、整笔选择、提交 | `cash_import_source_plan.py`、`cash_import_business_plan.py`、`cash_import_entry_choices.py`、`cash_import_commit.py` | 先共用账单行、预览/选择/提交协议；规划留服务端，按真实双端需求再提取纯规则；提交重读来源/账户/当前事实/关系并比较操作指纹，原子保存余额/entry/投影/收据 | 未迁移 |
| 来源文件与行证据 | `adapters/cash_statement_*`、`bank_statement_document.py`、`icbc_pdf_*`、`domain/cash_source_*` | 保持上传/服务端解析流程，先共用行模型和协议；解码、逐行归一、字节预算/物理位置/声明真实性留服务端，不新增客户端解析入口 | 平台 CSV/TSV/XLSX、建行 XLS、工银亚洲 TSV、工行信用卡/借记卡 PDF 的原始文档读取已迁入后端，并分别通过 Python 匿名金标准；记录类型/子类型与对方账号公共规则、平台、银行与工行 PDF 行归一已迁并对照；来源身份/文件绑定键、已解析文件组合、来源账户扫描及来源对应解析已迁；多文件来源批次规划已迁并通过原功能断言及完整结果对照；解析会话编解码、内存及 R2 条件对象端口已迁；R2 SDK 与清扫器已接本机运行入口；维护计划存储已接线；内部来源账户对应仓库和只读建议已迁，发布保留调用方事务；上传文件准备纯层与只读来源规划/会话视图已迁；上传存储接线、导入成功证明的对应发布、上传流程与真实样本仍未迁移 |
| 支付镜像、退款抵扣、转账配对 | `domain/relations/`、`cash_relation_policy.py`、`cash_relation_graph.py`、`application/cash_current_relation_*`、`cash_relation_context.py`、`relations.py` | 先共用选择/命令协议与客户端组合校验；完整图和候选纯计算按双端需求接入；查询当前超集、授权、决定及落账由服务器负责 | 角色/闭包/分组/完整图纯规则 43 原定义已迁并复核；当前候选、决定、SQL 更新及资金生命周期仍未迁移 |
| 当前收支、证据、分页、投影发布 | `cash_projections.py`、`web_queries.py`、`adapters/relational/cash_*` | 共用 DTO/版本及纯展示结果；当前成员/entry 完整性、staging/active dataset、快照游标由服务器负责 | 投影纯构建 26 原定义及管理员状态 GET 已迁并复核；暂存发布、全量重建应用服务及工作区初始化已通过双库；管理员重建 HTTP 已通过真实双库及锁等待权限验证；内部增量维护、保存点缓存及 7 定义/14 参数的持久化图集成通过双库；手工新建、转账和旧现金新增/余额校准已接线，其余公开写入、当前查询/证据、分页及性能仍未迁移 |
| 投资事件、重放、持仓/成本、表现 | `investment.py`、`investment_import.py`、`investment_web_queries.py`、`domain/investment_*` | 共用协议与实际客户端需要的精确计算；完整重放/成本/PnL/回报按双端需求提取，来源/持久化由服务器负责 | 未迁移 |
| 现金—投资资金调拨 | `cash_investment_funding_relations.py` | 共用 UTC 窗口/资格；端点互斥、确认/拒绝、当前事实和审计留服务端 | 未迁移 |
| 连接器同步、增量游标 | `sync_service.py`、`adapters/connectors/ccxt_exchange.py`、`polymarket.py` | 共用事件归一；服务端凭据、网络分页、cursor 与财务写入不下沉 | 未迁移 |
| 行情、FX、历史估值 | `adapters/market_data.py`、`fx_rates.py`、`application/valuation.py` | 共用资产身份、估值/新鲜度规则；外部请求、缓存和超时由服务器负责 | 未迁移 |
| 持仓刷新、SSE | `portfolio_refresh.py`、`web/app.py` | 工作区隔离 worker；5 秒刷新、15 秒 heartbeat、60 秒 backoff、last-success/generation/version、关闭等待 | 未迁移 |
| 解析暂存、R2、清扫 | `parsed_import_staging.py`、`parsed_import_cleanup.py` | owner/CAS/TTL 最大 1800 秒及大小预算；内存/R2；启动 sweep、30 秒周期、100 项分页；健康/安全日志和关闭等待 | 编解码、内存/R2 条件端口、SDK 和清扫生命周期已迁并接本机入口；维护计划使用同一存储；上传文件准备纯层已迁，上传运行时接线和剩余原用例仍未完成 |
| 运行装配、schema、数据库 | `runtime.py`、`config.py`、`adapters/relational/{runtime,dialect,uow,workspace_lock}.py` | 显式 FT_DATABASE_URL、Alembic head 校验；SQLite BEGIN IMMEDIATE/PG FOR UPDATE、无回退或自动迁移 | 未迁移 |
| 已退役入口 | `tests/contract/test_cli_free_runtime.py`、`test_cli_replacement_api.py` | 无产品 CLI；旧 relations/check/accept/reject 和 cash-facts 保持 404 | 未迁移验证 |

## 持久化范围

当前表覆盖 `workspaces`、`users`、`workspace_memberships`、`user_sessions`、`workspace_invitations`、`accounts`、`statement_account_mappings`、`cash_mutation_receipts/results`、`cash_categories/states`、`cash_transactions/components`、`cash_source_empty_pages/bindings`、`cash_transaction_issues`、`cash_issue_candidates`、`cash_projection_states/datasets/projections/members/relations`、`cash_entries/members`、`investment_events`、`cash_investment_funding_relations`、`ledger_snapshots`、`transaction_relations`、`cash_relation_candidates`、`account_aliases`、`sync_cursors`，以及 `cash_source_documents.py` 等独立模型中的来源文件证据。

PG `NUMERIC(38,18)` 与 SQLite `String(96)`/`decimal_compare` 无损十进制等价，UTC 显式时区；BIGINT 在 SQLite 保持 INTEGER affinity。复合 workspace 外键、逻辑删除、来源证据和审计不删改。收据 `(workspace_id,user_id,idempotency_key)` 永久唯一，同时绑定 digest、operation、plan/context。请求结果未知按原键核实；暂存清理不能重新开放已消费命令。

已实现的 Kotlin 业务查询和写入已改用 jOOQ OSS `3.21.9`，以 SQLite 和 PostgreSQL 18 功能矩阵验证。67 个官方生成文件只留后端，schema 仍由原 Alembic 管理；JDBC 继续负责事务、保存点和驱动配置。当前详情、按流水定位和收支证据三个 GET 已实现并有双库功能及实际客户端验证，最终独立审查尚未完成，暂不增加已验收 HTTP 数；其余查询及列表继续待迁移。证据不包含账单原文，完整来源流程依赖未迁移的单条流水详情；旧证据 DTO 的草稿空值解码限制保留，来源成员 DTO 的空值消费已覆盖。

## 格式矩阵

| 来源 | 字节解码与预算 | 可共享内容 | 状态 |
|---|---|---|---|
| 支付宝、微信 | UTF-8-SIG/GB18030 CSV；openpyxl XLSX，ZIP/XML 防扩张 | 原列、精确金额/方向、来源时区、类型、账号属性、支付方式、来源说明、provider ID/跳过原因 | 原始文档读取与行归一已迁移；上传接口未迁移 |
| 建行借记 `ccb_debit` | OLE XLS/xlrd，声明、顺序号、表头 | 已解码行归一、币种/日期/账户/覆盖证据 | 原始文档读取与行归一已迁移；上传接口未迁移 |
| 工银亚洲 `icbc_asia` | UTF-16 TSV、繁体表头、独立时间列、银行/币种/笔数/日期声明 | 已解码行归一、规范账号、来源身份/覆盖 | 原始文档读取与行归一已迁移；上传接口未迁移 |
| 工行 `icbc_credit/icbc_debit` | PDF/pdfplumber/qpdf、密码错误区分、工具超时 | 结构行、退款/冲正、时间、类型、完整页证据 | 原始文档读取与行归一已迁移（PDFBox，含密码失败区分、页/字符/表/行预算、页指纹与文档键）；上传接口与真实样本未迁移 |
| DFZQ | PDF 和明确文本路径 | 投资事件、总发生金额/费用/成本/CHECKIN | 未迁移 |
| IBKR、Schwab | CSV | 投资归一、重叠导入身份、ticker/费用 | 未迁移 |
| uSmart HK | 加密 PDF 和明确文本路径 | 投资事件与产品账户归属 | 未迁移 |

现金解码限制含 100 MiB 输入、25 MiB 解析内容、100000 业务行、200000 物理行、128 列；格式各自合同继续保留。投资上传为 base64 JSON，解码后 25 MiB，密码头 `X-FT-Statement-Password`，临时文件 finally 清理。共享核心不宣称能在 Wasm/iOS 运行 JVM/Python 字节库；过渡格式 adapter 若启用须写明调用、失败与退出条件。

## 测试与黄金合同

全量 Python 测试对应表位于 `compose/test-migration.json`，检查器为 `scripts/check_kotlin_test_migration.py`。基线的 `tests/` 与 `compose/tests/` 共 2,612 个测试定义，授权新增响应编码回归后当前为 2,613 个定义、5,844 个完整 pytest 参数化用例；已迁移 232 个定义/577 个展开参数，其余 2,381 个定义保持 pending。新增源回归单独通过 `source_additions` 绑定当前指纹和理由，固定基线不变；未登记目标的定义一律保持 pending。检查包含测试函数、模块参数、fixture、数据文件与配置指纹，明确复核后的变化绑定基线/当前指纹和原因；不把黄金样本数或旧 Python 回归数当作 Kotlin 测试迁移数。最终 `--require-complete` 必须带完整 collection，存在未处理项即失败。

- Python oracle：`test_exact_decimal.py`、`test_cash_record_policy.py`、`test_cash_category_management.py`、`test_cash_statement_rows.py`、`test_bank_statement_rows.py`、`test_cash_statement_document.py`、`test_icbc_pdf_statement.py`、`test_cash_source_matching.py`、`test_cash_import_readable_equivalence.py`。
- 完整 `tests/contract` 与 `tests/integration`：HTTP/错误、来源/关系/投影、暂存生命周期、收据/未知结果、修订/权限/回滚、同步/券商及双库矩阵。
- 匿名 fixture：`tests/fixtures/{ibkr,schwab,dfzq,usmart_hk,import_idempotency}`、ccxt/polymarket JSON、现金退款 CSV；其他格式复用测试中的动态匿名文件生成器。
- 原完整常规回归 `scripts/run_backend_tests.py`（SQLite/PG 各 8 片）与独占 `-m performance`。PG 必须人工准备专用 `_test` 库，测试仅接受明确 URL；并行分片不得共库重置。
- Compose `CashDraftInputTest`、`CashCategoryLogicTest`、`ApiContractsTest`、全部受影响 `CashImport*ApiTest`/`CashMaintenanceApiTest`，以及公共 JVM/Android host/iOS/JS/Wasm 目标。用户禁止自动开浏览器，本任务 API/CLI 优先；无 UI 产品变化不安排设备或 UI 演示。
- 兼容比较包含 HTTP status/body、字段省略/null/default、严格类型/重复键/额外字段/大小预算、If-Match/Idempotency-Key、multipart、SSE 和停用入口；不只比较成功 JSON。
