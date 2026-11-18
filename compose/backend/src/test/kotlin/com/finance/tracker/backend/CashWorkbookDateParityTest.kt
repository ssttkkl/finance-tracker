package com.finance.tracker.backend

import com.finance.tracker.business.domain.cashCcbWorkbookDateText
import com.finance.tracker.business.domain.cashPlatformWorkbookDateText
import java.time.format.DateTimeFormatter
import org.apache.poi.ss.usermodel.DateUtil
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class CashWorkbookDateParityTest {
    @Test fun allBinaryExponentsAndNeighboursMatchOriginalPoiDateArithmetic() {
        for (exponent in 1..2046) {
            val power = exponent.toLong() shl 52
            for (bits in power - 1..power + 1) verify(Double.fromBits(bits))
        }
    }
    @Test fun realDateFractionsRoundingEdgesAndLargeValuesMatchOriginalPoi() {
        val random = Random(5112026)
        repeat(2_000) { verify(random.nextDouble() * 3_000_000) }
        for (value in listOf(0.0,-0.0,59.0,60.0,61.0,Double.MAX_VALUE,2147483647.9,2147483648.1,4294967295.9,4294967296.1,
            45000.123456789,0.9999999999,2958465.999999999,1e23)) verify(value)
        for (day in listOf(0.0,59.0,60.0,61.0,45000.0,2958465.0)) {
            for (millis in listOf(0.4999,0.5,0.5001,999.4999,999.5,999.5001,86_399_999.4999,86_399_999.5)) {
                val value = day + millis / 86_400_000.0
                for (bits in value.toBits()-1..value.toBits()+1) if (bits >= 0) verify(Double.fromBits(bits))
            }
        }
    }
    @Test fun representativeDateFormattingRecordsItsActualCost() {
        val values = List(10_000) { 45000.0 + it / 86400.0 }
        var expected = 0L
        var actual = 0L
        val nativeStarted = System.nanoTime()
        for (value in values) expected += DateUtil.getLocalDateTime(value, false).toString().replace('T',' ').hashCode()
        val nativeNanos = System.nanoTime() - nativeStarted
        val sharedStarted = System.nanoTime()
        for (value in values) actual += cashPlatformWorkbookDateText(value, false).hashCode()
        val sharedNanos = System.nanoTime() - sharedStarted
        assertEquals(expected, actual)
        println("synthetic_date_format count=${values.size} native_ns=$nativeNanos shared_ns=$sharedNanos checksum=$actual jdk=${Runtime.version()}")
    }
    private fun verify(value: Double) {
        for (window in listOf(false,true)) {
            val expected = DateUtil.getLocalDateTime(value,window)
            assertEquals(expected.toString().replace('T',' '),cashPlatformWorkbookDateText(value,window),"platform bits=${value.toBits()} 1904=$window")
            assertEquals(expected.format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")),cashCcbWorkbookDateText(value,window),"bank bits=${value.toBits()} 1904=$window")
        }
    }
}
