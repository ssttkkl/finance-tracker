package com.finance.tracker

import com.finance.tracker.data.*
import kotlinx.serialization.json.*
import kotlin.test.*

class LedgerReceiptMappingTest {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private val legacy = """{"receipt_id":"receipt","plan_id":null,"operation_kind":"import","committed":true,"created_at":"2026-01-01T00:00:00Z"}"""
    @Test fun exactVersionRemainsTextAcrossTheReceiptAndResolveModels() {
        for (sequence in listOf("0", "9007199254740993", "9223372036854775807")) {
            val body = JsonObject(json.parseToJsonElement(legacy).jsonObject + ("ledger_seq" to JsonPrimitive(sequence)))
            val dto = json.decodeFromJsonElement<CashMutationReceiptDto>(body)
            assertEquals(sequence, dto.toDomain().ledgerSeq)
            assertEquals(JsonPrimitive(sequence), json.encodeToJsonElement(dto).jsonObject["ledger_seq"])
            val resolved = CashMutationResolveDto("committed", dto).toDomain()
            assertEquals(sequence, resolved.receipt?.ledgerSeq)
        }
    }
    @Test fun legacyAbsenceIsPreservedEvenWhenDefaultFieldsAreEncoded() {
        val dto = json.decodeFromString<CashMutationReceiptDto>(legacy)
        assertNull(dto.toDomain().ledgerSeq)
        assertFalse("ledger_seq" in json.encodeToJsonElement(dto).jsonObject)
    }
    @Test fun malformedRemoteVersionsFailRatherThanBeingRoundedOrIgnored() {
        for (raw in listOf("", "-1", "+1", "01", " 1", "1.0", "١", "9223372036854775808")) {
            val body = JsonObject(json.parseToJsonElement(legacy).jsonObject + ("ledger_seq" to JsonPrimitive(raw)))
            assertEquals("ledger_version_invalid", assertFailsWith<IllegalStateException> {
                json.decodeFromJsonElement<CashMutationReceiptDto>(body).toDomain()
            }.message)
        }
    }
    @Test fun remoteVersionMustBeJsonText() {
        for (value in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap()))) {
            val body = JsonObject(json.parseToJsonElement(legacy).jsonObject + ("ledger_seq" to value))
            assertFails { json.decodeFromJsonElement<CashMutationReceiptDto>(body).toDomain() }
            val resolve = buildJsonObject { put("status", "committed"); put("receipt", body) }
            assertFails { json.decodeFromJsonElement<CashMutationResolveDto>(resolve).toDomain() }
        }
    }
}
