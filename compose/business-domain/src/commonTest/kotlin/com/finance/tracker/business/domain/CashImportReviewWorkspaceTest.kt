package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashWriteSetDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.time.Instant

class CashImportReviewWorkspaceTest {
    private val fixture = LocalCashImportBusinessPlanTest()

    private class Blocks : CashImportReviewWorkspace {
        val texts = mutableListOf<String>()
        var reads = 0
        override fun write(text: String): Int = texts.size.also { texts += text }
        override fun read(block: Int): String = texts[block].also { reads++ }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test fun frozenPagesPreserveOrderWideNumbersAndReadOnlyTheirBlock() {
        val notes = (0..8).map { index ->
            mapOf("description" to "原😀\u0001".repeat(2500) + index,
                "wide" to JsonUnquotedLiteral("90071992547409939999"), "optional" to JsonNull)
        }
        val input = mapOf("items" to notes, "item_details" to notes.withIndex().associate { "pi_${it.index}" to it.value })
        val blocks = Blocks()
        val reference = freezeLocalCashImportPreviewPlan(input)
        val stored = freezeLocalCashImportPreviewPlan(input, blocks)
        assertTrue(blocks.texts.size > 2)
        assertEquals(0, blocks.reads)
        assertEquals(reference.getValue("items").jsonArray.last(), stored.getValue("items").jsonArray.last())
        assertEquals(1, blocks.reads)
        assertEquals(reference.getValue("item_details").jsonObject["pi_2"], stored.getValue("item_details").jsonObject["pi_2"])
        assertEquals(2, blocks.reads)
        assertEquals(reference.toString(), stored.toString())
    }

    @Test fun choicesWithoutSelectedRelationsOrEntriesDoNotReadTrustedPreviewKeys() {
        val source = fixture.source()
        val unusedPlan = JsonObject(emptyMap())
        val context = localCashImportChoiceContextFromPlan(unusedPlan)
        val bindings = freezeLocalCashImportChoiceBindings(source, unusedPlan, CashWriteSetDto(),
            fixture.input(fixture.files()), context, trustedPlan = true)
        assertEquals(JsonPrimitive(source.workspaceId), bindings["workspace"])
        assertEquals(JsonPrimitive(CASH_RULES_VERSION), bindings["rules"])
        assertEquals(JsonObject(emptyMap()), bindings["relations"])
        assertEquals(JsonObject(emptyMap()), bindings["entries"])
    }

    @Test fun authenticatedBlockRepresentationPreservesTheWriteSetAndOldConclusion() {
        val files = fixture.files()
        val source = fixture.source()
        fun prepare(workspace: CashImportReviewWorkspace?) = prepareLocalCashImportReview(
            files, source, fixture.input(files), null, emptyMap(), false, {}, {},
            { Instant.parse("2026-10-01T00:00:00Z") }, workspace)
        val reference = prepare(null)
        val stored = prepare(Blocks())
        assertEquals(reference.plan.toString(), stored.plan.toString())
        assertEquals(reference.changes, stored.changes)
        assertEquals(reference.confirmedReview().encode(), stored.confirmedReview().encode())
        assertEquals(LocalCashImportResultInput.freeze(reference.plan).encode(), LocalCashImportResultInput.freeze(stored.plan).encode())
        val original = reference.plan.getValue("operations").jsonArray
        val frozen = stored.plan.getValue("operations").jsonArray
        val refs = assertNotNull(frozen.frozenOperationReferences())
        assertEquals(original.size, refs.operationPositions.size)
        assertEquals(original.map { it.jsonObject.getValue("preview_transaction_id").jsonPrimitive.content },
            refs.parentsByPosition)
        assertEquals(original, frozen)
    }
    @Test fun publicSourceWriteValidatesEveryTransactionBeforeInspectingIssues() {
        val files = fixture.files()
        val source = fixture.source()
        val plan = prepareLocalCashImportReview(files, source, fixture.input(files), null, emptyMap(), false, {}, {},
            { Instant.parse("2026-10-01T00:00:00Z") }).plan
        val operations = plan.getValue("operations").jsonArray.map { it.jsonObject }.toMutableList()
        operations[0] = JsonObject(operations[0] + ("expected_issues" to JsonNull))
        operations[1] = JsonObject(operations[1] + ("expected_revision" to JsonPrimitive("invalid-revision")))
        val malformed = JsonObject(plan + ("operations" to JsonArray(operations)))
        val failure = assertFailsWith<IllegalStateException> {
            buildLocalCashImportSourceWrites(source, files, prepareLocalCashImportSources(files, source), malformed)
        }
        assertEquals("local_import_transaction_revision_changed", failure.message)
    }
}
