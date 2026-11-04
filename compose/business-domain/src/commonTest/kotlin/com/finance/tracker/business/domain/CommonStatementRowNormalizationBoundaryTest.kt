package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashSourceCoverage
import com.finance.tracker.business.contracts.CashStatementDocument
import com.finance.tracker.business.contracts.RawCashRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommonStatementRowNormalizationBoundaryTest {
    @Test fun emptyDocumentsUseTheSharedDispatcherAndKeepOriginalMetadata() {
        val rules = statementBoundaryRules()
        for (source in listOf("alipay", "wechat", "ccb_debit", "icbc_asia", "icbc_credit", "icbc_debit")) {
            val input = CashStatementDocument(source, "table:0", "raw-v1", emptyList(), "owner", "label",
                CashSourceCoverage(null, null, false, "", emptyMap(), "v1"), 0, 0, "CNY", "nickname")
            val result = rules.normalizeDocument(input)
            assertEquals(source, result.sourceType)
            assertEquals("raw-v1+native-v1", result.parserVersion)
            assertEquals(input.coverage, result.coverage)
            assertEquals("owner", result.sourceAccountIdentifier)
            assertEquals("nickname", result.sourceNickname)
            assertEquals(emptyList(), result.rows)
        }
    }

    @Test fun unsupportedSourceFailsBeforeAnyPrimitiveAdapterIsCalled() {
        val input = CashStatementDocument("unsupported", "table:0", "v1", emptyList(), null, null,
            CashSourceCoverage(null, null, false, "", emptyMap(), "v1"), null, 0)
        assertEquals("statement_source_unsupported", assertFailsWith<StatementDocumentFailure> {
            statementBoundaryRules().normalizeDocument(input)
        }.code)
    }

    @Test fun unreadableRowsKeepSourceSpecificSkipAndCoverageEvidenceRules() {
        val coverage = CashSourceCoverage(null, null, true, "declared", emptyMap(), "v1",
            documentKey = "a".repeat(64), pageTotal = 1, pageFingerprints = mapOf("1" to "b".repeat(64)))
        val original = CashStatementDocument("alipay", "table:0", "v1", listOf(RawCashRow(1, null)),
            null, null, coverage, 1, 1)
        assertEquals("statement_unreadable", assertFailsWith<StatementDocumentFailure> {
            statementBoundaryRules().normalizeDocument(original)
        }.code)
        for (source in listOf("wechat", "ccb_debit", "icbc_asia", "icbc_credit", "icbc_debit")) {
            val result = statementBoundaryRules().normalizeDocument(original.copy(sourceType = source))
            assertEquals("row_unreadable", result.rows.single().errorCode)
            assertEquals("skipped", result.rows.single().disposition)
            assertEquals(false, result.coverage.complete)
            assertEquals("", result.coverage.evidence)
            val pdf = source == "icbc_credit" || source == "icbc_debit"
            assertEquals(if (pdf) null else coverage.documentKey, result.coverage.documentKey)
            assertEquals(if (pdf) emptyMap() else coverage.pageFingerprints, result.coverage.pageFingerprints)
        }
        assertEquals(true, original.coverage.complete)
        assertEquals("declared", original.coverage.evidence)
    }

    private fun statementBoundaryRules() = CashStatementRowNormalizationRules(
        amounts = CashStatementAmountValues<Int>(
            fromCash = { error("unused") }, sign = { error("unused") }, absolute = { error("unused") },
            negate = { error("unused") }, plain = { error("unused") }, zeroPlain = { error("unused") }),
        timestamp = { _, _ -> error("unused") }, basicDate = { error("unused") },
        classify = { _, _ -> error("unused") }, account = { _, _, _, _ -> error("unused") },
        textValue = { error("unused") }, uppercase = { error("unused") },
        sourcePattern = { Regex("unused") }, freezeNativeRow = { error("unused") },
    )
}
