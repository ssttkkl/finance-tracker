package com.finance.tracker.business.domain

internal object RelationSerializationTestAdapters {
    val rules=CashRelationSerializationRules(cashRelationCore,
        {value ->CashAmount.parse(value.toString())},PlatformRefundTestAdapters::absolute,
        RelationPipelineTestAdapters::difference,
        {value ->runCatching {cashRelationCore.stableTimestamp(value)}.getOrElse {value.toString()}},
        {value ->if(value is CashAmount) (if(PlatformRefundTestAdapters.sign(value)<0) "-" else "")+cashAbsolutePlainText(value) else value})
}
