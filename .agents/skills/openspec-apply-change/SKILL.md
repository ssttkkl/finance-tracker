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

当前维护协议适配位于 `compose/shared/src/commonMain/kotlin/com/finance/tracker/data/CashMaintenanceApi.kt`，领域接口位于同根 `domain/CashMaintenanceModels.kt`。账本维护与记账编辑的实现记录见 `openspec/changes/archive/2026-10-28-compose-cash-ledger-maintenance/` 和 `openspec/changes/archive/2026-10-28-compose-cash-entry-editor/`；现金导入页面的实现及验证记录见 `openspec/changes/archive/2026-10-28-compose-cash-import-workflow/`。导入选择编码必须区分必填 `previous_plan_id:null`、字段补丁中的明确清空，以及未使用时应省略的 `common_fields`／`inherit_common_fields`；相应 HTTP 回归位于 `CashImportPlanApiTest.kt`。

现金导入收据的 `status` 保留既有兼容语义；新页面用 `commit_outcome` 区分预览已知问题与提交新增异常，并用 `has_business_writes` 判断实际保存事实。旧收据缺省时不补算，接口细节见 `docs/import/cash-import-redesign-contracts.md`。

来源问题正文更新以 `pending_source_payload_present` 和归一后的 `pending_source_payload` 共同判断；缺省或不可判断不视为清空。

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

现金导入公开分页的性能证据必须区分冷读与热读。公开响应缓存仅可保留已过滤的 JSON 字节，并逐次核对真实暂存对象、归属、TTL、完整计划引用及收据消费；不能作为写入或重试会话的执行输入。

当前导入入口为共享 `CashImportTaskScreen`；新增导入行为不得恢复旧四步或 scan／preview／commit 客户端。核对复用主页表格与只读详情，文件选择和来源账户映射分步呈现，导入不提供字段、来源和关系编辑。旧协议编辑能力不因此删除；第一版 UI 演示确认前不把最终验收标为完成。结果按实际账单行统计，提交新增异常只给统一错误、安全原因和返回，不提供逐行失败处理。演示同样保留未知 null、来源只读及普通草稿保存语义，转正式需完整信息和有效现金类账户。

导入来源差异的 `source_payload` 和 `components` 必须使用结构化只读展示，不能交给标量格式器；明确空组成项显示既有空值提示。完全相同的 JSON 差异无需呈现，不在客户端推断金额或时间的业务等价。

演示账本的 `composition` 同样只承载真实关系种类；单笔为空数组，完整转账为 `transfer_pair`。`single`／`combined` 是筛选值，资金分配 `components` 不构成关系，不能据此显示“已合并”。

现金 UI 接入新协议前，先比较修改前已验收的 Compose 源码和实际截图，保留金额／币种组合、详情信息层级及固定操作等已有结构。接口字段顺序不决定表单顺序；全部可编辑项按业务分组展开，资金分配与记账字段同组。正常、实际额外影响与错误状态都须核对唯一主要操作，不能只审正常截图。Material 规则按项目技能的官方链接正文核对，颜色对比使用实际主题配对和可执行回归，不能因使用原生 M3 控件便推断已通过。

合入索引账本能力时，序号／月份位置涵盖全部匹配的正式与草稿流水，金额汇总仅采用统计资格；沿用稳定排序和未知时间桶，不能按月份首次出现定位不连续的同月区间。同步迁移当前协议的夹具和测试，不为满足旧测试恢复已退役导入、详情或关系写入口。

查询性能优化先核实服务实例的真实生命周期；HTTP 按请求构建服务时，热缓存基准不能代替冷请求分析。缓存仅优化读取，不承担可见性或计数正确性；携版本的冷页只聚合所需数量，不加载约定省略的完整月份数据。

导入错误定位使用 `ft.cash_diagnostics` 的局部 scope，转换／规划只附上下文，最终 HTTP、文件隔离或提交包装才产生编号和脱敏调用帧日志；不输出异常正文或 SQL 参数。`row` 与 `related` 精度不可混淆，持久端点不能猜测本次文件，批量不拆分重跑；最终提交只给 request 范围。Compose 只消费 `error.diagnostic`／文件 `diagnostic` 白名单并只读显示，不写恢复存储或增加逐项补救。协议见 `docs/import/cash-import-redesign-contracts.md` 的错误定位章节。
现金导入的呈现合同见 `openspec/changes/archive/2026-10-28-compose-cash-import-workflow/`：从账本打开全屏模态任务，保留兼容地址、恢复与退出保护。后续 UI 修改仍按 `AGENTS.md` 的演示确认循环交付第一版并逐轮处理反馈。


## 后端性能与 PR 分片验证

后端常规检查通过 `scripts/run_backend_tests.py` 按文件确定分片，同一片内顺序运行；汇总检查只接受所有分片成功。真实 PostgreSQL 分片必须使用各自独立的专用 `_test` 数据库，不得并发重置同一库。分类性能夹具包含稳定收支对象及完整成员；公共备注校验 `entry.fields` 和对象修订，同时保护来源原文与来源修订。导入性能继续计时完整预览与提交，不以内部规划耗时替代；预算调整须有明确授权，并按剩余失败场景单独定义，不连带放宽通过项。

同次导入预览可从完整校验的私有暂存快照更新计划，原解析文件以不可变 JSON 字节保留；不得把可变 DTO 的对象身份当作文件未变化证明。完整重建可复用同事务已验证来源快照的数量，但发布前仍重算来源摘要并核对真实数量，普通仓库发布入口继续完整校验。

无关联现金流水的性能优化仍须先完成事实、组成项和资金引用校验，保留原投影输出及错误翻译；类型子类走通用算法。小批当前对象联合读取必须保护 ORM 会话中的待写和旧成员状态，跨工作区、禁用 autoflush、缺对象及大批次继续原合同。调用内资金解析缓存先检查原始参数类型并限制大小，不得跨文件或调用复用。无旧记录的可靠来源新增快路径逐行执行原完整字段校验，重复编号、多文件、弱证据及文档分页回通用规划。

JSON 预算的普通容器快路径必须与原算法差分核对异常、剩余预算、深度和自定义协议；未知类型及预算自身引用完整回退。组成项与资金外连接保留无资金组成项、全部当前资金行和跨批次覆盖。投影写后仍读取完整上下文及对象版本，不能因性能省略损坏成员的错误检查。

性能优化优先缩短私有准备图与最终图的生命周期，不暂停全局 GC 或修改阈值。只读详情快路径保留操作引用、成员归属、组成项和错误次序，索引键或未知协议回原算法。调用内成功财务校验缓存必须覆盖精确输入类型、字段存在性、全部组成项及 Decimal context，仅缓存完整校验成功，限制大小并在调用结束释放。
