package com.finance.tracker.business.contracts

import kotlin.test.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

class CashWriteSetReceiptSerializationTest {
    private val result = Json.parseToJsonElement("""{
        "transactions":[{"id":"9007199254740993","row_revision":"9223372036854775807","component_ids":["9007199254740995","2"]}],
        "source_bindings":[{"id":"3","revision":"1"}],"issues":[{"id":"4","revision":"2"}],
        "statement_account_mappings":[{"id":"5","revision":"3"}],"relations":[{"id":"6","revision":"4"}],
        "entries":[{"id":"条目😀","root_transaction_id":"9007199254740993","identity_transaction_id":"2","revision":"5"}],
        "resolved_issue_ids":["7"],"retired_entry_ids":["旧条目"]
    }""").jsonObject
    private fun receipt(value: JsonElement = result) = buildJsonObject {
        put("receipt_id", "receipt"); put("operation_kind", "cash_write_set"); put("committed", true)
        put("created_at", "2026-11-14T00:00:00Z"); put("write_set_result", value)
    }
    @Test fun actualIdentitiesAndOriginalArrayPositionsRoundTripWithoutNumericConversion() {
        val decoded = Json.decodeFromJsonElement<CashMutationReceiptDto>(receipt())
        assertEquals(result, Json.encodeToJsonElement(decoded).jsonObject["write_set_result"])
    }
    @Test fun strictTextReceiptValidatesResultAndPreservesLongStrings() {
        val expected = Json.decodeFromJsonElement<CashMutationReceiptDto>(receipt())
        assertEquals(expected, decodeArchivedCashMutationReceipt(receipt().toString()))
        val historical = receipt().toString().replace("\"entries\":[", "\"entries\":false,\"entries\":[")
        assertEquals(expected, decodeArchivedCashMutationReceipt(historical))
        for (invalid in listOf(JsonNull, JsonObject(result - "entries"),
            JsonObject(result + ("unknown" to JsonPrimitive(true))))) {
            assertFailsWith<SerializationException> { decodeArchivedCashMutationReceipt(receipt(invalid).toString()) }
        }
    }

    @Test fun oldReceiptsDoNotAcquireAnEmptyResultEvenWhenEncodingDefaults() {
        val json = Json { encodeDefaults = true }
        val old = JsonObject(receipt().filterKeys { it != "write_set_result" })
        assertFalse("write_set_result" in json.encodeToJsonElement(json.decodeFromJsonElement<CashMutationReceiptDto>(old)).jsonObject)
    }
    @Test fun resultIsClosedAndCompleteEvenInsideAnOtherwiseLenientReceiptReader() {
        val json = Json { ignoreUnknownKeys = true }
        for (invalid in listOf(JsonNull, JsonArray(emptyList()), JsonObject(result - "entries"), JsonObject(result + ("payload" to JsonPrimitive("secret"))),
            JsonObject(result + ("issues" to JsonArray(listOf(buildJsonObject { put("id", "1"); put("revision", "2"); put("payload", "secret") })))))) {
            assertFailsWith<SerializationException> { json.decodeFromJsonElement<CashMutationReceiptDto>(receipt(invalid)) }
        }
    }
    @Test fun everyNumericIdentityAndRevisionRequiresCanonicalPositiveLongString() {
        for (invalid in listOf(JsonPrimitive(1), JsonPrimitive(true), JsonNull, JsonPrimitive("0"), JsonPrimitive("-1"), JsonPrimitive("01"),
            JsonPrimitive("+1"), JsonPrimitive("1.0"), JsonPrimitive("9223372036854775808"))) {
            val row = result.getValue("transactions").jsonArray.single().jsonObject
            for (field in listOf("id", "row_revision", "component_ids")) {
                val changed = JsonObject(row + (field to if (field == "component_ids") JsonArray(listOf(invalid)) else invalid))
                val input = JsonObject(result + ("transactions" to JsonArray(listOf(changed))))
                assertFailsWith<SerializationException> { Json.decodeFromJsonElement<CashMutationReceiptDto>(receipt(input)) }
            }
            for (field in listOf("source_bindings", "issues", "statement_account_mappings", "relations")) {
                val input = JsonObject(result + (field to JsonArray(listOf(buildJsonObject { put("id", invalid); put("revision", "1") }))))
                assertFailsWith<SerializationException> { Json.decodeFromJsonElement<CashMutationReceiptDto>(receipt(input)) }
            }
        }
    }
    @Test fun emptyCommandResultStillContainsEveryRequiredCollection() {
        val empty = JsonObject(result.mapValues { JsonArray(emptyList()) })
        val decoded = Json.decodeFromJsonElement<CashMutationReceiptDto>(receipt(empty))
        assertEquals(empty, Json.encodeToJsonElement(decoded).jsonObject["write_set_result"])
    }
    @Test fun malformedConstructedResultsCannotBeEncoded() {
        val decoded = Json.decodeFromJsonElement<CashMutationReceiptDto>(receipt())
        val value = decoded.writeSetResult!!
        val row = value.transactions.single()
        for (invalid in listOf(row.copy(id = "01"), row.copy(rowRevision = "0"), row.copy(componentIds = listOf("9223372036854775808")))) {
            assertFailsWith<SerializationException> { Json.encodeToString(decoded.copy(writeSetResult = value.copy(transactions = listOf(invalid)))) }
        }
    }
    @Test fun opaqueEntryIdentitiesStillRequireNonemptyWellFormedStrings() {
        val row = result.getValue("entries").jsonArray.single().jsonObject
        for (id in listOf(JsonPrimitive(1), JsonNull, JsonPrimitive(""), JsonPrimitive("\uD800"))) {
            val entries = JsonObject(result + ("entries" to JsonArray(listOf(JsonObject(row + ("id" to id))))))
            val retired = JsonObject(result + ("retired_entry_ids" to JsonArray(listOf(id))))
            for (input in listOf(entries, retired)) {
                assertFailsWith<SerializationException> { Json.decodeFromJsonElement<CashMutationReceiptDto>(receipt(input)) }
            }
        }
    }
}
