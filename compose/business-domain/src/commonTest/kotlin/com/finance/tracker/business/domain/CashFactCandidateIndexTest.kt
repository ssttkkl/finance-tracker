package com.finance.tracker.business.domain

import kotlin.test.*
import kotlinx.serialization.json.*

class CashFactCandidateIndexTest {
    private val golden=Json.parseToJsonElement("""{"mirror":[["b1"],["m1","exp1","exp_other","p2p_exp","exp2"],["br"],["mr","p2p"]],"transfer_in":["in1","in2"],"transfer_pair":[["in1"],[],["fx_in"]],"refund":[["m1","b1","exp1","p2p_exp","exp2"],["ref"],["m1","b1","exp1","p2p_exp"]],"by_id":["b1","br","exp1","exp2","exp_other","fx_in","fx_out","in1","in2","late","m1","mr","out","p2p","p2p_exp","ref"]}""").jsonObject
    private fun sourceJson(value:Any?)=sourceJson(value,{null},::pythonFloatText)
    private fun fact(id: String, amount: String = "-10.00", currency: String = "CNY",
        recordType: String = "consumption", subtype: String = "not_applicable",
        billSource: String = "alipay", source: String = "alipay", account: String = "a",
        occurred: String = "2026-10-01T10:00:00+00:00", note: String = "", deleted: Boolean = false) =
        RelationFact(id = id, amount = CashAmount.parse(amount), currency = currency, accountId = account,
            accountName = "现金", occurredAt = occurred, counterparty = "商户", note = note,
            recordType = recordType, recordSubtype = subtype, billSource = billSource, source = source,
            recordId = "A-$id", deleted = deleted)
    private fun ids(items: List<RelationFact<CashAmount>>): List<String> = items.map { it.id }

    @Test fun candidateIndexBucketsMatchPython() {
        val facts = listOf(
            fact("m1"),
            fact("b1", billSource = "icbc_debit", source = ""),
            fact("mr", "10.00", recordType = "refund"),
            fact("br", "10.00", recordType = "refund", billSource = "icbc_debit", source = ""),
            fact("out", "-50.00", recordType = "transfer_out", subtype = "ordinary_transfer", billSource = "ccb_debit", source = ""),
            fact("in1", "50.00", recordType = "transfer_in", subtype = "ordinary_transfer", billSource = "icbc_debit", source = "",
                occurred = "2026-10-01T10:01:00+00:00"),
            fact("in2", "50.00", recordType = "transfer_in", subtype = "credit_repayment", billSource = "icbc_debit", source = ""),
            fact("fx_out", "-100.00", recordType = "fx_out", subtype = "currency_exchange", billSource = "ccb_debit", source = ""),
            fact("fx_in", "100.00", currency = "USD", recordType = "fx_in", subtype = "currency_exchange",
                billSource = "icbc_debit", source = ""),
            fact("ref", "10.00", recordType = "refund", occurred = "2026-10-03T10:00:00+00:00"),
            fact("exp1", occurred = "2026-10-01T10:00:00+00:00"),
            fact("exp2", occurred = "2026-10-02T10:00:00+00:00"),
            fact("exp_other", account = "b"),
            fact("late", occurred = "2026-08-01T10:00:00+00:00"),
            fact("p2p", "10.00", recordType = "refund", note = "微信红包"),
            fact("p2p_exp", note = "微信红包"),
            fact("deleted", deleted = true))
        val index = cashFactCandidateIndex(facts) { cashRelationCore.sourceGroup(it) }
        val byId = facts.associateBy { it.id }
        assertEquals(golden.getValue("mirror"), sourceJson(listOf("m1", "b1", "mr", "br").map {
            ids(index.mirrorCandidates(byId.getValue(it)))
        }))
        assertEquals(golden.getValue("transfer_in"), sourceJson(ids(index.transferInCandidates(byId.getValue("out"), 1))))
        assertEquals(golden.getValue("transfer_pair"), sourceJson(listOf(
            ids(index.transferPairCandidates(byId.getValue("out"), 300)),
            ids(index.transferPairCandidates(byId.getValue("out"), 30)),
            ids(index.transferPairCandidates(byId.getValue("fx_out"), 300)))))
        assertEquals(golden.getValue("refund"), sourceJson(listOf(
            ids(index.refundCandidates(byId.getValue("ref"))),
            ids(index.refundCandidates(byId.getValue("exp2"))),
            ids(index.refundCandidates(byId.getValue("p2p"))))))
        assertEquals(golden.getValue("by_id"), sourceJson(index.byId.keys.sorted()))
    }
    @Test fun preciseMagnitudeAndUtcDayBucketsDoNotDropBoundaryMirrors() {
        val seed=fact("seed",amount="-99999999999999999999.123456789012345678",occurred="0001-01-01T00:00:00Z")
        val same=seed.copy(id="same",billSource="bank",source="bank")
        val next=same.copy(id="next",occurredAt="0001-01-02T00:00:00Z")
        val different=same.copy(id="different",amount=CashAmount.parse("-99999999999999999999.123456789012345677"))
        val deleted=same.copy(id="deleted",deleted=true)
        val index=cashFactCandidateIndex(listOf(seed,same,next,different,deleted)) {cashRelationCore.sourceGroup(it)}
        assertEquals(listOf("same","next"),ids(index.mirrorCandidates(seed)))
        val latest=seed.copy(id="latest",occurredAt="9999-12-31T23:59:59Z",amount=CashAmount.parse("-1.2300"))
        val bank=latest.copy(id="bank",billSource="bank",source="bank",amount=CashAmount.parse("-1.23"))
        assertEquals(listOf("bank"),ids(cashFactCandidateIndex(listOf(latest,bank)) {cashRelationCore.sourceGroup(it)}.mirrorCandidates(latest)))
    }
    @Test fun transferPaddingIsExclusiveAndInvalidTimesKeepTheOriginalFallbackOrder() {
        val seed=fact("out",amount="-10",recordType="transfer_out",subtype="ordinary_transfer",occurred="2026-01-01T00:00:00Z")
        val inside=seed.copy(id="inside",amount=CashAmount.parse("10"),recordType="transfer_in",occurredAt="2026-01-01T00:05:00.999999Z")
        val boundary=inside.copy(id="boundary",occurredAt="2026-01-01T00:05:01Z")
        val invalid=inside.copy(id="invalid",occurredAt="bad")
        val wrong=inside.copy(id="wrong",recordSubtype="credit_repayment")
        val index=cashFactCandidateIndex(listOf(seed,boundary,invalid,inside,wrong))
        assertEquals(listOf("inside","invalid"),ids(index.transferPairCandidates(seed,300)))
        assertEquals(listOf("boundary","invalid","inside"),ids(index.transferPairCandidates(seed.copy(occurredAt="bad"),300)))
        assertFalse("invalid" in index.byId)
        assertTrue(index.mirrorCandidates(seed.copy(occurredAt="bad")).isEmpty())
    }
    @Test fun refundLookupKeepsSixteenDayIndexPaddingAndAccountCurrencyScope() {
        val refund=fact("refund",amount="10",recordType="refund",occurred="1969-01-17T00:00:00Z")
        val expense=fact("expense",occurred="1969-01-01T00:00:00Z")
        val early=expense.copy(id="early",occurredAt="1968-12-31T23:59:59Z")
        val other=expense.copy(id="other",accountId="other")
        val currency=expense.copy(id="currency",currency="USD")
        val index=cashFactCandidateIndex(listOf(refund,expense,early,other,currency))
        assertEquals(listOf("expense"),ids(index.refundCandidates(refund)))
        assertEquals(listOf("refund"),ids(index.refundCandidates(expense)))
        assertTrue(index.refundCandidates(refund) {true}.isEmpty())
        assertEquals(15,REFUND_CANDIDATE_DAYS)
    }

    @Test fun yearLimitRefundRangeKeepsTheExistingIsoStringComparison() {
        val expense=fact("expense",occurred="9999-12-31T00:00:00Z")
        val refund=expense.copy(id="refund",amount=CashAmount.parse("10"),recordType="refund")
        val index=cashFactCandidateIndex(listOf(expense,refund))
        assertTrue(index.refundCandidates(expense).isEmpty())
        assertEquals(listOf("expense"),ids(index.refundCandidates(refund)))
        assertEquals("0000-12-16",shiftCashCandidateDay("0001-01-01",-16))
        assertEquals("+10000-01-16",shiftCashCandidateDay("9999-12-31",16))
    }

}
