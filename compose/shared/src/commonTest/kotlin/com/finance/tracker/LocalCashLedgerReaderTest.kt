package com.finance.tracker

import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import com.finance.tracker.business.domain.CashProjectionDomainFailure
import com.finance.tracker.core.LocalCashLedgerReader
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashLedgerReaderTest {
    private fun snapshot(): LedgerSnapshotDto {
        val created = "2026-04-02T00:00:00Z"
        val dates = listOf("2026-03-31T03:59:59.999999Z", "2026-03-31T04:00:00Z", "2026-04-01T04:00:00Z", null)
        val rows = dates.mapIndexed { index, date ->
            val id = (index + 1).toString()
            LedgerCashTransactionFactDto(id, "10", if (date == null) "draft" else "posted", "legacy", null,
                "1", "atomic", null, "record:$id", JsonNull, JsonNull, null, JsonArray(emptyList()), date,
                if (date == null) null else "-$id", if (date == null) null else "CNY", "source", "",
                JsonArray(emptyList()), "source", null, "consumption", "not_applicable", created, null, "", "")
        }
        val empty = LedgerFactsDto(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0")
        return LedgerSnapshotDto("workspace", "9007199254740993", CASH_RULES_VERSION, empty.copy(
            accounts = listOf(LedgerAccountFactDto("10", "Cash", "cash", true, JsonArray(emptyList()), "1")),
            cashTransactions = rows,
            cashEntries = rows.map { LedgerCashEntryFactDto("entry:${it.id}", it.id, it.id, "1", null, "public note",
                "Cafe", "", JsonArray(emptyList()), JsonArray(emptyList()), null) },
            cashEntryMembers = rows.map { LedgerCashEntryMemberFactDto(it.id, "entry:${it.id}", JsonObject(emptyMap()), JsonArray(emptyList())) }))
    }

    @Test fun rawFiltersUseTheSelectedPlatformsCalendarBeforeSelectingAndSummarizing() {
        val reader = LocalCashLedgerReader(snapshot())
        val filters = CashFiltersDto(dateFrom = "20260331", dateTo = "2026-03-31", accountId = "+0010", currency = "cny",
            counterparty = "  cafe ")
        val page = reader.page(filters, " America/New_York ", startIndex = 0, limit = 1)
        assertEquals(listOf("2"), page.items.map { it.transactionId })
        assertEquals(1, page.totalCount); assertEquals(0L, page.pendingItemCount)
        assertEquals("America/New_York", page.filters["timezone"])
        assertEquals("2026-03-31", page.filters["date_from"])
        assertEquals("10", page.filters["account_id"])
        assertEquals(listOf(CashMonthlySummaryDto("2026-03", listOf(CashMonthlyCurrencySummaryDto("CNY", "0", "-2")))), page.monthlySummaries)
        val utc = reader.page(filters, "UTC", startIndex = 0)
        assertEquals(listOf("2", "1"), utc.items.map { it.transactionId })
        assertEquals(2, utc.totalCount)
        assertEquals(listOf(CashMonthPositionDto("2026-03", 0, 1)), page.monthPositions)
    }

    @Test fun continuationKeepsUnknownDatesAndRejectsAChangedRawScope() {
        val reader = LocalCashLedgerReader(snapshot())
        val first = reader.page(timeZoneId = "America/New_York", limit = 1)
        assertEquals("4", first.items.single().transactionId); assertNull(first.items.single().occurredAt)
        assertEquals(1L, first.pendingItemCount)
        assertEquals(listOf("2026-04", "2026-03"), first.monthlySummaries.map { it.month })
        val second = reader.page(timeZoneId = "America/New_York", cursor = first.nextCursor, limit = 1)
        assertEquals("3", second.items.single().transactionId)
        assertEquals("invalid_cursor", assertFailsWith<CashProjectionDomainFailure> {
            reader.page(timeZoneId = "UTC", cursor = first.nextCursor, limit = 1)
        }.code)
        assertEquals("read_snapshot_changed", assertFailsWith<CashProjectionDomainFailure> {
            reader.page(startIndex = 0, expectedLedgerSeq = 1)
        }.code)
        assertEquals("invalid_filter", assertFailsWith<CashProjectionDomainFailure> {
            reader.page(CashFiltersDto(dateFrom = "2026-02-30"))
        }.code)
    }
}
