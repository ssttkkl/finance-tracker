package com.finance.tracker.business.domain

import java.time.LocalDate
import kotlin.test.*

class CashCandidateCalendarParityTest {
    @Test fun candidateDayTextMatchesJavaAcrossAllSupportedMonthEnds() {
        for(year in 0..10000) for(month in 1..12) {
            val first=LocalDate.of(year,month,1)
            val date=first.plusMonths(1).minusDays(1)
            assertEquals(date.toString(),formatCashCandidateDay(date.toEpochDay()),"$year-$month")
        }
        for(day in listOf("0001-01-01","1969-12-31","2000-02-29","9999-12-31")) for(offset in -16..16)
            assertEquals(LocalDate.parse(day).plusDays(offset.toLong()).toString(),shiftCashCandidateDay(day,offset))
    }
}
