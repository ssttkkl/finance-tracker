package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashEvidenceHttpContractTest {
    @Test fun evidenceMatchesEveryPythonFieldAndKeepsFinancialStateUnchanged() = testApplication {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_EVIDENCE_CONTRACT_DIR")))
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val db = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val failure = AtomicReference<String?>()
        application { financeModule(db, currentProjections = CurrentProjectionReadService(db,
            afterStateRead = { failure.get()?.let { throw StorageFailure(it) } })) }
        val cases = Json.parseToJsonElement(root.resolve("evidence-reads.json").readText()).jsonArray
        assertTrue(cases.size >= 50)
        val captures = mutableListOf<JsonObject>()
        for ((index, raw) in cases.withIndex()) {
            val case = raw.jsonObject
            fun actions(key: String) = db.write { c ->
                case.getValue(key).jsonArray.forEach { value -> c.createStatement().use { it.execute(value.jsonPrimitive.content) } }
            }
            actions("actions")
            try {
                failure.set(case.getValue("failure").jsonPrimitive.contentOrNull)
                val before = currentProjectionDatabaseState(db)
                val response = client.get(case.getValue("path").jsonPrimitive.content) {
                    case.getValue("headers").jsonObject.forEach { (key, value) -> header(key, value.jsonPrimitive.content) }
                }
                val body = response.bodyAsText()
                val context = "$index ${case.getValue("path")} $body"
                assertEquals(case.getValue("status").jsonPrimitive.int, response.status.value, context)
                if ("text" in case) assertEquals(case.getValue("text").jsonPrimitive.content, body, context)
                else assertEquals(case.getValue("body"), Json.parseToJsonElement(body), context)
                assertEquals(before, currentProjectionDatabaseState(db), context)
                if (response.status.value == 200) {
                    for (private in listOf("source_payload", "source_snapshot", "relation_metadata", "pending_fields", "pending_source_payload", "manual_overrides", "sensitive-account", "sensitive-memo", "私有"))
                        assertFalse(private in body, private)
                    captures.add(buildJsonObject { put("path", case.getValue("path")); put("body", body) })
                }
            } finally { actions("undo"); failure.set(null) }
        }
        assertTrue(captures.isNotEmpty())
        System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let { output ->
            val directory = Path.of(output)
            Files.createDirectories(directory)
            Files.writeString(directory.resolve("evidence-responses.json"), buildJsonObject {
                put("backend", if (db.sqlite) "sqlite" else "postgresql"); put("responses", JsonArray(captures))
            }.toString())
        }
    }
}
