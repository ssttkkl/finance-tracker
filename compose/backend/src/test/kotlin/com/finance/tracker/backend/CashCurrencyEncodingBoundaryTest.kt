package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashCurrencyEncodingBoundaryTest {
    @Test fun rawCurrencySurrogatesCannotBorrowTheIdentityOfAConfiguredAstralCurrency()=testApplication {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val workspaces=WorkspaceService(db);val name="币种原始编码验证";val workspace=workspaces.create("synthetic-admin",name).activeWorkspaceId!!;deletionAccount(db,workspace)
        db.write {c ->c.prepareStatement("UPDATE accounts SET currencies="+(if(db.sqlite) "?" else "CAST(? AS json)")+" WHERE workspace_id=?").use {it.setString(1,"[\"𐐀AB\"]");it.setString(2,workspace);it.executeUpdate()}}
        application {financeModule(db)}
        try {
            val prefix="""{"account_name":"账户","amount":"1","currency":"""".toByteArray()
            val suffix="""AB","occurred_at":"2026-10-24"}""".toByteArray()
            for((bytes,expected) in listOf(byteArrayOf(0xed.toByte(),0xa0.toByte(),0x81.toByte(),0xed.toByte(),0xb0.toByte(),0x80.toByte()) to "请先选择币种",
                "\\ud801\\udc00".toByteArray() to "projection.invalid_fact")) {
                val response=client.post("/api/v1/cash-records") {bearerAuth("synthetic-admin");contentType(ContentType.Application.Json);setBody(prefix+bytes+suffix)}
                assertEquals(400,response.status.value,response.bodyAsText())
                assertEquals(JsonPrimitive(expected),Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject["message"])
                db.read {c ->for(table in listOf("cash_transactions","cash_transaction_components","cash_entries","cash_projections","ledger_snapshots")) c.prepareStatement("SELECT count(*) FROM $table WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {r ->assertTrue(r.next());assertEquals(0,r.getInt(1),table)}}}
            }
        } finally {workspaces.delete("synthetic-admin",name);workspaces.select("synthetic-admin","fixture-a")}
    }
}
