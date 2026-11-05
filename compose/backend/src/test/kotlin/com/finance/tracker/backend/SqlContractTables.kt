package com.finance.tracker.backend

import java.time.*
import kotlin.test.*
import kotlinx.serialization.json.*

internal fun contractInstant(raw:String,sqlite:Boolean):Instant=if(sqlite) LocalDateTime.parse(raw.replace(' ','T')).toInstant(ZoneOffset.UTC) else OffsetDateTime.parse(raw.replace(' ','T')).toInstant()
internal fun compareContractTables(db:Database,expected:JsonObject,context:String)=db.read {c ->
    for((table,raw) in expected) {
        val definition=raw.jsonObject;val columns=definition.getValue("columns").jsonArray.map {it.jsonPrimitive.content};val kinds=definition.getValue("kinds").jsonArray.map {it.jsonPrimitive.content}
        val actual=c.createStatement().use {s ->s.executeQuery("SELECT ${columns.joinToString(",")} FROM $table").use {rows ->buildList {
            while(rows.next()) add(JsonArray(columns.indices.map {i ->val text=rows.getString(i+1);if(text==null) JsonNull else when(kinds[i]) {
                "json" ->Json.parseToJsonElement(text)
                "boolean" ->JsonPrimitive(rows.getBoolean(i+1))
                "time" ->contractInstant(text,db.sqlite).let {JsonPrimitive(it.epochSecond*1000000+it.nano/1000)}
                "decimal" ->JsonPrimitive(rows.getBigDecimal(i+1).stripTrailingZeros().toPlainString())
                else ->when(rows.metaData.getColumnType(i+1)) {java.sql.Types.INTEGER,java.sql.Types.BIGINT,java.sql.Types.SMALLINT ->JsonPrimitive(rows.getLong(i+1));else ->JsonPrimitive(text)}
            }}))
        }}}
        assertEquals(definition.getValue("rows").jsonArray.groupingBy {it}.eachCount(),actual.groupingBy {it as JsonElement}.eachCount(),"$context table=$table")
    }
}
internal fun restoreContractTables(db:Database,seed:JsonObject)=db.write {c ->
    for(table in listOf("cash_projection_members","cash_relation_candidates","transaction_relations","cash_entry_members","cash_entries","cash_projections","cash_projection_states","cash_projection_datasets","cash_transactions","accounts")) c.createStatement().use {it.executeUpdate("DELETE FROM $table")}
    c.createStatement().use {it.executeUpdate("UPDATE cash_categories SET parent_id=NULL");it.executeUpdate("DELETE FROM cash_categories");it.executeUpdate("DELETE FROM cash_category_states")}
    for(table in listOf("cash_categories","cash_category_states","accounts","cash_transactions","cash_entries","cash_entry_members","cash_projection_datasets","cash_projection_states","cash_projections","cash_projection_members","transaction_relations","cash_relation_candidates")) {
        if(table !in seed) continue
        val definition=seed.getValue(table).jsonObject;val columns=definition.getValue("columns").jsonArray.map {it.jsonPrimitive.content};val kinds=definition.getValue("kinds").jsonArray.map {it.jsonPrimitive.content}
        val rows=definition.getValue("rows").jsonArray.let {if(table=="cash_categories") it.sortedBy {row ->row.jsonArray[columns.indexOf("depth")].jsonPrimitive.int} else it}
        for(row in rows) {
            val values=columns.indices.associate {i ->val value=row.jsonArray[i];columns[i] to if(value==JsonNull) null else when(kinds[i]) {
                "json" ->value
                "boolean" ->value.jsonPrimitive.boolean
                "time" ->value.jsonPrimitive.long.let {Instant.ofEpochSecond(it/1000000,(it%1000000)*1000)}
                "decimal" ->value.jsonPrimitive.content.toBigDecimal()
                else ->if(value.jsonPrimitive.isString) value.jsonPrimitive.content else value.jsonPrimitive.long
            }}
            deletionInsert(c,db,table,values)
        }
    }
}
