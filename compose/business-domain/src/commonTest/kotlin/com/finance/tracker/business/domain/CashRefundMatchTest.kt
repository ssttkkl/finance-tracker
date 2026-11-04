package com.finance.tracker.business.domain

import kotlin.test.*
import kotlinx.serialization.json.*

class CashRefundMatchTest {
    private val golden=Json.parseToJsonElement("""{"merchant":{"kind":"refund_offset","primary_fact_id":"e1","secondary_fact_id":"r1","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"refund_offset.merchant_or_order.v1","confidence":"strong","evidence":{"amount_delta":"0.00","time_delta_seconds":86400,"same_currency":true,"counterparty_similarity":"商户","source_pair":["alipay","icbc_debit"],"rule_id":"refund_offset.merchant_or_order.v1","candidate_count":1,"signals":["refund","merchant","same_account"],"open_leg":false,"anchor_role":"","candidate_fact_ids":["e1"],"extras":{"refund_amount":"10.00","expense_amount":"10.00","remaining_before":"10.00","days":"1","within_auto":"true","priority":"2"}},"created_by":"system","anchor_fact_id":"r1","open_leg":false},"order_lock_late":{"kind":"refund_offset","primary_fact_id":"e1","secondary_fact_id":"r1","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"refund_offset.merchant_or_order.v1","confidence":"strong","evidence":{"amount_delta":"0.00","time_delta_seconds":1728000,"same_currency":true,"counterparty_similarity":"商户","source_pair":["alipay","icbc_debit"],"rule_id":"refund_offset.merchant_or_order.v1","candidate_count":1,"signals":["refund","merchant","order_lock","same_account"],"open_leg":false,"anchor_role":"","candidate_fact_ids":["e1"],"extras":{"refund_amount":"10.00","expense_amount":"10.00","remaining_before":"10.00","days":"20","within_auto":"true","priority":"4"}},"created_by":"system","anchor_fact_id":"r1","open_leg":false},"title":{"kind":"refund_offset","primary_fact_id":"e1","secondary_fact_id":"r1","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"refund_offset.merchant_or_order.v1","confidence":"strong","evidence":{"amount_delta":"0.00","time_delta_seconds":86400,"same_currency":true,"counterparty_similarity":"未知","source_pair":["alipay","icbc_debit"],"rule_id":"refund_offset.merchant_or_order.v1","candidate_count":1,"signals":["refund","title_exact","same_account","title_exact_unique"],"open_leg":false,"anchor_role":"","candidate_fact_ids":["e1"],"extras":{"refund_amount":"10.00","expense_amount":"10.00","remaining_before":"10.00","days":"1","within_auto":"true","priority":"3"}},"created_by":"system","anchor_fact_id":"r1","open_leg":false},"account":{"kind":"refund_offset","primary_fact_id":"e1","secondary_fact_id":"r1","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"refund_offset.merchant_or_order.v1","confidence":"strong","evidence":{"amount_delta":"0.00","time_delta_seconds":86400,"same_currency":true,"counterparty_similarity":"商户","source_pair":["alipay","icbc_debit"],"rule_id":"refund_offset.merchant_or_order.v1","candidate_count":2,"signals":["refund","counterparty_account","same_account"],"open_leg":false,"anchor_role":"","candidate_fact_ids":["e1","e2"],"extras":{"refund_amount":"10.00","expense_amount":"10.00","remaining_before":"10.00","days":"1","within_auto":"true","priority":"3"}},"created_by":"system","anchor_fact_id":"r1","open_leg":false},"weak":{"kind":"refund_offset","primary_fact_id":"e1","secondary_fact_id":"r1","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"refund_offset.merchant_or_order.v1","confidence":"strong","evidence":{"amount_delta":"0.00","time_delta_seconds":86400,"same_currency":true,"counterparty_similarity":"退款","source_pair":["alipay","icbc_debit"],"rule_id":"refund_offset.merchant_or_order.v1","candidate_count":1,"signals":["refund","same_account","weak_link","weak_unique_strong"],"open_leg":false,"anchor_role":"","candidate_fact_ids":[],"extras":{"refund_amount":"10.00","expense_amount":"10.00","remaining_before":"10.00","days":"1","within_auto":"true","priority":"1"}},"created_by":"system","anchor_fact_id":"r1","open_leg":false},"nearest":{"kind":"refund_offset","primary_fact_id":"e1","secondary_fact_id":"r1","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"refund_offset.merchant_or_order.v1","confidence":"strong","evidence":{"amount_delta":"0.00","time_delta_seconds":86400,"same_currency":true,"counterparty_similarity":"商户","source_pair":["alipay","icbc_debit"],"rule_id":"refund_offset.merchant_or_order.v1","candidate_count":2,"signals":["refund","merchant","same_account","full_nearest_unique"],"open_leg":false,"anchor_role":"","candidate_fact_ids":["e2","e1"],"extras":{"refund_amount":"10.00","expense_amount":"10.00","remaining_before":"10.00","days":"1","within_auto":"true","priority":"2"}},"created_by":"system","anchor_fact_id":"r1","open_leg":false},"tie":{"kind":"refund_offset","primary_fact_id":"r1","secondary_fact_id":null,"primary_fact_type":"cash","secondary_fact_type":null,"status":"pending_review","rule_id":"refund_offset.merchant_or_order.v1","confidence":"weak","evidence":{"amount_delta":"0.00","time_delta_seconds":86400,"same_currency":true,"counterparty_similarity":"商户","source_pair":["alipay","icbc_debit"],"rule_id":"refund_offset.merchant_or_order.v1","candidate_count":2,"signals":["refund","merchant","same_account"],"open_leg":true,"anchor_role":"refund","candidate_fact_ids":["e1","e2"],"extras":{"refund_amount":"10.00","expense_amount":"10.00","remaining_before":"10.00","days":"1","within_auto":"true","priority":"2"}},"created_by":"system","anchor_fact_id":"r1","open_leg":true},"account_only_multi":null,"zero_candidates":{"kind":"refund_offset","primary_fact_id":"r1","secondary_fact_id":null,"primary_fact_type":"cash","secondary_fact_type":null,"status":"pending_review","rule_id":"refund_offset.merchant_or_order.v1","confidence":"weak","evidence":{"amount_delta":"0","time_delta_seconds":0,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"refund_offset.merchant_or_order.v1","candidate_count":0,"signals":["refund","open_leg_zero_candidate"],"open_leg":true,"anchor_role":"refund","candidate_fact_ids":[],"extras":{"refund_amount":"10.00"}},"created_by":"system","anchor_fact_id":"r1","open_leg":true},"transfer_candidate":null,"other_account":null,"other_currency":null,"too_late":null,"remaining":null,"title_account_conflict":null,"event_collapse":{"kind":"refund_offset","primary_fact_id":"e1","secondary_fact_id":"r1","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"refund_offset.merchant_or_order.v1","confidence":"strong","evidence":{"amount_delta":"0.00","time_delta_seconds":86400,"same_currency":true,"counterparty_similarity":"商户","source_pair":["alipay","icbc_debit"],"rule_id":"refund_offset.merchant_or_order.v1","candidate_count":1,"signals":["refund","merchant","same_account"],"open_leg":false,"anchor_role":"","candidate_fact_ids":["e1"],"extras":{"refund_amount":"10.00","expense_amount":"10.00","remaining_before":"10.00","days":"1","within_auto":"true","priority":"2"}},"created_by":"system","anchor_fact_id":"r1","open_leg":false}}""").jsonObject
    // Fixture merchant names are Chinese or ASCII; production Unicode normalization remains a separate adapter.
    private val rules=CashRefundMatchRules(cashRelationCore,
        {value ->value.orEmpty().replace("_","").replace(" ","").lowercase()},
        ::parseRelationTimestampInstant,{a,b ->(b-a).inWholeNanoseconds},
        PlatformRefundTestAdapters::absolute,{a,b ->CashAmount.parse(absoluteCashDifferenceText(a,b))},
        ::cashAbsolutePlainText,{value ->CashAmount.parse(value.toString())})
    private fun encode(value:Any?)=sourceJson(value,{null},::pythonFloatText)
    private fun seed(id: String = "r1", amount: String = "10.00", counterparty: String = "商户",
        note: String = "", recordId: String = "R1", account: String = "a", currency: String = "CNY",
        accountValue: String = "", occurred: String = "2026-10-02T10:00:00+00:00") = RelationFact(
        id = id, amount = CashAmount.parse(amount), currency = currency, accountId = account, accountName = "现金",
        occurredAt = occurred, counterparty = counterparty, note = note, recordType = "refund",
        counterpartyAccount = accountValue, billSource = "alipay", source = "alipay", recordId = recordId)
    private fun expense(id: String = "e1", amount: String = "-10.00", counterparty: String = "商户",
        note: String = "", recordId: String = "E1", account: String = "a", currency: String = "CNY",
        accountValue: String = "", occurred: String = "2026-10-01T10:00:00+00:00",
        recordType: String = "consumption") = RelationFact(id = id, amount = CashAmount.parse(amount),
        currency = currency, accountId = account, accountName = "现金", occurredAt = occurred,
        counterparty = counterparty, note = note, recordType = recordType, counterpartyAccount = accountValue,
        billSource = "icbc_debit", source = "", recordId = recordId)

    private fun expected(name: String): JsonElement = golden.getValue(name)

    @Test fun refundCandidateMatchingMatchesPython() {
        val base = seed()
        fun assertCase(name: String, proposal: RelationProposal?) {
            val actual = proposal?.toJsonValue(::encode) ?: JsonNull
            assertEquals(expected(name), actual, name)
        }
        assertCase("merchant", rules.evaluate(base, listOf(expense())))
        assertCase("order_lock_late", rules.evaluate(base,
            listOf(expense(recordId = "R1", occurred = "2026-09-12T10:00:00+00:00"))))
        assertCase("title", rules.evaluate(seed(note = "退款-商品", counterparty = "未知"),
            listOf(expense(note = "商品", counterparty = "别的"))))
        assertCase("account", rules.evaluate(seed(accountValue = "6222"), listOf(
            expense(accountValue = "6222", counterparty = "甲", note = "x"),
            expense(id = "e2", counterparty = "乙", note = "y", recordId = "E2"))))
        assertCase("weak", rules.evaluate(seed(counterparty = "退款", note = "", recordId = "R1"),
            listOf(expense(counterparty = "消费", recordId = "E1"))))
        assertCase("nearest", rules.evaluate(base, listOf(expense(),
            expense(id = "e2", recordId = "E2", occurred = "2026-09-30T10:00:00+00:00"))))
        assertCase("tie", rules.evaluate(base, listOf(expense(),
            expense(id = "e2", recordId = "E2"))))
        assertCase("account_only_multi", rules.evaluate(seed(accountValue = "6222"), listOf(
            expense(accountValue = "6222", counterparty = "甲"),
            expense(id = "e2", counterparty = "乙", recordId = "E2", accountValue = "6222"))))
        assertCase("zero_candidates", rules.evaluate(base, emptyList()))
        assertCase("transfer_candidate", rules.evaluate(base,
            listOf(expense(recordType = "transfer_reversal"))))
        assertCase("other_account", rules.evaluate(base, listOf(expense(account = "b"))))
        assertCase("other_currency", rules.evaluate(base, listOf(expense(currency = "USD"))))
        assertCase("too_late", rules.evaluate(base, listOf(expense(occurred = "2026-08-23T10:00:00+00:00"))))
        assertCase("remaining", rules.evaluate(base, listOf(expense(amount = "-5.00"))))
        assertCase("title_account_conflict", rules.evaluate(
            seed(accountValue = "6222", note = "退款-商品", counterparty = "未知"), listOf(
                expense(accountValue = "6222", counterparty = "甲", note = "其他"),
                expense(id = "e2", counterparty = "别的", note = "商品", recordId = "E2"))))
        assertCase("event_collapse", rules.evaluate(base, listOf(
            expense(id = "e1", recordId = "E1"),
            expense(id = "e2", recordId = "E2", occurred = "2026-09-27T10:00:00+00:00")),
            candidateEventIds = mapOf("e1" to "event-1", "e2" to "event-1")))
    }
    @Test fun exactRemainingAndWindowBoundariesStayDistinct() {
        val refund=seed(amount="0.000000000000000002")
        val original=expense(amount="-0.000000000000000003")
        assertNull(rules.evaluate(refund,listOf(original),mapOf("e1" to CashAmount.parse("0.000000000000000001"))))
        val proposal=assertNotNull(rules.evaluate(refund,listOf(original)))
        assertEquals("0.000000000000000001",proposal.evidence.amountDelta)
        val atBoundary=seed(occurred="2026-10-16T10:00:00+00:00")
        assertEquals("accepted",rules.evaluate(atBoundary,listOf(expense()))?.status)
        // The original Double day window rounds one extra nanosecond back to its boundary.
        assertEquals("accepted",rules.evaluate(atBoundary.copy(occurredAt="2026-10-16T10:00:00.000000001+00:00"),listOf(expense()))?.status)
        assertNull(rules.evaluate(atBoundary.copy(occurredAt="2026-10-16T10:00:00.000001+00:00"),listOf(expense())))
        assertEquals(0,CashAmount.parse("-0.00").compareTo(CashAmount.parse("0E+12")))
        assertTrue(CashAmount.parse("-0.000000000000000001") < CashAmount.parse("0"))
        assertTrue(CashAmount.parse("99999999999999999999.999999999999999999") > CashAmount.parse("99999999999999999999.999999999999999998"))
    }

}
