package com.finance.tracker

import com.finance.tracker.business.contracts.*
import com.finance.tracker.data.LocalCashImportSubmission
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashImportSubmissionTest {
    @Test fun aBaseRejectionSurvivesEncodingWithoutChangingTheOriginalOperation() {
        val original = LocalCashImportSubmission.freeze("original-key", localCashWriteSetFixture())
        val old = JsonObject(Json.parseToJsonElement(original.encode()).jsonObject - "baseRejected")
        assertFalse(LocalCashImportSubmission.decode(old.toString()).baseRejected)
        val rejected = original.withBaseRejection()
        val restored = LocalCashImportSubmission.decode(rejected.encode())
        assertTrue(restored.baseRejected); assertNull(restored.receipt())
        assertEquals(original.idempotencyKey, restored.idempotencyKey)
        assertEquals(original.requestBody, restored.requestBody)
        assertEquals(rejected.encode(), rejected.withBaseRejection().encode())
        assertFalse(original.baseRejected)
    }

    @Test fun anAuthoritativeReceiptSupersedesAnEarlierSingleAttemptRejection() {
        val original = LocalCashImportSubmission.freeze("original-key", localCashWriteSetFixture())
        val committed = original.withBaseRejection().withReceipt(localCashWriteSetReceiptFixture())
        assertFalse(committed.baseRejected)
        assertEquals(localCashWriteSetReceiptFixture(), LocalCashImportSubmission.decode(committed.encode()).receipt())
        assertFails { committed.withBaseRejection() }
        val conflicting = JsonObject(Json.parseToJsonElement(committed.encode()).jsonObject + ("baseRejected" to JsonPrimitive(true)))
        assertFails { LocalCashImportSubmission.decode(conflicting.toString()) }
        for (bad in listOf(JsonNull, JsonPrimitive("true"))) assertFails {
            LocalCashImportSubmission.decode(JsonObject(Json.parseToJsonElement(original.encode()).jsonObject + ("baseRejected" to bad)).toString())
        }
    }

    @Test fun frozenRequestKeepsExactNumbersAndCallerChangesCannotReplaceIt() {
        val payload = linkedMapOf<String, JsonElement>("exact" to Json.parseToJsonElement("1.000000000000000001"))
        val original = localCashWriteSetFixture(JsonObject(payload))
        val frozen = LocalCashImportSubmission.freeze("original-key", original)
        payload["exact"] = JsonPrimitive("changed")
        val restored = LocalCashImportSubmission.restore(frozen.idempotencyKey, frozen.requestBody)
        assertEquals("original-key", restored.idempotencyKey)
        assertEquals(frozen.requestBody, restored.requestBody)
        val row = Json.parseToJsonElement(restored.requestBody).jsonObject.getValue("changes").jsonObject
            .getValue("transactions").jsonArray.single().jsonObject
        assertEquals("1.000000000000000001", row.getValue("values").jsonObject.getValue("source_payload").jsonObject.getValue("exact").jsonPrimitive.content)
        assertNull(restored.receipt())
    }

    @Test fun frozenCompactTransportSurvivesEncodingAndIsDroppedAfterAReceipt() {
        val original = LocalCashImportSubmission.freeze("original-key", localCashWriteSetFixture())
        val compact = JsonObject(Json.parseToJsonElement(original.requestBody).jsonObject +
            ("command_version" to JsonPrimitive(2)) +
            ("request_digest" to JsonPrimitive(com.finance.tracker.business.domain.cashWriteSetRequestDigest(original.request())))).toString()
        val prepared = original.withTransport(compact)
        val restored = LocalCashImportSubmission.decode(prepared.encode())
        assertEquals(compact, restored.transportBody)
        assertEquals(original.requestBody, restored.requestBody)
        assertFails { prepared.withTransport(compact) }
        assertNull(LocalCashImportSubmission.decode(prepared.withReceipt(localCashWriteSetReceiptFixture()).encode()).transportBody)
        val wrongBase = JsonObject(Json.parseToJsonElement(compact).jsonObject + ("base_ledger_seq" to JsonPrimitive("8"))).toString()
        assertFails { original.withTransport(wrongBase) }
    }

    @Test fun committedReceiptIsFrozenWithAllIdentitiesAndCannotBeReplaced() {
        val receipt = localCashWriteSetReceiptFixture()
        val prepared = LocalCashImportSubmission.freeze("original-key", localCashWriteSetFixture())
        val committed = prepared.withReceipt(receipt)
        assertEquals(receipt, committed.receipt())
        assertEquals(committed.receiptBody, committed.withReceipt(receipt).receiptBody)
        assertFailsWith<IllegalStateException> { committed.withReceipt(receipt.copy(receiptId = "different")) }
        assertEquals("9007199254740993", committed.receipt()?.writeSetResult?.transactions?.single()?.id)
        assertFails { prepared.withReceipt(receipt.copy(committed = false)) }
    }
}

internal fun localCashWriteSetFixture(payload: JsonElement = JsonObject(emptyMap())): CashWriteSetRequestDto {
    val target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("row"), null)
    val row = CashTransactionWriteDto(target, CashTransactionWriteValuesDto("2", "posted", "import", null, "atomic", "ccb_debit", "source",
        payload, JsonObject(emptyMap()), null, JsonObject(emptyMap()), "2026-11-14T00:00:00Z", "-12.30", "CNY", "Party", "", JsonArray(emptyList()), "S", null, "consumption", "not_applicable"),
        listOf(CashComponentWriteDto(NewCashWriteReferenceDto("part"), "2", "-12.30", "CNY", "0", "", "", JsonObject(emptyMap()))))
    return CashWriteSetRequestDto("7", "cash-rules-v1", CashWriteSetDto(transactions = listOf(row)))
}

internal fun localCashWriteSetReceiptFixture(): CashMutationReceiptDto = Json.decodeFromString("""{
    "receipt_id":"receipt","operation_kind":"cash_write_set","committed":true,"created_at":"2026-11-15T00:00:00Z",
    "ledger_seq":"9","write_set_result":{"transactions":[{"id":"9007199254740993","row_revision":"2","component_ids":["5"]}],
    "source_bindings":[],"issues":[],"statement_account_mappings":[],"relations":[],
    "entries":[{"id":"entry","root_transaction_id":"9007199254740993","identity_transaction_id":"9007199254740993","revision":"1"}],
    "resolved_issue_ids":[],"retired_entry_ids":["retired"]}}
""")
