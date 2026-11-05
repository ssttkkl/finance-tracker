# Kotlin 后端本机预览

公开协议由 [OpenAPI 接线](src/main/kotlin/com/finance/tracker/backend/LegacyOpenApi.kt) 提供：`GET /openapi.json` 无需认证或数据库连接，只列出本次运行实际注册的操作，保留原公开参数、完整 schema 和可解析引用；未接入操作及未装配的导入服务不列入，`/docs`／`/redoc` 仍关闭。公开元数据资源含 89 项，当前完整装配运行时为 88 项；维护计划和持仓读取／刷新／SSE 已完成原合同验收；投资导入已接入，完整双库功能覆盖与独立复核已通过；同步操作已接入，并通过原交易所双库集成、凭据脱敏与独立复核。

在仓库根运行 `PYTHONPATH=src PYTHONDONTWRITEBYTECODE=1 .venv/bin/python scripts/export_kotlin_openapi_contract.py --check` 核对已提交资源；更新时去掉 `--check` 后审查并运行 `*OpenApiContractTest`。服务器只读取编译资源，不执行 Python。历史收据兼容准备器 `scripts/export_legacy_receipt_migration_compatibility.py --database-url <显式目标> --output <仓库外目录>` 实际执行原历史升级及有损降级拒绝；只允许不存在的绝对 SQLite 文件或空的 PG `_test` schema。设置 `FT_KOTLIN_LEGACY_RECEIPT_MIGRATION_DIR` 后运行 `*LegacyReceiptMigrationCompatibilityTest`，读取真实升级后的旧／新完整收据。

维护预计图的 [直接原断言](src/test/kotlin/com/finance/tracker/backend/CashMaintenanceBusinessOriginalTest.kt) 覆盖原 12 定义／30 双库参数；[额外确认效果](src/test/kotlin/com/finance/tracker/backend/MutationPlanEffectsOriginalTest.kt) 覆盖原 9 定义／9 参数。前者实际调用预览、暂存和详情，核对来源／关系保护、种子、拒绝后不发布及 31 表只读；内部阶段工厂默认仍构造真实业务图。既有完整图检查点另行保留，不重复计数。

按 `scripts/export_cash_maintenance_business_contracts.py --help` 的 15 个 `--scenario` 为 SQLite／专用 PG `_test` 库分别准备匿名状态，`--output` 指向仓库外共同目录。设置 `FT_KOTLIN_MAINTENANCE_BUSINESS_CONTRACT_DIR` 后运行 `:backend:test --tests '*CashMaintenanceBusinessOriginalTest' -PbackendFunctionalOnly=true`。纯效果测试可单独筛选 `*MutationPlanEffectsOriginalTest`，无需数据库。OpenAPI 已接通；当前关系计划等组合已按迁移台账完成并通过合同测试。

旧交易查询由 [读取服务](src/main/kotlin/com/finance/tracker/backend/LegacyTransactionQueryService.kt) 提供，保留正式流水、UTC 月份／账户／分类、默认 30 条及 1–200 范围、精确金额与原十字段响应；不依赖投影就绪。当前 Compose 无该 GET 消费者；账户余额查询已通过真实行情估值接入，见下文账户余额与财务报表。

使用 `scripts/export_legacy_transaction_query_contracts.py --database-url "$FT_TEST_POSTGRES_URL" --output "$FT_KOTLIN_TRANSACTION_QUERY_CONTRACT_DIR"` 在手工准备的空 `_test` 库生成匿名输入及原 HTTP 对照，输出必须在仓库外。设置该目录后运行 `:backend:test --tests '*LegacyTransactionQueryTest' -PbackendFunctionalOnly=true`；SQLite 使用显式 `sqlite+pysqlite:///` 绝对路径。测试直接覆盖两个原应用定义，并核对完整响应、31 表只读、实际读取快照与故障停止，不调用行情服务。

当前记录的 [原断言回归](src/test/kotlin/com/finance/tracker/backend/CashCurrentRecordOriginalTest.kt) 验证事务内最新值、回滚／跨连接快照、工作区隔离、完整闭包来源及确认时的现金部分证明；[失败结果回归](src/test/kotlin/com/finance/tracker/backend/CashMutationFailureResultsOriginalTest.kt) 直接验证稳定去重、已有组排除和虚拟编号脱敏，并禁止实际 SQL。两文件 9 个原定义中，8 定义／22 参数已直接对应；含 SQLAlchemy 未刷写删除状态的 1 定义／4 参数保留 Python 参考，Native 另验证软删除拒绝和 ID 参数／不可变快照边界，不混入迁移数。

为 `scripts/export_cash_current_record_contracts.py --help` 列出的 12 个 `--scenario` 手工准备独立空测试库，`--output` 使用仓库外共同目录；生成器只使用原匿名种子、原镜像设置和来源输入，不生成预期响应。设置 `FT_KOTLIN_CURRENT_RECORD_CONTRACT_DIR` 后运行 `:backend:test --tests '*CashCurrentRecordOriginalTest' --tests '*CashMutationFailureResultsOriginalTest' -PbackendFunctionalOnly=true`。写场景会消费数据库，重跑须重新准备；发现失败结果的两个纯测试无需数据库。

```bash
PYTHONPATH=.:src:tests:scripts PYTHONDONTWRITEBYTECODE=1 .venv/bin/python scripts/export_cash_current_record_contracts.py \
  --database-url "$FT_TEST_POSTGRES_URL" --output "$FT_KOTLIN_CURRENT_RECORD_CONTRACT_DIR" --scenario scopes_0
```

维护 HTTP 的 [原生回归](src/test/kotlin/com/finance/tracker/backend/CashMaintenanceOriginalHttpTest.kt) 直接对应原文件全部 17 定义／52 双库参数，覆盖草稿保存、转正式、完整输入替换、来源问题、联合关系零额结果、身份合并、账户变化、回滚／结果未知、当前权限、过期、非法协议和私有归属。使用真实 Ktor 请求核对计划、详情、收据及原键恢复，SQL 故障验证实际到达和完整回滚；成功后的当前详情读取失败不改变收据成功。维护验收同时核对业务图、来源／身份、完整成员、提交重验、发布／恢复和纯输入原断言，证据见 tasks。

草稿保存计划与关系维护请求不接受 `issue_resolutions` 字段，显式空数组也返回输入错误；内部已保存表示中的已验证空列表由专用适配读取，不能放宽公开协议。内部黄金输入与真实 HTTP 输入分别验证，原账户／金额／财务规则不变。

为 `scripts/export_cash_maintenance_http_contracts.py --help` 列出的 26 个 `--scenario` 手工准备独立空测试库，导出原匿名种子至首个客户端创建前；`--output` 为仓库外共同根目录。生成器只保存原输入和必要身份，不生成预期响应。设置 `FT_KOTLIN_MAINTENANCE_HTTP_CONTRACT_DIR` 后运行 `:backend:test --tests '*CashMaintenanceOriginalHttpTest' -PbackendFunctionalOnly=true`；测试会消费写库，重跑必须重新准备。

```bash
PYTHONPATH=.:src:tests:scripts PYTHONDONTWRITEBYTECODE=1 .venv/bin/python scripts/export_cash_maintenance_http_contracts.py \
  --database-url "$FT_TEST_POSTGRES_URL" --output "$FT_KOTLIN_MAINTENANCE_HTTP_CONTRACT_DIR" --scenario finalize
```

已准备操作的 [共享预计图入口](src/main/kotlin/com/finance/tracker/backend/CashImportBusinessPlanBuilder.kt) 复用导入后段，允许维护直接提供当前流水、额外种子／决定组和当前关系构建回调，不依赖导入文件。`CashImportBusinessPlanContractTest` 对照 10 组完整 Python 结果，并验证输入隔离、无文件维护、未变化成员、快照变换顺序和决定组缺失拒绝；测试环境沿用 `FT_KOTLIN_IMPORT_PREPARATION_CONTRACT_DIR`。

`maintain_relations` 已接通 [当前关系预览](src/main/kotlin/com/finance/tracker/backend/CurrentCashMaintenancePlanBuilder.kt) 与 [原子提交](src/main/kotlin/com/finance/tracker/backend/CurrentCashMaintenanceCommitWriter.kt)：保留完整决定组、候选身份与证明、上一方案、拆分公共值及明确的人工关联。必要的候选清理在现金部分移除前执行，字段、关系、余额、稳定收支、投影及永久收据同事务；提交调用失败保留未知结果，原键重试先查永久收据。6 个维护接口已按完整合同验收，适用于 Kotlin 本机预览；生产入口仍为 Python。

普通保存、正式编辑、转正式及既有来源决定使用相同的完整预计图和确认执行器，已移除单流水的重复呈现／写入代码。正式备注编辑不发起新关系发现；转正式只以本操作的现金部分为种子，允许完整发现组参与同一事务。`scripts/export_cash_maintenance_business_contracts.py` 为 15 个原业务计划检查点分别准备独立测试库；设置 `FT_KOTLIN_MAINTENANCE_BUSINESS_CONTRACT_DIR`，运行 `:backend:test --tests '*CashMaintenanceBusinessPlanTest' -PbackendFunctionalOnly=true` 对照全部内部结果／错误及 31 表只读，检查点不计作原定义迁移。普通维护原提交文件的全部 13 定义／28 参数已直接断言，其中 4 个预览／发现异常定义由 `CashMaintenancePreviewFailureTest` 覆盖。恢复组合、双端转账及身份合并均已接入该管线，完整验收证据见 tasks。

手工转账的双端财务编辑保留同一维护单元，按账户／币种推导转账、还款或换汇类型，保留目标的公共编辑和原内部关系编号。两端保存及关系子类型更新后验证完整图，再统一维护余额、收支、投影与收据；第二端或关系写后失败整笔回滚，另一端／关系修订及账户资格变化拒绝旧方案。原双端成功、故障和依赖变化 3 定义／14 参数由原生确认测试覆盖；另以原孤立转账 1 定义／2 参数验证详情能力关闭及单端金额拒绝。`scripts/export_cash_transfer_maintenance_contracts.py` 提供 3 个完整只读检查点，可与原 15 个普通维护检查点合并；原定义与检查点分开统计。现有 `KotlinMaintenancePlanResponseIntegrationTest` 消费本批真实 HTTP 请求、双成员方案／详情和永久收据，共享生产代码／DTO 不变。

公共备注编辑与双端财务补丁同时提交时，Python 与 Kotlin 均保留公共编辑的动作及差异；财务值未变化不再覆盖备注修改。新增原回归覆盖任一端、只改备注、同时修改金额和完全无变化，直接检查预览、保存计数、实际公共值、精确余额及同键重放；完整双库及实际 Compose 消费已通过，独立最终审查无可执行问题，命令及证据见 tasks。

维护计划发布的 [原生回归](src/test/kotlin/com/finance/tracker/backend/CashMutationPublicationOriginalTest.kt) 对应原 12 定义／40 参数：121 项合成计划验证冻结分页、CAS／TTL、私有归属、消费围栏、坏摘要、预算失败与意外 flush 回滚。内部构建器注入默认仍用真实业务图，取消及确认装载沿原权限／版本规则，未增加公开路由。设置 `FT_KOTLIN_MUTATION_PUBLICATION_CONTRACT_DIR` 运行；完整双库及实际 Compose 消费已通过，独立最终审查无可执行问题，证据见 tasks。

维护提交恢复的 [原生回归](src/test/kotlin/com/finance/tracker/backend/CashMutationRecoveryOriginalTest.kt) 对应原 9 定义／34 双库参数，覆盖局部依赖变化、无关导入、账户资格、永久消费与原键重放、清理失败、关闭方案、真实写锁和当前权限。核实使用非阻塞工作区写锁，只有锁忙返回 `pending`；其他存储错误继续传递，过期或缺失方案返回 `plan_expired`，私有归属拒绝保持原异常边界。

为 `scripts/export_cash_mutation_commit_recovery_contracts.py --help` 列出的 17 个 `--scenario` 手工准备独立空测试库，以原匿名种子导出首次预览前状态；`--output` 为仓库外共同根目录，设置 `FT_KOTLIN_MUTATION_RECOVERY_CONTRACT_DIR` 后运行 `:backend:test --tests '*CashMutationRecoveryOriginalTest' -PbackendFunctionalOnly=true`。测试自行预览并真实提交，不读取 Python 预期；运行后须重新准备写库。

```bash
PYTHONPATH=.:src:tests:scripts PYTHONDONTWRITEBYTECODE=1 .venv/bin/python scripts/export_cash_mutation_commit_recovery_contracts.py \
  --database-url "$FT_TEST_POSTGRES_URL" --output "$FT_KOTLIN_MUTATION_RECOVERY_CONTRACT_DIR" \
  --scenario current_dependency_change_rejects_before_writes_without_consuming_context_0
```

身份草稿合并复用完整维护计划及确认事务，按双方原财务事实、人工公共值、完整绑定／引用和现金部分对应验证；无法证明的对应不猜测，待处理正文保留。合并保留目标身份、转移绑定和引用、软删除源，返回目标并保持原键永久重放；问题实例／正文与旧候选证明保留。`CashDraftMergeOriginalTest` 直接覆盖原 29 定义／88 双库参数；设置 `FT_KOTLIN_DRAFT_MERGE_CONTRACT_DIR`（真实确认）和 `FT_KOTLIN_DRAFT_MERGE_GOLDEN_DIR`（隔离只读）分别运行。完整双库及实际 Compose 消费已通过，独立最终审查无可执行问题，证据见 tasks；两端既有 UI TODO 继续保留。

维护预览异常的 [原断言回归](src/test/kotlin/com/finance/tracker/backend/CashMaintenancePreviewFailureTest.kt) 使用 `scripts/export_cash_maintenance_preview_failures.py` 为四个原测试分别保存预览入口前的真实状态；设置 `FT_KOTLIN_MAINTENANCE_PREVIEW_FAILURE_DIR`，运行 `:backend:test --tests '*CashMaintenancePreviewFailureTest' -PbackendFunctionalOnly=true`。测试在具备发现种子后、实际发现器的账户别名读取处注入异常，核对单部分／显式多部分／导入均只到达一次、31 表不变，维护上下文未创建、导入原会话正文／修订及未发布方案保持。账单原文修改拒绝后详情不变，完整退款仍呈现一笔、金额为零；不改变生产代码、公共字段或 UI。

当前关系的 [预览对照](src/test/kotlin/com/finance/tracker/backend/CurrentCashRelationPlannerTest.kt) 使用 `scripts/export_current_cash_maintenance_contracts.py` 的 55 个检查点；每个场景分别准备独立测试库，设置 `FT_KOTLIN_CURRENT_PLAN_CONTRACT_DIR` 为共同仓库外输出目录。关系维护的 [原断言回归](src/test/kotlin/com/finance/tracker/backend/CurrentCashMaintenanceOriginalTest.kt) 使用 `scripts/export_current_cash_commit_contracts.py` 的 58 个场景，设置 `FT_KOTLIN_CURRENT_COMMIT_CONTRACT_DIR`；其中 35 个原定义／112 个双库参数由 Kotlin 自行预览、提交并直接断言，另含两个 HTTP 场景。当前关系提交原文件的 18 定义／58 参数、人工关联原文件的 8 定义／26 参数均已映射；后者包含既有搜索回归。证明变化在真实预览后实施，字段、第二关系成员、候选清理、投影及收据的故障在实际写入后注入，核对 31 表回滚与原方案重试；新鲜上下文读取次数在 JDBC 层观测。无效预览不得替换原方案或隐式确认关系。设置 `FT_KOTLIN_CLIENT_CONTRACT_DIR` 后，运行 `:shared:testAndroidHostTest --tests '*KotlinCurrentMaintenanceResponseIntegrationTest'` 验证实际回包经过现有 Compose 仓库与领域映射。两个生成器的运行环境为 `PYTHONPATH=.:src:tests`；写入夹具被消费，复跑须重新准备。低层写入及提交故障专项沿用相同预览生成器，分别在 `remove`、`rollback`、`service`、`service_commit`、`service_lost` 子目录准备 `current_explicit_removal_marks_the_existing_confirmed_relation_effect_s2`，设置 `FT_KOTLIN_CURRENT_WRITER_CONTRACT_DIR` 后运行对应测试类。

此运行时处于全量迁移过程中，已实现认证、工作区、邀请与成员接口，分类目录读取、创建、编辑、排序、删除检查、原子删除及单项/批量分类，以及账户按 ID 的读写、现金/投资列表、永久收据的五个 GET、管理员投影状态查询/重建、账本类型选项、手工收支新建、完整手工转账创建及旧现金新增/余额校准，以及导入会话、预览、读取、确认、当前流水关联候选、旧流水列表及旧交易查询读取和流水／已选收支删除、普通草稿保存、账户别名登记，以及投资事件写入／列表／账单原文读取和资金调拨待审核／扫描／确认／驳回，以及持仓读取／刷新／SSE、投资导入与同步，共接入 87/87 个 HTTP 操作。维护计划和持仓接口已完成完整合同验收；投资导入完整双库功能覆盖与独立复核已通过，同步已接入、原交易所双库集成与独立复核已通过；接入数不代表原测试迁移或模块验收已完成。匿名双库验证与剩余专项见 [实施证据](../../openspec/changes/archive/2026-11-04-kotlin-backend-shared-core/tasks.md)，全部接口状态见 [迁移盘点](../../openspec/changes/archive/2026-11-04-kotlin-backend-shared-core/inventory.md)。

当前收支详情、按流水定位及收支证据的三个 GET 已实现，已完成双库功能、现有 Compose 消费测试及独立复核。读取在同一财务快照中取得版本、金额和实际成员，返回前重新核对当前权限；维护接口已完成合同验收，其余模块的原组合测试仍待迁移。

证据返回根记录、关联流水及现金部分、已确认关系、非活动关系提示和退款时间线，资金调拨信息仅含原白名单；不返回账单原文或待采用正文。来源成员消费者支持草稿空值，旧 `EvidenceDto` 对空金额／空时间仍解码失败，这一客户端既有限制保留；单条流水详情及其现有来源消费者已接入；完整来源规则组合原测试仍待迁移。

工作区删除的财务数据清理已由 `WorkspaceFinancialDeletionTest` 与 `WorkspaceContractTest` 覆盖。分类 update 保持 Python 的调用边界；独立 move 已维护当前投影。账户余额/估值查询、旧写入适配、财务写入、查询和后台任务均已按迁移台账接入并验证。原 Python 后端和正式运行入口保留，本预览不用于生产替换。

后端依赖独立的 `business-contracts`、`business-domain` JVM target，不依赖 Compose UI；JSON 使用 `kotlinx.serialization`。密码适配使用仅服务器依赖的 Argon2 实现，与原 Python 密码双向兼容；会话只保存 SHA-256 令牌摘要，保留有效期、当前工作区和成员权限。分类目录保留原排序、名称路径与修订，创建、移动和排序重读权限与目录修订；客户端和服务端共用祖先、循环与五层规则。注册和登录的凭据字段仅接受字符串；其他接口的现有字段转换保持原合同。

已实现的业务查询和写入使用 jOOQ OSS `3.21.9` 的生成字段与 DSL，数据库功能矩阵为 SQLite 和 PostgreSQL 18。jOOQ 复用现有受管 JDBC 事务，不自行提交、回滚或重试；JDBC 仍负责驱动配置、schema 校验和事务控制。金额、UTC 微秒与 JSON 由后端专用绑定保留，SQL 及绑定值日志关闭。表、字段和记录类型位于 [生成目录](src/generated/kotlin)，不进入公共协议或 Compose 模块。

现金字段、转正式、投影构建、关系角色及最终图的纯规则已迁入 JVM，并保留原生单测及 Python 匿名差分；投影和完整关系图已由内部全量重建与发布消费；手工收支新建和转账创建已接入同事务写入，其余现金写入仍待迁移。时间、投影与完整关系图差分分别由 `scripts/export_cash_time_goldens.py`、`scripts/export_projection_goldens.py` 和 `scripts/export_cash_relation_goldens.py` 生成，支持 `--check`，不读取数据库或真实账单。

当前角色关系写入仓库已实现严格端点与创建方式校验、按修订替换及候选整体替换；非法候选保留原状态，已确认关系按已记录角色校验。共用关系组保存层已联合校验完整图和角色证明，保留虚拟端点映射、完整重复识别、共同公共值、候选及发现失败记录；外层负责授权、投影和提交。已接入同一联合导入确认事务。

确认事务收尾层已实现冻结前后值的余额变化、手工转账快照口径、依赖关系结果与完整成员校验后的整笔公共编辑、增量投影刷新和待处理计数；逐行结果保留批内重复和跳过。已接入写锁后重验、授权、永久收据与 HTTP 确认。

联合确认前置层已实现冲突文档证据筛选、所需既有证据例外、冻结候选集合恢复及有界全新流水写入；保留 20 项门槛、100 项批次、父项/现金部分映射、来源身份/独立证明占用和外层整笔回滚。父项与现金部分按输入顺序取回 ID，别名批量保存；本轮未验证性能。已由联合提交服务及 HTTP 确认消费。

来源事项选择的纯规则已实现，覆盖正文缺省／明确清空、固定字段、现金部分归属、多个来源冲突及当前事项修订。`scripts/export_cash_source_resolution_goldens.py --check` 校验 708 组匿名输入、完整结果和正文摘要。来源正文派生已覆盖微信、支付宝、建行、工行信用卡／借记卡和工银亚洲；保留原文备注、支付渠道与退款线索，账户身份只取可信解释，人工备注优先。`scripts/export_cash_source_effects_goldens.py --check` 校验完整结果及输入不变。单条流水详情与完整维护管线已接入这些规则，其余来源／持久化模块的原组合测试继续单独迁移。

账单原始文档读取已实现平台 CSV/TSV/XLSX、建行 XLS、工银亚洲 UTF-16 TSV 及工行信用卡/借记卡 PDF；工作表的深度、物理行和单元格坐标预算复用共享守卫，XML 语法解析及 POI 单元格解码仍由后端负责；PDF 使用后端专用 PDFBox，保留页/字符/表/行/列预算、独立密码失败类型、页指纹与文档键。各行读取器由 `scripts/export_cash_statement_document_goldens.py`、`export_bank_statement_document_goldens.py` 和 `export_icbc_pdf_document_goldens.py` 生成匿名 Python 金标准对照；记录类型与对方账号规则由 `export_cash_record_type_goldens.py` 生成行组合对照。记录类型/子类型分类与对方账号规范表示已按来源行字段迁入后端，并通过匿名 Python 行组合对照；平台 CSV/TSV/XLSX、建行 XLS/工银亚洲 TSV 以及工行信用卡/借记卡 PDF 行归一也已按 Python 完整行结果对照。统一读取/归一入口、来源身份/文件绑定键、已解析文件组合及来源账户扫描、支付宝组件分摊及来源对应解析已实现并按 Python 键值对照；平台来源的支付宝交易订单号与微信交易单号按原规则必填，支付宝订单号以固定全局范围参与匹配而资金账户范围仍按账单所有者隔离；多文件批次规划已保留最大出现次数、可靠快照选择、共同结果引用和完整旧目标证据，34 个原功能定义与 49 组完整 Python 结果已验证；来源账户扫描与选择、上传文件准备纯层（严格文件命令、脱敏、范围继承、选择失效与关系证明）已实现；来源规划只读仓库和导入会话视图也已实现并按双库匿名种子对照；上传 HTTP 已接入会话创建、追加、替换、删除、取消和读取（原文件来源范围命令已移除），会话视图按来源公开 `session_label`／`session_group_ref`、解析在锁外、发布与永久收据核实同锁。导入计划公开视图、分页与冻结详情纯层已实现并按 15 组 Python 结果对照；导入逐流水操作规划纯层已实现并按双库 Python 结果对照；条目目标/公共条目编辑与关系核心、几何/镜像图、余额/损益投影与退款文本/菱形匹配与平台退款配对纯层已实现并按 Python 结果对照；来源绑定、当前问题/候选及认证空页的事务内写入仓库已实现；共用流水写入覆盖现金部分身份/序号、草稿端点拆除、公共编辑和来源问题，类型变化沿完整旧组校验后解除不兼容关系/资金关联并使候选失效。它们保留调用方事务回滚，并由联合确认消费。完整导入业务计划、预计列表/详情及预览 HTTP 已接共享解析暂存，发布核对当前修订、上一计划、原有效期、权限与永久收据，并使用对象版本条件替换；失败保留原计划。联合导入确认已在同一写锁/事务内重验并保存来源、流水、关系、成功使用证明的账户对应、余额、公共值、投影和永久收据。原键重放先查永久收据，提交调用前后故障均保留未知状态并按原键核实；暂存清理失败不改变已提交结果。真实 R2 连通性与真实账单样本属外部环境验证，本地未运行；原组合测试已按台账完成或保留，迁移台账无 pending。

导入预览的实际 Ktor 回包目录可运行 `:shared:testAndroidHostTest --tests com.finance.tracker.KotlinImportPreviewResponseIntegrationTest`，验证现有 Compose 预览请求、计划/冻结详情解码、领域映射和业务错误消费，且不重复请求。确认实际回包可运行 `:shared:testAndroidHostTest --tests com.finance.tracker.KotlinImportCommitResponseIntegrationTest`，核对收据/结果、原键核实、确定未提交/未知状态和诊断消费，且不自动重试。这些消费者测试还验证实际 409 重验响应的原行级诊断及不自动重试；不代替完整原测试或 UI 验收。

来源账户对应的内部仓库和只读建议已实现：按完整来源组查询已确认对应、窄范围旧对应和完整账户别名，重新核对当前账户资格；发布在调用方事务内执行，同值不改变修订。[导入资金组扫描及账户选择准备](src/main/kotlin/com/finance/tracker/backend/CashImportFunding.kt) 已实现，按完整身份区分来源，保留明确分摊、优惠排除和当前账户资格；微信按账单昵称（缺失时按文件）形成会话来源且不参与账户对应建议，121 组 Python 完整结果及原生边界回归通过。上传及实际成功证明的提交已接入联合导入流程。

解析会话已实现私有 JSON 编解码、独立文件/整体预算、用户与工作区隔离、原到期时间、条件替换、取消清空、不可变计划快照与有界公开页缓存。内存存储和 R2 条件对象端口使用同一生命周期测试；R2 端口保留未知结果、关闭读取流并按元数据分页清扫。AWS S3 SDK 客户端和后台清扫器已接本机运行入口，启动首扫失败则拒绝启动，停止时等待在途扫描并关闭客户端；对象请求保留条件头，结果未知不自动重试。维护计划已接同一配置存储，支持内存和 R2，实例替换后可读取原方案；财务提交成功后清空临时正文，清空失败不改变永久收据。上传文件准备纯层、只读来源规划/会话视图和上传 HTTP 已接入此存储与工作区事务；导入预览、选择和确认已接线；完整导入原测试已按台账完成或保留；真实 R2 与真实账单专项属外部环境验证，本地未运行。匿名格式对照由 `scripts/export_parsed_import_codec_goldens.py --output compose/backend/src/test/resources/python-parsed-import-codec.json --check` 验证。

已接入的单流水维护提交在同一工作区写锁内按冻结输入重建，核对操作指纹、账户资格、事项和收支快照；依赖变化时不写财务数据、不消费方案。草稿保存保留外部事项，来源待处理事项阻止转正式，公共人工值与事项处理同事务写回。完整关系、多成员和剩余原测试仍待迁移。

稳定收支对象仓库已有双库原生测试，覆盖公共人工值、成员恢复值、合并冲突、拆分继承和事务内对象身份。该仓库已接入全量重建、新工作区初始化和内部增量维护；手工收支新建和转账创建已使用该仓库，其余公开现金写入仍待迁移。投影维护须在同一事务共用仓库上下文并完成 flush，不能跨事务复用缓存。保存点创建前保存既有脏值，回滚后恢复收支、成员和当前状态缓存。分类独立 move 服务已在同事务维护使用中的投影；update 按 Python 现状保留调用边界。

SQL 源值适配保留 SQLite 金额表示、PostgreSQL 补零归一及 Python 微秒时间；固定小数、Unicode 键序、浮点拒绝和 UTF-8 摘要由 `scripts/export_cash_canonical_goldens.py --check` 的 1,548 个匿名向量验证。内部投影源读取、确认闭包和局部关系上下文已有双库测试；完整上下文与摘要通过 `scripts/export_projection_source_contracts.py` 的匿名 SQL 样本对照。联合校验在准备决定前重新核对版本、完整组、端点和角色证据，返回解析份额，不写关系或发布数据集。全量重建、暂存发布和内部增量维护已有双库测试；增量只替换受影响完整组，独立项及相同结构保留行身份。手工收支新建与转账创建已接入；转账编辑和完整维护已完成合同验收，导入已接入联合确认。持久化图的原生测试另核实双账户真实分配、部分菱形、拆旧组及 80 节点交替闭包预算；参数预算按实际 SQL 执行统计。

内部来源证明绑定同一仓库和受管 JDBC 事务，提交、回滚、SQL 异常或连接关闭后拒绝旧证明；自动提交查询不能签成一次来源快照。SQLite 物理事务与 PG 隐式事务分别处理，普通 savepoint 保留外层身份。标准 JDBC 归属链保留受管连接，原生驱动 unwrap 和批次中的事务控制明确拒绝，普通 DML batch 保留。发布已消费同仓库/同事务证明，并重新核对来源摘要、事实及成员数量。暂存替换保留 900 条批次与完整角色/序号，旧数据集清理、稳定收支与活动指针同事务提交；非存储失败独立记录安全诊断，原活动指针/来源修订变化时不覆盖。新工作区创建复用该管线，管理员重建 HTTP 已接入并通过双库验证。

手工转账保留现金、贷款、借款及证券/加密账户组合；双侧事件、现金部分关系、余额与投影在同一事务保存，完整响应由既有 Compose 创建 API 消费。同币种显式到账金额按原合同忽略并返回警告，跨币种要求到账金额；不新增手续费或客户端流程。匿名对照由 `scripts/export_cash_transfer_contracts.py` 准备新隔离双库，纯事件/余额对照由 `scripts/export_cash_transfer_rules.py --check` 验证。

旧现金新增和余额校准分别使用 `POST /api/v1/cashflow/add` 与 `POST /api/v1/cashflow/checkin`。新增保留原来源、日期响应和金额精度；成功响应无法编码时整笔回滚。余额校准保存零金额事实，仅设置指定币种的余额，保留其他币种；快照结构损坏与金额错误保持原状态及检查顺序。两个入口都在锁后及返回前重验当前权限。Compose 当前没有这两个旧接口的消费者，本片不增加页面或客户端入口。

来源问题处理的 [原生回归](src/test/kotlin/com/finance/tracker/backend/CashSourceIssueOriginalTest.kt) 已覆盖原文件全部 22 定义／78 双库参数：采用、保留现有值、明确清空与旧格式正文，人工备注／清空和分类，来源冲突、权限、事项变化及真实写后回滚。采用后的来源说明贯穿后续补丁；来源固定字段先校验，预览保留受保护字段的 incoming／effective／protected 差异，所选事项编号按字符串排序。只处理所选事项，公共人工值、来源字段、余额、投影和永久收据同事务。

`scripts/export_cash_source_issue_contracts.py` 为 `--help` 列出的 41 个场景分别准备独立空测试库，传入 `--database-url`、共同的仓库外 `--output` 和 `--scenario`，运行环境为 `PYTHONPATH=.:src:tests:scripts`。39 个原参数组合与 2 个补充场景分别统计；补充覆盖旧正文保护及编号 `10`／`2` 的顺序，不增加原定义数。设置 `FT_KOTLIN_SOURCE_ISSUE_CONTRACT_DIR` 运行真实提交，另以隔离的 `FT_KOTLIN_SOURCE_ISSUE_GOLDEN_DIR` 对照完整只读计划。执行 `:backend:test --tests '*CashSourceIssueOriginalTest' -PbackendFunctionalOnly=true`，25 个 JUnit 方法验证原断言、完整图和补充边界；写库复跑须从初始状态恢复。设置 `FT_KOTLIN_CLIENT_CONTRACT_DIR` 后，以 `:shared:testAndroidHostTest --tests '*KotlinMaintenancePlanResponseIntegrationTest'` 验证四种真实 HTTP 来源采用回包经过既有 Compose 仓库／领域映射，包括待采用正文、公共人工值、所选事项与收据。

确认独立记录的 [原生回归](src/test/kotlin/com/finance/tracker/backend/CashIndependentIdentityOriginalTest.kt) 覆盖原 9 定义／46 双库参数。只结束所选身份问题，保留同一流水和全部绑定；不转正式时字段、父修订和余额不变。可同次采用来源、明确清空或转正式，后续重复导入继续命中该记录。完整绑定摘要包含实例编号、创建时间、修订、解析器及覆盖证据；绑定变化或活动归属冲突拒绝旧计划，微信范围保持当前可信／文件／未绑定隔离。认证页冲突的预览 HTTP 保留 Python 的 422 安全响应；真实流水、投影和收据写后失败均整笔回滚。

`scripts/export_cash_independent_identity_contracts.py` 为 `--help` 列出的 23 个原参数组合分别准备独立空测试库，参数为 `--database-url`、仓库外 `--output` 和 `--scenario`，运行环境 `PYTHONPATH=.:src:tests:scripts`。设置 `FT_KOTLIN_INDEPENDENT_IDENTITY_CONTRACT_DIR` 为写库目录，另设隔离的 `FT_KOTLIN_INDEPENDENT_IDENTITY_GOLDEN_DIR` 对照完整只读图；运行 `:backend:test --tests '*CashIndependentIdentityOriginalTest' -PbackendFunctionalOnly=true`。10 个 JUnit 方法分别统计，不作为原定义数。写库复跑须恢复初始状态，原键永久收据验证包含删除暂存后重放。设置 `FT_KOTLIN_CLIENT_CONTRACT_DIR` 后，以 `:shared:testAndroidHostTest --tests '*KotlinMaintenancePlanResponseIntegrationTest'` 验证两种真实独立确认 HTTP 回包经过现有 Compose 请求、详情、状态和收据消费。独立确认、草稿合并及完整维护均通过对应原合同，模块间证据分别见 tasks。

## 只解析 PDF

`POST /api/v1/cash-import/pdf/parse` 要求登录且在当前工作区具有写权限。multipart 包含一份 `metadata` JSON 文本和一份 `file:<client_file_ref>` 文件；`metadata.files` 只能有一项，复用 `client_file_ref`、`source_hint`、`password` 和 `last_modified_at_ms` 字段。密码只放请求体。文件上限 100 MiB，总请求上限 101 MiB，归一响应上限 25 MiB。

入口复用工行信用卡／借记卡的受限 PDF 子进程，返回 `rules_version` 和完整归一文档；文档保留逐行位置、来源说明、问题、错误及页证明，金额使用文本。它不需要 R2／导入暂存配置、不建立会话或计划、不读取账本、不写业务数据，解析后重新核对原用户及工作区权限。文件超限返回 413，文件／来源／密码问题返回 422，解析进程忙或超时返回可重试的 503；取消按取消语义传播。

共享 `parseCashPdf` 传输和 `localNormalizedCashDocument` 还原入口已提供，尚未接入页面的文件调度／完整本地导入。旧服务端导入路径仍保留到本次改造完成，不能据此声明离线导入已可用。

## 运行匿名预览

在 `compose/` 使用 JDK 21 构建：

```sh
./gradlew :backend:installDist --no-configuration-cache --max-workers=1
```

只连接已存在、已达到 `20261104_54` 的任务专用匿名数据库。程序不创建 schema、不升级数据库，也不自动选择其他数据库。当前 URL 支持限于绝对文件 `sqlite+pysqlite:////...` 或有 host、无 query 的 `postgresql+psycopg://...`；SQLite 默认驱动/相对路径、PG socket 与 SSL 等连接参数尚未移植，明确拒绝而非静默忽略。


`workspaces.ledger_seq` 由 Alembic 维护，托管事务按工作区合并一次推进并与事实一同回滚。账户、分类、别名、账单账户映射，以及现金流水、来源证据、条目、关系和资金对应的写入口已接入并完成独立写面审计。幂等收据重放及没有事实变化的操作不推进。

`GET /api/v1/ledger/version` 返回选定工作区的 `ledger_seq` 字符串；`GET /api/v1/ledger/snapshot` 在同一读取事务返回 `workspace_id`、`ledger_seq`、共享规则 `rules_version` 和 `facts`。快照包含全历史现金事实、组成项、来源证据、条目、关系、问题、目录与只读资金关联；保留草稿和删除标记，排除完整投资事件、账户私有配置、余额与投影。金额、标识和修订使用字符串，来源规划及操作中的修订保持 64 位整数，开放 JSON 保留原结构。当前快照为单响应，尚无分页；PostgreSQL 使用可重复读，SQLite 使用同连接 WAL 快照。返回前重新确认会话及选定工作区。共享 `buildLocalCashLedgerEntries` 已将快照派生分组与持久化收支条目组合，公开显示字段取条目，来源流水保持原值；该入口不分配新身份。客户端缓存与离线导入尚未接入。

已接受且生效的只读资金关系另带 `investment_event`，只复制关联资金事件的账户、币种与 from_amount／to_amount，保留精确金额及 null；其余关系该字段为 null，不返回投资正文或完整投资账本。`LedgerFundingSnapshotContractTest` 验证确认／回滚、账户改名、现金删除及版本联动、状态过滤和其他工作区隔离。删除先解除全部物化数据集的资金关系外键，再清目标关系，失败同事务回滚；原投资事件保持。

```sh
curl "$API_ORIGIN/api/v1/ledger/version" -H "Authorization: Bearer $SESSION_TOKEN"
curl "$API_ORIGIN/api/v1/ledger/snapshot" -H "Authorization: Bearer $SESSION_TOKEN"
```

```sh
FT_KOTLIN_PREVIEW=true \
FT_IMPORT_STAGING_BACKEND=memory \
FT_IMPORT_STAGING_ALLOW_MEMORY=1 \
FT_DATABASE_URL=sqlite+pysqlite:////absolute/path/to/anonymous_test.db \
FT_KOTLIN_PORT=18240 \
JAVA_OPTS=-Xmx512m \
backend/build/install/backend/bin/backend
```

暂存后端必须显式配置。上述本机示例使用内存；R2 使用 `FT_IMPORT_STAGING_BACKEND=r2`，并设置 `FT_R2_ENDPOINT`（HTTPS origin）、`FT_R2_BUCKET`、`FT_R2_ACCESS_KEY_ID` 和 `FT_R2_SECRET_ACCESS_KEY`，凭据留在仓库外。`FT_R2_REGION` 默认 `auto`，`FT_R2_PREFIX` 默认 `cash-import`。`FT_IMPORT_STAGING_TTL_SECONDS` 为 1–1800，清扫间隔 `FT_IMPORT_STAGING_SWEEP_SECONDS` 为 1–60，页大小 `FT_IMPORT_STAGING_SWEEP_PAGE_SIZE` 为 1–200；默认分别为 1800、30、100。真实 R2 部署连通性未验证，本轮只用本机合成 HTTP 服务核对协议。

服务仅监听 `127.0.0.1`，默认端口 `18240`。`FT_WEB_ORIGIN` 可指定无路径、凭据、query 或 fragment 的 HTTPS origin；默认 `http://127.0.0.1:5173`，另允许 `localhost` / `127.0.0.1` 的显式 HTTP 端口，不使用 Cookie 认证。没有 `FT_KOTLIN_PREVIEW=true` 时拒绝启动。

## 验证

从仓库根目录执行 `scripts/export_kotlin_contracts.py --database-url <显式测试URL> --output <仓库外任务目录>`，通过原 Alembic 和 Python 应用服务生成匿名数据库及 HTTP 黄金。SQLite 必须为新绝对路径；PostgreSQL 必须由执行者先准备空的专用 `_test` 库，脚本拒绝覆盖已有 schema，不探测或启动数据库。

认证、工作区和分类的匿名黄金分别由 `scripts/export_auth_contracts.py`、`scripts/export_workspace_contracts.py` 与 `scripts/export_category_contracts.py` 准备。账户、收据、分类删除和收支分类黄金由 `scripts/export_account_contracts.py`、`scripts/export_receipt_contracts.py`、`scripts/export_category_deletion_contracts.py`、`scripts/export_classification_contracts.py` 准备。投影源和数据集全列对照分别由 `scripts/export_projection_source_contracts.py` 与 `scripts/export_projection_dataset_contracts.py` 准备。上述双库生成器均要求 `--database-url`、`--oracle-database-url` 和 `--output`：两个 URL 必须指向不同的新建测试库，前者供 Kotlin 使用，后者供 Python 生成预期结果。脚本拒绝覆盖已有 schema，不读取真实账本。

在 `compose/` 为各能力设置下面的环境变量，值为对应生成器产出的仓库外目录。除基础读取外，需要目标和 Python 参考库的生成器必须显式使用两个不同的新测试数据库；每个能力使用独立目录和数据库。

| 环境变量 | 夹具生成器（仓库根目录） |
|---|---|
| `FT_KOTLIN_CONTRACT_DIR` | `scripts/export_kotlin_contracts.py` |
| `FT_KOTLIN_AUTH_CONTRACT_DIR` | `scripts/export_auth_contracts.py` |
| `FT_KOTLIN_WORKSPACE_CONTRACT_DIR` | `scripts/export_workspace_contracts.py` |
| `FT_KOTLIN_CATEGORY_CONTRACT_DIR` | `scripts/export_category_contracts.py` |
| `FT_KOTLIN_ACCOUNT_CONTRACT_DIR` | `scripts/export_account_contracts.py` |
| `FT_KOTLIN_RECEIPT_CONTRACT_DIR` | `scripts/export_receipt_contracts.py` |
| `FT_KOTLIN_CATEGORY_DELETION_CONTRACT_DIR` | `scripts/export_category_deletion_contracts.py` |
| `FT_KOTLIN_CLASSIFICATION_CONTRACT_DIR` | `scripts/export_classification_contracts.py` |
| `FT_KOTLIN_PROJECTION_SOURCE_CONTRACT_DIR` | `scripts/export_projection_source_contracts.py` |
| `FT_KOTLIN_PROJECTION_DATASET_CONTRACT_DIR` | `scripts/export_projection_dataset_contracts.py` |
| `FT_KOTLIN_PROJECTION_REBUILD_CONTRACT_DIR` | `scripts/export_projection_rebuild_contracts.py` |
| `FT_KOTLIN_PROJECTION_INCREMENTAL_CONTRACT_DIR` | `scripts/export_projection_incremental_contracts.py` |
| `FT_KOTLIN_MANUAL_CASH_CONTRACT_DIR` | `scripts/export_manual_cash_contracts.py` |
| `FT_KOTLIN_TRANSFER_CONTRACT_DIR` | `scripts/export_cash_transfer_contracts.py` |
| `FT_KOTLIN_LEGACY_CASHFLOW_CONTRACT_DIR` | `scripts/export_legacy_cashflow_contracts.py` |
| `FT_KOTLIN_CURRENT_PROJECTION_CONTRACT_DIR` | `scripts/export_current_projection_contracts.py` |
| `FT_KOTLIN_EVIDENCE_CONTRACT_DIR` | `scripts/export_evidence_contracts.py` |
| `FT_KOTLIN_IMPORT_PREPARATION_CONTRACT_DIR` | `scripts/export_cash_import_preview_preparation_contracts.py` |
| `FT_KOTLIN_SOURCE_WRITE_CONTRACT_DIR` | `scripts/export_cash_source_write_contracts.py` |
| `FT_KOTLIN_RECORD_WRITER_CONTRACT_DIR` | `scripts/export_cash_record_writer_contracts.py` |
| `FT_KOTLIN_CURRENT_RELATION_CONTRACT_DIR` | `scripts/export_current_cash_relation_contracts.py` |
| `FT_KOTLIN_PLAN_RELATION_WRITE_CONTRACT_DIR` | `scripts/export_cash_plan_relation_write_contracts.py` |
| `FT_KOTLIN_PLAN_FINALIZE_CONTRACT_DIR` | `scripts/export_cash_plan_finalize_contracts.py` |
| `FT_KOTLIN_IMPORT_COMMIT_PREPARATION_CONTRACT_DIR` | `scripts/export_cash_import_commit_preparation_contracts.py` |
| `FT_KOTLIN_IMPORT_COMMIT_CONTRACT_DIR` | `scripts/export_cash_import_commit_contracts.py`（确认命令完整对照，另传独立 `--oracle-database-url`） |
| `FT_KOTLIN_IMPORT_ORIGINAL_COMMIT_CONTRACT_DIR` | `scripts/export_cash_import_original_commit_contracts.py`（逐场景原断言夹具） |
| `FT_KOTLIN_IMPORT_ORIGINAL_OPERATION_CONTRACT_DIR` | `scripts/export_cash_import_original_operation_contracts.py`（原操作规划的匿名输入） |
| `FT_KOTLIN_IMPORT_ORIGINAL_BUSINESS_CONTRACT_DIR` | `scripts/export_cash_import_original_business_contracts.py`（原业务计划的匿名输入／当前状态） |
| `FT_KOTLIN_ASSOCIATION_CONTRACT_DIR` | `scripts/export_cash_association_contracts.py`（当前流水关联候选 HTTP 与原只读断言） |
| `FT_KOTLIN_PROJECTION_LIST_CONTRACT_DIR` | `scripts/export_cash_projection_list_contracts.py`（当前账本列表、筛选与分页） |
| `FT_KOTLIN_PROJECTION_ORIGINAL_QUERY_CONTRACT_DIR` | `scripts/export_cash_projection_original_query_contracts.py`（原查询断言的真实预置状态） |
| `FT_KOTLIN_LEGACY_SEARCH_CONTRACT_DIR` | `scripts/export_legacy_cash_record_search_contracts.py`（旧流水列表 HTTP 与原读取断言） |
| `FT_KOTLIN_DRAFT_SAVE_CONTRACT_DIR` | `scripts/export_cash_draft_save_contracts.py`（普通草稿保存原预置状态及 HTTP） |
| `FT_KOTLIN_DELETION_CONTRACT_DIR` | `scripts/export_cash_deletion_contracts.py`（流水／已选收支删除的原预置状态及 HTTP） |

查询原断言的 [原生回归](src/test/kotlin/com/finance/tracker/backend/CashProjectionOriginalQueryTest.kt) 使用表中生成器，为 `--help` 列出的 10 个场景分别准备独立空测试库，传入 `--database-url`、共同 `--output` 根目录和 `--scenario`，运行环境为 `PYTHONPATH=.:src:tests`；设置表中环境变量后，在 `compose/` 运行 `./gradlew :backend:test --tests '*CashProjectionOriginalQueryTest' -PbackendFunctionalOnly=true`。生成器执行原测试到首次查询前，只保存实际状态；退款输入来自原关系工厂，不保存预期查询结果。`index_version`、`cursor_version`、`snapshot` 会执行真实重建，复跑须准备新库。新增 13 个方法与现有列表测试共同对应原查询文件全部 23 定义／40 参数；月汇总直接调用共享 `CashProjectionMonthlyRules`，原生仅适配浏览时区及 BigDecimal 文本；未知日期保留位置，统计资格与完整精度累计保持。`summarizeLocalCashProjections` 消费完整有序选择，端侧筛选和页面仍待接入。SQL 观测验证版本页不重复计算月份信息，以及重建交错仍属于同一快照。

旧流水列表的 [原生回归](src/test/kotlin/com/finance/tracker/backend/LegacyCashRecordSearchTest.kt) 使用对应生成器，为 `--help` 列出的 8 个场景分别准备独立空测试库，传入 `--database-url`、共同的仓库外 `--output` 和 `--scenario`；环境同上，设置 `FT_KOTLIN_LEGACY_SEARCH_CONTRACT_DIR` 后运行 `./gradlew :backend:test --tests '*LegacyCashRecordSearchTest' -PbackendFunctionalOnly=true`。原四个读取定义／七个参数由 Kotlin 直接核对断言，完整 HTTP 对照另覆盖字段、草稿、筛选和错误。`snapshot`、`revoke` 会修改其专用库，复跑须重新准备。GET `/cash-records` 保留原时间及编号游标和 1–20 页长，包含人工流水及草稿；当前 Compose 没有该 GET 的实际消费者，不据此新增页面或宣称客户端接线。

普通草稿保存的 [原生回归](src/test/kotlin/com/finance/tracker/backend/CashDraftSaveContractTest.kt) 使用对应生成器，为 `--help` 列出的 48 个场景分别准备独立空测试库，传入 `--database-url`、共同的仓库外 `--output` 和 `--scenario`；`http` 另需不同的显式 `--oracle-database-url`。生成器保存场景索引及原测试待保存前的输入，运行环境为 `PYTHONPATH=.:src:tests`。设置 `FT_KOTLIN_DRAFT_SAVE_CONTRACT_DIR`，在 `compose/` 执行 `./gradlew :backend:test --tests '*CashDraftSaveContractTest' -PbackendFunctionalOnly=true`。原 17 定义／82 参数由 Kotlin 直接断言，另核对完整 HTTP、31 表回滚、真实 COMMIT 失败／响应丢失、已提交后的详情读取失败／删除和 PG 锁后降权。保存保持草稿、不写余额，成功收据独立于当前详情；重新执行须准备全新库。设置 `FT_KOTLIN_CLIENT_CONTRACT_DIR` 后，以 `:shared:testAndroidHostTest --tests '*KotlinDraftSaveResponseIntegrationTest'` 验证实际回包通过现有 Compose 保存接口与领域映射。完整关系维护和其他原组合测试继续待迁移。

删除的 [原生回归](src/test/kotlin/com/finance/tracker/backend/CashDeletionContractTest.kt) 使用对应生成器，为 `--help` 列出的 29 个场景分别准备独立空测试库，传入 `--database-url`、共同的仓库外 `--output` 和 `--scenario`；`api` 另需不同的显式 `--oracle-database-url`。设置 `FT_KOTLIN_DELETION_CONTRACT_DIR`，在 `compose/` 运行 `./gradlew :backend:test --tests '*CashDeletionContractTest' -PbackendFunctionalOnly=true`。场景执行原测试至待验证操作前，Kotlin 重写原 17 定义／40 参数的断言，另对照完整 HTTP；SQLite 跳过仅 PG 适用的真实锁队列测试。删除、重新导入、降权及换区会修改专用库，复跑须重新准备。设置 `FT_KOTLIN_CLIENT_CONTRACT_DIR` 后，以 `:shared:testAndroidHostTest --tests '*KotlinDeletionResponseIntegrationTest'` 验证实际回包经过现有 Compose 仓库与领域映射。单流水旧 DELETE 无当前客户端调用者，批量预览与删除使用现有调用；完整关系维护及其余原组合测试仍待迁移。

当前账本列表的 [原生回归](src/test/kotlin/com/finance/tracker/backend/CashProjectionListContractTest.kt) 使用表中生成器，为 `--help` 列出的 9 个场景分别准备独立目标与参考空测试库，传入 `--database-url`、不同的 `--oracle-database-url`、共同 `--output` 根目录和 `--scenario`；运行环境为 `PYTHONPATH=.:src:tests`。在 `compose/` 执行 `./gradlew :backend:test --tests '*CashProjectionListContractTest' -PbackendFunctionalOnly=true`。测试覆盖原完整 HTTP、游标与序号分页、筛选后的统计与月份位置、全局选项、精确金额、草稿、已删除成员、版本及安全失败；`snapshot`／`revoke` 会修改事实，复跑须重新准备。设置 `FT_KOTLIN_CLIENT_CONTRACT_DIR` 后，实际回包可供 `:shared:testAndroidHostTest --tests '*KotlinProjectionListResponseIntegrationTest'` 验证现有 Compose 列表调用及领域映射。带版本的后续序号页省略月份数据并在 SQL 计数；分类树在同次读取内仅加载一次。

关联候选的 [原生回归](src/test/kotlin/com/finance/tracker/backend/CashAssociationContractTest.kt) 使用表中生成器，为 `--help` 列出的 9 个场景分别准备独立空测试库，传入 `--database-url`、共同 `--output` 根目录和 `--scenario`，运行环境为 `PYTHONPATH=.:src:tests`。在 `compose/` 执行 `./gradlew :backend:test --tests '*CashAssociationContractTest' -PbackendFunctionalOnly=true`；测试覆盖原完整 HTTP 响应、闭包排除、分页、精确金额、本地日期、权限、安全失败和同快照读取。`revoke`、`snapshot` 场景会修改事实，复跑必须重新准备。设置 `FT_KOTLIN_CLIENT_CONTRACT_DIR` 后，实际回包可供 `:shared:testAndroidHostTest --tests '*KotlinAssociationResponseIntegrationTest'` 验证现有 Compose 消费者。这里只接通候选读取，完整关联预览／提交仍待迁移。

完整业务计划的 [原断言回归](src/test/kotlin/com/finance/tracker/backend/CashImportOriginalBusinessTest.kt) 使用表中生成器，为 `--help` 列出的 18 个场景分别准备独立空测试库，传入 `--database-url`、共同 `--output` 根目录和 `--scenario`；运行环境为 `PYTHONPATH=.:src:tests`。在 `compose/` 执行 `./gradlew :backend:test --tests '*CashImportOriginalBusinessTest' -PbackendFunctionalOnly=true`。场景包含来源／关系／候选的真实预置状态，不生成预期计划；候选变化用例会修改事实，重新执行必须准备新库。原文件全部 30 定义／84 参数以实际构建结果验证；无新建／转正式种子时跳过关系发现。原 monkeypatch 使用实例内部阶段／图构建依赖适配，默认仍为真实实现，无新增 HTTP 或配置字段。

操作规划的 [原断言回归](src/test/kotlin/com/finance/tracker/backend/CashImportOriginalOperationTest.kt) 使用表中生成器，以 `--database-url`、共同根目录 `--output` 和 `--scenario` 逐一准备 `--help` 列出的 13 个独立空测试库；在 `compose/` 执行 `./gradlew :backend:test --tests '*CashImportOriginalOperationTest' -PbackendFunctionalOnly=true`。`base` 由只读用例共用，其他场景预置原来源绑定、人工保护、草稿问题及账户状态；版本字段通过原 ORM 更新，所有规划调用禁止写入 SQL 并比较完整业务表快照。生成器只保存原输入；原生测试直接核对原文件全部 36 定义／94 参数，包括已有来源更新／时间顺序、公共备注、固定字段冲突、草稿补全、现金部分身份、内部错误停止及完整 typed／wire 输入不变性。其他原业务计划等文件仍待迁移。

解析上下文的 [原生回归](src/test/kotlin/com/finance/tracker/backend/CashImportOriginalContextTest.kt) 使用 `scripts/export_cash_import_context_contracts.py` 逐场景准备独立空库，参数同下方会话生成器；设置共同根目录 `FT_KOTLIN_IMPORT_CONTEXT_CONTRACT_DIR` 后，在 `compose/` 执行 `./gradlew :backend:test --tests '*CashImportOriginalContextTest' -PbackendFunctionalOnly=true`。它核对解析在工作区锁外、解析后权限／消费、私有工作流及真实银行字节和加密 PDF；角色／收据用例必须重新准备后重跑。工银亚洲 TSV 以严格逻辑记录读取，备注内引号、制表符及换行保留，非法引号返回文件不可读；私有编辑上下文的导入核实在无收据时返回 `not_committed`，不能用于导入。

会话 HTTP 的 [原生回归](src/test/kotlin/com/finance/tracker/backend/CashImportOriginalSessionApiTest.kt) 使用 `scripts/export_cash_import_session_contracts.py`。为其 `--help` 列出的每个场景手工准备独立空测试库，传入 `--database-url`、共同输出根目录 `--output` 和 `--scenario`；设置 `FT_KOTLIN_IMPORT_SESSION_CONTRACT_DIR` 为共同根目录，在 `compose/` 运行 `:backend:test --tests '*CashImportOriginalSessionApiTest'`。生成器只准备原账本、认证和解析文档，不生成预期响应。测试通过实际 Ktor 部件观察读取／释放，覆盖元数据拒绝、密码重试、文件范围、用户边界、过期／永久收据与读取失败；内部 8 字节测试预算不改变生产 100 MiB 上限。已提交和修改角色的场景必须重新准备后重跑。

导入对象寿命的 [原生回归](src/test/kotlin/com/finance/tracker/backend/CashImportPlanLifetimeTest.kt) 使用原确认生成器的 `lifetime` 场景，沿用 `FT_KOTLIN_IMPORT_ORIGINAL_COMMIT_CONTRACT_DIR`。准备包装在最终快照前、快照在条目选择前退出构建器持有范围；JVM 弱引用及有限次显式 GC 验证可回收性，冻结操作和条目目标保持列表。该合同不按耗时验收，也不替代性能测试。

原计划 API 的 [Ktor 回归](src/test/kotlin/com/finance/tracker/backend/CashImportOriginalPlanApiTest.kt) 使用独立匿名种子和真实上传／预览／确认／恢复路由；来源、关系、收据和提交响应丢失的故障位于实际 JDBC 边界。为 `scripts/export_cash_import_plan_api_contracts.py --help` 列出的每个 `--scenario` 分别准备空测试库，设置 `FT_KOTLIN_IMPORT_PLAN_API_CONTRACT_DIR` 为共同输出根目录：

```bash
PYTHONPATH=.:src:tests PYTHONDONTWRITEBYTECODE=1 .venv/bin/python scripts/export_cash_import_plan_api_contracts.py \
  --database-url "$FT_TEST_POSTGRES_URL" --output "$FT_KOTLIN_IMPORT_PLAN_API_CONTRACT_DIR" --scenario lifecycle
cd compose
./gradlew :backend:test --tests '*CashImportOriginalPlanApiTest' -PbackendFunctionalOnly=true
```

该生成器只提供原输入，不生成预期结果；SQLite 逐场景传不同的绝对测试库路径。写入用例会消费数据库，重跑须重建。导入接口的认证和只读角色拒绝均返回完整公开错误字段，且先于正文解析；403 实际回包由现有 Compose 预览消费者验证，不自动重试。

原计划发布断言由 `CashImportOriginalPublicationTest` 直接验证，不使用 Python 预期回包。为 `scripts/export_cash_import_publication_contracts.py --help` 列出的每个 `--scenario` 手工准备独立空测试库，运行下列命令；`--output` 是全部场景的共同根目录，生成器在其下建立场景子目录。设置 `FT_KOTLIN_IMPORT_PUBLICATION_CONTRACT_DIR` 为此根目录后运行测试。生成器只使用原匿名账本和认证种子；121 条合成条目、畸形构建结果及原断言都在 Kotlin 中定义。消费和权限测试会修改各自隔离库，重跑必须重新准备。

```bash
PYTHONPATH=.:src:tests PYTHONDONTWRITEBYTECODE=1 .venv/bin/python scripts/export_cash_import_publication_contracts.py \
  --database-url "$FT_TEST_POSTGRES_URL" --output "$FT_KOTLIN_IMPORT_PUBLICATION_CONTRACT_DIR" --scenario publication
cd compose
./gradlew :backend:test --tests '*CashImportOriginalPublicationTest' -PbackendFunctionalOnly=true
```

SQLite 同样逐场景显式传入不同的 `sqlite+pysqlite:////绝对路径/测试.db`，不设置 PostgreSQL URL。计划发布保留修订、原有效期、旧计划及输入不可变性；缺省详情映射可为空，显式 `null` 的处理明细或文件结果会在发布前拒绝。

原确认断言夹具需为生成器 `--help` 中的每个 `--scenario` 准备独立空测试库，以 `--output <仓库外根目录>/<scenario>` 输出；设置 `FT_KOTLIN_IMPORT_ORIGINAL_COMMIT_CONTRACT_DIR` 为该根目录。生成器复用原匿名账本、editor、现金/贷款账户和已解析文件，运行时环境为 `PYTHONPATH=.:src:tests`。Kotlin 自行预览、提交并直接断言，不消费 Python 预期结果；批量故障在实际 JDBC 来源写入后触发。场景还包括镜像与全额退款整组、收据随当前拆分/删除定位、并发一次消费、旧计划拒绝、来源更新及草稿组成项替换/序号交换；关系/认证空页故障在实际写入后验证整体回滚。确认原测试文件的 44 个定义已逐项对应；关系发现内部编程错误停止预览，重验错误码转换保留更精确的原诊断，并补齐文件位置。其他原测试和完整迁移仍待完成。数据库和夹具会被消费，重新执行写入测试须重新准备，不能复用旧收据状态。

PG 另以 `FT_KOTLIN_TEST_DB_USER` / `FT_KOTLIN_TEST_DB_PASSWORD` 提供测试凭据。运行当前功能验收：

```bash
./gradlew :backend:test :backend:codegenTest :backend:installDist -PbackendFunctionalOnly=true
```

`backendFunctionalOnly=true` 仅排除 `CategoryDeletionPerformanceTest` 和 `CashClassificationPerformanceTest`，保留其他原生断言、事务、并发及安全测试；不传该参数时维持原测试集合和性能预算。测试不探测或启动数据库，也不复用另一数据库的 Gradle 结果。已启动本机 Netty 预览后，可从根目录使用 `scripts/verify_kotlin_http.py --origin http://127.0.0.1:18240 --fixtures <读取黄金目录>` 对比读取响应；工具只接受显式 loopback 地址，不启动服务。

## jOOQ 代码生成

普通构建直接使用已校验的生成代码，无需连接数据库。重新生成时，先由执行者准备 PostgreSQL 18 的空 `_test` 数据库，再使用上述基础夹具生成器运行现有 Alembic 迁移。schema 事实源仍为原迁移，不手写或单独维护 jOOQ 表定义。

在 `compose/` 使用 JDK 21 执行：

```bash
FT_JOOQ_CODEGEN_URL=jdbc:postgresql://127.0.0.1:55432/finance_jooq_test \
FT_JOOQ_CODEGEN_USER=postgres \
./gradlew :backend:generateJooq
```

按需通过 `FT_JOOQ_CODEGEN_PASSWORD` 提供密码；连接串不得包含凭据、查询参数或 fragment。生成器只读检查 PostgreSQL 18+ 和 `20261028_51`，随后调用官方 KotlinGenerator。可追加 `-PjooqOutput=<仓库外比较目录>`，将结果与生成目录逐文件比较。`./gradlew :backend:codegenTest` 不连接数据库，验证非法目标和格式错误不会输出代码或泄露连接串。

管理员真实 HTTP 黄金由 `scripts/export_projection_rebuild_contracts.py` 按相同双库参数准备；运行完整测试还须设置 `FT_KOTLIN_PROJECTION_REBUILD_CONTRACT_DIR` 为其输出目录。

增量全列黄金由 `scripts/export_projection_incremental_contracts.py` 按相同双库参数准备；完整测试设置 `FT_KOTLIN_PROJECTION_INCREMENTAL_CONTRACT_DIR` 为其输出目录。七步操作包含拆组、独立刷新、草稿新增/移除和人工备注，原生测试另核实实际行身份、保存点缓存及晚期失败回滚。

手工新建、转账、旧现金新增/校准的完整 HTTP 与财务表对照分别由 `scripts/export_manual_cash_contracts.py`、`scripts/export_cash_transfer_contracts.py`、`scripts/export_legacy_cashflow_contracts.py` 按上述双库参数准备。完整后端测试还须设置 `FT_KOTLIN_MANUAL_CASH_CONTRACT_DIR`、`FT_KOTLIN_TRANSFER_CONTRACT_DIR` 和 `FT_KOTLIN_LEGACY_CASHFLOW_CONTRACT_DIR`。旧现金对照在每个后端包含 96 个 HTTP 场景，逐项核对响应及 13 张财务表全部列，失败必须没有财务写入。

公共规则黄金由 `scripts/export_shared_rule_goldens.py` 从当前 Python 领域生成，`--check` 验证内容；需要项目 Python 3.11 / Unicode 14 oracle。它不访问数据库、网络或真实账单。共享测试可运行 `:business-domain:jvmTest`、`:business-domain:jsNodeTest`、`:business-domain:wasmJsNodeTest`，无需打开浏览器。

非凭据字段的旧文本转换由 `scripts/export_python_string_goldens.py --check` 与 `PythonStringGoldenTest` 验证，覆盖 Python Unicode 14、随机有限浮点数、所有有限二进制幂及相邻值。旧名称和角色字段沿用该文本转换；资金角色兼容函数按原合同复用有限浮点转文本后，再执行精确十进制校验，关系端点继续拒绝浮点。金额运算始终使用精确十进制。

实际回包验证先设置 `FT_KOTLIN_CLIENT_CONTRACT_DIR=<仓库外输出目录>` 执行上述后端功能测试，再使用相同环境变量执行 `:shared:testAndroidHostTest --tests '*Kotlin*ResponseIntegrationTest'`。当前覆盖分类、账户、收据、选项、新建、转账和当前成员；对每个数据库分别生成并消费。客户端通过正式 API 解码 Ktor 读写字节；分类写入取得 `id/revision` 确认后沿用目录刷新，目录名称路径按真实父 ID 转为领域祖先对象。

Python 单测迁移对应表为 `compose/test-migration.json`。从根目录执行 `scripts/check_kotlin_test_migration.py --collection <完整 pytest --collect-only -q 输出> --report <仓库外报告>` 检查源定义、展开参数、夹具及目标；追加 `--require-complete` 后，任何未迁移项都会失败。授权修复新增的源回归通过 `source_additions` 记录当前指纹和理由，仍按同一断言及目标门禁验收，固定基线不变。规则黄金和 Python 参考回归不能代替 Kotlin 原生测试；当前完整迁移门禁已通过（2,726 定义／6,272 用例，2,352 migrated／374 retained／0 pending）。本轮按用户要求仅验收功能，性能测试未作为完成门槛，原性能预算和未通过证据见实施记录。

名称 casefold、币种大写与数字字符数据由 `scripts/export_category_casefold.py --check` 核对，沿用 Python 3.11 / Unicode 14。分类移动已同步修复 Python 与 Kotlin 的后代深度计算；仅影响后续命令，不自动修复现有数据库。

账户命令保留名称/币种规范化、固定类型、当前修订与永久原键重放；账户、余额快照和结果收据一起提交。删除必须先停用且没有真实引用或非零/不可读余额；删除后仍可核实原结果。存储结果未知返回 `account_result_unknown` 和 `commit_state=unknown`，沿原键核实；查询不可用返回 `account_unavailable`。当前原生专项包含 editor 成功路径、viewer 拒绝、锁等待后撤权、晚期回滚及提交后响应丢失。

同样的实际 Ktor 回包目录可运行 `:shared:testAndroidHostTest --tests com.finance.tracker.KotlinAccountResponseIntegrationTest`，验证普通账户读取、导入账户读取与读写命令消费共享 DTO。账户列表按现金/投资视图分组，保留停用账户；历史余额/估值查询不在此列表接口中。

永久收据读取保留操作者和当前工作区隔离、旧键原文查询、行/关系分页及当前投影位置；读取也锁定工作区并复核当前权限。收据摘要只输出公开字段，损坏记录返回安全诊断。服务端保留任意精度非负计数；现有 Compose 仍使用 `Long`，超范围计数是未解决的客户端边界。实际回包目录可运行 `:shared:testAndroidHostTest --tests com.finance.tracker.KotlinReceiptResponseIntegrationTest`，验证现有 API 的读取消费及该限制。维护专用提交及原键核实、导入解析暂存和预览条件发布已接入；导入确认及其原键核实已接线，完整原组合测试和真实存储/账单专项仍待完成。

永久收据仓库的摘要/结果校验、原键重放、单一会话/编辑上下文消费已迁入，并由账户命令实际复用。仓库与业务写入共用调用方事务，异常整体回滚；原数据库永久唯一和工作区复合外键由真实双库测试验证。摘要按原 Python ASCII JSON 编码计算 1 MiB 上限，包括 DEL 转义；`scripts/export_receipt_write_goldens.py --check` 核对 1,052 个匿名校验及字节预算向量。通用 UoW 并发、历史 Alembic 升降级、现金提交与 retry 仍未验收。

`GET /api/v1/operations/projections` 只允许当前工作区管理员读取构建状态；没有状态行时返回原缺省，不创建状态或触发重建。失败构建保留已有活动数据集和错误字段，存储失败返回 `projection.unavailable`。`POST /api/v1/operations/projections/rebuild` 已接入实际重建：锁后及返回前重验管理员，拒绝时整事务回滚且不写失败诊断；领域校验返回 409，运行/存储错误保留原 503 合同。状态仓库同时用于收据结果的当前 ready 数据集/版本读取。Compose 当前没有管理员状态/重建入口，因此不提取额外公共 DTO。

`GET /api/v1/cash-ledger/options` 为当前工作区成员返回原 17 类流水、合法子类型和 3 类关系，保留标签及枚举顺序；空工作区也可读，不初始化数据集。三个选项 DTO 由现有 Compose `fetchLedgerOptions` 和后端共同消费，每次返回独立列表。`scripts/export_ledger_options.py --check` 核对 Python 完整选项，实际回包可用 `:shared:testAndroidHostTest --tests com.finance.tracker.KotlinLedgerOptionsResponseIntegrationTest` 验证。手工新建与当前关系维护已使用这些选项；完整维护接口已验收；原 Python 测试迁移台账已完成，生产入口仍为 Python。

分类删除按当前收支记录计使用量，保留目录/分类修订和确认检查；同一事务清除当前值、拆分恢复值、含已删除流水的分类引用和全部投影，保留其他人工字段并使依赖候选失效。活动投影和状态仅按原 ready 规则推进版本，异常整体回滚。DELETE 按原始字节处理 Python JSON 编码、语法和整数错误边界；`scripts/export_category_json_goldens.py --check` 校验匿名兼容向量。实际 Compose 确认协议通过 `KotlinCategoryDeletionResponseIntegrationTest` 消费 `FT_KOTLIN_CLIENT_CONTRACT_DIR` 下回包。万条记录耗时、集合 SQL 和 RSS 采样专项不代表原高水位内存测试已经迁移；准确状态见实施证据。

单项和批量分类 PUT 保留完整成员、当前数据集/版本与同值人工保护；公共分类写入稳定收支对象，原始流水及其人工覆盖保持独立。单成员对象同步独立恢复值，多成员保留各自恢复值；整批原子更新，已保护且展示相同的重复命令不推进版本。锁等待后重新核实角色、会话有效期与当前工作区，原客户端通过 `KotlinClassificationResponseIntegrationTest` 实际消费回包。性能保留原 12 条 API SQL / 500 毫秒门槛；后端源测试与保留定义以对应表为准（0 pending）。

内部余额快照仓库保留账户稳定 ID、当前名称和 crypto/security 映射，组成项增量以精度 80 计算并验证金额范围；草稿和删除记录不计余额，导入的普通转账排除，manual 来源保留。原生双库验证含负零、完整有效位数、账户改名及回滚；手工记账、转账和旧现金新增/校准已接入，导入仍待迁移。内部账户类型只接受真实账户模型提供的字符串。

手工新建的内部请求读取器已验证 65,536 字节、编码、重复键/非有限数、严格字段类型及 64 个现金部分限制；直接应用边界复制可变容器，保留缺省与明确清空的区别。POST `/api/v1/cash-records` 已使用该边界，原子保存流水、现金部分、余额及稳定收支/投影，并复用 Compose 现有创建消费者。缺省或明确清空备注/分类的人工保护与原 Python 一致；纯日期及无时区时间按 UTC 保存，正式入账的独立时间校验仍要求时区。公开 OpenAPI schema 已接通；原 Python 测试迁移台账已完成（0 pending）。

当前关系预览的原 18 个测试定义／62 个展开用例由 `CurrentCashRelationPlanOriginalTest` 直接核对完整关系摘要、当前候选证明、原决定、失效选择、联合组和父级草稿。原 55 个完整预览检查点继续作为差分，原定义迁移数单独统计；驳回刷新在独立可写夹具中由 Native 实际提交。

稳定收支的整笔公共编辑、解除继承、冲突公共值、重新导入保护、删除后归属和工作区隔离由 `CashEntryServiceOriginalTest` 直接核对原剩余服务断言。预览、实际提交、来源值和全量重建分别验证；旧候选删除后，旧维护计划在写入前拒绝。仅当拆开后人工公共字段实际不同，解除关联才要求明确继承。

按 `scripts/export_cash_entry_service_contracts.py --help` 列出的 24 个场景分别手工准备独立空测试库，使用 `--database-url`、共同的仓库外 `--output` 和 `--scenario` 生成初始输入。运行环境为 `PYTHONPATH=.:src:tests:scripts`；PostgreSQL 库名以 `_test` 结尾，SQLite 使用全新的绝对文件路径。设置 `FT_KOTLIN_ENTRY_SERVICE_CONTRACT_DIR` 后，在 `compose/` 执行 `./gradlew :backend:test --tests '*CashEntryServiceOriginalTest' -PbackendFunctionalOnly=true`。这些测试会消费写库，重跑必须重新准备或恢复初始快照。

公开计划 JSON 以实际收到的字节核对 64 MiB 预算，不依赖 Content-Length；解码、严格 JSON 与字段校验在 IO 调度器执行。导入／维护预览和确认、草稿保存及原键核实均使用同一入口，保留 Python BOM／UTF-8／UTF-16／UTF-32 及非有限数拒绝合同。确认先校验幂等键，正文只接受严格正整数修订；未提交状态只在服务器可证明时输出。运行 `:backend:test --tests "*CashPlan*OriginalTest" -PbackendFunctionalOnly=true`，真实 HTTP／Netty 用 `FT_KOTLIN_CONTRACT_DIR` 的匿名初始库；纯流／调度／编码测试无需数据库。Native 没有安装 URI 访问日志，实际 Netty 记录验证原键及查询不泄露；Uvicorn 过滤器定义保留 Python 执行。

来源决定输入的 `CashSourceIssueInputOriginalTest` 直接覆盖原 4 定义／22 参数：3 个共享记录意图、16 类非法结构、规范化重复事项和终结性合并。运行 `:backend:test --tests "*CashSourceIssueInputOriginalTest" -PbackendFunctionalOnly=true`，只调用实际输入解析器，无需数据库。维护合同的 23 个相关原文件为 243 migrated／3 retained、零 pending；ORM、历史 Alembic 与 Uvicorn 保留项分别有 Python 执行及 Native 兼容证据。完整范围与双库／增量证据见 tasks。

解析暂存的 [原断言回归](src/test/kotlin/com/finance/tracker/backend/ParsedImportStagingOriginalTest.kt) 验证实际内存与 R2 条件对象端口：文档页证明／行位置、原始说明／来源范围、私有生命周期、CAS／固定 TTL、取消清空、有界清扫、JSON／字节预算、快照原文件复用与公开缓存。运行 `:backend:test --tests "*ParsedImportStagingOriginalTest" -PbackendFunctionalOnly=true`，无需数据库或云凭据；条件对象假客户端保留真实状态／条件写及关闭流，不代表真实 R2 云验证。该原文件累计 51 定义／135 参数 migrated、4 整定义／30 参数 retained，全部 165 个原参数已有对应；四个保留项的宽类型 Python 输入无法通过 Kotlin 模型或方法签名构造，Native 分别验证可表示的无效值和损坏存储拒绝，后者不冒充原输入异常。既有双库完整证据与本片新增 37 项的 SHA／覆盖边界见 tasks。

[当前关系来源读取原断言](src/test/kotlin/com/finance/tracker/backend/CashRelationOriginOriginalTest.kt) 在真实 SQLite／PostgreSQL 新增草稿关系时检查只执行一次父流水 `SELECT`，来源校验复用同次已读完整父项；观察器记录 JDBC 执行，不将 `prepareStatement` 计作查询。清扫配置的原混合参数定义整体 retained：Kotlin 构造器为 `Int`，原 `True` 页长不可构造；既有清扫边界方法验证 0／61 秒和 201 页长的原错误，Python 全文件参考保留。两个原文件合计 30 migrated／1 retained、零 pending，准确命令、双库／类型证据与残余模块见 tasks。没有生产、schema、依赖或 UI 变化。

账户别名登记 `POST /api/v1/relations/aliases` 保留原按账户名称定位、卡尾／完整账号规范化及历史命名空间；成功只写别名，不自动确认关系。真实双库各 66 组原 HTTP／完整行状态、实际故障回滚和权限重验已验证，见 [合同测试](src/test/kotlin/com/finance/tracker/backend/AccountAliasContractTest.kt)；当前 Compose 无此独立旧接口的调用入口。

投资事件列表与账单原文两个 GET 已接入 [只读服务](src/main/kotlin/com/finance/tracker/backend/InvestmentLedgerReadService.kt)。列表保持原日期／时区、标的字面片段、1–50 条和版本游标；金额以 18 位小数输出，原文与资金调拨摘要沿用原安全字段。匿名输入与原 HTTP 使用 `scripts/export_investment_read_contracts.py --config <仓库外目标 URL 映射 JSON> --output <仓库外目录>` 生成；配置键见脚本 `GROUPS`，每个目标必须为新的绝对 SQLite 文件或人工准备的空 PostgreSQL `_test` 库。设置 `FT_KOTLIN_INVESTMENT_READ_CONTRACT_DIR=<输出目录>` 后运行 `*InvestmentLedgerReadContractTest`，随后 `:shared:testAndroidHostTest --tests '*KotlinInvestmentReadResponseIntegrationTest'` 消费本轮实际回包。生成器使用根 .venv 与 `PYTHONPATH=.:src:tests:scripts`，不启动数据库。投资导入已接入，完整验收见下文；原默认 Decimal 精度导致的部分大金额读取 500 仍保留。

资金调拨四操作使用 [应用服务](src/main/kotlin/com/finance/tracker/backend/FundingRelationService.kt)，保留 UTC 自然日窗口、候选优先级、人工决定和原关系证据，工作区锁后重查端点，确认与投影原子维护。当前双库完整回归通过。原输入使用 `scripts/export_funding_relation_inputs.py --databases <原定义目标 URL 映射 JSON> --output <仓库外目录>` 捕获；HTTP 对照使用 `scripts/export_funding_relation_contracts.py --databases <GROUPS 目标 URL 映射 JSON> --seed-url <前者普通歧义候选初始库 URL> --output <仓库外目录>`。所有目标须为新的绝对 SQLite 文件或人工准备的空 PostgreSQL `_test` 库；设置 `FT_KOTLIN_FUNDING_CONTRACT_DIR` 和 `FT_KOTLIN_FUNDING_HTTP_DIR` 分别指向两类输出，运行 `*FundingRelationContractTest` 与 `*FundingRelationHttpContractTest`。脚本不启动数据库，写测试须从未消费初始状态运行；原协议无幂等键，不自动重放决定。

账户余额、财务报表和持仓已接入统一估值。[纯领域](src/main/kotlin/com/finance/tracker/backend/ValuationDomain.kt) 和 [应用服务](src/main/kotlin/com/finance/tracker/backend/ValuationService.kt) 已实现原标的／数量校验、精确市值、时效、单项／批量及历史报价结果；原 10 定义／10 参数和 230 组实际 Python 输出／提供器调用／预算时钟对照及双库完整回归已通过。金额使用精确系数／指数和原 28／报表 80 位上下文，保留负零；缺价、不支持或历史不可用不填假零。行情提供器已接入账户余额／财务报表的默认服务；FX 中间价依赖已实现；持仓折算已接入；投资导入已验收，同步迁移继续进行，当前 86/86 个 HTTP 操作。

使用根 .venv 与 `PYTHONPATH=.:src:tests:scripts` 执行 `scripts/export_valuation_contracts.py --output <仓库外新 JSON 路径>` 生成真实 Python 对照，显式 `--character-output <ValuationCharacters.kt 路径>` 可重建 Python Unicode 分类表；输出包含源 SHA 和 Unicode 版本。设置 `FT_KOTLIN_VALUATION_CONTRACT=<该 JSON 路径>` 后运行 `*ValuationOriginalTest` 和 `*ValuationParityTest`。该工具和纯服务不执行网络、数据库或凭据操作，不计为真实行情或客户端接线验证。

### 行情提供器验证

[证券下载及预算](src/main/kotlin/com/finance/tracker/backend/SecurityQuoteProvider.kt)、[行情适配器](src/main/kotlin/com/finance/tracker/backend/MarketQuoteSources.kt) 和 [JVM HTTP](src/main/kotlin/com/finance/tracker/backend/QuoteHttp.kt) 保留原证券主源与备用顺序、来源时间、日期查询边界、加密币合并请求、预测市场搜索及并发预算。旧 `yfinance`／`yfinance-history` 值仅作为兼容来源标识，Kotlin 运行时不调用 Python。原 17 定义／17 参数由 `MarketQuoteOriginalTest` 直接对应，补充验证通过实际 JVM 本机 HTTP 与真实 Python SDK 离线解析完成，不代表已验证上游当前可用性或已经完成全部持仓能力。

在根目录使用 `.venv`、`PYTHONPATH=.:src:tests:scripts` 执行 `scripts/export_market_adapter_contracts.py --output <仓库外新 JSON 路径>` 和 `scripts/export_market_quote_contracts.py --output <另一新 JSON 路径> --cache <仓库外新匿名缓存目录>`。后者通过本机安装的原 `yfinance` 解析匿名响应，只替换其 HTTP I/O；输出绑定源、SDK 文件 SHA 和版本。分别设置 `FT_KOTLIN_MARKET_ADAPTER_CONTRACT` 与 `FT_KOTLIN_MARKET_SDK_CONTRACT` 后，在 `compose/` 运行 `./gradlew :backend:test --tests '*Market*Test' :backend:ktlintCheck :backend:detekt -PbackendFunctionalOnly=true`。五类共 26 个 Native 方法包含 189 组适配器和 27 组 SDK 对照，生成样本不另计原测试迁移数。

提供器默认使用服务端 `HTTPS_PROXY`，为空时使用 `HTTP_PROXY`，无非空大写覆盖时按协议读取 `https_proxy`／`http_proxy`，并遵守 `no_proxy`／`NO_PROXY`；可选 Finnhub 凭据只读取 `FT_FINNHUB_API_KEY`，没有密钥时跳过该来源。CoinGecko／Polymarket 沿用原观察时钟，现金沿用当前时钟；本片不改变这些旧语义，不修改数据库、生产入口或客户端页面。

### 账户余额与财务报表

[`FinanceQueryService`](src/main/kotlin/com/finance/tracker/backend/FinanceQueryService.kt) 接入 `GET /api/v1/queries/accounts` 和 `GET /api/v1/reports/finance`。账户按原创建时间／ID 和币种顺序返回快照余额，证券账户实际调用默认组合行情源；没有可用同币种行情时按原合同回退成本。普通合计使用 28 位精度，报表使用 80 位。生产报表始终读取当前正式投影，排除删除来源和非统计项目；未初始化返回 `projection.unavailable`，不回退原始流水。资金流按原全期转出成员归并取前十，不随月份过滤。

[`FinanceQueryApplication`](src/main/kotlin/com/finance/tracker/backend/FinanceQueryApplication.kt) 保留原纯应用的旧行级报表合同，只有显式不提供当前 reporter 时使用。当前两个 GET 没有 Compose 调用者，不新增重复 DTO 或页面；现有 Web／Native TODO 保留。原两文件 9 定义／34 参数均已对应，其中本片新增 7 定义／32 参数；67 组应用差分、每库 80 组原 Netty 回包及实际 JDBC／当前身份／财务发布交错验证为补充证据，不计入原参数数。

运行根 `.venv` 下的 `scripts/export_finance_query_contracts.py --output <仓库外新 JSON 路径>` 生成纯应用 Oracle；`scripts/export_finance_report_inputs.py` 接受 `--config` 的十四个显式空目标和 `--output` 的仓库外新目录，按原测试实际 setup 捕获首次读／重建边界；`scripts/export_finance_query_http.py --source-config <报表配置> --targets-config <四个显式空目标配置> --output <仓库外新目录>` 生成原 HTTP 合同。PostgreSQL 目标必须手工准备为 `_test`，复制初始行后对齐 sequence；工具不自动探测或启动服务。`initial-manifest.json` 的 SHA 绑定可写前状态，重复测试先恢复该状态，SQLite 有 WAL 时须用 online backup。

分别设置 `FT_KOTLIN_FINANCE_APPLICATION_CONTRACT`、`FT_KOTLIN_FINANCE_REPORT_CONTRACT_DIR` 和 `FT_KOTLIN_FINANCE_HTTP_DIR`，在 `compose/` 执行 `./gradlew :backend:test --tests '*FinanceQuery*Test' --tests '*FinanceReportOriginalTest' :backend:ktlintCheck :backend:detekt -PbackendFunctionalOnly=true`；真实 PG 另显式设置 `FT_TEST_POSTGRES_URL` 和适用测试用户。实际命令、全量与精确清理证据见 [当前任务](../../openspec/changes/archive/2026-11-04-kotlin-backend-shared-core/tasks.md#账户余额查询与财务报表)。

### FX 中间价

[`FxRates`](src/main/kotlin/com/finance/tracker/backend/FxRates.kt) 保留只读 Frankfurter 历史中间价、UTC 日期、同币种短路、进程缓存及相对汇率偏差；不修改正式金额。缓存包含缺失结果，注入 fetcher 时绕过缓存；默认请求失败才回查前五日，错误价格和响应结构保持缺失。默认 [`FxRateProvider`](src/main/kotlin/com/finance/tracker/backend/FxRates.kt) 共享进程实例。实际 JVM HTTP 使用原 `ft-fx/1.0`、JSON 请求头和 8 秒超时；本机对照不证明真实上游当前可用。

在根目录使用 `.venv` 和 `PYTHONPATH=.:src:tests:scripts` 执行 `scripts/export_fx_rate_contracts.py --output <仓库外新 JSON>`，设置 `FT_KOTLIN_FX_CONTRACT` 后在 `compose/` 执行 `./gradlew :backend:test --tests '*FxRate*Test' --tests '*MarketHttpTest' :backend:ktlintCheck :backend:detekt -PbackendFunctionalOnly=true`。原五定义有直接断言，107 组原输出／请求另经实际 JVM HTTP 比较，不重复计入原参数。该依赖已供持仓估值使用，完整验收证据见 [任务](../../openspec/changes/archive/2026-11-04-kotlin-backend-shared-core/tasks.md#fx-历史中间价)。

### 投资重放与周期计算

[`InvestmentProjection`](src/main/kotlin/com/finance/tracker/backend/InvestmentCore.kt) 保留事件／命令的 `security` 快照、基础币种面值、数量／成本、手续费及 CHECKIN 替换规则；历史不完整时允许负持仓。普通运算为 80 位，释放成本使用 40 位除法后 HALF_UP 量化 18 位，金额输入／写出沿用 NUMERIC(38,18)。纯函数失败可保留原局部内存变更，后续数据库调用者仍须在同一事务内整体回滚。

[`InvestmentCommandApplication`](src/main/kotlin/com/finance/tracker/backend/InvestmentCommandApplication.kt) 在仓库回调前校验精确金额并构造命令；[`周期计算与快照校验`](src/main/kotlin/com/finance/tracker/backend/InvestmentPerformance.kt) 保留原默认 28 位及调用方显式 80 位、时区／期间校验、资金流权重和非正资本空回报。快照有限性校验与持久化金额范围是不同边界，不把有限大数误拒绝为非有限。

在根目录使用 `.venv` 与 `PYTHONPATH=.:src:tests:scripts` 执行 `scripts/export_investment_core_inputs.py --output <仓库外新输入 JSON>`，再执行 `scripts/export_investment_core_contracts.py --original-inputs <前述文件> --output <仓库外新对照 JSON>`。设置 `FT_KOTLIN_INVESTMENT_CORE_INPUTS` 与 `FT_KOTLIN_INVESTMENT_CORE_CONTRACT` 后，在 `compose/` 执行 `./gradlew :backend:test --tests '*InvestmentValidationOriginalTest' --tests '*InvestmentCommandOriginalTest' --tests '*InvestmentReplayOriginalTest' --tests '*InvestmentPerformanceOriginalTest' --tests '*InvestmentCoreContractTest' :backend:ktlintCheck :backend:detekt -PbackendFunctionalOnly=true`。原 50 定义／68 参数直接对应；31 组原输入和 256 组差分分别保存，不把对照当作原断言。投资数据库命令与原 POST 已接通；投资导入已接入，验收边界见 [实施任务](../../openspec/changes/archive/2026-11-04-kotlin-backend-shared-core/tasks.md#投资重放与周期计算)。

### 投资事件写入

[投资写入服务](src/main/kotlin/com/finance/tracker/backend/InvestmentWriteService.kt) 接入原 `POST /api/v1/investment-events`，支持买入、卖出、兑换、存入、取出、分红及现金／持仓校准。账户资格、18 位金额量化、原错误顺序和完整成功结果保持；事件及持仓快照在同一工作区写事务保存，返回前重验权限和完整响应编码。Python 与 Kotlin 均在成功响应不能编码时整体回滚。未持久化的 `price` 仍执行原仓库量化校验。

使用根 `.venv` 与 `PYTHONPATH=.:src:tests:scripts` 执行 `scripts/export_investment_write_contracts.py --database-url <新目标 URL> --oracle-database-url <独立新 Oracle URL> --output <仓库外目录>`，生成 76 组原 HTTP 完整响应和 13 表全列状态。SQLite 使用新的绝对文件，PostgreSQL 使用人工准备的空 `_test` 库；生成器不启动数据库。将输出绑定 `FT_KOTLIN_INVESTMENT_WRITE_DIR`，将普通匿名库绑定 `FT_KOTLIN_CONTRACT_DIR`，在 `compose/` 执行 `./gradlew :backend:test --tests '*InvestmentWrite*Test' :backend:installDist :backend:ktlintCheck :backend:detekt -PbackendFunctionalOnly=true`。每次写入回归需恢复精确初始状态；PG sequence 也必须与原 Oracle 一致。原断言、权限／故障与完整回归证据见 [实施任务](../../openspec/changes/archive/2026-11-04-kotlin-backend-shared-core/tasks.md#投资命令持久化与公开写入)。

当前共享仓库没有此旧 POST 的调用者，Web／Native 两端入口 TODO 保留；投资导入与同步运行时均已迁移并验证。

### 本地持仓读取

[持仓查询](src/main/kotlin/com/finance/tracker/backend/PortfolioQueryService.kt) 与 [数据库读取器](src/main/kotlin/com/finance/tracker/backend/RelationalPortfolioRepository.kt) 提供组合估值所需本地快照、来源名称和事件投影。本地阶段返回精确数量／成本、原币种和名称，估值字段保留空值；不调用行情或历史。完整事件读取用于周期计算，本地阶段只选择当前非现金标的所需的五个来源字段。原组合 GET、刷新与 SSE 已接入这些读取服务。

根目录设置 `PYTHONPATH=.:src:tests:scripts`，使用 `.venv/bin/python scripts/export_portfolio_holdings_contracts.py --database-url <新目标库> --oracle-database-url <不同的新对照库> --output <仓库外新目录>` 生成真实原读取对照。PostgreSQL 须先明确准备两个空的 `_test` 库；生成器不启动数据库。设置 `FT_KOTLIN_HOLDINGS_CONTRACT_DIR` 指向基础匿名 `export_kotlin_contracts.py` 夹具目录、`FT_KOTLIN_HOLDINGS_ORACLE_DIR` 指向前述输出，两库分别在 `compose/` 执行 `./gradlew :backend:test --tests '*PortfolioHoldings*Test' --tests '*PortfolioRepositoryOriginalTest' :backend:ktlintCheck :backend:detekt -PbackendFunctionalOnly=true`；PostgreSQL 另设测试数据库用户。对照测试在每个场景前恢复其已记录快照，可复用未修改的账户／事件夹具；重新生成对照时须使用新目标库和新输出目录。验收状态见 [实施任务](../../openspec/changes/archive/2026-11-04-kotlin-backend-shared-core/tasks.md#本地持仓读取依赖)。

### 完整组合估值应用

[组合装配](src/main/kotlin/com/finance/tracker/backend/PortfolioValuation.kt) 和 [周期表现](src/main/kotlin/com/finance/tracker/backend/PortfolioPeriod.kt) 已实现当前行情、展示币种／USD 换算、当前汇总及周期盈亏。每次查询按资产类型和标的去重，当前、历史与 FX 共享原两秒预算；普通查询并行预取历史，进度回调先发送当前结果再读取历史。该服务已通过原功能断言、双库覆盖和独立复核，并供公开 GET、刷新和 SSE 调用。

在根目录设置 `PYTHONPATH=src`，使用 `.venv/bin/python scripts/export_portfolio_valuation_contracts.py --output <仓库外绝对 JSON 路径>` 生成真实 Python 完整结果与调用对照；绑定 `FT_KOTLIN_PORTFOLIO_VALUATION_CONTRACT`，数据库原断言复用前节 `FT_KOTLIN_HOLDINGS_CONTRACT_DIR`。两库分别在 `compose/` 执行 `./gradlew :backend:test --tests '*PortfolioValuation*Test' :backend:installDist :backend:ktlintCheck :backend:detekt -PbackendFunctionalOnly=true`。生命周期测试启动独立 JVM，验证忽略取消的上游工作不会阻止退出；对照、全量验证与原定义映射见 [实施任务](../../openspec/changes/archive/2026-11-04-kotlin-backend-shared-core/tasks.md#完整组合估值应用装配)。

组合周期使用 [冻结的时区规则](src/main/kotlin/com/finance/tracker/backend/PortfolioTimezone.kt)，保留原 Python 的历史与未来边界。根目录运行 `.venv/bin/python scripts/export_portfolio_timezone.py --check --oracle-output <仓库外绝对 JSON 路径>` 核对已提交资源并生成对照，绑定 `FT_KOTLIN_PORTFOLIO_TIMEZONE_CONTRACT` 后运行 `:backend:test --tests '*PortfolioTimezoneTest'`。更新资源时去掉 `--check`，重新审查并验证全部时区对照；生产运行不读取 Python 或宿主时区数据。

### 持仓后台刷新依赖

[刷新协调器](src/main/kotlin/com/finance/tracker/backend/PortfolioRefresh.kt) 和 [缺报价合并](src/main/kotlin/com/finance/tracker/backend/PortfolioRefreshMerge.kt) 已通过功能验收和独立复核，并接入公开读取和应用生命周期。保留原展示口径、手动优先、重放、心跳、错误退避及停止等待；原成功单价按当前数量重算，不保存财务事实。

根目录使用 `PYTHONPATH=src .venv/bin/python scripts/export_portfolio_refresh_contracts.py --output <仓库外绝对 JSON 路径>` 生成原完整合并结果，绑定 `FT_KOTLIN_PORTFOLIO_REFRESH_CONTRACT` 后在 `compose/` 运行 `./gradlew :backend:test --tests '*PortfolioRefresh*Test' :backend:installDist :backend:ktlintCheck :backend:detekt -PbackendFunctionalOnly=true`。原定义、交错及关闭验证见 [实施任务](../../openspec/changes/archive/2026-11-04-kotlin-backend-shared-core/tasks.md#持仓刷新协调器)。


### 持仓公开接口验证

本机迁移预览已装配持仓 GET、SSE 和刷新 POST，通过 SQLite／PostgreSQL 16 的原断言、完整功能覆盖、真实 Compose 消费和独立复核。原路径与参数不变，`phase=holdings` 仅返回本地持仓；刷新返回 `202 {"accepted":true}`，实时流提供版本重放、心跳及失败事件。后台按工作区绑定服务，行情请求期间不持有数据库事务；请求返回和流发送前重查当前身份。应用停止关闭订阅并等待已开始的刷新。

仓库根目录执行 `PYTHONPATH=.:src:scripts .venv/bin/python scripts/export_portfolio_http_contracts.py --database-url <新目标测试库 URL> --oracle-database-url <独立原实现测试库 URL> --output <仓库外绝对目录>`。SQLite 目标必须不存在，PostgreSQL 目标须人工准备为空且库名以 `_test` 结尾。设置 `FT_KOTLIN_PORTFOLIO_CONTRACT_DIR` 为输出目录、`FT_KOTLIN_PORTFOLIO_HTTP_DIR` 为前节通用匿名持仓夹具，再在 `compose/` 运行 `./gradlew :backend:test --tests '*PortfolioHttp*Test' --tests '*OpenApiContractTest' -PbackendFunctionalOnly=true`。实际消费者另用 `:shared:testAndroidHostTest --rerun --tests '*KotlinPortfolio*IntegrationTest'` 执行，须设置 `FT_KOTLIN_PORTFOLIO_HOST_FILE` 指向正在运行的本机测试宿主地址文件；关闭构建与配置缓存，防止环境切换复用旧报告。测试宿主 `PortfolioHttpConsumerHost` 只用于匿名验证。对照与实际客户端验证见 [实施任务](../../openspec/changes/archive/2026-11-04-kotlin-backend-shared-core/tasks.md#持仓公开读取与实时流)。

共享刷新调用使用专属 `PortfolioRefreshAcceptedDto` 解码原 `accepted` 字段，Repository 仍返回 Unit。Web 与 Native 共用这个协议修复；生产页面和展示结构不变。

### IBKR 解析与事件映射

[IBKR 内部读取器](src/main/kotlin/com/finance/tracker/backend/IbkrImporter.kt) 已实现 Activity CSV 的总结、业务行、来源身份及投资事件映射，已通过原断言、完整对照和独立复核，已接入下述投资导入 HTTP。UTF-8、CSV 引号与换行、原字段长度上限和来源原始文本单元保持原行为；股票总额与佣金各计一次，FX 只记基础币种净额，期末现金校准单独保留来源行。

根目录使用 `PYTHONPATH=src .venv/bin/python scripts/export_ibkr_contracts.py --output <仓库外绝对 JSON 路径>` 生成真实 Python 完整输出。设置 `FT_KOTLIN_IBKR_CONTRACT` 指向该文件、`FT_KOTLIN_IBKR_FIXTURE` 指向仓库匿名 `tests/fixtures/ibkr/transactions_1y_sample.csv`，在 `compose/` 运行 `./gradlew :backend:test --tests '*Ibkr*Test' :backend:installDist :backend:ktlintCheck :backend:detekt -PbackendFunctionalOnly=true`。原七定义和逐行完整重放证据见 [实施任务](../../openspec/changes/archive/2026-11-04-kotlin-backend-shared-core/tasks.md#ibkr-解析与映射)。

### Schwab 解析与事件映射

[Schwab 内部读取器](src/main/kotlin/com/finance/tracker/backend/SchwabImporter.kt) 已实现 Transaction History CSV 解析、来源身份、费用／税务／退款映射和离线重放，已通过原断言、完整差分及独立复核，已接入下述投资导入 HTTP。保留原始列名和值，按日期和参照号排序；负 JRN 只有匹配同日期的股息来源说明时才标记预扣税。IBKR 与 Schwab 共用内部 CSV／Decimal 工具，两者完整原断言和差分须一并回归。

根目录执行 `PYTHONPATH=src:scripts .venv/bin/python scripts/export_schwab_contracts.py --output <仓库外绝对 JSON 路径>`；设置 `FT_KOTLIN_SCHWAB_CONTRACT` 与 `FT_KOTLIN_SCHWAB_FIXTURE`，后者指向匿名 `tests/fixtures/schwab/transaction_history_sample.csv`。在 `compose/` 运行 `./gradlew :backend:test --tests '*Schwab*Test' --tests '*Ibkr*Test' :backend:installDist :backend:ktlintCheck :backend:detekt -PbackendFunctionalOnly=true`，IBKR 环境按前节设置。当前证据见 [实施任务](../../openspec/changes/archive/2026-11-04-kotlin-backend-shared-core/tasks.md#schwab-解析与映射)。

### DFZQ 文本解析与事件映射

[DFZQ 内部读取器](src/main/kotlin/com/finance/tracker/backend/DfzqImporter.kt) 已完成已提取文本的流水／汇总解析、来源标识和投资事件映射内部验收，原断言、完整差分与独立复核已通过。保留原始文本单元、现金和证券汇总快照；费用按原净额合同拆分，不能重复扣除。PDF 解密／提取已由下述 PDFBox 独立进程提供；投资文件分派、导入事务和 HTTP 已接入，当前验收状态见下文。

根目录执行 `PYTHONPATH=src:scripts .venv/bin/python scripts/export_dfzq_contracts.py --output <仓库外绝对 JSON 路径>`，设置 `FT_KOTLIN_DFZQ_CONTRACT` 指向结果；在 `compose/` 执行 `./gradlew :backend:test --tests '*Dfzq*Test' :backend:installDist :backend:ktlintCheck :backend:detekt -PbackendFunctionalOnly=true`。原测试完整调用、费用关系、精度及逐步重放证据见 [实施任务](../../openspec/changes/archive/2026-11-04-kotlin-backend-shared-core/tasks.md#dfzq-文本解析与映射)。

### uSmart HK 文本解析与事件映射

[uSmart HK 内部读取器](src/main/kotlin/com/finance/tracker/backend/UsmartImporter.kt) 已实现保证金／日内融文本解析、来源身份、现金与投资事件映射和离线重放，已完成内部依赖验收。保留原文快照、成交汇总与费用、换汇配对、期末现金与已清仓持仓；显示备注不影响现金去重身份。PDF 解密／提取已由下述 PDFBox 独立进程提供；投资文件分派、导入事务和 HTTP 已接入，当前验收状态见下文。

根目录执行 `PYTHONPATH=src:scripts .venv/bin/python scripts/export_usmart_contracts.py --output <仓库外绝对 JSON 路径>`，设置 `FT_KOTLIN_USMART_CONTRACT` 指向结果；在 `compose/` 执行 `./gradlew :backend:test --tests '*Usmart*Test' :backend:installDist :backend:ktlintCheck :backend:detekt -PbackendFunctionalOnly=true`。原调用、版式／金额边界及逐步重放证据见 [实施任务](../../openspec/changes/archive/2026-11-04-kotlin-backend-shared-core/tasks.md#usmart-hk-文本解析与映射)。


### PDFBox 独立进程解析

PDF 解码使用现有 PDFBox 依赖，每次新建 JVM；工行 PDF 的格式分派和内部直接读取已接入，投资文字提取端口已实现并通过独立复核。投资文件分派和导入 HTTP 已接入，匿名普通／加密 PDF 已有完整来源及实际入库验证，其他投资版式须按具体来源验证。CSV 和 Excel 读取方式不变。

服务最多同时执行两个 PDF 任务，满额在启动前失败，不建立等待队列；每个任务默认总期限 60 秒。工作进程设置堆上限 256 MiB、元空间 128 MiB、直接内存 32 MiB，并保留输入、页数、字符数和输出预算；这些 JVM 参数不等于总 RSS 或 CPU 硬限制。成功返回必须同时收到完整结果并确认进程正常退出。超时、取消、崩溃和超限都会终止进程并释放名额，不返回部分文档。

PDF 和密码通过标准输入管道传入，不经参数、环境、密码文件或解密后的临时 PDF；子进程清空继承环境、使用私有临时工作目录，原始日志丢弃，禁用 JVM 堆转储和原生崩溃转储，宿主只接收受控错误。部署系统的独立转储收集策略仍须由运维控制。macOS 可能自动添加系统文本编码环境变量。用户确认本轮暂缓操作系统沙箱：子进程仍具有宿主用户的文件和网络权限，不属于权限隔离环境。

构建和运行只需既有 JDK／PDFBox，不要求 qpdf、mutool、C 编译器或容器。`installDist` 包内已有工作进程入口，普通运行复用当前 Java 运行时和分发 classpath；Gradle 测试显式使用测试 runtime classpath。执行 `./gradlew :backend:test --tests '*PdfProcessTest' --tests '*IcbcPdf*Test' --tests '*CashStatementDispatchTest' :backend:installDist :backend:detekt -PbackendFunctionalOnly=true`，Gradle 自动设置公开合成环境标记，仅用于验证未继承环境。证据和开销观测见 [实施任务](../../openspec/changes/archive/2026-11-04-kotlin-backend-shared-core/tasks.md#pdfbox-独立进程解析)。

投资 PDF 对照由根目录 `PYTHONPATH=src:scripts .venv/bin/python scripts/export_investment_pdf_contracts.py --directory <仓库外新绝对目录>` 生成；原 Python 对照生成需要开发环境的 mutool，生产无需该工具。设置 `FT_KOTLIN_PDF_TOOL_CONTRACT=<目录>/contract.json`，在 `compose/` 执行 `./gradlew :backend:test --tests '*InvestmentPdfContractTest'`，核对实际普通／加密 PDF 的完整解析行、来源身份与事件。匿名版式对照不代表真实券商全部版本验收。


### 投资文件读取

[内部文件分派](src/main/kotlin/com/finance/tracker/backend/InvestmentDocument.kt) 已实现 DFZQ／uSmart 文本与 PDF、IBKR／Schwab CSV 读取，保留原行、来源正文、元数据及精确金额类型。PDF 使用上述独立 JVM；文本的 UTF-8 替换与换行按原 Python 行为处理，CSV 仍严格校验编码。文件分派已通过独立复核，导入事务和原 POST `/api/v1/investment-import` 已接入，完整双库功能覆盖与独立复核已通过，两端入口保持 TODO。

在根目录执行 `PYTHONPATH=src:scripts .venv/bin/python scripts/export_investment_document_contracts.py --output <仓库外绝对 JSON 路径>`，设置 `FT_KOTLIN_INVESTMENT_DOCUMENT_CONTRACT` 指向结果，并按上节设置 `FT_KOTLIN_PDF_TOOL_CONTRACT`；在 `compose/` 运行 `./gradlew :backend:test --tests '*InvestmentDocumentTest' :backend:installDist :backend:detekt -PbackendFunctionalOnly=true --no-configuration-cache`。证据见 [实施任务](../../openspec/changes/archive/2026-11-04-kotlin-backend-shared-core/tasks.md#投资文件分派与原子导入)。

[投资导入服务](src/main/kotlin/com/finance/tracker/backend/InvestmentImportService.kt) 在解析前检查账户和访问权限，在写锁内重新验证。投资事件、账单原文和快照同事务保存；按工作区、来源类型及记录编号去重，任何写入或校验失败均整批回滚。原 HTTP 接受严格 base64，解码后最多 25 MiB，密码只取 `x-ft-statement-password`；文件名仅决定格式，不作为宿主路径。

用 `scripts/export_investment_import_contracts.py` 或 `scripts/export_investment_import_http_contracts.py` 的 `--database-url`、`--oracle-database-url` 和 `--output` 参数准备两个不同的新测试库及仓库外目录。分别设置 `FT_KOTLIN_INVESTMENT_IMPORT_DIR`／`FT_KOTLIN_INVESTMENT_IMPORT_HTTP_DIR` 后运行 `*InvestmentImportContractTest`／`*InvestmentImportHttpContractTest`。写入用例会消费目标库，重跑须重新准备。直接原断言 `*InvestmentImportTest` 另需 `FT_KOTLIN_CONTRACT_DIR`、`FT_KOTLIN_INVESTMENT_FIXTURES`（`tests/fixtures` 的绝对路径）和上述 `FT_KOTLIN_PDF_TOOL_CONTRACT`；同一组在 SQLite 和显式专用 PostgreSQL 执行，完整证据见 [实施任务](../../openspec/changes/archive/2026-11-04-kotlin-backend-shared-core/tasks.md#投资文件分派与原子导入)。

内部同步服务已实现事件、持仓快照和游标的整次原子提交；网络返回后重新核对当前工作区、权限及账户，合法空扫描可更新游标。原同步及游标 21 个测试定义已迁移，并通过 SQLite／PostgreSQL 功能验证。凭据读取、真实连接器和同步 HTTP 已接入，双库功能验证与独立复核已通过，当前仍为 86/86 个公开操作。

连接器凭据读取保留服务端 `FT_CREDENTIALS_DIR/credentials.yaml`（未设置时为 `~/.ft/credentials.yaml`）；使用 SnakeYAML 2.7 安全构造，保留原字段与钱包校验，并尽力设置文件 `0600` 和目录忽略规则。错误不包含 YAML 正文或异常 cause。该模块已接入同步 HTTP，不代表租户独立凭据配置。


### Polymarket 连接器

[Polymarket 读取器](src/main/kotlin/com/finance/tracker/backend/PolymarketConnector.kt) 已实现 Activity 分页、BUY／SELL／REDEEM／YIELD 映射，以及同一区块的当前 pUSD 余额快照。活动原文和编号保留；增量游标只过滤活动，余额快照每次读取，余额失败不返回活动子集。快照不扫描转账日志。内部同步已验证两库原子保存与重复导入，公开同步 HTTP 已接入，独立复核已通过。

HTTP 使用后端 Apache HttpClient 5.6.4、原 User-Agent 和 30 秒超时；按协议读取代理变量，支持 HTTPS 代理认证及 `no_proxy`，凭据只发给代理。客户端不隐式重试，连接器按原三次策略处理传输故障，内部错误立即终止。生产默认读取活动和余额；测试只注入活动的边界保持可独立验证。

在根目录执行 `PYTHONPATH=src:scripts .venv/bin/python scripts/export_polymarket_contracts.py --output <仓库外绝对 JSON 路径>`，设置 `FT_KOTLIN_POLYMARKET_CONTRACT` 指向输出，并按前述方法准备显式测试库和 `FT_KOTLIN_CONTRACT_DIR`；在 `compose/` 执行 `./gradlew :backend:test --tests '*Polymarket*Test' -PbackendFunctionalOnly=true --no-configuration-cache`。测试仅访问本机 HTTP／RPC，不使用真实钱包或发送交易。


冻结详情只恢复当前成员的计划引用，返回公开字段的独立副本；恢复冻结引用时保留缺省成员／组成项字段，公开详情将缺省成员列表呈现为空列表，明确 `null` 返回 `plan_build_failed`。发布前的校验仅复制会修改的容器，快速校验与完整路径保持相同错误，编号转换保留原 4,300 位预算和 Unicode 数字规则。直接边界见 `CashImportPlanValuesTest` 与 `CashImportReadableOriginalTest`；后者按原 98 个可构造参数及 Python 实际结果核对。原 String 子类比较协议因 Kotlin String 不可继承而保留 Python 参考。运行 `PYTHONPATH=.:src:tests:scripts .venv/bin/python scripts/export_cash_import_readable_original.py --output <JSON路径>` 可复现对照数据。

来源位置、页声明、关系选择与行情边界的原始断言集中在 `RemainingSourceBoundaryTest`：同原文件位置证明与 provider 身份分别核对，页证据只在相同声明和命名空间内合并；明确关系决定的豁免只适用于所选组，不扩散到相邻未选择组。该回归不连接真实数据源或数据库。


单文件来源匹配的原 44 个功能定义由 `CashSourceMatchingOriginalTest` 直接验证，保留 49 个原参数组合。重复出现的次数、未知账户归属、可信编号、人工保护、固定字段冲突和候选消耗分别核对；原性能定义不在本轮验收范围。


资金账户解析的值与结构边界由 `CashImportFundingOriginalTest` 保留原 35 定义／68 参数：缺少对应账户、已知及未知分摊、钱包回退、当前账户资格、来源身份／脱敏和原文不变分别验证。原内部异常注入由 `CashFundingFailureOriginalTest` 在实际支付宝 parser、parts 和 total 依赖点验证。生产 `CashImportFunding` 使用默认引擎，测试故障只影响自己的实例，内部异常继续原样终止操作。


微信来源范围由 `CashSourceScopeOriginalTest` 直接验证：昵称、原账号与内部账户选择不改变默认范围；用户命名范围隔离同文件位置与来源编号。原非法 label 定义含 Kotlin 字符串签名不能接收的数字和列表，整定义保留 Python 参考；原生验证五个非法字符串，协议另拒绝数字／列表，不把两种错误合同混同。


名称归一与工行收支类型的原断言位于 `ConvertNormalizeOriginalTest`：O2O 前缀、品牌剩余描述、支付前缀和原文保留分别验证，跨境汇款与购汇保持转账／换汇的类型区别。前缀清理直接使用生产 helper，不通过包含其他清理步骤的入口替代。

连接器迁移保留原限制：Kraken 成交在 API 返回 50 条时可能提前结束，漏掉后续页；OKX 数字资金类型会被拒绝，账单默认历史窗口及页大小也有约束；Binance 默认现货成交未指定交易对，资金账单调用不支持现货。用户已决定迁移后另修。本次离线等价验证不代表完整同步或现网成功，公开同步操作已接入，独立复核已通过。

内部 `CcxtExchangeConnector` 已实现原成交／资金事件映射、费用子事件、分页去重、游标和外部失败重试；`CcxtConnectorOriginalTest` 直接覆盖原 19 定义／26 参数。金额沿原 28 位 Decimal 规则，时间沿原毫秒到微秒舍入，完整来源正文保留；未知内部异常立即停止。根目录运行 `PYTHONPATH=.:src:tests:scripts .venv/bin/python scripts/export_ccxt_connector_contracts.py --output <JSON路径>` 可复现完整 Python 结果、请求及重试对照。同版官方 Java SDK `4.5.90` 适配器已实现，凭据工厂与公开同步已装配，独立复核已通过。每次抓取独立拥有 REST 客户端，关闭和中断会取消实际 HTTP 请求并有界等待；不关闭 SDK 共享执行器。Netty 统一为 `4.2.17.Final`、BouncyCastle 为 `1.86`。

导入逐行结果保留预览中已知的跳过原因，实际保存身份供成功项及批内重复项共同引用；空成员的已知关系跳过直接返回冻结原因，不执行 SQL。原断言由 `CashImportOutcomeOriginalTest` 验证。现金命令与解析会话导入保持独立，四类会话错误保留原基类和错误码；Python 旧命令类及模块退役的结构测试继续作为原运行时参考执行，不计入 Kotlin 原定义迁移。


SDK 对照使用合成凭据和本机 HTTP 服务，覆盖三家签名、完整来源正文、冷市场加载、原限制、错误及取消，不读取真实账户。根目录运行 `PYTHONPATH=.:src:tests:scripts .venv/bin/python scripts/export_ccxt_sdk_contracts.py --output <仓库外 JSON 路径>`，将输出路径设为 `FT_KOTLIN_CCXT_SDK_CONTRACT` 后运行 `:backend:test --tests '*CcxtSdkLoopbackTest'`。生命周期 HTTPS 检查另需 `FT_KOTLIN_CCXT_TLS_KEYSTORE` 指向合成 PKCS12 测试证书，别名 `loopback`、密码 `synthetic-only`，SAN 包含 `127.0.0.1`；证书留在仓库外。SDK 输入的超 Long 游标会精确拒绝，合法日期范围全部可表示；响应体仍沿 SDK 原读取行为，未增加体积预算。Java 与 Python 的查询参数顺序和 HTTP 原因短语可能不同，完整业务结果、实际签名和失败类别由测试核对。


管理员调用 `POST /api/v1/operations/sync`，提交 `source`（`binance`、`kraken`、`okx`、`polymarket`）、`account`，可选 `full` 和正整数 `batch_size`（默认 500）。原 `provider` 字段在 `source` 为空时兼容。网络抓取和 SDK 关闭在财务事务前完成；事件、持仓和游标整次提交。配置错误返回 `400 sync_configuration_invalid`，不包含服务器路径或配置正文；连接器失败返回固定安全提示。服务端请求取消会中断 SDK 等待，客户端断连是否取消服务端任务由 HTTP 引擎决定。

在根目录运行 `PYTHONPATH=.:src:tests:scripts .venv/bin/python scripts/export_sync_http_inputs.py --output <仓库外 JSON 路径>` 生成原 HTTP 参数对照，设置 `FT_KOTLIN_SYNC_HTTP_INPUTS` 后执行 `SyncHttpTest`。原交易所集成输入由 `scripts/export_sync_exchange_inputs.py` 从原测试 AST 与匿名成交夹具提取；未修改既有原断言。同步完整对照另用 `export_sync_contracts.py` 生成专用双库数据，设置 `FT_KOTLIN_SYNC_CONTRACT_DIR`；数据库须预先准备且 PostgreSQL 名称以 `_test` 结尾，测试不自行探测或启动数据库。


资金解析与逐流水操作规划使用调用内的 [正式财务校验缓存](src/main/kotlin/com/finance/tracker/backend/PostingValidationCache.kt)：仅复用相同财务字段的成功校验，每次准备独立创建，最多保存 256 项；失败、未知类型／附加现金部分字段及超过 128 个现金部分仍执行完整校验。该缓存不复用权限、账户资格或跨请求结果。原六定义／20 参数中，两定义直接对应 Native；涉及 Python Decimal 非有限值、字符串子类、复制协议和可变算术上下文的四定义保留完整 Python 参考，并补原生兼容验证。实际调用点和双库证据见 [实施任务](../../openspec/changes/archive/2026-11-04-kotlin-backend-shared-core/tasks.md#调用内正式财务校验缓存)。


[账户默认建议原断言](src/test/kotlin/com/finance/tracker/backend/CashMappingSuggestionOriginalTest.kt) 覆盖精确别名、本人来源／文件范围、当前账户资格和旧映射提示四个原定义／28 个双库参数。建议读取不写入，范围场景先经过真实预览和确认；币种不足仍可给出建议，实际导入另行校验资格。根目录运行 `PYTHONPATH=.:src:tests:scripts .venv/bin/python scripts/export_cash_mapping_suggestion_inputs.py --help` 查看原参数场景，每场景使用新匿名 SQLite 或显式专用 PostgreSQL `_test` 库；设置 `FT_KOTLIN_MAPPING_SUGGESTION_DIR` 后运行 `*CashMappingSuggestionOriginalTest`。[账户默认映射发布原断言](src/test/kotlin/com/finance/tracker/backend/CashMappingPublicationOriginalTest.kt) 补齐其余九定义／30 个双库参数，覆盖最终确认、幂等重放、草稿资格、冻结选择和行／关系／映射／收据失败回滚。使用 `scripts/export_cash_mapping_publication_inputs.py --help` 准备每参数新库，设置 `FT_KOTLIN_MAPPING_PUBLICATION_DIR` 后运行 `*CashMappingPublicationOriginalTest`；确认后的建议同样验证无写入。

[导入账户当前资格重验](src/test/kotlin/com/finance/tracker/backend/CashAccountRevalidationOriginalTest.kt) 覆盖原三个定义／56 个双库参数。改名、修订变化和无关币种调整不阻止有效导入；停用、删除或移除所需币种导致整次确认拒绝，人工现金部分同样复核。根目录运行 `PYTHONPATH=.:src:tests:scripts .venv/bin/python scripts/export_cash_account_revalidation_inputs.py --help`，每参数使用全新匿名库；设置 `FT_KOTLIN_ACCOUNT_REVALIDATION_DIR` 后运行 `*CashAccountRevalidationOriginalTest`。原草稿转正式保留流水 ID，拒绝后按原幂等键查询无收据。

[文件变化后的关联选择恢复](src/test/kotlin/com/finance/tracker/backend/CashChoiceRecoveryOriginalTest.kt) 覆盖原四定义／28 个双库参数。文件增删、替换和来源范围变化保留未受影响的选择及流水 ID，有效期不续期，旧计划拒绝读写；重新预览失败保留原暂存内容，取消清除私有证明。根目录运行 `PYTHONPATH=.:src:tests:scripts .venv/bin/python scripts/export_cash_choice_recovery_inputs.py --help` 准备每参数新匿名库，设置 `FT_KOTLIN_CHOICE_RECOVERY_DIR` 后运行 `*CashChoiceRecoveryOriginalTest`。

[共享候选原合同](src/test/kotlin/com/finance/tracker/backend/CashSharedCandidatesOriginalTest.kt) 覆盖两原定义／六个双库参数。预览后候选变化时保存当前候选修订；草稿写入后的内部失败回滚新增记录、候选链接及同事务的候选修改。根目录运行 `PYTHONPATH=.:src:tests:scripts .venv/bin/python scripts/export_cash_shared_candidate_inputs.py --help` 准备每参数新匿名库，设置 `FT_KOTLIN_SHARED_CANDIDATE_DIR` 后运行 `*CashSharedCandidatesOriginalTest`。

[导入整笔收支公共编辑](src/test/kotlin/com/finance/tracker/backend/CashEntryChoicesOriginalTest.kt) 覆盖十二原定义／28 个双库参数，含两个实际 HTTP 合同。公共备注保存到收支条目与投影，账单原文保持来源值；旧编辑引用、不可用分类和依赖失败按原合同拒绝。根目录运行 `PYTHONPATH=.:src:tests:scripts .venv/bin/python scripts/export_cash_entry_choice_inputs.py --help` 准备原输入及初始种子，设置 `FT_KOTLIN_ENTRY_CHOICE_DIR` 后运行 `*CashEntryChoicesOriginalTest`。

[Multipart 原始流回归](src/test/kotlin/com/finance/tracker/backend/CashMultipartOriginalTest.kt) 保留原八定义／12 参数及上传 CORS 一定义：实际请求字节预算、截断拒绝、取消／断连传播、头／文件数／文本上限与文件名脱敏。`CashMultipartReader` 直接使用 Ktor 通道，逐行读取头并有界保存正文；请求结束时关闭收集器及通道、等待转发协程退出。单文件保留 100 MiB+1 字节供应用层识别超限；总请求上限覆盖全部字节。前置换行、声明字符集／Latin-1 回退、重复 Content-Disposition 取最后一项及当前原依赖的单行 4224 字节／八个头限制保持。运行 `:backend:test --tests '*CashMultipartOriginalTest' -PbackendFunctionalOnly=true` 无需数据库；受影响的真实会话合同仍使用全新两库夹具验证。

[转换层平台与付款前缀原断言](src/test/kotlin/com/finance/tracker/backend/ConvertHelperOriginalTest.kt) 覆盖 `test_convert.py` 的平台／前缀 72 定义／87 次比较及工行付款渠道／品牌优先级 9 定义／11 次比较。在仓库根目录运行 `.venv/bin/python scripts/export_convert_helper_assertions.py --output compose/backend/src/test/resources/original-convert-helper-assertions.json --check` 只核对原 AST 字面量及相等／不等运算，不运行被测实现来生成预期；平台 `source` 在原函数未使用，输入仍保留。真实名称归一共用内部平台识别和前缀函数；付款来源的四个固定标签定义及其他完整读取／退款配对仍待逐项验证和处置，不以 `payment_method` 代替旧 `source`。


[CORS 原合同](src/test/kotlin/com/finance/tracker/backend/CorsOriginalTest.kt) 直接验证本地动态端口、恶意主机后缀及无认证的幂等提交预检。使用显式匿名数据库与有效测试令牌访问真实账户列表；允许跨域不代替业务授权，预检不创建计划或写入。


[工行借记卡单行原断言](src/test/kotlin/com/finance/tracker/backend/IcbcDebitOriginalTest.kt) 使用原 13 列表格行，在完整归一调用中观察实际中间记录，覆盖 11 定义／45 断言。原金额、币种、摘要清洗、退款／撤销标记与最终保留的账单原文分别核对；观察函数仅在测试调用启用。仓库根目录运行 `.venv/bin/python scripts/export_icbc_debit_original_assertions.py --output compose/backend/src/test/resources/original-icbc-debit-assertions.json --check` 核对原 AST 字面量和布尔表达式。


[支付宝／微信纯事实原断言](src/test/kotlin/com/finance/tracker/backend/PlatformFactOriginalTest.kt) 覆盖 23 定义／74 断言。原 CSV／XLSX 工厂生成 25 行输入，真实解码后一次归一观察内部事实，并核对最终原文与跳过状态。仓库根目录运行 `.venv/bin/python scripts/export_platform_original_assertions.py --output compose/backend/src/test/resources/original-platform-fact-assertions.json --check` 核对原文本资源；追加 `--fixtures` 和仓库外全新绝对目录生成测试文件，并将该目录配置为 `FT_KOTLIN_PLATFORM_FACT_ORIGINAL_DIR`。旧退款追踪包装不在本片验收内。


[DfzqOriginalTest](src/test/kotlin/com/finance/tracker/backend/DfzqOriginalTest.kt) 还覆盖旧通用导入器中的 19 位小数拒绝原规则：当前显式账户映射收到原 DEPOSIT 八字段后，在结果产生前拒绝并保留原错误提示；旧 YAML 账户路由和通用输出包装不因此恢复或计为完成。


`CashLegacySourceReplacementTest` 核对四个旧固定来源标签及一个旧跳过统计定义的当前替代。原五定义／七断言整项保留 Python 参考；固定标签输入补充为明确的合成文档，当前来源类型、文件账户、付款账户和完整原文分别验证。未支付关闭沿原真实 CSV 工厂核对一条原始行及零候选；不恢复旧 `_acceptance` 包装。运行需 `FT_KOTLIN_LEGACY_SOURCE_DIR` 指向仓库外夹具目录，可用 `.venv/bin/python scripts/export_legacy_source_replacements.py --output compose/backend/src/test/resources/legacy-source-replacements.json --check --fixtures /绝对路径/新目录` 准备。


`CashLegacyProviderReplacementTest` 验证支付宝／微信两个旧记录编号定义的当前替代：原十列真实文件保留交易编号和商户编号，文件范围与账户范围分别控制重复匹配；显式变体验证跨文件、备注更新、金额冲突及跨范围拒绝。原定义整项保留，不恢复旧 `_fact_id` 格式。运行设置 `FT_KOTLIN_LEGACY_PROVIDER_DIR`；以 `.venv/bin/python scripts/export_legacy_provider_replacements.py --output compose/backend/src/test/resources/legacy-provider-replacements.json --check --fixtures /绝对路径/新目录` 生成仓库外原文件及明确标记的变体。


`IcbcLegacyIdentityReplacementTest` 保留工行两个旧内容哈希定义的完整 Python 参考，以原十三列行及显式 partial payload 适配验证当前来源身份。不同物理位置保留，同定位重复／原文变化和批内矛盾分别判定；文件卡号不被业务行账号覆盖。该测试使用已解码文档，PDF 字节和实际写入验证另列。资源以 `.venv/bin/python scripts/export_icbc_legacy_identity.py --output compose/backend/src/test/resources/icbc-legacy-identity-replacements.json --check` 核对。


`IcbcLegacyPdfBoundaryReplacementTest` 通过默认 PDFBox 独立进程读取五份匿名 PDF，验证旧空行／短行异常的当前替代：空行跳过、缺项候选与有效行并存、覆盖证明失效、资金准备与正式记录校验，以及两列表头拒绝。两个旧异常定义整体保留 Python 参考；缺失的原真实 PDF 占位测试不算通过。设置 `FT_KOTLIN_LEGACY_PDF_BOUNDARY_DIR`，以 `.venv/bin/python scripts/export_icbc_legacy_pdf_boundaries.py --output compose/backend/src/test/resources/icbc-legacy-pdf-boundaries.json --check --fixtures /绝对路径/新目录` 准备真实夹具。


`IcbcCreditCardOriginalTest` 迁移五个信用卡原卡号定义，通过五份匿名 PDF 保留全部 15 个原断言；完整卡号、掩码卡号和同文档下一行缺卡号分别验证。原中间来源身份与当前文件内分组分开核对，选择对应账户后验证完整资金准备结果。设置 `FT_KOTLIN_ICBC_CARD_ORIGINAL_DIR`；以 `.venv/bin/python scripts/export_icbc_credit_card_originals.py --output compose/backend/src/test/resources/icbc-credit-card-originals.json --check --fixtures /绝对路径/新目录` 准备真实夹具。


`IcbcCreditLegacyReplacementTest` 验证两个旧信用卡定义的当前替代，原定义整体保留 Python 参考。不同卡号的相同交易经实际来源匹配分别保留；同一 PDF 重读识别为重复，同文件不同位置仍保留。旧七元素格式缺币种时保留问题候选，选账户后仍须补齐币种。设置 `FT_KOTLIN_ICBC_CREDIT_LEGACY_DIR`；以 `.venv/bin/python scripts/export_icbc_credit_legacy_replacements.py --output compose/backend/src/test/resources/icbc-credit-legacy-replacements.json --check --fixtures /绝对路径/新目录` 准备四份真实 PDF。


`IcbcRefundPairingOriginalTest` 迁移 `tests/test_convert.py::TestIcbcRefundPairing` 的 23 个原定义／92 个断言。原扁平文本单元映射为当前信用卡表格列，经实际归一路径、当前资金扫描（原退款行交易序号形成独立分组后显式全选同一账户）和当前关系规划器核对记录、刷卡金冲减标记、退款候选与配对强度；两条旧弱置信预期按当前同账户同币种商户强匹配登记为 retained，不恢复旧文本配对。资源以 `PYTHONPATH=.:src .venv/bin/python scripts/export_icbc_refund_pairing_originals.py --output compose/backend/src/test/resources/original-icbc-refund-pairing.json --check` 核对。


`IcbcRowNormalizationOriginalTest` 迁移 `TestIcbcParseLines` 11 个和 `TestIcbcEdgeCases` 4 个定义，共 15 个原定义／43 个断言。原扁平文本单元映射为当前表格列，经真实 `normalizeIcbcDocument` 核对原生记录的日期、币种、对方／摘要分离、金额方向、分类、卡尾和转账摘要，日期另核对产品行上海时区时间；`收支` 由原 `借`／`贷` 或金额符号确定。短 hash 身份、旧 CNY 缺省、旧前向扫描窗口、非精确方向值和真实 PDF 占位共 7 个定义整体保留 Python 参考。资源以 `PYTHONPATH=.:scripts .venv/bin/python scripts/export_icbc_row_normalization_originals.py --output compose/backend/src/test/resources/original-icbc-row-normalization.json --check` 核对。
`PlatformRefundMatcherOriginalTest` 迁移 `tests/test_platform_refund_matchers.py` 的 21 个定义，共 37 个原断言。支付宝订单键与关闭／还款状态谓词直接调用 `CashPlatformRefund`；平台硬键定义用 `CashRefundHardKey.match` 核对多笔部分退款共享原消费、投资流出订单键、微信红包退回 `p2p_return`、无链接拒绝与非消费 `transfer_reversal`；微信双行定义核对内嵌退款金额、原消费／收入腿、同支付方式、ISO 时间最近唯一、30 天部分退款、红包／转账退回排除及京东拆分。输入与预期取自原定义字面量。
`RefundOffsetOriginalTest` 迁移 `tests/test_transaction_relations_refund.py` 中 34 个使用 `evaluate_refund_offset` 的定义，共 36 次调用／88 个原断言。导出器以真实 Python 测试运行并记录每次 `FactView` 输入与提案结果；Native 用同一输入回放 `CashRefundMatch.evaluate`，逐字段比较完整提案 JSON。资源以 `PYTHONPATH=.:src:tests:scripts .venv/bin/python scripts/export_refund_offset_originals.py --output compose/backend/src/test/resources/original-refund-offset.json --check` 核对。


`RefundClassificationOriginalTest` 迁移 `TestIcbcRefundPlatform` 一定义和 `TestCcbRefundClassification` 两定义。信用卡退款平台经实际归一路径、资金分组同账户选择与关系规划核对两条事实和一条退款关系；CCB 两定义用显式同账户同币种同额事实核对当前规划器的单候选强、多候选弱，保留原强度字面量，不恢复旧分类器。资源以 `PYTHONPATH=.:src .venv/bin/python scripts/export_refund_classification_originals.py --output compose/backend/src/test/resources/original-refund-classification.json --check` 核对。


`RefundCardOrderOriginalTest` 迁移 `TestCardNumber` 两定义和 `TestAlipayOrderPrefix007` 一定义：信用卡全额／部分退款保留卡号尾号，支付宝原 BOM CSV 经真实解码与当前关系规划器核对订单前缀退款。设置 `FT_KOTLIN_REFUND_CARD_ORDER_DIR`，以 `PYTHONPATH=.:scripts .venv/bin/python scripts/export_refund_card_order_originals.py --output compose/backend/src/test/resources/original-refund-card-order.json --check --fixtures /绝对路径/新目录` 准备原 CSV。


`IcbcDebitReversalOriginalTest` 迁移 `TestIcbcDebitReversal` 7 定义：三个撤销定义核对当前“无撤销关系、事实保留”及退款规则差异；四个借记卡退款定义把原文行映射为十三列、显式补缺失摘要后走资金选择与当前规划器，核对强度、候选数与金额。无外部夹具，直接使用仓库资源 `original-icbc-debit-reversal.json`，以 `.venv/bin/python scripts/export_icbc_debit_reversal_originals.py --output compose/backend/src/test/resources/original-icbc-debit-reversal.json --check` 核对。


`AlipayRefundBasicOriginalTest` 迁移 `TestAlipayCategory` 5 个基础退款定义：原 BOM CSV 经真实解码、归一、资金同账户选择与当前关系规划核对记录数、金额、分类、配对、退款信号与强度；投资理财流出的 category-nocount 强配对差异按整定义 retained，当前只形成待确认 open-leg。设置 `FT_KOTLIN_ALIPAY_REFUND_DIR`，以 `PYTHONPATH=.:scripts .venv/bin/python scripts/export_alipay_refund_basics.py --output compose/backend/src/test/resources/original-alipay-refund-basics.json --check --fixtures /绝对路径/新目录` 准备原 CSV。


`AlipayRefundMatchingOriginalTest` 迁移 `TestAlipayCategory` 7 个退款匹配优先级定义：原 BOM CSV 经真实解码、归一、资金同账户选择与当前关系规划核对配对消费、日期、订单号、候选数与强度；旧 `rule_hint` 标签映射到当前 `order_lock`／`title_exact` 信号或 `scan.alipay.order_prefix.v1` 规则，描述兜底和长前缀自动核销的强度／候选数差异按整定义 retained。与基础退款类共用 `FT_KOTLIN_ALIPAY_REFUND_DIR`，以 `PYTHONPATH=.:scripts .venv/bin/python scripts/export_alipay_refund_matching.py --output compose/backend/src/test/resources/original-alipay-refund-matching.json --check --fixtures /绝对路径/新目录` 准备原 CSV。


`WechatRefundOriginalTest` 迁移 `TestWechatCategory` 9 个退款定义：原 XLSX 工厂字节经真实解码、归一、资金同账户选择与当前关系规划核对配对消费、规则、候选数、强度及红包／转账退回分类。微信原始消费行状态含「退款」时被归类为 refund，测试按 `CashImportRelationDiscovery` 为负向 refund 原始消费注入 `offset_role=expense`，并单独回归无该角色时 relation-core 仍拒绝。设置 `FT_KOTLIN_WECHAT_REFUND_DIR`，以 `PYTHONPATH=.:scripts .venv/bin/python scripts/export_wechat_refund_originals.py --output compose/backend/src/test/resources/original-wechat-refund.json --fixtures /绝对路径/新目录` 生成原 XLSX，再用同一命令加 `--check` 核对资源与工作簿单元格值。

账本版本接线覆盖来源绑定、问题及候选、认证空页证据、原子流水创建、现金条目／成员、普通导入／草稿合并／人工创建／删除及关系／分类／资金对应；同值与空操作不推进，候选集合不变时保留原 ID。`LedgerSourceVersionContractTest`、`LedgerEntryVersionContractTest`、`LedgerCashWriteVersionContractTest`、`LedgerRelationVersionContractTest` 与对应原关系／分类／人工／条目回归在 SQLite／真实 PostgreSQL 验证，其他现金事实写入口及端侧同步仍待接通。当前命令与证据见 [离线账本任务](../../openspec/changes/offline-first-ledger-import/tasks.md)。

选定工作区的版本读取为 `GET /api/v1/ledger/version`，需登录并具有当前工作区成员权限（viewer 可读），返回形如 `{"ledger_seq":"42"}` 的完整十进制字符串；接口不返回账本事实、不修改业务数据。全量快照接口已提供；共享领域层可从同一快照生成既有列表读取 DTO，包含稳定收支身份、公共字段、分类路径、来源、待处理原因及转账双端，读取版本使用 `ledger_seq`；`buildLocalCashFilterOptions` 按完整基础可见集合与全部分类目录生成全局选项。平台 CSV 的来源／输入预算／编码边界已复用共享读取器；本地 CSV 与工银亚洲字节工厂可直接返回完整归一文档，工作簿与完整文件调度仍待完成。端侧筛选、分页及缓存尚未接入应用。新增 Kotlin OpenAPI 元数据维护在 [openapi-additions.json](openapi-additions.json)，通过 `PYTHONPATH=src:. .venv/bin/python scripts/export_kotlin_openapi_contract.py` 合入现有运行时资源；同命令加 `--check` 核对，重名路径或组件被拒绝。
