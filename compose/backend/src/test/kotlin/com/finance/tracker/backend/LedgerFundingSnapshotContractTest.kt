package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.*
import java.time.Instant
import kotlinx.serialization.json.*
import kotlin.test.*

class LedgerFundingSnapshotContractTest {
    private val at=Instant.parse("2026-10-01T09:00:00.123456Z")
    private val money="1.230000000000000001"
    private data class Fixture(val db:Database,val workspace:String,val token:String,val security:Long,
        val fact:Long,val part:Long,val event:Long,val relation:Long)
    private fun version(db:Database,workspace:String)=db.read {c->
        c.jooq(db.sqlite).select(WORKSPACES.LEDGER_SEQ).from(WORKSPACES).where(WORKSPACES.ID.eq(workspace)).fetchSingle().value1()!!
    }
    private fun event(context:CashSqlContext,security:Long,key:String,amount:String=money):Long {
        InvestmentEventRepository(context).insert(security,buildJsonObject {
            put("record_type","funding");put("record_subtype","external");put("occurred_at",manualInstantText(at))
            put("currency","CNY");put("to_amount",amount);put("note","investment-private-note")
            put("private_extra","investment-private-payload")
        },"snapshot-minimal",key,buildJsonObject {put("private","investment-private-source")})
        val e=INVESTMENT_EVENTS
        return context.sql.select(e.ID).from(e).where(e.WORKSPACE_ID.eq(context.workspace)).and(e.RECORD_ID.eq(key)).fetchSingle().value1()!!
    }
    private fun fixture(action:(Fixture)->Unit)=withLedgerTestWorkspace {db,workspace,token ->
        fun account(name:String,type:String)=AccountService(db).create(token,buildJsonObject {
            put("name",name);put("type",type);put("currencies",JsonArray(listOf(JsonPrimitive("CNY"))))
        },"create-$type").getValue("account_id").jsonPrimitive.long
        val cash=account("Funding cash","cash");val security=account("Funding security","security")
        val fact=db.write {c->check(c.lockWorkspace(db.sqlite,workspace))
            CashSqlContext(c,workspace,db.sqlite).insertAtomicCash(cash,at,CashAmount.parse("-$money"),"CNY","transfer_out","ordinary_transfer","import")
        }
        val initial=version(db,workspace)
        val event=db.write {c->check(c.lockWorkspace(db.sqlite,workspace));event(CashSqlContext(c,workspace,db.sqlite),security,"linked")}
        assertEquals(initial,version(db,workspace),"an unlinked investment insertion does not change cash facts")
        val relation=db.write {c->check(c.lockWorkspace(db.sqlite,workspace))
            FundingRelationRepository(CashSqlContext(c,workspace,db.sqlite)).scan().single()
        }
        assertEquals(initial+1,version(db,workspace));assertEquals("pending_review",relation.apiText("status"))
        action(Fixture(db,workspace,token,security,fact.first,fact.second,event,relation.getValue("id").jsonPrimitive.long))
    }
    private fun Fixture.confirm()=db.write {c->check(c.lockWorkspace(db.sqlite,workspace))
        FundingRelationRepository(CashSqlContext(c,workspace,db.sqlite)).confirm(relation,"tester")
    }
    @Test fun confirmationRollbackAccountRenameAndCashDeletionKeepSnapshotVersionCoherent()=fixture {f->
        val before=LedgerReadService(f.db).snapshot(f.token)
        assertNull(before.facts.cashInvestmentFundingRelations.single().investmentEvent)
        class Abort:RuntimeException()
        assertFailsWith<Abort> {f.db.write {c->check(c.lockWorkspace(f.db.sqlite,f.workspace))
            FundingRelationRepository(CashSqlContext(c,f.workspace,f.db.sqlite)).confirm(f.relation,"tester");throw Abort()
        }}
        assertEquals(before,LedgerReadService(f.db).snapshot(f.token))
        f.confirm()
        val accepted=LedgerReadService(f.db).snapshot(f.token)
        assertEquals(before.ledgerSeq.toLong()+1,accepted.ledgerSeq.toLong())
        f.db.read {c->assertLocalCashProjectionViews(c,f.db,f.workspace,accepted,f.token)}
        val relation=accepted.facts.cashInvestmentFundingRelations.single()
        assertEquals(f.event.toString(),relation.investmentEventId)
        val investment=requireNotNull(relation.investmentEvent)
        assertEquals(f.security.toString(),investment.accountId);assertEquals("CNY",investment.currency)
        assertNull(investment.fromAmount);assertEquals(money,investment.toAmount)
        val encoded=Json.encodeToJsonElement(LedgerSnapshotDto.serializer(),accepted).jsonObject
        val nested=encoded.getValue("facts").jsonObject.getValue("cash_investment_funding_relations").jsonArray.single().jsonObject.getValue("investment_event").jsonObject
        assertEquals(setOf("account_id","currency","from_amount","to_amount"),nested.keys)
        assertFalse("investment-private" in encoded.toString())
        AccountService(f.db).update(f.token,f.security.toString(),buildJsonObject {put("name","Renamed funding security")},1,"rename-security")
        val renamed=LedgerReadService(f.db).snapshot(f.token)
        assertEquals(accepted.ledgerSeq.toLong()+1,renamed.ledgerSeq.toLong())
        assertEquals("Renamed funding security",renamed.facts.accounts.single {it.id==investment.accountId}.name)
        val staging=f.db.write {c->check(c.lockWorkspace(f.db.sqlite,f.workspace))
            val repository=CashProjectionRepository(CashSqlContext(c,f.workspace,f.db.sqlite))
            val sources=repository.readValidatedSources()
            val dataset=repository.createStagingDataset(sources.digest,CASH_PROJECTION_RULES_VERSION)
            repository.replaceDataset(dataset,buildCashProjections(sources.facts,sources.relations),1)
            dataset
        }
        val beforeDelete=detailDatabaseState(f.db)
        class DeleteAbort:Error()
        assertFailsWith<DeleteAbort> {CashDeletionService(f.db,afterStage={if(it=="references")throw DeleteAbort()})
            .deleteRecord(f.token,f.fact.toString())}
        assertEquals(beforeDelete,detailDatabaseState(f.db))
        assertEquals(renamed,LedgerReadService(f.db).snapshot(f.token))
        CashDeletionService(f.db).deleteRecord(f.token,f.fact.toString())
        val deleted=LedgerReadService(f.db).snapshot(f.token)
        assertEquals(renamed.ledgerSeq.toLong()+1,deleted.ledgerSeq.toLong())
        assertTrue(deleted.facts.cashInvestmentFundingRelations.isEmpty())
        f.db.read {c->
            val sql=c.jooq(f.db.sqlite)
            assertEquals(1,sql.fetchCount(INVESTMENT_EVENTS,INVESTMENT_EVENTS.ID.eq(f.event)))
            val p=CASH_PROJECTIONS
            assertEquals(1,sql.fetchCount(p,p.WORKSPACE_ID.eq(f.workspace).and(p.DATASET_ID.eq(staging)).and(p.FUNDING_RELATION_ID.isNull)))
        }
    }
    @Test fun onlyActiveAcceptedRelationsAttachInvestmentFacts()=fixture {f->
        f.confirm()
        f.db.write {c->check(c.lockWorkspace(f.db.sqlite,f.workspace));c.markLedgerChanged(f.workspace)
            val context=CashSqlContext(c,f.workspace,f.db.sqlite)
            fun link(event:Long,status:String,slot:String)=deletionInsert(c,f.db,"cash_investment_funding_relations",linkedMapOf(
                "workspace_id" to f.workspace,"cash_transaction_component_id" to f.part,"investment_event_id" to event,
                "direction" to "cash_to_investment","status" to status,"active_slot" to slot,"rule_id" to "fixture",
                "evidence" to JsonObject(emptyMap()),"created_by" to "system","created_at" to at,"decided_by" to "","decision_reason" to ""))
            link(event(context,f.security,"rejected","10"),"rejected","active")
            link(event(context,f.security,"pending","11"),"pending_review","active")
            link(f.event,"accepted","inactive-minimal")
            event(context,f.security,"unlinked","12")
        }
        val workspaces=WorkspaceService(f.db)
        val originalVersion=version(f.db,f.workspace)
        val foreign=workspaces.create(f.token,"Funding snapshot foreign").activeWorkspaceId!!
        try {
            fun account(name:String,type:String)=AccountService(f.db).create(f.token,buildJsonObject {
                put("name",name);put("type",type);put("currencies",JsonArray(listOf(JsonPrimitive("CNY"))))
            },"foreign-$type").getValue("account_id").jsonPrimitive.long
            val cash=account("Foreign cash","cash");val security=account("Foreign investment","security")
            f.db.write {c->check(c.lockWorkspace(f.db.sqlite,foreign));val context=CashSqlContext(c,foreign,f.db.sqlite)
                context.insertAtomicCash(cash,at,CashAmount.parse("-$money"),"CNY","transfer_out","ordinary_transfer","import")
                event(context,security,"foreign")
                val row=FundingRelationRepository(context).scan().single()
                FundingRelationRepository(context).confirm(row.getValue("id").jsonPrimitive.long,"tester")
            }
            workspaces.select(f.token,f.workspace)
            val before=detailDatabaseState(f.db)
            val snapshot=LedgerReadService(f.db).snapshot(f.token)
            val relations=snapshot.facts.cashInvestmentFundingRelations
            assertEquals(originalVersion,snapshot.ledgerSeq.toLong())
            assertEquals(4,relations.size)
            assertEquals(listOf(f.relation.toString()),relations.filter {it.investmentEvent!=null}.map {it.id})
            assertEquals(before,detailDatabaseState(f.db))
            val encoded=Json.encodeToString(LedgerSnapshotDto.serializer(),snapshot)
            assertFalse("investment-private" in encoded);assertFalse("Foreign" in encoded)
            assertEquals(2,snapshot.facts.accounts.size)
        } finally {
            workspaces.select(f.token,foreign);workspaces.delete(f.token,"Funding snapshot foreign");workspaces.select(f.token,f.workspace)
        }
    }
}
