package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.engine.*
import io.ktor.server.netty.Netty
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.*
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.slf4j.Logger

class CashPlanProtocolHttpOriginalTest {
    private fun database(): Database {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")), "database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    @Test fun actualMaintenanceRouteRejectsReceivedBytesOverOriginalPlanBudgetBeforeBusinessRead() = testApplication {
        val db = database(); val before = detailDatabaseState(db)
        application { financeModule(db) }
        val response = client.post("/api/v1/cash-mutations/plans") {
            bearerAuth("synthetic-admin")
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Application.Json
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    channel.writeStringUtf8("""{"intent":"save_draft","transaction_id":"9223372036854775807","expected_revision":1,"patch":{}}""")
                    val chunk = ByteArray(1024) { 32 }
                    repeat(65536) { channel.writeFully(chunk) }
                }
            })
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
        assertEquals(JsonPrimitive("import_payload_invalid"), Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject["code"])
        assertEquals(before, detailDatabaseState(db))
    }
    @Test fun entryTargetRefusalExposesOnlyAuthoritativeNoCommitState() = testApplication {
        application {
            financeModule(database())
            routing { get("/protocol-error/{state}") {
                throw CashImportPlanError("plan_changed").also { it.commitState = call.parameters["state"].takeUnless { state -> state == "absent" } }
            } }
        }
        for (state in listOf("absent", "not_committed", "unknown", "committed")) {
            val response = client.get("/protocol-error/$state")
            assertEquals(HttpStatusCode.Conflict, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            if (state == "not_committed") assertEquals(JsonPrimitive("not_committed"), body["commit_state"]) else assertFalse("commit_state" in body)
        }
    }
    @Test fun maintenanceRejectsNonfiniteAndNonObjectJsonBeforeBusinessCalls() = testApplication {
        val db = database(); val before = detailDatabaseState(db)
        application { financeModule(db) }
        val bodies = listOf("NaN", "Infinity", "1e999", "-1e999").map {
            """{"intent":"save_draft","transaction_id":"1","expected_revision":1,"patch":{"source_payload":{"value":$it}}}"""
        } + listOf("[]", "null")
        for (body in bodies) {
            val response = client.post("/api/v1/cash-mutations/plans") { bearerAuth("synthetic-admin"); contentType(ContentType.Application.Json); setBody(body) }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertEquals(JsonPrimitive("import_payload_invalid"), Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject["code"])
            assertEquals(before, detailDatabaseState(db))
        }
    }
    @Test fun maintenanceCommitChecksOriginalKeyBeforeReadingMalformedBody() = testApplication {
        val db = database(); val before = detailDatabaseState(db)
        application { financeModule(db) }
        val response = client.post("/api/v1/cash-mutations/plans/unused/commit") {
            bearerAuth("synthetic-admin"); contentType(ContentType.Application.Json); setBody("{")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals(JsonPrimitive("idempotency_key_required"), Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject["code"])
        assertEquals(before, detailDatabaseState(db))
    }
    @Test fun actualNettyReceiptLookupNeverLogsRawKeyOrPrivateQuery() {
        val db = database(); val before = detailDatabaseState(db)
        val messages = Collections.synchronizedList(mutableListOf<String>())
        val logger = Proxy.newProxyInstance(Logger::class.java.classLoader, arrayOf(Logger::class.java)) { _, method, args ->
            when {
                method.name == "getName" -> "native-protocol-compatibility"
                method.name.startsWith("is") -> true
                method.name in setOf("trace", "debug", "info", "warn", "error") -> {
                    messages += args.orEmpty().joinToString(" ") { if (it is Array<*>) it.joinToString(" ") else it.toString() }; null
                }
                else -> null
            }
        } as Logger
        val server = embeddedServer(Netty, environment = applicationEnvironment { log = logger }, configure = {
            connector { host = "127.0.0.1"; port = 0 }; configureFinanceHttp()
        }) { financeModule(db) }.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().single().port }
            val (status, _) = rawHttpGet(port, "/api/v1/cash-mutations/receipts/by-key/private-key?private=value")
            assertEquals(404, status)
        } finally { server.stop(100, 1000) }
        assertTrue(messages.isNotEmpty(), "Logger must observe real engine lifecycle events")
        assertTrue(messages.none { "private" in it }, messages.toString())
        assertEquals(before, detailDatabaseState(db))
    }
}
