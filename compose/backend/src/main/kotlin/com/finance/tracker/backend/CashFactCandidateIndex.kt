package com.finance.tracker.backend

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset

private const val CANDIDATE_DAY_PAD = 1

internal data class IndexedRelationFact(val fact: RelationFact, val ts: Double, val day: String,
    val absAmount: BigDecimal, val currency: String, val sign: Int, val group: String)

/** 关系匹配的候选索引；只剪除不可能配对，业务窗口仍由匹配器决定。 */
internal class CashFactCandidateIndex(facts: List<RelationFact>,
    private val sourceGroup: (RelationFact) -> String = { "other" }) {
    val byId = linkedMapOf<String, RelationFact>()
    private val mirrorBuckets = linkedMapOf<String, MutableList<IndexedRelationFact>>()
    private val transferInByDay = linkedMapOf<String, MutableList<IndexedRelationFact>>()
    private val expensesByDay = linkedMapOf<String, MutableList<IndexedRelationFact>>()
    private val refundsByDay = linkedMapOf<String, MutableList<IndexedRelationFact>>()
    private val expenseDays = linkedMapOf<String, MutableSet<String>>()
    private val refundDays = linkedMapOf<String, MutableSet<String>>()
    private val transferPairByKey = linkedMapOf<String, MutableList<IndexedRelationFact>>()
    private val transferPairTimestamps = linkedMapOf<String, MutableList<Double>>()
    private val transferPairAllByKey = linkedMapOf<String, MutableList<RelationFact>>()
    private val transferPairInvalidByKey = linkedMapOf<String, MutableList<RelationFact>>()
    private val fxInByTime = mutableListOf<IndexedRelationFact>()
    private val fxInAll = mutableListOf<RelationFact>()
    private val fxInInvalid = mutableListOf<RelationFact>()

    private fun money(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
    private fun key(vararg parts: String): String = parts.joinToString("")
    private fun transferKey(subtype: String, currency: String, amount: BigDecimal) = key(subtype, currency, money(amount))

    init {
        for (fact in facts) {
            if (fact.deleted || fact.factType != "cash" || fact.signedAmount.signum() == 0) continue
            val amount = fact.signedAmount
            val currency = fact.currency.ifEmpty { "CNY" }.uppercase()
            val transferKey = transferKey(fact.recordSubtype, currency, amount.abs())
            if (amount.signum() > 0) {
                transferPairAllByKey.getOrPut(transferKey) { mutableListOf() } += fact
                if (CashRelationCore.isFxInRecord(fact)) fxInAll += fact
            }
            val instant = runCatching { CashRelationGeometry.parseDt(fact.occurredAt) }.getOrNull()
            if (instant == null) {
                if (amount.signum() > 0) {
                    transferPairInvalidByKey.getOrPut(transferKey) { mutableListOf() } += fact
                    if (CashRelationCore.isFxInRecord(fact)) fxInInvalid += fact
                }
                continue
            }
            val indexed = IndexedRelationFact(fact, instant.epochSecond + instant.nano / 1_000_000_000.0,
                instant.atOffset(ZoneOffset.UTC).toLocalDate().toString(), amount.abs(), currency,
                if (amount.signum() > 0) 1 else -1, sourceGroup(fact))
            byId[fact.id] = fact
            if (amount.signum() > 0) {
                transferPairByKey.getOrPut(transferKey) { mutableListOf() } += indexed
                if (CashRelationCore.isFxInRecord(fact)) fxInByTime += indexed
            }
            if (indexed.group in setOf("platform", "bank"))
                mirrorBuckets.getOrPut(key(indexed.group, indexed.currency, money(indexed.absAmount), indexed.day)) { mutableListOf() } += indexed
            if (CashRelationCore.isTransferInRecord(fact))
                transferInByDay.getOrPut(indexed.day) { mutableListOf() } += indexed
            val accountCurrency = key(fact.accountId, indexed.currency)
            if (CashRelationCore.isRefundExpenseCandidate(fact)) {
                expensesByDay.getOrPut(key(fact.accountId, indexed.currency, indexed.day)) { mutableListOf() } += indexed
                expenseDays.getOrPut(accountCurrency) { linkedSetOf() } += indexed.day
            } else if (CashRelationCore.isRefundIn(fact)) {
                refundsByDay.getOrPut(key(fact.accountId, indexed.currency, indexed.day)) { mutableListOf() } += indexed
                refundDays.getOrPut(accountCurrency) { linkedSetOf() } += indexed.day
            }
        }
        for (items in transferPairByKey.values) items.sortBy { it.ts }
        for ((k, items) in transferPairByKey) transferPairTimestamps[k] = items.map { it.ts }.toMutableList()
        fxInByTime.sortBy { it.ts }
    }

    private fun neighborDays(day: String, pad: Int = CANDIDATE_DAY_PAD): List<String> {
        val base = LocalDate.parse(day)
        return (-pad..pad).map { base.plusDays(it.toLong()).toString() }
    }
    fun mirrorCandidates(seed: RelationFact): List<RelationFact> {
        val group = sourceGroup(seed)
        if (group !in setOf("platform", "bank")) return emptyList()
        val other = if (group == "platform") "bank" else "platform"
        val instant = runCatching { CashRelationGeometry.parseDt(seed.occurredAt) }.getOrNull() ?: return emptyList()
        val day = instant.atOffset(ZoneOffset.UTC).toLocalDate().toString()
        val currency = seed.currency.ifEmpty { "CNY" }.uppercase()
        val result = mutableListOf<RelationFact>()
        for (candidateDay in neighborDays(day)) {
            for (item in mirrorBuckets[key(other, currency, money(seed.signedAmount.abs()), candidateDay)].orEmpty()) {
                if (item.fact.id == seed.id) continue
                if (CashRelationCore.isPaymentMirrorExpense(seed) && CashRelationCore.isPaymentMirrorExpense(item.fact) ||
                    CashRelationCore.isPaymentMirrorRefund(seed) && CashRelationCore.isPaymentMirrorRefund(item.fact))
                    result += item.fact
            }
        }
        return result
    }
    fun transferInCandidates(seed: RelationFact, dayPad: Int): List<RelationFact> {
        val instant = runCatching { CashRelationGeometry.parseDt(seed.occurredAt) }.getOrNull() ?: return emptyList()
        val day = instant.atOffset(ZoneOffset.UTC).toLocalDate().toString()
        return neighborDays(day, dayPad).flatMap { candidateDay ->
            transferInByDay[candidateDay].orEmpty().filter { it.fact.id != seed.id }.map { it.fact }
        }
    }
    private fun bisectRight(values: List<Double>, value: Double): Int = values.indexOfFirst { it > value }.let { if (it < 0) values.size else it }
    private fun bisectLeft(values: List<Double>, value: Double): Int = values.indexOfFirst { it >= value }.let { if (it < 0) values.size else it }
    fun transferPairCandidates(seed: RelationFact, windowSeconds: Int): List<RelationFact> {
        if (seed.deleted || seed.factType != "cash" || seed.signedAmount.signum() >= 0) return emptyList()
        val subtype = seed.recordSubtype
        val currency = seed.currency.ifEmpty { "CNY" }.uppercase()
        val amount = seed.signedAmount.abs()
        val instant = runCatching { CashRelationGeometry.parseDt(seed.occurredAt) }.getOrNull()
        if (instant == null) {
            if (subtype == "currency_exchange" && CashRelationCore.isFxOutRecord(seed)) return fxInAll.toList()
            return transferPairAllByKey[key(subtype, currency, money(amount))].orEmpty().toList()
        }
        val seedTs = instant.epochSecond + instant.nano / 1_000_000_000.0
        val lower = seedTs - windowSeconds - 1
        val upper = seedTs + windowSeconds + 1
        fun byWindow(items: List<IndexedRelationFact>, timestamps: List<Double>): List<RelationFact> {
            val lo = bisectRight(timestamps, lower); val hi = bisectLeft(timestamps, upper)
            return if (lo >= hi) emptyList() else items.subList(lo, hi).map { it.fact }
        }
        if (subtype == "currency_exchange" && CashRelationCore.isFxOutRecord(seed))
            return byWindow(fxInByTime, fxInByTime.map { it.ts }) + fxInInvalid
        val transferKey = key(subtype, currency, money(amount))
        return byWindow(transferPairByKey[transferKey].orEmpty(), transferPairTimestamps[transferKey].orEmpty()) +
            transferPairInvalidByKey[transferKey].orEmpty()
    }
    fun refundCandidates(seed: RelationFact, isRefundExcludedLeg: ((String) -> Boolean)? = null): List<RelationFact> {
        val instant = runCatching { CashRelationGeometry.parseDt(seed.occurredAt) }.getOrNull() ?: return emptyList()
        val day = instant.atOffset(ZoneOffset.UTC).toLocalDate()
        if (isRefundExcludedLeg?.invoke(seed.text) == true) return emptyList()
        val currency = seed.currency.ifEmpty { "CNY" }.uppercase()
        val accountCurrency = key(seed.accountId, currency)
        val pad = REFUND_CANDIDATE_DAYS + CANDIDATE_DAY_PAD
        val result = mutableListOf<RelationFact>()
        if (seed.signedAmount.signum() > 0) {
            if (!CashRelationCore.isRefundIn(seed)) return emptyList()
            val days = expenseDays[accountCurrency].orEmpty().sorted()
            if (days.isEmpty()) return emptyList()
            val start = day.minusDays(pad.toLong()).toString(); val end = day.toString()
            val lo = days.indexOfFirst { it >= start }.let { if (it < 0) days.size else it }
            val hi = days.indexOfFirst { it > end }.let { if (it < 0) days.size else it }
            for (candidateDay in days.subList(lo, hi)) for (item in expensesByDay[key(seed.accountId, currency, candidateDay)].orEmpty())
                if (item.fact.id != seed.id) result += item.fact
        } else if (seed.signedAmount.signum() < 0) {
            val days = refundDays[accountCurrency].orEmpty().sorted()
            if (days.isEmpty()) return emptyList()
            val start = day.toString(); val end = day.plusDays(pad.toLong()).toString()
            val lo = days.indexOfFirst { it >= start }.let { if (it < 0) days.size else it }
            val hi = days.indexOfFirst { it > end }.let { if (it < 0) days.size else it }
            for (candidateDay in days.subList(lo, hi)) for (item in refundsByDay[key(seed.accountId, currency, candidateDay)].orEmpty())
                if (item.fact.id != seed.id) result += item.fact
        }
        return result
    }
}
