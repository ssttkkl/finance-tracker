package com.finance.tracker.business.domain

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformStatementTextPrimitiveParityTest {
    @Test fun blankCellsAndReplacementUtf8MatchOriginalJvmForAllUtf16Units() {
        for (unit in Char.MIN_VALUE..Char.MAX_VALUE) {
            for (text in listOf(unit.toString(), "a$unit", "$unit$unit")) {
                assertEquals(text.isBlank(), platformStatementCellBlank(text), "blank ${unit.code}")
                assertEquals(text.toByteArray(StandardCharsets.UTF_8).size.toLong(), platformStatementUtf8Size(text), "utf8 ${unit.code}")
            }
        }
        for (text in listOf("𐒠😀", "\ud800\ud800\udc00\udc00", "\udfff\ud800\udc00", "A\ud800B")) {
            assertEquals(text.toByteArray(StandardCharsets.UTF_8).size.toLong(), platformStatementUtf8Size(text))
        }
    }

    @Test fun declarationPatternsKeepOriginalAsciiAndMultilineSemantics() {
        val count = "(?m)^\\s*共\\s*(\\d[\\d,]*)\\s*笔(?:记录|交易)?"
        val patterns = listOf(count, "^\\s*微信昵称\\s*[:：]\\s*(?:\\[([^\\]]*)\\]|【([^】]*)】|([^\\r\\n,]*))",
            "(?:^|(?<=[\\]】,]))\\s*导出类型\\s*[:：]\\s*(?:\\[([^\\]]*)\\]|【([^】]*)】|([^\\r\\n,]*))")
        val prefixes = (pythonWhitespace.toList() + listOf(0xfeff, 0x30, 0x1f600)).map { buildString { appendPythonCodePoint(it) } }
        val inputs = prefixes.flatMap { prefix -> listOf("${prefix}共1笔记录", "a${prefix}共1笔记录", "a${prefix}${prefix}共1笔记录",
            "${prefix}微信昵称：[原文]", "】${prefix}导出类型：[全部]", "${prefix}共١笔记录") } + listOf("a\r\n共1笔记录", "a\r共1笔记录", "共1笔记录\r\n共2笔记录")
        fun matches(regex: Regex, input: String) = regex.findAll(input).map { it.range to it.groupValues }.toList()
        for (pattern in patterns) for (input in inputs) {
            assertEquals(matches(Regex(pattern), input), matches(platformStatementPattern(pattern), input), "$pattern $input")
        }
    }
}
