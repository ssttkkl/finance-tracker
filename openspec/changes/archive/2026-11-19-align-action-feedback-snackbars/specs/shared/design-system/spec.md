## MODIFIED Requirements

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
- **THEN** 所属任务 MUST 持续显示对应状态及可用动作，结果未知时不得重复提交
