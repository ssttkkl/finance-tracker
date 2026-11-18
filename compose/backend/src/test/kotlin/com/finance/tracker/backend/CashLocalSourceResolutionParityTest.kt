package com.finance.tracker.backend

import com.finance.tracker.business.domain.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CashLocalSourceResolutionParityTest {
    private fun local(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonObject -> value.mapValues { local(it.value) }
        is JsonArray -> value.map(::local)
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.booleanOrNull != null -> value.boolean
            else -> value
        }
    }
    private fun text(value: JsonObject, key: String) = value[key]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
    private fun fields(value: JsonObject, local: Boolean): Map<String, Any?> = value.mapValues {
        if (local) local(it.value) else cashSourceValue(it.value)
    }
    private fun strings(value: JsonElement?) = value?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.toSet()
    private fun coverage(raw: JsonObject) = CashSourceCoverage(text(raw, "start"), text(raw, "end"),
        raw.getValue("complete").jsonPrimitive.boolean, text(raw, "evidence")!!,
        raw.getValue("filters").jsonObject.mapValues { it.value.jsonPrimitive.content }, text(raw, "strategy_version")!!,
        raw.getValue("start_inclusive").jsonPrimitive.boolean, raw.getValue("end_inclusive").jsonPrimitive.boolean,
        text(raw, "time_precision")!!, text(raw, "key"), raw["total"]?.takeIf { it != JsonNull }?.jsonPrimitive?.int,
        raw.getValue("pages").jsonObject.mapValues { it.value.jsonPrimitive.content })
    private fun row(raw: JsonObject, local: Boolean) = SourceOccurrence(text(raw, "source_type")!!, text(raw, "account"),
        text(raw, "digest")!!, text(raw, "parser_version")!!, text(raw, "table_key")!!,
        raw.getValue("position").jsonPrimitive.int, fields(raw.getValue("fields").jsonObject, local),
        coverage(raw.getValue("coverage").jsonObject), text(raw, "provider_id"), text(raw, "stable_evidence"), text(raw, "document_position"))
    private fun record(raw: JsonObject, local: Boolean) = SourceRecord(text(raw, "transaction_id")!!,
        text(raw, "source_type")!!, text(raw, "account"), fields(raw.getValue("fields").jsonObject, local),
        raw.getValue("coverages").jsonArray.map { coverage(it.jsonObject) }, strings(raw["binding_keys"]), strings(raw["protected_fields"]),
        text(raw, "stable_evidence"), text(raw, "provider_id"), text(raw, "provider_binding_key"), text(raw, "stable_evidence_binding_key"),
        deleted = raw.getValue("deleted").jsonPrimitive.boolean,
        fileOccurrences = raw.getValue("file_occurrences").jsonObject.mapValues { strings(it.value) })
    private fun fixture(name: String) = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream(name))
        .bufferedReader().use { it.readText() })

    @Test fun allOriginalBatchesAndMatchesUseEquivalentProductionAdapters() {
        val batches = fixture("/python-cash-source-batch.json").jsonArray
        assertEquals(49, batches.size)
        for ((index, raw) in batches.withIndex()) {
            val data = raw.jsonObject
            fun input(local: Boolean): SourceBatchRows {
                val rows = data.getValue("rows").jsonArray.map { it.jsonObject }
                val evidence = data.getValue("document_evidence").jsonArray.map { it.jsonObject }.map {
                    SourceDocumentEvidence(text(it, "source_type")!!, text(it, "parser_version")!!, coverage(it.getValue("coverage").jsonObject))
                }
                return SourceBatchRows(LinkedHashMap(rows.associate { text(it, "ref")!! to row(it, local) }), evidence,
                    evidence.map { it.coverage }, data.getValue("file_metadata").jsonObject.mapValues { (_, pair) ->
                        pair.jsonArray[0].jsonPrimitive.int to pair.jsonArray[1].takeIf { it != JsonNull }?.jsonPrimitive?.long
                    })
            }
            fun records(local: Boolean) = data.getValue("records").jsonArray.map { record(it.jsonObject, local) }
            assertEquals(CashSourceBatch.resolve(input(false), records(false)),
                resolveLocalCashSourceBatch(input(true), records(true)), "batch $index")
        }
        val matches = fixture("/python-cash-source-match.json").jsonObject.getValue("cases").jsonArray
        assertEquals(12, matches.size)
        for (raw in matches) {
            val data = raw.jsonObject
            val targets = data.getValue("targets").jsonObject.mapValues { strings(it.value) }
            fun run(local: Boolean): Result<List<SourceResolution>> = runCatching {
                val rows = data.getValue("rows").jsonArray.map { row(it.jsonObject, local) }
                val records = data.getValue("records").jsonArray.map { record(it.jsonObject, local) }
                if (local) resolveLocalCashSourceRows(rows, records, targets)
                else CashSourceResolver.resolveSourceRows(rows, records, targets)
            }
            val native = run(false); val actual = run(true)
            assertEquals(native.getOrNull(), actual.getOrNull(), text(data, "name"))
            assertEquals(native.exceptionOrNull()?.javaClass, actual.exceptionOrNull()?.javaClass)
            assertEquals(native.exceptionOrNull()?.message, actual.exceptionOrNull()?.message)
        }
    }

    @Test fun numericPayloadDigestsAndTemporalKeysRetainNativeDistinctions() {
        val payloads = listOf("0", "-0.0", "0.1", "1", "1.0", "1e0", "0.5", "9007199254740993", "1e30",
            "true", "null", "[1,1.0]", "{\"a\":1,\"b\":0.1}", "NaN")
        val base = Json.parseToJsonElement("""{"amount":"-20.00","currency":"CNY","occurred_at":"2026-08-15T02:00:00+00:00","record_type":"consumption","record_subtype":"not_applicable","source_payload":{}}""").jsonObject
        val coverage = CashSourceCoverage("2026-08-01T00:00:00Z", "2026-09-01T00:00:00Z", true, "e", emptyMap(), "v")
        for (new in payloads) for (old in payloads) {
            fun values(token: String, local: Boolean) = fields(JsonObject(base + ("source_payload" to
                Json.parseToJsonElement("{\"value\":$token}"))), local)
            fun run(local: Boolean): List<SourceResolution> {
                val rows = (1..2).map { SourceOccurrence("ccb_debit", "6222", "f".repeat(64), "p", "t", it,
                    values(if (it == 1) new else old, local), coverage) }
                val records = (1..2).map { SourceRecord("t$it", "ccb_debit", "6222", values(old, local), listOf(coverage)) }
                return if (local) resolveLocalCashSourceRows(rows, records) else CashSourceResolver.resolveSourceRows(rows, records)
            }
            assertEquals(run(false), run(true), "$new/$old")
        }
        for (time in listOf("2026-08-15T02:00:00Z", "2026-08-15T02:00:01.0000009Z", "0001-01-01T00:00:00Z", "9999-12-31T23:59:59.999999Z")) {
            val raw = JsonObject(base + ("occurred_at" to JsonPrimitive(time)))
            fun run(local: Boolean): SourceBatchPlan {
                val fields = fields(raw, local)
                val a = SourceOccurrence("ccb_debit", "6222", "f".repeat(64), "p", "t", 1, fields, providerId = "p1")
                val b = a.copy(fileDigest = "b".repeat(64))
                val input = SourceBatchRows(linkedMapOf("a:1" to a, "b:1" to b), emptyList(), emptyList(), fileMetadata = mapOf("a" to (0 to 1L), "b" to (1 to 2L)))
                return if (local) resolveLocalCashSourceBatch(input, emptyList()) else CashSourceBatch.resolve(input, emptyList())
            }
            assertEquals(run(false), run(true), time)
        }
    }
}
