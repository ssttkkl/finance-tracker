package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class RemainingSourceBoundaryTest {
    private fun emptyCoverage() = CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1")
    private fun row(position: Int = 1, provider: String? = null) = SourceOccurrence("fixture-bank", "owner-full-account", "a".repeat(64), "fixture-v1", "sheet-1", position,
        mapOf("amount" to "-20.00", "currency" to "CNY", "account_id" to "101", "occurred_at" to "2026-08-15T00:00:00Z", "counterparty" to "甲", "note" to "", "category_id" to null,
            "record_type" to "consumption", "record_subtype" to "not_applicable", "source_payload" to mapOf("名称" to "甲")),
        CashSourceCoverage("2026-08-01T00:00:00Z", "2026-09-01T00:00:00Z", true, "statement-declared-count-and-range", emptyMap(), "source-coverage-v1"), provider)
    private fun old(first: SourceOccurrence, providerKey: Boolean = false) = row().let { base -> SourceRecord("1", base.sourceType, base.sourceAccountKey, base.fields + ("state" to "posted"),
        listOf(base.coverage), first.bindingKeys.toSet(), providerBindingKey = if (providerKey) first.providerBindingKey else null,
        fileOccurrences = mapOf(first.fileScopeKey to setOf(first.occurrenceKey))) }
    @Test fun otherPositionInSameOriginalFileProvesDistinctOccurrence() {
        val result = CashSourceResolver.resolveSourceFile(listOf(row(2).copy(coverage = emptyCoverage())), listOf(old(row()))).single()
        assertEquals("new", result.action); assertTrue(result.bind)
    }
    @Test fun sameProviderIdentityMatchesAnotherPosition() {
        val existing = old(row(provider = "synthetic-transaction"), true)
        val result = CashSourceResolver.resolveSourceFile(listOf(row(2, "synthetic-transaction").copy(coverage = emptyCoverage())), listOf(existing)).single()
        assertEquals("duplicate", result.action); assertEquals(existing.transactionId, result.transactionId)
    }
    @Test fun otherFileOrParserCannotProveDistinctness() {
        for (remaining in listOf(row(2).copy(fileDigest = "b".repeat(64), coverage = emptyCoverage()), row(2).copy(parserVersion = "other-parser", coverage = emptyCoverage()))) {
            val result = CashSourceResolver.resolveSourceFile(listOf(remaining), listOf(old(row()))).single()
            assertEquals("pending", result.action); assertFalse(result.bind)
        }
    }
    @Test fun newProviderNumberDoesNotOverrideDistinctPosition() {
        val result = CashSourceResolver.resolveSourceFile(listOf(row(2, "synthetic-new-number").copy(coverage = emptyCoverage())), listOf(old(row()))).single()
        assertEquals("new", result.action); assertTrue(result.bind)
    }
    private fun coverage(pages: Map<String, String>) = CashSourceCoverage("2026-07-01T00:00:00Z", "2026-08-01T00:00:00Z", false, "", emptyMap(), "source-coverage-v1", documentKey = "d".repeat(64), pageTotal = 3, pageFingerprints = pages)
    private fun documentRow(page: Int = 2) = ParsedCashRow(page, mapOf("amount" to "-12.5", "currency" to "CNY", "occurred_at" to "2026-07-05T00:00:00Z", "counterparty" to "合成商户", "note" to "", "record_type" to "consumption", "record_subtype" to "not_applicable", "source_payload" to emptyMap<String, Any?>()), documentPosition = "p$page:t0:r1")
    private fun file(coverage: CashSourceCoverage, rows: List<ParsedCashRow>, letter: String = "a", parser: String = "fixture-v1", source: String = "fixture") = ParsedImportFile("file_$letter", letter.repeat(64), "parsed",
        ParsedCashDocument(source, "table", parser, rows, "full-owner-account", null, coverage, rows.size, rows.size), clientFileRef = "client_$letter", filename = "synthetic.pdf", size = 100, sourceType = source)
    @Test fun differentParserNamespacesCannotCreateFalsePageConflicts() {
        val first = file(coverage(mapOf("1" to "a".repeat(64))).copy(pageTotal = 1), listOf(documentRow(1)))
        val second = file(coverage(mapOf("1" to "b".repeat(64))).copy(pageTotal = 1), listOf(documentRow(1)), "b", "another-parser")
        val result = CashSourceBatch.resolve(CashSourceRows.sourceBatchRows(listOf(first, second)), emptyList())
        assertFalse(result.rows.any { it.action == "conflict" })
    }
    @Test fun knownEmptyPageCompletesCurrentFileWithoutMutation() {
        val file = file(coverage(mapOf("2" to "b".repeat(64), "3" to "c".repeat(64))), listOf(documentRow(), documentRow(3)))
        val known = SourceDocumentEvidence("fixture", "fixture-v1", coverage(mapOf("1" to "a".repeat(64))))
        val beforeFile = file.copy(document = file.document!!.copy(coverage = file.document.coverage.copy(pageFingerprints = file.document.coverage.pageFingerprints.toMap()), rows = file.document.rows.map { it.copy(fields = it.fields!!.toMap()) }))
        val beforeKnown = known.copy(coverage = known.coverage.copy(pageFingerprints = known.coverage.pageFingerprints.toMap()))
        val rows = CashSourceRows.sourceBatchRows(listOf(file), knownDocumentEvidence = listOf(known))
        assertTrue(rows.rows.values.all { it.coverage.complete && it.coverage.contains(it.fields["occurred_at"]) }); assertEquals(beforeFile, file); assertEquals(beforeKnown, known); assertEquals(2, rows.documentEvidence.size)
    }
    @Test fun mismatchedBoundaryFilterPrecisionOrDeclarationCannotCertify() {
        val file = file(coverage(mapOf("2" to "b".repeat(64), "3" to "c".repeat(64))), listOf(documentRow()))
        val original = coverage(mapOf("1" to "a".repeat(64)))
        for (changed in listOf(original.copy(start = "2026-07-02T00:00:00Z"), original.copy(end = "2026-08-02T00:00:00Z"), original.copy(startInclusive = false), original.copy(endInclusive = true),
            original.copy(filters = mapOf("currency" to "CNY")), original.copy(timePrecision = "day"), original.copy(strategyVersion = "other"), original.copy(pageTotal = 4))) {
            val known = SourceDocumentEvidence("fixture", "fixture-v1", changed)
            assertFalse(CashSourceRows.sourceBatchRows(listOf(file), knownDocumentEvidence = listOf(known)).rows.values.first().coverage.complete)
        }
    }
    @Test fun knownPagesFromOtherNamespacesCannotCompleteFile() {
        val file = file(coverage(mapOf("2" to "b".repeat(64), "3" to "c".repeat(64))), listOf(documentRow()))
        for ((source, parser) in listOf("other" to "fixture-v1", "fixture" to "other")) {
            val known = SourceDocumentEvidence(source, parser, coverage(mapOf("1" to "a".repeat(64))))
            assertFalse(CashSourceRows.sourceBatchRows(listOf(file), knownDocumentEvidence = listOf(known)).rows.values.first().coverage.complete)
        }
    }
    @Test fun explicitExemptionDoesNotExtendToAdjacentUnselectedGroups() {
        fun group(id: String, members: List<String>): MutableMap<String, Any?> = linkedMapOf("suggestion_id" to id, "member_refs" to members.map { mapOf("transaction_id" to it) }, "candidates" to emptyList<Any?>(),
            "allowed_actions" to listOf("accept", "defer", "ignore"), "action" to "accept", "edges" to listOf(mapOf("secondary_transaction_id" to members.last())))
        for (action in listOf("accept", "defer", "ignore")) {
            val groups = listOf(group("new-a", listOf("t1", "t2")), group("new-b", listOf("t1", "t3")), group("unrelated", listOf("t4", "t5")))
            val stale = mapOf("suggestion_id" to "old", "action" to "accept")
            val previous = mapOf("relation_groups" to CashRecordPolicy.copyValue(groups), "invalidated_relation_choices" to mapOf("old" to mapOf("choice" to stale, "member_refs" to listOf(mapOf("transaction_id" to "t1")))))
            CashImportRelationDecisions.applyChoices(groups, listOf(stale, mapOf("suggestion_id" to "new-a", "action" to action)), previous)
            assertEquals(listOf(action, "defer", "accept"), groups.map { it["action"] })
        }
    }
    @Test fun originalUsHkCnSymbols() {
        for ((ledger, quote) in mapOf("aapl.us" to "AAPL", "0700.hk" to "0700.HK", "00700.hk" to "0700.HK", "600519.sh" to "600519.SS", "159740.sz" to "159740.SZ")) assertEquals(quote, securitySymbol(ledger))
    }
    @Test fun originalCryptoAndPredictionSymbolsRejected() { assertFailsWith<UnsupportedQuote> { securitySymbol("btc") }; assertFailsWith<UnsupportedQuote> { securitySymbol("pm:slug:yes") }; assertNull(ledgerSecuritySymbol("btc")) }
    @Test fun originalNanSnapshotRejectedAtValidationBoundary() {
        val snapshot = Json.parseToJsonElement("""{"accounts":{"security":{"broker":{"currency":"USD","positions":{"tsla":{"shares":"NaN","total_cost":"1","cost_currency":"USD"}}}}}}""").jsonObject
        assertContains(assertFailsWith<IllegalArgumentException> { validateInvestmentSnapshot(snapshot) }.message!!, "non-finite")
    }
}
