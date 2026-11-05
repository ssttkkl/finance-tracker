package com.finance.tracker.backend

import java.math.MathContext
import java.math.RoundingMode
import kotlinx.serialization.json.*

internal data class CashTransferAccount(val name:String,val type:String)
internal fun CashAmount.fixedTransferText()=fixedCashDecimal(CashStoredDecimal(toString()))
internal fun CashAmount.pythonTransferText():String {
    val (negative,digits,exponent)=representation
    val adjusted=exponent+digits.length-1
    val body=if(exponent>0 || adjusted< -6) digits.first()+(if(digits.length==1) "" else "."+digits.drop(1))+"E"+(if(adjusted>=0) "+" else "")+adjusted
        else if(exponent==0L) digits else {
            val position=digits.length+exponent.toInt()
            if(position>0) digits.take(position)+"."+digits.drop(position) else "0."+"0".repeat(-position)+digits
        }
    return (if(negative) "-" else "")+body
}
internal fun pythonCashLower(value:String):String {
    fun member(code:Int,ranges:IntArray):Boolean {
        var low=0;var high=ranges.size/2-1
        while(low<=high) {val middle=(low+high)/2;when {code<ranges[middle*2] ->high=middle-1;code>ranges[middle*2+1] ->low=middle+1;else ->return true}}
        return false
    }
    val points=value.codePoints().toArray()
    return buildString {for((index,point) in points.withIndex()) {
        if(point==0x03a3) {
            var before=index-1;while(before>=0 && member(points[before],pythonCaseIgnorableRanges)) before--
            var after=index+1;while(after<points.size && member(points[after],pythonCaseIgnorableRanges)) after++
            append(if(before>=0 && member(points[before],pythonCasedRanges) && (after==points.size || !member(points[after],pythonCasedRanges))) 'ς' else 'σ')
        } else append(pythonLowerCase[point] ?: String(Character.toChars(point)))
    }}
}
internal fun cashTransferEvent(date:String,amount:CashAmount,account:CashTransferAccount,counterpart:CashTransferAccount,
    currency:String,counterpartCurrency:String,outgoing:Boolean,note:String):JsonObject {
    if(account.type in setOf("security","crypto")) return buildJsonObject {
        put("occurred_at",date);put("record_type","funding");put("record_subtype","subaccount")
        put("from_ticker",if(outgoing) pythonCashLower(currency) else "");put("to_ticker",if(outgoing) "" else pythonCashLower(currency))
        put("from_amount",if(outgoing) amount.pythonTransferText() else "0");put("to_amount",if(outgoing) "0" else amount.pythonTransferText())
        put("price","1");put("commission","0");put("commission_asset","");put("currency",currency);put("account_name",account.name)
        put("note",note.ifEmpty {"transfer ${if(outgoing) "to" else "from"}:${counterpart.name}"})
    }
    val counterpartIsCash=counterpart.type !in setOf("security","crypto")
    val type=when {
        currency!=counterpartCurrency && counterpartIsCash ->if(outgoing) "fx_out" else "fx_in"
        "loan" in setOf(account.type,counterpart.type) && counterpartIsCash ->"repayment"
        outgoing ->"transfer_out"
        else ->"transfer_in"
    }
    return buildJsonObject {
        val text=amount.fixedTransferText()
        put("occurred_at",date);put("amount",if(!outgoing) text else if(text.startsWith('-')) text.drop(1) else "-$text");put("currency",currency)
        put("counterparty","");put("note",note.ifEmpty {
            if(currency!=counterpartCurrency) if(outgoing) "购汇至$counterpartCurrency" else "购汇自$counterpartCurrency"
            else if(outgoing) "转账至${counterpart.name}" else "来自${counterpart.name}"
        })
        put("record_type",type);put("record_subtype",defaultCashRecordSubtype(type));put("account_name",account.name)
        put("source_type","");put("record_origin","legacy")
    }
}

internal fun applyCashTransferSnapshot(value:JsonObject,account:CashTransferAccount,currency:String,amount:CashAmount):JsonObject {
    fun mapping(element:JsonElement?,message:String):JsonObject=element?.let {check(it is JsonObject) {message};it} ?: JsonObject(emptyMap())
    fun plus(raw:JsonElement?,current:String,projected:String):String {
        val old=exactCashAmount(raw?.let {if(it is JsonPrimitive && it.isString) it.content else pythonString(it)} ?: "0",current)
        val result=old.arithmeticValue().add(amount.arithmeticValue(),MathContext(80,RoundingMode.HALF_EVEN))
        val money=exactCashAmount(result.toString(),projected)
        return (if(result.signum()==0 && old.representation.negative && amount.representation.negative) "-" else "")+money.fixedTransferText()
    }
    val buckets=mapping(value["accounts"],"snapshot accounts must be a mapping")
    val security=account.type in setOf("security","crypto")
    val type=if(security) "security" else account.type.takeIf {it in setOf("cash","loan","lend")} ?: "cash"
    val accounts=mapping(buckets[type],"snapshot account type must be a mapping")
    val pocket=mapping(accounts[account.name],"snapshot account balance must be a currency mapping")
    val changed=if(!security) JsonObject(pocket+(currency to JsonPrimitive(plus(pocket[currency],"current balance","projected balance"))))
    else {
        val defaults=if(account.name !in accounts) buildJsonObject {put("currency",currency);put("positions",JsonObject(emptyMap()))} else pocket
        val positions=mapping(defaults["positions"],"snapshot positions must be a mapping")
        val ticker=pythonCashLower(currency)
        val position=if(ticker !in positions) buildJsonObject {put("shares","0");put("total_cost","0");put("cost_currency",currency)}
            else mapping(positions[ticker],"snapshot position must be a mapping")
        check("shares" in position) {"snapshot position requires shares"}
        val shares=plus(position.getValue("shares"),"current shares","projected shares")
        check("total_cost" in position) {"snapshot position requires total_cost"}
        val cost=plus(position.getValue("total_cost"),"current cost","projected cost")
        JsonObject(defaults+("positions" to JsonObject(positions+(ticker to JsonObject(position+mapOf("shares" to JsonPrimitive(shares),"total_cost" to JsonPrimitive(cost)))))))
    }
    return JsonObject(value+("accounts" to JsonObject(buckets+(type to JsonObject(accounts+(account.name to changed))))))
}
