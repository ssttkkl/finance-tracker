package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

class LedgerSnapshotContractTest {
    private val at=Instant.parse("2026-10-01T09:00:00.123456Z")
    private val amount="-1.230000000000000001"
    private fun fixture(action:(Database,String,String,Long,Long)->Unit)=withLedgerTestWorkspace { db,workspace,token ->
        val account=AccountService(db).create(token,buildJsonObject {
            put("name","Snapshot cash");put("type","cash");put("currencies",JsonArray(listOf(JsonPrimitive("CNY"))))
        },"create").getValue("account_id").jsonPrimitive.long
        val fact=db.write { c ->
            check(c.lockWorkspace(db.sqlite,workspace));val context=CashSqlContext(c,workspace,db.sqlite)
            val (id,_)=context.insertAtomicCash(account,at,CashAmount.parse(amount),"CNY","consumption","not_applicable","import")
            context.entries.applyEdit(id,mapOf("note" to "reviewed"),null)
            id
        }
        action(db,workspace,token,account,fact)
    }
    @Test fun snapshotContainsCanonicalFactsOnlyWithExactMoneyTimeAndWorkspace()=fixture { db,workspace,token,account,fact -> testApplication {
        val before=detailDatabaseState(db)
        application { financeModule(db) }
        val response=client.get("/api/v1/ledger/snapshot") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK,response.status,response.bodyAsText())
        val snapshot=Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("workspace_id","ledger_seq","rules_version","facts"),snapshot.keys)
        assertEquals(JsonPrimitive(workspace),snapshot["workspace_id"])
        assertEquals(JsonPrimitive(com.finance.tracker.business.domain.CASH_RULES_VERSION),snapshot["rules_version"])
        assertTrue(snapshot.getValue("ledger_seq").jsonPrimitive.isString)
        val facts=snapshot.getValue("facts").jsonObject
        assertEquals(setOf("accounts","account_aliases","statement_account_mappings","cash_categories","category_directory_revision",
            "cash_transactions","cash_transaction_components","cash_source_bindings","cash_source_empty_pages","transaction_relations",
            "cash_relation_candidates","cash_transaction_issues","cash_issue_candidates","cash_entries","cash_entry_members",
            "cash_investment_funding_relations"),facts.keys)
        val savedAccount=facts.getValue("accounts").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(account.toString()),savedAccount["id"]);assertFalse("metadata_json" in savedAccount)
        val saved=facts.getValue("cash_transactions").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(fact.toString()),saved["id"]);assertEquals(JsonPrimitive(amount),saved["amount"])
        assertEquals(at,Instant.parse(saved.getValue("occurred_at").jsonPrimitive.content))
        assertEquals(1,facts.getValue("cash_transaction_components").jsonArray.size)
        assertEquals(JsonPrimitive("reviewed"),facts.getValue("cash_entries").jsonArray.single().jsonObject["note"])
        assertEquals(1,facts.getValue("cash_entry_members").jsonArray.size)
        assertEquals(before,detailDatabaseState(db))
    } }
    @Test fun snapshotHttpPreservesStoredOpenJsonNumberLexemes() = fixture { db,workspace,token,_,fact ->
        val raw = Json.parseToJsonElement("""{"tokens":[9007199254740993,1e0,-0,1.2300,18446744073709551616,true,null,"1e0"]}""")
        db.write { c ->
            check(c.lockWorkspace(db.sqlite,workspace)); c.markLedgerChanged(workspace)
            val sql=c.jooq(db.sqlite)
            sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.SOURCE_PAYLOAD,raw)
                .where(CASH_TRANSACTIONS.ID.eq(fact)).and(CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace)).execute()
            sql.update(CASH_ENTRY_MEMBERS).set(CASH_ENTRY_MEMBERS.STANDALONE_VALUES,raw)
                .where(CASH_ENTRY_MEMBERS.TRANSACTION_ID.eq(fact)).and(CASH_ENTRY_MEMBERS.WORKSPACE_ID.eq(workspace)).execute()
        }
        assertEquals(raw,LedgerReadService(db).snapshot(token).facts.cashTransactions.single().sourcePayload)
        val before=detailDatabaseState(db)
        testApplication {
            application { financeModule(db) }
            val response=client.get("/api/v1/ledger/snapshot") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK,response.status,response.bodyAsText())
            val facts=Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("facts").jsonObject
            assertEquals(raw,facts.getValue("cash_transactions").jsonArray.single().jsonObject["source_payload"])
            assertEquals(raw,facts.getValue("cash_entry_members").jsonArray.single().jsonObject["standalone_values"])
        }
        assertEquals(before,detailDatabaseState(db))
    }

    @Test fun writerBetweenVersionAndFactsCannotMixSnapshotVersions()=fixture { db,workspace,token,account,fact ->
        val initial=LedgerReadService(db).version(token).ledgerSeq
        val service=LedgerReadService(db,afterSnapshotVersionRead={ db.write { c ->
            check(c.lockWorkspace(db.sqlite,workspace));c.markLedgerChanged(workspace)
            val sql=c.jooq(db.sqlite)
            sql.update(ACCOUNTS).set(ACCOUNTS.NAME,"Later cash").set(ACCOUNTS.REVISION,ACCOUNTS.REVISION.plus(1L))
                .where(ACCOUNTS.ID.eq(account)).and(ACCOUNTS.WORKSPACE_ID.eq(workspace)).execute()
            sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.NOTE,"later source")
                .set(CASH_TRANSACTIONS.ROW_REVISION,CASH_TRANSACTIONS.ROW_REVISION.plus(1L))
                .where(CASH_TRANSACTIONS.ID.eq(fact)).and(CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace)).execute()
        } })
        val snapshot=service.snapshot(token)
        assertEquals(initial,snapshot.ledgerSeq);assertEquals("Snapshot cash",snapshot.facts.accounts.single().name)
        assertEquals("",snapshot.facts.cashTransactions.single().note)
        val fresh=LedgerReadService(db).snapshot(token)
        assertEquals((initial.toLong()+1).toString(),fresh.ledgerSeq)
        assertEquals("Later cash",fresh.facts.accounts.single().name);assertEquals("later source",fresh.facts.cashTransactions.single().note)
    }
    @Test fun snapshotRechecksSelectionBeforeReturningAnyFacts()=fixture { db,workspace,token,_,_ ->
        assertFailsWith<AccessFailure> { LedgerReadService(db).snapshot(token,Principal("admin","","fixture-b")) }
        val service=LedgerReadService(db,afterSnapshotVersionRead={ db.write { c -> c.jooq(db.sqlite).update(USER_SESSIONS)
            .setNull(USER_SESSIONS.ACTIVE_WORKSPACE_ID).where(USER_SESSIONS.TOKEN_DIGEST.eq(tokenDigest(token))).execute() } })
        try { assertTrue(assertFailsWith<AccessFailure> { service.snapshot(token) }.forbidden) }
        finally { WorkspaceService(db).select(token,workspace) }
        setAccountTestRole(db,workspace,"viewer")
        assertEquals(workspace,LedgerReadService(db).snapshot(token).workspaceId)
    }
    @Test fun fullHistoryIncludesDraftsAndDeletionMarkersInsteadOfVisibleProjections()=fixture { db,workspace,token,account,fact ->
        db.write { c ->
            check(c.lockWorkspace(db.sqlite,workspace));c.markLedgerChanged(workspace)
            val sql=c.jooq(db.sqlite)
            sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.DELETED_AT,at).set(CASH_TRANSACTIONS.DELETED_BY,"fixture")
                .set(CASH_TRANSACTIONS.DELETE_REASON,"history").where(CASH_TRANSACTIONS.ID.eq(fact)).execute()
            val draft=CashSqlContext(c,workspace,db.sqlite).insertAtomicCash(account,at,CashAmount.parse(amount),"CNY","consumption","not_applicable","import").first
            sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.STATE,"draft").where(CASH_TRANSACTIONS.ID.eq(draft)).execute()
        }
        val facts=LedgerReadService(db).snapshot(token).facts
        assertEquals(2,facts.cashTransactions.size);assertEquals(2,facts.cashTransactionComponents.size)
        val deleted=facts.cashTransactions.single { it.id==fact.toString() }
        assertEquals(at,Instant.parse(deleted.deletedAt));assertEquals("history",deleted.deleteReason)
        assertEquals("draft",facts.cashTransactions.single { it.id!=fact.toString() }.state)
    }

    @Test fun snapshotCarriesNonemptySourceRelationIssueAndFundingFactsWithoutPrivateMetadata()=fixture { db,workspace,token,account,fact ->
        CategoryService(db).create(token,"Snapshot category")
        db.write { c ->
            check(c.lockWorkspace(db.sqlite,workspace))
            val context=CashSqlContext(c,workspace,db.sqlite)
            val sql=context.sql
            AccountAliasRepository(context).add("card_tail","1234",account)
            CashFundingMappingRepository(c,workspace,db.sqlite).publish(listOf(mapOf("source_type" to "alipay",
                "source_account_ref" to "snapshot-owner","account_id" to account.toString())),"admin")
            sql.update(ACCOUNTS).set(ACCOUNTS.METADATA_JSON,buildJsonObject { put("private_marker","must-not-leak") })
                .where(ACCOUNTS.ID.eq(account)).execute()
            sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.SOURCE_TYPE,"alipay").where(CASH_TRANSACTIONS.ID.eq(fact)).execute()
            val other=context.insertAtomicCash(account,at,CashAmount.parse(amount),"CNY","consumption","not_applicable","import",sourceType="alipay")
            val source=CashSourceWriteRepository(context)
            source.saveOccurrence(fact,SourceOccurrence("alipay","owner","a".repeat(64),"fixture-v1","table",1,
                emptyMap(),providerId="snapshot-provider"))
            source.saveIssue(fact,"identity","source_identity_ambiguous",candidates=mapOf(other.first to 1L))
            val coverage=CashSourceCoverage(null,null,false,"",emptyMap(),"coverage-v1",documentKey="f".repeat(64),pageTotal=2,
                pageFingerprints=mapOf("1" to "a".repeat(64),"2" to "b".repeat(64)))
            val document=ParsedCashDocument("alipay","table","fixture-v1",emptyList(),"owner","Synthetic snapshot",coverage,0,0)
            CashSourceDocumentWriteRepository(context).saveEmptyPages(listOf(ParsedImportFile("snapshot","a".repeat(64),"parsed",document,filename="snapshot.csv")))
            val refund=context.insertAtomicCash(account,at,CashAmount.parse("1.230000000000000001"),"CNY","refund","not_applicable","import",sourceType="alipay")
            val component=sql.select(CASH_TRANSACTION_COMPONENTS.ID).from(CASH_TRANSACTION_COMPONENTS)
                .where(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID.eq(fact)).fetchSingle(CASH_TRANSACTION_COMPONENTS.ID)!!
            val relations=CurrentCashRelationWriteRepository(context)
            val relation=relations.add(mapOf("kind" to "refund_offset","primary_transaction_id" to fact,"anchor_transaction_id" to fact,
                "primary_component_id" to component,"anchor_component_id" to component))
            relations.setCandidates(relation["id"],listOf(mapOf("candidate_transaction_id" to refund.first,
                "candidate_component_id" to refund.second,"expected_row_revision" to 1L)),1L)
            context.insertAtomicCash(account,at,CashAmount.parse("-10"),"CNY","transfer_out","ordinary_transfer","import")
            val security=deletionInsert(c,db,"accounts",linkedMapOf("workspace_id" to workspace,"name" to "Snapshot security",
                "type" to "security","active" to true,"revision" to 1L,"currencies" to JsonArray(listOf(JsonPrimitive("CNY"))),
                "metadata_json" to JsonObject(emptyMap()),"created_at" to at,"updated_at" to at))!!
            val e=INVESTMENT_EVENTS
            sql.insertInto(e).set(e.WORKSPACE_ID,workspace).set(e.ACCOUNT_ID,security).set(e.SOURCE_TYPE,"ibkr_csv")
                .set(e.RECORD_ID,"snapshot-funding").set(e.SOURCE_PAYLOAD,JsonNull).set(e.OCCURRED_AT,at)
                .set(e.RECORD_TYPE,"funding").set(e.RECORD_SUBTYPE,"external").set(e.CURRENCY,"CNY").set(e.NOTE,"")
                .set(e.FROM_TICKER,"").setNull(e.FROM_AMOUNT).set(e.TO_TICKER,"CNY").set(e.TO_AMOUNT,CashStoredDecimal("10"))
                .setNull(e.COMMISSION).set(e.COMMISSION_ASSET,"").set(e.PAYLOAD,JsonObject(emptyMap())).set(e.CREATED_AT,at).execute()
            assertEquals(1,FundingRelationRepository(context).scan().size)
        }
        val snapshot=LedgerReadService(db).snapshot(token)
        val f=snapshot.facts
        assertEquals(workspace,snapshot.workspaceId)
        assertEquals("1234",f.accountAliases.single().aliasValue)
        assertEquals(account.toString(),f.statementAccountMappings.single().accountId)
        assertTrue(f.cashCategories.any { it.name=="Snapshot category" });assertTrue(f.categoryDirectoryRevision.toLong()>0)
        assertTrue(f.cashSourceBindings.isNotEmpty());assertTrue(f.cashSourceBindings.all { it.transactionId==fact.toString() })
        assertEquals(2,f.cashSourceEmptyPages.size)
        assertEquals("refund_offset",f.transactionRelations.single().kind)
        assertEquals("1",f.cashRelationCandidates.single().expectedRowRevision)
        assertEquals("source_identity_ambiguous",f.cashTransactionIssues.single().code)
        assertEquals(1,f.cashIssueCandidates.size)
        assertTrue(f.cashEntries.isNotEmpty());assertTrue(f.cashEntryMembers.isNotEmpty())
        assertTrue(f.cashTransactionComponents.isNotEmpty());assertEquals(4,f.cashTransactions.size)
        assertEquals("pending_review",f.cashInvestmentFundingRelations.single().status)
        val encoded=Json.encodeToString(com.finance.tracker.business.contracts.LedgerSnapshotDto.serializer(),snapshot)
        assertFalse("private_marker" in encoded);assertFalse("must-not-leak" in encoded)
        assertFalse("investment_events" in encoded)
    }
}
