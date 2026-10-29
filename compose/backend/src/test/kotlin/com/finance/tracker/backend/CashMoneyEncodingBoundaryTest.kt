package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashMoneyEncodingBoundaryTest {
    @Test fun rawSurrogateDigitsAreRejectedWhileEscapedAstralDigitsRemainValid()=testApplication {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val workspaces=WorkspaceService(db);val name="金额原始编码验证";val workspace=workspaces.create("synthetic-admin",name).activeWorkspaceId!!;deletionAccount(db,workspace)
        application {financeModule(db)}
        val tables=listOf("cash_transactions","cash_transaction_components","investment_events","transaction_relations","cash_relation_candidates","ledger_snapshots","cash_entries","cash_entry_members","cash_projection_states","cash_projection_datasets","cash_projections","cash_projection_members","cash_projection_relations")
        fun financial()=db.read {c ->tables.associateWith {table ->c.prepareStatement("SELECT * FROM $table WHERE workspace_id=?").use {s ->s.setString(1,workspace);s.executeQuery().use {r ->buildSet {while(r.next()) add((1..r.metaData.columnCount).map {r.getString(it)})}}}}}
        val cases=listOf(
            Triple("/api/v1/cash-records","""{"account_name":"账户","amount":"MONEY_DIGIT","currency":"CNY","occurred_at":"2026-10-24"}""","invalid_record"),
            Triple("/api/v1/cash-records","""{"account_name":"账户","amount":"1","currency":"CNY","occurred_at":"2026-10-24","components":[{"amount":"MONEY_DIGIT"}]}""","invalid_record"),
            Triple("/api/v1/cashflow/add","""{"account":"账户","amount":"MONEY_DIGIT","counterparty":"x","currency":"CNY","date":"2026-10-24"}""","amount_must_be_decimal_string"),
            Triple("/api/v1/cashflow/checkin","""{"account":"账户","balance":"MONEY_DIGIT","currency":"CNY","date":"2026-10-24"}""","balance_must_be_decimal_string"),
            Triple("/api/v1/cashflow/transfer","""{"from_account":"账户","to_account":"账户","amount":"MONEY_DIGIT","from_currency":"CNY","to_currency":"CNY","date":"2026-10-24","time":"04:30"}""","amount_must_be_decimal_string"),
            Triple("/api/v1/cashflow/transfer","""{"from_account":"账户","to_account":"账户","amount":"1","to_amount":"MONEY_DIGIT","from_currency":"CNY","to_currency":"USD","date":"2026-10-24","time":"04:30"}""","to_amount_must_be_decimal_string"))
        try {
            for((path,body,code) in cases) {
                val before=financial();val split=body.split("MONEY_DIGIT");assertEquals(2,split.size)
                val bytes=split[0].toByteArray()+byteArrayOf(0xed.toByte(),0xa0.toByte(),0x81.toByte(),0xed.toByte(),0xb2.toByte(),0xa1.toByte())+split[1].toByteArray()
                val rejected=client.post(path) {bearerAuth("synthetic-admin");contentType(ContentType.Application.Json);setBody(bytes)}
                assertEquals(400,rejected.status.value,path+rejected.bodyAsText())
                assertEquals(JsonPrimitive(code),Json.parseToJsonElement(rejected.bodyAsText()).jsonObject.getValue("error").jsonObject["code"])
                assertEquals(before,financial(),path)
                val accepted=client.post(path) {bearerAuth("synthetic-admin");contentType(ContentType.Application.Json);setBody(body.replace("MONEY_DIGIT","\\ud801\\udca1"))}
                assertEquals(201,accepted.status.value,path+accepted.bodyAsText())
            }
            val accountId=db.read {c ->c.prepareStatement("SELECT id FROM accounts WHERE workspace_id=? AND name='账户'").use {it.setString(1,workspace);it.executeQuery().use {r ->assertTrue(r.next());r.getLong(1).toString()}}}
            val digits=accountId.map {Character.toChars(0x104a0+it.digitToInt()).concatToString()}.joinToString("")
            val prefix="""{"account_name":"账户","amount":"1","currency":"CNY","occurred_at":"2026-10-24","components":[{"amount":"1","account_id":"""".toByteArray()
            val suffix="\"}]}".toByteArray()
            val cesu=digits.flatMap {char ->listOf((0xe0 or (char.code shr 12)).toByte(),(0x80 or (char.code shr 6 and 63)).toByte(),(0x80 or (char.code and 63)).toByte())}.toByteArray()
            val before=financial()
            val rejected=client.post("/api/v1/cash-records") {bearerAuth("synthetic-admin");contentType(ContentType.Application.Json);setBody(prefix+cesu+suffix)}
            assertEquals(400,rejected.status.value,rejected.bodyAsText());assertEquals(before,financial())
            assertTrue(Json.parseToJsonElement(rejected.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("message").jsonPrimitive.content.startsWith("invalid literal for int() with base 10:"))
            val escaped=digits.map {"\\u"+it.code.toString(16).padStart(4,'0')}.joinToString("")
            val accepted=client.post("/api/v1/cash-records") {bearerAuth("synthetic-admin");contentType(ContentType.Application.Json);setBody(prefix+escaped.toByteArray()+suffix)}
            assertEquals(201,accepted.status.value,accepted.bodyAsText())
        } finally {workspaces.delete("synthetic-admin",name);workspaces.select("synthetic-admin","fixture-a")}
    }
}
