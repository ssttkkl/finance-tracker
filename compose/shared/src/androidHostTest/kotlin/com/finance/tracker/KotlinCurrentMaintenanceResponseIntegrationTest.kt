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

class KotlinCurrentMaintenanceResponseIntegrationTest {
    private fun request(raw: JsonObject) = CashMaintenanceRequest(
        intent = raw.getValue("intent").jsonPrimitive.content, transactionId = raw.getValue("transaction_id").jsonPrimitive.content,
        expectedRevision = raw.getValue("expected_revision").jsonPrimitive.long,
        patch = raw.getValue("patch").jsonObject.toStructuredObject(),
        relationChoices = raw["relation_choices"]?.jsonArray.orEmpty().map { value -> val choice = value.jsonObject
            CashRelationDecision(choice.getValue("suggestion_id").jsonPrimitive.content, choice.getValue("action").jsonPrimitive.content,
                choice["candidate_id"]?.jsonPrimitive?.contentOrNull, choice["common_fields"]?.jsonObject?.toStructuredObject(),
                choice["inherit_common_fields"]?.jsonPrimitive?.booleanOrNull)
        },
        editContextId = raw["edit_context_id"]?.jsonPrimitive?.contentOrNull,
        previousPlanId = raw["previous_plan_id"]?.jsonPrimitive?.contentOrNull,
        expectedPlanRevision = raw["expected_plan_revision"]?.jsonPrimitive?.longOrNull,
        relationAdditions = raw["relation_additions"]?.jsonArray.orEmpty().map { value -> val addition = value.jsonObject
            CashRelationAddition(addition.getValue("target_transaction_id").jsonPrimitive.content, addition.getValue("expected_target_revision").jsonPrimitive.long,
                addition.getValue("anchor_component_id").jsonPrimitive.content, addition.getValue("target_component_id").jsonPrimitive.content,
                addition.getValue("kind").jsonPrimitive.content,
                addition["common_fields"]?.jsonObject?.toStructuredObject())
        },
    )
    @Test fun actualCurrentRelationPlansAndReceiptsUseExistingRepositoryAndDomainMapping() = runTest {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")))
        val captures = Json.parseToJsonElement(Files.readString(root.resolve("current-maintenance-response.json"))).jsonArray
        assertEquals(setOf("joint_http", "manual_http"), captures.map { it.jsonObject.getValue("label").jsonPrimitive.content }.toSet())
        for (value in captures) {
            val capture = value.jsonObject; val previews = capture.getValue("previews").jsonArray
            val plan = capture.getValue("plan").jsonObject; val receipt = capture.getValue("receipt").jsonObject
            var index = 0; var committed = false
            val engine = MockEngine { call ->
                val path = call.url.encodedPath
                val body = when {
                    path.endsWith("/plans") -> {
                        assertEquals(HttpMethod.Post, call.method)
                        val step = previews[index++].jsonObject
                        val actual = Json.parseToJsonElement((call.body as TextContent).text).jsonObject
                        for ((key, expected) in step.getValue("request").jsonObject) {
                            if (expected == JsonNull) assertTrue(actual[key] == null || actual[key] == JsonNull, key)
                            else if (expected == JsonArray(emptyList())) assertEquals(expected, actual[key] ?: JsonArray(emptyList()), key)
                            else assertEquals(expected, actual[key], key)
                        }
                        step.getValue("body")
                    }
                    path.endsWith("/commit") -> {
                        assertEquals(HttpMethod.Post, call.method); assertEquals(capture.getValue("key").jsonPrimitive.content, call.headers["Idempotency-Key"])
                        assertEquals(plan.getValue("plan_revision"), Json.parseToJsonElement((call.body as TextContent).text).jsonObject["expected_plan_revision"])
                        committed = true; receipt
                    }
                    path.contains("/items/") -> capture.getValue("items").jsonArray.single { it.jsonObject.getValue("preview_item_id").jsonPrimitive.content == path.substringAfterLast('/') }
                    else -> { assertEquals(HttpMethod.Get, call.method); plan }
                }
                respond(body.toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val client = FinanceApiClient({ "https://api.example.invalid" }, CategoryTokenStore(), engine)
            try {
                val repository = KtorCashMaintenanceRepository(client)
                for (step in previews) {
                    val result = repository.preview(request(step.jsonObject.getValue("request").jsonObject))
                    assertEquals(step.jsonObject.getValue("body").jsonObject.getValue("plan_id").jsonPrimitive.content, result.planId)
                }
                val current = repository.plan(plan.getValue("plan_id").jsonPrimitive.content)
                assertTrue(current.canCommit); assertTrue(current.items.isNotEmpty())
                for (item in capture.getValue("items").jsonArray) {
                    val details = repository.item(current.planId, item.jsonObject.getValue("preview_item_id").jsonPrimitive.content)
                    assertEquals(item.jsonObject.getValue("members").jsonArray.size, details.members.size)
                    assertTrue(details.relationChoices.isNotEmpty())
                }
                val result = repository.commit(ImportFrozenCommand(current.planId, current.revision, capture.getValue("key").jsonPrimitive.content))
                assertTrue(result.committed); assertEquals("mutation", result.operationKind); assertEquals(1, result.summary["relations_confirmed"])
                assertEquals(receipt.getValue("receipt_id").jsonPrimitive.content, result.receiptId); assertTrue(committed); assertEquals(previews.size, index)
            } finally { client.close() }
        }
    }
}
