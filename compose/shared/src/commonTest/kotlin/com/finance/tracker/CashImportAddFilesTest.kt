package com.finance.tracker

import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*
import kotlin.test.*

class CashImportAddFilesTest {
    @Test fun addFilesStaysAvailableWhileUploadingAndStopsAtCombinedLimit() {
        val files = (1..18).map { file("c$it") }
        val context = ImportContext("s", 3, "future", "open", files, emptyList(), ImportChoiceSet(), null, true, null)
        val uploading = ImportTaskUiState(recoveryReady = true, context = context, busy = true, activeUploads = 1)
        assertTrue(uploading.canAddFiles)
        assertTrue(canAddCashImportFiles(uploading, listOf(pending("c19")), true))
        assertFalse(canAddCashImportFiles(uploading, listOf(pending("c19")), false))
        val full = uploading.copy(context = context.copy(files = files + file("c19") + file("c20")))
        assertFalse(canAddCashImportFiles(full, emptyList(), true))
    }

    @Test fun fileStepExplainsFailuresAndPrioritizesMissingPasswords() {
        val ready = file("ready")
        val context = ImportContext("s", 3, "future", "open", listOf(ready), emptyList(), ImportChoiceSet(), null, true, null)
        val state = ImportTaskUiState(context = context)
        assertNull(cashImportFileStepNotice(state))
        val localFailure = state.copy(localFileFailures = listOf(ImportLocalFileFailure("bad", "request_failed")))
        assertEquals("cash_import_fix_failed_files", cashImportFileStepNotice(localFailure))
        assertEquals("cash_import_fix_failed_files", cashImportFileStepNotice(state.copy(context = context.copy(
            files = listOf(ready, file("bad").copy(status = "failed"))))))
        assertEquals("cash_import_enter_password", cashImportFileStepNotice(localFailure.copy(context = context.copy(
            files = listOf(ready, file("locked").copy(status = "password_required"))))))
        assertNull(cashImportFileStepNotice(state.copy(context = context.copy(files = listOf(ready.copy(status = "partial"))))))
    }

    private fun file(ref: String) = ImportContextFile("f-$ref", ref, "$ref.csv", "alipay", "ready", 1, emptyList(), false)
    private fun pending(ref: String) = CashImportPendingFile(ref, "$ref.csv", 3)
}
