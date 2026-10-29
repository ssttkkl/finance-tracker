package com.finance.tracker.backend

import java.nio.file.Path
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashProjectionDatasetContractTest {
    internal val tables=listOf("cash_entries","cash_entry_members","cash_projection_states","cash_projection_datasets","cash_projections","cash_projection_members","cash_projection_relations")
    private val booleans=setOf("split_inherit","statistics_eligible","visible","has_payment_mirror","has_refund_offset","has_transfer_pair")
    private val json=setOf("counterparty_account_attrs","manual_fields","standalone_values","standalone_manual_fields","roles_json")
    private val times=setOf("updated_at","created_at","published_at","sort_at","occurred_at","build_started_at","build_finished_at")
    private class Rollback:RuntimeException()
    internal fun raw(c:Connection,table:String):List<JsonObject> =c.prepareStatement("SELECT * FROM $table WHERE workspace_id=?").use {
        it.setString(1,"fixture-a");it.executeQuery().use {r ->val columns=(1..r.metaData.columnCount).associateWith {r.metaData.getColumnName(it)}
            val decimals=columns.keys.filter {r.metaData.getColumnTypeName(it).uppercase(java.util.Locale.ROOT).substringBefore('(') in setOf("NUMERIC","DECIMAL")}.toSet()
            buildList {while(r.next()) add(JsonObject(columns.entries.associate {(index,key) ->
                val text=if(index in decimals) r.getBigDecimal(index)?.toPlainString() else r.getString(index)
                key to (text?.let(::JsonPrimitive) ?: JsonNull)
            }))}
        }
    }
    internal fun snapshot(c:Connection,db:Database,dataset:String?):JsonObject {
        val rows=tables.associateWith {raw(c,it)}
        val entries=rows.getValue("cash_entries").associate {row ->val id=row.getValue("id").jsonPrimitive.content
            if(id!="source-entry") UUID.fromString(id)
            id to if(id=="source-entry") id else "entry:${row.getValue("root_transaction_id").jsonPrimitive.content}"
        }
        val parents=rows.getValue("cash_projections").associate {it.getValue("id").jsonPrimitive.content to "projection:${it.getValue("projection_id").jsonPrimitive.content}"}
        dataset?.let(UUID::fromString)
        return JsonObject(rows.mapValues {(table,values) ->JsonArray(values.map {row ->JsonObject(row.mapValues {(key,value) ->
            if(value==JsonNull) JsonNull else {val text=value.jsonPrimitive.content
                when {
                    key in setOf("dataset_id","active_dataset_id","last_build_id") || table=="cash_projection_datasets" && key=="id" ->{assertEquals(dataset,text);JsonPrimitive("current-dataset")}
                    key=="entry_id" || table=="cash_entries" && key=="id" ->JsonPrimitive(entries.getValue(text))
                    key=="projection_row_id" || table=="cash_projections" && key=="id" ->JsonPrimitive(parents.getValue(text))
                    key=="id" && table=="cash_projection_members" ->JsonPrimitive("member:${row.getValue("cash_transaction_id").jsonPrimitive.content}")
                    key=="id" && table=="cash_projection_relations" ->JsonPrimitive("relation:${row.getValue("transaction_relation_id").jsonPrimitive.content}")
                    key in times ->JsonPrimitive(requireNotNull(storedCashInstant(text,db.sqlite)).toString())
                    key in json ->Json.parseToJsonElement(text)
                    key in booleans ->JsonPrimitive(text in setOf("1","t","true"))
                    key=="net_amount" ->JsonPrimitive(storedCashDecimal(text,db.sqlite).text)
                    else ->value
                }
            }
        })})})
    }
    internal fun normalizedExpected(value:JsonElement,key:String?=null):JsonElement=when {
        value==JsonNull ->value
        key in times ->JsonPrimitive(Instant.parse(value.jsonPrimitive.content).toString())
        value is JsonObject ->JsonObject(value.mapValues {(name,item) ->normalizedExpected(item,name)})
        value is JsonArray ->JsonArray(value.map {normalizedExpected(it)})
        else ->value
    }
    @Test fun completePublishedRowsMatchPythonAcrossDraftAggregateFundingRelationsAndRepeatedSourceChanges() {
        val root=Path.of(requireNotNull(System.getenv("FT_KOTLIN_PROJECTION_DATASET_CONTRACT_DIR")))
        val config=Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val expected=normalizedExpected(Json.parseToJsonElement(root.resolve("projection-datasets.json").readText())).jsonArray
        val sourceTables=listOf("cash_transactions","cash_transaction_components","cash_investment_funding_relations","transaction_relations","cash_transaction_issues")
        val initial=db.read {c ->(tables+sourceTables).associateWith {raw(c,it).toSet()}}
        assertFailsWith<Rollback> {db.write {c ->
            val datasets=mutableSetOf<String>();var firstEntries:Map<String,String>?=null
            for((iteration,case) in expected.withIndex()) {
                if(iteration==2) {
                    c.prepareStatement("UPDATE cash_transactions SET counterparty='来源变化🦝',note='来源新备注',row_revision=row_revision+1 WHERE workspace_id='fixture-a'").use {it.executeUpdate()}
                    c.prepareStatement("UPDATE cash_projection_states SET source_revision=1 WHERE workspace_id='fixture-a'").use {it.executeUpdate()}
                }
                val before=sourceTables.associateWith {raw(c,it).toSet()}
                val repo=CashProjectionRepository(CashSqlContext(c,"fixture-a",db.sqlite),now={Instant.parse("2026-08-12T03:04:05.123456Z").plusNanos((iteration+1L)*1000)})
                repo.rebuildFailureContext();val proof=repo.readValidatedSources();val dataset=repo.createStagingDataset(proof.digest,CASH_PROJECTION_RULES_VERSION)
                assertTrue(datasets.add(dataset));repo.replaceDataset(dataset,buildCashProjections(proof.facts,proof.relations),iteration+1L)
                repo.publishDataset(dataset,proof.digest,CASH_PROJECTION_RULES_VERSION,proof)
                val actual=snapshot(c,db,dataset);assertEquals(case.jsonObject.keys,actual.keys)
                for(table in tables) {val wanted=case.jsonObject.getValue(table).jsonArray;val found=actual.getValue(table).jsonArray
                    assertEquals(wanted.size,found.size,"iteration $iteration $table count");assertEquals(wanted.toSet(),found.toSet(),"iteration $iteration complete $table columns")}
                val identities=raw(c,"cash_entries").associate {it.getValue("root_transaction_id").jsonPrimitive.content to it.getValue("id").jsonPrimitive.content}
                if(iteration==0) firstEntries=identities else assertEquals(firstEntries,identities,"stable IDs survive dataset replacement")
                assertEquals(before,sourceTables.associateWith {raw(c,it).toSet()},"publication cannot overwrite sources")
            }
            throw Rollback()
        }}
        assertEquals(initial,db.read {c ->(tables+sourceTables).associateWith {raw(c,it).toSet()}},"test scope rollback")
    }
}
