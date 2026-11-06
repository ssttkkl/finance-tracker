package com.finance.tracker.backend

import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.serialization.json.*

class PortfolioValuationLifecycleTest {
    @Test fun ignoredSingleQuoteDoesNotKeepProcessAlive() = exits("single", deadlineSeconds = 1.0)
    @Test fun ignoredPredictionMarketBatchDoesNotKeepProcessAlive() = exits("prediction")
    private fun exits(mode: String, deadlineSeconds: Double = 0.05) {
        val urls = generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<java.net.URLClassLoader>().flatMap { it.urLs.asSequence() }.map { java.nio.file.Path.of(it.toURI()).toString() }.toList()
        val classpath = (urls + System.getProperty("java.class.path").split(java.io.File.pathSeparator)).distinct().joinToString(java.io.File.pathSeparator)
        val executable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(executable, "-cp", classpath, "com.finance.tracker.backend.PortfolioExitProbe", mode, deadlineSeconds.toString()).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(8, TimeUnit.SECONDS), "a provider ignoring cancellation kept the JVM alive")
            val output = process.inputStream.bufferedReader().readText(); assertEquals(0, process.exitValue(), output); assertContains(output, "PORTFOLIO_EXIT_OK"); assertContains(output, "BLOCKING_STARTED")
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
object PortfolioExitProbe {
    @JvmStatic fun main(args: Array<String>) {
        val provider: QuoteProvider = if (args[0] == "single") object : QuoteProvider { override fun rawQuote(identity: String, kind: AssetKind): ProviderTick? { println("BLOCKING_STARTED"); Thread.sleep(60000); return null } }
            else PredictionMarketQuoteProvider(QuoteJsonFetcher { _, _ -> println("BLOCKING_STARTED"); Thread.sleep(60000); JsonArray(emptyList()) })
        val ticker = if (args[0] == "single") "slow.us" else "pm:slow-market:yes"
        val raw = Json.parseToJsonElement("""{"accounts":{"Broker":{"currency":"USD","positions":{"$ticker":{"shares":"1","total_cost":"1","cost_currency":"USD"}}}},"base_currencies":{"Broker":["USD"]},"configured_currencies":["USD"]}""").jsonObject
        val result = PortfolioQueryService(object : PortfolioRepository { override fun loadPortfolio() = raw }, ValuationService(provider), queryDeadlineSeconds = args[1].toDouble()).getPortfolio()
        check(result.getValue("accounts").jsonArray.single().jsonObject.getValue("positions").jsonArray.single().jsonObject["quote_status"] == JsonPrimitive("partial"))
        println("PORTFOLIO_EXIT_OK")
    }
}
