package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URI
import java.nio.file.Files
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.UUID
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CashRecordValueWriterTest(private val backend: String) {
    private val successfulStatements = mutableListOf<String>()
    private fun observe(connection: Connection): Connection {
        fun call(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? =
            try { method.invoke(target, *(args ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.targetException }
        return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            val result = call(connection, method, args)
            if (result is Statement) {
                val prepared = args?.firstOrNull() as? String
                Proxy.newProxyInstance(Statement::class.java.classLoader, arrayOf(if (result is PreparedStatement) PreparedStatement::class.java else Statement::class.java)) { _, operation, values ->
                    val returned = call(result, operation, values)
                    if (operation.name in setOf("execute", "executeQuery", "executeUpdate", "executeBatch"))
                        (prepared ?: values?.firstOrNull() as? String)?.let { successfulStatements.add(it.replace("\"", "").replace("`", "").lowercase()) }
                    returned
                }
            } else result
        } as Connection
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun backends(): List<Array<String>> = listOf(arrayOf("sqlite"), arrayOf("postgres"))
    }

    private fun inWorkspace(action: (Database, String, Long) -> Unit) {
        val directory = if (backend == "sqlite") Files.createTempDirectory("ft-source-values-") else null
        val db = if (directory != null) Database("jdbc:sqlite:${directory.resolve("source_test.db")}", decorateConnection = ::observe).also { it.bootstrapSchema() }
        else {
            val url = System.getenv("FT_TEST_POSTGRES_URL")
            org.junit.Assume.assumeTrue("Explicit PostgreSQL test database required", url != null)
            require(URI(url!!.replaceFirst("postgresql+psycopg://", "postgresql://")).path.endsWith("_test"))
            databaseConnectionConfig(mapOf("FT_DATABASE_URL" to url)).let { Database(it.jdbcUrl, it.user, it.password, ::observe) }
        }
        val workspace = UUID.randomUUID().toString()
        try {
            db.validateSchema()
            db.write { c -> deletionInsert(c, db, "workspaces", linkedMapOf("id" to workspace, "name" to "Source values", "created_at" to deletionTime)) }
            action(db, workspace, deletionAccount(db, workspace))
        } finally {
            db.write { c -> c.prepareStatement("DELETE FROM workspaces WHERE id=?").use { it.setString(1, workspace); it.executeUpdate() } }
            directory?.let { Files.walk(it).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        }
    }

    private fun values(account: Long, parts: List<Map<String, Any?>> = listOf(part(account, 0))): Map<String, Any?> = mapOf(
        "state" to "draft", "note" to "Source S", "source_type" to "alipay", "record_id" to "source-row",
        "account_id" to account.toString(), "amount" to "-12.30", "currency" to "CNY", "occurred_at" to deletionTime,
        "record_type" to "consumption", "record_subtype" to "not_applicable", "components" to parts,
    )
    private fun part(account: Long, ordinal: Int, id: Long? = null, amount: String = "-12.30"): Map<String, Any?> = buildMap {
        put("ordinal", ordinal); put("account_id", account.toString()); put("amount", amount); put("currency", "CNY")
        id?.let { put("component_id", it.toString()) }
    }
    private fun operation(values: Map<String, Any?>, id: Long? = null): Map<String, Any?> = mapOf(
        "values" to values, "transaction_id" to id?.toString(), "manual_fields" to emptyList<String>(), "pending_fields" to emptyMap<String, Any?>(),
        "preview_components" to (values.getValue("components") as List<*>).map { raw ->
            val value = raw as Map<*, *>; mapOf("ordinal" to value["ordinal"], "component_id" to "virtual:${value["ordinal"]}")
        },
        "entry_edit" to mapOf("patch" to mapOf("note" to "Must not apply C")),
    )
    private fun parent(c: CashSqlContext, id: Long) = c.sql.selectFrom(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.ID.eq(id)).fetchSingle()
    private fun parts(c: CashSqlContext, id: Long) = c.sql.selectFrom(CASH_TRANSACTION_COMPONENTS)
        .where(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID.eq(id)).orderBy(CASH_TRANSACTION_COMPONENTS.ORDINAL).fetch()
    private fun create(c: CashSqlContext, account: Long, count: Int = 1): Long {
        val values = values(account, (0 until count).map { part(account, it) }); val operation = operation(values)
        val writer = CashRecordValueWriter(c)
        val id = writer.saveParent(operation, values, null, null, true)
        writer.saveComponents(id, operation, values, null, false, false) { error("New rows cannot delete components") }
        return id
    }
    private fun snapshot(db: Database, workspace: String) = db.read { c ->
        LedgerSnapshotRepository(c, workspace, db.sqlite).facts() to c.prepareStatement("SELECT ledger_seq FROM workspaces WHERE id=?").use {
            it.setString(1, workspace); it.executeQuery().use { r -> check(r.next()); r.getLong(1) }
        }
    }

    @Test fun sourceDefaultsAndOneVersionWithoutEntryOrIssueSideEffects() = inWorkspace { db, workspace, account ->
        val before = snapshot(db, workspace)
        db.write { connection ->
            val c = CashSqlContext(connection, workspace, db.sqlite) { deletionTime }
            val id = create(c, account, 2); val row = parent(c, id); val table = CASH_TRANSACTIONS
            assertEquals("import", row[table.RECORD_ORIGIN]); assertEquals(1L, row[table.ROW_REVISION]); assertEquals(deletionTime, row[table.CREATED_AT])
            assertEquals("Source S", row[table.NOTE]); assertNull(row[table.SOURCE_FINGERPRINT]); assertEquals(JsonObject(emptyMap()), row[table.MANUAL_OVERRIDES])
            val components = parts(c, id); assertEquals(2, components.size)
            components.forEach { assertEquals("", it[CASH_TRANSACTION_COMPONENTS.LABEL]); assertEquals("", it[CASH_TRANSACTION_COMPONENTS.SOURCE_KEY]); assertEquals(JsonObject(emptyMap()), it[CASH_TRANSACTION_COMPONENTS.METADATA_JSON]) }
        }
        val after = snapshot(db, workspace)
        assertEquals(before.second + 1, after.second)
        assertEquals(before.first.copy(cashTransactions = after.first.cashTransactions, cashTransactionComponents = after.first.cashTransactionComponents), after.first)
    }

    @Test fun parentNoopAndUpdatePreserveServerMetadata() = inWorkspace { db, workspace, account ->
        val id = db.write { create(CashSqlContext(it, workspace, db.sqlite) { deletionTime }, account) }
        db.write { connection ->
            val c = CashSqlContext(connection, workspace, db.sqlite); val t = CASH_TRANSACTIONS
            c.sql.update(t).set(t.SOURCE_FINGERPRINT, "persisted-fingerprint").set(t.MANUAL_OVERRIDES, buildJsonObject { put("note", true) }).where(t.ID.eq(id)).execute()
        }
        val before = snapshot(db, workspace)
        db.write { connection ->
            val c = CashSqlContext(connection, workspace, db.sqlite); val values = values(account); val writer = CashRecordValueWriter(c)
            assertEquals(id, writer.saveParent(operation(values, id), values, id, parent(c, id), false))
        }
        assertEquals(before, snapshot(db, workspace))
        db.write { connection ->
            val c = CashSqlContext(connection, workspace, db.sqlite); val values = values(account) + ("note" to "New source S")
            CashRecordValueWriter(c).saveParent(operation(values, id), values, id, parent(c, id), true)
            val row = parent(c, id); assertEquals(2L, row[CASH_TRANSACTIONS.ROW_REVISION]); assertEquals("persisted-fingerprint", row[CASH_TRANSACTIONS.SOURCE_FINGERPRINT])
            assertEquals(buildJsonObject { put("note", true) }, row[CASH_TRANSACTIONS.MANUAL_OVERRIDES])
        }
        assertEquals(before.second + 1, snapshot(db, workspace).second)
    }

    @Test fun revisionOverflowRollsBackTheTransaction() = inWorkspace { db, workspace, account ->
        val id = db.write { create(CashSqlContext(it, workspace, db.sqlite), account) }
        db.write { connection -> connection.jooq(db.sqlite).update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.ROW_REVISION, Long.MAX_VALUE).where(CASH_TRANSACTIONS.ID.eq(id)).execute() }
        val before = snapshot(db, workspace)
        assertFailsWith<ArithmeticException> { db.write { connection ->
            val c = CashSqlContext(connection, workspace, db.sqlite); val values = values(account)
            CashRecordValueWriter(c).saveParent(operation(values, id), values, id, parent(c, id), true)
        } }
        assertEquals(before, snapshot(db, workspace))
    }

    @Test fun draftReorderKeepsIdsAndHiddenComponentValues() = inWorkspace { db, workspace, account ->
        val id = db.write { create(CashSqlContext(it, workspace, db.sqlite), account, 2) }
        db.write { connection ->
            val c = CashSqlContext(connection, workspace, db.sqlite); val p = CASH_TRANSACTION_COMPONENTS; val old = parts(c, id)
            old.forEach { c.sql.update(p).set(p.LABEL, "label:${it[p.ID]}").set(p.SOURCE_KEY, "key:${it[p.ID]}").set(p.METADATA_JSON, buildJsonObject { put("private", it[p.ID]!!) }).where(p.ID.eq(it[p.ID])).execute() }
            val desired = old.reversed().mapIndexed { ordinal, row -> part(account, ordinal, row[p.ID]) }; val values = values(account, desired)
            val mapping = CashRecordValueWriter(c).saveComponents(id, operation(values, id), values, null, true, false) { error("Reorder must not remove parts") }
            assertEquals(old.reversed().map { it[p.ID].toString() }, mapping.values.toList())
            parts(c, id).forEach { row -> assertEquals("label:${row[p.ID]}", row[p.LABEL]); assertEquals("key:${row[p.ID]}", row[p.SOURCE_KEY]); assertEquals(buildJsonObject { put("private", row[p.ID]!!) }, row[p.METADATA_JSON]) }
        }
    }

    @Test fun replacementRepairsOnceAfterParentWriteBeforeAnyDeletion() = inWorkspace { db, workspace, account ->
        val id = db.write { create(CashSqlContext(it, workspace, db.sqlite), account, 2) }; val before = snapshot(db, workspace)
        db.write { connection ->
            val c = CashSqlContext(connection, workspace, db.sqlite); val writer = CashRecordValueWriter(c); val p = CASH_TRANSACTION_COMPONENTS
            val old = parts(c, id).map { it[p.ID]!! }.toSet(); val values = values(account) + ("note" to "Changed parent")
            writer.saveParent(operation(values, id), values, id, parent(c, id), true)
            var calls = 0
            val mapping = writer.saveComponents(id, operation(values, id), values, null, true, false) { removed ->
                calls++; assertEquals(old, removed); assertEquals(old, parts(c, id).map { it[p.ID]!! }.toSet()); assertEquals("Changed parent", parent(c, id)[CASH_TRANSACTIONS.NOTE])
            }
            assertEquals(1, calls); assertEquals(parts(c, id).single()[p.ID].toString(), mapping["virtual:0"])
            assertEquals(JsonObject(emptyMap()), parts(c, id).single()[p.METADATA_JSON])
        }
        assertEquals(before.second + 1, snapshot(db, workspace).second)
    }

    @Test fun repairFailurePropagatesAndRollsBackParentAndVersion() = inWorkspace { db, workspace, account ->
        val id = db.write { create(CashSqlContext(it, workspace, db.sqlite), account, 2) }; val before = snapshot(db, workspace)
        val failure = IllegalStateException("reference repair failed")
        assertSame(failure, assertFailsWith<IllegalStateException> { db.write { connection ->
            val c = CashSqlContext(connection, workspace, db.sqlite); val writer = CashRecordValueWriter(c); val values = values(account)
            writer.saveParent(operation(values, id), values, id, parent(c, id), true)
            writer.saveComponents(id, operation(values, id), values, null, true, false) { throw failure }
        } })
        assertEquals(before, snapshot(db, workspace))
    }

    @Test fun laterComponentSqlFailureRollsBackEarlierParentAndComponentWrites() = inWorkspace { db, workspace, account ->
        val id = db.write { create(CashSqlContext(it, workspace, db.sqlite), account, 2) }; val before = snapshot(db, workspace)
        successfulStatements.clear()
        assertFailsWith<StorageFailure> { db.write { connection ->
            val c = CashSqlContext(connection, workspace, db.sqlite); val p = CASH_TRANSACTION_COMPONENTS; val old = parts(c, id)
            val desired = listOf(part(account, 0, old[0][p.ID], "-99.00"), part(Long.MAX_VALUE, 1, old[1][p.ID]))
            val values = values(account, desired) + ("note" to "Parent to roll back"); val writer = CashRecordValueWriter(c)
            writer.saveParent(operation(values, id), values, id, parent(c, id), true)
            writer.saveComponents(id, operation(values, id), values, null, true, false) { error("No deletion expected") }
        } }
        assertEquals(1, successfulStatements.count { Regex("^update (?:main\\.)?cash_transactions ").containsMatchIn(it) }, successfulStatements.toString())
        assertEquals(1, successfulStatements.count { Regex("^update (?:main\\.)?cash_transaction_components ").containsMatchIn(it) }, successfulStatements.toString())
        assertEquals(before, snapshot(db, workspace))
    }

    @Test fun postedUnchangedComponentsKeepMappingWithoutSqlOrRepair() = inWorkspace { db, workspace, account ->
        val id = db.write { connection ->
            val id = create(CashSqlContext(connection, workspace, db.sqlite), account)
            connection.jooq(db.sqlite).update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.STATE, "posted").where(CASH_TRANSACTIONS.ID.eq(id)).execute()
            id
        }
        val before = snapshot(db, workspace)
        db.write { connection ->
            val c = CashSqlContext(connection, workspace, db.sqlite); val componentId = parts(c, id).single()[CASH_TRANSACTION_COMPONENTS.ID]!!
            val values = values(account, listOf(part(account, 0, componentId))) + ("state" to "posted")
            successfulStatements.clear()
            val mapping = CashRecordValueWriter(c).saveComponents(id, operation(values, id), values, values, true, true) { error("Unchanged posted row") }
            assertEquals(mapOf("virtual:0" to componentId.toString()), mapping); assertTrue(successfulStatements.isEmpty())
        }
        assertEquals(before, snapshot(db, workspace))
    }
}
