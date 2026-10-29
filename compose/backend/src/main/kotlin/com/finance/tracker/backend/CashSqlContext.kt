package com.finance.tracker.backend

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import kotlinx.serialization.json.*

internal class CashSqlContext(val connection:Connection,val workspace:String,val sqlite:Boolean,val now:()->Instant=Instant::now) {
    private val transaction=(connection as? CashSqlTransactionBoundary)?.cashTransactionStamp()
    val entries=CashEntryRepository(connection,workspace,sqlite)
    val snapshot by lazy {CashSnapshotRepository(this)}
    internal var projectionState:CashLockedProjectionState?=null
    init {(connection as? CashSqlTransactionBoundary)?.cashOnSavepointRollback {if(projectionState!=null) CashProjectionRepository(this).readyStateLockOrNone()}}
    fun requireActive() {if(connection.isClosed || transaction?.isCurrent()==false) throw CashProjectionFailure("projection.incomplete")}
    fun requireTransactionStamp():CashSqlTransactionStamp {requireActive();return transaction ?: throw CashProjectionFailure("projection.incomplete")}
}
internal fun ResultSet.nullableLong(column:String):Long?=getLong(column).let {if(wasNull()) null else it}
internal fun ResultSet.cashDecimal(column:String,sqlite:Boolean):CashStoredDecimal?=getString(column)?.let {storedCashDecimal(it,sqlite)}
internal fun ResultSet.cashInstant(column:String,sqlite:Boolean):Instant?=storedCashInstant(getString(column),sqlite)
internal fun ResultSet.cashJson(column:String):JsonElement?=getString(column)?.let(Json::parseToJsonElement)
internal fun CashSqlContext.insertCashRow(table:String,values:Map<String,Any?>):Long {
    requireActive()
    val slots=values.values.joinToString(",") {when {
        it is JsonElement && !sqlite ->"CAST(? AS json)"
        it is CashAmount && !sqlite ->"CAST(? AS numeric)"
        else ->"?"
    }}
    return connection.prepareStatement("INSERT INTO $table(${values.keys.joinToString(",")}) VALUES($slots) RETURNING id").use {s ->
        values.values.forEachIndexed {index,value ->when(value) {
            is Instant ->s.instant(index+1,value,sqlite)
            is JsonElement ->s.setString(index+1,value.toString())
            is CashAmount ->s.setString(index+1,if(sqlite) fixedCashDecimal(CashStoredDecimal(value.toString())) else value.toString())
            else ->s.setObject(index+1,value)
        }}
        s.executeQuery().use {r ->check(r.next());r.getLong(1)}
    }
}
internal data class CashFactSqlRow(val id:Long,val accountId:Long?,val occurredAt:Instant?,val amount:CashStoredDecimal?,val currency:String?,
    val counterparty:String,val categoryId:String?,val note:String,val sourceType:String?,val recordId:String,val cashGranularity:String,
    val state:String,val createdAt:Instant?,val rowRevision:Long,val recordType:String?,val recordSubtype:String?,val deletedAt:Instant?,val payload:JsonElement?,val fundingRelationId:Long?=null) {
    fun projection(attention:Boolean=false,fundingId:Long?=fundingRelationId,contextOnly:Boolean=false)=CashProjectionFact.create(id,accountId,occurredAt,amount?.text,currency,
        counterparty,categoryId,note,sourceType,recordId,fundingId,cashGranularity,if(contextOnly) "posted" else state,if(contextOnly) null else createdAt,attention)
}
internal fun readCashFactRows(context:CashSqlContext,ids:Set<Long>?=null,liveOnly:Boolean=true,includePayload:Boolean=false,includeFunding:Boolean=false,forContext:Boolean=false):List<CashFactSqlRow> {
    context.requireActive();if(ids!=null && ids.isEmpty()) return emptyList()
    val ordered=ids?.sorted()
    val columns="id,account_id,occurred_at,amount,currency,counterparty,category_id,note,source_type,record_id,cash_granularity,state,row_revision,record_type,record_subtype,deleted_at"+(if(forContext) "" else ",created_at")+(if(includePayload) ",source_payload" else "")+
        (if(includeFunding) ", (SELECT f.id FROM cash_investment_funding_relations f JOIN cash_transaction_components p ON p.workspace_id=f.workspace_id AND p.id=f.cash_transaction_component_id WHERE f.workspace_id=tx.workspace_id AND p.cash_transaction_id=tx.id AND f.status='accepted' AND f.active_slot='active' ORDER BY f.id LIMIT 1) AS funding_relation_id" else "")
    val sql="SELECT $columns FROM cash_transactions tx WHERE workspace_id=?"+(if(liveOnly) " AND deleted_at IS NULL" else "")+(if(ordered!=null) " AND id IN (${ordered.joinToString(",") {"?"}})" else "")+" ORDER BY id"
    return context.connection.prepareStatement(sql).use {
        it.setString(1,context.workspace);ordered?.forEachIndexed {index,id ->it.setLong(index+2,id)}
        it.executeQuery().use {r ->buildList {while(r.next()) add(CashFactSqlRow(r.getLong("id"),r.nullableLong("account_id"),r.cashInstant("occurred_at",context.sqlite),r.cashDecimal("amount",context.sqlite),r.getString("currency"),
            r.getString("counterparty"),r.getString("category_id"),r.getString("note"),r.getString("source_type"),r.getString("record_id"),r.getString("cash_granularity"),r.getString("state"),if(forContext) null else r.cashInstant("created_at",context.sqlite)!!,
            r.getLong("row_revision"),r.getString("record_type"),r.getString("record_subtype"),r.cashInstant("deleted_at",context.sqlite),if(includePayload) r.cashJson("source_payload") else null,if(includeFunding) r.nullableLong("funding_relation_id") else null))}}
    }
}
internal data class CashComponentSqlRow(val id:Long,val transactionId:Long,val accountId:Long?,val amount:CashStoredDecimal?,val currency:String?,val ordinal:Long,val label:String="",val sourceKey:String="")
internal data class CashFundingSqlRow(val id:Long,val componentId:Long,val investmentEventId:Long,val direction:String)
internal data class CashIssueVersion(val id:Long,val transactionId:Long,val revision:Long)
internal data class CashComponentVersion(val id:Long,val transactionId:Long,val accountId:Long?,val amount:String?,val currency:String?,val ordinal:Long)
internal fun fixedCashDecimal(value:CashStoredDecimal):String {
    CashAmount.parseOrNull(value.text)?.representation?.let {r ->
        if(r.coefficient=="0" && r.exponent>Int.MAX_VALUE) return if(r.negative) "-0" else "0"
    }
    val decimal=value.text.toBigDecimalOrNull() ?: return value.text
    return (if(value.text.startsWith('-') && decimal.signum()==0) "-" else "")+decimal.toPlainString()
}
