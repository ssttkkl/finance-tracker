package com.finance.tracker.backend

import java.nio.file.Path
import java.sql.Connection
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashProjectionIncrementalMigrationTest {
    private fun inWorkspace(decorate:(Connection)->Connection={it},action:(Database,String,Long,List<Long>)->Unit) {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"),decorate)
        val workspaces=WorkspaceService(db);val name="增量投影测试";val workspace=workspaces.create("synthetic-admin",name).activeWorkspaceId!!;val account=deletionAccount(db,workspace)
        val facts=db.write {c ->List(3) {deletionFact(c,db,workspace,account,null)}}
        CashProjectionService(db,workspace).rebuild()
        try {action(db,workspace,account,facts)} finally {workspaces.delete("synthetic-admin",name);workspaces.select("synthetic-admin","fixture-a")}
    }
    private fun rows(c:Connection,workspace:String,table:String):List<JsonObject> =c.prepareStatement("SELECT * FROM $table WHERE workspace_id=? ORDER BY "+when(table) {"cash_entry_members" ->"transaction_id";"cash_projection_states" ->"workspace_id";else ->"id"}).use {
        it.setString(1,workspace);it.executeQuery().use {r ->val columns=(1..r.metaData.columnCount).associateWith {r.metaData.getColumnName(it)}
            buildList {while(r.next()) add(JsonObject(columns.entries.associate {(index,key) ->key to (r.getString(index)?.let(::JsonPrimitive) ?: JsonNull)}))}
        }
    }
    private fun snapshot(db:Database,workspace:String)=db.read {c ->listOf("cash_transactions","cash_transaction_components","cash_investment_funding_relations","transaction_relations","cash_transaction_issues","cash_entries","cash_entry_members","cash_projection_states","cash_projection_datasets","cash_projections","cash_projection_members","cash_projection_relations").associateWith {rows(c,workspace,it)}}
    private fun status(db:Database,workspace:String)=CashProjectionService(db,workspace).status()
    @Test fun standaloneChangesKeepProjectionAndMemberIdentitiesAndEveryUnrelatedColumn()=inWorkspace {db,workspace,_,ids ->
        val before=snapshot(db,workspace);val old=status(db,workspace)
        db.write {c ->c.prepareStatement("UPDATE cash_transactions SET note='修改备注',counterparty='新商家',amount=-20,row_revision=row_revision+1 WHERE workspace_id=? AND id=?").use {it.setString(1,workspace);it.setLong(2,ids[0]);assertEquals(1,it.executeUpdate())}
            CashProjectionService.maintainInSession(CashSqlContext(c,workspace,db.sqlite),setOf(ids[0]))
        }
        val after=snapshot(db,workspace);val next=status(db,workspace)
        assertEquals(old.getValue("active_dataset_id"),next.getValue("active_dataset_id"));assertEquals(3L,next.getValue("projection_version").jsonPrimitive.long);assertEquals(1L,next.getValue("source_revision").jsonPrimitive.long)
        assertEquals(3L,next.getValue("projection_count").jsonPrimitive.long);assertEquals(3L,next.getValue("member_count").jsonPrimitive.long)
        assertEquals(before.getValue("cash_projection_members"),after.getValue("cash_projection_members"))
        val oldRows=before.getValue("cash_projections").associateBy {it.getValue("projection_id")};val newRows=after.getValue("cash_projections").associateBy {it.getValue("projection_id")}
        for(id in ids.drop(1)) assertEquals(oldRows[JsonPrimitive("cash:$id")],newRows[JsonPrimitive("cash:$id")])
        val changed=newRows.getValue(JsonPrimitive("cash:${ids[0]}"));assertEquals(oldRows.getValue(JsonPrimitive("cash:${ids[0]}")).getValue("id"),changed.getValue("id"));assertEquals(JsonPrimitive("修改备注"),changed.getValue("note"));assertEquals(JsonPrimitive("新商家"),changed.getValue("counterparty"));assertEquals(-20,changed.getValue("net_amount").jsonPrimitive.content.toBigDecimal().toInt())
    }
    @Test fun additionAndRemovalChangeOnlyAffectedRowsAndMaintainExactCounts()=inWorkspace {db,workspace,account,ids ->
        val before=snapshot(db,workspace)
        val added=db.write {c ->val id=deletionFact(c,db,workspace,account,null);CashProjectionService.maintainIfReadyInSession(CashSqlContext(c,workspace,db.sqlite),setOf(id),newFactIds=setOf(id));id}
        val withNew=snapshot(db,workspace);assertEquals(before.getValue("cash_projections"),withNew.getValue("cash_projections").filter {it.getValue("root_cash_transaction_id").jsonPrimitive.long!=added});assertEquals(4L,status(db,workspace).getValue("member_count").jsonPrimitive.long)
        db.write {c ->CashProjectionService.removeIfReadyInSession(CashSqlContext(c,workspace,db.sqlite),setOf(ids[0]));c.prepareStatement("DELETE FROM cash_transactions WHERE workspace_id=? AND id=?").use {it.setString(1,workspace);it.setLong(2,ids[0]);assertEquals(1,it.executeUpdate())}}
        val after=snapshot(db,workspace);assertEquals(3L,status(db,workspace).getValue("projection_count").jsonPrimitive.long);assertEquals(3L,status(db,workspace).getValue("member_count").jsonPrimitive.long)
        assertEquals(withNew.getValue("cash_projections").filter {it.getValue("root_cash_transaction_id").jsonPrimitive.long!=ids[0]},after.getValue("cash_projections"))
        assertTrue(after.getValue("cash_entry_members").none {it.getValue("transaction_id").jsonPrimitive.long==ids[0]})
    }
    @Test fun oneSeedSplitIncludesAllOldMembersAndUnchangedGroupsKeepAllStoredIdentities()=inWorkspace {db,workspace,account,ids ->
        val relation=db.write {c ->
            for(id in ids.take(2)) deletionInsert(c,db,"cash_transaction_components",linkedMapOf("id" to id+1_000_000_000_000L,"workspace_id" to workspace,"cash_transaction_id" to id,"account_id" to account,"amount" to java.math.BigDecimal("-10"),"currency" to "CNY","ordinal" to 0,"label" to "","source_key" to "","metadata_json" to JsonObject(emptyMap()),"created_at" to deletionTime))
            val relation=deletionRelation(c,db,workspace,ids[0],ids[1]);c.prepareStatement("UPDATE transaction_relations SET kind='payment_mirror',state='confirmed',primary_component_id=?,secondary_component_id=?,anchor_component_id=?,primary_role='expense',secondary_role='expense' WHERE id=?").use {it.setLong(1,ids[0]+1_000_000_000_000L);it.setLong(2,ids[1]+1_000_000_000_000L);it.setLong(3,ids[0]+1_000_000_000_000L);it.setLong(4,relation);it.executeUpdate()}
            CashProjectionService.maintainInSession(CashSqlContext(c,workspace,db.sqlite),ids.take(2).toSet());relation
        }
        assertEquals(2L,status(db,workspace).getValue("projection_count").jsonPrimitive.long);val grouped=snapshot(db,workspace)
        db.write {c ->CashProjectionService.maintainInSession(CashSqlContext(c,workspace,db.sqlite),setOf(ids[1]))}
        val same=snapshot(db,workspace)
        for(table in listOf("cash_projection_members","cash_projection_relations")) assertEquals(grouped[table],same[table],table)
        assertEquals(grouped.getValue("cash_projections").map {it.getValue("id")},same.getValue("cash_projections").map {it.getValue("id")})
        db.write {c ->c.prepareStatement("UPDATE transaction_relations SET active=false,row_revision=row_revision+1 WHERE workspace_id=? AND id=?").use {it.setString(1,workspace);it.setLong(2,relation);it.executeUpdate()};CashProjectionService.maintainInSession(CashSqlContext(c,workspace,db.sqlite),setOf(ids[0]))}
        val split=snapshot(db,workspace);assertEquals(3L,status(db,workspace).getValue("projection_count").jsonPrimitive.long);assertEquals(3L,status(db,workspace).getValue("member_count").jsonPrimitive.long)
        assertEquals(ids.toSet(),split.getValue("cash_projection_members").map {it.getValue("cash_transaction_id").jsonPrimitive.long}.toSet());assertTrue(split.getValue("cash_projection_relations").isEmpty())
        assertEquals(same.getValue("cash_projections").single {it.getValue("root_cash_transaction_id").jsonPrimitive.long==ids[2]},split.getValue("cash_projections").single {it.getValue("root_cash_transaction_id").jsonPrimitive.long==ids[2]})
    }
    @Test fun currentReferencesKeepWorkspaceDatasetAndFiveHundredBatchBoundaries()=inWorkspace {db,workspace,_,ids ->db.write {c ->
        val context=CashSqlContext(c,workspace,db.sqlite);val repository=CashProjectionRepository(context);val state=repository.requireReadyStateLock()
        val input=ids.toSet()+(90000L..90500L).toSet();val found=repository.currentReferences(input,state.datasetId)
        assertEquals(ids.map(Long::toString).toSet(),found.keys);assertEquals(found,repository.currentReferences(ids.toSet(),state.datasetId));assertTrue(repository.currentReferences(input,"unrelated-dataset").isEmpty())
        assertTrue(CashProjectionRepository(CashSqlContext(c,"fixture-a",db.sqlite)).currentReferences(input,state.datasetId).isEmpty())
    }}
    @Test fun readyStateHasOneLiveIdentityPerTransactionContextAcrossRepositoriesAndFullPublication()=inWorkspace {db,workspace,_,ids ->db.write {c ->
        val context=CashSqlContext(c,workspace,db.sqlite);val first=CashProjectionRepository(context);val state=first.requireReadyStateLock()
        val second=CashProjectionRepository(context);val same=second.requireReadyStateLock();assertSame(state,same)
        first.refreshDisplayFields(ids[0],"显示","备注",state);assertEquals(3L,same.projectionVersion);assertEquals(1L,same.sourceRevision)
        val proof=first.readValidatedSources();val dataset=first.createStagingDataset(proof.digest,CASH_PROJECTION_RULES_VERSION)
        first.replaceDataset(dataset,buildCashProjections(proof.facts,proof.relations),4);first.publishDataset(dataset,proof.digest,CASH_PROJECTION_RULES_VERSION,proof)
        assertSame(state,second.requireReadyStateLock());assertEquals(dataset,state.datasetId);assertEquals(4L,state.projectionVersion)
        second.refreshDisplayFields(ids[0],"再次显示","再次备注",state)
        assertEquals(5L,state.projectionVersion);assertEquals(2L,state.sourceRevision)
        assertEquals(JsonPrimitive(5),second.status().getValue("projection_version"))
    }}
    @Test fun noReadyStateMaintainsOnlyStableEntriesAndRejectsRequiredPublication()=inWorkspace {db,workspace,_,ids ->
        db.write {c ->c.prepareStatement("DELETE FROM cash_projection_states WHERE workspace_id=?").use {it.setString(1,workspace);it.executeUpdate()};c.prepareStatement("DELETE FROM cash_projection_datasets WHERE workspace_id=?").use {it.setString(1,workspace);it.executeUpdate()}}
        val before=snapshot(db,workspace)
        db.write {c ->val context=CashSqlContext(c,workspace,db.sqlite)
            c.prepareStatement("UPDATE cash_transactions SET note='未初始化修改' WHERE workspace_id=? AND id=?").use {it.setString(1,workspace);it.setLong(2,ids[0]);it.executeUpdate()}
            assertNull(CashProjectionService.maintainIfReadyInSession(context,setOf(ids[0])))
            val entry=context.entries.current(ids[0])!!;assertEquals(JsonPrimitive("未初始化修改"),entry.values["note"])
            assertEquals("projection.unavailable",assertFailsWith<CashProjectionFailure> {CashProjectionService.maintainInSession(context,setOf(ids[0]))}.code)
        }
        assertEquals("uninitialized",status(db,workspace).getValue("availability").jsonPrimitive.content)
        assertEquals(before.getValue("cash_projection_datasets"),snapshot(db,workspace).getValue("cash_projection_datasets"));assertTrue(snapshot(db,workspace).getValue("cash_projections").isEmpty())
        CashProjectionService(db,workspace).rebuild();assertEquals(3L,status(db,workspace).getValue("member_count").jsonPrimitive.long)
    }
    @Test fun standaloneRefreshRepairsMissingRowsAndKeepsDraftAttentionWithoutFullSourceReads()=inWorkspace {db,workspace,account,ids ->
        val original=snapshot(db,workspace)
        db.write {c ->val context=CashSqlContext(c,workspace,db.sqlite)
            c.prepareStatement("UPDATE cash_transactions SET note='直接刷新' WHERE workspace_id=? AND id=?").use {it.setString(1,workspace);it.setLong(2,ids[0]);it.executeUpdate()}
            val row=readCashFactRows(context,setOf(ids[0])).single();CashProjectionService.replaceStandaloneFactIfReadyInSession(context,row)
        }
        assertEquals(original.getValue("cash_projection_members"),snapshot(db,workspace).getValue("cash_projection_members"))
        db.write {c ->val context=CashSqlContext(c,workspace,db.sqlite)
            val dataset=CashProjectionRepository(context).requireReadyStateLock().datasetId
            c.prepareStatement("DELETE FROM cash_projections WHERE workspace_id=? AND dataset_id=? AND root_cash_transaction_id=?").use {it.setString(1,workspace);it.setString(2,dataset);it.setLong(3,ids[0]);it.executeUpdate()}
            c.prepareStatement("UPDATE cash_projection_states SET projection_count=projection_count-1,member_count=member_count-1 WHERE workspace_id=?").use {it.setString(1,workspace);it.executeUpdate()}
            CashProjectionService.replaceStandaloneFactIfReadyInSession(context,readCashFactRows(context,setOf(ids[0])).single())
            val draft=deletionFact(c,db,workspace,account,null)
            c.prepareStatement("UPDATE cash_transactions SET state='draft',account_id=NULL,amount=NULL,currency=NULL,occurred_at=NULL WHERE workspace_id=? AND id=?").use {it.setString(1,workspace);it.setLong(2,draft);it.executeUpdate()}
            CashProjectionService.maintainStandaloneFactIfReadyInSession(context,readCashFactRows(context,setOf(draft)).single())
            val projected=rows(c,workspace,"cash_projections").single {it.getValue("root_cash_transaction_id").jsonPrimitive.long==draft}
            assertEquals(JsonPrimitive("draft"),projected.getValue("state"));assertEquals(JsonPrimitive("1"),projected.getValue("attention_count"));assertEquals(JsonNull,projected.getValue("net_amount"));assertEquals(deletionTime,storedCashInstant(projected.getValue("sort_at").jsonPrimitive.content,db.sqlite))
        }
        assertEquals(4L,status(db,workspace).getValue("member_count").jsonPrimitive.long)
    }
    @Test fun savepointRollbackRestoresEntryMemberAndLiveStateCachesBeforeContinuingTheOuterTransaction() {
        for(mode in listOf("sql","jdbc")) inWorkspace {db,workspace,account,ids ->
            if(mode=="jdbc" && db.sqlite) return@inWorkspace
            db.write {c ->
                val context=CashSqlContext(c,workspace,db.sqlite);val repository=CashProjectionRepository(context);val state=repository.requireReadyStateLock()
                val dataset=state.datasetId;val first=context.entries.current(ids[0])!!;val second=context.entries.current(ids[1])!!;val original=rows(c,workspace,"cash_projections")
                val point=if(mode=="jdbc") c.setSavepoint("cache_boundary") else {c.createStatement().use {it.execute("SAVEPOINT cache_boundary")};null}
                context.entries.patch(ids[0],mapOf("note" to "保存点内人工备注"),first.revision)
                for(id in ids.take(2)) deletionInsert(c,db,"cash_transaction_components",linkedMapOf("id" to id+1_000_000_000_000L,"workspace_id" to workspace,"cash_transaction_id" to id,"account_id" to account,"amount" to java.math.BigDecimal("-10"),"currency" to "CNY","ordinal" to 0,"label" to "","source_key" to "","metadata_json" to JsonObject(emptyMap()),"created_at" to deletionTime))
                val relation=deletionRelation(c,db,workspace,ids[0],ids[1]);c.prepareStatement("UPDATE transaction_relations SET kind='payment_mirror',state='confirmed',primary_component_id=?,secondary_component_id=?,anchor_component_id=?,primary_role='expense',secondary_role='expense' WHERE id=?").use {it.setLong(1,ids[0]+1_000_000_000_000L);it.setLong(2,ids[1]+1_000_000_000_000L);it.setLong(3,ids[0]+1_000_000_000_000L);it.setLong(4,relation);it.executeUpdate()}
                CashProjectionService.maintainInSession(context,ids.take(2).toSet(),state)
                val created=deletionFact(c,db,workspace,account,null);CashProjectionService.maintainIfReadyInSession(context,setOf(created),newFactIds=setOf(created))
                val proof=repository.readValidatedSources();val staged=repository.createStagingDataset(proof.digest,CASH_PROJECTION_RULES_VERSION)
                repository.replaceDataset(staged,buildCashProjections(proof.facts,proof.relations),5);repository.publishDataset(staged,proof.digest,CASH_PROJECTION_RULES_VERSION,proof)
                assertNotEquals(dataset,state.datasetId);assertEquals(4L,state.memberCount)
                if(point!=null) {c.rollback(point);c.releaseSavepoint(point)} else c.createStatement().use {it.execute("ROLLBACK TO SAVEPOINT cache_boundary");it.execute("RELEASE SAVEPOINT cache_boundary")}
                assertEquals(dataset,state.datasetId);assertEquals(2L,state.projectionVersion);assertEquals(3L,state.projectionCount);assertEquals(3L,state.memberCount)
                assertSame(first,context.entries.current(ids[0]));assertSame(second,context.entries.current(ids[1]));assertEquals(JsonPrimitive(""),first.values.getValue("note"));assertEquals(1L,first.revision);assertNull(context.entries.current(created))
                assertEquals(original,rows(c,workspace,"cash_projections"));CashProjectionService.maintainInSession(context,setOf(ids[0]),state)
                assertEquals(3L,state.projectionVersion);assertEquals(1L,state.sourceRevision);assertEquals(3L,state.projectionCount);assertEquals(3L,state.memberCount)
                assertEquals(JsonPrimitive(""),context.entries.current(ids[0])!!.values.getValue("note"))
            }
        }
    }
    @Test fun aFreshTransactionSavepointNeverCallsOldContextListenersOnTheReusedConnection()=inWorkspace {db,workspace,_,ids ->db.write {c ->
        val old=CashSqlContext(c,workspace,db.sqlite);CashProjectionRepository(old).requireReadyStateLock();old.entries.current(ids[0])
        if(db.sqlite) c.createStatement().use {it.execute("COMMIT");it.execute("BEGIN IMMEDIATE")} else c.commit()
        val fresh=CashSqlContext(c,workspace,db.sqlite);val repository=CashProjectionRepository(fresh);val state=repository.requireReadyStateLock();fresh.entries.current(ids[0])
        val proof=repository.readValidatedSources()
        c.createStatement().use {it.execute("SAVEPOINT fresh_transaction");it.execute("ROLLBACK TO fresh_transaction");it.execute("RELEASE fresh_transaction")}
        proof.requireFor(repository.sources,proof.digest);assertEquals(2L,state.projectionVersion);assertEquals(3L,state.memberCount)
        assertEquals("projection.incomplete",assertFailsWith<CashProjectionFailure> {old.requireActive()}.code)
        CashProjectionService.maintainInSession(fresh,setOf(ids[0]),state);assertEquals(3L,state.projectionVersion)
    }}
    @Test fun savepointCreationFlushesEarlierDirtyValuesWhileRollbackDiscardsOnlyLaterValues() {
        for(mode in listOf("sql","jdbc")) inWorkspace {db,workspace,_,ids ->
            if(mode=="jdbc" && db.sqlite) return@inWorkspace
            db.write {c ->val context=CashSqlContext(c,workspace,db.sqlite);val state=CashProjectionRepository(context).requireReadyStateLock()
                val (members,entries)=context.entries.forTransactions(ids.take(2));val first=entries.getValue(members.getValue(ids[0]).entryId);val member=members.getValue(ids[1]);val originalEntry=member.entryId
                first.values=JsonObject(first.values+mapOf("note" to JsonPrimitive("保存点前备注")));first.manualFields=listOf("note")
                member.standaloneValues=JsonObject(member.standaloneValues+mapOf("note" to JsonPrimitive("保存点前恢复值")));member.standaloneManualFields=listOf("note")
                val point=if(mode=="jdbc") c.setSavepoint("dirty_boundary") else {c.createStatement().use {it.execute("SAVEPOINT dirty_boundary")};null}
                first.values=JsonObject(first.values+mapOf("note" to JsonPrimitive("保存点内备注")));member.standaloneValues=JsonObject(member.standaloneValues+mapOf("note" to JsonPrimitive("保存点内恢复值")));member.entryId=first.id;context.entries.flush()
                if(point!=null) {c.rollback(point);c.releaseSavepoint(point)} else c.createStatement().use {it.execute("ROLLBACK TO dirty_boundary");it.execute("RELEASE dirty_boundary")}
                assertSame(first,context.entries.current(ids[0]));assertEquals(JsonPrimitive("保存点前备注"),first.values.getValue("note"));assertEquals(originalEntry,member.entryId);assertEquals(JsonPrimitive("保存点前恢复值"),member.standaloneValues.getValue("note"))
                CashProjectionService.maintainInSession(context,setOf(ids[0]),state);assertEquals(JsonPrimitive("保存点前备注"),context.entries.current(ids[0])!!.values.getValue("note"));assertEquals(3L,state.projectionVersion)
            }
        }
    }
    @Test fun staleOrForeignLockedStateAndBothVersionOverflowsCannotCommitPartialIncrementalChanges() {
        for(boundary in listOf("context","commit","rollback")) inWorkspace {db,workspace,_,ids ->
            val before=snapshot(db,workspace);val previous=status(db,workspace)
            db.write {c ->val original=CashSqlContext(c,workspace,db.sqlite);val state=CashProjectionRepository(original).requireReadyStateLock()
                if(boundary!="context") {if(db.sqlite) c.createStatement().use {it.execute(if(boundary=="commit") "COMMIT" else "ROLLBACK");it.execute("BEGIN IMMEDIATE")} else if(boundary=="commit") c.commit() else c.rollback()}
                val fresh=CashSqlContext(c,workspace,db.sqlite)
                assertEquals("projection.unavailable",assertFailsWith<CashProjectionFailure> {CashProjectionService.maintainInSession(fresh,setOf(ids[0]),state)}.code)
            }
            assertEquals(before,snapshot(db,workspace));assertEquals(previous,status(db,workspace))
        }
        for(field in listOf("projection_version","source_revision")) inWorkspace {db,workspace,_,ids ->
            db.write {c ->c.prepareStatement("UPDATE cash_projection_states SET $field=9223372036854775807 WHERE workspace_id=?").use {it.setString(1,workspace);it.executeUpdate()}}
            val before=snapshot(db,workspace);val previous=status(db,workspace)
            assertFailsWith<ArithmeticException> {db.write {c ->c.prepareStatement("UPDATE cash_transactions SET note='必须回滚的来源变化' WHERE workspace_id=? AND id=?").use {it.setString(1,workspace);it.setLong(2,ids[0]);it.executeUpdate()};CashProjectionService.maintainInSession(CashSqlContext(c,workspace,db.sqlite),setOf(ids[0]))}}
            assertEquals(before,snapshot(db,workspace),field);assertEquals(previous,status(db,workspace),field)
        }
    }
    @Test fun actualLateStateFailureRollsBackSourceEntryProjectionAndEveryMemberColumn() {
        var reached=false
        fun call(target:Any,method:java.lang.reflect.Method,args:Array<out Any?>?):Any?=try {method.invoke(target,*(args ?: emptyArray()))} catch(f:java.lang.reflect.InvocationTargetException) {throw f.targetException}
        fun observe(c:Connection):Connection=java.lang.reflect.Proxy.newProxyInstance(Connection::class.java.classLoader,arrayOf(Connection::class.java)) {_,method,args ->
            val result=call(c,method,args)
            if(result is java.sql.PreparedStatement && (args?.firstOrNull() as? String).orEmpty().observedSql().startsWith("update cash_projection_states set projection_version="))
                java.lang.reflect.Proxy.newProxyInstance(java.sql.PreparedStatement::class.java.classLoader,arrayOf(java.sql.PreparedStatement::class.java)) {_,operation,values ->try {call(result,operation,values)} catch(f:java.sql.SQLException) {if(f.message.orEmpty().contains("synthetic incremental failure")) reached=true;throw f}}
            else result
        } as Connection
        inWorkspace(::observe) {db,workspace,_,ids ->
            val trigger="ft_incremental_"+java.util.UUID.randomUUID().toString().replace("-","");check(workspace.matches(Regex("[A-Za-z0-9-]+")))
            db.write {c ->c.createStatement().use {
                if(db.sqlite) it.execute("CREATE TRIGGER $trigger BEFORE UPDATE OF projection_version ON cash_projection_states WHEN NEW.workspace_id='$workspace' BEGIN SELECT RAISE(ABORT,'synthetic incremental failure'); END")
                else {it.execute("CREATE FUNCTION $trigger() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''synthetic incremental failure''; RETURN NEW; END'");it.execute("CREATE TRIGGER $trigger BEFORE UPDATE OF projection_version ON cash_projection_states FOR EACH ROW WHEN (NEW.workspace_id='$workspace') EXECUTE FUNCTION $trigger()")}
            }}
            try {val before=snapshot(db,workspace);val previous=status(db,workspace)
                assertFailsWith<StorageFailure> {db.write {c ->c.prepareStatement("UPDATE cash_transactions SET note='晚期失败应回滚' WHERE workspace_id=? AND id=?").use {it.setString(1,workspace);it.setLong(2,ids[0]);it.executeUpdate()};CashProjectionService.maintainInSession(CashSqlContext(c,workspace,db.sqlite),setOf(ids[0]))}}
                assertTrue(reached,"must reach actual late state UPDATE");assertEquals(before,snapshot(db,workspace));assertEquals(previous,status(db,workspace))
            } finally {db.write {c ->c.createStatement().use {it.execute("DROP TRIGGER $trigger"+if(db.sqlite) "" else " ON cash_projection_states");if(!db.sqlite) it.execute("DROP FUNCTION $trigger()")}}}
        }
    }
}
