package com.finance.tracker.backend

import com.finance.tracker.business.domain.LedgerDecimal
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

internal object BrokerText {
    val currencies = setOf("USD", "HKD", "CNY", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "NZD", "SGD")
    private val context = MathContext(28, RoundingMode.HALF_EVEN)
    fun decimal(raw: String): String {
        var nonfinite = false
        val rep = LedgerDecimal.parseRepresentationOrNull(raw) { if (it == LedgerDecimal.InputError.FINITE) nonfinite = true }
        if (rep == null) {
            if (!nonfinite) throw ArithmeticException("invalid decimal operation")
            return FinanceDecimal.parse(raw).text
        }
        val number = BigDecimal(rep.coefficient + "E" + rep.exponent)
        return (if (rep.negative) "-" else "") + number.toString()
    }
    fun abs(raw: String): String {
        val d = decimal(raw); val finite = d.toBigDecimalOrNull()
        if (finite != null) {
            val magnitude = finite.abs()
            val exponent = -magnitude.scale().toLong()
            if (magnitude.signum() == 0) return BigDecimal.ZERO.setScale(-exponent.coerceIn(-1000026L, 999999L).toInt()).toString()
            val adjusted = exponent + magnitude.precision() - 1
            if (adjusted > 999999L) throw ArithmeticException("decimal overflow")
            val targetExponent = maxOf(exponent, adjusted - 27, -1000026L)
            val rounded = magnitude.setScale(-targetExponent.toInt(), RoundingMode.HALF_EVEN)
            if (rounded.signum() != 0 && rounded.precision().toLong() - rounded.scale() - 1 > 999999L) throw ArithmeticException("decimal overflow")
            return rounded.round(context).toString()
        }
        if (d.removePrefix("-").startsWith("sNaN")) throw ArithmeticException("invalid decimal operation")
        if (d.removePrefix("-").startsWith("NaN")) return d
        return d.removePrefix("-")
    }
    fun fmt(raw: String): String {
        val d = decimal(raw); val number = d.toBigDecimalOrNull() ?: return d
        val result = (if (number.signum() == 0 && d.startsWith('-')) "-" else "") + number.toPlainString()
        return if ('.' in result) result.trimEnd('0').trimEnd('.').ifEmpty { "0" } else result
    }
    fun equity(code: String, currency: String = "", default: String = "us"): String {
        val token = valuationStrip(code); require(token.isNotEmpty()) { "empty equity ticker" }
        val lower = pythonCashLower(token)
        if ('.' in token || valuationUpper(token) in currencies) return lower
        val suffix = when (valuationUpper(valuationStrip(currency))) {
            "HKD" -> "hk"
            "CNY" -> when (lower.first()) { '5', '6' -> "sh"; '0', '1', '2', '3' -> "sz"; else -> "cn" }
            "USD" -> "us"
            else -> default
        }
        return "$lower.$suffix"
    }
    fun csv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>(); var i = 0
        while (i < text.length) {
            val row = mutableListOf<String>(); val field = StringBuilder(); var quoted = false; var beginning = true; var touched = false; var points = 0
            fun append(char: Char) {
                if (!char.isLowSurrogate() || field.isEmpty() || !field.last().isHighSurrogate()) points++
                if (points > 131072) throw IllegalArgumentException("field larger than field limit (131072)")
                field.append(char)
            }
            while (i < text.length) {
                val char = text[i++]
                if (quoted) {
                    if (char == '"') {
                        if (i < text.length && text[i] == '"') { append('"'); i++ } else quoted = false
                    } else append(char)
                    continue
                }
                if (char == '\r' || char == '\n') {
                    if (char == '\r' && i < text.length && text[i] == '\n') i++
                    break
                }
                touched = true
                if (char == ',') { row += field.toString(); field.clear(); points = 0; beginning = true }
                else if (char == '"' && beginning) { quoted = true; beginning = false }
                else { append(char); beginning = false }
            }
            if (touched || row.isNotEmpty() || field.isNotEmpty()) row += field.toString()
            rows += row
        }
        return rows
    }
}
