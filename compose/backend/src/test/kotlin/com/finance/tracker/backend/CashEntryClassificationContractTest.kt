package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.util.UUID
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.sql.Connection
import java.sql.Statement
import java.sql.PreparedStatement
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationTargetException
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CashEntryClassificationContractTest(private val backend: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun backends(): List<Array<String>> = listOf(arrayOf("sqlite"), arrayOf("postgres"))
    }
    private fun sequence(db: Database, ws: String) = db.read { c -> c.jooq(db.sqlite).select(WORKSPACES.LEDGER_SEQ)
        .from(WORKSPACES).where(WORKSPACES.ID.eq(ws)).fetchSingle(WORKSPACES.LEDGER_SEQ)!! }
    private fun facts(db: Database, ws: String) = db.read { LedgerSnapshotRepository(it, ws, db.sqlite).facts() }
    private fun authenticated(decorate: (Connection) -> Connection = { it }, action: (Database, String, Long, String, String) -> Unit) = withCashWriteSetTestWorkspace(backend, decorate) { db, ws, account ->
        val user = UUID.randomUUID().toString(); val token = "entry-classification-$user"
        db.write { c ->
            val sql = c.jooq(db.sqlite)
            sql.insertInto(USERS).set(USERS.ID, user).set(USERS.EMAIL, "$user@example.invalid")
                .set(USERS.PASSWORD_HASH, "unused").set(USERS.CREATED_AT, deletionTime).execute()
            sql.insertInto(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID, ws).set(WORKSPACE_MEMBERSHIPS.USER_ID, user)
                .set(WORKSPACE_MEMBERSHIPS.ROLE, "admin").set(WORKSPACE_MEMBERSHIPS.CREATED_AT, deletionTime).execute()
            sql.insertInto(USER_SESSIONS).set(USER_SESSIONS.ID, UUID.randomUUID().toString()).set(USER_SESSIONS.USER_ID, user)
                .set(USER_SESSIONS.TOKEN_DIGEST, tokenDigest(token)).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, ws)
                .set(USER_SESSIONS.CREATED_AT, deletionTime).set(USER_SESSIONS.EXPIRES_AT, java.time.Instant.parse("2099-01-01T00:00:00Z")).execute()
        }
        try { action(db, ws, account, token, user) } finally { db.write { c ->
            val sql = c.jooq(db.sqlite)
            sql.deleteFrom(USER_SESSIONS).where(USER_SESSIONS.USER_ID.eq(user)).execute()
            sql.deleteFrom(WORKSPACE_MEMBERSHIPS).where(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute()
            sql.deleteFrom(USERS).where(USERS.ID.eq(user)).execute()
        } }
    }
    private data class Fixture(val group: String, val single: String, val parents: List<Long>, val source: String, val target: String)
    private fun fixture(db: Database, ws: String, account: Long, token: String): Fixture {
        val source = CategoryService(db).create(token, "原分类").id
        val target = CategoryService(db).create(token, "目标分类").id
        return db.write { c ->
            val ids = List(3) { deletionFact(c, db, ws, account, source) }
            val group = "group-${UUID.randomUUID()}"; val single = "single-${UUID.randomUUID()}"
            deletionEntry(c, db, ws, group, ids[0], source)
            deletionEntry(c, db, ws, single, ids[2], source)
            for ((index, id) in ids.withIndex()) deletionInsert(c, db, "cash_entry_members", linkedMapOf(
                "workspace_id" to ws, "transaction_id" to id, "entry_id" to if (index < 2) group else single,
                "standalone_values" to buildJsonObject { put("category_id", source); put("note", "来源独立备注") },
                "standalone_manual_fields" to JsonArray(listOf(JsonPrimitive("note")))))
            c.markLedgerChanged(ws)
            Fixture(group, single, ids, source, target)
        }
    }
    @Test fun canonicalEntriesClassifyWithoutAnyMaterializedDatasetAndKeepSourceFacts() = authenticated { db, ws, account, token, _ ->
        val data = fixture(db, ws, account, token); val before = facts(db, ws); val base = sequence(db, ws)
        val result = CashClassificationService(db).setEntryCategory(token, listOf(data.group, data.single, data.group), base, data.target, ws)
        assertEquals(base + 1, sequence(db, ws)); assertEquals(JsonPrimitive(base + 1), result["projection_version"])
        assertEquals(JsonPrimitive(2), result["projection_count"]); assertEquals(JsonPrimitive(3), result["updated_transaction_count"])
        val after = facts(db, ws)
        assertEquals(before.cashTransactions, after.cashTransactions)
        assertEquals(before.cashTransactionComponents, after.cashTransactionComponents)
        assertTrue(after.cashEntries.all { it.categoryId == data.target && it.revision == "2" && JsonPrimitive("category_id") in it.manualFields.jsonArray })
        for (member in after.cashEntryMembers) {
            val single = member.transactionId == data.parents[2].toString()
            assertEquals(JsonPrimitive(if (single) data.target else data.source), member.standaloneValues.jsonObject["category_id"])
            assertEquals(JsonPrimitive("来源独立备注"), member.standaloneValues.jsonObject["note"])
            assertEquals(single, JsonPrimitive("category_id") in member.standaloneManualFields.jsonArray)
        }
        assertEquals(result, CashClassificationService(db).setEntryCategory(token, listOf(data.group, data.single), base + 1, data.target, ws))
        assertEquals(after, facts(db, ws)); assertEquals(base + 1, sequence(db, ws))
    }
    @Test fun exactLargeLedgerBaseIsIndependentOfAnyOtherWorkspaceState() = authenticated { db, ws, account, token, _ ->
        val data = fixture(db, ws, account, token); val base = 9007199254740993L
        db.write { c -> c.jooq(db.sqlite).update(WORKSPACES).set(WORKSPACES.LEDGER_SEQ, base).where(WORKSPACES.ID.eq(ws)).execute() }
        val result = CashClassificationService(db).setEntryCategory(token, listOf(data.group), base, null, ws)
        assertEquals(JsonPrimitive(base + 1), result["projection_version"])
        assertNull(facts(db, ws).cashEntries.single { it.id == data.group }.categoryId)
        assertEquals(base + 1, sequence(db, ws))
    }
    private class SqlObservation {
        var active = false
        var failAfterMembers = false
        var failedAfterMembers = false
        val statements = mutableListOf<String>()
        private fun call(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? =
            try { method.invoke(target, *(args ?: emptyArray())) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        fun wrap(connection: Connection): Connection = Proxy.newProxyInstance(Connection::class.java.classLoader,
            arrayOf(Connection::class.java)) { _, method, args ->
            val value = call(connection, method, args)
            if (value !is Statement) value else {
                val prepared = args?.firstOrNull() as? String
                Proxy.newProxyInstance(Statement::class.java.classLoader,
                    arrayOf(if (value is PreparedStatement) PreparedStatement::class.java else Statement::class.java)) { _, operation, parameters ->
                    val executing = operation.name in setOf("execute", "executeUpdate", "executeQuery", "executeBatch", "executeLargeBatch")
                    val text = prepared ?: parameters?.firstOrNull() as? String
                    if (active && executing && text != null && text !in setOf("COMMIT", "ROLLBACK")) {
                        check("cash_projection" !in text.lowercase()) { "Canonical classification touched a materialized table: $text" }
                        statements += text
                    }
                    val result = call(value, operation, parameters)
                    if (active && failAfterMembers && executing && text?.lowercase()?.let {
                            "update" in it && "cash_entry_members" in it } == true) {
                        failedAfterMembers = true
                        throw java.sql.SQLException("synthetic failure after member batch", "XX000")
                    }
                    result
                }
            }
        } as Connection
    }

    @Test fun sameValueAndClearUseTheExistingPublicEditRulesAndRepeatWithoutNewEffects() = authenticated { db, ws, account, token, _ ->
        val data = fixture(db, ws, account, token); val before = facts(db, ws); val base = sequence(db, ws)
        val service = CashClassificationService(db)
        val first = service.setEntryCategory(token, listOf(data.group, data.single), base, data.source, ws)
        assertEquals(base + 1, sequence(db, ws))
        val protected = facts(db, ws)
        assertTrue(protected.cashEntries.all { it.revision == "2" && JsonPrimitive("category_id") in it.manualFields.jsonArray })
        assertEquals(first, service.setEntryCategory(token, listOf(data.group, data.single), base + 1, data.source, ws))
        assertEquals(protected, facts(db, ws)); assertEquals(base + 1, sequence(db, ws))
        service.setEntryCategory(token, listOf(data.group, data.single), base + 1, null, ws)
        val cleared = facts(db, ws)
        assertEquals(base + 2, sequence(db, ws)); assertTrue(cleared.cashEntries.all { it.categoryId == null && it.revision == "3" })
        assertEquals(before.cashTransactions, cleared.cashTransactions)
        val single = cleared.cashEntryMembers.single { it.transactionId == data.parents[2].toString() }
        assertEquals(JsonNull, single.standaloneValues.jsonObject["category_id"])
        assertEquals(JsonPrimitive("来源独立备注"), single.standaloneValues.jsonObject["note"])
        assertEquals(listOf("category_id", "note"), single.standaloneManualFields.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun missingTargetsAndCategoryOrStaleBaseLeaveTheWholeSelectionUnchanged() = authenticated { db, ws, account, token, _ ->
        val data = fixture(db, ws, account, token); val before = facts(db, ws); val base = sequence(db, ws)
        val service = CashClassificationService(db)
        data class Rejection(val ids: List<String>, val version: Long, val category: String?, val code: String)
        for (case in listOf(
            Rejection(listOf(data.group, "missing"), base, data.target, "projection.version_conflict"),
            Rejection(listOf(data.group, data.single), base - 1, data.target, "projection.version_conflict"),
            Rejection(listOf(data.group, data.single), base, "missing", "category.not_found"),
            Rejection(emptyList(), base, data.target, "category.projections_required"))) {
            assertEquals(case.code, assertFailsWith<CategoryFailure> {
                service.setEntryCategory(token, case.ids, case.version, case.category, ws)
            }.code)
            assertEquals(before, facts(db, ws)); assertEquals(base, sequence(db, ws))
        }
    }

    @Test fun missingOrDeletedMembersAndInvalidRootsRejectBeforeAnyPublicEdit() {
        for (mode in listOf("deleted", "missing", "root", "identity")) authenticated { db, ws, account, token, _ ->
            val data = fixture(db, ws, account, token)
            db.write { c ->
                val sql = c.jooq(db.sqlite)
                when (mode) {
                    "deleted" -> sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.DELETED_AT, deletionTime)
                        .where(CASH_TRANSACTIONS.ID.eq(data.parents[0])).execute()
                    "missing" -> sql.deleteFrom(CASH_ENTRY_MEMBERS).where(CASH_ENTRY_MEMBERS.TRANSACTION_ID.eq(data.parents[0])).execute()
                    "root" -> sql.update(CASH_ENTRIES).set(CASH_ENTRIES.ROOT_TRANSACTION_ID, data.parents[2]).where(CASH_ENTRIES.ID.eq(data.group)).execute()
                    else -> sql.update(CASH_ENTRIES).set(CASH_ENTRIES.IDENTITY_TRANSACTION_ID, data.parents[2]).where(CASH_ENTRIES.ID.eq(data.group)).execute()
                }
                c.markLedgerChanged(ws)
            }
            val before = facts(db, ws); val base = sequence(db, ws)
            assertEquals("projection.version_conflict", assertFailsWith<CategoryFailure> {
                CashClassificationService(db).setEntryCategory(token, listOf(data.single, data.group), base, data.target, ws)
            }.code)
            assertEquals(before, facts(db, ws)); assertEquals(base, sequence(db, ws))
        }
    }

    @Test fun viewerOrRevokedPermissionsAreRecheckedInsideTheActualWriteTransaction() {
        for (mode in listOf("viewer", "removed", "expired")) authenticated { db, ws, account, token, user ->
            val data = fixture(db, ws, account, token); val before = facts(db, ws); val base = sequence(db, ws)
            val service = CashClassificationService(db, beforeWrite = { db.write { c ->
                val sql = c.jooq(db.sqlite)
                when (mode) {
                    "viewer" -> sql.update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "viewer")
                        .where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(ws)).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute()
                    "removed" -> sql.deleteFrom(WORKSPACE_MEMBERSHIPS).where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(ws))
                        .and(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute()
                    else -> sql.update(USER_SESSIONS).set(USER_SESSIONS.EXPIRES_AT, deletionTime)
                        .where(USER_SESSIONS.USER_ID.eq(user)).execute()
                }
            } })
            assertFailsWith<AccessFailure> { service.setEntryCategory(token, listOf(data.group), base, data.target, ws) }
            assertEquals(before, facts(db, ws)); assertEquals(base, sequence(db, ws))
        }
    }

    @Test fun anotherWorkspacesEntryOrCategoryCannotBeChangedThroughTheCurrentSelection() = authenticated { db, ws, account, token, _ ->
        val data = fixture(db, ws, account, token)
        withCashWriteSetTestWorkspace(backend) { other, foreign, foreignAccount ->
            val foreignEntry = "foreign-${UUID.randomUUID()}"; val foreignCategory = "foreign-category-${UUID.randomUUID()}"
            other.write { c ->
                val root = deletionFact(c, other, foreign, foreignAccount, null)
                deletionEntry(c, other, foreign, foreignEntry, root, null)
                deletionInsert(c, other, "cash_entry_members", linkedMapOf("workspace_id" to foreign, "transaction_id" to root,
                    "entry_id" to foreignEntry, "standalone_values" to JsonObject(emptyMap()), "standalone_manual_fields" to JsonArray(emptyList())))
                deletionInsert(c, other, "cash_categories", linkedMapOf("id" to foreignCategory, "workspace_id" to foreign,
                    "parent_scope_key" to "__root__", "name" to "外部分类", "normalized_name" to "外部分类", "category_path" to "/$foreignCategory/",
                    "depth" to 1, "sort_order" to 0, "revision" to 1L, "created_at" to deletionTime, "updated_at" to deletionTime))
            }
            val before = facts(db, ws); val outside = facts(other, foreign); val base = sequence(db, ws)
            val service = CashClassificationService(db)
            assertEquals("projection.version_conflict", assertFailsWith<CategoryFailure> {
                service.setEntryCategory(token, listOf(data.group, foreignEntry), base, data.target, ws)
            }.code)
            assertEquals("category.not_found", assertFailsWith<CategoryFailure> {
                service.setEntryCategory(token, listOf(data.group), base, foreignCategory, ws)
            }.code)
            assertFailsWith<AccessFailure> { service.setEntryCategory(token, listOf(foreignEntry), sequence(other, foreign), null, foreign) }
            assertEquals(before, facts(db, ws)); assertEquals(outside, facts(other, foreign)); assertEquals(base, sequence(db, ws))
        }
    }

    @Test fun entryAndLedgerOverflowRollBackAllEntriesAndMembers() = authenticated { db, ws, account, token, _ ->
        val data = fixture(db, ws, account, token)
        for (ledgerOverflow in listOf(false, true)) {
            db.write { c ->
                val sql = c.jooq(db.sqlite)
                sql.update(CASH_ENTRIES).set(CASH_ENTRIES.REVISION, if (ledgerOverflow) 1L else Long.MAX_VALUE)
                    .where(CASH_ENTRIES.ID.eq(data.single)).execute()
                if (ledgerOverflow) sql.update(WORKSPACES).set(WORKSPACES.LEDGER_SEQ, Long.MAX_VALUE).where(WORKSPACES.ID.eq(ws)).execute()
            }
            val before = facts(db, ws); val base = sequence(db, ws)
            assertFailsWith<ArithmeticException> {
                CashClassificationService(db).setEntryCategory(token, listOf(data.group, data.single), base, data.target, ws)
            }
            assertEquals(before, facts(db, ws)); assertEquals(base, sequence(db, ws))
        }
    }

    @Test fun storageFailureAfterTheMemberBatchRollsBackEntryChangesAndLedgerVersion() {
        val observation = SqlObservation()
        authenticated(observation::wrap) { db, ws, account, token, _ ->
            val data = fixture(db, ws, account, token); val before = facts(db, ws); val base = sequence(db, ws)
            observation.active = true; observation.failAfterMembers = true
            try {
                assertFailsWith<StorageFailure> {
                    CashClassificationService(db).setEntryCategory(token, listOf(data.group, data.single), base, data.target, ws)
                }
            } finally { observation.active = false }
            assertTrue(observation.failedAfterMembers)
            assertEquals(before, facts(db, ws)); assertEquals(base, sequence(db, ws))
        }
    }

    @Test fun selectedEntryIdsAreCapturedBeforeTheBeforeWriteCallback() = authenticated { db, ws, account, token, _ ->
        val data = fixture(db, ws, account, token); val base = sequence(db, ws)
        val input = mutableListOf(data.group)
        val service = CashClassificationService(db, beforeWrite = { input.clear(); input += data.single })
        service.setEntryCategory(token, input, base, data.target, ws)
        val after = facts(db, ws)
        assertEquals(data.target, after.cashEntries.single { it.id == data.group }.categoryId)
        assertEquals(data.source, after.cashEntries.single { it.id == data.single }.categoryId)
    }

    @Test fun canonicalBatchDoesNotReadOrWriteAnyProjectionTable() {
        val observation = SqlObservation()
        authenticated(observation::wrap) { db, ws, account, token, _ ->
            val data = fixture(db, ws, account, token); val base = sequence(db, ws)
            observation.active = true
            try { CashClassificationService(db).setEntryCategory(token, listOf(data.group, data.single), base, data.target, ws) }
            finally { observation.active = false }
            assertTrue(observation.statements.any { "cash_entry_members" in it.lowercase() })
            assertTrue(observation.statements.none { "cash_projection" in it.lowercase() })
            assertTrue(observation.statements.size <= 12, "Expected a bounded set of statements: ${observation.statements}")
        }
    }

    @Test fun selectingMoreThanOneSqlChunkKeepsEverySelectedMemberAndLeavesOthersUnchanged() = authenticated { db, ws, account, token, _ ->
        val source = CategoryService(db).create(token, "原分类").id
        val target = CategoryService(db).create(token, "批量分类").id
        val ids = db.write { c ->
            val selected = List(402) { index ->
                val root = deletionFact(c, db, ws, account, source)
                val id = "$ws:chunk:$index"
                deletionEntry(c, db, ws, id, root, source)
                deletionInsert(c, db, "cash_entry_members", linkedMapOf("workspace_id" to ws, "transaction_id" to root, "entry_id" to id,
                    "standalone_values" to buildJsonObject { put("category_id", source); put("note", "保持原值") },
                    "standalone_manual_fields" to JsonArray(emptyList())))
                id
            }
            c.markLedgerChanged(ws)
            selected
        }
        val base = sequence(db, ws)
        val before = facts(db, ws)
        val result = CashClassificationService(db).setEntryCategory(token, ids.take(401) + ids.first(), base, target, ws)
        assertEquals(JsonPrimitive(401), result["projection_count"]); assertEquals(JsonPrimitive(401), result["updated_transaction_count"])
        assertEquals(base + 1, sequence(db, ws))
        val after = facts(db, ws)
        assertEquals(before.cashTransactions, after.cashTransactions)
        assertEquals(401, after.cashEntries.count { it.categoryId == target && it.revision == "2" })
        assertEquals(before.cashEntries.single { it.id == ids.last() }, after.cashEntries.single { it.id == ids.last() })
        val unselected = before.cashEntryMembers.single { it.entryId == ids.last() }
        assertEquals(unselected, after.cashEntryMembers.single { it.entryId == ids.last() })
        assertEquals(401, after.cashEntryMembers.count { it.standaloneValues.jsonObject["category_id"] == JsonPrimitive(target) })
    }

    @Test fun malformedStoredPublicMetadataRemainsAProgramFailureWithoutPartialWrites() = authenticated { db, ws, account, token, _ ->
        val data = fixture(db, ws, account, token)
        db.write { c -> c.jooq(db.sqlite).update(CASH_ENTRIES).set(CASH_ENTRIES.MANUAL_FIELDS, JsonObject(emptyMap()))
            .where(CASH_ENTRIES.ID.eq(data.group)).execute() }
        val before = facts(db, ws); val base = sequence(db, ws)
        assertFailsWith<IllegalArgumentException> {
            CashClassificationService(db).setEntryCategory(token, listOf(data.single, data.group), base, data.target, ws)
        }
        assertEquals(before, facts(db, ws)); assertEquals(base, sequence(db, ws))
    }

    @Test fun hundredSelectionsFromTenThousandEntriesKeepTheSameSqlLatencyAndMemoryBudgets() {
        val observation = SqlObservation()
        authenticated(observation::wrap) { db, ws, account, token, _ ->
            val source = CategoryService(db).create(token, "来源分类").id
            val target = CategoryService(db).create(token, "选择分类").id
            val ids = db.write { c ->
                val selected = mutableListOf<String>()
                for (index in 0 until 10000) {
                    val root = deletionFact(c, db, ws, account, source)
                    val id = "$ws:performance:$index"
                    deletionEntry(c, db, ws, id, root, source)
                    val members = if (index < 100) listOf(root, deletionFact(c, db, ws, account, source)) else listOf(root)
                    if (index < 100) selected += id
                    for (member in members) deletionInsert(c, db, "cash_entry_members", linkedMapOf(
                        "workspace_id" to ws, "transaction_id" to member, "entry_id" to id,
                        "standalone_values" to buildJsonObject { put("category_id", source); put("note", "保持原值") },
                        "standalone_manual_fields" to JsonArray(emptyList())))
                }
                c.markLedgerChanged(ws)
                selected
            }
            fun rss(): Long {
                val process = ProcessBuilder("/bin/ps", "-o", "rss=", "-p", ProcessHandle.current().pid().toString()).start()
                val output = process.inputStream.bufferedReader().readText().trim().toLong()
                check(process.waitFor() == 0)
                return output * 1024
            }
            val baseline = rss()
            val peak = java.util.concurrent.atomic.AtomicLong(baseline)
            val running = java.util.concurrent.atomic.AtomicBoolean(true)
            val sampleFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val sampler = Thread {
                try { while (running.get()) { peak.accumulateAndGet(rss(), ::maxOf); Thread.sleep(10) } }
                catch (failure: Throwable) { sampleFailure.set(failure) }
            }.apply { isDaemon = true; start() }
            val elapsed = mutableListOf<Long>(); val counts = mutableListOf<Int>()
            var version = sequence(db, ws)
            try {
                for (round in 0 until 7) {
                    observation.statements.clear(); observation.active = true
                    val started = System.nanoTime()
                    val result = try { CashClassificationService(db).setEntryCategory(token, ids, version, if (round % 2 == 0) target else source, ws) }
                    finally { observation.active = false }
                    val duration = System.nanoTime() - started
                    version++
                    assertEquals(JsonPrimitive(version), result["projection_version"])
                    assertEquals(JsonPrimitive(100), result["projection_count"])
                    assertEquals(JsonPrimitive(200), result["updated_transaction_count"])
                    if (round >= 2) { elapsed += duration; counts += observation.statements.size }
                }
            } finally { running.set(false); sampler.join() }
            sampleFailure.get()?.let { throw AssertionError("RSS sampling failed", it) }
            val growth = (peak.get() - baseline).coerceAtLeast(0)
            val evidence = buildJsonObject {
                put("backend", backend); put("entries", 10000); put("selected", 100); put("selected_members", 200)
                put("p95_ns", elapsed.max()); put("max_sql", counts.max()); put("sampled_rss_growth_bytes", growth)
                put("sql", JsonArray(observation.statements.map(::JsonPrimitive)))
            }
            println("ENTRY_CLASSIFICATION_PERFORMANCE $evidence")
            System.getenv("FT_ENTRY_CLASSIFICATION_PERF_DIR")?.let {
                val path = java.nio.file.Path.of(it); require(path.isAbsolute)
                java.nio.file.Files.createDirectories(path)
                java.nio.file.Files.writeString(path.resolve("entry-classification-performance-$backend.json"), evidence.toString())
            }
            assertTrue(counts.all { it <= 12 }, "100 selections exceeded 12 SQL statements: $evidence")
            assertTrue(elapsed.max() <= 500000000L, "p95 exceeded 500ms: $evidence")
            assertTrue(growth <= 96L * 1024 * 1024, "RSS growth exceeded 96 MiB: $evidence")
        }
    }

    @Test fun publicClassificationUsesTheExactCanonicalLedgerVersionWithoutMaterializedState() = authenticated { db, ws, account, token, _ ->
        val data = fixture(db, ws, account, token)
        db.write { c -> c.jooq(db.sqlite).update(WORKSPACES).set(WORKSPACES.LEDGER_SEQ, 9007199254740993L).where(WORKSPACES.ID.eq(ws)).execute() }
        val before = facts(db, ws)
        testApplication {
            application { financeModule(db, "http://localhost:18390") }
            val response = client.put("/api/v1/cash-projections/categories") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("projection_ids", JsonArray(listOf(JsonPrimitive("cash-entry:${data.single}"))))
                    put("projection_version", 9007199254740993L); put("category_id", data.target) }.toString())
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals(9007199254740994L, Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("projection_version").jsonPrimitive.long)
            assertEquals(before.cashTransactions, facts(db, ws).cashTransactions)
        }
    }

    @Test fun publicClassificationRetainsIntegerCompatibleVersionsAndRejectsWrongWorkspaceHeaders() = authenticated { db, ws, account, token, _ ->
        val data = fixture(db, ws, account, token)
        testApplication {
            application { financeModule(db, "http://localhost:18390") }
            for (version in listOf("1", "true", "1.0", "\" 1 \"", "\"+1\"")) {
                db.write { c -> c.jooq(db.sqlite).update(WORKSPACES).set(WORKSPACES.LEDGER_SEQ, 1L).where(WORKSPACES.ID.eq(ws)).execute() }
                val response = client.put("/api/v1/cash-projections/categories") {
                    bearerAuth(token); contentType(ContentType.Application.Json)
                    setBody("""{"projection_ids":["cash-entry:${data.single}"],"projection_version":$version,"category_id":"${data.target}"}""")
                }
                assertEquals(HttpStatusCode.OK, response.status, "version=$version ${response.bodyAsText()}")
            }
            val before = facts(db, ws)
            for (headers in listOf(listOf("another-workspace"), listOf(ws, ws), listOf(""))) {
                val response = client.put("/api/v1/cash-projections/categories") {
                    bearerAuth(token); contentType(ContentType.Application.Json)
                    headers.forEach { header("X-FT-Workspace-ID", it) }
                    setBody("""{"projection_ids":["cash-entry:${data.single}"],"projection_version":1,"category_id":null}""")
                }
                assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
                assertEquals(before, facts(db, ws))
            }
        }
    }

}
