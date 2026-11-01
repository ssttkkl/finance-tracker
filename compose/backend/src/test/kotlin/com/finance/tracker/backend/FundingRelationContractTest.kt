package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class FundingRelationContractTest {
    private val at = Instant.parse("2026-10-01T09:00:00Z")
    private val r = CASH_INVESTMENT_FUNDING_RELATIONS
    private val t = CASH_TRANSACTIONS
    private val p = CASH_TRANSACTION_COMPONENTS
    private fun db(name: String): Database {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_FUNDING_CONTRACT_DIR")))
            .resolve("test_" + name).resolve("database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    private fun <T> write(db: Database, block: (CashSqlContext) -> T): T = db.write { c ->
        assertTrue(c.lockWorkspace(db.sqlite, "funding")); block(CashSqlContext(c, "funding", db.sqlite, { at }))
    }
    private fun scan(db: Database) = write(db) { FundingRelationRepository(it).scan() }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.id() = text("id").toLong()
    private fun component(db: Database, record: String): Long = db.read { c -> c.jooq(db.sqlite)
        .select(p.ID).from(p).join(t).on(p.CASH_TRANSACTION_ID.eq(t.ID))
        .where(t.RECORD_ID.eq(record)).fetchSingle(p.ID)!! }
    private fun event(db: Database) = db.read { c -> c.jooq(db.sqlite).select(INVESTMENT_EVENTS.ID).from(INVESTMENT_EVENTS)
        .where(INVESTMENT_EVENTS.WORKSPACE_ID.eq("funding")).orderBy(INVESTMENT_EVENTS.ID).limit(1).fetchSingle(INVESTMENT_EVENTS.ID)!! }
    private fun draft(type: String) {
        val db = db("complete_cash_draft_is_not_a_funding_candidate_until_posted-" + type)
        assertEquals(emptyList(), scan(db))
        db.read { assertEquals(0, it.jooq(db.sqlite).fetchCount(r)) }
        write(db) { it.sql.update(t).set(t.STATE, "posted").where(t.RECORD_ID.eq("draft-funding")).execute() }
        val row = scan(db).single()
        assertEquals(component(db, "draft-funding"), row.text("cash_transaction_component_id").toLong())
        assertEquals(if (type == "investment_out") "accepted" else "pending_review", row.text("status"))
    }
    @Test fun completeStrongDraftIsExcludedUntilPosted() = draft("investment_out")
    @Test fun completeOrdinaryDraftIsExcludedUntilPosted() = draft("transfer_out")
    @Test fun strongCandidateIsIdempotentAndMaintainsRealProjection() {
        val db = db("unique_strong_candidate_is_idempotently_confirmed_and_projects_as_bank_security_transfer")
        val first = scan(db); assertEquals(first, scan(db))
        val row = first.single()
        assertEquals("accepted", row.text("status"))
        assertEquals(component(db, "cash-1"), row.text("cash_transaction_component_id").toLong())
        assertEquals(event(db), row.text("investment_event_id").toLong())
        assertEquals("cash-investment-funding-v1", row.text("rule_id"))
        assertEquals(Json.parseToJsonElement("""{"business_day_window":0,"candidate_count":1,"cash_record_type":"investment_out","match_keys":["amount","currency","direction","business_day"]}"""), row["evidence"])
        CashProjectionService(db, "funding", { at }).rebuild()
        db.read { c ->
            val table = CASH_PROJECTIONS
            val projection = c.jooq(db.sqlite).selectFrom(table).where(table.WORKSPACE_ID.eq("funding")).fetchSingle()
            assertEquals("internal_transfer", projection[table.ECONOMIC_TYPE])
            assertEquals("bank_security_transfer", projection[table.TRANSFER_SUBTYPE])
            assertEquals(0, projection[table.NET_AMOUNT]!!.text.toBigDecimal().signum())
            assertEquals(row.id(), projection[table.FUNDING_RELATION_ID])
        }
    }
    private fun institution(name: String, record: String, window: Int) {
        val db = db(name); val row = scan(db).first()
        assertEquals("accepted", row.text("status"))
        assertEquals(component(db, record), row.text("cash_transaction_component_id").toLong())
        assertEquals(event(db), row.text("investment_event_id").toLong())
        assertEquals("unique_institution_name_candidate", row.text("decision_reason"))
        assertEquals(Json.parseToJsonElement("""{"business_day_window":$window,"candidate_count":1,"cash_record_type":"transfer_out","match_keys":["institution_name","direction","business_day"]}"""), row["evidence"])
    }
    @Test fun institutionAllowsCrossCurrencyAndDifferentAmount() = institution(
        "unique_institution_name_candidate_allows_cross_currency_and_amount_difference", "cash-ibkr", 6)
    @Test fun schwabInstitutionAllowsBankFeeDifference() = institution(
        "schwab_institution_name_candidate_allows_bank_fee_difference", "cash-schwab", 0)
    @Test fun exactInstitutionCandidateReplacesGenericTransfer() {
        val db = db("institution_name_prefers_the_unique_exact_candidate_over_generic_transfer"); val row = scan(db).single()
        assertEquals("accepted", row.text("status"))
        assertEquals(component(db, "cash-dfzq"), row.text("cash_transaction_component_id").toLong())
        assertEquals(event(db), row.text("investment_event_id").toLong())
    }
    @Test fun institutionStillRequiresDirectionalWindow() {
        assertEquals(emptyList(), scan(db("institution_name_candidate_respects_directional_window")))
    }
    @Test fun unreviewedSystemCandidateCanBeUpgraded() {
        val row = scan(db("institution_name_candidate_upgrades_unreviewed_system_candidate")).first()
        assertEquals("accepted", row.text("status")); assertEquals("system", row.text("decided_by"))
        assertEquals("unique_institution_name_candidate", row.text("decision_reason"))
    }
    @Test fun strongerInstitutionArchivesLegacyGenericCandidate() {
        val db = db("stronger_institution_candidate_archives_legacy_generic_candidate")
        val rows = scan(db).associateBy { it.text("cash_transaction_component_id").toLong() }
        val generic = rows.getValue(component(db, "cash-legacy-generic"))
        assertEquals("rejected", generic.text("status")); assertEquals("no_longer_candidate", generic.text("decision_reason"))
        assertEquals("accepted", rows.getValue(component(db, "cash-legacy-dfzq")).text("status"))
    }
    @Test fun ambiguousCandidatesRequireManualDecisionAndConsumeEndpointsOnce() {
        val db = db("ordinary_or_ambiguous_candidates_require_manual_decision_and_consume_endpoints_once"); val rows = scan(db)
        assertEquals(setOf("pending_review"), rows.map { it.text("status") }.toSet())
        assertEquals(setOf(event(db)), rows.map { it.text("investment_event_id").toLong() }.toSet())
        val accepted = write(db) { FundingRelationRepository(it).confirm(rows.first().id(), "tester", "matched receipt") }
        assertEquals("accepted", accepted.text("status")); assertEquals("tester", accepted.text("decided_by"))
        val other = rows.first { it["cash_transaction_component_id"] != accepted["cash_transaction_component_id"] }
        assertContains(assertFailsWith<FundingRelationFailure> { write(db) { FundingRelationRepository(it).confirm(other.id(), "tester") } }.message.orEmpty(), "端点已被确认关系占用")
        val rejected = write(db) { FundingRelationRepository(it).reject(other.id(), "tester", "not the funding transfer") }
        assertEquals("rejected", rejected.text("status"))
        assertTrue(rejected.text("cash_transaction_component_id").toLong() != component(db, "cash-a") ||
            accepted.text("cash_transaction_component_id").toLong() == component(db, "cash-a"))
    }
    @Test fun acceptedFundingEvidenceChangesProjectionSourceDigest() {
        val db = db("confirmed_funding_relation_participates_in_projection_source_digest")
        assertEquals("accepted", scan(db).first().text("status"))
        write(db) { context ->
            val repository = CashProjectionSourceRepository(context); val before = repository.sourceDigest()
            context.sql.update(r).set(r.EVIDENCE, Json.parseToJsonElement("""{"candidate_count":1,"business_day_window":1}"""))
                .where(r.WORKSPACE_ID.eq("funding")).execute()
            assertNotEquals(before, repository.sourceDigest())
        }
    }
    @Test fun confirmationRechecksLifecycleWithoutConsumingInvestment() {
        val db = db("confirmation_rechecks_cash_lifecycle_without_consuming_an_investment_endpoint")
        val pending = scan(db).single(); assertEquals("pending_review", pending.text("status"))
        write(db) { it.sql.update(t).set(t.STATE, "draft").where(t.RECORD_ID.eq("pending-to-draft")).execute() }
        assertContains(assertFailsWith<FundingRelationFailure> { write(db) {
            FundingRelationRepository(it).confirm(pending.id(), "tester")
        } }.message.orEmpty(), "端点不可用")
        db.read { assertEquals("pending_review", it.jooq(db.sqlite).select(r.STATUS).from(r)
            .where(r.ID.eq(pending.id())).fetchSingle(r.STATUS)) }
        val other = write(db) { context ->
            val source = context.sql.selectFrom(t).fetchSingle()
            val clone = source.copy().apply {
                recordId = "other-posted"; state = "posted"; recordType = "investment_out"; recordSubtype = "not_applicable"
            }; clone.store()
            val part = context.sql.selectFrom(p).fetchSingle().copy().apply {
                cashTransactionId = clone.id
            }; part.store(); part.id!!
        }
        val accepted = scan(db).filter { it.text("status") == "accepted" }.single()
        assertEquals(other, accepted.text("cash_transaction_component_id").toLong())
    }
    @Test fun nonExternalAndCrossWorkspaceEndpointsFailClosed() {
        val db = db("non_external_funding_and_other_workspace_endpoints_fail_closed")
        assertEquals(emptyList(), scan(db))
        assertContains(assertFailsWith<FundingRelationFailure> { write(db) {
            FundingRelationRepository(it).confirm(999999, "tester")
        } }.message.orEmpty(), "找不到资金调拨关系")
        val foreign = write(db) { context ->
            val w = WORKSPACES; val a = ACCOUNTS; val e = INVESTMENT_EVENTS
            context.sql.insertInto(w).set(w.ID, "other").set(w.NAME, "other").set(w.CREATED_AT, at).execute()
            val account = context.sql.selectFrom(a).where(a.TYPE.eq("security")).fetchSingle().copy().apply {
                workspaceId = "other"; name = "Other Broker"
            }; account.store()
            val investment = context.sql.selectFrom(e).orderBy(e.ID).limit(1).fetchSingle().copy().apply {
                workspaceId = "other"; accountId = account.id; recordId = "other-investment"; recordSubtype = "external"
            }; investment.store(); investment.id!!
        }
        val part = component(db, "cash-sub")
        assertFailsWith<StorageFailure> { write(db) { context ->
            context.sql.insertInto(r).set(r.WORKSPACE_ID, "funding").set(r.CASH_TRANSACTION_COMPONENT_ID, part)
                .set(r.INVESTMENT_EVENT_ID, foreign).set(r.DIRECTION, "cash_to_investment").set(r.STATUS, "pending_review")
                .set(r.RULE_ID, "test").set(r.EVIDENCE, buildJsonObject {}).set(r.ACTIVE_SLOT, "active")
                .set(r.CREATED_BY, "system").set(r.CREATED_AT, at).set(r.DECIDED_BY, "").set(r.DECISION_REASON, "").execute()
        } }
        db.read { assertEquals(0, it.jooq(db.sqlite).fetchCount(r)) }
    }
    @Test fun competingWorkspaceDeleteIsReadAfterActualPostgresLockWait() {
        val db = db("funding_scan_reads_current_endpoints_after_a_competing_workspace_delete")
        org.junit.Assume.assumeFalse("Original scenario is PostgreSQL-only", db.sqlite)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            lateinit var worker: java.util.concurrent.Future<List<JsonObject>>
            db.write { writer ->
                assertTrue(writer.lockWorkspace(false, "funding"))
                val pid = writer.createStatement().use { statement -> statement.executeQuery("select pg_backend_pid()").use { it.next(); it.getInt(1) } }
                writer.jooq(false).update(t).set(t.DELETED_AT, at).where(t.RECORD_ID.eq("concurrent-delete")).execute()
                worker = executor.submit<List<JsonObject>> { scan(db) }
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                var waiting = false
                while (!waiting && System.nanoTime() < deadline) {
                    writer.createStatement().use { it.execute("select pg_stat_clear_snapshot()") }
                    waiting = writer.prepareStatement("select exists(select 1 from pg_stat_activity where ? = any(pg_blocking_pids(pid)))").use { statement ->
                        statement.setInt(1, pid); statement.executeQuery().use { it.next(); it.getBoolean(1) }
                    }
                    if (!waiting) Thread.sleep(10)
                }
                assertTrue(waiting, "Independent scanner must actually wait for the writer database lock")
            }
            assertEquals(emptyList(), worker.get(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
    }
}
