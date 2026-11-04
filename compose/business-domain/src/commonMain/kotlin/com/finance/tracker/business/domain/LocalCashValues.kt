package com.finance.tracker.business.domain

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import kotlin.time.Instant

internal val localCashInteger = Regex("-?[0-9]+")

/** Keep JSON number tokens typed until their field-specific validation on every target. */
internal fun decodeLocalCashValue(value: JsonElement?): Any? = when (value) {
    null, JsonNull -> null
    is JsonObject -> value.mapValues { decodeLocalCashValue(it.value) }
    is JsonArray -> value.map(::decodeLocalCashValue)
    is JsonPrimitive -> when {
        value.isString -> value.content
        value.booleanOrNull != null -> value.boolean
        localCashInteger.matches(value.content) -> Json.parseToJsonElement(canonicalLocalCashInteger(value.content))
        else -> Json.parseToJsonElement(value.toString())
    }
}

internal fun canonicalLocalCashInteger(value: String): String {
    val digits = value.removePrefix("-").trimStart('0').ifEmpty { "0" }
    return (if (value.startsWith('-') && digits != "0") "-" else "") + digits
}

internal fun localCashIntegerText(value: Any?): String? = when (value) {
    is SourceJsonInteger -> value.value.toString()
    is Long -> value.toString()
    is JsonPrimitive -> value.content.takeIf { !value.isString && localCashInteger.matches(it) }?.let(::canonicalLocalCashInteger)
    else -> null
}

/** JSON-origin trees have string keys, no aliases/cycles and immutable scalar leaves. */
internal fun copyLocalCashValue(value: Any?): Any? = when (value) {
    null, is String, is Boolean, is Number, is SourceJsonInteger, is JsonPrimitive, is CashAmount, is Instant -> value
    is Map<*, *> -> value.entries.associateTo(linkedMapOf()) { (key, item) -> key to copyLocalCashValue(item) }
    is List<*> -> value.mapTo(arrayListOf()) { copyLocalCashValue(it) }
    else -> throw InvalidCashValue()
}

/**
 * Encode normalized local record values and decoded JSON tokens without losing integer precision.
 * Generated integers use SourceJsonInteger or Long; unmarked Number values are binary floats.
 */
@OptIn(ExperimentalSerializationApi::class)
fun encodeLocalCashValue(value: Any?): JsonElement = when (value) {
    null, JsonNull -> JsonNull
    is String -> JsonPrimitive(value.also { it.encodeToByteArray(throwOnInvalidSequence = true) })
    is Boolean -> JsonPrimitive(value)
    is SourceJsonInteger -> JsonPrimitive(value.value)
    is Long -> JsonPrimitive(value)
    is JsonPrimitive -> when {
        value.isString -> encodeLocalCashValue(value.content)
        value.booleanOrNull != null -> JsonPrimitive(value.boolean)
        localCashInteger.matches(value.content) -> JsonUnquotedLiteral(canonicalLocalCashInteger(value.content))
        else -> JsonUnquotedLiteral(localCashFloatText(value.double))
    }
    is CashAmount -> JsonPrimitive(value.toString())
    is Instant -> JsonPrimitive(localCashInstantText(value))
    is Number -> JsonUnquotedLiteral(localCashFloatText(value.toDouble()))
    is List<*> -> JsonArray(value.map(::encodeLocalCashValue))
    is Map<*, *> -> JsonObject(value.entries.sortedWith { a, b -> pythonStringOrder(a.key.toString(), b.key.toString()) }
        .associate { (key, item) ->
            val text = key as? String ?: throw InvalidCashValue()
            text.encodeToByteArray(throwOnInvalidSequence = true)
            text to encodeLocalCashValue(item)
        })
    else -> throw InvalidCashValue()
}

private fun localCashFloatText(value: Double): String = when {
    value.isNaN() -> "NaN"
    value == Double.POSITIVE_INFINITY -> "Infinity"
    value == Double.NEGATIVE_INFINITY -> "-Infinity"
    else -> pythonFloatText(value)
}

/** Native Instant text uses groups of three fraction digits, including full nanosecond metadata. */
internal fun localCashInstantText(value: Instant): String {
    val whole = formatPythonCashTimestamp(value).removeSuffix("+00:00").substringBefore('.')
    val nanos = value.nanosecondsOfSecond
    val fraction = when {
        nanos == 0 -> ""
        nanos % 1_000_000 == 0 -> "." + nanos.toString().padStart(9, '0').take(3)
        nanos % 1_000 == 0 -> "." + nanos.toString().padStart(9, '0').take(6)
        else -> "." + nanos.toString().padStart(9, '0')
    }
    return whole + fraction + "Z"
}
