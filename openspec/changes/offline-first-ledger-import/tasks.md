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
- [ ] 0.4 阶段 4 构建：在独立分支按本文件小步实施，测试先失败再转绿
- [ ] 0.5 阶段 5 审查：产品/范围、工程、最终 diff 三类审查；DX/安全/设计按风险触发

查询与原始筛选切片（比较基线 `155d257b`）、永久收据版本切片（比较基线 `6743bb09`）、本地浏览 owner 切片（比较基线 `12f862e9`）、公共字段选择切片（比较基线 `ec99d059`）、关系选择失效核心（比较基线 `71bc96a2`）、候选查询规划（比较基线 `1818da04`）、计算值摘要（比较基线 `f2563ce4`）、关系上下文模型（比较基线 `a7d4c264`）及本地关系上下文读取（比较基线 `5ad85b26`）已完成独立 5a 范围／工程／安全／最终 diff 及实际 5b 契约复核，无遗留 finding。owner 的撤权竞态 P1 已由唯一同步围栏修复，真实线程交错先红再绿并重审关闭。收据新增 P2 已先红再绿并重审关闭：显式 null 与旧字段缺省分开，只在该字段使用受限解码；不修改全局 JSON。两项 P2 均先红再绿：公开 Map 的外部修改由副本隔离，内部固定范围不变；极端金额指数按用户确认的整数 20 位／小数 18 位在展开前拒绝。各切片的实际双库／跨端矩阵与范围见下方证据；实际加密驱动、应用会话及页面尚未接通，不关闭 7.2 或整个 change。


- [ ] 0.6 阶段 6 测试与 QA：执行双后端契约矩阵、三端编译与单测、Web 真实浏览器冒烟，并运行 `openspec validate --all --strict` 与 `openspec doctor`
- [ ] 0.7 阶段 7 发布：记录交付证据、发布准备与回滚信息（未上线，无流量切换），并按用户实际决策完成规则沉淀反思

## 1. 共享规则下沉（先服务端复用，行为不变）

- [ ] 1.1 先写失败测试：共享层对查重、配对、投影、余额规则给出与既有服务端实现等价的确定性结果
- [x] 1.2 将现金流水/组成项、来源身份、关系、entry、映射、分类等写集 DTO 收敛进 `business-contracts`，补齐本次编辑意图；新公开入口仅允许文件导入核对
  - 条目以完整业务分组及全部 entry_dependencies 表达；旧依赖包含最终退休对象，前置编辑核真实旧归属，严格拒绝旧 winner target／identity／deleted_entries 字段。模型、引用／拓扑、完整旧组门及跨端验证和独立复核已完成；实际 Long／UUID 元数据、完整效果和事务仍归 3.2／3.4。
  - 有序 relation_groups 保存全部新关系选择组的完整边分区及明确 common_fields，与行前置 C、归并后 D 分开。序列化、引用覆盖／顺序、三态 active 边及公共补丁浅归一已先红再绿并完成跨端及独立复核；仅完成传输与意图表达。可信旧人工值的自动继承、联合批次、三阶段效果及真实提交仍待 3.2，不把 DTO 完成当服务接通。
  - `CashSourceCoverage`、`RawCashRow`／`CashStatementDocument`、资金 Group／Part／Row／Scan／Resolution 与 PDF 归一文档线路模型已共享；原 null、原文、位置、计数、默认值及显式平台编码保持。来源候选与操作 expected_revision 保留完整 Long。最终写集 DTO 已覆盖交易／组成项、来源绑定／空页、关系／候选、问题／候选、条目／成员及映射；已有／新建引用显式区分，分类引用已有目录 ID。序列化不等于合法性校验或服务接线，审查及验证见本页写集传输证据。
- [ ] 1.3 将查重与来源身份、关系配对管线、投影派生、余额规则下沉 `business-domain`
  - 本地候选读取已按完整事实快照组装，平台先取标准化编号，缺失才由平台适配按映射惰性读取 JSON；通用候选／配对不读取原始平台 JSON。原 14 个候选用例、标准化值优先、平台回退及原文不读取回归已覆盖；不把非文本值序列化为编号。查询窗越过 year 1／9999 的原 SQL 时间绑定已由真实双库原查询复现，专用合成库事务完整回滚且全库不变。用户已确认现金发生时间统一限制为 UTC 1900..2100（录入／导入／写集校验），投资及系统创建时间除外；共享业务门、服务端现金入口、客户端提交前校验和边界回归已完成本页专项，新写集校验入口仍待接入，不能以当前 14 例宣称完整写集日期范围已交付；本地准备配置见对应证据，完整发现与业务计划仍未完成。
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

  - 工作表 XML 的深度、行／列／坐标预算与错误优先级由 `CashWorksheetXmlGuard` 共享，现有 StAX 入口委托同一守卫；XML 语法、字节解码、ZIP 和 POI 单元格读取仍在原生边界。native ZIP 预检已按实际累计限量读取，并对预算内完整工作表校验，修复 64 KiB 片段导致正常大表误拒及后段坐标错误；目录按实际空内容确认。完整端侧工作簿读取仍待完成。

  - 可变预览状态与正式图／草稿投影组合迁入 `CashImportPreviewSnapshot<I,T>`，backend 仅保留原值工厂、严格 ID、图及普通投影桥，原 graphBuilder copy 注入不变。状态组合不替代下述操作叠加的独立验证；本地初始组合见本页工厂证据；完整发现、业务计划与页面仍待接通。

  - 拆分公共值恢复与关系组图应用已迁入 `CashImportRelationDecisionRules<I,T>`，backend 注入原成员／确认闭包读取和标量桥；边映射、链接及闭包工具单源共享。保留图内自动撤回、显式决定拒绝、公共字段冲突转待定与原异常；完整端侧组装仍待接线。

  - 操作叠加、类型修改的关系／资金失效与整条操作回退已迁入 `CashImportPreviewOverlayRules<I,T>`；backend 保留初始读取、原值／时刻／事实工厂及条目公共值读取。完整端侧初始组装及预览仍待完成。

  - 冻结核对结果、成员详情／关系影响、稳定 entry 目标及处理／文件结果已迁入 `CashImportPreviewPresentationRules<I,T>`；backend 只桥接精确标识、虚拟部分及原值复制／entry 排序。单一文件结果和成员引用函数亦已共享，不改页面或文案，完整端侧业务计划与预览仍待接线。

  - 提案到关系建议组、精确依赖摘要和有界候选组合已共享 `CashImportRelationGroupRules<I,T>`；native 注入原精确 ID／金额及微信原消费决定，发现管线与完整端侧生产值适配仍需接线，不以纯测试配置替代。

  - 预览来源增强、保留编辑目标与种子读取编排已迁入 `CashImportPreviewPreparationRules<I,T>`，Prepared 模型单源共享；native 注入原授权／账户／候选／上下文读取，不替代完整端侧候选查询或本地预览工厂。

- [ ] 1.4 服务端改为复用共享实现，保持既有对外行为不变；跑通现有后端测试作为基线
  - 已关闭来源触达基线 finding：saveOccurrence 返回真实 insert/update 结果，writer 累计全部来源更新后加入 touched，恢复原 normal 等全部 15 个 writer case 的固定期望；不改既有 ledger_seq 标记。新增无问题项的真实 duplicate 提交证明补 provider 绑定触发投影维护，独立新会话同值重导不维护；多来源全部执行、coverage 更新及同值 no-op 亦已回归。双库证据与独立审查见下方来源触达小节；1.4 的完整共享迁移仍未完成。
  - 原生完整操作基线已对齐当前原导出器：固定支付宝交易范围、条目身份默认取根流水、无法读取金额以 null 保真。当前完整 oracle 从未修改的原导出器整包生成，未手改期望；SQLite 全部 36 原操作用例加完整 golden 通过，PG 完整 golden 通过。完整逐流水决定已迁至 `CashImportOperationRules`，backend 只保留原 SQL／标量桥；本地 `prepareLocalCashImportOperations` 已从同一快照组合来源准备、账户选择、字段策略及完整操作决定；页面和持久化尚未接入。
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
  - 已完成：来源昵称 NFC 复用私有 JVM 基线数据与共享规范排序／组合，完整 Unicode／Hangul 与非法 UTF-16 对照通过，不做兼容归一；冻结数据变化沿用规则版本评估。
  - 已完成：来源账户 Java Unicode word／68 个 Nd 数字区块及金额边界由共享词法实现，8,604 条原模式向量与全部码点差分通过；与 Python 14 数字表保留独立口径。
  - 剩余来源实体下沉归任务 1.3：来源键、文档模型与 Resolver／Batch／Rows 已共享，backend 保留 JVM 原始值桥接；完整归一、关系和投影的决定已共享，但原始值生产配置、读取器与端侧接线仍未完成，不能宣称完整端侧导入已可用。

## 2. 服务端：工作区版本与全量快照

- [x] 2.1 先写失败测试：版本单调递增、提交失败不推进、双后端等价
- [x] 2.2 为每个工作区维护单调递增账本版本，与规范事实写入同事务推进（PostgreSQL 工作区行 `FOR UPDATE`；SQLite `BEGIN IMMEDIATE`）
  - 已实现基础：Alembic `20261104_54` 增加非负 64 位 `workspaces.ledger_seq`，jOOQ 由真实 PG 18 生成。连接内标记按物理事务／工作区合并，保存点准确恢复标记状态；计数器双库回归覆盖并发、保存点交错、回滚与溢出。下述各写入口已接入并完成独立写面审计；基础测试与真实入口回归分别核对。
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

新入口限于文件导入及核对。无文件的来源事项、身份草稿合并、删除及主动拆分仍由后端既有命令承担；共享辅助器与通用 DTO 不构成开放这些动作的依据。原文件导入只更新 live import／legacy，已有正式金融字段及类型固定，不删除父项或主动拆分旧确认组；新入口须从可信事实验证这些边界及完整归并效果。

- [ ] 3.1 先写失败测试：结构/不变量校验、非法关系集拒绝、金额精度拒绝、工作区隔离
- [ ] 3.2 实现写集校验（权限、账户资格、修订号、金额与币种精度、现金 UTC 1900..2100 发生时间范围、引用完整性、关系图合法性），不重跑启发式
  - 条目完整效果以成功派生的业务值为断言，实际身份／修订／退休由真实事务产生；先验证完整旧组整体落一个最终组及全旧修订读集，不只验选中成员。收据保存实际身份与退休结果；原溢出／碰撞沿事务拒绝，不改选对象或抹平程序异常。传输与引用／拓扑已按该所有权实现；文件专用内部门已核完整旧组不拆／存活，效果重放及事务服务尚未完成。
  - B 自动继承存在待核顺序风险：原 DecisionRules 按投影 cash:rootId 顺序首次填联合 decisions，虚拟／实际新 ID 可改变排序。已有 allocation 仅影响元数据证明限于 effective B 已确定之后；完整文件候选替换链可达性尚未实测。先以真实 GroupRules→ChoiceRules→DecisionRules 保留两份不同旧人工值、至少一 accept 及 defer／ignore 候选替换验证，不凭静态推断改旧业务或扩 wire；公共值完整服务完成前须解决此项。
  - 合并前公共选择须按全部新关系选择组边分区和原顺序重建联合批次，defer／ignored 边同样参与原分组；公共编辑父集合仅取各组边端点。复用真实旧值与前置编辑核冲突／自动继承，不信最终公共值或客户端 joint ID；相同关系幂等时连同选择消费，不能重新 chooseCommon。模型及输入守卫已提供；独立审查指出全部 inactive 且空补丁可穿过，已先失败回归并按原文件三动作均保持 active=true 修复。内部批次准备已复用原纯分组规则并保留新旧引用隔离与完整顺序；公共继承、三阶段效果及事务重放仍待实现，不能以已共享 chooseCommon 算法算完整效果。
  - 行核对前置公共编辑须独立于来源 edited_fields／最终条目编辑表达；先用原实际仓储及传输回归固定 S／C／D，再补可选 entry_edit、真实旧条目修订／不存在依赖和公共 patch presence。引用及归一守卫不得将最终 standalone 当授权；完整服务仍须按原共享合并、applyEdit、关系协调、最终 patch 的顺序核对。
  - 文件动作范围门先失败回归：拒绝父项删除、无文件事项／拆分决定、已有关系重写和非 live import／legacy 的流水更新；仅约束写入目标，不筛掉只读上下文。绑定阻止另一 live owner 的迁移，保留已软删 owner 的原合法复用；引用／固定错误顺序及输入不变一并核对，不把该门当完整授权或事务。
  - 最终条目值输入先复用五公共字段归一，成员独立值须为原完整对象；人工／本次编辑字段只接受原白名单，最终临时拆分标记不持久化。先测属性类型、缺失／多余字段、组内显式空值、未知人工／编辑字段及三态最终标记；不将标记声明或shape通过当作人工意图／效果证明，成员历史分类不在此层扩资格检查。
  - 内部最终条目拓扑门复用同次确认图及精确引用，草稿单成员不造时间；种子含交易／确认关系、绑定新旧父项、显式条目及拆分选择的真实旧成员。先测漏合并／拆分、错误根／组外身份、空条目、删除退休遗漏、绑定单独改根及新草稿／虚拟引用；闭包核完整增量，不修复无关未物化事实。此门不核稳定身份选择、公共值／人工或独立值，完整效果及服务仍待实现。 target／identity 断言已随 wire 显式替换为完整分组覆盖和所有受影响旧条目修订依赖，退休不由客户端指令指定；文件专用内部门已核完整旧组不拆／存活；真实效果与服务接线仍待实现。
  - 条目协调先抽取不含时间的内部计算分组，原投影入口原样委托，供后续有效草稿与已验证确认图共用；以无发生／创建时间的新草稿组、完整旧协调测试及跨端矩阵验证。该抽取不验证写集最终效果，不关闭本项。
  - 合并前公共选择沿原 chooseCommon 下沉，保留公共字段过滤、类别检查、惰性单成员创建、每父修订及空补丁不 flush；先测类别／归一错误顺序、共享条目重复推进、明确空值、故障和溢出，再复跑原生双库与跨端。已用原仓储固定标识对照验证预建步骤的身份效果；完整 checker 的重放、标识解析及效果仍待实现。
  - 固定字段核对后补状态方向回归：原导入／维护不将已有正式流水退回草稿；不得靠改状态规避已正式约束。草稿保存／转正式保持原身份，是否可转正式仍由完整问题、账户及组成项检查决定。
  - 通用模型已有拆分意图表达及引用回归，仅保护既有无文件维护语义。新文件导入不提供主动拆分／删除，不将 entry_split_choices 接为导入能力；接线前收敛公开模型并核对旧完整成员及身份锚点仍在同一最终组内，不能按选中成员推断。
  - 原条目公共字段 patch 已共享：共享修订／字段边界、人工标记及单成员恢复，原仓储负责查询和 flush；先以显式清空、同值重放、过期修订、无效字段、溢出和程序故障回归，不改变已有保护口径。
  - 原条目 reconcile 纯协调已下沉，人工公共值结果验证复用该核心；复用现有投影模型并参数化条目流水标识。保持身份选择、共同字段冲突、成员独立值／拆分继承、修订推进及新成员登记时点，SQL／flush／savepoint 所有者保持原仓储；以纯规则边界和原完整条目／关系调用对照验证。泛型协调器不等于最终写集意图、动作效果或真实事务已接通。
  - 既有来源事项准备器已下沉共享核心，原生继续消费并保留 JSON／摘要与错误桥，原完整 source-resolution golden 保护既有行为。此辅助器不接入新文件导入端点，不以已有代码扩大维护入口范围。
  - 最终人工标记与本次导入编辑意图已分开表达。通用 DTO 中既有四种来源事项动作及合并目标依赖不属于新入口；公开接线前收敛可写合同并拒绝越界维护。真实旧公共值、导入字段白名单、标记推导及问题退役效果仍须验证，不新增长期保护字段。原读取未填 protectedFields／普通 manual_fields 为空，不能假称旧导入已接保护；3.2 仍未完成。
  - 前置独立工程／安全复核确认：旧 writer 依赖服务端计划重建，不能直接暴露给客户端。新入口从 DB 构造真实 current／before，验证固定字段与人工保护、正式／草稿完整性及账户资格；以伪造 current／组件快速路径、过期修订、跨工作区账户／分类、重复或悬空虚拟引用、错父组成项、关系 action／边不一致、过期候选及 entry 目标／成员变化作为先失败回归。引用预检、金融值归一、正式／草稿完整性及最终生效确认图纯校验已提供；真实事务与完整业务合法性仍未接通，不计整项完成。仅复用 pure graph／规范事实仓储，不恢复旧 ready 投影门或将非法关系降为部分成功；准确边界见 design D3。
- [ ] 3.3 先写失败测试：单事务全成功/全失败、版本推进、收据写入
- [ ] 3.4 实现写集落库：现金流水与组成项、关系与候选、entry 与成员、待处理问题、账单账户映射与来源绑定
- [ ] 3.5 先写失败测试：数据层幂等（来源身份去重、关系指纹去重）、提交层幂等（同键返回首结果）、结果未知可按原键核实
- [ ] 3.6 实现两层幂等与收据/核实接口
  - 当前切片补齐永久收据的精确 ledger_seq 摘要、原键／ID 读取及 shared 类型传递；先验证规范字符串、超 JS 安全整数、非法类型／损坏持久值及原收据缺省不变。写集事务填写真实版本和最终 commit／resolve 接线另验，不以可保存字段计整体幂等完成。
- [ ] 3.7 先写失败测试：基线过期整批拒绝并返回可识别错误；提交与校验同事务（CAS/`FOR UPDATE`）
- [ ] 3.8 实现基线校验与拒绝
  - 首片为托管事务内的 `requireLedgerBase`：锁定真实工作区版本，精确比较而不推进、不缓存；实际改动仍复用每事务一次的版本标记。新增真实账户写入与收据、整体回滚、相同基线并发、锁持有、savepoint、超出 JS 安全整数和事务生命周期回归；完整写集服务／HTTP 接线及鉴权后收据重放顺序仍待完成，不勾选 3.7／3.8。
- [ ] 3.9 双后端契约矩阵：写集落库事实、幂等结果与精确金额等价

## 4. 服务端：移除投影物化

- [ ] 4.1 先写失败测试：端点删除后旧读取面返回可识别错误/404，写集提交与快照不受影响
- [ ] 4.2 删除 `GET /cash-projections`、投影详情与证据、`operations/projections(+rebuild)` 等读取面
- [ ] 4.3 新增 Alembic 迁移删除 `cash_projection_*` 表；`validateSchema()` 必需表集合同步更新
- [ ] 4.4 标废并清理迁移变更中被本变更覆盖的 parity 项与测试映射
  - 原 `CashProjectionGraphReadMigrationTest` 整类在本片执行 11 方法，3 个旧物化结果的 `cash:<root>` 查询断言仍为 null（equal allocations、local split、local closure），其余通过；未改旧断言或计全类通过。新本地快照测试直接比较当前 native 完整 source build，独立于旧物化行标识；旧物化退役时按接口／模型实际归属处理。
- [ ] 4.5 删除服务端旧导入写路径（导入会话/预览计划/确认提交端点及其实现与暂存清理），保留 8.2／8.4 的 PDF／旧 XLS 无状态 parse-only；`import/cash-import-session` 的 REMOVED 需求随之生效
  - 核对旧 fixture 归属：原 Alipay 7CSV 回放首个文件缺交易订单号，违反当前 statement-import 的缺列拒绝规格，在 `597a800c` 原实现亦 `statement_header_not_found`；未计通过，不篡改原夹具或放宽读取器。外部 `platform-alipay-baseline.log` 留有验证证据。

  - 旧 `sourcePlanReadsAndViewMatchPythonContracts` 已保留完整新 oracle 比较；其旧 `suggestion.account` 字段缺失在 `fb038773` 原 planner 下亦失败。聚焦 source 新方法不替代这项完整旧合同。保留旧接口时必须按正式账户快照合同覆盖有／无建议分支并修复；实际退役时只随接口／模型明确退役清理该测试，不为通过而删字段、固定 `null` 或忽略整用例。

## 5. 端侧：本地缓存与安全

- [ ] 5.1 先写失败测试：缓存事实范围、工作区隔离、版本替换的原子性
  - 内部 SQL 存储已验证完整 15 类非空事实、范围复合键、精确版本／标识／金额和 JSON 数字词法、关闭重开及真实 SQL abort 保旧；加密缓存端口和生命周期尚未接入，不以内部存储计完整缓存验收。
- [ ] 5.2 落地本地库 schema 与索引（账户、流水与组成项、来源绑定与证据、关系与候选、问题、entry 与成员、分类、映射、别名、funding 关系）
  - `SqlLedgerSnapshotStore` 使用 SQLDelight runtime 2.4.1，完整 DTO 为单个 TEXT，单条 UPSERT 原子发布事实／版本／规则版本；仅内部读写及删 SQL 行，不实现加密端口、不管理文件或密钥。事实查询索引仍待实施。
- [ ] 5.3 先写失败测试：加密落盘、密钥取自平台安全存储、登出/失效清理
- [ ] 5.4 实现缓存加密与登出/失效清理
- [ ] 5.5 先写失败测试：版本未变不重下；版本变化原子替换，不混入旧版本
  - 刷新端口层已覆盖未变化不下载、完整替换、失败保旧、并发刷新、版本回退、旧登录迟到响应及清理失败重试；内部 SQL 快照的真实落盘／重开与失败保旧已验证，仍未与刷新端口及加密驱动组合，不以分层测试计整体通过。
- [ ] 5.6 实现刷新流程：版本查询 → 全量快照 → 原子替换并记录已同步版本
  - `LedgerSnapshotSync` 与 `KtorLedgerSnapshotSource` 已组合版本、整包读取和原子缓存端口；登录范围固定，网络不占用发布／清理锁，关闭和权限失效封闭旧实例。成功清理只一次，旧 401／403 不能清新实例；ledger HTTP 本身不清共享 token，所属会话边界仍须接线。实际加密库、密钥清除和应用生命周期发布尚未完成。

## 6. 端侧：导入阶段 1–7 与写集提交

- [x] 6.1 先写失败测试：CSV/XLSX 解析 + 归一化/类型分类与既有结果等价
  - 平台 CSV／银行 TSV 原文本与文档归一、UTF-8／GB18030 字节解码、工银亚洲 UTF-16 TSV 与值型 XLSX 均有本地入口。原 10 个文本、11 平台／14 银行／6 工行归一案例及全空间编码对照沿既有证据保留；本片以真实原 8 份 XLSX 完整原始文档／归一及准确失败码对照保护读取结果。旧 CCB XLS 无状态服务端原始文档与端侧归一已在 8.3 的独立双库矩阵验证。
  - 新读取器连接 kmp-zip 2.1.0、xmlutil 0.91.3、OPC、样式／共享字符串／活动工作表及原数字／日期规则；原 14 成功／14 失败编码矩阵、真实日期／数值、字段 namespace／结构、错误值及空值回归通过。按用户决定不支持 XLSX 公式：所选表任意公式整份拒绝，普通等号文本保留，不用缓存或服务端回退。各端真实字节回放与 JVM／Chrome 规模探针均通过，具体范围见本页证据；不据此声称完整文件调度或页面已接通。未覆盖的编码／OOXML 特性和资源上限见 design。
- [ ] 6.2 先写失败测试：对缓存账本查重、账户归属、操作计划的结果与既有服务端实现等价
  - 已共享 `CashFundingResolutionRules<A>` 的账户选择、币种／可用性、分配合并与就绪决定；后端仍注入原金额／类型／深复制／入账验证缓存。来源分组／扫描已共享至 `CashFundingScanRules`；原支付组件决定已共享到 `CashSourceAccountMappingRules<A>`；NFC 已由 `normalizeCashNfc` 共享；Unicode 模式已由 `CashSourceAccountTextPatterns` 共享；端侧来源账户生产配置已由 `LocalCashSourceAccountMapping` 接通，`scanLocalCashFunding` 使用真实模式／NFC／精确金额／摘要／深复制，完整回放 64 个原扫描案例。`resolveLocalCashFunding` 已接真实共享入账策略及严格 JSON 修订值，完整回放另 57 个原 resolve 案例；`prepareLocalCashRecord` 已接通全部字段策略和 JSON 数字词法，资金 posting 复用同一生产值桥；对缓存查重、操作计划及其余导入生产接线仍未完成，不以 common 测试值桥代替。
  - 来源计划准备已共享至 `CashImportSourcePlanningRules`，native 仅提供原上下文读取与批次解析；`resolveLocalCashSourceRows`／`resolveLocalCashSourceFile`／`resolveLocalCashSourceBatch` 已接真实本地金额、时间、字段及摘要；`prepareLocalCashImportSources` 已以单个完整快照接入候选、绑定、组件与认证空页读取；`prepareLocalCashImportInput` 已接通保存的账户／行／关系／entry 选择结构校验；完整本地操作工厂已接通账户、目标、问题、分类和条目依赖读取；缓存落盘及页面尚未接通。既有 source-plan oracle 仍含已退役微信旧编号回退与 `source_scope_label`，旧 native 基线亦在读取断言失败；按当前原导出器完整重新生成该单一 oracle，保留旧流水种子及全部断言，并增加禁止旧微信身份推断的明确断言。依据当前 statement-import 身份合同与 `16cddd8f` 退役决定，不恢复查询兼容或更改业务语义。新聚焦 source 方法与相关 source 回归双库通过；原完整 view 用例仍暴露独立旧字段缺口，未计通过。
- [ ] 6.3 先写失败测试：候选召回与关系配对结果与既有实现等价，且相同输入确定性
- [ ] 6.4 实现端侧导入阶段 1–7
  - 公共字段决定已有计算型入口，直接作用真实本地呈现计划；核对结果组装已将分组、保存选择、拆分恢复、图应用、失败归集和呈现单源共享，本地按同一快照／准备／独占预览配置，再应用公共字段决定并附操作／行结果。保留精确计算类型，必须显式提供已计算提案；新增 15 个原提案 case 的 native builder 完整对照和真实退款全流程、双库与跨端专项已通过。最终冻结编码已完成，金额转原固定十进制、时刻转 UTC 微秒，精确整数和原始 JSON 数字种类分别保留；完整发现、文件到核对串联、计划保存和页面尚未接通。
  - 普通现金、含现金端的转账及现金字段编辑已在本地日期转换为 UTC 后复用同一发生时间门，保留 invalid_datetime 反馈、空时间草稿和未编辑字段资格；纯投资转账继续原时间转换。Android host 失败回归已转绿；跨端编译、实际表单 Web 冒烟及界面验证仍未完成，不据此宣称全部前端验收通过。
  - 同一完整快照上的关系上下文读取已实现，按原确认闭包／决定组资格保留元数据并排除无关事实；原 SQL 完整模型／事实／token 的双库对照及各端回归通过，完整端侧预览仍待接线。
  - 关系上下文只读模型／依赖 token 已共享，backend 类型别名与薄委托保留原 SQL 读取、顺序与值范围；双库原准备／完整预览及跨端摘要验证通过。模型本身不承担事实读取，完整预览仍待接线。
  - 计算值预览摘要与虚拟标识桥已共享，保留原 Decimal／微秒时刻与来源摘要边界；backend 复用，端侧公共字段／关系选择改用计算值入口。原完整预览及双库／跨端验证通过；完整端侧预计事实／关系组生成仍待实现。
  - 候选查询规划及本地无 IO 入口已共享，后端复用原 SQL；原 14 个候选及 4 个预览准备 case 的双库回归、查询边界跨端测试均通过。实际快照候选读取、完整关系发现／预览仍待完成，不勾选本项。
  - 公共字段选择的目标索引／稳定编辑引用及补丁决定已共享，backend 保留原 BigInteger 排序与值／摘要薄桥，以既有原 golden、完整服务／HTTP 和本地 JSON 入口回归保护。端侧完整目标构建与核对页仍待接入。关系选择失效／generation 切片复用原关系选择完整语料，纯 applyChoices 核心保留原任意精度计数及旧选择不复活语义，不改图应用或上游 CashImportChoiceContext 的非负且小于 Long.MAX_VALUE 限制。
- [ ] 6.5 先写失败测试：写集构造完整、稳定输入可重派、决策按稳定引用复用、指纹一致静默重提交、不一致提示重核对
- [ ] 6.6 实现写集提交与冲突重派
  - 提交后缓存接线须将已确认收据版本作为刷新下限；先回归旧版本查询不能复用旧事实、过旧快照不能发布、失败保留原缓存及成功后真实浏览换版。不以收据版本直接赋本地水位；本片不清理 outbox，完整提交／核实及意图清理仍待接入。
- [ ] 6.7 实现离线 outbox（含退避与重试上限）与 pending overlay 展示

## 7. 端侧：派生投影与浏览切换

- [ ] 7.1 先写失败测试：投影归属、内部转账不计收支、退款冲抵、余额校准不显示、双后端事实等价
- [ ] 7.2 实现端侧投影/筛选/分页/月度汇总派生，并接入账本浏览
  - `buildLocalCashProjections` 已从完整快照组合真实逐行投影，含来源排序、草稿／问题／待核关系标记、只读资金对应、确认组件图与父流水关系转换；成功完整输出与失败码在双库对照。月度统计已共享，`summarizeLocalCashProjections` 消费完整有序选择并保留未知日期位置、统计资格及不受单笔限额的精确累计；native 时区薄桥复用。`buildLocalCashLedgerEntries` 已组合稳定条目及成员，公开字段与来源值分开，缺失／矛盾身份不发布。既有列表 DTO 已移入 contracts，shared 类型别名继续复用；`buildLocalCashProjectionViews` 从同一完整快照组合原全部列表字段，包含分类路径、来源、当前问题、关系摘要和普通／银证转账双端，版本明确使用 ledger_seq。显式浏览时区的月份适配已提供，平台只查询给定时刻的秒偏移，共享公历计算年月；`buildLocalCashFilterOptions` 已提供完整目录和基础可见集合的全局选项，按用户确认不随当前筛选／分页缩小。筛选日期文本已复用共享公历／ISO 周日期归一，后端继续原 ZoneId 起日转换；选定时区的本地日期范围已提供，按午夜缺失／重复／跳日处理；本地筛选执行与分页已具备，缓存 owner 与页面尚未调用，不能勾选整体任务。关键词按用户确认采用普通文字包含且忽略大小写，不解释 SQL 通配符；`cashLedgerKeywordMatches` 已由本地查询消费，谓词与完整查询分别验证。
  - `CashLedgerCursorScope` 已提供共享游标编解码；按工作区、精确账本版本、客户端共享／投影规则和完整规范筛选映射绑定查询。`CashLedgerCursorPosition.isAfter` 以真实排序时刻和稳定身份码点次序续页，7 个 common 回归覆盖微秒、超过 JS 安全整数的版本、规则失效、格式／UTF-8、范围隔离与完整遍历。该组件已接入本地查询，尚未接入页面，任务整体仍未完成。
  - `LocalCashLedgerQuery` 已把同一快照的一次条目／视图派生与规范筛选、游标／序号分页、完整范围总数／待处理数和月度汇总组合；全局选项独立于筛选。现金账户检查代表与所有现金成员组成项，不使用银证投资端展示账户。零金额默认隐藏，包括净额 0 的转账；明确显示零金额时保留转账／全额退款。未知金额／日期不填零或创建日期，等时公共标识按码点续页。`normalizeLocalCashLedgerQueryInput` 已复用同形共享 CashFiltersDto，规范完整筛选和日期边界；JDK 币种字母资格冻结为私有区间。原生 parser 与完整 page 已有独立对照；shared 的 LocalCashLedgerReader 将原始筛选、选定时区的实际起日与月份查询组合，缓存持有者与页面尚未调用。内部 LocalCashLedgerOwner 已组合固定范围的刷新与实际本地分页，关闭资格直接使用同步层同一状态，覆盖未缓存、无变化不下载、刷新保旧／新版本游标、并发关闭及旧登录迟到响应；真实加密驱动、应用会话与页面接线仍不在此切片完成。
- [ ] 7.3 移除对服务端投影读取面的依赖；账本版本变化时整包替换并保持筛选条件
- [ ] 7.4 规则版本变化时触发整体重派生

## 8. PDF／旧 XLS parse-only

- [x] 8.1 先写失败测试：只返回归一化行、不读账本、不写业务数据、密码不留存
  - 入口不存在的 HTTP 404 已先复现；38 个原始 PDF 样本保留完整成功文档／失败码对照。实际 SQL 观测拒绝现金／账户表读取及所有 DML，完整数据库前后相等；密码仅请求体，工作区／角色／会话失效在成功与密码错误后均拒绝返回文档。客户端真实迟到 401、文件预算、JSON 数字和异常／取消均有失败回归。
- [ ] 8.2 实现无状态 parse-only 端点并接入端侧 PDF 路径
  - 服务端 `POST /api/v1/cash-import/pdf/parse`、正式 OpenAPI、共享 `parseCashPdf` 和 `localNormalizedCashDocument` 已提供；复用原受限 PDF 子进程，不依赖暂存／R2。单文件 `parseLocalCashStatement` 已消费该路径，保留原字节／来源提示优先级及取消／错误传播；批次 owner、稳定输入保存与页面仍未接入，不能勾选整体任务。

- [x] 8.3 先写失败测试：旧 XLS 无状态原始文档接口，原银行全部文档与本地归一等价，双库只有鉴权查询、无账本读写，资源／权限／错误与取消受控
- [ ] 8.4 实现旧 XLS parse-only 与共享客户端调用；在完整文件调度中接入端侧归一，明确该格式需联网
  - 服务端 `/api/v1/cash-import/xls/parse`、正式原始文档 OpenAPI 和共享 `parseCashXls` 已提供；服务端复用受限子进程，只解码，端侧继续使用 `normalizeLocalCashStatement`。原 6 个 OLE 案例的完整文档／归一、只允许鉴权 SQL、完整数据库不变、双库权限／预算／错误、真实受限 worker 通道的故障注入／退出、客户端输入／取消／迟到 401 均已覆盖。XLS 端侧解码留待后续验证；单文件 `parseLocalCashStatement` 已消费此入口并接入实际本地归一；批次 owner、稳定输入保存及页面仍未接入，任务整体不勾选。


## 9. Web 持久化

- [ ] 9.1 先写失败测试：Web 端本地库读写与跨会话持久化
- [ ] 9.2 落地 OPFS + SQLDelight-wasm，并处理 Web 静态加密口径（见 design Open Questions）
  - 仓库外合成兼容探针已验证真实 SQLDelight JS／Wasm driver 与 Multiple Ciphers WASM／OPFS：完整 Chrome 重启后读取既存 TEXT 行、提交／回滚及主文件有限明文检查。webpack、完整 worker 协议、活动侧文件、产品密钥和应用接入未验证，不勾选生产持久化任务。

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
- [ ] 11.4 `openspec validate --all --strict`、`openspec doctor`、`git diff --check`；实际分发 Android 二进制前，核对并随发布提供精确 desugared-library 的对应源码与通知，不能仅用 upstream 链接代替分发准备
  - 后端全量 detektTest 的 10 项既有测试 finding 已清理：常量时钟显式声明 Instant，catch 使用原别名指向的完整异常类名且 13 个 classfile SHA 完全相同，必需 fixture 用 assertNotNull 断言。backend-test-static-full.log 的完整测试编译、全量 ktlint／detektMain／detektTest 已通过，无限定源文件、规则关闭或业务修改；这不替代本任务其他检查和全后端运行回归。
- [ ] 11.5 同步 `docs/feature-map.md`、`DOMAIN_GLOSSARY.md`、现金导入流程文档与本 change 相关的接口/命令示例
- [ ] 11.6 在 tasks.md 记录当前 `HEAD`、比较基线、实际命令、结果、执行时间与遗留风险；重跑受影响的验证


### 文件关系联合批次准备验证及收尾证据

比较基线／验证 HEAD：1c4ea221，2026-11-06（UTC）。internal prepareCashImportWriteSetRelationBatches 先核既有文件范围，再复用原 cashRelationCommitGroups：旧边仅 active confirmed 连通，全部新边及其明确选择组联合分区共同参与，包括 defer／ignore；以明确组列表首次批次出现和批次内组序返回。私有 typed 字符串键仅用于连通计算，隔离 New／Existing 同文本，不充当存储 ID 或执行顺序；不信客户端 decision_group_id 或关系数组排列。返回组保持原分区／公共补丁，不扩旧闭包成员、不写输入或账本。公共继承、三阶段效果、幂等组消费及实际服务仍待实现，3.2 不关闭。

cash-import-write-relation-batches-red.log 因缺入口先失败。green 7 方法通过；终态 jvm.log 沿 NODE_ENV=development、外部 lint worker init、--no-daemon／--max-workers=1／--no-configuration-cache／--no-build-cache、in-process 及 Xmx3072M 执行完整 domain jvmTest、ktlintCheck、detektMetadataCommonMain、detektJvmTest 及 backend compileTestKotlin，通过；JVM 161 类／588 方法，全 0 failure／error／skip。platforms.log 加 -Pkotlin.incremental=false 并关闭 JS／Wasm IC，jsNodeTest／wasmJsNodeTest／testAndroidHostTest 各选 '*CashWriteSet*Test*'、'*CashEntryAllocationEffectsTest*'、'*CashImportWriteSet*Test*'，均 11 类／90 方法，全 0；domain compileTestKotlinIosSimulatorArm64 及 compileKotlinIosArm64 通过，仅计编译。当前原样 XML 保存于 <缓存>/cash-import-write-relation-batches-xml/domain-jvm、domain-js、domain-wasm、domain-android。没有 SQL／DDL 或事务消费者，不新增双库运行声明。

独立 5a 和实际独立 5b 已对照原纯分组、DecisionRules 和 native saveGroups 复核，无本片 finding；覆盖非接受桥的批次首见、旧边三态／存活资格、选择组联合、伪 joint ID、关系数组反转、引用类型隔离及原输入不变。增量成本为既有全事实 scope 检查、原联合分组／排序及线性索引，无新二次全组扫描。Cross-platform Impact Check：三端共享内部准备器受影响，backend 编译通过；无 UI、文案、路由或交互变化，不新增浏览器／设备 QA。D3、任务、两级地图及 apply 指引同组维护；词表、README、配置／示例无新术语、公开路由或部署变化，无迁移，可独立回退。落实原分组规则，无新增通用产品政策。B 自动继承的顺序风险另在本节 3.2 和 D3 保留待核，不拿结构分批通过当完整效果或元数据独立性证明。

### 有序关系选择组传输与输入验证及收尾证据

比较基线／验证 HEAD：ec255f04，2026-11-06 04:52 UTC。relation_groups 独立表达全部本次新关系选择组的完整边分区及顺序；common_fields 是原始明确公共补丁，缺省或空对象继续待原自动继承处理，内部 category_id:null 沿原清空语义，对象本身不得 null。不新增 action、parents 或 suggestion 权威字段。文件范围门检查非空／完整／不重叠的 New 边分区、三种合法状态且全部 active、仅 confirmed 组可带非空公共补丁及当前分类引用；独立五公共字段浅归一保留 presence／清空与空对象，不递归解码未知嵌套。既有 scope／topology 正例只补必需组声明，未削弱旧断言。此片不计算联合批次、自动继承、完整三阶段效果或实际事务，不关闭 3.2。

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

独立 5a／实际独立 5b 无 finding；未来 outbox 必须持续传入相同下限，直到完整事实到达，不把后续刷新失败当成写入失败或新建提交。Cross-platform Impact Check：三端复用同一内部同步入口；本片没有页面／文案变化，不冒充浏览器页面 QA。`commit-snapshot-floor-js-browser.log` 与 `commit-snapshot-floor-wasm-browser.log` 分别在 Chrome Headless 执行 `:shared:jsBrowserTest`／`:shared:wasmJsBrowserTest --tests '*Ledger*'`，各 16 类／66 方法，0 fail／error／skip；JS 使用 `-Pkotlin.incremental.js.ir=false`，Wasm 使用 `-Pkotlin.incremental.wasm=false`，单 worker、无 configuration/build cache。XML 在 `<缓存>/commit-snapshot-floor-xml/{js-browser,wasm-browser}/`。iOS 测试源码及 Arm64 framework 源码已随 `cash-input-range-final-native.log` 完整编译成功；不声称 iOS 运行或设备 QA。无服务存储或写入变化，不重复双库矩阵。已核对 design、任务、总／现金功能地图，README、词表、接口形状与部署配置无需更新；既有结果确定性合同的内部实现不新增通用规则。完整写集、收据核实、意图清理及加密应用接线仍未完成，不勾选整体 6.6。本小步可独立回退，不需要数据库回滚。

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

收尾核对 proposal、delta、design、tasks、apply 指引、功能地图、依赖／分发声明及矢量生成器，保留当前页面仍用服务端旧流程的事实。用户本次排除公式，只限定本 change 的 XLSX 接受边界，不沉淀成通用财务或文件处理规则。没有新增领域术语、部署或外部资源配置。完整写集／重派、文件调度、加密库与密钥／owner／索引、浏览查询和页面、旧接口退役、最终 QA 与归档均未完成；Web 解锁策略仍待用户选择。
