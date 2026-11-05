package com.finance.tracker

import com.finance.tracker.data.*
import com.finance.tracker.data.mapping.toStructuredObject
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class KotlinDraftSaveResponseIntegrationTest {
    @Test fun actualDraftSaveResponseUsesExistingRepositoryAndDomainMapping() = runTest {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")))
        val capture = Json.parseToJsonElement(Files.readString(root.resolve("draft-save-response.json"))).jsonObject
        assertTrue(capture.getValue("backend").jsonPrimitive.content in setOf("sqlite", "postgresql"))
        val input = capture.getValue("request").jsonObject
        val path = capture.getValue("path").jsonPrimitive.content
        val id = path.substringAfter("/cash-records/").substringBefore("/")
        val revision = input.getValue("expected_revision").jsonPrimitive.long
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            assertEquals(HttpMethod.Patch, request.method)
            assertEquals(path, request.url.encodedPath)
            assertEquals(capture.getValue("key").jsonPrimitive.content, request.headers["Idempotency-Key"])
            assertEquals(input, Json.parseToJsonElement((request.body as TextContent).text))
            respond(capture.getValue("body").toString(), HttpStatusCode.fromValue(capture.getValue("status").jsonPrimitive.int), headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = FinanceApiClient({ "https://api.example.invalid" }, CategoryTokenStore(), engine)
        try {
            val result = KtorCashMaintenanceRepository(client).saveDraft(id, revision,
                input.getValue("patch").jsonObject.toStructuredObject(), capture.getValue("key").jsonPrimitive.content)
            assertTrue(result.receipt.committed)
            assertEquals("save_draft", result.receipt.operationKind)
            assertEquals(mapOf("saved_draft" to 1L), result.receipt.summary)
            assertEquals("available", result.currentRecordStatus)
            val record = assertNotNull(result.currentRecord)
            assertEquals(id, record.transactionId)
            assertEquals("draft", record.state)
            assertEquals(revision + 1, record.rowRevision)
            assertEquals(1, calls)
        } finally { client.close() }
    }
}
