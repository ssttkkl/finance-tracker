package com.finance.tracker.backend

import com.finance.tracker.business.domain.CashBankStatementTextReader
import com.finance.tracker.business.domain.CashPlatformStatementTextReader
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter
import org.apache.poi.hssf.record.DateWindow1904Record
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Workbook
import kotlin.test.Test
import kotlin.test.assertEquals

class CashWorkbookDateCellTest {
    @Test fun realXlsxAndXlsDateCellsUseTheirStoredWindowAndOriginalSourceText() {
        val values = listOf(0.0,1.0,59.0,60.0,61.0,1.5,45000.123456789,0.9999999999,
            2147483647.9999998,2147483648.1,Double.MAX_VALUE)
        for (window in listOf(false,true)) {
            val platformRows = listOf(listOf("交易时间", "收/支", "金额(元)", "交易对方", "交易单号")) + values.mapIndexed { index,value ->
                listOf(DateUtil.getLocalDateTime(value,window).toString().replace('T',' '),"支出","20.00","原商户","wx-$index") }
            val bankRows = listOf(listOf("中国建设银行个人活期账户全部交易明细"), listOf("序号","交易日期","交易金额","账户余额")) +
                values.mapIndexed { index,value -> listOf((index+1).toString(),
                    DateUtil.getLocalDateTime(value,window).format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")),"20.00","0") }
            val platform = XSSFWorkbook().use { workbook ->
                val props = workbook.ctWorkbook.workbookPr ?: workbook.ctWorkbook.addNewWorkbookPr()
                props.date1904 = window
                fill(workbook,platformRows,firstDataRow=1,dateColumn=0,values)
                val bytes=ByteArrayOutputStream().also {workbook.write(it)}.toByteArray()
                CashStatementDocumentReader.readPlatformDocument(bytes,"wechat").also {
                    assertEquals(it, com.finance.tracker.business.domain.readLocalCashXlsxDocument(bytes, "wechat"))
                }
            }
            val bank = HSSFWorkbook().use { workbook ->
                workbook.workbook.records.filterIsInstance<DateWindow1904Record>().single().windowing=(if(window)1 else 0).toShort()
                fill(workbook,bankRows,firstDataRow=2,dateColumn=1,values)
                val bytes=ByteArrayOutputStream().also {workbook.write(it)}.toByteArray()
                BankStatementDocumentReader.readBankDocument(bytes,"ccb_debit")
            }
            assertEquals(CashPlatformStatementTextReader.collectRows(platformRows,"wechat","sheet:0",true),platform,"xlsx 1904=$window")
            assertEquals(CashBankStatementTextReader.readCcb(bankRows),bank,"xls 1904=$window")
        }
    }
    private fun fill(workbook:Workbook,rows:List<List<String>>,firstDataRow:Int,dateColumn:Int,values:List<Double>) {
        val sheet=workbook.createSheet()
        val style=workbook.createCellStyle().also {it.dataFormat=workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss.000")}
        for((index,cells) in rows.withIndex()) {
            val row=sheet.createRow(index)
            for((column,value) in cells.withIndex())row.createCell(column).setCellValue(value)
            if(index>=firstDataRow)row.getCell(dateColumn).apply {setCellValue(values[index-firstDataRow]);cellStyle=style}
        }
    }
}
