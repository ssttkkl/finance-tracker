package com.finance.tracker.business.domain

import kotlin.test.*
import no.synth.kmpzip.io.ByteArrayOutputStream
import no.synth.kmpzip.zip.ZipEntry
import no.synth.kmpzip.zip.ZipOutputStream

class CashXlsxArchiveValidationTest {
    private val sheetNamespace = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private val relationshipNamespace = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/"
    private fun worksheet(body: String): String = """<worksheet xmlns="$sheetNamespace"><sheetData>$body</sheetData></worksheet>"""
    private fun row(index: Int, cells: List<String>): String = "<row r=\"$index\">" + cells.mapIndexed { column, text ->
        "<c r=\"${'A' + column}$index\" t=\"inlineStr\"><is><t>$text</t></is></c>"
    }.joinToString("") + "</row>"
    private fun workbook(extras: Map<String, String> = emptyMap()): ByteArray {
        val entries = linkedMapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/></Types>""",
            "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="root" Type="${relationshipNamespace}officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<workbook xmlns="$sheetNamespace" xmlns:r="${relationshipNamespace.removeSuffix("/")}"><sheets><sheet name="Selected" sheetId="1" r:id="sheet"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="sheet" Type="${relationshipNamespace}worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to worksheet(
                row(1, listOf("交易时间", "收/支", "金额(元)", "交易对方", "交易单号")) +
                    row(2, listOf("2026-10-01 12:00:00", "支出", "12.30", "Synthetic merchant", "source-1")),
            ),
        ) + extras
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name)); val content = text.encodeToByteArray()
                zip.write(content, 0, content.size); zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test fun malformedUnusedXmlAndRelationshipsRejectTheCompleteWorkbook() {
        val valid = readLocalCashXlsxDocument(workbook(), "wechat")
        assertEquals(1, valid.rows.size); assertEquals("12.30", valid.rows.single().payload?.get("金额(元)"))
        for (path in listOf("unused.xml", "custom/data.xml", "xl/worksheets/sheet2.xml", "metadata/unused.rels"))
            for (xml in listOf("<metadata><broken></metadata>", "<metadata/><second/>", "<metadata value=\"&unknown;\"/>",
                "<!DOCTYPE metadata SYSTEM 'https://never-read.invalid'><metadata/>")) {
                val bytes = workbook(mapOf(path to xml)); val before = bytes.copyOf()
                assertEquals("statement_unreadable", assertFailsWith<StatementDocumentFailure>("$path: $xml") {
                    readLocalCashXlsxDocument(bytes, "wechat")
                }.code)
                assertContentEquals(before, bytes)
            }
    }

    @Test fun allWorksheetRootsUseTheSameCoordinateBudgetsRegardlessOfPathOrSelection() {
        val cases = listOf(
            "<row r=\"200001\"/>" to "statement_rows_too_large",
            "<row r=\"\"/>" to "statement_rows_too_large",
            "<row r=\"1\"><c r=\"DY1\"/></row>" to "statement_header_invalid",
            "<row r=\"1\"><c r=\"A2\"/></row>" to "statement_unreadable",
            "<row r=\"1\"><c r=\"A1\"/><c r=\"A1\"/></row>" to "statement_unreadable",
            "<row r=\"1\">" + "<c/>".repeat(129) + "</row>" to "statement_header_invalid",
        )
        for (path in listOf("custom/unselected.xml", "xl/worksheets/sheet2.xml", "custom/worksheet.rels")) for ((body, error) in cases) {
            assertEquals(error, assertFailsWith<StatementDocumentFailure>("$path: $body") {
                readLocalCashXlsxDocument(workbook(mapOf(path to worksheet(body))), "wechat")
            }.code)
        }
    }

    @Test fun validUnusedPartsDoNotBecomeStatementDataOrApplyWorksheetRulesToOtherRoots() {
        val expected = readLocalCashXlsxDocument(workbook(), "wechat")
        val extra = mapOf(
            "custom/unused.xml" to "<metadata><row r=\"999999999\"><c r=\"invalid\"/></row></metadata>",
            "custom/unselected.xml" to worksheet("<row r=\"1\"><c r=\"A1\"><f>SUM(B1:B2)</f><v>900</v></c></row>"),
            "metadata/unused.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>",
        )
        assertEquals(expected, readLocalCashXlsxDocument(workbook(extra), "wechat"))
    }
}
