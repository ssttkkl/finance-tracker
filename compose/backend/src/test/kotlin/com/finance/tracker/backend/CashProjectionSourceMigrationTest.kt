package com.finance.tracker.backend

import java.sql.Connection
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashProjectionSourceMigrationTest {
    private val statements=mutableListOf<String>()
    private fun observed(c:Connection):Connection=java.lang.reflect.Proxy.newProxyInstance(Connection::class.java.classLoader,arrayOf(Connection::class.java)) {_,method,args ->
        if(method.name=="prepareStatement") statements.add((args!![0] as String).replace("\"", "").replace("`", "").lowercase(java.util.Locale.ROOT).replace(Regex("\\s+as\\s+"), " ").replace(Regex("\\s+"), " "))
        try {method.invoke(c,*(args ?: emptyArray()))} catch(f:java.lang.reflect.InvocationTargetException) {throw f.targetException}
    } as Connection
    private fun inWorkspace(action:(Database,String,Long,String)->Unit) {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"),::observed)
        val workspaces=WorkspaceService(db);val workspace=workspaces.create("synthetic-admin","投影源读取测试").activeWorkspaceId!!
        val account=deletionAccount(db,workspace);val category=CategoryService(db).create("synthetic-admin","源分类").id
        try {action(db,workspace,account,category)} finally {workspaces.delete("synthetic-admin","投影源读取测试");workspaces.select("synthetic-admin","fixture-a")}
    }
    private data class Fixture(val ids:List<Long>,val parts:Map<Long,Long>)
    private fun seed(c:Connection,db:Database,workspace:String,account:Long,count:Int=3):Fixture {
        val ids=(1..count).map {deletionFact(c,db,workspace,account,null)}
        val json=if(db.sqlite) "?" else "CAST(? AS json)"
        val parts=ids.associateWith {id ->c.prepareStatement("INSERT INTO cash_transaction_components(id,workspace_id,cash_transaction_id,account_id,amount,currency,ordinal,label,source_key,metadata_json,created_at) VALUES(?,?,?,?,?,'CNY',0,'','',$json,?) RETURNING id").use {
            it.setLong(1,Math.addExact(id,1_000_000_000_000L));it.setString(2,workspace);it.setLong(3,id);it.setLong(4,account);it.setBigDecimal(5,java.math.BigDecimal("-10"));it.setString(6,"{}");it.instant(7,deletionTime,db.sqlite);it.executeQuery().use {r ->assertTrue(r.next());r.getLong(1)}
        }}
        return Fixture(ids,parts)
    }
    private fun fund(c:Connection,db:Database,workspace:String,account:Long,part:Long,status:String="accepted",slot:String="active",relation:Long=part+3_000_000_000_000L):Long {
        val event=part+2_000_000_000_000L
        deletionInsert(c,db,"investment_events",linkedMapOf("id" to event,"workspace_id" to workspace,"account_id" to account,"record_id" to "",
            "occurred_at" to deletionTime,"record_type" to "funding","record_subtype" to "external","currency" to "CNY","note" to "","from_ticker" to "",
            "to_ticker" to "","to_amount" to java.math.BigDecimal("100"),"commission_asset" to "","payload" to JsonObject(emptyMap()),"created_at" to deletionTime))
        deletionInsert(c,db,"cash_investment_funding_relations",linkedMapOf("id" to relation,"workspace_id" to workspace,"cash_transaction_component_id" to part,
            "investment_event_id" to event,"direction" to "cash_to_investment","status" to status,"active_slot" to slot,"rule_id" to "fixture","evidence" to JsonObject(emptyMap()),
            "created_by" to "system","created_at" to deletionTime,"decided_by" to "","decision_reason" to ""))
        return relation
    }
    @Test fun fundingVersionsRetainDeletedAndDraftParentsWithoutOccupyingFormalGraphComponents()=inWorkspace {db,workspace,account,_ ->
        db.write {c ->val data=seed(c,db,workspace,account)
            c.prepareStatement("UPDATE cash_transactions SET deleted_at=? WHERE id=?").use {it.instant(1,deletionTime,db.sqlite);it.setLong(2,data.ids[1]);it.executeUpdate()}
            c.prepareStatement("UPDATE cash_transactions SET state='draft' WHERE id=?").use {it.setLong(1,data.ids[2]);it.executeUpdate()}
            val funding=data.ids.takeLast(2).map {fund(c,db,workspace,account,data.parts.getValue(it))}
            val context=CashRelationReadRepository(CashSqlContext(c,workspace,db.sqlite)).readContext(data.ids.toSet())
            assertEquals(data.ids.toSet(),context.transactionIds);assertEquals(data.ids,context.transactionRevisions.map {it.first})
            assertEquals(setOf(data.ids[0],data.ids[2]),context.activeTransactionIds)
            assertEquals(listOf(data.ids[0]),context.facts.map {it.id.storedCashId()});assertEquals(listOf(data.parts.getValue(data.ids[0]).toString()),context.components.map {it.componentId})
            assertEquals(data.parts.values.toSet(),context.componentVersions.map {it.id}.toSet());assertEquals(funding,context.fundingVersions.map {it.id})
            assertTrue(context.fundingComponentIds.isEmpty())
            assertEquals(1,buildCashRelationGraph(context.facts,context.components,context.relations,context.fundingComponentIds).projections.size)
        }
    }
    @Test fun parentAndComponentReadsKeepAllFiveHundredAndOneRowsWithBoundedQueries()=inWorkspace {db,workspace,account,_ ->
        db.write {c ->val data=seed(c,db,workspace,account,501);statements.clear()
            val context=CashRelationReadRepository(CashSqlContext(c,workspace,db.sqlite)).readContext(data.ids.toSet())
            assertEquals(data.ids.toSet(),context.transactionIds);assertEquals(data.parts.values.map {it.toString()}.toSet(),context.components.map {it.componentId}.toSet())
            assertEquals(501,context.componentVersions.size);assertTrue(context.fundingVersions.isEmpty())
            val parents=statements.filter {it.startsWith("select tx.id, tx.account_id, tx.occurred_at") && "from cash_transactions tx" in it}
            assertEquals(listOf(501,2),parents.map {it.count {ch ->ch=='?'}}, statements.joinToString("\n"))
            val parts=statements.filter {"from cash_transaction_components p left outer join cash_investment_funding_relations" in it}
            assertEquals(listOf(502,3),parts.map {it.count {ch ->ch=='?'}})
            assertTrue(statements.none {"source_payload" in it || "relation_metadata" in it})
        }
    }
    @Test fun storedOrdinalsPreserveFullDatabaseIntegerWidthInVersionsAndDigest()=inWorkspace {db,workspace,account,_ ->
        db.write {c ->val data=seed(c,db,workspace,account);val repo=CashProjectionSourceRepository(CashSqlContext(c,workspace,db.sqlite));val before=repo.sourceDigest()
            val ordinal=if(db.sqlite) 4_294_967_296L else Int.MAX_VALUE.toLong()
            c.prepareStatement("UPDATE cash_transaction_components SET ordinal=? WHERE id=?").use {it.setLong(1,ordinal);it.setLong(2,data.parts.getValue(data.ids[0]));it.executeUpdate()}
            val version=repo.relationContext(setOf(data.ids[0])).componentVersions.single()
            assertEquals(ordinal,version.ordinal);assertNotEquals(before,repo.sourceDigest())
        }
    }
    private fun mirror(c:Connection,db:Database,workspace:String,data:Fixture):Long {
        val id=deletionRelation(c,db,workspace,data.ids[0],data.ids[1])
        c.prepareStatement("UPDATE transaction_relations SET kind='payment_mirror',state='confirmed',primary_component_id=?,secondary_component_id=?,anchor_component_id=?,primary_role='expense',secondary_role='expense' WHERE id=?").use {
            it.setLong(1,data.parts.getValue(data.ids[0]));it.setLong(2,data.parts.getValue(data.ids[1]));it.setLong(3,data.parts.getValue(data.ids[0]));it.setLong(4,id);assertEquals(1,it.executeUpdate())
        };return id
    }
    @Test fun currentSourcesSeparateAttentionFromConfirmedClosureAndNeverExpandCandidates()=inWorkspace {db,workspace,account,_ ->
        db.write {c ->val data=seed(c,db,workspace,account);val accepted=mirror(c,db,workspace,data)
            val draft=deletionRelation(c,db,workspace,data.ids[1],data.ids[2]);deletionCandidate(c,db,workspace,accepted,data.ids[2])
            val repo=CashProjectionSourceRepository(CashSqlContext(c,workspace,db.sqlite));val source=repo.readSources()
            assertEquals(data.ids,source.facts.map {it.id.storedCashId()});assertEquals(listOf(false,true,true),source.facts.map {it.needsAttention});assertEquals(listOf(accepted.toBigInteger()),source.relations.map {it.id})
            assertEquals(data.ids.take(2).toSet(),repo.confirmedTransactionClosure(setOf(data.ids[0])));assertEquals(setOf(data.ids[2]),repo.confirmedTransactionClosure(setOf(data.ids[2])))
            val context=repo.relationContext(data.ids.take(2).toSet())
            assertEquals(setOf(accepted,draft),context.relationRevisions.map {it.first}.toSet());assertEquals(data.ids.take(2).toSet(),context.transactionIds)
            val build=buildCashProjections(source.facts,source.relations);assertEquals(2,build.projections.size);assertEquals(data.ids.toSet(),build.memberIds.map {it.storedCashId()}.toSet())
        }
    }
    @Test fun deletedConfirmedCounterpartAndPartialReadsRejectIncompleteGraph()=inWorkspace {db,workspace,account,_ ->
        db.write {c ->val data=seed(c,db,workspace,account);mirror(c,db,workspace,data);val repo=CashProjectionSourceRepository(CashSqlContext(c,workspace,db.sqlite))
            assertEquals("projection.incomplete",assertFailsWith<CashProjectionFailure> {repo.readSourcesForFacts(setOf(data.ids[0]))}.code)
            assertTrue(repo.readSourcesForFacts(emptySet()).facts.isEmpty())
            c.prepareStatement("UPDATE cash_transactions SET deleted_at=? WHERE workspace_id=? AND id=?").use {it.instant(1,deletionTime,db.sqlite);it.setString(2,workspace);it.setLong(3,data.ids[1]);it.executeUpdate()}
            assertEquals("projection.incomplete",assertFailsWith<CashProjectionFailure> {repo.readSources()}.code)
            assertEquals(0L,ProjectionStateRepository(c,workspace,db.sqlite).status().getValue("member_count").jsonPrimitive.long)
        }
    }
    @Test fun explicitComponentTranslationAndCurrentMembershipCloseTheWholeAffectedGroup()=inWorkspace {db,workspace,account,category ->
        db.write {c ->val data=seed(c,db,workspace,account);mirror(c,db,workspace,data);val repo=CashProjectionSourceRepository(CashSqlContext(c,workspace,db.sqlite))
            val firstPart=data.parts.getValue(data.ids[0]);assertTrue(firstPart !in data.ids)
            assertEquals(data.ids.take(2).toSet(),repo.confirmedTransactionClosure(setOf(firstPart),inputIsComponents=true))
            assertTrue(repo.confirmedTransactionClosure(setOf(firstPart)).isEmpty())
            val source=repo.readSourcesForFacts(data.ids.take(2).map {data.parts.getValue(it)}.toSet(),inputIsComponents=true);assertEquals(data.ids.take(2),source.facts.map {it.id.storedCashId()})
            val dataset=ProjectionStateRepository(c,workspace,db.sqlite).ready()!!.first
            val row=deletionProjection(c,db,workspace,dataset,data.ids[1],account,category,1)
            c.prepareStatement("UPDATE cash_projections SET member_count=2 WHERE id=?").use {it.setLong(1,row);it.executeUpdate()}
            for((ordinal,id) in data.ids.takeLast(2).withIndex()) deletionInsert(c,db,"cash_projection_members",linkedMapOf("workspace_id" to workspace,"dataset_id" to dataset,"projection_row_id" to row,"cash_transaction_id" to id,"roles_json" to JsonArray(listOf(JsonPrimitive(if(ordinal==0) "root" else "mirror"))),"ordinal" to ordinal))
            assertEquals(data.ids.toSet(),repo.affectedTransactionClosure(setOf(data.ids[0]),dataset))
        }
    }
    @Test fun digestTracksCanonicalSourceFieldsAndRejectsFloatingPayloadBeforeAnyPublish()=inWorkspace {db,workspace,account,_ ->
        db.write {c ->val data=seed(c,db,workspace,account);val repo=CashProjectionSourceRepository(CashSqlContext(c,workspace,db.sqlite));val initial=repo.sourceDigest()
            c.prepareStatement("UPDATE cash_transaction_components SET metadata_json="+(if(db.sqlite) "?" else "CAST(? AS json)")+" WHERE workspace_id=?").use {it.setString(1,"""{"ignored":true}""");it.setString(2,workspace);it.executeUpdate()};assertEquals(initial,repo.sourceDigest())
            for(sql in listOf("UPDATE cash_transactions SET note='changed' WHERE workspace_id=?","UPDATE cash_transaction_components SET label='changed' WHERE workspace_id=?")) {
                c.createStatement().use {it.execute("SAVEPOINT source_digest")}
                try {c.prepareStatement(sql).use {it.setString(1,workspace);it.executeUpdate()};assertNotEquals(initial,repo.sourceDigest())}
                finally {c.createStatement().use {it.execute("ROLLBACK TO SAVEPOINT source_digest");it.execute("RELEASE SAVEPOINT source_digest")}}
            }
            val json=if(db.sqlite) "?" else "CAST(? AS json)"
            c.prepareStatement("UPDATE cash_transactions SET source_payload=$json WHERE workspace_id=? AND id=?").use {it.setString(1,"""{"floating":1.0}""");it.setString(2,workspace);it.setLong(3,data.ids[0]);it.executeUpdate()}
            assertEquals("canonical.invalid_value",assertFailsWith<CashCanonicalFailure> {repo.sourceDigest()}.code)
        }
    }

    private fun contextSeed(c:Connection,db:Database,workspace:String,account:Long):Fixture {
        val data=seed(c,db,workspace,account,5)
        for((index,id) in data.ids.withIndex()) {
            val refund=index in 2..3;val amount=java.math.BigDecimal(if(refund) "100" else "-100")
            c.prepareStatement("UPDATE cash_transactions SET amount=?,record_type=? WHERE id=?").use {it.setBigDecimal(1,amount);it.setString(2,if(refund) "refund" else "consumption");it.setLong(3,id);it.executeUpdate()}
            c.prepareStatement("UPDATE cash_transaction_components SET amount=? WHERE id=?").use {it.setBigDecimal(1,amount);it.setLong(2,data.parts.getValue(id));it.executeUpdate()}
        };return data
    }
    private fun edge(c:Connection,db:Database,workspace:String,data:Fixture,a:Int,b:Int,kind:String="payment_mirror",state:String="draft",group:String?=null):Long {
        val id=deletionRelation(c,db,workspace,data.ids[a],data.ids[b])
        c.prepareStatement("UPDATE transaction_relations SET kind=?,state=?,decision_group_id=?,primary_component_id=?,secondary_component_id=?,anchor_component_id=?,primary_role=?,secondary_role=?,applied_amount=? WHERE id=?").use {
            it.setString(1,kind);it.setString(2,state);it.setString(3,group);it.setLong(4,data.parts.getValue(data.ids[a]));it.setLong(5,data.parts.getValue(data.ids[b]));it.setLong(6,data.parts.getValue(data.ids[a]));
            it.setString(7,if(a<2 || a==4) "expense" else "refund");it.setString(8,if(kind=="refund_offset" || b in 2..3) "refund" else "expense");it.setBigDecimal(9,java.math.BigDecimal("100"));it.setLong(10,id);it.executeUpdate()
        };return id
    }
    private fun joint(c:Connection,db:Database,workspace:String,data:Fixture):List<Long> {
        edge(c,db,workspace,data,0,2,"refund_offset","confirmed");edge(c,db,workspace,data,1,3,"refund_offset","confirmed")
        return listOf(edge(c,db,workspace,data,0,1,group="joint"),edge(c,db,workspace,data,2,3,group="joint"))
    }
    private fun newMirror(data:Fixture)=CashRelationGraphEdge(999_999_999L,"payment_mirror",data.ids[0].toString(),data.ids[1].toString(),data.parts.getValue(data.ids[0]).toString(),data.parts.getValue(data.ids[1]).toString(),"expense","expense")
    private fun relationCount(c:Connection,workspace:String,table:String):Long=c.prepareStatement("SELECT count(*) FROM $table WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {r ->r.next();r.getLong(1)}}
    private fun failure(code:String,action:()->Unit) {assertEquals(code,assertFailsWith<CashRelationFailure>(block=action).code)}
    @Test fun onlyConfirmedClosureAndExplicitCompleteGroupsExpandContext()=inWorkspace {db,workspace,account,_ ->
        db.write {c ->val data=contextSeed(c,db,workspace,account);joint(c,db,workspace,data);val service=CashRelationContextService(CashSqlContext(c,workspace,db.sqlite))
            val local=service.load(setOf(data.ids[0]));assertEquals(setOf(data.ids[0],data.ids[2]),local.transactionIds);assertTrue(local.jointMembers.isEmpty())
            val all=service.load(setOf(data.ids[0]),listOf("joint"));assertEquals(data.ids.take(4).toSet(),all.transactionIds);assertEquals(4,all.components.size);assertEquals(2,all.jointMembers.size)
            assertEquals(data.ids.take(4).map {data.parts.getValue(it).toString()}.toSet(),all.components.map {it.componentId}.toSet())
        }
    }
    @Test fun fullJointConfirmationIsPreparedWithoutWritingAndPartialOrDifferentActionsFail()=inWorkspace {db,workspace,account,_ ->
        db.write {c ->val data=contextSeed(c,db,workspace,account);val drafts=joint(c,db,workspace,data);val service=CashRelationContextService(CashSqlContext(c,workspace,db.sqlite))
            val context=service.load(setOf(data.ids[0]),listOf("joint"));val updates=context.relations.filter {it.id in drafts}.map {it.copy(state="confirmed")}
            val prepared=service.validate(context,updates=updates);assertEquals(context.transactionIds,prepared.affectedTransactionIds);assertEquals(1,prepared.build.projections.size);assertEquals(0,prepared.build.projections.single().netAmount!!.arithmeticValue().signum())
            c.prepareStatement("SELECT state FROM transaction_relations WHERE id IN (?,?) ORDER BY id").use {it.setLong(1,drafts[0]);it.setLong(2,drafts[1]);it.executeQuery().use {r ->repeat(2) {assertTrue(r.next());assertEquals("draft",r.getString(1))}}}
            assertEquals(0L,relationCount(c,workspace,"cash_projections"))
            for(groups in listOf(emptyList(),listOf("joint"))) {val partial=service.load(setOf(data.ids[0]),groups);val target=partial.relations.first {it.id==drafts[0]};failure("relation_group_incomplete") {service.validate(partial,updates=listOf(target.copy(state="confirmed")))}}
            for(state in listOf("draft","ignored")) failure("relation_group_incomplete") {service.validate(context,updates=listOf(updates[0],updates[1].copy(state=state)))}
        }
    }
    @Test fun parentRelationMembershipAndNewConfirmedNeighboursInvalidateContext() {
        for(changed in listOf("parent","member","membership","neighbour")) inWorkspace {db,workspace,account,_ ->db.write {c ->
            val data=contextSeed(c,db,workspace,account);val drafts=joint(c,db,workspace,data);val service=CashRelationContextService(CashSqlContext(c,workspace,db.sqlite));val before=service.load(setOf(data.ids[0]),listOf("joint"))
            when(changed) {
                "parent" ->c.prepareStatement("UPDATE cash_transactions SET note='changed',row_revision=row_revision+1 WHERE id=?").use {it.setLong(1,data.ids[0]);it.executeUpdate()}
                "member" ->c.prepareStatement("UPDATE transaction_relations SET decision_reason='changed',row_revision=row_revision+1 WHERE id=?").use {it.setLong(1,drafts[0]);it.executeUpdate()}
                "membership" ->c.prepareStatement("UPDATE transaction_relations SET active=false,row_revision=row_revision+1 WHERE id=?").use {it.setLong(1,drafts[0]);it.executeUpdate()}
                else ->edge(c,db,workspace,data,0,4,state="confirmed")
            }
            failure("relation_context_changed") {service.validate(before)}
        }}
    }
    @Test fun changesOutsideLocalContextRemainValidAndReadsNeverLoadSourceMetadata()=inWorkspace {db,workspace,account,_ ->db.write {c ->
        val data=contextSeed(c,db,workspace,account);val service=CashRelationContextService(CashSqlContext(c,workspace,db.sqlite));statements.clear();val before=service.load(setOf(data.ids[0]))
        assertTrue(statements.isNotEmpty());assertTrue(statements.none {"source_payload" in it || "relation_metadata" in it})
        c.prepareStatement("UPDATE cash_transactions SET note='outside',row_revision=row_revision+1 WHERE id=?").use {it.setLong(1,data.ids[4]);it.executeUpdate()}
        assertEquals(setOf(data.ids[0]),service.validate(before).affectedTransactionIds)
    }}
    @Test fun pendingRolesNeedSourceProofWhileExistingConfirmedRolesAndLegacyZeroAreRetained()=inWorkspace {db,workspace,account,_ ->db.write {c ->
        val data=contextSeed(c,db,workspace,account);c.prepareStatement("UPDATE cash_transactions SET record_type='refund' WHERE id=?").use {it.setLong(1,data.ids[0]);it.executeUpdate()}
        val draft=edge(c,db,workspace,data,0,1);val service=CashRelationContextService(CashSqlContext(c,workspace,db.sqlite));val before=service.load(data.ids.take(2).toSet());val update=before.relations.single().copy(state="confirmed")
        failure("relation_role_unproven") {service.validate(before,updates=listOf(update))}
        assertEquals(1,service.validate(before,updates=listOf(update),evidenceByRelation=mapOf(draft to CashRelationEvidence(primaryOriginalExpense=true))).build.projections.size)
        c.prepareStatement("UPDATE transaction_relations SET state='confirmed',row_revision=row_revision+1 WHERE id=?").use {it.setLong(1,draft);it.executeUpdate()}
        assertEquals(1,service.validate(service.load(setOf(data.ids[0]))).build.projections.size)
        c.prepareStatement("UPDATE transaction_relations SET active=false WHERE id=?").use {it.setLong(1,draft);it.executeUpdate()}
        val refund=edge(c,db,workspace,data,0,2,"refund_offset","confirmed")
        c.prepareStatement("UPDATE transaction_relations SET applied_amount=0 WHERE id=?").use {it.setLong(1,refund);it.executeUpdate()}
        val legacy=service.load(setOf(data.ids[0]));assertNull(legacy.relations.single().appliedAmount);assertEquals(0,service.validate(legacy).build.projections.single().netAmount!!.arithmeticValue().signum())
    }}
    @Test fun missingForeignAndUnknownGroupSeedsAreRejectedBeforeGraphPreparation()=inWorkspace {db,workspace,account,_ ->db.write {c ->
        val data=contextSeed(c,db,workspace,account);val service=CashRelationContextService(CashSqlContext(c,workspace,db.sqlite))
        for((ids,groups) in listOf(setOf(Long.MAX_VALUE) to emptyList(),setOf(data.ids[0]) to listOf("missing"),setOf(data.ids[0],1001L,Long.MAX_VALUE) to emptyList())) assertFailsWith<CashRelationFailure> {service.load(ids,groups)}
    }}
    @Test fun newEdgesRequireLoadedEndpointsAndMalformedEdgesFailBeforeRoleLookup()=inWorkspace {db,workspace,account,_ ->db.write {c ->
        val data=contextSeed(c,db,workspace,account);val service=CashRelationContextService(CashSqlContext(c,workspace,db.sqlite));val context=service.load(data.ids.take(2).toSet());val edge=newMirror(data)
        val prepared=service.validate(context,additions=listOf(edge));assertEquals(1,prepared.build.projections.size);assertEquals(0,java.math.BigDecimal("100").compareTo(cashRelationDecimal(prepared.relations.single().appliedAmount)!!.arithmeticValue()))
        assertEquals(0L,relationCount(c,workspace,"transaction_relations"))
        failure("relation_endpoint_incomplete") {service.validate(context,additions=listOf(edge.copy(secondaryTransactionId=data.ids[4].toString(),secondaryComponentId=data.parts.getValue(data.ids[4]).toString())))}
        assertFailsWith<CashRelationFailure> {service.validate(context,additions=listOf(edge.copy(primaryComponentId=emptyList<Any>())))}
        failure("relation_endpoint_incomplete") {service.validate(context,additions=listOf(edge.copy(state="draft",primaryTransactionId=Long.MAX_VALUE.toString(),secondaryTransactionId=null,primaryComponentId=null,secondaryComponentId=null,primaryRole=null,secondaryRole=null)))}
    }}
    @Test fun componentAndFundingChangesFenceContextWithoutChangingParentRevision()=inWorkspace {db,workspace,account,_ ->db.write {c ->
        val data=contextSeed(c,db,workspace,account);val service=CashRelationContextService(CashSqlContext(c,workspace,db.sqlite));val id=data.ids[0];val part=data.parts.getValue(id)
        c.prepareStatement("UPDATE cash_transactions SET cash_granularity='aggregate',account_id=NULL WHERE id=?").use {it.setLong(1,id);it.executeUpdate()}
        c.prepareStatement("UPDATE cash_transaction_components SET amount=-40 WHERE id=?").use {it.setLong(1,part);it.executeUpdate()}
        deletionInsert(c,db,"cash_transaction_components",linkedMapOf("id" to part+1_000_000L,"workspace_id" to workspace,"cash_transaction_id" to id,"account_id" to account,"amount" to java.math.BigDecimal("-60"),"currency" to "CNY","ordinal" to 1,"label" to "","source_key" to "","metadata_json" to JsonObject(emptyMap()),"created_at" to deletionTime))
        val before=service.load(setOf(id));val revision=before.transactionRevisions
        c.prepareStatement("UPDATE cash_transaction_components SET amount=? WHERE id=?").use {it.setBigDecimal(1,java.math.BigDecimal("-30"));it.setLong(2,part);it.executeUpdate();it.setBigDecimal(1,java.math.BigDecimal("-70"));it.setLong(2,part+1_000_000L);it.executeUpdate()}
        assertEquals(revision,service.load(setOf(id)).transactionRevisions);failure("relation_context_changed") {service.validate(before)}
        c.prepareStatement("UPDATE cash_transactions SET cash_granularity='atomic',account_id=?,record_type='investment_out' WHERE id=?").use {it.setLong(1,account);it.setLong(2,data.ids[4]);it.executeUpdate()}
        val unfunded=service.load(setOf(data.ids[4]));fund(c,db,workspace,account,data.parts.getValue(data.ids[4]));val fresh=service.load(setOf(data.ids[4]))
        assertEquals(unfunded.transactionRevisions,fresh.transactionRevisions);failure("relation_context_changed") {service.validate(unfunded)}
        assertEquals(setOf(data.parts.getValue(data.ids[4]).toString()),fresh.fundingComponentIds);assertEquals(0,service.validate(fresh).build.projections.single().netAmount!!.arithmeticValue().signum())
    }}
    @Test fun cleanupPreservesInactiveMembersAndRejectsPartialReactivationOrConfirmation() {
        for(action in listOf("defer","ignore","remove","partial","reactivate","confirm")) inWorkspace {db,workspace,account,_ ->db.write {c ->
            val data=contextSeed(c,db,workspace,account);val drafts=joint(c,db,workspace,data)
            c.prepareStatement("UPDATE transaction_relations SET active=false WHERE id=?").use {it.setLong(1,drafts[1]);it.executeUpdate()}
            val service=CashRelationContextService(CashSqlContext(c,workspace,db.sqlite));val context=service.load(setOf(data.ids[0]),listOf("joint"));val members=context.relations.filter {it.id in drafts}
            val updates=when(action) {
                "partial" ->listOf(members[0].copy(active=false))
                "reactivate" ->members.map {it.copy(active=true,state="ignored")}
                else ->members.map {if(it.active==false) it else when(action) {"remove" ->it.copy(active=false);"confirm" ->it.copy(state="confirmed");else ->it.copy(state=if(action=="defer") "draft" else "ignored")}}
            }
            if(action in setOf("partial","reactivate","confirm")) assertFailsWith<CashRelationFailure> {service.validate(context,updates=updates,cleanupGroupIds=setOf("joint"))}
            else {val prepared=service.validate(context,updates=updates,cleanupGroupIds=setOf("joint"));val inactive=members.single {it.active==false};assertEquals(inactive,prepared.relations.single {it.id==inactive.id})
                c.prepareStatement("SELECT state FROM transaction_relations WHERE id=?").use {it.setLong(1,drafts[0]);it.executeQuery().use {r ->assertTrue(r.next());assertEquals("draft",r.getString(1))}}
            }
        }}
    }
    @Test fun allUnfundedPartsAndActiveFundingSurviveOneJoinedReadWithEarliestFundingChosen()=inWorkspace {db,workspace,account,_ ->db.write {c ->
        val data=contextSeed(c,db,workspace,account);val id=data.ids[0];val first=data.parts.getValue(id);val second=first+1_000_000L;val third=first+2_000_000L
        c.prepareStatement("UPDATE cash_transactions SET account_id=NULL,cash_granularity='aggregate' WHERE id=?").use {it.setLong(1,id);it.executeUpdate()}
        c.prepareStatement("UPDATE cash_transaction_components SET amount=-20 WHERE id=?").use {it.setLong(1,first);it.executeUpdate()}
        for((part,amount,ordinal) in listOf(Triple(second,"-30",1),Triple(third,"-50",2))) deletionInsert(c,db,"cash_transaction_components",linkedMapOf("id" to part,"workspace_id" to workspace,"cash_transaction_id" to id,"account_id" to account,"amount" to java.math.BigDecimal(amount),"currency" to "CNY","ordinal" to ordinal,"label" to "","source_key" to "","metadata_json" to JsonObject(emptyMap()),"created_at" to deletionTime))
        val funding=listOf(fund(c,db,workspace,account,second,relation=9_000_000_000_001L),fund(c,db,workspace,account,first,relation=9_000_000_000_002L),fund(c,db,workspace,account,third,"rejected","old",9_000_000_000_003L))
        statements.clear();val context=CashRelationReadRepository(CashSqlContext(c,workspace,db.sqlite)).readContext(setOf(id))
        assertEquals(listOf(first,second,third).map {it.toString()},context.components.map {it.componentId});assertEquals(setOf(first.toString(),second.toString()),context.fundingComponentIds)
        assertEquals(funding.take(2),context.fundingVersions.map {it.id});assertEquals(funding.first(),context.facts.single().fundingRelationId);assertEquals(3,context.componentVersions.size)
        val reads=statements.filter {"from cash_transaction_components" in it || "from cash_investment_funding_relations" in it};assertEquals(1,reads.size);assertTrue("left outer join cash_investment_funding_relations" in reads.single())
    }}
    @Test fun twoParentsUseOneComponentFundingQueryAndTokenKeepsPythonAsciiFieldOrder()=inWorkspace {db,workspace,account,_ ->db.write {c ->
        val data=contextSeed(c,db,workspace,account);statements.clear();val context=CashRelationReadRepository(CashSqlContext(c,workspace,db.sqlite)).readContext(data.ids.take(2).toSet())
        assertEquals(data.ids.take(2).map {data.parts.getValue(it).toString()}.toSet(),context.components.map {it.componentId}.toSet())
        val reads=statements.filter {"from cash_transaction_components" in it || "from cash_investment_funding_relations" in it};assertEquals(1,reads.size);assertTrue("left outer join cash_investment_funding_relations" in reads.single())
        val fixed=context.copy(transactionRevisions=listOf(7L to 4L),componentVersions=listOf(CashComponentVersion(17,7,3,"-100.00","CNY",4_294_967_296L)),relationRevisions=listOf(27L to 2L),relationGroups=listOf(27L to "联合🦝"),
            fundingVersions=listOf(CashFundingSqlRow(37,17,47,"cash_to_investment")),issueVersions=listOf(CashIssueVersion(57,7,3)),entryVersions=listOf(CashEntrySnapshot(7,"entry-人",5,"digest")),jointMembers=listOf(CashJointRelationMember("27","联合🦝",2L,"draft",true)))
        assertEquals("c47df766e28d1804c44dfa83b48e06f4cf1a7c4413d2cbdbebfedf96b0d5aff4",fixed.token())
    }}
    @Test fun malformedContextChangesAreRejectedWithoutAnyWrites()=inWorkspace {db,workspace,account,_ ->db.write {c ->
        val data=contextSeed(c,db,workspace,account);val service=CashRelationContextService(CashSqlContext(c,workspace,db.sqlite));val before=service.load(data.ids.take(2).toSet());val edge=newMirror(data)
        failure("relation_context_changed") {service.validate(null)}
        failure("relation_data_invalid") {service.validate(before,updates=3)}
        failure("relation_data_invalid") {service.validate(before,updates=listOf(edge.copy(appliedAmount=1.0)),additions=null)}
        failure("relation_data_invalid") {service.validate(before,additions=listOf("bad"))}
        failure("relation_context_changed") {service.validate(before,updates=listOf(edge))}
        failure("relation_context_changed") {service.validate(before,additions=listOf(edge,edge))}
        failure("relation_data_invalid") {service.validate(before,additions=listOf(edge),evidenceByRelation=listOf("bad"))}
        failure("relation_data_invalid") {service.validate(before,additions=listOf(edge),evidenceByRelation=mapOf(123 to CashRelationEvidence()))}
        failure("relation_group_incomplete") {service.validate(before,cleanupGroupIds=listOf("missing"))}
        failure("relation_data_invalid") {service.load(setOf(0))}
        failure("relation_data_invalid") {service.load(setOf(data.ids[0]),listOf(""))}
        failure("relation_data_invalid") {service.load(setOf(data.ids[0]),listOf("x".repeat(129)))}
        failure("relation_data_invalid") {service.load(setOf(data.ids[0]),listOf("\uD800"))}
        assertEquals(0L,relationCount(c,workspace,"transaction_relations"));assertEquals(0L,relationCount(c,workspace,"cash_projections"))
    }}
    @Test fun evidenceDictionaryUsesPythonNumericKeysAndEmptyDynamicIterablesRemainValid()=inWorkspace {db,workspace,account,_ ->db.write {c ->
        val data=contextSeed(c,db,workspace,account)
        c.prepareStatement("UPDATE cash_transactions SET record_type='refund' WHERE id=?").use {it.setLong(1,data.ids[0]);it.executeUpdate()}
        val service=CashRelationContextService(CashSqlContext(c,workspace,db.sqlite));val context=service.load(data.ids.take(2).toSet());val edge=newMirror(data).copy(id=9L)
        for(key in listOf<Any>(9,9L,9.toBigInteger(),9.0,9.0f,java.math.BigDecimal("9.000"))) {
            val prepared=service.validate(context,additions=listOf(edge),evidenceByRelation=mapOf(key to CashRelationEvidence(primaryOriginalExpense=true)))
            assertEquals(1,prepared.build.projections.size)
        }
        assertEquals(1,service.validate(context,additions=listOf(edge.copy(id=1L)),evidenceByRelation=mapOf(true to CashRelationEvidence(primaryOriginalExpense=true))).build.projections.size)
        for(key in listOf<Any>("9",9.25,Double.NaN,Double.POSITIVE_INFINITY,java.math.BigDecimal("9.0001"))) failure("relation_data_invalid") {service.validate(context,additions=listOf(edge),evidenceByRelation=mapOf(key to CashRelationEvidence()))}
        for(empty in listOf<Any>("",emptyMap<String,Any>())) {
            assertEquals(context.transactionIds,service.validate(context,updates=empty).affectedTransactionIds)
            assertEquals(context.transactionIds,service.validate(context,additions=empty).affectedTransactionIds)
            assertEquals(context.transactionIds,service.validate(context,cleanupGroupIds=empty).affectedTransactionIds)
        }
    }}
    @Test fun proofDictionaryCollapsesNumericAliasesUsingTheLastInsertedValue()=inWorkspace {db,workspace,account,_ ->db.write {c ->
        val data=contextSeed(c,db,workspace,account)
        c.prepareStatement("UPDATE cash_transactions SET record_type='refund' WHERE id=?").use {it.setLong(1,data.ids[0]);it.executeUpdate()}
        val service=CashRelationContextService(CashSqlContext(c,workspace,db.sqlite));val context=service.load(data.ids.take(2).toSet());val edge=newMirror(data).copy(id=9L)
        for(alias in listOf<Any>(9.0,9.0f,java.math.BigDecimal("9.000"))) {
            val deny=linkedMapOf<Any,Any>(9L to CashRelationEvidence(primaryOriginalExpense=true),alias to CashRelationEvidence())
            failure("relation_role_unproven") {service.validate(context,additions=listOf(edge),evidenceByRelation=deny)}
            val allow=linkedMapOf<Any,Any>(9L to CashRelationEvidence(),alias to CashRelationEvidence(primaryOriginalExpense=true))
            assertEquals(1,service.validate(context,additions=listOf(edge),evidenceByRelation=allow).build.projections.size)
        }
    }}
}
