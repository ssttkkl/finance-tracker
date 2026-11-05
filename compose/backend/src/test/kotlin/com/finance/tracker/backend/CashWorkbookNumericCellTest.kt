package com.finance.tracker.backend

import com.finance.tracker.business.domain.CashBankStatementTextReader
import com.finance.tracker.business.domain.CashPlatformStatementTextReader
import java.io.ByteArrayOutputStream
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.ss.usermodel.Workbook
import kotlin.test.Test
import kotlin.test.assertEquals

class CashWorkbookNumericCellTest {
    @Test fun realXlsxAndXlsCellsPreserveDistinctNumericPolicies() {
        val values = listOf(0.0, -0.0, 0.1, 0.0001, Double.fromBits(1), Double.fromBits(2),
            1e15, 1e16, 1e23, -1e23, Double.MAX_VALUE, -Double.MAX_VALUE)
        val platformRows = listOf(listOf("交易时间", "收/支", "金额(元)", "交易对方", "交易单号")) +
            values.mapIndexed { index, value -> listOf("2026-08-15 10:00:00", "支出", platformText(value), "原商户", "wx-$index") }
        val bankRows = listOf(listOf("中国建设银行个人活期账户全部交易明细"),
            listOf("序号", "交易日期", "交易金额", "账户余额")) + values.mapIndexed { index, value ->
                listOf((index + 1).toString(), "2026-08-15", bankText(value), "0") }
        val platform = XSSFWorkbook().use { workbook ->
            writeRows(workbook, platformRows, firstDataRow = 1, values)
            val bytes = ByteArrayOutputStream().also { workbook.write(it) }.toByteArray()
            CashStatementDocumentReader.readPlatformDocument(bytes, "wechat").also {
                assertEquals(it, com.finance.tracker.business.domain.readLocalCashXlsxDocument(bytes, "wechat"))
            }
        }
        val bank = HSSFWorkbook().use { workbook ->
            writeRows(workbook, bankRows, firstDataRow = 2, values)
            val bytes = ByteArrayOutputStream().also { workbook.write(it) }.toByteArray()
            BankStatementDocumentReader.readBankDocument(bytes, "ccb_debit")
        }
        assertEquals(CashPlatformStatementTextReader.collectRows(platformRows, "wechat", "sheet:0", true), platform)
        assertEquals(CashBankStatementTextReader.readCcb(bankRows), bank)
    }
    private fun writeRows(workbook: Workbook, rows: List<List<String>>, firstDataRow: Int, values: List<Double>) {
        val sheet = workbook.createSheet()
        for ((index, cells) in rows.withIndex()) {
            val row = sheet.createRow(index)
            for ((column, value) in cells.withIndex()) row.createCell(column).setCellValue(value)
            if (index >= firstDataRow) row.getCell(2).setCellValue(values[index - firstDataRow])
        }
    }
    private fun platformText(value: Double) = if (value.isFinite() && value == Math.rint(value) && Math.abs(value) < 1e16)
        value.toLong().toString() else value.toString()
    private fun bankText(value: Double) = if (value.isFinite() && value == Math.rint(value)) value.toLong().toString() else value.toString()
}
