package com.finance.tracker.backend

import java.sql.Connection
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*
import org.jooq.Field
import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.backend.persistence.generated.tables.records.CashMutationResultsRecord

internal class ReceiptConflict(val code: String,val receiptId: String?=null,var commitState: String?=null): RuntimeException(code)

internal class ReceiptRepository(private val connection: Connection,private val workspace: String,private val sqlite: Boolean,
    private val now: ()->java.time.Instant=java.time.Instant::now,private val nextId: ()->String={java.util.UUID.randomUUID().toString()}) {
    private val sql = connection.jooq(sqlite)
    private val receipts = CASH_MUTATION_RECEIPTS
    private val resultRows = CASH_MUTATION_RESULTS
    private fun reference(value: Any?,maximum: Int)=receiptReference(value,maximum)!!
    fun replay(user: Any?,key: Any?,requestDigest: Any?,operation: Any?,planId: Any?=null): JsonObject? {
        val row=byKey(user,key)
        if(row!=null && (row["request_digest"]?.jsonPrimitive?.content!=receiptDigest(requestDigest) || row["operation_kind"]?.jsonPrimitive?.content!=operation ||
            row["plan_id"]?.takeUnless {it==JsonNull}?.jsonPrimitive?.content!=planId)) throw ReceiptConflict("idempotency_conflict",row.getValue("receipt_id").jsonPrimitive.content)
        return row
    }
    fun forContext(user: Any?,sessionDigest: Any?=null,editContextDigest: Any?=null): JsonObject? {
        if((sessionDigest==null)==(editContextDigest==null)) throw IllegalArgumentException("mutation_context_invalid")
        val value=receiptDigest(sessionDigest ?: editContextDigest)!!
        return lookup(reference(user,128),if(sessionDigest!=null) receipts.SESSION_DIGEST else receipts.EDIT_CONTEXT_DIGEST,value)
    }
    fun create(user: Any?,key: Any?,requestDigest: Any?,operation: Any?,resultSummary: Any?,results: Any?,planId: Any?=null,sessionDigest: Any?=null,editContextDigest: Any?=null): JsonObject {
        val owner=reference(user,128);val requestKey=reference(key,255);val digest=receiptDigest(requestDigest)!!
        val plan=receiptReference(planId,128,nullable=true);val kind=receiptOperation(operation)
        val session=receiptDigest(sessionDigest,true);val edit=receiptDigest(editContextDigest,true)
        if(session!=null && edit!=null) throw IllegalArgumentException("mutation_context_invalid")
        val summary=normalizeReceiptSummary(resultSummary);val items=normalizeReceiptResults(results)
        byKey(owner,requestKey)?.let {throw ReceiptConflict("idempotency_conflict",it.getValue("receipt_id").jsonPrimitive.content)}
        if(session!=null || edit!=null) forContext(owner,session,edit)?.let {
            throw ReceiptConflict(if(session!=null) "session_committed" else "edit_context_committed",it.getValue("receipt_id").jsonPrimitive.content)
        }
        val id=nextId();val at=now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        sql.insertInto(receipts).set(receipts.ID, id).set(receipts.WORKSPACE_ID, workspace).set(receipts.USER_ID, owner)
            .set(receipts.IDEMPOTENCY_KEY, requestKey).set(receipts.REQUEST_DIGEST, digest).set(receipts.OPERATION_KIND, kind)
            .set(receipts.PLAN_ID, plan).set(receipts.SESSION_DIGEST, session).set(receipts.EDIT_CONTEXT_DIGEST, edit)
            .set(receipts.RESULT_SUMMARY, summary).set(receipts.CREATED_AT, at).execute()
        val records = items.map { item ->
            fun text(key: String) = item[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
            CashMutationResultsRecord().apply {
                workspaceId = workspace; receiptId = id; resultKind = text("result_kind"); itemRef = text("item_ref"); outcome = text("outcome")
                code = text("code"); operationRef = text("operation_ref"); transactionId = text("transaction_id"); relationId = text("relation_id"); accountId = text("account_id")
            }
        }
        if (records.isNotEmpty()) sql.batchInsert(records).execute()
        return get(owner,id)!!
    }
    fun get(user: Any?,id: Any?): JsonObject?=lookup(reference(user,128),receipts.ID,reference(id,36))
    fun byKey(user: Any?,key: Any?): JsonObject?=lookup(reference(user,128),receipts.IDEMPOTENCY_KEY,reference(key,255))
    fun lookupKey(user: Any?,key: Any?): JsonObject? {
        if(key !is String || !validReceiptLookupKey(key)) throw IllegalArgumentException("mutation_reference_invalid")
        return lookup(reference(user,128),receipts.IDEMPOTENCY_KEY,key)
    }
    private fun lookup(user: String,column: Field<String?>,value: String): JsonObject? {
        val row = sql.select(receipts.ID, receipts.PLAN_ID, receipts.OPERATION_KIND, receipts.REQUEST_DIGEST,
            receipts.SESSION_DIGEST, receipts.EDIT_CONTEXT_DIGEST, receipts.CREATED_AT, receipts.RESULT_SUMMARY).from(receipts)
            .where(receipts.WORKSPACE_ID.eq(workspace)).and(receipts.USER_ID.eq(user)).and(column.eq(value)).fetchOne() ?: return null
        val instant = row[receipts.CREATED_AT]!!
        val micros=instant.nano/1000
        val created=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC).format(instant)+
            (if(micros==0) "" else "."+micros.toString().padStart(6,'0'))+"+00:00"
        val summary = row[receipts.RESULT_SUMMARY]!!.jsonObject
        return buildJsonObject {
            for ((key, field) in listOf("receipt_id" to receipts.ID, "plan_id" to receipts.PLAN_ID, "operation_kind" to receipts.OPERATION_KIND,
                "request_digest" to receipts.REQUEST_DIGEST, "session_digest" to receipts.SESSION_DIGEST, "edit_context_digest" to receipts.EDIT_CONTEXT_DIGEST))
                put(key, row[field]?.let(::JsonPrimitive) ?: JsonNull)
            put("committed",true);put("created_at",created);summary.forEach {(key,item) ->put(key,item)}
        }
    }
    fun results(user: Any?,receipt: Any?,resultKind: Any?=null,after: Any?=0,limit: Any?=50): JsonObject? {
        val size=strictCashInteger(limit)?.takeIf {it in java.math.BigInteger.ONE..java.math.BigInteger.valueOf(200)}?.toInt()
        val cursor=strictCashInteger(after)?.takeIf {it.signum()>=0 && it<=java.math.BigInteger.valueOf(Long.MAX_VALUE)}?.toLong()
        if(size==null || cursor==null || resultKind!=null && resultKind !in setOf("row","relation","file","account")) throw IllegalArgumentException("mutation_page_invalid")
        if(get(user,receipt)==null) return null
        val fields = listOf(resultRows.RESULT_KIND, resultRows.ITEM_REF, resultRows.OUTCOME, resultRows.CODE,
            resultRows.OPERATION_REF, resultRows.TRANSACTION_ID, resultRows.RELATION_ID, resultRows.ACCOUNT_ID)
        val predicate = resultRows.WORKSPACE_ID.eq(workspace).and(resultRows.RECEIPT_ID.eq(reference(receipt,36))).and(resultRows.ID.gt(cursor))
        val items = sql.select(listOf(resultRows.ID) + fields).from(resultRows)
            .where(if (resultKind == null) predicate else predicate.and(resultRows.RESULT_KIND.eq(resultKind as String)))
            .orderBy(resultRows.ID).limit(size + 1).fetch().map { row -> buildJsonObject {
                put("result_id", row[resultRows.ID]!!.toString())
                for (field in fields) put(field.name, row[field]?.let(::JsonPrimitive) ?: JsonNull)
            } }
        return buildJsonObject {put("items",JsonArray(items.take(size)));put("next_after",if(items.size>size) JsonPrimitive(items[size-1].getValue("result_id").jsonPrimitive.long) else JsonNull)}
    }
}
