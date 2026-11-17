# 任务

变更分级：A（跨模块架构重设计、公共契约变更、数据模型与持久化变更）。前置：`kotlin-backend-shared-core` 已完成 parity 并合入，Python 运行时已废弃。本变更在独立 feature 分支/worktree 中实施。

## 0. 七阶段与结论

- [x] 0.1 阶段 1 思考：阅读 `openspec/specs/` 相关主规格（import、cash-ledger、accounts、shared）、`openspec/project-context.md`、`DOMAIN_GLOSSARY.md` 与迁移变更记录，调用 `$openspec-explore` 固化取舍，结论回写 design.md
  - PowerSync 的后续独立实验按用户既有决定记录：允许 PostgreSQL 单源、实验性 Web／Wasm 和以降低复杂度换取更高资源；本期主线仍自研最小同步，不以旧否决条件阻止改造后的比较。文档一致性独立复核无 finding，`openspec validate offline-first-ledger-import --strict` 与 diff 检查通过；仅规划记录校正，无受影响代码测试。
- [x] 0.2 阶段 2 计划：完成本 change 的 proposal/specs/design；如出现改变范围或语义的新决策，先回写 artifact 再实施
  - 2026-11-05（UTC），比较基线 `77cd387d`：用户已确认现金发生时间按 UTC 统一限定为 `[1900-01-01, 2101-01-01)`，录入／导入／编辑／写集校验共用，投资和系统时间除外。proposal、浏览 delta 的唯一主 requirement、导入 delta 引用及 design 已原位对齐；独立 5a 和实际 5b 契约复核无阻断，`openspec validate offline-first-ledger-import --strict` 与 diff 检查通过。此处仅确认规划；来源超限沿既有 `time_unreadable`、缺时间草稿、偏移跨界、直接写入及原子拒绝仍需实施／验证，不把原 year 1／9999 探针当成新范围通过。本决定保留在现金业务规格，不扩为所有时间或投资的通用规范。
  - 条目身份所有权规划已按既有服务端生成元数据合同修订。比较基线／核对 HEAD：`c506d56c`，2026-11-06（UTC）；独立 5a 和实际独立 5b 均无阻断，strict 及 diff 检查通过。本片只回写 D3、导入 delta 和任务，不改当前 wire 或用旧测试冒称新合同通过；1.2 重新打开，后续按失败回归实施。原身份规则保持，无新增通用产品政策。
  - 文件导入边界已按用户目标、proposal 与真实 build(files) 调用链收敛：不迁入无文件维护。导入 delta、D3 和 3.x 对应范围同组修正；通用 DTO／共享辅助器不作为范围授权，后续公开接线须补越界拒绝测试。身份分配及完整效果规则尚未定案，本片不改代码或宣称 3.2 完成。 比较基线／核对 HEAD：`e4bc7dc1`，2026-11-06 03:25 UTC；独立 5a 和实际独立 5b 均无阻断，复核真实文件来源、固定策略、新建议边及 INSERT／精确 no-op 调用链。strict 与 diff 检查通过；只改规划，不重复代码／双库／浏览器验证，不新增通用产品规则。
- [x] 0.3 阶段 3 任务拆分：核对每个 requirement 与本文件任务、失败测试、审查、验证与文档收尾的对应关系（核对中发现缺口：新增 4.5 删除服务端旧导入写路径）
  - 浏览 delta 已按当前主规格保留完整零金额筛选、草稿、待处理与分页场景，并仅转换端侧读取责任；双后端修改及文件扫描移除使用主规格的真实名称。既有行为未变，源码未变；独立规划复核指出连续翻页场景遗漏规则版本条件，已补齐条件及事实未变／规则更新的游标失效场景；其余新增 diff 无 finding。后续整体 artifact 审计仍须核对其余会话退役要求。
- [x] 0.4 阶段 4 构建：在独立分支按本文件小步实施，测试先失败再转绿
  - 后端物化投影退役、规范条目协调、双库基线与 jOOQ 类型、离线写入限制 UI 切片均已在 `worktree-offline-first-ledger-import` 分支按小步实施；每步先复现失败或明确环境限制再转绿，完整后端 1,505 项双库及 UI 三类改动测试通过，证据见 4.3／4.4／6.7／11.6。
- [x] 0.5 阶段 5 审查：产品/范围、工程、最终 diff 三类审查；DX/安全/设计按风险触发
  - 各实施切片均完成独立 5a 范围／工程／最终 diff 及实际 5b 契约／UI 复核；本轮另由独立上下文复核 `f54a5713^..HEAD` 的最终切片，无 blocker，finding 与处置记录在 11.3。剩余 major 2（客户端远端旧读取方法）已记录为需拆分共享接口的后续独立小步，不影响当前生产读取路径。

查询与原始筛选切片（比较基线 `155d257b`）、永久收据版本切片（比较基线 `6743bb09`）、本地浏览 owner 切片（比较基线 `12f862e9`）、公共字段选择切片（比较基线 `ec99d059`）、关系选择失效核心（比较基线 `71bc96a2`）、候选查询规划（比较基线 `1818da04`）、计算值摘要（比较基线 `f2563ce4`）、关系上下文模型（比较基线 `a7d4c264`）及本地关系上下文读取（比较基线 `5ad85b26`）已完成独立 5a 范围／工程／安全／最终 diff 及实际 5b 契约复核，无遗留 finding。owner 的撤权竞态 P1 已由唯一同步围栏修复，真实线程交错先红再绿并重审关闭。收据新增 P2 已先红再绿并重审关闭：显式 null 与旧字段缺省分开，只在该字段使用受限解码；不修改全局 JSON。两项 P2 均先红再绿：公开 Map 的外部修改由副本隔离，内部固定范围不变；极端金额指数按用户确认的整数 20 位／小数 18 位在展开前拒绝。各切片的实际双库／跨端矩阵与范围见下方证据；三端实际加密工厂及内部单次认证／缓存恢复组合已提供，应用会话、撤权枚举和页面尚未接通，不关闭 7.2 或整个 change。


来源绑定最终增量切片（比较基线 `2b5e5f0d`）已完成独立 5a 范围／工程／安全／最终 diff 及实际 5b 契约复核。旧文档证据自替换／清空与平台作用域两项 P2 已用失败回归修复并重审关闭；JS 通用值容器中的页总数整数种类差异已修复并独立增量复核，无遗留阻断。完整服务边界与实际矩阵见[来源绑定最终增量验证](#来源绑定最终增量验证)。

- [ ] 0.6 阶段 6 测试与 QA：执行双后端契约矩阵、三端编译与单测、Web 真实浏览器冒烟，并运行 `openspec validate --all --strict` 与 `openspec doctor`
- [ ] 0.7 阶段 7 发布：记录交付证据、发布准备与回滚信息（未上线，无流量切换），并按用户实际决策完成规则沉淀反思

## 1. 共享规则下沉（先服务端复用，行为不变）

- [x] 1.1 先写失败测试：共享层对查重、配对、投影、余额规则给出与既有服务端实现等价的确定性结果
  - 独立完成性审计已核原来源／查重及关系算法黄金、投影双库事实派生对照，以及 CashRelationProjectionTest.projectionMatchesPython 的完整 balances／收入／支出断言；当前域层五运行时完整矩阵实际执行相关套件，证据见本页服务端退款共享接线。本项关闭不等于整个应用与旧路径退役完成。
- [x] 1.2 将现金流水/组成项、来源身份、关系、entry、映射、分类等写集 DTO 收敛进 `business-contracts`，补齐本次编辑意图；新公开入口仅允许文件导入核对
  - 条目以完整业务分组及全部 entry_dependencies 表达；旧依赖包含最终退休对象，前置编辑核真实旧归属，严格拒绝旧 winner target／identity／deleted_entries 字段。模型、引用／拓扑、完整旧组门及跨端验证和独立复核已完成；实际 Long／UUID 元数据、完整效果和事务仍归 3.2／3.4。
  - 有序 relation_groups 保存全部新关系选择组的完整边分区及明确 common_fields，与行前置 C、归并后 D 分开。序列化、引用覆盖／顺序、三态 active 边及公共补丁浅归一已先红再绿并完成跨端及独立复核；仅完成传输与意图表达。内部联合批次及生效 B 一致性／可信人工字段覆盖已提供；端侧原始选择重算、完整三阶段效果、幂等消费及真实提交仍待后续，不把 DTO 完成当服务接通。
  - `CashSourceCoverage`、`RawCashRow`／`CashStatementDocument`、资金 Group／Part／Row／Scan／Resolution 与 PDF 归一文档线路模型已共享；原 null、原文、位置、计数、默认值及显式平台编码保持。来源候选与操作 expected_revision 保留完整 Long。最终写集 DTO 已覆盖交易／组成项、来源绑定／空页、关系／候选、问题／候选、条目／成员及映射；已有／新建引用显式区分，分类引用已有目录 ID。序列化不等于合法性校验或服务接线，审查及验证见本页写集传输证据。
- [x] 1.3 将查重与来源身份、关系配对管线、投影派生、余额规则下沉 `business-domain`
  - 来源范围／身份、覆盖证明、字段策略、Resolver／Rows／Batch、资金扫描／分配、操作规划、候选读取、预览图／操作叠加、关系发现 Phase A–D、建议与选择、公共字段及完整呈现均已由共享生产规则组合。`calculateLocalCashImportBusinessPlan` 从同一完整快照和实际解析文件构造来源、操作、预计事实、真实发现、选择及最终写集；不注入预设提案。`buildLocalCashProjections` 使用完整事实和共享图／投影规则。按 design 的既定边界，共享余额派生是 `CashProjectionRules`／`CashRelationProjection`，服务端 `CashSnapshotRepository`／`CashBalanceService` 的存储维护保留；Graph、页面、队列和旧路径退役由后续任务承接。
  - 原商户表达式参考保留完整 21,309 对向量及 UTF-16BE 摘要；私有边界模型、共享精确文本与扫描索引、生产金额／纳秒／平台日期、完整管线和文件计划证据见[商户文本](#本地商户文本与扫描索引1363)、[完整关系规划](#本地完整关系规划配置1363)及[文件计划](#本地完整文件业务计划接线1364)。有限向量、差分和确定性证据不单独等同任意字符串的形式证明。
  - 完成性经独立范围复核与主流程核对：`production-pipeline` 六份、`full-planning` 七份源码摘要均与当前文件相等；`plan-reads` 固定域层 JVM／JS／Wasm／iOS Simulator 887／839／839／839 方法均通过，从其提交 `5cc7e29e` 至当前相关 domain／contracts／backend 无变更。当前共享交付／可空计划模型 QA 另计，不把其未通过的 Web 验证当作算法完成证据。核对清单见仓库外 `offline-ledger-1116/success-delivery/algorithm-task-audit.json`，具体构建命令及原固定 XML 保留在各证据目录。

- [x] 1.4 服务端改为复用共享实现，保持既有对外行为不变；跑通现有后端测试作为基线
  - `CashRefundMatch` 的生产配置已与端侧完整发现共同调用 normalizeCashMerchant，原 Java Unicode 正则／ROOT 小写仅保留独立测试参考。当前完整后端、共享五运行时及双库版本／快照均已通过，独立源码／证据终审无 finding；旧路径退役仍由 4.x 承接。
  - 已关闭来源触达基线 finding：saveOccurrence 返回真实 insert/update 结果，writer 累计全部来源更新后加入 touched，恢复原 normal 等全部 15 个 writer case 的固定期望；不改既有 ledger_seq 标记。新增无问题项的真实 duplicate 提交证明补 provider 绑定触发投影维护，独立新会话同值重导不维护；多来源全部执行、coverage 更新及同值 no-op 亦已回归。双库证据与独立审查见下方来源触达小节；该共享规则接线已完成，完整变更的剩余工作由应用及退役任务承接。
  - 原生完整操作基线已对齐当前原导出器：固定支付宝交易范围、条目身份默认取根流水、无法读取金额以 null 保真。当前完整 oracle 从未修改的原导出器整包生成，未手改期望；SQLite 全部 36 原操作用例加完整 golden 通过，PG 完整 golden 通过。完整逐流水决定已迁至 `CashImportOperationRules`，backend 只保留原 SQL／标量桥；本地 `prepareLocalCashImportOperations` 已从同一快照组合来源准备、账户选择、字段策略及完整操作决定，已接生产本地计划、Graph 及生命周期；更广应用回归由 6.4／10 承接。
- [x] 1.5 为共享规则引入显式规则版本，并让规则版本随快照/派生结果可观察
  - 已完成：`business-domain` 新增 `CASH_RULES_VERSION`（`cash-rules-v1`）并有稳定性测试
  - 已完成：快照必填 `rules_version` 返回共享 `CASH_RULES_VERSION`，轻量版本查询仍只返回事实版本
  - 已完成：快照与派生模型携带规则版本，owner 按事实／规则捕获并在新运行时重建；游标绑定当前共享及投影规则，升级拒绝旧游标且不推进账本版本。当前五运行时实际执行 CashRulesVersionTest、CashProjectionModelsTest、CashLedgerCursorTest，真实双库版本／快照各 22 项另跑全绿，独立完成性审计通过；本项关闭。
- [x] 1.6 前置（1.3 的硬阻塞）：为共享层提供多平台原语——SHA-256 摘要、码点计数与迭代、Python 浮点文本、`LedgerDecimal` 十进制表示；先写失败测试，再让 backend 复用同一实现
  - 已完成：多平台 SHA-256（含标准测试向量）、`pythonCodePoints`/`pythonCodePointCount`/`pythonStringOrder`、`pythonWhitespace` 已落 `business-domain`，失败测试转绿（`:business-domain:jvmTest` 4/4）
  - 已完成：backend 复用——`tokenDigest` 走共享 `sha256Hex`（保留 `strictUtf8`），`pythonStringOrder` 委托共享实现；`SourceDigestParityTest` 1/1、`CashSourceIdentityGoldenTest` 4/4 通过
  - 已完成：`sourceJson` 稳定 JSON 结构下沉共享（精确十进制与浮点文本**由调用方注入**，后端注入 `BigDecimal`/`pythonFloatText` 以保持既有 digest 口径）；等价性 oracle 共 19 个测试通过（`SourceDigestParityTest`、`CashSourceIdentityGoldenTest`、`CashSourceResolutionGoldenTest`、`CashSourceMatchGoldenTest`、`CashSourceScopeOriginalTest`、`CashSourceBatchGoldenTest`、`CashSourceRowsGoldenTest`）
  - 已完成：`pythonFloatText` 以 IEEE 754 位模式与精确中点区间生成跨平台最短文本，backend 委托共享实现；私有有界整数算术不改变 `LedgerDecimal`。固定 CPython 位模式向量、全部 2,046 个正规二进制指数及两侧相邻值（含负数）、2,000 个固定随机位模式与十进制计数边界，对照迁移前 JVM 实现。
  - 已完成：`parsePythonCashTimestamp` 使用共享 `kotlin.time.Instant` 与纯日历运算，保留 ISO 周日期消歧、显式偏移／naive 策略、微秒截断、Unicode 分隔符及范围错误。backend 仅适配 JVM 时间类型与既有错误；共享入口在替换前直接通过现有 20,053 条 Python 时间样本与旧实现对照。
  - 已完成：Python Unicode 14 大小写表与上下文 Sigma 逻辑共享；native 只保留单码点查询桥及共享 lower 委托，原表逐字等价。12,625 条 CPython 向量与纯规则／双库服务回归保护同一口径；不改变商户匹配器 JVM 大小写合同。
  - 已完成：来源时间归一及上海／香港完整历史切换共享，98 次历史转换私有固化，4,454 条独立 JVM atZone 向量和原完整 helper 差分通过；来源时区不依赖端侧本机时区或固定 UTC+8。
  - 已完成：来源昵称 NFC 复用私有 JVM 基线数据与共享规范排序／组合，完整 Unicode／Hangul 与非法 UTF-16 对照通过，不做兼容归一；冻结数据变化沿用规则版本评估。
  - 已完成：来源账户 Java Unicode word／68 个 Nd 数字区块及金额边界由共享词法实现，8,604 条原模式向量与全部码点差分通过；与 Python 14 数字表保留独立口径。
  - 剩余来源实体下沉归任务 1.3：来源键、文档模型与 Resolver／Batch／Rows 已共享，backend 保留 JVM 原始值桥接；完整归一、关系和投影的决定已共享，但原始值生产配置、读取器与端侧接线仍未完成，不能宣称完整端侧导入已可用。

## 2. 服务端：工作区版本与全量快照

- [x] 2.1 先写失败测试：版本单调递增、提交失败不推进、双后端等价
- [x] 2.2 为每个工作区维护单调递增账本版本，与规范事实写入同事务推进（PostgreSQL 工作区行 `FOR UPDATE`；SQLite `BEGIN IMMEDIATE`）
  - 已实现基础：Kotlin 双库基线 `20261104_54` 定义非负 64 位 `workspaces.ledger_seq`，jOOQ 由真实 PG 18 生成。连接内标记按物理事务／工作区合并，保存点准确恢复标记状态；计数器双库回归覆盖并发、保存点交错、回滚与溢出。下述各写入口已接入并完成独立写面审计；基础测试与真实入口回归分别核对。
  - 已接入目录写入口：账户命令、分类目录修订、别名写入、账单账户映射发布；对应原服务和 HTTP 回归在双库通过。来源绑定、问题及候选、认证空页证据亦已接线并通过双库回归；原子流水创建与条目／成员仓储亦已接线并通过双库回归；普通／批量导入、草稿合并、人工创建与删除亦已接线；当前／旧关系、候选失效及类型解除、分类直写、资金对应关系与旧逻辑删除亦已接线；手工转账关系独立仓储亦已显式标记；全量 DML／批量 store 与辅助仓储所有者完成审计。分类删除由同事务 CategoryService.bump 覆盖，删除引用清理由 CashDeletionService 标记，投影调整成员委托 CashEntryRepository。工作区整体删除先撤销会话，已删除空间不再提供版本或快照；新建空工作区初始版本为 0。余额、投资事件、游标与物化投影写入不属于快照事实。
- [x] 2.3 先写失败测试：版本查询只返回版本、隔离与权限
- [x] 2.4 提供轻量版本查询接口
  - 缓存传输的两个 GET 已支持可选 `workspace_id` 前置条件：与当前鉴权选择不符时 403，不按参数切换或授予读取权限；旧无参语义不变。共享客户端 `fetchLedgerVersion`／`fetchLedgerSnapshot` 始终携带非空预期工作区，快照回包再次核对工作区。缓存存储／发布及会话生命周期仍待接入。
- [x] 2.5 先写失败测试：快照在只读事务锁定版本 `V`、分页不跨版本、隔离与权限
- [x] 2.6 提供全量事实快照接口，覆盖端侧缓存所需全部规范事实（含只读 `cash_investment_funding_relations`）
- [x] 2.7 双后端契约矩阵：版本、快照事实、精确金额与时间值等价（SQLite + 真实 PostgreSQL `_test` 库，显式 `FT_TEST_POSTGRES_URL`）
  - 同一固定合成 fixture 的完整 HTTP 响应已在双库逐字段对照。`LedgerSnapshotParityTest` 与版本／写入口／快照／OpenAPI 矩阵各 11 类 71 方法通过、0 skip；2026-11-04 17:41 UTC，验证基线 `f891b743`。PG 显式目标 `ft_offline_ledger_1104_test`（127.0.0.1:5432），配置目录 `sqlite`／`postgres-5432`；原始日志 `snapshot-identical-{sqlite,postgres}.log` 在仓库外。fixture 输入和期望独立，15 集合、修订、金额、时间与 JSON 不做输出归一化。


- [x] 2.10 先写失败测试：已接受且生效的资金关系返回精确最小投资端字段，拒绝／失效／未关联／外工作区事件不泄漏，关联状态变化触发版本且事务回滚保持旧快照
- [x] 2.11 实现最小只读关联资金事件快照、同步 DTO／OpenAPI／本地完整快照持久化，并审计实际事件写入与账户／关系版本联动；不纳入投资计算与完整投资账本

## 3. 服务端：写集提交

新入口限于文件导入及核对。无文件的来源事项、身份草稿合并、删除及主动拆分仍由后端既有命令承担；共享辅助器与通用 DTO 不构成开放这些动作的依据。原文件导入只更新 live import／legacy，已有正式金融字段及类型固定，不删除父项或主动拆分旧确认组；服务从可信事实验证这些边界及完整归并效果。

- [x] 3.1 先写失败测试：结构/不变量校验、非法关系集拒绝、金额精度拒绝、工作区隔离
- [x] 3.2 实现写集校验（权限、账户资格、修订号、金额与币种精度、现金 UTC 1900..2100 发生时间范围、引用完整性、关系图合法性），不重跑启发式
- [x] 3.3 先写失败测试：单事务全成功/全失败、版本推进、收据写入
- [x] 3.4 实现写集落库：现金流水与组成项、关系与候选、entry 与成员、待处理问题、账单账户映射与来源绑定
- [x] 3.5 先写失败测试：数据层幂等（来源身份去重、关系指纹去重）、提交层幂等（同键返回首结果）、结果未知可按原键核实
- [x] 3.6 实现两层幂等与收据/核实接口
- [x] 3.7 先写失败测试：基线过期整批拒绝并返回可识别错误；提交与校验同事务（CAS/`FOR UPDATE`）
- [x] 3.8 实现基线校验与拒绝
- [x] 3.9 双后端契约矩阵：写集落库事实、幂等结果与精确金额等价

生产事务命令已组合真实工作区鉴权锁、原键收据重放、规则及基线守卫、来源身份解析、完整引用／金融值／图校验、来源与组成项／绑定／元数据／最终映射、前置 C、关系组 B、条目协调及最终 D、实际余额与永久完整身份结果。来源精确重导不重复 DML 或余额，但仍执行独立新增 alias／映射／问题及公共效果；关系整批精确命中连同 B 消费，部分或异值拒绝。旧关系问题省略即保留，显式 Existing 仅接受整项同值，不新建、改写或退役。账户映射只传最终增量，端侧沿 used_account_selections 保留原默认保存行为；不恢复组／行／原分配证明。

公共字段按五字段白名单、真实旧 owner／条目观察、presence、人工字段及原联合批次顺序验证，客户端最终成员值不反授权 C／B。条目声明覆盖完整受影响业务分组和全部旧修订读集，服务核协调／D 后完整结果，实际 ID、最终修订及真正消失的旧对象退休集合由事务产生；不扩范围修复无关未物化事实。候选并集沿原 writer 保存，校验原依赖、valid 单对资格及实际确认图，不要求每个笛卡尔组合合法；完整 choices／单选／builder 仍属端侧 6.3–6.6，不能作为新增服务端枚举门。

公开 `POST /api/v1/cash-import/write-set` 已接入同一事务服务，独立于旧暂存服务，原键／ID 核实复用永久收据读取；同键重放先于规则与基线。HTTP 的严格 UTF-8／JSON、64 MiB／128 层、数字词法、错误状态、权限与并发已通过独立 5a／5b 及完整后端 QA。既有内部证据见下方来源、关系、条目、身份结果各节，新增公开证据见“公开写集提交 HTTP”。逐项实际实现与双库证据已由独立上下文核证，3.1–3.9 关闭；客户端完整写集构建、恢复、页面及旧路径退役仍按原任务完成。

## 4. 服务端：移除投影物化

- [x] 4.1 先写失败测试：端点删除后旧读取面返回可识别错误/404，写集提交与快照不受影响
- [x] 4.2 删除 `GET /cash-projections`、投影详情与证据、`operations/projections(+rebuild)` 等读取面
- [x] 4.3 更新双库 Kotlin 基线 SQL 和显式升级说明，删除 `cash_projection_*` 表；`validateSchema()` 必需表集合同步更新
  - 当前实施：永久逐项收据和单笔详情的当前定位迁到规范成员及真实账本版本，保原永久结果、整组待处理数与权限边界；先补真实双库零物化 SQL、分页、非代表成员、删除／缺失、大版本、未知 kind 及只读回归，并对照本地快照在成员缺失时整体失败。表结构、其他写入依赖及 4.4 不随本片关闭；证据见[永久收据与单笔详情定位](#永久收据与单笔详情定位43)。
  - 双库基线已删除 5 张 `cash_projection_*` 表（含索引、序列、约束），`Database.requiredTables`、PostgreSQL bootstrap 表数（28／27）及 `StorageContractTest` 同步更新；用已迁移的 PostgreSQL 测试库重新生成 jOOQ 类型并删除旧表生成文件。完整双库回归 1,505 次执行零失败。升级说明：新基线不再创建物化表，既有库保留空表不影响运行，可在维护窗口删除，无需数据迁移或版本号变更；历史迁移夹具仍引用旧表，需在补跑前另行清理。
  - 测试夹具中的物化表插入／删除／状态断言已清理（分类、关系、维护、导入写入、版本合同）；双库基线表、`validateSchema` 与 jOOQ 生成类型仍待删除。
  - 物化投影实现已整体删除：`CashProjectionService`、`CashProjectionRepository`、`ProjectionStateRepository`、`CurrentProjectionReadService`、`CashProjectionListService`、`CashEvidenceReadRepository` 及旧读取 parity 测试；`CashProjectionSourceRepository` 只保留规范维护与导入摘要共用的来源读取。`LocalCashEntryReadContractTest` 保留 3 个直接规范断言，`LedgerFundingSnapshotContractTest` 移除物化视图对照。
  - 无文件维护提交已删除 `canonicalEntries` 开关与物化分支，默认路径即规范协调；死代码 `saveEntryChoices` 及旧投影读取服务注入退役。物化读取路由的 parity 测试（投影详情／证据／列表／原始查询共七类）同步退役，`LedgerFundingSnapshotContractTest` 的本地视图对照仍保留。
  - 普通草稿保存已改为规范条目协调并以真实 `ledger_seq` 作为收据版本；分类移动及删除引用清理不再维护物化行，新增双库草稿保存合同覆盖一次版本推进、条目保留、收据重放与全库不变。
  - 工作区创建／删除、账户引用检查及分类删除已移除物化依赖：不再初始化空数据集、不再把物化表列入删除顺序或账户外键、分类清除只更新规范条目／成员／流水；对应历史迁移测试的物化断言已退役，当前分类删除合同与性能入口保持。
  - 单笔删除已改为规范条目协调；物化 `targets`／`preview`／`deleteProjections` 及 `CashDeletionContractTest` 退役，`delete_current_dissolve`／`delete_all`、关系清理、失败回滚、缺失／非法模式与 viewer 拒绝由 `CashEntryDeletionContractTest` 双库覆盖。
  - 关系确认／驳回／取代／逻辑删除及资金调拨确认已改为只协调规范条目（`maintainCanonicalCashEntries`），物化 `maintainIfReadyInSession` 调用从两服务移除；无物化状态下的关系验收改为始终执行规范校验。
  - 分类设置命令已只保留规范条目路径（`setCurrentCategory` 解析 `cash-entry:` 后调用 `setEntryCategory`），物化 `setCategory`／`select` 及 `CashClassificationMigrationTest` 退役；当前 `CashEntryClassificationContractTest` 与 `LocalCashEntryReadContractTest` 双库覆盖分类、版本冲突、权限、回滚及本地证据。
  - 手工记账、现金／投资转账、旧现金新增与余额校准已复用规范条目协调，保留原事务、精确余额、公共字段、响应与一次账本版本推进；本片验证及退役边界见[创建命令规范协调](#创建命令规范协调43)。表结构、其余在线引用及 4.4 仍未完成。
  - `GET /reports/finance` 综合报表已按用户明确决定整体移除，不保留服务端现金汇总例外；专用汇总与旧响应等价测试同步退役，账户余额及独立投资事件／持仓／估值保持。当前双库证据见本文件“旧综合财务报表退役”；其余物化依赖仍待清理。
- [x] 4.4 标废并清理迁移变更中被本变更覆盖的 parity 项与测试映射
  - `compose-cash-import-live.e2e.ts` 已迁到实际本地准备、完整计算、写集及结果页面，四份实际格式、206 条事实的真实浏览器回归通过；不新增跳过，也不将此定向用例或三条 CSV 流程当作全套 Web 通过。其余旧 parity 项及测试归属继续清理。
  - 原 `CashProjectionGraphReadMigrationTest` 整类在本片执行 11 方法，3 个旧物化结果的 `cash:<root>` 查询断言仍为 null（equal allocations、local split、local closure），其余通过；未改旧断言或计全类通过。新本地快照测试直接比较当前 native 完整 source build，独立于旧物化行标识；旧物化退役时按接口／模型实际归属处理。
  - 物化状态／重建路由的 parity 测试已随 4.2 路由退役删除：`ProjectionStateMigrationTest`、`ProjectionRebuildMigrationTest`、`ProjectionRebuildHttpContractTest`、`CashEvidenceHttpContractTest` 共四类 318 行，仅断言已删除的 `/operations/projections` 与证据路由；`ProjectionStatusService`／`ProjectionRebuildService` 及 FinanceModule 注入同步删除，`LegacyCashflowMigrationTest` 的物化投影断言方法退役（当前 add／校准无物化状态由 `LegacyCashflowWriteTest` 覆盖）。删除后 `:backend:test`、`ktlintCheck`、`detektMain`、`detektTest` 通过，1,523 次执行零失败；历史类因外部夹具不可用仍不在此环境执行，未计为通过。
  - 核对旧 fixture 归属：原 Alipay 7CSV 回放首个文件缺交易订单号，违反当前 statement-import 的缺列拒绝规格，在 `597a800c` 原实现亦 `statement_header_not_found`；未计通过，不篡改原夹具或放宽读取器。外部 `platform-alipay-baseline.log` 留有验证证据。

  - 旧 `sourcePlanReadsAndViewMatchPythonContracts` 已保留完整新 oracle 比较；其旧 `suggestion.account` 字段缺失在 `fb038773` 原 planner 下亦失败。聚焦 source 新方法不替代这项完整旧合同。保留旧接口时必须按正式账户快照合同覆盖有／无建议分支并修复；实际退役时只随接口／模型明确退役清理该测试，不为通过而删字段、固定 `null` 或忽略整用例。
  - 剩余 30 类历史迁移测试仍以字符串或夹具引用已删除的 `cash_projection_*` 表，均带 `HistoricalMigrationEvidence`、不在 `:backend:test` 运行，且外部夹具在本环境不可用；已在本任务标废，不计为通过。补跑 `migrationEvidenceTest` 前须按新 schema 清理这些引用或删除对应方法：`CashClassificationPerformanceTest`、`CashCurrencyEncodingBoundaryTest`、`CashDraftMergeOriginalTest`、`CashEntryRepositoryMigrationTest`、`CashMoneyEncodingBoundaryTest`、`CashSnapshotFailureBoundaryTest`、`CashTransferAuthorizationBoundaryTest`、`CashTransferHttpContractTest`、`CashTransferWriteTest`、`CategoryDeletionMigrationTest`、`CategoryDeletionPerformanceTest`、`CurrentProjectionReadMigrationTest`、`FundingRelationHttpContractTest`、`InvestmentWriteAuthorizationBoundaryTest`、`InvestmentWriteTest`、`LedgerOptionsMigrationTest`、`LegacyCashflowAuthorizationBoundaryTest`、`LegacyCashflowHttpContractTest`、`LegacyCashflowWriteTest`、`ManualCashAuthorizationBoundaryTest`、`ManualCashHttpContractTest`、`ManualCashWriteMigrationTest`、`MutationPlanCommitHttpContractTest`、`MutationPlanFinalizeCommitHttpContractTest`、`MutationPlanResolveCommitHttpContractTest`、`MutationPlanSaveDraftCommitHttpContractTest`、`ReceiptHttpContractTest`、`WorkspaceContractTest`、`WorkspaceFinancialDeletionTest`、`WorkspaceWriteBoundaryTest`；`CategoryDeletionFixtures.deletionProjection` 仅服务这些历史用例。`CashProjectionDatasetContractTest` 已收窄为只快照规范表，供仍保留的历史用例复用。

- [x] 4.5 删除服务端旧导入写路径（导入会话/预览计划/确认提交端点及其实现与暂存清理），保留 8.2／8.4 的 PDF／旧 XLS 无状态 parse-only；`import/cash-import-session` 的 REMOVED 需求随之生效
  - 旧十一方法及导入会话／预览／确认、上传编排／协调器／批量写入和文件空页发现写入实现均已退役；真实 HTTP 回归核旧路由不可用、正式写集／规范快照及原永久收据仍可用。Main 的通用暂存仅创建维护 mutation／空 files，Graph 使用 LocalCashImportLifecycleRepository；维护 CAS／TTL／清扫和无状态 PDF／旧 XLS 保留。当前双库、完整后端及安装方法证据见“旧文件空页发现写入入口退役”；只读 native oracle 与旧 parity 归属继续按 4.4 完成。

## 5. 端侧：本地缓存与安全

- [x] 5.1 先写失败测试：缓存事实范围、工作区隔离、版本替换的原子性
  - 完整事实、范围复合键、精确版本／标识／金额和 JSON 数字词法、关闭重开及真实 SQL 失败保旧已有失败先行与生产组合证据。当前 `result-locations-platforms` 的 SQL 快照 6 项、范围 SQL 21 项及同步 17 项均通过；三端加密工厂、原身份 owner 与生产 Graph 已组合，不以单个存储端口冒称应用行为。
- [x] 5.2 落地本地库 schema 与索引（账户、流水与组成项、来源绑定与证据、关系与候选、问题、entry 与成员、分类、映射、别名、funding 关系）
  - `SqlLedgerSnapshotStore` 使用 SQLDelight runtime 2.4.1，完整 DTO 为单个 TEXT，按服务地址／用户／工作区复合键由单条 UPSERT 原子发布事实与版本。生产来源解析、绑定／别名、按时刻候选、Phase A→D 与账本查询使用同快照的本地索引；账本与当前结果定位复用派生 capture。既定合同不要求拆成 15 张物理表或持久化所有派生索引。完整事实与共享索引实际测试已通过，任意规模的性能保证不在此处推断；整条导入性能仍由最终 QA 核验。
- [x] 5.3 先写失败测试：加密落盘、密钥取自平台安全存储、登出/失效清理
  - Android 实际 Keystore／SQLCipher、Apple 实际 Keychain／SQLCipher，以及 Web 持久包装密钥／加密 OPFS 的失败回归与系统运行证据见本页各平台工厂和密钥仓储段；原平台资源未在本片修改。当前共享／Web 矩阵继续覆盖错钥／缺钥拒绝、范围隔离、取消、擦除失败围栏、真实撤权与原令牌退出，不将 JVM 假密钥库冒称平台安全存储。
- [x] 5.4 实现缓存加密与登出/失效清理
  - 三端加密库、范围目录／租约及密钥生命周期已接生产 Graph、登录／工作区发表、离线身份恢复、联网核验及登出／撤权清理。恢复记录与事实共库加密，绑定原令牌摘要及完整身份；失败保旧、清理围栏、精确原资源释放与后来登录不受误清均已有当前回归。Web 使用已确认的自动解锁，不新增缓存密码，不保证抵御完整资料复制或同源脚本。
  - `browser-cold-start-current` 已补直接应用证据：当前生产构建的真实 Chrome 157、1440×1100、`http://127.0.0.1:18391/` 上，普通流及冲突流均关闭测试浏览器并确认其断连，以同一独立 profile 启动新浏览器；屏蔽 API 后按原身份读取实际账本条目，核对永久收据中的真实条目 ID、合成商户和 -20 CNY，再读取原导入结果，最后恢复联网并收到原用户／工作区的真实核验 200。两项分别 24.5s／26.3s，总计 57s，0 失败／跳过／重试；每条流程的冷启动离线阶段只有两次预期登录核验不可达，没有联网读取事实或新增 POST，两份 trace／流程及截图各自保存，当前生产构建 `stale=false`。
  - 首轮 `browser-cold-start` 在应用打开前因自动／手动录制冲突失败；`browser-cold-start-tracing` 已显示真实冷启动账本，但断言误要求合并语义中的独立商户节点，未记整轮通过。只修测试的 trace 管理及完整行定位，账本断言反而补核真实条目身份，保留全部失败证据，不改应用实现。默认原生 QA 仍为相称编译／单测，不追加设备交互门，也不把 Web 冷启动称为原生设备运行。
- [x] 5.5 先写失败测试：版本未变不重下；版本变化原子替换，不混入旧版本
  - 刷新端口层已覆盖未变化不下载、完整替换、失败保旧、并发刷新、版本回退、旧登录迟到响应及清理失败重试；内部 SQL 快照的真实落盘／重开与失败保旧已验证。Web 真实加密工厂已组合 Ktor adapter、版本同步与本地浏览验证，HTTP 使用合成 MockEngine；应用登录／页面及原生同步组合仍待接线。
- [x] 5.6 实现刷新流程：版本查询 → 全量快照 → 原子替换并记录已同步版本
  - `LedgerSnapshotSync` 与原凭据 Ktor source、三端缓存工厂、原会话 owner 及生产 Graph 已组合。登录发表、现金／账户／分类写入与成功收据恢复按版本读取完整快照并原子替换；累计收据版本下限，失败保留提交成功并封闭陈旧事实，原代次成功后才开放。17 项同步回归与当前原会话／刷新边界矩阵通过，真实导入保存及冲突恢复均核到后续快照。在线始终本地浏览归 7.2／7.3，不作为本项刷新接线的未完成理由。

## 6. 端侧：导入阶段 1–7 与写集提交

- [x] 6.1 先写失败测试：CSV/XLSX 解析 + 归一化/类型分类与既有结果等价
  - 平台 CSV／银行 TSV 原文本与文档归一、UTF-8／GB18030 字节解码、工银亚洲 UTF-16 TSV 与值型 XLSX 均有本地入口。原 10 个文本、11 平台／14 银行／6 工行归一案例及全空间编码对照沿既有证据保留；本片以真实原 8 份 XLSX 完整原始文档／归一及准确失败码对照保护读取结果。旧 CCB XLS 无状态服务端原始文档与端侧归一已在 8.3 的独立双库矩阵验证。
  - 新读取器连接 kmp-zip 2.1.0、xmlutil 0.91.3、OPC、样式／共享字符串／活动工作表及原数字／日期规则；原 14 成功／14 失败编码矩阵、真实日期／数值、字段 namespace／结构、错误值及空值回归通过。按用户决定不支持 XLSX 公式：所选表任意公式整份拒绝，普通等号文本保留，不用缓存或服务端回退。各端真实字节回放与 JVM／Chrome 规模探针均通过，具体范围见本页证据；不据此声称完整文件调度或页面已接通。未覆盖的编码／OOXML 特性和资源上限见 design。
- [x] 6.2 先写失败测试：对缓存账本查重、账户归属、操作计划的结果与既有服务端实现等价
  - `prepareLocalCashImportSources` 从同一完整快照读取来源候选、绑定、组成项及认证空页；`prepareLocalCashImportOperations` 读取真实账户／目标／问题／分类／条目依赖，组合原字段策略和完整操作决定。原扫描 64、资金解析 57、来源批次 49 案例及来源匹配原用例有实际生产配置回放；`business-plan` 固定当前 schema 的 SQLite／PostgreSQL 完整计划对照覆盖新建、选择、草稿、存量候选及 entry 依赖，并核只读后全库不变，见[文件计划证据](#本地完整文件业务计划接线1364)。
  - 文件计划随后与同次来源准备的写集构造组合，该两份域源码由 `owner-calculation` 对应摘要覆盖；`plan-reads` 四运行时完整矩阵实际再次覆盖来源／资金／操作和完整计划，相关域依赖从其已提交版本至当前未变。旧 session 视图 `suggestion.account` 的历史夹具缺口按 4.1 保留／退役处理，不恢复已退役来源身份规则；没有把默认矩阵排除的历史 416 场景计为本轮通过。页面和实际批次调度归 6.4／10，不作为本算法测试项的重复未完成理由。
- [x] 6.3 先写失败测试：候选召回与关系配对结果与既有实现等价，且相同输入确定性
  - 真实同快照候选、预计事实发现、完整 Phase A–D／图校验／摘要和文件计划已接生产配置；原 pipeline、退款、43 次完整镜像调用、JVM 240 种固定 seed／四类链上下文差分，以及真实退款发现／接受／后置编辑／review 重算链均有通过证据，见[生产管线](#本地生产关系管线组合1363)、[完整关系规划](#本地完整关系规划配置1363)和[文件计划](#本地完整文件业务计划接线1364)。本项不是仅测试注入提案的组装器。
  - 完成性由独立上下文复核，并核生产管线六份／完整规划七份当前源码摘要及 `plan-reads` 四运行时固定非空 XML；证据时效与限制见 1.3。相同输入／显式稳定记录身份的确定性已有回归，不将有限黄金及差分语料宣称为全输入形式证明；Graph 与页面已接生产本地生命周期，离线写入限制另行完成。
- [ ] 6.4 实现端侧导入阶段 1–7
  - 共享规则已组合来源准备、账户／资金选择、逐流水操作、真实候选读取与关系发现、预计事实、公共字段与条目协调，以及完整计划／写集。原 SQL／生产预览黄金、双库及跨端计算验证保留在对应实施小节；本地 owner 与生命周期现已接生产 Graph，真实 CSV 已走通断网准备／核对、联网保存、确认前重开与成功结果离线重开。完整关系／多文件／格式和故障的应用回归、完整逐项结果页面仍待完成，不能以单行 CSV 主链宣称本项全完成。
  - 普通现金、含现金端的转账及现金字段编辑已在本地日期转换为 UTC 后复用同一发生时间门，保留 invalid_datetime 反馈、空时间草稿和未编辑字段资格；纯投资转账继续原时间转换。Android host 失败回归已转绿；跨端编译、实际表单 Web 冒烟及界面验证仍未完成，不据此宣称全部前端验收通过。
- [x] 6.5 先写失败测试：写集构造完整、稳定输入可重派、决策按稳定引用复用、指纹一致静默重提交、不一致提示重核对
  - 完成性由独立上下文及主流程按五分项核证。完整 builder 与 review／关系／条目重绑定、完整结论指纹已由生产计算与原会话恢复组合；同结论先原子保存新尝试再提交，差异不发新 POST，保留文件与选择并退休旧计划。`plan-reads` 四运行时 11 个相关域套件各 99 方法的 44 份固定 XML 共 396 方法实例已逐项核摘要、用例及结果；domain／contracts 自其提交 `5cc7e29e` 后无差异。最新 shared Recovery 37、SQL 17 及 JS／Wasm 各 11 恢复方法实跑；完整 builder 双库四个关键方法及当前测试源码摘要亦核对。精确清单与主流程核验见 `offline-ledger-1116/receipt-command/task-6.5-{audit,primary-verification}.json`；早期已变化源码的整包 manifest 不作为当前通过证据。此处仅关闭既定测试项，Graph、结果统计和页面重核对均已由对应项接线；离线写入限制继续由 6.7 承接。
- [ ] 6.6 实现写集提交与冲突重派
  - 提交后缓存接线须将已确认收据版本作为刷新下限；先回归旧版本查询不能复用旧事实、过旧快照不能发布、失败保留原缓存及成功后真实浏览换版。不以收据版本直接赋本地水位；共享客户端已提供固定原凭据／工作区的提交与 ID／原键 HTTP 核实，正文在挂起前精确冻结；该传输不清理原操作恢复材料；流水／组成项／C 写集片段已从原操作与同一快照构造，保留精确引用、旧修订及隐藏字段，完整写集及同次完整计划计算已组合；内部原会话 owner 的提交／核实、显式生产重派及按完整缓存清理已接生产 Graph／本地生命周期。原批次与永久结果恢复已有真实页面证据；结论变化后的同次解除提交围栏及重新核对已通过真实冲突分支验证，离线写入限制归 6.7。
- [ ] 6.7 禁止离线提交业务写入：界面及应用提交边界同时守卫，保留离线准备／核对；不实现离线确认队列、联网自动发送或待提交账本叠加
  - 用户已明确“断网不允许提交写入操作”，proposal、离线缓存主归属 delta、导入引用与 D5 同组对齐；已在线发出的结果未知操作仍按原键核实，不删除其恢复材料。已知离线拒绝不得冻结新提交，恢复在线必须核验原身份／工作区；本项实现与 Web／Native 验证尚未完成。

## 7. 端侧：派生投影与浏览切换

- [x] 7.1 先写失败测试：投影归属、内部转账不计收支、退款冲抵、余额校准不显示、双后端事实等价
  - 独立完成性复核已核原投影黄金、拓扑、退款／转账、隐藏余额校准及双库快照派生对照；record-read-final 的 JVM／JS／Wasm 固定 XML 与 local-ledger-query-final 的 SQLite／PostgreSQL CashProjectionSourceMigrationTest 各 25 项证据覆盖本项。原场景不因应用接线单独重复计数，7.2／7.3 另验真实页面。
- [x] 7.2 实现端侧投影/筛选/分页/月度汇总派生，并接入账本浏览
  - LocalCashLedgerQuery 在同一完整快照组合规范条目、完整视图、全局目录选项、规范筛选、稳定游标／序号分页、完整范围总数／待处理数和月度汇总。现金账户按代表及全部现金组成项过滤；银证投资端不混入现金条件。未知日期／金额保持 null，零额展示、内部转账、退款与余额校准沿既定规则。日期使用选定时区真实起日及共享公历，关键词按普通文字忽略大小写；金额筛选保持已批准 20／18 位边界。
  - 原读取黄金、真实双库完整分页／转账／退款对照、游标七项规则／版本失效、万条事实与关系性能及完整 DTO 对照均已有冻结证据；独立完成性复核确认实现链。Graph 当前在线／离线均通过 SessionCacheRepository、LocalFirstFinanceRepository 与 LocalCashMaintenanceRepository 调用同一快照本地读取，不回退旧 GET；当前菜单版最终浏览器及独立 UI 门见[应用整体切换](#应用账本与规范维护整体切换7273)，真实五流程及最终独立 UI／证据门已通过，本项关闭。
- [x] 7.3 移除对服务端投影读取面的依赖；账本版本变化时整包替换并保持筛选条件
  - 生产 Graph 已移除旧投影读取依赖；缺事实明确报错，跨捕获刷新使分页失效。实际同步原子替换整包事实，缓存下限、原会话／代次与 epoch 围栏保持；FAB 或下拉刷新保留筛选，保存后的 reload 仅本地重建。算法、双库、会话及浏览器证据同 7.2，最终独立 UI 门已通过；删除旧服务端路由及物化表仍由 4.2／4.3 承接。
- [x] 7.4 规则版本变化时触发整体重派生
  - 本地 query／派生视图仅保存在当前 owner 内存，代码升级后由新运行时重建，不恢复持久旧派生值。CashLedgerCursorScope 同时绑定当前共享及投影规则常量；原七个游标回归包含 ledgerOrEitherDerivedRuleVersionChangeRequiresACompleteReload，拒绝旧规则游标且不推进账本事实版本。独立完成性复核已核当前源码及 record-read-final 固定 JVM／JS／Wasm XML 摘要。

## 8. PDF／旧 XLS parse-only

- [x] 8.1 先写失败测试：只返回归一化行、不读账本、不写业务数据、密码不留存
  - 入口不存在的 HTTP 404 已先复现；38 个原始 PDF 样本保留完整成功文档／失败码对照。实际 SQL 观测拒绝现金／账户表读取及所有 DML，完整数据库前后相等；密码仅请求体，工作区／角色／会话失效在成功与密码错误后均拒绝返回文档。客户端真实迟到 401、文件预算、JSON 数字和异常／取消均有失败回归。
- [x] 8.2 实现无状态 parse-only 端点并接入端侧 PDF 路径
  - PDF parse-only → 归一文档 → parseLocalCashStatement → 原会话 owner／LocalCashImportTask／生产生命周期与页面已接通，PDF 仍明确需要联网。原 PDF 六方法真实双库无账本读写及完整样本对照保留在 main-merge-xml，相关生产源及测试自 0fd6e4b9 未改；当前完整矩阵的 PDF API 4 项与 Imports 70 项实际执行。独立完成性复核确认接线及证据；未将旧历史套件算入本轮默认矩阵，也不把客户端 MockEngine 当成部署页面验收。

- [x] 8.3 先写失败测试：旧 XLS 无状态原始文档接口，原银行全部文档与本地归一等价，双库只有鉴权查询、无账本读写，资源／权限／错误与取消受控
- [x] 8.4 实现旧 XLS parse-only 与共享客户端调用；在完整文件调度中接入端侧归一，明确该格式需联网
  - XLS parse-only → 原始 CashStatementDocument → normalizeLocalCashStatement → 原会话 owner／Task／生产生命周期与页面已接通；格式仍需联网解码，之后字段归一、操作与关系计算都在本地，不要求本期端侧解码 XLS。原六个 OLE 完整文档／归一对照及真实双库各七方法的只允许鉴权 SQL、数据库不变、权限／预算／错误证据保留在 main-merge-xml，相关生产源及测试未改；当前完整矩阵 XLS API 4 项与 Imports 实际执行。独立复核确认接线和证据，旧历史 XML 不重复计入本轮默认后端。


## 9. Web 持久化

- [x] 9.1 先写失败测试：Web 端本地库读写与跨会话持久化
  - 真实 SQLDelight／加密 OPFS 工厂、关闭重开和底层完整 Chrome 进程重启已有实际证据；固定底层日志为 `offline-ledger-1104/ledger-web-lease-protocol-node-current.log`，16 项通过，所测 Worker／vault 资源未改。当前 shared JS／Wasm 的 Web 缓存、擦除、同步与恢复套件继续通过。完整应用冷启动另已按 5.4 的当前实际浏览器证据验证，不由底层存储测试代替。
- [x] 9.2 落地 OPFS + SQLDelight-wasm，并按已确认的自动解锁策略完成 Web 密钥生命周期
  - 固定 WASM、校验和及分发声明随资源打包，正式加密 Worker、SQLDelight 驱动、不可导出持久包装密钥与范围库清理已接生产应用。实际浏览器已覆盖重启、精确文本／索引、活动 DELETE 日志、缺钥／错钥保文件、排他租约、清理失败围栏和未提交页溢出后的恢复；关闭并核对 2.6.1 的 ChaCha20 硬件加速，临时数据强制内存。应用登录、离线结果 reload 及 5.4 的完整浏览器冷启动均已验证；最终 UI／整链验收仍未完成。

## 10. UI、跨端影响与演示确认循环

- [ ] 10.1 先写失败测试：离线状态、冲突重核对提示、静默重提交后的结果展示
- [ ] 10.2 实现导入流程的端侧化与相关状态（加载/空/错误/冲突/完成）
- [ ] 10.3 执行 Cross-platform Impact Check：写明 Web、Native、共享层是否受影响及理由（Web compact 与 Native Phone 共用产品合同）
- [ ] 10.4 完成首版后，做相称编译验证并在真实浏览器做代表视口视觉校验，随后启动开发/QA 演示模式交给用户确认（中间检查点）
- [ ] 10.5 按反馈迭代，只补跑受影响的编译与 Web 视觉/交互校验，用最新构建重新演示
- [ ] 10.6 用户确认方向后完成剩余实现与细节修整，再进入最终审查与回归
  - 2026-11-16 用户已采用结果页演示方向：保留已保存统计、显示“账本已保存，结果整理尚未完成”、只恢复原批次；完整逐项结果及最终设计／交互验证仍未完成。

## 11. 最终验证与文档收尾

- [x] 11.1 双后端契约矩阵完整跑通（SQLite + 真实 PostgreSQL `_test`，显式 `FT_TEST_POSTGRES_URL`；未配置记未完成，不宣称通过）
  - `12471f52` 的 `:backend:test` 在 SQLite 与真实 PostgreSQL `_test`（`FT_TEST_POSTGRES_URL` 指向 `offline_first_ledger_1114_test`）双库执行 1,505 项，零失败／错误／跳过；`f176e511` 只改 shared／docs／QA，未改后端合同，故该矩阵对当前 HEAD 的后端代码仍有效。证据为 `retire-historical-parity` 的 command／exit／log／新鲜 XML。
- [ ] 11.2 三端相称编译与单元测试；Web 真实浏览器冒烟（记录浏览器、URL、视口、步骤、控制台/网络错误）
  - 独立复核（separate context，只读）覆盖 `f54a5713^..HEAD` 的后端物化退役与 UI 切片：无 blocker；2 major、8 minor。已处置：major 1（`CashRelationService` 把 component ID 当 transaction ID 传给 `maintainCanonicalCashEntries`）已改为经 `parentIdsForComponents` 映射；minor 3（分类移动死查询）、minor 4（`projectionStatus` 死字段）、minor 8（tasks 列表项合并）、minor 9（测试命名失真）已修复，后端全量 1,505 项通过。待处置：major 2（客户端仍公开并测试已删的 `GET /cash-projections`／证据读取方法）。当前生产读取由 `LocalFirstFinanceRepository`／`LocalCashMaintenanceRepository` 覆盖，远端方法仅作为 delegate 合同保留、未被生产路径调用；彻底移除需拆分共享层读取接口并重跑三端回归，作为后续独立小步，不在本轮扩大范围、minor 5 剩余项（`CashProjectionListInputs` 仅测试使用；两个不可达异常处理器已删除）、minor 6（离线预览未接资格：问题处理按钮已接 `connection.canSubmit`）、minor 10（历史 helper 死分支已删除物化行映射）。minor 7 已修复主要文档引用（`design.md` 耦合层与状态／重建句、`docs/feature-map.md` 表结构状态）。未运行 Gradle，1,505 双库通过未由复核者独立复验。
- [ ] 11.3 独立复核：产品/范围、工程、最终 diff；按风险触发 DX/设计/安全复核，finding 与处置理由回写 artifacts
- [x] 11.4 `openspec validate --all --strict`、`openspec doctor`、`git diff --check`；实际分发 Android 二进制前，核对并随发布提供精确 desugared-library 的对应源码与通知，不能仅用 upstream 链接代替分发准备
  - `f176e511` 上 `openspec validate --all --strict` 27/27、`openspec doctor` 健康、`git diff --check` 通过；本 change 不实际分发 Android 二进制，desugared-library 源码与通知不适用，发布前若转为分发须另行补齐。
  - 后端全量 detektTest 的 10 项既有测试 finding 已清理：常量时钟显式声明 Instant，catch 使用原别名指向的完整异常类名且 13 个 classfile SHA 完全相同，必需 fixture 用 assertNotNull 断言。backend-test-static-full.log 的完整测试编译、全量 ktlint／detektMain／detektTest 已通过，无限定源文件、规则关闭或业务修改；这不替代本任务其他检查和全后端运行回归。
- [ ] 11.5 同步 `docs/feature-map.md`、`DOMAIN_GLOSSARY.md`、现金导入流程文档与本 change 相关的接口/命令示例
  - 当前 `HEAD` 为 `12471f52`（后端 4.3／4.4 已关闭）。本轮在当前工作树复跑 `:shared:testAndroidHostTest`：19 个 `Kotlin*IntegrationTest` 因外部夹具／真实后端不可用报 `IllegalArgumentException: Required value was null`，属环境缺失，未计通过；改动的 `AccessViewModelTest`、`ImportAccountViewModelTest`、`SessionCacheRepositoryTest` 三类通过（6 秒，`--rerun`）。UI 切片已于 `f176e511` 作为检查点提交；演示方向确认仍待用户反馈，6.7、10、11 保持 active。
- [x] 11.6 在 tasks.md 记录当前 `HEAD`、比较基线、实际命令、结果、执行时间与遗留风险；重跑受影响的验证
  - 当前 `HEAD` `f176e511`（UI 切片检查点），后端代码基线 `12471f52`。本轮实际执行：`:shared:testAndroidHostTest --tests AccessViewModelTest／ImportAccountViewModelTest／SessionCacheRepositoryTest --rerun` 通过（6 秒）；`openspec validate --all --strict` 27/27、`doctor` 健康、`git diff --check` 通过。后端 1,505 项双库矩阵结果沿用 `12471f52`（后端未变）。遗留风险：19 个 `Kotlin*IntegrationTest` 因外部夹具／真实后端不可用未运行；UI 演示方向确认仍待用户反馈，6.7／10.5／10.6／11.2／11.3／11.5 保持 active。


### Web 自动解锁与加密数据库验证及收尾证据

比较基线／验证 HEAD：eb29a9fa 加本片工作树，2026-11-06 10:20 UTC。用户已选择网页自动解锁，不增加额外缓存密码；该决定及保护边界已写入 proposal、缓存 delta 与 D10。该选择仅限本离线缓存，不推广为其他产品的通用密码政策。

ledger-key-vault.mjs 以服务地址／使用者／工作区数组的 SHA-256 为记录与锁标识，随机 32 字节数据库密钥用不可导出的 AES-GCM 包装密钥加密；12 字节随机 IV、128 位认证标签及范围附加数据随密文保存。明文数据库密钥不进入 localStorage／IndexedDB。read 缺失只返回 null；已确认新库的所有者才显式 create；已有损坏记录即使 create 也拒绝，不换钥。IDB 成功事务完成后才返回材料，同源范围锁避免多标签首次创建分叉；删密钥只删除该范围的记录，不代表数据库、侧文件、旧会话或打开句柄已清理。

web-key-vault-red.log 在真实 Chromium 上先复现缺少模块；web-key-vault-abort-red.log 在真实 add 请求已成功后调用实际事务 abort，复现错误对象为空。修复优先传播 transaction.error／request.error，仅两者都空才补标准 AbortError；失败不返回密钥且实际记录不存在。此回归不假造 add 成功，也不把 request success 当事务提交。

ledger-storage/worker.js 将密钥仓储与实际加密 OPFS 数据库组合，仍未连接 SQLDelight 工厂、登录／撤权生命周期或页面。官方 v2.6.1 三份资源保持原字节，provenance.json 记录发布压缩包及逐文件 SHA-256；THIRD-PARTY-NOTICES.txt 保留 MC、SHA-2、ChaCha20、AEGIS、Argon2、Emscripten／musl 等声明及完整许可文本。数据库排他持锁，已有文件只读原钥，缺失或错误不重建；原始密钥经 C 指针传入，JS／WASM 临时材料擦除。初始化核对实际版本、chacha20、硬件加速 off、DELETE journal 和内存临时数据，无明文降级或运行时 CDN。

web-encrypted-db-red.log 先复现 Worker 缺失；web-encrypted-db-failure-red.log 实际复现 acquire 获取目录失败后未关闭／未释放租约。修复在新 erase 获取资源前设关闭围栏，并在获取目录失败时释放租约。文件删除失败保留密钥，旧连接拒绝后续 SQL，新 Worker 才能明确重试；不假装登出完成，不删除其他工作区。

实际命令：在 compose/qa/web 运行 npm run test:node，27 项通过，无 failure／cancel／skip；加密库组和既有密钥组各为 10 子测试及父测试，共 11 项。浏览器 HeadlessChrome 151.0.7922.34，数据库组 URL http://127.0.0.1:60671，视口 1280×720；增加主文件溢出 SHA 断言后单组复跑 URL http://127.0.0.1:60240，11 项通过。完整 Chromium 重启先读取旧值，保留超过 2^53 的文本标识及 20／18 位金额；验证索引、提交／回滚、真实非空 DELETE journal 的有限明文标记检查、第二 Worker 拒绝、缺钥及另一把有效钥拒绝且逐文件 SHA 不变、还原包装记录后重读。中断测试先断言未提交时主库 SHA 已变化，再 terminate 并重开，只读到原已提交值。既有密钥组完整重跑重启离线读取、两标签页并发及损坏／存储故障／事务 abort 回归。无 console error、pageerror 或失败网络请求；不把有限标记扫描当完整密码学审计。全部合成数据，仓库外专用临时 profile 测后删除。

公共 Gradle 参数执行 webApp:jsProcessResources／wasmJsProcessResources 通过，两个目标的 Worker、官方资产、来源记录及分发声明 SHA 与源文件逐一相同；node --check、OpenSpec strict 与 git diff --check 通过。日志为缓存 web-encrypted-db-{node,node-final,packaging}.log，打包摘要为 web-encrypted-db-packaged-sha.json。没有 Kotlin／原生代码、后端事实持久化或 schema 变化，本片不重复原生编译和 SQLite／PostgreSQL 矩阵；内部资源浏览器验证不冒充应用离线流程冒烟。

独立 5a（安全／存储／复杂度）及实际独立 5b 完成：租约／关闭状态 finding 已修复，许可缺漏 P2 已补齐并重审，中断证据 P3 已增加主库变化断言且通过；无 UI 专项。Cross-platform Impact Check：Web 两目标共用同一资源，Android／iOS 本片无实现或展示变化。已核对缓存规格、D9／D10、tasks、两级地图、实施指引、依赖分发及词表；未新增领域术语、部署参数或公开应用入口。用户解锁决定只沉淀本业务，无需新增通用规范。资源可随代码回退，本库为未接应用的新格式，无历史用户数据迁移。SQLDelight 工厂、完整缓存 owner／索引、跨标签协调、应用撤权／登出及页面仍未组合，5.3／5.4／9.1／9.2 保持未完成，不归档或发布。

### 固定范围 SQL 缓存适配及收尾证据

比较基线／验证 HEAD：8ed1ed3a 加本片工作树，2026-11-06 10:39 UTC。D9 先记录关闭与清理的责任边界；scoped-sql-cache-red.log 因缺少适配器先编译失败。ScopedSqlLedgerFactCache 复用 SqlLedgerSnapshotStore，不新增表或事实模型；平台工厂必须显式提供加密驱动、可等待的真实连接关闭及该范围的文件／密钥删除动作，不以 Web SDK terminate 单独当作句柄已关闭。

适配器固定服务地址／使用者／工作区，串行读写；clear 先设关闭返回围栏，等待既有 SQL，再关闭驱动并擦除存储。关闭失败不删，删除失败不重关成功关闭的驱动；失败和取消保留关闭状态，可明确重试。初始化失败以 NonCancellable 等待关闭，只附加清理诊断、不删旧存储。catch 在该清理上下文内部保留原异常身份；同异常对象正例验证 Kotlin addSuppressed 的既有自抑制保护，没有为该正例增加多余分支。

scoped-sql-cache-affected-platforms.log 使用本页公共 Gradle 参数并关闭 JS／Wasm incremental，运行 shared:testAndroidHostTest，精确选择 ScopedSqlLedgerFactCacheTest、SqlLedgerSnapshotStoreTest、LedgerSnapshotSyncTest、LocalCashLedgerOwner*、LedgerSyncApiTest，共 6 类／39 方法，0 failure／error／skip；新类 10 方法使用真实 JDBC SQLite 验证完整快照、范围隔离、真实 SQL 拒绝保旧、迟到读取围栏、关闭／删除失败及取消后重试、实际建表后取消并等待非取消关闭、不同及相同异常对象保真。随后 shared:compileKotlinJs、compileKotlinWasmJs、compileKotlinIosSimulatorArm64、compileKotlinIosArm64 通过；Android 主代码及测试随前述任务编译。终态仅补协程测试 opt-in 注解，scoped-sql-cache-final-tests.log 再跑相同 39 项全部通过，当前 6 份 XML 保存于 scoped-sql-cache-final-xml。原生仅编译及单测，不宣称设备 QA。

扩大运行的 scoped-sql-cache-platforms.log 在 host 全测阶段得到 620 项、19 失败、17 skip；19 失败均为 Kotlin*ResponseIntegrationTest／KotlinPortfolioLiveIntegrationTest 未配置 FT_KOTLIN_CLIENT_CONTRACT_DIR，17 skip 亦为既有夹具依赖，不能算全量通过。原样 XML 保存在 scoped-sql-cache-full-host-attempt；该外部夹具集成组须在最终全量验证时由当前 Kotlin 后端重新捕获响应后补跑，不能用旧迁移输出冒充当前证据。相称的当前缓存测试已如上明确选择并全部通过，未修改、删除或隐式跳过旧测试。

独立 5a 工程／安全／异常及实际独立 5b 契约审查无遗留 finding；无 UI 专项。Cross-platform Impact Check：通用适配器影响三端，相关编译已运行；没有页面、文案、路由或实际平台工厂接线，Web 组件与原生设备 QA 本片不新增。本片没有服务端 Application Service、schema 或新 SQL 形状变化，不重复 SQLite／PostgreSQL 服务端矩阵，也不把 JDBC 当加密通过。

收尾核对 D9、任务、两级功能地图、实施指引与词表，无新领域术语、公开 API／部署配置或通用用户规则。可独立代码回退；原生平台工厂、完整生命周期、索引和应用所有者仍待接线；Web 工厂见本页后续证据，5.3／5.4／9.2 不勾选，不归档或发布。OpenSpec strict 及 diff 检查在提交前完成。

### Web SQLDelight 加密工厂及收尾证据

比较基线／验证 HEAD：1ac0037a 加本片工作树，2026-11-06 11:30 UTC。范围为已确认的 Web 加密缓存内部平台工厂，沿任务 5.3／5.4／9.1／9.2 继续；不改变产品范围。D9 先明确控制通道、关闭确认、取消和 SDK 监听责任。复用既有 SQLDelight 2.4.1 依赖，只新增 web-worker-driver；显式应用默认层级模板使 JS／Wasm 共用 webMain／webTest，不新增事实模型或明文驱动。原 schema、完整快照及范围适配器原样消费。

WebLedgerFactCache 将实际加密 Worker、WebWorkerDriver、完整 DTO TEXT 存储和关闭／擦除动作组合。初始化取消在非取消上下文等待打开并关闭；SQLDelight 非负请求与负数控制 RPC 隔离。同源 connection.mjs 以原生异步 import 加载，Web 与 Karma 的 Webpack external 均显式配置；不使用 eval 或 CDN。真正关闭失败不擦除，成功确认才终止；结构化错误允许明确重发，线程错误／超时在重复 close 时仍拒绝。固定 SDK 原产物成功只移除 message 监听，真实线程抛错曾复现 Already resumed；私有连接层按该版本注册顺序成对释放两种监听，线程错误先设围栏再通知活动请求，后续发送保留失败。第三方原源码未改，完整 Apache 2.0 许可随资源提供。

测试先行证据保存在仓库外缓存目录：web-ledger-factory-semantic-negative.log 与 web-ledger-factory-negative-xml 在实际写读后故意设错版本期望，JS／Wasm 均实际失败；该负控已移除。web-ledger-sdk-fatal-red.log 使用原实际驱动及真实 Worker throw 复现旧监听重恢复；关闭失败的 Node 红灯及修复见 web-ledger-controller-close-green.log。web-ledger-sdk-fatal-final.log 使用本页公共 Gradle 参数、关闭 JS／Wasm incremental，CHROME_BIN 指定本机 Chrome，分别执行 shared:jsBrowserTest／wasmJsBrowserTest --rerun --tests '*WebLedgerFactCacheTest*'，各 3 方法，0 failure／error／skip；fresh XML 保存于 web-ledger-factory-final-xml。真实 ChromeHeadless 157、http://localhost:9876/context.html、741×413 实际视口，以实际工厂验证完整 DTO、Long／金额／开放 JSON 数字词法、范围拒绝保旧、清理后新开、打开中取消不泄漏租约；测试专用 Worker 暂停真实 exec 响应后实际 throw，活动／后续读取及时失败、已完成请求不重复恢复。只证明请求已进行且 SQL 执行前暂停，不声称 SQLite 指令内部崩溃；底层事务中断恢复由前一 Worker 切片覆盖。一次空间不足的 Wasm 打包中断虽被 Gradle 标作成功，因无 fresh XML 未计通过；清理本次生成包后才取得上述两端终态。

web-ledger-factory-node-final.log 从 compose/qa/web 执行 npm run test:node，30 项全通过、0 skip。真实 Chromium 151、临时 localhost URL 与 1280×720，覆盖实际 30 秒关闭超时持续拒绝、关闭错误后真正重发并确认、线程错误、密文文件／日志、进程重启、事务中断、密钥与范围清理；控制台／页面／网络错误均为空。合成数据与浏览器状态在仓库外，故障脚本仅位于 webTest 资源并由 Karma 精确代理，不作为产品资源。

web-ledger-factory-platforms-final.log 使用本页公共 Gradle 参数（外部 cash-write-set-lint.init.gradle、max-workers=1、JVM 3 GiB）并关闭 JS／Wasm incremental，运行 shared:testAndroidHostTest，选择 ScopedSqlLedgerFactCacheTest、SqlLedgerSnapshotStoreTest、LedgerSnapshotSyncTest、LocalCashLedgerOwner*、LedgerSyncApiTest，6 类／39 方法全通过、0 skip；fresh XML 另存 web-ledger-factory-host-xml。shared:compileKotlinIosSimulatorArm64／compileKotlinIosArm64、webApp:jsBrowserDevelopmentWebpack／wasmJsBrowserDevelopmentWebpack 均成功，Android 主代码随 host 测试编译。两端 processedResources/main 中 8 份存储资源逐字与源码相同，16 项 SHA 保存于 web-ledger-factory-packaged-sha.json；没有 ledger-worker-fault.js，亦无该测试脚本进入 webApp/build。这里验证开发 Webpack 及真实预览所消费的处理后资源，不声称重新生成生产发行包。首次组合矩阵的 Webapp 编译缓存因磁盘不足失败，已清理本 worktree 的可重生成测试包后完整重跑成功；失败日志原样保留。全量 host 的外部响应夹具缺口沿上一节保留，未以这 39 项代替全量回归。

独立 5a（范围／工程／安全／异常／复杂度／最终 diff）和实际独立 5b 契约复核完成。关闭重试 P2 已先红再绿并重审；真实 SDK 故障与活动请求的窄增量复核无 finding。没有页面、路由、文案或交互变化，UI 专项不适用。Cross-platform Impact Check：Web JS／Wasm 共用新工厂；共享源集层级影响 Android／iOS，须以当前相称编译与受影响缓存单测验证，原生设备 QA 不在默认范围。本片不改后端 Application Service、schema 或 SQL 形状，不重复服务端双库矩阵；不把浏览器 SQLite 当真实 PostgreSQL 通过。

收尾核对 D9、缓存 delta、两级功能地图、实施指引、词表与第三方分发；没有新领域术语、公开业务接口或新用户决定，不新增通用规范。代码可独立回退；仍未接应用登录／撤权、页面、完整所有者索引及多标签协调，原生加密工厂也未完成，5.3／5.4／9.1／9.2 保持未勾选，不归档或发布。

### Web 版本同步与加密缓存组合证据

比较基线／验证 HEAD：3a3f4caf 加本片工作树，2026-11-06（UTC）。本片只增加组合回归、Karma 的密钥模块精确原生 import 外部项和验证文档；不改生产逻辑、存储模型或行为合同。复用真实 createWebLedgerFactCache、LocalCashLedgerOwner、KtorLedgerSnapshotSource 和完整合成快照，HTTP 明确为 MockEngine，不是后端集成或产品页面 E2E。

WebLedgerSnapshotSyncTest 验证未缓存时 page 返回 null 且不访问网络、同版本只查版本而不下载、变更后实际 SQL 中的完整事实与页金额换版且旧游标拒绝；503 保留旧页和数据库原事实，已确认收据下限迫使过期版本响应时重新获取事实。401／403 路径真实删除范围 OPFS 目录和 IndexedDB 密钥材料，清理前同时断言两者存在、之后同时断言两者不存在；旧 owner／cache 拒绝读取，同范围新打开为空。作用域请求携带原工作区及 token，测试 tokenStore 不允许修改或清除应用凭据。密钥取证只读取合成范围，读出的临时原始钥立即擦除，测试不写真实账户或文件。

web-ledger-sync-composition-materials-final.log 在本页公共 Gradle 参数后关闭 JS／Wasm incremental，设置 CHROME_BIN 为本机 Chrome，执行 shared:jsBrowserTest --rerun --tests '*WebLedger*Test*' 与 shared:wasmJsBrowserTest --rerun --tests '*WebLedger*Test*'，2026-11-06 11:44 UTC 两端各 2 类／5 方法全通过、0 failure／error／skip，日志 BUILD SUCCESSFUL 49s。fresh XML 保存于 web-ledger-sync-composition-final-xml/<任务>/。ChromeHeadless 157，http://localhost:9876/context.html，741×413；无用户页面 QA 声称。初次新测试因 Kotlin js 内联解析器不支持 async 语句而编译失败（web-ledger-sync-composition.log），已仅将测试探针改为 Promise 链；未改生产行为。版本同步的先失败回归及真实 SQL 原子拒绝保旧沿原测试证据保留，结合本次真实加密组合完成 5.5 测试项；应用接线的 5.6 不勾选。

阶段 5a 的独立范围／工程／安全及证据审查无阻断；目录正例证据 P3 已将探针改为清理前后同时断言密钥及目录 presence，当前两端终态已核对关闭。实际执行 5b 范围判断：仅增加测试／配置／验证文档，没有数据库、DTO、接口或 UI 合同变化，复用上一实现片的契约结论，不重复调度专项。Cross-platform Impact Check：Web 两目标共用 webTest，Android／iOS 和生产源码均未变；只重跑受影响 Web 集成，不重跑不受影响的 native、服务端双库或页面 QA。新的测试和配置可独立回退。

词表、缓存 delta、D9、地图及实施指引按内部验证边界核对，没有新的用户决定或领域术语，不新增通用规范。不把新增回归作为应用离线功能可用或最终全量通过；完整应用所有者、平台工厂／本地索引、登录／撤权接线和页面仍待完成。

### 同客户端凭据生命周期验证及收尾证据

比较基线／验证 HEAD：992b6232 加本片工作树，2026-11-07（本地）。范围属于 5.3／5.4 的登录前置边界，D10 已先明确同客户端互斥、请求身份快照和迟到清理，没有新增产品选择。凭据读／写／条件删除同锁，网络不持锁；显式匿名快照不读取后续登录。401 只清理仍匹配请求的令牌，退出只清理一次，取消时以 NonCancellable 等待条件清理。双故障保留双方诊断，取消仍为取消；ApiFailure 可保留 cause，不改变线协议。

仓库外 credential-lifecycle-red.log／xml 先复现四项失败：迟到 401、迟到退出擦新令牌、读取取消被包装及存储原因丢失。credential-logout-cleanup-red.log／xml 的七项中两项失败，复现异步清理被取消及远端失败被清理失败覆盖。当前 credential-lifecycle-current-platforms.log 使用公共 2 GiB／单 worker／非 daemon／in-process 参数，执行 :shared:testAndroidHostTest（FinanceApiClientTest、FinanceApiCredentialLifecycleTest、LedgerSyncApiTest）、:shared:compileKotlinIosArm64、:shared:compileTestKotlinIosSimulatorArm64、:shared:compileKotlinJs、:shared:compileKotlinWasmJs，88.60 秒成功。host 三类共 22 方法，零 failure／error／skip；九项新回归另覆盖比较／删除期间阻止登录插入、显式匿名身份和取消与清理双故障。Ktor 可跨协程复制取消异常，网络取消不以 MockEngine 原异常对象地址判断；凭据读取直接取消仍断言同对象。fresh XML 位于 credential-lifecycle-current-host-xml。

credential-lifecycle-current-browser.log 使用现有 ChromeHeadless init、NODE_ENV=development、CHROME_BIN 及关闭 JS／Wasm incremental 参数，执行 :shared:jsBrowserTest 和 :shared:wasmJsBrowserTest，均单模式 --tests '*FinanceApi*'；63.16 秒成功，两端各两类 18 方法，零 failure／error／skip，fresh XML 位于 credential-lifecycle-current-browser-xml。采样进程树 RSS 峰值：跨端构建 2,692,415,488 字节，浏览器矩阵 2,821,226,496 字节，可能重复共享页，不是产品内存指标；未同时启动模拟器。

独立 5a 工程／安全／异常／复杂度及实际独立 5b 契约复核完成，两项 P2 修复后复核关闭，无遗留 finding。Cross-platform Impact Check：通用 HTTP 与 AuthApi 影响三端，已执行相称编译与真实 Chrome 单测；没有 UI、路由、文案、服务端持久化或分发依赖变化，不新增页面冒烟、原生设备 QA、双后端或打包验证。本片不是跨客户端／标签页、缓存注销、离线启动或页面接线验证，5.3／5.4 保持未完成。

收尾核对词表、D10、README、两级地图和实施指引，无新领域概念或通用产品规则。沿已确认隔离及取消边界恢复正确行为，无需新增通用规范；代码可独立回退，无数据迁移。strict 及含新增测试的 diff 检查在提交前执行，不归档、开 PR 或发布。

### 绑定操作的登录凭据写入验证

比较基线／验证 HEAD：7a39839f 加本片工作树，2026-11-07（本地）。登录／注册新增绑定原目录 ticket 的内部重载，请求前核原服务地址和代次；响应后在同客户端 credentialMutex 内再核代次与请求原令牌，再复用原存储错误边界写响应令牌。HTTP 等待不占锁；旧公开及双参内部入口仍按原时点写入，返回同次 session／token／origin，不重新读当前令牌猜响应身份。锁内 get／set 提取供原入口及新守卫复用，空令牌、取消与原 cause 不变。该守卫只防旧响应覆盖后来已成功的同客户端登录，不提供缓存资格、跨客户端／标签页或 TokenStore.set 内部取消的原子事务；Graph 未接此入口。

ledger-scoped-auth-red.log 15.33 秒编译失败，确认新绑定重载缺失，不计旧公开入口业务缺陷。新增五项回归覆盖旧 login／register 响应阻塞、后一次不同身份登录成功后只写一次；stale／foreign 无 HTTP；目录代次未变但原令牌改变也拒绝；current 的登录／注册、null／空／非空旧令牌均返回原响应对；凭据重验仓储故障保 ApiFailure 原因、不写入。两个响应按各自请求序号固定，不使用可变总计数选择旧回包。

ledger-scoped-auth-host.log 37.77 秒成功：ScopedAuthenticatedApiSession（5）、AuthenticatedApiSession（6）、FinanceApiCredentialLifecycle（9）、LedgerCredentialSnapshot（6）、LedgerSessionPublication（14），五类四十项全为 0 fail／error／skip；fresh XML 为 ledger-scoped-auth-host-xml。platforms.log 109.22 秒成功：Android Main 校验 UP-TO-DATE（本片 common 已由 host 编译）、iOS Arm64 及 iOS Simulator 测试编译，Chrome JS／Wasm 各八类九十项全零；浏览器在上述五类加 LedgerSessionRestoration（23）、LedgerOfflineSessionFallback（14）、WebLedgerSessionRegistration（13），fresh XML／时间戳为 ledger-scoped-auth-terminal-browser-xml。后者保留实际加密身份发布／冷恢复及默认原生 fetch 拒绝组合；新增绑定认证本身使用合成 HTTP／凭据仓储，不冒称产品登录或离线启动已接通。

实际命令沿既有两份 init、--no-daemon、--max-workers=1、--no-configuration-cache、--no-build-cache、in-process／UTF-8，host Xmx1536M；browser Xmx2048M、Node768M、JS／Wasm incremental=false。任务 :shared:testAndroidHostTest、:shared:compileAndroidMain、:shared:compileKotlinIosArm64、:shared:compileTestKotlinIosSimulatorArm64、:shared:jsBrowserTest、:shared:wasmJsBrowserTest，测试按上述类名过滤。host 采样进程树 RSS 峰值 2,374,844,416 字节，平台矩阵 5,054,529,536 字节、最大单进程 3,021,799,424 字节；共享页可能重复，不是产品内存，未并行模拟器。确认无活动 Karma 后清理本任务两个临时 webpack 目录 128,665,013 字节，保留外部 manifest；全部日志、XML 和采样留仓库外 offline-ledger-1104。

独立 5a 与实际独立 5b 已核响应对、锁序、原错误边界和范围，静态无 finding；终态运行及同组文档已完成独立核验，无遗留 finding。Cross-platform Impact Check：三端共享内部认证和原凭据锁逻辑，原生编译／主机测试及浏览器回归；无 UI、交互、路由、文案、依赖、服务端事实或 SQL 改动，本片不新增 UI 专项、Hallmark、产品页面冒烟、设备交互或双库。D10、README、总览／共享地图、词表与 apply 指引同组核对；无新产品取舍、通用规范、领域术语或数据迁移。应用会话、退出、持续联网核验与页面仍未接，相关任务及整个 change 不勾选、不归档、不发布。

### 已核验恢复的撤权清理与资源终结验证

比较基线／验证 HEAD：d2b79c39 加本片工作树，2026-11-07（本地）。LedgerSessionRestoration 在线成功后先构造并校验全部工作区范围及活动唯一归属，再用已核验使用者／工作区集合清理已失去访问权的范围，随后刷新／发布活动账本；无活动工作区也按已确认权限清理，其他用户保留。离线历史身份不作撤权依据。清理对象在异步工作前强持有，失败不继续开库或发布，关闭封闭迟到结果并收尾原资源；本片不处理认证拒绝时的用户定位或显式退出，Graph 和产品页面仍未接通。

ledger-restoration-revocation-red.log／red-xml 先复现已核验无任何工作区却留下旧缓存引用，一项行为红灯，20.43 秒失败。首次组合 host.log 五类五十八项全零，37.79 秒成功，仅作当时工作树证据。独立审查随后发现两项 P2：换代后的已拒绝清理永久阻塞 close、坏工作区校验晚于删除；review-red.log／red-xml 三项均实际失败，19.40 秒。新增必需 LedgerCacheErasure.close：未开始不取得租约；已拒绝且释放成功可正常结束，释放仍失败保同一资源及原诊断；已开始擦除沿原动作完成，已擦除仅最终释放。batch.close 先封新项，再仅关闭 selected，不扫描其余目标；该收尾不等于整体擦除成功。范围完整校验在任何清理前，后续复用已验证 activeScope。

审查另发现目录 current 异步读取期间 close 后仍启动目标，close-barrier-red.log／同名 XML 的读屏障用例实际失败，修复为读取返回后再次核 closing。首轮修复矩阵的坏 surrogate 夹具仍失败，原因是 Mock 原始 UTF-8 先把孤立代理字符替换成其他合法字符；first-fix-xml 和 close-barrier-red-xml 均保留该事实，不计生产校验失效。夹具改用显式 JSON escape 传递原 surrogate，保留完整原始码点及 active／无 active、空／空白／非法 UTF-16 组合，不放宽生产范围。

父级最终自查再复现关闭锁序缺口：恢复对象先等资源锁，未能即时封闭 batch 后项；parent-close-red.log／同名 XML 一项实际失败，预期只删 revoked，实际多删 unstarted。修复为以 StateFlow 强持有清理引用，恢复 close 在等锁前立即发 stop；迟到创建的动作在发布后再核 Closed，避免 close 先看到 null 后仍执行。两个 revoked 回归保留只处理首项、剩余目录、created 与不发布断言。独立窄复核确认该修复；此前 host-terminal／platforms 结果不当作当前终态。

ledger-restoration-revocation-parent-final.log 在同一终态工作树串行执行 shared Android host 五类六十八项、Chrome JS／Wasm 各九类九十七项，全部 0 fail／error／skip；137.79 秒成功，同时编译 Android Main、iOS Arm64 及 iOS Simulator 测试成功。fresh XML 与各目标时间戳集中于 ledger-restoration-revocation-parent-final-xml。主机选择 LedgerSessionRestoration（23）、LedgerSessionCacheCleanup（12）、LedgerCacheErasureOwner（11）、LedgerSessionCacheOwner（13）、LedgerSessionDirectory（9）；浏览器加 WebLedgerCacheErasure（11）、WebLedgerSessionRegistration（13）、WebLedgerLeaseProtocol（2），Directory 过滤同时包含 WebLedgerSessionDirectory（3）。

新增默认恢复使用合成 Mock HTTP 与真实加密工厂：在 version 请求前核 revoked 文件／钥／租约已消失，其他用户物理摘要不变，随后真实整包落盘、身份发布、分页及保留关闭；另核拒绝后 close 正常收尾且不触碰新缓存持有租约，以及 lost ACK 后 close 仅释放、不重新擦除。primitive／batch 回归区分拒绝已释放与释放仍失败、未开始关闭、部分收尾及目录读取屏障；恢复双目标回归验证父级即时停止。该组合不等于真实服务器或产品断网启动 QA。

实际终态命令沿本节前两片的既有 init、--no-daemon、--max-workers=1、--no-configuration-cache、--no-build-cache、in-process／UTF-8、Xmx2048M、Node768M、JS／Wasm incremental=false。任务 :shared:testAndroidHostTest、:shared:compileAndroidMain、:shared:compileKotlinIosArm64、:shared:compileTestKotlinIosSimulatorArm64、:shared:jsBrowserTest、:shared:wasmJsBrowserTest，测试按上段类名过滤。采样进程树 RSS 峰值 5,108,449,280 字节、最大单进程 3,133,030,400 字节，共享页可能重复，不是产品内存；未并行模拟器。结束确认无活动 Karma 后清理本任务两个临时 webpack 目录 128,527,339 字节并保留外部 manifest。日志、各失败／终态 XML 及采样留仓库外 offline-ledger-1104；strict／diff 检查在提交前执行。

独立 5a 及实际独立 5b 已重审三项 P2、父级即时停止修复与真实 Web 断言，无新增阻断；最终运行及同组文档已完成独立核验，无遗留 finding。Cross-platform Impact Check：三端内部恢复与清理资源合同同组调整，Web 实际存储组合，原生编译／主机验证；无 UI、交互、路由、用户文案、依赖、后端事实或 SQL 变化，本片不新增 UI 复用、Hallmark、产品页面冒烟、设备交互或双库。D10、README、总览／共享地图、词表与 apply 指引已核对；沿已确认撤权语义，无新产品决定、通用规范、领域术语或数据迁移。当前仅内部恢复已组合权限清理，应用启动、持续联网核验、退出及页面未接，5.3／5.4／7.2 和整个 change 保持未完成，不归档或发布。

### 同用户跨工作区清理验证

比较基线／验证 HEAD：3a923a8b 加本片工作树，2026-11-07（本地）。内部 LedgerSessionCacheCleanup 用原 ticket、已确定使用者摘要及复制的可访问工作区集合选择目标。目录新增 scopes(ticket)，同一读取内核代次并返回引用；首次成功枚举固定列表，只选择同 origin／使用者的不可访问工作区。逐项强持有正式 erasure，成功才推进，失败继续原对象，已完成项及后来登记范围不进入重试；换代后可完成已开始的部分擦除，但不得开始下一项。空保留集合清理本用户全部范围，其他用户及 origin 始终保留；此对象不判断认证资格，调用者须先封闭旧读取及关闭旧缓存。

ledger-cleanup-selection-red.log 确认新类缺失的编译红灯，15.30 秒失败。host.log 36.74 秒成功，选择 LedgerSessionCacheCleanup、LedgerCacheErasureOwner、LedgerSessionDirectory，三类二十五项 0 fail／error／skip；fresh XML 为 ledger-cleanup-selection-host-xml。九项新增协议测试覆盖集合外部修改、撤权／退出选择、失败同对象、固定目标、过时代次、目录故障、部分清理后换代、取消及空目标完成隔离。

platforms.log 已完成 Android Main 校验（UP-TO-DATE，当前 common 已由 host 编译）、iOS Arm64 及 iOS Simulator 测试编译，随后 JS 七类五十二项中新增两项失败，原因是新多工作区夹具的 seed 快照仍固定 workspace。first-browser-xml 保留该失败；只改合成快照 workspaceId 匹配目标，不计生产缺陷，生产代码不变。browser-final.log 串行执行 Chrome JS／Wasm 七类各五十二项，全为 0 fail／error／skip，71.43 秒成功；fresh XML 及时间戳为 ledger-cleanup-selection-final-browser-xml。实际存储组合验证可访问工作区／其他用户文件和密钥摘要不变、旧已完成 batch 不删重建材料，以及第二范围定位删除失败后真实 lease 保留，换代并新建 later 缓存后只完成原第二对象且不扫描 later。

实际命令沿上节既有 init、--no-daemon、--max-workers=1、--no-configuration-cache、--no-build-cache、-Pkotlin.compiler.execution.strategy=in-process、UTF-8，主机 Xmx1536M、浏览器 Xmx2048M／Node768M、JS／Wasm incremental=false。浏览器选择上述三类及 WebLedgerCacheErasure、WebLedgerSessionRegistration、WebLedgerLeaseProtocol，Directory 过滤同时命中 WebLedgerSessionDirectory；编译任务为 :shared:compileAndroidMain、:shared:compileKotlinIosArm64、:shared:compileTestKotlinIosSimulatorArm64。主机采样进程树 RSS 峰值 2,387,263,488 字节，终态浏览器 4,829,478,912 字节，最大单进程 2,909,487,104 字节；共享页可能重复，不是产品内存，没有并行模拟器。确认无活动 Karma 后清理本任务两个临时 webpack 目录 128,036,017 字节并保留外部 manifest；全部证据在仓库外 offline-ledger-1104。

独立 5a／实际独立 5b 及真实 Web 组合静态复核无 finding，终态证据及同组文档已完成独立核验，无遗留 finding。Cross-platform Impact Check：三端共用清理选择，Web 真实加密存储组合，原生编译／主机测试；没有 UI、交互、路由、用户文案、依赖、后端事实或 SQL 变化，本片不新增 UI 复用、Hallmark、页面冒烟、原生设备交互或双库验证。D10、README、总览／共享功能地图、词表和 apply 指引已同组核对。无新产品决定、通用规则、领域术语或迁移。仅内部清理已提供，应用退出／撤权生命周期及页面未接通，5.3／5.4／7.2 与整个 change 不勾选、不归档、不发布。

### 固定范围擦除的操作围栏验证

比较基线／验证 HEAD：07c180b4 加本片工作树，2026-11-07（本地）。应用默认擦除入口强制原 ticket，三端工厂共用 LedgerCacheErasureOwner；取得真实范围租约后才核目录代次，删除前拒绝过期操作。拒绝释放失败强持有同一资源，旧对象以后只能完成释放并重抛原诊断；已开始部分擦除继续同一动作，文件／钥／定位全部移除后先记完成再最终释放。平台既有路径、实际删除顺序和普通缓存行为不变；显式平台工具入口保留可选 ticket，不作为应用授权。

ledger-erasure-owner-red.log 记录新类缺失的编译红灯；first.log 的七项中两项失败来自协程栈恢复后外层异常地址断言，修正为核原异常仍在原因链，保留清理异常的 suppressed 检查，不计产品缺陷。终态 common 七项覆盖租约期间换代、前置故障、拒绝后释放重试、部分擦除、最终 ACK 丢失与取消等待。

ledger-erasure-owner-host-native.log 61.26 秒成功：shared Android host 选择 LedgerCacheErasureOwner、LedgerSessionDirectory、LedgerSessionCacheOwner、LedgerSessionRestoration，四类四十三项 0 fail／error／skip；Android Main、iOS Arm64 及 iOS Simulator 测试编译通过。fresh XML 保留于 ledger-erasure-owner-host-native-xml。原生本片只有编译／主机验证，不冒称三端实际工厂均已设备运行。

ledger-erasure-owner-browser.log 中 Chrome JS 六类四十一项全零，Wasm 测试链接在 Xmx1536M 发生 heap OOM，整轮 84.70 秒失败，不把它算作 Wasm 通过。保持源代码不变，仅恢复既有 Xmx2048M 后，browser-2048.log 31.66 秒成功：JS UP-TO-DATE 沿前轮 fresh XML，Wasm 六类四十一项全零。两目标均选择 LedgerCacheErasureOwner、LedgerSessionDirectory（同时命中 WebLedgerSessionDirectory）、WebLedgerCacheErasure、WebLedgerSessionRegistration、WebLedgerLeaseProtocol，完整终态 XML 及各实际时间戳保留于 ledger-erasure-owner-browser-final-xml。真实默认清理入口在取得实际 SDK 租约后阻塞目录读取，再通过实际 CAS 换代，核原物理文件／钥摘要不变和租约释放；旧拒绝对象在新缓存已持锁时仍不能重新取得租约或删除。部分擦除及最终 ACK 丢失后推进代次，重试仍完成原动作或仅释放。

执行命令共用 -p compose、既有 cash-write-set-lint.init.gradle、--no-daemon、--max-workers=1、--no-configuration-cache、--no-build-cache、-Pkotlin.compiler.execution.strategy=in-process 及 UTF-8；浏览器另用 shared-browser-unit.init.gradle、NODE_OPTIONS=--max-old-space-size=768、ChromeHeadless、JS／Wasm incremental=false。主机 Xmx1536M 采样进程树 RSS 峰值 2,668,068,864 字节；成功浏览器 Xmx2048M 峰值 4,745,756,672 字节、最大单进程 2,825,437,184 字节，可能重复共享页，不是产品内存；没有并行模拟器。测试结束确认无活动 Karma 后清理本任务临时产物，外部 manifest 保留；日志、内存采样及 XML 都在仓库外 offline-ledger-1104 目录。

独立 5a 及实际独立 5b 已核所有权、异常、取消和三端替换边界，无 finding；终态运行及同组文档已由独立上下文复核，无遗留 finding。Cross-platform Impact Check：三端共享清理状态机，Web 真实存储组合回归，Native 编译及主机回归；不改 UI、交互、路由、文案、依赖、服务端事实或 SQL，UI 专项、Hallmark、产品页面冒烟和双后端本片不适用。D10、README、总览／共享功能地图、词表及 apply 指引同组核对；无新领域概念、产品决策、通用规则或迁移。应用注销、撤权枚举和页面尚未接通，5.3／5.4／7.2 及整体保持未完成；不归档或发布。

### 本地现金账户读取与 JSON 文本兼容验证

比较基线／验证 HEAD：b57b4a4d 加本片工作树，2026-11-07（本地）。buildLocalCashAccountReferences 从完整快照保留 cash／loan／lend、停用状态、完整 Long 排序及原 currencies 的真假与数组／对象键／字符串码点迭代，不 trim、去重或改变命令校验。账户读取沿三层原 readSnapshot／read／readReady，复用完整事实、令牌绑定身份、current、代次及关闭围栏，不访问网络或初始化投影索引；Graph 与页面尚未接线。

Python JSON 文本及 repr 移到共享层，backend 三个内部函数薄委托，printable 旧表删除后只保留一个 private 共享表；完整原 PythonStringGoldenData 除 package 外逐字相同，printable initializer 与比较基线逐字相同。替换前 shared 与原 native 对照全部 0..10ffff 码点及显式 surrogate 拆分、5,000 位整数通过，fresh XML 为 ledger-accounts-before-delegation-xml。替换前原 repr 文本以 NUL 分隔的 SHA-256 固定为 98f10605ea58fdbfcfc9eda665a3c9047583e020957c1d847f2f3d69e55ccf95；original-digest-xml 的 capture-original 断言是采集输出，不计产品红灯。终态改为原完整 golden、固定摘要和明确结果断言，不使用委托后自对照。

ledger-accounts-red.log 与 owner-red.log 分别复现纯入口及三层读取入口缺失的编译失败。完整 JS golden 首轮因 19.543 秒超过默认 Mocha 2 秒失败；保留 node-timeout-xml，Node JS／Wasm 测试设置 120 秒且不删样本。随后 Wasm 原完整 golden 实际复现小数文本解析错一位：-2.796741545048726e-258 输出相邻 binary64 的文本；wasm-rounding-red-xml 保留该 12 项中 1 项失败，JS 同轮 12 项全零。新精确解析以平台近似仅作候选，复用有界二进制中点及 ties-to-even；邻位失败后完整单调二分。极端指数根据原系数／指数直接判定，零保留符号，比较不展开指数或溢出 Long，只接 JSON 文本与账户真假。独立 CPython bits 回归覆盖 10 个固定输入、18 个有符号中点及两侧、24 个错误／缺失近似候选和巨大指数；Float formatter 的原精确位模式向量保持。ledger-accounts-float-parse-red.log 仅记录新 helper 缺失编译失败。

ledger-accounts-float-correction-jvm.log 30.64 秒成功：domain 的 LocalCashAccountCatalog、PythonJsonText、PythonFloatText 三类十一项及 backend 原 PythonStringGolden、SharedPythonJsonTextParity 两类四项均 0 fail／error／skip。fresh XML 为 ledger-accounts-float-correction-{jvm,native}-xml。ledger-accounts-{sqlite,postgres}-final.log 分别 7.15／8.18 秒成功，显式 :backend:migrationEvidenceTest 选择 AccountListMigrationTest 和 LocalCashLedgerCatalogParityTest，各两类四项全零；后者新增 11 种原存币种形状的 AccountService.list 与完整快照本地结果对照，使用独立合成工作区并在 finally 删除、恢复 fixture-a，不修改原夹具期望。FT_KOTLIN_CONTRACT_DIR 分别为仓库外 sqlite／postgres-5432，PG 同时显式 FT_KOTLIN_TEST_DB_USER=huangwenlong 和 FT_TEST_POSTGRES_URL=postgresql+psycopg://huangwenlong@127.0.0.1:5432/ft_offline_ledger_1104_test；未启动或自动探测数据库。fresh XML 为同名 -xml。

ledger-accounts-final-platforms.log 在同一终态工作树串行执行 domain JVM／JS Node／Wasm Node 四类各十八项（账户、目录、完整 JSON 文本、原浮点格式），shared Android host 四类五十一项及 Chrome JS／Wasm 各五类六十四项，全部 0 fail／error／skip；同时编译 Android Main、iOS Arm64 与 iOS Simulator 测试。host／browser 选择 LocalCashLedgerOwner、LedgerSessionCacheOwner、LedgerSessionRestoration、LedgerOfflineSessionFallback，浏览器另加 WebLedgerSessionRegistration；真实默认工厂加密冷重开在原生 fetch 拒绝后读取账户、目录和分页，正式清理仍核文件／钥／租约消失。fresh XML 及逐任务时间戳集中于 ledger-accounts-final-platform-xml，不能用旧失败 XML 或仅 BUILD SUCCESSFUL 作通过证据。

完整矩阵 242.69 秒成功，沿 --max-workers=1、--no-daemon、--no-configuration-cache、--no-build-cache、-Pkotlin.compiler.execution.strategy=in-process、-Dorg.gradle.jvmargs=-Xmx2048M（另含 UTF-8）、NODE_OPTIONS=--max-old-space-size=768、-Pkotlin.incremental.js=false、-Pkotlin.incremental.wasm=false 和既有 ChromeHeadless init；实际任务为 :business-domain:jvmTest／:business-domain:jsNodeTest／:business-domain:wasmJsNodeTest、:shared:testAndroidHostTest、:shared:compileAndroidMain、:shared:compileKotlinIosArm64、:shared:compileTestKotlinIosSimulatorArm64、:shared:jsBrowserTest／:shared:wasmJsBrowserTest，各 --tests 过滤对应上段类名；本片的实际命令及输出由同名日志和 XML 对应，OpenSpec strict／diff 检查在提交前执行。采样进程树 RSS 峰值 5,112,217,600 字节、单进程 3,202,596,864 字节，可能重复共享页，属于构建与 Chrome 测试，不是产品内存；未并行模拟器。结束后先确认无活动 Karma，仅清理本任务两个临时 webpack 目录 127,481,140 字节，外部 manifest 保留。

独立 5a／实际独立 5b 已复核账户范围、原兼容文本、冻结数据、三层读取门及新精确解析的中点／符号／溢出／二分和资源边界；独立 CPython 只读核验 18 个边界向量一致，静态无新增 finding。最终运行证据及同组文档已完成独立复核，无遗留 finding。Cross-platform Impact Check：三端共享账户及文本，backend 保持同一响应合同；无组件、布局、交互、路由或用户文案变化，不新增 UI 复用／Hallmark 或产品页面冒烟。原生编译不冒称设备运行，内部浏览器组合不冒称产品离线可用。D4／D10、README、三份功能地图、词表和 apply 指引同组核对；无新领域概念、产品取舍、通用规则或数据迁移，不归档或发布，整体任务保持未完成。

### 本地流水类型与分类目录读取验证

比较基线／验证 HEAD：0c39eb2b 加本片工作树，2026-11-07（本地）。原 LedgerOptionsService 的全部流水类型／子类型／关系标签与生成顺序移到 business-domain 的 buildCashLedgerOptions，backend 薄委托保留原 requireContext 和公开合同。shared 的本地浏览、固定身份 owner 和单次恢复分别复用原 readSnapshot／read／readReady 门，分页、分类及类型目录均核完整事实、原身份、凭据、代次及关闭状态；目录不发请求、不创建投影索引或资格副本。分类目录使用完整事实、原树序和名称路径，每项 revision 取 category_directory_revision，保留实际 depth 与 Long sortOrder.toInt 转换，未把未使用的行版本或存储路径误作公开字段。

ledger-catalog-red.log 先复现共享目录入口缺失的编译失败，owner-red.log 再复现三层目录读取入口缺失，不计运行红灯。common 六项新规则测试覆盖原完整类型 golden、独立返回列表、目录完整树序／名称路径／全局版本、空目录、原 sortOrder 窄化及坏数值拒绝。LedgerOptionsGolden.kt 的 3,801 字节内容提交前与既有 backend python-ledger-options.json 逐字比对一致，没有修改原 golden。新增 owner 回归验证缺完整事实不返回目录、读取不发 HTTP、关闭后拒绝；另以合成 Cache 的阻塞读取验证两类目录在 close 胜出后均不能返回，不冒称真实 SQL 关闭故障。原身份丢失、认证拒绝及真实加密冷恢复用例分别补目录断言。

独立 5a／实际独立 5b 发现并关闭 P2：新 LocalCashLedgerCatalogParityTest 依赖外部夹具，却未标历史证据分类；已沿既有 LedgerOptionsMigrationTest 添加 HistoricalMigrationEvidence。显式使用 :backend:migrationEvidenceTest，不能放进默认无夹具的 :backend:test 或用默认排除当通过。ledger-catalog-{sqlite,postgres}.log 使用已准备的各 FT_KOTLIN_CONTRACT_DIR，选择上述两类，各四项均 0 fail／error／skip；完整 HTTP golden／鉴权与三类既有身份的 snapshot 分类目录对照 ReadServices 原读，未新建数据或启动数据库。PG 显式 FT_TEST_POSTGRES_URL 指向 ft_offline_ledger_1104_test，FT_KOTLIN_TEST_DB_USER=huangwenlong；SQLite／PG 分别 54.11／7.17 秒成功，fresh XML 位于同名 -xml 目录。

ledger-catalog-core-host-platforms.log 使用公共单 worker／2 GiB／非 daemon／in-process 参数、768 MiB Node、关闭 JS／Wasm incremental，执行 business-domain 的 JVM／JS Node／Wasm Node CashLedgerCatalogTest、CategoryMoveMigrationTest、LocalCashLedgerQueryTest，各三类十九项全零；shared Android host 的 LocalCashLedgerOwnerTest、LedgerSessionCacheOwnerTest、LedgerSessionRestorationTest、LedgerOfflineSessionFallbackTest，四类五十一项全零，并编译包含新目录并发用例的 iOS Simulator 测试，74.47 秒成功。此前 owner-host.log 的相同生产源码已执行 Android Main／iOS Arm64／iOS Simulator 测试编译及四类五十项 host 回归，67.36 秒成功；后续仅增加目录专项测试，不用旧五十项代替当前五十一项。fresh 终态 XML 为 ledger-catalog-final-core-xml/<模块>/<任务>/。

ledger-catalog-browser.log 沿 ChromeHeadless init 和上述公共参数串行执行 shared 两目标，选择上述四类及 WebLedgerSessionRegistration，每目标五类六十四项全零，84.78 秒成功。实际默认入口的原生 fetch 拒绝后加密冷重开再读取目录，正式 common 清理后仍核密钥／文件／租约消失；该浏览器内部组合不是产品页面 QA。fresh XML 为 ledger-catalog-{jsBrowserTest,wasmJsBrowserTest}-xml。core-host／browser 采样进程树 RSS 峰值 3,100,934,144／4,809,179,136 字节，可能重复共享页，非产品内存，未并行模拟器。

独立 5a／实际独立 5b 复核标签、目录版本／路径／排序、共享读取围栏、异常、测试分类与复杂度，P2 关闭后无新增 finding。Cross-platform Impact Check：三端共用纯目录及内部读取，backend 返回相同标签；无组件、布局、交互、路由或用户文案变化，不新增 UI 专项、Hallmark、产品页面冒烟或原生设备 UI QA。D4／D10、README、总览／共享／现金账本功能地图、词表及 apply 指引同组核对；README 将内部恢复长段拆为身份、定位及读取职责，并链接正式协议，保留当前页面仍在线的边界。没有新用户产品选择或通用规则，无数据迁移，可独立回退。本目录切片不作为账户读取证据；应用生命周期、跨范围清理和页面仍未接线，7.2 及整体不勾选；strict、golden 字节及含新增文件 diff 在提交前检查。

### 应用默认平台存储入口验证

比较基线／验证 HEAD：2ce74689 加本片工作树，2026-11-07（本地）。PlatformLedgerStorage 以 expect／actual 直接委托原 Android／Apple／Web 正式工厂，打开必须提供 scope／existingOnly／registration，独立清理返回原 LedgerCacheErasure；不新增驱动、资源状态或默认材料。LedgerSessionRestoration 默认函数引用改用该入口，显式注入保持。Android 复用 MainActivity 在非演示 setContent 前安装的 applicationContext，缺初始化明确失败，不猜 Context 或换目录。现有真实 Web 冷恢复回归去掉测试工厂 lambda，实际消费默认入口，再通过 common 清理桥核文件、密钥及租约消失；取消交付回归继续保留显式屏障，不为薄委托新增镜像测试。

platform-ledger-entry-host-native.log 沿公共单 worker／2 GiB／非 daemon／in-process 参数，执行 :shared:testAndroidHostTest 的 LedgerSessionRestoration、LedgerSessionCacheOwner、LedgerOfflineSessionFallback，fresh 三类四十一项全零，并编译 Android Main、iOS Arm64 与 iOS Simulator 测试，67.42 秒成功。platform-ledger-entry-browser.log 沿既有 ChromeHeadless init、768 MiB Node、关闭 JS／Wasm incremental 的公共参数串行执行两目标，选择上述三类及 WebLedgerSessionRegistration，每目标四类五十四项均 0 fail／error／skip，86.18 秒成功。fresh XML 为仓库外 platform-ledger-entry-host-xml 及 platform-ledger-entry-{jsBrowserTest,wasmJsBrowserTest}-xml。host／browser 采样进程树 RSS 峰值 2,765,160,448／4,581,244,928 字节，可能重复共享页，非产品内存，未并行模拟器。

独立 5a／实际独立 5b 完成窄范围契约与工程复核，无 finding；Android 既有初始化次序、hierarchy actual、参数透传、默认函数引用和错误传播已核对。Cross-platform Impact Check：三端默认内部入口变化，Web 实际运行，原生相称编译；Android／iOS 新薄桥没有实际设备运行证据，不冒称通过。无 UI、路由、文案或组件变化，不追加页面冒烟、设备 UI QA 或 Hallmark；服务端 Application Service／schema、公开 DTO 和依赖未改，不重复双库。D10、README、两级地图、词表及 apply 指引同组核对，未新增领域概念或产品取舍，无需新通用规范，无数据迁移，可独立回退。Graph、跨范围清理及应用最终发布仍待接线，整体任务不勾选；strict 与含新增文件 diff 在提交前检查。

### 单次认证及账本恢复组合验证

比较基线／验证 HEAD：4370829b 加本片工作树，2026-11-07（本地）。LedgerSessionRestoration 固定原 ApiCredentialSnapshot 和目录 ticket，每对象只执行一次严格会话恢复。在线成功且有有效活动工作区时刷新完整事实并发布绑定身份；只有该认证请求的已识别网络失败才按 current 打开已有库。无活动工作区直接返回服务端身份但仍核原令牌、代次与完成围栏，不创建账本。版本／快照、目录及缓存故障不转为旧身份成功。状态只记录 Initial／Running／Ready／Closed，不复制身份或缓存资格；Ready 前不读页，之后仍由固定 owner 逐页核绑定身份及当前凭据。

实际 owner 在异步开库前由恢复对象强持有。关闭先原子封闭状态，不等待认证或账本 HTTP，再关闭已持有的原 owner；迟到响应不能再取得 owner 或把 Closed 改成 Ready。失败及取消后不能重跑本次恢复，但同一资源保留到明确关闭成功，关闭失败可重试。资源互斥不覆盖网络。该内部对象尚未替代应用全生命周期、跨范围撤权枚举或最终 UI 状态围栏。

ledger-restoration-red.log 先出现缺入口及星号导入歧义的编译失败；修为显式 contracts 导入，不计运行红灯。host-first.log 的四十一项一失败来自新测试错误预期 IOException，实际既有 KtorLedgerSnapshotSource 已包装为保留原 I/O 的 DomainFailure；仅修测试为原 network_unavailable 及 cause，并核不读旧身份，不能称生产回退缺陷。当前十四项新 common 回归覆盖离线定位／分页、在线完整刷新、HTTP 拒绝、无 current、无绑定身份、非法工作区、无活动工作区、认证中关闭、迟到登录／代次、取消交付／关闭失败重试、单次执行、快照 I/O 不回退及无工作区迟到围栏。

ledger-restoration-host-native.log 沿公共单 worker／2 GiB／非 daemon／in-process 参数，执行 :shared:testAndroidHostTest 的 '*Api*Test*'、LedgerSessionRestoration、LedgerSessionCacheOwner、LedgerOfflineSessionFallback、LedgerCredentialSnapshot，fresh 十七类一百五十五项均 0 fail／error／skip；并编译 Android Main、iOS Arm64 与 iOS Simulator 测试，63.27 秒成功。ledger-restoration-browser.log 使用既有 ChromeHeadless init、768 MiB Node、关闭 JS／Wasm incremental 和公共参数串行运行两目标，每目标九类九十项全零，85.12 秒成功。fresh XML 保存于仓库外 ledger-restoration-final-host-xml 及 ledger-restoration-final-{jsBrowserTest,wasmJsBrowserTest}-xml；过滤在上一片八类上加入 LedgerSessionRestoration，WebLedgerSessionRegistration 增至十三项。host／browser 采样进程树 RSS 峰值 2,689,695,744／4,815,388,672 字节，可能重复共享页，不是产品内存，未并行模拟器。

两项新增 Web 组合使用正式 SQLDelight／加密 OPFS 工厂、真实目录 CAS、既有令牌绑定记录与本客户端原生 fetch 坏端口拒绝：正常保留关闭后 existingOnly 冷重开，返回原身份和本地页；工厂已成功返回后的包装器交付屏障取消，实际租约持续持有至恢复对象明确 close。前置缓存为合成数据直接发布，不冒称在线真实服务；坏端口不证明物理断网，交付取消不冒称 SDK 内部开库取消。清理在 finally／NonCancellable 执行，未改真实财务数据或浏览器配置。

独立 5a／实际独立 5b 复核异常来源、原凭据、状态 CAS、资源所有权、取消、关闭重试及复杂度，无新增 finding；Ready 的未使用身份副本已删除。Cross-platform Impact Check：三端共享内部恢复组合，原生相称编译与 host 回归、Web 两目标实际加密存储验证；未接 Graph，不改变页面、文案、路由或组件，不重复产品页面冒烟或原生设备 UI QA。服务端 Application Service／schema、公共 DTO 和依赖未改，不重复双库。D10、README、两级地图、词表及 apply 指引同组核对；没有新用户产品取舍，无需新增通用规范，无数据迁移，可独立回退。整体 5.3／5.4 和全变更仍未完成，不归档或发布；strict 与完整 diff 检查在提交前执行。

### Web fetch 传输来源识别验证

比较基线／验证 HEAD：0715815d 加本片工作树，2026-11-07（本地）。Web 默认客户端用公开 fetch hook 记录本客户端原 Promise 的拒绝对象，私有 WeakSet 不修改或强持有该对象；同步委托异常和非对象拒绝不标记。请求边界只匹配已核 Ktor 3.6.0 JS／Wasm 的直接异常桥，先保留 ApiFailure 及真正取消，再把已识别传输失败转成保留原因的 IOException。没有按文案、TypeError 或任意 cause 链猜测；Android／iOS 及注入的 MockEngine 不装此 hook。浏览器不能由 fetch 拒绝区分断网、DNS、TLS、CORS 和浏览器策略，不宣称测得物理断网。

web-session-transport-red.log／双端 XML 先以四项两失败复现实际 fetch 拒绝无法进入恢复／写入传输分支。storage-red.log／双端 XML 的六项一失败又复现通用 cause 扫描把凭据仓储沿用的旧网络原因误判为新传输故障；当前已收窄至直接 SDK 桥，ApiFailure 优先。六项新回归覆盖真实坏端口 fetch、普通写入不确定性、Karma 实际可读 HTTP404、MockEngine 文案伪造、其他客户端旧对象及同客户端凭据仓储旧原因。最后一项的401是测试临时替换 fetch 返回的 Response，不冒称真实服务器401；finally 恢复原 fetch。真实坏端口不需后端或外网。

web-session-transport-browser.log 的 Wasm fresh XML 八类七十四项全零；该轮 JS 中止后无 XML，不计通过。随后 final-js.log 仅 UP-TO-DATE、仍无 XML，亦不计验证。js-executed.log 显式对 jsBrowserTest 使用 --rerun，13.29 秒完成，同八类七十四项全零；fresh XML 保存为 web-session-transport-final-{js,wasm}-xml。任务筛选为 WebSessionTransportFailure／LedgerOfflineSessionFallback／FinanceApiClient／FinanceApiCredentialLifecycle／AuthenticatedApiSession／LedgerSessionCacheOwner／LedgerCredentialSnapshot／WebLedgerSessionRegistration，沿现有 ChromeHeadless init、单 worker、2 GiB Gradle、768 MiB Node、关闭 JS／Wasm incremental 的公共参数串行运行。

web-session-transport-host-app.log 执行 Android host 的 '*Api*Test*'、LedgerOfflineSessionFallback、LedgerSessionCacheOwner、LedgerCredentialSnapshot，fresh 十六类一百四十一项全零，并编译 Android Main、iOS Arm64、iOS Simulator 测试和非演示 Wasm 开发 Webpack，103.13 秒成功。QA 构建仅以 FT_API_ORIGIN=http://127.0.0.1:9 产生可重复 fetch 拒绝，不改仓库配置或默认服务地址。采样进程树 RSS 峰值为 3,681,583,104 字节；显式 JS 补跑为 3,923,034,112 字节，可能重复共享页，不是产品内存，未并行模拟器。

Web 产品冒烟使用安装的 Google Chrome、Playwright、http://127.0.0.1:5193/、390×900，实际键入合成邮箱与密码并点击登录；网络错误显示原有提示，邮箱保留、提交按钮恢复，无 pageerror。实际请求以 ERR_UNSAFE_PORT 失败，控制台有预期网络错误／network_unavailable 诊断，以及既有 Wasm memory API 弃用提示，不能写成控制台零错误。7.6 秒通过；截图、diagnostics.json、外部 QA 脚本保存在仓库外 web-transport-product-smoke/results。该冒烟验证网络错误呈现，不代表应用已能离线启动或读取缓存。

独立 5a／实际 5b 复核来源隔离、取消、HTTP／仓储错误优先、原写入不确定性及复杂度，已关闭上述 P2；未覆盖真实响应流中断、显式浏览器 Abort 和同步 fetch 委托抛错，不用现有样本宣称覆盖。Cross-platform Impact Check：Web 两目标启用来源适配，原生为空实现且编译／host 回归；现有登录反馈分类受影响并已真实冒烟，无组件、布局、token 或用户文案变更，不增加原生设备 UI QA。服务端 Application Service／schema 未改，不重复双库。D10、README、两级地图、词表与 apply 指引同组核对；沿既定离线恢复边界，不新增通用规范或用户产品取舍，无数据迁移。本片可独立回退；Graph／SessionRepository、跨范围清理及页面缓存仍未接通，5.3／5.4 保持未完成。strict 与含新增文件 diff 在提交前检查。

### 严格会话离线恢复策略验证

比较基线／验证 HEAD：32359720 加本片工作树，2026-11-07（本地）。新增 restoreSessionWithOfflineFallback 使用原凭据只做一次 session HTTP；仅已识别传输 IOException／请求超时调用原 owner 的恢复读取。成功认证、匿名凭据、HTTP／协议拒绝、令牌仓储故障、程序错误及取消不回退。无合格缓存抛原网络失败；本地读取故障仍为主，保留网络 suppressed，不自动重试或丢失资源。严格会话内部选项才启用流式 execute、先记录拒绝状态，并在 SDK cleanup／超时解包后核真正取消和已知 I/O；默认请求仍为原 saved-response，普通写入正文丢失保持原不确定性，原公开恢复重试与收据字段不变。坏包诊断保留原 cause。

仓库外 ledger-offline-fallback-red.log 为缺入口编译红灯。rejection-red.log／xml 九项一失败，复现先403／坏包后I/O误返回缓存；body-red.log 为 ByteChannel close(Throwable) 夹具编译错误，不计运行，修为 cancel 后 body-red-current.log／xml 十项两失败，复现已知拒绝响应正文断开仍返回缓存。scope-red.log／xml 十四项一失败，复现普通 POST503 坏正文被错误改判 ApiFailure；同轮 CE(timeout) 正文及真正调用方取消均通过，不虚称新漏洞。当前十四项涵盖上述边界、匿名、原身份／地址、两类网络故障、本地损坏及本地 I/O／取消、原网络诊断与仓储故障，均已转绿。

host-final.log 按公共 2 GiB／单 worker／非 daemon／in-process 参数运行 :shared:testAndroidHostTest，选择 '*Api*Test*'／LedgerOfflineSessionFallback／LedgerSessionCacheOwner／LedgerCredentialSnapshot，并编译 iOS Arm64、iOS Simulator 测试及 Android Main，63.27 秒成功；fresh host-final-xml 十六类共一百四十一项全零，包括原导入、维护、创建、解析、收据／核实及凭据协议。browser.log 沿既有 ChromeHeadless init、768 MiB Node、关闭 JS／Wasm incremental 及公共参数顺序执行两目标，选择 FinanceApiClient／FinanceApiCredentialLifecycle／AuthenticatedApiSession／LedgerOfflineSessionFallback／LedgerSessionCacheOwner／LedgerCredentialSnapshot／WebLedgerSessionRegistration，83.63 秒成功，fresh jsBrowserTest-xml／wasmJsBrowserTest-xml 每目标七类 9＋9＋6＋14＋13＋6＋11 共六十八项全零。真实 OPFS／加密存储重开用例另组合单次合成认证 I/O→原 owner 读取，确认返回原身份，额外只发一次认证请求。HTTP 故障仍来自 MockEngine，不冒称真实 fetch 已适配。host／browser 采样进程树 RSS 峰值 2,760,785,920／4,801,708,032 字节，可能重复共享页，没有并行模拟器，非产品指标。

独立 5a 及实际独立 5b 已发现并推动关闭重试／正文两处 P2 和普通写入范围回归；复核实际红灯、严格会话专用选项、SDK3.6.0 streaming／saved 路径与取消解包、原401 compare-clear、资源释放及诊断。终态日志和同组文档提交前复核。Cross-platform Impact Check：共享内部 HTTP／恢复策略，三端编译及 Web 两目标回放；没有 UI、交互、文案、服务端 Application Service／schema、新依赖或 wire，不追加产品页面冒烟、原生设备 UI 或双库。D10、缓存 delta、README、两级地图、词表及 apply 指引按实际范围核对；无新用户决定或应升为通用规范的实现偏好，无数据迁移，可独立回退。原生浏览器 fetch 的来源识别仍待实现，已核实际 Ktor3.6.0 源码的 Error／JsError 包装，不能只用 Mock I/O 或错误文案宣称网页断网恢复；Graph／SessionRepository、跨范围清理及页面仍未接线，5.3／5.4 不勾选，不归档或发布。strict 与含新增文件 diff 检查在提交前执行。

### 固定范围会话缓存 owner 组合验证

比较基线／验证 HEAD：25f617fb 加本片工作树，2026-11-07（本地）。LedgerSessionCacheOwner 只服务固定凭据／目录代次／范围，组合平台工厂、LedgerStorage 与原 LocalCashLedgerOwner。恢复仅 existingOnly；在线刷新网络不占资源互斥，事实完整落盘后才保存身份并发布 current。开库在 NonCancellable／resourceMutex 内把返回的实际资源立即存为强引用；关闭先封闭在途 open，再等待原浏览 owner 的关闭或已选擦除，失败保留同一资源供重试。读取前后核当前令牌及代次，每页重新核 token 绑定身份，不保存重复 eligible 状态。不判断认证失败回退，不代替跨范围撤权枚举及总应用生命周期。

仓库外 `ledger-session-owner-red.log` 是入口缺失的编译红灯。`eligibility-red.log`／red-xml 为十三项两失败：缺恢复身份，以及恢复后身份丢失，仍能取得旧页；补每页身份资格门后该 P2 关闭。`host-current.log` 按公共 2 GiB／单 worker／非 daemon／in-process 参数执行 :shared:testAndroidHostTest，选择 SessionCacheOwner／SessionPublication／LocalCashLedgerOwner／LedgerSnapshotSync／OfflineSessionStorage，并编译 iOS Arm64、iOS Simulator 测试及 Android Main，54.09 秒成功；fresh host-current-xml 五类 13＋14＋8＋16＋8 共五十九项全零。common 假存储只证明取消／关闭／失败保留、旧身份拒绝及锁顺序，不冒称平台加密。

`browser.log` 沿既有 ChromeHeadless init、768 MiB Node、关闭 JS／Wasm incremental 及公共参数顺序执行两目标，选择 SessionCacheOwner／SessionPublication／LocalCashLedgerOwner／LedgerSnapshotSync／WebSessionRegistration，82.64 秒成功；包含匹配到的 WebLedgerSnapshotSync 三项，每目标六类 13＋14＋8＋16＋11＋3 共六十五项全零，fresh XML 为 jsBrowserTest-xml／wasmJsBrowserTest-xml。新增真实组合沿默认 Ktor source 进行两次合成 HTTP 请求、正式 OPFS／密钥／SQL 刷新及身份发布，preserve close 后新代次 existingOnly 重开，旧身份及本地页相等且无额外 HTTP；换令牌后拒页，重复旧 close 不影响新租约。另一项在真实工厂返回资源的交付包装处挂起并取消，放行后原 owner 仍持实际租约，显式 close 才释放；该证明不等于 SDK 内部开库取消。两用例 finally 不可取消地放行／关闭并独立擦除测试范围。host／browser 采样进程树 RSS 峰值 2,616,639,488／4,843,339,776 字节，可能重复共享页；没有并行模拟器，非产品性能基准。

独立 5a 及实际独立 5b 已复核资源交付、锁序、失败所有权、身份资格和真实 Web 用例，P2 已关闭，无其他静态 finding；终态证据与同组文档在提交前复核。Cross-platform Impact Check：内部共享资源组合，三端编译及 Web 两目标回放；无 UI、交互、文案、服务端写入／schema、新依赖或 wire，不追加产品页面冒烟、原生设备 UI 或双库。D10、缓存 delta、README、两级地图、词表、apply 指引按实际接入核对；无新产品决定、领域术语或应升为通用规范的偏好，可独立回退，无数据迁移。Graph／SessionRepository、网络失败回退及页面仍未接线，5.3／5.4 不勾选；strict 与含新增文件 diff 检查提交前执行，不归档或发布。

### 登录响应与原凭据组合验证

比较基线／验证 HEAD：1de1d80d 加本片工作树，2026-11-07（本地）。内部 `AuthenticatedApiSession` 保留同次认证响应的 SessionDto／access_token 及原请求地址；authenticate 在请求前捕获身份，request 显式使用该快照，保存响应令牌成功后返回 pair，不重读后续令牌。公共 login／register 仍返回原 SessionDto；原请求读令牌一次，401 比较删除、logout 和凭据锁时序不变，网络不占锁。它是认证响应与当前凭据的必要隔离，不新增持久资料或服务端 wire。

仓库外 `authenticated-api-session-red.log` 先复现两个内部入口缺失，仅为编译红灯。六项新增回归覆盖两次顺序登录后原 pair 保持、注册、地址在请求中改变及后续固定请求、原 pair 在新 token 下发布先于存储被拒、503／坏回包不保存令牌、保存故障保留原原因；双登录用例不宣称并发顺序已协调。`host.log` 按公共 2 GiB／单 worker／非 daemon／in-process 参数运行 :shared:testAndroidHostTest，选择 AuthenticatedApiSession／FinanceApiCredentialLifecycle／LedgerCredentialSnapshot／LedgerSessionPublication，并编译 iOS Arm64、iOS Simulator 测试及 Android Main，61.29 秒成功；fresh host-xml 四类 6＋9＋6＋14 共三十五项全零。`browser.log` 沿既有 ChromeHeadless init、768 MiB Node、关闭 JS／Wasm incremental 及公共参数顺序执行两端相同四类，77.55 秒成功，各三十五项全零，fresh XML 分别为 jsBrowserTest-xml／wasmJsBrowserTest-xml。host／browser 采样进程树 RSS 峰值 2,670,788,608／4,088,561,664 字节，可能重复共享页；未并行原生模拟器，非产品性能指标。

独立 5a 及实际独立 5b 静态复核无 finding，核原响应来源、地址／令牌捕获、公开时序及现有回归边界；终态日志／XML 与文档在提交前复核。Cross-platform Impact Check：仅共享认证数据来源，三端相称编译及 Web 两目标回放；没有 UI、交互、文案、服务端写入／schema 或新依赖，不追加产品页面冒烟、设备 UI 或双库。D10、README、两级地图、词表、apply 指引及原认证契约已核对；无新用户决定或应升为通用规范的偏好，无数据迁移，可独立回退。恢复 owner、网络失败回退及页面仍待接线，5.3／5.4 不勾选；strict、含新增文件的 diff 检查提交前执行，不归档或发布。

### 已核验身份发布组合验证

比较基线／验证 HEAD：f3cc59c8 加本片工作树，2026-11-07（本地）。`publishVerifiedLedgerSession` 在调用方已持有的同一范围租约上，按取得 SessionDto 的原凭据核令牌和目录代次，先保存完整事实上的绑定身份，再发布 current，最后读回存储／current／代次并核原令牌。凭据互斥及不可取消区间等待真实 SQL／CAS；失败保留存储所有权及原异常，由恢复 owner 收尾。外部实例令牌变更靠最终核对拒绝，不宣称跨存储事务或应用已接线。

仓库外 `ledger-session-publication-red-current.log` 在修正测试 DTO 导入及返回类型后仅因缺失发布入口编译失败，不冒称运行时红灯。十四项新 common 回归覆盖正常顺序、取消待锁、同客户端换令牌被阻挡、token-store／保存／CAS 失败、外部换令牌、旧代次和关闭存储；假存储只验证协议。SQL host 新用例用真实 SQLite 验完整事实资格及保存后发布，不冒称平台加密。`host.log` 原四十六项全零；最终 `host-current.log` 以公共单 worker、2 GiB、非 daemon、in-process 参数运行 `:shared:testAndroidHostTest`，选择 Publication／OfflineSessionStorage／Directory／StorageId／ApiCredentialLifecycle，并编译 iOS Arm64、iOS Simulator 测试及 Android Main，28.59 秒成功，fresh `host-current-xml` 为 14＋8＋9＋2＋9 共四十二项全零。原凭据快照六项在初次 host.log 验证，未改其生产协议，不冒称最终命令又执行了该类。

真实 Web 新增两项使用正式加密工厂、OPFS 文件及实际目录 WebLock：发布后 preserve close／existingOnly 重开能读原绑定身份；实际 CAS 被锁住时取消仍等待，凭据写入不可越过，放行后才完成。`browser.log` 以 ChromeHeadless、既有 init、768 MiB Node、关闭 JS／Wasm incremental 及公共 Gradle 参数顺序运行两目标，选择 Publication／WebRegistration／Directory／StorageId，64.31 秒成功；每目标五类共三十七项全零，fresh XML 为 `jsBrowserTest-xml`／`wasmJsBrowserTest-xml`。首次失败源于 JS 将旧测试孤立代理常量折叠为问号，第二次显示 toChar 常量仍被折叠；两份失败日志和 XML 分别保留为 `browser-first`／`browser-fixture-second`。最终两处测试改用运行时字符数组，并先核真实码值；拒绝要求不变。host／browser 进程树采样 RSS 峰值 1,939,652,608／4,610,588,672 字节，可能重复共享页；没有并行模拟器，非产品性能基准。

独立 5a 及实际独立 5b 契约复核无阻断 finding，核凭据锁／存储／短 CAS 顺序、最终读回及真实 Web 用例；UI 专项不适用。Cross-platform Impact Check：内部共享发布协议影响三端，已做相称编译及两种浏览器目标回放；无页面、HTTP wire、服务端 Application Service／schema 或依赖变化，不重复产品页面冒烟、设备 UI 或双库。D10、README、两级功能地图、词表及 apply 指引按当前接入范围核对；无新领域概念、产品决定或应升为通用规范的用户偏好。本片可独立回退，无数据迁移；恢复 owner、HTTP 离线回退及页面未接通，5.3／5.4 保持未勾选，不归档或发布。strict 与含新增文件的 diff 检查提交前执行。

### 同步与本地浏览终结验证

比较基线／验证 HEAD：b51d6f69 加本片工作树，2026-11-07（本地）。事实缓存端口的 close 保留文件及钥，LedgerStorage 继承；同步与浏览 owner 使用原子终结模式封闭读取并固定首次动作。owner 的 close 首次选择 PRESERVE，若 clear／撤权已选 ERASE 则只完成原擦除；底层 close 合同未变。CAS 失败后读取不可逆胜出模式，实际资源动作仍共用原互斥及 NonCancellable。显式 clear 不能在旧 PRESERVE 后删除后来新建的缓存；不增加第二个 owner 或 stop API，不猜异常码来决定清理。

ledger-owner-finish-red.log／red-xml 是两项新增协程回归的实际红灯：撤权清理失败后 close 被 mode_invalid 拒绝，以及擦除仍在等待时 close 已提前失败完成。修复后 host.log 按公共 2 GiB／单 worker／非 daemon／in-process 参数执行 :shared:testAndroidHostTest --tests '*LedgerSnapshotSyncTest*' --tests '*LocalCashLedgerOwner*' --tests '*LedgerSyncApiTest*'、:shared:compileKotlinIosArm64、:shared:compileTestKotlinIosSimulatorArm64，54.09 秒成功。fresh host-xml 为同步十六项、浏览八项、API 四项及真实线程交错一项，共二十九项全零。

browser.log 按既有 ChromeHeadless init、NODE_ENV=development、CHROME_BIN、NODE_OPTIONS=--max-old-space-size=768、关闭 JS／Wasm incremental 及公共 Gradle 参数，顺序执行 :shared:jsBrowserTest、:shared:wasmJsBrowserTest；每目标均选择完整类名的 LedgerSnapshotSyncTest／LocalCashLedgerOwnerTest，77.52 秒成功，两目标各十六加八共二十四项全零，fresh XML 分别保存于 jsBrowserTest-xml／wasmJsBrowserTest-xml。host／browser 采样进程树 RSS 峰值分别 2,642,247,680／3,627,499,520 字节，可能重复共享页，不是产品指标；未并行原生模拟器。

新增用例核已选擦除失败后 close 只重试 clear、不调用底层 close，完成后重复 close 不擦 fresh 事实；擦除持锁时 close 等待同一原动作，成功只清一次，finally 放行合成屏障。原普通关闭保留、失败 preserve 固定模式、取消等待真实关闭、晚到 403 拒绝、旧 preserve 后 clear 拒绝、真实线程撤权及原因链／认证诊断回归仍全绿。异常断言保留原对象身份及 suppressed，不仅比较文案；common 同时核类型。

未修改 SDK、Worker、缓存工厂或物理文件／钥协议，本片不重复无变化的系统矩阵。已提交基线 b51d6f69 的 ledger-owner-preserve-host.log 27 项及 APK／iOS 编译、current-system.log Android 二十八项、js.log 真实存储二十六项、js-common.log 二十二项、wasm.log 四十八项全零记录保留；这些是明确的基线证据，不冒称在本片重新运行。其真实 owner 普通 close→existingOnly 重开→本地分页、最终显式清理及 401／403 擦除断言不变。应用初始化、HTTP 回退及页面尚未消费该 owner。

独立 5a 与实际独立 5b 终态复核无遗留 finding；已核六文件增量、两个真实红灯、不可逆 CAS、等待／重试计数、host 二十九项及 JS／Wasm 各二十四项全零、编译和文档边界。strict 与 diff 检查通过。Cross-platform Impact Check：仅共享同步／浏览终结的分支和 common 回归，三端相称编译及受影响逻辑回放；未改 UI、用户文案、服务端 Application Service／schema、依赖或 wire，不追加产品页面冒烟或双库。核对 D10／D14、缓存 delta、README、两级地图、词表及实施指引；地图现有普通关闭／显式擦除的内部状态仍正确，不需重复条目。无新产品决定或通用规范；可独立回退，无数据迁移。应用恢复 owner、联网核验、退出／撤权枚举及页面仍待接线，5.3／5.4 不勾选，不归档或发布。strict 及 diff 检查在提交前执行。

### Apple 普通缓存释放失败重试验证

比较基线／验证 HEAD：c7300e96 加本片工作树，2026-11-07（本地）。新增回归在实际定位 CAS 成功后，按随机根目录及完整范围摘要找到并关闭唯一自有锁 FD，让原 lease.release 真实返回 EBADF；随后创建新的真实加密库、密钥及 current，再重试旧 clear。ledger-apple-release-red-link.log 是测试误把 Unit 当 Boolean 的编译失败，修正夹具后 red-link-current.log 链接成功；red-system.log 和 red-xml 为十九项十八成功、一失败，精确断言 Old cleanup must not delete new files。该红灯证实旧回调会重复删除，不冒称自然出现的内核关闭故障。

修复只在普通 Apple 工厂的文件、密钥及 afterErasure 全部完成后，先设置局部 erasureComplete 再释放租约。Scoped 缓存已有互斥；释放异常后的原实例跳过删除并重报原锁存异常，不重复 close 复用 FD。green-link.log 按公共 2 GiB／单 worker／非 daemon／in-process 参数运行 :shared:compileKotlinIosArm64 :shared:linkDebugTestIosSimulatorArm64，51.02 秒成功；无模拟器并行重构建。随后同前述 iOS 18.3／UDID 在 Keychain 模式、768 MiB Gradle 堆执行 :shared:iosSimulatorArm64Test --device <UDID> --rerun；green-system.log 13.38 秒成功，green-xml 为清理五项、工厂十一项、Keychain 三项，共十九项全零。核新文件仍存在、原新钥摘要／current／可读事实不变及旧释放异常为同一对象；运行后关闭本次模拟器。

独立 5a 与实际独立 5b 已复核真实红灯、故障隔离、标记顺序、复用 FD 风险、当前十九项全零 XML 及文档，P2 已关闭，无遗留 finding。strict 与 diff 检查通过。Cross-platform Impact Check：只修 Apple 内部终结重试，无页面、common 合同、服务端、schema 或依赖变化；不重复无关平台、双库或产品页面验证。核对 D10、缓存 delta、README、两级地图、词表及实施指引；地图现有三端清理能力无需另列此内部缺陷，无新用户决定或规范。本片可独立回退，无数据迁移；应用恢复和页面仍未接线，不勾选整个 5.3／5.4、不归档或发布。strict 与含新增测试的 diff 检查在提交前执行。

### Apple 独立缓存清理验证及收尾证据

比较基线／验证 HEAD：e5ebb100 加本片工作树，2026-11-07（本地）。沿 D10 复用纯范围校验、实际 Apple 文件租约和 Keychain，首次 clear 才持锁，直接删除事实、指定钥及定位；不读取／生成密钥或打开数据库。互斥与不可取消区间覆盖清理，材料与引用完成标记先于释放；失败保留原 owner，完成旧对象不得删除新材料。

仓库外 ledger-apple-erasure-red.log 为缺少新入口的编译红灯，不冒称运行时缺陷。compile-first.log 的 :shared:compileKotlinIosArm64、:shared:compileTestKotlinIosSimulatorArm64 28.59 秒成功；修正空文件夹具为显式删除合成主库后新建空文件，link.log 的 :shared:linkDebugTestIosSimulatorArm64 35.74 秒成功。链接使用既有公共 2 GiB／单 worker／非 daemon／in-process 参数；峰值进程树 RSS 2,746,564,608 字节，可能重复共享页，不是产品内存指标。

实际系统验证在已手动启动的 iOS 18.3／iPhone 16 Pro（ECEE3D47-20FC-4217-A9B2-E7F6C26ECFC8）运行 -PledgerKeychainTests=true :shared:iosSimulatorArm64Test --device <上述 UDID> --rerun，复用预链接二进制、Gradle 堆 768 MiB，其余公共参数不变。system.log 14.44 秒成功，fresh XML 在 system-xml：新增清理四项、原工厂十一项及仓储三项共十八项，零 failure／error／skip；测试后关闭本次启动的模拟器。测试编译与链接先于启动，不并行重构建。首次系统启动前因 ENOSPC 无法创建测量日志，未执行测试；确认无进程占用后清理二十四份本项目 Karma 临时包共 1,534,991,233 字节，精确清单保存于 karma-temp-cleanup-20261107.json，未清理其他临时数据。

四项回归核原文件／原钥不被非法 scope 删除、缺钥／错钥／空主库和孤立日志擦除、其他工作区及 current 隔离、真实 prefs CAS 前注入失败持锁与同对象重试，以及完成旧对象不擦新缓存。真实 OS 删除拒绝、取消和 release 失败本片未专项验证；不将注入元数据失败称为实际文件系统错误。独立 5a 与实际独立 5b 终态复核无实现 finding；已亲核三类十八项实际 XML、编译／链接／运行日志及范围。P3 待办文字残留已原位修正。strict 与 diff 检查通过。

Cross-platform Impact Check：仅 Apple 内部入口、测试与测试模式过滤；Android／Web、common 协议、服务端 Application Service／schema、依赖及页面不变，不重复无关双库或产品 UI QA。README、两级地图、D10、缓存 delta、词表和实施指引按当前接入范围核对；无新用户决定或通用规则，无数据迁移，可独立回退。应用恢复／退出／撤权 owner 和页面尚未接线，5.3／5.4 保持未勾选；strict 及含新增文件的 diff 检查在提交前执行，不归档或发布。

### Web 独立缓存清理验证及收尾证据

比较基线／验证 HEAD：f021883b 加本片工作树，2026-11-07（本地）。沿 D10 已确认 erase-only 接入 Web；工厂先复用目录纯 scope 校验，首次 clear 的不可取消区间才取得并保存同一 Worker 连接。直接 erase／forget 后先标记完成，再最终 close；没有数据库 open 或 SQLDelight 驱动。失败保留同一对象及租约，已完成的旧对象不重新取得连接，最终 ACK 失败后的重试只释放，不擦除新所有者。

ledger-web-erasure-red.log 先复现缺少入口的测试编译失败，不作为运行时财务缺陷。js-first.log 因 ENOSPC 在打包阶段失败，未计浏览器通过；确认无占用后只清理生成中间产物和旧合成 SQLite 探针浏览器资料，保留 SDK／源码、APK、日志及 XML。js-current.log 使用既有公共 2 GiB／单 worker／非 daemon／in-process、ChromeHeadless init、NODE_ENV=development、CHROME_BIN 及关闭 JS／Wasm incremental 参数，运行 :shared:jsBrowserTest --tests '*WebLedger*'，49.09 秒成功，六类二十五方法零 failure／error／skip，fresh XML 在 js-current-xml。

独立 Wasm 首次在浏览器启动前因空间复查停止，记录保留于 wasm-aborted-before-browser.log，不计通过；移除已保存证据的 JS 生成文件后单独重跑。wasm-current.log 执行同模式 :shared:wasmJsBrowserTest，额外 NODE_OPTIONS=--max-old-space-size=768 限制 Node 堆，49.99 秒成功，同样六类二十五方法全零，fresh XML 在 wasm-current-xml。两端各为新增清理五项、原登记七项、原工厂六项、同步两项、目录三项及协议两项。采样进程树 RSS 峰值分别 5,216,305,152／3,930,374,144 字节，共享页可能重复计数，不是产品内存指标；构建和浏览器串行，未并行模拟器。

新增五项真实浏览器回归覆盖：两类非法 scope 的原文件／原钥摘要不变及锁空闲；缺钥、坏主库和孤立日志三种材料完全移除；forget 注入失败持锁至同对象明确重试；真实短元数据 Web Lock 阻塞 CAS 时取消不能提前释放；真实 final close 已释放后注入 ACK 错误，旧重试不擦新实例。故障 Worker 的 armOnCreate 缺省关闭，已有登记测试仍沿原路径。密钥摘要仅用于合成数据比较，原数组擦零；OPFS 测试破坏仅在预置缓存关闭后执行。取得租约时取消和真实系统 OPFS 拒绝本片未专项验证，不将合成 ACK 故障称为实际数据库关闭失败。

独立 5a 工程／安全／输入／异常／取消／复杂度及实际独立 5b 终态复核无遗留 finding；已亲核暂存九文件、两端各六类二十五项当前 XML、成功日志及文档边界，失败／主动停止未冒充通过。Cross-platform Impact Check：仅 Web 内部独立清理和 JS／Wasm 测试资源，Android／iOS 及 common 实现、后端 Application Service／schema、依赖和页面均不变。不追加原生设备、双库或打包验证；协议／存储集成测试不替代应用页面冒烟。

收尾核对 D9／D10、缓存 delta、README、两级地图、词表和实施指引，无新用户决定或应沉淀的通用规则。可独立代码回退，无用户数据迁移。三端独立清理已提供，应用 owner／启动／退出／撤权组合和页面仍待接线，5.3／5.4 不勾选；strict 与含新增文件的 diff 检查在提交前执行，不归档或发布。

### Android 独立缓存清理验证及收尾证据

比较基线／验证 HEAD：f71184ff 加本片工作树，2026-11-07（本地）。D10 先明确固定范围 erase-only，不假造可读 LedgerStorage；共用 LedgerCacheErasure 只提供 clear；本节验证 Android 消费，Web 实现与验证见对应小节。工厂先复用目录纯范围校验，首次 clear 才取得并保存真实租约；同一互斥及不可取消区间按原删除 helper、vault.erase、directory.forget、租约释放执行，不读取或创建数据库密钥、不打开 SDK。失败须保留同一清理对象；已完成的旧对象不重新取得租约或触碰新实例。

ledger-android-erasure-red.log 先复现缺少入口的编译失败，不计作运行时缺陷。独立审查 P2 指出校验晚于删除，已先补原生失败回归：scope-red-apk.log 构建后在实际 API 36／arm64-v8a 执行非摘要 principal 和非规范 origin 两项，scope-red-system.log 为两项两失败、零成功状态，均在原文件已被删除的明确断言失败；APK SHA-256 603299cf9a25d9258a0c7ddc61d8c0f050f21dfaba174f1305e44a7744972c4a。修复仅开放目录原有纯 validateScope 供工厂先调用，不复制或放宽规则；新回归核完整原文件字节、原钥摘要及可再次取得租约。

ledger-android-erasure-current-platforms.log 使用公共 2 GiB／单 worker／非 daemon／in-process 参数，执行 :shared:testAndroidHostTest --tests '*LedgerSessionDirectoryTest*'、:shared:assembleAndroidDeviceTest、:shared:compileKotlinJs、:shared:compileKotlinWasmJs、:shared:compileKotlinIosArm64、:shared:compileTestKotlinIosSimulatorArm64，84.72 秒成功；host 九项零 failure／error／skip，fresh XML 在 current-host-xml。采样进程树 RSS 峰值 2,935,734,272 字节，可能重复共享页，不是产品内存。构建结束后才手动启动自有 API 36／arm64-v8a、1536 MiB／2 核模拟器；current-system.log 为 17.506 秒 OK (27 tests)、二十七成功状态及零失败／忽略。current-result.json 保存六个实际类和 APK SHA-256 b5bb7ef406fe3ddc8d1709c6891f4be0c1c170d48d0518fe817de932e168b373，自有模拟器已关闭。

系统矩阵为新增清理五项、原范围登记五项、原工厂十二项、离线记录一项、同步／浏览两项和目录两项。新测试使用真实 Keystore、SQLCipher 准备的合成旧文件、Android 文件锁和 prefs；元数据失败是 CAS 前明确注入，不是实际磁盘故障。覆盖缺钥、损坏数据库、缺主库的孤立日志、其他工作区／当前引用隔离、forget 失败持锁与同对象重试、完成旧对象不擦新缓存，以及上述两项输入围栏；不将模拟器脚本进程成功单独计作测试通过。

独立 5a 工程／安全／输入／异常／取消／复杂度及实际独立 5b 终态复核已关闭 P2，无遗留阻断；已亲核红灯原始失败、当前二十七项原始成功状态、host 九项、跨端编译、APK 摘要与暂存增量。P3 文档残留已按当前实际平台接入范围原位修正。Cross-platform Impact Check：Android 新独立清理及测试，共享只增加内部资源接口并开放原纯校验；JS／Wasm、iOS 编译该 common 变化，不消费新 Android 路径。无页面、用户文案、后端 Application Service／schema、依赖变化，不追加浏览器产品冒烟、服务端双库或打包验证。

收尾核对 D10、缓存 delta、README、两级地图、词表和实施指引，无新领域概念或通用规则需沉淀。可独立代码回退，无数据迁移。三端独立清理已提供，应用退出／撤权枚举、恢复 owner 和页面仍待接线，5.3／5.4 不标完成；strict 及含新增文件的 diff 检查在提交前执行，不归档或发布。

### Web 工厂范围登记验证及收尾证据

比较基线／验证 HEAD：90f7d801 加本片工作树，2026-11-07（本地）。沿 5.3／5.4 和 D9／D10 的已确认范围登记组合，不新增产品选择。Web 工厂在取得 Worker 前拒绝错范围，实际 acquire 后、open 创建材料前执行可选登记；同一个连接承担关库及擦除，清理引用成功后才最终 close／SDK terminate。打开／登记与擦除／移除均不可取消地等待真实 Promise 完成。文件、钥、引用均移除后，在缓存互斥内先标记擦除完成再释放；最终 ACK 失败后的旧重试不重新擦除新所有者。普通 close 保留引用；可选参数仍须由应用传入。

ledger-web-registration-red.log 先复现新增回归无法编译：生产工厂没有 registration 参数，不将此记作运行时财务缺陷复现。首轮 js-first.log 运行六项新增测试通过，补齐错范围／过期代次后再执行完整 WebLedger 模式。wasm-current.log 使用公共 2 GiB／单 worker／非 daemon／in-process、ChromeHeadless init、NODE_ENV=development、CHROME_BIN 及关闭 JS／Wasm incremental 参数，运行 :shared:wasmJsBrowserTest --tests '*WebLedger*'，49.99 秒成功；js-current.log 独立执行同模式 :shared:jsBrowserTest，29.09 秒成功。两端各五类二十方法为新增登记七项、原工厂六项、同步两项、目录三项及协议两项，fresh XML 分别在 wasm-current-xml 和 js-current-xml，全零 failure／error／skip。采样进程树 RSS 峰值分别为 4,302,028,800／4,178,329,600 字节，可能重复共享页，不是产品内存；两目标串行且未并行运行原生测试。

新增测试使用实际浏览器定位存储，包装只在真实 CAS 前观察或注入故障。两个取消分支用真实同源元数据 Web Lock 阻塞 Promise，确认取消等待后范围仍 busy，再解锁并核对完成后的引用及材料；不以内存 gate 代替存储操作。忘记引用失败时核实际文件／钥已删但租约及引用保留，同所有者重试后新实例仍可读。测试 Worker 在真实最终 close 完成、租约已释放后，仅把 ACK 改为合成错误；新实例写入后重试旧 clear，核对新事实未被擦除。该注入不是实际数据库 close、内核故障或 Worker 进程崩溃；初始化失败加最终关闭双故障未运行专项。

独立 5a 工程／异常／安全／取消／复杂度及实际独立 5b 终态复核完成，无遗留 finding；已亲核新增范围测试、两端当前二十项 XML、日志及文档边界，并沿生成方法、Kotlin 适配器与 Mocha 实现确认返回的 runTest Promise 被等待，不以 XML 的零耗时字段冒称性能指标。Cross-platform Impact Check：仅 Web 工厂可选钩子、JS／Wasm 测试资源消费变化；Android／iOS、共享公共合同、后端存储、依赖和页面均未改变。不重复原生系统、服务端双库或打包验证；真实浏览器测试不代替应用页面冒烟。旧 Node 协议回归对应上片，生产 Worker／controller 本片未变，不冒称重新运行。

收尾核对 D9／D10、缓存 delta、README、两级地图、词表及实施指引，没有新领域名词或应提升为通用规则的用户决定。可独立代码回退，无用户数据迁移。三端独立 erase-only 见本页对应证据，应用 owner／登录／启动恢复及页面仍未接线，5.3／5.4 不勾选；strict 与包含新增文件的 diff 检查在提交前执行，不归档或发布。

### Web 分步租约协议验证及收尾证据

比较基线／验证 HEAD：90dd513f 加本片工作树，2026-11-07（本地）。沿 5.3／5.4 和 D9 组合现有 Worker 资源所有权，未改产品合同。内部 acquire 只持范围租约、不创建事实或钥；close_database 和 erase 均保留租约，由最终 close 释放。已持锁 open 失败进入同所有者可擦除的封闭态，外范围 open 不改原租约。旧单步 open／erase 包装器仍自行最终关闭；取得擦除租约失败也必须禁止后续 open。SQLDelight 2.4.1 的驱动 close 会终止 Worker；本节仅记录分步协议，工厂登记及取消验证见本页 Web 工厂范围登记小节。

ledger-web-lease-protocol-red.log／xml 先复现新增两方法缺少 staged 入口。随后 node-red.log 在真实 Chrome 精确复现三项：非法 null 范围仍创建 Worker、已有库模式失败提前释放租约，以及旧擦除取得失败后仍可 open；16 个 Node 计数含父项，四失败包含三个失败子项和父项。修复后 PLAYWRIGHT_CHROME_PATH 指向本机 Chrome，执行 node --test compose/qa/web/tests/compose-ledger-database.node-test.mjs，node-current.log 为 31.979 秒、15 子项及父项共 16 通过，零失败／取消／跳过。1280×720、临时本机服务地址与浏览器版本写入原日志，pageerror／requestfailed／console error 均空。除新增两项外，保留完整精确 TEXT、活动 DELETE 日志无明文、回滚、真实浏览器重启、脏页中断恢复、错钥／缺钥保文件、关闭 ACK 重试／fatal／实际超时断言。擦除故障先核完整文件摘要不变和原钥保留，再核同所有者重试及擦后仍 busy，最终关闭后新库无旧表。

ledger-web-lease-protocol-current-browser.log 的初次 JS 部分完成但 Wasm 停滞，磁盘近满后已停止该任务，不以此记完整矩阵成功。随后独立 wasm-current.log 使用既有 2 GiB／单 worker／非 daemon／in-process、浏览器 init、NODE_ENV=development、CHROME_BIN、关闭 JS／Wasm incremental 参数，执行 :shared:wasmJsBrowserTest --tests '*WebLedger*'，29.62 秒成功，四类十三方法全部通过，零 failure／error／skip；fresh XML 保存在 wasm-current-xml。随后 JS 初次执行因 ENOSPC 失败，日志保留于 js-enospc.log，清理确认没有打开句柄的再生中间产物后单独重跑相同模式 :shared:jsBrowserTest，js-current.log 为 16.33 秒成功，同样四类十三方法全部通过，fresh XML 在 js-current-xml。两端分别为实际工厂六项、同步两项、目录三项和新协议两项。采样进程树 RSS 峰值分别为 3,580,542,976／2,747,645,952 字节，可能重复共享页，不是产品内存指标；构建及浏览器均串行，没有同时启动模拟器。

独立 5a 工程／资源所有权／异常／安全／复杂度及实际独立 5b 窄增量复核已完成，三项 P2 修复并终态关闭，无遗留 finding；独立复核已亲核暂存增量、当前两端十三项 XML、Node 十六计数原始日志及文档边界。Cross-platform Impact Check：仅 Web 内部协议、测试及其资源配置变化；Kotlin 工厂尚未消费 staged 入口，Android／iOS 运行路径、后端 Application Service／schema、依赖和页面不变。不以真实 Chrome 协议／工厂回归代替应用页面冒烟，不重复原生设备、双库或打包验证。

收尾核对 D9、缓存 delta、README、两级地图、词表与实施指引，无新领域概念或产品选择，无需新增通用规范。可整体回退此代码片，无数据库或密钥迁移。Kotlin 工厂登记及三端独立清理已由本页对应小节补齐，应用恢复／联网核验及页面仍待完成，5.3／5.4 保持未完成；strict 和包含新增文件的 diff 检查在提交前执行，不归档或发布。

### 原生工厂范围登记验证及收尾证据

比较基线／验证 HEAD：d578eb56 加本片工作树，2026-11-07（本地）。沿 5.3／5.4 和 D10 的已确认启动范围引用实施，不扩产品范围。内部 LedgerSessionRegistration 只绑定固定 scope／directory／ticket，不持有资源；工厂在 acquire 前拒绝错范围，真实租约取得后、任何 facts／vault.read 前登记。clear 复用旧缓存的关闭确认和终结围栏，在文件和钥删除后条件 forget，最后释放租约；forget 失败保留原异常、引用、ERASE 围栏和租约，原实例明确重试成功才释放。普通 close 保留引用；完成旧 clear 后重试不接触新实例。可选参数不等于应用已强制传入。

ledger-session-registration-red-build.log 编译成功后，red-system.log 在实际 API 36／arm64-v8a 运行四项全部失败（APK 425585b26558b6743b77106c3fcbdae0827d449bb19b3df4ccbbf189d6330177）：旧工厂忽略登记、登记失败仍造材料、缺少清理登记及过期绑定到达 driver。初始红灯以内存 metadata 观察顺序／注错，不能称真实定位持久化。修复后新增错范围前置拒绝回归，并把成功读写改为真实 AndroidLedgerSessionLocatorStorage；包装器只在 CAS 前观察或注入故障，driver 打开前读取实际 XML，确认范围已提交且无原邮箱。写失败仍为明确注入，不是实际磁盘故障、进程崩溃或重启验证。

ledger-session-registration-platforms.log 使用既有 2 GiB／单 worker／非 daemon／in-process 参数及 cash-write-set-lint.init.gradle，执行 :shared:testAndroidHostTest --tests '*LedgerSessionDirectoryTest*'、:shared:assembleAndroidDeviceTest、:shared:compileKotlinIosArm64、:shared:compileTestKotlinIosSimulatorArm64、:shared:compileKotlinJs、:shared:compileKotlinWasmJs，69.38 秒成功；host 九项与当前目录实现保持一致。改进 Android 测试后仅 :shared:assembleAndroidDeviceTest 再构建，current-apk.log 11.22 秒成功。iOS 新钩子完成主工程及测试编译／独立静态复核，本片未运行 Keychain／Apple 工厂系统回归。

python3 run-android-ledger-system.py ledger-session-registration-current 后列出五类：AndroidLedgerSessionRegistrationTest、AndroidLedgerFactCacheTest、AndroidLedgerOfflineSessionTest、AndroidLedgerSnapshotSyncTest、AndroidLedgerSessionDirectoryTest。API 36／arm64-v8a 实际二十二项组合通过：新增登记五项、原工厂十二项、离线恢复记录一项、版本同步／本地浏览两项及真实定位存储两项。current-system.log 为 15.124 秒 OK (22 tests)、二十二成功状态及零失败／忽略；current-result.json 保存 APK SHA-256 17d14430b8d8de0570df89860faa10c104feab6c2c5451dd51c735b27d2fef6b。自有模拟器已关闭，未与 Gradle 并行。Web 工厂未消费新绑定对象，运行路径与 d578eb56 相同，只做新 common 文件的 JS／Wasm 编译，不重复未受影响的浏览器矩阵，不以旧结果宣称新执行。

独立 5a 工程／资源所有权／取消／异常／复杂度及实际独立 5b 窄增量复核无阻断；已复核真实 prefs 包装与错误注入边界。Cross-platform Impact Check：Android／iOS 内部工厂新增可选绑定，common 增加非资源型绑定；Web 无运行消费。无 UI、服务端 Application Service／schema、依赖或链接变化，不追加页面冒烟、服务端双库或打包对齐。独立终态复核已亲核当前 Android 原始日志、状态与 APK 摘要，以及测试注入和文档边界，无遗留 finding。

收尾核对 D10、缓存 delta、README、两级地图、词表与实施指引，无新领域概念或产品取舍，不新增通用规范。可独立代码回退，无真实用户数据迁移。Web 持锁钩子及三端独立清理已由本页对应小节补齐；应用所有者、启动恢复及页面仍未接通，5.3／5.4 不勾选；strict 和含新增文件的 diff 检查在提交前执行，不归档或发布。

### 范围定位目录验证及收尾证据

比较基线／验证 HEAD：0bbad92b 加本片工作树，2026-11-07（本地）。本片沿 A 类 5.3／5.4 实施已确认的非显示启动引用，D10 先记录最小元数据、发布及租约前置。每 origin 一个短记录，原子保存登记集合、currentId 与正代次；begin 保留已有 current 但封闭旧发布者，register 必须属于当前代次。完整事实和原令牌恢复记录提交后才允许调用 publish；目录本身不授权。forget 只移除固定范围及仍指向该范围的 current，不改变其他 current 或代次。所有 factory lease 钩子及应用 owner 均尚未组合，不能以该组件声明崩溃遗留清理或断网重启成功。

初始目录七项测试未实现时失败，补齐后通过；独立 5a／实际 5b 找到两个 P2，均另以可区分的实际失败回归锚定。ledger-session-generation-red.log／xml 为 host 九项一失败，缺 generation／显式零可把 begin 重置为首次代次；修复仅对已有记录要求正代次，真正缺记录仍可由内部零初始化。Apple 原 stringForKey 把 NSArray 当缺项；ledger-session-apple-nontext-red.log 及 red-xml 中 macOS／iOSSimulator 各一项一失败，随后改为 objectForKey，仅 String 可读，read 和 CAS 共用检查，保留原数组／字典。仓库外 native-ledger-session-locator 只直接编译生产 Apple storage、新增仓库回归及 common port 签名，不替代 Foundation；不是完整 Directory 或应用工程。

执行命令及结果（所有日志位于仓库外 offline-ledger-1104）：

- 主工程使用既有 cash-write-set-lint.init.gradle、--no-daemon --max-workers=1 --no-configuration-cache --no-build-cache、in-process、-Xmx2048M 参数，执行 :shared:testAndroidHostTest --tests '*LedgerSessionDirectoryTest*'、:shared:assembleAndroidDeviceTest、:shared:compileKotlinIosArm64、:shared:compileTestKotlinIosSimulatorArm64、:shared:compileKotlinJs、:shared:compileKotlinWasmJs。ledger-session-directory-final-platforms.log：111.18 秒成功；当前 host 九项零失败／错误／跳过，fresh XML 为 final-host.xml。最初 host.log 已过七项但 Wasm 原生 String／Boolean Promise 互操作编译失败，已改 JsString／JsBoolean，不计该旧执行为全平台通过。新增 Apple directory 两项正常存储回归仅完成主工程编译。
- 仓库外 Native 工程用同版本 Kotlin 2.4.20 和已用协程 1.10.2、1536 MiB／单 worker 执行 macosArm64Test iosSimulatorArm64Test --continue；ledger-session-apple-nontext-current.log：8.20 秒成功，两目标各一项零失败／错误／跳过，fresh XML 为 current-xml。为 standalone Foundation 单元运行，无原生页面、Keychain 或完整重启声明。
- python3 run-android-ledger-system.py ledger-session-directory-final com.finance.tracker.AndroidLedgerSessionDirectoryTest：API 36／arm64-v8a 自有 QA，0.113 秒 OK (2 tests)，真实偏好提交文件、对象重建及双 storage CAS 通过，零失败／忽略。final-result.json 保存 APK SHA-256 9744f49e24f85da91d3ccd20a2267beaed408ecd13136fa4667084907e6b90fd；该测试不是进程重启或页面 QA，模拟器已关闭。
- Chrome 使用既有 browser init、NODE_ENV／CHROME_BIN 及关闭 JS／Wasm incremental 参数，执行 :shared:jsBrowserTest 和 :shared:wasmJsBrowserTest，各单模式 --tests '*LedgerSessionDirectoryTest*'。ledger-session-directory-final-browser.log：54.11 秒成功，每端两类共十二项（common 九项、Web 三项），零失败／错误／跳过；实际存储对象重建、双写 CAS 和独立 iframe 持锁等待均通过，fresh XML 为 final-browser-xml。最初 browser.log 的 JS 十一项一超时来自测试 iframe 脚本／目标 origin，Wasm 未执行；旧执行不计当前通过。主编译与浏览器进程树 RSS 峰值分别为 2,821,177,344 和 3,490,824,192 字节，包含编译及自有 Chrome、可能重复共享页，不是产品内存；构建、模拟器及浏览器均串行。

独立 5a 工程／异常／安全／复杂度及实际独立 5b 内部持久合同复核已完成；两个 P2 已修并经重审闭环；独立终态复核已亲核暂存代码、host／浏览器／Apple Native XML、Android 原始成功状态与 APK 摘要、四目标编译任务和文档边界，无遗留 finding。单 cell 集合加 CAS 避免旧标签页在新操作后补登记，无额外准备／删除状态。原生共享互斥只承诺当前单应用进程跨对象及顺序重启，Web 以同源短锁覆盖标签页，不声称并发原生多进程。网络不持元数据锁，调用实际工厂时必须遵守范围租约先于短锁。Cross-platform Impact Check：三端内部定位实现与测试受影响，没有页面、文案、后端 Application Service／schema、依赖或链接变化；不追加页面冒烟、服务端双库、无关加密驱动矩阵或打包对齐验证。

收尾核对 D10、缓存 delta、README、两级功能地图、词表及实施指引，无新业务概念或用户政策；本片只是落实已确认的离线访问边界，不增加通用规范。可独立代码回退；无真实用户数据迁移，不归档、开 PR 或发布。三端工厂登记／擦除及独立清理见对应实现片；当前令牌与页面代次、应用会话及最终离线流程尚未完成，5.3／5.4 不勾选。strict、Node 语法与含新增文件的 diff 检查在提交前执行。

### 恢复网络身份绑定验证及收尾证据

比较基线／验证 HEAD：f28d61eb 加本片工作树，2026-11-07（本地）。D10 先明确捕获后绑定及采集期的责任边界。ApiCredentialSnapshot 同时保存令牌和地址，恢复会话的重试及 scoped source 可显式共用同一对象；单参数 KtorLedgerSnapshotSource 首次调用时串行捕获一次，网络不持锁，显式匿名对象不会吸收后续登录。request 仍逐次校验地址，版本／整包保留预期工作区参数和外来快照拒绝；ledger 不直接清共享令牌。退出仍在 try 中求值地址，坏配置也完成原令牌的条件清理。

ledger-credential-snapshot-runtime-red.log／xml 实际运行四项中两项失败，复现 source 和会话重试换用后续地址／凭据（19.38 秒）；最初 red.log 仅测试双星号导入别名冲突，已改显式导入，不计行为红灯。修复及显式共用／匿名回归后，ledger-credential-snapshot-host.log 四类 28 方法成功（39.81 秒）。独立 5a 与实际 5b 复核后，ledger-credential-snapshot-current-platforms.log 使用公共 2 GiB／单 worker／非 daemon／in-process 参数，执行 :shared:testAndroidHostTest（LedgerCredentialSnapshotTest、FinanceApiCredentialLifecycleTest、FinanceApiClientTest、LedgerSyncApiTest）、:shared:assembleAndroidDeviceTest、:shared:compileKotlinIosArm64、:shared:compileTestKotlinIosSimulatorArm64、:shared:compileKotlinJs、:shared:compileKotlinWasmJs，83.75 秒成功，未变输入的 host 结果复用；当前 XML 保存于 ledger-credential-snapshot-current-host-xml，六项新回归和全部 28 项均零失败／错误／跳过。iOS 仅编译，不声称系统运行或打包。

ledger-credential-snapshot-current-system.log 在既有独占 API 36／arm64-v8a QA 模拟器上执行 AndroidLedgerSnapshotSyncTest：3.567 秒 OK (2 tests)，两成功状态、零失败／忽略；设备及 APK 摘要见对应 result.json。真实 SQLCipher／Keystore／范围租约与改变后 source 的正向同步、本地页及 401／403 擦除组合通过，HTTP 仍为 MockEngine。自有模拟器在运行后关闭。

ledger-credential-snapshot-current-browser.log 沿 ChromeHeadless init、NODE_ENV、CHROME_BIN 与关闭 JS／Wasm incremental 参数，执行 :shared:jsBrowserTest 和 :shared:wasmJsBrowserTest，各单模式 --tests '*Credential*'；57.12 秒成功。模式也匹配方法名：每端实际六类共 19 方法（凭据生命周期九项、身份快照六项、AccessViewModel／PDF parse-only／XLS parse-only／LedgerSyncApi 各一项），均零失败／错误／跳过，fresh XML 位于 ledger-credential-snapshot-current-browser-xml，不当作页面冒烟。平台与浏览器进程树 RSS 峰值分别为 2,858,221,568 和 4,352,835,584 字节，包含编译／自有 Chrome，可能重复共享页，不是产品内存指标；未与模拟器并行。

独立 5a 范围／工程／安全／取消及实际独立 5b 请求身份合同复核完成，无阻断 finding。已明确实际 Graph 地址为实例常量；测试只证明捕获后的变化，不证明对独立可变 getter／TokenStore 原子采集。采集期一致性、同令牌切区、最终发布及 UI 状态仍由后续恢复 owner 负责。Cross-platform Impact Check：共享认证和网络适配影响三端，已运行相称编译、真实 Chrome 单测及受影响 Android 组合；没有 UI、服务端事实／schema、依赖或链接变化，不重复页面 QA、服务端双库、驱动全矩阵或打包对齐检查。

README、两级地图、D10、实施指引与词表已核对，无新领域概念、用户政策或通用规则。代码可独立回退，无数据迁移。启动定位、离线回退、权限恢复及应用页面仍未接通，5.3／5.4 不勾选；strict 及含新增测试的 diff 检查在提交前完成，不归档、开 PR 或发布。

### 已有加密库恢复打开验证及收尾证据

比较基线／验证 HEAD：70162177 加本片工作树，2026-11-07（本地）。按已确认 D10 的坏材料拒绝边界，先记录恢复工厂 existingOnly，再测试／实施。三平台恢复模式在读钥及打开 SDK 前拒绝缺库或零字节主库，不创建事实目录、数据库或密钥；必要范围锁仍正常取得／释放。默认在线创建路径保留。主库非空只表示可尝试现存驱动打开，完整已提交快照及原令牌身份资格仍由存储校验，不以有效空库授予离线访问。

ledger-existing-only-red.log 的 compileTestKotlinJs 先因缺少 existingOnly 参数失败（14.23 秒），不当作运行行为复现。实际 ledger-existing-empty-runtime-red.log／xml 在真实 Chrome JS 六项中一项失败：0 字节文件及真实原密钥被恢复工厂成功接受，预期异常未发生；27.48 秒。三端随后只在恢复模式提前拒绝空主库。最初 ledger-existing-empty-red.log 是测试辅助中的 async／await 不受 Kotlin js() 解析器支持，已改用 Promise 链，不计行为红灯。

ledger-existing-only-host.log 的四类 37 方法已通过；加入空文件守卫后，ledger-existing-only-initial-compile.log 中 host 四类同样 37 方法通过，随后 iOS 测试缺 assertFalse import 导致整次构建失败（54.87 秒），未计完整构建通过。补齐测试 import 后，ledger-existing-only-current-platforms.log 使用公共 2 GiB／单 worker／非 daemon／in-process 参数，执行 :shared:testAndroidHostTest（ScopedSqlLedgerFactCacheTest、LedgerOfflineSessionStorageTest、SqlLedgerSnapshotStoreTest、LedgerSnapshotSyncTest）、:shared:assembleAndroidDeviceTest、:shared:compileKotlinIosArm64、:shared:compileTestKotlinIosSimulatorArm64、:shared:compileKotlinJs、:shared:compileKotlinWasmJs，30.51 秒成功；未变输入的 host 结果被复用，fresh XML 已核并保存在 ledger-existing-only-current-host-xml，37 方法零失败／错误／跳过。Apple 新增缺库及零字节／原钥回归，本片仅编译生产及测试代码，不宣称新 iOS 系统运行。

ledger-existing-only-current-system.log 使用既有独占 API 36／arm64-v8a QA 模拟器及 am instrument 命令，运行 AndroidLedgerOfflineSessionTest、AndroidLedgerFactCacheTest、AndroidLedgerSnapshotSyncTest、AndroidEncryptedSqlDriverTest：18.711 秒 OK (23 tests)，23 成功状态、零失败／忽略，设备及 APK 摘要见对应 result.json。新增两项验证重复缺库拒绝不造材料、正常创建关闭后可用现存模式重开、擦除后再拒绝，以及空主库拒绝后仍为零字节且原钥不变；随后默认在线打开成功证明范围租约已释放。身份恢复测试现存重开亦传 existingOnly。模拟器运行后关闭，不与 Gradle／Chrome 并行。

ledger-existing-only-current-browser.log 沿现有 ChromeHeadless、NODE_ENV、CHROME_BIN、关闭 JS／Wasm incremental 参数，执行 :shared:jsBrowserTest 与 :shared:wasmJsBrowserTest，各单模式 --tests '*WebLedger*'；59.13 秒成功，每目标 WebLedgerFactCacheTest 六方法及 WebLedgerSnapshotSyncTest 两方法，共八方法零失败／错误／跳过；XML 位于 ledger-existing-only-current-browser-xml。通过真实 Worker／OPFS／IndexedDB 验证缺库不造目录／钥、拒绝后重试、现存加密会话重开、零字节与原钥摘要保留及最终清理，原同步／撤权及线程故障回归同时通过。该矩阵不是应用启动或页面冒烟。最终平台构建与 Chrome 矩阵采样 RSS 峰值分别为 2,150,973,440 和 3,718,217,728 字节，可能重复共享页，不是产品指标。

独立 5a 工程／安全／异常／复杂度及实际独立 5b Worker 内部打开协议复核完成；空主库增量同样已复核，无遗留 finding。Cross-platform Impact Check：三平台工厂及 Web 内部 RPC 受影响，已执行对应编译和实际 Android／Web 验证；没有 UI、后端事实／schema、依赖或原生链接变化，不重复页面 QA、服务端双库或包对齐检查。README、两级地图、D10、实施指引及词表已核对，无新用户政策或通用规则；代码及内部协议可同组回退，无数据迁移。启动定位、固定网络凭据、应用恢复和页面仍未接通，5.3／5.4 不勾选；strict、Node 语法及 diff 检查在提交前完成，不归档、开 PR 或发布。

### 缓存保留关闭验证及收尾证据

比较基线／验证 HEAD：af99ff97 加本片工作树，2026-11-07（本地）。沿 5.3／5.4 及 D10 的离线启动资源准备，先回写正常资源释放与撤权擦除的区别，再实施。LedgerStorage.close 保留事实、恢复资料及密钥；真实关库确认后才调用平台租约释放，clear 仍执行擦除。两者共用现有 mutex 和读写围栏，首次终结动作固定，只能重试同动作，旧保留实例不能再擦除重开后的文件。原生工厂显式释放对应文件租约；Web 连接关闭本已等待 Worker 释放 Web Lock，不增加第二个关闭所有者。

ledger-normal-close-red.log 先因缺少 close／releaseStorage 入口编译失败（10.16 秒）。ledger-normal-close-host.log 的 26 方法成功；独立 5a 与实际 5b 范围判断后，ledger-normal-close-current-platforms.log 使用前节公共 2 GiB／单 worker 参数执行 :shared:testAndroidHostTest（ScopedSqlLedgerFactCacheTest、LedgerOfflineSessionStorageTest、SqlLedgerSnapshotStoreTest、LedgerSnapshotSyncTest）、:shared:assembleAndroidDeviceTest、:shared:compileKotlinIosArm64、:shared:compileTestKotlinIosSimulatorArm64、:shared:compileKotlinJs、:shared:compileKotlinWasmJs，129.32 秒成功。host 四类共 37 方法零失败／错误／跳过，XML 位于 ledger-normal-close-current-host-xml。新增三方法使用真实 JDBC 文件重开并核不擦除、旧实例拒绝及关闭／释放故障的原动作重试；关闭故障是回调注入，不冒称真实系统 close 故障。现有取消／异步关闭回归同时通过。

ledger-normal-close-current-system.log 按既有独占 QA 模拟器与 am instrument 命令运行 AndroidLedgerOfflineSessionTest、AndroidLedgerFactCacheTest、AndroidLedgerSnapshotSyncTest、AndroidEncryptedSqlDriverTest，20.671 秒 OK (21 tests)，21 成功状态、零失败／忽略。设备及 APK 摘要位于 ledger-normal-close-current-result.json。恢复测试改为正式 Android 工厂 close 后重开，不再用测试 no-op eraser；核文件及密钥保留、原令牌资料可读、旧实例不能删除重开缓存，以及最终 clear 擦除。运行后关闭自有模拟器。Apple 新增正式工厂保留关闭／原钥重开／旧实例拒绝回归，本片只编译生产及测试代码，未运行新的 iOS 系统方法。

ledger-normal-close-current-browser.log 沿 ChromeHeadless init、NODE_ENV、CHROME_BIN 和关闭 JS／Wasm incremental 参数，执行 :shared:jsBrowserTest、:shared:wasmJsBrowserTest，各单模式 --tests '*WebLedgerFactCacheTest*'；68.37 秒成功，两目标各四方法零失败／错误／跳过，XML 位于 ledger-normal-close-current-browser-xml。浏览器恢复测试同样使用正式工厂 close／reopen，验证原资料、错误令牌拒绝、旧实例不能清新缓存及最终擦除。该验证不是完整浏览器重启或应用页面冒烟。进程树 RSS 峰值：平台构建 2,497,347,584 字节，浏览器矩阵 3,306,553,344 字节，可能重复共享页，非产品指标；构建／浏览器与模拟器错开。

独立 5a 工程／资源安全／异常／范围复核无阻断。实际 5b 判断本片只改内部资源生命周期，不改表、DTO、领域模型、后端协议或 UI，契约／UI 专项不适用。Cross-platform Impact Check：共享关闭、两端原生租约及 Web 工厂消费受影响，已执行上述相称验证；没有页面交互、服务端 Application Service 或 schema、依赖和原生链接变化，不重复页面 QA、服务端双库或包对齐检查。README、两级地图、D10、实施指引及词表已核对；没有新用户决定或通用规则，不新增规范。代码可独立回退，无数据迁移。现存库专用打开、启动定位、固定凭据恢复、应用生命周期及页面仍未接通，5.3／5.4 不勾选；strict 及 diff 检查在提交前执行，不归档、开 PR 或发布。

### 加密身份恢复记录验证及收尾证据

比较基线／验证 HEAD：a061e7df 加本片工作树，2026-11-06 17:12 UTC。用户已明确允许已登录且缓存过账本的设备断网重启后浏览、联网发现失效后清除；已调用 grilling 澄清及 openspec-update-change，先更新词表、proposal、缓存 delta、D10 与任务，再按 apply 实施本片底层存储。离线访问不授予新的服务端权限，邮箱摘要不承诺匿名化或抗字典识别。

LedgerStorage 是原 LedgerFactCache 的内部子接口；恢复记录复用 SessionDto，只增加私有令牌摘要包装，单独一条 SQL UPSERT 原子更新。仅存在完整已提交快照时可读／存，读取校验邮箱摘要、活动工作区及唯一目录成员，原令牌改变返回缺省而不使用旧身份，损坏 JSON／摘要／范围失败不补值。元数据与事实共用驱动、互斥与关闭围栏，三平台工厂返回相同内部接口，未增加新密码库、密钥或关闭生命周期。表按需创建以读取既有内部库，不改服务端 schema 或现金事实合同。

ledger-offline-session-red.log 先复现缺少身份摘要及恢复读写入口，11.19 秒失败。沿下节公共 2 GiB／单 worker／非 daemon Gradle 参数，ledger-offline-session-platforms.log 执行 :shared:testAndroidHostTest（LedgerOfflineSessionStorageTest、ScopedSqlLedgerFactCacheTest、SqlLedgerSnapshotStoreTest、LedgerSnapshotSyncTest）、:shared:assembleAndroidDeviceTest、:shared:compileKotlinIosArm64、:shared:compileTestKotlinIosSimulatorArm64、:shared:compileKotlinJs、:shared:compileKotlinWasmJs，158.91 秒成功；host 四类／34 方法，零 failure／error／skip，当前 XML 保存在仓库外 ledger-offline-session-current-host-xml。七个新 host 方法覆盖未缓存拒绝、原令牌与完整 DTO、无原令牌文本、真实 SQL 关闭重开、身份／范围／成员拒绝、SQL abort 保旧、坏材料失败、关闭前已读取结果的围栏及严格 UTF-8 身份键。iOS 仅生产与测试代码编译，不声称新系统运行或 Xcode 打包。

手动启动同一 API 36／arm64-v8a QA 模拟器，按前述 am instrument 命令选择 AndroidLedgerOfflineSessionTest、AndroidLedgerFactCacheTest、AndroidLedgerSnapshotSyncTest、AndroidEncryptedSqlDriverTest。ledger-offline-session-current-system.log 为 17.748 秒、OK (21 tests)，四类 21 成功状态、零失败／忽略，设备及 APK 摘要在 ledger-offline-session-current-result.json。新增系统方法先用真实 SQLCipher／SQL 缓存准备现存文件，关库保留合成文件后由正式工厂及真实 Keystore 重开，检查身份资料／原令牌的有限明文标记不存在、原 token 可读、改 token 不可读，清理删除事实与恢复记录所在目录及密钥。该测试不模拟应用启动或真实网络；准备器的 no-op eraser 仅用于合成重开夹具，不进入生产。运行后仅关闭本次自有模拟器。

ledger-offline-session-browser.log 增加既有 shared-browser-unit.init.gradle 选择 ChromeHeadless，并设置 NODE_ENV=development、CHROME_BIN='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'，关闭 JS／Wasm incremental，执行 :shared:jsBrowserTest 与 :shared:wasmJsBrowserTest，各用单一 --tests '*WebLedgerFactCacheTest*'。68.25 秒成功，两目标各一类／四方法，全部零失败／错误／skip；fresh XML 位于 ledger-offline-session-current-browser-xml。新增恢复记录方法使用真实加密 OPFS Worker 和 SQLDelight：先关连接保留文件，再由正式工厂重开、读取原资料与令牌绑定，错误身份不覆盖，clear 后封闭旧实例并删除材料。它不是完整 Chrome 重启、登录或页面冒烟；旧工厂取消及线程故障回归同时通过。

采样进程树 RSS 峰值：跨端构建 2,534,260,736 字节（约 2.36 GiB），浏览器矩阵 3,680,305,152 字节（约 3.43 GiB，包含自有 Chrome），可能重复计算共享页，不是产品内存指标；Android 模拟器与构建／Chrome 错开。没有新增库、原生链接或分发声明，本片不重跑无关最终包对齐检查。独立 5a 工程／安全／异常／范围／复杂度及实际 5b 缓存表／身份历史契约复核无阻断；UI 专项不适用。Cross-platform Impact Check：共享存储及三平台工厂接口受影响，已运行前述跨端编译和实际 Android／Web 驱动验证；无页面、后端 Application Service 或服务端 schema 变化，不重复服务端双库或原生 UI QA。

收尾核对词表、proposal、delta、D10、README、两级地图及实施指引。用户决定属于本业务离线授权边界，保留在本 change，不推广成所有应用的通用权限规范；没有代理自行引入的新产品偏好。当前只完成恢复记录存储，启动范围定位、原令牌读取、网络失败回退、联网核验／撤权、应用退出及页面均未接线，整个离线重启能力和 5.3／5.4 不标完成。代码可独立回退，尚无真实用户缓存迁移；strict 及含新增文件的 diff 检查在提交前执行，不归档、开 PR 或发布。

### Android 缓存与同步浏览组合验证

比较基线／验证 HEAD：e75c7b31 加本片工作树，2026-11-06 16:57 UTC。只新增 AndroidLedgerSnapshotSyncTest，不改生产源码、依赖或已确认合同。真实 Keystore、范围租约、SQLCipher、SQL 快照、KtorLedgerSnapshotSource 与 LocalCashLedgerOwner 组合，HTTP 明确使用合成 MockEngine，不是实际后端、登录集成或断网重启身份恢复。复用现有合同作组合验证，不把已有实现通过的新测试称为缺陷红转绿。

android-ledger-sync-build.log 使用下节公共 2 GiB、单 worker、非 daemon 参数执行 :shared:assembleAndroidDeviceTest，18.34 秒成功，进程树采样 RSS 峰值 1,215,545,344 字节（约 1.13 GiB），非产品运行内存。随后手动启动同一 API 36／arm64-v8a QA 模拟器，安装当前 APK，以相同 am instrument 命令单独选择 com.finance.tracker.AndroidLedgerSnapshotSyncTest。android-ledger-sync-current-system.log 为 5.07 秒、OK (2 tests)，两成功状态、零失败／忽略；设备及 APK 摘要保存在 android-ledger-sync-current-result.json。结束后仅关闭自有模拟器，未与构建同时运行。

两方法验证首次快照、版本不变不下载、换版后的完整事实／游标失效、读取本地页不发网络、503 保留已确认页与事实、收据版本下限强制追新，以及实际 401／403 后先封闭旧 owner 再清除事实目录和密钥。清理前后分别验证材料存在性；重新创建同范围为空，TokenStore 的替换／清除方法明确禁止被该范围 HTTP 调用。旧 token 与其他登录的生命周期仍由后续应用接线负责。

独立 5a 测试范围／异常／清理和实际 5b 范围判断无 finding；本片没有契约或 UI 变化，不重开专项。Cross-platform Impact Check：只新增 Android 系统组合回归，Web／iOS 与生产代码未变；不重复无关原生构建、浏览器页面或双库矩阵，不声称原生 UI QA。收尾核对 README、两级地图和实施指引，未新增产品决定或术语，未新增通用规范。strict 与含新文件 diff 检查在提交前执行；应用会话、索引、完整导入、页面与最终验收仍未完成，不改整体任务完成标记、不归档或发布。

### Android 加密缓存工厂验证及收尾证据

比较基线／验证 HEAD：2a22b445 加本片工作树，2026-11-06 16:47 UTC。按既有 5.3／5.4 组合 Android 范围租约、Keystore、SQLCipher 与 ScopedSqlLedgerFactCache；D10 先记录默认 noBackupFilesDir 路径、现存库资格、失败保旧、按范围清理及不确定租约所有权。未新增用户决定、依赖或缓存协议，不接应用登录／撤权、原生事实索引或页面。

android-cache-factory-red.log 先出现缺少工厂入口，同时包含测试误用未公开 Os.unlink 的编译错误；测试与实现均改用 Android 公开 Os.remove 后构建通过。独立 5a 发现已交付实例在 clear 关库失败时没有显式保留租约；修正 closeDriver 闭包，使其与初始化失败同样保留 lease 并重抛原异常。未修复版的 android-cache-factory-clear-red-system.log 实际为 OK (1 test)：WeakReference 已清且锁描述符仍为一，不把文件名或静态 finding 冒充已复现终结器解锁／红转绿。补强避免依赖特定运行时的资源回收行为；最终回归仍保留该丢弃实例分支。

沿下节公共 2 GiB／单 worker／非 daemon Gradle 参数，android-cache-factory-final-build.log 执行 :shared:assembleAndroidDeviceTest、:shared:testAndroidHostTest（选择 ScopedSqlLedgerFactCacheTest、LedgerStorageIdTest、AndroidLedgerKeyOwnershipTest）及 :androidApp:assembleDebug，24.52 秒成功。host 为三类／14 方法，零 failure／error／skip，当前 XML 保存在仓库外 android-cache-factory-current-host-xml；采样进程树 RSS 峰值 1,263,026,176 字节，约 1.18 GiB，不含随后启动的模拟器，非产品内存指标。

手动启动同一 FTComposeQA_API36（emulator-5574、API 36／arm64-v8a、1536 MiB／2 核、无窗口／快照保存），安装当前测试 APK，以 adb -s emulator-5574 shell am instrument -w -r -e class com.finance.tracker.AndroidLedgerFactCacheTest,com.finance.tracker.AndroidEncryptedSqlDriverTest,com.finance.tracker.AndroidLedgerKeyVaultTest,com.finance.tracker.AndroidLedgerStorageLeaseTest,com.finance.tracker.LedgerStorageIdTest com.finance.tracker.shared.test/androidx.test.runner.AndroidJUnitRunner 执行。android-cache-factory-current-system.log 为 19.059 秒、OK (33 tests)，五类 33 个成功状态、零失败／忽略，设备、原输出及当前测试 APK 摘要在 android-cache-factory-current-result.json。运行完成后仅关闭自有模拟器；构建与模拟器不重叠。

十个工厂方法覆盖默认 noBackup 根目录、精确版本／金额／开放 JSON、真实快照读写与工作区隔离、旧加密库用原 Keystore 钥重开、缺钥／错钥保文件与密钥且释放租约、孤立日志不建新库、父目录与主库符号链接保外文件、实际 chmod 引发部分删除失败后的关闭围栏与明确重试、初始化不确定的双诊断及实际锁描述符、已交付实例关闭确认失败后丢弃对象仍持锁，以及真实打开后取消只关库一次、保文件／密钥并可重开。故障中的 SDK 关闭已实际返回，注入的是确认异常，未实测内核关闭失败；GC 测试不证明所有运行时的终结策略。所有数据和路径均为唯一合成范围；两项故意不确定的租约由进程保留到测试进程退出，测试仅清理各自唯一合成文件，不把该做法用于生产清理。

独立 5a 工程／安全／范围／异常／复杂度及实际 5b 持久化生命周期契约复核完成，关闭所有权 P2 已重审，无剩余 finding；UI 专项不适用。Cross-platform Impact Check：本片只新增 Android 内部工厂和系统测试，共享缓存及 Web／iOS 实现未变；受影响 host 回归、Android 编译及系统测试已运行，不重复无关 Web 页面 QA、iOS 编译或服务端双库矩阵，不声称原生 UI QA。最终 Android APK 中原生库和声明逐字核验见 android-cache-factory-packaging.json，无新许可输入。

收尾核对 D10、缓存 delta、README、两级地图、实施指引及词表；未产生应升级为通用规范的用户决定。代码可独立回退，新内部库尚未接应用、不迁移真实用户文件。完整端侧导入、写集、索引、登录生命周期、页面及最终回归仍未完成，5.3／5.4 不勾选；strict 和含新增文件的 diff 检查在提交前执行，不归档、开 PR 或发布。

### Android 加密驱动验证及收尾证据

比较基线／验证 HEAD：e96293f9 加本片工作树，2026-11-06 16:37 UTC。本片沿 5.3／5.4 实现底层驱动，不新增业务决定。D9 先记录固定官方 SQLCipher Android 4.20.0、SQLDelight Android driver 2.4.1、私有原始密钥所有权及 SDK 清理边界。AndroidX SQLite 直接声明对齐实际由 SQLDelight 选中的 2.7.1，不把初始 2.7.0 声明写成最终运行版本。

android-cipher-adapter-red.log 先复现缺少驱动入口的编译失败。仓库外独立真实 SDK 探针 android-cipher-capability-large-system.log 证明：执行真实 schema 读取后，普通 unchecked postKey 异常遗留一个匹配描述符；探针的私有反射仅用于验证和释放其自有资源，不进入生产。生产改用通用 SQLiteException 哨兵进入 SDK 原有清理路径，再还原同一原异常；不使用会触发破坏性恢复的 corruption 类型。SDK 原生关闭可能仅报告日志错误，私有静默观察器按当前线程观察资源错误、不输出 SQL 或异常；普通查询约束拒绝不封闭 owner。日志目标在加载原生库前安装，事务结束也进入观察门。关闭只尝试一次，不确定时保留 owner／连接及密钥到进程退出，交由后续工厂继续持有范围租约。

构建沿下节公共 Gradle 参数，依次执行 :shared:assembleAndroidDeviceTest 与 :androidApp:assembleDebug；当前 android-cipher-adapter-current-build.log 成功，17.36 秒，采样进程树 RSS 峰值 1,297,858,560 字节（约 1.21 GiB）。该统计可能重复计算共享页，排除随后启动的模拟器及其他应用，不是产品内存指标。随后手动启动既有 FTComposeQA_API36（emulator-5574、API 36、arm64-v8a、1536 MiB／2 核、无窗口与快照保存），安装当前测试 APK，执行 adb -s emulator-5574 shell am instrument -w -r -e class com.finance.tracker.AndroidEncryptedSqlDriverTest,com.finance.tracker.AndroidLedgerKeyVaultTest,com.finance.tracker.AndroidLedgerStorageLeaseTest,com.finance.tracker.LedgerStorageIdTest com.finance.tracker.shared.test/androidx.test.runner.AndroidJUnitRunner。

android-cipher-adapter-current-system.log 为 9.906 秒、OK (23 tests)，23 个成功状态、零失败／忽略；原输出、设备及测试 APK 摘要在 android-cipher-adapter-current-result.json。八个驱动方法使用真实 SQLDelight／SQLCipher，验证引擎与 libtomcrypt 版本、精确 20／18 位 TEXT、实际回滚、主库及活动 DELETE 日志的有限明文标记检查、调用方擦钥后私有副本仍有效、三次错钥保原文件且不误标关闭不确定、普通初始化原异常及零残留描述符、关闭报告故障的黏性围栏、打开与关闭双诊断、24 MiB 文本关闭后重读、无效钥不建文件和 UNIQUE 拒绝后继续读取。描述符检查同时验证打开时大于零，不以路径别名造成的假零通过。关闭故障通过真实 SDK close 后注入报告确认错误，不冒称内核 close 失败；延迟池重连、事务结束时的资源故障和实际 16 KiB 页大小设备未验证。全部使用合成目录并 finally 清理，结束后仅关闭本次自有模拟器。

android-cipher-adapter-dependencies.log 核对实际 debugRuntimeClasspath 的上述三版本。android-cipher-adapter-packaging.json 核官方 AAR SHA-256 为 1e57857adfd29e2f5ddb4686e1e5ec6659920220d4bf182fdb5ef085b5577100，APK 四种 ABI 的 SQLCipher 与 AAR 逐字相同，ELF PT_LOAD 均 16384，zipalign -c -P 16 4 成功；这是静态对齐证据。完整随包声明包含固定 AAR 的 BSD、Android 包装层 Apache 和 LibTomCrypt 1.18.2 的 public-domain 许可分支，SQLDelight／AndroidX 的 Apache 正文复用同一资源。最终资源与 APK 逐字核对，不以下载上游源码代替包内声明。声明版本对齐后的补打包记录另保存在 android-cipher-adapter-notice-build.log 和当前 packaging JSON，实际选中运行依赖未改变。

独立 5a 范围／工程／安全／异常／复杂度与实际 5b 驱动生命周期契约审查无剩余 finding，UI 不适用。Cross-platform Impact Check：新增 Android 实现及依赖，Web／iOS 只共同分发声明，未改其运行代码或页面；没有服务端 Application Service／schema 变化，不重复双库矩阵，也不将系统单元测试当原生 UI QA。核对 README、两级地图、D9、缓存 delta、实施指引与词表，没有新领域术语或应推广的用户规则。可独立代码回退；完整 Android 范围工厂、原生索引、应用登录／撤权与页面仍未完成，5.3／5.4 不勾选，不归档或发布。strict、含新增文件的 diff 及最终依赖／分发核验在提交前执行。

### Android 范围文件租约验证及收尾证据

比较基线／验证 HEAD：5a732fcb 加本片工作树，2026-11-06 16:07 UTC。D9 先记录严格原生范围摘要、私有根目录、独立持久锁文件及关闭不确定时的占用；ledgerStorageId 移入 common，Apple 只委托，完整编码与原固定摘要保持不变，不迁移既有文件或密钥。Android 租约只创建根／locks，拒绝对应目录或锁文件符号链接、非私有权限，以非阻塞 FileChannel 锁覆盖数据库及清理生命周期；不创建事实目录或访问密钥。

android-storage-lease-red.log 先复现缺少租约和共享摘要的测试编译失败。Android FileLock 官方平台约束指出关闭同文件其他通道可能释放进程锁，因此不能仅依赖 Java 重叠异常；实际 API 36 原实现的独立 ART 进程探针通过（android-storage-lease-kernel-baseline-system.log，5 方法），未复现提前解锁，不将其记作已复现缺陷。关闭确认失败后仍可借用同范围的缺口由 android-storage-lease-owner-red-system.log 精确复现，一方法一失败；现在按规范根目录＋标识在 open 前登记进程占用，获取失败只有资源关闭确认后才撤销，正常释放才移除占用。不确定关闭保持原异常及占用直到进程退出，旧实例再次释放不能清除新实例占用。

公共 Gradle 参数为 ./compose/gradlew -p compose --init-script <缓存>/cash-write-set-lint.init.gradle --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx2048M -Dfile.encoding=UTF-8'。android-storage-lease-green.log 同次执行 :shared:testAndroidHostTest --tests '*LedgerStorageIdTest*' --tests '*AndroidLedgerKeyOwnershipTest*'、:shared:assembleAndroidDeviceTest、:shared:compileKotlinIosArm64 与 :shared:compileTestKotlinIosSimulatorArm64，成功 166 秒；host 两类／四方法全绿、零 failure／error／skip。纯摘要包含原独立固定值、所有范围维度、拼接歧义和动态构造的非法 UTF-16。当前 Android registry 改动后的 android-storage-lease-final-build.log 沿相同参数重跑 host 四方法与 APK，成功 30 秒；XML 保存于仓库外 android-storage-lease-final-host-xml。iOS 生产／测试代码未在后续 Android 专用修复中改变，本片仅编译，不声称新 iOS 运行时或 Xcode 打包验证。

android-storage-lease-final-platforms.log 同一公共参数执行 :shared:assembleAndroidDeviceTest、:shared:compileTestKotlinJs 与 :shared:compileTestKotlinWasmJs，成功 114 秒，包含两种 Web 主代码及测试编译。该共享摘要只被原生存储使用，不改变 Web 存储身份或页面，因此不将编译作为浏览器页面 QA。进程树采样峰值：首次原生编译约 2.21 GiB，终态 Android 编译约 1.59 GiB，Web 编译约 2.04 GiB；采样可能重复计算共享页，排除随后启动的模拟器和其他应用，不是产品运行指标。

编译结束后手动启动既有 FTComposeQA_API36（emulator-5574、API 36／arm64-v8a、1536 MiB／2 核、无窗口／快照保存），安装最新 shared-androidTest.apk，再执行 adb -s emulator-5574 shell am instrument -w -r -e class com.finance.tracker.AndroidLedgerStorageLeaseTest,com.finance.tracker.AndroidLedgerKeyVaultTest,com.finance.tracker.LedgerStorageIdTest com.finance.tracker.shared.test/androidx.test.runner.AndroidJUnitRunner。android-storage-lease-current-system.log 为 9.654 秒、OK (15 tests)，15 个成功状态、零失败／忽略；APK 摘要、设备和原状态码保存于仓库外 android-storage-lease-current-result.json。租约五方法覆盖正常互斥与重复释放、删事实不解锁、不同范围／不同根目录独立、路径别名、三类符号链接、宽权限拒绝且保原文件、真实流 close 后注入确认失败并持续拒绝同范围。独立 app_process 探针实际读取内核锁资格，核持有期间、重复借用拒绝后及明确释放后的结果；该方法需 API 26 以上，用现有 JUnit 条件单独限定，不扩大生产 minSdk 24，也不把低版本跳过计作验证通过。

全部测试只操作合成私有目录，finally 清理；确认异常不是实测内核 close 故障，未证明强制终止恢复或跨进程首次密钥创建。终态系统测试同时重跑先前八个 Keystore 方法和两个纯摘要方法，运行后仅关闭本次模拟器。构建与模拟器错开；磁盘紧张时仅清理本 worktree 未被打开的可重建中间目录，释放约 1.12 GiB，具体清单留在仓库外 build-intermediate-cleanup-20261107.json，保留源码、最终应用包、SDK、日志及 XML。

独立 5a 范围／工程／安全／异常／复杂度与终态 registry 已复核，无新阻断；实际独立 5b 按内部资源寻址及租约原语判断无表、DTO、领域事实或 UI 变化，契约／UI 专项不适用。Cross-platform Impact Check：Android 新租约；iOS 委托相同共享摘要；Web 只编译新 common 文件、无接线变化。无页面或服务端 Application Service／schema 变化，不重复无关浏览器交互与后端双库，不声称设备 UI QA。核对 design、缓存 delta、README、两级地图、词表及实施指引，无新用户决定或领域名词，不新增通用规则。无新依赖，可独立回退，既有 Apple 身份不变；Android SQLCipher、完整范围工厂、原生索引和应用生命周期仍待完成，5.3／5.4 不勾选。strict 及含新增文件的 diff 检查在提交前执行；不归档或发布。

### Android 密钥仓储验证及收尾证据

比较基线／验证 HEAD：46eb7ead 加本片工作树，2026-11-06 15:50 UTC。D10 先记录 AndroidKeyStore 不可导出 AES-256-GCM 包装密钥、32 字节随机数据库密钥、范围认证及 noBackupFilesDir 的固定 61 字节密文记录。仓储只接收严格 64 位小写十六进制摘要，不计算完整登录范围或判断数据库文件资格。发布使用私有同目录临时文件、文件同步、rename 和目录同步；同进程 monitor 与实际文件锁保护 read／create／erase。读取缺项不建钥；坏记录、缺包装别名或系统异常拒绝且不覆盖。清理仅删除指定记录／临时密文，再删除该别名，保留锁 inode。

android-key-vault-red.log 先复现缺少仓储的 Android 测试编译失败。独立 5a 发现 P3：密钥在文件锁／流释放前产出，后置释放异常会遗留未交付副本。提取原行为后 android-key-ownership-release-red.log 精确两方法一失败；acquireAndroidLedgerKey 改为整个锁资源块成功退出后才转交，失败擦除暂存副本并保留原异常。当前 android-key-vault-green.log 的 :shared:testAndroidHostTest --tests '*AndroidLedgerKeyOwnershipTest*' 两方法全通过、零 failure／error／skip，原 XML 保存为仓库外 android-key-ownership-green.xml。该异常由纯所有权测试注入，不冒称真实 OS 文件锁释放故障。

本片公共命令为 ./compose/gradlew -p compose --init-script <缓存>/cash-write-set-lint.init.gradle --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx2048M -Dfile.encoding=UTF-8'。上述 host 测试及 :shared:assembleAndroidDeviceTest 同次运行成功，89 秒；进程树采样 RSS 峰值 1,695,449,088 字节，约 1.58 GiB，共享页可能重复计数，不含随后启动的模拟器或其他应用，不是产品内存指标。APK 构建完成后手动启动 FTComposeQA_API36（API 36／arm64-v8a），端口 5574、1536 MiB／2 核、无窗口／快照保存；未擦除已有数据或修改永久 AVD 配置。

adb -s emulator-5574 install -r compose/shared/build/outputs/apk/androidTest/shared-androidTest.apk 成功，再执行 adb -s emulator-5574 shell am instrument -w -r -e class com.finance.tracker.AndroidLedgerKeyVaultTest com.finance.tracker.shared.test/androidx.test.runner.AndroidJUnitRunner。android-key-vault-system.log 为 2.072 秒、OK (8 tests)，8 个成功状态、零失败／忽略；原始输出与 APK SHA-256／设备／状态码保存在仓库外 android-key-vault-system-result.json。八方法覆盖实际非导出 Keystore、加密记录和新实例重读、重复创建不换钥、缺别名及损坏／跨范围密文拒绝、两线程首次创建一致、真实 chmod 触发删除拒绝后保钥重试、符号链接拒绝及外文件保留、孤儿密文定点清理、非法 ID。全部仅使用合成范围并在 finally 清理，不读取登录令牌；运行后仅关闭本次 emulator-5574。

独立 5a 产品范围／工程／安全／异常／复杂度／最终增量已执行，P3 修复后重审关闭；实际独立 5b 契约复核无 finding，UI 不适用。文件类型拒绝仅按已测普通记录和符号链接描述，不声称任意特殊文件均可及时拒绝。跨进程并发、进程崩溃恢复、真实系统拒绝／锁屏及硬件保护尚未验证。Cross-platform Impact Check：仅 Android 私有仓储和测试，iOS／Web 及公共协议未变；没有页面、文案、服务端存储或 Application Service 变化，不重复无关浏览器、后端双库或 iOS 构建，不声称原生 UI QA。

收尾核对 D10、缓存 delta、README、两级地图、实施指引与词表；没有新产品决定或领域名词，无需新增通用规则。未添加依赖、schema 或应用接线，可独立代码回退；Android SQLCipher 驱动、完整范围工厂、原生索引和应用生命周期仍待完成，5.3／5.4 保持未勾选。strict 与含新增文件的 diff 检查在提交前执行；不归档或发布。

### Apple 加密驱动验证及收尾证据

比较基线／验证 HEAD：09e5f95d 加本片工作树，2026-11-06（UTC）。按 5.3／5.4 已确认加密要求实现底层 Apple 驱动；D9 先记录 SQLDelight 2.4.1、SQLiter 1.3.3 与官方 SQLCipher 4.20.0 exact SwiftPM、原始字节传钥、连接所有权及失败关闭。没有变更现金事实、服务端 schema 或业务 API。新增 native-driver 依赖、私有 C API 声明、SwiftPM 生成包、Kotlin／Xcode 锁文件及最终 Xcode 链接；保留 Kotlin 2.4.20，不引入 CocoaPods。

仓库外同版本真实 SDK 的 native-cipher-sdk-close-red.log／native-ledger-cipher/native-cipher-sdk-close-red.xml 已复现错钥失败打开后 driver.close 遗漏句柄。新增测试缺少工厂时先编译失败（native-cipher-adapter-red.log）；实现保留生命周期回调取得的真实句柄，失败打开即清理；每连接验证实际密码引擎、内存临时存储及 DELETE 日志，SQLiter 日志关闭。独立审查发现 SDK closed 先于 C close，已改为任何关闭异常持续锁定诊断，尽力关闭其他连接且不擦钥、不推进文件删除。真实 manager 的连接先完成关闭再注入确认错误，证明 closed=true 不能清除此状态；这不是实测真实 C close 失败。仓库外仅源码副本删去不确定检查的负对照精确此方法失败，native-cipher-close-negative.log／xml 已保留；生产源码未替换，探针已恢复指向仓库文件。

macOS 独立 Gradle probe 通过 symlink 消费仓库当前生产文件、C 声明和同一测试文件，运行 macosArm64Test --rerun；native-cipher-adapter-final.log 与 native-ledger-cipher/native-cipher-adapter-final.xml 为 4 方法、0 failure／error／skip，12:24 UTC。覆盖真实主文件与活动回滚日志的有限明文标记检查、20／18 位精确 TEXT、实际事务回滚、关闭重开、调用方擦钥后懒连接仍可创建、3 次错钥不改原文件且无遗留文件描述符、非法钥不建文件及不确定关闭持续拒绝。此探针不代替 iOS 验证或完整安全证明。

公共 Gradle 参数运行 shared:integrateLinkagePackage（显式 XCODEPROJ_PATH）、两个 iOS 生产编译成功，native-cipher-ios-integration.log 为 75s。终态 shared:compileKotlinIosArm64／compileKotlinIosSimulatorArm64／compileTestKotlinIosArm64／compileTestKotlinIosSimulatorArm64 全通过，native-cipher-ios-final.log 为 32s。再运行 shared:iosSimulatorArm64Test --device D0A5F360-E10A-43E8-BEA6-37340026EDBD --tests '*AppleEncryptedSqlDriverTest*' --rerun，native-cipher-ios-unit.log 为 30s；fresh XML 位于 native-cipher-ios-unit-xml，12:28:29 UTC，4 方法全绿、0 failure／error／skip。仅原生单元测试，没有设备交互或页面 QA 声称。

实际 xcodebuild -project compose/iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'generic/platform=iOS Simulator' -derivedDataPath <仓库外缓存>/native-ios-app CODE_SIGNING_ALLOWED=NO build 成功，终态打包日志 native-cipher-xcode-app-notice-final.log。otool 核对应用动态库实际依赖 @rpath/SQLCipher.framework/SQLCipher，未依赖系统 libsqlite3，官方 framework 已嵌入；完整 BSD／Apache 声明与源码逐字一致。两份锁均固定上游 revision e7415c5e6b4fd9b78874bb956aad8a42b08e9966，打包摘要见 native-cipher-app-packaging.json。Xcode 工程局部 17 行接线与生成器完整输出经 plutil JSON 比对完全等义，不保留无关格式改写。

独立 5a 范围／工程／安全／异常／复杂度／最终 diff 与实际独立 5b 已执行，关闭标志 finding 已按上述保守边界处置并重审，无遗留实现阻断。无公共模型／接口或 UI 行为变化，不触发 UI 专项。Cross-platform Impact Check：iOS 新增加密驱动及链接，Android／Web 不接入该驱动，仅共同分发许可；共享 SQL、服务端 Application Service 与 schema 未修改，本片不重复服务端双库矩阵或未受影响页面冒烟，不以原生 SQLite 当 PostgreSQL 通过。

核对 D9／D10、缓存 delta、README、两级地图、实施指引、词表及许可分发；无新领域概念、用户选择或通用规则需要沉淀。该片可整体代码回退，未接应用的新库不迁移既有用户文件。Keychain、范围目录租约、原生缓存工厂、销毁、索引和应用生命周期仍待组合；5.3／5.4、完整安全验收及整个 change 不标完成，不归档或发布。

### Apple Keychain 仓储验证及收尾证据

比较基线／验证 HEAD：736cd812 加本片工作树，2026-11-06（UTC）。D10 先记录独立服务、范围摘要寻址、32 字节安全随机密钥、WhenUnlockedThisDeviceOnly／非 iCloud、重复创建及异常边界。仓储只接收后续工厂提供的 64 位十六进制 ID，不计算完整登录范围，也不判断文件存在。实际 SecItem API 读取缺项才返回 null；长度损坏或系统错误直接失败；原子新增遇重复时读取原项，既有损坏不删除后重建。临时候选 ByteArray、可变 CFData 缓冲及 CF 所有权按调用结果释放，不声称可证明 OS 内部所有副本物理擦除。

先新增 AppleLedgerKeyVaultTest，缺少仓储时 compileTestKotlinIosSimulatorArm64 编译失败（native-keychain-vault-red.log）；补实现后运行真实 iOS Keychain 三项集成。仓库外能力探针确认 standalone 返回 -25291，单纯启动模拟器或只加授权仍失败；测试专用授权与非 standalone 同时满足后写入／删除返回成功。该前提不是应用密钥逻辑缺陷，原失败及成功日志保存在 native-keychain-*capability.log。固定 Kotlin 2.4.20 SwiftPM 只为默认测试任务配置动态库搜索路径；显式模式复用默认任务与单一测试二进制，不需要自建 run、重复资源处理或 SDK 缓存路径适配。

公共 Gradle 参数加 -PledgerKeychainTests=true，执行 shared:iosSimulatorArm64Test --device D0A5F360-E10A-43E8-BEA6-37340026EDBD --rerun，native-keychain-vault-green.log 成功 14s；14:51:13 UTC fresh XML 保存于 native-keychain-vault-os-xml，1 类／3 方法，0 failure／error／skip。验证新实例读回、返回副本独立、重复创建保留原钥、真实可访问性属性为设备本地解锁资格、不同 ID 密钥隔离与定点幂等删除、手动写入损坏材料后 read／create／新实例均拒绝，未悄悄覆盖坏项。只使用随机合成记录，全部指定项在 finally 删除，没有读取或修改登录令牌。

普通模式执行 shared:iosSimulatorArm64Test --device 同上 --tests '*AppleEncryptedSqlDriverTest*' --tests '*AppleLedgerKeyIdTest*' --rerun，随后 shared:compileKotlinIosArm64／compileTestKotlinIosArm64；native-keychain-platforms-final.log 成功 27s。14:51:58 UTC 两类／5 方法全通过，0 failure／error／skip，XML 保存于 native-keychain-default-xml；Simulator 生产和测试编译随上述真实运行完成。纯 ID 校验已从系统类拆出，保持默认可运行。新 mode 是明确任务输入，普通全量不包含三项系统集成，必须另跑该模式；不能用默认通过冒称 Keychain 通过。无页面、设备交互或真机 QA 声称；没有验证锁屏／硬件保护、系统拒绝或并发首次创建，不扩大此次运行结论。

独立 5a 安全／工程／异常／复杂度／最终 diff 与实际独立 5b 终态复核无阻断；测试分类 P3 已拆类处理并重审关闭。OpenSpec strict 与含全部新增文件的暂存 diff 检查通过。Cross-platform Impact Check：仅 iOS 仓储及测试配置变化，生产应用授权未变，Android／Web 及公共合同未改；不重复未受影响的后端双库或浏览器流程。完整范围摘要、目录及文件租约、加密缓存工厂和登出／撤权组合仍待完成，5.3／5.4 不勾选。

收尾核对 D10、缓存 delta、README、两级地图、实施指引及词表；无新用户决定或领域术语，不新增通用规范。可独立代码回退，新仓储尚未接应用，无既有用户密钥迁移；不归档或发布。

### Apple 缓存工厂验证及收尾证据

比较基线／验证 HEAD：16ef0047 加本片工作树，2026-11-06 15:24 UTC。D9 先记录私有 Application Support／排除备份、完整范围租约、文件检查和 Keychain、真实 SQLCipher、Scoped SQL 缓存组合；原生索引及应用尚未接线。已有主库缺钥、错钥或只剩侧文件不重建。初始化失败保留存储并确实关闭才释放租约；失败打开且关闭不确定时，用内部 AppleLedgerDriverOpenCloseFailure 保留原 cause 与关闭诊断，保持租约直到进程退出。明确清理在关闭成功后按目录、指定 Keychain 项、租约顺序执行；文件部分删除后失败仍保留密钥及租约，不能承诺文件全部保留。

native-cache-factory-red.log 先复现工厂入口缺失。独立 5a 发现父目录符号链接 P2，native-cache-factory-parent-red.log 真实复现越出事实根目录；已在访问前和清理时核 facts 父目录是真实目录，当前相同外目录回归转绿。修复不改变产品范围或业务合同。实际独立 5b 契约复核无新增 finding，UI 专项不适用；终态普通组、文档及最终增量复核无阻断；三处相邻旧状态文案已按复核原位收窄。

本片公共命令为 ./compose/gradlew -p compose --init-script <缓存>/cash-write-set-lint.init.gradle --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx2048M -Dfile.encoding=UTF-8'。先在模拟器关闭时执行 shared:linkDebugTestIosSimulatorArm64、shared:compileKotlinIosArm64、shared:compileTestKotlinIosArm64，native-cache-factory-final-compile.log 成功 62s；然后手动启动指定模拟器 D0A5F360-E10A-43E8-BEA6-37340026EDBD，开启 -PledgerKeychainTests=true 执行 shared:iosSimulatorArm64Test --device <该 UDID> --rerun。native-cache-factory-final-system.log 成功 17s，15:23:16／17 UTC fresh XML：工厂 8 方法、Keychain 3 方法，全部零 failure／error／skip，保存于仓库外 native-cache-factory-system-xml。

八项工厂回归使用真实系统 Keychain、SQLCipher、Darwin 文件锁及 SQL 存储，验证精确 20／18 位金额、超 JS 安全整数版本和开放 JSON 数字词法、实际备份排除属性、重开预置加密事实且不换钥、缺钥／错钥逐字保文件和原钥、残留侧文件拒绝、父目录符号链接拒绝、真实 chmod 触发删除失败后旧实例封闭且保钥／租约并可明确重试、失败打开时不确定关闭的诊断和租约围栏。关闭故障注入在真实 C close 返回后抛确认异常，不冒充真实 C close 故障；测试仅在 teardown 关闭自己的合成租约 FD，生产不提供该恢复路径。

普通模式沿相同命令执行 shared:iosSimulatorArm64Test --device <同 UDID> --tests '*AppleEncryptedSqlDriverTest*' --tests '*AppleLedgerStorageLeaseTest*' --tests '*AppleLedgerKeyIdTest*' --rerun，native-cache-factory-final-default.log 成功 13s。15:24:07／09 UTC fresh XML 共 3 类／10 方法全通过、零 failure／error／skip，保存于 native-cache-factory-default-xml；包含失败打开同时关闭确认失败的两种原始诊断。系统模式和普通模式的 XML 分开保存，两个 iOS 目标的生产／测试编译均已通过，测试后关闭本次启动的模拟器。没有重新构建 Xcode 应用包，不沿用旧包冒称当前应用接线通过。

资源采样脚本及 CSV／JSON 留在仓库外：本片编译进程树 RSS 峰值 2,412,707,840 字节（约 2.25 GiB），系统／普通测试进程树分别约 607／707 MiB；均不含已启动的模拟器或其他应用，RSS 共享页也可能重复计数，不能当作产品运行内存或设备 SLO。采用 2 GiB 堆、单 worker，编译与模拟器运行错开；此前未记录的峰值不能由本次结果倒推。

Cross-platform Impact Check：仅 iOS 私有平台工厂及测试模式变化，公共协议、Android／Web 和页面文案不变，不重复未受影响的后端双库或浏览器流程，不声称设备交互 QA。取消、真实系统拒绝／锁屏、进程崩溃恢复、Android 工厂、原生索引和应用登录／登出／撤权组合仍待验证；5.3／5.4 保持未勾选。收尾核对 design、缓存 delta、README、两级地图、实施指引及词表；无新产品决定、术语或通用规则，无新依赖或迁移，可独立代码回退。strict 和含新增文件的 diff 检查在提交前执行；不归档或发布。

### Apple 范围文件租约验证及收尾证据

比较基线／验证 HEAD：6e47a10b 加本片工作树，2026-11-06 15:05 UTC。D9 先记录严格 UTF-8、完整精确范围的长度前缀摘要和排他锁文件所有权；AppleLedgerStorageLease 不创建事实目录或访问 Keychain。锁文件在 locks 下持久保留，facts 范围目录可删除，release 只关闭自身描述符；close 错误锁存为不确定，不重复关闭可能已复用的编号。root 必须由后续工厂提供私有应用目录，本片拒绝最终 root／locks 符号链接及锁文件符号链接，不宣称任意外部目录具有安全沙箱保证。

native-scope-lease-red.log 先复现缺少入口的测试编译失败。沿本页 3 GiB／单 worker 公共 Gradle 参数及 cash-write-set-lint.init.gradle，执行 shared:iosSimulatorArm64Test --tests '*AppleLedgerStorageLeaseTest*' --tests '*AppleEncryptedSqlDriverTest*' --tests '*AppleLedgerKeyIdTest*' --rerun，随后 shared:compileKotlinIosArm64／compileTestKotlinIosArm64；native-scope-lease-final.log 构建成功。fresh XML 15:05:05／07 UTC，3 类／9 方法全部通过、0 failure／error／skip，保存于仓库外 native-scope-lease-final-xml。租约四方法覆盖独立 Python 摘要固定值、全部范围维度及代理项拒绝；真实 flock 同范围互斥、不同范围独立、删事实目录仍互斥及正常重复释放；锁文件符号链接拒绝；手动关闭自有 FD 后真实 EBADF、再强制复用编号仍不被重复 release 关闭。定位测试 FD 时使用唯一合成目录与完整哈希后缀，兼容模拟器 /var 的实际路径别名。没有伪造 close 返回值或修改生产 FD。

独立 5a 安全、输入、异常、资源所有权、复杂度及最终增量复核无阻断 finding。实际独立 5b 按内部路径／文件租约范围判断无表、DTO、领域模型或 UI 变化，契约与 UI 专项不适用。Cross-platform Impact Check：仅 iOS 私有存储组件与原生回归，公共合同、Android／Web、页面／用户文案不变；不重复未受影响的浏览器、后端双库或 Xcode 应用构建。运行测试并非设备交互 QA；尚未证明另进程崩溃恢复、实际工厂的目录／密钥／关闭后删除组合或应用登出，5.3／5.4 保持未勾选。

收尾核对 design、缓存 delta、README、两级地图、实施指引及词表；没有新产品选择或领域术语，不新增通用规则。无新依赖或迁移；组件尚未接应用，可独立代码回退。strict 和含新增文件的 diff 检查在提交前执行；不归档或发布。

### 本地工作簿全成员验证及收尾证据

比较基线／验证 HEAD：4e96553c，2026-11-06 08:48 UTC。D3 先记录与 main 预检一致的部件范围及根元素坐标判定，新增 CashXlsxArchiveValidationTest 的完整有效 ZIP 保留正常账单并加入未消费部件；local-xlsx-all-xml-red.log 实际复现畸形 unused.xml 及非标准路径 row=200001 被旧入口接受，合法额外部件控制通过。实现沿现有 scanCashWorkbookParts 同一次遍历，在 parts 保存前复用 visitCashWorkbookXml 完整验证；根 worksheet 才进入既有坐标守卫，CRC／中央目录／实际累计字节及成员上限不变。选中表的后置守卫与公式拒绝仍保留，不解释未消费公式。

沿本页公共 Gradle 参数运行 business-domain:jvmTest、ktlintCheck、detektJvmTest，完整 JVM 165 类／627 方法，0 failure／error／skip。detektJvmMain 实际 NO-SOURCE，不冒充公共生产代码检查；另运行 detektMetadataCommonMain 通过。jsNodeTest、wasmJsNodeTest、testAndroidHostTest 各 140 类／591 方法，全部 0 failure／error／skip；关闭 JS／Wasm incremental。compileTestKotlinIosSimulatorArm64／compileTestKotlinIosArm64 通过，仅编译不运行设备或模拟器交互。保留原完整 XLSX 向量，不修改其预期。

backend:test 精确选择 CashWorkbookDocumentTest／CashWorkbookXmlEncodingTest，共 2 类／15 方法，0 failure／error／skip。原 rejected helper 保留全部原生断言并把同一 POI 合成／改写 ZIP 直接交本地入口断同码，覆盖根不在常规路径、隐式越界列、DTD、坏 XML／目录／CRC／预算等；不以此宣称任意编码、XML 或 OPC 全集等价。backend:ktlintCheck／detektTest、OpenSpec strict 和 diff 检查通过。外部日志为缓存 local-xlsx-all-xml-{red,green,platforms,backend-static}.log，XML 位于 local-xlsx-all-xml-results，敏感数据未入仓。

独立 5a 及实际独立 5b 契约复核无 finding；没有 UI、依赖、数据库或线路变化，UI 专项及双库写入不适用。Cross-platform Impact Check：三端共用本地读取修复并已完成上述执行／编译；当前页面尚未调用该入口，不新增页面冒烟，不把 Node 测试当真实页面浏览器验证。已核对 D3、tasks、两级地图、实施指引及现有解析／公式规格，README／部署配置无变化。未新增用户业务决策或通用规则。本片可独立代码回退；唯一渠道、文件 owner、月汇总及完整导入仍待接入，6.1／6.2 整项不勾选，不归档或发布。

### 来源 SQL 写入验证及收尾证据

比较基线／验证 HEAD：0fd6e4b9，2026-11-06 08:37 UTC。父项 DML 原文不变，组成项仅将删除前修复改为必填回调；旧入口仍按父项→C→组成项→类型维护→绑定→问题→候选失效执行。新内部原语不接公开 DTO、不授权，也不把空回调作为来源服务的默认行为。D3 先明确该边界，测试先因缺少类编译失败，再实现并验证。

实际命令沿本页公共 Gradle 参数。FT_TEST_POSTGRES_URL 显式为 postgresql+psycopg://huangwenlong@127.0.0.1:5432/ft_offline_ledger_1104_test；backend:test --tests '*CashRecordValueWriterTest' 运行 SQLite 临时空库及已准备 PostgreSQL，8 方法×2 后端共 16 项，0 failure／error／skip。覆盖服务器默认值及每事务版本、无操作、隐藏元数据、修订溢出、重排、替换回调恰好一次且早于删除、真实 posted 快捷路径不访问 SQL、回调原异常传播，以及实际第二次组成项更新触发外键失败后父项／首次组成项更新／版本整体回滚。窄 SQL 夹具不证明完整来源命令合法性；完整校验仍归后续事务服务。新方法属于默认测试，不归外部历史分类。

backend:migrationEvidenceTest 精确选择 CashRecordWriterContractTest／CashEntryRepositoryMigrationTest，SQLite 和真实 PostgreSQL 各 2 类／46 方法，均 0 failure／error／skip；包含原 writer 全部 15 个完整操作场景、条目编辑、草稿组成项元数据／引用修复及确认关系删除拒绝。SQLite 的 FT_KOTLIN_RECORD_WRITER_CONTRACT_DIR=缓存 record-writer-sqlite、FT_KOTLIN_CONTRACT_DIR=sqlite；PG 分别为 cash-range-writer-1105-postgres（同一专用库内既有合成 schema）和 postgres-5432，并显式 FT_KOTLIN_TEST_DB_USER=huangwenlong。各用例沿原 savepoint 整体回滚并核对原库状态，未启动或重建数据库。

backend:ktlintCheck／detektMain／detektTest、OpenSpec strict、git diff --check 通过。外部证据位于缓存 source-value-writer-{red,green,legacy-sqlite,legacy-postgres}.log 和 source-value-writer-xml/{new,sqlite,postgres}。SQL 观察器兼容两种方言的标识引号；真实 SQL 成功后才记执行，故障测试核前一父项和组成项更新确已执行。

独立 5a 以及实际独立 5b 契约审查无实现阻断；posted 夹具已按复核改为真实 posted 父项后再验证快捷路径。Cross-platform Impact Check：仅 backend 内部实现，线路、三端页面及交互不变；本片无需重跑浏览器／原生编译，整 change 的前端 QA 仍未完成。已核对 D3、tasks、两级功能地图、apply 指引、词表及 README；没有新依赖、配置、公开入口或术语，部署示例无需改动。用户本轮未新增业务决策，无通用规则可沉淀。本片可独立代码回退，无 schema 变化；3.2／3.4 整项不勾选，不归档或发布。

### main 合并与后续适配

比较基线为离线分支合并前 HEAD 65be32d9；合入 origin/main d63ecdae。main 将 Kotlin 迁移压为 2950f790，本分支保留原迁移提交；以迁移完成内容 bcf7f8ef 做三方内容合并，最终合并提交仍保留真实双亲，不把整个迁移重新实现。原工作树不修改，原提交可由 backup/offline-first-before-main-20261106 恢复。

本次适配包括 Kotlin 空库基线、ledger_seq／54 校验及 jOOQ 一致性；保留 main 的 Neon 连接安全参数、生产启动、health、PDF crop／旋转和首页探测、唯一渠道识别、时区月汇总、账户币种批量请求、追加 CAS 和容量恢复。原生 ZIP 全成员校验继续复用共享的 CRC／中央目录／累计解压预算，StAX 对每个 XML／rels 完整校验，根元素为 worksheet 时复用共享坐标守卫。空行号及隐式第 129 列错误、坐标错误优先级随 main 修正；保留本分支的值型 XLSX、日期／数字和公式拒绝口径。

外部历史夹具测试按 main 的 HistoricalMigrationEvidence 分类显式执行；本分支新增且仍依赖已保存合成库／原始 oracle 的合同测试也使用该任务，保留全部断言。默认 test 不配置这些外部夹具；新测试逐步改为自包含的工作仍属于未完成改造，不能将被过滤或缺夹具的测试计为通过。默认 CI、共享层及当前受影响测试须分别复跑。

后续本地导入接线必须覆盖以下 main 新合同，尚未完成，不因本次合并勾选原任务：

- 6.1／6.2：端侧无来源提示时唯一渠道探测，零／多匹配为 import_channel_unrecognized，仍待接入。本地 XLSX 已沿同次 ZIP 遍历完整校验所有 XML／rels，含非标准路径及未选中工作表，保留选中表公式边界；验证见本页本地工作簿全成员证据。完整文件 owner 尚未接线。
- 6.2／10：本地核对的 all／pending／confirmed 时区月汇总、旧计划合同及已提交收据优先；不得把账本月份累计器当作等价的导入月汇总。
- 6.2／10：迁移文件 owner 时保留追加幂等／CAS、解析一次、20 文件计数，以及超大预览保留账户选择后回准备页、可删替追加、一次 Snackbar；503／结果未知不开放编辑。
- 4.3／11：后续 schema 变化同时维护 Kotlin 基线与明确升级方案，不恢复已退役 Alembic 或 ft 导出器；保留已保存历史夹具，独立 Unicode／时间等生成工具不随 Python 后端一并删除。

本轮只整合 main 和核对重做范围，完整写集公开服务、真实本地缓存安全生命周期及页面切换仍未完成；不归档或发布本 change。用户决定无需形成新的通用规范，现有离线边界继续有效，schema 基线适配记录在本 change。

#### 合并验证与审查证据

验证时间截至 2026-11-06 07:50 UTC。验证工作树 HEAD 为 65be32d9，MERGE_HEAD 为 d63ecdae；所有本次修复纳入同一个双亲合并提交，验证时间为 2026-11-06。外部日志和 XML 位于 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1104 的 main-*.log／main-merge-xml，未放入仓库。

公共 Gradle 入口为仓库根的 ./compose/gradlew -p compose，设置 NODE_ENV=development，加载缓存目录的 cash-write-set-lint.init.gradle（仅将 lint worker 堆提高为 1 GiB，不禁用规则），使用 --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.compiler.execution.strategy=in-process，JVM 参数为 -Xmx3072M -Dfile.encoding=UTF-8。端侧编译另显式设置三个 Kotlin incremental 属性为 false；下面各段给出实际任务、筛选、环境和终态结果。

main-merge-red.log 先在新初始化／旧分支版本校验的组合中复现 SQLite 4 项失败；main-guard-red.log 的新断言先复现空行号及隐式列溢出两项错误。修复双库基线、版本和共享守卫后，main-merge-green.log 的 schema／数据库配置／OpenAPI／渠道探测／工作簿／PDF 坐标／月汇总和领域定向测试通过。main-schema-final.log 在清除上游导出 SQL 的行尾空格后，复跑完整 SchemaBootstrap* 两类／9 方法（真实 SQLite／PG），0 failure／error／skip；SQL 结构及约束不变。OpenAPI 资源完整 90 操作，保留 ledger、PDF／XLS parse-only 和 main 新币种请求合同。

main-full-default.log 执行完整领域 jvmTest，164 类／624 方法，0 failure／error／skip。main-final-default-lint.log 显式设置 FT_TEST_POSTGRES_URL=postgresql+psycopg://huangwenlong@127.0.0.1:5432/ft_offline_ledger_1104_test，执行 backend:test 全集（186 类／950 方法，0 failure／error／skip，含真实 PG 初始化回滚及默认／约束校验）、backend ktlintCheck／detektMain／detektTest 和 domain ktlintCheck／detektMetadataCommonMain／detektJvmTest，通过。两混合类恢复默认集的三个自包含方法另由 main-historical-sqlite.log 的 backend:test --tests '*CashXlsParseContractTest' --tests '*LedgerReceiptContractTest' 实际通过；不把被 category 排除的方法算作默认通过。

main-final-historical-sqlite.log 使用 backend:migrationEvidenceTest，准确选择 com.finance.tracker.backend.Ledger*ContractTest、LedgerSnapshotParityTest、PDF／XLS parse-only、CashSourceRevisionContractTest、两本地读取／上下文 parity、CashEntryRepositoryMigrationTest、CashImportPreviewSnapshotContractTest 和原始操作 parity，共 20 类／144 方法，0 failure／error／skip。FT_KOTLIN_LEDGER_CONTRACT_DIR 和 FT_KOTLIN_CONTRACT_DIR 指向缓存 sqlite；FT_KOTLIN_IMPORT_PREPARATION_CONTRACT_DIR 指向 relation-choice-1105-preparation；原操作场景指向 operation-native-baseline-2233。命令不再用会误选 InvestmentLedgerReadContractTest 的包名前通配符。

main-final-historical-postgres.log 显式设置同一 FT_TEST_POSTGRES_URL、FT_KOTLIN_TEST_DB_USER=huangwenlong；ledger／普通合同指向 postgres-5432，准备合同指向 candidate-query-1105-preparation-postgres，执行同一准确测试集合并强制重跑，共 19 类／143 方法，0 failure／error／skip。仅有 SQLite 原始操作场景的 1 方法不冒充 PG 验证；双库均实际跑到完整 39 项条目仓储回归。测试不自动探测或启动数据库；schema 用例仅在已配置专用测试实例内创建及清理自己的临时 _test 数据库，不覆盖既有合成库。

main-platforms.log 沿公共 Gradle 参数并关闭 Kotlin／JS／Wasm incremental，执行 shared 的 ImportTaskViewModelTest／CashRecordTaskViewModelTest（2 类／101 方法，0 failure／error／skip）及 shared JS、Wasm、Android、iOS Simulator／Arm64 主代码编译，通过。main-codegen.log 的 backend:codegenTest 通过，schema 初始化、校验及生成器均要求 54；代码生成器未连接或改写真实账本。main-web-node.log 的 5 项 Node／真实 Chromium 控件生命周期及键盘回归通过。

独立上下文完成范围化 5a 和实际独立 child 的 5b：比较 bcf7f8ef→d63ecdae 新合同和合并前 65be32d9 实现，复核解析／worker 安全协议、共享规则单源、schema、路由／DI／OpenAPI、会话容量／追加／收据时序、分类与文档。发现三个自包含方法被整类分类误排、README 不兼容的旧 Python 同库回退说明两项 P2，已分别改为方法级分类及明确 54 回退边界，增量复核关闭，无本轮剩余 finding。端侧渠道／XML／汇总等尚未接线项保留上述任务，不以只读评审或现有服务端测试宣称完成。

main-web-smoke-fixed-port.log：Chromium 151.0.7922.34，URL http://127.0.0.1:25186/，视口 390×844、768×900、1440×900。使用现有 runner 的 --grep 精确选择 account panel switches workspace、one batch request expands currencies、preview capacity refusal 两状态、capacity rejection emits one batch 和 concurrent append keeps，共 6 项全部通过；实际执行工作区切换／管理／退出登录、账户映射下一步的批量币种补齐、422 超大预览回准备页及删替恢复、503 容量拒绝保留状态、一次提示和三 worker 并发追加／失败／删除及总容量阻止继续。运行前按 freshness gate 从当前代码构建；同一套浏览器合成 API 夹具验证真实页面，不代表真实后端端到端或新本地 owner 已接通。断言未发现非预期控制台／静态资源／网络错误，预期拒绝状态按用例白名单处理。截图保存在缓存 main-web-smoke-fixed-port 下，包括 account-batch-currency-1440.png、容量恢复、并发追加和失败删除；退出登录截图位于 /tmp/compose-app-shell-signed-out-768.png。固定端口复跑用于补全可复现 URL 证据，先前随机端口的 6 项亦通过，不累计为额外场景。

Cross-platform Impact Check：本次保留 main 已有共享页面／文案和原生可访问性实现，无新增页面或设计取舍。Web 的主流程冒烟结果如下；Android／iOS 按默认范围只做编译及单测，不宣称设备 QA。原始账单、外部缓存解锁策略、PowerSync 实验及最终本地写集页面仍不在本轮合并验收范围。不归档未完成 change，不创建 PR 或发布。

### 可信来源准备实施前核对

比较基线 ec233fe9，2026-11-06（UTC）。独立 5a 与实际独立 5b 只读核原 OperationRules、SourceRead、CashRecordWriter／批量新建 writer、组成项持久化及 source effects；无新增 wire 或产品决策需求，D3 已先明确来源标记不是 edited_fields 并集、公共编辑统一走 C、source fingerprint 与组成项隐藏元数据按旧值／默认值派生，不能直接调用旧 writer 消费 client current／manual_fields。后续先失败覆盖伪造／清空标记、正式源 edited_fields 绕 C、草稿合法缺项与明确金融／原文 effects、隐藏元数据任改、真实同值 no-op／修订和 C 只执行一次。该段仅规划，不计实现／测试通过，不关闭源保护原读取缺口或 3.2／3.4。语义沿原文件规则细化，无新增通用政策；strict 与 diff 检查通过。

### 首次关系公共选择事务验证及收尾证据

比较基线／验证 HEAD：94f63d04，2026-11-06 06:51 UTC。applyCashImportWriteSetFirstCommonChoices 仅用于已确认整联合批次首次写入：有效 B／post-C 人工覆盖与批次准备先完成，接受组的全部父项按 typed ref 一次解析，核正数及逻辑别名，随后沿原批次／组序逐组执行 chooseCommon。组内父项 distinct 后按真实 Long 十进制文本排序，不按 owner 或跨组去重，不额外 flush、改 standalone 或重选 B。共享 prepare 为实际 backend 消费公开、算法本体未变。独立规划复核依据原调用链确认：在 B 固定／批内一致及完整旧组不拆前提下，组内父序差异属服务端元数据与 checked 异常先后，不增 parent-order wire；异常整事务拒绝，不改选对象。

cash-import-write-common-apply-red.log 先复现缺入口；空执行基线在 assertion-red.log 中 5 方法／4 个真实断言失败。green.log 首次实际执行被严格图门拒绝：旧 seed 留下一个未选中正式行无 component。新增 B 专用 seedFirstCommonRows 补齐所有正式流水的组成项，未改旧夹具或放宽图守卫；complete-fixture.log 的原 5 方法于 10 秒通过。补跨组与分配例后 expanded.log 的 7 方法于 10 秒通过；最终加强反文本序输入与唯一解析顺序断言，不改变实现。

sqlite-jvm.log 沿本页公共 Gradle 参数执行 backend:test --tests '*CashEntryRepositoryMigrationTest'、正常 backend ktlintCheck／detektMain／detektTest 和领域完整 jvmTest／ktlintCheck／显式 detektMetadataCommonMain／detektJvmTest，2 分 8 秒成功。SQLite 显式 FT_KOTLIN_CONTRACT_DIR=<缓存>/sqlite；postgres.log 设置该变量为 <缓存>/postgres-5432、FT_TEST_POSTGRES_URL=postgresql+psycopg://huangwenlong@127.0.0.1:5432/ft_offline_ledger_1104_test 及 FT_KOTLIN_TEST_DB_USER=huangwenlong，以 :backend:test --rerun 强制复跑同完整类，9 秒成功。双库各 1 类／39 方法（原 16＋前置 8＋收尾 8＋B 7），完整领域 JVM 164 类／621 方法，均 0 failure／error／skip。XML 原样保存至 <缓存>/cash-import-write-common-apply-xml/{assertion-red,backend-sqlite,backend-postgres,domain-jvm}，测试未自动探测／启动数据库。

新合同实际验证 C→B 公共值、逐父修订、来源全集与 standalone 分离，缺 post-C 人工字段在解析前零 SQL 拒绝，第二唯一解析异常原对象及别名／非正数在任何 B 前拒绝，defer／ignored／空命令零 SQL，第二文本序父项溢出时整事务事实／版本回滚。另 3 父两接受组的同一 parent 跨组仍推进两次、仅解析一次且按组序首次出现为 0／2／1；两个无 owner 父以反实际文本序输入，用固定服务端 UUID 分配验证实际文本序执行及独立值不被 B 覆盖。前者不是不同 parent 已共用旧 owner 的新执行器专项；后者未强制跨位数 ID，不单独声称击中 numeric-vs-text 差异，源码明确使用 Long::toString。均不构成完整关系写入／去重／新服务请求证明。

platforms.log 使用同公共参数及 Kotlin／JS／Wasm incremental=false，执行领域 JS、Wasm、Android、iOS Simulator 和 iOS Arm64 主代码编译，46 秒成功。共享本体仅公开给后端，完整领域 JVM 已跑；本片不重复三端运行矩阵，不声称设备或浏览器 QA。独立 5a／实际独立 5b 实现和新增两方法复核无遗留 finding，范围与未覆盖条件已按实际证据限定。Cross-platform Impact Check：执行器仅服务端，三端共享语义不变，相关目标已编译；无 UI／文案／路由变化。D3、tasks、两地图及 apply 指引同组维护；无新领域术语、HTTP 用法、依赖、配置、schema 或迁移，README／词表／示例无需改写。没有新增用户选择或通用规范，可独立回退；strict 与 diff 检查通过。外层须落实整批同意图去重并连同 B 消费、完整旧组不拆与授权／依赖，不能对已存在批次再次调用；完整来源／关系／收据和公开服务仍待接入，不关闭 3.x 或整个 change。

### 真实前置编辑事务验证及收尾证据

比较基线／验证 HEAD：6a2626bd，2026-11-06 06:35 UTC。applyCashImportWriteSetEntryEdits 在受管事务内复用原 applyEdit：先以可信 before 核全部原始 C 的旧归属／修订并归并，全部保留 C 的真实父项解析、正数和别名检查先于任何编辑；原旧修订或明确 null 原样交仓储，不取 current 补造通过。prepareCashImportWriteSetEntryEdits 为真实 backend 消费公开，算法本体不变。不加额外 flush／catch／提交，保留原仓储缓存与写入时点，最终 flush 由外层流程处理。

cash-import-write-entry-apply-red.log 先复现缺入口及测试的跨模块 nullable smart-cast；本地观察值固定后，空实现基线在 assertion-red.log 的 4 方法全部真实断言失败。green.log 首四方法于 20 秒通过。补解析、空输入及第二 C 溢出后，sqlite-jvm.log 沿本页公共 Gradle 参数执行原完整 CashEntryRepositoryMigrationTest、backend 正常 ktlintCheck／detektMain／detektTest 和领域完整 jvmTest／ktlintCheck／显式 detektMetadataCommonMain／detektJvmTest，2 分 7 秒成功；领域 164 类／621 方法，均 0 failure／error／skip。

新增两个真实 New 父映射后，final-sqlite.log 因测试 Map 的窄类型推断编译失败，不计通过；显式 CashWriteReferenceDto key 后 final-sqlite-corrected.log 执行同完整仓储类及正常 backend ktlint／detektTest，1 分 27 秒成功。SQLite 使用 FT_KOTLIN_CONTRACT_DIR=<缓存>/sqlite；postgres.log 显式使用 <缓存>/postgres-5432、FT_TEST_POSTGRES_URL=postgresql+psycopg://huangwenlong@127.0.0.1:5432/ft_offline_ledger_1104_test 和 FT_KOTLIN_TEST_DB_USER=huangwenlong，以 :backend:test --rerun 强制复跑完整同类，9 秒成功。双库各 1 类／32 方法（原 16＋收尾 8＋前置 8），0 failure／error／skip；XML 原样保存至 <缓存>/cash-import-write-entry-apply-xml/{backend-sqlite,backend-postgres,domain-jvm,assertion-red}，测试未自动探测或启动数据库。

真实合同覆盖同旧 owner 的两个 C 只推进一次且源值／独立值不变、后项坏观察／相冲突补丁在解析和 DML 前拒绝、已有无 owner 的 null 创建恢复、第二条目真实修订已变或 Long.MAX_VALUE 溢出时整事务事实／版本回滚、第二唯一解析的异常原对象及 alias／非正数零 SQL 拒绝、空命令零 SQL 与已结束上下文。另从真实写前 before（账户已在、流水未插入）开始，在同事务插入两个 source／component 并取数据库分配 ID，以两个 typed New 引用执行 C，核两个独立条目／revision=2、各自 C／standalone、来源备注不变及 ledger_seq 一次推进。SQL 来源夹具只准备该阶段条件，不是完整来源写协议、身份合法性或新公开服务证明；没有用插入后事实替换 before。

platforms.log 沿公共参数增加 Kotlin／JS／Wasm incremental=false，执行 :business-domain:compileKotlinJs、compileKotlinWasmJs、compileAndroidMain、compileKotlinIosSimulatorArm64 和 compileKotlinIosArm64，49 秒成功。本片共享函数仅 internal→public、函数体未变，领域完整 JVM 与受影响真实后端已运行，不重复上片三端运行矩阵或冒称本片设备／浏览器通过。

独立 5a 及实际独立 5b 实现和新增四个方法复核无代码 finding。文档按复核精确限定：applyEdit 检查修订／存在性，不独立对照 UUID；C 前 owner 不被同修订重挂依赖外层同事务基线／依赖及禁止提前 B／reconcile。正式来源写集保存不执行 C，由 Submission 的 entry_pre_edits 阶段唯一执行；维护 CashRecordWriter 自带 entry_edit，不能叠接到该导入来源阶段，来源阶段不得提前改变旧条目修订。Cross-platform Impact Check：新增执行器仅 JVM 服务端，共享准备核心语义不变，各目标已编译；没有页面／用户文案／路由变化，不新增浏览器或设备 QA。D3、tasks、两级地图及 apply 指引同组维护；无新领域术语、HTTP 入口、README 用法、依赖、配置或迁移，其余无需改写。按既有合同落实，没有新增用户选择或通用规范；可独立回退。strict 与 diff 检查通过。B 首次写入／去重消费、关系、完整授权及收据仍待外层组合，3.2／3.4 与整体 change 不标完成。

### 真实条目事务收尾验证及收尾证据

比较基线／验证 HEAD：5d42fb8d，2026-11-06 06:23 UTC。finishCashImportWriteSetEntries 在外层受管事务、写前授权／依赖及 C／B 后执行；全部实际根／成员引用先解析一次并缓存，核正数、重复／别名／交叠及两侧完整分区，再复用原 reconcile、按 edited_fields 做普通 D patch，flush 后完整事实核对。真实当前修订只用于已授权 D 执行，不能补造旧观察；全部失败交外层 db.write 回滚。两个既有 domain 函数为真实 backend 消费公开，不增 façade、DTO 或分配服务。本片未接完整源写入／关系／收据，3.2／3.4 保持未完成。

cash-import-write-entry-finish-red.log 先复现缺入口；仅 reconcile 的最小基线在 assertion-red.log 的 5 方法中 3 项真实断言失败，XML 留于 <缓存>/cash-import-write-entry-finish-xml/assertion-red。green.log 首 5 方法于 20 秒通过；补 3 方法后，sqlite-jvm.log 沿本页公共 Gradle 参数执行 backend:test --tests '*CashEntryRepositoryMigrationTest'、正常 backend ktlintCheck／detektMain／detektTest 及完整领域 jvmTest／ktlintCheck／显式 detektMetadataCommonMain／detektJvmTest，2 分 9 秒成功。独立复核的非阻断 P3 已将无物化 SQL 断言由 cash_projection_ 收严到 cash_projection，覆盖 cash_projections 基表。final-sqlite.log 再跑同整类及 backend 正常 ktlint／detektTest，1 分 28 秒成功；实现／domain 未变的 main 静态和共享运行证据仍适用。

SQLite 使用 FT_KOTLIN_CONTRACT_DIR=<缓存>/sqlite；final-postgres.log 显式设置 <缓存>/postgres-5432、FT_TEST_POSTGRES_URL=postgresql+psycopg://huangwenlong@127.0.0.1:5432/ft_offline_ledger_1104_test 和 FT_KOTLIN_TEST_DB_USER=huangwenlong，以 :backend:test --rerun 强制跑同类，8 秒成功。双库各 1 类／24 方法（原 16＋新 8），完整领域 JVM 164 类／621 方法，0 failure／error／skip；测试未自动探测或启动数据库。真实成功路径区分 S／C／B／D，另启连接核全部实际条目／独立值及每事务一次 ledger_seq；晚期结果不符／修订溢出回滚全部事实与版本。10 种完整分区拒绝、非正数解析、第二唯一引用的程序异常原对象、空命令零 SQL、去除 boundary 的上下文及已结束上下文均有断言。fixture 的来源写入与 C／B 由测试准备；Existing 旧依赖不是完整新服务合法请求证明，不将这些测试称为完整提交协议回归。

platforms.log 沿公共参数增加 Kotlin／JS／Wasm incremental=false，JS／Wasm／Android host 各选 *CashWriteSet*Test* 和 *CashImportWriteSet*Test*，各 13 类／121 方法均 0 failure／error／skip；iOS Simulator 测试及 Arm64 主代码仅编译。矩阵 1 分 31 秒成功，最终 backend 断言改动不影响这些共享源码结果。原 XML 保留在对应 backend-sqlite／backend-postgres／domain-jvm／domain-js／domain-wasm／domain-android。

独立 5a 和实际独立 5b 实现及补充测试复核无遗留 finding；P3 已修复并重新双库验证。Cross-platform Impact Check：服务端阶段新增真实条目写入，共享函数只开放既有算法给后端，三端逻辑不改，完成相称编译与共享回归；没有 UI／文案／路由变化，不新增页面浏览器或原生设备 QA 声明。D3、tasks、两级地图及 apply 指引同组核对；README／词表／HTTP 示例／依赖／配置／迁移无新入口或概念，不改写。全事实 reader 明确保留一次全工作区 15 集合 O(事实量) 成本，后续整服务性能验收须覆盖；完整授权、旧拓扑、C／B 与幂等／退休收据仍归外层服务。按既有业务合同实施，无新增用户产品选择或通用规范；本片可独立回退，无 schema 变更。strict 与 diff 检查通过，整个 change 未完成。

### 前置公共编辑合并验证及收尾证据

比较基线／验证 HEAD：95752988，2026-11-06（UTC）。内部 prepareCashImportWriteSetEntryEdits 先对全部原始编辑执行动作／引用／金融固定门，再归一公共补丁，按写前真实旧 owner 合并至首个有编辑的交易写项；后续同 owner 的 C 去除，原依赖读集、交易顺序和来源字段原样保留，新建／无 owner 各自独立。原 CashImportOperationRules 的字段冲突循环改为共用 mergeCashImportEntryPatch，保留 cast、逐操作 entry_edit_group 标记、整组完成后首补丁替换与后续 C 去除的原时点；发生冲突时已合入的 prefix 与 null presence 语义不变。新 typed 入口返回副本，不执行 applyEdit、不写库或证明完整三阶段效果；3.2／3.4 仍未完成。

cash-import-write-entry-edits-red.log 先因缺入口编译失败；assertion-red.log 的 9 方法／4 项真实断言失败复现缺合并／重复 C 未去除／冲突漏拒，原 XML 在 <缓存>/cash-import-write-entry-edits-xml/assertion-red。green.log 于 18 秒通过。新 9 方法覆盖相容字段合并、不同值冲突、明确分类 null 相同／冲突、首编辑行顺序与无编辑行、后续过期修订／错 owner／null 观察不得丢弃、New／Existing 同文本及无 owner 隔离、后续非法财务补丁不得消失、无编辑／空命令，以及 Any? 原类型部分修改与 null 冲突时点。

sqlite-jvm.log 沿本页公共 Gradle 参数执行完整领域 jvmTest／ktlintCheck／显式 detektMetadataCommonMain／detektJvmTest 及 backend:test --tests '*CashImportOperationTest'，33 秒成功。领域 164 类／621 方法，SQLite 原完整操作规划类 1 类／2 方法均 0 failure／error／skip；既有金额 canonical 对照保持，不将该矩阵说成完全没有归一。FT_KOTLIN_PLAN_BUILD_CONTRACT_DIR 显式为 <缓存>/sqlite；测试只 seed 合成 operation-workspace。postgres.log 设置该变量为 postgres-5432，显式 FT_TEST_POSTGRES_URL 和 FT_KOTLIN_TEST_DB_USER=huangwenlong 指向既有 ft_offline_ledger_1104_test，使用 :backend:test --rerun --tests '*CashImportOperationTest' 强制重跑，6 秒成功，同 1 类／2 方法均 0；未自动探测／启动库。它们保护既有操作规划，不充当新写集事务写入证据。原 XML 在 domain-jvm／backend-sqlite／backend-postgres。

platforms.log 沿公共参数增加 Kotlin／JS／Wasm incremental=false，JS／Wasm／Android host 分别选择 *CashWriteSet*Test*、*CashEntryAllocationEffectsTest*、*CashImportWriteSet*Test*、*CashImportOperationRulesTest*、*LocalCashImportOperationTest*；iOS Simulator 测试及 Arm64 主代码仅编译，未运行设备 QA。整条命令 1 分 33 秒成功；原 XML 在对应 domain-js／domain-wasm／domain-android。三运行时各 16 类／126 方法，0 failure／error／skip，终态证据已独立核对。

独立 5a 和实际独立 5b 无代码 finding，核对先验旧观察、真实 owner 分组、null presence、原操作异常／标记时序、输入不变与线性复杂度。Cross-platform Impact Check：三端共享准备／原操作规划受影响，native 双库完整两方法回归；没有 UI、文案、路由或交互变化，不新增浏览器冒烟或设备运行声明。D3、tasks、两级地图和 apply 指引同组维护；词表、README、接口示例及配置无新概念、可用入口、依赖或迁移，其余无需改写。按已有原合并合同落实，没有新增通用规范或产品选择；可独立回退。OpenSpec strict 与 diff 检查通过，真实 C／B／D 执行与原子写集仍待接线。

### 实际条目业务结果核对验证及收尾证据

比较基线／验证 HEAD：8dec1b80，2026-11-06（UTC）。内部 validateCashWriteSetEntryResults 仅归一用户声明的最终条目，空声明不读取实际对象或解析引用。实际条目／成员由服务端写后完整事实构建唯一索引，按 owner 一次取得完整成员集合；核实际根、成员及五公共值、公共人工字段和每成员完整独立值／标记。两个逻辑引用解析到同一实际成员或重复实际条目须拒绝；实际字段不重归一，flags 按集合解释历史顺序，程序异常不降为业务失败。旧依赖及完整拓扑用写前事实验证，完整受影响覆盖、未声明退休／孤立对象、授权和真实事务仍由服务负责。此门不修改／选择值、不分配身份或接 HTTP，3.2／3.4 不勾选。

cash-write-entry-results-red.log 先因缺核对入口编译失败；green.log 的首 11 方法于 25 秒通过；补实际分配大 ID 文本和坏持久 flags 的传播后，jvm.log 以本页公共 Gradle 参数执行领域完整 jvmTest、ktlintCheck、显式 detektMetadataCommonMain、detektJvmTest 及 backend:compileTestKotlin，44 秒成功。163 类／612 方法均 0 failure／error／skip，原始 XML 在 <缓存>/cash-write-entry-results-xml/domain-jvm。新增 13 方法覆盖五公共字段分别不符、同值错根、请求漏／增成员及未请求实际成员、UUID／数字锚点／修订由服务端决定、flags 集合及属性数组顺序、来源／C／D 分离、完整 standalone 多余字段、解析别名重复成员、重复 owner、缺实际根、无关损坏字段不扩范围、空结果、回调程序故障、超 JS 安全整数的实际分配文本及坏持久 flags。

cash-write-entry-results-platforms.log 使用同参数及全局 Kotlin／JS／Wasm incremental=false，选择 *CashWriteSet*Test*、*CashEntryAllocationEffectsTest*、*CashImportWriteSet*Test*，JS／Wasm／Android host 各 13 类／114 方法，0 failure／error／skip；iOS Simulator 测试及 Arm64 主代码仅编译。整条命令 1 分 32 秒成功；原始 XML 在同证据目录的 domain-js／domain-wasm／domain-android。没有将 synthetic 写后条目快照冒作真实导入或 DB 写入证据；本片没有 SQL 消费方，不另跑双库，不替代后续真实原子服务矩阵。

独立 5a 和实际独立 5b 无 finding，含新增回归、异常／完整 owner 边界及线性复杂度的复核；最终证据与文档已独立复核。Cross-platform Impact Check：三端共用纯结果核对受影响，backend 仅编译共享变化；无 UI、文案、路由或交互，不新增页面／原生设备 QA。D3、tasks、两级地图和 apply 指引同组更新；词表、README、配置／示例无新概念、可用接口、依赖或迁移，其余无需改写。无新的用户产品选择或通用规范，按原业务结果与服务器元数据所有权落实；本片可独立回退。OpenSpec strict 及 diff 检查通过。

### 生效公共选择完整性验证及收尾证据

比较基线／验证 HEAD：e9c3f2e4，2026-11-06（UTC）。prepareCashImportWriteSetCommonChoices 先保留原动作范围错误顺序，再归一公共输入并复用原联合批次与同一次最终确认图。按每批全部边端点命中正式多成员投影，核所有接受组的生效 B 字段／presence／值一致，覆盖可信旧条目及 C 编辑后的公共人工字段；同一旧 owner 的 C 跨成员传播，新／无 owner 父项按类型化引用隔离。单成员和范围外人工字段不增加要求，不从最终 D 或 standalone 反授权。返回归一的原批次，不选择自动首值、不分配 ID 或写库；完整三阶段效果、幂等消费和真实服务仍待接入，3.2 不勾选。原预览仍按原规则计算 B；旧服务曾重建完整计划，新服务按明确五字段选择和完整效果建立信任边界，不将旧 writer 描述为原已信任客户端结果。

cash-import-write-common-choices-red.log 先因缺入口编译失败；assertion-red.log 的 8 方法／5 项真实断言失败复现不同接受补丁和人工字段遗漏，原 XML 在 <缓存>/cash-import-write-common-choices-xml/assertion-red。初始 8 方法 green 通过；扩展测试首次失败分别是新 import 夹具缺稳定来源绑定、最终结果夹具缺旧条目依赖，均补完整合法输入后执行，不放宽更早图／引用门。最终 11 个新方法覆盖非接受桥、所有扫描投影、明确分类清空、旧闭包、前置编辑、同 owner 其他成员、新／无 owner、类型化同文本引用隔离、最终结果反授权、独立批次及无接受组。

cash-import-write-common-choices-jvm.log 使用本页公共 Gradle 参数，执行 :business-domain:jvmTest、ktlintCheck、显式 detektMetadataCommonMain、detektJvmTest 和 :backend:compileTestKotlin，51 秒成功。JVM XML 原样保存至 <缓存>/cash-import-write-common-choices-xml/domain-jvm，162 类／599 方法，0 failure／error／skip。platforms.log 同参数增加 -Pkotlin.incremental=false、-Pkotlin.incremental.js.ir=false、-Pkotlin.incremental.wasm=false，依次执行 jsNodeTest／wasmJsNodeTest／testAndroidHostTest，各选 *CashWriteSet*Test*、*CashEntryAllocationEffectsTest*、*CashImportWriteSet*Test*，各 12 类／101 方法、0 failure／error／skip；iOS Simulator 测试及 Arm64 主代码仅编译。矩阵 1 分 33 秒成功；原 XML 在 domain-js／domain-wasm／domain-android。未运行原生设备 QA，不宣称通过。

独立 5a 和实际独立 5b 关闭一项性能 P2：完整成员 Set 不能按每个端点重复 hash，改为整数投影索引去重，每个相关投影只遍历一次；当前无残余代码 finding。额外成本为既有全图校验及线性索引／公共字段扫描，不重跑启发式。Cross-platform Impact Check：三端共享纯校验受影响，backend 编译通过；没有 HTTP／DB 消费方或 UI、文案、路由变化，不新增真实双库或浏览器／设备运行声明，完整 change 的最终门禁仍保留。D3、delta、tasks、两级地图及 apply 指引同组更新；核对词表、README、配置、接口示例，无新术语、依赖、迁移或可用端点，其余无需改写。落实已确认端侧计算／服务端校验边界，无新增通用产品规范；本片可独立回退。最终 OpenSpec strict、diff 检查及独立证据核对通过；收尾旧待办措辞已原位统一当前职责。

### 文件关系联合批次准备验证及收尾证据

比较基线／验证 HEAD：1c4ea221，2026-11-06（UTC）。internal prepareCashImportWriteSetRelationBatches 先核既有文件范围，再复用原 cashRelationCommitGroups：旧边仅 active confirmed 连通，全部新边及其明确选择组联合分区共同参与，包括 defer／ignore；以明确组列表首次批次出现和批次内组序返回。私有 typed 字符串键仅用于连通计算，隔离 New／Existing 同文本，不充当存储 ID 或执行顺序；不信客户端 decision_group_id 或关系数组排列。返回组保持原分区／公共补丁，不扩旧闭包成员、不写输入或账本。公共继承、三阶段效果、幂等组消费及实际服务仍待实现，3.2 不关闭。

cash-import-write-relation-batches-red.log 因缺入口先失败。green 7 方法通过；终态 jvm.log 沿 NODE_ENV=development、外部 lint worker init、--no-daemon／--max-workers=1／--no-configuration-cache／--no-build-cache、in-process 及 Xmx3072M 执行完整 domain jvmTest、ktlintCheck、detektMetadataCommonMain、detektJvmTest 及 backend compileTestKotlin，通过；JVM 161 类／588 方法，全 0 failure／error／skip。platforms.log 加 -Pkotlin.incremental=false 并关闭 JS／Wasm IC，jsNodeTest／wasmJsNodeTest／testAndroidHostTest 各选 '*CashWriteSet*Test*'、'*CashEntryAllocationEffectsTest*'、'*CashImportWriteSet*Test*'，均 11 类／90 方法，全 0；domain compileTestKotlinIosSimulatorArm64 及 compileKotlinIosArm64 通过，仅计编译。当前原样 XML 保存于 <缓存>/cash-import-write-relation-batches-xml/domain-jvm、domain-js、domain-wasm、domain-android。没有 SQL／DDL 或事务消费者，不新增双库运行声明。

独立 5a 和实际独立 5b 已对照原纯分组、DecisionRules 和 native saveGroups 复核，无本片 finding；覆盖非接受桥的批次首见、旧边三态／存活资格、选择组联合、伪 joint ID、关系数组反转、引用类型隔离及原输入不变。增量成本为既有全事实 scope 检查、原联合分组／排序及线性索引，无新二次全组扫描。Cross-platform Impact Check：三端共享内部准备器受影响，backend 编译通过；无 UI、文案、路由或交互变化，不新增浏览器／设备 QA。D3、任务、两级地图及 apply 指引同组维护；词表、README、配置／示例无新术语、公开路由或部署变化，无迁移，可独立回退。落实原分组规则，无新增通用产品政策。B 的自动首值排序边界按 3.2／D3 以端侧生效补丁及可信字段覆盖处理，不拿结构分批通过当完整效果或元数据独立性证明。

### 有序关系选择组传输与输入验证及收尾证据

比较基线／验证 HEAD：ec255f04，2026-11-06 04:52 UTC。relation_groups 独立表达全部本次新关系选择组的完整边分区及顺序；common_fields 传递核对后生效公共补丁，内部 category_id:null 沿原清空语义，对象本身不得 null；空补丁的可信字段覆盖由本页生效公共选择完整性检查约束。不新增 action、parents 或 suggestion 权威字段。文件范围门检查非空／完整／不重叠的 New 边分区、三种合法状态且全部 active、仅 confirmed 组可带非空公共补丁及当前分类引用；独立五公共字段浅归一保留 presence／清空与空对象，不递归解码未知嵌套。既有 scope／topology 正例只补必需组声明，未削弱旧断言。此片不计算联合批次、自动继承、完整三阶段效果或实际事务，不关闭 3.2。

测试先行：relation-write-groups-contract-red.log 2 方法均因旧 wire 不认识关系组失败；新增 DTO 后 contract-green 全量通过。domain-red.log 6 方法／5 项真实失败复现缺分区、动作／类别及公共补丁校验。独立 5a／实际 5b 指出整组 inactive+{} 穿过的 P2，inactive-red.log 以 7 方法／1 项真实失败复现，再要求所有新边 active 修复；P2 已独立重审关闭。原始失败 XML 保存于 <缓存>/relation-write-groups-xml/contracts-red、domain-red、inactive-red。完整 entity codec 方法另独立断言来源 S、行前置 C、关系公共 B 和最终 D 的四个 JSON 路径，仅证明传输互不覆盖，不作为合法命令或效果对照。

终态 relation-write-groups-jvm-final.log 使用 NODE_ENV=development、外部 cash-write-set-lint.init.gradle、--no-daemon／--max-workers=1／--no-configuration-cache／--no-build-cache、in-process 及 Xmx3072M，执行两模块完整 jvmTest／ktlintCheck／detektMetadataCommonMain、domain detektJvmTest 及 backend compileTestKotlin，通过；contracts 10 类／26 方法，domain 160 类／581 方法，全 0 failure／error／skip。此前 jvm.log 因新测试漏 import 编译失败，不计通过。platforms.log 加 -Pkotlin.incremental=false 并关闭 JS／Wasm IC，contracts 全量 jsNodeTest／wasmJsNodeTest／testAndroidHostTest 各 10 类／26 方法；domain 各选 '*CashWriteSet*Test*'、'*CashEntryAllocationEffectsTest*'、'*CashImportWriteSet*Test*'，各 10 类／83 方法，全部 0 failure／error／skip。两模块 iOS Simulator 测试编译及 Arm64 主代码编译通过，仅计编译；当前原样 XML 保存于上述缓存 contracts/domain-jvm、-js、-wasm、-android 子目录。

独立 5a 与实际独立 5b 已重审生产实现及 P2 修复，无新增 finding；终态证据与文档已独立核对。Cross-platform Impact Check：三端共享 DTO／输入守卫受影响，backend 编译通过；无 UI、文案、交互或路由变化，不新增浏览器／设备 QA。没有新增 SQL／DDL 或事务消费者，不新增双库运行声明。D3、任务、两级功能地图及 apply 指引同组维护；词表、README、配置／示例没有新术语、公开路由或部署变化。沿原关系公共选择语义补齐表达，无新增通用产品政策；无迁移，可独立回退。完整导入、写入服务、实际加密缓存及页面仍未完成。

### 文件导入完整旧组验证及收尾证据

比较基线／验证 HEAD：`50cae6b0`，2026-11-06（UTC）。internal validateCashImportWriteSetEntryTopology 先文件动作范围及完整最终拓扑，再核所有受影响旧条目的全部真实成员和身份锚点：旧父须存活，并处于同一个已验证最终业务组。generic 拓扑只新增返回原闭包已触及的旧条目，不改变维护场景的拆分能力；仅列旧依赖不扩大写范围。完整公共效果、三阶段编辑、实际身份／退休及事务仍待接入，3.2 保持未完成。

`cash-import-entry-whole-group-red.log` 缺入口先失败，随后仅组合既有 scope 与 generic topology 的基线在 `cash-import-entry-whole-group-assertion-red.log` 运行 6 方法、3 项真实断言失败，复现漏掉拆组、删除旧成员及组外身份锚。失败 XML 原样保存于 `<缓存>/cash-import-entry-whole-group-xml/assertion-red/`。补完整旧组检查并增加正常整组合并用例后，green 7 方法通过；终态 `cash-import-entry-whole-group-jvm.log` 使用外部 lint worker init、NODE_ENV=development、--no-daemon／--max-workers=1／--no-configuration-cache／--no-build-cache、in-process 及 Xmx3072M，执行完整 domain jvmTest、ktlintCheck、detektMetadataCommonMain、detektJvmTest 及 backend compileTestKotlin，通过。JVM 159 类／574 方法，全部 0 failure／error／skip。

`cash-import-entry-whole-group-platforms.log` 关闭 JS／Wasm IC，jsNodeTest 与 wasmJsNodeTest 各选 '*CashWriteSet*Test*'、'*CashEntryAllocationEffectsTest*'、'*CashImportWriteSet*Test*'，均 9 类／76 方法、0 failure／error／skip；随后 Android 因 Kotlin 编译器 DirtyData／FqNames 增量内部异常失败，未计该轮通过。仅对未完成 Android／iOS 以 `cash-import-entry-whole-group-android-ios.log` 加 -Pkotlin.incremental=false 重跑，成功；Android 同选 9 类／76 方法，全 0。domain compileTestKotlinIosSimulatorArm64 及 compileKotlinIosArm64 通过，仅计编译。当前 XML 保存于上述缓存 domain-jvm／domain-js／domain-wasm／domain-android；没有新 SQL、迁移或真实事务消费者，不新增双库结果，不把旧原生矩阵当本服务证明。

独立 5a 和实际独立 5b 已复核实现，无 finding；范围含完整组、根与身份锚不同、合法整组合并、被 generic 接受而被 file 拒绝的三类缺口、只读依赖及错误先验，未把本辅助器当完整授权或效果。Cross-platform Impact Check：三端共享内部门受影响，backend 编译通过；无 UI／交互／路由／文案变化，不新增浏览器或设备 QA。D3、任务、两级地图及 apply 指引同组更新；词表、README、配置／示例无新术语、公开端点或部署变化。落实已批准的文件导入范围，无新增通用产品政策；无迁移，可独立回退。完整新写集提交、加密存储与页面仍未完成。

### 条目存储元数据所有权验证及收尾证据

比较基线／验证 HEAD：`61a18558`，2026-11-06 04:23 UTC。写集条目只声明完整业务根／成员和五公共值、人工／独立值；移除可写 target、identity_transaction、split_inherit 及 deleted_entries，严格解码拒绝旧字段。独立 entry_dependencies 保存真实旧条目修订，包括最终退休对象；引用预检核 existing、唯一、精确修订及前置编辑／成员真实旧归属，拓扑沿旧完整成员与最终组核覆盖及全部受影响旧依赖。读依赖本身不触发写范围，generic 拆分／删除拓扑用例不代表文件入口开放维护。完整旧组不拆／存活、合并前选择意图及三阶段效果、实际分配／退休／收据尚待完整服务验证，不关闭 3.2 或整个 change。

`entry-storage-ownership-contract-red.log` 两项真实序列化失败先复现旧协议依赖客户端元数据；失败 XML 保存于 `<缓存>/entry-storage-ownership-xml/contracts-red/`。改模型后首次 green 命令遇磁盘 ENOSPC，不计程序结果；仅清理本 worktree 已忽略且可重建的编译产物后，contract-green-retry 成功。终态 `entry-storage-ownership-jvm.log` 使用 NODE_ENV=development、外部 cash-write-set-lint.init.gradle（lint worker 1024 MiB）、--no-daemon／--max-workers=1／--no-configuration-cache／--no-build-cache、in-process、Xmx3072M，执行两模块完整 jvmTest、ktlintCheck、detektMetadataMain、domain detektJvmTest 及 backend compileTestKotlin，通过；另以 `entry-storage-ownership-static.log` 执行两模块明确的 detektMetadataCommonMain／detektJvmMain 任务；前述 detektMetadataMain 不替代它们，两模块 detektJvmMain 为 NO-SOURCE，不计实际分析。contracts 9 类／24 方法，domain 158 类／567 方法，全部 0 failure／error／skip。

`entry-storage-ownership-platforms.log` 沿相同公共参数并关闭 JS／Wasm IC；contracts 全量 jsNodeTest／wasmJsNodeTest／testAndroidHostTest 各 9 类／24 方法，domain 各以 '*CashWriteSet*Test*'、'*CashEntryAllocationEffectsTest*'、'*CashImportWriteSetScopeTest*' 运行 8 类／69 方法，全部 0 failure／error／skip。两模块 compileTestKotlinIosSimulatorArm64 与 compileKotlinIosArm64 通过，仅计编译。当前 XML 原样保存于上述缓存 contracts-jvm／domain-jvm 及 contracts/domain 的 js／wasm／android 子目录。没有新 SQL／DDL 或事务消费者，不重复双库，也不以旧仓储结果冒称本协议已真实提交。

独立 5a 和实际独立 5b 已复核实现，无新增 finding；模型删除的旧权威断言由业务组／根／完整闭包及旧读集拒绝承接，未缩减已批准行为。Cross-platform Impact Check：三端共享契约及纯校验受影响，backend 编译通过；无 UI、交互、文案或路由变化，不新增浏览器／设备 QA。D3、任务、两级功能地图及 apply 指引同组维护；词表、README、配置／示例无新术语、部署或公开端点接线变化。沿用户已定服务端元数据所有权，无新增通用产品政策；无迁移，可独立回退。实际加密缓存、完整导入、提交及界面仍未完成。

### 行核对前置公共补丁验证及收尾证据

比较基线／验证 HEAD：`8fd815f9`，2026-11-06 03:58 UTC。原 OperationRules 将行公共 patch 与来源 values 分开，单条及批量新行写入均在关系前 applyEdit，最终 entry_choices 在协调后执行。前置 C 不能由来源 S／最终公共 D 或 standalone 反推。本片先回写 D3／导入 delta，再新增可选 transaction.entry_edit：旧条目依赖必填可空，非空须匹配真实旧父成员及修订；新父或原无条目可明确观察为空，旧对象最终退役也不丢依赖。partial patch 只允许非空的五公共字段子集，浅层校验后复用原值归一，明确清空保留；未来传输须显式编码必填 JSON null，不改全局客户端 Json。

`entry-before-relation-edit-model-red.log` 先因缺模型失败；加入模型后 `entry-before-relation-edit-validation-red.log` 以 3 项真实断言复现缺归属／修订／存在性及 patch 归一检查。独立审查 P2 指出前置分类不能被最终空分类掩盖；`entry-before-relation-edit-category-red.log` 再以分类与空补丁 2 项真实失败复现。补前置 String 分类目录引用及空补丁拒绝后转绿；null 与坏类型不强转、历史独立分类不扩大资格检查。失败 XML 原样保存于 `<缓存>/entry-before-relation-edit-xml/validation-red/`、category-red。

终态 `entry-before-relation-edit-jvm-sqlite.log` 使用本页公共 Gradle 参数，执行两模块完整 jvmTest、正常 ktlintCheck／detektMetadataCommonMain／detektJvmTest、backend ktlintCheck／detektTest 及指定 backend:test。contracts 8 类／22 方法，domain 158 类／566 方法；contracts detektJvmTest 为 NO-SOURCE，不计实际分析。SQLite 与 `entry-before-relation-edit-postgres.log` 中显式环境下强制 --rerun 的真实 PostgreSQL 同组各 3 类／23 方法（仓储 16、版本／保存点 5、原公共选择 2），全部 0 failure／error／skip。新仓储用例实际提交后另起读取，核 S／C／D、两成员独立值及人工标记；这是原仓储时序回归，不冒充完整文件提交或新写集服务。两项纯协调分配对照分别检查完整五公共值、人工集合和每成员 standalone，并明确断言 UUID 存续、数字锚点和组序可不同；仅覆盖完整旧组不拆前提，不宣称身份协议已实现。

`entry-before-relation-edit-platforms.log` 沿相同公共参数执行 contracts 全量 JS Node／Wasm Node／Android host，每目标 8 类／22 方法；domain 每目标使用 '*CashWriteSet*Test*'、'*CashEntryAllocationEffectsTest*'、'*CashImportWriteSetScopeTest*'，各 8 类／68 方法，全部 0 failure／error／skip。两模块 iOS Simulator 测试编译和 Arm64 主代码编译通过，仅计编译；原样 XML 位于上述缓存的 contracts-jvm／domain-jvm／backend-sqlite／backend-postgres／contracts-js／contracts-wasm／contracts-android／domain-js／domain-wasm／domain-android。

独立 5a 及实际独立 5b 已重审分类及空补丁修复，P2 关闭，无本片遗留 finding；终态 XML／日志与文档已独立核对，无新 finding。Cross-platform Impact Check：三端共享 DTO／引用与归一受影响，backend 生产写入时序未改；无 UI、文案、路由或交互变化，不新增浏览器／设备 QA。D3、delta、tasks、两级地图及 apply 指引同组更新；词表、README／配置／示例无新术语、公开端点或部署变化。本片补齐既有文件编辑表达，不新增通用产品政策；无迁移，可独立回退。完整标记效果、旧组闭包、身份所有权、同对象补丁合并及实际事务仍待服务接线，3.2 及整个 change 保持未完成。

### 文件导入动作范围验证及收尾证据

比较基线／验证 HEAD：`c7bd6c8e`，2026-11-06（UTC）。internal validateCashImportWriteSetScope 先复用引用、金融归一及固定字段门，再拒绝父项删除、无文件来源事项／拆分决定及已有关系重写。只约束被写的存活 import／legacy 父项，不排除只读 manual／manual_transfer 上下文；绑定禁止从另一存活父项迁移，已软删旧 owner 的原复用路径保留。幂等同事实 no-op 须先解析去除，可信事实缺父传播内部错误，不猜作已删除；该门没有公开服务消费者，不代表完整权限或效果合法。

`cash-import-write-scope-red.log` 因缺入口先失败；实现后 `cash-import-write-scope-jvm.log` 完整领域 JVM 157 类／559 方法，0 failure／error／skip。命令使用 NODE_ENV=development、现有外部 lint worker init、--no-daemon／--max-workers=1／--no-configuration-cache／--no-build-cache、in-process、关闭 JS／Wasm IC 及 Xmx4096M，执行 :business-domain:jvmTest、ktlintCheck、detektMetadataCommonMain、detektJvmTest 和 :backend:compileTestKotlin，全部成功，未关闭或缩小静态检查。6 项新增方法覆盖四种事项动作、两种拆分选择、自动问题退役、两种来源与两种状态组合、手工上下文与软删、旧关系三种状态、绑定复用／创建及输入不变、引用及固定冲突错误顺序。

`cash-import-write-scope-platforms.log` 沿相同公共参数，执行 jsNodeTest／wasmJsNodeTest／testAndroidHostTest，各用 --tests '*CashImportWriteSetScopeTest*'，每个目标 1 类／6 方法，均 0 failure／error／skip。iOS Simulator 测试编译和 Arm64 主代码编译通过，仅计编译。原样 XML 保存于 `<缓存>/cash-import-write-scope-xml/` 下 domain-jvm／domain-js／domain-wasm／domain-android。

独立 5a 和实际独立 5b 已核当前生产实现，无 finding；评审者未构建，最终 XML／日志及文档已独立核对，无本片遗留 finding。Cross-platform Impact Check：三端共享内部门受影响，原 backend 导入／维护调用未改；无 UI、文案、路由或交互变化，不新增浏览器／设备 QA。没有新增 SQL／DDL 或实际事务消费者，不重复双库或宣称新入口已接通。D3、tasks、两级地图及 apply 指引同组更新，词表／README／配置及示例无新术语、公开协议或部署变更；本片落实用户既定文件导入范围，无新增通用产品规则，无迁移，可独立回退。3.2 仍未完成。

### 最终条目值输入验证及收尾证据

比较基线／验证 HEAD：`7fced3e8`，2026-11-06 02:50 UTC。internal normalizeCashWriteSetEntryValues 复用原五公共字段值规则，成员 standalone 保持迁移 50／生产创建的完整五键对象；未知键、缺键和错误叶类型拒绝。attrs 在任何递归解码前核数组／字符串元素，深嵌套类型错误立即停止；只转换已知 InvalidCashValue，程序错不转为用户输入错误。字段集合仅接受原白名单并去重、Python 排序，不假定合法旧列表本已排序；standalone 历史分类只验 null／String，不新加当前目录资格。最终 split_inherit 必须空，本次独立拆分选择及所有无关 JSON／引用原样保留。归一不能证明标记来源可信、手工授权或实际最终效果。

`cash-write-entry-values-red.log` 因缺内部入口失败；`cash-write-entry-values-jvm.log` 完整领域 JVM 156 类／553 方法，全部 0 failure／error／skip，正常 ktlintCheck、detektMetadataCommonMain、detektJvmTest 及 backend compileTestKotlin 通过。新增 6 方法覆盖空文本、历史分类、未排序／重复字段集合和输入不变、非法人工／编辑字段、完整独立值的缺／多键及类型、公开 attrs 与 4096 层数组拒绝、最终三态标记及独立布尔选择保留。

`cash-write-entry-values-platforms.log` 使用公共参数但 JVM 堆为 4096 MiB，关闭 JS／Wasm IC；jsNodeTest、wasmJsNodeTest、testAndroidHostTest 各选择 `*CashWriteSet*Test*`，各 6 类／55 方法，全部 0 failure／error／skip；compileTestKotlinIosSimulatorArm64、compileKotlinIosArm64 通过，仅计编译。原样 XML 位于 `<缓存>/cash-write-entry-values-xml/` 的 domain-jvm／domain-js／domain-wasm／domain-android。没有新增数据库消费者或事务，双库不另跑，不能以此前仓储结果冒充当前完整提交服务证明。

独立 5a 和实际独立 5b 无本片 finding，核对完整对象的生产依据、窄异常转换、attrs 浅读、旧分类及集合语义和完成边界；终态证据及文档另在提交前核。Cross-platform Impact Check：三端共享内部输入规则受影响，backend 仅编译验证；没有 UI、文案、路由和交互变化，不触发浏览器或设备 QA。D3、tasks、两级地图及 apply 指引同组更新；词表、README、配置／部署及接口示例无新术语、依赖、wire 或迁移，无需改写。没有新增用户取舍或通用规范，可独立回退；完整身份分配重放、来源动作、保护标记及最终事务仍待实现，3.2 保持未完成。

### 最终条目拓扑验证及收尾证据

比较基线／验证 HEAD：`707376d3`，2026-11-06 02:38 UTC。internal validateCashWriteSetEntryTopology 复用原确认图构建结果、金融归一和精确引用；public graph 前置与错误顺序保持。有效草稿只建单成员计算组，不读取或伪造创建时间。原条目完整成员、最终组及显式写集种子按成员索引／队列展开，每组仅展开一次；逐显式条目拒绝空成员及组外根／身份，再对完整受影响最终成员和实际根核对。来源绑定的新旧父项均入种子，未改条目仍参与最终占用与结果核对；范围外未物化事实不强制补条目。该门仅核身份属于组内，不推导稳定身份或公共／人工／独立值，不作为授权或完整合法性凭据。

`cash-write-entry-topology-red.log` 因缺内部入口失败。`cash-write-entry-topology-jvm-final.log` 完整领域 JVM 155 类／547 方法，0 failure／error／skip；ktlintCheck、detektMetadataCommonMain、detektJvmTest 及 backend compileTestKotlin 均通过。新增 9 方法核对漏合并／拆分、错误根、组外身份、空条目、删除后残留、单独改绑定导致根变化、绑定迁移时旧父组漏写、无时间草稿及 New／Existing 同文本隔离、范围外不修复，以及原引用／图错误优先。绑定迁移用例保持新父组结果完整，只遗漏旧父组的必要更新，能够区分新旧 owner 种子；输入快照保持原样。

`cash-write-entry-topology-platforms.log` 用公共参数但 JVM 堆为 4096 MiB，关闭 JS／Wasm IC；jsNodeTest、wasmJsNodeTest、testAndroidHostTest 各选择 `*CashWriteSet*Test*`，各 5 类／49 方法，全部 0 failure／error／skip；compileTestKotlinIosSimulatorArm64、compileKotlinIosArm64 均通过，仅计编译。原样 XML 保存于 `<缓存>/cash-write-entry-topology-xml/` 的 domain-jvm／domain-js／domain-wasm／domain-android。内部校验未接真实写集事务或数据库读写，本片不新增双库运行结论；现有仓储矩阵沿前述公共选择专项，不以之冒充新服务验证。

独立 5a 及实际独立 5b 无残余 finding；设计预审的绑定 owner 漏项及显式空条目漏项均已落实并回归，计算组与条目状态分开，闭包改为索引队列后的增量复核亦通过。终态文档及证据提交前核对。Cross-platform Impact Check：三端同一内部写集校验受影响，backend 仅编译验证共享接口；无 UI、文案、路由或交互变化，不新增浏览器／设备 QA。D3、tasks、两级地图和 apply 指引同组更新；核对词表、README、配置、部署及示例，无新 wire、依赖、迁移或术语，其余无需改写。没有新增产品取舍或通用规范，可独立回退；完整身份选择、人工公共值、成员恢复、来源动作及服务事务仍待实现，3.2 未完成。

### 合并前公共字段选择验证及收尾证据

比较基线／验证 HEAD：`444680f3`，2026-11-06 02:21 UTC。原 chooseCommon 的公共字段过滤、完整归一、类别检查、逐父惰性取得条目和每父修订委托共享 CashEntryEditRules；native 保留原分类 SQL、缺条目的 singleton 创建及末尾 flush。返回布尔只控制原 flush 门，不表示值有变化。不同父项即使共用同一条目或同值，仍各增一次修订；独立值不随此选择改写，普通后置 patch 保留其单成员更新行为。类别资格未增加新规则，程序错及溢出继续传播，事务恢复仍由调用方负责。

`entry-common-choice-red.log` 先因缺共享入口失败。新增仓储 flush／standalone 回归先以原未改实现执行，`entry-common-choice-native-baseline.log` 1 方法通过；`entry-common-choice-identity-original.log` 再对 `444680f3` 原仓储源码执行固定 UUID 身份对照，1 方法通过，随后精确恢复新委托源码。该对照在两份独立合成工作区分别验证有／无 prechoose：新父项与旧单成员无人工冲突，最终保留条目分别为 a-new／z-old；断言根及身份、公共 note／manual、成员归属及两成员的独立 note／manual。它证明原身份的顺序依赖，不声称已比较所有公共字段或完成新写集效果校验。原始 XML 分别保存在 `<缓存>/entry-common-choice-xml/native-baseline/`／identity-original。

`entry-common-choice-jvm-static.log` 完整领域 JVM 154 类／538 方法，0 failure／error／skip；正常领域 ktlintCheck、detektMetadataCommonMain、detektJvmTest 及 backend compileTestKotlin、ktlintCheck、detektMain、detektTest 均通过。新增身份回归后的 backend ktlintCheck／detektTest 在 `entry-common-choice-sqlite-static.log` 再验通过，不变的 main 静态证据仍适用。`entry-common-choice-sqlite-static.log`／`entry-common-choice-postgres.log` 用公共 Gradle 参数及 `:backend:test --rerun` 运行 CashEntryRepositoryMigrationTest、LedgerEntryVersionContractTest、CashImportEntryChoiceTest、MutationPlanEntryTargetGoldenTest，各 4 类／23 方法，全部 0 failure／error／skip。显式合成环境和数据库用户沿上一协调专项，专用 PostgreSQL `_test` 不自动探测、启动或创建。XML 保存在 backend-sqlite／backend-postgres。

`entry-common-choice-platforms.log` 使用公共参数但 JVM 堆为 4096 MiB，关闭 JS／Wasm IC；jsNodeTest、wasmJsNodeTest、testAndroidHostTest 各选择 `*CashEntry*RulesTest*`，各 3 类／24 方法，全部 0 failure／error／skip；XML 为 domain-js／domain-wasm／domain-android。compileTestKotlinIosSimulatorArm64、compileKotlinIosArm64 均通过，仅计编译，不以 Node 测试替代 iOS 运行。

独立 5a 及实际独立 5b 无 finding，含新增原仓储身份用例的增量复核；终态矩阵及文档另在提交前核对。Cross-platform Impact Check：三端共享纯字段选择受影响，native 仓储仍负责真实 SQL／事务；没有 UI、文案、路由或交互变化，不新增浏览器或原生设备 QA。D3、tasks、两级地图及 apply 指引同组更新；核对词表、README、配置／部署及示例，无新协议、依赖、迁移或术语，其余无需改写。没有新产品取舍或通用规范候选；可独立回退。完整动作授权、临时身份解析、成员退休及最终写集比较仍未接通，3.2 不勾选。

### 条目无时间协调输入验证及收尾证据

比较基线／验证 HEAD：`a7b72b70`，2026-11-06 02:07 UTC。内部 CashEntryProjectionGroup 只保留原 projectionId、根、成员和经济根；原 reconcile 仅取这四个字段再委托同一算法，不提前转换标识或改排序。无时刻的精确新草稿可计算条目结果，不构造假的发生／创建时间；本片仍不校验写集完整效果或授权。完整闭包核遗漏与原选中成员协调保持独立；成员退休顺序留待完整 checker 实测；预建 singleton 的原仓储身份效果已由本页公共选择专项验证，不代表新写集已重放该效果。

`entry-coordination-groups-red.log` 先因缺内部入口失败。`entry-coordination-groups-jvm.log` 执行完整领域 jvmTest（153 类／532 方法，0 failure／error／skip）、ktlintCheck、detektMetadataCommonMain、detektJvmTest 及 backend compileTestKotlin，全部通过。新增两方法覆盖无时间草稿的大整数标识、公开及独立人工值、同对象登记和经济根优先于条目 ID。`entry-coordination-groups-platforms.log` 使用下方公共参数但 JVM 堆为 4096 MiB，关闭 JS／Wasm IC；jsNodeTest、wasmJsNodeTest、testAndroidHostTest 各选择 `*CashEntryReconciliationRulesTest*`，各 1 类／12 方法，全部 0 failure／error／skip；compileTestKotlinIosSimulatorArm64、compileKotlinIosArm64 通过，仅计编译。

`entry-coordination-groups-sqlite.log`／`entry-coordination-groups-postgres.log` 用公共参数及 `:backend:test --rerun` 分别运行 CashEntryRepositoryMigrationTest、LedgerEntryVersionContractTest、CashImportEntryChoiceTest、MutationPlanEntryTargetGoldenTest，各 4 类／21 方法，全部 0 failure／error／skip。合成环境分别为 `<缓存>/sqlite` 和 `<缓存>/postgres-5432` 的 FT_KOTLIN_CONTRACT_DIR／FT_KOTLIN_LEDGER_CONTRACT_DIR；PostgreSQL 显式用户 huangwenlong 和既有专用 ft_offline_ledger_1104_test，不自动建库／启动实例。该矩阵验证现有仓储和版本边界，不代表新写集服务已接通。原样 XML 保存于 `<缓存>/entry-coordination-groups-xml/` 的 domain-jvm／domain-js／domain-wasm／domain-android 及 backend-sqlite／backend-postgres。

独立 5a 和实际独立 5b 无 finding，核对信息抽取、原标识转换／异常次序、排序、选中成员和语义边界；终态证据提交前另核。Cross-platform Impact Check：三端共享条目协调受影响，原 backend 入口同一委托；无 UI、交互或文案变化，不触发浏览器及设备 QA。D3、tasks、两级地图及 apply 指引同组更新；核对词表、README、配置及示例，没有新术语、公开协议、依赖或部署变动，其余无需改写。此片只实现既定边界，无新增通用产品规则；无迁移，可单独回退。strict 与 diff 检查通过，3.2 继续未完成。

### 写集正式状态方向验证及收尾证据

比较基线／验证 HEAD：`162435bc`，2026-11-05（UTC）。原导入 OperationRules 只在非正式分支按完整性决定 draft／posted，当前维护的草稿保存／转正式亦先验旧草稿；固定字段预检补齐相同方向，既有正式流水不能退回草稿。守卫放原固定 policy 之后，保留旧金融冲突优先；不限制合法草稿保存或同身份转正式，也不把该通过当完整入账资格。

`cash-write-state-direction-red.log` 真实复现只改状态仍放行，原失败 XML 见 `<缓存>/cash-write-state-direction-xml/red/`；补守卫后 `cash-write-state-direction-jvm-green.log` 完整领域 JVM 153 类／530 方法，0 failure／error／skip，XML 原样保存于 domain-jvm。新增 2 方法覆盖四种已有创建方式、纯状态降级错误路径、草稿保存与转正式及输入不变；既有伪造草稿并改金额的回归继续验证 fixed_field_conflict 优先。`cash-write-state-direction-platforms.log` 按公共参数关闭 JS／Wasm IC，执行三运行目标的全部 CashWriteSet*Test* 各 4 类／40 方法，全部 0 failure／error／skip；XML 保存在 domain-js／domain-wasm／domain-android。iOS Simulator 测试编译及 Arm64 主代码编译通过，仅计编译；领域 ktlint、detektMetadataCommonMain／detektJvmTest 和 backend 测试编译均通过，未调整检查规则。

独立 5a 及实际独立 5b 无本片 finding，核对原状态方向、异常顺序、引用与问题／账户资格边界及测试；评审者未构建；终态证据及文档范围已提供最终复核。Cross-platform Impact Check：三端共用的纯写集前置校验受影响，backend 既有导入／维护状态逻辑未修改；无 UI、文案、路由或交互变化，不新增浏览器／设备 QA。本片没有真实写集事务消费者，不另跑双库或宣称服务已接通，3.2 仍未完成。

D3、导入 delta、tasks、两级地图与 apply 指引同组更新；核对词表、README／配置／部署示例，无新增术语、依赖、公开端点或运行配置。此片保留既有状态方向，不新增通用产品规则；无数据迁移，可独立回退。完整状态资格、动作效果及真实事务仍须继续实现。

### 拆分意图表达验证及收尾证据

比较基线／验证 HEAD：`4c74a586`，2026-11-05（UTC）。独立契约预复核确认最终空 split_inherit 丢失本次保留／恢复决定，edited_fields 不能表达旧条目拆分范围。先在 D3／导入 delta／任务原位补齐既有行为，再新增 entry_split_choices：旧条目带修订引用及必填布尔，与最终条目及删除列表分开。引用预检只允许真实已有条目及原修订，拒绝新建目标、重复决定和修订别名；旧条目可在同批删除，不以最终对象是否存活丢弃依赖。无实际差异不新增确认，完整范围及必要组统一选择仍须服务验证。

`entry-split-choice-red.log` 先因缺模型失败；`entry-split-choice-references-red.log` 真实复现默认 Boolean 解码接受字符串 true，原失败 XML 保存于 contracts-boolean-red；仅该字段的解码器改为严格检查 JSON 布尔，未改变全局 JSON 或其他布尔字段。`entry-split-choice-reference-assertions-red.log` 再以 2 项真实断言证明旧引用预检漏检选择，XML 保存在 references-red；守卫补齐后 `entry-split-choice-jvm-green.log` 的两模块完整 JVM 转绿：contracts 7 类／20 方法、domain 153 类／528 方法，全部 0 failure／error／skip。原样 XML 见 `<缓存>/entry-split-choice-xml/contracts-jvm/` 与 domain-jvm。

`entry-split-choice-static-backend.log` 正常执行两模块 ktlint、detektMetadataCommonMain／detektJvmTest 及 backend 测试编译，全部成功；contracts 的 detektJvmTest 为 NO-SOURCE，不冒称实际分析。`entry-split-choice-js.log` 的 contracts 全量与 domain CashWriteSet*Test* 已通过，分别 7 类／20 方法及 4 类／38 方法，全部无失败或跳过，XML 保存于 contracts-js／domain-js。`entry-split-choice-platforms.log` 完整成功：contracts 的 Wasm／Android 各 7 类／20 方法，domain 的 Wasm／Android 各 4 类／38 方法，全 0 failure／error／skip。XML 保存于 contracts-wasm／contracts-android／domain-wasm／domain-android；两模块 iOS Simulator 测试编译及 Arm64 主代码编译通过，仅计编译。

独立 5a 和实际独立 5b 无本片 finding；评审范围含显式 false 保留、缺字段拒绝及限定字段解码、旧对象删除依赖、引用类型与真实修订、同条目统一选择及最终标记分离。评审者未构建；终态 XML／日志及文档已独立复核，无本片残余 finding。本片只有 DTO 与纯引用守卫，没有 HTTP／DB 消费方，不另跑真实双库或宣称完整拆分／提交通过，3.2 仍未完成。Cross-platform Impact Check：三端共享传输及预检模型受影响，无 UI、文案、路由或交互变化，不新增浏览器／设备 QA。

D3、导入 delta、tasks、两级地图和 apply 指引同组更新；proposal 的目标／非目标与完整意图合同保持一致，无新增能力范围。核对词表、README／配置／部署示例，无新术语、依赖、公开端点或运行配置。该片仅补足已确认拆分行为的表达，不将内部实现升级为通用用户偏好；无需新增通用规范，无数据迁移，可独立回退。

### 公共字段编辑共享验证及收尾证据

比较基线／验证 HEAD：`d73ed2eb`，2026-11-05（UTC）。原 CashEntryRepository.patch 的字段归一、修订先验、人工标记合并、单成员独立值更新共享为 CashEntryEditRules；checked Long 推进与协调器共用。字段值及人工标记仍在修订溢出检查前按原顺序更新，成员读取仍在实际变化及修订推进后发生；失败时由外层事务／savepoint 恢复，核心不伪装内存回滚。已授权的同值与明确清空合同保持，不扩大长期保护规则；选择公共值的类别资格仍由原仓储检查。

`entry-edit-rules-red.log` 先因缺核心失败，`entry-edit-rules-jvm.log` 的完整领域 JVM 与 backend 测试编译转绿。JVM XML 原样保存在 `<缓存>/entry-edit-rules-xml/domain-jvm/`：153 类／526 方法，0 failure／error／skip。新增 6 方法覆盖明确清空、同值首次保护及重复无动作、多人组独立值保持、旧修订优先与字段白名单、全部归一后才修改、空补丁不读成员、溢出时点及成员读取程序故障的原样传播。`entry-edit-rules-sqlite-static.log`／`entry-edit-rules-postgres.log` 的同 4 类／21 方法在两库各 0 failure／error／skip，涵盖仓储 13、版本及 savepoint 5、原 golden 3；XML 在 backend-sqlite／backend-postgres。前者正常全量 backend ktlint／detektMain／detektTest 及领域 ktlint／detektMetadataCommonMain／detektJvmTest 全成功，无规则调整或限定源文件。`entry-edit-rules-js.log`／`entry-edit-rules-platforms-final.log` 按同一过滤范围执行编辑、协调、公共字段继承和完整快照桥：JS／Wasm／Android host 各 4 类／25 方法，0 failure／error／skip；XML 保存在 domain-js／domain-wasm／domain-android。iOS Simulator 测试编译及 Arm64 主代码编译通过，仅计编译。

独立 5a 及实际独立 5b 无本片 finding，涵盖原 patch 求值／异常、字段归一、人工标记、组与单成员差异、导入复用及外层 flush；评审者未运行构建；本片运行证据与文档范围已提供最终复核。Cross-platform Impact Check：backend 与三端共享纯规则受影响，无 UI、文案、路由或交互变化，不新增浏览器／设备 QA。完整写集的身份、权限、动作资格及最终效果仍待接线，不勾选 3.2。

同组核对 D3、tasks、两级地图、词表、README／部署示例与 apply 指引；无新术语、依赖、配置、公开端点或用户能力，保留真实未完成边界。此片迁移已有规则，不新增通用产品偏好；无数据迁移，可独立回退。整个 change 的运行回归与最终收尾仍未完成。

### 稳定收支对象协调共享验证及收尾证据

比较基线／验证 HEAD：`f5c7ecd0`，2026-11-05 20:43 UTC。CashEntry／CashEntryMember 仅参数化流水标识，原生及已存快照消费者保持 Long；共享 CashEntryReconciliationRules 保留原协调规则，端侧可保留精确虚拟整数。原仓储继续 SQL、即时成员登记、延迟条目插入、两轮 flush 与空对象清理；核心按原合同原地修改选中成员及条目，期望结果校验须显式传脱离真实缓存的副本。结构与公共值可分别推进修订，保留溢出错误及拆分三态，不重定义人工清空或保护规则。

`entry-reconciliation-red.log` 先因缺共享模型及核心失败，`entry-reconciliation-first-green.log` 的首批 3 项回归及后端测试编译转绿。`entry-reconciliation-jvm.log` 完整领域 JVM：152 类／520 方法，0 failure／error／skip；原样 XML 见 `<缓存>/entry-reconciliation-xml/domain-jvm/`。新增 10 方法覆盖明确空值、对象身份与独立人工值、共同字段冲突、三态拆分、选中成员范围、两次修订、两处溢出时点、后续失败前登记、UTF-16 条目排序与数字锚点、超过 Long／JS 数值范围的虚拟标识。`entry-reconciliation-sqlite-final.log`／`entry-reconciliation-postgres-final.log` 使用同一 5 类共 33 方法（仓储 13、版本 5、导入编辑 12、golden 3），两库各 0 failure／error／skip；XML 保存在 backend-sqlite／backend-postgres，覆盖实际 SQL、回滚、保存点恢复及 query budget。`entry-reconciliation-static.log` 正常全量 backend ktlint／detektMain／detektTest 及领域 ktlint／detektMetadataCommonMain／detektJvmTest 全通过，未限定源文件或关闭规则。

跨端第一轮 `entry-reconciliation-platforms.log` 在 JS 通过后因 Wasm 编译磁盘不足失败，不计整轮通过。仅清理本 worktree 受 Git 忽略且可重建的输出和执行历史后，`entry-reconciliation-wasm-recovery.log` 单独完整运行成功。JS／Wasm 各 3 类 19 方法，0 failure／error／skip，分别保存 domain-js／domain-wasm XML；`entry-reconciliation-native-final.log` 的 Android host 同 3 类／19 方法、0 failure／error／skip，XML 见 domain-android；iOS Simulator 测试编译及 Arm64 主代码编译通过，仅计编译。跨端选择同时覆盖协调器、既有公共字段继承及完整快照预览桥。

首轮 `entry-reconciliation-sqlite-static.log` 的仓储 13 项、账本版本 5 项及两类 golden 3 项通过；复用上次已消费的导入编辑 fixture 导致 6 项旧收据／已提交数据断言失败，不能计整轮通过。XML 单独保留在 backend-dirty-fixture，不改测试预期；以原完整生成器新建两库各 14 份独立合成 fixture，见 `entry-reconciliation-fixtures.log`，后续运行使用新目录，不覆盖旧库或真实数据。

独立 5a 和实际独立 5b 的范围评审无本片 finding，涵盖原生桥、对象身份、求值／异常、选中成员、三态、修订及预览消费者；评审者未运行构建；双库、JVM 和正常静态终态证据已独立核实，平台结果和最终文档范围亦已核对，无遗留本片 finding。Cross-platform Impact Check：backend 和三端共享领域模型受影响；无 UI、文案、路由或交互修改，不新增页面冒烟，原生设备 QA 不在本片范围。完整写集的编辑意图、动作资格、人工标记效果与事务仍未接线，不勾选 3.2。

同组核对 design、tasks、两级地图、词表、README／部署示例与 apply 指引；仅更新共享能力现状和调用约束，无新术语、依赖、配置或可用端点。此片恢复既有规则的共享执行，不新增通用产品规范；独立回退不需数据迁移。整个 change 的完整回归、页面接线及最终收尾仍未完成。

### 后端测试静态门禁验证及收尾证据

比较基线／验证 HEAD：`89eacf4d`，2026-11-05 20:18 UTC。先有完整 detektTest 的 10 项失败证据，范围回写后只修正测试声明：6 处固定时钟增加 Instant 类型，3 处 catch 使用原 CashPolicyError 别名指向的完整类名，1 处必需 fixture 非空断言改用 assertNotNull。所有异常分支、原输入、预期和生产实现均保持，没有添加或放宽检查规则。

`backend-test-static-full.log` 使用公共 Gradle 参数（Xmx4096M）和仅调整 ktlint worker 堆的既有外部 init，正常执行 `:backend:compileTestKotlin :backend:ktlintCheck :backend:detektMain :backend:detektTest`，完整范围成功，未使用限定源文件的 init。`backend-test-static-before-bytecode.json` 与 `backend-test-static-bytecode-comparison.json` 记录 3 类 catch 测试及其内部类共 13 个 classfile SHA-256 完全相同；确认类名表达变化不改变运行产物。固定时钟文本、后续赋值和 fixture 断言对象保持原值。该片只有测试静态表达调整，没有新增业务测试，不将编译或 SHA 比对冒充完整数据库／HTTP 运行回归。

独立 5a 工程／范围复核无 finding；独立执行防劣化范围判断，纯测试及证据维护不触发契约或 UI 专项。Cross-platform Impact Check：只影响 backend 测试编译与检查，生产共享层、Web、Native、持久化和用户流程均未修改，无需重复原三端或浏览器 QA。核对 tasks、既有地图、README、词表和 apply 指引，不存在新能力、配置、术语或用户规则需要改写；无需新增通用规范。该片可独立回退，无数据迁移，最终全后端运行回归及整个 change 继续未完成。

### 当前来源事项准备共享验证及收尾证据

比较基线／验证 HEAD：`96f7a157`，2026-11-05 20:12 UTC。既有来源事项模型与 pending／组成项／prepare 决策下沉 `CashSourceIssueResolutionRules`；native 仅保留原 JSON、摘要、复制与明确问题变化异常桥，原循环顺序、正文 presence、固定冲突先于显式冲突裁决及受保护字段候选值均保持。新增 `prepareLocalCashSourceIssueResolutions`／`pendingLocalCashSourceIssuePatch` 使用既有精确 JSON 值桥，原始数字词法不经 Double 窄化整数。返回 patch 不是已授权或已过滤保护字段的落库结果，不能直接写入。

`source-issue-rules-red.log` 先因缺共享核心失败；`source-issue-local-entry-red.log` 再因缺端侧生产入口失败，随后转绿。`source-issue-rules-jvm-final.log` 使用公共 Gradle 参数（Xmx4096M），执行完整 `:business-domain:jvmTest` 和 backend 两类来源事项测试，成功；原始 `python-cash-source-resolutions.json` 的全部 708 案例按 native／local 两路对同一完整 expected 回放，未修改原 fixture。`source-issue-rules-platforms.log` 加外部 lint init、关闭 JS／Wasm IC，执行 JS Node／Wasm Node／Android host 各 `--tests '*CashSourceIssueResolutionRulesTest*'`、iOS Simulator 测试编译、iOS Arm64 主代码编译、ktlint 及三个 detekt 任务，全部成功；JvmMain 为 NO-SOURCE。XML 原样保存在 `<缓存>/source-issue-rules-xml/`：domain JVM 151 类／510 方法，另三运行目标各 1 类／11 方法，全部 0 fail／error／skip，iOS 只编译。11 项回归覆盖正文缺省／明确清空、私有元数据、固定及结构冲突、组成项归属／ordinal、多来源显式裁决、问题修订／动作资格、精确 Long、原 case701 冻结摘要及数字容器／Unicode 键、程序故障传播和输入复制。

backend 全量 ktlint 和 detektMain 已通过；相称清理了 4 个既有未用 import，PortfolioTimezone.local 只补显式返回类型，未修改投资范围或时区规则。`source-issue-rules-backend-global-check.log` 设置既有 `FT_KOTLIN_PORTFOLIO_TIMEZONE_CONTRACT` 到迁移期 82,347 条用例的 oracle，backend 三类 13 方法全部通过；首次漏设该变量仅导致时区原始 fixture 测试失败，已完整补跑。该次命令因全量 detektTest 的 10 项既有 finding 失败，不宣称该命令整体成功；缺口清理及最新正常全量结果见本页测试静态门禁证据。外部 `source-issue-rules-test-static.init.gradle` 在 projectsEvaluated 后限定 4 个相关测试源文件，`source-issue-rules-backend-scoped-static.log` 实际断言并打印源文件清单后通过；最初 configureEach 被插件覆盖的运行仍按全量失败记录，未把配置意图当证据。没有关闭或更改任何检查规则。

独立 5a 与实际独立 5b 无本片 finding，原生／端侧桥、完整原案例、冻结摘要、机械静态修正与检查范围均已复核。事务服务仍须验证真实问题归属、动作与最终增量的一致性、人工标记推导及 merge 身份转移，不勾选 3.2。Cross-platform Impact Check：三端共用规则与端侧 JSON 入口，backend 调用相同核心；无 UI、文案或交互修改，不新增页面冒烟；未改 SQL、DDL 或事务调用点，真实双库留完整服务接线后验证。本片不是完整后端回归。核对 design、tasks、两级地图、词表及 apply 指引，无新依赖、配置或可用端点，README／部署示例无需扩写。迁移既有合同，无新增产品政策或通用规范；可独立回退，无数据迁移。

### 写集编辑意图与来源动作验证及收尾证据

比较基线／验证 HEAD：`5b923cba`，2026-11-05 19:53 UTC。D3／导入 delta 先补足既有人工编辑及来源事项合同的表达缺口，再实施 `edited_fields` 和四种 `issue_resolutions`；最终人工标记保留为期望结果，不直接授权保存。问题与合并目标必须是各自实体范围内的已有对象并带真实修订，动作可与同一问题的最终退役同时出现，但同一问题不能重复决定。问题正文、动作资格和效果仍由后续真实事务读取与校验。

`cash-write-intent-contract-red.log` 因缺少字段／动作类型先失败，`cash-write-intent-contract-green.log` 完整 contracts JVM 转绿；`cash-write-intent-references-red.log` 两项真实断言复现未校验动作依赖，补守卫后 `cash-write-intent-domain-green.log` 完整 domain JVM 转绿。使用公共 Gradle 参数（Xmx4096M）；`cash-write-intent-platforms.log` 加既有外部 lint init、关闭 JS／Wasm IC，执行 contracts 全量 JS Node／Wasm Node／Android host 测试，domain 三目标各用 `--tests '*CashWriteSet*Test*'`，两模块 iOS Simulator 测试编译、iOS Arm64 主代码编译、ktlint 和 detektMetadataCommonMain／detektJvmMain／detektJvmTest，全部成功；JvmMain 及 contracts JvmTest detekt 为 NO-SOURCE。XML 原样保存在 `<缓存>/cash-write-intent-xml/`：contracts 四个运行目标各 7 类／18 方法；domain JVM 150 类／499 方法，另三目标各 4 类／36 方法，全部 0 fail／error／skip。iOS 只编译。

独立 5a 及实际独立 5b 契约审查无 finding，范围涵盖既有来源问题合同、传输表达和引用前置守卫。未覆盖编辑白名单、人工标记推导、来源动作资格／所有权及其与最终增量的一致性；不把纯引用 fixture 当合法完整命令，不关闭 3.2。Cross-platform Impact Check：三端及服务共用 DTO 与纯预检，无 UI／文案／交互变化；没有 HTTP／数据库消费方，不另跑真实双库或页面冒烟，后续实际服务接线另验。D3、tasks、两级地图及 apply 指引已按边界收尾，无新依赖、配置或可用端点，README／部署示例无需扩写。保留既有用户语义，不新增通用规范；本片可独立回退，无数据迁移。

### 写集固定字段验证与收尾证据

比较基线／验证 HEAD：`27389088`，2026-11-05 19:37 UTC。D3 先限定按可信旧值核对固定字段；`cash-write-set-fixed-red.log` 先因缺少入口失败，随后七项行为回归转绿。预检只构造既有记录策略的身份／金融／类型比较输入，没有更改持久模型、手工保护政策或写入行为；实际旧 state/origin 决定可编辑范围，不能用本批 draft/manual 声明解冻。新建导入保持原 writer 的 import／无手工转账组默认；新组件同文本引用不冒充旧 ID。两侧按 ordinal 排序比较，消除快照按 ID 与业务顺序不同造成的误判，原列表保持不变。

`cash-write-set-fixed-final-all.log` 使用公共参数（Xmx4096M）、既有外部 lint init 和关闭 JS／Wasm IC，执行完整 `:business-domain:jvmTest`，JS Node／Wasm Node／Android host 各用 `--tests '*CashWriteSet*Test*'`，随后 iOS Simulator 测试编译、iOS Arm64 主代码编译、ktlint、detektMetadataCommonMain／detektJvmMain／detektJvmTest，全部成功；JvmMain 为 NO-SOURCE。XML 原样复制到 `<缓存>/cash-write-set-fixed-xml/`：JVM 150 类／497 方法，另外三目标各 4 类／34 方法，全部 0 fail／error／skip，iOS 只编译。

固定字段回归覆盖来源创建方式／身份伪装、正式导入与 legacy 的金额／币种／时间／账户／组成项／类型冻结、最终 draft 仍不能改原正式金额、金额与时刻等价文本、公共文本更新、草稿及手工可编辑金融值、组成项新旧同文本引用、按 ordinal 保序和错换拒绝。独立 5a 与实际独立 5b 无遗留 finding，终态日志和 XML 已复核。这里只接调用方提供的可信事实，无 HTTP／数据库消费者；手工意图／保护标记、生命周期、手工转账成对资格及完整提交另验，3.1／3.2 不勾选。

Cross-platform Impact Check：三端及服务共用纯比较，无页面、文案或交互变化；不以 Node 测试替代页面 QA，原生设备 QA 不适用。不另跑双库矩阵，本片不能证明真实鉴权或事务读取；后续服务必须在基线锁后实际重读。D3、tasks、两级地图和 apply 指引按边界收尾，README／部署／接口示例无新可用入口或配置需要更新；无新依赖、术语或通用规范。可独立回退，无数据迁移；完整改造继续未完成。

### 写集生效确认图验证与收尾证据

比较基线／验证 HEAD：`8c571543`，2026-11-05 19:24 UTC。D3 先限定最终生效确认图预检；`cash-write-set-graph-entry-red.log` 在测试声明修正后仅因缺少入口失败。先提供完整事实和最终值叠加，再以同一原图规则验证存量与本次确认边；没有服务端发现、SQL 或投影物化。独立 5a／实际独立 5b 发现 P2：旧零额默认逻辑被同时用于本批关系，可能把显式 0 当作全额。`cash-write-set-graph-zero-red.log` 真实断言失败后，将默认处理限于未修改的旧事实；新增／替换的零原样进入规则，非零端点拒绝而真实零退款允许。已重审关闭，无遗留 finding。common 中 JVM 专属 putIfAbsent 已换为等价的首项保留，不用 JVM 通过证明跨端可用。

`cash-write-set-graph-final-all.log` 使用公共 Gradle 参数（Xmx4096M）、既有外部 lint init、关闭 JS／Wasm IC，执行完整 `:business-domain:jvmTest`，JS Node／Wasm Node／Android host 各选择 `--tests '*CashWriteSet*Test*'`，以及 iOS Simulator 测试编译、iOS Arm64 主代码编译、ktlint 和三个 detekt 任务，成功。补齐来源绑定实际导入路径后，`cash-write-set-graph-source-final.log` 用同样参数重跑完整 JVM、上述三个运行目标、iOS Simulator 测试编译、ktlint 和 detektJvmTest，全成功；生产源码与前一日志相同。当前 XML 在 `<缓存>/cash-write-set-graph-xml/`：JVM 149 类／490 方法，另三目标各 3 类／27 方法，全部 0 fail／error／skip；iOS 只编译。静态源码、日志与原始 XML 经独立复核。

九项图回归覆盖未改确认边、组成项替换、父项删除／转草稿、已有退款累计及额度边界、存量转账占用与明确释放、错误角色／重复边、有效资金占用及换组件绕过、存量默认零与本批显式零分离、完整 Long／已有和新建同文本引用、原事实不变，以及本批来源绑定参与稳定键。缺少 import 来源键会拒绝，有最终绑定则通过，但这不验证绑定证据本身。草稿／失效关系及候选状态、锚点、联合决定和来源资格仍待后续服务验证，3.1／3.2 不勾选。

`cash-write-set-graph-performance-linked.log` 用仓库外 `write-graph-performance.init.gradle` 加入合成 `CashWriteGraphPerformanceProbe`，仅运行 JVM 该类；1 方法通过，XML 与合成源码保留于同一缓存目录。100 笔预热后，1 万／5 万笔全量事实（各同数完整组成项）在无边时为 148／293 ms，含 5 千／2.5 万条独立镜像确认边时为 245／611 ms。每次核对完整对象数量，未裁剪图规则。该观测是本机同进程、空增量下整个事实图预检的单次耗时，不代表网络、实际导入事务、峰值内存、移动设备、SLO 或 PowerSync 性能对比；服务接线后仍需实际事务性能验证。性能任务不替代前述复制保存的完整回归 XML。

Cross-platform Impact Check：三端与服务可共享该纯入口，无页面、文案或交互修改，浏览器业务冒烟与原生设备 QA 不适用。本片没有实际持久化消费者，不以合成事实证明数据库隔离、事务或双库行为。核对 D3、tasks、两级功能地图与 apply 指引，未新增依赖、部署配置、术语或可用端点，README 与操作示例无需扩写；不新增通用规范。完整写集合法性、原子写入、缓存与页面仍未完成。本片可独立回退，无数据迁移或外部发布。

### 写集金融值归一验证与收尾证据

比较基线／验证 HEAD：`89e79b77`，2026-11-05 19:08 UTC。D3 先限定金融输入归一边界，返回同一 DTO 的归一结果，不另建含糊的“已验证命令”。`cash-write-set-financial-red.log` 先复现缺少入口；`cash-write-set-financial-incomplete-red.log` 再以真实断言复现正式缺类型被提前报作 invalid_record_type，修正为正式分支直接复用 validateForPosting，保留 incomplete_record 及字段位置，草稿／未知状态继续原状态和部分类型规则。

`cash-write-set-financial-final-all.log` 使用公共 Gradle 参数（Xmx4096M）、既有仓库外 lint init 及 `-Pkotlin.incremental.js.ir=false -Pkotlin.incremental.wasm=false`，执行 `:business-domain:jvmTest`；JS Node、Wasm Node 及 Android host 各 `--tests '*CashWriteSetFinancialValuesTest*'`；`:business-domain:compileTestKotlinIosSimulatorArm64 :business-domain:compileKotlinIosArm64 :business-domain:ktlintCheck :business-domain:detektMetadataCommonMain :business-domain:detektJvmMain :business-domain:detektJvmTest`，全部成功。JVM 实际 148 类／481 方法，另三运行目标各 1 类／8 方法，0 fail／error／skip；XML 原样复制到 `<缓存>/cash-write-set-financial-xml/`。iOS 只编译，不宣称运行或设备 QA。detektJvmMain 为 NO-SOURCE，不虚称其执行了源码分析。

回归覆盖 20／18 位金额、Unicode／下划线兼容、负零／尾零、极端指数拒绝、零的极大正指数、转换后 UTC 两端及最后微秒、微秒截断、草稿缺值与已知矛盾、正式完整性、组成项金额／方向／币种／账户／ordinal、精确汇总、关系金额精度、原文 JSON 保真和失败不修改输入。关系类型、角色、符号和额度未由本函数判断，非法关系不能凭金额通过而接受；引用、账户资格、固定／保护字段、状态迁移和完整图仍须后续服务校验。没有 HTTP／数据库消费方，不另跑真实双库或页面冒烟，3.1／3.2 继续未完成。

独立 5a 及实际独立 5b 契约审查无遗留 finding，范围不触发 UI 专项。Cross-platform Impact Check：三端及服务可共用纯归一，无页面、文案或交互变化。核对 D3、tasks、两级功能地图与 apply 指引，修正地图中失效的“客户端提交前时间校验未接入”；未新增术语、依赖、配置或操作入口，README／部署示例无需改写。用户已确认的现金 UTC 范围及精确金额规则仍限本业务，不增加通用规范。本片可独立回退，无数据迁移或外部发布；完整提交、缓存和页面仍未完成。

### 最终写集传输模型验证与收尾证据

比较基线／验证 HEAD：`588e6cbe`，2026-11-05。D3 先确定只传最终增量、显式已有／新建引用及服务端审计边界；`cash-write-set-contract-red.log` 先复现线路类型缺失，随后实现 `CashWriteSetRequestDto` 及全部实体族。版本化引用只有一个模型，分别用于写目标和读取依赖；组成项、候选和条目成员由父对象包含，避免额外重复父 ID。空页复用没有审计字段的现有证明 DTO；不开放账户、分类或投资事实写入。

`cash-write-set-contract-jvm.log` 执行 `:business-contracts:jvmTest`；`cash-write-set-contract-platforms.log` 使用公共 Gradle 参数（Xmx4096M）执行 `:business-contracts:jsNodeTest :business-contracts:wasmJsNodeTest :business-contracts:testAndroidHostTest :business-contracts:compileTestKotlinIosSimulatorArm64 :business-contracts:compileKotlinIosArm64 :business-contracts:ktlintCheck :business-contracts:detektMetadataCommonMain :business-contracts:detektJvmMain :business-contracts:detektJvmTest`，全部成功。JVM、Android host、JS Node 和 Wasm Node 各 7 类／15 方法，0 fail／error／skip，原 XML 保存在 `<缓存>/cash-write-set-contract-xml/`。覆盖完整实体族线路往返、超过 JS 安全整数的引用、完整 Long 修订、20／18 金额文本、微秒时刻、原始 JSON 数字词法、已有／新建同文本不混同、数值版本与审计注入拒绝；这些仅证明线路保真，不把组合 fixture 当合法业务命令。iOS 只编译，不声称运行或设备 QA。

本片无 HTTP、数据库写入或页面变化；真实双库和浏览器业务流程不适用，待消费方接入后另验。Cross-platform Impact Check：三端与 Kotlin 后端共用同一 contracts 类型，无 UI／交互变化，保留原页面行为。独立 5a 及实际独立 5b 契约审查均无残余 finding，评审者复核了当前模型、原写入面及四目标 XML；本片不触发 UI 复用专项。1.2 仅按传输 DTO 收敛完成，3.x、6.6 仍未完成；DTO 的 nullable 修订、状态及角色需要未来服务按实际快照校验，不据能反序列化而接受。未新增依赖、配置或部署要求，README／接口操作示例不把未接入的类型写成可用端点；功能地图按此限制更新。用户既有最终增量决定保留 D3，无需新增通用规范；本片可独立回退，无数据库迁移。

### 写集引用预检验证与收尾证据

比较基线／验证 HEAD：`ea37fd6a`。D3 先限定纯引用预检，不能替代真实鉴权、基线守卫、同事实幂等解析或完整合法性校验。`cash-write-set-references-red.log` 先复现缺少入口，初始五项回归转绿；独立 5a／实际独立 5b 发现两项 P2：自然键只对请求内去重、未修改条目仍拥有的成员被新条目声明。`cash-write-set-references-occupied-red.log` 以存量来源键真实断言失败后，改成存量扣除显式替换／解决／删除再加入最终值；合法同目标更新、解决后重建和成员迁移均保留。十项测试覆盖全部目标／依赖修订、实体类型、父组件归属、已移除部分、重复声明／自然键／成员、完整 Long 与别名拒绝，以及可信事实损坏按内部错误停止。组成项 ordinal 保持原 `Int.MAX_VALUE` 范围，不因数据库列为 Long 扩大业务域；未改存量关系／候选的完整闭包仍待后续服务校验。

`cash-write-set-references-full-jvm.log` 使用公共 Gradle 参数（Xmx4096M）及仓库外 `<缓存>/cash-write-set-lint.init.gradle` 执行 `:business-domain:jvmTest :business-domain:ktlintCheck :business-domain:detektMetadataCommonMain :business-domain:detektJvmMain :business-domain:detektJvmTest`，全部成功；完整 JVM 实际 147 类／473 方法，0 fail／error／skip。外部 init 只将既有 ktlint worker 的堆改为 1024m，未修改仓库依赖或禁用规则；原 worker 堆失败不计通过。静态规则另发现本 change 旧 `RelationSet` 包装迭代器的委托无法被识别，显式增加耗尽检查并保留包装，空集、耗尽异常及不能经可变迭代器删除的原行为已验证；不能称旧委托运行时不抛异常，也不改关系语义。

`cash-write-set-references-platforms.log` 在 JS Node 执行引用及关系政策两类测试，实际 32 方法全部通过；后续 Wasm 编译因磁盘耗尽失败，整个命令不能算通过。仅在任务终止、复制原 XML 后，清理本 worktree 已核对 Git 忽略的 JS／Wasm 编译缓存及 JS 同步输出；未动全局缓存、凭据、真实数据或其它 daemon。`cash-write-set-references-platforms-noic.log` 使用公共参数及 `-Pkotlin.incremental.wasm=false` 执行 `:business-domain:wasmJsNodeTest --tests '*CashWriteSetReferencesTest*' --tests '*CashRelationPolicyTest*' :business-domain:testAndroidHostTest --tests '*CashWriteSetReferencesTest*' --tests '*CashRelationPolicyTest*' :business-domain:compileTestKotlinIosSimulatorArm64 :business-domain:compileKotlinIosArm64`，成功。Wasm Node 与 Android host 各 2 类／32 方法，0 fail／error／skip；四目标 XML 在 `<缓存>/cash-write-set-references-xml/`，iOS 只编译，不声称运行或设备 QA。

最终 `cash-write-set-references-final-all.log` 以同一公共参数、外部 lint init 及 `-Pkotlin.incremental.js.ir=false -Pkotlin.incremental.wasm=false` 执行完整 `:business-domain:jvmTest`、JS／Wasm Node 与 Android host 的上述两类测试、`:business-domain:compileTestKotlinIosSimulatorArm64 :business-domain:ktlintCheck :business-domain:detektJvmTest`，全部成功。当前 XML 重新核对为 JVM 147／473，其余三目标各 2／32，全部 0 fail／error／skip；原 iOS Arm64 生产源码未再变化。审查 P3 已关闭：删除回归在同一迭代器先 `next()` 后执行，并核对原集合不变，避免初始状态异常形成假阳性。

独立 5a 与实际独立 5b 已关闭两项引用 finding，迭代器补丁及最终日志／XML 亦已重审，无残余阻断。本片只在内存传入的事实范围预检，没有 HTTP／SQL 消费方，不能据合成事实证明真实跨工作区鉴权或双库写入。3.1／3.2 不勾选；金融字段、状态、保护字段、完整条目／关系图与原子落库仍未完成。Cross-platform Impact Check：三端和 Kotlin 服务可共享纯函数，无页面或交互变化；浏览器页面和原生设备 QA 不适用。未新增依赖、迁移或部署配置；后续同事实幂等解析须先于预检，不能把占用键拒绝当作相同关系幂等成功。功能地图、D3 和实施技能按此边界核对；未接端点，README、配置及操作示例无需扩写。用户的最终增量／服务端校验选择继续由业务规格表达，不将本片实现细节新增为通用规则。本片可独立回退，无数据库回滚。

### 现金表单 UTC 输入校验验证与收尾证据

Web 实际操作发现的 P2 已修复：在表单滚动后保存超限时刻，仍保留输入且不发送请求，并滚回原错误区域。`cash-input-ui/cash-range-error.png` 与手动滚回后的 `cash-range-error-top.png` 为原复现；`range-scroll-red-actual.log` 在真实 1899 年 12 月 31 日表单上复现错误位于视口上方。默认时间固定为中午，以 1900 年 1 月 1 日为日期的先前用例实际合法，属于测试设置错误，不作为失败回归。局部 ScrollState 与保存尝试只触发界面定位，不改变业务状态或调用次数。

2026-11-05 18:19 UTC，当前 `c4573b2a` 工作树以官方 `start-compose-demo.mjs` 新构建生产 Wasm，在 Chrome、`http://127.0.0.1:5189/w/workspace-1/`、1440×900 与 390×900 执行 `compose-cash-manual.e2e.ts` 的两条 UTC 回归，`range-scroll-final-browser.log` 为 2／2 通过。每个视口均真实输入金额、滚出错误区域、连续两次保存范围外日期、保留金额及日期且 0 写入，再经日期选择器改为合法日期并仅产生一次写入。控制台、失败请求及静态 HTTP 错误均为空。Compose 语义镜像的几何可在滚动后滞留，重复保存因此对真实截图区域做滚出前后变化及回位一致性比较，不用该 DOM viewport 断言冒充画面验证；内联日历同样可能缺失语义，恢复步骤使用固定代表视口中已实际截图核对的日期／完成位置，再断言真实写入结果。两宽度的 `range-error-after-save-{1,2}.png` 与 `range-correction-calendar.png` 保留在 `<缓存>/cash-input-ui/range-results/`。本片只验证既有表单反馈，不代表整体离线导入页面 QA。

比较基线／验证 HEAD：`c4573b2a`，2026-11-05。设计先明确现金专用输入适配，再用 `cash-input-range-red.log` 复现普通记账及草稿仍生成 1899 UTC 提交字段，用 `cash-input-transfer-range-red.log` 复现转账两腿编辑接受同一范围外时刻。`CashOccurrenceInput` 只对已成功转换的 UTC 时刻复用共享范围，返回原转换文本；通用转换、投资和系统时间不变。独立 5a 发现 P2：既有转账输入适配在调用方提供纯投资两端时，不能无条件应用现金限制；当前页面仍读取 cash 账户视图，不因此宣称页面可选择投资两端。`cash-input-investment-range-red.log` 先复现误拒，修复后纯 security／crypto 双向转账保留宽范围，含现金端的各方向仍拒绝；没有新增账户过滤或资格政策。

`cash-input-ui/range-scroll-final-native.log` 在最终表单实现上以公共 Gradle 参数（本次 Xmx4096M）执行 `:shared:testAndroidHostTest --tests '*Cash*InputTest*' --tests '*CashCreationViewModelTest*' :shared:compileTestKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64 :shared:compileKotlinJs`，成功。Android 实际 7 类／46 方法，0 fail／error／skip；XML 在 `<缓存>/cash-input-ui/range-scroll-final-xml/`。iOS 仅编译，不声称设备 QA。`cash-input-range-js-browser.log` 和 `cash-input-range-wasm-browser-noic.log` 分别在 Chrome Headless 执行 `:shared:jsBrowserTest`／`:shared:wasmJsBrowserTest --tests '*Cash*InputTest*'`，各 6 类／42 方法，全部通过。Wasm 本次使用 `-Pkotlin.incremental.wasm=false`，不修改仓库编译配置；前次磁盘耗尽不计通过。XML 保存在仓库外 `<缓存>/cash-input-range-final-xml/{android-host,js-browser,wasm-browser}/`。浏览器输入单测在文案及滚动修改前执行，输入 Kotlin 源码未变；显示文案及滚动由上述新生产页面验证，不能混同两种证据。后端现金时间门及投资例外的原双库证据保持，不以本片输入测试替代。

Cross-platform Impact Check：共享提交前校验与表单错误定位影响 Web、Android 和 iOS，原控件、布局、token 及错误码不变。现金专用日期错误三份中文资源及英文资源同时说明 UTC 1900 至 2100 年范围；XML 已逐份解析并核对唯一键，不改变投资界面或通用转换。独立 5a、实际独立 5b（契约／UI 复用）及最终截图设计／Hallmark 复核均无残余 finding；评审者实际读取两宽度各两次错误截图、日历截图、原始 XML 与通过日志，确认提示不遮挡、390 px 自然换行且输入保留。测试的物理坐标和截图区域相等只验证当前中文、受控时钟及 1440／390×900 布局，不推广到其他日期、语言、任意视口或读屏；原生设备 QA 未执行。无需新增领域词或通用规范，本用户现金范围决定只保留对应业务规格；未新增依赖、接口或部署配置。功能地图、设计和任务按实际接线核对，README、词表和部署说明无需变动；本小步可独立回退，不需要数据库迁移。

### 提交后完整事实版本下限验证与收尾证据

比较基线／验证 HEAD：`d7a924ed`，2026-11-05 17:20 UTC。`commit-snapshot-floor-red.log` 先复现刷新入口缺少收据下限；新增参数只用于比较，不把收据版本赋给本地缓存。`commit-snapshot-floor-green.log` 使用本页公共 Gradle 参数执行 `:shared:testAndroidHostTest --tests '*LedgerSnapshotSyncTest*' --tests '*LocalCashLedgerOwner*'`，实际 3 类／19 方法，0 fail／error／skip，XML 保存在仓库外 `<缓存>/commit-snapshot-floor-xml/android-host/`。覆盖超过 JS 安全整数的精确版本、旧查询仍强制完整下载、过旧快照失败保旧并可重试、真实金额换版及旧游标拒绝，并复跑原鉴权、关闭及真实双线程围栏。

独立 5a／实际独立 5b 无 finding；原在线操作恢复必须持续传入相同下限，直到完整事实到达，不把后续刷新失败当成写入失败或新建提交。Cross-platform Impact Check：三端复用同一内部同步入口；本片没有页面／文案变化，不冒充浏览器页面 QA。`commit-snapshot-floor-js-browser.log` 与 `commit-snapshot-floor-wasm-browser.log` 分别在 Chrome Headless 执行 `:shared:jsBrowserTest`／`:shared:wasmJsBrowserTest --tests '*Ledger*'`，各 16 类／66 方法，0 fail／error／skip；JS 使用 `-Pkotlin.incremental.js.ir=false`，Wasm 使用 `-Pkotlin.incremental.wasm=false`，单 worker、无 configuration/build cache。XML 在 `<缓存>/commit-snapshot-floor-xml/{js-browser,wasm-browser}/`。iOS 测试源码及 Arm64 framework 源码已随 `cash-input-range-final-native.log` 完整编译成功；不声称 iOS 运行或设备 QA。无服务存储或写入变化，不重复双库矩阵。已核对 design、任务、总／现金功能地图，README、词表、接口形状与部署配置无需更新；既有结果确定性合同的内部实现不新增通用规则。完整写集、收据核实、意图清理及加密应用接线仍未完成，不勾选整体 6.6。本小步可独立回退，不需要数据库回滚。

### 当前关系维护完整回归

比较基线／验证工作树 HEAD `ac933cb0`；2026-11-05（UTC）。原 export_current_cash_maintenance_contracts.py 在仓库外 current-plan-1105-sqlite／current-plan-1105-postgres 分别完整生成 61 个场景及独立合成库，COMPLETE.json 均为 61，不裁剪清单、原数据或期望。current-plan-1105-sqlite.log 首次完整维护两方法均因 relation_role_incompatible 失败，原四类 28 方法通过；current-plan-zero-refund-red.log 新双向零退款定向回归同样先红。

根因是 CurrentCashRelationPlanner.manualGroups 遗漏两端零额、起点 refund 且对端非 refund 时的退款排序；原 Python _ordered_endpoints 与原双向零退款案例已有此合同，bcf7f8ef 的 Kotlin 源码亦缺同一条件，并非共享核对组装新引入。修复仅在原退款／转账 kind 门内增加交换条件，不改角色或图规则，不提前解释镜像金额。对端金额缺失不认作零，继续交原端点校验；current-plan-zero-target-amount-red.log 先复现新非空断言引入 NPE，再以 null-safe 比较恢复具体 relation_endpoint_incomplete。该回归仅在原反向场景的写事务内置空目标组成项金额，断言同 sentinel 回滚及全库不变；成功定向回归比较原完整结果、端点方向和全库只读。

current-plan-zero-refund-final-sqlite.log／current-plan-zero-refund-final-postgres.log 使用本页公共 Gradle 参数，执行完整 CurrentCashRelationPlannerTest、CashImportBusinessPlanContractTest、CashImportPreviewSnapshotContractTest、CashImportEntryChoiceTest、LocalCashRelationContextParityTest，逐类 --tests '*类名*'；各 5 类／32 方法，0 failure／error／skip。维护首方法重放全部 61 个成功或预期拒绝场景，第二方法完整比较其中 53 个成功场景的关系组、依赖和失败项；8 个错误场景由首方法明确校验，不当作单元测试 skip。最终 XML 在仓库外 current-plan-zero-refund-xml/{sqlite,postgres}/backend/test。

SQLite 使用上述独立场景库和 relation-choice-1105-preparation；PG 使用专用 ft_offline_ledger_1104_test 的 ft_current_1105_00..60 隔离 schema，以及 candidate-query-1105-preparation-postgres。显式设置 FT_TEST_POSTGRES_URL／FT_KOTLIN_TEST_DB_USER，JDBC 逐例绑定 currentSchema；不探测／启动数据库、不改 public 或其他既有 schema。为准备合成库仅清理本 worktree 已忽略且可重建的 JS／Wasm 生成包，原样本与外部 XML 保留。

独立 5a／实际 5b 已完成条件、空金额错误边界、终态源码、XML 及文档复核，无遗留 finding；旧组装段落的失效待跑表述已改为专项引用，不混用矩阵。Cross-platform Impact Check：仅 backend 恢复既有维护预览，三端消费同一远端结果，shared／UI／线路字段未改；不新增本片平台编译或页面冒烟，不替代全 change 的最终前端 QA，原生设备 QA 默认不执行。已核对 design、tasks、两级地图、README、词表及 apply 指引；无新能力、文案、入口、依赖、配置或术语，其余现状文档无需改写。无新用户决策或通用规范；完整端侧发现、保存、写集和页面仍未完成。新写集前置安全复核独立细化 D3／3.2 的 DB 权威重读及事实／收据边界，未新增 wire，明确待实现。OpenSpec strict 与 diff 检查通过。

### 本地核对计划冻结编码验证与收尾证据

验证时间：2026-11-05 16:47 UTC；比较基线／验证 HEAD `deff907b`。local-plan-encoding-red.log 先复现冻结入口缺失。freezeLocalCashImportPreviewPlan 在最终边界将计算金额转为原固定十进制文本、时刻转 UTC 微秒文本，显式整数／宽整数保持 JSON 整数，来源 JSON 浮点沿原 native 格式规范化，不能声称保留原指数或尾零词法。summary、文件识别行数及诊断计数／行号按原 Int 合同编码，原文中的同名字段不被误判为元数据；非法计数、非有限数、未知对象和坏 UTF-16 停止编码，输入与前计划不变。固定金额文本与原关系上下文复用 localCashStoredDecimalText，未改来源摘要、比较或通用 JSON 编码。

独立 5a 发现 nullable JsonNull 和巨大零字面量边界，local-plan-encoding-boundary-red.log 分别真实复现 InvalidCashValue 和 -0E2147483649 未转 -0；修复后转绿。最初 zero-red 命名日志实际通过，-0E2147483648 的最终 scale 可容纳，不能记作失败证据。common 三方法覆盖负零／尾零／大指数零、微秒截断、系统时间不受现金日期门限制、宽整数、原始数字种类、冻结 JSON 再编码、nullable 元数据和失败不改输入；真实组装计划亦实际冻结。native 直接以原 fixedCashDecimal 对照九组普通／Unicode／巨大零／非法文本，原 15 个提案 case 加入真实 parsed files 并按 ParsedImportCodec.bytes 比较完整最终 JSON；原 retained_target 准备专用 case 的排除理由及全部原黄金断言保持，不冒充文件到匹配流程证明。

local-plan-encoding-final-matrix.log 使用本页公共 Gradle 参数，backend 选择完整 CashImportBusinessPlanContractTest、CashImportPreviewSnapshotContractTest、CashImportEntryChoiceTest、LocalCashRelationContextParityTest，SQLite 4 类／28 方法。领域 jvmTest、jsNodeTest、wasmJsNodeTest、testAndroidHostTest 各选择 LocalCashImportPlanEncodingTest、LocalCashImportPreviewPlanTest、CashImportPreviewPlanRulesTest、CashImportEntryChoiceRulesTest、LocalCashImportRelationDecisionsTest、LocalCashImportPreviewPresentationTest、LocalCashRelationContextTest，所有类名以 --tests '*类名*' 选择，各 7 类／22 方法，0 failure／error／skip。iOS Simulator 测试及 Arm64 主代码编译通过，仅计编译。local-plan-encoding-final-postgres.log 重跑上述完整四个 backend 类，4 类／28 方法，0 failure／error／skip。SQLite 使用 relation-choice-1105-preparation，PG 使用 candidate-query-1105-preparation-postgres，显式 URL／用户指向既有 ft_offline_ledger_1104_test，不探测／启动数据库。最终 XML 位于仓库外 local-plan-encoding-xml/final-sqlite-platforms 和 final-postgres；较早未提取共享 helper 的结果不作最终证据。

独立 5a 及实际 5b 契约审查已完成修复复核与终态源码、XML、文档检查，无遗留 finding；评审者未构建或修改实现。Cross-platform Impact Check：三端共享最终编码及关系上下文格式边界受影响；无页面、交互、路由、文案或持久化格式变更，本片不新增页面冒烟，Node 不代替页面 QA，原生设备 QA 默认不执行。核对 design、tasks、两级功能地图、Compose README、词表及 apply 指引；无新依赖、外部配置、命令或领域术语，README／词表／skill 无需修改。没有新增用户决策或通用规范；完整发现、计划保存、写集和页面仍未完成，1.3／6.4／6.6 保持未完成。OpenSpec strict 与 diff 检查通过。

### 已计算提案到核对结果组装验证与收尾证据

验证日期：2026-11-05（UTC）；比较基线／验证 HEAD `8ac47629`。local-preview-plan-red.log 先复现本地整装入口缺失。CashImportPreviewPlanRules 将原 backend 的分组、保存选择、拆分、图应用、失败父项排序／去重／首项保留、关注标记、呈现及依赖元数据单源共享。native 保留快照变换、发现、追加当前关系组和公共编辑的原顺序，以及编辑后附 operations／row_decisions 和最终 wire 的边界；不删除原扩展点或原黄金断言。本地 completeLocalCashImportPreviewPlan 使用同一完整快照、准备和独占预览，以明确提供的已计算提案完成组装，继而应用公共字段决定并附操作／行结果，不执行发现或保存。

独立 5a 的跨平台 finding 已先红再绿关闭：local-preview-plan-js-red.log 真实复现 common 两处 Map.putIfAbsent 未解析；删除默认按 key 值为 null 时写 false，失败集合按 key 不存在时保首项，保留显式 null 和原首项语义。最终四目标回归及 iOS 编译通过。独立 5a 及实际独立 5b 无遗留 finding；5b 按 codebase-degradation-review 调度 contract-design-review，确认七个阶段／值适配回调具有现有 native／local 两消费方与旧 SQL 拆分读取的隔离价值，无新持久模型、状态或协议，ui-reuse 不适用。

common 的 LocalCashImportPreviewPlanTest 两方法从实际本地准备、工厂和呈现出发，验证保存公共决定、依赖 token／候选集合、金额／时刻／精确虚拟标识以及前计划只读；另一方法让真实弱退款提案经过候选选择、图应用和最终呈现，已应用金额按 CashAmount representation 保精确小数位，公共备注作用展示代表而新来源成员保持原值。CashImportPreviewPlanRulesTest 三方法验证合成重复业务失败保首且只标记已有事实、追加当前组在选择前且显式 null 删除默认在恢复前，以及 group／additional／choice／restore／apply／digest／render／group-value 八回调的真实程序异常注入、原异常身份与后续停链；这些编排故障测试不冒充发现算法或 SQL 证明。

native 新整装方法对 15 个原提案 case，在同次实际准备／工厂基础上指定原提案，经 native builder 和 local 入口逐字段比较完整结果及图状态；再次准备的账户、候选、保留目标和上下文 token 均核相同。原第 16 个 retained_target 只有准备专用 edit_ref、没有最终公共字段 patch，因此未纳入新增最终 builder 对照；原全部 16 组分组黄金及准备断言仍保留。原完整 CashImportBusinessPlanContractTest 及 CashImportPreviewSnapshotContractTest 继续执行，未改固定期望，不把上述阶段输入当原文件到本地匹配的证明。

local-preview-plan-final-platforms.log 沿下方公共 Gradle 参数执行 backend 完整 CashImportBusinessPlanContractTest／CashImportPreviewSnapshotContractTest／CashImportEntryChoiceTest，SQLite 3 类／24 方法；领域 jvmTest／jsNodeTest／wasmJsNodeTest／testAndroidHostTest 各选择 LocalCashImportPreviewPlanTest、CashImportPreviewPlanRulesTest、CashImportEntryChoiceRulesTest、LocalCashImportRelationDecisionsTest、LocalCashImportPreviewPresentationTest，类名均带尾部 *，各 5 类／15 方法，0 failure／error／skip。iOS Simulator 测试及 Arm64 主代码编译通过，只计编译。local-preview-plan-final-postgres.log 重跑同三个完整 backend 类，3 类／24 方法全通过。SQLite 使用 relation-choice-1105-preparation；PG 使用 candidate-query-1105-preparation-postgres，显式 URL／用户指向既有 ft_offline_ledger_1104_test；不启动或探测数据库。最终 XML 在仓库外 local-preview-plan-xml/sqlite-platforms 及 postgres。上述组装矩阵本身不覆盖 CurrentCashRelationPlannerTest 的完整维护流程；追加组顺序与默认值另有 common 编排回归。原 61 个维护场景的双库完整回放及零退款修复，统一见本页“当前关系维护完整回归”，不将该结果混入上述组装矩阵。

Cross-platform Impact Check：三端共享核对组装与 native 原组装复用受影响，页面／文案／交互未改；Node 不替代页面冒烟，未追加原生设备 QA。核对 design、tasks、功能地图和导入地图、README、词表、apply 指引；无新依赖、配置、命令或用户概念，README／词表／skill 无需改写。无新增用户决策或通用规范需沉淀。完整本地发现（含商户文本兼容）、计划线路编码／保存、写集、本地加密库及页面均未完成，1.3／6.4／6.6 不勾选。OpenSpec strict、文档链接与 diff 检查提交前执行。

### 计算型公共字段决定验证与收尾证据

验证日期：2026-11-05（UTC）；比较基线／验证 HEAD `a50fea60`。local-computed-entry-choice-red.log 先复现计算型入口缺失。LocalCashImportEntryChoices 新重载直接接独占的 MutableMap 计划并委托原共享规则，JSON 入口仍先复制／解码后委托；不新增模型、状态或持久化。计算型入口沿原 native 语义原地修改本次工作结果，失败时由调用方丢弃该次结果，不承诺计划内回滚；用户选择和上次核对结果只读。

common 新回归从真实完整快照、预览工厂和呈现结果生成稳定目标，应用备注后检查 display／entry fields／entry_edits，原 CashAmount 与 Instant 对象保留，预览虚拟 ID 大于 2^64；previous 和 choices 摘要不变、预览事实备注未变。原完整黄金、失效依赖、分类缺失和禁用字段拒绝继续执行。native 原完整呈现方法遍历全部 presentation case，取实际可见条目应用相同备注决定，逐字段比较完整 native／local 结果，并保留 previous、choices、预览状态和全库只读断言；该新备注对照不单独证明分类分支，不修改原固定期望。

local-computed-entry-choice-final-platforms.log 沿下方公共 Gradle 参数执行 backend 完整 CashImportPreviewSnapshotContractTest／CashImportEntryChoiceTest，SQLite 2 类／19 方法；领域 jvmTest、wasmJsNodeTest、testAndroidHostTest 各选择 CashImportEntryChoiceRulesTest、LocalCashImportPreviewPresentationTest、LocalCashImportRelationDecisionsTest，均 3 类／10 方法、0 failure／error／skip。iOS Simulator 测试与 Arm64 主代码编译通过，只计编译。JS 第一轮类名无尾部通配符实际未选中用例，不能计通过；local-computed-entry-choice-final-js.log 单独执行 :business-domain:jsNodeTest，以上述三个类名各加尾部 * 重跑，XML 确认 3 类／10 方法且 0 failure／error／skip。local-computed-entry-choice-final-postgres.log 以相同公共参数重跑上述完整两个 backend 类，2 类／19 方法全通过。SQLite 使用 relation-choice-1105-preparation；PG 使用 candidate-query-1105-preparation-postgres，显式 FT_TEST_POSTGRES_URL／用户沿既有 ft_offline_ledger_1104_test，不探测或启动数据库。最终 XML 在仓库外 local-computed-entry-choice-xml/sqlite-platforms 及 postgres。

独立 5a 及实际独立 5b 无 finding；5b 按 codebase-degradation-review 调度 contract-design-review，检查计算计划与 JSON 复制入口的必要隔离、目标依赖及分类合同，ui-reuse 不适用。Cross-platform Impact Check：三端共享计算型计划字段决定受影响，页面／交互／文案未变；Node 不代替页面冒烟，未追加原生设备 QA。核对 design、tasks、功能地图及导入模块地图、README、词表、apply 指引；本片只更新实现能力与验证边界，无新命令、配置、依赖或术语，README／词表／skill 无需改写。无新增用户决策或通用规范需沉淀；完整发现、核对计划、保存及页面尚未组装，1.3／6.4／6.6 保持未完成。OpenSpec strict 与 diff 检查提交前执行。

### 来源绑定触达验证与收尾证据

验证时间：2026-11-05 15:55 UTC；比较基线／验证 HEAD `763ea10d`。完整 writer 原 oracle 的绑定 touched 缺口保留于 cash-occurrence-range-sqlite.log；新增 source-binding-commit-red.log 通过真实 duplicate 计划、确认核心、来源仓储与投影维护路径复现 expected 2／actual 1。合成场景在事务内去除原待处理问题和草稿关系，预置同一来源的文件绑定、移除 provider 绑定；明确断言操作为 duplicate、issues 为空且 unchanged，避免原问题项掩盖漏维护。确认后检查 provider 写入、来源排序键与 digest 改变、projection_version 及收据版本同步推进；独立新会话重导同内容保持行状态、来源 digest 与投影版本不变。此证据证明实际维护被触发，不声称构造了展示代表切换。

CashSourceWriteRepository.saveOccurrence 只在真实 insert/update 后返回 true；writer 每个来源出现先保存，再累计变化，全部 bind 更新不因前项为 true 而短路。第二回归逐个核对两份文件来源的全部绑定键已保存，重放 touched 为空、完整保存状态不变；coverage 更新返回 true，再同值返回 false。所有新增写入测试均以同一 sentinel 强制事务回滚，最终核对全库状态；原完整 oracle 的全部固定输入、期望及回滚断言保留。

source-binding-final-sqlite.log 沿下方公共 Gradle 参数执行 :backend:test，选择完整 CashRecordWriterContractTest、CashSourceWriteContractTest、LedgerSourceVersionContractTest：3 类／15 方法、0 failure／error／skip。source-binding-final-postgres.log 选择完整 writer 与版本类：2 类／12 方法、0 failure／error／skip，未声称旧来源黄金类在此 PG 轮运行。SQLite 使用 record-writer-sqlite、source-write-v2-sqlite、sqlite；PG 使用 cash-range-writer-1105-postgres 和 postgres-5432，显式 FT_TEST_POSTGRES_URL 指向既有 ft_offline_ledger_1104_test、用户 huangwenlong。FT_KOTLIN_RECORD_WRITER_CONTRACT_DIR／FT_KOTLIN_SOURCE_WRITE_CONTRACT_DIR／FT_KOTLIN_LEDGER_CONTRACT_DIR 按上述目录设置；无自动探测或启动数据库。最终 XML 原位保存在仓库外 source-binding-xml/sqlite 和 postgres。

独立 5a 及实际独立 5b 均无 finding；5b 按 codebase-degradation-review 调度 contract-design-review，复核来源身份／coverage／revision、真实 DML 结果、writer 受影响集与提交维护的一致性，不新增持久状态、版本或接口。Cross-platform Impact Check：仅现有 Kotlin 服务端写入与派生触达修复，三端客户端合同、UI、交互、文案和共享规则不变；ui-reuse、浏览器及原生设备 QA 不适用。本片不完成未来写集提交或投影退役。核对 design、tasks、两级功能地图、README、词表及 apply 指引；无新增术语、能力、配置、迁移、命令或工作流，除 design／tasks 外无需改写。无新增用户决策或通用规则需沉淀。OpenSpec strict 与 diff 检查提交前执行。

### 现金发生时间业务门验证与收尾证据

验证时间：2026-11-05 15:42 UTC；比较基线／验证 HEAD `fa706507`。cash-occurrence-range-red.log 与 cash-occurrence-range-native-red.log 分别先复现本地记录／来源归一和原生现金／写入接受超限日期。cashOccurrenceTimeInRange 仅用于现金 occurred_at，按转换后的 UTC 秒检查 1900 首日含至 2101 首日不含；原微秒归一与 null 草稿资格保留。shared LocalCashRecordValues 和 native CashRecordPolicy 共用此门，平台／银行／工行三类归一在来源时区转换之后检查；超限仍沿 time_unreadable／null，原始账单文本与覆盖证明不伪造或裁剪。通用 Python 时间原语、来源时区转换、系统创建时间和筛选日期不改。

独立 5a 的 P2 已真实先红再绿关闭：旧 manualCashTimestamp 同时被投资调用，不能直接收窄。cash-occurrence-range-investment-red.log 复现真实投资命令与纯投资转账被误拒；修复后旧函数原体及完整 10,000+ 手工日期黄金重放均不变，新增现金专用包装只用于普通现金／旧现金入口和转账现金端。InvestmentWriteService／InvestmentEventRepository 及纯投资转账继续原解析范围。新 service 回归覆盖 1899／2101 投资记录可写、纯投资转账可写；含现金的两个混合方向及全现金方向同值拒绝，完整表状态和账本版本不变。cash-occurrence-range-investment-green.log 已转绿。

新日期回归覆盖首尾微秒、纳秒截微秒、偏移后的 UTC 跨界、上海 1900 历史偏移以及本地 2101 年仍落 UTC 2100 年的合法来源日期；现金值拒绝同时，通用解析仍接受宽域时刻。原生 writer 专项先实际保存 entry note 再提交超限发生时间，核对整事务回滚及全库不变，异常未发生时也用显式 sentinel 强制回滚，红测试不污染数据库。候选实际 SQL 和本地快照读取对照 4 个新范围极值，原 ±61 天包含边界保留，事务完整回滚且全库不变；不再声称支持 year 1／9999 的现金事实。

cash-occurrence-range-final-sqlite.log／final-postgres.log 沿下方公共 Gradle 参数执行 :backend:test，选择 CashOccurrenceTimeBoundaryTest、CashImportCandidateContractTest、ManualCashTimestampMigrationTest、CashRecordPolicyMigrationTest、SharedCashTimestampParityTest、StatementSourceTimestampParityTest、LocalStatementNormalizationParityTest、CashTransferWriteTest 的完整类，以及 CashRecordWriterContractTest.invalidOccurrenceTimeRollsBackAnEarlierWriteInTheSameTransaction、InvestmentWriteTest.cashOccurrenceRangeDoesNotRestrictInvestmentCommands 两个指定方法。双库各 10 类／50 方法，0 failure／error／skip。该日期专项不替代完整回归；原 writer 全 15-case 方法的绑定 touched 缺口已由下方独立来源触达切片恢复，固定期望未改。

cash-occurrence-range-platforms.log 执行领域 jvmTest／jsNodeTest／wasmJsNodeTest／testAndroidHostTest，各选择 CashOccurrenceTimeTest、LocalCashRecordPolicyTest、CommonLocalBankRowNormalizationTest、CommonLocalIcbcRowNormalizationTest、CommonLocalPlatformRowNormalizationTest、PythonCashTimestampTest、StatementSourceTimestampTest；四任务各 7 类／18 方法，0 failure／error／skip。iOS Simulator 测试与 Arm64 主代码实际编译通过，仅计编译。最终 XML 在既有仓库外 cash-occurrence-range-xml 的 final-sqlite／final-postgres／platforms。SQLite 使用 record-writer-sqlite、candidate-query-1105-sqlite 及 sqlite；PG 使用 candidate-query-1105-postgres 和 postgres-5432，writer 为手动在已授权 ft_offline_ledger_1104_test 建 ft_cash_range_writer_1105 schema 后由未修改 exporter 仅生成合成数据，显式设置 URL／用户和 currentSchema，不启动或探测数据库、不改 public。

独立 5a／实际独立 5b 无本片残余阻断，P2 修复后重审关闭；终态证据与文档提交前核对。Cross-platform Impact Check：三端共享记录值／导入归一受影响，未修改页面、文案或交互组件，本片不把 Node 当浏览器页面或 iOS 运行证明；客户端提交前校验及对应表单冒烟、新写集服务仍待实施，3.2／6.4 和整体日期交付不勾选。核对 design、tasks、总览及现金／导入地图、apply 指引和 README，无新增依赖、迁移、部署命令或术语；README 无需变动。用户已确认的现金范围仅落本业务规格，不升级为投资／系统时间的通用规则；无额外规范沉淀。OpenSpec strict 与 diff 检查提交前执行。

### 本地计算型关系选择与拆分恢复验证与收尾证据

验证时间：2026-11-05 15:26 UTC；比较基线 `77cd387d`，验证 HEAD `fa8edb26`（仅另含已确认日期规划）。local-computed-relation-choices-real-refund-red.log 通过原生真实工厂生成合法退款候选，复现选择时 CashDecimalLiteral 不在 copy 叶白名单而触发 InvalidCashValue；原 16 个建议组 case 只有镜像，不能单靠它们证明退款路径。修复仅允许该不可变计算叶深复制，native／local 的原始 source_payload 校验仍拒绝它。local-relation-decisions-red.log 先证明本地计算型选择／拆分入口缺失。新入口直接复用同一选择规则，保留精确虚拟整数、金额及 JSON 整数代次；原 JSON 入口仍复制后委托。

拆分恢复从同一完整快照读取 context 成员，在实际选中且 member／fact 均存在后才解码独立值，旧确认闭包沿完整 source 的 active confirmed 两端关系展开。common 3 方法通过真实退款组选择、完整图应用、原金额与 ID 类型、previous 不变、原文输入拒绝计算叶、旧闭包成员恢复及坏无关元数据不解码；缺失事实不读其坏独立值，真正使用坏值时程序异常传播。退款 fixture 显式提供可靠来源键和公共备注决定，不放宽图或字段冲突规则。

原生完整预览方法保留全部旧 oracle；新增方法对原 16 个生成组及新增真实退款候选比较 native／local 选择后的完整 group、invalidated、sources、generations。原组应用方法对全部原 application case 比较成功的完整组／失败项／图状态，异常分支比较 code／fields／图状态；不声称异常后的组对象已逐字段比较。保留原手工公共值冲突、旧闭包及全库不变断言，不修改原固定期望。

local-relation-decisions-final-platforms.log 沿下方公共 Gradle 参数执行 backend CashImportPreviewSnapshotContractTest、CashRecordPolicyMigrationTest，领域 JVM／JS Node／Wasm Node／Android Host 各选择 LocalCashImportRelationDecisionsTest、CashImportRelationChoiceRulesTest、LocalCashImportRelationGroupsTest、LocalCashRecordPolicyTest，随后编译 iOS Simulator 测试及 Arm64 主代码。local-relation-decisions-final-postgres.log 显式设置 FT_TEST_POSTGRES_URL／用户及 candidate-query-1105-preparation-postgres，重跑同两个 backend 类；SQLite 使用 relation-choice-1105-preparation。双库各 2 类／47 方法，四领域目标各 4 类／13 方法，均 0 failure／error／skip；iOS 只计编译。最终 XML 在既有仓库外缓存 local-relation-decisions-xml/sqlite-platforms 与 final-postgres，未自动探测或启动数据库。中断后的缺失进程句柄与无 Gradle 进程已核实后重跑，未完成的一轮不计通过。

独立 5a 及实际独立 5b 无遗留 finding，源码及终态证据提交前复核。Cross-platform Impact Check：三端共用计算型选择与快照拆分读取，无 UI／路由／文案变化；Node 不替代页面冒烟，默认不新增原生设备 QA。核对 design、tasks、两级地图、apply 指引与 README 范围，无新增依赖、配置、外部入口或术语；README 无需修改。没有新增产品取舍或通用规范，日期用户决定单独保留在已确认规划。完整发现、核对计划、写集、加密库和页面仍未完成，1.3／6.4／6.6 不勾选。OpenSpec strict 与 diff 检查提交前执行。

### 本地预览准备配置验证与收尾证据

验证时间：2026-11-05 14:43 UTC；比较基线／验证 HEAD：`5957e3eb`。local-preview-preparation-red.log 先复现缺少本地准备入口。LocalCashImportPreviewPreparation 以显式活动守卫、同一快照账户、本地候选及本地关系上下文配置既有共享规则；计算型查询重载直接保留值类型，JSON 入口仅解码后委托。native 新方法从全部 8 个原快照 case 的已增强计算型操作出发，经本地 prepareOperations 比较完整操作／账户／候选／保留目标／上下文 token，再进真实工厂比完整 summary；保留旧黄金和全库只读断言，不称为原文件或原始行到预览的完整证明。

common 4 方法以真实快照组合来源增强、候选、上下文与工厂，核对两层守卫、>2^53 的账户修订、输入不变、失效目标先于账户解码及同一守卫程序异常。计算型操作保留 Instant／CashAmount，查询与 JSON 起点一致且计算摘要不变。按独立复核建议补支付宝来源行的真实组合：存量记录位于窗口外且原文／标准化编号皆缺失，只有 provider 绑定；SourceOccurrence 的 providerId／固定来源范围确实进入候选读取。显式 null 编号保持原不回退语义，未用固定候选端口伪造该结果。

local-preview-preparation-final-sqlite-platforms.log 沿下方公共 Gradle 参数，backend 选择 CashImportPreviewSnapshotContractTest；领域 JVM／JS Node／Wasm Node／Android Host 选择 LocalCashImportPreviewPreparationTest、CashImportPreviewPreparationRulesTest、CashImportCandidateQueryTest、LocalCashImportCandidatesTest，随后编译 iOS Simulator 测试及 Arm64 主代码。local-preview-preparation-postgres.log 显式设置 FT_TEST_POSTGRES_URL／用户及 candidate-query-1105-preparation-postgres，重跑同一 backend 类；SQLite 使用 relation-choice-1105-preparation。双库各 1 类／16 方法，四领域目标各 4 类／16 方法，均 0 failure／error／skip，iOS 只计编译。最终 XML 保存于既有仓库外缓存 local-preview-preparation-xml 的 sqlite-platforms／postgres，未自动探测或启动库。

独立 5a 及实际独立 5b 无阻断，源码及最终证据提交前快核。Cross-platform Impact Check：三端纯准备入口受影响，backend 未改授权或查询；无页面／交互／文案变化，Node 不替代页面冒烟，默认不新增原生设备 QA。核对 design、tasks、两级地图与 apply 指引，无新增依赖、外部配置、命令或领域术语，README 无需修改。守卫由调用方提供且快照固定，本片不代表应用会话或撤权生命周期已接通。完整发现、保存选择重派、业务计划、写集、加密库及页面仍未完成，1.3／6.4／6.6 不勾选；没有新增产品政策或通用规范。OpenSpec strict 与 diff 检查通过。

### 本地候选读取验证与收尾证据

验证时间：2026-11-05 14:32 UTC；比较基线／验证 HEAD：`b510c2ae`。local-candidate-read-red.log 先复现缺少本地候选入口。readLocalCashImportCandidateIds 在同一完整工作区快照上保留正式／未删／导入或历史资格、包含边界的时间窗、来源类型门、provider 绑定、现金部分账户、冲突作用域和支付宝编号变体。平台字段描述被现有行归一及来源说明共用；仅平台适配按标准化字段、原文统一键及该来源映射读取编号，保留首个非 null 值优先，空串或非文本不向后跳选、不 JSON 化成编号。canonical 原字段优先及非文本不转换是本 change 的来源边界，不宣称所有旧 SQL 隐式转换都等价；原服务端仓储仍未改且待退役。

新增 native 方法遍历全部 14 个原 case，完整本地 ID 集同时与真实原查询和原固定期望比较，保留原方法和全库只读断言。common 5 方法覆盖标准化／原文优先、平台映射、非平台不探测、资格／作用域／账户／绑定、前缀、包含时间边界及不解析无关金额。独立审查的 P3 已先红再绿：local-candidate-read-lazy-red.log 用真实 selector 及不可读取的 JsonObject 证明标准化两键齐全时仍解码原文；原文改为由平台适配惰性解码并缓存。回归同时证明确需回退时原程序异常原样传播，不吞为空候选。

local-candidate-read-final-sqlite-platforms.log 使用下方公共 Gradle 参数，backend 选择 CashImportCandidateContractTest、PlatformRowNormalizationGoldenTest、CashSourceEffectsGoldenTest；领域 JVM／JS Node／Wasm Node／Android Host 选择 LocalCashImportCandidatesTest、CashImportCandidateQueryTest、CommonLocalPlatformRowNormalizationTest、CommonCashSourceEffectsGoldenTest，随后编译 iOS Simulator 测试及 Arm64 主代码。local-candidate-read-final-postgres.log 显式设置 FT_TEST_POSTGRES_URL／用户及 candidate-query-1105-postgres 配置，重跑同 3 个 backend 类；SQLite 使用 candidate-query-1105-sqlite。双库各 3 类／4 方法，四领域目标各 4 类／11 方法，均 0 failure／error／skip；iOS 只计编译。最终 XML 在既有仓库外缓存 local-candidate-read-xml/final-sqlite-platforms 及 final-postgres，早期 eager 版结果不替代最终矩阵。未自动探测或启动数据库。日期域极值未由这 14 例证明，真实原查询跨 year 1／9999 的补验保留于 1.3，尚未据内存 SQL 探针改动生产时间规则。

独立 5a／实际独立 5b 无其余 finding，P3 修复后重审，终态证据提交前快核。Cross-platform Impact Check：三端共享候选读取与映射，原服务端仍使用原仓储；无页面、文案或交互变化，Node 单测不冒充页面冒烟，默认不新增设备 QA。核对 design、tasks、两级地图和 apply 指引；无新增依赖、配置、部署入口或术语，README 无需变动。用户来源边界保留于本业务规格，不擅自推广到所有 JSON 或加通用交易号列。本地准备配置见对应证据，完整发现、业务计划、写集、加密存储和页面仍未接通，1.3／6.4／6.6 未勾选。

### 本地核对结果呈现验证与收尾证据

验证时间：2026-11-05 14:18 UTC；比较基线／验证 HEAD：`f39fe45c`。local-preview-presentation-red.log 先复现缺少本地图应用／呈现入口。presentLocalCashImportPreview 使用真实工厂的计算型预览状态、完整图 build、原 entry 摘要及精确边排序；applyLocalCashImportRelationGroups 复用既有完整组决定。新增 native 回归遍历全部 42 个原呈现 case，先比较图应用失败与完整状态，再比较含成功／密码文件的完整呈现结果，保留原黄金及全库只读断言。新 common 用真实工厂验证时刻／金额类型、稳定目标、原输入与文件诊断不变，以及 2^63 边界和数字／字符串回退排序。测试 fixture 的星投影下标与 atomic 部分一致性已修正，未放宽生产规则或修改原期望。

local-preview-presentation-sqlite-platforms.log 沿下方公共 Gradle 参数执行 backend CashImportPreviewSnapshotContractTest，以及领域 JVM／JS Node／Wasm Node／Android Host 的 LocalCashImportPreviewPresentationTest、CashImportPreviewPresentationTest、LocalCashImportRelationGroupsTest、CashImportRelationDecisionRulesTest，随后编译 iOS Simulator 测试及 Arm64 主代码。local-preview-presentation-postgres.log 显式设置 FT_TEST_POSTGRES_URL／用户及 candidate-query-1105-preparation-postgres 隔离配置，重跑同一 backend 类；SQLite 使用 relation-choice-1105-preparation。双库各 1 类／15 方法，四领域目标各 4 类／13 方法，全部 0 failure／error／skip，iOS 只计编译。最终日志及 local-preview-presentation-xml 保存在既有仓库外缓存，未自动探测或启动数据库。

独立 5a 范围／工程／异常／最终 diff 和实际独立 5b 契约审查无 finding，终态证据提交前复核。Cross-platform Impact Check：Web／Android／iOS 共用新配置，未改页面、路由、文案或持久化，Node 测试不冒充页面冒烟，默认不追加原生设备 QA。design、tasks、两级地图与 apply 指引同组核对，README 无新增运行命令或配置。平台标准化字段优先、仅平台适配可回退原始 JSON 的用户决定已先写入 proposal／delta／design／任务，本地候选读取见对应证据，原服务端 SQL 仍待退役；该决定仅落本业务，不另造通用规范或新术语。完整发现、保存选择复用、拆分独立值读取、业务计划、写集、加密缓存和页面仍未完成，1.3／6.4／6.6 保持未勾选。OpenSpec strict 与 diff 检查通过。

### 本地建议组生产配置验证与收尾证据

比较基线／验证 HEAD：`b2188d3a`。local-relation-groups-red.log 先复现本地组入口缺失；buildLocalCashImportRelationGroups 使用真实本地工厂及精确整数、摘要与计算金额桥。微信状态文字两函数原体移为同文件共享函数，原平台方法委托且仍注入原金额解析；原消费 selector 位于共享组规则文件，native 负数／状态原语不变。common 新 3 个回归覆盖真实工厂、独立 CPython 虚拟关系 ID、来源／类型／方向／负数门、100 位状态嵌入金额、退款计算不套列限及程序错误传播；独立 ID 生成器保留于仓库外 preview-factory-id-oracle.py。

local-relation-groups-green-sqlite.log 保留原 native 对照失败：normal 组仅 suggestion_id 不同，完整边 ID／字段一致。测试先将已计算操作转普通 JSON，导致 occurred_at 计算时刻被字符串替换；指纹包含 parentOps 原值，因此不可混用。local-preview-computed-input-red.log 先复现缺少计算型工厂入口，随后增加直接 List<Map> 重载并深复制原计算值；JSON 重载仅解码委托，不猜字符串。追加回归验证金额／时刻类型、原输入摘要不变及 JSON 字符串仍保留。native 测试标量桥只转换平台表示：时刻保留秒和纳秒，大整数保留整数 token，已知 Int 用显式整数标记，不改普通文本、浮点或 CashAmount；原期望 hash 不变。local-relation-groups-computed-sqlite.log 转绿。

验证时间：2026-11-05 13:59 UTC。local-relation-groups-sqlite-platforms.log 使用公共 Gradle 参数运行 backend CashImportPreviewSnapshotContractTest、CashPlatformRefundTest、LocalCashRelationChoiceParityTest，以及领域 JVM／JS Node／Wasm Node／Android Host 的 LocalCashImportRelationGroupsTest、LocalCashImportPreviewSnapshotTest、CashImportRelationGroupRulesTest、CashPlatformRefundTest、CashImportDigestTest，随后编译 iOS Simulator 测试及 Arm64 主代码。local-relation-groups-postgres.log 在显式 FT_TEST_POSTGRES_URL／用户及 candidate-query-1105-preparation-postgres 隔离配置上重跑同 3 个 backend 类；SQLite 沿 relation-choice-1105-preparation。双库各 3 类／18 方法，四领域目标各 5 类／17 方法，全部 0 failure／error／skip；iOS 只计编译。全部 16 个原建议组 case 对照完整组／候选／失败／计算摘要，原 8 个初始状态、原金额表示／摘要、原 2,000／20 候选门及全库只读断言保留；原 65,536 BMP 和大代次解析回归亦通过。最终日志及 local-relation-groups-xml 均在仓库外既有缓存，不自动探测或启动数据库。

独立 5a／实际独立 5b 对实现及类型修复复核无 finding，终态证据提交前快核；OpenSpec strict、diff 与文档检查提交前执行。Cross-platform Impact Check：三端生产组配置受影响，backend 只委托相同共享状态决定，无 UI／路由／文案变化，不增加本片页面冒烟；Node 不替代页面 QA，原生设备交互默认不执行。核对 design、tasks、两级地图、apply 指引和 README 范围，无新依赖、配置、外部入口或产品术语。仅完成已确认边界内组配置和计算输入保真，不增加通用政策；完整发现、选择应用生产接线、业务计划、写集提交、缓存和页面仍待完成，1.3／6.4／6.6 不勾选。

### 本地初始预览工厂验证与收尾证据

比较基线／验证 HEAD：`0ffc1076`。local-preview-factory-red.log 先复现本地工厂／精确整数入口缺失；buildLocalCashImportPreviewSnapshot 组合存量读取、上下文正式事实、有效草稿、原共享操作叠加及条目继承，按叠加前存量集合读取同快照条目。CashImportPreviewInteger 复用既有精确整数解析／比较，显式支持已知整数 JSON 编码与复制，不改变金额或持久标识范围。原 choice generation 继续原 null／文本门，解析体只抽为共用函数。新工厂的 4 个 common 回归覆盖草稿／已删元数据／手工条目、完整虚拟流水与部分、数值排序与全角 BMP 数字、JSON 数字、原输入不变及显式时钟错误传播。固定虚拟标识由仓库外 preview-factory-id-oracle.py 的独立 CPython JSON／SHA-256 对照，不以工厂自身生成期望。

验证时间：2026-11-05 13:36 UTC。local-preview-factory-sqlite-platforms.log 沿公共 Gradle 参数运行原 CashImportPreviewSnapshotContractTest、既有 LocalCashRelationChoiceParityTest／LocalCashImportPreviewReadParityTest，以及领域 JVM／JS Node／Wasm Node／Android Host 的 LocalCashImportPreviewSnapshotTest、CashImportDigestTest、CashImportRelationChoiceRulesTest，随后编译 iOS Simulator 测试及 Arm64 主代码。四领域目标各 3 类／11 方法，0 failure／error／skip，iOS 只计编译；原代次测试保留所有 65,536 个 BMP 字符及 2,000 位进位／摘要对照，未改旧期望。

独立审查指出金额归一比较的证据限制后，新 local 工厂方法加强为全部 8 个原 case 的完整 summary 直接相等，另比较原金额 representation 及完整 values／operations／rows／事实 ID、金额和时刻的计算摘要。原黄金方法及其预期不变。local-preview-factory-digest-sqlite.log／local-preview-factory-digest-postgres.log 是最终 backend 矩阵，双库各 3 类／16 方法，全部 0 failure／error／skip；此前归一／直接 summary 日志不替代最终摘要证据。两个原始读取器及全库只读断言保留。SQLite 使用既有 relation-choice-1105-preparation，PG 使用显式 FT_TEST_POSTGRES_URL／用户及 candidate-query-1105-preparation-postgres 隔离配置，不自动探测或启动库。最终日志和 local-preview-factory-xml 在仓库外既有缓存；后续只加强 backend 测试，领域源码和平台测试未变，不重复未受影响平台矩阵。

独立 5a／实际独立 5b 已复核实现和精确对照，无 finding；终态证据提交前复核。OpenSpec strict、diff 及文档检查在提交前完成。Cross-platform Impact Check：工厂供三端复用，原生既有业务调用不改；无 UI／路由／文案变化，本片不新增页面冒烟，Node 不替代页面 QA，原生设备交互默认不执行。核对 design、tasks、两级地图、apply 指引和 README 范围，无新依赖、外部配置或用户术语。只实现已确认计算边界，不沉淀通用规范；同源快照／上下文由调用方保证，关系发现生产适配、完整业务计划、写集提交、缓存和页面仍待接通，1.3／6.4／6.6 不勾选。

### 本地预览存量值读取验证与收尾证据

比较基线／验证 HEAD：`8cfb34eb`。local-preview-read-red.log 先复现本地存量值入口缺失；readLocalCashImportPreviewValues 只读取上下文范围的原始值、精确部分和来源键，原生 scope 选择仅提取原条件分支，SQL 及其余读取保持独立。LocalCashImportPreviewReadParityTest 对照原无操作预览完整 values 的 7 组种子／闭包及全库只读状态，不删字段或替换旧 oracle。common 回归覆盖账户／文件／歧义及异来源范围、草稿／已删元数据、精确 JSON、解释白名单、时刻／部分／排序键与输入不变、无关非法金额不读取。local-preview-read-green-sqlite.log 先通过 JVM 与原完整预览。

验证时间：2026-11-05 13:19 UTC。local-preview-read-sqlite-platforms.log 使用公共 Gradle 参数执行 backend LocalCashImportPreviewReadParityTest、LocalCashRelationContextParityTest、CashImportPreviewSnapshotContractTest，领域 JVM／JS Node／Wasm Node／Android Host 的 LocalCashImportPreviewReadTest 和 LocalCashRelationContextTest，随后编译 iOS Simulator 测试及 Arm64 主代码。local-preview-read-postgres.log 在显式 FT_TEST_POSTGRES_URL／用户及既有 candidate-query-1105-preparation-postgres 隔离配置上重跑同三个 backend 类；SQLite 沿 relation-choice-1105-preparation。双库各 3 类／16 方法，四领域目标各 2 类／7 方法，全部 0 failure／error／skip；iOS 只计编译。日志及 local-preview-read-xml 保存于仓库外既有缓存；不自动探测或启动数据库。

独立 5a／实际独立 5b 复核无 finding，终态证据提交前复核；OpenSpec strict 和 diff 检查通过。Cross-platform Impact Check：三端同一快照读取受影响，无 UI／路由／文案变化，不另做本片页面冒烟；Node 不替代页面 QA，原生设备交互默认不执行。核对 design、tasks、两级地图、apply 指引与 README 范围；无新配置、依赖、外部入口或词表概念。只补当前范围内读取能力，同源版本边界仍由 owner／调用方保证；本地初始组合见本页工厂证据，完整发现、业务计划与提交仍待接通。1.3／6.4／6.6 继续不勾选；无新增产品取舍或通用规范。

### 预览条目公共字段验证与收尾证据

比较基线／验证 HEAD：`940d8a13`。`preview-entry-public-red.log` 先复现共享入口／条目模型缺失；原公共字段循环移入 applyCashImportPreviewEntryPublic，领域条目模型由 native typealias 复用，仓储对象身份、var、copy 及已保存修改追踪不改。新增回归覆盖同条目补丁顺序、根字段继承／缺根回退、手工字段惰性解码、新操作标识与排序及程序错误传播。首次绿色编译只发现新 fixture 的泛型推断和调用私有 fact.copy，改为明确类型与正式事实工厂后 preview-entry-public-green-typed.log 通过；未更改既有期望或领域行为。

验证时间：2026-11-05 13:10 UTC。preview-entry-public-sqlite-platforms.log 使用公共 Gradle 参数，运行原 backend CashImportPreviewPreparationContractTest／CashImportPreviewSnapshotContractTest，以及领域 JVM／JS Node／Wasm Node／Android Host 的 CashImportPreviewEntryRulesTest、CashImportPreviewSnapshotTest、CashImportEntryChoiceRulesTest，随后编译 iOS Simulator 测试与 Arm64 主代码。preview-entry-public-postgres.log 在既有专用 `_test` 库、显式 FT_TEST_POSTGRES_URL／用户及 candidate-query-1105-preparation-postgres 配置上重跑同两个 backend 类；SQLite 沿 relation-choice-1105-preparation。双库各 2 类／13 方法、四领域目标各 3 类／10 方法，全部 0 failure／error／skip；iOS 仅计编译。日志与 preview-entry-public-xml 均保留在仓库外既有缓存，不自动启动或探测数据库。

独立 5a／实际独立 5b 代码和领域模型审查无 finding，终态证据提交前复核；OpenSpec strict、diff 检查及文档核对在提交前执行。Cross-platform Impact Check：共享规则供三端及 backend 复用，无 UI／路由／文案变化，不新增本片页面冒烟；Node 不替代页面 QA，原生设备交互默认不执行。核对 design、tasks、两级地图、apply 指引及 README 范围，无新外部入口、依赖、配置或词表概念；条目模型搬移不是数据库迁移。仅本 change 的实现决策，不新增产品规范。完整本地读取、预览和提交／页面仍待接通，1.3／6.4／6.6 继续不勾选。

### 预览准备读取编排验证与收尾证据

比较基线／验证 HEAD：`177d626a`。`preview-preparation-red.log` 先复现共享准备入口缺失；原来源字段增强、编辑目标保留、操作资格及种子集合整理移入 CashImportPreviewPreparationRules，Prepared 模型以 native typealias 单源共享。守卫及账户／候选／关系上下文读取沿原端口注入；prepare 与 prepareOperations 守卫时点、无目标先失败、账户与候选再闭包读取顺序保持，原 operations 不改。

验证时间：2026-11-05 13:00 UTC。`preview-preparation-sqlite-platforms.log` 沿公共 Gradle 参数运行 backend 原 CashImportPreviewPreparationContractTest／CashImportPreviewSnapshotContractTest，以及领域 JVM／JS Node／Wasm Node／Android Host 的 CashImportPreviewPreparationRulesTest／CashImportRelationGroupRulesTest，随后编译 iOS Simulator 测试与 Arm64 主代码。`preview-preparation-postgres.log` 使用既有 PG 专用库和隔离 schema、显式 FT_TEST_POSTGRES_URL／用户，重跑同两个 backend 类。双库各 2 类／13 方法，四领域目标各 2 类／6 方法，全部 0 failure／error／skip；iOS 只计编译。SQLite 使用既有 relation-choice-1105-preparation，PG 使用 candidate-query-1105-preparation-postgres 配置，不自动探测或启动数据库。最终日志及 preview-preparation-xml 保存在仓库外既有缓存目录。

独立 5a／实际 5b 已复核代码、端口及终态证据；OpenSpec strict 和 diff 检查在提交前执行。Cross-platform Impact Check：三端预览准备计算受影响，backend 原 SQL／权限与读取资格不改；无 UI、路由或用户文案变化，不需本片页面冒烟，Node 不替代页面 QA，原生设备 QA 默认不执行。核对 design、tasks、两级功能地图及 apply 指引；没有新依赖、入口、配置或词表概念，README 无本片新增操作。端侧候选查询执行、完整工厂与业务计划／页面仍待接通，整体 1.3／6.4／6.6 不勾选；无新增产品取舍或通用规范。

### 关系建议组与候选组合验证与收尾证据

比较基线／验证 HEAD：`01586895`。`relation-groups-red.log` 先复现共享组构建入口缺失；原 build／member／variants／combinations／candidateChoices 及 result 模型移入领域层，edgeKey 为单一共享函数。native 保留严格 ID、虚拟 ID、计算摘要、原 BigInteger 父项边界、退款数值及负数原语，微信原消费仍沿既有发现规则注入；没有把它误记为端侧生产配置已完成。

`relation-groups-int-red.log` 以独立 CPython 完整组 payload 摘要复现已知 candidateCount 被本地开放 Number 编为 1.0，实际 hash 精确命中独立的浮点对照。proof 使用已有 SourceJsonInteger 保持原整数字面量；native sourceJson 亦识别该标记，原原生摘要仍须由完整黄金验证。仓库外 `relation-groups-known-int-oracle.py` 保留合成输入及 1／1.0 两个完整对照，不修改原 oracle，也不扩大计数范围。

验证时间：2026-11-05 12:44 UTC。`relation-groups-final-sqlite-platforms.log` 沿公共 Gradle 参数执行 :backend:test（原 CashImportPreviewSnapshotContractTest 全 12 方法、CashImportPreviewPreparationContractTest 全 1 方法／4 case）、领域 jvmTest／jsNodeTest／wasmJsNodeTest／testAndroidHostTest（各 CashImportRelationGroupRulesTest、CashImportPreviewOverlayTest、CashImportRelationDecisionRulesTest），并编译 iOS Simulator 测试／Arm64 主代码。`relation-groups-final-postgres.log` 重跑同 2 个 backend 类；双库各 2 类／13 方法，均 0 fail／error／skip。原完整建议组、Decimal／时刻摘要、2,000／20 上限、选择／图应用／类型回退及全库只读断言保留；四领域目标各 3 类／11 方法，均 0 fail／error／skip，iOS 只计编译。最终矩阵含已知候选数整数修复及独立完整组 hash；修复前矩阵不作为最终证据。新 common 的固定虚拟 ID／存量门仍只是测试配置，不代表完整端侧生产适配。

数据库沿既有仓库外 relation-choice-1105-preparation／candidate-query-1105-preparation-postgres 配置，PG 显式使用 FT_TEST_POSTGRES_URL／用户及隔离 schema，不探测或启动数据库。最终日志及 `<缓存>/relation-groups-final-xml/` 保留本次矩阵。独立 5a 及实际独立 5b 已对迁移和整数标记修复复核无 finding，终态 XML、文档及 diff 已复核，OpenSpec strict 与 diff 检查通过；已核对 design、tasks、Compose README、两级功能地图及 apply 指引，无新依赖、外部入口或配置，无需改 README 正文。

Cross-platform Impact Check：三端建议组计算受影响，backend 复用原调用；无页面、路由或用户文案变化，不需本片页面冒烟，Node 不替代页面 QA，原生设备 QA 默认不执行。完整发现／本地预览生产配置、工厂与页面仍待接通，1.3／6.4／6.6 不勾选；无新增产品取舍或通用规范。

### 冻结核对结果组装验证与收尾证据

比较基线／验证 HEAD：`4f00db09`。`preview-presentation-red.log` 先复现共享呈现入口缺失。完整原 present、成员字段边界和 targetSnapshot 移入领域规则，backend 保留原精确 BigInteger／虚拟部分判定，并复用原 entry 选择规则与数字排序。cashImportRef／cashImportFileView 单源共享，服务端会话权限、寿命及数据读取保持在原入口；无页面、布局或文案变化。

验证时间：2026-11-05 12:30 UTC。`preview-presentation-sqlite-platforms.log` 使用公共 Gradle 参数执行 :backend:test（原 CashImportPreviewSnapshotContractTest 全 12 方法、CashImportPreviewPreparationContractTest 全 1 方法／4 case）、领域 jvmTest／jsNodeTest／wasmJsNodeTest／testAndroidHostTest（各 CashImportPreviewPresentationTest、CashImportPreviewSnapshotTest、CashImportEntryChoiceRulesTest），并编译 iOS Simulator 测试／Arm64 主代码。`preview-presentation-postgres.log` 重跑同 2 个 backend 类。双库各 2 类／13 方法，四领域目标各 3 类／9 方法，均 0 fail／error／skip，iOS 只计编译。旧完整结果、计算摘要、成员冻结边界及文件结果断言全部保留，未修改 oracle。新测试覆盖触及／草稿排序、稳定目标、虚拟部分字段隐藏和密码／诊断深复制；common test-only ID 适配不是生产端侧 BigInteger／JSON 数值适配。初次新断言的 emptyList 泛型推断失败不计通过。

SQLite 与 PG 分别使用既有 relation-choice-1105-preparation／candidate-query-1105-preparation-postgres 外部配置，PG 显式沿既有 FT_TEST_POSTGRES_URL／用户与隔离 schema，不探测或启动数据库。日志与 `<缓存>/preview-presentation-xml/` 保存本次完整矩阵。独立 5a 及实际独立 5b 无 finding，终态 XML、文档与 diff 已复核；OpenSpec strict 与 diff 检查通过。已核对 design、tasks、Compose README、两级功能地图和 apply 指引，无新外部入口、依赖或配置，无需改 README 正文。

Cross-platform Impact Check：三端共享同一冻结结果组装，backend 复用；无页面或交互变更，本片无需页面冒烟，Node 不替代页面 QA，原生设备 QA 默认不执行。本地初始组合见本页工厂证据，完整发现、端侧业务计划和页面仍待接通，整体 1.3／6.4／6.6 不勾选；无新增产品取舍或通用规范。

### 预计事实操作叠加验证与收尾证据

比较基线／验证 HEAD：`816e950e`。`preview-overlay-red.log` 先复现共享叠加入口缺失；oldClosure、typeEffects 和操作循环移入共享规则。native 保留初始读取、事实工厂、时钟及资金角色值桥；putIfAbsent 的已有 null 值覆盖语义通过 common 显式 null 判断保留，removeIf 换为 common removeAll。只有原 CashRelationFailure 进入整条操作回退；其他程序异常不包装为跳过。

验证时间：2026-11-05 12:21 UTC。`preview-overlay-sqlite-platforms.log` 沿公共 Gradle 参数执行 :backend:test（原 CashImportPreviewSnapshotContractTest 全 12 方法、CashImportPreviewPreparationContractTest 全 1 方法／4 case）、领域 jvmTest／jsNodeTest／wasmJsNodeTest／testAndroidHostTest（各 CashImportPreviewOverlayTest、CashImportPreviewSnapshotTest、CashImportRelationDecisionRulesTest），并编译 iOS Simulator 测试／Arm64 主代码。`preview-overlay-postgres.log` 重跑同 2 个 backend 类。双库各 2 类／13 方法，四领域目标各 3 类／11 方法，均 0 fail／error／skip；iOS 只计编译。原完整事实、类型修改／整条回退、关系组／图应用、展示及全库只读断言保留。新 common 的关系与资金使用分开的合法初始模型，分别核对撤销和整条恢复；新建虚拟部分、null 来源默认值和程序异常同对象传播亦有回归。初次新 fixture 的可变 Map 泛型推断失败不计通过，旧测试／oracle 未修改。

SQLite 与 PG 使用既有 relation-choice-1105-preparation／candidate-query-1105-preparation-postgres 外部配置，PG 显式使用既有 FT_TEST_POSTGRES_URL／用户及隔离 schema；不探测或启动数据库。日志及 `<缓存>/preview-overlay-xml/` 保留当前矩阵。独立 5a 及实际独立 5b 无 finding，终态 XML 与文档范围已复核；该矩阵验证操作叠加；初始本地组合另见本页工厂证据。OpenSpec strict 与 diff 检查通过。已核对 design、tasks、Compose README、两级功能地图及 apply 指引，无新外部接口、依赖或配置，不需改 README 正文。

Cross-platform Impact Check：三端预计事实计算受影响，backend 调用同一规则；无 UI、路由或用户文案变化，本片无需页面冒烟，原生设备 QA 默认不执行。本地初始组合见本页工厂证据，完整发现、业务计划与页面仍未接通，1.3／6.4／6.6 不勾选；没有新增产品取舍或通用规范。

### 关系组图应用验证与收尾证据

比较基线／验证 HEAD：`54b379dc`。`relation-application-red.log` 先复现共享图应用入口缺失。CashImportRelationDecisionRules 迁移原恢复及应用规则，原生鉴权留在 restore 入口，成员只加载一次且仅选择恢复时读取；独立值在原有成员／事实存在检查之后才解码，不提前扫描无关成员。link／edgeMap／closures 为单一共享实现。原始测试／生成器不变。

验证时间：2026-11-05 12:10 UTC。`relation-application-sqlite-platforms.log` 使用既有公共 Gradle 参数执行 :backend:test（CashImportPreviewSnapshotContractTest 全 12 方法、CashImportPreviewPreparationContractTest 全 1 方法／4 case）、领域 jvmTest／jsNodeTest／wasmJsNodeTest／testAndroidHostTest（各 CashImportRelationDecisionRulesTest 与 CashImportPreviewSnapshotTest），并编译 iOS Simulator 测试／Arm64 主代码。`relation-application-postgres.log` 重跑同 2 个 backend 类。双库各 2 类／13 方法，四领域目标各 2 类／7 方法，均 0 fail／error／skip；iOS 只计编译。原 application 方法完整覆盖 14 个 accept／defer／ignore、inactive、联合／人工字段冲突、显式解冲突、自动／人工图失败、current unchanged 及拆分恢复／继承／必选 case，保留完整事实／关系／公共值／投影及全库只读断言。

SQLite 与 PG 分别沿原 relation-choice-1105-preparation／candidate-query-1105-preparation-postgres 配置，PG 显式设置既有 FT_TEST_POSTGRES_URL／用户与隔离 schema；不探测或启动数据库。完整日志及 `<缓存>/relation-application-xml/` 保存最终矩阵。新 fixture 初始错误地给支付镜像两端不同现金账户，原规则正确撤回；已仅改新 fixture 为同账户，并让非法显式决定回归断言准确 relation_endpoint_incompatible，未改旧 oracle。初次新测试泛型推断／fixture 失败不计通过。独立 5a 及实际独立 5b 无 finding，终态 XML、文档范围和 diff 已复核；OpenSpec strict 与 diff 检查通过。已核对 Compose README、两级功能地图及 apply 指引，本片没有新外部入口、依赖或配置，无需改 README 正文。

Cross-platform Impact Check：三端关系决定计算受影响，原 backend 入口复用；无 UI、路由或用户文案变化，本片无需页面冒烟，原生设备 QA 默认不执行。本地初始组合见本页工厂证据，端侧关系组生产配置、完整业务计划及页面仍待组装，1.3／6.4／6.6 不勾选。没有新增产品决策或通用规范。

### 预览状态组合验证与收尾证据

比较基线／验证 HEAD：`ca8bfafd`。`preview-state-red.log` 先复现共享状态入口缺失；原 data class 与 build 方法移入 domain，原生仅以 typealias 和平台值／构图桥保留调用。局部父项先筛原边，再由新增边按精确 ID 覆盖；草稿边标记待确认，资金按原预览最后记录选择，构图仅使用明列现金部分及其资金占用。构图异常原样传播，build 不写预览事实。新测试首次将 CashAmount.toString 误期望为固定小数文本，已改为核对原 representation；旧原生测试／oracle 未修改。

验证时间：2026-11-05 12:00 UTC。沿下方公共 Gradle 参数，`preview-state-sqlite-platforms.log` 执行 :backend:test（CashImportPreviewSnapshotContractTest 全 12 方法、CashImportPreviewPreparationContractTest 全 1 方法／4 case）、领域 jvmTest／jsNodeTest／wasmJsNodeTest／testAndroidHostTest（各 CashImportPreviewSnapshotTest 3 方法）及 iOS Simulator 测试编译／Arm64 主代码编译；`preview-state-postgres.log` 重跑同 2 个 backend 类。SQLite 与真实 PG 各 2 类／13 方法、四个领域目标各 1 类／3 方法，0 fail／error／skip，iOS 只计编译。原预计事实、关系组／选择／图应用、完整呈现、类型变更回退和全表只读断言均保留；新 common 验证局部新增边覆盖、资金最后记录／未列组成项过滤、草稿待确认及原异常同对象传播。

SQLite 配置指向仓库外 relation-choice-1105-preparation，PG 指向 candidate-query-1105-preparation-postgres，显式使用既有 FT_TEST_POSTGRES_URL／用户和 ft_candidate_prep_1105 schema，不探测／启动数据库。完整日志及 `<缓存>/preview-state-xml/` 保存本次矩阵；初次错误金额文本期望的 preview-state-green.log 不计最终通过。独立 5a 及实际独立 5b 契约审查无 finding，终态 XML、文档范围和 diff 已复核；OpenSpec strict 与 diff 检查通过。已核对 Compose README 的入口与现状引用，无新外部配置或依赖，不需改正文。Cross-platform Impact Check：三端共享预览状态受影响，backend 复用原调用；无 UI、路由或用户文案变化，无需本片页面冒烟，Node 单测不替代页面 QA，原生设备 QA 默认不执行。本地初始组合见本页工厂证据，加密缓存、最终写集、完整业务计划及页面仍待组装，整体 1.3／6.4／6.6 不勾选。本片迁移既有合同，没有新增用户产品取舍或通用规范。

### 本地关系上下文读取验证与收尾证据

比较基线／验证 HEAD：`5ad85b26`，2026-11-05 11:48 UTC。`local-relation-context-red.log` 先复现本地读取入口缺失。readLocalCashRelationContext 在一个完整工作区快照上沿确认且生效的双向端点扩展闭包，草稿只召回不扩展；显式决定组保留非生效历史成员。草稿／删除父的修订、组成项、资金和 entry 依赖仍保留，正式事实及资金占用只来自未删除 posted 父；公共条目值不覆盖来源事实，也不处理无关流水的财务字段。金额及来源键沿原原语读取，未知程序错不伪装成字段问题，入口无 IO／写入。

`local-relation-context-final-valid-sqlite-platforms.log` 沿公共 Gradle 参数执行 :backend:test（LocalCashRelationContextParityTest、CashImportPreviewPreparationContractTest、CashImportPreviewSnapshotContractTest），3 类／16 方法通过；领域 jvmTest／jsNodeTest／wasmJsNodeTest／testAndroidHostTest 各选择 LocalCashRelationContextTest 与 CashRelationReadContextTest，各 2 类／7 方法，0 fail／error／skip。iOS Simulator 测试代码及 Arm64 主代码编译通过，不声称设备运行。`local-relation-context-final-valid-postgres.log` 以同参数选择同3个 backend 类，在真实 PG 同样 3 类／16 方法通过，0 skip；日志／XML 在既有仓库外 `<缓存>/local-relation-context-final-*`。

原 SQL 读取与本地完整上下文在 7 个真实 seed 范围（含草稿、资金、多现金部分及全量）逐项比较：除平台投影标识／时刻的明确桥接外，所有上下文字段、19 个事实字段和完整 token 相同，并比较全库只读不变。另在 Database.read 的既有自动回滚事务内临时配置含非生效成员的正向决定组，比较完整模型／事实／token，退出后核对全库不变。第三端锚点单独命中不适用：既有 ck_cash_relation_anchor 强制锚点属于 primary／secondary；没有绕过约束或保留不合法 setup，生产锚点筛选仍沿原 SQL。storedPlain 的负零、巨大零指数、精确小数及原样无效文本以真实 fixedCashDecimal 对照。

common 回归验证确认闭包不扩草稿边、不处理无关坏事实、删除／草稿元数据、公共值不覆盖来源值、决定组历史、输入错误次序、多现金部分资金按 ID 取首项及超过 JS 安全整数的修订／投资事件 ID。非法 UTF-16 在运行时构造；JS 编译曾复现仅 JVM 可用的 putIfAbsent，已改保留首值的 common Map 写法后重跑最终矩阵，不将前次部分通过当作最终结果。

SQLite 的 FT_KOTLIN_IMPORT_PREPARATION_CONTRACT_DIR 指向 relation-choice-1105-preparation，PG 指向 candidate-query-1105-preparation-postgres，并显式设置既有 FT_TEST_POSTGRES_URL／用户；不自动探测、启动或创建数据库，不改变 public。独立 5a 的跨端 API、宽捕获、测试 surrogate 和文档边界 finding 已修复重审；实际独立 5b 无新增契约 finding。OpenSpec strict 与 diff 检查通过。

Cross-platform Impact Check：三端本地导入读取受影响，backend 原读取器保持；无 UI、路由或用户文案变化，Node 单测不替代页面冒烟，原生设备 QA 默认不执行。已核对 design、tasks、Compose README、两级功能地图及 apply 指引；README 无新增外部使用／部署变化，入口及未完成边界由功能地图引用。没有新增用户决策或通用政策，无需新增规范。完整预计事实／关系组、加密缓存、应用及页面仍待接线，整体 6.4／6.6 不勾选。

### 关系上下文模型验证与收尾证据

比较基线／验证 HEAD：`a7d4c264`，2026-11-05 11:31 UTC。`relation-read-context-red.log` 先复现共享上下文／版本记录／token 入口缺失。CashRelationReadContext 及组成项、问题、资金、entry 的精确只读版本记录移入 domain；backend 以 typealias 保留原调用点，SQL、权限、闭包、条目 flush 和所有资格过滤未改。I/T 仅隔离原生投影值，未新增网络／持久化模型。token 继续使用原 9 组依赖及顺序，不混入种子、选择或派生事实。

`relation-read-context-green.log` 沿公共 Gradle 参数执行 :business-domain:jvmTest --tests '*CashRelationReadContextTest*'，1 类／3 方法通过；固定 Python 标准 ASCII 紧凑 JSON 的 SHA 字面量覆盖全 9 组、补充平面／控制字符及超过 JS 安全整数的 Long，另验证每类依赖改变使 token 失效、非依赖运行状态不影响 token。`relation-read-context-postgres-platforms.log` 执行领域 jsNodeTest／wasmJsNodeTest／testAndroidHostTest 同一类，各 1 类／3 方法，0 fail／error／skip；iOS Simulator 测试代码及 Arm64 主代码编译通过，不声称设备运行。

`relation-read-context-sqlite.log` 与 `relation-read-context-postgres-platforms.log` 执行 :backend:test，选择 CashRelationReadContextParityTest、CashImportPreviewPreparationContractTest、CashImportPreviewSnapshotContractTest，双库各 3 类／14 方法，0 fail／error／skip。原 4 个准备 case 及完整预览 12 方法原样保留，并比较全表只读不变；新差分以未修改 receiptAsciiJson 对照全部 65,536 个 UTF-16 单元及补充平面混合串，验证原上下文字符串转义。数据库与前片使用相同显式路径：SQLite relation-choice-1105-preparation，PG candidate-query-1105-preparation-postgres，后者设置已有 FT_TEST_POSTGRES_URL／用户；不探测或启动数据库。当前 XML 在仓库外 `<缓存>/relation-read-context-xml/`。

独立 5a 及实际独立 5b 无 finding，OpenSpec strict 与 diff 检查通过。Cross-platform Impact Check：三端共享模型及摘要受影响；无 UI、路由或用户文案变化，Node 单测不代替页面冒烟，原生设备 QA 默认不执行。核对 design、tasks、两级功能地图与 apply 指引，本片模型／摘要不替代本地读取的独立验证；完整预览及页面尚未实现。本片只是既有模型责任归属调整，没有新增用户决策或通用规则，整体 6.4／6.6 仍未完成。

### 计算值预览摘要验证与收尾证据

比较基线／验证 HEAD：`f2563ce4`，2026-11-05 11:18 UTC。`import-digest-red.log` 先复现本地摘要／虚拟标识入口缺失。cashImportComputedDigest 复用原计算值递归转换，native 保留 ParsedImportCodec.timeText 和 sourceDigest 的开放值桥；端侧 localCashImportDigest 使用同一 Decimal 表示及 UTC 微秒文本，公共字段与关系选择的实际本地入口复用它。虚拟标识仍为来源摘要前 128 位的无符号整数加 2^64；SnapshotBuilder 重复算法改为委托。没有把 JSON 金额字符串或日期字符串猜回计算类型，也没有改变持久身份、原 generation 范围或完整预览边界。

`import-digest-final-sqlite-platforms.log` 使用公共 Gradle 参数执行 :backend:test（CashImportComputedDigestParityTest、CashImportPreviewSnapshotContractTest、CashImportEntryChoiceTest）、领域 jvmTest／jsNodeTest／wasmJsNodeTest／testAndroidHostTest（各选择 CashImportDigestTest、CashImportEntryChoiceRulesTest、CashImportRelationChoiceRulesTest），并编译 iOS Simulator 测试代码及 Arm64 主代码。backend 3 类／17 方法；四个领域任务各 3 类／10 方法，0 fail／error／skip；iOS 只计编译。`import-digest-final-postgres.log` 以同参数重跑同三个 backend 类，3 类／17 方法通过、0 skip。原完整预览 12 方法全部保留，覆盖预计事实、分组／候选、计算摘要、选择失效、图应用／拆分恢复、展示和类型修改回退；未将测试缩成只看 helper。

原生差分使用独立 BigDecimal 字符串与负零符号、原时刻 codec、原 BigInteger unsigned hex 转换，覆盖多种有效尺度、UTC 日期边界／纳秒截断及 1,000 个不同完整虚拟 ID。common 的固定 SHA／ID 字面量以 Python 标准 json.dumps(sort_keys=True, ensure_ascii=False, separators=(',', ':'))／hashlib.sha256 及整数运算计算，另验证全零／全 f 的 128 位边界；不依赖被测转换生成期望。全部旧 golden 原样保留，原选择完整结果继续回放。

外部合成成本探针源码、Gradle init、日志与 XML 在既有仓库外缓存的 import-digest-probe-*／import-digest-cost-*；探针只注入当前 backend test 源集，不进入产品或提交。1000 次预热后三轮各 10,000 个真实来源摘要及虚拟 ID，逐项对比原结果。当前固定 39 位十进制进位转换测得共享 30／32／29 ms，原 BigInteger 16／12／13 ms；该机器一次观测不是跨设备 SLO 或完整导入耗时。转换仅需固定小整数数组，复用现有无符号加法，未新增通用整数框架或依赖。

SQLite 的 FT_KOTLIN_IMPORT_PREPARATION_CONTRACT_DIR 使用 relation-choice-1105-preparation，PG 使用 candidate-query-1105-preparation-postgres（已授权测试库内手动准备的隔离 schema）；PG 显式 FT_TEST_POSTGRES_URL／用户，测试不探测／启动数据库，不删除 public。最终 XML 在 `<缓存>/import-digest-final-xml/`，成本 XML 单独保留。独立 5a 及实际独立 5b 无阻断，热路径成本已实际观测后改用有界转换并重审；OpenSpec strict 与 diff 检查通过。

Cross-platform Impact Check：三端计算值／选择摘要受影响，原 backend 复用；没有 UI、路由或用户文案变化，Node 不冒充页面 QA，原生设备 QA 默认不执行。已核对 design、tasks、两级功能地图和 apply 指引，仍明确端侧完整预计事实／关系组与页面未完成。本片没有新用户选择或通用政策，无需沉淀新规范；整体 6.4／6.6 不勾选。

### 候选查询规划验证与收尾证据

比较基线／验证 HEAD：`1818da04`，2026-11-05 11:06 UTC。`candidate-query-red.log` 先复现本地查询入口缺失；窗口合并、候选类型、负消费门、按来源／范围／现金账户分组的硬键请求移入 CashImportCandidateQueryRules。backend 保留工作区资格、SQL JSON 取键、来源分类及实际读取；本地入口只生成查询条件，不代表已完成快照候选执行或完整预览。现有测试与 oracle 均未修改。

`candidate-query-whitespace.log` 沿公共 Gradle 参数执行领域 jvmTest、jsNodeTest、wasmJsNodeTest，各选择 `*CashImportCandidateQueryTest*`；`candidate-query-final-sqlite.log` 同参数执行 testAndroidHostTest 同类及 iOS Simulator 测试代码／Arm64 主代码编译。四个测试任务各 1 类／4 方法，0 fail／error／skip；验证相接窗口合并、负零、类型门、超过 JS 安全整数的现金账户、来源行 null 范围、显式 null 交易号、空账号请求、元数据存在性、Alipay 前缀及 Unicode 空白边界；iOS 为编译，不声称运行。

`candidate-query-final-{sqlite,postgres}.log` 执行 :backend:test，选择 CashImportCandidateContractTest 和 CashImportPreviewPreparationContractTest，各 2 类／2 方法，0 fail／error／skip。原候选 exporter 生成 14 个跨窗口、硬键、来源范围、现金账户、工作区及百项批次边界 case；原预览准备 exporter 的 4 个完整 case 保持操作、候选、保留父节点、上下文与令牌，并比较全表只读不变。SQLite 使用新的绝对测试文件；PG 在已授权 ft_offline_ledger_1104_test 中手动准备 ft_candidate_query_1105／ft_candidate_prep_1105 schema，显式 search_path／currentSchema，不删除 public，测试不探测／启动数据库。两个 FT_KOTLIN_IMPORT_*_CONTRACT_DIR 指向仓库外对应 candidate-query-1105-* 目录，PG 显式设置现有 FT_TEST_POSTGRES_URL 和用户。日志与 XML 位于既有仓库外缓存目录 candidate-query-*。

独立 5a 及实际独立 5b 无阻断；非阻断 P3 已采用，backend 复用已展开交易号集合，不重复分配前缀。Cross-platform Impact Check：三端查询规划受影响，原 backend 调用同一规则；没有 UI、路由或文案变化，Node 测试不代替页面冒烟，原生设备 QA 按默认范围不执行。已核对总／导入功能地图、design、tasks 和 apply 指引，保留页面仍走服务端的事实。无新增用户决策或通用规范；完整端侧候选读取、预览、缓存和页面仍待完成。OpenSpec strict 与 diff 检查通过。

### 关系选择失效核心验证与收尾证据

比较基线／验证 HEAD：`71bc96a2`，2026-11-05 10:53 UTC。`relation-choice-common-red.log` 先复现本地入口缺失。仅将 applyChoices 纯段移入共享 CashImportRelationChoiceRules，backend 保留原 BigInteger／copy／importDigest 值桥；拆分恢复与图应用另由上述共享规则及独立矩阵验证，完整预览仍待接线。JSON 入口复制当前组后处理，原 choices／previous 不变。generation 在本片核心用精确整数运算和裸 JSON 数字，不能当金额／ledger_seq；2^80 和 2,000 位只证明原 BigInteger 槽与摘要／进位保真，不更改上游 CashImportChoiceContext.validated 的非负且严格小于 Long.MAX_VALUE 范围。

未修改的 export_cash_import_preview_preparation_contracts.py 对新绝对 SQLite 路径生成原完整语料；新 18 个 relation-choice-cases 与已有 record-writer-sqlite/preparation 语料完整相同，再将原输出保存为 backend 固定 oracle，由既有 common 生成器的 relation-choices 类型嵌入，`--kind all --check` 通过。`relation-choice-first-green.log` 的领域 jvmTest 为 1 类／3 方法，完整比较全部 18 成功／失败结果及输入不变；另覆盖新显式选择与旧失效决定的四种顺序、巨大 generation。`relation-choice-platforms.log` 执行领域 jsNodeTest、wasmJsNodeTest、testAndroidHostTest（各 `--tests '*CashImportRelationChoiceRulesTest*'`），各 1 类／3 方法，0 fail／error／skip；iOS Simulator 测试代码及 Arm64 主代码编译通过，不声称设备运行。

`relation-choice-final-{sqlite,postgres}.log` 沿公共 Gradle 参数执行 `:backend:test`，选择 LocalCashRelationChoiceParityTest、RemainingSourceBoundaryTest、CashImportPreviewSnapshotContractTest.frozenRelationChoicesAndInvalidationMatchPython 及 CashImportOriginalBusinessTest 的 explicitCurrentChoiceWinsRetainedInvalidatedChoiceInAllFourOrders／nonzeroGenerationSurvivesFileInvalidationAndBlocksFingerprintRevival，双库各 4 类／17 方法，全部 0 fail／error／skip。覆盖全部 65,536 个 BMP／UTF-16 字符的原 BigInteger 入口、正负／带号 Nd 字符／超 Long／巨大计数完整组哈希与进位对照；真实 builder 连续失效、文件失效保留上下文、旧指纹返回不复活及新显式决定优先。原 frozen-case 方法仍完整运行 18 案例，未缩减为聚焦子集。

原业务 exporter 分别准备 open 和四个 open_{accept,ignore}_{true,false} 独立合成场景；SQLite 新文件，PG 在已授权的 ft_offline_ledger_1104_test 内手动创建 ft_relchoice_1105_* schema 并显式配置 search_path／currentSchema，不删除 public，测试不探测或启动数据库。FT_KOTLIN_IMPORT_ORIGINAL_BUSINESS_CONTRACT_DIR 指向对应 relation-choice-1105-business-sqlite／postgres，FT_KOTLIN_IMPORT_PREPARATION_CONTRACT_DIR 仅为原只读 frozen JSON 语料；PG 设置已有 FT_TEST_POSTGRES_URL／用户。XML 在仓库外 `<缓存>/relation-choice-final-xml/`。

独立 5a／实际独立 5b 无新增阻断；指出的大数证明范围已在 design、tasks 和 apply 指引明确限定纯核心，未顺改旧 validator。Cross-platform Impact Check：三端共享同一选择规则，原 backend 调用点复用；无 UI、路由或用户文案变化，Node 单测不冒充页面冒烟，原生设备 QA 默认不执行。已核对模块／总功能地图、设计、任务、原生成器与新资源，OpenSpec strict 和 diff 检查通过。保留既有用户决定的优先级和失效语义，无新增产品政策或通用规范；选择失效片不替代图应用的独立验证，完整预览、缓存及页面仍未完成，不能勾选整体 6.4／6.6。

### 核对公共字段选择验证与收尾证据

比较基线／验证 HEAD：`ec99d059`，2026-11-05 10:37 UTC。`entry-choice-common-red.log` 先复现共享入口缺失。原目标索引、完整依赖、稳定 edit_ref 和选择应用逐方法移入 CashImportEntryChoiceRules，backend 仅保留原 BigInteger 边排序及 CashRecordPolicy／importDigest 薄桥，旧测试和期望未改。新本地 JSON 入口深复制完整结果后应用原决定，失败不修改原计划；不接入完整预览或页面，不更改清空／长期保护政策。原黄金由现有 export_common_projection_vectors.py 的 entry-choices 单源生成，--check 一致；没有手造第二份期望。

`entry-choice-first-green.log` 的 `:business-domain:jvmTest --tests '*CashImportEntryChoiceRulesTest*'` 共 1 类／3 方法，完整比较原 target、single、applied 及失效／分类／禁用字段拒绝；原 backend CashImportEntryChoiceTest 2 方法继续验证计算型金额的摘要与数值边排序。`entry-choice-platforms.log` 同一公共 Gradle 参数执行领域 jsNodeTest、wasmJsNodeTest、testAndroidHostTest（各同一筛选），各 1 类／3 方法，0 fail／error／skip；iOS Simulator 测试代码及 Arm64 主代码编译通过。Node 单测不是 Web 页面冒烟或 iOS 运行证明。

`entry-choice-final-{sqlite,postgres}.log` 各以 `:backend:test --tests '*CashImportEntryChoiceTest*' --tests '*CashEntryChoicesOriginalTest*'` 执行 2 类／14 方法，全部 0 fail／error／skip。包含原新建／既有混合条目、重复无目标、公共清空重预览后真实提交、来源决定、依赖变化整体回滚、分类资格及 HTTP 编辑／候选能力。先用未修改的 export_cash_entry_choice_inputs.py 准备 14 份独立合成输入；SQLite 使用不同新文件，PG 在已授权且显式设置 FT_TEST_POSTGRES_URL 的 ft_offline_ledger_1104_test 内人工创建 ft_entry_1105_* 隔离 schema，以 options/search_path 导入并在外部 JDBC 配置显式 currentSchema，不删除 public 既有测试夹具，不自动启动数据库。FT_KOTLIN_ENTRY_CHOICE_DIR 分别指向外部 entry-choice-1105-sqlite／postgres，PG 用户保持既有设置；实际结果 XML 保存于 `<缓存>/entry-choice-final-xml/`。

独立 5a／实际独立 5b 逐方法及契约复核无新增 finding。未覆盖的完整端侧目标构建、计算型金额／时刻 importDigest 生产桥及完整核对预览保持明确待办，不以本片规范 JSON 回放替代。Cross-platform Impact Check：三端复用同一规则和本地 JSON 入口，backend 调用点复用原宿主值桥；无页面、交互、文案或持久化形状变化，原生设备 QA 默认不执行。本轮已核对 design、tasks、模块／总功能地图、生成器、原测试及 apply 指引；OpenSpec strict 和 diff 检查通过，无新依赖或配置。迁移既有合同，不新增产品决策或通用规范；仅内部规则与薄桥，可按小步提交回退，不需要数据库回滚。

### 本地浏览 owner 验证与收尾证据

比较基线／验证 HEAD：`12f862e9`，2026-11-05 10:19 UTC。新增内部 owner 固定单一 LedgerCacheScope，组合已有同步与真实浏览读取器；每次分页先捕获 committed 缓存，才按事实版本与规则声明复用派生读取器。`local-ledger-owner-red.log` 先复现缺失 owner；空账本计数使用原序号分页 `startIndex=0` 合同，不把 keyset 页的合法 null 计数改成零。独立 5a／实际独立 5b 发现 P1：owner 单独保存的 closed 状态未跟随同步层撤权，已捕获的页面可在清理后返回。`local-ledger-owner-auth-red.log` 用真实双线程／实际列表派生消费后的屏障复现旧实现返回成功，改为共享同步层唯一围栏后转绿，重审无遗留 finding；普通网络、存储失败与取消仍保留旧事实可读。

`local-ledger-owner-final-platforms.log` 沿公共 Gradle 参数和既有 Chrome init 依次执行 `:shared:testAndroidHostTest --tests '*LocalCashLedger*' --tests '*LedgerSnapshotSyncTest*'`、`:shared:jsBrowserTest --tests '*LocalCashLedger*'`、`:shared:wasmJsBrowserTest --tests '*LocalCashLedger*'`、`:shared:compileTestKotlinIosSimulatorArm64` 与 `:shared:compileKotlinIosArm64`。Android host 4 类／18 方法，Chrome JS／Wasm 各 2 类／8 方法，全部 0 fail／error／skip；iOS 仅编译通过。线程回归完整运行 401／403 × 清理成功／失败四组合，在同一真实 reader 已取得快照并开始派生时阻塞，另一线程进入实际权限清理后再放行，不使用假的结果或提前关闭代替撤权。共同测试覆盖无缓存与空账本、离线分页、版本未变零下载、新版本真实金额／完整月度合计及旧游标拒绝、失败保旧、清理重试、关闭时存储等待和旧登录迟到失败不清新 owner。XML 在仓库外 `<缓存>/local-ledger-owner-final-xml/`。

本片不修改服务存储，无需重跑双库；不引入库或运行配置。Cross-platform Impact Check：三端共用 owner 与读取核心；没有页面／用户文案／交互变化，浏览器内部测试不冒充页面 QA，原生设备 QA 默认不执行。核对 design、tasks、模块／总功能地图及 apply 指引，保持实际加密驱动、应用登录生命周期、规则更新的应用重开与页面尚未接通的现状，不勾选整体 5.x／7.2。用户本轮无新增产品决策；范围持有与唯一围栏属于既有隔离／安全合同的实现，不新增通用政策。OpenSpec strict 与 diff 检查通过，当前小步仅内部组合，可独立回退而不改变数据库或既有页面。

### 永久收据版本验证与收尾证据

比较基线／验证 HEAD：`6743bb09`，2026-11-05 10:07 UTC。`ledger-receipt-red.log` 先复现写摘要拒绝新增版本；shared 缺字段编译与 OpenAPI 缺元数据亦先失败。独立 5a／实际独立 5b 契约审查发现显式 null 被当缺字段的 P2，`ledger-receipt-null-red.log` 在真实 DTO／resolve 解码回归失败，字段级 serializer 修复后转绿并重审关闭。该 serializer 不改全局 JSON；字段缺省且 encodeDefaults／explicitNulls 均打开时仍不输出 ledger_seq。

沿用下方公共 Gradle 参数，`ledger-receipt-final-{sqlite,postgres}.log` 执行 `:backend:test`，选择 LedgerReceiptContractTest、ReceiptWriteGoldenTest、ReceiptStoreMigrationTest、ReceiptReadMigrationTest、ReceiptProtocolMigrationTest、OpenApiContractTest，各 6 类／43 方法，均 0 fail／error／skip。原写摘要 golden 保留全部 1,052 个样本。双库配置继续为显式 `<缓存>/sqlite`／`postgres-5432`，PG 使用已获授权准备的 `ft_offline_ledger_1104_test`、既有 FT_TEST_POSTGRES_URL／用户，测试未启动数据库。新测试包含真实持久化、原键重放及 HTTP 按原键／ID 返回完整同一收据，覆盖 0、超过 JS 安全整数及 Long.MAX_VALUE；旧收据不补版本。非法类型／词法／范围不能写入，损坏持久值停止读取且不回显私有内容，不以当前账本版本掩盖。

`ledger-receipt-null-green.log` 的 shared Android host 4 方法通过；`ledger-receipt-final-platforms.log` 的 Chrome JS／Wasm 各 1 类／4 方法，全部 0 fail／error／skip，iOS Simulator 测试代码及 Arm64 主代码编译通过。Browser 任务使用单一 `--tests '*LedgerReceiptMappingTest*'` 与既有 Chrome Headless init 配置，fresh XML 分别为 10:05:03／10:05:39 UTC；按实际带任务名前缀的 XML 核对，不以文件名猜测是否执行。XML 保存于仓库外 `<缓存>/ledger-receipt-final-xml/`。OpenAPI 生成器 --check 核对实际 90 operations／78 paths，OpenSpec strict 与 diff 检查通过。

Cross-platform Impact Check：contracts 字段与 shared 映射影响三端协议解码，无页面、交互或文案变化；浏览器内部测试不等于页面冒烟，不声称 iOS／Android 设备运行。已核对 backend README、功能地图、design、delta、tasks、OpenAPI 和 apply 指引；没有新领域名词或部署配置。仅允许永久收据携带精确原提交版本，最终写集 service 必须在事务中填写真实版本，提交／原键核实接线仍待完成，不勾选整体 3.6。该规则源自已确认的账本版本及结果未知核实合同，无需另增通用产品规范。

### 本地账本查询验证与收尾证据

比较基线／HEAD：`155d257b`；当前工作树验证时间为 2026-11-05 09:34 UTC。查询与输入缺失先在 `local-ledger-query-red.log`／`local-ledger-input-red.log` 复现；外部修改筛选 Map 的回归在 `local-ledger-input-isolation-red.log` 失败，再由固定内部快照转绿。金额范围回归先以安全的小输入 `1e20` 失败，修复后覆盖正负极端 Int 指数、20 位整数＋18 位小数边界、负零、尾随零与预算内精确文本；红灯不执行无界 2 GB 分配。旧关系 fixture 中账户／来源身份冲突保留明确拒绝断言，不通过放宽图规则使其通过。

正式命令共用 `NODE_ENV=development ./compose/gradlew -p compose --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx6144M -Dfile.encoding=UTF-8'`。`local-ledger-query-final-platforms.log` 的领域任务为 `:business-domain:jvmTest`、`jsNodeTest`、`wasmJsNodeTest`、`testAndroidHostTest`，选择 LocalCashLedgerInput／Query、LocalCashProjection、CommonCashProjectionGolden、CashLedgerCursor 与 CashProjectionMonthlyRules 测试；实际 XML 分别为 6 类 35 方法、2 类 16 方法、6 类 35 方法、6 类 35 方法。JS Node 的实际选择仅原始输入／查询 2 类，不声称执行其余 4 类。Android shared 的 `testAndroidHostTest --tests '*CashLedger*' --tests '*FinanceModelMapperTest'` 共 6 类 36 方法。上述结果均 0 fail／error／skip，iOS Simulator 测试代码及 Arm64 主代码编译通过，不宣称 iOS 运行时通过。

Chrome 任务另加 `CHROME_BIN='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'` 与仓库外 `-I <缓存>/shared-browser-unit.init.gradle`；该文件只对 jsBrowserTest／wasmJsBrowserTest 配置 `useKarma { useChromeHeadless() }`。领域两浏览器任务各用单一 `--tests '*LocalCashLedger*'`，各 2 类 16 方法；shared 以单一 `--tests '*CashLedger*'` 重跑，`local-ledger-reader-final-browser.log` 各 5 类 30 方法，含真实 reader 2、日期范围 5、月份 4、展示 14、ViewModel 5，全部 0 fail／error／skip。reader 验证真实纽约／UTC 日期差异、未知日期和游标范围。多个筛选模式的一轮产生空／跳过结果及无响应，已停止且不计通过；正式结果以新时间戳 XML 为准。完整所选金额的合计仍可超过单笔列宽，不将新筛选输入限制套到统计。

`local-ledger-query-final-{sqlite,postgres}.log` 显式使用 `FT_KOTLIN_CONTRACT_DIR=<缓存>/sqlite`／`postgres-5432`，执行 `:backend:test --tests '*CashProjectionSourceMigrationTest' --tests '*LocalCashLedgerInputParityTest'`，各 2 类 27 方法，0 fail／error／skip。用户授权启动既有 PG 18 实例并在缺失时新建 `ft_offline_ledger_1104_test`，仅以现有工具导入合成夹具和迁移；设置 `FT_TEST_POSTGRES_URL=postgresql+psycopg://huangwenlong@127.0.0.1:5432/ft_offline_ledger_1104_test` 与 `FT_KOTLIN_TEST_DB_USER=huangwenlong`，测试未自动启动／切换数据库。完整 page 在 13 组筛选下逐页比对所有字段，原生／本地分别使用真实游标续页；仅替换已确认的 ledger_seq 版本及游标协议文本，并比较是否存在后续页。保留原输入语料的 >300 案例；仅对获批金额范围差异用独立 BigDecimal precision／scale 判断预期，范围内及其他字段仍与原 parser 完整比较。全部 1,114,112 个标量的原币种字母资格及 ROOT uppercase 经实际 public 工厂对照；冻结资格生成器逐字复生成一致。

原始日志和逐任务 XML 位于仓库外 `<缓存>/local-ledger-query-final-xml/`。Cross-platform Impact Check：共享读取用于 Web／Android／iOS；现有页面、文案和交互未改，浏览器内部测试不冒充产品页面冒烟，原生设备 QA 默认不执行。文档同组核对 proposal、delta、design、功能地图与 apply skill；原接口仍在使用，缓存 owner／应用生命周期／页面尚未接通，不宣称离线账本已可用。用户确定的 20／18 位仅为现金账本筛选参数规则，保留在本 change，不推广为通用金额或汇总限制；无需另增产品编码规范或术语。

### 账本关键词验证与收尾证据

验证时间：2026-11-05 07:10 UTC；工作树 HEAD／比较基线为 `b257ffe7`。用户已明确选择普通文字包含且忽略大小写；proposal／浏览 delta／design／tasks 先回写，未提前更改主规格或旧服务端查询。`ledger-keyword-red.log` 在测试声明修正后复现缺失 `cashLedgerKeywordMatches`；实现复用已有冻结 Unicode 14 casefold 和原查询两端空白合同，不引入依赖、SQL、NFC／去重音或商户匹配变更。

`ledger-keyword-platforms.log` 用下方公共 Gradle 参数执行 `:business-domain:jvmTest`、`:business-domain:jsNodeTest`、`:business-domain:wasmJsNodeTest`、`:business-domain:testAndroidHostTest`（每个任务 `--tests '*CashLedgerKeywordTest*'`），随后 `:business-domain:compileKotlinIosSimulatorArm64 --console=plain`。四端测试各 1 类／3 方法，0 失败／错误／skip，iOS 编译通过；固定正反例覆盖 ASCII、Unicode／补充平面、两字段不拼接、%／_／反斜杠字面、两端及中部空白。最新 XML 为既有仓库外缓存的 `ledger-keyword-current-xml/business-domain/<任务>/`。新谓词没有平台 API 或页面调用，不另做 Web 页面冒烟；Node 测试不冒充页面或 iOS 设备 QA，不另跑数据库矩阵。

独立 5a 与 5b 契约审查无本片 finding；reviewer 不修改实现、不运行构建，UI 专项不适用。OpenSpec strict 与 diff 检查通过。新行为仅在当前 change 的端侧浏览合同与纯谓词落地；日期范围另见下方独立验证；完整筛选／分页、缓存与页面仍未接通，7.2 不勾选。当前页面仍使用待退役服务端读取，文档与地图不把纯函数写成页面已可用。用户选择的适用边界是账本关键词，保留于本业务规格，不扩为导入匹配或所有搜索的通用规则。

### 浏览时区日期范围验证与收尾证据

验证时间：2026-11-05 08:10 UTC（资源复验 08:17 UTC）；当前验证 HEAD `6332d96e`，比较基线 `4fbeb8f8`。日期算法、依赖及测试源码与最后矩阵相同；后续仅规范许可文本行尾空白，资源按新字节重新构建验证。`ledger-date-bounds-red.log` 先复现缺失入口。`localCashLedgerDateBounds` 保留原日期归一、逆序及结束日 9999-12-31 的错误次序，显式时区按首个有效时刻计算；UTC 结果可进入公元 0 年，不因此修改来源日期范围。共同名称表由固定 Temurin 21.0.6+7-LTS 的生成器产生并逐字复生成核对，独立审查发现的 Intl／iOS 接受额外时区写法已由 common 精确名称 gate 修复；不是时区转换数据库。

`ledger-date-bounds-current-platforms.log` 用下方公共 Gradle 参数及 `CHROME_BIN=/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`、`NODE_ENV=development` 执行 `:shared:testAndroidHostTest`、`:shared:jsBrowserTest`、`:shared:wasmJsBrowserTest`（每个任务同时筛选 `*CashLedgerDateBoundsTest*`／`*CashLedgerCalendarTest*`），随后 `:shared:compileTestKotlinIosSimulatorArm64`、`:androidApp:assembleDebug`、`:webApp:jsBrowserDevelopmentWebpack`、`:webApp:wasmJsBrowserDevelopmentWebpack`。各测试 2 类／9 方法，全部通过、0 error／skip；编译及打包通过。范围测试覆盖全部 603 名称的平台资格、16 个独立 JDK 起日结果、23／25 小时日、午夜缺失／重叠／整日跳过、旧固定名／别名及无效输入。XML 为仓库外 `ledger-date-bounds-current-xml/shared/<任务>/`。第一次平台运行的 JS 进程未给出可信测试结果，停止后以当前显式 Chrome 运行和 XML 为准，不计旧结果通过。

Cross-platform Impact Check：共享范围合同影响 Web／Android／iOS 的本地查询能力；本片尚无页面调用、UI 文案或交互变化，因此不声称完成页面冒烟，原生设备交互按默认范围不执行；iOS 只做测试代码编译，不声称运行通过。只读客户端原语无存储或服务写入变化，不重复双库矩阵。Android 实际 APK 已执行 desugaring，随包 calendar notices 与源资源逐字相等；`ledger-date-bounds-resolved-platforms.log` 在直接版本对齐后执行 iOS Simulator 测试代码及 iOS Arm64 代码编译、Android APK 与 Web 两套完整开发分发，全部通过；`ledger-date-bounds-distribution.log`／补跑日志确认实际 iOS 版本及 Android desugar 2.1.5。当前 Android APK、JS／Wasm 完整开发分发与 iOS Simulator 生成资源中的 notices 均与源文件逐字相等，规范行尾空白后的 `ledger-date-bounds-notice-resources.log` 补跑通过，当前路径／APK 摘要记在 `ledger-date-bounds-resource-check.txt`；不把 iOS 资源编译当作完整 Xcode 安装包或实际运行。精确 desugared-library 对应源码的二进制分发准备在 11.4 收尾门禁处理，当前未发布应用。正式 iOS 依赖由既有 Ktor 选中 0.8.0-0.6.x-compat，直接声明与实际版本对齐，不以强制纯 0.8.0 降级既有调用；范围桥只使用 kotlin.time.Instant。

独立 5a 及实际 5b 契约审查无本片剩余 finding，原时区资格 P2 已重审关闭。OpenSpec strict 与 diff 检查通过。完整本地筛选／分页与缓存／页面仍未接入，不勾选 7.2。新增库许可、版本与可复现输入目录已写入 design／Compose README／资源，功能地图与 apply 指引同步；不新增产品语义或通用规则。

### 浏览日期归一验证与收尾证据

验证时间：2026-11-05 07:00 UTC；工作树 HEAD／比较基线为 `12885224`。`ledger-filter-date-red.log` 先复现共享入口缺失。`ledger-filter-date-matrix.log` 使用下方公共 Gradle 参数执行 `:business-domain:jvmTest --tests '*CashLedgerFilterDateTest*' :backend:test --tests '*CashLedgerFilterDateParityTest*' --console=plain`，各 1 类／2 方法，全部通过、0 error／skip。原 native formatter 对比 0..400 与 1582／1900／2000／2026／9998／9999 年，包含周号 0..54、日序 0..8、扩展／紧凑／缺省周一与日历边界，共 527,472 个完整值／拒绝结果；独立 oracle 不委托共享解析。初写测试把 2026-W53 误作非法，原结果为 2026-12-28；已补该固定正例，非法周用 2025-W53，未修改原测试或 golden。

`ledger-filter-date-platforms.log` 以同样公共参数依次执行 `:business-domain:jsNodeTest`、`:business-domain:wasmJsNodeTest`、`:business-domain:testAndroidHostTest`（各 `--tests '*CashLedgerFilterDateTest*'`）、`:business-domain:compileKotlinIosSimulatorArm64`；三个测试各 1 类／2 方法，0 失败／错误／skip，iOS 编译通过。日志与 `ledger-filter-date-current-xml/<模块>/<任务>/` 均在既有仓库外缓存目录。仅日期归一，无平台 API、持久化或页面变化；不另做双库业务矩阵／页面冒烟或宣称 iOS 设备 QA，本地完整时区日期范围另见下方独立验证，完整查询仍待实现。

5a 独立审查无 finding，5b 范围判断不触发专项。核对 design、tasks、两级功能地图与 apply 指引，复用已有日期术语；没有新增用户选择或通用规则，日期格式政策保持原样。OpenSpec strict 与 diff 检查通过；日期原语不改变关键词政策；整个查询和改造仍未完成。

### 解析验证与收尾证据

#### 单文件分派

验证时间：2026-11-05 06:50 UTC；工作树 HEAD／比较基线为 `6c21fbcd`。共享 `cashStatementFormat` 与 backend 复用原字节／来源优先级；`parseLocalCashStatement` 将本地 CSV／值型 XLSX／工银亚洲文本和 PDF／XLS parse-only 组合为一次文件读取。`statement-dispatch-red.log` 与 `statement-reader-red.log` 先复现两个缺失入口。读取器测试 fixture 的 XLSX 来源使用原 case 11 的 `wechat`；首次错误传成 `alipay` 时在测试期望读取处失败，未更改任何原 oracle。Ktor 可复制异常以恢复栈，远端错误明确断言同具体类型且 cause 链包含原异常，原文件读取处仍断言同一对象，不以通用错误通过回归。

日志与 XML 位于下述仓库外缓存目录，Gradle 公共参数同下。`statement-reader-matrix.log` 实际执行 `:shared:testAndroidHostTest`（`LocalCashStatementReaderTest`、`CashPdfParseApiTest`、`CashXlsParseApiTest`），`:business-domain:jvmTest`（`CashStatementDispatchTest`）和 `:backend:test`（`CashStatementDocumentGoldenTest`、`BankStatementDocumentGoldenTest`、`CashStatementDispatchTest`、`LocalCashStatementFileParityTest`、`LocalCashXlsxParityTest`），每类用 `--tests '*类名*'`，最后 `--console=plain`；分别为 3 类／13 方法、1 类／2 方法、5 类／20 方法，0 失败／错误／skip。原成功／错误文档和完整归一保留；HTTP Mock 回归断言只有对应 parse-only 路径、原元数据和同一原字节，本地路径不请求网络。

`statement-reader-platforms.log` 使用外部 `-I <缓存目录>/statement-reader-browser.init.gradle` 仅选择 ChromeHeadless，并设置 `CHROME_BIN='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'`。执行 `:shared:jsBrowserTest`／`:shared:wasmJsBrowserTest` 选择前述三类（各 3 类／13 方法），`:business-domain:jsNodeTest`／`:business-domain:wasmJsNodeTest` 选择分派测试（各 1 类／2 方法），全部 0 失败／错误／skip；同时 `:shared:compileKotlinIosSimulatorArm64`、`:webApp:jsBrowserDevelopmentWebpack` 与 `:webApp:wasmJsBrowserDevelopmentWebpack` 通过。按独立审查建议补 PDF 大整数、金额和原文固定字面量后，以同样公共参数／外部 init，重跑前述三个 shared 测试任务及三类选择，`statement-reader-exact-platforms.log` 全部通过。最新 XML 保存在 `statement-reader-current-xml/<模块>/<任务>/`。

独立 5a 与 5b 契约审查无本片阻断；评审者未修改实现或运行构建。OpenSpec strict 和 `git diff --check` 通过。Cross-platform Impact Check：Web／Android／iOS 共享文件分派均受影响，按前述测试及编译验证；没有产品页面、路由或文案修改，Chrome 单测不冒充真实页面冒烟。本片不改持久化或权限合同，不另运行双库矩阵；整个写集和缓存接入后仍须完整双库验证。用户既有 XLS／公式边界不变，无新增领域术语或通用规范。README、两级功能地图、design 和 apply 指引均限定单文件入口；批次 owner、稳定输入持久化与规则协调、主线程响应／取消、完整导入、缓存和页面仍未完成。

#### 值型 XLSX 读取器

验证时间：2026-11-05 06:24 UTC；验证工作树 HEAD／比较基线为 `341898e5`。本片是值型 XLSX 字节到完整原始文档和本地归一入口；未接完整文件分派、缓存或页面。`local-xlsx-red.log` 先复现缺失入口，`local-xlsx-formula-boundary-red.log` 先复现新公式拒绝合同未实现；namespace、扩展样式、错误码及值 presence 的先红日志分别为 `local-xlsx-namespace-red.log`、`local-xlsx-style-path-red.log`、`local-xlsx-error-cell-red.log`、`local-xlsx-cell-value-red.log`。其余 XML／OPC finding 的先红日志保留在同一仓库外目录，不删改原始账单黄金。

全部日志、XML、合成规模源码及 Gradle init 位于 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1104/`。在仓库根执行的公共参数：

```sh
NODE_ENV=development ./compose/gradlew -p compose --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx6144M -Dfile.encoding=UTF-8'
```

`local-xlsx-value-matrix.log` 在公共参数后依次执行 `:business-domain:jvmTest`（下表前五个 common 类）及 `:backend:test`，每类用 `--tests '*类名*'` 选择，最后 `--console=plain`。common 类为 `CommonPlatformXlsxOriginalTest`、`CashWorkbookXmlTest`、`CashWorkbookArchiveTest`、`CashXlsxFormulaBoundaryTest`、`CashXlsxCellValueTest`；backend 类为 `LocalCashXlsxParityTest`、`CashWorkbookDateCellTest`、`CashWorkbookNumericCellTest`、`CashWorkbookXmlEncodingTest`、`CashStatementDocumentGoldenTest`、`CashWorkbookPreflightTest`、`CashWorkbookArchiveParityTest`。结果为 common 5 类／18 方法、backend 7 类／27 方法，全部通过，0 error／skip。原 8 个 XLSX 的完整文档与归一输出、14 成功／14 失败 XML 编码矩阵及原整包预算断言均保留。

`local-xlsx-value-platforms.log` 设置 `CHROME_BIN='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'`，增加 `-I <缓存目录>/xlsx-performance.init.gradle`。该 init 只为 `business-domain/commonTest` 加仓库外合成规模源码，并用实际 `useConfigDirectory` 配置 ChromeHeadless、Mocha／无活动超时 120 秒；首次规模运行超过默认 2 秒测试超时，未计通过，未改产品阈值或裁剪输入。最终按顺序执行以下任务，每个测试任务均用前述五类的 `--tests`；JVM 与两个 Browser 任务额外选择 `CashXlsxPerformanceProbe`：

| 任务 | 结果 |
|---|---|
| `:business-domain:jvmTest` | 6 类／19 方法，0 失败／错误／skip |
| `:business-domain:jsNodeTest`、`:business-domain:wasmJsNodeTest` | 各 5 类／18 方法，0 失败／错误／skip |
| `:business-domain:jsBrowserTest`、`:business-domain:wasmJsBrowserTest` | Chrome Headless 157，各 6 类／19 方法，0 失败／错误／skip |
| `:business-domain:testAndroidHostTest` | 5 类／18 方法，0 失败／错误／skip |
| `:business-domain:compileKotlinIosSimulatorArm64`、`:shared:prepareCommonComposeResources` | 通过；不宣称 iOS 设备运行 |
| `:webApp:jsBrowserDevelopmentWebpack`、`:webApp:wasmJsBrowserDevelopmentWebpack` | 通过 |

合成规模探针验证真实解码后的完整行数及末笔标识：1 万／5 万行文件分别 227,004／1,120,526 字节，工作表实际 XML 约 3.5／18 MiB。JVM 读取 396／1049 ms，Chrome JS 1261／6423 ms，Chrome Wasm 1348／6234 ms。它是该机器的一次读取观测，不是跨设备 SLO、总导入耗时、峰值 heap 或 PowerSync 比较；页面接线仍需验证主线程长任务与取消。XML 保存于 `local-xlsx-value-xml/<模块>/<任务>/`，最终矩阵均 `BUILD SUCCESSFUL`。

`export_common_projection_vectors.py --kind platform-xlsx --check` 核对 common 原 8 个文件和完整期望来自单一原生 oracle。xmlutil 0.91.3 的完整 Apache 2.0 COPYING 加入既有声明资源，不复制上游运行源码；serialization 保持既有 1.10.0，本轮核对领域库 JVM／JS／Wasm 处理资源、Compose common prepared 及 Web JS／Wasm 新生成产物中的声明一致；未重打 Android／iOS 应用包，后续实际分发前必须重新生成并核对声明，不能沿用旧打包缓存。依赖和资源核验、OpenSpec strict 及 `git diff --check` 在提交前完成。独立 5a／5b 的有限 finding 均已关闭，未用 reviewer 的只读意见冒充测试证据。

Cross-platform Impact Check：共享解析代码影响 Web／Android／iOS，前述测试与相称编译已执行；本片无页面、交互、路由或用户可见文案变化，浏览器单测不是产品页面冒烟。没有数据库或持久化行为变更，本片不另跑双库；整个改造的最终双库矩阵、真实页面 QA 与归档仍待完成。

收尾核对 proposal、delta、design、tasks、apply 指引、功能地图、依赖／分发声明及矢量生成器，保留当前页面仍用服务端旧流程的事实。用户本次排除公式，只限定本 change 的 XLSX 接受边界，不沉淀成通用财务或文件处理规则。没有新增领域术语、部署或外部资源配置。完整写集／重派、文件调度、加密库与密钥／owner／索引、浏览查询和页面、旧接口退役、最终 QA 与归档均未完成；Web 自动解锁已确认，实际密钥与缓存生命周期仍待完成。

### 撤权后会话活动工作区验证

比较基线／验证 HEAD：e6f437d1 加本片工作树，2026-11-07（本地）。真实移除成员后，旧令牌的持久会话仍指向已移除工作区；内部恢复要求活动项属于同次返回的可访问工作区，否则整次恢复以 `ledger_session_scope_invalid` 停止，撤权缓存永不清理。`ReadServices.state` 改为只在同次读取的工作区列表中保留持久活动项，不在读取时回写会话行、不自动改选其他工作区；业务读取仍按活动成员资格拒绝。合同由 `shared/kotlin-backend-parity` 的 MODIFIED“保留存储与安全不变量”记录，属对迁移前“原样回显持久指针”的明确修订；D10 同步说明该归一让撤权前已有的悬空指针无需数据迁移。

ledger-session-membership-red.log／expanded-red-xml 先以两项真实登记、邀请、选择与移除用例复现 `assertNull` 失败，实际仍返回已移除工作区 ID；首轮新增用例另暴露测试自身以省略默认值的 DTO 序列化冒充 HTTP 回包，改为按原字段解码并显式核对 `active_workspace_id` 为 JSON null，保留空列表回包。

终态命令沿既有 init、`--no-daemon`、`--max-workers=1`、`--no-configuration-cache`、`--no-build-cache`、in-process 与 Xmx1536M，任务 `:backend:migrationEvidenceTest` 按类过滤。夹具全部在仓库外 offline-ledger-1104：SQLite 用 `sqlite.db` 副本；PostgreSQL 用从 `ft_offline_ledger_1104_test` 模板复制的专用 `ft_ledger_session_revocation_test`，不自动探测或启动数据库。revocation-sqlite-final.log／postgres-final.log 各两项 0 fail／error／skip（13.28／8.18 秒）；normalization-regression-{sqlite,postgres}-final.log 的 ReadContractTest（含原 Python `/auth/session` 记录）、WorkspaceLockReauthorizationTest 与 AuthenticationWriteBoundaryTest 各八项，SQLite 一项按既有 PostgreSQL-only 假设跳过、其余全零，PostgreSQL 八项全零（7.17／9.24 秒）；fresh XML 为对应 `-xml` 目录。采样进程树 RSS 峰值 1,606,729,728／1,124,876,288 及 1,263,566,848／1,159,905,280 字节，可能重复共享页，不是产品内存。收尾按用户、工作区、会话与成员计数核对两库隔离夹具与模板一致，撤权用例重复执行不留下新行。

读取 oracle 的外部夹具 `{sqlite,postgres-5432,postgres}/http.json` 只把 `Bearer synthetic-removed` 的 `active_workspace_id` 从 `fixture-a` 改为 null，其余字节不变：三份改前副本 SHA-256 同为 `6d35661e245f664a5c4a93ef3f2631da3651832183621cf332361ce453392f5e`（保存于 session-normalization-oracle），改后同为 `ac101393b68bad7dfdb5f90c35b3ec556ffcdeb348aa8e7f260ffc32e7cce0da`。这是本片授权的合同变更记录，不是为通过而放宽其他记录；proposal 与 parity 的授权差异列表同组登记该差异。PostgreSQL 回归与撤权用例串行复用同一专用 `_test` 副本：该库由模板复制而来、用例自清理，未并发重置；受当时磁盘余量限制没有另建第二个 333 MB 副本，已清理 5.8 GB 可重建的 Gradle 本地构建缓存。

独立 5a／实际独立 5b 由独立上下文复核读取归一、delta 完整性、测试边界、外部 oracle 与文档记录，无 P0。两项 P1 已回写：proposal 的 Modified Capabilities 补充 `shared/kotlin-backend-parity` 及该授权差异；新增“后端运行时保持全部现有合同”的 MODIFIED delta，把会话归一列入原已授权差异清单，避免归档后两条 requirement 互相矛盾。P2 均已处理：测试把注册、建工作区等准备移入 `try` 并以可空标识逐步收尾，用例完成后清理失败才使测试失败，准备失败时只打印清理诊断以保留原失败，同时把第二用例名改为只声明服务端不自动选中；delta 场景改名为“有效用户没有可访问工作区”，并说明客户端仍按 `workspace-entry` 选择首个可访问工作区，避免把服务端归一表述成端到端不选中；design D10 记录删除工作区仍同事务清指针、移除外成员资格只依赖读取归一及重新受邀会使旧指针重新有效的取舍。复核后重跑两库撤权用例与夹具计数核对均通过。

Cross-platform Impact Check：仅服务端会话读取语义变化，客户端仍要求活动项属于工作区列表，未改共享类型、页面、路由或文案；Web 无 UI 变化，不追加页面冒烟、原生设备 QA、UI 复用或 Hallmark。D10、compose README 的恢复说明、共享功能地图、词表与 apply 指引同组核对（backend README 未记录该会话字段，无需修改）；总览功能地图已描述离线恢复能力状态，本片只细化服务端归属，不重复改写。无新领域术语、数据迁移或部署变化，可独立回退。认证拒绝的用户定位、显式退出、Graph 接线、页面与完整离线能力仍未完成，5.3／5.4／7.2 及整个 change 不勾选、不归档。

### 应用会话退出的缓存清理验证

比较基线／验证 HEAD：00b486c5 加本片工作树，2026-11-07（本地）。真实 `createFinanceTrackerGraph` 的会话仓库改为 `SessionCacheRepository`：只记录最近一次经服务端核验的使用者摘要，退出先按发起时的令牌快照撤销会话，再推进目录代次、关闭当前恢复对象（启动恢复尚未接线，当前为空）、以空保留集合清除该使用者全部范围。未核验身份时不猜测也不删除；令牌撤销与缓存清理分别执行，清理失败保留原 `LedgerSessionSignOut` 供同一使用者再次退出重试，双失败以 `DomainFailure` 保留原令牌 code／status／category，缓存失败放在 `SessionSignOutFailure` cause，不使用 JS 不可读的 `suppressed`。单一代次使并发登录先注册的新范围不可能被本次清理选中：登录自己的代次会封闭旧退出，旧退出的代次则使其登记失败。启动恢复与账本页面仍走服务端，本片不把仓库装饰器写成离线可用。

session-signout-red.log 记录新类型缺失的编译红灯（15.33 秒）。为取得行为红灯，临时去掉清理调用：session-signout-behavior-red.log 七项中五项失败（89.87 秒，其中依赖清理可见性的并发用例超时），恢复实现后转绿。

session-signout-host-final6.log 沿既有单 worker／非 daemon／in-process／Xmx1536M 参数执行 `:shared:testAndroidHostTest`，新增两类十四项 0 fail／error／skip（19.44 秒）；fresh XML 为 session-signout-host-final-xml。session-signout-wasm-final3.log 在 Chrome Headless 157 执行 `:shared:wasmJsBrowserTest` 全量七百六十三项零失败（43.98 秒），新增两类十四项全零，XML 为 session-signout-wasm-final-xml。session-signout-native-final3.log 编译 `:shared:compileAndroidMain`、`:shared:compileKotlinIosArm64`、`:shared:compileTestKotlinIosSimulatorArm64` 成功（30.69 秒）。JS 全量 session-signout-js-final2.log 七百五十七项中新增两类十四项全零，另有一项与本片无关的既有失败 `CashImportPlanApiTest.oldReceiptKeyCountsUnicodeCharactersAndNeverReplacesInvalidUnicode`（孤立代理字符键在 JS 未抛错）；stash 本片后在 HEAD 复跑 session-signout-baseline-browser.log 同样失败（七百四十三项、一项失败），故不把该失败算作本片回归，也不宣称 JS 全量通过。采样进程树 RSS 峰值：host 1,731,264,512／1,602,961,408，Wasm 3,101,327,360／2,191,998,976，native 2,249,736,192／2,122,072,064，JS 3,832,135,680／2,309,193,728 字节；可能重复共享页，不是产品内存。

独立 5a／实际独立 5b 由独立上下文复核身份记录、代次围栏、令牌先撤销、重试资源、错误契约与 Graph 接线，无 P0。P1 已修复并先红后绿：清理失败保留的对象在其它操作推进代次后原本会以 `ledger_session_operation_superseded` 作为协程取消抛回，导致退出界面永不完成且对象不可重试；现在检测被替代后先收尾原对象，再按新代次创建新清理，内部替代只报告 `sign_out_incomplete`，真实调用方取消仍原样传播；对应新增“代次推进后重试”和“清理期间被并发操作替代”两项回归。P2 已修复：缓存单独失败也统一为 `DomainFailure(sign_out_incomplete)`，双失败时原令牌 code／status／category 保留、缓存失败作为次要原因；复核指出的 `close()` 原为死代码，现由替代重试在创建新清理前调用以收尾原对象。复核指出的身份读取与令牌快照非原子属迁移前退出入口同等窗口，已在 design 记录为残余并由调用方忙碌状态与令牌条件清除约束。测试缺口已补：重试、并发替代、统一失败类型；`active` 恢复对象仍为空是启动恢复未接线的已知边界。

Cross-platform Impact Check：共享会话仓库与退出清理影响 Web／Android／iOS，三端均编译并有主机／浏览器验证；无页面、路由、布局或用户文案变化，本片不追加产品页面冒烟、Hallmark、UI 复用或原生设备 QA。D10、compose README、共享功能地图、词表与 apply 指引同组核对；backend、公开 DTO 和数据库未改，不重复双库。认证拒绝的令牌绑定清理已在后续切片完成；启动恢复、持续联网核验、页面本地化与最终 QA 仍未完成，5.3／5.4／7.2 及整个 change 不勾选、不归档。

### 认证拒绝的令牌绑定清理验证

比较基线／验证 HEAD：7f75cda8 加本片工作树，2026-11-07（本地）。服务端拒绝时把该请求实际使用的令牌附在 `ApiFailure` 上，会话类请求（启动恢复、刷新、工作区选择与创建等）只对状态 401 且带令牌的失败，按加密恢复记录中的令牌摘要清除绑定一致的范围。匹配是摘要绑定而非猜测使用者，冷启动没有已核验身份也能清除；本地合成 401、登录／注册失败、网络、超时与业务读取的 401 不触发或只清令牌。探测会打开同源其它加密范围仅比较摘要，不读取或输出正文；坏材料不按“不匹配”结案，而是保留为可重试的未知。清理对象按令牌去重、按代次重定基，失败保留供后续拒绝或退出重试且不替换原认证失败。

session-expiry-behavior-red.log 以临时跳过清理取得行为红灯：仓库测试十五项中三项失败（29.68 秒），恢复后转绿。实现期间另修正测试自身的目录存储假件未按 key 隔离、以及清理完成后的删除进度断言。

session-expiry-host-final7.log 沿既有单 worker／非 daemon／in-process／Xmx1536M 参数执行 `:shared:testAndroidHostTest`，四类四十二项 0 fail／error／skip（30.64 秒，RSS 2,282,897,408／2,147,139,584 字节）；fresh XML 为 session-expiry-host-final-xml。session-expiry-wasm-final4.log 在 Chrome Headless 157 执行 `:shared:wasmJsBrowserTest` 全量七百七十九项零失败（43.89 秒，RSS 4,383,277,056／2,168,324,096），其中四类四十二项全零，XML 为 session-expiry-wasm-final-xml。session-expiry-native-final4.log 编译 `:shared:compileAndroidMain`、`:shared:compileKotlinIosArm64`、`:shared:compileTestKotlinIosSimulatorArm64` 成功（30.66 秒，RSS 2,288,304,128／2,160,558,080）。JS 全量 session-expiry-js-final4.log 七百七十三项中四类四十二项全零（47.06 秒，RSS 3,900,293,120／2,365,833,216），仍有同一项与本片无关的既有失败 `CashImportPlanApiTest.oldReceiptKeyCountsUnicodeCharactersAndNeverReplacesInvalidUnicode`（该失败已在 HEAD 无本片代码时复现），不宣称 JS 全量通过。采样可能的共享页重复不是产品内存。

独立 5a／实际独立 5b 首轮提出两项 P1 与多项 P2，均已处理并重审通过：文档与实现不一致已改正；并发清理竞争由清理对象自身互斥、仓库按令牌去重与逐次激活检查关闭；令牌来源改为服务端拒绝附带的实际使用令牌，合成 401 不再可能误删；探测失败改为未知并保留重试；关闭失败保留未释放资源；无范围时不再推进代次；取消不再被误判为替代。重审另指出替代检查位于 try 之外可能掩盖原令牌失败、以及 `ticket` 读取未同步，已移入 try、以互斥读取并补“并发清理各删一次”“部分失败后 close 只释放不再删除”两项回归；文档把触发范围改称会话类请求。复核无遗留阻断。

Cross-platform Impact Check：共享会话仓库、令牌拒绝定位与清理影响 Web／Android／iOS，三端均编译并有主机／浏览器验证；无页面、路由、布局或用户文案变化，不追加产品页面冒烟、Hallmark、UI 复用或原生设备 QA。D10、compose README、共享功能地图、词表与 apply 指引同组核对；backend、公开 DTO 和数据库未改，不重复双库。启动恢复与离线本地浏览已由后续切片接入真实 Graph；写入后刷新、始终本地读取、页面离线提示与最终 QA 仍未完成，5.3／5.4／7.2 及整个 change 不勾选、不归档。

### 应用启动恢复与离线本地浏览接线验证

比较基线／验证 HEAD：9b907e38 加本片工作树，2026-11-07（本地）。`SessionCacheRepository` 在启动恢复时创建内部 `LedgerSessionRestoration`：先推进代次并关闭旧恢复对象，恢复成功后按同代次检查再发布，身份同时取自该次响应；被替代（退出或新操作）不冒充协程取消，而是关闭候选并报 `session_superseded`。只有识别到网络失败、从加密恢复记录取得身份的恢复才通过 `LocalFirstFinanceRepository` 把收支分页、现金账户、类型目录和分类目录改为本地读取；在线成功仍读服务端分页，避免写入后未刷新缓存时显示旧事实。成功会话刷新、工作区切换、登录／注册／接受邀请与退出都会封闭并关闭旧恢复对象，关闭失败保留原对象供后续重试；401 清理只在被拒令牌与当前恢复对象一致时先关闭它。本地分页沿用服务端同一设备时区与 `projection_version` 版本围栏，输入错误保留 `invalid_filter` 400、快照变化保留 409 与 `internal` 的未知分类。

local-first-behavior-red.log 以临时改回纯在线恢复取得行为红灯：仓库测试二十二项中七项失败（29.66 秒），恢复后转绿。实现期间独立复核先后指出：本地分页时区与服务端不一致、恢复没有串行围栏、迟到 401 会关闭新登录的读取对象（三项 P1）；修复后重审又指出成功会话刷新未封闭旧读取、退出读取本地对象未与代次同锁（两项 P1），均已修复并补“离线恢复后才暴露本地读取”“成功刷新关闭旧读取”“失败刷新保留”“迟到其他令牌 401 不关闭”“恢复被退出替代不发布”等回归。

local-first-host-final2.log 沿既有单 worker／非 daemon／in-process／Xmx1536M 参数执行 `:shared:testAndroidHostTest`，五类五十九项 0 fail／error／skip（组合运行 8.19 秒，含编译的首轮 38.84 秒；RSS 峰值 915,210,240／787,628,032 字节，随后同代码树矩阵见下）。local-first-wasm-final.log 在 Chrome Headless 157 执行 `:shared:wasmJsBrowserTest` 全量七百九十六项零失败（45.02 秒，RSS 4,192,780,288／2,314,387,456），XML 为 local-first-wasm-final-xml。local-first-native-final.log 编译 `:shared:compileAndroidMain`、`:shared:compileKotlinIosArm64`、`:shared:compileTestKotlinIosSimulatorArm64` 成功（30.66 秒，RSS 2,230,124,544／2,103,492,608）。local-first-js-final.log 的 JS 全量七百九十项中新增相关类全零，仍有同一项与本片无关的既有失败 `CashImportPlanApiTest.oldReceiptKeyCountsUnicodeCharactersAndNeverReplacesInvalidUnicode`（已在无本片代码的 HEAD 复现），不宣称 JS 全量通过。共享页可能重复，不是产品内存。

独立 5a／实际独立 5b 末轮复核无 P0／P1，遗留两项仅在同一使用者恢复与退出真正并发时出现的 P2 竞态（已取消的退出会跳过收尾、极窄窗口内的新恢复可能被收尾关闭），均记录为残余并在退出取消路径补了非取消收尾。Cross-platform Impact Check：共享会话仓库、本地读取适配与 Graph 接线影响 Web／Android／iOS，三端均编译并有主机／浏览器验证；无页面、路由、布局或用户文案变化，但应用启动与账本读取路径已改变，**尚未执行真实浏览器离线启动与浏览冒烟**，列为完成前必做；不追加 Hallmark 或原生设备交互。D10、compose README、共享／总览／现金账本功能地图、词表与 apply 指引同组核对；backend、公开 DTO 和数据库未改，不重复双库。写入后缓存刷新、始终本地读取、离线提示、详情／导入／工作区管理的离线能力、真实浏览器离线 QA 与最终验证仍未完成，5.3／5.4／6.6／7.2 及整个 change 不勾选、不归档。

#### 生产 Wasm 预览断网启动冒烟

比较基线：1d745326 加本片工作树，2026-11-07（本地）。用 `:backend:installDist` 产物与临时 SQLite 数据库启动本机 Kotlin 后端（JDK 21），`start-compose-demo.mjs --build-only` 构建生产 Wasm 预览，预览服务经同源代理连接后端，Playwright + Chrome Headless 完成注册、创建工作区、在线重载写缓存、拦截全部 `/api/**` 后重载的离线启动流程。在线重载的请求依次为 `auth/session`、`ledger/version`、`ledger/snapshot`，随后仍从服务端读取分页与目录；离线重载只有 `auth/session` 以 `net::ERR_FAILED` 失败，账本页仍可见、无诊断错误，截图与请求记录保存于仓库外 offline-smoke/evidence。

冒烟先暴露三个生产阻断并已修复：生产 webpack 无法解析 `/ledger-storage/session-locator.mjs`，需与既有 `connection.mjs` 一样在 `webApp/webpack.config.d/ledger-storage.js` 声明原生外部导入；本地预览服务把 `.mjs` 当 `application/octet-stream`，已补 `text/javascript` 并在 preview-proxy node test 断言；加密缓存 Worker 要求跨源隔离，已给预览服务加 `Cross-Origin-Opener-Policy: same-origin` 与 `Cross-Origin-Embedder-Policy: require-corp`，并为生产 Static Site 增加 `webApp/src/webMain/resources/_headers` 及 compose README 部署说明，缺少隔离头时缓存初始化失败而不是静默降级。新增常驻 E2E `compose/qa/web/tests/compose-offline.e2e.ts`（断网重启后从加密缓存浏览账本，16.1 秒通过），随 `npm run test:e2e` 在真实后端矩阵执行；临时调试配置与截图未留在仓库。该冒烟只覆盖生产 Wasm 预览与本机后端，不代表 Render 已部署或原生设备 QA。


### 提交后缓存刷新接线验证

比较基线／验证 HEAD：`d73fc44f0dc78a964a23e34b6028e074cc7f0aba` 加本片工作树，2026-11-14（本地）。现金录入、草稿／维护保存、导入确认、账户及分类变更在请求前捕获原恢复对象和会话代次；成功后的完整快照刷新不能选中后来登录或工作区。核实仅在 committed 且收据确认成功时刷新；按 ID／原键读取成功收据的恢复路径同样接线。离线恢复后联网写成功也刷新原缓存，不因历史 offline 标记跳过。原提交成功不被刷新异常或内部 superseded 取消替换；真实调用方取消继续传播。刷新错误使用既有安全诊断，只保存类型及允许的错误码，不输出账单、令牌或完整异常消息。

本进程已知写成功但尚未取得完整事实时暂时隐藏本地读取；后续成功刷新恢复。刷新代次防较早完成覆盖较晚失败。最大已确认收据版本由同一个 `LedgerSnapshotSync` 累计，即使刷新失败，历史收据或无版本刷新都不能降低下限；不直接推进缓存水位，不新增持久化事实或队列状态。在线浏览仍用原服务端分页，首次登录／工作区切换的完整缓存接线和持续联网核验仍待完成。

`refresh-red.log` 先复现离线成功写后跳过刷新、内部取消替换成功、非 committed 核实触发刷新三项失败；`floor-red.log` 再复现高版本失败后旧收据降低下限。终态共用 `JAVA_HOME` 指向 JDK 21、`NODE_ENV=development ./compose/gradlew -p compose --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.compiler.execution.strategy=in-process`，主机 JVM 2 GiB，浏览器／原生矩阵 3 GiB。证据位于仓库外 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/`：

| 命令与范围 | 当前结果 |
|---|---|
| `:shared:testAndroidHostTest`，SessionCacheRepository、CashMaintenanceApi、CashCreationApi、CashImportPlanApi、LocalFirstFinanceRepository、LedgerSessionCacheOwner、LedgerSessionRestoration、LedgerSnapshotSync 八类 | 142 项，0 failure／error／skip；`refresh-final-host.log`，35 秒 |
| `:shared:wasmJsBrowserTest` 全量 | 815 项，0 failure／error／skip；`refresh-platforms.log`，组合矩阵 74 秒 |
| `:shared:jsBrowserTest` 全量 | 809 项，0 failure／error／skip；`refresh-js.log`，50 秒；包含此前记录的孤立代理字符键用例，本轮实际通过 |
| `:shared:compileAndroidMain :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosSimulatorArm64` | 与 Wasm 矩阵组合编译通过；不宣称原生设备 QA |
| `node compose/qa/web/scripts/run-compose-playwright.mjs compose-offline.e2e.ts --output <证据目录>/browser-evidence` | 两项真实后端 E2E 通过；`refresh-e2e-final.log`，23.8 秒 |
| `openspec validate --all --strict --json`、`openspec doctor --json`、`git diff --check` | 27/27、healthy、通过 |

Web 使用 Chrome 157、`http://127.0.0.1:5189`，真实 Kotlin 后端 `127.0.0.1:18613` 与独立合成 SQLite，视口为 390×844 和 1440×1600。生产预览启动器实际核验构建指纹 `eb44a6ce1af08c3893431414d3241335944ace5f9873f18ee6c72e483f1e34e8`。新增流程通过真实表单保存 -12.50 CNY，核对新快照的完整流水、事实版本及原精确文本，再拦截 API 重载；离线新页面只显示一笔记录和 -12.5 CNY 汇总，静态资源继续可用。在线无控制台／页面／网络异常，离线只有预期的 auth/session 网络拒绝；截图和网络附件在 browser-evidence。表单关闭的画布已实际检查；旧语义导出残留不作为页面仍打开的判断，流水选择器也明确排除同前缀复选框。未执行 Render 部署或原生设备交互。

独立 5a 产品／范围、工程、取消／错误及最终 diff 与实际独立 5b 契约复核提出的跨会话刷新、内部取消、离线写后陈旧、诊断丢失、非 committed 刷新、收据恢复遗漏和版本下限回退均已修复、补回归并重审关闭，无遗留阻断。本片未改 UI 组件、布局、主题、文案或协议模型，UI 复用／Hallmark 不适用。Cross-platform Impact Check：共享仓库和同步影响 Web／Android／iOS，已运行上述相称矩阵；该切片不改服务端 DML／schema，不以浏览器 SQLite 冒烟代替整个改造的双后端验收。

用户明确授权代理创建专用测试库；已在仓库外建立独立 PostgreSQL 18.6 集群，端口 55441、数据库 `offline_first_ledger_1114_test`，显式连接与夹具目录写在权限 0600 的 `postgres.env`，不探测或复用其他服务。SQLite 对照库另行加载相同仓库基线与合成 admin。`LedgerVersionContractTest`、`LedgerSnapshotContractTest`、`LedgerSnapshotParityTest` 在两库各 22 项全通过、0 skip；命令为 `:backend:migrationEvidenceTest` 加上述三类过滤，分别配置 `FT_KOTLIN_LEDGER_CONTRACT_DIR`，PG 另显式设置 `FT_TEST_POSTGRES_URL` 与测试角色；日志／fresh XML 为 `snapshot-{postgres,sqlite}.log` 和对应 `-xml`。这只复核当前版本／快照，不代表尚未接通的完整写集已通过。

收尾核对并更新 D5／D10、Compose README、总览／共享／现金账本功能地图和 apply 指引；词表、公共接口、部署配置及第三方依赖没有新概念或变化。没有新的产品／UI／财务语义选择，无需新增通用规则；测试库准备仅为本任务执行授权。完整写集服务、端侧导入调度、离线写入限制／重派、始终本地读取、旧路径退役、最终 QA 与归档仍未完成，本 change 保持 active。用户已授权将缓存刷新和来源保存改动仅在现有分支本地提交，不推送或创建 PR。

### 真实来源写集保存验证

比较基线与验证 HEAD 均为 `d73fc44f0dc78a964a23e34b6028e074cc7f0aba`，2026-11-14（Asia/Shanghai），分支 `worktree-offline-first-ledger-import`。本片沿 A 类 change 的 3.2／3.4 实施既定来源阶段，无新增范围或财务语义。证据目录为仓库外 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`。

共享 `prepareCashImportWriteSetSourceValues` 先执行金融值、引用、固定字段和文件动作门，核明确来源编辑、旧人工标记／指纹、组成项隐藏值和原文编辑 effects。新建／转正式核资金账户可用性、类型与币种，已有正式账户保持冻结豁免；草稿明确账户编辑只核明选非空 ID 的全部最终币种，明确 currency 核全部已选账户，自动缺项／不可用草稿留待完整问题阶段。原文沿既有对象／64 层合同，在递归解码前核层级；账户属性先核字符串数组，解释仅浅解码。数值等价父项／组成项金额保留可信旧表示。内部 `saveCashImportWriteSetSources` 要求有效事务 stamp，在同连接读取真实完整旧事实及存储修订，调用原 SQL writer，返回实际 ID 映射、真实余额前后值及变更集合；不执行 C。组成项删除前引用修复原位提取供两个入口复用，确认关系保护不变。

先失败证据：`source-values-red.log` 和 `source-write-red.log` 复现缺少入口；`source-explanation-red.log` 两库复现 null→空解释多余写入；`source-order-red.log` 两库复现仅数组换序推进修订；`source-amount-red.log` 四例复现尾零／正负零表示导致 SQLite 额外 DML 或返回值未保旧。深度测试初版在 JVM 已通过，不把 `source-depth-red.log` 当作行为红灯。修复回归同时核全库、父修订、账本版本、引用映射、输入只读、隐藏值、C 未执行和真实写入后整体回滚。

终态 `source-account-matrix-jvm.log` 使用本页单 worker、非 daemon、in-process、Xmx3072M 公共参数，显式载入 `postgres.env`，执行领域完整 `:business-domain:jvmTest` 和 `:backend:test --tests '*CashImportWriteSetSourcesTest' --tests '*CashRecordValueWriterTest'`，39 秒成功：领域 169 类／657 项，来源保存及原 raw writer 两类双库共 40 项，均 0 failure／error／skip。`source-account-red.log` 先复现转正式可用性门缺失；草稿测试初版的时刻文本比较失败来自既有 UTC 归一，已将断言限定为本次账户／组成项合同，不计作策略行为红灯。

终态 `source-account-platforms.log` 使用相同 Gradle 参数、Chrome Headless 157 及仓库外 `quality.init.gradle`（ktlint worker 1024 MiB），执行领域 JS／Wasm 浏览器全量、Android 主代码、iOS Arm64 主代码、iOS Simulator 测试编译、领域 ktlint／detekt MetadataCommonMain／JvmTest 和 backend ktlint／detekt Main／Test，4 分 2 秒全部成功。两浏览器各 144 类／621 项，0 failure／error／skip；fresh XML 为 `source-final-domain-{jvm,js,wasm}-xml` 和 `source-final-backend-xml`。较早 `source-matrix.log` 在领域 ktlint CommonTest 默认 worker 堆耗尽，不写该旧命令整体通过；独立 `source-quality.log` 和上述终态矩阵均已实际通过，不修改产品运行堆配置。

用户测试库授权下，另在同一独立 PostgreSQL 集群准备 `ledger_regression_1114_test` 并加载当前基线，以该库显式配置 `FT_TEST_POSTGRES_URL` 执行默认 `:backend:test :backend:installDist`。`backend-regression.log` 于 2 分 23 秒成功，191 类／992 项，0 failure／error／skip；fresh XML 为 `backend-regression-xml`。该完整自包含回归先于账户门增量，增量后已重跑上述受影响双库 40 项；不把 992 项记成账户增量后的全量重跑，也不替代外部历史夹具任务。来源两库仍使用前述专用 fixture 库，无自动数据库发现或启动。终态 `source-account-openspec-{validate,doctor}.json` 为 27／27 与 healthy，`git diff --check` 通过。

JS 全量首次实际暴露既有孤立代理字符夹具被常量折叠及精确浮点回归超出默认 2 秒，`source-platforms-final.log` 保留两项失败。夹具改运行时字符数组并先核码值；浏览器沿 Node 已有 120 秒测试预算，Mocha 及 Karma 心跳／断开／无活动同步配置，保留全部向量及空测试集拒绝。后续 `source-platforms-verified.log` 只因未放宽的心跳断开失败，不计通过；最终上述两浏览器全量实际完成。

独立 5a 范围／工程／输入安全／最终 diff 和实际 5b 契约 child `refresh_review/source_contract` 发现的三项 P2 均先红再绿并重审关闭：解释等价、组成项传输顺序、等价金额表示。账户增量及两行草稿独立、同 ID 多币种、cash／loan／lend、真实事务拒绝及旧正式账户豁免的增量复核无 finding；原始 source DML 与 C 分离、真实修订／回滚及引用修复未发现遗留阻断，文档与最终实现一致。未覆盖完整服务鉴权、基线、来源身份、绑定、问题／候选、映射、C／B／D、幂等与 HTTP，不把本片内部入口记作公开能力。Cross-platform Impact Check：共享纯门影响全部目标，已执行上述编译／单测；后端阶段未改 UI／文案／路由，UI 复用、Hallmark、设备及新增 Web 主流程 QA 不适用。

已核对 D3、总览及导入功能地图和 apply 指引；schema、依赖、公共 HTTP／CLI、部署配置及术语没有变化，对应 README／词表／迁移说明无需新增。仅延续既定语义与用户测试库及提交授权，无新的可复用用户偏好或规范需要沉淀。来源阶段已提交 `251613f1` 并按当前小步规则推送至 `worktree-offline-first-ledger-import`；整个 change 的 3.2／3.4 和最终验证保持未完成，不归档、不创建 PR、不宣称离线导入可用。

### 账户映射最终增量验证

2026-11-14（Asia/Shanghai），比较基线及验证 HEAD 为 `cf0d059d` 加本片工作树，分支 `worktree-offline-first-ledger-import`。按用户明确要求收敛账户映射契约：只提交既有最终增量，服务端保留真实账户资格、渠道／kind／非空来源键、自然键及旧身份／修订校验，移除重演用 wire、服务端发布集合推导和未提交的内部观察模型。DTO、引用门和生产 OperationRules 与 `cf0d059d^` 完全一致；共享规则已输出 used_account_selections，不新增第二份发布算法。真实保存、同值确认信息不变、实际 ID 和来源结果的原事务／工作区／savepoint 围栏保留。

证据位于仓库外 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`。`mapping-contract-rollback-red.log` 先复现移除重演字段的 wire 回归，11 项中 1 项失败；`mapping-contract-rollback-values-red.log` 复现最终映射必须附重演观察才被接受，5 项中 4 项失败。实现后 `mapping-contract-rollback-focused.log` 的 contracts 11 项、domain 8 项、同一来源／映射参数化类的 SQLite 与真实 PostgreSQL 共 44 项均为 0 失败／错误／skip。保留真实插入、同值、修改、finalize、非法微信映射在来源更新后拒绝、晚期故障与溢出整体回滚，以及过期／跨工作区／savepoint 结果拒绝；空映射增量保持无默认变更。两项本地生产组合回归覆盖 apply／posted defer／keep、草稿 defer、单笔覆盖、币种过滤及多组同账户，直接核原 used 输出。

完整矩阵 `mapping-contract-rollback-matrix.log`（6 分 42 秒）使用 `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`、`NODE_ENV=development`、`CHROME_BIN=/Applications/Google Chrome.app/Contents/MacOS/Google Chrome` 和仓库 Gradle JDK criteria。公共参数为 `./compose/gradlew -p compose --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.compiler.execution.strategy=in-process` 及 JVM `-Xmx3072M -Dfile.encoding=UTF-8`；`-I <证据目录>/quality.init.gradle` 将既有 ktlint worker heap 设为 1024m。真实 PostgreSQL 显式设置 `FT_TEST_POSTGRES_URL=postgresql://finance_tracker_test@127.0.0.1:55441/ledger_regression_1114_test` 及测试角色，测试不启动或探测数据库。

| 实际任务 | 当前结果 |
|---|---|
| `:business-contracts:jvmTest`、`:business-domain:jvmTest`、`:backend:test` 全量 | 分别 10 类／27 项、170 类／664 项、191 类／1,016 项；全部 0 失败／错误／skip |
| contracts／domain 的 `jsBrowserTest` 和 `wasmJsBrowserTest` 全量 | ChromeHeadless 157，contracts 两端各 10 类／27 项，domain 两端各 145 类／628 项；全部 0 失败／错误／skip |
| `:backend:installDist`；三个模块 `ktlintCheck`；domain `detektMetadataCommonMain`／`detektJvmTest`；backend `detektMain`／`detektTest` | 全部通过，部分静态任务复用相同源码的有效结果 |
| `:business-domain:compileAndroidMain`、`compileKotlinIosArm64`、`compileTestKotlinIosSimulatorArm64` | 编译通过；不宣称 iOS 运行或设备 QA |
| `openspec validate --all --strict --json`、`openspec doctor --json`、`git diff --check` | 27/27、healthy、通过 |

最终 XML 保存为 `mapping-contract-rollback-<模块>-<任务>-xml`，fresh 时间与计数核对见 `mapping-contract-rollback-results.json`。独立 5a 产品／范围、工程（产品编码规范 §7）及最终 diff 和 5b 实际契约复核无代码阻断；D3 原文 provenance／完整证明等歧义已收敛为真实旧来源约束、自然键、owner／scope 冲突及幂等，最终范围复核无新增 finding。回退前内部观察矩阵不计为当前合同证据。

Cross-platform Impact Check：共享传输和最终映射门影响 Web／Android／iOS，按上述浏览器单测及编译覆盖；无页面、交互、路由或文案变化，UI 复用／Hallmark 和页面冒烟不适用。沿原工作区事务及参数绑定，无新增存储、schema、依赖或外部服务；完整写集性能仍归完整服务验收。已同步 D3、delta、总览／导入功能地图和 apply 指引，README、词表、接口命令示例、迁移及部署配置无需改动。

用户决定保持原映射行为、撤去重复证明过程，只落实本 change 的客户端／服务端分工；现有产品编码规范已覆盖避免重复模型和无必要兼容，不新增通用规则。按现行授权在原任务分支提交推送，不改写历史、不归档或创建 PR。完整鉴权、基线、来源与关系身份幂等、绑定／问题／C／B／D／收据组合及公开提交仍待完成，3.2／3.4 与整个 change 保持未完成。

### 来源绑定最终增量验证

2026-11-14（Asia/Shanghai），比较基线及验证 HEAD 为 `2b5e5f0d` 加本片工作树，分支 `worktree-offline-first-ledger-import`。共享准备器直接消费既有最终来源绑定字段，校验真实旧目标与修订、自然身份、渠道固定作用域、同 owner 身份／稳定证据以及相关文档声明／页指纹。规范文档／页别名保留旧 live owner 的既有命名空间与页证据；普通元数据更新和软删 owner 复用保留原语义。内部阶段沿原来源事务 owner／savepoint 围栏，复用旧 saveOccurrence 抽出的绑定 DML，返回实际 ID，同值不写、变化推进修订，拒绝和晚期故障由同一事务整体回滚。没有原文件位置、完整同源成员、代表行或账户决定的新增证明协议。

证据目录为仓库外 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`。`bindings-domain-red.log`／`bindings-store-red.log` 固定新门和新保存阶段缺失；身份、字段与文档不变量的失败证据在 `bindings-review-red.log`（共享 8 项中 2 项失败，双库 62 项中 4 项失败）及 `bindings-alias-red.log`（共享 9 项中 1 项失败，双库 64 项中 2 项失败）。真实存储回归核对 source 插入或编辑已经执行后再拒绝／注入故障，完整数据库与版本保持原样；旧 native occurrence 产生的三类绑定经新阶段均为精确 no-op。`bindings-platform-red.log` 与对应 JS XML 保留 637 项中 4 项失败：页总数经过 Any 容器被编码为浮点格式；归一输出边界复用 SourceJsonInteger，严格解码、原默认值及比较语义不变。`bindings-platform-focused.log` 的 JS／Wasm 各 9 项全通过；无 XML 或空筛选运行不作为证据。

最终同代码树矩阵 `bindings-final-matrix.log` 用时 5 分 49 秒。命令环境为 `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`、`NODE_ENV=development`、`CHROME_BIN=/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`，实际 JDK 沿仓库 Gradle criteria。公共参数为 `./compose/gradlew -p compose --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.compiler.execution.strategy=in-process`、JVM `-Xmx3072M -Dfile.encoding=UTF-8` 及 `-I <证据目录>/quality.init.gradle`。真实 PostgreSQL 显式设置 `FT_TEST_POSTGRES_URL=postgresql://finance_tracker_test@127.0.0.1:55441/ledger_regression_1114_test` 及测试角色；测试不启动或探测数据库。

| 实际任务 | 当前结果 |
|---|---|
| `:business-domain:jvmTest` 全量 | 171 类／673 项，0 失败／错误／skip |
| `:backend:test` 全量 | 191 类／1,036 项，0 失败／错误／skip；本次生产来源／映射／绑定阶段的同一参数化类在 SQLite 与真实 PostgreSQL 各 32 项 |
| `:business-domain:jsBrowserTest`、`wasmJsBrowserTest` 全量 | ChromeHeadless 157，两端各 146 类／637 项，全部 0 失败／错误／skip |
| domain 的 `ktlintCheck`、`detektMetadataCommonMain`、`detektJvmTest`；backend 的 `ktlintCheck`、`detektMain`、`detektTest`、`installDist` | 全部通过；未改源码的依赖任务可复用有效结果 |
| `:business-domain:compileAndroidMain`、`compileKotlinIosArm64`、`compileTestKotlinIosSimulatorArm64` | 全部编译通过，不宣称 iOS 运行或设备 QA |
| `openspec validate --all --strict --json`、`openspec doctor --json`、`git diff --check` | 27/27、healthy、通过 |

最终 XML 保存为 `bindings-final-<模块>-<任务>-xml`，新生成时间、类／用例计数及真实双库分组见 `bindings-final-results.json`。独立 5a 按产品编码规范 §7 核范围、输入／事实边界、DML 提取兼容性、修订／回滚及复杂度；实际 5b 契约复核未发现新增模型或重复协议。两项 P2 的处置分别保留真实旧 live 文档证据并保护其规范别名、复用既有支付宝／微信作用域常量；整数序列化修复也已增量复核。无遗留阻断。

Cross-platform Impact Check：共享校验影响 Web／Android／iOS，已执行上述浏览器测试和相称原生编译；无页面、布局、交互、路由、主题或用户文案变化，UI 复用、Hallmark、页面冒烟和设备交互不适用。已核对 README、导入流程／合同、D3、功能地图、词表、接口／命令示例及配置／迁移；更新总览／导入功能地图和 apply 指引，其余无新增 schema、依赖、公共 HTTP／CLI 或术语，说明无需改动。

用户决定撤去重复证明过程，沿既定客户端计算与服务端真实事实校验分工；现有产品编码规范已覆盖避免冗余模型与无必要复杂度，不新增通用规范。按已授权的小步规则提交推送原任务分支。完整鉴权、基线、创建身份幂等、空页／问题／关系／C／B／D／收据及公开服务仍待组合，3.2／3.4 与整个 change 保持未完成，不归档、不创建 PR，也不宣称已能离线导入。

### 来源元数据最终增量验证

2026-11-14（Asia/Shanghai），比较基线及验证 HEAD 为 `26408185` 加本片工作树，分支 `worktree-offline-first-ledger-import`。内部来源元数据阶段消费同一来源事务结果，在绑定后保存既有空页与来源问题最终增量。绑定与空页共用相关旧／新别名的文档声明及页指纹一致性校验；原规范业务页识别仍决定空页去重，普通别名的完整 coverage 不冒充占用页。问题的 owner／key 不可更换，候选先核旧依赖后存同批实际 ID／修订，已有歧义的空候选请求保留原整项和旧候选；普通来源更新不能退役歧义或关系问题。原仓储处理真实身份、修订、同值不写、候选替换与删除级联，失败由外层事务整体回滚。没有新增 wire、原文件真实性证明或客户端算法重演。

证据目录为仓库外 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`。`metadata-domain-red.log`／`metadata-store-red.log` 固定缺失准备器及真实保存阶段；`metadata-documents-red.log` 和对应 XML 固定共享 9 项中 1 项失败、真实双库 82 项中 2 项失败，证明普通文件／账户别名 coverage 与空页冲突的漏检。`metadata-focused-green.log` 的共享 9 项、真实双库 82 项通过，随后补强普通别名匹配声明下的空页保存及同值重放，纳入最终矩阵。真实数据库检查比较完整状态与账本版本，覆盖来源写入后的拒绝、问题修订溢出、元数据写入后的晚期故障、候选退休级联、同键删后建取得新身份；raw JSON 空键与超出 2^53 的整数保真。

独立 5a 按产品编码规范 §7 复核范围、依赖、输入／状态、DML 提取、兼容、回滚与复杂度；实际 5b 契约审查核对字段和仓储复用、无重复证明协议。唯一 P2 是绑定与空页证据范围不一致，已抽取共用文档校验并以真实双库先红后绿关闭；陈旧候选用例只写 owner，让只读候选依赖独立触发拒绝。增量重审无遗留阻断。

最终同代码树矩阵 `metadata-final-matrix.log` 用时 6 分 26 秒，全任务成功。公共命令为 `./compose/gradlew -p compose -I <证据目录>/quality.init.gradle --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.compiler.execution.strategy=in-process`，JVM 参数 `-Xmx3072M -Dfile.encoding=UTF-8`。环境为 `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`、`NODE_ENV=development`、`CHROME_BIN=/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`，实际 JDK 沿仓库 Gradle criteria；真实 PostgreSQL 显式设置 `FT_TEST_POSTGRES_URL=postgresql://finance_tracker_test@127.0.0.1:55441/ledger_regression_1114_test` 及测试角色，测试不探测或启动数据库。

| 实际任务 | 当前结果 |
|---|---|
| `:business-domain:jvmTest` 全量 | 172 类／682 项，0 失败／错误／skip |
| `:backend:test` 全量 | 191 类／1,054 项，0 失败／错误／skip；同一来源参数化类 SQLite 与真实 PostgreSQL 各 41 项 |
| `:business-domain:jsBrowserTest`、`wasmJsBrowserTest` 全量 | ChromeHeadless，两端各 147 类／646 项，0 失败／错误／skip |
| domain 的 `ktlintCheck`、`detektMetadataCommonMain`、`detektJvmTest`；backend 的 `ktlintCheck`、`detektMain`、`detektTest`、`installDist` | 全部通过 |
| `:business-domain:compileAndroidMain`、`compileKotlinIosArm64`、`compileTestKotlinIosSimulatorArm64` | 编译通过，不宣称 iOS 运行或设备 QA |
| `openspec validate --all --strict`、`openspec doctor`、`git diff --check` | 27/27、健康、通过 |

最终 XML 保存为 `metadata-final-<模块>-<任务>-xml`，实际计数及生成时间范围见 `metadata-final-results.json`。`metadata-tested-code.json` 固定六个实现／测试文件的 SHA-256，矩阵结束后已核当前代码一致；独立文档／范围复核亦无新增 finding。

Cross-platform Impact Check：共享校验影响 Web／Android／iOS，适用 JVM／浏览器测试及原生编译；没有页面、交互、路由、主题或用户文案变化，UI 复用、Hallmark、页面冒烟及设备交互不适用。文档收尾核对 README、导入流程／合同、D3、功能地图、词表、接口／命令示例和配置／迁移，更新两份功能地图及 apply 指引；无新增 schema、依赖、HTTP／CLI 或术语，其余无需改动。

用户决定回退账户映射额外证明契约，本片继续沿客户端共享计算与服务端真实事实校验分工；现有规范已覆盖避免冗余模型，不新增通用规则。按已授权的小步规则提交推送原分支。完整鉴权、基线、创建身份幂等、关系及 C／B／协调／D／收据组合和公开服务仍待完成；3.2／3.4 与整个 change 保持未完成，不归档、不创建 PR。

### 首次关系及条目事务组合验证

2026-11-14（Asia/Shanghai），比较基线及验证 HEAD 为 `fe151e5a` 加本片工作树，分支 `worktree-offline-first-ledger-import`。内部 `saveCashImportWriteSetFirstRelations` 消费原来源事务结果，限于外层已排除精确幂等及其 B 的首次新关系。先用真实 before 验依赖、选择组和公共选择，随后对真实写后来源及完整旧闭包校验全部新增关系，再全增量执行一次 B；按共享联合批次与原组序复用原关系／候选仓储。私有计算 ID 不窄化新引用，存储联合 UUID、实际身份、操作者和时刻由服务端产生，客户端不能指定联合范围。客户端关联金额须与真实图结果精确一致；非 draft 候选和未知 decision source 拒绝，draft 包括空候选仍沿原修订规则。候选先验写前依赖，再保存同批实际修订，并核对仓储未静默改变声明状态。

真实事务组合回归串起来源、绑定、C、B／关系、完整图、协调及 D（调用来源元数据阶段，但该用例没有新增空页或问题），保留来源 S、成员独立 C 与最终公共 D 的不同值；最终独立值被伪造时，已写来源、绑定、关系、条目及账本版本整体回滚。联合 draft 桥连接两个接受组的回归固定每父只执行一次 B；旧 confirmed 退款与新退款共用原额度，超限拒绝、精确余量成功。文件专用拓扑门仅调整 Kotlin 可见性以供 backend 使用，原规则和 generic 内部结构不变，不开放 HTTP 或维护动作。

仓库外证据目录 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`：`relations-red.log` 固定缺失保存阶段的失败编译；测试夹具保持正式导入所需稳定来源绑定，不以去掉图校验修补夹具。`relations-focused.log`／`relations-focused.xml` 固定最初的 SQLite／PostgreSQL 同一参数化类各 49 项全绿；当前 `relations-review-green.log` 为各 51 项，共 102 项，0 失败／错误／skip。安全／拒绝回归精确核对错误类型与代码，覆盖字段／角色／金额／anchor、候选状态、过期事务结果和已执行写入后的整体回滚。独立 5a 按产品编码规范 §7 及实际 5b 契约复核发现两项 P2：原仓储预算前递归解码过深证据，以及完整 posted 锚点的可用候选未核金融／角色硬约束。`relations-review-red.log`／对应 XML 的 102 项中 4 项失败分别复现两库的栈溢出和非法 pair 被接受。现解码前以迭代栈核对象与 64 层边界，原仓储仍管其预算；关系和候选两路径都拒绝超深／非对象证据并整体回滚，深度边界正例保真。候选复用原角色、账户、币种、等额规则，只约束真实完整 posted 且声明 valid 的组合，不完整草稿锚点仍能保存。完整候选替代方案的联合图合法性仍须在完整命令内补齐，不能按所有互斥候选一起确认或逐边 full-graph 校验来误拒联合方案。修复已聚焦转绿，独立增量重审确认两项 P2 已关闭、无新增阻断；最终文档复核也已收窄组合证据与候选未覆盖范围。

最终矩阵 `relations-final-matrix.log` 用时 4 分钟，全任务成功；源码未变的共享测试／编译复用同一代码的有效结果，发生修复的 backend 测试与静态检查已重跑。环境、公共 Gradle 参数和显式 PostgreSQL 库与上方来源元数据矩阵一致，测试不自动探测或启动数据库。

| 实际任务 | 当前结果 |
|---|---|
| `:business-domain:jvmTest` 全量 | 172 类／682 项，0 失败／错误／skip |
| `:backend:test` 全量 | 191 类／1,074 项，0 失败／错误／skip；同一来源／关系组合参数化类 SQLite 与真实 PostgreSQL 各 51 项 |
| `:business-domain:jsBrowserTest`、`wasmJsBrowserTest` 全量 | ChromeHeadless，两端各 147 类／646 项，0 失败／错误／skip |
| domain 的 `ktlintCheck`、`detektMetadataCommonMain`、`detektJvmTest`；backend 的 `ktlintCheck`、`detektMain`、`detektTest`、`installDist` | 全部通过 |
| `:business-domain:compileAndroidMain`、`compileKotlinIosArm64`、`compileTestKotlinIosSimulatorArm64` | 编译通过，不宣称 iOS 运行或设备 QA |
| `openspec validate --all --strict`、`openspec doctor`、`git diff --check` | 27/27、健康、通过 |

最终 XML 保存为 `relations-final-<模块>-<任务>-xml`，计数、时间范围及双库分组见 `relations-final-results.json`。`relations-tested-code.json` 固定三个实现／测试文件的 SHA-256；矩阵结束后核对当前代码一致，源码与证据相符。

Cross-platform Impact Check：共享层只开放既有文件拓扑校验供 backend 调用；关系真实保存是服务端实现，覆盖 Web／Native 的共同提交合同，没有页面、交互、路由、主题或用户文案变化。UI 复用、Hallmark、页面冒烟及设备 QA 不适用；原生只计编译。核对 D3、导入／总览功能地图、apply 指引、README、词表、接口示例及配置／迁移；更新当前阶段说明，其余没有新增 wire、schema、依赖、术语或 HTTP／CLI，无需修改。

用户决定回退账户映射额外证明契约，本片继续沿最终增量与真实数据库不变量分工，不新增通用规范。整体 change 与 3.2／3.4 保持未完成；完整鉴权、基线、来源／关系身份幂等、关系问题、永久收据、公开提交及端侧完整接线仍待实现，不能将首次关系 helper 当作完整命令或数据层幂等。按现行小步授权提交推送原任务分支，不归档、不创建 PR。


### 首次写集提交命令组合验证

2026-11-14（Asia/Shanghai），比较基线及验证 HEAD 为 `1a722da8` 加本片工作树，分支 `worktree-offline-first-ledger-import`。本片沿既定 A 类 D3，不新增文件证明或账户映射观察协议，整体 3.2／3.4 和完整 change 保持未完成。

1. 思考：核对原鉴权、受管事务、提交边界、账本版本、永久收据及现有内部保存阶段。新增命令只组合首次写入路径，完整来源／关系身份幂等、候选替代联合图和完整身份响应仍是公开端点前的必需项。
2. 计划：同连接固定工作区，锁后及全部返回路径重新鉴权，成员资格锁保持到提交。原键和正文摘要先重放收据，新请求再验规则版本／基线；正文摘要和 DTO 解码共享有界深复制快照，保留数字词法、UTF-8、128 层结构及原 64 MiB 预算，不取代业务 JSON 的 64 层边界。
3. 任务拆分：同一 SQLite／显式真实 PostgreSQL 参数化类覆盖实际保存、精确数字、版本、空增量／同值、原键重放、正文冲突、旧规则及陈旧基线、鉴权、晚期回滚、响应丢失和并发。复用并提取原来源类的双库 fixture，不改变其准备或清理语义，测试不自动探测／启动数据库。
4. 构建：`submission-red.log` 固定缺失内部命令的编译失败。内部命令按来源、绑定、元数据、最终映射、C、首次关系及 B、真实完整组协调／D、余额和永久收据执行。声明拓扑先验证完整覆盖，最终真实图只取声明成员闭包，覆盖纯 D 和无关条目隔离。行／关系结果使用位置引用及真实 ID，不限制 opaque 新引用；暂不开放 HTTP。
5. 审查：独立 5a 按产品编码规范 §7 核对范围、异常、输入、事务、重试和测试；实际 5b 由防劣化技能调度契约审查，核到原收据模型、鉴权、条目、余额和 fixture。发现 P2：调用方可在等待锁时修改原 JSON，使摘要与保存内容分离；`submission-review-red.log/xml` 的 38 项中 4 项失败，分别复现两库的根 map 和嵌套 list 替换。现同次有界遍历固定容器并求摘要，解码只读该快照，修复后转绿。P3 诊断阶段滞后已改为动作前 enter、成功后 done。独立增量重审确认 P2／P3 关闭、无新增阻断；最终文档复核发现的两处地图残句已原位修正。未覆盖项保持上述完整幂等、候选联合图及完整身份响应，不以内部组合宣称公开服务完成。
6. 测试与 QA：`submission-focused-final.log` 为 SQLite／PostgreSQL 各 21 项，共 42 项，0 失败／错误／skip；新增元数据／映射／条目／收据组合有成功与收据后晚期回滚，最终鉴权撤权／logout／expiry 在新事实及收据已写后整体回滚。提交成功但响应丢失后由正式 ReceiptReadService 原键查询和同键重试返回同一结果，不重复 ID／余额／版本。`submission-final-matrix.log` 用时 4 分 8 秒，113 任务（13 执行、100 up-to-date）全部成功；共享源码未变的有效测试／编译结果复用 Gradle 输入核验，未冒称重新运行。结果见下表。
7. 发布：D3、导入及总览功能地图、apply 指引已按内部接线现状更新；核对 README、词表、接口／命令示例、配置／迁移及相关规格，无新 wire、schema、依赖、术语、HTTP 或 CLI，其余无需修改。用户要求撤回额外账户映射证明协议，本片沿最终增量与真实数据库不变量分工，不新增通用规范。按现行授权自动提交推送原分支，不归档、不创建 PR；完整 change 仍待端侧发现／构建／页面与完整服务等任务。实现限服务端组合，无 UI、交互、路由或用户文案改动，Web 页面冒烟、Hallmark、UI 复用和设备 QA 不适用，原生只计相称编译；Web／Native 消费的共同提交合同未改变。

| 实际任务 | 当前结果 |
|---|---|
| `:backend:test` 全量 | 192 类／1,116 项，0 失败／错误／skip；新增提交类 SQLite／真实 PostgreSQL 各 21 项，原来源／关系类各 51 项 |
| `:business-domain:jvmTest` 全量 | 172 类／682 项，0 失败／错误／skip，源码未变复用有效结果 |
| `:business-domain:jsBrowserTest`、`wasmJsBrowserTest` 全量 | ChromeHeadless，两端各 147 类／646 项，0 失败／错误／skip，源码未变复用有效结果 |
| domain `ktlintCheck`、`detektMetadataCommonMain`、`detektJvmTest`；backend `ktlintCheck`、`detektMain`、`detektTest`、`installDist` | 全部通过 |
| `:business-domain:compileAndroidMain`、`compileKotlinIosArm64`、`compileTestKotlinIosSimulatorArm64` | 编译任务通过，源码未变复用有效产物，不宣称 iOS 运行或设备 QA |
| `openspec validate --all --strict`、`openspec doctor`、`git diff --check` | 27/27、健康、通过 |

证据目录 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`；最终 XML 为 `submission-final-<模块>-<任务>-xml`，计数、时间范围和双库分组见 `submission-final-results.json`；`submission-tested-code.json` 固定实现、两测试及 fixture 的 SHA-256，完整矩阵后核对一致。真实 PostgreSQL 使用显式专用 `ledger_regression_1114_test`（本地 55441，`finance_tracker_test`）；聚焦使用原 `postgres.env` 的专用 `_test` 库。实际完整命令为：

```sh
source /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/postgres.env
export FT_TEST_POSTGRES_URL=postgresql://finance_tracker_test@127.0.0.1:55441/ledger_regression_1114_test
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
export NODE_ENV=development
export CHROME_BIN='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
./compose/gradlew -p compose -I /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/quality.init.gradle \
  --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache \
  -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8' \
  :business-domain:jvmTest :backend:test :business-domain:jsBrowserTest :business-domain:wasmJsBrowserTest \
  :business-domain:compileAndroidMain :business-domain:compileKotlinIosArm64 :business-domain:compileTestKotlinIosSimulatorArm64 \
  :business-domain:ktlintCheck :business-domain:detektMetadataCommonMain :business-domain:detektJvmTest \
  :backend:ktlintCheck :backend:detektMain :backend:detektTest :backend:installDist
```


### 来源身份解析阶段验证

2026-11-14（Asia/Shanghai），比较基线及验证 HEAD 为 `31ec91a5` 加本片工作树，分支 `worktree-offline-first-ledger-import`。沿既定 A 类 D6 完成内部可信来源身份解析，整体 change 和 3.2／3.4 保持未完成。

1. 思考：核对原来源读仓储、绑定唯一索引、稳定证据普通索引、SourceResolver 和真实 writer 的比较／保留规则。不用时间、内容召回或代表选择确定本次身份，不新增来源证明协议。
2. 计划：受管事务中读取同工作区完整事实，汇总全部自然键／稳定证据 live owner。新引用命中同一父项时核原 New 默认资格、来源业务值、方向性说明及完整 ordinal 金融组成项；旧 origin／指纹／人工标记／组成项隐藏字段保留，冻结 posted 账户不重新要求启用。软删只分类绑定复用，不复活父项。结果只返回 parent／part／精确 binding 和已删绑定复用，限原事务／工作区，savepoint 回滚即失效；不改 DTO、不补造修订、不写入或消费 C／B。
3. 任务拆分：提取原共享 scalar／单 owner alias 检查及既有 coverage 归一的 Kotlin 可见性；真实来源 writer 与解析共用字段归一和方向性说明比较，避免复制规则。双库同一参数化类覆盖精确值、JSON、组成项换序／空项、New 资格、旧隐藏值保留、停用账户、软删／多 owner、跨区／事务／回滚及原仓储对照。
4. 构建：`source-identities-red.log` 固定缺失解析函数的失败编译。新增内部解析阶段使用原自然键结构及真实 before 的索引，无 DML；原引用与未命中新 alias 保留，不把来源同值当作整操作完成。实际命令、图 ID、旧条目依赖、完整效果及关系联合批次仍需共同识别这些映射；不得用 New→Existing DTO 改写伪造客户端观察。
5. 审查：独立 5a 按产品编码规范 §7、实际 5b 经防劣化技能调度契约评审，核到原来源仓储／resolver、固定字段、来源 writer、共享字段及 alias 检查、事务与异常边界。发现两个 P2 和一个 P3：说明 null／空对象被对称折叠；非法 proposed JSON 形状漏出原 InvalidCashValue；筛选 New 后字段下标偏移。`source-identities-review-red.log/xml` 的 38 项中 4 项失败复现两库的方向性说明与下标问题。现原保存和解析共用原方向性比较，仅 proposed 字段边界转换已知 InvalidCashValue，存量损坏继续传播原异常；金融错误路径映回原数组位置。说明双向回归还对照真实 source writer，输入形状和损坏持久值分别断言 policy／程序故障。独立增量重审确认两 P2／P3 均关闭，无新增阻断；末项空金融草稿用例亦已复核。未覆盖完整别名接线、C／B 消费、关系幂等及完整提交，不以此小步关闭这些门禁。
6. 测试与 QA：身份类 SQLite／真实 PostgreSQL 各 23 项，共 46 项，0 失败／错误／skip；包括零组成项和金融字段全空的 draft，原 native occurrence 写入的精确命中与软删绑定实际迁移，来源同值但新 alias／C 未应用时不误判完整成功。JSON 64 层边界、非法 shape、跨工作区、稳定证据非唯一／自然键分裂归属等拒绝路径均核全库不变。1,001 alias 只读工作量实际完成，当前 XML 记录 SQLite 11 ms、PostgreSQL 9 ms，只有原匹配绑定被列为已存在；按实际 owner 只验证一次完整 alias 组。该用例不是大账本或完整导入性能验收，完整事实读取仍为 O(工作区事实量)。完整矩阵 `source-identities-final-matrix.log` 用时 6 分 10 秒，113 任务（32 执行、81 up-to-date）通过；当前 backend 测试和测试静态检查见 `source-identities-final-backend.log`，3 分 53 秒，21 任务（5 执行、16 up-to-date）通过。仅补测试后按影响范围重跑 backend，不重复源码未变的共享／原生检查。
7. 发布：D6、两份功能地图及 apply 指引已说明内部解析现状与剩余接线；核对 README、词表、接口／命令示例、配置／迁移及相关规格，无新增 wire、schema、依赖、术语或 HTTP／CLI，其他无需修改。本次没有新用户业务取舍，继续遵从回退额外证明协议的既定边界，无需新增通用规范。共享层仅抽取原字段／alias 校验并开放 Kotlin 内部复用，业务语义不变；服务端增加只读事务阶段，Web／Native 的公开提交合同未变，无 UI、路由、交互或用户文案改动，UI 复用、Hallmark、页面冒烟及设备 QA 不适用，原生只计编译。按现行授权提交推送原分支，不归档、不创建 PR，不声称整体来源幂等或完整 change 完成。

| 实际任务 | 当前结果 |
|---|---|
| `:backend:test` 全量 | 193 类／1,162 项，0 失败／错误／skip；身份类两库各 23 项，原来源／关系类各 51 项，内部提交类各 21 项 |
| `:business-domain:jvmTest` 全量 | 172 类／682 项，0 失败／错误／skip；当前共享代码已在聚焦矩阵执行，完整矩阵复用其有效结果 |
| `:business-domain:jsBrowserTest`、`wasmJsBrowserTest` 全量 | ChromeHeadless，实际执行，两端各 147 类／646 项，0 失败／错误／skip |
| domain `ktlintCheck`、`detektMetadataCommonMain`、`detektJvmTest`；backend `ktlintCheck`、`detektMain`、`detektTest`、`installDist` | 全部通过，末项测试静态检查已按当前测试源码补跑 |
| domain `compileAndroidMain`、`compileKotlinIosArm64`、`compileTestKotlinIosSimulatorArm64` | 编译通过，不宣称 iOS 运行或设备 QA |
| `openspec validate --all --strict`、`openspec doctor`、`git diff --check` | 27/27、健康、通过 |

仓库外证据目录 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`。最终 XML 为 `source-identities-final-<模块>-<任务>-xml`，计数、时间范围、双库分组与 alias 工作量见 `source-identities-final-results.json`；`source-identities-tested-code.json` 固定三个实现及测试文件的 SHA-256，最终核对当前代码一致。测试库、完整环境、Gradle 参数及全任务命令与“首次写集提交命令组合验证”相同，输出日志为上述 `source-identities-final-matrix.log`。当前 backend 复核沿同一显式 `ledger_regression_1114_test`、相同 Gradle 参数执行 `:backend:test :backend:ktlintCheck :backend:detektTest`；测试不自动探测或启动数据库。


### 来源身份映射的共享校验接线

2026-11-14（Asia/Shanghai），比较基线及验证 HEAD 为 `d7c7da22` 加本片工作树，分支 `worktree-offline-first-ledger-import`。沿 A 类 D6，不改变范围、来源业务语义或 wire；整体 change 与 3.2／3.4 保持未完成。

1. 思考：来源只读解析已完成；原共享门禁按原始 New／Existing 区分身份，会漏掉实际旧收支依赖并在图中重复同一来源。本片只修正可信映射下的计算身份，不重新发现来源。
2. 计划：共享门禁接收原事务解析的父项／组成项／绑定映射，不改 DTO、不补修订。映射须命中真实事实、已声明目标、组成项父项及绑定自然键／live owner；程序内错接保留不变量故障。原 Existing 观察仍核原预期修订，未命中新项保留私有计算 ID。软删绑定复用不归并其旧父项。
3. 任务拆分：先写共享引用、图与拓扑用例，再补原解析结果的同上下文投影。覆盖混合引用重复、候选实际 parent／part、完整旧关系／投资组成项、legacy 排序、draft 单例、未命中新引用、映射输入快照和原旧收支观察；双库从真实解析调用相同共享门禁，不改写请求。
4. 构建：`source-alias-guards-red.log` 固定缺少可信映射入口的编译失败，不作为行为红灯。`source-alias-kind-red.log` 的同文本跨实体类型用例 1 项失败，复现条目拓扑把关系／绑定 target 误用父项映射而读错身份；按实体种类分开映射后转绿。原 writer、DTO、修订和现有默认无映射调用保持不变；图按真实父项／组成项替换一次，保留旧 origin 与绑定排序。
5. 审查：独立 5a 按产品编码规范 §7 核输入／异常、信任边界、原 New／Existing 观察、完整旧图和收支依赖、增量复杂度及最终 diff；实际 5b 经 codebase-degradation-review 调度独立 child `refresh_review/source_contract` 执行 contract-design-review。主复核及 child 均无阻断 finding，末项 live owner 围栏和 15 项共享测试已独立确认。非 wire 的单一可信映射容器隔离数据库实际身份与客户端观察、按类型归一及复制 map，未增加持久状态或重复 wire。未覆盖导入专用范围／来源保存、完整命令和 C／B 消费，也未宣称这些已完成。
6. 测试与 QA：聚焦 `source-alias-guards-focused-final.log` 共共享 4 类／51 项、backend 2 类／90 项通过，之后新增 live binding owner 的内部不变量由完整矩阵覆盖。`source-alias-guards-matrix.log` 6 分 46 秒，113 任务（35 执行、78 up-to-date）全部通过；当前全部实现／测试的 SHA-256 与 `source-alias-guards-tested-code.json` 一致。最终 XML 与时间／计数见下表，0 失败／错误／skip；跳过的无源码 Gradle task 不作测试通过证据。
7. 发布：D6、两份功能地图及 apply 指引已按当前门禁接线更新；核对 README、词表、接口／命令示例、配置／迁移及相关规格，无新领域概念、wire、schema、依赖或公开入口，其余无需修改。没有新用户业务取舍，沿回退重复证明协议的既定边界，无需新增通用规范。Cross-platform Impact Check：共享计算门影响 Web／Native，执行以下跨端单测／编译；无 UI、路由、交互或用户文案变化，UI 复用、Hallmark、页面冒烟及设备 QA 不适用。按现行授权提交推送原分支，不归档、不创建 PR；后续仍须将同一身份视图接入导入专用保存／公共效果与完整提交，才能完成来源和关系幂等。

| 实际任务 | 当前结果 |
|---|---|
| `:backend:test` 全量 | 193 类／1,164 项，全部 0 失败／错误／skip；来源身份类 SQLite／真实 PostgreSQL 各 24 项，含实际解析后共享引用及图验证、原请求不变和事务／跨区／savepoint 围栏 |
| `:business-domain:jvmTest` 全量 | 173 类／697 项，全部 0 失败／错误／skip；新增共享身份映射 15 项 |
| `:business-domain:jsBrowserTest`、`wasmJsBrowserTest` 全量 | ChromeHeadless，两端各 148 类／661 项，全部 0 失败／错误／skip；同一 15 项映射用例实际执行 |
| domain `ktlintCheck`、`detektMetadataCommonMain`、`detektJvmTest`；backend `ktlintCheck`、`detektMain`、`detektTest`、`installDist` | 全部通过 |
| domain `compileAndroidMain`、`compileKotlinIosArm64`、`compileTestKotlinIosSimulatorArm64` | 编译通过，不宣称 iOS 运行或设备 QA |
| `openspec validate --all --strict`、`openspec doctor`、Markdown 本地链接、`git diff --check` | 27/27、健康、无缺失、通过 |

仓库外证据目录 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`。最终 XML 为 `source-alias-guards-final-<模块>-<任务>-xml`，实际计数／时间范围与双库分组见 `source-alias-guards-final-results.json`。测试库、完整环境、Gradle 参数和全任务命令与“首次写集提交命令组合验证”相同，输出为上述 `source-alias-guards-matrix.log`；显式使用 `ledger_regression_1114_test`，测试不自动探测或启动数据库。


### 来源身份的保存接线验证

2026-11-14（Asia/Shanghai），比较基线及验证 HEAD 为 `52aa30ef` 加本片工作树，分支 `worktree-offline-first-ledger-import`。沿 A 类 D6，保持原业务和最终增量合同；整体 change 与 3.2／3.4 保持未完成。

1. 思考：只读解析和共享门禁已有可信身份视图，真实来源／绑定保存尚未消费，精确重导仍被重复声明拒绝。本片接通内部来源／绑定持久化；数据层整项效果与公开提交仍未完成。
2. 计划：来源保存在同一受管事务解析同一请求，复用 before／aliases，避免在独立调用间错接证明。精确来源 New 命中跳过源 DML，返回真实父项／组成项，New／空修订不改写，余额和 C／B 不重复执行。原 Existing 仍按原客户端观察；绑定使用精确／软删实际身份，同值不写、新 alias 新增，已删 owner 不复活。
3. 任务拆分：文件范围、固定字段引用及来源账户、绑定／文档准备传递同一映射，绑定按实际 parent 分组核冲突。New 默认／隐藏字段资格保持，冻结 posted 账户只放过已证明精确来源；普通 New 继续核 active。双库实际保存核 ID、全库不变、余额／版本／修订及真实软删绑定迁移、冲突和晚期回滚，共享同一准备分支在 JVM／JS／Wasm 执行。
4. 构建：`source-identity-save-red.log` 的 6 项全部失败，复现原入口拒绝精确重导、旧隐藏值／停用账户命中及带新 alias 的重导。现来源保存直接调用解析，命中不调用 parent／part writer；绑定沿原 writer 使用实际 oldId。旧 stableEvidence 碰撞回归改为 DML 前的 `source_identity_conflict`：record_id 不同已违反既定完整来源等价，移除不可达的“已插两行”断言，保留全库不变断言，未改错误 mapper。原 Existing 金融／来源准备、标准 UTC 归一和修订路径保持原义。
5. 审查：独立 5a 按产品编码规范 §7 核输入／异常、真实事实及修订、来源和公共效果的分离、精确 no-op／软删／绑定归属、复杂度与最终 diff；实际 5b 经 codebase-degradation-review 调度独立 child `refresh_review/source_contract` 执行 contract-design-review。主复核及 child 无代码阻断 finding；最终文档复核的两处旧未接入措辞（P3）已原位改为来源／绑定已接、完整命令仍待，并经复核关闭。确认早期冲突处置符合 D6、未弱化来源默认或复活旧父项；末项两个跨端准备测试及 SourceResult 连同内层 identities 的 owner／savepoint 围栏已增量复核。metadata／mapping／entry／C／B／relations／完整 command 的统一 alias 接线未覆盖，也未宣称完成；无 UI 专项范围。
6. 测试与 QA：聚焦 `source-identity-save-focused-final.log` 通过，当前全部实现／测试由 `source-identity-save-matrix.log` 完整覆盖，6 分 43 秒，113 任务（33 执行、80 up-to-date）全部通过。身份类 SQLite／真实 PostgreSQL 各 30 项，其中本片新增各 6 项；同一共享 alias 类三端各 17 项，含账户资格和绑定准备的两项新增分支。9 个实现／测试文件 SHA-256 与 `source-identity-save-tested-code.json` 一致；最终 XML 及计数／执行时间如下，全部 0 失败／错误／skip。
7. 发布：D3／D6、两份功能地图与 apply 指引已原位更新保存现状和剩余接线；核对 README、词表、接口／命令示例、配置／迁移及相关规格，无新增领域概念、wire、schema、依赖或公开入口，其余无需修改。没有新的用户业务取舍，保持撤回重复证明协议的既定边界，无需新增通用规范。Cross-platform Impact Check：共享准备门影响 Web／Native，执行以下单测／编译；无 UI、路由、交互或用户文案变化，UI 复用、Hallmark、Web 页面冒烟及设备 QA 不适用。按现行授权提交推送原分支，不归档、不创建 PR；后续继续组合元数据、公共编辑、关系精确幂等及完整命令，不将来源同值视为整项已应用。

| 实际任务 | 当前结果 |
|---|---|
| `:backend:test` 全量 | 193 类／1,176 项，全部 0 失败／错误／skip；来源身份类两库各 30 项，包括真实重导 no-op、旧隐藏值及停用账户、新 alias、软删绑定实际迁移、C 保留、晚期回滚及冲突 |
| `:business-domain:jvmTest` 全量 | 173 类／699 项，全部 0 失败／错误／skip |
| `:business-domain:jsBrowserTest`、`wasmJsBrowserTest` 全量 | ChromeHeadless，两端各 148 类／663 项，全部 0 失败／错误／skip；同一 alias 17 项实际执行 |
| domain `ktlintCheck`、`detektMetadataCommonMain`、`detektJvmTest`；backend `ktlintCheck`、`detektMain`、`detektTest`、`installDist` | 全部通过 |
| domain `compileAndroidMain`、`compileKotlinIosArm64`、`compileTestKotlinIosSimulatorArm64` | 编译通过，不宣称 iOS 运行或设备 QA |
| `openspec validate --all --strict`、`openspec doctor`、Markdown 本地链接、`git diff --check` | 27/27、健康、无缺失、通过 |

仓库外证据目录 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`。最终 XML 为 `source-identity-save-final-<模块>-<任务>-xml`，实际计数／时间范围与双库分组见 `source-identity-save-final-results.json`。测试库、完整环境、Gradle 参数和全任务命令与“首次写集提交命令组合验证”相同，输出为上述 `source-identity-save-matrix.log`；显式使用 `ledger_regression_1114_test`，测试不自动探测或启动数据库。上述来源／绑定阶段及 JSON 精确值、账户、跨工作区拒绝沿相同生产阶段和准备函数双库验证；完整 Application Service 的身份幂等组合、完整导入性能仍归原未完成任务，不以本片窄范围结果替代。


### 来源身份的完整阶段接线验证

2026-11-14（Asia/Shanghai），比较基线及验证 HEAD 为 `89c47a3b` 加本片工作树，分支 `worktree-offline-first-ledger-import`。沿 A 类 D3／D6，保留所有原业务、最终增量与客户端观察合同。

1. 思考：来源／绑定真实保存已消费身份映射，元数据、文件条目拓扑、C／B、关系及内部提交仍按原 New／Existing 判断归属或重复自然键。本片将同一视图贯穿既有阶段，验证实际提交服务，不增加证明协议。
2. 计划：按实际父项索引 source writes、旧 owner、自引用、候选及关系批次；原 DTO、New／空修订、显式 Existing 原观察保持。C 按真实写前 owner 合并，B 保留原逐选择组与顺序，关系键／父项键独立；完整公共结果仍由实际协调／D 核对。source 同值回执为 unchanged，不吞掉本次其他效果。
3. 任务拆分：先在同一真实提交服务复现重导失败，再覆盖精确 no-op、真实旧依赖、C、新 alias／映射／问题、首次关系公共效果、拒绝与各阶段回滚；共享纯阶段在各端执行同一边界场景。
4. 构建：`source-stage-alias-red.log` 的 3 场景 × 双库共 6 项全部失败，复现原完整提交拒绝精确来源重导、带 C 的重导及新增 alias／映射。现在元数据 owner／候选及退役、最终映射、文件拓扑、C 合并、联合批次／公共选择和首次关系均接同一 aliases。anchor 按实际父项判角色，回执按真实来源命中报告 unchanged。新增服务 7 方法及共享 6 方法；服务回归核实际 ID、完整事实／余额／版本、新效果与 8 个晚期阶段整体回滚。旧调用仅将 resolve 命名传参适配默认参数，未改原 B 逐组次数、顺序或成员独立值语义。
5. 审查：独立 5a 按产品编码规范 §7 核输入／异常、实际身份及原依赖、C 合并、原 B 次序、实体 key 隔离、最终效果及范围；实际 5b 经 codebase-degradation-review 调度独立 child `refresh_review/source_contract` 执行 contract-design-review。源码及新增服务／共享测试无阻断 finding；文档复核指出纯准备器两处“未接事务”旧措辞与“一次 B”歧义（P3），已原位说明内部服务已组合、B 按原选择组顺序执行。完整关系整批幂等、候选替代图及完整实际身份响应未在本片实现或宣称完成；无 UI 专项范围。
6. 测试与 QA：聚焦 `source-stage-alias-focused.log`、`source-stage-alias-service.log` 及 `source-stage-alias-common-final.log` 通过；当前 16 个 Kotlin 实现／测试文件 SHA-256 与 `source-stage-alias-tested-code.json` 一致，完整 `source-stage-alias-matrix.log` 在 6 分 38 秒完成，113 任务（33 执行、80 up-to-date）通过。服务类两库各 28 项（本片新增各 7），来源身份类各 30 项；共享 alias 类三端各 23 项（本片新增各 6）。以下 XML 全部 0 失败／错误／skip。初次共享夹具的 coverage 归一及无关不完整 entries 断言已修正，仅最终日志及全量矩阵计为通过；不以夹具失败冒充行为先红。
7. 发布：D3／D6、两份功能地图、apply 指引及当前任务摘要已原位更新；核对 README、词表、接口／命令示例、配置／迁移及相关规格，无新增领域概念、wire、schema、依赖、HTTP 或 CLI，其余无需修改。用户要求撤去额外账户映射证明协议，继续保持最终增量与真实数据库不变量的分工；现有规范已覆盖，无需新增通用规则。Cross-platform Impact Check：共享纯门影响 Web／Native，执行以下单测／编译；无 UI、路由、交互或用户文案变化，UI 复用、Hallmark、Web 页面冒烟及设备 QA 不适用。按现行授权提交推送原分支，不归档、不创建 PR。整个 change 与 3.2／3.4 等完整服务任务仍未完成；后续须补关系整批幂等连同 B 消费、完整候选替代联合图、关系问题及完整身份响应、公开服务与端侧完整接线。

| 实际任务 | 当前结果 |
|---|---|
| `:backend:test` 全量 | 193 类／1,190 项；实际 SQLite／真实 PostgreSQL 服务各 28 项、来源身份类各 30 项，全部 0 失败／错误／skip |
| `:business-domain:jvmTest` 全量 | 173 类／705 项，全部 0 失败／错误／skip |
| `:business-domain:jsBrowserTest`、`wasmJsBrowserTest` 全量 | ChromeHeadless 157，两端各 148 类／669 项，全部 0 失败／错误／skip；同一 alias 类各 23 项实际执行 |
| domain `ktlintCheck`、`detektMetadataCommonMain`、`detektJvmTest`；backend `ktlintCheck`、`detektMain`、`detektTest`、`installDist` | 全部通过 |
| domain `compileAndroidMain`、`compileKotlinIosArm64`、`compileTestKotlinIosSimulatorArm64` | 编译通过，不宣称 iOS 运行或设备 QA |
| `openspec validate --all --strict`、`openspec doctor`、Markdown 本地链接、`git diff --check` | 27/27、健康、88 个本地链接目标无缺失（未新增或修改锚点）、通过 |

仓库外证据目录 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`。最终 XML 为 `source-stage-alias-final-<模块>-<任务>-xml`，实际计数／时间及双库分组见 `source-stage-alias-final-results.json`，代码 SHA 见上述 tested-code JSON。完整测试库、环境及 Gradle 命令与“首次写集提交命令组合验证”相同：显式 source 仓库外 `postgres.env` 使用 `ledger_regression_1114_test`，JAVA_HOME 为 Temurin 21，NODE_ENV=development、CHROME_BIN 指向本机 Chrome；通过 `quality.init.gradle`、`--continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache`、in-process Kotlin 及 3 GiB JVM 执行上表全部 Gradle 任务。测试不探测或启动数据库；内部服务的这组结果不替代完整关系幂等、完整导入性能或最终页面验收。


### 关系整批身份消费验证

2026-11-14（Asia/Shanghai），比较基线及验证 HEAD 为 `e75b6403` 加本片工作树，分支 `worktree-offline-first-ledger-import`。沿 A 类 D3／D6。保持原 existingGroups 同值、完整联合组及逐选择组 B 语义，不新增 wire 或启发式重跑。

1. 思考：原来源身份已贯穿内部提交，重复关系仍作为新边进入最终图而失败；按原键收据重放不覆盖新的提交键或另一设备的数据层重复。
2. 计划：先校原引用／旧观察与输入，再按真实边键／金额／状态／角色／锚点／证据／候选匹配完整批次及存量联合成员。完整命中整体消费关系与 B，部分或异值冲突拒绝。原请求用于回执，执行副本仅移除完整命中关系组。
3. 任务拆分：同一真实服务双库先红覆盖新键重放及 B 不重复；追加完整联合／部分与异值拒绝、候选／旧观察、独立首次批次、新增其他效果、原键响应丢失及晚期回滚，核实际关系 ID、全库事实／版本／余额。
4. 构建：`relation-replay-red.log` 的 3 场景 × 双库共 6 项在真实再次提交处失败；现已组合完整关系批次身份解析与仅删除完整命中组的执行副本，原位置回执保存实际 ID／duplicate。实际旧引用及完整基础输入门在消费前执行；未命中独立批次沿原首次 writer，其他来源、C、元数据、映射及条目不删。新增服务 14 方法覆盖精确重放、C／映射共存、draft 候选、部分／异值／inactive joint／同批一新一旧拒绝、独立批次、7 阶段回滚和原键核实；其中来源失效边界直接运行同一生产 source／identity 阶段，明确不冒称完整命令。
5. 审查：独立 5a 按产品编码规范 §7 核原观察、写后事实、完整联合组、输入预算、B 消费、事务／savepoint 围栏、异常、复杂度及最终 diff；实际 5b 经 codebase-degradation-review 调度独立 child `refresh_review/source_contract` 执行 contract-design-review。两项 P2 已关闭：① duplicate 路径遗漏原 writer 文本／标量门，原 inputValues 抽取供首次及身份解析共用，审计文本不加入事实相等；② draft 候选必须核当前 SQL live posted 修订、同批来源变化的端点／anchor，以及来源保存对未声明旧候选的原失效语义。`relation-replay-review-red.log` 对中间实现定向复现 6 项，`relation-replay-invalidation-red.log` 复现原 source 阶段 4 项；均已修复并聚焦转绿。原 primitive 按源行顺序调用，`relation-replay-per-row-red.log` 双库 2 项证明集中调用把预期修订 4 错推进为 3，现恢复原逐行 +2；不刷新候选 expected revision。最终独立增量复核无新阻断。公共一致性门共享提取而不新增 wire；无 UI 专项范围。
6. 测试与 QA：聚焦 `relation-replay-focused-final.log` 在 15 秒通过，服务类双库各 42 项及共享 B／batch 18 项全部通过；当前 8 个 Kotlin 文件 SHA-256 与 `relation-replay-tested-code.json` 一致。完整 `relation-replay-matrix.log` 在 6 分 35 秒、113 任务（32 执行、81 up-to-date）通过。服务类每库新增 14 项，其中 9 项真实 submit 组合、5 项明确来源／身份阶段边界；不把低层阶段测试计作完整 Application Service 合同。完整计数及范围如下，全部 0 失败／错误／skip。
7. 发布：D3／D6、两份功能地图、apply 指引及当前 3.x 任务摘要已原位核对实际接线和剩余边界；核对 README、词表、接口／命令示例、配置／迁移及相关规格，无新增领域概念、wire、schema、依赖、HTTP 或 CLI，其余无需修改。用户已要求回退额外映射证明协议，继续保留最终增量／真实数据库不变量的原分工；原修订和候选失效行为已有规格覆盖，无需新增通用规则。Cross-platform Impact Check：服务端身份解析不改 Web／Native 交互，共享 B 一致性提取由 JVM／JS／Wasm 执行并作原生编译；无 UI、路由或文案变化，UI 复用、Hallmark、Web 页面冒烟和设备 QA 不适用。按现行授权提交推送原分支，不创建 PR。整个 change 与完整 3.x 保持未完成，不归档：完整候选替代图、关系问题、全部实体效果幂等／完整身份响应及公开入口／端侧仍待完成。


| 实际任务 | 当前结果 |
|---|---|
| `:backend:test` 全量 | 193 类／1,218 项；提交服务类 SQLite／真实 PostgreSQL 各 42 项，来源身份类各 30 项，全部 0 失败／错误／skip |
| `:business-domain:jvmTest` 全量 | 173 类／705 项，全部 0 失败／错误／skip |
| `:business-domain:jsBrowserTest`、`wasmJsBrowserTest` 全量 | ChromeHeadless 157，两端各 148 类／669 项，全部 0 失败／错误／skip；共享 B 一致性 11 项及 batch 7 项三端实际执行 |
| domain `ktlintCheck`、`detektMetadataCommonMain`、`detektJvmTest`；backend `ktlintCheck`、`detektMain`、`detektTest`、`installDist` | 全部通过 |
| domain `compileAndroidMain`、`compileKotlinIosArm64`、`compileTestKotlinIosSimulatorArm64` | 编译通过，不宣称 iOS 运行或设备 QA |
| `openspec validate --all --strict`、`openspec doctor`、Markdown 本地链接、`git diff --check` | 27/27、健康、90 个本地目标无缺失（未改锚点）、通过 |

仓库外证据目录 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`。最终 XML 为 `relation-replay-final-<模块>-<任务>-xml`，实际计数／时间及双库分组见 `relation-replay-final-results.json`，代码 SHA 见上述 tested-code JSON。完整测试库、环境和 Gradle 任务与“来源身份的完整阶段接线验证”相同：显式 source 仓库外 `postgres.env` 使用 `ledger_regression_1114_test`；Temurin 21、NODE_ENV=development、CHROME_BIN 为本机 Chrome，`quality.init.gradle`、`--continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache`、in-process Kotlin 及 3 GiB JVM 执行上表全部任务。测试不探测或启动数据库。身份解析复用完整已读取事实，建立边键／候选／联合组索引；新批次仍复用原 writer，已有来源失效按原行序查询。完整导入性能、候选替代图及全部实体效果仍归未完成任务，不以本片窄范围替代。


## 最终账户映射精确身份验证

比较基线：`cf431b6e`；范围属于已确认的最终增量与数据层幂等，不新增证明字段或恢复发布重演。

1. 思考：核对原映射 publish 的同自然键同账户 no-op、lookup 实际 ID、共享引用门与现有 delta 的同值合同。现有引用门将新引用的全部自然键命中拒绝，阻断合法同值重导；独立只读契约评估确认属于既定合同补齐。
2. 计划：只在引用预检内部按真实事实归一精确映射身份，新引用及空修订保持；不同账户、旧观察失效、重复声明及不合资格账户保持拒绝。复用原保存仓储，不新增 wire、schema、来源观察、客户端发布算法或公共别名模型。
3. 任务拆分：先失败覆盖共享精确命中／重复身份／异值与修订，以及同一实际提交服务的重复导入、异值拒绝、其他新效果及晚期回滚；再实施并跑跨端和双库矩阵。
4. 构建：共享引用门只增加私有精确映射身份归一，作用于目标去重与自然键检查；原保存仓储及 DTO 不变。先失败已复现共享同值命中和实际提交双库拒绝，聚焦 JVM／双库已转绿；独立复核及下方完整矩阵通过。
5. 审查：独立 5a 产品／范围、工程（产品编码规范 §7）／安全／最终 diff 及实际 5b 契约复核通过，无新增 finding。复核覆盖 New／空修订不变、原 Existing 观察、双新／混合重复声明、账户资格、自然键异值、程序损坏和确认信息；完整矩阵与文档证据已核对；无 UI 变更。
6. 测试与 QA：下方 SQLite／真实 PostgreSQL 同服务矩阵、共享 JVM／JS／Wasm 实际单测、Android／iOS 相称编译、质量检查及 OpenSpec 校验通过。
7. 发布：D3、导入 delta 的既定同值场景、两份功能地图及 apply 指引已同组更新；核对 README、词表、接口／命令示例、配置／迁移及相关规格，无新术语、wire、schema、依赖、HTTP 或 CLI，其余无需改动。用户要求撤去额外账户映射证明协议，本片只补现有最终增量的精确幂等，不恢复额外传输或服务端发布重演；无需新增通用规范。Cross-platform Impact Check：共享引用门影响 Web／Native，由下方实际测试及编译验证；无 UI、交互、路由或用户文案变化，Web 页面冒烟、UI 复用、Hallmark 及设备 QA 不适用。按既有授权提交推送原任务分支，不归档、不创建 PR，整个 change 仍未完成。


验证时间：2026-11-14（Asia/Shanghai），比较基线及验证 HEAD 为 `cf431b6e` 加本片工作树，目标分支 `worktree-offline-first-ledger-import`。证据保存在仓库外 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`；`mapping-identity-tested-code.json` 固定本片 5 个 Kotlin 文件的 SHA-256。新服务的 4 个方法在两库各执行一次，另在原来源保存阶段方法中核对 New 同值返回原实际 ID；后者不冒充完整命令矩阵。

先失败证据：`mapping-identity-red.log` 与 `mapping-identity-red-xml` 保留共享引用／映射的 3 个实际失败；`mapping-identity-red-corrected.log` 及其 XML 保留修正账户重名夹具后的实际提交 3 方法 × 双库共 6 个失败，均在同值映射的前置引用拒绝处。`mapping-identity-fixture.log` 因方法筛选未命中而无测试执行，不计行为证据。最小实现后聚焦 `mapping-identity-focused.log` 32 秒通过，含映射 8、引用 18、来源阶段 102（每库 51）和提交 92（每库 46）项，0 失败／错误／skip。

最终 `mapping-identity-matrix.log` 6 分 42 秒成功，113 tasks（32 executed／81 up-to-date）。命令前显式读取仓库外 `postgres.env`，使用专用 `ledger_regression_1114_test`（localhost:55441）；测试不自动启动或探测数据库。`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`、`NODE_ENV=development`、`CHROME_BIN=/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`；公共命令为 `./compose/gradlew -p compose -I <证据目录>/quality.init.gradle --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.compiler.execution.strategy=in-process`，JVM 参数为 `-Xmx3072M -Dfile.encoding=UTF-8`。

| 实际执行范围 | 结果 |
|---|---|
| `:backend:test`，同一真实提交服务覆盖 SQLite／PostgreSQL | 193 类／1226 方法，0 失败／错误／skip；提交类每库 46 方法，包含本片 4 项 |
| `:business-domain:jvmTest` | 173 类／708 方法，0 失败／错误／skip |
| `:business-domain:jsBrowserTest`、`:business-domain:wasmJsBrowserTest`，ChromeHeadless | 各 148 类／672 方法，0 失败／错误／skip；映射各 8、引用各 18 方法实际运行 |
| `:business-domain:compileAndroidMain`、`:business-domain:compileKotlinIosArm64`、`:business-domain:compileTestKotlinIosSimulatorArm64` | 受影响共享层编译通过；不计原生设备运行 |
| `:business-domain:ktlintCheck`、`:business-domain:detektMetadataCommonMain`、`:business-domain:detektJvmTest`、`:backend:ktlintCheck`、`:backend:detektMain`、`:backend:detektTest`、`:backend:installDist` | 全部通过 |

最终 XML 固定在 `mapping-identity-final-<模块>-<任务>-xml`，数量、时间及选中测试见 `mapping-identity-final-results.json`，代码 SHA 核对一致。真实合同覆盖精确 New 同值不改变映射 ID／确认人／时间／修订／快照／版本，原键重放不写，映射命中后的独立来源仍保存；在映射、条目及收据后故障整体回滚。同键异账户、双新／新旧重复声明、停用账户及陈旧 Existing 拒绝，合法 Existing 修改仍沿原修订推进。shared 超 JS 安全整数 ID 与 Long.MAX_VALUE 修订保持精确；账户映射只按原完整自然键，未新增 SQL 或算法搜索，额外索引扫描保持线性。

`mapping-identity-openspec.log` 记录 `openspec validate --all --strict` 27/27，`mapping-identity-doctor.log` 正常；91 个 Markdown 本地目标无缺失（`mapping-identity-links.json`），无既有锚点改动，`git diff --check` 通过。完整候选替代图的合同收敛、关系问题、完整身份响应、公开服务与端侧接线仍未完成；本片不关闭 3.2／3.4／3.6 或整个 change。


## 来源问题精确身份验证

比较基线：`749a11e9`；属于既有来源问题保存与数据层幂等合同，不新增 wire 或来源证明。

1. 思考：核对原 rowIssues 的歧义空候选保留、saveIssue 的正文／完整候选同值语义、来源别名及前置引用拒绝。独立只读评估确认同值新引用应复用实际问题身份，原歧义保留不因传输新旧引用而改变。
2. 计划：以同事务真实事实计算来源问题精确身份，保持 New／空修订与 Existing 原观察；预算先于 JSON 深解码，复用既有数值相等。排除显式退休后重建与 relation: 问题；非保留分支在写后实际候选修订变化时拒绝，同键异值及重复声明保持拒绝。
3. 任务拆分：先失败覆盖同值／语义 JSON／完整候选及空候选保留、双新／混合／异值／旧修订／深度；同一实际提交服务验证重导不写、候选变动拒绝、独立效果、退役重建与整体回滚。
4. 构建：共享来源问题身份解析已贯穿引用预检、元数据准备及实际保存；原 DTO、来源别名模型和仓储 DML 不变。共享 5 项及提交双库 10 项先失败已复现，聚焦单测转绿；独立复核及下方完整矩阵通过。
5. 审查：独立 5a 产品／范围、工程（产品编码规范 §7）／安全／最终 diff 与实际 5b 契约复核通过，无新增 finding；新增身份解析文件已完整纳入。原修订、完整候选观察、写后修订、JSON 预算／数值、空候选保留、退休重建及错误传播均已核对；完整矩阵和文档证据已核对。
6. 测试与 QA：下方真实 SQLite／PostgreSQL 同服务合同、共享 JVM／JS／Wasm 实际单测、原生相称编译及质量检查通过。
7. 发布：D3、导入 delta 的既有问题同值／歧义保留场景、两份功能地图及 apply 指引已同组更新；核对 README、词表、接口／命令示例、配置／迁移及相关规格，无新术语、wire、schema、依赖、HTTP 或 CLI，其余无需改动。沿用户撤回额外证明契约的决定，只核既有最终增量与真实事实，不新增原文件真实性或发布重演；当前规范已覆盖，无需新增通用规则。Cross-platform Impact Check：共享引用／元数据准备影响 Web／Native，由下方实际单测与编译覆盖；无 UI、交互、路由或用户文案变化，Web 页面冒烟、UI 复用、Hallmark 及设备 QA 不适用。按既有授权提交推送原任务分支，不归档、不创建 PR，整个 change 仍未完成。


验证时间：2026-11-14（Asia/Shanghai），比较基线及验证 HEAD 为 `749a11e9` 加本片工作树，目标分支 `worktree-offline-first-ledger-import`。仓库外证据目录为 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`；`issue-identity-tested-code.json` 固定本片 7 个 Kotlin 文件（含新增生产解析文件）的 SHA-256，完整矩阵后核对一致。新增提交服务 6 方法在两库实际各执行一次，另在原来源元数据阶段方法中验证 New 同值返回原实际问题 ID 与整库不变；后者不冒充完整命令合同。

先失败证据 `issue-identity-red.log` 与 `issue-identity-red-xml`：共享 14 方法中 5 失败、实际提交 104 方法中 10 失败（5 × 双库），均在同值 New 问题的前置引用拒绝处，0 error／skip。最小实现后 `issue-identity-focused.log` 33 秒成功，包含来源元数据 14、引用 18、来源阶段 102（每库 51）、提交 104（每库 52），0 失败／错误／skip；随后新增来源阶段实际 ID 断言由最终矩阵执行。

最终 `issue-identity-matrix.log` 6 分 48 秒成功，113 tasks（34 executed／79 up-to-date）。命令前显式读取仓库外 `postgres.env`，数据库为 localhost:55441 的专用 `ledger_regression_1114_test`，测试不启动或探测数据库。使用 `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`、`NODE_ENV=development`、`CHROME_BIN=/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`。公共命令为 `./compose/gradlew -p compose -I <证据目录>/quality.init.gradle --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.compiler.execution.strategy=in-process`，JVM 参数为 `-Xmx3072M -Dfile.encoding=UTF-8`。

| 实际执行范围 | 结果 |
|---|---|
| `:backend:test`，同一真实提交服务的 SQLite／PostgreSQL 合同 | 193 类／1238 方法，0 失败／错误／skip；提交每库 52 方法，含本片新增 6 项 |
| `:business-domain:jvmTest` | 173 类／713 方法，0 失败／错误／skip |
| `:business-domain:jsBrowserTest`、`:business-domain:wasmJsBrowserTest`，ChromeHeadless | 各 148 类／677 方法，0 失败／错误／skip；来源元数据各 14、引用各 18 方法实际执行 |
| `:business-domain:compileAndroidMain`、`:business-domain:compileKotlinIosArm64`、`:business-domain:compileTestKotlinIosSimulatorArm64` | 受影响共享层编译通过，不计原生设备运行 |
| `:business-domain:ktlintCheck`、`:business-domain:detektMetadataCommonMain`、`:business-domain:detektJvmTest`、`:backend:ktlintCheck`、`:backend:detektMain`、`:backend:detektTest`、`:backend:installDist` | 全部通过 |

完整 XML 固定在 `issue-identity-final-<模块>-<任务>-xml`，实际数量、时间及选中方法见 `issue-identity-final-results.json`。合同验证同值 New 保留问题 ID／候选行 ID／修订／时间／账本快照／版本及原键重放；JSON `1`／`true`、小数词法等价沿原数值语义，大于 JS 安全整数不舍入。完整候选集合忽略顺序但不允许重复；非保留分支的同批来源更新不能刷新旧问题证明，带原问题观察的 Existing 更新仍可合法保存实际候选修订。空歧义候选在结构和预算门通过后保留整项及陈旧观察，不删除或刷新旧候选。独立新来源仍保存，元数据、条目及收据后的故障整体回滚；异值／重复／陈旧 Existing 拒绝，显式退休后 New 同键仍创建新问题。共享回归包含深度、未允许 origin 键、超 JS 安全整数身份、Long.MAX_VALUE 修订、真实来源别名以及损坏候选唯一性。私有索引按来源问题与候选线性构建，不新增 SQL 或候选搜索；既有同值保存仍由原仓储判断。

`issue-identity-openspec.log` 记录 strict 27/27，`issue-identity-doctor.log` 正常，91 个 Markdown 本地目标无缺失（`issue-identity-links.json`），无既有锚点改动，`git diff --check` 通过。独立 5a／实际 5b 无遗留 finding；完整候选替代图合同收敛、关系问题、完整实际身份响应、公开提交与端侧管线仍待完成，不关闭 3.2／3.4／3.6 或整个 change。


### 实际身份收据的模型与永久存读（3.4／3.6，内部切片）

比较基线 `b92840fd`，延续 A 类 change 的既定实际身份结果合同，独立完成收据存读，不改变账户映射或来源证明边界。

1. 思考：现内部服务只保存流水／关系的位置结果；完整身份组装依赖尚未实现的关系问题阶段。复用 RESULT_SUMMARY，无新表、业务状态或客户端算法输入。
2. 计划：D3 定义可选闭合结果快照，严格字符串身份／修订、原请求位置、旧摘要与新结果独立预算、原键永久读取及损坏拒绝。
3. 任务拆分：先用旧模型及 OpenAPI 写失败回归，再实现共享 DTO、同事务存储、永久读取及同组 schema；双库验证原 ID／键／重放、后来变化及损坏结果，实际执行两条公开 GET；完整组装仍待后续依赖完成。
4. 构建：`receipt-identity-red.log` 中共享 5 方法失败 2，真实双库 10 方法失败 6；分别复现合法结果拒绝与损坏结果静默丢弃。实现后 `receipt-identity-focused.log` 10 秒通过，共享 7 方法、双库各 6 方法，0 失败／错误／skip。不开放 HTTP，不向现内部命令添加半份结果。
5. 审查：实施前独立上下文确认复用单一收据快照属于既定合同接线，无额外证明或产品取舍。独立 5a 已按产品规则 §7 检查输入、永久结果、复杂度与错误传播；实际独立子上下文 5b 契约审查已沿模型、summary、仓储所有读取及公共服务复核，无新增 finding。无需 UI 复用审查；完整组装及关系问题不在本片范围。新增容器复制和真实事实删除后读取回归已覆盖非阻断建议；混合转义及多字节 UTF-8 恰好 64 MiB／超一字节边界已通过。
6. 测试与 QA：比较基线 `b92840fd` 加本片工作树，2026-11-14（Asia/Shanghai）。证据根为 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`，`receipt-identity-tested-code.json` 记录受测 Kotlin／资源 SHA；测试使用显式 PostgreSQL `_test` 库与仓库外 `postgres.env`，未自动探测或启动数据库。原失败 XML 固定在 `receipt-identity-red-xml`；OpenAPI 缺字段红回归在 `receipt-identity-openapi-red.log`／`.xml`，模型及两条公开 HTTP 的定向 27 方法在 `receipt-identity-openapi-green.log` 7 秒通过。

完整受影响矩阵 `receipt-identity-matrix.log` 成功，6 分 48 秒，134 任务（51 执行／83 up-to-date）。调用 `./compose/gradlew -p compose -I <证据根>/quality.init.gradle`，共用 `--continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'`，JDK 为 Temurin 21，浏览器为 ChromeHeadless。

| 实际任务 | 结果 |
|---|---|
| `:business-contracts:jvmTest`、`:business-contracts:jsBrowserTest`、`:business-contracts:wasmJsBrowserTest` | 各 11 类／34 方法，0 失败／错误／skip；新增严格身份结果各 7 方法执行 |
| `:business-domain:jvmTest` | 173 类／713 方法，0 失败／错误／skip |
| `:business-domain:jsBrowserTest`、`:business-domain:wasmJsBrowserTest` | 各 148 类／677 方法，0 失败／错误／skip |
| `:business-contracts:compileAndroidMain`、`:business-contracts:compileKotlinIosArm64`、`:business-contracts:compileTestKotlinIosSimulatorArm64`，及同名 `:business-domain` 任务 | 全部通过，只计相称编译 |
| `:business-contracts:ktlintCheck`、`:business-contracts:detektMetadataCommonMain`、`:business-contracts:detektJvmTest`，及同名 `:business-domain` 任务 | 全部通过 |
| `:backend:test`、`:backend:ktlintCheck`、`:backend:detektMain`、`:backend:detektTest`、`:backend:installDist` | `receipt-identity-final-backend.log` 4 分 5 秒通过，24 任务（8 执行／16 up-to-date）；194 类／1251 方法，0 失败／错误／skip；新收据各库 6 方法，OpenAPI 15 方法 |

共享代码未随最终资源补齐而变化，原矩阵 XML 固定在 `receipt-identity-final-<模块>-<任务>-xml`，不重跑无关矩阵；最终后端 XML 同样固定，`receipt-identity-final-results.json` 记录实际方法与时间，受测字节一致。身份快照在真实来源／条目删除后仍经两条公开 HTTP、直接读取及原键重放返回；错误 operation、损坏结果、输入容器后续修改、空结果、超 JS 安全整数／Long 上限和恰好／超出预算已覆盖。新校验仅线性遍历有界结果，不新增业务查询；64 MiB 多字节及转义边界用独立标准编码器计算，后端 12 方法整体约 1 秒。
7. 发布：已核对 D3、tasks、backend README、OpenAPI、两份功能地图、词表及 apply 指引；接口示例、运行配置／迁移无新增内容，其余 README／参考文档无需修改。README 区分内部真实版本已接与完整公开服务未接；共享响应模型已提供，完整组装及客户端消费仍待完成。Cross-platform Impact Check：Web／Native 共用新合同，以 JVM、实际 JS／Wasm 浏览器单测及相称原生编译验证；没有页面、路由、交互或用户可见文案变化，UI 复用、Hallmark、产品页面冒烟及原生设备 QA 不适用。继续保留用户决定的精简映射增量，不新增来源证明或通用规范。独立最终证据门已通过，日志终态、固定 XML、9 个 Kotlin 文件及 OpenAPI 资源 SHA 与当前工作树一致；strict 27/27、doctor、92 个本地文档目标及 diff 检查通过，无遗留 finding。按现行授权提交推送同分支；整体 change 和 3.4／3.6 保持未完成，不归档或创建 PR。


### 已有关系问题的保留核对（3.2／3.4，内部切片）

比较基线 `5be9e7f6`。

1. 思考：主规格要求非法自动组丢弃、明确选择失败拒绝；生产 GroupRules／DecisionRules 返回空失败集合。旧 writer 的 joint_skip／discovery 夹具绕过 builder，不能据此扩展文件入口。普通文件 OperationRules 保留全部旧 relation: 问题。当前 metadata 准备器无条件让行而保存器过滤，会静默忽略变更及漏回已有身份。
2. 计划：沿 D3 仅保留；省略不删除，显式 Existing 核 owner／key／原观察及完整内容后返回实际 ID，无来源写项亦可保留；新建／变更／退役拒绝，无新 wire 或启发式。
3. 任务拆分：先写共享及双库失败回归，最小修复准备／保存门；原问题及候选、版本、时间和原键结果不得改变，拒绝时整体回滚。
4. 构建：`retained-relation-issue-red.log` 中共享 14 方法失败 1；`retained-relation-issue-backend-red.log` 中选中 14 方法失败 4（两库各 2），失败 XML 已固定。`retained-relation-issue-focused.log` 41 秒通过共享 JVM 全部 713 方法和来源 106 方法；`retained-relation-issue-composition.log` 24 秒通过来源 106 与完整命令 108 方法，0 失败／错误／skip。
5. 审查：独立必要性评估已核生产调用链、主规格、历史直接 writer 夹具及保留规则。实现后的独立 5a（产品规则 §7）及实际子上下文 5b 已沿真实旧观察、owner／key、JSON、候选、事务与返回身份复核，无代码 finding；技能中的“候选读取”已收敛为“当前候选查询”，将已接的旧问题保留从未完成阻断中移除，两项 P3 措辞原位关闭。无 UI，不触发 UI 复用或 Hallmark。
6. 测试与 QA：2026-11-14（Asia/Shanghai），受测基线为 `5be9e7f6` 加本片工作树，5 个 Kotlin 文件 SHA 记录在仓库外 `offline-ledger-1114/retained-relation-issue-tested-code.json`。真实 PostgreSQL 使用同一显式 `_test` 库及外部 `postgres.env`，测试未自动探测或启动数据库。`retained-relation-issue-matrix.log` 全部成功，6 分 31 秒，113 任务（32 执行／81 up-to-date）；JDK 为 Temurin 21，浏览器为 ChromeHeadless。

实际命令为 `./compose/gradlew -p compose -I <证据根>/quality.init.gradle`，携带本页收据矩阵相同的 `--continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'`。

| 实际任务 | 结果 |
|---|---|
| `:backend:test` | 194 类／1259 方法，0 失败／错误／skip；来源各库 53、完整命令各库 54 方法 |
| `:business-domain:jvmTest` | 173 类／713 方法，0 失败／错误／skip |
| `:business-domain:jsBrowserTest`、`:business-domain:wasmJsBrowserTest` | 各 148 类／677 方法，0 失败／错误／skip；新版保留回归在两浏览器实际执行 |
| `:business-domain:compileAndroidMain`、`:business-domain:compileKotlinIosArm64`、`:business-domain:compileTestKotlinIosSimulatorArm64` | 全部通过，只计原生编译 |
| `:business-domain:ktlintCheck`、`:business-domain:detektMetadataCommonMain`、`:business-domain:detektJvmTest`、`:backend:ktlintCheck`、`:backend:detektMain`、`:backend:detektTest`、`:backend:installDist` | 全部通过 |

最终 XML 固定为 `retained-relation-issue-final-<模块>-<任务>-xml`；实际方法、时间及计数在 `retained-relation-issue-final-results.json`，当前受测字节一致。覆盖无来源写项的真实 ID 返回、整库／候选／修订／时间／快照／版本不变、Long.MAX_VALUE 问题修订、创建／改写／退役拒绝、独立来源／条目／收据整体回滚及原键原版本重放。纯准备对旧事实线性索引，不新增 SQL；保留分支不读取当前候选修订或调用问题 writer。
7. 发布：已按实际范围原位核对 D3、当前 3.x 任务摘要、backend README、两份功能地图及 apply 指引，明确旧失败 writer 不可达与旧问题保留已接，完整身份组装仍未完成。其余 README／参考资料、词表、接口示例、OpenAPI、配置和迁移无需变更：没有新字段、入口、依赖或术语。Cross-platform Impact Check：共享校验影响 Web／Native，以 JVM、真实 JS／Wasm 浏览器单测及原生相称编译覆盖；无页面、路由、交互或用户文案变化，UI 复用、Hallmark、产品页面冒烟与原生设备 QA 不适用。继续保留用户要求的精简映射增量，不将不可达辅助分支升级为新产品能力，不新增通用规范。独立最终证据门通过，固定 XML、5 个 Kotlin 文件 SHA、实际日志与文档一致，无遗留 finding；按现行授权提交推送当前任务分支，3.2／3.4 及整个 change 保持未完成，不归档、不创建 PR。


### 完整实际身份结果组装（3.4／3.6，内部切片）

比较基线 `8f21b733`。

1. 思考：沿 D3 已确定的永久身份响应，内部提交仍丢弃绑定、问题及映射阶段返回值，条目最终事实已读取却未用于收据；现有协议无需扩展。属于现有 A 类 change 的事务组合小步，不改变映射、来源或发现语义。
2. 计划：收集真实保存映射，复用协调和 D 后最终事实；按原冻结请求数组／组成项位置组装全部八类结果。原关系批次幂等命中仍返回实际身份；条目返回最终根、锚点与修订。仅报告提交前已保存且真正退休的旧条目及显式解决的问题，同事务保存结果与版本，永久读取不回查当前对象。
3. 任务拆分：先失败覆盖全实体、空命令、组成项次序、别名精确命中、C／B／D 最终修订、混合首次／重复关系、旧条目退休及问题替换；组合响应丢失、后续修改／删除、原键重放及晚期失败回滚。
4. 构建：共享合同未改；完整命令先红 112 方法中 20 失败，均因结果字段缺失，两库各 10；XML 已固定。结果组装后首轮聚焦 112 方法全部通过；追加换序及 identity_result 阶段故障覆盖后的 `assembled-identity-composition-final.log` 20 秒成功；完整命令 114 方法（两库各 57）与永久收据 12 方法（两库各 6）全部通过，0 失败／错误／skip，实际 XML 已固定；条目语义由完整命令中的 C／B／D、旧组退休及条目单独编辑验证，不额外声称不存在的 EntriesTest 类。
5. 审查：独立 5a（产品规则 §7）及实际子上下文 5b 已复核最终事实时点、原位置、别名、消费关系、退休与原子性，无代码 finding；QA 前另核旧空条目辅助器不读库断言，保留原空路径，完整命令在外层补一次最终事实读取，独立增量复核确认职责和原空路径均保持，可进入完整 QA。无 UI 变化。
6. 测试与 QA：2026-11-14（Asia/Shanghai），受测基线为 `8f21b733` 加本片工作树，4 个 Kotlin 文件 SHA 固定在仓库外 `offline-ledger-1114/assembled-identity-tested-code.json`，当前字节一致。真实 PostgreSQL 沿同一显式 `_test` 库与外部 `postgres.env`，测试未自动探测／启动数据库。`assembled-identity-matrix.log` 全部成功，4 分 28 秒，24 任务（14 执行／10 up-to-date），运行时 Temurin 21。

实际命令为 `./compose/gradlew -p compose -I <证据根>/quality.init.gradle :backend:test :backend:ktlintCheck :backend:detektMain :backend:detektTest :backend:installDist`，携带本页前述 `--continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'`。

后端 194 类／1265 方法，0 失败／错误／skip；完整命令 114（各库 57）、永久收据 12（各库 6）均实际执行。历史条目仓储属于默认排除的 `HistoricalMigrationEvidence`，不计入 1265；另显式执行两库各 39 方法，包含空条目辅助器不读库原断言。质量检查及 installDist 全部通过。XML 固定在 `assembled-identity-final-backend-test-xml`，实际方法和时间见 `assembled-identity-final-results.json`。共享合同和规则字节未改，原跨端合同矩阵沿原已验证字节，不计本片新增跨端实现，不重跑无关浏览器／原生构建。

历史专项命令将任务改为 `:backend:migrationEvidenceTest --tests '*CashEntryRepositoryMigrationTest*'`，其余 Gradle 参数同上，分别明确设置 `FT_KOTLIN_CONTRACT_DIR=<证据根>/sqlite` 与 `<证据根>/postgres`。首次 SQLite 39 失败均停在缺失合成会话的鉴权入口；仓库外手工准备固定 synthetic-admin 测试会话和 fixture-a 基础工作区，不改变财务事实、应用或测试源码，不由测试自动探测／启动数据库。`assembled-identity-original-sqlite-final.log` 7 秒、`assembled-identity-original-postgres-final.log` 8 秒全部通过，各 39 方法，0 失败／错误／skip；两库最终 XML 各固定，方法及时间同组写入最终结果文件。

组装对已读事实线性建索引，无逐行 SQL；有条目路径复用原最终读取，空条目命令只在外层补一次完整事实读取。实际执行覆盖数组及组成项换序、新引用同值真实身份、混合首次／重复关系、C／B／D 后最终根／锚点／修订、旧条目退休、问题退休重建、保留旧关系问题、空命令、晚期故障整体回滚、响应丢失和实际后续修改／删除后的永久结果。完整服务规模性能仍归后续整服务验收，不以本片用例代替。
7. 发布：已按实际结果原位核对 D3、当前 3.x 摘要、backend README、两份功能地图、词表及 apply 指引。接口／命令示例、OpenAPI、配置、迁移及其余 README／参考资料无需变化：沿用已有可选结果字段，无新协议、依赖或领域术语。Cross-platform Impact Check：仅 backend 原子结果组装变化，共享合同已在前片验证且字节未变，Web／Native 无新增代码或展示变化；UI 复用、Hallmark、页面冒烟和设备 QA 不适用。继续保持用户要求的精简映射合同，不新增通用规则。strict 27/27、doctor、92 个本地文档目标及 diff 检查通过；独立最终证据门通过：固定 XML、4 个 Kotlin 文件 SHA、实际日志和范围文档一致，无遗留 finding。按授权提交推送同分支，不归档、不创建 PR；3.4／3.6 和整个 change 保持未完成，公开提交、客户端消费、完整发现和页面仍须接通。


### 客户端收据保留实际身份快照（3.6，内部切片）

比较基线 `fb42c4f4`。

1. 思考：共享 wire 已能严格解码 write_set_result，但 CashMutationReceiptDto.toDomain 丢弃该值，按 ID／原键读取及核实无法向恢复流程交付实际身份。沿现有已确定响应合同修复映射，不增加业务选择。
2. 计划：ImportReceipt 直接保留共享闭合结果类型，避免镜像模型及数字转换；缺省旧结果仍为 null。映射和所有既有收据读取／核实消费同一值，不添加新请求或缓存写入。
3. 任务拆分：先写映射及真实 Ktor MockEngine 读取链路的失败回归，覆盖八类结果、超 JS 安全整数及 Long 上限、数组顺序、旧结果缺省；真实已提供的 ID／原键读取走 MockEngine，通用 resolve DTO 单独核映射，不假称新写集已可用旧 plan resolve 端点。严格坏结果继续拒绝且不触发刷新，不在映射中重复协议校验。
4. 构建：类型尾部先提供默认 null 以编译回归，行为修复前 `client-identity-red.log` 38 秒，6 方法中 2 失败，均为完整快照被映射丢成 null；红 XML 已固定。随后仅将共享结果传入现有收据模型；`client-identity-focused-final.log` 19 秒通过 host 四类 46 方法，0 失败／错误／skip，XML 已固定；坏结果不发布／不触发刷新，真实已提供的 ID／原键路径和通用 resolve DTO 映射分别覆盖。
5. 审查：正式独立 5a（产品规则 §7）及实际子上下文 5b 通过，无 finding。已核全部 ImportReceipt 构造与 Ktor 导入／维护收据路径共用 mapper、精确值及缺省、严格解码、发布／刷新前失败、取消及 IO 边界；直接复用结果 DTO 无独立业务生命周期，不引入八类镜像模型。无 UI，不触发 UI 复用或 Hallmark。
6. 测试与 QA：2026-11-14（Asia/Shanghai），基线 `fb42c4f4` 加本片工作树，3 个 Kotlin 文件 SHA 固定在仓库外 `offline-ledger-1114/client-identity-tested-code.json`，当前受测字节一致。`client-identity-focused-final.log` 的 `:shared:testAndroidHostTest --tests '*LedgerReceiptMappingTest*' --tests '*CashImportLifecycleRepositoryTest*' --tests '*CashMaintenanceApiTest*' --tests '*DemoCashImportLifecycleTest*'` 实际四类 46 方法通过。

`client-identity-matrix.log` 成功，1 分 58 秒，157 任务（29 执行／128 up-to-date）。实际命令为 `./compose/gradlew -p compose :shared:jsBrowserTest :shared:wasmJsBrowserTest :shared:compileAndroidMain :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosSimulatorArm64`，携带本页同样的 `--continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'`；Temurin 21、`NODE_ENV=development`、显式 Chrome 为 `/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`。

JS 97 类／812 方法、Wasm 99 类／818 方法，均 0 失败／错误／skip；host 与两个真实 ChromeHeadless 目标分别实际执行全部 7 个收据映射方法，时间／名称及计数固定在 `client-identity-final-results.json` 与 `client-identity-final-<任务>-xml`。Android 主代码与 host 单元、iOS 主代码及模拟器测试编译通过，只计相称编译，不计原生设备交互。shared 未配置 ktlint／detekt 任务，不新增无关插件，以范围 diff、独立工程复核和实际编译核对；`git diff --check` 通过。后端、SQL、协议 serializer 与金额规则未改，不重跑无关后端双库、质量检查或业务规则矩阵。MockEngine 只证明 Ktor 解码／仓储映射，不作为产品页面、真实服务端网络或公开新写集提交证据。
7. 发布：已核对并原位维护 D3、backend README、两份地图、词表及 apply 指引；其余 README／参考资料、公开接口示例、OpenAPI、配置和迁移无需修改，没有新端点、字段、依赖或领域术语。Cross-platform Impact Check：共享收据映射同时影响 Web／Native，已以 host、JS／Wasm 实际执行及 Android／iOS 相称编译覆盖；无页面、组件、路由或用户文案变化，不触发 UI 复用、Hallmark、产品页面冒烟或原生设备 QA。继续保持用户已确定的精简映射合同，无需新增通用规则。strict 27/27、doctor、92 个本地文档目标及 diff 检查通过；独立最终证据门通过，三目标固定 XML、3 个 Kotlin 文件 SHA、实际日志和范围文档一致，无遗留 finding。按授权提交推送同分支；该步只传递永久快照，不宣称公开提交、恢复应用或离线导入完整交付，不归档、不创建 PR，3.6 和整个 change 保持未完成。


### 候选方案与保存边界核对（3.2／3.4，既有行为证据）

比较基线 `f0002a8c`。

1. 思考：独立必要性复核已对照主规格“候选按完整方案区分展示及处理”、共享 GroupRules、旧 writer.candidateValues 与维护 planner。完整合法 choices 在发布时拍平为各边候选并集，不保留原组合相关性；因此服务端要求全部笛卡尔组合合法会拒绝原本合法建议，不属于本 change 的旧行为合同。
2. 计划：Flow-Back 原位澄清 D3 实施职责，保留完整方案的端侧筛选／单选及最终已确认图的服务端校验；不新增 wire、来源证明或服务端枚举。用两支出各 100、两退款各 60、每笔退款各可选两支出的反例验证当前生产服务。
3. 任务拆分：真实双库保存合法候选并集；两套交叉选择分别以独立首次完整写集确认；两套同支出超额选择分别以首次完整写集拒绝且全库／收据回滚。用当前首次提交和真实图／仓储，不直接写假关系或跳过服务门。
4. 构建：`candidate-union-focused.log` 13 秒成功，新增 3 方法在两库实际执行共 6 项，全 0 失败／错误／skip；每库分别覆盖候选并集、两套合法交叉方案和两套非法超额方案。首次即通过，验证既有生产行为，没有修改生产代码或为测试制造新语义。XML 已固定。
5. 审查：独立必要性评估及 5a 范围／工程／最终 diff 复核完成。两项 P3 证据补强已同轮复核：按真实关系 ID 核逐边完整候选集合，合法确认核实际端点精确等于本次方案；不会将候选总数或任一合法结果当作正确选择。三处文档残句已原位收敛。5b 实际范围判断为仅既有行为测试与职责说明，未改数据库、模型、DTO、接口合同或 UI，不重复子专项；沿已完成的独立必要性结论。
6. 测试与 QA：2026-11-14（Asia/Shanghai），基线 `f0002a8c` 加本片工作树；唯一受测 Kotlin 测试文件 SHA 固定在仓库外 `offline-ledger-1114/candidate-union-tested-code.json`，当前一致，生产代码字节未改。真实 PostgreSQL 沿显式 `_test` 库和外部 `postgres.env`，没有测试自动探测／启动数据库。

实际命令为 `./compose/gradlew -p compose -I <证据根>/quality.init.gradle :backend:test --tests '*CashImportWriteSetSubmissionTest*' :backend:ktlintCheck :backend:detektTest`，携带本页前述相同 Gradle 参数、Temurin 21 和 `NODE_ENV=development`。`candidate-union-matrix.log` 成功，1 分 58 秒，21 任务（6 执行／15 up-to-date）；完整提交服务 120 方法（各库 60），0 失败／错误／skip，包含补强后的全部 6 个新方法实例。XML 固定在 `candidate-union-final-backend-test-xml`；实际方法／时间及计数见 `candidate-union-final-results.json`。编译测试、ktlint 和 detektTest 通过。

没有新增生产算法、SQL、接口、共享模型或 UI，不重跑未受影响的完整后端／历史、跨端及打包矩阵；前片完整默认 1265、历史双库各 39 的实际生产字节保持，不把旧计数当作本片新增方法结果。不以本片合成服务输入替代完整本地 builder、发现、旧 writer 全量或页面接线。
7. 发布：已原位核对 D3、当前 3.x 任务、backend README、两份地图、词表及 apply 指引，移除三处仍将候选全组合合法误作服务端门的残句；主规格／delta 的完整方案单选及最终图规则保持，其余文档、接口示例、OpenAPI、配置、迁移及依赖无需变化。Cross-platform Impact Check：生产 Web／Native／共享层未改，无用户文案、页面或交互变化，不触发 UI 专项、页面冒烟或原生设备 QA。本步为既有职责核对，没有新的用户产品决定或通用规则。strict、doctor、92 个本地文档目标和 diff 检查通过；独立最终证据门通过，固定 XML、唯一测试文件 SHA、实际日志和职责文档一致，无遗留 finding。按授权提交推送同分支；客户端完整 choices／单选／builder、公开提交及恢复应用仍待交付，不宣称全部提交服务完成，3.2／3.4 及整个 change 保持未完成，不归档、不创建 PR。


### 公开写集提交 HTTP（3.4／3.6／3.8）

比较基线 `09ff5d46`，沿 A 类 change 的既定提交合同接通传输，不新增业务字段或改变文件入口资格。

1. 思考：内部服务已组合真实身份、鉴权、基线、C／B／协调／D、余额及永久结果；公开路由尚缺失。旧 objectInput 会归一数字，不能用于精确正文摘要。
2. 计划：沿 D3 新路由复用内部服务和永久收据读取；保留 JSON 数字原文，严格 UTF-8、重复键与大小／深度边界。区分输入／业务拒绝、基线与规则冲突、程序异常及可识别临时故障，提交状态只取真实事务边界。
3. 任务拆分：先以实际 HTTP 与同一生产服务在 SQLite／显式 PostgreSQL 测试成功、原键重放、冲突、失效基线／规则、权限、输入保真及错误后核实；补静态 OpenAPI 与相称输入边界测试，再独立 5a／5b、完整受影响后端 QA 和文档收尾。
4. 构建：公开路由复用同一事务命令，不依赖旧暂存服务；严格 JSON 模式只保留数字原文，旧 Python 数字／字符串默认解析保持。永久 ID／原键读取支持固定工作区核实；未提交、未知和原结果保持真实语义。`write-set-http-red.log` 的 12 项真实双库用例均因缺路由失败，原 XML 已固定；`write-set-http-scope-red.log` 的双库范围门与 OpenAPI 认证共 5 项失败，修复后 `write-set-http-scope-green.log` 19 秒通过 50 方法：HTTP 28、JSON 3、OpenAPI 17、既有 ReceiptProtocolMigrationTest 2。
5. 审查：独立 5a 按工程／安全／最终 diff 追踪解析、鉴权、范围、事务和错误链；codebase-degradation-review 由独立上下文调度子上下文实际执行 contract-design-review。P1（会话切换可能改变原目标）已以必填唯一工作区参数、收据可选范围及 HTTP 身份到读事务的围栏关闭，并覆盖同基线全 New 草稿、两工作区同键不同收据及 middleware 后切换；P2（新 POST 缺 Bearer 元数据）已补 schema 和回归。增量重审均无遗留 finding；未触发 UI 专项。独立逐项服务验收审计确认 3.1–3.9 的实际消费者和双库回归均已具备，当前完整 QA 终态核证通过；端侧 builder 和旧路径退役归原 6.x／4.x，不缩小或新增本组验收。
6. 测试与 QA：2026-11-14（Asia/Shanghai），比较 `09ff5d46` 加本片工作树。仓库外证据根 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`。显式加载该目录 `postgres.env`，使用专用真实 PostgreSQL `_test` 库，不自动探测或启动数据库。`write-set-http-matrix.log` 的实际命令为 `./compose/gradlew -p compose -I <证据根>/quality.init.gradle :backend:test :backend:ktlintCheck :backend:detektMain :backend:detektTest :backend:installDist --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'`；JAVA_HOME 指向已安装 JDK 21。4 分 30 秒通过，24 tasks（12 executed／12 up-to-date）。默认后端 196 类／1,304 方法，0 failure／error／skip，包含 Submission 120（双库各 60）、新增 HTTP 28（双库各 14）、JSON 3、OpenAPI 17 及原收据、快照、业务回归；空 codegen 的 Gradle SKIPPED 不计为测试 skip。

`write-set-http-final-backend-test-xml` 固定完整 XML，`write-set-http-final-results.json` 记录实际类／方法，`write-set-http-tested-code.json` 固定 11 个 Kotlin／OpenAPI 资源 SHA，当前字节一致。输入安全专项覆盖标准 JSON、重复／转义重复键、无效 UTF-8／UTF-16、原数字词法／超 JS 安全整数、深度边界、有界正文在验证前拒绝；真实 HTTP 覆盖原工作区围栏、同基线竞态、权限、原键重放／冲突、整体拒绝、程序 500／临时 503、提交前回滚及提交后响应丢失核实。生产服务的 64 MiB／转义预算及完整图／效果矩阵保持，未以编译或空筛选代替运行。历史 CashPlanProtocolHttpOriginalTest 已以同一公共 Gradle 参数另运行 :backend:migrationEvidenceTest --tests '*CashPlanProtocolHttpOriginalTest*'，分别显式指定 FT_KOTLIN_CONTRACT_DIR 为证据根的 sqlite／postgres。write-set-http-original-{sqlite,postgres}.log 分别为 7 秒／6 秒通过，各 5 方法、0 failure／error／skip，原始完整类未改；固定 XML 与结果分别在同前缀 -xml／-results.json。覆盖旧维护正文实际 64 MiB 上界、非有限／非对象输入、键校验顺序、提交状态及实际 Netty 收据查询不泄漏。仅使用此前人工准备的合成会话／库配置，不启动或探测数据库；这 10 次历史执行不混入默认 1,304。
7. 发布：D3、当前任务、backend README、两份功能地图、OpenAPI 及 apply 指引已按公开入口和剩余端侧状态原位更新；核对词表、主规格／delta、其余 README／参考资料、接口／命令示例、配置和迁移，无新领域概念、业务语义、依赖或数据库 schema 变更，其余无需修改。继续保持用户撤去额外映射／来源证明的决定；固定工作区是已有隔离义务，没有新的产品取舍或通用规范需要沉淀。Cross-platform Impact Check：本片仅 backend 与协议文档变化，共享 DTO 和 Web／Native 生产调用未改；旧读取无 query 行为保留，客户端提交／恢复仍待接线，无页面、交互、布局或用户文案接入变化，UI 复用、Hallmark、浏览器页面冒烟及原生设备 QA 不适用，不追加无关原生编译。strict 27/27、doctor 和 92 个本地文档目标检查通过；独立最终证据门已核对固定 XML、双库实际方法、11 个受测文件 SHA、日志终态与范围文档，无遗留 finding；按授权提交推送同分支。3.x 完成不代表整个 change 完成，4.x／6.x 等仍待交付，不归档、不创建 PR。


### 客户端写集 HTTP 传输（6.6，内部组合边界）

2026-11-14（Asia/Shanghai），比较基线及验证 HEAD 为 `89c2ebe1` 加本片工作树，分支 `worktree-offline-first-ledger-import`。沿既定服务协议接通共享客户端，不新增传输模型、证明字段或业务语义。

1. 思考：服务端公开提交与固定工作区收据读取已完成，旧客户端仅有 plan 提交；现有 ApiCredentialSnapshot、精确写集 DTO、收据 serializer 和 mapper 可直接复用。
2. 计划：提交前固定 JSON 正文，明确传入原凭据和工作区，核实沿同一范围；严格要求实际写集收据及完整结果，不以 HTTP 2xx、空正文或旧收据冒充成功。仅新传输要求响应正文，原调用默认行为保持；已知拒绝状态保留，但无诊断不能推断提交状态。
3. 任务拆分：先失败回归实际 MockEngine 请求字节／头／范围、原身份、未知结果核实、错误与损坏响应；实现既有模型上的薄传输，独立 5a／5b 后执行 host／实际 JS／Wasm 浏览器及相称原生编译。
4. 构建：`client-write-set-red.log` 复现缺失传输 API，随后实现固定范围 POST、ID／原键读取、完整收据门及仅新调用使用的必需响应正文选项；原精确版本门抽出共用，旧调用语义保持。13 项新增方法覆盖实际精确正文、容器挂起后变更、全部身份结果、原凭据与工作区、丢响应原键核实、不自动重试、404 不推断未提交、错误状态／原诊断、401 不清后来登录、空／旧／损坏收据、取消及非法引用。编译时跨模块 smartcast 已改为局部值；取消回归检查原原因链而不依赖协程异常对象身份，未改生产取消语义。
5. 审查：独立 5a 产品／范围、工程（产品编码规范 §7）、安全／错误链和最终 diff，以及 codebase-degradation-review 调度的独立子上下文实际 contract-design-review 均无遗留 finding；复用原模型／序列化／请求能力，不新增存储或镜像响应模型。新增回归及两处夹具修正已独立增量复核，无弱化断言。没有 UI 专项；完整应用 owner、builder 和 离线写入限制 不在本步受审完成范围。
6. 测试与 QA：证据根 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`。公共命令参数为 `./compose/gradlew -p compose --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'`，JAVA_HOME 指向已安装 Temurin 21，实际编译／测试沿仓库 Gradle toolchain criteria；NODE_ENV=development，CHROME_BIN 指向已安装 Google Chrome。

`client-write-set-host-final.log` 实际执行 `:shared:testAndroidHostTest`，用 `--tests` 选择 CashWriteSetApiTest、CashImportPlanApiTest、LedgerReceiptMappingTest、FinanceApiClientTest、LedgerCredentialSnapshotTest、LedgerOfflineSessionFallbackTest；20 秒通过，52 tasks（5 executed／47 up-to-date），6 类／71 方法。`client-write-set-platforms-final.log` 实际执行 `:shared:jsBrowserTest :shared:wasmJsBrowserTest :shared:compileAndroidMain :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosSimulatorArm64`；1 分 6 秒通过，157 tasks（24 executed／133 up-to-date）。ChromeHeadless 157 的 JS 为 98 类／825 方法，Wasm 为 100 类／831 方法，新增 13 方法在 host 与两个真实浏览器任务均实际执行；全部 0 failure／error／skip。Android 主代码、iOS Arm64 主代码和 Simulator 测试代码编译通过，不宣称 iOS 测试运行或设备 QA。

浏览器失败证据完整保留：`client-write-set-js-first-emission.txt`、`client-write-set-js-rebuilt.log` 及对应固定 XML 确认旧夹具中的混合孤立代理字符在生成 JS 中成为合法问号，关闭增量且 54 tasks 全执行仍复现；`client-write-set-first-runtime-emission.txt` 与固定 XML 确认新增测试同类常量亦被替换。两处仅按已有测试做法改为运行期字符数组并先核 UTF-16 码值，保留原三组非法样本、emoji 128／255 接受和 256 拒绝、零额外网络断言，并加合法 `bad?` 正例；生产键校验未改。失败矩阵不计通过；以上最终日志及 XML 才计当前结果。

最终 host XML 固定于 `client-write-set-host-final-xml`，两个浏览器 XML 为 `client-write-set-final-<任务>-xml`；类／方法计数见 `client-write-set-host-final-results.json` 与 `client-write-set-final-platform-results.json`，`client-write-set-tested-code.json` 固定 5 个 Kotlin 文件 SHA。该 shared 模块没有 ktlint／detekt 任务；已执行相称编译、运行回归及 diff 检查。本片不改服务端持久化或 Application Service，双库服务合同沿 `89c2ebe1` 已完成证据，不为纯客户端传输重复数据库矩阵。

7. 发布：D3、tasks、backend README、总览／导入功能地图及 apply 指引已按实际薄传输和剩余接线原位核对；词表、主规格／delta、接口示例、依赖、配置／迁移无新语义或形状，其他文档无需修改。继续保留用户撤去额外账户映射／来源证明的决定，无需新增通用规范。Cross-platform Impact Check：共享客户端影响 Web／Android／iOS，已按上述矩阵验证；没有页面、路由、交互、主题或用户文案变化，产品页面冒烟、UI 复用、Hallmark 及设备 QA 不适用，浏览器单测不冒充页面 QA。OpenSpec strict 27/27、doctor healthy、95 个本地文档目标及 23 个锚点检查通过；独立最终证据门已核对固定 XML、日志终态、5 个受测文件 SHA 和 11 文件范围，无遗留 finding，按授权提交推送同分支。该内部能力可随代码独立回退，无存储迁移；6.6、完整客户端与整个 change 仍未完成，不归档或创建 PR。

### 客户端流水写集构造（6.5／6.6）

比较基线 `3ac53d98`，延续完整写集及 C／B／D 的既定分工，先完成构造器中的流水／组成项／C 片段，其他片段继续组合到同一 DTO，不缩小完整导入范围。

1. 思考：本地完整预览已保留原 operations 与精确虚拟引用；展示值叠加公共字段，不能直接保存为来源。旧 writer 的字段、隐藏值、组成项保留和前置编辑时点是本步对照。
2. 计划：由同一快照、完成的冻结计划及原 row_choices 构造 CashTransactionWriteDto 列表；原始 JSON 数字、可空金融值、新引用、实际旧修订与组成项身份保持，显式非公共编辑与 C 分开，不生成实际数据库身份或版本。
3. 任务拆分：真实本地 prepare→preview→freeze 驱动失败回归，再实现；覆盖新建／草稿／正式旧对象、前置公共编辑、source defer／keep、精确数字及输入只读；独立 5a／实际 5b 后运行领域与真实双库写入对照及跨端矩阵。
4. 构建：缺失构造入口的失败编译后实现原 DTO 上的流水片段。9 项真实 prepare→preview→freeze 回归覆盖来源／C 分离、完整虚拟引用、旧修订和隐藏字段、显式金融编辑、defer／keep、原始 JSON、非代表重复行选择及多组成项换序／保留／替换；源 JSON 沿既定 native 归一后得到 1.23／1000.0，构造器对完成计划中的原 token 不再归一。原 UTC 格式按既定 +00:00 保留。SQLite／真实 PostgreSQL 的来源阶段新增真实消费测试已通过，未将 C 执行或完整提交计入本步。
5. 审查：独立 5a 工程（产品编码规范 §7）、产品／范围、来源值和错误边界及最终 diff，以及 codebase-degradation-review 调度独立子上下文实际 contract-design-review 均无 finding。建议补充的非代表重复行和多组成项分支已补齐并通过增量复核；无 UI 专项。完整关系／来源元数据／映射／条目组合及应用 owner 未覆盖，仍按原任务完成。
6. 测试与 QA：2026-11-14（Asia/Shanghai），比较基线及验证 HEAD 为 `3ac53d98` 加本片工作树，证据根 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`。`client-transaction-writes-api-red.log` 在夹具类型正确后复现缺失构造入口。构造器曾误将 null 传入要求数组的既有 input 门，已修为显式空数组；测试的 native JSON 桥和 UTC 文本期望已沿原 API／格式修正，不改来源归一、选择或写入语义。当前以 `client-transaction-writes-matrix.log` 的完整成功终态计通过，不把调试失败的组合命令计为通过。

实际命令为 `./compose/gradlew -p compose -I <证据根>/quality.init.gradle :business-domain:jvmTest :backend:test :business-domain:jsBrowserTest :business-domain:wasmJsBrowserTest :business-domain:compileAndroidMain :business-domain:compileKotlinIosArm64 :business-domain:compileTestKotlinIosSimulatorArm64 :business-domain:ktlintCheck :business-domain:detektMetadataCommonMain :business-domain:detektJvmTest :backend:ktlintCheck :backend:detektTest :backend:installDist --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'`。JAVA_HOME 指向已安装 Temurin 21，实际编译／测试沿 Gradle toolchain criteria；NODE_ENV=development，CHROME_BIN 为已安装 Google Chrome。显式加载既有 `postgres.env` 的专用真实 `_test` 库，测试不启动或探测数据库；quality init 只将 ktlint worker heap 设为 1024m。

6 分 44 秒通过，112 tasks（32 executed／80 up-to-date）。领域 JVM 174 类／722 方法，backend 196 类／1,308 方法；ChromeHeadless 157 的 JS／Wasm 各 149 类／686 方法，全部 0 failure／error／skip。新增构造类 9 方法在 JVM 和两个实际浏览器任务均执行；来源阶段完整参数化类 110 方法，SQLite／PostgreSQL 各 55，新增本地预览消费与重导两项各端实际运行。新增消费验证实际 parent／part ID、金额、归一 payload、原字段及无 C 执行；重导保留 origin／指纹／人工标记和组成项隐藏值，同值来源不写、不推进版本，不吞原 C。完整存储／提交仍由原服务矩阵覆盖，不将本步片段当作完整客户端写集。Android 主代码、iOS Arm64 主代码及 Simulator 测试代码编译通过，不宣称 iOS 运行或设备 QA；上述静态检查和 installDist 全通过。构造按完整快照建立索引并线性遍历本次操作／组成项，不引入组合搜索；整体导入性能仍归完整管线验收。

固定 XML 为 `client-transaction-writes-final-<模块>-<任务>-xml`，`client-transaction-writes-final-results.json` 保存实计类／方法，`client-transaction-writes-tested-code.json` 固定 3 个 Kotlin 文件 SHA，当前字节一致。

7. 发布：D3、当前任务、总览／导入功能地图及 apply 指引已核对流水片段的实际输入、来源／公共编辑分工及剩余完整组合；README、词表、主规格／delta、接口示例、依赖、配置／迁移无新行为或形状，其余无需修改。继续保留用户撤去额外账户映射／来源证明的决定，没有新的通用规范需要沉淀。Cross-platform Impact Check：共享领域构造影响 Web／Android／iOS，按上述实际浏览器单测和原生编译验证；没有页面、路由、文案、主题或交互修改，UI 复用、Hallmark、产品页面冒烟及设备 QA 不适用。OpenSpec strict 27/27、doctor healthy、94 个本地目标及 23 个锚点、diff 检查通过；独立最终证据门确认固定 XML、日志终态、3 个受测文件 SHA 和 8 文件范围一致，无遗留 finding，按授权提交推送同分支；可随代码独立回退，无 schema 迁移。完整 builder、6.5／6.6 和整个 change 仍未完成，不归档或创建 PR。

### 客户端来源写集组合（6.5／6.6）

比较基线 `acd5cf84`。组合真实本地计划的流水、绑定、来源问题／候选、空页及最终账户映射；关系／条目与恢复 owner 仍需并入完整写集。

1. 思考：核对原 CommitPreparation 的文档选择、RecordWriter 的完全相同重导早退／问题保留与 pending、BindingValueWriter 的重复自然键和稳定证据、MappingRepository 的同值及冲突规则，不引入额外输入证明。
2. 计划：既有流水构造结果加来源最终增量，New 引用仅为提交内稳定文本，已有身份及修订取同一完整快照。将原问题不变判断与文档选择提取为共享纯规则供旧写入口和新构造复用；映射只消费既有 used 输出，不重算发布条件。
3. 任务拆分：真实本地 prepare→preview→freeze 先写失败回归，覆盖初次、重导、新别名、defer／keep、草稿问题及清除、歧义与关系问题保留、映射及空页；真实双库来源组合消费并与旧写面核对，独立审查后执行跨端及完整回归。
4. 构建：真实 prepare→preview→freeze 来源组合已实现，原重导／文档选择抽为共享纯规则。来源说明单独变化而 defer 的真实回归先红后修：duplicate 保留旧说明。双库消费发现 JSON 字符串／布尔／null 叶子未转成内部值，真实嵌套 null 回归复现异常；修复限定于 source_payload 的有界归一，不更改精确 wire 或数值比较。纯数字同值用例本已通过，不计作失败证据。
5. 审查：独立 5a 产品／范围、工程（产品编码规范 §7）及最终 diff 与 codebase-degradation-review 调度的独立实际 contract-design-review 已完成，合并同一说明 defer P2 finding；修复经两方增量复核关闭，无新生产 finding。审查核对原重导早退、绑定自然键末次元数据／稳定证据、问题保留及 pending presence、文档页选择和 used 最终映射；未覆盖完整关系／条目组合及恢复接线，留在原后续任务。补强合法 summary 说明变化、64 层接受和 65 层拒绝回归；无 UI 专项。
6. 测试与 QA：2026-11-14～15（Asia/Shanghai），验证 HEAD 为 `acd5cf84` 加本片工作树。`client-source-writes-focused-final.log` 在 33 秒内完成来源构造 15 项、值规则 5 项及双库来源保存 118 项，全部 0 failure／error／skip；同组补强合法说明及深度边界后执行完整矩阵。首轮跨端编译发现 JVM 专用 Map.getOrDefault，改为 containsKey／getValue 并保留显式 null 的错误边界，独立复核无语义变化；最终相同矩阵已完整重跑通过，不以失败轮次的 JVM 结果代替当前通过。

实际命令为 `./compose/gradlew -p compose -I <证据根>/quality.init.gradle :business-domain:jvmTest :backend:test :business-domain:jsBrowserTest :business-domain:wasmJsBrowserTest :business-domain:compileAndroidMain :business-domain:compileKotlinIosArm64 :business-domain:compileTestKotlinIosSimulatorArm64 :business-domain:ktlintCheck :business-domain:detektMetadataCommonMain :business-domain:detektJvmTest :backend:ktlintCheck :backend:detektMain :backend:detektTest :backend:installDist --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'`。JAVA_HOME 指向已安装 Temurin 21，实际编译／测试沿 Gradle toolchain criteria；NODE_ENV=development，CHROME_BIN 指向已安装 Google Chrome。显式加载证据根原有 postgres.env 的专用真实 `_test` 库；测试不启动或探测数据库，quality init 只设既有 ktlint worker heap。

证据根为仓库外 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`，最终 `client-source-writes-matrix.log` 在 7 分 6 秒内通过（113 tasks：30 executed／83 up-to-date）。领域 JVM 175 类／738 项，backend 196 类／1,316 项；ChromeHeadless 157 的 JS／Wasm 各 150 类／702 项，全部 0 failure／error／skip。来源新类 15 项三端均实际执行；双库来源参数化类 118 项，SQLite／PostgreSQL 各 59 项，新增组合消费、草稿转正式、说明 defer 无写及待采用 null 原文四项各库执行。实际来源、绑定、来源元数据和最终映射均沿同一真实事务组合，不以此片段冒充完整公开提交；C 留待原独立阶段。Android 主代码、iOS Arm64 主代码与 Simulator 测试代码编译通过，不宣称 iOS 执行或设备 QA；ktlint、所列 detekt 及 installDist 均通过。

固定 XML 为 `client-source-writes-final-<模块>-<任务>-xml`；`client-source-writes-final-results.json` 记录实际计数，`client-source-writes-tested-code.json` 固定本步 9 个 Kotlin 文件 SHA。最终验证器及独立最终证据门均已核对全部 XML 新鲜度、双库方法数量、受测源码字节、14 文件范围及成功终态，无遗留 finding。构造按快照索引、操作／绑定／问题及页面遍历，映射按自然键排序，不增加组合搜索；完整导入性能仍归整条管线验收。

7. 发布：D3、当前任务、总览／导入功能地图及 apply 指引已原位更新实际来源组合与剩余完整关系／条目接线；已核对 README、词表、主规格／delta、接口示例、依赖、配置及迁移，没有新领域概念、wire、数据库 schema 或公开入口，其余无需修改。沿用户撤去额外账户映射／来源证明协议的决定，保留最终增量与原保存规则，不新增通用规范。Cross-platform Impact Check：共享构造影响 Web／Android／iOS，以上实际浏览器单测及原生编译覆盖；没有页面、文案、交互或主题变化，UI 复用、Hallmark、产品页面冒烟及设备 QA 不适用。OpenSpec strict 27/27、doctor healthy、96 个本地文档目标与 23 个锚点及 diff 检查通过。按现行授权提交推送原任务分支，可随代码独立回退，无数据迁移；完整 builder、6.5／6.6 与整个 change 仍未完成，不归档或创建 PR。


### 客户端关系写集组合（6.5／6.6）

比较基线 `41db22ec`。消费真实完成计划和同次来源增量，继续构造关系、候选与独立 B；最终条目／成员及恢复接线保持后续任务。

1. 思考：核对原 PlanRelationWriter 的联合批次、锚点、候选方案拍平／去重及确认来源；核对现有文件专用 scope、关系批次及公共选择门，不新增协议或重新执行发现。
2. 计划：沿原虚拟边 ID 建立 New 引用，真实旧端点／候选取同一快照修订，新端点／部分取本次来源引用。保留 accept／defer／ignore、证据和 applied_amount；B 仅来自 accept 组，数据库联合标签仍由服务端生成。
3. 任务拆分：真实本地操作准备、预览、提案分组、选择与冻结先写失败回归；覆盖缺省和显式选择、候选修订／证据／去重、联合组顺序与独立 B，再用真实双库保存和原写面核对，完成独立审查及跨端矩阵。
4. 构建：已先复现新构造入口缺失；真实文件预览沿 prepare(operations, source.rows) 增强稳定来源键，prepareOperations 只供已增强输入。关系构造已实现；合法 payment_mirror 缺省 defer 的空金额回归复现实际 "null" 与原 "0" 差异，修复后覆盖 payment_mirror／transfer_pair 的 defer／ignore。8 项本地回归已通过，包含双退款完整联合组、原边顺序和一个 B。真实双库消费又复现正退款开放锚点的候选方向拒绝：同一输入先经原 writer 真实保存成功、savepoint 回滚还原，再由新阶段因按主端位置猜角色而拒绝；修复限于无另一端且未声明角色的开放关系，沿原变体按符号确定方向。退款／转入的原写面差分与显式错误角色拒绝及完整 facts 回滚已定向通过；候选资格与客户端边位置保持原规则。
5. 审查：独立 5a 范围／工程／安全／最终 diff 与 codebase-degradation-review 调度的实际独立契约复核已完成；空金额缺省与开放退款方向两项 P2 均先红再绿并增量复核关闭，无遗留 finding。最终测试增量复核核实联合组及误角色测试确实触达相应门；早先夹具失败不计通过。UI 无变化，不触发 UI 专项。最终独立证据及文档门已核对固定 XML、当前受测源码 SHA、日志终态与 9 文件范围，结论通过。
6. 测试与 QA：2026-11-14（UTC，本地 11-15），验证 HEAD／比较基线 `41db22ec`，本节范围的当前工作树通过以下矩阵。日志位于仓库外 `offline-ledger-1114/client-relation-writes-matrix.log`，终态成功 7 分 11 秒，113 项任务（33 执行、80 已是最新）。显式加载已准备的 `postgres.env`／`FT_TEST_POSTGRES_URL`，专用库名以 `_test` 结尾，测试未探测或启动数据库；构建启动 JAVA_HOME 为 Temurin 21，Gradle 实际工具链为 Zulu 21.0.11。

```sh
./compose/gradlew -p compose -I <缓存>/quality.init.gradle \
  :business-domain:jvmTest :backend:test \
  :business-domain:jsBrowserTest :business-domain:wasmJsBrowserTest \
  :business-domain:compileAndroidMain :business-domain:compileKotlinIosArm64 \
  :business-domain:compileTestKotlinIosSimulatorArm64 \
  :business-domain:ktlintCheck :business-domain:detektMetadataCommonMain \
  :business-domain:detektJvmTest :backend:ktlintCheck :backend:detektMain \
  :backend:detektTest :backend:installDist \
  --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'
```

domain JVM 176 类／746 方法，backend 196 类／1,320 方法；JS 与 Wasm 均 151 类／710 方法，实际 ChromeHeadless 157 执行。所有测试 0 失败／错误／跳过；新增关系构造测试三种执行环境均 8 方法，真实来源／关系阶段在 SQLite／PostgreSQL 各 61 方法。固定 XML 为 `client-relation-writes-final-<模块>-<任务>-xml`，实际计数见 `client-relation-writes-final-results.json`；`client-relation-writes-tested-code.json` 固定 4 个 Kotlin 文件 SHA，验证器已核对代码字节、全部 XML 新鲜度及终态。组合按快照、来源引用和候选边建立索引，不新增候选组合枚举；开放方向校验仍逐候选一次，完整导入性能归整条管线验收。

7. 发布：D3、当前任务、两份功能地图及 apply 指引已原位核对；README、词表、主规格／delta、接口／命令示例、配置及迁移无新概念、wire、schema 或公开入口，其余无需修改。继续保留用户撤去额外映射／来源证明协议的决定，沿原保存规则组装最终增量，无需新增通用规范。Cross-platform Impact Check：共享构造影响 Web／Android／iOS，以上单测及原生编译验证；无页面、路由、交互或用户文案修改，UI 复用、Hallmark、产品页面冒烟及设备 QA 不适用。OpenSpec strict 27/27、doctor、本次 56 个本地目标与 17 个锚点及 diff 检查通过；最终独立证据门已通过，无遗留 finding。按授权提交推送同任务分支，可随代码独立回退，无数据迁移；完整 builder、6.5／6.6 与整个 change 保持未完成，不归档或创建 PR。

### 客户端最终条目与完整写集组合（6.5／6.6）

比较基线 `507933b7`。沿已完成计划及同次来源／关系增量，补齐最终条目与成员、旧条目观察；不接线发现、离线写入限制 或页面。

1. 思考：核对真实服务 C→B→协调→D、共享编辑／协调器、确认图及受影响范围门。最终公共值、成员独立值与来源值分别保留，服务端拥有实际身份。
2. 计划：受影响完整分区选择复用现有拓扑逻辑；以同一快照的私有副本应用共享规则，不新增公开中间模型或 wire。草稿不补时间，候选依赖不扩大条目范围，退休旧条目亦保留原修订依赖。
3. 任务拆分：先以真实本地操作／完成计划构造验证缺失入口；覆盖来源 S、前置 C、关系 B、后置 D，独立值与人工标记、完整联合组、精确依赖及未定时草稿，再接实际同一服务双库合同与跨端验证、独立 5a／5b。
4. 构建：入口缺失及 D 目标遗漏种子均有失败回归，已实现同 DTO 的来源／关系／最终条目组合；受影响组选择与旧校验共用纯准备器。6 项条目回归覆盖 C／B／D 分离、联合组、D-only 旧展示组、候选上下文不扩写、未定时草稿及五公共字段显式清空／人工标记，原拓扑／关系测试一并通过。原上下文夹具的 manual_overrides 数组与缺字段的 standalone_values 仅在本片使用的夹具中校正为真实存储形状，不改变生产读取容错；服务拒绝的断言沿实际 CashWriteSetSubmissionFailure。实际 SQLite／PostgreSQL 经同一提交 Application Service 验证来源、C／B／协调／D、伪造独立值晚期拒绝整体回滚、实际身份及原键重放／新键重导不推进事实版本；另先真实保存退款草稿，转正式合并两旧条目，精确核两份旧修订依赖及收据退休集合，定向 4 方法通过。私有协调按选中成员／旧 owner 取输入并维护成员索引，虚拟引用使用单次索引，避免逐新单成员组扫描完整历史。
5. 审查：独立 5a 范围／工程／安全／最终 diff 与 codebase-degradation-review 实际调度的独立契约 5b 已完成，无阻断 finding；两真实旧条目合并退休的覆盖建议已补真实双库回归并增量复核，P3 服务层描述已改为提交 Application Service，不冒称 HTTP 端到端。最终独立证据及文档门已核对当前 5 个 Kotlin 文件 SHA、固定 XML、日志终态与 11 文件范围；剩余过时服务／builder 状态表述的 P3 已原位收敛并复核，无遗留 finding。
6. 测试与 QA：2026-11-15（UTC），验证 HEAD／比较基线 `507933b7`，本节范围的当前工作树通过以下矩阵。仓库外日志 `offline-ledger-1114/client-entry-writes-matrix.log` 终态成功 7 分 1 秒，113 任务（31 执行、82 已是最新）；专用 `_test` PostgreSQL 沿已准备的 `postgres.env` 显式设置 `FT_TEST_POSTGRES_URL`，测试未探测或启动数据库。构建启动 JAVA_HOME 为 Temurin 21，Gradle 实际工具链为 Zulu 21.0.11。

```sh
./compose/gradlew -p compose -I <缓存>/quality.init.gradle \
  :business-domain:jvmTest :backend:test \
  :business-domain:jsBrowserTest :business-domain:wasmJsBrowserTest \
  :business-domain:compileAndroidMain :business-domain:compileKotlinIosArm64 \
  :business-domain:compileTestKotlinIosSimulatorArm64 \
  :business-domain:ktlintCheck :business-domain:detektMetadataCommonMain \
  :business-domain:detektJvmTest :backend:ktlintCheck :backend:detektMain \
  :backend:detektTest :backend:installDist \
  --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'
```

domain JVM 177 类／752 方法，backend 196 类／1,324 方法；JS 与 Wasm 均 152 类／716 方法，实际 ChromeHeadless 157 执行。全部 0 失败／错误／跳过；新增条目测试三种执行环境均 6 方法，来源／关系／条目同服务合同在 SQLite／PostgreSQL 各 63 方法。固定 XML 为 `client-entry-writes-final-<模块>-<任务>-xml`，实际计数见 `client-entry-writes-final-results.json`；`client-entry-writes-tested-code.json` 固定 5 个 Kotlin 文件 SHA，验证器已核全部 XML 新鲜度、源码字节与成功终态。实际服务对照覆盖来源、编辑、绑定／问题／映射、完整关系／条目、余额、永久收据及实际退休身份，不把内部组合冒充 HTTP 或完整离线导入。受影响分区索引／闭包沿原拓扑选择，条目计算复用原共享协调复杂度，不新增候选组合搜索；整条导入性能仍归完整管线验收。

7. 发布：D3／D5、当前任务、backend README、两份功能地图及 apply 指引已原位核对；词表、主规格／delta、其余 README／参考资料、接口／命令示例、依赖、配置及迁移没有新概念、wire、schema 或入口，其余无需修改。继续保持用户撤去额外映射／来源证明协议的决定，沿原保存规则组装最终增量，无需新增通用规范。Cross-platform Impact Check：共享构造影响 Web／Android／iOS，以上单测及原生编译验证；无页面、路由、交互或用户文案修改，UI 复用、Hallmark、产品页面冒烟及设备 QA 不适用，实际浏览器单测不冒称页面 QA。OpenSpec strict 27/27、doctor 与 diff 检查已通过；本次 59 个本地文档目标与 17 个锚点检查通过；最终独立证据门已通过，无遗留 finding。按授权提交推送同任务分支，可随代码独立回退，无数据迁移；构造器内部已完整，6.5／6.6 的重派／恢复、发现和页面接线以及整个 change 仍未完成，不归档或创建 PR。

### 重派文件输入冻结（6.5／6.6）

比较基线 `2c28c13b`。在既有 A 类 change 内补充文件输入恢复，不改变识别、账户映射及来源证明合同。

1. 思考：现有单文件读取器只返回文档，未保留原始字节及文件身份；后续重派不能依赖外部文件句柄仍可读。
2. 计划：单次有界读取固定原文件及解析结果，复用现有归一文档编码；内部编码仅供加密存储，不保存密码，不新增网络协议。
3. 任务拆分：先覆盖恢复前后完整文档／稳定行身份、精确数字、原文件读一次、副本隔离、PDF／XLS 不二次联网及取消／失败不发布；再实现、独立审查和跨端验证。
4. 构建：缺失 capture API 的失败回归后实现一次有界读取及版本化文件编码；文档与原字节每次恢复为独立副本，元数据在首次挂起前固定，密码不进入编码。复用原归一 DTO／字段编码，仅已存在的 JSON 数字原样保留，不改变正常归一规则；恢复时一次核原 100 MiB 预算、Base64 长度、实际大小及摘要，不重跑识别。文件身份不一致与高精度小数／指数丢失均先有真实失败回归，再最小修复；未修改账户映射／来源证明协议。
5. 审查：独立 5a 与实际 codebase-degradation-review 调度的契约 5b 已发现并处置两项 P2：容器自身字节／摘要不一致未拒绝，以及通用值编码会窄化已有 JSON 小数。两项分别有真实失败回归，主审与实际独立子审增量复核均已关闭，无新 finding。审查覆盖产品编码规范 §7 的输入、来源保真、恢复身份、密码、异常／取消、副本隔离及复杂度；无 UI 变化，UI 专项不适用。
6. 测试与 QA：2026-11-15（UTC），验证时 HEAD／比较基线 `2c28c13b`，本节当前工作树的 4 个 Kotlin 文件 SHA 固定在仓库外 `offline-ledger-1114/stable-file-input-tested-code.json`。定向 10 方法通过；最终命令如下，`stable-file-input-matrix.log` 终态成功 1 分 55 秒，187 任务（29 执行、158 已是最新）。Android Host 96 类／816 方法、JS 98 类／830 方法、Wasm 100 类／836 方法，全部 0 失败／错误／跳过；文件读取回归各 10 方法，浏览器 XML 确认 ChromeHeadless 157 实际执行，iOS 两项编译均通过。构建启动 JAVA_HOME 为 Temurin 21，Gradle 实际工具链为 Zulu 21.0.11。

```sh
./compose/gradlew -p compose \
  -I <缓存>/stable-file-input-self-contained.init.gradle \
  :shared:testAndroidHostTest :shared:jsBrowserTest :shared:wasmJsBrowserTest \
  :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosSimulatorArm64 \
  --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'
```

初次未过滤的 Android Host 全测中，19 个历史外部接口／live 方法因缺少 FT_KOTLIN_CLIENT_CONTRACT_DIR／FT_KOTLIN_PORTFOLIO_HOST_FILE 失败；本步未修改这些接口，最终自包含矩阵通过外部 init 明确排除 `com.finance.tracker.Kotlin*ResponseIntegrationTest` 和 `com.finance.tracker.KotlinPortfolioLiveIntegrationTest`，排除项未计通过。重跑这些外部集成须先为当前后端生成合同夹具，并准备原投资 live 测试主机；本步不以历史夹具伪作当前接口验证。SQLite／PostgreSQL 服务及端侧存储本步无改动，不重跑服务双库、加密落盘或登录清理测试。固定 XML 为 `stable-file-input-final-<任务>-xml`，`verify-stable-file-input.py` 已核源码 SHA、XML 新鲜度、各读取回归数量与成功终态；完整编码／恢复按原字节线性处理，恢复副本不共享，未引入识别或候选搜索，整批导入性能仍归完整管线验收。

7. 发布：D5、当前任务、两份功能地图及 apply 指引已核对；原归一 DTO、服务端请求、README、参考资料、配置／迁移、主规格／delta 及词表无新概念或操作，无需修改。用户撤回额外账户映射／来源证明合同的选择继续保持，不新增通用规则；编码自身身份核对属于实现不变量，不是产品新合同。Cross-platform Impact Check：Web／Android／iOS 的共享文件输入均受影响，由上述单测及原生编译验证；无页面、路由、交互或文案修改，Hallmark、页面冒烟及设备 QA 不适用，浏览器单测不冒称页面 QA。strict 27/27、doctor、5 份文档的 101 个本地目标／23 个锚点及 diff 检查均通过。最终独立证据／文档门已通过，无遗留 finding；按既有授权仅提交推送本步 9 文件至原任务分支，排除 `.commandcode/`，无数据迁移。尚未接持久化 owner、决策重绑定、离线写入限制 或页面，6.5／6.6 及整个 change 仍未完成，不归档或创建 PR。

### 加密库的文件批次持久化（6.5／6.7）

比较基线 `9f87ef49`。复用原范围加密库及会话 owner，保存冻结文件输入，不新增后端合同或页面。

1. 思考：文件编码尚无持久化端口；事实刷新不能覆盖未提交输入，关闭及身份切换也不能发布迟到结果。
2. 计划：在同驱动加独立范围 SQL 行，整批原子保存，固定批次引用只接受相同内容重试；共用范围、互斥和关闭围栏，通过原会话 owner 核凭据／代次及身份后访问。
3. 任务拆分：先验证有序恢复、范围隔离、重开、事实刷新保留、同键异值、SQL 失败保旧、坏记录拒绝、关闭／撤权围栏，再实施并独立审查，运行共享测试、实际 Web 加密存储及原生相称编译。
4. 构建：存储／owner 缺失入口的失败回归后实现单行批次存读及同范围 owner 组合；文件顺序和精确编码保留，同引用同值重试、异值拒绝，真实 SQL 故障保旧；scope、身份、关闭及父任务取消分别核对。等待期间可变列表被改和取消后仍启动动作均有各 2 方法真实失败回归，分别在首次挂起前复制外层列表、在不可取消交付及动作前后核取消修复。已开始的 SQL 不冒称撤销，仍可按原批次重开幂等恢复；旧 LedgerStorage 假实现只补显式不应调用的文件端口，不加生产回退。
5. 审查：独立 5a 及 codebase-degradation-review 实际调度的独立契约 5b 已完成，两项 P2（列表冻结、取消后启动动作）均经增量复核关闭，无新 finding。覆盖产品编码规范 §7 的稳定身份、范围／授权、输入保真、原子性、取消／资源所有权及复杂度；移除仅是本地材料操作，不虚构服务端撤销。无 UI 变更，UI 专项不适用。
6. 测试与 QA：2026-11-15（UTC），验证时 HEAD／比较基线 `9f87ef49`，16 个 Kotlin 文件 SHA 固定在仓库外 `offline-ledger-1114/import-files-tested-code.json`。定向 40 方法通过（SQL 3、Scoped cache 18、会话 owner 19）；最终 `import-files-matrix.log` 成功 1 分 4 秒，187 任务（23 执行、164 已是最新）。Android Host 97 类／828 方法、JS 98 类／835 方法、Wasm 100 类／841 方法，全部 0 失败／错误／跳过；浏览器 XML 核 ChromeHeadless 157 实际执行。Web 缓存各 7 方法，新增真实批次用例覆盖加密 OPFS 重开、完整编码／顺序、同键重试与异值拒绝、事实刷新保留，关闭后扫描实际文件拒绝明文 SQLite 头／文件名／原字节 Base64，清理后库与密钥缺失且已有库恢复拒绝。iOS 两项相称编译通过；Android 编译及共同单测由 Host 任务覆盖，不冒称 Android／iOS 设备或原生密钥库新运行证据。

```sh
./compose/gradlew -p compose \
  -I <缓存>/stable-file-input-self-contained.init.gradle \
  :shared:testAndroidHostTest :shared:jsBrowserTest :shared:wasmJsBrowserTest \
  :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosSimulatorArm64 \
  --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'
```

Host 全测已在本节当前 Host 输入下实际执行通过；最终仅修正 Web 测试探针为仓库支持的 Promise 链，Gradle 判 Host 输入未变而复用结果。验证器 `verify-import-files.py` 核 16 文件现字节 SHA、Host 来源修改时间／首次成功任务与最终 UP-TO-DATE、JS／Wasm XML 新鲜度、各回归数量及成功终态，固定 XML 为 `import-files-final-<任务>-xml`，计数见 `import-files-final-results.json`。沿原外部 init 排除历史 `Kotlin*ResponseIntegrationTest`／`KotlinPortfolioLiveIntegrationTest`，不把它们计为通过；补跑仍须当前后端合同夹具及原投资 live 主机，本片未改对应接口。端侧存储固定 SQLite，已跑同 SQL 的 JDBC 故障／范围合同及真实 Web 加密驱动；无 PostgreSQL 客户端存储或服务端事实行为变更，服务双库矩阵不适用本片。无新候选搜索或全历史事实派生；批次编码／校验按输入大小线性处理，整批导入性能仍待完整管线验收。启动 JAVA_HOME 为 Temurin 21，Gradle 实际工具链为 Zulu 21.0.11。

7. 发布：proposal／design／tasks、导入／共享／总览功能地图及 apply 指引已核对，原回滚文案中“缓存整体可丢弃”按既定稳定输入／原操作保留合同收敛为仅已同步事实可重建，未提交或结果未知的材料不得随代码回退静默删除；原登出／失效擦除规则不变。README、参考资料、主规格／delta、词表和服务端接口／配置无新概念或操作，无需修改；本地表沿现有惰性 CREATE，不需服务端迁移，代码回退保留该表及材料。用户撤回额外来源／映射证明的决定继续保持，无新产品取舍，无需沉淀通用规则。Cross-platform Impact Check：共享文件存储与会话访问影响 Web／Android／iOS，以上共同测试及原生编译覆盖；没有页面、路由、交互或文案改动，Hallmark、页面冒烟和设备交互 QA 不适用。strict 27/27、doctor、7 份文档的 142 个本地目标／25 个锚点及 diff 检查均通过，最终独立证据／文档门已通过，无遗留 finding；仅按原授权提交推送本步 23 文件，排除 `.commandcode/`，不归档或创建 PR。尚无原请求／收据组合、决策重绑定、完整批次调度、离线写入限制 或页面，6.5／6.7 及整个 change 仍未完成。

### 原提交与收据恢复组合（6.6／6.7）

比较基线 `19a98b21`。在原加密批次行保存原请求／幂等键和收据，组合单次安全恢复及收据版本下限清理，不重跑发现或改变映射／来源合同。

1. 思考：已有文件批次和 HTTP 传输尚未组合；请求发出前无持久原操作，响应丢失与提交后刷新失败仍缺端侧完整恢复入口。
2. 计划：同一批次行的版本化包络保持旧文件数组可读，原请求在首次挂起前冻结且在 HTTP 前原子保存；恢复按原键核实，收据成功后保留已提交结果，真实完整快照达到原收据水位才清理整行。
3. 任务拆分：先覆盖精确请求／收据冻结、同键不可替换、旧行兼容、响应丢失重开、404 原键重试、收据保存／刷新／清理失败、版本不足拒绝清理及凭据／取消围栏，再独立审查和跨端验证。
4. 构建：缺失原操作／存储／owner 入口的失败回归后实现同批次包络、不可替换的精确请求、原键核实及单次同键提交。原请求和收据保存为冻结文本，旧文件数组继续可读；收据先保存、再刷新至原版本、按真实完整缓存水位清理。准备后的普通文件移除拒绝，提交后的本地故障返回成功收据及原诊断，原凭据和取消围栏保持。Web 条件更新误判影响行数已由真实 Chrome 回归先红复现，再沿原 CAS 条件写后核实际状态修复；没有读取线程或连接局部变化数。无快照／版本 8 低于收据 9 时保留整行，实际完整缓存 9 才允许清理。
5. 审查：独立 5a 产品／范围、工程（产品编码规范 §7）、恢复／会话安全及最终 diff，与实际独立子 5b 契约复核已完成。唯一 P2 为 Web DML 成功但返回 0 导致原操作保存／清理误报失败，已按上述真实失败回归修复并重审关闭；无遗留 finding。批次引用即永久原键，完成清理后也不得复用，调用方职责已写入 D5 与 owner 注释；未新增证明协议、身份镜像或 tombstone。无 UI 改动，UI 复用与 Hallmark 不适用。
6. 测试与 QA：2026-11-15（UTC），验证 HEAD／比较基线 `19a98b21` 加当前工作树；定向 Host 六类共 64 方法通过，真实 JS 浏览器加密库八方法通过。完整共享自包含矩阵 Host 99 类／839 方法、JS 99 类／838 方法、Wasm 101 类／844 方法，共 2521 方法，0 失败／错误／跳过；三个任务均实际执行，iOS arm64 生产与 Simulator arm64 测试编译通过。日志、当前 18 个 Kotlin 文件 SHA、固定 XML 和核证脚本均在仓库外证据根目录，详见下方。本片只改端侧 SQLite，服务端 Application Service／数据库行为未改，PostgreSQL 不适用；不把浏览器 SQLite 当作服务端双库验证。历史 response integration 及 portfolio live 类依赖外部夹具／服务，按既有外部 init filter 排除，不计通过；相关服务端接口无变化。共享模块无 lint／detekt 任务，由相称编译、diff 与独立工程复核覆盖。
7. 发布：D5、两模块／总览功能地图及 apply 指引已按内部单次恢复原位更新；核对 README、参考资料、主规格／delta、词表、接口／命令示例、配置与迁移，无新 wire、依赖、领域概念或服务端 schema，其余无需修改。本地同表同列包络兼容旧文件数组；代码回退须保留恢复材料，不能把新包络作为损坏输入清除。沿用户撤去额外账户映射／来源证明合同的决定，不新增通用规范。Cross-platform Impact Check：共享恢复与存储影响 Web／Android／iOS，以上共同测试及原生编译覆盖；没有页面、路由、交互或用户文案修改，Hallmark、页面冒烟和设备交互 QA 不适用，真实浏览器存储测试不冒称产品页面验收。strict 27/27、doctor healthy、6 份文档的 143 个本地目标／25 个锚点及 diff 检查通过；最终独立证据门已核代码摘要、固定 XML／日志与本步范围，遗留恢复文案 P3 已原位区分内部单次恢复与自动调度／页面未接状态，可按授权提交推送同分支。自动重派／决策重绑定、退避上限、pending 展示和页面仍待接入，6.6／6.7 不勾选，不归档或创建 PR。

证据根目录 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`：`import-submission-{red,store-red,owner-red}.log` 记录缺失实现的先行失败，`import-submission-web-red.log`／`.xml` 记录真实 Web `cash_import_submission_changed`；`import-submission-owner-focused.log`、`import-submission-cas-focused.log` 的 Host 64 方法及 `import-submission-web-focused.log` 的最终 Web 八方法分别核对。中间未生成测试 XML 的 Web 失败运行未计通过；改用 `*WebLedgerFactCacheTest*` 后按 XML 确认非空。完整终态见 `import-submission-matrix.log`、`import-submission-tested-code.json`、`import-submission-final-results.json`、`import-submission-final-<task>-xml` 及 `verify-import-submission.py`；固定结果不随重跑静默覆盖。

最终命令（在工作树执行，`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`、`NODE_ENV=development`、`CHROME_BIN=/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`，Gradle 实际工具链 Zulu 21.0.11）：

```sh
./compose/gradlew -p compose \
  -I /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/stable-file-input-self-contained.init.gradle \
  :shared:testAndroidHostTest :shared:jsBrowserTest :shared:wasmJsBrowserTest \
  :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosSimulatorArm64 \
  --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/verify-import-submission.py
openspec validate --all --strict
openspec doctor --json
git diff --check
```

Web 证据为 ChromeHeadless 157、Karma `http://localhost:9876/context.html`、756×413：真实加密 OPFS 先保存原请求、关闭重开、保存完整收据、低水位拒绝清理、再次重开后保存版本 9 再清理，物理文件原文／文件名／原字节标记不得明文出现，最终缓存及密钥材料移除；JS／Wasm 均执行同一方法，无测试失败或 Worker 断连。六个 Host 恢复用例使用生产 owner、真实 SQLite 和 HTTP MockEngine：提交后丢响应按原键重开核实且不二次 POST、收据保存／快照刷新／清理故障保留原操作及成功结果、基线拒绝不换正文、凭据在核实期间改变禁止迟到提交；不冒称 HTTP MockEngine 是已部署服务端验证。

### 商户私有边界候选验证（1.3／6.3）

比较基线／核对 HEAD：`1e144137903a2597d539567f6f4827b2ce5d6343`，2026-11-15 14:46 UTC。本步只修改规划记录；Java 候选、探针与生成的 class 均在仓库外 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/merchant-boundary-model`，不改变后端或共享生产源码。

1. 思考：从原始 UTF-16BE 夹具与公开 API 观察恢复反向扫描／正向扫描／缓存分工。单标量映射不能解释 Σ，静态边界也不能表示按查询历史出现的额外边界；现代 cased 判断有 197 个可观察字符与旧口径不同。沿既定 A 类 change 推进兼容验证，不新增业务取舍。
2. 计划：design 原位记录字符类别、最长前缀竞争、反向标记尾缀、半代理读法与严格缓存条件；外部 `MODEL.md` 说明可达残余状态、查询顺序、类别替换、不可观察 cased 属性及证明范围。禁止 JDK 源码／表移植、宿主整串 lower、缩减原语料或线上回退的边界保持。
3. 任务拆分：分开核原完整正规化结果、普通正向边界、每次查询返回值／当前位置、全码点类别替换及真实 Σ 调度；cased／uncased 字母、罗马数字、U+0345、enclosed 字符不合并成一个普通边界代表。冻结属性、同一 Kotlin 实现及完整发现另行实施。
4. 构建：外部候选使用公开 `Character` 属性、正向语义规则和反向重定位；自供 `CharacterIterator` 只观察公开读取。保留失败反例；反向读取不得用后续 `codePointAt` 重新组合高代理，缓存仅在位置严格小于查询前一偏移时复用。未向仓库加入候选生产入口，未修改原 21,309 输入／期望。
5. 审查：独立只读 5a 未发现已复现反例或禁用源码／表移植迹象；状态说明、cased 维度真实 Σ 轨迹及宿主证据已补齐，并独立核对 16 份 Java 源码、17 份 class、4 份日志 SHA、原向量与两个负控制。最终结论无阻断，可关闭外部 Java 研究子步并继续 Kotlin 候选／冻结属性差分，完整生产兼容门保持未完成。纯规划／外部验证不涉及仓库模型、DTO、数据库、API 或 UI，5b、UI 复用与 Hallmark 不适用；生产接线前仍须审查真实实现。
6. 测试与 QA：Temurin `21.0.6+7`／Eclipse Adoptium，默认 locale providers；完整环境、Java 源码／class 和各日志 SHA-256 在外部 `manifest.json`。在该目录执行同一 JDK 的 `javac *.java` 后，以下实际命令均成功且以断言拒绝差异：

   | 命令 | 实际覆盖及结果 |
   |---|---|
   | `java -Xmx1024m VerifyCandidate ../merchant-reference/original-vectors-utf16be.tsv` | 原 21,309 对输入／期望、null、139,611 保留码点 × 8 上下文及 100,000 固定种子串；1,238,198 次正规化、29,363,913 次查询无差异。 |
   | `java -Xmx1024m VerifyClassSequences 6` | 15 类别／UTF-16 宽度代表，长度 ≤6 的 12,204,241 串、311,556,840 次查询无差异；长度仅为覆盖界限。 |
   | `java -Xmx1024m VerifyClassSubstitution` | 每个保留码点在 225 个左右代表环境中替换为同类同宽代表；31,412,475 串、487,156,410 次公开查询返回值及当前位置一致。 |
   | `java -Xmx1024m VerifySigmaSequences 5` | 21 个含 cased 维度的代表，长度 ≤5 的 919,885 个含 Σ 串；1,010,815 个 Σ、1,150,424 次真实顺序查询及完整正规化结果一致。 |

   故意错误的期望与把缓存 `<` 改成 `<=` 的候选均以相应断言失败，负控制日志保留。以上均为 JVM 原型；未运行 Gradle、Kotlin 浏览器／Native、产品页面或双库，不能算这些门通过。参考全标量摘要未被重新采样或改写，候选的完整冻结映射摘要仍待 Kotlin 实现验证。
7. 发布：本步仅回写 design、tasks 与 apply 指引，README、功能地图、词表、协议示例及配置／迁移没有行为变化，无需改写。沿已确认精确兼容及撤回额外证明协议的边界，无需新增通用规范。Cross-platform Impact Check：未改任何运行时，Web／Native／共享生产均无变化；后续 Kotlin 候选须运行同一语料、验证长标记／多 Σ 复杂度并复核完整发现。`openspec validate --all --strict` 27／27、`openspec doctor`、3 份文档的 5 个本地目标／4 个锚点与 `git diff --check` 已通过，独立最终证据门无阻断，按授权提交推送同分支。本步不关闭 1.3／6.3，不归档、不创建 PR，可随规划提交独立回退。

### 成功收据交付与批次清理次序（6.6）

比较基线 `75b20a95`。延续权威成功、原键核实与完整快照清理合同，补齐应用恢复引用可在清理前持久化的入口。

1. 思考：当前核心在结果返回前即清理成功批次；页面若尚未保存收据引用便取消或退出，可能失去最新重派操作的交付入口。
2. 计划：原请求记录收据后调用固定原范围的交付动作，再刷新完整快照并清理批次；交付异常保留成功结果与恢复材料，取消继续传播，后续恢复先读原收据，不重复提交。没有交付动作的内部调用维持既有行为。
3. 任务拆分：先覆盖交付时持久收据／文件仍在、交付失败保成功且重开可恢复、取消及原会话变化；实现后独立复核与共享跨端回归。实际生命周期接线核对还须让本地预览通过现有 typed DTO／domain：本地 `expires_at` 合法为空，不能因旧模型的必填 String 拒绝；保留旧服务端非空到期时间原值，不补造本地 TTL。以真实本地 task 的预览和重读执行失败回归，再调整两个类型。
4. 构建：`beforeCompletion` 缺失命名参数先红；沿原 task／imports／owner 透传交付动作，权威收据持久化后先核原会话并交付，再核原会话、刷新及清理。交付异常保留权威成功、原诊断和恢复材料，取消继续传播；原底层恢复入口及尾随 lambda 保持。本地无 TTL 的真实预览在 typed DTO 解码时因 `expires_at=null` 先行为失败（23s），两个计划模型改为可空到期时间后，真实预览／重读及旧非空值透传断言通过；不改全局 JSON、不补默认日期，也不声称既有 `explicitNulls=false` 会拒绝缺失字段。补真实 SQLite 交付顺序、异常后重开、真实协程取消、换代及成功交付后刷新失败的重复交付回归，并补原 SessionCacheRepository 捕获入口回归。
5. 审查：独立 5a 复核原操作身份、异常／取消、原范围、恢复材料与锁顺序；实际独立子上下文按代码库防劣化调度执行 5b 契约审查，均无 finding。新增重复交付回归与文档经独立复核无 finding，原 5b 结论沿用；随后两行可空计划模型及真实 typed 接入增量经独立 5a 和实际独立子上下文 5b 复核，亦无 finding。无 UI 修改，UI 复用审查不适用；Graph／页面及离线写入限制不在本步。最终独立复核重算当前七份源码及 308 份固定 XML，核实际方法、命令终态和执行来源，确认无遗留 finding，门禁通过。
6. 测试与 QA：2026-11-16，比较基线及验证 HEAD 为 `75b20a95` 加当前工作树。证据根目录 `~/.cache/finance-tracker/offline-ledger-1116/success-delivery` 的 `commands.json` 固定完整 shared 三目标及 iOS 双编译命令／环境；空间恢复后的矩阵实际终态成功（1m22s，187 任务：21 执行／166 up-to-date）。JS 102 类／910 方法、Wasm 104 类／916 方法均在本轮 ChromeHeadless 157 实际执行，失败的租约用例及真实 typed 预览／收据交付亦通过。Host 本轮 UP-TO-DATE，沿当前相同七份源码的实际 `current-host.log`／固定 XML 核 102 类／952 方法；iOS 两任务本轮 UP-TO-DATE，沿相同源码的 `current-native.log` 核两项实际编译成功。`verify.py` 分别验证本轮 Web XML 新鲜度与原 Host／Native 命令终态、同源码摘要、完整非空方法及固定输出摘要；`tested-code.json`／`final-results.json` 固定当前七份 Kotlin SHA、308 份 XML，总 2778 方法，0 失败／错误／跳过。聚焦失败和前期低空间失败／空发现诊断保留在 `red.log`、`typed-plan-red.*`、`first-failed-*` 及 `js-diagnostic-*`，空发现未计通过；用户授权后仅清理已核非在用的生成型 tmp 和指定两处 Gradle 缓存，恢复空间后补验，不能将该观测单独等同唯一根因。清理清单及实际释放空间保留在相邻 `temp-cleanup` 证据目录。当前不改服务端、DML、SQL schema 或 HTTP 合同，服务端双库合同无需重跑，不把本地 SQLite fixture 当作 PostgreSQL 验证。
7. 发布：README、主规格／delta、词表、接口／命令示例、配置与迁移按本片核对，无新 HTTP 接口、存储格式、依赖或领域概念而无需修改；D7、三份功能地图与 apply 指引已同步交付顺序、可空到期时间及重复交付幂等边界。strict 27/27、doctor healthy、6 文档 188 本地目标／36 锚点及 diff 检查通过；最终独立证据门通过，沿已有授权仅提交推送本步至同分支。Cross-platform Impact Check：共享恢复及计划模型影响 Web／Android／iOS，当前源码三测试目标及相称 iOS 编译已验；没有页面、交互或用户文案变化，产品页面冒烟、Hallmark 和原生设备 UI QA 不适用。沿用户撤回额外账户／来源证明协议的决定，无新增通用规范；本次缓存清理授权属于环境操作，不沉淀为产品规则。代码可独立回退，无存储迁移，不删除原请求／幂等键及材料。Graph／页面仍未切换，旧结果页与新写集收据的计划身份、实际结果计数仍需在正式接线对齐，不用预览计数伪装实际保存结果；算法任务 1.3／6.2／6.3 经独立完成性审计及主流程摘要／固定结果核对已关闭，整体 30/71，不归档或创建 PR。

### 本地导入任务协调与确认冻结（6.4／6.6）

比较基线 `5cc7e29e`。复用实际原会话、冻结文件、生产计算、只读计划及原键恢复，补齐单批次生命周期协调，不新增账户／来源证明合同。

1. 思考：底层能力已齐，但缺少持有实际任务修订及当前计划的协调者；文件解析与预览／保存选择竞争可能发表过时结果。
2. 计划：单批次任务锁只协调保存、移除、取消、计算及确认冻结，解析在锁外；同范围原句柄贯穿全部动作。文件变更废弃旧计划，预览成功后保存真实核对结论再发表新计划及修订，无 TTL；重开恢复文件和选择，不复活旧计划。确认先冻结原请求，网络恢复复用既有服务。
3. 任务拆分：先覆盖完整准备／退款预览／只读／确认链、文件竞争、陈旧修订／计划、取消、恢复及原会话失效，再实现并执行独立复核、跨端回归与相称编译。
4. 构建：入口缺失先在 `red.log` 编译失败；首轮聚焦 8 方法中 1 个测试误要求合法空处理明细非空，修正断言。保存回执丢失回归曾误用行引用、随后 Map 星投影编译失败，已保留失败记录并改用真实操作引用。`boundary-current-red.log` 的 13 方法中 5 项真实失败覆盖保存后异常、首解析取消、陈旧事实取消及生产重派旧计划；两项使用真实 SQLite。`cancellation-red.log` 的 15 方法中 2 项真实失败补充退休后 caller 取消与等待最终发表锁时换登录。修后 `focused-green.log` 15 方法通过，保留原 XML；未知网络失败仍保原请求与原预览，不冒称业务失败。`reads-red.log` 的读取计数真实失败及同值重试夹具错误分别保留，修后 `focused-final.log` 18 方法通过；四种缓存读取不再重读全部原文件，写后异常不留下旧计划，同编码文件重试保留当前计划。
5. 审查：独立 5a 与实际子 5b 确认 P1：新 review 落盘后异常仍可确认旧计划；P2：重派退休请求后旧计划仍可冻结、首解析及陈旧事实不能取消。已先废旧计划再存 review、恢复期间封闭重新冻结并处理取消后的退休观察、取消仅读取真实文件；最终发表锁内重新核原会话，失败诊断保留。15 方法聚焦通过，两级独立窄复核已闭合上述问题。逐页重解码全部原文的成本风险已按唯一任务写前失效收窄：缓存读只核原会话及必要退休观察，真实文件变更前废旧计划，同编码重试保持计划／修订；原文读取计数回归先红，实际 SQLite 写后异常及同值重试回归通过。最终聚焦 18 方法通过，性能增量独立 5a／实际子 5b 无新增 finding；不改 UI，UI 专项不适用。
6. 测试与 QA：2026-11-16（Asia/Shanghai），比较基线及验证 HEAD `5cc7e29e` 加本片工作树。`focused-final.log` 18 方法通过；最终 `matrix.log` 2 分 14 秒成功，187 任务中 30 实际执行／157 up-to-date。`commands.json` 固定原 quality／自包含 init、单 worker、3 GiB、非 daemon／in-process 参数，运行 shared 完整 Host、JS、Wasm 自包含单测及 iOS arm64 主编译／Simulator 测试编译，五任务均实际执行。Host 102 类／946 方法、ChromeHeadless 157 的 JS 102／909、Wasm 104／915，共 2770 方法零失败／错误／跳过；新增 common 12 方法在三端实跑，真实 SQLite 新增 6 方法覆盖生产重派、退休后取消、真实 review／文件 UPDATE 后异常、最终发表锁换登录及同值重试。6 份 Kotlin SHA 和 308 份 fresh 非空 XML 经 `verify.py` 核对并固定。不改 backend、domain 规则、schema 或 wire，不重复无改动的领域／后端矩阵；shared 无 lint／detekt 任务，沿编译、diff 与独立工程审查。沿既有 init 仅排除依赖外置后端的 shared Live 集成类，不计通过；内存会话组合不冒称 SQL 证据，SQLite 端侧缓存不称 PostgreSQL 或整个服务双库，浏览器单元测试不称产品页面冒烟。
7. 发布：README、主规格／delta、词表、接口／命令示例、配置与迁移已按本片核对，无新公开接口、存储格式、依赖或领域概念，其余无需修改；D7、三份功能地图与 apply skill 已同步内部任务边界。strict 27/27、doctor healthy、6 文档 186 本地目标／34 锚点及 diff 检查通过；最终独立复核直接重算 6 份源码 SHA 与 308 份固定 XML SHA，核对实际方法、五任务终态及文档边界，门禁通过，无遗留 finding。Cross-platform Impact Check：共同任务协调影响 Web／Android／iOS，共同测试及相称编译已验；无页面、交互或用户文案变化，产品浏览器冒烟、Hallmark 和原生设备 UI QA 不适用。沿用户撤回额外账户／来源证明协议的决定，无新增通用规范。可独立回退代码，无存储迁移；保留文件、选择、已冻结请求及原键，不通过删除批次回退。Graph 的旧生命周期、页面、离线写入限制仍未切换，完整应用恢复仍需对齐收据交付与恢复指针；27/71 不变，不归档或创建 PR，完成本步门禁后按已有授权提交推送。

本步证据根为 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1116/local-task`。失败日志及 XML 保留实际问题，不把夹具错误算作生产行为失败；`tested-code.json`／`final-results.json`、固定 `final-*-xml` 及 `verify.py` 绑定测试代码、实际方法和新鲜结果。浏览器为 ChromeHeadless 157、Karma `http://localhost:9876/context.html`，覆盖共同任务组合；完整用户流程仍待产品接线验收。

实际命令（工作树根执行，`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`、`NODE_ENV=development`、`CHROME_BIN=/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`；不加载服务端数据库配置）：

```sh
./compose/gradlew -p compose \
  -I /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/quality.init.gradle \
  -I /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/stable-file-input-self-contained.init.gradle \
  :shared:testAndroidHostTest :shared:jsBrowserTest :shared:wasmJsBrowserTest \
  :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosSimulatorArm64 \
  --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1116/local-task/verify.py
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1116/local-task/check-links.py
openspec validate --all --strict
openspec doctor --json
git diff --check
```

### 核对计划只读视图与本地读取（1.3／6.4）

比较基线 `511868f4`。延续既有核对、分页、详情和月度汇总合同，把页面实际需要的纯读取规则移入共享层；不改变来源／账户映射写集、不切换半成品生命周期。

1. 思考：完整本地计算已有冻结计划，但分页、字段引用展开、公开字段裁剪、可读校验与导入月度汇总仍只在 backend，当前页面不能读取本地计划。
2. 计划：共享同一计划视图规则，backend 仅保留原 BigInteger、组件标识、记录值及日历／金额薄桥；本地读取冻结 JSON，保留数字词法、引用、完整候选和字段编辑信息。月度分组及按原顺序的 80 位 HALF_EVEN 累计单源共享，平台只处理时区／时刻与数值表示。分页游标继续绑定计划、类型和筛选，详情读取不改输入，保留虚拟组件标识隐藏和原错误顺序。不得伪造计划编号、任务修订或 TTL，不把只读视图当成提交资格。
3. 任务拆分：先以原 view／readable／monthly 黄金测试驱动本地读取入口，保留全部原向量；验证跨端分页、原始数字、详情不变性、全量月汇总、边界／错误及生产计算组合。再复核原 backend 回归与实际平台日历、独立工程／契约检查和适用完整矩阵。
4. 构建：原视图主体及全量月份分组已移入共享规则，backend 只留原数值／记录／时区桥；本地只读入口已接 `LocalCashImportCalculation.reader(planId)`，不补修订或 TTL。仓库外 `plan-reads/red.log` 先因缺读取入口编译失败；`focused.log` 的本地原黄金 5 方法及 backend 原 view／readable／monthly 14 方法通过，`focused-platform.log` 的平台桥 5 方法及领域 lint／适用 detekt 通过。新增 3 项数词／虚拟 ID、Unicode 游标和容器隔离回归通过。叶值保真 P2 经 `leaf-red.log` 的 10 方法中两项真实失败复现：`-0` 被规范成 `0`，孤立代理字符串被编码拒绝；已保留 XML，局部修正读取器的解码与 String 叶值输出，不改全局计算／传输编码器，`leaf-green.log` 的 10 方法通过。
5. 审查：独立 5a 与实际独立 5b 已交叉确认并窄复核关闭叶值保真 P2，无新增 finding。已核局部数字 token／UTF-16 边界、两项真实红灯、完整原规则抽取、汇总、游标、原错误顺序及数字表／阈值缓存；全局编码及用户撤回的证明合同未变。无 UI 变化，UI 专项不适用；完整 QA 另验。
6. 测试与 QA：首轮完整矩阵 `first-matrix.log` 在 JS／Wasm／Native 编译发现原视图的 JVM 专用 `Map.getOrDefault`；JVM、Host 与 backend 通过，未计跨端通过。已逐一替换 11 处为 common Map 存在性读取，保留显式 null 与缺省区别，独立窄复核无 finding，并补成员缺省／null 回归；修后 `matrix.log` 8 分 5 秒成功，235 任务中 64 实际执行／171 up-to-date。原失败日志、源 SHA 及实际生成的 XML 已保留。最终 `commands.json` 以原 quality／自包含 init、单 worker、3 GiB、非 daemon／in-process 参数运行完整领域 JVM／JS／Wasm／iOS Simulator 单测及 Android／iOS 编译、领域 lint／适用 detekt、完整 shared Host／JS／Wasm 自包含单测与 iOS 编译，再运行受影响的 backend 原 view／readable／monthly 三类测试。领域分别 198 类／887 方法、168／839、168／839、168／839，shared 分别 102／928、102／897、104／903，backend 3／14，共 6146 方法零失败／错误／跳过；ChromeHeadless 157 两目标、新读取器各 11 方法及实际平台桥各 5 方法均实跑。11 份 Kotlin SHA 与 1013 份 fresh 非空 XML 经 `verify.py` 核对并固定；原向量生成器三组 `--check` 通过。沿既有 init 仅排除依赖外置后端的 shared Live 集成类，backend 仅声称受影响的原只读三类，不冒称全部后端或历史迁移回归。后端 `:backend:ktlintCheck :backend:detektMain :backend:detektTest` 沿相同 init／单 worker／3 GiB 参数通过，`backend-quality.log` 2 分 57 秒成功，20 任务中 10 实际执行／10 up-to-date；shared 无对应任务。不改 DML／schema，不能以纯读取矩阵冒称整个服务或页面通过。
7. 发布：README、接口／命令／配置示例、迁移和词表按本片核对，无新 HTTP、存储格式、依赖、领域概念而无需修改；D7、三份功能地图及 apply skill 已同步实际只读接入和未接页面边界。strict 27/27、doctor、6 文档 185 本地目标／34 锚点及 diff 检查通过；最终独立复核重算 11 份 Kotlin SHA 与 1013 份固定 XML hash，核对实际方法、命令终态和文档边界，无新增 finding，后端质量检查终态通过后本步门禁完成。Cross-platform Impact Check：共享只读逻辑及三端平台桥受影响，实际 Web 两目标和 Native 单测／相称编译已验，不把浏览器单元测试称为产品页面冒烟或设备 UI QA。用户撤回的额外证明合同保持撤回，无新通用规范；纯读取可代码回退，不改变已存文件、核对输入、原请求或幂等键。完整生命周期、页面及队列尚未接线，27/71 不变，不归档／PR；完成本步门禁后按已有授权提交推送。

### 文件状态与同快照账户准备视图（6.4／8.2／8.4）

比较基线 `63d82bc3`。延续既有单文件失败隔离、密码边界和账户预选规则，补齐本地准备阶段的真实数据入口。

1. 思考：现有冻结文件只表达成功解析，不能恢复需密码／已知解析失败的文件条目；账户准备仍无同范围的一次性文件、分组、预选与保存选择视图。
2. 计划：成功文件沿原 v1 编码保留原字节和文档；已知格式／密码错误仅保存 v2 的文件元数据、摘要、状态与安全错误码，不保存失败正文或密码。重试由本次文件／密码输入执行，恢复条目不伪造可重读文件。原成功捕获入口仍按异常传播；新增准备入口只转换已声明的解析失败，401／403、网络、取消和程序错误继续抛出。解析不占 owner 资源锁，准备文件保存复用原原子追加／替换及提交冻结。准备视图在同一 owner 锁内读文件、事实和原选择，复用共享文件视图、资金扫描与预选；文件名仅 basename／255 码点，不脱敏改写，平台账号组沿原摘要。无 TTL、伪造计划或新公开协议；本地任务修订、完整调度及页面仍须接线。
3. 任务拆分：先覆盖缺失的新捕获／准备视图，核旧编码、失败编码闭合性、密码／正文不落盘、原凭据、各类异常、混合状态、账户选择与平台组、文件名、同范围／同快照及提交冻结；再执行独立工程／契约复核、真实 SQLite 与 Web 加密重开和跨端验证。
4. 构建：已实现 v1／v2 文件编码、已知失败捕获、原 owner／会话解析与保存入口及同快照准备视图。仓库外 `preparation-states/red.log` 最初含缺失入口和测试 DTO 构造错误，`focused.log`／`owner-red.log` 为私有函数可见性编译失败，不冒称行为红灯。`focused-green.log` 记录 32 方法中 4 项失败：三项为行数经通用浮点编码变成 `1.0`／`2.0`，一项为文件名测试夹具缺资金显示名；分别对明确行数字段编码整数及使用完整生产夹具，保留失败 XML。`focused-current.log` 的 Host 49 方法（准备 7、会话 25、真实 SQLite 文件 17）零失败／错误／跳过；新增 Web 加密重开见完整矩阵。
5. 审查：独立 5a 产品／范围、工程、安全及最终 diff 与实际独立 5b 契约复核均无 finding。核 v1 兼容、v2 状态与安全码闭合、异常来源、同锁快照、原凭据及事实资格、提交冻结和字段级整数编码；没有额外账户／来源证明协议。无 UI 变化，UI 复用与 Hallmark 不适用；完整 Web 加密重开及跨端 QA 另验，不以静态复核代替运行。
6. 测试与 QA：仓库外证据根为 `~/.cache/finance-tracker/offline-ledger-1116/preparation-states`。第一轮完整矩阵 `first-matrix.log` 的 Host／JS／Wasm 分别 923／892／898 方法，仅同一旧测试把新支持的 v2 当未知版本而失败；两种 iOS 编译通过，失败 XML 和源 SHA 保留。仅将该测试未知版本样本改 v3，未放宽状态校验。最终 `matrix.log` 1 分 36 秒成功，187 任务中 26 实际执行／161 up-to-date；`commands.json` 固定原自包含 init、单 worker、3 GiB、非 daemon、in-process 参数，执行 `:shared:testAndroidHostTest :shared:jsBrowserTest :shared:wasmJsBrowserTest :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosSimulatorArm64`。Host 101 类／923 方法，ChromeHeadless 157 的 JS 101／892、Wasm 103／898，共 2713 方法零失败／错误／跳过；新增准备类各 7、会话各 25、真实 SQL 文件 17、Web 实际加密文件重开各 11 均实跑。10 份 Kotlin SHA 与 305 份 fresh 非空 XML 经 `verify.py` 核对并固定，iOS 主编译 up-to-date、模拟器测试编译实际执行；shared 无 lint／detekt 任务。沿既有 init 排除需外置后端的 Kotlin Response／Portfolio Live 集成类，不冒称完整线上回归；本片未改服务端、领域算法、wire、schema 或依赖，不重复双后端及产品页面冒烟，也不把端侧缓存证据称为双后端矩阵。
7. 发布：README、接口／配置／迁移示例和词表按本片核对，无公开入口或新领域术语而无需修改；D7、三份功能地图与 apply skill 已按内部接入状态更新。strict 27/27、doctor、6 文档 181 本地目标／34 锚点及含新增文件 diff 检查通过；提交前独立复核直接核对 10 份当前 Kotlin SHA、305 份固定 XML、真实 testcases、命令终态、旧版本夹具修订及当前文档，最终门通过，无遗留 finding。Cross-platform Impact Check：共享能力影响 Web／Android／iOS，实际两浏览器目标与相称编译已验，无页面或设备 QA 声明。保留撤回额外账户／来源证明协议的用户决定，无需新增通用规范。SQL schema 未变，但旧代码不能解码 v2；回退保留加密批次、选择和原请求／幂等键，恢复支持该编码的版本后处理，不清空输入。完整生命周期、页面和离线写入限制仍未接线，27/71 不变，不归档或创建 PR；按已有授权仅提交推送本步。

### 本地文件批次的追加、替换与移除（6.4）

比较基线 `5774a389`。延续既有文件操作、核对选择失效和原请求冻结规则，补齐实际生命周期所需的持久化及原会话入口。

1. 思考：当前批次只能首次保存或整体删除，不能承接页面追加、替换和移除；将整个列表直接覆盖会丢并发追加，或改动结果未知的原请求。
2. 计划：独立追加在当前列表内按稳定引用合并，同引用同编码不重复写、异值拒绝；替换／移除核对原文件完整编码，保留顺序及其他文件。批次有原提交或收据时拒绝改动。文件实际变化时沿原规则保留账户及关系选择／最小上下文，过滤被替换／移除文件的行选择，清除 entry 选择及旧确认结论；与文件列表同一 SQL 行原子保存。不新增修订、证明协议或持久状态；移除最后文件保留空批次和原尝试序号，不能借重新追加复用提交键。初次保存仍要求非空，空批次不得准备提交。
3. 任务拆分：先补缺失入口的失败测试，再实施原存储、范围适配、原 owner 与会话句柄；覆盖顺序、幂等、独立追加、同引用冲突、陈旧文件、SQL 失败、空批次、原请求冻结、重开及范围隔离。解析／HTTP 在锁外，提交前重核原文件与会话，取消／解析失败不保存半份变化。独立工程及适用契约复核后执行受影响回归、真实 Web 加密重开与相称编译。
4. 构建：SQL、核对输入及 owner 入口先因缺少方法失败；已补同一行文件／选择变更、空批次保留尝试序号、缓存串行入口及原会话捕获，解析继续锁外使用原凭据。新测试覆盖五项真实 SQL 存储回归、两项缓存互斥行为、三项生产会话组合及一个共享输入变换，另补真实 Web 加密重开。首次聚焦编译的测试泛型错误和空输入异常类型预期错误已修正，仅调整测试，不改变生产异常边界；日志及失败 XML 保留。最终聚焦 22 个会话入口、16 个 SQL 文件、21 个范围缓存及 8 个核对输入方法，共 67 方法通过，0 失败／错误／跳过；完整矩阵已执行通过。
5. 审查：独立 5a 范围／工程（产品编码规范 §7）及实际子 5b 契约复核无阻断；核单行 files／review 原子性、互斥合并、原文件编码门、精确重试、提交／收据冻结、空批次保留尝试序号及原范围围栏。D5 残余措辞已收窄为首次保存限制与逐文件状态／页面待接；补充旧 expected 而当前结果已相同的重试不执行 UPDATE、不清除后来确认的断言。无 UI 变化，UI 复用与 Hallmark 不适用。
6. 测试与 QA：2026-11-15（UTC），验证 HEAD／比较基线 `5774a389` 加本片工作树。最终聚焦 67 方法通过；完整 domain JVM 197 类／876 方法、JS／Wasm／iOS Simulator 各 167 类／828 方法，shared Host 100 类／912 方法、JS 100 类／881 方法、Wasm 102 类／887 方法，共 6040 方法，0 失败／错误／跳过，七项测试任务均实际执行。矩阵 6m22s 成功，229 任务：56 执行／173 up-to-date。domain Android、两模块 iOS arm64 及 Simulator 测试编译通过；domain ktlint 与适用 detekt 通过，JVM production／AndroidHost 专属 detekt 为 NO-SOURCE；shared 无 lint／detekt 任务，沿编译、diff 与独立工程审查。12 份 Kotlin SHA、1000 份固定新鲜非空 XML、精确方法数与成功日志终态已核对。真实 SQLite 覆盖写入中止时文件与选择一起保旧、不同范围隔离、顺序／幂等／冲突、空批次重开和原尝试键；实际缓存互斥覆盖同时追加不丢文件及等待前冻结列表。ChromeHeadless 157 的 JS／Wasm 实跑真实加密缓存：文件追加／替换／移除及选择失效随库重开，空批次追加后仍使用 attempt:1，已有请求冻结，最后文件与密钥清理完成。共同 owner 测试使用内存存储，只证明真实解析／会话组合及围栏，不替代 SQL 证据。端侧缓存没有 PostgreSQL 运行时，本片不改服务端 schema／DML／协议，双后端矩阵不适用，不能把 SQLite／Web 证据称为双后端通过。shared 按既有 init 排除外部 response／portfolio live 夹具，不计通过。
7. 发布：design、三份功能地图及 apply 指引已按实际文件变更入口和提交冻结更新；核对 README、主规格／delta、词表、参考资料、接口／命令示例、配置及迁移。本片未增加表、字段、wire 或配置，空批次复用原 files／attempt，其余文档无需改写。保持既有选择失效规则及用户撤回额外证明协议的决定，无新用户取舍，无需新增通用规范。Cross-platform Impact Check：共同入口影响 Web／Android／iOS，已执行共同回归及相称编译；未改页面、路由、用户文案或交互，产品页面冒烟、Hallmark、UI 复用和原生设备 QA 不适用。无服务端迁移，代码可独立回退且保留原材料；旧代码不能消费的新空批次保留原编码，不以重建库回退。逐文件状态、页面与离线写入限制仍待接线，本片不勾选 6.4，不归档、不创建 PR。strict 27／27、doctor healthy、六份文档 181 个本地目标／34 个锚点与 diff 检查通过；最终独立证据门已通过，无遗留 finding，按既有授权提交推送同分支。

证据根目录 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1116/file-mutations`：`red.log`／`review-red.log`／`owner-red.log` 保留缺少存储、核对输入及 owner 入口的先行编译失败；测试泛型编译错误与错误期待异常类型的记录保留在 `focused.log`／`focused2.log` 及失败 XML，未当作生产行为失败。`focused3.log` 为最终聚焦成功；随后补充的旧 expected 精确重试与非空关系选择断言在完整矩阵实跑。`matrix.log` 为完整成功终态，`commands.json`／`run-matrix.py` 记录命令且不加载服务端数据库配置。`tested-code.json`／`final-results.json`、固定 `final-*-xml` 及 `verify.py` 核对源码、实际执行、新鲜非空结果及完整数量。浏览器为 ChromeHeadless 157、Karma `http://localhost:9876/context.html`，覆盖真实存储与共同业务入口，不代替产品页面验收。

实际命令（工作树根目录执行；`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`、`NODE_ENV=development`、`CHROME_BIN=/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`）：

```sh
./compose/gradlew -p compose \
  -I /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/quality.init.gradle \
  -I /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/stable-file-input-self-contained.init.gradle \
  :business-domain:jvmTest :business-domain:jsBrowserTest :business-domain:wasmJsBrowserTest \
  :business-domain:iosSimulatorArm64Test :business-domain:compileAndroidMain \
  :business-domain:compileKotlinIosArm64 :business-domain:compileTestKotlinIosSimulatorArm64 \
  :business-domain:ktlintCheck :business-domain:detektMetadataCommonMain \
  :business-domain:detektJvmMain :business-domain:detektJvmTest :business-domain:detektAndroidHostTest \
  :shared:testAndroidHostTest :shared:jsBrowserTest :shared:wasmJsBrowserTest \
  :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosSimulatorArm64 \
  --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1116/file-mutations/verify.py
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1116/file-mutations/check-links.py
openspec validate --all --strict
openspec doctor --json
git diff --check
```

### 会话仓储的原范围导入入口（6.4／6.6）

比较基线 `d0c001e4`。将已有生产导入能力交给应用会话仓储，不改变文件、选择、写集或原键恢复合同。

1. 思考：Graph 使用的会话仓储只暴露离线浏览读取；实际导入生命周期无法取得已核验且绑定原凭据／工作区的本地能力。
2. 计划：由完成恢复的原 restoration 提供限制为导入动作的入口，复用同一个 owner；仓储捕获原 ActiveLocal／generation，动作前后核原发表；资金预选／计算另核事实可读资格。在线和离线均可取得，不从浏览专用 offline 筛选推断导入资格；未恢复或无工作区不提供入口。原键恢复不受陈旧事实门阻挡，恢复开始即封闭陈旧事实；成功刷新后按本次 epoch 重新开放，迟到旧刷新不能开放。可变输入在入口首次挂起前冻结，关闭或换代拒绝迟到结果，不创建新 owner 或清除原恢复材料。
3. 任务拆分：先覆盖真实 restoration／owner 的文件保存、资金预选、完整预览和恢复入口，再核未恢复／无工作区、在线与离线、原 scope／令牌换代、刷新失败、迟到读及输入冻结；独立工程及适用契约复核后执行 shared 跨端回归与相称编译。
4. 构建：入口先因缺失 captureImports 失败，再以原 owner 薄入口组合文件、资金预选、完整计算、稳定选择、原请求准备及生产恢复；可变输入在首次 guard 前冻结。真实 parse-only／收据 401 后缓存仍可读先红复现，再复用原拒绝清理。借现有代次后又以真实迟到 401 复现新 reader 票据失效，固定为同一 storage 租约内 token 检查及 clear；同代次身份 writer 必须等原清理释放后才能写入。未选择删除的读取／释放失败保原 owner，确认释放后才替换原固定 scope 位置的重读动作；已选择删除只继续原 storage.clear。同令牌在途恢复的失败回归进一步驱动原 PendingRestoration 对象所有权：异步工厂前登记原 token，已取得的实际 owner 同锁登记，拒绝时同锁封闭匹配的发表与候选，锁外关闭及条件清 token；覆盖工厂尚未返回、认证在途、快照已持租约与凭据读／清失败。关闭取消异常的先行回归后，失败保留原对象、继续其他清理并保留原 401；真正调用方取消仍传播。19 个入口、12 个令牌清理及原 36 个仓储方法共 67 方法最终聚焦通过，0 失败／错误／跳过；真实 Web 加密回归已在 JS／Wasm 的 ChromeHeadless 实际执行通过。
5. 审查：独立 5a 与实际子 5b 发现 P1：新入口未接真实 401 的原令牌清理；已用 parse-only／收据真实 HTTP 拒绝复现缓存仍可读，合成 401 不应清理。已复用原 withRejectionCleanup，实际迟到旧 401 暴露目录换代会封闭新真实 restoration；只读借票据后，独立复核继续指出同 G 探针／擦除分属两次租约可删新身份，已固定为同一 storage 租约检查及 clear，并补同 G writer、换代拒绝、临时读取重试及真实 Web 回归。同租约及重试、同 token 在途恢复对象所有权已通过增量独立静态复核：登记、发表及拒绝同锁，网络与资源关闭在锁外，条件清 token 复用原凭据锁；凭据存储故障不能跳过 owner 封闭，不同新 token 不受影响。最后发现的 synthetic close cancellation 已以失败回归复现并修复：原资源保留、主失败及次级诊断、后续清理必须同时保真；67 方法聚焦通过，两级增量独立重审关闭 P1 及关闭异常项，无新增 finding；完整 QA 通过，无遗留 finding。无 UI 变化，UI 复用与 Hallmark 不适用。
6. 测试与 QA：2026-11-15（UTC），验证 HEAD／比较基线 `d0c001e4` 加当前工作树。67 方法聚焦通过；shared 完整 Host 100 类／902 方法、JS 100 类／877 方法、Wasm 102 类／883 方法，共 2662 方法，0 失败／错误／跳过，三项测试任务均实际执行。完整矩阵 2m08s 成功，187 任务：30 执行／157 up-to-date；shared iOS arm64 主代码及 Simulator 测试编译通过，Android 主代码由 Host 测试依赖编译。11 份 Kotlin SHA 与 302 份新鲜非空 XML、精确方法数及成功日志终态已核对。真实 ChromeHeadless 157 的 JS／Wasm 用默认平台工厂确认旧 token 文件／密钥被删，新 token 的文件、密钥、身份、票据与可重开文件完整保留；共同并发测试覆盖同代次 writer、迟到响应、凭据故障和持租约关闭失败。真实业务生产计算及原键 HTTP 组合使用内存夹具，不宣称服务端事务或双库验证；本片不改服务端 DML／schema，沿既有双库证据，不新增双库矩阵。shared 按既有 init 排除外部 response／portfolio live 夹具，不计通过；shared 无 lint／detekt 任务，采用编译、diff 及独立工程复核。页面与自动调度仍待接入。
7. 发布：design、三份功能地图与 apply 指引已按原范围导入入口及 401 清理更新；核对 README、主规格／delta、词表、参考资料、接口／命令示例、配置及迁移，本片无新 wire、依赖、存储格式或领域概念，其余无需改写。未恢复用户撤回的映射／来源证明协议，无新用户取舍，无需新增通用规范。Cross-platform Impact Check：共享入口影响 Web／Android／iOS，以上共同测试及相称编译覆盖；未改用户页面、路由、文案或交互，产品页面冒烟、Hallmark、UI 复用及原生设备 QA 不适用。保留原恢复材料，无迁移，可独立回退代码；完整文件生命周期、页面与离线写入限制尚未接，本片不勾选 6.4／6.6，不归档或创建 PR。strict 27／27、doctor healthy、六份文档 181 个本地目标／34 个锚点及 diff 检查通过；最终独立证据门已通过，无遗留 finding，按既有授权提交推送同分支。

证据根目录 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/session-import-access`：`rejection-red.log`／`late-rejection-red.log` 及对应 XML 保留原 401 清理与新票据失效失败，`pending-rejected-restoration-red.log`／对应 XML 保留同 token 迟到认证重新发表失败，`rejection-close-cancel-red.log`／对应 XML 保留关闭取消替换原拒绝失败。`rejection-close-cancel-green.log` 与固定三类 XML 为最终 67 方法聚焦成功。其他构建／夹具失败原样保留，不计通过。完整矩阵在 `matrix.log`，`commands.json`／`run-matrix.py` 记录命令，`tested-code.json`／`final-results.json`、固定 `final-*-xml` 及 `verify.py` 核当前源码、实际执行、新鲜结果和数量。浏览器为 ChromeHeadless 157，Karma `http://localhost:9876/context.html`；这里验证内部能力及真实加密缓存，不代替产品页面验收。

实际命令（在工作树执行；`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`、`NODE_ENV=development`、`CHROME_BIN=/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`）：

```sh
./compose/gradlew -p compose \
  -I /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/stable-file-input-self-contained.init.gradle \
  :shared:testAndroidHostTest :shared:jsBrowserTest :shared:wasmJsBrowserTest \
  :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosSimulatorArm64 \
  --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/session-import-access/verify.py
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/session-import-access/check-links.py
openspec validate --all --strict
openspec doctor --json
git diff --check
```

### 本地资金账户预选及同范围批次读取（1.3／6.4）

比较基线 `b02187d4`。按原规则迁移账户预选及缓存读取，不新增账户映射证明、持久状态或用户取舍。

1. 思考：文件保存与完整预览已接 owner，但账户步骤的确认记录／旧记录／别名预选仍只在 SQL 服务；本地生命周期不能复用旧远程会话完成离线账户准备。
2. 计划：共享预选决定及其 key／结果模型，后端保留原分批 SQL；本地读取同一快照，owner 从同范围已保存文件扫描资金组并返回预选。映射存在但为空或目标不合资格时不回退，微信及唯一性、别名数字域、旧渠道适用范围保持。
3. 任务拆分：先以缺失共享／本地入口失败；覆盖优先级、空目标、资格变化、唯一性、旧渠道、完整数字账号、只读及异常传播；以当前 schema 的真实 SQLite／PostgreSQL 新矩阵及完整后端回归验证 SQL 桥，再跑领域和共享跨端、相称编译与质量门。
4. 构建：共享入口缺失的先行失败后提取原 SQL 预选决定，保留当前／旧映射存在性、原别名及过滤后唯一候选；后端只保留原工作区批量查询。端侧以请求键集合索引读取同一快照，owner 组合已保存文件、资金组及预选，并在返回前核原身份。领域七方法与 owner 30 方法聚焦通过；新当前 schema 的 SQLite／PostgreSQL 四方法通过，覆盖失效当前目标不回退和 405 组跨三批别名读取，读前后完整数据库不变。两次夹具约束失败（空账户及重复账户名）已修正为合法停用账户，不改生产、不放宽 schema；失败日志和 XML 保留。
5. 审查：独立 5a 产品／范围、工程（产品编码规范 §7）、身份／异常安全和最终 diff，与实际独立子 5b 契约复核均无 finding。核映射存在性不等于非空目标、过滤后唯一、旧渠道资格、SQL 工作区／排序／200 分批、精确身份／修订及本地集合索引；没有新证明协议或持久状态。无 UI 变化，UI 复用与 Hallmark 不适用。
6. 测试与 QA：2026-11-15（UTC），比较基线／验证 HEAD `b02187d4` 加当前工作树。领域七方法、owner 30 方法及真实双库四方法共 41 方法聚焦通过；完整 domain JVM 197 类／875 方法，JS／Wasm／iOS Simulator 各 167 类／827 方法，shared Host 99 类／879 方法、JS 99 类／853 方法、Wasm 101 类／859 方法，backend 198 类／1332 方法，共 7279 方法，0 失败／错误／跳过，八个测试任务均实际执行。矩阵成功 10m25s，244 任务：61 执行／183 up-to-date。领域 Android 与 iOS arm64、两模块 Simulator 测试及 shared iOS arm64 编译通过；domain／backend ktlint 及适用 detekt 通过，domain JVM production／AndroidHost 专属 detekt 为 NO-SOURCE；shared 无 lint／detekt 任务，沿相称编译、diff 与独立工程审查。新公共规则在四运行时、owner 新两方法在 Host／JS／Wasm 实跑；双库四方法使用显式既有 `postgres.env` 的专用 `_test` 库及新建临时 SQLite，真实原 SQL 和当前完整快照得到同一字面期望，包含停用当前目标不回退及全部 405 别名组；读前后全库状态不变。默认 backend 排除 HistoricalMigrationEvidence，shared 按既有 init 排除外部 response／portfolio live 夹具，不计通过。七份 Kotlin SHA、1195 份固定新鲜非空 XML、精确方法数及日志终态已核对；两次夹具失败保留，未计通过。
7. 发布：design、三份功能地图及 apply 指引已按实际预选与 owner 读取更新；核对 README、主规格／delta、词表、参考资料、接口／命令示例、配置和迁移，无新 wire、依赖、存储格式或领域概念，其余无需修改。原映射发布仍只采用实际使用且未被单笔覆盖的选择，未恢复用户撤回的额外证明契约；本片沿现有语义实施，无新用户取舍，无需新增通用规范。Cross-platform Impact Check：共同规则同时影响 backend／Web／Android／iOS；上述真实浏览器测试、Native 执行及编译覆盖，没有页面、路由、交互或用户文案改动，产品页面冒烟、Hallmark、UI 复用及设备交互 QA 不适用。SQL 保留原排序与 200 分批，本地按键集合线性筛选完整事实，不引入候选搜索；完整导入性能与长任务响应仍须产品接线验收。代码可独立回退，无迁移，保留原批次恢复材料。页面上下文、逐文件生命周期、自动调度及队列仍未接，本片不勾选 1.3／6.4，不归档或创建 PR。strict 27/27、doctor healthy、六份文档 178 个本地目标／34 个锚点及 diff 检查通过；最终独立证据门通过，无遗留 finding，按既有授权提交推送同分支。

证据根目录 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/funding-suggestions`：`red.log` 保留缺失共享入口失败，`focused.log` 记录领域和 owner 成功。`parity.log`／`parity2.log` 及对应 `parity-fixture-failure.xml`／`parity2-fixture-failure.xml` 保留两次夹具约束失败，`parity3.log`／`parity-green.xml` 为修正后双库四方法成功。完整矩阵终态在 `matrix.log`；`tested-code.json`、`final-results.json`、固定 `final-*-xml` 与 `verify.py` 核对当前源码、实际执行、新鲜非空结果及完整数量，固定 XML 不随重跑覆盖。`commands.json`、`run-matrix.py` 记录实际命令及显式测试库配置文件路径，不保存凭据；`validate.log`／`doctor.json`、`links.json` 核 OpenSpec 与文档。浏览器为 ChromeHeadless 157、Karma `http://localhost:9876/context.html`，覆盖同一预选规则、精确身份／修订及 owner 身份围栏；不代替产品页面验收。

实际命令（在工作树执行，显式加载既有 `postgres.env`；`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`、`NODE_ENV=development`、`CHROME_BIN=/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`）：

```sh
./compose/gradlew -p compose \
  -I /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/quality.init.gradle \
  -I /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/stable-file-input-self-contained.init.gradle \
  :business-domain:jvmTest :business-domain:jsBrowserTest :business-domain:wasmJsBrowserTest \
  :business-domain:iosSimulatorArm64Test :business-domain:compileAndroidMain \
  :business-domain:compileKotlinIosArm64 :business-domain:compileTestKotlinIosSimulatorArm64 \
  :business-domain:ktlintCheck :business-domain:detektMetadataCommonMain \
  :business-domain:detektJvmMain :business-domain:detektJvmTest :business-domain:detektAndroidHostTest \
  :shared:testAndroidHostTest :shared:jsBrowserTest :shared:wasmJsBrowserTest \
  :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosSimulatorArm64 \
  :backend:test :backend:ktlintCheck :backend:detektMain :backend:detektTest \
  --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/funding-suggestions/verify.py
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/funding-suggestions/check-links.py
openspec validate --all --strict
openspec doctor --json
git diff --check
```

### 成功解析批次的原会话捕获与保存（6.4）

比较基线 `597c0473`。沿既定成功文件编码、同范围缓存与批次原子性，组合原字节读取、格式解析和保存，不改变逐文件失败处理或用户确认规则。

1. 思考：单文件读取及 owner 保存已分别完成，调用方仍须自行固定原会话、按序捕获并避免保存半批结果；PDF／旧 XLS 请求需显式消费原凭据，不能在有界读取等待后取新登录。
2. 计划：owner 固定输入列表，在现有数量／引用门及缓存身份预检后，逐份捕获并在全部成功后原子保存；读文件和远程解析期间释放资源锁，保存前重核范围资格。成功结果继续供既有完整本地预览使用，无新增持久格式或协议。
3. 任务拆分：先写真实 CSV 字节到保存和完整预览、首挂起前列表冻结、失败／取消无部分保存、身份／关闭围栏、数量／重复引用门与固定远程凭据回归；实现后独立 5a／5b，再运行共享完整跨端矩阵及原生编译和文档检查。
4. 构建：先以 owner 捕获入口缺失和固定凭据参数缺失的编译失败复现，再组合原列表／数量门、缓存资格、锁外有界读取及全成功后的原子保存；固定凭据贯穿 PDF／旧 XLS 远程解析。聚焦真实字节到完整预览、失败不替换、首挂起列表冻结和身份／关闭门通过；补真实 SQLite 的保存、重开、同值重试及异值不可替换回归通过。
5. 审查：独立 5a 产品／范围、工程（产品编码规范 §7）、原身份／异常安全与最终 diff，以及实际独立子 5b 契约复核均无 finding。核凭据传递、锁外读取／HTTP、真实原子保存及无半批发布；没有新协议或持久状态，无 UI 改动，UI 复用与 Hallmark 不适用。
6. 测试与 QA：2026-11-15（UTC），比较基线／验证 HEAD `597c0473` 加当前工作树。最终聚焦 owner 28、读取器 11、真实 SQLite 恢复 26 方法共 65 方法通过；完整 shared Host 99 类／877 方法、JS 99 类／851 方法、Wasm 101 类／857 方法，共 2585 方法，0 失败／错误／跳过，三个任务均实际执行。完整矩阵成功 2m，187 任务：30 执行／157 up-to-date；Android 主代码随 Host 回归编译，iOS arm64 生产及 Simulator arm64 测试编译通过。七份 Kotlin SHA、299 份固定新鲜非空 XML、日志成功终态与前片测试数增量已核对。真实 CSV 字节经 owner 保存后进入完整生产计划；Host 实际 SQLite 的重开、同值重试与异值拒绝通过，固定凭据的 PDF／旧 XLS 使用合成 MockEngine，不冒称部署服务端或原文件真实性验证。浏览器实际执行相同 owner／读取器回归及既有 Web 加密存储测试。shared 无 lint／detekt 任务，沿相称编译、diff 及独立工程复核；领域与 backend 生产未改，不重复前片矩阵或 PostgreSQL，不将旧双库证据算作当前新测试。历史外部 response／portfolio live 夹具类按原 init filter 排除，不计通过。
7. 发布：design、三份功能地图及 apply 指引已按实际成功批次入口更新；核对 README、主规格／delta、词表、参考资料、接口／命令示例、配置及迁移，无新 wire、领域概念、依赖或存储格式，其余无需修改。沿用户撤回额外账户映射／来源证明协议的既定决定，无新产品取舍，无需新增通用规范。Cross-platform Impact Check：共享读取和 owner 影响 Web／Android／iOS，由以上浏览器、Host 回归及原生编译覆盖；没有页面、路由、交互或用户文案变化，产品页面冒烟、Hallmark、UI 复用和设备交互 QA 不适用。没有增加候选搜索或数据复制层，完整文件导入长任务响应与规模性能仍须应用接线验收，Default 不构成浏览器 Worker 隔离。无迁移，可随代码独立回退，保留既有批次恢复材料。逐文件状态、追加／替换／移除、自动调度及页面仍未完成，6.4 保持未勾选，不归档或创建 PR。strict 27/27、doctor healthy、六份文档 175 个本地目标／34 个锚点及 diff 检查通过；最终独立证据门通过，无遗留 finding，按既有授权提交推送同分支。

证据根目录 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/batch-capture`：`red.log` 保留缺失入口／凭据参数的先行失败；`focused2.log` 为最终 65 方法成功，`matrix.log` 为完整矩阵成功。`tested-code.json`、`final-results.json`、固定 `final-shared-*-xml` 及 `verify.py` 核当前代码、测试完整性／新鲜度与前片增量，不随重跑覆盖固定 XML。`commands.json` 记录命令与环境，`validate.log`／`doctor.json`、`links.json` 记录 OpenSpec 和六份文档检查。浏览器为 ChromeHeadless 157、Karma `http://localhost:9876/context.html`，覆盖真实 CSV 到完整预览、身份与取消围栏和固定远程请求凭据；不是产品页面验收。

实际命令（在工作树执行，`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`、`NODE_ENV=development`、`CHROME_BIN=/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`）：

```sh
./compose/gradlew -p compose \
  -I /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/stable-file-input-self-contained.init.gradle \
  :shared:testAndroidHostTest :shared:jsBrowserTest :shared:wasmJsBrowserTest \
  :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosSimulatorArm64 \
  --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/batch-capture/verify.py
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/batch-capture/check-links.py
openspec validate --all --strict
openspec doctor --json
git diff --check
```

### 缓存 owner 的生产导入计算与重派接线（6.4／6.6）

比较基线 `45eb405c`。沿原冻结文件、固定快照及原键优先恢复合同，将真实完整计算用于内部预览和明确的生产重派入口，不新增协议或保存完整计划。

1. 思考：原 owner 已保存文件、选择及原提交，重派仍要求调用方注入计算；初始预览缺少从同一 owner 取得文件和快照的生产入口。现有完整文件计算与写集应使用同一来源准备结果，避免重复运行来源解析。
2. 计划：完整业务计划内部抽出一次计算的收尾回调，新增计划与完整写集的同次计算入口。owner 从同范围批次及完整缓存读取，输入在首次挂起前冻结；计算在 Default 执行且检查取消／关闭，返回前核原凭据和目录代次。预览只返回冻结展示、候选确认数据和请求，不持久化或提交；用户确认后才沿已有存储保存。显式生产恢复入口复用原收据优先、有界重派与新尝试原子保存；底层只恢复原请求入口保留。
3. 任务拆分：先写 owner 真实退款预览与生产重派的失败回归；再实现同次计划／写集组合及 owner 入口，覆盖不联网、恢复编号、输入隔离、过期范围、真实关系、重开与结论变化；独立复核后运行受影响领域／shared 多端矩阵及相称编译、质量和文档检查。
4. 构建：缺失领域同次计算及 owner 入口的先行失败后实现；同一次来源准备返回完整计划与写集，owner 冻结四类原选择并读取同范围文件／快照，计算不保存或发 HTTP。显式生产恢复入口绑定同一真实计算；原底层默认仅恢复原请求。原重派夹具也已换成完整生产计算，新增真实退款、重开后冲突重派及新键先存后发回归；补输入首挂起前冻结、凭据变化／身份消失、缺缓存和关闭围栏。
5. 审查：独立 5a 产品／范围、工程（产品编码规范 §7）、会话／恢复安全及最终 diff，与实际独立子 5b 契约复核均无 finding。核同次准备、真实快照、输入隔离、资源互斥及返回围栏，保留原键收据优先和未知结果恢复；无 UI 变化，UI 复用与 Hallmark 不适用。
6. 测试与 QA：2026-11-15（UTC），比较基线／验证 HEAD `45eb405c` 加当前工作树。聚焦领域七方法、owner 23 方法及真实 SQLite 恢复 25 方法共 55 方法通过；完整矩阵 domain JVM 196 类／868 方法、JS／Wasm／iOS Simulator 各 166 类／820 方法，shared Host 99 类／870 方法、JS 99 类／845 方法、Wasm 101 类／851 方法，共 5894 方法，0 失败／错误／跳过，七个任务均实际执行。矩阵成功 5m55s，229 任务：53 执行／176 up-to-date。领域 Android 与 iOS arm64、两模块 Simulator 测试编译及共享 iOS arm64 编译通过，domain ktlint 和适用 common／JVM test detekt 通过；JVM production／AndroidHost 专属 detekt 为 NO-SOURCE。共享模块无 lint／detekt 任务，沿相称编译、diff 与独立工程审查覆盖。四份 common owner 新回归在 Host／Chrome JS／Wasm 实跑，领域新增同次计划／写集与时钟变化重派回归在四运行时实跑；真实退款的生产恢复用真实本地 SQLite 与合成 HTTP MockEngine，不冒称已部署服务端或双库提交验证。既有 shared 外部 response／portfolio live 夹具类按相同 init filter 排除，不计通过。服务端 DML／schema 与 Application Service 未改，本片不重跑 PostgreSQL；前一完整业务计划片的双库证据不冒充当前新验证。七份 Kotlin SHA、993 份新鲜非空 XML、日志成功终态及现有恢复基线测试数量增量均已核对。
7. 发布：D2／D3／D5、三份功能地图及 apply 指引已按实际 owner 组合更新；核对 README、词表、主规格／delta、参考资料、接口／命令示例、配置与迁移，没有新 wire、领域概念、依赖或存储格式，其余无需修改。独立文档复核的三处 P3 过时 owner／输入保存状态已原位收窄到尚未接入的文件解析批次调度、页面及结果应用。沿用户撤回额外账户映射／来源证明合同的决定，没有新产品取舍，无需新增通用规范。Cross-platform Impact Check：共享计算及 owner 影响 Web／Android／iOS，以上真实 ChromeHeadless 157 浏览器测试、Native 执行和相称编译覆盖；没有页面、路由、交互或用户文案变化，产品页面冒烟、Hallmark、UI 复用及设备交互 QA 不适用。Default 在浏览器不构成 Worker 隔离，完整导入长任务响应与性能仍须产品接线时验证，不以原完整计划探针替代。可随代码独立回退，无迁移；原批次及已准备请求保持既有恢复合同。内部入口不代替离线写入限制、完整文件解析调度或页面接线，6.4／6.6 仍未勾选，不归档或创建 PR。strict 27/27、doctor healthy、六份文档 173 个本地目标／34 个锚点及 diff 检查通过；最终独立证据门已通过，无遗留 finding，按既有授权提交推送同分支。

证据根目录 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/owner-calculation`：`red.log`／`domain-red.log` 保留缺失入口失败，`focused3.log` 为最终聚焦成功；`matrix.log`、`tested-code.json`、`final-results.json`、固定 `final-*-xml` 及 `verify.py` 核对实际源码、完整新鲜测试及终态，不随重跑覆盖固定 XML。`commands.json` 记录原命令及环境，`validate.log`／`doctor.json` 与 `links.json` 记录文档和 OpenSpec 门。浏览器为 Karma `http://localhost:9876/context.html` 的真实 ChromeHeadless 157，内部 owner 测试覆盖离线计算、首挂起冻结及身份围栏，已有 Web 加密存储测试继续执行；不等同产品页面验收。

实际命令（在工作树执行，`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`、`NODE_ENV=development`、`CHROME_BIN=/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`）：

```sh
./compose/gradlew -p compose \
  -I /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/quality.init.gradle \
  -I /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/stable-file-input-self-contained.init.gradle \
  :business-domain:jvmTest :business-domain:jsBrowserTest :business-domain:wasmJsBrowserTest \
  :business-domain:iosSimulatorArm64Test :business-domain:compileAndroidMain \
  :business-domain:compileKotlinIosArm64 :business-domain:compileTestKotlinIosSimulatorArm64 \
  :business-domain:ktlintCheck :business-domain:detektMetadataCommonMain \
  :business-domain:detektJvmMain :business-domain:detektJvmTest :business-domain:detektAndroidHostTest \
  :shared:testAndroidHostTest :shared:jsBrowserTest :shared:wasmJsBrowserTest \
  :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosSimulatorArm64 \
  --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/owner-calculation/verify.py
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/owner-calculation/check-links.py
openspec validate --all --strict
openspec doctor --json
git diff --check
```

### 本地完整文件业务计划接线（1.3／6.4）

比较基线 `9c1ebece`。沿既定规则组合原文件操作准备、预览、真实关系发现、选择应用和完整呈现，不引入额外来源／账户映射证明。

1. 思考：完整 Phase A→D 已有生产配置，文件业务入口仍未调用它。原发现器对预计现金部分取来源备注、原始消费角色和存量确认边，先过滤来源及种子，再惰性读取别名；这些共同决定必须单一维护。
2. 计划：共享原预览发现编排，backend 保留活动守卫、SQL 和数值桥；本地读取同一快照别名并调用真实完整 planner。新增完整文件业务计划入口，保留计算类型、上次 occurrence 编号及显式恢复编号，最终冻结继续既有入口。
3. 任务拆分：先写完整文件入口失败测试，覆盖实际退款发现与选择、entry 编辑、草稿、编号恢复、活动守卫和只读输入；再实现并比较当前 schema 下原生完整计划，运行受影响多端、编译、质量与独立审查。
4. 构建：缺完整文件入口先红，再共享原发现器字段映射与编排，backend 保留 SQL／活动守卫／宿主标量桥，本地使用完整生产 planner。新文件真实退款自动发现、选择、entry 编辑、草稿门、无 provider 编号恢复、输入隔离及实际文件→完整写集→确认恢复→三次重算已回归。显式活动守卫先于输入读取，程序错误传播。当前 schema 双库测试准备实际存量流水、来源绑定、旧条目／成员和别名，完整对照原生结果，不改旧黄金。
5. 审查：独立 `refresh_review` 5a 及实际子 `source_contract` 5b 契约复核无生产 finding；已核原字段 presence、来源门、种子、有效确认边、别名惰性读取／原顺序、定向端点 first-wins、typed 金额／ordinal、来源备注、occurrence 编号恢复与只读输入。P3 覆盖建议已补成旧流水端点、候选 ID 和实际旧 entry／修订精确断言；嵌套 metadata 建议因既有合法域只容许标量而撤回。文档 P3 旧状态残句已原位改成内部解析后计算已提供、owner／重派调用和页面仍待。无 UI，UI 专项不适用；最终独立固定源码／XML／文档门通过，无遗留 finding。
6. 测试与 QA：2026-11-15（UTC），比较基线／验证 HEAD `9c1ebece` 加当前工作树。精确命令、环境、7 项源码 SHA、日志和固定新鲜非空 XML 位于仓库外 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/business-plan`；`verify.py` 已核源码不变、原领域／backend 资源黄金未改、五个测试任务均实际执行。完整 domain JVM 196 类／867 方法，JS／Wasm／iOS Simulator arm64 各 166 类／819 方法，共 3,324；backend 默认 197 类／1,328 方法，合计 4,652，全部零失败／错误／跳过。新增 common 4＋6 方法四目标存在；JS／Wasm 实际 ChromeHeadless 157，不以编译代替运行。新当前 schema 完整计划对照为 SQLite／真实 PostgreSQL 各两方法，包含新文件、关系／entry 选择、草稿、真实存量候选及旧条目依赖，计算前后全库不变；显式使用既有 `postgres.env` 的 `_test` 库，测试不启动或探测数据库。此前聚焦 backend 原序列化／规划各一及双库四方法亦全绿，不重复计入总数。

   仓库根 `./compose/gradlew -p compose` 的完整矩阵成功 8m54s，127 任务：38 执行／89 up-to-date。Android 主代码、iOS arm64 生产与 Simulator 测试编译，domain／backend ktlint 及适用 detekt 全绿；domain JVM main／Android host detekt 为 NO-SOURCE，未计执行。单 worker、禁增量及 build／configuration cache、in-process 编译、Gradle JVM 3 GiB、ktlint worker 1 GiB；实际参数见 commands.json。backend 默认排除历史迁移分类，外部历史环境未补跑，不计通过。

   Gradle 启动前独立运行真实完整文件计划及最终冻结 JVM 探针：100／250／500／1,000／2,000／4,000 行每组各三次，核每个组的退款类型、accept、金额、操作／建议数量及整份结果确定性；中位约 91／136／246／534／1,436／4,063 ms，没有截断或新增上限。结果包含来源／操作准备、预览、实际发现、组选择、完整呈现与编码，不含文件解码或服务端提交；整条导入产品性能仍须后续验收，增长快于线性的风险保留。无页面变化，产品浏览器流程冒烟与设备交互 QA 不适用。
7. 发布：design、tasks、三份功能地图与 apply 指引按实际内部完整文件计划更新，并清理旧的生产发现未完成表述；核对 README、词表、主规格／delta、接口／命令示例、配置及迁移，无新用户概念、wire、依赖、schema 或操作，其余无需修改。沿精确兼容及撤回额外来源／映射证明的既定决定，无新用户取舍，无需新增通用规则。Cross-platform Impact Check：共享计算影响 Web／Android／iOS，上述四目标实际测试及原生编译覆盖；无页面、路由、文案或交互变化。strict 27／27、doctor healthy、六份文档 172 个本地目标／33 个锚点及 diff 检查通过；最终独立证据门通过，无遗留 finding。按已有授权提交推送同分支，可随代码独立回退，无迁移。owner／重派生产调用、完整文件解析调度、队列及页面仍待接通，1.3／6.4 保持未勾选，不归档、不创建 PR。

### 本地完整关系规划配置（1.3／6.3）

比较基线 `cfc21505`。沿既定 Phase A→D、完整图校验和摘要合同组合生产配置；不改变来源证明、账户默认或提交行为。

1. 思考：B–D 已接真实配置，完整规划仍用有界测试适配。生产入口需同一宽累计值组合平台硬键、初始余量、来源行读取、图校验和摘要；现有通用行读取属于纯字段解释，应让 backend 与本地复用。
2. 计划：共享原 `factViewFromRow` 字段映射，金额、真假及 ordinal 保留宿主适配；新增内部 `LocalCashRelationPlanning`，复用完整共享 planner／validation／fingerprints。图验证只使用确定性正 Long 快照编号，原虚拟父／组成项引用保持字符串，不窄化；JSON 明确保留计算金额、Instant、原整数精度及整数／浮点类型，非整数来源值仍沿旧 binary64 摘要口径。
3. 任务拆分：先将原完整规划三方法切换真实配置并实际红，再组合；补 Phase A 剩余额度、图拒绝、行解析及跨端表示回归，运行完整领域矩阵、相称编译、受影响后端纯规则与独立审查。预览发现读取仍按原调用链继续组合，不以配置存在宣称产品接通。
4. 构建：缺入口红已实际复现，原完整规划三方法及新增字段／Phase A／图门用例初轮通过；backend 原序列化及完整规划两方法通过，黄金未改。两个独立 finding 的回归实际失败后修复，聚焦 13 方法转绿。代理字符回归改由运行时字符数组构造并先核码值，完整跨端验证另记下方。
5. 审查：独立 5a 与实际子 5b 契约审查确认并关闭两项 P2：共享规划内置真假门不识别本地 JSON 零／false，可能阻止原 record_id 回退并漏 Phase A；局部摘要兜底严格 UTF-8 拒绝孤立代理字符串，偏离原 legacyReferenceUtf8。失败已复现，按原边界注入本地真假门及保留局部旧字符串编码，两级静态重审关闭。原小数摘要沿 binary64，过宽“原数词保真”声明按既有 native 合同收敛，并补实际本地编码与原生 Double 差分，不改为另一份完整核对结论合同。字段迁移、默认值、ordinal 与 Long 快照图边界无其他 finding；无 UI，UI 专项不适用，最终固定源码／日志／XML 证据门通过，无遗留 finding。
6. 测试与 QA：2026-11-15（UTC），比较基线／验证 HEAD `cfc21505` 加当前工作树，精确参数、7 个源码 SHA、固定 XML、日志及探针位于仓库外 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/full-planning`。仓库根 `./compose/gradlew -p compose` 实跑 JVM 194 类／857 方法、真实 ChromeHeadless 157 的 JS／Wasm 及 iOS Simulator arm64 各 164 类／809 方法，共 3,284 方法，零失败／错误／跳过；原完整规划三方法及新增共同七方法四端均存在，JVM 三个新差分覆盖完整 BMP ordinal／边界、BigDecimal 文本及原生 Double。原完整规划夹具及后端两份原黄金未修改。

   完整命令终态成功 4m35s，Android 主代码、iOS arm64 生产及 Simulator 测试编译、domain ktlint、commonMain／JVM test detekt 均通过；JVM main／Android host detekt 为 NO-SOURCE，未计执行。单 worker、禁增量及 build／configuration cache、in-process 编译、JVM 3 GiB、ktlint worker 1 GiB，实际目标及环境记录在 commands.json。其后相同代码的 backend 原序列化与完整规划两方法成功 49s，XML 新鲜非空且零失败／错误／跳过；不把纯规则测试当数据库矩阵。

   Gradle 终态后独立运行真实完整 Phase A→D、图校验及摘要 JVM 探针，500／1,000／2,000／4,000 条事实每组各三次，完整核预期端点、唯一性、accepted、Phase A 及退款金额，对应 250／500／1,000／2,000 个提案，中位约 116／192／568／2,105 ms；没有截断。结果显示规模增长快于线性，完整导入性能仍须在生产发现器／文件管线验收，不把本机规划结果宣称为产品性能。没有新增搜索或持久化行为，双后端矩阵不适用；无 UI，产品浏览器流程冒烟不适用。
7. 发布：design、tasks、三份功能地图与 apply 指引按内部完整规划范围更新；核对 README、词表、主规格／delta、协议示例及配置／迁移，没有新增概念、wire、依赖或 schema，其余无需改。沿精确兼容及撤回额外来源／映射证明的边界，无新用户业务取舍，无需新增通用规范。Cross-platform Impact Check：共享规则影响 Web／Android／iOS，以上四运行时及原生编译覆盖；没有页面、路由、交互或用户文案变化，产品冒烟、Hallmark、UI 复用及设备交互 QA 不适用。strict 27／27、doctor healthy、六份文档 168 个本地目标／32 个锚点及 diff 检查通过；独立最终证据门已通过，无遗留 finding，按授权提交推送同分支，可随代码独立回退，无迁移。1.3／6.3 保持未勾选，预览发现读取、完整文件计划、队列与页面仍待组合，不归档、不创建 PR。

### 本地生产关系管线组合（1.3／6.3）

比较基线 `bfb9fed5`。本步沿原规则组合内部精确累计、时间、商户文本、候选索引、镜像、转账、菱形退款与商户退款，不新增公开合同、来源证明或保存规则。

1. 思考：原 backend 以 BigDecimal／Duration 和 Java 商户文本注入共享规则；现有共同测试的有界适配不能作为生产配置。金额索引必须按数值归一，而证据保留原 scale；镜像事件累计不能回写单笔金额类型。
2. 计划：新增内部 `LocalCashRelationPipeline`，使用 `LocalCashRelationArithmetic` 及精确商户入口，复用共享算法。按原 ChronoUnit 秒差、Duration 纳秒溢出、候选 Double 秒和日界语义提供时间适配；保留原菱形退款 0.0001 容差。完整 Phase A 计划及预览发现读取另须组合，不以本步代表页面可用。
3. 任务拆分：先将原管线、商户退款与完整镜像回放测试切换到生产配置并实际红，再实现；新增宽累计、Unicode 商户、状态及原生适配差分，运行四目标、相称编译和质量检查，独立审查后收尾。
4. 构建：真实缺入口 red 已复现，生产配置与原管线三方法、商户退款两方法、43 次原镜像回放均转绿。新增六个共同方法覆盖 Unicode 完整退款证据、两笔退款消耗余量、时刻对象与字符串精度、数值索引、宽累计和菱形容差；JVM 原生差分另比完整输出及上下文。原参考适配遗漏字符串微秒截断的测试缺陷已按未变 backend 修正，未修改生产时间语义。
5. 审查：独立只读 5a 已核产品／范围及产品编码规范 §7 工程、异常与精度、最终 diff，无阻断。金额键、scale、分组默认、时刻计算、菱形容差及 fact.toString 的原交叉核对边界一致；三个原测试黄金与 43 次调用断言保留。5b 独立判断不触发：内部宿主配置，无事实模型、DTO、DB、API 或 UI 改动。预置占用／余量建议已补入并重审关闭：链式 case 对 strict true／false 与空／预置上下文四种组合逐一核全提案及状态。未新增 aliases／blocked 组合差分声明；最终固定源码／日志／XML 证据门通过，无遗留 finding。
6. 测试与 QA：2026-11-15（UTC），比较基线／验证 HEAD `bfb9fed5` 加当前工作树；仓库根实际执行 `./compose/gradlew -p compose`，完整参数、6 个源码 SHA、固定 XML、日志及规模探针在仓库外 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/production-pipeline`。JVM 192 类／847 方法、JS／Wasm／iOS Simulator arm64 各 163 类／802 方法，共 3,253 方法，零失败／错误／跳过，浏览器为真实 ChromeHeadless 157.0.0.0。新增共同六方法、原管线三／退款二／镜像一方法在四目标实跑，JVM 新两方法含 240 组固定种子输入及四种链式上下文差分。原黄金正文、镜像及商户原夹具未改。Android 主代码、iOS arm64 生产及 Simulator 测试编译、domain ktlint、commonMain／JVM test detekt 通过；JVM main／Android host detekt 为 NO-SOURCE，未计执行。完整命令终态成功 4m25s，单 worker、禁增量及 build／configuration cache、in-process 编译、JVM 3 GiB、ktlint worker 1 GiB，环境及目标精确记录在 commands.json。

   Gradle 终态后独立运行真实生产 B–D JVM 探针，每个规模三次均核完整预期端点、唯一性、状态、金额及商户信号；500／1,000／2,000／4,000 事实对应 250／500／1,000／2,000 个合法退款提案，中位约 39.9／41.7／70.9／142.2 ms。包括索引及整条 B–D 计算，不包含 Phase A、完整计划、文件或 UI，不宣称完整导入性能。没有新增候选搜索或数据写入，双后端矩阵不适用；无 HTTP、页面或用户文案改动，不把领域浏览器单测当产品流程冒烟。
7. 发布：design、tasks、三份功能地图及 apply 指引按真实内部组合范围更新；核对 README、词表、主规格／delta、协议示例和配置／迁移，没有新领域概念、wire、依赖或 schema，其余无需修改。沿精确兼容及撤回额外来源／映射证明的既定边界，无新用户业务取舍，无需新增通用规范。Cross-platform Impact Check：共享规则影响 Web／Android／iOS，上述四运行时及原生编译已验证；未改变页面、路由、交互或用户文案，产品冒烟、Hallmark、UI 复用及设备交互 QA 不适用。strict 27／27、doctor healthy、六份文档 165 个本地目标／32 个锚点及 diff 检查通过；独立最终门已核固定源码、原夹具、未变 backend、新鲜非空 XML 及规模结果，无遗留 finding，按已有授权提交推送同分支。可随代码独立回退，无迁移。1.3／6.3 保持未勾选，完整 Phase A 计划、预览发现、导入／队列及页面仍待组合，不归档、不创建 PR。

### 本地商户文本与扫描索引（1.3／6.3）

比较基线／核对 HEAD：`e2a0fc3bb31f8fd592552f0f71edc9561220147a`。本步实现内部精确商户文本处理，不接完整发现、HTTP、存储或页面；原后端商户表达式不变。

1. 思考：按已验证的私有边界模型迁移，保持 Java Unicode word 减下划线、ROOT 单码点映射和有状态 Σ 的原合同。初版长标记已复现二次扫描：1,024／2,048 个标记的 JVM 探针约 33／129 ms；不以截断或商户长度限制规避。沿既定 A 类范围实施，不引入新业务取舍。
2. 计划：design 明确内部 `normalizeLocalCashMerchant`、每 Σ 独立游标及同文本只读扫描索引；正向残余状态依赖下一／两个 UTF-16 单元，连接符引用已完成的后续 token；反向残余状态依赖前一／两个单元。两个方向各三行状态，每位置只保存正向终点／反向起点，预处理时间／空间线性。固定属性来自公开 Character，不复制 JDK 分词源码／表。
3. 任务拆分：先缺入口失败，再核原夹具及全标量摘要、真实上下文／查询位置、完整属性范围、长串输出和跨端运行；性能先复现再改索引。原夹具完整复制，原文件 SHA-256 `4291072c105cad2f889d38dc46a17114a15d88a25918bce759cdd4756a0f1a04` 不变，JSON 内容 SHA-256 `1315eef086cab30c18af4cbffe6b62c48914525c5122bb00b0fc36a2d7ea14e3` 在 common 测试内核对，不重新采样。
4. 构建：冻结 1,427 个公开字符属性范围，属性 SHA-256 `e91f2e0bb73b3b725dda9949b4648b13ddd679a1e48398858a8facf2a818c5eb`；生成器限定 Temurin `21.0.6+7`，可 `--check` 复跑。Σ 之外只读取已有冻结单码点映射；cased 沿 Lu／Ll／Lt 与已验证额外范围，不引入不可观察 circled 常量。原偏移、半代理读法、最长前缀竞争、反向标记尾缀及缓存严格小于条件保持；索引只读，查询状态不能在 Σ 之间共享。
5. 审查：独立只读 5a 按产品编码规范 §7 核对生成器、冻结范围、原夹具保真、DP 依赖、半代理、查询隔离及输入／失败边界，无阻断。代码库防劣化范围判断：仅内部文本规则、公开属性数据及测试，无领域事实模型、DTO、数据库、API 或 UI 变化，5b 不触发；无需子专项。新增缓存等号反例已独立复核；最终固定 XML／文档证据门通过。design 将已验证内容仍列为后续门的 P3 已原位收敛，完整发现保留未完成，无遗留 finding。
6. 测试与 QA：2026-11-15（UTC），Temurin `21.0.6+7`，工作区根执行 `./compose/gradlew -p compose`；完整参数、6 个代码 SHA、固定 XML、日志及性能驱动器位于仓库外 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/merchant-kotlin`。缺入口 red 为实际编译失败；最终 JVM 839、真实 Chrome Headless `157.0.0.0` JS 796／Wasm 796、iOS Simulator arm64 单元测试 796，共 3,227 方法全部零失败／错误／跳过。6 个共同方法含原 21,309 向量与内容摘要、`0..0x10FFFF` 原摘要、legacy cased／UTF-16、查询历史与零偏移、缓存等号边界及 16,384 规模完整长串；JVM 另核全字符属性、每保留码点 8 个原上下文与查询状态、10,000 固定种子混合串。普通编译不充当这些运行结果。

   初次完整命令的目标为 `:business-domain:jvmTest :business-domain:jsBrowserTest :business-domain:wasmJsBrowserTest :business-domain:compileAndroidMain :business-domain:compileKotlinIosArm64 :business-domain:compileTestKotlinIosSimulatorArm64 :business-domain:iosSimulatorArm64Test :business-domain:ktlintCheck :business-domain:detektMetadataCommonMain :business-domain:detektJvmMain :business-domain:detektJvmTest :business-domain:detektAndroidHostTest`，终态成功 4m43s。最后新增共同缓存反例后，四个实际测试目标及 `ktlintCheck`／`detektJvmTest` 再执行，终态成功 3m29s，上述 3,227 来自这次新 XML；未改生产源码，初次 Android／iOS 编译及 commonMain 质量证据继续适用。两次均用外部 `quality.init.gradle` 将 ktlint worker 设为 1 GiB，以及 `--continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process` 和 Gradle JVM `-Xmx3072M -Dfile.encoding=UTF-8`；Node 为 development，浏览器使用系统 Chrome。detekt JVM main／Android host 为 NO-SOURCE，不冒称执行了对应检查。

   `verify-final.py` 已核 6 个 SHA、原夹具、未修改的后端表达式、冻结属性 `--check`、新鲜且非空的四套 XML 与全部方法计数。生成器 `py_compile` 和复跑一致。Gradle 结束后独立运行长串探针，6 类 × 4 种规模 × 每组 5 次均核完整输出；131,072 标记约 12.5 ms，655,360 UTF-16 单元的补充平面组合中位约 52.6 ms，未截断，详见 `long-performance.log`。这些是本机 JVM 文本处理结果，不代表完整导入性能。内部纯计算没有存储行为变化，双库不适用。
7. 发布：design、tasks、apply 指引及导入／共享／总览功能地图按内部实现范围核对，README、词表、主规格／delta、协议示例和配置／迁移没有新增合同，其余无需改动。没有新的用户业务选择，沿精确兼容与撤回额外证明协议的既定边界，无需新增通用规范。Cross-platform Impact Check：实现位于共享 domain，影响 Web／Android／iOS 后续本地发现；后端仍走原适配，未改任何页面、路由、交互或用户文案，产品页面冒烟、Hallmark、UI 复用及设备交互 QA 不适用。strict 27／27、doctor healthy、6 份文档的 161 个本地目标／31 个锚点与 diff 检查通过，独立最终门已核源码／日志／XML SHA 及四运行时方法，无阻断，按授权提交推送同分支。本片可随代码独立回退，无 schema／迁移。不勾 1.3／6.3；完整关系发现配置、完整提案／计划与产品接线仍待实施，不归档、不创建 PR。

### 本地平台退款日期与配置（1.3／6.3）

比较基线 `0a545476`。按原平台退款公共 API 行为迁移日期适配，并把宽累计／精确时间组合用于本地 Phase A 配置；不修改现金时间范围或商户规则。

1. 思考：测试适配的 Python ISO 解析不能保持 JVM ISO／SMART、前 19 单元及偏移处理；原生解释和已经共享的匹配规则需要分开证明。
2. 计划：保留原三段解析次序和 UTF-16 前缀，独立公历计算支持原年份范围；冻结 JVM 空白边界。内部生产配置复用精确累计值和纳秒异常，平台硬键同一配置组装，完整发现仍等商户精确适配。
3. 任务拆分：先失败日期／纳秒／SMART 边界和 JVM 原函数独立差分；随后实现，原平台退款／硬键黄金保持不变并接新配置验证；独立工程复核后跑 domain 跨端回归、原生编译与质量检查。
4. 构建：缺失解析入口先红；纯规则实现原 ISO／SMART 次序、原字符 trim、UTF-16 前缀、日期夹紧／午夜推进、偏移秒及纳秒，并复用公历原语，仅将其 private 改 internal。平台退款和硬键生产内部配置已用同一宽累计及精确纳秒原语组装；原平台／硬键黄金不改，测试改用该配置及明确计算类型。手写中间 Z 用例的预期被 JVM 原表达式证实不准，已只修该断言并追加所有位置的插入变体；组合测试中一处旧 CashAmount 显示适配漏改，已修测试类型，不放宽生产。宽余量及最细合法退款实跑覆盖该配置。
5. 审查：独立 5a 范围／工程复核通过，无 finding；核对解析分支顺序、SMART／严格边界、JVM 原函数独立性、黄金保真、金额及纳秒、输入失败与程序错误、生产 Phase A 组合和 helper 可见性范围。5b 按技能判断仅内部解析与数值配置，未改领域模型、存储、DTO、对外接口或 UI，不触发子专项。自检 P2 已先红定位：金额适配捕获 InvalidCashValue 会把域外数字／对象的内部类型错误静默转无金额。最小修复只接受 null 和已识别金额文本失败为无金额，未知类型原异常传播；新增真实生产 rules.toDecimal 回归覆盖已知类型及该边界，真实规则回归先红再绿，独立增量重审已关闭该 P2，无遗留 finding。精确商户与完整发现验收不降低。
6. 测试与 QA：2026-11-15（UTC），比较基线／验证 HEAD 为 `0a545476` 加当前工作树。完整 domain JVM 188 类／830 方法、JS 161 类／790 方法、Wasm 161 类／790 方法，共 2410 方法，0 失败／错误／跳过，三任务均实际执行；新日期 common 6 与 JVM 2、生产平台退款 3 及硬键 4 均在最终矩阵中通过。JVM 原函数对照 10445 项日期输入，包括 ISO／SMART、偏移、纳秒、扩展年份、原 trim 与逐位置插入变体。两份原平台／硬键黄金正文及原 backend 函数逐字未改；公历 helper 只有可见性变化。Android 主代码、iOS arm64 生产及 Simulator arm64 测试源码编译通过；ktlint 和 detekt commonMain／JVM test 通过，detekt JVM main／Android host 为 NO-SOURCE 不虚报执行。

证据位于仓库外 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`：`platform-date-red.log` 为缺入口首红，`platform-date-first-xml` 保留手写 Z 预期失败及原 oracle 两方法成功；`platform-date-production-focused.log` 为测试旧类型适配漏改的编译失败，不算新测试结果。`platform-date-final-focused.log` 的 14 项定向成功后，自检 P2 的 `platform-scalar-red.log`／`platform-scalar-green.log` 及固定 XML 记录原生产金额适配实际返回 null 的先红与窄修后的三项成功。原 `platform-date-matrix.log`／58405 的 2407 项仅为改前证据；修后最终 `platform-date-final-matrix.log`／session `96189` exit 0、BUILD SUCCESSFUL 2m 59s、99 任务（26 执行／73 up-to-date）。`verify-platform-date.py` 核七个 Kotlin SHA、原函数／黄金及公历范围、完整新鲜非空 XML、无跳过、ChromeHeadless 157 和编译／质量任务；固定 XML 为 `platform-date-final-business-domain-*-xml`，结果／代码摘要为 `platform-date-final-results.json`／`platform-date-tested-code.json`。

实际命令：Temurin 21 JAVA_HOME、NODE_ENV=development 及 Chrome 下，在原 worktree 执行 `./compose/gradlew -p compose -I <缓存>/quality.init.gradle :business-domain:jvmTest :business-domain:jsBrowserTest :business-domain:wasmJsBrowserTest :business-domain:compileAndroidMain :business-domain:compileKotlinIosArm64 :business-domain:compileTestKotlinIosSimulatorArm64 :business-domain:ktlintCheck :business-domain:detektMetadataCommonMain :business-domain:detektJvmMain :business-domain:detektJvmTest :business-domain:detektAndroidHostTest --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'`；外部 init 沿既有配置将 ktlint worker 设为 1024m，不修改检查规则或源码集。

7. 发布：design、tasks、三份功能地图及 apply 指引已核对本地平台退款／硬键配置的实际边界；README、参考资料、主规格／delta、词表、接口／命令示例、依赖、配置与迁移无新公开行为或数据形状，其余无需修改。沿用户保留原来源／映射保存、撤回额外证明合同的决定，不新增通用规范。Cross-platform Impact Check：共享解析与匹配配置影响 Web／Android／iOS，以上浏览器单测与原生编译覆盖；无页面／文案／交互／路由变化，UI 复用、Hallmark、页面冒烟及设备 QA 不适用，不把浏览器单测称为产品导入页面通过。没有存储或服务端 Application Service 改动，双库矩阵不适用；可随代码独立回退，无数据迁移。OpenSpec strict 27/27、doctor 正常，六文档 152 个本地目标／25 个锚点和 diff 检查通过。最终独立证据门已核七份 SHA、原函数／黄金、公历范围、2410 项固定 XML、最终日志及 13 文件范围，无遗留 finding；按既有授权仅提交推送本步 13 文件至同分支并排除 `.commandcode/`。精确商户和完整生产发现／页面接线仍未完成，1.3／6.3 与整个 change 不标完成、不归档或创建 PR。

### 本地发现累计与纳秒值适配（1.3／6.3）

比较基线 `460d80e6`。沿既定 BigDecimal 无舍入累计和 Duration.toNanos 溢出语义，补生产内部数值配置；不改变发现、账户保存或商户规则。

1. 思考：已有 LedgerArithmeticValue 支持无存储精度限制的计算，但发现测试配置仍使用 CashAmount；退款余量可超过单字段范围。Kotlin Duration 的 inWholeNanoseconds 会饱和，不能替代原 JVM 纳秒异常合同。
2. 计划：本地累计配置只接归一金额字符串与明确类型，串联关系核心、初始余量和内部序列化，保留原 scale／尾零和无舍入加减；独立纳秒原语按分量检查 Long 边界。原始平台日期及商户精确模型继续保留门槛，不安装临时近似配置。
3. 任务拆分：先写宽累计／抵消／表示／初始余量回归和纳秒边界，JVM 独立对照 BigDecimal 及 java.time.Duration；实现后独立工程复核，执行 domain 三目标回归、质量检查和原生编译。
4. 构建：`relation-arithmetic-red.log` 先因缺少新配置／原语失败。实现内部累计配置，复用已验证任意精度原语和明确金额类型，初始退款余量与序列化证据不回转为 CashAmount；保持原 applied_amount 失败回退规则。纳秒差先归一分量再校验乘加，两侧 Long 极值可表示，紧邻溢出抛 ArithmeticException。没有改变旧 backend 或把受限测试配置晋升生产。
5. 审查：独立 5a 范围／工程复核通过，无 finding。覆盖财务无舍入、原表示、已识别输入错误与程序错误、原余量规则、纳秒正负边界、独立 JVM 对照及配置必要性；复核确认不是多余封装。5b 按代码库防劣化技能判断，仅内部数值配置，未改领域模型／存储／DTO／接口或 UI，不触发子专项。完整生产发现验收及原商户兼容门槛保持。
6. 测试与 QA：2026-11-15（UTC），比较基线／验证 HEAD 为 `460d80e6` 加当前工作树。定向 common 6／JVM 独立对照 2 方法通过；完整 domain JVM 186 类／820 方法、JS 160 类／782 方法、Wasm 160 类／782 方法，共 2384 方法，0 失败／错误／跳过，三任务均实际执行。JVM 806 对金额输入对照独立 BigDecimal 的原表示、加减及数值顺序；3006 对时刻对照 java.time.Duration 的纳秒结果或实际 ArithmeticException。Android 主代码、iOS arm64 生产及 Simulator arm64 测试源码编译通过；detekt commonMain／JVM test 通过，JVM main／Android host 无专属源码为 NO-SOURCE，不虚报执行。

证据位于仓库外 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`：`relation-arithmetic-red.log` 为缺失配置的先红，`relation-arithmetic-focused.log` 为 8 项定向通过。`relation-arithmetic-matrix.log` 的 session `37462` 整体 exit 1／2m 55s，唯一失败为 commonTest ktlint worker 的 Java heap space；全部测试、原生编译与适用 detekt 已完成。仅以既有外部 `quality.init.gradle` 将 ktlint worker 配为 1024m 后补跑 `:business-domain:ktlintCheck`，`relation-arithmetic-quality.log`／session `88220` exit 0、BUILD SUCCESSFUL 11s；源码、规则及样本不变，不能称原矩阵整体成功。`verify-relation-arithmetic.py` 同时核对该唯一环境失败与补跑成功、三文件 SHA、原黄金未改、非空新鲜 XML、ChromeHeadless 157 及各任务。固定 XML 为 `relation-arithmetic-final-business-domain-*-xml`，结果与摘要为 `relation-arithmetic-final-results.json`／`relation-arithmetic-tested-code.json`。

实际命令：Temurin 21 JAVA_HOME、NODE_ENV=development 与 Chrome 下，执行 `./compose/gradlew -p compose :business-domain:jvmTest :business-domain:jsBrowserTest :business-domain:wasmJsBrowserTest :business-domain:compileAndroidMain :business-domain:compileKotlinIosArm64 :business-domain:compileTestKotlinIosSimulatorArm64 :business-domain:ktlintCheck :business-domain:detektMetadataCommonMain :business-domain:detektJvmMain :business-domain:detektJvmTest :business-domain:detektAndroidHostTest --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'`；补跑质量仅增加 `-I <缓存>/quality.init.gradle`、选 `:business-domain:ktlintCheck` 并使用 1536m daemon。

7. 发布：design、tasks、三份功能地图及 apply 指引已核对内部配置与未接完整发现的边界；README、参考资料、主规格／delta、词表、接口／命令示例、配置及迁移没有新公开行为、概念、依赖或数据形状，其余无需修改。沿用户保留原来源／映射行为且撤回额外证明合同的决定，不新增通用规范。Cross-platform Impact Check：纯共享计算影响 Web／Android／iOS，以上浏览器单测与原生编译覆盖；无 UI／文案／交互／路由改动，UI 复用、Hallmark、页面冒烟及设备 QA 不适用。没有存储或服务端 Application Service 改动，双库矩阵不适用；可随代码独立回退，无数据迁移。OpenSpec strict 27/27、doctor 正常，六文档 152 个本地目标／25 个锚点及 diff 检查通过。最终独立证据门已核三份 SHA、原黄金、2384 项固定 XML、组合验证日志及九文件范围，无遗留 finding；按已有授权只提交推送本步九文件至同分支并排除 `.commandcode/`。平台原始日期解析、精确商户处理及完整生产发现入口仍未闭合，1.3／6.3 与整个 change 不标完成、不归档或创建 PR。

### 基线冲突重派组合（6.6）

比较基线 `9b161908`。沿 D5 完成原键核实后的新尝试持久化、完整重算与有界自动重提，保持来源／映射原规则和结果未知不换键。

1. 思考：单次拒绝不能证明旧正文永远不会提交；还缺刷新后严格落后的基线、再次原键核实、新尝试身份及原子发布。
2. 计划：只从真实／已保存 POST 拒绝进入，先刷新完整快照并核版本严格超过旧基线，再查原键；成功收据优先，只有明确缺失才重算。结论同则以单行原子序号和冻结正文准备新键，不同或预期计划输入失效则退休旧请求并保留选择供重新核对。内部异常／刷新／查询失败保留旧尝试，最多自动重派三次并退避，关闭或取消按同一新键恢复。
3. 任务拆分：先覆盖真实 SQLite 序号／请求原子替换、身份碰撞与故障保旧，完整计算和 HTTP 组合覆盖顺序、相同／变化结论、结果未知、未来基线、查询／存储／会话失败及重试上限；独立复核后执行共享跨端回归。
4. 构建：缺失退休入口先红。原批次包络增加可选非负尝试序号，旧值为零，严格拒绝损坏或溢出；同一行条件更新保存新键与冻结正文，或仅退休已证明过期的旧请求。原会话 owner 沿完整缓存及原键核实再消费三次真实计算结果；成功收据优先，结果未知不换键。最多三次重派、250／500／1000 ms 退避，超限保留最新请求，取消后按已保存键恢复。新增真实重算 fixture 使用隔离银行消费，不接近似商户／候选算法。
5. 审查：独立 5a 与实际子 5b 完成，无遗留生产 finding。工程／契约范围覆盖真实 POST 拒绝、严格过期证明与核收据顺序、合法重算结论变化和输入失效两分支、程序诊断保真、序号不碰撞／不回绕、SQL 原子失败、会话／取消围栏及重试上限。建议补齐合法但结论变化路径，已以真实账户名称变化、三次计算全部成功且无新 POST 验证。P3 现状文案已收窄为生产发现入口、离线写入限制与页面未接；无 UI 变化，UI 复用不适用。
6. 测试与 QA：2026-11-15（UTC），比较基线／验证 HEAD 为 `9b161908` 加当前工作树。35 项定向测试全部通过（恢复 owner 24、真实 SQLite 11）；完整 shared Host 99 类／865 方法、JS 99 类／841 方法、Wasm 101 类／847 方法，共 2553 方法，0 失败／错误／跳过，三测试任务均实际执行。覆盖相同／变化结论、合法计算与预期输入失效、原键／新键丢响应、严格过期、查询断连／503、GET 伪同码、程序异常、序号损坏／耗尽、SQL 故障、取消与换登录，以及三次上限和 1750 ms 总退避。Web 两目标均实际完成新尝试的加密关闭／重开及收据后完整水位清理；ChromeHeadless 157、非空测试集及 XML 新鲜度已核。Android 主代码、iOS arm64 生产及 Simulator arm64 测试编译通过，shared 未配置 ktlint／detekt，不虚报检查。外部 init 沿原规则排除历史 response integration／portfolio live 类，不计通过；本片没有改变对应接口。

证据位于仓库外 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`：`rebase-red.log` 为缺失退休入口的首红；`rebase-valid-fixture-focused.log` 为原 24 项成功；`rebase-boundaries-final.log` 为当前 35 项成功。前置边界失败保存在 `rebase-boundaries-red-xml`，仅修正 MockEngine 的测试调度及跨协程异常身份断言，不放宽生产行为。`rebase-matrix.log` 的矩阵终态为 exit 0／BUILD SUCCESSFUL 2m 7s，Gradle session `19806`；`verify-rebase.py` 核对 12 个 Kotlin 文件 SHA、未改原 entry 黄金、各完整测试 XML／Chrome 与编译任务，固定 XML 在 `rebase-final-shared-*-xml`，结果为 `rebase-final-results.json`，代码摘要为 `rebase-tested-code.json`。

实际命令：在原任务 worktree，以 Temurin 21 JAVA_HOME、NODE_ENV=development 和 Chrome 执行 `./compose/gradlew -p compose -I <缓存>/stable-file-input-self-contained.init.gradle :shared:testAndroidHostTest :shared:jsBrowserTest :shared:wasmJsBrowserTest :shared:compileAndroidMain :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosSimulatorArm64 --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'`。OpenSpec strict 27/27、doctor 正常，六份文档 151 个本地目标／25 个锚点及 diff 检查通过。

7. 发布：D5、三份功能地图及 apply 指引已按内部重派实际状态原位核对；README、参考资料、词表、主规格／delta、接口／命令示例、依赖、服务端配置及迁移无新概念或公开行为，其余无需修改。继续遵守用户撤回额外来源／账户映射证明及保留原保存规则的决定，不新增通用规范。Cross-platform Impact Check：共享恢复及加密存储影响 Web／Android／iOS，以上真实浏览器单测及原生编译覆盖；无页面、路由、交互或用户文案变化，页面冒烟、UI 复用、Hallmark 及设备 QA 不适用，浏览器存储测试不冒充页面验收。客户端缓存固定 SQLite，本片没有修改服务端 Application Service，不把客户端回归当作 PostgreSQL 矩阵。尝试计数沿原单行包络，不新增表或后台循环；兼容旧材料，回退旧解码代码必须保留新包络并恢复兼容解码后再恢复请求，不得清除待提交内容。最终独立证据门已核对 12 个 SHA、原黄金、三目标 2553 项完整 XML、编译日志及 18 文件范围，无遗留 finding；按已有授权仅提交推送本步范围至同分支，排除 `.commandcode/`。生产发现器、离线写入限制和页面仍未接通，6.6／6.7 和整个 change 均未完成，不归档或创建 PR。

### 原提交基线拒绝恢复（6.6／6.7）

比较基线 `d5c19f63`。沿 D5 保存真实单次拒绝，保持原请求及结果未知核实，不开放换键、自动重派或清理原批次。

1. 思考：原恢复会在每次 receipt_not_found 后再次提交同一已拒绝基线；普通 ApiFailure 尚未进入持久恢复材料。
2. 计划：只保存真实 POST 的 409／ledger_base_changed／not_committed 三条件拒绝，沿原行更新并核原正文身份。重开仍先按原键核实，完整收据优先，无收据返回原拒绝，不重复 POST；单次拒绝不独立授权换键。失败保旧，存储故障保留原拒绝与实际原因。
3. 任务拆分：先覆盖编码兼容／损坏和成功优先，真实 SQLite 原子更新／重开／故障，原会话 HTTP 拒绝及重开不再 POST、三条件及旧会话围栏；随后独立复核与共享跨端存储回归。
4. 构建：缺失入口先红；同一原请求增加可选内部拒绝状态，旧编码缺省 false，收据成功清除拒绝，禁止收据／拒绝并存。真实 POST 三条件后经原会话／范围围栏保存，CAS 及读回核对原键／正文，故障保旧且两类原因均保留。重开仍先查原键，无收据不再发送已拒绝正文，不刷新、换键或清理。损坏编码回归发现 serializer 接受字符串 true，已只对新字段显式要求 JSON 布尔值，保留失败证据，不改变全局 JSON；null／字符串及相斥状态均拒绝。
5. 审查：独立 5a 与实际子 5b 完成，无遗留 finding。工程及契约范围覆盖原键／正文保真、真实 POST 三条件、状态生命周期、成功优先、局部严格解码、范围／会话／取消围栏、SQL 原子失败及诊断。拒绝仅描述一次响应，不授权换键，后续仍需确认旧基线落后及原键核实。无 UI 变化，UI 复用不适用。
6. 测试与 QA：2026-11-15（UTC），比较基线／验证 HEAD 为 `d5c19f63` 加当前工作树。23 项 Host 聚焦通过（恢复 owner 11、真实 SQLite 8、codec 4）。完整 shared Host 99 类／849 方法、JS 99 类／841 方法、Wasm 101 类／847 方法，共 2537 方法，0 失败／错误／跳过，三测试任务均实际执行。十个 Kotlin 摘要与矩阵前固定值一致，原 entry 黄金未改，完整非空 XML 数量、新鲜度、ChromeHeadless 157 及无跳过另行核证。Android 主代码、iOS arm64 生产及 Simulator arm64 测试编译通过；shared 未配置 ktlint／detekt，不虚报对应检查。沿原外部 init 排除历史 response integration／portfolio live 类，不计通过，相关接口未改。
7. 发布：D5、三份功能地图及 apply 指引已原位更新；核对 README、参考资料、词表、主规格／delta、接口／命令示例、配置与迁移，无新领域概念、wire、依赖、服务端 schema 或用户操作，其余无需修改。沿用户撤回额外来源／账户映射证明及保留原规则的决定，不新增通用规范。Cross-platform Impact Check：共享恢复与本地加密存储影响 Web／Android／iOS，以上完整测试及原生编译覆盖；无页面、路由、交互或用户文案变化，产品页面冒烟、Hallmark 与设备交互 QA 不适用。客户端缓存固定 SQLite，本片未改服务端 Application Service，PostgreSQL 矩阵不适用，不把端侧测试当作双后端通过。新增状态沿原单行条件更新，不新增表、搜索或后台循环；回退到旧编码代码须保留原批次，不得当损坏输入清除。strict 27/27、doctor 正常、六份文档 150 个本地目标／25 个锚点及 diff 检查通过；最终独立证据门通过，无遗留 finding，按已有授权提交推送同分支。重派、新尝试身份、退避与上限、完整发现和页面仍未完成，不勾选 6.6／6.7，不归档或创建 PR。

证据根目录 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`：`base-rejection-red.log` 为缺失入口失败；`base-rejection-focused.log`／`base-rejection-focused-red-xml` 固定损坏布尔编码未拒绝的行为失败；`base-rejection-final-focused.log`／`base-rejection-final-focused-xml` 为 23 方法成功。矩阵 `base-rejection-matrix.log` 成功（1m56s，187 任务：30 执行／157 up-to-date）；`base-rejection-tested-code.json`、`verify-base-rejection.py`、`base-rejection-final-results.json` 及 `base-rejection-final-<module>-<task>-xml` 固定十个源码 SHA 与三套完整非空 XML，固定输出不被后续重跑静默覆盖。JS／Wasm 使用 ChromeHeadless 157、Karma `http://localhost:9876/context.html`，真实加密库保存拒绝、关闭重开、读取原请求，再保存成功收据并重开，核拒绝清除和完整版本后清理；不冒称产品页面或设备 QA。

实际命令（工作树执行，`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`、`NODE_ENV=development`、`CHROME_BIN=/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`，沿实际 Zulu 21.0.11 Gradle 工具链）：

```sh
./compose/gradlew -p compose \
  -I /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/stable-file-input-self-contained.init.gradle \
  :shared:testAndroidHostTest :shared:jsBrowserTest :shared:wasmJsBrowserTest \
  :shared:compileAndroidMain :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosSimulatorArm64 \
  --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/verify-base-rejection.py
openspec validate --all --strict
openspec doctor
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/check-base-rejection-links.py
git diff --check
```

### 重算与原确认结论组合（6.5）

比较基线 `65efa752`。沿 D5 组合既有三次完整计算、两段重绑定及完整结论核对，不增加服务端证明或自动提交许可。

1. 思考：独立重绑定与比较入口尚未串联，调用方容易遗漏先配对后条目及最终完整结论核对。
2. 计划：固定最新快照，通过显式计算入口执行无选择、配对选择、完整选择三次原计算；每次使用相应原上下文、原账户／行输入及 occurrence 编号。最终结果明确比较原确认，缺少原确认或定位失败拒绝继续，内部异常原样传播；不持久化新确认、不提交或换键。
3. 任务拆分：真实完整计算 fixture 覆盖同结论、业务变化、缺失原确认、失效定位、失败传播及原输入保真，先红后绿；独立复核后运行跨端回归。
4. 构建：入口缺失先红；新增显式同快照重算入口，沿原输入和 occurrence 编号执行三次完整计算，先恢复关系再恢复条目，最后以真实完整计划及写集比较原确认。缺少原确认先拒绝，稳定定位失败停止，三个阶段内部异常原样传播。结果只是本次临时核对数据，原确认和原请求均不修改。五项新方法复用真实完整计算 fixture；弱组行为保持，强组无候选时不传候选编号。
5. 审查：独立 5a 与实际子 5b 均已完成，无 finding。工程及契约复核覆盖阶段顺序、同快照、账户／行选择与原编号保真、完整比较、失败停止、结果生命周期与复杂度；不新增证明、冻结或授权层。返回的计算 Map 沿现有独占只读约定，后续调度须沿原提交冻结边界消费，不能把比较布尔值单独持久化为重试许可。无 UI 变化，UI 复用不适用。
6. 测试与 QA：2026-11-15（UTC），比较基线／验证 HEAD 为 `65efa752` 加当前工作树。35 项 JVM 聚焦全部通过（新组合 5、重绑定 11、确认恢复 6、完整比较 13）。完整 domain JVM 184 类／812 方法、JS／Wasm 各 159 类／776 方法，共 2364 方法，0 失败／错误／跳过；三测试任务均实际执行。三个 Kotlin 摘要与矩阵前固定值一致，原 entry 黄金未改；完整非空 XML 新鲜度、数量、ChromeHeadless 157 及无跳过另行核证。Android 主代码、iOS arm64 生产及 Simulator arm64 测试编译、domain ktlint 与适用 detekt 通过。
7. 发布：D5、三份功能地图与 apply 指引已原位更新；核对 README、参考资料、词表、主规格／delta、接口／命令示例、配置与迁移，无新领域概念、wire、依赖、schema 或用户操作，其余无需修改。沿撤回额外来源／账户映射证明及保持原规则的用户决定，无新增通用规范。Cross-platform Impact Check：domain 组合影响 Web／Android／iOS，三目标领域回归和相称原生编译覆盖；无页面、路由、交互或用户文案变化，产品页面冒烟、Hallmark 与设备交互 QA 不适用。本片不改 codec／SQL／缓存或服务端 Application Service，存储及 PostgreSQL 矩阵不适用，不重复计前片共享存储结果。三次既有计算无新增搜索算法，完整发现与导入性能仍待整管线验证；结果沿既有独占只读约定消费，不持久化新确认，可独立随代码回退。strict 27/27、doctor 正常、六份文档 150 个本地目标／25 个锚点及 diff 检查通过；完整矩阵及最终独立证据门通过，无遗留 finding，按已有授权提交推送同分支。真实完整发现器、原键核实后的自动重派和页面未接入，6.5 保持未勾选，不归档或创建 PR。

证据根目录 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`：`recalculation-red.log` 为入口缺失，`recalculation-final-focused.log`／`recalculation-final-focused-xml` 为 35 项聚焦全绿。旧条目值变化会使完整备选定位直接失效，业务差异结果回归使用原强组父项及组成项同步金额变化，保留寻址但改变完整结论，未放宽生产定位或比较规则。矩阵 `recalculation-matrix.log` 成功（3m2s，140 任务：32 执行／108 up-to-date）；`recalculation-tested-code.json`、`verify-recalculation.py`、`recalculation-final-results.json` 及 `recalculation-final-<module>-<task>-xml` 固定三个源码 SHA 与三套完整 XML，固定输出不被后续重跑静默覆盖。JS／Wasm 为 ChromeHeadless 157 的 Karma `http://localhost:9876/context.html` 真实浏览器领域测试，没有产品页面视口或设备交互，不冒称对应 QA。

实际命令（工作树执行，`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`、`NODE_ENV=development`、`CHROME_BIN=/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`，沿实际 Zulu 21.0.11 Gradle 工具链）：

```sh
./compose/gradlew -p compose \
  -I /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/quality.init.gradle \
  :business-domain:jvmTest :business-domain:jsBrowserTest :business-domain:wasmJsBrowserTest \
  :business-domain:compileAndroidMain :shared:compileKotlinIosArm64 \
  :shared:compileTestKotlinIosSimulatorArm64 :business-domain:ktlintCheck \
  :business-domain:detektMetadataCommonMain :business-domain:detektJvmTest \
  --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/verify-recalculation.py
openspec validate --all --strict
openspec doctor
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/check-recalculation-links.py
git diff --check
```

### 稳定选择重绑定（6.5）

比较基线 `8d2127a1`。沿 D5 恢复原配对和条目选择，定位摘要只作内部寻址，最终仍须原规则及完整结论核对；不新增服务端证明或提交许可。

1. 思考：行／账户选择已有稳定引用，新来源编号已有保留入口；配对 ID 包含上下文观察，entry 编辑引用还包含所选配对和当前依赖，不能直接复用旧派生 ID。
2. 计划：确认时仅为已选对象保留业务定位摘要，沿结论比较器复用身份／金额／时刻编码；配对先恢复并保留反复活代次，条目再按当前完整成员定位并交原目标规则。缺失、多义或旧格式缺少绑定时保留原恢复数据并返回 `plan_changed`。
3. 任务拆分：先以真实完整计算计划覆盖观察修订变化后的配对选择和条目编辑恢复，再覆盖备选组合、身份、失效代次、旧格式、歧义、输入隔离、原字段精度与同一快照限制；独立审查后执行跨端矩阵。
4. 构建：新增两段重绑定及确认时的最小定位摘要；原配对／完整候选及最终成员唯一匹配，重用原选择规则生成代次引用，保留原字段与失效选择。接收及返回时深复制 typed 目标；原 JSON 数词通过独立 `inputJson()` 返回，不经过通用浮点编码。缺失／多义、旧格式缺少定位摘要、跨快照／规则／确认对象及更高失效代次均拒绝。入口缺失失败回归后实现；两项复核问题均有明确红绿证据。
5. 审查：独立 5a 及实际子 5b 已完成。两项 P2 为旧历史覆盖新失效代次、typed 目标接收时未隔离外部修改；分别以新旧历史逐键取最大和接收时深复制修复，独立复核关闭，无新增 finding。工程及契约范围覆盖身份、原选择保真、失效防复活、成员定位、输入边界、复杂度与回滚；不把寻址摘要当提交许可，无 UI 变化，UI 复用不适用。
6. 测试与 QA：2026-11-15（UTC），比较基线／验证 HEAD 为 `8d2127a1` 加当前工作树。37 项 JVM 聚焦方法全部通过（重绑定 11、确认恢复 6、原输入恢复 7、完整比较 13）。完整 domain JVM 183 类／807 方法、JS／Wasm 各 158 类／771 方法，shared Host 99 类／842 方法、JS 99 类／839 方法、Wasm 101 类／845 方法，共 4875 方法，0 失败／错误／跳过；六测试任务均实际执行。五个 Kotlin 源码摘要与矩阵前固定值一致，原 entry 黄金未改，完整非空 XML 新鲜度、数量、真实 ChromeHeadless 157 及无跳过另行核证。Android 主代码、iOS arm64 生产及 Simulator arm64 测试编译、domain ktlint 与适用 detekt 通过。本片不改服务端 Application Service／schema／DML，端侧存储固定 SQLite，PostgreSQL 矩阵不适用，未把端侧测试算作双后端通过。沿原外部 init 排除历史 response integration／portfolio live 类，不计通过，相关接口未改。
7. 发布：D5、三份功能地图及 apply 指引已按内部两段恢复更新；核对 README、参考资料、词表、主规格／delta、接口／命令示例、配置与迁移，无新领域概念、wire、依赖、schema 或用户操作，其余无需修改。沿撤回额外来源／账户映射证明的边界，无新的用户取舍需要沉淀为通用规范。Cross-platform Impact Check：共享选择恢复影响 Web／Android／iOS，完整矩阵及相称原生编译验证；无页面、路由、交互或用户文案变化，产品页面冒烟、Hallmark 与设备交互 QA 不适用。定位及历史按索引匹配，不新增候选搜索，完整导入性能仍归整条管线验收。回退到不支持新定位摘要的代码须保留原批次行，不能当损坏数据清除。strict 27/27、doctor 正常、六份文档 149 个本地目标／25 个锚点及 diff 检查通过；完整 QA 与最终独立证据门已通过，无遗留 finding，按已有授权提交推送同分支。完整重算调度、自动重试及页面未完成，6.5 不勾选，不归档或创建 PR。

证据根目录 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`：`rebinding-entry-red.log` 固定入口缺失；`rebinding-isolation-red.log`／`.xml` 的五方法中两项失败分别证明较新失效代次和外部 typed 目标修改问题。`rebinding-final-focused.log`／`rebinding-final-focused-xml` 为修复及扩展后的 37 方法全绿。初次比较测试在完整指纹相等后错误要求写集必不相等，已改为该场景应相等，未放宽生产比较器。原数词回归直接核 JSON 输入端口，不能以通用编码器把数词转浮点后的结果作原输入 oracle。矩阵 `rebinding-matrix.log` 成功（4m54s，217 任务：54 执行／163 up-to-date）；`rebinding-tested-code.json`、`verify-rebinding.py`、`rebinding-final-results.json` 及 `rebinding-final-<module>-<task>-xml` 固定五个源码 SHA 与六套完整非空 XML，固定输出不被后续重跑静默覆盖。JS／Wasm 在 ChromeHeadless 157 的 Karma `http://localhost:9876/context.html` 执行领域及真实加密库恢复回归；没有产品页面视口或设备交互，不冒称对应 QA。

实际命令（工作树执行，`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`、`NODE_ENV=development`、`CHROME_BIN=/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`，沿实际 Zulu 21.0.11 Gradle 工具链）：

```sh
./compose/gradlew -p compose \
  -I /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/stable-file-input-self-contained.init.gradle \
  -I /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/quality.init.gradle \
  :business-domain:jvmTest :business-domain:jsBrowserTest :business-domain:wasmJsBrowserTest \
  :shared:testAndroidHostTest :shared:jsBrowserTest :shared:wasmJsBrowserTest \
  :business-domain:compileAndroidMain :shared:compileKotlinIosArm64 \
  :shared:compileTestKotlinIosSimulatorArm64 :business-domain:ktlintCheck \
  :business-domain:detektMetadataCommonMain :business-domain:detektJvmTest \
  --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/verify-rebinding.py
openspec validate --all --strict
openspec doctor
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/check-rebinding-links.py
git diff --check
```

### 确认结论恢复（6.5）

比较基线 `f42adc75`。沿 D5 将确认时的完整结论与原核对输入一并恢复，不增加提交许可或服务端证明。此片属于原 A 类 change 的内部恢复组合；不改变范围、验收或业务规则。

1. 思考：原核对输入保留选择和依赖，但重启后不能还原确认时的只读参与者事实；不能用最新缓存补造原确认结论。
2. 计划：沿 `LocalCashImportReview` 增加确认冻结入口，由实际完整计划、快照和最终写集计算完整指纹；旧编码继续返回未提供结论。沿原加密批次保存，不增加表或另一份可执行计划。
3. 任务拆分：先覆盖实际完整 builder 的确认／恢复、完整指纹与写入分量区分、旧编码和损坏拒绝，再覆盖真实 SQLite 重开／原提交冻结及 Web 加密存读；随后独立审查与跨端验证。
4. 构建：缺失确认入口及读取方法先红，新增入口由完整比较器计算原确认指纹并复用原冻结目标／上下文，新版编码保留单份指纹，旧版不补造确认。真实完整 builder 的文件失败及只读事实变化均影响确认结论；完整计划缺项或损坏确认拒绝恢复，不转成缺省。初版 JSON 回解用例未覆盖计算类型；改为直接取得真实完整计划后，退款目标 CashDecimalLiteral 在通用编码处失败，保留回归并改用既有正式计划冻结边界，同时保持金额、时间及计数编码。新增六方法直接消费真实计算 Map，其中完整选中退款目标的 typed 依赖恢复摘要不变；真实 SQLite 重开／故障及冻结、原会话 owner、Web 加密存读用例已实际验证新版恢复数据。
5. 审查：独立 5a 产品／范围、产品编码规范 §7 工程、恢复／输入安全与最终 diff 及实际子 5b 契约复核已完成。两级均发现 P2：通用值编码不能处理真实计算金额且改变计数词法，原 JSON 回解测试遮蔽入口；已真实先红并复用正式冻结边界，两级增量复核关闭，无遗留 finding。无 UI 变化，UI 复用不适用。
6. 测试与 QA：2026-11-15（UTC），比较基线／验证 HEAD 为 `f42adc75` 加当前工作树。修复后 JVM 聚焦新六、原 Review 七、RelationWrites 八及完整结论十三，共 34 方法成功。完整 domain JVM 182 类／796 方法、JS／Wasm 各 157 类／760 方法，shared Host 99 类／842 方法、JS 99 类／839 方法、Wasm 101 类／845 方法，共 4842 方法，0 失败／错误／跳过；六测试任务均实际执行。七个 Kotlin 摘要与矩阵前固定值一致，原 entry 黄金未改；完整非空 XML 新鲜度、数量、真实 ChromeHeadless 157 和无跳过另行核证。Android 主代码、iOS arm64 生产及 Simulator arm64 测试编译、domain ktlint 与适用 detekt 通过。端侧库固定 SQLite，本片不改服务端 Application Service／schema／DML，PostgreSQL 矩阵不适用，未将端侧存储算作双后端通过。沿原外部 init 排除历史 response integration／portfolio live 类，不计通过，相关接口未改。
7. 发布：D5、三份功能地图及 apply 指引已按实际内部确认结论存读更新；核对 README、参考资料、词表、主规格／delta、接口／命令示例、配置与迁移，无新领域概念、wire、依赖、schema 或用户操作，其余无需修改。没有新用户取舍，继续沿撤回额外来源／账户映射证明的边界，无需新增通用规范。Cross-platform Impact Check：共享恢复编码影响 Web／Android／iOS，实际浏览器存储测试及原生编译验证；无页面、路由、交互或用户文案变化，产品页面冒烟、Hallmark 与设备交互 QA 不适用。完整计划仅在确认时走已有冻结及完整比较器，不持久化第二份计划或新增候选搜索，完整导入性能仍归整条管线验收。回退到仅支持 v1 的代码须保留 v2 批次行，不得当损坏数据清除；恢复支持该格式的代码后继续原操作。strict 27/27、doctor healthy、六份文档 148 个本地目标／25 个锚点及 diff 检查通过；最终独立证据门通过，无遗留 finding，按授权提交推送同分支。稳定选择重绑定、完整重算、自动重试及页面未完成，6.5 保持未勾选，不归档或创建 PR。

证据根目录 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`：`confirmed-review-red.log` 为缺失入口失败；`confirmed-review-focused.log` 为最初 12 方法通过，不作为真实计算 Map 入口证据。`confirmed-review-computed-red.log`／`.xml` 固定实际退款目标失败，修复后的 `confirmed-review-computed-green.log`／`-xml` 为 34 方法成功。完整矩阵 `confirmed-review-matrix.log` 成功（4m55s，217 任务：54 执行／163 up-to-date）；`confirmed-review-tested-code.json`、`verify-confirmed-review.py`、`confirmed-review-final-results.json` 和 `confirmed-review-final-<module>-<task>-xml` 固定七个源码 SHA、六套完整非空 XML 及浏览器运行结果，固定输出不被后续重跑静默覆盖。

实际命令（工作树执行，`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`、`NODE_ENV=development`、`CHROME_BIN=/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`，沿实际 Zulu 21.0.11 Gradle 工具链）：

```sh
./compose/gradlew -p compose \
  -I /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/stable-file-input-self-contained.init.gradle \
  -I /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/quality.init.gradle \
  :business-domain:jvmTest :business-domain:jsBrowserTest :business-domain:wasmJsBrowserTest \
  :shared:testAndroidHostTest :shared:jsBrowserTest :shared:wasmJsBrowserTest \
  :business-domain:compileAndroidMain :shared:compileKotlinIosArm64 \
  :shared:compileTestKotlinIosSimulatorArm64 :business-domain:ktlintCheck \
  :business-domain:detektMetadataCommonMain :business-domain:detektJvmTest \
  --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/verify-confirmed-review.py
openspec validate --all --strict
openspec doctor --json
git diff --check
```

Web 证据为 ChromeHeadless 157、Karma `http://localhost:9876/context.html`：真实加密库先保存旧版核对输入再更新新版确认结论，关闭重开后输入和完整指纹精确读回，刷新事实后不改变，准备原提交后拒绝覆盖；物理文件不得明文出现原选择标记，最终库与密钥材料清除。JS／Wasm 均实际执行；测试未采集产品页面视口，不冒称页面或设备 QA。共享存储用的固定指纹来自已有完整 fixture，仅验证编码存读；完整业务计算由 domain 实际 builder 及原比较器回归覆盖，不把存储 fixture 当独立业务 oracle。

### 完整核对结论比较（6.5）

比较基线 `1100d31a`。沿 D5 组合写入效果与实际核对结果，不自行启用重派或替换用户选择。

1. 思考：写集候选为摊平形式，不能表达联合方案；未写入行、文件失败、失效选择和只读参与者业务值也须参与结论。
2. 计划：沿原完整计划读取业务结果，复用写入分量的身份、金额和时刻语义，按已知内部结构排除临时编号及观察依赖；原文和诊断保持精确 JSON，不递归猜测同名字段。
3. 任务拆分：先以真实完整 builder 覆盖行／文件／候选组合、只读参与者和失效选择变化，确认仅观察与临时引用变化不变、输入不修改，再独立工程／契约审查及跨端验证。
4. 构建：入口缺失先红，再沿同一身份／财务原语组合完整写入及计划结果；真实两行各有两个候选的四个联合方案中删除一组，实际重建的写集指纹保持相同而完整结论改变。补行／文件、预计结果、失效选择、只读金融／人工值、旧关系／问题候选、资金及类型效果；财务表示等价，开放 JSON 原数词与同名键保持。派生编号、历史代次、观察依赖只在已知位置移出，不改原恢复上下文。参与者通过索引队列、每条目／边一次展开，在编码前收齐完整旧条目和历史候选关联；关系业务引用缓存避免重复展开原证据。
5. 审查：独立 5a 产品／范围、产品编码规范 §7 工程、输入／比较安全及最终 diff，实际独立子 5b 契约复核已完成。P2 派生 ID 漏入失效分量／commit 标签，经真实失效规则及已接受组先红、语义归一后关闭；历史候选使已删除来源与存活同键来源混合的 P2，经保持参与者集合不变的候选切换先红，再按两库部分唯一键的存活条件回退实际 ID，两级复核关闭。只读事实遗漏和后增参与者的候选遗漏也先红、索引队列修复并复核；无遗留 finding。无 UI 变化，UI 复用与 Hallmark 不适用，不以比较器完成宣称重绑定或重派接通。
6. 测试与 QA：2026-11-15（UTC），比较基线／验证 HEAD 为 `1100d31a` 加当前工作树。聚焦新完整结论 13、原写入分量 9、TransactionWrites 11、SourceWrites 15，共 48 方法成功。完整 domain JVM 181 类／790 方法，JS／Wasm 各 156 类／754 方法，共 2298 方法，0 失败／错误／跳过；三个测试任务均实际执行。隔离 JVM 加载实际 fixture 和两个生产比较器取得固定摘要，在共享测试中加入两项断言后重跑同一完整矩阵，三端输出一致；这些断言验证跨运行时一致性，不作为独立业务 oracle。Android 主代码、iOS arm64 生产及 Simulator arm64 测试编译、domain ktlint 和适用 detekt 通过。三个 Kotlin 摘要与完整矩阵前固定值相同，原 entry 黄金未改；XML 新鲜度、数量、真实 ChromeHeadless 157 和无跳过另行核验。纯 domain 比较器不接 IO／服务端／schema／DML，本片不触发新增双后端合同矩阵，不把领域单测当作存储验证。
7. 发布：D5、三份功能地图及 apply 指引已按真实内部比较范围更新；核对 README、参考资料、词表、主规格／delta、接口／命令示例、配置与迁移，没有新领域概念、wire、依赖、schema 或用户操作，其余无需修改。没有新的用户取舍，沿撤回额外来源／映射证明的边界，无需新增通用规范。Cross-platform Impact Check：共享比较逻辑影响 Web／Android／iOS，真实浏览器领域单测及原生编译覆盖；无页面、路由、交互或用户文案变化，产品页面冒烟、Hallmark 与设备 QA 不适用。参与者按索引队列及访问集合展开，关系业务引用缓存避免大证据重复复制，规范集合排序使用预计算文本；未新增候选搜索，完整导入性能仍归整条管线验收。strict 27/27、doctor healthy、六份文档 148 个本地目标／25 个锚点及 diff 检查通过；最终独立证据门通过，无遗留 finding，按授权提交推送同分支。指纹尚未接入持久恢复或自动提交，可独立随代码回退，无数据迁移。完整自动重派、稳定选择重绑定和页面仍待组合，6.5 不勾选，不归档或创建 PR。

证据根目录 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`：`full-conclusion-red.log` 为缺失入口失败；`full-conclusion-{participants,identity,closure,deleted}-red.log` 及相应 XML 固定实际发现，`full-conclusion-deleted-green.log` 为最终 48 方法成功。首轮完整矩阵 `full-conclusion-matrix.log` 成功（2m46s，96 任务：25 执行／71 up-to-date），原固定证据保留。`full-conclusion-jvm-digest.log`／`.txt` 固定实际 JVM 摘要；追加两项共享断言后的最终矩阵 `full-conclusion-cross-runtime-matrix.log` 成功（2m15s，96 任务：17 执行／79 up-to-date）。`full-conclusion-cross-runtime-tested-code.json`、`verify-full-conclusion-cross-runtime.py`、`full-conclusion-cross-runtime-final-results.json` 和 `full-conclusion-cross-runtime-final-<task>-xml` 固定当前三个源码 SHA、三套完整非空 XML 及浏览器运行结果；原固定输出不被后续重跑静默覆盖。Web 为 Karma ChromeHeadless 157 的领域测试，无产品页面变化，不冒称设备或页面 QA。

实际最终命令：

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
NODE_ENV=development \
CHROME_BIN='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' \
./compose/gradlew -p compose \
  -I /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/quality.init.gradle \
  :business-domain:jvmTest :business-domain:jsBrowserTest :business-domain:wasmJsBrowserTest \
  :business-domain:compileAndroidMain :business-domain:compileKotlinIosArm64 \
  :business-domain:compileTestKotlinIosSimulatorArm64 :business-domain:ktlintCheck \
  :business-domain:detektMetadataCommonMain :business-domain:detektJvmTest \
  --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/verify-full-conclusion-cross-runtime.py
openspec validate --all --strict
openspec doctor --json
git diff --check
```

### 写集业务效果指纹（6.5）

比较基线 `b54041f9`。作为 D5 完整重派结论的写入分量，先覆盖完整最终业务效果，不独立开启自动提交。

1. 思考：旧服务端 fingerprint 包含修订和派生编号，不能用于排除无关工作区变化；仅比较最终展示也会遗漏人工标记、独立值及来源增量。
2. 计划：沿现有 DTO 明确归一财务字段和各类业务引用，保持全部写入业务字段，排除基线／修订及纯分配元数据；原文 JSON 保留精确数词和普通字符串，不递归删除同名键。完整核对结论与调度仍另行组合。
3. 任务拆分：先覆盖任意临时引用改名、观察修订变化、精确金额／时刻等价、完整字段变化、只读输入及真实本地完整 builder，再独立工程／契约审查和跨端验证。
4. 构建：先以缺失入口失败，再覆盖真实完整 builder、临时引用改名、观察修订变化、财务表示等价和完整业务字段变化；原文精确数词及同名键、UTF-16 孤立代理字符保留。身份审查发现先用真实绑定命中旧空 record_id 路径及无唯一来源键的不同存量候选复现，再统一身份函数：完整非空渠道／编号使用业务键，其余 Existing 回退实际 ID，New 仍要求完整身份。补真实旧条目 C 的完整成员和问题退休分支，不改变原 builder。
5. 审查：独立 5a 产品／范围、产品编码规范 §7 工程、输入／指纹安全和最终 diff，以及实际独立子 5b 契约复核已完成。身份 P2 经失败回归、修复及两级增量复核关闭，无遗留 finding；补充 C 和 resolvedIssues 分支也已复核。无 UI 改动，UI 复用与 Hallmark 不适用；完整结论及重派未在本片完成声明内。
6. 测试与 QA：2026-11-15（UTC），比较基线／验证 HEAD 为 `b54041f9` 加当前工作树。身份修复后 19 方法聚焦成功，补充后新指纹 9、TransactionWrites 11、SourceWrites 15，共 35 方法成功。完整 domain JVM 180 类／777 方法，JS／Wasm 各 155 类／741 方法，共 2259 方法，0 失败／错误／跳过，三个测试任务均实际执行；Android 主代码、iOS arm64 生产及 Simulator arm64 测试编译、domain ktlint 与适用 detekt 均通过。四个 Kotlin 源码摘要与完整矩阵前固定值一致，原 entry 黄金未改；XML 新鲜度、数量、真实 ChromeHeadless 157 和无跳过另行核验。纯 domain 比较器未接 IO／服务端／schema／DML，本片不触发双后端合同矩阵，不能把这些测试算作新存储验证。沿原 JVM 工具链运行，不调整规则／输入限制或增加例外。
7. 发布：D5、三份功能地图及 apply 指引已按内部写入分量更新；核对 README、参考资料、词表、主规格／delta、接口／命令示例、配置及迁移，无新领域概念、wire、依赖、schema 或用户操作，其余无需修改。没有新的用户取舍，沿撤回额外来源／映射证明的边界，无需新增通用规范。Cross-platform Impact Check：共享比较逻辑影响 Web／Android／iOS，真实浏览器领域单测及原生编译覆盖；无页面、路由、交互或用户文案变更，产品页面冒烟、Hallmark 与设备 QA 不适用。归一和编码按实际树规模处理，集合排序使用预计算文本，不引入候选搜索；完整导入性能仍归整条管线验收。strict 27/27、doctor healthy、六份文档 147 个本地目标／25 个锚点及 diff 检查通过；最终独立证据门已通过，无遗留 finding，按授权提交推送同分支，可独立随代码回退，无数据迁移。完整核对结论、决策重绑定、静默重派及页面未完成，6.5 不勾选，不归档、不创建 PR。

证据根目录 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`：`write-conclusion-red.log` 为缺失入口失败；`write-conclusion-identity-red.log`／`-red-xml` 固定两项身份回归失败，`write-conclusion-identity-green.log` 和 `write-conclusion-fixed-focused.log` 为 19／35 方法成功。完整矩阵 `write-conclusion-matrix.log` 成功（2m45s，96 任务：25 执行／71 up-to-date）；`write-conclusion-tested-code.json`、`verify-write-conclusion.py`、`write-conclusion-final-results.json` 和 `write-conclusion-final-<task>-xml` 固定四个源码 SHA、三套完整非空 XML 及浏览器运行结果。固定证据不得被后续重跑静默覆盖；Web 为 Karma ChromeHeadless 157 的领域测试，无产品页面变化，不冒称设备或页面 QA。

实际命令（在工作树执行，`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`、`NODE_ENV=development`、`CHROME_BIN=/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`，Gradle 实际工具链 Zulu 21.0.11）：

```sh
./compose/gradlew -p compose \
  -I /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/quality.init.gradle \
  :business-domain:jvmTest :business-domain:jsBrowserTest :business-domain:wasmJsBrowserTest \
  :business-domain:compileAndroidMain :business-domain:compileKotlinIosArm64 \
  :business-domain:compileTestKotlinIosSimulatorArm64 :business-domain:ktlintCheck \
  :business-domain:detektMetadataCommonMain :business-domain:detektJvmTest \
  --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/verify-write-conclusion.py
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/check-write-conclusion-links.py
openspec validate --all --strict
openspec doctor --json
git diff --check
```

### 稳定核对输入持久化（6.5）

比较基线 `0bbdee79`。沿 D5 在同一加密批次中保留账户、行、关系及 entry 选择与最小恢复依赖；不新增服务端证明或重跑启发式。

1. 思考：最小关系上下文已共享，端侧尚未保存实际核对输入；entry 选择还须原完整目标依赖，不能把展示 JSON 猜回计算类型。
2. 计划：冻结四类原输入和最小关系上下文，仅保存本次 entry 选择所引用的目标；内部类型编码显式区分计算金额／时刻和普通字符串。沿现有单批次行原子替换，准备原提交后拒绝更新选择；沿原范围、会话和取消围栏访问。
3. 任务拆分：先覆盖真实计算计划的 entry 编辑恢复、目标变化拒绝、数值类型／精度及输入隔离，再覆盖真实 SQLite 重开、更新失败保留、范围隔离、原提交冻结与会话凭据变化；补真实 Web 加密重开并独立审查。
4. 构建：域与存储入口先有缺失实现失败，再组合冻结输入、明确类型的最小目标编码与同批次更新；保留原输入数词、新来源 record_id、明确空值及关系失效代次。真实计算计划、原完整 entry 黄金、失效选择及同范围 SQLite／Web 加密重开均有回归；准备原提交后禁止再改选择。独立审查发现真实退款边使用 CashDecimalLiteral，已用完整非空关系目标先红复现，再以明确 decimal 标签保留原类型／文本；恢复后金额依赖变化仍拒绝沿用编辑。
5. 审查：独立 5a 产品／范围、产品编码规范 §7 工程、输入／恢复安全与最终 diff，以及实际独立子 5b 契约复核已完成。唯一 P2 为遗漏真实关系计算金额类型，已按上述真实失败回归修复，经两级增量复核关闭，无新 finding。没有 UI 改动，UI 复用与 Hallmark 不适用；完整重算及页面不在本片完成声明内。
6. 测试与 QA：2026-11-15（UTC），比较基线／验证 HEAD `0bbdee79` 加当前工作树。domain JVM 179 类／766 方法、JS 与 Wasm 各 154 类／730 方法，shared Host 99 类／842 方法、JS 99 类／839 方法、Wasm 101 类／845 方法，共 4752 方法，0 失败／错误／跳过。新增域七方法在三个目标实跑，真实 SQL 批次七方法、会话恢复七方法和 Web 加密九方法均通过；原 entry 黄金未修改，完整 applied 结果仍相等。Android 主代码、iOS arm64 生产及 Simulator arm64 测试编译，domain ktlint／适用 detekt 通过。Kotlin 增量缓存关闭失败的聚焦运行保留为失败；完整验证关闭增量编译，不放宽规则或测试。首轮领域 JS 启动停滞且无测试，诊断写入曾报 ENOSPC；核无活动浏览器／请求后终止该 Karma，清理已确认属于本工作树的旧生成临时包，原矩阵其余五测试任务通过；同代码补跑的完整命令最终成功，领域 JS 实际执行，其余当前输入未变的结果由 Gradle 复用，源码 SHA 与 XML 新鲜度另行核证。端侧库固定 SQLite，本片不改服务端 Application Service／schema／DML，PostgreSQL 矩阵不适用；未将 Web SQLite 当双后端。沿原外部 init 排除历史 response integration／portfolio live 类，不计通过，相关接口未改。
7. 发布：D5、导入／共享／总览功能地图及 apply 指引已按实际内部范围更新；核对 README、参考资料、词表、主规格／delta、接口／命令示例、配置与迁移，无新领域概念、wire、依赖、服务端 schema 或用户操作，其余无需修改。同批次可选 review 包络兼容旧文件／提交行；代码回退须保留含新恢复字段的行，旧代码不能解码时不得当损坏清除。没有新用户取舍，继续沿撤回额外来源／映射证明的边界，无需新增通用规范。Cross-platform Impact Check：共享输入与存储影响 Web／Android／iOS，以上共同测试、真实浏览器加密操作和原生编译覆盖；无页面、路由、交互或用户文案变化，产品页面冒烟、Hallmark 与设备交互 QA 不适用。输入及所选目标按实际树规模处理，不增加候选搜索，完整导入性能仍归整条管线验收。strict 27/27、doctor healthy、6 份文档 146 个本地目标／25 个锚点及 diff 检查通过；最终独立证据门已通过，无遗留 finding；按授权提交推送同分支。完整重派、结论指纹、离线写入限制及页面仍待组合，6.5 不勾选；不归档或创建 PR。

证据根目录 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`：`review-input-red.log`／`review-input-store-red.log` 为缺失入口失败，`review-input-refund-red.log`／`.xml` 为真实退款目标的 InvalidCashValue；`review-input-focused.log`、`review-input-web-focused.log` 为聚焦成功，`review-input-fixed-focused.log` 保留后续增量缓存故障，不计整轮通过。`review-input-matrix.log` 保留领域 JS 未执行及其余任务实际执行；`review-input-final-matrix.log` 为同代码最终成功（57s，217 任务：16 执行／201 up-to-date）。`review-input-tested-code.json` 固定 11 个 Kotlin SHA，`verify-review-input.py` 核当前摘要、未改原黄金、两日志任务终态、六套非空且新鲜 XML、全部计数／结果及浏览器版本，固定输出为 `review-input-final-results.json` 和 `review-input-final-<module>-<task>-xml`，不静默覆盖已固定 XML。临时包清理的精确范围见 `review-input-stale-bundles.json`，当前构建和正式证据保留。

实际命令（在工作树执行，`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`、`NODE_ENV=development`、`CHROME_BIN=/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`，Gradle 实际工具链 Zulu 21.0.11）：

```sh
./compose/gradlew -p compose \
  -I /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/stable-file-input-self-contained.init.gradle \
  -I /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/quality.init.gradle \
  :business-domain:jvmTest :business-domain:jsBrowserTest :business-domain:wasmJsBrowserTest \
  :shared:testAndroidHostTest :shared:jsBrowserTest :shared:wasmJsBrowserTest \
  :business-domain:compileAndroidMain :shared:compileKotlinIosArm64 \
  :shared:compileTestKotlinIosSimulatorArm64 :business-domain:ktlintCheck \
  :business-domain:detektMetadataCommonMain :business-domain:detektJvmTest \
  --continue --no-daemon --max-workers=1 --no-configuration-cache --no-build-cache \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/verify-review-input.py
openspec validate --all --strict
openspec doctor --json
git diff --check
```

Web 证据为 ChromeHeadless 157、Karma `http://localhost:9876/context.html`：正式加密库保存两份先后核对选择，关闭重开后精确读回最新份，事实刷新保留，准备原提交后拒绝更改；原物理目录不得明文出现选择标记，最后库与密钥材料清除。JS／Wasm 均执行同一方法；这些浏览器领域／存储测试未采集页面视口，不冒称产品页面验收。

### 关系选择恢复上下文共享（1.3／6.5）

比较基线 `c19f7f22`。复用既有最小引用上下文与失效代次，作为稳定选择保存／重算的前置，不新增服务端来源或账户映射证明。

1. 思考：选择应用已共享，但提取／校验仍在 backend，端侧缺少恢复相同选择及保留新 record_id 的可靠入口；entry 编辑另有精确目标依赖，不能仅靠关系上下文宣称全部选择可重用。
2. 计划：共享纯提取／校验及单份上下文替换，backend 保留整数种类／真假／字段归一薄桥；端侧 JSON 入口保留精确 generation、候选引用与明确空值，不恢复可执行计划或改变原上界。
3. 任务拆分：先用原 36 条 Python 合同及整数边界、只读输入、重复恢复和失效不复活写失败测试，再复用原 backend 调用点，独立复核、跨端与受影响后端验证。
4. 构建：原 36 条合同与新增端侧入口先因缺失实现失败；随后共享原提取／校验／单份替换规则，后端原调用点改为薄委托并保留异常类型别名。端侧 JSON 与计算型输入分开，后者只提取稳定引用，不解码或序列化财务正文；默认计数显式标记整数。端侧七方法覆盖原 36 条、上界／数字种类、明确清空及重复恢复、真实选择应用后失效不复活、输入容器隔离、外层原数词及忽略正文／先拒绝形状；与后端原四方法聚焦通过。精度窄化、忽略正文递归和保留坏上下文前递归均先有真实失败回归，再用局部惰性只读视图及原数词复制修复。匿名协变 Map 委托生成的 containsValue 导致 Wasm 模块加载类型校验失败，已有真实浏览器失败及 CDP 编译错误证据；改为显式 AbstractMap／AbstractList 后同一七方法真实执行通过，不增加超时或改变用例。完整持久选择／entry 依赖和重派仍待接线。
5. 审查：独立 5a 产品／范围、工程（产品编码规范 §7）、输入／异常安全和最终 diff，以及实际独立子 5b 契约复核已完成。JSON 边界 P2 为全树 decode／encode 改写无关数词，且在忽略正文或拒绝未知形状前递归，已按上述失败回归修复并经两级增量复核关闭。Wasm 加载 P2 已改为显式视图，经两级窄复核确认语义、访问顺序与复杂度保持，真实 Wasm 七方法绿终态后关闭；无新 finding。纯提取逐分支兼容、合法 Long 域与原 36 条导出完整性已独立核对；没有新增服务端证明、全局预算或重复形状门。无 UI 变化，UI 复用与 Hallmark 不适用。
6. 测试与 QA：2026-11-15（UTC），比较基线／验证 HEAD 为 `c19f7f22` 加当前工作树。修复后完整矩阵 domain JVM 178 类／759 方法、backend 196 类／1324 方法、JS 与 Wasm 各 153 类／723 方法，共 3529 方法，0 失败／错误／跳过；四测试任务均实际执行，端侧新增七方法在 JVM／JS／Wasm 实跑，backend 原四方法保留。原 36 条导出逐项与未修改的 Python 资源相等。真实写集服务的既有 SQLite／PostgreSQL 各 63 方法亦在本轮默认后端回归通过，显式使用既有 postgres.env 的专用 `_test` 库；本片不改 DML、schema 或存储合同，不把纯上下文测试当作新持久化交付。Android 主代码、iOS arm64 生产及 Simulator arm64 测试编译，domain／backend ktlint、适用 detekt 和 backend installDist 均通过。首轮矩阵及两次 Wasm 定向加载失败保留原日志／CDP 诊断，未计通过；最终证据以显式视图修复后新固定的五个 Kotlin SHA、日志和 XML 为准。默认 backend 排除历史迁移分类，未补跑的历史外部证据不计入本片通过项。
7. 发布：D2、两模块／总览功能地图及 apply 指引已按实际共享范围更新；核对 README、参考资料、词表、主规格／delta、接口／命令示例、配置与迁移，没有新领域概念、wire、依赖、schema 或操作，其余无需修改。没有新的用户业务取舍，继续沿撤回额外来源／账户映射证明及保持规则行为的既定边界，无需新增通用规范。Cross-platform Impact Check：domain 共享规则影响 Web／Android／iOS，backend 原调用点也消费同一实现；上述真实浏览器单测与原生编译覆盖，没有页面、路由、交互或用户文案修改，Hallmark、UI 复用、产品页面冒烟及设备交互 QA 不适用。保留原形状与规模门，提取／校验按引用、组及候选线性读取，惰性视图不递归忽略正文；没有新增候选搜索，完整导入性能仍归整条管线验收。strict 27/27、doctor healthy、6 份文档的 145 个本地目标／25 个锚点及 diff 检查已通过，最终独立证据门已通过，无遗留 finding；按授权提交推送同分支，可随代码独立回退，无数据迁移。持久决策、entry 重绑定、结论指纹、自动重派和页面仍未完成，1.3／6.5 保持未勾选，不归档或创建 PR。

证据根目录 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114`：`choice-context-red.log` 记录入口缺失失败；`choice-context-{precision,ignored-body,invalid-retention}-red.log` 分别记录数词窄化、忽略正文递归和保留无效上下文前递归失败；`choice-context-initial-matrix.log`、`choice-context-wasm-focused.log`、`choice-context-wasm-one.log` 保留 Wasm 加载失败，`choice-context-wasm-loading-diagnostic.jsonl` 捕获 CompileError，`choice-context-wasm-rejected-function.txt` 按原二进制 name section 记录失败函数 containsValue。`choice-context-wasm-fixed-focused.log` 及其 Wasm XML 确认修复后七方法非空通过。最终矩阵见 `choice-context-matrix.log`（成功 6m35s，113 任务：26 执行／87 up-to-date）、`choice-context-tested-code.json`、`choice-context-final-results.json`、`choice-context-final-<module>-<task>-xml` 和 `verify-choice-context.py`；核证覆盖源码摘要、全部 XML 新鲜度／非空／计数／结果、原 36 条完整一致性和真实浏览器版本，固定 XML 不随重跑静默覆盖。Web 为 ChromeHeadless 157 的 Karma 浏览器领域测试，无产品页面变化；不把这些单测当作产品页面或原生设备 QA。

实际命令（在工作树执行，显式加载证据根 `postgres.env`；`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`、`NODE_ENV=development`、`CHROME_BIN=/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`，Gradle 工具链沿 Zulu 21.0.11）：

```sh
./compose/gradlew -p compose \
  -I /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/quality.init.gradle \
  :business-domain:jvmTest :backend:test \
  :business-domain:jsBrowserTest :business-domain:wasmJsBrowserTest \
  :business-domain:compileAndroidMain :business-domain:compileKotlinIosArm64 \
  :business-domain:compileTestKotlinIosSimulatorArm64 \
  :business-domain:ktlintCheck :business-domain:detektMetadataCommonMain \
  :business-domain:detektJvmTest :backend:ktlintCheck :backend:detektMain \
  :backend:detektTest :backend:installDist --continue --no-daemon --max-workers=1 \
  --no-configuration-cache --no-build-cache -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8'
python3 /Users/huangwenlong/.cache/finance-tracker/offline-ledger-1114/verify-choice-context.py
openspec validate --all --strict
openspec doctor --json
git diff --check
```


### 页面原命令收据交付（6.6／10）

1. 思考：比较基线 `82cceaec`。实际页面只接受旧计划收据，合法写集成功因 `plan_id=null` 被拒；引用在仓储返回后才保存，无法承接本地清理前交付。已用实际 ViewModel 红回归复现。整条 change 仍为 A，本片不改变既有流程或财务语义。
2. 计划：页面仓储增加原冻结命令及成功交付回调，旧三参调用保持；确认／核实在回调内关联响应并保存同范围收据引用。新写集须有真实版本及身份结果，不补造计划 ID；按收据 ID 重开只接受确切 ID。重复交付可幂等保存，失效任务必须取消，交付或保存失败不得授权本地清理。
3. 任务拆分：合法写集成功先红再绿；覆盖确认、核实、确切 ID 恢复、错命令／迟到／畸形响应、保存挂起／失败、取消及重复交付；完成独立复核和共享矩阵。Graph、本地批次恢复指针、结果统计、账户 scope 及完整生命周期仍继续实施，不关闭对应整项。
4. 构建：既有 `CashImportTaskViewModel` 直接消费原命令交付入口；相同收据重复交付不再创建确认操作，核实后的重复指针写入移除；保存失败保留已知成功与原异常，原 Submission 引用仍可恢复。未改服务端协议、财务写集、数据库或旧恢复编码。
5. 审查：独立 5a 已核原命令关联、迟到取消、重复交付、畸形收据拒绝、保存失败保留成功及原诊断，无 finding。代码库防劣化调度的独立契约与 UI 复用审查均无 finding；新重载仅承接原命令及交付，不扩服务端证明或恢复编码，UI 继续复用原状态／结果页，没有新增组件或 token。TS 增量窄复核确认确切收据 GET、重开不重复提交及本 suite 的完整缓存夹具，无 finding。
6. 测试与 QA：2026-11-16，比较基线／验证 HEAD `82cceaec` 加当前工作树。`~/.cache/finance-tracker/offline-ledger-1116/receipt-command/red.log` 的单一 ViewModel 用例实际断言失败；`red-xml` 固定原 XML。最终矩阵 2m44s，187 任务：33 执行／154 up-to-date；Host 102 类／961 方法、JS 102 类／919 方法、Wasm 104 类／925 方法共 2805 方法，三测试任务全部实际执行，0 失败／错误／跳过；9 个新增方法均在三个目标实跑。iOS arm64 生产与 Simulator arm64 测试实际编译通过。`commands.json`、`tested-code.json`、`matrix.log`、`verify.py` 及 `final-results.json` 固定五份 Kotlin 摘要和 308 份新 XML，验证非空、用例、数量、结果与新鲜度。
   - 真实浏览器：Google Chrome 157.0.8103.78，1440×900，当前生产 Wasm／Ktor 客户端，`http://127.0.0.1:18379/w/workspace-1/cash-import`。既有用例实际选择文件、切换核对筛选、确认、查看实际统计、重载后读取同一收据；另一用例注入预期 503 结果未知后，实际点击核实，仍使用原键且只提交一次。两用例 2/2 通过（17.0s），无非预期控制台或网络错误，确认及重开截图与代码摘要保留在 `browser-artifacts`／`browser-tested-code.json`；合成 HTTP 响应不等同真实后端存储验收。首轮 trace 证实旧本 suite 夹具缺 `/ledger/version`、返回 404 而在登录恢复拒绝打开；现只补原工作区版本与完整快照，生产逻辑未放宽。首轮失败保留在 `first-failed-browser-*`，未计为通过。生产包新鲜度门及 `browser-build.json` 核当前输入／实际产物，未沿用旧包冒称。
   - Cross-platform Impact Check：Web／Android／iOS 共用仓储和状态层；上述实际共享矩阵与原生编译覆盖。当前无服务端、DML 或 schema 变更，双后端不适用；6.5 完成性核证不冒称新的服务端矩阵。本片无 Compose、布局、文案或路由变化，不新增视觉方向／原型／Hallmark 审计；现有页面的受影响交互已执行真实冒烟。新写集 Graph、批次恢复编码及整条离线入口仍待后续接线，未声称其端到端通过。strict 27/27、doctor healthy、六文档的 188 本地目标／36 锚点及 diff 检查通过。
7. 发布：已按最终实现核对 README、docs、参考入口、词表、主规格／delta、接口／命令示例、配置与迁移；仅 design、tasks、导入／共享／总览功能地图及 apply 指引需同组更新，其余没有新增行为、操作或领域概念，无需修改。用户既有决策与最终映射增量、撤回额外来源证明等边界保持，没有新的用户取舍或需新增的通用规范。当前整体 31/71，最终独立证据门已通过，无遗留 finding，按已有授权同分支提交推送；不归档、不建 PR。代码可单独回退，无数据迁移；保留原收据引用和加密恢复材料，不能用清空恢复绕过失败。


### 本地批次页面恢复入口（6.6／10）

1. 思考：比较基线 f30399e8。本地原请求及尝试键已由加密批次保存；旧页面恢复编码只认服务端会话／计划，不能用旧运行时计划恢复该请求。v2 local_batch 读取缺失已先用真实失败回归复现。
2. 计划：恢复引用只含批次编号，上下文标明本地或远端来源；页面沿独立批次恢复端口取得同批次准备或成功回调，不构造假的计划或重复保存实际尝试键。旧 v1 引用仍严格读取，命名空间保持；回退保留 v2 材料，恢复支持版本后再继续。
3. 任务拆分：覆盖严格编码、再次重开、原批次核实、未知／明确拒绝、准备恢复、成功交付失败／取消、错上下文以及编辑／确认／取消／退出围栏和恢复动作资格。完整 adapter、原工作区账户保护、真实结果统计和 Graph 接线继续由整体任务承接。
4. 构建：LocalBatch 分支不依赖旧 frozenCommand；hasSubmission 统一页面状态与结果页围栏，主动本地确认也保留批次身份，恢复可编辑上下文才同时清原命令和恢复身份。确认前保批次引用，已知成功后保确切收据引用；不改原 HTTP、SQL、财务语义或原请求重派规则。
5. 审查：独立 5a 按产品编码规范 §7 核对范围、工程、输入／异常、恢复围栏及最终 diff；独立 5b 契约及 UI 复用指出上下文覆盖旧会话引用、结果页隐藏本地恢复动作两项 P2，均经失败回归、修复及独立重审关闭，无遗留 finding。所有准备上下文共用私有引用选择，结果页直接消费共用恢复动作资格；LOCAL 确认保留批次围栏，可编辑恢复同时清除原冻结命令和批次身份。
6. 测试与 QA：2026-11-16，比较基线／验证 HEAD 为 `f30399e8` 加当前工作树。证据根 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1116/local-batch-recovery`：`red.log`／`red-xml` 保留旧编码实际失败，`pointer-red.log`／`pointer-red-xml` 保留两条指针错路由实际失败；定向回归及最终共享矩阵通过。`commands.json` 记录实际命令，`matrix.log` 成功 2m11s，187 任务：29 执行／158 up-to-date；Host 102 类／973 方法、JS 102 类／931 方法、Wasm 104 类／937 方法，共 2841 方法，三测试任务全部实际执行，0 失败／错误／跳过。12 个新增方法在三个目标均实跑，iOS arm64 生产与 Simulator arm64 测试均实际编译成功。`tested-code.json`、`verify.py`、`final-results.json` 及 308 份固定 XML 核对九份 Kotlin 摘要、完整计数、用例及新鲜度，独立复核通过。
   - 真实浏览器：Google Chrome 157.0.8103.78，1440×900，当前生产 Wasm／Ktor 客户端，`http://127.0.0.1:18379/w/workspace-1/cash-import`。既有页面实际选择文件、切换核对筛选、确认、查看实际统计并重载同一收据；结果未知时点击核实沿原键且只提交一次；旧远端权威未提交拒绝只提供返回，不重派或核实。三用例 3/3 通过（2.4m，含构建），无跳过／重试或非预期控制台／网络错误；确认与重开两张截图保留。`browser-command.json`、`browser-tested-code.json`、`browser-results.json`、`browser-verification.json` 和 `browser-build.json` 固定十份代码摘要、实际用例及当前生产包指纹，未沿用旧包。合成 HTTP 夹具不等同真实后端存储验收。
   - Cross-platform Impact Check：Web／Android／iOS 共用核心、页面状态与恢复动作，上述共享测试、原生编译及既有页面冒烟覆盖。本片没有新布局、文案、token 或视觉方向，Hallmark／原型与原生设备交互 QA 不适用；不改 DML、schema 或服务端存储合同，双后端不适用。真实本地生命周期 adapter 与 Graph 尚未组合，不把合成仓储的 ViewModel 测试称为该新入口端到端通过。
7. 发布：按最终实现核对 README、docs、参考入口、词表、主规格／delta、接口／命令示例、配置与迁移；仅 design、tasks、导入／共享／总览功能地图及 apply 指引需同组更新，其余无新增业务行为、wire、配置、领域概念或操作，无需修改。strict 27/27、doctor healthy、六文档的 188 个本地目标／36 个锚点及 diff 检查通过；最终独立源码／单位测试／浏览器／文档证据门通过，无遗留 finding。整体 31/71 不变，按授权小步提交推送，不归档、不建 PR。本片无新增用户业务取舍，保持撤回额外来源／账户映射证明的边界，无需新增通用规范。回退须保留 v2 引用与原加密材料，旧版本不支持时恢复支持版本后继续，不能清空恢复绕过失败。

### 写集收据实际统计（3.4／6.6／10）

1. 思考：比较基线 `a76690e8`。写集永久收据已有逐项实际结果与身份，但缺少实际统计及待处理数，现有结果页会把缺省值显示为零。整条 change 仍为 A，本片只补服务端可核实的提交分量，不新增来源或账户映射证明。
2. 计划：复用既有收据字段，在原事务内根据真实前后事实及身份命中统计来源操作和关系选择组；实际版本变化决定写入事实，稳定条目成员决定待处理记录数。原键重放及永久读取保留首结果，不查询后来状态改写摘要；端侧文件／跳过／批内重复另由完整本地生命周期组合。
3. 任务拆分：先用真实服务覆盖来源状态转换、精确幂等、多边组、草稿关系／问题、只读依赖、无变化及旧收据稳定性，取得失败证据；再实现统计，完成独立复核和 SQLite／真实 PostgreSQL 同合同矩阵。
4. 构建：`CashImportWriteSetReceipt` 消费原命令真实身份、原始关系组及同事务前后事实，保留逐项回执的既有 outcome；关系重放按原组计重复，不能按边数增加计数。条目收尾返回写后事实与实际 D 编辑数量，在协调后每次 patch 前固定原修订，只有该 patch 推进才计数；原空条目路径仍不额外读库。关注父数与完整条目的待处理数分开，只有本次业务对象进入范围，旧依赖和未编辑的声明不扩范围。统计随首收据存储，读取及重放不重算，不新增 SQL、投影物化、请求字段或证明。
5. 审查：独立 5a 按产品编码规范 §7 核范围、工程、输入／异常、结果确定性、事务和最终 diff；代码库防劣化调度的独立契约 5b 实际执行。预审指出 D-only 应保留 `updated_entries`，已用双库失败断言固定缺失、在真实 D patch 前后核修订并补仅人工标记／同值重复／C 与 B 不冒充 D 回归，独立重审关闭。完整成员有旧问题与本次父集合分离、关系多边组计数、只读依赖及旧收据不变均复核，无遗留 finding；无 UI 组件、布局或文案变更，UI 专项不适用。
6. 测试与 QA：2026-11-16，比较基线／验证 HEAD `a76690e8` 加当前工作树。证据根 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1116/write-set-statistics`：`red.log`／`red-xml` 固定 SQLite／PostgreSQL 两次新建成功后 `summary=null` 的真实断言失败；`entry-red.log`／`entry-red-xml` 固定两次 D 统计缺失失败。初次扩大用例中的非法夹具（新来源带待更新问题、ignored 设不活动、问题 owner 不在来源操作中）已按原生产合同修正，未放宽生产，原 `focused`／`focused-fixed` 失败保留且不计绿。`focused-current` 的写集／来源两类共 258 方法全绿；默认任务明确排除历史分类，不能把同时指定的历史条目类算作已执行。
   - 完整默认后端矩阵 `final-matrix-command.json`／`final-matrix.log` 成功 4m37s，24 任务：12 执行／12 up-to-date。198 类／1344 方法全绿，0 失败／错误／跳过；写集提交 132、公开 HTTP 28、来源保存 126 方法均在同一真实服务合同的 SQLite／显式 `_test` PostgreSQL 上执行。六个新增方法在双库各实跑；公开回包通过实际共享收据 DTO 解码断言统计、待处理与写入事实，原键／ID 读取仍返回原响应。backend ktlint、detektMain／detektTest 和 installDist 均实际执行成功。`verify.py`、`final-results.json` 固定五份 Kotlin 摘要及 198 份新鲜非空 XML，逐项核计数、双库方法、当前源码和构建终态。
   - 历史条目仓储分类另以已准备的 `offline-ledger-1114/{sqlite,postgres}/database.json` 和 `:backend:migrationEvidenceTest --tests '*CashEntryRepositoryMigrationTest'` 显式运行：SQLite／PostgreSQL 各 39 方法，0 失败／错误／跳过，分别成功 7s／8s；原测试文件未修改。`entry-historical-*-results.json`、两库固定 XML 及 `historical-verification.json` 核同一 39 方法、五份当前源码与原历史测试摘要，共 78 方法另计。首轮历史 PG 未读其独立用户配置而报 `storage.connect`，失败日志／XML 保留；执行器补读原 `postgres.env` 中已有的 `FT_KOTLIN_TEST_DB_USER` 后运行同一已准备库，不修改测试／生产代码或自动探测／启动数据库。当前没有 schema 或升级变化，统计只线性扫描已有前后事实，完整默认回归包含适用性能用例；未新增候选搜索或枚举。
   - Cross-platform Impact Check：仅 backend 统计与内部条目阶段返回值变化，共享 DTO／消费者、Web／Native 页面、交互及文本均未修改，不额外编译未变化的原生应用或重复页面冒烟；公开 HTTP 的真实共享 DTO 消费已验证。本片不宣称新本地入口或完整结果页端到端通过，端侧文件／跳过／批内重复及 Graph 仍待组合，最终整体 Web QA 保持未完成。
7. 发布：已核 README、docs、参考入口、词表、主规格／delta、接口／命令示例、配置与迁移；仅 design、tasks、导入／总览功能地图及 apply 指引需同组更新，其余没有新领域术语、wire、配置、迁移或用户操作，无需修改。strict 27/27、doctor healthy、五文档的 138 个本地目标／33 个锚点及 diff 检查通过，最终独立源码／默认矩阵／两库历史证据及文档门通过，无遗留 finding，按授权提交推送同分支。保留原账户映射最终增量及撤回额外来源／映射证明的边界，本片恢复既有实际统计要求，无新增通用产品规则。整体 31/71，不归档、不建 PR。代码可独立回退，旧收据缺省字段仍不推算，已保存首收据不改写。

### 原工作区账户准备边界（6.4／6.6）

1. 思考：比较基线 `212f4f1f`。原账户客户端读取后来凭据，服务端新建账户缺少调用方原工作区守卫；仅在 HTTP 前后核客户端状态不能防止中途切换后向新工作区新建账户。本片落实既定工作区隔离，账户映射仍只提交最终增量。
2. 计划：沿既有工作区预期模式，为账户 CRUD／币种扩充增加可选的 `workspace_id`，路由及真实服务事务核对；共享内部账户传输固定原凭据及工作区，复用现有账户校验、真实删除资格和永久收据，不引入额外账户证明。旧未带预期调用保留。
3. 任务拆分：实际双库 HTTP 先复现中间切换新建落错工作区，再覆盖读取／新建／更新／删除／币种与原键收据及客户端固定凭据、编码、取消和拒绝清理；完成独立复核、受影响跨端测试及双库回归。
4. 构建：账户路由严格读取单个非空预期，保留旧 view 和未知查询行为；服务在原事务锁／权限／收据重放前核同一工作区，读取与币种初次授权、逐项提交均保留守卫。共享内部账户客户端绑定原凭据与工作区，首挂起前冻结正文，不清后来登录令牌；public 旧调用保留。原键及 ID 读取通用账户收据，不套写集专用类型，删除资格仍从真实账户详情读取。OpenAPI 六个账户操作同步声明可选守卫。
5. 审查：独立 5a 已按产品编码规范 §7 核范围、工程、安全、异常／输入、事务、恢复和最终 diff；代码库防劣化调度的独立契约 5b 已实际执行，无阻断或遗留 finding。预期工作区在原锁、权限和重放前核对，币种每项继续核同一预期，账户详情保留真实能力。审查指出孤立代理字符应运行时构造及 401 应直接断言原 rejectedToken，两项均已修正并在三个目标实跑。账户列表新增 400／403 的元数据已对齐并复跑文档合同与打包；无 UI 组件、布局、主题或文案变化，UI 专项不适用。
6. 测试与 QA：2026-11-16，比较基线／验证 HEAD `212f4f1f` 加当前工作树。证据根 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1116/import-account-scope`。`red.log`／`red-xml` 固定双库中途切换后账户实际写入后来工作区、返回 201 的失败；`client-red.log`／固定 XML 记录原入口采用 later origin 的真实断言失败，不以编译错误代替失败回归。定向扩展六方法在双库实际运行共 12 项通过；客户端八方法 Host 初步通过。
   - `final-matrix-command.json`／`final-matrix.log` 完整矩阵成功 6m58s，209 任务：58 执行／151 up-to-date。后端 199 类／1357 方法、Host 103 类／981 方法、JS 103 类／939 方法、Wasm 105 类／945 方法，共 4222 方法全部通过，0 失败／错误／跳过。六个新 HTTP 方法在 SQLite／显式真实 `_test` PostgreSQL 各执行一次，八个客户端方法在三个目标各执行一次；backend ktlint、detektMain／detektTest、installDist 及 iOS arm64 生产／Simulator arm64 测试编译均实际执行成功。共享矩阵沿既有 self-contained 入口排除依赖独立响应夹具的 Kotlin 响应／持仓集成类，不把它们计为本轮通过。
   - 完整矩阵后仅 OpenAPI 的账户列表补入已验证实际返回的 400／403 说明，Kotlin 八份源码未变；`metadata-command.json`／`metadata.log` 对当前资源实际重跑 18 项 OpenAPI 合同及 installDist，成功 9s。`verify.py`、`final-results.json` 固定九份当前源码／资源摘要、完整矩阵时原资源及各目标非空新鲜 XML，并核资源唯一差异、每个新增方法、实际任务和构建终态。
   - 未修改的历史 `AccountStorageBoundaryTest` 另在独立 SQLite 复制夹具和同一显式 PostgreSQL 测试库实跑，各四方法全绿，分别成功 6s／6s，覆盖旧调用、原键未知结果、原权限锁等待和协议拒绝。初轮 SQLite 通用夹具把 synthetic-admin 绑定 UUID 用户，而旧测试固定更新 admin，实际两项失败保留于 `historical-sqlite.log`／failed XML，不计绿；手工按该历史约定准备独立 SQLite 复制及 PostgreSQL 临时合成身份，运行后恢复原 PostgreSQL 夹具，并仅清理初轮 SQLite 遗留的确切测试工作区、恢复原合成会话。`historical-*-ready-command.json`、两库固定 XML、原测试摘要及夹具恢复记录可复核，没有修改生产权限或测试断言，也没有由测试探测／启动数据库。
   - Cross-platform Impact Check：共享传输覆盖 Web／Android／iOS，Host 与 Chrome 中 JS／Wasm 实跑及两项 iOS 编译如上。页面、路由、交互、用户文案和 Graph 未变，本片不额外重复页面冒烟，不将 Chrome 单元测试称为本地生命周期端到端验收。无 schema、依赖、财务规则或候选性能变化；真实事务拒绝／重放与权限锁等待已覆盖适用安全边界。
7. 发布：已按最终实现核对 README、docs、参考入口、词表、主规格／delta、接口／命令示例、配置与迁移；同组更新 design、tasks、导入／账户／总览功能地图、OpenAPI 及 apply 指引，其余无新术语、schema、依赖、配置或用户操作，无需修改。strict 27/27、doctor healthy、六份文档的 143 个本地目标／34 个锚点及 diff 检查通过；最终独立源码／证据／文档门通过，无遗留 finding，按授权提交推送同分支。保持已撤回额外来源／账户映射证明及原最终映射保存规则，本片为既定隔离边界的实现，无新用户产品取舍，无需新增通用规范。整体 31/71，不宣称 owner、本地生命周期、Graph、完整结果或整条 change 完成；不归档、不建 PR。回退代码不会改写已保存账户或永久收据；旧调用继续按当前工作区执行，保留原请求键核实未知结果。

### 本地批次结果保存（6.4／6.6／10）

1. 思考：比较基线 `1ca98a22`。当前完整批次清理删除原文件／review／请求，服务端不能重建未发送的文件失败、跳过及批内重复，结果恢复尚缺持久依据。本片落实既有结果与恢复合同，不新增服务器证明或账户映射字段。
2. 计划：从完整生产计划冻结最小未提交分量，随原请求保存并在原键／基线重派中保留；实际收据保存后、交付／刷新／清理前，在同一加密库归档原收据及该分量。按原范围、收据、批次／实际键读回，实际业务统计不从预览或后续快照推算。沿原互斥／关闭／取消和物理擦除，不新建密钥或事实缓存生命周期。
3. 任务拆分：先以真实本地任务和 SQL 库复现清理／重开后结果缺失，再覆盖原键幂等、不同收据／批次冲突、归档失败保已知成功和原材料、同结论重派保原分量、跨范围及关闭／擦除。独立复核后执行受影响 Host／JS／Wasm 及 iOS 编译和文档检查。
4. 构建：`LocalCashImportResultInput` 仅冻结端侧未发送行、最小文件结果及已知关系失败；服务端已表示的 duplicate 操作代表不补计，批内别名仍按行补计。文件计数保留合法 null，展示名保留原字符串域，不保留失败文件正文、密码、原诊断或完整计划。结果表与临时批次表在同一加密物理库，收据／批次／实际尝试键均限制原范围且同值幂等；冲突拒绝，不覆盖旧结果，已归档批次编号不可复用。`recordReceipt → archive → callback → refresh → complete`，完整快照及归档未就绪不能删材料。派生展示与原收据分开，实际数、身份、版本、待处理、提交事实保持；旧无分量命令不补造数据。别名及失败父项以明确操作位置连接实际身份，归档及读取均核位置范围，结果展开每次调用只解一次原收据，避免按遗漏行重复解析大收据。
5. 审查：独立 5a 及 `$codebase-degradation-review` 调度的独立契约 5b 已执行，覆盖原范围、最小字段、幂等、原收据／展示分离、统计、身份连接、恢复、资源围栏及复杂度。发现的 P2（合法失败文件行数 null 被拒绝）由真实混合文件全链复现并修复；操作位置关联及越界门已补齐，空白展示名只作为原持久构造域一致性保留，不声称真实解析入口可达。独立交叉核对生产顺序：冻结文件／文档行顺序经 SourceBatch 原 rows.keys 输出、非 alias 根行追加及后续保序 map 进入写集；同完整结论固定存活根行、row_refs 与 occurrence_key，代表切换、移除或分组合并会改变结论，因此重派保持相同操作位置。没有用排序后的指纹直接假定传输顺序，也不允许注入计算器伪造计划／写集不一致。解码开销问题已关闭，当前无遗留 finding；源码／证据／文档最终门已通过。没有 UI 修改，不触发 UI 复用或设计审查。
6. 测试与 QA：证据根 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1116/local-import-result`，当前执行基线 HEAD `1ca98a22`，2026-11-16（Asia/Shanghai）。`red.log` 实际运行原任务测试 1 项失败，断言原提交缺 `resultInput`；`nullable-file-red-xml` 保留真实混合文件的 null 计数失败，修复后 `nullable-green-xml` 127 项全绿。最终冻结 14 份 Kotlin 源码，`final-matrix` 于 12:57–12:59 实跑 2m46s，187 个任务（33 executed／154 up-to-date）；实际命令见 `final-matrix-command.json`：`:shared:testAndroidHostTest :shared:jsBrowserTest :shared:wasmJsBrowserTest :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosSimulatorArm64`，沿既有 quality／自包含测试 init script、单 worker、in-process 编译、3 GiB 堆和禁用配置／构建缓存。Host 105 类／991 项，Chrome JS 104 类／944 项，Chrome Wasm 106 类／950 项，共 2,885 项，均无失败、错误或跳过。官方 OPFS 驱动实跑归档加密、批次清理、换快照、关库重开及密钥／物理库擦除；真实 SQLite 覆盖三范围隔离、异批次收据冲突、重派输入不变、实际尝试键、归档损坏拒绝及 ack 丢失恢复。
   - Cross-platform Impact Check：共享生产链覆盖 Web／Android／iOS，Chrome JS／Wasm 实跑与 iOS 编译如上；页面、交互、路由、用户文案和 Graph 未变，不把浏览器存储单测称为应用导入冒烟，不追加原生设备 QA。沿既有 Host 自包含集合排除 `Kotlin*ResponseIntegrationTest` 与 `KotlinPortfolioLiveIntegrationTest`，不将其计通过；本片未改服务器事实持久化或双后端 Application Service，新增存储仅为设计指定的端侧 SQLite／OPFS，未运行 PostgreSQL 新矩阵，不以该证据替代整体双库验收。无依赖、密钥、服务端 schema 或财务规则变化。strict 27/27、doctor healthy、5 份文档 139 个本地目标／33 个锚点及 `git diff --check` 通过；当前源码与冻结测试清单一致。
7. 发布：最终独立门已通过，按授权提交推送原分支。已核对 README、docs、references、词表、主规格／delta、接口／命令、配置／迁移及功能地图；同组更新 design、tasks、导入／总览地图和 apply 指引，其他无新术语、公共 API、配置或用户操作，无需修改。用户撤回额外证明及保留原映射保存规则仍有效；本片落实既定结果持久化，无新的产品取舍，无需新增通用规范。回退实现不会改写服务端事实／收据，未提交或未知结果仍保留原命令核实，显式擦除按既定范围执行。整体 31/71；本地生命周期仓储、Graph、完整结果页面、离线写入限制 和最终整链 QA 仍未完成，不归档、不建 PR。

### 导入账户与原会话组合（6.4／10）

1. 思考：基线 `de3e827c`。账户传输已有原工作区守卫，本地任务尚缺与原缓存 owner／发表代次一致的账户及刷新入口；旧生命周期仓储仍选当前 API 凭据，不能直接用于新的本地任务。
2. 计划：原导入句柄组合本地账户列表、固定范围联网账户命令及原范围收据；刷新器与原句柄从同次 active／generation 捕获，复用已有成功后刷新与诊断规则，不读取后来登录。保留真实删除资格、独立币种项及最终映射保存规则，不添加证明字段。
3. 任务拆分：先复现旧导入账户请求遗漏原工作区，再覆盖原凭据、输入冻结、换登录／关闭、实际 401 清理、403 不擦除、成功后刷新失败保成功及后续原范围刷新恢复；独立 5a／5b 后执行共享层回归与相称编译。
4. 构建：原 `LedgerSessionImports`／owner 已组合现金／借贷账户目录、详情、创建、修改、删除、币种批次及账户收据；目录保持 inactive 和原币种语义，服务端资格仍以实际详情为准。可变列表、嵌套币种和 JSON patch 在守卫首挂起前冻结，HTTP 不持资源锁，原工作区及凭据固定。`SessionCacheRepository` 将既有成功后刷新抽为 `refreshFor`，原捕获入口与导入句柄复用同次 active／generation 及 refreshEpoch；刷新失败保留成功／整份部分成功结果与安全诊断并关闭陈旧事实读取，旧刷新不得重新开放较新失败代次。令牌已换时原守卫在 HTTP 前取消；已发出的真实 401 沿原拒绝清理且不清后来令牌，403／网络／取消不擦除。账户永久收据保持通用 kind；合成可选版本下限已透传，实际账户收据当前不带 `ledger_seq`，不宣称实际合同新增该字段。
5. 审查：独立 5a 与 `$codebase-degradation-review` 调度的独立 `source_contract` 5b 均无阻断 finding，覆盖原 owner／工作区／凭据、输入冻结、同次捕获、刷新与发表竞态、成功后故障、独立币种项及原拒绝清理。已采纳非阻断建议，补充部分币种成功／409 拒绝后刷新故障及原收据恢复，确认成功项不重发。仅测试和说明增量的窄复核无 finding，最终源码／证据／文档门已通过；无 UI 修改，不触发 UI 复用或设计审查。
6. 测试与 QA：证据根 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1116/import-account-owner`，执行基线 HEAD `de3e827c`，2026-11-16（Asia/Shanghai）。`red-xml` 实际运行 1 项失败，旧生命周期账户请求缺 `workspace_id`；`red-source.kt` 与当次记录 SHA 完全相符。`focused-green-xml` 125 项通过。最终 `partial-currency-matrix` 冻结 3 份生产 Kotlin 和 1 份测试源码，于 13:22–13:23 实跑 1m40s，175 个任务（25 executed／150 up-to-date）；实际命令见 `partial-currency-matrix-command.json`：`:shared:testAndroidHostTest :shared:jsBrowserTest :shared:wasmJsBrowserTest :shared:compileTestKotlinIosSimulatorArm64`，沿既有 quality／自包含 init script、单 worker、in-process 编译及禁用配置／构建缓存。Host 105 类／1,001 项、Chrome JS 104 类／954 项、Chrome Wasm 106 类／960 项，共 2,915 项，均无失败、错误或跳过，含本片 10 项账户组合用例。生产源码与 13:18–13:20 的 `final-matrix` 一致；该矩阵 187 任务（31 executed／156 up-to-date）、2m45s，另实跑 `:shared:compileKotlinIosArm64` 及 Simulator 测试编译，新增部分币种测试后已重编 Simulator 测试；iOS 编译不称设备 QA。可选收据下限测试使用合成带版本响应，只证明兼容字段透传。
   - Cross-platform Impact Check：共享原会话账户入口影响 Web／Android／iOS，覆盖如上。页面、路由、交互、文案、Graph 未变，不将 Chrome 单测称为应用导入冒烟，不追加原生设备 QA。沿既有 Host 自包含集合排除 `Kotlin*ResponseIntegrationTest` 与 `KotlinPortfolioLiveIntegrationTest`，不将其计通过。本片未改服务器服务、持久化、schema、依赖、密钥或财务规则，既有 SQLite／PostgreSQL 账户事务合同保留，不以客户端模拟 HTTP 替代整体双库验收。strict 27/27、doctor healthy、5 份文档 140 个本地目标／33 个锚点及 `git diff --check` 通过；当前 4 份 Kotlin 源码与冻结矩阵一致。
7. 发布：最终独立门已通过，按授权提交推送原分支。已核对 README、docs、references、词表、主规格／delta、接口／命令、配置／迁移及功能地图，同组更新 design、tasks、导入／总览地图与 apply 指引；无新术语、公共 API、配置、迁移或用户操作，其余无需修改。本片为既定原会话隔离合同的组合，无新用户产品取舍，无需新增通用规范；回退客户端不会撤销已保存账户或永久收据，未知结果仍沿原键核实。完整本地生命周期／Graph、离线写入限制 及整体 QA 仍未完成，整体 31/71，不归档、不建 PR。


### 本地生命周期与应用入口（6.4／6.5／6.6／10）

1. 思考：当前 Graph 使用旧 Ktor 会话仓储，本地解析、计算、原键恢复及结果归档尚未被完整生命周期接口组合；继续整体 A 类 change，不增加来源／账户映射证明或更改财务规则。
2. 计划：仓储按原缓存 owner 绑定唯一批次协调器与运行期计划；全部操作首挂起前固定输入，跨会话操作由原句柄拒绝。CSV／XLSX 本地解析，PDF／XLS 复用原 parse-only；预览消费真实共享计算，确认先冻结再恢复原提交，结果从永久归档读取，账户使用既有原范围端口。成功后的次级故障沿已知成功边界呈现，不重复提交。
3. 任务拆分：先运行旧应用仓储 CSV 上传的失败回归，再实现生命周期、完整只读视图、确认／恢复、原范围账户与结果读取，切换 Graph 后验证实际应用流程；不能以模拟传输或内部协调器单测替代整链证据。
4. 构建：本地生命周期仓储与 Graph 已接真实本地解析、计划、确认、原键恢复、永久结果与账户端口。新登录及工作区变更沿原响应凭据先完成本地 owner 再交付 Session；同值新发表也以确切 Session 实例更换仓储寿命。已完成批次拒绝取消，首次文件读取前等待原批次恢复关联；零材料批次可恢复为空准备并取消。文件读取、联网解析与存储故障分别保留原语义。
   - 账户选择读取原可读本地事实、币种与角色，真实编辑及账户写入保留原 API。真实浏览器首轮复现断网选择调用 `/accounts/by-id`，修复后已走通断网解析／选择／核对及联网真实写集保存，实际快照新增一条合成流水。退出在清理准备前固定原令牌与地址；HTTP 后清理等待取消时，以不可取消收尾关闭或保留确切原资源，不再扫描后来 owner。真实 SQLite／owner／生命周期／VM 已复现并修复旧请求退休后页面仍保留提交围栏：满足既定请求与归档条件后清旧命令，严格准备原会话事实，再交付实际可编辑上下文；未知／成功不得混入。首次严格刷新失败保留原原因与批次，重试只恢复原批次；程序异常停止。确认前重开的账户准备在运行期与建议快照皆缺时也改读本地选择快照；真实 VM 与本地生命周期失败回归及定向 Host 已通过。完整结果页仍须补齐。
5. 审查：独立 5a 及实际独立契约／UI 复用 5b 已核原 owner、首次身份、确切 Session 发表、display 收据、异常来源、账户选择与真实编辑边界，以及退出固定凭据、原目录代次和资源所有权。登录／注册 401 不误清原令牌；旧退出不清后来登录；取消仍释放或保留原资源。非法服务地址的旧退出回归恢复 finally 条件清理，显式匿名快照不采用后来令牌。退休恢复增量经独立 5a 及实际子上下文契约 5b 复核：仅当前 epoch 成功可开放事实，离线已读不联网，读取失败与重试保原批次，旧命令不复活，错批次与迟到不发布。网络、权限、取消与程序故障原因链均保持，无新增 finding；评审者未运行测试，不把静态结论替代实际 QA。不增加来源／映射证明字段。
6. 测试与 QA：比较基线 `105ed60f` 加当前工作树，2026-11-16，证据根为仓库外 `offline-ledger-1116/local-import-lifecycle/`。`red-login-and-completed-cancel` 两项、`red-first-file-association` 一项、`red-late-logout-and-empty-batch` 三项、`red-logout-token-before-prepare` 一项，以及 `red-local-selection-and-cancelled-logout` 两项均实际复现失败。最后两项修复后定向 Host 五类 242 方法全绿。
   - `verified-current-platforms-command.json`／日志及 exit JSON 固定该轮源码、实际命令和成功终态，16:11–16:13 实跑 2m12s，187 任务（29 executed／158 up-to-date）。Host 106 类／1041 方法、Chrome JS 105 类／994 方法、Chrome Wasm 107 类／1000 方法，共 3035 方法，0 失败、错误、跳过；三个测试任务均显式 `--rerun`。iOS arm64 生产及 Simulator arm64 测试编译通过；完整 XML 与计数固定在 `verified-current-platforms-xml/`。沿既有自包含入口排除依赖外部响应／持仓夹具的类，不计为通过。前一 `current-shared-matrix` 在各目标各有一项非法地址退出回归失败，完整保留，不计绿；修复后定向及本轮完整矩阵已覆盖。
   - 真实后端浏览器 `browser-actual-confirm` 已实际验证新登录、断网 CSV／账户选择／核对、联网一次写集 POST 200 与真实快照一条流水；该轮在预期原键 404 的诊断断言处停止，不能称整体通过。测试只豁免确切的原键不存在响应，其他控制台／网络错误仍断言；`browser-final` 在真实 Chrome 157、1440×1100、`http://127.0.0.1:18391/` 实跑通过（1 项，0 跳过／重试），完成断网刷新后原结果与返回入口验证，无第二次 POST；三份截图及永久真实收据附件保留在同名目录。该结果对应补齐确认前重开账户准备之前的源码，后续只补跑受影响分支。QA-only 合成结果演示不替代此生产 Graph 验证。页面弹出层关闭后存在已知 CMP-10623 语义镜像滞留，交互沿实测 canvas 几何并核实际协议／快照与截图，不调用隐藏应用函数。
   - 确认前重开缺运行期快照的分支由真实 `ImportAccountViewModel` 与本地生命周期组合先复现失败：`red-reopened-account-network-xml` 保存失败；一行预检补读改用 `selectionAccount` 后，Host 三类 197 方法全绿。`reopened-account-platforms` 中 Host 与两项 iOS 编译成功，但浏览器类过滤未执行用例，该轮整体失败且不计浏览器通过；随后不带过滤的 `reopened-account-browser-full` 实跑 43s，JS 105 类／995 方法、Wasm 107 类／1001 方法均 0 失败、错误、跳过，含新回归。最后源码只改变该预检补读及对应测试，其他生产源码与前述完整矩阵摘要一致。独立 5a／契约窄审无 finding，真实浏览器 `browser-reopened-preparation` 实跑通过（1 项，0 跳过／重试）：离线预览后 reload，恢复原选择，账户准备及再次预览不发 HTTP；随后一次写集保存，快照真实新增一条 -20 CNY 流水，断网重开结果仍保留。当前生产 Wasm 与源码／资源的 freshness 指纹一致，四份截图已检查，恢复后的账户、商户、金额与结果统计一致；测试实际 21.1s，含新生产构建总计约 3m。
   - 退休恢复真实红测 `red-page-rebase-sqlite` 在旧请求已删除、原文件和选择仍在时，因页面提交围栏未解除失败；原 XML 保留。接入实际 `SessionCacheRepository` 后，`browser-rebase` 普通流通过，冲突流在真实 409 后复现事实读取资格未重新开放，不能记为浏览器通过。严格刷新修复后的 `rebase-strict-boundaries-host` 四类 241 方法全绿，固定 XML 与计数在同名前缀目录；实际 SQLite 同时覆盖正常返回核对及首轮刷新 I/O 失败后仅显式恢复原批次，另覆盖错批次、迟到与程序异常停止。当前源码的 `rebase-strict-final-platforms` 2m16s 成功，187 任务（29 executed／158 up-to-date），Host 106 类／1047 方法、Chrome JS 105 类／999 方法、Chrome Wasm 107 类／1005 方法，共 3051 方法零失败／错误／跳过；三个运行目标均 `--rerun`，iOS arm64 生产及 Simulator arm64 测试编译通过。源码摘要与新鲜 XML 已核并固定在同名前缀的 command、exit JSON 与 `-xml/counts.json`。真实 Chrome 157／1440×1100／18391 的 `browser-rebase-strict` 普通流通过；冲突流已实际返回账户步骤，但测试误等文件步骤按钮而失败，保留 trace／截图，不记整轮通过。按实际既定步骤修正测试后，`browser-rebase-accounts` 单独实跑冲突分支 22.9s（含相同生产构建核验总计 30.4s）通过，无跳过或重试：先真实账户改名和 POST 409，保留原文件／选择并同次回到账户核对；重新选择后预览采用新名称，显式确认以原批次 `:attempt:1` 键保存一次，真实快照仅新增一条 -20 CNY 流水，断网重开保留结果。两个分支均无旧 sessions／plans 请求，除明确的原键 404 与冲突 409 外无控制台／网络异常；三个关键冲突／核对／离线结果截图已检查，该轮源码／资源与生产 Wasm 指纹一致，`browser-rebase-strict-freshness.json` 为 stale=false。
   - 本片未改服务端 Application Service、DML 或 schema，沿既有 SQLite／显式 PostgreSQL 合同，不把客户端模拟 HTTP 当作双库证据。Cross-platform Impact Check：共享仓储、Graph、登录／工作区及任务生命周期同时影响 Web、Android、iOS；覆盖上述三个运行目标及相称 iOS 编译，默认不追加原生设备 QA。整体端侧浏览切换、离线写入限制、完整结果和最终整链回归仍未完成。
7. 发布：本片本地生命周期／Graph 接线、原批次结果恢复与当前结果状态专项设计门已完成；完整逐项结果另在后续任务保留。已核对 README、docs、references、词表、主规格／delta、接口／命令、配置／迁移；同组更新 design、tasks、导入／认证／共享／总览功能地图及 apply 指引，其余没有新术语、wire、配置或迁移，无需修改。strict 27/27、doctor healthy、7 份文档 206 个本地链接目标／37 个锚点及 diff 检查通过。用户撤回额外来源／账户映射证明、原映射保存规则继续有效；本片是既定隔离与恢复行为的实现修复，无新产品取舍，无需新增通用规范。用户已采用结果页呈现，最新同源码演示及真实三流程均通过，最终独立证据门已通过，按同分支授权提交推送；整体仍未完成，不归档、不建 PR。

### 本地已保存结果的收尾恢复（6.6／10）

1. 思考：应用入口仍须由旧服务端生命周期切换到本地任务；真实任务已区分权威收据与结果归档／刷新故障，但页面仅从交付回调接收收据，缺少回调前失败及回调后失败的完整状态。按整体 A 类 change 的既定成功／未知／未提交合同补齐，不引入来源或账户映射证明，不改变原保存规则。
2. 计划：本地域完成故障携原批次、已知成功收据与原异常；页面显式接纳已保存结果并保留原批次恢复资格，不提前升级恢复指针、不补调失败回调。程序异常停止并保留诊断，可恢复故障只显式恢复原批次；同一收据的后续交付完成收尾。信息架构沿现有结果标题、实际统计、任务反馈与核实按钮；没有需删除的冗余区域，不新增页面／卡片／原型或 wire。
3. 任务拆分：先用页面真实状态转换复现归档前、交付中及交付后失败；覆盖恢复同收据、错批次／错收据、取消、迟到与程序异常；再实现最小边界，完成独立 5a／5b、UI 审查及受影响验证。之后继续完整本地生命周期和 Graph，不能把本片当作整条接线完成。
4. 构建：已完成原批次及已知成功收据的完成故障边界、同收据展示补齐及原任务恢复；失败回调不重复保存，程序异常保诊断并停止，可恢复故障只恢复原批次。
5. 审查：独立工程、契约及 UI 复用复核已指出并关闭恢复时捕获错批次、raw／display 收据比较及后续 raw 故障丢失完整展示的问题。用户已于 2026-11-16 明确采用 `http://127.0.0.1:18389/` 演示方向：保留已保存统计，收尾中断提示“账本已保存，结果整理尚未完成”，只提供继续原批次的恢复入口；当前源码对应的最终 Hallmark audit 与独立设计复核均无 finding（0 critical／0 major／0 minor；P5 H4 E4 S5 R5 V4），受影响生产 Graph 三流程交互 QA 已通过。
6. 测试与 QA：新 VM 回归实际先红后绿，当前 VM 94 方法已在上述 `rebase-strict-final-platforms` 完整 Host／JS／Wasm 矩阵执行，无失败、错误或跳过；相称 iOS 编译通过。当前 `completion-final-current` 生产源码加仓库外合成仓储的 QA-only 演示构建 1m35s 成功，62 任务（14 executed／48 up-to-date）；28 份 Kotlin、35 资源、198 冻结文件及实际 HTTP 返回的 9 份关键资源摘要经独立核对均一致，`stale=false`。Chrome 157／`http://127.0.0.1:18392/` 在 1440／768／414／390／375／320×900 实测 Tab＋Enter 恢复、忙碌保护和完成转换，六项统计 2／0／1／0／0／0 全程保留，完成后提示及恢复入口消失；程序异常宽／窄均无普通恢复。窄屏无裁切或按钮折行，无 pageerror；截图及 `browser.json／freshness.json／review-manifest.json` 在仓库外 `import-completion-state/result-final-review-current/`。原 18389 演示保留为用户确认依据，其旧摘要不作为当前最终 QA。合成仓储演示不证明生产 Graph 导入或完整逐项页面。Material 3 States、Expressive、Buttons Overview／Specs 正文已用实际浏览器核对，沿现有 Text／TextButton 与 token；Hallmark／独立设计门已通过本片结果状态，真实 Graph 的收尾刷新失败及原批次恢复冒烟已取得上述通过终态。真实生产 Graph 的 `browser-completion-current` 三流程均通过（普通 27.182s、冲突 29.512s、收尾恢复 28.808s，含新生产 Wasm 构建总计 4m01s，零跳过／重试／flaky）。Chrome 157／1440×1100／18391 使用真实本机 Kotlin 后端；第三流在真实 POST 200 后仅拦截原账本 snapshot 返回一次 503，页面保留已保存 1 笔及其余零统计，显示已确认提示与原批次入口；继续整理后真实快照只有一笔 -20 CNY，提示消失，全程仅一次写集 POST。其后关闭浏览器进程、同 profile 新进程离线启动，原账目／结果仍在，冷启动仅两次 auth/session 探测，无额外 POST 或 pageerror。普通／冲突流继续覆盖本地在线目录、离线核对、确认前重开与原键重派。6 份进程 trace 的冻结 TS 与当前逐字相同，生产构建指纹 `stale=false`；已检查实际中断／恢复截图，原键 404、明确冲突 409 与注入的确切 snapshot 503 之外无异常。生产后端沿已安装原写集服务，未把未启用的内部规范分类／删除称为浏览器通过。 无服务端 DML／schema 变化，双后端不适用。Cross-platform Impact Check：Web、Android、iOS 共用状态与任务边界，均受影响；原生设备 QA 不在默认范围。
7. 发布：用户已确认本片结果页呈现；其“已保存统计保留、只恢复原批次”落实既有写入安全原则，适用已确认提交后的收尾故障，提交未知与程序异常仍按原边界处理，无需新增通用规范。本片真实 Graph 冒烟、专项设计及最终独立文档／证据门已通过；交付基线 b0bffd25 加当前范围，41 文件精确核对，28 份 Kotlin（20 生产／8 测试）保持原受测摘要，318 份 XML 共 3075 方法全零，领域查询另沿同源码的原领域运行证据。strict 27/27、doctor 正常、8 文档 256 个本地目标／41 个锚点及 diff 检查通过；按同分支授权提交推送；完整逐项结果、离线写入限制 和在线完整切换仍未完成，不归档、不建 PR。

### 本地导入结果定位（6.4／6.6／10）

1. 思考：永久明细已归档，但本地生命周期结果页仍固定返回空定位与版本 `0`；复用既有实际结果与本地条目合同，属于整体 A 类 change 的未完成接线，不新增公共协议或财务语义。
2. 计划：在同一已提交快照中复用账本成员索引，按当前页的实际流水生成稳定条目定位；读取受原 owner、发表代次与刷新 epoch 保护。事实暂不可用时保永久明细、移除定位，不读取服务端投影或重新开放资格。
3. 任务拆分：先复现 SQLite 结果页定位缺失和页面遇到不可用事实丢失结果；覆盖精确大整数、分页范围、重开、换版／删除、未知成员、不可读及刷新中途失效。保留页面版本下限，补独立 5a／5b 与跨端验证。
4. 构建：已实现同快照的完整成员索引及当前页定位，账本页与结果读取复用派生 capture；原会话关闭事实读取资格或跨刷新 epoch 时仅移除定位，不联网或重开资格。页面保留版本下限，临时不可用值 `0` 不丢明细或降低下限，新任务重置。永久归档正文不改变。底层事实丢失会使原恢复身份失效，继续按现有守卫拒绝读取，不绕过验证。
5. 审查：独立 5a 及代码库防劣化调度的实际独立契约 5b 已核同快照、非主成员／隐藏全退成员、原作用域及 epoch、错误保真、版本下限、分页范围和复杂度，无生产 finding；后续窄核已更正无快照的身份前提，确认生产守卫未放宽，测试按实际身份资格失败关闭。本片无页面布局、文案或组件变化，UI 复用专项无新增范围，整体 UI 门保持未完成。
6. 测试与 QA：基线 `105ed60f` 加本片工作树，2026-11-16，证据根为仓库外 `offline-ledger-1116/local-import-lifecycle/`。`result-locations-red` 三方法均真实失败，分别为实际 SQLite 结果定位固定 `0`、无定位页未保新明细、`0` 被误作版本回退；原 XML 已冻结。初步修复后的 `result-locations-focused` 三类 147 方法通过。`result-locations-boundaries` 的新增测试错误假设删去事实后仍有有效离线身份，实际 `ledger_cache_identity_missing` 属既定守卫；冻结失败记录后，仅修正测试与 design 的身份前提，不修改生产守卫。真实跨 epoch 读取及领域合并成员断言已通过该轮。修正后的 `result-locations-boundaries-final` 25s 成功，共享三类 149 方法、领域查询 11 方法均无失败／错误／跳过。
   - 完整 `result-locations-platforms` 4m20s 成功，206 任务（42 executed／164 up-to-date），实际命令与 25 份 Kotlin 源码摘要见同名 command JSON。领域 JVM 198 类／887 方法、JS Node 168／839、Wasm Node 168／839；共享 Host 106／1052、ChromeHeadless 157 的 JS 105／1001、Wasm 107／1007，共 5625 方法全绿，六测试任务均 `--rerun`；iOS arm64 生产及 Simulator arm64 测试编译实际通过。852 份 XML 时间均在执行窗口，源码摘要重算一致，固定报告与计数在 `result-locations-platforms-xml/`。沿既有 self-contained init 排除依赖外部响应／持仓夹具的类，不将排除项算通过。
   - 当前结果页布局尚未调用逐项结果及定位，新增读接口没有改变已有浏览器导入主流程；前述真实生产浏览器证据只覆盖其对应源码，不冒称本轮逐项结果 UI／跳转冒烟通过。客户端结果派生不修改服务端 Application Service、schema 或 DML，双后端合同沿已有服务证据；端侧 SQLite 与共享浏览器运行不能冒称 PostgreSQL 验证。Cross-platform Impact Check：Web、Android、iOS 共用结果仓储与状态，均受影响；完整结果 UI 及其 Web 冒烟另验。
7. 发布：本片源码与相称验证已完成，最终独立证据／文档核对已通过（25 份 Kotlin 摘要、852 份新鲜 XML、当前浏览器 TS／trace 与文档一致，无新增 finding）；已同组更新总览、导入／共享功能地图、apply 指引及 design。README、references、词表、主规格／delta、接口／命令、配置／迁移已核，无新术语、wire、schema 或用户动作，其余无需修改。实现落实既有结果定位与隔离合同，没有新用户产品取舍，无需新增通用规范。原结果页呈现方向及该状态最终设计门已通过，完整逐项页面仍未完成，整体保持未完成，不归档、不建 PR。

### 完整逐项结果归档（6.4／6.6／10）

1. 思考：基线 `653f9b6f`。已归档原收据与端侧未提交分量，逐行／逐关系真实结果尚只存在永久服务端结果表；不能用计划或汇总补造离线结果页。
2. 计划：复用现有带原工作区预期的永久结果接口，分页读取并核真实身份与完整覆盖，再与原收据保存同一加密记录；先保存成功收据，补读失败保材料，恢复不重发成功命令。不新增 wire、证明或数据库表。
3. 任务拆分：先复现真实任务清理后缺逐项结果，再覆盖原凭据、分页、错误／取消、完整性与身份拒绝、同值幂等及旧记录补齐、SQLite／OPFS 重开。执行独立 5a／5b、共享跨端测试和相称编译。
4. 构建：原收据先保存，随后沿固定凭据／工作区分页补读永久逐项结果；按服务端定义的位置核真实身份、动作及完整覆盖，游标严格前进且矛盾末页拒绝。当前服务端投影引用不归档。逐项结果同原收据／未提交分量存同一加密记录；保留原提交的旧记录仅在基础同值且缺明细时 CAS 补齐，已有完整结果不替换。补读／归档失败保成功及材料，恢复不重复写入；已完整归档不再补读，真实 401 沿既有原令牌失效清理。旧已清理批次仅可辨缺失，不宣称自动补页。
5. 审查：独立 5a 及代码库防劣化调度的实际独立契约 5b 均无阻断 finding，已核范围、失败保真、原范围／身份、分页／完整覆盖、同值补齐与复杂度。兼容边界及新增矛盾末页拒绝、冻结／损坏测试均已窄复核无 finding；新增真实 HTTP 验证不冒称客户端与提交全链。无 UI 变化，UI 复用／设计专项不适用；最终源码／证据／文档门已通过。
6. 测试与 QA：2026-11-16（Asia/Shanghai），执行基线 HEAD `653f9b6f`，证据根 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1116/import-result-items`。`red-xml` 真实任务运行 1 项失败：批次清理后归档缺永久逐项结果；固定 `red-source.kt` 与当次摘要相符。最终冻结 14 份 Kotlin 源码；`self-contained-matrix` 于 13:54:05–13:55:01 成功 55s、206 任务（19 executed／187 up-to-date），实际命令见该轮 command JSON。后端 `CashWriteSetReceiptTest` 在 SQLite 与显式真实 `_test` PostgreSQL 共 14 项；新增永久结果 HTTP 方法在两库各实跑，覆盖真实存储／路由／DTO 的分页、精确身份、实际 outcome、原工作区 403 和重复参数既有 422。客户端与服务端分别验证，不冒称同一提交端到端。
   - Host 106 类／1,011 项、Chrome JS 105 类／964 项、Chrome Wasm 107 类／970 项，共 2,945 项；加双库测试共 2,959 项，均无失败、错误或跳过。10 项新增客户端用例每个目标均实际执行。原任务红测转绿、补读失败保成功／材料且不重发、已归档后刷新失败不重复补读、原令牌真实 401 清理均覆盖；SQLite 与官方 OPFS 实跑旧记录补齐、同值幂等／完整结果拒绝覆盖、批次清理、快照替换、加密重开和擦除。`verify.py` 重算源码摘要、核实际任务和固定新鲜 XML，不以非空目录或编译替代测试。
   - 同摘要的 `final-matrix` 首轮于 13:48:51–13:53:30 实际执行 iOS arm64 生产、Simulator arm64 测试编译，以及 backend ktlint／detektTest；该轮因遗漏既有 self-contained init，把缺外部夹具的 Host 集成类误纳入，19 失败／17 跳过，不计通过。终态保留于原日志和固定 XML。随后携原 `stable-file-input-self-contained.init.gradle` 并对四个测试任务逐一 `--rerun`，取得上述全绿；两轮源码完全相同，末轮 iOS／静态检查为 up-to-date，不谎称再次执行。早期精确方法过滤未匹配 Parameterized 方法，以及新 HTTP 测试误把既有 422 写作 400 的两库失败亦保留，不计绿；未改服务端状态语义。
   - Cross-platform Impact Check：共享补读及结果存储影响 Web／Android／iOS，覆盖如上；页面、路由、交互、文案和 Graph 未变，不把 Chrome 单测称应用导入冒烟，不追加设备 QA。沿原自包含范围排除 `Kotlin*ResponseIntegrationTest` 与 `KotlinPortfolioLiveIntegrationTest`，不将其计通过。未改服务端生产服务／持久化、wire、schema、依赖、密钥或财务规则；真实双库验证本片复用的永久结果接口，不替代整体提交矩阵。strict 27/27、doctor healthy、5 份文档 141 个本地目标／33 个锚点与 diff 检查通过。
7. 发布：最终独立源码／证据／文档门已通过，按授权提交推送原分支。已按最终实现核对 README、docs、references、词表、主规格／delta、接口／命令、配置／迁移及功能地图，同组更新 design、tasks、总览／导入地图及 apply 指引；无新术语、公共 wire、配置、表或用户操作，其余无需修改。保留用户撤回额外证明及原账户映射保存规则，本片落实既定结果恢复，不新增产品取舍或通用规范。回退需保留加密结果库；旧版本不识别新增明细字段时可能拒绝读取，不得清空材料替代恢复，服务端原键收据与逐项结果保持。完整生命周期、Graph、结果页、离线写入限制 与整体验收仍待完成，整体 31/71；不归档、不建 PR。

### 会话本地读取与刷新失效（7.2／7.3，应用接线）

1. 思考：Graph 已有本地分页、账户及目录适配，但在线恢复仍刻意不暴露本地 reader。按既定在线／离线相同本地派生合同继续整体 A 类 change；在线目录可独立接线，完整在线列表须同时对齐详情、分类与删除的事实及版本，不能使已有动作退化。
2. 计划：在线／离线目录使用原 owner，离线列表保持同一派生入口；每次读取前后核原发表代次及刷新 epoch。换会话取消，仍同会话跨刷新用既有 `read_snapshot_changed` 触发分页失效，不选择后来会话的服务器请求。在线列表、详情、证据及缺可读缓存的旧回退仍须在完整切换中移除，不把本片标为 7.3 完成。
3. 任务拆分：先复现旧 reader 跨发表与刷新仍返回旧值，以及取消误走服务器；独立审查补 wrapper → 实际仓储 → PagingSource 的刷新回归，再验证四个读面、真实调用方取消、正常在线目录／离线列表及无额外 HTTP。后续完整在线列表先完成规范事实与账本版本的维护／详情适配。
4. 构建：已接在线本地账户及目录，在线列表保留原来源；本地读取 wrapper 在调用前后核原发表代次与刷新 epoch。LocalFirst 不吞取消，同会话刷新沿既有快照失效进入分页 Invalid，新 PagingSource 可正常读取。
5. 审查：独立 5a 和实际独立契约 5b 发现两项 P2：直接暴露在线本地列表会把 `ledger_seq` 传入仍比较独立投影版本的分类／删除接口，新写集条目亦可能不在旧详情 active dataset；同会话跨 epoch 的合成取消不能触发 PagingSource.Invalid。前者先保留在线列表来源、仅接本地目录，完整切换依赖原位写入 design；后者须改用既有快照失效并补实际调用链回归。无布局、文案、组件变化，不触发 UI 复用专项；修复后独立 5a 与实际独立契约 5b 均窄复核关闭两项 P2，无新增 finding；评审者只读，不以静态结论替代 QA。
6. 测试与 QA：基线 `105ed60f` 加工作树，证据根为仓库外 `offline-ledger-1116/local-import-lifecycle/`。`online-local-reader-red` 两类 48 方法中 8 项真实失败，XML 已冻结。初次实现的 focused 因 DTO 别名遗漏编译失败；修正后的 focused-fixed 48 方法通过。`online-local-reader-platforms` 三运行目标通过，但对应尚未修复上述两项 finding 的中间实现，不作为最终行为门通过；原 XML 保留。`local-reader-review-red` 两类 49 方法中 3 项真实失败，包含实际仓储／分页调用链与在线列表隔离，XML 已冻结；修复后的 `local-reader-review-green` 三类 56 方法全绿（0 失败／错误／跳过），含真实调用方取消及刷新后新分页成功。最终 `local-reader-final-platforms` 2m52s 成功，187 任务（33 executed／154 up-to-date），Host 106 类／1057 方法、Chrome JS 105／1006、Chrome Wasm 107／1012，共 3075 方法零失败／错误／跳过；三测试任务均 `--rerun`，iOS arm64 生产及 Simulator arm64 测试编译通过。318 份新鲜 XML、计数与实际源码摘要已核并固定在同名 command／exit JSON 和 `-xml/`；仍沿既有 self-contained init 排除外部响应／持仓夹具，不把排除项记为通过。真实 Chrome 157／1440×1100／`http://127.0.0.1:18391/` 的 `browser-local-catalogs-current` 两流程全绿，实际 27.675s／28.326s，含新生产 Wasm 构建共 3m29.6s，0 跳过／重试／flaky。登录后联网核验原身份／工作区，拦截三个远端目录接口，实际打开筛选并看到缓存内的合成账户，两流程均零目录请求；在线列表仍沿原接口，未声称已移除旧投影读取。随后分别完成普通保存和真实 409 后重新核对，实际写集请求分别 1 次／含拒绝共 2 次；关闭浏览器进程、原 profile 新进程启动后离线恢复原账本／结果，再联网核验原身份。冷启动各仅 2 次认证探测，无事实 HTTP、额外提交、旧导入路径或 pageerror；预期原键 404／冲突 409 之外无网络／控制台错误。当前 TS 与 4 份 trace 冻结正文逐字匹配，构建 stamp 与产物核对 `stale=false`；已检查在线账户菜单、离线实际流水和结果截图。真实服务端 DML／schema 未改，本片不新增双后端验证范围，不以客户端测试代替 PostgreSQL 合同。Cross-platform Impact Check：Web、Android、iOS 共用仓储，读取来源及迟到结果边界均受影响；无布局、文案或新交互。
7. 发布：本片实现、独立审查及适用验证已完成，同组更新 design、总览／现金账本／共享功能地图及 apply 指引。README、references、术语、主规格／delta、接口／命令、配置／迁移已核，无新 wire、schema、用户动作或术语，其余无需修改。用户未作新产品取舍，实施顺序按实际依赖调整，无需新增通用规范。完整在线列表／详情／维护／旧回退退役、离线写入限制 和完整逐项结果 UI 仍未完成；原结果页呈现方向及其专项设计门已通过。当前交付范围的最终独立证据门已通过，按同分支授权提交推送，整体 38/71，不归档、不建 PR。

### 规范条目的批量分类事务（4／7.2／7.3）

1. 思考：现有批量分类从物化投影选成员并验证独立投影版本，不能消费端侧 `ledger_seq`。按既定稳定对象、明确选择、整批原子及来源不变合同实施，不增加来源或账户映射证明。
2. 计划：先在现有分类 Application Service 提供内部规范条目命令，同事务锁工作区、重新鉴权、验证账本基线与分类资格，从规范条目／完整成员读取明确选择，复用共享公共字段编辑规则并集合保存。没有物化状态也可执行，版本仅随规范事实实际变化推进；不做发现、重算投影或换算旧版本。旧 HTTP 路由待详情、删除与列表切换时同组改接；内部能力不代表页面已迁移。
3. 任务拆分：先复现无物化状态及版本分离时不能分类；覆盖合并／独立成员、明确空分类、同值保护及幂等、陈旧／缺失／跨工作区目标、权限变化、写后回滚和精确大版本。两库运行同一生产服务，检查 SQL 不接触物化表和批量查询边界，再做独立 5a／契约 5b。
4. 构建：内部 `setEntryCategory` 已完成；原分类 HTTP 与页面保持旧路径。规范条目批量读取以 400 个身份分块，整组校验后复用原公共字段 patch，集合保存条目与单例独立值；不读写物化表。明确同值首次保护、重复不再推进、清空、来源值保持及程序错误传播均沿原规则。
5. 审查：独立 5a 与实际独立契约 5b 已核事务内鉴权／账本基线、整组根／身份／存活成员、shared patch、原子回滚、saved 缓存同步、索引及集合 SQL，无阻断 finding；未覆盖的 400 个身份分块边界已补 401 个选择／成员及未选对象保持回归。无 UI 改动，不触发 UI 复用专项；最终独立证据核对通过，无遗留 finding；已确认暂存范围、源码摘要、七份 XML、双库分块／公开组合链、隔离编译及性能证据，任务进度混用的 P3 已关闭。
6. 测试与 QA：基线 `105ed60f` 加本片，2026-11-16，证据根 `offline-ledger-1116/local-import-lifecycle/` 在仓库外。初次红测有 JSON 字段类型编译错误，不计业务复现；修正后的 `entry-classification-red-fixed` 真实 SQLite／PostgreSQL 四实例均在物化状态不可用处失败，XML 已冻结。初步实现后 focused 四实例绿，boundaries 两库 22 实例、随后 performance 两库 24 实例均通过；中间 final 因测试引用模块内部 renderer 编译失败，不计绿，已改用公开生产查询入口。
   - 当前 `entry-classification-final-current` 实跑 29s 成功，15 任务（5 executed／10 up-to-date），后端五类 207 方法、领域编辑／协调两类 18 方法，共 225 方法，0 失败／错误／跳过，两测试任务均 `--rerun`。新分类合同类为 14 方法／双库 28 实例；同一实际服务覆盖无物化状态、与独立物化版本分离的精确大账本版本、组／单例／保护／清空／no-op、陈旧及跨工作区、逐种独立损坏／权限／令牌过期、执行成员 batch 后真实存储故障回滚、程序异常、401 个选择与未选对象保持。公开真实写集 HTTP → 规范分类服务 → 公开事实快照 → 公开本地查询组合在两库通过，原收据未被后来分类改写。
   - SQL 观察器在实际执行时拒绝接触任何 `cash_projection*` 表。性能场景 10,000 条目／100 选择／200 成员，2 轮预热、5 轮记录；SQLite P95 6,586,375ns／11 SQL／RSS 增量 1,015,808 字节，PostgreSQL P95 85,045,875ns／10 SQL／360,448 字节，低于原 500ms／12 SQL／96MiB 预算，采样失败不能当通过。当前 command／exit、源码摘要、七份新鲜 XML 与计数及独立性能 JSON 已固定。使用显式已准备 PostgreSQL `_test` 库，未自动探测或启动数据库。仅内部事务与仓储，不改变页面／文案／交互；Web、Native 暂未接此入口，后续整体切换仍需真实浏览器与相称跨端验证。
   - 独立暂存验证 `entry-classification-staged` 从基线 `105ed60f` 的新 worktree 仅应用本片 10 文件，未带入待确认 UI／客户端接线；全新编译及同一测试命令 1m33s 成功，15 任务全部 executed，225 方法及七份新鲜 XML 均零失败／错误／跳过。SQLite P95 6,576,708ns／11 SQL／RSS 增量 1,015,808 字节，PostgreSQL P95 73,772,875ns／10 SQL／589,824 字节；源码摘要与原工作树本片相同，冻结在同名 command／exit、`-xml/`、`-performance/` 和 staged patch。
7. 发布：本片实现、独立最终门及适用验证完成，strict 27/27、doctor 正常、107 个本地链接及暂存 diff 检查通过，按现有同分支授权独立提交推送；同组更新 design、总览／现金账本功能地图与 apply 指引。README、references、主规格／delta、词表、接口示例、配置／迁移已核，无新公开 wire、schema、用户动作或术语，其余无需修改。无新用户产品取舍，沿既定稳定对象与账本版本合同实施，无需新增通用规范。完整在线列表、详情、删除、旧接口退役及整体剩余任务保留；完整 change 仍未完成，不归档、不建 PR。

### 规范条目的删除影响与批量删除（4／7.2／7.3）

1. 思考：原预览与确认依赖物化状态和独立版本，不能消费端侧规范条目及账本版本；沿既定明确整笔范围、危险确认、权限与原子性合同推进，无新用户取舍。
2. 计划：内部预览与删除共用规范条目完整成员校验；预览只读、同快照核基线并返回前复核身份，执行锁工作区、重鉴权、核基线后沿原引用清理／逻辑删除／完整组清理／余额提交。不运行发现或服务端投影，过渡期仅保留必要派生外键清理。完整组保存采用集合 SQL，旧通用部分成员删除不变。
3. 任务拆分：先复现无物化状态的预览及删除失败，再覆盖完整所选组／未选保持、基线／跨工作区／权限、各阶段回滚、关系及候选引用、余额和精确版本；两库同一实际服务运行，检查分块与删除自身的 SQL／延迟／RSS，再做独立 5a／契约 5b。
4. 构建：内部 previewEntries／deleteEntries 已实现，旧 HTTP 与页面尚未改接。选择在回调前固定；完整组核根、身份、存活成员与 confirmed 闭包，超出明确选择即拒绝。引用清理、来源逻辑删除、完整组退休及余额同事务，保留原修订溢出和故障传播。removeCompleteEntries 按 400 条目分块删除成员及条目，核实际影响数量并同步缓存，不逐条目保存或重新派生。
5. 审查：独立 5a 与实际独立契约 5b 及 bulk 增量复核均无遗留 finding，覆盖预览快照／身份／版本、写事务鉴权、选择闭包、引用／余额、删除数量、缓存及保存点边界。原通用 removeMembers 的逐条目 SQL 经成本红测证实后改用完整组专用集合删除；旧部分成员路径不变。无 UI 变化，不触发 UI 复用专项，用户已确认的结果页不在本片提交范围。
6. 测试与 QA：基线 00636499 加本片，2026-11-16，仓库外证据根 offline-ledger-1116/local-import-lifecycle/。首次准备脚本编码失败导致无测试匹配，不计业务复现；准备完成后的 entry-deletion-red-prepared 四个真实双库实例均在 projection.unavailable 失败，XML 已冻结。focused 连同分类 32 实例通过；boundary 四项因测试 confirmed 关系未补齐组成项外键失败，修正夹具后关闭，未改生产约束。entry-deletion-cost 新增真实成本红测，100 选择 SQLite 232 SQL／PostgreSQL 231 SQL，只有两项成本断言失败，完整 XML 保留；集合删除后 bulk-green 三类 86 实例通过。
   - entry-deletion-final 实跑 39s，15 任务（7 executed／8 up-to-date），后端六类 237 方法、领域编辑／协调两类 18 方法，共 255 方法，0 失败／错误／跳过，两测试任务均 --rerun；八份新鲜 XML、源码摘要与计数已核并冻结，新删除类 15 方法／双库 30 实例。覆盖无物化状态、完整组及未选对象、独立损坏根／身份／成员、stale／missing／empty／negative、闭包不扩围、四阶段全表回滚、候选／问题 owner 保留及修订、余额与重复删除、精确大版本、返回前范围变更、事务内权限／过期、可变输入固定、账本／条目溢出和 501 选择跨分块。公开真实写集 HTTP → 分类／删除内部服务 → 公开快照 → 公开本地查询的两库组合通过，删除后空页、原键重放不复活流水或扣第二次余额。
   - 删除专项独立于分类：10,000 条目，每轮 100 选择／200 成员，7 轮不同集合（2 预热／5 记录）；SQLite P95 19,505,208ns／33 SQL／RSS 增量 1,015,808 字节，PostgreSQL P95 161,683,667ns／31 SQL／557,056 字节，均在本片 500ms／50 SQL／96MiB 回归门内。采样失败不能当通过；同名 command／exit、-xml/ 与 -performance/ 固定实际证据。使用显式已准备 PostgreSQL _test 库，未自动探测或启动数据库。过渡期只清除必要物化外键，未读取 active dataset、派生或发布投影。
   - 独立暂存验证 entry-deletion-staged 从 00636499 仅应用本片九文件，1m42s 成功，15 任务全部 executed；四份 Kotlin 与暂存摘要一致，八份新鲜 XML 共 255 方法全零。隔离删除性能 SQLite P95 18,583,375ns／33 SQL／RSS 增量 999,424 字节，PostgreSQL 166,817,917ns／31 SQL／671,744 字节，均通过；终态、摘要、固定 XML 与性能 JSON 在同名前缀证据中。独立最终证据／文档门无新增 finding。Cross-platform Impact Check：仅内部 backend 事务和仓储，Web／Native 尚未调用新入口；无页面、交互、文案、wire 或 schema 变化，不把内部双库结果称为最终浏览切换或 UI 通过。
7. 发布：本片实现、独立源码审查与双库验证已完成；隔离验证及独立最终门通过，strict 27/27、doctor 正常、109 个本地链接和全工作树／暂存 diff 检查通过，按原同分支授权提交推送。已核 README、docs、references、词表、主规格／delta、接口／命令、配置／迁移，同组更新 design、总览／现金账本地图和 apply 指引；无新公开 wire、schema、用户动作或术语，其余无需修改。无新用户产品取舍，沿既定选择范围及账本版本合同实施，无需新增通用规范。完整在线列表／详情／HTTP 维护切换、旧路径退役及整体剩余任务保留，不归档、不建 PR。

### 本地当前成员与证据读取（4／7.2／7.3）

1. 思考：原成员详情及证据读面依赖 active dataset，端侧只有分页／定位，无法完成同账本版本的详情读取。沿既定完整事实本地派生和原详情／来源合同推进，版本改用 ledger_seq，无新增产品取舍。
2. 计划：在同一 LocalCashLedgerQuery 捕获中提供成员与完整证据，复用原已派生投影／稳定条目，不重新匹配或联网。保留可见成员入口与隐藏证据的不同资格、完整角色／端点／退款／资金信息及错误语义，后续应用端口与维护整体切换。
3. 任务拆分：先复现无物化状态无法详情／证据，再比较真实双库原读面与同快照本地输出（只明确替换账本版本）；覆盖合并、草稿、待处理、隐藏、金额精度、时间及原文／角色、失效关系和安全资金证据，运行共享目标并独立 5a／契约 5b。
4. 构建：LocalCashLedgerQuery 已复用同捕获的 LocalCashProjectionViews 提供 currentGroup／evidence，返回完整成员与既有 JSON 证据，版本直接取 ledger_seq。来源原值与条目公共值分开，保留组件顺序、Long 序号、实际端点、非生效关系状态／顺序、退款时间线及安全资金白名单。非生效关系按父记录索引，仅首次构建扫描完整关系集；读取不写事实、不查询物化状态、不补网络。原证据分类目录资格也保持，公共分类单独编辑不改变来源无分类时的旧证据输出。
5. 审查：独立 5a 按产品编码规范检查原读取合同、错误、完整成员、来源与公共值隔离、精度、时间、组件端点、复杂度及测试；发现两项 P2：DTO 默认编码扩展原分类／账户字段、ordinal.toInt 收窄。已裁剪证据字段并保持 Long，通过完整双库差分及宽序号回归。独立 5b 调用 codebase-degradation-review → contract-design-review，发现仅有条目公共分类时的原目录加载资格差异；已保留旧行为，以真实旧分类服务操作后的双库完整 JSON 对照覆盖。保留既有成员 DTO 与证据 JSON，未新增持久派生状态或证明字段；应用接线尚未覆盖。无 UI 改动，不触发 UI 专项。当前源码及终态证据的独立复核已通过，无遗留 finding。
6. 测试与 QA：基线 HEAD 51e3eee440b3a4d0d0bbafda1762f604d59aaf17；2026-11-16 执行，命令／时间／五个 Kotlin 文件 SHA／结果及冻结 XML 在仓库外 `offline-ledger-1116/local-import-lifecycle/entry-read-complete-*`。先用旧读面复现无物化状态的四项真实双库失败，冻结于 entry-read-red-xml，再转用本地同快照读取。最终 `:backend:test` 选新读取／分类／删除三 suite 共 76 项（18／28／30），`:business-domain:jvmTest` 890 项、`:business-domain:jsNodeTest` 842 项、`:business-domain:wasmJsNodeTest` 842 项全部执行且零失败／跳过；`:business-domain:compileKotlinIosArm64` 与 `:business-domain:compileTestKotlinIosSimulatorArm64` 通过，整轮 183.31 秒、源码 SHA 一致。九种双库读取场景包含无物化状态、组成项／软删端点、镜像＋退款、隐藏零额／未知草稿、真实公共分类编辑、转账与银证资金关系；完整证据只明确替换两处版本后逐字段对照。万条流水／万条非生效关系的纯生产读取，5 次预热、7 轮每轮 100 次；外部验证器严格核 capture≤5000 ms、100 次 max≤500 ms，实际 SQLite／PostgreSQL 模板 capture 157.54／109.21 ms、max 8.95／1.63 ms，预算及绑定 XML 摘要见 entry-read-performance.json；不代表双库 IO 或整页性能。同一源码的共享兼容矩阵 entry-read-shared-* 用 153.23 秒完成：`:shared:testAndroidHostTest` 1057 项、`:shared:jsBrowserTest` 1006 项、`:shared:wasmJsBrowserTest` 1012 项，318 份新 XML、3075 项零失败／错误／跳过；`:shared:compileKotlinIosArm64` 与 `:shared:compileTestKotlinIosSimulatorArm64` 通过。Web、Native 与共享业务层均编译相同新实现；未修改 UI 或应用读端口，本片不代替真实页面冒烟或 Native 设备 QA。
7. 发布：同组更新 design、总览／现金账本功能地图及 apply 指引。已按最终实现核对 README、docs、references、词表、主规格／delta、接口／命令示例、配置／迁移；其余没有新术语、wire、schema、配置或用户操作，无需修改。strict 27/27、doctor healthy、5 份文档 121 个本地链接目标／35 个锚点及 diff 检查通过。用户没有作新产品取舍，本片保持既定只读与兼容边界，无需新增通用规范。当前实现、适用验证及最终独立证据门均通过，按同分支授权提交推送；完整在线详情／证据／维护、离线写入限制、完整逐项结果页面与旧路径退役仍未完成，整体 38/71，不归档、不建 PR。代码可独立回退，无持久格式或迁移变化，回退不能改写永久结果与规范事实。

### 本地流水完整详情读取（4／7.2／7.3）

1. 思考：成员／证据已可本地读取，但来源原文、公共条目、编辑能力和问题选项仍依赖旧服务端详情；沿既定全部事实端侧派生补齐同快照读面，不新增产品取舍或证明合同。
2. 计划：复用同一 LocalCashLedgerQuery 及来源／记录策略，按明确角色生成只读能力提示，保留原备注令牌、问题、候选及关系；稳定定位仅采用同捕获的账本版本。未知／已删拒绝，损坏状态停止，不修复／写入或联网。
3. 任务拆分：先复现缺少物化状态时旧详情没有当前定位，再按真实双库详情逐字段对照（仅替定位版本），覆盖导入／人工／转账、草稿、只读角色、来源更新／身份歧义、原始数值／备注及错误；执行跨平台与独立 5a／契约 5b，应用端口与完整切换继续保留。
4. 构建：LocalCashLedgerQuery.record 已从同一完整快照生成原来源字段、组件、公共条目、原备注令牌、问题／原候选观察、保存关系及稳定定位；不写事实或联网。角色参数只影响可编辑提示，不能授权实际写入。来源问题复用现有准备／效果／组件身份规则，显示序号保留 Long，内部策略整数沿旧 Int 桥。原 JSON 数字词法、金额 scale／负零、时间及来源／公共值隔离保持。身份选项使用同捕获索引，先去重自然键及证据键，再汇总存活 owner，避免别名数与历史 owner 数相乘。
5. 审查：独立 5a 按产品编码规范核读取资格、错误、数值／时间保真、能力提示、来源问题、身份／关系及复杂度；独立 codebase-degradation-review → contract-design-review 核跨层合同和模型归属。两项 P2 已修复并经窄复核：元数据真假必须复现旧 BigDecimal 的最终 scale 有效域，而非金额解析或只限制指数；相同证据的多个别名不能重复展开全部 owner。独立 JDK 探针与真实来源解决选项对照覆盖极端零指数，2,000 别名／2,000 历史 owner 的性能红绿保留。无新增或遗留源码 finding；最终完整 QA 证据门已通过。无 UI 改动，不触发 UI 专项。
6. 测试与 QA：基线 HEAD adaf69c8e5fa192e436ef914e32559489ea03698，2026-11-16，证据根 offline-ledger-1116/local-import-lifecycle/。旧详情在没有物化状态时缺少当前定位的两项真实双库红测冻结于 record-read-red-xml。最终聚焦 record-read-final-preflight 在相同五个 Kotlin 摘要上 35 秒成功，真实双库完整读取 26 项、领域读取 17 项均零失败／错误／跳过；真实手工覆盖字段决定是否接受非法来源 patch，避免不可观察的真假断言。最终 record-read-final 实跑 316.35 秒成功，214 任务（47 executed／167 up-to-date），五份源码摘要未变。后端读取／分类／删除／来源规则四 suite 共 92 项；领域 JVM 893、JS Node 845、Wasm Node 845；共享 Android Host 1057、Chrome JS 1006、Chrome Wasm 1012，全部 5,750 项零失败／错误／跳过，七套 856 份新鲜 XML 已固定并核摘要。完整详情在原双库服务逐字段对照，只有 projection_ref.projection_version 明确替换为账本版本，候选保存的旧修订不被刷新。iOS arm64 生产及 Simulator arm64 测试编译通过，未执行原生设备交互。原自包含 init 排除的外部响应／持仓夹具不计通过。
   - 性能专项：10,000 流水／10,000 非生效关系，另有 10,000 绑定、2,000 当前别名及 2,000 软删历史 owner；每组 5 次预热、7 轮每轮 100 次纯读取。外部验证器核首次捕获≤5000 ms／100 次最大≤500 ms；当前别名读取最大 SQLite 75.44 ms／PostgreSQL 模板 70.90 ms，首次捕获 103.82／135.49 ms。去重前功能测试通过但性能最大 9474.09／10917.37 ms，record-read-alias-red-performance.json 明确为失败，不与最终绿混用。样本、预算及 XML 摘要绑定在 record-read-final-performance.json；这是合成内存读取，不代表数据库 IO、整页或全导入性能。
   - record-read-complete 为修复前中间矩阵，显式标记 intermediate_before_alias_optimization，不作为终态。最终命令／时间／源码／固定 XML／计数由 verify-record-final.py 和 record-read-final-verification.json 核验；独立 DecimalTruthProbe.java／txt 保留原 JDK 参考。Cross-platform Impact Check：Web／Android／iOS 共同编译同一业务函数；本片无应用读端口或页面改动，不代替真实页面冒烟或设备 QA。SQLite 与显式已准备的真实 PostgreSQL _test 库实际对照，测试没有自动探测／启动库。strict 27/27、doctor healthy、5 份文档 124 个本地目标／36 个锚点及 diff 检查通过。
7. 发布：完整验证及最终独立源码／证据／文档门已通过，按原分支授权提交推送。同组更新 design、总览／现金账本功能地图及 apply 指引；README、docs、references、主规格／delta、词表、接口／命令示例、配置／迁移已按最终实现核对，无新术语、wire、schema、配置或用户动作，其余无需修改。保留用户撤回额外证明及原账户映射保存规则；本片沿既定读取合同实施，无新产品取舍，无需新增通用规范。代码可独立回退，无持久格式变化，不得改写永久结果及规范事实。应用读端口、在线列表／维护、离线写入限制、完整逐项结果 UI 与旧路径退役均保留未完成；整体 38/71，不归档、不建 PR。

### 无文件维护提交移除物化依赖（4／7.2／7.3）

1. 思考：无文件维护的规划及重验已从规范事实读取，提交末段仍强制 active dataset 并维护物化结果；沿既定后端维护边界移除依赖，不扩大文件写集能力或新增证明合同。
2. 计划：在原事务及永久收据路径提供内部 canonicalEntries 规范条目命令，旧 HTTP 默认保持原行为，待应用整体切换时删除过渡分支；受影响范围交替包含原条目完整成员及当前确认关系闭包，避免拆分漏成员。待处理统计从完整成员及原 attention 规则计算，版本直接返回本事务 ledger_seq；不发布物化投影或额外镜像版本。原键重放、权限、问题／关系处理、余额与错误保持。
3. 任务拆分：先写真实双库无物化状态的维护失败回归，再覆盖公共编辑、草稿／转正式、关系拆分及未选保持、原键重放、回滚、权限与修订冲突、精确版本；检查旧物化 SQL、执行适用专项及独立 5a／契约 5b。
4. 构建：原 MutationPlanService／writer 增加内部 canonicalEntries 路径，默认 false 保持旧 HTTP；规范路径复用当前来源／关系保存及余额，交替展开全部旧条目成员及当前确认关系闭包，清理已退休源成员并复用共享协调，不读取 active dataset。pending 按完整组的草稿／原 attention 规则计算，避免漏掉未直接成功的同组成员；新收据 ledger_seq 与兼容 projection_version 均取真实事务版本，旧收据原样重放。
5. 审查：独立 5a 按产品编码规范核范围、原子性、权限／重验、闭包、协调／余额、版本、幂等与复杂度；实际独立 codebase-degradation-review → contract-design-review 核规范事实、内部过渡路径与公开默认边界。当前生产无 finding；按 P3 建议强化原组非空人工公共值及不同成员独立值，对继承／不继承两个原语义分别做可观察断言，原来源字段保持。无 UI 变更，不触发 UI 专项；最终独立源码／证据／文档门已通过，无遗留 finding。
6. 测试与 QA：基线 HEAD 0ffcd928f64d036e3add85e868cce28de1c3f6de；2026-11-16，仓库外证据根 offline-ledger-1116/local-import-lifecycle/。canonical-maintenance-red-diagnostic 在同一真实维护服务及 SQLite／PostgreSQL 的四实例均因 projection.unavailable 失败，完整 XML 与当次测试源码冻结于 canonical-maintenance-red-xml 及 canonical-maintenance-red-test.kt；更早的测试表名编译错误／无组成项夹具错误不作为业务红灯。终态 canonical-maintenance-final 全量默认 backend 回归及 ktlintCheck／detektMain／detektTest 于 327.53 秒成功，22 任务（9 executed／13 up-to-date）；203 份新鲜 XML 共 1,467 项零失败／错误／跳过，当前五份 Kotlin 源码摘要完全一致。默认任务明确排除 HistoricalMigrationEvidence，未执行的历史外部夹具不计通过；本片的实际服务全部 11 方法／双库 22 实例均执行，没有 PG skip。
   - 覆盖无物化公共编辑／草稿保存、转正式、精确大版本同值 no-op、三个写阶段全表回滚、来源／条目陈旧及权限撤销、原键重放、旧默认路径保持、完整旧关系移除后两侧协调及稳定身份／未选保持；强化夹具给原组公共人工值及两成员不同独立人工值，继承／不继承分别核公共与独立值，原来源事实不改。身份草稿合并验证软删源、绑定转移、旧源条目清理及目标身份／手工备注保留；同组未直接成功成员的问题计入 pending，不额外计为成功父项的 attention。最初增强拆分测试将继承后的独立值误断言为旧值，按未改原规则及主规格修正测试预期后双分支全绿，没有改变生产政策。
   - 同一实际提交服务在 1,000 条无关事实下，两轮预热、五轮不同备注写入；实际 execute 前的 SQL 探针拒绝任何 cash_projection 表访问。SQLite 最大 18.15 ms／62 SQL，PostgreSQL 最大 21.74 ms／60 SQL，低于本片 5000 ms／250 SQL 回归预算，1,000 未选条目仍在且原备注保持。完整数字、命令／时间、源码与 XML 摘要由 verify-canonical-maintenance.py 及 canonical-maintenance-final-verification.json 固定；这是本机合成服务提交，不代表整页、移动设备或完整文件导入性能。真实 PostgreSQL 使用已显式配置的 _test 库，测试不探测／启动库。
   - Cross-platform Impact Check：仅 backend 内部路径及仓储辅助方法，默认 HTTP、客户端 DTO、共享读取端口、Web／Native 页面与文案没有变化；本片不追加 UI 冒烟或原生编译／设备 QA，也不将服务测试当应用切换完成。strict 27/27、doctor healthy、5 份文档 127 个本地目标／37 个锚点和 diff 检查通过。
7. 发布：完整验证及最终独立源码／证据／文档门已通过，按原分支授权提交推送。同组更新 design、总览／现金账本功能地图及 apply 指引；README、docs、references、主规格／delta、词表、接口／命令示例、配置／迁移已按最终实现核对，无新 wire、schema、公开配置、术语或用户操作，其余无需修改。保留用户撤回额外证明及原映射保存规则；这一步只落实既定维护边界，无新产品取舍，无需新增通用规范。新增内部路径可独立回退，不改事实／永久收据；既有收据原样重放。完整在线列表／详情／维护切换、离线写入限制、完整逐项结果 UI 与旧路径／物化表退役仍未完成，整体 38/71，不归档、不建 PR。

### 会话内完整详情与来源读取接线（7.2／7.3）

1. 思考：本地业务查询已提供完整记录、成员和证据，但会话 reader／owner 只暴露列表与目录；沿既定完整事实只读合同接通同范围应用读端口，不改变页面或维护语义。
2. 计划：现有响应 DTO 原样共享并保留旧包类型别名，统一同捕获读取；来源证据与所有成员记录一次生成，复用原映射。角色只裁剪能力提示，恢复对象／发表／刷新围栏覆盖整个读取，不以多次刷新读取拼接来源。Graph 切换待完整列表／维护合同就绪。
3. 任务拆分：先写缺失端口的失败回归，覆盖同快照版本／成员／原文、只读角色、关闭／刷新／迟到读取和错误；运行受影响共享跨平台及相称编译，独立 5a／契约 5b。
4. 构建：先以 session-detail-red 复现缺失 currentGroup 端口的编译失败，再共享原 DTO、接通 reader／owner／restoration 及发表围栏。sourceRecords 在一次 storage 读取中产生证据与全部成员记录，旧结果不随刷新变化；远端仍逐成员请求并即时核对，首项定位不符时不读取后项。本地复用同一成员检查，修订不符拒绝。角色取原范围 Session，缓存 reader 不缓存权限；未知及程序错误不补网络或部分结果。Graph、在线列表及维护默认路径不变。
5. 审查：独立 5a 按产品编码规范核字段／默认值、数值及原文、同快照边界、原会话角色、关闭／刷新取消及远端即时拒绝。独立 5b 通过 codebase-degradation-review → contract-design-review 实际复核；原 DTO 四段正文与提取 SHA 逐一核对，只有既有 AccountDto 别名展开为 AccountReferenceDto。内部组合值隔离同次读取结果，不引入业务／持久状态；当前实现、冻结 QA 及正式文档的最终独立门已通过，无遗留 finding。无 UI 改动，不触发 UI 专项。
6. 测试与 QA：session-detail-boundaries 聚焦共享 Host 已通过，覆盖三角色×在线／离线真实恢复对象无新增详情 HTTP、同版本完整来源、关闭／换代／刷新围栏、开放 JSON 大整数／小数 token 与运行时构造的孤立 UTF-16 代理。最终 session-detail-delivery 在基线 HEAD c7f45dd1fcb17fb894cde12aae81e10f51955e67、2026-11-16 执行 189.44 秒，218 任务（50 executed），20 个 Kotlin 摘要与当前源码一致。实际命令／时间／摘要、353 份新鲜 XML 与校验器保存在仓库外 offline-ledger-1116/local-import-lifecycle/session-detail-delivery-* 及 verify-session-detail-delivery.py。business-contracts JVM／JS Node／Wasm Node 各 34 项，共享 Android Host 1064、Chrome JS 1013、Chrome Wasm 1019，后端完整读取及规范维护两 suite 48 项；全部 3,246 项零失败／错误／跳过。contracts ktlint 与 common detekt 检查通过，JVM Main／Test 和 Android Host detekt 为 NO-SOURCE。共享 iOS arm64 生产及 Simulator 测试编译通过；delivery 中 contracts ktlint／common detekt、iOS 两架构与三端 shared 测试均实际执行；部分无变动任务沿同源码缓存复用，不声称全部重新执行。真实双库沿显式已准备 PostgreSQL _test 及 SQLite，未探测／启动数据库；外部响应／持仓夹具被自包含 init 排除，不计通过。session-detail-final 因测试字符串构造的跨平台弃用而失败，已改 concatToString；session-detail-complete 构建成功但部分 XML 为缓存，明确不作为全新矩阵。Cross-platform Impact Check：三端均编译同一会话端口，Web 实跑 JS／Wasm 浏览器测试，Native 运行 Host 测试及 iOS 编译；本片未改 Graph／UI／路由／文案，无页面冒烟或设备 QA 结论。
7. 发布：同组更新 design、两份功能地图及 apply 指引；已核 README、docs、references、词表、主规格／delta、接口／命令示例、配置／迁移，没有新字段、术语、schema、持久格式、配置或用户操作，其余无需改写。strict 27/27、doctor healthy、5 份文档 128 个本地目标／37 个锚点及 diff 检查通过。原响应模型归属调整可独立回退，不变更规范事实或永久结果。无新增用户产品选择或通用规则，保持原合同，无需沉淀新规范；最终独立门已通过，按原分支授权提交推送。Graph／在线列表／维护整体切换、离线写入限制、完整逐项结果页面与旧路径退役仍未完成，整体 38/71，不归档、不建 PR。


### 应用账本与规范维护整体切换（7.2／7.3）

1. 思考：内部详情及三类规范维护已就绪，线上列表仍读旧物化版本；本片统一实际 Graph 的列表／详情／分类／删除及维护，保持既有页面任务与写入语义，沿 A 类 change 完成受影响全链验证。
2. 计划：先更新失败回归，再开放在线本地列表、取消缺失事实时旧读取回退，维护只读仓储绑定会话端口；HTTP 写边界解析明确稳定条目与精确账本版本，默认维护使用规范协调。按用户决定在原 FAB 菜单补齐文字刷新入口，不新增账户／来源证明字段，不改变映射保存规则。
3. 任务拆分：覆盖在线／离线同结果、陈旧及关闭无远端回退、完整详情／来源、真实双库 HTTP 分类／删除／维护及过期／权限拒绝；共享跨端回归、iOS 编译、Web 生产真实浏览器走账本／详情／维护及离线重载，独立 5a／契约 5b，按实际影响核对文档。
4. 构建：application-ledger-switch-red-observed 以原生产仓储复现在线 page 为 null 及缺事实回退旧服务端的两项失败；application-canonical-http-red-observed 在 SQLite／PostgreSQL 的四项 HTTP 分类／删除均因旧物化状态不可用而失败，XML 固定在同名 red-xml。现 Graph 的本地 reader 对在线／离线一致，列表／目录／完整详情缺事实不回退旧读取；维护的真实计划／提交仍由服务端处理，默认提交协调规范成员。分类／删除 HTTP 边界解析公开稳定条目，精确使用账本版本。分类保留原 iterable／pythonIntegerValue 及字面表示，删除保持非字符串整数字面及 96 码点预算。
   - 请求范围：CashBusinessOperations 在首挂起前固定原发表及 immutable generation，提供同一次固定凭据 API 与刷新回调；动作前后及取消均围栏，真实 401 复用原精确 token 清理。API 视图共用 HttpClient、transport tracker 与 credentialMutex，close 不关闭共享资源；X-FT-Workspace-ID 唯一、非空且不能由 extraHeaders 覆盖，服务端匹配已鉴权范围并在各实际事务核原工作区。分类目录补同事务预期工作区，投资／SSE 不走新增现金 factory。可变选择／补丁在 factory 挂起前冻结。
   - 刷新职责：用户 FAB 刷新项／下拉刷新／整页重试先同步完整事实，再用原筛选重建列表；保存后的 reload 仅本地重载，移除旧 LocalFirst 重复写刷新包装，成功后只有原 bound Ktor 操作持有刷新。缺事实使用 projection.unavailable 的 Error，跨捕获刷新才 Invalid，避免自动重建循环。仅原可读、同 owner／代次／epoch 的明确网络故障可重新打开原缓存；已知写入后未同步的事实继续封闭，认证、协议、程序错误及取消传播。
5. 审查：独立 5a 按产品编码规范核跨层身份、事务、字段保真、输入预算、异常／取消及复杂度；实际 5b 通过 codebase-degradation-review → contract-design-review。确认两项 finding：P1 请求完成后缺发表围栏及借新代次使用旧 owner，P2 统一输入桥漂移原分类／删除语义；均已修复，经当前源码窄复核无遗留阻断。不可达旧 direct draft 端点候选已按真实 VM 调用撤回，不计页面 finding；其退役仍归 4.x。
   - 交互改动触发 UI 门禁：用户刷新与纯列表重建区分，复用原 PullToRefreshBox／StateMessage／FloatingActionButtonMenuItem 及原布局／token，在原 FAB 菜单补明确的文字刷新入口。独立 result_design_review 已实际执行 ui-reuse-review、Hallmark audit 及设计静态复核，0 critical／major／minor，P5 H4 E4 S5 R5 V4；最终复核发现 major：正常事实同步仅有手势入口，F5 会丢筛选，缺少等效键盘动作。按用户决定移至原 FAB 菜单，与手势共用 guard；当前菜单已完成匹配构建、Tab／Enter、防连发与 compact 实测；独立自有 Chrome 在 18394 复核相同行为、HTTP 产物摘要与 stale=false，已关闭 major 及角色边界小项，最终 Hallmark 0 critical／major／minor，ui-reuse 无 finding。独立证据在 fab-final-review/review-manifest.json，不以原预核代替终审。
6. 测试与 QA：基线 HEAD 4da3911bdf585b80b98220f057ce24993ef4f4f2，2026-11-16，仓库外证据根 offline-ledger-1116/local-import-lifecycle/。application-ledger-switch-focused 的新真实双库 HTTP 与内部规范服务 90 项通过；共享聚焦覆盖本地详情、只读错误、原发表／相等新发表／实际挂起 HTTP 后换工作区、精确 token 清理、固定凭据及共享锁、冻结选择与补丁、唯一写后刷新、网络保旧／已知写入封闭／旧失败不重开及显式刷新防连发。编译及夹具错误不作业务红灯。
   - 刷新可访问性核验采用 compose-material3：实际 Chrome 读取 Material 3 Buttons Overview／Specs、Assistive technology 和 Applying M3 Expressive 正文，核短标签、原生焦点／禁用状态、默认尺寸与次要操作层级；JetBrains TextButton common API 确认 enabled 及默认 padding 合同，实际依赖仍为 material3 1.12.0-alpha03，不升级依赖。用户明确将刷新收进原 FAB 菜单，采用既有带文字和图标的 FloatingActionButtonMenuItem；同步中隐藏该项并保持动作 guard，不依赖当前 API 不提供的 enabled 参数。只读角色仅开放刷新菜单，不开放新增／导入。FAB menu Overview／Specs 已实际用 Chrome 读取，采用 3 个相关动作及原 token／尺寸／焦点行为；正文另存 refresh-fab-m3.json，JetBrains common MenuItem 签名已核。只读剩余选择仅保留取消，空菜单禁止展开且关闭始终允许；Web FAB 资格复用既有 PlatformControlAccessibility，不另造无障碍适配。
   - FAB 补验 application-ledger-refresh-fab-current 在最终菜单源码上用 55 秒、97 任务（14 executed）通过 Host 的 6 项真实 VM 回归及 iOS 两目标编译；新增 SemanticIds、四套短标签资源及刷新向量，原 29 份 Kotlin 仅 Screen 改动，其余摘要与完整矩阵一致，完整源链及冻结 XML 在同名 verification／xml。toolbar 中间方案的 disabled DOM 检查失败，未计为最终通过；该常驻入口已按用户决定移除。旧生产包缺少正常刷新动作的真实复现保留在 refresh-accessibility-red；FAB 版实际键盘及只读忙碌保护由最终浏览器执行。
   - application-ledger-switch-matrix 实跑 471.78 秒、209 任务（38 executed），原 29 个 Kotlin 中 28 份与终态一致，Screen 的菜单增量由上述源链及补验覆盖；完整默认后端 1477、共享 Host 1073、Chrome JS 1022、Chrome Wasm 1028，共 4,600 项、524 份新鲜 XML 全部零失败／错误／跳过。后端 ktlintCheck／detektMain／detektTest／installDist 与共享 iOS arm64 生产／Simulator 测试编译通过。原 HistoricalMigrationEvidence 与自包含 init 排除的外部响应／持仓夹具不计通过；真实 PostgreSQL _test 显式配置，没有探测／自动启动。命令、时间、源码摘要、固定 XML 及核验结果在 application-ledger-switch-matrix-*。
   - 当前 FAB 源码再跑 application-ledger-refresh-fab-web-tests 用 96 秒、101 任务（28 executed）完成 Chrome JS 1022／Wasm 1028 项，214 份新鲜 XML 全零，源码／资源与最终菜单一致。OpenAPI 两份资源及现有 OpenApiContractTest 的 18 项新鲜 XML 已另核，安装的后端资源与真实 /openapi.json 一致。
   - Chrome 157、真实 Kotlin 后端 18243 与生产 Wasm 18393 的五条流程分别通过：普通导入 28.667s、冲突重核对 31.677s、已保存收尾恢复 29.181s、完整账本维护／刷新 35.523s、只读成员菜单 3.660s。前三项取 browser-application-fab-verified 的实际成功 case，后一 job 中失败的旧焦点标定不计通过；最终两项由 browser-application-fab-ledger-verified 独立重跑全部成功。八份进程 trace 的 TS 与当前对应文件逐字一致，命令／时间、报告与源码摘要在 application-ledger-switch-browser-verification.json，生产构建指纹 stale=false。
   - 实际走新登录、本地在线列表／详情／来源、编辑计划提交、分类、删除影响／确认、纯本地重载、外部新增后按原筛选同步、Tab／Enter 打开 FAB 并刷新、在途防连发、503 明确错误及原入口重试、390 断网刷新保旧／冷启动完整详情；只读成员无新增／导入，忙碌无可用项时 FAB 的真实语义禁用且不展开。没有旧投影／详情 GET，现金命令均带原预期工作区。正常读测试仅两条明确注入的 ERR_FAILED（version 与断网冷启 auth/session），503 亦为明确注入，无额外静态资源错误；已知保存中断仍只恢复原批次，不发第二笔写集。原三条导入流继续证明离线核对和完整浏览器进程重启后的结果／账本保留。
   - Cross-platform Impact Check：Web／Native 共用相同 Graph、会话及刷新交互；Web 已执行上述真实主流程，Native 已作 Host 测试及 iOS 编译，不宣称设备交互通过。最终独立 UI、工程／源码及证据门均已通过，当前无遗留 finding；最新合成数据演示 18394 已按用户位置决定改成 FAB 菜单，筛选行无刷新常驻按钮。
7. 发布：已同组维护 design、功能地图、backend README、协议资源及 apply 指引；当前菜单版浏览器、最终独立设计及工程／源码／证据门均已通过。已按最终实现核对 README、docs、references、词表、主规格／delta、接口／命令示例、配置／迁移；没有新术语、持久格式或公开配置，其余无需修改。strict 27/27、doctor healthy，6 份文档的 124 个本地目标／36 个锚点及 diff 检查通过。按原分支授权提交推送；本片可独立回退代码，不删除规范事实、永久收据或本地待处理材料，旧读取路径退役另行完成。用户选择将刷新收进 FAB 菜单，减少筛选区的常驻操作；本片已按此呈现。此选择限于当前账本操作区，已有 UI 主次与复用规则足够，无需新增“所有刷新必须进 FAB”的通用规范。保持撤回额外证明及原映射保存规则。独立完成性核对后关闭 7.1–7.4、8.2／8.4，当前整体 44/71；不归档、不建 PR，整个 change 仍待 离线写入限制、完整逐项结果与旧路径退役。


### 服务端退款商户文本共享接线（1.1／1.4／1.5）

1. 思考：服务端退款排序规则已共享，生产配置仍重复 Java word 过滤及 ROOT 小写；本片只消除该适配分叉，保留原金额、时间与匹配语义，不改变导入范围。
2. 计划：将已验证的共同商户函数作为唯一生产入口，两端复用；原 JVM 表达式仅留在独立测试。冻结字符数据、边界游标、21,309 原向量和全标量摘要均不修改。
3. 任务拆分：先新增跨模块入口的失败回归；逐码点和多 Σ／标记／连接符／补充平面／非法 UTF-16 上下文对照原表达式，再比较实际后端完整退款提案。运行域层跨运行时原完整语料及后端回归，独立 5a 和适用 5b，核对文档。
4. 构建：shared-merchant-red 先以跨模块入口不存在的 UNRESOLVED_IMPORT 编译失败；将原函数直接改名公开为 normalizeCashMerchant，backend 与 local 使用同一函数引用，不增加包装器。既有 common／JVM 测试仅替换名称，原断言、字符数据、21,309 原向量与全标量摘要保持。原 Regex／Locale.ROOT 只在独立 JVM 测试存在。
5. 审查：独立 5a 核对本片工程检查面、全部调用点、函数实现只改名称／可见性、原字符表及完整夹具字节保持、完整提案与原金额／时刻适配均无 finding。5b 通过 codebase-degradation-review 判断无领域模型、DTO、数据库、后端接口或 UI 变化，契约及 UI 专项不适用；不以纯函数可见性误造新业务端口。最终独立源码／证据门已通过，无遗留 finding。
6. 测试与 QA：基线 HEAD f1da4eaaa40d1b63738495a126d476dc7dc022d5，2026-11-17（Asia/Shanghai）；仓库外证据根 offline-ledger-1116/local-import-lifecycle/。shared-merchant-focused 在 37 秒通过后端新增与原退款／完整管线及域层原商户／真实管线差分；编译红灯不冒称业务输出不一致。
   - shared-merchant-matrix 用 578.10 秒、127 任务（39 executed）通过完整默认 backend 1,480、domain JVM 893、JS Node／Wasm Node／iOS Simulator 各 845 项，共 4,908 项、906 份新鲜 XML 全部零失败／错误／跳过。后端 ktlintCheck／detektMain／detektTest、域层 ktlintCheck／common 与 JVM test detekt 及 iOS arm64 编译通过；NO-SOURCE 项不计实跑。新增独立 JVM 对照遍历 0..0x10ffff，22 类代表的 10,648 个双 Σ 上下文及 16 类实际退款提案分别核完整结果、余量和事件折叠；原完整向量、全标量摘要与长串回归在各运行时实际执行。有限差分不宣称全字符串形式证明。
   - shared-merchant-android 用 10.59 秒、13 任务（4 executed）完成 Android Host 的 845 项，168 份新鲜 XML 全零，Android 主代码沿同源码相称编译；与上述矩阵合计 5,753 项。实际命令、时间、当前六份 Kotlin 摘要、原数据摘要及固定 XML 在同名 command／verification／xml，verify-shared-merchant.py 核源与新鲜度后冻结。
   - 默认后端仍明确排除 HistoricalMigrationEvidence，未运行的历史夹具不计通过。为闭合版本任务，另显式在原已准备 SQLite 与真实 PostgreSQL _test 库选择 LedgerVersionContractTest、LedgerSnapshotContractTest、LedgerSnapshotParityTest；不自动启动或探测数据库，两库各 22 项、各 3 份新鲜 XML 全零，分别用 7.09／8.12 秒通过，结果独立记录在 shared-merchant-snapshot-{sqlite,postgres}，不混入默认后端计数；本片总计 5,797 方法实例。
   - Cross-platform Impact Check：共享纯算法影响 Web／Android／iOS 和后端配置，五个域层运行时实跑及 iOS arm64 编译保持原合同；无页面、路由、交互或文案变化，不新增 UI／设备冒烟结论。复用既有投影双库／算法黄金审计核完整来源查重、配对、投影及 balances 断言，并核版本常量、快照字段、派生 rulesVersion、owner 捕获与双规则游标；当前无语义变更，不推进规则版本。
7. 发布：已同组更新 design、两份功能地图与 apply 指引；最终独立证据门通过，strict 27/27、doctor healthy、5 份文档 123 个本地目标／36 个锚点及 diff 检查通过，按原分支授权提交推送；关闭 1.1／1.4／1.5，当前整体 47/71。README、docs、references、词表、主规格／delta、接口／命令示例、配置／迁移按本片核对，无新 HTTP、schema、依赖或产品规则，其余无需修改。纯入口替换可单独回退，规范事实、永久收据与本地恢复材料不变；本片无新增用户产品取舍，无需新增通用规范。用户最新“断网禁止写入”的决定由下一切片回写并落实，不与这次算法接线混合；整个 change 未完成，不归档、不建 PR。


### 离线只读与禁止提交的范围对齐（6.7）

1. 思考：用户明确断网不允许提交业务写入；离线核对与本地准备仍是本 change 的目标。现有离线身份和本地 reader 已具备，历史角色不能替代当前联网资格；现有原请求恢复材料不能因取消队列计划被删除。
2. 计划：proposal、唯一主归属 shared/offline-ledger-cache delta、导入引用与 D5 对齐。不实现离线确认队列、联网自动发送或待提交账本叠加；在线主动确认后已冻结的原操作，包括首 HTTP 前中断者，继续按原键恢复。仅回写已确认的范围，尚未宣称应用守卫完成。
3. 任务拆分：先覆盖已知离线直接调用不得冻结／POST、离线准备与核对仍可进行、原在线操作及已知成功保留、原身份核验后主动保存、迟到联网结果不得给后来会话开放写入。随后接界面动作与真实提交边界，核共享层和 Web／Native 影响；UI 按现有门禁设计／演示，存储合同按实际改变执行双库；保留原结果未知与收据版本验证。
4. 构建：待规划审查通过后实施；当前仅 artifact 对齐。
5. 审查：独立契约／范围复核指出预览写集不能误禁、原在线冻结操作的保留不能缩到已发请求，两项已原位澄清；最终五份 artifact 的独立契约／范围一致性复核已通过，无遗留 finding。实际 UI／模型及工程审查随实现执行。
6. 测试与 QA：基线 HEAD 29117914，2026-11-17（Asia/Shanghai），strict 27/27、doctor healthy、5 份文档 11 个本地目标／11 个锚点及 diff 检查通过；仅规划记录，无实现、构建或应用测试结论，不将 1.4 的算法矩阵当成本项通过。
7. 发布：本决定仅明确既定现金离线访问的提交边界，不新增通用联网框架或投资离线能力。规划一致性检查与独立复核已通过，按原分支授权提交规划；应用文档／功能地图及 apply 指引随实现同组收尾。6.7 仍未完成，整个 change 保持 active，不归档、不建 PR。


### 已知离线的业务提交边界（6.7）

1. 思考：原 `LedgerSessionLocal.offline` 标记用于恢复身份，但此前现金命令及导入冻结入口未消费该资格。本片为范围明确的 B 类内部接线：约束冷启动离线的实际调用，不新增持久状态、wire、依赖或财务计算；页面、持续网络状态及重新联网资格仍待完成，不把历史角色改为只读。
2. 计划：现金操作复用现有调用语义标记写入，读取和无副作用 POST 保留；导入及账户修改在原范围检查后、持久冻结／发出写入前检查在线资格。拒发错误保留 `network_unavailable`，不声明原操作 `not_committed`。已有成功收据仍可原批次收尾，未知原操作的正文、键及文件保持。
3. 任务拆分：先用生产仓储及真实原会话恢复复现离线写入会发 HTTP／保存冻结请求，再最小接线。回归覆盖十类现金写入、四类账户修改、导入冻结、本地准备／核对、首 HTTP 前原材料、结果未知原材料及成功收尾，并核实无副作用 POST 透传；独立 5a／5b、受影响共享三目标全量单测及 iOS 编译验证。无 UI 改动，不以此关闭 6.7。
4. 构建：实际业务红灯由 `offline-write-boundary-red-behavior` 的三项全失败固定；补充恢复的 `offline-write-boundary-red-recovery` 五项四失败一通过，已保存成功收据的收尾原本就可用。原 XML／测试源码分别冻结在仓库外同名 `-frozen/`／`-xml/`。现金 HTTP 工厂显式接入 write 标记；原导入句柄的必填 `checkWrite` 在原恢复资格下检查，装饰器保留同一闭包。输入仍在首次挂起前复制。15 处 Host 直构造测试显式提供在线资格，不为适配改成默认空守卫。旧账户成功／刷新测试改为在线准备，真实 parse-only 401 仍从离线恢复触发；原断言和资源围栏保持。
5. 审查：独立 5a 核十类现金及四类账户入口、新冻结、取消／原代次、首次挂起前复制、原未知与成功处理，无遗留 finding。独立 `codebase-degradation-review` 调度实际 `contract-design-review`，检查恢复状态、原会话生命周期与错误语义，无 finding；没有新增 wire、schema 或领域模型，复用原写入语义及资格，不新增通用联网框架。UI 未改，UI 复用、Hallmark 和设计专项不适用。最后补充的维护 preview 输入按原 edit_entry 合同提供 entry 及修订，未修改生产行为。
6. 测试与 QA：基线 HEAD `7a3000d6844b505743c997c12a8661d05ec326e6`，2026-11-17（Asia/Shanghai）。仓库外证据根 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1116/local-import-lifecycle`；`offline-write-boundary-green` 四类受影响 Host 回归 186 项通过。最终 `offline-write-boundary-matrix` 以 Temurin 21、Chrome Headless 157 在根目录执行 `./compose/gradlew -p compose -I <quality.init.gradle> -I <stable-file-input-self-contained.init.gradle> :shared:testAndroidHostTest --rerun :shared:jsBrowserTest --rerun :shared:wasmJsBrowserTest --rerun :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosSimulatorArm64`，附既有单 worker／非 daemon／无缓存／in-process／3 GiB 参数，142.66 秒成功。Android Host 107 类／1,078 项，JS 浏览器 106 类／1,027 项，Wasm 浏览器 108 类／1,033 项，共 3,138 项、321 份新鲜 XML，全部零失败／错误／跳过；11 个 Kotlin 源码 SHA、命令、时间、退出码、固定 XML 和六个关键回归的实际执行由 `verify-offline-write-boundary.py` 及同名 verification 核实。既有显式 init 排除依赖外部服务的 Kotlin*ResponseIntegrationTest 与 KotlinPortfolioLiveIntegrationTest，不把本次单测当作这些集成用例通过。iOS arm64 主代码与 Simulator arm64 测试源码编译通过，未执行原生设备交互。客户端提交守卫未改服务端 Application Service 或持久化规则，PostgreSQL 矩阵不适用，不借用旧双库证据。
7. 发布：D5、四份功能地图及 apply 指引已按实际边界核对；README、参考资料、词表、主规格／delta、接口／命令示例、配置与迁移无新增概念或协议，其余无需修改。用户选择离线不提交已写入对应 change 合同，无需再沉淀为其他通用规范；继续保留撤回来源／映射证明及原映射保存规则。Cross-platform Impact Check：Web／Android／iOS 共享调用边界受影响，以上三目标共同回归及相称原生编译覆盖；页面、路由和用户文案未改，真实产品页面冒烟随后续 UI 接线完成，本片单测不冒称页面验收。strict 27/27、doctor、七份文档的 248 个本地目标／42 个锚点与 diff 检查通过；独立最终证据门已核 11 份源码摘要、321 份新鲜 XML、18 文件范围及文档边界，无遗留 finding。按原分支授权仅提交推送本片，排除 `.commandcode/`。代码回退保留在线已冻结材料，无新迁移。冷启动离线拦截不代表持续网络变化、页面禁用或重新联网验证已完成，6.7 和整个 change 保持未完成，不归档、不建 PR。

### 原会话的持续离线资格（6.7）

1. 思考：冷启动离线门已完成；当前原恢复资源的 bool 仅在 restore 时赋值，在线使用中的实际断网不能封闭后续写入，也没有同源状态供页面消费。本片为 B 类共享会话资格接线，不改财务、持久化或 wire。
2. 计划：由原恢复资源持有可观察的已知离线资格，应用仓储透传当前资源的流；只由原作用域真实 transport failure 封闭，原句柄直接消费同一值。成功读取／事实刷新不清离线资格，实际 session 核验与新资源发表才重开。取消／程序错误／权限拒绝不冒称断网，迟到原失败不能改变后来会话。传输来源只在真实 HTTP 执行／响应读取边界标记，保留原消息／cause 和 `IOException` 兼容；本地文件、凭据及数据库 I/O 不按断网处理，parse-only 包装保留可识别的原传输来源。
3. 任务拆分：先回归在线动作的网络失败→同会话后续写入零 HTTP、读取刷新不能重新授权、显式实际会话核验才重开、迟到失败不污染新登录，以及真实导入句柄的账户故障→新冻结拒绝、本地 I/O 不封闭与远程解析断网封闭两组相反回归。再接可观察资格、独立审查、三目标共享测试与相称原生编译。页面禁用、操作前 UI 资格和重新联网完整流程仍随后续 UI 验证完成，不关闭 6.7。
4. 构建：`live-offline-qualification-red` 三项真实回归两失败一通过，25.40 秒，固定在线故障后仍可写的缺口；原 XML 与测试源码在仓库外同名 `-xml/`。实际 HTTP 执行／响应读取用内部 FinanceTransportFailure 标记；原拒绝／取消边界保持，不在本地存储访问上标记网络。会话只沿该 marker、network_unavailable/status0 的 DomainFailure 及明确 parse-only 包装追溯，允许协程同类包装复制，任意 I/O 或其他原因链不接受。九份 Kotlin 源码实现单源 StateFlow、当前发表流切换、原资源／代次归因及异常传播。四处既有未知恢复用例补实际 session 核验并从新句柄恢复，相同正文／键及请求次数断言保持。JVM 协程可能复制异常；文件及结果故障断言核对完整原因链中仍存在原异常对象，不要求异常只能包装一层。
5. 审查：独立 5a 核原 owner／generation、在线失败与冷启动、只读刷新、未知／已成功操作、取消及原 token，生产无遗留 finding。独立 codebase-degradation-review 调度实际 contract-design-review：单一资格不复制角色或 Session 身份，无新增 wire、schema、持久状态或万能联网框架；初版把通用 I/O 当作网络的 major 已由 HTTP 来源 marker 与相反回归关闭，parse-only 的协程包装沿明确类型闭合。文档 P3 已把拒绝正文中断规则限定为保留状态的严格请求入口，普通 API 沿原 saved-response 合同。UI 未改，UI 复用、Hallmark 和设计专项不适用。
6. 测试与 QA：基线 HEAD `f86c48761339e7ed22c8e288a76ce8adfebb6aeb`，2026-11-17（Asia/Shanghai）。证据根 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1116/local-import-lifecycle`；`live-offline-qualification-focused-final` 六类受影响 Host 回归 211 项通过，47.23 秒。最终 `live-offline-qualification-matrix-final` 在根目录执行 `./compose/gradlew -p compose -I <quality.init.gradle> -I <stable-file-input-self-contained.init.gradle> :shared:testAndroidHostTest --rerun :shared:jsBrowserTest --rerun :shared:wasmJsBrowserTest --rerun :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosSimulatorArm64`，附既有单 worker／非 daemon／无缓存／in-process／3 GiB 参数，106.09 秒成功。Android Host 107 类／1,084 项，Chrome Headless 157 的 JS 106 类／1,033 项和 Wasm 108 类／1,039 项，共 3,156 项、321 份新鲜 XML，全部零失败／错误／跳过；九份源码 SHA、命令／时间／退出码、固定 XML 及十一项关键回归的实际执行由 `verify-live-offline-qualification.py` 与同名 verification 核实。iOS arm64 主代码在前轮同源码编译实际执行，最终轮为 UP-TO-DATE；验证器逐一核对前后生产源码 SHA 相同，Simulator arm64 测试源码在最终轮实际编译通过。本次仍显式排除依赖外部服务的 Kotlin*ResponseIntegrationTest／KotlinPortfolioLiveIntegrationTest，不把其未运行算通过。未改服务端 Application Service 或存储行为，PostgreSQL 矩阵不适用；未执行原生设备交互。
7. 发布：D5、四份功能地图及 apply 指引已按当前内部状态原位更新；核对 README、references、词表、主规格／delta、公开接口／命令示例、配置及迁移，无新概念、wire、依赖或服务端 schema，其余无需修改。用户离线不写入的决定保留在本 change，不新增通用规范；撤回额外来源／映射证明及原映射保存规则保持。Cross-platform Impact Check：Web／Android／iOS 共用会话流及 HTTP 异常来源，以上三目标共同回归和原生编译覆盖；页面、路由和用户文案未改，产品浏览器冒烟随实际 UI 接线执行，不把真实浏览器单测冒称产品流程验收。strict 27/27、doctor、七份文档的 248 个本地目标／42 个锚点及 diff 检查通过。独立最终证据门已核九份源码摘要、321 份新鲜 XML、16 文件范围及文档边界，无遗留 finding。按原分支授权提交推送本片，排除 `.commandcode/`；无新迁移，回退代码保留原材料。离线值为 false 不单独授予权限，页面仍需原身份、范围、角色及就绪资格；UI、重新联网完整流程及整体 6.7 未完成，不归档、不建 PR。

### 离线写入限制的界面与重新核验（6.7／10）

- 2026-11-17 补充 Web 标签页可见性：新增 `rememberPageVisible` 的 expect／actual，Android／iOS 用 `Lifecycle.RESUMED`，JS／Wasm 用 `document.visibilityState` + `visibilitychange`；轮询与离线重连都改为消费该信号，三目标编译通过。
- 2026-11-17 用户选择方案 A：前台且账本页可见时每 10 秒（±2 秒抖动）做一次条件版本检查，版本变化才拉快照并重载列表；后台／离线／核验中暂停。已加 `pollOnce` 回归与页面可见性接线，未改服务端契约。
- 2026-11-17 用户补充：离线与重试进行中共用同一文案“当前断网，只能浏览本地数据；联网后将重新同步”，已删除独立的“正在重新连接”文案并同步 design 与 QA e2e。
- 2026-11-17 用户补充：认证成功但随后接口报系统错误（非网络错误）时停止自动重试，显示手动“重试”按钮；已按 `accessState.errorCode != network_unavailable` 区分，自动重试仅在离线、前台且无系统错误时运行。
- 2026-11-17 用户决定：不显示手动“重新连接”按钮，改为后台按 2／4／8／10 秒退避自动重连，成功后连接提示消失；重连只恢复写入资格，不发送未确认内容。已同步 design、feature-map、四套文案与 QA e2e（不再点击重连，改为等待自动核验）。

1. 思考：现金／账户／导入业务守卫及可观察资格已完成；本片消费实际资格，其他工作区服务写入守卫仍须独立补齐，导入准备与服务端保存须分别表达。按原 A 类 change 的本轮 UI 切片执行，不改变原商户、来源或映射合同。
2. 计划：D5 已列离线／核验中／已核验动作覆盖表，UI 角色保持，连接说明在当前可见任务区域复用，不在筛选栏加刷新。M3 正文及实际依赖已核；Hallmark 沿现有 Finance token 原位接线，无页面重构、无删除、无需 HTML 原型。
3. 任务拆分：先回归实际会话核验防连发与迟到发表；接共享 UI 资格、导入确认／账户写入及现金动作，保留本地准备与核对；相称编译及真实浏览器代表流程后立即演示，再按反馈执行独立 UI／契约／Hallmark 与完整回归。不提前关闭 6.7／10。
4. 构建：会话核验单飞／迟到、离线币种 no-op／批量落键跨离线、本地恢复失败及撤回账户读取跨离线均由失败回归驱动。在线已冻结撤回的首次落键与 send 二次落键暂停窗口另有回归：两处均保原 key／command，断网零发送，恢复在线不自动发送，显式重试只使用原键、修订及 patch。只有未形成完整扩币批次的本轮部分键可清理。界面复用原生禁用与既有无障碍适配；未就绪不伪装忙碌，保留安全退出。
5. 审查：独立 5a 和 5b 分别覆盖范围／工程、内部契约及 UI 复用，当前无遗留 finding。账户已冻结单键与尚未成批部分材料的区别已按 D5 复核；领域事实、存储、wire 及 DTO 不变。独立设计／Hallmark 实读九张稳定截图并操作 390px 真浏览器键盘，0 critical／major／minor；Tab 可达重连，Enter 触发实际核验，在途 aria-disabled=true，全程零业务 POST、零 pageError。四套本地化完整，沿原 token、组件及角色，不新增按钮包装。
6. 测试与 QA：HEAD 比较基线 d3508372，2026-11-17 02:18:57–02:21:45（UTC+8）的 offline-ui-matrix-final 执行 shared Android Host、jsBrowserTest、wasmJsBrowserTest，分别 107 类／1092 项、106 类／1041 项、108 类／1047 项，共 3180 项、321 份新鲜 XML，零失败／错误／跳过；25 份当前 Kotlin 摘要逐一匹配，iOS Arm64 生产与 Simulator Arm64 测试编译实际执行。命令使用既有 run.py、quality.init.gradle、stable-file-input-self-contained.init.gradle，每项测试 --rerun；证据在仓库外 offline-ledger-1116/local-import-lifecycle 的同名前缀 command／exit／verified／xml／log。Chrome 1440×1100 的 browser-offline-ui-verified 三条真实 Compose 流程全部通过，覆盖正常／冲突重核／成功收尾、离线禁用及物理点击零 POST、显式重连零自动提交、auth 成功但本地恢复 503 后禁保存／可关闭／可再次核验、冷启动账本与结果。原 browser command 的 testSha 实际计算 ledger 用例，原记录保留；另以 source-evidence.json 核十二份 trace 均嵌入当前 import TS。该浏览器范围不覆盖最后的撤回冻结修正；该修正已由三目标实际单测覆盖；最新演示包经 freshness gate 重建后，账本／离线创建／重连再次实跑，零 pageError、在途 aria-disabled=true，原审查截图另存不覆盖。Node QA 32／32、strict 27／27、doctor 健康，九份文档的 275 个本地目标／45 个锚点通过；UI-only 与内部资格无存储变更，不新增双后端矩阵，原生设备 QA 按默认范围不运行。
7. 发布：首版 QA 演示 http://127.0.0.1:18402/__qa 已按 UI §4.3 交用户待呈现方向确认；不重复询问断网禁止写入的决定。Cross-platform Impact Check：共享页面、连接资格与控件在 commonMain，Web／Android／iOS 均受影响，由真实 Web 冒烟及相称原生编译／单测覆盖。已同组更新总览与现金／导入／共享／账户／工作区地图及 apply 指引；README、references、公开接口示例、配置／迁移和词表没有新增概念或需要修改的合同。用户离线不写、保留 FAB 刷新及成功批次原地恢复的决定留在本 change，无需新增通用规范。已按原分支授权作为检查点提交推送；演示方向确认仍待用户反馈，工作区实际服务写入守卫及其他剩余任务另行完成，6.7、10 及整个 change 保持 active，未归档、不建 PR。

### 工作区实际写入的离线守卫（6.7）

1. 思考：工作区界面已限制，SessionCacheRepository 的七个业务修改入口尚未消费原离线资格；仅 UI 禁用不满足原验收。
2. 计划：沿 D5 补实际入口检查与原凭据绑定；成员写入与原会话切换串行，保留读取及核验，不改 wire／存储或角色。
3. 任务拆分：先复现七入口已知离线仍调用 delegate，再验证实际 HTTP 断网封闭原资源、后续零请求及固定原凭据；补原会话排队与关闭期间退出回归，完成独立复核、三目标验证及文档收尾。
4. 构建：offline-workspace-write-red-valid 的 55 项中三项真实失败；固定凭据的三个 AuthApi 内部重载沿原路径与正文，SessionCacheRepository 在等待串行锁前捕获原发表对象，锁内核原身份／代次／离线资格。offline-workspace-race-red 的 57 项中两项失败，覆盖七入口排队等核验及关闭期间退出；已在关闭返回后、HTTP 前加操作代次围栏。失败 XML 与测试源码保留在仓库外同名前缀目录。
5. 审查：独立 5a 按工程检查面及独立 codebase-degradation-review／contract-design-review 均已关闭排队重选会话和关闭后失效发送两个 P1，无遗留 finding。成员发送前再次检查原在线资格；未添加领域状态、wire 或存储模型。无新增 UI，UI 复用与设计专项不重复执行。
6. 测试与 QA：offline-workspace-race-green 以 31 秒通过 SessionCacheRepository、AccessViewModel、WorkspaceManagementViewModel 的受影响 Host 测试；最终 offline-workspace-matrix-final 在基线 d35083723b441f57ec357374cc8a261bf36d89e0、2026-11-17（Asia/Shanghai）以 165.39 秒通过 Android Host 107 类／1097 项、JS 浏览器 106 类／1046 项、Wasm 浏览器 108 类／1052 项，共 3195 项、321 份新鲜 XML，全部零失败／错误／跳过；iOS Arm64 生产及 Simulator Arm64 测试编译通过。命令由既有 run.py 执行 shared 三项目标 --rerun 及两项 iOS 编译，沿 quality 与 stable-file-input 初始化；同名前缀 command／exit／verification／xml 核准全部 27 份当前 Kotlin 摘要和五项新增回归在各目标的执行。该矩阵基于当前合并工作树（含尚待呈现确认的 UI），不把未确认 UI 计为已交付；本片提交只含四份实际服务／测试文件及相应记录。strict 27／27、doctor healthy、九份文档 275 个本地目标／45 个锚点及 diff 检查通过。无服务端存储变更，双后端矩阵不适用。
7. 发布：实现、独立复核和适用验证已通过，按原分支授权单独提交推送实际服务守卫，保留待确认 UI 改动。已核对工作区地图、总览、apply 指引及 D5；README、references、词表、接口示例、配置／迁移无新合同需改。守卫可单独回退，原缓存、恢复材料和永久收据不变。Cross-platform Impact Check：实际守卫位于 commonMain，影响 Web／Android／iOS，同一资格及原会话合同保持；不新增页面、控件或交互。没有新增用户业务取舍，无需新增通用规范；整个 change 保持 active，不归档或创建 PR。

### 旧物化投影 HTTP 读取面退役（4.1／4.2）

1. 思考：应用在线与离线已经改读本地规范快照，旧列表、详情、证据、物化定位和运维状态／重建路由不再是生产读取回退。按既定 D4 删除六个路由；本片不删除表结构、内部旧实现或旧暂存导入。
2. 计划：保留规范快照、原键写集提交与收据，以及规范维护／分类／删除。旧方法不可用：保留同路径 DELETE 的列表 GET 返回 405，其余五项返回 404；不增加兼容转发或新聚合接口，双库核规范事实和版本不变。
3. 任务拆分：先在真实 SQLite／PostgreSQL 的写集 HTTP 合同中新增失败回归，检查六个旧入口状态并对照规范快照、永久收据和同键重放；再删除路由，核调用方、OpenAPI 与文档，将迁移兼容条款与已确认退役范围对齐，完成独立工程／契约复核及相称后端回归。
4. 构建：projection-routes-red 双库两项均真实失败，六个旧方法仍返回 200／503，规范事实、版本与原收据断言已通过；原 XML／源码冻结在同名 -xml。FinanceModule 仅移除六个路由，OpenAPI operation 精确由 91 减为 85，同路径 DELETE 与其他规范写命令保留。三个仅含 parameters 的空 PathItem 已清理，前后全部 operation 内容相同。
5. 审查：独立 5a 及实际 codebase-degradation-review 调度的 contract-design-review 均无遗留 finding；核生产 Graph 的本地读取、六方法退役、其他方法保留及双库事实／收据。迁移兼容条款按原 proposal BREAKING 范围同步例外，独立 5a／5b 确认无新范围；未执行的历史 HTTP 证据不计通过，4.4 仍待清理。综合报表混合依赖归入 4.3 待决边界，不扩大本片删除范围。无 UI 改动，UI 专项不适用。
6. 测试与 QA：比较基线 c1afac1ec059ddc95f3dc5d7cf7091554710c81b，2026-11-17（Asia/Shanghai），仓库外证据根 offline-ledger-1116/local-import-lifecycle。projection-routes-green-final 的双库 HTTP／OpenAPI 50 项通过；projection-routes-backend-final 用 329.88 秒完成默认后端全量 204 类／1482 项，零失败／错误／跳过，ktlintCheck、detektMain／detektTest 通过。默认排除 HistoricalMigrationEvidence，已退役接口的历史兼容用例不计通过。verify-projection-routes.py 核准本片三份后端 Kotlin 摘要、processed OpenAPI 资源与全部新鲜 XML。随后仅删三个空 PathItem，operation map 内容保持；projection-routes-contract-final 再以 10.45 秒通过双库 HTTP／OpenAPI 50 项及当前资源核验。无存储结构变更；同一 financeModule 真实服务实跑 SQLite 与已准备 PostgreSQL _test，不探测或启动数据库。strict 27／27、doctor healthy、十四份文档的 338 个本地目标／60 个锚点及 diff 检查通过。
7. 发布：待最终文档／证据门后按原分支授权提交推送。Cross-platform Impact Check：只删除后端已无生产客户端依赖的旧读取方法，Web／Android／iOS 共享读端口、DTO、页面及交互未改，不重复 UI 演示、原生编译或设备 QA；待确认 UI 保留工作树。README、现金流水说明、精确接口参考、总览／现金地图、proposal／design／迁移 delta 与 apply 指引同组更新；词表、references、账户／解析配置和迁移无新本片合同需改。无需新增通用规范，综合报表去留按待决边界处理。旧路由可随代码独立回退，无需数据回滚；物化表／内部旧实现与旧暂存导入继续按 4.3–4.5 完成。本片关闭 4.1／4.2，整体 49／71，保持 active，不归档或创建 PR。

### 旧导入退役的共享依赖拆分（4.5）

1. 思考：旧导入编排与维护／新写集共用提交诊断，原操作差分又通过 HTTP 夹具加载数据；直接整删会丢失仍在范围内的能力及独立 oracle。本片仅分离归属，不改变业务计算、提交、wire 或存储。
2. 计划：原提交边界逐字移至独立文件；测试数据库、原文档和故障探针独立为数据夹具，纯操作／快照对照不构造旧 HTTP／CommandService。旧 HTTP 夹具继承同一数据能力，仅在实际测试访问时惰性构造旧 service／commands，Session HTTP 夹具保留所需辅助方法；旧链与暂存收窄仍属后续 4.5。
3. 任务拆分：先固定原 13×16×2 共 416 组语料及只读／校验基线；将现有纯规则测试改用独立数据夹具并见编译红灯，再完成无语义提取。受影响维护／写集／诊断／原始差分回归、独立工程及适用契约复核、文档收尾。
4. 构建：retirement-fixture-baseline 先跑通 40 方法及全部 416 比较；现有三处纯规则测试先改用尚未定义 Data 的结构红灯为 UNRESOLVED_REFERENCE，不冒称业务输出失败。提交边界、数据／故障／文件解码块及五个 JSON 扩展移动；API 夹具编排保持，仅增加线程安全 lazy。Session HTTP 夹具保留 API 型辅助能力。最终 retirement-shared-dependencies-normalized 在 15.91 秒通过同一 40 方法和 416 比较及 ktlint；两个新文件仅规范化尾空行，语义块未改。
5. 审查：独立 5a 按工程面核源码块、作用域、原输入／断言、单实例及故障／时钟闭包，无 finding。惰性初始化不冻结 now、mode 或 activeFiles，仍复用同一 commands。独立 codebase-degradation-review 判断本片无模型、wire、存储合同或 UI 变化，不触发契约／UI 专项；未把编译错误的中间 green 计为通过。
6. 测试与 QA：比较基线 c364a4cd45de9386c7262718190b409a4df2f6fa，2026-11-17（Asia/Shanghai），证据根在仓库外 offline-ledger-1116/local-import-lifecycle。当前源码 retirement-shared-dependencies-current-test 以 202.78 秒完成默认后端 204 类／1482 项，零失败／错误／跳过；verify-retirement-current-backend.py 固定全部新鲜 XML、当前源码和资源摘要。此前 backend-final 的 ktlintCheck、detektMain／detektTest 已通过，尾空行规范化后的 normalized 再跑 ktlint。默认仍排除 HistoricalMigrationEvidence。另显式设置 FT_KOTLIN_IMPORT_ORIGINAL_OPERATION_CONTRACT_DIR 为 retirement-original-operations，normalized 的 migrationEvidenceTest 选择三类测试实跑 40 方法及其 416 组比较，15.91 秒通过；不把历史方法计入默认全量。13 份原输入字节及原数据库／WAL 摘要留在 source-manifest，仅在隔离副本准备 SQLite，不改原证据。verify-retirement-extraction-normalized.py 核提交边界与五个 JSON 扩展仅删一个尾空行，数据／故障块逐字保持，API 只增加 lazy，三处测试只替换夹具类型且断言不变。当前源码侧证补齐暂存后运行清单遗漏的文件，不将规范化前的 class 摘要宣称为当前字节通过。strict 27／27、doctor healthy、九份文档 278 个本地目标／45 个锚点及 diff 检查通过。
7. 发布：最终独立证据门通过，当前八份后端源码与暂存字节一致；按原分支授权提交推送。Cross-platform Impact Check：同包内部实现位置及测试构造归属变化，无 wire、存储、共享／Web／Native 页面或交互变化；不重复浏览器、原生编译或设备 QA。原有双库写集／维护合同在默认后端中继续实跑，本片无新增存储行为。已核 README、docs、references、功能地图、词表、接口样例及配置／迁移，本片不改变现状行为，无需新增用户说明；只更新 design／tasks／apply 指引。没有新增用户产品取舍，无需新增通用规范；可整体回退本片代码，不改事实、永久收据或本地批次。旧服务端导入链与暂存收窄继续完成，4.5 保持未完成，整体仍为 49／71，不归档、不建 PR。

### 退役前保留当前 HTTP 合同（4.5）

1. 思考：旧导入全流程测试中仍混有独立账户币种命令；临时存储容量错误也由旧预览入口测试。先把仍保留的合同从旧链测试中分离，不能随旧入口一起删除。
2. 计划：账户币种的两库测试独立成类并保持全部断言；三项容量／存量 JSON 错误改由实际维护计划读取触发，保留状态、错误正文、重试资格、一次读取及事实零写入断言。不改生产错误处理、文案、接口或存储。
3. 任务拆分：先实跑原五项为基线，再移动现有测试、改维护路由并运行新归属；核对原断言、旧导入四项仍在、独立复核与相称静态检查。测试迁移本身无待修复生产缺陷，不制造业务失败；完整旧路由删除另按先红后绿执行。
4. 构建：retirement-current-http-baseline 的五项先通过，包含真实 PostgreSQL。币种完整函数及四个数据库／响应 helper 逐字搬迁，旧四项上传／追加测试保持；容量测试只换类名、服务注入及维护 GET 路由，原断言逐字保持。搬走后旧文件的无用 import 已删除；中间 ktlint 失败不计通过。
5. 审查：独立 5a 静态复核无 finding，核对两库、错误来源、重试资格、零写入及原断言；最终独立证据门核三份测试源码与暂存字节、两份新鲜 XML 均一致，无 finding。防劣化范围为测试归属迁移，无生产模型、wire、存储行为或 UI 改动，独立 codebase-degradation-review 判断不触发契约／UI 子专项。
6. 测试与 QA：比较基线 3959df72d4971abb4b3a9f959f4b96cf334523d8，2026-11-17（Asia/Shanghai），仓库外 offline-ledger-1116/local-import-lifecycle 的 retirement-current-http-final 以 129.77 秒完成两类／五项，零失败／错误／跳过；ktlintCheck、detektTest 通过。显式 FT_TEST_POSTGRES_URL 指向已配置的专用测试环境，币种 PostgreSQL 方法实际运行，不将 skip 算通过。final-verification.json 固定当前源码、新鲜 XML 摘要及源码块证明；没有改生产字节或规则，不重复全量后端、共享端编译或浏览器。OpenSpec strict、九文档 278 个本地目标／45 个锚点及最终 diff 检查通过。
7. 发布：最终独立门通过，按既有分支授权小步提交推送。Cross-platform Impact Check：仅后端测试归属与故障触发入口调整，无 Web／Native／共享层产品代码或呈现变化；不触发 UI 演示或设备 QA。已核 README、接口参考、词表、功能地图、配置及迁移，行为和公开合同保持，无需改用户说明；仅更新 design、tasks 及 apply 指引。无新增用户取舍，无需沉淀通用规则；可整体回退本片测试。4.5 和整个 change 保持未完成，不归档、不建 PR。

### 旧暂存导入 HTTP 入口退役（4.5）

1. 思考：退役目标是 Main 的旧导入服务注入、十一项会话／计划方法及 resolve 的 ip_ 分支。生产客户端已走本地生命周期与新写集；本片落实既有 BREAKING 范围，其他接口、原事实和永久收据保持。
2. 计划：删除生产注入、旧路由及 ip_ 分支和相应 OpenAPI 方法；移除 financeModule 的旧服务参数，不留下无效注入。旧生产编排及纯规则仍按实际调用归属后续清理，本片不单独关闭 4.5。
3. 任务拆分：双库退役测试先红，核十一项不可用、ip_ 拒绝、旧／新永久收据和原键读取、新写集重放、快照／事实／版本不变及维护 mp_ 核实。更新公开合同测试；已退役 HTTP 测试与仍有效业务 oracle 按行为分开，保留当前账户币种、维护、解析与安全合同。独立 5a／5b、完整后端与 parse-only 当前合同验证、文档回写。
4. 构建：双库失败回归真实复现 ip_ 仍返回 committed，而预期为 404；删除十一方法、生产注入及 ip_ 分支，OpenAPI 从 85 减至 74 方法。十二处测试文件仅移除无效注入参数，不新增历史排除；保留原 fixture 和纯规则对照。原月度文件测试迁至真实写集 HTTP：同一 11 文件／759 行经 JVM reader、公共文档桥与生产完整本地计算，核原独立 377 事实、382 批内重复、原键重放及新键重导，分页核实际结果身份；新增重导逐行分类及上海／UTC 月界、不串时区验证。
5. 审查：独立 5a 与实际调度的契约 5b 已核十一方法精确退役、维护／解析／永久收据保持；月度迁移复核指出新键重导仅核零写可能假绿，已补 can_commit、759 行全重复；按实际逐项 outcome=created 和逐边关系数修正测试预期，未改生产政策。文档末轮发现的后端预览、导入 TTL、旧 ORM 类、映射可空、结果粒度、程序错误普通重试及鉴权／重放顺序残句均按实际合同修正。最终独立 5a／实际契约 5b 无遗留 finding，13 份后端 Kotlin、OpenAPI 与受测／暂存摘要及 204 份新鲜 XML 均已独立核准，29 文件未混入 UI 或外部缓存。UI 专项不适用，本片不包含待确认的离线界面。
6. 测试与 QA：比较基线 `a128359128c58845f3afc50970728ff4d2b71e15`，2026-11-17 执行；仓库外证据根 `/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1116/local-import-lifecycle`。`retired-import-routes-red-final` 两库实际先红；`retired-import-routes-green` 四类 56 方法通过。最终 `run.py retired-import-routes-verified-backend :backend:test :backend:ktlintCheck :backend:detektMain :backend:detektTest` 使用外部质量配置、Java 21、单 worker／3 GiB／in-process／无 daemon 及配置缓存，在 295.62s 成功；204 类、1,481 方法，0 failure／error／skip。全部 XML 新鲜度、命令、终态、后端源码及暂存字节和 OpenAPI 摘要已核并固定为同名 `-xml`／`-verification.json`。当前 parse-only 专项 `retired-import-parse-sqlite/postgres` 各 13 方法实跑通过，分别 54.96s／54.79s，含真实 PDF／旧 XLS、权限、预算、正文清理及禁止财务 SQL；生产字节未变化，配置由外部专用 `_test` 库提供，不自动探测或启动。OpenSpec strict 全部 27 项和 doctor、文档 361 个本地目标／68 个锚点通过。历史旧 HTTP 测试不计当前通过，四格式浏览器仍按 4.4 迁移；失败断言轮不计通过。
7. 发布：README、流程总览、接口与逻辑表参考、维护暂存配置、夹具说明、导入执行技能与 reference、总览／账本／导入地图、design／tasks 及 apply 指引同组更新。词表及主规格无新概念或额外行为变化；配置名、对象前缀、实际数据库与迁移未修改。Cross-platform Impact Check：本片删除客户端已不再使用的旧后端方法，现有本地 Graph、Web／Android／iOS 页面、共享 DTO 及解析合同未改，不重复 UI 演示、原生编译或设备 QA；未确认离线 UI 保留工作树。没有新的产品取舍，无需新增通用规范。路由和文档可整体随代码回退，不需数据回滚。最终独立门通过，按同分支授权提交推送；4.4／4.5 和整个 change 保持未完成，仍为 49／71，不归档、不建 PR。

### 四格式浏览器合同迁移（4.4）

1. 思考：旧四格式页面用例等待已退役的服务端会话／计划而实际超时。迁移后真实浏览器又复现有效微信 XLSX 显示解析失败：本地读取器在 source=null 时提前 statement_unreadable，原 native 和共用 collectRows 已支持无提示表头识别，页面本来不要求用户选择来源。该局部缺口按 C 类修复恢复既定行为，不新增来源、识别规则、上传回退或 UI。
2. 计划：沿同一四份匿名 CSV／XLSX／XLS／PDF、账户选择和补 USD 场景，使用实际登录、本地准备／重载恢复、新写集及规范快照；核每格式事实行数、206 条总事实、确认前零业务写入、一次确认、永久收据和返回本地账本。CSV／XLSX 不上传，只有 PDF／旧 XLS parse-only；全程禁止旧会话／计划／投影 GET 请求。先补无 source 的共同 XLSX 原向量和真实月度字节 native 差分失败回归，再移除提前拒绝并将 nullable source 原样交给共用表头规则，原显式来源校验及安全预检保持。
3. 任务拆分：先固定旧用例失效边界，再迁移等待与断言；用真实 Chrome 和 Kotlin 服务运行，保留 trace、代表截图和网络／控制台诊断。独立工程复核测试未弱化、文档覆盖回写及相称 Node 检查。
4. 构建：共同原有效向量及三份实际月度 XLSX 无提示读取先红，均为 statement_unreadable；仅移除 null 提前拒绝及私有参数改可空，继续使用原 collectRows。四格式用例保留原文件和 75／102／11／18 分源数量、四渠道账户及真实补 USD／revision=2。恢复后精确断言四个冲突同名来源组清空、其余选择保持，只重选这四项，不改变原合并政策。原 CMP-10623 弹层镜像问题沿稳定实际几何、菜单归属、真实画面及物理确认绕行；确认前快照零流水，原响应后等待结果归档触发的事实刷新，再稳定画面并重载读取原结果，避免主动打断尚在收尾的写入。结果核已处理 206、零草稿及 pending、无整理中提示、原收据及返回本地账本；一次写集，PDF／XLS 各一次解析，无 CSV／XLSX 上传或旧路径调用。
5. 审查：独立 5a 与实际契约 5b 已核两行生产改动及新回归，无遗留 finding；自动识别与 native 相同，显式来源拒绝、歧义／缺失表头、格式／预算／XML／公式保持。浏览器复核补齐完整结果及原失败边界，按原有效选择与冲突重选表述；仅原键提交前不存在收据的精确一次 by-key 404 属预期，其他诊断要求为空。文档按现有实现和原单测校准同名分组，不新增产品规则；最终独立证据门已通过：三份 Kotlin、浏览器脚本与受测／暂存字节一致，703 份新鲜 XML 共 3,445 项全零；浏览器 trace、生产包及九项附件摘要匹配，未混入待确认 UI。UI 未修改，复用／设计专项不适用。
6. 测试与 QA：比较基线 `985b9a348aebb44289b9017b58c45a3962cac6b7`，2026-11-17，证据根沿仓库外 offline-ledger-1116/local-import-lifecycle。`xlsx-source-detection-red` 两项真实先红并固定 XML；聚焦 `xlsx-source-detection-focused` 32s 通过领域 2／后端 13 方法及两模块 ktlint。最终 `xlsx-source-detection-matrix` 使用相同 run.py／Java 21／单 worker／3 GiB 参数，183.04s、117 tasks 成功：领域 JVM 198 类／894 方法，ChromeHeadless 157 的 JS／Wasm 各 168 类／846 方法，Android host 168 类／846 方法，后端 native 差分 13 方法；共 3,445 方法，0 failure／error／skip。iOS Arm64 主代码及 Simulator 测试编译、领域与后端 ktlint 通过；detektMetadataCommonMain／detektJvmTest 实际执行通过，detektJvmMain／detektAndroidHostTest 为 NO-SOURCE，iOS 仅编译。XML 新鲜度及当前生产／测试摘要固定为同名 verification／xml。`browser-four-format-final` 在真实 Chrome 157、1440×3000、http://127.0.0.1:18403/、当前生产预览和 Kotlin 18244／独立外部 SQLite `_test` 库实跑，1 项 49.1s、全程 56.1s，0 失败／skip／重试；trace、账户／恢复／核对／结果／账本截图及命令、结果已保存，错误检查除原键预查 404 外全部为空。Node helper 32 项全绿，32.48s。未重复无变化的存储双库矩阵或原生设备 QA；旧 11 文件双库回归与 parse-only 仍是独立证据，不冒充本次浏览器。
7. 发布：最终独立门通过，按同分支授权提交推送。OpenSpec strict 27 项、doctor 及文档 309 个本地目标／60 个锚点检查通过。已核 README、流程与精确接口参考、配置／迁移、词表和导入技能；本片不扩字段或配置，更新实际 XLSX 行为、夹具说明、总览／导入地图及 design／tasks／apply 指引。Cross-platform Impact Check：Web／Android／iOS 共用 XLSX 入口，JVM、JS／Wasm、Android host 实际测试和 iOS 相称编译已覆盖，不追加设备 QA；页面、文案和布局未改，不新增 UI 演示。既有离线界面待确认部分仍留工作树，本步不提交。无需新增通用规范，本片只恢复既定自动识别与沿原合并规则核对；代码和测试可整体回退，不需数据回滚。4.4／4.5 与整个 change 保持未完成，49／71，不归档、不建 PR。

### 账户映射建议的当前写集验证（4.5）

1. 思考：原建议测试四项仍通过旧 HTTP 服务发布映射，删除旧编排前必须保留完整账号别名、歧义、归属、文件隔离及资格断言。本片为既有 A 类 change 内测试归属迁移，不改生产政策、协议或映射保存规则。
2. 计划：保留原十一份场景和全部业务断言，改用独立数据夹具；原隔离测试沿真实规范快照、生产本地完整计算及新写集 Application Service 提交。建议同时对照原只读 SQL 适配和当前本地快照规则，保留查询只读及事实不变断言，不额外上传资金组或来源证明。
3. 任务拆分：先跑旧四方法基线，再迁移原场景并执行当前四方法；检查不再构造旧 HTTP／Command 服务，独立工程及防劣化范围审查、相称静态检查、文档和提交收尾。测试迁移没有待修复生产缺陷，不制造业务红灯。
4. 构建：mapping-retirement-baseline 的原四方法先通过；同十一场景改用独立数据夹具，原隔离提交走当前完整本地计算和实际写集服务。只读 SQL 与本地快照建议逐 Map 比较；保留全部原断言，确认前全库零写，原键重放回同一完整收据并核前后全库相等。未构造旧 HTTP／Command 服务或写暂存对象。
5. 审查：独立 5a 核四方法、2+2+4+3 共十一场景及显式 owner／文件局部隔离，静态无阻断；按复核建议增加重放前后完整数据库相等。独立 codebase-degradation-review 判断仅测试迁移，不触发生产契约或 UI 子专项；最终独立证据门通过：当前／受测／暂存源码一致，新鲜 XML 四方法全零，十一份原输入与源库摘要逐一核准，未混入待确认 UI。
6. 测试与 QA：比较基线 145f9741d60933ece3ea265cd145c8161ea64153，2026-11-17（Asia/Shanghai），仓库外证据根 offline-ledger-1116/local-import-lifecycle。沿既有 run.py／Java 21／单 worker／3 GiB 配置，FT_KOTLIN_MAPPING_SUGGESTION_DIR 显式指向 mapping-retirement-final，执行 :backend:migrationEvidenceTest --tests '*CashMappingSuggestionOriginalTest*' :backend:ktlintCheck :backend:detektTest，123.88 秒成功；四方法／十一场景，零失败／错误／跳过。ktlint 当前两行测试增补复用前轮已通过缓存，detektTest 实跑；源码摘要、新鲜 XML、命令及终态固定于同名前缀 verification／xml，验证脚本再次核原输入和源数据库摘要保持。OpenSpec strict 27／27、doctor healthy、九文档 281 个本地目标／45 个锚点通过。原始场景与数据库仅只读，仓库外按原字节备份为独立副本，只补当前 workspaces.ledger_seq=0；所有原表／列逐值一致。无生产存储、Web／Native／共享 UI 改动，不重复浏览器、原生编译或存储双库矩阵；本次 SQLite 迁移证据不宣称 PostgreSQL 或完整 change 通过。
7. 发布：最终独立门通过，按原分支授权小步提交推送。已核 README、references、接口／命令示例、配置／迁移、词表和功能地图，无生产行为或用户功能变化，无需改现状说明；只更新 design／tasks 及 apply 指引。Cross-platform Impact Check：仅后端测试装配改变，Web／Native／共享层代码、页面和合同未改，不追加跨端构建或 UI 演示。可整体回退测试和记录，无数据回滚。无新用户取舍或通用规范；4.5 和整个 change 保持未完成，不归档、不建 PR。

### 旧会话与计划 HTTP 迁移测试清理（4.4）

1. 思考：CashImportHttpContractTest 与 CashImportPlanHttpTest 的九方法仍要求已退役的十一项 HTTP 方法成功；它们不再验证当前公开入口。独立逐方法核对后，将共用的严格元数据安全矩阵先迁至 PDF／XLS parse-only，其余旧会话／计划包装断言退役。该测试清理不改变生产行为。
2. 计划：删除两类，保留所有独立 PlanView／本地 reader 黄金、资源、完整 Python 命令状态 oracle、真实银行解析和内存寿命测试。PDF／XLS 保留原无效元数据用例，补原未知字段、数组类型、重复 ref、缺文件列表、part 对应、预算、非法时间及非 multipart 请求检查，核 422／安全错误码、解析器零调用、全库零写。
3. 任务拆分：先跑现有两个无效元数据方法为基线；扩当前 parse-only 测试后删旧类。按当前入口分别在显式 SQLite／真实 PostgreSQL 测试环境实跑，保留 fresh XML；独立范围／工程与防劣化复核及文档收尾。不把已退役 HTTP 的失败改成跳过或当前通过。
4. 构建：原两个无效元数据方法先通过；各保留原四／五组输入并追加八组 multipart 拒绝和一次非 multipart JSON，合计二十七次无效请求。原根字段及文件项的非法时间分别验证；PDF／XLS 均核安全错误、解析器零调用及全库不变。两个旧类仅删除原 HEAD 字节、共九方法／525 行，未调整排除或添加 skip；编译输出已核没有两类残留。
5. 审查：独立预核确认一段安全断言仍属当前入口，已纳入提取；独立 5a 已逐项核原矩阵、原 PDF／XLS 输入顺序与错误码、零调用／零写断言及保留黄金，无 finding；独立 codebase-degradation-review 判断仅测试归属／退役，不触发生产契约或 UI 子专项。最终独立证据门通过：七文件范围、两份测试的工作树／受测／暂存字节、双库及默认全量新鲜 XML、原黄金与编译残留均核准，无遗留 finding。旧 cash-import-preview-responses.json 生产器随旧类退役，KotlinImportPreviewResponseIntegrationTest 仅消费历史捕获，不再视为当前联调或可重生成证据；其旧客户端清理仍属后续 4.4／4.5，不删除独立黄金资源。
6. 测试与 QA：比较基线 57448bab4d441be4c5dd098584a358b5bdf511d4，2026-11-17（Asia/Shanghai），证据根沿仓库外 offline-ledger-1116/local-import-lifecycle。聚焦 retired-http-schema-current 两方法及 ktlint 以 16.21 秒通过；当前 retired-http-parse-sqlite／postgres 沿 run.py、Java 21、单 worker／3 GiB、显式 FT_KOTLIN_LEDGER_CONTRACT_DIR 分别实跑十三方法，54.53／54.99 秒全零；PostgreSQL 原配置与 FT_TEST_POSTGRES_URL 同库且以 _test 结尾，不自动启动或探测。retired-http-default-backend 完整默认后端在 290.58 秒成功：204 类／1,482 方法，零失败／错误／跳过；ktlint 沿聚焦当前源码已过结果，detektTest 实跑通过。相对旧 1,481 方法仅增加已提交的 XLSX 无提示回归，无旧方法消失；方法集逐项比较已固定，未把计数校验器的旧预期当作测试失败。两库 parse 及默认全量的命令、终态、新鲜 XML、当前源码／暂存摘要和两类编译残留检查固定于同名前缀 verification／xml；独立 PlanView／本地 reader 黄金及资源字节与 HEAD 相同。OpenSpec strict 27／27、doctor healthy、九文档 281 个本地目标／45 个锚点及 diff 检查通过。无生产存储或页面变化，不重复浏览器、原生设备或共享 UI 回归；共享层历史消费者保留事实不冒充当前验收。
7. 发布：最终独立证据门通过，按既有分支授权提交推送。已核 README、docs、references、接口／命令样例、配置／迁移、词表及功能地图，无产品功能或公开协议变化，不改现状行为说明；只更新 design／tasks 和 apply 指引。Cross-platform Impact Check：只改后端测试，无 Web／Native／共享生产代码或呈现变化，不另做 UI 演示、原生编译或设备 QA。测试与记录可整体回退，无数据回滚。无新产品决定、公开合同或配置变化，4.4／4.5 及整个 change 保持 active，不归档、不建 PR。

### 账户默认值发布测试迁入当前写集（4.5）

1. 思考：九项原发布测试仍依赖旧服务预览和提交，覆盖十五种输入、实际采用选择、单笔覆盖、暂存草稿、同账号合并、回滚及重复导入。沿既有 change 迁至真实规范快照／本地完整计算／新写集，不新增账户映射或来源证明合同。
2. 计划：原输入和独立业务断言保留；旧 row_write 失败诊断转为当前来源绑定阶段的实际 SQL 故障，仍证明两行事实与默认值整体回滚。账户／默认值变更后，原冻结请求按当前基线合同拒绝，显式沿原选择重算并再次确认；不恢复旧服务提交时自动重算。发布及收据后的实际故障继续核全库回滚。
3. 任务拆分：先恢复有效原夹具并跑九项旧业务基线，再移除旧 API／Command／暂存依赖；当前方法及静态检查、独立工程／防劣化检查、原数据／新鲜 XML 核对和文档收尾。测试迁移不制造业务红灯；原失效元数据不冒称生产业务缺陷。
4. 构建：原九方法在有效夹具上先通过，再迁至当前完整本地计算与写集服务；九方法／十五场景全绿。保留原业务断言，补原键完整收据及全库不变断言；旧文件级 skipped 字段已不存在，改核当前 commit_outcome 的明确成功字段，不用缺省零掩盖错误。实际来源绑定 INSERT 后的故障及映射／收据后的故障均核整体回滚；旧冻结请求先拒绝且全库不变，再显式沿原选择重新核对。仓库外只读保留十五份原输入和源库；标准初态复用当前 schema 的同源匿名 formal 数据，金融字段保持，差异只在创建时间、entry UUID 及已升级的 identity 列；mutable 初态按原 export 的 _bind_existing 字段和三个绑定，从同批独立 bound 数据复用。仅补当前 ledger_seq=0，文件仅删已废弃且全为空的 source_scope_label 并补 source_nickname=null，其余输入字节及值保持，不重生成期望。
5. 审查：独立 5a 静态复核无新增阻断，已核九方法／十五场景、默认值规则、显式重核、实际故障后的全库回滚及 formal／_bind_existing 夹具来源。按复核发现修正两项旧 skipped 字段断言；最终独立证据门通过：受测／工作树／暂存源码摘要一致，九方法／十五场景、新鲜 XML、原输入／夹具来源及四文件范围均核准，无遗留 finding。独立 codebase-degradation-review 判断仅测试装配迁移，无生产契约或 UI 变化，不触发契约／UI 子专项。
6. 测试与 QA：比较基线 095809b0b49e2aa85ded144860d1d32639dcaceb，2026-11-17（Asia/Shanghai）。仓库外 offline-ledger-1116/local-import-lifecycle 的 mapping-publication-retirement-baseline-current-schema 固定原九方法通过及原源码；旧元数据／旧 schema 的准备失败不计业务红灯。FT_KOTLIN_MAPPING_PUBLICATION_DIR 显式指向 mapping-publication-retirement-green，沿 run.py／Java 21／单 worker／3 GiB 执行 :backend:migrationEvidenceTest --tests '*CashMappingPublicationOriginalTest*' :backend:ktlintCheck :backend:detektTest，123.17 秒成功，九方法／十五场景，零失败／错误／跳过；ktlint 复用已通过结果，detektTest 实跑。verification.json 及冻结 XML 核源码摘要 160c10b68e6ef12b91831e5d3ae68f63925bbd56797de58a7dae62cbcfd2a2b8、XML 新鲜度、十五份原输入／转换副本与两份源库摘要、原 formal 业务字段等价。无生产接口、存储或 UI 变化；默认后端测试不包含该历史分类，不重复无变化的全量后端、浏览器或原生设备 QA，不把 SQLite 夹具证据称作新增 PostgreSQL 矩阵。本 change 的 OpenSpec strict、doctor healthy、九文档 281 个本地目标／45 个锚点及 diff 检查通过。
7. 发布：最终独立门通过，按原分支授权小步提交推送，保留待确认 UI。已核 README、references、接口／命令示例、配置／迁移、词表和功能地图；无生产行为或用户功能变化，仅更新 design／tasks 与 apply 指引。Cross-platform Impact Check：仅后端测试装配改变，Web／Native／共享层生产代码、页面和合同未改，不追加跨端构建或 UI 演示。无新用户取舍，无需新增通用规范；本片可整体回退，不需数据回滚。4.5 和整个 change 保持未完成，49／71，不归档、不创建 PR。

### 账户资格重核测试迁入当前写集（4.5）

1. 思考：三项原账户资格测试覆盖二十八个新建、草稿补全、财务单笔编辑及资格丢失场景，仍使用旧服务。沿既定范围迁至本地完整计算和真实写集，保持账户资格与业务结果，不改变提交时的基线守卫。
2. 计划：保留原二十八份匿名输入、金融断言与草稿身份；账户事实变化推进账本版本。确认前变化按当前快照计算；确认后变化先拒绝旧请求并核全库无变化，再由测试显式保留原选择重新核对。资格丢失／已删账户及手工组成项中的账户变更必须拒绝冻结请求，原键无收据；另用仅更新为真实当前基线、changes 不变的不可信请求验证实际账户资格守卫、精确字段及整体不写入。该测试边界请求不属于客户端生产流程，不自动重提交。
3. 任务拆分：恢复当前 schema 的原 formal 初态和原合成账户 5001 设置，先跑原三方法基线；迁移装配，运行当前三方法与静态检查，独立 5a／防劣化范围判断及来源／XML 证据门。测试迁移无生产缺陷，不制造业务红灯。
4. 构建：原三方法／二十八场景先通过，当前装配已迁至规范快照、完整本地计算和真实写集；保留草稿身份、成功数及 posted／账户 5001 原断言，当前成功状态严格核 commit_outcome，不读取已退役的 skipped。原输入只规范化已废弃的空元数据；原源库与输入只读，副本复用同源 formal 金融字段，并按原 _account 设置补账户 5001，不重生成期望。
5. 审查：独立 5a 指出仅基线拒绝不能证明账户资格守卫，已补四个真实当前基线、不变 changes 的不可信请求，分别核具体资格错误、精确字段、not_committed、全库无变化及无收据；manual 明确核父账户为空和组成项 5001，静态重审闭合。其余无新增 finding；最终独立证据门通过，受测／工作树／暂存源码摘要一致，三文件范围、新鲜 XML、二十八份原输入与源库均核准，无遗留 finding。独立防劣化范围判断仅测试装配迁移，不触发契约／UI 子专项。
6. 测试与 QA：比较基线 08072ecd59763e9ab7f8255c8c3d06ac6725a919，2026-11-17（Asia/Shanghai）。沿仓库外 offline-ledger-1116/local-import-lifecycle 的 run.py／Java 21／单 worker／3 GiB 配置，FT_KOTLIN_ACCOUNT_REVALIDATION_DIR 显式指向 account-revalidation-retirement-final，执行 :backend:migrationEvidenceTest --tests '*CashAccountRevalidationOriginalTest*' :backend:ktlintCheck :backend:detektTest，126.24 秒成功，三方法／二十八场景含四个加固边界，零失败／错误／跳过。ktlint 复用已通过结果，detektTest 实跑。原基线三方法通过，final-verification.json／冻结 XML 核当前源码 05d4c0f8ca5267e0ee1c8fc9520bba90781e7acf0ef105f1705c504fe410ebc5、新鲜 XML、原源码、二十八份输入和两份只读源库摘要；只规范化两项空元数据。OpenSpec strict 全部 27 项、doctor healthy、九文档 281 个本地目标／45 个锚点及 diff 检查通过。仅测试装配改变，不重复无变化的全量后端或存储矩阵，不将本历史分类计入默认后端测试覆盖，不宣称新增 PostgreSQL 或整个 change 通过。
7. 发布：最终独立门通过，按同分支授权提交推送，待确认 UI 保留工作树。已核 README、references、接口／命令示例、配置／迁移、词表及功能地图；无生产行为或用户功能变化，无需改现状说明，仅更新 tasks 与 apply 指引。无新用户决策，无需沉淀通用规范；本片可整体回退，无数据回滚。Cross-platform Impact Check：仅后端测试，Web／Native／共享层生产合同与页面无变化，不重复浏览器或原生编译。4.5 与整个 change 未完成，不归档、不创建 PR。

### 共享候选测试迁入当前写集（4.5）

1. 思考：原两方法覆盖两草稿各共享同一两个候选、按当前修订保存，以及写后候选／新事实整体回滚，现有分层测试未完整替代。旧冻结输入为支付宝空交易号，已不满足 statement-import 主规格；该来源修正已由 89682e52 将原 Python _no_number_file 改为 icbc_debit，不是新产品取舍。
2. 计划：旧三份输入及无效基线失败只读保留，不补平台编号或放宽生产校验；依据已合并 _as_source/_no_number_file 生成新有效银行夹具，只替换来源及原 helper 的银行展示说明。沿当前快照／完整本地计算／真实写集保留两方法；候选变更推进版本，先拒绝冻结请求，再显式新快照重核。故障在来源元数据实际写后触发，须看到两新草稿、两问题及四候选，再在同一事务改旧候选并失败，核全库、版本和收据整体回滚。
3. 任务拆分：固定旧输入不可达证据；先跑已修正来源的新有效夹具旧业务基线，再迁移装配，独立范围／工程及防劣化复核、当前测试／静态检查、来源与新鲜 XML 证据门及文档收尾。不将旧无效输入称作通过，不制造生产业务红灯。
4. 构建：已修正来源的新银行基线两方法／三场景先通过，再迁至独立数据夹具、规范快照、本地完整计算和真实写集；当前两方法保留原候选集合／修订与业务结果断言。故障实际发生在两草稿、两问题及四候选写入后，并在同事务改旧候选后抛错；补原键无收据和全库回滚断言，当前成功状态核明确 commit_outcome。标准初态复用已核金融字段等价的同源当前 schema formal；原源库及输入均不修改，不重生成期望。
5. 审查：独立只读分类已确认旧输入违反现行主规格、既有来源修正及现有覆盖缺口。实施独立 5a 无新增 finding，已核银行副本严格对应既有修正、两问题共享完整候选／修订及实际写后回滚；独立防劣化判断仅测试装配迁移，不触发契约／UI 子专项。最终独立证据门通过，受测／工作树／暂存摘要一致，三文件范围、新鲜 XML、原输入／来源修正与源库均核准，无遗留 finding。
6. 测试与 QA：比较基线 220f18bf7cf27f4a0cee5f77aa28f8ea179cb736，2026-11-17（Asia/Shanghai）。沿仓库外 offline-ledger-1116/local-import-lifecycle 的 run.py／Java 21／单 worker／3 GiB 配置，FT_KOTLIN_SHARED_CANDIDATE_DIR 显式指向 shared-candidates-retirement-current，执行 :backend:migrationEvidenceTest --tests '*CashSharedCandidatesOriginalTest*' :backend:ktlintCheck :backend:detektTest，120.37 秒成功，两方法／三场景，零失败／错误／跳过；ktlint 和 detektTest 实跑。current-verification.json／冻结 XML 核源码 644186efc081baf72d877fa302bbcc5f1e54dd5ec600e28cfe00c5cd55e05b29、新鲜 XML、旧源码、三份原输入与副本／源库摘要，并独立重核已合并来源修正。bank-baseline 两方法先通过，obsolete-alipay-xml 固定旧无效输入两失败；旧输入失败仅作不可达来源证据，不计当前通过或业务缺陷。OpenSpec strict 27／27、doctor healthy、九文档 281 个本地目标／45 个锚点和 diff 检查通过。无生产字节变化，不重复全量后端、存储双库矩阵或跨端构建。
7. 发布：最终独立门通过，按原分支授权小步提交推送，待确认 UI 保留工作树。已核 README、references、接口／命令示例、配置／迁移、词表和功能地图；本片无产品变化，无需改现状说明，仅更新 tasks 与 apply 指引。无新用户取舍，无需沉淀通用规范；测试及记录可整体回退，无数据回滚。Cross-platform Impact Check：仅后端测试，无 Web／Native／共享层生产、页面或合同变化，不追加设备 QA，不把本片 SQLite 夹具证据称作 PostgreSQL 或整个 change 通过。4.5 与整个 change 仍未完成，49／71，不归档、不创建 PR。

### 旧计划发布包装退役与本地读取保证（4.4／4.5）

1. 思考：旧 Publication 十八项混合了已退役服务的 TTL／修订／权限与仍有效的分页、输入安全及展示裁剪。独立逐方法分类确认十一项纯规则迁共享测试、七项包装退役；原 PlanApi 二十七项与业务黄金本片保留。
2. 计划：保留原 121 项构造、过滤／绝对位置边界、121 条 processing 不漏不重、六种非法输入、四种损坏输出、私有字段与安全问题文本断言，直接执行生产 LocalCashImportPlanReader／prepareLocalCashImportInput。补同一真实 LocalCashImportTask 首次成功后再计算失败、saveReview 前保原计划／核对结果／修订／事实且无 HTTP 的回归。旧 TTL、私有计划 ID、服务端预览权限不转为本地合同；保存后的 acknowledgement 失败沿当前合同废弃旧计划，不能一概保旧。
3. 任务拆分：原十个无 HTTP 纯规则方法先跑基线；完整过滤分页沿现有独立 LocalReader 黄金与迁移后的原 121 项矩阵核对，不把已退役 HTTP 422 断言算当前通过。新增共同测试及真实 owner 回归，独立 5a／防劣化范围审查，受影响跨端实跑／编译、静态检查与文档收尾。测试归属迁移不制造生产缺陷红灯。
4. 构建：原十个无 HTTP 纯规则方法先通过，新增共同十一方法与真实 Task 回归经聚焦测试通过后，移除旧 Publication 十八方法／316 行。原 built() 121 项构造逐字保留；新读取不变性直接保持 JsonObject／JsonArray 的可变底层别名，防止测试 helper 提前复制掩盖问题。真实 Task 用已有合法计划后的非法选择执行实际计算并失败，核 saveReview 零次、计划 ID 未推进及原计划／核对结果／修订／事实／文件／HTTP 保持。旧源文件与匿名 formal 源库只读，仓库外副本仅补当前 ledger_seq=0；本片无生产行为变化。
5. 审查：独立分类已确认十一／七方法范围及当前 owner 的计算失败前保存缺口；独立 5a 对新增十一方法、真实 Task 回归和删除范围均无 finding，确认 PlanApi 二十七项及黄金未改；Task 新回归不引用待确认 UI readiness，pending SessionCacheRepository 仅新增 Flow，不改变本回归计算／保存路径。验证在合并工作树运行须如实记录。独立防劣化判断仅测试装配迁移，不触发契约／UI 子专项。最终独立证据门通过，五文件范围、受测／工作树／暂存源码、921 份新鲜 XML、原构造与黄金保持均核准，无遗留 finding。
6. 测试与 QA：比较基线 5fab4007b670e8a09b5d457a4dccfc4e8c5075b4，2026-11-17（Asia/Shanghai）。证据根沿仓库外 offline-ledger-1116/local-import-lifecycle；publication-retirement-baseline 的原十方法通过，focused-current 的领域新十一／原 Reader 十一／Input 三项与共享 Imports 七十八项，共 103 项通过、42 秒。最终 run.py publication-retirement-matrix 使用 Java 21／单 worker／3 GiB／in-process 及原 self-contained init，完整命令固定在同名 command.json：领域 jvmTest、jsBrowserTest、wasmJsBrowserTest、testAndroidHostTest 均 --rerun；共享 jsBrowserTest／wasmJsBrowserTest 均 --rerun，testAndroidHostTest 限 LedgerSessionImportsTest 并 --rerun；两模块 iOS 主代码及 Simulator 测试编译、领域 ktlintCheck／detektJvmTest、后端 compileTestKotlin／ktlintCheck／detektTest。357.71 秒成功，领域 JVM 199 类／905 项，JS／Wasm／Android 各 169 类／857 项；共享 JS 106 类／1,047 项、Wasm 108 类／1,053 项，Android Imports 七十八项；共 921 份新鲜 XML／5,654 项，零失败／错误／跳过。JS／Wasm 使用实际 ChromeHeadless 157；iOS 领域主代码复用编译，其余相称编译实际执行，不冒称设备测试。领域 ktlint 复用聚焦当前源码结果，detektJvmTest 实跑；后端测试编译与 detektTest 实跑，ktlint 复用无变化结果。共享模块无独立 lint 任务。matrix-verification.json 固定所有当前受测 Kotlin、XML 摘要／新鲜度、原构造逐字相等、PlanApi／字面 oracle 保持及旧编译类已删除。测试在含待确认 UI 的合并工作树运行，本片新回归不依赖 readiness 呈现；不把历史外部夹具算默认回归，不宣称新增 PostgreSQL、浏览器产品 QA 或整个 change 通过。OpenSpec strict 27／27、doctor healthy、九文档 281 个本地目标／45 个锚点及 diff 检查通过。
7. 发布：最终独立门通过，按同分支授权小步提交推送，保留待确认 UI。已核 README、docs、references、接口／命令示例、配置／迁移、词表、功能地图及独立测试资源；旧 Publication 类／变量无其余使用，无产品变化，无需改现状说明，仅更新 tasks 与 apply 指引。无新用户取舍，无需沉淀通用规范；本片可整体回退，无数据回滚。Cross-platform Impact Check：新增共同规则与共享 Task 回归已按上列目标实跑及相称编译；生产 Web／Native／共享行为和页面不变，不追加呈现演示或设备 QA。4.4／4.5 与整个 change 仍未完成，49／71，不归档、不创建 PR。

### 永久收据重放故障窗口迁移（4.4／4.5）

1. 思考：旧 PlanApi 的两项已提交重放故障仍须验证，首次提交丢失响应与重放查询／事务退出不是同一窗口。本片仅将该保证移至正式写集 HTTP，沿当前错误分类，不恢复旧服务。
2. 计划：同键原正文先成功保存，再分别在 ReceiptRepository.replay 的实际查询后、成功重放的物理事务提交后注入 SQL 故障，核 unknown、安全错误正文、精确阶段及全库不变；移除故障后，按原键读取并重放原正文，返回原完整永久收据，不再施加账本效果。SQLite 与显式已准备 PostgreSQL 使用同一服务合同；不探测或启动数据库。
3. 任务拆分：先跑当前写集 HTTP 基线；新增连接装饰器只用于测试，保留默认 fixture 行为；两库实跑及静态检查、独立工程／防劣化与终态证据后，删除旧两项包装及只供它们使用的 helper。旧 PlanApi 其他测试本片保留，后续按独立分类迁移；无生产缺陷，不制造业务红灯。
4. 构建：当前写集 HTTP 原三十六方法先通过；新增两项各在双库实跑。测试 JDBC 代理先执行真实重放查询，或先执行物理 COMMIT／Connection.commit，再抛 SQL 故障；仅原键成功保存后的重放请求启用，核实际查询与一次故障。原完整正文及键保持，错误为现有 500/internal_error、unknown、非自动重试及精确 request／database_commit 阶段；随后全库、事实和版本保持，按原键读取及同正文重放取得同一完整收据。仅删除 PlanApi 专用 helper 与两方法，其余二十五方法逐字不变。
5. 审查：独立 5a 已核实际 SQL 后故障、unknown 及安全诊断、精确阶段、全库不变和原键重放，无 finding；删除范围逐字复核通过。独立防劣化判断只改测试装配，不触发契约／UI 子专项。最终独立门通过：四文件范围、受测／工作树／暂存源码、新鲜 XML、完整方法集增量及旧二十五项保持均核准，无遗留 finding。
6. 测试与 QA：比较基线 ad0dc87e6ee4aed7fb5fd0a16e5750738672a48b，2026-11-17（Asia/Shanghai），证据根 offline-ledger-1116/local-import-lifecycle。沿 run.py／Java 21／单 worker／3 GiB、显式准备的 PostgreSQL _test 环境，原写集 HTTP 三十六项先通过；新增后 current 四十项及静态检查通过。删除旧包装后，write-set-replay-faults-final 执行 :backend:test :backend:ktlintCheck :backend:detektTest，303.27 秒成功，204 类／1,486 项，零失败／错误／跳过；逐项对照原 1,482 集合，精确新增两个方法各两库、无遗漏。随后仅将新增回归的快照增强为 DefaultSchema 全生成表、全列及 SQL NULL 区分；最终 all-tables 执行 :backend:test --tests '*CashImportWriteSetHttpTest*' :backend:ktlintCheck :backend:detektTest，142.85 秒成功，四十项全零，实际 SQL 窗口各两库执行、静态检查实跑。最终字节的聚焦证据与增强前全量组合使用，不称最终源码完整重跑。两个 verification.json／冻结 XML 记录终态、时间、新鲜度、全部受测 Kotlin 摘要、旧二十五项逐字保持及默认方法集差异。测试未自动探测或启动数据库；无生产／UI 字节变化，不重复浏览器或原生编译，不宣称整个 change 通过。OpenSpec strict 27／27、doctor healthy、九文档 281 个本地目标／45 个锚点及 diff 检查通过。
7. 发布：按同分支授权小步提交推送，待确认 UI 保留工作树。已核 README、docs、references、接口／命令样例、配置／迁移、词表及功能地图；无产品或协议变化，无需改现状说明，仅更新 tasks 与 apply 指引。无新增用户决策，无需沉淀通用规范；测试及记录可整体回退，无数据回滚。Cross-platform Impact Check：仅后端测试，无 Web／Native／共享层生产、UI 或协议变化，不追加浏览器或原生编译；4.4／4.5 及整个 change 保持未完成，49／71，不归档、不创建 PR。

### 写集实际 SQL 失败的整体回滚（4.4／4.5）

1. 思考：旧 PlanApi 的来源绑定、关系、空页及重复＋新增四项故障保证仍有效；单纯阶段回调不证明实际 SQL 已执行。本片只迁移当前写集的同合同回滚及原键重试，不改生产接口或财务语义。
2. 计划：使用合法合成完整写集，在实际 INSERT 返回后读取同事务可见行并抛 SQL 故障；覆盖两行来源、已插关系、认证空页及已有重复和新增混合。核精确失败阶段、not_committed、安全诊断、全部生成表及版本回滚，无新收据，恢复后同键同正文成功并精确重放。双库显式准备，不由测试启动或探测。
3. 任务拆分：沿上一片当前四十项双库字节基线；新增四方法后先聚焦实跑，再删除旧四方法，按实际使用保留共用 helper，复跑当前双库及静态检查。独立工程／防劣化、证据和文档收尾；迁移保证不制造生产缺陷红灯，不删除原规则黄金。
4. 构建：新增四方法在双库实际执行，source／relation 为两新流水，mixed 为已保存原收据的一旧重复＋一新增，empty 为只有认证空页。JDBC 代理先完成目标 INSERT，再沿同一原连接读目标行数及父行数后抛 SQL 故障；核实际新增一行与对应新父数，精确 not_committed 和 source_bindings／relations／source_metadata 阶段。全生成表、版本及原收据保持，无新键收据；同键原正文成功，再精确重放且不新增效果。已删除旧四方法，其余二十一方法及原 atomic helper 保持。
5. 审查：独立 5a 核四类合法输入、实际 INSERT 后故障、同事务可见行、精确阶段、全生成表回滚及原正文重试，无 finding。独立防劣化判断仅测试装配，不触发契约／UI 子专项；最终独立门通过：四文件暂存范围、受测／当前／index 摘要、四十八项新鲜 XML、原二十一项／atomic／黄金保持均核准，无遗留 finding。
6. 测试与 QA：比较基线 0482d1995eaf3253b232739bd9a7c227ad5e7dc3，2026-11-17（Asia/Shanghai），证据根 offline-ledger-1116/local-import-lifecycle。沿 run.py／Java 21／单 worker／3 GiB 与仓库外显式 PostgreSQL _test 配置，执行 :backend:test --tests '*CashImportWriteSetHttpTest*' :backend:ktlintCheck :backend:detektTest；current 142.74 秒、删除旧包装后的 final 140.09 秒均成功。最终四十八项，零失败／错误／跳过；逐项对照上一片四十项仅新增四方法各两库，无遗漏。最终 ktlint 测试源检查与 detektTest 实跑；未改变生产或公共夹具，不重复已完成的完整后端回归。final-verification.json／冻结 XML 固定源码摘要、全部当前受测 Kotlin 保持、新鲜度、实际两库方法集合及旧二十一项逐字不变。原独立 PlanView／黄金资源保持。测试不探测或启动数据库；本片不宣称 Web／Native 或整个 change 通过。OpenSpec strict 27／27、doctor healthy、九文档 281 个本地目标／45 个锚点与 diff 检查通过。
7. 发布：按同分支授权小步提交推送，保留待确认 UI。已核 README、docs、references、接口／命令示例、配置／迁移、词表及功能地图；无产品行为变化，无需改现状说明，仅更新 tasks 与 apply 指引。无新增用户决策或通用规范；本片测试及记录可整体回退，无数据回滚。Cross-platform Impact Check：只改后端测试，无 Web／Native／共享生产、页面或合同变化，不重复浏览器或原生编译；旧 PlanApi 其他方法与字面 oracle 保留，4.4／4.5 及整个 change 保持未完成，49／71，不归档、不创建 PR。

### 已替代的旧计划 HTTP 包装退役（4.4／4.5）

1. 思考：独立逐方法分类已确认二十一项余留中十四项被当前 owner／reader、正式写集 HTTP、纯操作或维护测试承接，或仅验证已退役的 TTL／HMAC／会话 resolve／HTTP 参数语法。只删已替代包装，保留七项真正缺口；本片不恢复旧服务或改变现行合同。
2. 计划：替代关系如下；删除 atomic／invalidated 的唯一使用者后同步删私有 helper 与失效 imports。保留原字面 oracle、资源、纯规则、维护测试和真实解析／内存寿命证据。auth 捕获生产器退役后，共享层旧捕获消费者明确仅历史，不作为当前联调或可重新生成证据。

| 退役包装范围 | 当前保证归属及边界 |
| --- | --- |
| 生命周期／分页收据、重核对／旧计划、缓存分页／无关文档读取 | LedgerSessionImportsTest 的真实 Task 组合、修订守卫、缓存读取计数及永久结果恢复；LocalCashImportPlanReaderTest／PublicationRulesTest 的独立黄金及完整分页；写集 HTTP 原键／ID 永久读取，不恢复旧会话单次消费规则 |
| 失败回滚／响应丢失、缺正文／重复 JSON／缺键 | CashImportWriteSetHttpTest 的实际 SQL 故障、已提交响应丢失及原键核实、严格传输矩阵；不恢复旧 full-array 必填要求 |
| 查询错误／绝对位置、输入结构／安全裁剪 | 共同 reader／PublicationRules／Input 规则；旧 HTTP 重复查询、浮点 limit 及服务会话修订语法随入口退役，不改变本地 typed 输入合同 |
| 新行不能 keep_existing | OriginalOperation／OriginalBusiness 的原纯规则保留；本地真实 Task 计算失败前保存保证保留，不再测试旧服务器计划重发 |
| 取消／过期／独立 writer 的 resolve | 本地无服务 TTL 或 ip_ resolve；Task 取消与永久完成保证及维护 CAS／TTL／真实独立 writer 核实测试保留 |
| 旧会话认证／viewer 错误捕获 | ReceiptReadMigrationTest 的完整公共安全错误与当前写集 HTTP 401／403；旧 cash-import-viewer-error.json 生产器退役，KotlinImportCommitResponseIntegrationTest 对该旧文件仅消费历史捕获，不算当前或可重生成联调 |

3. 任务拆分：先跑当前领域读取／输入与共享真实 Task 组合基线，正式写集沿上一片同字节四十八项双库终态；精确删十四方法及专用 helper，余七项逐字保持。当前受影响 owner／HTTP 回归、后端编译及静态检查、独立工程／防劣化与终态证据门后提交。迁移测试职责不制造生产缺陷红灯。
4. 构建：当前 owner 基线领域三类二十五项、共享真实 Task 七十八项先通过，再精确删十四方法、atomic／invalidated 两 helper 及五项专用 imports。余七方法及四个仍用的路径／输入 helper 逐字保持；当前写集 HTTP、独立 PlanView／Readable 黄金与共享历史捕获消费者字节均保持，不调整排除或新增跳过。旧错误捕获生产器随 auth 包装删除，不能继续作为当前联调入口。
5. 审查：独立分类与 5a 已核十四项集合、对应当前 owner 及退役边界，确认仅预定十四方法／两 helper／五 imports 删除，七方法、写集 HTTP 与独立黄金保持，无 finding。独立防劣化判断仅测试退役，不触发契约／UI 子专项；最终独立门通过：三文件范围、受测／当前／index 字节、十四／七精确范围、黄金保持及四十八项新执行／一百零三项同源码基线复用均核准，无遗留 finding。
6. 测试与 QA：比较基线 510ee2812125f250344f181e5a03cb05afde004d，2026-11-17（Asia/Shanghai），证据根 offline-ledger-1116/local-import-lifecycle。沿 run.py／Java 21／单 worker／3 GiB，baseline 执行 :business-domain:jvmTest（仅 LocalCashImportPlanReaderTest／PublicationRulesTest／InputTest）及 :shared:testAndroidHostTest（仅 LedgerSessionImportsTest），11.93 秒成功、二十五＋七十八项全零。删除后 final 以相同范围加 :backend:test --tests '*CashImportWriteSetHttpTest*' :backend:ktlintCheck :backend:detektTest，141.74 秒成功；正式 HTTP 四十八项新鲜双库 XML 全零，后端测试编译、ktlint 测试源检查及 detektTest 实跑。领域二十五／共享七十八项因源码无变复用上述当轮基线，不冒称最终再执行或一百五十一项全新测试。final-verification.json／冻结 XML 核所有当前受测 Kotlin 摘要、两种时间范围、新鲜 HTTP 方法集合、十四／七精确范围及黄金保持。测试在含待确认 UI 的合并工作树运行，本片无生产改动，不称产品浏览器 QA、原生设备 QA、完整回归或整个 change 通过。OpenSpec strict 27／27、doctor healthy、九文档 281 个本地目标／45 个锚点及 diff 检查通过。
7. 发布：按同分支授权小步提交推送，保留待确认 UI。已核 README、docs、references、接口／命令示例、配置／迁移、词表、功能地图及黄金资源，无产品或用户页面变化，无需修改现状说明，仅更新 tasks 与 apply 指引。无新增用户决策或通用规范；测试退役可整体回退，无数据回滚。Cross-platform Impact Check：只删后端历史包装，共同领域／共享生产与页面不变，使用既有实际跨端证据并补当前 owner 基线，不重复浏览器、原生编译或设备 QA。4.4／4.5 及整个 change 保持未完成，49／71，不归档、不创建 PR。

### 永久收据权限与旧子集重试边界（4.4／4.5）

1. 思考：余留七项中的其他操作者收据隔离、降为 viewer 后读取自己收据、失败子集旧入口拒绝是现行公共 HTTP 保证；旧计划游标及重核对权限由当前 reader／写集测试承接，不恢复旧会话。
2. 计划：当前正式写集成功后，用同工作区有效 editor 的另一用户核原 ID／原键均 404，证明其仍可读真实快照；收据本人降为 viewer 后仍按原 ID／键取得相同完整收据，写入及重放继续 403。旧 retry-sessions 使用真实已存收据 ID 和原 null 行体保持 404，无 retry_context。收据读取／权限拒绝动作全生成表不变，旧入口核原事实及版本保持。
3. 任务拆分：上一片四十八项双库与当前 H 同字节作基线；先补新断言，再精确删除三项旧包装，剩余四项完整保留。当前 H 全部双库及后端编译／静态检查、独立工程／防劣化与证据门后提交；没有待修复生产缺陷，不制造业务红灯。
4. 构建：新增其他 editor 的同工作区快照读取及原 ID／键隔离回归，完整原收据仍由本人取得；既有 viewer 方法补相同完整读取及角色变化后的全表不变，仍核原键及新键写入 403。旧入口矩阵补真实永久收据 ID 与原 null 行体、无 retry_context，保留事实和版本不变。仅删三项旧包装及 root helper，其余四方法／三个 helper 逐字保持。
5. 审查：独立 5a 核有效 editor 资格／同空间隔离、viewer 本人读取及写入拒绝、全表不变和旧入口原真实 ID／正文，无 finding；精确删除三方法／root、保留四方法／三 helper 均核准。独立防劣化判断只改测试，不触发契约／UI 子专项；最终独立门通过：四文件范围、受测／当前／index 摘要、五十项新鲜双库 XML、精确新增实例与旧四方法／三 helper 保持均核准，无遗留 finding。
6. 测试与 QA：比较基线 e37544af355c588f7c28224f7992a69aa5bcbaae，2026-11-17（Asia/Shanghai），证据根 offline-ledger-1116/local-import-lifecycle。沿 run.py／Java 21／单 worker／3 GiB 及仓库外显式 PostgreSQL _test 环境，receipt-access-current 执行 :backend:test --tests '*CashImportWriteSetHttpTest*' :backend:ktlintCheck :backend:detektTest，143.43 秒成功。五十项新鲜 XML，零失败／错误／跳过，精确比上一片增加一方法各两库，旧四十八项均保留；后端测试编译、ktlint 测试源检查、detektTest 实跑。verification.json／冻结 XML 固定全部受测 Kotlin、当前源码摘要、新鲜度、实际方法集合及旧四方法逐字保持；旧 HTTP 包装不算当前通过。未改变生产或公共夹具，不重复完整后端、领域／共享矩阵或产品浏览器／设备 QA；本片不等于整个 change 通过。OpenSpec strict 27／27、doctor healthy、九文档 281 个本地目标／45 个锚点与 diff 检查通过。
7. 发布：按同分支授权小步提交推送，保留待确认 UI。已核 README、docs、references、接口／命令示例、配置／迁移、词表与功能地图；无产品或公共合同变化，无需改现状说明，仅更新 tasks 与 apply 指引。无新增用户决策或通用规范；本片可整体回退，无数据回滚。Cross-platform Impact Check：仅后端测试，无 Web／Native／共享生产或页面变化，不重复浏览器、原生编译或设备 QA；4.4／4.5 及整个 change 保持未完成，49／71，不归档、不创建 PR。

### 原本地计划四项最终行为迁移（4.4／4.5）

1. 思考：余留四项必须分别落在当前完整本地计算／实际写集、真实 Task 发布及本地结果合成上。原退款四来源归一为一个零金额条目，不能只核字符串；固定冲突的 failed／skipped 是本地结果，不能加回服务端收据。仅迁移验证职责，不改变既有合同或范围。
2. 计划：保留 plan-api-1103bg 的 refund／draft／duplicate（含 conflict）／file_status 原输入与源库摘要。原已执行源库只读，另用同源干净当前 schema 副本，明确仅系统时间、条目 UUID／identity 及旧元数据适配，原账户、流水、组成项金融字段逐值核准。完整计划核原业务断言，再走当前写集；退款永久四 ID 必须等于实际新来源，当前本地定位完整四成员同一真实 entry。重复及固定冲突分别核空 items、可确认、行级决定、事实／版本不变，允许永久收据新增。
3. 任务拆分：新增当前本地计划四方法，先在隔离 SQLite 副本实跑；共享层补真实 Task 无账号草稿后非法 manual_overrides 不替换原 review／plan／revision 且合法输入仍可继续、失败文件状态／顺序／引用／null 计数与计划一致。实际 backend 计划／请求／收据／逐项结果捕获只作共同结果测试输入，固定预期沿原断言，不作为期望 oracle；完整捕获仓库外留存，raw 收据不加 skipped，display 合成失败／计数／原因且编码重开一致。各证据分别记录，不合称完整生命周期或浏览器验收。当前替代验证通过后再删旧四包装；独立工程／防劣化、相称跨端与静态检查后提交。
4. 构建：新增当前完整本地计划四方法，原 refund 四来源经真实写集保存，永久逐项 ID 与新增事实及写集回执精确相等，本地定位返回同一真实 entry 的完整四成员。无账户生成两草稿，非法 manual_overrides 拒绝且不写库，原合法输入仍可继续；真实 Task 另核原上下文、review、plan、revision、文件与事实不替换，公开 pending=2。重复与固定冲突均保持原事实／版本，永久 raw 收据无 skipped，允许新增收据。失败文件沿实际顺序／引用保持 ready／failed、1／null，真实 Task 另核其实际 2／null。共同结果测试只消费真实计划／请求／收据／永久项捕获，固定断言核首建一、重复一、冲突本地 failed／skipped 一与原因，raw 权威不变，编码重开一致。替代测试通过后精确删除最后四项旧 PlanApi 类及其三个私有 helper，其他 API／数据夹具、纯规则、黄金资源和维护场景保留。
5. 审查：独立 5a 对后端四方法、两个真实 Task 方法、共同结果消费及捕获来源分层复核，无阻断 finding；已核四真实 ID／条目、非法输入发布前不变性与合法继续、事实／版本及收据边界、原文件 null、raw／display 权威分离与编码恢复。独立防劣化判断仅测试迁移，不触发契约／UI 子专项。最终独立门通过：七文件精确范围、受测／当前／index 摘要、217 份新鲜 XML 共 2,196 项、两浏览器精确三方法增量、旧四方法／编译残留删除及原夹具／黄金保持均核准，无遗留 finding。
6. 测试与 QA：比较基线及本片核对 HEAD 5205b0abb52737f5d1ea4da5cb66e7a76e915261，2026-11-17（Asia/Shanghai），证据根 offline-ledger-1116/local-import-lifecycle。沿 run.py／Java 21／单 worker／3 GiB；current 初次四项中三项通过，失败为测试误读内部计划不存在的 item_counts，非生产缺陷，不冒称业务红绿。更正为真实内部状态并由 Task 核公开计数后，green 四项＋共享八十五项通过；新增共同结果消费后 current Android 八十六项通过。删除旧类后 final 执行 :backend:migrationEvidenceTest --tests '*CashImportLocalPlanOriginalTest*'、:shared:jsBrowserTest --rerun、:shared:wasmJsBrowserTest --rerun、:shared:testAndroidHostTest --tests '*LedgerSessionImportsTest*' --tests '*LocalCashImportResultTest*' --rerun、:shared:compileTestKotlinIosSimulatorArm64、:backend:compileTestKotlin :backend:ktlintCheck :backend:detektTest；208.56 秒成功。新鲜 XML 为 SQLite 原业务四项、JS 106 类／1,050 项、Wasm 108 类／1,056 项、Android 两类／86 项，零失败／错误／跳过。JS／Wasm 比上一片精确新增三个共同方法、无原方法遗漏；沿同一原 self-contained init 排除外部捕获／live 集成，不新增排除。iOS 仅测试编译，不宣称设备 QA；后端编译及 detekt 实跑，ktlint 最终 UP-TO-DATE，沿 green 同字节新类实际检查及本次删除复核。verification.json／冻结 XML 固定终态、时间、新鲜度、受测 Kotlin 摘要、原输入／源库不变及实际捕获裁剪；原库均只读，用显式核准金融字段的隔离副本验证，不复用已执行旧库。该四项历史验证为 SQLite；本片不改生产存储，不冒称重新跑 PostgreSQL／正式双库五十项、产品 Web 主流程、完整生命周期或整个 change。OpenSpec strict 27／27、doctor healthy、九文档 281 个本地目标／45 个锚点与 diff 检查通过。
7. 发布：按同分支授权小步提交推送，保留待确认 UI。已核 README、docs、references、接口／命令示例、配置／迁移、词表、功能地图及独立黄金；无生产、产品或协议变化，无需改现状说明，仅更新 tasks 与 apply 指引。无新增用户决策或通用规范；本片测试及记录可整体回退，无数据回滚。Cross-platform Impact Check：新增共同 Task／结果回归实际执行 JS／Wasm／Android，并完成 iOS 测试编译；Web／Native／共享生产和页面保持，不追加 UI 演示或设备 QA。剩余旧 API 夹具仍有其他原业务／维护使用者，不整体删除；4.4／4.5 及整个 change 保持未完成，49／71，不归档、不创建 PR。

### 客户端旧远端生命周期仓储退役（4.4／4.5）

1. 思考：生产 Graph 已使用 LocalCashImportLifecycleRepository，旧 KtorCashImportLifecycleRepository 只有旧测试使用。逐方法独立分类确认共享 context／plan／detail／choices mapper、永久收据与账户 API 仍有效，不按文件整体删除公共合同。
2. 计划：原五项选择／预览／详情断言改直接 DTO／mapper；保留原历史收据／resolve／结果 mapper、极大账户 ID／revision／false 及安全 unknown 元数据组合，改用保留的真实 API 边界。当前 Local 另核部分准备后断网保留最新 context／剩余 refs，畸形收据在刷新前拒绝。删除旧 Ktor 编排和仅远端上传／取消／刷新壳；旧 Android 七项 RPC 捕获消费者退役，仓库外捕获及独立黄金保持；旧 HTTP 历史生产器仍有保留，不算当前可重生成联调，按 4.4／4.5 后续逐项迁移。不改生产 Graph、DTO 或业务语义，旧 FinanceApi 混合文件中的永久收据／账户／mp_ resolve 保留。
3. 任务拆分：沿 c95bbb5c 最终矩阵中同字节 common 基线；先改有效断言归属并补当前 Local 缺口，聚焦运行后删除无使用的旧生产仓储及历史消费者。独立 5a、防劣化／契约、共同跨端回归与相称编译、精确 diff 及文档核对后同分支提交。无生产缺陷，不伪造业务红灯。
4. 构建：五项 context／plan／choices／detail 映射改直接调用生产 DTO／mapper，金额文本、大整数、明确空选择／null、defer、完整成员／候选／差异原断言保留；四个原字面输入逐字保持。历史 receipt／resolve／结果映射仍用原字段，unknown 的 receipt ID／commit state／安全 message／cause 改由永久收据 API 实际错误解析，极大账户 ID／revision／false 改走保留账户 API。旧取消／连续上传和旧刷新编排壳退役，输入安全 toString 独立保留。当前 Local 新增部分准备中断及畸形收据两回归：首 CSV 已保存、PDF 真实传输失败、后续文件未读，最新上下文及剩余引用准确且无提交；畸形 write_set_result 按 ID／键两真实读取拒绝，无版本／快照 HTTP、事实／token／reader／材料保持。聚焦通过后精确删除无调用旧 Ktor 实现及七项旧 Android RPC 消费者，其他公共 API／DTO／mapper、生产 Local／Graph、历史捕获与独立黄金保持。
5. 审查：独立 5a 对解耦与两个真实 Local 回归复核无 finding，随后核三删除文件与 HEAD／备份一致、零类引用、生产公共依赖原字节保持。代码库防劣化已实际调度独立契约窄核；无页面或交互变化，UI 专项不适用。独立契约复核无 finding，确认 Graph→Local→API 真实调用和永久收据／账户／mp_ 保留；最终独立门通过：九文件精确范围、29 份受测 Kotlin 摘要与暂存四份当前 Kotlin、新鲜 218 份 XML 共 2,223 项、方法七移除／六新增及新鲜两 jar 无旧类均核准。P3 文档范围发现已收窄：本片只退役七项客户端消费者，仍留的历史 HTTP 捕获生产器后续逐项迁移，不算当前可重生成联调；无遗留 finding。
6. 测试与 QA：比较基线及本片核对 HEAD c95bbb5c7c2750515254b8f38421f04f40adc951，2026-11-17（Asia/Shanghai），证据根 offline-ledger-1116/local-import-lifecycle。原同字节基线为上一片冻结 JS／Wasm 全 common；本片 current 初轮因测试误用 LocalCashImportFile 属性名编译失败，修正后 green 119 项中 118 通过，剩一项因误用 encodeDefaults=false 的通用 API 编码器缺省省略字段失败；均是测试适配错误，不冒称生产缺陷红灯。改用与 Local preview 一致 encodeDefaults=true 后，mapping-green 119 项全零并冻结 XML。精确删除三文件后，final 沿 run.py／Java 21／单 worker／3 GiB 和原 self-contained init，执行 :shared:jsBrowserTest --rerun :shared:wasmJsBrowserTest --rerun :shared:testAndroidHostTest --tests '*CashImportLifecycleRepositoryTest*' --tests '*LedgerReceiptMappingTest*' --tests '*CashImportPlanApiTest*' --tests '*LedgerSessionImportsTest*' --rerun :shared:compileTestKotlinIosSimulatorArm64，138.42 秒成功。新鲜 XML 为 JS 106 类／1,049 项、Wasm 108 类／1,055 项、Android 四类／119 项，零失败／错误／跳过；原外部集成排除不变，两浏览器对上一片精确七移除／六新增（含准确更名），无其他方法遗漏。JS／Wasm／Android／iOS 共享生产编译及 iOS 测试编译实际执行；新鲜 Android compile／runtime 两 jar 均无旧 Ktor 类，未运行的旧输出目录不作为当前包。verification.json／冻结 XML 固定受测源码、新鲜度、时间、方法精确差异、原常量和 PlanApi 二十一方法／receipt 前五方法原样保持。OpenSpec strict 27／27、doctor healthy、九文档 281 本地目标／45 锚点、diff 检查通过。本片不改存储，不重复双库；共同浏览器单测不称产品主流程／设备 QA 或整个 change 通过。
7. 发布：按同分支授权小步提交推送，保留待确认 UI。已核 README、docs、references、接口／命令示例、配置／迁移、词表、功能地图及历史捕获／黄金；实际应用一直使用 Local 仓储，无新用户行为或协议变动，无需改现状说明，仅更新 tasks／apply 指引。无新增用户决策或通用规范；可整体回退本片代码与测试，无数据回滚。Cross-platform Impact Check：Web／Native 生产移除相同无调用旧客户端编排，共享 Local／Graph／公共合同保持；共同 JS／Wasm／Android 已实跑，iOS 相称编译完成，无呈现变化，不追加演示／设备 QA。旧 FinanceApi 混合方法及后端旧编排仍按职责继续退役，4.4／4.5 和整个 change 保持未完成，49／71，不归档、不创建 PR。

### 客户端旧会话上传 API 退役（4.4／4.5）

1. 思考：旧客户端五个 session API 与三种上传结果 DTO 只被旧 ContextApiTest 使用，实际本地捕获及 parse-only 使用 CashImportUploadInput；同名 context DTO／mapper 仍用于本地，不属于删除范围。
2. 计划：原二十一个测试逐方法分类，原样保留两个 context DTO、两个底层有界源预算断言，两个错误／reference 格式矩阵改用永久收据 API。旧 multipart、服务器修订及路径编排退役；原始字节、时间戳、密码和无来源设置由实际 PDF／XLS parse-only 断言承接。真实 Local 补每次回调挂起／失败停止后续、bad→good 继续、集合前置零读零保存与取消传播；无提交或自动发送。
3. 任务拆分：沿 be93a072 当轮同字节共同矩阵作基线，先补当前组合及 parse-only 安全断言；通过后删除旧 API 编排及三旧 DTO，原普通输入类独立原样保留。独立工程／防劣化契约、相称跨端矩阵、文档核对及最终精确证据门后同分支提交；无生产缺陷，不伪造红灯。
4. 构建：原二十一项中四项 context DTO／底层有界源方法逐字保持，两个公共安全错误／引用矩阵只改永久收据读取，SESSION 字面输入保持。补四个真实 Local 方法：首文件真实保存后回调挂起或抛错均阻止次文件读取；本地 I/O、超限、无 bounded reader、坏设置、不支持类型均安全返回单文件失败并实际保存后续好文件；全超限零读取／无文件，空／二十一／重复／非法引用在回调和读取前拒绝，读取取消保原异常因果链且停止后续，无提交或额外 HTTP。真实 PDF／OLE 解析原测试另核密码不在 URL、两种旧密码 header 为空、无 source_scope_label，原字节及时间戳断言保留。聚焦通过后删除旧五 API、私有上传编排及三旧结果 DTO；原普通 CashImportUploadInput 声明、属性及安全注释同包原样搬至独立文件，其他公共 DTO／mapper、Local／Graph、parse-only 和源读取保持。
5. 审查：独立 5a 已逐函数核原六项保留、输入字面及四个实际 Local 回归，无 finding；精确抽取／删除及零引用复核通过。代码库防劣化实际调度独立契约窄核，无 finding，确认输入安全、当前 Local／Graph、公共 context／receipt、PDF／XLS 与有界源合同均保留；无 UI 变化，不触发 UI 子专项。最终独立门通过：七文件精确范围、29 份受测 Kotlin 与四份暂存当前 Kotlin 摘要、新鲜 217 份 XML 共 2,185 项、精确十五删／四增及两新鲜 jar 的输入／旧 API 边界均核准，无遗留 finding。
6. 测试与 QA：比较基线及本片核对 HEAD be93a072d4c4d8ea7e92e62c3f5e51d041edb2dc，2026-11-17（Asia/Shanghai），证据根 offline-ledger-1116/local-import-lifecycle。沿上一片同字节共同矩阵作基线；本片 current 103 中 102 通过，唯一失败是测试把 JVM 协程堆栈恢复复制的取消异常要求为相同对象；改核取消类型／消息及原 fault 在因果链内后，green 103 全零。该适配失败不冒称生产缺陷红灯。删除旧 API 后 final 沿 run.py／Java 21／单 worker／3 GiB 与原 self-contained init，执行 :shared:jsBrowserTest --rerun :shared:wasmJsBrowserTest --rerun :shared:testAndroidHostTest --tests '*CashImportContextApiTest*' --tests '*LedgerSessionImportsTest*' --tests '*LocalCashStatementReaderTest*' --rerun :shared:compileTestKotlinIosSimulatorArm64，162.02 秒成功。新鲜 XML 为 JS 106 类／1,038 项、Wasm 108 类／1,044 项、Android 三类／103 项，零失败／错误／跳过；两浏览器比上一片精确退役十五旧包装、新增四实际方法，无其他遗漏，原外部集成排除不变。JS／Wasm／Android／iOS 共享生产及 iOS 测试编译实际执行；新鲜 Android compile／runtime 两 jar 保留输入类、无旧 ContextApiKt。verification.json／冻结 XML 固定受测 Kotlin、新鲜度、方法差异、原 Input 声明与六项保留断言，未执行旧目录不当当前包。OpenSpec strict 27／27、doctor healthy、九文档 281 本地目标／45 锚点及 diff 检查通过。无生产存储变化，不重复双库；共同浏览器单测不称产品流程冒烟或设备 QA，整个 change 未通过最终验收。
7. 发布：按同分支授权小步提交推送，保留待确认 UI。已核 README、docs、references、接口／命令示例、配置／迁移、词表、功能地图及历史证据／黄金；实际应用继续原 Local 流程，无新用户行为或协议，不改现状说明，仅更新 tasks 与 apply 指引。无新增用户决策或通用规范；本片可整体回退，无数据回滚。Cross-platform Impact Check：Web／Native 共用输入及本地流程保持，删除相同旧客户端会话上传编排；三目标已实跑，iOS 相称编译完成，无呈现变化，不追加演示／设备 QA。旧计划客户端混合 API 与后端旧编排继续按职责退役；4.4／4.5 与整个 change 保持未完成，49／71，不归档、不创建 PR。

### 客户端旧计划 API 退役与永久命令边界保留（4.4／4.5）

1. 思考：客户端剩余六个旧 preview／plan／items／detail／processing／commit 函数只供旧 PlanApiTest 使用；同文件的永久收据、原键、结果、通用 mp_ 核实及 key／page／revision helper 仍用于当前维护／账户／写集，不能整体删除。
2. 计划：原二十一测试逐项分类，四个旧分页／提交／网络包装退役，七项改当前选择 toDto 编码、原详情 DTO、安全收据错误和账户边界；其余十项原样保留，通用核实例仅换 mp_。输入的缺省与显式 null、空数组、false、大 ID、金额文本，以及原 PLAN 的修订／可确认／零金额仍有直接固定断言，不恢复旧远端编排。
3. 任务拆分：沿 10d930fb 同字节共同基线，先改测试归属，聚焦通过后删除六函数及专属 serializer／路径 helper；永久函数与共享 helper 原字节保留，文件按职责重命名。独立工程／防劣化契约、共同跨端与相称编译、文档和最终精确暂存门后同分支提交，无生产缺陷，不制造红灯。
4. 构建：原二十一项改为十七项。三组选择直接走真实 ImportChoiceSet→toDto→本地 encodeDefaults=true，完整 key／空数组、明确 null／false／空字符串、大 ID 和精确金额文本保持；首次预览另直接解码原 PLAN 保修订四／可确认／0.00，详情只去旧路径转义，保全部六项业务断言。安全错误五形态改永久收据 HTTP，账户非法 patch 零 HTTP、五形态账户 ID 拒绝保留。九个公共测试块及六个字面输入逐字保持，第十个通用核实例改 mp_ 并核请求 plan_id；只退役四个旧分页／提交／网络包装。聚焦通过后移除六旧 API、专属 serializer／常量／path helper，原永久收据／结果／核实函数和全部共享校验 helper 同包原样归入 CashMutationApi.kt，当前调用方不变。
5. 审查：独立 5a 逐方法核选择映射、原 PLAN 输出、详情及两个五形态矩阵，无 finding；增量复核精确重建剩余源文件、零旧函数与手写 facade 依赖。代码库防劣化实际调度独立契约复核，无 finding，确认公共签名／默认参数／Unicode 门／原正文及当前维护／账户／写集／冻结存储使用保持；无 UI 变化，不触 UI 子专项。当前同源编译边界无 ABI 阻断，不声称外部预编译二进制兼容。最终独立门通过：四项逻辑变更／五路径精确范围、27 份受测 Kotlin 与两份暂存当前 Kotlin、新鲜 219 份 XML 共 2,124 项、七旧名删／三更名增及两新鲜 jar 的 facade 边界均核准，无遗留 finding。
6. 测试与 QA：比较基线及本片核对 HEAD 10d930fb5b4db3a7b7dd25c7493d77bfce477f7e，2026-11-17（Asia/Shanghai），证据根 offline-ledger-1116/local-import-lifecycle。同字节基线沿上一片共同矩阵；current 聚焦 Plan 十七／Lifecycle mapper 九／WriteSet 十三／AccountScope 八／MaintenanceSavePlan 三，共五十项全零。没有生产缺陷，不制造失败红灯。删除／归位后 final 沿 run.py／Java 21／单 worker／3 GiB 与原 self-contained init，执行 :shared:jsBrowserTest --rerun :shared:wasmJsBrowserTest --rerun :shared:testAndroidHostTest --tests '*CashImportPlanApiTest*' --tests '*CashImportLifecycleRepositoryTest*' --tests '*CashWriteSetApiTest*' --tests '*ImportAccountScopeTest*' --tests '*MaintenanceSavePlanApiTest*' --rerun :shared:compileTestKotlinIosSimulatorArm64，174.29 秒成功。新鲜 XML 为 JS 106 类／1,034 项、Wasm 108 类／1,040 项、Android 五类／50 项，零失败／错误／跳过；两浏览器对上一片精确七个旧名移除／三个更名新增，净减四包装，无其他遗漏，原外部集成排除保持。四目标共享生产及 iOS 测试编译实际执行，新鲜 Android compile／runtime 两 jar 有 CashMutationApiKt、无旧 facade。verification.json／冻结 XML 固定受测 Kotlin、新鲜度、方法集合、原公共 API／helpers、九测试块及六输入逐字保持。OpenSpec strict 27／27、doctor healthy、九文档 281 本地目标／45 锚点及 diff 检查通过。无存储变化不重复双库；共同浏览器单测不称产品主流程或设备 QA，不作为外部已编译消费者 ABI 证明，整个 change 尚未通过最终验收。
7. 发布：按同分支授权小步提交推送，保留待确认 UI。已核 README、docs、references、接口／命令示例、配置／迁移、词表、功能地图及历史证据／黄金；无当前用户可用行为或新协议变化，无需改现状说明，仅更新 tasks／apply 指引。无新用户决策或通用规范；本片可整体回退，无数据回滚。Cross-platform Impact Check：Web／Native 共同移除六旧客户端计划函数，当前 Local／Graph／DTO／parse-only 保持；三目标实跑及 iOS 相称编译完成，无呈现变化，不追加演示／设备 QA。后端旧编排及历史验证映射继续按职责退役，4.4／4.5 与整个 change 保持未完成，49／71，不归档、不创建 PR。

### 原银行解析与锁边界测试迁移（4.4／4.5）

1. 思考：旧 OriginalContext 的银行原字节、亚洲引号反例、真实 PDF 密码及非持锁解析仍有有效合同，不能随旧会话编排整体删除。独立分类确认当前 PDF 三十八向量已覆盖同一加密文件的三种密码结果；本片只迁移解析合同族，不改变功能或验收。
2. 计划：原银行字节改走实际 XLS parse-only→共同端侧归一及共同亚洲解析，保不完整行、精确金额、位置、计数、原备注和零财务读写；quoted reader 方法原样搬迁。当前 PDF／XLS HTTP 补真实另一连接取得工作区锁的并发实证。旧会话修订、TTL、文件名裁剪与暂存不作为新解析合同，剩余会话职责继续逐项迁移。
3. 任务拆分：先新增现行边界测试并运行 SQLite／显式 PostgreSQL 及当前本地密码状态、替换恢复回归；通过后删除对应四个旧会话方法。保原外部字节、独立资源、其他四个旧方法及生产代码，完成独立 5a／防劣化、最终精确验证和文档核对后同分支提交。无生产缺陷，不伪造红灯。
4. 构建：新增三个当前测试。银行使用原 XLS／亚洲字节，实际 XLS parse-only 返回后调用共同端侧归一，亚洲调用共同本地解析，与原默认读取器完整文档对照并保原失败输入；quoted 方法原样搬迁。另一个 HTTP 测试同时核 PDF／XLS 解析期间另一连接取得真实工作区锁、仅鉴权 SQL 且禁止财务读写，以及维护相关十五张表不变。SQLite 三项和本地准备七项／实际会话八十六项先通过，随后只删除原 Context 八项中的对应四项及五个无用 import，其他方法与所有生产代码保持。
5. 审查：独立 5a 确认仅删预定四项、其余四个方法正文逐字保持、quoted 原方法原样、银行完整文档对照及真实跨连接锁有效；原 PDF 三密码为同一加密字节 SHA cdc6ee8f0d5d2b371e65e022859e50191ac66385adb4c1bb117403bbdbcf704b。代码库防劣化已实际调度独立契约窄核，无源码 finding；鉴权前后范围、PDF 三十八向量及本地密码状态／替换恢复足够承接，不保旧 TTL／文件名脱敏合同；无 UI 变化不触 UI 专项。唯一 P3 证据表述已修：detailDatabaseState 比较维护相关十五表，另用 authOnly SQL 探针证明解析服务无财务读取或写入，不称全库快照。最终独立门已核四文件精确暂存、两份受测 Kotlin 摘要、新鲜两库三十二项与当前本地九十三项全部全零、原输入和资源保持及待确认 UI 未混入；无遗留 finding。
6. 测试与 QA：比较基线及本片核对 HEAD 291bc642fc384156964538b7f42124ffce30f2c2，2026-11-17（Asia/Shanghai），证据根 offline-ledger-1116/local-import-lifecycle。沿 run.py／Java 21／单 worker／3 GiB／显式准备的测试库及原外部 bank 目录，current-sqlite 执行 :backend:migrationEvidenceTest --tests '*CashImportParsingOriginalTest' --rerun 与 :shared:testAndroidHostTest --tests '*LocalCashImportPreparationTest*' --tests '*LedgerSessionImportsTest*' --rerun，25.84 秒成功，新鲜后端三项／Android 九十三项全零。删除旧四项后，final-postgres／final-sqlite 分别执行 :backend:migrationEvidenceTest --tests '*CashImportParsingOriginalTest' --tests '*CashPdfParseContractTest' --tests '*CashXlsParseContractTest' --rerun；两库各三类十六项，零失败／错误／跳过，分别 171.29／56.07 秒成功，PostgreSQL 同轮 :backend:ktlintCheck／:backend:detektTest 实际成功。PDF 三十八原文档与 XLS 六 OLE 文档在原方法内完整循环，不以十六方法数冒充文档数；本片最终双库三十二方法加当前本地九十三项共一百二十五项（不重复计新增三项初轮）。verification.json／冻结 XML 核源码、时间／新鲜度、方法数及零跳过；四原银行输入与 PDF 三十八资源 SHA 固定，quoted 原样及剩余四方法逐字保持，无生产源／黄金变化。OpenSpec strict 27／27、doctor healthy、九文档 281 本地目标／45 锚点及 diff 检查通过。backend 生产与共同依赖编译因无变化为 UP-TO-DATE，受影响后端测试编译实际完成；无存储生产改动，本片实际双库的是当前解析安全合同，不称全写集／整个 change 验收或产品浏览器／设备 QA。
7. 发布：按同仓库同分支授权在门禁通过后小步提交推送，保留待确认 UI。已核 README、docs、references、接口／命令示例、配置／迁移、词表、功能地图及独立资源；仅迁移测试职责，无用户行为、生产协议或配置变化，无需改现状说明，仅更新 tasks／apply 指引。没有新的用户选择或需沉淀的通用规范；本片可整体回退，无数据回滚。Cross-platform Impact Check：仅后端测试消费已存在的共同解析，Web／Native／共享生产及页面不变；实际本地准备与恢复已有本片当前 Android 回归，不重复产品浏览器／设备 QA 或无变化的原生编译。剩余旧会话职责及内部旧生产编排仍待迁移；4.4／4.5 和整个 change 保持未完成，49／71，不归档、不创建 PR。

### 旧导入会话上下文测试收口（4.4／4.5）

1. 思考：原 Context 剩四项经独立分类，私有维护隔离由当前维护 store 与旧路由拒绝测试保留；解析后权限重验由当前 PDF／XLS 的范围／角色／到期与成功／错误矩阵保留；清理后成功恢复由真实本地生命周期验证。唯一缺口是同 Task 文件已开始读取后才冻结／完成提交的交错，不复刻四个旧包装。
2. 计划：复用真实磁盘 SQLite／生产 owner／Task，暂停读取，同 Task 冻结原提交或实际恢复完成，再释放原读取；断言迟到文件拒绝，原文件／核对／请求键正文或归档保持，不能产生额外 HTTP。保留真实重开证据，先测试后删除旧四方法及整个旧类，历史字节／资源与维护合同不动；旧 ip_ resolve=200 不迁为当前行为。
3. 任务拆分：新增一个两分支交错方法；当前聚焦通过后删旧类，复核保留的方法／原输入／私有维护／权限／收据承接。独立 5a／防劣化契约、相称最终验证、文档核对与精确暂存后同分支提交。没有生产缺陷，不制造业务红灯。
4. 构建：新增一个真实磁盘 SQLite 方法，循环 pending／committed 两分支；同 Task 在文件读取暂停期间冻结，完成分支沿原键核实未提交后实际 POST 原正文、持久化收据／结果并清理，再释放读取。迟到返回 plan_changed，不增加读取／HTTP，pending 的文件／review／冻结正文保持，completed 的清理状态／永久归档保持，真实重开再核。聚焦当前一项先通过，随后删除仅剩四方法的旧 Context 类和旧 ip_ resolve 捕获生产器；独立外部旧资源、其他旧夹具及所有生产代码保持。
5. 审查：独立 5a 核实际 readBounded 回调中 entered／release 和同 Task freeze 的严格交错、pending 完整 encode 对照、completed 原键正文 POST／归档清理及重开，不是假先冻结再读取；无 finding。代码库防劣化实际调度独立契约窄核，无 finding，确认另外三族由现行维护 owner／workflow／revision、PDF／XLS 六组合及成功后恢复保留。旧 cash-import-private-resolve.json 只有被删 producer、当前无消费者，历史黄金保留；旧 ip_ 200 不迁为现行合同。本片范围限同批次唯一 Task，HTTP 用 MockEngine，不声称跨 Task 并发或真实后端提交全链路；无 UI 变化不触 UI 子专项。最终独立门已核四文件精确暂存、受测源码摘要、原四十三方法保持、全 188 项新鲜 XML 全零及旧编译类消失，未混入待确认 UI；无遗留 finding。
6. 测试与 QA：比较基线及本片核对 HEAD 1698be476260c0c8a59642a95ed9438abaef734b，2026-11-17（Asia/Shanghai），证据根 offline-ledger-1116/local-import-lifecycle。run.py／Java 21／单 worker／3 GiB 下，current 仅运行新增一方法两交错，15.20 秒成功。删除旧类后 final 执行 :shared:testAndroidHostTest --tests '*LocalCashImportRecoveryTest*' --tests '*SqlLedgerImportFilesTest*' --tests '*LedgerSessionImportsTest*' --rerun 与 :backend:test --tests '*MutationPlanStoreTest' --tests '*ParsedImportStoreTest' --tests '*CashImportWriteSetHttpTest.retiredStagedImportRoutesKeepOriginalReceiptsWriteSetAndMaintenanceResolve*' --rerun :backend:ktlintCheck :backend:detektTest，119.79 秒成功。新鲜 Android 三类 44／17／86 共 147 项；后端四类共 41 项，包括维护 store 五项、ParsedStore memory／模拟 R2 两参数各十四项、过滤器匹配的 R2Store 六项，以及旧路由正式 HTTP 在 SQLite／显式 PostgreSQL 各一项；全 188 项零失败／错误／跳过。初轮一项不重复计数。verification.json／冻结 XML 核源码、时间／新鲜度、精确方法与参数化库名；原 Recovery 四十三方法逐字保持，旧类只含原四项，当前共同／维护／解析测试原样，无旧编译类。Android 当前测试及后端测试编译、detekt 实际执行，生产编译和后端 ktlint 为 UP-TO-DATE；前片相同剩余后端源已实际 ktlint 通过。本片未改生产存储，不重复全写集双库；维护 R2 是受控客户端，不能称真实云存储。OpenSpec strict 27／27、doctor healthy、九文档 281 本地目标／45 锚点及 diff 检查通过，不称产品浏览器／设备 QA 或整个 change 通过。
7. 发布：按同仓库同分支授权在适用门禁通过后小步提交推送，保留待确认 UI。已核 README、docs、references、接口／命令示例、配置／迁移、词表、功能地图及原独立资源；仅迁移当前验证与退役旧包装，没有用户行为、生产接口或配置变化，不改现状说明，仅更新 tasks／apply 指引。没有新的用户决定或需沉淀的通用规范；本片可整体回退，无数据回滚。Cross-platform Impact Check：新增 AndroidHost 真实 SQLite 恢复回归，不改 Web／Native／共享生产或 UI；不重复未变共同浏览器矩阵、原生编译或设备 QA，当前跨端合同保持。旧 SessionApi、ContextService 及其他依赖仍按职责后续迁移，4.4／4.5 与整个 change 保持未完成，49／71，不归档、不创建 PR。

### 旧上传与会话展示生产链退役（4.4／4.5）

1. 思考：独立调用链分类确认旧上传、文件变更和会话展示仅余历史测试调用；预览／提交仍有真实事务和维护依赖，本片不整删。保留 parse-only 共用 CashImportUpload／InputError、全部独立黄金及当前维护存储能力。
2. 计划：现行 Preparation 补 owner／资金／缺身份分组及建议≠选择、三类格式错误文案；完整端侧文件计划补 add／replace／remove／continuous×defer／ignore、编码恢复、未识别 ID 及删除成员失效；实际 Task 补完整公开详情、defer 结果。不为旧随机文件命名空间、TTL、文件名脱敏或非法 preview 容忍恢复合同。
3. 任务拆分：先补四个当前验证点并运行；通过后成组删除 Context／SessionView、HttpService 上传／文件／展示段及专用构造器和 helper，保预览／提交；退役已承接的 Context／SessionApi／SessionFixture／ChoiceRecovery 及其余精确旧片段。独立 5a／防劣化、跨端与真实双库解析及保留预览验证后核文档和精确暂存，同分支提交。
4. 构建：新增三方法并增强实际 Task 一方法，聚焦十八项先通过。分组覆盖同 owner 不同资金、不同 owner／批次、缺身份逐文件及脱敏；映射建议与保存选择分开，三类格式失败保精确码与安全重选。完整文件计划覆盖四类变化×defer／ignore、编码恢复、未识别 ID 及删除成员后的旧确认失效，再核完整写集不带原 confirmed 关系或被删行；draft 关系可 active，不能误断为空。实际 Task 核公开详情、defer 后结果及旧计划不可读。随后删除 Context／ContextService、SessionView、HttpService 上传／文件变更／会话展示段及专用构造器，InputError／Upload 原样独立保留；预览／提交主体不变。退役三个旧测试类共三十四项、专用 SessionFixture 及六个已承接旧方法，共四十项；EntryChoices 漏调 removeFile 的一项也按原断言归属退役，不留生产空壳，其余事务方法原样保留。
5. 审查：独立 5a 与代码库防劣化实际调度的独立契约窄核均无 finding。原上传值及注释逐字保持，剩余计划读取／预览／提交主体逐字保持，真实 parse-only、维护与永久收据生产源码不变；删除后的旧类型引用为零。原 SourcePlan 前两项、Detection 其余、Multipart 解析主体、维护方法剩余断言及 OriginalBusiness 二十九方法保留；后者仅把数据读取夹具 Api 改 Data，不再构造旧服务。文件变化完整写集复核确认 active draft 是既有合法语义，不放宽生产校验。最终独立证据门通过，核准二十一暂存路径、十三份当前 Kotlin 摘要、1,135 份新鲜 XML／7,229 次全零执行及原输入／五份黄金保持；八项遗留未计入通过，UI／.commandcode 未混入。无 UI 变化不触 UI 专项。
6. 测试与 QA：比较基线及本片核对 HEAD baef71a47c97d5e614eb053fdd9891cc43ed9da7，2026-11-17（Asia/Shanghai），证据根 offline-ledger-1116/local-import-lifecycle。沿 run.py／Java 21／单 worker／3 GiB，current-verified 十八项先通过。backend-full 执行 :backend:test :backend:ktlintCheck :backend:detektMain :backend:detektTest，342.12 秒成功，默认二百零三类／1,480 项全零，含真实 SQLite／显式 PostgreSQL 写集 HTTP 五十项；静态检查实际执行。common-final 沿原 self-contained init，执行 :business-domain:jvmTest／jsBrowserTest／wasmJsBrowserTest／iosSimulatorArm64Test --rerun、:business-domain:ktlintCheck／detektJvmTest，及 :shared:jsBrowserTest／wasmJsBrowserTest --rerun、:shared:testAndroidHostTest --tests '*LocalCashImportPreparationTest*' --tests '*LocalCashImportRecoveryTest*' --tests '*SqlLedgerImportFilesTest*' --tests '*LedgerSessionImportsTest*' --rerun、:shared:compileTestKotlinIosSimulatorArm64，296.55 秒成功。新鲜 XML 为 domain JVM 906、JS／Wasm／Native 各 858，shared JS 1,036、Wasm 1,042、Android 四类 156，共 5,714 次执行全零；新增完整文件方法在 domain 四目标实跑；准备新增两方法及 Task 增强在 shared JS／Wasm／Android 实跑，iOS 共享仅测试编译。retained-current 执行 :backend:migrationEvidenceTest --tests '*CashImportSourcePlanTest' --tests '*CashMutationPublicationOriginalTest.maintenanceCommitLoaderChecksActualRevisionType' --rerun，6.62 秒成功三项。parse-sqlite／parse-postgres 各执行 :backend:migrationEvidenceTest --tests '*CashImportParsingOriginalTest' --tests '*CashPdfParseContractTest' --tests '*CashXlsParseContractTest' --rerun，分别 56.13／54.80 秒，各十六项全零。总计上述终态 7,229 次执行，初轮十八项与失败历史轮不重复计入；XML／源码摘要、新鲜度、精确原块／资源及四原银行字节由 verification.json／冻结 XML 固定。OpenSpec strict 27／27、doctor healthy、九文档 281 本地目标／45 锚点及 diff 检查通过。

   历史种子仅在独立副本补当前 ledger_seq、identity=root、可空 projection business_label 和空 audit 表，所有原列行值逐一核相同、原输入与种子摘要保持；副本去掉已退役 file source_scope_label、补 document source_nickname=null，原财务和身份字段不变。更广历史重跑初轮因旧库缺表失败；补结构后 OriginalBusiness 二十九项中二十一项通过、八项失败，失败 XML／日志保留。八项是 4.4 待核迁移证据：两项被当前 codec 拒绝，其余六项原预期与当前输出不同，尚未逐项完成基线／合同定位，不笼统判定为可废弃旧语义，不计入本片通过，不删弱断言／原输入。另两项历史维护种子已有执行状态的失败不算通过。独立复核确认本片不改这些方法及业务规则，上传链删除门限定当前合同；不以本片门关闭遗留或整个 change。共同浏览器单测不称产品主流程 QA，Native 单测／编译不称原生设备交互 QA。
7. 发布：按同仓库同分支授权在最终证据门通过后提交推送，保留待确认 UI。已核 README、docs、references、接口／命令示例、配置／迁移、词表、功能地图、原独立资源与历史证据；本片移除已退役内部链，无新用户可用行为、公开协议或配置变化，现状说明无需修改，更新 tasks／apply 指引。没有新的用户决定或应沉淀的通用规范；断网禁止业务写入及 FAB 刷新位置沿既有确认，未把内部退役选择当作用户偏好。本片可整体回退，无数据回滚。Cross-platform Impact Check：后端删除旧上传／会话编排，共享生产端侧行为保持，Web／Native 同一新增验证及受影响当前测试实际运行，无页面变化，不追加演示或设备交互 QA。八项历史迁移证据、剩余旧预览／提交与专用暂存仍待完成；4.4／4.5 与整个 change 保持未完成，49／71，不归档、不创建 PR。

### 永久功能测试的导入种子迁移（4.4／4.5）

1. 思考：永久公共字段编辑／重导、删除后重导及维护计划恢复仍借旧导入服务建立事实。本片迁移这三个测试族的真实导入前置，保原来源、公共字段保护、稳定条目、删除墓碑及不相关变化不使维护计划失效的断言；生产行为不变。
2. 计划：真实规范快照→原规范文档桥→完整端侧计算→typed 写集→正式提交，不保旧 session／plan ID，不建旧 API facade。公开命令结果按真实写集对象核对，不把文件行或缺省 skipped=0 当服务端合同；保原输入，测试库只用隔离副本。
3. 任务拆分：先写当前准备值与真实提交测试前置、保全部正常及冲突分支，聚焦运行，再复核其余方法原样与旧服务引用消失；独立 5a／防劣化契约、当前双库及相关维护／写集回归、文档与精确提交门后同分支提交。无生产缺陷，不制造业务红灯。
4. 构建：三个测试族沿实际规范快照、完整端侧计算及正式写集提交，新的内部测试准备值只固定本次快照／计划／typed 请求，不构造旧服务或暂存身份。保公共空备注／人工对方、来源原文、稳定条目与重建、冲突全库不变，以及先维护预览→真实不相关导入→账户改名→原维护确认的顺序。删除后重导核计划正式动作、typed 状态、实际收据 created_posted／saved_draft 及新旧事实／绑定；计划零动作计数是稀疏键，不补造字段。原 reimport_missing 实为支付宝空交易号，按现行主规格不可达：保留原输入与删除保证，核原 codec 拒绝且全库不变，不补编号或改来源以制造通过；另用明确的合法银行无编号合成文档，沿同一完整生产计算／提交实际导入、删除、重导，核新 ID、原墓碑、正式状态、备注及有效绑定。原始黄金不改；初始副本从 captured-before-native 清单恢复，不能复用已执行后的旧库。RecordWriter 未提交外层事务及旧物化版本合同另列后续，不在事务回调内套独立服务。
5. 审查：独立 5a 及新增分支复核无 finding，核原断言与顺序、真实完整计算／提交、原输入及种子摘要；原其他四十五个测试方法逐字保持。原支付宝空号不能宣称重导通过，新银行是独立合法覆盖，不改旧输入来源。计划动作、typed 状态、收据及实际存储四层保无草稿，旧墓碑／新 ID、绑定转移及原文保持。实际代码库防劣化判断本片仅测试归属及内部测试值／helper，无生产数据库／模型／DTO／接口或 UI 变化，契约与 UI 子专项不触发；无新生产 facade。最终独立门已核六条精确暂存路径、四份当前／受测／暂存 Kotlin 摘要、七份新鲜 XML 与全六十二项通过及原输入保持，仅 P3 ktlint 缓存结果的证据措辞已按终态日志修正，无遗留 finding。
6. 测试与 QA：比较基线及本片核对 HEAD 2de98446，2026-11-17（Asia/Shanghai），证据根 offline-ledger-1116/local-import-lifecycle。沿 run.py／Java 21／单 worker／3 GiB，final-complete 执行 :backend:migrationEvidenceTest --tests '*CashEntryServiceOriginalTest.importPublicPatchPreservesSourceAndProtectsEntryAcrossReimport' --tests '*CashEntryServiceOriginalTest.unprotectedReimportMatchesPreviewCommitAndFullRebuild' --tests '*CashEntryServiceOriginalTest.reimportCombinesOnePublicDecisionAndRejectsConflictingValues' --tests '*CashMutationRecoveryOriginalTest.unrelatedRealImportAndAccountRenameDoNotInvalidatePostedNote' --tests '*CashDeletionContractTest.deletedAlipaySourceReimportsWithIdAndRejectsMissingId' --tests '*CashDeletionContractTest.deletedBankSourceWithoutProviderIdReimportsAsNewRecord' --rerun，加 :backend:test --tests '*CashImportWriteSetHttpTest*' --tests '*CashMaintenancePreviewFailureTest*' --rerun 及 :backend:ktlintCheck／detektTest，138.38 秒成功。新鲜历史三类六方法及默认写集 HTTP 五十项全零；预览故障类属于历史分类，没有在默认 test 执行，不算通过。六方法实际循环六个公共字段向量、一个维护恢复、两个原删除向量与一个新增合法银行文档；原支付宝空号只证明删除后拒绝且不写，合法银行验证实际导入→删除→重导。final-postgres 在显式人工准备的真实 _test 库、独立十个 schema，以同六方法执行，9.20 秒成功，全零；测试不探测／启动数据库。两库十二历史方法加当前 HTTP 五十项共六十二次终态执行，零失败／错误／跳过。七份新鲜 XML、四份受测 Kotlin 摘要、原始输入与执行前种子摘要由 verification.json 固定；各副本逐原列行值比较，仅补 ledger_seq、entry identity、可空 business_label 及空新 audit 表。初轮零动作字段假设失败、一次编译错误及旧空号输入拒绝的证据保留，不计入通过，不修改生产或原黄金。后端测试编译及 detektTest 实际执行；ktlintCheck 及各检查汇总任务为 UP-TO-DATE，复用当前输入的既有结果通过；生产及共同依赖编译亦为 UP-TO-DATE。OpenSpec strict 27／27、doctor 健康、九文档 282 本地目标／45 锚点及 diff 检查通过；不以此关闭其他八项旧导入迁移证据或整个 change。
7. 发布：适用门禁通过后按同仓库同分支授权小步提交推送，保留待确认 UI。已核 README、docs、references、接口／命令示例、配置／迁移、功能地图、词表与评审记录；仅迁移测试前置及补合法／拒绝覆盖，无用户行为、公开协议、新概念或配置变化，现状文档及地图无需更改，原位更新 tasks／apply 指引。没有新的用户选择或应沉淀的通用规则；本片可整体回退，无数据回滚。Cross-platform Impact Check：消费既有共同计算，无 Web／Native／共享生产或页面变化，不重复无变化的平台编译与产品浏览器／设备 QA。旧 RecordWriter、草稿／来源问题等测试依赖及服务端内部旧预览／提交继续后续迁移，4.4／4.5 与整个 change 未完成，49／71，不归档、不创建 PR。

### 旧综合财务报表退役（4.3／4.4）

1. 思考：用户已明确选择整体移除旧综合报表；当前 Compose 无调用，独立投资事件／持仓／估值及账户余额合同保持。此决定解除物化表清理中的报表待决边界，不代表其他 4.3 依赖已清。
2. 计划：proposal／cash-ledger delta／parity 例外及 design 同步回写。删除真实 GET 路由、OpenAPI 方法及专用 report 组装／现金 SQL／月份解析；保留账户读取、原精确余额与估值、鉴权重验及实际快照隔离。旧 report 原始黄金保留，报表专用历史包装退役；混合黄金保留 accounts 子集、独立投资原断言及原输入，不重生期望。
3. 任务拆分：先补真实双库 HTTP 404、无报表读取／报价／写入及保留账户／投资入口回归，证明旧路由仍存在时失败，再移除生产链。独立 5a／防劣化契约、当前账户／投资回归及相称构建、地图／接口说明和精确提交门后同分支提交。
4. 构建：删除旧路由、OpenAPI 方法、Service／Repository 的 report／月份／物化查询及专用钩子，以及 Application 的 report／transactions／cashReporter 和专用文本／汇总 helper。FinanceDecimal、financeObject、listAccounts／balances 保持；账户查询继续同一事务及 PostgreSQL REPEATABLE READ，保 principal 与返回前鉴权重验。退役 Report 三方法及 QueryOriginal 两方法，原混合黄金只筛能力而不筛成功，49 应用／40 HTTP accounts 向量继续消费原输入、expected 与调用顺序；NoHome 保禁 HOME、初始化及余额检查。并发证据在真实 ledger_snapshots 结果取得行后、后续 accounts 读取前完成另一连接的实际记账和账户启用状态变化，本次核原完整输出、下一次核新余额和状态；无新生产回调。正在迁移的永久功能导入种子测试不混入本片提交。
5. 审查：独立 5a 与代码库防劣化实际调度的独立契约窄核均无 finding。FinanceDecimal、financeObject、listAccounts／balances 逐字保持，OpenAPI 精确只移除单一路径；账户事务隔离、原身份与返回前重鉴权、异常映射保持。49／40 按能力筛选全部原账户向量，保原负例、参数、精度和调用顺序。并发观察器在真实快照行取得后发布另一连接记账及账户状态，首读核原完整输出，下一读核新值；允许账户查询的原两次读取，不以错误的固定 SQL 条数弱化快照断言。最终独立门已核二十一精确暂存路径、受测／当前／暂存 Kotlin 摘要、210 份新鲜 XML 及 1,492 次全零执行，原输入／种子保持，允许按原授权提交推送；无遗留 finding。无 UI 改动，不触 UI 专项；待确认 UI 和永久功能导入种子 WIP 排除。
6. 测试与 QA：比较基线及本片核对 HEAD 6f3dd077，2026-11-17（Asia/Shanghai），证据根 offline-ledger-1116/local-import-lifecycle。report-retirement-red-current 在删除旧路由前实际执行新增两方法的双库四项，两个退役断言均因原 503 而失败、两个保留能力通过；冻结 XML 及源码摘要证明同一回归未改。final-sqlite 沿 run.py／Java 21／单 worker／3 GiB 执行 :backend:migrationEvidenceTest --tests '*FinanceQueryHttpTest*' --tests '*FinanceQueryBoundaryTest*' --tests '*FinanceQueryParityTest*' --rerun，加 :backend:test／ktlintCheck／detektMain／detektTest，310.27 秒成功。默认 204 类／1,482 项及历史账户三类五方法全零；49 应用与 40 HTTP 原向量在方法内完整循环，不以方法数冒充向量数。final-postgres 沿同一命令的历史五方法在人工显式准备的真实 _test 库运行，8.57 秒成功，全零；包含真实 JDBC 交错、权限变化及实际读取后故障。新增双库 HTTP 退役四项已在默认回归通过，涵盖四种月份参数、零财务查询／报价／写入及账户／独立投资 GET 保留。共 1,492 次终态执行，零失败／错误／跳过。verification.json／冻结 XML 核源码、新鲜度、原输入及种子摘要；四组种子副本只补当前版本元数据，保原列行值，PostgreSQL 由外部脚本显式准备，测试不探测或启动数据库。旧报表黄金保留，未重生期望。OpenSpec strict 27／27、doctor 健康、十四文档 314 本地目标／48 锚点及 diff 检查通过。后端生产及测试编译实际完成，后端静态检查实际运行；无共享或原生生产变化，不重复无变化的平台编译或产品浏览器／设备 QA。此前八项历史导入迁移证据仍未完成，不计入本片通过。
7. 发布：门禁通过后按同仓库同分支授权提交推送。已核 README、docs、references、接口／命令示例、配置／迁移、功能地图、词表和评审文档；更新总览、投资地图与架构账户说明、proposal／delta／design／apply 指引，OpenAPI 无旧路径。没有新增用户概念或配置，无需修改词表及迁移；本片整体回退即可恢复接口，无数据变更。用户明确选择只适用于旧综合报表，不沉淀为删除其他未调用接口的通用规则。Cross-platform Impact Check：当前共享客户端未调用旧端点，保留投资页面与端口；无呈现变化，不追加 UI 演示或设备交互 QA。4.3／4.4 与整个 change 未完成，保留待确认 UI、其余物化依赖及旧导入内部链，不归档、不创建 PR。

### 来源问题导入测试迁移（4.4／4.5）

1. 思考：来源问题的两个旧导入包装、独立保存及草稿合并的重导尾部依赖支付宝旧账号范围绑定；当前规格只读可靠的固定范围交易身份。原七个执行前种子及输入保持，原 helper 与当前 helper 在各自隔离副本运行四方法均同样失败，不把原失败宣称本次迁移回归，也不补全局绑定认领旧记录。本片先完成来源问题两项；另外两族保持原样，合法当前歧义初始化另行完成。
2. 计划：新增明确的当前状态合同：通过真实完整端侧计算与正式写集先创建可靠身份和候选记录，捕获实际 ID，再用真实来源仓储设置一／两个修订为 2 的待采用正文及候选。此设置不声称由匹配器自然生成。之后实际新别名 keep_existing 或显式 null defer，保所有原有效正文、候选、修订、身份及 presence 保证，不重生原黄金、不改旧来源／编号／身份。
3. 任务拆分：先新增真实双库两个方法，循环三种当前场景，绿后退役对应两项旧身份包装及专用导入 helper；其余二十三个方法、完整只读黄金及 files 保持。适用当前写集／维护回归、独立审查、防劣化范围判断、文档与精确提交门后同分支提交。
4. 构建：新类使用当前库／正式导入创建初始及候选流水；keep_existing 核一／两个问题及候选整表、原 row_revision、原字段／公共值、完整非绑定事实不变，原绑定保留并新增实际指向初始 ID 的别名，无新父记录。显式 source_payload:null 的 defer 核原 ID／字段、detail 的 null＋present=true、SQL 的 _source_payload_present=true＋null 本体及无额外候选。每次本地计算核全表零写。新双库四项先通过，再删除旧两个方法及专用 helper；Draft／Independent 源文件已精确恢复原样。
5. 审查：独立 5a 无 finding，确认当前初始化覆盖原有效保证，不以旧绑定兼容冒充当前行为；初始父与候选都由真实导入创建，问题状态的 writer 设置明确。keep_existing 保全问题／候选、字段／条目／修订、除绑定的全事实、原绑定及实际新增别名归属；defer 保明确 null／省略的区别和两层 presence。原其余二十三个方法逐字保持，files 仍供完整黄金；Draft／Independent 两源摘要精确恢复。实际代码库防劣化判断仅测试及初始化，无生产数据库／领域模型／DTO／接口或 UI 变化，契约与 UI 子专项不触发。无用 resultRows 空壳已删除，最终独立门已核四条精确暂存路径、两份受测／当前／暂存 Kotlin 摘要、三份新鲜 XML／七十八项全通过及原输入保持，允许按原授权提交推送；无遗留 finding。
6. 测试与 QA：比较基线及本片核对 HEAD c8163079，2026-11-17（Asia/Shanghai），证据根 offline-ledger-1116/local-import-lifecycle。source-import-continuity-verified 沿 run.py／Java 21／单 worker／3 GiB 执行 :backend:test --tests '*CashSourceImportContinuityTest*' --tests '*CashImportWriteSetHttpTest*' --tests '*CashCanonicalMaintenanceContractTest*' --rerun，加 :backend:ktlintCheck／detektTest，138.85 秒成功。三份新鲜 XML 为新类四项、写集 HTTP 五十项、规范维护二十四项，共七十八次终态执行，零失败／错误／跳过；三个类均实际 SQLite／显式真实 PostgreSQL 参数化。新类每库循环 keep_existing 的一／两个问题及显式 null defer，共六个场景分支，不以四个方法执行数冒充场景数。测试不探测或启动数据库。原始输入、七个初始种子摘要、原二十三方法、两个恢复源码及新鲜 XML／当前受测两份 Kotlin 摘要由 verification.json 固定。当前测试编译及 detektTest 实际执行；ktlintCheck 及检查汇总任务为 UP-TO-DATE，通过当前输入的既有结果，生产及共同依赖编译无变化亦复用缓存。OpenSpec strict 27／27、doctor 健康、九文档 282 本地目标／45 锚点与 diff 检查通过；首轮新四项及空壳删除前的终态不重复计入本次数目。

   maintenance-reimport-current／baseline 在七个各自新副本的四方法失败及完全相同消息保留：keep_existing 找不到可靠旧身份而 plan_input_invalid，另三项得到新的真实 ID。baseline 临时使用原三份源码、实际重编译及执行，finally 精确恢复候选；原始输入／种子不改，不补编号、改 scope 或认领旧 ID。该对照用于界定当前合同，不宣称旧七个原向量通过。新增当前问题状态由真实 writer 设置，只证明给定合法状态的服务保真，不声称匹配器自然生成链；merge／independent 当前可达初始化由下节验证；其他八项旧迁移证据仍另验。
7. 发布：适用门禁通过后按同仓库同分支授权小步提交推送，保留待确认 UI。已核 README、docs、references、接口／命令示例、配置／迁移、功能地图、词表与评审记录；只替换测试归属及当前初始化，没有用户行为、协议、新概念或配置变化，现状文档／地图不改，更新 tasks／apply 指引。没有新的用户选择或需沉淀的通用规则；本片可整体回退，无数据回滚。Cross-platform Impact Check：共同计算仅由后端测试消费，无 Web／Native／共享生产或页面变化，不重复无变化的平台编译及产品浏览器／设备 QA。原始失败仍是历史证据，不以本片关闭全部旧身份测试、4.4／4.5 或整个 change，49／71，不归档、不创建 PR。

### 独立保存及合并后的当前来源重导（4.4／4.5）

1. 思考：独立保存和草稿合并的旧维护保证仍有效，旧重导尾部的支付宝身份已不符合当前合同。此片为 A 类 change 内范围明确的测试迁移，不改变产品语义，保原输入、种子与基线失败证据。
2. 计划：用合法银行无编号的两个不完整导出，沿完整端侧计算和正式写集产生真实歧义及候选；通过实际维护 HTTP 选择独立保存或合并，之后清除真实暂存，再核完整收据重放和原文件重导。不得手工补绑定或伪造歧义。
3. 任务拆分：先证明当前 producer、问题及候选修订；补独立保存 resolve／finalize、合并 posted／draft 四分支；通过后才去掉旧重导尾部及专用 helper。原维护主段、其余方法及只读黄金保持。独立审查、双库、相关回归、文档与精确暂存门后提交推送。
4. 构建：新两方法经真实导入产生 A／B 不同 ID、source_identity_ambiguous、单候选 A 及当前修订，未手工设置问题。规范维护 HTTP 预览／详情全库零写、正式确认后，独立保存核 resolve-only 仍为草稿且字段／修订／余额／绑定保持，finalize 核正式状态；合并核目标原草稿／正式状态、手工空备注及标志、余额、来源软删除／不可读、所有绑定 ID 转移及正式 row 收据。实际 TTL 清扫删除暂存后，原 HTTP commit／resolve 完整收据相同且全表不变；再用 B 正式重导核原保留 ID、正式状态、无问题及空备注保持。草稿目标重导为额外覆盖，未代替维护后仍为草稿的断言。旧两族仅去对应重导尾部及专用 helper；其余三十九方法、两段维护主体和原 files／只读黄金保持。
5. 审查：独立 5a 无 finding，核真实 producer、候选／修订、各阶段状态、余额／绑定／手工清空／软删除及物理清扫后的原键永久重放。原两个新别名／显式清空测试方法逐字保持；旧两族共四十一方法除两处明确退役尾部外逐字保持。实际代码库防劣化判断纯测试迁移，无生产数据库、模型、DTO、接口或 UI 变化，契约与 UI 子专项不触发。最终独立门已核五条精确路径、三份受测／当前／暂存 Kotlin 摘要、七份新鲜 XML／八十六项全零和原输入／种子保持；两处过期待办文案已原位收口，无遗留 finding，允许按原授权提交推送。
6. 测试与 QA：比较基线及本片核对 HEAD 192068a2，2026-11-17（Asia/Shanghai），证据根 offline-ledger-1116/local-import-lifecycle。identity-continuity-current 沿 run.py／Java 21／单 worker／3 GiB 执行 :backend:migrationEvidenceTest --tests '*CashIndependentIdentityOriginalTest.independentPreservesEntityBindingsAndPermanentReplayAfterContextRemoval' --tests '*CashDraftMergeOriginalTest.mergeKeepsTargetIdentityBalanceAndPermanentReplay' --rerun，加 :backend:test --tests '*CashSourceImportContinuityTest*' --tests '*CashImportWriteSetHttpTest*' --tests '*CashCanonicalMaintenanceContractTest*' --rerun 及 :backend:ktlintCheck／detektTest，145.97 秒成功；默认三类八十二项及 SQLite 历史两项全零。identity-continuity-postgres 沿同两个历史方法在显式人工准备的真实 `_test` 库四个隔离 schema 运行，8.65 秒成功，两项全零。七份新鲜 XML 合计八十六次终态执行，零失败／错误／跳过；当前三类均两库参数化，新两个方法实际循环每库四种维护／重导分支，原历史每方法循环两个原场景。测试不探测或启动数据库。原始输入、执行前种子、三份受测源码、原方法完整性及 XML 摘要由 identity-continuity-verification.json 固定；副本仅补当前版本元数据，逐原列行值核相同。首次 producer 四项另存，不重复计入。后端测试编译、ktlint 测试源检查及 detektTest 实际执行，生产／共同依赖无变化复用缓存。OpenSpec strict 27／27、doctor 健康、九文档 282 本地目标／45 锚点及 diff 检查通过。
7. 发布：适用门禁通过后按同仓库同分支授权小步提交推送，保留待确认 UI。已核 README、docs、references、接口／命令示例、配置／迁移、功能地图、词表与评审记录；此片仅测试迁移，无用户行为、公开协议、配置或新概念变化，现状文档／地图无需修改，更新 tasks／apply 指引。没有新的用户决定或通用规则候选；此片可整体回退，无数据回滚。Cross-platform Impact Check：无共享／Web／Native 生产与页面变化，不重复无变化的平台编译、演示或设备 QA。原旧身份输入及失败证据保持，剩余旧编排／物化依赖、4.4／4.5 与整个 change 未完成，49／71，不归档、不创建 PR。

### 公共字段编辑的当前完整导入合同（4.4／4.5）

1. 思考：旧 EntryChoices 十一方法和仅供它使用的 API fixture 仍包装已退役上传／预览／确认。公共字段编辑、来源保真、失效依赖、候选只读及整体回滚保证须由当前完整端侧发现与正式写集承接；不能以手组 proposal 的分层单测代替完整流程。
2. 计划：新增当前双库完整文件计算测试，验证新建清空、混合已有／新增、来源 keep_existing／defer、缺失／过期 edit_ref、分类资格、重复无目标、真实写后原子回滚及三个候选成员的公共详情。先验证真实 producer 和既有覆盖，全部保证有对应证据后才退役旧包装，保原输入和黄金。
3. 任务拆分：先当前初始化及完整发现，补缺口，当前／共同回归及原输入核对，独立 5a／防劣化范围判断后精确提交；不恢复旧计划身份、旧 API facade 或旧绑定兼容。
4. 构建：当前完整文件九方法十八项双库合同覆盖新建清空及再次核对、无来源 operation 的既有成员与新增成员经真实发现合组、keep_existing／defer 下既有关系的独立公共编辑、未知引用与真实跨工作区分类拒绝、实际保存条目修订后拒绝旧引用、全重复无编辑目标、三个跨预览项候选只读详情，以及来源／待确认关系实际写后整体回滚。故障观察在同一捕获连接按原工作区精确核本次父数量、全部 draft 关系及非空候选数量，再抛原异常；核所有生成表回滚，原键原正文重试及精确重放。没有注入 proposal 或补旧身份绑定，来源原文保持。

   entry-current-complete 的实际 Task 红回归发现首次公共编辑也报 plan_changed：存储恢复输入只保存已选目标，不能作为尚未选择的当前目标。design 先 Flow-Back，再由 domain forPreview 仅深复制 typed 目标及最小选择上下文，必填私有工厂随本次 Calculation 进入 Task；当前计划按原四类选择及缺省归一提取本次目标后完整重算，重启仍沿已存恢复输入。闭包不捕获完整计划，不猜 JSON 类型，持久化仍只保存实际选择。未知目标／分类失败不分配新计划、不保存核对、不替换原结果；明确空值、编码重开及实际恢复通过。旧两类在补证通过后删除，DataFixture、十四份原输入和独立黄金保持。
5. 审查：独立 5a 与实际调度的独立契约窄核无生产 finding，确认 live／恢复上下文分离、四键归一、typed 精度、目标资格、原会话／文件／提交冻结和保存前失效边界保持。防劣化本片触内部计算／恢复合同，实际执行契约子审查；无 UI 组件或呈现变更，不触 UI 复用／视觉审查。P2 测试观察曾未限定工作区，已按本次精确写入量及 draft／候选核对修复并独立静态关闭；原全表回滚与原键重试保证保持。原八十六个 Task 方法逐字不变，红回归源码可从终态仅还原追加的缺省输入检查后精确复原其受测摘要，未弱化原分类及旧计划保留断言。最终精确暂存十四路径、八份受测源码、414 份 XML／2,718 次执行及浏览器证据经独立复核通过；design 的页面措辞已修正为 Task 接收目标，与只读页面现状一致，无遗留 finding。
6. 测试与 QA：比较基线及本片核对 HEAD f357daf9，2026-11-17（Asia/Shanghai），证据根 offline-ledger-1116/local-import-lifecycle。entry-current-final-jvm 沿 run.py／Java 21／单 worker／3 GiB 执行 :backend:test／installDist／ktlintCheck／detektTest、:business-domain:jvmTest／ktlintCheck、:shared:testAndroidHostTest --tests '*LedgerSessionImportsTest*' --tests '*LocalCashImportPlanReadsTest*' --rerun，342.54 秒成功；后端 206 类／1,508 项、业务核心 199 类／907 项、共享两类／92 项全零。后端新九方法实际 SQLite／显式真实 PostgreSQL 参数化，测试不探测或启动数据库。

   entry-current-platforms 执行业务核心 jsBrowserTest／wasmJsBrowserTest／iosSimulatorArm64Test 各筛 LocalCashImportReviewTest、共享 jsBrowserTest／wasmJsBrowserTest 各筛上述两共享类并 --rerun，另 :shared:compileKotlinIosSimulatorArm64／compileKotlinIosArm64 及 :webApp:wasmJsBrowserDistribution，329.93 秒成功；三业务核心端各九项，共享两端各九十二项全零，iOS 两架构编译实际执行。entry-current-domain-quality 执行 :business-domain:detektMetadataCommonMain／detektJvmTest，8.96 秒成功；业务核心生产／测试静态检查、后端测试源 ktlint 与 detekt 实际执行。完整 argv 固定于三个 command.json。两轮终态共 414 份新鲜 XML／2,718 次执行，零失败／错误／跳过；八份当前受测 Kotlin 摘要、原方法／输入和红灯保真由 entry-current-verification.json 固定。初轮编译错误、初始夹具缺来源显示名／错误预期／漏 workspace 参数及 Task 红灯保留，不计入通过，未改生产业务规则修绿。

   现有真实浏览器命令 run-compose-playwright.mjs compose-local-import.e2e.ts --grep 'CSV，联网' --reporter=line,json 在 Chrome、http://127.0.0.1:18511/、1440×1100 执行，40.87 秒一项通过：真实注册／登录、来源账户选择、断网准备与核对且确认禁用、显式重连及重核对后一次原写集确认、永久结果及离线冷启动账本／结果。旧导入请求为零，脚本控制台／非预期网络检查通过；两份 trace、七张截图与 JSON 报告在 entry-current-live-web，已查看实际保存结果截图。正式 Wasm 分发由匹配源码构建后沿仓库 freshness gate 复核；QA 后仅停止本次后台，保专用 SQLite 与用户原演示。当前页面只读，浏览器不证明公共编辑 UI，编辑由真实 Task 及正式 HTTP 证明。

   Java 21、256 MiB 独立保留量探针核 1,000／10,000 个合成目标：捕获 0.579／21.282 毫秒，保留堆约 0.65／9.49 MB；只选一目标保存始终 478 字节。原计划及无关 8 MiB 正文可回收，未捕获完整计划；这是该探针的实测，不是任意输入延迟承诺。没有截断或新增输入限制。原输入／黄金未改；OpenSpec、链接及最终 diff 另核。
7. 发布：门禁通过后按同仓库同分支授权小步提交推送，保留待确认 UI。已核 README、docs、references、接口／命令示例、配置／迁移、功能地图、词表及评审记录；无用户语义、公开 HTTP、配置或新概念变化，更新 design／tasks／apply 和导入地图，明确内部公共编辑与只读页面的实际边界；总览页面数及其他现状无需更改。没有新的用户选择或通用规范候选；此片可整体回退，无数据回滚。Cross-platform Impact Check：共享 Task／计算影响 Web 与 Native，完成上述共同执行、Android Host 及 iOS 编译、真实 Web 主流程冒烟；无视觉、文案或布局变化，不新建演示，不追加原生设备 QA，不据此完成 6.4／10.x 页面验收。剩余旧服务／物化依赖及整个 change 未完成，49／71，不归档、不创建 PR。

### 旧服务端导入协调器退役（4.4／4.5）

1. 思考：当前正式路由已不调用旧 HttpService／CommandService；剩余引用是历史提交／HTTP／寿命测试、一个维护预览测试和 Outcome 反射。先按当前合同逐项核对旧四十三个提交方法，不恢复会话单次消费、旧 resolve pending 或物化发布协议。原十份源码已固定于仓库外 legacy-coordinator-original，原输入和黄金保持。
2. 计划：正式写集承接事实、身份、余额、权限、整批回滚及永久收据，真实 Task 承接本地计划与恢复。已确认缺口为完整文件发现生成的联合退款第二条实际 INSERT 后失败及独立组整体回滚，以及导入草稿在相同 ordinal 替换新组件后正式入账；先补当前真实合同再删除旧协调器及专属包装。联合退款确认／整组暂缓与零净额另核当前完整计算和存储，不拿单边或阶段结束故障代替第二条真实写入。
3. 任务拆分：当前完整 producer 双库测试先运行，保存红／绿证据；独立核旧方法归属，再精确删除无使用实现与专属测试。保留共用维护提交边界、parse-only、永久收据及原解析材料；旧 CommitWriter 的唯一额外测试调用须处理后才可删除。随后后端全量、相称质量检查、独立 5a／5b 和文档核对。
4. 构建：当前完整 producer 新增七方法，双库三十二项先通过；覆盖三十二行双 alias、逐项人工公共值、单双组成项与两账户精确余额，既有 confirmed 退款下类型冲突零业务变化，四关联成员整组确认／暂缓及零净额实际列表，真实既有来源更新后第二关系 INSERT 的故障／全表回滚／原键重试，部分草稿、组件替换与原 ID 换序，以及新文件 alias 与同值重导。随后删除旧 Http／Command／CommitWriter 三实现与 OriginalCommit 四十三、旧 HTTP 四、旧寿命二、维护测试导入分支一和 RecordWriter 旧调用一，共五十一方法；Outcome 仅去旧反射尾段，无 SQL 测试保留。共用维护边界、解析、永久收据及原始材料保持。
5. 审查：独立旧保证归属核对已完成，三族补证后可成组退役，旧 100 阈值／SQL 顺序、会话消费、resolve 与物化版本协议不复活；第二关系故障点补实际旧行 note／修订读取，避免只看请求值；独立 5a 核旧方法归属与精确删除无遗留代码 finding；实际 5b 范围核确认不新增数据库、DTO、请求／响应、公开接口或 UI 合同，不另触契约／UI 子专项。维护余三、RecordWriter 余六及 Outcome 无 SQL／现行断言保持；原完整文件九方法及 helper 除测试连接装饰参数外逐字保持。最终独立门已核十五路径、五份当前／受测／暂存 Kotlin 摘要、二百零六份新鲜 XML／1,522 次执行、安装包无旧类及原断言／待确认 UI 保持；apply 遗留主体措辞已原位修正，无遗留 finding。
6. 测试与 QA：本片比较基线及核对 HEAD 7c867a2d，2026-11-17（Asia/Shanghai），证据根 offline-ledger-1116/local-import-lifecycle，使用 legacy-coordinator 前缀。current5 在旧包装删除前聚焦十六方法／三十二项双库先通过；初轮测试夹具缺实际支付宝说明、月份转换回调、SQLite SQL 引号及两处测试编译错误均保留，不作为生产缺陷红灯或通过证据。第二关系故障最终增加同连接实际旧行 note／revision 观察后，删除终态沿 run.py／Java 21／单 worker／3 GiB 执行 :backend:test／installDist／ktlintCheck／detektMain／detektTest，319.95 秒退出零。二百零六类／1,522 次执行零失败／错误／跳过，包含新七方法各在 SQLite／显式真实 PostgreSQL 实际运行；数据库不由测试探测或启动。终态生产／测试编译、安装分发及主／测试 detekt 均实际执行，ktlint 测试源实跑、主源沿删除检查；XML 全部新鲜并冻结，源码摘要与受测命令匹配。安装 jar 核旧 Http／PlanBuilder／Command／CommitWriter 类及嵌套类不存在，正式写集、维护和永久收据类保持。

   preservation.json 固定原九个完整文件方法、维护三项、RecordWriter 六项、Outcome 及二十五个待确认 UI 源码不变；original-manifest 固定十份旧源码。完整旧 Python 期望和原始财务输入未改，不把已退役五十一方法计入本轮通过。OpenSpec strict 27／27、doctor healthy、九文档 284 本地目标／45 锚点及 diff 检查通过。未改共享核心／客户端／UI、存储行为或公开传输，不以本片静态死代码删除要求重复原生编译或浏览器演示；当前正式路由及 parse-only／收据／维护边界由全量实际 HTTP 回归核对，不宣称页面或整个 change 验收完成。
7. 发布：适用门禁通过后按同仓库同分支授权小步提交推送，保留待确认 UI。已核 README、docs、references、接口／命令示例、配置／迁移、功能地图、词表及评审记录；无新用户语义、公开协议、配置或概念，更新 design／tasks／apply 与导入地图，主规格行为及总览页面数无需改变。没有新的用户选择或通用规范候选；可整体回退，无数据迁移或数据回滚。Cross-platform Impact Check：无 Web／Native／共享层生产或呈现变化；不重复无影响的平台／设备 QA。暂存仓储的导入专属辅助 API、剩余物化依赖、parity 清理及应用／UI 最终验收仍待完成，49／71；不关闭 4.4／4.5，不归档、不创建 PR。

### 旧导入计划的暂存副本与页面缓存退役（4.4／4.5）

1. 思考：旧协调器已退役，ParsedImportStore 的计划专用读写、副本和页面缓存只剩测试引用；MutationPlanStore 使用通用 create／get／replace／cancel。ParsedImportCodec 的跳过文件解码及注入已编码文件片段仅供旧优化路径。删除这些专用路径，保共享维护保存／清理、完整编解码、错误分类及生命周期。
2. 计划：先迁移仍有效的预算／容量、隔离、损坏与存储故障保证到通用仓储入口；只退役无生产用户的旧计划副本与缓存专属断言。保精确错误码、失败前状态、原 CAS／归属／取消／TTL 和完整文件／财务类型验证；不修改维护协议或引入新暂存结构。
3. 任务拆分：七份原源码已固定仓库外 legacy-staging-original。先在未删生产实现上运行改归属后的测试，再删除专用辅助／codec 优化分支，执行受影响及完整后端回归、相称质量检查、独立 5a／5b 和文档收尾。现合同退役不制造生产缺陷红灯。
4. 构建：通用入口改归属测试先在未删生产实现上通过八十二项；删除 PlanSnapshot、计划专用读／替换及页面缓存、codec plan-only 解码与预编码文件注入。保预算／容量／错误状态、完整数据隔离、归属与 TTL 合同；只去旧快照／缓存专属七个方法。
5. 审查：独立 5a 与实际代码库防劣化／契约窄核无 finding，保留通用预算／深度／CAS／归属／生命周期／provider 故障及清理保证；逐字重建确认 Store 仅删指定块、Codec 仅删旁路，普通编码／完整解码的验证、预算、字段顺序和格式保持。四个测试类按原保证迁移，仅旧快照／缓存专属七方法退役；维护计划五方法与 R2／清理生产边界均逐字保持。无 UI，不触 UI 复用／设计专项；最终独立门核十路径精确暂存、六份工作树／受测／暂存 Kotlin 摘要、二百零六份新鲜 XML／1,511 次执行与安装包，确认仅旧专属方法退役及改名、无意外方法丢失，文档与待确认 UI 范围保持，无遗留 finding。
6. 测试与 QA：比较基线及本片核对 HEAD d26a963a，2026-11-17（Asia/Shanghai），证据根 offline-ledger-1116/local-import-lifecycle，使用 legacy-staging 前缀。沿 run.py／Java 21／单 worker／3 GiB；baseline 在旧实现、current 在删除后均执行 :backend:test，筛 ParsedImportStoreTest、ParsedImportStagingOriginalTest、ParsedImportCodecTest、MutationPlanStoreTest、ParsedImportCleanupTest，包含 R2ParsedImportStoreTest；各六份新鲜 XML／八十二项全零。current 20.98 秒成功，不计为完整后端通过。

   final 执行 :backend:test／installDist／ktlintCheck／detektMain／detektTest，314.68 秒退出零；二百零六类／1,511 次执行零失败／错误／跳过，含当前 SQLite／显式真实 PostgreSQL 金融事务与维护合同。完整矩阵比基线少十一执行，对应退役七个仅旧快照／缓存方法，其中四方法原参数化两端；不新增 skip、不把删除项算通过。主／测试 ktlint 和 detekt 实际执行，生产与测试编译沿 current 同字节结果；固定终态 XML 和受测 Kotlin 摘要。安装 jar 与 javap 证实旧 PlanSnapshot／CacheKey／CacheValue 类型及旧辅助／旁路 API 均不存在，单参数完整 encode 和当前维护／R2／清扫类保持。

   preservation.json 固定两生产文件仅指定删除／旁路语法变化、五十九个未改测试方法逐字相同，维护 store／R2／清扫实现与五个维护测试不变；七份原源码在 original-manifest 固定。内存／R2 测试为实际仓储加模拟对象客户端，不宣称真实云 R2。原格式、原始输入及黄金未改，无数据迁移。OpenSpec strict 27／27、doctor healthy、九文档 284 本地目标／45 锚点及 diff 检查通过；未改共享／Web／Native 生产或呈现，不重复无变化的平台编译、演示或设备 QA，也不将本片当作整个 change 验收。
7. 发布：适用门禁通过后按同仓库同分支授权小步提交推送，保留待确认 UI。已核 README、docs、references、接口／命令示例、配置／迁移、功能地图、词表及评审记录；无新用户语义、公开协议、配置或概念，原位更新 apply 的旧快照／缓存说明及导入地图，design／tasks 记录实际边界；总览页面数及主规格行为无需改变。没有新的用户选择或通用规范候选；可整体回退，无数据或存储格式迁移。Cross-platform Impact Check：仅后端无调用的专用 API 退役，当前维护保存路径及传输不变，Web／Native／共享层无代码或呈现影响，未重复平台／设备 QA。独立 4.5 完成性审计确认共享暂存及清扫 worker 实际只服务维护，应保留；旧批量准备器及 files→空页辅助写入入口已退役，4.5 终态证据见下节。4.3 物化、4.4 parity 及应用／UI 最终验收仍未完成；4.5 终态见下节，不归档、不创建 PR。

### 旧导入批量写入准备器退役（4.4／4.5）

1. 思考：当前正式写集不调用 CashImportCommitPreparation／CashImportNewRowsRepository；两实现仅由历史准备五方法及旧版本一方法使用。有效保证包括多行组成项／来源别名／公共编辑／精确余额、一次版本推进、冲突整体回滚及文档空页一致性；现有正式计算与写集测试已承接，不恢复旧批量阈值、候选集合签名或被当前墓碑身份规则替换的拒绝协议。
2. 计划：当前完整三十二行计算补明确一次账本版本推进及收据版本；先在旧死实现仍存在时跑当前合同，再删除两实现与六项历史专属包装。共享来源规划、文档一致性、维护保存及原输入／黄金保留；另一个 files→空页写入 wrapper 尚有历史使用者，本片不关闭 4.5。
3. 任务拆分：五份原源码已固定仓库外 legacy-bulk-original，保历史有效断言归属；补证、删除、受影响／全量后端验证、独立 5a／5b、文档收尾及精确小步提交。只加强现有当前断言，不制造无产品缺陷的红灯。
4. 构建：完整三十二行 producer 补明确一次账本推进与收据同版本；旧实现仍存在时四个当前合同类／268 次执行先通过。随后删除两份死写实现、旧准备五方法及旧版本一方法；其余版本测试逐字保持，完整 producer 原身份／别名／公共编辑／余额／重放断言均保留。删除后相同四类／268 次执行通过；不改生产规则修绿。
5. 审查：独立 5a 与实际防劣化范围核无代码 finding；原六项有效保证由当前完整 producer、来源身份／文档和正式写集 HTTP 实际 SQL 后故障／全表回滚／原键重试承接。已逐字核三文件完整删除、旧版本仅十一行专属方法删除，其余方法保持；完整 producer 只增加版本同一性断言。5b 未改变现有 schema／格式、领域模型／DTO 或当前服务协议，不触契约子专项；无 UI，不触 UI 子专项。design 的前置 C 及原任务说明已改为正式来源不执行 C、Submission 在 entry_pre_edits 唯一执行，维护 writer 不叠接。最终独立门已核九路径精确暂存、两份当前受测／暂存 Kotlin 摘要、三份精确删除及二百零七份新鲜 XML／1,526 次执行，安装包无旧类且当前写集／维护保持；四文档与待确认 UI 范围准确，无遗留 finding。
6. 测试与 QA：比较基线及核对 HEAD 848ba378，2026-11-17（Asia/Shanghai），证据根 offline-ledger-1116/local-import-lifecycle，使用 legacy-bulk 前缀。baseline／current 沿 run.py／Java 21／单 worker／3 GiB 执行 :backend:test，筛 CashImportEntryContinuityTest、CashImportWriteSetHttpTest、CashImportWriteSetSourceIdentitiesTest、CashImportWriteSetSourcesTest；各四类／268 次执行零失败／错误／跳过，分别 43.64／42.33 秒。current 的生产及测试编译实际执行，未改当前服务规则。

   final 执行 :backend:test／installDist／ktlintCheck／detektMain／detektTest，另 :business-domain:jvmTest 筛 LocalCashImportSourceWritesTest 并 --rerun，307.85 秒退出零。后端二百零六类／1,511 次执行、共享来源写集一类／十五次执行，共二百零七份新鲜 XML／1,526 次执行全零；包括当前同一应用服务 SQLite／显式真实 PostgreSQL 合同，测试不探测或启动数据库。六个退役方法属历史分类，不在默认矩阵内；后端总数不减、不新增 skip、不计退役方法通过。主／测试 detekt、测试源 ktlint 实跑，主源 ktlint 沿删除检查；编译沿 current 同字节结果。安装 jar 核两旧实现及嵌套类不存在，当前写集、文档证据、维护与清扫类保持。

   verification.json 固定终态时间窗、XML 及当前受测源摘要，preservation.json 逐字核旧版本余方法与完整 producer 原断言，original-manifest 固定五份原源码。当前空页选择、子集／位置不完整及冲突命名空间的三项 shared producer 测试实跑；服务端空页一致性与真实 INSERT 后故障由上述当前双库合同验证。原输入／黄金、共享／Web／Native 生产代码及待确认 UI 未改；无数据迁移，不重复无影响的平台或浏览器 QA。OpenSpec strict 27／27、doctor healthy、九文档 284 本地目标／45 锚点及 diff 检查通过；精确暂存门另核。
7. 发布：适用门禁通过后按同仓库同分支授权提交推送；不包含待确认 UI。已核 README、docs、references、接口／命令示例、配置／迁移、功能地图、词表及评审记录；无新用户语义、公开协议、配置或概念，原位更新 design 的实际 C 接线、tasks、apply 及导入地图，其余当前主规格／总览无需更改。没有新的用户选择或通用规范候选；本片可整体回退，无数据迁移。Cross-platform Impact Check：仅后端无生产调用实现退役，当前公开服务与格式不变，Web／Native／共享层无代码或呈现影响；共享来源规则仅重跑已有单测，不重复平台／设备 QA。saveEmptyPages(files) 的旧发现写入 wrapper 已退役，有效证据／版本保证由明确证据及当前本地规则承接；保 saveEmptyEvidence 与共享来源规则、维护暂存和清扫。4.3 物化、4.4 parity 及应用／UI 最终验收仍未完成；4.5 终态见下节，不归档、不创建 PR。

### 旧文件空页发现写入入口退役（4.4／4.5）

1. 思考：正式来源写集只保存明确空页证据；saveEmptyPages(files) 已无生产调用，剩余为来源保存、账本版本和规范快照的历史测试。独立规划核对原四十四来源样本：三十五个无空页动作；九个空页样本中四个仍属证据保存合同，五个属于已移到本地的文件筛选。
2. 计划：保前三十五个样本及四个证据保存样本的原输入、错误、完整状态与顺序；后者明确核空文档 seed 后使用 saveEmptyEvidence，不将任意文件认作空页。五个旧 wrapper 样本保原材料，不计后端通过，由当前真实 common producer 的占用页、子集、位置和计数规则承接，补 skipped 行占页精确覆盖。版本及快照 seed 保原 namespace／coverage，跨表故障保六至九页观察和整体回滚。
3. 任务拆分：先固定原源码与输入、准备隔离双库夹具，新增本地生产规则测试及迁移测试先在旧实现存在时运行；删除唯一旧入口后复核当前正式双库矩阵与后端全量、安装包和相称质量检查。独立复核及完整调用图审计后判断 4.5 是否可关闭；4.3／4.4 不随之完成。
4. 构建：真实 common producer 新增 skipped 行仍占原页的精确回归，核无父项／绑定且仅页二为明确空页。三历史类改明确证据 seed，保原 namespace／coverage；旧实现仍在时 SQLite／真实 PostgreSQL 各十四方法先通过。随后仅删除 saveEmptyPages(files)，校验、明确证据保存及实际写页逐字保持；头注改为空页证据，不暗示原文件真实性。删除后同一两库矩阵各十四方法通过，原三十九存储样本、六至九页观察及失败整体回滚均保持。
5. 审查：独立 5a 与实际防劣化范围核通过，无代码 finding。已逐字核生产只删旧函数及头注、原三十五样本与四证据样本动作／错误／完整状态保持，版本／快照／回滚仅换 seed，新 producer 未复制筛选算法。未改当前 typed 证据 API／DTO、schema／格式或实际存储实现，契约子专项不触发；无 UI，不触 UI 专项。完整调用图审计未见旧会话发布／确认写链残余，维护通用仓储／清扫、parse-only、永久收据应保留；纯只读 native oracle 和测试适配归 4.4。最终独立提交门与 4.5 关闭门通过：九路径精确暂存、五份 Kotlin 摘要、二百一十三份新鲜 XML／1,555 次执行及安装方法均匹配；原 SQLite 副本三十四表全部行／列另核相等，当前调用图无旧导入写链，保留能力准确，无遗留 finding。
6. 测试与 QA：比较基线及核对 HEAD d6729692，2026-11-17（Asia/Shanghai），证据根 offline-ledger-1116/local-import-lifecycle，使用 legacy-empty 前缀。手动准备 source-write-v2 原库的隔离 SQLite 完整副本及显式真实 PostgreSQL `_test` 隔离 schema；原行、列、输入和期望保持，无 metadata edits。首次准备的列序比较及重复基线版本 stamp 错误已纠正，失败准备不计测试通过；原库和两份原 JSON 的 SHA 保持，测试不探测／启动数据库。

   旧函数仍存在时 baseline-sqlite／postgres 筛 CashSourceWriteContractTest、LedgerSourceVersionContractTest、LedgerSnapshotContractTest，经 :backend:migrationEvidenceTest 各三类／十四方法通过，13.14／7.77 秒；删除后同一 current 两库各十四方法通过，10.30／7.70 秒，39 原存储样本每库实跑，五个旧文件筛选样本明确退役不计通过。首次 producer JVM 十六项 25.33 秒通过，后续只整理新增测试的局部非空写法；当前精确源码以 final 重跑为准。

   final 沿 run.py／Java 21／单 worker／3 GiB 执行 :backend:test／installDist／ktlintCheck／detektMain／detektTest，及 :business-domain:ktlintCheck／detektJvmTest／jvmTest 筛 LocalCashImportSourceWritesTest 并 --rerun，329.27 秒退出零。后端二百零六类／1,511 次执行含当前正式双库应用合同，共同来源规则一类／十六次执行全零；加删除后两库历史合同共二百一十三份新鲜 XML／1,555 次执行零失败／错误／跳过，不把 baseline 或退役样本重复计为终态。主／测试 ktlint、detekt 及 domain 测试静态检查实跑；后端编译沿 current 同字节结果，domain 当前测试编译实际执行。未改共同生产实现或其他平台实现，新增共同规则由 JVM 实际执行，不重复无影响的浏览器／原生编译或设备 QA。

   verification.json 固定命令、时间窗、全部当前受测源码／XML 与安装 jar 摘要；javap 核 saveEmptyPages 方法不存在，validateEvidence／saveEmptyEvidence／savePage 保持，旧五个协调／批量类及嵌套类不在安装包。preservation.json 逐字核删除块、原当前十五个共同方法、来源 action／error／完整状态与第二方法、跨 parser／工作区及六至九页故障回滚；独立复核另核版本 seed／冲突和快照仅换显式证据。OpenSpec strict 27／27、doctor healthy、九文档 284 本地目标／45 锚点及 diff 检查通过；当前暂存与文档最终门另核。
7. 发布：适用门禁通过后按同仓库同分支授权自动提交推送，保待确认 UI。已核 README、docs、references、接口／命令示例、配置／迁移、功能地图、词表及评审记录；无新用户语义、公开协议、配置或概念，原位更新 design／tasks／apply 及导入地图，其他主规格／总览无需更改。没有新的用户选择或通用规范候选；可整体回退，无数据迁移。Cross-platform Impact Check：生产仅后端无调用 wrapper 删除，Web／Native／共享生产不变；新增共同测试验证原生产算法，无 UI，不重复设备／平台 QA。4.5 以完整调用图、真实双库、正式 HTTP 全量及安装方法证据关闭；旧 fixture 两条未通过记录移归 4.4，不当作通过或删除。4.3 物化、4.4 parity 和应用／UI 最终验收仍未完成，50／71，不归档、不创建 PR。


### 创建命令规范协调（4.3）

1. 思考：三类当前创建命令仍调用混合物化发布；该调用也承担规范条目协调，不能整体删除。按已确认 D3，仅去物化依赖，不移动这些无文件命令的计算入口，不改金融／权限／响应语义。此为 A 类变更内的独立可验证切片，4.3 及整个 change 仍未完成。
2. 计划：复用 maintainCanonicalCashEntries，以真实父流水 ID 协调；转账仅含现金侧，投资事件与 shares／cost 保持原命令。保完整组、稳定条目／成员、公共字段与人工标记，余额仍由原事务只写一次，不增加中间层、状态或配置。
3. 任务拆分：先保留三生产及七测试原源码、原输入与双库黄金；在旧生产下新增三类命令真实双库零物化 SQL／晚错回滚回归并复现失败，再最小替换三调用。迁移原写入及 HTTP 合同，独立 5a／5b 后运行当前全量与质量检查；文档和精确暂存门后按授权提交推送。
4. 构建：三生产文件各只替换一行协调调用。三旧写入类保全部十六方法，仅物化数量改规范 entry／member 数量并核旧物化状态不变、退役专属 source_revision 溢出触发；原晚错、全表回滚、精度／负零／校准、投资 shares／cost、一次版本和非法输入保持。两 HTTP 类原五十二／九十六样本请求、raw／raw_bytes、header、顺序、状态和完整响应不改；只明确扣除五张物化表，并严格核黄金表集合。现新增 identity_transaction_id 独立等于新条目 root 后，原其余列多重集全部对照。测试快照 helper 的显式表选择只服务当前 HTTP，默认七表路径保持。
5. 审查：独立 5a 已核三个真实父 ID、原锁／事务、前后鉴权、余额／响应未动及规范完整组协调，无 finding。实际 $codebase-degradation-review 调度独立 $contract-design-review，无 finding；未新增生产抽象、字段或持久状态，原黄金仅扣明示物化合同。无 UI，不触 UI 专项。最终两独立上下文分别核过十份当前 Kotlin 源码、二百一十六份新鲜 XML、原输入／黄金、安装包及十六路径精确暂存，无遗留 finding；未混入待确认 UI。PostgreSQL 起点的三十三表／十八序列计数已独立复核，两轮全部五十一个对象相等。
6. 测试与 QA：比较基线和核对 HEAD 880f0d1b，2026-11-17（Asia/Shanghai），仓库外证据 offline-ledger-1116/local-import-lifecycle，canonical-create 前缀。新增三个参数化方法在旧生产下六次执行均因实际物化 SQL 红灯；替换后完整 CashCanonicalMaintenanceContractTest 双库通过。当前最终命令沿 run.py／Java 21／单 worker／3 GiB 执行 :backend:test、:backend:installDist、:backend:ktlintCheck、:backend:detektMain、:backend:detektTest 并 --rerun，308.56 秒退出零；二百零六类／1,517 次执行零失败、错误或跳过。候选 main／test 编译已在此前同字节 exact-v3 实际执行，最终构建复用相同结果；主／测试静态检查实跑。SQLite／PostgreSQL 三写入类各十六方法通过，6.79／10.87 秒；两 HTTP 类每库两方法、原五十二／九十六样本通过，7.49／15.77 秒。最终共二百一十六份新鲜 XML／1,553 次执行全零，baseline 和失败轮不重复计为当前结果。verification.json 冻结全部十份源摘要、各次命令时间窗／XML 摘要、原种子／黄金和安装 jar；javap 核三调用均指向规范协调，无 CashProjectionService 引用；二十五份待确认 UI Kotlin 摘要保持。

   三份原 SQLite seed 在当前 DDL 的隔离副本及显式真实 PostgreSQL `_test` schema 中准备，原已有列／行不改，新增 identity／business_label／ledger_seq 使用当前缺省；空临时表和已删 subtype 列只在零行表省略，当前版本元数据单独记录。SQLite 使用原 full-sqlite-1103ba 黄金，PostgreSQL 使用原 full-pg-1103ba 黄金；请求材料逐项相等，黄金字节未改。首次错用 SQLite 黄金及 PostgreSQL 空父序列 false 基线的失败不计通过；原 PG 库不可达，测试 schema 显式设父序列 (1,true)，未改 golden 或 ID 断言。最终 exact-v3 在两轮前固定三十三张表的相同行以及十八条序列的 last_value／is_called，两个起始 JSON 完全相等；先用 HEAD 原三生产文件实际跑完整 PostgreSQL 原黄金通过，再恢复候选跑同一合同。前期基线未完整固定物化序列及误标的一轮不作为 before 证据，不能称原 PG 物理种子已恢复。命令、精确摘要、新鲜 XML、种子清单和原材料保留在外部证据目录，测试不探测或启动数据库。OpenSpec strict 27／27、doctor healthy；十份文档三百一十一个本地目标／五十八锚点及 diff 检查通过。
7. 发布：文档及验证完成后按同仓库同分支授权提交推送，不含待确认 UI；未部署或迁移。README、接口／命令示例、配置和主规格无新行为需更新，design／tasks、apply 技能、现金主说明／地图及总览同步实际接线；词表无新概念。用户未作新的产品取舍，无新通用规则候选。Cross-platform Impact Check：仅后端三创建命令内部协调与测试变化，Web／Native／共享生产及用户交互不变；受影响真实 HTTP 双库验证，不重复无影响的浏览器／设备 QA。可整体回退，无数据迁移，4.3／4.4、其他在线物化引用和应用／UI 最终验收保持未完成，不归档、不创建 PR。

### 永久收据与单笔详情定位（4.3）

1. 思考：4.2 已删除物化读取路由，但永久逐项收据与单笔详情仍查询 `cash_projection_states`／`cash_projection_members`／`cash_projections` 取得定位、待处理数和版本。客户端已按 `cash-entry:<entry_id>` 与真实 `ledger_seq` 消费，服务端须与同一事实对齐；本片只改读取定位，不删表、不动物化写入依赖，4.3 保持未完成。
2. 计划：收据页按本页真实未删除成员连接 `cash_entries`／`cash_entry_members` 生成定位，版本取同一事务 `workspaces.ledger_seq`；详情按规范条目整组成员计算问题／草稿关系去重待处理数，不读物化状态。永久收据自身 `ledger_seq`、结果、顺序、游标与所有者边界不变；缺失规范成员时服务端保留可读但不伪造定位，本地完整快照仍整体失败，差异写入 design。
3. 任务拆分：先在旧生产下以 SQL 探针拒绝任何 `cash_projection*` SQL 复现失败，再最小替换两处读取；覆盖分页边界、非代表成员、删除／缺失、大版本、未知 kind、viewer 与跨工作区、表内容不变及本地快照对照。之后独立 5a／5b、完整后端与质量检查、文档和精确暂存门。
4. 构建：`ReceiptReadService` 以成员连接条目并按 `transaction_id` 稳定输出本页定位，`CashRecordDetailService` 以条目整组行状态与关系待处理集合去重计数；两处版本均取 `workspaces.ledger_seq`，工作区行缺失时回落 `0` 而非空指针。未新增字段、端点、配置或持久状态，未改收据正文与详情其他字段。
5. 审查：独立 5a 复核无 critical；1 项 major 为“服务端缺失成员返回空定位、本地快照 `projection.incomplete`”的边界未记录，已写入 design 并补本地对照断言。minor 处置：最终测试版本补跑旧生产红灯；草稿关系与 inactive／ignored 关系分别隔离证明计数；定位顺序改为集合断言，避免把实现排序当合同；未知 kind 与已删除详情补断言；工作区行缺失改 typed 回落。5b 无新契约字段、无 UI，`projection_id` 值语义已由客户端 HTTP 边界消费，不触 UI 专项。
6. 测试与 QA：比较基线 `6caf78d0`，2026-11-17（Asia/Shanghai），证据根 `offline-ledger-1116/local-import-lifecycle`，`canonical-reference-*` 前缀。最终测试文件在旧生产下六次执行全部红灯（`ReceiptUnavailable`／`CashRecordReadUnavailable`，探针命中物化 SQL），修复后同六次双库通过；完整 `:backend:test`、`:backend:installDist`、`:backend:ktlintCheck`、`:backend:detektMain`、`:backend:detektTest` 以单 worker／非 daemon／无缓存／in-process 执行，206 份新鲜 XML／1,523 次执行零失败、错误或跳过。三份当前 Kotlin 源摘要记录在 `canonical-reference-final-command.json`；`git diff --check` 通过。
7. 发布：文档与验证完成后按同仓库同分支授权提交推送，不含待确认 UI 与 4.5 技能文档。README、接口示例、配置和主规格无新字段需更新；词表无新概念。用户未作新的产品取舍，无新通用规则候选。Cross-platform Impact Check：仅后端读取定位与测试变化，Web／Native／共享生产及用户交互不变；不重复无影响的浏览器／设备 QA。可整体回退，无数据迁移；4.3／4.4、物化表与其他在线引用保持未完成，不归档、不创建 PR。
