package com.finance.tracker

import com.finance.tracker.data.*
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
import org.junit.Assume.assumeTrue

class KotlinImportPreviewResponseIntegrationTest {
    @Test fun actualKtorPreviewsAndFrozenDetailsUseExistingComposeConsumerWithoutRetry() = runTest {
        val root = System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")
        assumeTrue("Actual Ktor captures are required; skip is not acceptance", root != null)
        val captured = Json.parseToJsonElement(Files.readString(Path.of(requireNotNull(root), "cash-import-preview-responses.json"))).jsonObject
        assertTrue(captured.getValue("backend").jsonPrimitive.content in setOf("sqlite", "postgresql"))
        val sessionId = captured.getValue("session_id").jsonPrimitive.content
        var successes = 0
        var failures = 0
        for (raw in captured.getValue("responses").jsonArray) {
            val source = raw.jsonObject
            val request = source.getValue("request").jsonObject
            val input = Json.decodeFromJsonElement<CashImportPreviewInput>(request)
            val choices = ImportChoiceSet(
                accountSelections = input.accountSelections.map { ImportAccountSelection(it.sourceAccountRef, ImportAccountTarget(it.accountId, it.expectedAccountRevision)) },
                entries = input.entryChoices.map { ImportEntryChoice(it.editRef, it.patch.toStructuredObject()) })
            val status = source.getValue("status").jsonPrimitive.int
            val text = source.getValue("body").jsonPrimitive.content
            var calls = 0
            val engine = MockEngine { actual ->
                calls++
                assertEquals(HttpMethod.Post, actual.method)
                assertEquals("/api/v1/cash-import/sessions/$sessionId/plans", actual.url.encodedPath)
                assertEquals(request, Json.parseToJsonElement((actual.body as TextContent).text))
                respond(text, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val client = FinanceApiClient({ "https://api.example.invalid" }, CategoryTokenStore(), engine)
            try {
                val repository = KtorCashImportLifecycleRepository(client)
                if (status == 200) {
                    val plan = repository.preview(sessionId, input.expectedSessionRevision, input.previousPlanId, choices)
                    val body = Json.parseToJsonElement(text).jsonObject
                    assertEquals(body.getValue("plan_id").jsonPrimitive.content, plan.planId)
                    assertEquals(input.expectedSessionRevision + 1, plan.sessionRevision)
                    assertEquals("-12.30", plan.items.single().display.amount)
                    assertTrue(plan.canCommit)
                    assertEquals(1, plan.files.size)
                    assertEquals(body.getValue("items").jsonArray.single().jsonObject.getValue("display").jsonObject.getValue("note").jsonPrimitive.content,
                        plan.items.single().display.note)
                    successes++
                } else {
                    val failure = assertFailsWith<DomainFailure> { repository.preview(sessionId, input.expectedSessionRevision, input.previousPlanId, choices) }
                    assertEquals(422, failure.status)
                    assertEquals("category_unavailable", failure.code)
                    failures++
                }
                assertEquals(1, calls)
            } finally { client.close() }
        }
        val detail = captured.getValue("detail").jsonObject
        val detailId = detail.getValue("preview_item_id").jsonPrimitive.content
        var detailCalls = 0
        val engine = MockEngine { request ->
            detailCalls++
            assertEquals(HttpMethod.Get, request.method)
            assertTrue(request.url.encodedPath.endsWith("/items/$detailId"))
            respond(detail.toString(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = FinanceApiClient({ "https://api.example.invalid" }, CategoryTokenStore(), engine)
        try {
            val result = KtorCashImportLifecycleRepository(client).item("frozen-plan", detailId)
            assertEquals(detailId, result.itemId)
            assertEquals(1, result.members.size)
            assertNotNull(result.entry?.editRef)
            assertEquals(1, detailCalls)
        } finally { client.close() }
        assertEquals(2, successes)
        assertEquals(1, failures)
    }
}
