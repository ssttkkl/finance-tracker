package com.finance.tracker.backend

import com.finance.tracker.business.domain.CashCandidateTime

internal class CashFactCandidateIndex(facts:List<RelationFact>,sourceGroup:(RelationFact)->String={"other"}) {
    private val index=com.finance.tracker.business.domain.CashFactCandidateIndex(facts,CashRelationCore.sharedRules,
        {it.abs().stripTrailingZeros().toPlainString()}, {value ->
            val instant=CashRelationGeometry.parseDt(value)
            CashCandidateTime(instant.epochSecond+instant.nano/1_000_000_000.0) {
                instant.atOffset(java.time.ZoneOffset.UTC).toLocalDate().toString()
            }
        },{day,offset ->java.time.LocalDate.parse(day).plusDays(offset.toLong()).toString()},sourceGroup)
    val byId get()=index.byId
    fun mirrorCandidates(seed:RelationFact)=index.mirrorCandidates(seed)
    fun transferInCandidates(seed:RelationFact,dayPad:Int)=index.transferInCandidates(seed,dayPad)
    fun transferPairCandidates(seed:RelationFact,windowSeconds:Int)=index.transferPairCandidates(seed,windowSeconds)
    fun refundCandidates(seed:RelationFact,isRefundExcludedLeg:((String)->Boolean)?=null)=index.refundCandidates(seed,isRefundExcludedLeg)
}
