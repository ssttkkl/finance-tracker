package com.finance.tracker.business.domain

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class CashWorkbookNumberParityTest {
    @Test fun allBinaryExponentsAndNeighboursMatchNativeJava21() {
        assertEquals(21, Runtime.version().feature(), "Workbook number oracle requires the migration's Java 21 baseline")
        for (exponent in 1..2046) {
            val power = exponent.toLong() shl 52
            for (bits in power - 1..power + 1) { verify(Double.fromBits(bits)); verify(-Double.fromBits(bits)) }
        }
    }
    @Test fun subnormalsDecimalBoundariesAndFixedRandomBitsMatchNativeReaders() {
        for (bits in 1L..300L) { verify(Double.fromBits(bits)); verify(-Double.fromBits(bits)) }
        val random = Random(5112026)
        repeat(2_000) { verify(Double.fromBits(random.nextLong())) }
        for (exponent in -323..308) {
            val value = "1e$exponent".toDouble()
            if (value.isFinite() && value > 0) for (bits in value.toBits()-1..value.toBits()+1) {
                verify(Double.fromBits(bits)); verify(-Double.fromBits(bits))
            }
        }
        for (value in listOf(0.0,-0.0,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY)) verify(value)
    }
    @Test fun representativeCellFormattingRecordsItsActualCost() {
        val values = List(10_000) { index -> (index - 5_000) / 100.0 }
        // The result is observed so this measures the production formatter, without a flaky timing assertion.
        var expected = 0L
        var actual = 0L
        val nativeStarted = System.nanoTime()
        for (value in values) expected += (if (value == Math.rint(value) && Math.abs(value) < 1e16)
            value.toLong().toString() else value.toString()).length
        val nativeNanos = System.nanoTime() - nativeStarted
        val sharedStarted = System.nanoTime()
        for (value in values) actual += cashPlatformWorkbookNumberText(value).length
        val sharedNanos = System.nanoTime() - sharedStarted
        assertEquals(expected, actual)
        println("synthetic_cell_format count=${values.size} native_ns=$nativeNanos shared_ns=$sharedNanos text_chars=$actual")
    }
    private fun verify(value: Double) {
        // Keep the two unchanged native policies as independent differential oracles.
        val platform = if (value.isFinite() && value == Math.rint(value) && Math.abs(value) < 1e16)
            value.toLong().toString() else value.toString()
        val bank = if (value.isFinite() && value == Math.rint(value)) value.toLong().toString() else value.toString()
        assertEquals(platform, cashPlatformWorkbookNumberText(value), "platform bits=${value.toBits()}")
        assertEquals(bank, cashCcbWorkbookNumberText(value), "bank bits=${value.toBits()}")
        assertEquals(value.toString(), javaBinary64Text(value), "Java bits=${value.toBits()}")
        if (value.isFinite()) assertEquals(value.toBits(), javaBinary64Text(value).toDouble().toBits())
    }
}
