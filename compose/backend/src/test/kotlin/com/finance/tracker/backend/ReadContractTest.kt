package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.*

class ReadContractTest {
    @Test fun configuredHttpsOriginIsUsedForSimpleAndPreflightResponses() = testApplication {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")))
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val database = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        application { financeModule(database, "https://example.invalid/") }
        val simple = client.get("/api/v1/auth/session") {
            header("Origin", "https://example.invalid")
            header("Authorization", "Bearer synthetic-admin")
        }
        assertEquals(200, simple.status.value)
        assertEquals("https://example.invalid", simple.headers["Access-Control-Allow-Origin"])
        for (path in listOf("/api/v1/auth/session", "/api/v1/accounts?view=cash", "/api/v1/cash-categories")) {
            val preflight = client.options(path) {
                header("Origin", "https://example.invalid")
                header("Access-Control-Request-Method", "GET")
                header("Access-Control-Request-Headers", "authorization")
            }
            assertEquals(200, preflight.status.value)
            assertEquals("https://example.invalid", preflight.headers["Access-Control-Allow-Origin"])
            assertEquals("OK", preflight.bodyAsText())
            assertFalse(preflight.headers.contains("Access-Control-Allow-Credentials"))
            kotlin.test.assertTrue("authorization" in preflight.headers["Access-Control-Allow-Headers"].orEmpty().lowercase())
        }
    }

    @Test fun unauthenticatedCashAccountsAreRejected() = testApplication {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")))
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val database = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        application { financeModule(database) }
        val response = client.get("/api/v1/accounts?view=cash")
        assertEquals(401, response.status.value)
        assertEquals("authentication_required", Json.parseToJsonElement(response.bodyAsText()).jsonObject
            .getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
    }

    @Test fun readsMatchPythonOnTheExplicitIsolatedDatabase() {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")) {
            "FT_KOTLIN_CONTRACT_DIR must name explicitly prepared anonymous fixtures"
        })
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val cases = Json.parseToJsonElement(root.resolve("http.json").readText()).jsonArray
        val database = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        database.validateSchema()
        val categoryResponses = mutableListOf<JsonElement>()
        testApplication {
            application { financeModule(database) }
            cases.forEach { value ->
                val case = value.jsonObject
                val response = client.request(case.getValue("path").jsonPrimitive.content) {
                    method = HttpMethod.parse(case.getValue("method").jsonPrimitive.content)
                    case.getValue("headers").jsonObject.forEach { (name, value) -> header(name, value.jsonPrimitive.content) }
                }
                val context = case.getValue("method").jsonPrimitive.content + " " + case.getValue("path").jsonPrimitive.content + " " + case["headers"]
                assertEquals(case.getValue("status").jsonPrimitive.int, response.status.value, context)
                if ("body" in case) assertEquals(case["body"], Json.parseToJsonElement(response.bodyAsText()), context)
                else assertEquals(case.getValue("text").jsonPrimitive.content, response.bodyAsText(), context)
                case["cors"]?.jsonObject?.forEach { (name, value) ->
                    assertEquals(value.jsonPrimitive.content, response.headers[name], context + " " + name)
                }
                assertFalse(response.headers.contains("Access-Control-Allow-Credentials"), context)
                if (case.getValue("method").jsonPrimitive.content == "GET" &&
                    case.getValue("path").jsonPrimitive.content == "/api/v1/cash-categories" && response.status.value == 200) {
                    categoryResponses.add(buildJsonObject { put("body", response.bodyAsText()) })
                }
            }
        }
        System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let { output ->
            val directory = Path.of(output)
            require(directory.isAbsolute)
            Files.createDirectories(directory)
            Files.writeString(directory.resolve("category-responses.json"), buildJsonObject {
                put("backend", if (database.sqlite) "sqlite" else "postgresql")
                put("captured_at", java.time.Instant.now().toString())
                put("responses", JsonArray(categoryResponses))
            }.toString())
        }
    }
}
