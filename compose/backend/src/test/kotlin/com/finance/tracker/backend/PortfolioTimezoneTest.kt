package com.finance.tracker.backend

import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class PortfolioTimezoneTest {
    @Test fun allOriginalTimezoneTransitionsAndFutureRules() {
        val cases = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_PORTFOLIO_TIMEZONE_CONTRACT"))).readText()).jsonArray
        val zones = mutableMapOf<String, PortfolioTimezone>()
        for (case in cases) {
            val row = case.jsonArray; val name = row[0].jsonPrimitive.content
            val zone = zones.getOrPut(name) { PortfolioTimezone(name) }; val instant = Instant.ofEpochSecond(row[1].jsonPrimitive.long)
            val local = LocalDateTime.parse(row[2].jsonPrimitive.content); val fold = row[3].jsonPrimitive.int
            assertEquals(local, zone.local(instant), "$name $instant")
            assertEquals(fold, zone.fold(instant), "$name $instant fold")
            val restored = zone.time(local, fold)
            assertEquals(instant, restored.instant(), "$name $local fold=$fold")
            assertEquals(row[4].jsonPrimitive.int, restored.offset!!.totalSeconds, name)
        }
    }

    @Test fun originalHistoricalAndCurrentTimezoneRules() {
        for ((zone, now, expected) in listOf(
            Triple("EST", "1900-01-15T12:00:00+00:00", "1900-01-01T00:00:00-05:19:36"),
            Triple("HST", "1900-01-15T12:00:00+00:00", "1900-01-01T00:00:00-10:30"),
            Triple("MST", "1944-06-15T12:00:00+00:00", "1944-06-01T00:00:00-06:00"),
            Triple("America/Asuncion", "2026-06-15T12:00:00+00:00", "2026-06-01T00:00:00-03:00"),
        )) assertEquals(expected, portfolioTimeText(portfolioPeriodBounds(QuoteTime.parse(now), "month_to_date", zone).first), zone)
    }
    @Test fun monthStartPreservesTheCurrentFold() {
        val now = QuoteTime.parse("2026-11-01T05:30:00+00:00")
        assertEquals("2026-11-01T00:00:00-05:00", portfolioTimeText(portfolioPeriodBounds(now, "month_to_date", "America/Havana").first))
        assertEquals("2026-10-26T00:00:00-04:00", portfolioTimeText(portfolioPeriodBounds(now, "week_to_date", "America/Havana").first))
    }
}
