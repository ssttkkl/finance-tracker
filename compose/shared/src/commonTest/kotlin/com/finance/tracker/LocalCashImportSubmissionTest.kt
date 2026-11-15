package com.finance.tracker

import com.finance.tracker.business.contracts.*
import com.finance.tracker.data.LocalCashImportSubmission
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashImportSubmissionTest {
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
