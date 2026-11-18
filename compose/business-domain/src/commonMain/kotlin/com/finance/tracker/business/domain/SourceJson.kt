package com.finance.tracker.business.domain

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/** A known generated integer, retaining its type through an open Any value boundary on JS. */
data class SourceJsonInteger(val value:Int)

/**
 * Python `json.dumps(ensure_ascii=False, sort_keys=True, separators=(",", ":"))` 的稳定 JSON 表示。
 *
 * 精确十进制与浮点文本的格式化口径由调用方注入：后端以 `BigDecimal`/`pythonFloatText` 注入，
 * 端侧以共享实现注入。这样在把逻辑下沉共享层的同时，保证既有 digest（持久身份键）口径不变。
 *
 * @param decimalText 返回精确十进制文本；非十进制返回 `null`。命中时以 JSON 字符串承载。
 * @param floatText 浮点文本格式化。
 */
@OptIn(ExperimentalSerializationApi::class)
fun sourceJson(
    value: Any?,
    decimalText: (Any) -> String?,
    floatText: (Double) -> String,
): JsonElement = when (value) {
    null -> JsonNull
    is SourceJsonInteger -> JsonPrimitive(value.value)
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    else -> {
        val decimal = decimalText(value)
        when {
            decimal != null -> JsonPrimitive(decimal)
            value is Double -> JsonUnquotedLiteral(floatText(value))
            value is Float -> JsonUnquotedLiteral(floatText(value.toDouble()))
            value is Number -> JsonUnquotedLiteral(value.toString())
            value is List<*> -> JsonArray(value.map { sourceJson(it, decimalText, floatText) })
            value is Map<*, *> -> JsonObject(
                value.entries
                    .sortedWith { first, second -> pythonStringOrder(first.key.toString(), second.key.toString()) }
                    .associate { it.key.toString() to sourceJson(it.value, decimalText, floatText) },
            )
            else -> JsonPrimitive(value.toString())
        }
    }
}
