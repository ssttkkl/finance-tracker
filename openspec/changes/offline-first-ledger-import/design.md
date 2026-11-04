## Context

本变更以迁移后的 Kotlin 架构为基线：服务端 `compose/backend`（Ktor + jOOQ，PostgreSQL 与 SQLite 双后端，schema 由 Alembic 独占），共享模块 `compose/business-contracts`（线路 DTO）与 `compose/business-domain`（纯规则），客户端 `compose/shared`（Compose Multiplatform，Android/iOS/Web）。Python 运行时在本变更生效前已废弃，不在讨论范围。动机见 `proposal.md`。

当前约束：客户端无本地数据库（仅内存分页 + token/恢复指针）。导入解析、归一化、查重、账户归属、操作计划、候选召回、关系配对与投影派生全部在服务端；浏览依赖服务端物化投影（`cash_projection_*`）与版本绑定游标。余额由服务端 `ledger_snapshots` 增量维护。关系匹配依赖较宽的既有账本状态（流水与组成项、来源绑定与来源证据、关系与候选、别名、账单账户映射、待处理问题、entry 与成员）。

### 阶段 1 探索结论：共享规则下沉边界与测试基线

**可下沉的纯规则**（`compose/backend` 中不依赖 SQL/jOOQ/session 的实现），按职责分组：

- 行归一化与类型分类：`CashStatementRowNormalization`、`CashRecordClassification`、`CashRecordTypes`、`CashRecordPolicy`。
- 来源身份与查重：`CashSourceIdentity`、`CashSourceResolver`、`CashSourceResolutionPolicy`、`CashSourceRows`、`CashSourceBatch`。
- 资金归属：`CashImportFunding`/`CashImportFundingEngine`。
- 候选召回索引：`CashFactCandidateIndex`。
- 关系配对：`CashRelationPipeline`、`CashMirrorMatch`、`CashTransferMatch`、`CashRefundMatch`、`CashRefundHardKey`、`CashRefundSignals`/`CashRefundDiamond`、`CashPlatformRefund`、`CashRelationPlanning`、`CashRelationGeometry`、`CashRelationCore`。
- 关系图与策略：`CashRelationGraph`、`CashRelationPolicy`。
- 投影派生与余额：`CashProjectionRules`、`CashRelationProjection`。

**留在 `compose/backend` 的耦合层**（读写仓储与服务编排）：`CashImportSourcePlanner`、`CashImportOperationPlanner`、`CashImportBusinessPlanBuilder`、`CashImportCandidateRepository`、`CashImportRelationDiscovery`、`CashRelationService`、`CashProjectionService`、`CashProjectionRepository`、`CashProjectionSourceRepository`、`CashSnapshotRepository`/`CashBalanceService`、`CashFundingMappings`/`CashImportAccountMappings` 等。共享层 MUST NOT 依赖 jOOQ、数据库连接或 Ktor。

**已在 `business-domain` 的共享面**（新迁移应与之一致）：`LedgerDecimal`/`DecimalRepresentation`、`AllocationBalance`、`CashComponentRules`、`CategoryRules`、`DecimalCharacterData`。

**测试基线现状与缺口**：backend 已有大量 `Cash*` 测试类可作为等价性 oracle（各能力分组的 `*GoldenTest`/`*OriginalTest`）。`business-domain`/`business-contracts` 只声明了 `commonTest`，构建脚本的 `check` 未显式接线测试任务；执行任务 1.1 前必须先确认共享模块的测试任务确实可运行，否则"测试先行"无法验证。

**已验证的基线结果（阶段 1 实测）**：

- `:business-domain:jvmTest` 可运行，现有共享规则测试通过——JVM 侧可作为共享层的验证入口。
- `:business-domain:check` 初次失败于 JS/Wasm 依赖 `androidx.paging:paging-common-js:3.5.3` 解析不到。核实后确认**镜像与官方源均可正常下载该构件**，实际是 Gradle 缓存了上一次失败的解析结果（负缓存）。执行一次 `--refresh-dependencies` 后 `:kotlinNpmInstall` 成功，`:shared:compileKotlinWasmJs` 与 `:shared:compileKotlinJs` 均编译通过。
- 结论：JS/Wasm 目标在本环境可用，任务 9 与 11.2 无镜像阻塞。共享层验证仍以 `jvmTest` 为主，避免每轮拉起 npm 与浏览器测试。
- **下沉的前置阻塞（实测发现）**：上列"纯规则"只对数据库解耦，仍依赖 JVM 专有原语——`sourceDigest` 经 `tokenDigest` 使用 `java.security.MessageDigest`，`sourceJson` 使用 `java.math.BigDecimal`，多处使用 `String.codePointCount` 与 Java 浮点文本格式化。移入 `business-domain`（KMP：`jvm/android/ios/js/wasmJs`）前 MUST 先提供多平台等价原语（SHA-256 摘要、码点计数与迭代、Python 浮点文本、`LedgerDecimal` 十进制表示）。此项已登记为任务 1.6，是 1.3 的硬前置。
- **前置原语的依赖链**：摘要／码点／空白与稳定 JSON 结构已共享；浮点文本使用 binary64 位模式构建精确十进制值及相邻浮点的中点区间，按最短有效位、最近值与偶数规则选择表示，再应用 Python 的固定／科学计数边界。专用私有整数算术仅服务浮点格式化，不扩展 `LedgerDecimal` 的账务范围，不增加通用任意精度依赖。backend 委托共享实现，旧 `BigDecimal` 版本仅保留在 JVM 差分测试。时间解析使用共享 `kotlin.time.Instant` 与纯日历运算，保留现有接受／拒绝、微秒和偏移合同；backend 适配 JVM 时间对象并保持既有错误类型。`CashSourceCoverage` 及来源身份实体的迁移归入任务 1.3。
- **多平台字符串化的硬约束**：任何进入 digest 或派生的数值/时间文本 MUST 由共享实现产生，MUST NOT 依赖 `Double.toString()`、平台 `Instant` 格式化或 JVM 默认文本，否则三端与服务端会漂移。

## Goals / Non-Goals

**Goals:**

- 端侧在无网络时能浏览账本并完成导入预览与提交。
- 服务端不再承担导入启发式计算与投影物化，只保存规范事实、校验不变量并同步。
- 三端（Android/iOS/Web）与共享模块使用同一份领域规则，避免多份漂移。
- 在多设备共用同一账本时，冲突以可识别的拒绝暴露，不产生静默认。

**Non-Goals:**

- 不做变更日志/增量差量（本期以版本 + 全量快照替代，仅保留可升级形状）。
- 不引入 CRDT、现成同步引擎或自动合并。
- 不砍余额能力，不纳入投资导入与估值算法。
- 不做多人协同编辑。
- 不改变 Alembic 对 schema 的所有权。

## Decisions

### D1 一致性模型：服务端权威的状态同步，而非 CRDT 或现成同步引擎

**决定**：采用"服务端权威 + 端侧状态缓存"的状态同步模型。端侧持有完整事实副本，服务端是事实与不变量的唯一裁决者。

**理由**：本领域的正确性在跨记录不变量上（一个锚点至多一条生效关系、退款不超额、每条流水恰好归属一个投影、余额守恒、分类路径合法）。CRDT 只保证单对象收敛，不保证集合级约束；采用 CRDT 后仍需服务端校验器，且校验失败时本地已合并，反而产生"本地已合并、服务端不认"的分歧。

**备选与否决理由**：

- **CRDT（Automerge、Yjs、cr-sqlite、sqlite-sync、Ditto）**：与产品不变量"服务端校验、不自动合并"正面冲突；金额字段用 LWW 会静默丢写、用多值寄存器必须人工裁决（等于恢复冲突流程）；时序依赖客户端时钟（已被否决的身份依据）；墓碑语义无法承载审计与重新导入复活；不解决领域计算，且元数据/op log 反而增加存储。**否决作为主方案**；仅在将来转向多用户协同编辑时，可对**软字段**（备注、分类、标签）评估，硬事实仍服务端校验。
- **现成同步引擎（PowerSync、ElectricSQL、Turso/libSQL、ObjectBox Sync）**：PowerSync 是唯一同时满足 KMP 客户端 + 本地 SQLite + 服务端权威写入的方案；ElectricSQL 为 Postgres 读路径且无 Kotlin 客户端；Turso/libSQL 是整库副本且绑定弱；ObjectBox Sync 为 NoSQL 且商业授权。**本期否决**，PowerSync 另记条件性备选（见下）。
- **自研增量变更日志**：能省带宽，但引入日志表、保留窗口、剪枝与全量兜底四套逻辑，直接增加服务端存储。**本期不做**。

**升级触发条件**：真需要增量同步、实体类型/写入频率显著增长、或需要多端实时协同。届时优先加 `ledger/changes` + 客户端水位（增量），实时性可用已有 SSE + 版本查询。

**条件性备选：PowerSync（记备查，本期不引入）**

PowerSync 的写入模型与服务端权威一致（客户端写本地 SQLite → `ps_crud` 上传队列 → 后端 `uploadData()` 校验落库，失败自动回滚并异步传播），比 CRDT 契合。本期仍不引入，原因：

- **源库只支持 Postgres/MySQL/MongoDB/SQL Server，不支持 SQLite**。采用即等于放弃 SQLite 服务端，牵连 `shared/runtime-database` 的"双后端一等支持、不得静默回退"原则与整套双后端验证策略，属于长期、持续成本。
- **Kotlin SDK 的 Web(JS/Wasm) 仍为实验性支持**，wasm 目标尚在等待上游稳定，且已知 `Long` 表示与 `ByteArray` 拷贝问题；本项目 Web 目标正是 `js`+`wasmJs`，在财务应用上押实验特性风险过高。
- **Sync Rules 引入第二份"选哪些表/行"的知识**，需随 schema 变更维护，与 Alembic 独占 schema 冲突；工作区隔离也要在其中再表达一遍。
- **写路径是 CRUD op 批次**，与"领域写集整体校验 + 全量或全失败 + 基线过期重派"不完全对齐；批次边界由 SDK 决定，需自建适配才能恢复导入原子性与领域级拒绝语义。
- 增加 **PowerSync Service + bucket 存储 + Postgres 逻辑复制**，与降低服务端成本目标相悖；且它**不解决领域计算**（查重/配对/投影），那才是复杂度的主体。
- **本地静态加密支持待核实**（D10 要求）；PowerSync 使用自带 SQLite 构建，能否满足静态加密需实测。

**预设切换判据**：仅当同时满足——(a) 接受 Postgres 单后端并放弃 SQLite 服务端；(b) Kotlin Web/Wasm 支持转为稳定；(c) 接受 PowerSync Service 的运维；(d) 产品确需实时多端 + 真增量——才重新评估。

**验证计划（改造完成后另行 spike）**：单拉分支并搭临时环境（Postgres 逻辑复制 + PowerSync Service + Sync Rules + 上传适配），时间盒、**不合并**、结论写入 artifact。基准必须覆盖"**语义等价 + 代码行数增减 + 性能**"三轴，不得只比性能——增量与整包重下相比，PowerSync 按构造就在性能上占优，而真正决定取舍的是语义、可维护性与运维。先做**零成本自身基线**：按现实规模（1 万 / 5 万条流水）测量自研快照刷新的耗时与流量；若已在可接受区间，则性能不构成切换理由，spike 可省。

### D2 同步机制：工作区版本 + 全量快照

**决定**：服务端为每个工作区维护单调递增的账本版本，与写入同事务推进。端侧记录已同步版本；刷新时先查版本，仅当变化才拉取全量快照，并以快照版本原子替换本地缓存。快照在单个只读事务内锁定版本 `V`，分页也不跨版本。

**理由**：在已放弃差量的前提下，这是实现最简且语义最强的状态同步；版本号同时服务于并发基线与"是否需要重下"判断。

**备选**：时间戳轮询（时钟/删除语义不可靠，否决）；整包无版本盲拉（无法判断是否需要，否决）。

**回滚/升级形状**：端侧保留 `last_synced_seq`、服务端保留版本与快照接口；将来加增量只需新增接口，不动既有契约。

账本版本保存在 `workspaces.ledger_seq`，初始为 0，保持非负有符号 64 位整数；现有工作区锁继续承担写序列化。托管事务按工作区合并版本标记，首次标记与事实写入共用连接；事务回滚一并恢复。托管连接记录具名／JDBC 保存点处已有的版本标记，回滚时恢复该保存点状态并丢弃内层状态，释放时清理对应保存点。保存点后首次取得的行锁可能因回滚释放，因此不能把重读的相同版本值推断成自己的推进；回滚后重做首次标记须重新锁行并基于当时版本推进。业务版本读写使用生成的 jOOQ 字段，SQL 词法识别仅用于跟踪事务及保存点边界。未触及规范事实的读取、会话和收据写入不标记；各规范事实写入口必须显式接入，不根据 SQL 文本或任意 `Database.write` 猜测。元数据列由 Alembic 添加，jOOQ 从真实迁移后的专用 PostgreSQL 18 库生成，Kotlin 不创建 schema。

目录事实入口分别复用自身的既有事务边界：账户命令在收据重放与修订核对后、实际写入前标记；分类目录在修订推进处标记，多行移动仍共享一次账本版本；别名写入和账单账户映射发布在真实插入／更新时标记。映射同值重发和分类边界重排不标记；明确拒绝及晚期失败由外层事务回滚事实、版本与收据。其余现金事实仓储仍按任务 2.2 逐项接入，不以目录入口覆盖代表全写入面完成。

版本查询端点为 `GET /api/v1/ledger/version`，仅返回 `ledger_seq` 十进制字符串，避免 Web 数字精度损失；不接受任意工作区作为数据选择器。沿当前会话选定工作区鉴权，并核对路由捕获的用户／工作区与实际读取及返回前上下文一致；viewer 可读取，失效或未选定工作区拒绝。版本读取不写入缓存、收据或任何业务表。新增 Kotlin API 元数据在 `compose/backend/openapi-additions.json` 维护，原导出器将其与保留合同合成现有运行时资源；重复路径／组件显式失败，避免重新生成时丢掉新增 API，不改变运行时文档的单一资源入口。

全量读取端点为 `GET /api/v1/ledger/snapshot`，响应封装选定的 workspace_id、ledger_seq 与类型化事实集合。代理键、修订和金额以十进制字符串传输，UTC 时间保留微秒；不透传数据库记录或账户私有 metadata。集合覆盖账户、别名、账单映射、分类及目录修订、全部现金流水（含草稿与删除标记）及组成项、来源绑定／空页证据、关系／候选、问题／候选、entry／成员和只读资金对应。来源文档证明复用绑定 coverage 与空页，不构造不存在的文档表；投资事件、余额、物化投影、收据与会话不输出。

首个实现返回单份全量响应，不开放分页游标或独立实体拉取；数据库读取在同一事务完成，PG 使用 REPEATABLE READ，SQLite 使用同一 WAL 读快照。读取 V 之后其他设备提交也只返回 V 的事实，返回前复核会话后才交给 HTTP 序列化。后续按既定规模基线判断分页／流式需求，若引入分页须保留同一快照边界，不逐页重新读取当前版本。

### D3 计算边界：端侧执行导入阶段 1–7，服务端承担 8–10

**决定**：端侧完成解析、行归一化与类型分类、对缓存账本查重、账户归属、操作计划、候选召回、关系配对，并以**单个原子写集**提交；服务端校验结构与业务不变量、落库、发收据，**不重跑启发式**。

**理由**：兑现"服务端只写最终增量"与降算力目标；匹配所需的账本状态已被端侧全量缓存覆盖。

**备选**：仅端侧做解析、匹配留服务端（不满足主目标，否决）；服务端重跑匹配做二次确认（费算力且引入"信谁"的分叉，否决）。

### D4 投影：服务端不物化，端侧派生（C1）

**决定**：删除 `cash_projection_*` 物化与 `GET /cash-projections`、详情/证据、`operations/projections` 等读取面；浏览、筛选、分页、月度汇总与聚合由端侧用共享规则派生。服务端本期不保留聚合接口。

**理由**：端侧已有完整事实，派生是同事实的纯函数；删物化直接省存储与重建复杂度。若未来出现端侧算不动的重聚合，再按具体名单补服务端按需聚合。

**备选**：保留服务端物化（与主目标冲突，否决）；服务端物化 + 端侧派生并存（两份实现与在线离线不一致，否决）。

### D5 并发：v1 整工作区基线（L1）+ 稳定输入重派

**决定**：写集提交携带基线版本；服务端在同一事务内做等价于 CAS 的校验，基线过期即整批拒绝。端侧基于稳定输入（文件、账户映射以及按稳定引用寻址的行、entry 与配对决策）在最新缓存上重派并复用决策：**结论指纹一致则静默重提交**，不一致则提示重新核对。

**理由**：L1 实现最简、水位管理无缝隙；"重派 + 指纹比对"把它的误伤缺点补掉，体验接近作用域基线（L3）。指纹必须基于来源身份、金额、日期与参与者等业务键，不得使用 `planId`/`suggestionId`/`candidateId` 等每次派生都变的临时 ID。

**备选**：作用域基线 L3（需变更日志带作用域字段，本期不做，保留升级形状）；读集 OCC（候选窗口实体多、成本高，否决）。

**事务边界**：校验与落库必须在同一事务内完成，否则存在 TOCTOU，会接受过期写入。

**livelock**：需退避与重试上限，超限提示稍后再试。

### D6 幂等：数据层 + 提交层两层

**决定**：

- **数据层**：现金流水按来源绑定身份去重（沿用既有唯一索引语义）；交易关系按关系类型、成员来源键与关联金额的确定性指纹去重。相同指纹视为已应用并幂等成功。
- **提交层**：以用户、工作区与幂等键确定一次提交；相同键的重试返回第一次结果；结果未知时按原键核实；已成功内容不重复写入。

**理由**：行身份让重放安全，收据让回复正确且可审计；二者职责不同，不可互替。手工录入没有来源单据，只能依赖提交层。

**约束**：指纹必须由共享代码生成，三端一致，否则跨设备去重漂移。客户端生成的值（如需要）必须在本地队列中生成一次并持久化复用，重试不重算。

来源绑定、待处理问题与已认证空页在实际插入／变化／清除时标记；重复来源出现、相同问题和已存在空页不推进。问题的候选集合未变时保留既有候选行，避免同值重发删除再创建内部身份；问题正文变化而候选集合相同也不重建候选。草稿绑定迁移复用已验证身份快照的计数，空迁移不写入、不推进。对应清除只在原问题存在时标记，仍由同一工作区锁和外层事务负责原子性。

### D7 规则落位：下沉 `business-domain` 共享 KMP

**决定**：查重、配对、投影、余额规则与写集 DTO 下沉共享模块，三端与服务端共用一份实现。

**理由**：D4 要求端侧派投影、D3 要求端侧跑匹配，若各自实现必然漂移；共享实现同时保证"在线离线一致"与"双后端一致"。规则版本变化触发端侧整体重派生。

来源覆盖证明复用原 `CashSourceCoverage` 序列化结构，数据形状放入 `business-contracts`，时间包含性与可比较性放入 `business-domain` 扩展函数。domain 依赖 contracts，contracts 不反向依赖规则；backend 通过 typealias 与 JVM 时间类型薄适配复用，不保留第二份模型或算法。来源定位键使用固定的字符串／空值／整数数组编码，位置保持整数文本；原始字符串先通过严格 UTF-8 检查，再编码与摘要，不依赖 JS 的 Any 数值类型判定或 JSON writer 对非法 Unicode 的处理。

字段变更的可编辑性、固定财务字段、人工保护、类型组合、完整性与现金部分一致性由共享 `CashRecordPolicyRules` 编排。现有 backend 的 JVM 值归一与 Python 数值等价比较通过两个值适配函数进入共享规则；适配不决定哪些字段可修改，也不绕过服务端校验。该切片不宣称客户端值归一或完整导入已接通。

来源查重的分组、证据优先级、歧义判定和多重集合匹配统一下沉到共享 `CashSourceResolver`；匹配索引作为等价键由调用方提供，backend 继续用原 JVM 金额／时间编码。字段准备、来源摘要和覆盖证明的原始值桥接在构造时注入，规则主体不依赖 JVM；这一步不改变索引等价关系、来源 ID 分配或任何数据库写入，也不代表端侧生产适配与 Batch／Rows 已接通。

已解析的 `ParsedCashRow`／`ParsedCashDocument`、文件最小视图与 `SourceBatchRows` 放入共享领域层；它们是导入计算输入，不新增线路 serializer，原服务端显式暂存编码继续复用同一模型。`CashSourceRows` 共享文件顺序、字段深复制、来源范围和页面证据组合，原始 CSV／XLSX／PDF 读取与逐行归一仍在 backend，迁移模型不代表解析已下沉。

来源批次 `CashSourceBatch` 的内容归一桥接继续保留 backend 既有 Decimal／时间表示；批次决定、文件时间优先级、最大多重数与共享歧义组移到 domain。JVM 引用缓存改为请求内内容缓存：候选及去向列表以只读快照缓存内容 hash，避免同一大候选组逐行重复散列；求值缓存键同时包含“本行是否直接目标”，保持原直接结果与批内重复结果的区别。缓存不跨调用，不保存财务事实或用户决定。

来源行类型判断复用共享 `CashRecordTypeRules`，保留撤销、平台转账退回、退款、还款和渠道关键词的既有优先级；原 JVM 对任意原生数值的 Python 文本／真假转换通过两个值桥接提供，金额方向仍使用共享精确十进制表示。对方账号的掩码识别与重建证明仍由既有 backend 分类入口处理，尚不宣称完整行归一已迁移。

对方账号识别与重建校验下沉 `CounterpartyAccountRules`，`CounterpartyAccount` 只保留一份共享模型，证明仍是规则实例内的不可伪造运行时 token，不作为客户端提交即可信的证明。JVM 专属数字正则改为按同一 Unicode 14 码点表读取数字段；尾号左边界保留既有 JVM 对补充平面前置数字的特殊匹配，原 UTF-16 长度条件、属性组合、空值、掩码清理与来源原文绑定保持。十进制与非十进制数字表由共享生成器维护，公开访问返回副本，backend 生成表仅持有初始副本；原生值文本与严格来源摘要桥接继续复用 backend 既有入口。

### D8 解析边界：CSV/XLSX 端侧，PDF 服务端 parse-only

**决定**：端侧解析 CSV/XLSX。PDF 通过服务端无状态 parse-only 端点返回归一化行；该端点不建立会话、不读账本、不写业务数据，密码只用当前请求且不落日志与持久化。

**理由**：PDF 抽取当前依赖 JVM 的 PDFBox，端侧（尤其 Web/Wasm、iOS）复刻成本过高；parse-only 保持"服务端只写最终增量"的边界。

### D9 Web 持久化：OPFS + SQLDelight-wasm

**决定**：Web 端以 OPFS 承载 SQLDelight 的 wasm 存储；其余端使用平台原生存储。

**理由**：localStorage 容量与事务能力不足；OPFS 提供可持久化的文件级存储。

### D10 缓存加密与登出清理

**决定**：缓存加密落盘，密钥取自平台安全存储（Keystore/Keychain）；登出、认证失效或工作区不可访问时清除缓存与密钥。

**风险**：Web/Wasm 下的静态加密缺少标准方案。详见 Risks。

### D11 余额：服务端继续派生维护，端侧不缓存

**决定**：`ledger_snapshots` 与校准语义保持不变，由服务端在提交时按改动流水增量维护；端侧不缓存余额、不提交余额数字。端侧如需展示余额，用共享规则从缓存事实派生。

**理由**：余额是流水的纯函数；让客户端提交余额数字是信任漏洞。此项记为"后续可砍"的简化候选，不在本次范围，因其牵连 `accounts` 规格与投资侧（投资读模型以 `ledger_snapshots.version` 作新鲜度版本号、投资账单期末余额核对依赖校准）。

### D12 投资：本轮不纳入

**决定**：仅**只读**缓存 `cash_investment_funding_relations` 作为现金导入匹配输入；不做任何投资计算。

**理由**：现金导入的候选召回读取该表，缺数据会使"现金↔投资"匹配降级；只读缓存可在不纳入投资计算的前提下保持现金导入结论不变。

### D13 后端差异与事务边界

**决定**：所有校验、写入、版本推进、收据写入在同一事务内完成。PostgreSQL 以工作区行 `FOR UPDATE` 取得写序列化；SQLite 以 `BEGIN IMMEDIATE` 取得写锁。快照在只读事务内锁定版本。方言差异只存在于 persistence adapter，用户可见结果、精确金额与幂等结果必须等价。

### D14 同步与缓存层置于端口之后

**决定**：端侧的同步与缓存实现置于一个明确端口（接口）之后，本地事实缓存 schema 与具体同步机制解耦；领域层只依赖端口，不感知同步机制。

**理由**：D1 将 PowerSync 记为条件性备选。置于端口之后，将来若切换只需替换端口实现（从"版本 + 全量快照"换成增量同步），不波及领域计算、派生与 UI；同时防止把某一同步机制的假设（如服务端物化投影、增量游标）泄漏进领域层。

**备选**：领域层直接依赖具体同步实现（切换时波及面大，否决）。

## Risks / Trade-offs

- **端侧承担全部启发式计算，低端设备/Web 大账本性能压力** → 以本地索引优化；必要时按"按需拉取旧事实"降级；先在代表视口做真实浏览器冒烟。
- **Web/Wasm 缺少成熟的静态加密方案** → 分别评估 SQLCipher 的 wasm 构建与"值级 WebCrypto 加密"的代价；若都不可行，明确 Web 的降级口径（依赖浏览器存储隔离 + 登出即清），并在验收中如实标注，不得宣称与原生同等。
- **快照体积随账龄增长** → 快照分页/流式；后续如成为瓶颈，按 D1 触发条件补增量。
- **L1 误伤整次导入** → 以重派 + 指纹比对齐静默重提交；加退避与重试上限防 livelock。
- **端侧派生与服务端规则漂移** → 单一共享实现 + 规则版本；规则变更触发整体重派生。
- **删除物化为破坏性且牵连多接口** → 未上线可接受；迁移期 parity 项随本变更标废，需在文档与规格同步中明确。
- **删除后仍被外部消费的投影接口** → 需确认旧 React Web 与 `mobile/` 已不在兼容范围（proposal 已列非目标）。
- **余额能力保留带来的后续简化债** → 记为可砍项，避免遗忘。

## Migration Plan

1. **前置**：`kotlin-backend-shared-core` 完成 parity 并合入；确认 Python 运行时已废弃。
2. **共享层先行**：把查重/配对/投影/余额规则与写集 DTO 下沉 `business-domain`/`business-contracts`，先服务端复用并保持行为不变（此步不改变对外契约）。
3. **服务端**：新增版本、版本查询、全量快照与写集提交（校验 + 落库 + 收据）；随后删除投影物化与投影读取接口（Alembic 迁移删表）。
4. **端侧**：落地本地库、索引、加密、快照替换，再接入端侧导入与派生，最后切换浏览到端侧派生。
5. **验证**：双后端契约矩阵（版本/快照/写集/幂等/降级）、三端编译与单测、Web 真实浏览器冒烟；原生端默认只做相称编译与单测。
6. **文档收尾**：同步 `docs/feature-map.md`、`DOMAIN_GLOSSARY.md`、现金导入流程文档，并标废被覆盖的 parity 项。

**Rollback**：本变更为未上线产品的破坏性重设计。回滚以版本回退迁移后的 parity 基线（恢复服务端投影物化与投影读取接口、恢复服务端导入写路径）为准；端侧缓存可整体丢弃并从快照重建，不产生服务端孤儿数据。回滚不涉及历史数据迁移。

## Open Questions

- **Web 静态加密口径**：SQLCipher-wasm 与值级 WebCrypto 都无法满足时，Web 是否接受"依赖浏览器存储隔离 + 登出即清"的降级？需在实现前给出结论，但不改变规格与任务拆分。
- **快照分页阈值与单页规模**：可由实现期依据真实账本规模标定，不改变合同。
- **自研快照性能基线**：按现实规模（1 万 / 5 万条流水）测量刷新耗时与流量；结果决定是否需要启动 D1 的 PowerSync spike，不改变规格与任务拆分。
- **PowerSync 本地静态加密支持**：仅在启动 D1 spike 时才需核实，不改变当前设计。

现金条目写入仅在 dirty flush、实际新建或删除成员时标记版本，重复 reconcile 和同值 patch 不增加版本；空条目清理由同次成员迁移／删除的标记覆盖。原子流水与组成项创建复用事务标记，任何后续异常连同版本回滚。保存点边界沿用条目仓储的 flush／reload 回调，不从投影版本推断事实变化。

普通导入与有界批量导入在真实父项／组成项写入前标记；重复正式流水沿既有快速路径不写入。草稿合并的实际删除、更新与修订推进共用标记；流水删除在已校验非空目标后、清理引用前标记，整次删除共用事务。人工创建在父项插入前标记，不依赖投影是否可用；分类批量更新只对规范条目变化标记，不把单纯物化投影修正算作事实变更。

组成项金额无变化沿既有后端存储语义判断：SQLite 比较 fixedCashDecimal 文本，保留原有尾零／负零表示更新；PG 比较精确数值。真正无变化才跳过 DML，保留 label、source_key 和 metadata，不改变共享精确值类型对原表示的保留。原来源 writer 黄金中的 touched 仍含旧 Python 全量触达语义，当前 Kotlin 基线已有问题／关系范围优化；本片保留原断言和失败记录，不用它冒充完整黄金通过，旧导入路径退役时核对其测试归属。

关系仓储的 add／replace／候选替换／状态或类型修订在真实 DML 前标记；保留现有显式命令即推进 row_revision 的合同，不把相同候选命令改成无修订。批量状态命令仅触及存在的工作区关系时标记。候选失效仅在实际待失效候选存在时标记。资金对应关系 store 先比原始记录字段，重复扫描无变化不写入；分类更新只对规范 entry／member 变化标记，单纯修正物化投影不推进事实版本。
