package com.finance.tracker.backend

import com.finance.tracker.business.domain.DecimalRepresentation
import com.finance.tracker.business.domain.LedgerDecimal
import com.finance.tracker.business.domain.cashComponentError
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.IdentityHashMap
import kotlinx.serialization.json.*

internal class InvalidCashValue : RuntimeException("invalid_cash_value")
internal class CashPolicyError(val code: String,val fields: List<String>) : RuntimeException(code)
internal class CashAmount private constructor(private val decimal: LedgerDecimal) {
    val representation: DecimalRepresentation get()=decimal.representation
    private fun signature(): String {
        if(decimal.sign==0) return "0"
        val coefficient=representation.coefficient.trimEnd('0')
        val exponent=representation.exponent+(representation.coefficient.length-coefficient.length)
        return "${decimal.sign}:$coefficient:$exponent"
    }
    fun numericText(): String {
        if(decimal.sign==0) return "0"
        val coefficient=representation.coefficient.trimEnd('0')
        return (if(decimal.sign<0) "-" else "")+coefficient+"E"+(representation.exponent+representation.coefficient.length-coefficient.length)
    }
    override fun equals(other: Any?)=other is CashAmount && signature()==other.signature()
    override fun hashCode()=signature().hashCode()
    override fun toString()=(if(representation.negative) "-" else "")+representation.coefficient+"E"+representation.exponent
    companion object {
        fun parse(text: String)=parseOrNull(text) ?: throw IllegalArgumentException("invalid_cash_amount")
        fun parseOrNull(text: String,onInvalid:(LedgerDecimal.InputError)->Unit = {})=LedgerDecimal.parseOrNull(text,onInvalid)?.let(::CashAmount)
    }
}
internal fun exactCashAmount(text:String,field:String="value"):CashAmount=CashAmount.parseOrNull(text) {reason ->
    throw IllegalArgumentException(when(reason) {
        LedgerDecimal.InputError.DECIMAL_COMPATIBLE ->"$field must be decimal-compatible"
        LedgerDecimal.InputError.FINITE ->"$field must be finite"
        LedgerDecimal.InputError.SCALE ->"$field exceeds NUMERIC(38,18): must have at most 18 decimal places"
        LedgerDecimal.InputError.RANGE ->"$field exceeds NUMERIC(38,18)"
    })
} ?: error("decimal parser did not report rejection")
internal data class CashPolicyUpdate(val values: Map<String,Any?>,val typeChanged: Boolean,
    val protectedFields: List<String>,val normalizedValues: Map<String,Any?>)

internal object CashRecordPolicy {
    private val mutable=mutableCashFields
    private val financial=financialCashFields
    private val identity=setOf("record_id","source_type","source_scope_key","source_account_key","record_origin","manual_transfer_id")
    private val recognized=mutable+financial+identity
    private val textFields=setOf("counterparty","counterparty_account","note")
    private val types=cashRecordTypes
    private val subtypes=cashRecordSubtypes
    private fun invalid(): Nothing=throw InvalidCashValue()
    private fun integer(value: Any?): BigInteger?=when(value) {
        is BigInteger ->value
        is Byte,is Short,is Int,is Long ->BigInteger.valueOf((value as Number).toLong())
        else ->null
    }
    private fun amount(value: Any?): CashAmount?=when(value) {
        null ->null
        is CashAmount ->value
        is String ->CashAmount.parseOrNull(value) ?: invalid()
        is BigDecimal ->CashAmount.parseOrNull(value.toString()) ?: invalid()
        else ->integer(value)?.let {CashAmount.parseOrNull(it.toString())} ?: invalid()
    }
    private fun reference(value: Any?): String {
        if(value is String) return value.takeIf {it.isNotEmpty()} ?: invalid()
        return integer(value)?.takeIf {it.signum()>0}?.toString() ?: invalid()
    }
    private fun currency(value: Any?): String = (value as? String)?.takeIf {it.length==3 && it.all {c ->c in 'A'..'Z'}} ?: invalid()
    private fun clone(value: Any?,memo: IdentityHashMap<Any,Any> = IdentityHashMap()): Any? {
        if(value==null || value is String || value is Boolean || value is Byte || value is Short || value is Int || value is Long || value is BigInteger || value is BigDecimal || value is Float || value is Double || value is CashAmount || value is Instant || value is java.time.OffsetDateTime || value is java.time.ZonedDateTime) return value
        memo[value]?.let {return it}
        return when(value) {
            is Map<*,*> ->linkedMapOf<Any?,Any?>().also {copy ->memo[value]=copy;value.forEach {(key,item) ->copy[clone(key,memo)]=clone(item,memo)}}
            is List<*> ->arrayListOf<Any?>().also {copy ->memo[value]=copy;value.forEach {copy.add(clone(it,memo))}}
            is Set<*> ->linkedSetOf<Any?>().also {copy ->memo[value]=copy;value.forEach {copy.add(clone(it,memo))}}
            else ->invalid()
        }
    }
    internal fun copyValue(value: Any?): Any? = clone(value)
    private fun jsonValue(value: Any?,depth: Int=0) {
        if(depth>64) invalid()
        when(value) {
            null,is String,is Boolean,is BigInteger,is Byte,is Short,is Int,is Long ->Unit
            is Double ->if(!value.isFinite()) invalid()
            is Float ->if(!value.isFinite()) invalid()
            is Map<*,*> ->{if(value.keys.any {it !is String}) invalid();value.values.forEach {jsonValue(it,depth+1)}}
            is List<*> ->value.forEach {jsonValue(it,depth+1)}
            else ->invalid()
        }
    }
    fun componentValues(value: Any?): List<MutableMap<String,Any?>> {
        if(value !is List<*>) invalid()
        return value.map {raw ->
            if(raw !is Map<*,*> || raw.keys.any {it !is String}) invalid()
            @Suppress("UNCHECKED_CAST") val item=clone(raw) as MutableMap<String,Any?>
            for(field in listOf("component_id","account_id")) if(item[field]!=null) item[field]=reference(item[field])
            if(item["amount"]!=null) item["amount"]=amount(item["amount"])
            if("ordinal" in item) item["ordinal"]=integer(item["ordinal"])?.takeIf {it.signum()>=0 && it<=BigInteger.valueOf(Int.MAX_VALUE.toLong())}?.toInt() ?: invalid()
            if(item["currency"]!=null) item["currency"]=currency(item["currency"])
            item
        }
    }
    fun normalize(field: String,value: Any?): Any?=when(field) {
        in textFields ->value as? String ?: invalid()
        "amount" ->amount(value)
        "occurred_at" ->if(value==null) null else pythonCashTimestamp(value)
        "account_id" ->if(value==null) null else reference(value)
        "cash_granularity" ->(value as? String)?.takeIf {it in setOf("atomic","aggregate")} ?: invalid()
        "currency" ->if(value==null) null else currency(value)
        "components" ->componentValues(value)
        "counterparty_account_attrs" ->if(value is List<*> && value.all {it is String}) value.toMutableList() else invalid()
        "source_payload" ->{if(value!=null && value !is Map<*,*>) invalid();jsonValue(value);clone(value)}
        "record_type","record_subtype","category_id" ->if(value==null || value is String) value else invalid()
        else ->clone(value)
    }
    private fun sorted(values: Collection<String>)=values.sortedWith {left,right ->
        val a=left.codePoints().toArray();val b=right.codePoints().toArray()
        var result=0
        for(index in 0 until minOf(a.size,b.size)) if(a[index]!=b[index]) {result=a[index].compareTo(b[index]);break}
        if(result==0) a.size.compareTo(b.size) else result
    }
    private fun numeric(value: Any?): BigDecimal?=when(value) {
        is CashAmount ->BigDecimal(value.numericText())
        is BigDecimal ->value
        is BigInteger ->BigDecimal(value)
        is Byte,is Short,is Int,is Long ->BigDecimal.valueOf((value as Number).toLong())
        is Boolean ->if(value) BigDecimal.ONE else BigDecimal.ZERO
        is Double ->if(value.isFinite()) BigDecimal(value) else null
        is Float ->if(value.isFinite()) BigDecimal(value.toDouble()) else null
        else ->null
    }
    private fun equal(left: Any?,right: Any?,withinContainer: Boolean=false): Boolean {
        if(left is Double && left.isNaN() || right is Double && right.isNaN() || left is Float && left.isNaN() || right is Float && right.isNaN())
            return withinContainer && left===right
        if(left is CashAmount && right is CashAmount) return left==right
        val a=numeric(left);val b=numeric(right)
        if(a!=null && b!=null) return a.compareTo(b)==0
        if(left is Map<*,*> && right is Map<*,*>) {
            if(left.size!=right.size) return false
            val unmatched=right.entries.toMutableList()
            for(entry in left.entries) {
                val index=unmatched.indexOfFirst {equal(entry.key,it.key,true)}
                if(index<0 || !equal(entry.value,unmatched.removeAt(index).value,true)) return false
            }
            return true
        }
        if(left is List<*> && right is List<*>) return left.size==right.size && left.indices.all {equal(left[it],right[it],true)}
        if(left is Set<*> && right is Set<*>) {
            if(left.size!=right.size) return false
            val unmatched=right.toMutableList()
            for(item in left) {
                val index=unmatched.indexOfFirst {equal(item,it,true)}
                if(index<0) return false
                unmatched.removeAt(index)
            }
            return true
        }
        return left==right
    }
    fun valuesEqual(left: Any?,right: Any?)=equal(left,right)
    private fun validPair(type: Any?,subtype: Any?)=validCashRecordPair(type,subtype)
    fun prepare(current: Map<*,*>,patch: Any?,protected: Set<String> = emptySet()): CashPolicyUpdate {
        if(patch !is Map<*,*> || patch.keys.any {it !is String}) throw CashPolicyError("invalid_field",emptyList())
        val state=if("state" in current) current["state"] else "posted"
        if(state !in setOf("draft","posted")) throw CashPolicyError("invalid_state",listOf("state"))
        val keys=patch.keys.filterIsInstance<String>()
        val unknown=sorted((keys-recognized).toSet())
        if(unknown.isNotEmpty()) throw CashPolicyError("field_not_editable",unknown)
        val normalized=linkedMapOf<String,Any?>()
        for(field in keys) try {normalized[field]=normalize(field,patch[field])}
            catch (_: InvalidCashValue) {throw CashPolicyError("invalid_field",listOf(field))}
        val fixed=identity+(if(state=="posted" && current["record_origin"] !in setOf("manual","manual_transfer")) financial+setOf("record_type","record_subtype") else emptySet())
        val conflicts=sorted((fixed intersect keys.toSet()).filter {field ->
            try {!equal(normalize(field,current[field]),normalized[field])} catch (_: InvalidCashValue) {true}
        })
        if(conflicts.isNotEmpty()) throw CashPolicyError("fixed_field_conflict",conflicts)
        val protectedFields=sorted(normalized.keys intersect protected intersect mutable)
        val effective=normalized.filterKeys {it !in fixed && it !in protected}
        val type=if("record_type" in effective) effective["record_type"] else current["record_type"]
        val subtype=if("record_subtype" in effective) effective["record_subtype"] else current["record_subtype"]
        if(state=="posted" || type!=null && subtype!=null) {
            if(!validPair(type,subtype)) throw CashPolicyError("invalid_record_type",listOf("record_type","record_subtype"))
        } else {
            if(type!=null && type !in types) throw CashPolicyError("invalid_record_type",listOf("record_type"))
            if(subtype!=null && subtype !in subtypes) throw CashPolicyError("invalid_record_type",listOf("record_subtype"))
        }
        val changed=effective.filter {(field,value) ->try {!equal(normalize(field,current[field]),value)} catch (_: InvalidCashValue) {true}}
        return CashPolicyUpdate(changed,changed.keys.any {it in setOf("record_type","record_subtype")},protectedFields,normalized)
    }
    fun sourceNote(payload: Map<*,*>?): String {
        for(field in listOf("备注","備註","注释","描述","商品说明","商品","交易描述","附言","交易地点/附言","摘要","remark","remarks","comment","comments","description","note")) {
            val value=payload?.get(field)
            if(value is String && value.trim {it.code in pythonWhitespace}.isNotEmpty()) return value
        }
        return ""
    }
    private fun known(values: Map<String,Any?>) {
        fun scalar(value: Any?): JsonElement=when(value) {
            null ->JsonNull
            is CashAmount ->JsonPrimitive(value.toString())
            is String ->JsonPrimitive(value)
            is Int ->JsonPrimitive(value)
            else ->error("unexpected_normalized_cash_value")
        }
        val record=buildJsonObject {
            for(field in listOf("amount","currency","account_id","cash_granularity")) put(field,scalar(values[field]))
            @Suppress("UNCHECKED_CAST") val parts=values["components"] as List<Map<String,Any?>>
            put("components",JsonArray(parts.map {part ->buildJsonObject {
                for(field in listOf("component_id","account_id","amount","currency","ordinal")) if(field in part) put(field,scalar(part[field]))
            }}))
        }
        cashComponentError(record)?.let {throw CashPolicyError(it.code,it.fields)}
    }
    fun validateConsistency(record: Map<*,*>) {
        val values=linkedMapOf<String,Any?>()
        for(field in listOf("amount","currency","account_id","cash_granularity","components")) try {
            values[field]=normalize(field,if(field in record) record[field] else when(field) {"cash_granularity" ->"atomic";"components" ->emptyList<Any>();else ->null})
        } catch (_: InvalidCashValue) {throw CashPolicyError("invalid_field",listOf(field))}
        known(values)
    }
    fun validateForPosting(record: Map<*,*>) {
        val required=mutableSetOf("amount","currency","occurred_at","record_type","record_subtype")
        if((if("cash_granularity" in record) record["cash_granularity"] else "atomic")=="atomic") required.add("account_id")
        val missing=sorted(required.filter {record[it]==null || record[it]==""})
        if(missing.isNotEmpty()) throw CashPolicyError("incomplete_record",missing)
        if(record["cash_granularity"]=="aggregate" && record["account_id"]!=null) throw CashPolicyError("component_account_mismatch",listOf("account_id"))
        val normalized=linkedMapOf<String,Any?>()
        for(field in sorted(required+setOf("components","cash_granularity"))) try {
            normalized[field]=normalize(field,if(field=="cash_granularity" && field !in record) "atomic" else record[field])
        } catch (_: InvalidCashValue) {throw CashPolicyError("invalid_field",listOf(field))}
        if(!validPair(normalized["record_type"],normalized["record_subtype"])) throw CashPolicyError("invalid_record_type",listOf("record_type","record_subtype"))
        @Suppress("UNCHECKED_CAST") val parts=normalized.getValue("components") as List<Map<String,Any?>>
        if(parts.isEmpty() || parts.any {part ->listOf("account_id","amount","currency","ordinal").any {part[it]==null}}) throw CashPolicyError("incomplete_record",listOf("components"))
        known(normalized)
    }
}
