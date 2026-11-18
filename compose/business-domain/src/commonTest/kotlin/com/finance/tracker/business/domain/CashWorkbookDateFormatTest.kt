package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class CashWorkbookDateFormatTest {
    @Test fun builtInDateIdsTakePrecedenceEvenWithoutAFormatString() {
        for (id in (14..22) + (45..47)) for (text in listOf(null,"","General","invalid")) {
            assertTrue(cashWorkbookIsDateFormat(id,text),"$id $text")
        }
        for (id in listOf(-1,0,13,23,44,48,164,65535)) {
            assertFalse(cashWorkbookIsDateFormat(id,null))
            assertFalse(cashWorkbookIsDateFormat(id,"General"))
        }
    }
    @Test fun sourceDateFormatsKeepOriginalPrefixesEscapesAndFirstSection() {
        for (text in listOf("yyyy-MM-dd","mm/dd/yy","hh:mm:ss.000","[h]","[m]","[s]","[hh]:mm",
            "[$-409]m/d/yy","[DBNum1]yyyy年m月d日","[Red]yyyy-mm-dd","dd/mm/yy;[red]dd/mm/yy",
            "yyyy\\-mm\\-dd","yyyy/mm/dd;@","YMDHST", "\\m", "]m[", "m0am", "mamp", "[]AM")) {
            assertTrue(cashWorkbookIsDateFormat(164,text),text)
        }
    }
    @Test fun longFormatSegmentsKeepAllPrefixAndSuffixPositions() {
        val zeros="0".repeat(100_000)
        assertTrue(cashWorkbookIsDateFormat(164,"m"+zeros+"AMP"))
        assertFalse(cashWorkbookIsDateFormat(164,"m"+zeros+"AMP0"))
        assertTrue(cashWorkbookIsDateFormat(164,"[DBNum1][$-409][Red]yyyy-mm-dd;"+"x".repeat(100_000)))
    }
    @Test fun numericLiteralAndInvalidSuffixesDoNotBecomeDateFormats() {
        for (text in listOf(null,"","General","0.00","0%","[Red]0.00","yyyy-MM-dd G","[DBNum4]yyyy-mm-dd",
            "yyyy-mm-dd\n","[$-409]\nyyyy","yy;",";yy","[h]foobar","[hh]00","m0m0","年月日","\\q")) {
            assertFalse(cashWorkbookIsDateFormat(164,text),text)
        }
    }
}
