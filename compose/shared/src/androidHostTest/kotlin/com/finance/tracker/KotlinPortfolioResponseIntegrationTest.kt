package com.finance.tracker

import com.finance.tracker.data.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class KotlinPortfolioResponseIntegrationTest {
    @Test fun capturedHoldingsValuationAndStreamUseCurrentDtoAndRepository() = runTest {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_PORTFOLIO_CONTRACT_DIR")))
        var phase = "holdings"
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method); assertEquals("/api/v1/investment-portfolio", request.url.encodedPath)
            assertEquals(phase, request.url.parameters["phase"])
            respond(root.resolve(if (phase == "holdings") "native-response-1.json" else "native-response-2.json").readText(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = FinanceApiClient({ "https://example.invalid" }, CategoryTokenStore(), engine)
        try {
            val repository = KtorFinanceRepository(client)
            val holdings = repository.fetchInvestmentPortfolio(null, "24h", phase)
            val position = holdings.accounts.single().positions.single { it.ticker == "AAPL.US" }
            assertEquals("10.000000000000000001", position.shares); assertNull(position.currentPrice)
            phase = "valuation"
            val valued = repository.fetchInvestmentPortfolio("CNY", "24h", phase)
            val quoted = valued.accounts.single().positions.single { it.ticker == "AAPL.US" }
            assertEquals("11.00", quoted.currentPrice); assertEquals("110.00000000000000001100", quoted.marketValue)
            val frames = kotlinx.serialization.json.Json.parseToJsonElement(root.resolve("native-frames.json").readText()) as kotlinx.serialization.json.JsonArray
            val frame = (frames.first() as kotlinx.serialization.json.JsonPrimitive).content
            val data = frame.lineSequence().first { it.startsWith("data: ") }.removePrefix("data: ")
            val decoded = assertNotNull(decodeInvestmentPortfolioEvent("portfolio", data))
            assertEquals(quoted.shares, decoded.accounts.single().positions.single { it.ticker == "AAPL.US" }.shares)
            assertNull(decodeInvestmentPortfolioEvent("refresh_error", "{}"))
        } finally { client.close() }
    }

    @Test fun acceptedRefreshResponseUsesExistingRepositoryWithoutFalseFailure() = runTest {
        val body = Path.of(requireNotNull(System.getenv("FT_KOTLIN_PORTFOLIO_CONTRACT_DIR")), "native-response-26.json").readText()
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method); assertEquals("/api/v1/investment-portfolio/refresh", request.url.encodedPath)
            assertEquals("CNY", request.url.parameters["display_currency"]); assertEquals("30d", request.url.parameters["period"])
            respond(body, HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = FinanceApiClient({ "https://example.invalid" }, CategoryTokenStore(), engine)
        try { KtorFinanceRepository(client).refreshInvestmentPortfolio("CNY", "30d") } finally { client.close() }
    }
}
