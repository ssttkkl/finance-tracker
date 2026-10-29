package com.finance.tracker.backend

import java.nio.file.Path
import java.sql.Connection
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashProjectionServiceMigrationTest {
    private class Rollback:RuntimeException()
    private fun inWorkspace(decorate:(Connection)->Connection={it},action:(Database,String,Long)->Unit) {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"),decorate)
        val workspaces=WorkspaceService(db);val name="投影重建服务测试";val workspace=workspaces.create("synthetic-admin",name).activeWorkspaceId!!;val account=deletionAccount(db,workspace)
        try {action(db,workspace,account)} finally {workspaces.delete("synthetic-admin",name);workspaces.select("synthetic-admin","fixture-a")}
    }
    private fun reset(db:Database,workspace:String)=db.write {c ->for(table in listOf("cash_projection_states","cash_projection_datasets")) c.prepareStatement("DELETE FROM $table WHERE workspace_id=?").use {it.setString(1,workspace);it.executeUpdate()}}
    private fun seed(db:Database,workspace:String,account:Long)=db.write {c ->List(3) {deletionFact(c,db,workspace,account,null)}}
    private fun rows(db:Database,workspace:String)=db.read {c ->listOf("cash_entries","cash_entry_members","cash_projection_datasets","cash_projections","cash_projection_members","cash_projection_relations","cash_transactions").associateWith {table ->
        c.prepareStatement("SELECT * FROM $table WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {r ->val n=r.metaData.columnCount
            buildList {while(r.next()) add(JsonArray((1..n).map {i ->r.getString(i)?.let(::JsonPrimitive) ?: JsonNull}).toString())}.sorted()}}
    }}
    private fun invalidate(db:Database,workspace:String,account:Long,ids:List<Long>)=db.write {c ->
        for(id in ids) deletionInsert(c,db,"cash_transaction_components",linkedMapOf("id" to id+1_000_000_000_000L,"workspace_id" to workspace,"cash_transaction_id" to id,"account_id" to account,"amount" to java.math.BigDecimal("-10"),"currency" to "CNY","ordinal" to 0,"label" to "","source_key" to "","metadata_json" to JsonObject(emptyMap()),"created_at" to deletionTime))
        val relation=deletionRelation(c,db,workspace,ids[0],ids[1])
        c.prepareStatement("UPDATE transaction_relations SET state='confirmed',primary_component_id=?,secondary_component_id=?,anchor_component_id=?,primary_role='expense',secondary_role='refund',applied_amount=10 WHERE id=?").use {
            it.setLong(1,ids[0]+1_000_000_000_000L);it.setLong(2,ids[1]+1_000_000_000_000L);it.setLong(3,ids[0]+1_000_000_000_000L);it.setLong(4,relation);it.executeUpdate()
        }
    }
    @Test fun firstEmptyAndNonemptyRebuildsAndRepeatedResultsUseTheActualApplicationService() {
        for(nonempty in listOf(false,true)) inWorkspace {db,workspace,account ->
            reset(db,workspace);if(nonempty) seed(db,workspace,account)
            val service=CashProjectionService(db,workspace);assertEquals("uninitialized",service.status().getValue("availability").jsonPrimitive.content)
            val first=service.rebuild();val second=service.rebuild();val third=service.rebuild()
            for((index,status) in listOf(first,second,third).withIndex()) {
                assertEquals("ready",status.getValue("availability").jsonPrimitive.content);assertEquals(index+1L,status.getValue("projection_version").jsonPrimitive.long)
                assertEquals(if(nonempty) 3L else 0L,status.getValue("projection_count").jsonPrimitive.long);assertEquals(if(nonempty) 3L else 0L,status.getValue("member_count").jsonPrimitive.long)
            }
            assertEquals(1,rows(db,workspace).getValue("cash_projection_datasets").size)
        }
    }
    @Test fun invalidRelationKeepsPreviousActiveDataAndPersistsOnlySafeIndependentDiagnostics()=inWorkspace {db,workspace,account ->
        val ids=seed(db,workspace,account);val service=CashProjectionService(db,workspace);val first=service.rebuild();invalidate(db,workspace,account,ids);val before=rows(db,workspace)
        assertEquals("projection.invalid_relation",assertFailsWith<CashProjectionFailure> {service.rebuild()}.code)
        val failed=service.status();assertEquals(first.getValue("active_dataset_id"),failed.getValue("active_dataset_id"));assertEquals(first.getValue("projection_version"),failed.getValue("projection_version"))
        assertEquals("ready",failed.getValue("availability").jsonPrimitive.content);assertEquals("failed",failed.getValue("last_build_status").jsonPrimitive.content)
        assertEquals("projection.invalid_relation",failed.getValue("last_error_code").jsonPrimitive.content);assertEquals("收支投影构建失败",failed.getValue("last_error_summary").jsonPrimitive.content);assertEquals(before,rows(db,workspace))
    }
    @Test fun sourceChangedAfterBuildIsRejectedAndTheEntireAttemptRollsBackBeforeIndependentDiagnostic()=inWorkspace {db,workspace,account ->
        reset(db,workspace);seed(db,workspace,account);val before=rows(db,workspace)
        val service=CashProjectionService(db,workspace,beforePublish={c ->c.prepareStatement("UPDATE cash_transactions SET note='changed' WHERE workspace_id=?").use {it.setString(1,workspace);it.executeUpdate()}})
        assertEquals("projection.concurrent_update",assertFailsWith<CashProjectionFailure> {service.rebuild()}.code)
        assertEquals(before,rows(db,workspace));val status=service.status();assertEquals("failed",status.getValue("last_build_status").jsonPrimitive.content);assertEquals(0L,status.getValue("projection_version").jsonPrimitive.long);assertEquals(JsonNull,status.getValue("active_dataset_id"))
    }
    @Test fun unknownInternalFailureIsRedactedAndStorageFailureDoesNotWriteBuildDiagnostics() {
        for(storage in listOf(false,true)) inWorkspace {db,workspace,account ->
            seed(db,workspace,account);val original=CashProjectionService(db,workspace).status();val before=rows(db,workspace)
            val service=CashProjectionService(db,workspace,beforePublish={c ->if(storage) c.createStatement().use {it.execute("SELECT nonexistent_synthetic_column FROM cash_projection_states")} else throw IllegalStateException("private synthetic details")})
            if(storage) {assertFailsWith<StorageFailure> {service.rebuild()};assertEquals(original,service.status())}
            else {val failure=assertFailsWith<CashProjectionFailure> {service.rebuild()};assertEquals("projection.failed",failure.code);assertEquals("projection.failed",failure.message);assertEquals("projection.failed",service.status().getValue("last_error_code").jsonPrimitive.content)}
            assertEquals(before,rows(db,workspace))
        }
    }
    @Test fun lateFailureDiagnosticCannotOverwriteNewSuccessfulBuildAndDiagnosticFailurePreservesOriginalError() {
        for(mode in listOf("later-success","diagnostic-failure")) inWorkspace {db,workspace,account ->
            val ids=seed(db,workspace,account);val good=CashProjectionService(db,workspace);val first=good.rebuild()
            if(mode=="diagnostic-failure") invalidate(db,workspace,account,ids)
            val broken=CashProjectionService(db,workspace,beforePublish={throw CashProjectionFailure("projection.incomplete")},beforeFailedBuild={
                if(mode=="diagnostic-failure") throw IllegalStateException("private diagnostic unavailable") else good.rebuild()
            })
            assertEquals(if(mode=="diagnostic-failure") "projection.invalid_relation" else "projection.incomplete",assertFailsWith<CashProjectionFailure> {broken.rebuild()}.code)
            val after=good.status();assertEquals("succeeded",after.getValue("last_build_status").jsonPrimitive.content);assertEquals(JsonNull,after.getValue("last_error_code"))
            assertEquals(first.getValue("projection_version").jsonPrimitive.long+(if(mode=="later-success") 1L else 0L),after.getValue("projection_version").jsonPrimitive.long)
        }
    }
    @Test fun fullRebuildReadsAndValidatesSourcesExactlyOnceWhilePublicationStillFencesDigestAndCount() {
        var sourceReads=0
        fun observe(c:Connection):Connection=java.lang.reflect.Proxy.newProxyInstance(Connection::class.java.classLoader,arrayOf(Connection::class.java)) {_,method,args ->
            if(method.name=="prepareStatement" && (args?.firstOrNull() as? String).orEmpty().observedSql().contains("funding_relation_id from cash_transactions tx")) sourceReads++
            try {method.invoke(c,*(args ?: emptyArray()))} catch(f:java.lang.reflect.InvocationTargetException) {throw f.targetException}
        } as Connection
        inWorkspace(::observe) {db,workspace,account ->
            seed(db,workspace,account);sourceReads=0
            val status=CashProjectionService(db,workspace).rebuild()
            assertEquals(3L,status.getValue("member_count").jsonPrimitive.long);assertEquals(1,sourceReads)
        }
    }
    @Test fun initializationSharesPublicationAndCommitsOnlyWithItsCallerWhileReadyStateIsIdempotent()=inWorkspace {db,workspace,account ->
        reset(db,workspace);seed(db,workspace,account);val before=rows(db,workspace)
        assertFailsWith<Rollback> {db.write {c ->val result=CashProjectionService.initializeInSession(CashSqlContext(c,workspace,db.sqlite));assertEquals(1L,result.getValue("projection_version").jsonPrimitive.long);assertEquals(3L,result.getValue("member_count").jsonPrimitive.long);throw Rollback()}}
        assertEquals(before,rows(db,workspace));assertEquals("uninitialized",CashProjectionService(db,workspace).status().getValue("availability").jsonPrimitive.content)
        val first=db.write {CashProjectionService.initializeInSession(CashSqlContext(it,workspace,db.sqlite))};val published=rows(db,workspace)
        val second=db.write {CashProjectionService.initializeInSession(CashSqlContext(it,workspace,db.sqlite))};assertEquals(first,second);assertEquals(published,rows(db,workspace))
    }
}
