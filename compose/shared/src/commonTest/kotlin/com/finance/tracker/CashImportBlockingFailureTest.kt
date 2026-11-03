package com.finance.tracker

import com.finance.tracker.presentation.ImportAccountUiState
import com.finance.tracker.presentation.cashImportLoadBlocked
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CashImportBlockingFailureTest {
    @Test fun auxiliaryLoadFailureBlocksTheImportTask() {
        assertTrue(cashImportLoadBlocked(true, ImportAccountUiState(ready = true)))
    }

    @Test fun accountLoadFailureBlocksOnlyAfterTheRequestSettlesWithoutReadiness() {
        val failed = ImportAccountUiState(ready = false, busy = false, errorCode = "request_failed")
        assertTrue(cashImportLoadBlocked(false, failed))
        assertFalse(cashImportLoadBlocked(false, failed.copy(busy = true)))
        assertFalse(cashImportLoadBlocked(false, failed.copy(ready = true)))
        assertFalse(cashImportLoadBlocked(false, failed.copy(errorCode = null)))
    }

    @Test fun operationalAccountErrorsAfterReadinessDoNotBlockTheTask() {
        assertFalse(cashImportLoadBlocked(false, ImportAccountUiState(ready = true, errorCode = "account_in_use")))
    }
}
