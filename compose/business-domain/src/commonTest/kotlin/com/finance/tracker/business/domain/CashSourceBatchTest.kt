package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashSourceCoverage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CashSourceBatchTest {
    private val policy = CashRecordPolicyRules(
        normalize = { field, value -> when (field) {
            "amount" -> (value as? String)?.let(CashAmount::parse) ?: value
            "occurred_at" -> (value as? String)?.let(::parsePythonCashTimestamp) ?: value
            "note" -> value as? String ?: throw InvalidCashValue()
            else -> value
        } },
        equal = { left, right -> left == right },
    )
    private fun digest(values: List<Any?>) = sourceJson(values,
        { value -> (value as? CashAmount)?.numericText() }, ::pythonFloatText).toString()
    private val resolver = CashSourceResolver(
        prepareFields = policy::prepare,
        indexValue = { fields -> listOf(fields["amount"]?.let { CashAmount.parse(it.toString()).numericText() },
            (fields["occurred_at"] as? String)?.let(::parsePythonCashTimestamp), fields["currency"]) },
        digestValues = ::digest,
    )
    private var groupDigests = 0
    private val batch = CashSourceBatch(resolver,
        normalizePatch = { patch -> policy.prepare(mapOf("state" to "draft"), patch).normalizedValues },
        contentValue = { it },
        digestValues = { values ->
            if (values.firstOrNull() == "source-results-v1") groupDigests++
            digest(values)
        },
    )
    private val coverage = CashSourceCoverage("2026-08-01T00:00:00Z", "2026-09-01T00:00:00Z", true, "proof", emptyMap(), "v1")
    private fun row(position: Int = 1, note: String = "original", provider: String? = null) =
        SourceOccurrence("fixture-bank", "owner", "a".repeat(64), "p1", "t", position,
            mapOf("amount" to "-20.00", "currency" to "CNY", "occurred_at" to "2026-08-15T00:00:00Z",
                "record_type" to "consumption", "record_subtype" to "not_applicable", "note" to note), coverage, provider)
    private fun file(ref: String, vararg rows: SourceOccurrence) = rows.associateTo(linkedMapOf()) {
        "$ref:r${it.position}" to it.copy(fileDigest = ref.repeat(64))
    }
    private fun resolve(rows: Map<String, SourceOccurrence>, records: List<SourceRecord> = emptyList(),
        times: Map<String, Long?> = emptyMap()): SourceBatchPlan {
        val metadata = rows.keys.map { it.substringBefore(':') }.distinct().mapIndexed { index, ref -> ref to (index to times[ref]) }.toMap()
        return batch.resolve(SourceBatchRows(LinkedHashMap(rows), emptyList(), emptyList(), metadata), records)
    }
    private fun old(id: String, note: String = "original") = SourceRecord(id, "fixture-bank", "owner", row(note = note).fields,
        listOf(coverage))

    @Test
    fun rereadFilePreservesLegitimateOccurrencesAndExactDuplicateBindings() {
        val original = file("a", row(), row(2))
        val result = resolve(original + original.mapKeys { it.key.replace("a:", "retry:") })
        assertEquals(listOf("new", "new", "batch_duplicate", "batch_duplicate"), result.rows.map { it.action })
        assertEquals(listOf("a:r1", "a:r2"), result.rows.drop(2).map { it.duplicateOf })
        assertTrue(result.rows.all { it.bind })
        assertEquals((original.keys + listOf("retry:r1", "retry:r2")).toList(), result.rows.map { it.rowRef })
    }

    @Test
    fun overlappingExportsUseMaximumMultiplicityWithoutArbitraryBindings() {
        val result = resolve(file("a", row(), row(2)) + file("b", row(), row(2), row(3)))
        assertEquals(3, result.rows.count { it.action == "new" })
        assertEquals(2, result.rows.count { it.action == "batch_duplicate" })
        val duplicates = result.rows.filter { it.action == "batch_duplicate" }
        assertTrue(duplicates.all { !it.bind && it.duplicateOf == null })
        duplicates.forEach { duplicate -> assertEquals(3, result.groups.single { it.groupRef == duplicate.groupRef }.rowRefs.size) }
    }

    @Test
    fun reliableSnapshotUsesModificationTimeBeforeSelectionOrder() {
        val rows = file("a", row(provider = "A")) + file("b", row(note = "updated", provider = "A"))
        assertEquals("b:r1", resolve(rows).rows.first().duplicateOf)
        val older = resolve(rows, times = mapOf("a" to 200L, "b" to 100L))
        assertEquals("new", older.rows.first().action)
        assertEquals("a:r1", older.rows.last().duplicateOf)
        val equalAmount = rows.mapValues { (_, row) -> row.copy(fields = row.fields + ("amount" to "-20")) }
        assertEquals(1, resolve(equalAmount).rows.count { it.action == "new" })
    }

    @Test
    fun ambiguousGroupsRemainCompactAndShareOneDeterministicReference() {
        val rows = file("a", row(note = "new"), row(2, "new"), row(3, "new")) +
            file("b", row(note = "another"), row(2, "another"), row(3, "another"))
        val result = resolve(rows, listOf(old("1", "old1"), old("2", "old2"), old("3", "old3")))
        assertTrue(result.rows.all { it.action == "pending" && it.candidateIds.isEmpty() })
        val group = result.groups.single()
        assertEquals(9, group.rowRefs.size + group.candidateIds.size)
        assertTrue(result.rows.all { it.groupRef == group.groupRef })
        assertEquals(digest(listOf("source-results-v1", group.rowRefs, group.candidateIds)), group.groupRef)
        assertFalse(result.rows.any { it.bind })
    }

    @Test
    fun largeCandidateGroupDoesNotReencodeEveryCandidateForEveryRow() {
        val count = 200
        val rows = file("a", *(1..count).map { row(it, "changed$it") }.toTypedArray())
        val records = (1..count).map { old(it.toString(), "old$it") }
        groupDigests = 0
        val result = resolve(rows.mapValues { (_, value) -> value.copy(coverage = coverage.copy(complete = false)) }, records)
        assertTrue(result.rows.all { it.action == "pending" })
        assertEquals(count, result.groups.single().candidateIds.size)
        assertEquals(1, groupDigests)
    }

    @Test
    fun candidateReferencesKeepStableHashWithoutExposingMutableInputs() {
        val input = mutableListOf("first", "second")
        val references = SourceReferences(input)
        input[0] = "changed after resolution"
        val expected = listOf("first", "second")
        assertTrue(references == expected && expected == references)
        assertTrue(references == references)
        assertEquals(expected.hashCode(), references.hashCode())
        assertEquals("result", mapOf<List<String>, String>(references to "result")[expected])
    }

}
