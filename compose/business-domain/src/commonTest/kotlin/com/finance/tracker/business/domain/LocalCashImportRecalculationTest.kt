package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashSourceBindingWriteDto
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class LocalCashImportRecalculationTest {
    private fun fixture(strong: Boolean = false) = LocalCashImportRebindingTest().Fixture(strong)

    @Test fun localPreviewConsumesOnlyItsOriginalMutableOperationRows() {
        val fixture = fixture()
        val selections = fixture.input.getValue("account_selections")
        val files = fixture.files.map { file ->
            val document = checkNotNull(file.document)
            file.copy(document = document.copy(rows = document.rows.map { row ->
                val payload = mapOf("商品说明" to "Source refund", "fixture" to listOf("a"))
                row.copy(fields = checkNotNull(row.fields) + ("source_payload" to payload))
            }))
        }
        val original = prepareLocalCashImportOperations(files, fixture.source, selections, wireValues = false)
        val data = prepareOwnedLocalCashImportOperations(files, fixture.source, selections, JsonArray(emptyList()), emptyMap())
        assertEquals(original.operations, data.operations)
        assertEquals(original.rows, data.rows)
        assertEquals(original.candidateSets, data.candidateSets)
        val compactScan = scanLocalCashFundingForOperations(files)
        assertEquals(original.scan.groups, compactScan.groups)
        assertTrue(original.scan.rows.any { "source_payload" in it.fields })
        assertTrue(compactScan.rows.none { "source_payload" in it.fields || "note" in it.fields })

        val prepare = LocalCashImportPreviewPreparation(fixture.source) {}
        val expected = prepare.prepare(original.operations, original.source.rows.rows)
        val owned = prepare.prepareOwned(data.operations, data.source.rows.rows, emptyList(), null)
        assertEquals(expected.operations, owned.operations)
        assertEquals(expected.candidateIds, owned.candidateIds)
        assertSame(data.operations.single(), owned.operations.single())
        val at = { Instant.parse("2026-10-03T09:00:00Z") }
        val external = buildLocalCashImportPreviewSnapshot(fixture.source, expected.context, expected.operations, original.rows, at)
        val preview = buildOwnedLocalCashImportPreviewSnapshot(fixture.source, owned.context, data.operations, data.rows, compactValues = true, now = at)
        assertEquals(external.operations, preview.operations)
        assertEquals(external.rows, preview.rows)
        assertSame(data.operations.single(), preview.operations.single())
        assertSame(data.rows.single(), preview.rows.single())
        assertFalse("preview_transaction_id" in expected.operations.single())
        val parent = preview.operations.single().getValue("preview_transaction_id") as String
        val originalValues = external.values.getValue(parent)
        val compactValues = preview.values.getValue(parent)
        assertFalse(compactValues is LinkedHashMap<*, *>)
        assertEquals(originalValues.keys.toList(), compactValues.keys.toList())
        assertEquals(originalValues, compactValues)
        assertEquals(compactValues, originalValues)
        val initialComponents = (preview.operations.single().getValue("values") as Map<*, *>)["components"]
        assertNotSame(initialComponents, compactValues.getValue("components"))
        val payload = assertNotNull((preview.operations.single().getValue("values") as Map<*, *>)["source_payload"])
        val externalPayload = external.values.getValue(parent).getValue("source_payload")
        assertEquals(payload, externalPayload)
        assertNotSame((expected.operations.single().getValue("values") as Map<*, *>)["source_payload"], externalPayload)
        assertNotSame((external.operations.single().getValue("values") as Map<*, *>)["source_payload"], externalPayload)
        assertSame(payload, preview.values.getValue(parent).getValue("source_payload"))
        val externalSeeds = cashImportPreviewDiscoverySeeds(external)
        val internalSeeds = cashImportPreviewDiscoverySeeds(preview)
        assertEquals(externalSeeds, internalSeeds)
        val externalProposals = discoverLocalCashImportRelations(fixture.source, external, externalSeeds, expected.accounts) {}
        val internalProposals = discoverLocalCashImportRelations(fixture.source, preview, internalSeeds, owned.accounts) {}
        assertEquals(externalProposals, internalProposals)
        val choices = prepareLocalCashImportInput(selections, JsonArray(emptyList()), JsonArray(emptyList()), null)
        val externalPlan = completeLocalCashImportPreviewPlan(fixture.source, expected, external, externalProposals,
            files, choices, null, original.candidateSets)
        val internalPlan = completeLocalCashImportPreviewPlan(fixture.source, owned, preview, internalProposals,
            files, choices, null, data.candidateSets)
        assertEquals(freezeLocalCashImportPreviewPlan(externalPlan), freezeLocalCashImportPreviewPlan(internalPlan))
    }

    @Test fun oneSnapshotRestoresBothDecisionsAndComparesTheActualCompleteResult() {
        val f = fixture(); val (review, original) = f.confirmed(); val frozen = review.encode(); val current = f.observed()
        val inputs = mutableListOf<JsonObject>()
        val result = review.recalculate(current) { source, input, previous, ids ->
            assertSame(current, source); assertEquals(review.recordIds(), ids)
            assertEquals(review.inputJson()["account_selections"], input["account_selections"])
            assertEquals(review.inputJson()["row_choices"], input["row_choices"])
            inputs += input
            f.calculate(input, previous, ids, source).let { it.plan to it.writes }
        }
        assertEquals(3, inputs.size)
        assertEquals(JsonArray(emptyList()), inputs[0]["relation_choices"])
        assertEquals(JsonArray(emptyList()), inputs[0]["entry_choices"])
        assertTrue(inputs[1].getValue("relation_choices").jsonArray.isNotEmpty())
        assertEquals(JsonArray(emptyList()), inputs[1]["entry_choices"])
        assertTrue(inputs[2].getValue("entry_choices").jsonArray.isNotEmpty())
        assertEquals(inputs.last(), result.input)
        assertTrue(result.conclusionUnchanged)
        assertEquals(original.writes, result.changes)
        assertEquals(review.conclusion(), localCashImportConclusion(current, freezeLocalCashImportPreviewPlan(result.plan), result.changes))
        assertEquals(frozen, review.encode())
    }

    @Test fun streamingNormalizedWritesKeepsThePreviousCanonicalConclusionBytes() {
        val fixture = fixture()
        val (_, batch) = fixture.confirmed()
        val normalized = CashImportConclusionValues(fixture.source.facts, batch.writes).normalized()
        val review = buildJsonObject {
            put("𐐷", JsonArray(listOf(JsonPrimitive("\uD800"), JsonPrimitive("\uDC00"), JsonNull)))
            put("\uE000", JsonPrimitive(Long.MAX_VALUE))
            put("range", JsonPrimitive("quoted \"\\\t\u0000"))
        }
        val workspace = "账本𐐷"
        val legacy = Json { encodeDefaults = true }
        val envelope = buildJsonObject {
            put("version", "cash-import-conclusion-v1")
            put("workspace", workspace)
            put("rules", CASH_RULES_VERSION)
            put("writes", legacy.encodeToJsonElement(normalized))
            put("review", review)
        }
        fun canonical(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.keys.sortedWith(::pythonStringOrder).associateWith { canonical(value.getValue(it)) })
            is JsonArray -> JsonArray(value.map(::canonical))
            else -> value
        }
        val previous = buildString {
            for (char in canonical(envelope).toString()) {
                if (char.code < 128) append(char) else append("\\u" + char.code.toString(16).padStart(4, '0'))
            }
        }
        assertEquals(sha256Hex(previous), hashCashImportConclusion(workspace, normalized, review))
    }

    @Test fun separateReviewSectionsPreserveTheOldBytesAndCompleteParticipantClosure() {
        val prefix = JsonArray(listOf(JsonPrimitive("original"), JsonPrimitive(1)))
        val suffix = JsonArray(listOf(JsonPrimitive(("😀\t\u0000\"\\\uD800").repeat(4096)), JsonNull))
        val visited = mutableSetOf<String>()
        val fields = mapOf<String, () -> JsonElement>(
            "rows" to { visited += "later"; suffix },
            "operations" to { visited += "earlier"; prefix },
        )
        val participants = JsonArray(listOf(JsonPrimitive("earlier"), JsonPrimitive("later")))
        val previous = JsonObject(linkedMapOf("operations" to prefix, "participants" to participants, "rows" to suffix))
        val expected = sha256HexAscii { append -> writeConclusionText(previous.toString(), append) }
        val actual = sha256HexAscii { append ->
            writeCashImportReviewConclusion(fields, {
                assertEquals(setOf("earlier", "later"), visited)
                participants
            }, append)
        }
        assertEquals(expected, actual)
    }

    @Test fun normalizedWriteSetsAreMaterializedOnlyWhenTheirCollectionIsConsumed() {
        val fixture = fixture()
        val (_, batch) = fixture.confirmed()
        val reference = CashImportConclusionValues(fixture.source.facts, batch.writes).normalized()
        val bindings = batch.writes.sourceBindings
        assertTrue(bindings.isNotEmpty())
        var reads = 0
        val checked = object : AbstractList<CashSourceBindingWriteDto>() {
            override val size = bindings.size
            override fun get(index: Int) = bindings[index].also { reads++ }
        }
        val result = CashImportConclusionValues(fixture.source.facts, batch.writes.copy(sourceBindings = checked))
            .forSerialization()
        assertEquals(reference.sourceBindings.size, result.sourceBindings.size)
        assertEquals(reference.transactions, result.transactions.toList())
        assertEquals(0, reads)
        assertEquals(reference.sourceBindings, result.sourceBindings.toList())
        assertEquals(bindings.size, reads)
        assertEquals(cashWriteSetJson.encodeToString(reference), cashWriteSetJson.encodeToString(result))
        reads = 0
        val review = buildJsonObject { put("note", "摘要𐐷") }
        assertEquals(hashCashImportConclusion(fixture.source.workspaceId, reference, review),
            hashCashImportConclusion(fixture.source.workspaceId, result, review))
        assertEquals(bindings.size, reads)
    }

    @Test fun oneFrozenPlanPreservesTheLegacyCommandConclusionAndPreviewHistory() {
        val fixture = fixture()
        val evaluatedAt = Instant.parse("2026-10-03T09:00:00Z")
        val (plan, changes) = calculateLocalCashImportBusinessPlan(fixture.files, fixture.source, fixture.input,
            requireActive = {}, now = { evaluatedAt })
        val frozen = freezeLocalCashImportPreviewPlan(plan)
        val expected = LocalCashImportReview.freeze(fixture.input, plan, fixture.source, changes, frozen)
        val previousReview = LocalCashImportReview.forPreview(plan)
        val observed = mutableListOf<CashImportPlanningStage>()
        val mutableInput = fixture.input.toMutableMap()
        val current = prepareLocalCashImportReview(fixture.files, fixture.source, JsonObject(mutableInput), null, emptyMap(),
            includePreviewContext = true, requireActive = {}, progress = { observed += it }, now = { evaluatedAt })
        mutableInput.clear()
        assertEquals(CashImportPlanningStage.entries.toList(), observed)
        assertEquals(frozen.toString(), current.plan.toString())
        assertEquals(cashWriteSetJson.encodeToString(changes), cashWriteSetJson.encodeToString(current.changes))
        assertEquals(expected.encode(), current.confirmedReview().encode())
        assertEquals(previousReview(fixture.input).encode(), current.previousReviewFor(fixture.input).encode())
        assertEquals(LocalCashImportReview.encodePreviewContext(plan), current.previewContext)
    }

    @Test fun changedBusinessFactsRequireAnotherReviewEvenWhenBothBindingsStillMatch() {
        val f = fixture(strong = true); val (review, _) = f.confirmed(); val frozen = review.encode()
        val observed = f.observed()
        val current = observed.copy(facts = observed.facts.copy(
            cashTransactions = observed.facts.cashTransactions.map { if (it.id == "1") it.copy(amount = "-6.00") else it },
            cashTransactionComponents = observed.facts.cashTransactionComponents.map { if (it.id == "11") it.copy(amount = "-6.00") else it }))
        var calls = 0
        val result = review.recalculate(current) { source, input, previous, ids ->
            calls++; f.calculate(input, previous, ids, source).let { it.plan to it.writes }
        }
        assertEquals(3, calls); assertFalse(result.conclusionUnchanged)
        assertNotEquals(review.conclusion(), localCashImportConclusion(current, freezeLocalCashImportPreviewPlan(result.plan), result.changes))
        assertEquals(frozen, review.encode())
    }

    @Test fun aReviewWithIdenticalInputsAndNoChoicesReusesTheSameCalculation() {
        val f = fixture()
        val starting = f.calculate(discover = false)
        val review = LocalCashImportReview.decode(LocalCashImportReview.freeze(f.input, starting.plan, f.source, starting.writes).encode())
        val current = f.source.copy(ledgerSeq = (f.source.ledgerSeq.toLong() + 1).toString())
        var calls = 0
        val result = review.recalculate(current) { source, input, previous, ids ->
            calls++
            f.calculate(input, previous, ids, source, discover = false).let { it.plan to it.writes }
        }
        assertTrue(result.conclusionUnchanged)
        assertEquals(starting.writes, result.changes)
        assertEquals(f.input, result.input)
        assertEquals(1, calls)
    }

    @Test fun aNewRelationHistoryIsCalculatedAndChangesTheOriginalConclusion() {
        val f = fixture()
        val starting = f.calculate(discover = false)
        val review = LocalCashImportReview.decode(LocalCashImportReview.freeze(f.input, starting.plan, f.source, starting.writes).encode())
        val current = f.source.copy(ledgerSeq = (f.source.ledgerSeq.toLong() + 1).toString())
        var calls = 0
        val result = review.recalculate(current) { source, input, previous, ids ->
            calls++
            f.calculate(input, previous, ids, source).let { it.plan to it.writes }
        }
        assertEquals(2, calls)
        assertFalse(result.conclusionUnchanged)
        assertTrue((result.plan.getValue("relation_groups") as List<*>).isNotEmpty())
    }

    @Test fun noOriginalConfirmationCannotBeReplacedByTheLatestCalculation() {
        val f = fixture(); val (confirmed, final) = f.confirmed()
        val old = LocalCashImportReview.freeze(confirmed.inputJson(), final.plan)
        val frozen = old.encode(); var called = false
        assertEquals("plan_changed", assertFailsWith<CashImportPlanError> {
            old.recalculate(f.source) { _, _, _, _ -> called = true; error("must not calculate") }
        }.code)
        assertFalse(called); assertEquals(frozen, old.encode())
    }

    @Test fun failedBindingStopsBeforeApplyingAnotherDecision() {
        val f = fixture(); val (review, _) = f.confirmed(); val frozen = review.encode(); var calls = 0
        assertEquals("plan_changed", assertFailsWith<CashImportPlanError> {
            review.recalculate(f.source) { source, input, previous, ids ->
                calls++; f.calculate(input, previous, ids, source, discover = false).let { it.plan to it.writes }
            }
        }.code)
        assertEquals(1, calls); assertEquals(frozen, review.encode())
    }

    @Test fun originalCalculationFailuresPropagateAndNeverContinueThePasses() {
        val f = fixture(); val (review, _) = f.confirmed(); val frozen = review.encode()
        for (failedPass in 1..3) {
            var calls = 0; val expected = IllegalStateException("calculation defect")
            assertSame(expected, assertFailsWith<IllegalStateException> {
                review.recalculate(f.source) { source, input, previous, ids ->
                    if (++calls == failedPass) throw expected
                    f.calculate(input, previous, ids, source).let { it.plan to it.writes }
                }
            })
            assertEquals(failedPass, calls); assertEquals(frozen, review.encode())
        }
    }
}
