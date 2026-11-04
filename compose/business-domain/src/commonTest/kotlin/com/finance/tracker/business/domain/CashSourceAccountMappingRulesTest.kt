package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Normalized scalar samples using the production source-pattern vocabulary. */
class CashSourceAccountMappingRulesTest {
    private val amounts = object : CashSourceAccountAmounts<LedgerArithmeticValue> {
        override fun parse(text: String) = try { LedgerArithmeticValue.fromCanonical(text) }
            catch (failure: IllegalArgumentException) { throw NumberFormatException(failure.message) }
        override fun sign(value: LedgerArithmeticValue) = value.signum
        override fun negate(value: LedgerArithmeticValue) = value.negate()
        override fun plain(value: LedgerArithmeticValue) = value.toPlainString()
        override fun add(left: LedgerArithmeticValue, right: LedgerArithmeticValue) = left.add(right)
        override fun compare(left: LedgerArithmeticValue, right: LedgerArithmeticValue) = left.compareTo(right)
        override val zero = LedgerArithmeticValue.ZERO
    }
    private fun text(value: Any?) = value?.toString()?.trim { it.code in pythonWhitespace }.orEmpty()
    private val rules = CashSourceAccountMappingRules(amounts, ::text, { it?.toString() ?: "None" },
        { (it as? Number)?.toLong() ?: it.toString().toLong() },
        { value -> value != null && value != false && value != "" }, { it.uppercase() },
        { sha256Hex(sourceJson(it, { null }, ::pythonFloatText).toString()) },
        CashSourceAccountTextPatterns)

    @Test fun compositeIdentityAndAllocationConservationAreSeparate() {
        val row = mapOf("source_type" to "alipay", "payment_method" to "银行卡(1234)10元&余额(钱包)20元", "amount" to "-30.00")
        val keys = rules.sourceComponentIdentityKeys(row)
        assertEquals(listOf(listOf("alipay", "payment_method", "银行卡(1234)10元"), listOf("alipay", "payment_method", "余额(钱包)20元")), keys)
        val draft = rules.buildComponentAllocationDraft(row)
        assertEquals(true, draft["conserved"])
        assertEquals("aggregate", draft["cash_granularity"])
        assertEquals(listOf("-10", "-20"), (draft["components"] as List<*>).map { (it as Map<*, *>)["amount"] })
        assertEquals("-30.00", draft["total_amount"])
        val incomplete = rules.buildComponentAllocationDraft(row, listOf("1", "2"))
        assertFalse(incomplete["conserved"] as Boolean)
        assertEquals("requires_allocation", incomplete["status"])
    }

    @Test fun sourceIdentityKeepsFullBankAccountAndWalletAliases() {
        assertEquals(Triple("wechat", "payment_method", "微信零钱"), rules.sourceIdentityKey(mapOf("source_type" to "wechat", "payment_method" to "零钱")))
        val bank = mapOf("source_type" to "icbc_credit", "_source_account_identifier" to "6212000000001234", "currency" to "cny")
        assertEquals(Triple("icbc_credit", "file_account", "6212000000001234"), rules.sourceIdentityKey(bank))
        val groups = rules.scanSourceRows(listOf(bank, bank))
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().rowCount)
        assertEquals(listOf("CNY"), groups.single().currencies)
        assertTrue("1234" in groups.single().maskedEvidence)
    }
}
