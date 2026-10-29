## 1. 思考

- [x] 1.1 显式执行 grill-me → grilling，用户确认全量验收和本地授权边界；确认旧实验停机，核实最新远端 main 起点并创建隔离 worktree。
- [x] 1.2 执行 OpenSpec explore，阅读主规格/上下文/active changes/词表，完成 86 操作、任务、格式、存储、安全和测试盘点；只读查询本机相关 memory，未找到可用条目。

## 2. 计划

- [x] 2.1 通过 OpenSpec propose 形成 proposal、两项 delta、design、inventory，明确公共模块、平台 adapter、完整权威校验、事务、兼容、过渡与回滚。
- [x] 2.2 产品/工程/安全独立预审并处理阻断 finding，记录最终实施边界。

## 3. 任务拆分

- [x] 3.1 依赖排序：首片 → 认证/账户/分类 → 现金/维护/投影 → 解析/来源/导入 → 关系 → 投资/同步/行情/后台 → 全量验证；每片先 oracle/失败测试，再实现与回归。
- [x] 3.2 验证 artifacts 一致及 OpenSpec strict，逐项关联路线矩阵、requirements、失败场景和测试证据。
- [x] 3.3 建立基线 Python 测试对应表和漏项检查，区分静态定义与参数化用例，记录规则/断言、Kotlin 目标、运行结果及确需保留的基础设施测试；先补查已迁移片段，不用黄金或 Python 回归冒充原生测试迁移。

## 4. 构建

- [ ] 4.0.1 jOOQ 基础：可重现的官方 schema 代码生成、仅后端依赖、SQLite 文本/PG NUMERIC 金额、UTC/JSON 绑定及安全异常映射；失败先行并通过双库事务/保存点合同。
- [ ] 4.0.2 jOOQ 垂直验证：迁移当前收支详情/定位与完整转账的查询写入调用链，保留当前权限、财务快照、金额表示和原子回滚；SQLite/PG18 功能及实际客户端回包验证。
- [ ] 4.0.3 jOOQ 全量替换：迁移其余已实现仓库的业务 SQL，复核生成类型边界，运行全部已实现功能回归、安全检查、相称构建和独立审查；性能按用户要求排除。
- [x] 4.1 首片失败先行：匿名 Python 黄金、公共规则/DTO 差分、Ktor 旧会话/分类读取的双库 HTTP 与权限/过期/跨工作区/错误测试。
- [x] 4.2 新增无 UI 的 KMP contracts/domain，实际接入 Compose 的认证 DTO、分类上级规则和精确分摊，移除对应双实现；实现首片 Ktor/JDBC。
- [ ] 4.3 完整认证：注册/登录/登出、工作区创建/选择/重命名/删除、邀请预览/接受、成员/最后管理员，保持旧 Argon2/会话及全部错误合同；注册/登录按用户明确授权仅接受字符串凭据，补非字符串 400 与无写入反例。
- [x] 4.3.1 注册、登录、登出及旧 Argon2 双向兼容；20 组非字符串字段无写入（含旧强转可接受的长数字及含邮箱文本的数组/对象）、用户/会话结果、重复注册竞争和失败回滚。
- [x] 4.3.2 工作区、邀请与成员的 11 个 HTTP 接口实现并通过 53 组匿名双库响应及状态对照；首个空账本初始化在同一事务发布。
- [x] 4.3.3 并发自降只保留一名管理员、同邀请一次消费、到期边界、已有成员不降权、初始化与删除晚期失败回滚、并发删除备选指针保护。
- [ ] 4.3.4 完整财务数据删除、锁等待期间换区/撤权/登出及原认证性能矩阵；这些未验收项不因 4.3.1–4.3.3 通过而关闭。
- [ ] 4.4 完整账户命令/查询：资格/币种/余额/别名、修订、引用、snapshot 与永久幂等收据。
- [x] 4.4.1 稳定 ID 创建/查询/修改/删除及现金/投资列表，共享实际读写 DTO；56 组 HTTP/摘要/全部持久状态、原生 editor/CAS/原键/并发/失败回滚、坏快照/引用/撤权等待和实际 Compose 解码双库验收。
- [ ] 4.4.2 旧写入适配/所有币种扩展的修订、底层 CAS、历史迁移/序列与 OpenAPI；账户余额/估值查询、别名及相关剩余接口随领域切片迁移。不能以 4.4.1 代替完整 4.4。
- [ ] 4.5 完整分类写入：名称规范化、目录修订、锁、深度/循环/子树、移动/重排、删除影响及 entry/事实/投影、单项和批量分类。
- [x] 4.5.1 共享目录 wire 类型并修正 Compose 名称数组/对象路径差异，以实际后端响应验证客户端解码和领域映射；先失败测试，保留原 API 字段。
- [x] 4.5.2 将分类上级/循环/深度规则接入真实服务端命令，与 Compose 使用同一入口，迁移相应 Python 参数和断言；财务引用、投影及批量分类另验，不能局部关闭 4.5。
- [x] 4.5.3 分类删除检查和原子删除：完整迁移 entry/独立恢复值/含已删除事实的人工空分类保护、候选依赖失效和全部数据集清理；双库原生回归、原断言迁移、性能与实际客户端解码验证后计入接口。
- [x] 4.5.4 单项/批量分类 PUT：当前数据集/版本、维护可见性、完整 entry/成员、同值人工保护、单成员恢复值、投影/来源版本原子更新；原事实保持独立，迁移原参数/断言并验证双库与实际客户端。
- [ ] 4.6 公共完整精确金额/币种/时间/类型/分摊规则，按 Python 边界及指定舍入黄金，逐步替换前端纯规则重复实现。
- [x] 4.6.1 金额合法范围与已知现金部分一致性提供类型化和保留 JSON 类型的公共入口；固定/随机 Python 黄金在 JVM、JS、Wasm 通过，补齐 Unicode、零与极值指数、组成项引用和 ordinal 反例。
- [ ] 4.7 现金新增/草稿/手工转账/删除/完整组/公共编辑、当前查询/证据、余额及原子投影。
- [ ] 4.7.1 迁移现金字段/转正式纯规则及对应原生单测；动态语言边界剩余 2 项待适配。
- [x] 4.7.1.1 现金字段/转正式规则 26 个原定义、59 个展开用例已逐断言迁入独立 JVM 测试并经复核；调用已有公共现金部分校验。
- [ ] 4.7.1.2 动态字符串子类属性/键的 2 项原断言按语言类型边界适配并独立复核，不以黄金替代。
- [ ] 4.7.2 将规则接入实际现金写入、当前账户/修订/来源及完整组校验，双库验证余额/entry/投影/收据原子发布后才计接口进度。
- [x] 4.7.2.1 迁移余额快照仓库和现金组成项增量：当前账户名称/稳定 ID 映射、精度 80 加减及 NUMERIC 范围、草稿/删除/导入转账排除、人工转账余额口径、版本与 caller-owned 回滚。逐参数核对原仓库、ledger 与 statement 直接别名入口；共享函数参数可独立验收，不代表实际导入/记账入口已接线。
- [x] 4.7.2.2 迁移手工新建严格请求与应用字段边界：65,536 字节、真实编码、重复键/非有限数/深度、严格字段类型及组件 64 上限；缺省和显式 note/category 区分。复用现有 JSON 读取器，不改变账户请求边界。
- [x] 4.7.2.3 实现实际手工流水和现金部分持久化、来源固定字段/显式人工保护、快照及独立增量投影同事务；字段/金额/币种/账户、时间及结果完整响应按 Python 对照，晚期故障全回滚。
- [x] 4.7.2.4 接入 POST cash-records 及现有 Compose 创建消费者，完成角色/锁后权限、真实 HTTP/数据库与实际客户端解码；OpenAPI 原组合断言未覆盖仍 pending。验收后再增接口数。
- [x] 4.7.2.5 迁移转账双侧事件和余额纯规则：现金/贷款/借款、证券/加密头寸、同币种警告、跨币种到账金额及精度；先失败测试，不计 HTTP 完成。
- [x] 4.7.2.6 接入完整转账事务，保存双侧现金/投资事件及现金已确认关系；双库 92 组全列对照、3 原定义/9 参数、当前权限、晚期全回滚及现有 Compose 消费通过。
- [x] 4.7.2.7 接入旧现金新增和余额校准；保留账户/来源/精确金额/快照/响应及错误顺序，响应编码失败整笔回滚。双库各 96 组 HTTP/13 表全列、5 原定义/9 参数、实际锁等待授权、完整 Kotlin 功能回归及真实 Netty 通过。
- [ ] 4.7.3 迁移写入依赖的完整投影纯构建：事实/关系类型、镜像归并、退款分摊、转账、草稿、关注状态与确定顺序，先迁原单测；26 原定义已通过并复核，动态子类/性能仍待验；SQL 发布和当前事实完整性另验。
- [x] 4.7.4 迁移完整关系角色/闭包/原子分组与最终图纯规则及原单测；当前角色证据、完整成员、额度和端点占用必须先验证，不靠投影输出推断输入有效。
- [x] 4.7.5 迁移投影状态仓库和管理员状态 GET；保留无状态缺省、完整字段、当前工作区/管理员权限与失败合同，并将现有收据的 ready 状态读取接入同仓库。只读状态不代表重建或数据集发布完成。
- [x] 4.7.6 迁移账本选项 GET，复用现有一级/子类型合法组合；将 Compose 实际消费的选项 DTO 提到公共协议，保留完整原标签/顺序/缺省及普通可读角色，实际回包双端解码和受影响编译后验收。
- [ ] 4.7.7 迁移稳定收支对象仓库与投影发布所需行适配：公共人工值、独立恢复值、合并冲突、拆分继承、旧根优先与修订；先失败回归，再接入真实重建/维护事务。原 ORM identity/autoflush 与历史迁移断言单独适配，未验收不能计原测试完成。
- [x] 4.7.7.1 内部仓库 12 项原生方法及四个原 lookup 定义的全部 12 参数通过双库；关闭脏值 expire 和多行 JOIN 回退问题。事务内单一上下文、提交前 flush 及批量投影成本随真实接入验收，不关闭 4.7.7。
- [ ] 4.7.8 迁移完整投影源读取/图校验/摘要、暂存及发布围栏、增量连通闭包和失败诊断；真实双库回滚/竞争/性能及管理员 rebuild HTTP 后验收，原事实与永久人工值不因重建丢失。
- [x] 4.7.8.1 SQL 源值的 Decimal/UTC 微秒/canonical 编解码通过 4 项原生测试和 1,548 个 Python 向量；共用时间绑定器先截断微秒，再运行当前完整双库和实际客户端。不单独增加原定义或接口数，源读取/发布见后续子任务，不视为通用 canonical 模块完成。
- [x] 4.7.8.2 投影源读取、确认闭包、局部关系上下文及无写入的联合校验；21 个原定义/56 个展开用例完成原生迁移及独立复核，关闭资金过滤、500 批边界、SQLite 整数截断和动态证据兼容问题；当前完整双库及实际客户端通过。不以内部仓库完成计 HTTP 进度，relation-origin writer 仍 pending。
- [x] 4.7.8.3 绑定真实 JDBC 事务的来源证明、暂存数据集替换及原子发布；核实摘要、事实/成员数量、同仓库/同事务、旧活动集保留、失败诊断和完整重建。提交、回滚及连接关闭后旧上下文失效，savepoint 不改变外层事务身份。
- [x] 4.7.8.3.1 受管 JDBC 生命周期与只读来源证明通过双库 14 项专项及完整回归；包括 SQL/批次/归属链、SQLite 物理状态、异常隐式回滚和元数据接口。发布消费与数量围栏见 4.7.8.3.2，不单独增加 HTTP。
- [x] 4.7.8.3.2 暂存替换与发布消费来源证明：保留完整列映射、成员角色/序号及关系，900 写入/回查批次、901 实际边界、缺失父映射拒绝、旧人工值及活动集原子保留；原仓库/事务/数量三反例迁移后再计原定义。
- [x] 4.7.8.3.3 全量重建应用服务和新工作区初始化复用同一发布管线；失败停止并回滚财务写入，非存储错误另事务记录受活动指针/来源修订保护的安全诊断，诊断失败不替换原错误；原测试断言及双库回归完成后计迁移，管理员 HTTP 独立验收见 4.7.8.4。
- [x] 4.7.8.4 管理员 rebuild HTTP 接入实际应用服务；权限在工作区锁后及返回前重验，拒绝期间不写构建诊断。保留 Python 领域 ValueError 的 409 与运行/存储错误的 503（相同 code 也不丢失异常来源）；真实 HTTP 对照、双库回滚/PG 锁等待及原断言复核后计接口。
- [x] 4.7.8.5 增量仓库和应用服务维护受影响确认/旧成员完整闭包；保留独立/同组投影及成员身份，差量新增/移除、原计数/版本、草稿关注、人工值和 caller-owned 原子回滚；状态引用绑定同上下文/事务。先失败回归，再接现金写入，不能以全量重建替代增量或放宽性能门槛。
- [x] 4.7.8.6 分类独立移动命令在同事务收集子树下未删除来源与稳定收支根，接入增量维护；原 update 的名称/父级编辑保持既有 Python 调用边界，不额外扩展旧缺陷修复。验证人工分类、来源分类、无使用/未 ready 分支和晚期失败整事务回滚。
- [x] 4.7.8.7 逐断言迁移持久化图读取及增量集成：真实组成项等额退款分配、部分菱形、单种子拆旧组、新邻居旧组闭包、组成项预算拒绝、80 节点交替闭包参数预算和原类型不被重分类；双库与独立复核后再计源定义。
- [ ] 4.7.9 迁移当前收支详情及按流水定位的两个 GET：同一数据库快照中的活动数据集、版本、成员、金额及安全待处理事项；保留隐藏/可维护、已删除/跨工作区/缺失成员错误。真实双库 HTTP、重建交错、当前权限重验及现有 Compose 成员消费验证，原组合测试未覆盖部分保持 pending。
- [ ] 4.8 维护计划/上下文/效果/确认/收据、CAS/TTL、当前事实证明、冲突及未知结果原键核实。
- [x] 4.8.1 先迁永久收据按当前操作者/工作区读取、原键查询和行/关系结果分页的 5 个 GET；共享现有 Compose 收据协议，验证历史键严格解码、分页、脱敏、权限、当前投影引用及真实客户端解码。resolve/提交/暂存写入随后验收。
- [x] 4.8.2 迁移永久收据仓库 create/replay/for_context 及写入前摘要/结果白名单，保留原键/单上下文永久唯一、冲突携原收据、业务写入同事务回滚；原生迁移原参数/断言并接入现有账户命令，完整双库回归后验收。历史 Alembic 升降级和 retry/resolve 不因仓库完成而关闭。
- [ ] 4.9 服务端 CSV/TSV、XLSX/XLS/PDF 逐格式解码与归一、文件预算/密码/完整声明/匿名样本及原测试迁移；保持现有上传流程，公共层先共享行模型和协议，不新增 statement-core 或本地解析入口。
- [ ] 4.10 来源身份/差异/完整证据/去重/映射、InMemory/R2 暂存与清扫生命周期。
- [ ] 4.11 完整导入准备/选择/规划/预览分页详情/processing details/原子提交/收据，与当前 frontend 合同对照。
- [ ] 4.12 镜像/退款/转账纯算法及权威候选/决定/完整关系图、entry/投影更新、现金—投资资金调拨。
- [ ] 4.13 投资事件、重放/持仓/成本/费用/CHECKIN、周期 PnL/回报、事件查询及证据，DFZQ/IBKR/Schwab/uSmart 导入。
- [ ] 4.14 行情/FX/历史估值、CCXT/Polymarket 连接器、凭据/增量 cursor/同步，刷新 worker/SSE/退出清理。
- [ ] 4.15 逐项核对 86 条 route matrix、停用入口及装配、配置/schema/数据库失败关闭，公共 contracts 消费与旧客户端兼容；不代理 Python 业务实现冒充完成。


## 5. 审查

- [ ] 5.1 独立产品/范围审查、工程/DX/安全复核，覆盖所有共享边界、数值/时间/JSON 合同、SQL/凭据、授权重读、完整组、失败/幂等/回滚；阻断 finding 修复重审。
- [ ] 5.2 最终 diff 对照 delta/当前实现/匿名黄金和全部测试，审查遗漏/范围外变更与未迁移项。

## 6. 测试与 QA

- [ ] 6.1 每片执行受影响 Python oracle、JVM 公共与 Ktor HTTP、真实隔离 SQLite/PostgreSQL 同服务合同；记录失败先行与转绿。
- [ ] 6.2 公共模块 JVM、JS/Wasm 可运行测试及 Android/iOS 相称编译/单测；Compose 受影响共享/协议测试及 Web 构建。
- [ ] 6.3 完整双库 Python 功能回归与 Kotlin 迁移合同，安全、连接器错误/重试/回滚及 worker 生命周期/SSE；按用户明确要求，本轮不运行性能测试，功能迁移与功能验证通过即可完成本轮。原性能/资源预算和未通过证据保留，不宣称已验证。
- [ ] 6.4 OpenSpec all strict/doctor、构建/类型/依赖与 Gson 禁用检查、文档链接/锚点和 git diff --check。
- [ ] 6.5 全量 Python 测试对应表无未处理规则、参数和断言；目标测试独立运行通过，存储双库与共享跨平台证据完整；保留项理由与 Kotlin 兼容验证经独立复核。接口迁移和实际双端复用另行计数，不混为测试完成率。

Cross-platform Impact Check：Web、Android、iOS、JS/Wasm 的公共 DTO 和纯规则消费者受影响；后端新增 JVM 消费者。首片不改变页面、文案或产品交互，不适用 UI 原型/Hallmark/演示循环；用户明确禁止自动打开浏览器，不运行浏览器 UI 验证，不宣称其通过。若后续出现可观察 UI 变化，先 Flow-Back 确定验收与用户限制再实施。

## 7. 发布准备

- [ ] 7.1 核对 README/docs/references/接口/配置/schema/运行入口、功能地图和词表；说明当前可用/未迁移能力及格式过渡边界，记录回滚/观察项。
- [ ] 7.2 同步 delta 后验证并归档（仅真正完成时）；当前按用户要求继续本地迁移，未完成验收不归档。暂停时的进度已保存至 `origin/codex/kotlin-backend-shared-core`；未授权 PR、合并、部署、切流、真实数据迁移或旧后端删除。

## 8. 反思

- [ ] 8.1 按里程碑将可复用的兼容/数值/安全发现原位沉淀至项目 OpenSpec skills；重复失败后回到事实与根因，不改测试门槛逃避。

## 当前证据

2026-10-29 UTC；HEAD 与本轮比较基线均为 `b5dbef2716c11b367432c01048d6c2c2f1977d2e`，迁移固定基线仍为 `0c038a75e34264bd6d4aa5cce2070727df9d9ff7`。当前工作区 `/workspaces/finance-tracker`，分支 `codex/kotlin-backend-shared-core`。本片完成 4.7.2.7，任务为 42/77，HTTP 为 40/86（46.5%），还有 46 个 HTTP 操作待迁移。现有 `.devcontainer/devcontainer-lock.json` 修改不属于本片，保留用户状态。没有提交、推送、PR、部署、切流或真实数据操作。

已显式执行 grill-me → grilling、explore 和 apply，复用用户确认的完整迁移范围、无 UI 重设计及本地授权。用户后续明确要求本轮不运行性能测试，以功能迁移和功能测试通过为完成标准；通过 openspec-update-change 同步 proposal/design/tasks。性能阈值不变，此前已运行的分类删除性能检查未通过，原日志 `legacy-all-sqlite.log`（973 毫秒）与 `legacy-review-sqlite.log`（561 毫秒）保留；本轮后续功能回归明确排除 `CategoryDeletionPerformanceTest`、`CashClassificationPerformanceTest`，不以忽略失败宣称性能通过。

当前旧现金新增/余额校准保持原账户、来源、日期、零金额事实、分币种余额与投影事务。快照结构错误保持原内部 500，现金余额非字典及金额拒绝保持 400；校准先检查余额结构，再解析目标金额。投资头寸先计算 shares，再读取 total_cost。组成项币种保留原始代理字符来源，不能把 CESU 字节拼成合法币种。响应编码回归覆盖 editor、已有财务记录、无投影状态及 ready 状态，并逐参数验证失败回滚和后续成功。

本机工具、匿名夹具及证据根为 `/home/vscode/.cache/finance-kotlin-backend-20261029`（下文文件名均相对于其中 `evidence/`）。OpenSpec `1.15.0`、Node `v24.22.2`、Python `3.11.18`、pytest `9.2.0`、Gradle `9.5.1`、JDK `21`；Android SDK 位于同根 `tooling/android-sdk`，保留工程 `compileSdk=37`。原 macOS 证据未复制到本机，当前通过结论以此处实际执行结果为准；历史失败先行与审查处置继续由 Git 及后文原审查结论追溯。

SQLite 使用同根 `oracle-sqlite`、`legacy-sqlite-v2` 和 `matrix-sqlite/<能力>`。PostgreSQL `16.15` 由执行者显式准备于 `127.0.0.1:5432`，用户 `postgres`，库名全部以 `_test` 结尾：基础库 `ft_kotlin_resume_base_test`、旧现金目标/参考库 `ft_kotlin_resume_legacy_v2_test` / `ft_kotlin_resume_legacy_oracle_v2_test`，其余 `ft_kr_<能力>_test` / `ft_kr_<能力>_oracle_test`。完整显式变量分别保存在同根 `matrix-sqlite-env.json`、`matrix-postgres-env.json`；各运行结果 JSON 同时保存实际变量、命令、HEAD、开始时间和耗时。测试不探测或启动数据库，生成器拒绝覆盖已有 schema；所有夹具为任务匿名数据。

Gradle 从 `compose/` 运行，公共参数为 `--daemon --no-configuration-cache --project-cache-dir /home/vscode/.cache/finance-kotlin-backend-20261029/gradle-project -Dorg.gradle.jvmargs=-Xmx2048m -Pkotlin.compiler.execution.strategy=in-process`；后端完整功能回归使用 `--max-workers=2`，恢复后的客户端构建使用 `--max-workers=1`。完整功能回归另带 `--init-script /home/vscode/.cache/finance-kotlin-backend-20261029/functional-tests.gradle`，其中仅排除用户明确暂缓的两个性能类。`JAVA_HOME`、`GRADLE_USER_HOME` 和 `FT_KOTLIN_CLIENT_CONTRACT_DIR` 都指向同根任务目录。不同数据库的后端与实际客户端测试均实际执行，不复用另一数据库的缓存结果。

| 验证范围 | 当前命令、结果与证据 |
|---|---|
| 失败先行 | `resume-boundaries-sqlite-red.log` 实际复现快照根形状错误返回 400 和组成项 raw CESU 币种绕过；`legacy-snapshot-order-red.log` 复现坏 cash 桶与 `1e21` 同时出现时先返回金额错误。对应最小修复后 `resume-boundaries-sqlite-green.log` 30 项、`legacy-review-functional-green.json` 3 项通过，并纳入最终双库完整回归。新增源登记门禁由 `source-additions-red.log` 先复现后转绿；响应编码 Python/Kotlin 原失败回归已保存在进度提交，本轮加强 editor/已有数据/无状态前置 |
| 匿名 HTTP 与持久状态 | `export_legacy_cashflow_contracts.py --database-url <新目标测试库> --oracle-database-url <新参考测试库> --output <仓库外目录>`：每库 96 个旧现金 HTTP 场景，完整响应及 13 张财务表全部列一致，失败无业务写入；`legacy-<sqlite/postgres>-v2-prepare.log`。其他 13 类生成器的双库准备共 26 次均成功，精确参数见 `prepare-matrix.json` |
| 完整 Kotlin 后端功能 | SQLite：`:backend:test :backend:installDist :business-domain:jvmTest :business-domain:jsNodeTest :business-domain:wasmJsNodeTest :business-contracts:jvmTest :business-contracts:jsNodeTest :business-contracts:wasmJsNodeTest`；PG：`:backend:test :backend:installDist`。两库各 395 项，均 0 failure/error/skip；包括已迁移全部服务、96 组旧现金对照、晚期回滚和真实 PG 锁队列中的撤权/换区/登出。精确命令及 XML 为 `legacy-functional-all-<sqlite/postgres>.json` 和同名目录/log；分别 325.16 / 251.02 秒 |
| 公共跨平台规则 | 上述 SQLite 命令中，business-domain JVM/JS/Wasm 各 10 项，business-contracts 各 3 项，共 39 项，0 failure/error/skip；覆盖有限十进制表示提取及原消费入口，见 `legacy-functional-all-sqlite.json` |
| Compose 实际响应与编译 | `:shared:testAndroidHostTest --tests '*Kotlin*ResponseIntegrationTest' :shared:compileAndroidMain :androidApp:compileDebugKotlin :shared:compileKotlinJs :shared:compileKotlinWasmJs :webApp:compileKotlinJs :webApp:compileKotlinWasmJs`，SQLite 实际回包 10 项通过，76 个构建 task 成功，274.79 秒；`legacy-client-platforms-resumed.json/log`。PG 同消费者使用其实际回包执行 `:shared:testAndroidHostTest --tests '*Kotlin*ResponseIntegrationTest'`，10 项、0 failure/error/skip、19.87 秒，见 `legacy-client-postgres-final.json/log` |
| Python 本片完整功能参考 | 显式 `FT_REQUIRE_TEST_POSTGRES=1 FT_TEST_POSTGRES_URL=postgresql+psycopg://postgres@127.0.0.1:5432/ft_kotlin_resume_reference_test`，清除继承的 `FT_DATABASE_URL`。Python `-m pytest -q -p no:cacheprovider -m 'not performance'` 执行响应编码、CLI 替代 API、多币种账户、应用投影、精确小数、手工创建边界、转账 HTTP 及 PostgreSQL adapter 八个完整文件：97 passed、0 skip、57 项既有弃用警告，pytest 277.39 秒；完整参数/环境/起止与 XML 为 `legacy-python-functional.json/xml/log` |
| 错误顺序 Python 对照 | 新匿名 SQLite/PG 上分别实际执行 11 组坏快照与范围外金额组合，原状态/错误与 13 表无变化均通过；`legacy-snapshot-order-python-<sqlite/postgres>.log`，脚本为缓存根 `verify_python_snapshot_order.py`，PG 专库 `ft_kr_snapshot_order_test` |
| 真实 Netty | 当前 installDist 在 `127.0.0.1:18350/18351` 各完成 11 个 HTTP 操作，真实注册/建区/账户、18 位精确新增、另币种新增、数值输入及坏响应回滚、校准保留其他币种、金额拒绝和无令牌拒绝，最后删除本测试工作区。每库 13 表快照及 SQL 金额断言通过；`legacy-live-<sqlite/postgres>.json`，脚本为缓存根 `verify_live_legacy.py`，SQLite 匿名文件与 `ft_kr_live_legacy_test` 专库。服务器均已退出，没有打开浏览器 |
| 黄金内容 | 14 个生成器 `--check` 全部通过，包含共享金额、Unicode/Python 文本、时间、投影、关系、收据、选项、JSON 字节、SQL 摘要及手工/转账规则；命令和结果为 `legacy-generator-checks.json/log` |
| 迁移清单门禁 | `check_kotlin_test_migration.py --collection <完整 collection.txt> --report <legacy-migration-inventory.json>` 无错误；门禁自身 `python -m unittest scripts/kotlin_migration_checks.py` 11 项通过，见 `migration-gate-functional.log`。完整 `--require-complete` 实际返回 1，映射错误为空、2,381 个定义 pending，见 `legacy-migration-complete.log`；不能作为全量迁移通过结论 |
| OpenSpec | `openspec validate kotlin-backend-shared-core --strict --json` 通过；`openspec doctor --json` 健康。`openspec validate --all --strict --json` 为 26 passed / 12 failed，52 项缺失原场景错误及 25 项长正文警告分布于其他 6 个 change 与 6 个主规格。将 HEAD 的 `openspec/` 只读导出至仓库外，使用同一 CLI 重跑后逐条错误、路径和级别完全一致；没有本片新增问题。完整结果/基线对照见 `legacy-openspec-verification.json` 和 `legacy-openspec-{all,baseline,scoped,doctor}.json`；全量 6.4 仍未完成，须由相关规格收尾后原命令通过 |
| 静态与依赖 | 8 个已改 Markdown 文件的 173 个本地链接/锚点、3 个 Python 文件语法、73 项任务及 86 条接口计数、公共模块/后端 Gson 搜索和 `git diff --check` 通过，见 `legacy-static-checks.json`。`:backend:dependencyInsight --dependency gson --configuration runtimeClasspath` 未发现 Gson 依赖，见 `legacy-dependency-check.json/log` |

迁移测试固定基线包含 2,612 个原定义；授权新增响应编码回归后，当前共 2,613 个定义、5,844 个完整 pytest 用例。对应表登记 255 个定义，其中 232 migrated、23 pending；未登记的定义仍默认 pending，总计 2,381 pending。232 个定义对应 577 个源展开参数，不能与 JUnit 方法数或黄金样本数混用。本片新增 5 个定义/9 个参数的 24 条原 assert，经独立逐条复核后计迁移。pytest 9.2 对超过 100 字符的自动参数 ID 使用 `query6/query7/key1`，两个原收据映射按当前完整 collection 同步；原值、源/fixture 指纹和固定基线不变。新增源必须通过 `source_additions` 的当前指纹和理由检查，仍遵循全部参数/断言/目标门禁。

Cross-platform Impact Check：本片没有 UI、导航、文案或用户交互变化；旧现金两个接口无现有 Web/Native 消费者，不制造共享 DTO 或入口。已有手工创建、转账的共享余额/编码代码受影响，其 Ktor 实际回包由正式 Compose 消费者验证；有限小数表示提取由公共三平台测试及受影响 Android/Web 编译覆盖。Linux 无法执行 iOS 编译/测试，未宣称通过；在 macOS/JDK 21/Xcode 上执行 `:business-domain:iosSimulatorArm64Test :business-contracts:iosSimulatorArm64Test :shared:compileKotlinIosSimulatorArm64` 后才可关闭整体跨平台任务。本片后端功能验收不代表整体 6.2 已完成。按用户限制不打开浏览器，不执行或声称完成原生设备 QA。

运行环境重启打断过一轮客户端构建和全仓 Python 功能回归：`legacy-client-platforms.log` 的 Gradle daemon 消失，后续恢复命令已完成；`python-functional.log` 仅收集 5,775 个非性能用例并执行部分，没有结束报告，不计通过。当前本片验收使用完整 Kotlin 双库功能回归及上表八文件 Python 参考；全仓 Python、其余未迁移功能和历史迁移矩阵仍由总任务 6.3/6.5 跟踪。原性能专项本轮按用户明确要求排除。

文档核对范围为根 README、backend README、功能地图、routes/inventory、proposal/design/tasks、主规格及 delta、词表和项目 apply skill。根 README 的预览导航与现有主规格无需修改；余额校准、收支记录、关联流水等词表含义保持。backend README 与功能地图更新实际 40 个接口和旧现金限制，页面数仍为 10，Web/Native 既有 TODO 保留；新增源登记、夹具前置与快照错误顺序已沉淀到 apply skill。全量迁移尚未完成，保留原 Python 和 schema，未归档或切换生产入口；回滚本片只需撤回本机 Kotlin 改动，生产数据不受影响。

## 当前审查

旧现金新增/余额校准本片由独立上下文完成范围、工程、DX/安全及原断言复核；生产错误类别/顺序、真实 editor、当前权限、事务回滚、完整 SQL 对照和新增源门禁均已检查。审查者只读，测试由实施者运行。已关闭 finding：

| 严重级别与 finding | 处置与证据 |
|---|---|
| P2 响应编码回归缺少已有财务记录及无投影状态前置 | 同时覆盖 no-ready/ready、实际 editor、先保存真实记录；每个代理参数分别核对原 9 条断言及后续成功。独立重审确认，当前两库完整功能回归通过 |
| P2 校准提前校验金额改变坏快照优先错误 | 仓库先检查币种字典再解析金额，服务不预先转换；`legacy-snapshot-order-red.log` 先失败，双库 Python 11 向量及 Kotlin 完整回归转绿；写回 design 和 apply skill |
| P2 组成项 raw CESU 币种及快照异常类别 | 校验原始代理来源；内部形状错误与显式金额 ValueError 分开，投资头寸保留 shares/total_cost 次序；`resume-boundaries-sqlite-red.log` 先失败，最终双库功能回归通过 |

源新增登记、两条 pytest 长 ID 映射及其余旧映射语义经复核；新 5 定义/9 参数原断言完整。范围、工程、DX/安全、原断言和最终 diff 无剩余阻断；审查者独立读取双库 XML、真实 HTTP 与 Python/客户端结果，未代跑测试。性能按用户明确调整不作为本轮阻断，原失败证据保留。OpenSpec 全量既有 12 项失败保留为整体遗留，不计本片通过。

管理员重建独立复核关闭错误字面 P2：Python 原路由使用 StorageError 的完整固定说明，保留其标签和 file SQLite/postgresql 摘要，不拼接 URL 或驱动正文。领域/运行错误的类型按原来源保留，不按相同 code 推断。权限锁顺序、锁后及返回前重验、拒绝不写诊断、真实晚期回滚、匿名夹具恢复和 HTTP 黄金挂载/状态自检无剩余阻断。审查者未运行构建；最新完整双库和真实 HTTP 由实施者验证。包含 sync 的原组合定义不计迁移。

数据集/重建服务独立只读复核无剩余生产阻断。原数据集 7 定义/9 参数逐断言复核，完整映射、受限批次、proof 失效与真实晚期回滚证据均一致；全量服务另 10 定义/12 参数复核后计迁移。补齐 P2 原断言遗漏：完整来源实际读取恰一次、诊断失败保留原 `projection.invalid_relation`；固定时钟的证据局限改为三轮微秒推进。SQLAlchemy 编译缓存会保留默认值 callable，oracle 时钟通过共享可变值读取，避免冻结在首轮；三轮双库全列对照均通过。审查者仅只读，不代称执行构建；完整回归结果由实施者记录。管理员 HTTP 完成本节独立验收；公开现金写入及原性能定义未计完成。

事务生命周期独立只读复核关闭 P2：批次/ABORT/多语句边界、原生连接归属链、无事务窗口、SQLite SQL 结束与 JDBC 自动续开差异、非嵌套注释及引擎隐式回滚；元数据按声明接口封装关闭驱动多接口回归，PG 标识符中的 `$` 保留完整 token。14 项原生回归和当前完整双库结果均通过；按当前 SQLite/PG 应用 SQL 及内部错误停止整操作的合同，未发现剩余阻断。不扩张为未知驱动或完整 SQL 方言支持结论。来源证明只读副本及 owner 绑定已审，暂存/发布及其数量校验已由后续切片实现，见本节数据集证据。审查者未运行 Gradle，实际结果由执行者记录。


投影源/关系上下文独立只读复核关闭 P2：正式图资金过滤、父读取 500 分批、SQLite ordinal 64 位、证据字典数值键及碰撞覆盖；关闭 P3：空字符串/字典迭代和两个序列的错误次序。回归均失败先行，证据字典先按数值等价归一并后值覆盖，不放宽边 ID 的严格整数限制，明确 null 仍不是缺省证明。21 原定义/56 源参数的全部断言与循环已逐项复核；完整 SQL 对照另核全部字段、金额表示、UTC 微秒、token 和摘要。当前范围无剩余 finding；审查者未代跑 Gradle。原作者实际双库完整回归通过，暂存发布已完成本节内部验收，relation-origin writer 保持未完成。


SQL 源值适配独立只读复核无 finding。范围限定为当前 SQL 源值的 Decimal、UTC 时间、原生 JSON、Unicode 码点键序及 UTF-8；未覆盖通用 canonical 的任意 Enum、dataclass、tuple 等输入，不以向量计原测试定义完成。审查者实际运行 `export_cash_canonical_goldens.py --check`，1,548 向量通过；未代跑 Gradle。共用时间绑定修复先复现 `.123456999` 在 PG 进位到 `.123457`，统一截断微秒后当前完整认证/工作区/分类/账户/收据与实际客户端两库通过。

稳定收支仓库独立只读复核关闭两项 P2：脏成员 expire 后先 flush 误写，以及全局集合包含代替逐行 JOIN 归属检查。`cash-entry-review-red.log` 两项失败先行，恢复/丢弃待写属性、flush 跳过 expired、同实例重载及逐行回退后转绿。当前 12 个原生方法覆盖原 lookup 的 4 定义/12 参数及人工值、拆分、修订溢出、摘要兼容；没有新增阻断。Python Session 级上下文不能机械改成每调用独立缓存，design 已明确真实接入门禁；批量重建及更广关系图仍 pending。审查者未运行测试，双库结果由执行者记录。

单项/批量分类独立只读复核完成产品/范围、工程/DX/安全及最终 diff；关闭错误结构遗漏、失败注入位置和统计低估下全局连接问题。当前权限提示只定位锁，锁后真实会话/角色重验；PM 无限制保留全部成员，M/E 的 LIMIT 1 均由真实唯一键保证，SQLite JOIN 与 PG LATERAL 返回相同行集。原参数与断言迁移可计 4 定义/9 参数；性能原定义保持 pending。审查者未代跑构建，最新双库执行结果由执行者记录。

选项切片独立只读复核无 finding：三 DTO 的字段、缺省及 SerialName 与原包一致，普通可读角色、原标签/顺序、合法组合和调用间列表隔离正确。实际消费者和映射已接入，最新双库和受影响编译由执行者完成；审查者未代跑。不因完整目录黄金而将包含其他流程的原测试计完成。

投影状态独立只读复核未发现阻断。缺省/完整字段、失败后活动集、ready 锁顺序、管理员前后检查、跨工作区和失败合同与原实现一致。状态数值从 JDBC Long 构造，实际 HTTP 解码断言保留 Long.MAX_VALUE；没有以浮点问题的推测替代实测结论。无新 Compose 消费者，不制造重复公共 DTO；该接口只读，不据此关闭重建/原子发布。审查者没有代跑验证。

收据写入独立复核关闭 DEL ASCII 转义 P2：`receipt-store-budget-red.log` 先复现被低估的 1 MiB 预算；`char.code >= 127` 修复后精确上限、超 1 字节、DEL 超限及独立 `\u007f` 表示断言通过，见 `receipt-store-budget-green.log` 和最新 full 日志。写入前完整验证、创建冲突/原键重放/上下文消费、业务与结果同事务回滚，以及 16 个源定义的全部参数/断言未发现新增阻断。账户实际调用先重放再执行业务，仓库不自行提交；复制专项确认不保留调用方可变 Map/List。审查者未代跑构建或差分。

收据独立只读复核关闭两个 P2：全部 GET 使用工作区锁及锁后/返回前权限复核，现金路径 401/403 补齐原协议字段；原生红测与最新双库回归覆盖修复。额外 HTTP 对照发现通用 JsonElement 编码将超 Long 计数转浮点，五个 GET 改为校验后的 JSON 文本输出并通过原 2**70 向量。共用 DTO 保留原字段、缺省值和序列名；实际 Compose API 消费 Ktor 字节通过。现有客户端计数仍为 Long，Host 明确验证超范围解码失败；这是保留的兼容缺口，不宣称全范围双端可用。4 个源定义的角色、参数、异常和持久结果断言经独立复核后计迁移。审查者未代跑构建或双库。

关系纯规则 43 个原定义逐断言复核通过，P2/P3 均关闭：资金角色兼容函数按原 `exact_decimal` 接受有限浮点文本转换，端点继续 Decimal-only；非根父事实隐式资金占用的第二断言已补齐；纯关系 ID 全链路保留 BigInteger，联合成员接受 Iterable/Sequence/Iterator。`cash-relation-policy-red.log`、`cash-relation-graph-red.log` 为缺实现失败；`cash-relation-compatibility-red.log` 复现三项兼容差异，修复后绿色日志与独立只读复核关闭。原 Python 82 passed、0 skip、0.07 秒，见 `cash-relation-python-reference.log`；`cash-relation-differential.log` 通过 1,767 个完整图场景，核对原输入类型差别、精确金额表示及全部投影字段。真实 DB 财务写入、完整图调用与剩余动态子类/性能不因纯规则关闭。

投影纯构建的金额表示 P2 已关闭：退款算术保留原系数/指数，显式精度 80/HALF_EVEN；超 JVM 指数的零另行处理。`cash-projection-native-red.log` 先确认目标缺实现，`cash-projection-representation-red.log` 复现尾零丢失；修复后 `cash-projection-representation-green.log` 28 项通过，含 27 个原生方法与 1 个差分方法，差分 2,592 场景同时核对金额符号/系数/指数与完整投影。原 Python 相关 67 passed、0 skip、0.13 秒，见 `cash-projection-python-reference.log`。26 个原定义经独立只读复核可计 migrated；draft 中三个非法动态类型由真实 Instant/Boolean 工厂签名排除，映射明确说明类型适配，其余四参数真实运行时拒绝。不代表动态子类路径、性能、SQL 发布或 HTTP 接入完成。

现金纯规则 26 个原定义已逐断言独立复核，两项 P2 已关闭：ISO 周日期的日期/时间边界及全局 `Z` 替换按 Python 3.11；嵌套 Map 键和 Set 使用递归数值等价及 NaN 引用语义。`cash-policy-review-red.log` 先复现失败，`cash-policy-review-green.log` 的 30 个原生方法和 1 个时间差分测试通过；差分包含 10,020 个接受/拒绝及 UTC 向量。Tagged 两项仍 pending；独立审查仅只读，不代称执行验证。

独立上下文完成首片及本轮产品/范围、工程、DX/安全复核。范围包括 36 个已接入 HTTP 操作、权限重读与工作区隔离、JSON 字段类型、Argon2/令牌/严格 UTF-8、事务与锁顺序、最后管理员、一次性邀请、空投影初始化、删除顺序及失败回滚、公共金额规则、指数溢出和跨平台字符语义。

| finding | 处置/回写 |
|---|---|
| P1 极小指数取负溢出 | 规范化指数先限制范围，再取负和转换；Long 极值黄金与所有平台测试，写回 design |
| P2 JSON 标量转字符串丢失非法输入/组成项引用 | 公共规则增加保留原 JSON 类型的入口；组成项 ID/ordinal 均验证，错误字段和顺序差分；design 明确两层输入 |
| P2 零值极大指数 | 先结合小数位检查实际指数；新增小数零指数黄金 |
| P2 密码 surrogate 替换及 SQLite 登录写锁 | 严格 UTF-8 保持 Python 失败边界；昂贵校验锁外完成，短事务重读当前摘要；旧实现失败回归后通过 |
| P2 浮点名称/Unicode 分类差异 | 每个精度比较上下候选、精确距离与偶数；固定 Python 3.11 Unicode 14 表，全部幂边界及邻值黄金；独立重审关闭 |
| P2 并发删除备选指针悬空 | PG 对备选 `FOR KEY SHARE NOWAIT`，忙则整次回滚；屏障反例先失败，修复后通过；写回 design |
| 首片 origin/无 token 连接/存储及 schema 错误 | 均已补回归并在本轮 backend 测试重跑 |

分类删除本片独立上下文完成产品/范围、工程、DX/安全及最终 diff 复核，覆盖集合更新、当前权限/工作区锁、修订极值、输入字节、原断言映射和实际 Compose 请求/响应；已报 finding 均关闭。两个原性能定义与其余分类/发布任务继续 pending；独立审查者只读复核，不声称其代跑构建或测试。

分类切片独立复核关闭目录写回 DTO、参数/fixture 指纹、整数控制空白、排序时间戳及 editor 权限漏断言。最后账本原财务 fixture 用例降为 pending；新增根配置检查已补失败回归。账户独立复核还关闭名称 `+1` 误认 ID、坏快照证券写入、unknown/read 存储合同、原始 JSON 控制字符、服务键/收据元数据、editor 前置和重复筛选末值 finding；对应失败先行、原生及双库回归已通过。25 个账户/邀请候选逐断言复核通过后更新状态；参数门禁增强也经独立只读复核。本阶段发现项均有处置与独立复核，收据提交/上下文写入等未实施切片不据此视为已审查；没有执行重构建的审查者仅声称只读复核。原 Python 顶层非对象/破损 JSON 内部错误保持原边界，未扩大本轮凭据字段类型调整。待验收的完整工作区财务删除与其余操作撤权等待不算已审查验证完成；分类目录/摘要 path DTO 已按各自 wire contract 适配。数据库 URL 的默认 SQLite/相对路径、PG socket/SSL 等装配兼容仍未完成。

手工新建独立复核覆盖产品/范围、工程、DX/安全、最终 diff 和原断言映射，无剩余阻断。P2 输入容器别名、raw surrogate 日期分隔符、文本替换写入及巨指数零均已实际失败先行；对应 manual-cash-protocol-copy-red.xml、manual-cash-time-bytes-red.xml、manual-cash-utf8-red.xml 和 manual-cash-review-red.xml。UTF8 多字段错误次序和 PG 失败后子序列由扩展 source 对照实际复现并修正，证据 manual-cash-utf8-order-red.xml、manual-cash-component-sequence-red.xml。FINITE 特殊值使用 ASCII 规则；原 editor 前置已补齐，PG 权限测试以实际数据库锁队列作屏障，清理先等待异步请求结束。审查者只读，未代跑构建；最终双库/平台/Netty 结果由执行者记录。OpenAPI、其余创建/维护/收据与完整结果未知路径不因本片关闭。

本轮文档核对范围为 README/backend README、功能地图、接口矩阵、inventory、design/tasks、主规格/delta、词表、项目 apply skill。没有新页面或用户术语；既有普通创建 API 已接入本机预览，页面数仍为 10，两端既有 TODO 保留。公共模块和后端并行存在不代表已部署。回滚继续保留原 Python 与 schema，不切流、不双写；本轮无生产数据库变更。

转账最终独立复核覆盖范围、工程、DX/安全、原定义/参数映射和最终 diff，无剩余阻断。P2 Python/JVM 小写差异、日期 raw CESU 来源、投资量化/日期/备注顺序和源成功断言遗漏均已补回归并重审；纯函数负数/负零与公开非正拒绝分别验证。失败先行见 `cash-transfer-rules-red.log`、`cash-transfer-rules-review-red.log`、`cash-transfer-write-red.log`、`cash-transfer-http-red.xml`；完整 92 场景包含修复后的组合分支。源映射 3/9 已由只读复核确认，执行者完成双库完整及受影响复跑。README、功能地图、routes、inventory、词表现有转账概念、协议消费者和迁移技能已按最终实现核对；未引入新用户术语，页面数仍为 10。未迁移操作、OpenAPI、组合原测试及未授权发布动作保持未完成。
