package com.finance.tracker.client

import com.finance.tracker.business.contracts.CashImportPreviewInput
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.ParsedImportFile
import com.finance.tracker.business.domain.parseLocalCashCsvStatement
import com.finance.tracker.business.domain.sha256Hex
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CashImportReviewEntryParityTest {
    @Test
    fun reviewUsesTheSameStatementAndCommittedFactsAsTheInstalledCli() {
        val snapshot = Json.decodeFromString<LedgerSnapshotDto>(resource("cash-operation-snapshot.json").decodeToString())
        val bytes = resource("alipay-01.csv")
        val document = parseLocalCashCsvStatement(bytes)
        val file = ParsedImportFile("fixture-file", sha256Hex(bytes), "parsed", document, filename = "alipay-01.csv", size = bytes.size.toLong())
        val choices = CashImportPreviewInput(2, null, emptyList(), emptyList(), emptyList())
        val calendar = CashLedgerCalendar({ it == "UTC" }, { _, _ -> 0 }, { _, _ -> error("No date limits") })
        val review = CashImportReviewOperations(snapshot.workspaceId, calendar)
            .review(snapshot, "fixture-batch", "fixture-plan", 2, listOf(file), choices)

        assertEquals(2L, review.plan.sessionRevision)
        assertEquals(snapshot.workspaceId, review.workspaceId)
        assertEquals(snapshot.ledgerSeq, review.calculation.request.baseLedgerSeq)
        assertEquals("fixture-batch", review.plan.sessionId)
        assertTrue(checkNotNull(review.plan.fileResults.single().recognizedRowCount) > 0)
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/$name")).use { it.readBytes() }
}
