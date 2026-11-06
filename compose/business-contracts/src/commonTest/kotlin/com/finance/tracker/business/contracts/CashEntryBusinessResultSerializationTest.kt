package com.finance.tracker.business.contracts

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.test.*

class CashEntryBusinessResultSerializationTest {
    private val root = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("row"), null)
    private fun business() = buildJsonObject {
        put("root_transaction", Json.encodeToJsonElement(CashWriteVersionedReferenceDto.serializer(), root))
        put("category_id", JsonNull); put("note", "D"); put("counterparty", "merchant"); put("counterparty_account", "")
        put("counterparty_account_attrs", JsonArray(emptyList())); put("manual_fields", JsonArray(listOf(JsonPrimitive("note"))))
        put("members", JsonArray(emptyList()))
    }

    @Test fun aFinalEntryDescribesItsBusinessGroupWithoutPredictingStorageIdentity() {
        val value = Json.decodeFromJsonElement<CashEntryWriteDto>(business())
        assertEquals(root, value.rootTransaction)
        assertEquals("D", value.note)
        val encoded = Json.encodeToJsonElement(CashEntryWriteDto.serializer(), value).jsonObject
        assertFalse("target" in encoded)
        assertFalse("identity_transaction" in encoded)
        assertFalse("split_inherit" in encoded)
        for (field in listOf("target", "identity_transaction", "split_inherit")) {
            assertFailsWith<SerializationException> { Json.decodeFromJsonElement<CashEntryWriteDto>(JsonObject(business() + (field to JsonNull))) }
        }
    }

    @Test fun theOldEntryRevisionReadSetIsSeparateFromResultsAndClientRetirementInstructions() {
        val dependency = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("opaque-old-entry"), "9007199254740993")
        val input = buildJsonObject {
            put("entry_dependencies", JsonArray(listOf(Json.encodeToJsonElement(CashWriteVersionedReferenceDto.serializer(), dependency))))
        }
        val value = Json.decodeFromJsonElement<CashWriteSetDto>(input)
        val encoded = Json.encodeToJsonElement(CashWriteSetDto.serializer(), value).jsonObject
        assertEquals(input.getValue("entry_dependencies"), encoded.getValue("entry_dependencies"))
        assertFalse("deleted_entries" in encoded)
        assertFailsWith<SerializationException> {
            Json.decodeFromJsonElement<CashWriteSetDto>(buildJsonObject { put("deleted_entries", JsonArray(emptyList())) })
        }
    }
}
