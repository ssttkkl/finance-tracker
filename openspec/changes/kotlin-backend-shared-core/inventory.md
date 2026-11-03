# 全量迁移盘点

基线：`0c038a75e34264bd6d4aa5cce2070727df9d9ff7`。以下列出迁移验收范围；「未迁移」不代表原功能不可用，原 Python 运行时保留。完整 HTTP 方法与路径见 [接口矩阵](routes.md)。当前已接入 86/86 个 HTTP 操作，其中 6 个维护计划操作已完成完整合同验收；投资导入已接入、完整双库功能覆盖与独立复核已通过，同步已接入、原交易所双库集成及独立复核已通过。接入数不代表全部原测试或模块已完成。按用户要求，本轮只以功能迁移和功能测试验收，性能测试不阻断本轮功能交付；原预算和未通过证据保留。

## 模块与合同

| 能力 | 当前实现入口 | 共享核心 / 服务端责任 | Kotlin 状态 |
|---|---|---|---|
| 登录、注册、会话、工作区、邀请、成员 | `application/access.py`、`web/access_routes.py` | 共用 Role/DTO；Argon2、SHA256、会话 30 天、邀请 7 天一次性、最后管理员及删除留服务端 | 已实现全部 15 个 HTTP 接口并通过匿名双库对照；认证功能与独立复核已完成；完整财务删除、分类后稳定收支清理及另一工作区保留的原服务断言已验证 |
| 账户生命周期、币种、余额 | `account_commands.py`、`accounts.py`、`cash_balances.py` | 共用账户列表和命令 DTO；权限、账户修订、引用/余额检查、snapshot、收据原子写入留服务端 | 稳定 ID 读写及现金/投资列表已通过匿名双库、原生规则和实际 Compose 回包验证；余额查询／真实估值及当前财务报表已接入并完成双库验证；旧写入适配、底层 CAS 和历史迁移等仍待迁，4.4 未完成 |
| 分类树、批量/单项分类 | `cash_categories.py`、`cash_classification.py` | 共用目录 DTO、真实祖先解析、五层/循环/子树约束；同级名称 casefold、目录锁/修订、entry/投影同步留服务端 | 已实现目录读取、创建、编辑、排序、删除检查及原子删除；删除同步 entry/恢复值/事实/候选/全部投影。单项/批量分类已完成当前数据集、完整成员、人工保护及原子写入；独立 move 服务已接增量维护，update 保持 Python 调用边界，完整分类功能已迁，性能按用户要求不纳入本轮验收 |
| 现金事实、草稿、手工记录、转账、删除 | `cash_record_policy.py`、`cash_ledger.py`、`cash_record_maintenance.py`、`cash_record_writer.py`、`cash_record_deletion.py`、`cash_manual_*`、`cash_draft_merge*` | 共用精确金额/类型/分摊/完整性；当前账户资格、完整组、来源固定字段、修订、事务留服务端 | 字段/转正式纯规则 26 个原定义已迁并复核，动态类型边界已适配；账本类型选项 GET 已双端消费，余额快照/组成项增量通过双库原生测试，手工新建已完成严格公开输入、完整现金部分/余额/稳定收支/投影同事务写入及现有 Compose 消费；完整转账创建已通过双库同事务事件/关系/余额/投影与既有 Compose 消费；旧现金新增/余额校准已通过双库 96 组 HTTP/13 表全列、原生断言、锁等待权限及真实 Netty；来源事项选择纯规则已完成原生及 708 组 Python 差分，来源正文效果另有六类来源原生断言及 2,208 组完整差分；已接入单条详情、普通草稿保存及单流水维护重验；普通保存保持草稿／余额、永久收据及独立当前详情，原 17 定义／82 参数、双库完整 HTTP 与现有 Compose 消费已覆盖，完整维护已完成合同验收；单条／批量删除及影响预览已接入当前父端点／候选清理、稳定收支／投影／余额事务，保留原文／现金部分，锁后权限、回滚、重新导入与现有 Compose 批量消费者已验证；手工转账双端编辑已接入，共同确认类型／原关系／余额；公开 OpenAPI 已接通；导入剩余验收及其他原持久化、来源和投影测试仍待完成 |
| 现金维护计划、确认、结果未知核实 | `cash_mutation_*`、`cash_plan_execution.py`、`cash_commit_outcome.py` | 先共用值合同与客户端请求校验；纯效果计算按真实双端需求接入；CAS/TTL/owner、当前事实证明、永久幂等收据和回滚留服务端 | 永久收据五个 GET 已通过双库和实际 Compose 解码；超 Long 客户端计数限制保留。仓库原键重放/上下文唯一和写前校验已迁并由账户命令复用；共享预计图内部入口已支持已准备的维护操作、额外流水／决定组及快照／当前关系回调，保留无文件与只读边界，`maintain_relations` 的当前组／候选与人工关联预览及事务提交已接通，保留上一方案、耦合清理、完整重验和永久收据；35 个原定义／112 个双库参数及公开 HTTP／Compose 消费已迁，手工转账双端编辑、原 3 定义／14 参数已接入同一单元，另以 1 定义／2 参数核对孤立单端拒绝；身份草稿合并原 29 定义和维护故障／恢复原断言已迁并通过双库；解析暂存原文件已验收（51 migrated／4 retained），基础持久化及其他存储／运行时原测试仍待迁移，维护合同已验收；已有维护预览/读取及正式编辑、草稿保存/转正式的提交与原键核实接入共享解析存储，双库测试覆盖数据库提交失败保留方案、提交后临时清理失败仍返回原收据及永久消费优先；单流水提交已在工作区锁后重建并核对冻结操作及依赖；普通维护原提交文件全部 13 定义／28 参数已覆盖，包括来源原文拒绝、完整退款可见与三类发现失败的业务表／暂存不变；完整维护、内部取消、关系组合及多成员合同已验收；其余模块原测试和历史迁移仍未完成 |
| 文件准备与来源账户选择 | `cash_import_context.py`、`statement_account_mapping.py`、`cash_import_account_mappings.py`、`cash_import_funding.py` | 共用已解码行和来源账户规则；来源完整性和实际映射写入留服务端 | 来源账户扫描与内部建议已实现；导入资金组扫描/选择纯准备已实现并通过 115 组完整 Python 结果及原生边界回归；上传文件准备纯层已实现并对照 20 个文件名、5 个选择失效和 36 个关系证明用例；只读来源规划仓库和会话视图已实现并按 SQLite/PostgreSQL 匿名种子对照；上传 HTTP、工作区锁内发布与永久收据隔离已接入；导入计划公开视图/分页、逐流水操作规划与条目目标/公共条目编辑纯层已实现并按 Python 结果对照。只读预览准备、完整内存快照、类型变化回滚、预计投影、来源匹配事实/关系提议发现、关系组/候选构建、冻结关系选择、最终图应用及预计列表/详情/处理明细呈现已实现并有双库黄金与 Kotlin 业务断言；完整业务计划与预览 HTTP/共享暂存条件发布已接线；来源绑定/当前问题/认证空页事务内写入已实现并有双库合同及 Kotlin 回归；联合导入确认与永久收据已接线，原组合测试定义未计完成 |
| 导入来源计划、业务计划、整笔选择、提交 | `cash_import_source_plan.py`、`cash_import_business_plan.py`、`cash_import_entry_choices.py`、`cash_import_commit.py` | 先共用账单行、预览/选择/提交协议；规划留服务端，按真实双端需求再提取纯规则；提交重读来源/账户/当前事实/关系并比较操作指纹，原子保存余额/entry/投影/收据 | 来源规划读取已迁；导入计划公开视图/分页/冻结详情纯层、只读预览准备、含类型变化回滚的内存快照/预计投影、关系提议发现、关系组/候选构建及冻结选择/最终图应用已迁；完整业务计划与预览 HTTP/共享暂存条件发布已接线；来源绑定/当前问题/认证空页及共用流水/现金部分写入、类型变化局部关系维护、关系组保存及确认余额/完整条目编辑收尾、文档证据筛选、冻结候选恢复和有界新增已迁，并接入同事务联合确认、永久收据、同键重放及原键结果核实；完整原组合测试、真实 R2 与真实账单专项待验 |
| 来源文件与行证据 | `adapters/cash_statement_*`、`bank_statement_document.py`、`icbc_pdf_*`、`domain/cash_source_*` | 保持上传/服务端解析流程，先共用行模型和协议；解码、逐行归一、字节预算/物理位置/声明真实性留服务端，不新增客户端解析入口 | 平台 CSV/TSV/XLSX、建行 XLS、工银亚洲 TSV、工行信用卡/借记卡 PDF 的原始文档读取已迁入后端，并分别通过 Python 匿名金标准；记录类型/子类型与对方账号公共规则、平台、银行与工行 PDF 行归一已迁并对照；来源身份/文件绑定键、已解析文件组合、来源账户扫描及来源对应解析已迁；多文件来源批次规划已迁并通过原功能断言及完整结果对照；解析会话编解码、内存及 R2 条件对象端口已迁；R2 SDK 与清扫器已接本机运行入口；维护计划存储已接线；内部来源账户对应仓库和只读建议已迁，发布保留调用方事务；上传文件准备纯层、只读来源规划/会话视图与上传 HTTP 已迁；完整导入规划/预览及共享暂存发布已接线；确认事务、导入成功证明的对应发布、真实 R2 与真实样本仍未迁移 |
| 支付镜像、退款抵扣、转账配对 | `domain/relations/`、`cash_relation_policy.py`、`cash_relation_graph.py`、`application/cash_current_relation_*`、`cash_relation_context.py`、`relations.py` | 先共用选择/命令协议与客户端组合校验；完整图和候选纯计算按双端需求接入；查询当前超集、授权、决定及落账由服务器负责 | 角色/闭包/分组/完整图纯规则 43 原定义已迁并复核；当前角色关系字段写入、按修订替换及规范候选替换仓库已迁，类型变化局部维护已迁；关系组联合写入、完整重复识别及发现失败记录已迁；关系组已接入联合导入确认，其余资金生命周期仍未迁移 |
| 当前收支、证据、分页、投影发布 | `cash_projections.py`、`web_queries.py`、`adapters/relational/cash_*` | 共用 DTO/版本及纯展示结果；当前成员/entry 完整性、staging/active dataset、快照游标由服务器负责 | 投影纯构建、管理员状态／重建、暂存发布、全量及增量维护已通过双库；当前详情、按流水定位、证据及账本列表 GET 已接通，列表保留游标／序号、筛选／版本、全局选项及精确月汇总，并由实际 Compose 消费验证。手工新建、转账、旧现金新增／余额校准及导入已接线；其余公开写入、完整组合原测试、历史迁移和性能仍未验收 |
| 投资事件、重放、持仓/成本、表现 | `investment.py`、`investment_import.py`、`investment_web_queries.py`、`domain/investment_*` | 共用协议与实际客户端需要的精确计算；完整重放/成本/PnL/回报按双端需求提取，来源/持久化由服务器负责 | 事件列表／账单原文两个 GET 已完成原 HTTP／只读、源断言、真实故障／当前权限与现有 Compose 消费验收；原应用四定义和 HTTP 前四定义共 8／11 参数已迁，纯命令／事件重放、基础币种成本／手续费、快照有限性及周期损益／回报依赖已实现，原 50 定义／68 参数专项通过；数据库命令与原 POST 已接通；原存储／US2 与编码回滚 7 定义／12 参数对应 Native，本地持仓／名称及完整事件读取已实现，原五定义通过直接断言、真实原查询对照和双库完整功能覆盖；完整估值应用及公开 GET／SSE／刷新已通过原断言、双库完整功能覆盖与真实 Compose 消费，HTTP 原六定义／七参数已迁；投资导入已接入，原集成断言、完整双库回归及独立复核已通过，其他原测试继续迁移 |
| 现金—投资资金调拨 | `cash_investment_funding_relations.py` | 共用 UTC 窗口/资格；端点互斥、确认/拒绝、当前事实和审计留服务端 | 四操作已接入；原 13 定义／27 参数及双库完整验证通过 |
| 连接器同步、增量游标 | `sync_service.py`、`adapters/connectors/ccxt_exchange.py`、`polymarket.py` | 共用事件归一；服务端凭据、网络分页、cursor 与财务写入不下沉 | 内部同步事务与游标已迁移，原 21 定义及双库原子性／权限验证通过；凭据读取、Polymarket 连接器和 CCXT 映射已迁移；Java CCXT SDK 适配与本机 HTTP／HTTPS 对照、独立复核已通过；公开同步工厂与 HTTP 已接入，原交易所双库集成及独立复核已通过 |
| 行情、FX、历史估值 | `adapters/market_data.py`、`fx_rates.py`、`application/valuation.py` | 共用资产身份、估值/新鲜度规则；外部请求、缓存和超时由服务器负责 | 估值纯服务和行情提供器已实现并有原断言及真实 Python／SDK 对照；账户／报表已接入真实估值；FX 历史中间价／缓存依赖已实现，原五定义和实际 JVM HTTP 专项通过，完整验收见 tasks；完整组合估值内部应用已验收，原 26 定义／26 参数直接对应；持仓 GET／刷新／SSE 已接线并完成本机合同验收 |
| 持仓刷新、SSE | `portfolio_refresh.py`、`web/app.py` | 工作区隔离 worker；5 秒刷新、15 秒 heartbeat、60 秒 backoff、last-success/generation/version、关闭等待 | 进程内协调器／缺报价合并已通过原四定义、168 组完整输出／判等及独立复核；已接应用生命周期／公开 GET／SSE／刷新 POST；工作区隔离、发送前当前身份、取消释放及退出清理已验收 |
| 解析暂存、R2、清扫 | `parsed_import_staging.py`、`parsed_import_cleanup.py` | owner/CAS/TTL 最大 1800 秒及大小预算；内存/R2；启动 sweep、30 秒周期、100 项分页；健康/安全日志和关闭等待 | 编解码、内存/R2 条件端口、SDK 和清扫生命周期已迁并接本机入口；维护计划使用同一存储；上传文件准备纯层与上传 HTTP 已迁，完整导入规划/预览及条件发布已接线；联合确认已接线；解析暂存原文件已验收，其他存储／运行时原测试及真实 R2 仍待验收 |
| 运行装配、schema、数据库 | `runtime.py`、`config.py`、`adapters/relational/{runtime,dialect,uow,workspace_lock}.py` | 显式 FT_DATABASE_URL、Alembic head 校验；SQLite BEGIN IMMEDIATE/PG FOR UPDATE、无回退或自动迁移 | 未迁移 |
| 已退役入口 | `tests/contract/test_cli_free_runtime.py`、`test_cli_replacement_api.py` | 无产品 CLI；旧 relations/check/accept/reject 和 cash-facts 保持 404 | 未迁移验证 |

## 持久化范围

当前表覆盖 `workspaces`、`users`、`workspace_memberships`、`user_sessions`、`workspace_invitations`、`accounts`、`statement_account_mappings`、`cash_mutation_receipts/results`、`cash_categories/states`、`cash_transactions/components`、`cash_source_empty_pages/bindings`、`cash_transaction_issues`、`cash_issue_candidates`、`cash_projection_states/datasets/projections/members/relations`、`cash_entries/members`、`investment_events`、`cash_investment_funding_relations`、`ledger_snapshots`、`transaction_relations`、`cash_relation_candidates`、`account_aliases`、`sync_cursors`，以及 `cash_source_documents.py` 等独立模型中的来源文件证据。

PG `NUMERIC(38,18)` 与 SQLite `String(96)`/`decimal_compare` 无损十进制等价，UTC 显式时区；BIGINT 在 SQLite 保持 INTEGER affinity。复合 workspace 外键、逻辑删除、来源证据和审计不删改。收据 `(workspace_id,user_id,idempotency_key)` 永久唯一，同时绑定 digest、operation、plan/context。请求结果未知按原键核实；暂存清理不能重新开放已消费命令。

已实现的 Kotlin 业务查询和写入已改用 jOOQ OSS `3.21.9`；当前功能回归使用 SQLite 和本机 PostgreSQL 18，当前各片证据见 tasks；早期 PostgreSQL 16.14 记录不替代当前验证。67 个官方生成文件只留后端，schema 仍由原 Alembic 管理；JDBC 继续负责事务、保存点和驱动配置。当前详情、按流水定位和收支证据三个 GET 已实现并有双库功能及实际客户端验证，独立复核已完成，完整组合原测试仍待迁移；当前账本列表已接线，保留完整筛选、分页及月份统计；旧流水列表已接通后端原读取合同，当前 Compose 没有该 GET 消费者，旧交易查询已保留正式流水、UTC 月份／账户／分类、1–200 条和原平面响应；它也没有当前 Compose 消费者。账户余额查询和财务报表已接入真实估值与当前正式投影；两旧 GET 没有当前 Compose 调用者，两端 TODO 保留。查询剩余原测试继续待迁移。证据不包含账单原文，单条流水详情与现有来源消费者已接入；完整来源规则组合原测试仍待迁移；旧证据 DTO 的草稿空值解码限制保留，来源成员 DTO 的空值消费已覆盖。

当前流水关联候选 GET 已接线，复用现有 Compose 搜索消费者，保留原始字段与只读能力、完整关系闭包排除、按 ID 连续分页、账户／精确金额／本地日期筛选；不依赖活动投影。三个原只读定义已迁移，完整关联预览／确认及原 HTTP 定义已直接迁移，维护合同验收矩阵见 tasks。

## 格式矩阵

| 来源 | 字节解码与预算 | 可共享内容 | 状态 |
|---|---|---|---|
| 支付宝、微信 | UTF-8-SIG/GB18030 CSV；openpyxl XLSX，ZIP/XML 防扩张 | 原列、精确金额/方向、来源时区、类型、账号属性、支付方式、来源说明、provider ID/跳过原因 | 原始文档读取／归一与上传解析已接入；真实账单专项及剩余原测试待验收 |
| 建行借记 `ccb_debit` | OLE XLS/xlrd，声明、顺序号、表头 | 已解码行归一、币种/日期/账户/覆盖证据 | 原始文档读取／归一与上传解析已接入；真实账单专项及剩余原测试待验收 |
| 工银亚洲 `icbc_asia` | UTF-16 TSV、繁体表头、独立时间列、银行/币种/笔数/日期声明 | 已解码行归一、规范账号、来源身份/覆盖 | 原始文档读取／归一与上传解析已接入；真实账单专项及剩余原测试待验收 |
| 工行 `icbc_credit/icbc_debit` | PDFBox 独立 JVM、密码错误区分、时间与输入输出预算 | 结构行、退款/冲正、时间、类型、完整页证据 | 原始文档读取与行归一已迁移（PDFBox，含密码失败区分、页/字符/表/行预算、页指纹与文档键）；上传解析已接入；真实样本及剩余原测试未验收 |
| DFZQ | PDF 和明确文本路径 | 投资事件、总发生金额/费用/成本/CHECKIN | 已提取文本的解析／身份／映射内部依赖通过原 23 定义、完整对照及独立复核；PDFBox 独立进程及数据库导入／HTTP 已验收 |
| IBKR、Schwab | CSV | 投资归一、重叠导入身份、ticker/费用 | IBKR 原七定义与 Schwab 原八定义的解析／身份／映射和离线重放内部依赖已通过完整对照及独立复核；数据库导入／HTTP 已通过双库及独立复核 |
| uSmart HK | 加密 PDF 和明确文本路径 | 投资事件与产品账户归属 | 文本解析／身份／映射及离线重放已完成内部依赖验收；PDFBox 独立进程及导入事务／HTTP 已验收 |

现金解码限制含 100 MiB 输入、25 MiB 解析内容、100000 业务行、200000 物理行、128 列；格式各自合同继续保留。投资上传为 base64 JSON，解码后 25 MiB，密码头 `X-FT-Statement-Password`，Kotlin 直接传递字节，不创建 HTTP 账单临时文件。共享核心不宣称能在 Wasm/iOS 运行 JVM/Python 字节库；过渡格式 adapter 若启用须写明调用、失败与退出条件。

## 测试与黄金合同

当前记录复用与发现失败结果两文件共 9 定义／26 参数，已直接迁移 8 定义／22 参数；1 定义／4 参数依赖 SQLAlchemy 未刷写删除状态，按语言边界保留 Python 参考。Native 验证实际软删除拒绝、完整回滚和只接受 ID 的不可变快照边界，不用物理删除绕过财务外键，也不计为已迁移。完整来源闭包与无父修订变化的现金部分依赖分别直接验证，证据见 tasks。

维护 HTTP 的 `CashMaintenanceOriginalHttpTest` 直接对应原文件全部 17 定义／52 参数，包含实际回滚、提交后丢失响应、原键读取故障、权限／过期、非法结构、私有归属及成功后详情不可读。26 个原组合在双库分别执行，公开输入与内部规范化表示分别验证；原定义与附加断言分开统计，完整矩阵证据见 tasks。

维护预计图原 12 定义／30 双库参数和额外确认效果原 9 定义／9 参数已直接迁移。实际预览／暂存／详情核对来源、关系、种子和拒绝不发布，完整 31 表与 SQL 只读；纯效果核对明确选择不重复确认、字段值不泄露、连带成员已有问题及整组失败。15 个完整图检查点仍单独保留，不当作额外原定义；当前关系预览原 18 定义已完成直接断言，公开 JSON 边界本片补齐；完整维护接口验收已完成，剩余模块与原测试仍单独保留。

原 OpenAPI／浏览器错误和写边界新增直接迁移 8 定义／12 参数；历史 Alembic 收据迁移 1 定义／2 参数整体保留并在两库运行，Native 读取原真实升级后的两份完整收据及当前 schema，且全表只读。公开文档按当前真实路由筛选原完整 86 项资源，完整装配为 86 项；框架元数据入口不增加业务接口数。当前关系预览、稳定收支编辑及公开协议原定义已有直接对应，维护接口验收已完成，剩余共享存储／基础规则原测试继续单独保留。

全量 Python 测试对应表位于 `compose/test-migration.json`，检查器为 `scripts/check_kotlin_test_migration.py`。基线的 `tests/` 与 `compose/tests/` 共 2,612 个测试定义，授权新增现金／投资响应编码、转账公共编辑及同步凭据脱敏回归后当前为 2,617 个定义、5,894 个完整 pytest 参数化用例；当前工作树已映射 1,472 个定义／3,278 个展开参数，38 个定义按语言类型、宿主日志、历史迁移、异常边界、被替换的 PDF 工具实现及旧来源／统计／编号／行异常包装保留，其余 1,107 个定义保持 pending。当前草稿合并原 29 定义／88 参数及既有转账修复已通过完整双库组合和实际客户端验证，最终证据见 tasks。Kotlin/JUnit 回归方法与原 Python 参数分别统计，不合并为迁移数。新增源回归单独通过 `source_additions` 绑定当前指纹和理由，固定基线不变；未登记目标的定义一律保持 pending。检查包含测试函数、模块参数、fixture、数据文件与配置指纹，明确复核后的变化绑定基线／当前指纹和原因；不把黄金样本数或旧 Python 回归数当作 Kotlin 测试迁移数。最终 `--require-complete` 必须带完整 collection，存在未处理项即失败。

- 维护提交恢复原 9 定义／34 参数已由 `CashMutationRecoveryOriginalTest` 直接对应，包含真实双库锁、永久收据优先、过期与权限错误、局部依赖拒绝和当前账户资格；完整验证证据见 tasks。
- 维护计划发布原 12 定义／40 参数已由 `CashMutationPublicationOriginalTest` 直接对应；当前完整双库与实际 Compose 消费通过，独立最终复核无可执行 finding，证据见 tasks。
- 身份草稿合并原规划 16 定义／44 参数和确认 13 定义／44 参数已由 `CashDraftMergeOriginalTest` 直接对应；当前两库完整组合与实际 Compose 消费通过，最终复核无可执行 finding，证据见 tasks。
- 来源问题服务原文件全部 22 定义／78 参数已由 `CashSourceIssueOriginalTest` 直接断言；39 个原组合及 2 个补充场景分别核对完整只读计划与真实提交，补充不计迁移数。来源说明、保护差异、校验顺序及所选事项顺序保留 Python 合同，实际 HTTP 响应通过既有 Compose 维护仓库。
- 确认独立记录原文件全部 9 定义／46 参数已由 `CashIndependentIdentityOriginalTest` 直接断言，另以隔离库核对 23 组完整图。绑定实例摘要及可信范围保持，真实 HTTP 成功／拒绝与现有 Compose 消费、重放和全表回滚均覆盖；草稿合并原 29 定义及维护恢复／发布／HTTP 原断言已迁；完整维护接口已通过逐项合同验收。
- Python oracle：`test_exact_decimal.py`、`test_cash_record_policy.py`、`test_cash_category_management.py`、`test_cash_statement_rows.py`、`test_bank_statement_rows.py`、`test_cash_statement_document.py`、`test_icbc_pdf_statement.py`、`test_cash_source_matching.py`、`test_cash_import_readable_equivalence.py`。
- 完整 `tests/contract` 与 `tests/integration`：HTTP/错误、来源/关系/投影、暂存生命周期、收据/未知结果、修订/权限/回滚、同步/券商及双库矩阵。
- 匿名 fixture：`tests/fixtures/{ibkr,schwab,dfzq,usmart_hk,import_idempotency}`、ccxt/polymarket JSON、现金退款 CSV；其他格式复用测试中的动态匿名文件生成器。
- 原完整常规回归 `scripts/run_backend_tests.py`（SQLite/PG 各 8 片）与独占 `-m performance`。PG 必须人工准备专用 `_test` 库，测试仅接受明确 URL；并行分片不得共库重置。
- Compose `CashDraftInputTest`、`CashCategoryLogicTest`、`ApiContractsTest`、全部受影响 `CashImport*ApiTest`/`CashMaintenanceApiTest`，以及公共 JVM/Android host/iOS/JS/Wasm 目标。用户禁止自动开浏览器，本任务 API/CLI 优先；无 UI 产品变化不安排设备或 UI 演示。
- 兼容比较包含 HTTP status/body、字段省略/null/default、严格类型/重复键/额外字段/大小预算、If-Match/Idempotency-Key、multipart、SSE 和停用入口；不只比较成功 JSON。

严格来源决定输入的原 4 定义／22 参数已由 `CashSourceIssueInputOriginalTest` 直接对应，无数据库依赖。维护验收相关 23 个原文件合计 243 migrated／3 retained、零 pending；保留项为 ORM 未刷写删除状态、历史 Alembic 收据迁移与 Uvicorn 日志宿主，分别执行原 Python 参考及 Native 兼容验证。需求、实现、直接断言及当前证据矩阵见 tasks。6 个维护计划接口验收关闭，不改变其他模块原测试、共享存储／基础持久化、真实账单／R2、两端 UI、PG18 和全迁移的未完成状态。

解析暂存原文件全部 55 定义现为 51 migrated／4 retained、零 pending，对应 135 个已迁参数及 30 个保留参数。新增原生类运行同一存储公共行为和实际内存／R2 条件对象端口；观察器只委托底层读取／写入或注入原故障。原 Python 全文件 165 passed，四个混合宽类型原定义整体保留，不部分计数；Native 对可构造参数及损坏存储分别验证。当前类 37 项和静态检查通过，生产／资源／旧 201 类及构建配置 SHA 不变；旧完整双库和新增测试组合覆盖为 202 套／1,306 项，不宣称新全量命令或真实云验证。清扫原文件已按 8 migrated／1 retained 验收；真实 R2、来源／持久化其他原定义和全迁移仍未完成，详见 tasks。

当前关系上下文原文件的最后一次父流水查询断言已通过真实 SQLite／PG16，按同次已读取的完整父项核对来源，不新增来源查询；原文件 22 定义全部 migrated。清扫原文件为 8 migrated／1 整定义 retained、零 pending；保留的四组配置含 Kotlin `Int` 签名不能接收的 Python `True`，Native 复验全部可表示的原整数拒绝，原 Python 全文件参考继续运行。两文件完整门禁合计 30 migrated／1 retained、零 pending；生产与旧回归 SHA 不变，新增双库方法与完整旧矩阵组合覆盖的准确边界见 tasks。其他持久化、来源、真实 R2／账单和整个迁移仍未完成。

账户别名登记已接入 AccountAliasService／AccountAliasRepository，保留原账户名查询、卡尾与完整账号归一、通用历史命名空间和后端约束差异。66 组真实原 HTTP 与全列别名状态、写后／提交故障及当前权限重验在两库通过；原完整账号别名测试新增 1 定义／2 参数，组合文件其他定义仍 pending。Compose 无此独立旧接口的实际调用者，两端 UI TODO 保留，证据见 [当前审查](tasks.md#账户别名登记接口)。

统一估值纯领域／应用／历史边界的原 10 定义已建立直接 Native 对应，另 230 组真实 Python 差分比较完整数值表示、结果／错误、调用和预算。行情提供器原 17 定义／17 参数已建立直接 Native 对应，189 组原适配器与 27 组实际 SDK 输出另作补充差分；JVM 通过本机 HTTP 验证下载、代理、JSON 数值与超时。真实外部来源可用性不据此宣称通过，账户余额／财务报表已接入估值和提供器；FX、持仓与同步运行时已接入，HTTP 86/86；剩余原测试、配置和整体迁移验收仍未完成。

账户余额／财务报表新增原 7 定义／32 参数，原应用与当前报表两文件合计 9 migrated／34 参数、零 pending；原 Python 34 passed。Native 直接保留精度、当前正式投影／来源删除、月份、全期流向及未初始化不回退断言，另 67 组真实 Python 应用／调用顺序与双库各 80 组真实 Netty 回包、JDBC 故障和实际财务发布快照验证通过。当前完整双库及既有 Host 消费者通过，见 [实施任务](tasks.md#账户余额查询与财务报表)。没有新页面或虚构客户端消费者。

PDF 解码使用 PDFBox 独立 JVM，现有工行读取已接入；投资文字端口完成匿名普通／加密 PDF 的券商记录、身份和事件对照，完整投资文件分派和导入 HTTP 已通过双库及独立复核。原工具版本和临时密码文件的两定义保留 Python 职责，以实际 JVM 密码管道与故障回归核对新边界；不计入 migrated 数。


调用内正式财务校验缓存已接资金解析和操作规划，按完整财务字段快照仅复用成功，最多 256 项、每次调用独立；原两定义／两参数直接迁移，含语言专有边界的四定义／18 参数保留原 Python 参考与 Native 兼容测试。受影响双库及最终复核状态见 tasks，不据此关闭其余导入组合。


账户默认建议四个原定义／28 个双库参数已建立直接 Native 对应：精确别名、来源范围、当前资格及旧提示；来源范围场景通过真实预览／确认保存默认再核对隔离，建议读取验证全部表不变。其余九个账户映射发布定义／30 参数已通过两库确认、重放、草稿、冻结选择与后置故障整批回滚，最终证据见 [实施任务](tasks.md#账户默认映射发布原合同)。
