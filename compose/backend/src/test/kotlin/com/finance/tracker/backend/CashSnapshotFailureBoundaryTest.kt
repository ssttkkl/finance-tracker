package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashSnapshotFailureBoundaryTest {
    private val tables=listOf("cash_transactions","cash_transaction_components","transaction_relations","investment_events","ledger_snapshots","cash_entries","cash_entry_members","cash_projection_states","cash_projection_datasets","cash_projections","cash_projection_members","cash_projection_relations","cash_relation_candidates")
    private fun state(db:Database,workspace:String)=db.read {c ->tables.associateWith {table ->c.prepareStatement("SELECT * FROM $table WHERE workspace_id=?").use {s ->s.setString(1,workspace);s.executeQuery().use {r ->buildSet {while(r.next()) add((1..r.metaData.columnCount).associate {r.metaData.getColumnName(it) to r.getString(it)})}}}}}
    @Test fun storedSnapshotFailuresKeepTheirOriginalClassificationAndTransactionBoundary()=testApplication {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"));val workspaces=WorkspaceService(db)
        application {financeModule(db)}
        data class Case(val payload:String,val operation:String,val status:Int,val code:String?=null)
        val cases=buildList {
            for(payload in listOf("null","[]","7","\"bad\"","""{"accounts":null}""","""{"accounts":[]}""","""{"accounts":{"cash":null}}""","""{"accounts":{"cash":[]}}"""))
                for(operation in listOf("add","checkin","transfer")) add(Case(payload,operation,500))
            for(pocket in listOf("null","[]")) {
                val payload="""{"accounts":{"cash":{"账户":$pocket}}}"""
                for(operation in listOf("add","checkin")) add(Case(payload,operation,400,"snapshot account balance must be a currency mapping"))
                add(Case(payload,"transfer",500))
            }
            for(operation in listOf("add","checkin")) add(Case("""{"accounts":{"cash":{},"security":null}}""",operation,201))
            val nan="""{"accounts":{"cash":{"账户":{"CNY":"NaN"}}}}"""
            add(Case(nan,"add",400,"value must be finite"));add(Case(nan,"checkin",201))
            for(pocket in listOf("null","[]","""{"positions":null}""","""{"positions":[]}""","""{"positions":{"usd":null}}""","""{"positions":{"usd":[]}}""","""{"positions":{"usd":{}}}""","""{"positions":{"usd":{"shares":"0"}}}"""))
                add(Case("""{"accounts":{"security":{"Sec":$pocket}}}""","investment",500))
            add(Case("""{"accounts":{"security":{"Sec":{"positions":{"usd":{"shares":"bad"}}}}}}""","investment",400,"current shares must be decimal-compatible"))
        }
        for((index,case) in cases.withIndex()) {
            val name="快照失败边界$index";val workspace=workspaces.create("synthetic-admin",name).activeWorkspaceId!!
            try {
                deletionAccount(db,workspace)
                db.write {c ->
                    deletionInsert(c,db,"accounts",linkedMapOf("workspace_id" to workspace,"name" to "Sec","type" to "security","active" to true,"revision" to 1L,"currencies" to JsonArray(emptyList()),"metadata_json" to JsonObject(emptyMap()),"created_at" to deletionTime,"updated_at" to deletionTime))
                    deletionInsert(c,db,"ledger_snapshots",linkedMapOf("workspace_id" to workspace,"payload" to Json.parseToJsonElement(case.payload),"version" to 1L,"updated_at" to deletionTime))
                }
                val before=state(db,workspace)
                val (path,body)=when(case.operation) {
                    "add" ->"add" to """{"account":"账户","amount":"-1","currency":"CNY","counterparty":"test","date":"2026-08-04"}"""
                    "checkin" ->"checkin" to """{"account":"账户","balance":"10","currency":"CNY","date":"2026-08-04"}"""
                    else ->"transfer" to """{"from_account":"账户","to_account":"${if(case.operation=="investment") "Sec" else "账户"}","amount":"1","from_currency":"CNY","to_currency":"USD","to_amount":"1","date":"2026-08-04"}"""
                }
                val response=client.post("/api/v1/cashflow/$path") {bearerAuth("synthetic-admin");contentType(ContentType.Application.Json);setBody(body)}
                val context="${case.operation} ${case.payload} ${response.bodyAsText()}"
                assertEquals(case.status,response.status.value,context)
                case.code?.let {assertEquals(JsonPrimitive(it),Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject["code"],context)}
                if(case.status!=201) assertEquals(before,state(db,workspace),context)
            } finally {workspaces.delete("synthetic-admin",name);workspaces.select("synthetic-admin","fixture-a")}
        }
    }
}
