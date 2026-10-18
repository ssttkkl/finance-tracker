## ADDED Requirements

### Requirement: Compose dialogs follow Material 3 task and interaction guidance
Compose modal dialogs MUST open in response to a user action and stay focused on one task. Short decisions and focused input MUST use a basic dialog; workflows with a sequence of steps MUST use a full-screen dialog at compact breakpoints and a basic dialog at regular or wide breakpoints, except where a more specific product contract below requires a different presentation. Cash record detail and editing MUST share a full-screen task at compact breakpoints and a right-side full-height modal panel at regular and wide breakpoints. Basic dialogs MUST not exceed 560 dp in width. Dialog titles and actions MUST communicate the task and its outcome. Custom dialog containers MUST expose their task title as an accessible pane title. A dialog MUST preserve staged values when dismissed or cancelled, except that live query controls such as ledger filters apply their selections immediately and closing their container does not roll back those filters. Dialogs MUST keep the background unavailable while open and MUST offer a clear way to leave when the task permits dismissal. All task operation buttons MUST remain in a bottom action area, with the primary action rightmost and secondary or cancel actions to its left. Every non-AlertDialog modal MUST expose a close button at the top-left; AlertDialog MUST retain its component-provided actions without action icons. A full-screen task's body MAY scroll while its title and close affordance remain fixed. A compact full-screen dialog MAY present a basic dialog above it for a nested focused task such as editing, discard confirmation, or acknowledgement of a failed submission; a basic dialog MUST NOT present another basic dialog above itself.

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
- **WHEN** a user starts a task that requires moving through multiple stages, such as importing a bill
- **THEN** compact presentation MUST use a full-screen dialog
- **AND** regular and wide presentation MUST use a basic dialog no wider than 560 dp
- **AND** any scrollable content MUST keep the title and final actions visible

#### Scenario: A compact full-screen task has fixed bottom actions
- **WHEN** a user edits a record or creates a relationship in a compact full-screen dialog
- **THEN** the confirming action MUST remain in a fixed bottom action area
- **AND** the dismissive action MUST be to its left and MUST return to the prior task state or request discard confirmation when there are unsaved changes
- **AND** the header MUST retain the title and a top-left close affordance
- **AND** the body MAY scroll without hiding the title, close affordance, or bottom actions

#### Scenario: A user dismisses a full-screen task with unsaved work
- **WHEN** a user attempts to close, navigate Back, or press Escape on a full-screen task that contains unsubmitted work
- **THEN** compact presentation MUST show a basic discard confirmation dialog above the existing full-screen task
- **AND** regular and wide presentation MUST show the discard decision inside the existing basic dialog
- **AND** choosing the dismissive action MUST keep the task and all staged values intact
- **AND** confirming discard MUST leave the task without committing its unsubmitted data

#### Scenario: A cash record detail and edit share a responsive modal container
- **WHEN** a user opens a read-only cash record detail
- **THEN** compact presentation MUST use a full-screen task and regular or wide presentation MUST keep the right-side full-height modal panel
- **AND** switching from detail to edit MUST replace the layout inside the same modal container without opening a separate edit dialog
- **AND** the detail body MUST scroll independently while its title, top-left close action, and bottom detail actions remain visible
- **AND** the regular and wide panels MUST fill the viewport height
- **AND** the regular and wide panels MUST align their logical end edge to the viewport edge and retain only a 16 dp / 56 dp logical start gutter
- **AND** the regular and wide panels MUST use `surfaceContainerLow`, the theme's Large shape, and zero tonal elevation
- **AND** tapping the modal scrim MUST dismiss the panel using the current task's exit behavior
- **AND** dragging the panel toward its logical end edge MUST dismiss it using the current task's exit behavior
- **AND** the primary Edit action MUST be rightmost in the bottom action area, with More actions to its left
- **AND** the background ledger MUST remain unavailable until the panel is closed

#### Scenario: A modal cash record panel follows the Material 3 Side Sheet contract
- **WHEN** a user opens cash record detail, edit, or relation creation at regular or wide size
- **THEN** the task MUST be displayed as a modal side sheet anchored to the logical end edge and filling the viewport height
- **AND** the sheet MUST use `surfaceContainerLow`, the theme's Large shape, and zero tonal elevation
- **AND** the scrim MUST block interaction with the ledger and support dismissal
- **AND** dragging the sheet toward its logical end edge MUST dismiss it
- **AND** the top-left close affordance MUST remain visible while the body scrolls
- **AND** all task operation buttons MUST remain fixed at the bottom with the primary button rightmost

#### Scenario: A relationship composer is an independent modal task
- **WHEN** a user opens the relationship composer from cash record detail
- **THEN** the composer MUST be presented as its own M3 modal task, with compact full-screen presentation and a right-side full-height modal panel at regular and wide breakpoints
- **AND** only the composer task MUST be the active modal while it is open
- **AND** canceling or completing the composer MUST return to the previous cash record detail without losing its committed data

#### Scenario: A cash record short confirmation uses a Material 3 confirmation dialog
- **WHEN** a user requests record deletion, relation removal, or relation dissolution from a cash record detail
- **THEN** the operation MUST use a Material 3 confirmation dialog as the sole active modal
- **AND** the confirmation MUST explain the consequence and collect any deletion scope before its final action
- **AND** canceling or dismissing the confirmation MUST restore the same detail without changing the record or relationship
- **AND** the final destructive action MUST remain visible and use the theme's error color role

#### Scenario: A user opens a read-only investment detail
- **WHEN** a user opens investment event evidence or a holding's position details
- **THEN** every window size MUST use a basic dialog with a maximum width of 560 dp
- **AND** the title and dismiss action MUST remain visible while the detail body scrolls
- **AND** the dialog MUST NOT use the full-screen variant because the task has no staged edits or multi-step workflow

#### Scenario: A user attempts to discard an edited cash record
- **WHEN** a user changes a cash record draft and attempts to cancel, navigate Back, press Escape, or close the editor
- **THEN** the editor task panel MUST be temporarily suspended and one Material 3 discard confirmation dialog MUST be the only active modal at every breakpoint
- **AND** choosing the dismissive action MUST keep the draft and editor available
- **AND** confirming discard MUST discard only the unsaved draft and return to the record detail without issuing a save request

#### Scenario: A cash record save fails
- **WHEN** saving a cash record fails with a general error while the editor remains open
- **THEN** the editor task panel MUST be temporarily suspended and one Material 3 error acknowledgement dialog MUST be the only active modal at every breakpoint
- **AND** the save action MUST remain available for retry when the draft is still valid
- **AND** acknowledging the error MUST restore the editor with its draft available for correction or retry
- **AND** if the editor was dismissed while the save was in progress, the error MUST appear in a basic acknowledgement dialog on the ledger page

#### Scenario: A confirmation request is in progress
- **WHEN** a dialog's confirm action has started an asynchronous save, import, or relation operation
- **THEN** the confirm action MUST remain available and communicate the in-progress state
- **AND** the dialog's dismissive action MUST remain available
- **AND** repeated confirmation MUST NOT start a duplicate operation
- **AND** dismissing the dialog MUST NOT imply that an accepted operation was rolled back
- **AND** if the operation fails after the user dismisses its confirmation, the owning page MUST present a basic acknowledgement dialog with the failure and a clear acknowledgement action

#### Scenario: A user attempts to close an import while it is processing
- **WHEN** a user attempts to leave the import workflow while a scan or commit request is still processing
- **THEN** the existing import task MUST remain open and show the waiting message within its current task surface
- **AND** the workflow MUST NOT open an additional informational dialog above the task
- **AND** the user MUST be able to acknowledge the message and continue when the request finishes

#### Scenario: A required field in an edit dialog is empty
- **WHEN** a user clears a required account name in the new-account editor
- **THEN** the apply action MUST be disabled in compact, regular, and wide presentations
- **AND** the disabled state MUST be exposed through the action's accessibility semantics
- **AND** restoring a non-empty name MUST enable the apply action without discarding the draft

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
A dialog action area MUST present at most one confirming action and one dismissing action, except when a single acknowledgement is the entire task. When a task offers several possible outcomes, the dialog MUST collect that choice in its content before presenting one final confirming action. The confirming action MUST be placed after the dismissing action in the logical action order. A dismissing action MUST remain enabled; asynchronous work already accepted by a confirming action MAY continue after the dialog is dismissed. If such an operation fails after dismissal, the owning page MUST present a basic acknowledgement dialog with the failure. Destructive confirmations MUST name the operation and use the error color role for the confirming action; dismissing MUST leave the underlying data unchanged.

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
