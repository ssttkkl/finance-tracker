package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.BigInteger

internal typealias CashRelationGraphEdge=com.finance.tracker.business.domain.CashRelationGraphEdge
internal typealias CashJointRelationMember=com.finance.tracker.business.domain.CashJointRelationMember
internal fun strictCashInteger(value: Any?): BigInteger?=when(value) {
    is BigInteger ->value
    is Byte,is Short,is Int,is Long ->BigInteger.valueOf((value as Number).toLong())
    else ->null
}
internal fun CashAmount.arithmeticValue(): BigDecimal=if(representation.coefficient=="0" && representation.exponent>Int.MAX_VALUE) BigDecimal.ZERO else BigDecimal(toString())

internal fun validateCashRelationGraphEdgeInput(value:Any?)=cashRelationGraphRules.validateEdgeInput(value)
internal fun validateCashJointContext(expectedMembers:Any?,currentMembers:Any?,decidedIds:Any?)=
    cashRelationGraphRules.validateJointContext(expectedMembers,currentMembers,decidedIds)
internal fun buildCashRelationGraph(facts:Any?,components:Any?,relations:Any?,fundingComponentIds:Any?=emptyList<Any>()):CashProjectionBuild=
    cashRelationGraphRules.build(facts,components,relations,fundingComponentIds)
