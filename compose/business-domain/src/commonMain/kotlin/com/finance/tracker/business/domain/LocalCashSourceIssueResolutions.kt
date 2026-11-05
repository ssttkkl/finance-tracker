package com.finance.tracker.business.domain

import kotlinx.serialization.json.*

/** Read a current issue from the same trusted snapshot as its parent; this does not authorize a write. */
fun pendingLocalCashSourceIssuePatch(issue: CashSourceIssue): JsonObject = localSourceIssueRules.pendingPatch(issue)

fun prepareLocalCashSourceIssueResolutions(current: JsonObject, issues: List<CashSourceIssue>,
    choices: List<CashSourceResolution>, patch: JsonObject, protectedFields: Set<String>): PreparedCashSourceResolutions {
    @Suppress("UNCHECKED_CAST") val values = decodeLocalCashValue(current) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") val edits = decodeLocalCashValue(patch) as Map<String, Any?>
    return localSourceIssueRules.prepare(values, issues, choices, edits, protectedFields)
}

private val localSourceIssueRules = CashSourceIssueResolutionRules(LocalCashRecordValues, ::decodeLocalCashValue,
    { values -> localCashSourceDigest(values.map(::decodeLocalCashValue)) }, ::copySourceIssueJson)

private fun copySourceIssueJson(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapValues { copySourceIssueJson(it.value) })
    is JsonArray -> JsonArray(value.map(::copySourceIssueJson))
    else -> value
}
