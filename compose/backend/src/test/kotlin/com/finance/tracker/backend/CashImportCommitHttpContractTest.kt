package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportCommitHttpContractTest {
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_IMPORT_COMMIT_CONTRACT_DIR")))
    private fun database(decorate: (Connection) -> Connection = { it }) = Json.parseToJsonElement(root().resolve("database.json").readText()).jsonObject.let {
        Database(it.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"), decorate)
    }
    private val at = Instant.parse("2026-10-01T09:00:00Z")
    private fun cases() = Json.parseToJsonElement(root().resolve("cases.json").readText()).jsonArray.map { it.jsonObject }
    private class Store(at: Instant) : ParsedImportStore(clock = { at }) {
        private val values = linkedMapOf<String, ParsedImportObject>()
        var failClear = false
        fun seed(case: JsonObject) {
            val bytes = case.getValue("content").toString().encodeToByteArray()
            values[case.getValue("session_id").jsonPrimitive.content] = ParsedImportObject(bytes, digest(bytes))
        }
        fun seed(session: ParsedImportSession) {
            val bytes = codec.encode(session)
            values[session.sessionId] = ParsedImportObject(bytes, digest(bytes))
        }
        override fun readRaw(id: String) = values[id] ?: throw ParsedImportNotFound()
        override fun writeRaw(id: String, content: ByteArray, expected: String?, expiresAt: Instant) {
            if (values[id]?.etag != expected) throw ParsedImportRevisionConflict()
            if (failClear && Json.parseToJsonElement(content.decodeToString()).jsonObject["cancelled"] == JsonPrimitive(true)) throw ParsedImportStorageUnavailable()
            values[id] = ParsedImportObject(content.copyOf(), digest(content))
        }
        override fun deleteRaw(id: String) { values.remove(id) }
        override fun sweepExpired(cursor: String?, limit: Int, shouldStop: () -> Boolean) = ParsedImportSweep(0, 0, null)
    }
    private fun wire(value: Any?): JsonElement = when (value) {
        is JsonElement -> value
        is CashStoredDecimal -> JsonPrimitive(fixedCashDecimal(value))
        is Instant -> JsonPrimitive(ParsedImportCodec.timeText(value))
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to wire(it.value) })
        is Iterable<*> -> JsonArray(value.map(::wire))
        else -> sourceJson(value)
    }
    private fun state(db: Database): JsonElement = db.read { connection ->
        val context = CashSqlContext(connection, "operation-workspace", db.sqlite)
        fun rows(table: org.jooq.Table<*>, fields: String, order: String) = context.sql.selectFrom(table)
            .where(table.field("workspace_id", String::class.java)!!.eq(context.workspace)).orderBy(order.split(',').map { table.field(it)!! }).fetch()
            .map { record -> fields.split(',').associateWith { record.get(it) } }
        val facts = mapOf(
            "parents" to rows(CASH_TRANSACTIONS, "id,state,record_origin,source_type,record_id,source_payload,relation_metadata,manual_overrides,row_revision,account_id,amount,currency,occurred_at,cash_granularity,counterparty,note,category_id,counterparty_account,counterparty_account_attrs,record_type,record_subtype", "id"),
            "components" to rows(CASH_TRANSACTION_COMPONENTS, "id,cash_transaction_id,account_id,amount,currency,ordinal,label,source_key,metadata_json", "id"),
            "bindings" to rows(CASH_SOURCE_BINDINGS, "transaction_id,source_type,source_scope_kind,source_scope_key,identity_kind,identity_key,identity_strength,stable_evidence_key,coverage,revision", "transaction_id,source_scope_kind,source_scope_key,identity_kind,identity_key"),
            "issues" to rows(CASH_TRANSACTION_ISSUES, "id,transaction_id,issue_key,code,field_path,pending_fields,pending_source_payload,origin,revision", "id"),
            "issue_candidates" to rows(CASH_ISSUE_CANDIDATES, "issue_id,candidate_transaction_id,expected_row_revision", "issue_id,candidate_transaction_id"),
            "relations" to rows(TRANSACTION_RELATIONS, "id,state,active,row_revision,primary_transaction_id,secondary_transaction_id,anchor_transaction_id,primary_component_id,secondary_component_id,anchor_component_id,primary_role,secondary_role,decision_source,decided_by,decision_reason", "id"),
            "relation_candidates" to rows(CASH_RELATION_CANDIDATES, "relation_id,candidate_transaction_id,candidate_component_id,expected_row_revision,validity", "relation_id,candidate_transaction_id"),
            "funding" to rows(CASH_INVESTMENT_FUNDING_RELATIONS, "id,status,active_slot,decided_by,decision_reason", "id"),
            "entries" to rows(CASH_ENTRIES, "root_transaction_id,revision,category_id,note,counterparty,counterparty_account,counterparty_account_attrs,manual_fields,split_inherit", "root_transaction_id"))
        val member = CASH_ENTRY_MEMBERS
        val entry = CASH_ENTRIES
        val members = context.sql.select(member.TRANSACTION_ID, entry.ROOT_TRANSACTION_ID, member.STANDALONE_VALUES, member.STANDALONE_MANUAL_FIELDS).from(member)
            .join(entry).on(entry.WORKSPACE_ID.eq(member.WORKSPACE_ID).and(entry.ID.eq(member.ENTRY_ID))).where(member.WORKSPACE_ID.eq(context.workspace)).orderBy(member.TRANSACTION_ID)
            .fetch().map { mapOf("transaction_id" to it.value1(), "entry_root" to it.value2(), "standalone_values" to it.value3(), "standalone_manual_fields" to it.value4()) }
        val snapshot = context.sql.selectFrom(LEDGER_SNAPSHOTS).where(LEDGER_SNAPSHOTS.WORKSPACE_ID.eq(context.workspace)).fetchOne()
        val receipt = CASH_MUTATION_RECEIPTS
        val result = CASH_MUTATION_RESULTS
        val results = context.sql.select(receipt.IDEMPOTENCY_KEY, result.RESULT_KIND, result.ITEM_REF, result.OUTCOME, result.CODE, result.OPERATION_REF, result.TRANSACTION_ID, result.RELATION_ID, result.ACCOUNT_ID)
            .from(result).join(receipt).on(receipt.ID.eq(result.RECEIPT_ID)).where(result.WORKSPACE_ID.eq(context.workspace)).orderBy(receipt.IDEMPOTENCY_KEY, result.ID)
            .fetch().map { record -> mapOf("key" to record[receipt.IDEMPOTENCY_KEY]) + listOf("result_kind", "item_ref", "outcome", "code", "operation_ref", "transaction_id", "relation_id", "account_id").associateWith { record.get(it) } }
        wire(mapOf("rows" to facts, "pages" to rows(CASH_SOURCE_EMPTY_PAGES, "source_type,parser_version,document_key,page_number,coverage", "source_type,parser_version,document_key,page_number"),
            "entry_members" to members, "snapshot" to snapshot?.let { mapOf("payload" to it[LEDGER_SNAPSHOTS.PAYLOAD], "version" to it[LEDGER_SNAPSHOTS.VERSION]) },
            "mappings" to rows(STATEMENT_ACCOUNT_MAPPINGS, "source_type,identity_kind,source_account_key,account_id,confirmed_by,revision", "source_type,identity_kind,source_account_key"),
            "projections" to rows(CASH_PROJECTIONS, "root_cash_transaction_id,state,statistics_eligible,attention_count,economic_type,transfer_subtype,net_amount,currency,occurred_at,account_id,counterparty,category_id,category_path,note,source_type,record_id,visible,hidden_reason,has_payment_mirror,has_refund_offset,has_transfer_pair,member_count,accepted_relation_count,built_projection_version", "root_cash_transaction_id"),
            "projection_version" to context.sql.select(CASH_PROJECTION_STATES.PROJECTION_VERSION).from(CASH_PROJECTION_STATES).where(CASH_PROJECTION_STATES.WORKSPACE_ID.eq(context.workspace)).fetchSingle().value1(),
            "receipts" to rows(receipt, "idempotency_key,request_digest,operation_kind,plan_id,session_digest,result_summary", "idempotency_key"), "results" to results))
    }
    private fun difference(expected: JsonElement, actual: JsonElement, path: String = ""): String? {
        if (expected == actual) return null
        if (expected is JsonObject && actual is JsonObject) {
            if (expected.keys != actual.keys) return "$path keys expected=${expected.keys} actual=${actual.keys}"
            for (key in expected.keys) difference(expected.getValue(key), actual.getValue(key), "$path/$key")?.let { return it }
        } else if (expected is JsonArray && actual is JsonArray) {
            if (expected.size != actual.size) return "$path size expected=${expected.size} actual=${actual.size}"
            for (index in expected.indices) difference(expected[index], actual[index], "$path/$index")?.let { return it }
        }
        return "$path expected=${expected.toString().take(150)} actual=${actual.toString().take(150)}"
    }
    private fun equivalent(expected: JsonElement, actual: JsonElement, name: String) { difference(expected, actual)?.let { fail("$name: $it") } }
    private fun failingCommit(raw: Connection, mode: () -> String?, armed: AtomicBoolean): Connection {
        fun invoke(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? = try { method.invoke(target, *(args ?: emptyArray())) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        fun commit(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? {
            val fault = mode()
            if (fault !in setOf("commit_before", "commit_after") || !armed.compareAndSet(true, false)) return invoke(target, method, args)
            if (fault == "commit_after") invoke(target, method, args)
            throw SQLException("synthetic private driver failure")
        }
        return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "commit") commit(raw, method, args) else {
                val value = invoke(raw, method, args)
                if (method.name == "createStatement" && value is Statement) Proxy.newProxyInstance(Statement::class.java.classLoader, arrayOf(Statement::class.java)) { _, statementMethod, statementArgs ->
                    if (statementMethod.name == "execute" && statementArgs?.firstOrNull() == "COMMIT") commit(value, statementMethod, statementArgs)
                    else invoke(value, statementMethod, statementArgs)
                } else value
            }
        } as Connection
    }

    @Test fun actualHttpConfirmationsAndDatabaseStateMatchCompletePythonCommands() = testApplication {
        var mode: String? = null
        val armed = AtomicBoolean(false)
        val db = database { failingCommit(it, { mode }, armed) }
        val store = Store(at)
        var nextId = Json.parseToJsonElement(root().resolve("entry-id-seed.json").readText()).jsonObject.getValue("next").jsonPrimitive.long
        val commands = CashImportCommandService(db, store, now = { at }, afterStep = { step ->
            if (mode == "fault_$step") throw IllegalStateException("synthetic private source payload")
        }, entryIdFactory = { UUID(0L, nextId++).toString() })
        application { financeModule(db, cashImports = CashImportHttpService(db, store, commands)) }
        val captures = mutableListOf<JsonElement>()
        suspend fun commit(case: JsonObject, key: String = case.getValue("key").jsonPrimitive.content): HttpResponse = client.post("/api/v1/cash-import/plans/${case.getValue("plan_id").jsonPrimitive.content}/commit") {
            header(HttpHeaders.Authorization, "Bearer synthetic-owner"); header("Idempotency-Key", key)
            contentType(ContentType.Application.Json); setBody(buildJsonObject { put("expected_session_revision", case.getValue("revision")) }.toString())
        }
        for (case in cases()) {
            val name = case.getValue("name").jsonPrimitive.content
            assertEquals(case.getValue("entry_id_next").jsonPrimitive.long, nextId, "$name next entry identity")
            store.seed(case)
            store.failClear = name == "cleanup_failure"
            if (case["mutate_account"] == JsonPrimitive(true)) db.write { c -> c.jooq(db.sqlite).update(ACCOUNTS).set(ACCOUNTS.REVISION, ACCOUNTS.REVISION.plus(1L)).where(ACCOUNTS.ID.eq(9201L)).execute() }
            if (case["disable_account"] == JsonPrimitive(true)) db.write { c -> c.jooq(db.sqlite).update(ACCOUNTS).set(ACCOUNTS.ACTIVE, false).where(ACCOUNTS.ID.eq(9201L)).execute() }
            val before = detailDatabaseState(db)
            mode = name
            armed.set(name in setOf("commit_before", "commit_after"))
            val response = commit(case)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val expected = case.getValue("expected").jsonObject
            val code = body["error"]?.jsonObject?.get("code") ?: JsonNull
            equivalent(expected.getValue("error"), code, "$name error")
            assertEquals(if (code == JsonNull) 200 else if (code == JsonPrimitive("plan_changed")) 409 else 503, response.status.value, "$name $body")
            equivalent(expected.getValue("commit_state"), body["commit_state"] ?: JsonNull, "$name state proof")
            val receipt = if (code == JsonNull) JsonObject(body + mapOf("receipt_id" to JsonPrimitive("<receipt>"), "created_at" to JsonPrimitive("<time>"))) else JsonNull
            equivalent(expected.getValue("receipt"), receipt, "$name receipt")
            equivalent(expected.getValue("state"), state(db), "$name database")
            assertEquals(expected.getValue("entry_id_next").jsonPrimitive.long, nextId, "$name identity allocations")
            val stored = store.get(case.getValue("session_id").jsonPrimitive.content, "operation-workspace", "owner")
            assertEquals(expected.getValue("cleared").jsonPrimitive.boolean, stored.cancelled && stored.files.isEmpty(), name)
            if (expected["commit_state"] == JsonPrimitive("not_committed") || name == "commit_before") assertEquals(before, detailDatabaseState(db), "$name whole transaction rollback")
            if (code != JsonNull && body["error"]!!.jsonObject["diagnostic"] != null) {
                val diagnostic = body.getValue("error").jsonObject.getValue("diagnostic").jsonObject
                equivalent(expected.getValue("diagnostic"), JsonObject(diagnostic.filterKeys { it != "error_id" }), "$name diagnostic")
                assertFalse(body.toString().contains("synthetic private")); assertFalse(body.toString().contains("INSERT"))
                assertEquals(response.status.value == 503, body.getValue("error").jsonObject.getValue("retryable").jsonPrimitive.boolean)
            }
            captures += buildJsonObject { put("plan_id", case.getValue("plan_id")); put("revision", case.getValue("revision")); put("key", case.getValue("key")); put("status", response.status.value); put("body", body) }
            mode = null
            if (case["disable_account"] == JsonPrimitive(true)) db.write { c -> c.jooq(db.sqlite).update(ACCOUNTS).set(ACCOUNTS.ACTIVE, true).where(ACCOUNTS.ID.eq(9201L)).execute() }
            if (name in setOf("normal", "cleanup_failure", "commit_after")) {
                val replay = commit(case)
                assertEquals(200, replay.status.value, replay.bodyAsText())
                val replayBody = Json.parseToJsonElement(replay.bodyAsText()).jsonObject
                val resolved = client.post("/api/v1/cash-mutations/resolve") {
                    header(HttpHeaders.Authorization, "Bearer synthetic-owner"); contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("plan_id", case.getValue("plan_id")); put("idempotency_key", case.getValue("key")) }.toString())
                }
                val resolvedBody = Json.parseToJsonElement(resolved.bodyAsText()).jsonObject
                assertEquals(200, resolved.status.value, resolvedBody.toString())
                assertEquals(JsonPrimitive("committed"), resolvedBody["status"])
                assertEquals(replayBody, resolvedBody["receipt"])
                val other = commit(case, case.getValue("key").jsonPrimitive.content + "-other")
                assertEquals(409, other.status.value)
                assertEquals(JsonPrimitive("session_committed"), Json.parseToJsonElement(other.bodyAsText()).jsonObject.getValue("error").jsonObject["code"])
                if (name == "normal") {
                    val id = replayBody.getValue("receipt_id").jsonPrimitive.content
                    val page = client.get("/api/v1/cash-mutations/receipts/$id/rows?limit=200") { header(HttpHeaders.Authorization, "Bearer synthetic-owner") }
                    assertEquals(200, page.status.value, page.bodyAsText())
                    val expectedRows = expected.getValue("state").jsonObject.getValue("results").jsonArray.count {
                        it.jsonObject["key"] == case.getValue("key") && it.jsonObject["result_kind"] == JsonPrimitive("row")
                    }
                    assertEquals(expectedRows, Json.parseToJsonElement(page.bodyAsText()).jsonObject.getValue("items").jsonArray.size)
                    captures += buildJsonObject { put("resolve", resolvedBody); put("row_page", Json.parseToJsonElement(page.bodyAsText())); put("receipt_id", id) }
                }
            }
        }
        System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let { path ->
            val directory = Path.of(path); require(directory.isAbsolute); Files.createDirectories(directory)
            Files.writeString(directory.resolve("cash-import-commit-responses.json"), buildJsonObject { put("backend", if (db.sqlite) "sqlite" else "postgresql"); put("responses", JsonArray(captures)) }.toString())
        }
    }

    @Test fun commitRejectsViewerBeforeReadingOrWritingAnyPlan() {
        val db = database()
        val before = detailDatabaseState(db)
        assertFailsWith<AccessFailure> {
            CashImportCommandService(db, InMemoryParsedImportStore()).commit("synthetic-viewer", "ip_" + "a".repeat(43) + "." + "b".repeat(16), 2L, "viewer-attempt")
        }
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun httpInputsAreStrictAndMissingKeyPrecedesBodyValidation() = testApplication {
        val db = database()
        application { financeModule(db, cashImports = CashImportHttpService(db, Store(at))) }
        val before = detailDatabaseState(db)
        val id = "ip_" + "s".repeat(43) + "." + "n".repeat(16)
        suspend fun post(body: String, key: String? = "strict-input", token: String = "synthetic-owner") = client.post("/api/v1/cash-import/plans/$id/commit") {
            header(HttpHeaders.Authorization, "Bearer $token"); key?.let { header("Idempotency-Key", it) }
            contentType(ContentType.Application.Json); setBody(body)
        }
        fun error(body: String) = Json.parseToJsonElement(body).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content
        assertEquals("idempotency_key_required", error(post("not json", null).bodyAsText()))
        for (body in listOf("{}", "[]", "{\"expected_session_revision\":true}", "{\"expected_session_revision\":\"2\"}", "{\"expected_session_revision\":2.0}",
            "{\"expected_session_revision\":0}", "{\"expected_session_revision\":2,\"extra\":1}", "{\"expected_session_revision\":2,\"expected_session_revision\":3}")) {
            val response = post(body)
            assertEquals(422, response.status.value, body)
            assertEquals("import_payload_invalid", error(response.bodyAsText()), body)
        }
        assertEquals(403, post("{\"expected_session_revision\":2}", token = "synthetic-viewer").status.value)
        val expired = post("{\"expected_session_revision\":2}")
        assertEquals(410, expired.status.value)
        assertEquals(JsonPrimitive("not_committed"), Json.parseToJsonElement(expired.bodyAsText()).jsonObject["commit_state"])
        assertEquals(410, post(" ".repeat(70_000) + "{\"expected_session_revision\":2}").status.value)
        val paddedResolve = client.post("/api/v1/cash-mutations/resolve") {
            header(HttpHeaders.Authorization, "Bearer synthetic-owner"); contentType(ContentType.Application.Json)
            setBody(" ".repeat(70_000) + "{\"plan_id\":\"$id\",\"idempotency_key\":\"key\"}")
        }
        assertEquals(200, paddedResolve.status.value)
        assertEquals(JsonPrimitive("not_committed"), Json.parseToJsonElement(paddedResolve.bodyAsText()).jsonObject["status"])
        val invalidResolve = client.post("/api/v1/cash-mutations/resolve") {
            header(HttpHeaders.Authorization, "Bearer synthetic-owner"); contentType(ContentType.Application.Json)
            setBody("{\"plan_id\":\"$id\",\"idempotency_key\":\"key\",\"extra\":1}")
        }
        assertEquals(422, invalidResolve.status.value)
        assertEquals("import_payload_invalid", error(invalidResolve.bodyAsText()))
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun resolveDoesNotWaitForIndependentWriterAndCommitRechecksPermissionAfterLock() {
        val db = database()
        val before = detailDatabaseState(db)
        val source = cases().first()
        val original = ParsedImportCodec().decode(source.getValue("session_id").jsonPrimitive.content, source.getValue("content").toString().encodeToByteArray())
        val id = "l".repeat(43)
        val planId = "ip_$id." + "m".repeat(16)
        val store = Store(at)
        store.seed(original.copy(sessionId = id, plan = original.plan!! + mapOf("plan_id" to planId, "session_id" to id)))
        val commands = CashImportCommandService(db, store, now = { at })
        val executor = Executors.newSingleThreadExecutor()
        try {
            db.write { connection ->
                assertTrue(connection.lockWorkspace(db.sqlite, "operation-workspace"))
                val result = executor.submit<JsonObject> { commands.resolve("synthetic-owner", planId, "locked-resolution") }.get(3, TimeUnit.SECONDS)
                assertEquals(JsonPrimitive("pending"), result["status"])
            }
            assertEquals(JsonPrimitive("retry_original"), commands.resolve("synthetic-owner", planId, "locked-resolution")["status"])
            val observed = CountDownLatch(1)
            val waiting = CashImportCommandService(db, store, now = { at }, beforeWrite = { observed.countDown() })
            lateinit var worker: java.util.concurrent.Future<Unit>
            db.write { connection ->
                assertTrue(connection.lockWorkspace(db.sqlite, "operation-workspace"))
                worker = executor.submit<Unit> { assertFailsWith<AccessFailure> { waiting.commit("synthetic-owner", planId, original.revision.longValueExact(), "permission-after-lock") }; Unit }
                assertTrue(observed.await(3, TimeUnit.SECONDS))
                connection.jooq(db.sqlite).update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "viewer")
                    .where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq("operation-workspace").and(WORKSPACE_MEMBERSHIPS.USER_ID.eq("owner"))).execute()
            }
            worker.get(8, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
            db.write { c -> c.jooq(db.sqlite).update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "admin")
                .where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq("operation-workspace").and(WORKSPACE_MEMBERSHIPS.USER_ID.eq("owner"))).execute() }
        }
        assertEquals(before, detailDatabaseState(db))
    }
}
