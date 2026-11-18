package com.finance.tracker.business.domain

import kotlin.test.*

class CashWorksheetXmlGuardTest {
    private fun CashWorksheetXmlGuard.element(name:String,coordinate:String?=null) = start(name) {if(it=="r") coordinate else null}
    private fun rejected(code:String,block:()->Unit) = assertEquals(code,assertFailsWith<StatementDocumentFailure>(block=block).code)

    @Test fun implicitCoordinatesAndLastSupportedColumnAreAccepted() {
        val guard=CashWorksheetXmlGuard()
        guard.element("worksheet");guard.element("sheetData")
        guard.element("row");guard.element("c","A1");guard.end();guard.element("c","DX1");guard.end();guard.end()
        guard.element("row");guard.element("c");guard.end();guard.end();guard.end();guard.end()
    }
    @Test fun rowBoundsOrderingAndIntegerSyntaxRetainTheirErrors() {
        for (value in listOf("0","-1","1.0","200001","12345678","١"))
            rejected("statement_rows_too_large") {CashWorksheetXmlGuard().element("row",value)}
        rejected("statement_rows_too_large") {CashWorksheetXmlGuard().element("row","")}
        for(value in listOf("1","0000001")) {
            val guard=CashWorksheetXmlGuard();guard.element("row","1");guard.end()
            rejected("statement_rows_too_large") {guard.element("row",value)}
        }
        val guard=CashWorksheetXmlGuard();guard.element("row","200000");guard.end()
        rejected("statement_rows_too_large") {guard.element("row")}
    }
    @Test fun cellBoundsAndCoordinatesRetainTheirErrorPriority() {
        fun check(coordinate:String,code:String) {
            val guard=CashWorksheetXmlGuard();guard.element("row","1")
            rejected(code) {guard.element("c",coordinate)}
        }
        for(value in listOf("a1","A0x","A","ZZZZ1","A12345678","Ａ1"))check(value,"statement_header_invalid")
        check("DY1","statement_header_invalid");check("ZZZ200001","statement_rows_too_large")
        check("A200001","statement_rows_too_large");check("A0","statement_rows_too_large")
        check("A2","statement_unreadable")
        rejected("statement_unreadable") {CashWorksheetXmlGuard().element("c")}
        val duplicate=CashWorksheetXmlGuard();duplicate.element("row","1");duplicate.element("c","A1");duplicate.end()
        rejected("statement_unreadable") {duplicate.element("c","A1")}
    }
    @Test fun columnAndDepthBudgetsUsePhysicalEvents() {
        val guard=CashWorksheetXmlGuard();guard.element("row","1")
        repeat(128) {guard.element("c");guard.end()}
        rejected("statement_header_invalid") {guard.element("c")}
        val deep=CashWorksheetXmlGuard();repeat(128) {deep.element("wrapper")}
        rejected("statement_unreadable") {deep.element("wrapper")}
    }

    @Test fun emptyRowIndexKeepsUpstreamBudgetError() {
        val guard = CashWorksheetXmlGuard()
        guard.start("worksheet") { null }
        assertEquals("statement_rows_too_large", assertFailsWith<StatementDocumentFailure> {
            guard.start("row") { if (it == "r") "" else null }
        }.code)
    }

    @Test fun implicitColumnOverflowKeepsUpstreamHeaderError() {
        val guard = CashWorksheetXmlGuard()
        guard.start("worksheet") { null }
        guard.start("row") { if (it == "r") "1" else null }
        repeat(128) { guard.start("c") { null }; guard.end() }
        assertEquals("statement_header_invalid", assertFailsWith<StatementDocumentFailure> {
            guard.start("c") { null }
        }.code)
    }
}
