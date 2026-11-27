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

    fun encode(value: Any?, path: List<String>): JsonElement = when (value) {
        is CashAmount -> encodeLocalCashValue(localCashStoredDecimalText(value.toString()))
        is CashDecimalLiteral -> encodeLocalCashValue(localCashStoredDecimalText(value.text))
        is Instant -> encodeLocalCashValue(formatPythonCashTimestamp(value))
        is JsonPrimitive -> {
            if (value != JsonNull && !value.isString && value.booleanOrNull == null && !localCashInteger.matches(value.content) &&
                value.doubleOrNull?.isFinite() != true) throw InvalidCashValue()
            encodeLocalCashValue(value)
        }
        is Number -> {
            if (!value.toDouble().isFinite()) throw InvalidCashValue()
            encodeLocalCashValue(value)
        }
        is List<*> -> {
            val itemPath = path + "[]"
            JsonArray(buildList(value.size) { for (item in value) add(encode(item, itemPath)) })
        }
        is Map<*, *> -> encodeLocalCashObject(value) { name, item ->
            val normalized = when {
                path == listOf("summary") -> count(item)
                path == listOf("file_results", "[]") && name == "recognized_row_count" -> count(item, true)
                path == listOf("file_results", "[]", "diagnostic") && name == "total_locations" -> count(item)
                path == listOf("file_results", "[]", "diagnostic", "locations", "[]") && name == "row_number" -> count(item, true)
                else -> item
            }
            encode(normalized, path + name)
        }
        else -> encodeLocalCashValue(value)
    }
    return encode(value, emptyList()).jsonObject
}
