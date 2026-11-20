# design-system Specification

## Purpose

为 Compose Finance Tracker 提供以 Material 3、`FinanceTheme` 和 Git token 为基础的可审计设计系统。Compose 页面直接使用 Material 3 交互控件；FinanceTheme 保留项目颜色、字体与语义 token。
## Requirements
### Requirement: Compose pages call Material 3 controls directly

Compose 页面 MUST 在调用位置直接使用 Material 3 按钮和文本输入控件，不得使用 Finance 按钮/输入框包装层或 Compose Unstyled 交互控件。按钮 MUST 使用适当的 Material 3 filled、outlined、tonal 或 text 控件；文本输入 MUST 使用 Material 3 `TextField` 或 `OutlinedTextField`。调用点 MUST 显式表达业务回调、状态、字段语义和校验行为。控件尺寸 MUST 沿用 Material 3 默认行为并按内容自适应；调用点 MUST NOT 仅为统一尺寸而固定控件宽高或最小宽高。父布局可按需要约束控件填充空间，响应式容器可使用最大宽度。

#### Scenario: Cash Ledger renders a primary action

- **WHEN** 收支账本需要新增记录操作
- **THEN** 页面 MUST 直接调用 Material 3 按钮
- **AND** 按钮 MUST 保留业务回调、启用状态、标签、无障碍信息与稳定语义 ID

#### Scenario: Control dimensions follow Material 3 defaults and content

- **WHEN** Material 3 控件在不同文案长度和窗口宽度下布局
- **THEN** 控件 MUST 使用 Material 3 默认尺寸与内容自适应行为
- **AND** 调用点 MUST NOT 用固定宽高或最小宽高压缩标签或覆盖组件默认尺寸
- **AND** 父布局 MUST 只施加完成当前布局所需的填充或响应式最大边界

#### Scenario: Text input retains field behavior

- **WHEN** 使用者在认证、账本、导入或管理页面输入内容
- **THEN** 调用点 MUST 直接使用 Material 3 文本输入控件并保留标签、输入类型、校验、错误和禁用行为
- **AND** 输入 MUST 保留原有单行/多行约束、状态更新、语义 ID、失焦处理与可访问名称
- **AND** 普通字段 MUST 明确设置为单行；备注与描述等原多行字段 MUST 保持多行

### Requirement: FinanceTheme preserves project colors and typography

`FinanceTheme` MUST 将现有 Finance 颜色、字体和 Material 3 color scheme/typography 提供给 Compose 控件。调用点可读取 Git token 表达 Material 3 默认方案未涵盖的业务状态；不得另造独立的全局色彩或字体系统。交互蓝 MUST 保留现有蓝色风格，按浅深主题分别配置与前景配对；按钮小字及页面操作文字对其背景至少达到 4.5:1，焦点边界对字段背景至少达到 3:1。

#### Scenario: Light and dark themes use shared semantic roles

- **WHEN** Finance UI 在浅色或深色主题下渲染同一控件
- **THEN** 控件 MUST 从当前 `FinanceTheme` 解析颜色与字体
- **AND** 错误、成功、警告、禁用和焦点状态 MUST 使用确定的语义角色

#### Scenario: 蓝色操作在浅深主题中可读

- **WHEN** 蓝色按钮、操作文字和焦点边界在当前主题中展示
- **THEN** 蓝色与其前景及页面背景分别满足文字与边界对比要求
- **AND** 所有颜色从 Git token 和生成副本消费，不在页面硬编码补丁或将浅色蓝直接套入深色文字

### Requirement: Git token files are the only token input

设计 token MUST 以 Git 中的结构化 token 文件为唯一输入，提供 `background`、`foreground`、`surface`、`overlay`、`accent`、`danger`、`warning`、`field` 和 `focus` 等语义角色。生成的 Compose Kotlin token MUST 是运行时消费副本；页面结构、样式和 Material 3 控件选择 MUST 以 Compose 实现为事实源。

#### Scenario: Token changes are consumed by Compose

- **WHEN** token 文件修改浅色或深色主题中的语义颜色
- **THEN** token 生成结果 MUST 更新 Compose Kotlin 消费副本
- **AND** Compose 控件 MUST 使用同一语义角色，不得依赖并行设计资源作为运行时来源

### Requirement: Shared Finance display helpers remain addressable

设计注册表 MUST 记录仍由 Compose 多页面共享的 FinanceTheme、选择器、过滤区、数据表和金额展示辅助函数，并能解析其代码位置与状态合同。注册表 MUST NOT 要求按钮或文本输入使用 Finance 包装组件。

#### Scenario: Resolve a shared Finance display helper

- **WHEN** 页面需要使用共享数据表或金额展示
- **THEN** 注册表 MUST 返回其 Compose 实现与适用状态
- **AND** 页面 MUST 继续直接使用 Material 3 控件完成按钮和输入任务

### Requirement: Compose dialogs follow Material 3 task and interaction guidance
Compose modal dialogs MUST open in response to a user action and stay focused on one task. Short decisions and focused input MUST use a basic dialog; workflows explicitly presented as modal tasks with a sequence of steps MUST use a full-screen dialog at compact breakpoints and a basic dialog at regular or wide breakpoints, except where a more specific product contract below requires a different presentation. Cash record detail and editing MUST share a full-screen task at compact breakpoints and a right-side detached modal Side Sheet at regular and wide breakpoints. Side Sheets MUST retain their previously approved outer width bounds; when detached, they MUST have 16 dp margins at the viewport top, bottom, and logical end. Basic dialogs MUST not exceed 560 dp in width. Cash import MUST use its dedicated main-content task page, and current cash maintenance MUST follow its module contract for allowed actions, busy/unknown exit, errors, relation choices and unsaved work. No independent relationship composer is provided. Dialog titles and actions MUST communicate the task and its outcome. Custom dialog containers MUST expose their task title as an accessible pane title. A dialog MUST preserve staged values when dismissed or cancelled, except that live query controls such as ledger filters apply their selections immediately and closing their container does not roll back those filters. Dialogs MUST keep the background unavailable while open and MUST offer a clear way to leave when the task permits dismissal. All task operation buttons MUST remain in a bottom action area. Full-screen and basic dialogs MUST keep their existing action order; a modal Side Sheet MUST align its bottom actions to the left and place its primary action first (leftmost), as specified by the approved product contract. Every non-AlertDialog modal MUST expose a close button on the left side of its header; AlertDialog MUST retain its component-provided actions without action icons. A full-screen task's body MAY scroll while its title and close affordance remain fixed. A compact full-screen dialog MAY present a basic dialog above it for a nested focused task such as editing, discard confirmation, or acknowledgement of a failed submission; a basic dialog MUST NOT present another basic dialog above itself.

#### Scenario: A user cancels a staged value selection
- **WHEN** a user changes a staged value in a dialog and chooses cancel, presses Back/Escape, or dismisses the dialog without confirming
- **THEN** the application MUST retain the value that was present when the dialog opened
- **AND** the dialog MUST close without applying the unconfirmed value

#### Scenario: A user confirms a staged value selection
- **WHEN** a user changes a staged value and activates the dialog's confirm action
- **THEN** the application MUST apply the selected value through the existing screen state update
- **AND** the dialog MUST close after the confirming action is accepted

#### Scenario: Live query filters are changed in a modal filter surface
- **WHEN** a user selects a detailed ledger filter value
- **THEN** the selected filter MUST apply immediately to the ledger query
- **AND** closing the filter surface MUST preserve the applied filter values

#### Scenario: A multi-step task is presented at each breakpoint
- **WHEN** a user starts a task that requires moving through multiple stages, explicitly presented as a modal workflow
- **THEN** compact presentation MUST use a full-screen dialog
- **AND** regular and wide presentation MUST use a basic dialog no wider than 560 dp
- **AND** any scrollable content MUST keep the title and final actions visible

#### Scenario: A compact full-screen task has fixed bottom actions
- **WHEN** a user edits a record in a compact full-screen dialog
- **THEN** the confirming action MUST remain in a fixed bottom action area
- **AND** the dismissive action MUST be to its left and MUST return to the prior task state or request discard confirmation when there are unsaved changes
- **AND** the header MUST retain the title and a top-left close affordance
- **AND** the body MAY scroll without hiding the title, close affordance, or bottom actions

#### Scenario: A user dismisses a full-screen task with unsaved work
- **WHEN** a user attempts to close, navigate Back, or press Escape on a full-screen task that contains unsubmitted work
- **THEN** the task MUST request discard confirmation according to its owning module's exit contract
- **AND** current cash editors MUST preserve the panel and staged values beneath one Material 3 discard confirmation dialog
- **AND** choosing the dismissive action MUST keep the task and all staged values intact
- **AND** confirming discard MUST leave the task without committing its unsubmitted data

#### Scenario: A cash record detail and edit share a responsive modal container
- **WHEN** a user opens a read-only cash record detail
- **THEN** compact presentation MUST use a full-screen task and regular or wide presentation MUST keep the right-side detached modal Side Sheet
- **AND** switching from detail to edit MUST replace the layout inside the same modal container without opening a separate edit dialog
- **AND** the detail body MUST scroll independently while its title, top-left close action, and bottom detail actions remain visible
- **AND** the regular and wide panels MUST have 16 dp top, bottom, and logical end margins while retaining the existing logical start gutter
- **AND** applying the detached margins MUST preserve the previously approved panel widths
- **AND** the regular and wide panels MUST use `surfaceContainerLow`, the theme's Large shape, and zero tonal elevation
- **AND** tapping the modal scrim MUST dismiss the panel using the current task's exit behavior
- **AND** dragging the panel toward its logical end edge MUST dismiss it using the current task's exit behavior
- **AND** the primary Edit action MUST remain in the bottom action area; in a modal Side Sheet it MUST be the leftmost action, while the full-screen task MUST keep its existing action order
- **AND** the background ledger MUST remain unavailable until the panel is closed

#### Scenario: A modal cash record panel follows the Material 3 Side Sheet contract
- **WHEN** a user opens cash record detail or edit at regular or wide size
- **THEN** the task MUST be displayed as a modal Side Sheet detached 16 dp from the viewport's top, bottom, and logical end edges
- **AND** the sheet MUST use `surfaceContainerLow`, the theme's Large shape, and zero tonal elevation
- **AND** the scrim MUST block interaction with the ledger and support dismissal
- **AND** dragging the sheet toward its logical end edge MUST dismiss it
- **AND** the top-left close affordance MUST remain visible while the body scrolls
- **AND** all task operation buttons MUST remain fixed at the bottom, align left, and place the primary button leftmost

#### Scenario: A cash record short confirmation uses a Material 3 confirmation dialog
- **WHEN** a user requests record deletion or an allowed relationship removal from current cash detail
- **THEN** deletion MUST use its Material 3 confirmation dialog and show the actual deletion scope
- **AND** relationship maintenance MUST confirm its actual effects within the existing detail task, including explicit inheritance of manually chosen common values when required
- **AND** canceling confirmation MUST preserve the same detail without changing records or relationships
- **AND** destructive final actions MUST remain visible and use the theme's error color role

#### Scenario: A user opens a read-only investment detail
- **WHEN** a user opens investment event evidence or a holding's position details
- **THEN** every window size MUST use a basic dialog with a maximum width of 560 dp
- **AND** the title and dismiss action MUST remain visible while the detail body scrolls
- **AND** the dialog MUST NOT use the full-screen variant because the task has no staged edits or multi-step workflow

#### Scenario: A user attempts to discard an edited cash record
- **WHEN** a user changes a cash record draft and attempts to cancel, navigate Back, press Escape, or close the editor
- **THEN** one Material 3 discard confirmation dialog MUST block the existing editor panel while preserving its staged values at every breakpoint
- **AND** choosing the dismissive action MUST keep the draft and editor available
- **AND** confirming discard MUST discard only the unsaved draft and return to the record detail without issuing a save request

#### Scenario: A user attempts to close an import while it is processing
- **WHEN** a user attempts to leave the current import task while a request is processing
- **THEN** the existing task MUST remain open and show one Material 3 waiting notice
- **AND** dismissing the notice MUST preserve the task and MUST NOT cancel or repeat the request
- **AND** after processing the module's actual result or unknown-result exit contract MUST apply

#### Scenario: A required field in an edit dialog is empty
- **WHEN** a user clears a required account name in the new-account editor
- **THEN** the apply action MUST be disabled in compact, regular, and wide presentations
- **AND** the disabled state MUST be exposed through the action's accessibility semantics
- **AND** restoring a non-empty name MUST enable the apply action without discarding the draft
#### Scenario: Current cash commands keep their task-specific guards
- **WHEN** a current cash import or maintenance command is busy, unknown or rejected
- **THEN** the owning module MUST retain its current confirmation, safe retry, exit and error contract
- **AND** generic modal guidance MUST NOT restore removed relation creation or old cash write paths

#### Scenario: A relationship composer is an independent modal task
- **WHEN** a user opens the relationship composer from cash record detail
- **THEN** the composer MUST be presented as its own M3 modal task, with compact full-screen presentation and a detached right-side modal Side Sheet at regular and wide breakpoints
- **AND** only the composer task MUST be the active modal while it is open
- **AND** canceling or completing the composer MUST return to the previous cash record detail without losing its committed data

#### Scenario: A cash record save fails
- **WHEN** saving a cash record fails with a general error while the editor remains open
- **THEN** the editor task panel MUST be temporarily suspended and one Material 3 error acknowledgement dialog MUST be the only active modal at every breakpoint
- **AND** the save action MUST remain available for retry when the draft is still valid
- **AND** acknowledging the error MUST restore the editor with its draft available for correction or retry
- **AND** if the editor can be dismissed while the save is in progress, the owning ledger page MUST apply the post-dismissal feedback rule

#### Scenario: A confirmation request is in progress
- **WHEN** a dialog's confirm action has started an asynchronous save, import, or relation operation
- **THEN** the confirm action MUST remain available and communicate the in-progress state
- **AND** the dialog's dismissive action MUST remain available
- **AND** repeated confirmation MUST NOT start a duplicate operation
- **AND** dismissing the dialog MUST NOT imply that an accepted operation was rolled back
- **AND** the owning task MUST apply the post-dismissal feedback rule when a later result arrives

### Requirement: Custom Compose dialog containers use Material 3 surface and structure
Custom Compose basic dialogs MUST provide an inner Material surface container, use the current `MaterialTheme` shape and surface color roles, and render above one scrim without drawing a second competing scrim. The container MUST remain within the available window, MUST not exceed the basic dialog maximum width of 560 dp, and MUST keep its title and primary actions visible when its body scrolls. Custom-positioned basic dialogs on wide windows MUST respect at least 56 dp of screen-edge margin. Every non-AlertDialog modal MUST put its close button at the top-left; task operations MUST remain in a bottom action area with the primary action rightmost. AlertDialog action buttons MUST contain text without icons. Read-only details and short confirmation tasks SHOULD use a basic dialog, except where a more specific product contract requires a full-screen task or right-side panel. A full-screen dialog MAY host a basic dialog above it for a focused nested task; a basic dialog MUST NOT host another basic dialog, and MUST instead use an in-place state unless a more specific product contract requires a separate M3 AlertDialog as the sole active modal. Standard Material 3 dialogs and pickers MUST retain their component-provided structure and defaults unless a documented product requirement requires an override.

#### Scenario: A custom basic dialog contains scrollable content
- **WHEN** dialog content is taller than its available viewport
- **THEN** only the content region MUST scroll
- **AND** the dialog title and final actions MUST remain visible
- **AND** assistive technology MUST be able to identify the dialog by its task title

#### Scenario: A custom basic dialog contains short content
- **WHEN** the dialog content fits within the available viewport
- **THEN** the dialog MUST wrap to its content and fixed header/actions
- **AND** it MUST NOT fill the remaining screen height with unused space

#### Scenario: A record detail body is scrollable
- **WHEN** a cash record detail is open and its facts or related-record section exceeds the visible body area
- **THEN** the detail body MUST scroll independently
- **AND** the primary edit action and more-actions menu MUST remain fixed in the bottom action area, with Edit rightmost and More actions to its left
- **AND** the two direct actions MUST NOT be repeated inside the scrolling body

#### Scenario: A compact full-screen dialog contains a long task
- **WHEN** a compact full-screen task's body exceeds the visible viewport
- **THEN** the body MUST scroll independently
- **AND** the header MUST retain its title, dismissive action, and confirming action

#### Scenario: A dialog is rendered in light or dark theme
- **WHEN** the same dialog is shown under either FinanceTheme color scheme
- **THEN** its container, text, scrim, and actions MUST resolve through the corresponding Material 3 theme roles
- **AND** the container MUST remain visually distinct from the obscured page without a manually duplicated overlay

#### Scenario: A modal task needs a confirmation, edit, or error state
- **WHEN** a task already uses a full-screen or basic dialog and needs to confirm discard, edit a subtask, or acknowledge an error
- **THEN** a compact full-screen task MAY show a basic dialog above its full-screen container when that task contract allows nested focused work
- **AND** a regular or wide basic task MUST switch its existing container to the required in-place state unless a more specific product contract requires a separate M3 AlertDialog as the sole active modal
- **AND** a basic dialog MUST NOT open a nested `AlertDialog`, `DatePickerDialog`, or basic `Dialog`
- **AND** canceling or acknowledging the nested/in-place state MUST return to the prior task with its staged values intact

#### Scenario: A temporary menu is open above a dialog
- **WHEN** a user presses Escape while a menu or picker menu is open inside a dialog
- **THEN** the topmost menu MUST close first
- **AND** the underlying dialog MUST remain open with its current task state intact
- **AND** a subsequent Escape MAY dismiss the dialog according to that task's dismissal rules

#### Scenario: A read-only non-ledger detail is shown at a compact breakpoint
- **WHEN** a user opens a read-only investment detail with no staged edits or multi-step task
- **THEN** the application MUST use a basic dialog rather than a full-screen dialog
- **AND** the body MAY scroll while the title and acknowledgement action remain visible

#### Scenario: An edited cash record is discarded
- **WHEN** a user attempts to cancel, navigate Back, press Escape, or close a cash record edit with unsaved changes
- **THEN** the application MUST show a Material 3 discard confirmation dialog as the sole active modal
- **AND** canceling MUST restore the same edit container and preserve its draft
- **AND** confirming discard MUST discard only the unsaved draft and return to the cash record detail

#### Scenario: A date or time picker is opened from a modal task
- **WHEN** a user opens a date or time picker from a full-screen or basic dialog, including a filter surface or record editor
- **THEN** the picker MUST expand within the current dialog content without opening a second basic dialog
- **AND** its selected value MUST remain staged until the user confirms
- **AND** canceling or dismissing the picker MUST preserve the value that was present when it opened

### Requirement: Dialog actions represent one explicit decision
A dialog action area MUST present at most one confirming action and one dismissing action, except when a single acknowledgement is the entire task. When a task offers several possible outcomes, the dialog MUST collect that choice in its content before presenting one final confirming action. The confirming action MUST be placed after the dismissing action in the logical action order. A dismissing action MUST remain enabled; asynchronous work already accepted by a confirming action MAY continue after the dialog is dismissed. 确认界面关闭后，明确未执行且原处可继续处理的失败 MUST 通过所属任务的 Snackbar 提示一次。需要核实写入结果、刷新事实或纠正输入时，所属任务 MUST 持续显示真实状态与可用恢复动作。确认弹窗仍打开时 MUST 在弹窗内呈现对应失败。 Destructive confirmations MUST name the operation and use the error color role for the confirming action; dismissing MUST leave the underlying data unchanged.

#### Scenario: A destructive task offers multiple scopes
- **WHEN** a destructive operation has more than one scope or outcome
- **THEN** the dialog MUST present the scope as a selection within its content
- **AND** it MUST provide one destructive confirm action and one cancel action

#### Scenario: A destructive confirmation is dismissed
- **WHEN** a user cancels or dismisses a destructive confirmation
- **THEN** no deletion or relationship change MUST be applied unless the user had already confirmed and started that asynchronous operation
- **AND** the user MUST remain able to return to the prior task state

#### Scenario: A destructive confirmation is accepted
- **WHEN** a user confirms a clearly named destructive operation
- **THEN** the application MUST call the existing operation with the selected scope
- **AND** the confirm action MUST use the theme's error role while the dismiss action remains secondary

#### Scenario: An asynchronous action is already running
- **WHEN** a user confirms a save, import, relation, classification, or deletion request
- **THEN** the confirming action MUST remain available and show its in-progress state
- **AND** a request guard MUST prevent a second confirmation from starting duplicate work
- **AND** the dismissive action MUST remain available

#### Scenario: A user confirms a task in a compact full-screen dialog
- **WHEN** a compact full-screen dialog for a staged edit or relationship-creation task has a valid result that can be committed
- **THEN** its primary confirming action MUST appear at the right of its bottom action area
- **AND** the dismissive action MUST appear to its left
- **AND** the header MUST expose the close action at its top-left

#### Scenario: A user removes one or more cash record relationships
- **WHEN** a user chooses to unlink one related record or dissolve all accepted relationships from a cash record
- **THEN** the application MUST explain that the relationship will be removed and the underlying cash records will remain
- **AND** the detail task panel MUST be temporarily suspended while one Material 3 confirmation dialog is shown as the sole active modal
- **AND** the user MUST confirm before the existing relationship-removal operation is called
- **AND** canceling the confirmation MUST leave the relationship unchanged and restore the same detail state
- **AND** the confirming action MUST use the theme's error role while the dismissive action remains available

#### Scenario: A cash record task exposes a consistent close affordance
- **WHEN** a user views a cash record task in any presentation
- **THEN** detail, edit, and relation creation MUST expose a visible top-left header close action
- **AND** edit and relation creation MAY also provide bottom cancel actions as task operations
- **AND** AlertDialog confirmation states MUST retain their standard M3 structure without a separate close icon
- **AND** Escape or outside dismissal MUST follow the task's existing cancel, discard, or in-flight request behavior

#### Scenario: A cash record short confirmation is dismissed
- **WHEN** a user sees a deletion, relationship-removal, discard, or error acknowledgement dialog for a cash record task
- **THEN** the underlying detail, editor, or relation composer panel MUST not be rendered as a second active modal
- **AND** canceling or acknowledging the dialog MUST restore the exact originating task and preserve its staged draft

#### Scenario: A modal task state changes accessibly
- **WHEN** a user changes a cash record modal task or advances a stage in a multi-step modal workflow
- **THEN** its accessible title and available controls MUST describe the current task state
- **AND** controls from the previous task state MUST NOT remain exposed as the active dialog controls
- **AND** changing modal task state MUST preserve the task's committed and staged business data

#### Scenario: 已关闭的确认收到失败结果
- **WHEN** 已提交的确认弹窗关闭后，收到明确未执行且原处可继续处理的结果
- **THEN** 所属任务 MUST 显示一次 Snackbar，并保留原处入口

#### Scenario: 已关闭的确认收到需要处理的结果
- **WHEN** 原任务需要核实结果、刷新事实或修正输入
- **THEN** 所属任务 MUST 持续显示对应的业务状态及可用动作；写入恢复遵守[写入恢复沿用业务操作](#requirement-写入恢复沿用业务操作)

### Requirement: Dialog internal measurements follow the Material 3 variant
Compose modal containers MUST apply the measurement contract for their presentation variant while preserving approved product behavior. Basic Dialogs MUST use a 28 dp corner radius, 280 dp minimum width when the viewport allows it, 560 dp maximum width, and 24 dp outer padding. Their height MUST follow content and available viewport constraints without an arbitrary fixed height cap; when content exceeds available height, only the body MUST scroll while the title and actions remain visible. A Basic Dialog title area MUST use content-driven height, retain the approved top-left close affordance as a product exception, and keep 24 dp inset from its edges. The title/body gap MUST be 16 dp, body/actions gap MUST be 24 dp, and buttons MUST have 8 dp spacing with the action group aligned to the trailing edge. Compact Full-screen Dialogs MUST use a 56 dp header according to the spec table, a 24 dp left close icon, a 1 dp optional divider, 24 dp top and horizontal content insets, and a fixed 56 dp bottom action bar; their container MUST fill the available viewport and MUST NOT exceed 560 dp in width. A modal Side Sheet MUST retain its approved width, have 16 dp top, bottom, and logical-end margins when detached, and keep the 16 dp start inset, 24 dp top inset, and 12 dp spacing between header elements. Its content MUST use 24 dp side insets. Its bottom action area MUST use 16 dp top padding, a 72 dp action row, and 24 dp bottom padding, for 112 dp total; actions MUST be vertically centered within the action row, align left, and place the primary action first. AlertDialogs, DatePickerDialogs, and compact filter ModalBottomSheets MUST retain their component-specific measurements.

#### Scenario: A Basic Dialog follows Basic measurements with the close-button exception
- **WHEN** a user opens a non-full-screen custom Basic Dialog
- **THEN** its width MUST be at least 280 dp when available space permits and MUST NOT exceed 560 dp
- **AND** its container MUST use a 28 dp corner radius and 24 dp outer padding
- **AND** its title area MUST grow with its contents and keep the product's close button at the top-left
- **AND** the title/body and body/actions spacing MUST be 16 dp and 24 dp respectively
- **AND** its action buttons MUST have 8 dp spacing and the group MUST align to the trailing edge
- **AND** a long body MUST scroll independently when constrained by the viewport, without a fixed 760 dp height cap
- **AND** its existing task, dismissal, and commit behavior MUST remain unchanged

#### Scenario: A compact full-screen dialog follows Full-screen Dialog measurements
- **WHEN** a user opens a Compact full-screen non-AlertDialog task
- **THEN** its header MUST be 56 dp high according to the measurements table, with a 24 dp top-left close icon
- **AND** its content MUST have 24 dp top and horizontal insets
- **AND** its optional divider MUST be 1 dp and its fixed bottom action bar MUST be 56 dp high
- **AND** the full-screen container width MUST fit the viewport and MUST NOT exceed 560 dp
- **AND** its existing task, dismissal, and commit behavior MUST remain unchanged

#### Scenario: A Side Sheet retains its approved measurements
- **WHEN** a user opens a modal Side Sheet
- **THEN** its header and content MUST use the Side Sheet insets and 12 dp element spacing
- **AND** its detached container MUST have 16 dp margins at the viewport top, bottom, and logical end while preserving its previously approved width
- **AND** its bottom action area MUST measure 112 dp overall, comprising 16 dp top padding, a 72 dp action row, and 24 dp bottom padding
- **AND** its actions MUST be vertically centered within the 72 dp row
- **AND** its actions MUST align left with the primary action first
- **AND** its close icon MUST remain on the left per the approved product contract
- **AND** its dismissal/commit behavior MUST remain unchanged

### Requirement: 短暂操作反馈使用 Snackbar

Web、Android 和 iOS MUST 在当前可见任务使用 Snackbar 提示需要即时告知的短暂操作结果，例如复制结果、操作明确失败但当前任务可以继续。Snackbar MUST NOT 抢占焦点或要求确认，同一结果只提示一次。当前界面已经明确显示成功时 MUST 省去重复提示，业务规格明确要求反馈该结果时按其规定呈现。

输入修正、阻断任务的状态和后续必做动作 MUST 持续保留在所属位置。Snackbar MUST NOT 成为这些信息或操作的唯一载体。确认弹窗仍打开时 MUST 在弹窗内显示对应错误。Snackbar 如提供动作，MUST 直接服务同一对象的安全处理，例如重试读取。

#### Scenario: 短暂失败后可以留在原处

- **WHEN** 使用者尝试复制或执行其他局部操作，操作明确失败且页面仍可继续使用
- **THEN** 所属任务 MUST 显示一次 Snackbar，保留原业务入口，不增加确认弹窗

#### Scenario: 当前页面已经显示成功

- **WHEN** 操作成功并已展示新的链接、所选工作区或其他明确结果，且业务规格没有另行要求反馈
- **THEN** MUST 让使用者直接使用或查看结果，省去重复成功提示

### Requirement: 同一操作使用一个活动进度提示

共享界面 MUST 在可见任务或对应对象所在位置展示实际进行中的进度。进度已由按钮、条目或当前任务的控件表达时，MUST NOT 为同一操作叠加另一处进度指示、Snackbar 或“处理中”等重复文字。按钮 MUST 保留原业务动作名称。进度控件 MUST 保留无障碍名称及进度语义。权限限制 MUST 使用适用状态，不能显示为活动请求。影响用户判断的业务阶段、可量化的进度和需要用户执行的动作 MAY 用简短文字说明。

#### Scenario: 保存账户与核对结果

- **WHEN** 导入账户保存或核对请求正在执行
- **THEN** 当前表单 MUST 显示进度，且不因在途操作拥有恢复身份而显示等待用户处理的提示
- **AND** 请求结束后结果仍不明确时 MUST 显示当前状态，由对应的业务动作接续原请求

#### Scenario: 适用的进度提示已经可见

- **WHEN** 工作区目标行、刷新按钮或最上层导入任务已明确显示在途操作
- **THEN** 同一操作 MUST 不再显示重复进度提示，失败与待处理提示按对应合同保留

### Requirement: 操作提示只展示业务所需信息

界面 MUST 用用户熟悉的业务名称描述受影响的操作、已知结果及当前可用动作。普通反馈 MUST NOT 展示排查所需的内部信息，例如接口、请求标识、响应状态或调用栈。输入或业务条件需要用户处理时，MUST 指出具体对象和可执行的修正动作。系统 MUST NOT 为了给出具体原因而猜测未确认的事实。

#### Scenario: 保存遇到短暂问题

- **WHEN** 短暂故障影响保存，使用者可以通过原动作安全接续
- **THEN** 界面 MUST 给出简短保存提示及原“保存”动作，技术细节保留在诊断信息中

### Requirement: 写入恢复沿用业务操作

系统 MUST 承担已确认写入的结果查询、重放判断及数据刷新。支持安全接续时，MUST 沿用当前任务的“保存”“确认导入”等原业务动作，按原用户、工作区、操作对象、操作标识和提交内容接续。只有服务端保证幂等时才允许重发原请求。MUST NOT 要求使用者通过独立的“核实结果”“再检查一次”“重试原请求”等动作处理写入结果，也不得把返回与这些动作组成技术恢复选项。

界面 MUST 遵守[操作提示只展示业务所需信息](#requirement-操作提示只展示业务所需信息)。确认已保存、确认未保存、结果仍不明确时，分别按已知事实呈现。暂时不能继续时保留已确认决定及必要恢复信息，阻止完成依赖该结果的流程。业务允许返回时，返回仍是普通导航，MUST NOT 被当作取消、撤销或完成尚未明确的写入。

无法安全接续原写入时，系统 MUST 先主动刷新有权读取的当前业务内容。一次刷新只能证明当前事实，不能作为原写入的成功或失败证明。刷新本身失败时，MAY 提供明确对象的普通刷新或读取重试。确需使用者更正输入、选择对象，或就变化后的影响作出新的业务决定时，MUST 展示具体差异及相应业务动作。

#### Scenario: 新建账户的响应丢失

- **WHEN** 新建账户请求出错且无法确定是否已经创建
- **THEN** 表单 MUST 保留原输入，以“保存遇到问题，请重试。”等简短文字说明，并通过原“保存”接续同一次操作
- **AND** “返回”回到“选择对应账户”并由系统刷新账户列表。原保存未确认时 MUST 阻止完成依赖它的导入

#### Scenario: 删除或批量分类后无法确认结果

- **WHEN** 已确认的删除或批量分类无法确定结果，且该操作不支持安全重发
- **THEN** 系统 MUST 自动刷新原操作范围的当前内容，按当前事实更新界面，不要求使用者先核实旧操作
- **AND** 如果确需发起新的操作，MUST 基于最新对象和影响重新确认

#### Scenario: 原决定无需重做

- **WHEN** 短暂故障后原操作可以安全接续，且对象、输入和影响没有变化
- **THEN** MUST 保留原业务动作与决定，不新增确认弹窗或技术恢复菜单
