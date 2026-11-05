package com.finance.tracker.backend

import com.finance.tracker.business.domain.CashProjectionDomainFailure
import com.finance.tracker.business.domain.normalizeCashLedgerFilterDate
import java.time.LocalDate
import java.time.DateTimeException
import java.time.format.DateTimeFormatter
import kotlin.test.*

class CashLedgerFilterDateParityTest {
    // The unmodified JVM formatter contract before the shared implementation was introduced.
    private fun native(raw: String): String? { return try {
        val value = when {
            raw.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) -> LocalDate.parse(raw)
            raw.matches(Regex("[0-9]{8}")) -> LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE)
            raw.matches(Regex("[0-9]{4}(?:-W[0-9]{2}(?:-[0-9])?|W[0-9]{2}[0-9]?)")) -> {
                val compact = raw.replace("-", "")
                LocalDate.parse(compact.take(4) + "-W" + compact.substring(5, 7) + "-" +
                    if (compact.length == 8) compact.last() else '1', DateTimeFormatter.ISO_WEEK_DATE)
            }
            else -> return null
        }
        value.takeIf { it.year in 1..9999 }?.toString()
    } catch (_: DateTimeException) { null } }
    private fun shared(raw: String): String? = try { normalizeCashLedgerFilterDate(raw) }
        catch (error: CashProjectionDomainFailure) { assertEquals("invalid_filter", error.code); null }

    @Test fun fullGregorianCycleAndRangeEdgesMatchTheNativeDateFormatter() {
        val years = (0..400).toList() + listOf(1582, 1900, 2000, 2026, 9998, 9999)
        for (year in years) {
            val prefix = year.toString().padStart(4, '0')
            for (week in 0..54) {
                val number = week.toString().padStart(2, '0')
                for (raw in listOf("$prefix-W$number", "${prefix}W$number")) assertEquals(native(raw), shared(raw), raw)
                for (day in 0..8) for (raw in listOf("$prefix-W$number-$day", "${prefix}W$number$day"))
                    assertEquals(native(raw), shared(raw), raw)
            }
            for (month in 0..13) for (day in listOf(0, 1, 28, 29, 30, 31, 32)) {
                val raw = "$prefix-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
                assertEquals(native(raw), shared(raw), raw)
                assertEquals(native(raw.replace("-", "")), shared(raw.replace("-", "")), raw)
            }
        }
    }
    @Test fun backendStillUsesTheSelectedZoneAndOriginalUpperDateBoundary() {
        for (raw in listOf("2026-07-01", "20260701", "2026-W27-3", "2026W273")) {
            val input = CashProjectionListInputs.parse(mapOf("date_from" to raw, "date_to" to raw, "timezone" to "Asia/Tokyo"))
            assertEquals("2026-07-01", input.filters["date_from"])
            assertEquals("2026-06-30T15:00:00Z", input.start.toString())
            assertEquals("2026-07-01T15:00:00Z", input.end.toString())
        }
        assertEquals("invalid_filter", assertFailsWith<CashProjectionListFailure> {
            CashProjectionListInputs.parse(mapOf("date_from" to "2025-W53"))
        }.code)
        assertEquals("internal", assertFailsWith<CashProjectionListFailure> {
            CashProjectionListInputs.parse(mapOf("date_to" to "9999-12-31"))
        }.code)
    }
}
