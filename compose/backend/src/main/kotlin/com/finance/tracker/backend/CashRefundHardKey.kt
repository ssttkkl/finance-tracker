package com.finance.tracker.backend

import java.math.BigDecimal

internal object CashRefundHardKey {
    private val rules=com.finance.tracker.business.domain.CashRefundHardKeyRules(CashRelationCore.sharedRules,
        CashPlatformRefund.sharedRules, {value:BigDecimal ->value.abs()},{it.signum()}, {a,b ->a.compareTo(b)},
        {a,b ->a.subtract(b)},{it.toPlainString()},BigDecimal.ZERO)
    val sharedRules get()=rules
    fun match(detailedRows:List<Map<String,Any?>>,factsById:Map<String,RelationFact>,linkedPairs:Set<Pair<String,String>>?=null,
        remainingByExpense:Map<String,BigDecimal>?=null)=rules.match(detailedRows,factsById,linkedPairs,remainingByExpense)
}
