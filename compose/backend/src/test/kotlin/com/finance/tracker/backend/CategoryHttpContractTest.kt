package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CategoryHttpContractTest {
    @Test fun categoryDirectoryCommandsMatchPythonIncludingAllRowsAfterFailures() = testApplication {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CATEGORY_CONTRACT_DIR")))
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val database = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val sequence = AtomicInteger()
        val capturedCommands = mutableListOf<JsonElement>()
        val categories = CategoryService(database, now = { Instant.parse("2026-10-28T14:00:00Z") }, newId = { "category-created-${sequence.getAndIncrement()}" })
        application { financeModule(database, categories = categories) }
        restoreSeed(database)
        try {
            for (value in Json.parseToJsonElement(root.resolve("categories.json").readText()).jsonArray) {
                val case = value.jsonObject
                val response = client.request(case.getValue("path").jsonPrimitive.content) {
                    method = HttpMethod.parse(case.getValue("method").jsonPrimitive.content)
                    case.getValue("headers").jsonObject.forEach { (name, value) -> header(name, value.jsonPrimitive.content) }
                    if (case["request"] != JsonNull) { contentType(ContentType.Application.Json); setBody(case.getValue("request").toString()) }
                }
                val context = case.getValue("path").toString() + " " + case["request"]
                assertEquals(case.getValue("status").jsonPrimitive.int, response.status.value, context)
                assertEquals(case["body"], Json.parseToJsonElement(response.bodyAsText()), context)
                assertEquals(case["effects"], snapshot(database), context)
                if (response.status.value in 200..299 && case.getValue("method").jsonPrimitive.content != "GET") {
                    val directory = client.get("/api/v1/cash-categories") {
                        case.getValue("headers").jsonObject.forEach { (name, value) -> header(name, value.jsonPrimitive.content) }
                    }
                    assertEquals(200, directory.status.value)
                    capturedCommands.add(buildJsonObject {
                        put("method", case.getValue("method")); put("path", case.getValue("path"))
                        put("body", response.bodyAsText()); put("directory", directory.bodyAsText())
                    })
                }
            }
        } finally { restoreSeed(database) }
        System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let { output ->
            val directory = Path.of(output)
            require(directory.isAbsolute)
            Files.createDirectories(directory)
            Files.writeString(directory.resolve("category-command-responses.json"), buildJsonObject {
                put("backend", if (database.sqlite) "sqlite" else "postgresql")
                put("captured_at", Instant.now().toString())
                put("responses", JsonArray(capturedCommands))
            }.toString())
        }
    }

    private fun restoreSeed(database: Database) = database.write { connection ->
            val created = connection.createStatement().use {
                it.executeQuery("SELECT id FROM cash_categories WHERE id LIKE 'category-created-%' ORDER BY depth DESC,id").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
            connection.prepareStatement("DELETE FROM cash_categories WHERE id=?").use {
                created.forEach { id -> it.setString(1, id); it.executeUpdate() }
            }
            connection.createStatement().use {
                it.executeUpdate("UPDATE cash_categories SET parent_id=NULL,parent_scope_key='__root__',category_path='/root-z/',depth=1,sort_order=1,revision=1 WHERE id='root-z'")
                it.executeUpdate("UPDATE cash_categories SET sort_order=1,revision=1 WHERE id='root-a'")
                it.executeUpdate("UPDATE cash_category_states SET revision=7 WHERE workspace_id='fixture-a'")
            }
    }

    private fun snapshot(database: Database): JsonObject = database.read { connection ->
        fun rows(query: String, strings: Int, numbers: Int): JsonArray = connection.createStatement().use {
            it.executeQuery(query).use { result -> JsonArray(buildList {
                while (result.next()) add(JsonArray((1..strings).map { index -> result.getString(index)?.let(::JsonPrimitive) ?: JsonNull }
                    + ((strings + 1)..(strings + numbers)).map { index -> JsonPrimitive(result.getLong(index)) }))
            }) }
        }
        buildJsonObject {
            put("categories", rows("SELECT workspace_id,id,parent_id,parent_scope_key,name,normalized_name,description,category_path,depth,sort_order,revision FROM cash_categories ORDER BY workspace_id,id", 8, 3))
            put("states", rows("SELECT workspace_id,revision FROM cash_category_states ORDER BY workspace_id", 1, 1))
        }
    }
}
