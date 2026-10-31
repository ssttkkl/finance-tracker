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

当前维护协议适配位于 `compose/shared/src/commonMain/kotlin/com/finance/tracker/data/CashMaintenanceApi.kt`，领域接口位于同根 `domain/CashMaintenanceModels.kt`。页面接入任务由 `compose-cash-ledger-maintenance`、`compose-cash-import-workflow` 与 `compose-cash-entry-editor` 承载。导入选择编码必须区分必填 `previous_plan_id:null`、字段补丁中的明确清空，以及未使用时应省略的 `common_fields`／`inherit_common_fields`；相应 HTTP 回归位于 `CashImportPlanApiTest.kt`。

现金导入收据的 `status` 保留既有兼容语义；新页面用 `commit_outcome` 区分预览已知问题与提交新增异常，并用 `has_business_writes` 判断实际保存事实。旧收据缺省时不补算，接口细节见 `docs/cash-import-redesign-contracts.md`。

来源问题正文更新以 `pending_source_payload_present` 和归一后的 `pending_source_payload` 共同判断；缺省或不可判断不视为清空。

Kotlin 来源选择迁移须保留事项标识的字符串精确匹配；协议可接受的 19 位整数文本不一定能转为 Long，不能在事项查找前改变原拒绝类型。正文摘要严格对照 Python 有序 UTF-8 JSON；测试中的一般浮点数按类型、完整值及负零比较，金额文本、摘要和错误仍逐字核对。来源纯规则不代替详情可选动作的完整准备校验或维护事务。

来源正文派生只更新备注及单行解释，身份保留可信解释白名单，不从正文恢复卡号或来源账户。PDF 表头去 Python 空白后重名列全部剔除；原始字段值及明确人工备注保持。支付宝理财渠道的派生不改写金额。可选来源动作须继续执行完整准备，不因这些纯规则通过而默认允许采用。

普通草稿保存使用 `save_draft` 维护计划，保留 draft 状态并复用原计划核实。是否额外确认取维护计划顶层完整 `additional_effects`；明确空数组才可省略，缺省时提示重新核对，不走旧版本确认路径，不能从 `can_commit` 或首屏详情猜测。

导入及维护提交新增异常使本次业务写入全部回滚，不创建失败子集、派生会话或逐行补救流程；草稿明确矛盾须在表单和后端拒绝，合法缺项保持可保存。

已知修订／过期拒绝或明确 `commit_state=not_committed` 直接报错供用户重新读取后重试；不自动合并或替换修订重放。只有结果未知及已消费／幂等冲突保留最小原键核实。

草稿财务表单仅发送实际修改的标量，不展开旧完整 `components`；原子草稿唯一现金部分由后端基于采用来源后的有效值联动。来源明确的组成项（含空数组）与用户明确分摊不得隐式重造。

当前账本列表的 `transaction_id` 是打开流水详情的明确引用；`record_id` 是账单来源编号。不得用来源编号调用内部流水接口，也不解析 `projection_id` 猜目标。客户端与提供该字段的后端一起验收，不增加旧响应执行分支。

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

导入错误定位使用 `ft.cash_diagnostics` 的局部 scope，转换／规划只附上下文，最终 HTTP、文件隔离或提交包装才产生编号和脱敏调用帧日志；不输出异常正文或 SQL 参数。`row` 与 `related` 精度不可混淆，持久端点不能猜测本次文件，批量不拆分重跑；最终提交只给 request 范围。Compose 只消费 `error.diagnostic`／文件 `diagnostic` 白名单并只读显示，不写恢复存储或增加逐项补救。协议见 `docs/cash-import-redesign-contracts.md` 的错误定位章节。
现金导入的呈现合同以 active change 为准：从账本打开全屏模态任务，保留兼容地址、恢复与退出保护。按 `AGENTS.md` 的 UI 演示确认循环交付第一版及每轮反馈。


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
