package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportOriginalCommitTest {
    private val at = Instant.parse("2026-10-01T09:00:00Z")
    private class Store(at: Instant) : ParsedImportStore(clock = { at }) {
        val entries = linkedMapOf<String, ParsedImportObject>()
        fun erase() = entries.clear()
        override fun readRaw(id: String) = entries[id] ?: throw ParsedImportNotFound()
        override fun writeRaw(id: String, content: ByteArray, expected: String?, expiresAt: Instant) {
            if (entries[id]?.etag != expected) throw ParsedImportRevisionConflict()
            entries[id] = ParsedImportObject(content.copyOf(), digest(content))
        }
        override fun deleteRaw(id: String) { entries.remove(id) }
        override fun sweepExpired(cursor: String?, limit: Int, shouldStop: () -> Boolean) = ParsedImportSweep(0, 0, null)
    }
    private inner class Fixture(name: String, decorate: (Connection) -> Connection = { it }) {
        private val directory = Path.of(requireNotNull(System.getenv("FT_KOTLIN_IMPORT_ORIGINAL_COMMIT_CONTRACT_DIR"))).resolve(name)
        private val config = Json.parseToJsonElement(directory.resolve("database.json").readText()).jsonObject
        val input = Json.parseToJsonElement(directory.resolve("input.json").readText()).jsonObject
        val workspace = config.getValue("workspace").jsonPrimitive.content
        val user = config.getValue("user").jsonPrimitive.content
        val jdbcUrl = config.getValue("jdbc_url").jsonPrimitive.content
        val database = Database(jdbcUrl,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"), decorate)
        val store = Store(at)
        val original = ParsedImportCodec().decode(input.getValue("session_id").jsonPrimitive.content, input.getValue("session").toString().encodeToByteArray())
        val token = "original-import-owner"
        val commands = CashImportCommandService(database, store, now = { at })
        val reads = ReceiptReadService(database, now = { at })
        var session: ParsedImportSession? = null
        @Suppress("UNCHECKED_CAST") fun plan(): Map<String, Any?> {
            val staged = store.create(workspace, user, original.files)
            val result = CashImportHttpService(database, store).preview(token, staged.sessionId, 1, null,
                cashSourceValue(input.getValue("input")) as Map<String, Any?>)
            session = store.get(staged.sessionId, workspace, user)
            return result
        }
        fun commit(plan: Map<String, Any?>, key: String = "synthetic-confirm", service: CashImportCommandService = commands) =
            service.commit(token, plan.getValue("plan_id") as String, (plan.getValue("session_revision") as Number).toLong(), key)
        fun results(receipt: JsonObject) = reads.results(token, receipt.getValue("receipt_id").jsonPrimitive.content, "row", ReceiptPage(0, 200)).getValue("items").jsonArray
        fun snapshot() = database.read { connection ->
            DefaultSchema.DEFAULT_SCHEMA.tables.sortedBy { it.name }.associate { table ->
                val values = connection.createStatement().use { statement -> statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
                    val metadata = rows.metaData
                    buildList { while (rows.next()) add(JsonArray((1..metadata.columnCount).map { index ->
                        rows.getString(index)?.let(::JsonPrimitive) ?: JsonNull
                    }).toString()) }.sorted()
                } }
                table.name to values
            }
        }
        fun <T> read(block: (CashSqlContext) -> T) = database.read { block(CashSqlContext(it, workspace, database.sqlite)) }
        fun role(value: String) = database.write { connection -> connection.jooq(database.sqlite).update(WORKSPACE_MEMBERSHIPS)
            .set(WORKSPACE_MEMBERSHIPS.ROLE, value).where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(workspace).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user))).execute() }
    }
    private fun summary(receipt: JsonObject, key: String) = receipt.getValue("summary").jsonObject.getValue(key).jsonPrimitive.int
    private fun rowId(row: JsonElement) = row.jsonObject.getValue("transaction_id").jsonPrimitive.content.toLong()
    private fun snapshotPayload(fixture: Fixture) = fixture.read { it.sql.select(LEDGER_SNAPSHOTS.PAYLOAD).from(LEDGER_SNAPSHOTS)
        .where(LEDGER_SNAPSHOTS.WORKSPACE_ID.eq(it.workspace)).fetchOne(LEDGER_SNAPSHOTS.PAYLOAD) }
    private fun snapshotVersion(fixture: Fixture) = fixture.read { it.sql.select(LEDGER_SNAPSHOTS.VERSION).from(LEDGER_SNAPSHOTS)
        .where(LEDGER_SNAPSHOTS.WORKSPACE_ID.eq(it.workspace)).fetchOne(LEDGER_SNAPSHOTS.VERSION) }

    /** Observe executed writes only; never retain SQL parameters or source bodies. */
    private class Writes {
        var active = false
        var failSources = false
        val events = mutableListOf<String>()
        private fun label(query: String?): String? {
            val sql = query?.replace("\"", "")?.replace("`", "")?.lowercase() ?: return null
            return listOf("cash_transactions", "cash_transaction_components", "cash_source_bindings").firstOrNull {
                Regex("\\binsert\\s+into\\s+(?:\\w+\\.)?$it\\b").containsMatchIn(sql)
            }
        }
        private fun invoke(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? =
            try { method.invoke(target, *(args ?: emptyArray())) } catch (error: InvocationTargetException) { throw error.targetException }
        fun decorate(connection: Connection): Connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            val result = invoke(connection, method, args)
            if (result is Statement && method.name in setOf("prepareStatement", "createStatement")) statement(result, args?.firstOrNull() as? String) else result
        } as Connection
        private fun statement(statement: Statement, prepared: String?): Statement {
            val batch = mutableListOf<String>()
            val type = if (statement is PreparedStatement) PreparedStatement::class.java else Statement::class.java
            return Proxy.newProxyInstance(Statement::class.java.classLoader, arrayOf(type)) { _, method, args ->
                val query = label(prepared ?: args?.firstOrNull() as? String)
                if (method.name == "addBatch" && query != null) batch += query
                val result = invoke(statement, method, args)
                if (active && method.name in setOf("execute", "executeUpdate", "executeLargeUpdate", "executeQuery", "executeBatch", "executeLargeBatch")) {
                    val writes = if (method.name.endsWith("Batch")) batch.toList().also { batch.clear() } else listOfNotNull(query)
                    events += writes
                    if (failSources && "cash_source_bindings" in writes) throw SQLException("synthetic failure after persisted source aliases", "08006")
                }
                result
            } as Statement
        }
    }

    @Test fun realPlanCreatesFormalFactBindingsAndProjectionAsOriginal() {
        val fixture = Fixture("formal")
        val receipt = fixture.commit(fixture.plan())
        assertEquals(JsonPrimitive("completed"), receipt["status"])
        assertEquals(JsonPrimitive(true), receipt["committed"])
        assertEquals(1, summary(receipt, "created_posted"))
        val id = rowId(fixture.results(receipt).single())
        fixture.read { context ->
            val parent = context.sql.selectFrom(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.ID.eq(id)).fetchSingle()
            assertEquals("posted", parent.state)
            assertEquals(0, parent.amount!!.text.toBigDecimal().compareTo(BigDecimal("-12.5")))
            assertTrue(context.sql.fetchCount(CASH_SOURCE_BINDINGS, CASH_SOURCE_BINDINGS.TRANSACTION_ID.eq(id)) > 0)
        }
        assertTrue(receipt.getValue("projection_version").jsonPrimitive.long > 0)
    }

    @Test fun originalDraftStaysInFactTablesWithoutBalanceOrStatistics() {
        val fixture = Fixture("draft")
        val before = snapshotPayload(fixture)
        val receipt = fixture.commit(fixture.plan())
        val row = fixture.results(receipt).single().jsonObject
        assertEquals(JsonPrimitive("saved_draft"), row["outcome"])
        assertEquals(JsonPrimitive(1), receipt["needs_attention_count"])
        assertEquals(JsonPrimitive(1), receipt["pending_item_count"])
        val id = rowId(row)
        fixture.read { context ->
            val parent = context.sql.selectFrom(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.ID.eq(id)).fetchSingle()
            assertEquals("draft", parent.state); assertNull(parent.accountId)
            assertTrue(context.sql.fetchCount(CASH_TRANSACTION_ISSUES, CASH_TRANSACTION_ISSUES.TRANSACTION_ID.eq(id)) > 0)
            val projection = context.sql.selectFrom(CASH_PROJECTIONS).where(CASH_PROJECTIONS.ROOT_CASH_TRANSACTION_ID.eq(id)).fetchSingle()
            assertEquals(false, projection.statisticsEligible)
        }
        assertEquals(before, snapshotPayload(fixture))
    }

    @Test fun originalKeyReplaysWithoutTemporarySessionAndRejectsDifferentRequestOrKey() {
        val fixture = Fixture("replay"); val plan = fixture.plan()
        val first = fixture.commit(plan); fixture.store.erase()
        val before = fixture.snapshot()
        assertEquals(first, fixture.commit(plan))
        val other = assertFailsWith<ReceiptConflict> { fixture.commit(plan, "different-key") }
        assertEquals("session_committed", other.code)
        assertEquals(first.getValue("receipt_id").jsonPrimitive.content, other.receiptId)
        val changed = assertFailsWith<ReceiptConflict> { fixture.commands.commit(fixture.token, plan.getValue("plan_id") as String, 99, "synthetic-confirm") }
        assertEquals("idempotency_conflict", changed.code)
        assertEquals(before, fixture.snapshot())
    }

    @Test fun originalReceiptReplayStillRequiresCurrentWritePermission() {
        val fixture = Fixture("permission"); val plan = fixture.plan(); fixture.commit(plan)
        fixture.role("viewer")
        val before = fixture.snapshot()
        assertFailsWith<AccessFailure> { fixture.commit(plan) }
        assertEquals(before, fixture.snapshot())
    }

    @Test fun originalResolveObservesIndependentWriterReceiptAndCancellation() {
        val fixture = Fixture("resolve"); val plan = fixture.plan(); val id = plan.getValue("plan_id") as String
        val executor = Executors.newSingleThreadExecutor()
        try {
            fixture.database.write { connection ->
                assertTrue(connection.lockWorkspace(fixture.database.sqlite, fixture.workspace))
                val pending = executor.submit<JsonObject> { fixture.commands.resolve(fixture.token, id, "synthetic-confirm") }.get(3, TimeUnit.SECONDS)
                assertEquals(buildJsonObject { put("status", "pending") }, pending)
            }
        } finally { executor.shutdownNow() }
        if (fixture.database.sqlite) fixture.database.read { connection -> connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA busy_timeout").use { result -> assertTrue(result.next()); assertEquals(5000, result.getInt(1)) }
        } }
        assertEquals(buildJsonObject { put("status", "retry_original") }, fixture.commands.resolve(fixture.token, id, "synthetic-confirm"))
        val receipt = fixture.commit(plan); fixture.store.erase()
        assertEquals(buildJsonObject { put("status", "committed"); put("receipt", receipt) }, fixture.commands.resolve(fixture.token, id, "synthetic-confirm"))
        assertEquals(receipt, fixture.reads.receiptByKey(fixture.token, "synthetic-confirm"))
        val pending = fixture.plan()
        fixture.store.cancel(fixture.session!!.sessionId, fixture.workspace, fixture.user, fixture.session!!.revision)
        assertEquals(buildJsonObject { put("status", "not_committed") }, fixture.commands.resolve(fixture.token, pending.getValue("plan_id") as String, "not-submitted"))
    }

    @Test fun originalReceiptAndResultsRemainUserScopedAndStripPrivateDigests() {
        val fixture = Fixture("receipt_scope"); val receipt = fixture.commit(fixture.plan())
        val id = receipt.getValue("receipt_id").jsonPrimitive.content
        assertEquals(receipt, fixture.reads.receipt(fixture.token, id))
        assertTrue(setOf("session_digest", "request_digest").intersect(receipt.keys).isEmpty())
        for (read in listOf<() -> Any>({ fixture.reads.receipt("original-import-other", id) },
            { fixture.reads.results("original-import-other", id, "row", ReceiptPage(0, 200)) },
            { fixture.reads.receiptByKey("original-import-other", "synthetic-confirm") })) {
            assertEquals("receipt_not_found", assertFailsWith<ReceiptFailure> { read() }.code)
        }
    }

    @Test fun originalSuccessfulReceiptHasNoSubsetRecoveryCapability() {
        val fixture = Fixture("no_recovery"); val receipt = fixture.commit(fixture.plan())
        assertFalse(receipt.keys.any { it.startsWith("retry_context") })
        assertFalse(CashImportCommandService::class.java.methods.any { it.name == "retry_session" || it.name == "retrySession" })
    }

    @Test fun originalFirstHundredBatchFailureRollsBackAllRowsWithoutFallback() {
        val writes = Writes(); val fixture = Fixture("batch_failure", writes::decorate)
        val plan = fixture.plan(); val before = fixture.snapshot()
        writes.active = true; writes.failSources = true
        val failure = assertFailsWith<CashImportCommitFailure> { fixture.commit(plan) }
        writes.active = false
        assertEquals("not_committed", failure.commitState)
        assertEquals(before, fixture.snapshot())
        assertEquals("batch_write", failure.diagnostic.stage); assertEquals("related", failure.diagnostic.scope)
        assertEquals(100, failure.diagnostic.totalLocations)
        assertEquals(100, failure.diagnostic.locations.size)
        assertTrue(failure.diagnostic.locations.all { !it.displayName.isNullOrEmpty() && it.rowNumber != null })
        assertEquals(100, writes.events.count { it == "cash_transactions" })
        assertTrue(writes.events.contains("cash_source_bindings"))
        assertEquals(100, writes.events.indexOfFirst { it == "cash_transaction_components" })
    }

    @Test fun originalMixedBulkPreservesBothAliasesManualEntriesComponentsAndExactBalances() {
        val writes = Writes(); val fixture = Fixture("bulk_mixed", writes::decorate)
        val before = fixture.read { CashSnapshotRepository(it).load().value }
        val plan = fixture.plan()
        assertEquals(32, (plan.getValue("summary") as Map<*, *>)["create_posted"])
        writes.active = true
        val receipt = fixture.commit(plan)
        writes.active = false
        assertEquals(32, writes.events.count { it == "cash_transactions" })
        assertEquals(32, writes.events.indexOfFirst { it == "cash_transaction_components" })
        assertEquals(32, summary(receipt, "created_posted")); assertEquals(32, summary(receipt, "duplicate")); assertEquals(0, summary(receipt, "skipped"))
        fixture.read { context ->
            val rows = CashSourceRows.sourceBatchRows(fixture.original.files).rows
            val repository = CashSourceReadRepository(context)
            val expected = fixture.input.getValue("expected_parts").jsonObject
            val deltas = linkedMapOf("101" to BigDecimal.ZERO, "102" to BigDecimal.ZERO)
            for (index in 0 until 32) {
                fun identity(ref: String): Long {
                    val row = rows.getValue(ref)
                    return repository.forKeys(row.bindingSpecs, listOfNotNull(row.stableEvidenceBindingKey)).map { it.transactionId }.toSet().single()
                }
                val id = identity("file_a:r${index + 1}")
                assertEquals(id, identity("file_b:r${index + 1}"))
                val parent = context.sql.selectFrom(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.ID.eq(id)).fetchSingle()
                assertEquals(buildJsonObject { put("synthetic_index", index) }, parent.sourcePayload)
                val source = fixture.original.files.first().document!!.rows[index].fields!!
                assertEquals(0, parent.amount!!.text.toBigDecimal().compareTo(BigDecimal(source["amount"] as String)))
                assertEquals("原始备注", parent.note)
                assertTrue(parent.manualOverrides!!.jsonObject["note"] != JsonPrimitive(true))
                val entry = assertNotNull(context.entries.current(id))
                assertEquals(JsonPrimitive("人工备注$index"), entry.values["note"]); assertTrue("note" in entry.manualFields)
                val parts = context.sql.selectFrom(CASH_TRANSACTION_COMPONENTS).where(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID.eq(id)).orderBy(CASH_TRANSACTION_COMPONENTS.ORDINAL).fetch()
                val expectedParts = expected.getValue(index.toString()).jsonArray
                assertEquals(expectedParts.size, parts.size)
                for ((part, raw) in parts.zip(expectedParts)) {
                    val value = raw.jsonObject; val account = value.getValue("account_id").jsonPrimitive.content
                    val amount = BigDecimal(value.getValue("amount").jsonPrimitive.content)
                    assertEquals(account.toLong(), part.accountId); assertEquals("CNY", part.currency)
                    assertEquals(0, amount.compareTo(part.amount!!.text.toBigDecimal()))
                    deltas[account] = deltas.getValue(account) + amount
                }
            }
            val after = CashSnapshotRepository(context).load().value
            for ((id, delta) in deltas) {
                val kind = if (id == "101") "cash" else "loan"
                val name = context.sql.select(ACCOUNTS.NAME).from(ACCOUNTS).where(ACCOUNTS.ID.eq(id.toLong())).fetchSingle(ACCOUNTS.NAME)!!
                fun balance(value: JsonObject) = value["accounts"]?.jsonObject?.get(kind)?.jsonObject?.get(name)?.jsonObject?.get("CNY")?.jsonPrimitive?.content?.toBigDecimal() ?: BigDecimal.ZERO
                assertEquals(0, (balance(after) - balance(before)).compareTo(delta), "account $id exact delta")
            }
        }
    }

    @Test fun originalRowAliasFailurePreservesWholeBatchAndBothDuplicateLocations() {
        val writes = Writes(); val fixture = Fixture("row_failure", writes::decorate)
        val plan = fixture.plan(); val before = fixture.snapshot()
        writes.active = true; writes.failSources = true
        val failure = assertFailsWith<CashImportCommitFailure> { fixture.commit(plan) }
        writes.active = false
        assertEquals("not_committed", failure.commitState); assertEquals(before, fixture.snapshot())
        assertEquals("row_write", failure.diagnostic.stage); assertEquals("related", failure.diagnostic.scope)
        assertEquals(2, failure.diagnostic.totalLocations)
        assertTrue(failure.diagnostic.locations.all { !it.displayName.isNullOrEmpty() && it.rowNumber != null })
        assertTrue(writes.events.contains("cash_source_bindings"))
    }

    @Test fun originalFinalFailureRollsBackAllBusinessWritesAndAllowsOriginalRequestRetry() {
        for (phase in listOf("receipt", "projection")) {
            val fixture = Fixture("${phase}_failure"); val plan = fixture.plan(); val before = fixture.snapshot()
            val failing = CashImportCommandService(fixture.database, fixture.store, now = { at }, afterStep = { if (it == phase) error("synthetic final failure") })
            assertEquals("not_committed", assertFailsWith<CashImportCommitFailure> { fixture.commit(plan, service = failing) }.commitState)
            assertEquals(before, fixture.snapshot())
            fixture.read { assertEquals(0, it.sql.fetchCount(CASH_SOURCE_BINDINGS)) }
            assertEquals(1, summary(fixture.commit(plan), "created_posted"))
        }
    }

    @Test fun originalEquivalentNewSessionKeepsSameRecordAndBalanceVersion() {
        val fixture = Fixture("duplicate"); val first = fixture.commit(fixture.plan())
        val before = snapshotVersion(fixture)
        val second = fixture.commit(fixture.plan(), "new-import")
        assertEquals(JsonPrimitive("no_changes"), second["status"]); assertEquals(1, summary(second, "duplicate"))
        assertEquals(rowId(fixture.results(first).first()), rowId(fixture.results(second).first()))
        assertEquals(before, snapshotVersion(fixture))
    }

    @Test fun originalBadFileAndWhitelistSkipKeepDistinctResultStatusAndWriteProof() {
        for (failed in listOf(false, true)) {
            val fixture = Fixture(if (failed) "failed_file" else "normal_skip")
            val receipt = fixture.commit(fixture.plan())
            assertEquals(JsonPrimitive(if (failed) "partial" else "no_changes"), receipt["status"])
            assertEquals(buildJsonObject { put("has_new_failures", false); put("has_business_writes", failed); put("reason_codes", JsonArray(emptyList())) }, receipt["commit_outcome"])
        }
    }

    @Test fun originalSecondRowExecutionFailureLeavesNoReceiptOrRetryCapsule() {
        val fixture = Fixture("execution_failure"); val plan = fixture.plan(); val before = fixture.snapshot()
        var calls = 0
        val failing = CashImportCommandService(fixture.database, fixture.store, now = { at }, afterStep = { if (it == "row_write" && ++calls == 2) error("synthetic internal failure after write") })
        assertEquals("not_committed", assertFailsWith<CashImportCommitFailure> { fixture.commit(plan, service = failing) }.commitState)
        assertEquals(2, calls); assertEquals(before, fixture.snapshot()); assertEquals(1, fixture.store.entries.size)
        fixture.read { assertEquals(0, it.sql.fetchCount(CASH_MUTATION_RECEIPTS, CASH_MUTATION_RECEIPTS.IDEMPOTENCY_KEY.eq("synthetic-confirm"))) }
    }

    @Test fun originalResolvePropagatesEveryNonLockStorageFailure() {
        val fixture = Fixture("resolve_storage"); val plan = fixture.plan()
        for (code in listOf("storage.connect", "storage.readonly", "storage.schema")) {
            var connections = 0
            val failing = Database(fixture.jdbcUrl, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")) {
                if (++connections == 2) throw StorageFailure(code) else it
            }
            assertEquals(code, assertFailsWith<StorageFailure> {
                CashImportCommandService(failing, fixture.store).resolve(fixture.token, plan.getValue("plan_id") as String, "synthetic-storage-error")
            }.code)
            assertEquals(2, connections)
        }
    }
}
