package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class IcbcPdfRowNormalizationGoldenTest {
    private fun cases(): JsonArray = Json.parseToJsonElement(requireNotNull(
        javaClass.getResourceAsStream("/python-icbc-pdf-row-normalization.json")).bufferedReader().use { it.readText() }).jsonArray

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

    @Test fun icbcPdfRowsMatchPython() {
        val cases = cases()
        assertEquals(6, cases.size)
        for (case in cases) {
            val item = case.jsonObject
            val name = item.getValue("name").jsonPrimitive.content
            val document = documentFor(item)
            val parsed = runCatching { CashStatementRowNormalization.normalizeIcbcDocument(document) }
                .getOrElse { throw AssertionError("$name ${it.message}", it) }
            val expected = item.getValue("document").jsonObject
            assertEquals(expected.getValue("source_type").jsonPrimitive.content, parsed.sourceType, name)
            assertEquals(expected.getValue("table_key").jsonPrimitive.content, parsed.tableKey, name)
            assertEquals(expected.getValue("parser_version").jsonPrimitive.content, parsed.parserVersion, name)
            assertEquals(expected["declared"]?.takeIf { it != JsonNull }?.jsonPrimitive?.longOrNull, parsed.declaredCount, name)
            assertEquals(expected.getValue("observed").jsonPrimitive.int, parsed.observedCount, name)
            assertEquals(expected["account"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                parsed.sourceAccountIdentifier, name)
            assertEquals(expected["label"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                parsed.sourceAccountLabel, name)
            val coverage = expected.getValue("coverage").jsonObject
            assertEquals(coverage["start"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull, parsed.coverage.start, name)
            assertEquals(coverage["end"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull, parsed.coverage.end, name)
            assertEquals(coverage.getValue("complete").jsonPrimitive.boolean, parsed.coverage.complete, name)
            assertEquals(coverage.getValue("evidence").jsonPrimitive.content, parsed.coverage.evidence, name)
            assertEquals(coverage.getValue("start_inclusive").jsonPrimitive.boolean, parsed.coverage.startInclusive, name)
            assertEquals(coverage.getValue("end_inclusive").jsonPrimitive.boolean, parsed.coverage.endInclusive, name)
            assertEquals(coverage["document_key"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                parsed.coverage.documentKey, name)
            assertEquals(coverage["page_total"]?.takeIf { it != JsonNull }?.jsonPrimitive?.intOrNull,
                parsed.coverage.pageTotal, name)
            assertEquals(coverage.getValue("page_fingerprints").jsonObject,
                JsonObject(parsed.coverage.pageFingerprints.mapValues { JsonPrimitive(it.value) }), name)
            assertEquals(expected.getValue("rows").jsonArray.size, parsed.rows.size, name)
            for ((index, raw) in expected.getValue("rows").jsonArray.withIndex()) {
                val row = raw.jsonObject
                val actual = parsed.rows[index]
                assertEquals(row.getValue("position").jsonPrimitive.int, actual.position, "$name row $index")
                assertEquals(row["fields"]?.takeIf { it != JsonNull }?.jsonObject ?: JsonNull,
                    toJson(actual.fields), "$name row $index fields")
                assertEquals(row.getValue("payment_method").jsonPrimitive.content, actual.paymentMethod, "$name row $index")
                assertEquals(row.getValue("issue_codes").jsonArray.map { it.jsonPrimitive.content }, actual.issueCodes,
                    "$name row $index issues")
                assertEquals(row["error_code"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    actual.errorCode, "$name row $index")
                assertEquals(row.getValue("disposition").jsonPrimitive.content, actual.disposition, "$name row $index")
                assertEquals(row["source_account_identifier"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    actual.sourceAccountIdentifier, "$name row $index")
                assertEquals(row["document_position"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    actual.documentPosition, "$name row $index")
                assertEquals(row.getValue("source_explanation").jsonObject, toJson(actual.sourceExplanation),
                    "$name row $index explanation")
            }
        }
    }

    private fun documentFor(item: JsonObject): CashStatementDocument {
        val input = item.getValue("input").jsonObject
        val rows = input.getValue("rows").jsonArray.map { raw ->
            val row = raw.jsonObject
            val payload = row["payload"]?.takeIf { it != JsonNull }?.jsonObject?.entries
                ?.associate { it.key to it.value.jsonPrimitive.content }
            RawCashRow(row.getValue("position").jsonPrimitive.int, payload,
                row["error_code"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                row["document_position"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull)
        }
        val coverage = input.getValue("coverage").jsonObject
        return CashStatementDocument(input.getValue("source_type").jsonPrimitive.content,
            input.getValue("table_key").jsonPrimitive.content, input.getValue("parser_version").jsonPrimitive.content,
            rows, input["owner"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
            input["label"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
            CashSourceCoverage(
                coverage["start"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                coverage["end"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                coverage.getValue("complete").jsonPrimitive.boolean,
                coverage.getValue("evidence").jsonPrimitive.content,
                coverage.getValue("filters").jsonObject.entries.associate { it.key to it.value.jsonPrimitive.content },
                coverage.getValue("strategy_version").jsonPrimitive.content,
                coverage.getValue("start_inclusive").jsonPrimitive.boolean,
                coverage.getValue("end_inclusive").jsonPrimitive.boolean,
                coverage.getValue("time_precision").jsonPrimitive.content,
                coverage["document_key"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                coverage["page_total"]?.takeIf { it != JsonNull }?.jsonPrimitive?.intOrNull,
                coverage.getValue("page_fingerprints").jsonObject.entries.associate { it.key to it.value.jsonPrimitive.content }),
            input["declared"]?.takeIf { it != JsonNull }?.jsonPrimitive?.longOrNull,
            input.getValue("observed").jsonPrimitive.int)
    }
}
