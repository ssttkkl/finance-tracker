package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class FinanceQueryOriginalTest {
    private fun service(): FinanceQueryApplication {
        val clock = QuoteTime.parse("2026-07-25T00:00:00+00:00")
        val valuation = ValuationService(object : QuoteProvider {
            override fun rawQuote(identity: String, kind: AssetKind): ProviderTick {
                if (identity == "aapl.us") return ProviderTick("5", "USD", clock, "fake")
                throw UnsupportedQuote(identity)
            }
        }, clock = { clock })
        return FinanceQueryApplication(accounts = { listOf(FinanceQueryAccount("Cash", "cash"), FinanceQueryAccount("Broker", "security")) },
            snapshot = { Json.parseToJsonElement("""{"accounts":{"cash":{"Cash":{"CNY":12.34}},"security":{"Broker":{"currency":"USD","positions":{"usd":{"shares":10,"total_cost":10},"aapl.us":{"shares":2,"total_cost":6},"missing.us":{"shares":3,"total_cost":9}}}}}}""").jsonObject },
            valuation = valuation)
    }
    @Test fun listAccountsValuesCashAndInvestmentsWithOriginalCostFallback() {
        val accounts = service().listAccounts()["accounts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Cash" to "12.34".toBigDecimal(), "Broker" to "29".toBigDecimal()), accounts.map {
            it["name"]!!.jsonPrimitive.content to it["balance"]!!.jsonPrimitive.content.toBigDecimal()
        })
    }
    @Test fun queryApplicationAndDtosInitializeWithoutHomeLookups() {
        val urls = generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<java.net.URLClassLoader>()
            .flatMap { it.urLs.asSequence() }.map { java.nio.file.Path.of(it.toURI()).toString() }.toList()
        val classpath = (urls + System.getProperty("java.class.path").split(java.io.File.pathSeparator)).distinct().joinToString(java.io.File.pathSeparator)
        val executable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(executable, "-Djava.security.manager=allow", "-cp", classpath,
            "com.finance.tracker.backend.FinanceQueryNoHomeProbe").apply {
            environment().remove("HOME"); environment().keys.removeIf { it.startsWith("FT_") }
            redirectErrorStream(true)
        }.start()
        try {
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "isolated query initialization timed out")
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.exitValue(), output); assertTrue("NO_HOME_FINANCE_QUERY_OK" in output, output)
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

}
