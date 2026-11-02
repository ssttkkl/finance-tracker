package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

internal class FinanceQueryFixture(val group: String) {
    val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_FINANCE_HTTP_DIR")))
    val config = Json.parseToJsonElement(root.resolve(group).resolve("database.json").readText()).jsonObject
    val workspace = config["workspace"]!!.jsonPrimitive.content
    fun database(decorate: (Connection) -> Connection = { it }) = Database(config["jdbc_url"]!!.jsonPrimitive.content,
        System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"), decorate)
    fun provider(downloads: MutableList<String>): QuoteProvider = CompositeQuoteProvider(security = SecurityQuoteProvider(download = { value, _ ->
        val symbol = value as String; downloads.add(symbol)
        if (symbol == "AAPL") ProviderTick("5.00", "USD", null, "fixture") else null
    }))
    fun snapshot(database: Database = database()) = database.read { connection -> DefaultSchema.DEFAULT_SCHEMA.tables.associate { table ->
        table.name to connection.createStatement().use { statement -> statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
            buildList { while (rows.next()) add(JsonArray((1..rows.metaData.columnCount).map { rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
        } }
    } }
    fun expected(path: String, month: String? = null): JsonElement {
        val cases = Json.parseToJsonElement(root.resolve("cases.json").readText()).jsonArray
        val expected = cases.map { it.jsonObject }.single { it["path"] == JsonPrimitive(path) &&
            it["headers"]!!.jsonObject["Authorization"] == JsonPrimitive("Bearer finance-original-owner") &&
            (if (month == null) it["params"] == JsonNull else it["params"] == buildJsonObject { put("month", month) }) }
        return expected["body"]!!
    }
}

class FinanceQueryHttpTest {
    @Test fun actualOriginalHttpBodiesStatusesQuotesAndNoWritesMatch() {
        val fixture = FinanceQueryFixture("main"); val database = fixture.database(); val downloads = CopyOnWriteArrayList<String>()
        val service = FinanceQueryService(database, quoteProvider = fixture.provider(downloads))
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { financeModule(database, financeQueries = service) }.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().single().port }
        try {
        val cases = Json.parseToJsonElement(fixture.root.resolve("cases.json").readText()).jsonArray
        assertEquals(80, cases.size)
        val before = fixture.snapshot()
        for (raw in cases) {
            val case = raw.jsonObject; downloads.clear()
            val params = when (val value = case["params"]) {
                is JsonArray -> value.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content }
                is JsonObject -> value.map { it.key to it.value.jsonPrimitive.content }
                else -> emptyList()
            }
            val query = params.joinToString("&") { (key, value) -> java.net.URLEncoder.encode(key, Charsets.UTF_8) + "=" + java.net.URLEncoder.encode(value, Charsets.UTF_8) }
            val url = java.net.URI.create("http://127.0.0.1:$port" + case["path"]!!.jsonPrimitive.content + if (query.isEmpty()) "" else "?$query")
            val response = url.toURL().openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
            response.connectTimeout = 10000; response.readTimeout = 10000
            val status: Int; val text: String
            try {
                case["headers"]!!.jsonObject.forEach { (key, value) -> response.setRequestProperty(key, value.jsonPrimitive.content) }
                status = response.responseCode
                text = (if (status >= 400) response.errorStream else response.inputStream).use { it?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty() }
            } finally { response.disconnect() }
            val label = "${case["path"]} ${case["params"]} ${case["headers"]}"
            assertEquals(case["status"]!!.jsonPrimitive.int, status, "$label $text")
            val expected = case["body"]!!
            if (expected is JsonPrimitive && expected.isString) assertEquals(expected.content, text, label)
            else assertEquals(expected, Json.parseToJsonElement(text), label)
            assertEquals(case["downloads"]!!.jsonArray.map { it.jsonPrimitive.content }, downloads.toList(), label)
            assertEquals(before, fixture.snapshot(), label)
        }
        } finally { server.stop(0, 5000) }
    }
}
