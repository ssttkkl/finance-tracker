package com.finance.tracker.backend

import java.math.BigDecimal
import kotlin.test.*
import kotlinx.serialization.json.*

class CashRefundDiamondTest {
    private val golden: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-refund-diamond.json")!!
            .bufferedReader().use { it.readText() }).jsonObject
    }
    private fun fact(id: String = "1", amount: String = "-10.00", billSource: String = "alipay",
        source: String = "alipay", note: String = "", recordType: String = "consumption",
        recordId: String = "R-$id") = RelationFact(id = id, amount = BigDecimal(amount), currency = "CNY",
        accountId = "a", accountName = "现金", occurredAt = "2026-10-01T10:00:00+00:00",
        counterparty = "商户", note = note, recordType = recordType, billSource = billSource, source = source,
        recordId = recordId)

    @Test fun refundSignalsAndDiamondMatchPython() {
        val texts = listOf("退款-商品", "消费退货 商品", "微信红包-退款", "微信红包", "转账备注", "群收款", "提现",
            "refund item", "普通消费", "")
        val expectedTexts = golden.getValue("texts").jsonArray
        for ((index, text) in texts.withIndex()) {
            val expected = expectedTexts[index].jsonObject
            assertEquals(expected.getValue("signal").jsonPrimitive.boolean, CashRefundSignals.hasRefundSignal(text), "signal $index")
            assertEquals(expected.getValue("p2p").jsonPrimitive.boolean, CashRefundSignals.isP2pTransferFamily(text), "p2p $index")
            assertEquals(expected.getValue("p2p_refund").jsonPrimitive.boolean, CashRefundSignals.isP2pStyleRefund(text), "p2p refund $index")
            assertEquals(expected.getValue("excluded").jsonPrimitive.boolean, CashRefundSignals.isRefundExcludedLeg(text), "excluded $index")
            assertEquals(expected.getValue("subtype").jsonPrimitive.content, CashRefundSignals.p2pSubtype(text), "subtype $index")
            assertEquals(expected.getValue("stripped").jsonPrimitive.content,
                CashRefundSignals.stripRefundDescriptionPrefix(text), "stripped $index")
        }
        val facts = listOf(
            fact("bank_pay", "-100.00", "icbc_debit", "", "商品", recordId = "B1"),
            fact("bank_ref", "100.00", "icbc_debit", "", "退款-商品", "refund", "B2"),
            fact("plat_pay", "-100.00", "alipay", "alipay", "商品", recordId = "A1"),
            fact("plat_ref", "100.00", "alipay", "alipay", "退款-商品", "refund", "A2"),
            fact("other", "-5.00", "ccb_debit", "", recordId = "C1"))
        val expectedFacts = golden.getValue("facts").jsonArray
        for ((index, item) in facts.withIndex()) {
            val expected = expectedFacts[index].jsonObject
            assertEquals(expected.getValue("platform").jsonPrimitive.boolean,
                CashRefundSignals.isPlatformImportRefundSource(item), "platform $index")
            assertEquals(expected.getValue("refund_signal").jsonPrimitive.boolean,
                CashRefundSignals.hasRefundSignalForFact(item), "refund signal $index")
        }
        assertEquals(golden.getValue("title").jsonArray.map { it.jsonPrimitive.boolean }, listOf(
            CashRefundSignals.refundTitleExactMatch(facts[1], facts[0]),
            CashRefundSignals.refundTitleExactMatch(facts[3], facts[2]),
            CashRefundSignals.refundTitleExactMatch(fact(note = "退款-美团App"), fact(note = "美团App")),
            CashRefundSignals.refundTitleExactMatch(fact(note = "退款-"), fact(note = ""))))
        val proposals = CashRefundDiamond.match(facts,
            acceptedMirrors = listOf("bank_ref" to "plat_ref", "plat_pay" to "bank_pay"),
            acceptedPlatformRefunds = listOf("plat_pay" to "plat_ref"))
        assertEquals(golden.getValue("diamond"), sourceJson(proposals.map { it.json() }))
    }
}
