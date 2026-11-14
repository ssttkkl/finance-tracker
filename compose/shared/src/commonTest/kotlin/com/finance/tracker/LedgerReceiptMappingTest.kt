package com.finance.tracker

import com.finance.tracker.data.*
import com.finance.tracker.business.contracts.CashWriteSetResultDto
import com.finance.tracker.core.TokenStore
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
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
        assertNull(dto.toDomain().writeSetResult)
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
    private val result = """{
        "transactions":[{"id":"9007199254740993","row_revision":"9223372036854775807","component_ids":["9007199254740995","2"]}],
        "source_bindings":[{"id":"5","revision":"8"}],"issues":[{"id":"6","revision":"9"}],
        "statement_account_mappings":[{"id":"7","revision":"10"}],"relations":[{"id":"8","revision":"11"}],
        "entries":[{"id":"条目😀","root_transaction_id":"9007199254740993","identity_transaction_id":"2","revision":"12"}],
        "resolved_issue_ids":["13","14"],"retired_entry_ids":["旧条目二","旧条目一"]
    }"""
    private fun receiptWithResult() = JsonObject(json.parseToJsonElement(legacy).jsonObject + mapOf(
        "operation_kind" to JsonPrimitive("cash_write_set"), "write_set_result" to json.parseToJsonElement(result)))

    @Test fun exactIdentitySnapshotSurvivesDomainAndResolveMapping() {
        val expected = json.decodeFromString<CashWriteSetResultDto>(result)
        val dto = json.decodeFromJsonElement<CashMutationReceiptDto>(receiptWithResult())
        assertEquals(expected, dto.toDomain().writeSetResult)
        assertEquals(expected, CashMutationResolveDto("committed", dto).toDomain().receipt?.writeSetResult)
        assertEquals("9007199254740993", dto.toDomain().writeSetResult?.transactions?.single()?.id)
        assertEquals(listOf("9007199254740995", "2"), dto.toDomain().writeSetResult?.transactions?.single()?.componentIds)
    }

    @Test fun receiptIdAndOriginalKeyReturnTheCompleteRemoteSnapshot() = runTest {
        val body = receiptWithResult(); val expected = json.decodeFromString<CashWriteSetResultDto>(result)
        val paths = mutableListOf<String>()
        val store = object : TokenStore {
            private var current: String? = "synthetic-token"
            override suspend fun get() = current
            override suspend fun set(value: String) { current = value }
            override suspend fun clear() { current = null }
        }
        val api = FinanceApiClient({ "https://api.example.test" }, store, MockEngine { request ->
            paths += request.url.encodedPath
            respond(body.toString(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val repository = KtorCashImportLifecycleRepository(api)
            assertEquals(expected, repository.receipt("receipt").writeSetResult)
            assertEquals(expected, repository.receiptByKey("original/+?&").writeSetResult)
            assertEquals(listOf("/api/v1/cash-mutations/receipts/receipt", "/api/v1/cash-mutations/receipts/by-key"), paths)
        } finally { api.close() }
    }
    @Test fun invalidRemoteIdentityResultFailsBeforePublishingOrRefreshingTheReceipt() = runTest {
        val body = receiptWithResult().toMutableMap()
        body["write_set_result"] = JsonObject(body.getValue("write_set_result").jsonObject + ("unknown" to JsonPrimitive("not allowed")))
        val store = object : TokenStore {
            override suspend fun get(): String = "synthetic-token"
            override suspend fun set(value: String) = Unit
            override suspend fun clear() = Unit
        }
        val api = FinanceApiClient({ "https://api.example.test" }, store, MockEngine {
            respond(JsonObject(body).toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            var refreshes = 0
            val repository = KtorCashImportLifecycleRepository(api, captureRefresh = { { _: String? -> refreshes++ } })
            assertFails { repository.receiptByKey("original") }
            assertEquals(0, refreshes)
        } finally { api.close() }
    }
}
