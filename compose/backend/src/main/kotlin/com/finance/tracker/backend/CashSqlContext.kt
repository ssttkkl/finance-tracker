package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import org.jooq.Field
import org.jooq.impl.DSL
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import kotlinx.serialization.json.*

internal class CashSqlContext(val connection:Connection,val workspace:String,val sqlite:Boolean,val now:()->Instant=Instant::now) {
    private val transaction=(connection as? CashSqlTransactionBoundary)?.cashTransactionStamp()
    val sql by lazy { connection.jooq(sqlite) }
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
internal data class CashFactSqlRow(val id:Long,val accountId:Long?,val occurredAt:Instant?,val amount:CashStoredDecimal?,val currency:String?,
    val counterparty:String,val categoryId:String?,val note:String,val sourceType:String?,val recordId:String,val cashGranularity:String,
    val state:String,val createdAt:Instant?,val rowRevision:Long,val recordType:String?,val recordSubtype:String?,val deletedAt:Instant?,val payload:JsonElement?,val fundingRelationId:Long?=null) {
    fun projection(attention:Boolean=false,fundingId:Long?=fundingRelationId,contextOnly:Boolean=false)=CashProjectionFact.create(id,accountId,occurredAt,amount?.text,currency,
        counterparty,categoryId,note,sourceType,recordId,fundingId,cashGranularity,if(contextOnly) "posted" else state,if(contextOnly) null else createdAt,attention)
}
internal fun readCashFactRows(context:CashSqlContext,ids:Set<Long>?=null,liveOnly:Boolean=true,includePayload:Boolean=false,includeFunding:Boolean=false,forContext:Boolean=false):List<CashFactSqlRow> {
    context.requireActive();if(ids!=null && ids.isEmpty()) return emptyList()
    val tx = CASH_TRANSACTIONS.`as`("tx")
    val fields = mutableListOf<Field<*>>(tx.ID, tx.ACCOUNT_ID, tx.OCCURRED_AT, tx.AMOUNT, tx.CURRENCY, tx.COUNTERPARTY,
        tx.CATEGORY_ID, tx.NOTE, tx.SOURCE_TYPE, tx.RECORD_ID, tx.CASH_GRANULARITY, tx.STATE, tx.ROW_REVISION,
        tx.RECORD_TYPE, tx.RECORD_SUBTYPE, tx.DELETED_AT)
    if (!forContext) fields += tx.CREATED_AT
    if (includePayload) fields += tx.SOURCE_PAYLOAD
    val funding = CASH_INVESTMENT_FUNDING_RELATIONS.`as`("f")
    val part = CASH_TRANSACTION_COMPONENTS.`as`("p")
    val fundingId = DSL.field(DSL.select(funding.ID).from(funding).join(part)
        .on(part.WORKSPACE_ID.eq(funding.WORKSPACE_ID)).and(part.ID.eq(funding.CASH_TRANSACTION_COMPONENT_ID))
        .where(funding.WORKSPACE_ID.eq(tx.WORKSPACE_ID)).and(part.CASH_TRANSACTION_ID.eq(tx.ID))
        .and(funding.STATUS.eq("accepted")).and(funding.ACTIVE_SLOT.eq("active")).orderBy(funding.ID).limit(1)).`as`("funding_relation_id")
    if (includeFunding) fields += fundingId
    val condition = tx.WORKSPACE_ID.eq(context.workspace)
        .and(if (liveOnly) tx.DELETED_AT.isNull else DSL.noCondition())
        .and(if (ids != null) tx.ID.`in`(ids.sorted()) else DSL.noCondition())
    return context.sql.select(fields).from(tx).where(condition).orderBy(tx.ID).fetch { row ->
        CashFactSqlRow(row[tx.ID]!!, row[tx.ACCOUNT_ID], row[tx.OCCURRED_AT], row[tx.AMOUNT], row[tx.CURRENCY],
            row[tx.COUNTERPARTY]!!, row[tx.CATEGORY_ID], row[tx.NOTE]!!, row[tx.SOURCE_TYPE], row[tx.RECORD_ID]!!,
            row[tx.CASH_GRANULARITY]!!, row[tx.STATE]!!, if (forContext) null else row[tx.CREATED_AT]!!, row[tx.ROW_REVISION]!!,
            row[tx.RECORD_TYPE], row[tx.RECORD_SUBTYPE], row[tx.DELETED_AT], if (includePayload) row[tx.SOURCE_PAYLOAD] else null,
            if (includeFunding) row[fundingId] else null)
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
