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

class CashTransferHttpContractTest {
    @Test fun actualTransferResponsesAndAllThirteenFinancialTablesMatchPython()=testApplication {
        val root=Path.of(requireNotNull(System.getenv("FT_KOTLIN_TRANSFER_CONTRACT_DIR")))
        val config=Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val helper=CashProjectionDatasetContractTest()
        fun dates(value:JsonObject)=JsonObject(value.mapValues {(table,rows) ->
            if(table!="ledger_snapshots") helper.normalizedExpected(rows) else JsonArray(rows.jsonArray.map {raw ->
                val row=raw.jsonObject;JsonObject(helper.normalizedExpected(JsonObject(row-"payload")).jsonObject+("payload" to row.getValue("payload")))
            })
        })
        val extras=listOf("cash_transactions","cash_transaction_components","investment_events","transaction_relations","cash_relation_candidates","ledger_snapshots")
        val initial=db.read {c ->(helper.tables+extras).associateWith {table ->
            c.createStatement().use {s ->s.executeQuery("SELECT * FROM $table").use {r ->
                val columns=(1..r.metaData.columnCount).map {r.metaData.getColumnName(it)}
                columns to buildList {while(r.next()) add(columns.indices.map {r.getObject(it+1)})}
            }}
        }}
        assertTrue(extras.all {initial.getValue(it).second.isEmpty()},"dedicated transfer test database starts without financial writes")
        val sequences=if(db.sqlite) emptyMap() else db.read {c ->listOf("cash_transactions","cash_transaction_components","investment_events","transaction_relations").associateWith {table ->
            c.createStatement().use {it.executeQuery("SELECT last_value,is_called FROM ${table}_id_seq").use {r ->assertTrue(r.next());r.getLong(1) to r.getBoolean(2)}}
        }}
        val clock=AtomicReference(deletionTime)
        application {financeModule(db,clock=clock::get)}
        val captures=mutableListOf<JsonElement>()
        val expectedTransfers=mutableMapOf<String,String>();val actualTransfers=mutableMapOf<String,String>()
        fun normalized(value:JsonElement,transfers:Map<String,String>,key:String?=null):JsonElement=when {
            value==JsonNull ->value
            key=="manual_transfer_id" ->JsonPrimitive(transfers.getValue(value.jsonPrimitive.content))
            value is JsonObject ->JsonObject(value.mapValues {(name,item) ->normalized(item,transfers,name)})
            value is JsonArray ->JsonArray(value.map {normalized(it,transfers)})
            else ->value
        }
        try {
            for((index,raw) in Json.parseToJsonElement(root.resolve("cash-transfer.json").readText()).jsonArray.withIndex()) {
                val case=raw.jsonObject;clock.set(Instant.parse(case.getValue("at").jsonPrimitive.content))
                val response=client.post("/api/v1/cashflow/transfer") {
                    case.getValue("headers").jsonObject.forEach {(key,value) ->header(key,value.jsonPrimitive.content)}
                    contentType(ContentType.Application.Json)
                    val bytes=case["raw_bytes"]
                    if(bytes!=null) setBody(java.util.Base64.getDecoder().decode(bytes.jsonPrimitive.content))
                    else setBody(case["raw"].takeUnless {it==JsonNull}?.jsonPrimitive?.content ?: case.getValue("request").toString())
                }
                val context="$index request=${case["request"]}"
                assertEquals(case.getValue("status").jsonPrimitive.int,response.status.value,context+response.bodyAsText())
                if("text" in case) assertEquals(case.getValue("text").jsonPrimitive.content,response.bodyAsText(),context)
                else {
                    val body=Json.parseToJsonElement(response.bodyAsText())
                    if(response.status.value==201) {
                        fun register(value:JsonElement,transfers:MutableMap<String,String>) {
                            val ids=value.jsonObject.getValue("rows").jsonArray.map {it.jsonObject.getValue("manual_transfer_id").jsonPrimitive.content}
                            assertEquals(2,ids.size);assertEquals(1,ids.toSet().size);java.util.UUID.fromString(ids.first())
                            assertNull(transfers.put(ids.first(),"transfer:$index"),"every new operation has its own transfer identity")
                        }
                        register(case.getValue("body"),expectedTransfers);register(body,actualTransfers)
                    }
                    assertEquals(normalized(case.getValue("body"),expectedTransfers),normalized(body,actualTransfers),context)
                }
                if("body" in case && case["request"] is JsonObject && case.getValue("headers").jsonObject["Authorization"]==JsonPrimitive("Bearer synthetic-admin")) captures+=buildJsonObject {
                    put("request",case.getValue("request"));put("status",response.status.value);put("body",response.bodyAsText())
                }
                val actual=db.read {c ->
                    val dataset=c.createStatement().use {it.executeQuery("SELECT active_dataset_id FROM cash_projection_states WHERE workspace_id='fixture-a'").use {r ->assertTrue(r.next());r.getString(1)}}
                    val result=helper.snapshot(c,db,dataset).toMutableMap()
                    for(table in extras) result[table]=JsonArray(helper.raw(c,table).map {row ->JsonObject(row.mapValues {(key,value) ->
                        if(value==JsonNull) value else when(key) {
                            "created_at","updated_at","occurred_at" ->JsonPrimitive(storedCashInstant(value.jsonPrimitive.content,db.sqlite).toString())
                            "source_payload","relation_metadata","manual_overrides","counterparty_account_attrs","metadata_json","payload","evidence" ->Json.parseToJsonElement(value.jsonPrimitive.content)
                            "amount","from_amount","to_amount","commission","applied_amount" ->JsonPrimitive(fixedCashDecimal(storedCashDecimal(value.jsonPrimitive.content,db.sqlite)))
                            "active" ->JsonPrimitive(value.jsonPrimitive.content in setOf("1","t","true"))
                            else ->value
                        }
                    })})
                    dates(JsonObject(result))
                }
                val expected=normalized(dates(case.getValue("effects").jsonObject),expectedTransfers).jsonObject
                val current=normalized(actual,actualTransfers).jsonObject
                for((table,rows) in expected) assertEquals(rows.jsonArray.groupingBy {it}.eachCount(),current.getValue(table).jsonArray.groupingBy {it}.eachCount(),"$context complete $table")
            }
        } finally {
            db.write {c ->
                val order=listOf("cash_projection_members","cash_projection_relations","cash_entry_members","cash_entries","cash_projections","cash_projection_states","cash_projection_datasets","cash_relation_candidates","transaction_relations","investment_events","cash_transaction_components","cash_transactions","ledger_snapshots")
                for(table in order) c.createStatement().use {it.executeUpdate("DELETE FROM $table")}
                for(table in order.reversed()) {
                    val (columns,rows)=initial.getValue(table)
                    for(row in rows) c.prepareStatement("INSERT INTO $table(${columns.joinToString(",")}) VALUES(${columns.joinToString(",") {"?"}})").use {s ->
                        row.forEachIndexed {i,value ->s.setObject(i+1,value)};s.executeUpdate()
                    }
                }
                for(table in listOf("cash_transactions","cash_transaction_components","investment_events","transaction_relations")) c.createStatement().use {
                    if(db.sqlite) it.executeUpdate("DELETE FROM sqlite_sequence WHERE name='$table'")
                    else {val (value,called)=sequences.getValue(table);it.execute("SELECT setval('${table}_id_seq',$value,$called)")}
                }
            }
        }
        System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let {directory ->
            val out=Path.of(directory);require(out.isAbsolute);Files.createDirectories(out)
            Files.writeString(out.resolve("cash-transfer-responses.json"),buildJsonObject {put("backend",if(db.sqlite) "sqlite" else "postgresql");put("responses",JsonArray(captures))}.toString())
        }
    }
}
