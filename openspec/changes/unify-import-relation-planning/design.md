## Context

现有 CLI 关系入口在 [RelationService.check](/Users/huangwenlong/.hermes/skills/finance/finance-tracker/src/ft/application/relations.py) 中先读取完整现金事实，执行 Phase A 平台退款硬配对，再执行 `run_relation_phases` 的 B-D 阶段并持久化结果。现有 Web 预览在 [CashLedgerCommandService](/Users/huangwenlong/.hermes/skills/finance/finance-tracker/src/ft/application/cash_ledger.py) 内部重新拼装事实，只调用 B-D，并且预览事实未始终携带转换阶段的 `source_payload` 和 `offset_role`。Web 确认又通过 [StatementImportService](/Users/huangwenlong/.hermes/skills/finance/finance-tracker/src/ft/application/statement_import.py) 直接应用页面关系决定并关闭导入后的关系检查。

本变更必须保持 `Decimal` 金额、来源行快照、业务行幂等、工作区隔离、关系端点互斥、SQLite / PostgreSQL 等价和投影原子发布。现有 `match_phase_a_platform_refunds` 已经是无副作用的领域匹配函数，可作为统一规划的 Phase A 基础；需要把当前应用层的持久化编排与纯扫描编排分开。

## Goals / Non-Goals

**Goals:**

- 让 Web 预览、Web 确认、CLI 导入和独立关系检查共享同一关系规划器。
- 使转换阶段的结构化退款元数据完整进入预览和确认的关系事实。
- 使预览结果可稳定引用、可重算、可校验，并在上下文变化时失败关闭。
- 让关系决定、现金幂等写入和投影刷新在确认 Application Service 的同一事务内完成。
- 保留 CLI 的自动接受、待审核和关系规则语义，并兼容 Web 的拒绝、手动选择和跳过决定。

**Non-Goals:**

- 不新增关系类型、改变退款金额语义、调整候选时间窗口或重写现有领域匹配规则。
- 不把用户可见的 Web 关系页面改成新的信息架构；只调整响应字段和后端结果来源。
- 不迁移既有流水、既有活动关系或既有账户映射，不自动修复历史错误关系。
- 不把 CLI 的独立关系检查命令改造成新的后台任务或引入新的持久化批次。

## Decisions

### 1. 以 RelationPlan 作为唯一关系结果中间表示

在应用关系服务内抽取两类入口：

- `plan_in_uow(...)`：读取现有事实、可选的预览事实和活动关系，返回只读 `RelationPlan`。
- `apply_plan_in_uow(...)`：在已有导入事务中验证并应用计划和用户决定，保存关系并统一刷新投影。

`RelationPlan` 包含标准化端点引用、关系类型、subtype、状态、`rule_id`、受限证据、稳定 `proposal_key` 和计划上下文摘要。规划阶段不能调用关系仓储写入或投影维护。

`RelationService.check()` 保留 CLI 和现有命令合同，但内部改为在一个 UoW 中执行 `plan_in_uow` 后用 CLI 默认决定调用 `apply_plan_in_uow`。`StatementImportService` 在现金确认事务中直接调用同一组 in-UoW 方法，避免重新开启嵌套 UoW。

备选方案是让 Web 继续调用 `run_relation_phases`，只在外围补 Phase A。该方案会继续复制上下文、占用集合和应用层冲突规则，拒绝采用。

### 2. 统一事实装配，预览事实使用虚拟引用

规划器接收统一的事实装配结果，而不是接收 Web 自己拼出的简化字典。每条预览流水保留：

- 标准记录字段、精确 `Decimal` 金额、币种、账户和规范发生时间；
- `source_type`、`record_id`、`source_payload`；
- 平台状态、交易号、`offset_group`、`offset_role` 等转换元数据。

预览新流水使用不透明的虚拟事实引用，确认时通过 `(source_type, record_id)` 映射到幂等写入后的真实 `fact_id`。已有流水继续使用真实事实引用。Web 不接触来源规范账户键或原始敏感账号。

备选方案是只在 Web 的 `_fact_view_from_row` 上补一两个字段。该方案不能保证 Phase A 的详细行视图、剩余退款和未来新增渠道一致，拒绝采用。

### 3. 固定 Phase A → B-D 且移除无效开关

规划器固定按以下顺序执行：

```text
完整事实快照
  → Phase A 平台退款硬配对
  → 装载已确认关系、剩余退款和占用集合
  → Phase B payment_mirror
  → Phase C transfer / repayment
  → Phase D diamond / merchant refund
  → 关系计划
```

Phase A 的提案生成继续复用 `match_phase_a_platform_refunds`；其结果不在扫描中直接写库，而是进入统一计划。`DefaultRefundTextGates`、别名索引、精确金额、关系图冲突和 `_persist_proposal` 当前已有的状态升级/保留规则必须被规划器和应用器共同使用。`run_relation_phases` 中当前未生效的 `skip_platform_import_refund_seeds` 不再作为 Web 保护开关；如果保留参数，必须使其语义真实可测试，否则移除。

### 4. 预览和确认绑定同一计划上下文

预览响应除了文件摘要外，返回关系计划摘要。摘要覆盖规则版本、导入业务行身份、相关现有事实的稳定字段摘要、活动关系状态、别名证据版本以及工作区关系上下文。确认时服务端重新解析文件、重新应用映射、重新规划并比较摘要；不一致返回现有导入陈旧错误合同的关系专用错误，不接受旧计划。

用户决定使用稳定 `proposal_key` 和来源业务行引用传递，不使用 `preview-relation:<index>` 或数据库代理键作为唯一合同。确认前仍必须再次校验工作区、活动事实、端点互斥、关系类型、精确金额和候选合法性。

备选方案是只比较文件 `preview_digest`。它无法发现账本或关系在预览后变化，拒绝采用。

### 5. 在导入事务内合并用户决定

确认事务的顺序固定为：

1. 加锁并验证幂等键、映射版本、账户和来源输入。
2. 创建或更新本次确认允许的账户映射和现金流水，保留已存在业务行的当前账户与事实。
3. 以实际现金事实重新构造并验证 `RelationPlan`。
4. 应用用户决定：自动建议默认接受，手动选择必须命中合法候选，跳过保存为待审核或等价可审查状态，拒绝保存稳定拒绝决定。
5. 保存关系并在所有关系决定完成后只刷新一次受影响投影。
6. 任一步骤失败则回滚账户映射、流水、关系、余额和投影。

CLI 无用户决定时采用默认策略：唯一强证据自动关系接受，多候选或证据不足保持 `pending_review`；Web 只是在同一计划上叠加使用者决定。

### 6. 保留旧接口作为薄兼容层

`CashLedgerCommandService` 继续负责文件、会话 token、账户映射和响应序列化，但不再实现关系匹配规则。`StatementImportService` 保留已有 `relation_decisions` 输入和幂等响应字段，内部把决定转换为统一应用器输入。CLI 外层命令和 Web API 路由尽量不变，避免扩大公共接口迁移范围。

### 7. 在导入边界规范化来源时间

解析器输出的正式 `occurred_at` 必须在来源边界完成规范化：带 offset 的值保留其绝对时刻，无 offset 的值按导入渠道声明的来源时区解释，再统一输出 UTC。当前现金渠道使用中国大陆来源时区；工银亚洲使用其账单来源时区。`source_payload` 仍保留原始时间列，不把规范化字段写回来源快照。关系摘要对无法识别来源的旧式手工行按既有 UTC fallback 规范化，使预览和持久化边界的表示一致；本变更不迁移历史记录。

### 8. 稳定业务行排序是关系规划合同的一部分

关系规划不得使用数据库自增 ID 或 `preview:` 前缀作为候选 tie-breaker。事实、详细来源行、阶段 seed、镜像代表和候选 evidence 按规范发生时间、来源渠道、`record_id`、账户及最后的运行时 ID 排序；预览和确认使用相同排序键。关系建议列表与计划摘要都按稳定 `proposal_key` 排序，防止仅因虚拟事实换成真实事实而改变阶段占用、退款 `remaining_before` 或证据。

### 9. CLI 组合支付按行白名单跳过

`import_composite_payment_unresolved` 是支付宝来源账户扫描中唯一允许逐行跳过的组合支付错误。数据库映射 CLI 过滤这些行后继续映射、导入其余行，并在 `OperationResult.details` 与 CLI 成功输出中返回跳过数量和错误码；若文件所有业务行均为该错误，返回零新增的成功结果并明确计数。任何其他来源身份、映射、解析或持久化错误仍然整批失败，且不得写入部分流水。

### 10. 原始来源快照与关系派生元数据分栏保存

转换器产生的 `source_payload` 只保存原始账单业务行；`offset_role`、`offset_group`、`offset_strength`、`offset_match_type` 和 `offset_rule_hint` 等由转换或关系规划产生的值写入独立的 `relation_metadata` JSON 字段。事实装配和 `FactView` 同时暴露两类数据，退款角色门禁优先读取 `relation_metadata`，并对旧的测试事实保留从 `raw_payload` 读取的兼容回退。

选择独立字段而不是把派生值重新塞进 `source_payload`，是为了满足来源行快照不可变且可审计的合同；选择 JSON 而不是继续增加多个 nullable 列，是为了让不同渠道的结构化退款元数据可以增量演进，同时保持 SQLite 和 PostgreSQL 的同一写入语义。迁移列允许为空，不对既有历史流水猜测回填；同一业务行重新导入时，若原始来源未变但派生元数据变化，仍更新该字段并触发关系重新规划。

### 11. 退款时间解析统一为可比较的 UTC 语义

平台退款匹配器接受标准化的 ISO 8601 值、旧式空格分隔值和仅日期值。带 offset 的值先转换为 UTC 再以无时区内部值参与差值比较，无 offset 的旧值保留既有来源本地语义；任何无法解析的值仍视为没有时间证据，不得抛出或凭字符串顺序配对。

选择在领域边界集中解析，而不是要求每个来源解析器输出多种格式，能够覆盖已经落库的标准化字符串和旧来源快照；不改变来源快照本身，也不改变候选窗口和精确金额规则。

## Risks / Trade-offs

- [Phase A 从直接持久化改为计划应用可能改变旧状态时序] → 先保留现有 `_persist_proposal` 的业务键、人工决定保护和状态升级语义，增加 CLI 字符化结果对照测试。
- [预览虚拟事实与确认真实 ID 映射错误] → 只允许 `(source_type, record_id)` 映射，提交前重新查询幂等目标并拒绝缺失、跨工作区或目标账户冲突。
- [预览后账本变化导致用户频繁刷新] → 只摘要与本次计划相关的事实和活动关系；变化时明确返回陈旧错误，不静默猜测。
- [用户拒绝决定与现有关系仓储业务键冲突] → 使用既有可审计 `rejected` 状态或等价拒绝记录，并禁止覆盖人工决定；增加重复扫描回归测试。
- [CLI 与 Web 事务边界改变] → 保持 CLI `RelationService.check()` 独立调用合同，同时为导入服务提供 in-UoW 入口；SQLite 使用现有写锁，PostgreSQL 使用工作区锁和唯一约束验证。
- [旧关系或历史错误关系影响新计划] → 本变更不自动修复历史关系，只按现有活动关系、人工决定和端点互斥规则计算；历史修复另行处理。

## Migration Plan

1. 先添加关系规划、微信退款抢占、预览只读、拒绝持久化、重复导入和陈旧计划的失败回归测试。
2. 抽取共享规划器和 in-UoW 应用器，先让 CLI `check` 通过同一规划器，保持旧入口可用。
3. 切换 Web 预览到共享规划器，确认响应中的稳定决定标识和预览摘要。
4. 切换 Web 确认和现金 CLI 导入到同一事务应用器，移除 Web 专属关系匹配和关闭关系检查的路径。
5. 运行 SQLite 合同矩阵、真实 PostgreSQL 合同矩阵、受影响 Python/Vitest 测试和 Web 浏览器 QA。
6. 回滚时恢复旧编排入口即可；新规划器不写入独立事实表，失败确认不会留下部分数据，已持久化历史数据不自动回迁。
7. 为 `cash_transactions` 增加可空的 `relation_metadata` JSON 列，SQLite 和 PostgreSQL 均通过同一应用写入；降级只移除该派生列，不删除来源快照、现金流水或关系历史。旧记录不回填，后续重导入可按业务行幂等更新派生元数据。

## Open Questions

无。规则阶段顺序、预览/确认陈旧策略、用户决定语义和事务边界已由本次确认确定。
