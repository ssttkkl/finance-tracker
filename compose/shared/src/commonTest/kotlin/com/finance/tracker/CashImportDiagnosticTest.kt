package com.finance.tracker

import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*
import kotlin.test.*

class CashImportDiagnosticTest {
    @Test fun pdfTableIndexIsPresentedAsAHumanOrdinal() {
        assertEquals(listOf("2", "1", "8"), importDiagnosticPdfParts("p2:t0:r8"))
        assertNull(importDiagnosticPdfParts("unknown"))
    }

    @Test fun diagnosticKeepsOnlyPublicLocationAndMapsWithoutRawErrorText() {
        val body = """{"error":{"code":"commit_failed","message":"private exception","diagnostic":{"error_id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","stage":"row_write","scope":"row","locations":[{"file_ref":"f","display_name":"bill.csv","row_ref":"opaque","row_number":17,"position_kind":"csv_record","sql":"private sql"}],"total_locations":1,"stack":"private stack"}}}"""
        val diagnostic = assertNotNull(apiErrorDiagnostic(body))
        assertEquals(17, diagnostic.locations.single().rowNumber)
        assertEquals("opaque", diagnostic.locations.single().rowRef)
        assertFalse(diagnostic.toString().contains("private"))
        val failure = ApiFailure("commit_failed", 503, commitState = "not_committed", diagnostic = diagnostic).toDomainFailure()
        assertEquals(diagnostic, failure.diagnostic)
        assertEquals("commit_failed", failure.message)
        assertNull(apiErrorDiagnostic("""{"error":{"diagnostic":{"error_id":"invalid"}}}"""))
    }

    @Test fun confirmationFailureCarriesLocationAndNextRequestClearsIt() {
        val diagnostic = ImportDiagnostic("a".repeat(32), "row_write", "row", listOf(ImportDiagnosticLocation(rowNumber = 17)), 1)
        val request = ImportTaskRequest(1, 1)
        val state = ImportTaskUiState(taskId = 1, activeRequest = request, frozenCommand = ImportFrozenCommand("plan", 2, "key"))
            .confirmationFailed(request, DomainFailure("commit_failed", 503, FailureCategory.RECOVERABLE,
                commitState = "not_committed", diagnostic = diagnostic))
        assertEquals(diagnostic, state.errorDiagnostic)
        assertEquals(ImportSubmissionState.RETRY_ORIGINAL, state.submission)
        assertNull(state.retryOriginal().errorDiagnostic)
        assertNull(state.confirmationFailed(request, DomainFailure("request_failed", 0, FailureCategory.RECOVERABLE)).errorDiagnostic)
    }
}
