package com.finance.tracker.business.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
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

    @Test fun frozenOperationsReadOnlyTheirOwnAuthenticatedPage() {
        val operations = (1..9).map { index ->
            mapOf("operation_ref" to "operation-$index", "values" to mapOf("note" to "原😀\u0001".repeat(4000)))
        }
        val blocks = Blocks()
        val plan = mapOf("operations" to operations)
        val reference = freezeLocalCashImportPreviewPlan(plan)
        val stored = freezeLocalCashImportPreviewPlan(plan, blocks)
        val rows = stored.getValue("operations").jsonArray
        assertTrue(blocks.texts.size > 1)
        assertEquals(0, blocks.reads)
        assertEquals(reference.getValue("operations").jsonArray.last(), rows.last())
        assertEquals(1, blocks.reads)
        assertEquals(reference.getValue("operations").jsonArray.first(), rows.first())
        assertEquals(2, blocks.reads)
        assertEquals(reference.toString(), stored.toString())
    }

    @Test fun duplicateOperationFailsBeforeReadingMalformedParentMetadata() {
        val plan = Json.parseToJsonElement("""{"operations":[
            {"operation_ref":"op_1","preview_transaction_id":{}}, {"operation_ref":"op_1"}]}
        """).jsonObject
        val failure = assertFailsWith<IllegalStateException> { LocalCashImportResultInput.freeze(plan) }
        assertEquals("cash_import_result_input_invalid", failure.message)
    }

    @Test fun authenticatedBlockRepresentationPreservesTheWriteSetAndOldConclusion() {
        val files = fixture.files()
        val source = fixture.source()
        fun prepare(workspace: CashImportReviewWorkspace?) = prepareLocalCashImportReview(
            files, source, fixture.input(files), null, emptyMap(), true, {}, {},
            { Instant.parse("2026-10-01T00:00:00Z") }, workspace)
        val reference = prepare(null)
        val stored = prepare(Blocks())
        assertEquals(reference.plan.toString(), stored.plan.toString())
        assertEquals(reference.changes, stored.changes)
        assertEquals(reference.confirmedReview().encode(), stored.confirmedReview().encode())
        assertEquals(reference.previewContext, stored.previewContext)
        val input = fixture.input(files)
        assertEquals(reference.previousReviewFor(input).encode(), stored.previousReviewFor(input).encode())
        assertEquals(LocalCashImportResultInput.freeze(reference.plan).encode(), LocalCashImportResultInput.freeze(stored.plan).encode())

    }
}
