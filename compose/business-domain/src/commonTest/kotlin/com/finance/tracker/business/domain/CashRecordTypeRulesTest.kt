package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CashRecordTypeRulesTest {
    private val rules = CashRecordTypeRules(
        textOrEmpty = { value -> when (value) {
            null, false -> ""
            true -> "True"
            else -> value.toString()
        } },
        truthy = { value -> value != null && value != false && value != "" },
    )

    @Test
    fun nativeSourceTypesAndDirectionalCasesKeepTheirCataloguePairs() {
        data class Case(val source: String, val row: Map<String, Any?>, val type: String, val subtype: String)
        val cases = listOf(
            Case("wechat", mapOf("txn_type" to "转账", "direction" to "收入"), "transfer_in", "ordinary_transfer"),
            Case("wechat", mapOf("txn_type" to "零钱提现", "direction" to "支出"), "withdrawal_out", "withdraw_to_bank"),
            Case("wechat", mapOf("txn_type" to "商户消费", "direction" to "收入"), "consumption", "not_applicable"),
            Case("wechat", mapOf("txn_type" to "购买理财通", "direction" to "收入"), "investment_in", "not_applicable"),
            Case("alipay", mapOf("txn_type" to "转账红包", "direction" to "支出"), "transfer_out", "ordinary_transfer"),
            Case("alipay", mapOf("txn_type" to "投资理财", "note" to "收益发放"), "interest", "not_applicable"),
            Case("alipay", mapOf("txn_type" to "投资理财", "amount" to "-10"), "investment_out", "not_applicable"),
            Case("icbc_debit", mapOf("summary" to "跨境汇款", "amount" to "-10"), "transfer_out", "cross_border_remittance"),
            Case("icbc_asia", mapOf("summary" to "轉賬", "amount" to "10"), "transfer_in", "ordinary_transfer"),
            Case("ccb_debit", mapOf("summary" to "代理收款"), "repayment", "credit_repayment"),
            Case("bank", mapOf("summary" to "预约购汇", "amount" to "-10"), "fx_out", "currency_exchange"),
            Case("bank", mapOf("summary" to "利息存入"), "interest", "not_applicable"),
            Case("bank", mapOf("summary" to "管理费"), "fee", "not_applicable"),
        )
        for (case in cases) assertEquals(case.type to case.subtype, rules.classify(case.source, case.row), case.toString())
    }

    @Test
    fun reversalP2pReturnRefundAndRepaymentKeepTheirPrecedence() {
        val p2p = mapOf<String, Any?>("txn_type" to "转账退款", "_is_refund" to true, "summary" to "信用卡还款")
        assertEquals("transfer_reversal", rules.classifyType("wechat", p2p))
        assertEquals("reversal", rules.classifyType("wechat", p2p + ("_is_reversal" to true)))
        assertEquals("refund", rules.classifyType("bank", p2p))
        assertEquals("income", rules.classifyType("icbc_credit", mapOf("offset_type" to "benefit_rebate", "amount" to "-10")))
        assertEquals("fee", rules.classifyType("icbc_credit", mapOf("offset_type" to "fee_reversal", "amount" to "10")))
    }

    @Test
    fun sourceFallbackWhitespaceAndExactDecimalDirectionDoNotUseBinaryFloats() {
        assertEquals("transfer_in", rules.classifyType(null, mapOf("bill_source" to "\u001cwechat\u001f", "txn_type" to "转账", "amount" to "1e-999999")))
        assertEquals("transfer_out", rules.classifyType("", mapOf("source_type" to "wechat", "txn_type" to "转账", "amount" to "-1e-999999")))
        assertEquals("transfer_in", rules.classifyType("wechat", mapOf("txn_type" to "转账", "amount" to "-0.000")))
        assertEquals("transfer_out", rules.classifyType("wechat", mapOf("txn_type" to "转账", "direction" to "支出", "amount" to "100")))
    }

    @Test
    fun subtypeRejectionsKeepTheirExactMessages() {
        rules.validateSubtype("transfer_out", "ordinary_transfer")
        assertEquals("unknown record_type: missing", assertFailsWith<CashRecordRuleFailure> {
            rules.validateSubtype("missing", "not_applicable")
        }.message)
        assertEquals("record_subtype 'not_applicable' is invalid for record_type 'transfer_out'", assertFailsWith<CashRecordRuleFailure> {
            rules.validateSubtype("transfer_out", "not_applicable")
        }.message)
    }
}
