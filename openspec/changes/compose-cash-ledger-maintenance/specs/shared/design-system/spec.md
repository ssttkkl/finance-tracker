## MODIFIED Requirements

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
