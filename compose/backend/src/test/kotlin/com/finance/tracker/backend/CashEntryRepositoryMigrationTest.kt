package com.finance.tracker.backend

import com.finance.tracker.business.contracts.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
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
            val snapshots=repo.snapshot(ids)
            assertTrue(snapshots.all {it.digest.matches(Regex("[0-9a-f]{64}"))})
            assertEquals(snapshots.map {it.digest},repo.snapshot(ids).map {it.digest})
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
    @Test fun splittingKeepsOldIdentityAnchorAndRestoresOrInheritsExplicitHumanValues() {
        for(inherit in listOf(false,true)) inWorkspace {db,workspace,_ ->val account=deletionAccount(db,workspace)
            db.write {c ->val (repo,ids)=seed(c,db,workspace,account)
                repo.patch(ids[0],mapOf("note" to "各自甲"),1);repo.patch(ids[1],mapOf("note" to "各自甲"),1)
                val group=repo.reconcile(projections(ids,account,true)).values.single();val oldId=group.id;val oldAnchor=group.identityTransactionId
                repo.patch(ids[0],mapOf("note" to "合并公共值"),group.revision)
                assertEquals("inheritance_required",assertFailsWith<CashPolicyError> {repo.prepareSplit(ids,null)}.code)
                repo.prepareSplit(ids,inherit)
                val diffs=repo.splitFieldDiffs(projections(ids,account,false))
                assertEquals(2,diffs.size);assertTrue(diffs.all {it.field=="note" && it.current==JsonPrimitive("合并公共值") && it.restored==JsonPrimitive("各自甲")})
                val split=repo.reconcile(projections(ids,account,false).reversed())
                val first=split.getValue("cash:${ids[0]}");val second=split.getValue("cash:${ids[1]}")
                assertEquals(oldId,split.getValue("cash:$oldAnchor").id)
                assertNotEquals(first.id,second.id)
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
    @Test fun commonChoiceKeepsStandaloneValuesAndTheOriginalEmptyPatchFlushBoundary()=inWorkspace {db,workspace,_ ->
        val account=deletionAccount(db,workspace)
        db.write {c ->
            val (repo,ids)=seed(c,db,workspace,account);val entry=repo.current(ids[0])!!
            val member=repo.forTransactions(ids).first.getValue(ids[0]);val original=member.copy()
            repo.autoFlush=false;entry.values=JsonObject(entry.values+mapOf("note" to JsonPrimitive("pending")))
            fun storedNote():String=c.prepareStatement("SELECT note FROM cash_entries WHERE workspace_id=? AND id=?").use {
                it.setString(1,workspace);it.setString(2,entry.id);it.executeQuery().use {r ->assertTrue(r.next());r.getString(1)}
            }
            repo.chooseCommon(listOf(ids[0]),mapOf("amount" to "ignored"));assertEquals("",storedNote())
            repo.chooseCommon(emptyList(),mapOf("note" to "unused"));assertEquals("pending",storedNote())
            val revision=entry.revision
            repo.chooseCommon(listOf(ids[0]),mapOf("note" to "chosen"))
            assertEquals(revision+1,entry.revision);assertEquals("chosen",storedNote());assertEquals(original,member)
            repo.patch(ids[0],mapOf("note" to "edited"),entry.revision)
            assertEquals(JsonPrimitive("edited"),member.standaloneValues["note"])
            assertEquals(listOf("note"),member.standaloneManualFields)
        }
    }
    @Test fun preMergeCommonChoiceCanRetainTheNewSingletonIdentityWithoutAHumanConflict() {
        for(choose in listOf(false,true)) inWorkspace {db,workspace,_ ->
            val account=deletionAccount(db,workspace)
            db.write {c ->
                val ids=(1..2).map {deletionFact(c,db,workspace,account,null)}
                val allocated=listOf("z-old","a-new").iterator()
                val repo=CashEntryRepository(c,workspace,db.sqlite) {allocated.next()}
                val old=repo.reconcile(projections(listOf(ids[1]),account,false)).values.single()
                repo.patch(ids[1],mapOf("note" to "kept"),old.revision)
                if(choose) repo.chooseCommon(ids,mapOf("note" to "kept"))
                val grouped=repo.reconcile(projections(ids,account,true)).values.single()
                assertEquals(if(choose) "a-new" else "z-old",grouped.id)
                assertEquals(ids[0],grouped.rootTransactionId)
                assertEquals(ids[if(choose) 0 else 1],grouped.identityTransactionId)
                assertEquals(JsonPrimitive("kept"),grouped.values["note"])
                assertEquals(listOf("note"),grouped.manualFields)
                val members=repo.forTransactions(ids).first
                assertTrue(members.values.all {it.entryId==grouped.id})
                assertEquals(JsonPrimitive(""),members.getValue(ids[0]).standaloneValues["note"])
                assertEquals(emptyList(),members.getValue(ids[0]).standaloneManualFields)
                assertEquals(JsonPrimitive("kept"),members.getValue(ids[1]).standaloneValues["note"])
                assertEquals(listOf("note"),members.getValue(ids[1]).standaloneManualFields)
            }
        }
    }
    @Test fun rowPublicEditAndMergedPublicEditPreserveDifferentStandaloneAndSourceNotes()=inWorkspace {db,workspace,_ ->
        val account=deletionAccount(db,workspace)
        val ids=db.write {c ->
            val (repo,ids)=seed(c,db,workspace,account)
            c.prepareStatement("UPDATE cash_transactions SET note=? WHERE workspace_id=? AND id=?").use {
                it.setString(1,"S");it.setString(2,workspace);it.setLong(3,ids[0]);assertEquals(1,it.executeUpdate())
            }
            repo.applyEdit(ids[0],mapOf("note" to "C"),repo.current(ids[0])!!.revision)
            repo.chooseCommon(ids,mapOf("note" to "C"))
            val combined=repo.reconcile(projections(ids,account,true)).values.single()
            repo.patch(ids[0],mapOf("note" to "D"),combined.revision)
            ids
        }
        db.read {c ->
            val repo=CashEntryRepository(c,workspace,db.sqlite);val (members,entries)=repo.forTransactions(ids)
            assertEquals(1,entries.size);val entry=entries.values.single()
            assertEquals(JsonPrimitive("D"),entry.values["note"]);assertEquals(listOf("note"),entry.manualFields)
            assertEquals(JsonPrimitive("C"),members.getValue(ids[0]).standaloneValues["note"])
            assertEquals(listOf("note"),members.getValue(ids[0]).standaloneManualFields)
            assertEquals(JsonPrimitive(""),members.getValue(ids[1]).standaloneValues["note"])
            assertEquals(emptyList(),members.getValue(ids[1]).standaloneManualFields)
            c.prepareStatement("SELECT note FROM cash_transactions WHERE workspace_id=? AND id=?").use {
                it.setString(1,workspace);it.setLong(2,ids[0]);it.executeQuery().use {r ->assertTrue(r.next());assertEquals("S",r.getString(1));assertFalse(r.next())}
            }
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

    private fun writeEntryDeclaration(ids:List<Long>,root:Long,note:String="D",standalone:String="C"):CashWriteSetDto {
        fun reference(id:Long)=CashWriteVersionedReferenceDto(
            if(id==root) NewCashWriteReferenceDto("allocated-root") else ExistingCashWriteReferenceDto(id.toString()),null)
        return CashWriteSetDto(entries=listOf(CashEntryWriteDto(reference(root),null,note,"\u5546\u5bb6","",JsonArray(emptyList()),
            listOf("note"),ids.map {id ->CashEntryMemberWriteDto(reference(id),buildJsonObject {
                put("category_id",JsonNull);put("note",if(id==ids[0]) standalone else "");put("counterparty","\u5546\u5bb6")
                put("counterparty_account","");put("counterparty_account_attrs",JsonArray(emptyList()))
            },if(id==ids[0]) listOf("note") else emptyList())},listOf("note"))))
    }
    private fun resolveWriteEntry(reference:CashWriteReferenceDto,root:Long):Long=when(reference) {
        is NewCashWriteReferenceDto -> {assertEquals("allocated-root",reference.ref);root}
        is ExistingCashWriteReferenceDto ->reference.id.toLong()
    }
    private fun ledgerState(db:Database,workspace:String)=db.read {c ->
        val version=c.prepareStatement("SELECT ledger_seq FROM workspaces WHERE id=?").use {
            it.setString(1,workspace);it.executeQuery().use {r ->assertTrue(r.next());r.getLong(1)}
        }
        version to LedgerSnapshotRepository(c,workspace,db.sqlite).facts()
    }
    @Test fun writeSetEntryFinishPersistsSeparateSourcePreMergeAndFinalValuesWithoutMaterialization()=inWorkspace {db,workspace,statements ->
        val account=deletionAccount(db,workspace)
        val ids=db.write {c ->val (_,ids)=seed(c,db,workspace,account)
            c.prepareStatement("UPDATE cash_transactions SET note='S' WHERE workspace_id=? AND id=?").use {
                it.setString(1,workspace);it.setLong(2,ids[0]);assertEquals(1,it.executeUpdate())
            };ids}
        val before=ledgerState(db,workspace)
        db.write {c ->
            val context=CashSqlContext(c,workspace,db.sqlite);val repo=context.entries
            repo.applyEdit(ids[0],mapOf("note" to "C"),repo.current(ids[0])!!.revision)
            repo.chooseCommon(ids,mapOf("note" to "B"))
            val groups=projections(ids,account,true);val root=groups.single().primaryRecord.id.storedCashId()
            val resolved=mutableListOf<CashWriteReferenceDto>();statements.clear()
            finishCashImportWriteSetEntries(context,writeEntryDeclaration(ids,root),groups) {ref ->
                assertTrue(ref !in resolved,"References must be resolved once before writes");resolved+=ref;resolveWriteEntry(ref,root)
            }
            assertEquals(2,resolved.size);assertTrue(statements.none {"cash_projection" in it.lowercase()})
        }
        val after=ledgerState(db,workspace);assertEquals(before.first+1,after.first)
        val facts=after.second;val members=facts.cashEntryMembers.filter {it.transactionId in ids.map(Long::toString)}
        assertEquals(1,members.map {it.entryId}.toSet().size)
        val entry=facts.cashEntries.single {it.id==members.first().entryId}
        assertEquals("D",entry.note);assertEquals(JsonArray(listOf(JsonPrimitive("note"))),entry.manualFields)
        assertEquals(JsonPrimitive("C"),members.single {it.transactionId==ids[0].toString()}.standaloneValues.jsonObject["note"])
        assertEquals(JsonPrimitive(""),members.single {it.transactionId==ids[1].toString()}.standaloneValues.jsonObject["note"])
        assertEquals("S",facts.cashTransactions.single {it.id==ids[0].toString()}.note)
    }
    @Test fun writeSetEntryLateEffectFailureRollsBackAllEarlierStagesAndVersion()=inWorkspace {db,workspace,_ ->
        val account=deletionAccount(db,workspace);val ids=db.write {c ->seed(c,db,workspace,account).second}
        val before=ledgerState(db,workspace)
        val failure=assertFailsWith<CashPolicyError> {db.write {c ->
            val context=CashSqlContext(c,workspace,db.sqlite);val repo=context.entries
            repo.applyEdit(ids[0],mapOf("note" to "C"),repo.current(ids[0])!!.revision);repo.chooseCommon(ids,mapOf("note" to "B"))
            val groups=projections(ids,account,true);val root=groups.single().primaryRecord.id.storedCashId()
            finishCashImportWriteSetEntries(context,writeEntryDeclaration(ids,root,standalone="forged"),groups) {resolveWriteEntry(it,root)}
        }}
        assertEquals("entry_result_invalid",failure.code);assertEquals(before,ledgerState(db,workspace))
    }
    @Test fun writeSetEntryPartitionMismatchIsRejectedBeforeEntryDml()=inWorkspace {db,workspace,statements ->
        val account=deletionAccount(db,workspace);val ids=db.write {c ->seed(c,db,workspace,account).second}
        val before=ledgerState(db,workspace)
        val failure=assertFailsWith<CashPolicyError> {db.write {c ->
            val context=CashSqlContext(c,workspace,db.sqlite);val groups=projections(ids,account,false)
            val root=groups.first().primaryRecord.id.storedCashId();statements.clear()
            finishCashImportWriteSetEntries(context,writeEntryDeclaration(listOf(root),root),groups) {resolveWriteEntry(it,root)}
        }}
        assertEquals("entry_result_invalid",failure.code)
        assertTrue(statements.none {it.trimStart().lowercase().let {s ->s.startsWith("insert ") || s.startsWith("update ") || s.startsWith("delete ")}})
        assertEquals(before,ledgerState(db,workspace))
    }
    @Test fun writeSetSingletonEqualFinalPatchDoesNotReplaceStandaloneOrAdvanceRevision()=inWorkspace {db,workspace,_ ->
        val account=deletionAccount(db,workspace)
        db.write {c ->
            val (_,all)=seed(c,db,workspace,account);val ids=listOf(all[0]);val context=CashSqlContext(c,workspace,db.sqlite);val repo=context.entries
            repo.applyEdit(ids[0],mapOf("note" to "C"),repo.current(ids[0])!!.revision);repo.chooseCommon(ids,mapOf("note" to "B"))
            val before=repo.current(ids[0])!!.revision
            finishCashImportWriteSetEntries(context,writeEntryDeclaration(ids,ids[0],note="B"),projections(ids,account,false)) {resolveWriteEntry(it,ids[0])}
            assertEquals(before,repo.current(ids[0])!!.revision)
            assertEquals(JsonPrimitive("C"),repo.forTransactions(ids).first.getValue(ids[0]).standaloneValues["note"])
        }
    }
    @Test fun writeSetEntryRevisionOverflowPropagatesAndRollsBack()=inWorkspace {db,workspace,_ ->
        val account=deletionAccount(db,workspace);val ids=db.write {c ->val (_,ids)=seed(c,db,workspace,account)
            c.prepareStatement("UPDATE cash_entries SET revision=? WHERE workspace_id=?").use {
                it.setLong(1,Long.MAX_VALUE);it.setString(2,workspace);it.executeUpdate()
            };ids}
        val before=ledgerState(db,workspace)
        assertFailsWith<ArithmeticException> {db.write {c ->
            val groups=projections(ids,account,true);val root=groups.single().primaryRecord.id.storedCashId()
            finishCashImportWriteSetEntries(CashSqlContext(c,workspace,db.sqlite),writeEntryDeclaration(ids,root),groups) {resolveWriteEntry(it,root)}
        }}
        assertEquals(before,ledgerState(db,workspace))
    }
    @Test fun writeSetEntryRejectsAliasedRepeatedOverlappingAndInvalidRootsBeforeDml()=inWorkspace {db,workspace,statements ->
        val account=deletionAccount(db,workspace);val ids=db.write {c ->seed(c,db,workspace,account).second}
        val before=ledgerState(db,workspace)
        db.write {c ->
            val context=CashSqlContext(c,workspace,db.sqlite);val projections=projections(ids,account,true)
            val p=projections.single();val root=p.primaryRecord.id.storedCashId();val request=writeEntryDeclaration(ids,root)
            val entry=request.entries.single();val alias=entry.members.first().copy(transaction=CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("alias"),null))
            val cases=listOf(
                request.copy(entries=listOf(entry,entry)) to projections,
                request.copy(entries=listOf(entry.copy(members=entry.members+entry.members.first()))) to projections,
                request.copy(entries=listOf(entry.copy(members=entry.members+alias))) to projections,
                request.copy(entries=listOf(entry.copy(members=entry.members.filterNot {it.transaction.reference==entry.rootTransaction.reference}))) to projections,
                request to listOf(p,p),
                request to listOf(p.copy(memberIds=p.memberIds+p.memberIds.first())),
                request to listOf(p,p.copy(primaryRecord=p.members.first {it.first.id!=p.primaryRecord.id}.first)),
                request to listOf(p.copy(memberIds=p.memberIds.filterNot {it==p.primaryRecord.id})),
                CashWriteSetDto() to projections,
                request to emptyList(),
            )
            for((input,actual) in cases) {
                statements.clear()
                assertEquals("entry_result_invalid",assertFailsWith<CashPolicyError> {
                    finishCashImportWriteSetEntries(context,input,actual) {ref ->if(ref==alias.transaction.reference) ids[0] else resolveWriteEntry(ref,root)}
                }.code)
                assertTrue(statements.none {it.trimStart().lowercase().let {s ->s.startsWith("insert ") || s.startsWith("update ") || s.startsWith("delete ")}})
            }
            for(invalid in listOf(0L,-1L)) assertEquals("entry_result_invalid",assertFailsWith<CashPolicyError> {
                finishCashImportWriteSetEntries(context,request,projections) {invalid}
            }.code)
        }
        assertEquals(before,ledgerState(db,workspace))
    }
    @Test fun writeSetEntryResolutionFailurePropagatesBeforeAnyEntryWriteAndEmptyInputDoesNotRead()=inWorkspace {db,workspace,statements ->
        val account=deletionAccount(db,workspace);val ids=db.write {c ->seed(c,db,workspace,account).second}
        val before=ledgerState(db,workspace);val failure=IllegalStateException("allocation unavailable")
        assertSame(failure,assertFailsWith<IllegalStateException> {db.write {c ->
            val groups=projections(ids,account,true);val root=groups.single().primaryRecord.id.storedCashId();var calls=0;statements.clear()
            finishCashImportWriteSetEntries(CashSqlContext(c,workspace,db.sqlite),writeEntryDeclaration(ids,root),groups) {
                if(++calls==2) throw failure else resolveWriteEntry(it,root)
            }
        }})
        assertTrue(statements.none {it.trimStart().lowercase().let {s ->s.startsWith("insert ") || s.startsWith("update ") || s.startsWith("delete ")}})
        assertEquals(before,ledgerState(db,workspace))
        db.write {c ->val context=CashSqlContext(c,workspace,db.sqlite);statements.clear()
            finishCashImportWriteSetEntries(context,CashWriteSetDto(),emptyList()) {error("No references")}
            assertTrue(statements.isEmpty())
        }
        assertEquals(before,ledgerState(db,workspace))
    }
    @Test fun writeSetEntryRejectsUnmanagedOrEndedContextEvenForEmptyInput()=inWorkspace {db,workspace,_ ->
        db.read {c ->
            val raw=Proxy.newProxyInstance(Connection::class.java.classLoader,arrayOf(Connection::class.java)) {_,method,args ->
                try {method.invoke(c,*(args ?: emptyArray()))} catch(f:InvocationTargetException) {throw f.targetException}
            } as Connection
            assertEquals("projection.incomplete",assertFailsWith<CashProjectionFailure> {
                finishCashImportWriteSetEntries(CashSqlContext(raw,workspace,db.sqlite),CashWriteSetDto(),emptyList()) {error("No references")}
            }.code)
        }
        val ended=db.write {c ->CashSqlContext(c,workspace,db.sqlite)}
        assertEquals("projection.incomplete",assertFailsWith<CashProjectionFailure> {
            finishCashImportWriteSetEntries(ended,CashWriteSetDto(),emptyList()) {error("No references")}
        }.code)
    }
    private fun seedEntryEditRows(c:Connection,db:Database,workspace:String,account:Long):List<Long> {
        val (_,ids)=seed(c,db,workspace,account)
        for(id in ids) deletionInsert(c,db,"cash_transaction_components",linkedMapOf("workspace_id" to workspace,
            "cash_transaction_id" to id,"account_id" to account,"amount" to java.math.BigDecimal("-10"),"currency" to "CNY",
            "ordinal" to 0L,"label" to "","source_key" to "","metadata_json" to JsonObject(emptyMap()),"created_at" to deletionTime))
        return ids
    }
    private fun entryEditsForFacts(facts:LedgerFactsDto,edits:List<Pair<Long,JsonObject>>):CashWriteSetDto {
        val owners=facts.cashEntryMembers.associate {it.transactionId to it.entryId}
        val entries=facts.cashEntries.associateBy {it.id}
        val dependencies=edits.mapNotNull {(id,_) ->owners[id.toString()]}.distinct().map {id ->
            CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(id),entries.getValue(id).revision)
        }
        val rows=edits.map {(id,patch) ->
            val row=facts.cashTransactions.single {it.id==id.toString()}
            CashTransactionWriteDto(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(row.id),row.rowRevision),
                CashTransactionWriteValuesDto(row.accountId,row.state,row.recordOrigin,row.manualTransferId,row.cashGranularity,
                    row.sourceType,row.recordId,row.sourcePayload,row.relationMetadata,row.sourceFingerprint,row.manualOverrides,
                    row.occurredAt,row.amount,row.currency,row.counterparty,row.counterpartyAccount,row.counterpartyAccountAttrs,
                    row.note,row.categoryId,row.recordType,row.recordSubtype),
                facts.cashTransactionComponents.filter {it.cashTransactionId==row.id}.map {part ->CashComponentWriteDto(
                    ExistingCashWriteReferenceDto(part.id),part.accountId,part.amount,part.currency,part.ordinal,part.label,part.sourceKey,part.metadataJson)},
                entryEdit=CashEntryBeforeRelationEditDto(owners[row.id]?.let {owner ->dependencies.single {(it.reference as ExistingCashWriteReferenceDto).id==owner}},patch))
        }
        return CashWriteSetDto(transactions=rows,entryDependencies=dependencies)
    }
    @Test fun writeSetPreMergeEditsCoalesceOnceByRealOldOwnerAndPreserveSourcesAndStandalone()=inWorkspace {db,workspace,_ ->
        val account=deletionAccount(db,workspace);val ids=db.write {c ->val ids=seedEntryEditRows(c,db,workspace,account)
            CashEntryRepository(c,workspace,db.sqlite).reconcile(projections(ids,account,true));ids}
        val before=ledgerState(db,workspace);val old=before.second.cashEntries.single()
        val changes=entryEditsForFacts(before.second,listOf(ids[0] to buildJsonObject {put("note","C")},
            ids[1] to buildJsonObject {put("counterparty","C-party")}))
        db.write {c ->applyCashImportWriteSetEntryEdits(CashSqlContext(c,workspace,db.sqlite),changes,before.second) {
            (it as ExistingCashWriteReferenceDto).id.toLong()
        }}
        val after=ledgerState(db,workspace);val entry=after.second.cashEntries.single()
        assertEquals(old.revision.toLong()+1,entry.revision.toLong());assertEquals("C",entry.note);assertEquals("C-party",entry.counterparty)
        assertEquals(before.second.cashTransactions,after.second.cashTransactions)
        assertEquals(before.second.cashEntryMembers,after.second.cashEntryMembers);assertEquals(before.first+1,after.first)
        assertNotNull(changes.transactions.last().entryEdit)
    }
    @Test fun writeSetPreMergeEditsRejectLaterOldObservationAndConflictingPatchBeforeDml()=inWorkspace {db,workspace,statements ->
        val account=deletionAccount(db,workspace);val ids=db.write {c ->val ids=seedEntryEditRows(c,db,workspace,account)
            CashEntryRepository(c,workspace,db.sqlite).reconcile(projections(ids,account,true));ids}
        val before=ledgerState(db,workspace)
        val changes=entryEditsForFacts(before.second,ids.map {it to buildJsonObject {put("note","C")}})
        val later=changes.transactions.last();val edit=later.entryEdit!!
        val observation=edit.entry!!
        val bad=changes.copy(transactions=listOf(changes.transactions.first(),later.copy(entryEdit=edit.copy(
            entry=observation.copy(expectedRevision=(observation.expectedRevision!!.toLong()+1).toString())))))
        db.write {c ->val context=CashSqlContext(c,workspace,db.sqlite);statements.clear()
            val failure=assertFailsWith<com.finance.tracker.business.domain.CashWriteSetReferenceFailure> {
                applyCashImportWriteSetEntryEdits(context,bad,before.second) {error("Validation must precede resolution")}
            }
            assertEquals("reference_changed",failure.code);assertEquals("transactions[1].entry_edit.entry",failure.fieldPath)
            val conflict=changes.copy(transactions=listOf(changes.transactions.first(),later.copy(entryEdit=edit.copy(patch=buildJsonObject {put("note","other")}))))
            assertFailsWith<CashImportPlanError> {applyCashImportWriteSetEntryEdits(context,conflict,before.second) {error("No resolution")}}
            assertTrue(statements.none {it.trimStart().lowercase().let {s ->s.startsWith("insert ") || s.startsWith("update ") || s.startsWith("delete ")}})
        }
        assertEquals(before,ledgerState(db,workspace))
    }
    @Test fun writeSetPreMergeEditAbsentOwnerUsesOriginalCreationAndStandaloneRules()=inWorkspace {db,workspace,_ ->
        val account=deletionAccount(db,workspace);val ids=db.write {c ->val ids=seedEntryEditRows(c,db,workspace,account)
            CashEntryRepository(c,workspace,db.sqlite).removeMembers(setOf(ids[0]));ids}
        val before=ledgerState(db,workspace);val changes=entryEditsForFacts(before.second,listOf(ids[0] to buildJsonObject {put("note","C")}))
        assertNull(changes.transactions.single().entryEdit!!.entry)
        db.write {c ->applyCashImportWriteSetEntryEdits(CashSqlContext(c,workspace,db.sqlite),changes,before.second) {(it as ExistingCashWriteReferenceDto).id.toLong()}}
        val after=ledgerState(db,workspace);val member=after.second.cashEntryMembers.single {it.transactionId==ids[0].toString()}
        val entry=after.second.cashEntries.single {it.id==member.entryId}
        assertEquals("C",entry.note);assertEquals("2",entry.revision);assertEquals(JsonPrimitive("C"),member.standaloneValues.jsonObject["note"])
        assertEquals(JsonArray(listOf(JsonPrimitive("note"))),member.standaloneManualFields)
        assertEquals(before.second.cashTransactions,after.second.cashTransactions);assertEquals(before.first+1,after.first)
    }
    @Test fun writeSetPreMergeEditsNeverReplaceOldObservationWithCurrentRevisionAndRollbackEarlierEdits()=inWorkspace {db,workspace,_ ->
        val account=deletionAccount(db,workspace);val ids=db.write {c ->seedEntryEditRows(c,db,workspace,account)}
        val before=ledgerState(db,workspace);val changes=entryEditsForFacts(before.second,ids.map {it to buildJsonObject {put("note","C")}})
        val changedOwner=before.second.cashEntryMembers.single {it.transactionId==ids[1].toString()}.entryId
        assertEquals("entry_changed",assertFailsWith<CashPolicyError> {db.write {c ->
            c.prepareStatement("UPDATE cash_entries SET revision=revision+1 WHERE workspace_id=? AND id=?").use {
                it.setString(1,workspace);it.setString(2,changedOwner);assertEquals(1,it.executeUpdate())
            }
            applyCashImportWriteSetEntryEdits(CashSqlContext(c,workspace,db.sqlite),changes,before.second) {(it as ExistingCashWriteReferenceDto).id.toLong()}
        }}.code)
        assertEquals(before,ledgerState(db,workspace))
    }
    @Test fun writeSetPreMergeResolutionGuardsAllRowsBeforeAnyEdit()=inWorkspace {db,workspace,statements ->
        val account=deletionAccount(db,workspace);val ids=db.write {c ->seedEntryEditRows(c,db,workspace,account)}
        val before=ledgerState(db,workspace);val changes=entryEditsForFacts(before.second,ids.map {it to buildJsonObject {put("note","C")}})
        val failure=IllegalStateException("allocation interrupted")
        db.write {c ->
            val context=CashSqlContext(c,workspace,db.sqlite);var calls=0;statements.clear()
            assertSame(failure,assertFailsWith<IllegalStateException> {applyCashImportWriteSetEntryEdits(context,changes,before.second) {
                if(++calls==2) throw failure else (it as ExistingCashWriteReferenceDto).id.toLong()
            }})
            assertEquals(2,calls);assertTrue(statements.isEmpty())
            for(invalid in listOf(ids[0],0L,-1L)) {
                calls=0
                assertEquals("entry_result_invalid",assertFailsWith<CashPolicyError> {
                    applyCashImportWriteSetEntryEdits(context,changes,before.second) {if(++calls==1) ids[0] else invalid}
                }.code)
                assertTrue(statements.isEmpty())
            }
        }
        assertEquals(before,ledgerState(db,workspace))
    }
    @Test fun writeSetPreMergeEmptyInputDoesNotReadAndEndedContextIsRejected()=inWorkspace {db,workspace,statements ->
        val before=ledgerState(db,workspace)
        val ended=db.write {c ->val context=CashSqlContext(c,workspace,db.sqlite);statements.clear()
            applyCashImportWriteSetEntryEdits(context,CashWriteSetDto(),before.second) {error("No references")}
            assertTrue(statements.isEmpty());context
        }
        assertEquals("projection.incomplete",assertFailsWith<CashProjectionFailure> {
            applyCashImportWriteSetEntryEdits(ended,CashWriteSetDto(),before.second) {error("No references")}
        }.code)
        assertEquals(before,ledgerState(db,workspace))
    }
    @Test fun writeSetPreMergeRevisionOverflowRollsBackTheEarlierSuccessfulEdit()=inWorkspace {db,workspace,_ ->
        val account=deletionAccount(db,workspace);val ids=db.write {c ->val ids=seedEntryEditRows(c,db,workspace,account)
            val owner=CashEntryRepository(c,workspace,db.sqlite).current(ids[1])!!.id
            c.prepareStatement("UPDATE cash_entries SET revision=? WHERE workspace_id=? AND id=?").use {
                it.setLong(1,Long.MAX_VALUE);it.setString(2,workspace);it.setString(3,owner);assertEquals(1,it.executeUpdate())
            };ids}
        val before=ledgerState(db,workspace);val changes=entryEditsForFacts(before.second,ids.map {it to buildJsonObject {put("note","C")}})
        assertFailsWith<ArithmeticException> {db.write {c ->
            applyCashImportWriteSetEntryEdits(CashSqlContext(c,workspace,db.sqlite),changes,before.second) {(it as ExistingCashWriteReferenceDto).id.toLong()}
        }}
        assertEquals(before,ledgerState(db,workspace))
    }
    @Test fun writeSetPreMergeNewReferencesUseRealAllocatedParentsAndKeepNullOwnersIndependent()=inWorkspace {db,workspace,_ ->
        val account=deletionAccount(db,workspace);val before=ledgerState(db,workspace)
        assertTrue(before.second.cashTransactions.isEmpty())
        val ids=db.write {c ->
            val allocated=(1..2).map {index ->
                val id=deletionFact(c,db,workspace,account,null)
                c.prepareStatement("UPDATE cash_transactions SET record_origin='import',record_id=? WHERE workspace_id=? AND id=?").use {
                    it.setString(1,"new-source-$index");it.setString(2,workspace);it.setLong(3,id);assertEquals(1,it.executeUpdate())
                }
                deletionInsert(c,db,"cash_transaction_components",linkedMapOf("workspace_id" to workspace,"cash_transaction_id" to id,
                    "account_id" to account,"amount" to java.math.BigDecimal("-10"),"currency" to "CNY","ordinal" to 0L,
                    "label" to "","source_key" to "","metadata_json" to JsonObject(emptyMap()),"created_at" to deletionTime))
                id
            }
            val actual=LedgerSnapshotRepository(c,workspace,db.sqlite).facts()
            val original=entryEditsForFacts(actual,allocated.mapIndexed {index,id ->id to buildJsonObject {put("note","C$index")}})
            val identities:Map<CashWriteReferenceDto,Long> = allocated.mapIndexed {index,id ->NewCashWriteReferenceDto("new-parent-$index") to id}.toMap()
            val changes=original.copy(transactions=original.transactions.mapIndexed {index,row ->row.copy(
                target=CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("new-parent-$index"),null),
                components=row.components.mapIndexed {partIndex,part ->part.copy(reference=NewCashWriteReferenceDto("part-$index-$partIndex"))})})
            assertTrue(changes.transactions.all {it.entryEdit!!.entry==null});assertTrue(changes.entryDependencies.isEmpty())
            applyCashImportWriteSetEntryEdits(CashSqlContext(c,workspace,db.sqlite),changes,before.second) {identities.getValue(it)}
            allocated
        }
        val after=ledgerState(db,workspace);assertEquals(before.first+1,after.first)
        assertEquals(2,after.second.cashEntries.size)
        for((index,id) in ids.withIndex()) {
            val member=after.second.cashEntryMembers.single {it.transactionId==id.toString()}
            val entry=after.second.cashEntries.single {it.id==member.entryId}
            assertEquals(id.toString(),entry.rootTransactionId);assertEquals("C$index",entry.note);assertEquals("2",entry.revision)
            assertEquals(JsonPrimitive("C$index"),member.standaloneValues.jsonObject["note"])
            assertEquals("",after.second.cashTransactions.single {it.id==id.toString()}.note)
        }
    }
    private fun seedFirstCommonRows(c:Connection,db:Database,workspace:String,account:Long):List<Long> {
        val ids=seedEntryEditRows(c,db,workspace,account)
        val facts=LedgerSnapshotRepository(c,workspace,db.sqlite).facts()
        val covered=facts.cashTransactionComponents.map {it.cashTransactionId}.toSet()
        for(row in facts.cashTransactions.filter {it.id !in covered}) deletionInsert(c,db,"cash_transaction_components",linkedMapOf(
            "workspace_id" to workspace,"cash_transaction_id" to row.id.toLong(),"account_id" to account,
            "amount" to row.amount!!.toBigDecimal(),"currency" to row.currency,"ordinal" to 0L,"label" to "","source_key" to "",
            "metadata_json" to JsonObject(emptyMap()),"created_at" to deletionTime))
        return ids
    }
    private fun firstMirrorChoice(facts:LedgerFactsDto,ids:List<Long>,patch:JsonObject,state:String="confirmed"):CashWriteSetDto {
        fun target(id:Long)=CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(id.toString()),facts.cashTransactions.single {it.id==id.toString()}.rowRevision)
        fun part(id:Long)=ExistingCashWriteReferenceDto(facts.cashTransactionComponents.single {it.cashTransactionId==id.toString()}.id)
        val reference=NewCashWriteReferenceDto("first-mirror")
        return CashWriteSetDto(relations=listOf(CashRelationWriteDto(CashWriteVersionedReferenceDto(reference,null),"payment_mirror",
            target(ids[0]),target(ids[1]),part(ids[0]),part(ids[1]),target(ids[0]),part(ids[0]),"expense","expense",state,true,
            null,"user",JsonObject(emptyMap()),"10","rule","user",emptyList())),relationGroups=listOf(CashRelationWriteGroupDto(listOf(reference),patch)))
    }
    @Test fun writeSetFirstCommonChoicesApplyEffectiveBAfterCWithoutChangingStandalone()=inWorkspace {db,workspace,_ ->
        val account=deletionAccount(db,workspace);val ids=db.write {c ->seedFirstCommonRows(c,db,workspace,account)}
        val before=ledgerState(db,workspace);val choice=firstMirrorChoice(before.second,ids,buildJsonObject {put("note","B")})
        val c=entryEditsForFacts(before.second,listOf(ids[0] to buildJsonObject {put("note","C")}))
        val changes=choice.copy(transactions=c.transactions,entryDependencies=c.entryDependencies)
        db.write {connection ->val context=CashSqlContext(connection,workspace,db.sqlite)
            fun resolve(ref:CashWriteReferenceDto)=(ref as ExistingCashWriteReferenceDto).id.toLong()
            applyCashImportWriteSetEntryEdits(context,changes,before.second,::resolve)
            applyCashImportWriteSetFirstCommonChoices(context,changes,before.second,::resolve)
        }
        val after=ledgerState(db,workspace);assertEquals(before.first+1,after.first)
        assertEquals(before.second.cashTransactions,after.second.cashTransactions)
        for((index,id) in ids.withIndex()) {
            val member=after.second.cashEntryMembers.single {it.transactionId==id.toString()}
            val entry=after.second.cashEntries.single {it.id==member.entryId}
            assertEquals("B",entry.note);assertEquals(if(index==0) "3" else "2",entry.revision)
            assertEquals(JsonPrimitive(if(index==0) "C" else ""),member.standaloneValues.jsonObject["note"])
        }
    }
    @Test fun writeSetFirstCommonChoicesCannotOmitPostCManualFields()=inWorkspace {db,workspace,statements ->
        val account=deletionAccount(db,workspace);val ids=db.write {c ->seedFirstCommonRows(c,db,workspace,account)}
        val before=ledgerState(db,workspace);val choice=firstMirrorChoice(before.second,ids,JsonObject(emptyMap()))
        val c=entryEditsForFacts(before.second,listOf(ids[0] to buildJsonObject {put("note","C")}))
        val changes=choice.copy(transactions=c.transactions,entryDependencies=c.entryDependencies)
        db.write {connection ->val context=CashSqlContext(connection,workspace,db.sqlite);statements.clear()
            assertEquals("entry_result_invalid",assertFailsWith<CashPolicyError> {
                applyCashImportWriteSetFirstCommonChoices(context,changes,before.second) {error("Validate before resolution")}
            }.code)
            assertTrue(statements.isEmpty())
        }
        assertEquals(before,ledgerState(db,workspace))
    }
    @Test fun writeSetFirstCommonChoicesResolveEveryParentBeforeAnyChoice()=inWorkspace {db,workspace,statements ->
        val account=deletionAccount(db,workspace);val ids=db.write {c ->seedFirstCommonRows(c,db,workspace,account)}
        val before=ledgerState(db,workspace);val changes=firstMirrorChoice(before.second,ids,buildJsonObject {put("note","B")})
        val failure=IllegalStateException("allocation interrupted")
        db.write {connection ->val context=CashSqlContext(connection,workspace,db.sqlite);statements.clear();var calls=0
            assertSame(failure,assertFailsWith<IllegalStateException> {applyCashImportWriteSetFirstCommonChoices(context,changes,before.second) {
                if(++calls==2) throw failure else (it as ExistingCashWriteReferenceDto).id.toLong()
            }})
            assertEquals(2,calls);assertTrue(statements.isEmpty())
            for(invalid in listOf(ids[0],0L,-1L)) {
                calls=0
                assertEquals("entry_result_invalid",assertFailsWith<CashPolicyError> {
                    applyCashImportWriteSetFirstCommonChoices(context,changes,before.second) {if(++calls==1) ids[0] else invalid}
                }.code)
                assertTrue(statements.isEmpty())
            }
        }
        assertEquals(before,ledgerState(db,workspace))
    }
    @Test fun writeSetFirstCommonChoicesSkipUnacceptedGroupsAndEmptyCommandsWithoutSql()=inWorkspace {db,workspace,statements ->
        val account=deletionAccount(db,workspace);val ids=db.write {c ->seedFirstCommonRows(c,db,workspace,account)}
        val before=ledgerState(db,workspace)
        db.write {connection ->val context=CashSqlContext(connection,workspace,db.sqlite);statements.clear()
            for(state in listOf("draft","ignored")) applyCashImportWriteSetFirstCommonChoices(context,
                firstMirrorChoice(before.second,ids,JsonObject(emptyMap()),state),before.second) {error("No accepted choices")}
            applyCashImportWriteSetFirstCommonChoices(context,CashWriteSetDto(),before.second) {error("No references")}
            assertTrue(statements.isEmpty())
        }
        assertEquals(before,ledgerState(db,workspace))
    }
    @Test fun writeSetFirstCommonChoicesOverflowRollsBackEarlierParentAndVersion()=inWorkspace {db,workspace,_ ->
        val account=deletionAccount(db,workspace);val ids=db.write {c ->val ids=seedFirstCommonRows(c,db,workspace,account)
            val last=ids.sortedBy(Long::toString).last();val owner=CashEntryRepository(c,workspace,db.sqlite).current(last)!!.id
            c.prepareStatement("UPDATE cash_entries SET revision=? WHERE workspace_id=? AND id=?").use {
                it.setLong(1,Long.MAX_VALUE);it.setString(2,workspace);it.setString(3,owner);assertEquals(1,it.executeUpdate())
            };ids}
        val before=ledgerState(db,workspace);val changes=firstMirrorChoice(before.second,ids,buildJsonObject {put("note","B")})
        assertFailsWith<ArithmeticException> {db.write {connection ->
            applyCashImportWriteSetFirstCommonChoices(CashSqlContext(connection,workspace,db.sqlite),changes,before.second) {(it as ExistingCashWriteReferenceDto).id.toLong()}
        }}
        assertEquals(before,ledgerState(db,workspace))
    }
    @Test fun writeSetFirstCommonChoicesDoNotCoalesceTheSameParentAcrossAcceptedGroups()=inWorkspace {db,workspace,_ ->
        val account=deletionAccount(db,workspace);val ids=db.write {c ->
            val selected=seedFirstCommonRows(c,db,workspace,account)
            val all=LedgerSnapshotRepository(c,workspace,db.sqlite).facts().cashTransactions.map {it.id.toLong()}
            val third=all.single {it !in selected}
            CashEntryRepository(c,workspace,db.sqlite).reconcile(projections(listOf(third),account,false));selected+third
        }
        val before=ledgerState(db,workspace);val patch=buildJsonObject {put("note","B")}
        val first=firstMirrorChoice(before.second,ids.take(2),patch)
        val other=firstMirrorChoice(before.second,listOf(ids[0],ids[2]),patch).relations.single()
        val reference=NewCashWriteReferenceDto("second-mirror")
        val second=other.copy(target=CashWriteVersionedReferenceDto(reference,null),anchorTransaction=other.secondaryTransaction!!,
            anchorComponent=other.secondaryComponent)
        val changes=first.copy(relations=first.relations+second,relationGroups=listOf(CashRelationWriteGroupDto(listOf(reference),patch))+first.relationGroups)
        val resolved=mutableListOf<CashWriteReferenceDto>()
        db.write {c ->applyCashImportWriteSetFirstCommonChoices(CashSqlContext(c,workspace,db.sqlite),changes,before.second) {ref ->
            assertTrue(ref !in resolved);resolved+=ref;(ref as ExistingCashWriteReferenceDto).id.toLong()
        }}
        assertEquals(listOf(ids[0],ids[2],ids[1]),resolved.map {(it as ExistingCashWriteReferenceDto).id.toLong()})
        val after=ledgerState(db,workspace);assertEquals(before.first+1,after.first)
        for((index,id) in ids.withIndex()) {
            val member=after.second.cashEntryMembers.single {it.transactionId==id.toString()}
            val entry=after.second.cashEntries.single {it.id==member.entryId}
            assertEquals(if(index==0) "3" else "2",entry.revision);assertEquals("B",entry.note)
        }
        assertEquals(before.second.cashEntryMembers,after.second.cashEntryMembers)
    }
    @Test fun writeSetFirstCommonChoicesAllocateMissingEntriesInActualDecimalTextOrder()=inWorkspace {db,workspace,_ ->
        val account=deletionAccount(db,workspace);val ids=db.write {c ->val ids=seedFirstCommonRows(c,db,workspace,account)
            CashEntryRepository(c,workspace,db.sqlite).removeMembers(ids.toSet());ids}
        val before=ledgerState(db,workspace);val changes=firstMirrorChoice(before.second,ids.sortedBy(Long::toString).reversed(),buildJsonObject {put("note","B")})
        val entryIds=listOf("$workspace:first","$workspace:second");val allocations=entryIds.iterator()
        db.write {c ->applyCashImportWriteSetFirstCommonChoices(CashSqlContext(c,workspace,db.sqlite,{allocations.next()},{deletionTime}),
            changes,before.second) {(it as ExistingCashWriteReferenceDto).id.toLong()}}
        assertFalse(allocations.hasNext());val after=ledgerState(db,workspace)
        for((index,id) in ids.sortedBy(Long::toString).withIndex()) {
            val member=after.second.cashEntryMembers.single {it.transactionId==id.toString()}
            assertEquals(entryIds[index],member.entryId)
            assertEquals(JsonPrimitive(""),member.standaloneValues.jsonObject["note"])
        }
        assertEquals(before.first+1,after.first)
    }
}
