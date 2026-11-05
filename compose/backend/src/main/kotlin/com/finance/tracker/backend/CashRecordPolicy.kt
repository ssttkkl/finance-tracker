package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.IdentityHashMap
import kotlinx.serialization.json.*

internal typealias InvalidCashValue = com.finance.tracker.business.domain.InvalidCashValue
internal typealias CashPolicyError = com.finance.tracker.business.domain.CashPolicyError
internal typealias CashAmount = com.finance.tracker.business.domain.CashAmount
internal fun exactCashAmount(text: String, field: String = "value"): CashAmount =
    com.finance.tracker.business.domain.exactCashAmount(text, field)
internal typealias CashPolicyUpdate = com.finance.tracker.business.domain.CashPolicyUpdate

internal object CashRecordPolicy : com.finance.tracker.business.domain.CashRecordValues {
    private val rules = com.finance.tracker.business.domain.CashRecordPolicyRules(::normalize, ::valuesEqual)
    private val textFields=setOf("counterparty","counterparty_account","note")
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
        if(value==null || value is String || value is Boolean || value is Byte || value is Short || value is Int || value is Long || value is BigInteger || value is BigDecimal || value is Float || value is Double || value is CashAmount || value is CashDecimalLiteral || value is Instant || value is java.time.OffsetDateTime || value is java.time.ZonedDateTime) return value
        memo[value]?.let {return it}
        return when(value) {
            is Map<*,*> ->linkedMapOf<Any?,Any?>().also {copy ->memo[value]=copy;value.forEach {(key,item) ->copy[clone(key,memo)]=clone(item,memo)}}
            is List<*> ->arrayListOf<Any?>().also {copy ->memo[value]=copy;value.forEach {copy.add(clone(it,memo))}}
            is Set<*> ->linkedSetOf<Any?>().also {copy ->memo[value]=copy;value.forEach {copy.add(clone(it,memo))}}
            else ->invalid()
        }
    }
    override fun copyValue(value: Any?): Any? = clone(value)
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
    override fun normalize(field: String,value: Any?): Any?=when(field) {
        in textFields ->value as? String ?: invalid()
        "amount" ->amount(value)
        "occurred_at" ->if(value==null) null else pythonCashTimestamp(value).also {
            if(!com.finance.tracker.business.domain.cashOccurrenceTimeInRange(it.epochSecond)) invalid()
        }
        "account_id" ->if(value==null) null else reference(value)
        "cash_granularity" ->(value as? String)?.takeIf {it in setOf("atomic","aggregate")} ?: invalid()
        "currency" ->if(value==null) null else currency(value)
        "components" ->componentValues(value)
        "counterparty_account_attrs" ->if(value is List<*> && value.all {it is String}) value.toMutableList() else invalid()
        "source_payload" ->{if(value!=null && value !is Map<*,*>) invalid();jsonValue(value);clone(value)}
        "record_type","record_subtype","category_id" ->if(value==null || value is String) value else invalid()
        else ->clone(value)
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
    override fun valuesEqual(left: Any?,right: Any?)=equal(left,right)
    override fun prepare(current: Map<*,*>, patch: Any?, protected: Set<String>): CashPolicyUpdate =
        rules.prepare(current, patch, protected)
    fun sourceNote(payload: Map<*,*>?): String = rules.sourceNote(payload)
    override fun validateConsistency(record: Map<*,*>) = rules.validateConsistency(record)
    fun validateForPosting(record: Map<*,*>) = rules.validateForPosting(record)

}
