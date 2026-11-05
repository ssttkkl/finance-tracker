package com.finance.tracker

import com.finance.tracker.data.*
import com.finance.tracker.domain.CashAssociationSearch
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class KotlinAssociationResponseIntegrationTest {
    @Test fun actualAssociationPageUsesCurrentRepositoryAndKeepsReadOnlyFields() = runTest {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")))
        val capture = Json.parseToJsonElement(Files.readString(root.resolve("association-response.json"))).jsonObject
        assertTrue(capture.getValue("backend").jsonPrimitive.content in setOf("sqlite", "postgresql"))
        var calls = 0
        val engine = MockEngine { request ->
            calls++; assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/v1/cash-records/1101/association-targets", request.url.encodedPath)
            assertEquals("原始账单备注", request.url.parameters["query"]); assertEquals("100.00", request.url.parameters["amount"])
            assertNotNull(request.url.parameters["timezone_name"])
            respond(capture.getValue("body").toString(), HttpStatusCode.fromValue(capture.getValue("status").jsonPrimitive.int), headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = FinanceApiClient({ "https://api.example.invalid" }, CategoryTokenStore(), engine)
        try {
            val result = KtorCashMaintenanceRepository(client).associationTargets("1101", CashAssociationSearch(query = "原始账单备注", amount = "100.00"))
            val expected = capture.getValue("body").jsonObject.getValue("items").jsonArray.single().jsonObject
            val record = result.items.single()
            assertEquals("1102", record.transactionId); assertEquals(expected.getValue("row_revision").jsonPrimitive.long, record.rowRevision)
            assertEquals(expected.getValue("fields").toStructuredValue(), record.fields)
            assertEquals(emptyList(), record.editableFields); assertEquals(emptyList(), record.issues); assertEquals(emptyList(), record.relations)
            assertNull(record.entry); assertNull(record.projectionRef); assertNull(result.nextCursor); assertEquals(1, calls)
        } finally { client.close() }
    }
}
