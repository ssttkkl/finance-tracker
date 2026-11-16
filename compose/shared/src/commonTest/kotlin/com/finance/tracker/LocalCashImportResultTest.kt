package com.finance.tracker

import com.finance.tracker.business.contracts.CashImportCommitOutcomeDto
import com.finance.tracker.data.*
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashImportResultTest {
    @Test fun onlyUnsubmittedRowsAreAddedAndActualCommitFactsStayUntouched() {
        val input = localCashImportResultInputFixture()
        val actual = localCashWriteSetReceiptFixture().copy(status = "completed", summary = mapOf("created_posted" to 2L, "duplicate" to 1L),
            needsAttentionCount = 3, pendingItemCount = 2, commitOutcome = CashImportCommitOutcomeDto(false, true, emptyList()))
        val command = LocalCashImportSubmission.freeze("batch", localCashWriteSetFixture(), input).withReceipt(actual)
        val archived = assertNotNull(LocalCashImportResult.from("batch", LocalCashImportSubmission.decode(command.encode())))
        val displayed = archived.displayReceipt()
        assertEquals(mapOf("created_posted" to 2L, "duplicate" to 3L, "skipped" to 2L, "relations_failed" to 1L), displayed.summary)
        assertEquals("partial", displayed.status)
        assertEquals(actual, displayed.copy(status = actual.status, summary = actual.summary))
        assertEquals(actual, archived.authoritativeReceipt())
        assertEquals("9007199254740993", archived.rows().jsonArray.first().jsonObject.getValue("transaction_id").jsonPrimitive.content)
        assertEquals(listOf("9007199254740993", "42"), archived.relationFailures().single().jsonObject.getValue("parent_ids").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(command.encode(), command.withReceipt(actual).encode())
        assertFails { command.withReceipt(displayed) }
        assertEquals(archived.encode(), LocalCashImportResult.decode(archived.encode()).encode())
        assertEquals(4, input.content().getValue("rows").jsonArray.size)
        assertFalse(input.encode().contains("must-not-persist"))
    }

    @Test fun inputIsImmutableAndDoesNotRetainPreviewSuccessCountsOrPayloads() {
        val plan = localCashImportResultPlanFixture().toMutableMap()
        val frozen = LocalCashImportResultInput.freeze(JsonObject(plan))
        val original = frozen.encode()
        plan.clear()
        assertEquals(original, frozen.encode())
        assertEquals(setOf("version", "rows", "files", "relation_failures"), frozen.content().keys)
        assertEquals(listOf("alias", "unsubmitted", "normal-skip", "problem-skip"),
            frozen.content().getValue("rows").jsonArray.map { it.jsonObject.getValue("row_ref").jsonPrimitive.content })
        val restored = LocalCashImportSubmission.decode(LocalCashImportSubmission.freeze("batch", localCashWriteSetFixture(), frozen).withBaseRejection().encode())
        assertEquals(original, restored.resultInput?.encode())
        assertTrue(restored.baseRejected)
        assertFalse(restored.encode().contains("must-not-persist"))
        val old = LocalCashImportSubmission.freeze("old", localCashWriteSetFixture()).withReceipt(localCashWriteSetReceiptFixture())
        assertNull(old.resultInput); assertNull(LocalCashImportResult.from("old", old))
    }

    @Test fun normalSkipsDoNotInventAProblemAndKnownProblemsDoNotInventNewFailures() {
        for (code in listOf("row_not_selected", "unpaid_closed", "failed_repay", "account_opening", "row_unreadable")) {
            val input = LocalCashImportResultInput.freeze(Json.parseToJsonElement("""{
                "operations":[],"row_decisions":[{"row_ref":"skip","planned_action":"skip","code":"$code"}],
                "file_results":[],"relation_failures":[]}
            """).jsonObject)
            val actual = localCashWriteSetReceiptFixture().copy(status = "no_changes", commitOutcome = CashImportCommitOutcomeDto(false, false, emptyList()))
            val view = assertNotNull(LocalCashImportResult.from("batch", LocalCashImportSubmission.freeze("batch", localCashWriteSetFixture(), input).withReceipt(actual))).displayReceipt()
            assertEquals(if (code == "row_unreadable") "failed" else "no_changes", view.status)
            assertEquals(actual.commitOutcome, view.commitOutcome); assertEquals(1L, view.summary["skipped"])
        }
    }

    @Test fun corruptionAndCountOverflowFailInsteadOfPublishingInventedTotals() {
        val input = localCashImportResultInputFixture()
        for (replacement in listOf(JsonPrimitive(2), JsonPrimitive("1"), JsonNull)) {
            assertFails { LocalCashImportResultInput.decode(JsonObject(input.content() + ("version" to replacement)).toString()) }
        }
        assertFails { LocalCashImportResultInput.decode(JsonObject(input.content() - "files").toString()) }
        val badPlan = JsonObject(localCashImportResultPlanFixture() + ("operations" to JsonArray(emptyList())))
        assertFails { LocalCashImportResultInput.freeze(badPlan) }
        val actual = localCashWriteSetReceiptFixture().copy(summary = mapOf("duplicate" to Long.MAX_VALUE))
        val result = assertNotNull(LocalCashImportResult.from("batch", LocalCashImportSubmission.freeze("batch", localCashWriteSetFixture(), input).withReceipt(actual)))
        assertFails { result.displayReceipt() }
        val badRows = JsonArray(input.content().getValue("rows").jsonArray.mapIndexed { index, row ->
            if (index == 0) JsonObject(row.jsonObject + ("operation_index" to JsonPrimitive(1))) else row
        })
        val bad = LocalCashImportResultInput.decode(JsonObject(input.content() + ("rows" to badRows)).toString())
        assertFails { LocalCashImportResult.from("batch", LocalCashImportSubmission.freeze("batch", localCashWriteSetFixture(), bad).withReceipt(actual)) }
    }
}

internal fun localCashImportResultPlanFixture(): JsonObject = Json.parseToJsonElement("""{
    "operations":[{"operation_ref":"op","preview_transaction_id":"new-parent"}], "summary":{"created_posted":999999},
    "row_decisions":[
        {"row_ref":"representative","planned_action":"duplicate","operation_ref":"op","transaction_id":"42"},
        {"row_ref":"alias","planned_action":"duplicate_in_batch","operation_ref":"op","transaction_id":"42"},
        {"row_ref":"unsubmitted","planned_action":"duplicate","operation_ref":null},
        {"row_ref":"normal-skip","planned_action":"skip","code":"row_not_selected","issues":[{"message":"must-not-persist"}]},
        {"row_ref":"problem-skip","planned_action":"skip","code":"row_unreadable"}],
    "file_results":[{"file_ref":"file","display_name":"synthetic.csv","status":"ready","recognized_row_count":5},
        {"file_ref":"bad-file","display_name":"failed.pdf","status":"failed","recognized_row_count":null,
            "issues":[{"code":"invalid_file","message":"must-not-persist"}],"diagnostic":{"text":"must-not-persist"}}],
    "relation_failures":[{"code":"relation_invalid","parent_ids":["new-parent","42"],"private":"must-not-persist"}]
}""").jsonObject
internal fun localCashImportResultInputFixture() = LocalCashImportResultInput.freeze(localCashImportResultPlanFixture())
