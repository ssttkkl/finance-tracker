package com.finance.tracker.backend

import java.math.BigDecimal
import kotlin.test.*
import kotlinx.serialization.json.*

class CashTransferMatchTest {
    private val golden: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-transfer-match.json")!!
            .bufferedReader().use { it.readText() }).jsonObject.getValue("cases").jsonObject
    }
    private fun fact(id: String, amount: String = "-10.00", currency: String = "CNY",
        account: String = "a", recordType: String = "transfer_out", subtype: String = "ordinary_transfer",
        occurred: String = "2026-10-01T10:00:00+00:00", recordId: String = "T-$id",
        accountValue: String = "", attrs: List<String> = emptyList()) = RelationFact(
        id = id, amount = BigDecimal(amount), currency = currency, accountId = account, accountName = "现金",
        occurredAt = occurred, counterparty = "", note = "", recordType = recordType, recordSubtype = subtype,
        billSource = "ccb_debit", source = "", recordId = recordId,
        counterpartyAccount = accountValue, counterpartyAccountAttrs = attrs)
    private fun proposal(value: RelationProposal?): JsonElement = value?.let { sourceJson(it.json()) } ?: JsonNull
    private fun proposals(values: List<RelationProposal>): JsonElement = sourceJson(values.map { it.json() })

    @Test fun transferMatchingMatchesPython() {
        val aliases = mapOf("622212345678" to listOf("b"))
        val out = fact("out", "-100.00", recordId = "O1")
        val inc = fact("inc", "100.00", account = "b", recordType = "transfer_in", recordId = "I1",
            occurred = "2026-10-01T10:00:05+00:00")
        val late = fact("late", "100.00", account = "b", recordType = "transfer_in", recordId = "I2",
            occurred = "2026-10-01T10:06:40+00:00")
        val same = fact("same", "100.00", account = "a", recordType = "transfer_in", recordId = "I3",
            occurred = "2026-10-01T10:00:05+00:00")
        val fxOut = fact("fx_out", "-100.00", recordType = "fx_out", subtype = "currency_exchange", recordId = "F1")
        val fxIn = fact("fx_in", "100.00", currency = "USD", account = "b", recordType = "fx_in",
            subtype = "currency_exchange", recordId = "F2", occurred = "2026-10-01T10:00:30+00:00")
        assertEquals(golden.getValue("accepted"), proposal(CashTransferMatch.evaluateTransferPair(out, listOf(inc), aliases)))
        assertEquals(golden.getValue("ambiguous"), proposal(CashTransferMatch.evaluateTransferPair(out, listOf(inc,
            inc.copy(id = "inc2", recordId = "I3")))))
        assertEquals(golden.getValue("same_account"), proposal(CashTransferMatch.evaluateTransferPair(out, listOf(same))))
        assertEquals(golden.getValue("late"), proposal(CashTransferMatch.evaluateTransferPair(out, listOf(late))))
        assertEquals(golden.getValue("credit"), proposal(CashTransferMatch.evaluateTransferPair(
            out.copy(recordSubtype = "credit_repayment"), listOf(inc.copy(recordSubtype = "credit_repayment")))))
        assertEquals(golden.getValue("fx"), proposal(CashTransferMatch.matchPersonalFxExchange(fxOut, listOf(fxIn))))
        assertEquals(golden.getValue("fx_same_currency"), proposal(CashTransferMatch.matchPersonalFxExchange(
            fxOut, listOf(fxIn.copy(currency = "CNY")))))
        val aliasOut = out.copy(counterpartyAccount = "622212345678", counterpartyAccountAttrs = listOf("full"))
        assertEquals(golden.getValue("targeted"), proposals(CashTransferMatch.matchNormalizedSubtypeTransfers(
            listOf(aliasOut, inc), accountIdentifiers = aliases)))
        assertEquals(golden.getValue("targeted_no_alias"), proposals(CashTransferMatch.matchNormalizedSubtypeTransfers(
            listOf(out, inc))))
        assertEquals(golden.getValue("phase_c"), proposals(CashTransferMatch.matchTransferPairsPhaseC(
            listOf(out, inc, fxOut, fxIn), accountIdentifiers = aliases)))
        assertEquals(golden.getValue("phase_c_seed"), proposals(CashTransferMatch.matchTransferPairsPhaseC(
            listOf(out, inc, fxOut, fxIn), seedIds = listOf("inc"), accountIdentifiers = aliases)))
        assertEquals(golden.getValue("counterparty_invalid"), proposal(CashTransferMatch.evaluateTransferPair(
            out.copy(counterpartyAccount = "1234", counterpartyAccountAttrs = listOf("full")), listOf(inc), aliases)))
    }
}
