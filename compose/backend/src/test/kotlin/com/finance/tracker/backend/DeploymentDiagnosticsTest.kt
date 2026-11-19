package com.finance.tracker.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DeploymentDiagnosticsTest {
    @Test
    fun responseIdsAreServerGeneratedAndExposedOnlyToAllowedOrigins() {
        val root = Files.createTempDirectory("ft-diagnostics-test")
        val logger = serverDiagnosticLog as Logger
        val events = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(events)
        try {
            val database = Database("jdbc:sqlite:${root.resolve("diagnostics.db")}").also { it.bootstrapSchema() }
            testApplication {
                application {
                    financeModule(database, "https://web.example")
                    routing {
                        get("/diagnostic-test/{sensitive}") {
                            error("synthetic-secret-exception")
                        }
                        get("/diagnostic-bad-request") {
                            throw BadRequestException("synthetic-secret-bad-request")
                        }
                    }
                }

                val badRequest = client.get("/diagnostic-bad-request")
                assertEquals(HttpStatusCode.BadRequest, badRequest.status)

                val healthy = client.get("/health") {
                    header("Origin", "https://web.example")
                    header("X-Request-ID", "synthetic-client-id")
                }
                assertEquals(HttpStatusCode.OK, healthy.status)
                assertEquals("X-Request-ID", healthy.headers["Access-Control-Expose-Headers"])
                val id = assertNotNull(healthy.headers["X-Request-ID"])
                assertNotEquals("synthetic-client-id", id)
                assertEquals(id, java.util.UUID.fromString(id).toString())

                val unexpected = client.get("/diagnostic-test/synthetic-secret-path?value=synthetic-secret-query") {
                    header("Origin", "https://web.example")
                }
                assertEquals(HttpStatusCode.InternalServerError, unexpected.status)
                assertEquals("X-Request-ID", unexpected.headers["Access-Control-Expose-Headers"])
                assertEquals(1, unexpected.headers.getAll("X-Request-ID")?.size)
                val failureId = assertNotNull(unexpected.headers["X-Request-ID"])
                assertNotEquals(id, failureId)
                assertEquals("""{"error":{"code":"internal_error","message":"系统错误。"}}""",
                    unexpected.bodyAsText())

                val unauthorized = client.get("/api/v1/auth/session") { header("Origin", "https://web.example") }
                assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
                assertNotNull(unauthorized.headers["X-Request-ID"])

                val denied = client.get("/health") { header("Origin", "https://unapproved.example") }
                assertNotNull(denied.headers["X-Request-ID"])
                assertNull(denied.headers["Access-Control-Expose-Headers"])

                val preflight = client.options("/api/v1/auth/login") {
                    header("Origin", "https://web.example")
                    header("Access-Control-Request-Method", "POST")
                    header("Access-Control-Request-Headers", "content-type")
                }
                assertEquals(HttpStatusCode.OK, preflight.status)
                assertNotNull(preflight.headers["X-Request-ID"])

                val logged = events.list.map { it.formattedMessage }
                val related = logged.filter { "request_id=$failureId" in it }
                val failure = related.single { it.startsWith("http_failure") }
                val access = related.single { it.startsWith("http_request") }
                assertContains(failure, "route=/diagnostic-test/{sensitive}")
                assertContains(failure, "trace=java.lang.IllegalStateException[")
                assertContains(failure, "DeploymentDiagnosticsTest")
                assertContains(access, "status=500")
                assertFalse(logged.any { "synthetic-secret" in it || "synthetic-client-id" in it })
            }
        } finally {
            logger.detachAppender(events)
            events.stop()
            root.toFile().deleteRecursively()
        }
    }
}
