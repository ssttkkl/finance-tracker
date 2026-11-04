package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.BigInteger

internal typealias CashRelationFailure = com.finance.tracker.business.domain.CashRelationFailure
internal typealias CashDecimalLiteral = com.finance.tracker.business.domain.CashDecimalLiteral
internal typealias CashRelationEndpoint = com.finance.tracker.business.domain.CashRelationEndpoint
internal typealias CashRelationEvidence = com.finance.tracker.business.domain.CashRelationEvidence
internal typealias CashRelationEndpointValues = com.finance.tracker.business.domain.CashRelationEndpointValues
internal typealias CashRelationLink = com.finance.tracker.business.domain.CashRelationLink
internal typealias CashRelationGroups = com.finance.tracker.business.domain.CashRelationGroups
internal val CashRelationEndpointValues.number: BigDecimal get()=amount.arithmeticValue()
internal val cashRelationStringOrder= com.finance.tracker.business.domain.cashRelationStringOrder
internal fun relationFailure(code: String): Nothing=com.finance.tracker.business.domain.relationFailure(code)
internal fun cashRelationDecimal(value: Any?): CashAmount?=com.finance.tracker.business.domain.cashRelationDecimal(value)
internal fun validateCashRelationEndpoint(value: Any?): CashRelationEndpointValues=
    com.finance.tracker.business.domain.validateCashRelationEndpoint(value)
internal fun cashFundingRoleCompatible(direction: Any?,amount: Any?,recordType: Any?): Boolean =
    com.finance.tracker.business.domain.cashFundingRoleCompatible(direction,
        if(amount is BigDecimal || amount is BigInteger) CashDecimalLiteral(amount.toString()) else amount,recordType)
internal fun validateCashRelationRoles(kind: Any?,primary: Any?,secondary: Any?,primaryRole: Any?,secondaryRole: Any?) =
    com.finance.tracker.business.domain.validateCashRelationRoles(kind,primary,secondary,primaryRole,secondaryRole)
internal fun inferCashRelationRoles(kind: Any?,primary: Any?,secondary: Any?): Pair<String,String> =
    com.finance.tracker.business.domain.inferCashRelationRoles(kind,primary,secondary)
internal fun discoverCashRelationRoles(kind: Any?,primary: Any?,secondary: Any?,evidence: Any?=CashRelationEvidence()): Pair<String,String> =
    com.finance.tracker.business.domain.discoverCashRelationRoles(kind,primary,secondary,evidence)
internal fun affectedCashRelationClosure(seedIds: Iterable<*>,oldLinks: Iterable<*>,newLinks: Iterable<*>): Set<String> =
    com.finance.tracker.business.domain.affectedCashRelationClosure(seedIds,oldLinks,newLinks)
internal fun cashRelationCommitGroups(oldLinks: Iterable<*>,changes: Iterable<*>): List<List<String>> =
    com.finance.tracker.business.domain.cashRelationCommitGroups(oldLinks,changes)
internal fun cashRelationCandidateTypes(recordTypes: Set<String>): Set<String> =
    com.finance.tracker.business.domain.cashRelationCandidateTypes(recordTypes)
