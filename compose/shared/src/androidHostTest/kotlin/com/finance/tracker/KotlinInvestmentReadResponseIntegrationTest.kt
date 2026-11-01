package com.finance.tracker

import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class KotlinInvestmentReadResponseIntegrationTest {
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_INVESTMENT_READ_CONTRACT_DIR")))
    private fun captured(name: String): String {
        val config = Json.parseToJsonElement(root().resolve("database.json").readText()).jsonObject
        val jdbc = config.getValue("jdbc_url").jsonPrimitive.content
        assertTrue(jdbc.startsWith("jdbc:sqlite:") || jdbc.startsWith("jdbc:postgresql:"))
        return root().resolve(name).readText()
    }
    @Test fun actualInvestmentPageUsesExistingRepositoryAndPreservesExactNullableValues() = runTest {
        val body = captured("native-page-response.json")
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/v1/investment-events", request.url.encodedPath)
            assertNotNull(request.url.parameters["timezone"])
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = FinanceApiClient({ "https://api.example.invalid" }, CategoryTokenStore(), engine)
        try {
            val result = KtorFinanceRepository(client).fetchInvestmentPage(InvestmentFilters(), null)
            assertEquals(1L, result.dataVersion)
            assertEquals(50, result.pageSize)
            assertNull(result.nextCursor)
            assertEquals(7, result.items.size)
            assertEquals(StructuredText("UTC"), result.filters["timezone"])
            val trade = result.items.single { it.recordId == "investment-003" }
            assertEquals("fixture:investment-003", trade.eventId)
            assertEquals("1011.530000000000000000", trade.fromAsset.amount)
            assertEquals("10.000000000000000001", trade.toAsset.amount)
            assertEquals("11.530000000000000000", trade.commission.amount)
            assertEquals("2026-07-03T09:00:00+00:00", trade.occurredAt)
            assertFalse(result.items.single { it.recordId == "inactive" }.account.active)
            val nullable = result.items.single { it.recordId == "empty-source" }
            assertNull(nullable.fromAsset.amount); assertNull(nullable.toAsset.amount); assertNull(nullable.commission.amount)
            assertEquals("", nullable.sourceType)
            val funding = result.items.single { it.recordId == "investment-001" }.relations.single()
            assertEquals("accepted", funding.status)
            assertEquals("2000", funding.cashAmount)
            assertEquals("日常账户", funding.cashAccount.name)
            assertEquals(StructuredNumber("1"), funding.evidence.fields["candidate_count"])
            assertEquals(1, calls)
        } finally { client.close() }
    }

    @Test fun actualInvestmentEvidenceUsesExistingRepositoryAndStructuredRedactedSource() = runTest {
        val body = captured("native-evidence-response.json")
        assertFalse("SYNTHETIC_SECRET" in body)
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/v1/evidence/investment-events/fixture%3Ainvestment-003", request.url.encodedPath)
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = FinanceApiClient({ "https://api.example.invalid" }, CategoryTokenStore(), engine)
        try {
            val result = KtorFinanceRepository(client).fetchInvestmentEvidence("fixture:investment-003")
            assertEquals(1L, result.dataVersion)
            assertEquals("fixture:investment-003", result.event.eventId)
            assertEquals("10.000000000000000001", result.event.toAsset.amount)
            assertEquals(emptyList(), result.relations)
            val source = assertNotNull(result.sourceSnapshot).fields
            assertEquals(setOf("action", "quantity", "fee", "fees"), source.keys)
            assertEquals(StructuredText("BUY"), source["action"])
            assertEquals(StructuredNumber("17"), source["quantity"])
            assertEquals(StructuredBoolean(false), source["fee"])
            assertEquals(StructuredArray(listOf(StructuredText("1"), StructuredText("True"), StructuredText("ordinary"))), source["fees"])
            assertEquals(1, calls)
        } finally { client.close() }
    }
}
