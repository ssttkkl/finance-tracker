package com.finance.tracker.backend

import com.finance.tracker.business.domain.CashProjectionMonthlyRules
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

internal typealias CashProjectionMonthlyData = com.finance.tracker.business.domain.CashProjectionMonthlyData

internal class CashProjectionMonthlyAccumulator(zone: ZoneId) {
    private val rules=CashProjectionMonthlyRules<Instant>({ it.uppercase(Locale.ROOT) }) { instant ->
        instant.atZone(zone).let { "%04d-%02d".format(Locale.ROOT,it.year,it.monthValue) }
    }
    fun add(occurred:Instant?,economic:String?,amount:BigDecimal?,currency:String?,eligible:Boolean=true,attention:Long=0) =
        rules.add(occurred,economic,amount?.toString(),currency,eligible,attention)
    fun result():CashProjectionMonthlyData=rules.result()
}
