package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class CashSourceRowsGoldenTest {
    private fun cases(): JsonArray = Json.parseToJsonElement(requireNotNull(
        javaClass.getResourceAsStream("/python-cash-source-rows.json")).bufferedReader().use { it.readText() }).jsonArray

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

    private fun coverage(element: JsonObject): CashSourceCoverage = CashSourceCoverage(
        element["start"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
        element["end"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
        element.getValue("complete").jsonPrimitive.boolean,
        element.getValue("evidence").jsonPrimitive.content,
        element.getValue("filters").jsonObject.entries.associate { it.key to it.value.jsonPrimitive.content },
        element.getValue("strategy_version").jsonPrimitive.content,
        element.getValue("start_inclusive").jsonPrimitive.boolean,
        element.getValue("end_inclusive").jsonPrimitive.boolean,
        element.getValue("time_precision").jsonPrimitive.content,
        element["document_key"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
        element["page_total"]?.takeIf { it != JsonNull }?.jsonPrimitive?.intOrNull,
        element.getValue("page_fingerprints").jsonObject.entries.associate { it.key to it.value.jsonPrimitive.content })

    private fun document(element: JsonObject): ParsedCashDocument {
        val rows = element.getValue("rows").jsonArray.map { raw ->
            val row = raw.jsonObject
            val fields = row["fields"]?.takeIf { it != JsonNull }?.jsonObject?.entries?.associate { it.key to toAny(it.value) }
            ParsedCashRow(row.getValue("position").jsonPrimitive.int, fields,
                row["payment_method"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull.orEmpty(),
                row["provider_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                row.getValue("issue_codes").jsonArray.map { it.jsonPrimitive.content },
                row["error_code"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                row.getValue("disposition").jsonPrimitive.content,
                row["source_account_identifier"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                row["document_position"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                (row["source_explanation"] as? JsonObject)?.entries?.associate { it.key to toAny(it.value) } ?: emptyMap())
        }
        return ParsedCashDocument(element.getValue("source_type").jsonPrimitive.content,
            element.getValue("table_key").jsonPrimitive.content, element.getValue("parser_version").jsonPrimitive.content,
            rows, element["account"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
            element["label"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
            coverage(element.getValue("coverage").jsonObject),
            element["declared"]?.takeIf { it != JsonNull }?.jsonPrimitive?.longOrNull,
            element.getValue("observed").jsonPrimitive.int)
    }

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

    @Test fun sourceRowsMatchPython() {
        for (raw in cases()) {
            val item = raw.jsonObject
            val input = item.getValue("input").jsonObject
            val files = input.getValue("files").jsonArray.map { value ->
                val f = value.jsonObject
                ParsedImportFile(f.getValue("file_ref").jsonPrimitive.content, f.getValue("digest").jsonPrimitive.content,
                    f.getValue("status").jsonPrimitive.content,
                    f["document"]?.takeIf { it != JsonNull }?.jsonObject?.let(::document),
                    f["source_scope_label"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    f.getValue("is_subset").jsonPrimitive.boolean,
                    f["last_modified_at_ms"]?.takeIf { it != JsonNull }?.jsonPrimitive?.longOrNull)
            }
            val resolved = input.getValue("resolved").jsonObject.entries.associate { (file, value) ->
                file to value.jsonObject.entries.associate { (position, fields) ->
                    position.toInt() to fields.jsonObject.entries.associate { it.key to toAny(it.value) }
                }
            }
            val known = input.getValue("known").jsonArray.map { value ->
                val entry = value.jsonObject
                SourceDocumentEvidence(entry.getValue("source_type").jsonPrimitive.content,
                    entry.getValue("parser_version").jsonPrimitive.content,
                    coverage(entry.getValue("coverage").jsonObject))
            }
            val name = item.getValue("name").jsonPrimitive.content
            val result = runCatching {
                if (item.getValue("direct").jsonPrimitive.boolean) {
                    val rows = linkedMapOf<String, SourceOccurrence>()
                    for (file in files) rows.putAll(CashSourceRows.sourceRows(file, resolved[file.fileRef]))
                    Result(rows, emptyList(), emptyMap())
                } else {
                    val batch = CashSourceRows.sourceBatchRows(files, resolved, known)
                    val evidence = batch.documentEvidence.map {
                        listOf(it.sourceType, it.parserVersion, it.coverage.documentKey, it.coverage.complete)
                    }
                    Result(batch.rows, evidence, batch.fileMetadata)
                }
            }
            if (!item.getValue("ok").jsonPrimitive.boolean) {
                assertEquals(item.getValue("error").jsonPrimitive.content, result.exceptionOrNull()?.message, name)
                continue
            }
            val value = result.getOrElse { throw AssertionError(name, it) }
            assertEquals(item.getValue("rows").jsonArray.size, value.rows.size, name)
            for ((index, rawRow) in item.getValue("rows").jsonArray.withIndex()) {
                val expected = rawRow.jsonObject
                val actual = value.rows.entries.elementAt(index)
                assertEquals(expected.getValue("key").jsonPrimitive.content, actual.key, name)
                val row = actual.value
                assertEquals(expected.getValue("position").jsonPrimitive.int, row.position, name)
                assertEquals(expected["fields"]?.takeIf { it != JsonNull }?.jsonObject ?: JsonNull, toJson(row.fields), name)
                assertEquals(expected.getValue("account_key").let { it.takeIf { v -> v != JsonNull } }?.jsonPrimitive?.contentOrNull,
                    row.sourceAccountKey, name)
                assertEquals(expected["provider_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull, row.providerId, name)
                assertEquals(expected["document_position"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    row.documentPosition, name)
                assertEquals(expected.getValue("occurrence_key").jsonPrimitive.content, row.occurrenceKey, name)
                assertEquals(expected.getValue("file_scope_key").jsonPrimitive.content, row.fileScopeKey, name)
                assertEquals(expected.getValue("observation_scope_key").jsonPrimitive.content, row.observationScopeKey, name)
                assertEquals(expected.getValue("binding_keys").jsonArray.map { it.jsonPrimitive.content }, row.bindingKeys, name)
                val cov = expected.getValue("coverage").jsonObject
                assertEquals(cov.getValue("complete").jsonPrimitive.boolean, row.coverage.complete, name)
                assertEquals(cov.getValue("evidence").jsonPrimitive.content, row.coverage.evidence, name)
                assertEquals(cov["document_key"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    row.coverage.documentKey, name)
            }
            if (!item.getValue("direct").jsonPrimitive.boolean) {
                val evidence = item.getValue("evidence").jsonArray.map {
                    val e = it.jsonObject
                    listOf(e.getValue("source_type").jsonPrimitive.content, e.getValue("parser_version").jsonPrimitive.content,
                        e["key"]?.takeIf { v -> v != JsonNull }?.jsonPrimitive?.contentOrNull,
                        e.getValue("complete").jsonPrimitive.boolean)
                }
                assertEquals(evidence, value.evidence, name)
                val metadata = item.getValue("file_metadata").jsonObject.entries.associate { (file, value) ->
                    file to (value.jsonArray[0].jsonPrimitive.int to value.jsonArray[1].takeIf { it != JsonNull }?.jsonPrimitive?.longOrNull)
                }
                assertEquals(metadata, value.metadata, name)
            }
        }
    }

    private data class Result(val rows: LinkedHashMap<String, SourceOccurrence>,
        val evidence: List<List<Any?>>, val metadata: Map<String, Pair<Int, Long?>>)
    @Test fun resolvedFieldsCopyWithoutMutatingSourceRow() {
        val payload = linkedMapOf<String, Any?>("a" to "b")
        val fields = linkedMapOf<String, Any?>("amount" to "-20.00", "source_payload" to payload)
        val row = ParsedCashRow(1, fields)
        val document = ParsedCashDocument("alipay", "csv:0", "platform-document-v1+native-v1", listOf(row),
            "owner", "owner", CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1",
                endInclusive = false), 1, 1)
        val file = ParsedImportFile("f1", "f".repeat(64), "parsed", document)
        val rows = CashSourceRows.sourceRows(file, mapOf(1 to mapOf("account_id" to 7,
            "source_payload" to linkedMapOf("x" to "y"))))
        assertEquals(listOf("amount", "source_payload"), fields.keys.toList())
        assertEquals(linkedMapOf<String, Any?>("a" to "b"), fields["source_payload"])
        val actual = rows.getValue("f1:r1").fields
        assertEquals(7, actual["account_id"])
        assertEquals(linkedMapOf<String, Any?>("x" to "y"), actual["source_payload"])
    }

}
