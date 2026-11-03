package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class SyncHttpTest {
    private fun database(decorate: (java.sql.Connection) -> java.sql.Connection = { it }): Database {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")), "database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), decorateConnection = decorate)
    }
    @Test fun missingCredentialsAreSafeAndDeniedRolesDoNotReachFactory() = testApplication {
        val db = database(); val calls = mutableListOf<String>()
        val factory = InvestmentConnectorFactory(credentials = { provider -> calls.add(provider); throw IllegalArgumentException("Missing /private/server/credentials.yaml synthetic-secret") })
        application { financeModule(db, syncOperations = SyncHttpService(db, factory = factory::create)) }
        val denied = client.post("/api/v1/operations/sync") { header(HttpHeaders.Authorization, "Bearer synthetic-viewer"); setBody("broken") }
        assertEquals(403, denied.status.value); assertTrue(calls.isEmpty())
        for (provider in listOf("kraken", "binance", "okx", "polymarket")) {
            val response = client.post("/api/v1/operations/sync") {
                header(HttpHeaders.Authorization, "Bearer synthetic-admin"); contentType(ContentType.Application.Json)
                setBody("{\"source\":\"$provider\",\"account\":\"Broker\"}")
            }
            assertEquals(400, response.status.value)
            assertEquals(Json.parseToJsonElement("""{"error":{"code":"sync_configuration_invalid","message":"同步配置无效，请联系管理员。"}}"""), Json.parseToJsonElement(response.bodyAsText()))
        }
        assertEquals(listOf("kraken", "binance", "okx", "polymarket"), calls)
    }

    @Test fun originalPythonInputTypesDefaultsAndTruthiness() {
        val service = SyncHttpService(database())
        val cases = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_SYNC_HTTP_INPUTS"))).readText()).jsonArray
        assertEquals(54, cases.size)
        for (item in cases) {
            val row = item.jsonObject; val raw = row.getValue("raw").jsonPrimitive.content
            if (row.getValue("status").jsonPrimitive.int == 200) {
                val parsed = service.parse(raw.toByteArray())
                val forwarded = row.getValue("forwarded").jsonArray.single().jsonObject
                assertEquals(forwarded.getValue("provider").jsonPrimitive.content, parsed.provider, raw)
                assertEquals(forwarded.getValue("account_name").jsonPrimitive.content, parsed.account, raw)
                assertEquals(forwarded.getValue("full").jsonPrimitive.boolean, parsed.full, raw)
                val size = java.math.BigInteger(forwarded.getValue("batch_size").jsonPrimitive.content).min(java.math.BigInteger.valueOf(Int.MAX_VALUE.toLong())).toInt()
                assertEquals(size, parsed.batchSize, raw)
            } else {
                val failure = assertFails { service.parse(raw.toByteArray()) }
                val error = row.getValue("body").jsonObject.getValue("error").jsonObject
                if (failure is SyncConfigurationFailure) assertEquals("sync_configuration_invalid", error.getValue("code").jsonPrimitive.content, raw)
                else {
                    assertIs<SyncHttpFailure>(failure); assertEquals(error.getValue("code").jsonPrimitive.content, failure.code, raw)
                    assertEquals(error.getValue("message").jsonPrimitive.content, failure.message, raw)
                }
            }
        }
    }
    @Test fun malformedDuplicateAndLargeJsonFieldsMatchOriginalDecoder() {
        val service = SyncHttpService(database())
        assertEquals("kraken", service.parse("""{"source":"okx","source":"kraken","account":"Broker"}""".toByteArray()).provider)
        assertEquals("Expecting property name enclosed in double quotes: line 1 column 2 (char 1)",
            assertFailsWith<SyncHttpFailure> { service.parse("{".toByteArray()) }.code)
        assertEquals(Int.MAX_VALUE, service.parse("""{"source":"kraken","account":"Broker","batch_size":999999999999999999999999999999999999}""".toByteArray()).batchSize)
        for (raw in listOf("NaN", "Infinity", "-Infinity", "1e0")) assertFailsWith<SyncConfigurationFailure> {
            service.parse("""{"source":"kraken","account":"Broker","batch_size":$raw}""".toByteArray())
        }
    }

    @Test fun originalAdminProjectionAndUnsupportedSyncAssertions() = testApplication {
        val db = database(); var calls = 0
        val serviceReads = java.util.concurrent.atomic.AtomicInteger()
        val service = SyncService(database { serviceReads.incrementAndGet(); error("sync must not access storage") })
        val factory = InvestmentConnectorFactory(credentials = { calls++; error("unreachable") })
        application { financeModule(db, syncOperations = SyncHttpService(db, service = service, factory = factory::create)) }
        assertEquals(200, client.get("/api/v1/operations/projections") { header(HttpHeaders.Authorization, "Bearer synthetic-admin") }.status.value)
        assertEquals(200, client.post("/api/v1/operations/projections/rebuild") { header(HttpHeaders.Authorization, "Bearer synthetic-admin") }.status.value)
        val response = client.post("/api/v1/operations/sync") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin"); contentType(ContentType.Application.Json)
            setBody("""{"source":"unsupported","account":"Broker"}""")
        }
        assertEquals(400, response.status.value)
        assertEquals("sync_configuration_invalid", Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals(0, calls)
        assertEquals(0, serviceReads.get())
        val schema = Json.parseToJsonElement(client.get("/openapi.json").bodyAsText()).jsonObject
        assertTrue("post" in schema.getValue("paths").jsonObject.getValue("/api/v1/operations/sync").jsonObject)
    }
    @Test fun unexpectedAndStorageErrorsKeepSafeHttpBoundaries() = testApplication {
        val db = database()
        application { financeModule(db, syncOperations = SyncHttpService(db, factory = { source ->
            if (source == "storage") throw StorageFailure("storage.connect")
            else error("synthetic-secret /private/server/credentials.yaml")
        })) }
        for ((source, status, code) in listOf(Triple("internal", 502, "sync_failed"), Triple("storage", 503, "storage_unavailable"))) {
            val response = client.post("/api/v1/operations/sync") {
                header(HttpHeaders.Authorization, "Bearer synthetic-admin"); contentType(ContentType.Application.Json)
                setBody("""{"source":"$source","account":"Broker"}""")
            }
            assertEquals(status, response.status.value)
            val body = response.bodyAsText(); assertFalse("synthetic-secret" in body); assertFalse("/private" in body)
            if (source == "internal") assertEquals(code, Json.parseToJsonElement(body).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        }
    }

    @Test fun actualCredentialFilesMatchAllAuthorizedSafetyRegressionParameters() = testApplication {
        val db = database(); val directory = java.nio.file.Files.createTempDirectory("private-server-path-")
        val serviceReads = java.util.concurrent.atomic.AtomicInteger()
        val service = SyncService(database { serviceReads.incrementAndGet(); error("sync must not access storage") })
        var expectedProvider = ""; var reads = 0
        val credentials = ConnectorCredentials(mapOf("FT_CREDENTIALS_DIR" to directory.toString()))
        val factory = InvestmentConnectorFactory(credentials = { provider ->
            assertEquals(expectedProvider, provider); reads++
            if (provider == "polymarket") credentials.polymarket() else credentials.exchange(provider)
        })
        application { financeModule(db, syncOperations = SyncHttpService(db, service = service, factory = factory::create)) }
        try {
            for (provider in listOf("binance", "kraken", "okx", "polymarket")) for (content in listOf<String?>(null, "", "[bad", "[]", "other: {}", "$provider: {}", "$provider: {api_key: synthetic-key}")) {
                expectedProvider = provider
                val path = directory.resolve("credentials.yaml")
                if (content == null) java.nio.file.Files.deleteIfExists(path) else java.nio.file.Files.writeString(path, content)
                val response = client.post("/api/v1/operations/sync") {
                    header(HttpHeaders.Authorization, "Bearer synthetic-admin"); contentType(ContentType.Application.Json)
                    setBody("""{"source":"$provider","account":"Broker"}""")
                }
                assertEquals(400, response.status.value)
                assertEquals(Json.parseToJsonElement("""{"error":{"code":"sync_configuration_invalid","message":"同步配置无效，请联系管理员。"}}"""), Json.parseToJsonElement(response.bodyAsText()))
                assertFalse(directory.toString() in response.bodyAsText()); assertFalse("synthetic-key" in response.bodyAsText())
                assertEquals(0, serviceReads.get())
            }
            assertEquals(28, reads)
        } finally { directory.toFile().deleteRecursively() }
    }
}
