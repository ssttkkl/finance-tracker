package com.finance.tracker.business.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StatementSourceTimestampTest {
    @Test fun allHistoricalTransitionsMatchOriginalJvmAtZone() {
        val rows = Json.parseToJsonElement(StatementSourceZoneOriginalVectors.json).jsonArray
        assertEquals(4454, rows.size)
        for (row in rows) {
            val (source, input, expected) = row.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(expected, normalizeStatementTimestamp(input, source), "$source $input")
        }
    }

    @Test fun sourceAliasesAndExplicitOffsetsKeepTheirOriginalMeaning() {
        for (source in listOf("alipay", "wechat", "icbc_credit", "icbc_debit", "ccb_debit")) {
            assertEquals("1991-04-13T18:30:00+00:00", normalizeStatementTimestamp("1991-04-14T02:30:00", source))
            assertEquals("2025-01-01T04:34:56.123456+00:00", normalizeStatementTimestamp("20250101T123456,123456789+0800", source))
            assertEquals("2025-01-01T10:34:56+00:00", normalizeStatementTimestamp("2025-01-01 12:34:56+02", source))
        }
        assertEquals("2025-01-01T12:34:56+00:00", normalizeStatementTimestamp("2025-01-01T12:34:56", "unknown"))
        assertEquals("0000-12-31T15:54:17+00:00", normalizeStatementTimestamp("0001-01-01T00:00:00", "wechat"))
        assertEquals("2024-12-31T16:00:00+00:00", normalizeStatementTimestamp("20250101", "wechat"))
    }

    @Test fun invalidSourceTimesRemainRejected() {
        for (value in listOf("", "2025-02-29T00:00:00", "0000-01-01T00:00:00", "2025-01-01T24:00:00", "bad")) {
            assertEquals("invalid timestamp", assertFailsWith<IllegalArgumentException> {
                normalizeStatementTimestamp(value, "wechat")
            }.message)
        }
    }
}
