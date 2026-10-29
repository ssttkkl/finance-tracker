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
- [ ] 4.7.2.7 接入旧现金新增和余额校准，保留旧入口账户/来源/金额/快照与响应语义；同步修复已授权的响应编码失败后仍提交缺陷，Python/Kotlin 先失败回归，再验证双库整笔回滚；双库原断言和事务验证后逐接口验收。
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
- [ ] 6.3 完整双库 Python 回归与 Kotlin 迁移合同；完整原性能、内存/资源/安全预算、连接器错误/重试/回滚、worker 生命周期/SSE，门槛不放宽。
- [ ] 6.4 OpenSpec all strict/doctor、构建/类型/依赖与 Gson 禁用检查、文档链接/锚点和 git diff --check。
- [ ] 6.5 全量 Python 测试对应表无未处理规则、参数和断言；目标测试独立运行通过，存储双库与共享跨平台证据完整；保留项理由与 Kotlin 兼容验证经独立复核。接口迁移和实际双端复用另行计数，不混为测试完成率。

Cross-platform Impact Check：Web、Android、iOS、JS/Wasm 的公共 DTO 和纯规则消费者受影响；后端新增 JVM 消费者。首片不改变页面、文案或产品交互，不适用 UI 原型/Hallmark/演示循环；用户明确禁止自动打开浏览器，不运行浏览器 UI 验证，不宣称其通过。若后续出现可观察 UI 变化，先 Flow-Back 确定验收与用户限制再实施。

## 7. 发布准备

- [ ] 7.1 核对 README/docs/references/接口/配置/schema/运行入口、功能地图和词表；说明当前可用/未迁移能力及格式过渡边界，记录回滚/观察项。
- [ ] 7.2 同步 delta 后验证并归档（仅真正完成时）；用户已授权暂停迁移并将未完成进度提交、推送至 `origin/codex/kotlin-backend-shared-core`，本次不运行单测；未授权 PR、合并、部署、切流、真实数据迁移或旧后端删除。

## 8. 反思

- [ ] 8.1 按里程碑将可复用的兼容/数值/安全发现原位沉淀至项目 OpenSpec skills；重复失败后回到事实与根因，不改测试门槛逃避。

## 当前证据

迁移已按用户要求暂停；当前提交用于保存未完成进度，本次不运行单测，不代表开发完成或最终验收通过。旧现金新增与余额校准正在实施，包含未验收的该切片及原子写入复用改动。下列 219 文件快照界定已验收的转账范围；其完整回归不代表当前新增实现已通过。新切片按 4.7.2.7 继续失败先行、双库验证、原测试迁移和独立复核，验收前不增加接口或原测试完成数。

暂停时待办：`CashSnapshotFailureBoundaryTest` 已先运行失败，存储快照形状的内部错误/输入错误分类尚未修复；新响应编码源回归已先红后绿（Python 两库共 4 参数），Kotlin 响应编码、金额/整数/币种及相关写入已做范围化双库验证，见 `legacy-cashflow-inputs-<sqlite/postgres>-green-results.json/log`，不能代替当前完整回归。独立复核仍要求补齐响应编码回归的 editor 前置，以及组件币种原始代理字符校验；原测试对应表也须纳入新增源定义和参数。以上问题未关闭，4.7.2.7 仍未完成，接口与原测试完成数不增加。

当前分类、账户、永久收据、稳定收支、投影源/关系上下文、全量与增量发布、手工收支新建及完整转账创建已完成相应切片验收。新建在同一事务写入流水、现金部分、余额、稳定收支和投影，继续使用现有 Compose 创建消费者。

完整后端仍未完成；OpenAPI schema、旧现金新增/余额校准、转账编辑、草稿/维护/导入、关系/投资、其余查询与后台任务及原完整性能/历史迁移矩阵继续 pending。

2026-10-29 10:10 CST；HEAD 与比较基线均为 `0c038a75e34264bd6d4aa5cce2070727df9d9ff7`。已验收的代码、原生测试、工具、构建和文档共 219 文件快照见证据目录 `cash-transfer-write-validated-source.json`，SHA-256 为 `6c59996e304f7f63d1ef90380a3a39805a5caea77c69a12ffc2b95372cb09b68`；证据对应工作树 `/Users/huangwenlong/Documents/Codex/2026-10-28/task/finance-kotlin-backend`，分支 `codex/kotlin-backend-shared-core`。Node `v26.8.1`、OpenSpec `1.7.0`。既有验证执行时 HEAD 仍为上述比较基线，验收范围由文件快照界定；当前进度提交仅保存后续未验收改动，没有部署、生产入口变更或真实数据操作。

证据根目录为 `/Users/huangwenlong/Library/Caches/finance-kotlin-backend/evidence`（下表简称「证据目录」），任务缓存根为其父目录。PostgreSQL 16 由执行者人工准备于 `127.0.0.1:55487`，用户 `ft_kotlin_test`，所有库名以 `_test` 结尾；测试不探测或启动数据库。读取、认证、工作区及 live 验证分别使用隔离匿名库。Python 解释器为 `/Users/huangwenlong/.paseo/worktrees/1zalea72/average-snail/.venv/bin/python`，Python 命令设置 `PYTHONDONTWRITEBYTECODE=1`、`PYTHONPATH=tests:.:src`。

Gradle 命令在 `compose/` 运行，公共选项为 `--no-daemon --no-configuration-cache --max-workers=1 --project-cache-dir /Users/huangwenlong/Library/Caches/finance-kotlin-backend/gradle-project -Dorg.gradle.jvmargs=-Xmx2048m -Pkotlin.compiler.execution.strategy=in-process`。SQLite 分别显式设置 `FT_KOTLIN_CONTRACT_DIR=/Users/huangwenlong/Library/Caches/finance-kotlin-backend/oracle-sqlite-v2`、`FT_KOTLIN_AUTH_CONTRACT_DIR=/Users/huangwenlong/Library/Caches/finance-kotlin-backend/oracle-auth-sqlite`、`FT_KOTLIN_WORKSPACE_CONTRACT_DIR=/Users/huangwenlong/Library/Caches/finance-kotlin-backend/oracle-workspaces-sqlite`；分类另设置 `FT_KOTLIN_CATEGORY_CONTRACT_DIR` 为同根 `oracle-categories-sqlite-v1`，账户设置 `FT_KOTLIN_ACCOUNT_CONTRACT_DIR` 为 `oracle-accounts-sqlite-v3`，收据设置 `FT_KOTLIN_RECEIPT_CONTRACT_DIR` 为 `oracle-receipts-sqlite-v1`；PG 使用同根的 `oracle-postgres-v4`、`oracle-auth-postgres`、`oracle-workspaces-postgres`、`oracle-categories-postgres`、`oracle-accounts-postgres-v3`、`oracle-receipts-postgres-v1`，另设置 `FT_KOTLIN_TEST_DB_USER=ft_kotlin_test`。分类删除另设置 `FT_KOTLIN_CATEGORY_DELETION_CONTRACT_DIR` 为同根 `oracle-category-deletion-sqlite-v4` / `oracle-category-deletion-postgres-v4`。单项/批量分类设置 `FT_KOTLIN_CLASSIFICATION_CONTRACT_DIR` 为同根 `oracle-classification-sqlite-v1` / `oracle-classification-postgres-v1`；本片完整 PG 基础读取库改用 `oracle-postgres-v4`，失败测试的旧专库不再用于验收。源读取对照另设置 `FT_KOTLIN_PROJECTION_SOURCE_CONTRACT_DIR` 为同根 `oracle-projection-sources-sqlite-v2` / `oracle-projection-sources-postgres`，对应新专库 `finance_kotlin_projection_source_test` / `finance_kotlin_projection_source_oracle_test`。发布全列对照另设置 `FT_KOTLIN_PROJECTION_DATASET_CONTRACT_DIR` 为同根 `oracle-projection-datasets-sqlite-v3` / `oracle-projection-datasets-postgres-v3`，真实 PG 库为 `finance_kotlin_projection_dataset_v3_test` / `finance_kotlin_projection_dataset_oracle_v3_test`。管理员 HTTP 对照设置 `FT_KOTLIN_PROJECTION_REBUILD_CONTRACT_DIR` 为同根 `oracle-projection-rebuild-sqlite-v2` / `oracle-projection-rebuild-postgres-v2`；增量对照设置 `FT_KOTLIN_PROJECTION_INCREMENTAL_CONTRACT_DIR` 为 `oracle-projection-incremental-sqlite` / `oracle-projection-incremental-postgres`，PG 专库为 `finance_kotlin_projection_incremental_test` / `finance_kotlin_projection_incremental_oracle_test`。转账另显式设置 `FT_KOTLIN_TRANSFER_CONTRACT_DIR` 为同根 `oracle-transfer-sqlite-v3` / `oracle-transfer-postgres-v2`，PG 专库为 `finance_kotlin_transfer_v2_test` / `finance_kotlin_transfer_oracle_v2_test`。手工新建另显式设置 `FT_KOTLIN_MANUAL_CASH_CONTRACT_DIR` 为同根 oracle-manual-cash-sqlite-v4 / oracle-manual-cash-postgres-v3，PG 专库为 finance_kotlin_manual_cash_v3_test / finance_kotlin_manual_cash_oracle_v3_test。原始日志保留完整输出；不同后端的测试 task 均实际执行。

| 范围 | 命令、结果与证据 |
|---|---|
| 接口盘点 | 真实 `create_app(...).openapi()` 导出 86 个唯一方法/路径，与 routes 一致；已实现 15 个认证相关接口、分类读取/创建/编辑/排序/删除检查/删除、单项/批量分类、账户按 ID 读写及现金/投资列表、永久收据五个 GET、管理员投影状态/重建及账本选项。当前 38/86（44.2%），未迁移操作 48 个，不把接口存在当作完整领域验收 |
| Python 参考回归 | 显式 `FT_REQUIRE_TEST_POSTGRES=1 FT_TEST_POSTGRES_URL=postgresql+psycopg://ft_kotlin_test@127.0.0.1:55487/finance_kotlin_reference_test`，上述解释器 `-m pytest -q -p no:cacheprovider tests/test_exact_decimal.py tests/test_cash_record_policy.py tests/test_cash_category_management.py tests/test_cash_category_api.py tests/test_user_workspace_access.py --basetemp=/Users/huangwenlong/Library/Caches/finance-kotlin-backend/pytest-category-reference`：109 passed、无 skip、6 项既有 SQLAlchemy 警告，17.11 秒；`migration-category-python-reference.log`。不代替 Kotlin 测试迁移或完整财务删除验收 |
| 失败先行 | `auth-red.log` 缺少 Passwords/AuthenticationService/write API；`workspaces-red.log` 缺少 WorkspaceService；`shared-rules-review-red.log` 两项失败；`auth-boundaries-red.log` 严格 UTF-8、锁外校验两项失败；`python-text-red.log`、`python-power-text-red.log` 字符串差分失败；`workspaces-delete-race-red.log` 实际复现 PG 悬空替代工作区。随后最小修复并重跑 |
| 金额公共规则 | `:business-domain:jvmTest :business-domain:jsNodeTest :business-domain:wasmJsNodeTest`；JVM/JS/Wasm 各 9 项、0 skip；`migration-category-client-platforms.log`，包含原 Decimal 表示/拒绝向量与分类移动顺序回归。47 个金额接受边界与 338 个现金部分结果，覆盖金额范围、Unicode 十进制、零/指数/Long 极值、JSON 类型、缺省/显式空、组成项 ID、ordinal、方向/币种/精确合计及精确错误 |
| 认证 HTTP 与状态 | `:backend:test`，含原读取黄金 26 请求、认证 26 请求和工作区 53 请求；每次对照完整响应及选定用户、会话摘要、成员、邀请与投影状态。非字符串凭据另有 20 请求无写入；双向 Argon2 校验、注册竞争与事务回滚另验。上述 HTTP/存储片含 54 项；当前连同现金纯规则各后端 374 项 JUnit、0 failure/error/skip，见 `cash-transfer-write-all-sqlite.log`、`cash-transfer-write-all-postgres.log`，包括分类 44 组响应/全行快照、账户 56 组响应/规范化命令摘要/全部持久状态，控制字符拒绝无写入、移动深度和兄弟时间戳回归 |
| 并发/失败边界 | PG 屏障固定并发删除交错；SQLite 按写锁串行执行同合同。两后端覆盖并发管理员自降/邀请消费、到期、已有角色保留、坏密码不抢写锁、初始化失败、删除最后一步失败整体回滚；对应 WorkspaceWriteBoundaryTest 与 AuthenticationWriteBoundaryTest |
| 旧字段文本 | `PythonStringGoldenTest`，14,619 个对照样本通过；包含固定/随机数、全部有限二进制幂及两侧相邻值/负值、Unicode 14 可打印边界。仅用于非凭据字段，不用于金额。生成器 `scripts/export_python_string_goldens.py --check` 核对实际内容 |
| 构建与客户端 | `cash-transfer-write-all-sqlite.log` 包含当前完整后端 374、Host 25、business-domain JVM/JS/Wasm 各 9 项、发行包及受影响 iOS/JS/Wasm/Web 编译，156 tasks 通过。PG 同前缀日志再次实际执行后端 374、Host 25 及 installDist；无 failure/error/skip。实际命令/环境/耗时见 `cash-transfer-write-all-<sqlite/postgres>-results.json`。contracts 三目标原各 3 项的输入未变，`ledger-options-<sqlite/postgres>-results.json` 保留原命令；当前实际消费和平台编译由上述新证据覆盖 |
| 真实 Netty | 任务 loopback 上以 `-Xmx512m` 运行发行包，证据目录 `verify_live_access.py` 执行动态令牌、创建/邀请/接受/权限拒绝/极小浮点与 Unicode 旧字段转换/改名/删除/登出的 30 请求流程；SQLite 与 PG 各 30 passed，`live-access-sqlite.log`、`live-access-postgres.log`。入口分别为 `127.0.0.1:18340/18341`；本任务两个服务均已正常退出，不打开浏览器 |

原测试清单以固定基线重新枚举 tests 和 compose/tests，2,612 个源定义、完整 pytest collection 5,840 个用例；对应表审查 250 个定义，其中 227 migrated、23 pending，其余默认 pending，全量共 2,385 pending。已迁移定义对应 568 个源展开参数，不等同于 JUnit 方法数或完整迁移完成率。`cash-transfer-migration-check.log` 与 `test-migration-inventory.json` 记录当前状态；完整 `--require-complete` 有待迁项必须失败。门禁自身 9 项回归、根配置/模块参数/fixture/样本及完整节点核对证据仍为 `migration-config-green.log`、`migration-config-red.log` 和 `migration-parameter-red.log`。Decimal 私有不可变类型适配已复核，自定义 `__str__`、账户旧写入/底层 CAS/历史迁移及 OpenAPI 等未覆盖项保持 pending。

分类深度旧缺陷按用户确认同步修复 Python 与 Kotlin，失败先行见 `category-depth-python-red.log`，转绿及原断言保留；不自动修复真实数据库。目录名称数组与领域祖先对象的解码先失败见 `category-write-decoder-red.log`，当前实际读写回包已通过。分类删除的使用量、引用清除与投影同步已完成本片验证；独立 move 已接入当前投影维护，update 保持原 Python 调用边界。

账户原 Python 参考命令为 `FT_REQUIRE_TEST_POSTGRES=1 FT_TEST_POSTGRES_URL=postgresql+psycopg://ft_kotlin_test@127.0.0.1:55487/finance_kotlin_reference_test <上述解释器> -m pytest -q -p no:cacheprovider tests/contract/test_account_commands.py tests/contract/test_account_command_api.py --basetemp=/Users/huangwenlong/Library/Caches/finance-kotlin-backend/pytest-accounts-reference`：94 passed、无 skip、1 项既有 Starlette 弃用警告，49.21 秒；`accounts-python-reference.log`。原生账户专项覆盖 editor 成功/真实注册与工作区、当前成员与跨区、三类原引用向量、坏快照、余额更名不误归属、空补丁/旧修订、并发原键、晚期回滚及提交后丢失结果。SQLite 验证鉴权后撤权，PG 真实持锁/屏障验证等待后重读；均无账户/快照/收据写入。重复 `view` 按末值解析，先失败回归见 `accounts-view-query-red.log`。

真实 Netty 账户验证由证据目录 `verify_live_accounts.py --origin http://127.0.0.1:18342/18343` 执行（每次一个明确 origin），SQLite 与 PG 各 19 请求通过，见 `accounts-live-sqlite.log`、`accounts-live-postgres.log`；覆盖创建/读取/修改/旧修订拒绝/列表/停用/删除/删除后重放/ID 不复用/坏 JSON 和原键冲突。SQLite 使用匿名 `account-target-v3_test.db`，PG 使用独立 `finance_kotlin_accounts_test`，不与当前 v3 合同测试库并发修改。两个任务服务均已退出，没有打开浏览器。

当前 14 个生成器 `--check`（共享规则、Python 文本、casefold/uppercase/数字表、现金时间、投影、完整关系图、安全问题原因表、收据写入校验、账本选项、分类 JSON bytes、SQL 源摘要、手工请求/仓库时间、转账事件/余额）、33 个受影响 Python 文件编译、OpenSpec strict 38/38 与 doctor、189 个范围内相对链接/锚点、tracked diff 及 228 个范围文件 whitespace 检查通过；证据为 `receipt-store-generator-checks.log`（除 Python 文本表外的输入未变；文本表已按新 Unicode 字母表重验）、`ledger-options-golden-check.log`（选项输入未变）、`category-deletion-json-golden-check.log`、`cash-canonical-golden-check.log`、`cash-transfer-write-openspec.log`、`cash-transfer-write-doctor.log`、`cash-transfer-write-static-check.json` 和 `cash-transfer-write-links.json`。原工作树 `old-racoon` 保持干净；当前全部改动仍未提交。公共模块未新增运行时依赖；现金纯规则仍在 backend，不依赖 Compose UI、SQL 或外部业务调用。

尚未运行：完整 Python/Kotlin 双库回归、原完整性能/资源矩阵、全部金额运算/币种/时间/类型、完整工作区财务数据删除、其余写操作的锁等待撤权/换区/登出以及其余迁移模块。补跑条件为对应切片实现、生成相应匿名场景并绑定独立专库；skip 不计通过。公共模块消费和 Native 编译不代表完整前端功能或设备 QA。UI 没有产品行为变化，按用户限制不打开浏览器，不宣称浏览器或设备验证通过。

现金纯规则当前检查点：`:backend:test` 在上述显式 SQLite/PG 环境各 374 项、0 failure/error/skip，`cash-transfer-write-all-sqlite.log`、`cash-transfer-write-all-postgres.log`；PG 同命令执行 `:backend:installDist`。原 Python `tests/test_cash_record_policy.py` 在上述解释器/环境 61 passed、0 skip，0.04 秒，见 `cash-policy-python-reference.log`。时间生成器 `scripts/export_cash_time_goldens.py --check` 验证 10,020 个匿名样本。当前源码快照覆盖本次现金规则文件；投影和关系纯构建已通过本切片原生测试与复核，其余现金写入仍待实施，不宣称完整后端迁移已完成。

收据读取当前证据：`ReceiptHttpContractTest` 在两个新匿名专库各走真实 Netty 的 60 个请求，完整比对 Python 响应并核对 11 张表读取前后无写入；包括旧键、所有者/工作区、当前投影引用、分页、坏存储结果、损坏转义、5000 字符查询和超 Long 计数。当前 backend 每库 374 项通过，结果与精确命令/环境见 `cash-transfer-write-all-<sqlite/postgres>-results.json`。`receipt-review-red.log` 先复现返回前撤权与现金鉴权字段遗漏，`receipt-raw-query-red.log` 复现原始 HTTP 边界；修复后上述 final 命令通过。PG 专用屏障测试验证 id/key/rows/relations 等待工作区锁后撤权；SQLite 不运行该 PG 分支，其成功不能当作 PG 并发证据。两条 API 原生迁移显式使用 editor，分页保留 `mutation_page_invalid` 消息断言，已删除目标的永久引用同时核对真实 SQL 值和仓库返回值。

原 Python 收据两文件在同一显式 PostgreSQL 环境运行 `-m pytest -q -p no:cacheprovider tests/contract/test_cash_legacy_receipt_api.py tests/contract/test_cash_mutation_receipts.py --basetemp=/Users/huangwenlong/Library/Caches/finance-kotlin-backend/pytest-receipt-reference`：113 passed、无 skip、1 项既有 Starlette 警告，50.99 秒，见 `receipt-python-reference.log`。原生迁移只计其中 4 个已复核定义和 27 个展开用例；原文 owner/retry、通用 UoW、历史迁移和日志等仍 pending；仓库上下文及写入校验由下述独立原生证据验收，Python 回归不能代替这些迁移。

永久收据写入当前证据：`receipt-store-red.log` 先确认 create/replay/for_context 缺实现；原生 store 16 个方法（含复制、预算、原始 SQL 和 SQLite 索引）加 1 个差分方法，通过 1,052 个 `_summary/_results` 接受/拒绝、类型、完整结果及 ASCII 字节数向量。账户实际改为使用同一仓库后，两库当前 `:backend:test :backend:installDist` 各 374 项、0 failure/error/skip，完整日志与精确命令/环境见 `cash-transfer-write-all-<sqlite/postgres>.log`、`cash-transfer-write-all-<sqlite/postgres>-results.json`。最新实际账户/收据回包再由 Compose 正式消费者各 3 项验证，`receipt-store-client-<sqlite/postgres>-final.log`；原共享 DTO/平台代码无后续变化，前述 76 项 Host 和平台编译仍适用。

本切片新增 16 原定义、64 个展开用例经独立只读复核并完成最新双库回归。直接 JDBC 绕过仓库验证会话永久唯一（PG 23505）和工作区复合外键（PG 23503），SQLite 对应约束码 19；通过外层回滚标记核对没有部分保存。SQLite 原未筛种类分页的 EXPLAIN 没有 TEMP B-TREE，仅作为 SQLite 索引证据，不把 PG 空分支计该性能证明。原通用 UoW、历史 DDL 与 retry 继续 pending。此轮仅 backend 实现改变，共享/Native/UI 产品合同未变；实际回包解码已补验，不追加浏览器或设备操作。

投影状态当前证据：`projection-state-red.log` 先确认缺少状态服务；4 个原生方法覆盖缺省无写入、11 个存储字段、失败构建保留活动集、Long.MAX_VALUE 精度、当前工作区/管理员权限、middleware 后降权和固定存储失败合同。`cash-transfer-write-all-<sqlite/postgres>.log` 的当前完整后端回归及 installDist 各 374 项、0 failure/error/skip；最新收据实际回包 Host 各 1 项通过，`projection-state-client-<sqlite/postgres>.log`，精确命令/环境见对应 results JSON。共享/平台代码无变化，不重跑无受影响的编译。原 Python `tests/test_application_cash_projections.py` 19 passed、无 skip、23 项既有 SQLAlchemy 警告，10.34 秒，`projection-state-python-reference.log`；命令沿用上述显式参考环境，basetemp 为 `pytest-projection-state-reference`。缺省状态与全量重建的 11 个源定义/13 个参数已独立复核并计迁移；现金写入及包含 sync 的 admin-only 组合定义保持 pending。

账本选项当前证据：`ledger-options-red.log` 先复现缺路由，`ledger-options-copy-red.log` 复现跨调用列表共享。当前完整双库各 374 后端/25 Host，实际 fetchLedgerOptions 与领域映射由 KotlinLedgerOptionsResponseIntegrationTest 核对全部 17 类型/3 关系、合法子类型、标签和枚举顺序。共享 DTO 输入未变；当前命令、环境、构建及结果见 `cash-transfer-write-all-<sqlite/postgres>-results.json/log`。admin/editor/viewer 和空工作区可读且不初始化状态/数据集；不以完整目录黄金计其他原流程完成。

分类删除当前证据：`category-deletion-red.log` 先确认删除检查缺路由。`cash-transfer-write-all-<sqlite/postgres>.log` 完整 backend 各 374 项、installDist 通过；Compose 实际回包 Host 同次执行 `cash-transfer-write-all-<sqlite/postgres>.log` 各 13 项通过，均无 failure/error/skip，精确命令/环境、时间及结果见 `cash-transfer-write-all-<sqlite/postgres>-results.json`。新匿名双库各 25 个 HTTP/11 表全列状态对照；原生覆盖目录/分类修订、使用量及确认、live/deleted 人工空值保护、独立恢复值、跨 500 批候选去重、active/staging 投影、未就绪状态和三个 Long.MAX 整事务回滚。PG 用 `pg_blocking_pids` 观察真实工作区锁等待，再撤权并验证拒绝、无写入；SQLite 另验 middleware 后降权，不冒充 PG 证据。实际回包由现有 fetchCashCategoryDeletionImpact/deleteCashCategory 和目录 API 消费，未修改产品界面。

DELETE 使用原始字节的 Python 编码/语法/整数错误兼容；9,361 个 JSON bytes 黄金、34 个坏 JSON/非法字节 HTTP 无写入、9 种有效编码及 9 个混合代理整数错误向量通过，包含精确位置、4,300 位与 repr 200 字符上限。独立复核关闭候选 OR 扫描、SQLite 修订溢出、坏 JSON/字节、代理来源及长 repr finding；原输入错误继续拒绝，内部错误停止并回滚。

分类删除性能使用 1,000 分类/10,000 事实、当前收支和成员。当前 SQLite/PG 检查 p95、删除耗时及进程 RSS 采样增长见各 `client-<sqlite/postgres>/category-deletion-performance.json`；保留 250/500 毫秒和 96 MiB 门槛，集合仓库 7 条 SQL（含 impact），加工作区/state/delete/bump 共 11 条，未超过原 12 条财务 SQL 门槛。SQLite 实际 JDBC EXPLAIN 验证当前使用量索引与候选两个依赖索引，见 `client-sqlite/category-candidate-plans.json`。RSS 是并发 ps 采样，不能冒充原 getrusage 高水位；两个原性能定义仍 pending，不计入 227/568。原 Python 参考包括分类 management/API、manual classification 和这两个性能定义，81 passed、无 skip、48 项既有警告，44.31 秒，`category-deletion-python-reference.log`；精确参数为上述参考环境与 `tests/test_cash_category_management.py tests/test_cash_category_api.py tests/contract/test_cash_manual_classification.py tests/test_cash_category_performance.py::test_large_category_deletion_impact_uses_indexed_count tests/test_cash_category_performance.py::test_large_category_delete_clears_usage_in_one_write`，basetemp 为缓存根 `pytest-category-deletion-reference`。

Cross-platform Impact Check：Web 与 Native 沿用现有分类检查/确认流程、字段和错误语义；公共 UI/渲染与共享生产源码未改变。Host 实际消费本轮 Kotlin 响应并核对请求字段，两库均运行既有分类 ViewModel/用例和实际客户端，共各 13 项。新增后端代码仅 JVM，沿用前述未变化的 iOS/JS/Wasm 编译证据，不把未运行的浏览器或设备 QA 写成通过。没有新增页面或管理员状态入口，F-07 既有其他验收 TODO 保留。


单项/批量分类当前证据：`classification-red.log` 先确认缺实现，HTTP 对照发现现金鉴权遗漏结构字段，修复后新匿名双库各 27 个 HTTP 响应及 12 张表全部列对照通过。`cash-transfer-write-all-<sqlite/postgres>.log` 为最新完整 backend 各 374 项、installDist 通过；Host 同次执行 `cash-transfer-write-all-<sqlite/postgres>.log` 各 13 项，均无 failure/error/skip；命令、显式环境和耗时见 `cash-transfer-write-all-<sqlite/postgres>-results.json`。Host 包含六个 `Kotlin*ResponseIntegrationTest` 及分类 ViewModel/用例。原生 8 项覆盖 editor、原始流水独立、同值/清空保护、完整成员、单成员恢复值、维护可见性、删除成员、三类版本溢出、投影晚期数据库故障全回滚；真实非空关系与候选保持原值，故障位置由真实 JDBC 指定异常标志证明。PG 四分支观察实际工作区锁等待后撤权、换区、到期和登出，SQLite 验证 middleware 后相同变化；均拒绝且财务表不变。4 个源定义全部断言与 9 个源参数经独立复核后计入，对照 API 黄金不另算迁移。

分类性能先由真实完整 API 计数发现 14 条 SQL，`classification-performance-red.log`；锁后联查优化后保持原门槛。完整 PG 回归再由 `classification-full-plan-red.log` / `classification-join-order-red.log` 复现统计低估下的全局连接超时，`classification-live-stale-plan.txt` 记录错误形状。按 design 固定每级选中键边界后，最新新库完整回归通过，`classification-stale-lateral-plan.txt` 为保留夹具上独立查询 1.442ms 的计划证据，不冒充 API 耗时。实际 1,000 分类/10,000 投影、100 组/200 成员的五次 API p95：SQLite 8.586ms / 11 条 SQL，PG 77.395ms / 10 条 SQL；单成员分别 12/11 条 SQL。固定证据为 `cash-sql-values-classification-performance-<sqlite/postgres>.json`。计数包含真实 middleware、锁、重验和执行批次，SQLite 额外包含 BEGIN IMMEDIATE，未提高原 12 条/500ms 门槛。没有原 getrusage 高水位等效验证，三个相关原性能定义继续 pending。失败运行的旧专库均为本任务匿名 `_test` 库，没有真实数据。

Cross-platform Impact Check：沿用既有 Compose classifyCashProjections 请求/结果和领域映射，完整回包由正式客户端分别对两库消费；本片没有共享生产代码、页面、文案、交互或 Native 行为变化。前述 iOS/JS/Wasm 编译仍适用；不运行或宣称浏览器/设备 QA。

稳定收支仓库当前证据：`cash-entry-red.log`、`cash-entry-behavior-red.log` 先确认缺实现，两个缓存语义反例在 `cash-entry-review-red.log` 失败后修复。`cash-transfer-write-all-<sqlite/postgres>.log` 最新完整 backend 各 374 项、installDist 通过，0 failure/error/skip；精确命令、显式环境和耗时见 `cash-transfer-write-all-<sqlite/postgres>-results.json`。其中 12 个原生方法覆盖原四个 lookup 定义全部 12 参数、Python 固定摘要、人工冲突整事务回滚、旧根优先、明确拆分继承与各自恢复值、同值保护、单成员恢复、来源独立、初次/当前修订编辑及溢出回滚。`cash-entry-review-green.log` / `cash-entry-review-postgres.log` 为本片专项。原 Python `tests/contract/test_cash_entry_lookup.py` 在上述显式参考环境执行，12 passed、0 skip、12 项既有 SQLAlchemy 警告，6.56 秒；basetemp 为缓存根 `pytest-cash-entry-reference`，见 `cash-entry-python-reference.log`。

本片只增加 JVM 内部仓库并开放既有字段规范化供它调用，没有共享生产代码、HTTP 装配或客户端行为变化。前述实际客户端、平台编译和分类 HTTP 黄金仍适用；不增加接口数；全量重建接入另见本节，公开现金写入及历史迁移任务继续未完成。


SQL 源值当前证据：`cash-sql-values-red.log` 先确认缺少编解码器，`cash-sql-timestamp-postgres-red.log` 复现纳秒尾数被 PG 进位。`CashSqlValuesMigrationTest` 的 4 个方法通过 Decimal 表示、时间实际绑定、固定源值及 1,548 个完整文本/UTF-8 摘要接受/拒绝向量。`cash-transfer-write-all-<sqlite/postgres>.log` 最新完整后端各 374 项、Host 各 25 项及 installDist 通过，0 failure/error/skip；命令、环境和时间见 `cash-transfer-write-all-<sqlite/postgres>-results.json`。SQL 源值向量不增加原 canonical 定义计数；当前合并上下文切片后的累计为 227/568。没有共享生产代码、UI 或 Native 行为修改，沿用既有平台编译边界。


投影源及关系上下文当前证据：`projection-sources-red.log`、`relation-context-red.log` 先确认缺实现，`projection-sources-review-red.log` 三个边界反例失败；`relation-context-review-red.log`、`relation-context-error-order-red.log`、`relation-context-proof-collision-red.log` 分别复现数值键、错误次序和等价键覆盖问题。修复后当前 `cash-transfer-write-all-<sqlite/postgres>.log` 后端各 374 项、实际回包 Host 各 25 项及 installDist 通过，0 failure/error/skip；精确命令、环境、耗时见 `cash-transfer-write-all-<sqlite/postgres>-results.json`。其中 21 个原生方法覆盖确认闭包、显式完整组、旧版本拒绝、角色证据、500 批边界/查询预算、全部资金及未资助部分、SQLite 大整数、草稿/删除父和完整 ASCII token；另 1 方法在两个新匿名数据库逐字段对照全部来源、已解析关系、7 组完整上下文和 source digest。生成器的实际命令、显式 URL/目录和环境见 `projection-sources-oracle-commands.json`，输出见 `projection-sources-oracle-<sqlite/postgres>.log`，不包含真实账单。

原 Python `tests/contract/test_cash_relation_context.py` 在本节解释器与显式双库环境执行 `-m pytest tests/contract/test_cash_relation_context.py -q --basetemp=/Users/huangwenlong/Library/Caches/finance-kotlin-backend/pytest-relation-context-reference`，58 passed、0 skip，25.97 秒，`relation-context-python-reference.log`。21 定义/56 源参数经逐断言独立复核计迁移；relation-origin writer 的 1 定义/2 参数仍 pending，黄金对照不另计原定义。读取/准备过程没有新增 HTTP、UI、Native 或公共模块消费者，页面数和平台验证范围不变。全量重建、发布消费与数量围栏及失败诊断见本节；公开现金写入与原性能定义仍待实施。


事务生命周期及来源证明当前证据：`cash-sql-transaction-red.log` 先确认缺少证明能力；`cash-sql-transaction-review-red.log`、`cash-sql-transaction-trigger-red.log`、`cash-sql-transaction-dialect-red.log`、`cash-sql-transaction-engine-rollback-red.log`、`cash-sql-transaction-dollar-identifier-red.log` 复现边界及接口问题后修复。14 项原生方法在 SQLite/PG 专项各通过，见 `cash-sql-transaction-review-<sqlite/postgres>.log`；当前完整后端每库 374 项、Host 每库 25 项和 installDist 通过，0 failure/error/skip，实际命令、环境和耗时见 `cash-transfer-write-all-<sqlite/postgres>-results.json`。SQL 异常保守使旧证明失效，不把 SQLite 的 autoCommit 标志当作物理事务证据；普通 DML batch、元数据、savepoint 和现有接口全部回归。新增证明已由真实 publish 消费，仓库/事务/数量组合原定义经双库及复核计迁移；累计为 227 原定义/568 展开用例，接口仍为 38/86。无共享/平台/UI 改动，不追加浏览器或设备验证。


数据集和全量重建当前证据：`projection-dataset-red.log`、`projection-service-red.log` 先确认缺少仓库/应用服务。10 项数据集原生方法覆盖 901 条真实写入及 900/1 分批、受限回查、完整角色/关系、旧人工值、无效证明、空集/重复发布、源变化/缺成员/活动集拒绝、两个晚期数据库故障的 11 表全列回滚，以及失败诊断双围栏。另 1 方法使用 `export_projection_dataset_contracts.py` 的新匿名双库黄金，对照三轮发布的 7 表全部列；保留草稿/合计/退款/转账/投资资金/人工值，随机 ID 按真实归属归一并另验 UUID、稳定身份，时钟逐轮推进微秒。PG NUMERIC 测试读数使用 `getBigDecimal().toPlainString()` 避免驱动文本/二进制零表示变化；不放宽金额表示或摘要比较。生成器实际命令/目录见 `projection-datasets-oracle-commands.json`，日志为 `projection-datasets-oracle-<sqlite/postgres>-v3.log`。

7 项全量服务原生方法验证初次/重复构建、原来源仅完整校验一次、源变化围栏、实际非法关系、原活动集与独立安全诊断、迟到诊断、诊断失败不替换原 `projection.invalid_relation`、未知错误脱敏、存储失败直退，以及 caller-owned 初始化的提交/回滚和 ready 幂等。工作区创建已复用同一管线；既有认证/工作区黄金及故障回归同次通过。当前完整双库 `:backend:test :backend:installDist :shared:testAndroidHostTest` 各 374 后端/25 Host，0 failure/error/skip，见 `cash-transfer-write-all-<sqlite/postgres>.log` 和对应 results JSON。原数据集文件在显式参考环境运行 9 passed、0 skip、9 项既有 SQLAlchemy 警告，4.83 秒，`projection-dataset-python-reference.log`，basetemp 为 `pytest-projection-dataset-reference-v2`。本片计入 17 源定义/21 展开用例；接口仍 38/86，无共享/平台/UI 行为变化，不追加浏览器或设备验证。

管理员重建当前证据：`projection-rebuild-red.log` 先确认缺实现；5 项原生方法覆盖 admin 成功、editor/viewer 拒绝、同码领域 409/仓库 503、初始/事务存储故障、未知异常、定位后与返回前撤权的完整回滚。PG 四模式通过 `pg_blocking_pids` 确认真实工作区锁等待后，再撤权、换区、过期或退出；拒绝后财务表与构建诊断均不变。SQLite 验证定位后相同变化，不冒充 PG 并发。另 1 方法通过真实 loopback Netty 每库 17 个 HTTP，完整比对 Python 响应、7 表全部列并核实来源表无写入；包括六类存储错误的原字面 code/固定非识别摘要。`projection-rebuild-storage-red.xml` 先复现错误 code 缩短，修复后通过。黄金生成器显式挂载 operations_service 并断言每例预期状态；准确命令、URL 和目录见 `projection-rebuild-oracle-commands.json`，输出为 `projection-rebuild-oracle-<sqlite/postgres>-v2.log`。完整双库当前各 374 后端/25 Host、installDist 通过，无 failure/error/skip，命令/环境/耗时见 `cash-transfer-write-all-<sqlite/postgres>-results.json`。原 admin-only 组合定义还含 sync 断言，继续 pending；累计仍 227/568，接口 38/86。无共享或 UI 改动，不追加浏览器/设备验证。


增量维护当前证据：12 项原生方法和七步全列 Python 对照在 SQLite/真实 PostgreSQL 通过。原生测试覆盖受影响闭包拆组、独立项/相同组/无关行的实际身份、差量计数、草稿关注、状态唯一身份、未 ready 分支、跨上下文/事务拒绝、版本溢出及实际晚期 SQL 故障；完整来源、状态、收支、成员、关系和派生表逐列回滚。保存点失败先行见 `projection-incremental-savepoint-red.xml`、`projection-incremental-savepoint-lifecycle-red.xml`，修复后预刷新保存点之前的脏值、回滚重载原对象身份，事务结束清除监听。`projection-incremental-state-identity-red.xml` 复现旧状态引用，`projection-incremental-json-column-red.xml` 复现 SQLite 测试快照将 JSON affinity 误作数值；测试按声明列类型保留精确 NUMERIC。完整双库各 374 后端/25 Host、installDist 通过，0 failure/error/skip，准确命令、环境、耗时见 `cash-transfer-write-all-<sqlite/postgres>-results.json`。七步黄金是补充对照，不增加源定义；原 current_references 的四条断言和双库参数经独立只读复核，由 native 独立覆盖，计 1 定义/2 cases，累计 227/568。独立复核无剩余阻断，未代跑构建。无新增 HTTP、共享生产代码或 UI，页面数 10 不变，不追加浏览器/设备验证。公开现金写入接线及原完整性能仍未完成。

分类独立移动当前证据：`category-projection-red.xml` 先复现投影未刷新及未触发晚期溢出；5 项原生方法在双库通过，覆盖来源/人工分类、无关整行及来源保留、无使用不推进、未 ready 只维护稳定收支、已删除来源排除、update 既有边界及晚期溢出导致目录和财务全列回滚。独立只读复核未发现生产 finding；合并组非根分类与大于 400 个子树分类的专门向量作为后续图/性能补充，不冒充本片已执行。最新完整双库各 374 后端/25 Host、installDist 通过，0 failure/error/skip，精确命令、显式环境、耗时见 `cash-transfer-write-all-<sqlite/postgres>-results.json`，完整日志为 `cash-transfer-write-all-<sqlite/postgres>.log`。本片不增加原定义或 HTTP 数，累计仍 227/568、38/86。无共享生产代码、UI 或新平台入口，页面数 10 不变，不追加浏览器/设备验证。

持久化图读取当前证据：7 个原定义的全部断言与 14 个双库参数经独立只读复核，由 `CashProjectionGraphReadMigrationTest` 独立覆盖：双账户真实等额退款分配每次重建相加、非 canonical 部分菱形的全量/局部金额、单种子拆旧组的 5 成员、经新邻居扩展旧组的 7 成员、实际组成项超额拒绝保留活动集、80 节点交替闭包的原 `12*n` 参数预算，以及拒绝错误类型后原字段保留。查询观测 P3 已修复；`projection-graph-observer-red.xml/log` 用重复执行同一 PreparedStatement 证明旧准备次数计数会漏，新观察器按实际执行绑定参数统计，且拒绝零观测。该批生产行为已由前述失败先行切片实现，本片补迁原测试，没有虚构缺实现失败。最新完整双库各 374 后端/25 Host、installDist 通过，0 failure/error/skip，命令/环境/耗时见 `cash-transfer-write-all-<sqlite/postgres>-results.json`。完整原 Python 文件在专用参考环境 28 passed、0 skip、34 项既有 SQLAlchemy 警告、15.00 秒，实际命令、环境及输出见 `projection-graph-python-reference.json/log`。累计 215 定义/533 源参数，HTTP 38/86；黄金不另计数。无共享生产代码、UI 或平台入口变化，不追加浏览器/设备验证。

余额快照当前证据：`cash-snapshot-red.log` 先确认缺实现，13 个原生方法完成 SQLite/真实 PG 验证；覆盖无快照不写、稳定账户 ID/当前名称及未知键、稀疏 JSON、crypto/security、save 复制及版本、精度 80 与全部有效位数、负零/尾零、草稿/删除/导入转账排除、manual 保留、批量时间、外层/保存点回滚。独立复核 P2 经实际失败回归关闭：`cash-snapshot-zero-red.xml` 为极大指数零 fixed 格式，`cash-snapshot-shapes-id-red.xml` 为非法集合及合法负 ID，`cash-snapshot-account-type-red.xml` / `cash-snapshot-type-default-red.xml` 为先校验真实字符串类型再处理缺省。账户 type 来自非空 String 列；Kotlin 内部边界拒绝无法由有效账户模型生成的非字符串，不转成余额桶，也不宣称与 Python Bool/整数动态键合并兼容。missing/null/空字符串保持缺省；实际 API 资格及禁止伪造内部字段随写入接口验收。三个源定义、12 个源参数由独立原生方法逐断言覆盖；statement_delta 在原代码仅是共享函数的 import alias，因此直接调用同一组成项增量函数可迁其纯测试，不代表导入完成。原参考三组测试 12 passed、0 skip，6.63 秒，实际命令、环境及输出为 `cash-snapshot-python-reference.json/log`。最新完整双库各 374 后端/25 Host、installDist 通过，0 failure/error/skip，命令/环境/耗时见 `cash-transfer-write-all-<sqlite/postgres>-results.json`；无剩余审查 finding。累计 227 定义/568 源参数，HTTP 38/86。余额已由手工新建实际消费；其余现金写入仍待迁移。

手工新建当前证据：严格协议 7 项、时间 3 项、真实写入/回滚 4 项、原边界及输入专项 10 项、权限 3 项及完整 HTTP 对照 1 项通过。Python 协议 308、仓库时间 10,033、原 policy 时间 10,020 个向量保留编码来源；两库各 52 个 HTTP 场景逐响应及 10 张财务表全列对照。包含 editor、私有字段/重复键/非有限数/编码/深度/字节/64 部分限制、精确分摊、缺省与明确清空、行修订、失败后序列、Unicode、NUL、巨指数零、超 Long ID 及全部财务行回滚。PG 以 `pg_blocking_pids/Lock` 确认等待后，分别撤权、换区和登出；SQLite 验证 middleware 后、BEGIN 前变化；无财务写入。

当前完整 SQLite/PG 各 374 后端、25 Host、installDist，0 failure/error/skip；命令/环境/耗时见 `cash-transfer-write-all-<sqlite/postgres>-results.json`，输出见同前缀 log。SQLite 命令另含 business-domain JVM/JS/Wasm 各 9 项及 shared iOS arm64/simulator、JS/Wasm、Web 编译；156 tasks 通过。实际 Compose 创建请求保留金额字符串和请求体，成功解析及错误 code/status、不自动重试均通过；原客户端以 code 映射错误，不新增消息传播行为。真实 Netty 在 127.0.0.1:18346/18347 各 15 请求通过，脚本/命令/匿名专库/结果见 `manual-cash-live-<sqlite/postgres>.json/log`，两个服务均已退出。

原 Python `tests/contract/test_cash_manual_create_boundary.py` 在上述显式 PostgreSQL 参考环境 16 passed、0 skip、5 项既有警告、8.15 秒；命令及结果见 `manual-cash-python-reference.json/log`。6 个原定义、14 个展开参数经独立复核迁移；OpenAPI 的 1 定义/2 参数继续 pending。当前合计 227/2612 定义、568/5840 参数及 HTTP 38/86；不把黄金或 Python 执行计作 Kotlin 原断言迁移。14 生成器检查含 `manual-cash-time-golden-check.log`、`manual-cash-text-golden-check.log` 和 `manual-cash-protocol-golden-check.log`，其余未变输入沿用原证据。

Cross-platform Impact Check：共享 LedgerDecimal 仅增加可选失败原因回调，既有 Compose 调用保持原行为；Web/Android/iOS 仍共用原创建和输入流程，客户端实际回包及受影响编译已验证。没有 UI、文案或导航修改，不追加浏览器/设备交互；不宣称这些验证通过。当前 217 文件、31 Python 文件及 187 相对链接/锚点通过静态检查，见 `cash-transfer-write-static-check.json/links.json`。

转账当前证据：`CashTransferRulesTest` 6 项、真实写入/回滚 5 项、原 editor 响应断言 1 项、当前权限 3 项及 HTTP 全列对照 1 项均通过；275 事件、181 快照差分覆盖符号/尾零/巨指数零、Unicode final-sigma、所有现金/贷款/借款/证券/加密组合及原范围错误。SQLite 与真实 PG 各 92 个 HTTP 场景逐响应及 13 张财务表全列比较，保持失败后序列及后续成功写入。原生迁移新增 3 定义/9 参数，精确资金调拨成功结果、份额和成本均独立断言；多币种新增/校准组合原定义仍 pending。

转账完整回归 `cash-transfer-write-all-<sqlite/postgres>-results.json/log` 为每库 374 后端、25 Host、installDist，SQLite 同命令含 JVM/JS/Wasm 各 9 项和受影响 iOS/JS/Wasm/Web 编译，0 failure/error/skip。最后补齐源成功断言后，受影响的 16 后端和 3 Host 在两库重跑均通过，见 `cash-transfer-write-scoped-<sqlite/postgres>-results.json/log`；生产实现未改变。原 Python 参考上述显式双库环境运行 `tests/contract/test_cash_transfer_http_response.py`、`tests/test_postgres_adapter.py::test_transfer_rejects_non_positive_amounts`、`::test_cross_currency_cash_to_security_transfer_preserves_exact_projection_values` 及多币种文件两个转账定义：11 passed、0 skip、10 项既有弃用警告、7.42 秒，命令见 `cash-transfer-python-reference.json`，输出见同名 log。14 个生成器 `--check` 最新全部通过，见 `cash-transfer-generator-checks.log`。

真实 Netty 转账验证由证据目录 `run_live_cash_transfer.py <sqlite/postgres>` 启动当前发行包，`verify_live_cash_transfer.py` 在 `127.0.0.1:18348/18349` 各走 16 个流程请求，覆盖实际建工作区/账户、同币种警告、精确现金/投资双侧、无效金额/跨币种/账户/代理/内部量化的 13 表无写入、投影及删除后会话还原。源数据库分别是旧任务专库 `transfer-target-v2_test.db` / `finance_kotlin_transfer_v1_test`，与当前 92 场景验收库分离；精确命令/环境/耗时和原始 trace 为 `cash-transfer-live-<sqlite/postgres>.json/log`。任务进程均 finally 退出，无浏览器或真实数据操作。Web/Native 共用既有 `createTransfer`，本片只新增实际响应集成测试，不新增页面或更改表单；相称编译已覆盖。

## 当前审查

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
