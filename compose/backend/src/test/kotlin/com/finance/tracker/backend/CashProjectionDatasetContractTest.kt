package com.finance.tracker.backend

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
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
    internal fun snapshot(c:Connection,db:Database,dataset:String?,selectedTables:List<String> = tables):JsonObject {
        val rows=selectedTables.associateWith {raw(c,it)}
        val entries=rows.getValue("cash_entries").associate {row ->val id=row.getValue("id").jsonPrimitive.content
            if(id!="source-entry") UUID.fromString(id)
            id to if(id=="source-entry") id else "entry:${row.getValue("root_transaction_id").jsonPrimitive.content}"
        }
        val parents=rows["cash_projections"].orEmpty().associate {it.getValue("id").jsonPrimitive.content to "projection:${it.getValue("projection_id").jsonPrimitive.content}"}
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
}
