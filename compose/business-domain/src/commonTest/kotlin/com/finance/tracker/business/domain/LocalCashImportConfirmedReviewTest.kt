package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashImportConfirmedReviewTest {
    private val input = Json.parseToJsonElement("""{"account_selections":[],"row_choices":[],"relation_choices":[],"entry_choices":[]}""").jsonObject
    private fun complete(): Triple<LedgerSnapshotDto, Map<String, Any?>, CashWriteSetDto> {
        val (source, plan, changes) = LocalCashImportRelationWritesTest().calculateComputed()
        return Triple(source, plan, buildLocalCashImportEntryWrites(source, freezeLocalCashImportPreviewPlan(plan), changes))
    }

    @Test fun actualCalculatedRefundAmountsAndSelectedEntryTargetsCrossTheProductionFreezeBoundary() {
        val (source, plan, changes) = complete()
        val targets = plan.getValue("entry_targets") as Map<*, *>
        val selected = targets.entries.first { target -> ((target.value as Map<*, *>)["final_edges"] as List<*>).isNotEmpty() }
        val edges = (selected.value as Map<*, *>)["final_edges"] as List<*>
        assertTrue((edges.first() as Map<*, *>)["applied_amount"] is CashDecimalLiteral)
        val selectedInput = JsonObject(input + ("entry_choices" to buildJsonArray { add(buildJsonObject {
            put("edit_ref", selected.key as String); put("patch", buildJsonObject { put("note", "confirmed") })
        }) }))
        val saved = LocalCashImportReview.freeze(selectedInput, plan, source, changes)
        val recovered = LocalCashImportReview.decode(saved.encode())
        assertEquals(localCashImportConclusion(source, freezeLocalCashImportPreviewPlan(plan), changes), recovered.conclusion())
        assertEquals(localCashImportDigest(listOf(selected.value)), localCashImportDigest(listOf(
            (recovered.previousPlan()["entry_targets"] as Map<*, *>)[selected.key])))
    }

    @Test fun originalCompleteConclusionSurvivesRecoveryWithoutRecomputingFromNewFacts() {
        val (source, plan, changes) = complete()
        val saved = LocalCashImportReview.freeze(input, plan, source, changes)
        val restored = LocalCashImportReview.decode(saved.encode())
        val expected = localCashImportConclusion(source, freezeLocalCashImportPreviewPlan(plan), changes)
        assertEquals(expected, restored.conclusion())
        assertNotEquals(localCashImportWriteConclusion(source, changes), restored.conclusion())
        val changed = source.copy(facts = source.facts.copy(cashTransactions = source.facts.cashTransactions.map {
            it.copy(amount = "-123.45")
        }))
        assertNotEquals(expected, localCashImportConclusion(changed, freezeLocalCashImportPreviewPlan(plan), changes))
        assertEquals(expected, LocalCashImportReview.decode(saved.encode()).conclusion())
        assertEquals(LocalCashImportReview.freeze(input, plan).input(), restored.input())
        assertEquals(LocalCashImportReview.freeze(input, plan).previousPlan(), restored.previousPlan())
        assertFalse(saved.encode().contains("file_results"))
        assertFalse(saved.encode().contains("source_payload"))
    }

    @Test fun fileAndReviewOutcomesRemainPartOfTheSavedConfirmationEvenWithIdenticalWrites() {
        val (source, plan, changes) = complete()
        val failed = plan + ("file_results" to (plan.getValue("file_results") as List<*>).map {
            @Suppress("UNCHECKED_CAST") (it as Map<String, Any?>) + ("status" to "failed")
        })
        val first = LocalCashImportReview.freeze(input, plan, source, changes)
        val second = LocalCashImportReview.freeze(input, failed, source, changes)
        assertNotEquals(first.conclusion(), second.conclusion())
        assertEquals(localCashImportConclusion(source, freezeLocalCashImportPreviewPlan(failed), changes), LocalCashImportReview.decode(second.encode()).conclusion())
    }

    @Test fun legacyReviewsStayReadableAndNeverGainAnInventedConfirmation() {
        val original = LocalCashImportReview.freeze(input, emptyMap())
        assertEquals(JsonPrimitive(1), Json.parseToJsonElement(original.encode()).jsonObject["version"])
        assertNull(original.conclusion())
        assertNull(LocalCashImportReview.decode(original.encode()).conclusion())
        assertEquals(original.encode(), LocalCashImportReview.decode(original.encode()).encode())
    }

    @Test fun corruptConfirmationsCannotSilentlyBecomeMissingRecoveryData() {
        val (source, plan, changes) = complete()
        val document = Json.parseToJsonElement(LocalCashImportReview.freeze(input, plan, source, changes).encode()).jsonObject
        assertEquals(JsonPrimitive(2), document["version"])
        val invalid = listOf<JsonElement>(JsonNull, JsonPrimitive(true), JsonPrimitive(123), JsonPrimitive(""),
            JsonPrimitive("a".repeat(63)), JsonPrimitive("g".repeat(64)), JsonPrimitive("A".repeat(64)))
        for (value in invalid) assertFails { LocalCashImportReview.decode(JsonObject(document + ("conclusion" to value)).toString()) }
        assertFails { LocalCashImportReview.decode(JsonObject(document - "conclusion").toString()) }
        assertFails { LocalCashImportReview.decode(JsonObject(document + ("version" to JsonPrimitive(1))).toString()) }
        assertFails { LocalCashImportReview.decode(JsonObject(document + ("unknown" to JsonNull)).toString()) }
    }

    @Test fun brokenComputedPlansCannotFreezeAnEmptyOrPartialConclusion() {
        val (source, plan, changes) = complete()
        assertFails { LocalCashImportReview.freeze(input, plan - "file_results", source, changes) }
    }
}
