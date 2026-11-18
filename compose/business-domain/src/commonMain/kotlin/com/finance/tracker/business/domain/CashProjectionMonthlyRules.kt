package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.time.Instant

data class CashProjectionMonthlyData(val summaries: JsonArray, val positions: JsonArray, val total: Long, val pending: Long)

/** Consume every filtered row in list order; the caller supplies the browser's calendar month. */
class CashProjectionMonthlyRules<T>(private val normalizeCurrency:(String)->String=::pythonCashUpper,
    private val monthAt:(T)->String) {
    private val months=mutableSetOf<String>()
    private val totals=linkedMapOf<Pair<String,String>,Pair<LedgerArithmeticValue,LedgerArithmeticValue>>()
    private val positions=mutableListOf<Triple<String,Long,Long>>()
    private var count=0L
    private var pending=0L

    fun add(occurred:T?,economic:String?,amount:String?,currency:String?,eligible:Boolean=true,attention:Long=0) {
        val month=occurred?.let(monthAt) ?: "unknown"
        if(positions.lastOrNull()?.first==month) positions[positions.lastIndex]=positions.last().copy(third=count+1)
        else positions+=Triple(month,count,count+1)
        count++;if(attention>0)pending++
        if(eligible && occurred!=null) {
            months+=month
            if(economic in setOf("income","expense") && !currency.isNullOrEmpty()) {
                val key=month to normalizeCurrency(currency.trim {it.code in pythonWhitespace})
                val (income,expense)=totals[key] ?: (LedgerArithmeticValue.ZERO to LedgerArithmeticValue.ZERO)
                val value=LedgerArithmeticValue.fromCanonical(requireNotNull(amount))
                totals[key]=if(economic=="income") income.add(value) to expense else income to expense.add(value)
            }
        }
    }
    fun result():CashProjectionMonthlyData {
        fun money(value:LedgerArithmeticValue):String {
            if(value.signum==0)return "0"
            val text=value.toPlainString()
            return if('.' in text)text.trimEnd('0').trimEnd('.') else text
        }
        return CashProjectionMonthlyData(JsonArray(months.sortedDescending().map {month->buildJsonObject {
            put("month",month)
            put("currencies",JsonArray(totals.entries.filter {it.key.first==month}.sortedBy {it.key.second}.map {(key,values)->buildJsonObject {
                put("currency",key.second);put("income",money(values.first));put("expense",money(values.second))
            }}))
        }}),JsonArray(positions.map {(month,first,end)->buildJsonObject {
            put("month",month);put("start_index",first);put("end_index",end)
        }}),count,pending)
    }
}

/** Summarize the complete ordered selection, before applying a page limit. */
fun summarizeLocalCashProjections(projections:List<CashProjection<Long,Instant>>,
    monthAt:(Instant)->String):CashProjectionMonthlyData = CashProjectionMonthlyRules(monthAt=monthAt).apply {
    for(projection in projections)add(projection.occurredAt,projection.economicType?.value,
        projection.netAmount?.toString(),projection.primaryRecord.currency,projection.statisticsEligible,projection.attentionCount.toLong())
}.result()
