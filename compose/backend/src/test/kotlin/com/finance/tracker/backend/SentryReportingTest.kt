package com.finance.tracker.backend

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import io.ktor.client.request.get
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class SentryReportingTest {
    @Test
    fun envelopeContainsOnlySafeExceptionFields() {
        val envelopes = LinkedBlockingQueue<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/12/envelope/") { request ->
            val input = if (request.requestHeaders.getFirst("Content-Encoding") == "gzip")
                GZIPInputStream(request.requestBody) else request.requestBody
            envelopes.add(input.bufferedReader().use { it.readText() })
            request.sendResponseHeaders(200, -1)
            request.close()
        }
        server.start()
        val temp = Files.createTempDirectory("sentry-http")
        try {
            assertNull(BackendSentry.create(null, null))
            assertNull(BackendSentry.create("http://publickey@sentry.example.invalid/12", null))
            val release = "0123456789abcdef0123456789abcdef01234567"
            val reporter = BackendSentry.create("http://localtest@127.0.0.1:${server.address.port}/12", release)
            assertNotNull(reporter).use {
                val database = Database("jdbc:sqlite:${temp.resolve("http.db")}").also { db -> db.bootstrapSchema() }
                testApplication {
                    application {
                        financeModule(database, sentry = reporter)
                        routing {
                            get("/mock/{id}") { error("sentinel-user@example.invalid bearer secret") }
                        }
                    }
                    val response = client.get("/mock/sentinel-path?value=sentinel-query")
                    assertEquals(500, response.status.value)
                    val id = assertNotNull(response.headers["X-Request-ID"])
                    val sent = assertNotNull(envelopes.poll(10, TimeUnit.SECONDS))
                    assertContains(sent, "IllegalStateException")
                    assertContains(sent, "envelopeContainsOnlySafeExceptionFields")
                    assertContains(sent, release)
                    assertContains(sent, id)
                    assertContains(sent, "/mock/{id}")
                    assertFalse(listOf("sentinel-user", "sentinel-path", "sentinel-query", "bearer secret").any { it in sent })
                    assertFalse("\"infer_ip\":\"auto\"" in sent)
                    val event = Json.parseToJsonElement(sent.lineSequence().drop(2).first()).jsonObject
                    val forbidden = event.keys.intersect(setOf("request", "user", "breadcrumbs", "server_name"))
                    assertEquals(emptySet(), forbidden)
                    assertEquals(emptySet(), event["contexts"]?.jsonObject?.keys.orEmpty())
                }
            }
        } finally {
            server.stop(0)
            temp.toFile().deleteRecursively()
        }
    }
}
