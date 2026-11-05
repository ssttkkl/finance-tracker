package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

import java.math.MathContext
import java.math.BigInteger
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*

internal class ManualCashBusinessFailure(message:String):RuntimeException(message)
internal class ManualCashInternalFailure:RuntimeException("manual_cash_internal_failure")
private fun manualBusiness(message:String):Nothing=throw ManualCashBusinessFailure(message)
private fun manualText(value:JsonElement?):String=pythonString(value.takeIf(::pythonTruthy) ?: JsonPrimitive(""))
private fun manualTrim(value:String)=value.trim {it.code in pythonWhitespace}
internal fun manualOriginalUtf8(value:JsonElement?,original:DecodedPythonJson?,response:Boolean=false,normalized:String?=null,rawSurrogates:Set<Int>?=null) {
    if(value is JsonPrimitive && value.isString) {
        val text=normalized ?: value.content
        val shift=if(normalized==null) 0 else value.content.indexOf(text).coerceAtLeast(0)
        val separate=(rawSurrogates ?: original?.stringRawSurrogates(value).orEmpty()).map {it-shift}.toSet()
        val points=mutableListOf<Int>();var index=0
        while(index<text.length) {
            val width=if(text[index].isHighSurrogate() && index+1<text.length && text[index+1].isLowSurrogate() && index !in separate && index+1 !in separate) 2 else 1
            points+=if(width==2) text.codePointAt(index) else text[index].code;index+=width
        }
        val start=points.indexOfFirst {it in 0xd800..0xdfff}
        if(start>=0) {
            if(response) throw ManualCashInternalFailure()
            var end=start+1;while(end<points.size && points[end] in 0xd800..0xdfff) end++
            val location=if(end==start+1) "character ${pythonStringRepr(points[start].toChar().toString())} in position $start"
                else "characters in position $start-${end-1}"
            manualBusiness("'utf-8' codec can't encode $location: surrogates not allowed")
        }
        strictUtf8(text)
    }
}
private fun manualUpper(value:String)=buildString {value.codePoints().forEach {append(pythonUpperCase[it] ?: String(Character.toChars(it)))}}
private fun manualAlpha(value:String)=value.isNotEmpty() && value.codePoints().allMatch {code ->
    (pythonLetterRanges.indices step 2).any {index ->code in pythonLetterRanges[index]..pythonLetterRanges[index+1]}
}
private fun manualAmount(value:JsonElement?,name:String="value",original:DecodedPythonJson?=null):CashAmount {
    if(value!=null && original?.stringRawSurrogates(value).orEmpty().isNotEmpty()) manualBusiness("$name must be decimal-compatible")
    return try {exactCashAmount(pythonString(value ?: JsonNull),name)} catch(failure:IllegalArgumentException) {manualBusiness(failure.message!!)}
}
internal fun manualInstantText(value:Instant):String {
    val time=value.atOffset(ZoneOffset.UTC)
    return DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").format(time)+(if(time.nano/1000==0) "" else "."+(time.nano/1000).toString().padStart(6,'0'))+"+00:00"
}

internal class ManualCashRecordRepository(private val context:CashSqlContext) {
    private data class Account(val id:Long,val name:String,val type:String,val currencies:JsonElement)
    private data class Part(val account:Account,var amount:CashAmount,val ordinal:Int,val label:String,val labelSource:JsonElement?)
    private fun account(name:String?=null,id:BigInteger?=null,source:JsonElement?=null,original:DecodedPythonJson?=null):Account? {
        context.requireActive()
        val longId=id?.takeIf {it>=Long.MIN_VALUE.toBigInteger() && it<=Long.MAX_VALUE.toBigInteger()}?.toLong()
        if(id!=null && longId==null && context.sqlite) throw ManualCashInternalFailure()
        if(id==null) manualOriginalUtf8(source,original,normalized=name)
        val predicate = if (id == null) ACCOUNTS.NAME.eq(name) else if (longId != null) ACCOUNTS.ID.eq(longId)
            else ACCOUNTS.ID.eq(DSL.`val`(id.toString()).cast(SQLDataType.BIGINT))
        return context.sql.select(ACCOUNTS.ID, ACCOUNTS.NAME, ACCOUNTS.TYPE, ACCOUNTS.CURRENCIES).from(ACCOUNTS)
            .where(ACCOUNTS.WORKSPACE_ID.eq(context.workspace)).and(predicate).fetchOne()?.let {
                Account(it[ACCOUNTS.ID]!!, it[ACCOUNTS.NAME]!!, it[ACCOUNTS.TYPE]!!, it[ACCOUNTS.CURRENCIES]!!)
            }
    }
    private fun integer(value:JsonElement,original:DecodedPythonJson?):BigInteger=try {pythonIntegerValue(value,original?.stringRepresentation(value))}
        catch(failure:CategoryFailure) {manualBusiness(failure.code)}
    private fun validateCounterparty(value:String,attrs:JsonArray) {
        val normalized=attrs.map {pythonString(it)}
        if(normalized !in listOf(emptyList(),listOf("tail"),listOf("full"),listOf("masked"),listOf("masked","reconstructed"))) manualBusiness("counterparty_account_attrs has an invalid combination")
        val account=manualTrim(value);val points=account.codePoints().toArray()
        val digits=points.isNotEmpty() && points.all {it in pythonNondecimalDigits || pythonIntegerDigitStarts.any {start ->it in start until start+10}}
        if(account.isEmpty()) {if(normalized.isNotEmpty()) manualBusiness("counterparty_account_attrs must be empty when account is empty");return}
        if(normalized.isEmpty()) manualBusiness("counterparty_account_attrs is required for a non-empty account")
        when(normalized) {
            listOf("tail") ->if(points.size!=4 || points.any {pythonIntegerDigitStarts.none {start ->it in start until start+10}}) manualBusiness("counterparty_account_attrs tail requires exactly four digits")
            listOf("full") ->if('*' in account || '＊' in account || digits && points.size<=4) manualBusiness("counterparty_account_attrs full conflicts with account value")
            listOf("masked") ->if('*' !in account && '＊' !in account) manualBusiness("counterparty_account_attrs masked requires an explicit mask")
            listOf("masked","reconstructed") -> {
                if(!digits || points.size<=4) manualBusiness("counterparty_account_attrs reconstructed requires a full numeric value")
                manualBusiness("counterparty_account_attrs reconstructed requires verified source reconstruction from the same source row")
            }
        }
    }
    fun create(input:JsonObject,original:DecodedPythonJson?=null):JsonObject {
        context.requireActive()
        val rawParts=input["components"].takeUnless {it==JsonNull} as? JsonArray
        var name=manualTrim(manualText(input["account_name"]))
        var nameSource=input["account_name"]
        if(name.isEmpty()) {
            val first=rawParts?.firstOrNull()?.jsonObject
            name=manualTrim(manualText(first?.get("account_name")))
            nameSource=first?.get("account_name")
            val id=first?.get("account_id")
            if(name.isEmpty() && id!=null && id!=JsonNull && id!=JsonPrimitive("")) {name=account(id=integer(id,original))?.name.orEmpty();nameSource=null}
        }
        val parent=account(name=name,source=nameSource,original=original)?.takeIf {it.type in setOf("cash","loan","lend")} ?: manualBusiness("请选择现金、贷款或借款账户")
        val suppliedCurrency=manualText(input["currency"]);val currency=manualUpper(manualTrim(suppliedCurrency))
        if(original?.stringRawSurrogates(input["currency"] ?: JsonNull).orEmpty().isNotEmpty() || currency.codePointCount(0,currency.length)!=3 || !manualAlpha(currency)) manualBusiness("请先选择币种")
        val supported=parent.currencies.jsonArray.filter(::pythonTruthy).map {manualUpper(pythonString(it))}.toSet()
        if(currency !in supported) manualBusiness("账户 ${parent.name} 暂不支持 $currency，请更新账户配置后重试")
        val amount=manualAmount(input["amount"] ?: JsonPrimitive("0"),"amount",original)
        val type=manualText(input["record_type"]).ifEmpty {"other"}
        var subtype=manualText(input["record_subtype"])
        if(subtype.isEmpty() || subtype=="not_applicable" && type in setOf("transfer_in","transfer_out","fx_in","fx_out","repayment","withdrawal_in","withdrawal_out")) subtype=defaultCashRecordSubtype(type)
        if(type !in cashRecordTypes) {manualOriginalUtf8(input["record_type"],original,response=true);manualBusiness("unknown record_type: $type")}
        if(subtype !in cashRecordSubtypes) {manualOriginalUtf8(input["record_subtype"],original,response=true);manualBusiness("unknown record_subtype: $subtype")}
        if(!validCashRecordPair(type,subtype)) manualBusiness("record_subtype ${pythonStringRepr(subtype)} is invalid for record_type ${pythonStringRepr(type)}")
        val storedCurrency=manualUpper(suppliedCurrency)
        if(storedCurrency.codePointCount(0,storedCurrency.length)!=3 || !manualAlpha(storedCurrency)) manualBusiness("currency must be a three-letter code")
        val counterpartyAccount=manualText(input["counterparty_account"])
        val attrs=(input["counterparty_account_attrs"] ?: JsonArray(emptyList())) as? JsonArray
            ?: manualBusiness("counterparty_account_attrs must be a JSON array")
        validateCounterparty(counterpartyAccount,attrs)
        val dateValue=input["occurred_at"] ?: JsonPrimitive("")
        val occurred=try {manualCashTimestamp(manualText(dateValue),original?.stringRawSurrogates(dateValue).orEmpty())}
            catch(failure:IllegalArgumentException) {manualOriginalUtf8(dateValue,original,response=true);manualBusiness(failure.message!!)}
        val note=manualText(input["note"]);val category=manualTrim(manualText(input["category_id"])).ifEmpty {null}
        for(key in listOf("counterparty","counterparty_account","note","category_id")) manualOriginalUtf8(input[key],original,normalized=if(key=="category_id") category.orEmpty() else null)
        val protection=buildJsonObject {for(key in listOf("note","category_id")) if(key in input) put(key,buildJsonObject {put("value",if(key=="note") JsonPrimitive(note) else category?.let(::JsonPrimitive) ?: JsonNull)})}
        val id=context.sql.insertInto(CASH_TRANSACTIONS)
            .set(CASH_TRANSACTIONS.WORKSPACE_ID, context.workspace)
            .set(CASH_TRANSACTIONS.ACCOUNT_ID, parent.id)
            .set(CASH_TRANSACTIONS.STATE, "posted")
            .set(CASH_TRANSACTIONS.RECORD_ORIGIN, "manual")
            .set(CASH_TRANSACTIONS.ROW_REVISION, 1L)
            .set(CASH_TRANSACTIONS.CASH_GRANULARITY, "atomic")
            .setNull(CASH_TRANSACTIONS.SOURCE_TYPE)
            .set(CASH_TRANSACTIONS.RECORD_ID, "")
            .set(CASH_TRANSACTIONS.SOURCE_PAYLOAD, JsonNull)
            .set(CASH_TRANSACTIONS.RELATION_METADATA, JsonNull)
            .setNull(CASH_TRANSACTIONS.SOURCE_FINGERPRINT)
            .set(CASH_TRANSACTIONS.MANUAL_OVERRIDES, JsonObject(emptyMap()))
            .set(CASH_TRANSACTIONS.OCCURRED_AT, occurred)
            .set(CASH_TRANSACTIONS.AMOUNT, CashStoredDecimal(amount.toString()))
            .set(CASH_TRANSACTIONS.CURRENCY, storedCurrency)
            .set(CASH_TRANSACTIONS.COUNTERPARTY, manualText(input["counterparty"]))
            .set(CASH_TRANSACTIONS.COUNTERPARTY_ACCOUNT, counterpartyAccount)
            .set(CASH_TRANSACTIONS.COUNTERPARTY_ACCOUNT_ATTRS, attrs)
            .set(CASH_TRANSACTIONS.NOTE, note)
            .set(CASH_TRANSACTIONS.CATEGORY_ID, category)
            .set(CASH_TRANSACTIONS.RECORD_TYPE, type)
            .set(CASH_TRANSACTIONS.RECORD_SUBTYPE, subtype)
            .set(CASH_TRANSACTIONS.CREATED_AT, context.now())
            .setNull(CASH_TRANSACTIONS.DELETED_AT)
            .set(CASH_TRANSACTIONS.DELETED_BY, "")
            .set(CASH_TRANSACTIONS.DELETE_REASON, "")
            .returningResult(CASH_TRANSACTIONS.ID).fetchSingle().value1()!!
        val parts=(rawParts ?: JsonArray(listOf(buildJsonObject {put("account_id",parent.id);put("amount",input["amount"] ?: JsonPrimitive("0"))}))).mapIndexed {ordinal,raw ->
            val item=raw.jsonObject;val id=item["account_id"];val partName=manualTrim(manualText(item["account_name"]))
            val selected=when {
                id!=null && id!=JsonNull && id!=JsonPrimitive("") ->account(id=integer(id,original))
                partName.isNotEmpty() ->account(name=partName,source=item["account_name"],original=original)
                (rawParts?.size ?: 1)==1 ->parent
                else ->null
            }?.takeIf {it.type in setOf("cash","loan","lend")} ?: manualBusiness("组成项账户不存在或不是现金账户")
            val partCurrency=item["currency"]
            if(partCurrency!=null && partCurrency!=JsonNull && partCurrency!=JsonPrimitive("") &&
                (original?.stringRawSurrogates(partCurrency).orEmpty().isNotEmpty() || manualUpper(pythonString(partCurrency))!=storedCurrency)) manualBusiness("组成项币种必须与父流水一致")
            val partAmount=try {manualAmount(item["amount"],original=original)} catch(_:ManualCashBusinessFailure) {manualBusiness("import_component_amount_invalid")}
            Part(selected,partAmount,ordinal,manualText(item["label"]),item["label"])
        }
        if(parts.isEmpty()) manualBusiness("cash transaction requires at least one component")
        val direction=amount.arithmeticValue().signum()
        if(direction<0 && parts.all {it.amount.arithmeticValue().signum()>=0}) for(part in parts) {
            val text=part.amount.toString();part.amount=CashAmount.parse(if(text.startsWith('-')) text.drop(1) else "-$text")
        }
        if(direction>=0 && parts.any {it.amount.arithmeticValue().signum()<0}) manualBusiness("组成项金额方向必须与父流水一致")
        val arithmetic=MathContext(80,RoundingMode.HALF_EVEN)
        if(parts.fold(java.math.BigDecimal.ZERO) {total,part ->total.add(part.amount.arithmeticValue(),arithmetic)}.compareTo(amount.arithmeticValue())!=0) manualBusiness("组成项金额之和必须等于父流水金额")
        if(parts.size!=1 || parts.single().account.id!=parent.id) check(context.sql.update(CASH_TRANSACTIONS)
            .set(CASH_TRANSACTIONS.ACCOUNT_ID, if(parts.size==1) parts.single().account.id else null)
            .set(CASH_TRANSACTIONS.CASH_GRANULARITY, if(parts.size==1) "atomic" else "aggregate")
            .set(CASH_TRANSACTIONS.ROW_REVISION, CASH_TRANSACTIONS.ROW_REVISION.plus(1L))
            .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(context.workspace)).and(CASH_TRANSACTIONS.ID.eq(id)).execute()==1)
        for(part in parts) manualOriginalUtf8(part.labelSource,original)
        for(part in parts) {
            context.sql.insertInto(CASH_TRANSACTION_COMPONENTS)
            .set(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID, context.workspace)
            .set(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID, id)
            .set(CASH_TRANSACTION_COMPONENTS.ACCOUNT_ID, part.account.id)
            .set(CASH_TRANSACTION_COMPONENTS.AMOUNT, CashStoredDecimal(part.amount.toString()))
            .set(CASH_TRANSACTION_COMPONENTS.CURRENCY, storedCurrency)
            .set(CASH_TRANSACTION_COMPONENTS.ORDINAL, part.ordinal.toLong())
            .set(CASH_TRANSACTION_COMPONENTS.LABEL, part.label)
            .set(CASH_TRANSACTION_COMPONENTS.SOURCE_KEY, "")
            .set(CASH_TRANSACTION_COMPONENTS.METADATA_JSON, JsonObject(emptyMap()))
            .set(CASH_TRANSACTION_COMPONENTS.CREATED_AT, context.now())
            .returningResult(CASH_TRANSACTION_COMPONENTS.ID).fetchSingle().value1()!!
        }
        if(protection.isNotEmpty()) check(context.sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.MANUAL_OVERRIDES, protection)
            .set(CASH_TRANSACTIONS.ROW_REVISION, CASH_TRANSACTIONS.ROW_REVISION.plus(1L))
            .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(context.workspace)).and(CASH_TRANSACTIONS.ID.eq(id)).execute()==1)
        val p = CASH_TRANSACTION_COMPONENTS; val a = ACCOUNTS
        val components = context.sql.select(p.ID, p.ACCOUNT_ID, a.NAME, a.TYPE, p.AMOUNT, p.CURRENCY, p.ORDINAL, p.LABEL, p.SOURCE_KEY, p.METADATA_JSON)
            .from(p).join(a).on(a.WORKSPACE_ID.eq(p.WORKSPACE_ID).and(a.ID.eq(p.ACCOUNT_ID)))
            .where(p.WORKSPACE_ID.eq(context.workspace)).and(p.CASH_TRANSACTION_ID.eq(id)).orderBy(p.ORDINAL, p.ID).fetch().map { r -> buildJsonObject {
                put("id",r[p.ID]!!);put("cash_transaction_id",id);put("account_id",r[p.ACCOUNT_ID]!!);put("account_name",r[a.NAME]!!);put("account_type",r[a.TYPE]!!)
                put("amount",fixedCashDecimal(r[p.AMOUNT]!!));put("currency",r[p.CURRENCY]!!);put("ordinal",r[p.ORDINAL]!!);put("label",r[p.LABEL]!!);put("source_key",r[p.SOURCE_KEY]!!);put("metadata",r[p.METADATA_JSON]!!)
            } }
        val record=buildJsonObject {
            put("id",id);put("occurred_at",manualInstantText(occurred));put("amount",fixedCashDecimal(CashStoredDecimal(amount.toString())));put("currency",storedCurrency)
            put("counterparty",manualText(input["counterparty"]));put("counterparty_account",counterpartyAccount);put("note",note);put("category_id",category?.let(::JsonPrimitive) ?: JsonNull)
            put("record_type",type);put("record_subtype",subtype);put("account_name",if(parts.size==1) parts.single().account.name else "");put("account_type",if(parts.size==1) parts.single().account.type else "")
            put("source_type","");put("cash_granularity",if(parts.size==1) "atomic" else "aggregate");put("components",JsonArray(components))
        }
        CashBalanceService(context).applyChanges(listOf(null to JsonObject(record+mapOf("record_origin" to JsonPrimitive("manual")))))
        CashProjectionService.maintainStandaloneFactIfReadyInSession(context,readCashFactRows(context,setOf(id)).single())
        val categoryWire=category?.let {key ->
            val leaf = CASH_CATEGORIES.`as`("leaf"); val ancestor = CASH_CATEGORIES.`as`("ancestor")
            val path = context.sql.select(ancestor.ID, ancestor.NAME).from(leaf).join(ancestor)
                .on(ancestor.WORKSPACE_ID.eq(leaf.WORKSPACE_ID).and(leaf.CATEGORY_PATH.like(ancestor.CATEGORY_PATH.concat("%"))))
                .where(leaf.WORKSPACE_ID.eq(context.workspace)).and(leaf.ID.eq(key)).orderBy(ancestor.DEPTH)
                .fetch().map { r -> buildJsonObject {put("id",r[ancestor.ID]!!);put("name",r[ancestor.NAME]!!)} }
            if(path.isEmpty()) JsonNull else buildJsonObject {put("id",key);put("name",path.last().getValue("name"));put("path",JsonArray(path))}
        }
        return buildJsonObject {put("record",if(category!=null) JsonObject(record+("category" to (categoryWire ?: JsonNull))) else record);put("relations",JsonArray(emptyList()));put("options",Json.encodeToJsonElement(buildLedgerOptions()))}
    }
}

class ManualCashService(private val database:Database,private val now:()->Instant=Instant::now,
    private val beforeWorkspaceLock:()->Unit={},private val beforeReturn:()->Unit={},private val beforeWrite:()->Unit={}) {
    private val reads=ReadServices(database,now)
    internal fun create(token:String?,value:JsonElement,original:DecodedPythonJson?=null,requestWorkspace:String?=null):JsonObject {
        val input=manualCashApplicationInput(value)
        beforeWrite()
        return database.write {c ->
            val workspace=requestWorkspace ?: reads.session(c,token).workspaceId ?: throw AccessFailure(true)
            beforeWorkspaceLock()
            if (!c.lockWorkspace(database.sqlite, workspace)) throw AccessFailure(true)
            reads.lockedContext(c,token,workspace,write=true)
            val result=ManualCashRecordRepository(CashSqlContext(c,workspace,database.sqlite,now)).create(input,original)
            beforeReturn();reads.lockedContext(c,token,workspace,write=true)
            result
        }
    }
}
