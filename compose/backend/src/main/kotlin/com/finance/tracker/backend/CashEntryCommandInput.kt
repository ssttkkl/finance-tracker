package com.finance.tracker.backend

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Decode the public stable entry reference without interpreting the opaque stored identity. */
internal fun cashEntryCommandIds(value: JsonElement?, invalid: () -> Nothing): List<String> {
    val values = value as? JsonArray ?: invalid()
    if (values.isEmpty()) invalid()
    return values.map { item ->
        val text = (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
        if (text.codePointCount(0, text.length) > 96 || !text.startsWith("cash-entry:") || text.length == "cash-entry:".length) invalid()
        text.removePrefix("cash-entry:")
    }.distinct()
}

internal fun cashEntryCommandVersion(value: JsonElement?, invalid: () -> Nothing): Long =
    (value as? JsonPrimitive)?.takeIf { !it.isString && it.content.matches(Regex("-?[0-9]+")) }?.content?.toLongOrNull()?.takeIf { it >= 0 } ?: invalid()
