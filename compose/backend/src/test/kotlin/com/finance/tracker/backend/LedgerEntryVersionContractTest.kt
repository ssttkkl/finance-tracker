package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

class LedgerEntryVersionContractTest {
    private fun version(db: Database, workspace: String) = db.read { c -> c.jooq(db.sqlite)
        .select(WORKSPACES.LEDGER_SEQ).from(WORKSPACES).where(WORKSPACES.ID.eq(workspace)).fetchSingle(WORKSPACES.LEDGER_SEQ)!! }
    private fun fixture(action: (Database, String, Long, List<Long>) -> Unit) = withLedgerTestWorkspace { db, workspace, token ->
        val account = AccountService(db).create(token, buildJsonObject {
            put("name", "Entry version account"); put("type", "cash"); put("currencies", JsonArray(listOf(JsonPrimitive("CNY"))))
        }, "create").getValue("account_id").jsonPrimitive.long
        val ids = db.write { c ->
            check(c.lockWorkspace(db.sqlite, workspace))
            (1..2).map { CashSqlContext(c, workspace, db.sqlite).insertAtomicCash(account,
                Instant.parse("2026-10-01T09:00:00Z"), CashAmount.parse("-10"), "CNY", "consumption", "not_applicable", "import").first }
        }
        action(db, workspace, account, ids)
    }
    private fun projections(ids: List<Long>, account: Long, grouped: Boolean = false): List<CashProjection> {
        val facts = ids.map { CashProjectionFact.create(it, account, Instant.parse("2026-10-01T09:00:00Z"), "-10", "CNY", "Merchant", null, "", "fixture", it.toString()) }
        val edges = if (grouped) listOf(CashProjectionRelation.create(1, "payment_mirror", ids[0], ids[1])) else emptyList()
        return buildCashProjections(facts, edges).projections
    }
    private fun write(db: Database, workspace: String, action: (CashEntryRepository) -> Unit) = db.write { c ->
        check(c.lockWorkspace(db.sqlite, workspace)); action(CashEntryRepository(c, workspace, db.sqlite))
    }
    @Test fun atomicFactsAndComponentsAdvanceTogetherAndRollbackTogether() = fixture { db, workspace, account, _ ->
        val initial = version(db, workspace)
        fun insert(c: java.sql.Connection) = CashSqlContext(c, workspace, db.sqlite).insertAtomicCash(account,
            Instant.parse("2026-10-01T10:00:00Z"), CashAmount.parse("2"), "CNY", "income", "not_applicable", "manual")
        db.write { c -> check(c.lockWorkspace(db.sqlite, workspace)); insert(c); insert(c) }
        assertEquals(initial + 1, version(db, workspace))
        fun counts() = db.read { c -> val sql = c.jooq(db.sqlite)
            sql.fetchCount(CASH_TRANSACTIONS, CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace)) to
                sql.fetchCount(CASH_TRANSACTION_COMPONENTS, CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(workspace)) }
        val before = counts()
        class Rejected : RuntimeException()
        assertFailsWith<Rejected> { db.write { c -> check(c.lockWorkspace(db.sqlite, workspace)); insert(c); throw Rejected() } }
        assertEquals(before, counts()); assertEquals(initial + 1, version(db, workspace))
    }
    @Test fun reconcileCreatesOnceAndRepeatedReadsAndSameValuePatchDoNotAdvance() = fixture { db, workspace, account, ids ->
        val initial = version(db, workspace)
        write(db, workspace) { it.reconcile(projections(ids, account)) }
        assertEquals(initial + 1, version(db, workspace))
        write(db, workspace) { it.reconcile(projections(ids, account)); it.snapshot(ids); it.flush() }
        assertEquals(initial + 1, version(db, workspace))
        write(db, workspace) { it.patch(ids[0], mapOf("note" to "reviewed"), 1) }
        assertEquals(initial + 2, version(db, workspace))
        write(db, workspace) { it.patch(ids[0], mapOf("note" to "reviewed"), 2) }
        assertEquals(initial + 2, version(db, workspace))
        assertEquals("entry_changed", assertFailsWith<CashPolicyError> {
            write(db, workspace) { it.patch(ids[0], mapOf("note" to "stale"), 1) }
        }.code)
        assertEquals(initial + 2, version(db, workspace))
    }
    @Test fun memberChangesMergeSplitAndRemovalEachAdvanceOnce() = fixture { db, workspace, account, ids ->
        write(db, workspace) { it.reconcile(projections(ids, account)) }
        val initial = version(db, workspace)
        write(db, workspace) { it.reconcile(projections(ids, account, true)) }
        assertEquals(initial + 1, version(db, workspace))
        write(db, workspace) { it.reconcile(projections(ids, account, true)) }
        assertEquals(initial + 1, version(db, workspace))
        write(db, workspace) { it.prepareSplit(ids, false); it.reconcile(projections(ids, account)) }
        assertEquals(initial + 2, version(db, workspace))
        write(db, workspace) { it.removeMembers(ids) }
        assertEquals(initial + 3, version(db, workspace))
        write(db, workspace) { it.removeMembers(ids) }
        assertEquals(initial + 3, version(db, workspace))
        db.read { c -> assertFalse(c.jooq(db.sqlite).fetchExists(CASH_ENTRIES, CASH_ENTRIES.WORKSPACE_ID.eq(workspace))) }
    }
    @Test fun memberOnlyDirtyFlushAdvancesAndExpiredChangesDoNot() = fixture { db, workspace, account, ids ->
        write(db, workspace) { it.reconcile(projections(ids, account)) }
        val initial = version(db, workspace)
        write(db, workspace) { repo ->
            val member = repo.forTransactions(ids).first.getValue(ids[0])
            member.standaloneValues = JsonObject(member.standaloneValues + ("note" to JsonPrimitive("standalone")))
            repo.flush()
        }
        assertEquals(initial + 1, version(db, workspace))
        write(db, workspace) { repo ->
            val member = repo.forTransactions(ids).first.getValue(ids[0])
            member.standaloneValues = JsonObject(member.standaloneValues + ("note" to JsonPrimitive("discard")))
            repo.expireMember(ids[0]); repo.flush()
        }
        assertEquals(initial + 1, version(db, workspace))
    }
    @Test fun savepointReloadAndLaterEditPreserveFactAndVersionAtomicity() = fixture { db, workspace, account, ids ->
        write(db, workspace) { it.reconcile(projections(ids, account)) }
        val initial = version(db, workspace)
        db.write { c ->
            check(c.lockWorkspace(db.sqlite, workspace))
            val repo = CashEntryRepository(c, workspace, db.sqlite)
            val entry = repo.current(ids[0])!!
            val savepoint = c.setSavepoint("entry_edit")
            repo.patch(ids[0], mapOf("note" to "rolled back"), 1)
            c.rollback(savepoint)
            assertSame(entry, repo.current(ids[0])); assertEquals(1L, entry.revision); assertEquals(JsonPrimitive(""), entry.values["note"])
            repo.patch(ids[0], mapOf("note" to "kept"), 1)
            c.releaseSavepoint(savepoint)
        }
        assertEquals(initial + 1, version(db, workspace))
        val before = db.read { c -> CashEntryRepository(c, workspace, db.sqlite).snapshot(ids) }
        class Rejected : RuntimeException()
        assertFailsWith<Rejected> { write(db, workspace) { it.patch(ids[0], mapOf("note" to "discarded"), 2); throw Rejected() } }
        assertEquals(initial + 1, version(db, workspace))
        assertEquals(before, db.read { c -> CashEntryRepository(c, workspace, db.sqlite).snapshot(ids) })
    }
}
