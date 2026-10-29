package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.BigInteger
import kotlinx.serialization.json.*

internal fun pythonTruthy(value: JsonElement?): Boolean = when (value) {
    null, JsonNull -> false
    is JsonArray -> value.isNotEmpty()
    is JsonObject -> value.isNotEmpty()
    is JsonPrimitive -> if (value.isString) value.content.isNotEmpty()
        else value.booleanOrNull ?: (value.content.toDouble() != 0.0)
}

internal fun categoryRevisionMatches(value: JsonElement?, expected: Long?, actual: Long, literalRepresentation: String? = null): Boolean {
    if (value == null || value == JsonNull) return expected == null || expected == actual
    return pythonIntegerValue(value,literalRepresentation)==BigInteger.valueOf(actual)
}

internal fun pythonIntegerValue(value:JsonElement,literalRepresentation:String?=null):BigInteger {
    if (value !is JsonPrimitive) error("category revision is not integer-compatible")
    val integer = if (value.isString) {
        val raw = value.content
        val normalized = buildString {
            raw.trim { it.code in pythonWhitespace && it.code !in 0x1c..0x1f }.codePoints().forEach { code ->
                val zero = pythonIntegerDigitStarts.firstOrNull { code in it until it + 10 }
                if (zero == null) appendCodePoint(code) else append(('0'.code + code - zero).toChar())
            }
        }
        if (!normalized.matches(Regex("[+-]?[0-9]+(?:_[0-9]+)*")) || literalRepresentation!=null && literalRepresentation!=pythonStringRepr(raw)) {
            val representation = literalRepresentation ?: pythonStringRepr(value.content)
            val bounded=representation.substring(0,representation.offsetByCodePoints(0,minOf(200,representation.codePointCount(0,representation.length))))
            throw CategoryFailure("invalid literal for int() with base 10: $bounded")
        }
        val digits=normalized.count {it in '0'..'9'}
        if(digits>4300) throw CategoryFailure("Exceeds the limit (4300 digits) for integer string conversion: value has $digits digits; use sys.set_int_max_str_digits() to increase the limit")
        BigInteger(normalized.replace("_", ""))
    } else when (value.booleanOrNull) {
        true -> BigInteger.ONE
        false -> BigInteger.ZERO
        null -> {
            val text = value.content
            if (text.any { it in ".eE" } || text in setOf("NaN", "Infinity", "-Infinity")) {
                val number = text.toDouble()
                if (number.isNaN()) throw CategoryFailure("cannot convert float NaN to integer")
                if (!number.isFinite()) error("cannot convert float infinity to integer")
                BigDecimal(number).toBigInteger()
            } else BigInteger(text)
        }
    }
    return integer
}
