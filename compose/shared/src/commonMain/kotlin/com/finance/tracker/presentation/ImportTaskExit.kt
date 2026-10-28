package com.finance.tracker.presentation

enum class ImportTaskExitAction { BLOCK, KEEP_RECOVERY, FINISH, ACCOUNT_UNKNOWN, ASK_ACCOUNTS, CANCEL }

fun importTaskExitAction(task: ImportTaskUiState, accounts: ImportAccountUiState): ImportTaskExitAction = when {
    task.busy || accounts.busy -> ImportTaskExitAction.BLOCK
    !task.recoveryReady || !accounts.ready || task.receiptToFetch != null || task.frozenCommand != null &&
        task.submission != ImportSubmissionState.RETRY_ORIGINAL -> ImportTaskExitAction.KEEP_RECOVERY
    task.receipt != null && (task.receipt.status != "failed" || task.receipt.commitOutcome?.hasBusinessWrites != false) -> ImportTaskExitAction.FINISH
    accounts.pendingKeys.isNotEmpty() -> ImportTaskExitAction.ACCOUNT_UNKNOWN
    accounts.changedAccountIds.isNotEmpty() -> ImportTaskExitAction.ASK_ACCOUNTS
    task.receipt != null -> ImportTaskExitAction.FINISH
    else -> ImportTaskExitAction.CANCEL
}
