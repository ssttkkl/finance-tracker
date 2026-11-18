package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CashFundingResolutionTest {
    private val values = object : CashFundingAmountValues<LedgerArithmeticValue> {
        override fun parse(text: String) = LedgerArithmeticValue.fromCashAmount(exactCashAmount(text))
        override fun absolute(value: LedgerArithmeticValue) = value.abs()
        override fun negate(value: LedgerArithmeticValue) = value.negate()
        override fun total(values: List<LedgerArithmeticValue>) = values.fold(LedgerArithmeticValue.ZERO) { sum, value -> sum.add(value, 80) }
        override fun compare(left: LedgerArithmeticValue, right: LedgerArithmeticValue) = left.compareTo(right)
        override fun sign(value: LedgerArithmeticValue) = value.signum
        override fun plain(value: LedgerArithmeticValue) = value.toPlainString()
    }
    private val rules = CashFundingResolutionRules(values, { { _: Map<*, *> -> } },
        { fields -> fields.toMutableMap() }, { it is Int && it > 0 })
    private val groups = listOf("g1", "g2").map { CashFundingGroup(it, "alipay", "payment_method", "owner", it, it,
        currencies = listOf("CNY"), rowRefs = listOf("file:r1")) }
    private fun scan(parts: List<CashFundingPart>) = CashFundingScan(groups, listOf(CashFundingRow("file:r1",
        mapOf("amount" to "-30.00", "currency" to "CNY"), parts)))
    private fun selections(same: Boolean = false) = groups.mapIndexed { index, group -> group.sourceAccountRef to
        mapOf("account_id" to if (same) "1" else "${index + 1}", "expected_account_revision" to 3) }.toMap()
    private val accounts = (1..2).associate { "$it" to mapOf("id" to "$it", "active" to true,
        "type" to "cash", "currencies" to listOf("CNY")) }

    @Test fun allocationsAreSignedAndSameSelectedAccountMerges() {
        val input = scan(listOf(CashFundingPart("g1", "10"), CashFundingPart("g2", "20")))
        val split = rules.resolve(input, selections(), accounts).single()
        assertTrue(split.ready)
        assertEquals(listOf("-10", "-20"), (split.fields["components"] as List<*>).map { (it as Map<*, *>)["amount"] })
        val merged = rules.resolve(scan(listOf(CashFundingPart("g1", null), CashFundingPart("g2", null))), selections(true), accounts).single()
        assertTrue(merged.ready)
        assertEquals("1", merged.fields["account_id"])
        assertEquals("-30.00", ((merged.fields["components"] as List<*>).single() as Map<*, *>)["amount"])
        assertFalse("components" in input.rows.single().fields)
    }

    @Test fun unknownAllocationAndUnavailableAccountRemainExplicit() {
        val incomplete = rules.resolve(scan(listOf(CashFundingPart("g1", "10"), CashFundingPart("g2", null))), selections(), accounts).single()
        assertFalse(incomplete.ready)
        assertTrue("allocation_missing" in incomplete.issueCodes)
        val unavailable = rules.resolve(scan(listOf(CashFundingPart("g1", null))), selections(), emptyMap<String, Any>()).single()
        assertTrue("account_unavailable" in unavailable.issueCodes)
        assertFalse(unavailable.ready)
        assertFailsWith<CashFundingInputError> { rules.resolve(scan(emptyList()), mapOf("unknown" to emptyMap<String, Any>()), accounts) }
        assertFailsWith<CashFundingInputError> { rules.resolve(scan(emptyList()), mapOf("g1" to mapOf("account_id" to "1", "expected_account_revision" to true)), accounts) }
    }
}
