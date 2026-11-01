package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.nio.file.Path
import java.time.Instant
import java.sql.Connection
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationTargetException
import java.sql.Statement
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.Base64
import java.nio.file.Files
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashDraftSaveContractTest {
    private val at = Instant.parse("2026-10-01T09:00:00Z")
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_DRAFT_SAVE_CONTRACT_DIR")))
    private fun names(label: String) = categoryDeleteBody(root().resolve("scenarios.json").readText()).jsonObject.filterValues { it.jsonObject["label"] == JsonPrimitive(label) }.keys
    private inner class Fixture(val name: String) {
        val directory = root().resolve(name)
        private val config = categoryDeleteBody(directory.resolve("database.json").readText()).jsonObject
        val input = categoryDeleteBody(directory.resolve("input.json").readText()).jsonObject
        val metadata = categoryDeleteBody(directory.resolve("metadata.json").readText()).jsonObject
        val params = metadata.getValue("params").jsonObject
        val workspace = config.getValue("workspace").jsonPrimitive.content
        val user = config.getValue("user").jsonPrimitive.content
        val id = input.getValue("transaction_id").jsonPrimitive.content
        val token = "original-import-owner"
        val key = input.getValue("idempotency_key").jsonPrimitive.content
        fun database(decorate: (Connection) -> Connection = { it }) = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"), decorate)
        val database = database()
        val patch: Any? get() {
            val value = cashSourceValue(input.getValue("patch")) as Map<*, *>
            val invalid = input.getValue("non_string_patch_keys").jsonArray.map { it.jsonPrimitive.content }.toSet()
            return value.entries.associate { (key, item) -> (if (key in invalid) key.toString().toInt() else key) to item }
        }
        fun service(step: (String) -> Unit = {}, outcome: () -> Unit = {}, db: Database = database, budget: Int = 64 * 1024 * 1024) =
            CashDraftSaveService(db, now = { at }, afterStep = step, beforeOutcomeRead = outcome, maxInputBytes = budget)
        fun save(service: CashDraftSaveService = service()) = service.save(token, id, input["expected_revision"], patch, key)
        fun detail() = CashRecordDetailService(database, now = { at }).detail(token, id)
        fun <T> read(block: (CashSqlContext) -> T) = database.read { block(CashSqlContext(it, workspace, database.sqlite, now = { at })) }
        fun <T> write(block: (CashSqlContext) -> T) = database.write { block(CashSqlContext(it, workspace, database.sqlite, now = { at })) }
        fun parent() = read { it.sql.selectFrom(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace)).and(CASH_TRANSACTIONS.ID.eq(id.toLong())).fetchSingle() }
        fun snapshot() = database.read { connection -> DefaultSchema.DEFAULT_SCHEMA.tables.sortedBy { it.name }.associate { table ->
            table.name to connection.createStatement().use { statement -> statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
                val metadata = rows.metaData
                buildList { while (rows.next()) add(JsonArray((1..metadata.columnCount).map { index -> rows.getString(index)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
            } }
        } }
    }
    private fun record(value: JsonObject) = value.getValue("current_record").jsonObject
    private fun fields(value: JsonObject) = value.getValue("fields").jsonObject
    private fun receipt(value: JsonObject) = value.getValue("receipt").jsonObject

    @Test fun originalSourceDerivationHonorsProtectedEntryAndPreservesBindings() {
        for (name in names("source")) {
            val f = Fixture(name); val bindings = f.snapshot().getValue("cash_source_bindings")
            val result = record(f.save()); val explicit = f.params["explicit"]?.takeIf { it != JsonNull }
            assertEquals(f.params.getValue("expected"), result.getValue("entry").jsonObject.getValue("fields").jsonObject["note"])
            assertEquals(JsonPrimitive("draft"), result["state"])
            assertEquals(explicit ?: JsonPrimitive("来源新原文"), fields(result)["note"])
            val row = f.parent(); val metadata = row[CASH_TRANSACTIONS.RELATION_METADATA]!!.jsonObject
            assertEquals(JsonPrimitive("different-explanation-only"), metadata["txn_id"]); assertEquals(JsonPrimitive("trusted-owner"), metadata["_source_account_identifier"])
            assertFalse("_refund_signal" in metadata)
            assertEquals(f.params.getValue("protected").jsonPrimitive.boolean || explicit != null,
                row[CASH_TRANSACTIONS.MANUAL_OVERRIDES]!!.jsonObject["note"] == JsonPrimitive(true))
            assertEquals(bindings, f.snapshot().getValue("cash_source_bindings"))
        }
    }
    @Test fun originalSamePayloadRepairsExplanationAndAdvancesRevision() {
        val f = Fixture("repair"); val before = f.detail(); val result = record(f.save())
        assertTrue(result.getValue("row_revision").jsonPrimitive.long > before.getValue("row_revision").jsonPrimitive.long)
        assertEquals(JsonPrimitive(""), result.getValue("entry").jsonObject.getValue("fields").jsonObject["note"])
        assertEquals(JsonPrimitive("来源原文"), fields(result)["note"])
        val metadata = f.parent()[CASH_TRANSACTIONS.RELATION_METADATA]!!.jsonObject
        assertEquals(JsonPrimitive("current-source-id"), metadata["txn_id"]); assertFalse("_refund_signal" in metadata)
    }
    @Test fun originalCompleteSaveStaysDraftKeepsBindingsAndNeverChangesBalances() {
        val f = Fixture("complete"); val before = f.detail(); val state = f.snapshot(); val saved = f.save(); val result = record(saved)
        assertEquals(JsonPrimitive(true), receipt(saved)["committed"]); assertEquals(JsonPrimitive("available"), saved["current_record_status"])
        assertEquals(JsonPrimitive(f.id), result["transaction_id"]); assertEquals(JsonPrimitive("draft"), result["state"]); assertEquals(JsonPrimitive("pending"), result["confirmation_status"])
        assertTrue(result.getValue("row_revision").jsonPrimitive.long > before.getValue("row_revision").jsonPrimitive.long)
        assertEquals(JsonPrimitive(""), fields(result)["note"]); assertEquals(JsonPrimitive("101"), fields(result)["account_id"])
        assertTrue(result.getValue("issues").jsonArray.none { it.jsonObject["code"] == JsonPrimitive("account_missing") })
        assertTrue(JsonPrimitive("amount") in result.getValue("editable_fields").jsonArray); assertFalse(JsonPrimitive("source_type") in result.getValue("editable_fields").jsonArray)
        assertEquals(buildJsonObject { put("note", true); put("category_id", true) }, f.parent()[CASH_TRANSACTIONS.MANUAL_OVERRIDES])
        assertEquals(state.getValue("ledger_snapshots"), f.snapshot().getValue("ledger_snapshots")); assertEquals(state.getValue("cash_source_bindings"), f.snapshot().getValue("cash_source_bindings"))
    }
    @Test fun originalIncompleteSavePreservesUnknownValuesAndReasons() {
        val f = Fixture("unknown"); val result = record(f.save()); val values = fields(result)
        assertEquals(JsonPrimitive("draft"), result["state"])
        for (key in listOf("amount", "currency", "occurred_at")) assertEquals(JsonNull, values[key])
        assertEquals(JsonArray(emptyList()), values["components"]); assertTrue(result.getValue("issues").jsonArray.isNotEmpty())
    }
    @Test fun originalReplayReturnsSameReceiptAndFreshCurrentDetailWithoutRewrite() {
        val f = Fixture("replay"); val first = f.save()
        f.write { it.sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.NOTE, "后来的当前备注").where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(f.workspace)).and(CASH_TRANSACTIONS.ID.eq(f.id.toLong())).execute() }
        val current = f.detail(); val before = f.snapshot(); val repeated = f.save()
        assertEquals(receipt(first), receipt(repeated)); assertEquals(current, record(repeated)); assertEquals(JsonPrimitive("后来的当前备注"), fields(current)["note"])
        assertFalse(receipt(repeated).toString().contains("仅属当前流水的人工正文")); assertEquals(before, f.snapshot())
        val stored = f.read { it.sql.select(CASH_MUTATION_RECEIPTS.RESULT_SUMMARY).from(CASH_MUTATION_RECEIPTS)
            .where(CASH_MUTATION_RECEIPTS.ID.eq(receipt(first).getValue("receipt_id").jsonPrimitive.content)).fetchSingle().value1() }
        assertFalse(stored.toString().contains("仅属当前流水的人工正文"))
    }
    @Test fun originalInvalidPatchesRejectAtomically() {
        for (name in names("invalid")) { val f = Fixture(name); val before = f.snapshot(); val detail = f.detail()
            assertEquals(f.params.getValue("code").jsonPrimitive.content, assertFailsWith<CashDraftSaveFailure> { f.save() }.code)
            assertEquals(detail, f.detail()); assertEquals(before, f.snapshot())
        }
    }
    @Test fun originalStaleAndViewerCannotChangeDraft() {
        val f = Fixture("stale"); val before = f.detail(); val snapshot = f.snapshot()
        assertEquals("record_changed", assertFailsWith<ReceiptConflict> { f.save() }.code)
        f.write { it.sql.update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "viewer").where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(f.workspace)).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq(f.user)).execute() }
        assertTrue(assertFailsWith<AccessFailure> { f.service().save(f.token, f.id, before["row_revision"], mapOf("note" to "不应保存"), "synthetic-viewer-draft") }.forbidden)
        val expected = JsonObject(before + mapOf("editable_fields" to JsonArray(emptyList()), "entry" to JsonObject(before.getValue("entry").jsonObject + ("editable_fields" to JsonArray(emptyList())))))
        assertEquals(expected, f.detail()); assertEquals(snapshot.filterKeys { it != "workspace_memberships" }, f.snapshot().filterKeys { it != "workspace_memberships" })
    }
    @Test fun originalCommittedSaveSurvivesCurrentDetailReadFailure() {
        val f = Fixture("outcome"); val result = f.save(f.service(outcome = { error("synthetic private storage error") }))
        assertEquals(JsonPrimitive(true), receipt(result)["committed"]); assertEquals(JsonPrimitive("unavailable"), result["current_record_status"]); assertEquals(JsonNull, result["current_record"])
        assertFalse(result.toString().contains("private storage error")); assertEquals("已保存的人工备注", f.parent()[CASH_TRANSACTIONS.NOTE])
        val before = f.snapshot(); val repeated = f.save(); assertEquals(receipt(result), receipt(repeated)); assertEquals(JsonPrimitive("available"), repeated["current_record_status"]); assertEquals(before, f.snapshot())
    }
    @Test fun originalExternalIssuesCandidatesAndEvidenceRemainUnchanged() {
        val f = Fixture("issues"); val before = f.snapshot()
        val retained = f.read { it.sql.selectFrom(CASH_TRANSACTION_ISSUES).where(CASH_TRANSACTION_ISSUES.WORKSPACE_ID.eq(f.workspace)).and(CASH_TRANSACTION_ISSUES.TRANSACTION_ID.eq(f.id.toLong()))
            .and(CASH_TRANSACTION_ISSUES.CODE.`in`("source_update_pending", "source_identity_ambiguous", "relation_discovery_failed")).orderBy(CASH_TRANSACTION_ISSUES.ID).fetch().map { it.intoMap() } }
        val evidence = f.parent()[CASH_TRANSACTIONS.RELATION_METADATA]; val saved = record(f.save())
        assertTrue(retained.map { it.getValue("id").toString() }.toSet().all { id -> saved.getValue("issues").jsonArray.any { it.jsonObject["issue_id"] == JsonPrimitive(id) } })
        val after = f.read { it.sql.selectFrom(CASH_TRANSACTION_ISSUES).where(CASH_TRANSACTION_ISSUES.WORKSPACE_ID.eq(f.workspace)).and(CASH_TRANSACTION_ISSUES.ID.`in`(retained.map { (it.getValue("id") as Number).toLong() }))
            .orderBy(CASH_TRANSACTION_ISSUES.ID).fetch().map { it.intoMap() } }
        assertEquals(retained, after); assertEquals(evidence, f.parent()[CASH_TRANSACTIONS.RELATION_METADATA]); assertEquals(before.getValue("cash_issue_candidates"), f.snapshot().getValue("cash_issue_candidates"))
    }
    @Test fun originalReplacingPartsKeepsOwnedIdentityAndAllocatesOnlyNewParts() {
        val f = Fixture("parts"); val original = fields(f.detail()).getValue("components").jsonArray.single().jsonObject.getValue("component_id")
        val result = record(f.save()); val parts = fields(result).getValue("components").jsonArray
        assertEquals(listOf(0, 1), parts.map { it.jsonObject.getValue("ordinal").jsonPrimitive.int })
        assertEquals(original, parts[1].jsonObject["component_id"]); assertNotEquals(original, parts[0].jsonObject["component_id"]); assertEquals(JsonPrimitive("draft"), result["state"])
        assertEquals(f.id.toLong(), f.read { it.sql.select(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID).from(CASH_TRANSACTION_COMPONENTS).where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(f.workspace)).and(CASH_TRANSACTION_COMPONENTS.ID.eq(original.jsonPrimitive.long)).fetchSingle().value1() })
    }
    @Test fun originalProjectionFailureRollsBackDraftAndReceipt() {
        val f = Fixture("projection_failure"); val before = f.snapshot(); var reached = 0
        val failure = assertFailsWith<CashImportCommitFailure> { f.save(f.service(step = { if (it == "record_write") { reached++; error("synthetic projection publishing failure") } })) }
        assertEquals("not_committed", failure.commitState); assertEquals(1, reached); assertEquals(before, f.snapshot())
    }
    @Test fun originalRemovedPartsInvalidateAllCandidateStatesAndOldContext() {
        for (name in names("detach")) {
            val f = Fixture(name); val before = f.read { CashRelationContextService(it).load(setOf(1001L)) }; assertFalse(f.id.toLong() in before.transactionIds)
            val relation = f.read { it.sql.selectFrom(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(f.workspace)).fetchSingle() }
            f.save(); val after = f.read { it.sql.selectFrom(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(f.workspace)).fetchSingle() }
            assertEquals(f.id.toLong(), after[TRANSACTION_RELATIONS.SECONDARY_TRANSACTION_ID]); assertEquals(1001L, after[TRANSACTION_RELATIONS.ANCHOR_TRANSACTION_ID])
            assertEquals(null, after[TRANSACTION_RELATIONS.SECONDARY_COMPONENT_ID]); assertEquals(null, after[TRANSACTION_RELATIONS.SECONDARY_ROLE]); assertTrue(after[TRANSACTION_RELATIONS.ROW_REVISION]!! > relation[TRANSACTION_RELATIONS.ROW_REVISION]!!)
            val failure = assertFailsWith<RuntimeException> { f.read { CashRelationContextService(it).validate(before) } }; assertTrue(failure.message.orEmpty().contains("relation_context_changed"))
            if (f.params["candidate_validity"] != JsonNull) {
                val candidate = f.read { it.sql.selectFrom(CASH_RELATION_CANDIDATES).where(CASH_RELATION_CANDIDATES.WORKSPACE_ID.eq(f.workspace)).fetchSingle() }
                assertEquals(null, candidate[CASH_RELATION_CANDIDATES.CANDIDATE_COMPONENT_ID]); assertEquals("stale", candidate[CASH_RELATION_CANDIDATES.VALIDITY])
            }
        }
    }
    @Test fun originalInvalidJsonAndInputBudgetsFailBeforeAnyDatabaseAccess() {
        for (label in listOf("json", "budget")) for (name in names(label)) {
            val f = Fixture(name); val before = f.snapshot(); var opens = 0
            val db = f.database { opens++; it }; val maximum = if (label == "budget" && f.params["kind"] != JsonPrimitive("depth")) 256 else 64 * 1024 * 1024
            assertEquals("record_input_invalid", assertFailsWith<CashDraftSaveFailure> { f.save(f.service(db = db, budget = maximum)) }.code)
            assertEquals(0, opens); assertEquals(before, f.snapshot())
        }
    }
    @Test fun originalOverlongReferencesFailWithoutChanges() {
        for (name in names("reference")) { val f = Fixture(name); val before = f.snapshot()
            assertEquals(if (f.params["field"] == JsonPrimitive("transaction_id")) "record_not_found" else "record_input_invalid", assertFailsWith<CashDraftSaveFailure> { f.save() }.code)
            assertEquals(before, f.snapshot())
        }
    }
    @Test fun originalOrdinalBoundsMatchBothDatabases() {
        for (name in names("ordinal")) { val f = Fixture(name); val before = f.snapshot()
            if (f.params["ordinal"] == JsonPrimitive(Int.MAX_VALUE)) {
                val result = record(f.save()); assertEquals(Int.MAX_VALUE, fields(result).getValue("components").jsonArray.single().jsonObject.getValue("ordinal").jsonPrimitive.int); assertEquals(JsonPrimitive("draft"), result["state"])
            } else { assertEquals("invalid_field", assertFailsWith<CashDraftSaveFailure> { f.save() }.code); assertEquals(before, f.snapshot()) }
        }
    }
    @Test fun originalExplicitFinancialContradictionsRejectAtomically() {
        for (name in names("finance")) { val f = Fixture(name); val before = f.snapshot()
            assertTrue(assertFailsWith<CashDraftSaveFailure> { f.save() }.policy); assertEquals(before, f.snapshot())
        }
    }
    @Test fun sharedPlanPreparationRejectsComponentsOwnedByAnotherRecord() {
        val f = names("invalid").map(::Fixture).single { it.params["code"] == JsonPrimitive("component_reference_invalid") }
        val before = f.snapshot()
        val request = MutationPlanRequest.parse(buildJsonObject {
            put("intent", "save_draft"); put("transaction_id", f.id); put("expected_revision", f.input.getValue("expected_revision")); put("patch", f.input.getValue("patch"))
        })
        assertEquals("component_reference_invalid", assertFailsWith<CashPolicyError> { f.read { MutationPlanBuilder(it).build(f.id.toLong(), request) } }.code)
        assertEquals(before, f.snapshot())
    }
    @Test fun completeHttpContractMatchesOriginalAndProducesCurrentClientResponse() = testApplication {
        val f = Fixture("http"); val service = CashDraftSaveService(f.database, now = { at }, receiptId = { "11111111-1111-4111-8111-111111111111" })
        application { financeModule(f.database, cashDraftSave = service) }
        val cases = categoryDeleteBody(f.directory.resolve("http-cases.json").readText()).jsonArray; assertTrue(cases.size >= 40)
        for ((index, raw) in cases.withIndex()) {
            val case = raw.jsonObject; val before = f.snapshot()
            val response = client.patch(case.getValue("path").jsonPrimitive.content) {
                case.getValue("headers").jsonObject.forEach { (key, value) -> header(key, value.jsonPrimitive.content) }
                setBody(Base64.getDecoder().decode(case.getValue("raw_base64").jsonPrimitive.content))
            }
            val body = response.bodyAsText(); val label = "$index ${case["path"]} ${case["raw_base64"]}"
            assertEquals(case.getValue("status").jsonPrimitive.int, response.status.value, "$label $body")
            assertEquals(case.getValue("body"), Json.parseToJsonElement(body), label)
            if (!case.getValue("mutates").jsonPrimitive.boolean) assertEquals(before, f.snapshot(), label)
            if (response.status.value == 200) {
                val digest = f.read { it.sql.select(CASH_MUTATION_RECEIPTS.REQUEST_DIGEST).from(CASH_MUTATION_RECEIPTS)
                    .where(CASH_MUTATION_RECEIPTS.IDEMPOTENCY_KEY.eq(case.getValue("headers").jsonObject.getValue("Idempotency-Key").jsonPrimitive.content)).fetchSingle().value1() }
                assertEquals(case.getValue("request_digest").jsonPrimitive.content, digest)
            }
            if (response.status.value == 200) System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let { output ->
                val destination = Path.of(output); Files.createDirectories(destination)
                Files.writeString(destination.resolve("draft-save-response.json"), buildJsonObject {
                    put("backend", if (f.database.sqlite) "sqlite" else "postgresql"); put("path", case.getValue("path")); put("status", response.status.value)
                    put("request", Json.parseToJsonElement(Base64.getDecoder().decode(case.getValue("raw_base64").jsonPrimitive.content).decodeToString()))
                    put("body", Json.parseToJsonElement(body)); put("key", case.getValue("headers").jsonObject.getValue("Idempotency-Key"))
                }.toString())
            }
        }
    }
    private fun commitFailure(f: Fixture, responseLost: Boolean): Pair<Database, AtomicBoolean> {
        val fired = AtomicBoolean()
        val decorated = f.database { connection ->
            fun invoke(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? = try { method.invoke(target, *(args ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
            Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                if (method.name == "commit" && fired.compareAndSet(false, true)) {
                    if (responseLost) invoke(connection, method, args)
                    throw java.sql.SQLException("synthetic private commit failure")
                }
                val value = invoke(connection, method, args)
                if (method.name == "createStatement" && value is Statement) Proxy.newProxyInstance(Statement::class.java.classLoader, arrayOf(Statement::class.java)) { _, operation, values ->
                    if (operation.name == "execute" && (values?.firstOrNull() as? String)?.trim()?.equals("COMMIT", ignoreCase = true) == true && fired.compareAndSet(false, true)) {
                        if (responseLost) invoke(value, operation, values)
                        throw java.sql.SQLException("synthetic private commit failure")
                    }
                    invoke(value, operation, values)
                } else value
            } as Connection
        }
        return decorated to fired
    }
    @Test fun failuresAfterRecordProjectionAndReceiptRollBackAllTables() {
        for ((name, stage) in listOf("record_failure" to "record_write", "receipt_failure" to "receipt")) {
            val f = Fixture(name); val before = f.snapshot(); var reached = 0
            val failure = assertFailsWith<CashImportCommitFailure> { f.save(f.service(step = { if (it == stage) { reached++; error("synthetic private failure") } })) }
            assertEquals("not_committed", failure.commitState); assertEquals(1, reached); assertEquals(before, f.snapshot())
        }
    }
    @Test fun realCommitFailureAndLostResponseStayUnknownUntilOriginalKeyReplay() {
        for ((name, lost) in listOf("commit_failure" to false, "commit_response" to true)) {
            val f = Fixture(name); val before = f.snapshot(); val (db, fired) = commitFailure(f, lost)
            val failure = assertFailsWith<CashImportCommitFailure> { f.save(f.service(db = db)) }
            assertTrue(fired.get()); assertEquals("unknown", failure.commitState)
            val after = f.snapshot()
            if (!lost) assertEquals(before, after) else assertEquals(before.getValue("cash_mutation_receipts").size + 1, after.getValue("cash_mutation_receipts").size)
            val result = f.save(); assertEquals(JsonPrimitive(true), receipt(result)["committed"])
            if (lost) assertEquals(after, f.snapshot())
        }
    }
    @Test fun committedReceiptStaysKnownWhenRecordWasDeletedBeforeOutcomeRead() {
        val f = Fixture("deleted_outcome"); var reached = 0
        val saved = f.save(f.service(outcome = { reached++; CashDeletionService(f.database).deleteRecord(f.token, f.id) }))
        assertEquals(1, reached); assertEquals(JsonPrimitive(true), receipt(saved)["committed"]); assertEquals(JsonPrimitive("deleted"), saved["current_record_status"]); assertEquals(JsonNull, saved["current_record"])
        val before = f.snapshot(); val repeated = f.save(); assertEquals(receipt(saved), receipt(repeated)); assertEquals(JsonPrimitive("deleted"), repeated["current_record_status"]); assertEquals(before, f.snapshot())
    }
    @Test fun postgresRealWorkspaceLockWaitRechecksLatestWritePermission() {
        val f = Fixture("lock"); org.junit.Assume.assumeFalse(f.database.sqlite)
        val before = f.snapshot(); val ready = CountDownLatch(1); val workerPid = AtomicInteger(); val pool = Executors.newSingleThreadExecutor()
        var future: java.util.concurrent.Future<Throwable?>? = null
        try {
            f.database.write { holder ->
                assertTrue(holder.lockWorkspace(false, f.workspace))
                val holderPid = holder.createStatement().use { it.executeQuery("select pg_backend_pid()").use { rows -> rows.next(); rows.getInt(1) } }
                val service = CashDraftSaveService(f.database, beforeWorkspaceLock = { connection ->
                    workerPid.set(connection.createStatement().use { it.executeQuery("select pg_backend_pid()").use { rows -> rows.next(); rows.getInt(1) } }); ready.countDown()
                })
                future = pool.submit<Throwable?> { try { f.save(service); null } catch (failure: Throwable) { failure } }
                assertTrue(ready.await(5, TimeUnit.SECONDS)); val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); var blocked = false
                while (!blocked && System.nanoTime() < deadline) {
                    blocked = f.database.read { connection -> connection.prepareStatement("select ? = any(pg_blocking_pids(?))").use {
                        it.setInt(1, holderPid); it.setInt(2, workerPid.get()); it.executeQuery().use { rows -> rows.next(); rows.getBoolean(1) }
                    } }
                    if (!blocked) Thread.sleep(10)
                }
                assertTrue(blocked)
                holder.jooq(false).update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "viewer").where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(f.workspace)).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq(f.user)).execute()
            }
            assertTrue(assertIs<AccessFailure>(future!!.get(10, TimeUnit.SECONDS)).forbidden)
            assertEquals(before.filterKeys { it != "workspace_memberships" }, f.snapshot().filterKeys { it != "workspace_memberships" })
        } finally { pool.shutdownNow() }
    }
}
