package com.finance.tracker.backend

import com.finance.tracker.business.contracts.CashImportReceiptSection
import com.finance.tracker.business.contracts.ReadPagePosition
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
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
        if ("write_set_result" in summary && kind != "cash_write_set") throw IllegalArgumentException("mutation_summary_invalid")
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
        val created = formatReceiptCreationTime(row[receipts.CREATED_AT]!!)
        val summary = row[receipts.RESULT_SUMMARY]!!.jsonObject
        if ("write_set_result" in summary) {
            if (row[receipts.OPERATION_KIND] != "cash_write_set") throw IllegalArgumentException("mutation_receipt_invalid")
            receiptWriteSetResult(summary["write_set_result"], "mutation_receipt_invalid")
        }
        return buildJsonObject {
            for ((key, field) in listOf("receipt_id" to receipts.ID, "plan_id" to receipts.PLAN_ID, "operation_kind" to receipts.OPERATION_KIND,
                "request_digest" to receipts.REQUEST_DIGEST, "session_digest" to receipts.SESSION_DIGEST, "edit_context_digest" to receipts.EDIT_CONTEXT_DIGEST))
                put(key, row[field]?.let(::JsonPrimitive) ?: JsonNull)
            put("committed",true);put("created_at",created);summary.forEach {(key,item) ->put(key,item)}
        }
    }
    internal fun importPage(
        user: String, key: String, section: CashImportReceiptSection, position: ReadPagePosition,
    ): StoredImportReceiptPage? {
        if (!validReceiptLookupKey(key)) throw IllegalArgumentException("mutation_reference_invalid")
        if (section == CashImportReceiptSection.HEADER && position.offset != 0) throw ReadPageFailure()
        return if (sqlite) sqliteImportPage(user, key, section, position.offset)
        else postgresImportPage(user, key, section, position)
    }

    private fun sqliteImportPage(user: String, key: String, section: CashImportReceiptSection, offset: Int): StoredImportReceiptPage? {
        val receipt = lookupKey(user, key)?.takeIf { it["operation_kind"] == JsonPrimitive("cash_write_set") } ?: return null
        val version = receiptLedgerSequence(receipt["ledger_seq"], "mutation_receipt_invalid").content
        val pending = receipt["pending_transaction_ids"]?.let { receiptPendingTransactions(it, "mutation_receipt_invalid") }
        val arrays = receiptWriteSetResult(receipt["write_set_result"], "mutation_receipt_invalid")
        val id = receipt.getValue("receipt_id").jsonPrimitive.content
        val items = when (section) {
            CashImportReceiptSection.HEADER -> listOf(JsonObject(receipt - setOf("pending_transaction_ids", "write_set_result")))
            CashImportReceiptSection.PENDING_TRANSACTION_IDS -> pending?.toList().orEmpty()
            else -> arrays.getValue(section.wireName).jsonArray
        }
        return StoredImportReceiptPage(id, version, pending?.size, items.size, items.drop(offset).take(readPageRowWindow))
    }

    private fun postgresImportPage(user: String, key: String, section: CashImportReceiptSection,
        position: ReadPagePosition,
    ): StoredImportReceiptPage? {
        val header = section == CashImportReceiptSection.HEADER
        val pending = section == CashImportReceiptSection.PENDING_TRANSACTION_IDS
        val array = if (pending) "result_summary -> 'pending_transaction_ids'" else "result_summary -> 'write_set_result' -> ?"
        val projection = if (header) """
            1 as item_count,
            (select json_object_agg(part.key, part.value)::text
             from json_each(result_summary) part
             where part.key not in ('write_set_result','pending_transaction_ids','affected_transaction_ids','projection_refs')) as header
        """ else "coalesce(json_array_length($array), 0) as item_count"
        val query = """
            select id, plan_id, created_at, (result_summary -> 'ledger_seq')::text as ledger_seq,
              json_array_length(result_summary -> 'pending_transaction_ids') as pending_count,
              (select array_agg(field) from json_object_keys(result_summary -> 'write_set_result') as field) as write_fields,
              $projection
            from cash_mutation_receipts
            where workspace_id = ? and user_id = ? and idempotency_key = ? and operation_kind = 'cash_write_set'
        """
        return connection.prepareStatement(query).use { statement ->
            var index = 1
            if (!header && !pending) statement.setString(index++, section.wireName)
            statement.setString(index++, workspace)
            statement.setString(index++, user)
            statement.setString(index, key)
            statement.executeQuery().use { row ->
                if (!row.next()) return null
                val id = row.getString("id")
                val version = receiptLedgerSequence(row.getString("ledger_seq")?.let(Json::parseToJsonElement),
                    "mutation_receipt_invalid").content
                val pendingCount = row.getObject("pending_count", Int::class.javaObjectType)
                val fields = row.getArray("write_fields")?.array as? Array<*>
                val expected = CashImportReceiptSection.entries
                    .filterNot { it in setOf(CashImportReceiptSection.HEADER, CashImportReceiptSection.PENDING_TRANSACTION_IDS) }
                    .map { it.wireName }.toSet()
                if (fields?.size != expected.size || fields.toSet() != expected)
                    throw IllegalArgumentException("mutation_receipt_invalid")
                val count = row.getInt("item_count")
                val items = if (header) {
                    val summary = Json.parseToJsonElement(row.getString("header")).jsonObject
                    listOf(buildJsonObject {
                        put("receipt_id", id)
                        put("plan_id", row.getString("plan_id")?.let(::JsonPrimitive) ?: JsonNull)
                        put("created_at", formatReceiptCreationTime(row.getObject("created_at", OffsetDateTime::class.java).toInstant()))
                        put("operation_kind", "cash_write_set")
                        put("committed", true)
                        summary.forEach { (name, value) -> put(name, value) }
                    })
                } else if (count == 0 || position.offset >= count) emptyList()
                else postgresImportPageItems(user, key, section, array, position)
                StoredImportReceiptPage(id, version, pendingCount, count, items)
            }
        }
    }

    private fun postgresImportPageItems(
        user: String, key: String, section: CashImportReceiptSection, array: String, position: ReadPagePosition,
    ): List<JsonElement> {
        val query = """
            select item.value::text
            from cash_mutation_receipts, lateral json_array_elements($array) with ordinality as item(value, position)
            where workspace_id = ? and user_id = ? and idempotency_key = ? and operation_kind = 'cash_write_set'
            order by item.position offset ? limit ?
        """
        return connection.prepareStatement(query).use { statement ->
            var index = 1
            if (section != CashImportReceiptSection.PENDING_TRANSACTION_IDS) statement.setString(index++, section.wireName)
            statement.setString(index++, workspace)
            statement.setString(index++, user)
            statement.setString(index++, key)
            statement.setInt(index++, position.offset)
            statement.setInt(index, if (position.fragmentOffset == 0) readPageRowWindow else 1)
            statement.fetchSize = 8
            statement.executeQuery().use { row ->
                buildList {
                    var bytes = 0
                    while (row.next()) {
                        val value = row.getString(1)
                        val size = value.encodeToByteArray().size
                        if (isNotEmpty() && size > importReceiptReadPageTargetBytes - bytes) break
                        add(Json.parseToJsonElement(value))
                        bytes += size
                        if (bytes >= importReceiptReadPageTargetBytes) break
                    }
                }
            }
        }
    }

    fun results(user: Any?,receipt: Any?,resultKind: Any?=null,after: Any?=0,limit: Any?=50,onlyOperation: String?=null): JsonObject? {
        val size=strictCashInteger(limit)?.takeIf {it in java.math.BigInteger.ONE..java.math.BigInteger.valueOf(200)}?.toInt()
        val cursor=strictCashInteger(after)?.takeIf {it.signum()>=0 && it<=java.math.BigInteger.valueOf(Long.MAX_VALUE)}?.toLong()
        if(size==null || cursor==null || resultKind!=null && resultKind !in setOf("row","relation","file","account")) throw IllegalArgumentException("mutation_page_invalid")
        if (onlyOperation == null) {
            if (get(user, receipt) == null) return null
        } else if (!sql.fetchExists(sql.selectOne().from(receipts)
                .where(receipts.WORKSPACE_ID.eq(workspace)).and(receipts.USER_ID.eq(reference(user, 128)))
                .and(receipts.ID.eq(reference(receipt, 36))).and(receipts.OPERATION_KIND.eq(onlyOperation)))) return null
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

internal data class StoredImportReceiptPage(
    val id: String,
    val version: String,
    val pendingCount: Int?,
    val count: Int,
    val items: List<JsonElement>,
)

private val receiptTimeFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)

private fun formatReceiptCreationTime(instant: Instant): String {
    val micros = instant.nano / 1000
    val fraction = if (micros == 0) "" else "." + micros.toString().padStart(6, '0')
    return receiptTimeFormat.format(instant) + fraction + "+00:00"
}
