package com.finance.tracker.backend

import java.math.BigDecimal
import java.nio.file.Path
import java.sql.Connection
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashProjectionGraphReadMigrationTest {
    private val membershipExecutions=mutableListOf<Int>()
    private fun invoke(target:Any,method:java.lang.reflect.Method,args:Array<out Any?>?):Any?=try {method.invoke(target,*(args ?: emptyArray()))} catch(f:java.lang.reflect.InvocationTargetException) {throw f.targetException}
    private fun observed(c:Connection):Connection=java.lang.reflect.Proxy.newProxyInstance(Connection::class.java.classLoader,arrayOf(Connection::class.java)) {_,method,args ->
        val result=invoke(c,method,args)
        if(result is java.sql.PreparedStatement && "cash_projection_members" in (args?.firstOrNull() as? String).orEmpty()) {
            val bindings=mutableSetOf<Int>()
            java.lang.reflect.Proxy.newProxyInstance(java.sql.PreparedStatement::class.java.classLoader,arrayOf(java.sql.PreparedStatement::class.java)) {_,operation,values ->
                if(operation.declaringClass==java.sql.PreparedStatement::class.java && operation.name.startsWith("set") && values?.firstOrNull() is Int) bindings.add(values[0] as Int)
                if(operation.name=="clearParameters") bindings.clear()
                if(operation.name in setOf("executeQuery","execute","executeUpdate","executeLargeUpdate")) membershipExecutions.add(bindings.size)
                invoke(result,operation,values)
            }
        } else result
    } as Connection
    private data class Part(val id:Long,val parent:Long,val type:String)
    private inner class Fixture(val db:Database,val workspace:String,val account:Long,val otherAccount:Long,val baseline:Set<Long>) {
        val parts=mutableMapOf<Long,List<Part>>()
        fun fact(c:Connection,amount:String,aggregate:Boolean=false):Long {
            val id=deletionFact(c,db,workspace,account,null)
            c.prepareStatement("UPDATE cash_transactions SET amount=?,account_id=?,cash_granularity=?,record_type=?,source_type='graph-read-fixture',record_id=? WHERE workspace_id=? AND id=?").use {
                it.setBigDecimal(1,BigDecimal(amount));if(aggregate) it.setNull(2,java.sql.Types.BIGINT) else it.setLong(2,account)
                it.setString(3,if(aggregate) "aggregate" else "atomic");it.setString(4,if(BigDecimal(amount).signum()>0) "refund" else "consumption");it.setString(5,id.toString());it.setString(6,workspace);it.setLong(7,id);assertEquals(1,it.executeUpdate())
            };return id
        }
        fun part(c:Connection,parent:Long,amount:String,accountId:Long=account):Part {
            val ordinal=parts[parent].orEmpty().size;val id=parent*100+ordinal+1_000_000_000_000L
            deletionInsert(c,db,"cash_transaction_components",linkedMapOf("id" to id,"workspace_id" to workspace,"cash_transaction_id" to parent,"account_id" to accountId,
                "amount" to BigDecimal(amount),"currency" to "CNY","ordinal" to ordinal,"label" to "","source_key" to "","metadata_json" to JsonObject(emptyMap()),"created_at" to deletionTime))
            val result=Part(id,parent,if(BigDecimal(amount).signum()>0) "refund" else "consumption");parts[parent]=parts[parent].orEmpty()+result;return result
        }
        fun atomic(c:Connection,amount:String):Part=part(c,fact(c,amount),amount)
        fun edge(c:Connection,a:Part,b:Part,kind:String="payment_mirror",amount:String="100"):Long {
            val id=deletionRelation(c,db,workspace,a.parent,b.parent)
            c.prepareStatement("UPDATE transaction_relations SET kind=?,state='confirmed',primary_component_id=?,secondary_component_id=?,anchor_component_id=?,primary_role=?,secondary_role=?,applied_amount=? WHERE workspace_id=? AND id=?").use {
                it.setString(1,kind);it.setLong(2,a.id);it.setLong(3,b.id);it.setLong(4,a.id);it.setString(5,if(a.type=="refund") "refund" else "expense");it.setString(6,if(b.type=="refund") "refund" else "expense");it.setBigDecimal(7,BigDecimal(amount));it.setString(8,workspace);it.setLong(9,id);assertEquals(1,it.executeUpdate())
            };return id
        }
        fun deactivate(c:Connection,ids:List<Long>) {for(id in ids) c.prepareStatement("UPDATE transaction_relations SET active=false,row_revision=row_revision+1 WHERE workspace_id=? AND id=?").use {it.setString(1,workspace);it.setLong(2,id);assertEquals(1,it.executeUpdate())}}
        fun active():Pair<Map<String,Pair<BigDecimal,Int>>,Map<Long,Long>> =db.read {c ->
            val dataset=ProjectionStateRepository(c,workspace,db.sqlite).ready()!!.first
            val rows=c.prepareStatement("SELECT projection_id,net_amount,member_count FROM cash_projections WHERE workspace_id=? AND dataset_id=?").use {
                it.setString(1,workspace);it.setString(2,dataset);it.executeQuery().use {r ->buildMap {while(r.next()) put(r.getString(1),r.getBigDecimal(2).stripTrailingZeros() to r.getInt(3))}}
            }
            val members=c.prepareStatement("SELECT cash_transaction_id,projection_row_id FROM cash_projection_members WHERE workspace_id=? AND dataset_id=?").use {
                it.setString(1,workspace);it.setString(2,dataset);it.executeQuery().use {r ->buildMap {while(r.next()) put(r.getLong(1),r.getLong(2))}}
            };rows to members
        }
        fun assertValue(root:Long,amount:String,members:Int) {assertEquals(BigDecimal(amount).stripTrailingZeros() to members,active().first["cash:$root"])}
    }
    private fun inWorkspace(action:Fixture.()->Unit) {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"),::observed)
        val service=WorkspaceService(db);val name="持久化关系图测试";val workspace=service.create("synthetic-admin",name).activeWorkspaceId!!
        val account=deletionAccount(db,workspace)
        try {
            val other=db.write {c ->deletionInsert(c,db,"accounts",linkedMapOf("workspace_id" to workspace,"name" to "第二账户","type" to "cash","active" to true,"revision" to 1L,"currencies" to JsonArray(listOf(JsonPrimitive("CNY"))),"metadata_json" to JsonObject(emptyMap()),"created_at" to deletionTime,"updated_at" to deletionTime))!!}
            val baseline=db.write {c ->List(3) {deletionFact(c,db,workspace,account,null)}.toSet()}
            Fixture(db,workspace,account,other,baseline).action()
        } finally {service.delete("synthetic-admin",name);service.select("synthetic-admin","fixture-a")}
    }
    @Test fun persistedEqualRealAllocationsAreAddedOnEveryRebuild()=inWorkspace {
        val root=db.write {c ->val expense=fact(c,"-100",true);val refund=fact(c,"40",true)
            val a=part(c,expense,"-50");val b=part(c,expense,"-50",otherAccount);val x=part(c,refund,"20");val y=part(c,refund,"20",otherAccount)
            edge(c,a,x,"refund_offset","20");edge(c,b,y,"refund_offset","20");expense
        }
        repeat(2) {CashProjectionService(db,workspace).rebuild();assertValue(root,"-60",2)}
        db.read {c ->val sources=CashProjectionSourceRepository(CashSqlContext(c,workspace,db.sqlite)).readSources();assertEquals(2,sources.relations.map {it.refundAllocationKey}.toSet().size)}
    }
    @Test fun persistedPartialDiamondKeepsTheNoncanonicalAllocationDuringFullAndLocalBuilds()=inWorkspace {
        val ids=db.write {c ->val a=atomic(c,"-100");val b=atomic(c,"-100");val x=atomic(c,"100");val y=atomic(c,"100")
            edge(c,b,y,"refund_offset","40");edge(c,a,x,"refund_offset","40");edge(c,a,b);edge(c,x,y);listOf(a.parent,b.parent,x.parent,y.parent)
        }
        CashProjectionService(db,workspace).rebuild();assertValue(ids[0],"-60",4)
        db.write {CashProjectionService.maintainInSession(CashSqlContext(it,workspace,db.sqlite),setOf(ids[3]))};assertValue(ids[0],"-60",4)
    }
    @Test fun localSplitRebuildsAllOldMembersWhenOnlyOneSeedIsSupplied()=inWorkspace {
        val (ids,relation)=db.write {c ->val a=atomic(c,"-100");val b=atomic(c,"-100");listOf(a.parent,b.parent) to edge(c,a,b)}
        CashProjectionService(db,workspace).rebuild();assertEquals(2,active().first.getValue("cash:${ids[0]}").second)
        db.write {c ->deactivate(c,listOf(relation));CashProjectionService.maintainInSession(CashSqlContext(c,workspace,db.sqlite),setOf(ids[0]))}
        val (rows,members)=active();assertEquals(BigDecimal("-1E+2") to 1,rows["cash:${ids[0]}"]);assertEquals(rows["cash:${ids[0]}"],rows["cash:${ids[1]}"])
        assertNotEquals(members[ids[0]],members[ids[1]]);assertEquals(5,members.size)
    }
    @Test fun localClosureReachesOldMembersOfANewConfirmedNeighbour()=inWorkspace {
        val (parts,old)=db.write {c ->val p=List(4) {atomic(c,"-100")};p to listOf(edge(c,p[0],p[1]),edge(c,p[2],p[3]))}
        CashProjectionService(db,workspace).rebuild()
        db.write {c ->deactivate(c,old);edge(c,parts[1],parts[2]);CashProjectionService.maintainInSession(CashSqlContext(c,workspace,db.sqlite),setOf(parts[0].parent))}
        assertValue(parts[0].parent,"-100",1);assertValue(parts[3].parent,"-100",1);assertValue(parts[1].parent,"-100",2)
        assertEquals(parts.map {it.parent}.toSet()+baseline,active().second.keys)
    }
    @Test fun componentBudgetFailureCannotReplaceTheLastValidDataset()=inWorkspace {
        val parts=db.write {c ->val expense=fact(c,"-100",true);val a=part(c,expense,"-10");part(c,expense,"-90",otherAccount);a to atomic(c,"20")}
        val service=CashProjectionService(db,workspace);val before=service.rebuild()
        db.write {edge(it,parts.first,parts.second,"refund_offset","20")}
        assertEquals("projection.invalid_relation",assertFailsWith<CashProjectionDomainFailure> {service.rebuild()}.code)
        assertEquals(before["active_dataset_id"],service.status()["active_dataset_id"])
    }
    @Test fun alternatingOldAndNewGroupsHaveBoundedMembershipReadCost()=inWorkspace {
        val (parts,old)=db.write {c ->val p=List(80) {atomic(c,"-100")};p to (0 until 80 step 2).map {edge(c,p[it],p[it+1])}}
        CashProjectionService(db,workspace).rebuild()
        db.write {c ->deactivate(c,old);for(index in 1 until 79 step 2) edge(c,parts[index],parts[index+1])
            val repository=CashProjectionRepository(CashSqlContext(c,workspace,db.sqlite));val state=repository.requireReadyStateLock();membershipExecutions.clear()
            c.prepareStatement("SELECT count(*) FROM cash_projection_members WHERE workspace_id=? AND dataset_id=?").use {
                it.setString(1,workspace);it.setString(2,state.datasetId)
                repeat(2) {_ ->it.executeQuery().use {r ->assertTrue(r.next())}}
            }
            assertEquals(listOf(2,2),membershipExecutions,"each actual execution of a reused statement must be counted")
            membershipExecutions.clear()
            assertEquals(parts.map {it.parent}.toSet(),repository.sources.affectedTransactionClosure(setOf(parts[0].parent),state.datasetId))
            assertTrue(membershipExecutions.isNotEmpty(),"must observe actual membership SQL execution")
            val parameters=membershipExecutions.sum()
            assertTrue(parameters<=12*parts.size,"membership parameter budget: $parameters > ${12*parts.size}")
        }
    }
    @Test fun readerRejectsIncompatiblePersistedTypeWithoutReclassifyingIt()=inWorkspace {
        val root=db.write {c ->val a=atomic(c,"-100");val b=atomic(c,"-100");edge(c,a,b);a.parent}
        db.write {c ->c.prepareStatement("UPDATE cash_transactions SET record_type='other' WHERE workspace_id=? AND id=?").use {it.setString(1,workspace);it.setLong(2,root);it.executeUpdate()}
            assertEquals("projection.invalid_relation",assertFailsWith<CashProjectionDomainFailure> {CashProjectionSourceRepository(CashSqlContext(c,workspace,db.sqlite)).readSources()}.code)
            c.prepareStatement("SELECT record_type FROM cash_transactions WHERE workspace_id=? AND id=?").use {it.setString(1,workspace);it.setLong(2,root);it.executeQuery().use {r ->assertTrue(r.next());assertEquals("other",r.getString(1))}}
        }
    }
}
