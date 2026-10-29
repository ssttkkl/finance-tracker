package com.finance.tracker.backend

import io.ktor.server.engine.*
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.netty.Netty
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class ReceiptHttpContractTest {
    @Test fun receiptGetsMatchPythonWithoutChangingStoredBusinessData() {
        val root=Path.of(requireNotNull(System.getenv("FT_KOTLIN_RECEIPT_CONTRACT_DIR")))
        val config=Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val database=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val before=snapshot(database)
        val server=embeddedServer(Netty,environment=applicationEnvironment {},configure={
            connector {host="127.0.0.1";port=0};configureFinanceHttp()
        }) {financeModule(database)}.start(wait=false)
        val captured=mutableListOf<JsonElement>()
        try {
            val port=runBlocking {server.engine.resolvedConnectors().single().port}
            for(raw in Json.parseToJsonElement(root.resolve("receipts.json").readText()).jsonArray) {
                val case=raw.jsonObject;val path=case.getValue("path").jsonPrimitive.content
                val (status,text)=rawHttpGet(port,path,case.getValue("headers").jsonObject.mapValues {it.value.jsonPrimitive.content})
                assertEquals(case.getValue("status").jsonPrimitive.int,status,path)
                val body=Json.parseToJsonElement(text).jsonObject
                val error=body["error"]?.jsonObject
                val normalized=if(error!=null && "diagnostic" in error) {
                    val diagnostic=error.getValue("diagnostic").jsonObject
                    assertTrue(diagnostic.getValue("error_id").jsonPrimitive.content.matches(Regex("[0-9a-f]{32}")))
                    JsonObject(body+mapOf("error" to JsonObject(error+mapOf("diagnostic" to JsonObject(diagnostic+mapOf("error_id" to JsonPrimitive("0".repeat(32))))))))
                } else body
                assertEquals(case["body"],normalized,path)
                if(status==200) captured+=buildJsonObject {put("path",path);put("body",text)}
            }
            assertEquals(before,snapshot(database))
        } finally {server.stop(100,1000)}
        System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let {
            val output=Path.of(it);require(output.isAbsolute);Files.createDirectories(output)
            Files.writeString(output.resolve("receipt-responses.json"),buildJsonObject {
                put("backend",if(database.sqlite) "sqlite" else "postgresql");put("responses",JsonArray(captured))
            }.toString())
        }
    }
    private fun snapshot(database: Database)=database.read {c ->
        buildJsonObject {
            for(table in listOf("accounts","cash_transactions","transaction_relations","cash_mutation_receipts","cash_mutation_results","cash_projection_states","cash_projections","cash_projection_members","ledger_snapshots","workspace_memberships","user_sessions")) {
                put(table,c.createStatement().use {statement ->statement.executeQuery("SELECT * FROM $table").use {rows ->
                    JsonArray(buildList {while(rows.next()) add(JsonArray((1..rows.metaData.columnCount).map {index ->rows.getString(index)?.let(::JsonPrimitive) ?: JsonNull}))}.sortedBy {it.toString()})
                }})
            }
        }
    }
}
