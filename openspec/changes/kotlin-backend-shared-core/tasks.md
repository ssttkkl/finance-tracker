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

- [x] 4.0.1 jOOQ 基础：可重现的官方 schema 代码生成、仅后端依赖、SQLite 文本/PG NUMERIC 金额、UTC/JSON 绑定及安全异常映射；失败先行并通过双库事务/保存点合同。（独立复核 P2 已关闭；残余 P3 已登记）
- [x] 4.0.2 jOOQ 垂直验证：迁移当前收支详情/定位与完整转账的查询写入调用链，保留当前权限、财务快照、金额表示和原子回滚；SQLite/PG18 功能及实际客户端回包验证。（独立复核确认持久化基础设施）
- [x] 4.0.3 jOOQ 全量替换：迁移其余已实现仓库的业务 SQL，复核生成类型边界，运行全部已实现功能回归、安全检查、相称构建和独立审查；性能按用户要求排除。（独立复核确认；两库完整功能与 codegenTest 通过）
- [x] 4.1 首片失败先行：匿名 Python 黄金、公共规则/DTO 差分、Ktor 旧会话/分类读取的双库 HTTP 与权限/过期/跨工作区/错误测试。
- [x] 4.2 新增无 UI 的 KMP contracts/domain，实际接入 Compose 的认证 DTO、分类上级规则和精确分摊，移除对应双实现；实现首片 Ktor/JDBC。
- [ ] 4.3 完整认证：注册/登录/登出、工作区创建/选择/重命名/删除、邀请预览/接受、成员/最后管理员，保持旧 Argon2/会话及全部错误合同；注册/登录按用户明确授权仅接受字符串凭据，补非字符串 400 与无写入反例。
- [x] 4.3.1 注册、登录、登出及旧 Argon2 双向兼容；20 组非字符串字段无写入（含旧强转可接受的长数字及含邮箱文本的数组/对象）、用户/会话结果、重复注册竞争和失败回滚。
- [x] 4.3.2 工作区、邀请与成员的 11 个 HTTP 接口实现并通过 53 组匿名双库响应及状态对照；首个空账本初始化在同一事务发布。
- [x] 4.3.3 并发自降只保留一名管理员、同邀请一次消费、到期边界、已有成员不降权、初始化与删除晚期失败回滚、并发删除备选指针保护。
- [x] 4.3.4 完整财务数据删除、锁等待期间换区/撤权/登出及原认证性能矩阵；这些未验收项不因 4.3.1–4.3.3 通过而关闭。（财务删除双库覆盖；PG 实锁等待期间换区/撤权/登出均拒绝且不删除；性能按用户明确口径排除）
- [ ] 4.4 完整账户命令/查询：资格/币种/余额/别名、修订、引用、snapshot 与永久幂等收据。
- [x] 4.4.1 稳定 ID 创建/查询/修改/删除及现金/投资列表，共享实际读写 DTO；56 组 HTTP/摘要/全部持久状态、原生 editor/CAS/原键/并发/失败回滚、坏快照/引用/撤权等待和实际 Compose 解码双库验收。
- [ ] 4.4.2 旧写入适配/所有币种扩展的修订、底层 CAS、历史迁移/序列与 OpenAPI；账户余额/估值查询、别名及相关剩余接口随领域切片迁移。不能以 4.4.1 代替完整 4.4。
- [x] 4.5 完整分类写入：名称规范化、目录修订、锁、深度/循环/子树、移动/重排、删除影响及 entry/事实/投影、单项和批量分类。（4.5.1–4.5.4 全部完成并独立复核；原性能定义保持排除）
- [x] 4.5.1 共享目录 wire 类型并修正 Compose 名称数组/对象路径差异，以实际后端响应验证客户端解码和领域映射；先失败测试，保留原 API 字段。
- [x] 4.5.2 将分类上级/循环/深度规则接入真实服务端命令，与 Compose 使用同一入口，迁移相应 Python 参数和断言；财务引用、投影及批量分类另验，不能局部关闭 4.5。
- [x] 4.5.3 分类删除检查和原子删除：完整迁移 entry/独立恢复值/含已删除事实的人工空分类保护、候选依赖失效和全部数据集清理；双库原生回归、原断言迁移、性能与实际客户端解码验证后计入接口。
- [x] 4.5.4 单项/批量分类 PUT：当前数据集/版本、维护可见性、完整 entry/成员、同值人工保护、单成员恢复值、投影/来源版本原子更新；原事实保持独立，迁移原参数/断言并验证双库与实际客户端。
- [ ] 4.6 公共完整精确金额/币种/时间/类型/分摊规则，按 Python 边界及指定舍入黄金，逐步替换前端纯规则重复实现。
- [x] 4.6.1 金额合法范围与已知现金部分一致性提供类型化和保留 JSON 类型的公共入口；固定/随机 Python 黄金在 JVM、JS、Wasm 通过，补齐 Unicode、零与极值指数、组成项引用和 ordinal 反例。
- [ ] 4.7 现金新增/草稿/手工转账/删除/完整组/公共编辑、当前查询/证据、余额及原子投影。
- [x] 4.7.1 迁移现金字段/转正式纯规则及对应原生单测；动态语言边界剩余 2 项已按 JVM 类型边界适配并复核。
- [x] 4.7.1.1 现金字段/转正式规则 26 个原定义、59 个展开用例已逐断言迁入独立 JVM 测试并经复核；调用已有公共现金部分校验。
- [x] 4.7.1.2 动态字符串子类属性/键的 2 项原断言按语言类型边界适配并独立复核，不以黄金替代。（JVM String final/不可变；原生断言固定容器深拷贝、共享引用与按值复制，清单标 retained）
- [x] 4.7.2 将规则接入实际现金写入、当前账户/修订/来源及完整组校验，双库验证余额/entry/投影/收据原子发布后才计接口进度。（4.7.2.1–4.7.2.7 全部完成；OpenAPI 组合断言仍 pending 并已在子项登记）
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
- [x] 4.7.9 迁移当前收支详情及按流水定位的两个 GET：同一数据库快照中的活动数据集、版本、成员、金额及安全待处理事项；保留隐藏/可维护、已删除/跨工作区/缺失成员错误。真实双库 HTTP、重建交错、当前权限重验及现有 Compose 成员消费验证，原组合测试未覆盖部分保持 pending。（独立复核无 P1/P2；P3 已登记）
- [x] 4.7.10 迁移收支证据 GET：同一快照返回根记录、完整成员/现金部分、已确认关系、非活动关系提示、退款时间线及白名单资金调拨信息；保留隐藏记录、精确金额、字符串标识、来源信息省略及原错误。先以 Python 匿名全响应和 Kotlin 原断言失败复现，再验证 SQLite/PG18、重建交错、当前授权和现有 Compose 消费；不扩大为账单原文或关系扫描接口。（独立复核无 P1/P2；P3 已登记）
- [ ] 4.7.11 当前单条流水详情：按创建方式和当前角色返回字段、公共人工值、来源事项、候选、完整关联及可空投影位置；接通既有来源原文消费者，不新增编辑或导入入口。
- [x] 4.7.11.1 先迁来源事项只读选择规则：缺省/明确清空、固定字段、受保护字段、现金部分引用、多个来源冲突及修订；失败先行、原生断言和 Python 差分。该阶段不写流水或推进已验收接口数。（独立复核无 P1/P2；P3 生成器分支缺口已登记）
- [x] 4.7.11.2 迁移详情的 jOOQ 读取、当前来源可选动作及身份候选；授权与财务快照分别核验，隐藏/未初始化投影按原单条读取合同处理，损坏状态停止读取且不修复。（独立复核 P1 关闭、P3 无回归）
- [x] 4.7.11.2.1 迁移完整只读准备依赖的来源正文效果：原文备注、各来源当前单行解释、可信身份保留、显式修改及人工保护；原生断言和 Python 完整差分后提交推送，不计 HTTP 完成。（独立复核无 P1/P2；P3-1 已修复，P3-2/P3-3 登记）
- [x] 4.7.11.3 接入 GET、原安全诊断和现有 Compose 来源读取链路，完成双库完整响应/无写入、授权变化、源断言核对、实际客户端和功能回归后提交推送；独立复核单独登记，不将自检冒充独立结论。（独立复核确认客户端消费者合同；残余仅为 happy-path 证据 mock 与不可产生的假值损坏状态）
- [ ] 4.8 维护计划/上下文/效果/确认/收据、CAS/TTL、当前事实证明、冲突及未知结果原键核实。
- [ ] 4.8.3 完整维护提交重验：按 Python `_validate_current` 在写事务重建计划并核对冻结操作、当前关系/事项/账户/修订及原错误合同，不能仅凭父流水和 entry 修订提交；独立复核指出的关系/事项变化缺口须失败先行，未完成前 4.8 不可关闭。
- [x] 4.8.3.1 先补已接入单流水动作的提交重建与原 operation fingerprint、当前关系上下文/事项/账户资格依赖核对；失败不写财务、不消费方案，无关账户改名不使正式备注失效。完整关系/多成员操作和原恢复组合定义随后迁移。
- [x] 4.8.1 先迁永久收据按当前操作者/工作区读取、原键查询和行/关系结果分页的 5 个 GET；共享现有 Compose 收据协议，验证历史键严格解码、分页、脱敏、权限、当前投影引用及真实客户端解码。resolve/提交/暂存写入随后验收。
- [x] 4.8.2 迁移永久收据仓库 create/replay/for_context 及写入前摘要/结果白名单，保留原键/单上下文永久唯一、冲突携原收据、业务写入同事务回滚；原生迁移原参数/断言并接入现有账户命令，完整双库回归后验收。历史 Alembic 升降级和 retry/resolve 不因仓库完成而关闭。
- [x] 4.9.3 迁移建行借记卡 XLS 与工银亚洲 UTF-16 TSV 银行表格读取：OLE/UTF-16 格式探测、表头/元数据/重复列、行形状、账户掩码、币种、声明笔数/日期/序号覆盖与日期精度；11 组 Python 完整文档/错误差分通过；工行 PDF 待后续切片。
- [x] 4.9.2 迁移 XLSX 工作簿原始账单读取：ZIP 成员/解压预算/唯一名/仅 XML+rels、工作表行与坐标预检（含 DTD/实体拒绝）、活动工作表全行读取、尾部空列补齐与原有声明/覆盖规则；与 CSV/TSV 合计 19 组 Python 文档/错误差分通过。
- [x] 4.9.4 迁移工行 ICBC 信用卡/借记卡 PDF 原始文档读取：PDFBox 文字坐标与表格线解码、页/字符/表/行/列预算、格子表单元格构造、品牌与版本表头、行原文/物理位置、卡号归属、起止日期/笔数/页序/筛选/导出时间覆盖、页指纹与文档键及 `startxref … %%EOF` 完整性；先以 Python `read_icbc_document` 完整文档/错误黄金对照，再接入现有文档读取边界。密码错误保留独立类型，行归一、上传流程和真实样本仍待后续切片。
- [x] 4.9.1 迁移平台 CSV/TSV 原始账单读取：utf-8-sig/gb18030、50 行表头探测、列重复/数量、行形状、声明笔数/账户/昵称/导出类型/时间范围、完整覆盖证据及行/字节预算；11 组 Python 完整文档/错误差分通过；XLSX/XLS/PDF 与上传流程待后续切片。
- [x] 4.9.6 迁移平台 CSV/TSV/XLSX 行归一：支付宝/微信方向、跳过、金额、时间、对手方、备注、订单号、来源解释、分类/子类型与覆盖可用性；先以 Python `normalize_platform_document` 完整 `ParsedCashRow`/`ParsedCashDocument` 黄金对照，再接上传流程。
- [x] 4.9.5 迁移来源行归一共用的记录类型/子类型与对方账号规则：`classify_cash_record` 的冲正/退款/还款/转账/提现/投资/利息/费用/收支分支、来源方向判定、子类型默认与校验，以及掩码/尾号/完整号/工银亚洲可证明重建；先以 Python 匿名行组合黄金对照，再接入平台/银行/PDF 行归一。
- [x] 4.9.7 迁移建行 XLS 与工银亚洲 TSV 行归一：卡号/尾号显示名、交易地点/对方户名对手方、支付源、退款信号、币种、日期时间、收入/支出金额、account_opening 跳过、来源解释与覆盖包含；先以 Python `normalize_bank_document` 完整行/文档黄金对照。
- [x] 4.9.8 迁移工行信用卡/借记卡 PDF 行归一：收支方向/金额、币种、时间、对手方/账号、卡号归属与显示名、摘要噪声子序列、基金清算户、退款/冲正/offset、渠道、声明范围包含与 coverage 键清理；先以 Python `normalize_icbc_document` 完整行/文档黄金对照。
- [x] 4.9.9 统一读取+归一入口：按字节/来源分派平台、银行、PDF 文档读取并调用对应行归一；保留空输入、未知来源、密码失败和原始文档错误。`CashStatementDispatchTest` 用现有 PDF/平台/银行金标准验证三种格式分派及未知来源拒绝。
- [ ] 4.9 服务端 CSV/TSV、XLSX/XLS/PDF 逐格式解码与归一、文件预算/密码/完整声明/匿名样本及原测试迁移；保持现有上传流程，公共层先共享行模型和协议，不新增 statement-core 或本地解析入口。
- [x] 4.10.1 迁移来源身份纯规则：provider 摘要、文件/出现/文档/页绑定键、声明覆盖可比、来源范围（微信默认/命名 NFC、控制字符/长度）及多页完整性/当前覆盖派生；先以 Python 匿名键与完整性黄金对照，不接暂存或写入。
- [x] 4.10.2 迁移已解析文件到来源出现组合：跳过行、resolved 字段覆盖、subset 覆盖降级、微信 scope label、文件/文档证据与页完整性合并、文件元数据及重复 file_ref；先以 Python 匿名批次黄金对照，不查询账本或暂存。
- [x] 4.10.3 迁移来源账户扫描纯规则：支付宝支付组件/分摊草稿、微信零钱身份、各来源账户身份/掩码/币种、composite 问题行、legacy 键、稳定 group_id 与分组顺序；先以 Python 匿名行组合黄金对照，不查询账户或保存映射。
- [x] 4.10.4 迁移来源对应解析纯规则：当前索引/兼容/位置冲突、收据绑定证明、单文件一对一消耗、批内重复、守恒与 pending/conflict/new、稳定新增 ID；先以 Python 匿名 occurrences/records 黄金对照，不写账本。
- [x] 4.10.5 迁移多文件来源批次规划：重复出现、最大次数、可靠身份快照选择、证据传递、共同结果组、冲突隔离及输入顺序；迁移原功能断言并对照完整 Python 结果，保留空页证据。纯规则不写数据库，不计新增 HTTP；性能按已确认范围排除。
- [ ] 4.10.6 迁移解析会话存储及清扫：完整文件编解码与预算、用户/工作区隔离、原 TTL、条件写入/修订、取消清空、计划快照和公共页缓存、内存与 R2 适配；分别验证原功能断言与错误不泄露，接线后再计运行时完成。
- [x] 4.10.6.1 先完成解析文件及会话 JSON 编解码、原字段校验和深度/字节预算；保留旧缺省字段、空字符串来源解释、拒绝非法嵌套输入，不保存原始文件或密码。
- [x] 4.10.6.2 迁移后台清扫器：启动时首扫失败阻止启动，分页连续推进，运行失败仅输出稳定健康代码并保留游标，停止信号传入在途清扫且等待结束；显式配置内存/R2、TTL、清扫间隔与页大小，真实客户端及运行时另行接线。
- [x] 4.10.6.3 使用服务器专属 AWS S3 SDK 接入真实对象协议与显式配置，核对签名请求/条件头/私有元数据、单次未知写入、安全错误和资源关闭；以本机 HTTP 服务验收，不写真实 R2。运行时接入独立验证。
- [x] 4.10.6.4 维护计划接入共享解析存储：按 mutation 类型及精确上下文读取，保留原 TTL/CAS/预算和响应隔离；所有读取先核对永久消费，提交成功后再清空临时正文，清空失败不改变已提交收据。双库 HTTP 及双对象端口验证后替代旧进程 Map。
- [ ] 4.10.7 来源账户对应持久化：按来源组完整身份有界查询，复用已确认对应、窄范围旧对应及精确账户别名；建议只读并核对当前账户资格。成功使用证明由调用方在财务事务内发布，同值不推进修订、矛盾输入停止整操作；先迁内部仓库与建议，导入实际成功筛选随后接入。
- [x] 4.10.7.1 内部来源对应仓库和只读建议：200 键批次、精确别名、资格与旧对应边界、成功证明事务发布、同值幂等和晚期回滚；双库完整功能回归与独立复核后验收，原导入组合定义仍保持 pending。
- [x] 4.11.1 迁移导入资金组扫描与账户选择纯准备：保留各来源完整身份/文件局部身份、支付宝明确金额与补贴排除、按当前账户合并现金部分、缺项/矛盾/精度和原输入错误；先 Python 完整对照及原生失败回归，不计上传或导入提交完成。
- [x] 4.11.2 迁移上传文件准备与选择失效纯层：严格文件命令、单次有界读取、密码及文件名脱敏、来源范围继承、已知格式/预算错误和解析文件校验；文件变化清除旧计划及过时选择并保留关系选择证明。先失败回归及 Python 完整结果对照，再接上传工作区事务，不计接口完成。
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

上传文件准备纯层由两轮独立只读上下文复核，范围覆盖文件名脱敏、单次上限、密码/格式/预算结果映射、微信范围继承、替换失败重试、修订 CAS、聚合预算降级、选择失效与关系证明校验；对照 Python 同名函数逐分支核对，并确认会话只保存摘要/大小/脱敏文件名，不含正文或密码，且无财务或数据库写入。第一轮给出无 P1/P2 并列出四条边界观察，均由现有黄金/测试覆盖；第二轮针对无效显式范围、空/超限正文、预算降级、非字符串 row_ref 与证明重复 ID/代数错配做对抗复核，结论为无来源可证 P1/P2。未迁移的授权、永久收据、工作区事务与 multipart 是已登记的后续切片，不据此关闭完整导入。审查者只读，未代跑构建或双库回归。

## 当前证据

2026-10-30 UTC；来源正文效果阶段提交前验证的 HEAD 与 diff 比较基线为 `a460ac21d30e4840b1bb2cd18115c60077fcbca2`；来源选择规则阶段提交前验证的 HEAD 与 diff 比较基线为 `394b23e5553b18a980e37f92d59d6146289cc37b`，jOOQ/证据读取的提交前验证 HEAD 为 `779b148ca12fc3f7515bfaedf032c4ebeb882835`；详情读取阶段提交前验证 HEAD 为 `37f290a2949c869e87795ad2406d0c8b63d04ea8`；下表按各证据标识区分，迁移固定基线仍为 `0c038a75e34264bd6d4aa5cce2070727df9d9ff7`。工作区 `/Users/huangwenlong/.paseo/worktrees/1zalea72/relieved-duck`，分支 `continue-kotlin-backend-refactor`。当前任务 69/87；已验收 HTTP 44/86，当前单条流水详情双库合同、独立复核与现有 Compose 来源消费已完成。jOOQ 持久化独立复核 P2 已关闭，4.0.1–4.0.3、4.5、4.7.1、4.7.2 已勾选。jOOQ 功能迁移与功能验证已完成，但独立复核未完成，4.0.1–4.0.3、4.7.9–4.7.10 保持未勾选。4.9.4 ICBC PDF 原始文档读取已通过 38 组金标准、预算测试、静态检查和两轮独立复核，无 P1/P2；残余 P3 已登记。4.9.5–4.9.8 行归一、4.10.1 来源身份、4.10.2 已解析文件组合、4.10.3 来源账户扫描与 4.10.4 来源对应解析已完成独立复核并修复阻断项；多文件来源批次规划已完成；映射持久化、暂存与上传接线仍待后续切片。用户明确要求先提交并推送当前进度，后续每个实施阶段完成时提交并推送；授权限于本仓库 `continue-kotlin-backend-refactor` 推送至 `origin/codex/kotlin-backend-shared-core`，不包含 PR、部署、切流或真实数据操作。当前提交保存已验证进度，不代表全量迁移或独立审查完成。

已执行的 grill-me → grilling、explore、propose、jOOQ 架构确认和 apply 继续适用。用户明确要求本轮不运行性能测试，以功能迁移和功能测试为验收；功能回归使用 `-PbackendFunctionalOnly=true`，仅排除 `CategoryDeletionPerformanceTest`、`CashClassificationPerformanceTest`。原预算不变，原分类删除失败 `legacy-all-sqlite.log`（973 毫秒）与 `legacy-review-sqlite.log`（561 毫秒）保留，不宣称性能通过。

工具、匿名夹具和证据根为 `/home/vscode/.cache/finance-kotlin-backend-20261029`（下文证据文件相对于 `evidence/`）。OpenSpec 1.15.0、Node 24.22.2、Python 3.11.18、pytest 9.2.0、Gradle 9.5.1、JDK 21.0.13；Android SDK 位于 `tooling/android-sdk`，compileSdk 37。jOOQ OSS 3.21.9 的 67 个官方生成文件通过真实 Gradle 重新生成并逐文件相等，生成结果仅留后端。

SQLite 使用 `oracle-sqlite`、`matrix-sqlite/<能力>`、`legacy-sqlite-v2`、`current-projection-sqlite-v6`、`evidence-sqlite-v3`。执行者显式准备 PostgreSQL 18.6 于 `127.0.0.1:55432`；用户 `postgres`，每个能力使用独立 `_test` 目标和参考库，变量写于 `matrix-postgres-env.json`，基础为 `ft_kr_jooq_schema_test`，证据接口为 `ft_kr_evidence_test` / `ft_kr_evidence_oracle_test`。原 PostgreSQL 16 服务保留。Python 参考库为 `ft_kr_jooq_reference_test`，通过 `FT_TEST_POSTGRES_URL` 显式提供。测试不探测、启动或自动重置数据库。

Gradle 经缓存根 `run_gradle.py <sqlite|postgres> <证据名> <任务>` 从 `compose/` 执行，固定任务 JDK、Android SDK、缓存和完整能力变量；公共参数为 `--daemon --no-configuration-cache --max-workers=1 --project-cache-dir <缓存根>/gradle-project -Dorg.gradle.jvmargs=-Xmx2048m -Pkotlin.compiler.execution.strategy=in-process`。每次结果 JSON 保存实际命令、变量、HEAD、UTC 起点及耗时，JUnit XML 复制到同名目录。只把实际执行的任务计入本轮结果；UP-TO-DATE 不算重跑。

| 来源账户对应内部仓库与建议 | 2026-10-30 04:20 UTC，HEAD `a2406e5` 工作树，比较基线 `a2406e5`。阶段 1–3 对照 Python `cash_funding_mappings`、`cash_import_account_mappings`、现有数据库与来源合同，design/tasks 先记录内部边界；阶段 4 缺入口失败 `/tmp/ft-funding-mapping-red.log`，8 项原生方法涵盖同值/更换/矛盾、工作区/资格、完整唯一别名、窄旧对应与失效不回退、405 键批次、溢出无污染及实际晚期外键失败。阶段 5 独立辅助工具复核生产及原生测试，无代码阻断；五项测试缺口已补重审 `/tmp/ft-funding-review-final.txt`，SQL 观察器另审 `/tmp/ft-funding-sql-review.txt` 无阻断。审查输出中批次数文字解释有误，实际三列键 200/200/5 加工作区为 601/601/16 参数，二列别名为 401/401/11；以可执行断言为证据，非其文字计算。阶段 6 在 `compose/` 执行 `./gradlew :backend:test :backend:installDist :backend:ktlintCheck :backend:detektMain :backend:detektTest -PbackendFunctionalOnly=true --console=plain` SQLite 完整 582 项中 581 passed、1 PG 专属锁 skip；PG18 执行 test/installDist 完整 582 passed、0 skip，见 `/tmp/ft-funding-full-{sqlite,pg}.log` 与仓库外 XML。Python 显式专用 `_test`/`FT_TEST_POSTGRES_URL` 来源对应对照 58 passed、0 skip，`/tmp/ft-funding-python.log`。实际 SQL 核对全部键及每批参数，只读建议无写入，晚失败证明第二次 INSERT 到达且全部回滚。阶段 7 README、inventory、功能地图、词表和技能已按内部能力核对；无 UI/共享协议变化，Web/Native 不适用。回滚撤销内部类，无 schema/生产入口或真实数据变化。阶段 8 沉淀失效对应不回退、200 键批次、caller 事务与组合测试边界。原导入定义含资金扫描/提交未迁，不计源映射完成；4.10.7、4.10、4.11 保持未完成。性能按用户要求排除。 |
| 维护计划共享解析存储 | 2026-10-30 04:15 UTC，HEAD `e94b877` 工作树，比较基线 `e94b877`。阶段 1–3 对照 Python 维护/暂存及公开错误并先回写 design/tasks；阶段 4 失败先行 `/tmp/ft-mutation-staging-red.log`，CAS 回归 `/tmp/ft-mutation-cas-red.log` 证明构建后重读会覆盖并发修订，修复沿已校验快照执行条件替换。阶段 5 独立 Claude 只读上下文完成范围、工程、安全与最终 diff 复核，无 P1/P2，见 `/tmp/ft-mutation-independent-review.txt`；补充独立工具结论见 `/tmp/ft-mutation-short-review.json`。多个代理/CLI 429 不计审查通过。P3 快照隔离测试已补不同实例断言，`/tmp/ft-mutation-detached-final.log` store 测试及 ktlint 通过；保留清理请求发送前失败/清理 CAS 交错及过期 resolve 的专门用例缺口，当前永久收据优先测试覆盖临时读取失败。最终权限复核拒绝不会回滚已提交事实，仍按原键核实；此规则不改。阶段 6 当前实现完整功能回归 PG18 573 passed、0 skip，SQLite 573 项中 572 passed、1 个 PG 专属锁 skip，日志 `/tmp/ft-mutation-full-{pg,sqlite}.log`；构建/ktlint/detektMain/detektTest 通过。后续 CAS 与全部消费读取反例仅改测试，受影响 HTTP/store 两库通过 `/tmp/ft-mutation-cas-{green,pg}.log`；提交反例两库通过 `/tmp/ft-mutation-final-commit{,-pg}.log`，证明真实 COMMIT 故障已到达、16 表原子回滚且临时方案不变，取消 PUT 丢响应时成功收据不变；全部 GET 和重新预览在临时读失败时仍返回已提交。Python 对照另建显式专用 PG `_test` 数据库并设置 `FT_TEST_POSTGRES_URL`，102 passed、0 skip，`/tmp/ft-mutation-python-pg.log`。阶段 7 README、inventory、功能地图、词表与 apply 技能已核对；后端内部接线无 Web/Native/共享合同变动，浏览器/原生编译不适用，性能按用户决定未运行。回滚恢复旧维护存储注入和服务，Python及生产入口未变。阶段 8 沉淀 CAS 快照、永久消费优先与提交后清理规则。原测试定义映射未完成，不据此关闭完整维护或暂存任务。 |
| 解析会话清扫与 R2 SDK 接线 | 2026-10-30 02:52 UTC，HEAD `3eb74db` 工作树，比较基线 `3eb74db`。阶段 1–3 对照 Python cleanup/config 与当前 Ktor 启动顺序，design/tasks 记录后端专用 S3 SDK `2.57.7`、单次尝试及资源生命周期；阶段 4 失败先行见 `/tmp/ft-parsed-cleanup-red.log`、`/tmp/ft-parsed-config-red.log`，HTTP 字节反例纠正 SDK 分块正文。阶段 5 `batch_review` 独立范围/工程/安全复核，停止中断、幂等关闭及关闭掩盖首扫异常已修复，11 源定义映射复核，无阻断。阶段 6 `./gradlew :backend:test :backend:installDist -PbackendFunctionalOnly=true --console=plain` 完整回归：SQLite 570 项中 569 passed、1 个 PG 专属锁场景 skip；PG18 570 passed、0 skip，见 `/tmp/ft-parsed-full-sqlite.log` 和 `/tmp/ft-parsed-full-pg.log`。SQLite 同次 ktlint/detektMain/detektTest 及构建通过。首次 SQLite 4 失败为旧提交夹具已被消费，重新生成仓库外新数据库后完整通过；PG 四组提交夹具同样使用新建专用 `_test` 数据库，原其余能力夹具由外部 env.sh 显式提供。17 项 Python cleanup/config 全通过，`/tmp/ft-parsed-runtime-python.log`；Ktor 测试核对首扫先于监听、成功/失败启动和停止，实际 Netty `127.0.0.1:18567` 返回 401 后正常终止，日志 `/tmp/ft-parsed-netty.log`。真实 SDK 本机 HTTP 测试覆盖签名、条件头、完整正文、metadata/head/list/delete、412/503及已收正文后断连接，均只发一次；未访问真实 R2。两项新增 Maven 包 OSV 版本查询未返回公告（仅这两包，不代表全依赖无漏洞）。阶段 7 已更新 README 配置、功能地图、inventory、词表核对及技能；无 UI/公共客户端改动，Web/Native 不适用。回滚恢复原 Main 与移除本片依赖，原 Python/生产入口未变。阶段 8 记录资源关闭、未知写入和一次性夹具约束。配置 bool 页大小在 Kotlin Int 签名不可表达，该原定义保持 pending；真实 R2 部署、上传/维护应用服务存储接线仍未完成。 |
| 解析会话编解码与存储端口 | 2026-10-30 02:30 UTC，HEAD `224d961` 的工作树；比较基线 `224d961`。阶段 1–3 已对照 Python staging、原测试和现有来源模型并回写 design/tasks；阶段 4 由缺入口的失败测试驱动，见 `/tmp/ft-parsed-codec-red.log`、`/tmp/ft-parsed-store-red.log`。阶段 5 独立 `batch_review` 覆盖范围/工程/安全/源映射，任意精度整数、必填 nullable 字段、快照可变字节、缓存键嵌套类型等发现均已修复重审；无剩余阻断。阶段 6 在 `compose/` 执行 `./gradlew :backend:test --tests '*ParsedImport*' --tests '*CashSource*' --tests '*CashTime*' :backend:installDist :backend:ktlintCheck :backend:detektMain :backend:detektTest -PbackendFunctionalOnly=true --console=plain`：91 项、零 failure/error/skip，构建和静态检查通过，日志 `/tmp/ft-parsed-final.log`；根目录 `PYTHONPATH=src:tests .venv/bin/python -m pytest -q tests/test_parsed_import_staging.py -p no:cacheprovider` 为 165 passed；导出器 `--output compose/backend/src/test/resources/python-parsed-import-codec.json --check`、OpenSpec strict、diff check 通过。18 个原定义及完整参数映射，37 个仍 pending，见 `/tmp/ft-parsed-migration.json`。阶段 7 已核对 README、inventory、功能地图、词表和技能；本片无 SQL/HTTP/UI 变动，双数据库及 Web/Native 验证不适用，性能按用户要求未运行；真实 R2/运行时/上传接线后须补对应验证。回滚为撤销未接线存储实现，无数据迁移。阶段 8 将不可变快照、逐次存储核实与原参数断言要求沉淀入技能；codec 正常输出兼容，损坏对象解析及 filters 类型边界见 design。未声明全存储任务或全重构完成。 |
| 单流水维护提交重验 | 2026-10-30 05:08 UTC，HEAD `245029a` 工作树，比较基线 `245029a`。阶段 1–3 对照 Python `_validate_current`、原指纹白名单、当前事项/账户依赖和收支快照后回写 design/tasks；阶段 4 事项变化缺验失败见 `/tmp/ft-revalidation-red.log`，原存储请求直接公共解析的失败见 `/tmp/ft-revalidation-green.log`；来源阻止转正式、正式处理事项清除与草稿公共人工值分别失败先行，见 `/tmp/ft-revalidation-review-red.log`、`/tmp/ft-revalidation-entry-red.log`。三个 PG 实锁队列测试全部在缺锁实现失败，见 `/tmp/ft-revalidation-lock-red.log`。阶段 5 独立工具复核确认三个写入遗漏及实锁缺口，已修复；最终 `/tmp/ft-revalidation-review-verdict.txt` 无来源可证阻断。前轮关于 NOT IN、自动工作区锁、事务后重验无法回滚和 row_refs 的判断按代码与 Python 白名单拒绝，不采纳臆测；原始审查保存在仓库外。阶段 6 `./gradlew :backend:test :backend:installDist :backend:ktlintCheck :backend:detektMain :backend:detektTest -PbackendFunctionalOnly=true --console=plain`：SQLite 601 项中 600 pass、1 PG 专属 skip；真实 PG18 601 pass、0 skip，日志 `/tmp/ft-revalidation-final2-full-{sqlite,pg}.log`，XML 同名目录，构建和静态检查通过。内部依赖对照核对 Python 20 组构建的账户/事项/收支快照；原恢复组合因完整关系/导入尚未迁移保持 pending。`FT_TEST_POSTGRES_URL` 明确提供专用 `_test`，Python 维护 build/commit/recovery/record 四文件共 174 passed、0 skip，见 `/tmp/ft-revalidation-python-pg.log`；未配置首轮 87 pass/87 skip 不计双库通过。此前 PG 导出缺 env 导致 18 项路径失败，重建明确 PG 夹具后通过，不使用失败结果。阶段 7 核对 backend/root README、功能地图、inventory、词表及 apply skill；本片无 UI/共享客户端改动，Web/Native 编译和冒烟不适用，页面数仍 10，生产入口未切换。回滚为恢复原提交服务及构建器，无 schema 或真实数据变化。阶段 8 将内部请求、冻结依赖和显式锁经验写入 apply skill。完整 4.8、关系/多成员/完整写入迁移仍未完成，性能按用户要求排除。 |
| 导入资金组纯准备 | 2026-10-30 05:06 UTC，提交前 HEAD/比较基线 `5b81d33a050f0bd0c3000ec8cd0ef3edeb1030d4` 工作树。阶段 1–3 对照 Python 资金组扫描、选择、精确加总和复制边界，先回写 design/tasks。阶段 4 缺实现失败 `/tmp/ft-import-funding-red.log`，字段 presence/Unicode 遮掩/范围错误失败 `/tmp/ft-import-funding-boundary-red.log`，精确类型和别名复制/币种形状失败 `/tmp/ft-import-funding-copy-red.log`，银行卡正文读取边界失败 `/tmp/ft-import-funding-bank-red.log`；修复后 13 个原生方法通过，含 115 组完整 Python 结果。阶段 5 独立范围/工程/安全/DX 与最终源码复核，最终 `/tmp/ft-import-funding-verdict.txt` 无来源可证 P1/P2；复制和币种内部错误已修复，未展示扩展方法的推测不采纳。阶段 6 `PYTHONPATH=src:tests .venv/bin/python scripts/export_cash_funding_goldens.py --output compose/backend/src/test/resources/python-cash-funding.json --check`：71 原 Python 参数通过，资源一致。`./gradlew :backend:test :backend:installDist :backend:ktlintCheck :backend:detektMain :backend:detektTest -PbackendFunctionalOnly=true --console=plain`：SQLite 614 项中 613 pass、1 PG 专属 skip；PG18 614 pass、0 skip，最终日志 `/tmp/ft-import-funding-final-{sqlite,pg}.log`，XML 同名目录。两库使用各自仓库外配置及新建专用 `_test` 夹具。OpenSpec strict 和 diff 检查通过。阶段 7 backend README、功能地图、inventory、词表、主规格/delta 与 apply skill 核对；没有新术语/公共协议/UI，Web、Native、共享客户端不受影响，页面数 10 与 F-05 两端 TODO 保留，相称原生编译/浏览器不适用。回滚为撤销本片纯层，无 schema/真实数据/生产切换。阶段 8 沉淀深复制与信用卡正文边界。原资金组测试逐定义/参数/断言映射尚未逐项验收，保持 pending；115 完整结果不替代原测试迁移。上传、业务计划、成功映射发布和提交仍未完成；性能按用户要求排除。 |
| 上传文件准备与选择失效纯层 | 2026-10-30 UTC，提交前 HEAD/比较基线 `7675eef3` 工作树。阶段 1–3 对照 Python `cash_import_context`、`cash_import_choice_context` 与暂存 CAS 后回写 design/tasks，明确纯层不含授权、收据、数据库与 HTTP。阶段 4 先以 `/tmp/ft-context-boundary-red.py` 对文件名脱敏、行选择过滤、微信范围继承和重复建议 ID 四处变异，各目标测试均转红，恢复后转绿；其余生命周期回归先行覆盖。阶段 5 两轮独立只读复核 `/tmp/ft-upload-review.txt`、`/tmp/ft-upload-review2.txt` 均无来源可证 P1/P2；确认密码/正文不持久化、无财务或数据库写入、范围与预算边界与 Python 一致。阶段 6 `export_cash_import_context_goldens.py --output compose/backend/src/test/resources/python-cash-import-context.json --check` 对照 20 个文件名、5 个选择失效和 36 个关系证明用例；`CashImportContextTest` 17 项、`CashImportChoiceContextTest` 4 项通过。完整功能回归 `./gradlew :backend:test -PbackendFunctionalOnly=true --console=plain`：SQLite 635 项中 634 pass、1 PG 专属锁 skip；真实 PostgreSQL 18 635 pass、0 skip，日志 `/tmp/ft-ctx-full-{sqlite,pg}.log` 与 XML 同名目录；两库均重新生成仓库外专用夹具，ktlint/detekt 通过。阶段 7 README、功能地图、inventory、design/tasks 与 apply skill 已核对；没有新用户概念或公共协议，无 UI 变化，Web/Native/共享客户端不受影响，页面数仍 10，F-05 两端 TODO 保留，浏览器与原生编译不适用。回滚为撤销纯层类，无 schema、真实数据或生产入口变化。阶段 8 沉淀显式类型错误边界、计划证明只保留引用和文件变更失效规则。30 个原定义映射为 18 migrated、4 retained、8 pending，见 `/tmp/ft-context-migration.json`；待接工作区事务、权限复核、永久收据、multipart 与完整导入规划/提交；完整 4.11 仍不勾选。性能按用户要求排除。 |
| 验证范围 | 当前实际命令、结果与证据 |
|---|---|
| 来源正文功能回归 | `:backend:test :backend:installDist -PbackendFunctionalOnly=true`：SQLite `source-effects-final-sqlite.json` 为 445 项、0 failure/error/skip，构建成功；PostgreSQL 18.6 `source-effects-final-pg18-retry.json` 同为 445 项、0 failure/error/skip，installDist 为 UP-TO-DATE。首次 PG 运行 `source-effects-final-pg18.json` 因专用实例未运行发生 282 项失败，恢复原匿名实例并确认 `20261028_51` 后重跑通过；失败记录保留，不计通过。没有改测试预算或启动原 PG16 实例 |
| 来源正文效果 | `source-effects-red.log` 因缺入口失败；`:backend:test --tests '*CashSourceEffects*' -PbackendFunctionalOnly=true` 的 `source-effects-golden.json` 为 10 项、0 failure/error/skip，包含 9 个原生定义和 2,208 组完整差分；生成器 `scripts/export_cash_source_effects_goldens.py --check` 通过。Python 同一原文件 `python -m pytest -q -p no:cacheprovider -m 'not performance' tests/test_cash_statement_effects.py --junitxml=<缓存根>/evidence/source-effects-python.xml` 为 24 passed、0 skip、0.26 秒；纯规则不使用数据库。源对应登记 9 定义/24 参数，独立复核未完成，仍为 pending |
| 来源选择规则失败先行与差分 | `record-source-policy-red.log` 因缺实现失败；`record-source-policy-differential.log` 复现 JSON 浮点文本不同而值/摘要相同，差分保留类型及完整值校验；`record-source-reference-red.log` 复现事项标识接口不能接受原字符串。最终按字符串查找，前导零/超 Long 文本返回原 `issue_changed`；`scripts/export_cash_source_resolution_goldens.py --check` 验证 708 组完整值、错误、摘要及 Python 输入不变。8 项原生方法另核验归属、固定字段和容器复制，不把黄金计为原源定义完成 |
| 来源选择规则完整功能回归 | `run_gradle.py sqlite record-source-final-retry-sqlite :backend:test :backend:installDist -PbackendFunctionalOnly=true`（12:49 UTC，248.66 秒）及 `run_gradle.py postgres record-source-final-pg18 :backend:test :backend:installDist -PbackendFunctionalOnly=true`（12:55 UTC，187.76 秒）两库各 435 项后端通过，0 failure/error/skip；SQLite 完成 installDist，PG installDist 为 UP-TO-DATE。先前 `record-source-final-sqlite.log` 因 Gradle daemon 退出中断，无 JUnit 结果，不计通过；两项明确性能测试保持排除 |
| 来源选择规则 Python 功能参考 | `python -m pytest -q -p no:cacheprovider -m 'not performance'` 运行 `tests/contract/test_cash_source_issue_resolution.py tests/contract/test_cash_record_maintenance.py tests/contract/test_cash_independent_identity.py tests/contract/test_cash_draft_merge_plan.py`；显式 `FT_REQUIRE_TEST_POSTGRES=1 FT_TEST_POSTGRES_URL=postgresql+psycopg://postgres@127.0.0.1:55432/ft_kr_jooq_reference_test`，清除 `FT_DATABASE_URL`。两库合计 250 passed、0 skip，447.23 秒，530 项既有 SQLAlchemy 警告；命令、HEAD、时间和 JUnit 见 `record-source-python-functional.json/xml/log`。这些包含尚未迁移的维护写入，仅作参考，不增加已迁移原定义 |
| jOOQ 失败先行 | `jooq-persistence-red.json` 确认缺少持久化实现；`jooq-review-regressions-red.json` 于 10:56 UTC 复现代码生成 URI 原文泄漏和公共编辑读取无关损坏原文，`jooq-review-regressions-green.json` 16 个后端及 2 个代码生成测试通过。恢复窄列读取、固定安全错误及去除异常 cause；精确金额、UTC、JSON、caller-owned 事务与保存点均纳入双库完整回归 |
| jOOQ 全部已实现功能 | `:backend:test :backend:codegenTest :backend:installDist -PbackendFunctionalOnly=true`，`jooq-final-functional-sqlite.json` / `jooq-final-functional-pg18.json` 两库各 415 项后端测试，0 failure/error/skip；SQLite 另实际运行 2 项 codegenTest，PG 为 UP-TO-DATE。后续证据接口验证见下行 |
| 当前后端完整功能 | `:backend:test :backend:codegenTest :backend:installDist :shared:testAndroidHostTest --tests '*Kotlin*ResponseIntegrationTest' -PbackendFunctionalOnly=true`。`evidence-final-sqlite.json`（12:00 UTC，100.46 秒）及 `evidence-final-pg18.json`（12:02 UTC，180.17 秒）各 425 个后端、12 个实际客户端测试，均无 failure/error/skip。此后只改资金调拨损坏类型拒绝，受影响复跑见下行；不把 425 项写成新增拒绝后的全量重跑 |
| 证据失败先行及最后修改 | `evidence-native-red.json` 两项复现 404 缺入口；`evidence-root-membership-red.json` 复现根成员缺失时错误未映射，真实 Python 双库 404 对照见 `evidence-root-python.json`；`evidence-funding-shape-red.json` 复现内部类型数组/对象被静默忽略。`:backend:test --tests '*CashEvidence*' --tests '*CurrentProjection*' :backend:installDist :shared:testAndroidHostTest --tests '*KotlinEvidenceResponseIntegrationTest' -PbackendFunctionalOnly=true` 的最终 SQLite `evidence-reviewed-sqlite.json`（32.24 秒）与 PostgreSQL `evidence-reviewed-pg18.json`（51.83 秒）均为 27 项后端及 1 项客户端通过，无 failure/error/skip |
| 全响应与无写入 | 当前详情/定位原双库各 90 组，证据接口由 `scripts/export_evidence_contracts.py --database-url <新目标库> --oracle-database-url <新参考库> --output <仓库外目录>` 各生成 54 组完整响应。Ktor 比较全部字段及 17 张财务表的全部列，覆盖根/成员/现金部分、关系顺序、隐藏记录、草稿 null、Long、资金白名单及安全错误。最终同一快照发布/清理交错，以及读取和返回前的撤权、换区、登出、过期与 viewer 降权都进入 27 项受影响回归 |
| 原生源断言 | `CashEvidenceSourceMigrationTest` 逐条对应六个原定义/七个原展开参数，包括成员和关系一次批读、完整退款可见性/时间线、非活动提示、完整 HTTP envelope 和字符串现金部分标识。源对应表新增六项全部 pending，等待独立逐断言复核；包含资金调拨扫描/列表的组合原定义不计完成 |
| 实际客户端与构建 | `jooq-client-builds-sqlite.json`：11 项实际 Ktor 消费通过，`:shared:compileAndroidMain :androidApp:compileDebugKotlin :shared:compileKotlinJs :shared:compileKotlinWasmJs :webApp:compileKotlinJs :webApp:compileKotlinWasmJs` 成功；PG 当时 11 项见 `jooq-review-client-pg18.json`。新增证据后双库各 12 项见完整回归；最终资金形状修改只重跑证据消费者。来源成员 DTO 支持草稿 null；旧 EvidenceDto 空金额/时间报 `request_failed`，断言并记录该既有限制，不宣称完整来源原文流程通过 |
| 公共平台 | `jooq-functional-all-sqlite.json` 中 contracts JVM/JS/Wasm 各 3 项实际通过；domain 同任务为 UP-TO-DATE，未作为本轮重跑。证据接口没有修改公共规则或客户端生产代码 |
| Python 功能参考 | `FT_REQUIRE_TEST_POSTGRES=1 FT_TEST_POSTGRES_URL=postgresql+psycopg://postgres@127.0.0.1:55432/ft_kr_jooq_reference_test`，清除 `FT_DATABASE_URL`；`python -m pytest -q -p no:cacheprovider -m 'not performance'` 执行 `tests/test_application_cash_projection_evidence.py tests/test_relational_cash_projection_evidence.py tests/contract/test_web_api.py tests/contract/test_cash_current_projection_api.py`。58 passed、0 skip、70 项既有警告、pytest 87.21 秒；完整命令/时间/环境见 `evidence-python-functional.json/xml/log` |
| 真实 Netty | 缓存根 `verify_live_evidence.py --backend <sqlite|postgres>` 启动当前 installDist，使用 loopback 18450/18451 和独立匿名库。两库各 23 个 HTTP 操作完成，包含注册/工作区/账户原键、精确新增、FX 转账、当前读取与完整证据 Python 回包对照、401/404、校准、输入/范围拒绝及失败无写入；15 表快照。`evidence-live-sqlite.json` / `evidence-live-postgres.json`，服务器已退出。最后资金形状拒绝不影响该成功路径，错误分支由原生专项验证 |
| 官方代码生成与安全 | `:backend:generateJooq -PjooqOutput=<仓库外目录>` 真实执行，`jooq-codegen-gradle-verified.json/log`；`jooq-codegen-gradle-equal.json` 67 文件全部相同。`jooq-codegen-rejections.json` 验证 8 种显式目标拒绝，2 个 codegenTest 覆盖格式错误、凭据、参数和完整诊断去敏感原文。默认构建不联网查库 |
| 当前单条流水详情读取 | 生成器 `scripts/export_cash_record_detail_contracts.py --database-url <新隔离库> --output <仓库外目录>` 在 SQLite 与 PostgreSQL 18.6 各生成 36 组匿名 HTTP 用例（23 组 200 完整响应，13 组 401/403/404 安全错误），含真实 `asdict(SourceCoverage)` 默认覆盖、空 `pending_fields`、候选、来源动作成功/失败与降权；`:backend:test --tests '*CashRecordDetailContractTest*' -PbackendFunctionalOnly=true` 两库各 5 项通过，包含逐字段完整响应、全部相关表读取前后逐行一致、返回前降权去除编辑能力、撤权停止读取、损坏来源覆盖/待采用字段停止读取不修复。独立复核首轮 P1（无文档覆盖误判为损坏）与 P3（空数组、空证据键、字符串 page_total、绑定分批）已修复并重跑；证据根 `fixtures-sqlite/detail`、`fixtures-postgres/detail` |
| 详情实际客户端与完整回归 | `:shared:testAndroidHostTest --tests '*KotlinRecordDetailResponseIntegrationTest*'` 两库各 2 项通过：一项用真实 Ktor 字节解码 `CashCurrentRecordDto` 与领域映射，逐项核对字段、条目、问题许可动作/候选/待采用载荷、关系、投影引用及 `source_payload`；另一项以证据回包调用既有 `KtorCashMaintenanceRepository.sources()`，验证成员修订/投影版本交叉检查与 `CashSourceMember` 组装。全量 `:shared:testAndroidHostTest` 437 项、0 failure/error/skip。`:backend:test :backend:installDist :backend:codegenTest -PbackendFunctionalOnly=true` 在 SQLite 与 PostgreSQL 18.6 各 450 项、0 failure/error/skip，installDist 与 2 项 codegenTest 通过；夹具由 `generate_fixtures.sh` 在仓库外重新生成 |
| jOOQ 持久化复核修复 | 独立复核 P2：SQLite 连接未复现 Python 的 WAL/`synchronous=NORMAL` 与私有权限提示。`Database.kt` 现对每条连接设置 `foreign_keys`/`busy_timeout`/`journal_mode=WAL`/`synchronous=NORMAL`，启动读取主库及 `-wal`/`-shm` 权限并在预览入口输出脱敏提示；启动同时校验 29 张必需表存在，缺失返回 `storage.schema`。codegen 拒绝测试等待上限由 15 秒调整为 60 秒。新增 `StorageContractTest` 2 项覆盖 PRAGMA、私/公权限、侧车提示及“修订正确但缺表”分支；两库完整功能回归含 codegenTest 通过。保留 P3（生成目录陈旧项、生成表允许清单、PG socketTimeout）并登记。 |
| 语言类型边界 | `:backend:test --tests '*CashRecordPolicyLanguageBoundaryTest*'` 2 项通过：组件规范化保持共享容器引用、深拷贝隔离输入、account_id 字符串化；JVM String 不可附加属性且按值复制。`scripts/check_kotlin_test_migration.py` 无错误，两项原定义由 pending 调整为 retained，附执行命令与兼容说明 |
| 4.8 计划存储与输入校验（部分） | `MutationPlanStoreTest` 3 项与 `MutationPlanInputGoldenTest` 33 组 Python 差分通过：上下文 43 字符串标识、16 字符计划后缀、TTL 1–1800 秒、所有者/工作区隔离、修订 CAS、重复取消幂等、过期删除；输入校验覆盖六种意图、问题/关系选择、成员补丁、手工关系补充、元数据组合、标识与修订边界。`MutationPlanViewGoldenTest` 逐项对照 Python 冻结计划视图、分页游标/筛选、单项详情、虚拟组成项移除及 `plan_page_invalid`/`plan_cursor_invalid`/`plan_item_not_found`，10 组检查通过。`MutationPlanEntryTargetGoldenTest` 3 组对照 Python `entry_target` 的 `ie_` 摘要与完整目标结构（含无关系、活动边、父子成员、待写 row_refs、非活动边及金额文本）；`MutationPlanEffectsGoldenTest` 4 组对照附加影响摘要（字段值变化、新关注、关系/资金关系移除、显式移除抑制、无效选择、关系失败摘要与基线去重及排序）。生成器 `export_mutation_plan_{input,view,entry_target,effects}_goldens.py --check` 使用排序键后可重现。首个端到端切片已接入：`MutationPlanBuilder` 对当前草稿生成 save_draft、对已确认流水生成 edit_record、对完整草稿生成 finalize_draft 冻结计划（操作、字段差异、账户/待补齐问题、entry 公共值、entry 目标摘要、附加影响、单项显示），`MutationPlanService` 与 5 个 HTTP 入口完成创建/读取/分页/单项及修订/权限/过期错误；`MutationPlanBuildContractTest` 与 `MutationPlanHttpContractTest` 在 SQLite、PostgreSQL 18 各通过 Python 视图/单项逐字段对照及错误/无写入用例，夹具由 `export_mutation_plan_build_contracts.py` 生成。其余意图（maintain_relations）、提交/resolve/取消与正式客户端消费仍未完成，4.8 不勾选 |
| 4.8 计划构建与 HTTP 切片 | `MutationPlanBuildContractTest` 在 SQLite 与 PostgreSQL 18.6 各 18 组用例（save_draft 4 成功/1 字段错误、edit_record 2 成功/1 固定字段冲突、finalize_draft 2 成功/2 状态或完整性问题、edit_entry 1 成功/2 条目修订错误、resolve_issue 2 成功/1 事项修订错误）逐字段对照 Python 视图与单项详情；`MutationPlanHttpContractTest` 各 5 项覆盖创建/计划/当前计划/分页/单项、修订与固定字段冲突、未知字段/意图、viewer、匿名、非法 plan id、缺失上下文、finalize 成功/不完整及无写入。金额文本保留原始标度；夹具生成器 `scripts/export_mutation_plan_build_contracts.py` 创建独立 SQLite/PG 库。另有计划重预览 CAS HTTP 测试：同一 context 重新预览推进 `plan_revision`、旧 plan_id 与旧 revision 均返回 409 `plan_changed`；冻结 operation 已保存 entry_edit 元数据供后续提交使用。正式客户端消费、resolve/取消与其余意图仍未完成。 |
| 4.8 提交（edit_record 切片） | `MutationPlanCommitHttpContractTest` 在 SQLite 与 PostgreSQL 18.6 各 1 项通过：Python 预览+提交生成独立 oracle 库，逐字段核对收据（`completed`、`updated_posted=1`、投影版本、待处理计数）及 entry 修订/人工字段、流水备注/修订不变、投影版本与成员数；同一幂等键重放返回同一收据，已消费上下文新键返回 409 `edit_context_committed`，缺键返回 422。提交支持 entry-only `edit_record`、完整草稿 `finalize_draft`、`save_draft` 与 `resolve_issue` 的 `update_draft`/`duplicate`（流水行/组成项/问题/余额快照/投影/收据原子写入）；`maintain_relations` 与其他状态仍明确返回 `plan_input_invalid`。`MutationPlanSaveDraftCommitHttpContractTest` 与 `MutationPlanResolveCommitHttpContractTest` 双库对照 Python 收据、entry、流水、问题、投影与余额。现有 Compose 计划消费者已用真实 Ktor 字节验证：`KotlinMaintenancePlanResponseIntegrationTest` 解码计划、分页、单项与提交收据并核对字段；`maintain_relations` 已支持无关系选择/手工新增的关系自由计划（复用草稿/已确认更新路径），实际关系组、候选决定与资金关系写入仍 pending。另有 `MutationPlanFinalizeCommitHttpContractTest` 双库对照 Python 收据、entry、流水 state/note/row_revision/manual_overrides、余额快照与投影版本。`POST /cash-mutations/resolve` 同测试覆盖 `retry_original`、按原键/上下文返回已提交收据、非法 plan id 404 与缺键 422；4.8 不勾选 |
| 锁等待认证 | `WorkspaceLockReauthorizationTest`（PostgreSQL 18.6）3 次真实锁等待：持有工作区行锁期间分别切换会话工作区、删除当前成员、注销会话，再释放锁；删除请求均以 `AccessFailure` 失败且工作区仍存在，证明锁后重读当前会话/工作区/成员。SQLite 不执行该并发用例（以真实 PG 行锁为准） |
| 工作区财务删除 | `WorkspaceFinancialDeletionTest` 在 SQLite 与 PostgreSQL 18.6 各 1 项通过：创建独立工作区并写入导入现金流水、组成项、entry/成员、投影数据集/状态/投影/成员/关系及收支关系后，按名称删除工作区；工作区、账户、流水/组成项、entry/成员、关系、投影全表计数归零，会话切回剩余工作区。锁等待期间换区/撤权/登出及性能矩阵未覆盖 |
| 4.10.5 多文件来源批次规划 | 2026-10-30（本机），验证 HEAD/比较基线 `264d1a94799d011c5e40a1401d0f55bced5d532b` 加当前工作树。`:backend:test --tests '*CashSource*' :backend:installDist -PbackendFunctionalOnly=true` 为 47 项、0 failure/error/skip；`CashSourceBatchTest` 17 项及 `CashSourceBatchGoldenTest` 49 组完整结果覆盖 34 原功能定义/42 参数。`PYTHONPATH=src:tests .venv/bin/python -m pytest -q tests/test_cash_source_batch.py tests/test_cash_source_matching.py -k 'not large' -p no:cacheprovider` 为 91 passed、5 deselected；导出器 `--check`、ktlint/detektMain/detektTest、OpenSpec strict、`git diff --check` 通过。先行失败为缺少批次类型和入口，见 `/tmp/ft-source-batch-red.log`；最终证据 `/tmp/ft-source-batch-final.log`、`/tmp/ft-source-batch-static.log`、`/tmp/ft-source-batch-migration.json`。独立上下文 `batch_review` 完成范围/工程/安全/最终 diff 与原断言复核：缓存遗漏、Unicode 排序和测试缺口已修复重审，无剩余 finding。4 个 large 原定义保留 pending，未运行性能。无数据库读写、HTTP 或客户端改变，本片双库/Web/Native 验证不适用；后续接事务时须双库及实际客户端验证。文档已核对 README、inventory、功能地图、词表与本技能；无新领域术语。未接上传/暂存/提交，故不增加 HTTP 数、不归档；回滚为撤销本片纯规则及调用，不涉及数据迁移。 |
| 4.10.4 来源对应解析 | `CashSourceMatchGoldenTest` 对照 Python：12 组 new/duplicate/match/account-conflict/pending/deleted/targets/batch-duplicate/位置冲突/文档页冲突/守恒多行/额外新行，及 provider/import 新增 ID；逐项 action、transaction、candidate、reason、bind、duplicate_of 一致。原生断言未知 target 失败关闭、非字符串 occurred_at 仍可索引。独立复核无 P1/P2；已修复非字符串时间、未知 target、state null 与 record truthiness；覆盖 evidence 旧分支/文档作用域/数字相等性等 P3 已登记 |
| 4.10.3 来源账户扫描 | `CashSourceAccountMappingGoldenTest` 对照 Python：20 组支付宝组件/状态（含全角数字/U+3000/无空格边界）、13 组分摊草稿（完整/部分/负向/不守恒/非法/不完整/map 形式/布尔与浮点账户 ID/全角金额）、30 组来源身份/legacy 与 18 组分组（币种/行数/掩码/group_id/顺序），覆盖微信零钱、工行/建行/工银亚洲文件账户、composite 与缺失身份错误。独立复核无 P1；已修复 Unicode 空白/数字边界、allocations truthiness 与 Python truthiness/int 转换，负零/NaN/异常类型等 P3 已登记 |
| 4.10.2 已解析文件组合 | `CashSourceRowsGoldenTest` 对照 Python：13 组文件批次覆盖跳过行、字段深拷贝/resolved 覆盖、subset 覆盖降级、微信命名 scope、非微信 label 拒绝、重复 file_ref、双页完整性合并/指纹冲突、未解析/失败文件、已知文档证据同命名空间合并、带 document_key 的 subset、空行账户回退；occurrence 键、fields、provider/位置、coverage、evidence 顺序与文件元数据逐项一致；原生断言 resolved 新键/nested 值不改变原行。独立复核无 P1/P2，P2 测试缺口已补（深拷贝、已知证据合并、subset 文档键），覆盖字段/list/诊断等 P3 已登记 |
| 4.10.1 来源身份纯规则 | `CashSourceIdentityGoldenTest` 对照 Python：17 组 occurrence（provider/文件/出现/文档/页绑定键与 spec 顺序、无账户 provider、长度/位置/定位反例）、15 组 scope（微信默认/命名 NFC、控制字符、空白、64 码点/256 字节、非微信 label/owner 边界）、11 组多文档完整性/当前覆盖（一致、缺页、指纹/声明/筛选/精度/策略/页数冲突、双文档、无键）及 11 组 `contains`/`comparable`（边界、筛选、精度、策略、空格/小时/秒 offset、仅日期）逐项一致；`SourceDigestParityTest` 验证有限/非有限浮点与 Python `json.dumps` 摘要一致。独立复核无 P1；已修复 coverage 证据校验、`comparable` 覆盖、非有限浮点与时间格式，剩余 filters 类型/非字符串键/默认 end 边界等 P3 已登记 |
| 4.9.9 统一入口 | `CashStatementDispatchTest` 2 项：PDF、平台 CSV、建行 XLS 金标准分别经 `parseCashStatement` 完成读取与归一，未知来源返回 `statement_source_unsupported`；不新增上传、去重、关系或持久化。 |
| 4.9.8 ICBC PDF 行归一 | `IcbcPdfRowNormalizationGoldenTest` 对照 Python `normalize_icbc_document`：6 个文档、33 行覆盖信用卡借/贷/未知方向、金额/时间/币种错误、退款/退货/年费/刷卡金/返现/转账、掩码卡号、极值金额、借记卡退款/退货/撤销/基金赎回/渠道、无 owner、范围外与错误行；完整 fields、payment_method、issue/error、来源解释、row/document 位置、账户/归属及 coverage 键清理逐项一致。独立复核通过 461 行级和 17 文档级差分，无 P1；负零文本已统一修复并新增三类来源金标准，基金清算户/不可信商户/空描述/币种/日期边界等 P3 测试缺口已登记 |
| 4.9.7 银行行归一 | `BankRowNormalizationGoldenTest` 对照 Python `normalize_bank_document`：建行/工银亚洲 14 个文档、47 行覆盖收支方向、退款、支付源/地点/嵌套前缀对手方、币种变体、账户/卡号与空/短尾号、account_opening、金额/日期/币种错误（含 20260230 严格拒绝）、覆盖外行/边界/空证据、工银亚洲可证明重建、NUMERIC 边界、负零、错误行及不支持来源；完整 fields、payment_method、issue/error、disposition、来源解释、document account/label 与覆盖包含逐项一致。独立复核无 P1/P2；已修复 CCB 日期 SMART 自动截断、短卡号显示名和空错误码，负零/Unicode 正则等 P3 已登记 |
| 4.9.6 平台行归一 | `PlatformRowNormalizationGoldenTest` 对照 Python `normalize_platform_document`：6 个文档、57 行覆盖支付宝/微信支出/收入/不计收支、0 元、金额/时间/方向错误、关闭/还款失败跳过、退款信号、余额宝/余利宝路由、微信中性交易、订单号边界、错误行/空 payload、精确金额边界（scale/range/下划线/全角/负零）与日期变体；完整 fields、payment_method、provider_id、issue/error、disposition、来源解释和覆盖可用性逐项一致，输入 payload 由黄金单独保存而非从输出重建。独立复核确认 P1 金额精确边界与方向未知时理财路由已修复；负零文本、行诊断作用域与极端时间格式登记为 P3 |
| 4.9.5 记录类型/对方账号规则 | `CashRecordTypeGoldenTest` 对照 Python `classify_cash_record`/`normalize_counterparty_account`：224 组来源行覆盖冲正、P2P 退回、退款、还款、offset、微信/支付宝/工银亚洲/建行/工行/跨境汇款分支、方向来源与金额符号/空白/全角数字；35 组账号覆盖空值、分隔符、NBSP、掩码、尾号、完整号、全角尾号、文本/邮箱及工银亚洲可证明重建；163 组校验覆盖默认子类型、全部合法/非法组合与错误正文；payload 摘要与 Python 一致。独立复核无 P1/P2，已修复空分隔符、Unicode 空白/数字与共享规则复用问题 |
| 4.9 银行表格文档读取 | `BankStatementDocumentGoldenTest` 11 组对照 Python `read_bank_document`：建行 XLS 正常/标题不匹配/笔数不符/序号跳号/行形状/非 512 字节，工银亚洲 UTF-16 TSV 正常/笔数不符/银行不匹配/掩码账户/来源提示冲突；文档、行原文、账户/标签/币种与日期覆盖逐项一致。工行 PDF 原始文档读取见 4.9.4 |
| 4.9 CSV/TSV/XLSX 文档读取 | `CashStatementDocumentGoldenTest` 19 组对照 Python `read_platform_document`：支付宝/微信 CSV、引号内换行/逗号、GB18030、导出类型缺失、金额原样、声明笔数不符、列形状错误、空输入、非账单内容、未知来源及错误码；完整文档字段、行位置/原文/错误和覆盖证据逐项一致。XLSX 覆盖解压/成员/坐标/DTD 预检、稀疏行、超宽列、声明笔数不符与尾部空列。上传流程与行归一未迁移 |
| 4.9.4 ICBC PDF 文档读取 | `IcbcPdfDocumentGoldenTest` 38 组对照 Python `read_icbc_document`：信用卡/借记卡、来源提示及不匹配、两页、卡号冲突/缺失、区间冲突、笔数/页数/页码/筛选/缺失区间、无效/空表头、无表、同页两表、换行表头、欧元、特殊字符、缩放表格、装饰性单格框、合并列/合并行单元格、曲线绘制的表格、缺失 `%%EOF`、损坏 `startxref`、空/非 PDF/零页、加密必填/错误/正确密码（确定性 pypdf 样本，正确密码核对完整文档）；源类型、每行原文/物理位置、账户、声明笔数、上下界、`document_key`、`page_fingerprints` 与逐项覆盖一致。`IcbcPdfDocumentLimitsTest` 覆盖输入/页/字符/解析字节/物理行/业务行/表/列预算；`CashStatementDocuments` 按字节和来源提示分派 PDF。PDFBox 仅后端依赖；行归一、上传流程和真实账单样本未迁移。独立复核确认无 P1/P2；重审发现并修复曲线边丢失（新增曲线绘制表格金标准），残余 `startxref` 错误优先级、超 32 位声明笔数、旋转页与极端图形预算登记为 P3 |
| OpenSpec | 来源正文阶段 `openspec validate kotlin-backend-shared-core --strict --json` 重新通过；jOOQ 阶段 `openspec doctor --json` 健康。`openspec validate --all --strict --json` 为 26 passed / 12 failed；与jOOQ 验证基线导出的规格逐 finding/路径/级别完全一致，52 个缺失场景错误及 25 个长正文警告仍在其他 change/spec，见 `jooq-openspec-verification.json` 与 `jooq-openspec-{all,baseline,scoped,doctor}.json`。全局 6.4 未完成，不改动无关规格 |
| 清单门禁 | `scripts/check_kotlin_test_migration.py --collection <缓存根>/evidence/collection.txt --report <缓存根>/evidence/source-effects-migration-inventory.json` 重新核对无错误；`--require-complete` 仍按 2,381 个 pending 返回 1。当前 2,613 定义、5,844 原展开用例，已验收 232 定义/577 参数；证据读取六个及来源正文九个源对应尚未计入已迁移，不能把 JUnit 方法或黄金样本当作原定义数 |
| 静态、依赖和文档 | 来源正文阶段 6 份 Markdown 的 179 个本地链接/锚点、Python 生成器语法、17 个夹具变量、83 项任务/86 个接口及 `git diff --check` 通过，见 `source-effects-static.json`。公共模块无 SQL/jOOQ/Gson 越界依赖。此前 jOOQ 依赖核实见 `jooq-codegen-gradle-verified.log`，8 份 Markdown/177 链接见 `evidence-static-checks.json`，不作为本片新增静态结果 |

Cross-platform Impact Check：本片为后端替换与既有证据协议接入，无 UI、导航、文案或产品交互改动。Web/Native 共用的现有协议消费者通过实际 Ktor 字节验证；来源原文仍需未迁移的当前单条流水详情，不计完整 F-03/F-04 流程可用。页面数保持 10，已有 Web/Native TODO 保留。4.9.4 只新增后端 PDF 文档读取与后端专用 PDFBox 依赖，不改变公共 DTO、共享规则、页面、文案或交互；Web/Native/JS/Wasm 不受影响，不安排 UI 演示。Linux 无法运行 iOS 编译/测试，残余命令为 macOS/JDK 21/Xcode 上 `:business-domain:iosSimulatorArm64Test :business-contracts:iosSimulatorArm64Test :shared:compileKotlinIosSimulatorArm64`；不声称 iOS 或设备 QA 通过，也不打开浏览器。

文档收尾范围为根 README、backend README、功能地图、routes/inventory、proposal/design/tasks、主规格/delta、词表及项目 apply skill。根 README 的预览导航和词表现有收支/关联流水/现金部分含义不变；其他文档原位更新 jOOQ、PostgreSQL 18、三个读取接口、夹具命令与实际限制。未归档、切流或删除 Python/schema；本机回滚可撤销 Kotlin 变更，生产数据不受影响。全仓 Python 功能回归曾在环境重启时中断，`python-functional.log` 无完成报告，不计通过；全量功能迁移、余下接口、后台任务、格式与历史迁移矩阵仍由 6.3/6.5 跟踪。

## 当前审查

来源正文效果按 Python 原算法完成范围、工程、安全及原断言自检：六类来源、空值／异常 JSON、PDF 列歧义、可信身份、人工备注、金额方向及规则优先级均覆盖。2,208 组完整差分和 9 个原生方法通过，输入容器不修改。独立复核无 P1/P2，确认常量、正则、摘要、present 语义与六类来源规则逐项一致；P3-1 数值比较已改为按整数/浮点原始位比较，P3-2 生成器分支缺口和 P3-3 调用方类型合同作为残余风险登记。本片不新增依赖、SQL、schema、HTTP 或共享生产代码；Web/Native 页面数保持 10。根 README、backend README、功能地图、inventory、design/tasks、主规格／delta、词表及 apply skill 已核对；无新领域概念或行为变化。

来源选择规则已按 Python 原实现完成范围、工程、DX/安全及 diff 自检：修订/种类优先、正文存在性、固定字段先行、归属、冲突、复制和摘要均有原生/差分及当前功能回归。关闭 P2 事项字符串编号被 Long 提前收窄；浮点断言保留类型、完整值及负零，错误和摘要仍严格比较。独立复核无 P1/P2，708 组黄金可由脚本重新生成且与提交资源一致；`posted/manual_transfer`、非等价金额/组件冲突及 ICBC 交易场所回退未纳入确定性生成器，登记为残余覆盖缺口。该阶段无 UI/共享生产代码、schema 或新依赖变更，Web/Native 页面数与原 TODO 不变；README、功能地图、inventory、design/tasks、词表和 apply skill 已核对。

本片 jOOQ 与证据接口由实施者完成范围、工程、DX/安全及 diff 自检；当前没有可用独立复核上下文，没有宣称新的独立审查通过。既有历史切片的独立结论只覆盖其当时实现，不替代 jOOQ 全部改写及证据接口复核。以下处置通过失败先行及受影响测试验证，仍须独立重审后才能勾选对应任务。

| finding | 处置与回写 |
|---|---|
| P2 代码生成格式错误泄漏 URL 原文 | 固定错误去除 cause，两个 codegenTest 检查完整诊断及无文件输出；design/生成器/安全测试 |
| P2 宽记录读取改变无关损坏原文的公共编辑边界 | 恢复原 13 列，applyEdit/chooseCommon 验证只改公共值且原文不变；design/仓库/原生测试 |
| P2 证据根成员缺失错误不等价 | 显式转原 not_found 404，双库 Python HTTP 与 Kotlin 无写入回归；design/证据仓库 |
| P2 损坏的资金调拨类型被静默过滤 | 数组/对象停止读取，保留原内部错误边界；原生失败先行及双库受影响复跑，design |
| 既有客户端限制 | 旧 EvidenceDto 不接受草稿空金额/时间；来源成员 DTO 可读，完整原文仍缺单条详情。README/功能地图/inventory 明确记录，不以局部消费关闭整流程 |

以下为已保存切片的独立审查结论；本片不将其当作新独立结论。

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

当前收支详情/定位与收支证据读取由独立只读上下文复核，未发现 P1/P2。逐字段核对投影、成员、问题、关系、资金调拨白名单、金额/时间/字符串标识、隐藏/删除/跨区/未就绪错误及同一快照；授权在读取前和返回后独立重读，真实重建交错通过。保留三项 P3 不构成阻断：鉴权读取阶段的极少见存储故障会被映射为 `projection.unavailable` 而非全局 `storage.*`（正常路径先由拦截器失败）；证据关系状态越界按损坏状态停止读取而非标为 `rejected`（CHECK 约束下不可达）；非活动关系提示的混合排序仅由代码等价推导、夹具未强制混合顺序，后续补夹具。夹具未入库是环境事实，按 `generate_fixtures.sh` 在仓库外可重现。

当前单条流水详情首轮独立复核发现 P1：真实 `asdict(SourceCoverage)` 的无文档覆盖被误判为损坏，使正常重复草稿返回 503。已修复为接受 null/空 `page_fingerprints`，并新增 1017/1018 覆盖默认结构合同；同时修复空数组 `pending_fields` 的 Python 等价、空白证据键过滤、字符串 `page_total` 拒绝、候选/绑定分批和诊断异常重复包装。P3 均有回归或合同覆盖，重审确认 P1 关闭且无回归；客户端用例已补 `sources()` 交叉检查、领域映射问题/关系/投影与待采用载荷。残余仅 Python 对空字符串/数字/布尔假值 `pending_fields` 视作缺省而 Kotlin 仍停止读取，属不可由应用产生的损坏状态，登记不阻断。复核对字段集合、序号、金额/时间格式、关系候选纳入、attention、来源动作准备、待采用字段 presence、错误状态与消息、无写入和损坏状态停止读取未发现其他阻断。

jOOQ 持久化独立复核发现 P2：SQLite 适配器未复现 Python 的 WAL/synchronous 与权限提示。已在 `Database.kt` 补齐四条 PRAGMA、启动权限提示和 29 张必需表校验，并将 codegen 拒绝测试等待上限改为 60 秒；新增 `StorageContractTest` 覆盖 PRAGMA、权限、侧车和“修订正确但缺表”分支，双库完整功能与 codegenTest 通过。重审确认 P2 关闭且启动/安全行为无回归；保留 P3：生成到非空目录不会删除陈旧文件、生成器无表允许清单、PG `socketTimeout=30` 与 Python 不同，均登记为残余风险。

来源选择规则与来源正文效果独立复核无 P1/P2；708/2,208 组黄金可由 `.venv` 重新生成且与提交资源一致。已修复 P3-1：效果黄金测试改用数值感知比较，避免 Python `1e-07` 与 JVM `1.0E-7` 的文本差异造成假失败。保留 P3：生成器未确定性覆盖 `posted/manual_transfer` 固定字段分支和 ICBC `交易场所` 回退，组件/金额冲突样本多为语义相等；`CashAmount`/组件 Map 的未检查强转由当前调用方保证，4.8 接入时须固定输入类型。上述均登记为残余风险，不阻断当前切片。
