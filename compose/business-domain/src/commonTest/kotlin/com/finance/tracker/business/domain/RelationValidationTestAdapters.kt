package com.finance.tracker.business.domain

import kotlin.time.Instant

/** Only normalized fixture values; production raw scalar adapters remain a separate integration. */
internal object RelationValidationTestAdapters {
    val values=CashProjectionValues<Long,Instant>({it>0},{runCatching {formatPythonCashTimestamp(it)}.isSuccess},{it.uppercase()})
    private val projections=CashProjectionRules(values) {it.toString()}
    private val graph=CashRelationGraphRules(values,projections,{it as? Long},{it.toLong()})
    val rules=CashRelationValidationRules(cashRelationCore,RelationSerializationTestAdapters.rules,values,graph,
        {value:CashAmount ->LedgerArithmeticValue.fromCashAmount(value).toPlainString()},::parseRelationTimestampInstant,
        {it},{it},{value ->LedgerArithmeticValue.fromCanonical(value.toString()).toPlainString()},
        {value ->if(value is CashAmount) LedgerArithmeticValue.fromCashAmount(value).toPlainString() else value.toString()},
        {value ->sourceJson(value,{null},::pythonFloatText).toString()})
}
