package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashTransferBoundaryMigrationTest {
    @Test fun directTransferReturnsRealJsonRowsAfterOneCompleteEditorWrite()=testApplication {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val workspaces=WorkspaceService(db);val name="转账响应原断言";val workspace=workspaces.create("synthetic-admin",name).activeWorkspaceId!!
        db.write {c ->for(account in listOf("response-from","response-to")) deletionInsert(c,db,"accounts",linkedMapOf("workspace_id" to workspace,"name" to account,"type" to "cash","active" to true,"revision" to 1L,
            "currencies" to JsonArray(listOf(JsonPrimitive("CNY"),JsonPrimitive("USD"))),"metadata_json" to JsonObject(emptyMap()),"created_at" to deletionTime,"updated_at" to deletionTime))}
        setAccountTestRole(db,workspace,"editor")
        application {financeModule(db)}
        try {
            for((currency,toAmount) in listOf("CNY" to null,"USD" to "17.25000")) {
                val before=db.read {c ->c.createStatement().use {it.executeQuery("SELECT id FROM cash_transactions").use {r ->buildSet {while(r.next()) add(r.getLong(1))}}}}
                val payload=buildJsonObject {
                    put("from_account","response-from");put("to_account","response-to");put("from_currency","CNY");put("to_currency",currency)
                    put("amount","123.4500");put("date","2026-10-24");put("time","04:30:00");put("note","response regression")
                    if(toAmount!=null) put("to_amount",toAmount)
                }
                val response=client.post("/api/v1/cashflow/transfer") {bearerAuth("synthetic-admin");contentType(ContentType.Application.Json);setBody(payload.toString())}
                assertEquals(201,response.status.value,response.bodyAsText())
                val body=Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(JsonPrimitive(true),body["ok"])
                assertEquals(listOf("-123.4500",toAmount ?: "123.4500"),body.getValue("rows").jsonArray.map {it.jsonObject.getValue("amount").jsonPrimitive.content})
                assertEquals(JsonPrimitive("123.4500"),body.getValue("details").jsonObject["amount"])
                db.read {c ->
                    val added=c.createStatement().use {it.executeQuery("SELECT id,amount,record_origin,manual_transfer_id FROM cash_transactions").use {r ->buildList {
                        while(r.next()) if(r.getLong(1) !in before) add(listOf(r.getString(1),r.getString(2),r.getString(3),r.getString(4)))
                    }}}
                    assertEquals(2,added.size)
                    val amounts=added.map {it[1].toBigDecimal().stripTrailingZeros()}.toSet()
                    assertEquals(setOf("-123.4500".toBigDecimal().stripTrailingZeros(),(toAmount ?: "123.4500").toBigDecimal().stripTrailingZeros()),amounts)
                    assertEquals(setOf("manual_transfer"),added.map {it[2]}.toSet())
                    assertEquals(1,added.map {it[3]}.toSet().size);assertTrue(added.first()[3].isNotEmpty())
                    val ids=added.map {it[0].toLong()}.toSet()
                    c.prepareStatement("SELECT kind,state,active,rule_id FROM transaction_relations WHERE workspace_id=? AND primary_transaction_id IN (?,?) AND secondary_transaction_id IN (?,?)").use {
                        it.setString(1,workspace);ids.forEachIndexed {index,id ->it.setLong(index+2,id);it.setLong(index+4,id)}
                        it.executeQuery().use {r ->assertTrue(r.next());assertEquals("transfer_pair",r.getString(1));assertEquals("confirmed",r.getString(2));assertTrue(r.getBoolean(3));assertEquals("manual.transfer.v1",r.getString(4));assertFalse(r.next())}
                    }
                }
            }
        } finally {setAccountTestRole(db,workspace,"admin");workspaces.delete("synthetic-admin",name);workspaces.select("synthetic-admin","fixture-a")}
    }
}
