@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.finance.tracker.backend

import com.finance.tracker.business.contracts.*
import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.*

private val receiptJson=Json {encodeDefaults=true;explicitNulls=true;ignoreUnknownKeys=true}
class ReceiptReadService(private val database: Database,private val now: ()->Instant=Instant::now,private val beforeRead: ()->Unit={},private val beforeWorkspaceLock: ()->Unit={}) {
    private val reads=ReadServices(database,now)
    private fun <T> context(token: String?,lock: Boolean=true,action: (Connection,Principal)->T): T {
        val operation: (Connection)->T = {c ->
            val first=reads.context(c,token,write=false)
            beforeWorkspaceLock()
            if(lock && !database.sqlite && !c.lockWorkspace(false, first.workspaceId!!)) throw AccessFailure(true)
            val principal=reads.context(c,token,write=false)
            if(principal.workspaceId!=first.workspaceId) throw AccessFailure(true)
            beforeRead()
            val result=action(c,principal)
            val fresh=reads.context(c,token,write=false)
            if(fresh.workspaceId!=principal.workspaceId || fresh.userId!=principal.userId) throw AccessFailure(true)
            result
        }
        return try {if(lock) database.write(operation) else database.read(operation)}
        catch(failure: AccessFailure) {throw failure}
        catch(failure: ReceiptFailure) {throw failure}
        catch(failure: Exception) {throw ReceiptUnavailable(failure)}
    }
    fun receipt(token: String?,id: String): JsonObject=context(token) {c,principal ->
        publicReceipt(ReceiptRepository(c,principal.workspaceId!!,database.sqlite).get(principal.userId,id) ?: throw ReceiptFailure("receipt_not_found"))
    }
    fun receiptByKey(token: String?,key: String): JsonObject=context(token) {c,principal ->
        publicReceipt(ReceiptRepository(c,principal.workspaceId!!,database.sqlite).lookupKey(principal.userId,key) ?: throw ReceiptFailure("receipt_not_found"))
    }
    internal fun results(token: String?,id: String,kind: String,page: ReceiptPage): JsonObject=context(token,true) {c,principal ->
        val workspace=principal.workspaceId!!
        val stored=ReceiptRepository(c,workspace,database.sqlite).results(principal.userId,id,resultKind=kind,after=page.after,limit=page.limit) ?: throw ReceiptFailure("receipt_not_found")
        val state=ProjectionStateRepository(c,workspace,database.sqlite).ready()
        val items=stored.getValue("items").jsonArray.map {raw ->
            val row=raw.jsonObject.toMutableMap()
            val code=row["code"]?.takeUnless {it==JsonNull}?.jsonPrimitive?.content
            if(!code.isNullOrEmpty()) row["message"]=JsonPrimitive(cashImportIssue(code).message)
            val dto=receiptJson.decodeFromJsonElement<CashMutationResultDto>(JsonObject(row))
            JsonObject(receiptJson.encodeToJsonElement(dto).jsonObject.filterKeys {it in row})
        }
        val ids=items.mapNotNull {it["transaction_id"]?.takeUnless {it==JsonNull}?.jsonPrimitive?.content?.takeIf {text ->text.matches(Regex("[0-9]+"))}?.toLongOrNull()?.takeIf {it>0}}.toSet().sorted()
        val member = CASH_PROJECTION_MEMBERS; val projection = CASH_PROJECTIONS
        val refs=if(state==null || ids.isEmpty()) emptyList() else c.jooq(database.sqlite)
            .select(member.CASH_TRANSACTION_ID, projection.PROJECTION_ID).from(member).join(projection).on(projection.ID.eq(member.PROJECTION_ROW_ID))
            .where(member.WORKSPACE_ID.eq(workspace)).and(member.DATASET_ID.eq(state.first))
            .and(projection.WORKSPACE_ID.eq(workspace)).and(projection.DATASET_ID.eq(state.first)).and(member.CASH_TRANSACTION_ID.`in`(ids))
            .fetch().map { CashMutationProjectionRefDto(it[member.CASH_TRANSACTION_ID]!!.toString(), it[projection.PROJECTION_ID]!!) }
        val fresh=reads.context(c,token,write=false)
        if(fresh.workspaceId!=workspace || fresh.userId!=principal.userId) throw AccessFailure(true)
        val cursor=stored.getValue("next_after").takeUnless {it==JsonNull}?.jsonPrimitive?.content
        val dto=CashMutationResultPageDto(items.map {receiptJson.decodeFromJsonElement<CashMutationResultDto>(it)},cursor,refs,state?.second ?: 0)
        JsonObject(receiptJson.encodeToJsonElement(dto).jsonObject+mapOf("items" to JsonArray(items)))
    }
    private fun publicReceipt(value: JsonObject): JsonObject {
        fun text(key: String,nullable: Boolean=false): String? {
            val raw=value[key]
            if(nullable && raw==JsonNull) return null
            return (raw as? JsonPrimitive)?.takeIf {it.isString}?.content ?: throw IllegalArgumentException("mutation_receipt_invalid")
        }
        if(value["committed"]!=JsonPrimitive(true)) throw IllegalArgumentException("mutation_receipt_invalid")
        val base=CashMutationReceiptDto(text("receipt_id")!!,text("plan_id",true),text("operation_kind")!!,true,text("created_at")!!)
        val descriptor=CashMutationReceiptDto.serializer().descriptor
        val fields=(0 until descriptor.elementsCount).map {descriptor.getElementName(it)}.toSet()
        val safe=value.filterKeys {it in fields}.toMutableMap()
        fun count(raw: JsonElement?,positive: Boolean=false,bounded: Boolean=false) {
            val primitive=raw as? JsonPrimitive
            val number=primitive?.takeUnless {it.isString}?.content?.toBigIntegerOrNull()
            if(number==null || number.signum()<(if(positive) 1 else 0) || bounded && number>java.math.BigInteger.valueOf(Long.MAX_VALUE)) throw IllegalArgumentException("mutation_receipt_invalid")
        }
        for(field in listOf("summary","legacy_by_account")) if(field in safe) {
            val map=safe[field] as? JsonObject ?: throw IllegalArgumentException("mutation_receipt_invalid")
            map.values.forEach {count(it)}
        }
        for(field in listOf("needs_attention_count","pending_item_count","projection_version","account_revision"))
            safe[field]?.takeUnless {it==JsonNull}?.let {count(it,field=="account_revision",field=="account_revision")}
        for(field in listOf("account_id","retained_transaction_id")) safe[field]?.takeUnless {it==JsonNull}?.let {if(it !is JsonPrimitive || !it.isString) throw IllegalArgumentException("mutation_receipt_invalid")}
        safe["status"]?.takeUnless {it==JsonNull}?.let {if(it !is JsonPrimitive || !it.isString || it.content !in setOf("completed","partial","no_changes","failed")) throw IllegalArgumentException("mutation_receipt_invalid")}
        safe["account"]?.takeUnless {it==JsonNull}?.let {raw ->
            val account=raw as? JsonObject ?: throw IllegalArgumentException("mutation_receipt_invalid")
            val names=setOf("account_id","name","type","active","currencies","account_revision","deleted")
            if(!account.keys.containsAll(names)) throw IllegalArgumentException("mutation_receipt_invalid")
            for(field in listOf("account_id","name","type")) if((account[field] as? JsonPrimitive)?.isString!=true) throw IllegalArgumentException("mutation_receipt_invalid")
            for(field in listOf("active","deleted")) if((account[field] as? JsonPrimitive)?.let {it.isString || it.booleanOrNull==null}!=false) throw IllegalArgumentException("mutation_receipt_invalid")
            count(account["account_revision"],true,true)
            if((account["currencies"] as? JsonArray)?.all {it is JsonPrimitive && it.isString}!=true) throw IllegalArgumentException("mutation_receipt_invalid")
            safe["account"]=JsonObject(account.filterKeys {it in names})
        }
        safe["commit_outcome"]?.takeUnless {it==JsonNull}?.let {raw ->
            val outcome=raw as? JsonObject ?: throw IllegalArgumentException("mutation_receipt_invalid")
            for(field in listOf("has_new_failures","has_business_writes")) if((outcome[field] as? JsonPrimitive)?.let {it.isString || it.booleanOrNull==null}!=false) throw IllegalArgumentException("mutation_receipt_invalid")
            val codes=outcome["reason_codes"] as? JsonArray ?: throw IllegalArgumentException("mutation_receipt_invalid")
            if(codes.size>7 || codes.any {it !is JsonPrimitive || !it.isString || it.content !in setOf("record_changed","account_changed","entry_changed","row_save_failed","source_evidence_save_failed","relation_context_changed","relation_save_failed")}) throw IllegalArgumentException("mutation_receipt_invalid")
            safe["commit_outcome"]=JsonObject(outcome.filterKeys {it in setOf("has_new_failures","has_business_writes","reason_codes")})
        }
        val encoded=receiptJson.encodeToJsonElement(base).jsonObject
        for(field in listOf("receipt_id","plan_id","operation_kind","committed","created_at")) safe[field]=encoded.getValue(field)
        return JsonObject(safe)
    }
}
