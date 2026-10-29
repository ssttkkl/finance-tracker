package com.finance.tracker.backend

import io.ktor.server.engine.*
import io.ktor.server.netty.Netty
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class ProjectionRebuildHttpContractTest {
    @Test fun actualAdministratorHttpMatchesPythonAndAllPublicationColumnsOnBothDatabases() {
        val root=Path.of(requireNotNull(System.getenv("FT_KOTLIN_PROJECTION_REBUILD_CONTRACT_DIR")))
        val config=Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val helper=CashProjectionDatasetContractTest();val clock=AtomicReference(deletionTime);val failure=AtomicReference<String?>(null)
        val original=db.read {c ->helper.tables.associateWith {helper.raw(c,it)}}
        check(original.filterKeys {it !in setOf("cash_entries","cash_entry_members")}.values.all {it.isEmpty()})
        val sources=listOf("cash_transactions","cash_transaction_components","cash_investment_funding_relations","transaction_relations","cash_transaction_issues")
        val rebuild=ProjectionRebuildService(db,now=clock::get,afterHint={failure.get()?.let {throw StorageFailure(it)}})
        val server=embeddedServer(Netty,environment=applicationEnvironment {},configure={connector {host="127.0.0.1";port=0};configureFinanceHttp()}) {financeModule(db,clock=clock::get,projectionRebuild=rebuild)}.start(wait=false)
        try {
            val port=runBlocking {server.engine.resolvedConnectors().single().port}
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().use {client ->
                for((index,raw) in Json.parseToJsonElement(root.resolve("projection-rebuilds.json").readText()).jsonArray.withIndex()) {
                    val case=raw.jsonObject;clock.set(Instant.parse(case.getValue("at").jsonPrimitive.content))
                    failure.set(case["failure"]?.takeUnless {it==JsonNull}?.jsonPrimitive?.content)
                    case["action"]?.takeUnless {it==JsonNull}?.let {action ->db.write {c ->c.createStatement().use {it.executeUpdate(action.jsonPrimitive.content)}}}
                    val before=db.read {c ->sources.associateWith {helper.raw(c,it).toSet()}}
                    val request=HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/operations/projections/rebuild"))
                        .POST(HttpRequest.BodyPublishers.ofString("ignored body"))
                    case.getValue("headers").jsonObject.forEach {(key,value) ->request.header(key,value.jsonPrimitive.content)}
                    val response=client.send(request.build(),HttpResponse.BodyHandlers.ofString())
                    assertEquals(case.getValue("status").jsonPrimitive.int,response.statusCode(),"$index ${response.body()}")
                    val body=Json.parseToJsonElement(response.body()).jsonObject
                    val status=db.read {ProjectionStateRepository(it,"fixture-a",db.sqlite).status()}
                    val dataset=status["active_dataset_id"]?.takeUnless {it==JsonNull}?.jsonPrimitive?.content
                    val normalized=JsonObject(body.mapValues {(key,value) ->if(key in setOf("active_dataset_id","last_build_id") && value!=JsonNull) {assertEquals(dataset,value.jsonPrimitive.content);JsonPrimitive("current-dataset")} else value})
                    assertEquals(case.getValue("body"),normalized,"$index complete response")
                    val actual=db.read {helper.snapshot(it,db,dataset)}
                    for((table,rows) in helper.normalizedExpected(case.getValue("effects")).jsonObject) assertEquals(rows.jsonArray.groupingBy {it}.eachCount(),actual.getValue(table).jsonArray.groupingBy {it}.eachCount(),"$index all $table columns")
                    assertEquals(before,db.read {c ->sources.associateWith {helper.raw(c,it).toSet()}},"$index source rows cannot be overwritten")
                }
            }
        } finally {
            server.stop(100,1000)
            db.write {c ->
                for(table in listOf("cash_projection_states","cash_projection_datasets","cash_entry_members","cash_entries")) c.prepareStatement("DELETE FROM $table WHERE workspace_id='fixture-a'").use {it.executeUpdate()}
                for(table in listOf("cash_entries","cash_entry_members")) for(row in original.getValue(table)) {
                    val keys=row.keys.toList();val json=setOf("counterparty_account_attrs","manual_fields","standalone_values","standalone_manual_fields")
                    c.prepareStatement("INSERT INTO $table(${keys.joinToString(",")}) VALUES(${keys.joinToString(",") {if(it in json && !db.sqlite) "CAST(? AS json)" else "?"}})").use {statement ->
                        keys.forEachIndexed {index,key ->val value=row.getValue(key)
                            when {
                                value==JsonNull ->statement.setObject(index+1,null)
                                key in setOf("root_transaction_id","revision","transaction_id") ->statement.setLong(index+1,value.jsonPrimitive.long)
                                key=="split_inherit" ->statement.setBoolean(index+1,value.jsonPrimitive.content in setOf("true","t","1"))
                                else ->statement.setString(index+1,value.jsonPrimitive.content)
                            }
                        };assertEquals(1,statement.executeUpdate())
                    }
                }
                c.createStatement().use {it.executeUpdate("UPDATE workspace_memberships SET role='admin' WHERE workspace_id='fixture-a' AND user_id='admin'");it.executeUpdate("UPDATE transaction_relations SET primary_role='outgoing',row_revision=1 WHERE workspace_id='fixture-a' AND id=3003")}
            }
            assertEquals(original,db.read {c ->helper.tables.associateWith {helper.raw(c,it)}},"restore only dedicated anonymous test data")
        }
    }
}
