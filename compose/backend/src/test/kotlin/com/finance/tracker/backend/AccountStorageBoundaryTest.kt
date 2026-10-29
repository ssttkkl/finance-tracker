package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class AccountStorageBoundaryTest {
    private fun database(): Database {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    @Test fun lostAccountResultUsesUnknownContractAndOriginalKeyRecoversExactlyOneCommit() = testApplication {
        val database=database();val workspaces=WorkspaceService(database)
        val workspace=requireNotNull(workspaces.create("synthetic-admin","账户未知结果测试").activeWorkspaceId)
        setAccountTestRole(database,workspace,"editor")
        val fail=AtomicBoolean(true)
        val service=AccountService(database,afterCommit={if(fail.compareAndSet(true,false)) throw StorageFailure("storage.connect")})
        application {financeModule(database,accounts=service)}
        try {
            suspend fun create()=client.post("/api/v1/accounts") {
                bearerAuth("synthetic-admin");header("Idempotency-Key","lost-result");contentType(ContentType.Application.Json)
                setBody("""{"name":"原键账户","type":"cash","currencies":["CNY"]}""")
            }
            val lost=create();assertEquals(503,lost.status.value)
            assertEquals(Json.parseToJsonElement("""{"error":{"code":"account_result_unknown","message":"账户操作结果待确认，请重试本次操作。"},"commit_state":"unknown"}"""),Json.parseToJsonElement(lost.bodyAsText()))
            val recovered=create();assertEquals(201,recovered.status.value)
            assertEquals(JsonPrimitive(true),Json.parseToJsonElement(recovered.bodyAsText()).jsonObject["committed"])
            assertEquals(recovered.bodyAsText(),create().bodyAsText())
            database.read { c ->for(table in listOf("accounts","cash_mutation_receipts","cash_mutation_results")) c.prepareStatement("SELECT count(*) FROM $table WHERE workspace_id=?").use {
                it.setString(1,workspace);it.executeQuery().use {rows ->rows.next();assertEquals(1,rows.getInt(1),table)}
            } }
        } finally {setAccountTestRole(database,workspace,"admin");workspaces.delete("synthetic-admin","账户未知结果测试");workspaces.select("synthetic-admin","fixture-a")}
    }
    @Test fun readStorageFailureReturnsOnlyTheAccountBusinessReason() = testApplication {
        val database=database()
        val unavailable=AccountService(Database("jdbc:sqlite:/Users/huangwenlong/Library/Caches/finance-kotlin-backend/nonexistent-account-read_test.db"))
        application {financeModule(database,accounts=unavailable)}
        val response=client.get("/api/v1/accounts/by-id/1") {bearerAuth("synthetic-admin")}
        assertEquals(503,response.status.value)
        assertEquals(Json.parseToJsonElement("""{"error":{"code":"account_unavailable","message":"暂时无法查看账户，请稍后重试。"}}"""),Json.parseToJsonElement(response.bodyAsText()))
    }
    @Test fun invalidRawControlsAndOversizedBodyNeverWriteAnAccount() = testApplication {
        val database=database();val workspaces=WorkspaceService(database)
        val workspace=requireNotNull(workspaces.create("synthetic-admin","账户协议测试").activeWorkspaceId)
        application {financeModule(database)}
        try {
            val invalid=buildList {
                for(code in 0..31) {
                    val character=code.toChar()
                    add("""{"name":"a${character}b","type":"cash"}""")
                    add("""{"na${character}me":"ok","type":"cash"}""")
                    add("""{"name":"ok","type":"cash","currencies":["C${character}Y"]}""")
                }
                add("x".repeat(65537))
            }
            for(raw in invalid) {
                val response=client.post("/api/v1/accounts") {
                    bearerAuth("synthetic-admin");header("Idempotency-Key","invalid");contentType(ContentType.Application.Json);setBody(raw)
                }
                assertEquals(400,response.status.value)
                assertEquals("account_payload_invalid",Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            }
            database.read { c ->for(table in listOf("accounts","cash_mutation_receipts","cash_mutation_results","ledger_snapshots")) c.prepareStatement("SELECT count(*) FROM $table WHERE workspace_id=?").use {
                it.setString(1,workspace);it.executeQuery().use {rows ->rows.next();assertEquals(0,rows.getInt(1),table)}
            } }
        } finally {workspaces.delete("synthetic-admin","账户协议测试");workspaces.select("synthetic-admin","fixture-a")}
    }
    @Test fun permissionIsRecheckedAfterMiddlewareAndWorkspaceLockWait() = testApplication {
        val database=database();val workspaces=WorkspaceService(database)
        val workspace=requireNotNull(workspaces.create("synthetic-admin","账户撤权等待测试").activeWorkspaceId)
        val arrived=java.util.concurrent.CountDownLatch(1)
        val service=AccountService(database,beforeWrite={
            if(database.sqlite) database.write {c ->c.prepareStatement("UPDATE workspace_memberships SET role='viewer' WHERE workspace_id=? AND user_id='admin'").use {it.setString(1,workspace);it.executeUpdate()}}
        },beforeWorkspaceLock={arrived.countDown()})
        application {financeModule(database,accounts=service)}
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val holder=if(database.sqlite) null else java.sql.DriverManager.getConnection(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")).apply {
            autoCommit=false
            prepareStatement("SELECT id FROM workspaces WHERE id=? FOR UPDATE").use {it.setString(1,workspace);it.executeQuery().use {rows ->check(rows.next())}}
        }
        try {
            val pending=java.util.concurrent.CompletableFuture.supplyAsync {kotlinx.coroutines.runBlocking {
                client.post("/api/v1/accounts") {bearerAuth("synthetic-admin");header("Idempotency-Key","revoked");contentType(ContentType.Application.Json);setBody("""{"name":"禁止写入","type":"cash"}""")}
            }}
            assertTrue(arrived.await(10,java.util.concurrent.TimeUnit.SECONDS))
            if(holder!=null) {
                holder.prepareStatement("UPDATE workspace_memberships SET role='viewer' WHERE workspace_id=? AND user_id='admin'").use {it.setString(1,workspace);it.executeUpdate()}
                holder.commit()
            }
            val response=pending.get(10,java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(403,response.status.value)
            assertEquals(Json.parseToJsonElement("""{"error":{"code":"workspace_forbidden","message":"当前用户无权访问该账户。"}}"""),Json.parseToJsonElement(response.bodyAsText()))
            database.read {c ->for(table in listOf("accounts","cash_mutation_receipts","cash_mutation_results","ledger_snapshots")) c.prepareStatement("SELECT count(*) FROM $table WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {rows ->rows.next();assertEquals(0,rows.getInt(1),table)}}}
        } finally {
            holder?.rollback();holder?.close()
            database.write {c ->c.prepareStatement("UPDATE workspace_memberships SET role='admin' WHERE workspace_id=? AND user_id='admin'").use {it.setString(1,workspace);it.executeUpdate()}}
            workspaces.delete("synthetic-admin","账户撤权等待测试");workspaces.select("synthetic-admin","fixture-a")
        }
    }

}
