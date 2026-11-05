package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.Statement
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class CurrentCashMaintenanceServiceTest {
    private val scenario = "current_explicit_removal_marks_the_existing_confirmed_relation_effect_s1"
    private inner class Fixture(name: String) {
        private val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CURRENT_WRITER_CONTRACT_DIR"))).resolve(name).resolve(scenario)
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        private val canonicalInput = Json.parseToJsonElement(root.resolve("case.json").readText()).jsonObject.getValue("input").jsonObject
        val input = JsonObject(canonicalInput - "issue_resolutions").also {
            assertEquals(JsonPrimitive("maintain_relations"), canonicalInput["intent"])
            assertEquals(JsonArray(emptyList()), canonicalInput["issue_resolutions"])
        }
        val database = database()
        val at: Instant = Instant.parse("2026-10-01T09:00:00Z")
        val staging = InMemoryParsedImportStore(clock = { at })
        fun database(decorate: (Connection) -> Connection = { it }) = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"), decorate)
        fun service(db: Database = database) = MutationPlanService(db, now = { at }, staging = staging)
        fun snapshot() = database.read { connection -> DefaultSchema.DEFAULT_SCHEMA.tables.associate { table -> table.name to connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows -> val count = rows.metaData.columnCount
                buildList { while (rows.next()) add(JsonArray((1..count).map { rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
            }
        } } }
        fun selected(service: MutationPlanService): JsonObject {
            val first = service.preview("original-import-owner", JsonObject(input + ("relation_choices" to JsonArray(emptyList()))))
            val details = first.getValue("items").jsonArray.flatMap { item -> service.item("original-import-owner", first.getValue("plan_id").jsonPrimitive.content,
                item.jsonObject.getValue("preview_item_id").jsonPrimitive.content).getValue("relation_choices").jsonArray }
            assertTrue(details.isNotEmpty(), "Current relation missing from maintenance preview")
            val group = details.first().jsonObject
            val selected = service.preview("original-import-owner", JsonObject(input + mapOf(
                "edit_context_id" to first.getValue("edit_context_id"), "previous_plan_id" to first.getValue("plan_id"),
                "expected_plan_revision" to first.getValue("plan_revision"), "relation_choices" to JsonArray(listOf(buildJsonObject {
                    put("suggestion_id", group.getValue("suggestion_id")); put("action", "remove")
                })))))
            assertEquals(JsonPrimitive(1), selected.getValue("summary").jsonObject["relations_removed"])
            return selected
        }
        fun commit(service: MutationPlanService, plan: JsonObject, key: String) = service.commit("original-import-owner", plan.getValue("plan_id").jsonPrimitive.content,
            plan.getValue("plan_revision").jsonPrimitive.long, key)
    }
    @Test fun currentRemovalCommitsExistingDecisionAndReplaysTheSamePermanentReceipt() {
        val f = Fixture("service"); val service = f.service(); val initial = f.snapshot(); val plan = f.selected(service)
        val receipt = f.commit(service, plan, "current-remove-service")
        assertEquals(JsonPrimitive(true), receipt["committed"])
        val summary = receipt.getValue("summary").jsonObject
        assertEquals(JsonPrimitive(1), summary["relations_removed"]); assertEquals(JsonPrimitive(1), summary["duplicate"])
        val after = f.snapshot(); assertNotEquals(initial["transaction_relations"], after["transaction_relations"])
        for (table in listOf("cash_transactions", "cash_transaction_components", "ledger_snapshots")) assertEquals(initial[table], after[table], table)
        assertEquals(receipt, f.commit(service, plan, "current-remove-service")); assertEquals(after, f.snapshot())
        assertEquals(JsonPrimitive("committed"), service.resolve("original-import-owner", plan.getValue("plan_id").jsonPrimitive.content, "current-remove-service")["status"])
    }
    private fun commitFailure(f: Fixture, lost: Boolean): Pair<Database, AtomicBoolean> {
        val fired = AtomicBoolean()
        fun invoke(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?) = try { method.invoke(target, *(args ?: emptyArray())) }
            catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
        fun fail(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Nothing {
            if (lost) invoke(target, method, args)
            throw java.sql.SQLException("synthetic current maintenance commit failure")
        }
        return f.database { connection -> Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "commit" && fired.compareAndSet(false, true)) fail(connection, method, args)
            val result = invoke(connection, method, args)
            if (method.name != "createStatement") result else Proxy.newProxyInstance(Statement::class.java.classLoader, arrayOf(Statement::class.java)) { _, operation, values ->
                if (operation.name == "execute" && values?.firstOrNull() == "COMMIT" && fired.compareAndSet(false, true)) fail(result!!, operation, values)
                invoke(result!!, operation, values)
            }
        } as Connection } to fired
    }
    @Test fun actualCommitFailureAndLostResponseKeepUnknownUntilOriginalKeyReplay() {
        for ((name, lost) in listOf("service_commit" to false, "service_lost" to true)) {
            val f = Fixture(name); val normal = f.service(); val plan = f.selected(normal); val before = f.snapshot()
            val (database, fired) = commitFailure(f, lost)
            val failure = assertFailsWith<CashImportCommitFailure> { f.commit(f.service(database), plan, "current-remove-result") }
            assertEquals("unknown", failure.commitState); assertTrue(fired.get())
            val after = f.snapshot(); if (!lost) assertEquals(before, after)
            val receipt = f.commit(normal, plan, "current-remove-result"); assertEquals(JsonPrimitive(true), receipt["committed"])
            if (lost) assertEquals(after, f.snapshot())
            assertEquals(JsonPrimitive(1), receipt.getValue("summary").jsonObject["relations_removed"])
        }
    }
}
