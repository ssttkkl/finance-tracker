package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class ProjectionRebuildMigrationTest {
    private fun inWorkspace(action:(Database,String,Long)->Unit) {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val workspaces=WorkspaceService(db);val name="管理员重建测试";val workspace=workspaces.create("synthetic-admin",name).activeWorkspaceId!!;val account=deletionAccount(db,workspace)
        try {action(db,workspace,account)} finally {setAccountTestRole(db,workspace,"admin");workspaces.delete("synthetic-admin",name);workspaces.select("synthetic-admin","fixture-a")}
    }
    private fun snapshot(db:Database,workspace:String)=db.read {c ->listOf("cash_transactions","cash_entries","cash_entry_members","cash_projection_states","cash_projection_datasets","cash_projections","cash_projection_members","cash_projection_relations").associateWith {table ->
        c.prepareStatement("SELECT * FROM $table WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {r ->val n=r.metaData.columnCount;buildList {while(r.next()) add((1..n).map {r.getString(it)})}.sortedBy {it.toString()}}}
    }}
    @Test fun onlyCurrentAdministratorsCanPublishARebuiltDatasetThroughHttp()=inWorkspace {db,workspace,account ->testApplication {
        db.write {c ->repeat(3) {deletionFact(c,db,workspace,account,null)}}
        application {financeModule(db)}
        for(role in listOf("viewer","editor")) {
            setAccountTestRole(db,workspace,role);val before=snapshot(db,workspace)
            val response=client.post("/api/v1/operations/projections/rebuild") {bearerAuth("synthetic-admin")}
            assertEquals(403,response.status.value,response.bodyAsText());assertEquals(before,snapshot(db,workspace))
            assertEquals(if(role=="viewer") "当前角色仅可查看账本。" else "只有管理员可以执行该操作。",Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
        }
        setAccountTestRole(db,workspace,"admin")
        val response=client.post("/api/v1/operations/projections/rebuild") {bearerAuth("synthetic-admin")}
        assertEquals(200,response.status.value,response.bodyAsText());val status=Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(JsonPrimitive(2),status["projection_version"]);assertEquals(JsonPrimitive(3),status["member_count"]);assertEquals(JsonPrimitive("ready"),status["availability"])
        assertEquals(status,ProjectionStatusService(db).status("synthetic-admin"))
    }}
    @Test fun domainAndRuntimeFailuresWithTheSameCodePreserveDifferentPythonHttpContracts() {
        for(domain in listOf(false,true)) inWorkspace {db,workspace,account ->testApplication {
            db.write {c ->deletionFact(c,db,workspace,account,null)};val before=snapshot(db,workspace)
            val rebuild=ProjectionRebuildService(db,beforePublish={throw if(domain) CashProjectionDomainFailure("projection.incomplete") else CashProjectionFailure("projection.incomplete")})
            application {financeModule(db,projectionRebuild=rebuild)}
            val response=client.post("/api/v1/operations/projections/rebuild") {bearerAuth("synthetic-admin")}
            assertEquals(if(domain) 409 else 503,response.status.value,response.bodyAsText())
            assertEquals(Json.parseToJsonElement("""{"error":{"code":"projection.incomplete","message":"${if(domain) "账本投影重建失败，请检查数据后重试。" else "账本投影重建失败，请稍后重试。"}"}}"""),Json.parseToJsonElement(response.bodyAsText()))
            val after=snapshot(db,workspace);for(table in before.keys-"cash_projection_states") assertEquals(before[table],after[table],table)
            assertEquals("failed",ProjectionStatusService(db).status("synthetic-admin").getValue("last_build_status").jsonPrimitive.content)
        }}
    }
    @Test fun staleHintAndReturnTimeRevocationRollBackPublicationWithoutWritingFailureDiagnostics() {
        for(mode in listOf("hint","return")) inWorkspace {db,workspace,account ->testApplication {
            db.write {c ->deletionFact(c,db,workspace,account,null)};val before=snapshot(db,workspace)
            val rebuild=ProjectionRebuildService(db,afterHint={if(mode=="hint") setAccountTestRole(db,workspace,"editor")},beforeReturn={c ->
                if(mode=="return") c.prepareStatement("UPDATE workspace_memberships SET role='editor' WHERE workspace_id=? AND user_id='admin'").use {it.setString(1,workspace);it.executeUpdate()}
            })
            application {financeModule(db,projectionRebuild=rebuild)}
            val response=client.post("/api/v1/operations/projections/rebuild") {bearerAuth("synthetic-admin")}
            assertEquals(403,response.status.value,response.bodyAsText());assertEquals(before,snapshot(db,workspace))
        }}
    }
    @Test fun storageAndUnknownFailuresUseSafeRuntimeHttpErrorsAndDoNotPublish() {
        for(mode in listOf("storage","unknown","initial-storage")) inWorkspace {db,workspace,account ->testApplication {
            db.write {c ->deletionFact(c,db,workspace,account,null)};val before=snapshot(db,workspace)
            val rebuild=if(mode=="initial-storage") ProjectionRebuildService(Database("jdbc:sqlite:/Users/huangwenlong/Library/Caches/finance-kotlin-backend/nonexistent-rebuild_test.db"))
                else ProjectionRebuildService(db,beforePublish={c ->if(mode=="storage") c.createStatement().use {it.execute("SELECT nonexistent_synthetic_column FROM cash_projection_states")} else throw IllegalStateException("private synthetic details")})
            application {financeModule(db,projectionRebuild=rebuild)}
            val response=client.post("/api/v1/operations/projections/rebuild") {bearerAuth("synthetic-admin")}
            assertEquals(503,response.status.value,response.bodyAsText());val error=Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
            assertEquals(if(mode=="unknown") "projection.failed" else "storage.connect: unable to connect to selected storage (${if(mode=="initial-storage" || db.sqlite) "file SQLite" else "postgresql"})",error.getValue("code").jsonPrimitive.content)
            assertEquals("账本投影重建失败，请稍后重试。",error.getValue("message").jsonPrimitive.content);assertFalse(response.bodyAsText().contains("private"))
            val after=snapshot(db,workspace);for(table in before.keys-(if(mode=="unknown") setOf("cash_projection_states") else emptySet())) assertEquals(before[table],after[table],"$mode $table")
        }}
    }
    @Test fun lockWaitRechecksRoleWorkspaceExpiryAndLogoutBeforeAnyPublication() {
        for(mode in listOf("role","workspace","expiry","logout")) inWorkspace {db,workspace,account ->testApplication {
            db.write {c ->deletionFact(c,db,workspace,account,null)}
            val original=db.read {c ->c.prepareStatement("SELECT * FROM user_sessions WHERE token_digest=?").use {it.setString(1,tokenDigest("synthetic-admin"));it.executeQuery().use {r ->assertTrue(r.next());(1..r.metaData.columnCount).associate {index ->val name=r.metaData.getColumnName(index);name to if(name in setOf("created_at","expires_at")) contractInstant(r.getString(index),db.sqlite) else r.getString(index)}}}}
            fun revoke(c:java.sql.Connection) {
                when(mode) {
                    "role" ->c.prepareStatement("UPDATE workspace_memberships SET role='editor' WHERE workspace_id=? AND user_id='admin'").use {it.setString(1,workspace);assertEquals(1,it.executeUpdate())}
                    "workspace" ->c.prepareStatement("UPDATE user_sessions SET active_workspace_id='fixture-a' WHERE token_digest=?").use {it.setString(1,tokenDigest("synthetic-admin"));assertEquals(1,it.executeUpdate())}
                    "expiry" ->c.prepareStatement("UPDATE user_sessions SET expires_at=? WHERE token_digest=?").use {it.instant(1,deletionTime,db.sqlite);it.setString(2,tokenDigest("synthetic-admin"));assertEquals(1,it.executeUpdate())}
                    "logout" ->c.prepareStatement("DELETE FROM user_sessions WHERE token_digest=?").use {it.setString(1,tokenDigest("synthetic-admin"));assertEquals(1,it.executeUpdate())}
                }
            }
            val arrived=java.util.concurrent.CountDownLatch(1)
            val service=ProjectionRebuildService(db,afterHint={arrived.countDown();if(db.sqlite) db.write(::revoke)})
            application {financeModule(db,projectionRebuild=service)}
            val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
            val holder=if(db.sqlite) null else java.sql.DriverManager.getConnection(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")).apply {
                autoCommit=false;prepareStatement("SELECT id FROM workspaces WHERE id=? FOR UPDATE").use {it.setString(1,workspace);it.executeQuery().use {r ->assertTrue(r.next())}}
            }
            var pending:java.util.concurrent.CompletableFuture<io.ktor.client.statement.HttpResponse>?=null
            val before=snapshot(db,workspace)
            try {
                pending=java.util.concurrent.CompletableFuture.supplyAsync {kotlinx.coroutines.runBlocking {client.post("/api/v1/operations/projections/rebuild") {bearerAuth("synthetic-admin")}}}
                if(holder!=null) {
                    assertTrue(arrived.await(10,java.util.concurrent.TimeUnit.SECONDS))
                    val pid=holder.createStatement().use {it.executeQuery("SELECT pg_backend_pid()").use {r ->r.next();r.getInt(1)}}
                    val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);var waiting=false
                    while(!waiting && System.nanoTime()<deadline) {waiting=db.read {c ->c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)) AND wait_event_type='Lock'").use {it.setInt(1,pid);it.executeQuery().use {r ->r.next();r.getInt(1)>0}}};if(!waiting) Thread.sleep(10)}
                    assertTrue(waiting,"actual workspace lock wait $mode");revoke(holder);holder.commit()
                }
                val response=pending.get(10,java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(if(mode in setOf("expiry","logout")) 401 else 403,response.status.value,"$mode ${response.bodyAsText()}")
                assertEquals(before,snapshot(db,workspace),mode)
            } finally {
                holder?.rollback();holder?.close();pending?.get(10,java.util.concurrent.TimeUnit.SECONDS)
                db.write {c ->c.prepareStatement("DELETE FROM user_sessions WHERE token_digest=?").use {it.setString(1,tokenDigest("synthetic-admin"));it.executeUpdate()};deletionInsert(c,db,"user_sessions",original)}
            }
        }}
    }
}
