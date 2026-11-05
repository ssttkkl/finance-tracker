package com.finance.tracker.backend

import java.io.ByteArrayInputStream
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory

/** 建行借记卡 XLS 与工银亚洲 UTF-16 TSV 的一次读取。 */
internal object BankStatementDocumentReader {
    private val OLE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    private const val MAX_PHYSICAL_ROWS = 200_000
    private const val MAX_COLUMNS = 128

    fun readBankDocument(content: ByteArray, source: String? = null): CashStatementDocument {
        if (content.isEmpty()) fail("statement_input_invalid")
        val detected = when {
            content.size >= OLE.size && OLE.indices.all { content[it] == OLE[it] } -> "ccb_debit"
            content.size >= 2 && ((content[0] == 0xFF.toByte() && content[1] == 0xFE.toByte()) ||
                (content[0] == 0xFE.toByte() && content[1] == 0xFF.toByte())) -> "icbc_asia"
            else -> null
        }
        if (detected == null || source != null && source != detected) fail("statement_source_unsupported")
        return try {
            if (detected == "ccb_debit") readCcb(content) else readAsia(content)
        } catch (failure: StatementDocumentFailure) { throw failure }
        catch (_: Exception) { fail("statement_unreadable") }
    }

    private fun fail(code: String): Nothing = throw StatementDocumentFailure(code)

    private fun readCcb(content: ByteArray): CashStatementDocument {
        if (content.size % 512 != 0) fail("statement_unreadable")
        return WorkbookFactory.create(ByteArrayInputStream(content)).use { workbook ->
            if (workbook.numberOfSheets == 0) fail("statement_unreadable")
            val sheet = workbook.getSheetAt(0)
            if (sheet.lastRowNum + 1 > MAX_PHYSICAL_ROWS || sheet.lastRowNum >= 0 &&
                (0..sheet.lastRowNum).any { (sheet.getRow(it)?.lastCellNum?.toInt() ?: 0) > MAX_COLUMNS })
                fail("statement_rows_too_large")
            val records = (0..sheet.lastRowNum).map { rowIndex ->
                val row = sheet.getRow(rowIndex)
                if (row == null) emptyList() else (0 until maxOf(0, row.lastCellNum.toInt())).map { column ->
                    cellText(row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL))
                }
            }
            com.finance.tracker.business.domain.CashBankStatementTextReader.readCcb(records)
        }
    }

    private fun readAsia(content: ByteArray): CashStatementDocument =
        com.finance.tracker.business.domain.readIcbcAsiaStatementDocument(content)

    private fun cellText(cell: Cell?): String {
        if (cell == null) return ""
        return when (cell.cellType) {
            CellType.BLANK -> ""
            CellType.STRING -> cell.stringCellValue
            CellType.BOOLEAN -> if (cell.booleanCellValue) "True" else "False"
            CellType.NUMERIC ->
                if (cashWorkbookDateFormatted(cell))
                    com.finance.tracker.business.domain.cashCcbWorkbookDateText(cell.numericCellValue, cashWorkbookUses1904(cell))
                else com.finance.tracker.business.domain.cashCcbWorkbookNumberText(cell.numericCellValue)
            CellType.FORMULA -> "=" + cell.cellFormula
            else -> ""
        }
    }
}
