package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.json.*

internal data class LegacyCashInput(val account:String,val value:String,val currency:String?=null,val date:String?=null,
    val counterparty:String="",val note:String="",val source:String="",val recordType:String="other",val category:String?=null)

class LegacyCashflowService(private val database:Database,private val now:()->Instant=Instant::now,
    private val beforeWorkspaceLock:()->Unit={},private val beforeReturn:()->Unit={},private val beforeWrite:()->Unit={}) {
    private val reads=ReadServices(database,now)
    private fun input(value:JsonElement)=copyCashJsonContainers(value) as? JsonObject ?: legacyCashPayloadError("cashflow_payload_invalid")
    internal fun add(token:String?,value:JsonElement,original:DecodedPythonJson?=null,requestWorkspace:String?=null):JsonObject {
        val body=input(value)
        val amount=legacyCashDecimalText(body,"amount",original=original)!!;val counterparty=legacyCashText(body,"counterparty")!!
        val account=legacyCashText(body,"account")!!;val currency=legacyCashText(body,"currency")!!
        val note=legacyCashText(body,"note",false).orEmpty();val source=legacyCashText(body,"source",false).orEmpty()
        val date=legacyCashText(body,"date",false);val type=legacyCashText(body,"record_type",false).orEmpty().ifEmpty {"other"};val category=legacyCashText(body,"category_id",false)
        return write(token,LegacyCashInput(account,amount,currency,date,counterparty,note,source,type,category),false,body,original,requestWorkspace)
    }
    internal fun checkin(token:String?,value:JsonElement,original:DecodedPythonJson?=null,requestWorkspace:String?=null):JsonObject {
        val body=input(value)
        val account=legacyCashText(body,"account")!!;val balance=legacyCashDecimalText(body,"balance",original=original)!!
        val currency=legacyCashText(body,"currency")!!;val date=legacyCashText(body,"date",false)
        return write(token,LegacyCashInput(account,balance,currency,date),true,body,original,requestWorkspace)
    }
    internal fun addTransaction(token:String?,value:LegacyCashInput):JsonObject=write(token,value,false)
    internal fun checkinBalance(token:String?,value:LegacyCashInput):JsonObject=write(token,value,true)
    private fun write(token:String?,input:LegacyCashInput,checkin:Boolean,body:JsonObject?=null,original:DecodedPythonJson?=null,requestWorkspace:String?=null):JsonObject {
        fun source(key:String,text:String):JsonElement=body?.get(key).takeIf {it is JsonPrimitive && it.isString} ?: JsonPrimitive(text)
        fun positions(key:String,text:String):Set<Int> {
            val value=source(key,text);val shift=value.jsonPrimitive.content.indexOf(text).coerceAtLeast(0)
            return original?.stringRawSurrogates(value).orEmpty().map {it-shift}.filter {it in text.indices}.toSet()
        }
        val currency=normalizedCashOperationCurrency(input.currency.orEmpty())
            ?.takeIf {positions("currency",input.currency.orEmpty()).isEmpty()}
            ?: legacyCashBusinessError("cashflow.currency_required","必须显式提供有效的 3 位币种码")
        val date=if(input.date.isNullOrEmpty()) manualInstantText(now()) else if(checkin) input.date+" 00:00:00" else input.date
        val day=date.take(10)
        beforeWrite()
        return database.write {c ->
            val workspace=requestWorkspace ?: reads.session(c,token).workspaceId ?: throw AccessFailure(true)
            beforeWorkspaceLock()
            if (!c.lockWorkspace(database.sqlite, workspace)) throw AccessFailure(true)
            reads.lockedContext(c,token,workspace,write=true)
            val context=CashSqlContext(c,workspace,database.sqlite,now)
            try {manualOriginalUtf8(source("account",input.account),original,normalized=input.account)} catch(failure:ManualCashBusinessFailure) {throw IllegalArgumentException(failure.message)}
            val account=CashTransferRepository(context).accounts().firstOrNull {it.account.name==input.account}
                ?: legacyCashBusinessError("account.not_found","未找到账户: ${input.account}")
            if(account.account.type in setOf("security","crypto")) legacyCashBusinessError("cashflow.unsupported_account_type",
                if(checkin) "现金余额校准不支持 security 或 crypto 账户" else "手工现金交易不支持 security 或 crypto 账户",buildJsonObject {put("account_type",account.account.type)})
            val sourceType=(if(checkin) "手动" else input.source).trim {it.code in pythonWhitespace}
            val type=if(checkin) "other" else input.recordType.ifEmpty {"other"};val subtype=defaultCashRecordSubtype(type)
            if(type !in cashRecordTypes) {
                manualOriginalUtf8(source("record_type",type),original,response=true,normalized=type)
                throw IllegalArgumentException("unknown record_type: $type")
            }
            val sourceFingerprint=if(sourceType.isEmpty()) null else {
                val text=canonicalCashJson(mapOf("source_type" to sourceType,"record_id" to "","payload" to null),validateUtf8=false)
                val raw=positions("source",sourceType);val encodedPositions=mutableSetOf<Int>()
                var offset=text.indexOf(":\"",text.indexOf("\"source_type\""))+2
                for((index,char) in sourceType.withIndex()) {
                    if(index in raw) encodedPositions+=offset
                    offset+=when(char) {'"','\\','\b','\u000c','\n','\r','\t' ->2;else ->if(char.code<32) 6 else 1}
                }
                try {manualOriginalUtf8(JsonPrimitive(text),null,rawSurrogates=encodedPositions)} catch(failure:ManualCashBusinessFailure) {throw IllegalArgumentException(failure.message)}
                MessageDigest.getInstance("SHA-256").digest(strictUtf8(text)).joinToString("") {"%02x".format(it.toInt() and 0xff)}
            }
            val datePositions=positions("date",input.date.orEmpty())
            val occurred=try {manualCashTimestamp(date,datePositions)} catch(failure:IllegalArgumentException) {
                manualOriginalUtf8(JsonPrimitive(date),null,response=true,rawSurrogates=datePositions);throw failure
            }
            val amount=if(checkin) CashAmount.parse("0") else exactCashAmount(input.value,"value")
            val balance=if(checkin) CashAmount.parseOrNull(input.value) else null
            val note=if(checkin) {
                val rounded=balance?.let {BigDecimal(it.numericText()).setScale(2,RoundingMode.HALF_EVEN)}
                val text=if(rounded==null) "0.00" else (if(rounded.signum()==0 && balance.representation.negative) "-" else "")+rounded.toPlainString()
                "余额校准"+mapOf("CNY" to "¥","USD" to "$","HKD" to "HK$")[currency].orEmpty()+text
            } else input.note
            val category=if(checkin) null else input.category?.trim {it.code in pythonWhitespace}?.ifEmpty {null}
            for((key,text) in listOf("counterparty" to if(checkin) "" else input.counterparty,"note" to note,"category_id" to category.orEmpty())) {
                try {manualOriginalUtf8(if(checkin) JsonPrimitive(text) else source(key,text),if(checkin) null else original,normalized=text)}
                catch(failure:ManualCashBusinessFailure) {throw IllegalArgumentException(failure.message)}
            }
            val ids=context.insertAtomicCash(account.id,occurred,amount,currency,type,subtype,if(checkin) "legacy" else "manual",sourceType=sourceType.ifEmpty {null},
                fingerprint=sourceFingerprint,counterparty=if(checkin) "" else input.counterparty,note=note,category=category)
            val snapshot=context.snapshot.load(lock=true)
            if(checkin) {
                context.snapshot.setBalance(snapshot,input.account,account.account.type,currency,input.value)
            } else {
                context.snapshot.updateBalance(snapshot,input.account,account.account.type,currency,amount.toString())
            }
            snapshot.value=JsonObject(snapshot.value+("updated_at" to JsonPrimitive(if(checkin) day else date)));context.snapshot.save(snapshot)
            CashProjectionService.maintainIfReadyInSession(context,setOf(ids.first))
            beforeReturn();reads.lockedContext(c,token,workspace,write=true)
            val result=buildJsonObject {
                put("ok",true);put("error",JsonNull)
                put("row",buildJsonObject {
                    put("occurred_at",date);put("amount",amount.fixedTransferText());put("currency",currency);put("counterparty",if(checkin) "" else input.counterparty)
                    put("note",note);put("record_type",type);if(checkin) put("record_subtype","not_applicable")
                    if(!checkin) put("category_id",input.category?.let(::JsonPrimitive) ?: JsonNull)
                    put("account_name",input.account);put("source",if(checkin) "手动" else input.source);put("source_type","");put("record_origin",if(checkin) "legacy" else "manual")
                    if(checkin) put("locked","") else put("record_id","")
                })
                put("rows",JsonNull);put("message","")
                put("details",buildJsonObject {put("account",account.wire);if(checkin) put("day",day)})
            }
            if(!checkin) try {manualOriginalUtf8(JsonPrimitive(result.toString()),null)}
                catch(failure:ManualCashBusinessFailure) {throw IllegalArgumentException(failure.message)}
            result
        }
    }
}
