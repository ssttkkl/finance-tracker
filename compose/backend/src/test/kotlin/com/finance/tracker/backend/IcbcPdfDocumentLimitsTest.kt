package com.finance.tracker.backend

import java.util.Base64
import kotlin.test.*
import kotlinx.serialization.json.*

class IcbcPdfDocumentLimitsTest {
    private fun content(name: String): ByteArray {
        val cases = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/python-icbc-pdf-documents.json"))
            .bufferedReader().use { it.readText() }).jsonArray
        val item = cases.map { it.jsonObject }.first { it.getValue("name").jsonPrimitive.content == name }
        return Base64.getDecoder().decode(item.getValue("content").jsonPrimitive.content)
    }

    private fun failureCode(content: ByteArray, limits: IcbcPdfDocumentReader.IcbcPdfLimits): String =
        assertFailsWith<StatementDocumentFailure> {
            IcbcPdfDocumentReader.readIcbcDocument(content, limits = limits)
        }.code

    @Test fun budgetsFailSafely() {
        val credit = content("credit")
        val twoPages = content("two-pages")
        assertEquals("statement_rows_too_large", failureCode(twoPages, IcbcPdfDocumentReader.IcbcPdfLimits(maxPdfPages = 1)))
        assertEquals("statement_rows_too_large", failureCode(credit, IcbcPdfDocumentReader.IcbcPdfLimits(maxPdfChars = 1)))
        assertEquals("statement_rows_too_large", failureCode(credit, IcbcPdfDocumentReader.IcbcPdfLimits(maxParsedBytes = 1)))
        assertEquals("statement_rows_too_large", failureCode(credit, IcbcPdfDocumentReader.IcbcPdfLimits(maxPhysicalRows = 1)))
        assertEquals("statement_rows_too_large", failureCode(credit, IcbcPdfDocumentReader.IcbcPdfLimits(maxRows = 0)))
        assertEquals("statement_rows_too_large", failureCode(credit, IcbcPdfDocumentReader.IcbcPdfLimits(maxTablesPerPage = 0)))
        assertEquals("statement_header_invalid", failureCode(credit, IcbcPdfDocumentReader.IcbcPdfLimits(maxColumns = 5)))
        assertEquals("statement_input_invalid", failureCode(credit, IcbcPdfDocumentReader.IcbcPdfLimits(maxInputBytes = 10)))
    }
}
