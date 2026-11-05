package com.finance.tracker.backend

import java.math.BigDecimal
import kotlin.test.*
import kotlinx.serialization.json.*

class CashFactCandidateIndexTest {
    private val golden: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-fact-candidates.json")!!
            .bufferedReader().use { it.readText() }).jsonObject
    }
    private fun fact(id: String, amount: String = "-10.00", currency: String = "CNY",
        recordType: String = "consumption", subtype: String = "not_applicable",
        billSource: String = "alipay", source: String = "alipay", account: String = "a",
        occurred: String = "2026-10-01T10:00:00+00:00", note: String = "", deleted: Boolean = false) =
        RelationFact(id = id, amount = BigDecimal(amount), currency = currency, accountId = account,
            accountName = "现金", occurredAt = occurred, counterparty = "商户", note = note,
            recordType = recordType, recordSubtype = subtype, billSource = billSource, source = source,
            recordId = "A-$id", deleted = deleted)
    private fun ids(items: List<RelationFact>): List<String> = items.map { it.id }

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
        val index = CashFactCandidateIndex(facts) { CashRelationCore.sourceGroup(it) }
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
}
