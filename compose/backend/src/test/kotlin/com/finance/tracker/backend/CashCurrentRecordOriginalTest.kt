package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashCurrentRecordOriginalTest {
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CURRENT_RECORD_CONTRACT_DIR")))
    private fun names(label: String) = Json.parseToJsonElement(root().resolve("scenarios.json").readText()).jsonObject
        .filterValues { it.jsonObject["label"] == JsonPrimitive(label) }.keys.also { assertTrue(it.isNotEmpty(), label) }
    private class Rollback : RuntimeException()
    private inner class Fixture(name: String) {
        private val directory = root().resolve(name)
        private val config = Json.parseToJsonElement(directory.resolve("database.json").readText()).jsonObject
        val case = Json.parseToJsonElement(directory.resolve("case.json").readText()).jsonObject
        val params = case.getValue("metadata").jsonObject.getValue("params").jsonObject
        val workspace = config.getValue("workspace").jsonPrimitive.content
        val id = case.getValue("transaction_id").jsonPrimitive.long
        val now = Instant.parse("2026-10-22T00:00:00Z")
        val database = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val store = InMemoryParsedImportStore(clock = { now })
        val service = MutationPlanService(database, now = { now }, staging = store)
        fun context(connection: Connection, scope: String = workspace) = CashSqlContext(connection, scope, database.sqlite, now = { now })
        fun record() = database.read { MutationPlanBuilder(context(it)).record(id) }
        fun note(connection: Connection, value: String) = connection.jooq(database.sqlite).update(CASH_TRANSACTIONS)
            .set(CASH_TRANSACTIONS.NOTE, value).where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace)).and(CASH_TRANSACTIONS.ID.eq(1001L)).execute()
        fun detail() = CashRecordDetailService(database, now = { now }).detail("original-import-owner", id.toString())
        fun state() = database.read { connection ->
            val tables = DefaultSchema.DEFAULT_SCHEMA.tables; assertEquals(32, tables.size)
            tables.associate { table -> table.name to connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
                    buildList { while (rows.next()) add(JsonArray((1..rows.metaData.columnCount).map { rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
                }
            } }
        }
    }
    @Test fun currentExpiredRolledBackAndOtherSnapshotsNeverReplaceCurrentSqlValues() {
        val method = MutationPlanBuilder::class.java.declaredMethods.single { it.name == "record" }
        assertEquals(listOf(Long::class.javaPrimitiveType), method.parameterTypes.toList())
        for (name in names("record")) {
            val f = Fixture(name); val before = f.state(); val condition = f.params.getValue("condition").jsonPrimitive.content
            var old: MutationPlanBuilder.PlanRecord? = null
            if (condition == "rolled_back") assertFailsWith<Rollback> {
                f.database.write { connection ->
                    old = MutationPlanBuilder(f.context(connection)).record(1001L)
                    assertEquals(1, f.note(connection, "不应复用的未提交内容")); throw Rollback()
                }
            } else if (condition != "current") f.database.read { connection ->
                old = MutationPlanBuilder(f.context(connection)).record(if (condition == "other_record") 1002L else 1001L)
            }
            assertFailsWith<Rollback> {
                f.database.write { connection ->
                    val builder = MutationPlanBuilder(f.context(connection))
                    if (condition == "current") old = builder.record(1001L)
                    val oldNote = old!!.note
                    assertEquals(if (condition == "other_record") 1002L else 1001L, old!!.id, name)
                    assertEquals(1, f.note(connection, "当前已读内容"), name)
                    val current = builder.record(1001L)
                    assertEquals(1001L, current.id, name); assertEquals("当前已读内容", current.note, name)
                    assertEquals(oldNote, old!!.note, name); throw Rollback()
                }
            }
            assertEquals(before, f.state(), name)
        }
    }
    @Test fun anotherConnectionSnapshotCannotOverrideTheCurrentCommittedRecord() {
        for (name in names("other_session")) {
            val f = Fixture(name)
            f.database.read { first ->
                val old = MutationPlanBuilder(f.context(first)).record(1001L); val note = old.note
                f.database.write { second -> assertEquals(1, f.note(second, "其他会话的新值")) }
                f.database.read { current ->
                    assertEquals("其他会话的新值", MutationPlanBuilder(f.context(current)).record(1001L).note)
                    assertEquals(note, old.note)
                }
            }
        }
    }
    @Test fun softDeletionRejectsReadsAndNoPendingEntityCanBeSupplied() {
        for (name in names("deleted")) {
            val f = Fixture(name); val before = f.state()
            if (f.params.getValue("deleted") == JsonPrimitive("pending")) {
                val record = f.record()
                assertTrue(java.lang.reflect.Modifier.isFinal(record.javaClass.modifiers))
                assertTrue(record.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                    .all { java.lang.reflect.Modifier.isFinal(it.modifiers) })
                assertEquals(listOf(Long::class.javaPrimitiveType), MutationPlanBuilder::class.java.declaredMethods.single { it.name == "record" }.parameterTypes.toList())
                assertEquals(record, f.record()); assertEquals(before, f.state(), name)
                continue
            }
            assertFailsWith<Rollback> {
                f.database.write { connection ->
                    val builder = MutationPlanBuilder(f.context(connection)); val old = builder.record(1001L)
                    val sql = connection.jooq(f.database.sqlite)
                    assertEquals(1, sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.DELETED_AT, f.now).where(CASH_TRANSACTIONS.ID.eq(1001L)).execute())
                    assertEquals(1001L, old.id)
                    assertEquals("record_not_found", assertFailsWith<MutationPlanFailure> { builder.record(1001L) }.code)
                    throw Rollback()
                }
            }
            assertEquals(before, f.state(), name)
        }
    }
    @Test fun previouslyReadRecordNeverBypassesWorkspaceScope() {
        for (name in names("workspace")) {
            val f = Fixture(name); val before = f.state()
            f.database.read { connection ->
                val old = MutationPlanBuilder(f.context(connection)).record(1001L)
                assertEquals("record_not_found", assertFailsWith<MutationPlanFailure> {
                    MutationPlanBuilder(f.context(connection, "foreign-workspace")).record(old.id)
                }.code)
            }
            assertEquals(before, f.state(), name)
        }
    }
    @Test fun rootScopeAvailabilityNeverDropsAdditionalClosureMembersSources() {
        for (name in names("scopes")) {
            val f = Fixture(name); val before = f.state()
            f.database.read { connection ->
                val context = f.context(connection); val builder = CashImportPreviewSnapshotBuilder(context)
                val graph = CashRelationContextService(context).load(setOf(1003L))
                assertEquals(setOf(1001L, 1003L), graph.transactionIds, name)
                val rootScopes = builder.scopes(setOf(1003L)); val root = MutationPlanBuilder(context).record(1003L)
                assertEquals(1003L, root.id)
                val prepared = PreparedCashImportPreview(emptyList(), emptyMap(), emptySet(), emptySet(), graph)
                val first = builder.build(prepared, emptyList())
                val freshContext = f.context(connection); val freshGraph = CashRelationContextService(freshContext).load(setOf(1003L))
                val fresh = CashImportPreviewSnapshotBuilder(freshContext).build(prepared.copy(context = freshGraph), emptyList())
                assertTrue(CashRecordPolicy.valuesEqual(first.values, fresh.values), name)
                assertEquals(first.facts, fresh.facts, name); assertEquals(first.components, fresh.components, name)
                assertEquals("synthetic-bank-scope", first.values.getValue("1001")["source_scope_key"], name)
                assertEquals(f.params.getValue("root_bound").jsonPrimitive.boolean, rootScopes.isNotEmpty(), name)
            }
            assertEquals(before, f.state(), name)
        }
    }
    @Test fun postedImportTypeEditRejectsWithoutChangingMembersOrRelation() {
        for (name in names("type")) {
            val f = Fixture(name); val before = f.detail(); val state = f.state()
            val failure = assertFailsWith<MutationPlanFieldFailure> { f.service.preview("original-import-owner", buildJsonObject {
                put("intent", "edit_record"); put("transaction_id", f.id.toString()); put("expected_revision", before.getValue("row_revision"))
                put("patch", buildJsonObject { put("record_type", "transfer_out"); put("record_subtype", "ordinary_transfer") })
            }) }
            assertEquals("field_not_editable", failure.code)
            assertEquals(before.getValue("fields").jsonObject["components"], f.detail().getValue("fields").jsonObject["components"])
            f.database.read { connection -> assertEquals(true, connection.jooq(f.database.sqlite).select(TRANSACTION_RELATIONS.ACTIVE).from(TRANSACTION_RELATIONS)
                .where(TRANSACTION_RELATIONS.ID.eq(f.case.getValue("relation_id").jsonPrimitive.long)).fetchSingle().value1()) }
            assertEquals(state, f.state(), name)
        }
    }
    @Test fun componentChangeWithoutParentRevisionInvalidatesConfirmation() {
        for (name in names("component")) {
            val f = Fixture(name); val before = f.record()
            val plan = f.service.preview("original-import-owner", buildJsonObject {
                put("intent", "edit_record"); put("transaction_id", f.id.toString()); put("expected_revision", before.revision)
                put("patch", buildJsonObject { put("note", "不应保存的旧预览") })
            })
            f.database.write { connection -> assertEquals(1, connection.jooq(f.database.sqlite).update(CASH_TRANSACTION_COMPONENTS)
                .set(CASH_TRANSACTION_COMPONENTS.ORDINAL, 1L).where(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID.eq(f.id)).execute()) }
            assertEquals(before.revision, f.record().revision); val changed = f.state()
            val failure = assertFailsWith<MutationPlanFailure> { f.service.commit("original-import-owner", plan.getValue("plan_id").jsonPrimitive.content,
                plan.getValue("plan_revision").jsonPrimitive.long, "synthetic-current-component") }
            assertEquals("record_changed", failure.code); val after = f.record()
            assertEquals(before.note, after.note); assertEquals(before.revision, after.revision)
            f.database.read { connection -> assertEquals(1L, connection.jooq(f.database.sqlite).select(CASH_TRANSACTION_COMPONENTS.ORDINAL).from(CASH_TRANSACTION_COMPONENTS)
                .where(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID.eq(f.id)).fetchSingle().value1()) }
            assertEquals(changed, f.state()); assertEquals(plan, f.service.plan("original-import-owner", plan.getValue("plan_id").jsonPrimitive.content))
        }
    }
}
