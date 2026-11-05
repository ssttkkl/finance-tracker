package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*

internal class CashCanonicalFailure(val code:String):RuntimeException(code)
internal data class CashStoredDecimal(val text:String)
internal fun storedCashDecimal(value:String,sqlite:Boolean):CashStoredDecimal {
    if(sqlite) return CashStoredDecimal(value)
    val number=value.toBigDecimalOrNull() ?: return CashStoredDecimal(value)
    return CashStoredDecimal(if(number.signum()==0) "0" else number.stripTrailingZeros().toString())
}
internal fun storedCashInstant(value:String?,sqlite:Boolean):Instant?=value?.let {
    val text=it.replace(' ','T')
    if(sqlite) LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) else OffsetDateTime.parse(text).toInstant()
}
private fun canonicalCashDecimal(value:CashStoredDecimal):String {
    fun invalid():Nothing=throw CashCanonicalFailure("canonical.invalid_decimal")
    val amount=CashAmount.parseOrNull(value.text) ?: invalid();val r=amount.representation
    if(r.exponent !in -18..20) invalid()
    val scale=maxOf(0,-r.exponent);val integers=maxOf(0,r.coefficient.length+r.exponent)
    if(integers>20 || integers+scale>38) invalid()
    val decimal=BigDecimal(amount.toString())
    return (if(r.negative && decimal.signum()==0) "-" else "")+decimal.toPlainString()
}
internal fun canonicalCashJson(value:Any?,validateUtf8:Boolean=true):String {
    fun invalid():Nothing=throw CashCanonicalFailure("canonical.invalid_value")
    fun quoted(text:String):String=buildString {
        append('"')
        for(char in text) when(char) {
            '"' ->append("\\\"")
            '\\' ->append("\\\\")
            '\b' ->append("\\b")
            '\u000C' ->append("\\f")
            '\n' ->append("\\n")
            '\r' ->append("\\r")
            '\t' ->append("\\t")
            else ->if(char.code<32) append("\\u"+char.code.toString(16).padStart(4,'0')) else append(char)
        };append('"')
    }
    fun key(value:Any?):String=when(value) {
        null ->"None"
        is String ->value
        is Boolean ->if(value) "True" else "False"
        is Byte,is Short,is Int,is Long,is BigInteger ->value.toString()
        is Double ->pythonFloatText(value)
        is Float ->pythonFloatText(value.toDouble())
        else ->invalid()
    }
    fun timestamp(value:Instant):String {
        val time=try {pythonCashTimestamp(value).atOffset(ZoneOffset.UTC)} catch(_:InvalidCashValue) {throw CashCanonicalFailure("canonical.invalid_timestamp")}
        val base=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").format(time)
        return base+(if(time.nano==0) "" else "."+(time.nano/1000).toString().padStart(6,'0'))+"+00:00"
    }
    fun encode(value:Any?):String=when(value) {
        null,JsonNull ->"null"
        is String ->quoted(value)
        is Boolean ->value.toString()
        is Byte,is Short,is Int,is Long,is BigInteger ->value.toString()
        is CashStoredDecimal ->quoted(canonicalCashDecimal(value))
        is CashAmount ->quoted(canonicalCashDecimal(CashStoredDecimal(value.toString())))
        is BigDecimal ->quoted(canonicalCashDecimal(CashStoredDecimal(value.toString())))
        is Instant ->quoted(timestamp(value))
        is LocalDate ->if(value.year in 1..9999) quoted(value.toString()) else throw CashCanonicalFailure("canonical.invalid_timestamp")
        is JsonPrimitive ->when {
            value.isString ->quoted(value.content)
            value.booleanOrNull!=null ->value.content
            value.content.matches(Regex("-?(?:0|[1-9][0-9]*)")) ->BigInteger(value.content).toString()
            else ->invalid()
        }
        is Map<*,*> ->value.entries.associate {key(it.key) to it.value}.entries.sortedWith {a,b ->cashRelationStringOrder.compare(a.key,b.key)}.joinToString(",","{","}") {quoted(it.key)+":"+encode(it.value)}
        is List<*> ->value.joinToString(",","[","]",transform=::encode)
        else ->invalid()
    }
    return encode(value).also {if(validateUtf8) strictUtf8(it)}
}
