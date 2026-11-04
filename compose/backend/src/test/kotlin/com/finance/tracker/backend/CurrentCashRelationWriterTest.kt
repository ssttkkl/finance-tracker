package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class CurrentCashRelationWriterTest {
    private val scenario = "current_explicit_removal_marks_the_existing_confirmed_relation_effect_s2"
    private inner class Fixture(name: String) {
        private val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CURRENT_WRITER_CONTRACT_DIR"))).resolve(name).resolve(scenario)
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val case = Json.parseToJsonElement(root.resolve("case.json").readText()).jsonObject
        val database = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        fun context(connection: java.sql.Connection) = CashSqlContext(connection, config.getValue("workspace").jsonPrimitive.content, database.sqlite,
            now = { Instant.parse("2026-10-01T09:00:00Z") })
        fun snapshot() = database.read { connection -> DefaultSchema.DEFAULT_SCHEMA.tables.associate { table -> table.name to connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
                val count = rows.metaData.columnCount
                buildList { while (rows.next()) add(JsonArray((1..count).map { rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
            }
        } } }
        @Suppress("UNCHECKED_CAST") fun plan(context: CashSqlContext) = cashSourceValue(CurrentCashMaintenancePlanBuilder(context).build(
            MutationPlanRequest.fromStored(case.getValue("input").jsonObject), case.getValue("previous").jsonObject)) as Map<String, Any?>
    }
    @Test fun confirmedRemovalKeepsRelationIdentityAndSplitsOnlyItsEntries() {
        val f = Fixture("remove"); val before = f.snapshot()
        f.database.write { connection ->
            val context = f.context(connection); val state = CashProjectionRepository(context).requireReadyStateLock()
            val plan = f.plan(context); val affected = mutableSetOf<Long>()
            val result = CurrentCashRelationWriter(context, f.config.getValue("user").jsonPrimitive.content).saveGroups(plan, plan, affected)
            assertEquals(listOf("removed"), result.map { it["outcome"] }); assertEquals(setOf(1101L, 1102L), affected)
            CashProjectionService.maintainInSession(context, affected, state)
            val relation = context.sql.selectFrom(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(context.workspace)).fetchSingle()
            assertFalse(relation[TRANSACTION_RELATIONS.ACTIVE]!!); assertEquals("manual", relation[TRANSACTION_RELATIONS.DECISION_SOURCE])
            val references = CashProjectionRepository(context).currentReferences(affected, state.datasetId)
            assertNotEquals(references.getValue("1101")["projection_id"], references.getValue("1102")["projection_id"])
        }
        val after = f.snapshot()
        for (table in listOf("cash_transactions", "cash_transaction_components", "ledger_snapshots", "cash_source_bindings")) assertEquals(before[table], after[table], table)
        assertEquals(before.getValue("transaction_relations").size, after.getValue("transaction_relations").size)
    }
    @Test fun lateRelationWriteFailureRollsBackAllTables() {
        val f = Fixture("rollback"); val before = f.snapshot(); var writes = 0
        assertFailsWith<IllegalStateException> { f.database.write { connection ->
            val context = f.context(connection); val plan = f.plan(context)
            CurrentCashRelationWriter(context, f.config.getValue("user").jsonPrimitive.content, afterWrite = { writes++; error("synthetic late current relation failure") })
                .saveGroups(plan, plan, mutableSetOf())
        } }
        assertEquals(1, writes); assertEquals(before, f.snapshot())
    }
}
