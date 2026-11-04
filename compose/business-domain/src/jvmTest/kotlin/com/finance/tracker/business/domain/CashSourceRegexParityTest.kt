package com.finance.tracker.business.domain

import kotlin.test.*

class CashSourceRegexParityTest {
    private fun original(pattern:String):Regex {
        val spaces=pythonWhitespace.joinToString("") {"\\x{${it.toString(16)}}"}
        val digits=pythonIntegerDigitStarts.joinToString("") {"\\x{${it.toString(16)}}-\\x{${(it+9).toString(16)}}"}
        return Regex("(?d)"+pattern.replace("\\s","[$spaces]").replace("\\S","[^$spaces]").replace("\\d","[$digits]"))
    }
    @Test fun sourcePatternRewritingMatchesOriginalUnixLinesAndPythonClasses() {
        val amount="(?:[¥￥]\\s*[+-]?[0-9][0-9,]*(?:\\.[0-9]+)?|[+-]?[0-9][0-9,]*(?:\\.[0-9]+)?\\s*元)"
        val patterns=listOf("\\s+","^\\d+/\\d+\\s*","^[\\s（(【\\[、,，\\-—]+","[\\s）)】\\]、,，\\-—]+$",
            "^(.+?)(?:外卖订单|订单|购物车)","[（(][^)）]*[)）]","^(.+?)\\s*-\\s*高德地图",
            "[（(\\[【]\\s*($amount)\\s*[）)\\]】]","分期\\s*[（(]\\s*\\d+\\s*期\\s*[）)]",
            "\\s*[（(]?\\d+\\s*期[）)]?","$amount$","(?:[（(](?:尾号)?([0-9]{4})[）)]|尾号([0-9]{4})|([0-9]{4}))$",
            "([\\u4e00-\\u9fff]+银行)([\\u4e00-\\u9fff]*卡)?","^(银行转证券|证券转银行|银转证|证转银)\\d+\\S*$","[:\\d\\s.-]+")
        val basic=listOf("","商户外卖订单","一\r订单","一\n订单","一\u2028订单","门店 - 高德地图","支付（¥12.34）","工商银行(1234)","abc\n","abc\r")
        val points=(pythonWhitespace.toList()+pythonIntegerDigitStarts.flatMap {listOf(it,it+9)}+listOf(0xd800,0xdc00,0x1f600)).distinct()
        val inputs=basic+points.flatMap {p ->
            val c=buildString {appendPythonCodePoint(p)}
            listOf(c,"${c}/${c}支付宝-商户","分期($c 期)","$c($c 期)","银行转证券${c}x",":$c.-", "a${c}订单", "12元$c")
        }
        fun matches(regex:Regex,input:String)=regex.findAll(input).map {m ->m.range to m.groups.map {it?.value}}.toList()
        for(pattern in patterns) {
            val before=original(pattern);val after=cashSourceEffectRegex(pattern)
            for(input in inputs) {
                assertEquals(before.matches(input),after.matches(input),"matches $pattern")
                assertEquals(matches(before,input),matches(after,input),"find $pattern")
                assertEquals(before.replace(input,""),after.replace(input,""),"replace $pattern")
            }
        }
    }
    @Test fun closedBrandVocabularyKeepsUnicodeIgnoreCaseLiteralReplacement() {
        for(keyword in listOf("LINLEE林里","KFC","Steam","7-ELEVEN","NewUhoo","PIXIV","DeepSeek","bilibili","支付宝")) {
            val before=Regex(Regex.escape(keyword),RegexOption.IGNORE_CASE)
            val after=cashSourceKeywordRegex(keyword)
            val cases=listOf(keyword,keyword.lowercase(),keyword.uppercase(),keyword.replace('I','İ'),keyword.replace('I','ı'),
                keyword.replace('S','ſ'),keyword.replace('K','K'))
            for(input in cases+cases.map {"$keyword / $it"}) assertEquals(before.replace(input,""),after.replace(input,""),keyword)
        }
    }
}
