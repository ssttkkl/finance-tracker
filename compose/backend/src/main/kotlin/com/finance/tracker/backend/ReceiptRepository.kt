package com.finance.tracker.backend

import java.sql.Connection
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*

internal class ReceiptConflict(val code: String,val receiptId: String?=null): RuntimeException(code)

internal class ReceiptRepository(private val connection: Connection,private val workspace: String,private val sqlite: Boolean,
    private val now: ()->java.time.Instant=java.time.Instant::now,private val nextId: ()->String={java.util.UUID.randomUUID().toString()}) {
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
        return lookup(reference(user,128),if(sessionDigest!=null) "session_digest" else "edit_context_digest",value)
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
        val json=if(sqlite) "?" else "CAST(? AS JSON)"
        connection.prepareStatement("INSERT INTO cash_mutation_receipts(id,workspace_id,user_id,idempotency_key,request_digest,operation_kind,plan_id,session_digest,edit_context_digest,result_summary,created_at) VALUES(?,?,?,?,?,?,?,?,?,$json,?)").use {
            it.setString(1,id);it.setString(2,workspace);it.setString(3,owner);it.setString(4,requestKey);it.setString(5,digest);it.setString(6,kind)
            it.setString(7,plan);it.setString(8,session);it.setString(9,edit);it.setString(10,receiptAsciiJson(summary));it.instant(11,at,sqlite);it.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO cash_mutation_results(workspace_id,receipt_id,result_kind,item_ref,outcome,code,operation_ref,transaction_id,relation_id,account_id) VALUES(?,?,?,?,?,?,?,?,?,?)").use {statement ->
            for(item in items) {
                statement.setString(1,workspace);statement.setString(2,id)
                for((index,field) in listOf("result_kind","item_ref","outcome","code","operation_ref","transaction_id","relation_id","account_id").withIndex())
                    statement.setString(3+index,item[field]?.takeUnless {it==JsonNull}?.jsonPrimitive?.content)
                statement.addBatch()
            }
            if(items.isNotEmpty()) statement.executeBatch()
        }
        return get(owner,id)!!
    }
    fun get(user: Any?,id: Any?): JsonObject?=lookup(reference(user,128),"id",reference(id,36))
    fun byKey(user: Any?,key: Any?): JsonObject?=lookup(reference(user,128),"idempotency_key",reference(key,255))
    fun lookupKey(user: Any?,key: Any?): JsonObject? {
        if(key !is String || !validReceiptLookupKey(key)) throw IllegalArgumentException("mutation_reference_invalid")
        return lookup(reference(user,128),"idempotency_key",key)
    }
    private fun lookup(user: String,column: String,value: String): JsonObject?=connection.prepareStatement(
        "SELECT id,plan_id,operation_kind,request_digest,session_digest,edit_context_digest,created_at,result_summary FROM cash_mutation_receipts WHERE workspace_id=? AND user_id=? AND $column=?").use {
        it.setString(1,workspace);it.setString(2,user);it.setString(3,value)
        it.executeQuery().use {row ->
            if(!row.next()) null else {
                val raw=row.getString("created_at").replace(' ','T')
                val instant=if(sqlite) LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC) else OffsetDateTime.parse(raw).toInstant()
                val micros=instant.nano/1000
                val created=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC).format(instant)+
                    (if(micros==0) "" else "."+micros.toString().padStart(6,'0'))+"+00:00"
                val summary=Json.parseToJsonElement(row.getString("result_summary")).jsonObject
                buildJsonObject {
                    for((key,columnName) in listOf("receipt_id" to "id","plan_id" to "plan_id","operation_kind" to "operation_kind","request_digest" to "request_digest","session_digest" to "session_digest","edit_context_digest" to "edit_context_digest"))
                        put(key,row.getString(columnName)?.let(::JsonPrimitive) ?: JsonNull)
                    put("committed",true);put("created_at",created);summary.forEach {(key,item) ->put(key,item)}
                }
            }
        }
    }
    fun results(user: Any?,receipt: Any?,resultKind: Any?=null,after: Any?=0,limit: Any?=50): JsonObject? {
        val size=strictCashInteger(limit)?.takeIf {it in java.math.BigInteger.ONE..java.math.BigInteger.valueOf(200)}?.toInt()
        val cursor=strictCashInteger(after)?.takeIf {it.signum()>=0 && it<=java.math.BigInteger.valueOf(Long.MAX_VALUE)}?.toLong()
        if(size==null || cursor==null || resultKind!=null && resultKind !in setOf("row","relation","file","account")) throw IllegalArgumentException("mutation_page_invalid")
        if(get(user,receipt)==null) return null
        val items=connection.prepareStatement("SELECT id,result_kind,item_ref,outcome,code,operation_ref,transaction_id,relation_id,account_id FROM cash_mutation_results WHERE workspace_id=? AND receipt_id=? AND id>?"+
            (if(resultKind==null) "" else " AND result_kind=?")+" ORDER BY id LIMIT ?").use {
            it.setString(1,workspace);it.setString(2,reference(receipt,36));it.setLong(3,cursor)
            if(resultKind!=null) it.setString(4,resultKind as String)
            it.setInt(if(resultKind==null) 4 else 5,size+1)
            it.executeQuery().use {rows ->buildList {while(rows.next()) add(buildJsonObject {
                put("result_id",rows.getLong("id").toString())
                for(field in listOf("result_kind","item_ref","outcome","code","operation_ref","transaction_id","relation_id","account_id"))
                    put(field,rows.getString(field)?.let(::JsonPrimitive) ?: JsonNull)
            })}}
        }
        return buildJsonObject {put("items",JsonArray(items.take(size)));put("next_after",if(items.size>size) JsonPrimitive(items[size-1].getValue("result_id").jsonPrimitive.long) else JsonNull)}
    }
}
