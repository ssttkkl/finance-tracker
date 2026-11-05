package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class CashSourceMatchGoldenTest {
    private fun data(): JsonObject = Json.parseToJsonElement(requireNotNull(
        javaClass.getResourceAsStream("/python-cash-source-match.json")).bufferedReader().use { it.readText() }).jsonObject

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
        is JsonArray -> value.map(::toAny)
        is JsonObject -> value.entries.associate { it.key to toAny(it.value) }
    }

    private fun coverage(value: JsonObject): CashSourceCoverage = CashSourceCoverage(
        value["start"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
        value["end"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
        value.getValue("complete").jsonPrimitive.boolean, value.getValue("evidence").jsonPrimitive.content,
        value.getValue("filters").jsonObject.entries.associate { it.key to it.value.jsonPrimitive.content },
        value.getValue("strategy_version").jsonPrimitive.content,
        value.getValue("start_inclusive").jsonPrimitive.boolean, value.getValue("end_inclusive").jsonPrimitive.boolean,
        value.getValue("time_precision").jsonPrimitive.content,
        value["key"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
        value["total"]?.takeIf { it != JsonNull }?.jsonPrimitive?.intOrNull,
        value.getValue("pages").jsonObject.entries.associate { it.key to it.value.jsonPrimitive.content })

    @Test fun resolutionsMatchPython() {
        val data = data()
        for (raw in data.getValue("cases").jsonArray) {
            val item = raw.jsonObject
            val name = item.getValue("name").jsonPrimitive.content
            val rows = item.getValue("rows").jsonArray.map { value ->
                val row = value.jsonObject
                SourceOccurrence(row.getValue("source_type").jsonPrimitive.content,
                    row["account"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    row.getValue("digest").jsonPrimitive.content, row.getValue("parser_version").jsonPrimitive.content,
                    row.getValue("table_key").jsonPrimitive.content, row.getValue("position").jsonPrimitive.int,
                    row.getValue("fields").jsonObject.entries.associate { it.key to toAny(it.value) },
                    coverage(row.getValue("coverage").jsonObject),
                    row["provider_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull, null,
                    row["document_position"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull)
            }
            val records = item.getValue("records").jsonArray.map { value ->
                val row = value.jsonObject
                SourceRecord(row.getValue("transaction_id").jsonPrimitive.content,
                    row.getValue("source_type").jsonPrimitive.content,
                    row["account"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    row.getValue("fields").jsonObject.entries.associate { it.key to toAny(it.value) },
                    row.getValue("coverages").jsonArray.map { coverage(it.jsonObject) },
                    row.getValue("binding_keys").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                    row.getValue("protected_fields").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                    row["stable_evidence"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    row["provider_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    row["provider_binding_key"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    row["stable_evidence_binding_key"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    1, row.getValue("deleted").jsonPrimitive.boolean,
                    row.getValue("file_occurrences").jsonObject.entries.associate { it.key to
                        it.value.jsonArray.map { page -> page.jsonPrimitive.content }.toSet() })
            }
            val targets = item.getValue("targets").jsonObject.entries.associate { it.key to
                it.value.jsonArray.map { target -> target.jsonPrimitive.content }.toSet() }
            val result = runCatching {
                if (targets.isNotEmpty() || rows.size != 1 || name == "doc-conflict")
                    CashSourceResolver.resolveSourceRows(rows, records, targets)
                else CashSourceResolver.resolveSourceFile(rows, records)
            }
            if (!item.getValue("ok").jsonPrimitive.boolean) {
                assertEquals(item.getValue("error").jsonPrimitive.content, result.exceptionOrNull()?.message, name)
                continue
            }
            val resolutions = result.getOrElse { throw AssertionError(name, it) }
            val expected = item.getValue("resolutions").jsonArray
            assertEquals(expected.size, resolutions.size, name)
            for ((index, rawResolution) in expected.withIndex()) {
                val value = rawResolution.jsonObject
                val actual = resolutions[index]
                assertEquals(value.getValue("action").jsonPrimitive.content, actual.action, name)
                assertEquals(value["transaction_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    actual.transactionId, name)
                assertEquals(value.getValue("candidate_ids").jsonArray.map { it.jsonPrimitive.content },
                    actual.candidateIds, name)
                assertEquals(value.getValue("reason").jsonPrimitive.content, actual.reason, name)
                assertEquals(value.getValue("bind").jsonPrimitive.boolean, actual.bind, name)
                assertEquals(value["duplicate_of"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    actual.duplicateOf, name)
            }
        }
        val provider = data.getValue("allocated_ids").jsonObject.getValue("provider").jsonPrimitive.content
        val row = SourceOccurrence("ccb_debit", "6222", "f".repeat(64), "p", "t", 1,
            mapOf("amount" to "-20.00"), providerId = "p1")
        assertEquals(provider, CashSourceResolver.allocateSourceRecordId(row))
        assertTrue(CashSourceResolver.allocateSourceRecordId(row.copy(providerId = null)).startsWith("import-v1:"))
    }
    @Test fun unknownTargetFailsClosed() {
        val row = SourceOccurrence("ccb_debit", "6222", "f".repeat(64), "p", "t", 1,
            mapOf("amount" to "-20.00", "currency" to "CNY", "occurred_at" to "2026-08-15T02:00:00+00:00"))
        assertFailsWith<IllegalStateException> {
            CashSourceResolver.resolveSourceRows(listOf(row), emptyList(), mapOf(row.occurrenceKey to setOf("ghost")))
        }
    }

    @Test fun temporalRecordFieldsIndexLikeStrings() {
        val base = mapOf("amount" to "-20.00", "currency" to "CNY", "occurred_at" to "2026-08-15T02:00:00+00:00",
            "record_type" to "consumption", "record_subtype" to "not_applicable")
        val cov = CashSourceCoverage("2026-07-31T16:00:00+00:00", "2026-08-31T16:00:00+00:00", true, "e",
            emptyMap(), "v", true, false)
        val row = SourceOccurrence("ccb_debit", "6222", "f".repeat(64), "p", "t", 1, base, cov)
        val record = SourceRecord("t1", "ccb_debit", "6222",
            base + ("occurred_at" to java.time.Instant.parse("2026-08-15T02:00:00Z")), coverages = listOf(cov))
        assertEquals("duplicate", CashSourceResolver.resolveSourceRows(listOf(row), listOf(record)).single().action)
    }

}
