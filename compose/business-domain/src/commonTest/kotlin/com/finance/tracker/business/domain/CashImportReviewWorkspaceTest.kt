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
import kotlin.test.assertFails
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

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test fun earlyOperationBlocksKeepScalarTypesAndDecodeOnlyOneBlock() {
        val blocks = Blocks()
        val retained = StoredCashImportOperationValues(blocks)
        val source = linkedMapOf<String, Any?>(
            "record_id" to "identity", "amount" to CashAmount.parse("19.000000000000000001"),
            "occurred_at" to Instant.parse("2026-10-01T00:00:00.123456Z"),
            "source_payload" to listOf(mapOf("wide" to JsonUnquotedLiteral("90071992547409939999"),
                "literal" to CashDecimalLiteral("1.2300"), "" to "unchanged", "zero" to -0.0)),
            "long" to 9007199254740993L, "int" to SourceJsonInteger(8),
            "preview" to CashImportPreviewInteger.parse("9223372036854775808"),
            "nullable" to null, "boolean" to false,
        )
        val operations = (1..8).map { index ->
            linkedMapOf<String, Any?>("values" to source + ("note" to "原😀".repeat(21_000) + index))
        }
        val expected = operations.map { freezeLocalCashImportPreviewPlan(it).toString() }
        operations.forEach(retained::retain)
        retained.finish()
        assertTrue(blocks.texts.size > 2)
        assertEquals(0, blocks.reads)
        assertEquals(expected.last(), freezeLocalCashImportPreviewPlan(operations.last()).toString())
        assertEquals(1, blocks.reads)
        assertEquals(expected.first(), freezeLocalCashImportPreviewPlan(operations.first()).toString())
        assertEquals(2, blocks.reads)
    }

    @Test fun earlyOperationBlocksRejectInvalidUnicodeBeforeStorage() {
        for (payload in listOf(mapOf("\uD800" to "value"), JsonPrimitive("\uDC00"),
            JsonObject(mapOf("\uD800" to JsonNull)))) {
            val operation = linkedMapOf<String, Any?>("values" to mapOf("source_payload" to payload))
            assertFails { freezeLocalCashImportPreviewPlan(operation) }
            val blocks = Blocks()
            assertFails { StoredCashImportOperationValues(blocks).retain(operation) }
            assertTrue(blocks.texts.isEmpty())
        }
    }

    @Test fun earlyBlockFailureAbortsTheWholeReview() {
        val original = fixture.files().single()
        val document = checkNotNull(original.document)
        val largeRows = document.rows.map { row ->
            val fields = checkNotNull(row.fields)
            val payload = fields.getValue("source_payload") as Map<*, *>
            row.copy(fields = fields + ("source_payload" to (payload + ("long" to "x".repeat(70_000)))))
        }
        val files = listOf(original.copy(document = document.copy(rows = largeRows)))
        val failure = CashPolicyError("temporary_write_failed", emptyList())
        val blocks = Blocks()
        val workspace = object : CashImportReviewWorkspace by blocks {
            var writes = 0
            override fun write(text: String): Int {
                if (++writes == 1) throw failure
                return blocks.write(text)
            }
        }
        val error = assertFailsWith<CashPolicyError> {
            prepareLocalCashImportReview(files, fixture.source(), fixture.input(files), null, emptyMap(), true,
                {}, {}, { Instant.parse("2026-10-01T00:00:00Z") }, workspace)
        }
        assertSame(failure, error)
        assertTrue(blocks.texts.isEmpty())
    }

    @Test fun sourceValuesAreSpilledBeforeDiscoveryKeepingExactPlan() {
        val blocks = Blocks()
        val files = fixture.files()
        val source = fixture.source()
        val input = fixture.input(files)
        val now = { Instant.parse("2026-10-01T00:00:00Z") }
        var sourceBlocks = 0
        val stored = prepareLocalCashImportReview(files, source, input, null, emptyMap(), true, {},
            { stage ->
                if (stage == CashImportPlanningStage.DISCOVER_RELATIONS) sourceBlocks = blocks.texts.size
            }, now, blocks)
        val reference = prepareLocalCashImportReview(files, source, input, null, emptyMap(), true, {}, {}, now)
        assertTrue(sourceBlocks > 0)
        assertEquals(reference.plan.toString(), stored.plan.toString())
        assertEquals(reference.previewContext, stored.previewContext)
        assertEquals(reference.changes, stored.changes)
        assertEquals(reference.confirmedReview().encode(), stored.confirmedReview().encode())
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
