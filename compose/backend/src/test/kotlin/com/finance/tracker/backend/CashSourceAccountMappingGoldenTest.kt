package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class CashSourceAccountMappingGoldenTest {
    private fun data(): JsonObject = Json.parseToJsonElement(requireNotNull(
        javaClass.getResourceAsStream("/python-cash-account-mapping.json")).bufferedReader().use { it.readText() }).jsonObject

    private fun row(value: JsonObject): Map<String, Any?> = value.entries.associate { it.key to toAny(it.value) }

    private fun toAny(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.booleanOrNull != null -> value.boolean
            value.intOrNull != null -> value.int
            value.longOrNull != null -> value.long
            value.doubleOrNull != null -> value.double
            else -> value.content
        }
        is JsonArray -> value.map { toAny(it) }
        is JsonObject -> value.entries.associate { it.key to toAny(it.value) }
    }

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value.toString())
        is List<*> -> JsonArray(value.map(::toJson))
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to toJson(it.value) })
        else -> JsonPrimitive(value.toString())
    }

    @Test fun accountMappingMatchesPython() {
        val all = data()
        for (raw in all.getValue("components").jsonArray) {
            val item = raw.jsonObject
            val row = row(item.getValue("row").jsonObject)
            val components = CashSourceAccountMapping.parseAlipayPaymentComponents(row)
            assertEquals(item.getValue("components").jsonArray, toJson(components), item.getValue("index").jsonPrimitive.int.toString())
            assertEquals(item.getValue("status").jsonPrimitive.content, CashSourceAccountMapping.alipayComponentAllocationStatus(row))
        }
        for (raw in all.getValue("allocations").jsonArray) {
            val item = raw.jsonObject
            val row = row(item.getValue("row").jsonObject)
            val allocations = item["allocations"]?.takeIf { it != JsonNull }?.let(::toAny)
            val result = runCatching { CashSourceAccountMapping.buildComponentAllocationDraft(row, allocations) }
            val expectedError = item["error"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull
            if (expectedError != null) {
                assertEquals(expectedError, (result.exceptionOrNull() as? CashAccountMappingFailure)?.message,
                    item.getValue("name").jsonPrimitive.content)
            } else {
                assertEquals(item.getValue("draft").jsonObject, toJson(result.getOrThrow()),
                    item.getValue("name").jsonPrimitive.content)
            }
        }
        for (raw in all.getValue("identities").jsonArray) {
            val item = raw.jsonObject
            val row = row(item.getValue("row").jsonObject)
            val expectedError = item["error"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull
            if (expectedError != null) {
                val failure = runCatching {
                    CashSourceAccountMapping.sourceComponentIdentityKeys(row)
                    CashSourceAccountMapping.sourceIdentityKey(row)
                }.exceptionOrNull()
                assertEquals(expectedError, (failure as? CashAccountMappingFailure)?.message)
                continue
            }
            val keys = CashSourceAccountMapping.sourceComponentIdentityKeys(row)
            assertEquals(item.getValue("keys").jsonArray.map { key -> key.jsonArray.map { it.jsonPrimitive.content } }, keys)
            assertEquals(item.getValue("identity").jsonArray.map { it.jsonPrimitive.content },
                CashSourceAccountMapping.sourceIdentityKey(row).toList())
        }
        val groups = CashSourceAccountMapping.scanSourceRowsWithIssues(
            all.getValue("groups").jsonArray.let { array ->
                // Groups golden only carries outputs; reconstruct rows from the first source list.
                all.getValue("identities").jsonArray.map { it.jsonObject.getValue("row").jsonObject }
                    .filterIndexed { index, _ -> index !in setOf(5, 18, 19, 21, 22) }
                    .map(::row)
            })
        assertEquals(0, groups.second.size)
        val expectedGroups = all.getValue("groups").jsonArray
        assertEquals(expectedGroups.size, groups.first.size)
        for ((index, raw) in expectedGroups.withIndex()) {
            val expected = raw.jsonObject
            val actual = groups.first[index]
            assertEquals(expected.getValue("group_id").jsonPrimitive.content, actual.groupId)
            assertEquals(expected.getValue("source_type").jsonPrimitive.content, actual.sourceType)
            assertEquals(expected.getValue("identity_kind").jsonPrimitive.content, actual.identityKind)
            assertEquals(expected.getValue("source_account_key").jsonPrimitive.content, actual.sourceAccountKey)
            assertEquals(expected.getValue("display_name").jsonPrimitive.content, actual.displayName)
            assertEquals(expected.getValue("masked_evidence").jsonPrimitive.content, actual.maskedEvidence)
            assertEquals(expected.getValue("currencies").jsonArray.map { it.jsonPrimitive.content }, actual.currencies)
            assertEquals(expected.getValue("row_count").jsonPrimitive.int, actual.rowCount)
            assertEquals(expected.getValue("legacy_source_account_keys").jsonArray.map { it.jsonPrimitive.content },
                actual.legacySourceAccountKeys)
        }
    }
}
