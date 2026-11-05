package com.finance.tracker.backend

import io.github.ccxt.exchanges.Kraken
import io.github.ccxt.types.Trade
import java.math.BigDecimal
import java.math.BigInteger
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import io.github.ccxt.errors.AuthenticationError
import io.github.ccxt.errors.NetworkError
import java.util.concurrent.CompletableFuture
import kotlin.test.*
import kotlinx.serialization.json.*

class CcxtSdkClientTest {
    private fun options() = mapOf("apiKey" to "synthetic-key", "secret" to "c3ludGhldGljLXNlY3JldA==", "enableRateLimit" to false)
    @Test fun defaultSdkClientOwnsAndClosesHttpResources() {
        val client = CcxtSdkClient("kraken", mapOf("api_key" to "synthetic-key", "api_secret" to "c3ludGhldGljLXNlY3JldA=="))
        client.close(); client.close()
        assertFailsWith<IllegalStateException> { client.trades(null, 500) }
    }
    @Test fun completeTypedMapPreservesNumbersUnknownFieldsNullsAndCopiesContainers() {
        val nested = linkedMapOf<String, Any?>("null" to null, "large" to BigInteger("123456789012345678901234567890"),
            "decimal" to BigDecimal("1.2345"), "float" to -0.0, "array" to listOf(null, true, 2.0))
        val raw = linkedMapOf<String, Any?>("id" to "t", "extra" to nested)
        val exchange = object : Kraken(options()) {
            override fun fetchMyTrades(symbol: String?, since: Long?, limit: Long?, parameters: Map<String, Any>?): CompletableFuture<List<Trade>> =
                CompletableFuture.completedFuture(listOf(Trade(raw)))
        }
        CcxtSdkClient(exchange).use { client ->
            val result = client.trades(null, 500).single()
            val extra = result.getValue("extra").jsonObject
            assertFalse(extra.getValue("decimal").jsonPrimitive.isString)
            assertEquals("1.2345", extra.getValue("decimal").jsonPrimitive.content)
            assertEquals(JsonNull, extra["null"])
            assertEquals("123456789012345678901234567890", extra.getValue("large").jsonPrimitive.content)
            assertEquals("-0.0", extra.getValue("float").jsonPrimitive.content)
            assertEquals(Json.parseToJsonElement("[null,true,2.0]"), extra["array"])
            nested.clear(); raw.clear()
            assertEquals(5, extra.size); assertEquals("t", result.getValue("id").jsonPrimitive.content)
        }
    }

    @Test fun externalErrorsUnwrapOnlyFutureWrappersAndProgramErrorsKeepIdentity() {
        val errors = listOf(AuthenticationError("denied"), NetworkError("offline"), IOException("connection"),
            IllegalStateException("bug"), CancellationException("cancelled"), AssertionError("broken invariant"))
        for (error in errors) for (synchronous in listOf(false, true)) {
            val sdk = object : Kraken(options()) {
                override fun fetchMyTrades(symbol: String?, since: Long?, limit: Long?, parameters: Map<String, Any>?): CompletableFuture<List<Trade>> {
                    if (synchronous) throw error
                    return CompletableFuture.failedFuture(CompletionException(ExecutionException(error)))
                }
            }
            CcxtSdkClient(sdk).use { client ->
                val actual = assertFails { client.trades(null, 500) }
                if (error is io.github.ccxt.errors.BaseError || error is IOException) {
                    assertIs<CcxtCallFailure>(actual)
                    assertEquals(error.javaClass.simpleName, actual.originalType); assertEquals(error.message, actual.message)
                } else assertSame(error, actual)
            }
            assertTrue(sdk.httpClient.isTerminated)
        }
    }
    @Test fun exactTimestampBoundsRejectOverflowBeforeCallingSdk() {
        val received = mutableListOf<Long?>()
        val sdk = object : Kraken(options()) {
            override fun fetchMyTrades(symbol: String?, since: Long?, limit: Long?, parameters: Map<String, Any>?): CompletableFuture<List<Trade>> {
                received.add(since); return CompletableFuture.completedFuture(emptyList())
            }
        }
        CcxtSdkClient(sdk).use { client ->
            for (value in listOf(Long.MIN_VALUE, -62135596800000L, 253402300799999L, Long.MAX_VALUE)) client.trades(BigInteger.valueOf(value), 500)
            assertFailsWith<ArithmeticException> { client.trades(BigInteger.valueOf(Long.MAX_VALUE) + BigInteger.ONE, 500) }
            assertFailsWith<ArithmeticException> { client.ledger(BigInteger.valueOf(Long.MIN_VALUE) - BigInteger.ONE, 500, emptyMap()) }
            assertEquals(listOf<Long?>(Long.MIN_VALUE, -62135596800000L, 253402300799999L, Long.MAX_VALUE), received)
        }
    }
    @Test fun unsupportedObjectsAndNonStringKeysAreNotSilentlyStringified() {
        for (value in listOf(Any(), mapOf(1 to "value"))) {
            val sdk = object : Kraken(options()) {
                override fun fetchMyTrades(symbol: String?, since: Long?, limit: Long?, parameters: Map<String, Any>?): CompletableFuture<List<Trade>> =
                    CompletableFuture.completedFuture(listOf(Trade(mapOf("extra" to value))))
            }
            CcxtSdkClient(sdk).use { assertFailsWith<IllegalStateException> { it.trades(null, 500) } }
        }
    }

    @Test fun nonFiniteSdkValuesReachOriginalMapperValidation() {
        for (field in listOf("amount", "price", "timestamp")) for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val row = linkedMapOf<String, Any>("id" to "t", "timestamp" to 1000L, "symbol" to "BTC/USD", "side" to "buy",
                "amount" to 1.0, "price" to 1.0, "cost" to 1.0)
            row[field] = value
            val sdk = object : Kraken(options()) {
                override fun fetchMyTrades(symbol: String?, since: Long?, limit: Long?, parameters: Map<String, Any>?): CompletableFuture<List<Trade>> =
                    CompletableFuture.completedFuture(listOf(Trade(row)))
            }
            CcxtSdkClient(sdk).use { client ->
                val parsed = client.trades(null, 500).single()
                val connector = CcxtExchangeConnector("kraken", client)
                if (field == "timestamp" && value.isInfinite()) {
                    assertEquals("cannot convert float infinity to integer", assertFailsWith<ArithmeticException> { connector.mapTrade(parsed) }.message)
                } else {
                    val failure = assertFailsWith<ConnectorDataFailure> { connector.mapTrade(parsed) }
                    assertEquals(if (field == "timestamp") "invalid trade timestamp: nan" else "non-finite $field: ${pythonFloatText(value)}", failure.message)
                }
            }
        }
    }
}
