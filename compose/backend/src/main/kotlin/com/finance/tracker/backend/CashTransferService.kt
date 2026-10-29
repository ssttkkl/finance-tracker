package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*

import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.json.*

internal class CashflowOperationFailure(val code:String,message:String,val details:JsonObject?=null):RuntimeException(message)
internal fun legacyCashBusinessError(code:String,message:String,details:JsonObject=JsonObject(emptyMap())):Nothing=throw CashflowOperationFailure(code,message,details)
internal fun legacyCashPayloadError(code:String):Nothing {
    manualOriginalUtf8(JsonPrimitive(code),null,response=true)
    throw CashflowOperationFailure(code,"请求参数无效。")
}
internal fun legacyCashText(body:JsonObject,key:String,required:Boolean=true):String? {
    val value=body[key]
    if(!required && (value==null || value==JsonNull)) return null
    if(value !is JsonPrimitive || !value.isString || required && value.content.all {it.code in pythonWhitespace}) legacyCashPayloadError("${key}_required")
    return value.content.trim {it.code in pythonWhitespace}
}
internal fun legacyCashDecimalText(body:JsonObject,key:String,required:Boolean=true,original:DecodedPythonJson?=null):String? {
    val raw=body[key]
    if(!required && (raw==null || raw==JsonNull)) return null
    if(raw !is JsonPrimitive || !raw.isString || raw.content.all {it.code in pythonWhitespace}) legacyCashPayloadError("${key}_must_be_decimal_string")
    if(original?.stringRawSurrogates(raw).orEmpty().isNotEmpty()) legacyCashPayloadError("${key}_must_be_decimal_string")
    if(com.finance.tracker.business.domain.LedgerDecimal.parseRepresentationOrNull(raw.content)==null) legacyCashPayloadError("${key}_must_be_decimal_string")
    return raw.content
}
internal fun normalizedCashOperationCurrency(value:String):String? {
    val upper=buildString {value.trim {it.code in pythonWhitespace}.codePoints().forEach {append(pythonUpperCase[it] ?: String(Character.toChars(it)))}}
    if(upper.codePointCount(0,upper.length)!=3 || !upper.codePoints().allMatch {point ->(pythonLetterRanges.indices step 2).any {point in pythonLetterRanges[it]..pythonLetterRanges[it+1]}})
        return null
    return upper
}
internal data class CashTransferStoredAccount(val id:Long,val account:CashTransferAccount,val wire:JsonObject)
internal class CashTransferRepository(private val context:CashSqlContext) {
    fun accounts():List<CashTransferStoredAccount> {
        context.requireActive()
        val accounts = ACCOUNTS
        return context.sql.select(accounts.ID, accounts.NAME, accounts.TYPE, accounts.ACTIVE, accounts.CURRENCIES).from(accounts)
            .where(accounts.WORKSPACE_ID.eq(context.workspace)).orderBy(accounts.CREATED_AT, accounts.ID).fetch { row ->
                val name = row[accounts.NAME]!!
                val type = row[accounts.TYPE]!!
                val currencies = row[accounts.CURRENCIES]!!.jsonArray.filter(::pythonTruthy).map { item ->
                    val text = pythonString(item)
                    JsonPrimitive(buildString { text.codePoints().forEach { append(pythonUpperCase[it] ?: String(Character.toChars(it))) } })
                }
                CashTransferStoredAccount(row[accounts.ID]!!, CashTransferAccount(name, type), buildJsonObject {
                    put("name", name); put("type", type); put("active", row[accounts.ACTIVE]!!); put("currencies", JsonArray(currencies))
                })
            }
    }
    fun stage(account:CashTransferStoredAccount,row:JsonObject,original:DecodedPythonJson?,noteSource:JsonElement?,dateSurrogates:Set<Int>):Pair<Long,Long>? {
        context.requireActive()
        val investment=account.account.type in setOf("security","crypto")
        fun investmentAmount(key:String):CashAmount {
            val amount=exactCashAmount(row.getValue(key).jsonPrimitive.content,"value")
            if(amount.representation.exponent>= -18) return amount
            val quantized=amount.arithmeticValue().setScale(18,RoundingMode.HALF_UP)
            if(quantized.precision()>28) throw ManualCashInternalFailure()
            return exactCashAmount(quantized.toString(),"value")
        }
        val investmentFrom=if(investment) investmentAmount("from_amount") else null
        val investmentTo=if(investment) investmentAmount("to_amount") else null
        val date=row.getValue("occurred_at").jsonPrimitive.content
        val occurred=try {manualCashTimestamp(date,dateSurrogates)} catch(failure:IllegalArgumentException) {
            manualOriginalUtf8(JsonPrimitive(date),null,response=true,rawSurrogates=dateSurrogates)
            throw failure
        }
        try {manualOriginalUtf8(noteSource ?: row["note"],original,normalized=row.getValue("note").jsonPrimitive.content)} catch(failure:ManualCashBusinessFailure) {throw IllegalArgumentException(failure.message)}
        if(investment) {
            context.sql.insertInto(INVESTMENT_EVENTS)
            .set(INVESTMENT_EVENTS.WORKSPACE_ID, context.workspace)
            .set(INVESTMENT_EVENTS.ACCOUNT_ID, account.id)
            .setNull(INVESTMENT_EVENTS.SOURCE_TYPE)
            .set(INVESTMENT_EVENTS.RECORD_ID, "")
            .set(INVESTMENT_EVENTS.SOURCE_PAYLOAD, JsonNull)
            .set(INVESTMENT_EVENTS.OCCURRED_AT, occurred)
            .set(INVESTMENT_EVENTS.RECORD_TYPE, "funding")
            .set(INVESTMENT_EVENTS.RECORD_SUBTYPE, "subaccount")
            .set(INVESTMENT_EVENTS.CURRENCY, row.getValue("currency").jsonPrimitive.content)
            .set(INVESTMENT_EVENTS.NOTE, row.getValue("note").jsonPrimitive.content)
            .set(INVESTMENT_EVENTS.FROM_TICKER, row.getValue("from_ticker").jsonPrimitive.content)
            .set(INVESTMENT_EVENTS.FROM_AMOUNT, investmentFrom?.let { CashStoredDecimal(it.toString()) })
            .set(INVESTMENT_EVENTS.TO_TICKER, row.getValue("to_ticker").jsonPrimitive.content)
            .set(INVESTMENT_EVENTS.TO_AMOUNT, investmentTo?.let { CashStoredDecimal(it.toString()) })
            .set(INVESTMENT_EVENTS.COMMISSION, CashStoredDecimal(CashAmount.parse("0").toString()))
            .set(INVESTMENT_EVENTS.COMMISSION_ASSET, "")
            .set(INVESTMENT_EVENTS.PAYLOAD, buildJsonObject {put("manual_transfer_id",row.getValue("manual_transfer_id"))})
            .set(INVESTMENT_EVENTS.CREATED_AT, context.now())
            .returningResult(INVESTMENT_EVENTS.ID).fetchSingle().value1()!!
            return null
        }
        require(account.account.type in setOf("cash","loan","lend")) {"cashflow repository only supports cash, loan, and lend records"}
        val amount=exactCashAmount(row.getValue("amount").jsonPrimitive.content,"value");val currency=row.getValue("currency").jsonPrimitive.content
        return context.insertAtomicCash(account.id,occurred,amount,currency,row.getValue("record_type").jsonPrimitive.content,row.getValue("record_subtype").jsonPrimitive.content,
            "manual_transfer",transferId=row.getValue("manual_transfer_id").jsonPrimitive.content,note=row.getValue("note").jsonPrimitive.content)
    }
    fun relation(from:Pair<Long,Long>,to:Pair<Long,Long>,subtype:String) {
        context.sql.insertInto(TRANSACTION_RELATIONS)
            .set(TRANSACTION_RELATIONS.WORKSPACE_ID, context.workspace)
            .set(TRANSACTION_RELATIONS.KIND, "transfer_pair")
            .set(TRANSACTION_RELATIONS.SUBTYPE, subtype)
            .set(TRANSACTION_RELATIONS.PRIMARY_TRANSACTION_ID, from.first)
            .set(TRANSACTION_RELATIONS.SECONDARY_TRANSACTION_ID, to.first)
            .set(TRANSACTION_RELATIONS.PRIMARY_COMPONENT_ID, from.second)
            .set(TRANSACTION_RELATIONS.SECONDARY_COMPONENT_ID, to.second)
            .set(TRANSACTION_RELATIONS.ANCHOR_TRANSACTION_ID, from.first)
            .set(TRANSACTION_RELATIONS.ANCHOR_COMPONENT_ID, from.second)
            .set(TRANSACTION_RELATIONS.PRIMARY_ROLE, "outgoing")
            .set(TRANSACTION_RELATIONS.SECONDARY_ROLE, "incoming")
            .set(TRANSACTION_RELATIONS.STATE, "confirmed")
            .set(TRANSACTION_RELATIONS.ACTIVE, true)
            .set(TRANSACTION_RELATIONS.ROW_REVISION, 1L)
            .setNull(TRANSACTION_RELATIONS.DECISION_GROUP_ID)
            .set(TRANSACTION_RELATIONS.DECISION_SOURCE, "manual")
            .set(TRANSACTION_RELATIONS.EVIDENCE, JsonObject(emptyMap()))
            .set(TRANSACTION_RELATIONS.APPLIED_AMOUNT, CashStoredDecimal(CashAmount.parse("0").toString()))
            .set(TRANSACTION_RELATIONS.RULE_ID, "manual.transfer.v1")
            .set(TRANSACTION_RELATIONS.CREATED_BY, "manual")
            .set(TRANSACTION_RELATIONS.CREATED_AT, context.now())
            .set(TRANSACTION_RELATIONS.DECIDED_BY, "")
            .setNull(TRANSACTION_RELATIONS.DECIDED_AT)
            .set(TRANSACTION_RELATIONS.DECISION_REASON, "")
            .returningResult(TRANSACTION_RELATIONS.ID).fetchSingle().value1()!!
    }
}

class CashTransferService(private val database:Database,private val now:()->Instant=Instant::now,
    private val beforeWorkspaceLock:()->Unit={},private val beforeReturn:()->Unit={},private val beforeWrite:()->Unit={}) {
    private val reads=ReadServices(database,now)
    internal fun transfer(token:String?,value:JsonElement,original:DecodedPythonJson?=null,requestWorkspace:String?=null):JsonObject {
        val input=copyCashJsonContainers(value) as? JsonObject ?: legacyCashPayloadError("cashflow_payload_invalid")
        val fromName=legacyCashText(input,"from_account")!!;val toName=legacyCashText(input,"to_account")!!
        val amountText=legacyCashDecimalText(input,"amount",original=original)!!;val arrivalText=legacyCashDecimalText(input,"to_amount",false,original)
        val rawFromCurrency=legacyCashText(input,"from_currency")!!;val rawToCurrency=legacyCashText(input,"to_currency")!!
        var date=legacyCashText(input,"date",false);var time=legacyCashText(input,"time",false);val note=legacyCashText(input,"note",false).orEmpty()
        val amount=exactCashAmount(amountText,"amount");val suppliedArrival=arrivalText?.let {exactCashAmount(it,"to_amount")}
        if(amount.arithmeticValue().signum()<=0 || suppliedArrival!=null && suppliedArrival.arithmeticValue().signum()<=0) legacyCashBusinessError("transfer.invalid_amount","转账金额必须大于零")
        if(date.isNullOrEmpty()) date=DateTimeFormatter.ofPattern("uuuu-MM-dd").format(now().atOffset(ZoneOffset.UTC))
        if(time.isNullOrEmpty()) time=DateTimeFormatter.ofPattern("HH:mm:ss").format(now().atOffset(ZoneOffset.UTC))
        fun currency(key:String,text:String):String {
            if(original?.stringRawSurrogates(input.getValue(key)).orEmpty().isNotEmpty()) legacyCashBusinessError("transfer.currency_required","转账双方必须显式提供有效的 3 位币种码")
            return normalizedCashOperationCurrency(text) ?: legacyCashBusinessError("transfer.currency_required","转账双方必须显式提供有效的 3 位币种码")
        }
        val fromCurrency=currency("from_currency",rawFromCurrency);val toCurrency=currency("to_currency",rawToCurrency)
        beforeWrite()
        return database.write {c ->
            val workspace=requestWorkspace ?: reads.session(c,token).workspaceId ?: throw AccessFailure(true)
            beforeWorkspaceLock()
            if (!c.lockWorkspace(database.sqlite, workspace)) throw AccessFailure(true)
            reads.lockedContext(c,token,workspace,write=true)
            val context=CashSqlContext(c,workspace,database.sqlite,now);val repository=CashTransferRepository(context);val accounts=repository.accounts()
            fun validateName(key:String,name:String) {try {manualOriginalUtf8(input[key],original,normalized=name)} catch(failure:ManualCashBusinessFailure) {throw IllegalArgumentException(failure.message)}}
            validateName("from_account",fromName)
            val from=accounts.firstOrNull {it.account.name==fromName} ?: legacyCashBusinessError("account.not_found","未找到来源账户: $fromName($fromCurrency)")
            validateName("to_account",toName)
            val to=accounts.firstOrNull {it.account.name==toName} ?: legacyCashBusinessError("account.not_found","未找到目标账户: $toName($toCurrency)")
            for(account in listOf(from,to)) if(account.account.type in setOf("security","crypto") && accounts.count {it.account.name==account.account.name && it.account.type in setOf("security","crypto")}>1)
                legacyCashBusinessError("transfer.ambiguous_investment_account","同名投资账户无法由当前事件格式可靠区分",buildJsonObject {put("account_name",account.account.name)})
            if(fromCurrency!=toCurrency && suppliedArrival==null) legacyCashBusinessError("transfer.to_amount_required","跨币种转账需要 --to-amount")
            val arrival=if(fromCurrency==toCurrency) amount else suppliedArrival!!
            val warning=if(fromCurrency==toCurrency && suppliedArrival!=null) "同币种转账无需 --to-amount，忽略" else ""
            val transferId=UUID.randomUUID().toString();val timestamp="$date $time"
            fun rawPositions(key:String,text:String):Set<Int> {
                val source=input[key] as? JsonPrimitive ?: return emptySet()
                val shift=source.content.indexOf(text).coerceAtLeast(0)
                return original?.stringRawSurrogates(source).orEmpty().map {it-shift}.filter {it in text.indices}.toSet()
            }
            val dateSurrogates=rawPositions("date",date!!)+rawPositions("time",time!!).map {it+date.length+1}
            val fromRow=JsonObject(cashTransferEvent(timestamp,amount,from.account,to.account,fromCurrency,toCurrency,true,note)+("manual_transfer_id" to JsonPrimitive(transferId)))
            val toRow=JsonObject(cashTransferEvent(timestamp,arrival,to.account,from.account,toCurrency,fromCurrency,false,note)+("manual_transfer_id" to JsonPrimitive(transferId)))
            val fromFact=repository.stage(from,fromRow,original,input["note"],dateSurrogates);val toFact=repository.stage(to,toRow,original,input["note"],dateSurrogates)
            if(fromFact!=null && toFact!=null) repository.relation(fromFact,toFact,if(fromCurrency!=toCurrency) "currency_exchange" else if("loan" in setOf(from.account.type,to.account.type)) "credit_repayment" else "ordinary_transfer")
            val document=context.snapshot.load(lock=true)
            val outgoing=CashAmount.parse("-"+amount.toString())
            document.value=applyCashTransferSnapshot(document.value,from.account,fromCurrency,outgoing)
            document.value=applyCashTransferSnapshot(document.value,to.account,toCurrency,arrival)
            document.value=JsonObject(document.value+("updated_at" to JsonPrimitive(date)));context.snapshot.save(document)
            val ids=listOfNotNull(fromFact?.first,toFact?.first).toSet()
            if(ids.isNotEmpty()) CashProjectionService.maintainIfReadyInSession(context,ids)
            beforeReturn();reads.lockedContext(c,token,workspace,write=true)
            buildJsonObject {
                put("ok",true);put("error",JsonNull);put("row",JsonNull);put("rows",JsonArray(listOf(fromRow,toRow)));put("message","")
                put("details",buildJsonObject {
                    put("from_account",from.wire);put("to_account",to.wire);put("amount",amount.fixedTransferText());put("to_amount",arrival.fixedTransferText())
                    put("from_currency",fromCurrency);put("to_currency",toCurrency);put("date",date);put("warning",warning)
                    if(fromCurrency!=toCurrency) put("rate",amount.arithmeticValue().divide(arrival.arithmeticValue(),MathContext(28,RoundingMode.HALF_EVEN)).toPlainString())
                })
            }
        }
    }
}
