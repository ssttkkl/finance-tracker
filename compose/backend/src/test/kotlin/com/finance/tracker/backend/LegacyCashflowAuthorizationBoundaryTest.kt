package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.*
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class LegacyCashflowAuthorizationBoundaryTest {
    private val tables=listOf("cash_transactions","cash_transaction_components","investment_events","transaction_relations","cash_relation_candidates","ledger_snapshots","cash_entries","cash_entry_members","cash_projection_states","cash_projection_datasets","cash_projections","cash_projection_members","cash_projection_relations")
    private fun financial(db:Database,workspace:String)=db.read {c ->tables.associateWith {table ->
        c.prepareStatement("SELECT * FROM $table WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {r ->buildSet {
            while(r.next()) add((1..r.metaData.columnCount).map {r.getString(it)})
        }}}
    }}
    private fun disruption(c:Connection,workspace:String,kind:String) {
        val sql=when(kind) {
            "role" ->"UPDATE workspace_memberships SET role='viewer' WHERE workspace_id=? AND user_id='admin'"
            "workspace" ->"UPDATE user_sessions SET active_workspace_id='fixture-b' WHERE active_workspace_id=? AND user_id='admin'"
            else ->"DELETE FROM user_sessions WHERE active_workspace_id=? AND user_id='admin'"
        }
        c.prepareStatement(sql).use {it.setString(1,workspace);assertEquals(1,it.executeUpdate())}
    }
    private fun scenario(kind:String,checkin:Boolean)=testApplication {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val url=config.getValue("jdbc_url").jsonPrimitive.content;val user=System.getenv("FT_KOTLIN_TEST_DB_USER");val password=System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")
        val db=Database(url,user,password);val workspaces=WorkspaceService(db);val name="旧现金权限测试"
        val workspace=workspaces.create("synthetic-admin",name).activeWorkspaceId!!;deletionAccount(db,workspace)
        val session=db.read {c ->c.prepareStatement("SELECT id,user_id,token_digest,active_workspace_id,expires_at,created_at FROM user_sessions WHERE token_digest=?").use {
            it.setString(1,tokenDigest("synthetic-admin"));it.executeQuery().use {r ->assertTrue(r.next());(1..6).map {r.getObject(it)}}
        }}
        val before=financial(db,workspace);val arrived=CountDownLatch(1)
        val service=LegacyCashflowService(db,beforeWrite={if(db.sqlite) db.write {disruption(it,workspace,kind)}},beforeWorkspaceLock={arrived.countDown()})
        application {financeModule(db,legacyCashflow=service)}
        val holder=if(db.sqlite) null else DriverManager.getConnection(url,user,password).apply {
            autoCommit=false;prepareStatement("SELECT id FROM workspaces WHERE id=? FOR UPDATE").use {it.setString(1,workspace);it.executeQuery().use {r ->assertTrue(r.next())}}
        }
        var pending:CompletableFuture<io.ktor.client.statement.HttpResponse>?=null
        try {
            val operation=CompletableFuture.supplyAsync {kotlinx.coroutines.runBlocking {client.post("/api/v1/cashflow/"+if(checkin) "checkin" else "add") {
                bearerAuth("synthetic-admin");contentType(ContentType.Application.Json);setBody(if(checkin) """{"account":"账户","balance":"100","currency":"CNY","date":"2026-08-04"}""" else """{"account":"账户","amount":"-10","counterparty":"test","currency":"CNY","date":"2026-08-04"}""")
            }}}
            pending=operation
            assertTrue(arrived.await(10,TimeUnit.SECONDS))
            if(holder!=null) {
                val pid=holder.createStatement().use {it.executeQuery("SELECT pg_backend_pid()").use {r ->assertTrue(r.next());r.getInt(1)}}
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);var waiting=false
                while(!waiting && System.nanoTime()<deadline) {
                    waiting=db.read {c ->c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)) AND wait_event_type='Lock'").use {
                        it.setInt(1,pid);it.executeQuery().use {r ->assertTrue(r.next());r.getInt(1)>0}
                    }}
                    if(!waiting) Thread.sleep(10)
                }
                assertTrue(waiting,"writer must enter the actual PostgreSQL lock queue")
                assertFalse(operation.isDone);disruption(holder,workspace,kind);holder.commit()
            }
            val response=operation.get(10,TimeUnit.SECONDS)
            assertEquals(if(kind=="logout") 401 else 403,response.status.value,response.bodyAsText())
            assertEquals(before,financial(db,workspace))
        } finally {
            holder?.rollback();holder?.close()
            pending?.handle {_,_ ->Unit}?.get(15,TimeUnit.SECONDS)
            db.write {c ->
                c.prepareStatement("DELETE FROM user_sessions WHERE token_digest=?").use {it.setString(1,tokenDigest("synthetic-admin"));it.executeUpdate()}
                c.prepareStatement("INSERT INTO user_sessions(id,user_id,token_digest,active_workspace_id,expires_at,created_at) VALUES(?,?,?,?,?,?)").use {s ->session.forEachIndexed {i,value ->s.setObject(i+1,value)};s.executeUpdate()}
                c.prepareStatement("UPDATE workspace_memberships SET role='admin' WHERE workspace_id=? AND user_id='admin'").use {it.setString(1,workspace);it.executeUpdate()}
            }
            workspaces.delete("synthetic-admin",name);workspaces.select("synthetic-admin","fixture-a")
        }
    }
    @Test fun roleIsRecheckedAfterMiddlewareAndActualWorkspaceLockWait()=scenario("role",false)
    @Test fun workspaceIsRecheckedAfterMiddlewareAndActualWorkspaceLockWait()=scenario("workspace",false)
    @Test fun logoutIsRecheckedAfterMiddlewareAndActualWorkspaceLockWait()=scenario("logout",false)
    @Test fun calibrationRechecksRoleAfterActualWorkspaceLockWait()=scenario("role",true)
    @Test fun calibrationRechecksWorkspaceAfterActualWorkspaceLockWait()=scenario("workspace",true)
    @Test fun calibrationRechecksLogoutAfterActualWorkspaceLockWait()=scenario("logout",true)
}
