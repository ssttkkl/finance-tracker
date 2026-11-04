package com.finance.tracker.backend

import java.time.Instant
import kotlinx.serialization.json.*
import kotlin.test.*

internal object CashProjectionGoldenCodec {
    private fun JsonObject.text(key: String): String?=this[key]?.takeUnless {it==JsonNull}?.jsonPrimitive?.content
    private fun JsonObject.time(key: String)=text(key)?.let(::pythonCashTimestamp)
    private fun JsonObject.id(key: String)=text(key)?.toLong()
    private fun JsonObject.fact()=CashProjectionFact.create(getValue("id").jsonPrimitive.long,id("account_id"),time("occurred_at"),text("amount"),text("currency"),
        text("counterparty")!!,text("category_id"),text("note")!!,text("source_type"),text("record_id")!!,id("funding_relation_id"),text("cash_granularity") ?: "atomic",
        text("state") ?: "posted",time("created_at"),this["needs_attention"]?.jsonPrimitive?.boolean ?: false,
        text("record_type"),text("record_subtype"),this["stable_source_key"]?.takeUnless {it==JsonNull}?.jsonArray?.map {it.jsonPrimitive.content} ?: emptyList(),
        text("record_origin") ?: "legacy")
    private fun JsonObject.relation(): CashProjectionRelation {
        val key=this["refund_allocation_key"]?.takeUnless {it==JsonNull}?.jsonArray?.let {it[0].jsonPrimitive.content to it[1].jsonPrimitive.content}
        return CashProjectionRelation.create(getValue("id").jsonPrimitive.long,text("kind")!!,id("primary_fact_id")!!,id("secondary_fact_id")!!,
            text("status") ?: "accepted",text("applied_amount"),key)
    }
    private fun value(input: Any?): JsonElement=when(input) {
        null ->JsonNull
        is CashAmount ->buildJsonObject {put("negative",input.representation.negative);put("coefficient",input.representation.coefficient);put("exponent",input.representation.exponent)}
        is Instant ->JsonPrimitive(input.epochSecond*1_000_000+input.nano/1000)
        is kotlin.time.Instant ->JsonPrimitive(input.epochSeconds*1_000_000+input.nanosecondsOfSecond/1000)
        is String ->JsonPrimitive(input)
        is Boolean ->JsonPrimitive(input)
        is Number ->JsonPrimitive(input)
        is Pair<*,*> ->JsonArray(listOf(value(input.first),value(input.second)))
        is List<*> ->JsonArray(input.map(::value))
        else ->error("unsupported_projection_golden_value")
    }
    private fun fields(vararg pairs: Pair<String,Any?>)=JsonObject(pairs.associate {(key,item) ->key to value(item)})
    private fun <I:Comparable<I>,T:Comparable<T>> fact(item: com.finance.tracker.business.domain.CashProjectionFact<I,T>)=fields("id" to item.id,"account_id" to item.accountId,"occurred_at" to item.occurredAt,
        "amount" to item.amount,"currency" to item.currency,"counterparty" to item.counterparty,"category_id" to item.categoryId,"note" to item.note,
        "source_type" to item.sourceType,"record_id" to item.recordId,"funding_relation_id" to item.fundingRelationId,"cash_granularity" to item.cashGranularity,
        "state" to item.state,"created_at" to item.createdAt,"needs_attention" to item.needsAttention,
        "record_type" to item.recordType,"record_subtype" to item.recordSubtype,"stable_source_key" to item.stableSourceKey,
        "record_origin" to item.recordOrigin)
    private fun <I:Comparable<I>> relation(item: com.finance.tracker.business.domain.CashProjectionRelation<I>)=fields("id" to item.id,"kind" to item.kind,"primary_fact_id" to item.primaryFactId,
        "secondary_fact_id" to item.secondaryFactId,"status" to item.status,"applied_amount" to item.appliedAmount,"refund_allocation_key" to item.refundAllocationKey)
    private fun <I:Comparable<I>,T:Comparable<T>> projection(item: com.finance.tracker.business.domain.CashProjection<I,T>): JsonObject = JsonObject(fields("projection_id" to item.projectionId,"net_amount" to item.netAmount,
        "economic_type" to item.economicType?.value,"visible" to item.visible,"hidden_reason" to item.hiddenReason,"business_label" to item.businessLabel,
        "member_ids" to item.memberIds,"compositions" to item.compositions,"funding_relation_id" to item.fundingRelationId,"state" to item.state,
        "statistics_eligible" to item.statisticsEligible,"attention_count" to item.attentionCount,"occurred_at" to item.occurredAt,"sort_at" to item.sortAt,
        "confirmation_status" to item.confirmationStatus,"economic_root_ids" to item.economicRootIds)+mapOf("primary_record" to fact(item.primaryRecord),
        "members" to JsonArray(item.members.map {(member,roles) ->JsonArray(listOf(fact(member),value(roles)))}),"relations" to JsonArray(item.relations.map(::relation))))
    fun readFact(row: JsonObject)=row.fact()
    fun readRelation(row: JsonObject)=row.relation()
    fun <I:Comparable<I>,T:Comparable<T>> snapshot(result: com.finance.tracker.business.domain.CashProjectionBuild<I,T>)=buildJsonObject {
        put("projections",JsonArray(result.projections.map(::projection)));put("rules_version",result.rulesVersion);put("member_ids",value(result.memberIds.sorted()))
    }
}

class CashProjectionGoldenTest {
    @Test fun wholeProjectionResultsAndErrorsMatchPythonAcrossGraphsAndDraftStates() {
        val rows=Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/python-projection-rules.json")).bufferedReader().use {it.readText()}).jsonArray
        val failures=mutableListOf<String>()
        for((index,raw) in rows.withIndex()) {
            val row=raw.jsonObject
            val actual=try {
                val result=buildCashProjections(row.getValue("facts").jsonArray.map {CashProjectionGoldenCodec.readFact(it.jsonObject)},row.getValue("relations").jsonArray.map {CashProjectionGoldenCodec.readRelation(it.jsonObject)})
                buildJsonObject {put("result",CashProjectionGoldenCodec.snapshot(result))}
            } catch(failure: CashProjectionFailure) {buildJsonObject {put("error",failure.code)}}
            val expected=JsonObject(row.filterKeys {it in setOf("error","result")})
            if(expected!=actual) failures.add("case $index expected=$expected actual=$actual")
        }
        assertTrue(failures.isEmpty(),"${failures.size} projection mismatches: ${failures.take(3)}")
    }
}
