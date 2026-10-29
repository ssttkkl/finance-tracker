package com.finance.tracker.backend

import java.math.MathContext
import java.math.RoundingMode
import kotlinx.serialization.json.*

internal class CashSnapshotDocument(var value:JsonObject)
private val snapshotArithmetic=MathContext(80,RoundingMode.HALF_EVEN)
private fun snapshotMoney(value:JsonElement):CashAmount=exactCashAmount(if(value is JsonPrimitive && value.isString) value.content else pythonString(value))
private fun snapshotFixed(value:CashAmount):String {
    val number=value.arithmeticValue()
    return (if(number.signum()==0 && value.representation.negative) "-" else "")+number.toPlainString()
}

internal class CashSnapshotRepository(private val context:CashSqlContext) {
    private data class Account(val id:Long,val name:String,val type:String)
    private var loaded:CashSnapshotDocument?=null
    private var loadedWithLock=false
    private var accounts:List<Account>?=null
    private val c get()=context.connection
    private val workspace get()=context.workspace
    private val lock get()=if(context.sqlite) "" else " FOR UPDATE"
    init {(c as? CashSqlTransactionBoundary)?.cashOnSavepointRollback {if(loaded!=null) {accounts=null;loaded!!.value=read(loadedWithLock)}}}
    private fun accountModels():List<Account> {
        context.requireActive()
        return accounts ?: c.prepareStatement("SELECT id,name,type FROM accounts WHERE workspace_id=? ORDER BY id").use {
            it.setString(1,workspace);it.executeQuery().use {r ->buildList {while(r.next()) add(Account(r.getLong(1),r.getString(2),r.getString(3)))}}
        }.also {accounts=it}
    }
    private fun accountId(key:String):Long? {
        if(key.isEmpty()) return null
        val digits=buildString {for(code in key.codePoints().toArray()) {
            val start=pythonIntegerDigitStarts.firstOrNull {code in it until it+10} ?: return if(key.codePoints().allMatch {it in pythonNondecimalDigits || pythonIntegerDigitStarts.any {start ->it in start until start+10}}) throw IllegalArgumentException("invalid snapshot account ID") else null
            append('0'+code-start)
        }}
        if(digits.length>4300) throw IllegalArgumentException("snapshot account ID exceeds integer limit")
        return digits.toLongOrNull()
    }
    private fun toNames(value:JsonObject):JsonObject {
        val models=accountModels().associateBy {it.id};val exactNames=models.values.associate {it.id.toString() to it.name};val buckets=value["accounts"] ?: return value
        require(buckets is JsonObject) {"snapshot accounts must be a mapping"}
        return JsonObject(value+("accounts" to JsonObject(buckets.mapValues {(_,bucket) ->
            if(bucket !is JsonObject) bucket else {
                val named=linkedMapOf<String,JsonElement>()
                for((key,pocket) in bucket) {val name=exactNames[key] ?: accountId(key)?.let {models[it]?.name} ?: key;val previous=named[name]
                    named[name]=if(previous is JsonObject && pocket is JsonObject) JsonObject(previous+pocket) else pocket
                };JsonObject(named)
            }
        })))
    }
    private fun toIds(value:JsonObject):JsonObject {
        val models=accountModels();val buckets=value["accounts"] ?: return value;require(buckets is JsonObject) {"snapshot accounts must be a mapping"}
        return JsonObject(value+("accounts" to JsonObject(buckets.mapValues {(type,bucket) ->
            if(bucket !is JsonObject) bucket else JsonObject(bucket.entries.associate {(name,pocket) ->
                val model=models.firstOrNull {it.name==name && it.type==type} ?: if(type=="security") models.firstOrNull {it.name==name && it.type in setOf("security","crypto")} else null
                (model?.id?.toString() ?: name) to pocket
            })
        })))
    }
    private fun read(lockRow:Boolean):JsonObject {
        context.requireActive()
        val sql=if(lockRow && !context.sqlite) "SELECT s.payload FROM ledger_snapshots s JOIN workspaces w ON w.id=s.workspace_id WHERE s.workspace_id=? FOR UPDATE OF s,w"
            else "SELECT payload FROM ledger_snapshots WHERE workspace_id=?"+if(lockRow) lock else ""
        val stored=c.prepareStatement(sql).use {it.setString(1,workspace);it.executeQuery().use {r ->if(r.next()) Json.parseToJsonElement(r.getString(1)).jsonObject else null}}
        if(lockRow && (context.sqlite || stored==null)) c.prepareStatement("SELECT id FROM workspaces WHERE id=?$lock").use {it.setString(1,workspace);it.executeQuery().use {r ->check(r.next()) {"snapshot workspace missing"}}}
        return toNames(stored ?: Json.parseToJsonElement("""{"updated_at":"","accounts":{"cash":{},"loan":{},"lend":{},"security":{}}}""").jsonObject)
    }
    fun load(lock:Boolean=false):CashSnapshotDocument {context.requireActive();return loaded ?: CashSnapshotDocument(read(lock)).also {loaded=it;loadedWithLock=lock}}
    fun save(document:CashSnapshotDocument) {
        context.requireActive();val payload=toIds(copyCashJsonContainers(document.value).jsonObject)
        val old=c.prepareStatement("SELECT version FROM ledger_snapshots WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {r ->if(r.next()) r.getLong(1) else null}}
        val json=if(context.sqlite) "?" else "CAST(? AS json)"
        c.prepareStatement("INSERT INTO ledger_snapshots(workspace_id,payload,version,updated_at) VALUES(?,$json,?,?) ON CONFLICT(workspace_id) DO UPDATE SET payload=excluded.payload,version=excluded.version,updated_at=excluded.updated_at").use {
            it.setString(1,workspace);it.setString(2,payload.toString());it.setLong(3,old?.let {v ->Math.addExact(v,1)} ?: 1);it.instant(4,context.now(),context.sqlite);it.executeUpdate()
        }
        loaded=CashSnapshotDocument(copyCashJsonContainers(document.value).jsonObject)
    }
    private fun pocket(document:CashSnapshotDocument,name:String,type:String):JsonObject {
        val buckets=document.value["accounts"] ?: JsonObject(emptyMap());require(buckets is JsonObject) {"snapshot accounts must be a mapping"}
        val accounts=buckets[type] ?: JsonObject(emptyMap());require(accounts is JsonObject) {"snapshot account type must be a mapping"}
        val pocket=accounts[name] ?: JsonObject(emptyMap());require(pocket is JsonObject) {"snapshot account balance must be a currency mapping"};return pocket
    }
    private fun put(document:CashSnapshotDocument,name:String,type:String,currency:String,value:String) {
        val pocket=pocket(document,name,type);val buckets=document.value["accounts"]?.jsonObject ?: JsonObject(emptyMap());val accounts=buckets[type]?.jsonObject ?: JsonObject(emptyMap())
        val changedPocket=JsonObject(pocket+(currency to JsonPrimitive(value)))
        val changedAccounts=JsonObject(accounts+(name to changedPocket))
        document.value=JsonObject(document.value+("accounts" to JsonObject(buckets+(type to changedAccounts))))
    }
    fun setBalance(document:CashSnapshotDocument,name:String,type:String,currency:String,value:String) {context.requireActive();put(document,name,type,currency,snapshotFixed(snapshotMoney(JsonPrimitive(value))))}
    fun updateBalance(document:CashSnapshotDocument,name:String,type:String,currency:String,delta:String) {
        context.requireActive();val old=snapshotMoney(pocket(document,name,type)[currency] ?: JsonPrimitive(0));val change=snapshotMoney(JsonPrimitive(delta))
        val result=old.arithmeticValue().add(change.arithmeticValue(),snapshotArithmetic)
        val money=CashAmount.parseOrNull(result.toString()) ?: throw IllegalArgumentException("value exceeds NUMERIC(38,18)")
        put(document,name,type,currency,(if(result.signum()==0 && old.representation.negative && change.representation.negative) "-" else "")+snapshotFixed(money))
    }
}

internal fun cashCountsTowardBalance(row:JsonObject?):Boolean= row!=null && row.isNotEmpty() && (row["state"] ?: JsonPrimitive("posted"))==JsonPrimitive("posted") &&
    !pythonTruthy(row["deleted_at"]) && !pythonTruthy(row["deleted"]) && (row["record_origin"]==JsonPrimitive("manual") ||
    pythonString(row["record_type"].takeIf(::pythonTruthy) ?: JsonPrimitive("other")) !in setOf("transfer_in","transfer_out"))

internal class CashBalanceService(private val context:CashSqlContext) {
    private val repository get()=context.snapshot
    fun applyComponents(document:CashSnapshotDocument,row:JsonObject?,multiplier:Int) {
        if(!cashCountsTowardBalance(row)) return
        val source=row!!;val components=source["components"].takeIf(::pythonTruthy)?.jsonArray ?: JsonArray(listOf(buildJsonObject {
            put("account_name",source["account_name"] ?: JsonNull);put("account_type",source["account_type"] ?: JsonNull);put("amount",source["amount"] ?: JsonNull);put("currency",source["currency"] ?: JsonNull)
        }))
        for(raw in components) {
            val component=raw.jsonObject;val name=pythonString(component["account_name"].takeIf(::pythonTruthy) ?: JsonPrimitive("")).trim {it.code in pythonWhitespace}
            require(name.isNotEmpty()) {"组成项缺少目标账户"}
            val amount=snapshotMoney(component["amount"] ?: JsonNull);val delta=amount.arithmeticValue().multiply(multiplier.toBigDecimal(),snapshotArithmetic)
            val negativeZero=delta.signum()==0 && (amount.representation.negative xor (multiplier<0))
            val text=(if(negativeZero) "-" else "")+delta.toPlainString()
            val suppliedType=component["account_type"]
            require(suppliedType==null || suppliedType==JsonNull || suppliedType is JsonPrimitive && suppliedType.isString) {"snapshot account type must be a string"}
            val type=(suppliedType?.jsonPrimitive?.contentOrNull).orEmpty().ifEmpty {"cash"}
            val currency=pythonString(component["currency"].takeIf(::pythonTruthy) ?: source["currency"].takeIf(::pythonTruthy) ?: JsonPrimitive(""))
            val upper=buildString {currency.codePoints().forEach {append(pythonUpperCase[it] ?: String(Character.toChars(it)))}}
            repository.updateBalance(document,name,type,upper,text)
        }
    }
    fun applyChanges(changes:List<Pair<JsonObject?,JsonObject?>>) {
        context.requireActive();if(changes.none {cashCountsTowardBalance(it.first) || cashCountsTowardBalance(it.second)}) return
        val document=repository.load(lock=true);var latest:JsonObject?=null
        for((old,new) in changes) {applyComponents(document,old,-1);applyComponents(document,new,1)
            latest=if(cashCountsTowardBalance(new)) new else if(cashCountsTowardBalance(old)) old else latest
        }
        document.value=JsonObject(document.value+("updated_at" to (latest?.get("occurred_at") ?: JsonPrimitive(""))))
        repository.save(document)
    }
}
