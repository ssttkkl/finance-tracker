---
name: openspec-apply-change
description: Implement tasks from an OpenSpec change. Use when the user wants to start implementing, continue implementation, or work through tasks.
allowed-tools: Bash(openspec:*)
license: MIT
compatibility: Requires openspec CLI.
metadata:
  author: openspec
  version: "1.0"
  generatedBy: "1.7.0"
---

Implement tasks from an OpenSpec change.

离线仅允许读取缓存及本地导入准备／核对，不实现离线确认队列或待提交账本叠加。业务写入须在线，UI 禁用与实际提交入口均需守卫，不能把历史角色改为 viewer；在线主动确认后已冻结的原操作（含首 HTTP 前中断）保留原键及材料，恢复联网本身不发送未提交核对内容。原会话的已知离线入口已拦截现金命令、账户修改和新导入确认；未知原请求不宣称未提交，已知成功收据可继续收尾。原恢复资源已持有同一可观察资格，在线 HTTP 断网也封闭原资源，迟到失败不更改后来会话；读取／事实刷新不单独重开资格，须实际核验会话并发表新资源。传输来源仅由 HTTP 边界内部标记及两种明确包装识别，本地文件／存储 I/O 不按断网处理。共享 UI 已消费原离线和资源就绪资格，实际浏览器已验证确认禁用、显式重连零自动提交及本地恢复失败保持限制；演示方向待用户确认。工作区七类实际写入口沿同一原资格及固定凭据，等待串行锁前捕获原资源，关闭后、HTTP 前复核操作代次；完整 6.7 以 tasks 的当前证据为准。

Compose Wasm 的文件 Worker 有界读取、解析和捕获 CSV／XLSX；故障按受影响文件恢复。核对从当前工作区的已提交快照文本及账单捕获组装，使用同一共享规则，首屏只解码摘要、首屏和核对记录。明确确认后后台准备完整冻结正文，原所有者保存后才提交。已有收据的归档故障沿原键继续整理。流水查询 Worker 按会话持有同一版本的索引，故障时保存当前可见结果并沿本地入口重试，旧结果只供查看。凭据不跨 Worker，后端断开、浏览器实际断网与缓存重建分别验证。

Web 计算任务共用具名命令、请求编号及 Kotlin 挂起桥接。新增任务沿同一发布入口注册并保留业务响应校验。取消、迟到回包和非法响应按实例验证，流水会话关闭及 SQLite 存储协议分别由各自所有者管理。

旧物化列表／详情／证据／定位及状态／重建六路由已退役，对应 OpenAPI 方法删除；同路径 DELETE 保留时列表 GET 为 405，其余旧方法为 404。规范快照、写集与永久收据保持，物化表及内部旧实现仍按 4.3–4.5 完成。

旧综合财务报表 `GET /reports/finance` 按用户决定整体退役，不保留服务端现金汇总例外；真实路由、OpenAPI 方法及专用 report 组装／月份解析／现金 SQL 一并删除。保留账户余额及独立投资事件、持仓与估值；`FinanceDecimal` 仍被投资读取与导入使用，不能按报表前缀整删。原 67 个应用向量与 80 个 HTTP 向量保留全部原输入，只继续消费其中 49 个 accounts 应用向量与 40 个 accounts HTTP 向量，不重生期望。当前 HTTP 不可用及真实并发读取的证据以 tasks 为准，不能以路由移除关闭整个物化表清理。

手工记账、转账及旧现金新增／校准已使用 maintainCanonicalCashEntries，不能把旧混合维护连同条目身份协调一起删除。输入为父流水 ID，转账只含现金侧；余额、投资事件、校准覆盖及完整响应保持原合同。三旧写入类继续验证精度／人工标记／回滚，仅退役物化输出及专属溢出故障；两 HTTP 原黄金明确扣五表并保全部其他列和响应，新 identity 列单独核对。物化表及其余真实在线引用仍待清理，4.3／4.4 不能据本片关闭。

永久公共字段重导、删除后重导及维护恢复测试的导入前置使用真实规范快照、完整端侧计算和正式写集提交，不构造旧服务或暂存身份；不相关导入须发生在维护预览之后。保原公共清空／人工值、来源原文、稳定条目、冲突全库不变和墓碑／新身份。旧支付宝空交易号输入保留为拒绝回归，不能补编号或换来源制造通过；合法银行无编号文档另走实际导入、删除、重导，核正式状态与有效绑定。原黄金保留，运行副本从执行前种子清单恢复，只补当前版本元数据；不能用执行后的旧库冒充初始状态。RecordWriter 未提交外层事务的前置另行迁移，不在事务回调内嵌独立服务；本片不关闭 4.4／4.5。

旧支付宝账号范围绑定不能用于当前固定交易身份匹配，不得为修绿补绑定认领旧 ID。来源问题的当前别名保留／显式清空测试先由完整计算与正式提交产生可靠身份，再明确通过仓储设置待采用状态；核一／两个问题正文、修订、候选及父值不变，新别名仍指向初始实际 ID，显式 null 的 detail／SQL presence 保持。原两个旧身份包装退役，原输入／失败与其余完整黄金保留；独立保存及草稿合并的当前初始化、重导与维护重放保证由下段验证。

4.5 退役按实际调用职责拆分：提交状态／诊断边界已独立，原始规则数据夹具不构造旧 HTTP／Command 服务。旧会话、上传、计划、分页及确认十一方法和 ip_ 核实分支已退役；永久收据按原键／ID 继续读取，维护 mp_ 核实保留。原月度文件经生产本地计算和真实双库写集验证，不能把后端 reader 证据当作浏览器四格式通过。维护仍使用 buildOperations、previewInput 和暂存的 CAS／TTL／清扫，PDF／旧 XLS 仍使用上传值与 multipart 安全边界，不能按 CashImport 文件名前缀整删。账户币种双库合同已独立，临时存储错误由当前维护计划读取验证；原错误正文、资格与零写入断言保留。账户映射建议的原四项场景已改独立数据夹具、规范快照及真实本地计算／写集提交，保留原只读 SQL oracle 和隔离／资格断言；这不改变只保存实际采用选择的规则。旧会话和计划 HTTP 的两类九方法迁移测试已退役，共用元数据拒绝矩阵由当前 PDF／XLS parse-only 核 422、解析器零调用及账本零写；独立 PlanView／本地 reader 黄金和原资源继续保留。旧预览响应捕获的生产器退役，历史消费者不算当前联调。账户默认值发布九项原场景沿当前快照、本地完整计算和写集提交验证；确认后的事实变化先拒绝原基线，再保留原选择显式重核，不能恢复旧提交自动重算。实际 SQL 及映射／收据后的失败核全库回滚，当前收据不含旧文件级 skipped 字段，不得用缺省零掩盖错误。账户资格原二十八场景沿当前写集验证，确认后变化先拒绝原基线，合格账户经显式原选择重核仍保留新建／草稿补全／单笔编辑结果；资格丢失除基线拒绝外，还须用真实当前基线的不可信边界请求核精确资格错误、字段、整体无写入及无收据，不能只证明通用版本守卫。共享候选的两项测试须保留完整文件计算产生两草稿各共享两候选及实际写后整体回滚；旧支付宝空交易号向量按主规格不可达，独立银行夹具遵循已合并 89682e52 的来源修正，不能补编号、放宽生产门或冒称旧向量通过。旧计划发布包装的十八项已按职责分离：十一项分页／输入安全／公开裁剪规则迁至共同本地读取测试，七项旧 TTL、私有计划身份及服务包装退役；真实 Task 保留计算失败在保存前不替换原核对结果，保存后确认丢失仍须废弃旧计划。原 PlanApi 的已提交重放查询／事务退出故障已迁至当前写集 HTTP，实际 SQL 后注入分别在 SQLite／显式 PostgreSQL 核 unknown、全库不变及原键原正文精确重放；不恢复旧统一 503。原来源绑定、关系、空页及重复＋新增四项失败保证也沿当前写集双库验证：实际 INSERT 后同连接核可见行，失败核全生成表／版本回滚、无新收据，同键同正文重试后精确重放。旧 PlanApi 已被当前 owner／reader／写集和纯规则承接的十四项包装退役，另三项收据／入口包装已迁至正式 HTTP：同工作区另一 editor 虽能读快照仍不能按原 ID／键读他人收据，viewer 仍可完整读取本人收据而不能写入／重放；旧子集 retry 使用真实已存 ID 与原 null 行体仍 404。余四项已迁至当前完整本地计算／实际写集、真实 Task 和共同结果消费测试：退款四来源映射同一真实条目，非法补丁不替换原核对且可继续，重复／固定冲突无业务写入，失败文件计数保留 null。共同结果仅消费实际捕获输入，固定断言核 raw 收据与本地 failed／skipped 分离及编码恢复；旧 PlanApi 测试类已退役，仍使用旧 API 夹具的其他场景须逐项迁移。atomic／invalidated 的专用 helper 随使用者删除。七项旧客户端 RPC 捕获消费者已退役；仓库外历史捕获与独立黄金保留，仍留的旧 HTTP 捕获生产器按 4.4／4.5 后续逐项迁移，不算当前可重生成联调。无调用的客户端 Ktor 生命周期仓储删除；仍用的 context／plan／detail／choices、历史收据及结果 mapper 直接验证，永久收据／原键／账户／mp_ resolve API 保留。六个旧计划 preview／读取／commit API 及专用 serializer／path 已删除，永久命令 API 与共享 key／page／revision helper 原样归入 CashMutationApi.kt；本地选择仍用原 toDto 与显式编码，完整空选择／null／false／大 ID 保持。客户端五个旧 session API、上传编排及三种旧上传结果类型已删除，原普通 CashImportUploadInput 独立原样保留；context DTO、源预算关闭及公共错误引用矩阵继续验证。实际 Local 每次文件进度回调必须完成才读取下一份，回调失败保已存文件并停止；坏文件的有界读取／元数据错误不阻断好文件，集合无效先拒绝、取消传播且无提交。PDF／XLS 仍只走 parse-only，密码不进 URL／两种旧 header，不再发送来源范围设置。实际 Local 部分准备后断网保留已准备文件、最新上下文与剩余引用，不创建提交；畸形远端身份结果在缓存刷新及发表前拒绝。维护合同继续按实际职责保留。旧 Context 的银行原字节已由当前 XLS parse-only／共同端侧归一和共同亚洲解析承接，保不完整行、原备注、金额、位置及计数；亚洲 quoted reader 反例原样独立保留。PDF 原加密字节三密码仍由三十八原向量核对，本地密码失败只留元数据、替换及恢复由真实本地入口验证，不保旧会话 TTL／文件名脱敏合同。PDF／XLS 解析期间另一真实连接均须可取得工作区锁，服务端只鉴权且无财务 SQL。旧 Context 测试已整体退役；私有维护 owner／workflow／revision、文件上限与 TTL 继续由实际维护 store 验证，旧 ip_ resolve=200 不复活。当前真实 SQLite Task 覆盖读取中冻结及完成两个交错：迟到文件 plan_changed，原文件／review／冻结键正文或永久归档与重开结果保持且不额外发送；解析后权限由现 PDF／XLS 六组合及原会话守卫保留，清理后 committed 只沿永久原收据恢复。旧上传、文件变更及会话展示生产链已删除，parse-only 共用的 InputError／Upload 原样独立保留；当前准备分组／建议与选择／格式错误、完整文件变化至写集及实际 Task 详情直接验证，旧删文件后的 entry_choices 清空由当前 Review／SQL／Task 承接。当前完整计算、正式写集及维护保持。原业务规则二十九项的八项历史迁移证据未通过，须逐项核对基线与合同，不能当作全部废弃、删弱断言或计为通过。内部剩余旧编排及仅供导入的暂存能力尚待完整删除，不把本片完成视为 4.4／4.5 完成。

独立保存和草稿合并的当前重导测试使用合法银行无编号、不完整覆盖的两个导出，经完整计算与正式写集自然产生歧义、候选及当前修订，不手设问题或补旧绑定。维护走实际规范 HTTP，先核 resolve 后仍为草稿、合并保目标原状态／余额／手工空备注／绑定转移／来源软删，再实际清扫暂存，以原键重放完整收据且全库不变，最后重导命中保留 ID。旧两族仅退役重导尾部及专用 helper，维护主体与原输入／只读黄金保持；草稿目标重导是新增覆盖，不能代替维护后仍为草稿的保证。

当前 Task 的公共字段编辑使用同次完整计算提供的 typed 目标和最小上下文，首次选择不能只查已选目标的持久恢复输入。forPreview 私有闭包深复制这些值，按原四类选择归一后仅冻结本次已选目标；不捕获完整计划、不猜 JSON 标量类型、不扩大持久化。未知／失效引用和不可用分类在保存前失败，保原计划及核对；明确清空及重开仍沿实际恢复规则。当前双库完整文件合同及实际 Task 承接旧 EntryChoices 十一包装；原输入／黄金保留，DataFixture 继续使用，旧 API fixture 随唯一使用者删除。当前导入详情只读，不能以内部编辑测试宣称页面编辑入口已接通。

旧 CashImportHttpService／CashImportCommandService／CashImportCommitWriter 及仅服务旧协调器的提交／HTTP／寿命包装已退役。当前完整 producer 与正式 Submission 承接多行 alias／人工值／精确余额、四成员退款整组确认及暂缓、已有来源真实更新后第二条实际关系 INSERT 的原子回滚、草稿组件替换及原 ID 换序；不恢复旧 session 消费、plan resolve、物化版本或批处理 SQL 顺序。Outcome 保留无 SQL 与现行结果规则，仅去旧反射；维护预览及 RecordWriter 只移除旧导入调用，共用维护边界／解析／永久收据仍保留。原输入、Python 黄金及旧源码摘要在仓库外保持，退役捕获 producer 不能作为当前可重生成联调。ParsedImportStore 的导入专属快照、计划读写、公开页缓存及 codec 文件字节复用／plan-only 解码已退役；维护计划继续使用通用完整编解码、保存、读取、替换、取消及原 TTL 清理。旧批量写入准备器及 new rows 仓储亦已退役；完整多行 producer 明确核一次账本版本推进与收据同版本，实际 SQL 后故障由正式写集 HTTP 核整体回滚和原键重试。旧候选集合签名／批量阈值与已被当前墓碑身份规则替代的拒绝协议不复活。saveEmptyPages(files) 已退役；三十九个历史存储样本使用明确空文档证据，原错误／完整状态／顺序及版本／快照／跨表回滚保持。五个文件筛选 wrapper 样本不计当前后端通过，由真实 common producer 覆盖占用页、skipped 行仍占页、子集、定位及计数；原材料不改。保留正式 saveEmptyEvidence、共享来源组规则及维护暂存。剩余物化依赖仍待独立清理。

本地 XLSX 没有来源提示时沿原 `collectRows` 唯一表头识别，不提前拒绝 null 或猜文件名；显式不支持来源及 ZIP／XML／公式预算保持。四格式 Web 回归读取原实际文件、真实登录并核对 206 条事实，恢复仍有效的账户选择，冲突同名组按原规则重新选择；不调用旧计划或上传 CSV／XLSX，不把定向通过当作全套 Web 验收。

生产导入使用 `LocalCashImportLifecycleRepository`，每个原会话发表只捕获一次原 owner，同批次唯一协调器。Graph 按确切 Session 实例更新仓储寿命；新登录／工作区切换先以该响应凭据准备 owner，再发表 Session。首次文件读取前等待原批次恢复关联，空材料批次恢复真实空准备，永久结果存在时拒绝取消。账户选择及恢复准备的缺省补读使用 `selectionAccount`；编辑／撤销仍读真实 API，不能把缓存 can_delete 当作真实资格。

已确认成功后的结果归档、交付、刷新或清理故障使用原批次与已知收据的完成边界；页面保留成功统计及原异常，只能显式恢复原批次，不重调失败交付、不再 POST 已成功命令。raw 收据不可覆盖已补齐的 display 统计，二者权威身份和版本必须相同。文件读取故障与 parse-only 网络故障分别处理，存储失败不得伪装成单文件格式失败。登录／注册 401 不证明原令牌失效；退出在清理准备前固定原凭据和目录代次，取消也须关闭或保留确切原资源，不扫描后来 owner。

稳定核对输入使用 `LocalCashImportReview`，只保存四类原选择、最小关系上下文及所选 entry 的完整依赖。原输入 JSON 数词直接冻结；目标中的 CashAmount、CashDecimalLiteral 和 Instant 用明确内部类型编码，不猜测普通字符串。恢复得到独立输入／previousPlan／recordIds 后仍须走真实原操作和预览规则；不从完整展示 JSON 复活可执行计划。存读沿同一加密批次行、互斥、范围和会话围栏，准备原提交后拒绝改选择，不能以持久输入存在宣称完整重派或页面接通。

确认时使用同时接收快照和最终写集的 `LocalCashImportReview.freeze` 保存完整结论指纹及已选对象的最小业务定位摘要；旧版恢复的 `conclusion()` 为 null，不能用当前缓存补造原确认。新版仍只保存原输入、最小上下文、所选目标及摘要，不保存完整计划。原提交准备后沿原批次冻结边界拒绝改写；结论存读本身不授权重试，完整比对及原键核实须另行组合。

稳定选择使用 `rebindRelations`、`rebindEntries` 两段恢复：先从新计算组唯一定位原配对及候选，合并新旧历史的最高失效代次，沿原选择规则生成引用，再在配对生效后的完整成员中定位条目编辑。明确公共值、清空、原输入数词及失效选择保持；缺失、多义、更高失效代次或旧编码缺少定位摘要返回 `plan_changed`，不能丢弃选择或补造原绑定。两段限同一快照和原确认对象，typed 目标接收及返回时均深复制。JSON 端口使用 `inputJson()`，计算规则使用 `input()`；不能用通用编码器重建原 JSON 小数。该准备器不改变原确认、不提交；生产计算已接内部缓存 owner 预览及显式恢复入口，本地生命周期与页面已接显式恢复，离线状态呈现与重新联网完整流程仍待完成。

`LocalCashImportReview.recalculate` 已组合无配对／条目选择、恢复配对、恢复条目编辑三次原计算，显式传递同一快照、各阶段上下文和原 occurrence 编号，最终比较真实完整计划及写集与原确认。计算入口必须每次生成独占的完整计算计划，不能用展示 JSON、预设结果或上次已修改的预览对象替代。返回的 `conclusionUnchanged` 仅对应本次计算结果，不是持久重试许可；后续调度仍须核实原操作并沿原提交冻结边界消费结果。这个组合不执行发现、HTTP、写库或换键；完整解析后文件计算已接内部 owner 预览及显式生产恢复入口，本地生命周期与页面已接显式恢复，离线状态呈现与重新联网完整流程仍待完成。

内部 `LedgerSessionCacheOwner.resumeImportSubmission` 可显式接收完整生产计算入口，只有实际或已保存 POST 基线拒绝才进入重派；刷新完整缓存版本须严格超过旧请求基线，随后再次查原键，成功收据优先，只有明确不存在才重算。完整结论相同后沿原批次单行条件更新递增尝试序号及冻结新请求；结论变化或预期计划输入失效只退休旧请求，保留文件及核对选择。其他异常保留原尝试与诊断。新请求必须先持久化再 HTTP，结果未知／取消按该新键恢复；每次调用最多自动重派三次并退避 250／500／1000 毫秒，触及上限保留最新请求。序号不可复用或回绕，单次拒绝不证明未来基线永远不能提交。`resumeLocalCashImport` 已绑定完整生产计算，底层默认入口仍只恢复原请求；页面已接原批次显式恢复，离线状态呈现与重新联网完整流程仍待完成，不能用近似发现器补位。

内部 owner 的 `captureImportFiles` 只创建全部成功解析的稳定输入：首挂起前固定文件列表，核原缓存身份，读取及远程解析不持有资源锁，逐份沿原单次有界捕获，全部成功后原子保存。PDF／旧 XLS 显式使用原 `ApiCredentialSnapshot`，不在文件读取后取后来登录；范围、关闭和取消围栏继续生效。异常不保存部分文件，不吞程序错误或新建失败项；该成功批次入口仍不保存失败项；逐文件准备使用下述独立入口，本地生命周期及页面已接逐文件准备，离线状态呈现与重新联网完整流程仍待完成。

核对只读规则由 `CashImportPlanViewRules` 统一裁剪、分页、引用展开和可读校验；服务端只留原整数／组件身份／记录值桥。`CashImportMonthlySummaryRules` 保留原全量行分组及顺序，backend 使用原 BigDecimal／时区桥，本地保持 80 位 HALF_EVEN 和 scale。`LocalCashImportCalculation.reader(planId)` 接收生命周期实际计划身份，读取独立冻结 JSON，保留开放数字 token 及 legacy UTF-16；不要经全局计算编码器改写这些只读值，也不要补 TTL／修订或以读取成功授权确认。实际平台日历沿既有时区集合及固定 EST／MST／HST。本地任务的 `expires_at=null` 可沿可空的 `CashImportPlanDto`／`ImportPlan` 进入页面模型，已有非空时间原样保留；不补 TTL、不改全局 JSON 配置，也不以该字段授予确认资格。`LocalCashImportTask` 已绑定单个批次与原 `LedgerSessionImports`，组合准备、预览／保存、读取、冻结及恢复；生命周期持有者须保证同批次唯一协调器。解析在锁外，文件保存与预览串行，runtime 修订只随真实变更推进，重开不恢复旧计划。新 review 开始落盘前废弃旧计划；生产重派已退休请求或调用方取消了该结果交付后，旧计划均不得再冻结。普通结果未知保原请求，取消可在首文件解析时或事实陈旧时封闭任务，不访问账户准备。成功恢复可提供 `beforeCompletion`，在原收据持久化后、完整刷新及批次清理前保存固定原范围的恢复引用；失败保留权威成功及材料，取消继续传播。刷新或清理失败后显式恢复可能再次交付，调用方必须按同范围及同收据幂等保存。页面确认／核实通过 `CashImportLifecycleRepository` 的原命令及交付回调保存收据引用；新写集收据必须保持 `planId=null`，仅以仍在处理的相同冻结命令或确切恢复收据 ID 接受，不补造旧计划 ID。迟到交付须取消，不得返回成功授权清理；保存失败保留已知成功及原诊断，重复交付按同收据幂等保存。本地上下文显式标记 LOCAL，所有上下文保存均使用同一私有引用选择：本地保存 v2 LocalBatch(batchId)，远端保旧 v1 Session；不要在准备或预览后把本地引用覆盖回旧会话。LocalBatch 只定位原加密批次，恢复沿 resumeBatch，原实际尝试键及正文不得另存或伪造运行时计划。hasSubmission 统一编辑、结果及退出围栏，明确未提交的本地批次仍保留原批次恢复按钮，实际恢复可编辑上下文后才清命令及恢复身份。Graph 与本地生命周期仓储已接通显式导入／恢复，离线状态呈现与重新联网完整流程及完整结果页面仍待实现。

文件变更使用原 owner／会话入口的 `appendImportFiles`、`replaceImportFile`、`removeImportFile`；追加按最新列表合并，替换／移除核原文件完整编码，解析不持资源锁。原 SQL 行同时保存文件与 `LocalCashImportReview.afterFilesChanged` 的失效输入：保留账户／关系选择及最小历史，过滤被替换／删除文件的行选择，清除条目编辑与旧确认结论。精确无变化不清除新选择；已有提交或收据一律冻结。移除最后文件保留空批次及尝试序号，空批次不得准备提交；不要用删除再重建同键绕过恢复规则。内部能力不替代完整页面验收。

逐文件准备使用 `parseImportFile` 与 `savePreparedImportFile`：解析沿原凭据在资源锁外执行，保存沿原追加／替换、身份和提交冻结围栏。成功文件保留 v1，只有已声明的格式／密码解析失败成为 v2 元数据状态，不保存失败正文或密码，重试须提供本次文件。401／403、网络、取消与程序错误继续传播。`importPreparation` 在同一 owner 锁内读取文件、事实和原 review，复用原文件视图、资金扫描和预选，保留明确账户选择；显示名只去路径并限 255 码点，平台组沿原摘要。事实陈旧时可存文件但不得准备账户视图；已有提交拒绝准备，不补 TTL、计划或修订。该入口已接本地生命周期与页面；离线状态呈现与重新联网完整流程仍待完成。

账户预选使用共享 `CashFundingSuggestionRules`；SQL 仅提供原分批读取，端侧 `suggestLocalCashFundingAccounts` 读取同一完整快照，owner 的 `importFunding` 从同范围文件扫描资金组并返回预选。当前映射优先于适用旧映射；映射存在但目标为空、缺失或不合资格时不能回退别名。微信不预选，没有映射才按原完整 ASCII 数字账号与唯一 active cash／loan／lend 候选预选，不以币种过滤。它不保存选择、不发布映射或恢复额外账户映射证明合同，页面上下文已接本地准备；完整离线验收仍待完成。

应用层从 `SessionCacheRepository.captureImports` 捕获原 `LedgerSessionImports`，不直接取得缓存 owner 或后来登录；在线／离线均沿原 restoration 和发表代次。资金／计算须核事实可读，文件／review／原键恢复不能被陈旧事实门阻断。恢复开始沿原 refreshEpoch 封闭事实，只有同发表同 epoch 且无 recoveryFailure 才开放，不能把权威成功改成普通失败；调用后换代仍不得发表到新会话。入口先固定可变列表、JSON 和请求再等待 guard，互斥只核资格、不跨 HTTP／计算。真实 401 动作异常复用原 withRejectionCleanup，合成 401 不触发清理。仓储在异步工厂前登记原 token 的在途恢复，实际 owner 登记、发表及拒绝均在同一锁内；拒绝时先封闭同 token 的发表与在途对象，再在锁外关闭、条件清 token 和擦除，各项故障不能互相跳过或替换原拒绝。关闭失败保留原对象，synthetic cancellation 不能丢失资源，真正调用方取消仍传播。令牌清理观察现有目录代次，在同一 LedgerStorage 实际租约内核原 token 并 clear，匹配后失败保留该 storage，不能释放探针后再以另一租约擦除，也不能推进全局代次伤及新 reader。生产 Graph 与本地生命周期已使用原句柄，离线状态呈现与重新联网完整流程仍待完成。应用会话的本地 reader 对在线／离线统一提供列表、现金账户、目录及完整详情；Graph 使用 LocalFirstFinanceRepository 与 LocalCashMaintenanceRepository，缺事实明确 projection.unavailable，不回退旧物化读取，跨已捕获刷新才 read_snapshot_changed。来源证据与全部记录同次捕获，完整 DTO 在 business-contracts 唯一声明，能力提示取原绑定角色。CashBusinessOperations 在首挂起前固定原发表及 immutable generation，给命令同一固定 API／刷新回调，动作前后核范围并复用精确 401 清理；API 视图共用 HttpClient、transport tracker 与 credentialMutex，不拥有共享关闭权。X-FT-Workspace-ID 只守预期范围，服务端及实际事务均核当前鉴权，不授予权限。分类／删除沿原字段解析稳定条目，版本直接核 ledger_seq，各自输入语义保持；维护默认协调规范成员。用户显式 refresh 同步事实，reload 只重建列表；仅原可读、同 owner／代次／epoch 的明确 transport loss 可保缓存，已知写入后封闭不能重开。离线状态呈现与重新联网完整流程、完整逐项结果及旧路径退役仍待完成，不能因此关闭整个 change。

写入效果分量使用 `localCashImportWriteConclusion`：typed DTO 只剥除观察修订、临时引用和依赖元数据，完整保留来源、候选、C／有序 B／最终条目、人工标记及独立值。仅存活且完整非空渠道与 record_id 使用原唯一业务键；其他合法 Existing 来源（含旧候选中的已删除记录）回退实际 ID，New 不得伪造身份。财务金额／时刻只在明确字段归一，开放 JSON 保留原数词和普通字符串。它不包含未写入行、文件失败、完整候选组合或失效选择，不能单独作为静默重提交许可。

完整核对结论使用 `localCashImportConclusion` 组合该写入分量及原完整计划的行／文件结果、展示与条目明细、完整联合候选、类型效果和实际失效选择。失效选择按稳定成员、动作、明确公共值及原因比较，旧建议／候选／commit 编号和纯历史 generation 不进入当前效果；这些状态仍完整保留在原恢复上下文，不能复活已失效选择。只读参与者经旧条目、活动关系和已有候选的索引队列收齐后统一编码；旧关系业务引用缓存避免重复大 evidence 展开。开放原文和诊断不按同名键改写。它仍不执行发现、重绑定、提交或队列调度，不能将比较器存在写成静默重派已完成。

写集收据统计消费原命令的真实 before／final facts 与身份命中：来源按操作、关系按完整选择组计数，已消费关系组仍返回 duplicate；D 在协调后的实际 patch 前固定标量 revision，只计这次真正推进的对象，包含仅人工标记变化，不把 C／B 或协调当成 D。has_business_writes 取该事务事实版本是否推进；关注父数只取此次涉及集合，待处理记录按真实完整 entry 成员去重，其他成员有旧问题仍使整组待处理，但不扩此次关注父集合。复用当前事实，不读或写物化投影，不从身份数组长度、预览数量或后来快照补造首收据。未发送的文件失败、跳过和批内重复由本地原批次结果分量补齐，不新增服务端证明字段。

本地任务确认时从同一完整计划冻结最小结果分量，随原提交保存；排除已有提交操作代表的 duplicate 行，批内别名仍按行补计，不能采用预览成功统计。别名和失败父项以明确操作位置连接实际收据身份，不解析 opaque ref 或保留整份计划。实际原收据先保存，再沿原凭据与 workspace_id 分页读取永久逐项结果，核明确数组位置、真实身份、outcome 和完整覆盖，不保存服务端当前投影引用；同原分量幂等归档到同一加密库，之后才交付、刷新和清理；补读或归档失败保留权威成功、原异常及材料，恢复不重发已成功命令，已有完整归档不重复补读。仅保留原提交的旧记录可在 base 完全相同且缺 serverItems 时 CAS 补齐，已清理的旧批次不宣称可自动补页；真实 401 继续沿精确 rejectedToken 擦除，不因已成功而保留已知失效缓存。原收据和派生展示分开，后者不能回写 withReceipt。结果按原范围的收据、批次或实际尝试键读取，换快照与保留式关闭不删结果，显式擦除同库清除；归档后的批次编号不可复用。重派原样继承分量，旧无分量提交保持原收据行为，不用后来预览补造。文件识别行数保留合法 null。该结果能力已接 Graph、本地生命周期及实际结果摘要；当前页定位从同一可读本地快照的完整成员索引派生，非主成员也定位稳定条目，实际版本不取原收据。原身份有效但事实资格关闭／跨epoch仅去掉定位，缺失身份仍拒绝，程序错误不能吞作空定位；VM保留非零版本下限，v0清定位但不丢永久明细，不得复活旧定位。完整逐项页面和跳转交互仍待完成。

账户准备复用现有账户 API 的可选 `workspace_id`，服务端路由及真实事务均核原工作区，检查在收据重放前；币种批次先核授权，每项独立提交继续核同一预期。内部 `ImportAccountApi` 显式绑定原 `ImportAccountScope`，固定凭据及地址并在首挂起前冻结 JSON 正文，不清后来令牌。账户永久收据沿原键／ID 通用读取，不套写集专用 kind；真实删除资格仍由 get 返回。旧 public 未带 scope 调用保留。该传输已接原 `LedgerSessionImports`／owner：列表从同一可读缓存读取并保留 inactive 及原币种语义，详情及命令沿原 scope；可变参数在发表守卫前冻结。账户成功与已提交收据复用同次 active／generation 捕获的刷新器，原回调与其他写入共用 refreshEpoch；刷新失败留诊断、封闭陈旧事实且不覆盖成功，原收据若带可选 ledger_seq 则传下限，不能假定账户收据必有该字段。真实 401 沿原拒绝清理，不清后来令牌；换令牌或关闭先被原守卫取消。直接底层句柄缺刷新能力时不得发账户写入。生产 Graph 与本地生命周期已接上述账户端口，选择及币种预检补读使用原可读本地事实，真实编辑与撤销继续联网读取实际详情；完整账户流程验收仍待完成，不新增来源／账户映射证明。

内部 `CashClassificationService.setEntryCategory` 已用同事务鉴权与 ledger_seq 校验规范条目，`CashEntryRepository.setCategories` 复用共享 patch 并批量保存公共值／单例独立值，无物化表或启发式计算；已接真实分类 HTTP 和本地账本页面。版本直接使用 ledger_seq，不回填或换算旧投影版本。旧读取路由及物化表退役仍待完成。

内部 CashDeletionService.previewEntries／deleteEntries 使用规范条目及 ledger_seq，预览同快照校验并返回前复核原范围，执行沿工作区锁与事务内重鉴权，完整成员及确认闭包变化拒绝扩大选择。复用原引用／余额规则，完整组用 removeCompleteEntries 按 400 集合删除并核数量，保留原溢出／故障边界；旧部分成员路径不变。过渡期仅清理必要物化外键，不读 active dataset、派生或发布投影，表退役时须移除该清理。真实删除 HTTP 与本地列表／详情已同组切换；旧读取路由及物化表退役仍待完成，不能据本片关闭整体验收。

无文件维护的内部 MutationPlanService(canonicalEntries=true) 复用原预览／重验／事务／收据，不依赖 active dataset；生产 Graph 及 HTTP 默认已设为 true；内部旧兼容分支仍须随旧路径退役删除。受影响条目协调交替展开原规范组全部成员及当前 confirmed 闭包，保留拆分两侧及退休源清理；显式继承时公共人工值成为拆出对象的独立值，不继承时恢复各自独立值，来源原字段均不改。pending 按同组全部成员的草稿／attention 去重，不只统计成功父项。新收据 ledger_seq 与兼容 projection_version 取同一真实账本版本，旧收据原样重放；不换算版本或新增镜像状态。

LocalCashLedgerQuery.currentGroup／evidence 复用同快照稳定条目与 ledger_seq，保留完整成员修订／确认状态、隐藏证据、实际组件端点、非生效关系、退款及安全资金字段；账户／分类仅编码原证据字段，序号保持 Long。证据 projection 的分类仍受原成员／端点来源分类目录资格限制，不顺带改变分页或公共分类行为。重复证据按同快照父记录索引读取；应用详情／证据端口、在线列表及规范维护已同组切换；完整离线验收及旧路径退役仍须分别完成。

LocalCashLedgerQuery.record 以明确 canWrite 生成同快照完整流水详情，只读提示不授权写入；字段、原文数词、公共条目、原备注／令牌、问题／候选与关系沿原合同，定位仅用 ledger_seq。身份绑定按同捕获的完整业务键与证据键索引反查，不从来源正文重造；manual_overrides 的真假沿旧 BigDecimal 指数／scale 域判断，不走财务金额。内部策略的组件序号用 SourceJsonInteger 保持原 Int 准备语义，展示仍为 Long。应用端口与页面已接本地读取，完整离线 QA 及旧读取路由退役仍须分别完成。

**Store selection:** If the user names a store (a store is a standalone OpenSpec repo registered on this machine) or the work lives in one, run `openspec store list --json` to discover registered store ids, then pass `--store <id>` on the commands that read or write specs and changes (`new change`, `status`, `instructions`, `list`, `show`, `validate`, `archive`, `doctor`, `context`, `view`). Other commands do not take the flag. Hints printed by commands already carry the flag; keep it on follow-ups. Without a store, commands act on the nearest local `openspec/` root.

**Input**: Optionally specify a change name. If omitted, check if it can be inferred from conversation context. If vague or ambiguous you MUST prompt for available changes.

**Steps**

1. **Select the change**

   If a name is provided, use it. Otherwise:
   - Infer from conversation context if the user mentioned a change
   - Auto-select if only one active change exists
   - If ambiguous, run `openspec list --json` to get available changes and ask the user to select one

   Always announce: "Using change: <name>" and how to override (e.g., `$openspec-apply-change <other>`).

2. **Check status to understand the schema**
   ```bash
   openspec status --change "<name>" --json
   ```
   Parse the JSON to understand:
   - `schemaName`: The workflow being used (e.g., "spec-driven")
   - `planningHome`, `changeRoot`, and `actionContext`: planning scope and edit constraints
   - Which artifact contains the tasks (typically "tasks" for spec-driven, check status for others)

3. **Get apply instructions**

   ```bash
   openspec instructions apply --change "<name>" --json
   ```

   This returns:
   - `contextFiles`: artifact ID -> array of concrete file paths (varies by schema - could be proposal/specs/design/tasks or spec/tests/implementation/docs)
   - Progress (total, complete, remaining)
   - Task list with status
   - Dynamic instruction based on current state
   - Optional `context`: current required project instruction input from the selected root
   - Optional `operationGuidance`: current advisory guidance for apply

   **Handle states:**
   - If `state: "blocked"` (missing artifacts): show message, suggest using openspec-continue-change (if it is not installed, run `openspec status --change "<name>" --json` to see the next artifact and `openspec instructions <artifact-id> --change "<name>" --json` for how to create it)
   - If `state: "all_done"`: congratulate, suggest archive
   - Otherwise: proceed to implementation

   Treat `context` as a required prompt-level input. Read and consider it, and
   apply relevant project facts, conventions, and constraints while implementing.
   Treat `operationGuidance` as optional additive advice. Read and consider every
   entry, and follow entries that are applicable and compatible with the built-in
   workflow.

   Keep both fields separate from CLI-returned state, missing artifacts, tasks,
   progress, `contextFiles`, and the built-in `instruction`. They are not
   evidence of task completion, do not replace the built-in instruction, and do
   not permit bypassing a blocked state. If context conflicts with the built-in
   instruction, an explicit user choice, or a CLI-controlled value, report the
   conflict and preserve the controlling value. If guidance is inapplicable or
   conflicts with those controlling inputs, do not follow it and explain why.
   These are prompt-level behavior contracts, not enforceable checks.

4. **Read context files**

   Read every file path listed under `contextFiles` from the apply instructions output.
   In this repository, also apply the product boundaries in
   [AGENTS.md](../../../AGENTS.md#个人记账的冲突与输入边界) before adding conflict,
   validation, compatibility, or recovery branches. If implementation requires a
   different boundary, update the controlling change artifacts before coding.
   For an approved UI prototype, retain its information hierarchy and workflow
   alongside later user constraints. Browser checks must compare the rendered
   layout as well as interaction: presence of a component does not establish
   prototype parity. When reusing a ledger table in a selection dialog, verify
   readable amounts and shared header/row geometry at the actual container width;
   compact card reuse must keep the full-screen dialog contract.
   Keep complete suggestion choices at the plan level: member rows are read-only,
   while manual search selects a single counterpart. Follow the active change's
   explicit suggestion/search navigation instead of showing both steps at once.
   Preserve the selection return target across conflict resolution. Relation reviews
   show resulting records even without additional effects; rejection and removal
   prepare read-only previews inside their confirmation view before one final save.
   The files depend on the schema being used:
   - **spec-driven**: proposal, specs, design, tasks
   - Other schemas: follow the contextFiles from CLI output

   Do not copy `context` or `operationGuidance` verbatim into implementation
   files or planning artifacts unless the user separately asks for that content.

5. **Show current progress**

   Display:
   - Schema being used
   - Progress: "N/M tasks complete"
   - Remaining tasks overview
   - Dynamic instruction from CLI

6. **Implement tasks (loop until done or blocked)**

   For each pending task:
   - Show which task is being worked on
   - Make the code changes required
   - Keep changes minimal and focused
   - Mark task complete in the tasks file: `- [ ]` → `- [x]`
   - Continue to next task

   **Pause if:**
   - Task is unclear → ask for clarification
   - Implementation reveals a design issue → suggest updating artifacts
   - Error or blocker encountered → report and wait for guidance
   - User interrupts

7. **On completion or pause, show status**

   Display:
   - Tasks completed this session
   - Overall progress: "N/M tasks complete"
   - If all done: suggest archive
   - If paused: explain why and wait for guidance

预览状态下沉保留原图构建的 copy 注入边界、正式图后追加草稿的顺序和原始异常。局部边先按父项筛选，再由新增边按精确 ID 覆盖；资金在预览 build 中沿原最后记录选择，不得混同上下文读取的首条选择。只有 values 明列的现金部分及其资金占用进入构图；状态与 build 共享不替代操作叠加的独立验证，也不代表初始本地读取、条目公共值准备或完整端侧预览已接线。

关系组图应用下沉保留确认闭包提交组及自动候选撤回／显式拒绝的区别，不把 CashRelationFailure 之外的程序错误转成跳过。拆分恢复只在选中时读取成员，且在成员及事实存在后才解码独立值；当前删除的继承缺省、公共字段冲突转待定、applied_amount 回填和原回退边界均须用完整原生预览用例验证。规则与边工具共享不等于端侧建议组、初始本地状态、条目公共值、完整预览或页面已完成。

预计事实操作叠加必须保持原创建时刻、稳定来源键、精确虚拟 ID 与明列现金部分。类型修改先按原角色条件撤销不兼容关系／资金，再校验旧确认闭包；业务图失败恢复原值、事实、部分、关系、资金及删除集合，移除整条操作并生成原跳过事项。JVM putIfAbsent 对已有 null 亦补值，common 不得只判断键存在；程序错误仍传播，manual_transfer 沿原类型清理豁免。原完整类型修改／预览与只读黄金必须保留，共享 overlay 不等于初始本地读取和完整预览已组装。

冻结核对结果共享必须保留完整 member／relation-member 字段边界、触及范围、排序与 stable entry 目标；虚拟现金部分的隐藏边界沿原精确 2^63 比较，不窄化为 Long 或浮点。文件结果的公开字段、密码状态、诊断深复制与 can_commit 沿原原生用例。纯 renderer／文件视图不读取账本，且不代表端侧业务计划和核对页面已接通。

建议摘要证明中的已知候选数量使用显式整数标记，固定完整组 hash 与独立 1／1.0 对照防止跨端漂移，不以自比确定性代替。关系建议组共享保留活动边业务键去重、新建／转正式种子门、旧确认闭包及精确依赖，候选按原顺序最多尝试 2000 组且保留最多 20 个有效结果。仅原 CashRelationFailure 可隔离单个提案／候选；金额解释、稳定摘要、BigInteger 父项边界及微信原消费决定须沿真实平台桥验证，不能以 test-only 配置宣称完整端侧发现／预览已完成。成员及候选组合继续通过真实 snapshot.build 校验完整影响图。

预览准备共享保留两层授权守卫时点、来源字段增强及输入只读；保留编辑目标失效先于读取账户，候选仅接收新建正式／草稿转正式操作，再合并已有记录与额外种子加载关系上下文。保留目标可有虚拟引用但不擅自改原 Long 种子合同。Prepared 模型及读端口共享不替代真实候选查询、初始本地状态或完整端侧预览的验证。

条目公共字段准备复用共享条目模型和合并规则，保留同条目按操作顺序合并补丁、非手工字段继承根流水及缺根回退。仅解码被标为手工且实际使用的字段，不提前解码无关条目。读取集合仍为叠加前的存量父项；新操作使用 new: 标识，手工字段取原集合并按 Python 顺序排序，程序错误不转为导入跳过。

本地预览存量值按同一完整快照及关系上下文 transactionIds 读取，包括草稿和已删成员元数据，不因构图资格再缩范围。来源范围以单一账户为先；多个账户不得被单文件范围掩盖。现金部分用 context 的版本与顺序，附精确来源键；保留原始值类型和只读输入，以原 SQL 无操作预览的完整 values 对照验证，不用局部自比替代。读取入口不代表虚拟事实、条目读取或完整工厂已组装。

本地初始预览工厂保留 2^64 偏移及 128 位摘要的完整虚拟整数，使用数值排序并按 JSON 数字编码，不能窄化为 Long 或按字符串排序。工厂组合原正式事实、有效草稿、操作叠加和条目继承，条目读取集合必须在叠加前固定；时钟显式注入。新增生产配置需对照所有原始完整预览 case，保留既有黄金和全库只读断言；该工厂不等于完整发现、计划、提交或页面已完成。

本地建议组须使用真实预览整数与计算型操作，不把已计算时刻或金额先序列化为普通字符串再求指纹。JSON 入口不猜测字符串类型；计算型工厂入口直接深复制规划结果。组配置用原生全部提案 case 的完整 sg／候选／失败及计算摘要独立对照，不改旧 hash；微信原消费保留来源、类型、负数及状态门，状态嵌入金额与退款计算值不套流水列限。外部提案组入口不代表商户发现、候选召回或完整核对计划已完成。

本地呈现配置须从真实工厂状态构建完整图，使用精确虚拟部分门和原 entry 数字／字符串回退排序；本地图应用和原生全部呈现 case 比较完整状态及结果，不以数量或归一金额替代。平台专用读取先用标准化字段，缺失才按来源映射回退原始 JSON；通用候选／配对只消费提取结果。呈现入口不代表选择复用、拆分读取、发现、完整计划或页面已完成。

本地候选读取保留 provider 绑定与账户受限原文回退的独立条件、冲突来源范围及支付宝前缀；平台字段映射与归一共用，标准化两键齐全时不得解码原文。用真实不可读 JsonObject 回归按需读取，需回退时程序错误仍传播。原 14 个完整候选 case 不是全日期域证明；极值时间窗须核对真实旧查询及绑定，不能仅凭内存 SQL 探针改业务规则或宣称全域等价。

本地预览准备绑定同一快照，保留显式活动守卫和先校验保留目标、再读账户／候选／上下文的顺序；账户修订不窄化，计算型操作不经 JSON 回包。原生已增强操作的依赖及工厂对照不等于原文件全管线证明；平台来源行的 provider／scope 回退必须穿过真实本地候选读取，不能只用固定端口结果代替。

内部 CashEntryProjectionGroup 只抽取原 projectionId、根、成员及经济根，不提前执行标识转换或改排序；原生 reconcile 仍通过同一核心。草稿结果协调不伪造发生／创建时间，内部计算结构不承担图资格、完整范围或写入授权。完整闭包用于发现遗漏，不能直接扩大原选中成员索引；成员退休与空条目删除仍须沿真实原调用顺序验证。

合并前公共选择复用 CashEntryEditRules.chooseCommon：布尔返回仅控制原 flush 门，非空补丁配空父集合仍 flush，空补丁不 flush。保留先归一和类别、逐父惰性读／缺失时创建 singleton、同值及同一条目不同父各增修订，且不改 standalone。原仓储固定 UUID 对照证明无人工冲突也可能因预建 singleton 改稳定身份，完整效果核对不能只前置冲突字段；普通 patch 仍在其原时点处理，未接线不能宣称完成。

内部 validateCashWriteSetEntryTopology 复用同次确认图及精确父引用，草稿只做无时间单成员组；闭包种子含绑定新旧父、显式业务分组及确认图变化。按成员索引／队列展开旧条目完整成员与最终组，核完整业务分组、根及全部受影响旧条目修订依赖，并返回受影响旧条目；只读依赖本身不扩写范围。显式空条目拒绝，无关未物化事实不强修；不接收客户端 winner 或身份锚。文件专用门另核全部旧成员与真实身份锚存活且同一最终组。两门不推导人工／独立值或执行退休，不能当完整写集合法性／授权。

内部 normalizeCashWriteSetEntryValues 仅做公共值、行前置补丁、关系选择公共补丁和完整五字段 standalone 的输入归一；关系 common_fields 表达端侧核对后生效选择，空对象仅在原扫描范围无需公共人工字段时有效；原始用户补丁仅保留端侧重算，服务端不按实际新 ID 重选默认首值。attrs 先验数组与字符串元素，不递归解码未知嵌套对象。manual／edited 只允许公共白名单，最终条目不接受 split_inherit 字段；通用维护用独立选择原样另验，文件门拒绝该动作。合法历史 flags 可因分类清理追加而未排序，后续效果比较须按集合解释；历史 standalone 分类此层只验类型。归一和最终客户端标记不能替代真实旧状态、人工意图及来源效果验证。

内部 validateCashWriteSetEntryResults 仅对照真实协调及后置公共编辑后的完整 owner 事实：实际根、完整成员、五公共值、人工字段集合及每成员完整独立值／标记。解析只用服务端实际分配映射；旧修订／拓扑先用写前事实验证，不能用写后版本补依赖。实际字段不重归一，仅 flags 按集合解释，两个逻辑引用映射同一实际成员或重复条目须拒绝；程序错误原样传播。它不写入、不反授权，也不补足未声明退休／孤立对象和完整事务门禁。

真实 applyCashImportWriteSetFirstCommonChoices 仅用于外层已确认整联合批次首次写入：同意图关系的整批去重须连同 B 消费，不能再次调用。先完整有效 B／批次校验及全部接受组父引用缓存、正数与别名检查，随后保留批次／组序逐组调用原 chooseCommon。组内端点 distinct 后按实际 Long 文本排序，不按 owner 或跨组去重，不额外 flush 或改 standalone；成功元数据分配与 checked 失败路径仍归服务端。完整旧组不拆、授权／图／幂等与真实关系写入由外层负责，函数存在不代表公开提交服务完成。

真实条目收尾 finishCashImportWriteSetEntries 必须在受管事务内、既有授权／写前依赖与 C／B 后调用。先一次解析并缓存全部根／成员实际标识，核正数、重复／别名、交叠及两侧完整分区，再复用原 reconcile 与普通 patch D；D 取已授权 edited_fields 和真实当前修订，不能回填旧依赖或用 chooseCommon 代替。完整写后事实结果不符或仓储／解析故障由外层 db.write 回滚。复用 facts reader 会完整读取工作区 15 集合，须纳入后续性能预算；此内部阶段不证明完整提交、收据或页面接通。

内部 prepareCashImportWriteSetEntryEdits 先校验每个原始 C 的旧 owner／修订／不存在观察，再按写前真实成员 owner 合并补丁至首个编辑项；后续同 owner 编辑去除，旧读集、行顺序和来源值不变，新／无 owner 不按 null 或文本凑组。与原操作规则共享 mergeCashImportEntryPatch，presence 明确区分 null／缺失；冲突仍 plan_input_invalid，保留原操作逐补丁标记和整组完成后替换时点。准备器只返回副本；真实 applyCashImportWriteSetEntryEdits 已在受管事务内消费，所有保留 C 的父项先解析并核正数／别名，再逐项传原旧修订或明确 null 给 applyEdit，不取当前修订修补旧观察。必须在尚未执行 C 的来源写入之后、B／协调之前调用，不能叠接旧 writer 内置 C；保留原仓储缓存及 flush 时点。完整来源／B／后置 D 与结果门仍须由同一外层提交服务组合。



内部 CashRecordValueWriter 只复用父项与组成项 DML，不执行 C、绑定、问题或候选失效。CashImportWriteSetSources 在受管事务中读取真实旧事实，先经共享来源准备门，保留来源标记／指纹及组成项隐藏值，再保存实际 ID 和余额前后值；组成项按 ordinal 比较，数值等价金额保留旧表示，null 与空解释沿原 no-op 等价。payload 在递归解码前核 64 层边界。新建／转正式核全部账户资格，草稿明确账户编辑只核明选非空 ID（同 ID 的各币种均核），明确 currency 核全部已选账户，旧正式账户保持冻结豁免；自动草稿缺项／不可用账户由完整问题阶段处理。旧 writer 保持原顺序并传必填删除前引用修复；新服务仍须组合来源身份、绑定、问题、候选、基线、完整关系及 C／B／D，不能直接信客户端操作映射。双库来源保存通过不代表完整写集端点已接通。

本地 XLSX 遍历须在保留部件前完整校验每个 XML／rels，按首根 worksheet 决定是否应用行列守卫，不依赖路径或活动表。未选中公式不作业务解释，选中表仍按既定公式边界处理。原 ZIP 中央目录、CRC 与累计预算保持；用完整有效账单加坏未消费部件复现，并核合法额外部件不改文档结果，不宣称与 StAX 所有编码／OPC 全集等价。

**Output During Implementation**

Web 缓存已确认自动解锁，不增加独立密码。ledger-key-vault.mjs 只管理范围隔离的包装密钥材料：read 缺失不创建，只有确认新数据库的所有者才显式 create；损坏不能换钥，事务提交完成后才返回，显式 abort 无错误对象时保留明确 AbortError。实际 OPFS Worker 固定官方 WASM、校验和与完整分发声明；2.6.1 新写入前关闭并核对 ChaCha20 硬件加速，临时数据留内存。租约获取失败须释放，清理先设关闭围栏再关库、删目录与密钥；中断恢复若称已落盘，须断言主库 SHA 已变化，非空 journal 不足以证明。内部 Worker 回归不替代 SQLDelight 工厂、完整 owner、跨标签协调或应用登出接线，不得因删密钥记录而关闭完整清理任务。

Web 工厂使用同一 webMain 与真实 WebWorkerDriver；原生 import 模块由 Webpack external 保持同源异步加载，Karma 使用实际应用资源。SDK 2.4.1 的 message／error 监听须成对释放，线程错误先关闭围栏再拒绝活动及后续请求；只有真实 close 成功确认才允许下一步擦除，错误／超时不能在重试时变成成功。取消打开须等待原 Promise 结果并关闭，不能遗失仍在打开的连接。测试专用故障 Worker 仅由 webTest／Karma 代理提供，不打入产品资源；这些内部工厂测试不代替登录生命周期、页面和跨标签协调。

Web 同步组合验证须消费真实加密工厂及真实本地页派生，明确 HTTP MockEngine 与后端集成的区别。撤权取证在清理前后分别核对目录和密钥的存在状态，不能让密钥正例提前短路目录正例。离线 page 不触发 HTTP，收据版本只能作为重新下载下限，不能直接推进事实水位。内部组合通过仍不代表应用登录生命周期或页面已接通。认证会话读取只把仍可访问的工作区作为活动工作区返回；已移除成员的持久指针归一为空，不写回会话行也不由服务端自动改选，客户端仍要求活动项属于返回列表，否则按坏响应拒绝恢复。Graph 接线不得再以“原样回显持久指针”为前提，登出／已知认证失效时仍须先封闭旧读取再定位并清理对应范围。应用会话仓库只记录最近一次经服务端核验的使用者摘要：退出先推进目录代次、关闭当前恢复对象，再以空保留集合清除该使用者范围并撤销令牌；未核验身份时不得删除。清理失败保留原清理对象供同一使用者再次退出重试，令牌撤销照常执行；双失败用 `DomainFailure` 承载原令牌错误、缓存错误放在 `SessionSignOutFailure` 的 cause，不依赖 JS 不可读的 `suppressed`。会话类请求（启动恢复、刷新、工作区选择与创建等）返回 401 时按该请求实际使用的令牌（由服务端拒绝附在失败上）清除恢复记录绑定一致的范围，冷启动也可清除且不影响新登录范围；本地合成 401、登录失败与业务读取 401 不清除，坏材料保留为可重试的未知；清理失败保留原对象且不替换原认证失败。启动恢复已由 `SessionCacheRepository` 创建内部恢复对象，且只在 `LedgerSessionLocal.offline` 时通过 `LocalFirstFinanceRepository` 暴露本地收支分页、现金账户、类型目录与分类目录；在线浏览仍保留服务端路径；切换前须覆盖全部刷新及读取入口。切换工作区、登录／注册／接受邀请与退出先关闭旧恢复对象，关闭失败保留重试；401 清理前释放活动租约。现金写入、账户／分类变更及已提交收据恢复的刷新在请求前捕获原恢复对象和会话代次，不能在迟到响应后选择当前会话。同步 owner 累计已确认收据的最大版本下限，失败后的旧收据或 null 刷新不得降低它；本进程已知写入后暂时封闭本地读取，较早刷新完成不得覆盖较晚失败。刷新失败保留提交成功及安全诊断，内部 superseded 与真实调用方取消分开。页面离线提示与始终本地读取仍待完成。Web 端三个生产前提必须同组维护：`/ledger-storage/*.mjs` 原生模块导入要登记在 `webApp/webpack.config.d/ledger-storage.js` 的 externals；静态服务必须把 `.mjs` 作为 JavaScript 返回；加密缓存要求跨源隔离，预览服务与生产 `_headers` 都要发送 COOP `same-origin` 与 COEP `require-corp`。断网启动冒烟由 `compose-offline.e2e.ts` 在真实后端 + 生产 Wasm 预览覆盖，在线重载会写缓存、离线重载只允许 API 请求失败。

固定范围 SQL 缓存适配器只组合快照存储与平台清理动作，不能自行创建明文驱动。clear 先关闭返回围栏，等待原 SQL，再 await 真正关闭连接及 VFS 句柄，成功才擦除文件／密钥；取消和失败须能按成功阶段明确重试。初始化失败只以非取消上下文关闭，不删除旧存储；保留原异常和清理诊断。真实 JDBC 回归不替代平台加密工厂、密钥生命周期或页面接入。

原查询测试的状态可由原测试函数执行到首次查询前准备，拦截只能用于停止生成，不得伪造结果。重建交错保留原退款插入、原关系工厂的类型／修订／角色与真实发布；不要以单纯改金额替代。SQLAlchemy 的单条 CTE 文本适配为同一 jOOQ 读取连接／快照内的一次状态查询及关系读取，并保留原全部结果／版本／旧游标断言。月份计算次数只检查 SELECT 列，不能把 ORDER BY 中的字段误当成所选列。JVM 无环境 Decimal 精度，须直接测试生产累加器的精确结果及独立低精度对照，不虚构可变全局上下文。

当前账本列表必须同时保留游标与绝对序号分页、完整筛选绑定和版本围栏。首个无版本序号页返回筛选后月份位置及精确汇总；带版本后续页省略月份信息，只在 SQL 计算总数／待确认数，不能物化全账本 DTO。未知草稿占位置但不参加统计，全局筛选选项不随当前筛选收缩。列表项复用原字段呈现，不引入详情专用的成员／问题校验；分类树须在同次快照中仅加载一次，并以实际 SQL 查询次数回归。原 Python `int()`、FastAPI 布尔、ASCII JSON v2 游标与各数据库安全错误分别保留，不把 SQLite／PG 的原错误差异猜成同一结果。

端侧导入的基线守卫在重新鉴权及原键收据重放之后、同一实际写事务内执行，不以单独预检替代写入围栏。守卫只锁定和比较，不推进或缓存接受结果；savepoint 回滚后读取真实版本。回归应包含真实事实与收据、晚期异常整体回滚、同基线竞争、推进前锁持有及完整 Long 边界；仅内部守卫通过不能关闭完整写集服务、HTTP 或结果未知核实任务。

当前流水关联候选查询保留原始当前字段与只读空能力，不调用依赖 entry／投影的完整详情呈现。SQL 在同一快照中完成闭包排除、有界 ID 翻页与字段读取；PostgreSQL 使用重复读，原生回归须在 ID 选择后实际修改候选，证明返回字段和修订仍属于同一快照。SQLite 精确金额比较函数在原生连接打开时、托管包装前注册，不放开业务连接的 unwrap 通道。关联搜索的原只读定义单独登记，不能因 GET 已接线而关闭包含关联预览／确认的原测试或完整维护任务。

业务计划原 monkeypatch 使用实例内部阶段依赖适配，默认仍调用真实准备／发现／图构建函数，不用全局开关或新增 HTTP／配置字段。闭包断言记录每次真实图构建的事实数，只有最终整体构建可包含全部导入；不把组大小推测成实际执行规模。无新建／转正式种子时不进入发现阶段。候选剪枝的完整对照须强制把指定事实纳入实际关系上下文，再比较原全部输出，不能比较同一剪枝结果。只读 SQL 观测须接受递归 SELECT 和 `replace()` 等只读函数，同时拒绝实际写语句；状态变化测试单独提交事实修订后再逐次核对只读快照，复跑使用全新场景库。

逐行规划的猴子补丁适配不能只在最初 SQL 读取阶段失败。使用实际准备的来源计划，在原生 `operation` 读取首行时触发内部异常，观测首行到达和下一行未处理，并核对全部表不变；不要新增公开测试配置。来源更新测试分别验证批内文件时间排序与跨批不按旧文件时间拒绝；固定金额冲突不部分应用可变字段，公共人工备注保存在条目编辑而来源值仍沿来源更新。草稿补全须覆盖停用／外工作区账户、币种不支持、歧义事项、原现金部分 ID 保留及非法／重复 ID 拒绝，不能只以新建正式记录场景代替。

只读操作规划原测试可共用同一未修改匿名库，但账户变更等预置状态必须隔离，所有成功／拒绝分支都核对完整业务表快照；读取事务最终回滚不能单独证明只读，还须观测没有写入 SQL。原账户改名通过带版本列的 ORM 完成，直接 SQL 改名会漏掉修订变化，原生测试必须核对新修订与旧选择仍有效。同一操作的重复人工决定应覆盖原全部参数，显式分摊矛盾覆盖序号冲突、金额不守恒、符号错误与缺时间的笛卡尔积；不能把非法输入降级为草稿。typed／wire 使用稳定来源记录 ID 比较完整操作，并验证原生精确金额类型和文件深度相等，不仅比较金额文本或数量。生成器只准备原文件和账本，不生成断言预期。

解析上下文原测试的保留理由必须逐项审计：真实银行字节场景不能因黄金已覆盖而保留，须直接断言默认解析、坏行、恢复和空声明数。分隔文本按 CSV 逻辑记录保留引号内换行／制表符／双引号，位置及计数按原读取器，不把有效流水拆成 `row_shape_invalid`；默认平台逗号行为与银行严格模式分别回归。私有编辑上下文禁止导入，原键核实在权限／归属和永久收据核对后返回 `not_committed`。解析后撤权要有解析到达标记；复跑含角色／收据的场景必须重新准备库，不能把已消费库的拒绝当作业务回归。

原会话 HTTP 的 Starlette 临时文件断言适配为实际 Ktor 接收管线的文件部件交付及转发 `dispose`，不能用虚构临时文件代替。权限拒绝必须先于接收，协议失败和单文件预算失败也要验证所有已交付部件释放。元数据重复键回归必须包含两份均合法的值，不能只用空数组把重复键错误掩盖掉；复用已有严格 JSON 读取器。原小预算通过内部构造参数传给上下文，生产上限、HTTP 字段和运行配置保持原合同；会话实际回包要通过现有 Compose context 消费者验证失败文件状态和未知数量。

原准备图寿命测试不能因为 JVM 使用追踪 GC 就按 Python 基础设施保留。先把准备包装和最终快照的逻辑持有范围分别限制在准备／呈现方法内，只把必要的列表／候选集合向后传递；在真实步骤边界以弱引用和有限次显式 GC 验证可回收性，同时核对冻结列表类型和业务结果。不要以重复深复制整张图或手动置空代替阶段边界，也不要把释放时间当成本轮性能验收。

```
## Implementing: <change-name> (schema: <schema-name>)

Working on task 3/7: <task description>
[...implementation happening...]
✓ Task complete

Working on task 4/7: <task description>
[...implementation happening...]
✓ Task complete
```

**Output On Completion**

```
## Implementation Complete

**Change:** <change-name>
**Schema:** <schema-name>
**Progress:** 7/7 tasks complete ✓

### Completed This Session
- [x] Task 1
- [x] Task 2
...

All tasks complete! Ready to archive this change.
```

**Output On Pause (Issue Encountered)**

```
## Implementation Paused

**Change:** <change-name>
**Schema:** <schema-name>
**Progress:** 4/7 tasks complete

### Issue Encountered
<description of the issue>

**Options:**
1. <option 1>
2. <option 2>
3. Other approach

What would you like to do?
```

**Guardrails**
- Keep going through tasks until done or blocked
- Always read context files before starting (from the apply instructions output)
- If task is ambiguous, pause and ask before implementing
- If implementation reveals issues, pause and suggest artifact updates
- Keep code changes minimal and scoped to each task
- Update task checkbox immediately after completing each task
- Pause on errors, blockers, or unclear requirements - don't guess
- Use contextFiles from CLI output, don't assume specific file names
- Do not use context or operation guidance as proof that a task is complete
- Apply relevant project context; report conflicts with controlling workflow inputs
- Consider every guidance entry; explain any inapplicable or conflicting advice
- Do not copy runtime context or operation guidance into implementation files or planning artifacts
- Preserve CLI-controlled blocked/ready/all-done behavior and completion criteria

**Fluid Workflow Integration**

This skill supports the "actions on a change" model:

- **Can be invoked anytime**: Before all artifacts are done (if tasks exist), after partial implementation, interleaved with other actions
- **Allows artifact updates**: If implementation reveals design issues, suggest updating artifacts - not phase-locked, work fluidly

## 本仓库现金 Compose 接线参考

现金关系治理中的后端合同见 `openspec/changes/govern-cash-relation-model/`：关系只保存 kind、端点和角色，不接收已删除的 subtype，也不增加替代 variant。发现读取流水分类及来源证据，所有确认入口共用硬约束；人工添加不调用发现证明。业务标签由完整成员派生，公开收支引用由稳定 entry ID 提供，不能从代表流水 ID 拼装。迁移测试从实际历史 schema 构造旧字段，异常存量必须保持原数据并阻断。该 change 的用户已明确授权保留现有 Compose UI、仅按治理范围微调，Kimi 原型仅作局部交互参考；具体保留项与变更项见 design 第 9 节，后端测试通过不代表客户端已切换。

当前维护协议适配位于 `compose/shared/src/commonMain/kotlin/com/finance/tracker/data/CashMaintenanceApi.kt`，领域接口位于同根 `domain/CashMaintenanceModels.kt`。账本维护与记账编辑的实现记录见 `openspec/changes/archive/2026-10-28-compose-cash-ledger-maintenance/` 和 `openspec/changes/archive/2026-10-28-compose-cash-entry-editor/`；现金导入页面的实现及验证记录见 `openspec/changes/archive/2026-10-28-compose-cash-import-workflow/`。导入选择编码必须区分必填 `previous_plan_id:null`、字段补丁中的明确清空，以及未使用时应省略的 `common_fields`／`inherit_common_fields`；相应 HTTP 回归位于 `CashImportPlanApiTest.kt`。

现金导入收据的 `status` 保留既有兼容语义；新页面用 `commit_outcome` 区分预览已知问题与提交新增异常，并用 `has_business_writes` 判断实际保存事实。旧收据缺省时不补算，接口细节见 `docs/import/cash-import-redesign-contracts.md`。

来源问题正文更新以 `pending_source_payload_present` 和归一后的 `pending_source_payload` 共同判断；缺省或不可判断不视为清空。

Kotlin 来源选择迁移须保留事项标识的字符串精确匹配；协议可接受的 19 位整数文本不一定能转为 Long，不能在事项查找前改变原拒绝类型。正文摘要严格对照 Python 有序 UTF-8 JSON；测试中的一般浮点数按类型、完整值及负零比较，金额文本、摘要和错误仍逐字核对。来源纯规则不代替详情可选动作的完整准备校验或维护事务。

来源正文派生只更新备注及单行解释，身份保留可信解释白名单，不从正文恢复卡号或来源账户。PDF 表头去 Python 空白后重名列全部剔除；原始字段值及明确人工备注保持。支付宝理财渠道的派生不改写金额。可选来源动作须继续执行完整准备，不因这些纯规则通过而默认允许采用。

普通草稿保存使用 `save_draft` 维护计划，保留 draft 状态并复用原计划核实。是否额外确认取维护计划顶层完整 `additional_effects`；明确空数组才可省略，缺省时提示重新核对，不走旧版本确认路径，不能从 `can_commit` 或首屏详情猜测。

导入及维护提交新增异常使本次业务写入全部回滚，不创建失败子集、派生会话或逐行补救流程；草稿明确矛盾须在表单和后端拒绝，合法缺项保持可保存。

已知修订／过期拒绝或明确 `commit_state=not_committed` 直接报错供用户重新读取后重试；不自动合并或替换修订重放。只有结果未知及已消费／幂等冲突保留最小原键核实。

草稿财务表单仅发送实际修改的标量，不展开旧完整 `components`；原子草稿唯一现金部分由后端基于采用来源后的有效值联动。来源明确的组成项（含空数组）与用户明确分摊不得隐式重造。

当前账本列表的 `transaction_id` 是打开流水详情的明确引用；`record_id` 是账单来源编号。不得用来源编号调用内部流水接口，也不解析 `projection_id` 猜目标。客户端与提供该字段的后端一起验收，不增加旧响应执行分支。

现金投影列表筛选新增条件时，必须贯通查询参数、`ProjectionFilters`、游标数据和共享 `filter_conditions`；列表、分页、月汇总及待确认数共用同一资格条件。金额筛选按投影最终显示金额判断，SQLite 用精确十进制比较并显式保留未知 `NULL`，演示仓库也按投影金额过滤。客户端显式发送开关值，重置使用默认筛选模型；涉及缓存或游标的筛选都要加入游标绑定合同。

合并收支的待处理区域通过当前展示详情的明确成员引用读取其他待确认成员；处理命令使用问题所属流水修订，来源成员不因此开放普通编辑。当前原因动作是 `edit`，草稿编辑问题合并呈现，具体原因优先于重复的未补齐提示。

来源原文通过证据成员的明确流水引用读取当前详情，同一展示版本和成员修订才组合显示；变化只提示重新读取。草稿分摊只在用户明确编辑当前模式时发送 `components`，保留成员身份及顺序；切回单笔不发送隐藏分摊，也不继承其他模式的明确采用选择。

正式收支编辑使用 `CashEntryInput` 构建实际字段补丁及 `entry_id`／`expected_entry_revision`，由当前维护任务保存；取消需走任务的 `cancelEdit()`，清除未发送计划并在忙碌或结果未知时拒绝放弃。不能仅关闭表单而保留可提交的旧补丁。完整手动转账由 `CashTransferEntryInput` 按真实身份与方向识别双端，在同一详情表单保存；同币种联动金额，换汇保留双金额，财务修改始终发送两个成员补丁。仅公共修改不发送财务补丁；缺少完整组能力保守只改公共字段。演示仓库按实际成员变化增加修订，包括币种引发的另一端类型变化。

“记一笔”的直接命令由 `CashCreationViewModel`／`CashCreationRepository` 承载，正式 `CashLedgerRepository` 只保留查询、分类和投影删除，不提供旧全字段 PUT 或任意关系写入；创建不能转交账本 VM。演示专用内存助手不属于真实接口合同。普通记账选择方向后编码有符号金额；转账同币种省略 `to_amount`，跨币种发送两个精确正金额，本地时间换算 UTC。成功只刷新，未知锁住重复创建；演示转账遵守 `internal_transfer` 和正式统计资格，不以旧 `transfer` 经济类型代替。

关联核对按服务端完整成员引用读取并展示实际流水，不能把候选的预计合并摘要当成员；单候选也须完整展示，缺成员不可接受。已知 `invalidated_choices` 清理未发送计划和旧决定，提示重新核对，不自动迁移。后续预览只返回残余公共冲突时，已解决字段只能保留当前 `common_fields` 明确返回且完全相同的值。

导入核对默认全部，草稿依据 `anticipatedState` 区分，不能把 `pending` 当成草稿。明确选择确认状态筛选时读取独立分页，不能混用游标。公开合同尚无全量草稿／配对计数与排序，未读完时只能说明已加载范围；完整列表及详情齐备后才提供本地草稿／手动配对筛选。行内配对读取当前页明确条目的详情，以 `relationChoices` 的 `accept`／`defer` 展示，不把既存关系或成员数量当作自动配对。

导入整笔公共属性使用当前详情 `entry.edit_ref` 和 `entry_choices`，不挑首个来源行，也不改变其 `keep_existing`／`defer` 决定。目标失效后须由用户明确重新核对：重读服务器当前选择，清除旧整笔目标和关联决定再预览；不保留未成功预览的本地行输入，不迁移补丁。被编辑目标依赖失败时后端整命令确定未提交，客户端按权威标志安全返回。

导入关联核对的真实成员取当前详情 `relation_members`，按候选 `member_refs` 精确对应；这些成员始终只读，不因返回字段而开放行编辑。不得读取全量列表猜候选位置，或用候选合并摘要当真实成员。缺少完整引用时阻止接受。

迁移导入预览的内部关系候选读取时，先以原业务计划的匿名双后端结果核对时间窗、来源绑定、旧来源键、完整账户和范围约束；候选 ID 仅供后续完整图加载，不能当作已确认关系。原来源行存在但账户范围或交易号明确为空时，不回退到操作字段或来源编号；这些空值与字段缺失不同。纯读取测试须核对无财务写入，完整预览与 HTTP 可用性另片验收。

导入操作规划后的预览准备只从新建正式与草稿转正式操作召回关系候选；已有操作目标和可选额外种子仍应加载当前关系上下文。来源范围优先取来源行的账户范围，缺省才用文件范围；无可靠来源编号时使用出现键作为匹配标识。上一计划的公共条目选择必须命中冻结目标索引，缺目标返回 `plan_changed`，不猜测或迁移旧决定。准备结果仍是内部输入，不据此宣称完整预览已接通。

导入预览的虚拟父流水与现金部分 ID 可能超过 `Long`，投影和完整关系图须用完整正整数保留身份、排序、成员和关系端点；不能以截断值代替。数据库仓库入口在任何财务写入前对真实父流水、成员及关系 ID 做正数 `BIGINT` 精确范围校验，虚拟 ID 只用于内存预览。用 Python 超范围组合对照和双后端持久化拒绝回归分别验证两侧边界。

预览快照从同一关系上下文读取父流水、来源范围、现金部分和稳定收支对象，再应用本次操作；当前人工公共字段优先于来源字段，同组公共补丁作用于全部成员。已存金额在 SQLite 和 PostgreSQL 可能只差尾零，匿名黄金分别按各自原结果比较，不把表示差异当金额变化。正式流水类型变化须用操作前已确认关系的完整旧组，先移除不兼容的关系及资金关联，再校验预计投影；校验失败时恢复原值、端点、关系和移除集合，关联行转为不可导入。匿名黄金须覆盖关系保留与移除、资金关联、聚合现金部分、草稿和整笔回滚；公开预览仍须等关系发现与确认事务完成后接线。

导入关系发现从预计现金部分生成匹配事实，保留父流水/聚合序号、来源范围和原始备注；来源匹配元数据单独复制，微信负数退款按方向/平台状态补原消费角色。仅导入/旧流水可参与，种子按资格相交；空种子及无需发现先短路。传给既有唯一镜像提议管线的已确认关系必须两端均可参与，卡尾及账户标识别名只读当前工作区，提议按种类/子类/两端现金部分去重；完整关系组和确认事务仍由后续层校验。

关系组的稳定建议摘要保留 Python `default=str` 的精确十进制与带空格 UTC 时间表示；公开 JSON 编码不能直接代替计算值编码。提议转换按活动业务键去重、保留角色证据和完整旧组依赖；弱提议最多遍历 2,000 个候选组合，前 20 个通过完整图的结果可选，枚举不能修改原快照。完整构建不能过滤掉两端都缺少事实的已确认边，必须由图规则拒绝。

关系选择仅接受上一冻结计划或完全相同的已保存失效选择；失效时保存成员引用并提升来源建议世代，触及同一成员且未显式决定的新建议恢复待确认。公共字段仅随接受合并提供，独立值继承只随移除提供。图应用按完整提交组先登记边并校验旧组，人工公共值冲突恢复待确认；自动组的业务图失败恢复原边，人工组失败整体报错。拆分不继承公共值时，从当前成员保存的独立值恢复整个旧组；预览不得写当前关系或收支。

现金导入公开分页的性能证据必须区分冷读与热读。公开响应缓存仅可保留已过滤的 JSON 字节，并逐次核对真实暂存对象、归属、TTL、完整计划引用及收据消费；不能作为写入或重试会话的执行输入。

当前导入入口为共享 `CashImportTaskScreen`；新增导入行为不得恢复旧四步或 scan／preview／commit 客户端。核对复用主页表格与只读详情，文件选择和来源账户映射分步呈现，导入不提供字段、来源和关系编辑。旧协议编辑能力不因此删除；第一版 UI 演示确认前不把最终验收标为完成。结果按实际账单行统计，提交新增异常只给统一错误、安全原因和返回，不提供逐行失败处理。演示同样保留未知 null、来源只读及普通草稿保存语义，转正式需完整信息和有效现金类账户。

导入来源差异的 `source_payload` 和 `components` 必须使用结构化只读展示，不能交给标量格式器；明确空组成项显示既有空值提示。完全相同的 JSON 差异无需呈现，不在客户端推断金额或时间的业务等价。

演示账本的 `composition` 同样只承载真实关系种类；单笔为空数组，完整转账为 `transfer_pair`。`single`／`combined` 是筛选值，资金分配 `components` 不构成关系，不能据此显示“已合并”。

现金 UI 接入新协议前，先比较修改前已验收的 Compose 源码和实际截图，保留金额／币种组合、详情信息层级及固定操作等已有结构。接口字段顺序不决定表单顺序；全部可编辑项按业务分组展开，资金分配与记账字段同组。正常、实际额外影响与错误状态都须核对唯一主要操作，不能只审正常截图。Material 规则按项目技能的官方链接正文核对，颜色对比使用实际主题配对和可执行回归，不能因使用原生 M3 控件便推断已通过。

合入索引账本能力时，序号／月份位置涵盖全部匹配的正式与草稿流水，金额汇总仅采用统计资格；沿用稳定排序和未知时间桶，不能按月份首次出现定位不连续的同月区间。同步迁移当前协议的夹具和测试，不为满足旧测试恢复已退役导入、详情或关系写入口。

查询性能优化先核实服务实例的真实生命周期；HTTP 按请求构建服务时，热缓存基准不能代替冷请求分析。缓存仅优化读取，不承担可见性或计数正确性；携版本的冷页只聚合所需数量，不加载约定省略的完整月份数据。

导入错误定位使用 `ft.cash_diagnostics` 的局部 scope，转换／规划只附上下文，最终 HTTP、文件隔离或提交包装才产生编号和脱敏调用帧日志；不输出异常正文或 SQL 参数。`row` 与 `related` 精度不可混淆，持久端点不能猜测本次文件，批量不拆分重跑；最终提交只给 request 范围。Compose 只消费 `error.diagnostic`／文件 `diagnostic` 白名单并只读显示，不写恢复存储或增加逐项补救。协议见 `docs/import/cash-import-redesign-contracts.md` 的错误定位章节。
现金导入的呈现合同见 `openspec/changes/archive/2026-10-28-compose-cash-import-workflow/`：从账本打开全屏模态任务，保留兼容地址、恢复与退出保护。后续 UI 修改仍按 `AGENTS.md` 的演示确认循环交付第一版并逐轮处理反馈。


## Kotlin 迁移验证

> 迁移已完成：Python 后端与测试已删除。以下 Python oracle、迁移台账和 pytest 描述只作历史背景；当前验证使用 `compose/` 的 Kotlin 测试与显式双后端 schema 门禁。

Kotlin 后端迁移由 `kotlin-backend-shared-core` 变更承载。按该变更的完整接口矩阵逐项验证，局部 GET 切片不代表完整认证、分类或后端迁移完成。公共模块不得依赖 Compose UI、SQL、凭据或服务器网络适配；只读会话恢复与业务工作区授权分别验证。Python 匿名黄金生成器仅用于测试 oracle 和隔离数据库准备，不能进入 Kotlin 生产业务路径。数据库合同测试必须绑定显式任务专库，不能复用另一数据库的 Gradle up-to-date 测试结果。

金额 JSON 入口保留数值与字符串、缺省与显式空的区别，并校验组成项引用和 ordinal；不能先统一转字符串。注册/登录的类型限制不得扩大到其他字段。旧字段文本转换须固定 Unicode oracle，并覆盖二进制幂及相邻值；仅随机浮点样本不足以证明最短文本等价。密码使用严格 UTF-8，不替换孤立代理字符；昂贵校验放在 SQLite 写锁外，短写事务再核对当前摘要。工作区写入按目标、会话、邀请顺序重读；删除后的备选也须确认仍存在，忙时整次回滚，不发布悬空指针。

Python 单测随实现迁为 Kotlin 原生测试，按源定义、展开参数和全部断言追踪；黄金差分与跑旧 pytest 均不能代替。漏项检查同时核对模块级参数、conftest/fixture、样本文件和配置，不能只指纹化测试函数。源用例变化要绑定准确的基线/当前指纹和已确认原因，完整门禁有 pending 必须失败。

授权修复新增的 Python 回归通过 `source_additions` 单独登记当前指纹和理由，不能移动固定迁移基线；新增用例默认 pending，仍须核对全部参数、断言、夹具和真实 Kotlin 目标。pytest 版本改变自动参数 ID 时，以完整 collection 校正映射，保留原参数值和指纹检查。源夹具中的真实角色、已有财务记录及投影是否初始化分别影响执行分支，不能只迁移空账本或 ready 状态；每个原参数独立验证失败与后续成功断言。

分类目录及写回包使用含自身的名称数组，客户端领域路径仅含真实祖先对象；按同一目录的 id/parent_id 链重建，不能按名称猜 ID。写入先解码独立回包为命令确认，再沿用既有目录刷新，不能写库后因套用详情 DTO 而报解码失败。移动先固定原根深度，不依赖节点遍历顺序。整数修订文本的空白集合与 Python split/strip 不同，不能把 U+001C–001F 当 int 可接受的空白；拒绝路径须验证无目录初始化或其他写入。

分类批量写入的性能需在完整双库回归数据和新工作区统计下验证；不能以手动 ANALYZE 后的单条计划替代 API 门槛。PostgreSQL 的选中范围 CTE 之外还要核对后续连接是否沿唯一键有界读取。故障注入测试须证明到达指定晚期语句，只有通用存储异常和回滚快照可能掩盖更早的无关失败。

从 ORM 迁到 jOOQ/JDBC 时，身份、未保存属性、自动 flush 和 expire 都是可观察合同。expire 必须丢弃脏值，不能先 flush 再重载；JOIN 快路径须逐行核对缓存归属，仅检查返回集合包含可能漏掉过期关联。投影、关系和来源维护需共用事务级稳定收支仓库上下文，不能机械逐调用新建缓存。

本地 Kotlin 后端业务 SQL 使用 jOOQ 生成字段和 DSL；仅事务控制、驱动配置及 schema 检查保留 JDBC 语句。生成类型只留后端，由官方生成器从显式准备的 PostgreSQL 18 `_test` schema 生成，普通构建不连接数据库。保留原查询列范围，避免宽记录自动解码无关账单原文后改变公共编辑的接受边界。SQL 观察器适配引用符和大小写后，仍须证明实际批次、参数和晚期故障到达，不能只把断言改为不报错。

jOOQ 的 PostgreSQL 金额绑定保留原十进制文本并交给 NUMERIC 转换，不能先经 `BigDecimal` 把巨指数零归一化。SQLite 尾零、负零、64 位计数与 UTC 微秒按原合同分别验证；自定义绑定、JSON 与集合更新不得改变调用方事务和保存点缓存。代码生成器拒绝非法 URL 时去除包含原文的异常 cause，安全测试须覆盖格式错误、查询参数及凭据。当前用户明确排除性能测试时，使用 `-PbackendFunctionalOnly=true`，保留原预算和失败证据，不声称性能通过。

收支证据与当前详情分别核对可见条件和错误：证据可读隐藏记录，根成员缺失沿 Python LookupError 返回 404，存储错误保留原安全 code；不能直接套用当前详情的可维护条件或 503。成员、现金部分和关系端点的标识按实际完整回包核对；资金调拨白名单拒绝布尔伪整数。现有来源成员和旧证据消费者的草稿空值能力不同，分别记录实测结果，不以成功解码其中一个宣称完整来源流程已迁移。

Python datetime 只有微秒；JVM 时间绑定 SQL 前先截断微秒，不能让 PG 对纳秒尾数再次四舍五入。源摘要须复用原 canonical 表示和拒绝规则，不能散列 JDBC 默认文本；SQL 源值子集验证不代表通用序列化模块的任意类型都已迁移。

关系上下文的版本围栏保留草稿和已删除父流水，但图输入及资金占用只含正式、未删除父流水的部分；不可直接复用围栏中的全部资金 ID。上下文父读取按原 500 批边界验证 SQL 参数数，SQLite 整数列读取不能静默截断。动态证据字典先按 Python 数值等价归一键，并按遍历后值覆盖；edge ID 仍严格为整数。先收集两个变更序列，再校验边，以保留原错误次序。

来源证明必须绑定仓库与真实事务，不能只看连接是否打开。JDBC 提交/回滚、显式 SQL 结束和 savepoint 语义不同；SQLite 在 SQL 结束后不会因 JDBC autoCommit=false 自动续开，约束及触发器也可能主动回滚。SQL 异常后使证明失效，保留内部错误停止整操作的规则。归属链和 unwrap 不能绕回原生连接；元数据代理按声明接口封装，不按底层对象兼有的其他 JDBC 接口猜测类型。事务控制不进 batch，普通 DML 批量合同单独验证。

账户迁移按实际 editor 前置迁原生用例；管理员成功不能代替可编辑成员。余额快照的名称视图只按原数字 ID 规则解析，`+1` 等合法名称不能被数值转换误归属。坏快照必须停止整次写入，明确的旧数字键转换失败沿账户输入错误返回。账户服务重放先核对原键、规范化摘要及真实计划/上下文元数据，永久收据不引用已删除账户的外键；提交后丢结果也用原键核实。JSON parser 的通用库接受范围不能代替 Python strict JSON：原始字符串控制字符、重复键、正文预算与严格修订均验证，已转义控制字符按既有字段规则处理。

现金规则的嵌套对象键及集合比较须保留 Python 数值等价和 NaN 引用语义，不能交给 JVM 默认 equals。时间解析沿用原全局 `Z` 替换与 ISO 周日期分隔符消歧；同时核对接受/拒绝与 UTC 值。纯规则原生用例和差分只证明规则切片，未接事务的财务写入保持未完成。

投影退款算术还需核对十进制符号/系数/指数，不能只比数值；保留原尾零与精度 80 的局部舍入。资金角色兼容函数与 Decimal-only 关系端点使用不同输入合同；完整图核对隐式非根资金占用，关系 ID 不提前收窄，联合组必须核对全部当前成员和原修订。

永久收据的读取也遵守工作区锁、锁后与返回前授权复核。旧键查询在路由通用解码前验证原始百分号、UTF-8、重复字段和预算；真实 HTTP 验证不能用测试客户端自身拒绝输入代替。公开计数按原整数精度输出，核对通用 JSON 编码器是否会转浮点；共享 DTO 的范围限制与服务端完整范围分开验收。永久收据摘要按原 ASCII JSON 的转义后字节计算预算，DEL 也须转义；仓库创建不代替提交前原键核实，不能用写后重放掩盖重复业务写入。投影状态缺行的只读默认不得触发初始化或重建；管理员状态与普通收据读取分别核对权限，ready 数据集和版本在已有工作区锁内锁定状态后判断。选项协议复用保留原空标签和枚举顺序，并以正式客户端和领域映射消费实际回包；静态目录也不跨请求暴露可变列表，不因目录为空而初始化业务数据集。

## 后端性能与 PR 分片验证

来源批次迁移须分别核对可靠身份合并、弱证据最大出现次数与共同结果引用。文件重传不增加次数，可靠快照选择保留全部旧目标证据，固定财务冲突不因选取较新文件而消失。空页证据也参与解析命名空间校验。原生回归保留完整输入的深复制结构比较，不能用字符串快照代替；原定义含规模或时间预算时单独登记未验证部分。内部引用和候选 ID 的排序沿 Python 码点顺序。

后端常规检查通过 `compose/` 执行 `./gradlew :backend:test`；真实 PostgreSQL 验证必须使用各自独立的专用 `_test` 数据库，不得并发重置同一库。分类性能夹具包含稳定收支对象及完整成员；公共备注校验 `entry.fields` 和对象修订，同时保护来源原文与来源修订。导入性能继续计时完整预览与提交，不以内部规划耗时替代；预算调整须有明确授权，并按剩余失败场景单独定义，不连带放宽通过项。

同次导入预览可从完整校验的私有暂存快照更新计划，原解析文件以不可变 JSON 字节保留；不得把可变 DTO 的对象身份当作文件未变化证明。完整重建可复用同事务已验证来源快照的数量，但发布前仍重算来源摘要并核对真实数量，普通仓库发布入口继续完整校验。

无关联现金流水的性能优化仍须先完成事实、组成项和资金引用校验，保留原投影输出及错误翻译；类型子类走通用算法。小批当前对象联合读取必须保护 ORM 会话中的待写和旧成员状态，跨工作区、禁用 autoflush、缺对象及大批次继续原合同。调用内资金解析缓存先检查原始参数类型并限制大小，不得跨文件或调用复用。无旧记录的可靠来源新增快路径逐行执行原完整字段校验，重复编号、多文件、弱证据及文档分页回通用规划。

JSON 预算的普通容器快路径必须与原算法差分核对异常、剩余预算、深度和自定义协议；未知类型及预算自身引用完整回退。组成项与资金外连接保留无资金组成项、全部当前资金行和跨批次覆盖。投影写后仍读取完整上下文及对象版本，不能因性能省略损坏成员的错误检查。

性能优化优先缩短私有准备图与最终图的生命周期，不暂停全局 GC 或修改阈值。只读详情快路径保留操作引用、成员归属、组成项和错误次序，索引键或未知协议回原算法。调用内成功财务校验缓存必须覆盖精确输入类型、字段存在性、全部组成项及 Decimal context，仅缓存完整校验成功，限制大小并在调用结束释放。

For the active Kotlin backend migration, category-delete acceptance uses the
explicit `FT_KOTLIN_CATEGORY_DELETION_CONTRACT_DIR` fixture directory in addition
to the existing fixture variables. Use the saved historical target/oracle fixtures; the Python exporter has been retired.
Run fixture-dependent evidence through migrationEvidenceTest against explicit isolated test databases. Compare all financial columns after failed and successful commands,
then consume captured bytes through the existing Compose confirmation APIs.
Category usage is counted from current entries; deleting a category also clears
standalone restore values and protects an empty manual category on live and
deleted facts. JSON syntax and byte-encoding checks must precede any command.
Supplemental JSON goldens and sampled RSS evidence do not substitute for ported
Python assertions or the original peak-RSS performance tests.

投影全量重建先锁工作区/状态，再读取同仓库同事务来源证明；发布消费证明仍重算摘要和事实/成员数量。数据集替换保留 900 写入/回查批次，晚期活动指针及旧集删除故障须有真实 JDBC 到达标志与全部财务列回滚。原生服务测试要观测完整源读取恰一次，并让真实非法关系与诊断失败组合保留原错误。全列 oracle 应推进时间；SQLAlchemy 编译缓存可能保留默认 callable，测试时钟需读取当前值。PG NUMERIC 的 JDBC 文本/二进制传输可能把零显示为不同文本，快照按数值类型保留精确值与 scale，不能把显示变化误认业务写入。

迁移薄 HTTP 层时保留异常来源，不能只按 code 推断状态；projection.incomplete 可对应领域 ValueError 的 409 或仓库 RuntimeError 的 503。原 StorageError 在重建路由中作为完整固定字符串进入 code，按原标签与非识别数据库类型摘要验证，不加入 URL。生成 HTTP 黄金先确认目标 router 已挂载并逐例断言预期状态，不能把 404 录成有效合同。鉴权失败需跳过构建失败诊断；锁后和返回前拒绝均验证业务全回滚。组合原测试仍有其他操作断言时保持 pending。

保存点创建前须 flush 既有脏值，回滚后恢复收支、成员及状态缓存的对象身份，不能仅依赖数据库回滚。监听只属于注册时事务，提交/回滚/关闭后清除；同连接的新事务不得调用旧上下文。SQL 与 JDBC 保存点都以真实双库反例验证，保留外层证明身份不代表可继续使用旧缓存。SQLite JSON 声明可能被 JDBC affinity 报为 NUMERIC；全列测试快照按声明列类型判定精确小数，避免把账单原文送入 getBigDecimal。

迁移既有分类服务时分别追踪 move 与 update 的调用链；helper 相同不代表两个公开方法都会维护投影。只修复已批准的旧缺陷，其他行为按原边界验收；独立移动收集未删除来源和人工分类 entry 根后在同一事务维护完整组，无使用不推进投影版本。

SQL 查询预算按实际执行记录绑定参数，不能只统计 prepareStatement；复用同一语句多次执行也须累计。迁移原性能断言时补重复执行反例和非零观测，保留原预算，不用准备次数替代执行次数。

余额快照固定格式输出须覆盖超过 JVM Int 指数的正负零，原符号不能丢失或退回科学文本。名称还原先精确匹配模型 id.toString，再做 Unicode 数字回退；schema 未禁止负 ID。内部账户类型按真实模型的 String 校验，再处理缺省，不将集合或 Bool/数值转成新余额桶；公共请求不得伪造内部类型。直接 import alias 的源测试可以对照真正共享函数迁移，不据此关闭其上层导入流程。

余额快照的结构错误与明确金额拒绝沿原异常类别返回：顶层、账户类型和投资头寸损坏为内部错误，现金账户余额不是币种字典沿原 ValueError。校准先检查余额结构，再解析目标金额；投资头寸先计算 shares，再读取 total_cost。以结构损坏和非法金额的组合输入验证错误次序及整事务回滚，不能只测单一非法字段。旧现金新增的成功响应须在提交前完成 UTF-8 检查，保留原始代理字符来源；校准写零金额事实并仅修改指定币种余额，巨指数零的 PostgreSQL 结果须按各入口实际存储字段验证。

应用边界的白名单验证须在隔离调用方可变容器后返回；JsonObject/JsonArray 的只读接口不保证 backing Map/List 不变。递归复制保留共享引用，并保留不可变 primitive 身份供原始 Unicode 表示查询；回归应在验证后修改顶层、组件和嵌套数组，确认已接受输入不变。

转账迁移分别保留现金与投资事件、现金关系及余额口径；旧转账允许空币种配置，不能套用普通新建的更严格资格。Unicode 小写存在上下文规则，Locale.ROOT 不能替代 Python lower：以合法币种 `ΟΣʰ` 与已有 `οςʰ` 投资头寸回归 final-sigma 和 case-ignorable 字符。纯 helper 的负数、负零和科学表示与公开服务的正数前置分别验收。投资金额量化、日期解析、文本绑定和错误回包的先后须用组合非法输入确认；date/time 拼接继续携带原始代理边界。源成功结果断言不能因验证了持久化结果而省略，源测试 setup 与实际业务断言分别核对。

手工创建须同时对照 HTTP、完整财务行与失败后的序列：ORM flush 的账户/聚合更新及人工保护会推进新行修订，不能只用最终一次 INSERT 冒充等价。PG 组件批绑定失败可能不消费子序列，所有 label 编码须先验证再写组件；以坏第二组件后继续合法创建的真实 ID 验证。 逐源核对先明确 fixture 的角色、实际数据及调用入口，不能用 admin 成功代替原 editor 前置；PostgreSQL 权限等待须观测 pg_blocking_pids/Lock，再改变权限，latch 或 Future 超时不算数据库等待证明。字符错误先实测来源：持久化 UnicodeEncodeError 可为 400，错误回包无法编码才 500；保留原始代理边界、码点位置及多字段绑定顺序，不能由 JVM UTF-8 替换后提交。巨指数零的 fixed 输出须覆盖所有保存/回包入口；PG 原 NUMERIC 拒绝与 SQLite 成功要分别保留。

共享维护暂存分别验证完整编解码、存储端口和运行时接线。无回包发布仍须完整核对文件深度与页证明；编码保持解析及完整包络预算，读取完整验证元数据、计划和文件。原快照／公开页缓存已退役，同一对象连续读取后发生的存储故障仍须传播，不能返回上次结果。源用例参数化内存/R2 时，两种端口共同运行生命周期断言；条件对象 fake 不代表真实 R2 已接入。

R2 客户端迁移使用本机 HTTP 服务核对实际 SDK 签名、条件头、元数据及响应丢失；关闭 SDK 重试不替代网络反例。清扫器启动首扫失败必须在监听前中止，关闭失败不能覆盖首扫安全错误；资源关闭幂等。集成提交测试会消费匿名夹具，重复运行前准备新的独立测试库，不把旧收据/修订冲突误当实现回归，也不重置未知数据库。

维护计划复用解析存储时，重新预览须沿用已验证快照的修订做 CAS，构建后重读的新修订不能替代用户已核对的版本。数据库提交失败保留临时方案；业务事实与永久收据提交后才尽力清空临时正文，清空丢失响应不能改变成功结果。已消费方案的全部公开读取先查永久收据，临时存储不可用时也保持已提交错误。

来源账户对应建议存在已确认或窄范围旧对应时，失效账户不能回退别名；别名仅为完整 ASCII 数字身份的只读建议。映射、别名和账户读取逐批 200 个去重键，用实际 SQL 参数验证边界；发布只接受调用方成功证明，同值不更新确认人/时间/修订，不自行提交。仓库完成不能关闭资金组扫描、导入提交或原组合测试。

维护提交重验必须复用 Python operation fingerprint 白名单，公共预览对照另核对内部账户、事项与收支快照。内部归一请求不能直接冒充公共请求；非关系意图的空暂存 `relation_additions` 可由专用解码去除，公共解析仍拒绝。工作区锁必须显式取得，`database.write` 只提供事务；真实 PostgreSQL 锁队列、锁后权限与原键并发分别验证。

资金组准备复用已验证的深复制，不能通过 JSON 往返复制精确金额或嵌套容器。账户币种的内部形状错误停止整操作；银行卡正文只在无完整标识的工行信用卡读取遮掩卡号，其他银行使用转换器的可信显示名。纯资金组准备不计上传、对应成功发布或导入提交完成，完整结果对照不能代替原定义逐断言迁移。

上传文件准备迁移按解析器的显式类型边界处理失败：密码、已知文档格式和会话预算成为文件结果并保留 file_read 诊断，未知内部异常停止整次操作，不复制 Python 的任意异常吸收。文件名脱敏、选择失效和关系证明分别以 Python 结果对照；证明只保留引用并在读取时整体校验，替换继承已确认微信范围，显式无效范围也不清除。纯层无授权、永久收据、工作区事务或 HTTP，不据此计上传或完整导入完成。

来源规划读取迁移须保持只读边界：绑定按身份分片并去重，旧微信交易号保留原始与裁剪比较，同时间/文档/空页证据合并后提升覆盖；无法证明的冲突停止读取，不用内容散列或内部账户补造身份。会话视图只公开文件/来源账户/选择/预览输入/计划标识。双库种子对照必须覆盖旧候选、文档与空页合并、批次解析和视图字段；读取层不得写绑定或财务数据，上传 HTTP/收据/提交另片验收。

上传 HTTP 迁移须保持锁时序：multipart 解析与文件解码在数据库事务外完成，发布临时会话与核实永久收据在同一工作区锁内，重读会话修订；解析期间提交必须让晚到发布返回 session_committed 与原收据。已提交会话只返回空文件和收据摘要，拒绝修改。multipart 只接受单一 metadata 文本和 file:<client_ref> 部件，限制部件头、元数据、单文件与总量；密码只从元数据进入本次解析。错误码和 403 禁止消息按现金导入合同返回，不套用维护计划的通用文案。

导入计划的公开视图/分页/详情迁移必须保持复制与引用边界：校验路径不能改写调用方计划，成员在非复制路径也要浅复制；冻结详情只能引用本计划的操作或明细值，独立详情不要求操作引用；游标绑定计划、类型和筛选，非法游标与页码分别返回稳定错误码。该纯层不接预览/发布/提交，不据此计完整导入完成。

共用流水写入必须消费已重验操作并与外层原子事务共用连接。正式财务未变保留现金部分；替换先拒绝确认关系，再拆除草稿/忽略端点及候选，交换序号用未占用临时值。类型变化在关系写入前证明固定财务与其他上下文未变，沿已记录角色校验完整剩余图后解除不兼容关系/资金关联；普通字段与合并证明也会推进来源修订并使候选失效。公共条目编辑不改原始流水。内部 writer 不计联合导入确认或身份合并服务完成。

导入联合确认的证据筛选只排除全部已跳过的冲突命名空间；目标记录、直接候选及冻结集合引用的既有文档证明仍须校验。冻结候选恢复先校验内容摘要、规范正 ID、去重和当前存在性，不能接受同时携带内联候选的非空值。有界新增先查全部已有绑定和独立证明占用，再建立全部父项/现金部分与别名；保留 20 项门槛和 100 项批次，任何异常回滚整个调用，不逐行重试。已删除父项的既有绑定也阻止该全新批次入口占用身份。

确认收尾按冻结前后现金部分比较决定是否写余额，不预先改写金额表示；普通流水与手工转账保留各自账户口径、更新时间和快照修订。公共编辑须匹配关系结果且精确覆盖一个当前条目的全部成员，使用当前修订保存后增量刷新投影。任何晚期依赖或投影失败回滚前面的余额和编辑。逐行结果复制公共操作结果，批内重复只改该行 outcome，不改冻结输入。

关系组落账必须联合校验完整旧图和角色证明，再保存共同公共值及关系。重复识别须核对端点、金额、锚点、证明、当前候选和共同组全部成员；缺少成员、候选失效或无关旧组成员均不得判重复。共同提交组中一个 skip 使全部成员记录失败；晚期组失败依赖外层整体回滚，不捕获后续写入。候选映射跳过整项中未落地或不可用端点，发现失败按原稳定引用去重。该内部能力不计联合导入确认完成。

当前角色关系写入与旧接口适配分开，禁止根据金额重新猜角色。插入/替换先校验所有字段、端点归属和创建方式，再按当前修订写入；候选整批验证后才推进关系修订并替换。候选修订变化或已删除为 stale，缺现金部分或草稿为 incomplete；非法末项不得清除旧候选。完整图和外层原子事务由调用方负责，内部仓库不计导入确认接线完成。

来源写入仓库依赖调用方工作区锁与事务，不自行授权或提交。移动来源别名前先检查全部活动归属及当前身份；文档绑定仅保存当前页指纹，认证空页先重验声明/指纹，子集、数量不全或缺定位不提供空页证明。问题替换先校验全部候选及当前修订，同值重试不推进父修订，父字段与候选同时变化只加一；清除后重建问题不得复用 ID。内部仓库存在不计联合导入确认完成。

完整预览发布在同一工作区锁内核对当前权限、永久收据、会话修订和上一计划标识，保留原有效期；使用读取快照的对象版本条件替换。关系失败去重后标记待确认，公共条目选择核对后才统一编码计算金额/时间。发布前校验完整公开视图，失败不得覆盖原计划；文件变化后仅从已验证选择证明恢复记录编号，成功发布清除该证明。HTTP 输入保持必填 null、严格整数/引用/动作及重复键拒绝，实际回包必须经现有 Compose DTO/领域映射消费。此步不计导入确认或永久收据创建完成。

已保存导入计划的 HTTP 读取每次先核对当前用户、工作区及永久提交收据，再读取同一用户的有效计划快照；计划标识改变后旧路径返回计划变化。公开视图、分页和详情仅从冻结计划派生，详情不以当前数据库字段补全。测试用临时计划快照跨 SQLite/PostgreSQL 核对响应与无财务写入；只读入口接通不代表预览生成或确认事务完成。

导入操作规划迁移要保持延迟与保持语义：正式记录延迟必须清除预生成的条目编辑，否则会错误产生更新动作和共享条目分组；已使用账户证明直接比较资金组件列表，不能把列表误当映射或空值。正式记录只接受公共人工值，草稿补财务值时核对账户资格/币种；事项去重保留 after_import_action；共享收支条目同组补丁一致才合并。该纯层未接预览/发布/提交。

最终图预览呈现须同时覆盖受操作、旧关系闭包、关系决定和保留条目影响的投影，详情保留全部候选成员与旧关系移除效果。当前成员字段只能从快照恢复，公开字段剔除虚拟现金部分编号。条目编辑摘要保留原计算态金额与时间的 Python 表示，最终边按整数编号排序；只读呈现不计业务计划发布或导入确认完成。

条目目标迁移以 `ie_` 摘要绑定父事务、成员引用、受影响行/条目/关系、关系依赖和最终边；公共条目编辑必须核对当前目标摘要和分类存在，只接受公共字段。目标索引的依赖键与父引用必须使用同一字符串身份；索引/目标纯层不保存收支对象或投影，提交切片再接入。

关系核心迁移要先固定 Python int 语义：带符号或前导零的数字字符串按整数归一后排序，非数字按码点；无效时间保留裁剪后原文，naive 时间按 UTC。来源分组优先正式来源，正式为空才回退备注文本，未知正式来源不改判。open-leg 的第二侧哨兵是整数 0，不是字符串。该层不含匹配阶段与写入。

关系几何迁移保留 Python 时间语义：naive 按 UTC；业务日优先原始日期字段；日期-only 包括 YYYY-MM-DD、YYYYMMDD 和午夜时间；银行原始日期优先于正式时间哨兵。镜像图中相同平台评分和相同文本长度的并列不指定代表；卡尾窗口和交叉核对按原正则/子串。该层不含匹配阶段或写入。

关系投影迁移以活动事实计算余额；镜像组先排除转账成员，再按代表事实入账；退款冲销只递减原支出组且不为负；open-leg、已拒绝关系和退款接收侧不进入净额。投影纯层不发布数据集、不写关系或投影。

退款匹配迁移不以文本推断退款一级类型；退款文本只用于排除 P2P/转账种子和标题比较。标题比较先去「退款」前缀，通用平台标题不参与；菱形匹配只沿唯一平台镜像链，原支付必须为负且金额不小于退款。无唯一原支付不提议，不写关系。

平台退款迁移按精确十进制比较金额，不用 BigDecimal.equals 判等；时间窗口/最近距离并列时不配对。微信 R1–R4 依次为商户/交易号、全额状态、部分在途金额、residual；residual 可复用同一支出，其他规则消耗支出。转账退还要求账户/支付与源交易号互相印证且币种一致。该层不写关系。

Phase A 硬键退款按来源身份分组后再配；支付宝订单前缀/商户单、免押与同日唯一解冻、微信双行退款/转账退还；同账户同币种、扣除已用额度、跳过已链接对，固定投资转出可作支出侧；规则 ID 归一为 scan. 前缀。该层不写关系。

退款候选迁移的商户键是 Unicode 词字符（不能只用 ASCII \W 去中文）；候选先过同账户/币种/平台范围、窗口和剩余额度，再按订单锁/标题/对方账号/商户/精确金额排序；事件镜像先折叠，唯一强证据或唯一最近才自动接受，多候选出 open-leg pending。该层不写关系。

关系候选索引按来源组/币种/规范十进制金额/UTC 日期分桶；金额键去尾零避免 Decimal 等值不同键；转账配对窗口外扩 1 秒再交给匹配器；无效时间事实保留兜底；退款门默认关闭，由调用方显式传入。索引只剪枝，不决定最终配对。

转账/购汇迁移只读标准化字段；对方账号组合先验证，唯一目标才收窄，多目标保留歧义；普通转账同币种等额时间窗内且非同一账户；购汇用显式 fx 类型和不同币种；长窗口按唯一目标全局分配，阶段 C 用种子/已用集合过滤。该层不写关系。

支付镜像迁移只配平台×银行同账户同币种同向；强规则唯一才接受，ICBC 同分并列降级；金额差/晚窗只待确认；确定性字段分组先折叠，互唯一和贪心保证 1:1。FactView 文本交叉沿用 Python 鸭子类型（无 description 时退化为对象字符串比较），不能改成语义 counterparty 子串判断。锚点字段按原 dataclass 默认输出。

关系管线迁移按镜像→退款对准入→转账→菱形→商家退款顺序；镜像/退款占用扩展到等价事实，转账与菱形消耗事实，商家退款按镜像事件共享剩余并按事件上限；Phase A 退款在镜像揭示同一事件后降级。发现短路只排除四阶段均不可能的消费种子。该层不持久化。

关系计划迁移用不依赖数据库 ID 的稳定引用和提案键；序列化只输出引用/状态/证据，extras 先转 JSON 安全值并按字符串排序集合；缺内外部上下文摘要时拒绝并重新确认。行转事实视图需保留 Python 的 or-default、attrs 和默认子类型语义。该层不规划或持久化。

关系写入同时存在父流水 ID 与现金部分 ID：导入来源引用先解析成父流水，关系仓库按父流水找到唯一现金部分，端点占用、镜像冲突和投影图必须使用现金部分 ID 查询。旧 Python 导入人工镜像辅助方法把父流水查得的关系与现金部分端点直接比较，在两类 ID 不同的测试数据上会漏检；迁移时以当前关系规格的唯一端点要求为准，验证旧系统镜像退让和人工镜像占用拒绝。即时导入拒绝退款提案时，在进入下一提案前记录被拒绝的现金部分；不可达的旧分支不作为合同。
联合导入确认必须在写锁后先查原键及上下文永久收据，再读取/重验暂存。提交前回调须紧邻实际 COMMIT 将状态切为未知；驱动返回异常后不能依据 rollback 判断未提交。结果核实用独立不等待写锁事务，只把已识别锁忙映射为 pending；清理失败不替换永久成功收据。

完整确认差分固定两端内部收支 UUID 生成器并比较分配次序，不删除冻结依赖身份。账户仅修订变化且资格不变可以提交；预览后停用必须拒绝且整笔无写入。故障诊断分别核对阶段、范围、位置及总数，收据写入失败沿 request 阶段，不新增客户端未知阶段；409 计划变化可携诊断，但不能因此标可自动重试。核实请求的缺键/未知字段沿 Python 严格协议返回 import_payload_invalid，不能沿用不一致的 Kotlin 旧断言。

原确认回归复用原匿名行与 editor 身份，每场景独立测试库；生成器只提供输入，Kotlin 必须重建预览并直接核对原断言。完整回滚快照覆盖全部生成表并区分 SQL null 与文本 null；原生批次路径须观测父项先于现金部分、来源实际写入后故障以及未逐行回退。快照仓库 load 返回当前账户名称，原始存储使用稳定 ID，余额断言按真实仓库接口取键。非锁存储错误须注入到核实的独立写事务，不能只让最初的用户查询失败而漏测 pending 分支。

Concurrency tests must wait for both independent command workers before releasing their start gate. Port current assertion bodies even when historical test names suggest partial skips: stale plans reject the whole command. Refund bundles preserve a single decision group and visible zero net amounts; result pages follow current split/deletion while durable receipt summaries remain unchanged. Observe persisted relation and empty-page inserts before fault injection, and compare all business tables after rollback. A zero-write projection assertion must include state advancement, which every successful active-component replacement performs.

When translating plan_input_invalid into plan_changed during revalidation, carry the existing diagnostic through the conversion. The command boundary enriches known file/row locations while retaining the inner stage, scope and error ID; generate an outer diagnostic only if none exists. Trigger internal read failures inside the actual discovery/build stack, verify complete rollback and unchanged staging, and decode the real 409 response with the current Compose client without retry.

计划发布的原断言使用独立构建依赖覆盖边界，生产默认仍是完整业务构建器。构建选择须深复制，保存解析正文须来自原暂存字节；构建器或返回视图的可变列表不能污染已保存对象。缺省 `detail_values` 是空映射，显式 `null` 的处理明细／文件结果必须在条件替换前拒绝；不得用 Elvis 缺省抹平两者。121 条原合成条目以原生循环直接核对筛选、总数、绝对序号和游标绑定；JVM `Int` 无法接收布尔值时用真实 HTTP 非整数查询验证既有协议拒绝，并登记语言边界适配。新测试环境变量必须同组提供可复现的匿名场景准备脚本和说明，每场景隔离，不能只依赖本机脚本。

原计划 API 断言必须经过真实 Ktor 上传／预览／确认／收据／核实路由，不能只调用内部服务或复用 Python 预期响应。JDBC 故障匹配同时去掉 SQLite 反引号与 PG 双引号，并断言确实到达；测试读取重放必须在读取失败或事务完成异常后保持 unknown，不能返回 not_committed。公开计划读取可通过只破坏文档还原输入、保留有效 JSON 和元数据证明不物化来源正文；完整 get 必须失败，四类公开 HTTP 读取仍成功。全局 viewer 拦截也属于导入协议，须在正文解析前返回完整 field_errors／retryable；真实回包同时交给现有客户端消费者验证，不新增 UI 或公开测试参数。

旧流水列表与关联候选分别保持原准入、排序、游标和页长，不能复用后者而漏掉人工流水或草稿。完整 HTTP oracle 必须实际装配旧 mutation service，避免把未注册路由的 404 当作合同。嵌套 JSON 原值直接保留，不能经通用对象字符串化破坏分类或现金部分元数据；数字编号、Decimal 全部小数位及草稿空值独立核对。没有当前 Compose GET 消费者时明确记录，不新增假调用者来增加接线计数。

流水删除按父端点与候选引用清理，旧确认闭包只决定投影刷新范围，不据此删除其余合法确认边。同决定组只补删未确认边；候选被删时推进保留关系／事项修订，保留其余证明。投资资金关联按现金部分编号删除，先清投影关系引用，再软删除父项并维护余额和稳定收支；原文及现金部分不物理删除。原测试中的 projection_ref 包含不同 transaction_id 时只比较原断言要求的 projection_id，不能把更强但错误的整对象相等当回归。重新导入须实际重建预览并提交新收据；锁后权限须以真实 pg_blocking_pids 证明等待，不能用未完成 Future 代替。

普通草稿保存复用准备器和 writer，保持草稿且不写余额；现金部分归属对照补丁前的当前列表，数据库 JSON 来源说明先转业务映射再派生。严格编号、修订、JSON 深度及实际转义后字节预算在摘要和数据库访问前验证；原键收据在当前修订前重放。流水、公共条目、事项、投影与永久收据同事务，实际 COMMIT 前恢复未知状态；提交后详情不可读或已删除仍返回已知收据，不重写。HTTP oracle 克隆 PostgreSQL 数据后须同步整数序列，冻结模型默认值应替换 callable 实际读取的时钟／UUID，不能只改已被编译缓存的 column.default。

维护预计图复用导入已准备操作之后的关系与展示管线，不构造伪导入文件。直接入口保留操作的来源元数据，额外流水种子及决定组进入同一只读上下文；快照变换在当前关系构建前执行。操作没有导入行引用时使用 `transaction_id`，不能读取空 `row_refs` 的首项。当前关系预览与提交重建携带上一冻结方案，内部引用保留候选行编号；JSON 与存储金额先在适配边界转为业务值。写入使用一次新鲜关系图执行完整验证，底层证据映射使用整数关系键；清理冲突候选先于现金部分移除，字段／关系／投影／永久收据同事务。原测试提交前的夹具由 Kotlin 自行重放预览和断言，检查点对照数不计作原定义迁移数。完整维护、多成员及其余故障组合仍须独立验收。

证明变化的原提交夹具恢复至预览后的初始状态，由 Kotlin 在真实方案产生后重放差异；不能在变化后的库上重新预览来代替旧方案拒绝。故障须在实际字段、当前关系成员、投影或收据写入后注入，核对完整表回滚、未消费的方案及原键重试。新鲜关系上下文的次数以 JDBC 读取观测证明，并兼容 SQLite 与 PostgreSQL 的标识符引用格式；不以模拟计数替代。无效预览步骤保留原错误，核对原上下文未被替换或隐式确认。

普通维护也必须使用同一共享业务图和确认执行器，不能重新拼装单流水视图或另写现金部分／余额。转正式仅以该操作的现金部分为发现种子，正式备注不发起发现。原 diamond 银行退款匹配器把空种子列表视为假值并回退扫描，Kotlin 的可空集合判断须同时覆盖 `null` 与空列表；以原完整平台链、两条新增边与第二边写后回滚验证，不用弱化组范围来规避差异。新增纯层金标准字段不改变数据库种子时，复用初始库也须记录精确指纹差异和等价证明；不得笼统放行变更输入。

手工转账双端财务修改必须在当前收支成员、同一转账身份及唯一活动确认关系均匹配后准备。公共补丁留在目标操作的 `entry_edit`，两端类型由最终账户／币种推导；快照覆盖关系子类型，真实提交保存两端后按原修订替换同一关系并验证完整图。不得把一端保存作为部分成功；实际第二端／关系写后故障核对全部业务表回滚，依赖变化在预览之后真实实施。原同币种夹具的另一端是贷款账户，预期为还款，不能凭参数名推断普通转账。复制提交夹具时核对最终 JDBC 路径真实存在，不能只替换日期后缀；夹具地址错误轮不计通过，恢复初始状态后重跑。 合并目标公共编辑与双端财务操作时，保留公共编辑动作，差异按字段合并且公共编辑优先；不得只保留 `entry_edit` 而覆盖动作／差异。该授权修复须同步 Python 和 Kotlin，并直接验证任一端、备注／金额组合、无变化、精确余额及原键重放。账户编号错误的公开回包应先用原 HTTP 复现；原通用 503 不得猜改为字段错误，Ktor 不能泄露内部异常页面。

原关系发现异常的回归须先以真实准备值验证单现金部分／显式多现金部分，再在实际发现器已具备种子后的账户别名读取处注入异常；仅在早期读取阶段失败不能证明发现故障停止。核对相同异常只到达一次、全部业务表不变，以及维护上下文没有创建或导入会话原始正文／修订和未发布方案保持。生成器执行原测试到预览入口前停止，不生成断言预期或修改公开配置。

来源问题服务须累计采用正文后的非空来源说明，后续空补丁不应抹掉；来源固定字段先于显式补丁准入校验。差异按来源 incoming／effective／protected 生成，再合并显式补丁，受保护且最终未变的字段仍保留来源差异。所选事项编号按字符串排序，补编号 `10`／`2`，不能数值排序。只清所选事项，不伪造父修订或余额更新。生成器区分原 `_posted` 准备与目标导入，保护旧正文的补充状态须使用 ORM 实际修订。完整只读图使用隔离库，并观测全部 SQL 无写入；真实提交／故障库每次恢复初始状态，全部业务表、事项正文、余额与方案共同核对。39 原组合与 2 补充场景分开计数，实际 HTTP 采用响应还须通过现有 Compose 仓库／领域映射。

确认独立记录只结束所选身份问题，不新建实体或搬迁来源绑定；未明确转正式时保留父修订、字段和余额。预览按流水计独立确认、按所选事项计解决数。绑定摘要须包含实例编号、创建时间、完整键、修订、强度、稳定证据、解析器和完整覆盖 JSON；创建时间沿 Python UTC 文本及微秒格式，JSON 先转业务值，不能字符串化。即使父修订未变，绑定新增／删除／重建／转移或证据变化仍拒绝旧计划；SQLite 自动编号重用也须由新实例事实识别。微信范围只取可信绑定或文件／未绑定隔离，不从原文或旧编号授信。预览 HTTP 来源绑定冲突沿原安全 422，确认保持确定未提交；不要全局捕获并改变其他调用方。真实写后故障、删除暂存后的永久重放和后续再次导入都要实际运行，完整图与写库分开，原 23 组合及 9 定义分别计数。

身份草稿合并必须冻结双方绑定、原财务值与完整双向问题／关系候选闭包，包括同决定组非直接成员；等额或未知现金部分不得按顺序猜对应。先删已证明等价的候选，再保存目标、转移问题／绑定／引用并退出源，全部共用原确认事务；移动问题保留实例、正文和来源，旧候选证明不能刷新授信。泛型更新使用生成字段自身，按 Java 类型重新取得字段可能丢失 UTC 自定义绑定；实际写后再次读取并对照两库。HTTP 成功及原拒绝分支均需实际对照，关系拒绝仅在维护预览适配原安全 422。

当前记录迁移按真实 JDBC 事务验证最新值、回滚、跨连接旧快照及工作区范围；原输入的 SQLAlchemy 对象状态须明确适配。没有未刷写实体输入的 Native 不新增 ORM 删除注册表，混合定义保留为 Python 特有参考，另以实际软删除与类型边界验证，不虚增迁移数或绕过财务外键。来源快照必须覆盖完整关系闭包，根范围为空不代表其他成员无范围。现金部分事实变化即使父修订未变也须拒绝旧确认。发现失败结果须稳定去重、跳过已有组且不泄露虚拟编号，可在禁止 SQL 的连接下直接验证。

维护 HTTP 故障须在实际收据写完后、JDBC 真正提交后或首次原键查询处注入，核对到达次数、完整业务表、原键收据及安全正文。已有成功的原键查询故障只能保持结果未知；成功后的详情读取失败仍返回成功收据。jOOQ 选择语句可能使用 `execute`，不能只拦 `executeQuery`。草稿计划／关系维护的公开输入禁止 `issue_resolutions`，显式空数组也拒绝；内部 `_input` 黄金或已保存空列表使用专用适配，不以放宽公开解析兼容内部夹具。服务测试省略内部字段前必须明确验证其为空且意图正确。

维护 HTTP 原断言以真实请求覆盖完整任务：草稿同键重放返回原收据和新鲜当前详情，维护重新预览替换完整输入但不延长有效期；合并收据保留源结果引用并定位目标，联合关系金额为零仍返回同一投影。匿名种子在原首个客户端创建前停止，只保存输入，不生成原响应预期；每个写场景独立准备，源文件剩余未对应定义保持 pending。

维护提交核实须与导入核实一样使用非阻塞工作区写锁，锁后重读当前用户、工作区和写权限，只有 `storage.busy` 返回 `pending`；其他存储故障必须传递。永久收据／上下文消费核实先于临时正文，过期或缺失方案转换为原业务错误 `plan_expired`，私有拥有者拒绝不能被通用提交错误包装。清理临时正文失败后已提交收据仍可按原键恢复；用真实独立数据库锁验证，不能只抛模拟忙异常。

无文件维护发布的合成构建器只隔离 CAS／TTL、冻结分页和公开摘要边界；默认仍调用真实业务构建器，不新增运行配置。取消核对当前写权限、私有拥有者与计划修订；确认装载复用同一严格版本检查，永久消费先于正文，提交边界回调仍位于已无永久结果之后、正文读取之前。较小预算用相同底层暂存与内部 codec 构造参数适配，失败核对原字节／etag 不变；意外 flush 必须实际执行 SQL 再证明读事务回滚。

旧交易查询与旧流水列表使用不同合同：只取正式流水，UTC 月份、父／现金部分账户与分类筛选，默认 30 条且范围 1–200。原 Decimal 零值经应用转换为 `"0"`，非零保留数据库表示；现金部分同账户的两条记录不能按 distinct 误算为单账户回退。超长整数须保留原 422，不能因递归正则栈溢出泄露内部错误。读取页与账户在同一快照，真实 JDBC 故障停止整页；没有实际客户端消费者时保留两端 TODO。

维护预计图的原断言必须通过实际预览、暂存及详情验证，不以完整图对照替代。原 monkeypatch 用实例内部业务图工厂适配，默认仍调用真实构造器；未调用与完整自身现金部分种子在 `discover` 阶段观测；程序异常须先通过真实候选准入，再在账户别名读取等实际发现位置注入，不能在外层 override 一进入就抛出。拒绝必须证明没有暂存发布、没有写 SQL 且全表不变。额外确认效果按整组与明确用户决定去重，连带成员的既有发现问题不得误报新关注，摘要不携带私有字段值。

公开 OpenAPI 必须从实际注册的方法／结构化路径筛选，保留原公开参数名和完整可解析 schema；不手写另一份接线清单，不广告未实现或未装配的操作。原 schema 资源可用经验证的生成器维护，运行时不调用 Python 或数据库；缺少新路由的合同要显式失败。历史 Alembic 定义可整体 retained，但须实际运行原升级／拒绝降级，并由 Native 读取真实最终状态；历史准备器拒绝已存在 SQLite 和非空／非 `_test` PG 目标。写边界通过真实应用业务读取及 PG 阻塞队列验证，不能用 Future 未完成代替实际锁等待。

当前关系预览原断言须分别覆盖完整摘要与每个候选、原决定、选择证明失效及联合组。重放检查点的上一计划使用真实暂存存储并重绑定上下文身份，Native 自行读取当前数据库和重新生成计划；不把源预期注入返回值。驳回后的刷新必须实际提交，使用独立可写初始库，不污染只读差分基线。原 `CashEntryRepository.current` 禁止读取断言可通过真实 JDBC 到达栈观测，与其他必要批量读取区分。

稳定收支服务测试须分别核对来源字段、公共值、独立恢复值和重建后的当前归属；预览正确不代替实际确认。解除继承只针对实际不同的人工字段，冲突公共值必须先保留待决定，再按明确公共值合并。ORM 初始夹具转为 JDBC 准备时显式填写原 Python 默认值，公开认证响应仅提供邮箱时按邮箱读取内部用户 ID，不向 DTO 添加内部字段。来源硬键排除测试同时验证原绑定存在，手工来源发现短路需观测实际进入规划前的边界。

公开 JSON 流预算必须在读取时按实际字节执行，不能用 Content-Length、解析后对象大小或暂存预算代替；尾随空白即可绕过后两者。使用真实路由超预算请求验证接线，分块且伪造长度验证机制，越界不得进入模型／业务处理。解码与字段校验切到 IO 调度器，单请求调度器上的并行调用证明不会阻塞。确认先校验原幂等键再读正文，保留严格修订字段和错误优先级；Python 宿主日志定义可 retained，Native 必须用真实宿主日志验证隐私，不能添加虚假访问日志来凑相同标记。

- 维护接口整体验收须把业务、提交、发布／恢复、公开协议及严格来源输入的原文件一并执行完整范围门禁；独立审查无 finding 或业务类已映射不能代替纯输入定义。测试专属补齐可按逐文件 SHA 证明生产、资源及既有回归类不变，分别保存原完整双库证据和新纯测试结果；不得宣称重新执行了整条全量命令。

- 解析暂存原断言迁移时，条件对象假客户端必须保留实际字节、ETag／修订、元数据和流关闭；观察器仅委托具体端口或注入原边界故障，不替代公共存储行为。静态类型不可构造的混合参数原定义整体 retained；损坏存储拒绝是兼容证据，不能冒充原发布前输入异常。只读快照以完整读取失败的负控制验证无关行未恢复，公开缓存始终检查当前对象、拥有者及有效期。

- 原合同要求 SQL 查询次数时，应在实际 JDBC `execute`／`executeQuery`／`executeUpdate` 到达时观测，并限定到目标操作；`prepareStatement` 次数不能直接冒充执行次数。复用完整父项的来源断言保持原匿名关系输入，动态主键须明确对应；原 `Int` 配置混有 Python Bool 时保留整定义和原全部参数，Native 验证原整数界限，不扩大生产签名。

- 迁移账户别名旧接口时保留按账户名称查找的校验顺序、通用历史命名空间及停用／投资账户的原资格；不引入新的类型枚举或自动关系确认。用原应用实际生成完整 HTTP／别名行 Oracle，核对 Unicode 错误及 SQLite／PG 约束差异；锁后与写后核实当前权限，实际执行插入和提交边界故障。无实际客户端调用者时如实登记两端入口 TODO。

- 投资读取须按原规则转换完整 limit+1 后截取，保留默认精度报错、列表／证据的账户资格差异、来源白名单及活动资金关系。游标 Base64 字符串先检查 ASCII，保留原 ASCII 噪声宽松性；Python 整数兼容正则使用等价无回溯重复，保留 Unicode 十进制数字、空白和位数预算。故障只在真实 SQL 执行后注入，当前身份与共享快照通过独立连接写入证明。SQLite 输入用 online backup 捕获并验证版本与新增行，存在 WAL 时不能只复制主文件；准备器用绝对路径／正确 PYTHONPATH，成功后才开始 Gradle。

现金—投资资金调拨使用原完整测试第一次服务构造前的状态作为输入，只能停止准备，不替代业务结果。扫描保留父记录时间／编号顺序和现金部分端点；普通候选、机构候选、最近精确机构候选及人工决定按原规则处理。确认在工作区锁后重查端点，复用真实投影维护事务。备注的 UTF-8 存储校验在关系存在、状态与可用性校验后执行，不能盖过原错误。故障观察器须覆盖真实单条和批量执行：已有投影可能走 UPDATE／executeBatch，不能只监听 INSERT 就宣称投影回滚已测。PG 锁等待观察须刷新统计快照或使用独立观察事务，不能以 Future 尚未完成替代真实阻塞证据。

估值迁移先区分纯服务与真实行情提供器，不以成本常量冒充已接通账户／报表接口。普通估值保留 Decimal 精度 28，报表调用使用 80；数量与乘法输入按 NUMERIC(38,18) 校验，提供器原始有限单价在无数量时不提前套用数量范围。保持数值系数／指数／负零和源时效边界；历史结果不回退当前行情，批量先整体校验，保留原顺序和重复身份及实际剩余预算。Python 股票身份的字母／数字判断与大小写使用已验证生成数据，不能以 Java Unicode 属性猜测等价。单调时钟是浮点秒，只按数值比较 0／0.0，不用该归一修改财务数值表示。

迁移脚本的文件写入使用明确仓库根目录，Gradle 单独在 compose/ 执行；写入返回成功且目标文件存在后才启动构建，不把失败准备后的旧状态当作本轮验证。中文维护脚本使用仓库 .venv 的 Python，避免系统 Python 版本／输入解码差异。

行情提供器迁移须用已安装原 SDK 解析匿名响应建立输出 Oracle，再由实际 JVM HTTP 输入比较；下载函数的假帧不能代替 SDK 的 float64、交易时段、末尾同周期合并、重复时刻和全零／空 OHLC 清理。NaN 价格在当前批量中跳过，历史选中缺失值按原规则不可向前补价。原定义／参数、Native 方法及生成差分样本分别计数；本机 HTTP 不代表上游当前可用或账户／报表已接线。代理、密钥、请求数、功能截止时间和有界并发按实际传输／工作线程验证，不因排除性能测试而删除功能预算断言。

账户余额／财务报表迁移须区分纯应用旧行级分支与生产当前投影 reporter：生产不得因未初始化或失败回退原始流水。普通账户估值精度 28，报表在 80 位上下文中完成账户和统计，保留同币种报价及原成本回退。账户名／币种按源字符串操作拒绝内部错误，不把数字的宽松归一扩张为所有字段 `str()`。原 setup 在首次服务／重建前捕获，数据与期望分别绑定；实际原 HTTP 的重复参数、权限、下载调用和小数文本用 Netty 核对。SQL 故障在实际执行后触发，观察表名不能依赖一种方言引号；PG 复制主键后须对齐 sequence。快照交错使用真实财务命令与投影发布，下一读取证明新余额与新报表一起出现；没有实际客户端调用者时保留 TODO，不造消费者凑接线。

FX 依赖迁移须保留默认进程缓存中的缺失结果，以及注入 fetcher 绕过缓存和传播原异常的边界。同币种、日期截取、Unicode 大写／长度按原调用顺序执行，不增加币种白名单。Frankfurter 只有请求失败／空响应才回查五天，已返回的错误结构／价格停止；真实 JVM HTTP 保留原请求头和超时。Decimal 偏差按两次除法、减一、绝对值逐步舍入，Infinity 除法产生的零指数也属于源表示，不能用普通零替代。原日期解析错误须在适配边界按源返回缺失，不能泄露 JVM 内部异常；原五定义、生成差分和 Native 方法分别计数。

投资重放须保留基础币种面值与历史不完整的负持仓，释放成本按 40 位除法后 HALF_UP 量化 18 位，其他重放为 80 位。原输入只捕获每个对象首次调用前状态，独立对象编号不能串联；Native 自行完成序列及原中间／最终断言。默认子类型用原明确映射，不按允许集合首项猜测；既有账户币种不得擅自字符串化，坏快照结构是内部失败，不能伪装输入错误。纯函数失败后的局部内存与数据库事务原子性分别验证，快照有限性不得混入 NUMERIC 范围限制；周期运算的默认 28 位与调用方 80 位显式传递，原类型／字段／错误顺序保留。

投资写入须按原账户资格和 security／crypto 共用快照桶持久化，事件和完整快照同事务保存。原仓库 `price` 虽不入库也执行 18 位／默认 28 位量化校验；不得只校验实际列。成功结果的全部 JSON／UTF-8 编码须在提交前完成，used 字符串保留原始 surrogate 元数据，未使用的操作字段不提前验证。原 HTTP、13 表全列、真实插入／快照／提交故障与实际 PG 锁队列分别验证；普通功能回归从精确初始快照准备，Oracle sequence 同步。磁盘不足的失败不计通过，清理只按已登记专用目标进行并保留初始输入与证据，再恢复新场景重跑。

投资持仓迁移须分别保留 loadHoldings 的当前标的五列名称读取与 loadPortfolio 的完整事件投影；不要通过查询全历史再丢弃字段实现本地阶段。名称按原 SQL ID／事件时间次序及配置现金并集归并，不读 event.note 代替 source_payload.note。精确数量／成本保留全部 null 与空数组缺省，零数量先跳过成本验证；用实际注入的行情／历史端口证明不调用。查询层币种保留原字符串方法边界，truthy 非字符串须停止，原空值回退仍保留；数据库读取器原有显式 str 转换不能一起改掉。纯依赖与完整组合 HTTP 分开验收，源映射只有完整功能断言和双库证据完成后才计 migrated。

完整组合估值须在本地读取前启动共享预算，按资产类型／规范化身份去重；普通查询并行历史，进度回调先发当前估值再开始历史。持仓数量各自乘同一单价，不复用首个账户的市值。保留 Python Decimal 28 位、负零表示、原时区名称／微秒偏移及最近快照记录基准；零持仓跳过和已卖清周期收益分别验证。用实际独立 JVM 和提供器到达标记证明忽略取消的后台工作不阻止退出，不能只检查 daemon 属性。压缩的历史对照须解压并核对当前组数；写入回归使用原初始状态，失败准备不计通过。

组合周期时区须对照原完整名称、历史转折和未来规则，不能只检查名称或以固定偏移替代历史别名。月／年 replace 保留当前 fold，周起点的 timedelta 重置 fold；原微秒偏移保留本地时间与实际瞬间。生成资源按原 TZif 摘要去重，用每个转折及未来边界验证；无已验证生成器不得声称自动生成。

刷新协调迁移保留请求代数，执行中新增手动请求不能被旧完成标记清除。订阅懒进入、重放和最新版本分开验证，重复关闭只减少一次人数；取消与首次进入的交错不能遗留计数。缺价回退按当前持仓数量重算原单价，不重复旧市值；进度与最终快照按原值语义判等。停止须真实等待在途查询完成，再释放其数据库依赖。


持仓 HTTP 接线须在本地快照结束后才调用行情，工作区服务在启动 worker 前固定，返回／SSE 每次发送前重查当前身份。订阅注册到协程返回之间的取消也须释放：外层 finally 持有在 IO 块内赋值的资源；真实断连测试不能替代这一交错。刷新原始参数和规范化 worker 输入分别断言，完整 HTTP 对照中已捕获的请求值不可忽略。现有客户端若误用其他接口的 `ok` DTO，按原 `accepted` 合同修复并先用真实响应复现；随后直接使用现有读取／刷新／SSE 消费者连接本机双库宿主，不以 MockEngine 单独证明网络接线。

环境驱动的实际客户端回归须关闭配置／构建缓存并给测试任务传 `--rerun`，或有等效已验证的任务输入绑定；切换 SQLite／PostgreSQL 和本机宿主地址后核对 XML 执行时间。`UP-TO-DATE` 或 `FROM-CACHE` 的旧报告不能证明当前环境的网络消费。

券商 CSV 迁移须直接对照每行原始文本单元、业务行标识、完整事件与逐步重放快照，保留原 UTF-8／CSV 字段上限、Decimal 默认精度及异常类型。IBKR 股票总额与佣金分别记录，FX 净额不生成名义外币资产；期末现金校准使用真实总结来源行。不能为了共享读取器而改变已验证现金格式的兼容行为；内部解析通过不代表投资导入事务或 HTTP 已接通。

原 Decimal 解析行与直接调用映射的字符串输入须区分真假性：数值负零为假，字符串 `"-0"` 为真；来源身份和回退金额可能因此不同。不能只对照解析输出及独立映射，必须补解析→身份→事件完整链路，且不把类型标记写入来源原文。

Schwab 负 JRN 预扣税须对照完整账单内同日期、标准化发行人说明的正 DOI 配对，不能仅按负金额或 JRN 类型推断。保留 DictReader 的短空白行跳过顺序、原列名／值及日期／参照号排序。Python Unicode 数字和空白正则行为须有实际样本对照；提取券商共用文本／金额工具后同时执行原券商全部回归。

券商正则须区分 Python 与 JVM 的空白、十进制数字及点号换行集合；`(?U)` 不保证等价。金额括号的一元负号须保留 NaN 符号和非零下溢后的负零，原零按原规则转正。手续费相加既检查精度也检查指数溢出；共用工具发现原行为偏差时，原所有使用方须补失败对照并重跑。

DFZQ 总发生金额按原净额拆出所有非负已识别费用；买入费用不可拆时保留净额，不能重复扣费。文本和汇总快照须保留实际来源单元，日期截取按 Unicode 码点。直接调用的 Decimal、字符串、整数和浮点输入保留类型边界及原格式；证券快照最终价格沿原直接 abs／format，不能用归一后的价格替代。原测试捕获完整结果仍须绑定 Kotlin 解析对象链，金额关系单独断言。

uSmart HK 保证金与日内融产品的来源前缀、原始文本、现金标志优先级和重复序号必须保持。原 `_fmt` 保留输入小数位，只量化超过 18 位的小数；直接 Decimal／整数／浮点运算不能统一按字符串转 Decimal。原测试的字典副本仍须从 Kotlin 解析结果复制并应用差异，显示备注不应改变来源身份。

PDF 解码按批准合同使用 PDFBox 独立 JVM，原外部工具版本和密码文件不能机械移植。每次新进程、共享服务准入、堆与输入输出上限、总期限和退出确认须由真实故障进程验证；先终止再释放管道及名额。密码通过管道，不经 argv／环境／持久文件。清除环境、私有目录和 JVM 内存参数不构成操作系统沙箱，也不是总 RSS 限制；新提取器必须重新对照真实版式和来源证据。

投资文件分派为 IBKR／Schwab 增补元数据时保留类型化解析行，不能复制为普通 Map 后改变 Decimal 零值或符号语义。PDF 沿统一独立 JVM；文本采用原 UTF-8 replace 和通用换行，CSV 继续严格编码。文件分派验证完整行、来源身份及事件，不能据此关闭数据库导入或 HTTP 原测试。

投资文件名仅供格式分派时也须保留原 POSIX Path 的重复分隔符、`.`／`..` 和隐藏文件后缀语义，不以简单末段截取替代。原断言迁移同时保留完整来源键集、现金快照子类型、错误注入的独立原输入及重复后的明确条数；同样字段由生产映射器生成的预期不能替代这些独立约束。

投资导入历史 `base_currencies` 沿原账户原始行读取，字典按键、字符串按码点迭代，不能替换为当前 `currencies` 字段。非空不可迭代值维持拒绝。已知截断文件的解析异常在文件边界窄适配，保留 HTTP 错误与无写入；不得以捕获任意异常继续导入。

同步迁移以整次事务保存事件、持仓快照和游标，处理分块不形成提交边界。外部抓取后重验权限、工作区及账户身份；空扫描也验证游标写入。原字典键值对先按数值／布尔判等并保留首键表示，再做 JSON 键编码；不要先转字符串。原生自建工作区测试与黄金完整状态测试使用独立 PostgreSQL 库，避免自增序列污染预期身份。

凭据 YAML 迁移必须用原 SafeLoader 实际结果核对标量和复合值，不以 YAML 版本标签推断兼容。未知标签只进入安全构造拒绝，显式标签先验证节点类型；数字／时间构造异常只输出类别，不保留正文或 cause。重复键保留首位置／原键表示；PyYAML binary 的非严格填充行为需真实对照。凭据加载器通过不代表真实连接器／同步 HTTP 已接线。

Polymarket 同步必须按当前实现读取 Activity 与同一区块的 pUSD CHECKIN；旧测试中的 Transfer／compound 名称不定义新协议。原全活动映射后再过滤 since，游标取全部活动时间而非仅已入账事件。28 位 Decimal 舍入、原 ID 优先级和完整来源正文分别对照；真实传输验证坏 JSON／UTF-8、RPC fallback 和 HTTPS CONNECT 认证，代理认证不得进入源站头，不用全局认证配置。

冻结计划详情的缺省值与明确 null 分别核对：默认复制读取保持缺省组成项字段缺失，浅复制校验才补空列表，明确 null 始终拒绝。独立详情快速校验不得绕过相同错误；回归 fixture 须先满足公开 item 形状，避免前置失败掩盖目标分支。

来源文件位置的重试证明要保留原文件、解析器和 provider 身份的交叉输入；不能以新provider编号覆盖可证明的不同位置。页声明合并直接迁原边界／包含性／筛选／时间精度／策略／页总数参数，并验证资料不变。黄金覆盖或现有实现不代表已迁移原断言。

单文件来源匹配须按原完整多重集合和账户归属核对；可靠 provider 编号与稳定证据共同参加批内冲突传播。直接更新失败不得消耗候选，人工字段保护按每个目标分别比较；保留原覆盖范围、数量和可信别名全部参数。性能定义与功能定义单独记录，不以功能通过代替耗时预算验证。

资金账户原断言必须区分扫描结果与完成解析的结果；group_refs、组成项金额、atomic／aggregate 及当前账户资格都按原阶段核对。钱包回退保留支付方式与方向的完整笛卡尔积，脱敏检查原完整账号和尾号；精度检查同时验证全部输入不变。内部异常必须在原适配器、分段或求和阶段真实触发，不能用银行资料读取失败代替。

迁移原 monkeypatch 时，优先在 internal 实例上开放实际依赖点，保留生产单例调用面、默认委托及原异常 catch 类型；禁止跨租户共享的全局可变替换。故障注入后以同一异常对象、到达次数和默认实例正常结果证明原边界，生产依赖改变后重跑真实 HTTP 会话查看、建议和提交重建调用链。

原参数混合可构造值与静态签名无法接收的类型时，按整定义保留并运行原完整参数；原生直接覆盖可构造值，协议层类型拒绝单独验证。不同层的错误类型／错误码不能互相替代；来源范围仍按用户确认值和完整文件证明核对，不受昵称或内部账户映射影响。

迁移小型归一 helper 的原断言时，直接验证原职责对应的生产函数；完整处理入口可能执行额外步骤，不能拿相似输出替代。必要时只开放 internal 可见性，保留函数体与调用方；原 Python 未使用的参数可按事实省略，但须核对实际实现和记录理由。

快速路径与完整路径的等价测试须同时对照原运行时实际结果，避免两条路径一起错误仍通过。结构缺省与明确 null 分别验证，浅复制校验保持输入不变；Python 数字识别、字符串转整数及整数转字符串预算分开核对，Unicode digit 不等于可转换十进制数。原参数可从原测试标记导出，追加边界不重复计原参数。

原空集合分支天然不访问存储时，可用拒绝任何 SQL 的连接代理验证真实入口，无需新增生产替换点。原 Python 类／模块退役定义不能通过随意选择 JVM 类或创建空 facade 冒充迁移；按整定义保留原参考，另对真实拆分服务和共享错误做兼容核对，现行解析会话不属于退役范围。

交易所 mapper 核对原 Decimal.normalize 的精度、毫秒经过浮点到微秒的时间和完整来源正文；金额负值／零费／明确null按原阶段分别处理。分页raw_count在两路各自去重后计数，忽略的资金trade仍参与游标，费用子项不增加原行数。外部调用失败经窄端口保留原类别与消息后分类重试，内部程序错误直接停止；已确认保留的provider缺陷单独记录，不在等价迁移中暗改分页或资产范围。


CCXT 迁移用真实本机 HTTP／HTTPS 验证 SDK 签名、完整 TypedMap、市场加载和已确认保留的限制。关闭 SDK 不等于关闭 REST；独占传输须跟踪底层 Future，取消并有界等待，不终止全局执行器。浮点 JSON 保留 NaN／Infinity 表示以进入原数据校验，金额不能把 BigDecimal 变成字符串。签名随查询参数顺序变化时分别验证实际请求签名和参数集合；仅独立并行的冷市场请求可忽略到达顺序，分页请求保持顺序核对。同步必须在财务写事务前关闭抓取资源，避免清理失败把已提交结果误报失败。


同步路由先核管理员再读 body 或凭据，网络后及提交前在同一工作区事务重验；原内部服务的写权限合同不随 HTTP 收紧。每次抓取独占 SDK 并在返回事件前关闭，清理失败必须阻断旧游标回退；抓取与清理双故障、清理自身中断／取消／Error 分别回归。runInterruptible 只证明服务端请求协程取消能到达阻塞 SDK，测试客户端取消不等于服务端取消，不能据此宣称断连必不写入。原十个交易所集成用原四笔成交／资金事件、真实 mapper 和费用项后置失败保留幂等与整批回滚；凭据脱敏新回归的源定义、28 参数与输入指纹单独登记，不替换旧基线。


调用内校验缓存只存成功，键保留字段存在性、标量类型、小数原表示及现金部分顺序；不使用金额的数值等价 equals 代替表示键。未知容器／字段／类型应绕过缓存后执行原校验，容量达到上限不能跳过校验。两个原调用点各自创建局部实例，以真实校验计数和禁用命中的负向对照验证接线；没有 JVM 对应类型的参数整定义保留，并运行原完整参考，不能替换输入冒充迁移。


默认账户建议的原范围测试必须先真实确认使用证明，再以同一原文件及另一来源读取建议；直接写映射不等价。原文件摘要和用户确认来源范围会参与分组，不能经重新上传合成字节悄悄改变摘要。建议资格与提交资格按原阶段分别验证，币种不足仍建议的原行为不能在迁移中顺手收紧；每个原参数使用独立匿名状态，并同时观察建议无写 SQL 和全部表不变。

默认账户发布需保留成功使用证明、冻结选择和确认后建议只读检查。原 ORM 带 version_id_col 的账户改名、启用状态、类型或币种修改隐式递增修订，Native 直接 SQL 必须显式加一。故障应落在实际写入之后；小批次先核对冻结操作与批量阈值，再按原谓词失败，不新增生产钩子或以预写拒绝冒充回滚。

账户重验必须保留原变化阶段与旧选择修订；预览前账户已变化时，不能把原 expected_account_revision 更新成当前值而掩盖兼容断言。草稿转正式需真实先保存草稿再核对同一 ID，人工合计流水分别验证各现金部分资格。失败后的无收据使用实际按键查询，不仅查表数；快照应取账户变化后、提交前，保留有意变化的事实。

文件变化恢复测试须先真实建立含人工选择的计划，再执行原上传／删除命令；连续变化删除原文件，不是新增文件。清除 provider_id 的原输入用于核对 occurrence_key 对应流水 ID 稳定，不能换成有编号记录。验证 TTL 不续期前推进时钟；固定时钟下过期时间相等无法发现错误续期。发布故障在真实暂存 CAS 前拒绝并核对原字节，构建故障适配须说明具体时点。

候选回滚测试在实际整行保存后使用同一命令连接修改候选，不能另开事务。验证已写入行、候选链接及修改后的备注／修订可见，再抛指定故障；外层须检查故障本身的类型和消息，防止较早 SQL 异常也满足回滚断言。原 row_revision 的 onupdate 须显式适配，不遗漏隐式修订。

公共编辑从真实详情取得 edit_ref，重新预览沿用当前全部选择；来源 keep_existing／defer 不能吞掉公共备注。初始投影种子与提交后实际重建分开登记，旧目标测试通过真实条目 patch 和同事务维护改变事实。两个同名镜像 helper 不一定相同，必须用原导入来源。故障相位适配需明确说明，失败返回不能替代全表回滚及原键重试断言。

Multipart 迁移必须累计实际原始请求流，不能只加文件正文或信任 Content-Length。原临时文件生命周期适配为真实通道、正文收集器及请求协程，并在测试自身清理前核对全部资源退出、文件资源非空。保留原解析依赖的换行、字符集回退、重复头和头预算；先用实际原解析器验证合法输入，再确定错误边界。Ktor scanner 的输出缓冲需有界且及时刷新；原格式终止由状态机确认，不以正文中存在标记代替。

原测试可以从 AST 提取字面量预期，但必须保留全部调用、比较运算及局部赋值关联，未知语法立即拒绝；不能运行被测函数生成预期后宣称独立断言。职责内联时仅抽取同一生产规则并保留实际调用，不拿完整归一后的结果替代子步骤，也不创建无人消费的兼容标签函数。


迁移 CORS 原断言时，保留原 Origin、路径和请求头。当前全应用认证需要匿名有效令牌时明确登记适配；预检仍无认证且不构造真实提交。来源被拒绝的原测试只要求缺少允许来源头，不新增未经原合同要求的状态码。

原测试同一函数同时含固定来源和关键词推断时，按实际生产职责精确选择原函数列表；导出与原生调用两端验证类型参数，不用名称前缀宽选。资源分区分别核对原有和新增定义／断言，整体唯一性不能代替各分区实际执行。


原单行规则包含中间记录字段时，使用调用内观察实际归一路径产生的记录，回调只收集，返回后断言；不要重调私有解析函数冒充实际中间值。仅启用观察时复制只读快照并隔离嵌套来源原文，保持生产默认无分配。原清洗摘要和当前保留的账单原文分开核对；数值预期取原字面量，布尔组合保留原表达式。


原 CSV／XLSX 测试迁移须使用原文件工厂的编码、表头、可选列和完整行，真实解码后再核对逐行规则。可执行经过精确 AST 指纹核对的原输入工厂，不能执行被测实现生成预期；二进制输入保存在仓库外。跳过记录在实际归一早返回前观察，最终行保持明确原因；测试名称提及的边界只有原断言真正验证时才计覆盖。


旧包装中的拒绝测试仅在失败确实发生于现有共同生产规则、且原全部输入和错误断言保留时可直接迁移；先证明调用顺序，不能因外层输出不同机械保留全部规则，也不能据此恢复已退役路由。


已退役包装测试按整定义保留时，逐条区分旧标签／计数容器与仍有效的业务义务。原 helper 没有文件的输入须明确列出补充日期、金额、渠道与 owner；真实归一结果交给当前来源和资金两个消费者，不能手造中间结果。支付宝无声明 owner 不建立全局范围，微信默认范围与付款账户分开；一条跳过原始行不等于零原始行。


旧平台编号替代须用原真实文件贯穿解析至匹配，账户声明变体也必须真实解码。无声明的支付宝编号不能自行获得账户级绑定；微信默认范围和命名范围分别验证。文件摘要取实际字节，存量夹具只从首次来源出现形成，不偷塞传入绑定。匹配结果比较完整对象，金额冲突与可变说明更新分开；XLSX 空行变体须实际序列化并核对读取器的真实位置。


旧内容哈希退役的原调用须保留未传原文与部分原文的区别；行值和显式原文冲突时，当前完整原文适配必须说明采用哪一个。用实际来源出现验证不同位置保留、同位置重读／更新和同批矛盾；同摘要矛盾夹具只能称已解码输入边界，不能宣称不同真实文件字节相同。未知覆盖范围不伪造完整证明，未变检查使用调用前独立序列化快照。


旧 PDF row helper 的数组长度异常不能直接等同当前几何错误。以原单元内容真实绘图，分别验证空行、完整网格中的缺字段和真正短表头；不要提前补数组或注入错误码。绘图函数及常量固定指纹，不调用业务 oracle。缺项候选的 ready、完整资金结果和正式记录校验分别核对，有效控制行继续可用；匿名文件不冒充缺失的原真实账单。


原卡号测试可通过真实归一回调保留中间记录，但不得把中间掩码账号提升为当前跨文件来源范围。原 `get` 与下标访问分别保留；同文档串行污染测试不能拆成逐行独立调用。原扁平文字行到表格的字段补空、合并日期时间和分隔行适配须明确，当前最终字段、来源范围和显式账户选择另核对。


旧跨卡内容哈希的当前替代须把实际首次来源出现的绑定作为存量，再验证另一完整卡号不合并；重读声明必须真实再次读取文件。旧缺列文本不能隐式补币种来制造等价，显式账户选择也不能替代缺失的交易币种；问题候选、资金准备与正式校验分别断言。


旧转换退款配对的当前替代须把原扁平文本单元按日期边界映射为当前表格列，按列位置分配日期、卡号、收支、币种、金额及摘要／对方户名／交易场所，并断言全部尾部 token 只被使用一次；旧 `tracking_pairs` 的强度、候选数、金额关系与退款信号改从当前 `CashRelationPlanning` 提案读取，不重建旧结构。旧文本模糊商户规则已退役，当前同账户、同币种、商户文本匹配为强，多候选按最近且可覆盖金额归并，不要求同额；弱置信到强关系的差异必须整项登记 retained 并写明原断言，不能静默改写。

旧退款强度分类器的抽象输入不能直接当作关系事实迁移；须显式补全同账户、币种、对手方和金额，再让当前关系规划器观测强度。单候选同额商户匹配接受为强、多个同簇候选保持待确认弱时，保留原强度字面量但删除旧 `_ccb_refund_same_cluster` 分支，不把分类器逻辑搬进测试。

离线 UI 演示的丰富场景由 `createDemoFinanceTrackerGraph` 加载，基础 `DemoFinanceRepository` 保留最小夹具；内置账单字节与导入仓库共用合成行定义。演示核对沿用完整候选／成员、显式不可用原因、精确筛选、预览不写入与原键幂等合同，不用固定汇总冒充当前筛选结果。更新后通过启动器核验构建指纹，再提供实际可访问入口；端口占用时换空闲端口，不复用其他任务的服务。

现金共享月份表头在 compact 按“月份独占首行、每币种收支横排右对齐”呈现，账本与导入核对共用；regular／wide 保持月份与汇总分居左右。现有几何回归同时检查币种、收入、支出同行及各行右边界。

现金导入文件条目保留上传原名（仅 basename 与长度边界），来源账户标签仍按账户合同处理。第一步允许继续追加，客户端首文件创建会话后最多三个上传并发，服务端仅对追加使用受限合并。失败文件提供重试和通用删除；第一步下一步遇失败或需密码时以禁用样式响应点击并显示 Snackbar，必须处理后才能继续。初始依赖加载失败只显示安全错误与关闭，关闭保留恢复信息；具体合同见 `import/statement-import`、`import/cash-import-session` 与 `shared/cross-platform-presentation`。


离线账本改造的跨平台原语位于 `compose/business-domain`：`pythonFloatText` 负责既有 Python 浮点文本，`parsePythonCashTimestamp` 负责 ISO 时间解析。backend 同名适配入口复用共享实现；继续下沉来源身份时沿用这些原语，摘要口径以现有来源黄金、全部二进制指数邻值差分及时间样本核对。共享原语通过不代表查重、配对、缓存或导入接线完成。

来源覆盖证明的数据形状复用 `business-contracts/CashSourceCoverage`，包含性与可比较性在 `business-domain`，backend 仅适配 JVM 时间对象。数据移动不改变 `ParsedImportCodec` 的显式字段编码；后续迁移须继续核对来源匹配、归一与暂存 round-trip，不能仅凭共享 serializer 通过就宣称旧会话接线等价。

来源文档的证据模型与完整性合并复用 `business-domain/CashSourceDocuments`；backend 仅保留 typealias。组合不同页面时仍分别核对来源、解析器版本和文档键，原解析完成标记不代替页集合与声明一致性的完整证明。

来源出现身份与绑定键复用 `business-domain/SourceOccurrence`；摘要输入只含定位字符串、空值和整数位置，财务 fields 不参与。跨平台摘要回归保留固定 Python 预期；非法 surrogate 输入须在运行时构造并先核对码点，避免 Kotlin/JS 编译期替换。

现金精确值与类型目录复用 `business-domain/CashAmount`、`CashRecordTypes`；backend 通过 typealias／薄函数保留调用面。金额数值相等与原始系数／指数表示分别验证，不能因改为共享类型而归一掉负零、尾零或巨指数零。

现金字段决定与完整性校验复用 `business-domain/CashRecordPolicyRules`；backend 保留原始值归一和深层相等适配。共享规则测试不代表端侧生产归一已接通；后续来源 Resolver 与导入消费者须继续核对真实平台值、异常类别和原黄金结果。

来源匹配决定复用 `business-domain/CashSourceResolver`；泛型索引仅作为等价键，backend 保留 JVM 金额／时间索引和随机 ID 分配。构造时的字段、覆盖证明与摘要桥接须保持原值语义；共享匹配测试不代表端侧生产适配或完整导入已接通。

已解析行／文档、文件视图与来源批次组合复用 `business-domain/ParsedCashDocument`、`CashSourceRows`；backend typealias 保持 `ParsedImportCodec` 的显式暂存编码。共享模型不改变 Number 计数的原 JVM 类型，也不表示 CSV／XLSX 解析与完整客户端导入已接通。

批次来源查重复用 `business-domain/CashSourceBatch`；JVM 仅适配既有 Decimal／时间内容值。请求内去向缓存包含直接目标状态，候选／去向引用列表复用 `SourceReferences` 只读快照和内容 hash；核对原黄金结果及大歧义组，不把计算次数检查视为完整性能验收。

来源行类型决定复用 `business-domain/CashRecordTypeRules`；backend 绑定原 Python 文本／真假桥接，账号规则入口委托共享实现。核对类型优先级、方向、子类型与真实归一消费者，不以简化 common 值桥接测试宣称完整端侧归一接通。

对方账号规则复用 `business-domain/CounterpartyAccountRules`，模型只保留共享定义，重建证明仍在规则实例内验证来源原文。数字表由共享生成器维护，公开访问返回副本；尾号扫描须保留旧 JVM Regex 的实际补充平面边界，以差分与固定跨平台回归核对，不能仅凭理想 lookbehind 语义判断等价。

现金账本同步版本基础见 `LedgerTransactionVersions` 与 `CashSqlTransaction`，Alembic head 为 `20261104_54`。事实写入口显式调用连接的 `markLedgerChanged`，同一物理事务按工作区合并；保存点与完整事务回滚须保留版本原子性。`LedgerVersionContractTest` 要求显式 `FT_KOTLIN_LEDGER_CONTRACT_DIR`，真实 PostgreSQL 另需同目标 `_test` 的 `FT_TEST_POSTGRES_URL`；不可把仅有计数器测试通过写成全部事实写入口或快照接通。

目录事实的版本标记位于 `AccountService.execute`（收据重放后）、`CategoryService.bump`、`AccountAliasRepository.add` 和 `CashFundingMappingRepository.publish` 的实际变更分支。`LedgerCatalogVersionContractTest` 同时核对成功、同值映射／重放、权限拒绝与晚期回滚；原别名 HTTP 黄金继续逐项比较，并单独验证所触工作区的账本版本。尚未接线的其他现金仓储不可由目录测试代验。

来源元数据版本入口见 `CashSourceWriteRepository` 与 `CashSourceDocumentWriteRepository` 的实际变更分支；`LedgerSourceVersionContractTest` 核对同值、候选 ID、空页与整体回滚。测试夹具仍遵守平台编号必填合同；其他现金仓储须分别接线。

原子流水与条目版本标记见 `AtomicCashWriter`、`CashEntryRepository`；dirty flush 与成员变化沿用保存点回调，重复 reconcile 不推进。`LedgerEntryVersionContractTest` 核对版本与保存点，原条目仓储回归保留查询预算及身份锚拆分合同，不能假设随机 UUID 选择的身份锚总是第一行。

普通导入、批量新行、草稿合并、人工创建和删除的版本入口分别在对应 writer／repository 的真实 DML 分支。组成项同值判定须按后端实际存储比较（SQLite fixed 文本、PG 精确数值），保留尾零／负零表示更新与其他元数据。`LedgerCashWriteVersionContractTest` 覆盖这些原子性分支；旧 writer touched 黄金的基线差异见当前 tasks，不得宣称完整通过或静默改期望。

关系与候选修订、资金对应 store、分类直写及逻辑删除沿各仓储显式标记。资金同值 store 比较原字段，分类仅物化修正不推进；显式关系命令原有 row_revision 语义仍保留。`LedgerRelationVersionContractTest` 覆盖无关系删除、bind／kind、类型解除与资金重扫，原 current-role 黄金及分类回归另验，不以合成用例代称原资金全矩阵通过。

版本读取入口为 `LedgerReadService`／`LedgerReadRoutes`，共享 `LedgerVersionDto` 用十进制字符串保留完整版本。新增 API 须维护 `compose/backend/openapi-additions.json` 并用原 OpenAPI 导出器生成／check；运行时注册仍要求每个路由具备合同。`LedgerReadContractTest` 用完全限定类名运行，避免通配匹配旧 CashLedgerReadContractTest；该接口通过不代表快照或端侧缓存已接通。

快照读取复用 `LedgerReadService`／`LedgerSnapshotRepository` 与共享 `LedgerSnapshotDto`；15 类事实使用显式字段和工作区过滤，精确标识／修订／金额为字符串。PG 在首查询前使用可重复读，SQLite 同连接读取；版本与事实不得拆成独立事务。`LedgerSnapshotContractTest` 覆盖并发写入、草稿／墓碑、非空来源／候选／资金集合及返回前重鉴权；当前为单响应，客户端缓存尚未接入。

事实写面审计按快照表逐一检查 DML、batch/store 及辅助仓储所有者。`CashTransferRepository.relation` 独立标记，原转账回归分别验证两现金腿、混合腿与纯投资腿；派生余额／物化投影和投资事件不推进现金事实版本。整体删除工作区先撤选区会话，不保留可继续读取的已删版本。

关系端点／角色／证据、影响闭包和提交分组复用共享 `CashRelationPolicy`。backend 仅适配 JVM 大数输入及图算法所需 BigDecimal 扩展；共享规则使用 CashAmount 精确等值与符号，返回只读容器保留原快照语义。common 角色断言需在 JS／Wasm／Android 执行，不把 JVM 图回归当作完整端侧发现实现。

来源流水匹配视图与核心判断复用共享 `RelationFact<A>`／`CashRelationCoreRules<A>`；A 仅桥接后端 BigDecimal 与端侧 CashAmount 的符号。任意长标识排序保留十进制规范化与缺对端 Int 0；UTC 文本用共享纯日历 formatter。后端原时间对象适配仍保留，候选索引与完整发现并未由核心规则代迁。

快照 `rules_version` 来自共享 `CASH_RULES_VERSION`，与工作区数据版本 `ledger_seq` 分开；contracts 只声明字段，不反向依赖领域实现。客户端缓存／派生接线后须消费规则版本，不能把元数据已返回当作重派生已实现。

候选召回复用共享 `CashFactCandidateIndex`，后端仅适配 BigDecimal 绝对值键与 JVM 时间／日期；端侧默认使用 CashAmount 和共享日历。保留原 ISO 日期文本比较和无效时刻回退；时间对象的 UTC 日期延迟转换，不能把原日期异常吞进秒窗口 fallback。年 9999 的旧范围限制与回归见当前 change，纯迁移不顺带修改匹配行为。

提案／证据模型、退款文本门和菱形配对复用共享 `CashRefundSignals`／`CashRefundDiamondRules`。端侧精确容差用18位整数位串，时间差保整秒向零截断；后端保留原金额／时间桥。已知证据整数通过 `toJsonValue` 在类型擦除前编码，旧 Map 出口仍供 JVM；开放 extras 不能据此宣称通用 Any 数字已跨平台保真。

标准转账、定向转账与个人购汇共用 `CashTransferMatchRules`，backend 仅桥接原 BigDecimal、时间及共享候选索引。金额差值按原表示 scale 输出；不能用数值归一化丢弃证据尾零。账号标识的内部空白剔除保留原 JVM ASCII 集，JS 正则 `\s` 更宽，不能直接替代。

快照字段双库对照入口为 `LedgerSnapshotParityTest`，使用同一固定合成资源 `ledger-snapshot-parity.json` 与完整 HTTP 期望。分别设置 ledger／基础 contract 配置目录；PG 必须显式同目标 `_test` URL。不要用动态 ID／时间归一化替代逐字段等价，也不要把 `*LedgerReadContractTest` 的宽匹配误当作只运行现金用例。

几何判断共用 `CashRelationGeometryRules`，backend 只保留 JVM 输入／UTC 日期与原时间差适配。业务日先取原字段优先级并保留小数点截断行为；纯日历短日期解析须先校验完整 yyyy-MM-dd，不能因 helper 更宽而放宽输入。卡尾的 ASCII 空白与 ending 的 `İ/ı` 需要显式保留 JVM 语义；几何黄金及旧 Java 差分见对应 common／jvm 测试。

关系计划模型共用 `RelationPlan<A>`，稳定引用与提案键共用 `CashRelationReferences`，backend 保留原始行／时间／余量和旧计划序列化适配。提案身份只含原稳定引用、kind、rule 与候选引用（排序但不去重），不能把 status 等核对结论混入；它也不能代替完整结论指纹。原 JVM 摘要 UTF-8 对非法 UTF-16 的问号替换由共享实现显式保留，不能依赖各平台默认编码。

完整镜像判断复用 `CashMirrorMatchRules`；JVM 仅桥接 BigDecimal 与原时间／文本。CashAmount 默认入口保原金额文本和去尾零组键；只读 `RelationFact<out A>` 可 widen 后 copy 金额文本，不另造事实映射。开放 JSON 中生产者已知的整数可用 `SourceJsonInteger`，原 Float 1.0 不得改为整数。common 41 次原调用与 backend 43 次回放分别覆盖镜像本体和仍留后端的 canonical 管线，不把前者宣称为整条关系发现。

平台退款编排共用 `CashPlatformRefundRules<A,T>`，backend 保留原始 BigDecimal／LocalDateTime 宽松解析与 Duration.toNanos 溢出；窗口用 Duration.seconds 对应的 Long 秒。common 测试适配器不代表生产原始时间已迁移。旧 Alipay 7CSV 回放有缺交易订单号的原夹具，当前主规格要求拒绝，基线亦失败；不能以篡改夹具或放宽读取器宣称原回放已通过。

Phase A 硬键退款汇总复用 `CashRefundHardKeyRules<A,T>` 与同一平台退款规则，backend 仅桥接精确金额和原文本。来源身份分组、订单／商家键优先、linked 排除及余量复制与递减仍为原规则；test-only 规范值适配器集中在 `PlatformRefundTestAdapters`，不作为生产原始值入口。

商户退款排序共用 `CashRefundMatchRules<A,T>`，金额 `Comparable` 必须与精确数值等值一致；`CashAmount` 以符号、对齐位串实现。backend 与 local 复用 `normalizeCashMerchant`，Java Unicode word／ROOT lowercase 只在独立测试保留原参考；backend 保留原 Duration 纳秒适配；不能以 JS 普通 lowercase 替换（`A1Σ` 的 Sigma 上下文不同）。原 Double 天数窗口的纳秒舍入也须保留，common 规范夹具适配器不是端侧生产输入入口。

共同 `normalizeCashMerchant` 已提供冻结属性／单码点映射及同一 Kotlin 查询游标，Java 候选与状态说明保留在 tasks 指定的仓库外证据目录。原过滤后 UTF-16 坐标、每个 Σ 独立状态及同一游标内后退／前进次序须保持；`query == 0` 不移动状态，缓存复用条件为返回位置严格小于 `query - 1`，不能写成小于等于。正向／反向索引只共享按位置计算的只读结果，每方向以三行残余状态预处理，避免长标记与多 Σ 重复扫描。反向码点读取与后续向前读取在半代理位置不可互换；Mn／Me 尾缀与假名长串的最长前缀竞争不能简化成静态词组。现代 cased 属性、宿主整串 lower 和旧失败 rawKind 表不能充当精确替代。原 21,309 夹具文件及其内容摘要必须保持，完整标量、公开属性、真实查询轨迹、长串及各端运行沿 tasks 实际证据验收；内部文本处理不等于生产发现器／完整提案回归已接通。

关系发现总编排复用 `CashRelationPipelineRules<A,T>` 与 `MatchContext<A>`，JVM 通过既有 matcher 的 `sharedRules` 接入，不复制算法。累计额可在夹紧前超过单条事实的 NUMERIC(38,18)，须由宿主精确累计值实现 add/subtract，不能用事实字段校验器限制中间值。common 原镜像回放包含全部 43 调用；两次 canonical 调用由共享管线负责。

内部 `LocalCashPlatformRefund` 已把宽累计、原平台日期和精确纳秒差组合到平台退款及 Phase A 硬键；`parseLocalCashPlatformDate` 必须保持原 Offset→移除大写 Z 的 local ISO→原归一文本前 19 UTF-16 单元的 SMART 次序。保留月末夹紧、24:00、offset 秒、nanosecond 和原 LocalDateTime UTC 年边界；不能改用 Python ISO 或浏览器 Date。原平台／硬键黄金用新配置执行，日期 oracle 直接使用未修改的 JVM 原表达式。公历 helper 仅增加 internal 可见性，不改现金 occurred_at 年界。平台金额转换只将 null／已识别文本语法失败作为无金额；归一域外标量属于程序／协议错误，不能吞成 null 或继续匹配。此配置仍不包含精确商户正规化或完整发现接线。

本地发现数值配置使用内部 `LocalCashRelationArithmetic` 的宽累计值及同一 core／serialization，接收归一金额文本、CashAmount／CashDecimalLiteral 或内部累计值；不把开放 Any 数字猜为已验证金额。无舍入累计与单字段 CashAmount 范围分开，证据金额转 plain 文本且保留 scale，不能偷偷套用投影图的 80 位舍入。`relationElapsedNanos` 精确组合 epoch 秒及纳秒，Long 正负边界与原 Duration.toNanos 一致，溢出不能饱和。原平台日期适配由内部 LocalCashPlatformRefund 配置另行组合；精确商户正规化和完整生产发现仍待接线，不能据内部配置通过测试宣称本地导入已经可用。

关系计划 Map 序列化、明细形状和退款初始余量复用 `CashRelationSerializationRules<A>`。原始行读取仍为 native 适配；cache 容器递归共享而 JVM 标量识别保留，BigDecimal 用 plain 文本。`initialRemaining` 使用 applied_amount 并允许负余量，不能与 PlanCache 以退款全额占用的 refundRemaining 混用。common JSON 生产者对 serialize 的已知整数传入 `SourceJsonInteger`，backend 默认保留 Int 供旧 decoder，不能抹平 Float 1.0。

稳定引用解码、决定复用、seed／组成项展开、别名索引和全额退款剩余值复用 `CashRelationPlanCacheRules<A>`。backend 保留原 truthy／BigDecimal.toInt 及图合法性校验；Int 截断／溢出是迁移原语边界，不擅自改为新拒绝规则。多组成项没有 ordinal 时补索引，显式 null 保留；单组成项始终 null。不能把此编码／解码端口存在宣称为端侧图验证或缓存生产接线已完成。

关系余额／损益摘要复用 `CashRelationProjectionRules<F,A>`，事实金额 F 与累计 A 分离；public common 入口 `projectCashRelationFacts` 接受 CashAmount 并输出精确字符串合计。内部 `LedgerArithmeticValue` 使用 DecimalRepresentation／位串加减与 HALF_EVEN，保留 scale，不承担存储或原始平台输入解析；超过 NUMERIC(38,18) 的合计不能强行转 CashAmount。此 helper 不等同于账本浏览 `CashProjectionRules`，完整逐行投影和图还须迁移。

投影枚举／失败继承、确定性分组与树门禁复用 `CashProjectionTopology`。`CashProjectionComponents<I>` 保留 I 的数值自然顺序，native 仍用 BigInteger；不要转 Double 或把字符串字典序冒充数字序。来源排序仅接收原 amount 的符号，零与缺值分开；转账标签保持 repayment→fx→cross-border→ordinary 优先。完整金额图与逐行投影仍在后端待迁移，不把拓扑共享标为端侧浏览完成。

逐行投影模型共用 `CashProjectionFact<I,T>`／`CashProjectionRelation<I>`／`CashProjection<I,T>` 和 `CashProjectionValues<I,T>` 的平台原语解释。backend typealias 继续用 BigInteger／Instant，并以真实 shared companion 的顶层扩展保留原 create 签名；Kotlin 不能通过 typealias 引用嵌套 Companion 类型。原反射边界测试需指向扩展所在 Kt 类并校验 receiver／参数偏移，不删除 Instant／Boolean 断言。private constructor/copy 与金额、状态约束保留；memberIds 复用既有只读 RelationSet 快照，不能把共享模型存在当作全部投影算法已接通。

完整逐行投影复用共享 `CashProjectionRules<I,T>`，native 保留创建与存储标识桥；累计使用既有 `LedgerArithmeticValue` 并按原步骤执行 80 位 HALF_EVEN。代表仍按原 JSON 文本排序（小数秒的词典顺序不可改成时序），JS 已知 rank 用 SourceJsonInteger，原 native 时间文本由桥保留；退款分配键按首次值去重且冲突拒绝。全量 common 向量由原 backend 投影 oracle 生成并用 --check 检查，不人工维护另一套预期；金额图及端侧浏览接线需另做，不由共享算法存在推定完成。

完整金额图与联合决定校验复用 `CashRelationGraphRules<I,T>`，native 只解释严格整数与文本 ID；原生 Byte/Short/Int/Long/BigInteger 接受边界不能放宽到 Bool、浮点或字符串。组件／父项总额、角色与账户、资金占用、镜像／退款分配和联合组修订按原顺序校验，累计仍为 80 位 HALF_EVEN。common 全量图 oracle 包含超过 Long 上限的 ID，测试适配须精确保存原值，不能筛掉该行或通过 Double/Long 取巧；端侧生产 ID 模型与完整规划仍需接线。

关系计划的依赖摘要复用 `CashRelationPlanFingerprints<A>`，两类摘要保持各自完整字段、null、排序和规则版本；native 原精确金额／开放值编码仍薄适配。虚拟 ID 用 `cashRelationSnapshotId` 复用既有非法 UTF-16 问号替换后计算 SHA-256 前 8 字节低 63 位，0→1、已占用顺延及 MAX 回1保持；不要与来源身份或用户核对结论指纹混为同一语义。

虚拟投影上下文、局部依赖和提案／缓存计划合法性复用 `CashRelationValidationRules<A,I,T>`，native 的原始值、时刻、ID 与开放 JSON 解释保持薄桥。上下文累计按原无舍入精确加法，不能套图内 80 位规则；引用 JSON 的已知 index 显式 SourceJsonInteger。保留旧局部上下文仅裁投影事实而保留全组件的保守拒绝，不借本次下沉扩大自动放行；完整 Phase A→D 入口仍须另行接线。

完整 Phase A→D 计划入口复用 `CashRelationPlanningRules<A,I,T>`，同一实例组合硬键、后续管线、索引、序列化、摘要及校验。保持 Phase A 扣余量→既有占用→seed 排序→B-D→重叠降级→整组过滤→摘要顺序；硬键内部 LocalDateTime 与后续 Instant 不强行统一，星投影只封闭其内部时间类型。元数据 putIfAbsent 的替换须覆盖显式 null 而保留非空 blank；规范化 fixture 适配不能当作客户端生产原始行解析已完成。

Python Unicode 14 大小写表固化在 business-domain；原 ft 导出器已退役；native 只查询单个映射并复用共享 pythonCashLower，不公开可变 map/ranges。lower 保留上下文 Sigma 与孤立 UTF-16，upper/casefold 保留一对多映射；不能以宿主 lowercase/uppercase 代替。商户匹配器原 JVM regex/lowercase 属于另一合同，不因移表顺带改成 Python 口径。生成器必须用项目 Python 3.11／Unicode14，common 全量 12,625 向量覆盖映射和 Sigma 范围边界。

来源解释、支付方式与商户归一复用共享 `CashSourceEffects`，现金备注优先级使用同一 `cashRecordSourceNote`。来源有限正则保留 Python 冻结空白／数字及 JVM UNIX_LINES dot／末尾锚点；补充平面数字用类外完整字符分支，不能回退到 Wasm 不等价的 supplementary class range。品牌 I/K/S 等价字显式保留，原金额、可信身份和 protected 字段规则不变；common 全量 2,208 样本与 JVM 旧模式差分保护，仍不能据此宣称完整生产行归一已接通。

原始账单文档与行复用 business-contracts 的 `CashStatementDocument`／`RawCashRow`，backend reader、PDF 进程和 normalizer 使用 typealias，不另造同形模型。保留原文、null／空 map、documentPosition、Long 计数及原默认值；跨模块 nullable 属性使用局部值，不以删除断言绕过 smartcast。模型共享不代表端侧 CSV／工作簿读取器已实现。

平台／银行／工行逐行归一复用 `CashStatementRowNormalizationRules<A>`，`CashStatementAmountValues<A>` 仅封闭精确金额标量与表示；native 保留同一时区、日期、原始值、分类／账号、正则和只读观察快照。负零来源文本与数值零分离，Alipay 缺行整文档拒绝、其余来源 skip，ICBC 失效额外清文档／页证明，均保留原规则。非空行为以原 JVM 黄金及真实文件／observer 回归核对；common 空／不可读文档测试不代表生产端侧时区、正则与读取器已完成。

来源时间统一调用共享 `normalizeStatementTimestamp`；`export_statement_source_timezones.py` 固化原上海／香港 98 次历史转换，并由独立 Java atZone 生成 4,454 条 common 向量。数据私有，gap 前移、overlap 选较早偏移，未知 source UTC、显式 offset 优先、微秒和年 1→UTC 年 0 保持。生成器遇到未来循环规则拒绝生成，时区数据更新须随规则版本评估；不能以固定八小时或宿主本地时区替代。

已解码账单调用生产入口 `normalizeLocalCashStatement`，内部使用精确计算值、独立零文本与共享来源时区／类型／账号配置，不把计算类型暴露到 parsed 合同。字符串来源 payload 摘要仅处理字符串 map，保持严格 UTF-8；裸 JVM 数字类按 ASCII，显式来源 Nd／空白按冻结数据，默认行结束和 UNIX_LINES 分开编译。common 原平台／银行／工行 11＋14＋6 案例完整复用；文件字节读取和资金／完整导入接线仍须另行实现。

平台文本／分隔记录读取复用共享 `CashPlatformStatementTextReader`／`parseCashStatementDelimited`；byte 解码与工作簿安全验证仍在 native。JVM 空白集和无效 UTF-16 替代预算需显式跨端保留；元数据正则保持 ASCII 数字／空白与 JVM MULTILINE，先展开固定数字字符类再处理裸数字，避免 JS 禁止的嵌套类。10 个原文本 oracle 不代表空字节或 ZIP／工作簿读取已迁移。

严格字节文本复用 `decodeCashStatementText`：UTF-8 先行、GB18030 回退、双重失败 null、仅去一次 BOM。`export_statement_text_charset.py` 从原 JVM Charset 穷举映射并生成独立 fallback 向量；冻结表私有，不依赖宿主字符集。表升级须重生核差异及规则版本；helper 不代替调用者的文件预算／ZIP／XML 守卫，也不代表工作簿或完整端侧导入已接通。

银行表格规则使用共享 `CashBankStatementTextReader`，CCB 二进制仍经原 POI；工银亚洲 byte 入口 `readIcbcAsiaStatementDocument` 保持 BOM／source 守卫与 Java UTF-16 REPLACE 的坏高代理项消费语义。覆盖保留本地日历翌日后上海解析，不改为香港，年 0 可跨 UTC 负年；该边界只扩私有日期 formatter，不扩规范事实时间范围。原 bank oracle 6–10 项共用到 common，0–5 的 OLE／工作簿仍 native 验证，不把文本共享视为整个文件读取完成。

资金模型复用共享 `CashFundingModels`，native 零参 JSON 扩展传原 `sourceJson`，不复制同形 DTO。账户／分摊决定使用 `CashFundingResolutionRules<A>`；native 保留原精确金额与 protected total 实例分派、严格 revision 类型、深复制与每 resolve 新验证缓存。`PostingValidationIntegrationTest` 需显式原 operation fixture，未配不能计通过；common 值桥／空 posting 测试不代表完整端侧生产配置或 NFC／扫描已迁移。

来源资金扫描使用共享 `CashFundingScanRules`，native 注入原 NFC、支付方式／组件解释、严格摘要、金额文本和深复制，protected parts／组件方法仍可按实例覆盖。资金 (?U) 空白使用原 Java Unicode White_Space，不能换成多四个 C0 的 Python 空白；邮箱沿原完整 Python 集。固定混合类先展开避免 JS 嵌套类，码点截取不拆代理对；common 简化桥不是完整端侧生产来源适配。

来源账户与支付组件决定复用共享 `CashSourceAccountMappingRules<A>`，native 仅解释原金额／文本／真假／整数／Unicode 模式及摘要。allocation sum 保持精确，不套用资金 resolve 的 80 位总和；解析仅捕获原 NumberFormatException，完整卡号与来源优先级不变。循环内使用内部数字表，公开 defensive-copy getter 不作逐字符查找；common 简化桥不代表完整生产 Unicode／开放值已接通。

昵称规范组合使用共享 `normalizeCashNfc`，native 原 trim／digest 位置不变，不改 NFKC 或写回原文。`export_cash_nfc.py` 从原 JVM 固化规范分解、合法组合与 CCC；Hangul 公式化，短／长 run 稳定排序保留相同 CCC 与组合阻塞，非法 UTF-16 不修复。数据升级重生核差异与 rules_version；工具期内部 CCC API 不得成为运行时依赖。

来源账户 Unicode 词法用私有 Java word／Nd 表及完整码点边界，不混用 Python 14 数字集合。金额匹配保最左与货币分支优先，非法前边界后的 `A12.34元` 仍须识别 `34`；避免对长数字逐后缀执行正则。`export_source_account_text.py --check` 与全部码点 JVM 差分保护原式，common 含非法 UTF-16／补充平面／长串；此接线不代表开放标量生产配置完成。

端侧来源账户使用 `LocalCashSourceAccountMapping`，金额解析保留 BigDecimal 的 BMP Nd／最终 scale 边界，不以 IntMax 直接限制正指数，不套用 CashAmount 列精度。`scanLocalCashFunding` 接真实模式、NFC、严格摘要及归一 JSON 容器深复制；64 原 scan 向量和完整账户 oracle 不删断言，已知整数显式标记。运行时孤立代理摘要先验证是否实败，不能把静态猜测记为红绿修复；scan 完成不代表 resolve／posting 完成。

`resolveLocalCashFunding` 在 JSON 边界保留整数 token，不能先经 Double 或用 JS `is Int` 判断修订；浮点 1.0／1e0 与布尔／字符串仍拒绝。生成组件 ordinal 由 local 整数标记编码、native 默认保持。真实 posting 复用共享策略及值桥，57 原 resolve＋64 scan 都须完整断言；local 无成功缓存不改变正确性，但性能验证须测真实路径，不沿用 native 缓存结果。

来源计划准备复用 `CashImportSourcePlanningRules`，native 读端口保持先records、后document proof、再批次解析及原证据顺序；旧无证明 complete 不授信。原source-plan oracle曾残留已退役微信回退和scope字段，应从当前原exporter完整刷新，不能恢复旧fallback或手改预期。来源断言可共用helper独立跑真实双库，但原完整view比较须保留；其旧suggestion.account差异独立记未通过，直到合同修复或接口实际退役，不以聚焦方法通过冒充完整类通过。

端侧字段策略复用 `LocalCashRecordValues`；`prepareLocalCashRecord` 接收 JSON 而非擦除数值类型的 Any。整数 token 与生成 ordinal 标记单独保留，浮点相等按精确二进制值而非最短显示文本（0.1 不等于十进制 0.1）；`encodeLocalCashValue` 仅用于闭合 JSON／归一记录树，未标记 Number 按浮点处理。字段完整结果及失败类型需与 native 对照，不能以 ready／changed 两个标志替代；资金 posting 与后续查重共享同一值桥。

本地来源匹配调用 `resolveLocalCashSourceRows`／`resolveLocalCashSourceFile`／`resolveLocalCashSourceBatch`，不得用 fixture adapter 代替生产配置。原 49 batch 与 12 match oracle 完整回放；内容金额元组的生成整数显式标记，OffsetDateTime 整分钟省秒与 Instant 文本分开。生产配置不会替调用者选择候选，后续缓存读端口须保持工作区／来源／直接绑定及非微信时间候选资格；无 provider 的 UUID 只在新意图分配一次并持久化。

`prepareLocalCashImportSources` 从同一完整 snapshot 读取候选／绑定／组件／文档与认证空页后调用真实 local batch。保持 import／legacy、非删除及来源筛选、直接绑定＋非微信 source×time、250 分片后插入顺序；微信不能回退旧ID／原文。所有选中alias须解码，即使不在verified集合；未匹配坏coverage不得提前导致失败。`decodeStoredCashSourceCoverage` 共用原存储形状及默认／坏结构规则，native 保持原Unavailable包装。只读适配通过不代表缓存持久化、权限或规则升级已完成。

导入选择结构由 `CashImportInputRules` 共享，native 与 `prepareLocalCashImportInput` 各自提供原值桥。保持 entry→account→row→relation 校验顺序、引用／重复／动作和正整数修订；JSON入口不能把1.0／1e0、布尔、字符串当整数。common_fields立即真字段校验，entry／row patch值仍由后续操作计划校验；不要在纯迁移时顺手扩大拒绝。公共字段集和计划错误同形共享，分页／会话视图尚不属于本地输入入口的实现。

操作计划完整原始回归使用 `export_cash_import_original_operation_contracts.py` 的全部 13 个 SCENARIOS，资金 resolve 专用的 only-base 目录不足以运行整类。完整 operation golden 的种子须与原 exporter 一致：条目 identity_transaction_id 默认根流水、支付宝固定交易范围、金额未知用 null；非法金额字符串属于另一拒绝分支，不能混成同一输入。已保存原 oracle 不依赖已退役的 ft 导出器重新生成；新增合同使用 Kotlin 自包含夹具和独立期望核对，保留全部 operations／deferred／candidate_sets，不能按新实现手改期望。

逐流水操作决定复用 `CashImportOperationRules`，native SQL／标量留在读端口；条目仅传不可变id/revision读投影，不传可变CashEntry实体。`CashRecordValues`统一prepare/normalize/equal/copy/consistency边界，native每prepare独立PostingValidationCache不改成进程缓存；local生成Int须marker。故障注入若依赖stack类名，应指向迁移后的真实operation而保留异常/首行终止/只读断言；不能跳过测试。common不得遗留JVM putIfAbsent；保首值改写须维持集合顺序。共享core通过不代表完整local工厂已组合。

`prepareLocalCashImportOperations` 用一个完整快照组合真实输入／来源／资金／字段规则，不接受可与快照脱节的外部 sourcePlan。条目依赖摘要保留持久 JSON number token，不能借 source 编码把 1e0 改为显示文本；修订／身份不经 Double。原始 13×16×2 场景对照保留完整行、操作、候选与失败断言，测试只适配 JVM JSON 标量。issue 目录迁移须逐字核对，旧 native65／Python73 是已有生成漂移，不能把新增提示及动作伪称纯移位；目录同步随明确 UI 范围处理。

`buildLocalCashProjections` 从一个完整快照派生，与当前 native source read＋projection 比完整结果。只确认关系子图要求组件完整；不让无关草稿／单流水被组件校验额外阻断。来源绑定优先级、accepted/active资金、问题及active draft attention保持原口径；confirmed边涉及删除端点时先报projection.incomplete，不一律包装invalid_relation。旧物化行标识测试的缺口与新端侧source-build对照分开记录，不能删旧断言或宣称完整类通过。

缓存版本／快照请求使用显式预期workspace_id，两个GET只校验与当前鉴权选择一致，不按任意参数切换读取。版本回包仍只含ledger_seq；客户端非空scope及snapshot workspace核对不可省。后端原事务内与返回前Principal复核保留，客户端缓存发布还须结合会话生命周期，HTTP通过不代表落盘/清理已完成。

`LedgerSnapshotSync` 每登录范围单实例，等待成功close后再重开或换凭据。网络不占用缓存发布锁；关闭先封闭并清理，成功清理一次，旧实例迟到401/403和重复close不得清新实例。ledger HTTP不直接清共享token，权限拒绝交所属同步/会话处理，旧请求不能擦新登录token；其他API认证默认行为不变。快照版本须不低于查询及原缓存，清理失败保诊断且可重试。端口测试替身只证明编排，不能充当加密落盘/keys清除/登录切区接线证据。

`LedgerSnapshot.kt` 的开放 JsonElement 字段使用文件级专用 serializer：普通 encodeToString 与 encodeToJsonElement 都可能先把数字 token 经 Double／UInt 窄化，不能仅由树 API 推断保真。用完整实际 roundtrip 和真实双库 HTTP 保护 1e0／-0／尾零／大整数；合法数字递归 raw literal，非法裸值拒绝，不全局注册。`SqlLedgerSnapshotStore` 只做内部 SQL 保存，单条 TEXT UPSERT 原子发布完整 DTO，remove 仅删行；它不实现加密端口、不销毁文件／keys，也不代表索引或页面接线。引用后端外部 fixture 的 host Test 须同时声明 inputs.file 内容输入。Web SQLDelight Long 经过 Double，缓存身份／修订／版本／金额走 TEXT，不依赖 execute 的默认 rowCount 作 CAS。

PDF parse-only 复用原受限进程而不依赖会话/R2，完整归一文档字段与原 oracle 比较。文件预算在进程前拒绝，响应预算按实际 HTTP 默认字段和 UTF-8 计数；成功及密码失败都重查原权限。取消不进入普通错误转换；重查遮蔽原程序异常时保留内部诊断。新传输的迟到 401 不得清除新登录 token，须以真实请求交错回归，不能只断言状态码。

来源候选修订沿数据库／快照→SourceRecord→操作 expected_revision 保持 Long。不要把 row_revision 读成 Int，也不要为生成已知整数复用只接收 Int 的 SourceJsonInteger；原有位置／序号标记保持原边界。以大于 Int.MAX_VALUE、JS 安全整数范围及 Long.MAX_VALUE 的真实只读候选／操作和跨端 JSON 编码回归，普通修订仍跑完整原操作 oracle。

工作表事件预算与 XML 语法解析分开：共享守卫保留原深度、物理行、列坐标与错误优先级，宿主 parser 仍负责语法／DTD／实体。新增格式子集回放须复用原完整比较函数并保留原整包断言，不能删掉已知旧失败案例后把整类记绿；原语探针不证明完整 OOXML／XLS decoder 已可用。

月度统计复用共享 `CashProjectionMonthlyRules`，以完整有序筛选结果累计，再取页；未知日期行和无统计资格行仍占位置，连续月份位置不能按月份全局合并。内部转账可形成无收支币种的月份，精确合计不回转为 CashAmount、不使用 80 位投影舍入。月份由明确浏览时区映射，native ROOT 币种大小写适配保留；共享累计器和本地 helper 存在不代表筛选、时区支持和页面已接通。

本地浏览使用 `buildLocalCashLedgerEntries` 将完整快照的派生分组与现有 cash_entries／members 组合。公开 `cash-entry:` 身份必须取明确 entry.id，目标流水用明确 transactionId，不能解析任何投影标识猜编号。公共对方、分类、备注继续取 entry，来源成员原值不能覆盖；映射缺失、组内分裂或公开身份重复时不造条目或发布部分结果。此组合入口不含筛选／分页／完整证据或页面接线。

现金快照的只读资金关系在 accepted 且 active 时附带最小 investment_event（账户、币种、原 from/to 金额），不复制完整投资事件或正文。LEFT JOIN 的状态条件放 ON 中，保留其他原关系及 null，并同时按两表工作区约束。原事件写入仅新增；未来若编辑已同步字段须跟随事实版本。删除现金前先清同工作区全部物化 dataset 的资金关系外键，再删目标关系，同一事务回滚；不得只清 active dataset，也不能删除投资事件。

本地列表读取使用 `buildLocalCashProjectionViews` 组合现有 contracts DTO，shared 仅 typealias，不另造同义模型。账户引用、分类路径、问题／待配对原因、关系摘要及转账双端保持当前列表合同；源成员值与公共 entry 字段分开。native 全字段对照只显式把物化读取版本适配为 ledger_seq，其余字段完整相等。内部转账当前 statistics_eligible 可为 true，月度收入／支出再按经济类型排除，不能把这两个口径混成一个字段。读取模型可用不等于筛选／分页或页面已接通。

本地分页使用 `CashLedgerCursorScope` 绑定工作区、精确 ledger_seq、客户端实际共享／投影规则版本及完整规范筛选映射。筛选由构造时复制的私有 JSON 保持，版本用字符串编码；`invalid_cursor` 与 `read_snapshot_changed` 分开。`CashLedgerCursorPosition.isAfter` 按真实 sortAt 和 Python 码点稳定身份续页，未知发生日期不能被创建时间填充。游标不是授权边界，完整筛选／分页 owner 与页面接线仍须验证。

浏览月份使用显式时区的秒偏移与共享公历月份计算，不取设备默认时区或 Foundation 格式化日历。JS／Wasm 用完整 epoch 秒文本构造精确毫秒，setUTCFullYear 避开 0..99 年的 Date 构造规则并处理 BC era；微秒不进 offset 查询，保留历史秒级偏移。EST／MST／HST 仍为固定偏移，未知时区不回退 UTC。平台时区库版本差异及 iOS 运行时未测边界须如实记录，内部浏览器单测不等于产品页面冒烟；日期范围／查询接线另验。

账本类型／业务标签的筛选选项按用户确认保持全局，不随当前筛选或分页收缩；`buildLocalCashFilterOptions` 取完整分类目录与 base 可见集合（visible 或 attention>0），不应用 hide_zero。净额0的全额退款仍为基础可见项，与无待处理事项的隐藏余额校准区分。按当前原生路径与完整 options DTO 比较，发现旧主规格冲突须先由用户决定并回写 delta，不能以旧测试绿替代规格符合性。

CSV 的 byte 入口先来源资格、再实际 100 MiB 输入预算、最后严格编码，native 与 local 复用同一边界；PK 工作簿保留原 ZIP／POI 预检。`parseLocalCashCsvStatement`／`parseLocalIcbcAsiaStatement` 连接真实字节和完整归一，不代表完整文件分派。原 raw 文档成功可含 `row_shape_invalid`，归一若按既有合同拒绝整份文件，须显式断言原错误，不能跳过原案例或伪造成功；新增子集测试仍保留完整原 raw／归一回归。

工作簿数字单元格使用 `cashPlatformWorkbookNumberText`／`cashCcbWorkbookNumberText`，共享 binary64 精确选数器但保留 Java 21 与 Python 的独立格式合同，最小值分别为 4.9E-324 与 5e-324。平台仅小于 1e16 的整数去小数，CCB 所有有限整数仍沿原 Long 饱和，不在迁移中统一政策。真实 XSSF／HSSF 数值单元格与原 Java 策略整文档对照，保留 Python 旧算法／digest 和原 raw oracle；原语耗时不当作完整工作簿基准。

工作簿日期文本使用 `cashPlatformWorkbookDateText`／`cashCcbWorkbookDateText`，显式传入文件的1900／1904窗口，不使用设备时区。保留POI的Java十进制值、毫秒舍入、低32／64位转换与整日Int加法先溢出后扩Long的原次序；raw日期范围不代替归一层业务时间资格。平台省秒／三位毫秒与CCB固定秒分开，真实XSSF/HSSF日期style及两窗口完整文档对照必须保留；通用时间解析／格式化的完整 oracle 不缩减；现金发生时间资格按当前 change 的独立业务范围校验，不用 raw 工作簿日期决定事实范围。

ZIP 预检按实际累计解压量限读，不能先无界 readBytes 再判断；剩余额度＋1 的读取在超出时立即停止，失败关闭不得继续 drain。预算内完整 worksheet 交给原 StAX／guard，不把 64 KiB 未闭合片段当成整个 XML；目录未知大小也核对实际空内容。保留大表完整文档、后段坐标错误、空／非空目录及真实高压缩预算回归。解压字节上限不等于堆内存上限，worksheet 缓冲／复制及 POI 开销分别评估。

工作簿日期样式用 `cashWorkbookIsDateFormat`，保持内置编号优先、转义／;@ 删除、elapsed、单次 DBNum→locale→ASCII color 前缀及首分段的原次序；三个字符状态表达日期前缀／零／AMPM 后缀，不能按“包含 yyyy”等启发式扩大识别。native 先执行与原 binary64 非负门等价的检查，再读取真实样式，空样式按原拒绝；Short 不改成无符号。完整内置目录、字符组合／Unicode 前缀及真实 cell 样式对照、长段跨端回归均保留；该原语不等于二进制工作簿已接线。

工作簿 ZIP 容器用 kmp-zip 2.1.0，`preflightCashWorkbookArchive` 在共同预算内完整读取所有成员并验证中央目录，不以 closeEntry 跳过未用 XML 的 CRC；预算失败关闭整个流。回调仅校验工作表，整个容器成功前不得发布结果。native StAX／POI 语法与 cell 读取保持原边界；共享 ZIP 不代表完整本地 XLSX。MPL 及 kmp-zlib MIT／Zlib 声明以领域库资源为单一源，Compose 同步现有 common 资源时一并加入 files/third-party，构建验证生成资源及运行时传递版本。

旧 CCB XLS（OLE／BIFF）按用户确认走无状态服务端 parse-only，端侧继续字段归一／类型分类／查重／配对，不再安排端侧 BIFF 解码；保留 PDF／XLS 两个只读解析面时再删除旧完整导入写路径。原始行复用 CashStatementDocument，不混作 PDF 的归一化文档。XML 编码先保护真实工作簿的完整输出／错误基线：UTF 家族 BOM／声明、显式 GB18030、Latin-1／Windows-1252 与数字实体、冲突／未知声明及 UTF-32；CSV 的回退解码和银行 UTF-16 替换语义不能替代 XML 解码。

旧 XLS 通过 `POST /api/v1/cash-import/xls/parse` 与共享 `parseCashXls` 传输完整原始文档，随后使用 `normalizeLocalCashStatement`；不要把 PDF 的已归一 DTO 用于 XLS。两个入口复用有界 multipart 和解析后原身份／权限核对；XLS 无密码能力，实际默认读取必须在受限进程。未知 parse-only 程序错用专用安全 500，原 cause／suppressed 保留，远端只带类型和有界帧，不把异常消息写回客户端／日志。XLS 的未知退出／坏协议及不匹配密码码不能当文件错误，明确文件错误和 PDF 正常密码仍保留原码。真实 worker fault／退出回归与 service 假异常分别断言；旧银行读取器内部已有分类不以外边界测试宣称全面识别。共享 API 尚未等同完整文件调度或页面接线。


本地 XLSX 仅支持值型工作表，按当前 change 排除公式；发现所选表的公式元素整份拒绝，不使用缓存值、公式正文或服务端回退，普通等号文本保留。属性匹配同时核对 namespace，sheet 关联 id 用正式关系 URI，样式只读正式根下的 numFmts／cellXfs 路径，扩展载荷不能污染数字／日期解释。值读取区分 v 缺失和显式空；错误单元格核对原精确错误集合，伪造数字错误值拒绝，共享字符串索引仅在已知格式／范围失败时沿原空文本行为，不吞程序异常。保留原完整文档／归一与真实 POI 对照；规模探针只证明实际读取成本，不能替代页面响应、取消或完整导入验收。

单文件 `parseLocalCashStatement` 保留字节／来源提示的既有分派顺序，扩展名只用于原接受集合校验。元数据与实际读取预算由 PDF／XLS 传输和本地入口复用，原文件只做一次有界读取；远端包装复用已读字节和原引用／提示／修改时间，使用固定格式文件名，不透传原文件名。PDF 还原既有归一 DTO，XLS 继续端侧归一；内部异常与取消不转成文件成功或跳过。该入口不创建批次，不能据此勾选完整 owner、稳定输入保存、页面或离线导入验收。

稳定重派的文件输入使用 `captureLocalCashImportFile`，冻结原字节及同次完整归一文档，保留调用方文件引用、原文件名及修改时间。内部版本化编码仅供同范围加密存储；密码只用于当前解析请求，恢复文档不再次联网。恢复边界核容器自身的字节、大小和摘要一致性，不声称证明原文件真实性；每次恢复返回独立字节与文档树，已有 JSON 数字 token 原样保存，不能通过 Double 重编码。文件批次通过 `LedgerImportBatches` 接同驱动、范围、互斥与关闭围栏；保存入口在首次挂起前复制外层文件列表，固定批次只接受同内容重试，快照刷新不改输入。原会话 owner 只开已有材料并核原凭据、发布代次及缓存身份，不可取消打开交付后、动作前后均核父任务取消，保留关闭资源的所有权。

原提交恢复沿同一批次行保存版本化包络，兼容旧文件数组；批次引用也是原幂等键，调用方须保证新操作唯一且清理后不复用。首次 HTTP 前保存完整冻结请求，结果未知先按原键核实，仅 `404 receipt_not_found` 且尚无已保存基线拒绝时以原正文和原键尝试一次提交；已保存拒绝仍先查原键收据，无收据返回原拒绝，成功收据优先。基线拒绝只由真实 POST 的 409／ledger_base_changed／not_committed 三条件记录，失败保留原请求及两类诊断；不以拒绝状态单独授权换键。其他故障停止，不换键。完整成功收据先保存再刷新，清理只读同互斥内真实完整快照水位，不能信任调用方传入版本。条件更新／删除后读回实际状态，不假设 Web DML 返回原生行数。准备后普通文件移除必须拒绝，提交后的本地故障保留成功收据及原诊断，原凭据与取消围栏继续生效。内部解析后完整计划已提供，完整生产计算已接内部 owner 预览及显式恢复入口；页面已接本地生命周期，离线状态呈现与重新联网完整流程和完整逐项结果页面仍须完成。

关系选择恢复使用共享 `CashImportChoiceContextRules`，backend 保留原整数种类、真假、字段归一及会话适配；端侧使用 `LocalCashImportChoiceContext` 的 JSON 边界或计算型计划提取入口。只保留 occurrence_key／record_id、建议及候选引用、失效选择与来源 generation，不序列化完整计算值或来源正文。JSON 只读视图按规则实际访问解释引用，先核形状与规模，不递归解码忽略的正文；上下文外字段直接深复制 JsonElement，保留原数词，不能把外层 JSON 整体交 decode／encode 归一。generation 仍为整数且 `0 <= value < Long.MAX_VALUE`，拒绝浮点、布尔和字符串 token，不能把 JS Number 当精确整数。最新计划替换单份上下文，明确空值及失效选择保留；恢复后原建议再现也不自动接受旧选择。该上下文不包含条目编辑的完整目标依赖，不以共享完成宣称决策持久化、结论指纹或完整重派已接通。

现金发生时间按当前 change 的 UTC 1900..2100 业务门校验，不收窄投资、系统时间或通用 Python 时间原语。旧 manualCashTimestamp 被投资共用，现金专用包装只接现金录入／旧现金及转账现金端，纯投资端保持原范围。来源三类归一均在转 UTC 后判断，超限沿原 time_unreadable，不改原文／覆盖证明，不裁剪日期；实际投资与含现金转账、原子回滚必须分别验证。客户端现金输入同样在本地时间转 UTC 后调用专用适配，保留空草稿和未改字段；纯投资两端适配不等于当前现金页面新增投资账户选择。日期提示须解释已确认范围，错误定位应覆盖重复同码保存。Compose Web 的语义节点几何可能滞留，不能仅凭 viewport 断言认定实际画面可见；受控截图及真实修正后的单次写入需独立核对，固定坐标证据不推广到其他布局。

浏览筛选日期用 `normalizeCashLedgerFilterDate` 保留原四位年扩展／紧凑日期、ISO 周／缺省周一和年 1..9999 范围，拒绝时刻、offset、尾随空白与无效日期；后端继续原 ZoneId 起日转换，不把日期归一当 UTC 区间。本地范围使用 `localCashLedgerDateBounds`：结束日期先取翌日，再按选定时区取首个有效时刻，保留午夜 gap／overlap／整日跳过及终日上界错误；不固定加 24 小时。共同名字资格只冻结原 JDK 可用名称，保留别名／大小写边界，不能用 Intl canonicalName 相等代替。平台只转换合法日历与时区，程序异常不转成筛选错误。Android minSdk 24 的实际 app 使用 core library desugaring；iOS-only datetime 与 Web Temporal 不混入 js-joda timezone 规则；完整查询／页面与平台设备 QA 仍另验。JVM oracle 保持迁移前 formatter 独立表达，并覆盖完整公历周期／周数和范围边界。关键词旧 SQL LIKE 的双库差异已由用户统一，端侧查询应消费 `cashLedgerKeywordMatches` 和当前浏览 delta，不再复制数据库 LIKE；该谓词只处理账本公共对方／备注，不能替换导入商户归一。

本地 `LocalCashLedgerQuery` 的规范输入核心与原始筛选适配分开验证：同一 renderer 实例只构图一次，视图／选项／筛选／分页复用该捕获；完整范围数量及月份合计先于限页。账户条件覆盖所有现金成员组成项，银证投资端仅用于展示；分类按祖先 ID，不解释 SQL 通配符。转账净额为0，关闭零金额隐藏后才验证该类列表、成员账户及统计，避免因默认隐藏形成排除假阳性。保持 unknown 金额／日期和码点标识续页。原始筛选复用 contracts CashFiltersDto 与共享规范工厂；金额筛选按已确认的整数 20 位／小数 18 位，在展开指数前拒绝超限，不截断或舍入，不影响完整统计精度；公开 Map 副本不得改变内部金额／游标范围，热路径复用固定快照。shared 读取适配统一实际选定时区的日期边界和月份。完整 native page 对照仅替换已确认的版本／游标协议，分别用真实游标续页并比较其余完整字段；分层与查询测试不等于缓存或页面已接通。

收据的 ledger_seq 复用现有事实版本的规范非负 Long 十进制文本。写摘要与公开按键／ID 读取核对类型及范围，旧收据不按当前版本补写；shared 保留字符串并拒绝非规范版本，不经 Double。新增可选字段不能为旧序列化输出自动填 null，远端显式 null 必须在字段级解码拒绝，缺字段才是旧合同；最终写集 service 仍须在事务中填写真实提交版本，字段传递测试不能替代该绑定或原键核实的端到端验证。

固定字段核对使用真实旧状态，既有正式流水不允许退回草稿；固定金融冲突先于纯状态降级错误。草稿保存／转正式保留原身份，但该守卫不替代转正式所需问题处理、账户、完整性或权限检查。

最终增量使用独立 CashWriteSetRequestDto，不把只读快照的工作区、操作者、审计时刻或实际修订变成可写字段。已有／新建引用显式区分并保留完整文本，版本化引用只用一个模型；组成项、候选及条目成员由父对象包含。原始来源／证据 JSON 保持精确数字词法。当前来源事项准备复用 CashSourceIssueResolutionRules：每份来源先校验固定／组成项约束，再按显式补丁处理多来源冲突；缺正文、明确原文清空与 keep_existing／独立动作保持原边界。返回 patch 仍含受保护候选值，必须经后续 policy 应用，不能直接写入。native 保持原摘要／复制／错误桥，local 精确 JSON 桥需对原完整 708 案例和固定正文 hash 验证；仅准备器通过不证明完整动作效果、身份合并或事务。

稳定收支对象协调复用 CashEntryReconciliationRules；原仓储保留 SQL、成员即时登记、条目延迟插入与 flush 顺序。输入成员仅为原查询选中集合，不扩大到条目全部成员；核心原地修改对象，最终写集期望结果校验须传脱离真实缓存的副本。结构与公共值各自推进修订并保留溢出错误，拆分三态和独立人工值恢复沿原合同；泛型流水标识允许端侧新建虚拟整数，不得缩窄为 Long。显式公共编辑复用 CashEntryEditRules，修订先验、全量字段归一、人工标记合并、单成员独立值更新和失败时对象状态沿原 patch；原生查询与 flush 时点保持，本地校验仍须用独立副本。拆分选择由 entry_split_choices 单独保存旧条目真实修订及明确布尔，旧对象退役也保留；最终空 split_inherit 或 edited_fields 不能代替该决定。引用预检拒绝新建、重复和过期依赖，不证明实际差异、完整拆分范围、必要组统一选择或最终效果合法。协调及编辑核心不替代人工编辑意图、来源动作资格、权限及最终写集对应关系校验。

本次 edited_fields 与最终 manual flags 分开，后者只作结果断言；来源四种 typed issue_resolutions 只允许 merge 带已有目标依赖，真实正文不由客户端提供。引用预检拒绝新建动作依赖、过期修订及重复决定，但白名单、动作资格、标记推导和最终效果仍由真实服务验证。DTO 能表达的非法状态仍须由实际数据库上下文验证，序列化回放不证明身份／修订／引用闭包、删除资格、保护字段、完整成员、原子落库或幂等；1.2 完成不能据此勾选 3.x。

行核对前置公共编辑由 transaction.entry_edit 保存独立 patch，entry 为必填可空旧对象依赖：非空须匹配真实父→成员→条目及旧修订，空值须真实没有条目。来源事实 S、前置公共值 C 与最终归并公共值 D 不能相互替代，也不从最终 standalone 反授权 C。归一只允许五公共字段的非空子集，并保留未提供、null／空文本；显式分类引用核当前目录，历史独立值豁免不适用于新编辑。完整服务须重放同旧对象补丁合并／冲突检查、applyEdit、关系选择及协调、最终公共 patch 的原顺序；仅 wire／引用门通过不证明完整效果或事务。

文件导入写集的动作范围由内部 validateCashImportWriteSetScope 校验，先保留原引用／金融归一／固定错误顺序，再拒绝无文件的删除、来源事项与拆分选择以及旧关系重写。仅被写的 existing 流水限制为 live import／legacy，不把来源读取资格扩为全部只读上下文；来源绑定禁止从另一 live owner 转移，但保留已软删旧 owner 的合法复用。幂等同事实 no-op 须在此门前解析去除；调用方必须提供可信完整父事实，缺父不默认软删。有序 relation_groups 对全部新关系作非空、完整且不重叠分区，只允许 New 引用；三种文件动作的新边均须 active，组内状态一致并由此推导动作，仅 accepted 组可带公共补丁。父集合只从真实边端点派生，不扩旧确认闭包；relations 不另承担隐式执行顺序。内部 prepareCashImportWriteSetRelationBatches 用原纯分组规则结合旧 active confirmed 闭包与全部 incoming（含 defer／ignore），每个选择组提供私有联合标签，再按明确组首次批次出现及组内顺序返回；typed 私有字符串键不得成为存储 ID 或排序权威。独立 prepareCashImportWriteSetCommonChoices 再核所有 accepted 组的生效 B 相同，覆盖该批全部端点触达的正式多成员投影的可信 post-C 公共人工字段；旧 owner 共享 C，新／无 owner 父引用独立。投影索引用整数去重，不能对完整成员 Set 逐父 hash；不从最终 D／standalone 反授权，不在实际 ID 下重选 B 值。此准备不证明三阶段效果或服务已接通。该门未接服务，不证明完整权限、来源、候选、公共字段效果或事务；通用 DTO／共享维护辅助器不构成新端点范围授权。

validateCashWriteSetReferences 只预检调用方提供的事实，服务仍须在鉴权及基线锁后读取真实工作区事实；同事实已应用创建的身份解析先于预检，不能用占用键拒绝代替相同关系的幂等成功。按实体类型解析，现有目标／依赖修订不能补造；最终自然键先保留未修改存量占用，再释放显式替换／解决／删除目标；条目成员独立核批内唯一和真实旧归属读集，不把旧条目依赖视为写入或退休指令。组件 ordinal 沿原 Int 上限，端点只覆盖本批显式关系／候选，未改存量闭包与金额／状态／保护字段由外层完整服务按真实事实验证。纯函数及合成范围用例不证明真实数据库鉴权、原子性或双库等价。

normalizeCashWriteSetFinancialValues 返回归一后的同一写集类型，后续 writer 必须使用返回金额／时刻，不能校验后存原始文本。复用 CashAmount、存储定点 formatter、UTC 微秒及记录完整性／组成项规则，草稿允许缺值但拒绝已知矛盾；正式缺类型仍报 incomplete_record，不先转成 invalid_record_type。关系金额此处只验精度，角色／符号／额度另由图校验判断。非金融来源／证据 JSON 及引用保持原值，纯归一不证明权限、状态迁移、保护字段、真实引用或最终图合法；不得把它当直接落库凭据。

validateCashWriteSetConfirmedGraph 叠加最终流水／组成项／关系／绑定后，保留所有未改生效确认边，共同执行原图规则；改单笔金额、转草稿或移除组件不能绕开存量关系及有效资金占用。新建引用在每实体私有映射为超过 Long 的精确计算整数，不能截断、持久化或改写请求引用。默认关联金额只用于未修改的旧存储零值，本批显式零必须原样验证；新建／替换非零两腿零额应拒绝，真正零额退款仍按原规则允许。此入口不处理草稿／失效边及候选状态、锚点、联合决定、来源资格或保护字段，也不证明实际鉴权／原子提交；来源排序键存在不等于来源绑定合法。

validateCashWriteSetFixedRecords 从可信原 state/origin 比较身份、金融值与类型，不用客户端最终 draft/manual 放宽原正式来源的固定字段。按 ordinal 构造两侧组成项顺序，新引用用私有不可冒充存量 ID 的标记比较，计算标记不进入 DML。导入创建仍固定 import 且无 manual_transfer_id；本函数不处理人工意图、保护标记、状态迁移、账户或成对转账资格，不能当完整写命令验证。


本地浏览 owner 与 LedgerSnapshotSync 共用同一关闭围栏，不能另存一份只在显式 close 更新的状态；401／403 后，即使清理等待或失败，已捕获缓存的分页也不得返回。同步网络不占派生锁，失败及正常取消保留旧完整缓存可读。用真实派生消费后暂停、并行撤权并进入清理、再放行的线程交错验证返回边界；普通 close／空库测试不覆盖该竞态。内部 owner 组合不代表平台加密驱动、登录生命周期或页面已接通。

Apple 驱动须以固定 SQLDelight／SQLiter 与官方 SQLCipher 版本实测，不以系统 SQLite 或 JVM 加密模拟替代。SQLiter 创建连接失败可能发生在交付连接池之前，生命周期回调捕获的真实连接仍归平台适配器清理。其 closed 标志早于 C close，任何关闭异常都须持久保留为不确定；重复 close 无操作不能擦钥或允许删库。测试可在真实 manager 的关闭确认边界注入错误，明确这不等同于复现真实 C close 故障。SwiftPM 的生成包、锁文件、Xcode 最终动态库嵌入与许可一并核对；底层驱动不代表 Keychain、范围租约或应用离线能力已接通。

Apple Keychain 的真实系统测试须使用测试二进制授权声明、已启动的指定模拟器及非 standalone 模式。当前 Kotlin 2.4.20 SwiftPM 只为默认测试任务配置动态库路径，使用 -PledgerKeychainTests=true 复用默认 iosSimulatorArm64Test；不要为此复制内部下载路径或修改应用授权。普通模式只排除实际系统服务测试类，纯 ID 校验保留。两模式 XML 分开保存；读缺项、损坏拒绝和重复创建不换钥须经过真实 SecItem API，不能用假仓储代替。此仓储只接收范围摘要，不证明完整范围编码、文件存在检查、租约或登出组合完成。

核对公共字段选择复用 CashImportEntryChoiceRules，native 保留 BigInteger 边排序、计算型金额／时刻的 import digest 与原值桥。local apply 只消费已渲染 JSON，复制完整计划后应用，不能原地修改旧确认内容或让失败露出部分补丁。缺失／改变的目标、越权字段及当前分类失效保持明确拒绝；本片 JSON 黄金回放不证明完整端侧目标生成或核对页已接通。

关系选择的 applyChoices 核心使用共享 CashImportRelationChoiceRules，保持失效保留、来源 generation 推进、旧指纹不复活及本轮显式选择优先；native 仅桥接 BigInteger 和原摘要。local JSON 数字保真，generation 不作为金额或 ledger_seq 处理。2^80／大数 carry 只证明纯核心，既有 CashImportChoiceContext.validated 的非负且小于 Long.MAX_VALUE 上游门未变；不能据核心回放扩大完整上下文／预览接受范围。原18案例、native BMP数字门差分和真实重预览交错分别验证，不替代完整图应用或页面接线。计算型本地入口直接应用可变组，choices／previous 保持只读；CashDecimalLiteral 仅作为不可变计算叶允许复制，原始 JSON 校验不接受它。拆分恢复从同一完整快照读取 context 成员，实际选中且 member／fact 存在后才解码 standalone；旧确认闭包沿完整 source 的 active confirmed 两端关系展开。真实退款候选选择、全部原组选择和完整组应用／错误状态对照不能被普通 JSON 回放替代。

候选召回查询规划复用 CashImportCandidateQueryRules；类型目录、±61 天合并、纯负消费门和平台硬键请求在同一处定义。区分来源行缺省与存在但 null 的范围，交易号显式 null 不回退 provider，Alipay 扩展前缀用于精确键召回而原交易号另用于 startsWith。原双库候选 fixture 及只读预览准备结果必须保持；本地生成查询不代表本地执行、SQL JSON 取值口径或完整预览已通过。

计算值预览摘要使用 cashImportComputedDigest／localCashImportDigest，CashAmount 与 CashDecimalLiteral 保留原 Decimal 字符串（小数位、负零和指数），时刻按原 UTC 微秒文本用空格分隔。不要用来源摘要或已渲染 JSON 猜计算类型；native 的原 BigDecimal／开放值 sourceDigest 桥保持。cashImportVirtualId 继续取来源摘要的 unsigned 128 位前缀加 2^64，以完整十进制文本隔离 BigInteger，不能经 Long／Double。完整原预览12方法及组／候选／选择身份必须保持，纯摘要测试不能替代端侧预览接线。

CashRelationReadContext 仅共享既有只读关系上下文及精确版本记录，I/T 只隔离投影平台值；native typealias 不改 SQL 资格、闭包或事务。cashRelationReadContextToken 保留原9组依赖列表顺序、ASCII UTF16转义及完整Long，不从种子/展示字段推导版本。原准备及完整预览的token、读取结果和全表只读必须一致；模型与 token 不替代本地读取和完整预览的独立验证。

readLocalCashRelationContext 只读取已隔离的完整快照：确认且生效的primary/secondary边扩展闭包，草稿仅召回不扩展；显式组含inactive历史成员。草稿／删除父仍参与版本，正式事实和资金占用只取liveposted父的组成项。完整native上下文、19个事实字段、token及全库只读不变须对照；正向历史组可在既有read回滚事务内构造。第三端anchor单独命中违反ck_cash_relation_anchor，不可禁约束来凑覆盖；anchor筛选仍沿原SQL保留。common Map 保首值使用contains/assign；非法UTF16在测试运行时构造，不能由编译器替换掩盖。

条目最终结果只传根、完整成员和公共／人工／独立值，不预测存储 UUID、数字身份锚或退休对象；旧 target／identity_transaction／split_inherit 及 deleted_entries 严格拒绝。entry_dependencies 保存所有受影响旧条目真实修订，包含最终退休对象；前置 entry_edit 还须匹配真实旧归属及读集。拓扑展开旧完整成员与最终组，核完整业务分组覆盖和旧依赖，不以读依赖本身触发写范围。文件专用 validateCashImportWriteSetEntryTopology 先动作范围及 generic 拓扑，再核全部受影响旧成员／身份锚存活且同一最终组；只读旧依赖不扩写范围，旧闭包不缩到选中成员。该门和三阶段编辑效果、实际身份／修订／退休及收据映射仍须真实服务接线验证；不能把本次 wire／纯门通过当完整提交。

## Python 后端退役后的验证入口

生产数据库 URL 的支持范围与连接安全语义见[后端部署参考](../../../compose/backend/README.md#render-部署)。`DatabaseConnectionConfigTest` 覆盖启动配置到 JDBC 的参数映射；`DatabaseConnectionRuntimeTest` 使用显式 `_test` PostgreSQL 和临时 SQLite 验证同一配置入口，不自动准备数据库。真实 TLS 证据使用操作者准备的服务及独立证书目录，不能把纯字符串断言当作连接成功。

现金账单自动识别先完成全部既有候选的有界探测，只有唯一匹配后完整解析一次；探测窗口中的多套表头必须累积为歧义，不能按首个匹配选择。工作簿预检计量实际解压内容并完整校验 XML，PDF 文字与边线使用相同显示坐标；合成回归保留原文、金额、时间及错误边界，真实文件留在仓库外。暂存容量变更须同时核对编码后的实际大小、计划发布拒绝原子性、内存总量和三端错误映射；序列化预算不等同 JVM 峰值内存，不能用浏览器夹具代替原始文件的预览、提交和完整收据核对。

默认 `:backend:test` 与 CI 运行全部自包含测试。依赖仓库外历史夹具的迁移证据由 `HistoricalMigrationEvidence` category 显式标记，使用 `:backend:migrationEvidenceTest`；混合类的独立方法继续默认执行，不按类名或缺少环境变量自动隐藏。历史任务的 dry-run 只证明发现集合，不算执行通过。schema 的 PostgreSQL 回归仍使用显式 `_test` 数据库，不能归入历史任务。相关决定及未执行边界见 `retire-python-backend` change。

### Compose Web 工具入口

现金导入的身份脱敏月度切片、独立期望、实际格式生成器及双后端／真实浏览器入口见 [导入回归夹具](../../../docs/import/cash-import-fixtures.md)。该文档明确本批保留真实日期和金额的用户授权边界，不是原始个人账单的通用入库许可。

`compose/qa/web` 的 `npm run test:node` 收集全部 `*.node-test.mjs`，包含通过真实 Chromium 执行的 Web 控件语义生命周期回归；先执行 `npm ci` 和 `npx playwright install chromium`，或设置 `PLAYWRIGHT_CHROME_PATH`。Python 构建工具测试的直接入口为 `python3 compose/tests/test_build_wasm_web.py`，discovery 入口为 `python3 -m unittest discover -s compose/tests -p 'test_build_wasm_web.py'`；两入口均收集所有 11 项测试。完整 E2E 的本地注册案例仍需要专用 Kotlin 后端，`FT_API_PROXY_ORIGIN` 指向该本机服务。 PR 的 `Compose Web E2E` 使用临时 SQLite 与本机后端运行完整套件；可先用 `node compose/qa/web/scripts/start-compose-demo.mjs --build-only` 完成冷构建及产物指纹校验，再运行现有 E2E 入口。

Apple 文件租约的锁 inode 必须位于可删除事实目录之外，持有到数据库真实关闭及文件／密钥清理完成；不要在释放前 unlink 锁文件。完整范围摘要须消除拼接歧义并拒绝孤立 UTF-16 代理项。close 报错后不可重复关闭可能被系统复用的 FD；实际故障回归可以先破坏自有合成描述符得到 EBADF，再复用编号验证重复释放不误关其他文件。此原语不等于缓存工厂、跨进程崩溃恢复或应用登出组合已经完成。

Apple 缓存工厂的实际 Keychain 回归与仓储共用显式系统模式，普通模式排除两类系统测试。初始化失败且连接关闭不确定时，须保留原始失败及关闭诊断，并让范围租约保持到进程退出；普通错钥且已确认关闭则允许显式重试。访问范围目录前同时检查 facts 父目录，不只核最终目录；符号链接回归使用独立合成外目录。文件删除失败可能已部分清理，回归核旧实例封闭、密钥及租约保留和明确重试，不虚构主库字节始终保留。工厂组合不代表原生索引、应用会话或页面已接通。

Android 密钥仓储的真实回归在 androidDeviceTest 执行，只使用独立合成 ID；host 仅验证密钥交付所有权，不能代替 AndroidKeyStore。读取或创建必须等文件锁与流全部释放后才交付原始密钥，后置失败擦除未交付副本并保留原异常。记录缺项、包装别名缺失和密文损坏分别处理，读失败不得重建；文件删除拒绝时保留别名供明确重试。仓储测试不证明 SQLCipher、完整范围工厂、硬件保护、跨进程并发或应用登出已接通。

Android 文件租约在打开锁文件前按规范根目录及范围摘要登记同进程占用，避免仅靠 Java 重叠锁异常后关闭第二通道；只有确认关闭才移除占用，不确定关闭保持到进程退出。独立 app_process 探针在 API 26 以上验证内核锁，不把原实现探针通过描述为复现过 fcntl 提前释放；关闭确认错误在实际 close 后注入须明确其边界。共享原生范围摘要保持既有 Apple 字节，不能更改持久身份；租约不创建事实、删除密钥或代替缓存工厂。

Android SQLCipher 4.20.0 的 postKey unchecked 异常须进入 SDK 已确认的 SQLiteException 清理路径，再还原原异常；不使用触发破坏性恢复的 corruption 类型。静默日志目标先于原生库加载，资源关闭错误不能仅凭 isOpen 判成功；普通 SQL 约束拒绝也不能当关闭故障。保留原始私有密钥到确认关闭，不确定时强引用 owner，完整工厂仍须持有租约。大 TEXT 容量以实际驱动读回为证，不能把 CursorWindow 初始值当上限；真实关闭后注入报告错误及静态 16 KiB 对齐都须明确验证边界。底层驱动不代表完整缓存与应用已接通。

Android 缓存工厂在 noBackupFilesDir 下组合租约、Keystore、实际加密驱动与 SQL 缓存。先核 facts 父目录、范围目录及主库 lstat，已有库缺钥、错钥或孤立边文件不能重建。初始化失败与已交付实例的 clear 关闭失败均须显式保留租约；不依赖 GC 或仅包含字符串的进程登记表。实际 SDK 关闭后注入确认异常不能声称内核 close 故障，未修复 GC 探针通过不得称作红灯。清理不跟随符号链接，按事实、密钥、租约顺序；部分删除失败保留围栏供明确重试。工厂系统测试不代表应用登录、索引或页面已接通。

Android 缓存与同步浏览组合须同时验证实际文件／密钥在清理前存在、401／403 后均消失，普通失败保留原事实且本地分页不发请求。MockEngine 与真实 SQLCipher 的组合不等于真实后端、应用登录或断网重启身份恢复；范围 HTTP 不能因迟到响应清除其他登录的凭据。

离线访问恢复记录复用 SessionDto，与事实共用加密驱动、范围、互斥及关闭围栏；只保存令牌域隔离摘要，不复制原令牌，邮箱摘要不承诺匿名化。要求完整已提交快照，并核原 token、身份及唯一活动工作区；坏记录不补默认值，SQL abort 保留旧记录。恢复使用 existingOnly，缺库或零字节主库在读钥及开 SDK 前拒绝，不生成替代库／钥；默认在线创建路径独立保留，不把已有空库当成已提交账本。LedgerFactCache.close 是必需的资源合同，LedgerStorage 继承；同步及本地浏览 owner 的 close 同样保留事实及密钥，在确认关库后释放租约；clear 才擦除，首次终结动作固定，旧已释放实例不得转为删除新实例。同动作失败重试不重关已确认关闭的驱动。正式工厂关连接后重开不等于应用断网重启；启动定位、真实令牌读取、仅网络故障回退、联网核验及注销接线仍须独立验证。

同客户端凭据读取、登录写入与 compare／clear 使用同锁，网络等待不持锁。凭据快照同时固定令牌和地址，会话重试、version、snapshot 传同一已捕获对象；单参数 Ktor source 仅首次捕获，实例不跨登录复用。显式匿名快照不得读取后续登录；迟到 401 和退出只清理原令牌。退出清理使用不可取消边界且只执行一次，地址无效也要进入本地条件清理，保留取消和清理双诊断；Ktor 跨协程可能复制取消异常，不以 MockEngine 原异常对象地址作为传播断言。实际 Graph 地址固定，捕获后绑定不等于对独立可变 getter／TokenStore 原子采集；采集期、同令牌切区和迟到发布仍由恢复 owner 验证。该切片不代表缓存注销、页面、跨客户端或标签页已协调。

范围定位目录只存服务地址／使用者摘要／工作区引用，不保存邮箱、角色或令牌；单 origin 的引用集合、current 和发布代次共用单键 CAS。已存在记录的代次须大于零，缺失不可重置；Apple 非文本偏好项不得当缺失覆盖。新操作推进代次但保留上次已提交 current，登记不等于访问资格。工厂须在范围租约内、创建材料前登记，擦除后释放租约前条件移除引用；三端工厂可接 LedgerSessionRegistration，先拒绝错范围，在真实 lease 内执行上述顺序；定位擦除失败时原缓存保持 ERASE 围栏及租约，原实例重试成功才释放，普通 close 保留引用。可选工厂钩子不等于应用已强制登记。Web 分步协议的 acquire 不造材料，close_database／erase 保留同一租约，最终 close 才释放；已持锁的 open 失败须保持可擦除围栏，legacy erase 取得租约失败也不能重新 open。旧包装器继续完成最终关闭；SQLDelight driver.close 会 terminate Worker，不能用作持锁关库。Web 工厂的登记／移除须不可取消地等待真实 CAS Promise 完成，文件／钥／引用均移除后先在缓存互斥内标记擦除完成，再等最终 close ACK 和 SDK terminate；最终确认失败或取消后的重试不能再擦除新所有者。独立坏材料清理和应用接线未完成时不能声明崩溃遗留清理或离线启动已完成。原生仅承诺当前单应用进程多对象与顺序重启，Web 短锁覆盖多标签；发布代次不替代当前令牌校验或读取围栏。

固定范围的 LedgerCacheErasure 只承担坏材料清理，不伪装成 LedgerStorage。Android 工厂先复用目录的纯 scope 校验，再允许首次 clear 在不可取消区间取得租约；删除事实、指定钥及引用成功后先记完成再释放。失败须保留同一清理对象及 lease，成功旧对象不能再取得 lease 或删除新材料。非法 origin／principal 必须在任何删除前拒绝；真实原文件和原钥不变回归不能由仅断错误码替代。Web 独立清理同样使用固定连接的租约，实际 erase／forget 完成标记先于最终 ACK；取消等待不能提前释放或使旧重试再次擦除。Apple 普通缓存的擦除完成标记也须早于最终 release；真实自有 FD 破坏得到 EBADF 后重试，不得删除新所有者的文件／密钥／定位，不得二次关闭已复用描述符。此夹具不等于自然内核关闭故障。Apple 独立清理复用真实文件租约和 Keychain，缺钥／错钥／空主库／孤立日志测试须使用显式系统模式；普通编译不能代替验证。三端清理入口均不代替应用注销／撤权组合，不能把已有内部入口写成完整离线能力。

同步终结使用同一原子模式立即封闭读取并固定首个动作，实际 close／clear 在既有互斥及不可取消区间完成。同步／浏览 owner 的 close 在尚未终结时选择 PRESERVE，已选 ERASE 时只完成原擦除；CAS 失败后必须读取不可逆胜出模式，不能仍按旧 PRESERVE 继续。底层 LedgerStorage.close 保持严格保留合同，由 owner 调用已选动作。已保留关闭的旧 owner 不能转为擦除，迟到认证失败不得跨模式清新缓存；撤权失败用 clear 重试。NCC 可能触发协程栈恢复，异常回归须核原对象仍在原因链及认证原因未丢失，不要求外层一定同一对象。测试等待不可取消关闭时，读请求异步发起并先放行关闭；finally 必须解除合成屏障，避免测试自身永久等待。

已核验身份发布使用取得该 SessionDto 的原 ApiCredentialSnapshot；在同客户端凭据锁内先保存完整事实上的绑定记录，再发布目录 current，并读回存储／current／代次和原令牌。不可取消区间等待真实 SQL／CAS，不把取消当回滚；失败的资源仍由原 owner 保留并收尾，不暗中 close／clear。跨实例令牌写入不承诺跨存储原子性，应用仍须有最终操作围栏。非法 UTF-16 回归用运行时字符数组构造并先核码值，避免 JS 常量折叠把孤立代理字符变成问号。该组合不是完整断网恢复、HTTP 回退或页面接通。

登录／注册的内部 AuthenticatedApiSession 必须取同次响应的 SessionDto／access_token 和发起请求的固定服务地址；写 TokenStore 成功后返回，不在返回后重读当前令牌猜归属。公开入口保留原 SessionDto 和错误／写入时序，单次请求仍只读一次令牌，网络不占凭据锁。内部 pair 不代表并发登录排序、缓存发布或应用恢复已接通。

固定 LedgerSessionCacheOwner 必须在不可取消资源交付区间保存实际存储强引用，close 先封闭尚未交付的 open，再等原浏览 owner 的关闭／已选擦除；失败保留同一资源供重试，不能把 pending 租约丢在局部变量。网络不占资源互斥；每页核原令牌绑定身份及 current／代次，缺身份不因完整事实而返回页，也不增加 eligible 副本。真实工厂交付边界取消与 SDK 内部开库取消分别说明；内部范围组合不代表网络回退、跨范围清理或应用接通。

严格离线认证恢复只做一次原凭据会话请求，不复用会吞掉先前 HTTP／协议失败的重试循环；仅该路径启用响应头先于正文，记录已知拒绝并在 SDK 异常清理／超时解包后处理，真正取消优先。普通请求仍保留 saved-response／写入未知合同；局部缓存故障为主并保留网络诊断，不重试缓存或丢其 owner。必须覆盖 403→I/O、拒绝正文 I/O、超时包装取消、真正调用方取消及普通 POST 正文丢失。Web 原生 fetch 来源标记限定每客户端的原 Promise 拒绝对象及已核 SDK 直接桥，ApiFailure 和取消先于传输解释，不递归扫描原因或按文案猜测。保留跨客户端对象、仓储复用旧原因及实际可读 HTTP 拒绝回归；坏端口不证明物理断网，Karma／Mock 测试不替代实际页面冒烟。编译成功或 browserTest 无 XML 均不能算测试执行。

单次 LedgerSessionRestoration 的回退 catch 只包认证请求，在线核验后的版本／快照或目录／存储故障不得转读旧身份。固定凭据与目录代次，无活动工作区也核最终围栏；单例 Ready 仅表示本次操作完成，不复制身份资格。关闭先封闭状态，不等待 HTTP，实际 owner 在异步开库前已强持有；失败后不能重跑同一次恢复，但保留同对象供关闭重试。真实加密冷重开与工厂返回交付边界取消分别验证，不能据内部组合宣称 Graph、跨范围撤权和最终 UI 发布已接通。默认平台入口直接委托正式工厂并强制原 registration，existingOnly 原样透传；Android 复用已安装的 applicationContext，不建立替代根目录或第二套资源状态。Web 真实组合须消费默认入口及正式清理，原生编译不冒称默认桥已实际运行。

分页、流水类型目录及分类目录共用原完整事实／身份／令牌／current／关闭读取门，不直接返回全局标签绕过资格，也不为目录初始化投影索引。分类项公开 revision 取目录版本，路径按原父子名称及树序重建，保留存储 depth 与 Long sort_order 的原 Int 转换；不提前解释未使用的行 revision 或路径。共享类型标签、顺序及有效子类型对照原完整 golden，后端原鉴权不变。依赖外部合成夹具的新增 parity 测试沿 HistoricalMigrationEvidence 分类，以显式准备的双库运行 migrationEvidenceTest，不能破坏默认无夹具测试。

本地现金账户引用保留原 cash／loan／lend、停用状态、完整 Long 排序及 currencies 的 JSON 兼容，不把读取变为命令校验或静默清理。Python JSON 文本与 printable 冻结表共享后，须保留替换前完整标量摘要及原完整 golden，不能以委托后同一实现自对照作为独立证据。 JSON 小数的 binary64 候选须经精确中点和 ties-to-even 核验，Wasm 平台解析不能作为正确位模式的 oracle；零、次正规／正规及溢出中点、极差近似、巨大指数和原异常边界须直接核独立 bits，不删完整样本或展开指数。账户读取沿原完整事实／令牌绑定身份／current／代次和关闭门，不因全局账户目录绕过资格，也不代表应用页面已接通。

独立清理的应用默认入口强制绑定原 ticket；在取得真实范围租约后、删除前核代次。三端共用 LedgerCacheErasureOwner，未授权时保留材料并只释放原资源，部分擦除继续原动作，完成擦除后仅重试最终释放。失败仍强持有原对象，不重新取得删除资格；平台工具的显式无 ticket 入口不作为应用授权。真实 Web 租约后换代测试与原生编译分别说明，不能据此宣称注销／撤权枚举或应用离线恢复已接通。

跨范围缓存清理只从原 ticket 同次核验的范围目录选择原使用者下不可访问的工作区，复制保留集合并在首次成功枚举后固定列表。失败重试沿同一个已强持有擦除对象，完成项不重做，换代后不开始下一项；不要重新枚举后来的材料。该清理不推断用户身份，调用者仍须先封闭旧读取和关闭旧缓存；内部组合与真实浏览器存储回归不等于应用退出／撤权已接通。

已核验会话恢复在任何删除前构造并校验完整工作区范围，再按同次使用者／可访问工作区清理，随后才刷新／发布活动账本；离线历史身份不能作撤权依据。LedgerCacheErasure.close 仅收尾已选资源，RejectedReleased可正常终结，释放失败仍保原对象及诊断；批次close先封新项，目录异步读取返回后也须复核关闭，再创建下一eraser。父恢复对象须在等待资源互斥前传递stop；原子发布清理引用后再次核Closed，防止关闭先读到null的迟到动作。clear的失败不等于close失败或整个任务已完成，不按异常类型猜测租约释放。原始坏UTF-16在线回归须用JSON escape穿过传输，不能把UTF-8替换后的其他scope当作原样本。

绑定操作 ticket 的登录／注册入口须在请求前核 origin／代次，响应写入前在同客户端 credentialMutex 内再核代次及请求原令牌，然后复用原 get／set 错误边界写入。网络不占凭据锁，旧公开入口保留原写入时点、返回对和读次数。该守卫只防覆盖后来已成功的同客户端登录；最终缓存／UI 发布仍要围栏，不把它写成跨客户端、标签页或底层 TokenStore.set 的原子事务。

账户映射只传 statement_account_mappings 最终增量；端侧直接消费共享操作的 used_account_selections，不新增完整资金组、行决定或原 funding 观察来供服务端重演。服务端核真实工作区账户资格、合法非微信渠道、funding_group_v1、非空账户／来源键、自然键及旧修订，复用原仓储的同值不写及实际 ID；同自然键同账户的新引用可在可信事实内归一为同一计算身份，保留原 New／空修订，重复声明仍拒绝、异账户修改仍须原 Existing 修订；来源结果仍限原事务／工作区且回滚后失效。完整鉴权、基线、身份幂等、收据及服务组合仍另验。

来源绑定只传既有最终字段，校验真实目标／自然身份／作用域／稳定证据及相关文档页一致性；不得为此新增原文件位置、完整同源成员或代表行证明。内部保存沿同一来源事务 owner／savepoint 围栏，复用原绑定 DML 返回实际 ID，同值不写、变化推进修订；来源保存先解析同一请求的真实来源身份，精确 New 命中不重复源 DML／余额，New／空修订保持原义；绑定保存使用实际精确或已删绑定 ID，新增 alias 不被吞掉。完整效果与公开提交以真实事务及 HTTP 组合测试为证，不能仅以保存 helper 通过代替。

来源元数据仅传既有空页与问题最终字段，沿原来源事务结果在绑定后保存。空页与绑定共用相关旧／新 alias 的文档一致性校验；原规范业务页识别仍决定空页去重，不能把普通 alias 的整份 coverage 当作占用页。问题保持 owner／key，候选先验旧依赖再存同批实际 ID／修订，已有歧义的空候选请求保留原整项；普通来源更新不得退役歧义／关系问题。同归属／问题键且正文与完整候选观察同值的新引用可复用问题身份，原 New／空修订不变；歧义空候选先过输入门再保留整项及旧候选，不声明旧候选当前有效；非保留精确命中须再次核写后候选修订，变化拒绝，退休旧项再 New 同键仍新建。真实候选替换与退休级联复用原仓储，不新增原文件真实性声明。旧 relation: 问题省略即保留，显式 Existing 只核真实 owner／key／原观察及整项同值后回已有 ID，不要求来源写项，不执行 saveIssue 或当前候选查询／更新；新建、修改和退役拒绝。当前生产规划器丢弃非法自动组／拒绝明确选择失败，不生成旧 skip／discovery 的新失败问题；历史直接 writer 夹具不能扩大入口或引出 cohort／commitRef 证明协议。完整身份响应与命令原子效果仍由外层服务验证。

首次关系阶段仅供已排除整批精确幂等及其 B 的命令调用，沿原来源 owner／savepoint 围栏，先验证写前依赖／公共选择及写后来源的完整图，再按原选择组顺序执行 B，随后复用 add／setCandidates。私有计算 ID 不窄化新引用，联合存储 ID 由服务端分配；每个 draft 包括空候选仍走原候选修订语义，不丢弃非 draft 候选或接受仓储静默改变 validity。证据递归解码前核对象和 64 层边界；完整 posted 锚点的 valid 完整候选复用原角色／账户／币种／金额硬约束，不以此强制草稿完整。客户端关联金额与真实图结果须精确一致，完成计划的内部写集构造器已提供，完整解析后文件计算已提供，内部 owner 预览及显式生产恢复已接同一计算，方案页面已消费本地预览选择，完整关系分支验收仍待完成。真实 C→B／关系→协调／D 回归不能替代外层鉴权、基线、整批身份幂等和收据，薄适配不调用旧 plan／fresh 重建入口。

内部首次写集命令组合须复用 writeGuarded、原提交边界、账本基线及永久收据；同连接固定工作区，锁后及全部返回路径重新鉴权，成员资格锁保持到提交。原键重放先于规则／基线且不执行保存阶段，新请求按来源、绑定、元数据、最终映射、C、首次关系／B、完整组协调／D、余额、收据原子保存。正文摘要和解码共用同次有界深复制快照，保留 JSON 数字词法，转义前核预算；不得由原可变容器在等待锁时替换写入内容。收据读取真实版本，不为 no-op 额外 mark；可选 write_set_result 复用 RESULT_SUMMARY 单一快照，严格闭合身份／修订字符串及 UTF-8 独立 64 MiB 预算，旧摘要仍限原 1 MiB，旧收据缺省不补造，损坏结果及错误 operation 必须拒绝；完整组装只消费合法来源问题与已核旧关系问题的实际身份，不恢复不可达失败问题生成；收集各保存阶段真实 ID，复用协调／D 后最终事实取得修订，原请求数组及组成项位置不得改序，已消费关系批次仍入结果。只返回实际消失且已观察的旧条目退休身份及明确解决的问题，临时对象不充当旧退休；shared 的 ImportReceipt 已直接保留同一闭合结果值，映射不丢弃或转数值；当前 ID／原键读取用于实际结果核实，旧计划 resolve 不作为新写集核实端点；内部位置结果引用不能限制 opaque 新引用。来源精确身份及关系整批幂等已接内部首次命令；候选方案经共享 GroupRules 逐组合过滤后由原 writer 拍平保存，候选并集不保留组合关联；服务端核实际生效图及原候选资格，不把各边候选同时确认或增加全笛卡尔合法门。公开 POST /api/v1/cash-import/write-set 已复用该事务命令，必填且唯一的workspace_id查询参数固定原意图并与同次身份及事务身份核对；收据读取支持同范围参数，并将HTTP身份范围传入读事务，不能只在middleware核一次。严格 UTF-8／JSON 入口保留数字词法，不能走旧 objectInput 的 Python 数字归一；拒绝重复键并沿64 MiB／128层门。基线及规则冲突返回409，已证明未提交才带not_committed；程序异常500，已识别临时存储故障503，保留安全诊断和真实提交状态。原键／ID永久读取供结果核实，旧plan resolve不适用；未找到收据不证明在途操作未提交。共享客户端薄传输必须显式传原ApiCredentialSnapshot和工作区，挂起前以既有精确DTO固定正文；POST和ID／key读取都带同一scope，不重读后来登录或清全局token。新流拒绝空成功、旧operation／plan及缺版本／完整结果，复用原收据序列化和版本校验；已知HTTP拒绝在body丢失时保状态但不补commit_state，404未找到不代表确定未提交。传输不承担重试、缓存发布或原操作恢复材料清理。流水写集片段须读取完成计划的operations来源值及preview引用，不从展示值反推；New引用保留完整整数文本，Existing修订及隐藏字段沿同一快照，原row_choices已采用的非公共编辑独立表达edited_fields，C保留原entry_edit及旧条目观察，不能与B／D混写。来源写集已组合绑定、来源问题／候选、显式解决、空页及最终账户映射，保留duplicate早退、自然键末次元数据与旧稳定证据；duplicate不采用本次来源说明。来源原文的JSON叶子在64层门内转为内部值，保持既有数值比较及精确wire独立。文档选择与旧保存共用纯规则；映射只消费used_account_selections。关系写集沿完成计划与原关系选择，保留联合批次首次出现和组内边序、精确引用／修订、锚点／角色／证据，draft 候选按完整方案逐边拍平去重；空 applied_amount 沿原 writer 为 0，B 仅来自 accept，实际联合标签由服务端分配。无 secondary 且双角色缺省的开放退款／转账候选按原变体以金额符号定向，显式角色及闭边保持原校验；以原 writer 真保存回滚与新 stage 的双库对照为证。最终条目与完整写集构造已消费同一完成计划，沿共享受影响分区选择和私有副本按 C→逐组 B→协调→D 计算公共／成员独立值与人工标记，声明全部受影响旧条目修订，含实际退休者；明确 D 目标可扩大选中分区，候选上下文本身不能扩大。未定时草稿不补时间，实际 UUID／身份锚／修订由服务端生成，不上传临时身份；两真实旧条目合并与完整服务回滚／原键重放／新键重导需经同一提交 Application Service 双库核对，不冒称页面或缓存 owner 调度已接通。稳定输入与冲突重派的内部能力已提供，内部 owner 预览及显式生产恢复已接同一计算，outbox 和恢复应用仍须接线。

来源数据身份解析只汇总自然键／稳定证据的全部 live owner，核原 New 默认值、业务来源值和完整 ordinal 金融组成项；复用来源 writer 的字段归一及方向性说明比较，金额／JSON 沿原精确值比较，legacy／指纹／人工标记／组成项隐藏字段保留。只包装 proposed 输入的 InvalidCashValue，存量损坏保留原异常；混合声明保留原数组下标。结果仅描述原事务／工作区内的 parent／part／精确 binding 和已删绑定复用，回滚即失效；不改 DTO、不补 revision、不消费 C／B、不视为整项已应用。共享引用／已确认图／通用条目拓扑复用可信 `CashWriteSetSourceAliases`，transaction／component／binding 各自归一计算身份，核混合引用重复、实际旧条目观察和原图；公开 New／Existing 及 expected_revision 不改写，原 Existing 仍核原观察。未命中引用继续使用私有计算 ID，旧 origin／资金组成项及绑定排序沿原图。文件范围／来源账户／绑定准备及保存、元数据、最终映射、文件条目拓扑、C 合并、联合批次／公共选择及首次关系已消费同一映射。owner、自候选和 C 合并按实际父项／旧 entry 判断，显式 Existing 仍核原观察；B 保持原逐选择组顺序，关系键不套用父项 alias。同值来源回执 unchanged 不吞本次新增 alias／映射／问题或 C／B／D，关系整批幂等已在同一内部服务消费，公开 HTTP 命令已复用同一事务服务；软删命中不复活父项，不按时间／内容召回。

关系身份解析须先校原请求引用／旧观察、归一 B 一致性及原仓储的完整基础标量／精确金额／文本／证据预算，再按原边键与实际来源匹配完整联合批次。完整命中须覆盖全部旧 decision_group 成员且仍 active；部分、异值、重复键拒绝。draft 候选核写后真实 live posted 目标修订及原证据／validity，有候选的端点／锚点发生来源改动不得消费；来源保存按原逐行顺序复用 markCashRelationCandidatesStale，保留多目标时的关系修订推进次数。消费副本仅删已证明的完整关系／选择组，保留其他来源、C、条目、问题和映射；B 不重放，原位置回执保存真实 ID／duplicate，独立首次批次照常执行。事务／workspace／savepoint 围栏、原键收据及原 New／空修订不变，不以单个解析阶段宣称端侧完整候选方案／写集 builder 或恢复应用完成；公开传输复用同一事务服务验证。

内部 `LocalCashRelationPipeline` 已组合真实 B–D 规则及索引，金额键按数值去尾零、证据保留原 scale，transfer 默认索引 sourceGroup 为 other，完整管线索引使用真实来源分组。菱形退款保留精确 0.0001 容差；商户退款沿精确正规化和 Duration 纳秒溢出。字符串时间仍截到微秒，已计算 Instant 保留纳秒；两种输入不能经普通字符串往返后混同。该配置不包含 Phase A 计划、完整图过滤及预览发现读取，不能将内部管线写成文件导入已接通。

内部 `LocalCashRelationPlanning` 已组合完整 Phase A→D、原完整图校验及依赖摘要；原完整规划黄金须由生产配置执行。原行映射复用 `cashRelationFactFromRow`，金额、真假及 ordinal 保留宿主适配；规划器本地真假门须识别 JSON 零与 false，不能让标量包装阻止原编号回退。依赖摘要保持来源整数精度及旧 binary64 浮点表示、BigDecimal 文本与 Instant；普通字符串沿原 legacyReferenceUtf8 边界，不复用严格请求编码器。它与完整确认结论的开放 JSON 数词保真是不同合同。真实预览发现已由 `CashImportRelationDiscoveryRules` 共享并在本地组合，只有实际种子需要时才读取同快照别名；来源备注继续取原载荷。`buildLocalCashImportBusinessPlan` 已组合文件操作准备、预览工厂、真实发现、关系及公共选择、完整计算型呈现；恢复时传原 occurrence 编号，最终才冻结为计划 JSON。该入口无 IO，不代表页面、队列或完整文件解析调度已接通。
