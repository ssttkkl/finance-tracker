package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class CashImportOriginalPlanApiTest {
    private fun root(plan: JsonObject) = "/api/v1/cash-import/plans/${plan.apiText("plan_id")}"
    private fun receiptRoot(receipt: JsonObject) = "/api/v1/cash-mutations/receipts/${receipt.apiText("receipt_id")}"
    private fun revised(view: JsonObject, plan: JsonObject) = JsonObject(view + ("session_revision" to plan.getValue("session_revision")))
    private fun repreview(plan: JsonObject, vararg values: Pair<String, JsonElement>) =
        JsonObject(mapOf("previous_plan_id" to plan.getValue("plan_id")) + values)
    @Test fun fullRefundZeroRemainsReviewableAndHasOneCurrentResult() = testApplication {
        val f = CashImportOriginalApiFixture("refund")
        application { financeModule(f.database) }
        val plan = f.plan(client, f.upload(client)); assertEquals(1, plan.apiArray("items").size)
        val item = plan.apiArray("items")[0].jsonObject
        assertEquals(0, BigDecimal.ZERO.compareTo(item.getValue("display").jsonObject.apiText("amount").toBigDecimal()))
        assertEquals(JsonPrimitive(true), item["detail_available"])
        val receipt = f.commit(client, plan).apiBody()
        assertEquals(4, receipt.getValue("summary").jsonObject.apiNumber("created_posted"))
        assertEquals(1, receipt.getValue("summary").jsonObject.apiNumber("relations_confirmed"))
        val rows = f.get(client, receiptRoot(receipt) + "/rows").apiBody(); assertEquals(4, rows.apiArray("items").size)
        assertEquals(1, rows.apiArray("projection_refs").map { it.jsonObject.apiText("projection_id") }.toSet().size)
    }

    @Test fun unselectedAccountsSaveDraftsAndInvalidRowPatchDoesNotPublish() = testApplication {
        val f = CashImportOriginalApiFixture("draft")
        application { financeModule(f.database) }
        val view = f.upload(client); val noAccounts = JsonArray(emptyList())
        val draft = f.plan(client, view, buildJsonObject { put("account_selections", noAccounts) })
        assertEquals(2, draft.getValue("item_counts").jsonObject.apiNumber("pending"))
        assertTrue(draft.apiArray("items").all { it.jsonObject["statistics_eligible"] == JsonPrimitive(false) })
        val row = draft.apiArray("items")[0].jsonObject.apiArray("member_refs")[0].jsonObject.apiText("row_ref")
        val invalid = f.preview(client, revised(view, draft), repreview(draft, "account_selections" to noAccounts,
            "row_choices" to buildJsonArray { add(buildJsonObject {
                put("row_ref", row); put("action", "apply"); put("patch", buildJsonObject { put("manual_overrides", buildJsonObject { put("note", true) }) })
            }) }))
        assertEquals(422, invalid.status.value); assertEquals("plan_input_invalid", invalid.apiBody().apiError())
        val checked = f.plan(client, revised(view, draft), repreview(draft, "account_selections" to noAccounts))
        assertEquals(2, checked.getValue("summary").jsonObject.apiNumber("create_draft"))
        val receipt = f.commit(client, checked).apiBody()
        assertEquals(2, receipt.getValue("summary").jsonObject.apiNumber("saved_draft"))
        assertEquals(0, receipt.getValue("summary").jsonObject.apiNumber("skipped"))
    }

    @Test fun oldCursorAndOtherOperatorCannotReadOrCommit() = testApplication {
        val f = CashImportOriginalApiFixture("foreign")
        application { financeModule(f.database) }
        val plan = f.plan(client, f.upload(client)); val cursor = f.get(client, root(plan) + "/items?limit=1").apiBody().apiText("next_cursor")
        assertEquals(422, f.get(client, root(plan) + "/processing-details?cursor=$cursor").status.value)
        assertEquals(422, f.get(client, root(plan) + "/items?cursor=$cursor&confirmation_status=pending").status.value)
        f.role("editor", "original-import-other")
        assertEquals(404, f.get(client, root(plan), "original-import-other").status.value)
        assertEquals(404, f.commit(client, plan, token = "original-import-other").status.value)
        val receipt = f.commit(client, plan).apiBody()
        assertEquals(404, f.get(client, receiptRoot(receipt), "original-import-other").status.value)
        assertEquals(404, f.get(client, "/api/v1/cash-mutations/receipts/by-key/synthetic-api-confirm", "original-import-other").status.value)
    }

    @Test fun duplicateOnlyCanConfirmAndFixedAmountConflictIsFailedWithoutBusinessWrites() = testApplication {
        val f = CashImportOriginalApiFixture("duplicate")
        application { financeModule(f.database) }
        val first = f.commit(client, f.plan(client, f.upload(client))).apiBody()
        assertEquals(1, first.getValue("summary").jsonObject.apiNumber("created_posted"))
        val duplicate = f.plan(client, f.upload(client)); assertEquals(JsonArray(emptyList()), duplicate["items"])
        assertEquals(JsonPrimitive(true), duplicate["can_commit"])
        val receipt = f.commit(client, duplicate, "duplicate-confirm").apiBody()
        assertEquals("no_changes", receipt.apiText("status")); assertEquals(1, receipt.getValue("summary").jsonObject.apiNumber("duplicate"))
        val conflict = f.plan(client, f.upload(client, "conflict")); assertEquals(JsonArray(emptyList()), conflict["items"])
        assertEquals(1, conflict.getValue("summary").jsonObject.apiNumber("skip"))
        val failed = f.commit(client, conflict, "conflict-confirm").apiBody()
        assertEquals("failed", failed.apiText("status")); assertEquals(1, failed.getValue("summary").jsonObject.apiNumber("skipped"))
        assertEquals(buildJsonObject { put("has_new_failures", false); put("has_business_writes", false); put("reason_codes", JsonArray(emptyList())) }, failed["commit_outcome"])
        assertEquals("fixed_field_conflict", f.get(client, receiptRoot(failed) + "/rows").apiBody().apiArray("items")[0].jsonObject.apiText("code"))
    }

    @Test fun permissionsAreRecheckedBeforeRepreviewAndReceiptReplay() = testApplication {
        val f = CashImportOriginalApiFixture("permission")
        application { financeModule(f.database) }
        val view = f.upload(client); val plan = f.plan(client, view); val receipt = f.commit(client, plan).apiBody()
        f.role("viewer")
        assertEquals(403, f.commit(client, plan).status.value); assertEquals(403, f.preview(client, view).status.value)
        assertEquals(200, f.get(client, receiptRoot(receipt)).status.value)
    }

    @Test fun fileStatusAndUnknownCountMatchTheUploadedSession() = testApplication {
        val f = CashImportOriginalApiFixture("file_status")
        application { financeModule(f.database) }
        val view = f.upload(client); val plan = f.plan(client, view); assertEquals(JsonPrimitive(true), plan["can_commit"])
        val files = view.apiArray("files"); val shown = plan.apiArray("file_results"); assertEquals(files.size, shown.size)
        for (index in files.indices) for (key in listOf("file_ref", "status", "recognized_row_count")) {
            assertEquals(files[index].jsonObject.getValue(key), shown[index].jsonObject.getValue(key), "$index $key")
        }
        assertEquals(JsonNull, shown[1].jsonObject["recognized_row_count"])
    }

    @Test fun failedSubsetRetryEndpointStaysRemoved() = testApplication {
        val f = CashImportOriginalApiFixture("removed")
        application { financeModule(f.database) }
        val receipt = f.commit(client, f.plan(client, f.upload(client))).apiBody()
        val response = f.post(client, "/api/v1/cash-import/receipts/${receipt.apiText("receipt_id")}/retry-sessions",
            "{\"row_refs\":null}", key = "synthetic-removed-retry")
        assertEquals(404, response.status.value); assertFalse(receipt.keys.any { it.startsWith("retry_context") })
    }
}
