package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CorsOriginalTest {
    private fun database(): Database {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")))
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }

    @Test fun localFrontendDynamicPortRetainsOriginalOriginBoundary() = testApplication {
        application { financeModule(database(), "http://127.0.0.1:4173") }
        val response = client.get("/api/v1/accounts?view=cash") {
            header("Authorization", "Bearer synthetic-admin")
            header("Origin", "http://127.0.0.1:5181")
        }
        assertEquals(200, response.status.value)
        assertEquals("http://127.0.0.1:5181", response.headers["access-control-allow-origin"])
        val denied = client.get("/api/v1/accounts?view=cash") {
            header("Authorization", "Bearer synthetic-admin")
            header("Origin", "http://127.0.0.1.evil:5181")
        }
        assertFalse(denied.headers.contains("access-control-allow-origin"))
    }

    @Test fun importIdempotencyHeaderRetainsOriginalUnauthenticatedPreflight() = testApplication {
        application { financeModule(database(), "http://127.0.0.1:4173") }
        val response = client.options("/api/v1/cash-import/plans/synthetic-plan/commit") {
            header("Origin", "http://127.0.0.1:5181")
            header("Access-Control-Request-Method", "POST")
            header("Access-Control-Request-Headers", "content-type,idempotency-key")
        }
        assertEquals(200, response.status.value)
        assertTrue("idempotency-key" in response.headers["access-control-allow-headers"].orEmpty().lowercase())
    }
}
