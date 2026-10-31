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

class KotlinImportCommitResponseIntegrationTest {
    private fun captures(): JsonArray {
        val root = System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")
        assumeTrue("Actual Ktor captures are required; skip is not acceptance", root != null)
        val captured = Json.parseToJsonElement(Files.readString(Path.of(requireNotNull(root), "cash-import-commit-responses.json"))).jsonObject
        assertTrue(captured.getValue("backend").jsonPrimitive.content in setOf("sqlite", "postgresql"))
        return captured.getValue("responses").jsonArray
    }

    @Test fun actualConfirmationsPreserveReceiptsFailuresAndDiagnosticsWithoutRetry() = runTest {
        var successes = 0
        var failures = 0
        for (source in captures().map { it.jsonObject }.filter { "body" in it }) {
            val plan = source.getValue("plan_id").jsonPrimitive.content
            val revision = source.getValue("revision").jsonPrimitive.long
            val key = source.getValue("key").jsonPrimitive.content
            val status = source.getValue("status").jsonPrimitive.int
            val body = source.getValue("body").jsonObject
            var calls = 0
            val engine = MockEngine { request ->
                calls++
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("/api/v1/cash-import/plans/$plan/commit", request.url.encodedPath)
                assertEquals(key, request.headers["Idempotency-Key"])
                assertEquals(buildJsonObject { put("expected_session_revision", revision) }, Json.parseToJsonElement((request.body as TextContent).text))
                respond(body.toString(), HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val client = FinanceApiClient({ "https://api.example.invalid" }, CategoryTokenStore(), engine)
            try {
                val repository = KtorCashImportLifecycleRepository(client)
                if (status == 200) {
                    val receipt = repository.confirm(plan, revision, key)
                    assertEquals(body.getValue("receipt_id").jsonPrimitive.content, receipt.receiptId)
                    assertEquals(plan, receipt.planId)
                    assertEquals("import", receipt.operationKind)
                    assertTrue(receipt.committed)
                    assertEquals(body.getValue("status").jsonPrimitive.content, receipt.status)
                    assertEquals(body.getValue("summary").jsonObject.mapValues { it.value.jsonPrimitive.long }, receipt.summary)
                    assertEquals(body.getValue("commit_outcome").jsonObject.getValue("has_business_writes").jsonPrimitive.boolean, receipt.commitOutcome?.hasBusinessWrites)
                    successes++
                } else {
                    val failure = assertFailsWith<DomainFailure> { repository.confirm(plan, revision, key) }
                    assertEquals(status, failure.status)
                    assertEquals(body.getValue("error").jsonObject.getValue("code").jsonPrimitive.content, failure.code)
                    assertEquals(body.getValue("commit_state").jsonPrimitive.content, failure.commitState)
                    body.getValue("error").jsonObject["diagnostic"]?.jsonObject?.let { expected ->
                        val diagnostic = assertNotNull(failure.diagnostic)
                        assertEquals(expected.getValue("stage").jsonPrimitive.content, diagnostic.stage)
                        assertEquals(expected.getValue("scope").jsonPrimitive.content, diagnostic.scope)
                        assertEquals(expected.getValue("total_locations").jsonPrimitive.int, diagnostic.totalLocations)
                        assertEquals(expected.getValue("locations").jsonArray.size, diagnostic.locations.size)
                    }
                    failures++
                }
                assertEquals(1, calls)
            } finally { client.close() }
        }
        assertEquals(10, successes)
        assertEquals(10, failures)
    }

    @Test fun actualResolveAndResultPageUseExistingComposeConsumer() = runTest {
        val source = captures().map { it.jsonObject }.single { "resolve" in it }
        val receiptId = source.getValue("receipt_id").jsonPrimitive.content
        val resolve = source.getValue("resolve").jsonObject
        val receipt = resolve.getValue("receipt").jsonObject
        val plan = receipt.getValue("plan_id").jsonPrimitive.content
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            val body = if (request.method == HttpMethod.Post) {
                assertEquals("/api/v1/cash-mutations/resolve", request.url.encodedPath)
                assertEquals(buildJsonObject { put("plan_id", plan); put("idempotency_key", "commit-normal") }, Json.parseToJsonElement((request.body as TextContent).text))
                resolve
            } else {
                assertEquals("/api/v1/cash-mutations/receipts/$receiptId/rows", request.url.encodedPath)
                assertEquals("0", request.url.parameters["after"])
                assertEquals("200", request.url.parameters["limit"])
                source.getValue("row_page")
            }
            respond(body.toString(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = FinanceApiClient({ "https://api.example.invalid" }, CategoryTokenStore(), engine)
        try {
            val repository = KtorCashImportLifecycleRepository(client)
            val resolved = repository.resolve(plan, "commit-normal")
            assertEquals("committed", resolved.status)
            assertEquals(receiptId, resolved.receipt?.receiptId)
            val result = repository.results(receiptId, ImportResultKind.ROW, "0", 200)
            val expected = source.getValue("row_page").jsonObject
            assertEquals(expected.getValue("items").jsonArray.map { it.jsonObject.getValue("outcome").jsonPrimitive.content }, result.items.map { it.outcome })
            assertEquals(expected.getValue("projection_version").jsonPrimitive.long, result.projectionVersion)
            assertEquals(2, calls)
        } finally { client.close() }
    }
}
