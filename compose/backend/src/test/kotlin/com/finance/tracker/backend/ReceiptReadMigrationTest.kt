package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class ReceiptReadMigrationTest {
    private fun inWorkspace(action: (Database,String)->Unit) {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val database=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val workspaces=WorkspaceService(database);val workspace=workspaces.create("synthetic-admin","收据读取测试").activeWorkspaceId!!
        try {action(database,workspace)} finally {setAccountTestRole(database,workspace,"admin");workspaces.delete("synthetic-admin","收据读取测试");workspaces.select("synthetic-admin","fixture-a")}
    }
    private fun seed(database: Database,workspace: String,key: String="request-1",id: String="native-receipt",rows: Int=0) = database.write {c ->
        val json=if(database.sqlite) "?" else "CAST(? AS JSON)"
        c.prepareStatement("INSERT INTO cash_mutation_receipts(id,workspace_id,user_id,idempotency_key,request_digest,operation_kind,result_summary,created_at) VALUES(?,?,?,?,?,'legacy_import',$json,?)").use {
            it.setString(1,id);it.setString(2,workspace);it.setString(3,"admin");it.setString(4,key);it.setString(5,"a".repeat(64))
            it.setString(6,"""{"summary":{"new_rows":1},"legacy_by_account":{"历史账户":4}}""");it.instant(7,java.time.Instant.parse("2026-01-01T00:00:00.123400Z"),database.sqlite);it.executeUpdate()
        }
        repeat(rows) {index ->c.prepareStatement("INSERT INTO cash_mutation_results(workspace_id,receipt_id,result_kind,item_ref,outcome) VALUES(?,?,'row',?,'duplicate')").use {
            it.setString(1,workspace);it.setString(2,id);it.setString(3,"f1:r$index");it.executeUpdate()
        }}
    }
    @Test fun legacyQueryPreservesEveryOriginalKeyAndWhitelistedResult() = inWorkspace {database,workspace ->testApplication {
        application {financeModule(database)}
        setAccountTestRole(database,workspace,"editor")
        for((index,key) in listOf("旧结果/键 ?&+=#%","导".repeat(255)," key with internal spaces ").withIndex()) {
            val id="legacy-api-$index";seed(database,workspace,key,id)
            val response=client.get("/api/v1/cash-mutations/receipts/by-key?key="+java.net.URLEncoder.encode(key,Charsets.UTF_8)) {bearerAuth("synthetic-admin")}
            assertEquals(200,response.status.value,response.bodyAsText());val value=Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(id,value["receipt_id"]?.jsonPrimitive?.content);assertEquals("legacy_import",value["operation_kind"]?.jsonPrimitive?.content)
            assertEquals(Json.parseToJsonElement("""{"new_rows":1}"""),value["summary"]);assertEquals(Json.parseToJsonElement("""{"历史账户":4}"""),value["legacy_by_account"])
            assertEquals(JsonPrimitive(true),value["committed"]);assertFalse("status" in value)
            assertTrue(setOf("request_digest","session_digest","edit_context_digest","retry_context_id").none {it in value})
            assertEquals(value,Json.parseToJsonElement(client.get("/api/v1/cash-mutations/receipts/$id") {bearerAuth("synthetic-admin")}.bodyAsText()))
            for(kind in listOf("rows","relations")) {
                val page=client.get("/api/v1/cash-mutations/receipts/$id/$kind") {bearerAuth("synthetic-admin")}
                assertEquals(200,page.status.value);assertEquals(JsonArray(emptyList()),Json.parseToJsonElement(page.bodyAsText()).jsonObject["items"])
            }
            database.read {c ->assertFailsWith<IllegalArgumentException> {ReceiptRepository(c,workspace,database.sqlite).byKey("admin",key)}}
        }
    }}
    @Test fun invalidQueryIsBoundedAndNeverEchoesPrivateKey() = inWorkspace {database,workspace ->
        setAccountTestRole(database,workspace,"editor")
        val server=embeddedServer(Netty,environment=applicationEnvironment {},configure={connector {host="127.0.0.1";port=0};configureFinanceHttp()}) {financeModule(database)}.start(wait=false)
        try {
        val port=runBlocking {server.engine.resolvedConnectors().single().port}
        for(query in listOf("","key=","key=a&key=b","key=a&private=secret","key=%FF","key=%","key="+"a".repeat(256),"key="+"a".repeat(5000))) {
            val (status,body)=rawHttpGet(port,"/api/v1/cash-mutations/receipts/by-key?$query")
            assertEquals(422,status,"raw query length=${query.length}");val error=Json.parseToJsonElement(body).jsonObject.getValue("error").jsonObject
            assertTrue(error.getValue("message").jsonPrimitive.content.isNotEmpty());assertFalse("secret" in body);assertFalse("input" in error)
        }
        } finally {server.stop(100,1000)}
    }
    @Test fun cashAuthenticationErrorsKeepTheirCompletePublicContract() = inWorkspace {database,_ ->testApplication {
        application {financeModule(database)}
        for((token,status) in listOf(null to 401,"synthetic-expired" to 401,"synthetic-removed" to 403)) {
            val response=client.get("/api/v1/cash-mutations/receipts/missing") {token?.let {bearerAuth(it)}}
            assertEquals(status,response.status.value)
            val error=Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
            assertEquals(JsonArray(emptyList()),error["field_errors"]);assertEquals(JsonPrimitive(false),error["retryable"])
        }
    }}
    @Test fun receiptAndKeyReadsRecheckPermissionBeforeReturning() = inWorkspace {database,workspace ->
        if(!database.sqlite) {
            seed(database,workspace)
            for(byKey in listOf(false,true)) {
                val service=ReceiptReadService(database,beforeRead={database.write {c ->c.prepareStatement("DELETE FROM workspace_memberships WHERE workspace_id=? AND user_id='admin'").use {it.setString(1,workspace);it.executeUpdate()}}})
                try {
                    assertFailsWith<AccessFailure> {if(byKey) service.receiptByKey("synthetic-admin","request-1") else service.receipt("synthetic-admin","native-receipt")}
                } finally {database.write {c ->c.prepareStatement("INSERT INTO workspace_memberships(workspace_id,user_id,role,created_at) VALUES(?,'admin','admin',CURRENT_TIMESTAMP)").use {it.setString(1,workspace);it.executeUpdate()}}}
            }
        }
    }
    @Test fun everyReceiptReadWaitsForWorkspaceAndRechecksRevocation() = inWorkspace {database,workspace ->
        if(!database.sqlite) {
            seed(database,workspace)
            for(kind in listOf("id","key","rows","relations")) {
                val arrived=java.util.concurrent.CountDownLatch(1)
                val service=ReceiptReadService(database,beforeWorkspaceLock={arrived.countDown()})
                val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
                java.sql.DriverManager.getConnection(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")).use {holder ->
                    holder.autoCommit=false
                    holder.prepareStatement("SELECT id FROM workspaces WHERE id=? FOR UPDATE").use {it.setString(1,workspace);it.executeQuery().use {rows ->check(rows.next())}}
                    val pending=java.util.concurrent.CompletableFuture.supplyAsync {
                        runCatching {when(kind) {
                            "id" ->service.receipt("synthetic-admin","native-receipt")
                            "key" ->service.receiptByKey("synthetic-admin","request-1")
                            else ->service.results("synthetic-admin","native-receipt",if(kind=="rows") "row" else "relation",ReceiptPage(0,50))
                        }}
                    }
                    try {
                        assertTrue(arrived.await(10,java.util.concurrent.TimeUnit.SECONDS))
                        assertFailsWith<java.util.concurrent.TimeoutException> {pending.get(150,java.util.concurrent.TimeUnit.MILLISECONDS)}
                        holder.prepareStatement("DELETE FROM workspace_memberships WHERE workspace_id=? AND user_id='admin'").use {it.setString(1,workspace);assertEquals(1,it.executeUpdate())}
                        holder.commit()
                        assertIs<AccessFailure>(pending.get(10,java.util.concurrent.TimeUnit.SECONDS).exceptionOrNull())
                    } finally {
                        holder.rollback()
                        pending.get(10,java.util.concurrent.TimeUnit.SECONDS)
                        database.write {c ->c.prepareStatement("INSERT INTO workspace_memberships(workspace_id,user_id,role,created_at) VALUES(?,'admin','admin',CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING").use {it.setString(1,workspace);it.executeUpdate()}}
                    }
                }
            }
        }
    }
    @Test fun resultsAreStablyPagedBoundedAndPermanentReferencesNeedNoLiveTarget() = inWorkspace {database,workspace ->
        seed(database,workspace,rows=5);val service=ReceiptReadService(database)
        val first=service.results("synthetic-admin","native-receipt","row",ReceiptPage(0,2))
        val second=service.results("synthetic-admin","native-receipt","row",ReceiptPage(first.getValue("next_after").jsonPrimitive.long,2))
        val third=service.results("synthetic-admin","native-receipt","row",ReceiptPage(second.getValue("next_after").jsonPrimitive.long,2))
        assertEquals((0..4).map {"f1:r$it"},listOf(first,second,third).flatMap {it.getValue("items").jsonArray.map {it.jsonObject.getValue("item_ref").jsonPrimitive.content}})
        assertEquals(JsonNull,third["next_after"])
        database.read {c ->assertEquals("mutation_page_invalid",assertFailsWith<IllegalArgumentException> {ReceiptRepository(c,workspace,database.sqlite).results("admin","native-receipt",limit=201)}.message)}
        database.write {c ->c.prepareStatement("INSERT INTO cash_mutation_results(workspace_id,receipt_id,result_kind,item_ref,outcome,account_id) VALUES(?,?,'account','account-1','deleted','999999')").use {
            it.setString(1,workspace);it.setString(2,"native-receipt");it.executeUpdate()
        }}
        database.read {c ->
            c.prepareStatement("SELECT account_id FROM cash_mutation_results WHERE receipt_id='native-receipt' AND result_kind='account' AND workspace_id=?").use {
                it.setString(1,workspace);it.executeQuery().use {rows ->assertTrue(rows.next());assertEquals("999999",rows.getString(1))}
            }
            val page=ReceiptRepository(c,workspace,database.sqlite).results("admin","native-receipt",resultKind="account")!!
            assertEquals("999999",page.getValue("items").jsonArray.single().jsonObject.getValue("account_id").jsonPrimitive.content)
        }
    }
}
