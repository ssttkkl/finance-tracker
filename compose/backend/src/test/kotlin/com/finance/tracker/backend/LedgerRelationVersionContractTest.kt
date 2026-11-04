package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

class LedgerRelationVersionContractTest {
    private val at = Instant.parse("2026-10-01T09:00:00Z")
    private fun version(db: Database, workspace: String) = db.read { c -> c.jooq(db.sqlite).select(WORKSPACES.LEDGER_SEQ)
        .from(WORKSPACES).where(WORKSPACES.ID.eq(workspace)).fetchSingle(WORKSPACES.LEDGER_SEQ)!! }
    private fun fixture(action: (Database, String, List<Pair<Long, Long>>) -> Unit) = withLedgerTestWorkspace { db, workspace, token ->
        val account = AccountService(db).create(token, buildJsonObject {
            put("name", "Relation version cash"); put("type", "cash"); put("currencies", JsonArray(listOf(JsonPrimitive("CNY"))))
        }, "create").getValue("account_id").jsonPrimitive.long
        val ids = db.write { c ->
            check(c.lockWorkspace(db.sqlite, workspace)); val context = CashSqlContext(c, workspace, db.sqlite)
            (1..3).map { context.insertAtomicCash(account, at, CashAmount.parse("-10"), "CNY", "consumption", "not_applicable", "import") }
        }
        action(db, workspace, ids)
    }
    private fun write(db: Database, workspace: String, action: (CashSqlContext) -> Unit) = db.write { c ->
        check(c.lockWorkspace(db.sqlite, workspace)); action(CashSqlContext(c, workspace, db.sqlite))
    }
    private fun open(first: Pair<Long, Long>) = mapOf("kind" to "refund_offset", "primary_transaction_id" to first.first,
        "anchor_transaction_id" to first.first, "primary_component_id" to first.second, "anchor_component_id" to first.second)
    private fun candidate(second: Pair<Long, Long>) = mapOf("candidate_transaction_id" to second.first,
        "candidate_component_id" to second.second, "expected_row_revision" to 1L)
    @Test fun currentRelationsAndCandidateCommandsAdvanceOnceAndRejectedRevisionsDoNot() = fixture { db, workspace, ids ->
        val initial = version(db, workspace); var id = 0L
        write(db, workspace) { id = CurrentCashRelationWriteRepository(it).add(open(ids[0]))["id"] as Long }
        assertEquals(initial+1, version(db, workspace))
        write(db, workspace) { CurrentCashRelationWriteRepository(it).setCandidates(id, listOf(candidate(ids[1])), 1L) }
        assertEquals(initial+2, version(db, workspace))
        write(db, workspace) { CurrentCashRelationWriteRepository(it).setCandidates(id, listOf(candidate(ids[1])), 2L) }
        assertEquals(initial+3, version(db, workspace))
        assertEquals("relation_revision_changed", assertFailsWith<CashRelationFailure> {
            write(db, workspace) { CurrentCashRelationWriteRepository(it).replace(id, emptyMap<String, Any?>(), 2L) }
        }.code)
        assertEquals(initial+3, version(db, workspace))
        write(db, workspace) { context ->
            val repo = CurrentCashRelationWriteRepository(context)
            assertEquals(3L, repo.get(id)["row_revision"])
            repo.replace(id, mapOf("state" to "ignored", "active" to false), 3L)
            assertFalse(context.sql.fetchExists(CASH_RELATION_CANDIDATES, CASH_RELATION_CANDIDATES.RELATION_ID.eq(id)))
        }
        assertEquals(initial+4, version(db, workspace))
    }
    @Test fun invalidCandidateBatchAndWholeRelationTransactionRollBackTheirVersion() = fixture { db, workspace, ids ->
        val initial = version(db, workspace)
        assertFailsWith<CashRelationFailure> { write(db, workspace) { context ->
            val repo = CurrentCashRelationWriteRepository(context); val id = repo.add(open(ids[0]))["id"]
            repo.setCandidates(id, listOf(candidate(ids[1]), candidate(ids[2])+("evidence" to listOf(1))), 1L)
        } }
        assertEquals(initial, version(db, workspace))
        db.read { c -> assertFalse(c.jooq(db.sqlite).fetchExists(TRANSACTION_RELATIONS, TRANSACTION_RELATIONS.WORKSPACE_ID.eq(workspace))) }
    }
    @Test fun legacyRelationWritesAndEmptyOrMissingBatchesRespectVersionBoundaries() = fixture { db, workspace, ids ->
        val initial = version(db, workspace);var id=0L
        write(db, workspace) { id=CashRelationRepository(it).add(mapOf("kind" to "refund_offset", "primary_fact_id" to ids[0].first,
            "status" to "pending_review", "anchor_fact_id" to ids[0].first)) }
        assertEquals(initial+1,version(db,workspace))
        write(db, workspace) { CashRelationRepository(it).updateOpenLegCandidates(id,listOf(ids[1].second)) }
        assertEquals(initial+2,version(db,workspace))
        write(db, workspace) { repo ->
            CashRelationRepository(repo).updateStatusBatch(emptyList(),"rejected")
            CashRelationRepository(repo).updateStatusBatch(listOf(Long.MAX_VALUE),"rejected")
        }
        assertEquals(initial+2,version(db,workspace))
        write(db, workspace) { CashRelationRepository(it).updateStatus(id,"rejected",decidedBy="tester") }
        assertEquals(initial+3,version(db,workspace))
    }
    @Test fun legacyBindingAndKindRevisionAdvanceButInvalidKindDoesNot() = fixture { db,workspace,ids ->
        var id=0L
        write(db,workspace) { id=CashRelationRepository(it).add(mapOf("kind" to "payment_mirror", "primary_fact_id" to ids[0].first,
            "status" to "pending_review", "anchor_fact_id" to ids[0].first)) }
        val initial=version(db,workspace)
        write(db,workspace) { CashRelationRepository(it).bindOtherLeg(id,ids[1].first,status="accepted",decidedBy="tester") }
        assertEquals(initial+1,version(db,workspace))
        write(db,workspace) { CashRelationRepository(it).updateKind(id,"payment_mirror","tester","reviewed") }
        assertEquals(initial+2,version(db,workspace))
        assertFailsWith<CashRelationFailure> { write(db,workspace) { CashRelationRepository(it).updateKind(id,"refund_offset","tester","") } }
        assertEquals(initial+2,version(db,workspace))
    }
    @Test fun typeChangeRemovalAdvancesWithoutDependingOnProjectionPublication() = fixture { db,workspace,ids ->
        var id=0L
        write(db,workspace) { id=CashRelationRepository(it).add(mapOf("kind" to "payment_mirror", "primary_fact_id" to ids[0].first,
            "secondary_fact_id" to ids[1].first,"status" to "accepted")) }
        val initial=version(db,workspace)
        write(db,workspace) { context ->
            val before=CashRelationContextService(context).load(setOf(ids[0].first))
            context.sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.RECORD_TYPE,"investment_out")
                .set(CASH_TRANSACTIONS.ROW_REVISION,2L).where(CASH_TRANSACTIONS.ID.eq(ids[0].first)).execute()
            val result=CashRelationTypeUpdateService(context).maintain(before,setOf(ids[0].first),publish=false)
            assertEquals(setOf(id),result.removedRelationIds);assertNull(result.projectionStatus)
        }
        assertEquals(initial+1,version(db,workspace))
        write(db,workspace) { context ->
            val before=CashRelationContextService(context).load(setOf(ids[0].first))
            val result=CashRelationTypeUpdateService(context).maintain(before,emptySet(),publish=false)
            assertTrue(result.removedRelationIds.isEmpty())
        }
        assertEquals(initial+1,version(db,workspace))
    }
    @Test fun candidateInvalidationAdvancesOnlyForActualStaleTransitions() = fixture { db, workspace, ids ->
        write(db,workspace) { context ->
            val repo=CurrentCashRelationWriteRepository(context);val row=repo.add(open(ids[0]))
            repo.setCandidates(row["id"],listOf(candidate(ids[1])),1L)
            context.sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.ROW_REVISION,2L).where(CASH_TRANSACTIONS.ID.eq(ids[1].first)).execute()
        }
        val initial=version(db,workspace)
        write(db,workspace) { assertEquals(1,it.markCashRelationCandidatesStale(setOf(ids[1].first)).size) }
        assertEquals(initial+1,version(db,workspace))
        write(db,workspace) { assertTrue(it.markCashRelationCandidatesStale(setOf(ids[1].first)).isEmpty()) }
        assertEquals(initial+1,version(db,workspace))
    }
    @Test fun logicalDeletionWithoutAnyRelationsStillAdvancesAndRejectsRepeats() = fixture { db,workspace,ids ->
        val initial=version(db,workspace)
        write(db,workspace) { CashRelationService(it).logicalDeleteCash(ids[0].first,"tester","remove") }
        assertEquals(initial+1,version(db,workspace))
        assertFailsWith<IllegalArgumentException> { write(db,workspace) { CashRelationService(it).logicalDeleteCash(ids[0].first,"tester","remove") } }
        assertEquals(initial+1,version(db,workspace))
    }
    @Test fun fundingScanNoopsAndRejectedDecisionRollbacksRespectVersion() = fixture { db, workspace, ids ->
        write(db,workspace) { context ->
            context.sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.RECORD_TYPE,"transfer_out")
                .set(CASH_TRANSACTIONS.RECORD_SUBTYPE,"ordinary_transfer").where(CASH_TRANSACTIONS.ID.eq(ids[0].first)).execute()
            val account=deletionInsert(context.connection,db,"accounts",linkedMapOf("workspace_id" to workspace,
                "name" to "Funding version security","type" to "security","active" to true,"revision" to 1L,
                "currencies" to JsonArray(listOf(JsonPrimitive("CNY"))),"metadata_json" to JsonObject(emptyMap()),"created_at" to at,"updated_at" to at))!!
            val e=INVESTMENT_EVENTS
            context.sql.insertInto(e).set(e.WORKSPACE_ID,workspace).set(e.ACCOUNT_ID,account).set(e.SOURCE_TYPE,"ibkr_csv")
                .set(e.RECORD_ID,"funding-version").set(e.SOURCE_PAYLOAD,JsonNull).set(e.OCCURRED_AT,at)
                .set(e.RECORD_TYPE,"funding").set(e.RECORD_SUBTYPE,"external").set(e.CURRENCY,"CNY").set(e.NOTE,"")
                .set(e.FROM_TICKER,"").setNull(e.FROM_AMOUNT).set(e.TO_TICKER,"CNY").set(e.TO_AMOUNT,CashStoredDecimal("10"))
                .setNull(e.COMMISSION).set(e.COMMISSION_ASSET,"").set(e.PAYLOAD,JsonObject(emptyMap())).set(e.CREATED_AT,at).execute()
        }
        val initial=version(db,workspace);var row:JsonObject?=null
        write(db,workspace) { row=FundingRelationRepository(it).scan().single() }
        assertEquals(initial+1,version(db,workspace));assertEquals(JsonPrimitive("pending_review"),row!!["status"])
        write(db,workspace) { assertEquals(row,FundingRelationRepository(it).scan().single()) }
        assertEquals(initial+1,version(db,workspace))
        val id=row!!.getValue("id").jsonPrimitive.long
        class Rejected:RuntimeException()
        assertFailsWith<Rejected> { write(db,workspace) { FundingRelationRepository(it).reject(id,"tester");throw Rejected() } }
        assertEquals(initial+1,version(db,workspace))
        write(db,workspace) { assertEquals(JsonPrimitive("rejected"),FundingRelationRepository(it).reject(id,"tester")["status"]) }
        assertEquals(initial+2,version(db,workspace))
        write(db,workspace) { FundingRelationRepository(it).scan() }
        assertEquals(initial+2,version(db,workspace))
    }

}
