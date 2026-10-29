package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class AccountHttpContractTest {
    @Test fun accountCommandsMatchPythonResponsesDigestsAndEveryPersistedEffect() = testApplication {
        val root=Path.of(requireNotNull(System.getenv("FT_KOTLIN_ACCOUNT_CONTRACT_DIR")))
        val config=Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val database=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val sequence=AtomicInteger()
        val service=AccountService(database,now={Instant.parse("2026-10-28T14:00:00Z")},receiptId={ "00000000-0000-4000-8000-${sequence.getAndIncrement().toString().padStart(12,'0')}" })
        application { financeModule(database,accounts=service) }
        reset(database)
        val captured=mutableListOf<JsonElement>()
        val capturedLists=mutableListOf<JsonElement>()
        try {
            for(value in Json.parseToJsonElement(root.resolve("accounts.json").readText()).jsonArray) {
                val case=value.jsonObject
                val response=client.request(case.getValue("path").jsonPrimitive.content) {
                    method=HttpMethod.parse(case.getValue("method").jsonPrimitive.content)
                    case.getValue("headers").jsonObject.forEach { (name,value) -> header(name,value.jsonPrimitive.content) }
                    if(case["raw"]!=JsonNull) setBody(case.getValue("raw").jsonPrimitive.content)
                    else if(case["request"]!=JsonNull) {contentType(ContentType.Application.Json);setBody(case.getValue("request").toString())}
                }
                val context="${case["method"]} ${case["path"]} ${case["request"]} ${case["raw"]}"
                assertEquals(case.getValue("status").jsonPrimitive.int,response.status.value,context)
                val body=Json.parseToJsonElement(response.bodyAsText())
                assertEquals(case["body"],body,context)
                assertEquals(case["effects"],snapshot(database),context)
                if(response.status.value==200 && body is JsonObject && "items" in body) capturedLists.add(buildJsonObject {
                    put("view",case.getValue("path").jsonPrimitive.content.substringAfter("view="));put("body",response.bodyAsText())
                })
                if(response.status.value in 200..299 && body is JsonObject && "account_id" in body) captured.add(buildJsonObject {put("method",case.getValue("method"));put("path",case.getValue("path"));put("body",response.bodyAsText())})
            }
        } finally {reset(database)}
        System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let {
            val output=Path.of(it);require(output.isAbsolute);Files.createDirectories(output)
            Files.writeString(output.resolve("account-responses.json"),buildJsonObject {
                put("backend",if(database.sqlite) "sqlite" else "postgresql");put("responses",JsonArray(captured));put("lists",JsonArray(capturedLists))
            }.toString())
        }
    }
    private fun reset(database: Database)=database.write { c -> c.createStatement().use {
        it.executeUpdate("DELETE FROM cash_mutation_receipts WHERE workspace_id='fixture-a'")
        it.executeUpdate("DELETE FROM accounts WHERE workspace_id='fixture-a'")
        it.executeUpdate("DELETE FROM ledger_snapshots WHERE workspace_id='fixture-a'")
        if(database.sqlite) it.executeUpdate("DELETE FROM sqlite_sequence WHERE name IN ('accounts','cash_mutation_results')")
        else {
            it.execute("ALTER SEQUENCE accounts_id_seq RESTART WITH 2")
            it.execute("ALTER SEQUENCE cash_mutation_results_id_seq RESTART WITH 1")
        }
    } }
    private fun snapshot(database: Database): JsonObject=database.read { c ->
        val queries=mapOf(
            "accounts" to "SELECT id,workspace_id,name,type,active,revision,currencies,metadata_json FROM accounts ORDER BY id",
            "snapshots" to "SELECT workspace_id,payload,version FROM ledger_snapshots ORDER BY workspace_id",
            "receipts" to "SELECT id,workspace_id,user_id,idempotency_key,request_digest,operation_kind,result_summary FROM cash_mutation_receipts ORDER BY id",
            "results" to "SELECT workspace_id,receipt_id,result_kind,item_ref,outcome,account_id FROM cash_mutation_results ORDER BY id")
        buildJsonObject {for((key,query) in queries) put(key,c.createStatement().use { stmt -> stmt.executeQuery(query).use { rows ->
            JsonArray(buildList {while(rows.next()) add(JsonArray((1..rows.metaData.columnCount).map { index ->
                when {
                    key=="accounts" && index==5 -> JsonPrimitive(rows.getBoolean(index))
                    key=="accounts" && index in listOf(1,6) || key=="snapshots" && index==3 -> JsonPrimitive(rows.getLong(index))
                    key=="accounts" && index in listOf(7,8) || key=="snapshots" && index==2 || key=="receipts" && index==7 -> Json.parseToJsonElement(rows.getString(index))
                    else -> rows.getString(index)?.let(::JsonPrimitive) ?: JsonNull
                }
            }))})
        } })}
    }
}
