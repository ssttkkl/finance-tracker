package com.finance.tracker.business.domain

/** Normalized bounded fixtures; raw row parsing and production accumulator wiring are tested separately. */
internal object RelationPlanningTestAdapters {
    private val serialization=RelationSerializationTestAdapters.rules
    private val fingerprints=CashRelationPlanFingerprints(serialization,
        {value:CashAmount ->LedgerArithmeticValue.fromCashAmount(value).toPlainString()},
        {value ->sourceJson(value,{null},::pythonFloatText).toString()})
    private val hardKey=CashRefundHardKeyRules(cashRelationCore,PlatformRefundTestAdapters.platform,
        PlatformRefundTestAdapters::absolute,PlatformRefundTestAdapters::sign,PlatformRefundTestAdapters::compare,
        RelationPipelineTestAdapters::difference,::cashAbsolutePlainText,CashAmount.parse("0"))
    val rules=CashRelationPlanningRules(cashRelationCore,serialization,hardKey,RelationPipelineTestAdapters.rules,
        RelationValidationTestAdapters.rules,fingerprints,{cashFactCandidateIndex(it,cashRelationCore::sourceGroup)},
        {error("normalized fixture rows must resolve to their input facts")},PlatformRefundTestAdapters::absolute,
        RelationPipelineTestAdapters::difference,{CashAmount.parse(it.toString())},PlatformRefundTestAdapters::sign)
}
