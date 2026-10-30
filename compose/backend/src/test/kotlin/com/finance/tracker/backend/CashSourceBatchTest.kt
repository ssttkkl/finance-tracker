package com.finance.tracker.backend

import kotlin.test.*

class CashSourceBatchTest {
    private fun row(position: Int = 1, name: String = "甲", provider: String? = null,
        fields: Map<String, Any?> = emptyMap()) = SourceOccurrence(
        "fixture-bank", "owner-full-account", "a".repeat(64), "fixture-v1", "sheet-1", position,
        mapOf("amount" to "-20.00", "currency" to "CNY", "account_id" to "101",
            "occurred_at" to "2026-08-15T00:00:00Z", "counterparty" to name, "note" to "",
            "category_id" to null, "record_type" to "consumption", "record_subtype" to "not_applicable",
            "source_payload" to mapOf("名称" to name)) + fields,
        CashSourceCoverage("2026-08-01T00:00:00Z", "2026-09-01T00:00:00Z", true,
            "statement-declared-count-and-range", emptyMap(), "source-coverage-v1", endInclusive = false), provider)

    private fun old(id: String = "1", name: String = "甲", provider: String? = null) = row(name = name).let {
        SourceRecord(id, it.sourceType, it.sourceAccountKey, it.fields + ("state" to "posted"),
            listOf(it.coverage), providerId = provider)
    }

    private fun file(letter: String, vararg rows: SourceOccurrence) = rows.associateTo(linkedMapOf()) {
        "$letter:r${it.position}" to it.copy(fileDigest = letter.repeat(64))
    }

    private fun batch(rows: Map<String, SourceOccurrence>, records: List<SourceRecord> = emptyList(),
        times: Map<String, Long> = emptyMap()): SourceBatchPlan {
        val metadata = rows.keys.map { it.substringBefore(':') }.distinct().mapIndexed { index, key ->
            key to (index to times[key])
        }.toMap()
        return CashSourceBatch.resolve(SourceBatchRows(LinkedHashMap(rows), emptyList(), emptyList(), metadata), records)
            .also { assertEquals(rows.keys.toList(), it.rows.map { value -> value.rowRef }) }
    }

    private fun SourceBatchPlan.writes() = rows.filter { it.action in setOf("new", "match") }
    private fun SourceBatchPlan.result(ref: String) = rows.single { it.rowRef == ref }
    private fun SourceBatchPlan.equivalents(ref: String) = groups.single { it.groupRef == result(ref).groupRef }.rowRefs

    @Test fun repeatedFileKeepsOccurrencesAndExactDuplicateBindings() {
        val first = file("a", row(), row(2))
        val result = batch(first + first.mapKeys { it.key.replace("a:", "retry:") })
        assertEquals(2, result.writes().size)
        for (position in 1..2) with(result.result("retry:r$position")) {
            assertEquals("batch_duplicate", action); assertEquals("a:r$position", duplicateOf); assertTrue(bind)
        }
    }

    @Test fun overlappingMultisetsUseMaximumMultiplicityAndOnlyCommonExcess() {
        val rows = file("a", row(), row(2)) + file("b", row(), row(2), row(3))
        val fresh = batch(rows)
        assertEquals(3, fresh.writes().size)
        val duplicates = fresh.rows.filter { it.action == "batch_duplicate" }
        assertEquals(2, duplicates.size)
        duplicates.forEach { assertFalse(it.bind); assertEquals(3, fresh.equivalents(it.rowRef).size) }
        val existing = batch(rows, listOf(old(), old("2")))
        assertEquals(listOf("new"), existing.writes().map { it.action })
        existing.rows.filter { it.action in setOf("duplicate", "batch_duplicate") }.forEach { assertFalse(it.bind) }
    }

    @Test fun reliableSnapshotUsesTimeThenSelectionOrderAndPreservesFixedConflict() {
        val rows = file("a", row(provider = "A")) + file("b", row(name = "更新", provider = "A"))
        for (times in listOf(emptyMap(), mapOf("a" to 200L), mapOf("a" to 100L, "b" to 100L),
            mapOf("a" to 100L, "b" to 200L))) {
            val result = batch(rows, times = times)
            assertEquals("new", result.result("b:r1").action)
            assertEquals("batch_duplicate", result.result("a:r1").action)
            assertEquals("b:r1", result.result("a:r1").duplicateOf)
        }
        val reversed = batch(rows, times = mapOf("a" to 200L, "b" to 100L))
        assertEquals("a:r1", reversed.result("b:r1").duplicateOf)
        assertEquals("new", reversed.result("a:r1").action)
        assertEquals("batch_duplicate", reversed.result("b:r1").action)
        val changed = file("a", row(provider = "A")) + file("b", row(provider = "A", fields = mapOf("amount" to "-21")))
        batch(changed, listOf(old(provider = "A"))).rows.forEach {
            assertEquals("conflict", it.action); assertEquals("fixed_field_conflict", it.reason); assertFalse(it.bind)
        }
    }

    @Test fun unknownOwnersAndIncomparableCoverageDeferOnlyUnprovenRows() {
        for (second in listOf(row().copy(sourceAccountKey = null), row().copy(coverage = row().coverage.copy(start = null, end = null, complete = false, evidence = "")),
            row().copy(coverage = row().coverage.copy(filters = mapOf("kind" to "expense"))),
            row().copy(coverage = row().coverage.copy(timePrecision = "day")))) {
            val result = batch(file("a", row()) + file("b", second))
            assertEquals("pending", result.result("b:r1").action); assertFalse(result.result("b:r1").bind)
            if (second.sourceAccountKey == null) {
                assertEquals("new", result.result("a:r1").action)
                assertEquals(listOf("a:r1"), result.equivalents("b:r1"))
            } else { assertEquals("pending", result.result("a:r1").action); assertFalse(result.result("a:r1").bind) }
        }
    }

    @Test fun sourceProofsPropagateConflictAndKeepEveryOldTarget() {
        val rows = file("a", row(provider = "A")) +
            file("b", row(provider = "A").copy(stableEvidence = "verified:1")) +
            file("c", row(provider = "B").copy(stableEvidence = "verified:1"))
        assertTrue(batch(rows).rows.all { it.action == "conflict" })
        for (verified in listOf(false, true)) {
            val first = row(provider = "A", fields = mapOf("amount" to "-21"))
            val pair = file("a", first) + file("b", first.copy(stableEvidence = if (verified) "verified:old" else null))
            val target = if (verified) old().copy(stableEvidence = "verified:old")
            else old().copy(bindingKeys = setOf(pair.getValue("b:r1").fileBindingKey))
            batch(pair, listOf(target)).rows.forEach {
                assertEquals("conflict", it.action); assertEquals("fixed_field_conflict", it.reason); assertFalse(it.bind)
            }
        }
    }

    @Test fun invalidRowsNullPatchesAndEquivalentAmountsKeepOriginalBoundaries() {
        val invalid = file("a", row(provider = "A", fields = mapOf("currency" to emptyList<String>()))) +
            file("b", row(provider = "A"))
        val result = batch(invalid)
        assertEquals("conflict", result.result("a:r1").action)
        assertEquals("invalid_field", result.result("a:r1").reason)
        assertEquals("new", result.result("b:r1").action)
        val first = row(provider = "A")
        val omitted = first.copy(fields = first.fields - "category_id")
        assertTrue(batch(file("a", first) + file("b", omitted), listOf(old().let { it.copy(fields = it.fields + ("category_id" to "old")) })).rows.all {
            it.action == "conflict" && it.reason == "batch_identity_conflict"
        })
        val equal = row(provider = "A", fields = mapOf("amount" to "-20", "occurred_at" to "2026-08-15T08:00:00+08:00"))
        assertEquals(1, batch(file("a", first) + file("b", equal)).writes().size)
        assertEquals("invalid_source_row_ref", assertFailsWith<IllegalArgumentException> {
            batch(mapOf("" to first))
        }.message)
    }

    @Test fun distinctReliableRowsAndSameFileWeakOccurrenceAreNeverCollapsed() {
        assertEquals(2, batch(file("a", row(provider = "A")) + file("b", row(provider = "B"))).writes().size)
        assertEquals(2, batch(file("a", row(provider = "A"), row(2)) + file("b", row(provider = "A"))).writes().size)
        val pair = file("a", row(provider = "A"), row(2, provider = "B")) + file("b", row(), row(2))
        val result = batch(pair)
        assertEquals(2, result.writes().size)
        for (ref in listOf("b:r1", "b:r2")) {
            assertEquals("batch_duplicate", result.result(ref).action); assertFalse(result.result(ref).bind)
            assertEquals(listOf("a:r1", "a:r2"), result.equivalents(ref))
        }
    }

    @Test fun reorderedEqualSetsAndUniformUpdatesKeepUncertainBindingsEmpty() {
        val rows = file("a", row(), row(2, "乙")) + file("b", row(name = "乙"), row(2))
        for (records in listOf(emptyList(), listOf(old("1", "乙"), old("2")))) {
            val result = batch(rows, records)
            assertEquals(if (records.isEmpty()) 2 else 0, result.writes().size)
            assertFalse(result.rows.any { it.action in setOf("pending", "conflict") })
            if (records.isNotEmpty()) result.rows.forEach { assertFalse(it.bind); assertNull(it.transactionId) }
        }
        val updates = batch(file("a", row(name = "新"), row(2, "新")) + file("b", row(name = "新"), row(2, "新")),
            listOf(old(), old("2", "乙")))
        assertEquals(setOf("1", "2"), updates.writes().map { it.transactionId }.toSet())
        assertEquals(2, updates.writes().size)
    }

    @Test fun crossedNamesDeferTheWholeAmbiguousSetWithoutGreedyRemainders() {
        val rows = file("a", row(), row(2, "乙")) + file("b", row(name = "乙"), row(2, "丙")) +
            file("c", row(fields = mapOf("amount" to "-99")))
        for (records in listOf(emptyList(), listOf(old(), old("2", "乙")))) {
            val result = batch(rows, records)
            assertTrue(result.rows.filter { !it.rowRef.startsWith("c:") }.all { it.action == "pending" })
            assertEquals("new", result.result("c:r1").action)
        }
    }

    @Test fun uniqueWeakRowsAndKnownAliasesPreserveProvenCorrespondence() {
        val result = batch(file("a", row()) + file("b", row()), listOf(old()))
        with(result.result("a:r1")) { assertEquals("duplicate", action); assertEquals("1", transactionId) }
        with(result.result("b:r1")) {
            assertEquals("batch_duplicate", action); assertEquals("a:r1", duplicateOf); assertTrue(bind)
        }
        val pending = batch(file("a", row(provider = "A")) + file("b", row(provider = "A")),
            listOf(old().copy(sourceAccountKey = null)))
        assertEquals("pending", pending.result("a:r1").action)
        assertEquals("a:r1", pending.result("b:r1").duplicateOf)
        assertEquals(pending.result("a:r1").candidateIds, pending.result("b:r1").candidateIds)
        val noRange = row(provider = "A").copy(coverage = row().coverage.copy(start = null, end = null, complete = false, evidence = ""))
        val reliable = batch(file("a", noRange) + file("b", noRange))
        assertEquals(1, reliable.writes().size)
        assertTrue(reliable.result("b:r1").bind); assertEquals("a:r1", reliable.result("b:r1").duplicateOf)
    }

    @Test fun snapshotPayloadChangesAndCombinedIdentityRetainConflicts() {
        for (fields in listOf(mapOf("counterparty" to "更新"), mapOf("source_payload" to mapOf("状态" to "交易成功")))) {
            val result = batch(file("a", row(provider = "A")) + file("b", row(provider = "A", fields = fields)) +
                file("c", row(provider = "C")), times = mapOf("a" to 100L, "b" to 200L))
            assertEquals("new", result.result("b:r1").action)
            assertEquals("new", result.result("c:r1").action)
            assertEquals("batch_duplicate", result.result("a:r1").action)
            assertEquals("b:r1", result.result("a:r1").duplicateOf)
        }
        val tied = file("a", row()) + file("b", row(name = "变化"))
        val oldTarget = old().copy(bindingKeys = tied.values.flatMap { it.bindingKeys }.toSet())
        assertTrue(batch(tied, listOf(oldTarget)).rows.all { it.action == "conflict" })
        val combined = file("a", row(provider = "A")) + file("b", row(provider = "A").copy(stableEvidence = "conflicting"))
        assertTrue(batch(combined, listOf(old(provider = "A").copy(stableEvidence = "current"))).rows.all {
            it.action == "conflict" && it.reason == "source_identity_conflict"
        })
        val locator = row()
        assertTrue(batch(mapOf("one" to locator, "two" to locator.copy(fields = locator.fields + ("counterparty" to "另一条"))))
            .rows.all { it.action == "conflict" })
    }

    @Test fun scopeAndDeletedEvidenceCannotCreateOrResurrectExtraRecords() {
        val first = row(provider = "A")
        val second = row(provider = "B").copy(sourceAccountKey = null)
        for (known in listOf(first, first.copy(sourceAccountKey = null)))
            assertEquals(2, batch(file("a", known) + file("b", second)).writes().size)
        assertEquals(2, batch(file("a", row()) + file("b", row().copy(sourceAccountKey = "other-owner"))).writes().size)
        val result = batch(file("a", first) + file("b", first), listOf(old(provider = "A").copy(deleted = true)))
        assertEquals(1, result.writes().size); assertTrue(result.rows.all { it.transactionId == null })
        val distinct = batch(file("a", first) + file("b", row(provider = "B")) + file("c", row(), row(2)))
        assertEquals(2, distinct.writes().size)
        assertEquals("batch_duplicate", distinct.result("c:r1").action)
        assertEquals("batch_duplicate", distinct.result("c:r2").action)
        assertTrue(batch(file("a", row().copy(sourceAccountKey = null)) + file("b", row().copy(sourceAccountKey = null)))
            .rows.all { it.action == "pending" && !it.bind })
    }

    @Test fun selectedIdentityStillBlocksUnprovenCrossFileCollision() {
        val result = batch(file("a", row(provider = "A")) + file("b", row(name = "变化", provider = "A")) +
            file("c", row()) + file("d", row(provider = "D")) + file("e", row(fields = mapOf("amount" to "-99"))))
        assertEquals("batch_duplicate", result.result("a:r1").action)
        assertEquals("b:r1", result.result("a:r1").duplicateOf)
        assertEquals("new", result.result("b:r1").action)
        assertEquals("pending", result.result("c:r1").action)
        assertEquals("new", result.result("d:r1").action)
        assertEquals("new", result.result("e:r1").action)
        assertEquals("new", batch(file("a", row(provider = "A"), row(2)) +
            file("b", row(name = "变化", provider = "A"))).result("a:r2").action)
    }

    @Test fun permutationPreservesBusinessCountsAndDoesNotMutateInput() {
        val files = listOf(file("a", row(), row(2)), file("b", row()), file("c", row(), row(2), row(3)))
        fun copy(value: Any?): Any? = when (value) {
            is Map<*, *> -> value.entries.associate { it.key to copy(it.value) }
            is List<*> -> value.map(::copy)
            else -> value
        }
        val before = files.map { file -> file.mapValues { (_, value) ->
            @Suppress("UNCHECKED_CAST")
            value.copy(fields = copy(value.fields) as Map<String, Any?>,
                coverage = value.coverage.copy(filters = value.coverage.filters.toMap(), pageFingerprints = value.coverage.pageFingerprints.toMap()))
        } }
        for (first in files.indices) for (second in files.indices.filter { it != first }) {
            val third = files.indices.single { it != first && it != second }
            val result = batch(files[first] + files[second] + files[third])
            assertEquals(3, result.writes().size)
            assertEquals(3, result.rows.count { it.action == "batch_duplicate" })
        }
        assertEquals(before, files)
    }

    @Test fun emptyPageEvidenceParticipatesInTheParserNamespace() {
        val coverage = row().coverage.copy(documentKey = "a".repeat(64), pageTotal = 1,
            pageFingerprints = mapOf("1" to "b".repeat(64)))
        val occurrence = row().copy(coverage = coverage, documentPosition = "p1:t0:r1")
        val conflicting = SourceDocumentEvidence(occurrence.sourceType, occurrence.parserVersion,
            coverage.copy(pageFingerprints = mapOf("1" to "c".repeat(64))))
        val input = SourceBatchRows(file("a", occurrence), listOf(conflicting), emptyList(), emptyMap())
        assertEquals("batch_identity_conflict", CashSourceBatch.resolve(input, emptyList()).rows.single().reason)
        val otherNamespace = SourceBatchRows(input.rows, listOf(conflicting.copy(parserVersion = "other")), emptyList(), emptyMap())
        assertEquals("new", CashSourceBatch.resolve(otherNamespace, emptyList()).rows.single().action)
        val legacy = SourceBatchRows(input.rows, emptyList(), listOf(conflicting.coverage), emptyMap())
        assertEquals("batch_identity_conflict", CashSourceBatch.resolve(legacy, emptyList()).rows.single().reason)
    }

    @Test fun unicodeReferencesUsePythonCodePointOrdering() {
        val first = row()
        val second = row().copy(fileDigest = "b".repeat(64))
        val refs = linkedMapOf("\uD800\uDC00:r1" to first, "\uE000:r1" to second)
        val result = batch(refs, listOf(old("\uD800\uDC00", "旧一"), old("\uE000", "旧二")))
        assertTrue(result.groups.isNotEmpty())
        result.groups.forEach { group ->
            assertEquals(listOf("\uE000", "\uD800\uDC00"), group.candidateIds)
            assertEquals(sourceDigest("source-results-v1", group.rowRefs, group.candidateIds), group.groupRef)
        }
    }

    @Test fun ambiguousAndRejectedResultsKeepSharedGroupsResolvable() {
        val ambiguous = batch(file("a", row(name = "新"), row(2, "新"), row(3, "新")) +
            file("b", row(name = "另一种"), row(2, "另一种"), row(3, "另一种")),
            listOf(old("1", "旧1"), old("2", "旧2"), old("3", "旧3")))
        assertTrue(ambiguous.rows.all { it.action == "pending" && it.candidateIds.isEmpty() })
        assertEquals(1, ambiguous.groups.size)
        assertEquals(9, ambiguous.groups.sumOf { it.rowRefs.size + it.candidateIds.size })
        assertTrue(ambiguous.rows.all { it.groupRef == ambiguous.groups.single().groupRef })
        val rejected = batch(file("a", row(provider = "A"), row(2, "乙", "A"), row(3, provider = "A")) +
            file("b", row(), row(2), row(3)))
        assertEquals(3, rejected.rows.count { it.action == "conflict" })
        assertEquals(3, rejected.rows.count { it.action == "pending" })
        assertEquals(1, rejected.groups.size)
        assertEquals(3, rejected.groups.single().rowRefs.size)
        assertTrue(rejected.rows.filter { it.action == "pending" }.all { it.groupRef == rejected.groups.single().groupRef })
    }
}
