# 后端设计评审：govern-cash-relation-model

## 元信息

- 模式：增量，实施中补审。
- 评审上下文：独立只读子代理 `/root/backend_design_review`；未参与实现，未修改代码、测试、规格或报告文件。
- 日期：2026-10-30。
- 比较基线：`origin/main`，`bff18d48eaa4a2793296d327b34aa0476aa868d1`。
- 受审 HEAD：`5dec89568abee290acf7f85573a382c3d6ac79f9`。
- 规则版本：`docs/design-review/backend/rules.md` @ `bff18d48eaa4a2793296d327b34aa0476aa868d1`。
- 范围：三类现金关系去除 `subtype`、共同硬约束、金额图、代表与身份、迁移、应用写入口及后端读写合同。
- 授权边界：用户允许先实现后端和数据库、最后处理前端。Kimi 原型尚未确认；本报告不要求立即修改 Compose，但客户端同步切换仍是最终交付门禁。
- 相关未提交文件：
  - 领域：`src/ft/domain/cash_projection.py`、`cash_relation_graph.py`、`cash_relation_policy.py`，以及 `relations/core/keys.py`、`relations/core/types.py`、`relations/pipeline.py`、`relations/refund/hard_key.py`、`relations/transfer/match.py`。
  - 存储：`src/ft/adapters/relational/cash_entries.py`、`cash_relation_context.py`、`cash_relations.py`、`models.py`、`projections.py`、`repositories.py`、`web_queries.py`。
  - 应用：`src/ft/application/` 下的 `cash_current_relation_plan.py`、`cash_draft_merge.py`、`cash_import_business_plan.py`、`cash_import_plans.py`、`cash_ledger.py`、`cash_manual_associations.py`、`cash_manual_transfer_edit.py`、`cash_plan_execution.py`、`cash_projections.py`、`cash_record_maintenance.py`、`cash_relation_context.py`、`cash_relation_type_updates.py`、`cashflow.py`、`relations.py`、`statement_import.py`、`web_queries.py`。
  - 协议：`src/ft/mapping.py`、`src/ft/web/cash_maintenance_protocol.py`、`cash_plan_protocol.py`、`routes.py`。
  - 未跟踪迁移：`migrations/versions/20261030_52_cash_entry_identity.py`、`20261030_53_cash_relation_model.py`、`migrations/cash_relation_v3/`。
  - 测试：`tests/cash_projection_assertions.py`、三份 `tests/test_cash_{projection,relation_graph,relation_policy}.py`、`tests/test_application_web_queries.py`，以及 `tests/contract/` 中当前关系计划、公共编辑、导入业务计划、人工关联、投影图读取、关系上下文、Web API、身份迁移测试。
  - Artifact：`DOMAIN_GLOSSARY.md`、`openspec/changes/govern-cash-relation-model/`。

## 输入与检索

已阅读 `AGENTS.md`、项目后端评审 Skill、最新规则和模板、项目上下文、change 的 proposal/design/tasks 和全部五项 delta，并结合主规格相关合同检查实现冲突。已阅读相对基线的领域、存储、应用、协议和测试改动，以及未跟踪迁移。

跨层检索包括：

| 概念 | 检索与调用链 |
|---|---|
| 关系合法性 | `infer_relation_roles`、`discover_relation_roles`、`build_cash_relation_graph` → 当前计划／人工新增／导入规划 → `CashRelationGraphService` → 当前及兼容仓储 |
| 金额覆盖 | `applied_amount`、`refund_allocation_key`、`economic_amounts` → 完整图 → 投影重建与局部维护；另查 `relations._projection_validation_reason` |
| 代表和身份 | `representative_key`、`record_id`、来源 binding／occurrence、`identity_transaction_id`、`entry_id`、`projection_id` → 列表、当前定位、详情及证据查询 |
| 模型切换 | `subtype`、`transfer_subtype`、`business_label`、`contract_version`、`rules_version` → 暂存计划、提交重验、永久收据、HTTP DTO 和 Compose 序列化 |
| 迁移 | revision 52/53、冻结算法、业务唯一索引、候选、资金占用、entry 成员及旧投影 |
| 生命周期 | 确认、拒绝、解除、草稿转换、手动转账创建／编辑、删除引用、投影发布、原幂等键核实 |

已查看的关键调用方包括：

- `cash_import_commit.py`、`cash_mutation_commit.py`、`cash_plan_execution.py`。
- `cash_current_relation_plan.py`、`cash_manual_associations.py`、`cash_manual_transfer_edit.py`。
- `cashflow.py`、`statement_import.py`、`relations.py` 及其仓储调用。
- `cash_plan_routes.py`、`routes.py`、`cash_maintenance_protocol.py`、`cash_plan_protocol.py`。
- Compose 共享数据映射、关系模型及相关调用处；只核对协议影响，未执行 UI 审查或要求提前移植。

证据缺口：

- revision 53 尚无完整历史数据升级、候选方案、差异校验和中断恢复矩阵的当前通过证据。
- 稳定 `cash-entry:` 读取合同、模型版本失效、来源稳定排序尚缺覆盖本报告场景的应用／HTTP 契约测试。
- Agent 文档与客户端同步切换尚未完成；未将其认定为当前已可用。
- 未对全部历史兼容应用方法逐一证明外部可达性，因此不建议直接删除整套旧服务。

未覆盖区域：不适用；本次是增量评审，不是全量后端审查。

## 变更清单

### 数据库

| 表／字段 | 归类 | 责任与评审结果 |
|---|---|---|
| `cash_entries.identity_transaction_id` | 持久业务身份状态 | 与展示代表分离有必要；迁移以旧有效成员初始化。新对象选择和对外定位仍有 F-06。 |
| `cash_entries.root_transaction_id` | 展示投影引用 | 保留为当前代表引用合理，不应继续决定公开稳定标识。 |
| `transaction_relations.subtype` 删除 | 删除混合业务表达 | `kind`、端点、角色和记录分类已能表达共同财务语义，不新增替代 `variant` 合理。 |
| 关系唯一索引去除 `subtype` | 业务唯一性约束 | 镜像按对称端点、其他关系按方向规范化；迁移提前检查碰撞合理。 |
| `transaction_relations.applied_amount` | 派生金额／已确认分配 | 镜像完整覆盖、退款完整分配、转账为零；仍需保持原决定及兼容零值解释的验证证据。 |
| `cash_projection_relations.subtype` 删除 | 查询投影收敛 | 与当前关系模型一致。 |
| `cash_projections.business_label` | 查询投影 | 由完整事件事实派生，用于展示及筛选；不是关系写入事实。 |
| `cash_relation_migration_audit` | 历史快照 | `relation_id`、`workspace_id`、`legacy_subtype`、`migration_version` 仅保留迁移审计，不参与当前校验，职责合理。 |
| 投影状态版本与可用性 | 流程状态／缓存版本 | 迁移先关闭旧投影读取合理；不能代替计划模型版本校验。 |

### 接口

- 人工新增请求删除 `subtype`，保留实际端点、当前修订、`kind` 和共同值；由服务端推导角色。
- 关系计划、详情和查询响应删除关系 `subtype`。
- 列表筛选及选项改为 `business_label`／`business_labels`。
- 原幂等键、永久收据和现有提交入口保留。
- 设计要求扩展的搜索 `kind` 上下文及可选现金部分／不适用原因，当前路由和服务仍未完整实现。
- 当前读取仍以代表派生的 `cash:{transaction_id}` 为主，见 F-06。

### 数据模型

- `CashRelationGraphEdge`、`ProjectionRelation`、`RelationProposal` 去除 `subtype`。
- `CashProjectionFact` 增加记录类型和子类型，作为业务标签输入。
- `CashProjection` 增加 `economic_root_ids`；完整图提供独立 `economic_amounts`。
- 已验证投影来源携带 `CashProjectionBuild`，避免重建时丢失完整组成项计算结果。
- `ProjectionDTO`、`ProjectionFilters` 和筛选选项使用业务标签。
- 发现证据与确认角色推导分离合理，但旧父级金额校验和失败状态处理尚未收敛。

## Findings 摘要

| ID | 优先级 | 标题 | 状态 |
|---|---|---|---|
| F-01 | P0 | 非法候选仍进入持久待处理／部分成功路径，显式决定未统一整体拒绝 | open |
| F-02 | P0 | 未消费旧计划没有模型／合法性版本失效门禁 | open |
| F-03 | P0 | 迁移把逐行候选的交叉组合误当成完整替代方案 | open |
| F-04 | P1 | 旧父级投影校验与共同金额图重复，已误拒绝合法组合 | open |
| F-05 | P0 | 展示代表排序仍受随机来源记录 ID 影响 | open |
| F-06 | P0 | 稳定 entry 身份尚未贯通公开定位，新身份锚仍由代表决定 | open |

优先级采用本仓库定义；其中 P0 包含已确认的契约破坏，不表示这些问题都已导致线上数据损坏。

## Finding 详情

### F-01 [P0] 非法候选仍进入持久待处理／部分成功路径，显式决定未统一整体拒绝

- 状态：open。
- 位置：
  - `src/ft/application/cash_import_business_plan.py:595`
  - `src/ft/application/cash_import_business_plan.py:886`
  - `src/ft/application/cash_plan_execution.py:58`
  - `src/ft/application/cash_plan_execution.py:207`
  - `src/ft/application/cash_plan_execution.py:216`
- 需求与现有设计证据：design §1 和交易关系 delta 要求非法自动候选被丢弃并记录脱敏告警；明确用户请求不合法时整体失败。当前转换失败进入 `relation_failures`，图校验失败除 `origin == "manual"` 外均设置 `action="skip"`；后续保存 `relation:` 待处理事项和 failed 关系结果。`origin="current"` 或导入计划中的显式接受没有同等拒绝分支。
- 持续成本或失效场景：自动生成的聚合转账、跨账户镜像等非法候选可能令本来合法的流水出现待处理状态和部分成功收据。用户显式接受两个互斥方案时，失败也可能被表达为可提交的业务跳过，而不是整个输入被拒绝。
- 建议改法及代价：在计划中明确区分自动默认建议和用户显式决定。自动非法提交组恢复原图、整体丢弃，仅输出固定脱敏告警；显式新增／接受非法结果直接抛稳定业务错误，禁止进入可提交计划。复用现有事务和告警能力，不新增待处理模型。代价为调整规划、结果生成及对应双后端契约测试。
- 实现方反馈：已确认问题并新增红回归；报告截点尚未修改对应实现。
- 评审结论：冲突可由完整调用链证实，保留 open；修复后须核对无待处理事项、无失败结果及显式非法输入无业务写入。

### F-02 [P0] 未消费旧计划没有模型／合法性版本失效门禁

- 状态：open。
- 位置：
  - `src/ft/application/cash_import_plans.py:411`
  - `src/ft/application/cash_import_plans.py:483`
  - `src/ft/application/cash_mutation_plans.py:166`
  - `src/ft/application/cash_mutation_plans.py:209`
  - `src/ft/application/cash_mutation_commit.py:150`
- 需求与现有设计证据：design Migration Plan 第 7 步及两项 delta 明确要求未消费旧计划因模型／合法性版本变化而失效。两类计划仍写 `contract_version=1`；读取及提交检查计划身份、修订和重算结果，但未检查关系模型／合法性版本。投影 `RULES_VERSION` 已升级，未进入计划版本判断。
- 持续成本或失效场景：对本轮规则变化结果恰好相同的旧计划，重算比较不能证明已重新核对；旧字段结构可能在其他路径触发通用异常，而不是统一的重新核对错误。只读内存检查确认 `_checked_plan` 接受旧 `contract_version=1`。
- 建议改法及代价：为计划增加并验证明确的关系模型／合法性版本，读取、继续预览及提交统一使旧版本失效。保留现有“先查询永久收据、再加载计划”的顺序，成功旧命令仍可按原键核实。代价较小，但须补旧未消费计划、旧成功收据、过期暂存和未知结果恢复测试；不需要迁移永久收据为新命令。
- 实现方反馈：待提交修复或说明。
- 评审结论：现有比较不是版本门禁，保留 open。

### F-03 [P0] 迁移把逐行候选的交叉组合误当成完整替代方案

- 状态：open。
- 位置：
  - `migrations/versions/20261030_53_cash_relation_model.py:96`
  - `migrations/versions/20261030_53_cash_relation_model.py:113`
  - `migrations/versions/20261030_53_cash_relation_model.py:119`
  - 对照 `src/ft/application/cash_current_relation_plan.py:107`
- 需求与现有设计证据：迁移合同要求逐个完整替代方案叠加当前生效图，不能同时校验互斥方案。当前迁移对每条关系的候选作 `product(*options)`，要求每个组合全部合法；当前规划器同样枚举时会丢弃不合法组合，仅保留完整合法方案。两者对“方案”的解释不一致。
- 持续成本或失效场景：已有三组各自合法的消费／退款对，候选是将第一组同时镜像到第二组或第三组。两套完整方案都合法，但“消费选第二组、退款选第三组”这种交叉组合非法。内存复现中完整组合 `(3,4)`、`(5,6)` 均通过，交叉组合 `(3,6)`、`(5,4)` 均报 `relation_endpoint_occupied`；迁移将因此阻断合法历史候选集合。当前错误报告还只列活动确认关系 ID，不能定位触发失败的候选组。
- 建议改法及代价：冻结与当前规划一致的完整方案构造逻辑，保留候选行和历史意图，区分“不是方案的交叉组合”与“应保留但无法表示的完整方案”。输出受控工作区、关系组／候选引用及错误码。代价中等，须补双后端合法多方案、非法完整方案、缺端点历史状态及候选规模边界测试；不应删除候选来使迁移通过。
- 实现方反馈：待提交修复或说明。
- 评审结论：这是迁移算法与既有完整方案语义的具体冲突，保留 open；未执行数据库迁移复现。

### F-04 [P1] 旧父级投影校验与共同金额图重复，已误拒绝合法组合

- 状态：open。
- 位置：
  - `src/ft/application/relations.py:644`
  - `src/ft/application/relations.py:694`
  - `src/ft/application/relations.py:780`
  - `src/ft/application/relations.py:1115`
  - 对照 `src/ft/domain/cash_relation_graph.py:240`
- 需求与现有设计证据：目标要求发现和确认使用共同财务合法性。`plan_relation_proposals` 仍经 `_projection_validation_reason` 将组成项转换成父级 `ProjectionRelation`，直接调用 `build_cash_projections`，没有完整镜像金额基准及退款分配等价键。当前正式图已由 `build_cash_relation_graph` 维护这些事实，形成两套金额解释。
- 持续成本或失效场景：消费父金额 -100，现金部分 -30/-70；退款父金额 +40，两份真实退款部分各 +20；新增 -30 银行镜像。完整共同图正确得到 -60，旧发现门禁却返回 `projection_invalid`，误把两份相等真实分配按旧启发式折叠。该结论已用纯内存输入复现。
- 建议改法及代价：将发现最终合法性适配到同一完整图输入，保留真实组成项、角色、退款分配及资金占用。确认全部调用方已迁移后收敛旧金额适配，不能仅删除校验。代价中等，涉及预览／兼容应用调用和金额回归；需要关注原有局部校验性能预算。
- 实现方反馈：待提交修复或说明。
- 评审结论：重复设计已产生可观察分歧，保留 open。

### F-05 [P0] 展示代表排序仍受随机来源记录 ID 影响

- 状态：open。
- 位置：
  - `src/ft/domain/cash_projection.py:187`
  - `src/ft/domain/cash_projection.py:243`
  - `src/ft/domain/cash_source_matching.py:570`
  - `src/ft/adapters/relational/cash_relation_context.py:80`
  - `migrations/versions/20261030_53_cash_relation_model.py:68`
- 需求与现有设计证据：design §6 要求稳定来源身份包含来源类型、业务身份及作用域、稳定出现标识；不得由随机 ID 或导入顺序决定代表。当前排序仅使用来源优先级、时间、`source_type`、`record_id`。无来源编号的正式 `record_id` 由 `allocate_source_record_id` 生成 `import-v1:<uuid4>`；已有 `CashSourceBindingModel` 的作用域／出现身份没有传入投影事实，迁移也未读取。
- 持续成本或失效场景：同来源、同时间的两条合法人工镜像，默认描述取决于随机 UUID。内存复现中仅交换分配的 UUID，代表由 A 变为 B；业务事实未变。缺失来源身份的存量也不会被当前预检识别。
- 建议改法及代价：复用来源 binding 和 occurrence 合同构造独立、不可编辑的稳定排序键，贯通已保存事实、虚拟预览事实及冻结迁移；明确多个绑定的选择规则。无法证明稳定身份时按设计报告阻断，不能回退数据库 ID 或随机 UUID。代价中等，需要额外批量来源身份读取及其性能验证，不应新增与已有来源绑定重复的永久业务身份列。
- 实现方反馈：待提交修复或说明。
- 评审结论：随机值已进入目标明确禁止的经济代表选择，保留 open。

### F-06 [P0] 稳定 entry 身份尚未贯通公开定位，新身份锚仍由代表决定

- 状态：open。
- 位置：
  - `src/ft/domain/cash_projection.py:386`
  - `src/ft/adapters/relational/projections.py:437`
  - `src/ft/adapters/relational/web_queries.py:221`
  - `src/ft/adapters/relational/web_queries.py:310`
  - `src/ft/adapters/relational/cash_entries.py:171`
- 需求与现有设计证据：design §6 明确当前读模型以 `cash-entry:{entry_id}` 定位，代表另存；旧 `cash:{transaction_id}` 按原流水解析当前对象。当前构建仍生成 `cash:{root_id}`，存储和 DTO 原样使用，当前查询只按该字符串等值查找。新 entry 的 `identity_transaction_id` 同时被设为展示 `root`，而设计要求按稳定来源身份选锚，与代表分离。
- 持续成本或失效场景：成员不变、代表改变时数据库 entry ID 可保留，但公开 `projection_id` 仍变化，旧详情／证据引用不能通过原标识读取。新建组的未来拆分归属也仍由当时展示代表间接决定。
- 建议改法及代价：在 entry reconcile 后将稳定 entry 引用贯通当前投影、列表、详情、证据和结果定位；旧来源引用通过成员解析当前对象，并维持删除和工作区边界。新锚采用与代表独立的稳定身份选择函数。代价中等，需后端定位／合并／拆分／删除契约测试；客户端消费按已授权顺序留到原型确认后同步切换，不要求现在实施 Compose。
- 实现方反馈：待提交修复或说明。
- 评审结论：身份持久化已部分实现，但对外合同和新对象规则仍冲突，保留 open。

## 已检查并保留的边界

- 删除关系 `subtype` 而不另存 `variant` 合理；记录分类继续为发现规则提供输入，业务标签独立派生。
- 镜像同账户、同币种、完整等额及同父单射；退款按组成项等价类计入；多部分聚合转账拒绝，均保护具体财务消费者。
- `identity_transaction_id` 与当前代表不是不必要重复：一个负责对象延续，一个负责默认展示。
- `business_label` 作为可重建查询投影有明确筛选消费者，且目前不参与共同写入合法性。
- 迁移冻结算法副本有必要，不建议引用未来会变化的运行时实现。
- 独立投资资金关系、余额策略、人工公共值及成员独立恢复值继续分开，符合现有职责。
- 提交先读取永久收据再加载计划的结构应保留；F-02 不要求重执行历史命令。
- 最新 `routes.py:64` 已显式拒绝退役的 `transfer_subtype` 查询参数，本轮不再将静默忽略该字段列为 finding。
- revision 52 测试已改为从历史 schema 构造数据；不要求为了测试放开 revision 53 的禁止无损降级边界。

## 范围外问题

- 旧 `RelationService` 等仍含历史 CLI 文案和兼容应用方法。尚未完整证明所有外部调用均退役；不将整体删除或全面重构列为本次要求。
- 现有 `Session.flush(objects=...)` 弃用警告不影响本次关系语义结论，不强制本轮处理。
- 草稿与“待确认”重复标签是用户明确保留在范围外的议题；不借本次关系模型治理扩大状态模型改造。

## 结论

结论：阻断。

本次识别 6 项未关闭 finding。共同金额图及身份分离方向合理，但发现校验、失败结果生命周期、计划版本、迁移候选和公开稳定定位尚未形成一致合同，不能据此归档或发布。

以下是尚未完成的交付范围，未单独作为一般待办类 finding：

- revision 53 的完整预检差异报告、公共人工值／成员恢复值／分币种总额／余额核对及实际升级测试。
- 搜索接口的 `kind` 上下文和可选组成项／不适用原因。
- Agent 协议、词表、功能地图与当前文档收尾。
- 原型确认后的客户端同步切换、Web 冒烟及适用原生编译／单测。

验证边界：

- 评审只执行只读检索、`git diff --check` 和 `.venv/bin/python -B` 纯内存复现；未写数据库、工作树或报告文件。最终快照的 `git diff --check` 通过。
- 最新 HEAD 下重新复现了旧计划版本检查缺口、旧发现金额门禁分歧、UUID 影响代表排序。候选交叉组合的内存复现在前一 HEAD 执行；第二次合并仅更新工作流规则，相关实现未变。
- 已阅读 `/tmp/govern-post-main-core.txt` 的 `173 passed / 17 skipped`，以及旧双后端日志中的 `64 passed / 6 failed`；后者失败发生在身份测试仍尝试禁止降级时。实现方随后报告身份测试 6 项通过、查询测试 66 项通过；这些不作为本评审执行的最终验证。
- 未运行完整后端回归、revision 53 双后端迁移矩阵、性能／安全专项、构建、Compose 或真实浏览器 QA。现有测试结果不能替代上述未完成门禁。

本结论仅对记录的 HEAD、比较基线及相关未提交状态有效。后续实现修改须按同一 finding ID 独立复核。

## 复核记录

- 第 1 轮：完成实施中增量补审，提出 F-01 至 F-06，全部为 open。评审期间第二次合并仅更新工作流及后端审查规则，已读取新规则并核对相关应用代码。
- 实现方已开始补 F-01 红回归，并完成退役查询参数拒绝及身份迁移测试入口修正；未据此关闭任何 finding。
- 后续复核：待实现方提供修复、当前命令证据及受审版本。
