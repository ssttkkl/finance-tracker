package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class MaintenanceProtocolOriginalTest {
    @Test fun browserCanReadAuthenticationAndViewerErrorsWithoutTrustingMaliciousOrigins() = testApplication {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")), "database.json").readText()).jsonObject
        val database = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        fun snapshot() = database.read { connection -> DefaultSchema.DEFAULT_SCHEMA.tables.associate { table ->
            table.name to connection.createStatement().use { it.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
                buildList { while (rows.next()) add(JsonArray((1..rows.metaData.columnCount).map { rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
            } }
        } }
        application { financeModule(database) }
        val before = snapshot()
        for ((token, status) in listOf(null to 401, "synthetic-viewer" to 403)) {
            for (origin in listOf("http://127.0.0.1:5173", "http://127.0.0.1.evil:5173")) {
                val response = client.post("/api/v1/cash-mutations/plans") {
                    header("Origin", origin); header("Content-Type", "application/json")
                    if (token != null) header("Authorization", "Bearer $token")
                    setBody("{}")
                }
                assertEquals(status, response.status.value, response.bodyAsText())
                assertEquals(JsonPrimitive(false), Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject["retryable"])
                if (origin == "http://127.0.0.1:5173") assertEquals(origin, response.headers["Access-Control-Allow-Origin"])
                else assertNull(response.headers["Access-Control-Allow-Origin"])
                assertEquals(before, snapshot())
            }
        }
    }
}
