package com.finance.tracker.business.domain

import kotlin.time.Instant

private const val CANDIDATE_DAY_PAD = 1

const val REFUND_CANDIDATE_DAYS=15

class CashCandidateTime(val seconds: Double,private val day:()->String) {
    val utcDay: String get()=day()
}
private data class IndexedRelationFact<A>(val fact:RelationFact<A>,val ts:Double,val day:String,
    val amountKey:String,val currency:String,val sign:Int,val group:String)

/** 关系匹配的候选索引；只剪除不可能配对，业务窗口仍由匹配器决定。 */
class CashFactCandidateIndex<A>(facts: List<RelationFact<A>>,
    private val core:CashRelationCoreRules<A>, private val magnitude:(A)->String,
    private val time:(Any?)->CashCandidateTime, private val shiftDay:(String,Int)->String,
    private val sourceGroup: (RelationFact<A>) -> String = { "other" }) {
    val byId = linkedMapOf<String, RelationFact<A>>()
    private val mirrorBuckets = linkedMapOf<String, MutableList<IndexedRelationFact<A>>>()
    private val transferInByDay = linkedMapOf<String, MutableList<IndexedRelationFact<A>>>()
    private val expensesByDay = linkedMapOf<String, MutableList<IndexedRelationFact<A>>>()
    private val refundsByDay = linkedMapOf<String, MutableList<IndexedRelationFact<A>>>()
    private val expenseDays = linkedMapOf<String, MutableSet<String>>()
    private val refundDays = linkedMapOf<String, MutableSet<String>>()
    private val transferPairByKey = linkedMapOf<String, MutableList<IndexedRelationFact<A>>>()
    private val transferPairTimestamps = linkedMapOf<String, MutableList<Double>>()
    private val transferPairAllByKey = linkedMapOf<String, MutableList<RelationFact<A>>>()
    private val transferPairInvalidByKey = linkedMapOf<String, MutableList<RelationFact<A>>>()
    private val fxInByTime = mutableListOf<IndexedRelationFact<A>>()
    private val fxInAll = mutableListOf<RelationFact<A>>()
    private val fxInInvalid = mutableListOf<RelationFact<A>>()

    private fun money(value:A):String=magnitude(value)
    private fun key(vararg parts: String): String = parts.joinToString("")
    private fun transferKey(subtype: String, currency: String, amount: A) = key(subtype, currency, money(amount))

    init {
        for (fact in facts) {
            if (fact.deleted || fact.factType != "cash" || core.signedDirection(fact) == 0) continue
            val amount = fact.signedAmount
            val currency = fact.currency.ifEmpty { "CNY" }.uppercase()
            val transferKey = transferKey(fact.recordSubtype, currency, amount)
            if (core.signedDirection(fact) > 0) {
                transferPairAllByKey.getOrPut(transferKey) { mutableListOf() } += fact
                if (core.isFxInRecord(fact)) fxInAll += fact
            }
            val instant = runCatching { time(fact.occurredAt) }.getOrNull()
            if (instant == null) {
                if (core.signedDirection(fact) > 0) {
                    transferPairInvalidByKey.getOrPut(transferKey) { mutableListOf() } += fact
                    if (core.isFxInRecord(fact)) fxInInvalid += fact
                }
                continue
            }
            val indexed = IndexedRelationFact(fact, instant.seconds,
                instant.utcDay, money(amount), currency,
                if (core.signedDirection(fact) > 0) 1 else -1, sourceGroup(fact))
            byId[fact.id] = fact
            if (core.signedDirection(fact) > 0) {
                transferPairByKey.getOrPut(transferKey) { mutableListOf() } += indexed
                if (core.isFxInRecord(fact)) fxInByTime += indexed
            }
            if (indexed.group in setOf("platform", "bank"))
                mirrorBuckets.getOrPut(key(indexed.group, indexed.currency, indexed.amountKey, indexed.day)) { mutableListOf() } += indexed
            if (core.isTransferInRecord(fact))
                transferInByDay.getOrPut(indexed.day) { mutableListOf() } += indexed
            val accountCurrency = key(fact.accountId, indexed.currency)
            if (core.isRefundExpenseCandidate(fact)) {
                expensesByDay.getOrPut(key(fact.accountId, indexed.currency, indexed.day)) { mutableListOf() } += indexed
                expenseDays.getOrPut(accountCurrency) { linkedSetOf() } += indexed.day
            } else if (core.isRefundIn(fact)) {
                refundsByDay.getOrPut(key(fact.accountId, indexed.currency, indexed.day)) { mutableListOf() } += indexed
                refundDays.getOrPut(accountCurrency) { linkedSetOf() } += indexed.day
            }
        }
        for (items in transferPairByKey.values) items.sortBy { it.ts }
        for ((k, items) in transferPairByKey) transferPairTimestamps[k] = items.map { it.ts }.toMutableList()
        fxInByTime.sortBy { it.ts }
    }

    private fun neighborDays(day:String,pad:Int=CANDIDATE_DAY_PAD):List<String> =
        (-pad..pad).map {shiftDay(day,it)}
    fun mirrorCandidates(seed: RelationFact<A>): List<RelationFact<A>> {
        val group = sourceGroup(seed)
        if (group !in setOf("platform", "bank")) return emptyList()
        val other = if (group == "platform") "bank" else "platform"
        val instant = runCatching { time(seed.occurredAt) }.getOrNull() ?: return emptyList()
        val day = instant.utcDay
        val currency = seed.currency.ifEmpty { "CNY" }.uppercase()
        val result = mutableListOf<RelationFact<A>>()
        for (candidateDay in neighborDays(day)) {
            for (item in mirrorBuckets[key(other, currency, money(seed.signedAmount), candidateDay)].orEmpty()) {
                if (item.fact.id == seed.id) continue
                if (core.isPaymentMirrorExpense(seed) && core.isPaymentMirrorExpense(item.fact) ||
                    core.isPaymentMirrorRefund(seed) && core.isPaymentMirrorRefund(item.fact))
                    result += item.fact
            }
        }
        return result
    }
    fun transferInCandidates(seed: RelationFact<A>, dayPad: Int): List<RelationFact<A>> {
        val instant = runCatching { time(seed.occurredAt) }.getOrNull() ?: return emptyList()
        val day = instant.utcDay
        return neighborDays(day, dayPad).flatMap { candidateDay ->
            transferInByDay[candidateDay].orEmpty().filter { it.fact.id != seed.id }.map { it.fact }
        }
    }
    private fun bisectRight(values: List<Double>, value: Double): Int = values.indexOfFirst { it > value }.let { if (it < 0) values.size else it }
    private fun bisectLeft(values: List<Double>, value: Double): Int = values.indexOfFirst { it >= value }.let { if (it < 0) values.size else it }
    fun transferPairCandidates(seed: RelationFact<A>, windowSeconds: Int): List<RelationFact<A>> {
        if (seed.deleted || seed.factType != "cash" || core.signedDirection(seed) >= 0) return emptyList()
        val subtype = seed.recordSubtype
        val currency = seed.currency.ifEmpty { "CNY" }.uppercase()
        val amount = seed.signedAmount
        val instant = runCatching { time(seed.occurredAt) }.getOrNull()
        if (instant == null) {
            if (subtype == "currency_exchange" && core.isFxOutRecord(seed)) return fxInAll.toList()
            return transferPairAllByKey[key(subtype, currency, money(amount))].orEmpty().toList()
        }
        val seedTs = instant.seconds
        val lower = seedTs - windowSeconds - 1
        val upper = seedTs + windowSeconds + 1
        fun byWindow(items: List<IndexedRelationFact<A>>, timestamps: List<Double>): List<RelationFact<A>> {
            val lo = bisectRight(timestamps, lower); val hi = bisectLeft(timestamps, upper)
            return if (lo >= hi) emptyList() else items.subList(lo, hi).map { it.fact }
        }
        if (subtype == "currency_exchange" && core.isFxOutRecord(seed))
            return byWindow(fxInByTime, fxInByTime.map { it.ts }) + fxInInvalid
        val transferKey = key(subtype, currency, money(amount))
        return byWindow(transferPairByKey[transferKey].orEmpty(), transferPairTimestamps[transferKey].orEmpty()) +
            transferPairInvalidByKey[transferKey].orEmpty()
    }
    fun refundCandidates(seed: RelationFact<A>, isRefundExcludedLeg: ((String) -> Boolean)? = null): List<RelationFact<A>> {
        val instant = runCatching { time(seed.occurredAt) }.getOrNull() ?: return emptyList()
        val day = instant.utcDay
        if (isRefundExcludedLeg?.invoke(seed.text) == true) return emptyList()
        val currency = seed.currency.ifEmpty { "CNY" }.uppercase()
        val accountCurrency = key(seed.accountId, currency)
        val pad = REFUND_CANDIDATE_DAYS + CANDIDATE_DAY_PAD
        val result = mutableListOf<RelationFact<A>>()
        if (core.signedDirection(seed) > 0) {
            if (!core.isRefundIn(seed)) return emptyList()
            val days = expenseDays[accountCurrency].orEmpty().sorted()
            if (days.isEmpty()) return emptyList()
            val start = shiftDay(day,-pad); val end = day
            val lo = days.indexOfFirst { it >= start }.let { if (it < 0) days.size else it }
            val hi = days.indexOfFirst { it > end }.let { if (it < 0) days.size else it }
            for (candidateDay in days.subList(lo, hi)) for (item in expensesByDay[key(seed.accountId, currency, candidateDay)].orEmpty())
                if (item.fact.id != seed.id) result += item.fact
        } else if (core.signedDirection(seed) < 0) {
            val days = refundDays[accountCurrency].orEmpty().sorted()
            if (days.isEmpty()) return emptyList()
            val start = day; val end = shiftDay(day,pad)
            val lo = days.indexOfFirst { it >= start }.let { if (it < 0) days.size else it }
            val hi = days.indexOfFirst { it > end }.let { if (it < 0) days.size else it }
            for (candidateDay in days.subList(lo, hi)) for (item in refundsByDay[key(seed.accountId, currency, candidateDay)].orEmpty())
                if (item.fact.id != seed.id) result += item.fact
        }
        return result
    }
}

fun cashFactCandidateIndex(facts:List<RelationFact<CashAmount>>,
    sourceGroup:(RelationFact<CashAmount>)->String={"other"}):CashFactCandidateIndex<CashAmount> =
    CashFactCandidateIndex(facts,cashRelationCore,{it.numericText().removePrefix("-")},::cashCandidateTime,::shiftCashCandidateDay,sourceGroup)

private fun cashCandidateTime(value:Any?):CashCandidateTime {
    val instant=when(value) {
        is Instant ->value
        else -> {
            var text=value?.toString()?.trim().orEmpty()
            require(text.isNotEmpty()) {"occurred_at is required"}
            if('T' !in text && ' ' in text) text=text.replaceFirst(" ","T")
            parsePythonCashTimestamp(text,allowNaive=true)
        }
    }
    return CashCandidateTime(instant.epochSeconds+instant.nanosecondsOfSecond/1_000_000_000.0) {
        formatCashCandidateDay(instant.epochSeconds.floorDiv(86400L))
    }
}
