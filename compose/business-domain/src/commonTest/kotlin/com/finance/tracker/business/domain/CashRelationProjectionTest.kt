package com.finance.tracker.business.domain

import kotlin.test.*
import kotlinx.serialization.json.*

class CashRelationProjectionTest {
    private val golden=Json.parseToJsonElement("""{"balances":{"现金|CNY":"-77.00"},"expenses":{"CNY":"87.00"},"income":{"CNY":"20.00"},"excluded":["t1","t2"],"groups":[["e1"],["i1"],["m1","m2"],["o1"],["r1"],["t1"],["t2"]],"net":{"m1":"10.00","e1":"70.00","o1":"7.00"}}""").jsonObject
    private fun encode(value:Any?)=sourceJson(value,{null},::pythonFloatText)
    private fun fact(id: String, amount: String, billSource: String, source: String = "alipay",
        recordType: String = "consumption", deleted: Boolean = false, recordId: String = "R-$id") =
        RelationFact(id = id, amount = CashAmount.parse(amount), currency = "CNY", accountId = "a", accountName = "现金",
            occurredAt = "2026-10-01T10:00:00+00:00", counterparty = "商户", note = "", recordType = recordType,
            billSource = billSource, source = source, recordId = recordId, deleted = deleted)

    @Test fun projectionMatchesPython() {
        val facts = listOf(
            fact("m1", "-10.00", "alipay"),
            fact("m2", "-10.00", "icbc_debit", ""),
            fact("t1", "-50.00", "ccb_debit", recordType = "transfer_out"),
            fact("t2", "50.00", "icbc_debit", recordType = "transfer_in"),
            fact("e1", "-100.00", "wechat"),
            fact("r1", "30.00", "wechat", recordType = "refund"),
            fact("i1", "20.00", "wechat", recordType = "income"),
            fact("d1", "99.00", "wechat", recordType = "income", deleted = true),
            fact("o1", "-7.00", "wechat"))
        val relations = listOf<Map<String, Any?>>(
            mapOf("kind" to "payment_mirror", "status" to "accepted", "primary_fact_id" to "m1", "secondary_fact_id" to "m2"),
            mapOf("kind" to "transfer_pair", "status" to "accepted", "primary_fact_id" to "t1", "secondary_fact_id" to "t2"),
            mapOf("kind" to "refund_offset", "status" to "accepted", "primary_fact_id" to "e1", "secondary_fact_id" to "r1"),
            mapOf("kind" to "refund_offset", "status" to "accepted", "primary_fact_id" to "o1", "secondary_fact_id" to null),
            mapOf("kind" to "payment_mirror", "status" to "rejected", "primary_fact_id" to "o1", "secondary_fact_id" to "i1"))
        val result = projectCashRelationFacts(facts, relations)
        val balances = result.balances.entries.associate { (key, value) -> "${key.first}|${key.second}" to value }
        assertEquals(golden.getValue("balances"), encode(balances))
        assertEquals(golden.getValue("expenses"), encode(result.expenses.mapValues { it.value }))
        assertEquals(golden.getValue("income"), encode(result.income.mapValues { it.value }))
        assertEquals(golden.getValue("excluded"), encode(result.excludedTransferFactIds.sorted()))
        assertEquals(golden.getValue("groups"), encode(result.mirrorGroups.map { it.sorted() }.sortedBy { it.joinToString() }))
        assertEquals(golden.getValue("net"), encode(result.netExpenseByGroup.mapValues { it.value }))
    }
    @Test fun totalsCanExceedTheSingleFactRangeWithoutLosingPrecision() {
        val amount="99999999999999999999.999999999999999999"
        val result=projectCashRelationFacts(listOf(fact("a",amount,"wechat",recordType="income"),
            fact("b",amount,"wechat",recordType="income")),emptyList())
        assertEquals("199999999999999999999.999999999999999998",result.income["CNY"])
        assertEquals(result.income["CNY"],result.balances["现金" to "CNY"])
        val tiny=projectCashRelationFacts(listOf(fact("e","-0.000000000000000003","wechat"),
            fact("r","0.000000000000000002","wechat",recordType="refund")),listOf(mapOf(
                "kind" to "refund_offset","status" to "accepted","primary_fact_id" to "e","secondary_fact_id" to "r")))
        assertEquals("0.000000000000000001",tiny.expenses["CNY"])
    }

}
