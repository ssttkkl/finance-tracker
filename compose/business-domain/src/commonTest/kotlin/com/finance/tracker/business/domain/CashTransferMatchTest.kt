package com.finance.tracker.business.domain

import kotlin.test.*
import kotlinx.serialization.json.*

class CashTransferMatchTest {
    private val golden=Json.parseToJsonElement("""{"accepted":{"kind":"transfer_pair","primary_fact_id":"out","secondary_fact_id":"inc","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"transfer_pair.normalized.same_currency.time10.v1","confidence":"strong","evidence":{"amount_delta":"0.00","time_delta_seconds":5,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"transfer_pair.normalized.same_currency.time10.v1","candidate_count":1,"signals":["opposite_sign","record_subtype","counterparty_account"],"open_leg":false,"anchor_role":"","candidate_fact_ids":["inc"],"extras":{}},"created_by":"system","anchor_fact_id":"out","open_leg":false},"ambiguous":{"kind":"transfer_pair","primary_fact_id":"out","secondary_fact_id":null,"primary_fact_type":"cash","secondary_fact_type":null,"status":"pending_review","rule_id":"transfer_pair.normalized.same_currency.time10.v1","confidence":"weak","evidence":{"amount_delta":"0.00","time_delta_seconds":5,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"transfer_pair.normalized.same_currency.time10.v1","candidate_count":2,"signals":["opposite_sign","record_subtype","counterparty_account"],"open_leg":true,"anchor_role":"out","candidate_fact_ids":["inc","inc2"],"extras":{}},"created_by":"system","anchor_fact_id":"out","open_leg":true},"same_account":null,"late":null,"credit":{"kind":"transfer_pair","primary_fact_id":"out","secondary_fact_id":"inc","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"transfer_pair.normalized.same_currency.time10.v1","confidence":"strong","evidence":{"amount_delta":"0.00","time_delta_seconds":5,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"transfer_pair.normalized.same_currency.time10.v1","candidate_count":1,"signals":["opposite_sign","record_subtype","counterparty_account"],"open_leg":false,"anchor_role":"","candidate_fact_ids":["inc"],"extras":{}},"created_by":"system","anchor_fact_id":"out","open_leg":false},"fx":{"kind":"transfer_pair","primary_fact_id":"fx_out","secondary_fact_id":"fx_in","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"transfer_pair.currency_exchange.time60.v1","confidence":"strong","evidence":{"amount_delta":"0","time_delta_seconds":30,"same_currency":false,"counterparty_similarity":"","source_pair":["",""],"rule_id":"transfer_pair.currency_exchange.time60.v1","candidate_count":1,"signals":["opposite_sign","record_subtype","counterparty_account"],"open_leg":false,"anchor_role":"","candidate_fact_ids":["fx_in"],"extras":{}},"created_by":"system","anchor_fact_id":"fx_out","open_leg":false},"fx_same_currency":null,"targeted":[{"kind":"transfer_pair","primary_fact_id":"out","secondary_fact_id":"inc","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"transfer_pair.cross_border_remittance.v1","confidence":"strong","evidence":{"amount_delta":"0.00","time_delta_seconds":5,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"transfer_pair.cross_border_remittance.v1","candidate_count":1,"signals":["opposite_sign","record_subtype","counterparty_account"],"open_leg":false,"anchor_role":"","candidate_fact_ids":["inc"],"extras":{}},"created_by":"system","anchor_fact_id":"out","open_leg":false}],"targeted_no_alias":[],"phase_c":[{"kind":"transfer_pair","primary_fact_id":"fx_out","secondary_fact_id":"fx_in","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"transfer_pair.currency_exchange.time60.v1","confidence":"strong","evidence":{"amount_delta":"0","time_delta_seconds":30,"same_currency":false,"counterparty_similarity":"","source_pair":["",""],"rule_id":"transfer_pair.currency_exchange.time60.v1","candidate_count":1,"signals":["opposite_sign","record_subtype","counterparty_account"],"open_leg":false,"anchor_role":"","candidate_fact_ids":["fx_in"],"extras":{}},"created_by":"system","anchor_fact_id":"fx_out","open_leg":false},{"kind":"transfer_pair","primary_fact_id":"out","secondary_fact_id":"inc","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"transfer_pair.normalized.same_currency.time10.v1","confidence":"strong","evidence":{"amount_delta":"0.00","time_delta_seconds":5,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"transfer_pair.normalized.same_currency.time10.v1","candidate_count":1,"signals":["opposite_sign","record_subtype","counterparty_account"],"open_leg":false,"anchor_role":"","candidate_fact_ids":["inc"],"extras":{}},"created_by":"system","anchor_fact_id":"out","open_leg":false}],"phase_c_seed":[{"kind":"transfer_pair","primary_fact_id":"out","secondary_fact_id":"inc","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"transfer_pair.normalized.same_currency.time10.v1","confidence":"strong","evidence":{"amount_delta":"0.00","time_delta_seconds":5,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"transfer_pair.normalized.same_currency.time10.v1","candidate_count":1,"signals":["opposite_sign","record_subtype","counterparty_account"],"open_leg":false,"anchor_role":"","candidate_fact_ids":["inc"],"extras":{}},"created_by":"system","anchor_fact_id":"out","open_leg":false}],"counterparty_invalid":{"kind":"transfer_pair","primary_fact_id":"out","secondary_fact_id":"inc","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"transfer_pair.normalized.same_currency.time10.v1","confidence":"strong","evidence":{"amount_delta":"0.00","time_delta_seconds":5,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"transfer_pair.normalized.same_currency.time10.v1","candidate_count":1,"signals":["opposite_sign","record_subtype","counterparty_account"],"open_leg":false,"anchor_role":"","candidate_fact_ids":["inc"],"extras":{}},"created_by":"system","anchor_fact_id":"out","open_leg":false}}""").jsonObject
    private fun encode(value:Any?)=sourceJson(value,{null},::pythonFloatText)
    private fun fact(id: String, amount: String = "-10.00", currency: String = "CNY",
        account: String = "a", recordType: String = "transfer_out", subtype: String = "ordinary_transfer",
        occurred: String = "2026-10-01T10:00:00+00:00", recordId: String = "T-$id",
        accountValue: String = "", attrs: List<String> = emptyList()) = RelationFact(
        id = id, amount = CashAmount.parse(amount), currency = currency, accountId = account, accountName = "现金",
        occurredAt = occurred, counterparty = "", note = "", recordType = recordType, recordSubtype = subtype,
        billSource = "ccb_debit", source = "", recordId = recordId,
        counterpartyAccount = accountValue, counterpartyAccountAttrs = attrs)
    private fun proposal(value: RelationProposal?): JsonElement = value?.toJsonValue(::encode) ?: JsonNull
    private fun proposals(values: List<RelationProposal>): JsonElement = JsonArray(values.map { it.toJsonValue(::encode) })

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
        assertEquals(golden.getValue("accepted"), proposal(cashTransferMatch.evaluateTransferPair(out, listOf(inc), aliases)))
        assertEquals(golden.getValue("ambiguous"), proposal(cashTransferMatch.evaluateTransferPair(out, listOf(inc,
            inc.copy(id = "inc2", recordId = "I3")))))
        assertEquals(golden.getValue("same_account"), proposal(cashTransferMatch.evaluateTransferPair(out, listOf(same))))
        assertEquals(golden.getValue("late"), proposal(cashTransferMatch.evaluateTransferPair(out, listOf(late))))
        assertEquals(golden.getValue("credit"), proposal(cashTransferMatch.evaluateTransferPair(
            out.copy(recordSubtype = "credit_repayment"), listOf(inc.copy(recordSubtype = "credit_repayment")))))
        assertEquals(golden.getValue("fx"), proposal(cashTransferMatch.matchPersonalFxExchange(fxOut, listOf(fxIn))))
        assertEquals(golden.getValue("fx_same_currency"), proposal(cashTransferMatch.matchPersonalFxExchange(
            fxOut, listOf(fxIn.copy(currency = "CNY")))))
        val aliasOut = out.copy(counterpartyAccount = "622212345678", counterpartyAccountAttrs = listOf("full"))
        assertEquals(golden.getValue("targeted"), proposals(cashTransferMatch.matchNormalizedSubtypeTransfers(
            listOf(aliasOut, inc), accountIdentifiers = aliases)))
        assertEquals(golden.getValue("targeted_no_alias"), proposals(cashTransferMatch.matchNormalizedSubtypeTransfers(
            listOf(out, inc))))
        assertEquals(golden.getValue("phase_c"), proposals(cashTransferMatch.matchTransferPairsPhaseC(
            listOf(out, inc, fxOut, fxIn), accountIdentifiers = aliases)))
        assertEquals(golden.getValue("phase_c_seed"), proposals(cashTransferMatch.matchTransferPairsPhaseC(
            listOf(out, inc, fxOut, fxIn), seedIds = listOf("inc"), accountIdentifiers = aliases)))
        assertEquals(golden.getValue("counterparty_invalid"), proposal(cashTransferMatch.evaluateTransferPair(
            out.copy(counterpartyAccount = "1234", counterpartyAccountAttrs = listOf("full")), listOf(inc), aliases)))
    }

    @Test fun exactMagnitudeScaleAndTimeBoundariesArePreserved() {
        val out=fact("out", "-99999999999999999999.123456789012345678")
        val inc=fact("inc", "99999999999999999999.123456789012345678",account="b",recordType="transfer_in")
        assertEquals("0.000000000000000000",cashTransferMatch.evaluateTransferPair(out,listOf(inc))!!.evidence.amountDelta)
        assertNull(cashTransferMatch.evaluateTransferPair(out,listOf(inc.copy(amount=CashAmount.parse("99999999999999999999.123456789012345677")))))
        val simple=fact("out","-1.0")
        val incoming=fact("in","1.0000",account="b",recordType="transfer_in",occurred="2026-10-01T10:00:10.999999Z")
        assertEquals("accepted",cashTransferMatch.evaluateTransferPair(simple,listOf(incoming))!!.status)
        assertEquals("0.0000",cashTransferMatch.evaluateTransferPair(simple,listOf(incoming))!!.evidence.amountDelta)
        assertEquals("pending_review",cashTransferMatch.evaluateTransferPair(simple,listOf(incoming.copy(occurredAt="2026-10-01T10:00:11Z")))!!.status)
        assertNull(cashTransferMatch.evaluateTransferPair(simple,listOf(incoming.copy(occurredAt="2026-10-01T10:05:01Z"))))
    }
    @Test fun aliasAttributesAndAmbiguousTargetStayExplicit() {
        val aliases=mapOf("622212345678" to listOf("b"),"622298765678" to listOf("c"))
        assertEquals(setOf("b","c"),cashTransferMatch.accountTargets("6222****5678",listOf("masked"),aliases,null))
        assertTrue(cashTransferMatch.accountTargets("1234",listOf("full"),aliases,null).isEmpty())
        assertEquals(setOf("b"),cashTransferMatch.accountTargets("6222-1234 (5678)",listOf("full"),aliases,null))
        assertTrue(cashTransferMatch.accountTargets("6222\u300012345678",listOf("full"),aliases,null).isEmpty())
        val out=fact("out","-1",accountValue="5678",attrs=listOf("tail"))
        val inc=fact("in","1",account="b",recordType="transfer_in")
        assertEquals("pending_review",cashTransferMatch.evaluateTransferPair(out,listOf(inc),cardTails=mapOf("5678" to listOf("b","c")))!!.status)
    }

    @Test fun targetedTransfersKeepWindowsAndSeedOwnershipWithAnIndex() {
        val aliases=mapOf("622212345678" to listOf("b"))
        val out=fact("out","-100",accountValue="622212345678",attrs=listOf("full"),subtype="cross_border_remittance")
        val inc=fact("inc","12.5",currency="USD",account="b",recordType="transfer_in",occurred="2026-10-08T10:00:00Z")
        for(index in listOf(null,cashFactCandidateIndex(listOf(out,inc)))) {
            val result=cashTransferMatch.matchNormalizedSubtypeTransfers(listOf(out,inc),listOf("inc"),index,aliases)
            assertEquals(RELATION_RULE_CROSS_BORDER_REMITTANCE_V1,result.single().ruleId)
            assertEquals("0",result.single().evidence.amountDelta)
            assertTrue(cashTransferMatch.matchNormalizedSubtypeTransfers(listOf(out,inc),listOf("unrelated"),index,aliases).isEmpty())
        }
        assertTrue(cashTransferMatch.matchNormalizedSubtypeTransfers(listOf(out,inc.copy(occurredAt="2026-10-08T10:00:01Z")),accountIdentifiers=aliases).isEmpty())
        val internalAliases=mapOf("622212345678" to listOf("a"))
        assertEquals(RELATION_RULE_INTERNAL_ACCOUNT_TRANSFER_V1,cashTransferMatch.matchNormalizedSubtypeTransfers(
            listOf(out,inc.copy(accountId="a")),accountIdentifiers=internalAliases).single().ruleId)
    }
    @Test fun candidateReservationAndEmptyInputKeepExistingPhaseCSemantics() {
        val out=fact("out","-1",occurred="2026-10-01T10:00:01Z")
        val early=out.copy(id="early",recordId="earlier",occurredAt="2026-10-01T10:00:00Z")
        val incoming=fact("inc","1",account="b",recordType="transfer_in",occurred="2026-10-01T10:00:02Z")
        val result=cashTransferMatch.matchTransferPairsPhaseC(listOf(out,incoming,early),seedIds=listOf("inc"))
        assertEquals(listOf("early"),result.map {it.primaryFactId})
        assertEquals("inc",result.single().secondaryFactId)
        assertFailsWith<NoSuchElementException> {cashTransferMatch.matchTransferPairsPhaseC(emptyList())}
    }
}
