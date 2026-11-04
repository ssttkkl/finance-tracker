package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashSourceCoverage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CashSourceResolverTest {
    private val policy = CashRecordPolicyRules(
        normalize = { field, value -> when (field) {
            "amount" -> (value as? String)?.let(CashAmount::parse) ?: value
            "occurred_at" -> (value as? String)?.let(::parsePythonCashTimestamp) ?: value
            "note" -> value as? String ?: throw InvalidCashValue()
            else -> value
        } },
        equal = { left, right -> left == right },
    )
    private val resolver = CashSourceResolver(
        prepareFields = policy::prepare,
        indexValue = { fields -> listOf(fields["amount"]?.let { CashAmount.parse(it.toString()).numericText() },
            (fields["occurred_at"] as? String)?.let(::parsePythonCashTimestamp), fields["currency"]) },
        digestValues = { values -> sourceJson(values, { null }, ::pythonFloatText).toString() },
    )
    private val coverage = CashSourceCoverage("2026-08-01T00:00:00Z", "2026-09-01T00:00:00Z", true, "statement", emptyMap(), "v1")
    private fun row(position: Int = 1) = SourceOccurrence("ccb_debit", "account", "a".repeat(64), "parser-v1", "t", position,
        mapOf("amount" to "-20.00", "currency" to "CNY", "occurred_at" to "2026-08-15T00:00:00Z",
            "record_type" to "consumption", "record_subtype" to "not_applicable", "note" to "original"), coverage)
    private fun record(id: String = "t1") = SourceRecord(id, "ccb_debit", "account", row().fields, listOf(coverage))

    @Test
    fun completeMultisetKeepsLegitimateRepeatedRowsWithoutInventingBindings() {
        val result = resolver.resolveSourceRows(listOf(row(1), row(2)), listOf(record("t1"), record("t2")))
        assertEquals(listOf("duplicate", "duplicate"), result.map { it.action })
        assertTrue(result.all { it.transactionId == null && !it.bind })
        assertEquals(listOf("duplicate", "new"), resolver.resolveSourceRows(listOf(row(1), row(2)), listOf(record())).map { it.action })
    }

    @Test
    fun unknownCoverageRequiresReviewInsteadOfArbitraryBinding() {
        val value = row().copy(coverage = CashSourceCoverage(null, null, false, "", emptyMap(), "v1"))
        val result = resolver.resolveSourceFile(listOf(value), listOf(record("😀"), record("\ue000"))).single()
        assertEquals("pending", result.action)
        assertEquals(listOf("\ue000", "😀"), result.candidateIds)
        assertEquals("source_identity_ambiguous", result.reason)
        assertTrue(!result.bind)
    }

    @Test
    fun reliableIdentityRejectsFinancialChangesButAllowsMutableSourceUpdates() {
        val value = row().copy(providerId = "provider-1")
        val old = record().copy(bindingKeys = value.bindingKeys.toSet())
        val changed = value.copy(fields = value.fields + ("note" to "new source note"))
        assertEquals("match", resolver.resolveSourceRows(listOf(changed), listOf(old)).single().action)
        val invalid = changed.copy(fields = changed.fields + ("amount" to "-21"))
        val failed = resolver.resolveSourceRows(listOf(invalid), listOf(old)).single()
        assertEquals("conflict", failed.action)
        assertEquals("fixed_field_conflict", failed.reason)
        assertEquals("t1", failed.transactionId)
        assertTrue(!failed.bind)
    }

    @Test
    fun deletedRecordsAndConflictingPagesRespectTheirSeparateBoundaries() {
        assertEquals("new", resolver.resolveSourceRows(listOf(row()), listOf(record().copy(deleted = true))).single().action)
        val first = row().copy(coverage = coverage.copy(documentKey = "d".repeat(64), pageTotal = 1, pageFingerprints = mapOf("1" to "a".repeat(64))), documentPosition = "p1:t0:r1")
        val second = first.copy(fileDigest = "b".repeat(64), coverage = first.coverage.copy(pageFingerprints = mapOf("1" to "b".repeat(64))))
        val result = resolver.resolveSourceRows(listOf(first, second), emptyList())
        assertEquals(listOf("batch_identity_conflict", "batch_identity_conflict"), result.map { it.reason })
        assertTrue(result.all { it.action == "conflict" && !it.bind })
    }

    @Test
    fun invalidInputAndUnknownExplicitTargetsFailBeforeAnyResultIsApplied() {
        assertEquals("duplicate_source_locator", assertFailsWith<IllegalArgumentException> {
            resolver.resolveSourceFile(listOf(row(), row()), emptyList())
        }.message)
        assertEquals("one_source_file_required", assertFailsWith<IllegalArgumentException> {
            resolver.resolveSourceFile(listOf(row(), row(2).copy(fileDigest = "b".repeat(64))), emptyList())
        }.message)
        assertEquals("unknown_source_target", assertFailsWith<IllegalStateException> {
            resolver.resolveSourceRows(listOf(row()), emptyList(), mapOf(row().occurrenceKey to setOf("missing")))
        }.message)
    }
}
