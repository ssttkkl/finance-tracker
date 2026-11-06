package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class LedgerSourceVersionContractTest {
    private fun version(db: Database, workspace: String) = db.read { c ->
        c.jooq(db.sqlite).select(WORKSPACES.LEDGER_SEQ).from(WORKSPACES).where(WORKSPACES.ID.eq(workspace))
            .fetchSingle(WORKSPACES.LEDGER_SEQ)!!
    }
    private fun fixture(action: (Database, String, Long, Long) -> Unit) = withLedgerTestWorkspace { db, workspace, token ->
        val id = AccountService(db).create(token, buildJsonObject {
            put("name", "Source version account"); put("type", "cash"); put("currencies", JsonArray(listOf(JsonPrimitive("CNY"))))
        }, "create").getValue("account_id").jsonPrimitive.long
        // Seed valid cash facts; this slice exercises their source metadata write paths.
        val facts = db.write { c ->
            check(c.lockWorkspace(db.sqlite, workspace))
            val context = CashSqlContext(c, workspace, db.sqlite)
            (1..2).map {
                context.insertAtomicCash(id, Instant.parse("2026-10-01T09:00:00Z"), CashAmount.parse("1"), "CNY",
                    "income", "not_applicable", "import", sourceType = "alipay").first
            }
        }
        action(db, workspace, facts[0], facts[1])
    }
    private fun occurrence() = SourceOccurrence("alipay", "owner", "a".repeat(64), "fixture-v1", "table", 1,
        emptyMap(), providerId = "source-version-provider")
    private fun files(coverage: CashSourceCoverage = CashSourceCoverage(null, null, false, "", emptyMap(), "coverage-v1", documentKey = "f".repeat(64), pageTotal = 3,
        pageFingerprints = mapOf("1" to "a".repeat(64), "2" to "b".repeat(64), "3" to "c".repeat(64)))): List<ParsedImportFile> =
        listOf(ParsedImportFile("source-version", "a".repeat(64), "parsed", ParsedCashDocument("alipay", "table", "fixture-v1",
            emptyList(), "owner", "Synthetic source", coverage, 0, 0), filename = "source-version.csv"))
    private fun write(db: Database, workspace: String, action: (CashSqlContext) -> Unit) = db.write { c ->
        check(c.lockWorkspace(db.sqlite, workspace))
        action(CashSqlContext(c, workspace, db.sqlite))
    }
    @Test fun bindingAndIssueChangesAdvanceWhileIdenticalInputsAndMissingClearDoNot() = fixture { db, workspace, first, second ->
        val initial = version(db, workspace)
        write(db, workspace) { CashSourceWriteRepository(it).saveOccurrence(first, occurrence()) }
        assertEquals(initial + 1, version(db, workspace))
        write(db, workspace) { CashSourceWriteRepository(it).saveOccurrence(first, occurrence()) }
        assertEquals(initial + 1, version(db, workspace))
        fun issue(note: String? = null) = write(db, workspace) {
            CashSourceWriteRepository(it).saveIssue(first, "identity", "source_identity_ambiguous",
                pendingFields = note?.let { value -> mapOf("note" to value) }, candidates = mapOf(second to 1L))
        }
        issue(); assertEquals(initial + 2, version(db, workspace))
        issue(); assertEquals(initial + 2, version(db, workspace))
        issue("changed"); assertEquals(initial + 3, version(db, workspace))
        write(db, workspace) { CashSourceWriteRepository(it).clearIssue(first, "identity") }
        assertEquals(initial + 4, version(db, workspace))
        write(db, workspace) { CashSourceWriteRepository(it).clearIssue(first, "identity") }
        assertEquals(initial + 4, version(db, workspace))
    }
    @Test fun unchangedCandidateSetsKeepTheirStoredIdentities() = fixture { db, workspace, first, second ->
        fun save(note: String? = null) = write(db, workspace) {
            CashSourceWriteRepository(it).saveIssue(first, "identity", "source_identity_ambiguous",
                pendingFields = note?.let { value -> mapOf("note" to value) }, candidates = mapOf(second to 1L))
        }
        fun candidates() = db.read { c -> c.jooq(db.sqlite).selectFrom(CASH_ISSUE_CANDIDATES)
            .where(CASH_ISSUE_CANDIDATES.WORKSPACE_ID.eq(workspace)).fetch().map { it.intoMap() } }
        save()
        val original = candidates()
        save(); assertEquals(original, candidates())
        save("changed issue metadata"); assertEquals(original, candidates())
    }
    @Test fun certifiedEmptyPagesAdvanceOnceAndReplayOrConflictDoesNotAdvance() = fixture { db, workspace, _, _ ->
        val initial = version(db, workspace)
        val input = files()
        write(db, workspace) { CashSourceDocumentWriteRepository(it).saveEmptyPages(input) }
        assertEquals(initial + 1, version(db, workspace))
        write(db, workspace) { CashSourceDocumentWriteRepository(it).saveEmptyPages(input) }
        assertEquals(initial + 1, version(db, workspace))
        val conflicting = input.map { it.copy(document = it.document!!.copy(coverage = it.document!!.coverage.copy(
            pageFingerprints = it.document!!.coverage.pageFingerprints + ("1" to "e".repeat(64))))) }
        assertEquals("source_document_conflict", assertFailsWith<CashSourceBindingConflict> {
            write(db, workspace) { CashSourceDocumentWriteRepository(it).saveEmptyPages(conflicting) }
        }.code)
        assertEquals(initial + 1, version(db, workspace))
    }
    @Test fun sourceBindingsIssuesAndEmptyPagesShareOneAdvanceAndRollBackTogether() = fixture { db, workspace, first, second ->
        val initial = version(db, workspace)
        class Rejected : RuntimeException()
        assertFailsWith<Rejected> {
            write(db, workspace) { context ->
                CashSourceWriteRepository(context).saveOccurrence(first, occurrence())
                CashSourceWriteRepository(context).saveIssue(first, "identity", "source_identity_ambiguous", candidates = mapOf(second to 1L))
                CashSourceDocumentWriteRepository(context).saveEmptyPages(files())
                assertEquals(initial + 1, context.sql.select(WORKSPACES.LEDGER_SEQ).from(WORKSPACES)
                    .where(WORKSPACES.ID.eq(workspace)).fetchSingle(WORKSPACES.LEDGER_SEQ))
                throw Rejected()
            }
        }
        assertEquals(initial, version(db, workspace))
        db.read { c ->
            val sql = c.jooq(db.sqlite)
            assertFalse(sql.fetchExists(CASH_SOURCE_BINDINGS, CASH_SOURCE_BINDINGS.WORKSPACE_ID.eq(workspace)))
            assertFalse(sql.fetchExists(CASH_TRANSACTION_ISSUES, CASH_TRANSACTION_ISSUES.WORKSPACE_ID.eq(workspace)))
            assertFalse(sql.fetchExists(CASH_SOURCE_EMPTY_PAGES, CASH_SOURCE_EMPTY_PAGES.WORKSPACE_ID.eq(workspace)))
        }
    }
    @Test fun draftBindingTransferAdvancesOnlyWhenThereAreBindingsToMove() = fixture { db, workspace, first, second ->
        db.write { c -> c.jooq(db.sqlite).update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.STATE, "draft")
            .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace)).and(CASH_TRANSACTIONS.ID.eq(first)).execute() }
        val initial = version(db, workspace)
        write(db, workspace) { CashSourceWriteRepository(it).transferDraftBindings(first, second) }
        assertEquals(initial, version(db, workspace))
        write(db, workspace) { CashSourceWriteRepository(it).saveOccurrence(first, occurrence(), uncertain = true) }
        assertEquals(initial + 1, version(db, workspace))
        write(db, workspace) { CashSourceWriteRepository(it).transferDraftBindings(first, second) }
        assertEquals(initial + 2, version(db, workspace))
        write(db, workspace) { CashSourceWriteRepository(it).transferDraftBindings(first, second) }
        assertEquals(initial + 2, version(db, workspace))
        db.read { c -> assertTrue(c.jooq(db.sqlite).select(CASH_SOURCE_BINDINGS.TRANSACTION_ID).from(CASH_SOURCE_BINDINGS)
            .where(CASH_SOURCE_BINDINGS.WORKSPACE_ID.eq(workspace)).fetch(CASH_SOURCE_BINDINGS.TRANSACTION_ID).all { it == second }) }
    }
}
