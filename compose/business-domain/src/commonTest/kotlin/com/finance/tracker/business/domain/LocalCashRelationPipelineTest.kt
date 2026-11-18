package com.finance.tracker.business.domain

import kotlin.test.*

class LocalCashRelationPipelineTest {
    private fun amount(text: String) = LocalCashRelationArithmetic.amount(text)
    private fun fact(id: String, text: String, type: String = "consumption", source: String = "icbc_debit",
        merchant: String = "商户", at: String = "2026-10-01T10:00:00Z") =
        RelationFact(id, amount(text), "CNY", "a", occurredAt = at, counterparty = merchant,
            recordType = type, billSource = source, recordId = id)

    @Test fun fullPipelineUsesExactMerchantEvidenceAndKeepsRemainingScale() {
        val cases = listOf("AΣ𐀀A" to "aς𐀀a", "İAΣ𐐀A" to "i\u0307aσ𐐨a", "ΟΣ_!" to "ος")
        for ((original, normalized) in cases) {
            val expense = fact("e", "-10.0000", merchant = original)
            val refund = fact("r", "3.00", "refund", merchant = normalized, at = "2026-10-02T10:00:00Z")
            val context = MatchContext<LedgerArithmeticValue>()
            val proposals = LocalCashRelationPipeline.rules.run(listOf(expense, refund), context, seedIds = listOf("r"))
            val result = proposals.single()
            assertEquals("accepted", result.status)
            assertEquals("e", result.primaryFactId)
            assertEquals("r", result.secondaryFactId)
            assertTrue("merchant" in result.evidence.signals)
            assertEquals("7.0000", result.evidence.amountDelta)
            assertEquals("10.0000", result.evidence.extras["remaining_before"])
        }
    }

    @Test fun subsequentRefundUsesTheEventRemainderWithoutChangingCallerAmounts() {
        val expense = fact("e", "-10.0000")
        val first = fact("r1", "3.00", "refund", at = "2026-10-02T10:00:00Z")
        val second = fact("r2", "8.00", "refund", at = "2026-10-03T10:00:00Z")
        val remaining = mapOf("e" to amount("10.0000"))
        val context = MatchContext<LedgerArithmeticValue>().also { it.remainingByExpense = remaining }
        val result = LocalCashRelationPipeline.rules.run(listOf(expense, first, second), context)
        assertEquals(listOf("accepted"), result.map { it.status })
        assertEquals("7.0000", result.first().evidence.amountDelta)
        val exact = LocalCashRelationPipeline.rules.run(listOf(expense, first, second.copy(amount = amount("7.00"))),
            MatchContext<LedgerArithmeticValue>().also { it.remainingByExpense = remaining })
        assertEquals(listOf("accepted", "accepted"), exact.map { it.status })
        assertEquals("7.0000", exact.last().evidence.extras["remaining_before"])
        assertEquals("0.0000", exact.last().evidence.amountDelta)
        assertEquals("10.0000", remaining.getValue("e").toPlainString())
    }

    @Test fun instantObjectsKeepNanosecondsWhileStringTimesFollowOriginalMicroseconds() {
        val expense = fact("e", "-10.00", at = "2026-10-01T10:00:00.000000009Z")
        val refund = fact("r", "10.00", "refund", at = "2026-10-02T10:00:00.000000004Z")
        fun evaluate(e: RelationFact<LedgerArithmeticValue>, r: RelationFact<LedgerArithmeticValue>) =
            LocalCashRelationPipeline.refund.evaluate(r, listOf(e))!!.evidence.timeDeltaSeconds
        assertEquals(86400, evaluate(expense, refund))
        assertEquals(86399, evaluate(expense.copy(occurredAt = kotlin.time.Instant.parse(expense.occurredAt as String)),
            refund.copy(occurredAt = kotlin.time.Instant.parse(refund.occurredAt as String))))
    }

    @Test fun candidateBucketsUseNumericMagnitudeWhileEvidenceKeepsScale() {
        val platform = fact("p", "-100.0000", source = "alipay")
        val bank = fact("b", "-1E+2", at = "2026-10-01T10:00:05Z")
        val facts = listOf(platform, bank)
        assertEquals(listOf("b"), LocalCashRelationPipeline.index(facts).mirrorCandidates(platform).map { it.id })
        val result = LocalCashRelationPipeline.rules.run(facts).single()
        assertEquals("payment_mirror", result.kind)
        assertEquals("accepted", result.status)
        assertEquals("0.0000", result.evidence.amountDelta)
        val out = platform.copy(id = "out", recordType = "transfer_out", recordSubtype = "ordinary_transfer")
        val incoming = bank.copy(id = "in", amount = amount("100.00"), accountId = "b",
            recordType = "transfer_in", recordSubtype = "ordinary_transfer")
        assertEquals("transfer_pair", LocalCashRelationPipeline.rules.run(listOf(out, incoming)).single().kind)
    }

    @Test fun mirroredEventAccumulationExceedsColumnRangeBeforeClamping() {
        val text = "99999999999999999999.999999999999999999"
        val platform = fact("p", "-$text", source = "alipay")
        val bank = fact("b", "-$text")
        val members = setOf("p", "b")
        val remaining = mapOf("p" to amount("0"), "b" to amount("0"))
        val result = LocalCashRelationPipeline.rules.shareRefundRemainingAcrossMirrorEvents(listOf(platform, bank),
            mapOf("p" to members, "b" to members), remaining)
        assertEquals(0, result.getValue("p").signum)
        assertEquals(0, result.getValue("b").signum)
        assertEquals("0", remaining.getValue("p").toPlainString())
    }

    @Test fun diamondToleranceIsUnroundedAndExactlyInclusive() {
        val pay = fact("b", "-1.000000000000000001")
        val expense = fact("p", "-1.000000000000000001", source = "alipay")
        val platformRefund = fact("pr", "1.000100000000000001", "refund", "alipay")
        val bankRefund = platformRefund.copy(id = "br", billSource = "icbc_debit")
        fun discover(refund: RelationFact<LedgerArithmeticValue>) = LocalCashRelationPipeline.diamond.match(
            listOf(pay, expense, platformRefund, refund), listOf("b" to "p", "pr" to "br"), listOf("p" to "pr"))
        val result = discover(bankRefund).single()
        assertEquals("accepted", result.status)
        assertEquals("1.000100000000000001", result.evidence.extras["refund_amount"])
        assertTrue(discover(bankRefund.copy(amount = amount("1.000100000000000002"))).isEmpty())
    }
}
