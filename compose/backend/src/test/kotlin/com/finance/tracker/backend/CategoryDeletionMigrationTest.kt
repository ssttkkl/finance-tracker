package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CategoryDeletionMigrationTest {
    private fun inWorkspace(action: (Database,String)->Unit) {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val workspaces=WorkspaceService(db);val workspace=workspaces.create("synthetic-admin","分类删除测试").activeWorkspaceId!!
        try {action(db,workspace)} finally {setAccountTestRole(db,workspace,"admin");workspaces.delete("synthetic-admin","分类删除测试");workspaces.select("synthetic-admin","fixture-a")}
    }
    @Test fun deletionImpactAndDeletePreserveRevisionAndChildGuards() = inWorkspace {db,_ ->testApplication {
        application {financeModule(db)}
        val categories=CategoryService(db)
        val parent=categories.create("synthetic-admin","父分类")
        val child=categories.create("synthetic-admin","子分类",parent.id)
        val impact=client.get("/api/v1/cash-categories/${child.id}/deletion-impact") {bearerAuth("synthetic-admin")}
        assertEquals(200,impact.status.value,impact.bodyAsText())
        assertEquals(Json.parseToJsonElement("""{"category_id":"${child.id}","revision":2,"category_revision":1,"child_count":0,"direct_usage_count":0}"""),Json.parseToJsonElement(impact.bodyAsText()))
        suspend fun remove(id:String, revision:Long, categoryRevision:Long=1, count:Long=0)=client.delete("/api/v1/cash-categories/$id") {
            bearerAuth("synthetic-admin");contentType(ContentType.Application.Json)
            setBody("""{"expected_revision":$revision,"expected_category_revision":$categoryRevision,"expected_usage_count":$count,"confirmed":true}""")
        }
        val before=categories.list("synthetic-admin")
        for((id,revision,rowRevision,count,code) in listOf(
            listOf(parent.id,2L,1L,0L,"category.has_children"),listOf(child.id,1L,1L,0L,"category.revision_conflict"),
            listOf(child.id,2L,2L,0L,"category.impact_changed"),listOf(child.id,2L,1L,1L,"category.impact_changed"))) {
            val response=remove(id as String,revision as Long,rowRevision as Long,count as Long)
            assertEquals(if(code=="category.has_children") 400 else 409,response.status.value,response.bodyAsText())
            assertEquals(code,Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertEquals(before,categories.list("synthetic-admin"))
        }
        val deleted=remove(child.id,2)
        assertEquals(200,deleted.status.value,deleted.bodyAsText())
        assertEquals(Json.parseToJsonElement("""{"category_id":"${child.id}","cleared_transaction_count":0,"revision":3}"""),Json.parseToJsonElement(deleted.bodyAsText()))
        assertEquals(listOf(parent.id),categories.list("synthetic-admin").items.map {it.id})
        assertEquals(404,client.get("/api/v1/cash-categories/${child.id}/deletion-impact") {bearerAuth("synthetic-admin")}.status.value)
    }}
    @Test fun categoryEditAndDeleteReportsUsageAndRequiresCurrentRevision() = inWorkspace {db,workspace ->
        val service=CategoryService(db);val category=service.create("synthetic-admin","餐饮")
        val account=deletionAccount(db,workspace)
        val fact=db.write {c ->val id=deletionFact(c,db,workspace,account,category.id);deletionEntry(c,db,workspace,"delete-entry",id,category.id);id}
        val edited=service.update("synthetic-admin",category.id,name="外食",description="午餐和晚餐",expectedRevision=category.revision)
        assertEquals("外食",edited.name);assertEquals(listOf("外食"),edited.path)
        val impact=service.deletionImpact("synthetic-admin",category.id)
        assertEquals(JsonPrimitive(1),impact["direct_usage_count"])
        fun remove(revision:Long,confirmed:Boolean)=service.delete("synthetic-admin",category.id,JsonPrimitive(revision),impact["category_revision"],JsonPrimitive(1),confirmed)
        assertEquals("category.revision_conflict",assertFailsWith<CategoryFailure> {remove(category.revision,true)}.code)
        val latest=service.list("synthetic-admin").revision
        assertEquals("category.delete_confirmation_required",assertFailsWith<CategoryFailure> {remove(latest,false)}.code)
        assertEquals(JsonPrimitive(1),remove(latest,true)["cleared_transaction_count"])
        db.read {c ->c.prepareStatement("SELECT category_id FROM cash_transactions WHERE id=?").use {it.setLong(1,fact);it.executeQuery().use {rows ->assertTrue(rows.next());assertNull(rows.getString(1))}}}
    }

    @Test fun deletionProtectsEmptyValueForLiveAndDeletedRecordsAndStandaloneValues() = inWorkspace {db,workspace ->
        val service=CategoryService(db);val category=service.create("synthetic-admin","餐饮");val other=service.create("synthetic-admin","日常")
        val account=deletionAccount(db,workspace)
        val overrides=buildJsonObject {put("category_id",buildJsonObject {put("value",category.id);put("source_value",other.id)});put("note",buildJsonObject {put("value","人工备注")})}
        val facts=db.write {c ->
            val ids=listOf(true,false).map {deletionFact(c,db,workspace,account,category.id,it,overrides)}
            deletionEntry(c,db,workspace,"deleted-fact-entry",ids[0],other.id,JsonArray(listOf(JsonPrimitive("note"))))
            deletionEntry(c,db,workspace,"live-fact-entry",ids[1],category.id,JsonArray(listOf(JsonPrimitive("note"),JsonPrimitive("category_id"))))
            for((index,id) in ids.withIndex()) deletionInsert(c,db,"cash_entry_members",linkedMapOf("workspace_id" to workspace,"transaction_id" to id,
                "entry_id" to if(index==0) "deleted-fact-entry" else "live-fact-entry",
                "standalone_values" to buildJsonObject {put("category_id",category.id);put("note","恢复备注")},
                "standalone_manual_fields" to JsonArray(listOf(JsonPrimitive("note")))))
            ids
        }
        val impact=service.deletionImpact("synthetic-admin",category.id)
        val result=service.delete("synthetic-admin",category.id,impact["revision"],impact["category_revision"],impact["direct_usage_count"],true)
        assertEquals(JsonPrimitive(1),result["cleared_transaction_count"])
        db.read {c ->
            c.prepareStatement("SELECT id,category_id,manual_overrides,row_revision,deleted_at FROM cash_transactions WHERE workspace_id=? ORDER BY id").use {
                it.setString(1,workspace);it.executeQuery().use {rows ->for((index,id) in facts.withIndex()) {
                    assertTrue(rows.next());assertEquals(id,rows.getLong(1));assertNull(rows.getString(2));assertEquals(2L,rows.getLong(4))
                    val document=Json.parseToJsonElement(rows.getString(3)).jsonObject
                    assertEquals(JsonNull,document.getValue("category_id").jsonObject["value"])
                    assertEquals(JsonPrimitive(other.id),document.getValue("category_id").jsonObject["source_value"])
                    assertEquals(Json.parseToJsonElement("""{"value":"人工备注"}"""),document["note"])
                    assertEquals(index==0,rows.getString(5)!=null)
                };assertFalse(rows.next())}
            }
            c.prepareStatement("SELECT id,category_id,revision,manual_fields,note FROM cash_entries WHERE workspace_id=? ORDER BY id").use {
                it.setString(1,workspace);it.executeQuery().use {rows ->
                    assertTrue(rows.next());assertEquals(other.id,rows.getString(2));assertEquals(1L,rows.getLong(3));assertEquals(Json.parseToJsonElement("[\"note\"]"),Json.parseToJsonElement(rows.getString(4)))
                    assertTrue(rows.next());assertNull(rows.getString(2));assertEquals(2L,rows.getLong(3));assertEquals(Json.parseToJsonElement("[\"note\",\"category_id\"]"),Json.parseToJsonElement(rows.getString(4)));assertEquals("保留备注",rows.getString(5))
                }
            }
            c.prepareStatement("SELECT standalone_values,standalone_manual_fields FROM cash_entry_members WHERE workspace_id=?").use {
                it.setString(1,workspace);it.executeQuery().use {rows ->repeat(2) {assertTrue(rows.next());assertEquals(Json.parseToJsonElement("""{"category_id":null,"note":"恢复备注"}"""),Json.parseToJsonElement(rows.getString(1)));assertEquals(Json.parseToJsonElement("[\"note\",\"category_id\"]"),Json.parseToJsonElement(rows.getString(2)))}}
            }
        }
    }

    @Test fun candidateDependenciesAcrossBatchesInvalidateEachRelationOnlyOnce() = inWorkspace {db,workspace ->
        val service=CategoryService(db);val category=service.create("synthetic-admin","餐饮");val account=deletionAccount(db,workspace)
        val fixture=db.write {c ->
            val unchanged=deletionFact(c,db,workspace,account,null)
            val changed=(0..500).map {deletionFact(c,db,workspace,account,category.id)}
            val relations=listOf(
                deletionRelation(c,db,workspace,unchanged),
                deletionRelation(c,db,workspace,changed.first(),secondary=changed.last(),anchor=changed.last()),
                deletionRelation(c,db,workspace,unchanged,secondary=changed.last()),
                deletionRelation(c,db,workspace,changed.last(),active=false),
                deletionRelation(c,db,workspace,unchanged,secondary=changed.first(),anchor=changed.first()))
            deletionCandidate(c,db,workspace,relations[0],changed.last())
            for(index in 1..4) deletionCandidate(c,db,workspace,relations[index],unchanged,validity=if(index==4) "stale" else "valid")
            relations to changed
        }
        val impact=service.deletionImpact("synthetic-admin",category.id)
        service.delete("synthetic-admin",category.id,impact["revision"],impact["category_revision"],impact["direct_usage_count"],true)
        db.read {c ->c.prepareStatement("SELECT r.id,r.row_revision,c.validity FROM transaction_relations r JOIN cash_relation_candidates c ON c.workspace_id=r.workspace_id AND c.relation_id=r.id WHERE r.workspace_id=? ORDER BY r.id").use {
            it.setString(1,workspace);it.executeQuery().use {rows ->for((index,id) in fixture.first.withIndex()) {
                assertTrue(rows.next());assertEquals(id,rows.getLong(1));assertEquals(if(index<3) 2L else 1L,rows.getLong(2));assertEquals(if(index==3) "valid" else "stale",rows.getString(3))
            };assertFalse(rows.next())}
        }}
        if(db.sqlite) db.read {c ->
            val queries=mutableListOf<String>()
            val observed=java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection::class.java.classLoader,arrayOf(java.sql.Connection::class.java)) {_,method,args ->
                if(method.name=="prepareStatement") queries+=args!![0] as String
                try {method.invoke(c,*(args ?: emptyArray()))} catch(f:java.lang.reflect.InvocationTargetException) {throw f.targetException}
            } as java.sql.Connection
            assertTrue(CategoryDeletionRepository(true).invalidateCandidates(observed,workspace,fixture.second).isEmpty())
            val plans=mutableListOf<String>()
            for(query in queries.filter {it.trimStart().startsWith("SELECT candidate.id")}) c.prepareStatement("EXPLAIN QUERY PLAN $query").use {
                for(index in 1..it.parameterMetaData.parameterCount) it.setNull(index,java.sql.Types.NULL)
                it.executeQuery().use {rows ->while(rows.next()) plans+=rows.getString(4)}
            }
            assertTrue(plans.any {"ix_cash_relation_candidate_parent" in it && "candidate_transaction_id=?" in it},plans.toString())
            assertTrue(plans.any {"uq_cash_relation_candidate" in it && "relation_id=?" in it},plans.toString())
            assertFalse(plans.any {"SCAN candidate" in it},plans.toString())
            System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let {java.nio.file.Files.writeString(java.nio.file.Path.of(it,"category-candidate-plans.json"),JsonArray(plans.map(::JsonPrimitive)).toString())}
        }
    }

    @Test fun revisionOverflowRollsBackEveryFinancialChange() = inWorkspace {db,workspace ->
        val service=CategoryService(db);val category=service.create("synthetic-admin","餐饮");val account=deletionAccount(db,workspace)
        db.write {c ->val fact=deletionFact(c,db,workspace,account,category.id);deletionEntry(c,db,workspace,"overflow-entry",fact,category.id)
            val unchanged=deletionFact(c,db,workspace,account,null)
            val relation=deletionRelation(c,db,workspace,unchanged);deletionCandidate(c,db,workspace,relation,fact)
        }
        fun snapshot()=db.read {c ->listOf("cash_categories","cash_category_states","cash_transactions","cash_entries","cash_entry_members","cash_projection_states","cash_projections","transaction_relations","cash_relation_candidates").associateWith {table ->
            c.prepareStatement("SELECT * FROM $table WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {rows ->buildList {while(rows.next()) add((1..rows.metaData.columnCount).map {i ->rows.getString(i)})}.sortedBy {it.toString()}}}
        }}
        for(field in listOf("directory","projection_version","source_revision")) {
            db.write {c ->
                c.prepareStatement("UPDATE cash_category_states SET revision=? WHERE workspace_id=?").use {it.setLong(1,if(field=="directory") Long.MAX_VALUE else 1L);it.setString(2,workspace);it.executeUpdate()}
                c.prepareStatement("UPDATE cash_projection_states SET projection_version=?,source_revision=? WHERE workspace_id=?").use {
                    it.setLong(1,if(field=="projection_version") Long.MAX_VALUE else 7L);it.setLong(2,if(field=="source_revision") Long.MAX_VALUE else 11L);it.setString(3,workspace);it.executeUpdate()
                }
            }
            val before=snapshot();val impact=service.deletionImpact("synthetic-admin",category.id)
            assertFailsWith<ArithmeticException>(field) {service.delete("synthetic-admin",category.id,impact["revision"],impact["category_revision"],impact["direct_usage_count"],true)}
            assertEquals(before,snapshot(),field)
        }
    }

    @Test fun badJsonNeverDeletesAndPythonUnicodeEncodingsAreAccepted() = inWorkspace {db,workspace ->testApplication {
        application {financeModule(db)}
        val service=CategoryService(db);val category=service.create("synthetic-admin","保留分类")
        val before=service.list("synthetic-admin")
        val prefix="""{"expected_revision":1,"expected_category_revision":1,"expected_usage_count":0,"confirmed":true,"unused":"a"""
        val invalid=buildList {
            add("{\"expected_revision\":".encodeToByteArray())
            for(code in 0..31) add((prefix+code.toChar()+"b\"}").encodeToByteArray())
            add(prefix.encodeToByteArray()+byteArrayOf(255.toByte())+"b\"}".encodeToByteArray())
        }
        for(raw in invalid) {
            val response=client.delete("/api/v1/cash-categories/${category.id}") {bearerAuth("synthetic-admin");contentType(ContentType.Application.Json);setBody(raw)}
            assertEquals(400,response.status.value,response.bodyAsText());assertEquals(before,service.list("synthetic-admin"))
            assertEquals("分类无法删除，请刷新后重试。",Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
        }
        val surrogateInputs=listOf(
            byteArrayOf(237.toByte(),160.toByte(),189.toByte(),237.toByte(),184.toByte(),128.toByte()),
            "\\ud83d".encodeToByteArray()+byteArrayOf(237.toByte(),184.toByte(),128.toByte()),
            byteArrayOf(237.toByte(),160.toByte(),189.toByte())+"\\ude00".encodeToByteArray())
        for(field in listOf("expected_revision","expected_category_revision","expected_usage_count")) for(value in surrogateInputs) {
            val body="""{"expected_revision":1,"expected_category_revision":1,"expected_usage_count":0,"confirmed":true}"""
            val target="\"$field\":"+if(field=="expected_usage_count") "0" else "1"
            val pieces=body.split(target)
            val raw=pieces[0].encodeToByteArray()+"\"$field\":\"".encodeToByteArray()+value+"\"".encodeToByteArray()+pieces[1].encodeToByteArray()
            val response=client.delete("/api/v1/cash-categories/${category.id}") {bearerAuth("synthetic-admin");contentType(ContentType.Application.Json);setBody(raw)}
            assertEquals(400,response.status.value,response.bodyAsText())
            assertEquals("invalid literal for int() with base 10: '\\ud83d\\ude00'",Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertEquals(before,service.list("synthetic-admin"))
        }
        setAccountTestRole(db,workspace,"viewer")
        assertEquals(200,client.get("/api/v1/cash-categories/${category.id}/deletion-impact") {bearerAuth("synthetic-admin")}.status.value)
        assertEquals(403,client.delete("/api/v1/cash-categories/${category.id}") {bearerAuth("synthetic-admin");contentType(ContentType.Application.Json);setBody("{}")}.status.value)
        assertEquals(before,service.list("synthetic-admin"))
        setAccountTestRole(db,workspace,"editor")
        for(encoding in listOf("UTF-8","UTF-16","UTF-16LE","UTF-16BE","UTF-32","UTF-32LE","UTF-32BE","UTF-8-BOM","surrogatepass")) {
            val item=service.create("synthetic-admin",encoding)
            val body="""{"expected_revision":${item.revision},"expected_category_revision":1,"expected_usage_count":0,"confirmed":true,"unused":"中文😀"}"""
            val bytes=when(encoding) {
                "UTF-8-BOM" ->byteArrayOf(239.toByte(),187.toByte(),191.toByte())+body.encodeToByteArray()
                "surrogatepass" ->body.replace("中文😀","").let {it.substringBeforeLast('"').encodeToByteArray()+byteArrayOf(237.toByte(),160.toByte(),128.toByte())+"\"}".encodeToByteArray()}
                else ->body.toByteArray(java.nio.charset.Charset.forName(encoding))
            }
            val response=client.delete("/api/v1/cash-categories/${item.id}") {bearerAuth("synthetic-admin");contentType(ContentType.Application.Json);setBody(bytes)}
            assertEquals(200,response.status.value,"$encoding ${response.bodyAsText()}")
            assertTrue(service.list("synthetic-admin").items.none {it.id==item.id})
        }
    }}

    @Test fun deletionRechecksPermissionAfterMiddlewareAndRealWorkspaceLockWait() = inWorkspace {db,workspace ->testApplication {
        val categories=CategoryService(db);val category=categories.create("synthetic-admin","撤权保护")
        val impact=categories.deletionImpact("synthetic-admin",category.id)
        val arrived=java.util.concurrent.CountDownLatch(1)
        val service=CategoryService(db,beforeDelete={if(db.sqlite) setAccountTestRole(db,workspace,"viewer")},beforeWorkspaceLock={arrived.countDown()})
        application {financeModule(db,categories=service)}
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val holder=if(db.sqlite) null else java.sql.DriverManager.getConnection(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")).apply {
            autoCommit=false;prepareStatement("SELECT id FROM workspaces WHERE id=? FOR UPDATE").use {it.setString(1,workspace);it.executeQuery().use {rows ->assertTrue(rows.next())}}
        }
        var pending:java.util.concurrent.CompletableFuture<io.ktor.client.statement.HttpResponse>?=null
        try {
            pending=java.util.concurrent.CompletableFuture.supplyAsync {kotlinx.coroutines.runBlocking {
                client.delete("/api/v1/cash-categories/${category.id}") {bearerAuth("synthetic-admin");contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {put("expected_revision",impact.getValue("revision"));put("expected_category_revision",impact.getValue("category_revision"));put("expected_usage_count",0);put("confirmed",true)}.toString())}
            }}
            if(holder!=null) {
                assertTrue(arrived.await(10,java.util.concurrent.TimeUnit.SECONDS))
                val pid=holder.createStatement().use {it.executeQuery("SELECT pg_backend_pid()").use {rows ->rows.next();rows.getInt(1)}}
                val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                var waiting=false
                while(!waiting && System.nanoTime()<deadline) {
                    waiting=db.read {c ->c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)) AND wait_event_type='Lock'").use {it.setInt(1,pid);it.executeQuery().use {rows ->rows.next();rows.getInt(1)>0}}}
                    if(!waiting) Thread.sleep(10)
                }
                assertTrue(waiting,"Actual PostgreSQL workspace lock wait must be observed")
                holder.prepareStatement("UPDATE workspace_memberships SET role='viewer' WHERE workspace_id=? AND user_id='admin'").use {it.setString(1,workspace);it.executeUpdate()};holder.commit()
            }
            val response=pending.get(10,java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(403,response.status.value,response.bodyAsText())
            assertEquals(listOf(category.id),categories.list("synthetic-admin").items.map {it.id})
            assertEquals(impact,categories.deletionImpact("synthetic-admin",category.id))
        } finally {holder?.rollback();holder?.close();pending?.get(10,java.util.concurrent.TimeUnit.SECONDS)}
    }}

    @Test fun allDatasetsClearCategoryButOnlyReadyActiveDatasetPublishesANewVersion() = inWorkspace {db,workspace ->
        val service=CategoryService(db,now={deletionTime});val account=deletionAccount(db,workspace)
        val active=db.read {c ->c.prepareStatement("SELECT active_dataset_id FROM cash_projection_states WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {rows ->rows.next();rows.getString(1)}}}
        db.write {c ->deletionInsert(c,db,"cash_projection_datasets",linkedMapOf("id" to "deletion-staging","workspace_id" to workspace,"state" to "staging",
            "source_revision" to 11L,"source_digest" to "digest","rules_version" to "cash-projection-v2","created_at" to deletionTime))}
        for(mode in listOf("uninitialized","uninitialized-null","ready-active")) {
            val category=service.create("synthetic-admin",mode)
            val rows=db.write {c ->
                val fact=deletionFact(c,db,workspace,account,category.id);deletionEntry(c,db,workspace,"entry-$mode",fact,category.id)
                val projections=listOf(deletionProjection(c,db,workspace,active,fact,account,category.id,7),deletionProjection(c,db,workspace,"deletion-staging",fact,account,category.id,3))
                c.prepareStatement("UPDATE cash_projection_states SET availability=?,active_dataset_id=?,projection_version=7,source_revision=11,last_build_status='failed',last_build_id='preserved-build',last_error_code='old',last_error_summary='原失败' WHERE workspace_id=?").use {
                    it.setString(1,if(mode.startsWith("uninitialized")) "uninitialized" else "ready");it.setString(2,if(mode=="uninitialized-null") null else active);it.setString(3,workspace);it.executeUpdate()
                };projections
            }
            val impact=service.deletionImpact("synthetic-admin",category.id)
            service.delete("synthetic-admin",category.id,impact["revision"],impact["category_revision"],impact["direct_usage_count"],true)
            db.read {c ->
                c.prepareStatement("SELECT category_id,category_path,built_projection_version,note FROM cash_projections WHERE id=?").use {statement ->
                    for((index,id) in rows.withIndex()) {statement.setLong(1,id);statement.executeQuery().use {r ->assertTrue(r.next());assertNull(r.getString(1));assertNull(r.getString(2));assertEquals(if(index==1) 3L else if(mode=="ready-active") 8L else 7L,r.getLong(3));assertEquals("保留备注",r.getString(4))}}
                }
                c.prepareStatement("SELECT projection_version,source_revision,last_build_status,last_build_id,last_error_code,last_error_summary FROM cash_projection_states WHERE workspace_id=?").use {
                    it.setString(1,workspace);it.executeQuery().use {r ->assertTrue(r.next());val publish=mode=="ready-active"
                        assertEquals(if(publish) 8L else 7L,r.getLong(1));assertEquals(if(publish) 12L else 11L,r.getLong(2));assertEquals(if(publish) "succeeded" else "failed",r.getString(3));assertEquals("preserved-build",r.getString(4));assertEquals(if(publish) null else "old",r.getString(5));assertEquals(if(publish) null else "原失败",r.getString(6))}
                }
            }
        }
    }

}
