package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashSourceCoverage
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class BankStatementTextPrimitiveParityTest {
    @Test fun utf16ReplacementKeepsOriginalMalformedConsumption() {
        fun compare(bytes: ByteArray) = assertEquals(String(bytes, StandardCharsets.UTF_16), decodeBankStatementUtf16(bytes),
            bytes.joinToString { "%02x".format(it) })
        for (little in listOf(false, true)) {
            val bom = if (little) byteArrayOf(0xff.toByte(), 0xfe.toByte()) else byteArrayOf(0xfe.toByte(), 0xff.toByte())
            fun units(vararg values: Int) = bom + values.flatMap { value ->
                if (little) listOf(value.toByte(), (value ushr 8).toByte()) else listOf((value ushr 8).toByte(), value.toByte())
            }.toByteArray()
            for (unit in 0..0xffff) compare(units(unit))
            val boundaries = listOf(0, 0x41, 0xd7ff, 0xd800, 0xdbff, 0xdc00, 0xdfff, 0xe000, 0xfeff, 0xfffe, 0xffff)
            for (first in boundaries) for (second in boundaries) for (third in boundaries) {
                compare(units(first, second, third))
                compare(units(first, second, third) + byteArrayOf(0x42))
            }
        }
        val random = Random(0x716)
        for (size in 0..32) repeat(64) { compare(random.nextBytes(size)) }
    }

    @Test fun ccbDateCoverageKeepsJvmCalendarShanghaiAndYearBoundaries() {
        val values = listOf("00000101", "00000229", "00010101", "19000229", "19000301", "19490528",
            "19860504", "19910915", "20000229", "20260230", "20261301", "20260001", "99991231", "2026-01-01", "bad")
        for (text in values) {
            val records = listOf(listOf("中国建设银行个人活期账户全部交易明细"),
                listOf("卡号/账号：12345678 起始日期：$text 结束日期：$text"),
                listOf("序号", "交易日期", "交易金额", "账户余额"), listOf("1", "20260102", "-1.00", "100.00"))
            fun originalDate(): LocalDateTime? = try {
                when {
                    text.matches(Regex("[0-9]{8}")) -> LocalDateTime.of(text.take(4).toInt(), text.substring(4, 6).toInt(), text.takeLast(2).toInt(), 0, 0)
                    text.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) -> LocalDateTime.of(text.take(4).toInt(), text.substring(5, 7).toInt(), text.takeLast(2).toInt(), 0, 0)
                    else -> null
                }
            } catch (_: Exception) { null }
            fun originalText(time: LocalDateTime): String = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")
                .format(time.atZone(ZoneId.of("Asia/Shanghai")).toInstant().atOffset(java.time.ZoneOffset.UTC)) + "+00:00"
            val date = originalDate()
            val expected = CashSourceCoverage(date?.let(::originalText), date?.plusDays(1)?.let(::originalText), date != null,
                if (date != null) "bank-declared-range-sequence-v1" else "", mapOf("export_type" to "all"),
                "bank-table-coverage-v1", startInclusive = true, endInclusive = false, timePrecision = "day")
            assertEquals(expected, CashBankStatementTextReader.readCcb(records).coverage, text)
        }
    }
}
