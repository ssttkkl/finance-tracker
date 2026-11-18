package com.finance.tracker.backend

import com.finance.tracker.business.domain.cashWorkbookIsDateFormat
import org.apache.poi.ss.usermodel.BuiltinFormats
import org.apache.poi.ss.usermodel.DateUtil
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class CashWorkbookDateFormatParityTest {
    @Test fun completeBuiltinCatalogAndSmallFormatLanguageMatchPoi() {
        for (id in 0..255) verify(id, BuiltinFormats.getBuiltinFormat(id))
        val alphabet = "ymdhsTAMP0[]\\;@-: "
        fun visit(text: String, remaining: Int) {
            verify(164, text)
            if (remaining > 0) for (char in alphabet) visit(text + char, remaining - 1)
        }
        visit("",4)
    }
    @Test fun prefixesUnicodeAndFixedRandomFormatsMatchPoi() {
        val random = Random(5112026)
        val alphabet = "ymdhsTAMP0[]\\;@-: ,." + '$' + "年日月☺Σ\r\n\u0085\u2028\u2029\uD800"
        val prefixes = listOf("","[Red]","[DBNum1]","[DBNum3][$-409][Yellow]","[$-\n]","[HH]","[DBNum4]")
        val indices = listOf(-1,0,14,22,23,45,47,48,164,65535)
        repeat(10_000) {
            val text = prefixes[random.nextInt(prefixes.size)] + buildString {
                repeat(random.nextInt(64)) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            verify(indices[random.nextInt(indices.size)],text)
        }
        for (prefix in prefixes) for (body in listOf("yyyy-MM-dd","hh:mm:ss.000","[h]","m00amp","General","")) {
            for (id in indices) verify(id,prefix+body)
        }
        for (id in indices) {verify(id,null);verify(id,"")}
    }
    @Test fun actualNumericCellsPreserveTheOriginalValueGateAndStyleLookup() {
        org.apache.poi.xssf.usermodel.XSSFWorkbook().use { workbook ->
            val cell=workbook.createSheet().createRow(0).createCell(0)
            val formats=listOf("General","yyyy-mm-dd","[DBNum1][$-409][Red]yyyy年mm月dd日","[h]","0.00","yyyy-mm-dd G")
            val styles=formats.map { text ->workbook.createCellStyle().also {it.dataFormat=workbook.createDataFormat().getFormat(text)} } +
                listOf(0,14,22,45,47).map { id ->workbook.createCellStyle().also {it.dataFormat=id.toShort()} }
            for (value in listOf(-1.0,-Double.fromBits(1),-0.0,0.0,Double.fromBits(1),1.0,45000.123456789,Double.MAX_VALUE)) {
                cell.setCellValue(value)
                for(style in styles) {
                    cell.cellStyle=style
                    assertEquals(DateUtil.isCellDateFormatted(cell),cashWorkbookDateFormatted(cell),"bits=${value.toBits()} style=${style.dataFormat}")
                }
            }
        }
    }
    private fun verify(id:Int,text:String?) = assertEquals(DateUtil.isADateFormat(id,text),
        cashWorkbookIsDateFormat(id,text),"format=$id text=$text")
}
