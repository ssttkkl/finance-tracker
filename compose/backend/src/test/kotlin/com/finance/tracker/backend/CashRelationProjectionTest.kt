package com.finance.tracker.backend

import java.math.BigDecimal
import kotlin.test.*
import kotlinx.serialization.json.*

class CashRelationProjectionTest {
    private val golden: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-relation-projection.json")!!
            .bufferedReader().use { it.readText() }).jsonObject
    }
    private fun fact(id: String, amount: String, billSource: String, source: String = "alipay",
        recordType: String = "consumption", deleted: Boolean = false, recordId: String = "R-$id") =
        RelationFact(id = id, amount = BigDecimal(amount), currency = "CNY", accountId = "a", accountName = "现金",
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
        val result = CashRelationProjection.project(facts, relations)
        val balances = result.balances.entries.associate { (key, value) -> "${key.first}|${key.second}" to value.toPlainString() }
        assertEquals(golden.getValue("balances"), sourceJson(balances))
        assertEquals(golden.getValue("expenses"), sourceJson(result.expenses.mapValues { it.value.toPlainString() }))
        assertEquals(golden.getValue("income"), sourceJson(result.income.mapValues { it.value.toPlainString() }))
        assertEquals(golden.getValue("excluded"), sourceJson(result.excludedTransferFactIds.sorted()))
        assertEquals(golden.getValue("groups"), sourceJson(result.mirrorGroups.map { it.sorted() }.sortedBy { it.joinToString() }))
        assertEquals(golden.getValue("net"), sourceJson(result.netExpenseByGroup.mapValues { it.value.toPlainString() }))
    }
}
