package com.finance.tracker

import com.finance.tracker.data.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class KotlinMaintenancePlanResponseIntegrationTest {
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
