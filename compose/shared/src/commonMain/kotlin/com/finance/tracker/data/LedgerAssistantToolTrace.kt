package com.finance.tracker.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
internal enum class LedgerAssistantToolStatus { RUNNING, INTERRUPTED, COMPLETED, FAILED, WAITING, SUBMITTING, CANCELLED, SAVED }

@Serializable
internal data class LedgerAssistantToolTrace(
    val tool: String,
    val parameters: JsonObject,
    val status: LedgerAssistantToolStatus,
    val result: JsonObject? = null,
    val draftId: String? = null,
) {
    override fun toString(): String = "LedgerAssistantToolTrace(status=$status)"
}

private val ledgerQueryParameters = setOf(
    "date_from", "date_to", "account_id", "counterparty", "category_id", "currency",
    "amount_min", "amount_max", "economic_type", "time_zone_id",
)
private val ledgerDraftParameters = setOf(
    "account_id", "amount", "currency", "occurred_at", "record_type", "record_subtype",
    "counterparty", "note", "category_id",
)

internal fun ledgerAssistantTraceParameters(tool: String, fields: JsonObject?): JsonObject {
    val allowed = when (tool) {
        CREATE_ASSISTANT_TRANSACTION -> ledgerDraftParameters
        "list_transactions" -> ledgerQueryParameters + "limit"
        "summarize_transactions" -> ledgerQueryParameters
        "get_transaction" -> setOf("projection_id")
        "read_skill" -> setOf("skill_id", "resource")
        "run_script" -> setOf("code")
        else -> emptySet()
    }
    return JsonObject(fields.orEmpty().filter { (key, value) -> key in allowed && value is JsonPrimitive })
}

internal fun ledgerAssistantTraceResult(tool: String, output: JsonObject): JsonObject? {
    if (tool in ledgerAssistantSkillToolNames) {
        val allowed = when (tool) {
            "read_skill" -> setOf("error", "skill_id", "version", "resource")
            "run_script" -> setOf("error")
            else -> setOf("error", "name", "content_type", "bytes")
        }
        return buildJsonObject {
            output.filterKeys { it in allowed }.forEach { (name, value) -> put(name, value) }
            val files = output["files"] as? JsonArray
            if (files != null) put("files", JsonArray(files.filterIsInstance<JsonObject>().map {
                JsonObject(it.filterKeys { key -> key in setOf("name", "content_type", "bytes") })
            }))
        }
    }
    if ("error" in output) return null
    val key = when (tool) {
        "list_accounts" -> "accounts"
        "list_categories", "list_transactions" -> "items"
        "summarize_transactions" -> "months"
        "get_transaction" -> "records"
        else -> return null
    }
    val items = output[key] as? JsonArray ?: return null
    return buildJsonObject {
        put("count", items.size)
        if (tool == "summarize_transactions") {
            put("months", JsonArray(items.filterIsInstance<JsonObject>().map { month ->
                buildJsonObject {
                    (month["month"] as? JsonPrimitive)?.let { put("month", it) }
                    val currencies = (month["currencies"] as? JsonArray).orEmpty()
                    put("currencies", JsonArray(currencies.filterIsInstance<JsonObject>().map { currency ->
                        JsonObject(currency.filter { (name, value) ->
                            name in setOf("currency", "income", "expense") && value is JsonPrimitive
                        })
                    }))
                }
            }))
        }
    }
}
