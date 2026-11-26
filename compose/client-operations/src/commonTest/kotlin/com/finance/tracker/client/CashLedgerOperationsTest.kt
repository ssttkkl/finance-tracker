package com.finance.tracker.client

import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.LedgerFactsDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import kotlin.test.Test
import kotlin.test.assertEquals

class CashLedgerOperationsTest {
    @Test
    fun summaryRetainsTheWorkspaceAndExactRevision() {
        val snapshot = LedgerSnapshotDto(
            "workspace-a", "9007199254740995", "1",
            LedgerFactsDto(
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0",
            ),
        )
        val calendar = CashLedgerCalendar({ it == "Asia/Shanghai" }, { _, _ -> 28800 }, { _, _ -> error("No dates requested") })
        val result = CashLedgerOperations(snapshot, calendar).summary(CashFiltersDto(currency = "cny"), "Asia/Shanghai")
        assertEquals("workspace-a", result.workspaceId)
        assertEquals("9007199254740995", result.ledgerSeq)
        assertEquals("CNY", result.filters["currency"])
        assertEquals("Asia/Shanghai", result.filters["timezone"])
        assertEquals(emptyList(), result.months)
    }
}
