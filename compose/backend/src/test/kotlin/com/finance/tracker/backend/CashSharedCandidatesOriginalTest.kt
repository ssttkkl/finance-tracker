package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.nio.file.Path
import java.sql.Connection
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class CashSharedCandidatesOriginalTest {
    private class Fixture(val name: String) {
        val f = CashImportOriginalApiFixture(name, "FT_KOTLIN_SHARED_CANDIDATE_DIR")
        fun plan(variant: String): Map<String, Any?> {
            val files = f.files(variant); val staged = f.store.create(f.workspace, f.user, files)
            return f.service.preview(f.token, staged.sessionId, 1, null, mapOf("account_selections" to CashImportFunding.scan(files).groups.map {
                mapOf("source_account_ref" to it.sourceAccountRef, "account_id" to "101", "expected_account_revision" to 1)
            }))
        }
        fun commit(plan: Map<String, Any?>, key: String, commands: CashImportCommandService = f.commands) =
            commands.commit(f.token, plan["plan_id"] as String, (plan["session_revision"] as Number).toLong(), key)
        val original = commit(plan("default"), "synthetic-source-candidates")
        val identifiers = ReceiptReadService(f.database, { f.now }).results(f.token, original.apiText("receipt_id"), "row", ReceiptPage(0, 200))
            .apiArray("items").map { it.jsonObject.apiText("transaction_id").toLong() }.toSet()
        val pending = plan("second")
        fun change(connection: Connection, note: String) {
            val t = CASH_TRANSACTIONS
            assertEquals(1, connection.jooq(f.database.sqlite).update(t).set(t.NOTE, note).set(t.ROW_REVISION, t.ROW_REVISION.plus(1L))
                .where(t.ID.eq(identifiers.min()).and(t.WORKSPACE_ID.eq(f.workspace))).execute())
        }
        fun note() = f.database.read { it.jooq(f.database.sqlite).select(CASH_TRANSACTIONS.NOTE).from(CASH_TRANSACTIONS)
            .where(CASH_TRANSACTIONS.ID.eq(identifiers.min())).fetchSingle(CASH_TRANSACTIONS.NOTE) }
    }

    @Test fun frozenSharedCandidatesSaveCurrentDraftIssues() {
        for ((index, change) in listOf(false, true).withIndex()) {
            val fixture = Fixture("current-$index"); val f = fixture.f
            assertEquals(2, (fixture.pending["summary"] as Map<*, *>)["create_draft"])
            if (change) f.database.write { fixture.change(it, "当前候选的新备注") }
            val saved = fixture.commit(fixture.pending, "synthetic-shared-pending")
            assertEquals(2, saved.getValue("summary").jsonObject.apiNumber("saved_draft"))
            assertEquals(0, saved.getValue("summary").jsonObject.apiNumber("skipped"))
            f.database.read { connection ->
                val sql = connection.jooq(f.database.sqlite); val t = CASH_TRANSACTIONS; val i = CASH_TRANSACTION_ISSUES; val c = CASH_ISSUE_CANDIDATES
                val expected = sql.select(t.ID, t.ROW_REVISION).from(t).where(t.ID.`in`(fixture.identifiers)).fetch().associate { it.value1() to it.value2() }
                val issues = sql.select(i.ID).from(i).where(i.CODE.eq("source_identity_ambiguous")).fetch(i.ID)
                assertEquals(2, issues.size)
                for (issue in issues) assertEquals(expected, sql.select(c.CANDIDATE_TRANSACTION_ID, c.EXPECTED_ROW_REVISION).from(c)
                    .where(c.ISSUE_ID.eq(issue)).fetch().associate { it.value1() to it.value2() })
            }
        }
    }

    @Test fun failedDraftSaveRollsBackSharedCandidatesAndAllNewRows() {
        val fixture = Fixture("failure"); val f = fixture.f
        val frozen = f.store.getPlanSnapshot(CashImportPlanViews.sessionId(fixture.pending["plan_id"] as String), f.workspace, f.user)
        val operations = (frozen.plan!!.getValue("operations") as List<*>).map { it as Map<*, *> }
        assertEquals(listOf(listOf("file_b:r1"), listOf("file_b:r2")), operations.map { it["row_refs"] })
        assertTrue(operations.all { it["planned_action"] == "create_draft" })
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_SHARED_CANDIDATE_DIR")), fixture.name, "database.json").readText()).jsonObject
        var connection: Connection? = null
        val database = Database(config.apiText("jdbc_url"), System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")) { it.also { connection = it } }
        val before = f.snapshot(); val count = f.count()
        var writes = 0
        val commands = CashImportCommandService(database, f.store, now = { f.now }, afterStep = { stage ->
            if (stage == "row_write") {
                val operation = operations[writes++]
                if (operation["row_refs"] == listOf("file_b:r1")) {
                    val current = requireNotNull(connection)
                    val sql = current.jooq(f.database.sqlite); val t = CASH_TRANSACTIONS
                    assertEquals(count + 1, sql.fetchCount(t))
                    assertTrue(sql.fetchCount(CASH_ISSUE_CANDIDATES) > 0)
                    val revision = sql.select(t.ROW_REVISION).from(t).where(t.ID.eq(fixture.identifiers.min())).fetchSingle(t.ROW_REVISION)!!
                    fixture.change(current, "应当回滚的候选变化")
                    val changed = sql.select(t.NOTE, t.ROW_REVISION).from(t).where(t.ID.eq(fixture.identifiers.min())).fetchSingle()
                    assertEquals("应当回滚的候选变化", changed[t.NOTE]); assertEquals(revision + 1L, changed[t.ROW_REVISION])
                    error("synthetic pending save rollback")
                }
            }
        })
        val error = assertFailsWith<CashImportCommitFailure> { fixture.commit(fixture.pending, "synthetic-shared-pending-fault", commands) }
        assertEquals("synthetic pending save rollback", assertIs<IllegalStateException>(error.failure).message)
        assertEquals("not_committed", error.commitState); assertEquals(1, writes)
        assertEquals(count, f.count()); assertEquals(before, f.snapshot()); assertNotEquals("应当回滚的候选变化", fixture.note())
        assertEquals(0, f.database.read { it.jooq(f.database.sqlite).fetchCount(CASH_ISSUE_CANDIDATES) })
    }
}
