package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashClassificationPerformanceTest {
    @Test fun hundredGroupedSelectionsKeepTheOriginalApiSqlAndLatencyBudgets() = testApplication {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val active=AtomicBoolean(false);val statements=CopyOnWriteArrayList<String>()
        fun call(target:Any,method:java.lang.reflect.Method,args:Array<out Any?>?):Any?=try {method.invoke(target,*(args ?: emptyArray()))} catch(f:InvocationTargetException) {throw f.targetException}
        fun observe(c:Connection):Connection=Proxy.newProxyInstance(Connection::class.java.classLoader,arrayOf(Connection::class.java)) {_,method,args ->
            val value=call(c,method,args)
            if(value is Statement && active.get()) {
                val prepared=args?.firstOrNull() as? String
                value.queryTimeout=5
                Proxy.newProxyInstance(Statement::class.java.classLoader,arrayOf(if(value is PreparedStatement) PreparedStatement::class.java else Statement::class.java)) {_,operation,values ->
                    if(operation.name in setOf("execute","executeUpdate","executeQuery","executeBatch","executeLargeBatch")) {
                        val sql=prepared ?: values?.firstOrNull() as? String
                        if(sql!=null && sql !in setOf("COMMIT","ROLLBACK")) statements+=sql
                    };call(value,operation,values)
                }
            } else value
        } as Connection
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"),::observe)
        val workspaces=WorkspaceService(db);val workspace=workspaces.create("synthetic-admin","分类批量性能").activeWorkspaceId!!
        application {financeModule(db)}
        try {
            val account=deletionAccount(db,workspace);val ids=mutableListOf<String>();var singleton=""
            db.write {c ->
                for(index in 0 until 1000) deletionInsert(c,db,"cash_categories",linkedMapOf("id" to "classification-perf-$index","workspace_id" to workspace,"parent_scope_key" to "__root__",
                    "name" to "性能分类$index","normalized_name" to "性能分类$index","category_path" to "/classification-perf-$index/","depth" to 1,"sort_order" to index,"revision" to 1L,"created_at" to deletionTime,"updated_at" to deletionTime))
                val dataset=c.prepareStatement("SELECT active_dataset_id FROM cash_projection_states WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {r ->r.next();r.getString(1)}}
                for(index in 0 until 10000) {
                    val category=if(index%2==0) "classification-perf-0" else null
                    val root=deletionFact(c,db,workspace,account,category)
                    if(index==100) singleton="cash:$root"
                    val entry="classification-perf-entry-$index";deletionEntry(c,db,workspace,entry,root,category)
                    val row=deletionProjection(c,db,workspace,dataset,root,account,"classification-perf-0",7)
                    if(index%2==1) c.prepareStatement("UPDATE cash_projections SET category_id=NULL,category_path=NULL WHERE id=?").use {it.setLong(1,row);it.executeUpdate()}
                    val members=if(index<100) listOf(root,deletionFact(c,db,workspace,account,"classification-perf-0")) else listOf(root)
                    if(index<100) {ids+="cash:$root";c.prepareStatement("UPDATE cash_projections SET member_count=2 WHERE id=?").use {it.setLong(1,row);it.executeUpdate()}}
                    for((ordinal,fact) in members.withIndex()) {
                        deletionInsert(c,db,"cash_projection_members",linkedMapOf("workspace_id" to workspace,"dataset_id" to dataset,"projection_row_id" to row,"cash_transaction_id" to fact,"roles_json" to JsonArray(listOf(JsonPrimitive(if(ordinal==0) "root" else "mirror"))),"ordinal" to ordinal))
                        deletionInsert(c,db,"cash_entry_members",linkedMapOf("workspace_id" to workspace,"transaction_id" to fact,"entry_id" to entry,"standalone_values" to buildJsonObject {put("category_id",if(ordinal==0) category?.let(::JsonPrimitive) ?: JsonNull else JsonPrimitive("classification-perf-0"));put("note","独立备注")},"standalone_manual_fields" to JsonArray(emptyList())))
                    }
                }
                c.prepareStatement("UPDATE cash_projection_states SET projection_version=7,source_revision=0,projection_count=10000,member_count=10100 WHERE workspace_id=?").use {it.setString(1,workspace);it.executeUpdate()}
            }
            var version=7L;val times=mutableListOf<Long>();val counts=mutableListOf<Int>()
            for(round in 0 until 7) {
                statements.clear();active.set(true);val start=System.nanoTime()
                val response=client.put("/api/v1/cash-projections/categories") {bearerAuth("synthetic-admin");contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {put("projection_ids",JsonArray(ids.map(::JsonPrimitive)));put("projection_version",version);put("category_id","classification-perf-${if(round%2==0) 1 else 0}")}.toString())}
                val elapsed=System.nanoTime()-start;active.set(false)
                assertEquals(200,response.status.value,response.bodyAsText());val result=Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(JsonPrimitive(100),result["projection_count"]);assertEquals(JsonPrimitive(200),result["updated_transaction_count"]);version=result.getValue("projection_version").jsonPrimitive.long
                if(round>=2) {times+=elapsed;counts+=statements.size}
            }
            val evidence=buildJsonObject {put("backend",if(db.sqlite) "sqlite" else "postgresql");put("projections",10000);put("selected",100);put("members",200);put("p95_ns",times.max());put("max_sql",counts.max());put("sql",JsonArray(statements.map(::JsonPrimitive)))}
            statements.clear();active.set(true)
            val single=client.put("/api/v1/cash-projections/$singleton/category") {bearerAuth("synthetic-admin");contentType(ContentType.Application.Json)
                setBody(buildJsonObject {put("projection_version",version);put("category_id","classification-perf-1")}.toString())}
            active.set(false);assertEquals(200,single.status.value,single.bodyAsText())
            assertEquals(JsonPrimitive(1),Json.parseToJsonElement(single.bodyAsText()).jsonObject["updated_transaction_count"])
            assertTrue(statements.size<=12,"Actual singleton API SQL ${statements.size} exceeds 12: $statements")
            System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let {Files.writeString(Path.of(it,"classification-performance.json"),JsonObject(evidence+mapOf("singleton_sql" to JsonPrimitive(statements.size))).toString())}
            assertTrue(counts.all {it<=12},"Actual API SQL ${counts.max()} exceeds original 12: $evidence")
            assertTrue(times.max()<=500000000,"Actual API p95 exceeds original 500ms: $evidence")
        } finally {active.set(false);workspaces.delete("synthetic-admin","分类批量性能");workspaces.select("synthetic-admin","fixture-a")}
    }
}
