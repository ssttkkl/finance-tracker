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

**Output During Implementation**

原查询测试的状态可由原测试函数执行到首次查询前准备，拦截只能用于停止生成，不得伪造结果。重建交错保留原退款插入、原关系工厂的类型／修订／角色与真实发布；不要以单纯改金额替代。SQLAlchemy 的单条 CTE 文本适配为同一 jOOQ 读取连接／快照内的一次状态查询及关系读取，并保留原全部结果／版本／旧游标断言。月份计算次数只检查 SELECT 列，不能把 ORDER BY 中的字段误当成所选列。JVM 无环境 Decimal 精度，须直接测试生产累加器的精确结果及独立低精度对照，不虚构可变全局上下文。

当前账本列表必须同时保留游标与绝对序号分页、完整筛选绑定和版本围栏。首个无版本序号页返回筛选后月份位置及精确汇总；带版本后续页省略月份信息，只在 SQL 计算总数／待确认数，不能物化全账本 DTO。未知草稿占位置但不参加统计，全局筛选选项不随当前筛选收缩。列表项复用原字段呈现，不引入详情专用的成员／问题校验；分类树须在同次快照中仅加载一次，并以实际 SQL 查询次数回归。原 Python `int()`、FastAPI 布尔、ASCII JSON v2 游标与各数据库安全错误分别保留，不把 SQLite／PG 的原错误差异猜成同一结果。

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

后端常规检查通过 `scripts/run_backend_tests.py` 按文件确定分片，同一片内顺序运行；汇总检查只接受所有分片成功。真实 PostgreSQL 分片必须使用各自独立的专用 `_test` 数据库，不得并发重置同一库。分类性能夹具包含稳定收支对象及完整成员；公共备注校验 `entry.fields` 和对象修订，同时保护来源原文与来源修订。导入性能继续计时完整预览与提交，不以内部规划耗时替代；预算调整须有明确授权，并按剩余失败场景单独定义，不连带放宽通过项。

同次导入预览可从完整校验的私有暂存快照更新计划，原解析文件以不可变 JSON 字节保留；不得把可变 DTO 的对象身份当作文件未变化证明。完整重建可复用同事务已验证来源快照的数量，但发布前仍重算来源摘要并核对真实数量，普通仓库发布入口继续完整校验。

无关联现金流水的性能优化仍须先完成事实、组成项和资金引用校验，保留原投影输出及错误翻译；类型子类走通用算法。小批当前对象联合读取必须保护 ORM 会话中的待写和旧成员状态，跨工作区、禁用 autoflush、缺对象及大批次继续原合同。调用内资金解析缓存先检查原始参数类型并限制大小，不得跨文件或调用复用。无旧记录的可靠来源新增快路径逐行执行原完整字段校验，重复编号、多文件、弱证据及文档分页回通用规划。

JSON 预算的普通容器快路径必须与原算法差分核对异常、剩余预算、深度和自定义协议；未知类型及预算自身引用完整回退。组成项与资金外连接保留无资金组成项、全部当前资金行和跨批次覆盖。投影写后仍读取完整上下文及对象版本，不能因性能省略损坏成员的错误检查。

性能优化优先缩短私有准备图与最终图的生命周期，不暂停全局 GC 或修改阈值。只读详情快路径保留操作引用、成员归属、组成项和错误次序，索引键或未知协议回原算法。调用内成功财务校验缓存必须覆盖精确输入类型、字段存在性、全部组成项及 Decimal context，仅缓存完整校验成功，限制大小并在调用结束释放。

For the active Kotlin backend migration, category-delete acceptance uses the
explicit `FT_KOTLIN_CATEGORY_DELETION_CONTRACT_DIR` fixture directory in addition
to the existing fixture variables. Prepare target and oracle databases with
`scripts/export_category_deletion_contracts.py`; both must be new isolated test
databases. Compare all financial columns after failed and successful commands,
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

解析会话迁移分别验证编解码、存储端口和真实运行时接线。无回包发布仍须完整核对文件深度与页证明；私有快照持有不可变字节，不信任可变 DTO。公开页缓存逐次读取当前对象，按条件版本和内容摘要隔离，限制条数与总字节，不能遮蔽存储失败。源用例参数化内存/R2 时，两种端口共同运行生命周期断言；条件对象 fake 不代表真实 R2 已接入。

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
