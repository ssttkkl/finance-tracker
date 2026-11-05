package com.finance.tracker.business.domain

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Apply saved public-field decisions to a copied, already-rendered local plan. No IO or fact mutation. */
fun applyLocalCashImportEntryChoices(result: JsonObject, choices: JsonElement, previous: JsonObject?,
    categoryIds: Set<String>): JsonObject {
    @Suppress("UNCHECKED_CAST")
    val copied = copyLocalCashValue(decodeLocalCashValue(result)) as MutableMap<String, Any?>
    val input = decodeLocalCashValue(choices) as? List<*> ?: throw CashImportPlanError("plan_input_invalid")
    @Suppress("UNCHECKED_CAST")
    val old = previous?.let { decodeLocalCashValue(it) as Map<String, Any?> }
    CashImportEntryChoiceRules(LocalCashRecordValues, ::localCashImportDigest)
        .apply(copied, input, old) { it in categoryIds }
    return encodeLocalCashValue(copied).jsonObject
}
