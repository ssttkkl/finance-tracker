package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
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

internal fun pythonFloatText(value: Double): String {
    if (value.isNaN()) return "nan"
    if (value == Double.POSITIVE_INFINITY) return "inf"
    if (value == Double.NEGATIVE_INFINITY) return "-inf"
    if (value == 0.0) return if (java.lang.Double.doubleToRawLongBits(value) < 0) "-0.0" else "0.0"
    val exact = BigDecimal(value)
    val number = (1..17).firstNotNullOf { precision ->
        val nearest = exact.round(MathContext(precision, RoundingMode.HALF_EVEN))
        val step = BigDecimal.ONE.scaleByPowerOfTen(exact.precision() - exact.scale() - precision)
        listOf(nearest, nearest.subtract(step), nearest.add(step)).map { it.stripTrailingZeros() }
            .filter { it.precision() <= precision && it.toDouble() == value }
            .minWithOrNull(compareBy<BigDecimal> { it.subtract(exact).abs() }.thenBy { it.unscaledValue().testBit(0) })
    }.stripTrailingZeros()
    val exponent = number.precision() - number.scale() - 1
    if (exponent in -4..15) return number.toPlainString().let { if ('.' in it) it else "$it.0" }
    val coefficient = number.movePointLeft(exponent).toPlainString()
    return coefficient + "e" + if (exponent >= 0) "+${exponent.toString().padStart(2, '0')}" else "-${(-exponent).toString().padStart(2, '0')}"
}

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
