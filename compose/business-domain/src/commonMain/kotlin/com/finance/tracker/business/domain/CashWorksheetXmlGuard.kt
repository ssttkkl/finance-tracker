package com.finance.tracker.business.domain

/** Worksheet coordinate budgets over parser events; XML syntax and byte budgets belong to the reader. */
class CashWorksheetXmlGuard {
    private var depth=0
    private var rows=0
    private var cells=0
    private var currentRow=0
    private var lastColumn=0

    fun start(name:String,attribute:(String)->String?) {
        depth++
        if(depth>MAX_DEPTH)fail("statement_unreadable")
        when(name) {
            "row"->{
                rows++;cells=0;lastColumn=0
                val index=attribute("r") ?: (currentRow+1).toString()
                if(!index.all {it in '0'..'9'} || index.length>7)fail("statement_rows_too_large")
                if(index.isEmpty())fail("statement_unreadable")
                val value=index.toInt()
                if(value !in 1..MAX_PHYSICAL_ROWS || rows>MAX_PHYSICAL_ROWS || value<=currentRow)fail("statement_rows_too_large")
                currentRow=value
            }
            "c"->{
                cells++
                val coordinate=attribute("r")
                var column=lastColumn+1
                if(coordinate!=null) {
                    val match=coordinatePattern.matchEntire(coordinate) ?: fail("statement_header_invalid")
                    column=0
                    for(letter in match.groupValues[1])column=column*26+(letter-'A'+1)
                    val rowIndex=match.groupValues[2].toInt()
                    if(column>MAX_COLUMNS)fail("statement_header_invalid")
                    if(rowIndex !in 1..MAX_PHYSICAL_ROWS)fail("statement_rows_too_large")
                    if(rowIndex!=currentRow)fail("statement_unreadable")
                }
                if(currentRow==0 || column<=lastColumn || cells>MAX_COLUMNS)fail("statement_unreadable")
                lastColumn=column
            }
        }
    }

    fun end() {depth--}

    private fun fail(code:String):Nothing=throw StatementDocumentFailure(code)
    private companion object {
        const val MAX_DEPTH=128
        const val MAX_COLUMNS=128
        const val MAX_PHYSICAL_ROWS=200_000
        val coordinatePattern=Regex("([A-Z]{1,3})([0-9]{1,7})")
    }
}
