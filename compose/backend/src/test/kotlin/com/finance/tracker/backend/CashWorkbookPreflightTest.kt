package com.finance.tracker.backend

import com.finance.tracker.business.domain.CashPlatformStatementTextReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CashWorkbookPreflightTest {
    private fun workbook(count:Int):Pair<ByteArray,List<List<String>>> {
        val rows=listOf(listOf("交易时间","收/支","金额(元)","交易对方","交易单号")) + List(count) { index ->
            listOf("2026-08-15 10:00:00","支出","20.00","合成商户","wx-$index") }
        val bytes=XSSFWorkbook().use { book ->
            val sheet=book.createSheet()
            rows.forEachIndexed { index,cells ->val row=sheet.createRow(index);cells.forEachIndexed { col,text ->row.createCell(col).setCellValue(text) } }
            ByteArrayOutputStream().also {book.write(it)}.toByteArray()
        }
        return bytes to rows
    }
    private fun rewrite(content:ByteArray,change:(String,ByteArray)->ByteArray,extra:(ZipOutputStream)->Unit={}):ByteArray {
        val target=ByteArrayOutputStream()
        ZipOutputStream(target).use { output ->
            ZipInputStream(ByteArrayInputStream(content)).use { input ->
                while(true) {
                    val entry=input.nextEntry ?: break
                    output.putNextEntry(ZipEntry(entry.name));output.write(change(entry.name,input.readBytes()));output.closeEntry()
                }
            }
            extra(output)
        }
        return target.toByteArray()
    }
    @Test fun legitimateWorksheetBeyond64KiBStillReturnsItsCompleteDocument() {
        val (content,rows)=workbook(1500)
        var worksheetSize=0
        val checked=rewrite(content,{name,bytes ->if(name=="xl/worksheets/sheet1.xml")worksheetSize=bytes.size;bytes})
        assertTrue(worksheetSize>65_536)
        assertEquals(CashPlatformStatementTextReader.collectRows(rows,"wechat","sheet:0",true),
            CashStatementDocumentReader.readPlatformDocument(checked,"wechat"))
    }
    @Test fun coordinateAfterFirst64KiBReceivesTheActualBudgetFailure() {
        val (content,_)=workbook(1500)
        val changed=rewrite(content,{name,bytes ->if(name=="xl/worksheets/sheet1.xml")
            bytes.decodeToString().replace("</sheetData>","<row r=\"200001\"/></sheetData>").encodeToByteArray() else bytes})
        assertEquals("statement_rows_too_large",assertFailsWith<StatementDocumentFailure> {
            CashStatementDocumentReader.readPlatformDocument(changed,"wechat")
        }.code)
    }
    @Test fun unknownLengthDirectoryWithContentIsRejectedBeforePoi() {
        val changed=rewrite(workbook(1).first,{_,bytes->bytes}) { output ->
            output.putNextEntry(ZipEntry("unexpected/"));output.write(byteArrayOf(1));output.closeEntry()
        }
        assertEquals("statement_unreadable",assertFailsWith<StatementDocumentFailure> {
            CashStatementDocumentReader.readPlatformDocument(changed,"wechat")
        }.code)
    }
    @Test fun emptyDirectoryKeepsTheOriginalCompleteDocument() {
        val (content,rows)=workbook(1)
        val changed=rewrite(content,{_,bytes->bytes}) { output ->
            output.putNextEntry(ZipEntry("optional/"));output.closeEntry()
        }
        assertEquals(CashPlatformStatementTextReader.collectRows(rows,"wechat","sheet:0",true),
            CashStatementDocumentReader.readPlatformDocument(changed,"wechat"))
    }
    @Test fun actualAggregateInflationStopsAt100MiBWithoutTrustingZipHeaderSizes() {
        val changed=rewrite(workbook(1).first,{_,bytes->bytes}) { output ->
            output.putNextEntry(ZipEntry("padding.xml"))
            val chunk=ByteArray(1024*1024) {'x'.code.toByte()}
            repeat(101) {output.write(chunk)}
            output.closeEntry()
        }
        assertTrue(changed.size<1024*1024)
        assertEquals("statement_rows_too_large",assertFailsWith<StatementDocumentFailure> {
            CashStatementDocumentReader.readPlatformDocument(changed,"wechat")
        }.code)
    }
}
