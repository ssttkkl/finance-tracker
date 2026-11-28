package com.finance.tracker.business.domain

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.*

class CashImportReaderProjectionTest {
    private val fixture = LocalCashImportBusinessPlanTest()

    private class Blocks : CashImportReviewWorkspace {
        private val blocks = mutableListOf<String>()
        override fun write(text: String): Int = blocks.size.also { blocks += text }
        override fun read(block: Int): String = blocks[block]
    }

    private fun frozen(pairs: Int): JsonObject {
        val original = fixture.files().single()
        val template = checkNotNull(original.document)
        val rows = (0 until pairs).flatMap { group -> template.rows.mapIndexed { index, row ->
            val id = "ORDER-${group}" + if (index == 1) "_1" else ""
            val originalFields = checkNotNull(row.fields)
            val payload = (originalFields["source_payload"] as Map<*, *>).entries.associate { (key, value) -> key as String to value }
            row.copy(position = group * 2 + index + 1, providerId = id,
                fields = originalFields + ("source_payload" to (payload + ("交易订单号" to id))),
                sourceExplanation = row.sourceExplanation + ("txn_id" to id))
        } }
        val files = listOf(original.copy(document = template.copy(rows = rows, observedCount = rows.size, declaredCount = rows.size)))
        val raw = buildLocalCashImportBusinessPlan(files, fixture.source(), fixture.input(files), requireActive = {},
            now = { Instant.parse("2026-10-01T00:00:00Z") })
        val plan = freezeLocalCashImportPreviewPlan(raw, Blocks())
        assertEquals(pairs * 2, plan.getValue("operations").jsonArray.size)
        assertEquals(pairs, plan.getValue("relation_groups").jsonArray.size)
        return identifyCashImportPreview(plan, "synthetic-plan", "synthetic-batch", 1)
    }

    private fun read(plan: JsonObject, projection: Boolean) = LocalCashImportPlanReader(plan, true, projection) { rows, _ ->
        summarizeLocalCashImportMonths(rows) { it.take(7) }
    }

    private data class Sample(val bytes: Long, val ns: Long)

    @Test fun measureOnlyReaderStagesAndRecordFrozenFieldShapes() {
        val meter = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(meter.isThreadAllocatedMemorySupported)
        meter.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        fun <T> measure(action: () -> T): Pair<T, Sample> {
            val bytes = meter.getThreadAllocatedBytes(thread)
            val start = System.nanoTime()
            val value = action()
            return value to Sample(meter.getThreadAllocatedBytes(thread) - bytes, System.nanoTime() - start)
        }
        for (pairs in listOf(1, 16, 64)) {
            val plan = frozen(pairs)
            val old = read(plan, false)
            val new = read(plan, true)
            old.validateReadable(); new.validateReadable()
            val expected = old.view()
            assertEquals(expected, new.view())
            assertEquals(old.monthlySummaries(), new.monthlySummaries())
            val first = old.page("items", limit = 1, confirmationStatus = "confirmed", indexed = true)
            assertEquals(first, new.page("items", limit = 1, confirmationStatus = "confirmed", indexed = true))
            first["next_cursor"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let { cursor ->
                assertEquals(old.page("items", cursor, limit = 1, confirmationStatus = "confirmed"),
                    new.page("items", cursor, limit = 1, confirmationStatus = "confirmed"))
            }
            assertEquals(old.page("processing", limit = 2), new.page("processing", limit = 2))
            for (item in plan.getValue("items").jsonArray) {
                val id = item.jsonObject.getValue("preview_item_id").jsonPrimitive.content
                assertEquals(old.detail(id), new.detail(id))
                assertEquals(old.resolvedDetail(id), new.resolvedDetail(id))
            }
            for ((key, value) in plan) {
                val kind = if (value is JsonArray) "array" else if (value is JsonObject) "object" else "scalar"
                val count = when (value) { is JsonArray -> value.size; is JsonObject -> value.size; else -> 1 }
                println("review-plan-field ${pairs * 2} $key $kind $count ${value.toString().encodeToByteArray().size}")
            }
            val samples = mutableMapOf<Pair<Boolean, String>, MutableList<Sample>>()
            repeat(8) { repeat ->
                val order = if (repeat % 2 == 0) listOf(false, true) else listOf(true, false)
                for (projection in order) {
                    val (reader, construct) = measure { read(plan, projection) }
                    val (_, validate) = measure { reader.validateReadable() }
                    val (visible, view) = measure { reader.view() }
                    assertEquals(expected, visible)
                    if (repeat >= 2) {
                        for ((stage, value) in listOf("construct" to construct, "validate" to validate, "view" to view))
                            samples.getOrPut(projection to stage, ::mutableListOf) += value
                    }
                }
            }
            for ((key, values) in samples) {
                val (projected, stage) = key
                val mode = if (projected) "projected" else "full"
                for (sample in values) println("review-reader-sample ${pairs * 2} $mode $stage ${sample.bytes} ${sample.ns}")
            }
        }
    }

    @Test fun preservesOldMissingFieldsMutableInputAndErrorsOutsideTheFirstPage() {
        val plan = frozen(16)
        val omitted = plan.toMutableMap().also { it.remove("contract_version") }
        val old = read(JsonObject(omitted), false)
        val projected = read(JsonObject(omitted), true)
        val page = old.view()
        old.validateReadable(); projected.validateReadable()
        omitted.clear()
        assertEquals(page, old.view())
        assertEquals(page, projected.view())
        val details = plan.getValue("item_details").jsonObject.toMutableMap()
        val lastItem = plan.getValue("items").jsonArray.last().jsonObject.getValue("preview_item_id").jsonPrimitive.content
        details[lastItem] = JsonPrimitive("malformed detail")
        val invalid = JsonObject(plan + ("item_details" to JsonObject(details)))
        val invalidOld = read(invalid, false)
        val invalidNew = read(invalid, true)
        assertEquals(invalidOld.page("items", limit = 1), invalidNew.page("items", limit = 1))
        assertEquals(assertFailsWith<CashImportPlanError> { invalidOld.validateReadable() }.code,
            assertFailsWith<CashImportPlanError> { invalidNew.validateReadable() }.code)
    }
}
