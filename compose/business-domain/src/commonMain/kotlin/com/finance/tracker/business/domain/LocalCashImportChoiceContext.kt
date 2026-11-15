package com.finance.tracker.business.domain

import kotlinx.serialization.json.*

/** JSON recovery boundaries return independent trees and keep generation integers exact. */
fun validateLocalCashImportChoiceContext(raw: JsonElement): JsonObject =
    encodeLocalCashValue(localChoiceContext.validated(contextValue(raw))).jsonObject

fun localCashImportChoiceContextFromPlan(plan: JsonObject): JsonObject =
    encodeLocalCashValue(localCashImportChoiceContextFromPlan(contextMap(plan))).jsonObject

/** Computed plans are read directly; only stable references are copied, without serializing calculated values. */
fun localCashImportChoiceContextFromPlan(plan: Map<String, Any?>): Map<String, Any?> = localChoiceContext.fromPlan(plan)

fun retainLocalCashImportChoices(contextData: JsonObject, plan: JsonObject?): JsonObject {
    val context = if (plan != null) localCashImportChoiceContextFromPlan(plan)
        else contextData[CASH_IMPORT_CHOICE_CONTEXT_KEY]?.let(::validateLocalCashImportChoiceContext)
    val retained = contextData.filterKeys { it != CASH_IMPORT_CHOICE_CONTEXT_KEY }
        .mapValuesTo(linkedMapOf()) { copyContextJson(it.value) }
    if (context != null) retained[CASH_IMPORT_CHOICE_CONTEXT_KEY] = context
    return JsonObject(retained)
}

fun previousLocalCashImportChoices(contextData: JsonObject): JsonObject? =
    contextData[CASH_IMPORT_CHOICE_CONTEXT_KEY]?.let(::validateLocalCashImportChoiceContext)

fun withoutLocalCashImportChoices(contextData: JsonObject): JsonObject =
    JsonObject(contextData.filterKeys { it != CASH_IMPORT_CHOICE_CONTEXT_KEY }.mapValues { copyContextJson(it.value) })

@Suppress("UNCHECKED_CAST")
private fun contextMap(value: JsonObject): Map<String, Any?> = contextValue(value) as Map<String, Any?>

// Decode only values the shared rules read. Shape/size rejection and ignored bodies stay lazy.
private fun contextValue(raw: JsonElement): Any? = when (raw) {
    is JsonObject -> ContextObject(raw)
    is JsonArray -> ContextArray(raw)
    else -> decodeLocalCashValue(raw)
}

private class ContextObject(private val raw: JsonObject) : AbstractMap<String, Any?>() {
    override val size get() = raw.size
    override val keys get() = raw.keys
    override fun containsKey(key: String): Boolean = raw.containsKey(key)
    override fun get(key: String): Any? = raw[key]?.let(::contextValue)
    override val entries get() = raw.mapValues { contextValue(it.value) }.entries
}

private class ContextArray(private val raw: JsonArray) : AbstractList<Any?>() {
    override val size get() = raw.size
    override fun get(index: Int): Any? = contextValue(raw[index])
}

// Unrelated context values are opaque JSON. Preserve number tokens instead of interpreting them.
private fun copyContextJson(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapValues { copyContextJson(it.value) })
    is JsonArray -> JsonArray(value.map(::copyContextJson))
    else -> value
}

private val localChoiceContext = CashImportChoiceContextRules(
    { localCashIntegerText(it)?.toLongOrNull() }, ::contextTruthy, ::copyLocalCashValue,
    { LocalCashRecordValues.prepare(mapOf("state" to "draft"), it) }, { SourceJsonInteger(it) },
)

private fun contextTruthy(value: Any?): Boolean = when (value) {
    null -> false
    is Boolean -> value
    is String -> value.isNotEmpty()
    is JsonPrimitive -> localCashIntegerText(value)?.let { it != "0" } ?: (value.doubleOrNull?.let { it != 0.0 } ?: true)
    is Number -> value.toDouble() != 0.0
    is Collection<*> -> value.isNotEmpty()
    is Map<*, *> -> value.isNotEmpty()
    else -> true
}
