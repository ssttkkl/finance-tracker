## 当前范围与证据

A 类；工作树 `unarmed-deer`；比较基线固定为已合入的 `main` 提交 `0b1a86f8e3c6b99b2d2385b551639c2ba3f0737e`，合并后 HEAD `b6e1444b6fd20ffeb77b64b67b02fec967520219`。用户已完成三轮九项决策，并授权先实施后端和数据库、最后前端。用户已授权拉取并合并 `main`；已完成该合并，原有 78 个未提交文件按内容哈希核对完整恢复且无冲突，恢复备份仍保留在 Git stash。该授权不包括提交治理实现、推送、部署或迁移真实数据。Kimi 原型已产出；用户明确授权保留 Compose 当前 UI，仅针对本 change 微调，按需采纳原型交互。2026-10-30 用户确认首版演示；UI 复用审查完成并修复，Hallmark 12 项既有界面问题转入 `fix-cash-ui-audit-findings` 下个 change；Compose 契约增量审查与独立设计复核在启动后被用户取消，未形成结论。治理实现与真实迁移的外部写授权边界不变。

## 1. 思考

- [x] 1.1 显式调用 grill-me → grilling，记录目标、范围、非目标、验收及九项用户决定。
- [x] 1.2 阅读上下文、主规格、词表、活动 changes，调用 openspec-explore；追踪关系消费者、所有写入口及 P01–P26 用户路径。
- [x] 1.3 完成独立路径／领域设计审查，将发现回写 design 和 delta。

## 2. 计划

- [x] 2.1 完成 proposal、五项行为 delta、设计和用户路径矩阵；以无 subtype 的三类关系、共同硬约束及派生业务标签为目标。
- [x] 2.2 明确镜像等价单射、完整退款计入、持久身份锚、按状态预检／按方案验证、迁移阻断和回滚边界。
- [x] 2.3 OpenSpec 严格校验及 artifacts 一致性检查通过。
- [x] 2.4 Kimi K3.1 Pro 经 Paseo 产出首版原型，原 agent `3d06bffe-31b6-42e8-a726-662eaf369df4`、补交接 `33441eb8-b771-448e-a991-b95ca6cbd723`。仓库外原型 `/Users/huangwenlong/.paseo/prototypes/govern-cash-relation-model/`，实际 Chrome 1440×1000／390×844 截图索引为 `screenshots-20261030/index.html`，222 个状态、370 张截图，`source-hashes.json` 记录五个原型源文件版本。原型有退款金额、联合成员、解除恢复、零备选搜索和创建表单缺陷，未视为 QA 通过。用户已审阅并选择保留 Compose UI、按需采纳，实施范围及保留项见 design 第 9 节；不再等待整套原型批准，不再委托 Kimi 修正。

## 3. 任务拆分

- [x] 3.1 将硬约束、金额图、代表／身份、所有写者、存储迁移、协议查询、前端及验证拆为以下可验证步骤。
- [x] 3.2 Cross-platform Impact Check：Web、Native 与共享层均受关系 DTO、业务标签筛选、关系选择影响；Compose 共享层现已切换无关系 subtype、business_label、搜索 kind／明确现金部分及硬约束错误合同；Android／iOS 编译与共享单测已执行，Web 正在首版演示检查，不宣称原生设备 QA 或最终验收通过。

## 4. 构建

- [x] 4.1 先失败测试：无证据退款／负向 refund／investment_out、部分退回、跨家族转账、同账户镜像、非法角色／币种／金额及候选预筛选；实现纯硬约束与发现证明分离。
- [x] 4.2 先失败测试：镜像组成项完整等额及单射、未镜像部分保留、退款全额分配／钻石去重、无完整覆盖父、聚合转账拒绝；实现共同完整图金额基准。
- [x] 4.3 先失败测试：角色／来源／时间／稳定来源排序；代表变化不改净额、公共人工值及 entry ID；实现代表与经济基准分离及身份锚合并／拆分规则。
- [x] 4.4 删除当前关系 subtype 及调用参数；贯通导入、建议、人工新增／解除、草稿转换、重建、手动转账创建／编辑和仓储重验。人工不调用发现证明，发现规则仍保持原覆盖。
- [x] 4.5 实现按事实派生 business_label，贯通投影、详情、筛选树、游标、月份汇总、报表和 Agent 协议；删除旧可写字段，保留历史收据只读与旧计划失效。
- [x] 4.6 先失败迁移测试：合法存量保留、跨账户镜像／部分退款／重复键阻断、历史非生效状态与互斥候选、事务中断／重复执行；实现冻结的迁移与预检，保留关系决定和人工值。
- [x] 4.7 所有 P01–P26 后端合同回归覆盖主线、拒绝、取消无写入、修订失败和适用原键核实；不扩大直接命令协议。
- [x] 4.8 保留现有 Compose UI，切换无关系 subtype／派生 business_label 合同，删除关系子类型选择，按需采纳原型的局部交互；验证完整方案、公共值及错误出口，首版编译／Web 校验后立即演示。首版已获用户确认，后续 UI 复用审查发现已修复，证据见第 5、6 节。

## 5. 审查

当前调度：按最新 `$codebase-degradation-review` 执行独立 `$contract-design-review` 与 `$ui-reuse-review`。后端部分结论以本节末的完整增量终审原文为准；Compose 部分已完成 UI 复用审查并修复发现，独立契约增量审查在启动后被用户取消，未形成结论。旧审查原文保留，不替代本轮增量审查。

- [x] 5.1 当前后端里程碑的独立产品／工程／DX／安全及最终 diff 审查完成，无未关闭 finding；性能失败引出的实现或测试合同改动须重审。
- [x] 5.2 已按 `codebase-degradation-review` 完成当前后端独立 `contract-design-review`，F-01 至 F-12、H-01 至 H-06 已修复并复核；性能修复后重审受影响合同。Compose 的独立 `ui-reuse-review` 已完成并修复发现；Compose 契约增量审查由用户取消，未形成结论。
- [ ] 5.3 原型与 Compose 完成后执行独立设计复核、可见文字必要性检查、范围术语搜索与 Hallmark audit。可见文字必要性检查与范围术语搜索已完成；Hallmark 截图审查已执行（0 critical、5 major、7 minor），12 项均为既有页面问题，按用户决定登记到 `fix-cash-ui-audit-findings`，本轮不修；独立设计复核在启动后被用户取消，尚未完成。

此前设计审查涉及的镜像单射、退款完整分配、身份锚、状态分层迁移、角色表、直接命令边界及冲突主规格已回写 design 和 delta；这些设计结论不替代实现审查。

### Compose UI 审查与修复记录（2026-10-30）

`$codebase-degradation-review` 对 Compose 增量触发 `contract-design-review` 与 `ui-reuse-review`。`ui-reuse-review` 已完成，未发现重复业务组件、万能组件或绕过 token；11 项发现中 9 项已修复，2 项按边界保留：

| 发现 | 处置 |
|---|---|
| `unavailable_reason` 未展示，搜索行全禁用时无原因 | 已修复：搜索区显示后端原因，新增单测 |
| 永久 `relation_*` 约束错误仍提供“重试” | 已修复：永久约束不显示重试，新增单测 |
| 唯一合法现金部分自动采用但单选不显示已选 | 已修复：显示实际生效的锚点／对端，新增单测 |
| 删除子类型后残留 5 个文案键，且“换汇”与“个人购汇”并用 | 已修复：删除 4 语言 20 条；词表统一为“个人购汇” |
| `balance_adjustment` 可经注意项投影进入筛选显示原始代码 | 已修复：映射为“余额调整” |
| `p2p_return` 展示分支当前不可达 | 登记到 `fix-cash-ui-audit-findings`：需要时补充业务标签展示，本轮不新增列 |
| 业务标签 helper 放在详情文件 | 已修复：移至 `CashLedgerDisplay.kt` |
| 镜像／图无效与重新选择共用误导文案 | 已修复：改用中性的“当前关联数据无法核对，请刷新后重试。” |
| 空 `kind` 会作为 `kind=` 发送 | 已修复：空值不发送 |
| Compact 行禁用时仍可点击、无禁用语义 | 保留：既有行为，登记到 `fix-cash-ui-audit-findings` |
| `record_subtype` 显示“流水子类型”与词表“业务细分”不一致 | 已修复：4 语言文案改为“业务细分 / Business subtype” |

Hallmark 截图审查（当前模型无法渲染 PNG，审查者使用 macOS OCR、像素测量和源码核对；阴影、悬停态、动效、对比度未评分）结果：0 critical、5 major、7 minor，均为既有页面问题。用户决定本轮保持已完成 UI，12 项全部登记到新 change `fix-cash-ui-audit-findings`，本轮不修，未清零的 major 不视为设计 QA 通过。

2026-10-30 重新执行的 Compose 独立复核结论：

- 契约增量复核：未发现阻断。发现 1 项中等问题（`relation_endpoint_incomplete`、`relation_group_incomplete`、`relation_group_changed`、`relation_data_invalid`、`relation_mirror_component_duplicate` 未映射且仍显示无效重试）已修复并补测；混合搜索结果逐行原因已修复；QA fixture 残留 `subtype` 已删除；association-targets 文档已补齐 `kind`、`component_choices`、`unavailable_reason`；`roles`/`currency_mode` 在 design 标注为本期不暴露；旧 `economic_type=bank_security_transfer` 死分支已删除。
- 设计复核：未发现 critical 或阻断。M1 `↔` 缺字已改为“与”；M2 一级筛选写死已改为按 `typeOptions` 生成并补单测；M3 主动新增预览已展示双方所选现金部分，建议方案的逐项部分展示登记到 `fix-cash-ui-audit-findings`（需协议扩展）；M4 错误只说明约束类别已回写 design，逐值定位登记到新 change；M5 规格与实现冲突已按 Flow-Back 更新为“账单原文直接展示、不设折叠”，旧 E2E 与设备测试同步。m1 待确认文案、m2 关闭语义、m3 术语与孤儿字符串、m4 逐行原因、m6 按关系类型提示已修复；m5 关联搜索日期／金额校验登记到新 change；未使用的 `CashRelationTreatment` 已删除。
- 设计复核未覆盖阴影、悬停、动效、对比度、焦点和 390/768 视口；Hallmark 的视觉结论将由用户指定的 Kimi K3.1 Pro 在下一个 change 重新执行，本轮不宣称视觉设计 QA 通过。

### 契约设计独立复核原文

独立契约设计复核结论：F-01 至 F-07 的具体发现均已修复并复核，本轮受审范围未发现新增阻断项。全量 SQLite 回归仍有失败及错误，其他交付验证尚未完成；本结论不代表整个 change 的最终审查、QA 或交付完成。

评审者为 `/root/backend_design_review`。截至 2026-10-30 02:23:07（Asia/Shanghai），受审 HEAD 为 `b6e1444b6fd20ffeb77b64b67b02fec967520219`，比较基线固定为 `0b1a86f8e3c6b99b2d2385b551639c2ba3f0737e`，包含当前未提交实现。评审者未修改实现、测试或报告文件，未运行数据库写入测试；本报告由编排方原样落盘。

本轮按最新 `codebase-degradation-review` 调度至独立 `contract-design-review`，已读取两项技能。受审范围为后端存储、领域模型、规划／提交、投影、迁移、公开定位及关联目标搜索。没有新增 UI 实现，因此本轮未调度 `ui-reuse-review`。F-01 至 F-04 沿用此前独立复核结论，本轮重点复核 F-05、F-06、F-07 及其调用链。已删除的旧契约评审规则文件不作为本轮依据。

**F-01：非法自动候选与显式非法决定——已解决。**

已复核候选转换、完整方案、显式选择、最终组校验及保存路径。非法自动候选恢复原图并丢弃，不再生成关系失败项或额外待处理状态；具有完整端点或候选集合但不存在合法完整方案的弱候选同样丢弃，不能通过 `defer` 绕过校验。真正无对端、无候选的开放草稿保持既有合同。显式非法选择以及人工／当前关系请求统一返回稳定业务错误，丢弃告警按一次规划聚合并脱敏。

此前纯内存复现确认，交错聚合缺少完整覆盖父时，发现器产生的局部弱镜像不会进入可保存方案；相互竞争同一端点的显式选择整体拒绝。未发现保存非法必要子集的旁路。

**F-02：旧计划模型版本失效——已解决。**

两类计划共用 `CASH_PLAN_CONTRACT_VERSION = 2`，读取、继续预览、提交和结果核实均检查版本。文件追加、替换、删除后保留的选择上下文也检查同一版本，避免 `plan=None` 时恢复旧候选决定。

永久收据仍优先于计划版本检查；已成功命令可以在旧计划或暂存内容消失后按原幂等键返回原结果，不重新执行历史命令。此前纯内存及契约复核覆盖缺版本、版本 1、当前版本和文件变化前后两种时序。

**F-03：迁移候选方案与失效暂定对侧——已解决。**

revision 53 按完整替代方案叠加当前图校验，以合法组合建立可表示性集合，并要求每个有效变体至少属于一个合法完整方案；不把互斥候选的交叉组合当作必须同时成立的方案。

暂定对侧仅在没有候选记录，或由当前有效候选证明时进入直接方案；有效性包含状态、修订及端点归属。失效暂定对侧不会覆盖其他合法候选，缺端点历史草稿保持原状态。无法表示的有效候选仍阻断迁移。迁移审计已纳入运行时 metadata 与工作区删除生命周期；SQLite 表重建保留已删除关系 ID 的高水位。

**F-04：发现阶段父级校验误拒合法组成项——已解决。**

发现校验保留实际现金部分、账户、类型和退款分配，统一调用 `build_cash_relation_graph`，删除重复的父级金额快照中转。此前纯内存复现确认：消费 -100 分成 -30／-70，退款 +40 分成两份真实 +20，再增加 -30 镜像时，完整图通过，两份真实退款不会因金额相等被折叠。

共同图的程序异常继续传播；仅明确领域错误可用于过滤非法候选。已有回归覆盖 `RuntimeError`、`TypeError` 和普通 `ValueError`，防止程序错误被伪装成业务跳过。

**F-05：稳定来源身份参与代表选择——已解决。**

已沿 `cash_source_order_key`、来源绑定仓储、正式关系上下文、投影重建、导入与维护快照、草稿合并、`FactView`、`RelationService` 和冻结迁移复核。

稳定键复用现有五段来源绑定：来源类型、作用域类型、作用域键、身份类型、身份键；优先来源业务编号，再按账户作用域和确定排序选择。没有新增重复的永久身份字段。独立纯内存检查确认，绑定输入排列不影响结果，分别取最小键后再合并与对全集取最小键一致。

本轮发现的调用链遗漏均已补齐：

- `record_origin` 贯通领域与适配器，`import` 缺绑定时不能再次以裸 UUID 或任意来源文字回退。
- 正式和草稿来源键进入关系上下文、投影摘要及建议依赖；绑定变化可使旧快照失效。
- 草稿快照保留来源键；合并预览合并源、目标的绑定键，与提交时转移绑定一致。
- `FactView` 和发现图保留父流水稳定键；`RelationService` 从现存流水加载时批量读取来源键。
- revision 53 对活动关系和完整候选方案显式检查端点来源身份并报告缺失；运行时多成员图同样要求可证明的身份。

独立纯内存复核确认，合法的“来源业务编号绑定＋目标出现绑定”合并后，与持久绑定全集选出的键一致；发现适配保留来源键；导入记录缺键返回 `projection.source_identity_missing`。此前交换随机记录 ID 改变代表的绕过已关闭。

**F-06：持久对象定位与身份锚——已解决。**

`cash_entries.identity_transaction_id` 与展示代表 `root_transaction_id` 分开维护。reconcile 优先处理包含旧锚的拆分结果；合并旧对象时按锚的经济根资格及既有 entry UUID 选择保留对象；新对象按稳定来源身份选锚。代表变化不再决定旧对象延续或新锚选择。

投影持久化统一生成 `cash-entry:{entry.id}`。纯领域中的 `cash:{root}` 保留为本次构建映射键，不直接作为当前公开定位。列表、详情、证据、当前定位及收据结果均读取已保存的公共 ID。

旧 `cash:{transaction_id}` 仅在读取详情与证据时，通过当前工作区、活动数据集和未删除成员解析当前对象。分类、删除等写操作仍精确校验当前公共 ID 与投影版本，没有把旧引用兼容扩展成自动重放写操作。

revision 52 从属于旧对象的有效根初始化身份锚，非法归属在改表前阻断。独立纯内存复核确认，同一镜像组的平台代表为 2、银行身份锚为 1，改变展示代表后锚仍为 1。契约证据覆盖代表变化后的公开引用、拆分保锚、新锚与代表分离、旧引用读取及删除边界。未把单个“无 ready state 的其他工作区”用例等同于完整隔离矩阵；已同时核查查询限制和现有工作区／数据集边界测试。

**F-07：关联目标搜索与共同合法性不一致——已解决。**

已复核 `cash_manual_associations.py` 的搜索、角色预筛选、现金部分选择和人工新增，以及 `cash_plan_routes.py`、`cash_maintenance_protocol.py`、精确金额方言适配、共同角色规则与最终图。

本轮发现并复核了四处修复：

- 搜索此前将已被生效投资资金关系占用的现金部分标为可选，最终图才拒绝。当前批量读取本页及来源流水的资金占用，任一部分存在 `accepted`、`active` 资金关系时，对整条父流水返回空选择及 `relation_endpoint_occupied`。这一范围与现有资金投影要求父流水独立的限制一致；design H2 和搜索说明已明确，不新增部分银证资金混合投影能力。
- 普通退款两端父流水及所选部分均为零的既有合法例外，此前被 SQL 正负号筛选排除，反向选择也未调整角色。当前 SQL 与 `_ordered_endpoints` 在两个方向保持一致，搜索和人工预览共用方向处理；非零父中的零部分不能借此形成退款，零金额镜像及转账仍不允许。
- 聚合父的 `account_id` 为空，原父账户筛选使合法目标无法命中。当前账户筛选通过实际组成项的 `EXISTS` 在分页前执行。金额筛选沿用来源流水总金额的绝对值，已在 design 明确；不会把部分金额误当父总额。
- GET 搜索此前静默忽略未知查询参数。当前未知或重复参数均返回 `record_input_invalid`，无效 `kind` 返回 422。POST 关系新增仍使用 `StrictInput`；旧 `subtype` 和客户端伪造角色字段均拒绝。

独立纯内存复核确认：双零退款正反向返回正确的 `anchor_component_id`／`target_component_id`；资金占用返回空选择及受控原因；SQLite 和 PostgreSQL 的零金额条件不再恒为假；旧 `subtype` 与伪造 `primary_role` 均得到 `extra_forbidden`。DTO 要求返回页面 `kind`，以及目标的 `component_choices`、`unavailable_reason`，没有丢失新增结果字段。

搜索仅读取当前工作区的现金流水，不引入独立投资事件。现金 `investment_out` 只作为普通退款消费端，不能参与镜像；来源和目标的草稿、删除、手动记录及当前已关联成员边界保持。停用账户历史记录的既有行为未在本轮擅自扩大为新的禁用规则。

分页使用 SQL 筛选后的 `id > cursor` 与 `limit + 1`，新增账户筛选不会在分页后丢弃记录。SQLite 使用 `decimal_compare`，PostgreSQL 使用精确数值比较；未引入浮点金额转换。日期筛选继续按显式时区的自然日边界解释。

搜索提供当前局部可选范围，最终额度、闭包、公共值冲突和最新事实仍由完整预览与提交重验。保留这一区分合理，不应在搜索中复制完整关系规划引擎。聚合父整父资金占用的纯测试及搜索双后端契约已通过。

**保留的设计**

来源绑定是稳定排序事实源；领域构建键与持久公共 ID 分工明确；身份锚、展示代表、公共人工值和成员恢复值承担不同职责，不应机械合并。`business_label` 是可重建查询投影，迁移审计是历史快照，也不应与关系事实混为同一状态。搜索 DTO 的局部可选性与提交时的完整合法性检查具有不同职责，保留分层。

**验证证据与边界**

评审者执行只读检索、调用链核对、`.venv/bin/python -B` 纯内存复核及 `git diff --check`。截至上述快照，diff 检查通过。数据库测试由实现方运行，评审者读取代码与日志核对结果。

| 证据 | 结果及适用范围 |
|---|---|
| `/tmp/govern-core-current.txt` | 180 passed；核心领域与发现回归。 |
| `/tmp/govern-identity-final-dual.txt` | 347 passed、1 skipped；F-05／F-06 最终 SQLite／PostgreSQL 契约与迁移验证。唯一跳过项是 PostgreSQL 不适用的 SQLite 表重建身份高水位检查。 |
| `/tmp/govern-search-review-dual.txt` | 55 passed；F-07 修复后的最终 SQLite／PostgreSQL 搜索契约及聚合资金占用纯测试。 |
| `/tmp/govern-query-new-ref.txt` | 66 passed、7 skipped；公开定位查询断言更新后的验证。跳过项不计通过，不据此单独宣称查询全套双后端通过。 |
| `/tmp/govern-search-writes-dual.txt` | 217 passed；搜索、查询及提交相关双后端验证，早于 F-07 最后修复；搜索受影响结论以上述最终日志为准。 |
| `/tmp/govern-source-entry-dual.txt` | 320 passed；较早来源身份、entry、草稿合并及业务规划验证，最终受影响结论以最后一轮为准。 |
| `/tmp/govern-missing-source-{red,green}.txt` | 缺来源身份迁移回归的失败→通过证据。 |
| `/tmp/govern-existing-source-{red,green}.txt` | `RelationService` 正式来源键读取的失败→通过证据。 |
| `/tmp/govern-search-review-red.txt`、`/tmp/govern-search-review-sqlite.txt` | 搜索补审问题的失败→通过证据；SQLite 轮为 27 passed、27 skipped，双后端结论以最终 55 passed 为准。 |

此前 F-01 至 F-04 的独立复核证据仍保留其原范围，包括计划版本、候选完整方案、发现适配及迁移审计测试；不以旧日志替代本轮受影响验证。旧 `/tmp/govern-entry-scope.txt` 含错误异常预期导致的失败，不作为通过证据。

`/tmp/govern-backend-sqlite-full.txt` 当前包含失败及错误，不能记录为全量回归通过。实现方正在处理历史迁移测试夹具等问题；其修复和重跑结果不属于本次已完成的独立复核结论。

尚未完成或不属于本轮结论的范围：

- revision 53 全量迁移保真及差异核对，包括公共人工值、成员恢复值、来源绑定、分币种总额、余额和月份归属。
- 全量回归失败修复后的独立补审与重跑，以及最终相称构建／类型检查、性能预算、安全专项和完整范围审查。
- Agent 协议、词表、功能地图和文档收尾。
- 原型确认后的 Compose 切换、Web 真实冒烟及适用原生编译／单元测试。

F-01 至 F-07 可以记录为已修复并复核；整个第 5 阶段与 change 仍须完成剩余范围审查及其他适用门禁。

### 历史迁移增量独立复核原文

历史迁移测试增量审查结论：通过。H-01–H-03 均已修复并独立复核关闭，本轮范围无未关闭 finding；不代表整个 change 或第 5 阶段完成。

审查时间：2026-10-30 02:43 Asia/Shanghai。HEAD：`b6e1444b6fd20ffeb77b64b67b02fec967520219`；固定比较基线：`0b1a86f8e3c6b99b2d2385b551639c2ba3f0737e`。审查包含当前未提交改动。

范围为 `tests/historical_cash_schema.py`、`tests/cash_relation_fixtures.py`，以及 account commands、cash draft storage、draft projection storage、source storage、source document storage、attention、relation storage、entry migration、instance migration、full refund visibility、mutation receipts 中的历史迁移测试。按契约设计评审检查历史 schema、夹具、迁移及当前读取之间的边界；本轮不涉及 UI 复用审查。评审者未修改文件，未执行数据库写入测试。

- **H-01，P2，已关闭：旧 schema 仍使用当前 ORM。** 收据用例原先仅升级至 revision 40，便调用当前 `CashMutationRepository.create` 写入，继续依赖当前 ORM 与历史表结构偶然一致。现已通过反射表写入收据及结果，检查拒绝降级后的完整内容不变；升级 head 后才使用当前仓储核对查询、幂等重放及结果明细。边界与保真断言均已复核。
- **H-02，P2，已关闭：全额退款迁移移除了旧游标失效覆盖。** 现已在 revision 47 使用纯编码函数和旧发布版本构造游标，不查询当前 ORM；保留 revision 48 发布版本精确增加一次的断言，升级 head 并显式重建后验证旧游标返回 `ProjectionUpdatedError`。活动及 staging 数据集、未受影响工作区、未就绪状态、零额可见性和失败回滚断言均保留。
- **H-03，P2，已关闭：公共历史夹具使非空字段保真覆盖退化。** 新夹具最初未保留原有非空分类、交易对方和账单原文，导致相关全行比较只能验证空值。现已反射创建合成分类，为来源及投影写入非空 `category_id`、`counterparty`，为来源写入非空 `source_payload`；根分类使用既有 `__root__`。原有字段及引用比较重新覆盖这些实际值。

历史迁移现在从明确的旧 revision 建库，只执行对应迁移或必要的相邻往返，避免从 head 53 反向构造历史。已核对有损降级拒绝、异常注入及回滚、人工值与成员恢复值、关系候选及引用、账户／issue／实例高水位、活动来源唯一性和删除后重新导入等断言，未发现其他覆盖弱化。`cash_relation_fixtures.py` 仅在测试中用旧 `subtype` 选择合成类型和角色，并在构造当前模型前移除该字段；未恢复生产兼容字段。

revision 53 的 `downgrade()` 仍无条件抛出 `cash_relation_model_downgrade_requires_backup`，未发现测试绕过或放宽该保护。SQLite 使用各测试的临时数据库；PostgreSQL 使用显式配置且名称经验证以 `_test` 结尾的数据库，夹具前后重置并释放连接。该隔离方式适用于现有串行执行，不声明支持多个测试进程共享同一 PostgreSQL 库并发运行。

最终证据为 `/tmp/govern-historical-reviewed-dual.txt`：**53 passed、11 skipped、343 deselected，52.81 秒**。11 项跳过均来自方言专属测试在另一方言上的不适用分支，不计作通过。针对性修复验证 `/tmp/govern-historical-review-fixes.txt` 为 20 passed、1 skipped。评审者核对了日志及当前代码，`git diff --check` 通过。

后续 pre-36 历史测试的调整边界合理，但尚未纳入本轮结论：revision 36 明确不回填历史组成项，且属于会丢弃旧关系的一次性开发迁移。因此字段迁移应验证至所属 revision；缺组成项的 head 拒绝用例宜先到达 revision 52，再对 `52 → 53` 单独验证受控错误及事实、schema、版本不变，不能宣称整个 `pre-36 → head` 链路保真。49／51 往返仍应从真实历史 schema 以反射准备数据，不放宽 53 保护。


新增历史迁移测试增量审查结论：通过。H-04、H-05 均已修复并独立复核关闭；本轮范围无未关闭 finding，不代表整个 change 或第 5 阶段完成。

审查时间：2026-10-30 02:52 Asia/Shanghai。HEAD：b6e1444b6fd20ffeb77b64b67b02fec967520219；固定比较基线：0b1a86f8e3c6b99b2d2385b551639c2ba3f0737e。审查包含当前未提交改动。

范围：tests/contract/test_cash_legacy_receipt_migration.py、test_cash_projection_ownership.py、test_cash_relation_model_migration.py 新增缺组成项用例及其 test_cash_entry_identity_migration.py 共享夹具；tests/test_alembic_migration.py、test_015_migration_backfill.py、test_cash_category_migration.py、test_legacy_wealth_migrations.py、test_multi_currency_migration.py 的本轮修改。按契约设计评审检查迁移范围、历史夹具、保真与失败边界；UI 复用审查不适用。评审者未修改文件、未执行数据库写入测试。

H-04，P2，已关闭：历史夹具仍依赖当前 ORM。旧收据用例在 revision 48 通过 WorkspaceModel 创建跨工作区数据；缺组成项用例继承的 identity_connection 在 revision 51 通过 ensure_workspace 和当前账户、现金流水、组成项模型准备数据，继续依赖当前 ORM 与旧 schema 偶然一致。现已全部改为反射表插入；Session.execute(Table) 仅执行反射表操作。当前模型及仓储查询只在明确升级 head 后使用。既有跨工作区 ID 碰撞、工作区删除隔离、身份锚回填及失败回滚断言保留。

H-05，P2，已关闭：revision 37 的独立降级保护覆盖被 head 53 遮蔽。原测试将备份恢复错误替换为 revision 53 错误后，53 会先拒绝，无法发现37保护退化。现已在真实37阶段直接验证37→36抛出原 restore from backup 错误，再继续head并独立验证 cash_relation_model_downgrade_requires_backup；两个迁移边界分别覆盖，生产保护未修改。

旧字段回填测试限定到对应历史 revision，符合 revision 36 明确不回填历史组成项的一次性开发迁移边界。ICBC 来源更名保留正反往返结果；分类重建保留非空来源原文、指纹、人工备注及引用完整性；50↔51索引迁移以反射准备旧对象并增加全行值比较。多币种用例仍验证账户归并、币种、现金及投资归属、估值和快照，再单独验证37删除退役表。未把pre-36→head描述为受支持的保真迁移链。

归属迁移保留活动数据集、来源、成员和关系的完整快照，以及 missing_pointer、foreign_pointer、staging_pointer、orphan_active、multiple_active、member_dataset、relation_dataset、projection_workspace 八种损坏拒绝；在已删除退役副本并完成部分DDL后注入失败，仍验证数据、schema、版本和SQLite外键状态恢复。自动使用当前runtime的模块级autouse夹具已移除，历史测试不会同时申请另一个重置同一PostgreSQL库的fixture。

旧收据迁移保留真实计数、空明细、敏感旧字段排除、旧文件摘要不占用新上下文、键及ID碰撞、1001行之后的预检失败、实际DROP后回滚、历史结果阻止有损降级、空旧表往返、不同用户同键、跨工作区碰撞及删除隔离。新增53缺组成项用例从52删除组成项后取全表快照，精确验证 cash_relation_migration_relation_endpoint_incompatible、版本仍为52和快照不变，不进行自动修复或伪造组成项。

证据：首轮 /tmp/govern-migration-boundaries-dual.txt 为149 passed、3 skipped，155.31秒；H-04/H-05修复后的受影响完整矩阵 /tmp/govern-historical-h45-dual.txt 为74 passed、3 skipped，78.93秒，覆盖旧收据、identity全文件、relation model全文件及legacy wealth。3项跳过为SQLite畸形存储注入两项和SQLite高水位一项在PostgreSQL上的不适用分支，不计作通过。评审者核对当前代码及日志；git diff --check通过。其余完整回归、后端写接口契约复核、文档及前端交付不在本轮结论内。


### 旧字段与来源接线独立复核原文

F08 增量契约审查结论：通过，F-08 已修复并独立复核关闭。本结论仅覆盖已删除关系字段的运行时输入边界，不代表整个 change 或第 5 阶段完成。

审查日期：2026-10-30。HEAD：`b6e1444b6fd20ffeb77b64b67b02fec967520219`；固定比较基线：`0b1a86f8e3c6b99b2d2385b551639c2ba3f0737e`。审查包含当前未提交改动。按契约设计评审沿运行时装配、应用入口、关系仓储、导入决定、缓存解码及测试核对；不涉及 UI 复用审查。评审者未修改实现或正式 artifact，也未运行数据库测试。

**F-08，P2，已关闭：已删除的关系 `subtype` 在仍挂载的字典入口被静默接受。** 原先 HTTP 严格请求和当前关系仓储已拒绝该字段，但 `CashLedgerCommandService`、旧关系仓储、`RelationService` 导入决定与缓存路径、`StatementImportService` 仍可接受它，令同一删除合同在不同入口产生不同结果。修复对字段存在性做校验，覆盖 `None`、空串及非空值，不以真假值判断；流水自身的 `record_subtype` 保持独立语义。

`CashLedgerCommandService.add_relation/update_relation`、`RelationalRelationRepository.add` 和 `RelationService.supersede` 在事务或端点读取之前返回 `relation_data_invalid`。共享的决定校验在两个 apply 入口、两个决定匹配器，以及旧导入主入口和决定应用助手中执行，完整检查批次，在 skipped、rejected、ignored 分支之前返回 `import_relation_candidate_invalid`。替换关系入口的校验也先于旧关系状态更新，避免先释放旧关系再发现无效替代输入。

缓存结构校验同时放在旧导入主入口、缓存上下文预检和实际 proposal 解码处，存在已删除字段时返回 `import_relation_reconfirmation_required`。因此空输入、全部重复或跳过、禁用关系检查等路径不会再绕过缓存校验；拒绝发生在文件读取、外部上下文读取或事实查询之前。未通过恢复旧字段或迁移兼容分支解决问题。

`tests/test_cash_relation_removed_fields.py` 覆盖上述调用边界、批次首项 skipped 后次项无效、接受／拒绝／跳过／忽略状态、开启／关闭关系检查、缓存解码及上下文预检。双后端用例对 `Base.metadata.sorted_tables` 的每张表建立完整快照，捕获各入口的拒绝异常后显式 `commit()`，再检查快照完全相同，证明无部分写入，且不依赖回滚掩盖副作用。

证据：`/tmp/govern-removed-fields-red.txt` 为 62 failed；补充缓存边界红测 `/tmp/govern-removed-cache-red.txt` 为 9 failed、62 passed；早期绿测 `/tmp/govern-removed-fields-green.txt` 为 93 passed。`/tmp/govern-runtime-final-dual.txt` 为 222 passed、1 failed，其中 F08 双后端快照用例通过；唯一失败为聚合退款用例，不能将此日志描述为整套通过。对应补跑 `/tmp/govern-aggregate-refund.txt` 为 8 passed。替换入口红测 `/tmp/govern-supersede-oldfield-red.txt` 为 3 failed；后续 `/tmp/govern-runtime-current-dual.txt` 为 267 passed，75.38 秒。以上为实现方运行结果，评审者核对日志与当前代码。

本轮无未关闭 finding。来源绑定接线及其投影维护影响另行审查；不将本结论扩展至该范围、Compose 或全量回归。


来源绑定接线增量契约审查结论：通过。F-09 已修复并独立复核关闭，本轮范围无未关闭 finding；不代表整个 change、第 5 阶段或 Compose 实施完成。

审查日期：2026-10-30。HEAD：`b6e1444b6fd20ffeb77b64b67b02fec967520219`；固定比较基线：`0b1a86f8e3c6b99b2d2385b551639c2ba3f0737e`。包含当前未提交改动。范围为 `design.md` 第 10 节、`StatementImportService`、`CashSourceRepository.save_file_occurrence/_save_bindings`、共享 `file_source_keys`、相关来源存储和工行导入契约测试，并沿调用核对旧导入合并、工作单元、来源排序键、关系图与投影维护。仅触发契约设计评审，不涉及 UI 复用审查。评审者只读实现与正式 artifact，未执行数据库测试；仅运行无数据库的 Python 领域探针。

旧解析接口不能证明 `record_id` 是原生业务编号，因此独立的文件出现保存入口有必要。它只将捕获的真实输入内容摘要、`statement-output-v1`、`parser-output` 和解析结果行位置写成 `file_local/file_occurrence`，不生成 `provider_id`、来源账户或页面认证证据。原有行去重合同仍由旧导入合并负责；该绑定只补齐来源定位和确定性排序所需的证据，不宣称能完成新导入计划的跨文件身份识别。`SourceOccurrence` 继续承担当前来源识别的严格合同，两个入口共用键算法和写入原语，边界明确，未为旧路径放宽平台交易号要求。

共享文件键算法保持原有哈希参数和顺序。纯探针对微信、支付宝、工行来源各自有／无来源账户的 6 种组合验证旧新键一致；缺少原生交易号的支付宝及微信 `SourceOccurrence` 仍抛出 `platform_transaction_id_required`。文件位置不包含用户选定的内部账户，账户映射变化不会伪造新来源范围。

保存入口验证目标工作区、活动状态、来源类型及已有独立身份一致性。抽取的 `_save_bindings` 在改变任一别名前检查全部命中绑定的活动归属；同键同父复用，键属于另一活动父记录时拒绝，原父记录删除后允许既有别名改归属。`save_occurrence` 原有来源范围、文档页面冲突和覆盖裁剪校验保留。旧导入在持有工作区写锁的同一事务中，先合并事实，再保存文件出现，随后扫描关系、维护投影并提交；来源冲突会使整个操作回滚，不留下前面已合并的流水。

**F-09，P2，已关闭：纯重复导入新增来源绑定后漏掉投影维护。** 原实现 `StatementImportService` 新增绑定后仅依据 `result.source_changed` 累积维护对象。另一个真实文件含同一重复事实时，事实本身不变，但新增文件别名可能改变 `cash_source_order_key` 的最小键和同时间同来源镜像组的展示代表；已发布投影和版本因而与即时重建不同。纯领域探针已展示代表由成员 1 变为成员 2 的例子；工作单元提交没有隐式投影更新兜底。

修复令 `_save_bindings` 返回新增或改归属的布尔值，由文件出现入口透传；旧导入用独立的 `binding_affected_fact_ids` 并入受影响投影集合。它复用现有完整关系闭包维护，对新别名采取保守重建，未增加另一套代表判断逻辑，也未把重复事实计成导入／更新或触发重复关系扫描和余额变化。同文件同位置已绑定时返回 false，不单独触发维护。工行双后端用例先建立并发布真实投影，再导入内容不同但解析事实相同的文件，跟踪且实际执行共同维护函数，验证全部 3 个父记录被维护；再次导入同一文件验证无额外维护。

证据：`/tmp/govern-statement-binding-red.txt` 复现旧导入没有来源绑定；`/tmp/govern-file-binding-red.txt` 复现文件保存入口缺失。来源存储与旧导入矩阵 `/tmp/govern-statement-source-dual.txt` 为 237 passed、75.56 秒，但该结果早于 F09 修复，不替代修复后验证。`/tmp/govern-binding-republish-red.txt` 为 1 failed、1 方言跳过，复现纯重复新文件没有调用投影维护；修复后的 `/tmp/govern-binding-republish-dual.txt` 为 **135 passed、56.80 秒**。评审者核对了日志、实际维护调用和同文件幂等断言；`git diff --check` 通过。

本结论限于上述存储／领域／应用接线及受影响契约测试，不代替最终全后端回归、性能检查、文档收尾或用户确认后的 Compose 验证。旧解析器无法辨认原生编号与合成编号的限制仍然存在，已由设计明确为文件出现证据，不在本轮扩大解析器重构。


### 迁移保真独立复核原文

迁移保真增量契约审查结论：通过，H-06 已修复并独立复核关闭。本轮无未关闭 finding，不代表整个 change 或第 5 阶段完成。

审查日期：2026-10-30。HEAD：`b6e1444b6fd20ffeb77b64b67b02fec967520219`；固定比较基线：`0b1a86f8e3c6b99b2d2385b551639c2ba3f0737e`。包含当前未提交改动。按 `$codebase-degradation-review → $contract-design-review` 核对新增迁移保真用例，并沿历史夹具、52／53 迁移、公共收支对象协调、投影重建和查询 DTO 检查；不触发 UI 复用审查。评审者未修改实现或 artifact，未运行数据库测试。

**H-06，P2，已关闭：人工分类清空与成员恢复值的夹具不能检出来源覆盖。** 原夹具中来源分类和公共分类均为 `None`，成员恢复值也等于来源值，错误回填来源仍可通过。现已设置有效非空来源分类，公共分类保持显式清空，并检查 DTO `category` 和存储 `category_id`；成员恢复备注与来源备注不同，且包含显式空串。迁移和首次重建后均比较完整成员表，能够检出覆盖或丢失。公共备注清空、非空人工交易对方及人工字段标记也保留验证。

新增用例从真实 revision 52 出发，以反射表准备历史数据，升级 53 后才调用当前应用服务。父流水、组成项、来源绑定、成员恢复值、余额快照和候选表在迁移及首次重建前后完整相同。关系除删除的 `subtype` 和允许规范化的 `applied_amount` 外完整相同；后者另精确断言为 `{1: 30, 2: 30, 3: 100, 4: 30, 5: 0}`。

退款镜像组净支出为 `-70 CNY`，展示代表由成员 1 改为支付宝成员 3，月份归属变为二月。完整月度金额映射仅有二月 CNY 支出 `-70`，没有在一月或三月重复计入。换汇保持 `-80 CNY → 10 USD` 两侧金额及币种，业务标签为 `currency_exchange`，经济类型为 `internal_transfer`。公共对象 ID 保持，身份锚仍为成员 1，展示根为成员 3；第二次重建再次验证公共对象 ID 稳定。

最终证据 `/tmp/govern-migration-preservation-final-dual.txt`：**21 passed、1 skipped，23.55 秒**。跳过项为 PostgreSQL 下不适用的 SQLite 身份高水位专项，不计为通过。`git diff --check` 通过。本用例覆盖选定的迁移保真场景；第二次重建仅验证身份稳定，不应描述为第二轮完整字段保真检查。

固定关系 mutation 性能用例采用 `RelationService.accept` 的 **draft → confirmed** 替代旧 subtype 更新是合理的。三类关系在当前端点类型和金额符号约束下没有可用于原端点合法改 kind 的交集；确认现有草稿则会实际更新关系、验证完整图、维护投影并提交。当前实现保留 accepted add、cancel、dissolve，计时外准备草稿，计时覆盖完整 `accept`，计时后验证状态、修订增长及两成员合组。10,000 笔背景、3 次预热、20 次采样和 SQLite／PostgreSQL **100 ms** 门禁保持；测量项改称 `confirm`，场景摘要更新。该结论认可测试设计，尚不声明性能成绩通过，也不将新场景与旧 subtype 更新视为同一历史测量。

### 完整后端增量终审原文

后端增量终审结论：通过，当前受审后端范围无未关闭 finding。F-10、F-11、F-12 均已修复并独立复核关闭；最终性能修复和分页测试修正也已完成增量复核及受影响验证。本结论覆盖当前后端里程碑：tasks 5.1／5.2 的后端独立审查可关闭，性能原失败已修复或在原预算复验通过；不据此关闭整个第 5 阶段、前端 QA 或发布。Compose 未实施，其审查与验证仍待用户确认指定原型后进行。

审查日期：2026-10-30。HEAD：`b6e1444b6fd20ffeb77b64b67b02fec967520219`。固定比较基线：`0b1a86f8e3c6b99b2d2385b551639c2ba3f0737e`。包含当前未提交及未跟踪后端、迁移、测试和 change artifact，不以 tracked diff 代替完整范围。评审者未修改实现或正式 artifact，未执行数据库测试；下列数据库结果来自实施方执行日志，评审者只读核对。

按 `$codebase-degradation-review → $contract-design-review` 执行，并覆盖第 5a 的产品／范围、工程、DX、安全及最终 diff 视角。已读取 proposal、design、五份 delta 和 P01–P26 路径矩阵；沿关系事实、来源绑定、公共收支对象、投影、计划及永久收据追踪存储、模型、接口、调用方和测试。未新增 Compose 实现，本轮不执行 UI 复用或可视设计审查。

审查范围与跨层结论

- P01–P05：当前导入的来源身份、规划、保留选择、最终提交及结果核实，以及旧 `StatementImportService`／`RelationService` 调用链。非法自动候选与用户明确非法决定保持不同处置，编程异常不伪装待确认；流水、关系、公共值、投影和永久收据继续在统一事务中提交。新增绑定造成的代表变化现已贯通两条导入路径。
- P06–P17：当前对象及原始来源定位、已保存建议、完整候选方案、接受／拒绝／解除、主动搜索、人工现金部分选择、公共值冲突及恢复。人工确认不再读取发现证明；搜索按共同角色提供可选现金部分，分页前执行账户／金额筛选，投资资金占用按父流水处理，零额退款例外与共同校验一致。联合改选和一键替换没有被扩为新的后端承诺。
- P18–P21：公共编辑、草稿补全及身份合并、来源采用、手工记账／转账创建和双端编辑、单笔及批量删除。来源绑定随明确草稿合并转移，快照同步新排序键；删除沿受影响闭包清理当前关系、候选、资金引用及来源绑定。迁移审计不随单条关系删除，但随所属工作区删除；未发现新增悬空关系或工作区清理遗漏。
- P22–P26：列表、当前详情、来源证据、过滤选项、月份汇总、批量分类、报告、余额、旧来源引用、重建、账户别名和独立投资资金消费者。`business_label` 已贯通存储、领域、DTO 与 HTTP；公开定位采用 `cash-entry:{entry.id}`，旧 `cash:{transaction_id}` 仅用于受工作区约束的当前读取解析。余额计算与独立投资资金模型保持原职责；`p2p_return` 是展示标签，本期不新增对应筛选节点。
- 迁移范围：52 身份锚、53 关系模型、冻结领域实现、审计表及高水位、历史 schema 夹具、合法存量保真和失败回滚。52／53 不通过当前 ORM 构造旧 schema；候选按完整替代方案核验，失效暂定对侧不覆盖其他合法选择。F-12 的可执行只读预检及差异核对已补齐并复核；本轮没有执行真实库迁移。

必要设计保留

`identity_transaction_id` 是持久对象身份锚，`root_transaction_id` 是可变展示代表，两者不应合并。来源事实、公共人工字段和成员独立恢复值具有不同写入者及生命周期；保留明确清空和拆分恢复是必要合同，不能通过回写来源消除模型。`business_label` 是可重建查询投影，旧 subtype 审计是不可写历史快照；两者均不应恢复为关系合法性的输入。临时计划的 `contract_version=2` 与永久收据分开：旧计划失效，已成功命令仍先读收据，不重执行。搜索 DTO 与最终提交模型的分层用于表达局部可选性和完整财务约束，未发现应合并的新重复接口或模型。

新增 finding 及处置

**F-10，P2，已关闭：当前导入的纯重复新来源绑定遗漏投影维护。**

位置：`cash_import_commit.py` 的 duplicate 分支、`cash_record_writer.py::save`、`cash_sources.py::save_occurrence`。原路径虽保存新绑定，但未把绑定变化加入受影响集合；绑定的稳定顺序可能改变展示代表，导致预览与已发布投影不一致。此问题与此前关闭的旧 `StatementImportService` F-09 分属不同调用路径。

现已由来源仓储返回实际新增／改属绑定的布尔结果，writer 合入 touched，提交只维护现有确认图。新增双后端回归验证新文件别名恰一次发布、同文件后续导入不发布、来源金额和余额快照不变，duplicate 计数及结果保留。design §10 与 delta“已存在流水不重复生成导入关系决定”已同步区分绑定变化与真正 no-op。`/tmp/govern-current-binding-dual.txt`：275 passed，174.62 秒。根因、实现、回归和 artifact 已对齐，关闭。

**F-11，P2，已关闭：关系约束错误未兑现具体原因合同。**

位置：`cash_relation_policy.py::validate_relation_roles`、`cash_import_issues.py`、`cash_plan_routes.py::_plan_failure`，以及两个类型变化消费者。design §7 约定具体账户、币种、金额和角色原因；原实现统一返回 `relation_endpoint_incompatible`，退款不完整／超额／占用／聚合限制还落入“稍后重试”默认说明。用户无法根据错误选择合法方案，无变化重试也不会解决永久约束。

现已按角色合法后依次返回账户、币种及金额原因，并为约定的八项原因提供固定安全说明和 `review_relation` 动作。两个类型变化消费者仅把新 `relation_role_incompatible` 纳入原角色失效处理，账户、币种、金额错误仍整体拒绝。独立纯函数对照验证合法镜像成功，分别改变账户／币种／金额／类型得到对应四项业务码；HTTP 映射保留码、422 和 `retryable=false`，退款及占用错误不再引导无变化重试。新增纯测试覆盖十二项正反及映射断言，实施方报告失败到通过；`/tmp/govern-reason-contract-dual.txt` 已完成 406 passed，290.36 秒；受影响共同校验及消费者双后端回归通过。实现、错误映射和捕获边界已独立复核，关闭。

**F-12，P2，已关闭：迁移前只读预检和差异核对尚未完整兑现校验合同。**

位置：`migrations/versions/20261030_53_cash_relation_model.py::_preflight/upgrade`、`migrations/cash_relation_v3/audit.py`、`scripts/cash_relation_migration_check.py`；design Migration Plan 第 2、3、6 步。原路径没有可运行差异报告，首版工具还遗漏实际发布投影、当前成员归属及非生效关系金额，CLI 缺少安全错误定位，外部报告可以缩减工作区覆盖。这些均已在当前实现补齐。

预检和实际 upgrade 共用冻结校验。报告保存来源／现金部分／绑定／分类／余额快照和关系决定摘要，全状态关系金额先取原值再覆盖允许规范化的 resolved 值；既有成员保护字段另作摘要。每个预期组记录稳定对象及身份锚、展示代表、日期、币种和净额、公共人工字段含明确清空、实际投影展示字段摘要、来源摘要、排序、标签、统计资格、可见性、独立资金引用及关系组成。金额为空、日期为空的 draft 通过独立冻结构建形成预期单项，不被正式关系图忽略。

ready 基线验证所有活动流水恰一次覆盖、当前 entry 成员一致、投影及成员计数一致，金额／币种／经济类型／统计资格保持；允许代表、归属日期和业务标签变化单独列出。未初始化基线明确标记，不能伪称有旧展示核对。verify 重新读取 53 当前发布数据，核对全量保真摘要、每组成员归属、对象／锚／根、完整公开副本及实际统计状态，发布版本必须增加。外部报告经过类型与必要结构验证，报告工作区集合必须与实际现金事实范围完全一致，不能删除整组工作区绕过核对。

CLI 要求显式 `FT_DATABASE_URL`、仓库外报告和不存在的输出文件；SQLite 使用既有文件、只读 URI 和 query_only，PostgreSQL 使用只读事务，不升级或重建。源版本限定 52、verify 目标版本限定 53。业务失败返回白名单码及安全 workspace／对象 references，不打印任意异常正文；错误 JSON／空对象／null／数组和缺字段返回受控错误，不存在的 SQLite 目标不被创建。

新增真实双后端测试覆盖 ready／uninitialized × 迁移／重建／verify，包含手工清空 draft、来源与余额完整保真、允许代表跨月变化及错误旧金额拒绝。两种数据库均通过 CLI 子进程生成预检报告，再由既有迁移和重建入口写入，最后用 CLI verify 读取核对；前后全表快照验证两种 CLI 模式无业务写入。损坏回归分别更改 draft 公共值、已发布备注、非锚成员归属、成员计数、ignored 关系金额、删除 draft 投影及更改来源正文，verify 均必须拒绝；遗漏 report.workspace 也拒绝。数据库本身阻止的统计资格非法值不通过放宽约束伪造测试，verify 仍显式检查该字段。

独立只读复核已确认上述代码及断言责任，纯探针确认 malformed 报告和缺失工作区覆盖受控拒绝。组合回归发现三份历史 fixture 合并后保留过时 projection_count，修复仅把计数改成实际的 2，未放宽预检；最终 `/tmp/govern-migration-final-dual.txt` 已完成 131 passed、12 方言不适用 skipped，141.96 秒，包含当前 F-12 CLI／ready／draft／损坏／报告覆盖及相关历史迁移。实现、artifact 与验证证据一致，关闭 F-12；此前失败组合及首版日志不作为当前通过证据。

全量回归后测试期望增量复核：`test_pending_relation_type_rejects_incompatible_roles_without_creating_history` 将两个负向消费组成的待确认镜像改为退款，失败原因确为角色不合法，期望改成 F-11 的 `relation_role_incompatible` 正确。当前 kind 仍为 payment_mirror、status 仍为 pending_review、expense/expense 角色、修订 1 及人工决定来源不变的断言全部保留；未修改生产逻辑或放宽无写入要求。三个历史夹具仅修正合并后的投影计数。受影响四文件完整 SQLite 补跑 `/tmp/govern-full-regression-fixes.txt` 为 36 passed、21 skipped，28.95 秒；跳过不算通过，相关历史迁移另有已通过双后端组合。

最终查询增量复核：`web_queries.py::_projection_reference_condition` 的旧来源别名读取改为 `CashProjectionModel.id == member.correlate(None).scalar_subquery()`。`uq_cash_projection_members_dataset_cash` 自迁移 11 起保证当前工作区、数据集及流水至多一个成员；标量零行仍不匹配，不使用 LIMIT 隐藏重复。成员查询的工作区、数据集和源未删除条件，以及两个消费者外层的当前数据集、根／全组未删除和适用可维护性条件全部保留。独立 SQLite／PostgreSQL SQL 编译确认子查询不关联外层。代表变化、两条旧别名读取、原文等同、越界引用、删除拒绝及当前发布生命周期已有回归覆盖，本次未新增写入或公共契约。design §6 已准确同步查询选择。

读取组合回归的唯一失败是旧测试把同一创建时间的两条草稿写死为来源数字 ID 降序；改为公开 entry UUID 后该相对顺序不再固定，列表查询本身未因单条别名优化改变。最终测试参数化不同／相同创建时间：前者显式相差一秒并验证 1202 在前，后者从两个当前公开 projection_id 降序生成期望，保留主规格“同创建时间稳定翻页”场景。每页一条、总计十条无重复／遗漏和更换筛选后旧游标失效断言均保留；未放宽分页合同或改生产排序。原组合 168 passed／1 failed 后，全生命周期文件修正版 64 passed，最终同／不同时间参数四项双后端 4 passed；三个结果分开记录。

同轮文档缺口已修复：`docs/import/cash-import-redesign-contracts.md` 已列出身份锚，拆分保留 ID 的规则改为身份锚所在结果；不再把展示代表当成对象身份。词表已定义 `identity_transaction_id` 的内部用途及不得直接展示的边界，迁移审计参考准确说明只读用途、工作区归属与单条关系删除后保留，并链接本次迁移方案。功能地图及主阅读入口明确后端工作树状态、Compose 未切换和未部署，不把代码存在写成客户端可用。合同文档“已有候选按原证明修订、证据校验”的残留表述已改为当前修订、端点状态、共同硬约束及最终图，不重跑来源或时间发现证明，消除与人工确认合同的冲突。当前全部后端 diff 及未跟踪迁移／命令已纳入本轮累计审查；本轮最终复核没有发现新的产品范围、工程、DX、安全或跨层契约 finding。

既有 finding

F-01–F-09、H-01–H-06 沿用已记录的独立关闭结论，本轮未发现回归，不重复计数。F-08 的旧字段拒绝包含捕获异常后显式 commit 的全表不变证明；F-09 限于旧导入来源落盘；H-06 的第二次重建只验证 ID，不扩写为所有字段第二遍相等。必要的迁移保真、历史保护和审计生命周期已在各自报告说明。

安全与事务复核

当前新增查询及来源排序读取均由工作区限定，路由服务由认证上下文构造，计划／收据同时限定操作者和工作区；公开来源别名未成为跨工作区旁路。旧关系写字段在严格 HTTP 输入和直接应用／仓储入口拒绝。诊断保留受限位置、异常类型和代码帧，不格式化异常正文、SQL 参数或局部变量；未发现本增量引入的账号或账单正文日志泄漏。未知提交仍按原幂等键核实，提交后的暂存清理失败不重执行财务写入。本轮安全范围未新增 finding，不等同于全仓安全审计。

验证证据与未覆盖工作

| 证据 | 实际结果与适用范围 |
|---|---|
| `/tmp/govern-runtime-current-dual.txt` | 267 passed；此前 F-08 及运行时相关双后端范围。 |
| `/tmp/govern-binding-republish-dual.txt` | 135 passed；旧导入 F-09 绑定发布修复。 |
| `/tmp/govern-migration-preservation-final-dual.txt` | 21 passed、1 dialect skip；H-06 迁移保真范围。 |
| `/tmp/govern-current-binding-dual.txt` | 275 passed；当前导入 F-10、来源及哈希相关双后端范围。 |
| `/tmp/govern-reason-contract-dual.txt` | 406 passed；F-11 具体原因及受影响消费者双后端回归。 |
| `/tmp/govern-migration-final-dual.txt` | 131 passed、12 方言不适用 skipped；F-12 完整只读命令、ready／draft、保真／损坏／报告范围及相关历史迁移组合。 |
| `/tmp/govern-security-dual.txt` | 133 passed；隔离、诊断、计划版本及收据相关矩阵。 |
| `/tmp/govern-backend-sqlite-second.txt` | 1 failed、4586 passed、1422 skipped、73 deselected、5 subtests passed；不能写成完整回归通过。唯一失败为来源键哈希次数。 |
| `/tmp/govern-source-hash-green.txt` | 110 passed；来源键已改为只计算所请求摘要，定向修复通过，不冒充完整重跑。 |
| `/tmp/govern-backend-sqlite-final.txt` | 4 failed、4608 passed、1434 skipped、73 deselected、5 subtests passed，1427.61 秒。3 个失败对应该进程已加载的旧历史夹具计数，1 个对应 F-11 后过时错误码期望；不改写为全量无失败通过。 |
| `/tmp/govern-full-regression-fixes.txt` | 36 passed、21 skipped，28.95 秒；上述四文件完整 SQLite 补跑，断言调整已独立复核。 |
| `/tmp/govern-paths-final-dual.txt` | 878 passed，1009.91 秒；当前维护路径双后端矩阵。 |
| `/tmp/govern-remaining-paths-dual.txt` | 281 passed，265.80 秒；剩余导入／查询／资金路径双后端矩阵。 |
| `/tmp/govern-performance-final-dual.txt` | 70 passed、3 failed、6046 deselected，868.11 秒；原完整性能矩阵结果保留，三个 PostgreSQL 失败见下文。 |
| `/tmp/govern-reference-query-plan.txt` | 相同查询条件的 PostgreSQL EXPLAIN ANALYZE：旧 IN 7563.116 ms，唯一标量 0.899 ms；只用于定位别名查询根因，不代替端到端预算。 |
| `/tmp/govern-performance-fixes-dual.txt` | 6 passed，70.66 秒；三个原失败场景各在 SQLite／真实 PostgreSQL 原预算定向复验，无预算或样本缩减。 |
| `/tmp/govern-reference-final-dual.txt` | 168 passed、1 failed，146.76 秒；最小生产查询修改后的读取／别名／生命周期／HTTP 双后端组合，唯一失败为旧同时间数字 ID 排序期望。 |
| `/tmp/govern-reference-lifecycle-final-dual.txt` | 64 passed，67.45 秒；修正创建时间的生命周期完整双后端文件，此进程收集的仍是未参数化版本。 |
| `/tmp/govern-pagination-final-dual.txt` | 4 passed，4.76 秒；最终相同／不同创建时间参数 × 双后端均通过，包含原同时间规格场景。 |
| `/tmp/govern-performance-functional-final.txt` | 2 passed；仅受影响性能夹具功能检查，不是计时预算结果。 |
| 当前 `git diff --check` | 通过；不替代测试、编译或审查。 |

性能场景从无效 subtype 编辑改为真实 draft→confirmed 的设计已复核：10k 固定数据、3 次预热、20 样本、100 ms 双后端预算保留；准备草稿在计时外，接受、完整图校验、发布及提交在计时内，断言同一关系修订增加、端点／金额不变、发布版本增加和两项合并。该场景在原完整性能矩阵中通过，功能检查日志不冒充计时预算结果。

原完整性能矩阵的两个旧别名原文读取失败分别为分类 HTTP 8.603 秒超 500 ms、读取原文 p95 7.710 秒超 100 ms。查询计划显示旧 IN 执行先遍历 8500 个投影，并重复 8500 次成员定位；唯一非关联标量使成员定位成为一次 InitPlan，随后按投影主键读取，EXPLAIN 总耗时从 7563.116 ms 降为 0.899 ms。原预算定向双后端复验共 6 passed；其中 PostgreSQL 原文读取 p95 24.07975 ms，分类 HTTP 场景全部原断言通过。没有删减原文内容、数据规模、完整组检查或 SQL 数量约束。

第三个原失败为 PostgreSQL 维护 HTTP 完整预览与提交 p95 106.983708 ms，原预算 100 ms；该路径未改写，定向复验为 59.081125 ms，三次预热、二十样本和既有业务断言保留。报告保留一次超限与原预算复验通过两个事实，不把原因武断归为环境，也不称其由别名查询优化修复。当前无未处置性能失败；后续适用性能检查继续观察维护 HTTP 是否再次接近或超过原预算。未重新运行第二遍全性能矩阵，不宣称全性能矩阵一次零失败。

本报告不代表整个 change、前端验收或发布完成。实施方另行报告构建、compileall、OpenSpec 26 项校验及 212 个本地文档链接检查通过，这些阶段 6／收尾结果由 tasks 维护，本报告未独立重跑。完整 SQLite 原四个失败均有明确归因及受影响文件补跑通过证据，生产代码未因此修改；报告保留原始全量失败和后续补跑范围。最后生产查询修改另有上述双后端读取正确性与原预算性能回归，旧同时间期望修正后覆盖完整生命周期及最终参数场景，不宣称重新运行了一次零失败全量。当前后端功能、迁移及性能证据已收拢，后端里程碑可以按这些准确范围记录完成。

未执行真实数据迁移、部署、治理提交或推送。Compose Web／Android／iOS 的新字段、稳定引用、现金部分选择、筛选状态与错误语义尚待用户确认指定原型后实现；未执行对应编译、浏览器冒烟、Hallmark 或独立设计 QA，不能宣称前端完成或客户端已兼容新合同。

## 6. 测试与 QA

- [x] 6.1 当前后端构建步骤的失败→通过测试及受影响完整契约已收拢，实际命令和结果见本节；性能全矩阵及失败场景定向复验已收拢。
- [x] 6.2 SQLite 与显式专用 `_test` PostgreSQL 运行同一应用服务／迁移矩阵；未配置不计通过。
- [x] 6.3 当前后端完整回归与受影响补跑、构建／编译、性能及安全专项已执行；初次失败与定向复验分别记录，未调整原性能预算。
- [x] 6.4 Compose 共享测试及 Web 构建／真实浏览器代表视口冒烟；Android／iOS 受影响编译／单测；不默认增加原生设备 QA。结果见 `### Compose 最终验证`，未执行原生设备 QA。
- [x] 6.5 当前后端范围 OpenSpec strict、链接／锚点、迁移命令示例及 git diff --check 通过；后续实现改动重跑受影响项。

当前验证：2026-10-30（Asia/Shanghai），HEAD `b6e1444b6fd20ffeb77b64b67b02fec967520219`，固定 `main` 基线 `0b1a86f8e3c6b99b2d2385b551639c2ba3f0737e`。本轮再次 `git fetch origin main`、`git merge --no-edit origin/main` 得到 Already up to date，未提交实现保留。以下结果覆盖当前后端里程碑；原始失败与修复后受影响补跑分别保留。Compose 已实施并完成首轮演示确认，独立契约增量审查与设计复核未完成，整个 change 未验收。

所有 pytest 命令从仓库根运行，前缀 `PYTHONPATH=tests:.:src uv run pytest -q`；双后端显式设置 `FT_TEST_POSTGRES_URL=postgresql+psycopg://postgres@127.0.0.1:55438/ft_govern_relations_test`，串行访问合成专用测试库。方言跳过不计通过。失败先行的具体回归及对应实现见第 5 阶段独立证据；本轮迁移覆盖增补先失败日志为 `/tmp/govern-preflight-coverage-red.txt`。

| 实际命令范围／日志 | 结果与限制 |
|---|---|
| `uv run python scripts/run_backend_tests.py --shard-index 0 --shard-count 1`，`/tmp/govern-backend-sqlite-final.txt` | 完整执行结果为 4 failed、4608 passed、1434 skipped、73 deselected、5 subtests passed，1427.61 秒。3 项为启动时加载的旧夹具条数，1 项为旧角色错误码断言；只修测试，受影响四文件补跑通过，见下一行，不改写原失败结果 |
| `tests/test_cash_ledger_management.py tests/contract/test_cash_entry_migration.py tests/contract/test_cash_full_refund_visibility.py tests/contract/test_cash_instance_migration.py --tb=short`，`/tmp/govern-full-regression-fixes.txt` | 36 passed、21 PostgreSQL／方言不适用跳过，28.95 秒；覆盖上述全部失败及所在文件，未修改生产实现 |
| 同一完整命令，`/tmp/govern-backend-sqlite-second.txt` | 前一快照 1 failed、4586 passed、1422 skipped、73 deselected；来源哈希修复定向 `/tmp/govern-source-hash-green.txt` 为 110 passed |
| `tests/contract/test_cash_relation_model_migration.py tests/contract/test_cash_entry_identity_migration.py tests/contract/test_cash_entry_migration.py tests/contract/test_cash_full_refund_visibility.py tests/contract/test_cash_instance_migration.py tests/contract/test_cash_legacy_receipt_migration.py tests/test_alembic_migration.py --tb=short`，`/tmp/govern-migration-final-dual.txt` | 双后端 131 passed、12 方言不适用跳过，141.96 秒；覆盖真实历史 schema、只读 CLI、已有／未初始化投影、草稿、人工值、允许显示差异、禁止金额差异及异常回滚 |
| `tests/contract/test_cash_mutation_commit_recovery.py tests/contract/test_cash_mutation_plan_publication.py tests/contract/test_cash_mutation_commit.py tests/contract/test_cash_record_maintenance.py tests/contract/test_cash_current_relation_plan.py tests/contract/test_cash_manual_classification.py tests/contract/test_cash_current_relation_delete.py tests/contract/test_cash_current_finance_report.py tests/contract/test_cash_plan_model_version.py tests/contract/test_cash_mutation_receipts.py tests/contract/test_cash_mutation_business_plan.py tests/contract/test_cash_current_relation_commit.py tests/contract/test_cash_attention.py tests/contract/test_cash_draft_merge_commit.py tests/contract/test_cash_import_business_plan.py tests/contract/test_cash_draft_merge_plan.py tests/contract/test_cash_entry_editing.py tests/contract/test_cash_manual_associations.py --tb=short`，`/tmp/govern-paths-final-dual.txt` | 878 passed，1009.91 秒；覆盖维护计划／提交／恢复、当前关联、搜索、公共编辑、草稿合并、分类、统计、导入业务计划 |
| `tests/test_cash_relation_policy.py tests/test_cash_relation_graph.py tests/contract/test_cash_manual_associations.py tests/contract/test_cash_relation_context.py tests/contract/test_cash_current_relation_plan.py tests/contract/test_cash_import_business_plan.py`，`/tmp/govern-reason-contract-dual.txt` | 双后端 406 passed；具体硬约束码与错误出口 |
| 当前导入／来源／哈希，`/tmp/govern-current-binding-dual.txt` | 275 passed；同文件重复不发布，新来源绑定同事务刷新投影且金额不变 |
| 隔离／诊断／模型版本／收据，`/tmp/govern-security-dual.txt` | 133 passed；后续只改迁移预检，安全边界另由 F-12 CLI 合成测试验证 |
| 核心领域／发现 `/tmp/govern-core-current.txt`；身份 `/tmp/govern-identity-final-dual.txt`；搜索 `/tmp/govern-search-review-dual.txt` | 180 passed；347 passed、1 方言跳过；55 passed，受影响最终范围由后续组合核验 |
| `tests/contract/test_cash_import_commit.py tests/contract/test_cash_import_session_api.py tests/contract/test_web_api.py tests/contract/test_cash_draft_balances.py tests/test_cash_investment_funding_relations.py tests/test_transaction_relations_transfer.py tests/contract/test_dual_backend_counterparty_account_transfer_matching.py --tb=short`，`/tmp/govern-remaining-paths-dual.txt` | 双后端 281 passed，265.80 秒；补齐导入／查询／投资资金／余额与别名发现边界 |
| `tests/contract/test_cash_projection_graph_reads.py tests/contract/test_cash_projection_lifecycle_reads.py tests/contract/test_cash_entry_editing.py::test_current_projection_reference_survives_representative_change tests/contract/test_web_api.py tests/test_application_web_queries.py --tb=short`，`/tmp/govern-reference-final-dual.txt` | 168 passed、1 failed，146.76 秒；唯一失败为相同创建时间草稿仍断言旧数字引用顺序，生产分页未修改。生命周期整文件双后端补跑 `/tmp/govern-reference-lifecycle-final-dual.txt` 为 64 passed、67.45 秒；最终新增同时间／不同时间参数覆盖另见下一行 |
| `tests/contract/test_cash_projection_lifecycle_reads.py::test_unknown_dates_paginate_by_stable_created_position --tb=short`，`/tmp/govern-pagination-final-dual.txt` | 双后端 4 passed，4.76 秒；同创建时间按当前公开投影 ID 决胜，不同时间按创建时间排序，逐页无重复／遗漏与过滤游标失效断言保留 |
| `uv build`；`uv run python -m compileall -q src migrations scripts/cash_relation_migration_check.py` | sdist／wheel 成功，日志 `/tmp/govern-build-final.txt`；编译检查通过。仓库未配置 mypy／pyright，不把 compileall 称为静态类型检查 |
| `openspec validate --all --strict`；本地 Markdown 目标检查；`git diff --check` | 26 passed；247 个本地链接目标、49 个锚点无缺失（`/tmp/govern-links-final.txt`）；diff 检查通过 |
| `uv run pytest -q -m performance -s --tb=short`，`/tmp/govern-performance-final-dual.txt` | 显式双后端全性能矩阵 70 passed、3 failed，868.11 秒。两项旧引用原文读取退化至 7.7／8.6 秒，一项 PostgreSQL 维护 HTTP 为 106.98 ms 超出 100 ms；原预算、规模、预热及样本数均未调整 |
| `uv run python /tmp/govern-reference-query-plan.py`，`/tmp/govern-reference-query-plan.txt` | 合成 10K PostgreSQL 执行计划：旧 `IN` 半连接重复扫描 8500 次，7563.116 ms；同条件唯一标量子查询 0.899 ms。只修唯一定位条件，不改变成员校验；三个失败测试的双后端原预算定向补跑 `/tmp/govern-performance-fixes-dual.txt` 为 6 passed、70.66 秒；原文读取 p95 降为 24.08 ms，未改实现的维护 HTTP 复验为 59.08 ms。原矩阵超限仍保留，不归因为已证明的环境原因 |

后端用户路径与回归归属：

| 设计路径 | 测试证据文件（`tests/` 下） |
|---|---|
| P01–P05 导入准备、决定、确认与恢复 | `contract/test_cash_import_session_api.py`、`test_cash_import_business_plan.py`、`test_cash_import_commit.py`、`test_cash_plan_model_version.py` |
| P06–P07 当前对象与建议读取 | `contract/test_web_api.py`、`test_cash_relation_context.py`、`test_cash_current_relation_plan.py`、`test_cash_projection_lifecycle_reads.py` |
| P08–P12 接受、拒绝、暂不处理、新增、单关系改选 | `contract/test_cash_current_relation_commit.py`、`test_cash_manual_associations.py`、`test_cash_mutation_plan_publication.py`；只读取消不写入、失败回滚及旧引用拒绝均在当前计划服务验证 |
| P13、P15 产品操作边界 | 联合改选不可提交、先解除后新增由后续 Compose 原型验收；不制造新的后端原子替换合同 |
| P14、P16–P19 解除、公共冲突、恢复、编辑与草稿 | `contract/test_cash_entry_editing.py`、`test_cash_mutation_commit_recovery.py`、`test_cash_draft_merge_plan.py`、`test_cash_draft_merge_commit.py`、`test_cash_record_maintenance.py` |
| P20–P21 手动转账与删除 | `contract/test_cash_entry_editing.py`、`test_cash_current_relation_delete.py`；`test_cash_ledger_management.py`、`test_transaction_relations_transfer.py` |
| P22–P23 查询、统计与重建 | `contract/test_web_api.py`、`test_cash_current_finance_report.py`、`test_cash_manual_classification.py`、`test_cash_projection_graph_reads.py`；`test_relational_cash_projections.py` |
| P24–P26 别名、投资资金及余额 | `contract/test_dual_backend_counterparty_account_transfer_matching.py`；`test_cash_investment_funding_relations.py`、`test_transaction_relations_transfer.py`；`contract/test_cash_draft_balances.py`、`test_cash_current_finance_report.py` |

表内省略的 `contract/` 前缀继承同单元格首文件，分号之后重新以 `tests/` 为根。这些是后端证据，不替代真实页面操作；当前运行状态以上方命令结果为准。

Compose 共享层已同步新合同，用户已确认首版演示；编译、共享单测、受影响 E2E 与真实 Web 操作已有下述证据，独立契约与设计复核已完成。Hallmark 的视觉结论将用 Kimi K3.1 Pro 在下一个 change 重做，既有 UI 问题转入 `fix-cash-ui-audit-findings`，因此不宣称整体设计 QA 通过。真实财务数据库未迁移。

### Compose 首版演示检查点

2026-10-30，HEAD／基线沿用本页顶部。实现保持现有导航、详情侧板、关联模态容器、导入只读步骤及手动记账表单；删除关系子类型控件，DTO／请求／筛选改用 business_label，人工搜索携 kind，并且只能提交服务端明确给出的 component_choices。具体硬约束错误沿用现有错误区。详情发生日期改为当前投影代表日期，来源成员日期保留。

| 当前命令／证据 | 结果与边界 |
|---|---|
| `cd compose && ./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug :shared:compileKotlinIosArm64` | `/tmp/govern-compose-native-current.txt`，31 秒 BUILD SUCCESSFUL；436 个共享 Host 测试，0 失败／错误／跳过；Android Debug 包与 iOS Arm64 共享编译通过，未执行设备 QA |
| `:shared:testAndroidHostTest --tests '*CashLedgerDisplayTest'`，代表日期回归 | `/tmp/govern-compose-date-red.txt` 先断言失败，再加入当前投影日期消费后在完整 Host 测试转绿；缺日期不回退猜测成员日期 |
| DTO 请求／business_label／明确现金部分回归 | 已删除 subtype 请求先失败再通过；搜索新增字段最初为编译失败，不写作断言失败；明确允许组合、缺字段／失效部分及草稿／占用拒绝已有共享回归 |
| `npm --prefix compose/qa/web run test:node` | `/tmp/govern-compose-node.txt`，3 项通过 |
| `FT_TEST_POSTGRES_URL=postgresql+psycopg://postgres@127.0.0.1:55438/ft_govern_relations_test uv run pytest tests/test_application_web_queries.py -k projection_filter_options_and_business_label_filter_follow_active_visible_data` | `/tmp/govern-p2p-filter-green.txt`，SQLite／真实 PostgreSQL 2 passed、38 deselected；转账退回保留展示，不生成支出下业务标签筛选项；受影响全文件补跑留待最终验证 |

首版 Web 使用 Chrome headless（`--force-renderer-accessibility`）、1440×1000、zh-CN，地址 `http://127.0.0.1:5187/w/cash-web-workspace/`；真实 FastAPI 59626 端口连接仓库外合成 SQLite 账本。登录、账本读取、接受建议→完整成员核对→确认保存、跨类别转账搜索→明确选择→预览→保存已实际操作，未发生 HTTP 4xx／5xx 或 pageerror。两条 -100 镜像保存为 -100，平台代表与稳定公开 ID 已核对；转出 -80 与还款 +80 保存成功。首次浏览器捕获为旧修正前 bundle，最新日期修正后需按下一条证据再次核对，不冒充最新截图。

最新演示构建命令为 `FT_COMPOSE_PREVIEW_PORT=5187 FT_API_PROXY_ORIGIN=http://127.0.0.1:59626 node compose/qa/web/scripts/start-compose-demo.mjs`，`/tmp/govern-compose-demo-current.txt` 记录 2 分 13 秒 BUILD SUCCESSFUL。最新 Chrome 1440×1000 检查由 `/tmp/govern-compose-latest-check.mjs` 进行真实登录、搜索、打开合并详情、主动关联及取消，`/tmp/govern-compose-latest-check.txt` 为 PASS，HTTP／pageerror 均为空；详情确认使用平台代表 20:00，银行来源仍为 21:00。截图 `/tmp/govern-compose-latest-ledger.png`、`/tmp/govern-compose-latest-detail.png`、`/tmp/govern-compose-latest-association.png` 位于仓库外。演示 `/health` 返回 200，端口 5187 保持运行。

演示数据扩充遵循已确认 `grill-me → grilling` 边界：保留 Compose 页面、财务规则和数据授权范围，只增加本机合成数据、账单样例与操作索引。Web／Native／共享层无新增产品代码或合同，本轮不重复原生编译；继续使用上述已验证 Wasm 构建。演示主入口改为 `http://127.0.0.1:5190/w/cash-web-workspace/`，操作索引、下载和 API 工作台为 `http://127.0.0.1:5190/demo/guide`，原 5187 入口保留；二者连接同一真实 FastAPI 59626 和仓库外合成 SQLite 数据库。

| 扩充证据 | 当前结果与边界 |
|---|---|
| `/tmp/govern-compose-api-rich.txt`；`/tmp/govern_compose_rich_seed.py` | 196 条现金流水、24 条现金关系、33 个独立场景；保存后逐场景实际读取、维护计划、候选搜索与完整关系图校验通过；场景清单在临时数据目录 `scenarios.json` |
| `/tmp/govern-compose-rich-api-check.txt`；`/tmp/govern-compose-rich-plan-check.py` | `/tmp/govern-compose-guide-check.txt` 为浏览器 P01–P26 索引与真实 API 工作台 PASS、pageerror 为空；26 个 P 路径均有索引；现金详情、明确部分搜索、分类、资金待处理、报告实际读取 200；支付宝／微信 ready、工行 PDF password_required、错误文件 failed；映射后只读导入计划 4 条正式记录、1 个已发现关系 |
| `/tmp/govern-compose-rich-finance-check.py` | 部分／全额／多笔退款为 −250／0／−400，红包退回为 −125；普通转账／换汇／跨境标签及完整成员通过真实投影读取；保存前失败／事实变化／过期模式分别返回 503／409／410，正常模式已恢复 |
| `/tmp/govern-compose-rich-details-browser.txt` | Chrome 1440×1000、zh-CN，真实登录、搜索并打开单方案、多方案、联合方案、公共值冲突、解除、组合支付、草稿、来源更新、身份相关、手动消费详情，PASS，pageerror 为空；单／多方案弹窗与取消另实际操作通过 |
| `/tmp/govern-compose-rich-driver.mjs`；`/tmp/govern-compose-rich-api-check.py` | 浏览器上传实际加密 PDF、显示密码入口、提交正确密码并进入账户选择已操作；API 工作台读取资金待处理返回 200。导入完整浏览器保存与未知结果核实仍需补跑，不能将语义树刷新延迟的脚本超时算通过 |

分类 API 的 `path` 字符串与 Compose 对象结构不一致为既有范围外问题。本机响应中间层将分类改成祖先对象，让分类和公共值冲突路径可演示；没有修改生产分类协议，也不宣称分类生产联调通过。演示错误模式在仓库外中间层，保存后响应丢失先实际执行合成事务再丢弃响应，其收据保留；`/tmp/govern-compose-unknown-check.txt` 验证实际保存、原键核实和重复提交返回同一收据通过；计划过期／内容变化仅演示客户端错误合同，不冒充真实竞争修改。当前配对、导入、解除、编辑会改变合成账本；重启可保留或重建合成数据，旧临时账本保留可恢复。截图、样例、数据库及凭据均未写入 Git。

待完成：用户演示反馈、受影响最终浏览器回归及完整错误恢复、独立契约／UI 复用复核、Hallmark audit 与独立设计 QA。当前路径索引和合成数据覆盖不代表 P01–P26 全部分支端到端 QA 通过；无新产品 UI，不因本轮数据扩充扩大原生验证。

### Compose 最终验证

2026-10-30，HEAD `b6e1444b6fd20ffeb77b64b67b02fec967520219`。UI 复用审查修复后重新执行，未执行原生设备 QA。

| 命令／证据 | 结果与边界 |
|---|---|
| `cd compose && ./gradlew :shared:testAndroidHostTest`；`/tmp/govern-compose-design-fix-tests2.txt` | BUILD SUCCESSFUL；440 个共享 Host 测试，0 failed／0 error／0 skipped；覆盖不可用原因、自动选中现金部分、永久约束不重试、新增结构错误码映射与按现有类型生成快捷筛选 |
| `PLAYWRIGHT_CHROME_PATH=... npm --prefix compose/qa/web run test:e2e -- compose-cash-relations.e2e.ts compose-cash-detail-redesign.e2e.ts compose-cash-detail.e2e.ts`；`/tmp/govern-compose-design-fix-e2e-final.txt` | 31 passed；覆盖关系接受／拒绝／缺失／失效／未知结果／联合方案／p2p／解除／组合支付／冲突、详情原文直接展示、读取失败重试与只读成员 |
| `cd compose && ./gradlew :androidApp:assembleDebug :shared:compileKotlinIosArm64`；`/tmp/govern-compose-native-final3.txt` | BUILD SUCCESSFUL；Android Debug 包与 iOS Arm64 共享代码编译通过，未执行设备／模拟器 QA |
| `cd compose && ./gradlew :webApp:wasmJsBrowserDistribution --rerun-tasks`；`/tmp/govern-compose-web-final4.txt` | 完整重编译 BUILD SUCCESSFUL；所有 49 个任务执行，未复用旧 bundle |
| 真实 Chrome 1440×1000、zh-CN，合成演示 `http://127.0.0.1:5190/`；`/tmp/govern-final-smoke4.txt`（最新构建） | 登录、搜索、详情、关系待确认、账单原文直接展示、“与”分隔符无缺字、添加关联三类关系、无 subtype 控件均通过；pageerror 0、HTTP 4xx／5xx 0；控制台仅有既有 wasm memory 弃用提示 |
| 同一演示（前一构建）；`/tmp/govern-final-smoke2.txt` | 转账类型搜索按角色返回 0 行（该源为消费流水，不能作为转账端点）；业务标签筛选控件存在并读取实际选项；无页面错误 |
| 同一演示（前一构建）；`/tmp/govern-final-smoke3.txt` | 未配对转账记录可搜索到 16 个候选，选中后预览按钮可用，无页面错误；单现金部分不显示多余单选，符合现有合同 |
| 真实 API `GET /api/v1/cash-projections` 的 `filter_options` | 内部转账业务标签为 `bank_security_transfer`、`credit_repayment`、`cross_currency_remittance`、`currency_exchange`、`ordinary_transfer`，不含 `balance_adjustment` 或 `p2p_return` |

Hallmark 审查和独立设计复核的边界见第 5 节；未运行的设备 QA、阴影／动效／对比度视觉评估和完整 P01–P26 分支不宣称通过。演示数据、截图、日志和凭据均在本机仓库外。

## 7. 发布

- [x] 7.1 已核对主规格／change、README、导入与关系文档、接口示例、功能地图、词表和 openspec skills；README 与主阅读入口已更新为 Compose 已切换、演示已确认、客户端最终审查与整体 QA 未完成、真实数据未迁移。
- [x] 7.2 当前后端与 Compose 命令、结果、时间、HEAD／基线及遗留风险已记录；停写备份／只读预检／迁移／重建核验／同步切换／回滚步骤见 design 的 Migration Plan。维护 HTTP 首次超限后复验通过，发布观察继续保留该路径延迟；不承诺任意环境都满足预算。Hallmark 的 12 项既有 UI 问题按用户决定留在 `fix-cash-ui-audit-findings`，因此不宣称整体设计 QA 已通过。
- [x] 7.3 用户明确授权完成当前 change 后归档。归档只整理仓库记录，不代表发布授权；治理实现 commit／push／部署／真实迁移和真实数据迁移仍未获授权。视觉 Hallmark 复核按用户决定移至 `fix-cash-ui-audit-findings`。
