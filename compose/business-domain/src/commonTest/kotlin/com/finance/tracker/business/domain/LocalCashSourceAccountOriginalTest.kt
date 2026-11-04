package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalCashSourceAccountOriginalTest {
    private fun decode(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonObject -> value.mapValues { decode(it.value) }
        is JsonArray -> value.map(::decode)
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.booleanOrNull != null -> value.boolean
            value.intOrNull != null -> SourceJsonInteger(value.int)
            else -> value.double
        }
    }
    @Suppress("UNCHECKED_CAST")
    private fun row(value: JsonElement) = decode(value) as Map<String, Any?>
    private fun json(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        // These result schemas contain only generated integer ordinals and converted account IDs.
        is Number -> JsonPrimitive(value.toLong())
        is List<*> -> JsonArray(value.map(::json))
        is Map<*, *> -> JsonObject(value.entries.associate { it.key as String to json(it.value) })
        else -> error("unexpected_source_account_output")
    }
    @Test fun productionSourceAccountConfigurationMatchesCompleteOriginalOracle() {
        val all = Json.parseToJsonElement(LocalSourceAccountOriginalVectors.json).jsonObject
        for (raw in all.getValue("components").jsonArray) {
            val item = raw.jsonObject; val row = row(item.getValue("row"))
            assertEquals(item.getValue("components"), json(LocalCashSourceAccountMapping.parseAlipayPaymentComponents(row)))
            assertEquals(item.getValue("status").jsonPrimitive.content, LocalCashSourceAccountMapping.alipayComponentAllocationStatus(row))
        }
        for (raw in all.getValue("allocations").jsonArray) {
            val item = raw.jsonObject
            val result = runCatching { LocalCashSourceAccountMapping.buildComponentAllocationDraft(row(item.getValue("row")), item["allocations"]?.let(::decode)) }
            val error = item["error"]?.jsonPrimitive?.content
            if (error != null) assertEquals(error, (result.exceptionOrNull() as? CashAccountMappingFailure)?.message, item["name"].toString())
            else assertEquals(item.getValue("draft"), json(result.getOrThrow()), item["name"].toString())
        }
        for (raw in all.getValue("identities").jsonArray) {
            val item = raw.jsonObject; val row = row(item.getValue("row"))
            val result = runCatching { LocalCashSourceAccountMapping.sourceIdentityKey(row) }
            val error = item["error"]?.jsonPrimitive?.content
            if (error != null) assertEquals(error, (result.exceptionOrNull() as? CashAccountMappingFailure)?.message)
            else {
                assertEquals(item.getValue("identity"), json(result.getOrThrow().toList()))
                assertEquals(item.getValue("keys"), json(LocalCashSourceAccountMapping.sourceComponentIdentityKeys(row)))
            }
        }
        val rows = all.getValue("identities").jsonArray.filterIndexed { index, _ -> index !in setOf(5, 18, 19, 21, 22) }
            .map { row(it.jsonObject.getValue("row")) }
        val (groups, issues) = LocalCashSourceAccountMapping.scanSourceRowsWithIssues(rows)
        assertEquals(emptyList(), issues)
        assertEquals(all.getValue("groups"), json(groups.map { g -> mapOf(
            "group_id" to g.groupId, "source_type" to g.sourceType, "identity_kind" to g.identityKind,
            "source_account_key" to g.sourceAccountKey, "display_name" to g.displayName,
            "masked_evidence" to g.maskedEvidence, "currencies" to g.currencies,
            "row_count" to g.rowCount, "legacy_source_account_keys" to g.legacySourceAccountKeys) }))
    }
}
