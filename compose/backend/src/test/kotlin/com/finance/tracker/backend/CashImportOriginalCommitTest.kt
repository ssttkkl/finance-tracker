package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.CashDiagnosticDto
import com.finance.tracker.business.contracts.CashDiagnosticLocationDto
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.nio.file.Path
import java.nio.file.Files
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportOriginalCommitTest {
    private val at = Instant.parse("2026-10-01T09:00:00Z")
    private class Store(at: Instant) : ParsedImportStore(clock = { at }) {
        val entries = linkedMapOf<String, ParsedImportObject>()
        @Synchronized fun erase() = entries.clear()
        @Synchronized override fun readRaw(id: String) = entries[id] ?: throw ParsedImportNotFound()
        @Synchronized override fun writeRaw(id: String, content: ByteArray, expected: String?, expiresAt: Instant) {
            if (entries[id]?.etag != expected) throw ParsedImportRevisionConflict()
            entries[id] = ParsedImportObject(content.copyOf(), digest(content))
        }
        @Synchronized override fun deleteRaw(id: String) { entries.remove(id) }
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
        @Suppress("UNCHECKED_CAST") val defaultInput get() = cashSourceValue(input.getValue("input")) as Map<String, Any?>
        fun variant(name: String): List<ParsedImportFile> {
            val value = input.getValue("variants").jsonObject.getValue(name).jsonObject
            return ParsedImportCodec().decode(value.getValue("session_id").jsonPrimitive.content, value.getValue("session").toString().encodeToByteArray()).files
        }
        fun choices(files: List<ParsedImportFile> = original.files, mapped: Boolean = true, rows: List<Map<String, Any?>> = emptyList(), relations: List<Map<String, Any?>> = emptyList()): Map<String, Any?> = mapOf(
            "account_selections" to if (mapped) CashImportFunding.scan(files).groups.map { mapOf("source_account_ref" to it.sourceAccountRef, "account_id" to "101", "expected_account_revision" to 1) } else emptyList(),
            "row_choices" to rows, "relation_choices" to relations, "entry_choices" to emptyList<Any?>())
        fun plan(files: List<ParsedImportFile> = original.files, choices: Map<String, Any?> = defaultInput): Map<String, Any?> {
            val staged = store.create(workspace, user, files)
            val result = CashImportHttpService(database, store).preview(token, staged.sessionId, 1, null,
                choices)
            session = store.get(staged.sessionId, workspace, user)
            return result
        }
        fun repreview(choices: Map<String, Any?>): Map<String, Any?> {
            val current = session!!
            val result = CashImportHttpService(database, store).preview(token, current.sessionId, current.revision.toLong(),
                current.plan!!.getValue("plan_id") as String, choices)
            session = store.get(current.sessionId, workspace, user)
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
        fun <T> write(block: (CashSqlContext) -> T) = database.write { block(CashSqlContext(it, workspace, database.sqlite)) }
        fun page(receipt: JsonObject) = reads.results(token, receipt.getValue("receipt_id").jsonPrimitive.content, "row", ReceiptPage(0, 200))
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
        var failRelationAt = 0
        var failEmptyPages = false
        var relationWrites = 0
        var projectionWrites = 0
        lateinit var connection: Connection
        val events = mutableListOf<String>()
        private fun label(query: String?): String? {
            val sql = query?.replace("\"", "")?.replace("`", "")?.lowercase() ?: return null
            return listOf("cash_transactions", "cash_transaction_components", "cash_source_bindings", "transaction_relations", "cash_source_empty_pages").firstOrNull {
                Regex("\\binsert\\s+into\\s+(?:\\w+\\.)?$it\\b").containsMatchIn(sql)
            }
        }
        private fun invoke(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? =
            try { method.invoke(target, *(args ?: emptyArray())) } catch (error: InvocationTargetException) { throw error.targetException }
        fun decorate(connection: Connection): Connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            this.connection = connection
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
                    val sql = (prepared ?: args?.firstOrNull() as? String).orEmpty().replace("\"", "").replace("`", "").lowercase()
                    if (Regex("\\b(insert\\s+into|update|delete\\s+from)\\s+(?:\\w+\\.)?cash_projection(?:s|_states|_members)\\b").containsMatchIn(sql)) projectionWrites++
                    val writes = if (method.name.endsWith("Batch")) batch.toList().also { batch.clear() } else listOfNotNull(query)
                    events += writes
                    if (failSources && "cash_source_bindings" in writes) throw SQLException("synthetic failure after persisted source aliases", "08006")
                    relationWrites += writes.count { it == "transaction_relations" }
                    if ("transaction_relations" in writes && failRelationAt > 0 && relationWrites >= failRelationAt) error("synthetic failure after persisted relation")
                    if (failEmptyPages && "cash_source_empty_pages" in writes) error("synthetic failure after persisted empty page")
                }
                result
            } as Statement
        }
    }

    private class ReadFault(private val discovery: Boolean) {
        @Volatile var armed = false
        @Volatile var count = 0
        val errorId = "a".repeat(32)
        private fun invoke(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? =
            try { method.invoke(target, *(args ?: emptyArray())) } catch (error: InvocationTargetException) { throw error.targetException }
        fun decorate(connection: Connection): Connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            val result = invoke(connection, method, args)
            if (result is Statement && method.name in setOf("prepareStatement", "createStatement")) statement(result) else result
        } as Connection
        private fun statement(statement: Statement): Statement {
            val type = if (statement is PreparedStatement) PreparedStatement::class.java else Statement::class.java
            return Proxy.newProxyInstance(Statement::class.java.classLoader, arrayOf(type)) { _, method, args ->
                val result = invoke(statement, method, args)
                if (armed && method.name in setOf("executeQuery", "execute") && Thread.currentThread().stackTrace.any {
                    it.className == "com.finance.tracker.backend." + if (discovery) "CashImportRelationDiscovery" else "CashImportBusinessPlanBuilder"
                }) {
                    count++
                    if (discovery) error("private bill body")
                    throw CashImportPlanError("plan_input_invalid", diagnostic = CashDiagnosticDto(errorId, "row_planning", "row",
                        listOf(CashDiagnosticLocationDto(rowRef = "file_a:r1")), 1))
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

    private fun planCount(plan: Map<String, Any?>, key: String) = ((plan["summary"] as Map<*, *>)[key] as? Number)?.toInt() ?: 0
    private fun rejectChanged(fixture: Fixture, plan: Map<String, Any?>, key: String = "synthetic-confirm") {
        val before = fixture.snapshot()
        assertEquals("plan_changed", assertFailsWith<CashImportPlanError> { fixture.commit(plan, key) }.code)
        assertEquals(before, fixture.snapshot())
    }
    private fun ids(fixture: Fixture, receipt: JsonObject) = fixture.results(receipt).map(::rowId).toSet()
    private fun failedAtomically(fixture: Fixture, plan: Map<String, Any?>, service: CashImportCommandService = fixture.commands): CashImportCommitFailure {
        val before = fixture.snapshot()
        val failure = assertFailsWith<CashImportCommitFailure> { fixture.commit(plan, service = service) }
        assertEquals("not_committed", failure.commitState)
        assertEquals(before, fixture.snapshot())
        return failure
    }

    @Test fun originalConcurrentConfirmationsConsumeSessionOnceForSameAndDifferentKeys() {
        for (same in listOf(true, false)) {
            val fixture = Fixture(if (same) "concurrent_same" else "concurrent_different")
            val plan = fixture.plan(); val start = CountDownLatch(1); val ready = CountDownLatch(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val requests = listOf("race-1", if (same) "race-1" else "race-2").map { key -> executor.submit<JsonObject> {
                    ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS))
                    try { fixture.commit(plan, key, CashImportCommandService(fixture.database, fixture.store, now = { at })) }
                    catch (failure: ReceiptConflict) { buildJsonObject { put("code", failure.code); put("receipt_id", failure.receiptId) } }
                } }
                assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown()
                val results = requests.map { it.get(10, TimeUnit.SECONDS) }
                assertEquals(results[0]["receipt_id"], results[1]["receipt_id"])
                if (same) assertEquals(results[0], results[1])
                else assertEquals(1, results.count { it["code"] == JsonPrimitive("session_committed") })
                fixture.read { assertEquals(1, it.sql.fetchCount(CASH_TRANSACTIONS, CASH_TRANSACTIONS.SOURCE_TYPE.eq("alipay"))) }
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun originalAlreadyPreviewedIndependentSessionRejectsNewlyCommittedFactsAndMirrors() {
        for (mirror in listOf(false, true)) {
            val fixture = Fixture(if (mirror) "stale_mirror" else "stale_single")
            val first = fixture.plan(); val second = fixture.plan()
            fixture.commit(first, "first-session")
            rejectChanged(fixture, second, "second-session")
        }
    }

    @Test fun originalEquivalentAliasPreviewRejectsAfterOtherFileCommitsWithoutChangingRecord() {
        val fixture = Fixture("stale_alias")
        val first = fixture.plan(); val second = fixture.plan(fixture.variant("changed"))
        fixture.commit(first, "original-file")
        rejectChanged(fixture, second, "other-file")
    }

    @Test fun originalManualChangeAfterPreviewRejectsEntireCommandAndPreservesManualValue() {
        val fixture = Fixture("current_edit"); val plan = fixture.plan()
        fixture.write { context -> context.sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.NOTE, "刚刚修改")
            .set(CASH_TRANSACTIONS.MANUAL_OVERRIDES, buildJsonObject { put("note", true) })
            .set(CASH_TRANSACTIONS.ROW_REVISION, CASH_TRANSACTIONS.ROW_REVISION.plus(1L)).where(CASH_TRANSACTIONS.ID.eq(1003L)).execute() }
        rejectChanged(fixture, plan)
    }

    @Test fun originalSourceDescriptionUpdateDoesNotRewriteBalancePayloadOrVersion() {
        val fixture = Fixture("source_update")
        fixture.commit(fixture.plan())
        val before = snapshotPayload(fixture) to snapshotVersion(fixture)
        val receipt = fixture.commit(fixture.plan(fixture.variant("changed")), "source-update")
        assertEquals(1, summary(receipt, "updated_posted"))
        assertEquals(before, snapshotPayload(fixture) to snapshotVersion(fixture))
    }

    @Test fun originalDeferredSourceUpdatePreservesCurrentDescriptionAndKeepsPendingExplanation() {
        val fixture = Fixture("deferred_update")
        assertEquals(1, summary(fixture.commit(fixture.plan()), "updated_posted"))
        fixture.read { context ->
            val parent = context.sql.selectFrom(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.ID.eq(1003L)).fetchSingle()
            assertEquals("原始备注", parent.note)
            assertEquals(buildJsonObject { put("status", "原来源状态") }, parent.relationMetadata)
            val issue = context.sql.selectFrom(CASH_TRANSACTION_ISSUES).where(CASH_TRANSACTION_ISSUES.TRANSACTION_ID.eq(1003L)).fetchSingle()
            assertEquals(JsonPrimitive("新的原文"), issue.pendingFields!!.jsonObject["note"])
            assertEquals(buildJsonObject { put("status", "新来源状态") }, issue.pendingFields!!.jsonObject["_source_explanation"])
        }
    }

    @Test fun originalNewCounterpartIssueRejectsOldRelationPlanAndIndependentRowsTogether() {
        val fixture = Fixture("stale_issue"); val plan = fixture.plan()
        assertEquals(1, planCount(plan, "relations_accepted"))
        fixture.write { CashSourceWriteRepository(it).saveIssue(1003L, "other-current-issue", "posting_incomplete") }
        rejectChanged(fixture, plan)
    }

    @Test fun originalMirrorAndRowsCommitTogetherWithActualDistinctEndpoints() {
        val fixture = Fixture("mirror"); val plan = fixture.plan()
        assertEquals(1, ((plan.getValue("item_counts") as Map<*, *>)["confirmed"] as Number).toInt())
        val receipt = fixture.commit(plan)
        assertEquals(1, summary(receipt, "relations_confirmed")); assertEquals(2, summary(receipt, "created_posted"))
        assertEquals(JsonPrimitive(0), receipt["pending_item_count"])
        fixture.read { context ->
            val relation = context.sql.selectFrom(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.STATE.eq("confirmed")).fetchSingle()
            assertNotEquals(relation.primaryTransactionId, relation.secondaryTransactionId)
            assertNotNull(relation.primaryComponentId); assertNotNull(relation.secondaryComponentId)
        }
    }

    @Test fun originalMergedAttentionCountsParentsButPagesUseOneCurrentProjection() {
        val writes = Writes(); val fixture = Fixture("receipt_locations", writes::decorate); val plan = fixture.plan()
        val command = CashImportCommandService(fixture.database, fixture.store, now = { at }, afterStep = { step ->
            if (step == "relation_save") {
                val context = CashSqlContext(writes.connection, fixture.workspace, fixture.database.sqlite, now = { at })
                val parents = context.sql.select(CASH_TRANSACTIONS.ID).from(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.SOURCE_TYPE.`in`("alipay", "icbc_debit")).fetch(CASH_TRANSACTIONS.ID)
                parents.forEach { CashSourceWriteRepository(context).saveIssue(it!!, "relation:" + sourceDigest("synthetic-current-attention"), "relation_context_changed") }
            }
        })
        val receipt = fixture.commit(plan, service = command)
        assertEquals(JsonPrimitive(2), receipt["needs_attention_count"]); assertEquals(JsonPrimitive(1), receipt["pending_item_count"])
        assertFalse("affected_transaction_ids" in receipt); assertFalse("projection_refs" in receipt)
        val page = fixture.page(receipt); val refs = page.getValue("projection_refs").jsonArray
        assertEquals(2, refs.size); assertEquals(1, refs.map { it.jsonObject.getValue("projection_id") }.toSet().size)
        assertEquals(receipt["projection_version"], page["projection_version"])
    }

    @Test fun originalOldReceiptPageTracksCurrentSplitAndDeletionWithoutChangingSummary() {
        val fixture = Fixture("receipt_evolution"); val receipt = fixture.commit(fixture.plan())
        val first = fixture.page(receipt)
        fun refs(page: JsonObject) = page.getValue("projection_refs").jsonArray.map { it.jsonObject }
        assertEquals(1, refs(first).map { it.getValue("projection_id") }.toSet().size)
        val members = first.getValue("items").jsonArray.map(::rowId).toSet()
        fixture.write { context ->
            context.sql.update(TRANSACTION_RELATIONS).set(TRANSACTION_RELATIONS.ACTIVE, false)
                .set(TRANSACTION_RELATIONS.ROW_REVISION, TRANSACTION_RELATIONS.ROW_REVISION.plus(1L)).where(TRANSACTION_RELATIONS.STATE.eq("confirmed")).execute()
            CashProjectionService.maintainInSession(context, members)
        }
        assertEquals(2, refs(fixture.page(receipt)).map { it.getValue("projection_id") }.toSet().size)
        val removed = members.min()
        fixture.write { context ->
            context.sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.DELETED_AT, at).set(CASH_TRANSACTIONS.ROW_REVISION, CASH_TRANSACTIONS.ROW_REVISION.plus(1L))
                .where(CASH_TRANSACTIONS.ID.eq(removed)).execute()
            CashProjectionService.maintainInSession(context, setOf(removed))
        }
        val remaining = fixture.page(receipt)
        assertEquals(members - removed, refs(remaining).map { it.getValue("transaction_id").jsonPrimitive.content.toLong() }.toSet())
        assertTrue(remaining.getValue("projection_version").jsonPrimitive.long > receipt.getValue("projection_version").jsonPrimitive.long)
        assertEquals(receipt, fixture.reads.receipt(fixture.token, receipt.getValue("receipt_id").jsonPrimitive.content))
    }

    @Test fun originalRelationInsertFailureRollsBackRowsRelationsAndAttentionWithBothLocations() {
        val writes = Writes(); val fixture = Fixture("relation_failure", writes::decorate); val plan = fixture.plan()
        writes.active = true; writes.failRelationAt = 1
        val before = fixture.snapshot()
        val failure = assertFailsWith<CashImportCommitFailure> { fixture.commit(plan) }
        writes.active = false
        assertEquals("not_committed", failure.commitState); assertEquals(before, fixture.snapshot())
        assertEquals(1, writes.relationWrites)
        assertEquals("relation_save", failure.diagnostic.stage); assertEquals("related", failure.diagnostic.scope)
        assertEquals(2, failure.diagnostic.totalLocations)
        assertTrue(failure.diagnostic.locations.all { !it.displayName.isNullOrEmpty() && it.rowNumber != null })
    }

    @Test fun originalFullRefundBundleStaysJointAndZeroNetRemainsVisibleOrDefersAllMembers() {
        for (defer in listOf(true, false)) {
            val fixture = Fixture(if (defer) "refund_defer" else "refund_accept")
            var plan = fixture.plan()
            fun items() = plan.getValue("items") as List<*>
            assertEquals(1, items().size)
            val display = (items().single() as Map<*, *>)["display"] as Map<*, *>
            assertEquals(0, display["amount"].toString().toBigDecimal().compareTo(BigDecimal.ZERO))
            if (defer) {
                val group = (fixture.session!!.plan!!.getValue("relation_groups") as List<*>).single() as Map<*, *>
                plan = fixture.repreview(fixture.choices(relations = listOf(mapOf("suggestion_id" to group["suggestion_id"], "action" to "defer"))))
                assertEquals(4, items().size)
                assertTrue(items().all { (it as Map<*, *>)["confirmation_status"] == "pending" })
            }
            val receipt = fixture.commit(plan)
            assertEquals(4, summary(receipt, "created_posted")); assertEquals(1, summary(receipt, if (defer) "relations_deferred" else "relations_confirmed"))
            val members = ids(fixture, receipt)
            fixture.read { context ->
                val relations = context.sql.selectFrom(TRANSACTION_RELATIONS).fetch()
                assertTrue(relations.size >= 3); assertEquals(1, relations.map { it.decisionGroupId }.toSet().size)
                assertEquals(setOf(if (defer) "draft" else "confirmed"), relations.map { it.state }.toSet())
                val projections = context.sql.selectFrom(CASH_PROJECTIONS).where(CASH_PROJECTIONS.ROOT_CASH_TRANSACTION_ID.`in`(members)).fetch()
                assertEquals(if (defer) 4 else 1, projections.size); assertTrue(projections.all { it.visible == true })
                if (!defer) { assertEquals(0, projections.single().netAmount!!.text.toBigDecimal().compareTo(BigDecimal.ZERO)); assertNull(projections.single().hiddenReason) }
            }
        }
    }

    @Test fun originalFailureOnSecondJointEdgeRollsBackWholeRefundBundleAndOtherGroup() {
        val writes = Writes(); val fixture = Fixture("refund_failure", writes::decorate); val plan = fixture.plan()
        val before = fixture.snapshot(); writes.active = true; writes.failRelationAt = 2
        val failure = assertFailsWith<CashImportCommitFailure> { fixture.commit(plan) }
        writes.active = false
        assertEquals("not_committed", failure.commitState); assertEquals(2, writes.relationWrites); assertEquals(before, fixture.snapshot())
    }

    @Test fun originalRequiredExistingRowFailureCannotSubstituteOldFactForRelationGroup() {
        val writes = Writes(); val fixture = Fixture("required_failure", writes::decorate); val plan = fixture.plan()
        var reached = false
        val command = CashImportCommandService(fixture.database, fixture.store, now = { at }, afterStep = { step ->
            if (step == "row_write") {
                val note = writes.connection.jooq(fixture.database.sqlite).select(CASH_TRANSACTIONS.NOTE).from(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.ID.eq(1003L)).fetchSingle(CASH_TRANSACTIONS.NOTE)
                if (note == "新的原文") { reached = true; error("synthetic existing operation failure") }
            }
        })
        failedAtomically(fixture, plan, command); assertTrue(reached)
    }

    @Test fun originalConstraintFailureAfterRowWriteRejectsEntireCommandWithoutPartialRows() {
        val writes = Writes(); val fixture = Fixture("integrity_failure", writes::decorate); val plan = fixture.plan()
        var reached = false
        val command = CashImportCommandService(fixture.database, fixture.store, now = { at }, afterStep = { step ->
            if (step == "row_write" && !reached) {
                reached = true
                writes.connection.createStatement().use { it.executeUpdate("UPDATE cash_transactions SET currency='x' WHERE source_type='alipay'") }
            }
        })
        failedAtomically(fixture, plan, command); assertTrue(reached)
    }

    @Test fun originalReimportPreservesHistoricalRelationFailureWithoutNewIssues() {
        val fixture = Fixture("history"); val receipt = fixture.commit(fixture.plan()); val members = ids(fixture, receipt)
        fixture.write { context -> members.forEach { CashSourceWriteRepository(context).saveIssue(it, "relation:historical-failure", "relation_save_failed") } }
        CashProjectionService(fixture.database, fixture.workspace).rebuild()
        val second = fixture.commit(fixture.plan(), "reimport")
        assertEquals(2, summary(second, "duplicate"))
        fixture.read { assertEquals(2, it.sql.fetchCount(CASH_TRANSACTION_ISSUES)) }
    }

    @Test fun originalIdentityDraftReimportRetainsItsUnresolvedCandidateSet() {
        val fixture = Fixture("identity_draft"); val receipt = fixture.commit(fixture.plan())
        assertEquals(1, summary(receipt, "saved_draft"))
        fixture.read { assertEquals(1, it.sql.fetchCount(CASH_ISSUE_CANDIDATES)) }
        fixture.commit(fixture.plan(), "same-identity-draft")
        fixture.read { assertEquals(1, it.sql.fetchCount(CASH_ISSUE_CANDIDATES)) }
    }

    @Test fun originalPartialDraftKeepsKnownAccountButInvalidComponentStructureRejectsPreview() {
        val fixture = Fixture("partial_draft"); val receipt = fixture.commit(fixture.plan())
        assertEquals(1, summary(receipt, "saved_draft")); assertEquals(1, summary(receipt, "created_posted"))
        val id = rowId(fixture.results(receipt).first())
        fixture.read { context ->
            val part = context.sql.selectFrom(CASH_TRANSACTION_COMPONENTS).where(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID.eq(id)).fetchSingle()
            assertEquals(101L, part.accountId); assertNull(part.amount); assertNull(part.currency)
        }
        val before = fixture.snapshot()
        val files = fixture.variant("invalid")
        val invalid = fixture.choices(files, rows = listOf(mapOf("row_ref" to "file_c:r1", "action" to "defer", "patch" to mapOf("components" to listOf(mapOf("account_id" to "101"))))))
        assertEquals("plan_input_invalid", assertFailsWith<CashImportPlanError> { fixture.plan(files, invalid) }.code)
        assertEquals(before, fixture.snapshot())
    }

    @Test fun originalSuccessClearsSessionBodyAndFailureKeepsOnlyOriginalSession() {
        for (failure in listOf(true, false)) {
            val fixture = Fixture(if (failure) "cleanup_failure" else "cleanup_success"); val plan = fixture.plan()
            val staged = fixture.session!!
            if (failure) {
                val command = CashImportCommandService(fixture.database, fixture.store, now = { at }, afterStep = { if (it == "row_write") error("synthetic row failure") })
                failedAtomically(fixture, plan, command)
                val current = fixture.store.get(staged.sessionId, fixture.workspace, fixture.user)
                assertFalse(current.cancelled); assertTrue(current.files.isNotEmpty()); assertNotNull(current.plan); assertEquals(1, fixture.store.entries.size)
            } else {
                val receipt = fixture.commit(plan)
                val current = fixture.store.get(staged.sessionId, fixture.workspace, fixture.user)
                assertFalse(receipt.keys.any { it.startsWith("retry_context") }); assertTrue(current.cancelled); assertTrue(current.files.isEmpty()); assertNull(current.plan)
            }
        }
    }

    @Test fun originalChangedRelationAllocationIsNotMistakenForSameDecision() {
        val fixture = Fixture("allocation"); val first = fixture.plan(); val stale = fixture.plan()
        val receipt = fixture.commit(first, "first-allocation"); val members = ids(fixture, receipt)
        fixture.write { context ->
            val relation = context.sql.selectFrom(TRANSACTION_RELATIONS).fetchSingle()
            CurrentCashRelationWriteRepository(context).replace(relation.id!!, mapOf("applied_amount" to "5"), relation.rowRevision!!)
            CashProjectionService.maintainInSession(context, members)
        }
        rejectChanged(fixture, stale, "second-allocation")
    }

    @Test fun originalDraftFinalizationCanReplaceComponentAtSameOrdinalWithNewIdentity() {
        val fixture = Fixture("replace_component"); val first = fixture.commit(fixture.plan()); val id = rowId(fixture.results(first).first())
        val old = fixture.read { it.sql.select(CASH_TRANSACTION_COMPONENTS.ID).from(CASH_TRANSACTION_COMPONENTS).where(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID.eq(id)).fetchSingle(CASH_TRANSACTION_COMPONENTS.ID) }
        val patch = mapOf("account_id" to "101", "components" to listOf(mapOf("ordinal" to 0, "account_id" to "101", "amount" to "-12.5", "currency" to "CNY")))
        val plan = fixture.plan(choices = fixture.choices(rows = listOf(mapOf("row_ref" to "file_a:r1", "action" to "apply", "patch" to patch))))
        assertEquals(1, planCount(plan, "finalize_draft")); assertEquals(1, summary(fixture.commit(plan, "finalize"), "finalized_draft"))
        fixture.read { context ->
            val part = context.sql.selectFrom(CASH_TRANSACTION_COMPONENTS).where(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID.eq(id)).fetchSingle()
            assertNotEquals(old, part.id); assertEquals(0L, part.ordinal)
            assertEquals("posted", context.sql.select(CASH_TRANSACTIONS.STATE).from(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.ID.eq(id)).fetchSingle(CASH_TRANSACTIONS.STATE))
        }
    }

    @Test fun originalDraftSwapsOrdinalsWithoutReplacingComponentIdentities() {
        val fixture = Fixture("swap_components"); val first = fixture.commit(fixture.plan()); val id = rowId(fixture.results(first).first())
        fun parts() = fixture.read { context -> context.sql.selectFrom(CASH_TRANSACTION_COMPONENTS).where(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID.eq(id))
            .orderBy(CASH_TRANSACTION_COMPONENTS.ORDINAL).fetch().map { Triple(it.id!!, it.accountId!!, it.ordinal!!) } }
        val original = parts()
        val patch = mapOf("cash_granularity" to "aggregate", "account_id" to null, "components" to original.map { (part, account, ordinal) ->
            mapOf("component_id" to part.toString(), "account_id" to account.toString(), "ordinal" to 1L - ordinal, "amount" to "-6.25", "currency" to "CNY") })
        val plan = fixture.plan(choices = fixture.choices(mapped = false, rows = listOf(mapOf("row_ref" to "file_a:r1", "action" to "defer", "patch" to patch))))
        assertEquals(1, summary(fixture.commit(plan, "swap-components"), "saved_draft"))
        assertEquals(original.map { (part, account, ordinal) -> Triple(part, account, 1L - ordinal) }.toSet(), parts().toSet())
    }

    @Test fun originalImportedTypeConflictKeepsMembersAndDoesNotRepublishProjection() {
        val writes = Writes(); val fixture = Fixture("type_conflict", writes::decorate)
        val first = fixture.commit(fixture.plan()); val members = ids(fixture, first)
        val changed = fixture.variant("changed"); val plan = fixture.plan(changed, fixture.choices(changed))
        assertEquals(emptyList<Any?>(), plan["items"]); assertEquals(1, planCount(plan, "skip"))
        writes.active = true
        val receipt = fixture.commit(plan, "type-update")
        writes.active = false
        assertEquals(0, summary(receipt, "updated_posted")); assertEquals(1, summary(receipt, "skipped")); assertEquals(0, writes.projectionWrites)
        fixture.read { context ->
            assertEquals(true, context.sql.select(TRANSACTION_RELATIONS.ACTIVE).from(TRANSACTION_RELATIONS).fetchSingle(TRANSACTION_RELATIONS.ACTIVE))
            assertEquals(1, context.sql.fetchCount(CASH_PROJECTIONS, CASH_PROJECTIONS.ROOT_CASH_TRANSACTION_ID.`in`(members)))
        }
    }

    @Test fun originalEmptyPageFailureRollsBackEntireCommandAfterActualWrite() {
        val writes = Writes(); val fixture = Fixture("empty_page", writes::decorate); val plan = fixture.plan(); val before = fixture.snapshot()
        writes.active = true; writes.failEmptyPages = true
        val failure = assertFailsWith<CashImportCommitFailure> { fixture.commit(plan) }
        writes.active = false
        assertEquals("not_committed", failure.commitState); assertTrue("cash_source_empty_pages" in writes.events); assertEquals(before, fixture.snapshot())
    }

    @Test fun originalConnectionFailureAfterSecondRowStopsOuterTransactionInsteadOfSkippingRow() {
        val fixture = Fixture("connection_failure"); val plan = fixture.plan(); var calls = 0
        val command = CashImportCommandService(fixture.database, fixture.store, now = { at }, afterStep = { if (it == "row_write" && ++calls == 2) throw SQLException("synthetic driver failure", "08006") })
        failedAtomically(fixture, plan, command); assertEquals(2, calls)
        fixture.read { assertEquals(0, it.sql.fetchCount(CASH_TRANSACTIONS, CASH_TRANSACTIONS.SOURCE_TYPE.eq("alipay"))) }
    }

    @Test fun originalRejectedDocumentNamespaceDoesNotBlockOtherLegalRowsOrEmptyPages() {
        for (empty in listOf(false, true)) {
            val fixture = Fixture(if (empty) "namespace_empty" else "namespace_rows"); val plan = fixture.plan()
            assertEquals(1, planCount(plan, "create_posted")); assertEquals(if (empty) 0 else 2, planCount(plan, "skip"))
            val receipt = fixture.commit(plan)
            assertEquals(1, summary(receipt, "created_posted")); assertEquals(if (empty) 0 else 2, summary(receipt, "skipped"))
            assertEquals(JsonPrimitive(false), receipt.getValue("commit_outcome").jsonObject["has_new_failures"])
        }
    }

    @Test fun originalInternalDiscoveryProgrammingErrorStopsPreviewWithoutFinancialWrites() {
        val fault = ReadFault(discovery = true); val fixture = Fixture("discovery_error", fault::decorate)
        val before = fixture.snapshot(); fault.armed = true
        assertFailsWith<IllegalStateException> { fixture.plan() }
        fault.armed = false
        assertEquals(1, fault.count); assertEquals(before, fixture.snapshot())
        val staged = fixture.store.get(fixture.store.entries.keys.single(), fixture.workspace, fixture.user)
        assertEquals(1.toBigInteger(), staged.revision); assertNull(staged.plan)
    }

    @Test fun originalRevalidationConversionPreservesInnerRowDiagnosticInActualHttpResponse() = testApplication {
        val fault = ReadFault(discovery = false); val fixture = Fixture("revalidation_context", fault::decorate)
        val plan = fixture.plan(); val before = fixture.snapshot(); val staged = fixture.session!!
        application { financeModule(fixture.database, cashImports = CashImportHttpService(fixture.database, fixture.store, fixture.commands)) }
        fault.armed = true
        val response = client.post("/api/v1/cash-import/plans/${plan.getValue("plan_id")}/commit") {
            header(HttpHeaders.Authorization, "Bearer ${fixture.token}"); header("Idempotency-Key", "synthetic-confirm")
            contentType(ContentType.Application.Json); setBody(buildJsonObject { put("expected_session_revision", staged.revision.toLong()) }.toString())
        }
        fault.armed = false
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, fault.count); assertEquals(before, fixture.snapshot())
        assertEquals(409, response.status.value, body.toString())
        val error = body.getValue("error").jsonObject
        assertEquals(JsonPrimitive("plan_changed"), error["code"]); assertEquals(JsonPrimitive(false), error["retryable"])
        assertEquals(JsonPrimitive("not_committed"), body["commit_state"])
        val diagnostic = error.getValue("diagnostic").jsonObject
        assertEquals(JsonPrimitive("row_planning"), diagnostic["stage"]); assertEquals(JsonPrimitive("row"), diagnostic["scope"])
        assertEquals(JsonPrimitive(fault.errorId), diagnostic["error_id"])
        val location = diagnostic.getValue("locations").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("file_a:r1"), location["row_ref"]); assertTrue(location.getValue("display_name").jsonPrimitive.content.isNotEmpty())
        assertEquals(JsonPrimitive("file_a"), location["file_ref"]); assertEquals(JsonPrimitive(1), location["row_number"])
        assertEquals(staged.plan, fixture.store.get(staged.sessionId, fixture.workspace, fixture.user).plan)
        System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let { root ->
            val path = Path.of(root); Files.createDirectories(path)
            Files.writeString(path.resolve("cash-import-revalidation-error.json"), buildJsonObject {
                put("backend", if (fixture.database.sqlite) "sqlite" else "postgresql"); put("status", response.status.value)
                put("plan_id", plan.getValue("plan_id") as String); put("revision", staged.revision.toLong()); put("body", body)
            }.toString())
        }
    }
}
