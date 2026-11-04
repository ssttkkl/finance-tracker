package com.finance.tracker.backend

import java.math.BigInteger
import kotlinx.serialization.json.*

/** Compatibility for the existing non-credential name and role fields only. */
internal fun pythonString(value: JsonElement): String = when (value) {
    JsonNull -> "None"
    is JsonPrimitive -> when {
        value.isString -> value.content
        value.content == "true" -> "True"
        value.content == "false" -> "False"
        value.content.matches(Regex("-?[0-9]+")) -> BigInteger(value.content).toString()
        else -> pythonFloatText(value.content.toDouble())
    }
    is JsonArray -> value.joinToString(", ", "[", "]", transform = ::pythonRepr)
    is JsonObject -> value.entries.joinToString(", ", "{", "}") { pythonRepr(JsonPrimitive(it.key)) + ": " + pythonRepr(it.value) }
}

internal fun pythonFloatText(value: Double): String =
    com.finance.tracker.business.domain.pythonFloatText(value)

private fun pythonRepr(value: JsonElement): String {
    if (value !is JsonPrimitive || !value.isString) return pythonString(value)
    return pythonStringRepr(value.content)
}

internal fun pythonStringRepr(text: String, separateSurrogates: Set<Int> = emptySet()): String {
    val quote = if ('\'' in text && '"' !in text) '"' else '\''
    return buildString {
        append(quote)
        var index = 0
        while (index < text.length) {
            val code = if(index in separateSurrogates || index+1 in separateSurrogates) text[index].code else text.codePointAt(index)
            index += Character.charCount(code)
            when (code) {
                quote.code -> { append('\\'); append(quote) }
                '\\'.code -> append("\\\\")
                9 -> append("\\t")
                10 -> append("\\n")
                13 -> append("\\r")
                else -> {
                    var low = 0
                    var high = pythonPrintableRanges.size / 2 - 1
                    var printable = false
                    while (low <= high) {
                        val middle = (low + high) / 2
                        when {
                            code < pythonPrintableRanges[middle * 2] -> high = middle - 1
                            code > pythonPrintableRanges[middle * 2 + 1] -> low = middle + 1
                            else -> { printable = true; break }
                        }
                    }
                    if (printable) appendCodePoint(code)
                    else append(when { code <= 255 -> "\\x" + code.toString(16).padStart(2, '0')
                        code <= 65535 -> "\\u" + code.toString(16).padStart(4, '0')
                        else -> "\\U" + code.toString(16).padStart(8, '0') })
                }
            }
        }
        append(quote)
    }
}
