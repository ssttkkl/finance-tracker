package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class LocalCashRelationArithmeticTest {
    private val values get() = LocalCashRelationArithmetic

    @Test fun refundRemainingCanExceedThePersistedSingleAmountRange() {
        fun fact(id: String, text: String) = RelationFact(id, values.amount(text), "CNY", "account")
        val facts = listOf(fact("expense", "-99999999999999999999.00"), fact("refund", "99999999999999999999.00"))
        val relation = mapOf("kind" to "refund_offset", "status" to "accepted", "primary_fact_id" to "expense", "secondary_fact_id" to "refund")
        val remaining = values.serialization.initialRemaining(facts, List(3) { relation })
        assertEquals("-199999999999999999998.00", remaining.getValue("expense").toPlainString())
        assertEquals(setOf("expense"), remaining.keys)
        assertEquals("99999999999999999999.00", facts[1].amount.toPlainString())
    }

    @Test fun discoveryArithmeticDoesNotRoundAtEightyDigitsOrLoseTheOriginalScale() {
        val large = values.amount("1E+100")
        val tiny = values.amount("1E-100")
        assertEquals(tiny, large.add(tiny).subtract(large))
        assertEquals("-1.2300", values.amount(CashAmount.parse("-1.2300")).toPlainString())
        assertEquals("1.2300", values.amount(CashDecimalLiteral("1.2300")).toPlainString())
        assertEquals("0.0000", values.amount("-0.0000").toPlainString())
        assertEquals("100.00", values.amount("1.0000E+2").toPlainString())
        assertEquals("12.30", values.amount("\u0661\u0662.\u0663\u0660").toPlainString())
    }

    @Test fun invalidAmountTextAndUntypedNumbersCannotBecomeDiscoveryDefaults() {
        for (text in listOf("", " 1", "1 ", "1_0", "NaN", "1E+2147483649"))
            assertFailsWith<NumberFormatException> { values.amount(text) }
        for (value in listOf(null, 1.0, true, mapOf("amount" to "1")))
            assertFailsWith<InvalidCashValue> { values.amount(value) }
    }

    @Test fun serializationKeepsExactAccumulatedEvidenceAndTheOriginalFallbackRule() {
        val fact = RelationFact("expense", values.amount("-100.00"), "CNY", "account")
        val refund = RelationFact("refund", values.amount("30.00"), "CNY", "account")
        fun relation(applied: String) = mapOf("kind" to "refund_offset", "status" to "accepted", "primary_fact_id" to "expense", "secondary_fact_id" to "refund", "applied_amount" to applied)
        assertEquals("-20.00", values.serialization.initialRemaining(listOf(fact, refund), listOf(relation("90.00"), relation("bad"))).getValue("expense").toPlainString())
        assertEquals(mapOf("amount" to "123456789012345678901.2300"),
            values.serialization.cacheJsonValue(mapOf("amount" to values.amount("123456789012345678901.2300"))))
        assertEquals("2026-01-01T00:00:00.123456+00:00", values.serialization.canonicalOccurredAt(Instant.parse("2026-01-01T00:00:00.123456789Z")))
        assertEquals("invalid time", values.serialization.canonicalOccurredAt("invalid time"))
    }

    @Test fun elapsedNanosPreservesBothLongEdgesAndRejectsTheAdjacentOverflows() {
        val zero = Instant.fromEpochSeconds(0)
        val high = Instant.fromEpochSeconds(9223372036L, 854775807L)
        val low = Instant.fromEpochSeconds(-9223372037L, 145224192L)
        assertEquals(Long.MAX_VALUE, relationElapsedNanos(zero, high))
        assertEquals(Long.MIN_VALUE, relationElapsedNanos(zero, low))
        assertFailsWith<ArithmeticException> { relationElapsedNanos(zero, Instant.fromEpochSeconds(9223372036L, 854775808L)) }
        assertFailsWith<ArithmeticException> { relationElapsedNanos(zero, Instant.fromEpochSeconds(-9223372037L, 145224191L)) }
        assertFailsWith<ArithmeticException> { relationElapsedNanos(low, zero) }
        assertEquals(-Long.MAX_VALUE, relationElapsedNanos(high, zero))
    }

    @Test fun elapsedNanosKeepsSubmicrosecondDirectionAcrossEpochAndSecondBoundaries() {
        val from = Instant.fromEpochSeconds(-1, 999999999)
        val to = Instant.fromEpochSeconds(1, 1)
        assertEquals(1000000002L, relationElapsedNanos(from, to))
        assertEquals(-1000000002L, relationElapsedNanos(to, from))
        assertEquals(0L, relationElapsedNanos(from, from))
        assertEquals(1L, relationElapsedNanos(from, Instant.fromEpochSeconds(0)))
    }
}
