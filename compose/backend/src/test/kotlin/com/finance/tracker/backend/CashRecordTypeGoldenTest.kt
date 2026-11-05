package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class CashRecordTypeGoldenTest {
    private fun cases(): JsonObject = Json.parseToJsonElement(requireNotNull(
        javaClass.getResourceAsStream("/python-cash-record-types.json")).bufferedReader().use { it.readText() }).jsonObject

    private fun JsonElement?.toAny(): Any? = when (this) {
        null, JsonNull -> null
        is JsonPrimitive -> when {
            isString -> content
            booleanOrNull != null -> boolean
            intOrNull != null -> int
            longOrNull != null -> long
            doubleOrNull != null -> double
            else -> content
        }
        is JsonArray -> map { it.toAny() }
        is JsonObject -> entries.associate { it.key to it.value.toAny() }
    }

    private fun JsonObject.row(): Map<String, Any?> = entries.associate { it.key to it.value.toAny() }

    private fun failureMessage(block: () -> Unit): String? = try {
        block(); null
    } catch (failure: CashRecordRuleFailure) {
        failure.message
    }

    @Test fun classificationMatchesPython() {
        val rows = cases().getValue("rows").jsonArray
        assertEquals(224, rows.size)
        for (case in rows) {
            val item = case.jsonObject
            val source = item["source"]?.toAny() as? String
            val row = item.getValue("row").jsonObject.row()
            val type = item.getValue("type").jsonPrimitive.content
            val pair = CashRecordClassification.classify(source, row)
            assertEquals(type, pair.first, "$source $row")
            assertEquals(item.getValue("record_type").jsonPrimitive.content, pair.first, "$source $row")
            assertEquals(item.getValue("record_subtype").jsonPrimitive.content, pair.second, "$source $row")
        }
    }

    @Test fun accountsMatchPythonAndProofBindsSourcePayload() {
        val accounts = cases().getValue("accounts").jsonArray
        assertEquals(35, accounts.size)
        for (case in accounts) {
            val item = case.jsonObject
            val value = item["value"]?.toAny()
            val account = CashRecordClassification.normalizeAccount(value,
                item["source"]?.toAny() as? String ?: "", item["reference"]?.toAny() as? String ?: "",
                item["payload"]?.toAny())
            assertEquals(item.getValue("account").jsonPrimitive.content, account.value, item.toString())
            assertEquals(item.getValue("attrs").jsonArray.map { it.jsonPrimitive.content }, account.attrs)
            val digest = item["digest"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull
            if (digest != null) assertEquals(digest, CashRecordClassification.sourcePayloadDigest(item["payload"]?.toAny()))
        }
        val reconstructed = CashRecordClassification.normalizeAccount("6222********1234", "icbc_asia",
            "6222000011111234", mapOf("a" to 1))
        assertEquals("6222000011111234", reconstructed.value)
        assertEquals(listOf("masked", "reconstructed"), reconstructed.attrs)
        CashRecordClassification.validateAccountForWrite(reconstructed.value, reconstructed.attrs, reconstructed,
            "icbc_asia", mapOf("a" to 1))
        assertFailsWith<CashRecordRuleFailure> {
            CashRecordClassification.validateAccountForWrite(reconstructed.value, reconstructed.attrs, reconstructed,
                "icbc_asia", mapOf("a" to 2))
        }
        assertFailsWith<CashRecordRuleFailure> {
            CashRecordClassification.validateAccountForWrite(reconstructed.value, reconstructed.attrs, null,
                "icbc_asia", mapOf("a" to 1))
        }
    }

    @Test fun subtypeAndAccountValidationMatchPython() {
        val validation = cases().getValue("validation").jsonArray
        assertEquals(163, validation.size)
        for (case in validation) {
            val item = case.jsonObject
            when (item.getValue("kind").jsonPrimitive.content) {
                "default" -> assertEquals(item.getValue("subtype").jsonPrimitive.content,
                    CashRecordClassification.defaultSubtype(item["type"]?.toAny()), item.toString())
                "subtype" -> assertEquals(item["error"]?.toAny() as? String,
                    failureMessage {
                        CashRecordClassification.validateSubtype(item["type"]?.toAny(), item["subtype"]?.toAny())
                    }, item.toString())
                "account_validation" -> assertEquals(item["error"]?.toAny() as? String,
                    failureMessage {
                        CashRecordClassification.validateAccount(item["value"]?.toAny(), item["attrs"]?.toAny())
                    }, item.toString())
                else -> fail("unknown validation kind $item")
            }
        }
    }
}
