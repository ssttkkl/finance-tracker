package com.finance.tracker

import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import com.finance.tracker.domain.Portfolio
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.coroutines.*

class KotlinPortfolioLiveIntegrationTest {
    @Test fun actualBackendGetRefreshAndSseUseCurrentComposeConsumer() = runBlocking {
        val origin = Path.of(requireNotNull(System.getenv("FT_KOTLIN_PORTFOLIO_HOST_FILE"))).readText()
        assertTrue(origin.startsWith("http://127.0.0.1:"))
        val token = object : TokenStore {
            override suspend fun get() = "portfolio-owner"
            override suspend fun set(value: String) = Unit
            override suspend fun clear() = Unit
        }
        val client = FinanceApiClient({ origin }, token, allowInsecureHttp = true)
        try {
            val repository = KtorFinanceRepository(client)
            val holdings = repository.fetchInvestmentPortfolio(null, "24h", "holdings")
            val local = holdings.accounts.single().positions.single { it.ticker == "AAPL.US" }
            assertEquals("10.000000000000000001", local.shares); assertNull(local.currentPrice)
            val valuation = repository.fetchInvestmentPortfolio("CNY", "24h", "valuation")
            val valued = valuation.accounts.single().positions.single { it.ticker == "AAPL.US" }
            assertEquals("11.00", valued.currentPrice); assertEquals("110.00000000000000001100", valued.marketValue)
            repository.refreshInvestmentPortfolio("CNY", "24h")
            val delivered = CompletableDeferred<Portfolio>(); val failure = CompletableDeferred<Unit>()
            val stream = launch(Dispatchers.IO) { repository.streamInvestmentPortfolio("CNY", "24h", { delivered.complete(it) }, { failure.complete(Unit) }) }
            try {
                val actual = withTimeout(5000) { delivered.await() }
                assertEquals(valued, actual.accounts.single().positions.single { it.ticker == "AAPL.US" }); assertFalse(failure.isCompleted)
            } finally { stream.cancelAndJoin() }
            val invalid = launch(Dispatchers.IO) { repository.streamInvestmentPortfolio(null, "invalid", { error("invalid scope published portfolio") }, { failure.complete(Unit) }) }
            try { withTimeout(5000) { failure.await() } } finally { invalid.cancelAndJoin() }
        } finally { client.close() }
    }
}
