# 任务

变更分级：A（跨模块架构重设计、公共契约变更、数据模型与持久化变更）。前置：`kotlin-backend-shared-core` 已完成 parity 并合入，Python 运行时已废弃。本变更在独立 feature 分支/worktree 中实施。

## 0. 七阶段与结论

- [x] 0.1 阶段 1 思考：阅读 `openspec/specs/` 相关主规格（import、cash-ledger、accounts、shared）、`openspec/project-context.md`、`DOMAIN_GLOSSARY.md` 与迁移变更记录，调用 `$openspec-explore` 固化取舍，结论回写 design.md
- [x] 0.2 阶段 2 计划：完成本 change 的 proposal/specs/design；如出现改变范围或语义的新决策，先回写 artifact 再实施
- [x] 0.3 阶段 3 任务拆分：核对每个 requirement 与本文件任务、失败测试、审查、验证与文档收尾的对应关系（核对中发现缺口：新增 4.5 删除服务端旧导入写路径）
- [ ] 0.4 阶段 4 构建：在独立分支按本文件小步实施，测试先失败再转绿
- [ ] 0.5 阶段 5 审查：产品/范围、工程、最终 diff 三类审查；DX/安全/设计按风险触发

本轮审查范围：文档证据、命名空间与完整性规则下沉（基线 `d6375627`）。独立第 5a 核对分组键、声明九字段、首次页指纹、冲突粘性、完整页集合与时间范围，以及不修改输入，无 finding。第 5b 由 `$codebase-degradation-review` 调度独立 `$contract-design-review`，沿来源组合、规划、匹配和文档仓储核对单一模型、字段相等性与实际持久化编码，无 finding。未将既有 `complete/evidence` 当作完整性依据，`currentDocumentCoverage` 只修改这两个字段。无 UI 变化，不触发 UI 专项。整个 change 的最终专项审查仍未完成。

- [ ] 0.6 阶段 6 测试与 QA：执行双后端契约矩阵、三端编译与单测、Web 真实浏览器冒烟，并运行 `openspec validate --all --strict` 与 `openspec doctor`
- [ ] 0.7 阶段 7 发布：记录交付证据、发布准备与回滚信息（未上线，无流量切换），并按用户实际决策完成规则沉淀反思

## 1. 共享规则下沉（先服务端复用，行为不变）

- [ ] 1.1 先写失败测试：共享层对查重、配对、投影、余额规则给出与既有服务端实现等价的确定性结果
- [ ] 1.2 将现金流水/组成项、来源身份、关系、entry、映射、分类等写集 DTO 收敛进 `business-contracts`
  - 已完成首片：`CashSourceCoverage` 数据形状与文档证明校验移入 contracts，backend typealias 复用同一类型；原字段、默认值及显式暂存编码保持。完整写集 DTO 仍未实现。
- [ ] 1.3 将查重与来源身份、关系配对管线、投影派生、余额规则下沉 `business-domain`
  - 已完成首片：`CashSourceScope` 迁入共享层，backend 使用 typealias；平台交易编号、来源账户范围、Python 空白与 Unicode 码点边界保持原规则。覆盖证明的包含性／可比较性已移入共享扩展函数，JVM 时间类型由 backend 薄适配；文档证据、命名空间和完整性合并已迁入 `CashSourceDocuments`，后端以 typealias 复用。来源出现身份、查重与关系／投影仍未下沉。
- [ ] 1.4 服务端改为复用共享实现，保持既有对外行为不变；跑通现有后端测试作为基线
- [ ] 1.5 为共享规则引入显式规则版本，并让规则版本随快照/派生结果可观察
  - 已完成：`business-domain` 新增 `CASH_RULES_VERSION`（`cash-rules-v1`）并有稳定性测试
  - 待办：把规则版本随快照与派生结果对外可观察
- [x] 1.6 前置（1.3 的硬阻塞）：为共享层提供多平台原语——SHA-256 摘要、码点计数与迭代、Python 浮点文本、`LedgerDecimal` 十进制表示；先写失败测试，再让 backend 复用同一实现
  - 已完成：多平台 SHA-256（含标准测试向量）、`pythonCodePoints`/`pythonCodePointCount`/`pythonStringOrder`、`pythonWhitespace` 已落 `business-domain`，失败测试转绿（`:business-domain:jvmTest` 4/4）
  - 已完成：backend 复用——`tokenDigest` 走共享 `sha256Hex`（保留 `strictUtf8`），`pythonStringOrder` 委托共享实现；`SourceDigestParityTest` 1/1、`CashSourceIdentityGoldenTest` 4/4 通过
  - 已完成：`sourceJson` 稳定 JSON 结构下沉共享（精确十进制与浮点文本**由调用方注入**，后端注入 `BigDecimal`/`pythonFloatText` 以保持既有 digest 口径）；等价性 oracle 共 19 个测试通过（`SourceDigestParityTest`、`CashSourceIdentityGoldenTest`、`CashSourceResolutionGoldenTest`、`CashSourceMatchGoldenTest`、`CashSourceScopeOriginalTest`、`CashSourceBatchGoldenTest`、`CashSourceRowsGoldenTest`）
  - 已完成：`pythonFloatText` 以 IEEE 754 位模式与精确中点区间生成跨平台最短文本，backend 委托共享实现；私有有界整数算术不改变 `LedgerDecimal`。固定 CPython 位模式向量、全部 2,046 个正规二进制指数及两侧相邻值（含负数）、2,000 个固定随机位模式与十进制计数边界，对照迁移前 JVM 实现。
  - 已完成：`parsePythonCashTimestamp` 使用共享 `kotlin.time.Instant` 与纯日历运算，保留 ISO 周日期消歧、显式偏移／naive 策略、微秒截断、Unicode 分隔符及范围错误。backend 仅适配 JVM 时间类型与既有错误；共享入口在替换前直接通过现有 20,053 条 Python 时间样本与旧实现对照。
  - 剩余来源实体下沉归任务 1.3：`SourceOccurrence` 与来源绑定键仍在 backend；原语就绪不代表来源查重或完整导入已共享。

## 2. 服务端：工作区版本与全量快照

- [ ] 2.1 先写失败测试：版本单调递增、提交失败不推进、双后端等价
- [ ] 2.2 为每个工作区维护单调递增账本版本，与规范事实写入同事务推进（PostgreSQL 工作区行 `FOR UPDATE`；SQLite `BEGIN IMMEDIATE`）
- [ ] 2.3 先写失败测试：版本查询只返回版本、隔离与权限
- [ ] 2.4 提供轻量版本查询接口
- [ ] 2.5 先写失败测试：快照在只读事务锁定版本 `V`、分页不跨版本、隔离与权限
- [ ] 2.6 提供全量事实快照接口，覆盖端侧缓存所需全部规范事实（含只读 `cash_investment_funding_relations`）
- [ ] 2.7 双后端契约矩阵：版本、快照事实、精确金额与时间值等价（SQLite + 真实 PostgreSQL `_test` 库，显式 `FT_TEST_POSTGRES_URL`）

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

## 5. 端侧：本地缓存与安全

- [ ] 5.1 先写失败测试：缓存事实范围、工作区隔离、版本替换的原子性
- [ ] 5.2 落地本地库 schema 与索引（账户、流水与组成项、来源绑定与证据、关系与候选、问题、entry 与成员、分类、映射、别名、funding 关系）
- [ ] 5.3 先写失败测试：加密落盘、密钥取自平台安全存储、登出/失效清理
- [ ] 5.4 实现缓存加密与登出/失效清理
- [ ] 5.5 先写失败测试：版本未变不重下；版本变化原子替换，不混入旧版本
- [ ] 5.6 实现刷新流程：版本查询 → 全量快照 → 原子替换并记录已同步版本

## 6. 端侧：导入阶段 1–7 与写集提交

- [ ] 6.1 先写失败测试：CSV/XLSX 解析 + 归一化/类型分类与既有结果等价
- [ ] 6.2 先写失败测试：对缓存账本查重、账户归属、操作计划的结果与既有服务端实现等价
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

验证时间：2026-11-04 13:53 UTC。验证时 `HEAD` 与比较基线均为 `d6375627`；范围为文档完整性下沉的工作树 diff。新增共享测试先确认规则与类型缺失导致失败，再迁移实现与 backend typealias。

在 `compose/` 执行：

```sh
NODE_ENV=development ./gradlew :business-domain:jvmTest :business-domain:jsNodeTest :business-domain:wasmJsNodeTest :business-domain:compileKotlinIosSimulatorArm64 :business-domain:compileAndroidMain :business-domain:ktlintCheck :business-domain:detektMetadataCommonMain :business-domain:detektJvmTest :backend:test --tests '*CashSourceIdentityGoldenTest' --tests '*CashSourceMatchGoldenTest' --tests '*CashSourceMatchingOriginalTest' --tests '*CashSourceBatchGoldenTest' --tests '*CashSourceRowsGoldenTest' --tests '*CashSourceScopeOriginalTest' --tests '*CashStatementDocumentGoldenTest' --tests '*BankStatementDocumentGoldenTest' --tests '*ParsedImportCodecTest' --tests '*ParsedImportStagingOriginalTest' --tests '*ParsedImportStoreTest' --console=plain
```

结果：`BUILD SUCCESSFUL`。domain 的 JVM 34、JS 32、Wasm 32 个方法通过，backend 指定 12 类共 141 个方法通过，无跳过。本片未修改 contracts，沿用已验证的数据结构；不声称本轮重跑 contracts。Android／iOS 共享层编译、ktlint、detekt 通过。浮点与时间原语仍由共享测试验证，本轮未重跑完整后端或原时间黄金命令，不将旧结果冒充本轮全量结果。

JS 验证须显式使用 `NODE_ENV=development`；已有 Mocha 等测试工具的依赖锁已补齐，原运行依赖版本不变。根目录执行 `openspec validate offline-first-ledger-import --strict` 与 `git diff --check`，均通过。

本片不改变数据库行为、HTTP 接口、UI 或用户文案，双库矩阵、浏览器和原生设备 QA 不适用于此片；整个 change 的相关任务仍未完成。README、功能地图、接口文档和词表无需修改：当前用户可用能力与协议不变；apply 技能补充共享覆盖证明与原语入口。七阶段任务保留，用户决策反思并入第 7 阶段；本轮没有新的用户产品取舍，无需新增通用规则。
