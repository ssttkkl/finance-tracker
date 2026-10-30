package com.finance.tracker.backend

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.math.BigInteger
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashProjectionDatasetMigrationTest {
    private data class SqlCall(val sql:String,val parameters:Map<Int,Any?>,val batchSize:Int?)
    private val calls=mutableListOf<SqlCall>()
    private var omitParentLookup=false
    private var publicationFailureReached=false
    private fun String.parentLookup() = observedSql().replace("cash_projections.", "")
        .startsWith("select id,projection_id from cash_projections")
    private fun observe(c:Connection):Connection {
        fun invoke(target:Any,method:java.lang.reflect.Method,args:Array<out Any?>?):Any?=try {method.invoke(target,*(args ?: emptyArray()))} catch(f:InvocationTargetException) {throw f.targetException}
        return Proxy.newProxyInstance(Connection::class.java.classLoader,arrayOf(Connection::class.java)) {_,method,args ->
            val sql=args?.firstOrNull() as? String
            val actual=if(omitParentLookup && sql?.parentLookup()==true) args.mapIndexed {index,value ->if(index==0) "$sql AND projection_id='missing'" else value}.toTypedArray() else args
            val result=invoke(c,method,actual)
            if(result is PreparedStatement && sql!=null) {
                val bound=linkedMapOf<Int,Any?>();var batch=0
                Proxy.newProxyInstance(PreparedStatement::class.java.classLoader,arrayOf(PreparedStatement::class.java)) {_,operation,values ->
                    if(operation.name.startsWith("set") && values?.size==2 && values[0] is Int) bound[values[0] as Int]=if(operation.name=="setNull") null else values[1]
                    if(operation.name=="addBatch") batch++
                    if(operation.name in setOf("execute","executeQuery","executeUpdate","executeBatch","executeLargeBatch")) {
                        calls+=SqlCall(sql.observedSql(),bound.toMap(),if(operation.name.endsWith("Batch")) batch else null);if(operation.name.endsWith("Batch")) batch=0
                    }
                    try {invoke(result,operation,values)} catch(f:java.sql.SQLException) {
                        if(f.message.orEmpty().contains("synthetic publication failure")) publicationFailureReached=true
                        throw f
                    }
                }
            } else result
        } as Connection
    }
    private fun inWorkspace(action:(Database,String,Long)->Unit) {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"),::observe)
        val service=WorkspaceService(db);val name="投影数据集测试";val workspace=service.create("synthetic-admin",name).activeWorkspaceId!!;val account=deletionAccount(db,workspace)
        try {action(db,workspace,account)} finally {omitParentLookup=false;service.delete("synthetic-admin",name);service.select("synthetic-admin","fixture-a")}
    }
    private fun seed(c:Connection,db:Database,workspace:String,account:Long,count:Int=3)=List(count) {deletionFact(c,db,workspace,account,null)}
    private fun count(c:Connection,table:String,workspace:String,dataset:String?=null):Long=c.prepareStatement("SELECT count(*) FROM $table WHERE workspace_id=?"+(if(dataset==null) "" else " AND dataset_id=?")).use {
        it.setString(1,workspace);if(dataset!=null) it.setString(2,dataset);it.executeQuery().use {r ->assertTrue(r.next());r.getLong(1)}
    }
    private fun incomplete(action:()->Unit) {assertEquals("projection.incomplete",assertFailsWith<CashProjectionFailure>(block=action).code)}
    private fun rebuild(c:Connection,db:Database,workspace:String):JsonObject {
        val repository=CashProjectionRepository(CashSqlContext(c,workspace,db.sqlite),now={deletionTime})
        val proof=repository.readValidatedSources();val dataset=repository.createStagingDataset(proof.digest,CASH_PROJECTION_RULES_VERSION)
        repository.replaceDataset(dataset,buildCashProjections(proof.facts,proof.relations),Math.addExact(repository.status().getValue("projection_version").jsonPrimitive.long,1))
        return repository.publishDataset(dataset,proof.digest,CASH_PROJECTION_RULES_VERSION,proof)
    }
    private fun snapshot(db:Database,workspace:String)=db.read {c ->
        listOf("cash_transactions","cash_transaction_components","transaction_relations","cash_relation_candidates","cash_entries","cash_entry_members",
            "cash_projection_datasets","cash_projection_states","cash_projections","cash_projection_members","cash_projection_relations").associateWith {table ->
            c.prepareStatement("SELECT * FROM $table WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {r ->
                val columns=r.metaData.columnCount;buildList {while(r.next()) add(JsonArray((1..columns).map {index ->r.getString(index)?.let(::JsonPrimitive) ?: JsonNull}).toString())}.sorted()
            }}
        }
    }
    @Test fun virtualProjectionIdentityIsRejectedBeforeDatasetWrites()=inWorkspace {db,workspace,account ->
        db.write {c ->
            seed(c,db,workspace,account,1)
            val repository=CashProjectionRepository(CashSqlContext(c,workspace,db.sqlite),now={deletionTime})
            val proof=repository.readValidatedSources()
            val dataset=repository.createStagingDataset(proof.digest,CASH_PROJECTION_RULES_VERSION)
            val virtual=CashProjectionFact.create(BigInteger("24197857200151252747416209503149608431"),account,
                deletionTime,"-100","CNY","",null,"","fixture","virtual")
            val before=count(c,"cash_projections",workspace,dataset) to count(c,"cash_projection_members",workspace,dataset)
            val error=assertFailsWith<CashProjectionDomainFailure> {
                repository.replaceDataset(dataset,buildCashProjections(listOf(virtual),emptyList()),1L)
            }
            assertEquals("projection.invalid_fact",error.code)
            assertEquals(before,count(c,"cash_projections",workspace,dataset) to count(c,"cash_projection_members",workspace,dataset))
        }
    }
    @Test fun emptyAndRepeatedPublicationReplaceOnlyTheActiveDatasetAndPreserveStableEntryIdentity()=inWorkspace {db,workspace,account ->
        db.write {c ->for(table in listOf("cash_projection_states","cash_projection_datasets")) c.prepareStatement("DELETE FROM $table WHERE workspace_id=?").use {it.setString(1,workspace);it.executeUpdate()}}
        val missing=snapshot(db,workspace);db.read {c ->assertEquals("uninitialized",CashProjectionRepository(CashSqlContext(c,workspace,db.sqlite)).status().getValue("availability").jsonPrimitive.content)};assertEquals(missing,snapshot(db,workspace))
        val empty=db.write {rebuild(it,db,workspace)};assertEquals(1L,empty.getValue("projection_version").jsonPrimitive.long);assertEquals(0L,empty.getValue("member_count").jsonPrimitive.long)
        db.write {c ->seed(c,db,workspace,account)}
        val first=db.write {rebuild(it,db,workspace)};val entries=snapshot(db,workspace).getValue("cash_entries")
        val second=db.write {rebuild(it,db,workspace)}
        assertEquals(2L,first.getValue("projection_version").jsonPrimitive.long);assertEquals(3L,second.getValue("projection_version").jsonPrimitive.long)
        for(result in listOf(first,second)) {assertEquals(3L,result.getValue("projection_count").jsonPrimitive.long);assertEquals(3L,result.getValue("member_count").jsonPrimitive.long);assertEquals("succeeded",result.getValue("last_build_status").jsonPrimitive.content)}
        assertNotEquals(first.getValue("active_dataset_id"),second.getValue("active_dataset_id"));assertEquals(entries,snapshot(db,workspace).getValue("cash_entries"))
        db.read {c ->assertEquals(1L,count(c,"cash_projection_datasets",workspace));assertEquals(3L,count(c,"cash_projections",workspace));assertEquals(3L,count(c,"cash_projection_members",workspace))}
    }
    @Test fun changedSourcesMissingMembersAndActiveDatasetReuseRejectWithoutChangingAnyFinancialRow() {
        for(mode in listOf("source","member","active","replace-active","missing")) inWorkspace {db,workspace,account ->
            db.write {c ->seed(c,db,workspace,account);rebuild(c,db,workspace)};val before=snapshot(db,workspace)
            val failure=assertFailsWith<CashProjectionFailure> {db.write {c ->
                val repository=CashProjectionRepository(CashSqlContext(c,workspace,db.sqlite));val proof=repository.readValidatedSources();val build=buildCashProjections(proof.facts,proof.relations)
                val active=repository.status().getValue("active_dataset_id").jsonPrimitive.content
                if(mode=="replace-active") {repository.replaceDataset(active,build,3);fail("active dataset was replaced")}
                val dataset=if(mode=="active") active else if(mode=="missing") "absent-$workspace" else repository.createStagingDataset(proof.digest,CASH_PROJECTION_RULES_VERSION)
                if(mode in setOf("source","member")) repository.replaceDataset(dataset,build,3)
                if(mode=="source") c.prepareStatement("UPDATE cash_transactions SET counterparty='changed' WHERE workspace_id=?").use {it.setString(1,workspace);it.executeUpdate()}
                if(mode=="member") c.prepareStatement("DELETE FROM cash_projection_members WHERE workspace_id=? AND dataset_id=?").use {it.setString(1,workspace);it.setString(2,dataset);it.executeUpdate()}
                repository.publishDataset(dataset,proof.digest,CASH_PROJECTION_RULES_VERSION,proof)
            }}
            assertEquals(if(mode=="source") "projection.concurrent_update" else "projection.incomplete",failure.code,mode);assertEquals(before,snapshot(db,workspace),mode)
        }
    }
    @Test fun latePointerAndRetiredDatasetDeleteFailuresRollBackEveryDerivedAndStableEntryColumn() {
        for(mode in listOf("pointer","delete")) inWorkspace {db,workspace,account ->
            db.write {c ->seed(c,db,workspace,account);rebuild(c,db,workspace)}
            val active=db.read {ProjectionStateRepository(it,workspace,db.sqlite).status().getValue("active_dataset_id").jsonPrimitive.content}
            db.write {c ->
                c.prepareStatement("UPDATE cash_entries SET note='人工备注',manual_fields="+(if(db.sqlite) "?" else "CAST(? AS json)")+" WHERE workspace_id=?").use {it.setString(1,"[\"note\"]");it.setString(2,workspace);it.executeUpdate()}
                c.prepareStatement("UPDATE cash_transactions SET counterparty='来源已变化',row_revision=row_revision+1 WHERE workspace_id=?").use {it.setString(1,workspace);it.executeUpdate()}
                c.prepareStatement("UPDATE cash_projection_states SET source_revision=source_revision+1 WHERE workspace_id=?").use {it.setString(1,workspace);it.executeUpdate()}
            }
            val trigger="ft_publish_"+java.util.UUID.randomUUID().toString().replace("-","");val table=if(mode=="pointer") "cash_projection_states" else "cash_projection_datasets"
            check(workspace.matches(Regex("[A-Za-z0-9-]+")));check(active.matches(Regex("[A-Za-z0-9-]+")))
            val action=if(mode=="pointer") "UPDATE OF active_dataset_id" else "DELETE"
            val condition=if(mode=="pointer") "NEW.workspace_id='$workspace'" else "OLD.workspace_id='$workspace' AND OLD.id='$active'"
            db.write {c ->c.createStatement().use {
                if(db.sqlite) it.execute("CREATE TRIGGER $trigger BEFORE $action ON $table WHEN $condition BEGIN SELECT RAISE(ABORT,'synthetic publication failure'); END")
                else {it.execute("CREATE FUNCTION $trigger() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''synthetic publication failure''; RETURN NULL; END'")
                    it.execute("CREATE TRIGGER $trigger BEFORE $action ON $table FOR EACH ROW WHEN ($condition) EXECUTE FUNCTION $trigger()")}
            }}
            try {val before=snapshot(db,workspace);publicationFailureReached=false
                assertFailsWith<StorageFailure> {db.write {rebuild(it,db,workspace)}}
                assertTrue(publicationFailureReached,"must reach late $mode database failure");assertEquals(before,snapshot(db,workspace),mode)
            } finally {db.write {c ->c.createStatement().use {it.execute("DROP TRIGGER $trigger"+if(db.sqlite) "" else " ON $table");if(!db.sqlite) it.execute("DROP FUNCTION $trigger()")}}}
            val status=db.write {rebuild(it,db,workspace)};assertEquals(3L,status.getValue("projection_version").jsonPrimitive.long)
            db.read {c ->c.prepareStatement("SELECT note,counterparty FROM cash_entries WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {r ->repeat(3) {assertTrue(r.next());assertEquals("人工备注",r.getString(1));assertEquals("来源已变化",r.getString(2))}}}}
        }
    }
    @Test fun failureDiagnosticsRespectBothActivePointerAndSourceRevisionFences()=inWorkspace {db,workspace,account ->
        db.write {c ->seed(c,db,workspace,account);rebuild(c,db,workspace)}
        val expected=db.write {CashProjectionRepository(CashSqlContext(it,workspace,db.sqlite)).rebuildFailureContext()};val before=snapshot(db,workspace)
        for(stale in listOf(expected.copy(activeDatasetId="other"),expected.copy(sourceRevision=expected.sourceRevision+1))) {
            assertFalse(db.write {CashProjectionRepository(CashSqlContext(it,workspace,db.sqlite)).recordFailedBuild(stale,"projection.failed")});assertEquals(before,snapshot(db,workspace))
        }
        assertTrue(db.write {CashProjectionRepository(CashSqlContext(it,workspace,db.sqlite),now={deletionTime}).recordFailedBuild(expected,"projection.incomplete")})
        val failed=snapshot(db,workspace);for(table in before.keys-"cash_projection_states") assertEquals(before[table],failed[table],table)
        db.read {c ->val status=ProjectionStateRepository(c,workspace,db.sqlite).status();assertEquals("failed",status.getValue("last_build_status").jsonPrimitive.content);assertEquals("projection.incomplete",status.getValue("last_error_code").jsonPrimitive.content);assertEquals("收支投影构建失败",status.getValue("last_error_summary").jsonPrimitive.content);assertEquals(JsonPrimitive(expected.activeDatasetId),status.getValue("active_dataset_id"));assertEquals(2L,status.getValue("projection_version").jsonPrimitive.long)}
        db.write {c ->c.prepareStatement("UPDATE cash_projection_states SET source_revision=source_revision+1 WHERE workspace_id=?").use {it.setString(1,workspace);it.executeUpdate()}}
        val changed=snapshot(db,workspace);assertFalse(db.write {CashProjectionRepository(CashSqlContext(it,workspace,db.sqlite)).recordFailedBuild(expected,"projection.failed")});assertEquals(changed,snapshot(db,workspace))
    }
    @Test fun datasetReplacementIsIdempotentAndPublicationKeepsEachCashMemberUnique()=inWorkspace {db,workspace,account ->
        db.write {c ->seed(c,db,workspace,account);val repository=CashProjectionRepository(CashSqlContext(c,workspace,db.sqlite),now={deletionTime})
            val proof=repository.readValidatedSources();val build=buildCashProjections(proof.facts,proof.relations);val dataset=repository.createStagingDataset(proof.digest,CASH_PROJECTION_RULES_VERSION)
            repository.replaceDataset(dataset,build,2);repository.replaceDataset(dataset,build,2)
            assertEquals(3L,count(c,"cash_projection_members",workspace,dataset))
            val status=repository.publishDataset(dataset,proof.digest,CASH_PROJECTION_RULES_VERSION)
            assertEquals("ready",status.getValue("availability").jsonPrimitive.content);assertEquals(3L,status.getValue("projection_count").jsonPrimitive.long);assertEquals(3L,status.getValue("member_count").jsonPrimitive.long)
            c.prepareStatement("SELECT cash_transaction_id,count(*) FROM cash_projection_members WHERE workspace_id=? GROUP BY cash_transaction_id").use {it.setString(1,workspace);it.executeQuery().use {r ->repeat(3) {assertTrue(r.next());assertEquals(1L,r.getLong(2))};assertFalse(r.next())}}
        }
    }
    @Test fun oneElementLookupBatchesRemainRestrictedAndMergeEveryParentId()=inWorkspace {db,workspace,account ->
        db.write {c ->seed(c,db,workspace,account);val repository=CashProjectionRepository(CashSqlContext(c,workspace,db.sqlite),writeBatchSize=1)
            val proof=repository.readValidatedSources();val build=buildCashProjections(proof.facts,proof.relations);val dataset=repository.createStagingDataset(proof.digest,CASH_PROJECTION_RULES_VERSION);calls.clear()
            repository.replaceDataset(dataset,build,2)
            val lookup=calls.filter {it.sql.parentLookup()};assertEquals(build.projections.size,lookup.size)
            assertTrue(lookup.all {"workspace_id=?" in it.sql && "dataset_id=?" in it.sql && "projection_id in" in it.sql && it.parameters[1]==workspace && it.parameters[2]==dataset})
            assertEquals(build.projections.map {it.projectionId}.toSet(),lookup.flatMap {it.parameters.filterKeys {key ->key>=3}.values}.toSet())
            c.prepareStatement("SELECT id,projection_id FROM cash_projections WHERE workspace_id=? AND dataset_id=?").use {it.setString(1,workspace);it.setString(2,dataset);it.executeQuery().use {r ->val parents=mutableMapOf<String,Long>();while(r.next()) parents[r.getString(2)]=r.getLong(1)
                assertEquals(build.projections.map {it.projectionId}.toSet(),parents.keys)
                c.prepareStatement("SELECT projection_row_id FROM cash_projection_members WHERE workspace_id=? AND dataset_id=?").use {s ->s.setString(1,workspace);s.setString(2,dataset);s.executeQuery().use {members ->val ids=mutableSetOf<Long>();while(members.next()) ids+=members.getLong(1);assertEquals(parents.values.toSet(),ids)}}
            }}
        }
    }
    @Test fun bulkParentMappingPreservesCompleteRolesOrdinalsRelationsAndPublicEntryValues()=inWorkspace {db,workspace,account ->
        val category=CategoryService(db).create("synthetic-admin","路径示例").id
        db.write {c ->val ids=seed(c,db,workspace,account,4)
            c.prepareStatement("UPDATE cash_transactions SET category_id=? WHERE workspace_id=?").use {it.setString(1,category);it.setString(2,workspace);it.executeUpdate()}
            for(id in ids) deletionInsert(c,db,"cash_transaction_components",linkedMapOf("id" to id+1_000_000_000_000L,"workspace_id" to workspace,"cash_transaction_id" to id,"account_id" to account,"amount" to java.math.BigDecimal("-10"),"currency" to "CNY","ordinal" to 0,"label" to "","source_key" to "","metadata_json" to JsonObject(emptyMap()),"created_at" to deletionTime))
            val relation=deletionRelation(c,db,workspace,ids[2],ids[3])
            c.prepareStatement("UPDATE transaction_relations SET kind='payment_mirror',state='confirmed',primary_component_id=?,secondary_component_id=?,anchor_component_id=?,primary_role='expense',secondary_role='expense' WHERE id=?").use {
                it.setLong(1,ids[2]+1_000_000_000_000L);it.setLong(2,ids[3]+1_000_000_000_000L);it.setLong(3,ids[2]+1_000_000_000_000L);it.setLong(4,relation);it.executeUpdate()
            }
            deletionEntry(c,db,workspace,"existing-public-$workspace",ids[2],category,JsonArray(listOf(JsonPrimitive("note"))))
            deletionInsert(c,db,"cash_entry_members",linkedMapOf("workspace_id" to workspace,"transaction_id" to ids[2],"entry_id" to "existing-public-$workspace","standalone_values" to buildJsonObject {put("note","恢复备注")},"standalone_manual_fields" to JsonArray(listOf(JsonPrimitive("note")))))
            val context=CashSqlContext(c,workspace,db.sqlite);val repository=CashProjectionRepository(context,now={deletionTime});val proof=repository.readValidatedSources();val build=buildCashProjections(proof.facts,proof.relations)
            val projection=build.projections.single {it.relations.isNotEmpty()};val dataset=repository.createStagingDataset(proof.digest,CASH_PROJECTION_RULES_VERSION);calls.clear();repository.replaceDataset(dataset,build,7)
            assertTrue(calls.any {it.sql.startsWith("insert into cash_projections (") || it.sql.startsWith("insert into cash_projections(")});val lookups=calls.filter {it.sql.parentLookup()}
            assertTrue(lookups.any {it.parameters[1]==workspace && it.parameters[2]==dataset && projection.projectionId in it.parameters.values})
            c.prepareStatement("SELECT id,entry_id,note,category_id,category_path,built_projection_version FROM cash_projections WHERE workspace_id=? AND dataset_id=? AND projection_id=?").use {s ->s.setString(1,workspace);s.setString(2,dataset);s.setString(3,projection.projectionId);s.executeQuery().use {r ->
                assertTrue(r.next());val parent=r.getLong(1);assertEquals("existing-public-$workspace",r.getString(2));assertEquals("保留备注",r.getString(3));assertEquals(category,r.getString(4));assertEquals("/$category/",r.getString(5));assertEquals(7L,r.getLong(6));assertFalse(r.next())
                c.prepareStatement("SELECT cash_transaction_id,roles_json,ordinal FROM cash_projection_members WHERE workspace_id=? AND dataset_id=? AND projection_row_id=? ORDER BY ordinal").use {m ->m.setString(1,workspace);m.setString(2,dataset);m.setLong(3,parent);m.executeQuery().use {members ->
                    for((ordinal,pair) in projection.members.withIndex()) {assertTrue(members.next());assertEquals(pair.first.id.storedCashId(),members.getLong(1));assertEquals(JsonArray(pair.second.map(::JsonPrimitive)),Json.parseToJsonElement(members.getString(2)));assertEquals(ordinal,members.getInt(3))};assertFalse(members.next())
                }}
                c.prepareStatement("SELECT transaction_relation_id,kind,subtype,ordinal FROM cash_projection_relations WHERE workspace_id=? AND dataset_id=? AND projection_row_id=? ORDER BY ordinal").use {rel ->rel.setString(1,workspace);rel.setString(2,dataset);rel.setLong(3,parent);rel.executeQuery().use {relations ->
                    for((ordinal,expected) in projection.relations.withIndex()) {assertTrue(relations.next());assertEquals(expected.id.longValueExact(),relations.getLong(1));assertEquals(expected.kind,relations.getString(2));assertEquals(expected.subtype,relations.getString(3));assertEquals(ordinal,relations.getInt(4))};assertFalse(relations.next())
                }}
            }}
            c.prepareStatement("SELECT note FROM cash_transactions WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {r ->repeat(4) {assertTrue(r.next());assertEquals("",r.getString(1))}}}
        }
    }
    @Test fun realNineHundredAndOneRowsUseNineHundredParentMemberAndLookupBatches()=inWorkspace {db,workspace,account ->
        db.write {c ->val ids=seed(c,db,workspace,account,901);val repository=CashProjectionRepository(CashSqlContext(c,workspace,db.sqlite));assertEquals(900,repository.writeBatchSize)
            val proof=repository.readValidatedSources();val build=CashProjectionBuild(proof.facts.map {fact ->CashProjection("cash:${fact.id}",fact,fact.amount,CashEconomicType.EXPENSE,true,null,null,listOf(fact.id),listOf(fact to listOf("primary")),emptyList(),emptyList())})
            val dataset=repository.createStagingDataset(proof.digest,CASH_PROJECTION_RULES_VERSION);calls.clear();repository.replaceDataset(dataset,build,2)
            for(table in listOf("cash_projections","cash_projection_members")) assertEquals(listOf(900,1),calls.filter {it.sql.startsWith("insert into $table (") || it.sql.startsWith("insert into $table(")}.map {it.batchSize},table)
            val lookups=calls.filter {it.sql.parentLookup()};assertEquals(2,lookups.size);assertTrue(lookups.all {it.parameters[1]==workspace && it.parameters[2]==dataset})
            assertEquals(ids.map {"cash:$it"}.toSet(),lookups.flatMap {it.parameters.filterKeys {key ->key>=3}.values}.toSet());assertEquals(901L,count(c,"cash_projection_members",workspace,dataset))
        }
    }
    @Test fun missingParentMappingRollsBackEntriesStagingRowsAndCurrentPointer()=inWorkspace {db,workspace,account ->
        db.write {c ->seed(c,db,workspace,account)}
        val before=db.read {ProjectionStateRepository(it,workspace,db.sqlite).status()}
        incomplete {db.write {c ->val repository=CashProjectionRepository(CashSqlContext(c,workspace,db.sqlite));val proof=repository.readValidatedSources();val dataset=repository.createStagingDataset(proof.digest,CASH_PROJECTION_RULES_VERSION)
            omitParentLookup=true;repository.replaceDataset(dataset,buildCashProjections(proof.facts,proof.relations),2)
        }}
        omitParentLookup=false
        db.read {c ->assertEquals(before,ProjectionStateRepository(c,workspace,db.sqlite).status());assertEquals(0L,count(c,"cash_entries",workspace));assertEquals(1L,count(c,"cash_projection_datasets",workspace))}
    }
    @Test fun sourceProofFromAnotherRepositoryOldTransactionOrWrongCountCannotPublish() {
        for(invalid in listOf("repository","transaction","count")) inWorkspace {db,workspace,account ->
            db.write {c ->seed(c,db,workspace,account)};val before=db.read {ProjectionStateRepository(it,workspace,db.sqlite).status()}
            incomplete {db.write {c ->val context=CashSqlContext(c,workspace,db.sqlite);val repository=CashProjectionRepository(context);val proof=repository.readValidatedSources();val dataset=repository.createStagingDataset(proof.digest,CASH_PROJECTION_RULES_VERSION)
                repository.replaceDataset(dataset,buildCashProjections(proof.facts,proof.relations),2)
                val supplied=if(invalid=="count") CashValidatedProjectionSources(repository.sources,context.requireTransactionStamp(),proof.digest,CashProjectionSources(proof.facts.dropLast(1),proof.relations)) else proof
                val publisher=if(invalid=="repository") CashProjectionRepository(context) else repository
                if(invalid=="transaction") {if(db.sqlite) c.createStatement().use {it.execute("COMMIT");it.execute("BEGIN IMMEDIATE")} else c.commit()}
                publisher.publishDataset(dataset,proof.digest,CASH_PROJECTION_RULES_VERSION,supplied)
            }}
            db.read {c ->val after=ProjectionStateRepository(c,workspace,db.sqlite).status()
                if(invalid=="transaction") {
                    assertEquals(before.getValue("active_dataset_id"),after.getValue("active_dataset_id"));assertEquals(before.getValue("projection_version"),after.getValue("projection_version"))
                    assertEquals("running",after.getValue("last_build_status").jsonPrimitive.content);assertEquals(3L,count(c,"cash_projections",workspace))
                } else {assertEquals(before,after);assertEquals(0L,count(c,"cash_projections",workspace))}
            }
        }
    }
}
