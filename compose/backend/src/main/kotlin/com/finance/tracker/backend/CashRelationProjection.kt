package com.finance.tracker.backend

import java.math.BigDecimal

internal typealias RelationProjectionResult=com.finance.tracker.business.domain.RelationProjectionResult<BigDecimal>

internal object CashRelationProjection {
    private val rules=com.finance.tracker.business.domain.CashRelationProjectionRules(CashRelationCore.sharedRules,
        CashRelationGeometry.sharedRules,{value:BigDecimal ->value},{it.signum()},{it.abs()},
        {a,b ->a.add(b)},{a,b ->a.subtract(b)},BigDecimal.ZERO)
    fun project(facts:List<RelationFact>,acceptedRelations:List<Map<String,Any?>>)=rules.project(facts,acceptedRelations)
}
