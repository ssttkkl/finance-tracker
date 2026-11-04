package com.finance.tracker.backend

import com.finance.tracker.business.domain.parsePythonCashTimestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SharedCashTimestampParityTest {
    @Test
    fun sharedParserMatchesAllExistingPythonTimestampVectors() {
        for ((resource, allowNaive) in listOf("python-cash-timestamps.json" to false,
            "python-manual-cash-timestamps.json" to true)) {
            val rows = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/$resource")).readText()).jsonArray
            for (raw in rows) {
                val row = raw.jsonObject
                val input = row.getValue("value").jsonPrimitive.content
                val value = if (allowNaive) input.trim { it.code in pythonWhitespace } else input
                when {
                    "overflow" in row -> assertEquals(row.getValue("overflow").jsonPrimitive.content,
                        assertFailsWith<ArithmeticException>(input) { parsePythonCashTimestamp(value, allowNaive) }.message)
                    "error" in row -> assertFailsWith<IllegalArgumentException>(input) {
                        parsePythonCashTimestamp(value, allowNaive)
                    }
                    else -> {
                        val actual = parsePythonCashTimestamp(value, allowNaive)
                        assertEquals(Instant.parse(row.getValue("utc").jsonPrimitive.content), actual, input)
                    }
                }
            }
            assertEquals(if (allowNaive) 10033 else 10020, rows.size)
        }
    }

    @Test
    fun jvmTimeAdaptersPreserveMicrosecondsAndRangeErrors() {
        val time = java.time.Instant.parse("2026-08-04T12:34:56.123456789Z")
        val expected = java.time.Instant.parse("2026-08-04T12:34:56.123456Z")
        for (value in listOf(time, time.atOffset(java.time.ZoneOffset.ofHours(8)),
            time.atZone(java.time.ZoneId.of("Asia/Shanghai")))) {
            assertEquals(expected, pythonCashTimestamp(value))
        }
        val outside = java.time.Instant.parse("+10000-01-01T00:00:00Z")
        assertFailsWith<InvalidCashValue> { pythonCashTimestamp(outside) }
        assertEquals("date value out of range",
            assertFailsWith<ArithmeticException> { pythonCashTimestamp(outside, allowNaive = true) }.message)
        assertFailsWith<InvalidCashValue> { pythonCashTimestamp(java.time.Instant.MAX, allowNaive = true) }
        for (value in listOf(true, 123L, java.time.LocalDate.of(2026, 8, 4))) {
            assertFailsWith<InvalidCashValue> { pythonCashTimestamp(value) }
        }
    }
}
