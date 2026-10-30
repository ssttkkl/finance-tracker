package com.finance.tracker.backend

import java.math.BigDecimal
import kotlin.test.*
import kotlinx.serialization.json.*

class CashMirrorMatchTest {
    private val golden: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-mirror-match.json")!!
            .bufferedReader().use { it.readText() }).jsonObject.getValue("cases").jsonObject
    }
    private fun fact(id: String, amount: String = "-10.00", currency: String = "CNY", account: String = "a",
        billSource: String = "alipay", source: String = "alipay", occurred: String = "2026-10-01T10:00:00+00:00",
        counterparty: String = "商户", note: String = "", recordType: String = "consumption",
        rawPayload: Map<String, Any?>? = null, paymentMethod: String = "余额") = RelationFact(
        id = id, amount = BigDecimal(amount), currency = currency, accountId = account, accountName = "账户",
        occurredAt = occurred, counterparty = counterparty, note = note, recordType = recordType,
        billSource = billSource, source = source, recordId = "R-$id", rawPayload = rawPayload,
        paymentMethod = paymentMethod)
    private fun proposal(value: RelationProposal?): JsonElement = value?.let { sourceJson(it.json()) } ?: JsonNull
    private fun proposals(values: List<RelationProposal>): JsonElement = sourceJson(values.map { it.json() })

    @Test fun mirrorMatchingMatchesPython() {
        val platform = fact("p1")
        val bank = fact("b1", billSource = "icbc_debit", source = "", occurred = "2026-10-01T10:00:05+00:00")
        val late = fact("b2", billSource = "icbc_debit", source = "", occurred = "2026-10-01T10:10:00+00:00")
        val dateOnly = fact("b3", billSource = "icbc_debit", source = "", occurred = "2026-10-01T16:00:00+00:00",
            rawPayload = mapOf("date" to "2026-10-01"))
        val noText = fact("b4", billSource = "icbc_debit", source = "", counterparty = "",
            occurred = "2026-10-01T16:00:00+00:00", rawPayload = mapOf("date" to "2026-10-01"))
        val diffAmount = fact("b5", amount = "-11.00", billSource = "icbc_debit", source = "",
            occurred = "2026-10-01T10:00:30+00:00")
        assertEquals(golden.getValue("strong"), proposal(CashMirrorMatch.evaluate(platform, listOf(bank))))
        assertEquals(golden.getValue("date_only"), proposal(CashMirrorMatch.evaluate(platform, listOf(dateOnly))))
        assertEquals(golden.getValue("date_only_no_identity"), proposal(CashMirrorMatch.evaluate(platform, listOf(noText))))
        assertEquals(golden.getValue("short_text"), proposal(CashMirrorMatch.evaluate(platform,
            listOf(bank.copy(id = "b6", occurredAt = "2026-10-01T10:00:30+00:00")))))
        assertEquals(golden.getValue("late_text"), proposal(CashMirrorMatch.evaluate(platform, listOf(late))))
        assertEquals(golden.getValue("amount_delta"), proposal(CashMirrorMatch.evaluate(platform, listOf(diffAmount))))
        assertEquals(golden.getValue("cross_account"), proposal(CashMirrorMatch.evaluate(platform,
            listOf(bank.copy(accountId = "b")))))
        assertEquals(golden.getValue("same_group"), proposal(CashMirrorMatch.evaluate(platform,
            listOf(bank.copy(billSource = "alipay", source = "alipay")))))
        assertEquals(golden.getValue("multi_strong"), proposal(CashMirrorMatch.evaluate(platform,
            listOf(bank, bank.copy(id = "b7")))))
        assertEquals(golden.getValue("refund_dual"), proposal(CashMirrorMatch.evaluate(
            fact("pr", amount = "10.00", recordType = "refund", occurred = "2026-10-04T10:00:00+00:00"),
            listOf(fact("br", amount = "10.00", recordType = "refund", billSource = "icbc_debit", source = "",
                occurred = "2026-10-04T10:00:00+00:00", rawPayload = mapOf("date" to "2026-10-04"))))))
        val grouped = listOf(
            fact("g1"), fact("g2"),
            fact("g3", billSource = "icbc_debit", source = "", occurred = "2026-10-01T10:00:05+00:00"),
            fact("g4", billSource = "icbc_debit", source = "", occurred = "2026-10-01T10:00:50+00:00"))
        assertEquals(golden.getValue("greedy"), proposals(CashMirrorMatch.matchGreedy(grouped)))
        assertEquals(golden.getValue("greedy_seed"), proposals(CashMirrorMatch.matchGreedy(grouped, seedIds = listOf("g3"))))
        assertEquals(golden.getValue("greedy_unique"), proposals(CashMirrorMatch.matchGreedy(grouped, requireUniqueMirrors = true)))
        assertEquals(golden.getValue("greedy_blocked"), proposals(CashMirrorMatch.matchGreedy(grouped,
            blockedPairs = setOf("g1" to "g3"))))
    }
}
