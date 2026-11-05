package com.finance.tracker.backend

import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.*

class PostingValidationCacheTest {
    private fun record(changes: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> = linkedMapOf<String, Any?>(
        "state" to "posted", "amount" to BigDecimal("-20"), "currency" to "CNY",
        "occurred_at" to Instant.parse("2026-08-01T00:00:00Z"), "account_id" to "41",
        "record_id" to "source-1", "source_type" to "wechat", "source_scope_key" to "source-owner",
        "cash_granularity" to "atomic", "components" to listOf(linkedMapOf<String, Any?>("component_id" to "501",
            "account_id" to "41", "amount" to "-20", "currency" to "CNY", "ordinal" to 0)),
        "counterparty" to "商户", "counterparty_account" to "1234", "counterparty_account_attrs" to listOf("tail"),
        "note" to "来源备注", "category_id" to null, "record_type" to "consumption", "record_subtype" to "not_applicable",
        "source_payload" to mapOf("备注" to "来源备注")).apply { putAll(changes) }
    private fun outcome(validate: (Map<*, *>) -> Unit, value: Map<*, *>): Pair<String, List<String>>? =
        try { validate(value); null } catch (error: CashPolicyError) { error.code to error.fields }

    @Test fun identicalPlainFinancialValuesAreValidatedOncePerCall() {
        val calls = mutableListOf<Int>()
        val validate: (Map<*, *>) -> Unit = { calls.add(1); CashRecordPolicy.validateForPosting(it) }
        val cached = PostingValidationCache(validate)
        repeat(20) { cached(record(mapOf("note" to it.toString()))) }
        assertEquals(listOf(1), calls)
        PostingValidationCache(validate)(record())
        assertEquals(listOf(1, 1), calls)
    }

    @Test fun constructibleOriginalMatrixMatchesCompleteValidatorAfterSuccess() {
        val changes = listOf(emptyMap(), mapOf("amount" to "-21"), mapOf("amount" to true),
            mapOf("amount" to BigDecimal("1e40")), mapOf("amount" to BigDecimal("1e-19")), mapOf("currency" to "usd"),
            mapOf("occurred_at" to null), mapOf("record_type" to "refund"), mapOf("record_subtype" to "invalid"),
            mapOf("account_id" to "42"), mapOf("cash_granularity" to "aggregate"), mapOf("components" to emptyList<Any?>()),
            mapOf("components" to listOf(mapOf("amount" to "-20", "currency" to "CNY", "account_id" to "41", "ordinal" to true))),
            mapOf("components" to listOf(mapOf("amount" to "-21", "currency" to "CNY", "account_id" to "41", "ordinal" to 0))))
        assertEquals(14, changes.size)
        for (change in changes) {
            val cached = PostingValidationCache(CashRecordPolicy::validateForPosting)
            cached(record()); val value = record(change)
            assertEquals(outcome(CashRecordPolicy::validateForPosting, value), outcome(cached::invoke, value), change.toString())
        }
        assertNull(outcome(CashRecordPolicy::validateForPosting, record(mapOf("record_type" to "refund"))))
        val cached = PostingValidationCache(CashRecordPolicy::validateForPosting)
        cached(record())
        assertEquals("invalid_field" to listOf("amount"), outcome(cached::invoke, record(mapOf("amount" to "NaN"))))
    }

    @Test fun mutationsMissingFieldsAndOffsetTimesCannotReuseSuccess() {
        var calls = 0
        val cached = PostingValidationCache { calls++; CashRecordPolicy.validateForPosting(it) }
        val value = record(); cached(value)
        @Suppress("UNCHECKED_CAST") val part = (value["components"] as List<MutableMap<String, Any?>>).single()
        part["amount"] = "-21"
        assertEquals("component_total_mismatch", outcome(cached::invoke, value)!!.first)
        part["amount"] = "-20"; part.remove("ordinal")
        assertEquals("incomplete_record", outcome(cached::invoke, value)!!.first)
        repeat(2) { cached(record(mapOf("occurred_at" to OffsetDateTime.parse("2026-01-01T00:00:00+08:00")))) }
        assertEquals(5, calls)
        assertTrue(java.lang.reflect.Modifier.isFinal(String::class.java.modifiers))
    }

    @Test fun unknownComponentObjectsAndFailuresAlwaysReachValidator() {
        var calls = 0
        val cached = PostingValidationCache { calls++; CashRecordPolicy.validateForPosting(it) }
        val value = record()
        @Suppress("UNCHECKED_CAST") val part = (value["components"] as List<MutableMap<String, Any?>>).single()
        part["extra"] = Any()
        repeat(2) { assertEquals("invalid_field" to listOf("components"), outcome(cached::invoke, value)) }
        val invalid = record(mapOf("amount" to true))
        repeat(2) { assertEquals("invalid_field" to listOf("amount"), outcome(cached::invoke, invalid)) }
        assertEquals(4, calls); assertEquals(0, cached.successCount)
        val failure = IllegalStateException("synthetic-validator-failure")
        val broken = PostingValidationCache { throw failure }
        repeat(2) { assertSame(failure, assertFailsWith<IllegalStateException> { broken(record()) }) }
        assertEquals(0, broken.successCount)
    }

    @Test fun fixedArithmeticHasNoAmbientDecimalContext() {
        val value = record(mapOf("amount" to "-200", "components" to listOf(mapOf("account_id" to "41",
            "amount" to "-200", "currency" to "CNY", "ordinal" to 0))))
        val cached = PostingValidationCache(CashRecordPolicy::validateForPosting)
        cached(value)
        assertEquals(BigDecimal("-2E+2"), BigDecimal("-200").add(BigDecimal.ONE, java.math.MathContext(1)))
        assertEquals(BigDecimal("-199"), BigDecimal("-200").add(BigDecimal.ONE))
        assertNull(outcome(cached::invoke, value)); assertNull(outcome(CashRecordPolicy::validateForPosting, value))
    }

    @Test fun cacheIsBoundedWithoutDiscardingValidation() {
        var calls = 0
        val cached = PostingValidationCache { calls++; CashRecordPolicy.validateForPosting(it) }
        fun input(index: Int) = record(mapOf("occurred_at" to "2026-08-01T00:00:${(index % 60).toString().padStart(2, '0')}+00:00",
            "components" to listOf(mapOf("component_id" to (index + 1).toString(), "account_id" to "41", "amount" to "-20", "currency" to "CNY", "ordinal" to 0))))
        repeat(257) { cached(input(it)) }
        assertEquals(256, cached.successCount); assertEquals(257, calls)
        cached(input(0)); assertEquals(257, calls)
        cached(input(256)); assertEquals(258, calls)
    }

    @Test fun keysKeepPresenceTypesExactRepresentationAndOrder() {
        var calls = 0
        val cached = PostingValidationCache { calls++ }
        val base = record(); cached(base)
        for (amount in listOf<Any>(BigDecimal("-20.0"), "-20", -20, -20L, CashAmount.parse("-20"), CashAmount.parse("-20.0")))
            cached(record(mapOf("amount" to amount)))
        assertEquals(7, calls)
        val absent = record(); absent.remove("account_id"); cached(absent)
        cached(record(mapOf("account_id" to null))); assertEquals(9, calls)
        val parts = listOf(mapOf("account_id" to "41"), mapOf("account_id" to "42"))
        cached(record(mapOf("components" to parts))); cached(record(mapOf("components" to parts.reversed())))
        assertEquals(11, calls)
        cached(record()); assertEquals(11, calls)
    }

    @Test fun unfamiliarContainersAndOversizedComponentsBypassCache() {
        var calls = 0
        val cached = PostingValidationCache { calls++ }
        val custom = object : LinkedHashMap<String, Any?>(record()) {}
        val unfamiliar = object : ArrayList<Any?>(listOf(mapOf("account_id" to "41"))) {}
        for (value in listOf(custom, record(mapOf("components" to unfamiliar)), record(mapOf("components" to List(129) { mapOf("account_id" to "41") })),
            record(mapOf("components" to listOf(mapOf("extra" to "ordinary")))), record(mapOf("amount" to BigDecimal("1.23456789012345678901234567890123456789"))))) {
            cached(value); cached(value)
        }
        assertEquals(10, calls); assertEquals(0, cached.successCount)
    }

    @Test fun validUnknownFieldsAndLargeComponentListsStillValidateEveryTime() {
        var calls = 0
        val cached = PostingValidationCache { calls++; CashRecordPolicy.validateForPosting(it) }
        val unknown = record(mapOf("components" to listOf(mapOf("account_id" to "41", "amount" to "-20",
            "currency" to "CNY", "ordinal" to 0, "extra" to listOf("x")))))
        val large = record(mapOf("amount" to "-129", "cash_granularity" to "aggregate", "account_id" to null,
            "components" to List(129) { mapOf("account_id" to (it + 1).toString(), "ordinal" to it, "amount" to "-1", "currency" to "CNY") }))
        for (value in listOf(unknown, large)) {
            val before = CashRecordPolicy.copyValue(value)
            repeat(2) { assertNull(outcome(cached::invoke, value)) }
            assertEquals(before, value)
        }
        assertEquals(4, calls); assertEquals(0, cached.successCount)
    }

    @Test fun signedZeroAndLongExactDecimalsKeepOriginalRepresentations() {
        var calls = 0
        val cached = PostingValidationCache { calls++; CashRecordPolicy.validateForPosting(it) }
        for (text in listOf("-0.00", "0.00")) {
            val value = record(mapOf("amount" to CashAmount.parse(text), "components" to listOf(mapOf("account_id" to "41",
                "amount" to "0", "currency" to "CNY", "ordinal" to 0))))
            cached(value); cached(value)
        }
        assertEquals(2, calls); assertEquals(2, cached.successCount)
        val text = "-1." + "0".repeat(40)
        for (amount in listOf(BigDecimal(text), CashAmount.parse(text))) {
            val value = record(mapOf("amount" to amount, "components" to listOf(mapOf("account_id" to "41",
                "amount" to "-1", "currency" to "CNY", "ordinal" to 0))))
            cached(value); cached(value)
        }
        assertEquals(6, calls); assertEquals(2, cached.successCount)
    }
}
