package com.finance.tracker.backend

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.*

internal val deletionTime: Instant=Instant.parse("2026-08-12T00:00:00Z")
internal fun deletionInsert(c:Connection,db:Database,table:String,values:Map<String,Any?>):Long? {
    val slots=values.values.joinToString(",") {if(it is JsonElement && !db.sqlite) "CAST(? AS json)" else "?"}
    val returning=table in setOf("accounts","cash_transactions","transaction_relations","cash_relation_candidates","cash_projections")
    return c.prepareStatement("INSERT INTO $table(${values.keys.joinToString(",")}) VALUES($slots)"+if(returning) " RETURNING id" else "").use {
        values.values.forEachIndexed {i,value ->when(value) {
            is Instant ->it.instant(i+1,value,db.sqlite)
            is JsonElement ->it.setString(i+1,value.toString())
            else ->it.setObject(i+1,value)
        }}
        if(returning) it.executeQuery().use {rows ->check(rows.next());rows.getLong(1)} else {it.executeUpdate();null}
    }
}
internal fun deletionAccount(db:Database,workspace:String):Long=db.write {c ->deletionInsert(c,db,"accounts",linkedMapOf(
    "workspace_id" to workspace,"name" to "账户","type" to "cash","active" to true,"revision" to 1L,
    "currencies" to JsonArray(listOf(JsonPrimitive("CNY"))),"metadata_json" to JsonObject(emptyMap()),"created_at" to deletionTime,"updated_at" to deletionTime))!!}
internal fun deletionFact(c:Connection,db:Database,workspace:String,account:Long,category:String?,deleted:Boolean=false,
    overrides:JsonElement=JsonObject(emptyMap())):Long=deletionInsert(c,db,"cash_transactions",linkedMapOf(
    "workspace_id" to workspace,"account_id" to account,"state" to "posted","record_origin" to "legacy","row_revision" to 1L,
    "cash_granularity" to "atomic","record_id" to "","manual_overrides" to overrides,"occurred_at" to deletionTime,
    "amount" to java.math.BigDecimal("-10"),"currency" to "CNY","counterparty" to "商家","counterparty_account" to "",
    "counterparty_account_attrs" to JsonArray(emptyList()),"note" to "","category_id" to category,
    "record_type" to "consumption","record_subtype" to "not_applicable","created_at" to deletionTime,
    "deleted_at" to if(deleted) deletionTime else null,"deleted_by" to "","delete_reason" to ""))!!
internal fun deletionEntry(c:Connection,db:Database,workspace:String,id:String,root:Long,category:String?,manual:JsonArray=JsonArray(emptyList())) {
    deletionInsert(c,db,"cash_entries",linkedMapOf("id" to id,"workspace_id" to workspace,"root_transaction_id" to root,
        "revision" to 1L,"category_id" to category,"note" to "保留备注","counterparty" to "保留商家","counterparty_account" to "",
        "counterparty_account_attrs" to JsonArray(emptyList()),"manual_fields" to manual))
}
internal fun deletionRelation(c:Connection,db:Database,workspace:String,primary:Long,secondary:Long?=null,anchor:Long=primary,active:Boolean=true):Long=
    deletionInsert(c,db,"transaction_relations",linkedMapOf("workspace_id" to workspace,"kind" to "refund_offset","subtype" to "",
        "primary_transaction_id" to primary,"secondary_transaction_id" to secondary,"anchor_transaction_id" to anchor,
        "state" to "draft","active" to active,"row_revision" to 1L,"decision_source" to "rule","evidence" to JsonObject(emptyMap()),
        "applied_amount" to java.math.BigDecimal.ZERO,"rule_id" to "","created_by" to "system","created_at" to deletionTime,
        "decided_by" to "","decision_reason" to ""))!!
internal fun deletionCandidate(c:Connection,db:Database,workspace:String,relation:Long,transaction:Long,revision:Long=1,validity:String="valid"):Long=
    deletionInsert(c,db,"cash_relation_candidates",linkedMapOf("workspace_id" to workspace,"relation_id" to relation,
        "candidate_transaction_id" to transaction,"expected_row_revision" to revision,"evidence" to JsonObject(emptyMap()),"validity" to validity))!!
internal fun deletionProjection(c:Connection,db:Database,workspace:String,dataset:String,transaction:Long,account:Long,category:String,version:Long):Long=
    deletionInsert(c,db,"cash_projections",linkedMapOf("workspace_id" to workspace,"dataset_id" to dataset,"projection_id" to "cash:$transaction",
        "root_cash_transaction_id" to transaction,"state" to "posted","statistics_eligible" to true,"attention_count" to 0,
        "sort_at" to deletionTime,"economic_type" to "expense","net_amount" to java.math.BigDecimal("-10"),"currency" to "CNY",
        "occurred_at" to deletionTime,"account_id" to account,"counterparty" to "商家","category_id" to category,"category_path" to "分类原路径",
        "note" to "保留备注","record_id" to "","visible" to true,"has_payment_mirror" to false,"has_refund_offset" to false,"has_transfer_pair" to false,
        "member_count" to 1,"accepted_relation_count" to 0,"built_projection_version" to version,"created_at" to deletionTime,"updated_at" to deletionTime))!!
