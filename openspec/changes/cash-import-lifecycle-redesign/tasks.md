## 1. 思考：范围与事实

- [x] 1.1 显式执行项目 `grill-me → grilling`，将历次已确认决定和本轮授权写入 proposal；无未决业务分支，不重复询问。
- [x] 1.2 使用 `openspec-explore` 阅读项目原则、词表、目标文档、主规格、active changes、ORM、应用服务及测试，完成只读事实勘查。
- [x] 1.3 确认 A 类；记录账户独立命令、关系替换旧合同、新旧写入口和迁移的影响边界。

## 2. 计划：正式契约与切换

- [x] 2.1 创建 proposal、九项能力 delta 与 design；引用主设计及跨层契约，不创建重复产品入口。
- [x] 2.2 记录跨端影响、信息架构、删除的冗余信息、Hallmark 设计方向和自查。
- [x] 2.3 独立复核产品范围、工程、API／数据一致性和安全；修复阻断发现并记录处置。
- [x] 2.4 `openspec validate cash-import-lifecycle-redesign --strict`、文档链接／JSON 与 `git diff --check` 通过后，先提交并推送计划到 `origin/average-snail`。

## 3. 任务拆分与一致性

- [x] 3.1 将九项能力及目标文档场景对应到测试与任务；明确旧 API、CLI／技能、Compose 调用切换表。
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
- [ ] 4.5 为草稿不入统计、唯一当前归属、待确认可见性和局部投影写失败测试；调整投影读写和余额边界，补齐最新全额退款可见规则。
- [x] 4.5.1 增加草稿单例及可空财务展示、统计资格、待处理计数和稳定排序的领域／存储合同；迁移保留正式派生值及引用，旧缓存标记待重建。
- [x] 4.5.2 从当前问题及草稿关系派生待确认，接入局部读写、列表筛选／分页及列表汇总排除草稿；未知日期仍可定位，待确认不越过显式筛选。
- [x] 4.5.2.1 有界读取当前问题及活动草稿关系，按不同成员汇总；问题实例删除后不复用，局部上下文覆盖独立问题修订。
- [x] 4.5.2.2 接入父流水种子、草稿来源输入、待处理发布及生命周期摘要，验证没有组成项的草稿及待处理变化。
- [x] 4.5.2.3 接入可空列表合同、状态筛选、稳定游标和同快照汇总；排除草稿统计，验证未知金额、日期和显式筛选。
- [x] 4.5.3 校验唯一当前成员及跨工作区归属，移除旧退休副本依赖；保留组成项分配身份、保护人工分类及清空值，并完成余额及局部成本验证。
- [x] 4.5.3.1 强化投影、数据集、成员和关系引用的工作区归属及唯一活动约束；切换指针后清理旧派生副本，正式源数据及当前引用保持。
- [x] 4.5.3.2 确认边读入复用最终图的组成项分配证明，验证等额真实分摊、镜像退款、局部拆分及写失败回滚。
- [x] 4.5.3.3 整笔分类与普通编辑传播人工保护，自动同步保护同值及清空，删除分类同步覆盖值；统一工作区锁顺序和当前可见维护条件。
- [x] 4.5.3.4 内部行 DTO 保留草稿未知值，全部余额入口排除草稿并保留精度；旧管理统计切换当前正式投影，验证转正式、软删除和回滚，完成局部性能及双后端回归。
- [x] 4.5.3.5 为物理删除后的流水、组成项和当前关系 ID 不复用写失败测试；SQLite 迁移保留已用高水位、当前值／引用及关系表达式唯一索引，验证旧上下文不能指向新对象，PostgreSQL 保留序列语义。
- [ ] 4.5.4 为全额退款及全额平台退回在预览／主页保留净额 `0` 写失败测试；迁移旧派生显示标志及发布版本，验证确认／待确认、问题清除、显式筛选、原值不变、升级降级和双后端。
- [x] 4.5.4.1 完成领域、主页读取和历史派生缓存迁移，验证普通零金额隐藏及报表／维护／精度不回归。
- [ ] 4.5.4.2 在实际新预览、共享 Compose 及最终浏览器／演示中验证全额退款可见及详情入口；随 4.8.4、4.12–4.13 交付。
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
- [x] 4.8 为不可变计划、预计投影列表、分页／详情、计划替换和发布消费竞争写失败测试；实现计划与目标 DTO。
- [x] 4.8.1 接入文件级（含零行页）及已存认证页证据的计划／提交前重验，统一计算跨会话汇齐的完整性；不能仅靠旧行覆盖标志，空页元数据的持久化须在该步骤明确。
- [x] 4.8.1.1 统一文件及候选旧导出的认证空间、当前活动绑定与独立空页证据；只在本次内存规划提升完整性，覆盖零行、命名空间、冲突、删除与迁移回滚。
- [x] 4.8.1.2 联合提交在锁内重验并保存认证空页；证据冲突或保存点失败时跳过相关依赖决定，不让依赖旧证明的写入继续。
- [x] 4.8.2 实现已解析行的来源账户组及支付组成项桥接：来源范围隔离、稳定组引用、已有账户修订与币种、同账户合并、未知分摊及逐行错误；不创建账户、不提前写流水。
- [x] 4.8.3 实现不可变计划发布／替换和只读分页详情，验证同一会话 CAS、到期、权限、消费先验及失败保持；不把内部发布层当作已完成业务预览。
- [x] 4.8.4 接入实际来源、资金、字段差异、当前关系及新建议的业务构建器，输出唯一预计流水列表，覆盖批内共同操作和逐项安全失败。
- [x] 4.8.4.1 将实际来源共同操作、资金映射、字段规则及明确补丁组合为逐流水决定；重算补齐结果，保留人工保护及组成项身份，验证固定冲突、等价重复、暂缓更新及逐行安全失败。
- [x] 4.8.4.1.1 API 切换前校准账户选择重验：仅改名称或纯修订变化不跳过，按稳定 ID、当前归属、启用状态、允许类型及所需币种重验；覆盖新增／草稿转正式、人工资金补丁及无关行继续，普通账户命令 CAS 不变。
- [x] 4.8.4.2 接入当前关系与新建议、完整组决定及虚拟最终图，将逐流水操作组成唯一预计列表和详情；验证选择失效、关系错误隔离及当前闭包依赖。
- [x] 4.8.4.3 去除新增伪差异、共享冻结详情字段，分别约束 25 MiB 解析内容及 64 MiB 完整会话；验证不可变详情及真实 19 份账单的完整预览／暂存，不截断数据。
- [ ] 4.9 为逐行保存点、关系组隔离、缓存回滚、收据同事务、重复键／上下文消费和未知结果写失败测试；实现联合提交。
- [x] 4.9.1 实现同事务收据／逐项结果、用户幂等与会话／编辑上下文消费唯一性；将 PostgreSQL 工作区锁提前到 UoW 入口。
- [ ] 4.9.2 接入完整应用命令、逐行与关系组保存点、结果恢复和收据分页 API；最终切换时迁入并移除旧导入收据。
- [x] 4.9.2.1 实现真实计划联合写入：锁内局部重验、认证空页依赖、逐流水及整组保存点、成功余额增量、受影响投影与持久结果同事务；覆盖并发、回滚、幂等及来源／关系失败隔离。
- [x] 4.9.2.1.1 验证全新正式流水的有界批量试写及失败逐条回退；保持相同结果、精度、全部别名原子性、外层失败语义及两个后端的原成本门槛。
- [ ] 4.9.2.2 实现用户范围收据读取／分页与未知结果核实；失败子集派生随 4.10、HTTP 切换及旧收据迁入／移除随 4.11 完成。
- [x] 4.9.2.2.1 为原生锁忙映射及内部核实命令写失败测试；实际独立连接验证 SQLite／PostgreSQL 锁忙只返回 `pending`，锁释放后恢复权威状态，其他存储异常不被吞掉。
- [ ] 4.10 为失败项限时派生、子集范围安全、草稿补全／转正式、问题处理与维护重预览写失败测试；实现后续维护 API。
- [x] 4.10.1 为独立失败正文、私有访问、原 TTL、清理／回滚孤儿及失败存储安全降级写失败测试；替换保留完整原会话的过渡实现。
- [x] 4.10.1.1 完成有类型的私有暂存、继承 TTL、来源子集标记和公开入口失败关闭；不据此开放尚未实现的派生授权及正文消费。
- [x] 4.10.2 为无编号剩余次数、未验证决定无信用、子集不能认证完整／空页、必要别名、重复派生及幂等范围写失败测试；实现收据授权的新会话及计划，双后端重验。
- [x] 4.10.2.1 共享失败证明、当前来源结果和冻结操作中的候选集合；覆盖多失败单元共享大量候选且原证明失效，验证候选精确、必要别名不拆分及证明／计划体积近似线性，关闭独立规模复核 P2。
- [ ] 4.10.3 为同身份草稿 PATCH／转正式、统一字段及账户约束、原始备注／分类保护写失败测试；实现无文件的维护上下文和共享保存边界。
- [x] 4.10.3.1 提取共享流水写入器并接入无文件的详情／草稿保存服务；验证同身份、幂等、当前详情读取失败、人工保护、问题所有权、组成项归属／清理、关系修订、输入边界、相关投影和事务回滚。
- [x] 4.10.3.2 实现有类型的无文件编辑上下文、严格维护输入、计划 CAS／分页详情和消费先验；普通导入入口不能读取或消费该种上下文。
- [ ] 4.10.3.3 实现正式编辑及草稿转正式的同类不可变计划和联合提交，复用统一写入器、账户资格、类型影响及余额／旧新闭包；完整但尚未确认的草稿不自动转正式。
- [x] 4.10.3.3.1 补齐导入人工补丁与无文件维护共用的来源行派生：无人工保护且未明确填备注时同步原文；替换或清空当前来源解释，保留可信本方身份，不改固定字段、绑定或已确认关系。
- [x] 4.10.3.3.2 接入正式编辑及草稿转正式的真实只读业务构建器，复用当前操作、完整关系组、最终图和预计列表；正式编辑不寻找新配对，转正式参与新建议。
- [ ] 4.10.3.3.3 接入真实维护联合提交及恢复，验证流水／关系保存点、类型影响、当前依赖、余额、投影与消费收据原子性。
- [ ] 4.10.4 为当前问题应用／保留、身份草稿合并／独立、来源绑定转移和候选失效写失败测试；实现维护计划及当前闭包发布。
- [ ] 4.10.5 为当前草稿关系的完整决定组、候选修订、确认／延后／忽略／解除及父锚点删除写失败测试；复用最终图校验，禁止残缺组提交。
- [ ] 4.11 用 API 权限／结构／安全错误测试驱动 FastAPI 路由切换；CLI、Agent 与技能共用新服务，关闭绕过新合同的旧入口。
- [ ] 4.12 用共享 DTO／ViewModel 测试驱动 Compose 统一列表与一次确认；实现状态标签、按需详情、主页草稿维护及账户独立表单和退出补偿。
- [ ] 4.13 更新演示场景、语义测试 ID、功能地图及相关说明；Web／Android／iOS 覆盖状态按实际证据填写。

## 5. 审查

- [ ] 5.1 独立产品／范围与工程复核：金额、来源身份、关系组、闭包、保存点、并发、迁移及 API 可发现性。
- [ ] 5.2 安全与性能复核：隔离、日志脱敏、原文件清理、受控临时存储、输入上限、无全库重配与有界分页。
- [ ] 5.3 对实际 Compose 调用 `hallmark audit`，覆盖三类窗口及异常状态，修复并重新复核 critical／major。
- [ ] 5.4 最终 diff 复核 artifact 与实现一致性、旧合同残留、遗漏测试和新增依赖；记录 finding 及处置。

## 6. 测试与 QA

- 4.10.3.3.2 实际维护规划（2026-10-22，HEAD／比较基线 `bd243e4`）：真实默认构建器缺失先 2 failed、1 PostgreSQL case 明确跳过（5.36 s，`/tmp/ft-cash-mutation-business-red.log`）。按独立预审提取当前字段／问题／输入与操作准备，复用导入后段最终图及预计列表；保留问题分开保存 ID 与展示，不传入 writer 重写，整笔分类显式覆盖原投影全体成员且仅目标备注变化。正式编辑不检查旧账户新资金资格、不发现新关系；转正式验证账户、组成项及来源问题，只有转正式组成项成为发现种子。当前来源范围来自可信绑定，微信沿用默认范围，文件局部范围和其他无绑定记录保守隔离。
- 维护规划 SQLite：四项应用合同最终 95 passed、95 PostgreSQL cases 明确跳过（226.53 s，`/tmp/ft-cash-mutation-business-final-sqlite.log`）；额外零退款／忽略决定及无绑定内部编号均已验证。中间矩阵 3 failed／40 passed，分别是微信合成退款编号关系不符合既有原生规则，以及两个输入预算故障注入仍指向提取前模块；修正夹具及注入位置后重跑，不放宽业务规则。新增无绑定夹具最初因正常去重命中正式记录而误当草稿，改用真正独立来源后验证通过。原文配对另有 PDF 换行列名回归先 1 failed／2 passed，统一映射后 3 passed；配对仍不读取人工备注（`/tmp/ft-cash-matching-source-note-red.log`）。
- 维护规划独立静态复核覆盖固定财务约束、停用账户、整笔分类与类型拆分、问题正文所有权、作用域、只读预览和阶段边界，未发现新 P1/P2。追加零退款／既有忽略决定、无绑定内部业务编号不能作为退款证明的执行回归。审查者未运行数据库、测试、构建或私人探针；实际联合提交仍须重验完整分类成员、全部流水／问题／关系令牌，不能把本步当作维护写入完成。
- 维护规划最终双后端：`python3 /tmp/ft_cash_dual.py /tmp/ft-cash-mutation-business-final-dual.log tests/test_cash_matching_source_notes.py tests/contract/test_cash_mutation_business_plan.py tests/contract/test_cash_record_maintenance.py tests/contract/test_cash_import_business_plan.py tests/contract/test_cash_mutation_plan_publication.py tests/contract/test_cash_import_commit.py tests/contract/test_cash_import_operations.py -q --disable-warnings --tb=short --maxfail=4` → 355 passed、无 skip（656.52 s）。同一应用服务在显式专用 `_test` PostgreSQL 与 SQLite 重验实际预览、保存、导入及提交回归。`uv build --out-dir /tmp/ft-cash-mutation-business-dist`、源码编译和 OpenSpec strict 通过；新增文件已暂存后执行 `git diff --cached --check`，无空白问题。
- 维护规划真实探针：`PYTHONPATH=src uv run python /tmp/ft_cash_mutation_business_real_probe.py` 在仓库外临时 SQLite 解析并提交 19 份、15,198 次出现，结果仍为 12,706 正式、6 草稿、2,408 重复、78 安全跳过，441 确认组、27 延后组、0 关系失败；每次出现都有结果、同键重放正确。随后实际选中净额 0 的合并流水执行默认无文件维护预览，仍显示 1 项已确认结果，账本字段与修订不变。去标识日志 `/tmp/ft-cash-mutation-business-real-probe.log`，临时数据库已清理，未记录原文、密码及真实标识；并行功能验证的耗时不作成本证据。
- 维护规划独占成本：`uv run python /tmp/ft_cash_mutation_business_cost.py` → 8 passed（201.50 s，`/tmp/ft-cash-mutation-business-performance.log`）。SQLite／PostgreSQL 千行预览 2.6261／2.9440 s、提交 4.0427／8.2594 s、重复往返 4.7613／8.7054 s；失败重试派生加预览 3.2445／4.4672 s、提交 4.0802／6.0667 s，均通过原 5／15 s 门槛。RSS 高水位增量最大 22.92 MiB，小于 256 MiB。一万笔背景、3 次预热／20 次测量，草稿保存及当前详情读取 P95 25.89／71.76 ms，正式普通维护首次预览 P95 10.95／31.05 ms，均小于 100 ms；不声称覆盖实际维护提交或大关系组。测量未并发本任务其他测试、构建或运行时代码修改；临时隔离的两枚已核对孤立崩溃上报进程已全部恢复并核对为运行态，未暂停模拟器网络进程。
- 本步 Cross-platform Impact Check：只接内部维护规划与共享读取，Web、Native、共享 presentation 无页面、公开接口、用户文案或操作变化；功能地图补内部证据，页面数与跨端 TODO 不变。无 schema、新依赖或真实业务库写入。回退应用仅作废未提交临时维护计划，已有流水、关系、来源问题及永久收据不变。反思：共用边界应从已准备操作开始，不能伪造文件；保留问题的展示和写入必须分开，整笔分类不能依赖跳过人工值的自动同步。

- 4.10.3.3.1 来源派生（2026-10-22，HEAD／比较基线 `71b8b0f`）：补齐已确认主设计第 2.2／9.2 节的实现缺口；拆分来源派生、真实维护规划、联合命令三个可验证小步。缺共享模块的纯回归先 6 failed（0.74 s，`/tmp/ft-cash-source-effects-red.log`）。独立静态复核发现草稿金额补齐未刷新方向提示、解释单独变化被误作重复两项 P2；增量先 6 failed、2 PostgreSQL cases 明确跳过（5.71 s，`/tmp/ft-cash-source-effects-review-red.log`），随后修复。首次归一金额未知丢失可信身份的 4 项回归先失败（0.37 s，`/tmp/ft-cash-source-effects-partial-red.log`），与后续补充统一派生。
- 来源步最终 SQLite：纯规则／平台／银行／PDF／白名单及两项应用合同共 219 passed、65 PostgreSQL cases 明确跳过（155.06 s，`/tmp/ft-cash-source-effects-final-sqlite.log`）。中间矩阵因空解释 `None/{}` 被误判更新有 1 failed／64 passed；按空对象等价修复后完整重跑，不将中间失败写成通过。独立最终静态复核覆盖原文、清空保护、PDF 列名、一般 JSON、可信身份、金额依赖、解释写入与既有关系，原两项 P2 已关闭，无新增 P1/P2；审查者没有运行测试、数据库、构建或私人账单。
- 来源步最终双后端：`python3 /tmp/ft_cash_dual.py /tmp/ft-cash-source-effects-final-dual.log tests/test_cash_statement_effects.py tests/test_cash_statement_rows.py tests/test_bank_statement_rows.py tests/test_icbc_pdf_statement.py tests/test_cash_statement_evidence.py tests/contract/test_cash_record_maintenance.py tests/contract/test_cash_import_operations.py tests/contract/test_cash_import_business_plan.py tests/contract/test_cash_import_commit.py -q --disable-warnings --tb=short --maxfail=4` → 436 passed、无 skip（534.47 s）。同一应用服务在显式专用 `_test` PostgreSQL 与 SQLite 覆盖字段派生、落库修订、原始事项保留、真实业务计划、联合提交与故障隔离。`uv build --out-dir /tmp/ft-cash-source-effects-dist`、`uv run python -m compileall -q src`、OpenSpec strict、8 项合同 JSON 示例及 `git diff --check` 通过；成本验证另行独占。
- 来源步真实账单探针：`PYTHONPATH=src uv run python /tmp/ft_cash_joint_real_probe.py` → 19 份、15,198 个原始出现全部解析，首次 12,706 正式＋6 草稿、2,408 重复、78 安全跳过，441 确认组、27 待确认组、0 关系失败。再次 0 新增／更新／新关系，15,120 重复及相同 78 跳过；12,712 条流水与 477 条关系不增长，每个出现都有结果、两次同键收据重放正确。去标识日志 `/tmp/ft-cash-source-effects-real-probe.log`；仅仓库外临时 SQLite 与合成账户，退出已清理。与功能矩阵并行，耗时不作为成本证据；原文件、密码、名称、账户编号和来源正文没有进 Git／日志。
- 来源步独占成本：`uv run python /tmp/ft_cash_source_effects_cost.py` → 6 passed（168.10 s，`/tmp/ft-cash-source-effects-performance.log`）。SQLite／PostgreSQL 千行普通预览 2.6103／2.8658 s、首次提交 3.7005／8.1177 s、重复往返 4.4750／8.6044 s；失败内容准备 1.4425／1.4261 s、派生 0.5853／0.5147 s、重试预览 2.5835／3.7351 s、重试提交 3.8292／9.8794 s，均通过原 5／15 s 门槛。派生加预览合计仍单列 3.1689／4.2498 s；进程 RSS 高水位增量最大 13.78 MiB，低于 256 MiB，0 仅表示未超过已见高水位。一万笔账本的普通草稿备注保存连同当前详情读取，3 次预热／20 次测量 P95 为 17.73／67.91 ms，低于 100 ms；不声称覆盖最大来源正文或大关系组清理。测量时未并发本任务测试、构建或运行时代码修改；复用上步已核对的两枚孤立崩溃上报进程作临时资源隔离，结束两枚均已恢复并核对状态，未暂停模拟器网络进程。
- 来源步 Cross-platform Impact Check：只补内部归一、导入人工补充及草稿维护共享规则，Web、Native、共享 presentation 无页面、公开入口、文案或操作改动；功能地图更新内部证据，页面数、跨端覆盖及 TODO 不变。无 schema／依赖变化，无真实业务库写入。回退应用不改来源绑定及正式固定字段；最终接口切换仍按既定计划作废旧临时计划。反思：派生依赖不只原文，金额未知到补齐、只有解释变化和空值等价均须独立回归；待展示的问题与本操作负责写入的问题仍保持分离。

- 4.10.3.2 无文件计划发布（2026-10-22，HEAD／比较基线 `e56e5d5`）：存储 kind 反例先 3 failed（0.80 s，`/tmp/ft-cash-mutation-publication-red.log`）；维护服务缺失另 1 failed、1 PostgreSQL case 明确跳过（2.81 s，`/tmp/ft-cash-mutation-service-red.log`）。先回写 design、跨层合同及 ledger delta，再接 `mutation` 空文件上下文、按意图的有界输入、固定目标、父 CAS、独立计划修订、冻结分页、消费先验及当前计划恢复。预览不调用 UoW commit，实际 flush 业务修改的回归证明退出会回滚；不使用伪账单或将临时上下文当作持久草稿。
- 独立静态复核发现摘要可透传私有正文的 P2，先以未知键、布尔、负数、字符串四组反例 4 failed／4 PostgreSQL cases 明确跳过（9.44 s，`/tmp/ft-cash-mutation-summary-red.log`），再在首次发布、替换前及响应读取校验明确计数键和非负整数。计划 `update_posted` 与永久结果 `updated_posted` 分开；首次失败不建上下文，重预览失败保留旧计划。补同工作区其他编辑者不可读取／恢复／取消，以及构建器 flush 后回滚的证据，独立最终静态复核确认该 P2 关闭、无新 P1／P2。
- 最后 SQLite 命令 `PYTHONPATH=tests:.:src uv run pytest tests/test_parsed_import_staging.py tests/contract/test_cash_mutation_plan_publication.py -q --disable-warnings --tb=short --maxfail=3` → 133 passed、20 PostgreSQL cases 明确跳过（44.48 s，`/tmp/ft-cash-mutation-publication-final-sqlite.log`），不计作双后端通过；双后端、构建及最终成本见下面证据。合成构建器只证明发布层，不代表真实编辑／转正式已实现。
- 最终专用双后端命令 `python3 /tmp/ft_cash_dual.py /tmp/ft-cash-mutation-publication-final-dual.log tests/test_parsed_import_staging.py tests/contract/test_cash_mutation_plan_publication.py tests/contract/test_cash_import_plan_publication.py tests/contract/test_parsed_cash_import_context.py tests/contract/test_cash_mutation_receipts.py -q --disable-warnings --tb=short --maxfail=3` → 316 passed、无 skip（324.83 s），覆盖内存／R2、新维护及原导入发布、会话访问和永久消费。`uv build --out-dir /tmp/ft-cash-mutation-publication-dist`、语法编译及 OpenSpec strict 通过，运行前已加载最后摘要保护增量；独立静态范围复核无新 P1／P2。
- 首轮独占成本 `python3 /tmp/ft_cash_dual.py /tmp/ft-cash-mutation-publication-performance.log tests/test_cash_import_performance.py -q -m performance -s --disable-warnings --tb=short --maxfail=2` → 3 passed、1 failed（172.67 s）；PostgreSQL 重试预览 5.2847 s 超过 5 s，不能写成完整通过。SQLite／PostgreSQL 普通预览 3.5112／3.8675 s、首次提交 5.0488／11.4204 s、重复往返 6.5148／11.2071 s；SQLite 重试派生 0.7495 s、预览 4.1087 s、提交 5.9628 s，均满足预算。失败后只读检查见宿主机 10 核、负载约 17.6，后台模拟器及崩溃处理进程占用大量 CPU；这提示资源争用，不能仅凭该观察认定代码无回归。保留失败记录，使用仓库外临时计时器，对同一原 PostgreSQL 用例采集预览墙钟、进程 CPU 和 SQL 总耗时，预算和运行时代码保持不变。
- 带聚合计时的 PostgreSQL 定向诊断 `uv run python /tmp/ft_cash_retry_perf_timing.py` 仍 1 failed（59.57 s，`/tmp/ft-cash-mutation-publication-performance-diagnostic.log`）：重试预览墙钟约 5.3333 s、进程 CPU 2.1682 s、SQL 执行合计 1.5486 s。随后只在测量期间临时暂停经 PID／父进程／程序名核对的两个孤立崩溃上报进程，使用 `finally` 恢复，不改运行时代码或预算。实际命令 `uv run python /tmp/ft_cash_quiet_performance.py` 执行同一原四用例及双后端 → 4 passed（118.90 s，`/tmp/ft-cash-mutation-publication-performance-isolated.log`）；结束日志确认恢复两个进程，事后 `ps` 确认均恢复运行状态。
- 最终资源隔离测量的 SQLite／PostgreSQL 普通预览 2.3875／2.7993 s、首次提交 3.2064／4.1262 s、重复往返 4.5296／5.1300 s；重试准备 1.3226／1.3108 s、派生 0.4412／0.4775 s、预览 2.9431／2.8686 s、提交 4.3365／4.7579 s，均满足原预算，派生加预览为 3.3843／3.3460 s；RSS 高水位增量最大约 25.53 MiB。PostgreSQL 重试的进程 CPU 约 2.1468 s、SQL 合计 0.2348 s，结合未改代码的对照，支持前两次失败受宿主机资源争用显著影响。保留所有失败结果，不把它们替换为通过。测量时未并发本任务其他测试、构建或运行时代码修改；临时诊断脚本和财务测试配置仍在仓库外。
- 发布准备与反思：本步只有内部发布能力、无 schema 变更或实际公共 HTTP 切换；回退时放弃未完成的维护临时上下文即可，已有流水、问题和永久收据不受影响。公开摘要也属于契约和隐私边界，必须与明细一样校验；性能超时先用 CPU／SQL 及资源隔离对照定位，不能靠增加预算、删除断言或掩盖失败收口。真实编辑／转正式和当前问题／关系处理仍在 4.10.3.3–4.10.5。
- Cross-platform Impact Check：本步内部后端及目标接口合同变化，Web、Native、共享 presentation 的页面、操作、公共运行时入口和文案尚未切换；功能地图增加 F-04／F-05 的发布边界证据，页面数和三端 TODO 不变。无 schema／真实账本改写，HTTP 和客户端仍在 4.11–4.13，浏览器及最终 Hallmark 随实际 UI 执行；不把当前发布层提前当作端到端维护完成。
- 4.10.3.1 草稿维护（2026-10-22，HEAD／比较基线 `0c46d55`）：已从原导入命令机械提取共享写入器，原保存点仍经薄转发调用；新增直接读取当前详情及按父修订／幂等键保存草稿的应用服务，不需要文件或会话。普通保存保留状态、来源绑定、原解释、外部问题／候选和人工保护；组成项完整替换复用归属及财务字段约束。字段／问题、局部列表和最小收据同事务，发布失败全部回滚；提交后刷新不可用保留已知成功，重放返回原收据及当前详情。
- 独立只读复核的 JSON、超长库内引用、跨后端序号三个 P2 均采纳关闭。JSON 反例第一轮 5 failed、1 passed、6 skipped（15.03 s，`/tmp/ft-cash-record-maintenance-review-red.log`），其中两项失败只是测试过严要求修订恰增一次，不构成缺陷；修订增长及旧上下文拒绝本已成立，审查者确认遗漏模型 `onupdate` 后撤回原关系 finding，没有新增修订机制或扩张候选闭包。实际非法 Unicode／混合键／NaN 已补摘要前深度、UTF-8 及精确编码预算。超长流水／账户／组成项账户引用另先 3 failed（7.67 s，`/tmp/ft-cash-record-maintenance-reference-red.log`），再限制转换前长度及字符；序号领域与 SQLite 写入越界先 2 failed、3 passed（10.92 s，`/tmp/ft-cash-record-maintenance-ordinal-red.log`），再按既有 `Integer` 统一 `0..2**31-1`，最大合法值可保存，不改 schema。
- 最后共享字段及维护 SQLite 矩阵 `PYTHONPATH=tests:.:src uv run pytest tests/test_cash_record_policy.py tests/contract/test_cash_record_maintenance.py -q --disable-warnings --tb=short` → 83 passed、27 PostgreSQL cases 明确跳过（72.15 s，`/tmp/ft-cash-record-maintenance-ordinal-sqlite.log`），不计作双后端完成。新增一万笔账本下的草稿保存加当前详情整体成本验收，沿用普通编辑 P95 ≤ 100 ms，不把提交后的详情刷新排除在测量外；实际双后端与独占成本证据见下列最终命令。
- 显式专用双后端范围命令 `python3 /tmp/ft_cash_dual.py /tmp/ft-cash-record-maintenance-dual.log tests/contract/test_cash_record_maintenance.py tests/contract/test_cash_import_commit.py tests/contract/test_cash_relation_storage.py tests/contract/test_cash_relation_context.py tests/contract/test_cash_relation_type_updates.py tests/test_cash_record_policy.py -q --disable-warnings --tb=short --maxfail=3` → 327 passed、无 skip（497.12 s），覆盖共享写入器、原联合导入、类型清理及关系上下文。此进程在最后序号上限修正前已加载源码，不能据此冒称最后增量已通过；随后在 SQLite 完成后，最终实际策略／维护双后端命令 `python3 /tmp/ft_cash_dual.py /tmp/ft-cash-record-maintenance-final-dual.log tests/contract/test_cash_record_maintenance.py tests/test_cash_record_policy.py -q --disable-warnings --tb=short --maxfail=3` → 110 passed、无 skip（99.79 s），包括所有最后输入和序号边界。`uv build --out-dir /tmp/ft-cash-record-maintenance-final-dist`、语法编译、8 个 JSON 示例及 OpenSpec strict 均通过；独立最终静态范围复核无新 P1／P2。
- 最终独占成本命令 `python3 /tmp/ft_cash_dual.py /tmp/ft-cash-record-maintenance-performance.log tests/test_cash_record_maintenance_performance.py tests/test_cash_import_performance.py -q -m performance -s --disable-warnings --tb=short --maxfail=2` → 6 passed（221.69 s）。一万笔正式流水加一笔完整草稿，3 预热／20 样本，保存备注、投影、收据及刷新详情的整体 P95 为 SQLite 44.38 ms／PostgreSQL 84.77 ms，均低于 100 ms；独立静态复核确认实际执行不同写命令，不以重放或排除刷新降低耗时，此用例不代表大规模组成项／关系清理成本。原一千笔导入 SQLite／PostgreSQL 的预览 3.9554／3.7474 s、首次提交 5.4890／11.3889 s、重复往返 6.7318／7.3100 s；重试派生 0.7012／0.8122 s、预览 3.9551／4.0358 s、提交 6.2293／12.0906 s，均满足原对应预算，重试派生加预览为 4.6563／4.8481 s。RSS 增量最高约 28.78 MiB，低于 256 MiB。成本运行时未并发本任务其他测试、构建或运行时代码修改，仅整理文档证据。
- 本内部小步无 schema 变化和线上发布，回滚可恢复至比较基线的导入实现，库中草稿、收据及问题结构保持兼容；没有执行真实账本或部署操作。反思：关系版本审查须同时核对列级 `onupdate` 和执行反例，不能仅凭业务函数没有显式赋值判断修订缺失；保留单调增长及旧上下文拒绝的回归，不增加无业务必要的精确递增机制。
- Cross-platform Impact Check：本步只新增内部共享应用服务及校验，Web、Native、共享 presentation 的页面、公共入口、文案和操作未切换；功能地图补 F-04／F-05 的内部维护证据，页面总数和三端 TODO 不变。无原文件／真实账本写入，无数据库迁移；维护计划、转正式及问题／关系处理仍在 4.10.3.2–4.10.5，HTTP 与 Compose／浏览器／最终 Hallmark／用户演示仍在后续阶段，本步不声称整体交付。
- 4.10.3 维护预审（2026-10-22，当前 HEAD `0c46d55`）：先以无文件服务缺失的回归 3 failed、2 PostgreSQL cases 明确跳过（8.19 s，`/tmp/ft-cash-record-maintenance-red.log`）。独立只读预审确认先共享写入器、再接详情／草稿保存、最后实现编辑上下文与转正式；采纳目标／组成项归属、问题所有权、人工保护、受影响组及候选对侧、已提交但详情不可读六项实施约束。跨层表定义把父修订写成整个维护视图版本，与既有独立问题／关系修订和失败测试相矛盾，按已验证局部 token 校准文档，不改变业务决定。当前仍是失败测试及计划，未声明实现完成。
- 4.8.4.1.1 账户重验（2026-10-22，HEAD／比较基线 `f867f9a`）：账户改名及纯修订的新增／草稿转正式回归先 4 failed、1 passed（13.78 s，`/tmp/ft-cash-account-revalidation-red.log`），失败中止后未进入 PostgreSQL，不计为双后端。设计及 delta 明确按人工补丁后的最终父／全部组成项冻结 `account_id/exists/active/type/currency_support`，只包含实际所需币种，不再单独比较导入账户修订；普通账户 CAS 及正式固定字段约束不改。独立静态预审和最终复核关闭原 P2，无新 P1／P2，补无关币种删除及人工多账户分摊失效；审查没有代运行测试。
- SQLite 账户三文件 `tests/contract/test_cash_import_account_revalidation.py tests/contract/test_cash_import_operations.py tests/test_cash_import_funding.py -q --disable-warnings --tb=short --maxfail=4` → 105 passed、58 PostgreSQL cases 明确跳过（138.35 s，`/tmp/ft-cash-account-revalidation-sqlite.log`）。随后补账户失效原因：原笼统 `record_changed` 先 1 failed、1 skipped（3.16 s），修复后 `-k 'lost_account or allocation_accounts'` → 4 passed、4 skipped（10.18 s，`/tmp/ft-cash-account-reason-sqlite.log`）；只替换目标及修订未变化时的安全原因，不改变跳过判定。增量静态复核无阻断；最终完整双后端结果单独记录，不将中间 skip 当通过。
- 最终账户双后端命令 `python3 /tmp/ft_cash_dual.py /tmp/ft-cash-account-revalidation-dual.log tests/contract/test_cash_import_account_revalidation.py tests/contract/test_cash_import_operations.py tests/contract/test_cash_import_business_plan.py tests/contract/test_cash_import_commit.py tests/contract/test_cash_import_retry.py tests/contract/test_account_commands.py tests/test_cash_import_funding.py -q --disable-warnings --tb=short --maxfail=3` → 427 passed、无 skip（693.27 s）。使用显式专用 `_test` PostgreSQL，覆盖新增／草稿转正式／人工资金补丁的预览前后变化、全部最终分摊账户、仅相关行跳过、普通账户 CAS、共同配对和失败恢复；源代码在最终原因增量之后加载。`uv build --out-dir /tmp/ft-cash-account-revalidation-final-dist`、语法编译及 OpenSpec strict 通过，最终独占成本另列如下。
- 账户步最终独占成本 `python3 /tmp/ft_cash_dual.py /tmp/ft-cash-account-revalidation-performance.log tests/test_cash_import_performance.py -q -m performance -s --disable-warnings --tb=short` → 4 passed（169.76 s）。SQLite／PostgreSQL 普通预览 3.8720／3.8247 s、首次提交 5.6014／11.1282 s、重复往返 6.5090／10.8249 s；失败内容准备 1.8949／1.9757 s、派生 0.7221／0.7666 s、重试预览 4.4844／4.0798 s、重试提交 6.3795／6.1686 s，符合原有独立命令 5／15 s 预算。派生加预览合计 5.2065／4.8464 s 继续如实报告；进程 RSS 高水位增量普通约 5.44 MiB／0、重试 37.69／0.42 MiB，均低于 256 MiB。测量期间没有并发本任务测试、构建或修改运行时代码。
- 本步 Cross-platform Impact Check：只校准尚未公开的新后端计划与提交，Web、Native 和共享 presentation 未改页面或文案；功能地图更新内部证据，页面数及对齐 TODO 不变。无 schema、新依赖或真实数据迁移，回退应用只会恢复原先的账户误拒绝，不改既有流水和余额；最终接口切换仍须使升级前旧临时计划失效。反思：导入当前资金资格与账户普通命令 CAS 是不同依赖，不能把纯修订变化误作业务语义失效；新增回归同时约束最终人工分摊及无关行隔离。
- 4.10.1–4.10.2 最终功能矩阵（2026-10-22，HEAD／比较基线 `1c1e9c0`，验证本步未提交差异）：`python3 /tmp/ft_cash_dual.py /tmp/ft-cash-retry-shared-final-dual.log tests/contract/test_cash_import_retry.py tests/contract/test_cash_import_retry_candidates.py tests/contract/test_cash_import_commit.py tests/contract/test_cash_import_operations.py tests/contract/test_cash_import_business_plan.py tests/contract/test_cash_mutation_receipts.py tests/contract/test_parsed_cash_import_context.py tests/contract/test_cash_source_document_storage.py tests/contract/test_cash_import_plan_publication.py tests/test_cash_import_retry_validation.py tests/test_cash_source_retry_positions.py tests/test_cash_source_matching.py tests/test_cash_source_batch.py tests/test_parsed_import_staging.py -q --disable-warnings --tb=short --maxfail=4` → 706 passed、无 skip（844.36 s）。使用显式专用 `_test` PostgreSQL；同一应用合同覆盖 SQLite，候选形状破坏、候选当前修订、保存点回滚刷新及派生恢复均包含在最终源代码加载之后。先前 SQLite 中间矩阵 138 passed、112 PostgreSQL cases 明确跳过（269.41 s）不替代此结果。`uv build --out-dir /tmp/ft-cash-retry-shared-dist`、`uv run python -m compileall -q src` 及 OpenSpec strict 通过；最终成本检查另行独占运行。
- 成本门槛校准：首轮 2 failed、优化后 1 failed／3 passed（`/tmp/ft-cash-retry-cost-optimized.log`），分别反映“派生＋预览合计 5 s”的新增合并门槛，不能改写为通过。独立工程复核确认两个独立命令应分别计时：预览保持既有 5 s、提交保持 15 s，派生命令新增单独 5 s 预算，并继续报告合计；普通导入、重复导入和 256 MiB 门槛不变。
- 最终独占成本命令 `python3 /tmp/ft_cash_dual.py /tmp/ft-cash-retry-shared-final-performance.log tests/test_cash_import_performance.py -q -m performance -s --disable-warnings --tb=short` → 4 passed（175.92 s）。SQLite／PostgreSQL 普通预览 3.9101／3.8862 s、首次提交 5.5795／11.1414 s、重复往返 6.5679／9.2817 s；失败内容准备 1.7305／1.9614 s、派生 0.7685／0.7192 s、重试预览 4.2865／4.2323 s、重试提交 6.4045／11.9306 s，均满足对应 5／15 s 预算。派生加预览合计仍报告为 5.0550／4.9515 s，不冒称两端均符合此前新增的合计 5 s。进程 RSS 高水位增量：普通约 5.03 MiB／0，重试约 13.23／14.97 MiB，均低于 256 MiB；0 不表示没有内存使用。测量时未并发本任务其他测试、构建或运行时代码修改，只有文档证据整理。
- 新增规模独立静态复核 P2：共享组候选被逐失败单元展开到证明，证明变化后又重复扫描／复制，再在逐操作冻结时展开。登记 4.10.2.1；修复后，证明及冻结计划按内容签名共享集合，当前来源规划复用有效组，提交按集合加载当前候选，保存点回滚后刷新。32／128 单元的体积及候选精确性回归先失败后通过；最终双后端及成本结果单独记录，不增加用户交互。

- 重试成本首测为独占运行，未并发其他测试、构建或修改运行时代码：普通千行导入两后端均通过旧预算；新增千行失败子集用例 2 failed，派生加预览 SQLite 5.2227 s、PostgreSQL 5.5383 s，超过新增合计 5 s，准备 1.9322／2.0536 s、提交 6.0705／12.6444 s、内存增量 28.34 MiB／0，日志 `/tmp/ft-cash-retry-cost-final.log`。0 仅表示未超过同进程既有 RSS 高水位。首测不是通过证据；之后优化同请求内的根上下文读取和形状校验、输入签名复用及 JSON 预算遍历，再按前述独立命令边界验收。

- 4.10.1–4.10.2 应用层（2026-10-22，验证时 HEAD／比较基线 `1c1e9c0`）：按持久收据另存失败正文、继承原 TTL、登记派生及跨副本消费；成功草稿也撤销失败正文访问。初始应用回归先 2 failed，文件位置弱对应先 1 failed；原两项 P2 修复后 SQLite 主要链路 28 passed、24 PostgreSQL cases 明确跳过（58.54 s，`/tmp/ft-cash-retry-complete-sqlite.log`），完整形状及物理位置纯测试 30 passed（0.46 s，`/tmp/ft-cash-retry-private-shapes.log`）。账户来源范围变更的增量 1 passed、1 PostgreSQL case 明确跳过；这些中间结果不当作双后端证据。
- 十一文件显式双后端 `python3 /tmp/ft_cash_dual.py /tmp/ft-cash-retry-final-dual.log tests/contract/test_cash_import_retry.py tests/contract/test_cash_mutation_receipts.py tests/contract/test_parsed_cash_import_context.py tests/contract/test_cash_source_document_storage.py tests/contract/test_cash_import_commit.py tests/contract/test_cash_import_business_plan.py tests/contract/test_cash_import_plan_publication.py tests/test_cash_import_retry_validation.py tests/test_cash_source_retry_positions.py tests/test_cash_source_matching.py tests/test_cash_source_batch.py -q --disable-warnings --tb=short --maxfail=4` → 516 passed、2 failed（703.04 s）。两处旧过渡测试仍要求原会话充当失败上下文，按已确认私有副本合同更新断言；另补派生同键在成功后返回消费收据及实际认证文档重试，最终双后端两文件 `-k 'retry or consumed_context'` → 58 passed、90 deselected、无 skip（114.92 s，`/tmp/ft-cash-retry-final-incremental.log`）。原同键派生误报过期先 1 failed，修复后 SQLite 定向 1 passed；最终增量覆盖该修复，不把原大矩阵记作全绿。
- 独立只读复核关闭必要别名及形状校验两项 P2，补完整消费白名单／用户工作区过滤、登记失败／事务孤儿、根不可读、清理失败、兄弟旧计划、无编号剩余次数及另一子项成功后剩余次数验证。派生重放及成本测试设置增量复核无新增阻断；审查者未运行数据库、探针、构建或性能，不以审查替代执行证据。
- 真实恢复探针只使用仓库外临时 SQLite：微信 212 条，模拟一条写入后保存失败，211 条成功、1 条跳过；派生仅包含该失败行，重试新增 1 条、跳过 0 条，原键重放及成功消费均验证。`/tmp/ft-cash-retry-real-probe.log` 只保存计数与布尔结果；临时库已删除，没有保存原文件、密码或来源正文到 Git／日志。此前完整真实账单回归仍按原证据保留，本探针不替代最终 HTTP／客户端验证。

- 账户重验的独立合同核对确认一项 API 切换前 P2：合同 §3.6 允许无关展示变化，当前资金选择及 `expected_accounts` 指纹却会仅因修订变化拒绝。登记 4.8.4.1.1 并恢复父任务未完成；后续以当前资金语义依赖重验代替单独比较修订，保留普通账户命令严格 CAS。本轮为静态证据，尚无实施或测试通过声明，不混入失败项重试的完成范围。

- 4.10.1–4.10.2 应用层独立预审发现两项 P2：移除必要别名后可能部分保存并消费整个共同操作；内部证明仅检查外层容器，损坏的次数描述／文档证据／来源决定可能在正文返回后才报错。先补回归，SQLite 定向结果 4 failed、1 passed、5 PostgreSQL cases 未运行（`/tmp/ft-cash-retry-review-red.log`）；按 design 回写逐共同操作安全跳过、全部必要别名成功才消费及完整内部形状校验，后续修复和复核结果见本节最终记录。

- 共享候选的最后一轮独立静态复核无范围内 P1／P2；覆盖授权、必要别名、祖先消费、重放、证明、候选签名与指纹、当前候选修订、保存点回滚刷新和 JSON 预算。确认不再构造单一当前选项随后丢弃的临时组保持语义；真实问题与候选的规范链接仍须逐项保存，相应密集写入成本没有被宣称消除。审查未运行测试或访问真实账单，账户展示变化 P2 按 4.8.4.1.1 保留下一步。
- 本步 Cross-platform Impact Check：新增内部失败项派生能力，Web、Native 及共享 presentation 尚未消费新命令，不改页面、操作或文案，三端 UI 验收及 Hallmark 仍留在 4.11–4.13。功能地图只更新后端证据，页面数及对齐 TODO 保持。没有新增 schema／依赖或实际账本迁移；回退应用不会撤销已提交流水和收据，旧代码无法读取新增私有重试种类，限时正文仍由原 TTL 清理。发布前须统一切换公共入口和客户端，不能将内部能力视为已交付的新导入 UI。
- 反思：失败恢复不能仅缩小来源行集合后重跑普通导入；必须保留必要别名及原次数证明，并以持久成功结果消费。复用与优化的单位应是已校验的同请求资料及共享候选集合，不能扩大业务身份或取消逐流水保存点；相应反例已纳入本步回归。

- 4.10.1.1 私有重试基础（2026-10-22，验证时 HEAD／比较基线 `11bd695`）：内存／R2 的继承寿命、旧对象、取消清理和来源子集先 13 failed；普通入口读取／改写私有对象先 2 failed。新增上下文种类及内部资料受原 JSON、深度、字节及 CAS 边界约束，普通入口当前只开放 `import`，完整重试授权尚未接入。独立静态复核无阻断，补已有完整页仍不能认证子集，以及提交／核实不可进入私有上下文两个反例。
- 六文件双后端矩阵 `python3 /tmp/ft_cash_dual.py /tmp/ft-cash-retry-context-final-matrix.log tests/test_parsed_import_staging.py tests/contract/test_parsed_cash_import_context.py tests/test_cash_source_documents.py tests/contract/test_cash_source_document_storage.py tests/contract/test_cash_import_plan_publication.py tests/contract/test_cash_import_commit.py -q --disable-warnings --tb=short` → 345 passed、4 failed（376.75 s）；失败均为新增私有计划夹具后缀超过合同固定 16 字符，未进入目标拒绝路径。修正夹具并补独立复核反例后，最终两文件 `-k 'retry_subset or private_or_unregistered'` → 8 passed、78 deselected、无 skip（13.10 s，`/tmp/ft-cash-retry-context-final-incremental.log`）。未把首轮写成全绿。`uv build --out-dir /tmp/ft-cash-retry-context-dist`、OpenSpec strict 和 `git diff --check` 通过。
- 重试设计独立复核采纳两项 P2：移除“复用完整原会话”的过渡表述；子会话成功后按根收据及根逻辑操作撤销祖先／兄弟正文访问，以持久成功结果而非清理成功判断消费。正式设计最终无新增阻断；成功白名单、登记失败／孤儿、根不可读及兄弟旧计划仍须在 4.10.1–4.10.2 实现验证，未因设计闭合标记完成。Cross-platform Impact Check：本步仅内部暂存和拒绝边界，Web、Native、共享 presentation 未切换，功能地图不新增页面或移除缺口。1,000 行成本和完整回归随完整重试链路及最终门禁补跑，当前不声明重试可用。

- 4.9.2.2.1 结果核实（2026-10-22，HEAD／比较基线 `6e6a986`）：原生错误码优先反例及真实独立 SQLite 持锁先 7 failed；补 `55P03`、SQLite 扩展码和明确未知码不按文本误判。独立静态复核发现 PG 默认锁等待无界 P2，采纳核实专用 NOWAIT／SQLite 临时零等待及独立 UoW，不改变普通写等待；最终静态复核确认关闭、无新增阻断，未代运行测试。中间两文件双后端 118 passed（174.97 s）加载早于 NOWAIT，不当最终证据。最终 `python3 /tmp/ft_cash_dual.py /tmp/ft-cash-resolve-nowait-final.log tests/test_relational_runtime.py tests/contract/test_cash_import_commit.py tests/contract/test_cash_mutation_receipts.py -q -k 'native_codes or resolve or lock or uow' --disable-warnings --tb=short` → 26 passed、147 deselected、无 skip（35.02 s）；PG 明确关闭 `lock_timeout` 并用独立持锁连接，SQLite 验证连接池等待值恢复。随后只读原生码 `25006` 回归先 1 failed，修复后完整 `tests/test_relational_runtime.py` → 25 passed（7.84 s，`/tmp/ft-cash-resolve-runtime-final.log`）；该纯映射不影响已验证 NOWAIT 代码。`uv build --out-dir /tmp/ft-cash-resolve-dist`、OpenSpec strict 和 `git diff --check` 通过。公共核实 HTTP 及跨端客户端尚未切换，页面数、三端覆盖与功能地图缺口不变。

- 4.8.1.2／4.9.2.1 联合提交（2026-10-22，验证时 HEAD／比较基线 `6cbfbe0`，本步未提交差异）：缺失命令模块先 9 failed，补认证证据依赖、草稿组成项替换／交换、并发完全重复及新别名、关系精确分摊、当前问题保留和外层失败回滚反例。独立工程／财务复核关闭相关 finding，无新增阻断；该轮复核只读，未代替执行者的数据库测试。
- 最终十文件双后端矩阵：`python3 /tmp/ft_cash_dual.py /tmp/ft-cash-joint-import-final-matrix.log tests/contract/test_cash_import_commit.py tests/contract/test_cash_import_business_plan.py tests/contract/test_cash_import_operations.py tests/contract/test_cash_import_plan_publication.py tests/test_cash_import_plan_values.py tests/contract/test_cash_relation_type_updates.py tests/contract/test_cash_source_storage.py tests/contract/test_cash_source_document_storage.py tests/contract/test_cash_mutation_receipts.py tests/test_parsed_import_staging.py -q --disable-warnings --tb=short` → 545 passed、2 failed（853.94 s）。两处失败均为新增批量成功夹具错误：超过 18 位小数及把两组成项写为 `atomic`，财务约束正确拒绝；修正夹具且保留精度／归属断言后，最终双后端增量 `test_cash_import_commit.py -q -k 'successful_bulk or large_new_batch or old_receipt_pages'` → 6 passed、80 deselected、无 skip（16.69 s，`/tmp/ft-cash-joint-import-final-incremental.log`）。覆盖批量成功时每个 ID 的账户／组成项／跨文件别名及精确余额、真实 `IntegrityError` 回滚后逐条回退、收据旧引用随当前拆分／删除解析。首轮矩阵不写成全绿，完整回归仍在本节后续门禁。
- 固定一千行成本首轮两后端超预算；不变更 5 秒预览、15 秒首次提交／重复往返和 256 MiB 额外峰值门槛。优化编解码重复遍历、完整来源索引与锁内结果复用、新父记录无旧依赖查询、全新记录有界批量试写；任何可恢复批量失败仍回滚后逐条执行。最终独占双后端 `tests/test_cash_import_performance.py -q -m performance -s` → 2 passed（83.92 s，`/tmp/ft-cash-joint-import-performance-bulk.log`）：SQLite 预览 3.468 s、提交 5.069 s、重复往返 6.035 s；PostgreSQL 3.750／10.437／10.106 s。额外进程峰值分别约 3.0 MiB／0；后者表示此前进程峰值更高，不能解读为零内存。测量期间没有并发本任务测试、构建或运行时代码修改，之后只有测试夹具调整。此前 PostgreSQL 15.012 s 明确记为失败，未四舍五入视为通过。
- codec P2 的内存／R2 转义放大反例、完整来源索引计划及混合参数反例先失败后修复；大小超限保留原 CAS 修订／计划，125 分片最坏约 876 参数，兼容 999 上限。独立最终静态复核检查编解码、索引、批量／逐条等价、必要问题故障及外层回滚，无新增阻断。`uv build --out-dir /tmp/ft-cash-joint-import-dist`、OpenSpec strict 及 `git diff --check` 通过。
- 真实账单临时 SQLite 联合写入探针（`/tmp/ft-cash-joint-real-probe-first.log`）：19 份、15,198 个出现，首次 12,706 正式＋6 草稿、2,408 重复、78 安全跳过，441 个确认组、27 个待确认组；再次 0 新增／0 更新／0 新关系、15,120 重复及相同 78 跳过，12,712 条记录和 477 条关系均不增长。每个出现有结果，同键收据重放验证。微信 6 文件逐份写入探针（`/tmp/ft-cash-wechat-update-probe.log`）：3,974 个出现、3,549 新增、9 次更新、416 重复，0 跳过／身份歧义；默认单一来源没有要求补微信号。均使用仓库外临时库及合成账户，结束清理；原文件、密码、来源编号及正文未入 Git 或日志。探针与回归并行，耗时不作为成本证据。
- 完整 SQLite 回归 `/tmp/ft-cash-joint-import-sqlite-regression.log` 使用修正前已加载的批量夹具，已有同一夹具失败；约 32% 时在后续锁核实代码改动前主动终止，未计作完整通过，最终整体回归仍待 6.1。Cross-platform Impact Check：本步实现内部写命令及分页收据，Web、Native、共享 presentation 的页面、路由、操作和文案尚未切换。功能地图保留 F-03／F-04／F-05 及三端缺口；锁忙核实、只留失败正文的重试、维护 API、公共入口切换、Compose、最终浏览器／Hallmark／用户演示仍未完成。

- 4.8.4.2–4.8.4.3（2026-10-21，HEAD／比较基线 `02773ed`）：真实只读构建组合逐流水、旧确认闭包、新建议、选择及完整最终图；局部类型变更不启动发现，关系错误只生成该组安全结果。新增不构造差异，详情单跳引用同一冻结计划值，公开读取恢复独立副本并应用白名单。解析集合 25 MiB／完整会话 64 MiB 分别检查，超限／非法 JSON 在 CAS 前失败，原计划及修订保持。
- 独立工程复核采纳并关闭来源范围 P1、镜像任意排序 P1、反向竞争 P1 和候选截断锚点 P2；同平台编号按来源账户隔离，自动镜像须双方完整候选池均唯一。2×2 有效正式夹具旧行为 2 failed；反向竞争纯测试 2 failed；21×1 有效完整来源夹具移除显式锚点时 2 failed（`/tmp/ft-cash-mirror-anchor-valid-red.log`），修复后通过。早期锚点夹具缺正式类型／来源绑定时进入身份草稿，已修正，不能以其零关系结果证明业务修复。冻结字段边界 3 failed → 10 passed；非法 JSON、两级预算及原子性有独立回归。独立最终复核 103 passed 及镜像定向 4 passed，无新增阻断；未跑 PostgreSQL 或真实数据。
- 执行者显式双后端十文件矩阵：`test_cash_import_business_plan.py test_cash_import_operations.py test_cash_import_plan_publication.py test_cash_import_plan_values.py test_parsed_import_staging.py test_parsed_cash_import_context.py test_transaction_relations_refund.py test_transaction_relations_payment_mirror.py test_relations_pipeline_order.py test_import_relation_planning.py -q --disable-warnings --tb=short` → 430 passed、无 skip（97.20 s，`/tmp/ft-cash-business-plan-complete-matrix.log`）。旧导入及关系成本独占矩阵 `test_cash_projection_performance.py -q -m performance -k 'import_preview or relation_mutations or group_mutations' --disable-warnings --tb=short` → 8 passed、15 deselected（65.28 s，`/tmp/ft-cash-business-plan-legacy-performance.log`），没有并发测试、构建或运行时代码修改，原门槛保持。
- 真实只读规模探针 `PYTHONPATH=tests:.:src uv run python /tmp/ft_cash_business_real_probe.py` → 19 份全部解析、15,198 行，解析 18.39 s、业务计划 9.61 s；12,706 个新增正式、6 个草稿、2,408 个重复出现、78 个安全跳过，441 个拟确认及 27 个待确认关系组。JSON 观测约 42.14 MB，真实存储编码在 64 MiB 内且 CAS 暂存成功（`/tmp/ft-cash-business-real-probe-mutual-final.log`）。仅在仓库外临时库建合成账户，真实流水未写业务表，原文件／密码／来源正文未入仓库或日志；这不是进程峰值内存预算证明，也不是新联合提交验收。
- `uv build --out-dir /tmp/ft-cash-business-plan-dist`、OpenSpec strict、独立范围 diff 与 `git diff --check` 通过。Cross-platform Impact Check：本步仅内部只读计划及限时存储，Web、Native、共享 presentation 的实际页面、操作、路由和文案未切换。功能地图登记构建器证据，页面数及客户端 TODO 不变；HTTP、联合提交、后续维护、Compose、浏览器、最终 Hallmark 与演示仍按 4.9–4.13／6.5–6.7 验收。完整回归在 6.1 随新写入口补跑，不能把这一步当作整体导入完成。

- 4.5.4.1 全额退款后端（2026-10-21，比较基线 `b458cdb`，验证时 HEAD `b270ab6`）：原隐藏行为先 4 failed（`/tmp/ft-full-refund-visibility-red.log`），缓存迁移缺失先 3 failed（`/tmp/ft-full-refund-migration-red.log`）。48 迁移同步活动／暂存显示标志、规则元数据及数据库默认值；只推进受影响的当前发布版本，不改原流水、关系、金额、统计资格或就绪状态。降级识别新规则创建的结果，版本仍递增；故障注入验证事务回滚。
- 显式 SQLite／PostgreSQL 16 文件最终矩阵 476 passed、无 skip、1 warning（208.92 s，`/tmp/ft-full-refund-final-matrix.log`）。完整 SQLite 首轮 2,583 passed、636 skipped、53 deselected、1 failed（603.61 s，`/tmp/ft-full-refund-sqlite-regression.log`），失败是归属迁移测试把后续规则元数据变更错误算作源数据变化；把该测试精确限定到 45 迁移后，完整归属双后端 56 passed、无 skip（26.79 s，`/tmp/ft-full-refund-ownership-final.log`）。保留全部财务值／引用不变断言，未把该首轮写成全绿；后续整体回归仍在 6.1。
- 独立工程／财务复核未发现阻断；P3 数据库默认版本残留已补迁移及升级／降级反射检查，增量静态复核确认关闭。复核另独立跑纯领域及 SQLite 28 passed、3 PostgreSQL 项明确排除，不当作双后端证据。普通隐藏零项查询及分类／删除、问题清除后退款可见、全额／部分退款报表和 38 位金额断言均保留。
- 独占性能 `test_cash_projection_performance.py test_cash_category_performance.py -q -m performance --disable-warnings --tb=short` → 40 passed、3 deselected、1 warning（327.17 s，`/tmp/ft-cash-full-refund-performance.log`）；两后端均使用原固定规模、采样及耗时门槛，测量期间未并发测试、构建或运行时代码修改。`uv build --out-dir /tmp/ft-cash-full-refund-dist`、OpenSpec strict 和 `git diff --check` 通过。
- Cross-platform Impact Check：共享后端显示语义同时影响 Web／Native 的正式列表和详情，未改客户端布局、操作、路由或共享模型。Compose 当前没有额外按 `full_refund` 过滤，实际三尺寸展示、全额退款新预览和用户演示仍待 4.5.4.2／4.12–4.13／6.5–6.6；功能地图更新后端证据，不增加页面数、不移除客户端缺口。未操作真实账本或部署。

- 最新需求澄清（2026-10-21，HEAD `ebfbe07`）：用户对全额退款预览／主页的可见性明确回答「都不隐藏」。据此回写 proposal、浏览／分类 delta、design、词表及设计合同；覆盖全额平台退回，净额保持 `0`，没有问题的已确认项也保留。此决定不改变普通零金额隐藏、统计资格、日期或余额口径。4.5 因新增验收恢复未完成，4.5.4 先写回归再实施；最终 Compose 演示及浏览器 QA 还须包含两处列表、详情及问题清除后的状态，未以计划记录代替验证。
- 全额退款独立计划复核：范围为词表、主设计合同、proposal、浏览／分类 delta、design、任务及功能地图，未发现业务矛盾。采纳暂存缓存同样转换、规则默认值一致、降级识别新生成结果及保留其他隐藏项待确认覆盖四项边界，回写 design／4.5.4；实现与双后端验证尚未完成。
- 真实预览规模 Flow-Back（2026-10-21，HEAD `b458cdb`）：19 份账单全部解析、15,198 个原始出现；初版完整只读计划约 106.4 MB，超过原单对象预算，去标识汇总见 `/tmp/ft-cash-business-real-probe-sections.log`。不生成新增伪差异，详情单跳引用同计划最终值；解析集合仍限 25 MiB，规范化后含计划会话明确限 64 MiB，不压缩规避预算，不截断流水。独立只读复核接受此方向；采纳平面索引、冻结已有对侧、逐项恢复及白名单、两级预算／CAS 原子性，并明确内存适配器的 100 MiB 是已存 JSON 容量而非进程内存上限。补入 design／4.8.4.3，真实暂存及峰值成本仍须实测。
- 实际构建器独立复核新增两项 P1，4.8.4.2 保持未完成：近期开窗的同平台编号证据丢失来源账户作用域；非工行同分／等数量镜像可由随机 `record_id` 决定强配对端点。均采纳，先补跨微信来源及 2×2 镜像反例，再修发现输入及唯一性边界。回写关系 delta 与 design；不以稳定排序代替消除歧义，不重配已有确认，待确认仍可滞后处理。共享详情、白名单及双预算未发现新增阻断；联合提交重验及既有完整草稿维护仍属 4.9／4.10。
- 4.8.4.1 逐流水操作（2026-10-21，HEAD／比较基线 `e9f7a9a`）：模块缺失先 10 failed（`/tmp/ft-cash-import-operations-red.log`）。完成既有账户测试配置后，新增完成状态 5 failed、非法字段路径 1 failed、等价多重出现 1 failed、组成项身份 4 failed，分别覆盖最终账户重验、补齐后重算问题、行错误格式安全、集合重复及 ID 归属／保留；修复后 SQLite 29 passed、29 PostgreSQL cases 明确排除（14.07 s，`/tmp/ft-cash-import-operations-component-green.log`）。早期缺少币种或使用过期修订的夹具导致的失败已修正测试设置，没有放松正式账户约束。
- 独立只读工程／业务复核覆盖人工保护、固定字段、资金完整性、共同操作、身份问题和逐行失败；发现 P1 最终账户未重验，以及 P2 旧解析原因残留、非法字段名中断整批、组成项 ID 未保护，均补红绿回归并采纳修复。补填资金按最终值重验本工作区、账户类型、可用性、币种和修订，外工作区／不存在引用保留空值及待处理原因；来源重算保留当前组成项身份，明确补丁 ID 校验归属及去重。最终增量静态复核确认 finding 关闭，无新阻断项；该复核未运行 PostgreSQL。
- 最终显式双后端六文件矩阵 `test_cash_import_operations.py`、`test_cash_record_policy.py`、`test_cash_import_funding.py`、`test_cash_source_batch.py`、`test_cash_source_document_storage.py`、`test_cash_import_plan_publication.py` → 281 passed、无 skip（58.57 s，`/tmp/ft-cash-import-operations-final.log`）。中间 89／273 项结果不替代此最终矩阵。本步不更改数据库或既有财务写入口，未重复完整财务回归和旧性能矩阵；真实计划的规模成本随 4.8.4.2／6.7 验证。
- `uv build --out-dir /tmp/ft-cash-import-operations-dist`、OpenSpec strict、独立范围复核和 `git diff --check` 通过。
- Cross-platform Impact Check：本步只有内部只读组合及尚未对外生效的安全原因，Web、Native、共享 presentation 的页面、路由、文案和操作均未修改。功能地图补后端证据，页面数及三端缺口不变；实际预计列表、关系组、联合提交、HTTP、Compose 和最终浏览器／Hallmark／演示仍未完成，不能据此勾选 4.8.4 或整体导入完成。

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

- 4.8.3 不可变计划发布（2026-10-21，HEAD／比较基线 `626b374`）：新发布／替换／分页／到期／消费及权限合同先 8 failed，再 8 passed（`/tmp/ft-cash-import-plan-publication-red.log`、`-green.log`）。服务端构建器为显式依赖，当前测试使用合成预计结果；真实业务构建器仍在 4.8.4，未开放新 HTTP 或替换实际导入。计划及选择与会话修订在同一 CAS 对象更新，TTL 保持，公开响应与构建器输入隔离，完整操作只存服务端，分页不截断提交范围。
- 独立复核发现三个 P2：异常明细在 CAS 后才报错导致原计划丢失、未交付明细第一页却返回续页游标、详情及嵌套字段透传。新增 6 个 SQLite 反例先失败（`/tmp/ft-cash-import-plan-publication-review-red.log`）后，全部公开读取结构及摘要先于 CAS 校验；明细从空游标起读，详情／列表／候选等使用公开字段投影。范围复跑 21 passed；补充问题字段丢失 P2，以 2 个反例先失败后保留并校验 `blocking_scope/next_action/message`，文案仅从稳定原因生成，不能转发技术异常正文。
- 清扫后到期计划先 1 failed，再将有效格式但临时对象已清理的访问统一为 `plan_expired`，现存跨用户／工作区对象保护保持；已有收据仍先于临时读取。最后独立复核指出无原因码明细仍可转发问题文本，新增无 code／null code 两例先失败后移除问题扩展字段；独立静态确认全部 P2 关闭，没有扩大到未接入业务构建器。相关红日志 `/tmp/ft-cash-import-plan-issues-red.log`、`/tmp/ft-cash-import-plan-cleanup-red.log`、`/tmp/ft-cash-import-plan-no-code-red.log`。
- 最终五文件显式双后端 `test_cash_import_plan_publication.py`、`test_parsed_cash_import_context.py`、`test_parsed_import_staging.py`、`test_cash_mutation_receipts.py`、`test_parsed_import_cleanup.py` → 260 passed、无 skip、1 warning（67.83 s，`/tmp/ft-cash-import-plan-publication-final.log`）。此前 224／236／240／256 项矩阵是各轮中间证据，不替代最终结果。`uv build --out-dir /tmp/ft-cash-import-plan-publication-dist`、OpenSpec strict、范围 diff 和 `git diff --check` 通过。本步不改 schema／既有财务入口或查询，未重复跑旧财务性能；实际业务计划成本仍须在 4.8.4／6.7 测量。
- Cross-platform Impact Check：本步新增内部计划发布层及未来公开问题格式，没有改 Web、Native、共享 presentation 的现有页面、路由、用户操作或已生效文案；统一 DTO、客户端消费、真实浏览器、最终 Hallmark 和演示仍待 4.11–4.13。功能地图只补后端证据，三端覆盖与页面数不变；完整联合提交、保存点隔离、未知结果恢复仍在 4.9，不能以发布层测试勾选总体 4.8 或实际用户导入完成。

- 4.8.1.1 当前来源页认证（2026-10-21，HEAD／比较基线 `3c3575e`）：新能力先有 20 failed／1 passed（`/tmp/ft-cash-source-documents-red.log`），命名空间断言改为真正检查冲突结果后单独 1 failed（`/tmp/ft-cash-source-document-namespace-red.log`），避免仅比错原因码的空洞断言。47 迁移新增最小认证空页表，页码有界、工作区级联，已有来源表与财务值不变；有已保存证据拒绝有损降级。空页与活动来源绑定共同按渠道／解析空间／导出声明读取，批量按 150 个作用域查询；跳过行和无位置行不产生空页证明，预览只读。
- 独立预审的 P2 “遗漏候选旧导出”与“扁平混用解析空间”均采纳并回写 design：候选 A 的业务页与空页在导入 B 时同样可组合认证；完整性按当前证据重新计算，不信任单行旧标志，不回写旧流水。领域初轮 103 passed（0.86 s），新 SQLite 范围 21 passed／9 deselected（4.69 s）。首次显式双后端 13 文件来源、资金、暂存、账户／关系／草稿及迁移矩阵 528 passed、无 skip（124.33 s，`/tmp/ft-cash-source-documents-dual.log`）。
- 独立实施复核的 P2 草稿绑定迁移仍扁平比较旧页证据已采纳：新增实际迁移合并反例 SQLite 先 1 failed，再使用同一带命名空间冲突函数；两个来源存储合同文件最终显式双后端 98 passed、无 skip（39.92 s，`/tmp/ft-cash-source-document-merge-final.log`）。独立复核另有 25 passed／13 deselected，并以空内存库 EXPLAIN 确认两种读取使用作用域索引，没有把它算成 PostgreSQL 或数据规模性能证据。
- `uv build --out-dir /tmp/ft-cash-source-documents-dist`、OpenSpec strict、范围 diff 和 `git diff --check` 通过；完整 SQLite `/tmp/ft-cash-source-documents-regression.log` 在最后 P2 修复后启动 → 2,497 passed、549 skipped、53 deselected、1 warning（479.20 s），仍沿两项旧 Expo 排除，skip 不算 PG 证据；该轮不包含后来新增的计划发布层。Cross-platform Impact Check：本步只新增内部规划／证据存储，没有修改 Web、Native、共享 presentation 的页面、文案或操作，功能地图补证据而保留三端流程缺口。完整不可变计划、证据保存失败的依赖处理及实际新写入口仍分别在 4.8、4.9、4.11，未因此开放或宣称完成统一导入；浏览器、最终 Hallmark 与演示确认仍未完成。

- 4.5.3.5 当前实例身份（2026-10-21，HEAD／比较基线 `cf9fa6c`）：流水、组成项和关系同字段同修订删除重建的上下文反例先有 SQLite 6 failed／PostgreSQL 6 passed（`/tmp/ft-cash-instance-identity-red.log`）；真正新建 45 版库的迁移反例先有 5 failed／1 passed（`/tmp/ft-cash-instance-migration-red.log`）。46 迁移只在 SQLite 重建三个源表并恢复现存最大 ID 与可用序列的较大值，包含空表；PostgreSQL 沿用原序列。两项关系表达式唯一索引按原定义显式恢复，端点方向、当前引用和来源值不变，降级到 45 保留兼容安全属性。
- 最终独立静态复核无阻断，采纳低序列及活动来源唯一索引行为测试；真正完成首表复制或最后关系索引 DDL 后注入失败，均验证数据、结构、序列、版本与外键状态整体恢复。旧上下文在删除重建前已先通过验证，重建后必须拒绝，避免无效初始令牌的空洞测试。SQLite 严格警告范围 12 passed／7 deselected（6.11 s，`/tmp/ft-cash-instance-identity-green.log`）；追加两项边界后最终显式双后端 16 文件矩阵 498 passed、无 skip、1 warning（239.31 s，`/tmp/ft-cash-instance-identity-dual-final.log`）。此前 432 passed／6 failed 均为旧 HEAD 45 常量断言，修正并复跑，不计作成功证据。
- `uv build --out-dir /tmp/ft-cash-instance-identity-dist`、OpenSpec strict、范围 diff 及 `git diff --check` 通过。完整 SQLite 非性能回归沿两项旧 Expo 排除 → 2,471 passed、535 skipped、53 deselected、1 warning（486.78 s，`/tmp/ft-cash-instance-identity-regression.log`）；源实例改动后独占新建／关系变更性能双后端 → 4 passed、19 deselected（28.23 s，`/tmp/ft-cash-instance-identity-perf.log`），没有并行测试、构建或生产代码改动，既有规模与预算不变。整个 A 类变更最终完整回归与性能仍在 6.1／6.7。Cross-platform Impact Check：本步仅变更源实例持久化身份，没有修改 Web、Native、共享 presentation 的页面、文案或操作，功能地图补迁移合同证据而不改变页面数或三端 TODO；实际浏览器、Hallmark 和重大 UI 演示仍待 4.12–4.13。

- 4.5.3.4 草稿余额及当前报表（2026-10-21，HEAD／比较基线 `a07dae1`）：草稿未知 DTO、普通／批量删除、转正式余额增量、事务回滚及 38 位金额反例先有 12 failed；两个生产报表组合入口在完整夹具修正后均因未知日期失败，共 6 failed。内部行映射加入生命周期和修订，保留未知组成项；余额增量共用无存储 helper，草稿及删除项不产生增量，既有转账口径保持。收支及资金流改读当前正式投影及成员，在同一快照处理月份／全时段流向，未就绪安全返回 503，不回退另一套原始收支计算。
- 独立工程复核的 P2 混合批次草稿覆盖余额时间、部分已知组成项填出父账户均采纳：实际混合删除先证明时间为 `None`，部分未知草稿在两个后端先失败，再分别只从有余额影响的正式行取时间、保留真实父账户。显式 PG 首轮 1 failed／193 passed 定位 `ExactDecimal.normalize()` 默认精度舍入，追加 `cast(String)` 证明库内值正确而 ORM 读错；该项与部分未知反例共 3 failed／1 passed 后修复为精度 80 读取，不改存储或 schema。
- 最后独立精度盘点另发现过渡镜像默认绝对值、退款预算及预览组成项合计仍使用默认精度；新增双后端反例 5 failed 后改为无舍入符号操作、Decimal 80 流式退款累加及高精度合计，不再使用 SQLite 浮点 `SUM`。另一独立上下文对最终 diff、余额 helper 和新测试只读复核，确认前述 P2 关闭、无新阻断；完整草稿维护及父锚点关系删除仍在 4.10，不属于本步入口验收。
- 显式双后端最终范围 `test_cash_draft_balances.py`、`test_cash_current_finance_report.py`、类型／生命周期、组成项、流水维护、过渡导入、CLI 替换 API、多币种、应用查询、图读取、当前上下文／关系及旧导入向导共 14 文件 → 389 passed、无 skip、1 warning（187.94 s），日志 `/tmp/ft-cash-draft-finance-dual-complete.log`。此前不含过渡精度最后三个改动的矩阵 218 passed／无 skip（93.17 s），不视作最后改动证据。
- 完整 SQLite 非性能回归沿既定两项旧 Expo 排除 → 2,454 passed、526 skipped、53 deselected、1 warning（456.46 s），日志 `/tmp/ft-cash-draft-finance-regression-final.log`。该轮在最后三个过渡精度小补丁前启动，后续受影响导入／关系及新增反例由上述最终 389 项覆盖；整个 A 类变更的最终全量门禁仍在 6.1。`uv build --out-dir /tmp/ft-cash-draft-finance-dist`、OpenSpec strict、diff check 通过。下一步实例 ID 红测试单独保留，不包含在本步完整回归或提交中。
- 最终独占双后端性能运行 `tests/test_cash_projection_performance.py tests/test_cash_category_performance.py -q -m performance --disable-warnings --tb=short` → 40 passed、3 个非性能场景 deselected、1 warning（373.13 s），日志 `/tmp/ft-cash-draft-finance-perf-final.log`。固定账本规模、查询和耗时预算均保持，未并行测试或构建；最终增量独立复核确认精度及两个草稿 P2 关闭，无新增阻断。
- Cross-platform Impact Check：本步没有改 Web、Native、共享 presentation 页面或导航，既有报表 HTTP DTO 保持；Compose 当前没有调用该旧报表路径，未就绪错误复用已有安全文案。功能地图补 F-03／F-04 余额及报表证据，页面数与跨端 TODO 保持。浏览器、Hallmark、Native 及重大 UI 演示仍随 4.12–4.13 验收，不把后端合同算成已完成草稿维护。

- 4.5.3.3 人工保护及维护范围（2026-10-21，HEAD／比较基线 `661a3b2`）：新增同值／清空、无来源类型、整笔传播、自动同步、删除分类、待处理隐藏项、删除来源、候选依赖及锁顺序反例，初轮 20 failed／3 passed；普通编辑同值分类的独立 P2 另以 2 failed／1 passed 复现后修复。明确提交与实际改变分开：前者决定整笔分类及并发校验，后者决定父修订及候选失效。删除分类把未删除及已删除引用改成受保护空值，保留来源值及备注；过渡来源合并也尊重分类保护。
- 实现共用工作区锁顺序和来源可见条件；业务隐藏但待处理项可分类／删除，仍拒绝来源删除及跨工作区。批量分类原子失败会回滚父修订、覆盖值、候选、关系修订及投影。独立工程复核关闭同值整笔传播 P2，增量复核未发现阻断；采纳受保护空值／来源值不变和 501 项末批候选反例。新合同最终显式双后端 `tests/contract/test_cash_manual_classification.py -q --tb=short` → 60 passed、无 skip（36.14 s），日志 `/tmp/ft-cash-manual-final-edges-dual.log`；此前 14 文件矩阵 463 passed、1 个性能场景 deselected、无 skip（240.55 s），日志 `/tmp/ft-cash-manual-dual-final.log`。
- 分类性能首轮因逐批读写及候选查询超预算（4 failed／16 passed，删除 72／71 条 SQL），改为适配层 JSON 合并及 `UPDATE RETURNING`，保留其他人工覆盖；完全无变化的专用分类命令不推进父修订／发布版本。第二轮只余 SQLite 删除 13 > 12，复用返回的删除状态后两后端分类性能 20 passed（26.90 s），日志 `/tmp/ft-cash-manual-category-perf-final.log`，未放宽数据规模、查询及耗时预算。补全分类管理 delta、proposal／design 九项能力，独立规格复核无阻断。
- 完整 SQLite 非性能回归沿既定两项旧 Expo 排除 → 2,429 passed、501 skipped、53 deselected、1 warning（496.90 s），日志 `/tmp/ft-cash-manual-regression-final.log`；这些 skip 不计入 PostgreSQL 证据。构建 `uv build --out-dir /tmp/ft-cash-manual-protection-dist`、OpenSpec strict 及 diff check 已通过。新补的下一步草稿余额及报表红测试不属于本次完整回归范围，也不随本步提交。
- 大账本投影性能在独占环境重跑 → 20 passed、3 个非性能场景 deselected（297.01 s），日志 `/tmp/ft-cash-manual-projection-perf-isolated.log`。先前一次编排与短红测试有重叠，已主动终止，不计入证据；固定 10k／1k 规模、20 次采样及既有 100 ms 等预算保持。
- Cross-platform Impact Check：本步改变既有后端分类保护及可维护范围，功能地图登记 F-03／F-04／F-07 的实际证据；Web、Native、共享 presentation 的页面、文案及操作结构均未修改，没有新增页面或独立关系入口。完整新导入／草稿维护和三端最终 QA、Hallmark、演示确认仍待 4.10–4.13，不以内部合同替代客户端验收。

- 稳定身份补充调查（2026-10-21，HEAD `5502719`）：在仓库外隔离 SQLite 完整升级后执行创建、物理删除、重新创建，流水、组成项及关系三个 ID 均被复用，布尔汇总见 `/tmp/ft-cash-id-reuse-probe.log`，没有写真实账本。该行为可能让修订重新从 1 开始的新对象冒充旧上下文目标；补入 4.5.3.5、shared/ledger-records delta 与 design，在新接口切换前处理。它补足已有并发／删除新建合同，不增加历史查询或跨库 ID 等值要求。
- 稳定身份独立计划复核确认与既定范围一致，无需新业务决策；采纳当前父 ID 不变时组成项独立重建、同修订旧令牌真正拒绝、两项关系表达式唯一索引、来源／问题／投影／现金投资引用及 DDL 后失败回滚的覆盖要求。旧非单调表历史高水位不可推回，保证范围明确为新合同启用后，并将失效／排空旧临时上下文及更早降级的重验证写入 design；这不是已实施或真实环境迁移证据。

- 4.5.3.1 当前派生归属（2026-10-21，HEAD／比较基线 `4d993fc`）：新增归属、唯一活动集、发布目标和失败回滚的合同，初轮 SQLite 运行边界 11 failed，先证明旧实现允许错误归属及退休副本。45 迁移增加数据集／投影复合唯一键及子项复合外键、当前指针归属与活动部分唯一索引，状态只保留暂存／活动；先以 `NOT EXISTS` 检查全部归属、指针及活动集合，再显式清理退休子表／父表。升级保留当前主键、来源、组成项及关系引用，降级先拆引用约束，不恢复派生历史。
- 迁移反射首轮每次跟随 FK 读取来源关系表，出现不相关表达式索引警告；限制批量重建仅反射当前派生表后，新合同以 `-W error` 通过。增加真正删除退休副本并完成首张表 DDL 后的失败注入，验证当前及退休数据、版本、约束和 SQLite 外键状态全部恢复；另补跨工作区子项及同工作区指向暂存集反例。首次 PostgreSQL 4 项失败仅因共享夹具的外工作区名称不同，改为模块显式准备外工作区后复跑，不能用 SQLite 成功替代该后端证据。
- 全量写入自查补充目标归属及状态预检，当前／外工作区空构建两个反例先失败，修正后只允许暂存写入且所有删除带工作区条件。独立增量复核发现 P2：`expire_on_commit=False` 可保留旧暂存对象，另一会话发布后，缓存状态可能绕过检查；同时旧指针缓存可能使版本回退及残留副本。顺序双会话反例先有 2 failed／1 passed，再改为先锁工作区、强制刷新状态／目标；SQLite 在没有物理事务时先 `BEGIN IMMEDIATE`，PG 保持 workspace → state 锁顺序，获取工作区锁前禁止 autoflush。独立静态复核确认 P2 关闭，无新增阻断；正常待写数据在刷新前仍按事务 flush。
- 新归属合同最终 SQLite 28 passed、28 PostgreSQL cases 明确排除（13.86 s，`-W error`），日志 `/tmp/ft-cash-projection-ownership-sqlite-cache.log`。锁／缓存最后修正前的显式双后端范围 393 passed、无 skip、1 warning（184.86 s），完整 SQLite 2,392 passed、464 skipped、53 deselected、1 warning（410.53 s），均不视作最后修正后的完整证据，最终结果见下两项。工作区删除独立验证 3 passed／17 deselected；该测试收集时的既有 Starlette 弃用警告不影响迁移 `-W error` 结果。
- 最后锁／缓存修正后的显式专用双后端矩阵：`test_cash_projection_ownership.py`、组成项图／生命周期读取、草稿／关系／账户存储、Alembic 与投影迁移、投影应用／查询、工作区删除、分类、手动转账、实际流水维护、Web 集成及并发，共 24 个文件 → 537 passed、无 skip、1 warning（262.35 s），日志 `/tmp/ft-cash-projection-ownership-dual-cache-final.log`。完整 SQLite 沿前述两项旧 Expo 排除复跑 → 2,401 passed、473 skipped、53 deselected、1 warning（427.36 s），日志 `/tmp/ft-cash-projection-ownership-regression-final.log`。`uv build --out-dir /tmp/ft-cash-projection-ownership-cache-dist`、OpenSpec strict 和 `git diff --check` 通过；skip 不计入双后端验证。
- 最终独占双后端 `tests/test_cash_projection_performance.py -q -m performance --disable-warnings --tb=short` → 20 passed、3 个非性能用例 deselected（334.07 s），日志 `/tmp/ft-cash-projection-ownership-performance.log`；测量期间没有并行测试或构建，全部原预算保留。最终 diff 自查及独立增量复核已覆盖新外键、发布、锁、缓存、回滚及无历史副本；P1／P2 清零，本小步不代表整个导入变更完成。
- Cross-platform Impact Check：本步仅改变后端派生存储、发布与锁边界，Web、Native、共享 DTO／presentation、用户文案及页面数量均未变化；功能地图补归属／回滚证据，原统一导入和草稿维护缺口保持。未修改真实账本或执行发布，实际浏览器及最终 Hallmark 仍在客户端阶段验收。

- 4.5.3.2 组成项证明和局部闭包（2026-10-21，HEAD／比较基线 `a70c46b`）：等额真实分摊及关系解除后的旧组成员反例先失败，再将确认边读入接至共享最终图，保留退款分配键及组成项额度；完整镜像退款与非基准来源的明确分配不丢失。局部维护同时遍历当前旧组和新确认边，草稿／候选不扩张。无合法财务图时全量修复不发布半成品；普通独立项仍使用原位快速更新。
- 严格读入发现旧确认前校验重造父关系而丢失分配身份，改为验证完整组成项最终图；旧影响检查的读取阶段错误也映射到既有业务原因。明确手动还款／换币命令按命令上下文生成兼容类型及关系，不读取自由文本猜类型，不在读入时改写已有源流水。合成关系夹具仅在逐处显式 `complete_legacy_types=True` 时补齐旧默认类型；新增反例证明默认工厂及生产读取均不改写 `other`。旧正例的父金额与组成项金额显式保持一致，不自动修正生产金额。
- 双后端初轮暴露旧跨币种及零额退款夹具不符合已确认角色矩阵；三种不合法跨币种子类型改为必须拒绝，合法两类继续比较可见结果，零额退款正例使用同账户，单边零及异币反例保留。新手动命令测试以创建前后 ID 集合定位，不假定 PostgreSQL 自动序列大于手填 ID。修正后显式专用双后端范围矩阵 418 passed、无 skip、1 warning（188.29 s），日志 `/tmp/ft-cash-graph-reads-dual-final2.log`；完整 SQLite 沿前述两项旧 Expo 排除运行 → 2,373 passed、445 skipped、53 deselected、1 warning（370.55 s），日志 `/tmp/ft-cash-graph-reads-regression-final.log`。skip 不计入双后端验证。
- 独立工程复核关闭读取错误映射和合成夹具两项 P2；增量复核无新增阻断。复核指出旧、新分组交替长链的累计读取风险，80 笔合成反例在原算法以 1,844 个成员查询参数超过 960 上限先失败；改为只展开新到达成员并去重旧分组后转绿，独立只读再审确认完整闭包和成本边界。范围测试另有 69 passed／26 skipped 及新图 SQLite 12 passed／12 PostgreSQL cases 明确排除。未操作真实账本。
- 独占双后端性能初轮 18 passed、2 failed（255.91 s），日志 `/tmp/ft-cash-graph-reads-performance.log`；两项关系变更用例在预热因旧场景把消费／收入作为转账而被拒绝，未进入计时。该场景改为计时前显式准备 46 对合法同账户跨币种端点及对应组成项，仍为 10,000 笔、金额不变，执行 `currency_exchange → cross_currency_remittance` 的真实修改后核对结果；场景标识进入夹具摘要，其他场景、样本次数及全部预算不变。两项独占复跑 2 passed（12.70 s），日志 `/tmp/ft-cash-graph-relation-performance-final.log`；不把初轮说成 20 项一次通过。
- 性能夹具与最终文档独立增量复核确认实际子类型修改、全部财务输入一致、负载及预算未降低，没有新增阻断；该复核只读，不替代上述性能结果。最终 diff 自查确认未改原有来源财务值或开放新草稿写入口。
- `uv build --out-dir /tmp/ft-cash-graph-reads-dist`、OpenSpec strict 和 `git diff --check` 已通过。Cross-platform Impact Check：本步未改 Web、Native、共享 presentation、公共 DTO 或用户文案，当前读取及旧确认入口复用同一财务校验；F-03／F-04／F-05 的统一导入和草稿维护客户端缺口保持，已补功能地图证据，不改变页面数。词表仅将目标说明修正为实施中，既有入口词条待切换后同步；浏览器、最终 Hallmark 和重大 UI 用户演示仍属后续任务。
- 下一步 4.5.3.1 独立存储预审无方案阻断，采纳发布目标必须是本工作区新暂存集、外连接／不存在父行预检，以及降级先删引用 FK 三项保护；工作区删除已经按投影子表、state、dataset 顺序执行。该预审尚不是迁移实施或验证证据。

- 4.5.2.2–4.5.2.3 来源和读取（2026-10-21，HEAD／比较基线 `46d5f58`）：无组成项草稿、独立问题及未确认边的当前标记先失败，再接入父种子、有界确认闭包、状态／创建时间／待处理输入及生命周期发布摘要。问题或草稿关系变化没有推进父修订也拒绝旧全量修复发布；低层保存仍不在组中间态发布。误名的组成项闭包方法统一改为 `confirmed_transaction_closure`，现金编辑、关系及投影维护全部调用同一父流水边界。
- 列表 DTO 保留未知金额／币种／日期及经济类型，默认正式与草稿同列；HTTP 转发 `state/confirmation_status`，月汇总显式要求统计资格，待确认按最终可见投影计数。全额退款的当前问题覆盖业务隐藏，不覆盖账户、金额、日期等显式筛选、工作区或删除边界。列表、成员来源、关系、选项、计数及月汇总在同一 REPEATABLE READ／SQLite 显式读取事务内返回；`sort_at` 同时用于排序和 v2 游标，旧游标须回第一页，版本变化返回 `409 read_snapshot_changed`。SQLite 未知金额比较使用 CASE，未知不当作零。月汇总和格式化保留全部精度，合成 38 位金额反例在 `46d5f58` 失败，在当前实现通过。
- 最终范围自查发现每项版本误用了内部最后构建号，新增未触及项及详情与快照版本相等反例先红，再改为显式传入同一发布版本；外部不消费 `built_projection_version`。独立最终 SQLite 范围 105 passed、36 PostgreSQL cases 明确排除，无新增阻断；独立确认查询优化、可空详情、隐藏组筛选、严格游标、快照及错误码语义，未运行 PostgreSQL 或性能。
- 显式专用双后端初轮功能 308 passed、无 skip、1 warning（139.13 s），日志 `/tmp/ft-cash-lifecycle-reads-dual.log`；迁移及新增发布失效增量 119 passed、无 skip、1 warning（46.95 s）。首轮完整 SQLite 2,359 passed、1 failed、432 skipped、53 deselected：往返夹具包含草稿关系，接入真实 attention 后 43 迁移正确拒绝有损降级。往返用忽略决定验证可逆保存，另保留待确认降级拒绝测试。完整复跑沿前述两项旧 Expo 排除 → 2,361 passed、433 skipped、53 deselected、1 warning（355.62 s），日志 `/tmp/ft-cash-lifecycle-reads-final-regression.log`。
- 独占性能初轮 `tests/test_cash_projection_performance.py -q -m performance` 为 19 passed、1 failed（248.26 s），PostgreSQL 首次月汇总超时。只读 EXPLAIN 证明新 EXISTS 被展开为重复工作区嵌套扫描；调整为根主键限一行及一次删除成员集合，首轮修正仍在 SQLite 形成相关集合，读取 P95 约 211 ms，未通过原 100 ms 预算。明确取消集合自动相关后，SQLite 执行计划从 `CORRELATED LIST SUBQUERY` 变为 `LIST SUBQUERY`，根仍为主键标量查找；最终读路径及分页索引双后端 4 passed（26.20 s），日志 `/tmp/ft-cash-lifecycle-read-perf-final.log`，不提高预算、不依赖数据库 ANALYZE。测量没有并行运行其他测试或构建。
- 查询和版本修正后，显式专用双后端最终功能矩阵在上述初轮文件基础上加入 `test_cash_draft_storage.py`、`test_cash_draft_projection_storage.py` → 375 passed、无 skip、1 warning（160.62 s），日志 `/tmp/ft-cash-lifecycle-reads-final-dual.log`，覆盖所有受影响读取、普通编辑、API、SQLite／PostgreSQL 集成及迁移。`uv build --out-dir /tmp/ft-cash-lifecycle-reads-dist`、OpenSpec strict、最终 diff 与 `git diff --check` 通过；全库未配置后端的 skip 不计入双后端证据。
- Cross-platform Impact Check：后端 HTTP 的可空展示、状态、筛选和分页错误合同已变化，Web、Native 及共享 DTO／presentation 均需在 4.11–4.13 接入；本步未改客户端页面、交互或用户可见文案，新增草稿写入口仍关闭。功能地图保留 F-03／F-04／F-05 的客户端缺口及三端 TODO，不增加页面数。实际浏览器、最终 Hallmark、Native 与重大 UI 演示仍未完成。独立预审发现的旧报表、草稿删除内部行、余额精度、人工同值／清空和隐藏组操作缺口已写回 4.5.3；不将本步列表汇总等同全部统计及余额完成。

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
