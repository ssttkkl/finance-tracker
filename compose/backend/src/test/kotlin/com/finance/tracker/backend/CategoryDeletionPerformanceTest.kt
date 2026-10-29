package com.finance.tracker.backend

import java.lang.reflect.Proxy
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CategoryDeletionPerformanceTest {
    private class Rollback:RuntimeException()
    private fun rss():Long {
        val process=ProcessBuilder("/bin/ps","-o","rss=","-p",ProcessHandle.current().pid().toString()).start()
        val result=process.inputStream.bufferedReader().readText().trim().toLong()*1024
        check(process.waitFor()==0);return result
    }
    @Test fun tenThousandRowsKeepIndexedReadsSetWritesAndExistingLatencyBudgets() {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val workspaces=WorkspaceService(db);val workspace=workspaces.create("synthetic-admin","分类删除性能").activeWorkspaceId!!
        try {
            val account=deletionAccount(db,workspace)
            db.write {c ->
                for(table in listOf("cash_projection_states","cash_projection_datasets")) c.prepareStatement("DELETE FROM $table WHERE workspace_id=?").use {it.setString(1,workspace);it.executeUpdate()}
                val levels=mutableListOf<List<Pair<String,String>>>()
                for((level,count) in listOf(10,100,300,300,290).withIndex()) {
                    val rows=(0 until count).map {index ->
                        val id="category-${level+1}-$index";val parent=if(level==0) null else levels[level-1][index%levels[level-1].size]
                        val path=(parent?.second ?: "/")+id+"/"
                        deletionInsert(c,db,"cash_categories",linkedMapOf("id" to id,"workspace_id" to workspace,"parent_id" to parent?.first,
                            "parent_scope_key" to (parent?.first ?: "__root__"),"name" to "分类 ${level+1}-$index","normalized_name" to "分类 ${level+1}-$index",
                            "category_path" to path,"depth" to level+1,"sort_order" to index+1,"revision" to 1L,"created_at" to deletionTime,"updated_at" to deletionTime))
                        id to path
                    };levels+=rows
                }
                deletionInsert(c,db,"cash_category_states",linkedMapOf("workspace_id" to workspace,"revision" to 0L,"updated_at" to deletionTime))
                val json=if(db.sqlite) "'{}'" else "'{}'::json";val array=if(db.sqlite) "'[]'" else "'[]'::json"
                val series=if(db.sqlite) "ids" else "generate_series(1,10000) AS ids(n)"
                val recursive=if(db.sqlite) "WITH RECURSIVE ids(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM ids WHERE n<10000) " else ""
                c.prepareStatement(recursive+"""INSERT INTO cash_transactions(id,workspace_id,account_id,state,record_origin,row_revision,cash_granularity,record_id,manual_overrides,
                    occurred_at,amount,currency,counterparty,counterparty_account,counterparty_account_attrs,note,category_id,record_type,record_subtype,created_at,deleted_by,delete_reason)
                    SELECT 990000000+n,?,?,'posted','legacy',1,'atomic','',$json,?,-1,'CNY','固定性能商户','',$array,'','category-5-0','consumption','not_applicable',?,'','' FROM $series""").use {
                    it.setString(1,workspace);it.setLong(2,account);it.instant(3,deletionTime,db.sqlite);it.instant(4,deletionTime,db.sqlite);assertEquals(10000,it.executeUpdate())
                }
                c.prepareStatement("""INSERT INTO cash_entries(id,workspace_id,root_transaction_id,revision,category_id,note,counterparty,counterparty_account,counterparty_account_attrs,manual_fields)
                    SELECT 'entry-'||CAST(id AS TEXT),workspace_id,id,1,category_id,note,counterparty,counterparty_account,counterparty_account_attrs,$array FROM cash_transactions WHERE workspace_id=?""").use {it.setString(1,workspace);assertEquals(10000,it.executeUpdate())}
                val standalone=if(db.sqlite) "json_object('category_id',category_id,'note',note,'counterparty',counterparty,'counterparty_account',counterparty_account,'counterparty_account_attrs',json(counterparty_account_attrs))"
                    else "json_build_object('category_id',category_id,'note',note,'counterparty',counterparty,'counterparty_account',counterparty_account,'counterparty_account_attrs',counterparty_account_attrs)"
                c.prepareStatement("""INSERT INTO cash_entry_members(workspace_id,transaction_id,entry_id,standalone_values,standalone_manual_fields)
                    SELECT workspace_id,id,'entry-'||CAST(id AS TEXT),$standalone,$array FROM cash_transactions WHERE workspace_id=?""").use {it.setString(1,workspace);assertEquals(10000,it.executeUpdate())}
            }
            val service=CategoryService(db);repeat(2) {service.deletionImpact("synthetic-admin","category-5-0")}
            val baseline=rss();val peak=AtomicLong(baseline);val running=AtomicBoolean(true)
            val sampler=Thread {while(running.get()) {peak.accumulateAndGet(rss(),::maxOf);Thread.sleep(2)}}.apply {isDaemon=true;start()}
            val times=mutableListOf<Long>();var deleteElapsed=0L;var repositoryStatements=0;val plans=mutableListOf<String>()
            try {
                repeat(5) {val start=System.nanoTime();val impact=service.deletionImpact("synthetic-admin","category-5-0");times+=System.nanoTime()-start;assertEquals(JsonPrimitive(10000),impact["direct_usage_count"])}
                assertTrue(times.sorted().last()<=250000000,"Impact p95 ${times.sorted().last()}ns exceeds original 250ms")
                assertFailsWith<Rollback> {db.write {c ->
                    val statements=mutableListOf<String>()
                    val observed=Proxy.newProxyInstance(Connection::class.java.classLoader,arrayOf(Connection::class.java)) {_,method,args ->
                        if(method.name=="prepareStatement") statements+=args!![0] as String
                        try {method.invoke(c,*(args ?: emptyArray()))} catch(f:InvocationTargetException) {throw f.targetException}
                    } as Connection
                    val repo=CategoryDeletionRepository(db.sqlite)
                    assertEquals(10000L,repo.impact(observed,workspace,"category-5-0").usage)
                    repo.clear(observed,workspace,"category-5-0",deletionTime,10000)
                    repositoryStatements=statements.size
                    assertTrue(repositoryStatements+4<=12,"Financial SQL count plus workspace/state/delete/bump exceeds original 12")
                    if(db.sqlite) {
                        val impact=statements.first()
                        c.prepareStatement("EXPLAIN QUERY PLAN $impact").use {it.setString(1,workspace);it.setString(2,"category-5-0");it.executeQuery().use {rows ->while(rows.next()) plans+=rows.getString(4)}}
                        assertTrue(plans.any {"ix_cash_entries_workspace_category" in it && "category_id=?" in it},plans.toString())
                        assertTrue(plans.none {"SCAN entry" in it},plans.toString())
                    }
                    throw Rollback()
                }}
                val impact=service.deletionImpact("synthetic-admin","category-5-0");val start=System.nanoTime()
                val result=service.delete("synthetic-admin","category-5-0",impact["revision"],impact["category_revision"],impact["direct_usage_count"],true)
                deleteElapsed=System.nanoTime()-start
                assertEquals(JsonPrimitive(10000),result["cleared_transaction_count"])
                assertTrue(deleteElapsed<=500000000,"Delete ${deleteElapsed}ns exceeds original 500ms")
                peak.accumulateAndGet(rss(),::maxOf)
                assertTrue(peak.get()-baseline<=96L*1024*1024,"Observed process RSS growth exceeds original 96MiB")
            } finally {running.set(false);sampler.join(10000);assertFalse(sampler.isAlive)}
            db.read {c ->
                for(table in listOf("cash_transactions","cash_entries")) c.prepareStatement("SELECT count(*) FROM $table WHERE workspace_id=? AND category_id IS NULL").use {it.setString(1,workspace);it.executeQuery().use {rows ->rows.next();assertEquals(10000,rows.getInt(1),table)}}
                c.prepareStatement("SELECT count(*) FROM cash_entry_members WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {rows ->rows.next();assertEquals(10000,rows.getInt(1))}}
            }
            val evidence=buildJsonObject {put("backend",if(db.sqlite) "sqlite" else "postgresql");put("rows",10000);put("categories",1000);put("impact_p95_ns",times.max());put("delete_ns",deleteElapsed);put("repository_statements",repositoryStatements);put("sampled_rss_growth_bytes",maxOf(0,peak.get()-baseline));put("plans",JsonArray(plans.map(::JsonPrimitive)))}
            println(evidence)
            System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let {java.nio.file.Files.writeString(Path.of(it,"category-deletion-performance.json"),evidence.toString())}
        } finally {workspaces.delete("synthetic-admin","分类删除性能");workspaces.select("synthetic-admin","fixture-a")}
    }
}
