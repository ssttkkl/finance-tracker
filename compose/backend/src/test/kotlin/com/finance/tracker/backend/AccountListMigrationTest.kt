package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class AccountListMigrationTest {
    @Test fun cashAndInvestmentListsKeepAllFiveTypesOrderedAndNeverExposeOtherWorkspaces() {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val database=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val workspaces=WorkspaceService(database);val service=AccountService(database)
        val workspace=requireNotNull(workspaces.create("synthetic-admin","账户列表测试").activeWorkspaceId)
        val foreign=database.write {c ->
            c.prepareStatement("INSERT INTO accounts(workspace_id,name,type,active,revision,currencies,metadata_json,created_at,updated_at) VALUES('fixture-b','account-list-private','cash',true,1,${if(database.sqlite) "'[]'" else "'[]'::json"},${if(database.sqlite) "'{}'" else "'{}'::json"},CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id").use {it.executeQuery().use {rows ->rows.next();rows.getLong(1)}}
        }
        try {
            assertEquals(JsonArray(emptyList()),service.list("synthetic-admin","cash").getValue("items"))
            val ids=linkedMapOf<String,String>()
            for(type in listOf("cash","loan","lend","security","crypto")) {
                val result=service.create("synthetic-admin",buildJsonObject {put("name",type);put("type",type);put("currencies",JsonArray(listOf(JsonPrimitive("USD"))))},"create-$type")
                ids[type]=result.getValue("account_id").jsonPrimitive.content
            }
            service.update("synthetic-admin",ids.getValue("loan"),buildJsonObject {put("active",false)},1,"inactive-loan")
            val cash=service.list("synthetic-admin","cash").getValue("items").jsonArray
            assertEquals(listOf("cash","loan","lend"),cash.map {it.jsonObject.getValue("type").jsonPrimitive.content})
            assertEquals(listOf("cash","loan","lend").map {ids.getValue(it)},cash.map {it.jsonObject.getValue("id").jsonPrimitive.content})
            assertEquals(false,cash.single {it.jsonObject.getValue("type").jsonPrimitive.content=="loan"}.jsonObject.getValue("active").jsonPrimitive.boolean)
            cash.forEach {assertEquals(JsonArray(listOf(JsonPrimitive("USD"))),it.jsonObject["currencies"])}
            val investment=service.list("synthetic-admin","investment").getValue("items").jsonArray
            assertEquals(listOf("security","crypto"),investment.map {it.jsonObject.getValue("type").jsonPrimitive.content})
            investment.forEach {assertFalse("currencies" in it.jsonObject)}
            setAccountTestRole(database,workspace,"viewer")
            assertEquals(cash,service.list("synthetic-admin","cash").getValue("items"))
            assertEquals(investment,service.list("synthetic-admin","investment").getValue("items"))
        } finally {
            database.write {c ->c.prepareStatement("DELETE FROM accounts WHERE workspace_id='fixture-b' AND id=?").use {it.setLong(1,foreign);it.executeUpdate()}}
            setAccountTestRole(database,workspace,"admin");workspaces.delete("synthetic-admin","账户列表测试");workspaces.select("synthetic-admin","fixture-a")}
    }
    @Test fun repeatedViewQueryUsesTheLastValueIncludingAnExplicitEmptyValue() = testApplication {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val database=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val workspaces=WorkspaceService(database);val service=AccountService(database)
        workspaces.create("synthetic-admin","账户重复筛选测试")
        for(type in listOf("cash","security")) service.create("synthetic-admin",buildJsonObject {put("name",type);put("type",type)},"create-$type")
        application {financeModule(database)}
        try {
            for((query,type) in listOf("view=cash&view=investment" to "security","view=investment&view=cash" to "cash")) {
                val response=client.get("/api/v1/accounts?$query") {bearerAuth("synthetic-admin")}
                assertEquals(200,response.status.value)
                assertEquals(type,Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("items").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
            }
            val empty=client.get("/api/v1/accounts?view=cash&view=") {bearerAuth("synthetic-admin")}
            assertEquals(400,empty.status.value)
            assertEquals("invalid_filter",Json.parseToJsonElement(empty.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        } finally {workspaces.delete("synthetic-admin","账户重复筛选测试");workspaces.select("synthetic-admin","fixture-a")}
    }

}
