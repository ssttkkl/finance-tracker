package com.finance.tracker.backend

import java.time.Instant
import kotlinx.serialization.json.*
import kotlin.test.*

class CashRelationGraphGoldenTest {
    private fun decode(value: JsonElement): Any?=when(value) {
        JsonNull ->null
        is JsonPrimitive ->when {
            value.isString ->value.content
            value.booleanOrNull!=null ->value.boolean
            value.content.matches(Regex("-?[0-9]+")) ->value.content.toBigInteger()
            else ->value.double
        }
        is JsonArray ->value.map(::decode)
        is JsonObject ->when {
            value.keys==setOf("decimal") ->CashDecimalLiteral(value.getValue("decimal").jsonPrimitive.content)
            value.keys==setOf("datetime") ->pythonCashTimestamp(value.getValue("datetime").jsonPrimitive.content)
            value.keys==setOf("model","fields") ->model(value.getValue("model").jsonPrimitive.content,value.getValue("fields").jsonObject.mapValues {decode(it.value)})
            else ->value.mapValues {decode(it.value)}
        }
    }
    private fun model(type: String,fields: Map<String,Any?>): Any {
        fun get(key: String)=fields[key]
        return when(type) {
            "CashProjectionFact" ->CashProjectionFact.create((get("id") as Number).toLong(),(get("account_id") as Number?)?.toLong(),get("occurred_at") as Instant?,
                (get("amount") as CashDecimalLiteral?)?.text,get("currency") as String?,get("counterparty") as String,get("category_id") as String?,get("note") as String,
                get("source_type") as String?,get("record_id") as String,(get("funding_relation_id") as Number?)?.toLong(),get("cash_granularity") as String,
                get("state") as String,get("created_at") as Instant?,get("needs_attention") as Boolean)
            "RelationEndpoint" ->CashRelationEndpoint(get("component_id"),get("transaction_id"),get("amount"),get("currency"),get("account_id"),get("record_type"),get("record_subtype"),get("state"),get("active"))
            "CashRelationGraphEdge" ->CashRelationGraphEdge(get("id"),get("kind"),get("primary_transaction_id"),get("secondary_transaction_id"),get("primary_component_id"),get("secondary_component_id"),get("primary_role"),get("secondary_role"),get("subtype"),get("applied_amount"),get("state"),get("active"))
            else ->error("unknown_relation_oracle_model")
        }
    }
    @Test fun graphErrorsAndWholeProjectionsMatchPythonForAmountsOccupancyAndRawShapes() {
        val rows=Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/python-cash-relation-graphs.json")).bufferedReader().use {it.readText()}).jsonArray
        val failures=mutableListOf<String>()
        for((index,input) in rows.withIndex()) {
            val row=input.jsonObject
            val actual=try {
                val result=buildCashRelationGraph(decode(row.getValue("facts")),decode(row.getValue("components")),decode(row.getValue("relations")),decode(row.getValue("funding")))
                buildJsonObject {put("result",CashProjectionGoldenCodec.snapshot(result))}
            } catch(failure: CashRelationFailure) {buildJsonObject {put("error",failure.code)}}
            val expected=JsonObject(row.filterKeys {it in setOf("error","result")})
            if(actual!=expected) failures.add("case $index expected=$expected actual=$actual")
        }
        assertTrue(failures.isEmpty(),"${failures.size} relation graph mismatches: ${failures.take(3)}")
    }
}
