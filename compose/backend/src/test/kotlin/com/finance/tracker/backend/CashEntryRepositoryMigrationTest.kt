package com.finance.tracker.backend

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashEntryRepositoryMigrationTest {
    private fun inWorkspace(action:(Database,String,MutableList<String>)->Unit) {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val statements=mutableListOf<String>()
        fun call(target:Any,method:java.lang.reflect.Method,args:Array<out Any?>?):Any?=try {method.invoke(target,*(args ?: emptyArray()))} catch(f:InvocationTargetException) {throw f.targetException}
        fun observe(c:Connection):Connection=Proxy.newProxyInstance(Connection::class.java.classLoader,arrayOf(Connection::class.java)) {_,method,args ->
            val value=call(c,method,args)
            if(value is Statement) {
                val prepared=args?.firstOrNull() as? String
                Proxy.newProxyInstance(Statement::class.java.classLoader,arrayOf(if(value is PreparedStatement) PreparedStatement::class.java else Statement::class.java)) {_,operation,values ->
                    if(operation.name in setOf("execute","executeQuery","executeUpdate","executeBatch")) (prepared ?: values?.firstOrNull() as? String)?.let {statements.add(it)}
                    call(value,operation,values)
                }
            } else value
        } as Connection
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"),::observe)
        val workspaces=WorkspaceService(db);val workspace=workspaces.create("synthetic-admin","公共收支仓库测试").activeWorkspaceId!!
        try {action(db,workspace,statements)} finally {workspaces.select("synthetic-admin",workspace);workspaces.delete("synthetic-admin","公共收支仓库测试");workspaces.select("synthetic-admin","fixture-a")}
    }
    private fun seed(c:Connection,db:Database,workspace:String,account:Long):Pair<CashEntryRepository,List<Long>> {
        val all=(1..3).map {deletionFact(c,db,workspace,account,null)};val ids=listOf(all.first(),all.last())
        val facts=ids.map {CashProjectionFact.create(it,account,deletionTime,"-10","CNY","商家",null,"","fixture",it.toString())}
        val repo=CashEntryRepository(c,workspace,db.sqlite)
        repo.reconcile(buildCashProjections(facts,emptyList()).projections)
        return repo to ids
    }
    @Test fun creatingMissingEntriesLeavesUnrelatedSourcePayloadUnreadAndUnchanged()=inWorkspace {db,workspace,_ ->
        val account=deletionAccount(db,workspace)
        db.write {c ->
            val ids=(1..2).map {deletionFact(c,db,workspace,account,null)}
            val payload=if(db.sqlite) "{invalid source payload" else "{\"private\":\"unrelated source text\"}"
            val slot=if(db.sqlite) "?" else "CAST(? AS json)"
            c.prepareStatement("UPDATE cash_transactions SET source_payload=$slot WHERE workspace_id=?").use {
                it.setString(1,payload);it.setString(2,workspace);assertEquals(2,it.executeUpdate())
            }
            val repo=CashEntryRepository(c,workspace,db.sqlite)
            assertEquals(JsonPrimitive("人工备注"),repo.applyEdit(ids[0],mapOf("note" to "人工备注"),null).values["note"])
            repo.chooseCommon(listOf(ids[1]),mapOf("counterparty" to "公共交易对方"))
            assertEquals(JsonPrimitive("公共交易对方"),repo.current(ids[1])!!.values["counterparty"])
            c.prepareStatement("SELECT source_payload FROM cash_transactions WHERE workspace_id=? ORDER BY id").use {
                it.setString(1,workspace);it.executeQuery().use {rows ->
                    repeat(2) {assertTrue(rows.next());assertEquals(payload,rows.getString(1))};assertFalse(rows.next())
                }
            }
        }
    }
    @Test fun smallLookupIsOneQueryAndPreservesCurrentObjectIdentity()=inWorkspace {db,workspace,statements ->
        val account=deletionAccount(db,workspace)
        db.write {c ->
            val (repo,ids)=seed(c,db,workspace,account);val original=repo.forTransactions(ids)
            assertTrue(repo.snapshot(ids).all {it.digest=="b362a1c6b300c17eac57343877a925e86b081301a52fe8ff5cf562ef41c98e10"})
            statements.clear();val loaded=repo.forTransactions(ids+ids.first())
            assertEquals(1,statements.size);assertTrue(repo.forTransactions(emptyList()).let {it.first.isEmpty() && it.second.isEmpty()});assertEquals(1,statements.size)
            original.first.forEach {(id,member) ->assertSame(member,loaded.first[id])};original.second.forEach {(id,entry) ->assertSame(entry,loaded.second[id])}
        }
    }
    @Test fun unflushedAndStaleMembersKeepIdentityUntilExplicitExpiry()=inWorkspace {db,workspace,_ ->
        val account=deletionAccount(db,workspace)
        db.write {c ->
            val (repo,ids)=seed(c,db,workspace,account);val loaded=repo.forTransactions(ids);val first=loaded.first.getValue(ids[0]);val second=loaded.first.getValue(ids[1]);val original=first.entryId
            repo.autoFlush=false;first.entryId=second.entryId
            val pending=repo.forTransactions(listOf(ids[0]));assertSame(first,pending.first[ids[0]]);assertEquals(setOf(second.entryId),pending.second.keys)
            first.entryId=original;repo.autoFlush=true;repo.flush()
            c.prepareStatement("UPDATE cash_entry_members SET entry_id=? WHERE workspace_id=? AND transaction_id=?").use {it.setString(1,second.entryId);it.setString(2,workspace);it.setLong(3,first.transactionId);it.executeUpdate()}
            val stale=repo.forTransactions(listOf(ids[0]));assertSame(first,stale.first[ids[0]]);assertEquals(setOf(original),stale.second.keys)
            repo.expireMember(first.transactionId);val fresh=repo.forTransactions(listOf(ids[0]));assertSame(first,fresh.first[ids[0]]);assertEquals(setOf(second.entryId),fresh.second.keys)
        }
    }
    @Test fun fourHundredAndFourHundredOneIdsKeepAllBatchesAndOriginalQueryBudgets() {
        for((size,expected) in listOf(400 to 1,401 to 3)) inWorkspace {db,workspace,statements ->
            val account=deletionAccount(db,workspace)
            db.write {c ->val (repo,ids)=seed(c,db,workspace,account);statements.clear()
                val loaded=repo.forTransactions((ids[0] until ids[0]+size).toList())
                assertEquals(expected,statements.size);assertEquals(ids.toSet(),loaded.first.keys);assertEquals(loaded.first.values.map {it.entryId}.toSet(),loaded.second.keys)
            }
        }
    }
    @Test fun explicitExpiryDiscardsPendingChangesBeforeAnyAutoflush()=inWorkspace {db,workspace,statements ->
        val account=deletionAccount(db,workspace)
        db.write {c ->val (repo,ids)=seed(c,db,workspace,account);val original=repo.forTransactions(ids);val first=original.first.getValue(ids[0]);val old=first.copy()
            first.entryId=original.first.getValue(ids[1]).entryId;first.standaloneValues=JsonObject(first.standaloneValues+mapOf("note" to JsonPrimitive("待丢弃")));first.standaloneManualFields=listOf("note")
            repo.expireMember(first.transactionId);statements.clear();val loaded=repo.forTransactions(listOf(ids[0]));assertSame(first,loaded.first[ids[0]]);assertEquals(old,first)
            assertTrue(statements.none {it.observedSql().startsWith("update ")},statements.toString())
            c.prepareStatement("SELECT entry_id,standalone_values,standalone_manual_fields FROM cash_entry_members WHERE workspace_id=? AND transaction_id=?").use {it.setString(1,workspace);it.setLong(2,first.transactionId);it.executeQuery().use {r ->assertTrue(r.next());assertEquals(old.entryId,r.getString(1));assertEquals(old.standaloneValues,Json.parseToJsonElement(r.getString(2)));assertEquals(JsonArray(old.standaloneManualFields.map(::JsonPrimitive)),Json.parseToJsonElement(r.getString(3)))}}
        }
    }
    @Test fun staleJoinedMemberFallsBackPerRowEvenWhenBothEntriesAppearInTheJoin()=inWorkspace {db,workspace,statements ->
        val account=deletionAccount(db,workspace)
        db.write {c ->val (seeded,ids)=seed(c,db,workspace,account);val old=seeded.forTransactions(ids);val a=old.first.getValue(ids[0]).entryId;val b=old.first.getValue(ids[1]).entryId
            fun raw(id:Long,target:String) {c.prepareStatement("UPDATE cash_entry_members SET entry_id=? WHERE workspace_id=? AND transaction_id=?").use {it.setString(1,target);it.setString(2,workspace);it.setLong(3,id);it.executeUpdate()}}
            raw(ids[1],a);val repo=CashEntryRepository(c,workspace,db.sqlite);val cached=repo.forTransactions(ids)
            raw(ids[0],b);statements.clear();val loaded=repo.forTransactions(ids)
            assertEquals(setOf(a),loaded.second.keys);assertEquals(2,statements.size)
            cached.first.forEach {(id,m) ->assertSame(m,loaded.first[id])};assertSame(cached.second[a],loaded.second[a])
        }
    }
    @Test fun missingAndForeignEntriesAreReportedWithoutDiscardingTheMember() {
        for(foreign in listOf(false,true)) inWorkspace {db,workspace,_ ->
            val account=deletionAccount(db,workspace);val workspaces=WorkspaceService(db)
            val other=workspaces.create("synthetic-admin","外区公共收支").activeWorkspaceId!!
            try {db.write {c ->
                val (repo,ids)=seed(c,db,workspace,account);val member=repo.forTransactions(ids).first.getValue(ids[0]);val original=member.entryId
                if(foreign) deletionEntry(c,db,other,"foreign-entry-$workspace",ids[0],null)
                repo.autoFlush=false;member.entryId=if(foreign) "foreign-entry-$workspace" else "missing-entry"
                val loaded=repo.forTransactions(listOf(ids[0]));assertSame(member,loaded.first[ids[0]]);assertTrue(loaded.second.isEmpty())
                assertFailsWith<NoSuchElementException> {repo.snapshot(listOf(ids[0]))};member.entryId=original
            }} finally {workspaces.select("synthetic-admin",other);workspaces.delete("synthetic-admin","外区公共收支");workspaces.select("synthetic-admin",workspace)}
        }
    }
    private fun projections(ids:List<Long>,account:Long,grouped:Boolean):List<CashProjection> {
        val facts=ids.map {CashProjectionFact.create(it,account,deletionTime,"-10","CNY","商家",null,"","fixture",it.toString())}
        val edges=if(grouped) listOf(CashProjectionRelation.create(1,"payment_mirror",ids[0],ids[1])) else emptyList()
        return buildCashProjections(facts,edges).projections
    }
    @Test fun mergeRejectsConflictingHumanValuesAndPreservesSourcesOnRollback()=inWorkspace {db,workspace,_ ->
        val account=deletionAccount(db,workspace);lateinit var ids:List<Long>
        val original=db.write {c ->val (repo,seeded)=seed(c,db,workspace,account);ids=seeded
            repo.patch(ids[0],mapOf("note" to "人工甲"),1);repo.patch(ids[1],mapOf("note" to "人工乙"),1);repo.snapshot(ids)
        }
        val failure=assertFailsWith<CashPolicyError> {db.write {c ->CashEntryRepository(c,workspace,db.sqlite).reconcile(projections(ids,account,true))}}
        assertEquals("common_field_conflict",failure.code);assertEquals(listOf("note"),failure.fields)
        db.read {c ->val repo=CashEntryRepository(c,workspace,db.sqlite);assertEquals(original,repo.snapshot(ids))
            c.prepareStatement("SELECT note,manual_overrides FROM cash_transactions WHERE workspace_id=? ORDER BY id").use {it.setString(1,workspace);it.executeQuery().use {r ->repeat(3) {assertTrue(r.next());assertEquals("",r.getString(1));assertEquals(JsonObject(emptyMap()),Json.parseToJsonElement(r.getString(2)))}}}
        }
    }
    @Test fun splittingKeepsOldRootIdentityAndRestoresOrInheritsExplicitHumanValues() {
        for(inherit in listOf(false,true)) inWorkspace {db,workspace,_ ->val account=deletionAccount(db,workspace)
            db.write {c ->val (repo,ids)=seed(c,db,workspace,account)
                repo.patch(ids[0],mapOf("note" to "各自甲"),1);repo.patch(ids[1],mapOf("note" to "各自甲"),1)
                val group=repo.reconcile(projections(ids,account,true)).values.single();val oldId=group.id
                repo.patch(ids[0],mapOf("note" to "合并公共值"),group.revision)
                assertEquals("inheritance_required",assertFailsWith<CashPolicyError> {repo.prepareSplit(ids,null)}.code)
                repo.prepareSplit(ids,inherit)
                val diffs=repo.splitFieldDiffs(projections(ids,account,false))
                assertEquals(2,diffs.size);assertTrue(diffs.all {it.field=="note" && it.current==JsonPrimitive("合并公共值") && it.restored==JsonPrimitive("各自甲")})
                val split=repo.reconcile(projections(ids,account,false).reversed())
                val first=split.getValue("cash:${ids[0]}");val second=split.getValue("cash:${ids[1]}")
                assertEquals(oldId,first.id);assertNotEquals(oldId,second.id)
                for(e in listOf(first,second)) {assertEquals(JsonPrimitive(if(inherit) "合并公共值" else "各自甲"),e.values["note"]);assertNull(e.splitInherit)}
                val (members,_)=repo.forTransactions(ids)
                for(m in members.values) assertEquals(JsonPrimitive(if(inherit) "合并公共值" else "各自甲"),m.standaloneValues["note"])
            }
        }
    }
    @Test fun patchProtectsSameValueAndSingletonRestoreButRejectsStaleOrNonCommonFields()=inWorkspace {db,workspace,_ ->val account=deletionAccount(db,workspace)
        db.write {c ->val (repo,ids)=seed(c,db,workspace,account)
            val first=repo.current(ids[0])!!;val before=repo.snapshot(ids)
            assertEquals("entry_changed",assertFailsWith<CashPolicyError> {repo.patch(ids[0],mapOf("note" to "旧修订"),0)}.code)
            assertEquals("field_not_editable",assertFailsWith<CashPolicyError> {repo.patch(ids[0],mapOf("amount" to "1"),1)}.code)
            assertEquals("field_not_editable",assertFailsWith<CashPolicyError> {repo.patch(ids[0],listOf("note"),1)}.code)
            assertFailsWith<InvalidCashValue> {repo.patch(ids[0],mapOf("note" to true),1)};assertEquals(before,repo.snapshot(ids))
            val changed=repo.patch(ids[0],mapOf("note" to ""),1);assertSame(first,changed);assertEquals(2L,changed.revision);assertEquals(listOf("note"),changed.manualFields)
            val member=repo.forTransactions(ids).first.getValue(ids[0]);assertEquals(listOf("note"),member.standaloneManualFields)
            repo.patch(ids[0],mapOf("note" to ""),2);assertEquals(2L,changed.revision)
            c.prepareStatement("UPDATE cash_transactions SET note='来源更新',counterparty='新商家' WHERE workspace_id=?").use {it.setString(1,workspace);it.executeUpdate()}
            val refreshed=repo.reconcile(projections(ids,account,false)).getValue("cash:${ids[0]}");assertEquals(JsonPrimitive(""),refreshed.values["note"]);assertEquals(JsonPrimitive("新商家"),refreshed.values["counterparty"])
            val restored=repo.forTransactions(ids).first.getValue(ids[0]);assertEquals(JsonPrimitive(""),restored.standaloneValues["note"]);assertEquals(JsonPrimitive("新商家"),restored.standaloneValues["counterparty"])
        }
    }
    @Test fun explicitCommonChoiceCreatesMissingEntryAndRemovalRetiresOnlyEmptyEntries()=inWorkspace {db,workspace,_ ->val account=deletionAccount(db,workspace)
        db.write {c ->val (repo,ids)=seed(c,db,workspace,account);val group=repo.reconcile(projections(ids,account,true)).values.single()
            val before=repo.snapshot(ids);repo.chooseCommon(ids,mapOf("amount" to "9"));assertEquals(before,repo.snapshot(ids))
            assertEquals("category_unavailable",assertFailsWith<CashPolicyError> {repo.chooseCommon(ids,mapOf("category_id" to "private"))}.code)
            val revision=group.revision;repo.chooseCommon(ids,mapOf("note" to "公共决定"));assertEquals(revision+2,group.revision)
            repo.removeMembers(setOf(ids[1]));assertNotNull(repo.current(ids[0]));assertNull(repo.current(ids[1]));repo.removeMembers(setOf(ids[0]));assertNull(repo.current(ids[0]))
            repo.chooseCommon(listOf(ids[0]),mapOf("note" to "重新建立"));assertEquals(JsonPrimitive("重新建立"),repo.current(ids[0])!!.values["note"])
        }
    }
    @Test fun firstEditRequiresAnAbsentRevisionAndLaterEditsRequireTheCurrentRevision()=inWorkspace {db,workspace,_ ->val account=deletionAccount(db,workspace)
        db.write {c ->val (repo,ids)=seed(c,db,workspace,account);repo.removeMembers(setOf(ids[0]))
            assertEquals("entry_changed",assertFailsWith<CashPolicyError> {repo.applyEdit(ids[0],mapOf("note" to "错误"),1)}.code)
            assertNull(repo.current(ids[0]));val first=repo.applyEdit(ids[0],mapOf("note" to "首次公共编辑"),null)
            assertEquals(2L,first.revision);assertEquals(JsonPrimitive("首次公共编辑"),first.values["note"])
            assertEquals("entry_changed",assertFailsWith<CashPolicyError> {repo.applyEdit(ids[0],mapOf("note" to "旧值"),null)}.code)
            assertSame(first,repo.applyEdit(ids[0],mapOf("counterparty_account_attrs" to listOf("首项","次项")),2))
            assertEquals(JsonArray(listOf(JsonPrimitive("首项"),JsonPrimitive("次项"))),first.values["counterparty_account_attrs"])
        }
    }
    @Test fun revisionOverflowRollsBackProtectionAndGroupChanges()=inWorkspace {db,workspace,_ ->val account=deletionAccount(db,workspace)
        val ids=db.write {c ->val (_,ids)=seed(c,db,workspace,account);c.prepareStatement("UPDATE cash_entries SET revision=? WHERE workspace_id=?").use {it.setLong(1,Long.MAX_VALUE);it.setString(2,workspace);it.executeUpdate()};ids}
        val before=db.read {c ->CashEntryRepository(c,workspace,db.sqlite).snapshot(ids)}
        assertFailsWith<ArithmeticException> {db.write {c ->CashEntryRepository(c,workspace,db.sqlite).patch(ids[0],mapOf("note" to "不应保存"),Long.MAX_VALUE)}}
        assertEquals(before,db.read {c ->CashEntryRepository(c,workspace,db.sqlite).snapshot(ids)})
        assertFailsWith<ArithmeticException> {db.write {c ->CashEntryRepository(c,workspace,db.sqlite).reconcile(projections(ids,account,true))}}
        assertEquals(before,db.read {c ->CashEntryRepository(c,workspace,db.sqlite).snapshot(ids)})
    }
}
