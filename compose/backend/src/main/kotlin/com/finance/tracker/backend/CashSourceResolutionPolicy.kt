package com.finance.tracker.backend

import java.math.BigInteger
import kotlinx.serialization.json.*

internal val mutableCashFields = com.finance.tracker.business.domain.mutableCashFields
internal val financialCashFields = com.finance.tracker.business.domain.financialCashFields
internal val editableCashRecordFields = com.finance.tracker.business.domain.editableCashRecordFields

internal typealias CashSourceIssue = com.finance.tracker.business.domain.CashSourceIssue
internal typealias CashSourceResolution = com.finance.tracker.business.domain.CashSourceResolution
internal typealias PreparedCashSourceResolution = com.finance.tracker.business.domain.PreparedCashSourceResolution
internal typealias PreparedCashSourceResolutions = com.finance.tracker.business.domain.PreparedCashSourceResolutions

private val sourceIssueRules = com.finance.tracker.business.domain.CashSourceIssueResolutionRules(
    CashRecordPolicy, ::cashSourceValue, { values -> cashSourceDigest(*values.toTypedArray()) }, ::copyCashJsonContainers)

internal fun pendingCashSourcePatch(issue: CashSourceIssue): JsonObject = sourceIssueRules.pendingPatch(issue)

internal fun cashSourceValue(value: JsonElement?): Any? = when (value) {
    null, JsonNull -> null
    is JsonObject -> value.mapValues { cashSourceValue(it.value) }
    is JsonArray -> value.map(::cashSourceValue)
    is JsonPrimitive -> when {
        value.isString -> value.content
        value.booleanOrNull != null -> value.boolean
        value.content.matches(Regex("-?[0-9]+")) -> BigInteger(value.content)
        else -> value.content.toDouble()
    }
}

internal fun cashSourceDigest(vararg values: JsonElement?): String {
    fun encode(value: JsonElement?): String = when (value) {
        null, JsonNull -> "null"
        is JsonObject -> value.entries.sortedWith { a, b -> cashRelationStringOrder.compare(a.key, b.key) }
            .joinToString(",", "{", "}") { encode(JsonPrimitive(it.key)) + ":" + encode(it.value) }
        is JsonArray -> value.joinToString(",", "[", "]", transform = ::encode)
        is JsonPrimitive -> when {
            value.isString -> canonicalCashJson(value, validateUtf8 = false)
            value.booleanOrNull != null -> value.content
            value.content.matches(Regex("-?[0-9]+")) -> BigInteger(value.content).toString()
            else -> value.content.toDouble().let { number -> when {
                number.isNaN() -> "NaN"
                number == Double.POSITIVE_INFINITY -> "Infinity"
                number == Double.NEGATIVE_INFINITY -> "-Infinity"
                else -> pythonFloatText(number)
            } }
        }
    }
    val encoded = values.joinToString(",", "[", "]", transform = ::encode)
    strictUtf8(encoded)
    return tokenDigest(encoded)
}

internal fun validateCashSourceComponentIds(current: List<Map<String, Any?>>, requested: List<Map<String, Any?>>) =
    sourceIssueRules.validateComponentIds(current, requested)

internal fun prepareCashSourceResolutions(current: Map<String, Any?>, issues: List<CashSourceIssue>,
    choices: List<CashSourceResolution>, patch: Map<String, Any?>, protectedFields: Set<String>): PreparedCashSourceResolutions =
    try { sourceIssueRules.prepare(current, issues, choices, patch, protectedFields) }
    catch (_: com.finance.tracker.business.domain.CashSourceIssueChanged) { throw ReceiptConflict("issue_changed") }
