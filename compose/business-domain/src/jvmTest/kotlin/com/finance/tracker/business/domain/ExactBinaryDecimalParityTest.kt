package com.finance.tracker.business.domain

import java.math.BigDecimal
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class ExactBinaryDecimalParityTest {
    @Test fun sharedExactBinaryValuesMatchOriginalBigDecimalDoubleConstructor() {
        val random = Random(91621)
        val bits = buildList {
            add(0L); add(Long.MIN_VALUE); add(1L); add(Long.MIN_VALUE or 1L)
            for (exponent in 1..2046) {
                val base = exponent.toLong() shl 52
                add(base); add(base - 1); add(base + 1); add(base or Long.MIN_VALUE)
            }
            repeat(5_000) { add(random.nextLong()) }
        }
        for (bit in bits) {
            val value = Double.fromBits(bit)
            if (!value.isFinite()) continue
            val native = BigDecimal(value)
            val result = exactBinary64Decimal(value)
            val shared = BigDecimal((if (result.negative) "-" else "") + result.coefficient + "E" + result.exponent)
            assertEquals(0, native.compareTo(shared), bit.toULong().toString(16))
        }
    }
}
