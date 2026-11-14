package com.finance.tracker.backend

import com.finance.tracker.business.contracts.CashWriteSetResultDto
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

internal fun receiptWriteSetResult(value: Any?, code: String = "mutation_summary_invalid"): JsonObject {
    fun invalid(): Nothing = throw IllegalArgumentException(code)
    val result = value as? JsonObject ?: invalid()
    var bytes = 0L
    fun add(count: Int) {
        bytes += count
        if (bytes > CashImportProtocol.MAX_PLAN_INPUT_BYTES) invalid()
    }
    fun string(text: String) {
        add(2)
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            when {
                char == '"' || char == '\\' || char in "\b\u000C\n\r\t" -> add(2)
                char.code < 32 -> add(6)
                char.code < 128 -> add(1)
                char.code < 2048 -> add(2)
                char.isHighSurrogate() -> {
                    if (index == text.length || !text[index++].isLowSurrogate()) invalid()
                    add(4)
                }
                char.isLowSurrogate() -> invalid()
                else -> add(3)
            }
        }
    }
    fun count(element: JsonElement, depth: Int) {
        if (depth > 6) invalid()
        when (element) {
            is JsonObject -> {
                add(2)
                element.entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) add(1)
                    string(key); add(1); count(item, depth + 1)
                }
            }
            is JsonArray -> {
                add(2)
                element.forEachIndexed { index, item ->
                    if (index > 0) add(1)
                    count(item, depth + 1)
                }
            }
            is JsonPrimitive -> {
                if (!element.isString) invalid()
                string(element.content)
            }
        }
    }
    count(result, 0)
    val decoded = try { Json.decodeFromJsonElement(CashWriteSetResultDto.serializer(), result) }
    catch (_: SerializationException) { invalid() }
    return Json.encodeToJsonElement(CashWriteSetResultDto.serializer(), decoded).jsonObject
}
