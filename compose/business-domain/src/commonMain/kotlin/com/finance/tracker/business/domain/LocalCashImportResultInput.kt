package com.finance.tracker.business.domain

import kotlinx.serialization.json.*

/** Only outcomes omitted from the write set. Frozen text never retains the executable preview. */
class LocalCashImportResultInput private constructor(private val body: String) {
    fun encode(): String = body
    fun content(): JsonObject = Json.parseToJsonElement(body).jsonObject

    companion object {
        fun freeze(plan: JsonObject): LocalCashImportResultInput {
            val operations = plan.getValue("operations").jsonArray.reviewObjects()
            val positions = operations.withIndex().associate { it.value.text("operation_ref") to it.index }
            check(positions.size == operations.size) { "cash_import_result_input_invalid" }
            val parents = operations.withIndex().associate { it.value["preview_transaction_id"]?.jsonPrimitive?.content to it.index }
            val rows = plan.getValue("row_decisions").jsonArray.map { it.jsonObject }.filter { row ->
                val action = row.text("planned_action")
                val operation = row.optionalText("operation_ref")
                check(operation == null || operation in positions) { "cash_import_result_input_invalid" }
                action == "skip" || action == "duplicate_in_batch" || action == "duplicate" && operation == null
            }.map { row ->
                val position = row.optionalText("operation_ref")?.let(positions::getValue)
                JsonObject(row.filterKeys { it in rowFields } + ("operation_index" to (position?.let(::JsonPrimitive) ?: JsonNull)))
            }
            val files = plan.getValue("file_results").jsonArray.map { raw ->
                val file = raw.jsonObject
                JsonObject(file.filterKeys { it in fileFields } + ("code" to
                    (file["issues"]?.jsonArray?.firstOrNull()?.jsonObject?.get("code") ?: JsonNull)))
            }
            val failures = plan.getValue("relation_failures").jsonArray.map { raw ->
                val failure = raw.jsonObject
                val refs = failure.getValue("parent_ids").jsonArray.map { parent ->
                    val id = parent.jsonPrimitive.content
                    val index = parents[id]
                    if (index == null) buildJsonObject { put("transaction_id", id) }
                    else buildJsonObject { put("operation_index", index) }
                }
                JsonObject(failure.filterKeys { it in failureFields } + ("parents" to JsonArray(refs)))
            }
            return decode(buildJsonObject {
                put("version", 1); put("rows", JsonArray(rows)); put("files", JsonArray(files)); put("relation_failures", JsonArray(failures))
            }.toString())
        }

        fun fromWorker(body: String): LocalCashImportResultInput = LocalCashImportResultInput(body)

        fun decode(body: String): LocalCashImportResultInput {
            val root = Json.parseToJsonElement(body).jsonObject
            check(root.keys == setOf("version", "rows", "files", "relation_failures") && root["version"] == JsonPrimitive(1)) { "cash_import_result_input_invalid" }
            for (raw in root.getValue("rows").jsonArray) {
                val row = raw.jsonObject
                check(row.keys.all { it in rowFields }) { "cash_import_result_input_invalid" }
                row.text("row_ref")
                check(row.text("planned_action") in setOf("skip", "duplicate", "duplicate_in_batch")) { "cash_import_result_input_invalid" }
                check(row.text("planned_action") != "duplicate" || row.optionalText("operation_ref") == null) { "cash_import_result_input_invalid" }
                row.optionalText("operation_ref"); row.optionalText("code")
                val index = row["operation_index"] ?: JsonNull
                check(index == JsonNull || validIndex(index)) { "cash_import_result_input_invalid" }
                check((row.optionalText("operation_ref") == null) == (index == JsonNull)) { "cash_import_result_input_invalid" }
                row["transaction_id"]?.let { check(it == JsonNull || it is JsonPrimitive) { "cash_import_result_input_invalid" } }
            }
            check(root.getValue("rows").jsonArray.map { it.jsonObject.text("row_ref") }.let { it.size == it.toSet().size }) { "cash_import_result_input_invalid" }
            for (raw in root.getValue("files").jsonArray) {
                val file = raw.jsonObject
                check(file.keys.all { it in fileFields + "code" }) { "cash_import_result_input_invalid" }
                file.text("file_ref")
                check(file.getValue("display_name").let { it is JsonPrimitive && it.isString }) { "cash_import_result_input_invalid" }
                check(file.text("status") in setOf("ready", "partial", "failed", "password_required")) { "cash_import_result_input_invalid" }
                val count = file.getValue("recognized_row_count")
                check(count == JsonNull || count is JsonPrimitive && !count.isString && count.longOrNull?.let { it >= 0 } == true) { "cash_import_result_input_invalid" }
                file.optionalText("code")
            }
            for (raw in root.getValue("relation_failures").jsonArray) {
                val failure = raw.jsonObject
                check(failure.keys.all { it in failureFields }) { "cash_import_result_input_invalid" }
                failure.text("code"); failure.optionalText("suggestion_id")
                failure.getValue("parents").jsonArray.forEach { rawRef ->
                    val ref = rawRef.jsonObject
                    check(ref.keys == setOf("operation_index") && validIndex(ref.getValue("operation_index")) ||
                        ref.keys == setOf("transaction_id") && ref.optionalText("transaction_id") != null) { "cash_import_result_input_invalid" }
                }
            }
            return LocalCashImportResultInput(body)
        }

        private val rowFields = setOf("row_ref", "planned_action", "operation_ref", "transaction_id", "code", "operation_index")
        private val fileFields = setOf("file_ref", "display_name", "status", "recognized_row_count")
        private val failureFields = setOf("code", "suggestion_id", "parents")
    }
}

private fun JsonObject.text(key: String): String = checkNotNull(optionalText(key)) { "cash_import_result_input_invalid" }
private fun JsonObject.optionalText(key: String): String? = get(key)?.takeUnless { it == JsonNull }?.let {
    check(it is JsonPrimitive && it.isString && it.content.isNotBlank()) { "cash_import_result_input_invalid" }
    it.content
}

private fun validIndex(value: JsonElement): Boolean = value is JsonPrimitive && !value.isString &&
    value.intOrNull?.let { it >= 0 && it.toString() == value.content } == true
