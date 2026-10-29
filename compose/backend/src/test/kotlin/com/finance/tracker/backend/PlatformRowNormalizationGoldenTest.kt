package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class PlatformRowNormalizationGoldenTest {
    private fun cases(): JsonArray = Json.parseToJsonElement(requireNotNull(
        javaClass.getResourceAsStream("/python-platform-row-normalization.json")).bufferedReader().use { it.readText() }).jsonArray

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

    private fun JsonObject.toAny(): Any? = entries.associate { it.key to it.value.toAny() }
    private fun JsonElement.toAny(): Any? = when (this) {
        JsonNull -> null
        is JsonPrimitive -> when {
            isString -> content
            booleanOrNull != null -> boolean
            intOrNull != null -> int
            longOrNull != null -> long
            else -> content
        }
        is JsonArray -> map { it.toAny() }
        is JsonObject -> toAny()
    }

    @Test fun platformRowsMatchPython() {
        val cases = cases()
        assertEquals(6, cases.size)
        for (case in cases) {
            val item = case.jsonObject
            val name = item.getValue("name").jsonPrimitive.content
            val document = documentFor(item, name)
            val result = runCatching { CashStatementRowNormalization.normalizePlatformDocument(document) }
            if (!item.getValue("ok").jsonPrimitive.boolean) {
                val failure = result.exceptionOrNull()
                assertIs<StatementDocumentFailure>(failure, name)
                assertEquals(item.getValue("error").jsonPrimitive.content, failure.code, name)
                continue
            }
            val parsed = result.getOrElse { throw AssertionError("$name ${it.message}", it) }
            val expected = item.getValue("document").jsonObject
            assertEquals(expected.getValue("source_type").jsonPrimitive.content, parsed.sourceType, name)
            assertEquals(expected.getValue("table_key").jsonPrimitive.content, parsed.tableKey, name)
            assertEquals(expected.getValue("parser_version").jsonPrimitive.content, parsed.parserVersion, name)
            assertEquals(expected["declared"]?.takeIf { it != JsonNull }?.jsonPrimitive?.longOrNull, parsed.declaredCount, name)
            assertEquals(expected.getValue("observed").jsonPrimitive.int, parsed.observedCount, name)
            val coverage = expected.getValue("coverage").jsonObject
            assertEquals(coverage.getValue("complete").jsonPrimitive.boolean, parsed.coverage.complete, name)
            assertEquals(coverage.getValue("evidence").jsonPrimitive.content, parsed.coverage.evidence, name)
            assertEquals(coverage["start"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull, parsed.coverage.start, name)
            assertEquals(coverage["end"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull, parsed.coverage.end, name)
            assertEquals(coverage.getValue("start_inclusive").jsonPrimitive.boolean, parsed.coverage.startInclusive, name)
            assertEquals(coverage.getValue("end_inclusive").jsonPrimitive.boolean, parsed.coverage.endInclusive, name)
            assertEquals(expected.getValue("rows").jsonArray.size, parsed.rows.size, name)
            for ((index, raw) in expected.getValue("rows").jsonArray.withIndex()) {
                val row = raw.jsonObject
                val actual = parsed.rows[index]
                assertEquals(row.getValue("position").jsonPrimitive.int, actual.position, "$name row $index")
                assertEquals(row["fields"]?.takeIf { it != JsonNull }?.jsonObject ?: JsonNull,
                    toJson(actual.fields), "$name row $index fields")
                assertEquals(row.getValue("payment_method").jsonPrimitive.content, actual.paymentMethod, "$name row $index")
                assertEquals(row["provider_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    actual.providerId, "$name row $index")
                assertEquals(row.getValue("issue_codes").jsonArray.map { it.jsonPrimitive.content }, actual.issueCodes,
                    "$name row $index")
                assertEquals(row["error_code"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    actual.errorCode, "$name row $index")
                assertEquals(row.getValue("disposition").jsonPrimitive.content, actual.disposition, "$name row $index")
                assertEquals(row.getValue("source_explanation").jsonObject, toJson(actual.sourceExplanation),
                    "$name row $index explanation")
            }
        }
    }

    private fun documentFor(item: JsonObject, name: String): CashStatementDocument {
        val input = item["input"]?.takeIf { it != JsonNull }?.jsonObject
        val source = input?.getValue("source_type")?.jsonPrimitive?.content
            ?: if (name == "unsupported") "ccb_debit" else error("missing source")
        val rows = input?.getValue("rows")?.jsonArray?.map { raw ->
            val row = raw.jsonObject
            val payload = row["payload"]?.takeIf { it != JsonNull }?.jsonObject?.entries
                ?.associate { it.key to it.value.jsonPrimitive.content }
            RawCashRow(row.getValue("position").jsonPrimitive.int, payload,
                row["error_code"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull)
        }.orEmpty()
        val coverage = input?.getValue("coverage")?.jsonObject
        return CashStatementDocument(source, "csv:0", "platform-document-v1", rows, null, null,
            CashSourceCoverage(
                coverage?.get("start")?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                coverage?.get("end")?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                coverage?.getValue("complete")?.jsonPrimitive?.boolean ?: true,
                coverage?.getValue("evidence")?.jsonPrimitive?.content.orEmpty(),
                coverage?.getValue("filters")?.jsonObject?.entries?.associate { it.key to it.value.jsonPrimitive.content }
                    ?: mapOf("export_type" to "all"),
                coverage?.getValue("strategy_version")?.jsonPrimitive?.content ?: "platform-coverage-v1",
                coverage?.getValue("start_inclusive")?.jsonPrimitive?.boolean ?: true,
                coverage?.getValue("end_inclusive")?.jsonPrimitive?.boolean ?: false,
                coverage?.getValue("time_precision")?.jsonPrimitive?.content ?: "second"),
            input?.get("declared")?.takeIf { it != JsonNull }?.jsonPrimitive?.longOrNull,
            input?.getValue("observed")?.jsonPrimitive?.int ?: 0)
    }

}
