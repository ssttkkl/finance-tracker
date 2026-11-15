package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.time.Instant

/** Complete local Phase A–D planning, including the original graph validation and dependency digests. */
internal object LocalCashRelationPlanning {
    private val core = LocalCashRelationArithmetic.core
    private val serialization = LocalCashRelationArithmetic.serialization
    private val values = CashProjectionValues<Long, Instant>({ it > 0 },
        { try { formatPythonCashTimestamp(it); true } catch (_: IllegalArgumentException) { false } }, ::pythonCashUpper)
    private val projections = CashProjectionRules(values, ::localCashInstantText)
    private val graph = CashRelationGraphRules(values, projections, { it as? Long }, String::toLong)
    val fingerprints = CashRelationPlanFingerprints(serialization, { value: LedgerArithmeticValue -> value.toPlainString() },
        { json(it).toString() })
    val validation = CashRelationValidationRules(core, serialization, values, graph,
        { it.toPlainString() }, ::parseRelationTimestampInstant, { it }, { it },
        { LocalCashRelationArithmetic.amount(it).toPlainString() },
        { if (it is LedgerArithmeticValue) it.toPlainString() else it.toString() }, { json(it).toString() })
    val rules = CashRelationPlanningRules(core, serialization, LocalCashPlatformRefund.hardKeys,
        LocalCashRelationPipeline.rules, validation, fingerprints, { LocalCashRelationPipeline.index(it) }, ::factFromRow,
        { it.abs() }, { a, b -> a.subtract(b) }, LocalCashRelationArithmetic::amount, { it.signum }, ::truthy)

    fun factFromRow(row: Map<String, Any?>) = cashRelationFactFromRow(row, { LocalCashRelationArithmetic.amount(it.toString()) },
        ::truthy, { raw ->
            try { parseLocalCashIntegerText(raw.toString()).toPlainString().toIntOrNull() }
            catch (_: NumberFormatException) { null }
        })

    /** Keep exact source integers, native binary64 floats and computed decimal/Instant text distinct. */
    fun json(value: Any?): JsonElement = when (value) {
        is String -> JsonPrimitive(value)
        is JsonPrimitive -> if (value.isString) JsonPrimitive(value.content) else encodeLocalCashValue(value)
        is LedgerArithmeticValue -> JsonPrimitive(value.toString())
        is CashDecimalLiteral -> JsonPrimitive(LocalCashRelationArithmetic.amount(value).toString())
        is Map<*, *> -> JsonObject(value.entries.sortedWith { a, b -> pythonStringOrder(a.key.toString(), b.key.toString()) }
            .associate { it.key.toString() to json(it.value) })
        is List<*> -> JsonArray(value.map(::json))
        else -> encodeLocalCashValue(value)
    }

    private fun truthy(value: Any?): Boolean = when (value) {
        null, JsonNull -> false
        is Boolean -> value
        is String -> value.isNotEmpty()
        is Number -> value.toDouble() != 0.0
        is SourceJsonInteger -> value.value != 0
        is JsonPrimitive -> if (value.isString) value.content.isNotEmpty()
            else value.booleanOrNull ?: (value.double != 0.0)
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }
}
