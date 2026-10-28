package com.finance.tracker

import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*
import kotlin.test.*

class ImportTaskExitTest {
    private val changedAccounts = ImportAccountUiState(ready = true, changedAccountIds = setOf("account"))

    @Test fun accountUndoIsOfferedOnlyBeforeImportOrForAnAuthoritativeFailedResultWithoutWrites() {
        assertEquals(ImportTaskExitAction.ASK_ACCOUNTS, importTaskExitAction(ImportTaskUiState(), changedAccounts))
        assertEquals(ImportTaskExitAction.ASK_ACCOUNTS, importTaskExitAction(completed("failed", false), changedAccounts))
        for (status in listOf("completed", "partial", "no_changes")) {
            assertEquals(ImportTaskExitAction.FINISH, importTaskExitAction(completed(status, false), changedAccounts))
            assertEquals(ImportTaskExitAction.FINISH, importTaskExitAction(completed(status, true), changedAccounts))
        }
        assertEquals(ImportTaskExitAction.FINISH, importTaskExitAction(completed("failed", true), changedAccounts))
        assertEquals(ImportTaskExitAction.FINISH, importTaskExitAction(completed("failed", null), changedAccounts))
    }

    @Test fun unknownFinancialResultsKeepTheirPointersAndNeverStartUndo() {
        val frozen = ImportFrozenCommand("plan", 1, "original")
        for (submission in listOf(ImportSubmissionState.UNKNOWN, ImportSubmissionState.CHECKING, ImportSubmissionState.SAVING)) {
            val state = ImportTaskUiState(frozenCommand = frozen, submission = submission)
            assertEquals(ImportTaskExitAction.KEEP_RECOVERY, importTaskExitAction(state, changedAccounts))
        }
        assertEquals(ImportTaskExitAction.KEEP_RECOVERY, importTaskExitAction(ImportTaskUiState(recoveryReady = false), changedAccounts))
        val safe = ImportTaskUiState(frozenCommand = frozen, submission = ImportSubmissionState.RETRY_ORIGINAL)
        assertEquals(ImportTaskExitAction.ASK_ACCOUNTS, importTaskExitAction(safe, changedAccounts))
    }

    @Test fun unknownAccountsAreResolvedIndependentlyAndBusyWorkCannotExit() {
        val unknown = changedAccounts.copy(pendingKeys = listOf("account-original"))
        assertEquals(ImportTaskExitAction.ACCOUNT_UNKNOWN, importTaskExitAction(ImportTaskUiState(), unknown))
        assertEquals(ImportTaskExitAction.BLOCK, importTaskExitAction(ImportTaskUiState(busy = true), unknown))
        assertEquals(ImportTaskExitAction.BLOCK, importTaskExitAction(ImportTaskUiState(), unknown.copy(busy = true)))
        assertEquals(ImportTaskExitAction.CANCEL, importTaskExitAction(ImportTaskUiState(), ImportAccountUiState(ready = true)))
    }

    private fun completed(status: String, writes: Boolean?): ImportTaskUiState = ImportTaskUiState(
        receipt = ImportReceipt("receipt", "plan", "import", true, "now", status, emptyMap(), emptyMap(),
            0, 0, 1, null, null, null, null,
            writes?.let { ImportCommitOutcome(status == "failed", it, emptyList()) }), submission = ImportSubmissionState.COMPLETE)
}
