package com.finance.tracker.client

import com.finance.tracker.business.contracts.CashMonthlySummaryDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import kotlinx.serialization.json.Json
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class CashLedgerEntryParityTest {
    @Test
    fun terminalAndDirectCallUseTheSameFixtureAndExactSummary() {
        val snapshot = Json.decodeFromString<LedgerSnapshotDto>(resource("cash-operation-snapshot.json"))
        val expected = Json.decodeFromString<List<CashMonthlySummaryDto>>(resource("cash-operation-summary.json"))
        val calendar = CashLedgerCalendar(
            { it in ZoneId.getAvailableZoneIds() },
            { epoch, zone -> ZoneId.of(zone).rules.getOffset(java.time.Instant.ofEpochSecond(epoch.toLong())).totalSeconds },
            { _, _ -> error("No date limits") },
        )
        val result = CashLedgerOperations(snapshot, calendar).summary(timeZoneId = "Asia/Shanghai")
        assertEquals(expected, result.months)
        assertEquals(snapshot.workspaceId, result.workspaceId)
        assertEquals(snapshot.ledgerSeq, result.ledgerSeq)
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/$name")).bufferedReader().use { it.readText() }
}
