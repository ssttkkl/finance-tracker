package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.sql.Connection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.*
import kotlinx.serialization.json.*

class LedgerBaseContractTest {
    private fun fixture(action: (Database, String, Long, Long) -> Unit) = withLedgerTestWorkspace { db, workspace, token ->
        val account = AccountService(db).create(token, buildJsonObject {
            put("name", "Original"); put("type", "cash")
        }, "initial").getValue("account_id").jsonPrimitive.content.toLong()
        action(db, workspace, account, db.read { version(it, db, workspace) })
    }
    private fun version(c: Connection, db: Database, workspace: String): Long = requireNotNull(c.jooq(db.sqlite)
        .select(WORKSPACES.LEDGER_SEQ).from(WORKSPACES).where(WORKSPACES.ID.eq(workspace)).fetchOne(WORKSPACES.LEDGER_SEQ))
    private fun name(db: Database, workspace: String, account: Long): String = db.read { c -> requireNotNull(c.jooq(db.sqlite)
        .select(ACCOUNTS.NAME).from(ACCOUNTS).where(ACCOUNTS.WORKSPACE_ID.eq(workspace)).and(ACCOUNTS.ID.eq(account)).fetchOne(ACCOUNTS.NAME)) }
    private fun change(c: Connection, db: Database, workspace: String, account: Long, name: String) {
        check(c.jooq(db.sqlite).update(ACCOUNTS).set(ACCOUNTS.NAME, name).set(ACCOUNTS.REVISION, ACCOUNTS.REVISION.plus(1))
            .where(ACCOUNTS.WORKSPACE_ID.eq(workspace)).and(ACCOUNTS.ID.eq(account)).execute() == 1)
        c.markLedgerChanged(workspace)
    }
    private fun receipt(c: Connection, db: Database, workspace: String, key: String) = ReceiptRepository(c, workspace, db.sqlite)
        .create("admin", key, tokenDigest(key), "import", mapOf("status" to "completed"), emptyList<Any>())
    private fun findReceipt(db: Database, workspace: String, key: String) = db.read {
        ReceiptRepository(it, workspace, db.sqlite).byKey("admin", key)
    }

    @Test fun acceptedBaseDoesNotAdvanceUntilFactsChangeAndMultipleWritesAdvanceOnce() = fixture { db, workspace, account, base ->
        db.write { c ->
            c.requireLedgerBase(workspace, base)
            assertEquals(base, version(c, db, workspace))
        }
        assertEquals(base, db.read { version(it, db, workspace) })
        db.write { c ->
            c.requireLedgerBase(workspace, base)
            change(c, db, workspace, account, "First")
            change(c, db, workspace, account, "Second")
            receipt(c, db, workspace, "accepted")
        }
        assertEquals(base + 1, db.read { version(it, db, workspace) })
        assertEquals("Second", name(db, workspace, account))
        assertNotNull(findReceipt(db, workspace, "accepted"))
    }

    @Test fun staleAndFutureBasesRejectWithoutFactsOrReceipt() = fixture { db, workspace, account, base ->
        for (expected in listOf(base - 1, base + 1)) {
            val failure = assertFailsWith<LedgerBaseChanged> { db.write { c ->
                c.requireLedgerBase(workspace, expected)
                change(c, db, workspace, account, "Rejected")
                receipt(c, db, workspace, "rejected")
            } }
            assertEquals(expected, failure.expected)
            assertEquals(base, failure.current)
            assertEquals("ledger.base_changed", failure.message)
            assertEquals(base, db.read { version(it, db, workspace) })
            assertEquals("Original", name(db, workspace, account))
            assertNull(findReceipt(db, workspace, "rejected"))
        }
    }

    @Test fun failureAfterReceiptRollsBackFactsVersionAndReceiptTogether() = fixture { db, workspace, account, base ->
        val failure = AssertionError("synthetic-after-receipt")
        assertSame(failure, assertFailsWith<AssertionError> { db.write { c ->
            c.requireLedgerBase(workspace, base)
            change(c, db, workspace, account, "Rolled back")
            receipt(c, db, workspace, "rolled-back")
            throw failure
        } })
        assertEquals(base, db.read { version(it, db, workspace) })
        assertEquals("Original", name(db, workspace, account))
        assertNull(findReceipt(db, workspace, "rolled-back"))
        db.write { c -> c.requireLedgerBase(workspace, base); change(c, db, workspace, account, "Retry") }
        assertEquals(base + 1, db.read { version(it, db, workspace) })
    }

    @Test fun concurrentSubmissionsAtTheSameBaseAcceptOnlyOne() = fixture { db, workspace, account, base ->
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val futures = (1..2).map { index -> executor.submit<Boolean> {
                ready.countDown(); check(start.await(5, TimeUnit.SECONDS))
                try {
                    db.write { c ->
                        c.requireLedgerBase(workspace, base)
                        change(c, db, workspace, account, "Writer-$index")
                        receipt(c, db, workspace, "concurrent-$index")
                    }
                    true
                } catch (failure: LedgerBaseChanged) {
                    assertEquals(base + 1, failure.current); false
                }
            } }
            check(ready.await(5, TimeUnit.SECONDS)); start.countDown()
            val results = futures.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it })
            val winner = results.indexOf(true) + 1
            assertEquals("Writer-$winner", name(db, workspace, account))
            assertNotNull(findReceipt(db, workspace, "concurrent-$winner"))
            assertNull(findReceipt(db, workspace, "concurrent-${3-winner}"))
            assertEquals(base + 1, db.read { version(it, db, workspace) })
        } finally { start.countDown(); executor.shutdownNow() }
    }

    @Test fun baseLockSurvivesUntilCommitEvenBeforeVersionAdvance() = fixture { db, workspace, account, base ->
        val executor = Executors.newSingleThreadExecutor()
        val attempted = CountDownLatch(1)
        var second: java.util.concurrent.Future<Long>? = null
        try {
            db.write { first ->
                first.requireLedgerBase(workspace, base)
                second = executor.submit<Long> {
                    attempted.countDown()
                    db.write { c -> c.markLedgerChanged(workspace) }
                }
                check(attempted.await(5, TimeUnit.SECONDS))
                assertFailsWith<TimeoutException> { second!!.get(200, TimeUnit.MILLISECONDS) }
                change(first, db, workspace, account, "Locked")
            }
            assertEquals(base + 2, second!!.get(15, TimeUnit.SECONDS))
            assertEquals("Locked", name(db, workspace, account))
        } finally { executor.shutdownNow() }
    }

    @Test fun savepointRollbackDoesNotCacheAnEarlierAcceptedBase() = fixture { db, workspace, account, base ->
        db.write { c ->
            c.createStatement().use { it.execute("SAVEPOINT before_guard") }
            c.requireLedgerBase(workspace, base)
            change(c, db, workspace, account, "Discarded")
            c.createStatement().use { it.execute("ROLLBACK TO before_guard"); it.execute("RELEASE before_guard") }
            c.requireLedgerBase(workspace, base)
            change(c, db, workspace, account, "Kept")
            assertEquals(base + 1, assertFailsWith<LedgerBaseChanged> { c.requireLedgerBase(workspace, base) }.current)
        }
        assertEquals(base + 1, db.read { version(it, db, workspace) })
        assertEquals("Kept", name(db, workspace, account))
    }

    @Test fun guardPreservesExactLargeVersionsAndDoesNotOverflowForReadOnlyBase() = fixture { db, workspace, _, _ ->
        for (base in listOf(9_007_199_254_740_993L, Long.MAX_VALUE)) {
            db.write { c -> c.jooq(db.sqlite).update(WORKSPACES).set(WORKSPACES.LEDGER_SEQ, base)
                .where(WORKSPACES.ID.eq(workspace)).execute() }
            db.write { c -> c.requireLedgerBase(workspace, base) }
            assertEquals(base, db.read { version(it, db, workspace) })
            val failure = assertFailsWith<LedgerBaseChanged> { db.write { it.requireLedgerBase(workspace, base-1) } }
            assertEquals(base, failure.current)
        }
    }

    @Test fun guardRequiresAnActiveManagedTransactionAndAnExistingWorkspace() = fixture { db, workspace, _, base ->
        var closed: Connection? = null
        db.write { c ->
            closed = c
            assertFailsWith<IllegalArgumentException> { c.requireLedgerBase(workspace, -1) }
            assertTrue(assertFailsWith<AccessFailure> { c.requireLedgerBase("missing-$workspace", base) }.forbidden)
            c.requireLedgerBase(workspace, base)
        }
        assertFailsWith<IllegalStateException> { closed!!.requireLedgerBase(workspace, base) }
        assertEquals(base, db.read { version(it, db, workspace) })
    }
}
