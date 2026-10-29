package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class ManualCashHttpContractTest {
    @Test fun actualManualCreateResponsesAndAllFinancialColumnsMatchPython()=testApplication {
        val root=Path.of(requireNotNull(System.getenv("FT_KOTLIN_MANUAL_CASH_CONTRACT_DIR")))
        val config=Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val helper=CashProjectionDatasetContractTest()
        val extras=listOf("cash_transactions","cash_transaction_components","ledger_snapshots")
        val initial=db.read {c ->(helper.tables+extras).associateWith {table ->
            c.createStatement().use {s ->s.executeQuery("SELECT * FROM $table").use {r ->
                val columns=(1..r.metaData.columnCount).map {r.metaData.getColumnName(it)}
                columns to buildList {while(r.next()) add(columns.indices.map {r.getObject(it+1)})}
            }}
        }}
        assertTrue(extras.all {initial.getValue(it).second.isEmpty()},"dedicated manual test database starts without cash writes")
        val sequences=if(db.sqlite) emptyMap() else db.read {c ->listOf("cash_transactions","cash_transaction_components").associateWith {table ->
            c.createStatement().use {it.executeQuery("SELECT last_value,is_called FROM ${table}_id_seq").use {r ->assertTrue(r.next());r.getLong(1) to r.getBoolean(2)}}
        }}
        val clock=AtomicReference(deletionTime)
        application {financeModule(db,clock=clock::get)}
        val captures=mutableListOf<JsonElement>()
        try {
            for((index,raw) in Json.parseToJsonElement(root.resolve("manual-cash.json").readText()).jsonArray.withIndex()) {
                val case=raw.jsonObject;clock.set(Instant.parse(case.getValue("at").jsonPrimitive.content))
                val response=client.post("/api/v1/cash-records") {
                    case.getValue("headers").jsonObject.forEach {(key,value) ->header(key,value.jsonPrimitive.content)}
                    contentType(ContentType.Application.Json)
                    val bytes=case["raw_bytes"]
                    if(bytes!=null) setBody(java.util.Base64.getDecoder().decode(bytes.jsonPrimitive.content))
                    else setBody(case["raw"].takeUnless {it==JsonNull}?.jsonPrimitive?.content ?: case.getValue("request").toString())
                }
                val context="$index request=${case["request"]}"
                assertEquals(case.getValue("status").jsonPrimitive.int,response.status.value,context+response.bodyAsText())
                if("text" in case) assertEquals(case.getValue("text").jsonPrimitive.content,response.bodyAsText(),context)
                else assertEquals(case["body"],Json.parseToJsonElement(response.bodyAsText()),context)
                if("body" in case && case["request"] is JsonObject && case.getValue("headers").jsonObject["Authorization"]==JsonPrimitive("Bearer synthetic-admin")) captures+=buildJsonObject {
                    put("request",case.getValue("request"));put("status",response.status.value);put("body",response.bodyAsText())
                }
                val actual=db.read {c ->
                    val dataset=c.createStatement().use {it.executeQuery("SELECT active_dataset_id FROM cash_projection_states WHERE workspace_id='fixture-a'").use {r ->assertTrue(r.next());r.getString(1)}}
                    val result=helper.snapshot(c,db,dataset).toMutableMap()
                    for(table in extras) result[table]=JsonArray(helper.raw(c,table).map {row ->JsonObject(row.mapValues {(key,value) ->
                        if(value==JsonNull) value else when(key) {
                            "created_at","updated_at","occurred_at" ->JsonPrimitive(storedCashInstant(value.jsonPrimitive.content,db.sqlite).toString())
                            "source_payload","relation_metadata","manual_overrides","counterparty_account_attrs","metadata_json","payload" ->Json.parseToJsonElement(value.jsonPrimitive.content)
                            "amount" ->JsonPrimitive(fixedCashDecimal(storedCashDecimal(value.jsonPrimitive.content,db.sqlite)))
                            else ->value
                        }
                    })})
                    helper.normalizedExpected(JsonObject(result)).jsonObject
                }
                val expected=helper.normalizedExpected(case.getValue("effects")).jsonObject
                for((table,rows) in expected) assertEquals(rows.jsonArray.groupingBy {it}.eachCount(),actual.getValue(table).jsonArray.groupingBy {it}.eachCount(),"$context complete $table")
            }
        } finally {
            db.write {c ->
                val order=listOf("cash_projection_members","cash_projection_relations","cash_entry_members","cash_entries","cash_projections","cash_projection_states","cash_projection_datasets","cash_transaction_components","cash_transactions","ledger_snapshots")
                for(table in order) c.createStatement().use {it.executeUpdate("DELETE FROM $table")}
                for(table in order.reversed()) {
                    val (columns,rows)=initial.getValue(table)
                    for(row in rows) c.prepareStatement("INSERT INTO $table(${columns.joinToString(",")}) VALUES(${columns.joinToString(",") {"?"}})").use {s ->
                        row.forEachIndexed {i,value ->s.setObject(i+1,value)};s.executeUpdate()
                    }
                }
                for(table in listOf("cash_transactions","cash_transaction_components")) c.createStatement().use {
                    if(db.sqlite) it.executeUpdate("DELETE FROM sqlite_sequence WHERE name='$table'")
                    else {val (value,called)=sequences.getValue(table);it.execute("SELECT setval('${table}_id_seq',$value,$called)")}
                }
            }
        }
        System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let {directory ->
            val out=Path.of(directory);require(out.isAbsolute);Files.createDirectories(out)
            Files.writeString(out.resolve("manual-cash-responses.json"),buildJsonObject {put("backend",if(db.sqlite) "sqlite" else "postgresql");put("responses",JsonArray(captures))}.toString())
        }
    }
}
