package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashSourceCoverage
import kotlinx.serialization.json.*

private val storedCoverageKeys = setOf("start", "end", "complete", "evidence", "filters", "start_inclusive", "end_inclusive",
    "time_precision", "strategy_version", "document_key", "page_total", "page_fingerprints")

/** 与 Python `SourceCoverage(**coverage)` 相同：缺省字段取默认值，未知键或损坏结构停止读取。 */
fun decodeStoredCashSourceCoverage(value: JsonElement?): CashSourceCoverage {
    val item = value as? JsonObject ?: return CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1")
    if (item.keys.any { it !in storedCoverageKeys }) throw IllegalArgumentException("invalid source coverage")
    fun text(key: String): String? = (item[key]?.takeIf { it != JsonNull } as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun flag(key: String, default: Boolean): Boolean = item[key]?.takeIf { it != JsonNull }?.let {
        (it as? JsonPrimitive)?.booleanOrNull ?: throw IllegalArgumentException("invalid source coverage")
    } ?: default
    fun filters(): Map<String, String> {
        val raw = item["filters"]?.takeIf { it != JsonNull } ?: return emptyMap()
        val objectValue = raw as? JsonObject ?: throw IllegalArgumentException("invalid source coverage")
        return objectValue.mapValues { (_, item) ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("invalid source coverage")
        }
    }
    fun fingerprints(): Map<String, String> {
        val raw = item["page_fingerprints"]?.takeIf { it != JsonNull } ?: return emptyMap()
        val objectValue = raw as? JsonObject ?: throw IllegalArgumentException("invalid source coverage")
        return objectValue.mapValues { (_, item) ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("invalid source coverage")
        }
    }
    val pageTotal = item["page_total"]?.takeIf { it != JsonNull }?.let {
        (it as? JsonPrimitive)?.takeIf { value -> !value.isString && value.booleanOrNull == null }?.content?.toIntOrNull()
            ?: throw IllegalArgumentException("invalid source coverage")
    }
    return try {
        CashSourceCoverage(text("start"), text("end"), flag("complete", false), text("evidence") ?: "",
            filters(), text("strategy_version") ?: "source-coverage-v1", flag("start_inclusive", true),
            flag("end_inclusive", false), text("time_precision") ?: "second", text("document_key"), pageTotal, fingerprints())
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("invalid source coverage")
    }
}
