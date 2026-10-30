package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class MutationPlanRevalidationTest {
    private fun database(scenario: String): Database {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv(
            "FT_KOTLIN_REVALIDATION_CONTRACT_DIR"))).resolve(scenario).resolve("database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    private val now = Instant.parse("2026-10-30T04:30:00Z")
    private fun preview(service: MutationPlanService, intent: String = "edit_record", patch: JsonObject =
        buildJsonObject { put("note", "提交备注") }) = service.preview("synthetic-admin", buildJsonObject {
        put("intent", intent); put("transaction_id", "2001"); put("expected_revision", 1); put("patch", patch)
    })
    private fun financial(db: Database) = db.read { connection ->
        listOf("accounts", "cash_transactions", "cash_transaction_components", "cash_transaction_issues", "cash_entries",
            "cash_entry_members", "cash_projection_states", "cash_projection_datasets", "cash_projections", "cash_projection_members",
            "cash_projection_relations", "transaction_relations", "cash_investment_funding_relations", "ledger_snapshots",
            "cash_mutation_receipts", "cash_mutation_results").associateWith { table ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM $table WHERE workspace_id='commit-a'").use { rows ->
                    buildSet { while (rows.next()) add((1..rows.metaData.columnCount).map { rows.getString(it) }) }
                }
            }
        }
    }
    private fun reject(db: Database, service: MutationPlanService, staging: ParsedImportStore, plan: JsonObject, code: String) {
        val id = plan.getValue("plan_id").jsonPrimitive.content
        val before = financial(db)
        val failure = assertFailsWith<MutationPlanFailure> { service.commit("synthetic-admin", id, 1, "revalidation") }
        assertEquals(code, failure.code)
        assertEquals(before, financial(db))
        assertEquals(plan, service.plan("synthetic-admin", id))
        assertFalse(staging.get(MutationPlanStore.contextId(id), "commit-a", "admin").cancelled)
        assertEquals("retry_original", service.resolve("synthetic-admin", id, "revalidation").getValue("status").jsonPrimitive.content)
    }
    private fun issue(db: Database, code: String, key: String) {
        val issue = CASH_TRANSACTION_ISSUES
        db.write { c -> c.jooq(db.sqlite).insertInto(issue).set(issue.ID, 7001L).set(issue.WORKSPACE_ID, "commit-a")
            .set(issue.TRANSACTION_ID, 2001L).set(issue.ISSUE_KEY, key).set(issue.CODE, code)
            .set(issue.PENDING_FIELDS, buildJsonObject { put("counterparty", "旧待采用") })
            .set(issue.ORIGIN, JsonObject(emptyMap())).set(issue.REVISION, 1L)
            .set(issue.CREATED_AT, now).set(issue.UPDATED_AT, now).execute() }
    }
    @Test fun changedIssueRejectsBeforeWritesAndPreservesThePlan() {
        val db = database("issue")
        issue(db, "source_update_pending", "source:pending")
        val staging = InMemoryParsedImportStore(); val service = MutationPlanService(db, staging = staging)
        val plan = preview(service)
        val issue = CASH_TRANSACTION_ISSUES
        db.write { c -> c.jooq(db.sqlite).update(issue).set(issue.REVISION, 2L)
            .set(issue.PENDING_FIELDS, buildJsonObject { put("counterparty", "新待采用") })
            .where(issue.WORKSPACE_ID.eq("commit-a")).and(issue.ID.eq(7001L)).execute() }
        reject(db, service, staging, plan, "record_changed")
    }
    @Test fun changedEntryValueWithoutRevisionRejects() {
        val db = database("entry"); val staging = InMemoryParsedImportStore(); val service = MutationPlanService(db, staging = staging)
        val plan = preview(service)
        db.write { c -> c.jooq(db.sqlite).update(CASH_ENTRIES).set(CASH_ENTRIES.COUNTERPARTY, "改过的商户")
            .where(CASH_ENTRIES.ID.eq("entry-2001")).execute() }
        reject(db, service, staging, plan, "record_changed")
    }
    @Test fun changedStandaloneMemberRejects() {
        val db = database("standalone"); val staging = InMemoryParsedImportStore(); val service = MutationPlanService(db, staging = staging)
        val plan = preview(service)
        val member = CASH_ENTRY_MEMBERS
        db.write { c -> c.jooq(db.sqlite).update(member).set(member.STANDALONE_VALUES, buildJsonObject { put("note", "恢复备注") })
            .set(member.STANDALONE_MANUAL_FIELDS, JsonArray(listOf(JsonPrimitive("note"))))
            .where(member.TRANSACTION_ID.eq(2001L)).execute() }
        reject(db, service, staging, plan, "record_changed")
    }
    @Test fun addedCurrentRelationRejectsBeforeWrites() {
        val db = database("relation"); val staging = InMemoryParsedImportStore(); val service = MutationPlanService(db, staging = staging)
        val plan = preview(service)
        val relation = TRANSACTION_RELATIONS
        db.write { c -> c.jooq(db.sqlite).insertInto(relation).set(relation.ID, 8001L).set(relation.WORKSPACE_ID, "commit-a")
            .set(relation.KIND, "refund_offset").set(relation.SUBTYPE, "not_applicable")
            .set(relation.PRIMARY_TRANSACTION_ID, 2001L).set(relation.ANCHOR_TRANSACTION_ID, 2001L)
            .set(relation.PRIMARY_COMPONENT_ID, 3001L).set(relation.ANCHOR_COMPONENT_ID, 3001L)
            .set(relation.STATE, "draft").set(relation.ACTIVE, true).set(relation.ROW_REVISION, 1L)
            .set(relation.DECISION_SOURCE, "rule").set(relation.EVIDENCE, JsonObject(emptyMap()))
            .set(relation.RULE_ID, "refund-test").set(relation.CREATED_BY, "system").set(relation.CREATED_AT, now)
            .set(relation.DECIDED_BY, "").set(relation.DECISION_REASON, "").execute() }
        reject(db, service, staging, plan, "relation_context_changed")
    }
    private fun draft(db: Database) = db.write { c -> c.jooq(db.sqlite).update(CASH_TRANSACTIONS)
        .set(CASH_TRANSACTIONS.STATE, "draft").where(CASH_TRANSACTIONS.ID.eq(2001L)).execute() }
    private fun accountChange(scenario: String, mutate: (org.jooq.DSLContext) -> Unit) {
        val db = database(scenario); draft(db)
        val staging = InMemoryParsedImportStore(); val service = MutationPlanService(db, staging = staging)
        val plan = preview(service, "save_draft")
        db.write { c -> mutate(c.jooq(db.sqlite)) }
        reject(db, service, staging, plan, "record_changed")
    }
    @Test fun changedAccountActivityRejects() = accountChange("active") { it.update(ACCOUNTS).set(ACCOUNTS.ACTIVE, false)
        .where(ACCOUNTS.ID.eq(301L)).execute() }
    @Test fun removedRequiredCurrencyRejects() = accountChange("currency") { it.update(ACCOUNTS)
        .set(ACCOUNTS.CURRENCIES, JsonArray(listOf(JsonPrimitive("USD")))).where(ACCOUNTS.ID.eq(301L)).execute() }
    @Test fun changedAccountTypeRejects() = accountChange("type") { it.update(ACCOUNTS).set(ACCOUNTS.TYPE, "security")
        .where(ACCOUNTS.ID.eq(301L)).execute() }
    @Test fun unrelatedAccountChangesDoNotInvalidatePostedNote() {
        val db = database("rename"); val service = MutationPlanService(db)
        val plan = preview(service)
        db.write { c -> c.jooq(db.sqlite).update(ACCOUNTS).set(ACCOUNTS.NAME, "改名账户").set(ACCOUNTS.REVISION, 2L)
            .set(ACCOUNTS.CURRENCIES, JsonArray(listOf(JsonPrimitive("CNY"), JsonPrimitive("USD"))))
            .where(ACCOUNTS.ID.eq(301L)).execute() }
        val receipt = service.commit("synthetic-admin", plan.getValue("plan_id").jsonPrimitive.content, 1, "rename")
        assertEquals(receipt, service.commit("synthetic-admin", plan.getValue("plan_id").jsonPrimitive.content, 1, "rename"))
        db.read { c -> assertEquals("提交备注", c.jooq(db.sqlite).select(CASH_ENTRIES.NOTE).from(CASH_ENTRIES)
            .where(CASH_ENTRIES.ID.eq("entry-2001")).fetchOne(CASH_ENTRIES.NOTE))
            assertEquals(JsonArray(listOf(JsonPrimitive("note"))), c.jooq(db.sqlite).select(CASH_ENTRIES.MANUAL_FIELDS)
                .from(CASH_ENTRIES).where(CASH_ENTRIES.ID.eq("entry-2001")).fetchOne(CASH_ENTRIES.MANUAL_FIELDS)) }
    }
    @Test fun draftSaveRetainsExternalIssue() {
        val db = database("retained"); draft(db); issue(db, "source_update_pending", "source:pending")
        val service = MutationPlanService(db); val plan = preview(service, "save_draft")
        service.commit("synthetic-admin", plan.getValue("plan_id").jsonPrimitive.content, 1, "retain")
        db.read { c -> val sql = c.jooq(db.sqlite)
            assertEquals(1, sql.fetchCount(CASH_TRANSACTION_ISSUES))
            assertEquals(1L, sql.select(CASH_TRANSACTION_ISSUES.REVISION).from(CASH_TRANSACTION_ISSUES)
                .where(CASH_TRANSACTION_ISSUES.ID.eq(7001L)).fetchOne(CASH_TRANSACTION_ISSUES.REVISION)) }
    }
    @Test fun draftSaveRecomputesCorrectedParsedIssue() {
        val db = database("parsed"); draft(db); issue(db, "amount_unreadable", "import:parsed")
        val service = MutationPlanService(db); val plan = preview(service, "save_draft")
        service.commit("synthetic-admin", plan.getValue("plan_id").jsonPrimitive.content, 1, "parsed")
        db.read { c -> assertEquals(0, c.jooq(db.sqlite).fetchCount(CASH_TRANSACTION_ISSUES)) }
    }
    @Test fun explicitUnavailableAccountRejectsBeforePreviewWrites() {
        val db = database("explicit"); draft(db)
        db.write { c -> c.jooq(db.sqlite).update(ACCOUNTS).set(ACCOUNTS.ACTIVE, false).where(ACCOUNTS.ID.eq(301L)).execute() }
        val before = financial(db)
        val failure = assertFailsWith<MutationPlanFieldFailure> { preview(MutationPlanService(db), "save_draft",
            buildJsonObject { put("account_id", "301") }) }
        assertEquals("account_unavailable", failure.code); assertEquals(listOf("account_id"), failure.fields)
        assertEquals(before, financial(db))
    }
    @Test fun retainedSourceProblemPreventsFinalization() {
        val db = database("blocked"); draft(db); issue(db, "source_update_pending", "source:pending")
        val before = financial(db)
        val failure = assertFailsWith<MutationPlanFieldFailure> { preview(MutationPlanService(db), "finalize_draft") }
        assertEquals("source_update_pending", failure.code); assertEquals(emptyList(), failure.fields)
        assertEquals(before, financial(db))
    }
    @Test fun draftSaveAppliesExistingEntryEdit() {
        val db = database("draftentry"); draft(db)
        val service = MutationPlanService(db); val plan = preview(service, "save_draft")
        service.commit("synthetic-admin", plan.getValue("plan_id").jsonPrimitive.content, 1, "draftentry")
        db.read { c -> assertEquals("提交备注", c.jooq(db.sqlite).select(CASH_ENTRIES.NOTE).from(CASH_ENTRIES)
            .where(CASH_ENTRIES.ID.eq("entry-2001")).fetchOne(CASH_ENTRIES.NOTE))
            assertEquals(JsonArray(listOf(JsonPrimitive("note"))), c.jooq(db.sqlite).select(CASH_ENTRIES.MANUAL_FIELDS)
                .from(CASH_ENTRIES).where(CASH_ENTRIES.ID.eq("entry-2001")).fetchOne(CASH_ENTRIES.MANUAL_FIELDS)) }
    }
    @Test fun postedSourceResolutionRemovesIssueAtomically() {
        val db = database("postedresolve"); issue(db, "source_update_pending", "source:pending")
        val service = MutationPlanService(db)
        val plan = service.preview("synthetic-admin", buildJsonObject {
            put("intent", "resolve_issue"); put("transaction_id", "2001"); put("expected_revision", 1)
            put("patch", buildJsonObject { put("note", "提交备注") })
            put("issue_resolutions", JsonArray(listOf(buildJsonObject {
                put("issue_id", "7001"); put("expected_issue_revision", 1); put("resolution", "keep_existing")
            })))
        })
        service.commit("synthetic-admin", plan.getValue("plan_id").jsonPrimitive.content, 1, "postedresolve")
        db.read { c -> val sql = c.jooq(db.sqlite)
            assertEquals(0, sql.fetchCount(CASH_TRANSACTION_ISSUES))
            assertEquals("提交备注", sql.select(CASH_ENTRIES.NOTE).from(CASH_ENTRIES)
                .where(CASH_ENTRIES.ID.eq("entry-2001")).fetchOne(CASH_ENTRIES.NOTE)) }
    }
    private fun lockChange(kind: String) {
        val db = database("lock" + kind); val staging = InMemoryParsedImportStore()
        fun disrupt(c: java.sql.Connection) {
            val sql = c.jooq(db.sqlite)
            when (kind) {
                "role" -> sql.update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "viewer")
                    .where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq("commit-a")).execute()
                "workspace" -> sql.update(USER_SESSIONS).setNull(USER_SESSIONS.ACTIVE_WORKSPACE_ID).execute()
                else -> sql.deleteFrom(USER_SESSIONS).execute()
            }
        }
        val reached = java.util.concurrent.CountDownLatch(1)
        val service = MutationPlanService(db, staging = staging,
            beforeCommitWrite = { if (db.sqlite) db.write(::disrupt) },
            beforeCommitWorkspaceLock = { reached.countDown() })
        val plan = preview(service); val id = plan.getValue("plan_id").jsonPrimitive.content
        val before = financial(db)
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_REVALIDATION_CONTRACT_DIR")))
            .resolve("lock" + kind).resolve("database.json").readText()).jsonObject
        val holder = if (db.sqlite) null else java.sql.DriverManager.getConnection(
            config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")).apply {
                autoCommit = false; assertTrue(lockWorkspace(false, "commit-a"))
            }
        var pending: java.util.concurrent.CompletableFuture<Result<JsonObject>>? = null
        try {
            val operation = java.util.concurrent.CompletableFuture.supplyAsync {
                runCatching { service.commit("synthetic-admin", id, 1, "lock-test") }
            }
            pending = operation
            if (holder != null) {
                assertTrue(reached.await(10, java.util.concurrent.TimeUnit.SECONDS))
                val pid = holder.createStatement().use { it.executeQuery("SELECT pg_backend_pid()").use { r ->
                    assertTrue(r.next()); r.getInt(1)
                } }
                var waiting = false; val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                while (!waiting && !operation.isDone && System.nanoTime() < deadline) {
                    waiting = db.read { c -> c.prepareStatement(
                        "SELECT count(*) FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)) AND wait_event_type='Lock'").use {
                        it.setInt(1, pid); it.executeQuery().use { r -> assertTrue(r.next()); r.getInt(1) > 0 }
                    } }
                    if (!waiting) Thread.sleep(10)
                }
                assertTrue(waiting, "commit must enter the actual PostgreSQL workspace lock queue")
                assertFalse(operation.isDone); disrupt(holder); holder.commit()
            }
            val result = operation.get(10, java.util.concurrent.TimeUnit.SECONDS)
            val failure = assertIs<AccessFailure>(result.exceptionOrNull())
            assertEquals(kind != "logout", failure.forbidden)
            assertEquals(before, financial(db))
            assertFalse(staging.get(MutationPlanStore.contextId(id), "commit-a", "admin").cancelled)
        } finally {
            holder?.rollback(); holder?.close()
            pending?.handle { _, _ -> Unit }?.get(15, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
    @Test fun commitRechecksRoleAfterActualWorkspaceLockWait() = lockChange("role")
    @Test fun commitRechecksWorkspaceAfterActualWorkspaceLockWait() = lockChange("workspace")
    @Test fun commitRechecksLogoutAfterActualWorkspaceLockWait() = lockChange("logout")
    @Test fun concurrentOriginalKeyCommitsReturnOneReceipt() {
        val db = database("race"); val staging = InMemoryParsedImportStore()
        val arrived = java.util.concurrent.CountDownLatch(2); val release = java.util.concurrent.CountDownLatch(1)
        val service = MutationPlanService(db, staging = staging, beforeCommitWrite = {
            arrived.countDown(); assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
        })
        val plan = preview(service); val id = plan.getValue("plan_id").jsonPrimitive.content
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val pending = (1..2).map { executor.submit<JsonObject> { service.commit("synthetic-admin", id, 1, "race") } }
            assertTrue(arrived.await(10, java.util.concurrent.TimeUnit.SECONDS)); release.countDown()
            assertEquals(pending[0].get(15, java.util.concurrent.TimeUnit.SECONDS), pending[1].get(15, java.util.concurrent.TimeUnit.SECONDS))
            db.read { c -> val sql = c.jooq(db.sqlite)
                assertEquals(1, sql.fetchCount(CASH_MUTATION_RECEIPTS)); assertEquals(1, sql.fetchCount(CASH_MUTATION_RESULTS))
                assertEquals(1L, sql.select(CASH_TRANSACTIONS.ROW_REVISION).from(CASH_TRANSACTIONS)
                    .where(CASH_TRANSACTIONS.ID.eq(2001L)).fetchOne(CASH_TRANSACTIONS.ROW_REVISION))
                assertEquals(2L, sql.select(CASH_ENTRIES.REVISION).from(CASH_ENTRIES)
                    .where(CASH_ENTRIES.ID.eq("entry-2001")).fetchOne(CASH_ENTRIES.REVISION)) }
        } finally { release.countDown(); executor.shutdown(); assertTrue(executor.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)) }
    }
    @Test fun storedNormalizationDoesNotWidenPublicInput() {
        val stored = buildJsonObject { put("intent", "edit_record"); put("transaction_id", "2001"); put("expected_revision", 1)
            put("relation_additions", JsonArray(emptyList())) }
        assertEquals("edit_record", MutationPlanRequest.fromStored(stored).intent)
        assertEquals("plan_input_invalid", assertFailsWith<MutationPlanFailure> { MutationPlanRequest.parse(stored) }.code)
        assertEquals("plan_input_invalid", assertFailsWith<MutationPlanFailure> {
            MutationPlanRequest.fromStored(JsonObject(stored + ("relation_additions" to JsonArray(listOf(JsonObject(emptyMap()))))))
        }.code)
    }
}
