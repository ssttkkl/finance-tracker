# Kotlin 后端本机预览

此运行时处于全量迁移过程中，已实现认证、工作区、邀请与成员接口，分类目录读取、创建、编辑、排序、删除检查、原子删除及单项/批量分类，以及账户按 ID 的读写、现金/投资列表、永久收据的五个 GET、管理员投影状态查询/重建、账本类型选项、手工收支新建、完整手工转账创建及旧现金新增/余额校准，共 40/86 个 HTTP 操作。匿名双库验证与剩余专项见 [实施证据](../../openspec/changes/kotlin-backend-shared-core/tasks.md)，全部接口状态见 [迁移盘点](../../openspec/changes/kotlin-backend-shared-core/inventory.md)。

当前收支详情、按流水定位及收支证据的三个 GET 已实现，功能由 SQLite、PostgreSQL 18 与现有 Compose 消费测试验证；最终独立审查尚未完成，暂不增加上面的已验收数。读取在同一财务快照中取得版本、金额和实际成员，返回前重新核对当前权限；列表和维护入口仍待迁移。

证据返回根记录、关联流水及现金部分、已确认关系、非活动关系提示和退款时间线，资金调拨信息仅含原白名单；不返回账单原文或待采用正文。来源成员消费者支持草稿空值，旧 `EvidenceDto` 对空金额／空时间仍解码失败，这一客户端既有限制保留；完整来源原文流程还依赖未迁移的单条流水详情接口。

工作区删除尚缺完整财务数据验证。分类 update 保持 Python 的调用边界；独立 move 已维护当前投影。账户余额/估值查询、旧写入适配及其余财务写入、查询和后台任务仍未迁移。原 Python 后端和正式运行入口保留，本预览不用于生产替换。

后端依赖独立的 `business-contracts`、`business-domain` JVM target，不依赖 Compose UI；JSON 使用 `kotlinx.serialization`。密码适配使用仅服务器依赖的 Argon2 实现，与原 Python 密码双向兼容；会话只保存 SHA-256 令牌摘要，保留有效期、当前工作区和成员权限。分类目录保留原排序、名称路径与修订，创建、移动和排序重读权限与目录修订；客户端和服务端共用祖先、循环与五层规则。注册和登录的凭据字段仅接受字符串；其他接口的现有字段转换保持原合同。

已实现的业务查询和写入使用 jOOQ OSS `3.21.9` 的生成字段与 DSL，数据库功能矩阵为 SQLite 和 PostgreSQL 18。jOOQ 复用现有受管 JDBC 事务，不自行提交、回滚或重试；JDBC 仍负责驱动配置、schema 校验和事务控制。金额、UTC 微秒与 JSON 由后端专用绑定保留，SQL 及绑定值日志关闭。表、字段和记录类型位于 [生成目录](src/generated/kotlin)，不进入公共协议或 Compose 模块。

现金字段、转正式、投影构建、关系角色及最终图的纯规则已迁入 JVM，并保留原生单测及 Python 匿名差分；投影和完整关系图已由内部全量重建与发布消费；手工收支新建和转账创建已接入同事务写入，其余现金写入仍待迁移。时间、投影与完整关系图差分分别由 `scripts/export_cash_time_goldens.py`、`scripts/export_projection_goldens.py` 和 `scripts/export_cash_relation_goldens.py` 生成，支持 `--check`，不读取数据库或真实账单。

来源事项选择的纯规则已实现，覆盖正文缺省／明确清空、固定字段、现金部分归属、多个来源冲突及当前事项修订。`scripts/export_cash_source_resolution_goldens.py --check` 校验 708 组匿名输入、完整结果和正文摘要。来源正文派生已覆盖微信、支付宝、建行、工行信用卡／借记卡和工银亚洲；保留原文备注、支付渠道与退款线索，账户身份只取可信解释，人工备注优先。`scripts/export_cash_source_effects_goldens.py --check` 校验完整结果及输入不变。单条流水详情及维护事务尚未接入这些规则，不能据此增加已验收 HTTP 数或关闭来源处理流程。

账单原始文档读取已实现平台 CSV/TSV/XLSX、建行 XLS、工银亚洲 UTF-16 TSV 及工行信用卡/借记卡 PDF；PDF 使用后端专用 PDFBox，保留页/字符/表/行/列预算、独立密码失败类型、页指纹与文档键。各行读取器由 `scripts/export_cash_statement_document_goldens.py`、`export_bank_statement_document_goldens.py` 和 `export_icbc_pdf_document_goldens.py` 生成匿名 Python 金标准对照；记录类型与对方账号规则由 `export_cash_record_type_goldens.py` 生成行组合对照。记录类型/子类型分类与对方账号规范表示已按来源行字段迁入后端，并通过匿名 Python 行组合对照；平台 CSV/TSV/XLSX、建行 XLS/工银亚洲 TSV 以及工行信用卡/借记卡 PDF 行归一也已按 Python 完整行结果对照。统一读取/归一入口、来源身份/文件绑定键、已解析文件组合及来源账户扫描、支付宝组件分摊及来源对应解析已实现并按 Python 键值对照；多文件批次规划已保留最大出现次数、可靠快照选择、共同结果引用和完整旧目标证据，34 个原功能定义与 49 组完整 Python 结果已验证；来源账户扫描与选择、上传文件准备纯层（严格文件命令、脱敏、范围继承、选择失效与关系证明）已实现；来源规划只读仓库和导入会话视图也已实现并按双库匿名种子对照；上传 HTTP 已接入会话创建、追加、替换、范围、删除、取消和读取，解析在锁外、发布与永久收据核实同锁。导入计划公开视图、分页与冻结详情纯层已实现并按 15 组 Python 结果对照；导入逐流水操作规划纯层已实现并按双库 Python 结果对照；条目目标/公共条目编辑与关系核心、几何/镜像图、余额/损益投影与退款文本/菱形匹配与平台退款配对纯层已实现并按 Python 结果对照；镜像/转账阶段、导入业务计划/预览/提交、账户映射持久化、真实 R2 连通性和真实账单样本仍未迁移，不能据此宣称导入流程可用。

来源账户对应的内部仓库和只读建议已实现：按完整来源组查询已确认对应、窄范围旧对应和完整账户别名，重新核对当前账户资格；发布在调用方事务内执行，同值不改变修订。[导入资金组扫描及账户选择准备](src/main/kotlin/com/finance/tracker/backend/CashImportFunding.kt) 已实现，按完整身份区分来源，保留明确分摊、优惠排除和当前账户资格；115 组 Python 完整结果及原生边界回归通过。上传与实际成功证明的提交接线尚未完成。

解析会话已实现私有 JSON 编解码、独立文件/整体预算、用户与工作区隔离、原到期时间、条件替换、取消清空、不可变计划快照与有界公开页缓存。内存存储和 R2 条件对象端口使用同一生命周期测试；R2 端口保留未知结果、关闭读取流并按元数据分页清扫。AWS S3 SDK 客户端和后台清扫器已接本机运行入口，启动首扫失败则拒绝启动，停止时等待在途扫描并关闭客户端；对象请求保留条件头，结果未知不自动重试。维护计划已接同一配置存储，支持内存和 R2，实例替换后可读取原方案；财务提交成功后清空临时正文，清空失败不改变永久收据。上传文件准备纯层、只读来源规划/会话视图和上传 HTTP 已接入此存储与工作区事务；导入计划公开视图/分页纯层已实现，但预览构建、选择和提交尚未迁移，当前上传会话仍不能完成导入。匿名格式对照由 `scripts/export_parsed_import_codec_goldens.py --output compose/backend/src/test/resources/python-parsed-import-codec.json --check` 验证。

已接入的单流水维护提交在同一工作区写锁内按冻结输入重建，核对操作指纹、账户资格、事项和收支快照；依赖变化时不写财务数据、不消费方案。草稿保存保留外部事项，来源待处理事项阻止转正式，公共人工值与事项处理同事务写回。完整关系、多成员和剩余原测试仍待迁移。

稳定收支对象仓库已有双库原生测试，覆盖公共人工值、成员恢复值、合并冲突、拆分继承和事务内对象身份。该仓库已接入全量重建、新工作区初始化和内部增量维护；手工收支新建和转账创建已使用该仓库，其余公开现金写入仍待迁移。投影维护须在同一事务共用仓库上下文并完成 flush，不能跨事务复用缓存。保存点创建前保存既有脏值，回滚后恢复收支、成员和当前状态缓存。分类独立 move 服务已在同事务维护使用中的投影；update 按 Python 现状保留调用边界。

SQL 源值适配保留 SQLite 金额表示、PostgreSQL 补零归一及 Python 微秒时间；固定小数、Unicode 键序、浮点拒绝和 UTF-8 摘要由 `scripts/export_cash_canonical_goldens.py --check` 的 1,548 个匿名向量验证。内部投影源读取、确认闭包和局部关系上下文已有双库测试；完整上下文与摘要通过 `scripts/export_projection_source_contracts.py` 的匿名 SQL 样本对照。联合校验在准备决定前重新核对版本、完整组、端点和角色证据，返回解析份额，不写关系或发布数据集。全量重建、暂存发布和内部增量维护已有双库测试；增量只替换受影响完整组，独立项及相同结构保留行身份。手工收支新建与转账创建已接入；转账编辑、维护及导入写入仍待迁移。持久化图的原生测试另核实双账户真实分配、部分菱形、拆旧组及 80 节点交替闭包预算；参数预算按实际 SQL 执行统计。

内部来源证明绑定同一仓库和受管 JDBC 事务，提交、回滚、SQL 异常或连接关闭后拒绝旧证明；自动提交查询不能签成一次来源快照。SQLite 物理事务与 PG 隐式事务分别处理，普通 savepoint 保留外层身份。标准 JDBC 归属链保留受管连接，原生驱动 unwrap 和批次中的事务控制明确拒绝，普通 DML batch 保留。发布已消费同仓库/同事务证明，并重新核对来源摘要、事实及成员数量。暂存替换保留 900 条批次与完整角色/序号，旧数据集清理、稳定收支与活动指针同事务提交；非存储失败独立记录安全诊断，原活动指针/来源修订变化时不覆盖。新工作区创建复用该管线，管理员重建 HTTP 已接入并通过双库验证。

手工转账保留现金、贷款、借款及证券/加密账户组合；双侧事件、现金部分关系、余额与投影在同一事务保存，完整响应由既有 Compose 创建 API 消费。同币种显式到账金额按原合同忽略并返回警告，跨币种要求到账金额；不新增手续费或客户端流程。匿名对照由 `scripts/export_cash_transfer_contracts.py` 准备新隔离双库，纯事件/余额对照由 `scripts/export_cash_transfer_rules.py --check` 验证。

旧现金新增和余额校准分别使用 `POST /api/v1/cashflow/add` 与 `POST /api/v1/cashflow/checkin`。新增保留原来源、日期响应和金额精度；成功响应无法编码时整笔回滚。余额校准保存零金额事实，仅设置指定币种的余额，保留其他币种；快照结构损坏与金额错误保持原状态及检查顺序。两个入口都在锁后及返回前重验当前权限。Compose 当前没有这两个旧接口的消费者，本片不增加页面或客户端入口。

## 运行匿名预览

在 `compose/` 使用 JDK 21 构建：

```sh
./gradlew :backend:installDist --no-configuration-cache --max-workers=1
```

只连接已存在、已达到 `20261028_51` 的任务专用匿名数据库。程序不创建 schema、不升级数据库，也不自动选择其他数据库。当前 URL 支持限于绝对文件 `sqlite+pysqlite:////...` 或有 host、无 query 的 `postgresql+psycopg://...`；SQLite 默认驱动/相对路径、PG socket 与 SSL 等连接参数尚未移植，明确拒绝而非静默忽略。

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

Python 单测迁移对应表为 `compose/test-migration.json`。从根目录执行 `scripts/check_kotlin_test_migration.py --collection <完整 pytest --collect-only -q 输出> --report <仓库外报告>` 检查源定义、展开参数、夹具及目标；追加 `--require-complete` 后，任何未迁移项都会失败。授权修复新增的源回归通过 `source_additions` 记录当前指纹和理由，仍按同一断言及目标门禁验收，固定基线不变。规则黄金和 Python 参考回归不能代替 Kotlin 原生测试；当前完整迁移门禁仍未通过。本轮按用户要求仅验收功能，性能测试未作为完成门槛，原性能预算和未通过证据见实施记录。

名称 casefold、币种大写与数字字符数据由 `scripts/export_category_casefold.py --check` 核对，沿用 Python 3.11 / Unicode 14。分类移动已同步修复 Python 与 Kotlin 的后代深度计算；仅影响后续命令，不自动修复现有数据库。

账户命令保留名称/币种规范化、固定类型、当前修订与永久原键重放；账户、余额快照和结果收据一起提交。删除必须先停用且没有真实引用或非零/不可读余额；删除后仍可核实原结果。存储结果未知返回 `account_result_unknown` 和 `commit_state=unknown`，沿原键核实；查询不可用返回 `account_unavailable`。当前原生专项包含 editor 成功路径、viewer 拒绝、锁等待后撤权、晚期回滚及提交后响应丢失。

同样的实际 Ktor 回包目录可运行 `:shared:testAndroidHostTest --tests com.finance.tracker.KotlinAccountResponseIntegrationTest`，验证普通账户读取、导入账户读取与读写命令消费共享 DTO。账户列表按现金/投资视图分组，保留停用账户；历史余额/估值查询不在此列表接口中。

永久收据读取保留操作者和当前工作区隔离、旧键原文查询、行/关系分页及当前投影位置；读取也锁定工作区并复核当前权限。收据摘要只输出公开字段，损坏记录返回安全诊断。服务端保留任意精度非负计数；现有 Compose 仍使用 `Long`，超范围计数是未解决的客户端边界。实际回包目录可运行 `:shared:testAndroidHostTest --tests com.finance.tracker.KotlinReceiptResponseIntegrationTest`，验证现有 API 的读取消费及该限制。现金提交、resolve 和导入暂存仍未迁移。

永久收据仓库的摘要/结果校验、原键重放、单一会话/编辑上下文消费已迁入，并由账户命令实际复用。仓库与业务写入共用调用方事务，异常整体回滚；原数据库永久唯一和工作区复合外键由真实双库测试验证。摘要按原 Python ASCII JSON 编码计算 1 MiB 上限，包括 DEL 转义；`scripts/export_receipt_write_goldens.py --check` 核对 1,052 个匿名校验及字节预算向量。通用 UoW 并发、历史 Alembic 升降级、现金提交与 retry 仍未验收。

`GET /api/v1/operations/projections` 只允许当前工作区管理员读取构建状态；没有状态行时返回原缺省，不创建状态或触发重建。失败构建保留已有活动数据集和错误字段，存储失败返回 `projection.unavailable`。`POST /api/v1/operations/projections/rebuild` 已接入实际重建：锁后及返回前重验管理员，拒绝时整事务回滚且不写失败诊断；领域校验返回 409，运行/存储错误保留原 503 合同。状态仓库同时用于收据结果的当前 ready 数据集/版本读取。Compose 当前没有管理员状态/重建入口，因此不提取额外公共 DTO。

`GET /api/v1/cash-ledger/options` 为当前工作区成员返回原 17 类流水、合法子类型和 3 类关系，保留标签及枚举顺序；空工作区也可读，不初始化数据集。三个选项 DTO 由现有 Compose `fetchLedgerOptions` 和后端共同消费，每次返回独立列表。`scripts/export_ledger_options.py --check` 核对 Python 完整选项，实际回包可用 `:shared:testAndroidHostTest --tests com.finance.tracker.KotlinLedgerOptionsResponseIntegrationTest` 验证。手工新建已使用这些选项；关系操作仍待迁移。

分类删除按当前收支记录计使用量，保留目录/分类修订和确认检查；同一事务清除当前值、拆分恢复值、含已删除流水的分类引用和全部投影，保留其他人工字段并使依赖候选失效。活动投影和状态仅按原 ready 规则推进版本，异常整体回滚。DELETE 按原始字节处理 Python JSON 编码、语法和整数错误边界；`scripts/export_category_json_goldens.py --check` 校验匿名兼容向量。实际 Compose 确认协议通过 `KotlinCategoryDeletionResponseIntegrationTest` 消费 `FT_KOTLIN_CLIENT_CONTRACT_DIR` 下回包。万条记录耗时、集合 SQL 和 RSS 采样专项不代表原高水位内存测试已经迁移；准确状态见实施证据。

单项和批量分类 PUT 保留完整成员、当前数据集/版本与同值人工保护；公共分类写入稳定收支对象，原始流水及其人工覆盖保持独立。单成员对象同步独立恢复值，多成员保留各自恢复值；整批原子更新，已保护且展示相同的重复命令不推进版本。锁等待后重新核实角色、会话有效期与当前工作区，原客户端通过 `KotlinClassificationResponseIntegrationTest` 实际消费回包。性能保留原 12 条 API SQL / 500 毫秒门槛；后端源测试和未迁移定义仍以对应表为准。

内部余额快照仓库保留账户稳定 ID、当前名称和 crypto/security 映射，组成项增量以精度 80 计算并验证金额范围；草稿和删除记录不计余额，导入的普通转账排除，manual 来源保留。原生双库验证含负零、完整有效位数、账户改名及回滚；手工记账、转账和旧现金新增/校准已接入，导入仍待迁移。内部账户类型只接受真实账户模型提供的字符串。

手工新建的内部请求读取器已验证 65,536 字节、编码、重复键/非有限数、严格字段类型及 64 个现金部分限制；直接应用边界复制可变容器，保留缺省与明确清空的区别。POST `/api/v1/cash-records` 已使用该边界，原子保存流水、现金部分、余额及稳定收支/投影，并复用 Compose 现有创建消费者。缺省或明确清空备注/分类的人工保护与原 Python 一致；纯日期及无时区时间按 UTC 保存，正式入账的独立时间校验仍要求时区。OpenAPI schema 和其余现金入口继续待迁移。
