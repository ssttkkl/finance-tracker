package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*

class AccountApiMigrationTest {
    private fun database(): Database {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    @Test fun accountApiUnifiesStableIdentityRevisionAndDurableReplay() = testApplication {
        val database=database();val workspaces=WorkspaceService(database)
        val workspace=requireNotNull(workspaces.create("synthetic-admin","账户 API 合同测试").activeWorkspaceId)
        setAccountTestRole(database,workspace,"editor")
        application {financeModule(database)}
        try {
            suspend fun create(name: String="独立账户",key: String?="create")=client.post("/api/v1/accounts") {
                bearerAuth("synthetic-admin");if(key!=null) header("Idempotency-Key",key);contentType(ContentType.Application.Json)
                setBody(buildJsonObject {put("name",name);put("type","cash");put("currencies",JsonArray(listOf(JsonPrimitive("CNY"))))}.toString())
            }
            val missing=create(key=null);assertEquals(400,missing.status.value)
            assertEquals("idempotency_key_required",Json.parseToJsonElement(missing.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            for((method,path) in listOf(HttpMethod.Put to "/api/v1/accounts/old-name",HttpMethod.Patch to "/api/v1/accounts/old-name/active",HttpMethod.Delete to "/api/v1/accounts/old-name")) {
                val response=client.request(path) {this.method=method;bearerAuth("synthetic-admin");contentType(ContentType.Application.Json);setBody("{}")}
                assertTrue(response.status.value in setOf(404,405))
            }
            val first=create();assertEquals(201,first.status.value)
            val account=Json.parseToJsonElement(first.bodyAsText()).jsonObject
            val id=account.getValue("account_id").jsonPrimitive.content
            assertTrue(id.isNotEmpty());assertEquals(1,account.getValue("account_revision").jsonPrimitive.int);assertEquals(JsonPrimitive(true),account["committed"])
            assertTrue(setOf("request_digest","session_digest","edit_context_digest","plan_id","operation_kind").intersect(account.keys).isEmpty())
            val path="/api/v1/accounts/by-id/$id"
            val current=client.get(path) {bearerAuth("synthetic-admin")}
            assertEquals(200,current.status.value);assertEquals("独立账户",Json.parseToJsonElement(current.bodyAsText()).jsonObject.getValue("name").jsonPrimitive.content)
            suspend fun patch(body:String,key:String)=client.patch(path) {bearerAuth("synthetic-admin");header("Idempotency-Key",key);contentType(ContentType.Application.Json);setBody(body)}
            val updated=patch("""{"expected_revision":1,"patch":{"name":"已修改"}}""","edit")
            assertEquals(200,updated.status.value);assertEquals(2,Json.parseToJsonElement(updated.bodyAsText()).jsonObject.getValue("account_revision").jsonPrimitive.int)
            assertEquals(account,Json.parseToJsonElement(create().bodyAsText()))
            val conflict=patch("""{"expected_revision":1,"patch":{"name":"过时修改"}}""","stale")
            assertEquals(409,conflict.status.value);assertEquals("account_changed",Json.parseToJsonElement(conflict.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            val keyConflict=create("同键不同内容");assertEquals(409,keyConflict.status.value)
            assertEquals("idempotency_conflict",Json.parseToJsonElement(keyConflict.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            val inactive=patch("""{"expected_revision":2,"patch":{"active":false}}""","deactivate")
            assertEquals(3,Json.parseToJsonElement(inactive.bodyAsText()).jsonObject.getValue("account_revision").jsonPrimitive.int)
            suspend fun delete()=client.delete(path) {bearerAuth("synthetic-admin");header("Idempotency-Key","delete");contentType(ContentType.Application.Json);setBody("""{"expected_revision":3}""")}
            val deleted=delete();assertEquals(200,deleted.status.value);assertEquals(JsonPrimitive(true),Json.parseToJsonElement(deleted.bodyAsText()).jsonObject["deleted"])
            assertEquals(Json.parseToJsonElement(deleted.bodyAsText()),Json.parseToJsonElement(delete().bodyAsText()))
            assertEquals(404,client.get(path) {bearerAuth("synthetic-admin")}.status.value)
            val replies=coroutineScope {(1..2).map {async {create("并发账户","parallel")}}.awaitAll()}
            assertTrue(replies.all {it.status.value==201});assertEquals(Json.parseToJsonElement(replies[0].bodyAsText()),Json.parseToJsonElement(replies[1].bodyAsText()))
        } finally {setAccountTestRole(database,workspace,"admin");workspaces.delete("synthetic-admin","账户 API 合同测试");workspaces.select("synthetic-admin","fixture-a")}
    }
    @Test fun badAccountProtocolRejectsAllOriginalInputsWithoutEchoOrWrites() = testApplication {
        val database=database();val workspaces=WorkspaceService(database)
        val workspace=requireNotNull(workspaces.create("synthetic-admin","账户坏协议测试").activeWorkspaceId)
        val created=AccountService(database).create("synthetic-admin",buildJsonObject {put("name","原账户");put("type","cash")},"seed")
        val id=created.getValue("account_id").jsonPrimitive.content
        setAccountTestRole(database,workspace,"editor")
        application {financeModule(database)}
        try {
            for(body in listOf("""{"expected_revision":1.0,"patch":{}}""","""{"expected_revision":true,"patch":{}}""",
                """{"expected_revision":1,"expected_revision":2,"patch":{}}""","""{"expected_revision":1,"patch":{"type":"loan"}}""",
                """{"expected_revision":1,"patch":{"active":"false"}}""","""{"expected_revision":1,"patch":{"source_payload":{"SQL":"private"}}}""",
                """{"expected_revision":NaN,"patch":{}}""","[]","not json")) {
                val response=client.patch("/api/v1/accounts/by-id/$id") {bearerAuth("synthetic-admin");header("Idempotency-Key","bad");contentType(ContentType.Application.Json);setBody(body)}
                assertEquals(400,response.status.value)
                val text=response.bodyAsText();assertEquals("account_payload_invalid",Json.parseToJsonElement(text).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                for(privateText in listOf("private","SQL","Traceback")) assertFalse(privateText in text)
                val account=AccountService(database).get("synthetic-admin",id);assertEquals(1L,account.accountRevision);assertEquals("原账户",account.name)
            }
            database.read {c ->c.prepareStatement("SELECT count(*) FROM cash_mutation_receipts WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {rows ->rows.next();assertEquals(1,rows.getInt(1))}}}
        } finally {setAccountTestRole(database,workspace,"admin");workspaces.delete("synthetic-admin","账户坏协议测试");workspaces.select("synthetic-admin","fixture-a")}
    }
    @Test fun realRegistrationAndWorkspaceSelectionCanCreateAnAccount() = testApplication {
        val database=database();application {financeModule(database)}
        val email="account-native-api@example.invalid"
        try {
            suspend fun create(token:String?)=client.post("/api/v1/accounts") {
                if(token!=null) bearerAuth(token);header("Idempotency-Key","create");contentType(ContentType.Application.Json);setBody("""{"name":"独立账户","type":"cash","currencies":["CNY"]}""")
            }
            assertEquals(401,create(null).status.value)
            val registration=client.post("/api/v1/auth/register") {contentType(ContentType.Application.Json);setBody("""{"email":"$email","password":"synthetic secure password"}""")}
            assertEquals(200,registration.status.value)
            val token=Json.parseToJsonElement(registration.bodyAsText()).jsonObject.getValue("access_token").jsonPrimitive.content
            val workspace=client.post("/api/v1/auth/workspaces") {bearerAuth(token);contentType(ContentType.Application.Json);setBody("""{"name":"账户 API 工作区"}""")}
            assertEquals(200,workspace.status.value);assertEquals(201,create(token).status.value)
            WorkspaceService(database).delete(token,"账户 API 工作区")
        } finally {database.write {c ->c.prepareStatement("DELETE FROM users WHERE email=?").use {it.setString(1,email);it.executeUpdate()}}}
    }
    @Test fun accountApiScopeAndCurrentMembershipProtectReadsAndWrites() = testApplication {
        val database=database();val workspaces=WorkspaceService(database);val commands=AccountService(database)
        val own=requireNotNull(workspaces.create("synthetic-admin","账户权限测试").activeWorkspaceId)
        val payload=buildJsonObject {put("name","账户");put("type","cash")}
        val ownId=commands.create("synthetic-admin",payload,"own").getValue("account_id").jsonPrimitive.content
        val foreign=requireNotNull(workspaces.create("synthetic-admin","账户权限隔离").activeWorkspaceId)
        val foreignId=commands.create("synthetic-admin",payload,"foreign").getValue("account_id").jsonPrimitive.content
        workspaces.select("synthetic-admin",own);setAccountTestRole(database,own,"editor")
        application {financeModule(database)}
        try {
            assertEquals(404,client.get("/api/v1/accounts/by-id/$foreignId") {bearerAuth("synthetic-admin")}.status.value)
            assertEquals(404,client.patch("/api/v1/accounts/by-id/$foreignId") {bearerAuth("synthetic-admin");header("Idempotency-Key","foreign");contentType(ContentType.Application.Json);setBody("""{"expected_revision":1,"patch":{}}""")}.status.value)
            setAccountTestRole(database,own,"viewer")
            assertEquals(403,client.post("/api/v1/accounts") {bearerAuth("synthetic-admin");header("Idempotency-Key","forbidden");contentType(ContentType.Application.Json);setBody(payload.toString())}.status.value)
            val view=client.get("/api/v1/accounts/by-id/$ownId") {bearerAuth("synthetic-admin")}
            assertEquals(200,view.status.value)
            assertEquals(JsonPrimitive(false),Json.parseToJsonElement(view.bodyAsText()).jsonObject.getValue("capabilities").jsonObject["can_update"])
        } finally {
            setAccountTestRole(database,own,"admin");workspaces.select("synthetic-admin",own);workspaces.delete("synthetic-admin","账户权限测试")
            workspaces.select("synthetic-admin",foreign);workspaces.delete("synthetic-admin","账户权限隔离");workspaces.select("synthetic-admin","fixture-a")
        }
    }

}
