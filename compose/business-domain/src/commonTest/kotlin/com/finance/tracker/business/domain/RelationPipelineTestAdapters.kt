package com.finance.tracker.business.domain

/** Bounded normalized fixture adapters; production accumulated amounts are a separate concern. */
internal object RelationPipelineTestAdapters {
    private val refund=CashRefundMatchRules(cashRelationCore,
        {value ->value.orEmpty().replace("_", "").replace(" ", "").lowercase()},
        ::parseRelationTimestampInstant,{a,b ->(b-a).inWholeNanoseconds},
        PlatformRefundTestAdapters::absolute,::difference,::cashAbsolutePlainText,
        {value ->CashAmount.parse(value.toString())})
    val rules=CashRelationPipelineRules(cashRelationCore,cashRelationGeometry,cashMirrorMatch,
        cashTransferMatch,cashRefundDiamond,refund,{cashFactCandidateIndex(it,cashRelationCore::sourceGroup)},
        PlatformRefundTestAdapters::absolute,::sum,::difference,
        {value ->CashAmount.parse(value.toString())},CashAmount.parse("0"))
    // Only the small normalized fixture values use this bounded exact adapter.
    private fun sum(a:CashAmount,b:CashAmount)=combine(a,b,false)
    fun difference(a:CashAmount,b:CashAmount)=combine(a,b,true)
    private fun combine(a:CashAmount,b:CashAmount,negate:Boolean):CashAmount {
        val left=cashAbsolutePlainText(a); val right=cashAbsolutePlainText(b)
        val scale=maxOf(left.substringAfter('.', "").length,right.substringAfter('.', "").length)
        fun digits(text:String)=text.replace(".", "")+"0".repeat(scale-text.substringAfter('.', "").length)
        val first=digits(left); val second=digits(right)
        val signA=PlatformRefundTestAdapters.sign(a)
        val signB=PlatformRefundTestAdapters.sign(b)*(if(negate) -1 else 1)
        val order=compareUnsigned(first,second)
        val magnitude=if(signA==signB) addUnsigned(first,second)
            else if(order>=0) subtractUnsigned(first,second) else subtractUnsigned(second,first)
        val sign=if(signA==signB || order>=0) signA else signB
        return CashAmount.parse(formatSigned(magnitude,scale,sign))
    }
}
