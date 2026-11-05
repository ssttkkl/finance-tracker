package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class LegacyReceiptMigrationCompatibilityTest {
    @Test fun realOriginalUpgradeAndLossyDowngradeRefusalLeaveReadableLegacyAndCurrentReceipts() {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_LEGACY_RECEIPT_MIGRATION_DIR")))
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val database = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        database.validateSchema()
        fun snapshot() = database.read { connection -> DefaultSchema.DEFAULT_SCHEMA.tables.associate { table ->
            table.name to connection.createStatement().use { it.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
                buildList { while (rows.next()) add(JsonArray((1..rows.metaData.columnCount).map { rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
            } }
        } }
        val expected = Json.parseToJsonElement(root.resolve("receipts.json").readText()).jsonObject
        assertEquals(2, expected.size); assertTrue("legacy-receipt" in expected)
        val before = snapshot()
        database.read { connection ->
            val repository = ReceiptRepository(connection, config.getValue("workspace").jsonPrimitive.content, database.sqlite)
            for ((id, raw) in expected) {
                val value = raw.jsonObject
                val receipt = repository.get(value.getValue("user").jsonPrimitive.content, id)
                assertEquals<JsonElement?>(value.getValue("expected"), receipt, id)
                if (id == "legacy-receipt") assertEquals(JsonPrimitive(1), receipt!!.getValue("summary").jsonObject["new_rows"])
            }
        }
        assertEquals(before, snapshot())
    }
}
