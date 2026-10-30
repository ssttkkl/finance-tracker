package com.finance.tracker.backend

import java.math.BigDecimal
import kotlin.test.*
import kotlinx.serialization.json.*

class CashRefundHardKeyTest {
    private val golden: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-refund-hard-key.json")!!
            .bufferedReader().use { it.readText() }).jsonObject
    }
    private fun fact(id: String, recordId: String, amount: String = "-100.00",
        recordType: String = "consumption", billSource: String = "alipay", source: String = "alipay") =
        RelationFact(id = id, amount = BigDecimal(amount), currency = "CNY", accountId = "a", accountName = "现金",
            occurredAt = "2026-10-01T10:00:00+00:00", counterparty = "商户", note = "", recordType = recordType,
            billSource = billSource, source = source, recordId = recordId)
    private fun row(value: RelationFact, vararg extra: Pair<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>("id" to value.id, "txn_id" to value.recordId, "amount" to value.amount.toPlainString(),
            "raw_payload" to emptyMap<String, Any?>(), "bill_source" to value.billSource, "source" to value.source,
            "date" to "2026-10-01", "record_type" to value.recordType, "account_id" to value.accountId,
            "currency" to value.currency).apply { putAll(extra) }

    @Test fun hardKeyProposalsMatchPython() {
        val facts = listOf(
            fact("o1", "O1"), fact("o2", "O2", "-50.00"),
            fact("r1", "O1_1", "100.00", "refund"), fact("r2", "O2_1", "60.00", "refund"),
            fact("hold", "H1", "-10.00"), fact("free", "H1_U", "10.00", "refund"),
            fact("inv", "I1", "-20.00", "investment_out"), fact("invr", "I1_1", "20.00", "refund"),
            fact("we", "W1", "-30.00", billSource = "wechat", source = "wechat"),
            fact("wi", "W2", "30.00", "refund", "wechat", "wechat"),
            fact("te", "T1", "-40.00", "transfer_reversal", "wechat", "wechat"),
            fact("ti", "T2", "40.00", "transfer_reversal", "wechat", "wechat"))
        val byId = facts.associateBy { it.id }
        val rows = listOf(
            row(facts[0]), row(facts[2]), row(facts[1]), row(facts[3]),
            row(facts[4], "status" to "芝麻免押下单成功"), row(facts[5], "status" to "解冻成功"),
            row(facts[6]), row(facts[7]))
        fun proposals(value: List<RelationProposal>) = sourceJson(value.map { it.json() })
        assertEquals(golden.getValue("alipay"), proposals(CashRefundHardKey.match(rows, byId)))
        assertEquals(golden.getValue("remaining"), proposals(CashRefundHardKey.match(rows, byId,
            remainingByExpense = mapOf("o1" to BigDecimal.ZERO))))
        assertEquals(golden.getValue("linked"), proposals(CashRefundHardKey.match(rows, byId,
            linkedPairs = setOf("o1" to "r1"))))
        val wechatRows = listOf(
            row(facts[8], "status" to "已全额退款", "merchant_order_id" to "W2", "payment_method" to "零钱"),
            row(facts[9], "status" to "已退款", "txn_type" to "微信支付-退款", "payment_method" to "零钱"),
            row(facts[10], "status" to "已退款", "merchant_order_id" to "T2", "payment_method" to "零钱"),
            row(facts[11], "status" to "已退款", "txn_type" to "微信转账-退款", "payment_method" to "零钱"))
        assertEquals(golden.getValue("wechat"), proposals(CashRefundHardKey.match(wechatRows, byId)))
    }
}
