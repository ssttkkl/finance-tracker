package com.finance.tracker.business.domain

import kotlin.test.*
import kotlinx.serialization.json.*

class CashRefundHardKeyTest {
    private val golden=Json.parseToJsonElement("""{"alipay":[{"kind":"refund_offset","primary_fact_id":"inv","secondary_fact_id":"invr","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"scan.alipay.order_prefix.v1","confidence":"strong","evidence":{"amount_delta":"0","time_delta_seconds":0,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"scan.alipay.order_prefix.v1","candidate_count":1,"signals":["phase_a","platform_hard_key"],"open_leg":false,"anchor_role":"","candidate_fact_ids":[],"extras":{"phase":"A","refund_amount":"20.00"}},"created_by":"system","anchor_fact_id":"inv","open_leg":false},{"kind":"refund_offset","primary_fact_id":"o1","secondary_fact_id":"r1","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"scan.alipay.order_prefix.v1","confidence":"strong","evidence":{"amount_delta":"0","time_delta_seconds":0,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"scan.alipay.order_prefix.v1","candidate_count":1,"signals":["phase_a","platform_hard_key"],"open_leg":false,"anchor_role":"","candidate_fact_ids":[],"extras":{"phase":"A","refund_amount":"100.00"}},"created_by":"system","anchor_fact_id":"o1","open_leg":false},{"kind":"refund_offset","primary_fact_id":"hold","secondary_fact_id":"free","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"scan.alipay.auth_unfreeze.v1","confidence":"strong","evidence":{"amount_delta":"0","time_delta_seconds":0,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"scan.alipay.auth_unfreeze.v1","candidate_count":1,"signals":["phase_a","platform_hard_key"],"open_leg":false,"anchor_role":"","candidate_fact_ids":[],"extras":{"phase":"A","refund_amount":"10.00"}},"created_by":"system","anchor_fact_id":"hold","open_leg":false}],"remaining":[{"kind":"refund_offset","primary_fact_id":"inv","secondary_fact_id":"invr","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"scan.alipay.order_prefix.v1","confidence":"strong","evidence":{"amount_delta":"0","time_delta_seconds":0,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"scan.alipay.order_prefix.v1","candidate_count":1,"signals":["phase_a","platform_hard_key"],"open_leg":false,"anchor_role":"","candidate_fact_ids":[],"extras":{"phase":"A","refund_amount":"20.00"}},"created_by":"system","anchor_fact_id":"inv","open_leg":false},{"kind":"refund_offset","primary_fact_id":"hold","secondary_fact_id":"free","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"scan.alipay.auth_unfreeze.v1","confidence":"strong","evidence":{"amount_delta":"0","time_delta_seconds":0,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"scan.alipay.auth_unfreeze.v1","candidate_count":1,"signals":["phase_a","platform_hard_key"],"open_leg":false,"anchor_role":"","candidate_fact_ids":[],"extras":{"phase":"A","refund_amount":"10.00"}},"created_by":"system","anchor_fact_id":"hold","open_leg":false}],"linked":[{"kind":"refund_offset","primary_fact_id":"inv","secondary_fact_id":"invr","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"scan.alipay.order_prefix.v1","confidence":"strong","evidence":{"amount_delta":"0","time_delta_seconds":0,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"scan.alipay.order_prefix.v1","candidate_count":1,"signals":["phase_a","platform_hard_key"],"open_leg":false,"anchor_role":"","candidate_fact_ids":[],"extras":{"phase":"A","refund_amount":"20.00"}},"created_by":"system","anchor_fact_id":"inv","open_leg":false},{"kind":"refund_offset","primary_fact_id":"hold","secondary_fact_id":"free","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"scan.alipay.auth_unfreeze.v1","confidence":"strong","evidence":{"amount_delta":"0","time_delta_seconds":0,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"scan.alipay.auth_unfreeze.v1","candidate_count":1,"signals":["phase_a","platform_hard_key"],"open_leg":false,"anchor_role":"","candidate_fact_ids":[],"extras":{"phase":"A","refund_amount":"10.00"}},"created_by":"system","anchor_fact_id":"hold","open_leg":false}],"wechat":[{"kind":"refund_offset","primary_fact_id":"te","secondary_fact_id":"ti","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"scan.wechat.transfer_return.v1","confidence":"strong","evidence":{"amount_delta":"0","time_delta_seconds":0,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"scan.wechat.transfer_return.v1","candidate_count":1,"signals":["phase_a","platform_hard_key"],"open_leg":false,"anchor_role":"","candidate_fact_ids":[],"extras":{"phase":"A","refund_amount":"40.00"}},"created_by":"system","anchor_fact_id":"te","open_leg":false},{"kind":"refund_offset","primary_fact_id":"we","secondary_fact_id":"wi","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"scan.wechat.mer_txn.v1","confidence":"strong","evidence":{"amount_delta":"0","time_delta_seconds":0,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"scan.wechat.mer_txn.v1","candidate_count":1,"signals":["phase_a","platform_hard_key"],"open_leg":false,"anchor_role":"","candidate_fact_ids":[],"extras":{"phase":"A","refund_amount":"30.00"}},"created_by":"system","anchor_fact_id":"we","open_leg":false}]}""").jsonObject
    private val rules=CashRefundHardKeyRules(cashRelationCore,PlatformRefundTestAdapters.platform,
        PlatformRefundTestAdapters::absolute,PlatformRefundTestAdapters::sign,PlatformRefundTestAdapters::compare,
        {a,b ->CashAmount.parse(absoluteCashDifferenceText(a,b))},
        ::cashAbsolutePlainText,CashAmount.parse("0"))
    private fun encode(value:Any?)=sourceJson(value,{null},::pythonFloatText)
    private fun fact(id: String, recordId: String, amount: String = "-100.00",
        recordType: String = "consumption", billSource: String = "alipay", source: String = "alipay") =
        RelationFact(id = id, amount = CashAmount.parse(amount), currency = "CNY", accountId = "a", accountName = "现金",
            occurredAt = "2026-10-01T10:00:00+00:00", counterparty = "商户", note = "", recordType = recordType,
            billSource = billSource, source = source, recordId = recordId)
    private fun row(value: RelationFact<CashAmount>, vararg extra: Pair<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>("id" to value.id, "txn_id" to value.recordId, "amount" to cashLegacyDecimalText(value.amount),
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
        fun proposals(value: List<RelationProposal>) = JsonArray(value.map { it.toJsonValue(::encode) })
        assertEquals(golden.getValue("alipay"), proposals(rules.match(rows, byId)))
        assertEquals(golden.getValue("remaining"), proposals(rules.match(rows, byId,
            remainingByExpense = mapOf("o1" to CashAmount.parse("0")))))
        assertEquals(golden.getValue("linked"), proposals(rules.match(rows, byId,
            linkedPairs = setOf("o1" to "r1"))))
        val wechatRows = listOf(
            row(facts[8], "status" to "已全额退款", "merchant_order_id" to "W2", "payment_method" to "零钱"),
            row(facts[9], "status" to "已退款", "txn_type" to "微信支付-退款", "payment_method" to "零钱"),
            row(facts[10], "status" to "已退款", "merchant_order_id" to "T2", "payment_method" to "零钱"),
            row(facts[11], "status" to "已退款", "txn_type" to "微信转账-退款", "payment_method" to "零钱"))
        assertEquals(golden.getValue("wechat"), proposals(rules.match(wechatRows, byId)))
    }

    @Test fun remainingCapacityUsesExactDecimalsAndDoesNotMutateTheCaller() {
        val expense=fact("o","ORDER","-1.000000000000000001")
        val first=fact("r1","ORDER_1","0.500000000000000000","refund")
        val tooMuch=fact("r2","ORDER_2","0.500000000000000002","refund")
        val facts=listOf(expense,first,tooMuch)
        val remaining=mapOf("o" to CashAmount.parse("1.000000000000000001"))
        val result=rules.match(facts.map {row(it)},facts.associateBy {it.id},remainingByExpense=remaining)
        assertEquals(listOf("r1"),result.map {it.secondaryFactId})
        assertEquals("0.500000000000000000",result.single().evidence.extras["refund_amount"])
        assertEquals(CashAmount.parse("1.000000000000000001"),remaining.getValue("o"))
    }
    @Test fun identicalOrderKeysRemainIsolatedBySourceIdentity() {
        val a=fact("a","ORDER").copy(sourceIdentity="owner-a")
        val b=fact("b","ORDER").copy(sourceIdentity="owner-b")
        val ra=fact("ra","ORDER_1","100","refund").copy(sourceIdentity="owner-a")
        val rb=fact("rb","ORDER_1","100","refund").copy(sourceIdentity="owner-b")
        val facts=listOf(rb,b,ra,a)
        assertEquals(listOf("a" to "ra","b" to "rb"),rules.match(facts.map {row(it)},facts.associateBy {it.id}).map {it.primaryFactId to it.secondaryFactId})
        val ambiguous=listOf(a,b.copy(sourceIdentity="owner-a"),ra)
        assertTrue(rules.match(ambiguous.map {row(it)},ambiguous.associateBy {it.id}).isEmpty())
    }
}
