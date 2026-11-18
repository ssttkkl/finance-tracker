package com.finance.tracker.business.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalCashPlatformDateParityTest {
    // Original backend parseDt expression; no candidate grammar or calendar helper is used by this oracle.
    private fun original(value: Any?): LocalDateTime? {
        var text = value?.toString().orEmpty().trim().replace("/", "-")
        if (text.isEmpty()) return null
        runCatching {
            val parsed = OffsetDateTime.parse(text.replace("Z", "+00:00"))
            return parsed.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()
        }
        runCatching { return LocalDateTime.parse(text.replace("Z", "")) }
        text = text.take(19)
        for (format in listOf("uuuu-MM-dd HH:mm:ss", "uuuu-MM-dd HH:mm", "uuuu-MM-dd")) {
            runCatching {
                val formatter = DateTimeFormatter.ofPattern(format)
                return if (format == "uuuu-MM-dd") LocalDate.parse(text, formatter).atStartOfDay()
                else LocalDateTime.parse(text, formatter)
            }
        }
        return null
    }

    private fun compare(text: String?) {
        val expected = original(text)?.toInstant(ZoneOffset.UTC)?.let { it.epochSecond to it.nano }
        val actual = parseLocalCashPlatformDate(text)?.let { it.epochSeconds to it.nanosecondsOfSecond }
        assertEquals(expected, actual, text)
    }

    @Test fun dateGrammarAndResolverMatchTheUnmodifiedNativeExpression() {
        compare(null)
        val years = listOf("0000", "0001", "1900", "2000", "2024", "2025", "9999", "-0001", "-0000", "+0001",
            "+00000", "+10000", "10000", "-999999999", "+999999999", "+1000000000", "+0000000001", "-0000000001")
        for (year in years) for (month in listOf("00", "01", "02", "04", "12", "13")) for (day in listOf("00", "01", "28", "29", "30", "31", "32")) {
            val date = "$year-$month-$day"
            for (clock in listOf("", "T10:20", "T10:20:30.123456789Z", " 10:20:30", " 24:00", " 24:00:00")) compare(date + clock)
        }
        for (clock in listOf("00:00", "23:59", "24:00", "10:20:30", "10:20:60", "10:20:30.", "10:20:30.1", "10:20:30.123456789", "10:20:30.1234567890", "10:20:30,1"))
            for (separator in listOf("T", "t", " ")) for (suffix in listOf("", "Z", "z", "ZZ", "+08", "+0800", "+08:00", "+08:00:30", "+18:00", "-18:00", "+18:00:01", "+19:00", " trailing", "\n", "\ud83d\ude00"))
                compare("2026-01-01$separator$clock$suffix")
        val base = "2026-01-01T10:20:30"
        for (index in 0..base.length) for (insert in listOf("Z", "ZZ", "z", "/", "\u0000"))
            compare(base.take(index) + insert + base.drop(index))
        for (unit in (0..255).toList() + (0x2000..0x2060).toList() + listOf(0x1680, 0x3000, 0xd800, 0xdc00, 0xfeff)) {
            val char = unit.toChar()
            compare("${char}2026-01-01T10:20:30Z$char")
        }
    }

    @Test fun seededFullDateMutationsPreserveParsingAndPrefixBehavior() {
        val random = Random(472190)
        repeat(5000) {
            val date = LocalDate.of(random.nextInt(-999999999, 1000000000), random.nextInt(1, 13), 1).plusDays(random.nextLong(35))
            val clock = "%02d:%02d:%02d".format(Locale.ROOT, random.nextInt(26), random.nextInt(62), random.nextInt(62))
            val separator = listOf("T", "t", " ")[random.nextInt(3)]
            val suffix = listOf("", "Z", "+08:00", ".123456789", ".0000000000Z", " extra", "\ud800")[random.nextInt(7)]
            val text = "$date$separator$clock$suffix"
            compare(if (random.nextBoolean()) text.replace('-', '/') else text)
        }
    }
}
