package com.finance.tracker.business.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CashSourceCoverageContractTest {
    @Test
    fun serializedCoverageKeepsItsExistingFieldsAndDefaults() {
        val json = Json { encodeDefaults = true; explicitNulls = true }
        val wire = """{"start":null,"end":null,"complete":false,"evidence":"","filters":{},"strategyVersion":"v1","startInclusive":true,"endInclusive":false,"timePrecision":"second","documentKey":null,"pageTotal":null,"pageFingerprints":{}}"""
        val value = CashSourceCoverage(null, null, false, "", emptyMap(), "v1")
        assertEquals(wire, json.encodeToString(value))
        assertEquals(value, json.decodeFromString<CashSourceCoverage>(wire))
    }

    @Test
    fun documentEvidenceValidationRetainsItsBoundsAndError() {
        val valid = CashSourceCoverage(null, null, false, "", emptyMap(), "v1",
            documentKey = "a".repeat(64), pageTotal = 2, pageFingerprints = mapOf("1" to "b".repeat(64)))
        assertEquals(valid, Json.decodeFromString<CashSourceCoverage>(Json.encodeToString(valid)))
        val invalid = listOf<() -> CashSourceCoverage>(
            { valid.copy(documentKey = null) }, { valid.copy(documentKey = "not a digest") },
            { valid.copy(pageTotal = null) }, { valid.copy(pageTotal = 0) }, { valid.copy(pageTotal = 2001) },
            { valid.copy(pageFingerprints = emptyMap()) }, { valid.copy(pageFingerprints = mapOf("0" to "b".repeat(64))) },
            { valid.copy(pageFingerprints = mapOf("3" to "b".repeat(64))) },
            { valid.copy(pageFingerprints = mapOf("01" to "b".repeat(64))) },
            { valid.copy(pageFingerprints = mapOf("1" to "bad")) },
        )
        for (create in invalid) assertEquals("invalid_source_document_evidence",
            assertFailsWith<IllegalArgumentException> { create() }.message)
    }
}
