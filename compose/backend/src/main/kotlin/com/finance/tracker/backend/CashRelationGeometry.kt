package com.finance.tracker.backend

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** 关系匹配的时间、日期、卡尾和镜像图几何规则。 */
internal object CashRelationGeometry {
    fun asDecimal(value: Any?): BigDecimal = when (value) {
        is BigDecimal -> value
        is CashAmount -> BigDecimal(value.toString())
        else -> BigDecimal(value.toString())
    }
    fun absDecimal(value: Any?): BigDecimal = asDecimal(value).abs()
    fun parseDt(value: Any?): Instant = when (value) {
        is Instant -> value
        is java.time.OffsetDateTime -> value.toInstant()
        is java.time.ZonedDateTime -> value.toInstant()
        else -> {
            var text = value?.toString()?.trim().orEmpty()
            if (text.isEmpty()) throw IllegalArgumentException("occurred_at is required")
            if ('T' !in text && ' ' in text) text = text.replaceFirst(" ", "T")
            pythonCashTimestamp(text, allowNaive = true)
        }
    }
    private val rules=com.finance.tracker.business.domain.CashRelationGeometryRules(CashRelationCore.sharedRules,
        {value ->parseDt(value).atOffset(ZoneOffset.UTC).toLocalDate().toString()},
        {a,b ->kotlin.math.abs(ChronoUnit.SECONDS.between(parseDt(a),parseDt(b)))})
    fun timeDeltaSeconds(a:Any?,b:Any?)=rules.timeDeltaSeconds(a,b)
    fun sameCalendarDay(a:Any?,b:Any?)=rules.sameCalendarDay(a,b)
    fun isDateOnlyBusinessString(value:String?)=rules.isDateOnlyBusinessString(value)
    fun businessDayUtc(fact:RelationFact):LocalDate?=rules.businessDayUtc(fact)?.let(LocalDate::parse)
    fun factIsBankDateOnly(fact:RelationFact)=rules.factIsBankDateOnly(fact)
    fun sameBusinessDayUtc(a:RelationFact,b:RelationFact)=rules.sameBusinessDayUtc(a,b)
    fun extractCardTails(text:String?)=rules.extractCardTails(text)
    fun mainStyleCrossVerify(left:Any?,right:Any?)=rules.mainStyleCrossVerify(left,right)
    fun crossKindCompatible(existingKinds:Collection<String>,newKind:String)=rules.crossKindCompatible(existingKinds,newKind)
    fun platformScore(fact:RelationFact)=rules.platformScore(fact)
    fun canonicalMirrorFact(facts:List<RelationFact>)=rules.canonicalMirrorFact(facts)
    fun buildMirrorComponents(factIds:Collection<String>,acceptedPairs:List<Pair<String,String>>)=rules.buildMirrorComponents(factIds,acceptedPairs)
}
