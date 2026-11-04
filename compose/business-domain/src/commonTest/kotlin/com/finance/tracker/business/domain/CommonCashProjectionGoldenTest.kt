package com.finance.tracker.business.domain

import kotlin.time.Instant
import kotlinx.serialization.json.*
import kotlin.test.*

private typealias Fact=CashProjectionFact<Long,Instant>
private typealias Relation=CashProjectionRelation<Long>
private typealias Projection=CashProjection<Long,Instant>
private typealias Build=CashProjectionBuild<Long,Instant>
// Fixture value adapter only; production native Unicode and timestamp input adapters stay unchanged.
private val projectionFixtureValues=CashProjectionValues<Long,Instant>({it>0L},{runCatching {formatPythonCashTimestamp(it)}.isSuccess},{it.uppercase()})
private val rules=CashProjectionRules(projectionFixtureValues) {it.toString()}
internal object CommonProjectionGoldenCodec {
    private fun JsonObject.text(key: String): String?=this[key]?.takeUnless {it==JsonNull}?.jsonPrimitive?.content
    private fun JsonObject.time(key: String)=text(key)?.let(::parsePythonCashTimestamp)
    private fun JsonObject.id(key: String)=text(key)?.toLong()
    private fun JsonObject.fact()=CashProjectionFact.create(projectionFixtureValues,getValue("id").jsonPrimitive.long,id("account_id"),time("occurred_at"),text("amount"),text("currency"),
        text("counterparty")!!,text("category_id"),text("note")!!,text("source_type"),text("record_id")!!,id("funding_relation_id"),text("cash_granularity") ?: "atomic",
        text("state") ?: "posted",time("created_at"),this["needs_attention"]?.jsonPrimitive?.boolean ?: false,
        text("record_type"),text("record_subtype"),this["stable_source_key"]?.takeUnless {it==JsonNull}?.jsonArray?.map {it.jsonPrimitive.content} ?: emptyList(),
        text("record_origin") ?: "legacy")
    private fun JsonObject.relation(): Relation {
        val key=this["refund_allocation_key"]?.takeUnless {it==JsonNull}?.jsonArray?.let {it[0].jsonPrimitive.content to it[1].jsonPrimitive.content}
        return CashProjectionRelation.create(projectionFixtureValues,getValue("id").jsonPrimitive.long,text("kind")!!,id("primary_fact_id")!!,id("secondary_fact_id")!!,
            text("status") ?: "accepted",text("applied_amount"),key)
    }
    private fun value(input: Any?): JsonElement=when(input) {
        null ->JsonNull
        is CashAmount ->buildJsonObject {put("negative",input.representation.negative);put("coefficient",input.representation.coefficient);put("exponent",input.representation.exponent)}
        is Instant ->JsonPrimitive(input.epochSeconds*1_000_000+input.nanosecondsOfSecond/1000)
        is String ->JsonPrimitive(input)
        is Boolean ->JsonPrimitive(input)
        is Number ->JsonPrimitive(input)
        is Pair<*,*> ->JsonArray(listOf(value(input.first),value(input.second)))
        is List<*> ->JsonArray(input.map(::value))
        else ->error("unsupported_projection_golden_value")
    }
    private fun fields(vararg pairs: Pair<String,Any?>)=JsonObject(pairs.associate {(key,item) ->key to value(item)})
    private fun fact(item: Fact)=fields("id" to item.id,"account_id" to item.accountId,"occurred_at" to item.occurredAt,
        "amount" to item.amount,"currency" to item.currency,"counterparty" to item.counterparty,"category_id" to item.categoryId,"note" to item.note,
        "source_type" to item.sourceType,"record_id" to item.recordId,"funding_relation_id" to item.fundingRelationId,"cash_granularity" to item.cashGranularity,
        "state" to item.state,"created_at" to item.createdAt,"needs_attention" to item.needsAttention,
        "record_type" to item.recordType,"record_subtype" to item.recordSubtype,"stable_source_key" to item.stableSourceKey,
        "record_origin" to item.recordOrigin)
    private fun relation(item: Relation)=fields("id" to item.id,"kind" to item.kind,"primary_fact_id" to item.primaryFactId,
        "secondary_fact_id" to item.secondaryFactId,"status" to item.status,"applied_amount" to item.appliedAmount,"refund_allocation_key" to item.refundAllocationKey)
    private fun projection(item: Projection): JsonObject = JsonObject(fields("projection_id" to item.projectionId,"net_amount" to item.netAmount,
        "economic_type" to item.economicType?.value,"visible" to item.visible,"hidden_reason" to item.hiddenReason,"business_label" to item.businessLabel,
        "member_ids" to item.memberIds,"compositions" to item.compositions,"funding_relation_id" to item.fundingRelationId,"state" to item.state,
        "statistics_eligible" to item.statisticsEligible,"attention_count" to item.attentionCount,"occurred_at" to item.occurredAt,"sort_at" to item.sortAt,
        "confirmation_status" to item.confirmationStatus,"economic_root_ids" to item.economicRootIds)+mapOf("primary_record" to fact(item.primaryRecord),
        "members" to JsonArray(item.members.map {(member,roles) ->JsonArray(listOf(fact(member),value(roles)))}),"relations" to JsonArray(item.relations.map(::relation))))
    fun readFact(row: JsonObject)=row.fact()
    fun readRelation(row: JsonObject)=row.relation()
    fun snapshot(result: Build)=buildJsonObject {
        put("projections",JsonArray(result.projections.map(::projection)));put("rules_version",result.rulesVersion);put("member_ids",value(result.memberIds.sorted()))
    }
}

class CommonCashProjectionGoldenTest {
    @Test fun representativeRetainsJsonTextOrderingAndExactLargeIdentifiers() {
        val id=9007199254740993L
        fun fact(value:Long,time:String)=CashProjectionFact.create(projectionFixtureValues,value,1L,
            Instant.parse(time),"-10.00","CNY","",null,"","alipay","r$value")
        val earlier=fact(id,"2026-10-01T00:00:00Z")
        val later=fact(id+1,"2026-10-01T00:00:00.001Z")
        val relation=CashProjectionRelation.create(projectionFixtureValues,1L,"payment_mirror",id,id+1)
        val row=rules.build(listOf(earlier,later),listOf(relation)).projections.single()
        // Preserve existing JSON lexical ordering: '.' precedes 'Z', regardless of chronology.
        assertEquals(id+1,row.primaryRecord.id)
        assertEquals(listOf(id+1,id),row.memberIds)
        assertEquals(CashAmount.parse("-10.00"),row.netAmount)
        assertEquals(listOf(id,id+1),row.economicRootIds)
    }
    @Test fun wholeProjectionResultsAndErrorsMatchPythonAcrossGraphsAndDraftStates() {
        val rows=Json.parseToJsonElement(CashProjectionOriginalVectors.json).jsonArray
        assertEquals(2592,rows.size)
        val failures=mutableListOf<String>()
        for((index,raw) in rows.withIndex()) {
            val row=raw.jsonObject
            val actual=try {
                val result=rules.build(row.getValue("facts").jsonArray.map {CommonProjectionGoldenCodec.readFact(it.jsonObject)},row.getValue("relations").jsonArray.map {CommonProjectionGoldenCodec.readRelation(it.jsonObject)})
                buildJsonObject {put("result",CommonProjectionGoldenCodec.snapshot(result))}
            } catch(failure: CashProjectionFailure) {buildJsonObject {put("error",failure.code)}}
            val expected=JsonObject(row.filterKeys {it in setOf("error","result")})
            if(expected!=actual) failures.add("case $index expected=$expected actual=$actual")
        }
        assertTrue(failures.isEmpty(),"${failures.size} projection mismatches: ${failures.take(3)}")
    }
}
