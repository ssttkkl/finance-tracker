package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class PythonCashTimestampTest {
    @Test
    fun calendarWeekBasicAndAstralSeparatorsUseTheSameUtcInstant() {
        val expected = Instant.parse("2026-08-04T12:34:56.123456Z")
        for (date in listOf("2026-08-04", "20260804", "2026-W32-2", "2026W322")) {
            for (separator in listOf("T", " ", "😀")) {
                assertEquals(expected, parsePythonCashTimestamp("${date}${separator}12:34:56.123456789+00:00"))
            }
        }
        assertEquals(expected, parsePythonCashTimestamp("20260804T123456,123456Z"))
        assertEquals(Instant.parse("2026-08-03T23:04:56.123456Z"),
            parsePythonCashTimestamp("2026-08-04T00:34:56.123456+0130"))
    }

    @Test
    fun explicitOffsetAndNaivePoliciesRemainSeparate() {
        for (date in listOf("2026-08-04", "20260804", "2026-W32-2", "2026W322")) {
            assertFailsWith<IllegalArgumentException> { parsePythonCashTimestamp(date) }
            assertEquals(Instant.parse("2026-08-04T00:00:00Z"), parsePythonCashTimestamp(date, allowNaive = true))
        }
        assertEquals(Instant.parse("2026-08-04T12:00:00Z"),
            parsePythonCashTimestamp("2026-08-04Z12:00", allowNaive = true))
        assertFailsWith<IllegalArgumentException> { parsePythonCashTimestamp("2026-08-04Z12:00") }
        assertEquals(Instant.parse("2026-08-04T12:00:00Z"),
            parsePythonCashTimestamp("2026-08-04Z12:00Z", preserveZSeparator = true))
    }

    @Test
    fun offsetSecondsAndFractionPreservePythonSemantics() {
        assertEquals(Instant.parse("2026-08-04T11:59:58.5Z"),
            parsePythonCashTimestamp("2026-08-04T12:00:00+00:00:01.5"))
        assertEquals(Instant.parse("2026-08-04T12:00:00Z"),
            parsePythonCashTimestamp("2026-08-04T12:00:00+00:00:00.5"))
        assertEquals(Instant.parse("2026-08-04T10:19:21Z"),
            parsePythonCashTimestamp("2026-08-04T12:00:00+00:99:99"))
        assertEquals(Instant.parse("1969-12-31T23:59:59.000001Z"),
            parsePythonCashTimestamp("1970-01-01T00:00:00.000001+00:00:01"))
    }

    @Test
    fun calendarAndUtcRangeErrorsAreNotDefaults() {
        for (value in listOf("2026-02-29T00:00Z", "1900-02-29T00:00Z", "2025-W53-1T00:00Z",
            "2026-01-01T24:00Z", "2026-01-01T00:60Z", "2026-01-01T00:00:60Z",
            "0000-01-01T00:00Z", "2026-01-01T00:00+24:00", "2026-01-01T00:00z")) {
            assertFailsWith<IllegalArgumentException>(value) { parsePythonCashTimestamp(value) }
        }
        assertEquals(Instant.parse("2000-02-29T00:00:00Z"), parsePythonCashTimestamp("2000-02-29T00:00Z"))
        for (value in listOf("0001-01-01T00:00+01:00", "9999-12-31T23:59-01:00")) {
            assertFailsWith<IllegalArgumentException>(value) { parsePythonCashTimestamp(value) }
            assertEquals("date value out of range",
                assertFailsWith<ArithmeticException>(value) { parsePythonCashTimestamp(value, allowNaive = true) }.message)
        }
    }

    @Test
    fun validationAcceptsExactlyTheFormatterUtcRange() {
        val samples = listOf(
            Instant.fromEpochSeconds(-62135596801L, 999999999L) to false,
            Instant.fromEpochSeconds(-62135596800L) to true,
            Instant.fromEpochSeconds(0) to true,
            Instant.fromEpochSeconds(253402300799L, 999999999L) to true,
            Instant.fromEpochSeconds(253402300800L) to false,
        )
        for ((time, accepted) in samples) {
            assertEquals(accepted, isRepresentableCashTimestamp(time))
            assertEquals(accepted, runCatching { formatPythonCashTimestamp(time) }.isSuccess)
        }
    }

    @Test
    fun workbookDayFormattingKeepsGregorianMonthBoundariesAcrossEras() {
        for (year in (-400..400) + listOf(-10000000, -9999999, 1900, 2000, 9999, 10000, 9999999)) {
            val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
            val lengths = listOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
            val yearText = when {
                year < 0 -> "-" + (-year).toString().padStart(4, '0')
                year > 9999 -> "+$year"
                else -> year.toString().padStart(4, '0')
            }
            for ((index, days) in lengths.withIndex()) {
                for (day in listOf(1, days)) {
                    val month = (index + 1).toString().padStart(2, '0')
                    val date = day.toString().padStart(2, '0')
                    assertEquals("$yearText-$month-$date", formatCashWorkbookDay(timestampCalendarDay(year, index + 1, day)))
                }
                assertFailsWith<IllegalArgumentException> { timestampCalendarDay(year, index + 1, days + 1) }
            }
        }
        assertEquals("1970-01-01", formatCashWorkbookDay(0L))
        assertEquals("-0001-12-31", formatBankCoverageDay(-719529L))
        assertEquals("0000-01-01", formatCashCandidateDay(-719528L))
        assertEquals("+10000-12-31", formatCashCandidateDay(timestampCalendarDay(10000, 12, 31)))
        assertFailsWith<IllegalArgumentException> { formatCashWorkbookDay(timestampCalendarDay(-10000000, 1, 1) - 1) }
        assertFailsWith<IllegalArgumentException> { formatCashWorkbookDay(timestampCalendarDay(10000000, 1, 1)) }
    }

    @Test
    fun separatedSurrogatesCannotBecomeAnAstralSeparator() {
        val text = "2026-08-04😀12:00Z"
        assertFailsWith<IllegalArgumentException> { parsePythonCashTimestamp(text, separateSurrogates = setOf(10, 11)) }
        assertEquals(Instant.parse("2026-08-04T12:00:00Z"), parsePythonCashTimestamp(text))
    }
}
