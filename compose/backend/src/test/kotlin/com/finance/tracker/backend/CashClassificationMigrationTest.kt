package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashClassificationMigrationTest {
    private fun inWorkspace(decorateConnection:(java.sql.Connection)->java.sql.Connection={it},action:(Database,String)->Unit) {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"),decorateConnection)
        val workspaces=WorkspaceService(db);val workspace=workspaces.create("synthetic-admin","分类命令测试").activeWorkspaceId!!
        try {action(db,workspace)} finally {setAccountTestRole(db,workspace,"admin");workspaces.delete("synthetic-admin","分类命令测试");workspaces.select("synthetic-admin","fixture-a")}
    }
    private fun snapshot(db:Database,workspace:String)=db.read {c ->
        listOf("cash_categories","cash_category_states","cash_transactions","cash_entries","cash_entry_members","cash_projection_states","cash_projections","cash_projection_members","transaction_relations","cash_relation_candidates").associateWith {table ->
            c.prepareStatement("SELECT * FROM $table WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {r ->buildList {while(r.next()) add((1..r.metaData.columnCount).map {r.getString(it)})}.sortedBy {it.toString()}}}
        }
    }
    private data class Fixture(val facts:List<Long>,val projections:List<String>,val sourceCategory:String,val targetCategory:String)
    private fun fixture(db:Database,workspace:String):Fixture {
        val categories=CategoryService(db);val dining=categories.create("synthetic-admin","餐饮");val shopping=categories.create("synthetic-admin","购物")
        val account=deletionAccount(db,workspace)
        return db.write {c ->
            val dataset=c.prepareStatement("SELECT active_dataset_id FROM cash_projection_states WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {rows ->rows.next();rows.getString(1)}}
            val facts=(1..3).map {deletionFact(c,db,workspace,account,dining.id)}
            deletionEntry(c,db,workspace,"classification-group",facts[0],dining.id)
            deletionEntry(c,db,workspace,"classification-single",facts[2],dining.id)
            val groups=listOf(facts.take(2),facts.takeLast(1));val ids=mutableListOf<String>()
            for((index,members) in groups.withIndex()) {
                val root=members.first();val row=deletionProjection(c,db,workspace,dataset,root,account,dining.id,7)
                c.prepareStatement("UPDATE cash_projections SET member_count=? WHERE id=?").use {it.setInt(1,members.size);it.setLong(2,row);it.executeUpdate()}
                ids+="cash:$root"
                for((ordinal,fact) in members.withIndex()) {
                    deletionInsert(c,db,"cash_projection_members",linkedMapOf("workspace_id" to workspace,"dataset_id" to dataset,"projection_row_id" to row,"cash_transaction_id" to fact,
                        "roles_json" to JsonArray(listOf(JsonPrimitive(if(ordinal==0) "root" else "mirror"))),"ordinal" to ordinal))
                    deletionInsert(c,db,"cash_entry_members",linkedMapOf("workspace_id" to workspace,"transaction_id" to fact,"entry_id" to if(index==0) "classification-group" else "classification-single",
                        "standalone_values" to buildJsonObject {put("category_id",dining.id);put("note","恢复备注")},"standalone_manual_fields" to JsonArray(listOf(JsonPrimitive("note")))))
                }
            }
            c.prepareStatement("UPDATE cash_projection_states SET projection_version=7,source_revision=11,projection_count=2,member_count=3 WHERE workspace_id=?").use {it.setString(1,workspace);it.executeUpdate()}
            Fixture(facts,ids,dining.id,shopping.id)
        }
    }
    @Test fun classifyingProjectionChangesOnlyEntryAndStaleBatchesAreAtomic() = inWorkspace {db,workspace ->testApplication {
        val data=fixture(db,workspace)
        setAccountTestRole(db,workspace,"editor")
        application {financeModule(db)}
        suspend fun classify(ids:List<String>,version:Long,category:String?)=client.put("/api/v1/cash-projections/categories") {
            bearerAuth("synthetic-admin");contentType(ContentType.Application.Json)
            setBody(buildJsonObject {put("projection_ids",JsonArray(ids.map(::JsonPrimitive)));put("projection_version",version);put("category_id",category?.let(::JsonPrimitive) ?: JsonNull)}.toString())
        }
        val result=classify(data.projections.take(1),7,data.targetCategory)
        assertEquals(200,result.status.value,result.bodyAsText())
        assertEquals(JsonPrimitive(2),Json.parseToJsonElement(result.bodyAsText()).jsonObject["updated_transaction_count"])
        fun sourceCategories()=db.read {c ->c.prepareStatement("SELECT category_id FROM cash_transactions WHERE workspace_id=? ORDER BY id").use {it.setString(1,workspace);it.executeQuery().use {rows ->buildList {while(rows.next()) add(rows.getString(1))}}}}
        assertEquals(listOf(data.sourceCategory,data.sourceCategory),sourceCategories().take(2))
        val stale=classify(data.projections,6,null)
        assertEquals(409,stale.status.value)
        assertEquals("projection.version_conflict",Json.parseToJsonElement(stale.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals(List(3) {data.sourceCategory},sourceCategories())
    }}
    @Test fun explicitCategoryProtectsEntryIncludingSameValueAndClearWithoutWritingFacts() {
        for(clear in listOf(false,true)) inWorkspace {db,workspace ->
            val data=fixture(db,workspace);val category=if(clear) null else data.sourceCategory
            db.write {c ->
                val json=if(db.sqlite) "?" else "CAST(? AS json)"
                c.prepareStatement("UPDATE cash_transactions SET category_id=?,note='已有人工备注',manual_overrides=$json WHERE workspace_id=?").use {it.setString(1,category);it.setString(2,"""{"note":{"value":"已有人工备注"}}""");it.setString(3,workspace);it.executeUpdate()}
                c.prepareStatement("UPDATE cash_entries SET category_id=?,note='已有人工备注' WHERE workspace_id=?").use {it.setString(1,category);it.setString(2,workspace);it.executeUpdate()}
            }
            fun facts()=db.read {c ->c.prepareStatement("SELECT * FROM cash_transactions WHERE workspace_id=? ORDER BY id").use {it.setString(1,workspace);it.executeQuery().use {r ->buildList {while(r.next()) add((1..r.metaData.columnCount).map {r.getString(it)})}}}}
            db.write {c ->val relation=deletionRelation(c,db,workspace,data.facts[0]);deletionCandidate(c,db,workspace,relation,data.facts[2])}
            val before=facts()
            val result=CashClassificationService(db).setCategory("synthetic-admin",JsonArray(listOf(JsonPrimitive(data.projections[0]))),JsonPrimitive(7),category?.let(::JsonPrimitive))
            assertEquals(before,facts())
            db.read {c ->
                c.prepareStatement("SELECT category_id,manual_fields,revision FROM cash_entries WHERE workspace_id=? AND id='classification-group'").use {it.setString(1,workspace);it.executeQuery().use {r ->assertTrue(r.next());assertEquals(category,r.getString(1));assertTrue(Json.parseToJsonElement(r.getString(2)).jsonArray.contains(JsonPrimitive("category_id")));assertEquals(2L,r.getLong(3))}}
                c.prepareStatement("SELECT category_id,manual_overrides,note FROM cash_transactions WHERE workspace_id=? ORDER BY id").use {it.setString(1,workspace);it.executeQuery().use {r ->repeat(2) {
                    assertTrue(r.next());assertEquals(category,r.getString(1));val overrides=Json.parseToJsonElement(r.getString(2)).jsonObject
                    assertTrue("category_id" !in overrides);assertEquals(Json.parseToJsonElement("""{"value":"已有人工备注"}"""),overrides["note"])
                }}}
            }
            val protected=snapshot(db,workspace)
            val again=CashClassificationService(db).setCategory("synthetic-admin",JsonArray(listOf(JsonPrimitive(data.projections[0]))),result["projection_version"],category?.let(::JsonPrimitive))
            assertEquals(result,again);assertEquals(before,facts());assertEquals(protected,snapshot(db,workspace))
        }
    }

    @Test fun classificationRejectsEntryMembersOutsideSelectedProjectionWithoutChanges() = inWorkspace {db,workspace ->
        val data=fixture(db,workspace)
        db.write {c ->c.prepareStatement("UPDATE cash_entry_members SET entry_id='classification-group' WHERE workspace_id=? AND transaction_id=?").use {it.setString(1,workspace);it.setLong(2,data.facts[2]);assertEquals(1,it.executeUpdate())}}
        fun old()=db.read {c ->c.prepareStatement("SELECT category_id,revision FROM cash_entries WHERE workspace_id=? AND id='classification-group'").use {it.setString(1,workspace);it.executeQuery().use {r ->assertTrue(r.next());r.getString(1) to r.getLong(2)}}}
        val before=old()
        assertEquals("projection.version_conflict",assertFailsWith<CategoryFailure> {CashClassificationService(db).setCategory("synthetic-admin",JsonArray(listOf(JsonPrimitive(data.projections[0]))),JsonPrimitive(7),JsonPrimitive(data.targetCategory))}.code)
        assertEquals(before,old())
    }

    @Test fun singletonRestoreValuesChangeWhileGroupedMembersKeepTheirOwnValues() = inWorkspace {db,workspace ->
        val data=fixture(db,workspace)
        val result=CashClassificationService(db).setCategory("synthetic-admin",JsonArray(data.projections.map(::JsonPrimitive)),JsonPrimitive(7),JsonPrimitive(data.targetCategory))
        assertEquals(JsonPrimitive(8),result["projection_version"]);assertEquals(JsonPrimitive(2),result["projection_count"]);assertEquals(JsonPrimitive(3),result["updated_transaction_count"])
        db.read {c ->
            c.prepareStatement("SELECT transaction_id,standalone_values,standalone_manual_fields FROM cash_entry_members WHERE workspace_id=? ORDER BY transaction_id").use {it.setString(1,workspace);it.executeQuery().use {r ->for((index,id) in data.facts.withIndex()) {
                assertTrue(r.next());assertEquals(id,r.getLong(1));val value=Json.parseToJsonElement(r.getString(2)).jsonObject
                assertEquals(JsonPrimitive(if(index<2) data.sourceCategory else data.targetCategory),value["category_id"]);assertEquals(JsonPrimitive("恢复备注"),value["note"])
                assertEquals(JsonArray((if(index<2) listOf("note") else listOf("category_id","note")).map(::JsonPrimitive)),Json.parseToJsonElement(r.getString(3)))
            }}}
        }
    }

    @Test fun maintenanceVisibilityAndCurrentMembershipRejectIncompleteOrDeletedSelections() {
        for(mode in listOf("visible","attention","hidden","deleted-root","deleted-member","missing-projection-member","missing-entry-member","wrong-root","unavailable")) inWorkspace {db,workspace ->
            val data=fixture(db,workspace)
            db.write {c ->
                fun execute(sql:String,extra:Long?=null) {c.prepareStatement(sql).use {it.setString(1,workspace);extra?.let {id ->it.setLong(2,id)};it.executeUpdate()}}
                when(mode) {
                    "attention" ->execute("UPDATE cash_projections SET visible=false,attention_count=1 WHERE workspace_id=?")
                    "hidden" ->execute("UPDATE cash_projections SET visible=false,attention_count=0 WHERE workspace_id=?")
                    "deleted-root","deleted-member" ->c.prepareStatement("UPDATE cash_transactions SET deleted_at=? WHERE workspace_id=? AND id=?").use {it.instant(1,deletionTime,db.sqlite);it.setString(2,workspace);it.setLong(3,data.facts[if(mode=="deleted-root") 0 else 1]);it.executeUpdate()}
                    "missing-projection-member" ->execute("DELETE FROM cash_projection_members WHERE workspace_id=? AND cash_transaction_id=?",data.facts[1])
                    "missing-entry-member" ->execute("DELETE FROM cash_entry_members WHERE workspace_id=? AND transaction_id=?",data.facts[1])
                    "wrong-root" ->execute("UPDATE cash_entries SET root_transaction_id=${data.facts[1]} WHERE workspace_id=?")
                    "unavailable" ->execute("UPDATE cash_projection_states SET availability='uninitialized' WHERE workspace_id=?")
                }
            }
            val before=snapshot(db,workspace)
            val action={CashClassificationService(db).setCategory("synthetic-admin",JsonArray(listOf(JsonPrimitive(data.projections[0]))),JsonPrimitive(7),JsonPrimitive(data.targetCategory))}
            if(mode in setOf("visible","attention")) assertEquals(JsonPrimitive(2),action()["updated_transaction_count"])
            else {assertEquals(if(mode=="unavailable") "projection.unavailable" else "projection.version_conflict",assertFailsWith<CategoryFailure>(mode,action).code);assertEquals(before,snapshot(db,workspace),mode)}
        }
    }

    @Test fun threeRevisionOverflowsLeaveAllSelectedDataUnchanged() = inWorkspace {db,workspace ->
        val data=fixture(db,workspace)
        for(field in listOf("projection","source","entry")) {
            db.write {c ->
                c.prepareStatement("UPDATE cash_projection_states SET projection_version=?,source_revision=? WHERE workspace_id=?").use {it.setLong(1,if(field=="projection") Long.MAX_VALUE else 7);it.setLong(2,if(field=="source") Long.MAX_VALUE else 11);it.setString(3,workspace);it.executeUpdate()}
                c.prepareStatement("UPDATE cash_entries SET revision=? WHERE workspace_id=?").use {it.setLong(1,if(field=="entry") Long.MAX_VALUE else 1);it.setString(2,workspace);it.executeUpdate()}
            }
            val before=snapshot(db,workspace)
            assertFailsWith<ArithmeticException> {CashClassificationService(db).setCategory("synthetic-admin",JsonArray(data.projections.map(::JsonPrimitive)),JsonPrimitive(if(field=="projection") Long.MAX_VALUE else 7),JsonPrimitive(data.targetCategory))}
            assertEquals(before,snapshot(db,workspace),field)
        }
    }

    @Test fun lateProjectionFailureRollsBackEntryAndStandaloneWrites() {
      var projectionFailed=false
      fun call(target:Any,method:java.lang.reflect.Method,args:Array<out Any?>?):Any?=try {method.invoke(target,*(args ?: emptyArray()))} catch(f:java.lang.reflect.InvocationTargetException) {throw f.targetException}
      fun observe(c:java.sql.Connection):java.sql.Connection=java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection::class.java.classLoader,arrayOf(java.sql.Connection::class.java)) {_,method,args ->
          val value=call(c,method,args)
          if(value is java.sql.PreparedStatement && (args?.firstOrNull() as? String)?.observedSql()?.startsWith("update cash_projections set")==true)
              java.lang.reflect.Proxy.newProxyInstance(java.sql.PreparedStatement::class.java.classLoader,arrayOf(java.sql.PreparedStatement::class.java)) {_,operation,values ->
                  try {call(value,operation,values)} catch(f:java.sql.SQLException) {
                      if(f.message.orEmpty().contains("synthetic classification failure")) projectionFailed=true
                      throw f
                  }
              }
          else value
      } as java.sql.Connection
      inWorkspace(::observe) {db,workspace ->
        val data=fixture(db,workspace);val trigger="ft_classify_"+java.util.UUID.randomUUID().toString().replace("-","")
        db.write {c ->val relation=deletionRelation(c,db,workspace,data.facts[0]);deletionCandidate(c,db,workspace,relation,data.facts[2])}
        check(workspace.matches(Regex("[A-Za-z0-9-]+")))
        db.write {c ->c.createStatement().use {
            if(db.sqlite) it.execute("CREATE TRIGGER $trigger BEFORE UPDATE OF category_id ON cash_projections WHEN NEW.workspace_id='$workspace' BEGIN SELECT RAISE(ABORT,'synthetic classification failure'); END")
            else {it.execute("CREATE FUNCTION $trigger() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''synthetic classification failure''; RETURN NEW; END'")
                it.execute("CREATE TRIGGER $trigger BEFORE UPDATE OF category_id ON cash_projections FOR EACH ROW WHEN (NEW.workspace_id='$workspace') EXECUTE FUNCTION $trigger()")}
        }}
        try {
            val before=snapshot(db,workspace)
            assertFailsWith<StorageFailure> {CashClassificationService(db).setCategory("synthetic-admin",JsonArray(data.projections.map(::JsonPrimitive)),JsonPrimitive(7),JsonPrimitive(data.targetCategory))}
            assertTrue(projectionFailed,"The projection UPDATE must reach the injected database failure")
            assertEquals(before,snapshot(db,workspace))
        } finally {db.write {c ->c.createStatement().use {it.execute("DROP TRIGGER IF EXISTS $trigger"+if(db.sqlite) "" else " ON cash_projections");if(!db.sqlite) it.execute("DROP FUNCTION IF EXISTS $trigger()")}}}
    }}

    @Test fun workspaceHintNeverAuthorizesAfterLockWaitRevocationSessionSwitchExpiryOrLogout() {
        for(mode in listOf("role","workspace","expiry","logout")) inWorkspace {db,workspace ->testApplication {
            val data=fixture(db,workspace)
            val original=db.read {c ->c.prepareStatement("SELECT * FROM user_sessions WHERE token_digest=?").use {it.setString(1,tokenDigest("synthetic-admin"));it.executeQuery().use {r ->assertTrue(r.next());(1..r.metaData.columnCount).associate {index ->
                val name=r.metaData.getColumnName(index);name to if(name in setOf("created_at","expires_at")) contractInstant(r.getString(index),db.sqlite) else r.getString(index)
            }}}}
            fun revoke(c:java.sql.Connection) {
                when(mode) {
                    "role" ->c.prepareStatement("UPDATE workspace_memberships SET role='viewer' WHERE workspace_id=? AND user_id='admin'").use {it.setString(1,workspace);assertEquals(1,it.executeUpdate())}
                    "workspace" ->c.prepareStatement("UPDATE user_sessions SET active_workspace_id='fixture-a' WHERE token_digest=?").use {it.setString(1,tokenDigest("synthetic-admin"));assertEquals(1,it.executeUpdate())}
                    "expiry" ->c.prepareStatement("UPDATE user_sessions SET expires_at=? WHERE token_digest=?").use {it.instant(1,deletionTime,db.sqlite);it.setString(2,tokenDigest("synthetic-admin"));assertEquals(1,it.executeUpdate())}
                    "logout" ->c.prepareStatement("DELETE FROM user_sessions WHERE token_digest=?").use {it.setString(1,tokenDigest("synthetic-admin"));assertEquals(1,it.executeUpdate())}
                }
            }
            val arrived=java.util.concurrent.CountDownLatch(1)
            val service=CashClassificationService(db,beforeWrite={if(db.sqlite) db.write(::revoke)},beforeWorkspaceLock={arrived.countDown()})
            application {financeModule(db,classification=service)}
            val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
            val holder=if(db.sqlite) null else java.sql.DriverManager.getConnection(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")).apply {
                autoCommit=false;prepareStatement("SELECT id FROM workspaces WHERE id=? FOR UPDATE").use {it.setString(1,workspace);it.executeQuery().use {r ->assertTrue(r.next())}}
            }
            var pending:java.util.concurrent.CompletableFuture<io.ktor.client.statement.HttpResponse>?=null
            val before=snapshot(db,workspace)
            try {
                pending=java.util.concurrent.CompletableFuture.supplyAsync {kotlinx.coroutines.runBlocking {client.put("/api/v1/cash-projections/categories") {
                    bearerAuth("synthetic-admin");contentType(ContentType.Application.Json);setBody(buildJsonObject {put("projection_ids",JsonArray(data.projections.map(::JsonPrimitive)));put("projection_version",7);put("category_id",data.targetCategory)}.toString())
                }}}
                if(holder!=null) {
                    assertTrue(arrived.await(10,java.util.concurrent.TimeUnit.SECONDS))
                    val pid=holder.createStatement().use {it.executeQuery("SELECT pg_backend_pid()").use {r ->r.next();r.getInt(1)}}
                    val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);var waiting=false
                    while(!waiting && System.nanoTime()<deadline) {
                        waiting=db.read {c ->c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)) AND wait_event_type='Lock'").use {it.setInt(1,pid);it.executeQuery().use {r ->r.next();r.getInt(1)>0}}}
                        if(!waiting) Thread.sleep(10)
                    }
                    assertTrue(waiting,"Actual workspace lock wait must be observed: $mode");revoke(holder);holder.commit()
                }
                val response=pending.get(10,java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(if(mode in setOf("expiry","logout")) 401 else 403,response.status.value,"$mode ${response.bodyAsText()}")
                val error=Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
                assertEquals(JsonArray(emptyList()),error["field_errors"]);assertEquals(JsonPrimitive(false),error["retryable"])
                assertEquals(before,snapshot(db,workspace),mode)
            } finally {
                holder?.rollback();holder?.close();pending?.get(10,java.util.concurrent.TimeUnit.SECONDS)
                db.write {c ->c.prepareStatement("DELETE FROM user_sessions WHERE token_digest=?").use {it.setString(1,tokenDigest("synthetic-admin"));it.executeUpdate()};deletionInsert(c,db,"user_sessions",original)}
            }
        }}
    }

}
