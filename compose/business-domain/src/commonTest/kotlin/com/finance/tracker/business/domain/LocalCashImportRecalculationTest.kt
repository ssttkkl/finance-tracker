package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashImportRecalculationTest {
    private fun fixture(strong: Boolean = false) = LocalCashImportRebindingTest().Fixture(strong)

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
