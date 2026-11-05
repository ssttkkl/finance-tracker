package com.finance.tracker.backend

import io.github.ccxt.Exchange
import io.github.ccxt.errors.BaseError
import io.github.ccxt.exchanges.Binance
import io.github.ccxt.exchanges.Kraken
import io.github.ccxt.exchanges.Okx
import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

internal class CcxtSdkClient internal constructor(private val exchange: Exchange) : CcxtClient, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val http = CcxtHttpClient(exchange.httpClient).also { exchange.httpClient = it }
    constructor(provider: String, credentials: Map<String, String>) : this(create(provider, credentials))

    private fun fail(cause: Throwable): Nothing {
        var error = cause
        while ((error is CompletionException || error is ExecutionException) && error.cause != null) error = error.cause!!
        when (error) {
            is BaseError, is IOException -> throw CcxtCallFailure(error.javaClass.simpleName, error.message.orEmpty())
            else -> throw error
        }
    }
    private fun <T> await(call: () -> CompletableFuture<T>): T {
        check(!closed.get()) { "CCXT client is closed" }
        val future = try { call() } catch (error: BaseError) { fail(error) } catch (error: IOException) { fail(error) }
        return try { future.get() }
        catch (error: InterruptedException) {
            future.cancel(true)
            try { close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            finally { Thread.currentThread().interrupt() }
            throw error
        } catch (error: ExecutionException) { fail(error) }
    }
    override fun trades(since: BigInteger?, limit: Int): List<JsonObject> {
        val timestamp = since?.longValueExact()
        return await { exchange.fetchMyTrades(null, timestamp, limit.toLong(), mutableMapOf()) }.map { json(it).jsonObject }
    }
    override fun ledger(since: BigInteger?, limit: Int, params: Map<String, Int>): List<JsonObject> {
        val timestamp = since?.longValueExact()
        return await { exchange.fetchLedger(null, timestamp, limit.toLong(), params.mapValues { it.value as Any }.toMutableMap()) }.map { json(it).jsonObject }
    }
    override fun close() {
        val interrupted = Thread.interrupted()
        try {
            if (closed.compareAndSet(false, true)) {
                try {
                    http.shutdownNow()
                    exchange.close(true).join()
                } finally {
                    exchange.apiKey = null; exchange.secret = null; exchange.password = null
                }
            }
            check(http.awaitTermination(Duration.ofSeconds(5))) { "CCXT HTTP client did not terminate" }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
    companion object {
        private fun create(provider: String, credentials: Map<String, String>): Exchange {
            val options = linkedMapOf<String, Any>("apiKey" to credentials.getValue("api_key"), "secret" to credentials.getValue("api_secret"))
            credentials["password"]?.takeIf { it.isNotEmpty() }?.let { options["password"] = it }
            return when (provider) {
                "binance" -> Binance(options)
                "kraken" -> Kraken(options)
                "okx" -> Okx(options)
                else -> throw IllegalArgumentException("sync_provider_invalid")
            }
        }
        private fun json(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Double, is Float -> sourceJson(value)
            is Byte, is Short, is Int, is Long, is BigInteger, is BigDecimal -> JsonUnquotedLiteral(value.toString())
            is List<*> -> JsonArray(value.map(::json))
            is Map<*, *> -> JsonObject(value.entries.associate { (key, value) ->
                check(key is String) { "CCXT returned a non-string field name" }
                key to json(value)
            })
            else -> error("CCXT returned an unsupported value type")
        }
    }
}
