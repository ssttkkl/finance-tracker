package com.finance.tracker.backend

import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.Date1904Support

/** The existing workbook parser owns the date system; a device timezone never selects it. */
internal fun cashWorkbookUses1904(cell: Cell): Boolean = when (val workbook = cell.sheet.workbook) {
    is Date1904Support -> workbook.isDate1904
    is HSSFWorkbook -> workbook.workbook.isUsing1904DateWindowing
    else -> error("unsupported workbook date system")
}
