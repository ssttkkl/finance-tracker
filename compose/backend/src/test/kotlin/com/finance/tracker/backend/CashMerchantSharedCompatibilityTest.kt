package com.finance.tracker.backend

import com.finance.tracker.business.domain.CashRefundMatchRules
import com.finance.tracker.business.domain.normalizeCashMerchant
import java.math.BigDecimal
import java.time.Duration
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class CashMerchantSharedCompatibilityTest {
    private val originalFilter = Regex("(?U)[\\W_]+")
    private fun original(value: String?) = originalFilter.replace(value.orEmpty(), "").lowercase(Locale.ROOT)

    @Test fun everyCodePointKeepsOriginalFilteringAndScalarMapping() {
        assertEquals(original(null), normalizeCashMerchant(null))
        for (point in 0..0x10ffff) {
            val text = String(Character.toChars(point))
            val expected = original(text)
            val actual = normalizeCashMerchant(text)
            if (expected != actual) assertEquals(expected, actual, "code point $point")
        }
    }

    @Test fun contextualSigmaQueriesKeepOriginalUtf16AndFiltering() {
        val representatives = listOf("", "A", "1", "Σ", "İ", "ª", "ʰ", "\u0345", "\u0301", "‿",
            "\u200d", "𐀀", "𐐀", "𠀀", "漢", "か", "カ", "ー", "\ud800", "\udc00", "_!", "Ⓐ")
        for (left in representatives) for (middle in representatives) for (right in representatives) {
            val text = "${left}Σ${middle}Σ$right"
            assertEquals(original(text), normalizeCashMerchant(text), "UTF-16 ${text.map(Char::code)}")
        }
    }

    @Test fun actualBackendRefundProposalsMatchOriginalJvmConfiguration() {
        val reference = CashRefundMatchRules(
            CashRelationCore.sharedRules, ::original, CashRelationGeometry::parseDt,
            { a, b -> Duration.between(a, b).toNanos() },
            { value: BigDecimal -> value.abs() }, { a, b -> a.subtract(b) },
            { it.toPlainString() }, CashRelationGeometry::asDecimal)
        for (text in listOf("A1Σ", "AΣ‿A", "AΣª", "AΣʰ", "AΣ\u0345", "AΣⅠ", "AΣ𐀀A",
            "AΣ𐐀A", "AΣ𠀀A", "A\ud800Σ", "İAΣ", "A_Σ!A", "AΣⒶ", "退款", "_!", "商户")) {
            val refund = RelationFact("r", BigDecimal("3.00"), "CNY", "a",
                occurredAt = "2026-10-02T10:00:00Z", counterparty = text, recordType = "refund",
                billSource = "alipay", source = "alipay", recordId = "refund")
            val expense = RelationFact("e", BigDecimal("-10.00"), "CNY", "a",
                occurredAt = "2026-10-01T10:00:00Z", counterparty = original(text), recordType = "consumption",
                billSource = "icbc_debit", recordId = "expense")
            val candidates = listOf(expense, expense.copy(id = "older", occurredAt = "2026-09-30T10:00:00Z"))
            assertEquals(reference.evaluate(refund, candidates), CashRefundMatch.evaluate(refund, candidates), text)
            assertEquals(reference.evaluate(refund, candidates, mapOf("e" to BigDecimal("2.00"))),
                CashRefundMatch.evaluate(refund, candidates, mapOf("e" to BigDecimal("2.00"))), text)
            val events = mapOf("e" to "event", "older" to "event")
            assertEquals(reference.evaluate(refund, candidates, candidateEventIds = events),
                CashRefundMatch.evaluate(refund, candidates, candidateEventIds = events), text)
        }
    }
}
