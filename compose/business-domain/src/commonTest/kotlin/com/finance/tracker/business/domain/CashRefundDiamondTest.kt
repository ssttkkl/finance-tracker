package com.finance.tracker.business.domain

import kotlin.test.*
import kotlinx.serialization.json.*

class CashRefundDiamondTest {
    private val golden=Json.parseToJsonElement("""{"texts":[{"text":"退款-商品","signal":true,"p2p":false,"p2p_refund":false,"excluded":false,"subtype":"","stripped":"商品"},{"text":"消费退货 商品","signal":true,"p2p":false,"p2p_refund":false,"excluded":false,"subtype":"","stripped":"消费退货 商品"},{"text":"微信红包-退款","signal":true,"p2p":true,"p2p_refund":true,"excluded":false,"subtype":"redpacket","stripped":"微信红包-退款"},{"text":"微信红包","signal":false,"p2p":true,"p2p_refund":false,"excluded":true,"subtype":"redpacket","stripped":"微信红包"},{"text":"转账备注","signal":false,"p2p":true,"p2p_refund":false,"excluded":true,"subtype":"transfer","stripped":"转账备注"},{"text":"群收款","signal":false,"p2p":true,"p2p_refund":false,"excluded":true,"subtype":"receipt","stripped":"群收款"},{"text":"提现","signal":false,"p2p":true,"p2p_refund":false,"excluded":true,"subtype":"withdraw","stripped":"提现"},{"text":"refund item","signal":true,"p2p":false,"p2p_refund":false,"excluded":false,"subtype":"","stripped":"refund item"},{"text":"普通消费","signal":false,"p2p":false,"p2p_refund":false,"excluded":false,"subtype":"","stripped":"普通消费"},{"text":"","signal":false,"p2p":false,"p2p_refund":false,"excluded":false,"subtype":"","stripped":""}],"facts":[{"id":"bank_pay","platform":false,"refund_signal":false},{"id":"bank_ref","platform":false,"refund_signal":true},{"id":"plat_pay","platform":true,"refund_signal":false},{"id":"plat_ref","platform":true,"refund_signal":true},{"id":"other","platform":false,"refund_signal":false}],"title":[true,true,false,false],"diamond":[{"kind":"refund_offset","primary_fact_id":"bank_pay","secondary_fact_id":"bank_ref","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"refund_offset.diamond_via_platform.v1","confidence":"strong","evidence":{"amount_delta":"0","time_delta_seconds":0,"same_currency":true,"counterparty_similarity":"","source_pair":["icbc_debit","icbc_debit"],"rule_id":"refund_offset.diamond_via_platform.v1","candidate_count":1,"signals":["diamond","platform_chain","exact_or_residual"],"open_leg":false,"anchor_role":"","candidate_fact_ids":[],"extras":{"via":"platform_refund_mirror","refund_amount":"100.00"}},"created_by":"system","anchor_fact_id":"bank_pay","open_leg":false}],"diamond_empty_seeds":[{"kind":"refund_offset","primary_fact_id":"bank_pay","secondary_fact_id":"bank_ref","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"refund_offset.diamond_via_platform.v1","confidence":"strong","evidence":{"amount_delta":"0","time_delta_seconds":0,"same_currency":true,"counterparty_similarity":"","source_pair":["icbc_debit","icbc_debit"],"rule_id":"refund_offset.diamond_via_platform.v1","candidate_count":1,"signals":["diamond","platform_chain","exact_or_residual"],"open_leg":false,"anchor_role":"","candidate_fact_ids":[],"extras":{"via":"platform_refund_mirror","refund_amount":"100.00"}},"created_by":"system","anchor_fact_id":"bank_pay","open_leg":false}]}""").jsonObject
    private fun sourceJson(value:Any?)=sourceJson(value,{null},::pythonFloatText)
    private fun fact(id: String = "1", amount: String = "-10.00", billSource: String = "alipay",
        source: String = "alipay", note: String = "", recordType: String = "consumption",
        recordId: String = "R-$id") = RelationFact(id = id, amount = CashAmount.parse(amount), currency = "CNY",
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
                CashRefundSignals.hasRefundSignalForFact(item,cashRelationCore), "refund signal $index")
        }
        assertEquals(golden.getValue("title").jsonArray.map { it.jsonPrimitive.boolean }, listOf(
            CashRefundSignals.refundTitleExactMatch(facts[1], facts[0]),
            CashRefundSignals.refundTitleExactMatch(facts[3], facts[2]),
            CashRefundSignals.refundTitleExactMatch(fact(note = "退款-美团App"), fact(note = "美团App")),
            CashRefundSignals.refundTitleExactMatch(fact(note = "退款-"), fact(note = ""))))
        val proposals = cashRefundDiamond.match(facts,
            acceptedMirrors = listOf("bank_ref" to "plat_ref", "plat_pay" to "bank_pay"),
            acceptedPlatformRefunds = listOf("plat_pay" to "plat_ref"))
        assertEquals(golden.getValue("diamond"), JsonArray(proposals.map {it.toJsonValue(::sourceJson)}))
        assertEquals(golden.getValue("diamond_empty_seeds"), JsonArray(cashRefundDiamond.match(facts,
            acceptedMirrors = listOf("bank_ref" to "plat_ref", "plat_pay" to "bank_pay"),
            acceptedPlatformRefunds = listOf("plat_pay" to "plat_ref"), openOrPendingBankRefundIds = emptyList()).map { it.toJsonValue(::sourceJson) }))
    }
    private fun chain(pay:String,refund:String,start:String="2026-01-01T00:00:00Z",end:String="2026-01-01T00:00:00Z"):List<RelationProposal> {
        val facts=listOf(fact("bp",pay,"bank","").copy(occurredAt=start),fact("br",refund,"bank","",recordType="refund").copy(occurredAt=end),
            fact("pp",pay),fact("pr",refund,recordType="refund"))
        return cashRefundDiamond.match(facts,listOf("bp" to "pp","br" to "pr"),listOf("pp" to "pr"))
    }
    @Test fun decimalToleranceIsExactAtTheSmallestSupportedUnitAndAcrossCarry() {
        assertEquals(1,chain("-1","1.0001").size)
        assertTrue(chain("-1","1.000100000000000001").isEmpty())
        assertEquals(1,chain("-99999999999999999999.999999999999999999","99999999999999999999.999999999999999999").size)
        val precise=chain("-1","0.0010000000").single()
        assertEquals("0.0010000000",precise.evidence.extras["refund_amount"])
    }
    @Test fun integerEvidenceKeepsNullAndCountsWhenConvertedToJson() {
        val proposal=RelationProposal("refund_offset","a",null,evidence=RelationEvidence(timeDeltaSeconds=null))
        assertTrue(proposal.isOpenLeg);assertEquals("a",proposal.effectiveAnchorFactId)
        val json=proposal.toJsonValue(::sourceJson)
        val evidence=json.getValue("evidence").jsonObject
        assertEquals(JsonNull,evidence["time_delta_seconds"])
        assertEquals(JsonPrimitive(1),evidence["candidate_count"])
        assertFalse(evidence.getValue("candidate_count").jsonPrimitive.isString)
        assertEquals(JsonPrimitive("a"),json["anchor_fact_id"])
    }
    @Test fun timeDifferenceTruncatesSubsecondBoundariesBeforeAbsoluteValue() {
        assertEquals(0,chain("-1","1","2026-01-01T00:00:00.999999Z","2026-01-01T00:00:01.000001Z").single().evidence.timeDeltaSeconds)
        assertEquals(0,chain("-1","1","2026-01-01T00:00:01.000001Z","2026-01-01T00:00:00.999999Z").single().evidence.timeDeltaSeconds)
    }

}
