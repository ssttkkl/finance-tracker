package com.finance.tracker.business.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class PythonFloatTextParityTest {
    @Test
    fun everyBinaryExponentAndItsNeighboursMatchThePreviousBackend() {
        for (exponent in 1..2046) {
            val power = exponent.toLong() shl 52
            for (bits in power - 1..power + 1) {
                verify(Double.fromBits(bits))
                verify(-Double.fromBits(bits))
            }
        }
    }

    @Test
    fun deterministicBitPatternsAndDecimalNotationBoundariesMatch() {
        val random = Random(7062026)
        repeat(2_000) { verify(Double.fromBits(random.nextLong())) }
        for (exponent in -323..308) {
            val value = "1e$exponent".toDouble()
            if (value.isFinite() && value > 0.0) {
                verify(value)
                verify(Double.fromBits(value.toBits() - 1))
                verify(Double.fromBits(value.toBits() + 1))
            }
        }
    }

    private fun verify(value: Double) {
        assertEquals(previousBackendText(value), pythonFloatText(value), "bits=${value.toBits()}")
        if (value.isFinite()) assertEquals(value.toBits(), pythonFloatText(value).toDouble().toBits())
    }

    // The unchanged pre-port backend implementation is a JVM-only differential oracle.
    private fun previousBackendText(value: Double): String {
        if (value.isNaN()) return "nan"
        if (value == Double.POSITIVE_INFINITY) return "inf"
        if (value == Double.NEGATIVE_INFINITY) return "-inf"
        if (value == 0.0) return if (value.toBits() < 0) "-0.0" else "0.0"
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
        return coefficient + "e" + if (exponent >= 0) {
            "+${exponent.toString().padStart(2, '0')}"
        } else {
            "-${(-exponent).toString().padStart(2, '0')}"
        }
    }
}
