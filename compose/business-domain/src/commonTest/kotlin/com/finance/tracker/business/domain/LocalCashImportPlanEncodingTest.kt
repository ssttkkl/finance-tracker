package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class LocalCashImportPlanEncodingTest {
    @Test fun finalEncodingKeepsFixedDecimalsMicrosecondsExactIdsAndRawNumberKinds() {
        val payload = Json.parseToJsonElement("""{"integer":1,"float":1.0,"wide":18446744073709551617,"summary":{"count":1.0}}""")
        val value = linkedMapOf<String, Any?>(
            "summary" to mapOf("created_posted" to 1, "duplicate" to 0L),
            "file_results" to listOf(mapOf("recognized_row_count" to 3, "diagnostic" to mapOf(
                "total_locations" to 1, "locations" to listOf(mapOf("row_number" to 2))))),
            "amounts" to listOf(CashAmount.parse("-0.00"), CashAmount.parse("1.2300"), CashDecimalLiteral("1E+2"),
                CashAmount.parse("-0E2147483648"), CashDecimalLiteral("-0E2147483648"), CashDecimalLiteral("0E2147483648"),
                CashDecimalLiteral("-0E2147483649")),
            "time" to Instant.parse("2026-10-01T10:00:00.123456789Z"),
            "created_at" to Instant.parse("1800-01-01T00:00:00Z"),
            "ids" to listOf(CashImportPreviewInteger.parse("34038115944072999195166050254573576354"), Long.MAX_VALUE),
            "source_payload" to decodeLocalCashValue(payload), "empty" to JsonNull)
        val original = localCashImportDigest(listOf(value))
        val expected = Json.parseToJsonElement("""{
            "summary":{"created_posted":1,"duplicate":0},
            "file_results":[{"recognized_row_count":3,"diagnostic":{"total_locations":1,"locations":[{"row_number":2}]}}],
            "amounts":["-0.00","1.2300","100","-0","-0","0","-0"],"time":"2026-10-01T10:00:00.123456+00:00",
            "created_at":"1800-01-01T00:00:00+00:00",
            "ids":[34038115944072999195166050254573576354,9223372036854775807],"source_payload":$payload,"empty":null
        }""")
        assertEquals(expected, freezeLocalCashImportPreviewPlan(value))
        assertEquals(expected, freezeLocalCashImportPreviewPlan(expected.jsonObject))
        assertEquals(original, localCashImportDigest(listOf(value)))
        assertEquals(expected, freezeLocalCashImportPreviewPlan(value))
    }

    @Test fun frozenNullableCountsAndNestedRawNullsRemainReadable() {
        val frozen = Json.parseToJsonElement("""{"file_results":[{"recognized_row_count":null,"diagnostic":{"total_locations":1,"locations":[{"row_number":null}]}}],"source_payload":{"empty":null,"float":1.0}}""").jsonObject
        assertEquals(frozen, freezeLocalCashImportPreviewPlan(frozen))
    }

    @Test fun invalidMetadataCountsAndNonfiniteValuesNeverProduceFrozenJson() {
        for (count in listOf(-1, 1.5, Long.MAX_VALUE, Double.POSITIVE_INFINITY, "1")) {
            assertFailsWith<InvalidCashValue> { freezeLocalCashImportPreviewPlan(mapOf("summary" to mapOf("created_posted" to count))) }
            assertFailsWith<InvalidCashValue> { freezeLocalCashImportPreviewPlan(mapOf("file_results" to listOf(mapOf("recognized_row_count" to count)))) }
        }
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Json.parseToJsonElement("1e9999"), Any()))
            assertFailsWith<InvalidCashValue> { freezeLocalCashImportPreviewPlan(mapOf("value" to value)) }
        assertFails { freezeLocalCashImportPreviewPlan(mapOf("note" to 0xd800.toChar().toString())) }
        assertEquals(Json.parseToJsonElement("""{"summary":{"count":2147483647},"file_results":[{"recognized_row_count":null}]}"""),
            freezeLocalCashImportPreviewPlan(mapOf("summary" to mapOf("count" to Int.MAX_VALUE),
                "file_results" to listOf(mapOf("recognized_row_count" to null)))))
    }
}
