package com.finance.tracker.backend

import java.math.BigInteger

internal typealias CashImportRelationChoiceResult = com.finance.tracker.business.domain.CashImportRelationChoiceResult<BigInteger>

/** 处理冻结关系选择、拆分公共值和最终预览图；真实写入由确认事务处理。 */
internal object CashImportRelationDecisions {
    @Suppress("UNCHECKED_CAST")
    private fun map(value: Any?): Map<String, Any?> = value as Map<String, Any?>
    private fun copy(value: Any?): Any? = CashRecordPolicy.copyValue(value)
    private fun generation(value: Any?): BigInteger = value?.toString()?.toBigInteger() ?: BigInteger.ZERO

    private val choiceRules = com.finance.tracker.business.domain.CashImportRelationChoiceRules(
        BigInteger.ZERO, ::generation, { it + BigInteger.ONE }, { it }, ::copy,
        { values -> cashImportDigest(*values.toTypedArray()) })
    fun applyChoices(groups: List<MutableMap<String, Any?>>, choices: List<Map<String, Any?>>,
        previous: Map<String, Any?>?): CashImportRelationChoiceResult = choiceRules.apply(groups, choices, previous)

    private val decisionRules = com.finance.tracker.business.domain.CashImportRelationDecisionRules<BigInteger, java.time.Instant>(
        ::strictCashInteger, ::copy, { values -> cashImportDigest(*values.toTypedArray()) })

    fun restoreSplitValues(context: CashSqlContext, snapshot: CashImportPreviewSnapshot, groups: List<MutableMap<String, Any?>>) {
        context.requireActive()
        decisionRules.restoreSplitValues(snapshot, groups,
            { context.entries.forTransactions(snapshot.context.transactionIds).first },
            { map(cashSourceValue(it.standaloneValues)) }, CashRelationReadRepository(context)::confirmedClosure)
    }

    fun applyGroups(snapshot: CashImportPreviewSnapshot, groups: MutableList<MutableMap<String, Any?>>): List<Map<String, Any?>> =
        decisionRules.applyGroups(snapshot, groups)
}
