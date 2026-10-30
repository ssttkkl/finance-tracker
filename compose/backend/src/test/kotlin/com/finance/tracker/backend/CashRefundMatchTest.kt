package com.finance.tracker.backend

import java.math.BigDecimal
import kotlin.test.*
import kotlinx.serialization.json.*

class CashRefundMatchTest {
    private val golden: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-refund-match.json")!!
            .bufferedReader().use { it.readText() }).jsonObject.getValue("cases").jsonObject
    }
    private fun seed(id: String = "r1", amount: String = "10.00", counterparty: String = "商户",
        note: String = "", recordId: String = "R1", account: String = "a", currency: String = "CNY",
        accountValue: String = "", occurred: String = "2026-10-02T10:00:00+00:00") = RelationFact(
        id = id, amount = BigDecimal(amount), currency = currency, accountId = account, accountName = "现金",
        occurredAt = occurred, counterparty = counterparty, note = note, recordType = "refund",
        counterpartyAccount = accountValue, billSource = "alipay", source = "alipay", recordId = recordId)
    private fun expense(id: String = "e1", amount: String = "-10.00", counterparty: String = "商户",
        note: String = "", recordId: String = "E1", account: String = "a", currency: String = "CNY",
        accountValue: String = "", occurred: String = "2026-10-01T10:00:00+00:00",
        recordType: String = "consumption") = RelationFact(id = id, amount = BigDecimal(amount),
        currency = currency, accountId = account, accountName = "现金", occurredAt = occurred,
        counterparty = counterparty, note = note, recordType = recordType, counterpartyAccount = accountValue,
        billSource = "icbc_debit", source = "", recordId = recordId)

    private fun expected(name: String): JsonElement = golden.getValue(name)

    @Test fun refundCandidateMatchingMatchesPython() {
        val base = seed()
        fun assertCase(name: String, proposal: RelationProposal?) {
            val actual = proposal?.let { sourceJson(it.json()) } ?: JsonNull
            assertEquals(expected(name), actual, name)
        }
        assertCase("merchant", CashRefundMatch.evaluate(base, listOf(expense())))
        assertCase("order_lock_late", CashRefundMatch.evaluate(base,
            listOf(expense(recordId = "R1", occurred = "2026-09-12T10:00:00+00:00"))))
        assertCase("title", CashRefundMatch.evaluate(seed(note = "退款-商品", counterparty = "未知"),
            listOf(expense(note = "商品", counterparty = "别的"))))
        assertCase("account", CashRefundMatch.evaluate(seed(accountValue = "6222"), listOf(
            expense(accountValue = "6222", counterparty = "甲", note = "x"),
            expense(id = "e2", counterparty = "乙", note = "y", recordId = "E2"))))
        assertCase("weak", CashRefundMatch.evaluate(seed(counterparty = "退款", note = "", recordId = "R1"),
            listOf(expense(counterparty = "消费", recordId = "E1"))))
        assertCase("nearest", CashRefundMatch.evaluate(base, listOf(expense(),
            expense(id = "e2", recordId = "E2", occurred = "2026-09-30T10:00:00+00:00"))))
        assertCase("tie", CashRefundMatch.evaluate(base, listOf(expense(),
            expense(id = "e2", recordId = "E2"))))
        assertCase("account_only_multi", CashRefundMatch.evaluate(seed(accountValue = "6222"), listOf(
            expense(accountValue = "6222", counterparty = "甲"),
            expense(id = "e2", counterparty = "乙", recordId = "E2", accountValue = "6222"))))
        assertCase("zero_candidates", CashRefundMatch.evaluate(base, emptyList()))
        assertCase("transfer_candidate", CashRefundMatch.evaluate(base,
            listOf(expense(recordType = "transfer_reversal"))))
        assertCase("other_account", CashRefundMatch.evaluate(base, listOf(expense(account = "b"))))
        assertCase("other_currency", CashRefundMatch.evaluate(base, listOf(expense(currency = "USD"))))
        assertCase("too_late", CashRefundMatch.evaluate(base, listOf(expense(occurred = "2026-08-23T10:00:00+00:00"))))
        assertCase("remaining", CashRefundMatch.evaluate(base, listOf(expense(amount = "-5.00"))))
        assertCase("title_account_conflict", CashRefundMatch.evaluate(
            seed(accountValue = "6222", note = "退款-商品", counterparty = "未知"), listOf(
                expense(accountValue = "6222", counterparty = "甲", note = "其他"),
                expense(id = "e2", counterparty = "别的", note = "商品", recordId = "E2"))))
        assertCase("event_collapse", CashRefundMatch.evaluate(base, listOf(
            expense(id = "e1", recordId = "E1"),
            expense(id = "e2", recordId = "E2", occurred = "2026-09-27T10:00:00+00:00")),
            candidateEventIds = mapOf("e1" to "event-1", "e2" to "event-1")))
    }
}
