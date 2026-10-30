package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class CashSourceBatchGoldenTest {
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

    @Test fun completeBatchResultsMatchOriginalFunctionalCases() {
        val cases = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/python-cash-source-batch.json"))
            .bufferedReader().use { it.readText() }).jsonArray
        assertEquals(49, cases.size)
        for ((caseIndex, raw) in cases.withIndex()) {
            val item = raw.jsonObject
            val name = "$caseIndex: ${item.getValue("source").jsonPrimitive.content}"
            val rows = item.getValue("rows").jsonArray.map { value ->
                val row = value.jsonObject
                SourceOccurrence(row.getValue("source_type").jsonPrimitive.content,
                    row["account"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    row.getValue("digest").jsonPrimitive.content, row.getValue("parser_version").jsonPrimitive.content,
                    row.getValue("table_key").jsonPrimitive.content, row.getValue("position").jsonPrimitive.int,
                    row.getValue("fields").jsonObject.entries.associate { it.key to toAny(it.value) },
                    coverage(row.getValue("coverage").jsonObject),
                    row["provider_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull, row["stable_evidence"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
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
            val refs = item.getValue("rows").jsonArray.map { it.jsonObject.getValue("ref").jsonPrimitive.content }
            val metadata = item.getValue("file_metadata").jsonObject.mapValues { (_, value) ->
                value.jsonArray[0].jsonPrimitive.int to value.jsonArray[1].let { if (it == JsonNull) null else it.jsonPrimitive.long }
            }
            val evidence = item.getValue("document_evidence").jsonArray.map { it.jsonObject }.map {
                SourceDocumentEvidence(it.getValue("source_type").jsonPrimitive.content,
                    it.getValue("parser_version").jsonPrimitive.content, coverage(it.getValue("coverage").jsonObject))
            }
            val result = CashSourceBatch.resolve(SourceBatchRows(LinkedHashMap(refs.zip(rows).toMap()), evidence,
                evidence.map { it.coverage }, metadata), records)
            val actual = sourceJson(mapOf("rows" to result.rows.map {
                mapOf("row_ref" to it.rowRef, "action" to it.action, "transaction_id" to it.transactionId,
                    "candidate_ids" to it.candidateIds, "reason" to it.reason, "bind" to it.bind,
                    "duplicate_of" to it.duplicateOf, "group_ref" to it.groupRef)
            }, "groups" to result.groups.map {
                mapOf("group_ref" to it.groupRef, "row_refs" to it.rowRefs, "candidate_ids" to it.candidateIds)
            }))
            assertEquals(item.getValue("expected"), actual, name)
        }
    }
}
