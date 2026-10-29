package com.finance.tracker.backend

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
    private val c get()=context.connection
    private fun account(name:String?=null,id:BigInteger?=null,source:JsonElement?=null,original:DecodedPythonJson?=null):Account? {
        context.requireActive()
        val longId=id?.takeIf {it>=Long.MIN_VALUE.toBigInteger() && it<=Long.MAX_VALUE.toBigInteger()}?.toLong()
        if(id!=null && longId==null && context.sqlite) throw ManualCashInternalFailure()
        if(id==null) manualOriginalUtf8(source,original,normalized=name)
        val predicate=if(id==null) "name=?" else if(longId!=null) "id=?" else "id=CAST(? AS BIGINT)"
        return c.prepareStatement("SELECT id,name,type,currencies FROM accounts WHERE workspace_id=? AND $predicate").use {
            it.setString(1,context.workspace);if(longId!=null) it.setLong(2,longId) else it.setString(2,id?.toString() ?: name)
            it.executeQuery().use {r ->if(!r.next()) null else Account(r.getLong(1),r.getString(2),r.getString(3),Json.parseToJsonElement(r.getString(4)))}
        }
    }
    private fun integer(value:JsonElement,original:DecodedPythonJson?):BigInteger=try {pythonIntegerValue(value,original?.stringRepresentation(value))}
        catch(failure:CategoryFailure) {manualBusiness(failure.code)}
    private fun insert(table:String,values:Map<String,Any?>):Long=context.insertCashRow(table,values)
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
        val id=insert("cash_transactions",linkedMapOf("workspace_id" to context.workspace,"account_id" to parent.id,
            "state" to "posted","record_origin" to "manual","row_revision" to 1L,"cash_granularity" to "atomic",
            "source_type" to null,"record_id" to "","source_payload" to JsonNull,"relation_metadata" to JsonNull,"source_fingerprint" to null,"manual_overrides" to JsonObject(emptyMap()),
            "occurred_at" to occurred,"amount" to amount,"currency" to storedCurrency,"counterparty" to manualText(input["counterparty"]),"counterparty_account" to counterpartyAccount,
            "counterparty_account_attrs" to attrs,"note" to note,"category_id" to category,"record_type" to type,"record_subtype" to subtype,"created_at" to context.now(),"deleted_at" to null,"deleted_by" to "","delete_reason" to ""))
        val parts=(rawParts ?: JsonArray(listOf(buildJsonObject {put("account_id",parent.id);put("amount",input["amount"] ?: JsonPrimitive("0"))}))).mapIndexed {ordinal,raw ->
            val item=raw.jsonObject;val id=item["account_id"];val partName=manualTrim(manualText(item["account_name"]))
            val selected=when {
                id!=null && id!=JsonNull && id!=JsonPrimitive("") ->account(id=integer(id,original))
                partName.isNotEmpty() ->account(name=partName,source=item["account_name"],original=original)
                (rawParts?.size ?: 1)==1 ->parent
                else ->null
            }?.takeIf {it.type in setOf("cash","loan","lend")} ?: manualBusiness("组成项账户不存在或不是现金账户")
            if(item["currency"]!=null && item["currency"]!=JsonNull && item["currency"]!=JsonPrimitive("") && manualUpper(pythonString(item.getValue("currency")))!=storedCurrency) manualBusiness("组成项币种必须与父流水一致")
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
        if(parts.size!=1 || parts.single().account.id!=parent.id) c.prepareStatement("UPDATE cash_transactions SET account_id=?,cash_granularity=?,row_revision=row_revision+1 WHERE workspace_id=? AND id=?").use {
            it.setObject(1,if(parts.size==1) parts.single().account.id else null);it.setString(2,if(parts.size==1) "atomic" else "aggregate");it.setString(3,context.workspace);it.setLong(4,id);check(it.executeUpdate()==1)
        }
        for(part in parts) manualOriginalUtf8(part.labelSource,original)
        for(part in parts) {
            insert("cash_transaction_components",linkedMapOf("workspace_id" to context.workspace,"cash_transaction_id" to id,"account_id" to part.account.id,"amount" to part.amount,
                "currency" to storedCurrency,"ordinal" to part.ordinal,"label" to part.label,"source_key" to "","metadata_json" to JsonObject(emptyMap()),"created_at" to context.now()))
        }
        if(protection.isNotEmpty()) c.prepareStatement("UPDATE cash_transactions SET manual_overrides="+(if(context.sqlite) "?" else "CAST(? AS json)")+",row_revision=row_revision+1 WHERE workspace_id=? AND id=?").use {
            it.setString(1,protection.toString());it.setString(2,context.workspace);it.setLong(3,id);check(it.executeUpdate()==1)
        }
        val components=c.prepareStatement("SELECT p.*,a.name AS account_name,a.type AS account_type FROM cash_transaction_components p JOIN accounts a ON a.workspace_id=p.workspace_id AND a.id=p.account_id WHERE p.workspace_id=? AND p.cash_transaction_id=? ORDER BY p.ordinal,p.id").use {
            it.setString(1,context.workspace);it.setLong(2,id);it.executeQuery().use {r ->buildList {while(r.next()) add(buildJsonObject {
                put("id",r.getLong("id"));put("cash_transaction_id",id);put("account_id",r.getLong("account_id"));put("account_name",r.getString("account_name"));put("account_type",r.getString("account_type"))
                put("amount",fixedCashDecimal(r.cashDecimal("amount",context.sqlite)!!));put("currency",r.getString("currency"));put("ordinal",r.getInt("ordinal"));put("label",r.getString("label"));put("source_key",r.getString("source_key"));put("metadata",r.cashJson("metadata_json")!!)
            })}}
        }
        val record=buildJsonObject {
            put("id",id);put("occurred_at",manualInstantText(occurred));put("amount",fixedCashDecimal(CashStoredDecimal(amount.toString())));put("currency",storedCurrency)
            put("counterparty",manualText(input["counterparty"]));put("counterparty_account",counterpartyAccount);put("note",note);put("category_id",category?.let(::JsonPrimitive) ?: JsonNull)
            put("record_type",type);put("record_subtype",subtype);put("account_name",if(parts.size==1) parts.single().account.name else "");put("account_type",if(parts.size==1) parts.single().account.type else "")
            put("source_type","");put("cash_granularity",if(parts.size==1) "atomic" else "aggregate");put("components",JsonArray(components))
        }
        CashBalanceService(context).applyChanges(listOf(null to JsonObject(record+mapOf("record_origin" to JsonPrimitive("manual")))))
        CashProjectionService.maintainStandaloneFactIfReadyInSession(context,readCashFactRows(context,setOf(id)).single())
        val categoryWire=category?.let {key ->
            val path=c.prepareStatement("SELECT a.id,a.name FROM cash_categories leaf JOIN cash_categories a ON a.workspace_id=leaf.workspace_id AND leaf.category_path LIKE a.category_path || '%' WHERE leaf.workspace_id=? AND leaf.id=? ORDER BY a.depth").use {
                it.setString(1,context.workspace);it.setString(2,key);it.executeQuery().use {r ->buildList {while(r.next()) add(buildJsonObject {put("id",r.getString(1));put("name",r.getString(2))})}}
            }
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
            c.prepareStatement("SELECT id FROM workspaces WHERE id=?"+if(database.sqlite) "" else " FOR UPDATE").use {it.setString(1,workspace);it.executeQuery().use {r ->if(!r.next()) throw AccessFailure(true)}}
            reads.lockedContext(c,token,workspace,write=true)
            val result=ManualCashRecordRepository(CashSqlContext(c,workspace,database.sqlite,now)).create(input,original)
            beforeReturn();reads.lockedContext(c,token,workspace,write=true)
            result
        }
    }
}
