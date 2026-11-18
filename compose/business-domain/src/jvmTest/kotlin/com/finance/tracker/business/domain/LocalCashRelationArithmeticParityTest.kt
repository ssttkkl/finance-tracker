package com.finance.tracker.business.domain

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant as NativeInstant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class LocalCashRelationArithmeticParityTest {
    @Test fun accumulatedValuesMatchNativeUnroundedBigDecimalAndItsRepresentation() {
        val random = Random(917252)
        val samples = mutableListOf("0.0000", "-0.00", "1E+100", "1E-100", "99999999999999999999.999999999999999999", "12.3000")
        repeat(800) {
            val digits = buildString { repeat(random.nextInt(1, 121)) { append(random.nextInt(10)) } }
            samples += (if (random.nextBoolean()) "-" else "") + digits + "E" + random.nextInt(-110, 111)
        }
        for (text in samples) {
            val next = samples[random.nextInt(samples.size)]
            val first = BigDecimal(text); val second = BigDecimal(next)
            val a = LocalCashRelationArithmetic.amount(text); val b = LocalCashRelationArithmetic.amount(next)
            assertEquals(first.toPlainString(), a.toPlainString(), text)
            assertEquals(first.toString(), a.toString(), text)
            assertEquals(first.add(second).toPlainString(), a.add(b).toPlainString(), "$text + $next")
            assertEquals(first.subtract(second).toPlainString(), a.subtract(b).toPlainString(), "$text - $next")
            assertEquals(first.compareTo(second).compareTo(0), a.compareTo(b).compareTo(0))
        }
    }

    @Test fun elapsedNanosMatchesNativeDurationIncludingNegativeAndOverflowEdges() {
        val random = Random(713459)
        val samples = mutableListOf(0L to 0, 9223372036L to 854775807, 9223372036L to 854775808,
            -9223372037L to 145224192, -9223372037L to 145224191, -1L to 999999999)
        repeat(3000) { samples += random.nextLong(-62135596800L, 253402300800L) to random.nextInt(1000000000) }
        for ((index, to) in samples.withIndex()) {
            val from = if (index < 6) 0L to 0 else samples[random.nextInt(samples.size)]
            val expected = runCatching { Duration.between(NativeInstant.ofEpochSecond(from.first, from.second.toLong()), NativeInstant.ofEpochSecond(to.first, to.second.toLong())).toNanos() }
            val first = Instant.fromEpochSeconds(from.first, from.second.toLong())
            val second = Instant.fromEpochSeconds(to.first, to.second.toLong())
            if (expected.isSuccess) assertEquals(expected.getOrThrow(), relationElapsedNanos(first, second), "$from -> $to")
            else {
                assertEquals(ArithmeticException::class, expected.exceptionOrNull()!!::class)
                assertFailsWith<ArithmeticException> { relationElapsedNanos(first, second) }
            }
        }
    }
}
