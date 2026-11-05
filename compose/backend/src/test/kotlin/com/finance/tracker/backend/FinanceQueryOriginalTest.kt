package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class FinanceQueryOriginalTest {
    private fun service(rows: List<JsonObject> = emptyList()): FinanceQueryApplication {
        val clock = QuoteTime.parse("2026-07-25T00:00:00+00:00")
        val valuation = ValuationService(object : QuoteProvider {
            override fun rawQuote(identity: String, kind: AssetKind): ProviderTick {
                if (identity == "aapl.us") return ProviderTick("5", "USD", clock, "fake")
                throw UnsupportedQuote(identity)
            }
        }, clock = { clock })
        return FinanceQueryApplication(accounts = { listOf(FinanceQueryAccount("Cash", "cash"), FinanceQueryAccount("Broker", "security")) },
            snapshot = { Json.parseToJsonElement("""{"accounts":{"cash":{"Cash":{"CNY":12.34}},"security":{"Broker":{"currency":"USD","positions":{"usd":{"shares":10,"total_cost":10},"aapl.us":{"shares":2,"total_cost":6},"missing.us":{"shares":3,"total_cost":9}}}}}}""").jsonObject },
            transactions = { month -> rows.filter { month == null || it["occurred_at"]!!.jsonPrimitive.content.startsWith(month) }.sortedByDescending { it["occurred_at"]!!.jsonPrimitive.content } }, valuation = valuation)
    }
    private fun rows(raw: String) = Json.parseToJsonElement(raw).jsonArray.map { it.jsonObject }
    @Test fun listAccountsValuesCashAndInvestmentsWithOriginalCostFallback() {
        val accounts = service().listAccounts()["accounts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Cash" to "12.34".toBigDecimal(), "Broker" to "29".toBigDecimal()), accounts.map {
            it["name"]!!.jsonPrimitive.content to it["balance"]!!.jsonPrimitive.content.toBigDecimal()
        })
    }
    @Test fun reportReturnsStructuredTotalsAndIgnoresPreCheckinExpenses() {
        val input = rows("""[
          {"occurred_at":"2026-06-01 09:00:00","account_name":"Cash","currency":"CNY","record_type":"consumption","amount":"-100","note":"old"},
          {"occurred_at":"2026-06-02 09:00:00","account_name":"Cash","currency":"CNY","record_type":"other","amount":"0","note":"余额校准1000"},
          {"occurred_at":"2026-06-03 09:00:00","account_name":"Cash","currency":"CNY","record_type":"consumption","amount":"-12.50","note":"meal"},
          {"occurred_at":"2026-06-04 09:00:00","account_name":"Cash","currency":"CNY","record_type":"income","amount":"20","note":"gift"},
          {"occurred_at":"2026-05-01 09:00:00","account_name":"Cash","currency":"CNY","record_type":"transfer_out","amount":"-5","note":"Broker"}
        ]""")
        val result = service(input).report("2026-06")
        assertEquals(buildJsonObject { put("CNY", "12.50") }, result["expenses"])
        assertEquals(buildJsonObject { put("CNY", "20") }, result["income"])
        val flow = result["flows"]!!.jsonArray.first().jsonObject
        assertEquals(JsonPrimitive("Broker"), flow["note"]); assertEquals("5".toBigDecimal(), flow["amount"]!!.jsonPrimitive.content.toBigDecimal())
        val cash = result["accounts"]!!.jsonObject["accounts"]!!.jsonArray.first().jsonObject
        assertEquals("12.34".toBigDecimal(), cash["balance"]!!.jsonPrimitive.content.toBigDecimal())
    }
    @Test fun reportUsesRecordTypeInsteadOfUserCategory() {
        val input = rows("""[
          {"occurred_at":"2026-06-01 09:00:00","account_name":"Cash","currency":"CNY","category_id":"income-label","category":"expense","record_type":"income","amount":"20","note":"gift"},
          {"occurred_at":"2026-06-02 09:00:00","account_name":"Cash","currency":"CNY","category_id":"expense-label","category":"income","record_type":"consumption","amount":"-12.50","note":"meal"},
          {"occurred_at":"2026-06-03 09:00:00","account_name":"Cash","currency":"CNY","category_id":"transfer-label","category":"income","record_type":"transfer_out","amount":"-5","note":"Broker"}
        ]""")
        val app = FinanceQueryApplication(accounts = { listOf(FinanceQueryAccount("Cash", "cash")) },
            snapshot = { Json.parseToJsonElement("""{"accounts":{"cash":{"Cash":{"CNY":"0"}}}}""").jsonObject }, transactions = { input })
        val result = app.report("2026-06")
        assertEquals(buildJsonObject { put("CNY", "12.50") }, result["expenses"])
        assertEquals(buildJsonObject { put("CNY", "20") }, result["income"])
        val flow = result["flows"]!!.jsonArray.first().jsonObject
        assertEquals(JsonPrimitive("Broker"), flow["note"]); assertEquals("5".toBigDecimal(), flow["amount"]!!.jsonPrimitive.content.toBigDecimal())
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
