package com.finance.tracker.business.domain

import kotlinx.serialization.json.*

/** Preserve the existing Python text representation for JSON-origin compatibility values. */
fun pythonJsonText(value: JsonElement): String = when (value) {
    JsonNull -> "None"
    is JsonPrimitive -> when {
        value.isString -> value.content
        value.content == "true" -> "True"
        value.content == "false" -> "False"
        value.content.matches(Regex("-?[0-9]+")) -> canonicalLocalCashInteger(value.content)
        else -> pythonFloatText(parsePythonJsonBinary64(value.content))
    }
    is JsonArray -> value.joinToString(", ", "[", "]", transform = ::pythonRepr)
    is JsonObject -> value.entries.joinToString(", ", "{", "}") { pythonRepr(JsonPrimitive(it.key)) + ": " + pythonRepr(it.value) }
}

private fun pythonRepr(value: JsonElement): String {
    if (value !is JsonPrimitive || !value.isString) return pythonJsonText(value)
    return pythonJsonStringRepr(value.content)
}

fun pythonJsonStringRepr(text: String, separateSurrogates: Set<Int> = emptySet()): String {
    val quote = if ('\'' in text && '"' !in text) '"' else '\''
    return buildString {
        append(quote)
        var index = 0
        while (index < text.length) {
            val code = if(index in separateSurrogates || index+1 in separateSurrogates) text[index].code else {
                val first = text[index]
                if (first.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate())
                    0x10000 + ((first.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00)
                else first.code
            }
            index += if (code > 0xffff) 2 else 1
            when (code) {
                quote.code -> { append('\\'); append(quote) }
                '\\'.code -> append("\\\\")
                9 -> append("\\t")
                10 -> append("\\n")
                13 -> append("\\r")
                else -> {
                    if (isPythonJsonPrintable(code)) appendPythonCodePoint(code)
                    else append(when { code <= 255 -> "\\x" + code.toString(16).padStart(2, '0')
                        code <= 65535 -> "\\u" + code.toString(16).padStart(4, '0')
                        else -> "\\U" + code.toString(16).padStart(8, '0') })
                }
            }
        }
        append(quote)
    }
}
