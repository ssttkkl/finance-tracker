package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashSourceCoverage
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalCashFundingOriginalTest {
    private fun text(value: JsonObject, key: String): String? =
        value[key]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
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
    private fun fields(value: JsonObject, key: String) =
        (value[key] as? JsonObject)?.mapValues { decode(it.value) }
    private fun file(value: JsonObject): ParsedImportFile {
        val doc = (value["document"] as? JsonObject)?.let { d ->
            ParsedCashDocument(text(d, "source_type")!!, text(d, "table_key")!!, text(d, "parser_version")!!,
                d.getValue("rows").jsonArray.map { raw -> val r = raw.jsonObject
                    ParsedCashRow(r.getValue("position").jsonPrimitive.int, fields(r, "fields"),
                        text(r, "payment_method").orEmpty(), text(r, "provider_id"),
                        r.getValue("issue_codes").jsonArray.map { it.jsonPrimitive.content }, text(r, "error_code"),
                        text(r, "disposition")!!, text(r, "source_account_identifier"), text(r, "document_position"),
                        fields(r, "source_explanation") ?: emptyMap()) },
                text(d, "source_account_identifier"), text(d, "source_account_label"),
                CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1"),
                d["declared_count"]?.jsonPrimitive?.intOrNull, d.getValue("observed_count").jsonPrimitive.int,
                text(d, "source_nickname"))
        }
        return ParsedImportFile(text(value, "file_ref")!!, text(value, "digest")!!, text(value, "status")!!, doc,
            isSubset = value["is_subset"]?.jsonPrimitive?.boolean ?: false,
            lastModifiedAtMs = value["last_modified_at_ms"]?.takeIf { it != JsonNull }?.jsonPrimitive?.longOrNull,
            clientFileRef = text(value, "client_file_ref") ?: text(value, "file_ref")!!,
            filename = text(value, "filename") ?: "statement", size = value["size"]?.jsonPrimitive?.longOrNull ?: 0,
            sourceType = text(value, "source_type"), errorCode = text(value, "error_code"), diagnostic = fields(value, "diagnostic"))
    }
    @Test fun productionLocalFundingScanMatchesAllOriginalScanCases() {
        val cases = Json.parseToJsonElement(LocalFundingScanOriginalVectors.json).jsonArray
        assertEquals(64, cases.size)
        for (raw in cases) {
            val c = raw.jsonObject
            val result = runCatching { scanLocalCashFunding(c.getValue("files").jsonArray.map { file(it.jsonObject) })
                .json { sourceJson(it, { value -> (value as? CashAmount)?.toString() }, ::pythonFloatText) } }
            val context = "original case ${c.getValue("original_index")}"
            if (c.getValue("ok").jsonPrimitive.boolean) assertEquals(c.getValue("result"), result.getOrThrow(), context)
            else assertEquals(text(c, "error"), result.exceptionOrNull()?.message, context)
        }
    }
}
