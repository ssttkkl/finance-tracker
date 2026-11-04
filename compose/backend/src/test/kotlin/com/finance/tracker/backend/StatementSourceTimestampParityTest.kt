package com.finance.tracker.backend

import java.time.*
import kotlin.test.Test
import kotlin.test.assertEquals

class StatementSourceTimestampParityTest {
    @Test fun completeSourceTimeNormalizationMatchesOldJvmAtZone() {
        val pairs = mutableListOf<Pair<String, String>>()
        for ((source, zone) in listOf("wechat" to "Asia/Shanghai", "icbc_asia" to "Asia/Hong_Kong")) {
            for (transition in ZoneId.of(zone).rules.transitions) {
                for (boundary in listOf(transition.dateTimeBefore, transition.dateTimeAfter)) {
                    for (offset in listOf(-86400L, -7200L, -3600L, -1L, 0L, 1L, 1800L, 3599L, 3600L, 7200L, 86400L)) {
                        pairs += source to boundary.plusSeconds(offset).toString()
                        pairs += source to boundary.plusSeconds(offset).withNano(123456789).toString()
                    }
                }
            }
        }
        val forms = listOf("2025-01-01T12:34:56", "20250101", "20250101T123456,123456789+0800",
            "2025-01-01T12:34:56+02", "2025-01-01Z12:34", "2025-W01-1T01:00:00", "2025W011T010203",
            "0001-01-01T00:00:00", "9999-12-31T23:59:59.999999", "2025-01-01T12:34:56Z",
            "2025-01-01T12:34:56+00:00:01.123456", "\u001c2025-01-01 12:34:56\u0085", "2025-01-01😀12:34:56",
            "", "bad", "2025-02-29T00:00:00", "0000-01-01T00:00:00", "9999-12-31T23:59:59-01:00",
            "2025-01-01T24:00:00", "20250101T123456\u0085+0800")
        for (source in listOf("alipay", "wechat", "icbc_credit", "icbc_debit", "ccb_debit", "icbc_asia", "unknown")) {
            for (form in forms) pairs += source to form
        }
        for ((source, input) in pairs) {
            val before = runCatching { oldNormalizeStatementTimestamp(input, source) }
            val after = runCatching { normalizeStatementTimestamp(input, source) }
            assertEquals(before.getOrNull(), after.getOrNull(), "$source $input")
            assertEquals(before.exceptionOrNull()?.javaClass, after.exceptionOrNull()?.javaClass, "$source $input error")
            assertEquals(before.exceptionOrNull()?.message, after.exceptionOrNull()?.message, "$source $input message")
        }
    }
}

private val statementSourceTimezones = mapOf("alipay" to "Asia/Shanghai", "wechat" to "Asia/Shanghai",
    "icbc_credit" to "Asia/Shanghai", "icbc_debit" to "Asia/Shanghai", "ccb_debit" to "Asia/Shanghai",
    "icbc_asia" to "Asia/Hong_Kong")

private val explicitStatementOffset = Regex("(?:Z|[+-]\\d{2}(?::?\\d{2})?)$")
private val basicStatementDate = Regex("\\d{8}")
private val basicStatementDateTime = Regex("(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})(.*)")
private val compactStatementOffset = Regex("([+-]\\d{2})(\\d{2})$")
private val hourOnlyStatementOffset = Regex("([+-]\\d{2})$")

/** 来源时间按渠道时区解释并输出 UTC；无 offset 的时间不使用本机时区。 */
private fun oldNormalizeStatementTimestamp(value: String, source: String): String {
    var text = value.trim { it.code in pythonWhitespace }.replace(',', '.')
    if (basicStatementDate.matches(text)) text = "${text.take(4)}-${text.substring(4, 6)}-${text.substring(6, 8)}"
    else basicStatementDateTime.matchEntire(text)?.let {
        text = "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}T" +
            "${it.groupValues[4]}:${it.groupValues[5]}:${it.groupValues[6]}${it.groupValues[7]}"
    }
    text = compactStatementOffset.replace(text) { "${it.groupValues[1]}:${it.groupValues[2]}" }
    text = hourOnlyStatementOffset.replace(text) { "${it.groupValues[1]}:00" }
    if (text.length > 10 && text[10] == ' ') text = text.substring(0, 10) + "T" + text.substring(11)
    val zone = ZoneId.of(statementSourceTimezones[source] ?: "UTC")
    val instant = try {
        try {
            val parsed = pythonCashTimestamp(text)
            if (explicitStatementOffset.containsMatchIn(text) || text.endsWith("Z")) parsed
            else LocalDateTime.ofInstant(parsed, ZoneOffset.UTC).atZone(zone).toInstant()
        } catch (_: InvalidCashValue) {
            val parsed = pythonCashTimestamp(text, allowNaive = true)
            LocalDateTime.ofInstant(parsed, ZoneOffset.UTC).atZone(zone).toInstant()
        }
    } catch (_: InvalidCashValue) {
        throw IllegalArgumentException("invalid timestamp")
    }
    return manualInstantText(instant)
}
