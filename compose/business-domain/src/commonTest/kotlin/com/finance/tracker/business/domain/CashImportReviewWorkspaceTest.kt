package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashWriteSetDto
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
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

    @Test fun workingSourcesReleaseRawFieldsOnlyAfterBuildingWholeBatchOperations() {
        val files = fixture.files()
        val source = fixture.source()
        val input = fixture.input(files)
        val owned = CashImportReviewSources(files)
        val originalFields = files.flatMap { it.document?.rows.orEmpty() }.map { it.fields }
        assertTrue(originalFields.any { !it.isNullOrEmpty() })
        val originalText = originalFields.toString()
        fun prepare(reading: List<ParsedImportFile>, workspace: CashImportReviewWorkspace?, requireActive: () -> Unit = {}) =
            prepareLocalCashImportReview(reading, source, input, null, emptyMap(), true, requireActive, {},
                { Instant.parse("2026-10-01T00:00:00Z") }, workspace)
        val reference = prepare(files, null)
        val stored = prepare(owned, Blocks())
        assertEquals(reference.plan.toString(), stored.plan.toString())
        assertEquals(reference.changes, stored.changes)
        assertEquals(reference.confirmedReview().encode(), stored.confirmedReview().encode())
        assertEquals(reference.previewContext, stored.previewContext)
        assertEquals(originalText, files.flatMap { it.document?.rows.orEmpty() }.map { it.fields }.toString())
        assertEquals("cash_import_review_sources_consumed", assertFailsWith<IllegalStateException> { owned[0] }.message)
        assertEquals("cash_import_review_sources_consumed", assertFailsWith<IllegalStateException> { prepare(owned, Blocks()) }.message)
        val aborted = CashImportReviewSources(files)
        val stopped = IllegalStateException("fixture_cancelled")
        assertSame(stopped, assertFailsWith<IllegalStateException> { prepare(aborted, Blocks()) { throw stopped } })
        assertEquals("cash_import_review_sources_consumed", assertFailsWith<IllegalStateException> { prepare(aborted, Blocks()) }.message)
        val unclaimed = CashImportReviewSources(files)
        assertEquals(reference.plan.toString(), prepare(unclaimed, null).plan.toString())
        assertEquals(originalText, unclaimed.flatMap { it.document?.rows.orEmpty() }.map { it.fields }.toString())

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
    }
}
