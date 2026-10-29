package com.finance.tracker.backend

import java.nio.file.Path
import java.time.Instant
import java.math.BigInteger
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashProjectionSourceContractTest {
    private fun value(raw:Any?):JsonElement=when(raw) {
        null ->JsonNull
        is JsonElement ->raw
        is CashDecimalLiteral ->value(CashAmount.parse(raw.text))
        is CashAmount ->buildJsonObject {put("negative",raw.representation.negative);put("coefficient",raw.representation.coefficient);put("exponent",raw.representation.exponent)}
        is Instant ->JsonPrimitive(raw.toString())
        is Boolean ->JsonPrimitive(raw)
        is Number ->JsonPrimitive(raw)
        is String ->JsonPrimitive(raw)
        is Pair<*,*> ->JsonArray(listOf(value(raw.first),value(raw.second)))
        is Iterable<*> ->JsonArray(raw.map(::value))
        else ->error("Unsupported test snapshot type ${raw.javaClass.name}")
    }
    private fun fields(vararg pairs:Pair<String,Any?>)=JsonObject(pairs.associate {it.first to value(it.second)})
    private fun fact(row:CashProjectionFact)=fields("id" to row.id,"account_id" to row.accountId,"occurred_at" to row.occurredAt,"amount" to row.amount,
        "currency" to row.currency,"counterparty" to row.counterparty,"category_id" to row.categoryId,"note" to row.note,"source_type" to row.sourceType,
        "record_id" to row.recordId,"funding_relation_id" to row.fundingRelationId,"cash_granularity" to row.cashGranularity,"state" to row.state,"created_at" to row.createdAt,"needs_attention" to row.needsAttention)
    private fun relation(row:CashProjectionRelation)=fields("id" to row.id,"kind" to row.kind,"primary_fact_id" to row.primaryFactId,"secondary_fact_id" to row.secondaryFactId,
        "status" to row.status,"subtype" to row.subtype,"applied_amount" to row.appliedAmount,"refund_allocation_key" to row.refundAllocationKey)
    private fun component(row:CashRelationEndpoint)=fields("component_id" to row.componentId,"transaction_id" to row.transactionId,"amount" to row.amount,"currency" to row.currency,
        "account_id" to row.accountId,"record_type" to row.recordType,"record_subtype" to row.recordSubtype,"state" to row.state,"active" to row.active)
    private fun edge(row:CashRelationGraphEdge)=fields("id" to row.id,"kind" to row.kind,"primary_transaction_id" to row.primaryTransactionId,"secondary_transaction_id" to row.secondaryTransactionId,
        "primary_component_id" to row.primaryComponentId,"secondary_component_id" to row.secondaryComponentId,"primary_role" to row.primaryRole,"secondary_role" to row.secondaryRole,
        "subtype" to row.subtype,"applied_amount" to row.appliedAmount,"state" to row.state,"active" to row.active)
    private fun snapshot(row:CashRelationSqlContext)=fields("seed_ids" to row.seedIds.sorted(),"decision_group_ids" to row.decisionGroupIds,"transaction_ids" to row.transactionIds.sorted(),
        "facts" to row.facts.map(::fact),"components" to row.components.map(::component),"relations" to row.relations.map(::edge),
        "joint_members" to row.jointMembers.map {fields("id" to it.id,"decision_group_id" to it.decisionGroupId,"row_revision" to it.rowRevision,"state" to it.state,"active" to it.active)},
        "transaction_revisions" to row.transactionRevisions,"active_transaction_ids" to row.activeTransactionIds.sorted(),
        "component_versions" to row.componentVersions.map {listOf(it.id,it.transactionId,it.accountId,it.amount,it.currency,it.ordinal)},"relation_revisions" to row.relationRevisions,
        "relation_groups" to row.relationGroups,"funding_versions" to row.fundingVersions.map {listOf(it.id,it.componentId,it.investmentEventId,it.direction)},"funding_component_ids" to row.fundingComponentIds.sortedWith(cashRelationStringOrder),
        "issue_versions" to row.issueVersions.map {listOf(it.id,it.transactionId,it.revision)},"entry_versions" to row.entryVersions.map {listOf(it.transactionId,it.entryId,it.revision,it.digest)})
    private fun normalized(raw:JsonElement,key:String?=null):JsonElement=when {
        raw==JsonNull ->raw
        key in setOf("amount","applied_amount") ->value(CashAmount.parse(raw.jsonPrimitive.content))
        key in setOf("occurred_at","created_at") ->value(Instant.parse(raw.jsonPrimitive.content))
        raw is JsonArray ->JsonArray(raw.map {normalized(it)})
        raw is JsonObject ->JsonObject(raw.mapValues {(name,item) ->normalized(item,name)})
        else ->raw
    }
    @Test fun fullSourcesContextsAndCanonicalDigestMatchPythonOnActualSql() {
        val root=Path.of(requireNotNull(System.getenv("FT_KOTLIN_PROJECTION_SOURCE_CONTRACT_DIR")))
        val config=Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val expected=Json.parseToJsonElement(root.resolve("projection-sources.json").readText()).jsonObject
        db.read {c ->val context=CashSqlContext(c,"fixture-a",db.sqlite);val repo=CashProjectionSourceRepository(context);val sources=repo.readSources()
            assertEquals(normalized(expected.getValue("facts")),value(sources.facts.map(::fact)),"complete facts including exact Decimal representation")
            assertEquals(normalized(expected.getValue("relations")),value(sources.relations.map(::relation)),"complete resolved relations")
            assertEquals(expected.getValue("source_digest").jsonPrimitive.content,repo.sourceDigest())
            for((index,raw) in expected.getValue("contexts").jsonArray.withIndex()) {
                val case=raw.jsonObject;val ids=case.getValue("ids").jsonArray.map {it.jsonPrimitive.long}.toSet();val groups=case.getValue("groups").jsonArray.map {it.jsonPrimitive.content}
                val loaded=repo.relationContext(ids,groups)
                assertEquals(normalized(case.getValue("context")),snapshot(loaded),"context $index complete fields")
                assertEquals(case.getValue("token").jsonPrimitive.content,loaded.token(),"context $index version token")
            }
            assertEquals(expected.getValue("component_parents"),value(repo.parentIdsForComponents(setOf(2001,2009,2019,999999)).sorted()))
            assertEquals(expected.getValue("confirmed_closure"),value(repo.confirmedTransactionClosure(setOf(2001),inputIsComponents=true).sorted()))
        }
    }
}
