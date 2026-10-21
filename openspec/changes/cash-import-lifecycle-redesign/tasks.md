## 1. 思考：范围与事实

- [x] 1.1 显式执行项目 `grill-me → grilling`，将历次已确认决定和本轮授权写入 proposal；无未决业务分支，不重复询问。
- [x] 1.2 使用 `openspec-explore` 阅读项目原则、词表、目标文档、主规格、active changes、ORM、应用服务及测试，完成只读事实勘查。
- [x] 1.3 确认 A 类；记录账户独立命令、关系替换旧合同、新旧写入口和迁移的影响边界。

## 2. 计划：正式契约与切换

- [x] 2.1 创建 proposal、八项能力 delta 与 design；引用主设计及跨层契约，不创建重复产品入口。
- [x] 2.2 记录跨端影响、信息架构、删除的冗余信息、Hallmark 设计方向和自查。
- [x] 2.3 独立复核产品范围、工程、API／数据一致性和安全；修复阻断发现并记录处置。
- [x] 2.4 `openspec validate cash-import-lifecycle-redesign --strict`、文档链接／JSON 与 `git diff --check` 通过后，先提交并推送计划到 `origin/average-snail`。

## 3. 任务拆分与一致性

- [x] 3.1 将八项能力及目标文档场景对应到测试与任务；明确旧 API、CLI／技能、Compose 调用切换表。
- [x] 3.2 准备仓库外依赖、专用 `_test` PostgreSQL 和合成夹具；真实账单仅只读，运行结果去标识化且不进 Git。
- [ ] 3.3 流程／schema／协议变化先更新 artifact；影响验收的业务歧义按具体问题澄清。

## 4. 构建：先失败测试再实现

- [x] 4.1 为统一更新白名单、固定字段、人工清空保护、账号直接覆盖及类型兼容性写失败测试；实现共享领域策略。
- [x] 4.1.1 对照既有组成项合同补共享转正式校验的同方向约束；补建行 `交易地点/附言` 原始备注字段归一，避免新计划遗漏原文。
- [x] 4.2 为父子草稿空值、正式完整性、精度、工作区隔离和组成项归属写失败持久化测试；实现 ORM 与保留正式数据的迁移。
- [ ] 4.3 为当前来源绑定、无编号多重集合、范围不足／名称交叉反例、删除重导及草稿别名写失败测试；实现来源对应。
- [x] 4.3.1 完成单文件来源规划、同文件出现次数、认证证据、合成身份及事务内当前绑定／问题存储，验证两个后端。
- [ ] 4.3.2 新解析元数据接入，多文件共同计划不相加重复次数；真实来源重叠和旧来源保守回填在 4.7–4.9 验收后关闭 4.3。
- [x] 4.3.2.1 接入已确认的微信默认来源后端输入、文件设置命令和旧来源保守读取，验证重叠更新、固定字段冲突及作用域隔离；HTTP／Compose 在 4.11–4.12 切换。
- [x] 4.3.2.2 实现多文件共同来源规划与解析输入组合，覆盖重复上传、可靠身份冲突、无编号等价集合／次数、范围不足及不确定别名；计划发布和联合提交仍在 4.8–4.9。
- [ ] 4.4 为草稿关联、角色矩阵、确认关系保护、类型解除、联合退款钻石与旧闭包写失败测试；实现当前关系与候选结构及组校验。
- [x] 4.4.1 完成新建议证据／持久角色的共享校验、旧新确认图闭包及保守配对提交分组；保留既有特殊关系类型。
- [ ] 4.4.2 完成父记录及组成项关系结构／候选迁移、类型更新调用、联合草稿成员完整性及最终财务图和组保存点接入。
- [x] 4.4.2.1 用唯一当前关系结构替换旧状态／代理列／JSON 候选；增加父锚点、可空组成项、持久角色、决定组及修订和规范候选，验证已有关系／投影引用保留及双后端迁移。
- [ ] 4.4.2.2 接入联合草稿完整性和最终图校验；旧服务过渡 DTO 只映射同一事实源，不成为新接口合同。
- [x] 4.4.2.2.1 实现共享最终图及联合成员快照校验；组成项退款归并保留实际身份，补等额真实分摊、完整钻石、占用、精度、非根资金关联和窄零退款反例。
- [x] 4.4.2.2.2 从相关父流水及完整决定组加载当前上下文，提供计划／提交／维护共用的无写入校验边界；各业务入口接入及组保存点仍在 4.8–4.10 验收。
- [x] 4.4.2.3 接入类型更新后的角色校验、失效解除及旧新闭包，验证不重新搜索和不重读可变文本；补资金关联现金侧、失效候选及剩余图失败的整条回滚。新导入／维护计划在 4.8–4.10 复用，旧固定字段入口统一关闭仍在 4.11。
- [ ] 4.5 为草稿不入统计、唯一当前归属、待确认可见性和局部投影写失败测试；调整投影读写和余额边界。
- [x] 4.5.1 增加草稿单例及可空财务展示、统计资格、待处理计数和稳定排序的领域／存储合同；迁移保留正式派生值及引用，旧缓存标记待重建。
- [ ] 4.5.2 从当前问题及草稿关系派生待确认，接入局部读写、列表筛选／分页及全部统计排除草稿；未知日期仍可定位，待确认不越过显式筛选。
- [x] 4.5.2.1 有界读取当前问题及活动草稿关系，按不同成员汇总；问题实例删除后不复用，局部上下文覆盖独立问题修订。
- [ ] 4.5.2.2 接入父流水种子、草稿来源输入、待处理发布及生命周期摘要，验证没有组成项的草稿及待处理变化。
- [ ] 4.5.2.3 接入可空列表合同、状态筛选、稳定游标和同快照汇总；排除草稿统计，验证未知金额、日期和显式筛选。
- [ ] 4.5.3 校验唯一当前成员及跨工作区归属，移除旧退休副本依赖；保留组成项分配身份、保护人工分类及清空值，并完成余额及局部成本验证。
- [ ] 4.6 为独立账户创建修改、CAS、修订链及幂等删除写失败测试；实现普通账户接口与收据。
- [x] 4.6.1 完成账户版本列／迁移和统一 ORM 修订、稳定 ID、独立 CAS 应用命令、同事务白名单收据及删除引用／旧余额保护。
- [ ] 4.6.2 接入按 ID 的账户 API、CLI／Agent 及 Compose 普通账户表单和连续补偿链，关闭过渡期旧名称写入口。
- [x] 4.6.2.1 切换按稳定 ID 的账户 HTTP 命令，校验严格正文与幂等键，关闭按名称写路径，覆盖权限、并发重试及未知结果恢复。
- [ ] 4.7 为一次解析、不保留原文件、部分文件／行错误、TTL 与权限写失败测试；实现解析会话及临时存储。
- [x] 4.7.1 保留平台原始出现、文件声明及逐行错误，复用既有原生转换语义，校准真实平台表头。
- [x] 4.7.1.1 在同次单行归一中保留白名单来源解释，覆盖平台订单／状态及银行原生摘要／退款标记；仅保留单行证据，不保留候选组、配对置信或生成备注，接入解析暂存兼容旧对象。
- [ ] 4.7.2 实现仅解析内容的暂存、会话修订、文件替换／密码重传及工作区同步边界，关闭旧原文件会话入口时一起验收。
- [x] 4.7.2.1 完成解析 DTO 的单对象存储、内存／R2 条件替换、固定 TTL、取消清理和基础隔离验证。
- [x] 4.7.2.2 接入文件应用服务、工作区锁和消费收据校验；实现无人访问的过期对象有界清扫及部署运行条件，不能只依赖读取时清理。
- [x] 4.7.2.2.1 完成逐文件应用服务、锁外解析／锁内权限及收据重验、替换命名空间和预算隔离。
- [x] 4.7.2.2.2 接入无人访问的过期对象有界清扫、启动／关闭生命周期和运行配置；最终会话 API 与旧原文件入口切换仍在 4.7.2／4.11。
- [x] 4.7.3 银行来源迁入同一解析边界；验证原始出现、来源账户／范围、逐条失败和一次读取。
- [x] 4.7.3.1 迁入建行 XLS 与工银亚洲 UTF-16 表格，复用逐行原生归一并保留重复出现、来源声明和安全失败结果。
- [x] 4.7.3.2 迁入工行信用卡／借记卡 PDF，包含密码重传、一次提取及逐条隔离。
- [x] 4.7.3.2.1 一次读取原生 PDF 表格及范围证据，接入行级本方身份、共享单行分类和默认文件解析；验证金额固定列、密码／坏行隔离、资源清理和五份真实样本。
- [x] 4.7.3.2.2 支持真实信用卡同次导出的连续页面文件：最小认证证据、重复页面定位、共同完整性校验与互斥出现次数；缺段／重叠矛盾保持未知，其他账单继续。
- [ ] 4.8 为不可变计划、预计投影列表、分页／详情、计划替换和发布消费竞争写失败测试；实现计划与目标 DTO。
- [ ] 4.8.1 接入文件级（含零行页）及已存认证页证据的计划／提交前重验，统一计算跨会话汇齐的完整性；不能仅靠旧行覆盖标志，空页元数据的持久化须在该步骤明确。
- [x] 4.8.2 实现已解析行的来源账户组及支付组成项桥接：来源范围隔离、稳定组引用、已有账户修订与币种、同账户合并、未知分摊及逐行错误；不创建账户、不提前写流水。
- [ ] 4.9 为逐行保存点、关系组隔离、缓存回滚、收据同事务、重复键／上下文消费和未知结果写失败测试；实现联合提交。
- [x] 4.9.1 实现同事务收据／逐项结果、用户幂等与会话／编辑上下文消费唯一性；将 PostgreSQL 工作区锁提前到 UoW 入口。
- [ ] 4.9.2 接入完整应用命令、逐行与关系组保存点、结果恢复和收据分页 API；最终切换时迁入并移除旧导入收据。
- [ ] 4.10 为失败项限时派生、子集范围安全、草稿补全／转正式、问题处理与维护重预览写失败测试；实现后续维护 API。
- [ ] 4.11 用 API 权限／结构／安全错误测试驱动 FastAPI 路由切换；CLI、Agent 与技能共用新服务，关闭绕过新合同的旧入口。
- [ ] 4.12 用共享 DTO／ViewModel 测试驱动 Compose 统一列表与一次确认；实现状态标签、按需详情、主页草稿维护及账户独立表单和退出补偿。
- [ ] 4.13 更新演示场景、语义测试 ID、功能地图及相关说明；Web／Android／iOS 覆盖状态按实际证据填写。

## 5. 审查

- [ ] 5.1 独立产品／范围与工程复核：金额、来源身份、关系组、闭包、保存点、并发、迁移及 API 可发现性。
- [ ] 5.2 安全与性能复核：隔离、日志脱敏、原文件清理、受控临时存储、输入上限、无全库重配与有界分页。
- [ ] 5.3 对实际 Compose 调用 `hallmark audit`，覆盖三类窗口及异常状态，修复并重新复核 critical／major。
- [ ] 5.4 最终 diff 复核 artifact 与实现一致性、旧合同残留、遗漏测试和新增依赖；记录 finding 及处置。

## 6. 测试与 QA

- [ ] 6.1 运行受影响与完整 Python 回归；相同 Application Service 在 SQLite 与真实 PostgreSQL 跑合同矩阵，不把 skip 计入通过。
- [ ] 6.2 验证迁移升级前后正式值、来源、关系、余额及删除证据保持；验证可逆条件及拒绝有损降级。
- [ ] 6.3 以 `~/.ft/bills` 在专用测试库验证多来源重叠、重复导入、来源更新及错误恢复；报告只含汇总。
- [ ] 6.4 Compose 共享测试、Web 生产构建及相关 Native 编译；补适用 Native 客户端运行证据。
- [ ] 6.5 真实浏览器覆盖主流程、空／错误／部分完成／未知状态、键盘及 390／768／1440 px；记录 URL、步骤、截图与控制台／网络错误。
- [ ] 6.6 三尺寸 Compose 演示交付用户评审；收到用户确认前不勾选重大 UI 完成。
- [ ] 6.7 `openspec validate --all --strict`、`openspec doctor`、`git diff --check` 及适用性能／安全检查，记录未运行项与补跑条件。

## 7. 发布准备

- [ ] 7.1 每个可验证小步检查 status、diff 和验证后提交推送当前分支；授权不扩展到 PR、合并或部署。
- [ ] 7.2 完成切换矩阵、备份／迁移／回滚条件及观察项；真实数据迁移及部署等待相应授权。
- [ ] 7.3 所有验收及用户演示确认后同步主规格、更新词表并归档；未完成不得提前归档。

## 8. 反思

- [ ] 8.1 记录开发中计划缺口、反复失败及可复用约束；补回 artifacts 和防回归测试。

## 执行证据

- 4.5.2.1 当前待处理输入（2026-10-21，HEAD／比较基线 `a041637`）：缺少有界读取和问题令牌先失败，再实现只读问题 ID／父流水／修订与活动草稿明确端点；按不同成员计数，不扩张候选、不读取正文。新增／修改／清除问题不需要修改父流水也会使局部上下文失效。独立预审 P1 删除重建可复用问题 ID，采用 SQLite `AUTOINCREMENT`、PostgreSQL 原序列关闭；44 迁移保留已有问题、候选及外键，空表重建也恢复高水位，降级保留这个兼容的安全属性。
- 独立实施复核发现 P2 确认或忽略后仍使用旧待处理标记，新增 4 组反例先红，再按最终活动草稿边及独立问题重算预计结果。最终独立 SQLite attention/context 33 passed、33 PostgreSQL cases 明确排除，无新增阻断；另独立验证真正 43→44 升级、问题／候选内容与引用保持及 ID 不复用。未访问真实账单，未把该复核当双后端证据。
- 执行者显式专用 PostgreSQL，以 `FT_REQUIRE_TEST_POSTGRES=1`、`PGOPTIONS='-c lock_timeout=2000 -c statement_timeout=15000'` 执行 `tests/contract/test_cash_attention.py tests/contract/test_cash_relation_context.py tests/contract/test_cash_relation_type_updates.py tests/contract/test_cash_source_storage.py tests/contract/test_cash_draft_projection_storage.py tests/contract/test_account_commands.py tests/contract/test_cash_relation_storage.py tests/test_alembic_migration.py tests/test_runtime_docs.py -q --disable-warnings --maxfail=3 --tb=short` → 341 passed、无 skip（129.11 s），日志 `/tmp/ft-cash-attention-dual.log`。`uv build --out-dir /tmp/ft-cash-attention-dist`、OpenSpec strict、最终范围 diff 及 `git diff --check` 通过；完整回归及性能在后续读取接入后统一补跑，未计为本步已完成。
- Cross-platform Impact Check：本步是共享后端当前上下文和存储身份修正，没有改 Web、Native 或共享 presentation 的页面、公共 DTO、文案或操作；功能地图保留 F-03／F-04／F-05 未切换状态。浏览器及最终 Hallmark 不适用于这个内部小步，来源发布和实际列表仍在 4.5.2.2–4.5.2.3，导入／维护／三端验收仍待后续任务。没有操作真实数据库或执行部署。

- 4.5.1 草稿投影形状（2026-10-21，HEAD／比较基线 `7fd42d2`）：缺少草稿状态及可空展示的纯领域／存储合同先失败，再实现单例、未知值保留、正式统计资格、待处理成员计数及派生确认状态。确认边拒绝草稿端点；正式财务必填由数据库条件约束保留，草稿必须单例、无确认边且无统计资格。发生时间为空时 `sort_at` 使用父创建时间，不把重建时间写作账单日期。统一投影算术使用 80 位上下文，避免 38 位退款在直接父级构建时被调用方精度截断。
- 43 迁移只改变派生形状，保留正式投影主键、金额、分类、成员／关系引用及原始流水，旧缓存标记 `uninitialized` 等发布前显式重建；有草稿或待处理状态拒绝有损降级。旧迁移往返合同继续逐值比较来源及引用，只将该明确的缓存失效状态作为预期差异；升级失败仍须整体回滚并恢复 SQLite 外键。
- 独立工程／财务复核：143 passed、35 PostgreSQL cases 明确排除，无范围内阻断；额外纯探针将调用方 Decimal 精度设为 6，38 位消费退款差仍精确为 −1E−18，并恢复调用方上下文。未访问私人账单、运行性能或 PostgreSQL。执行者显式双后端矩阵 `tests/contract/test_cash_draft_projection_storage.py tests/contract/test_cash_draft_storage.py tests/contract/test_cash_relation_storage.py tests/contract/test_cash_relation_type_updates.py tests/test_cash_projection_migration.py tests/test_alembic_migration.py tests/test_cash_draft_projection.py tests/test_cash_projection.py tests/test_relational_cash_projections.py tests/test_application_cash_projections.py tests/test_cash_relation_graph.py -q --disable-warnings --maxfail=3 --tb=short` → 287 passed、无 skip（78.09 s），日志 `/tmp/ft-cash-draft-projection-dual.log`。账户降级拒绝的 HEAD 断言随 schema 推进更新后，显式双后端 `tests/contract/test_account_commands.py` → 58 passed、无 skip（21.59 s）。
- 一万笔全量修复、普通编辑、新建／类型编辑／删除的性能矩阵在 SQLite、PostgreSQL 共 6 项通过（137.09 s），日志 `/tmp/ft-cash-draft-projection-performance.log`，未在测量时并行运行其他测试或构建。Compose QA 在 `compose/qa/web` 执行 `npm ci` 成功，只安装锁定依赖，未修改 lockfile；实际浏览器及 Native 运行仍按后续任务验收。专用 PostgreSQL、合成夹具与临时解析对象均留在仓库外。
- 首轮完整 SQLite 为 2,318 passed、1 failed，唯一失败是执行进程已加载的旧 schema 版本断言；修正后重跑完整矩阵，沿前述两项旧 Expo 排除 → 2,319 passed、392 skipped、53 deselected、1 warning（323.19 s），日志 `/tmp/ft-cash-draft-projection-final-regression.log`，不能把首轮失败算作通过。`uv build --out-dir /tmp/ft-cash-draft-projection-dist`、OpenSpec strict、最终 diff 及 `git diff --check` 通过。
- Cross-platform Impact Check：本步只提供后端领域／派生表形状，未切换 Web、Native 或共享 presentation 的列表 DTO、页面、文案和操作；功能地图保留未完成入口及三端 TODO。来源与 attention 加载、全部统计／余额边界、当前成员约束及客户端显示仍待 4.5.2–4.5.3、4.11–4.13；新草稿写入口尚未开放，不把形状测试算成最终用户流程或浏览器 QA。

- 4.4.2.3 类型变更维护（2026-10-21，HEAD／比较基线 `b817c7b`）：模块缺失先失败，再从更新前局部上下文核对当前固定财务结构与关系修订，按持久角色保留兼容确认边、系统解除失效边；先验证完整剩余图，再在同一事务发布原组全部成员。不调用发现器，不重读来源提示作为角色证据；现金—投资只校验现金类型及固定方向，不读取或修改投资事件。剩余图非法或投影保存失败时，由调用方事务回滚整条更新，避免任意牺牲其他已有决定。普通编辑已接入，新导入／维护计划复用留在 4.8–4.10。
- 独立工程／财务复核发现 P1 聚合父流水空账户名使类型维护被绕过，及 P2 锚点变化未使候选失效；先以合成 SQLite 反例失败，再改为所有类型变化检查真实父／组成项财务结构，以及分别按候选修订与关系端点／锚点索引标记失效。草稿保留且不重新选配，所属关系修订推进。最终独立 SQLite 功能复核 114 passed、33 deselected，无新增阻断；未运行 PostgreSQL 或性能测试。
- 执行者显式专用双后端矩阵 `tests/contract/test_cash_relation_type_updates.py tests/contract/test_cash_relation_context.py tests/contract/test_cash_relation_storage.py tests/test_cash_ledger_management.py tests/test_cash_investment_funding_relations.py tests/test_cash_relation_graph.py tests/test_cash_relation_policy.py tests/test_cash_projection.py -q --disable-warnings --maxfail=3 --tb=short` → 290 passed、无 skip、1 warning（64.06 s），日志 `/tmp/ft-cash-relation-types-dual.log`。功能地图记录已接入的普通编辑及尚未切换的新导入入口，没有增加页面数或移除跨端 TODO。
- 完整 SQLite 沿前述两项旧 Expo 排除 → 2,288 passed、375 skipped、53 deselected、1 warning（284.65 s），日志 `/tmp/ft-cash-relation-types-regression.log`。随后仅增加普通入口安全业务错误转换、该错误的回滚反例及性能场景调整；最终显式双后端增量 `tests/contract/test_cash_relation_type_updates.py tests/test_cash_ledger_management.py tests/integration/test_web_sqlite.py tests/integration/test_web_postgres.py` 加下述两项性能 → 76 passed、无 skip、1 warning（41.08 s），日志 `/tmp/ft-cash-relation-types-final-incremental.log`。
- 一万笔普通编辑与新建／类型编辑／删除的两项性能在 SQLite、PostgreSQL 共 4 项通过，仍要求各操作 P95 ≤ 100 ms。最初旧性能场景同时修改金额、时间和类型，因新固定字段边界两后端均拒绝，不能记为通过；已将其改为实际交替的类型更新并断言金额保持 −10，保留同一预算。独立复核认可场景，采纳首个正式样本也必须发生类型变化的建议；最终增量包含此修订。`uv build --out-dir /tmp/ft-cash-relation-types-dist`、OpenSpec strict、最终 diff 复核和 `git diff --check` 通过。
- Cross-platform Impact Check：Web 与 Native 的普通编辑通过同一后端取得类型保存结果，共享 presentation 未改页面、导航、控件或文案；类型影响预览、统一新接口、演示及相关三端浏览器／客户端验收仍在 4.10–4.13，不据此声明最终产品流程或 UI QA 完成。本步无 schema／真实数据迁移，最终 Hallmark 随实际 Compose 切换执行。

- 4.4.2.2.2 当前关系上下文（2026-10-21，HEAD／比较基线 `96476fd`）：缺少加载及校验边界先失败，再实现明确父流水、旧确认闭包、完整指定决定组及资金占用的有界读取。不读取来源正文或配对元数据，不沿无关草稿扩张；校验只返回准备结果，不修改 ORM、写关系或发布投影。确认的新边重新发现角色，既有确认边仅重验持久角色；确认后的分配金额由最终图回填。父／关系／组成员／资金占用变化要求刷新，无关流水变化不使局部上下文失效。
- 独立工程／财务复核发现 P1 组成项变更未推进父修订会漏检、P1 联合组可用原样草稿或混合忽略凑齐成员，以及 P2 新草稿端点归属未提前校验；均先补失败回归，再以完整组成项结构令牌、一致的组内最终决定及工作区／归属检查关闭。最终独立 SQLite 功能矩阵 133 passed、50 deselected，无新阻断，未运行 PostgreSQL 或性能测试。执行者显式专用双后端矩阵 `tests/contract/test_cash_relation_context.py tests/contract/test_cash_relation_storage.py tests/contract/test_cash_projection_parity.py tests/contract/test_cash_draft_storage.py tests/test_cash_relation_graph.py tests/test_cash_relation_policy.py tests/test_cash_projection.py -q --disable-warnings --maxfail=3 --tb=short` → 258 passed、无 skip（60.94 s），日志 `/tmp/ft-cash-relation-context-dual.log`。
- 完整 SQLite 沿前述两项旧 Expo 排除 → 2,274 passed、361 skipped、53 deselected、1 warning（280.51 s），日志 `/tmp/ft-cash-relation-context-regression.log`；未配置 PostgreSQL 的 skip 不计入双后端完成证据。`uv build --out-dir /tmp/ft-cash-relation-context-dist`、OpenSpec strict、最终 diff 复核与 `git diff --check` 通过；本步未新增性能预算或修改原闭包查询形状，最终应用性能验收仍待 5.2。
- Cross-platform Impact Check：本步只增加内部共享应用校验和窄查询，不改变 Web、Native 或共享 presentation 的页面、文案和公共 DTO；功能地图继续保留 F-03／F-04／F-05 未切换状态。没有 schema 或真实数据迁移，浏览器与最终 Hallmark 不适用于本内部小步；实际计划／提交／维护、组保存点与客户端验收仍待后续任务。

- 4.4.2.2.1 最终关系图（2026-10-21，HEAD／比较基线 `a36256e`）：模块缺失先失败，再实现活动正式父流水的完整组成项、归属／角色、精确金额、转账独占、退款额度／逻辑消费唯一性、全部资金关联占用及父级图校验；草稿边无财务效果。退款分配保留组成项身份，经确认的完整组成项镜像才提供等价证明；同额真实分摊相加，镜像证据去重，同父多组成项镜像保留全部关系引用且不改变展示方向。联合成员快照验证当前完整集合及修订，但其数据库加载仍在 4.4.2.2.2。
- 独立工程／财务复核发现 P1 非基准来源的部分退款丢分配、P1 零组成项错误抵扣非零父退款、P1 明确零值被当缺省扩大，以及 P2 错误结构泄漏裸异常；均先复现失败再修复，回写 design／表合同，106 项纯定向测试及最终独立复核通过，无新增阻断。缺省分配用内部 `None` 区分，旧零哨兵须由适配层转译；普通零退款只兼容父总额均零的既有合同。最终应用入口和旧数据读入尚未切换，不声称所有旧投影已经消费组成项证明。
- 最终显式专用 PostgreSQL 矩阵 `tests/contract/test_cash_projection_parity.py tests/contract/test_cash_relation_storage.py tests/contract/test_cash_import_dual_backend.py tests/contract/test_dual_backend_icbc_refund_pairing.py tests/test_cash_relation_graph.py tests/test_cash_relation_policy.py tests/test_cash_projection.py tests/test_relational_cash_projections.py tests/test_application_cash_projections.py tests/test_transaction_relations_projection.py -q --disable-warnings --maxfail=3 --tb=short` → 229 passed、无 skip（50.23 s），日志 `/tmp/ft-cash-relation-graph-dual.log`。完整 SQLite 沿前述两项旧 Expo 排除 → 2,255 passed、342 skipped、53 deselected、1 warning（269.72 s），日志 `/tmp/ft-cash-relation-graph-regression.log`。1,001 笔父流水及 1,000 条独立退款边的去标识只读探针生成一个零净额结果，约 0.054 s，仅是当前领域成本观察，不替代最终应用性能验收。
- Cross-platform Impact Check：本步为内部共享财务规则和投影计算，不修改 Web、Native 或共享 presentation 页面／文案／公共 DTO；功能地图记录后端边界，F-03／F-04／F-05 的统一入口与客户端 TODO 仍保留。`uv build --out-dir /tmp/ft-cash-relation-graph-dist`、OpenSpec strict、最终 diff 及 `git diff --check` 通过。无 schema／真实数据迁移；浏览器及最终 Hallmark 不适用于此内部小步，应用接入、完整组加载和保存点在后续任务验收。

- 4.4.2.1 当前关系存储（2026-10-21，HEAD／比较基线 `d0c79b8`）：缺少目标列和候选表的合同先失败，再迁移同一张关系表及所有存储消费者。移除旧状态、排序副本、活动槽、代理列、JSON 候选和取代链；父锚点及端点归属、可空组成项、持久角色、决定组、单调修订和规范候选落库。迁移保留正式财务值、关系 ID、主次展示基准及投影引用；旧非现金端点拒绝并整体回滚，非法候选仅保留当前缺失提示，有损降级拒绝。旧应用 DTO 只是同一事实源的过渡映射，不是新 API。
- 独立复核采纳 P1 主次排序改变投影基准，以及未绑定对侧残留角色、候选指向自己、当前决定来源错误、降级漏验角色四类边界 finding，均补失败回归后关闭。SQLite 实际查询计划又发现闭包及直接关系读取会走工作区状态索引；分别改为两向索引递归和端点／候选 ID 分支，补索引、环、多种子、分片、活动过滤回归。最终独立复核 33 passed、31 项 PostgreSQL 明确排除，另 6 组拓扑探针通过，无范围内阻断；PostgreSQL 索引表达式离线编译及实际建表均已验证。
- 显式专用 PostgreSQL 运行 `tests/contract/test_cash_relation_storage.py tests/contract/test_cash_draft_storage.py tests/contract/test_cash_projection_parity.py tests/contract/test_cash_import_dual_backend.py tests/contract/test_dual_backend_icbc_refund_pairing.py tests/contract/test_dual_backend_counterparty_account_transfer_matching.py tests/test_alembic_migration.py tests/integration/test_web_postgres.py tests/integration/test_web_sqlite.py -q --disable-warnings --maxfail=3 --tb=short` → 164 passed、无 skip、1 warning（57.78 s）。完整 SQLite 沿前述两项旧 Expo 排除 → 2,205 passed、341 skipped、53 deselected、1 warning（271.02 s），日志 `/tmp/ft-current-relations-final-regression.log`。完整回归后只改直接关系查询的局部定位和新增反例，最终显式双后端增量 `tests/contract/test_cash_relation_storage.py tests/test_application_web_queries.py tests/test_application_cash_projection_evidence.py tests/test_cash_projection_performance.py -q -m 'not performance' --disable-warnings --maxfail=2 --tb=short` → 101 passed、无 skip、20 deselected（33.53 s），日志 `/tmp/ft-current-relations-final-functional.log`。
- 性能夹具迁入明确父端点和当前状态后，SQLite 10 项预算全部通过（79.82 s）；随后显式双后端增量中的 20 项性能均通过，覆盖一万笔重建／读取／编辑、关系操作、查询计划及导入成本。该次增量整体为 118 passed、1 failed（240.49 s），唯一失败是修复前的直接关系查询计划，已由前述最终功能矩阵及独立复核关闭，不能把整次失败命令写成通过。日志 `/tmp/ft-current-relations-performance-sqlite.log`、`/tmp/ft-current-relations-final-incremental.log`。`uv build --out-dir /tmp/ft-current-relations-dist`、OpenSpec strict 和最终 diff 空白检查通过。
- Cross-platform Impact Check：本步替换内部存储、查询及旧维护适配，没有改 Web、Native 或共享 presentation 的页面、文案或公共 DTO；功能地图保留 F-03／F-04／F-05 的未切换状态。浏览器及最终 Hallmark 不适用于此内部小步，客户端验收仍待 4.11–4.13。独立事实复核发现父投影会把同额真实分摊误当镜像、角色边界漏掉既有零退款，已先写回 design、表合同及 delta，实施留在 4.4.2.2；联合草稿成员完整性、最终财务图和普通类型更新仍未完成，不据此关闭父任务。

- 4.7.1.1 单行来源解释（2026-10-21，HEAD／比较基线 `4eb1c35`）：白名单模块缺失先失败，再在平台、银行表格及 PDF 的同次原生调用中提取字符串／布尔提示。排除生成备注、金额、分类、散列身份、原始行及跨行配对决定，错误类型提示忽略；暂存拒绝非法白名单对象，旧 JSON 缺字段按空对象兼容。没有新增数据库列或改写原有来源正文。
- 定向纯边界 `tests/test_cash_statement_evidence.py tests/test_cash_statement_rows.py tests/test_bank_statement_rows.py tests/test_icbc_pdf_statement.py tests/test_parsed_import_staging.py tests/test_cash_import_funding.py tests/test_parsed_import_cleanup.py -q --disable-warnings --tb=short` → 279 passed、1 warning。显式专用 PostgreSQL 矩阵含 `tests/contract/test_parsed_cash_import_context.py` 及上述前六项 → 315 passed、无 skip（15.09 s）。完整 SQLite 沿前述两项旧 Expo 排除 → 2,174 passed、311 skipped、53 deselected、1 warning（221.99 s），日志 `/tmp/ft-import-source-explanation-regression.log`。独立只读复核另跑 218 项测试通过，未发现阻断问题；未访问真实账单或 PostgreSQL。
- Cross-platform Impact Check：本步仅扩充后端内部解析 DTO，公共接口、Web、Native 和共享 presentation 均未切换，没有用户可见页面／文案变化；后续关系存储、统一计划和 Compose 入口仍按原任务验收。`uv build --out-dir /tmp/ft-cash-import-explanation-dist`、OpenSpec strict、最终 diff 复核及 `git diff --check` 通过；浏览器及最终 Hallmark 不适用于此内部小步。旧跨行元数据回填／移除留在统一写入口切换，不宣称已完成。

- 4.1.1 共享边界补齐（2026-10-21，HEAD／比较基线 `90b0f81`）：三种汇总相等但方向相抵及建行长附言反例先失败；共享转正式校验拒绝反向组成项，零父金额只允许零项，普通草稿保存不受影响。备注增加合并列原文并优先于分类摘要，保留既有备注优先级与空值回退，不拼接／截断。主设计不变量、转正式 delta 及银行行测试同步更新。
- 独立工程／财务复核 106 passed，另 7 个探针覆盖草稿保存、零项、正负零、精度及入参不变，无阻断。显式专用 PostgreSQL 执行 `tests/contract/test_cash_draft_storage.py tests/contract/test_parsed_cash_import_context.py tests/test_cash_record_policy.py tests/test_bank_statement_rows.py tests/test_cash_import_funding.py tests/test_cash_statement_rows.py tests/test_cash_transaction_components.py tests/test_statement_account_mapping.py -q --disable-warnings --maxfail=3 --tb=short` → 305 passed、无 skip（32.73 s）。完整 SQLite 沿前述命令及两项旧 Expo 排除 → 2,141 passed、311 skipped、53 deselected、1 warning（224.53 s），日志 `/tmp/ft-import-posting-boundaries-regression.log`，未把 backend skip 算双后端通过。
- Cross-platform Impact Check：本步是新解析／统一转正式边界的内部修正，没有当前 Web、Native、共享 presentation 页面／文案变化；F-05 与正式维护入口尚未切换，整体 4.7–4.12 保持未完成，浏览器及最终 Hallmark 不适用于此内部小步。没有 schema 或旧数据改写；最终 diff、OpenSpec strict 及 `uv build --out-dir /tmp/ft-cash-import-posting-dist` 通过。旧负向写路径对混合符号也有遗漏，最终统一入口替换时以共享校验关闭，不能把当前修正声称为旧入口已全部切换。

- 4.8.2 资金桥接（2026-10-21，HEAD／比较基线 `1754efd`）：缺模块先失败，新增纯组合模块及 47 项回归。消费解析行和已有账户快照，不写库、不创建账户。来源作用域与资金组分开；微信默认／命名来源隔离，银行不同完整账号即使尾号相同也分开，信用卡掩码仅文件内分组。缺账户、不可用／修订变化／币种不足只影响相关行；明确补选账户后解除原资金未识别原因。金额未知保持空值，多账户不猜余数，同内部账户可合并，显式金额矛盾不转正式。
- 独立复核发现三个 P2：剥离金额后余额别名未再次归一、格式化完整账号未脱敏、优惠面额误作零流水资金。均先补失败回归，再复用原生基础账户规则、脱敏分隔数字、忽略促销面额；定向及最终增量复核分别 43／47 passed，无新增阻断。另补支付空部分、空括号、信用卡掩码、异常隔离、无效币种伪组、64 部分上限及低 Decimal 精度反例；来源原文和备注保持完整，不引入新的分类规则。
- 最终受影响 `tests/test_cash_import_funding.py tests/test_statement_account_mapping.py tests/test_cash_transaction_components.py tests/test_cash_record_policy.py tests/test_cash_source_scope.py tests/test_bank_statement_rows.py tests/test_cash_statement_rows.py -q --disable-warnings --maxfail=2 --tb=short` → 232 passed、无 skip（7.72 s）。完整 SQLite 回归沿前述同一命令及两项旧 Expo 排除 → 2,137 passed、311 skipped、53 deselected、1 warning（221.82 s），日志 `/tmp/ft-import-funding-regression.log`；未将 backend skip 算双后端通过。本步不改变存储或现有入口，无新增双后端写合同；OpenSpec strict、diff 空白及 `uv build --out-dir /tmp/ft-cash-import-funding-dist` 通过，构建在仓库外。
- 19 份真实账单只读校准，15,198 次来源出现中 50 条按原规则跳过；15,148 条可辨认行在显式合成账户映射后 15,142 条满足正式字段条件，6 条组合支付缺分摊而保留草稿，0 行异常（读取与桥接约 17.87 s）。这不是实际账户选择、导入提交或 6.3 验收，未写真实数据。Cross-platform Impact Check：仅新增未接入口的后端组合能力，Web、Native、共享 presentation 无当前页面／文案变化，浏览器与最终 Hallmark 不适用于本步；F-05、完整计划、逐行落库及关系联合确认仍待完成。下一步保留原组成项方向与建行原文边界的补齐，已登记 4.1.1。

- 4.7.3.2.2 连续页面（2026-10-21，HEAD／比较基线 `b9efb0d`）：新证据字段缺失及缺少共同完整性先失败，再增加来源页证据、原始页行定位、观测集合及局部别名。同会话汇齐认证范围，互斥页段累加真实出现，重复页面不相加；不同下单声明继续走完整范围多重集合。跨会话相同定位可识别重复，不同定位可排除同笔；跨会话集齐、空页持久证据及应用 preflight 接入明确留在 4.8.1，不宣称已实现。
- 独立工程／来源／安全复核发现 P1 当前出现缺少旧记录字段导致批次崩溃、P1 未与已存页摘要比较、P1 零行页面未参与本批冲突。逐项失败回归后修复；`SourceBatchInput` 独立承载所有文件证据，规划显式支持已存证据，保存／草稿合并拒绝矛盾页，时间改变也可按作用域读取。补删除后不留页冲突墓碑的失败反例。持久摘要先裁成所在页再做 JSON 转换，并用按页定位别名索引，避免逐条复制／扫描整份页集合。最终独立矩阵 272 passed，35 项 PostgreSQL 明确排除，无新增阻断；非阻断 CPU 建议已采纳。
- 显式专用 PostgreSQL 最终完整定向 `tests/contract/test_cash_source_storage.py tests/contract/test_parsed_cash_import_context.py tests/test_cash_document_fragments.py tests/test_icbc_pdf_statement.py tests/test_cash_source_batch.py tests/test_cash_source_matching.py tests/test_parsed_import_staging.py -q --disable-warnings --maxfail=3 --tb=short` → 357 passed、无 skip（36.74 s）。之后仅摘要转换次序优化，源存储／连续页／暂存同一双后端增量 → 174 passed、无 skip（21.02 s）。完整 SQLite 回归沿前述命令及两项旧 Expo 排除 → 2,090 passed、311 skipped、53 deselected、1 warning（225.14 s），日志 `/tmp/ft-import-document-fragments-final-regression.log`；收集后的上述优化由增量覆盖，backend skip 不算双后端通过。OpenSpec strict 与 diff 空白检查通过。
- 五份真实 PDF 只读组合 6,458 行，分为四个认证导出；114 页的两份页面文件汇齐后，全部行具有认证完整范围。共同来源计划 4,825 次新增、1,633 行批内重复、0 待处理／冲突（读取及规划约 17.83 s），未写真实账本，不替代 6.3。Cross-platform Impact Check：本步没有 Web、Native 或共享 presentation 页面／文案变化，F-05 入口仍待最终 API 和 Compose 切换；页面数量与覆盖 TODO 不变，浏览器／最终 Hallmark 不适用于内部证据步骤，4.7 整体未完成。

- 4.7.3.2.1 工行 PDF（2026-10-21，HEAD／比较基线 `6204ffa`）：模块缺失先失败，再实现一次打开、逐页原生表格及文件声明读取，逐行复用原生分类；信用卡按行识别完整本方卡号，借记卡仅从文件头识别。金额固定列及精确小数、未知币种／日期／方向、坏行、密码重传、资源预算／关闭均有覆盖；密码仅本次解析使用，文件名先清除密码标记再截断。
- 独立来源／财务／安全复核发现 P1 正文对手卡号误作本方、P2 借记卡整数／一位金额被旧解析拒绝、P2 文件名密码括号及截断泄漏、P2 换行摘要丢失。分别补失败回归再修复，定向重新复核四项关闭、无新增阻断；独立 42 项 PDF 测试及两项密码反例通过，未访问 PostgreSQL 或私人账单。
- 最终显式专用 PostgreSQL 矩阵 `tests/contract/test_parsed_cash_import_context.py tests/contract/test_dual_backend_icbc_refund_pairing.py tests/test_icbc_pdf_statement.py tests/test_parsed_import_staging.py tests/test_cash_statement_rows.py tests/test_bank_statement_rows.py tests/test_cash_source_scope.py tests/test_counterparty_account_attrs.py -q --disable-warnings --maxfail=3 --tb=short` → 275 passed、无 skip（16.44 s）。完整 SQLite 回归沿前述命令及两项已知 Expo 排除 → 2,055 passed、307 skipped、53 deselected、1 warning（224.31 s），日志 `/tmp/ft-import-pdf-rows-regression.log`；未把 backend skip 计为双后端通过。OpenSpec strict、diff 空白检查通过。
- 五份真实 PDF 只读校准共 6,458 次出现，均无行问题；金额／币种／时间的多重集合与旧读取仅 5 条欧元不同，新读取保留真实 EUR，旧读取错误回退 CNY，类型／子类型分布全部一致。3 份具有完整证据，另两份为同次导出的连续页面文件，单份完整性如实保持未知，自动联合认证留在 4.7.3.2.2；未将真实数据写入账本或 Git，不替代 6.3。Cross-platform Impact Check：本步内部解析服务无 Web、Native 或共享 presentation 页面／文案变化，浏览器及最终 Hallmark 审查不适用；最终入口和客户端仍在 4.11–4.13，PDF 父任务与整体 4.7 保持未完成。

- 4.3.2.2 多文件共同来源规划（2026-10-21，HEAD／比较基线 `1fbd359`）：新增领域规划和已解析文件输入组合，保留每个会话行结果；同一文件重复上传不重复写，同来源可信完整范围的等价集合按共同次数处理，不相加重叠次数。不同可靠身份仍各自保留，弱证据不能任意绑定已有单笔；显式空值与省略补丁分开，等价 Decimal／时区值不产生假冲突。旧本方身份未知时，已消耗的文件别名不能证明额外新增。
- 测试先行：缺规划模块先失败；独立复核发现 P1 联合身份只取首行丢失旧目标、P1 冲突组被移除后弱行误新增、P2 同内容结果引用平方膨胀，均补失败反例并修复，定向复核确认关闭、无新增阻断。确定对应保留联合编号／认证证据及已知目标；冲突身份保留碰撞上下文；`SourceBatchPlan` 通过共享 `group_ref` 表达等价结果／候选集合，集合不是逐对身份声明。2,000 × 2 同内容、1,000 × 2 歧义及 1,000 冲突加 1,000 弱行均验证线性结果体积及 2 s 预算。
- 本步最终定向矩阵：显式注入专用 PostgreSQL 环境，`tests/contract/test_cash_source_storage.py tests/contract/test_parsed_cash_import_context.py tests/test_cash_source_batch.py tests/test_cash_source_matching.py tests/test_cash_source_scope.py tests/test_cash_record_policy.py tests/test_parsed_import_staging.py -q --disable-warnings --maxfail=2 --tb=short` → 323 passed、无 skip（30.69 s）；独立纯测试复核 156 passed（0.80 s）。真实六份微信只读共同规划 3,974 行 → 3,540 次新增、410 行批内重复、9 个冲突身份共 24 行、0 待处理，结果 623,245 bytes，约 0.46 s；这些冲突对应样本中已知的不同快照，不按文件顺序覆盖。未写真实账本，不替代 6.3 联合提交验收。
- Cross-platform Impact Check：本步只增加后端领域规划与解析输入组合，未改变当前 HTTP 或 Compose 页面／文案；Web、Native 和共享 presentation 的最终一次确认、结果分组与列表切换仍由 4.8–4.13／6.4–6.6 验收。功能地图保留 F-05 的重构中状态，页面数量和跨端 TODO 不变。父任务 4.3／4.3.2 仍未完成。
- 本步完整 SQLite 回归使用前述同一命令及两项已在旧基线复现的 Expo 断言排除 → 1,999 passed、303 skipped、53 deselected、1 warning（221.90 s）；日志 `/tmp/ft-import-source-batch-regression.log`。未将 backend skip 当成双后端通过。OpenSpec strict 与 diff 空白检查通过；本步无 UI、迁移及外部 API 变更，浏览器／Native／Hallmark 最终审查不适用于本步，整体门禁仍待完成。

- 2026-10-21，起始 `HEAD=5b41517`，比较基线同上；分支 `average-snail`，目标 `origin/average-snail`，用户已授权先推计划再开发。
- 环境：OpenSpec 1.7.0、Node 26.8.1、JDK 21、Xcode 26.3；已通过 `uv sync --group dev` 安装本 checkout Python 依赖；QA 依赖与新实现测试尚未运行。
- PostgreSQL：使用本机 PostgreSQL 16.14，在仓库外独立临时目录启动专用实例并创建 `ft_cash_import_test`；连接信息只保存在 `/tmp/ft-cash-import-test-env.json`，由命令包装器显式注入 `FT_TEST_POSTGRES_URL` 和 `FT_REQUIRE_TEST_POSTGRES=1`。Docker 镜像枚举出现存储 I/O 错误，不依赖该环境完成矩阵。
- Active change 处置：新合同取代 `safe-cross-source-refund-replacement` 的自动替换决定；保留确定性候选及完整图校验的算法证据。其他 active changes 的未完成验证及演示仍保持原状，不随本变更自动勾选。
- 本记录按实际小步持续补充；上述未完成项均不代表已经实现或通过。

- 计划阶段现状基线（尚无实现代码修改）：`uv run pytest tests/test_cash_import_session_service.py tests/test_cash_ledger_management.py -q --disable-warnings --maxfail=2` → 38 passed、5 skipped；随后使用上述显式 PostgreSQL 配置运行 `tests/contract/test_cash_import_dual_backend.py` → 13 passed、无 skip。新行为将另写失败测试，不把当前基线视为新设计验收。
- 计划静态检查：`openspec validate cash-import-lifecycle-redesign --strict` 通过；15 份本次文档的本地链接、围栏、空白及 8 个 JSON 示例解析通过，检查包含未跟踪文件。

- 独立计划复核（2026-10-21）：覆盖产品范围、工程、API／持久化、安全及 active changes；初次发现 P1 旧阻塞合同遗漏、P1 分类保护冲突、P1 旧 delta 回写风险、P2 来源身份与派生数据遗漏、P2 关系提交组不一致。全部采纳，修订八项 delta、design 和五项旧 proposal 回链；定向重新复核已逐项关闭，未遗留 P1/P2。该结论仅针对计划。

- 4.1 范围：新增纯领域 `cash_record_policy.py`，未切换旧应用写入口；38 项单测覆盖固定值等价、整条拒绝、人工清空保护、类型组合、草稿补全、精度及协议结构。原地输入不被修改，错误只携稳定码与字段名。独立复核的三个 P2（嵌套 JSON、已填草稿结构、原始繁体备注）已逐项补失败回归并修复。
- 本小步无数据库或 UI 修改，双后端和浏览器 QA 不适用于纯领域函数；前述现状双后端基线不作为本步新服务验收。所有应用入口接入在 4.2、4.9–4.11 完成，功能地图明确仍为重构中。

- 计划提交：`ec5756b docs: plan cash import lifecycle redesign` 已推送 `origin/average-snail`，随后才新增实现测试和代码。首个领域策略测试先因模块缺失失败；实现后发现草稿金额接受浮点，已收紧协议边界，29 项通过。该策略尚待新服务接入，不宣称旧写入口已完成切换。

- 4.1 提交前验证（HEAD `ec5756b`，基线相同）：`uv run pytest tests/test_cash_record_policy.py tests/test_record_type.py -q --disable-warnings --maxfail=1` → 72 passed；OpenSpec strict 和 diff 空白检查通过。独立定向复核三项发现均关闭。

- 4.2 开发中补计划：旧链漏掉流水到工作区账户的复合外键，新增迁移重申；SQLite 有数据 batch 重建先失败，补显式事务及外键恢复边界，往返比较保留父记录、组成项、关系、投影和账户，故意违反外键时迁移整体回滚且版本不前进。SQLite 部分唯一索引不依赖反射，显式重建删除过滤条件。
- 迁移复核发现运行期 schema 常量未推进，已补失败启动测试并更新为 `20261021_38`。当前双后端草稿／迁移／旧导入矩阵 107 passed、无 skip；完整回归按仓库 README 的 `PYTHONPATH=tests:.:src` 方式执行中（普通启动会使现有 conftest 辅助导入收集失败）。

- 4.2 最终定向验证（HEAD `bdcab8c`，基线相同）：显式配置专用 PostgreSQL 后执行 `PYTHONPATH=tests:.:src uv run pytest tests/contract/test_cash_draft_storage.py tests/test_alembic_migration.py tests/test_fact_field_unify.py tests/test_cash_record_policy.py tests/contract/test_cash_import_dual_backend.py -q --disable-warnings --maxfail=2 --tb=short` → 115 passed、无 skip。两个后端都覆盖升级后启动、草稿空值、正式父记录约束、行修订、跨工作区引用、已填数据迁移往返及拒绝有损降级；SQLite 额外验证外键错误整体回滚和不提交调用方写入。
- 4.2 独立工程复核：schema／迁移／运行时与共享转正式校验分别复核。运行期版本、组成项序号缺失／重复、汇总父账户三项发现均补失败测试并关闭；正式值求和采用 80 位 Decimal 上下文，避免合法 38 位值受默认上下文舍入。该步不暴露草稿 API 或页面，不声称统计过滤和后续维护已完成。
- 完整 SQLite CI 回归 `PYTHONPATH=tests:.:src uv run pytest tests -q -m 'not performance' --disable-warnings --tb=short` → 1548 passed、178 skipped、50 deselected、3 failed（170.97 s）。两项旧 Expo CI 断言在 `bdcab8c` 独立提取基线复现为 2 failed／2 passed，因旧工作流已移除，不在本重构恢复 Expo。另一项文档断言揭示现有文档索引缺少运行失败边界，随 schema 文档更新补齐；随后定向重跑。未将 skip 或上述完整回归计为完整通过。日志在仓库外 `/tmp/ft-import-sqlite-regression.log` 与 `/tmp/ft-import-prechange-legacy-tests.log`。
- 文档边界和旧子类型断言调整后的定向回归：`PYTHONPATH=tests:.:src uv run pytest tests/test_runtime_docs.py tests/test_cash_record_subtype.py tests/test_cash_record_policy.py -q --disable-warnings` → 63 passed；OpenSpec strict、`git diff --check` 通过。本步不含 UI，浏览器、Native 和 Hallmark 最终审查不适用，整体 UI 验收任务仍未完成。
- 4.2 提交 `1d0b62e feat(cash): persist incomplete records as drafts` 已推送当前分支。4.3 勘查覆盖六种现金来源的编号、完整账户、范围和原始备注；在 design 补齐未知范围及混合旧编号风险，先实现来源对应规划和当前绑定／问题存储，再由 4.7 新解析边界提供元数据。未改变来源分类规则，故来源 taxonomy 无新增分类单元；真实样本仍只读且不入库内夹具。

- 4.3.1 测试先行：模块／三表缺失先失败；范围筛选及边界、交叉名称、相同次数、等价更新、删除重导、别名保存点回滚和候选修订均有合成用例。可靠编号与认证证据按全部键及已知目标归并批内冲突，不能局部覆盖。无法确定来源账户时实际碰撞延后，不凭昵称猜账户，也不复制正式流水；正式固定字段冲突不消耗旧候选。
- 4.3.1 独立复核覆盖来源匹配、schema／迁移、当前问题和来源往返：已关闭不同稳定证据误匹配、混合编号候选误去重、文件别名绕过账户、非法索引阻塞整文件、仅候选变化不增修订、缺表启动未拒绝、认证行证据重载丢失及只检查优先证据八类 finding。对应反例先失败再修复；来源证据新增 `stable_evidence_key` 同步跨层表合同，不把内容散列提升为身份。
- 4.3.1 定向矩阵（HEAD `1d0b62e`，基线相同）：显式专用 PostgreSQL，`PYTHONPATH=tests:.:src uv run pytest tests/contract/test_cash_source_storage.py tests/test_cash_source_matching.py tests/test_cash_record_policy.py tests/contract/test_cash_draft_storage.py tests/test_alembic_migration.py tests/contract/test_cash_import_dual_backend.py tests/test_runtime_docs.py -q --disable-warnings --maxfail=2 --tb=short` → 200 passed、无 skip。过程中 PostgreSQL 报字符串 ID／BIGINT 比较错误，已规范化 ID 后重跑通过，未用 SQLite 成功代替该后端证据。随后两个纯匹配保护增量及全部领域策略重跑 → 99 passed。
- 性能：200 个来源出现查询上限为 12 次 SELECT，双后端通过；1,000 笔同秒同额且来源内容不同的完整重复集合初次 2.56 s 超过 2 s 预算，去掉重复范围校验并按来源快照预筛“无需修改”的边后，含该场景的 99 项纯测试合计约 0.1 s。快照预筛仅优化重复比较，不能用作业务身份。
- 完整 SQLite 回归：`PYTHONPATH=tests:.:src uv run pytest tests -q -m 'not performance' --disable-warnings --tb=short --deselect=tests/test_mobile_ci_packaging.py::test_mobile_ci_builds_release_like_artifacts_with_embedded_js --deselect=tests/test_mobile_ci_packaging.py::test_mobile_ci_docs_describe_login_origin_and_standalone_artifacts` → 1613 passed、196 skipped、53 deselected（181.47 s），仅显式排除已在旧基线复现的两项废弃 Expo CI 断言；未把其他 backend skip 算作双后端通过。后续规范化 ID 与纯匹配增量的受影响用例另跑；日志在 `/tmp/ft-import-source-regression.log`。最终 API、Compose 和草稿统计合同尚未切换，不勾选整体回归及 UI QA 完成。
- 真实样本只读勘查：仓库外账单目录共 42 个文件（含辅助文件），其中 12 份平台表格可检查表头。6 份支付宝可结构化提取账户和时间范围；6 份微信有昵称、导出类型及范围，正文“微信号”是说明文字，不能当本人微信号提取。新解析必须区分实际身份字段与说明，未知身份沿文件局部定位及后续补充规则；尚未将真实账单写入专用测试库，6.3 保持未完成。
- 4.3.1 提交前复验：上述同一双后端定向命令 → 202 passed、无 skip（26.79 s）；随后仅补未知账户已消耗候选分支的失败回归并修复，纯匹配／更新策略 → 100 passed（0.11 s）。OpenSpec strict 与 diff 空白检查通过。本步依然是未暴露的后端基础，无 Compose 文案或 UI 变化，不以其代替 4.7–4.13 入口及用户验收。

- 4.7 平台原始读取小步（HEAD `d275c6d`，基线相同）：新增不去重的 CSV／XLSX 文档对象，精确保留文本、编号前导零、多行字段及重复出现，提取本人账号与显式覆盖声明；结构坏行单独留安全原因。新模块缺失及逐项边界回归先失败，再实现；`uv run pytest tests/test_cash_statement_document.py tests/test_cash_source_matching.py tests/test_cash_record_policy.py -q --disable-warnings --tb=short` → 136 passed（0.22 s），OpenSpec strict、diff 空白检查通过。
- 本小步独立工程／安全复核：坏行误授完整范围、矛盾声明取首项、稀疏索引绕过预算、维度缩小藏行及关系目标改名绕过 XML 预检均采纳，补失败回归后关闭。XML 部件、非空目录项和真实坐标限制已写回 design；独立再跑 36 项读取测试通过，范围内无遗留阻断。尚未接入原生分类、银行解析、会话或提交，4.7 保持未完成。
- 真实账单只读校准（未入库）：6 份微信共 3,974 次、6 份支付宝共 3,813 次原始出现，12 份声明笔数均与观测一致，结构错误为 0，范围／筛选可解析；微信没有可靠本人账号，支付宝 6 份可提取。过程只输出汇总，不保存文件正文或标识。纯读取不涉及持久化，本步双后端不适用；会话、应用服务、真实入库、Compose 和客户端 QA 均仍待对应任务。

- 4.7.1 逐条归一（HEAD `91f47d3`，基线相同）：从旧转换器抽取两段共享来源函数，不调用跨行去重／配对；新边界把未知金额／时间／方向留空并附原因，意外适配器异常只跳该行。`note` 使用未截断的来源备注／描述，商品字段加入同一原文规则；可靠编号只用渠道交易号。金额取绝对值与变号改用 Decimal 的精确复制，新增 38 位数回归，避免默认上下文舍入。
- 4.7.1 独立工程复核无阻断，相关纯测试 379 passed、1 项既有真实 PDF 测试跳过；执行者的来源／转换／双后端类型及旧导入矩阵 431 passed、同一 PDF 跳过，不将该 skip 算后端验证。12 份真实账单对比旧转换：7,737 条候选的金额、时间、资金归属提示、类型完全一致；另 48 条未支付关闭和 2 条还款失败保留规则跳过原因。没有将真实数据写入账本或 Git。
- 4.7.1 完整 SQLite 回归沿前述同一命令，仅排除两个已复现的废弃 Expo 断言：1677 passed、196 skipped、53 deselected（176.09 s），日志 `/tmp/ft-import-native-regression.log`。尚未切换应用入口或实现银行边界，不据此关闭 4.7／6.1／6.3；没有 UI 改动，浏览器及 Native 不适用于该小步。当前验证后继续开发仅解析内容的会话。

- 4.7.2.1 基础暂存（HEAD `9749cfa`，基线相同）：新接口只接收解析文件 DTO；单一 JSON 原子携带文件、输入和当前计划，创建与替换分别使用不存在条件及 ETag 条件。内存／模拟 R2 共用并发、所有者隔离、固定 TTL、取消防复活、大小／文件数、未知写结果与不续期合同。初始模块缺失先失败，随后分别补跨 TTL、循环 JSON、坏嵌套文档、关闭流及 Unicode 安全错误回归。
- 4.7.2.1 独立工程／安全复核采纳四类 finding：写前不完整校验会破坏旧会话、递归校验晚于复制、关闭流异常泄漏、代理字符编码异常；均补失败回归并关闭。最终独立 46 passed；执行者 `PYTHONPATH=tests:.:src uv run pytest tests/test_parsed_import_staging.py tests/test_cash_import_staging.py tests/test_cash_statement_rows.py tests/test_cash_statement_document.py tests/test_cash_import_session_service.py tests/test_cash_source_matching.py tests/test_cash_record_policy.py -q --disable-warnings --tb=short` → 223 passed（0.37 s）。R2 条件能力查询官方合同，当前 botocore 模型确认支持 `IfMatch/IfNoneMatch`；没有连接或写入真实 R2，不将模拟证据声称为 live QA。
- 本步不写业务数据库，双后端不适用；没有用户可见入口修改，跨端及浏览器 QA 不适用。工作区同步、收据判断、文件应用服务、后台过期清扫和旧原文件路径关闭均尚未完成，4.7.2 保持未完成；对象 CAS 不作为联合提交并发正确性的证据。

- 4.9.1 收据／同步基础（HEAD `64c599d`，基线相同）：新增用户范围的命令收据和分页逐项结果，事务归调用方；当前会话和维护上下文只能消费一次，目标对象删除不会被永久结果引用阻塞。新模块缺失先失败；PostgreSQL 独立复现第二写者提前进入（1 failed／SQLite 1 passed），将工作区锁移至 UoW 入口后双后端通过。迁移 40 只增加新表并保留旧结果，已有正式数据无重写；有收据时拒绝有损降级，运行期同步要求 schema `20261021_40`。
- 4.9.1 独立工程复核的两项 P2（投影引用对象与正式 JSON 合同不符、默认结果分页需要整份排序）均采纳，补失败回归并同步修正 ORM／迁移；另补畸形枚举、跨工作区 FK、旧收据往返保留和拒绝有损降级。复核增量 SQLite 24 passed，无遗留阻断。
- 4.9.1 验证：显式专用 PostgreSQL、`PGOPTIONS='-c lock_timeout=2000 -c statement_timeout=15000'`，`tests/contract` 加 PostgreSQL 适配／运行时／导入及 Alembic → 317 passed、无 skip（85.14 s）。随后最终增量 `tests/contract/test_cash_mutation_receipts.py tests/test_alembic_migration.py tests/test_runtime_docs.py` → 68 passed、无 skip（17.96 s），包含两个后端；SQLite 查询计划证明默认分页不使用临时排序。
- 同时完整 SQLite 回归（沿前述命令及两项已知 Expo 排除）→ 1741 passed、214 skipped、53 deselected（188.98 s），日志 `/tmp/ft-import-receipts-regression.log`；完整收集之后新增的合同与输入修复以上述最终矩阵另验。曾误指定不存在的 `test_postgres_repositories.py`，命令未收集测试，已改用实际 `test_postgres_adapter.py` 运行上述矩阵，不计前者为证据。仍未实现联合提交应用路径或未知结果 API，4.9 保持未完成；无 UI 修改，不适用浏览器／Native QA。

- 4.7.2.2.1 文件会话服务（HEAD `9dbd6df`，基线相同）：新模块缺失先失败，再通过同一应用服务验证两个后端。覆盖逐文件失败、密码重传、锁外解析、权限撤销、解析中会话被消费、收据优先恢复、替换命名空间、单文件／总预算、取消及过期修订；重传不重读其他文件。自动来源识别复用同一次表头读取，歧义表头拒绝；超限文件大小截断的回归先失败再修复，失败元数据保留观测大小。
- 本步独立工程／安全复核：授权、收据与 CAS、TTL、解析失败隔离、文件替换与自动识别，无阻断发现；独立 SQLite／纯测试 97 passed，未访问 PostgreSQL 或真实 R2。执行者显式专用 PostgreSQL 执行 `tests/contract/test_parsed_cash_import_context.py tests/test_parsed_import_staging.py tests/test_cash_statement_document.py tests/test_cash_statement_rows.py` → 136 passed、无 skip（7.56 s）；OpenSpec strict、diff 空白检查通过。后端内部服务尚未暴露 HTTP 或 Compose，Web、Native、共享 presentation 无行为变化，浏览器／Hallmark 最终审查不适用于本步。后台清扫、银行来源和完整计划／提交仍未完成，不勾选整体 4.7。

- 4.4.1 角色与分组（HEAD `152ac1c`，基线相同）：新模块缺失先失败；33 项纯测试覆盖新建议逐端证据、既有角色与来源解耦、类型兼容、原有特殊关系、草稿端点拒绝、旧新闭包、退款钻石及联合草稿。独立工程复核重新检查既有发现器，33 passed、无阻断；确认完整联合成员加载和最终财务校验仍属后续接入，不以保守分组替代它们。
- 本步定向 `tests/test_cash_relation_policy.py tests/test_transaction_relations_transfer.py tests/test_platform_refund_matchers.py tests/test_cash_projection.py tests/test_transaction_relations_projection.py tests/test_cash_record_policy.py` → 140 passed、2 项 PostgreSQL 参数因该次未配置连接而 skipped（1.25 s），OpenSpec strict 通过；随后显式配置专用 PostgreSQL 补跑同一组，不把 skip 计为通过。首次误指定不存在的 `test_platform_refund.py` 未收集测试，不计为验证。纯领域策略不写库、不改变 Web／Native／共享 presentation；浏览器及最终 UI audit 不适用于本步，4.4 整体与调用入口仍未完成。
- 上述同一命令显式配置专用 PostgreSQL 后 → 142 passed、无 skip（2.08 s），补齐既有投影的两项后端参数；最终 diff 空白检查通过。

- 4.7.2.2.2 无人访问清扫（HEAD `8871394`，基线相同）：先以缺少清扫接口／worker 的失败用例驱动实现，内存和模拟 R2 分页删除固定到期对象；R2 仅列私有解析前缀并读元数据，不下载正文或触碰其他键。服务生命周期强制启动／停止清扫，首轮不可用则启动失败；运行故障只记录安全健康码，保留进度后重试。运行配置、权限、网络超时及停机期间的物理删除边界写入临时存储说明，未声称真实 R2 部署验证。
- 独立安全／生命周期复核发现 P2：首轮远程清扫持有启动锁，使停止信号无法传达。回归先失败，再将远程清扫移到短锁外，并用启动完成事件和停止计数防止关闭后创建 worker；重新复核关闭该发现，无新增问题，独立 75 passed。执行者的新暂存／清扫增量同为 75 passed，包含实际 TestClient 启动／关闭及启动失败回归。
- 显式专用 PostgreSQL 的暂存、清扫、文件会话应用服务、旧会话及配置定向矩阵 → 130 passed、无 skip（8.21 s）。完整 SQLite 回归沿前述命令及两项已知 Expo 排除 → 1824 passed、232 skipped、53 deselected（190.90 s），日志 `/tmp/ft-import-context-cleanup-regression.log`；完整收集后的生命周期修复由上述 75 项增量重验，不把后端 skip 算作通过。没有修改用户可见 UI 或文案，Web／Native／共享 presentation 无变化，浏览器／Hallmark audit 不适用于本步。旧原文件路径、银行和最终 API／Compose 仍未切换，4.7 和整体回归验收保持未完成。

- 4.6.1 账户命令基础（HEAD `57bd3a6`，基线相同）：新模块及版本列缺失先失败；迁移 41 保留正式值和引用，SQLite 另保留已删 ID 高水位（包含空账户表），已有账户修订推进则拒绝有损降级。统一 ORM 版本列覆盖旧普通重命名、停用和导入币种增补；新命令在工作区／账户锁内重验成员、CAS 并先查收据。创建立即提交，删除须先停用；引用、草稿组成项及非零旧快照阻止补偿删除；修改配置不删已有余额。
- 独立工程／数据安全复核采纳 P1 加密账户快照归属及 P2 异常桶误当无余额：`crypto/security` 共用 `accounts.security` 定位，GET 和 DELETE 共用保守删除检查，字符串／列表／`null` 均不能绕过。回归分别先失败（含 `null` 残余分支）再修复，定向重新复核 4 passed，P1／P2 清零。账户收据只允许普通账户结果字段，并拒绝来源正文及 ID／修订不一致。
- 账户及迁移／收据初次显式双后端矩阵 → 117 passed、无 skip（31.80 s）；扩展 `tests/contract`、PostgreSQL 导入／适配／运行时及 Alembic → 404 passed、无 skip（117.34 s）。随后新增边界用例及修复单独重验，不把前一矩阵当成最终增量验收。
- 完整 SQLite 回归沿前述命令及两项既有 Expo 排除 → 1851 passed、256 skipped、53 deselected（215.33 s），日志 `/tmp/ft-import-account-cas-regression.log`；收集之后的加密账户／异常结构修复由账户与收据增量另验。当前仍是后端内部能力，对外账户 API、CLI 和 Compose 尚未切换，不勾选 4.6 整体；没有 UI／用户文案修改，Web、Native、共享 presentation 无行为变化，浏览器与最终 Hallmark audit 不适用于本步。
- 最终账户／收据／迁移／运行文档矩阵显式配置同一专用 PostgreSQL → 134 passed、无 skip（38.55 s），覆盖全部上述增量和双后端；OpenSpec strict、`git diff --check` 通过。

- 4.6.2.1 HTTP 切换（HEAD `7cb4ca1`，比较基线相同）：新路由缺失先失败，再覆盖稳定 ID、严格 JSON／64 KB 上限、实际登录与工作区成员、CAS、重复请求并发和删除后收据重放。另模拟应用服务已提交后响应丢失，返回安全结果未知，原键重试恢复相同结果；读取故障不泄漏技术细节。旧按名称写路径和服务工厂移除，现有 API 合同测试同步切换。
- 独立工程／API／安全复核覆盖输入、OpenAPI、权限、线程池上下文、并发及未知结果，无阻断 finding；独立 SQLite HTTP／旧 API 合同 24 passed。执行者显式专用 PostgreSQL 执行 `tests/contract/test_account_command_api.py tests/contract/test_cli_replacement_api.py tests/contract/test_account_commands.py tests/test_user_workspace_access.py -q --disable-warnings --maxfail=2 --tb=short` → 121 passed、无 skip（39.12 s）；测试环境沿前述 `PYTHONPATH` 和 `PGOPTIONS`。
- 本步完整 SQLite 回归沿前述命令及两项已知 Expo 排除 → 1877 passed、282 skipped、53 deselected（215.77 s），日志 `/tmp/ft-import-account-api-regression.log`；OpenSpec strict 和 diff 空白检查通过。Cross-platform Impact Check：账户后端合同变化会影响 Web／Native 的后续表单消费；本步没有修改 Compose 页面、共享 presentation 或客户端文案，真实浏览器、Native 与最终 Hallmark 验收留在 4.12／6.4–6.6，不能据本步 HTTP 测试宣称通过。CLI／Agent 消费与连续退出补偿仍未实现，4.6／4.6.2 保持未完成。

- 4.7.3.1 银行表格（HEAD `fbce37d`，比较基线相同）：先由缺模块、默认会话不接受银行文件的失败测试驱动实现；签名与同一次表头读取确定渠道，原始重复行、完整文本和逐行错误均保留，复用两个旧转换器抽出的原生业务函数。缺金额／日期／币种保存未知；零额开户行保留明确跳过原因，不把内容散列／序号／凭证号当可靠 ID。默认内部会话已接入，工行 PDF 及最终 HTTP 提交仍未切换。
- 独立来源／财务／安全复核采纳两项 P1：遮蔽账号不能证明完整本方身份，CSV 连续序号不能证明尾部完整。四项账号、无总笔数和严格字节前缀截断反例分别先失败再修复；身份仅接受完整账号，展示标签保留，工银亚洲无独立笔数时覆盖 unknown。额外复现 XLS 缺尾部字节被 xlrd 警告后接受，补完整扇区和私有诊断保护；同列其他银行和“非全部”标题反例也补失败测试并关闭。两项 P1 定向重新复核关闭，独立纯矩阵 143 passed，未运行 PostgreSQL。
- 执行者最终显式专用 PostgreSQL 矩阵 `tests/test_bank_statement_rows.py tests/test_cash_statement_rows.py tests/test_cash_statement_document.py tests/test_ccb_debit.py tests/test_icbc_asia_current_account.py tests/contract/test_parsed_cash_import_context.py tests/test_parsed_import_staging.py -q --disable-warnings --maxfail=2 --tb=short` → 242 passed、无 skip（9.16 s）。完整 SQLite 回归沿前述命令及两项已知 Expo 排除 → 1914 passed、283 skipped、53 deselected（213.46 s），日志 `/tmp/ft-import-bank-tables-regression.log`；收集后的预算及来源识别增量由上述最终 242 项另验。OpenSpec strict、diff 空白检查通过。
- 真实来源只读校准：两份建行 XLS 共 953 次出现，0 行问题；金额／币种／时间／类型／资金提示与旧原生转换的多重集合完全一致（0 mismatch），两份均提取到完整本方身份及范围。没有可用的真实工银亚洲表格，该来源以去标识合成数据验证；未将真实数据写入账本或 Git，不据此关闭 6.3。没有新增来源分类单元或改变原生分类。Cross-platform Impact Check：Web、Native、共享 presentation 没有页面或文案变化，此内部解析小步不适用浏览器及最终 Hallmark 验收；对外切换与客户端验证仍待后续任务，4.7 整体保持未完成。
- 银行最终增量独立复核：来源声明、精确全量标题和元数据／出现预算，43 passed、无阻断 finding；没有访问 PostgreSQL。

- 4.3.2.1 微信默认来源（HEAD `3608797`，比较基线相同）：用户明确接受默认单一来源、多账号按需区分，已回写词表、主设计、跨层合同及两个 delta；不再保留待确认阻断。缺模块、缺文件设置命令和旧记录未识别范围的测试先失败，再实现来源输入桥接、NFC 名称校验、微信文件定位隔离、文件设置 CAS／权限／消费校验及旧微信可靠身份的只读推导，成功保存时才写绑定。内部账户和原始昵称／本人账号不改变默认范围。
- 独立工程／来源／安全复核采纳两项 P1 和一项 P2：旧原文编号首尾空白导致时间冲突漏查、草稿绑定转移绕过隐含来源、失败重传丢失来源选择。7 项 SQLite 反例全部先失败；查询使用与 Python 相同的空白规范化，普通绑定与草稿合并共用双方身份检查，失败／密码／预算回退均保留来源配置。定向重新复核 7 passed，三项 finding 关闭，无新增阻断；独立附加验证空白集合及预算回退通过，未访问 PostgreSQL。
- 最终显式专用 PostgreSQL 矩阵 `tests/contract/test_cash_source_storage.py tests/contract/test_parsed_cash_import_context.py tests/test_cash_source_scope.py tests/test_cash_source_matching.py tests/test_parsed_import_staging.py tests/test_cash_record_policy.py -q --disable-warnings --maxfail=2 --tb=short` → 276 passed、无 skip（34.17 s）。修复后的完整 SQLite 回归沿前述命令及两项已知 Expo 排除 → 1954 passed、301 skipped、53 deselected（245.38 s），日志 `/tmp/ft-import-wechat-scope-final-regression.log`。此前初次 262 项矩阵及修复前回归不作为最终修复验收；OpenSpec strict、diff 空白检查通过。
- 六份真实微信账单只读输入校准：228 组重叠编号中 9 组识别可更新变化，其余识别重复，0 组意外待处理／新增／冲突；共 425 次与组内首份快照比较，410 次重复、15 次更新（包含上述 9 组的重复出现）。只验证解析与来源策略组合，未写真实账本，不将其当作联合提交或 6.3 完成。Cross-platform Impact Check：后端契约已记录文件来源选择，Web／Native／共享 presentation 尚未修改，无当前 UI 或文案变化；相关入口、三端流程与浏览器／Hallmark 验收仍在 4.11–4.13／6.4–6.6。多文件共同操作计划尚未接入，4.3.2／4.3 整体保持未完成。
