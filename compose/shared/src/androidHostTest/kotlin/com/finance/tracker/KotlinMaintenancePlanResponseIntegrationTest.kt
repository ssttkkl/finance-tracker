package com.finance.tracker

import com.finance.tracker.data.*
import com.finance.tracker.data.mapping.toStructuredObject
import com.finance.tracker.domain.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class KotlinMaintenancePlanResponseIntegrationTest {
    @Test fun actualSourceIssueChoicesPendingBodyManualValuesAndReceiptUseExistingRepository() = runTest {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")))
        val captures = Json.parseToJsonElement(Files.readString(root.resolve("source-issue-response.json"))).jsonArray
        assertEquals(4, captures.size)
        for (raw in captures) {
            val capture = raw.jsonObject; val input = capture.getValue("request").jsonObject
            val plan = capture.getValue("plan").jsonObject; var committed = false; var previewed = false
            val engine = MockEngine { call ->
                val path = call.url.encodedPath
                val body = when {
                    path.startsWith("/api/v1/cash-records/") -> { assertEquals(HttpMethod.Get, call.method); capture.getValue(if (committed) "after" else "before") }
                    path.endsWith("/plans") -> {
                        assertEquals(HttpMethod.Post, call.method)
                        val sent = Json.parseToJsonElement((call.body as TextContent).text).jsonObject
                        for (field in listOf("intent", "transaction_id", "expected_revision", "patch", "issue_resolutions")) assertEquals(input[field], sent[field], field)
                        previewed = true; plan
                    }
                    path.contains("/items/") -> { assertEquals(HttpMethod.Get, call.method); capture.getValue("item") }
                    path.endsWith("/commit") -> {
                        assertEquals(HttpMethod.Post, call.method); assertEquals(capture.getValue("key").jsonPrimitive.content, call.headers["Idempotency-Key"])
                        assertEquals(plan["plan_revision"], Json.parseToJsonElement((call.body as TextContent).text).jsonObject["expected_plan_revision"])
                        committed = true; capture.getValue("receipt")
                    }
                    else -> error("unexpected $path")
                }
                respond(body.toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val client = FinanceApiClient({ "https://api.example.invalid" }, CategoryTokenStore(), engine)
            try {
                val repository = KtorCashMaintenanceRepository(client)
                val identifier = input.getValue("transaction_id").jsonPrimitive.content
                val before = repository.record(identifier); val issue = before.issues.single()
                assertEquals(listOf("apply_source", "keep_existing"), issue.allowedResolutions)
                assertEquals(true, issue.pendingSourcePayloadPresent)
                assertEquals(JsonPrimitive("来源新原文"), issue.pendingSourcePayload!!.toJsonObject()["备注"])
                val request = CashMaintenanceRequest(input.getValue("intent").jsonPrimitive.content, identifier,
                    input.getValue("expected_revision").jsonPrimitive.long, input.getValue("patch").jsonObject.toStructuredObject(),
                    issueResolutions = input.getValue("issue_resolutions").jsonArray.map { value ->
                        val choice = value.jsonObject
                        CashIssueResolution(choice.getValue("issue_id").jsonPrimitive.content,
                            choice.getValue("expected_issue_revision").jsonPrimitive.long, choice.getValue("resolution").jsonPrimitive.content)
                    })
                val current = repository.preview(request); assertTrue(previewed); assertTrue(current.canCommit)
                assertEquals(1, current.summary["resolve_issue"])
                val detail = repository.item(current.planId, current.items.single().itemId)
                assertEquals(capture["expected_note"], detail.entry!!.fields.toJsonObject()["note"])
                val receipt = repository.commit(ImportFrozenCommand(current.planId, current.revision, capture.getValue("key").jsonPrimitive.content))
                assertTrue(committed); assertTrue(receipt.committed); assertEquals(1, receipt.summary["resolved_issues"])
                assertEquals(capture.getValue("receipt").jsonObject.getValue("receipt_id").jsonPrimitive.content, receipt.receiptId)
                val after = repository.record(identifier); assertTrue(after.issues.isEmpty())
                assertEquals(capture["expected_note"], after.entry!!.fields.toJsonObject()["note"])
                assertEquals(JsonPrimitive("来源新原文"), after.fields.toJsonObject()["note"])
            } finally { client.close() }
        }
    }
    @Test fun actualMaintenancePlanPagingItemAndReceiptDecode() = runTest {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")))
        val plans = Json.parseToJsonElement(Files.readString(root.resolve("mutation-plan-client.json")))
            .jsonObject.getValue("plans").jsonArray
        assertEquals(2, plans.size)
        var sawPosted = false
        var sawDraft = false
        for (raw in plans) {
            val fixture = raw.jsonObject
            val view = fixture.getValue("view").jsonObject
            val planId = view.getValue("plan_id").jsonPrimitive.content
            val items = fixture.getValue("items").jsonObject
            val item = fixture.getValue("item")
            val receipt = Json.parseToJsonElement(Files.readString(root.resolve("mutation-receipt-client.json"))).jsonObject
            val engine = MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/commit") -> respond(receipt.toString(),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    path.endsWith("/items/pi_1") -> respond(item.toString(),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    path.endsWith("/items") -> respond(buildJsonObject {
                        put("items", view.getValue("items")); put("next_cursor", view.getValue("pages").jsonObject.getValue("items"))
                    }.toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    path.contains("/plans/") -> respond(view.toString(),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    else -> error("unexpected $path")
                }
            }
            val client = FinanceApiClient({ "https://api.example.invalid" }, CategoryTokenStore(), engine)
            try {
                val plan = client.readCashMaintenancePlan(planId)
                assertEquals(view.getValue("plan_revision").jsonPrimitive.long, plan.planRevision)
                assertEquals(view.getValue("summary").jsonObject,
                    JsonObject(plan.summary.mapValues { JsonPrimitive(it.value) }))
                assertEquals(view.getValue("items").jsonArray.size, plan.items.size)
                val page = client.readCashMaintenanceItems(planId)
                assertEquals(view.getValue("items").jsonArray.size, page.items.size)
                val detail = client.readCashMaintenanceItem(planId, "pi_1")
                assertEquals("pi_1", detail.previewItemId)
                val committed = client.commitCashMaintenance(planId, 1, "client-commit")
                assertEquals(receipt.getValue("receipt_id").jsonPrimitive.content, committed.receiptId)
                assertEquals(receipt.getValue("summary").jsonObject.getValue("updated_posted").jsonPrimitive.long,
                    committed.summary["updated_posted"] ?: 0L)
                if (view.getValue("items").jsonArray.any { it.jsonObject.getValue("anticipated_state").jsonPrimitive.content == "posted" })
                    sawPosted = true
                if (view.getValue("items").jsonArray.any { it.jsonObject.getValue("anticipated_state").jsonPrimitive.content == "draft" })
                    sawDraft = true
            } finally { client.close() }
        }
        assertTrue(sawPosted); assertTrue(sawDraft)
    }
}
