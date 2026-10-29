package com.finance.tracker.business.domain

/** Original finite decimal representation, independent of its normalized arithmetic value. */
data class DecimalRepresentation(val negative: Boolean, val coefficient: String, val exponent: Long)

/** A finite NUMERIC(38,18) value for comparisons and rule checks; never a binary float. */
class LedgerDecimal private constructor(internal val digits: String, internal val scale: Int, val sign: Int,
    val representation: DecimalRepresentation) {
    enum class InputError { DECIMAL_COMPATIBLE, FINITE, SCALE, RANGE }
    internal fun magnitudeAt(scale: Int): String = digits + "0".repeat(scale - this.scale)

    companion object {
        private val pattern = Regex("^([+-]?)([0-9]*)(?:\\.([0-9]*))?(?:[eE]([+-]?[0-9]+))?$")

        fun parseRepresentationOrNull(value:String,onInvalid:(InputError)->Unit = {}):DecimalRepresentation? {
            fun invalid(reason:InputError):DecimalRepresentation? {onInvalid(reason);return null}
            val text = normalizeCharacters(value.trim { it.code in decimalWhitespace })
            val asciiLower=buildString {for(char in text) append(if(char in 'A'..'Z') char+32 else char)}
            if(Regex("[+-]?(?:s?nan[0-9]*|inf(?:inity)?)").matches(asciiLower)) return invalid(InputError.FINITE)
            val match = pattern.matchEntire(text) ?: return invalid(InputError.DECIMAL_COMPATIBLE)
            val whole = match.groupValues[2]
            val fraction = match.groupValues[3]
            if (whole.isEmpty() && fraction.isEmpty()) return invalid(InputError.DECIMAL_COMPATIBLE)
            val explicitExponent = match.groupValues[4].let { if (it.isEmpty()) 0L else it.toLongOrNull() } ?: return invalid(InputError.DECIMAL_COMPATIBLE)
            val exponent = explicitExponent - fraction.length
            if (explicitExponent < Long.MIN_VALUE + fraction.length || exponent > 999999999999999999L || exponent < -1999999999999999997L) return invalid(InputError.DECIMAL_COMPATIBLE)
            val coefficient = (whole + fraction).trimStart('0').ifEmpty { "0" }
            return DecimalRepresentation(match.groupValues[1] == "-", coefficient, exponent)
        }

        fun parseOrNull(value: String,onInvalid: (InputError)->Unit = {}): LedgerDecimal? {
            fun invalid(reason:InputError):LedgerDecimal? {onInvalid(reason);return null}
            val representation=parseRepresentationOrNull(value,onInvalid) ?: return null
            val coefficient=representation.coefficient
            var exponent=representation.exponent
            var length = coefficient.length
            // Match Decimal.as_tuple(), including its single zero digit for zero values.
            while (exponent < 0 && length > 0 && coefficient[length - 1] == '0') {
                length--
                exponent++
            }
            val scale = if (exponent < 0) -exponent else 0
            val zero = coefficient == "0"
            val integerDigits = if (zero) 0 else (length + exponent).coerceAtLeast(0)
            if (scale > 18) return invalid(InputError.SCALE)
            if (integerDigits > 20 || integerDigits + scale > 38) return invalid(InputError.RANGE)
            if (zero) return LedgerDecimal("0", 0, 0, representation)
            val digits = coefficient.take(length) + if (exponent > 0) "0".repeat(exponent.toInt()) else ""
            return LedgerDecimal(digits, scale.toInt(), if (representation.negative) -1 else 1, representation)
        }

        private fun normalizeCharacters(value: String): String = buildString(value.length) {
            var index = 0
            while (index < value.length) {
                val first = value[index]
                val width = if (first.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()) 2 else 1
                val code = if (width == 2) 0x10000 + ((first.code - 0xd800) shl 10) + value[index + 1].code - 0xdc00 else first.code
                val start = decimalDigitStarts.firstOrNull { code in it until it + 10 }
                when {
                    start != null -> append(('0'.code + code - start).toChar())
                    code == '_'.code -> Unit
                    else -> append(value, index, index + width)
                }
                index += width
            }
        }
    }
}
