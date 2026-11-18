package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.time.Instant

/** Freeze a completed, exclusively computed plan without changing its values or raw JSON number kinds. */
fun freezeLocalCashImportPreviewPlan(value: Map<String, Any?>): JsonObject {
    fun count(value: Any?, nullable: Boolean = false): Any? {
        if ((value == null || value == JsonNull) && nullable) return null
        val exact = localCashIntegerText(value)
        val number = if (exact != null) exact.toIntOrNull() else (value as? Number)?.toDouble()?.let {
            if (!it.isFinite() || it < 0 || it > Int.MAX_VALUE || it % 1.0 != 0.0) null else it.toInt()
        }
        if (number == null || number < 0) throw InvalidCashValue()
        return SourceJsonInteger(number)
    }

    fun transform(value: Any?, path: List<String>): Any? = when (value) {
        null, JsonNull -> null
        is CashAmount -> localCashStoredDecimalText(value.toString())
        is CashDecimalLiteral -> localCashStoredDecimalText(value.text)
        is Instant -> formatPythonCashTimestamp(value)
        is JsonPrimitive -> value.also {
            if (!it.isString && it.booleanOrNull == null && !localCashInteger.matches(it.content) &&
                it.doubleOrNull?.isFinite() != true) throw InvalidCashValue()
        }
        is Number -> value.also { if (!it.toDouble().isFinite()) throw InvalidCashValue() }
        is List<*> -> value.map { transform(it, path + "[]") }
        is Map<*, *> -> value.entries.associate { (key, item) ->
            val name = key as? String ?: throw InvalidCashValue()
            val normalized = when {
                path == listOf("summary") -> count(item)
                path == listOf("file_results", "[]") && name == "recognized_row_count" -> count(item, true)
                path == listOf("file_results", "[]", "diagnostic") && name == "total_locations" -> count(item)
                path == listOf("file_results", "[]", "diagnostic", "locations", "[]") && name == "row_number" -> count(item, true)
                else -> item
            }
            name to transform(normalized, path + name)
        }
        else -> value
    }
    return encodeLocalCashValue(transform(value, emptyList())).jsonObject
}
