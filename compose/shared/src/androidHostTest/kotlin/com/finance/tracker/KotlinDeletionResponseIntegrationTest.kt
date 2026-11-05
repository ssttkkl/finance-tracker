package com.finance.tracker

import com.finance.tracker.data.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class KotlinDeletionResponseIntegrationTest {
    @Test fun actualDeletionResponsesUseExistingRepositoryAndDomainMapping() = runTest {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")))
        val impact = Json.parseToJsonElement(Files.readString(root.resolve("deletion-impact-response.json"))).jsonObject
        val deletion = Json.parseToJsonElement(Files.readString(root.resolve("deletion-response.json"))).jsonObject
        assertEquals(impact["backend"], deletion["backend"])
        assertTrue(impact.getValue("backend").jsonPrimitive.content in setOf("sqlite", "postgresql"))
        val firstVersion = impact.getValue("request").jsonObject.getValue("projection_version").jsonPrimitive.long
        val commitVersion = deletion.getValue("request").jsonObject.getValue("projection_version").jsonPrimitive.long
        assertEquals(firstVersion + 1, commitVersion)
        var calls = 0
        val engine = MockEngine { request ->
            calls++; val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val capture = if (calls == 1) {
                assertEquals(HttpMethod.Post, request.method); assertEquals("/api/v1/cash-projections/delete-impact", request.url.encodedPath)
                assertEquals(JsonArray(listOf(JsonPrimitive("cash:1003"))), body["projection_ids"]); assertEquals(JsonPrimitive(firstVersion), body["projection_version"])
                assertFalse("confirmed" in body); impact
            } else {
                assertEquals(HttpMethod.Delete, request.method); assertEquals("/api/v1/cash-projections", request.url.encodedPath)
                assertEquals(JsonArray(listOf(JsonPrimitive("cash:1002"))), body["projection_ids"]); assertEquals(JsonPrimitive(commitVersion), body["projection_version"])
                assertEquals(JsonPrimitive(true), body["confirmed"]); deletion
            }
            respond(capture.getValue("body").toString(), HttpStatusCode.fromValue(capture.getValue("status").jsonPrimitive.int), headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = FinanceApiClient({ "https://api.example.invalid" }, CategoryTokenStore(), engine)
        try {
            val repository = KtorFinanceRepository(client)
            val preview = repository.fetchCashProjectionDeleteImpact(listOf("cash:1003"), firstVersion)
            assertEquals(1, preview.projectionCount); assertEquals(1, preview.transactionCount); assertEquals(0, preview.relationGroupCount)
            val result = repository.deleteCashProjections(listOf("cash:1002"), commitVersion)
            assertTrue(result.deleted); assertEquals(1, result.projectionCount); assertEquals(1, result.transactionCount); assertEquals(0, result.relationGroupCount)
            assertEquals(commitVersion + 1, result.projectionVersion); assertEquals(2, calls)
        } finally { client.close() }
    }
}
