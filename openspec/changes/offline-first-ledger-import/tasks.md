# 任务

变更分级：A（跨模块架构重设计、公共契约变更、数据模型与持久化变更）。前置：`kotlin-backend-shared-core` 已完成 parity 并合入，Python 运行时已废弃。本变更在独立 feature 分支/worktree 中实施。

## 0. 七阶段与结论

- [x] 0.1 阶段 1 思考：阅读 `openspec/specs/` 相关主规格（import、cash-ledger、accounts、shared）、`openspec/project-context.md`、`DOMAIN_GLOSSARY.md` 与迁移变更记录，调用 `$openspec-explore` 固化取舍，结论回写 design.md
- [x] 0.2 阶段 2 计划：完成本 change 的 proposal/specs/design；如出现改变范围或语义的新决策，先回写 artifact 再实施
- [x] 0.3 阶段 3 任务拆分：核对每个 requirement 与本文件任务、失败测试、审查、验证与文档收尾的对应关系（核对中发现缺口：新增 4.5 删除服务端旧导入写路径）
- [ ] 0.4 阶段 4 构建：在独立分支按本文件小步实施，测试先失败再转绿
- [ ] 0.5 阶段 5 审查：产品/范围、工程、最终 diff 三类审查；DX/安全/设计按风险触发

本轮审查范围：来源资金扫描／分组及标签规则（基线 `0014d05a`）。独立 5a／5b 无 finding：scope／digest 材料与顺序、昵称优先级、账号脱敏、首次组插入／行引用／币种顺序、深复制和 parts-too-large 单行隔离保持。原 protected parts／组件解析仍实例分派，其余内部故障不变坏行；码点偏移只用于已计数边界。固定资金模式区分 Java Unicode／ASCII 和邮箱 Python 空白集合。5b 确认模型／JSON／mapping 与预览消费者无新契约，UI 不适用；整个 change 终审仍未完成。

- [ ] 0.6 阶段 6 测试与 QA：执行双后端契约矩阵、三端编译与单测、Web 真实浏览器冒烟，并运行 `openspec validate --all --strict` 与 `openspec doctor`
- [ ] 0.7 阶段 7 发布：记录交付证据、发布准备与回滚信息（未上线，无流量切换），并按用户实际决策完成规则沉淀反思

## 1. 共享规则下沉（先服务端复用，行为不变）

- [ ] 1.1 先写失败测试：共享层对查重、配对、投影、余额规则给出与既有服务端实现等价的确定性结果
- [ ] 1.2 将现金流水/组成项、来源身份、关系、entry、映射、分类等写集 DTO 收敛进 `business-contracts`
  - 已完成首片：`CashSourceCoverage` 数据形状与文档证明校验移入 contracts，backend typealias 复用同一类型；原字段、默认值及显式暂存编码保持。`RawCashRow`／`CashStatementDocument` 已移入 contracts，reader／PDF 进程／normalizer 复用同一类型，原 null、原文、位置、计数及默认值保持。资金 Group／Part／Row／Scan／Resolution 同形计算模型已共享，原显式 JSON 注入平台值编码；未新增线路 DTO。完整写集 DTO 仍未实现。
- [ ] 1.3 将查重与来源身份、关系配对管线、投影派生、余额规则下沉 `business-domain`
  - 已完成首片：`CashSourceScope` 迁入共享层，backend 使用 typealias；平台交易编号、来源账户范围、Python 空白与 Unicode 码点边界保持原规则。覆盖证明的包含性／可比较性已移入共享扩展函数，JVM 时间类型由 backend 薄适配；文档证据、命名空间和完整性合并已迁入 `CashSourceDocuments`，后端以 typealias 复用。`SourceOccurrence`、`SourceBindingKey` 与 provider 身份摘要已共享，定位验证、文档／文件／账户范围与绑定顺序保持；`CashAmount`、精确金额入口及记录类型／子类型目录已共享，保留原数值相等与原始表示；字段决定、来源备注与完整性／组成项校验编排已移入 `CashRecordPolicyRules`，JVM 原始值归一和深层相等适配仍保留；Resolver 的来源分组、可靠证据、歧义／冲突与多重集合查重已共享，backend 保留原索引／摘要／字段值桥接；Rows 的文件／已解析行组合和输入模型已共享，原暂存显式编码不变；Batch 的批次查重、文件时间优先级、最大多重数、紧凑歧义结果组已共享，backend 保留原内容值桥接；来源行类型判断、渠道分类与优先级已共享到 `CashRecordTypeRules`，原生值文本／真假仍由 backend 桥接；对方账号识别、属性校验与来源重建证明已共享到 `CounterpartyAccountRules`，JVM 正则以旧实现差分保护替换成码点扫描；关系端点校验、角色推断与证据门禁、影响闭包、提交分组和候选类型目录已迁入共享 `CashRelationPolicy`，后端类型别名及 JVM 输入／图金额适配保留；来源流水视图、核心符号谓词、渠道／作用域判断、稳定引用与排序已共享到 `CashRelationCore`，后端仅保留符号和原 JVM 时间适配；镜像、转账与退款候选索引已共享到 `CashFactCandidateIndex`，原金额键／时间／日期由 JVM 薄适配，端侧有 CashAmount 默认入口；提案／证据模型、退款文本门及完整菱形链路匹配已共享到 `CashRefundSignals`／`CashRefundDiamondRules`，端侧默认精确位串比较容差；标准／定向转账及个人购汇 Phase C 已共享到 `CashTransferMatchRules`，账号识别、时间窗口、贪心占用及 seed 语义保持；几何规则的业务日期、卡尾、镜像代表与连通分量已共享到 `CashRelationGeometryRules`，原 JVM 时间对象边界薄适配；关系计划模型 `RelationPlan<A>` 与稳定来源引用／提案键已共享，后端仍保留原始行、时间、余量与完整序列化适配；完整镜像的确定性分组、贪心占用、互相唯一与弱建议已共享到 `CashMirrorMatchRules`，41 次端侧原始调用与 43 次后端原始回放保护行为；平台退款状态门／硬键／窗口／最近唯一／残余复用与转账退回编排已共享到 `CashPlatformRefundRules<A,T>`，backend 保留原始金额和宽松日期桥接；Phase A 硬键汇总已共享到 `CashRefundHardKeyRules<A,T>`，复用平台退款规则，保留作用域分组与精确余量递减；完整行归一决定已由共享 `CashStatementRowNormalizationRules<A>` 与生产入口 `normalizeLocalCashStatement` 组装；完整文件调度仍待完成。
  - 商户退款强弱证据、额度、事件折叠、最近唯一和 open-leg 决定已迁入 `CashRefundMatchRules<A,T>`；`CashAmount` 新增精确数值排序。backend 保留 Java Unicode 商户正规化、BigDecimal 与 Duration 原边界；端侧生产 Unicode 正规化仍待完成。

  - 关系发现 Phase B→D 总管线及 MatchContext 已迁入共享 `CashRelationPipelineRules<A,T>`，复用同一镜像／转账／菱形／商户规则；Phase A 重叠降级、镜像事件余量共享、阻塞传递和上下文占用保持。后台精确累计值适配保留，端侧生产累计值适配仍待完成。

  - 关系计划稳定引用序列化、事实明细形状和 applied_amount 初始余量已共享到 `CashRelationSerializationRules<A>`；原始行读取、时刻及 native 标量解释仍由 backend 适配。common JSON 已知整数显式编码，保留 Float 1.0 及金额原精度，PlanCache 的退款全额占用语义未合并。

  - 稳定引用解码、人工决定复用、seed／组成项展开、别名索引及退款全额剩余值已共享到 `CashRelationPlanCacheRules<A>`；backend 保留原始值真假／整数解释及原图合法性校验，提案／缓存图校验已迁到下述共享 validator，客户端生产接线仍未完成。

  - 关系余额／损益摘要已共享到 `CashRelationProjectionRules<F,A>`；common `projectCashRelationFacts` 使用模块内精确累计并返回字符串合计，保留事实字段范围与累计范围分离。完整账本逐行投影及图验证由下述共享规则复用，浏览接线仍未完成。

  - 投影枚举／失败类型、确定性连通分组、单根树校验、来源排序及转账标签已共享到 `CashProjectionTopology`；native 保留 BigInteger 标识与 BigDecimal 符号桥，完整逐行投影和完整金额图／联合决定已迁入共享 `CashRelationGraphRules<I,T>`，native 保留严格整数解释并委托。端侧生产配置与浏览接线仍待完成。

  - 逐行投影事实／关系／结果／build 模型已共享，I/T 仅隔离平台标识与时刻，native typealias＋companion 扩展保留旧 API；共享 factory 维持金额→币种→状态／标识→时刻校验顺序。完整逐行算法已迁入共享 `CashProjectionRules<I,T>`，backend 委托；原代表 JSON 字符串顺序、重复退款分配键和逐步 80 位精确累计保持。完整金额图／联合决定已迁入共享 `CashRelationGraphRules<I,T>`，native 保留严格整数解释并委托。端侧生产配置与浏览接线仍待完成。

  - 关系计划的上下文／外部依赖摘要与虚拟快照标识已共享到 `CashRelationPlanFingerprints<A>`／`cashRelationSnapshotId`；native 仅传金额 plain 文本与原开放值 JSON，完整规划校验／管线入口已共享，客户端生产配置仍待接入，依赖摘要不等同于核对结论指纹。

  - 提案／缓存计划合法性、虚拟投影上下文与局部影响子图已共享到 `CashRelationValidationRules<A,I,T>`，native 复用同一模型并只解释平台原语。完整 Phase A→D 入口已由下述共享规则接入。

  - 完整 Phase A→D 计划入口与来源元数据补全、既有关系阻塞已共享到 `CashRelationPlanningRules<A,I,T>`；native 仅保留精确金额原语及原始行解析桥，复用已有规则实例。原始输入和生产端侧累计配置不由 test-only fixture 适配代替。

  - 来源说明、支付方式／商户归一及备注优先级已共享到 `CashSourceEffects`／`cashRecordSourceNote`，native 委托；冻结字符类、UNIX_LINES 与品牌忽略大小写口径保持，补充平面数字在 Wasm 使用完整字符分支。完整原始行归一决定已由 `CashStatementRowNormalizationRules<A>` 共享，native 仅提供原金额、时间、原始值／正则等薄桥；端侧生产行归一入口 `normalizeLocalCashStatement` 已组装并跨端回放全部非空原 oracle，文件读取器和完整导入仍待接线。

- [ ] 1.4 服务端改为复用共享实现，保持既有对外行为不变；跑通现有后端测试作为基线
- [ ] 1.5 为共享规则引入显式规则版本，并让规则版本随快照/派生结果可观察
  - 已完成：`business-domain` 新增 `CASH_RULES_VERSION`（`cash-rules-v1`）并有稳定性测试
  - 已完成：快照必填 `rules_version` 返回共享 `CASH_RULES_VERSION`，轻量版本查询仍只返回事实版本
  - 待办：端侧缓存／派生结果携带并消费规则版本，实现规则变化后的失效与重新派生
- [x] 1.6 前置（1.3 的硬阻塞）：为共享层提供多平台原语——SHA-256 摘要、码点计数与迭代、Python 浮点文本、`LedgerDecimal` 十进制表示；先写失败测试，再让 backend 复用同一实现
  - 已完成：多平台 SHA-256（含标准测试向量）、`pythonCodePoints`/`pythonCodePointCount`/`pythonStringOrder`、`pythonWhitespace` 已落 `business-domain`，失败测试转绿（`:business-domain:jvmTest` 4/4）
  - 已完成：backend 复用——`tokenDigest` 走共享 `sha256Hex`（保留 `strictUtf8`），`pythonStringOrder` 委托共享实现；`SourceDigestParityTest` 1/1、`CashSourceIdentityGoldenTest` 4/4 通过
  - 已完成：`sourceJson` 稳定 JSON 结构下沉共享（精确十进制与浮点文本**由调用方注入**，后端注入 `BigDecimal`/`pythonFloatText` 以保持既有 digest 口径）；等价性 oracle 共 19 个测试通过（`SourceDigestParityTest`、`CashSourceIdentityGoldenTest`、`CashSourceResolutionGoldenTest`、`CashSourceMatchGoldenTest`、`CashSourceScopeOriginalTest`、`CashSourceBatchGoldenTest`、`CashSourceRowsGoldenTest`）
  - 已完成：`pythonFloatText` 以 IEEE 754 位模式与精确中点区间生成跨平台最短文本，backend 委托共享实现；私有有界整数算术不改变 `LedgerDecimal`。固定 CPython 位模式向量、全部 2,046 个正规二进制指数及两侧相邻值（含负数）、2,000 个固定随机位模式与十进制计数边界，对照迁移前 JVM 实现。
  - 已完成：`parsePythonCashTimestamp` 使用共享 `kotlin.time.Instant` 与纯日历运算，保留 ISO 周日期消歧、显式偏移／naive 策略、微秒截断、Unicode 分隔符及范围错误。backend 仅适配 JVM 时间类型与既有错误；共享入口在替换前直接通过现有 20,053 条 Python 时间样本与旧实现对照。
  - 已完成：Python Unicode 14 大小写表与上下文 Sigma 逻辑共享；native 只保留单码点查询桥及共享 lower 委托，原表逐字等价。12,625 条 CPython 向量与纯规则／双库服务回归保护同一口径；不改变商户匹配器 JVM 大小写合同。
  - 已完成：来源时间归一及上海／香港完整历史切换共享，98 次历史转换私有固化，4,454 条独立 JVM atZone 向量和原完整 helper 差分通过；来源时区不依赖端侧本机时区或固定 UTC+8。
  - 剩余来源实体下沉归任务 1.3：来源键、文档模型与 Resolver／Batch／Rows 已共享，backend 保留 JVM 原始值桥接；完整归一、关系和投影的决定已共享，但原始值生产配置、读取器与端侧接线仍未完成，不能宣称完整端侧导入已可用。

## 2. 服务端：工作区版本与全量快照

- [x] 2.1 先写失败测试：版本单调递增、提交失败不推进、双后端等价
- [x] 2.2 为每个工作区维护单调递增账本版本，与规范事实写入同事务推进（PostgreSQL 工作区行 `FOR UPDATE`；SQLite `BEGIN IMMEDIATE`）
  - 已实现基础：Alembic `20261104_54` 增加非负 64 位 `workspaces.ledger_seq`，jOOQ 由真实 PG 18 生成。连接内标记按物理事务／工作区合并，保存点准确恢复标记状态；计数器双库回归覆盖并发、保存点交错、回滚与溢出。下述各写入口已接入并完成独立写面审计；基础测试与真实入口回归分别核对。
  - 已接入目录写入口：账户命令、分类目录修订、别名写入、账单账户映射发布；对应原服务和 HTTP 回归在双库通过。来源绑定、问题及候选、认证空页证据亦已接线并通过双库回归；原子流水创建与条目／成员仓储亦已接线并通过双库回归；普通／批量导入、草稿合并、人工创建与删除亦已接线；当前／旧关系、候选失效及类型解除、分类直写、资金对应关系与旧逻辑删除亦已接线；手工转账关系独立仓储亦已显式标记；全量 DML／批量 store 与辅助仓储所有者完成审计。分类删除由同事务 CategoryService.bump 覆盖，删除引用清理由 CashDeletionService 标记，投影调整成员委托 CashEntryRepository。工作区整体删除先撤销会话，已删除空间不再提供版本或快照；新建空工作区初始版本为 0。余额、投资事件、游标与物化投影写入不属于快照事实。
- [x] 2.3 先写失败测试：版本查询只返回版本、隔离与权限
- [x] 2.4 提供轻量版本查询接口
- [x] 2.5 先写失败测试：快照在只读事务锁定版本 `V`、分页不跨版本、隔离与权限
- [x] 2.6 提供全量事实快照接口，覆盖端侧缓存所需全部规范事实（含只读 `cash_investment_funding_relations`）
- [x] 2.7 双后端契约矩阵：版本、快照事实、精确金额与时间值等价（SQLite + 真实 PostgreSQL `_test` 库，显式 `FT_TEST_POSTGRES_URL`）
  - 同一固定合成 fixture 的完整 HTTP 响应已在双库逐字段对照。`LedgerSnapshotParityTest` 与版本／写入口／快照／OpenAPI 矩阵各 11 类 71 方法通过、0 skip；2026-11-04 17:41 UTC，验证基线 `f891b743`。PG 显式目标 `ft_offline_ledger_1104_test`（127.0.0.1:5432），配置目录 `sqlite`／`postgres-5432`；原始日志 `snapshot-identical-{sqlite,postgres}.log` 在仓库外。fixture 输入和期望独立，15 集合、修订、金额、时间与 JSON 不做输出归一化。


## 3. 服务端：写集提交

- [ ] 3.1 先写失败测试：结构/不变量校验、非法关系集拒绝、金额精度拒绝、工作区隔离
- [ ] 3.2 实现写集校验（权限、账户资格、修订号、金额与币种精度、引用完整性、关系图合法性），不重跑启发式
- [ ] 3.3 先写失败测试：单事务全成功/全失败、版本推进、收据写入
- [ ] 3.4 实现写集落库：现金流水与组成项、关系与候选、entry 与成员、待处理问题、账单账户映射与来源绑定
- [ ] 3.5 先写失败测试：数据层幂等（来源身份去重、关系指纹去重）、提交层幂等（同键返回首结果）、结果未知可按原键核实
- [ ] 3.6 实现两层幂等与收据/核实接口
- [ ] 3.7 先写失败测试：基线过期整批拒绝并返回可识别错误；提交与校验同事务（CAS/`FOR UPDATE`）
- [ ] 3.8 实现基线校验与拒绝
- [ ] 3.9 双后端契约矩阵：写集落库事实、幂等结果与精确金额等价

## 4. 服务端：移除投影物化

- [ ] 4.1 先写失败测试：端点删除后旧读取面返回可识别错误/404，写集提交与快照不受影响
- [ ] 4.2 删除 `GET /cash-projections`、投影详情与证据、`operations/projections(+rebuild)` 等读取面
- [ ] 4.3 新增 Alembic 迁移删除 `cash_projection_*` 表；`validateSchema()` 必需表集合同步更新
- [ ] 4.4 标废并清理迁移变更中被本变更覆盖的 parity 项与测试映射
- [ ] 4.5 删除服务端旧导入写路径（导入会话/预览计划/确认提交端点及其实现与暂存清理），仅保留 8.2 的无状态 parse-only；`import/cash-import-session` 的 REMOVED 需求随之生效
  - 核对旧 fixture 归属：原 Alipay 7CSV 回放首个文件缺交易订单号，违反当前 statement-import 的缺列拒绝规格，在 `597a800c` 原实现亦 `statement_header_not_found`；未计通过，不篡改原夹具或放宽读取器。外部 `platform-alipay-baseline.log` 留有验证证据。

## 5. 端侧：本地缓存与安全

- [ ] 5.1 先写失败测试：缓存事实范围、工作区隔离、版本替换的原子性
- [ ] 5.2 落地本地库 schema 与索引（账户、流水与组成项、来源绑定与证据、关系与候选、问题、entry 与成员、分类、映射、别名、funding 关系）
- [ ] 5.3 先写失败测试：加密落盘、密钥取自平台安全存储、登出/失效清理
- [ ] 5.4 实现缓存加密与登出/失效清理
- [ ] 5.5 先写失败测试：版本未变不重下；版本变化原子替换，不混入旧版本
- [ ] 5.6 实现刷新流程：版本查询 → 全量快照 → 原子替换并记录已同步版本

## 6. 端侧：导入阶段 1–7 与写集提交

- [ ] 6.1 先写失败测试：CSV/XLSX 解析 + 归一化/类型分类与既有结果等价
  - 已完成文本与归一部分：平台 CSV 文本／银行 TSV 记录解析及平台声明、表头、行预算已共享，10 个原文本向量完整回放；已解码文档可调用 `normalizeLocalCashStatement`，11 平台＋14 银行＋6 工行原始案例在各端按原字段／错误断言通过；UTF-8／GB18030 字节解码已共享并有独立全空间对照；银行文本规则和工银亚洲 BOM UTF-16 TSV byte 入口已共享；CCB／平台工作簿二进制读取及完整文件调度仍未完成，不能勾选完整任务。
- [ ] 6.2 先写失败测试：对缓存账本查重、账户归属、操作计划的结果与既有服务端实现等价
  - 已共享 `CashFundingResolutionRules<A>` 的账户选择、币种／可用性、分配合并与就绪决定；后端仍注入原金额／类型／深复制／入账验证缓存。来源分组／扫描已共享至 `CashFundingScanRules`；NFC／原支付组件及端侧生产值配置尚未完成，不以 common 测试值桥代替。
- [ ] 6.3 先写失败测试：候选召回与关系配对结果与既有实现等价，且相同输入确定性
- [ ] 6.4 实现端侧导入阶段 1–7
- [ ] 6.5 先写失败测试：写集构造完整、稳定输入可重派、决策按稳定引用复用、指纹一致静默重提交、不一致提示重核对
- [ ] 6.6 实现写集提交与冲突重派
- [ ] 6.7 实现离线 outbox（含退避与重试上限）与 pending overlay 展示

## 7. 端侧：派生投影与浏览切换

- [ ] 7.1 先写失败测试：投影归属、内部转账不计收支、退款冲抵、余额校准不显示、双后端事实等价
- [ ] 7.2 实现端侧投影/筛选/分页/月度汇总派生，并接入账本浏览
- [ ] 7.3 移除对服务端投影读取面的依赖；账本版本变化时整包替换并保持筛选条件
- [ ] 7.4 规则版本变化时触发整体重派生

## 8. PDF parse-only

- [ ] 8.1 先写失败测试：只返回归一化行、不读账本、不写业务数据、密码不留存
- [ ] 8.2 实现无状态 parse-only 端点并接入端侧 PDF 路径

## 9. Web 持久化

- [ ] 9.1 先写失败测试：Web 端本地库读写与跨会话持久化
- [ ] 9.2 落地 OPFS + SQLDelight-wasm，并处理 Web 静态加密口径（见 design Open Questions）

## 10. UI、跨端影响与演示确认循环

- [ ] 10.1 先写失败测试：离线状态、冲突重核对提示、静默重提交后的结果展示
- [ ] 10.2 实现导入流程的端侧化与相关状态（加载/空/错误/冲突/完成）
- [ ] 10.3 执行 Cross-platform Impact Check：写明 Web、Native、共享层是否受影响及理由（Web compact 与 Native Phone 共用产品合同）
- [ ] 10.4 完成首版后，做相称编译验证并在真实浏览器做代表视口视觉校验，随后启动开发/QA 演示模式交给用户确认（中间检查点）
- [ ] 10.5 按反馈迭代，只补跑受影响的编译与 Web 视觉/交互校验，用最新构建重新演示
- [ ] 10.6 用户确认方向后完成剩余实现与细节修整，再进入最终审查与回归

## 11. 最终验证与文档收尾

- [ ] 11.1 双后端契约矩阵完整跑通（SQLite + 真实 PostgreSQL `_test`，显式 `FT_TEST_POSTGRES_URL`；未配置记未完成，不宣称通过）
- [ ] 11.2 三端相称编译与单元测试；Web 真实浏览器冒烟（记录浏览器、URL、视口、步骤、控制台/网络错误）
- [ ] 11.3 独立复核：产品/范围、工程、最终 diff；按风险触发 DX/设计/安全复核，finding 与处置理由回写 artifacts
- [ ] 11.4 `openspec validate --all --strict`、`openspec doctor`、`git diff --check`
- [ ] 11.5 同步 `docs/feature-map.md`、`DOMAIN_GLOSSARY.md`、现金导入流程文档与本 change 相关的接口/命令示例
- [ ] 11.6 在 tasks.md 记录当前 `HEAD`、比较基线、实际命令、结果、执行时间与遗留风险；重跑受影响的验证


### 当前切片验证与收尾证据

验证时间：2026-11-04 21:02 UTC。验证时 `HEAD` 与比较基线均为 `0014d05a`。共享 scanner 入口／原生 Unicode 空白边界测试先失败，再迁移原组装与标签逻辑；原 protected 覆盖与类型别名消费无需改测试。

在仓库根显式复用仓库外合成 operation fixture 执行：

```sh
FT_KOTLIN_IMPORT_ORIGINAL_OPERATION_CONTRACT_DIR=/Users/huangwenlong/.cache/finance-tracker/offline-ledger-1104/funding-resolution-fixtures NODE_ENV=development ./compose/gradlew -p compose --no-configuration-cache :business-domain:jvmTest :business-domain:jsNodeTest :business-domain:wasmJsNodeTest :business-domain:testAndroidHostTest :business-domain:compileKotlinIosSimulatorArm64 :business-domain:compileAndroidMain :backend:test --tests '*CashImportFundingTest' --tests '*CashImportFundingOriginalTest' --tests '*CashFundingFailureOriginalTest' --tests '*PostingValidationIntegrationTest' --console=plain
```

`BUILD SUCCESSFUL`。共享 JVM 81 类 252 方法，JS／Wasm／Android host 各 62 类 225 方法、0 skip／失败，iOS simulator 编译通过；native 4 类 54 方法、0 skip／失败。原 121 个扫描／决定 JSON oracle、内部故障传播、实例隔离和原组身份／标签测试保持。JVM 固定资金模式逐范围／组值对照原 Regex；common 验证昵称归一回调位置、稳定组、原文保留、全卡号遮蔽、NBSP 与 Python 多出的 C0 边界。common NFC／支付方式桥仅为有限测试值，不视为真正生产端侧配置。

全部向量生成器 `--check`、OpenSpec 严格校验与索引 diff 检查提交前执行。外部日志 `funding-scan-{red,jvm,platforms}.log` 保留。NFC／原来源账户与支付组件解释、端侧标量／posting 适配、工作簿及页面接线尚未完成。

本片不改数据库或服务端写入规则，不重复双库服务矩阵。最近真实双库服务证据基线 `0c47dae7`，SQLite 14 类 104 方法与 PostgreSQL 19 类 122 方法全部 0 skip／失败，见仓库外 `python-case-services-{sqlite,postgres}.log`；不据旧证据宣称本 change 全量矩阵完成。

Cross-platform Impact Check：共享资金决定及既有读取／归一各端验证，页面仍走服务端，平台／CCB 工作簿和文件调度、资金归属／完整导入、缓存、写集与浏览接线未完成，无本片 UI／浏览器／设备 QA。README、词表、导入操作说明和接口示例无用户可用行为变化；apply 技能、模块／总功能地图与 design 原位更新。无新用户取舍，无需新增规范。
