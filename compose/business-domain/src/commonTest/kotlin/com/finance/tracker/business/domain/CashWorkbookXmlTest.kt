package com.finance.tracker.business.domain

import kotlin.test.*
import nl.adaptivity.xmlutil.EventType

class CashWorkbookXmlTest {
    @Test fun attributeLookupUsesTheExactSchemaNamespace() {
        visitCashWorkbookXml("""<a xmlns:foreign="urn:unrelated" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" foreign:t="b" t="s" foreign:r="B2" r="A1" foreign:id="wrong" r:id="correct"/>""".encodeToByteArray()) { reader, event ->
            if (event == EventType.START_ELEMENT) {
                assertEquals("s", reader.cashAttribute("t"))
                assertEquals("A1", reader.cashAttribute("r"))
                assertNull(reader.cashAttribute("id"))
                assertEquals("correct", reader.cashAttribute("id", "http://schemas.openxmlformats.org/officeDocument/2006/relationships"))
            }
        }
    }
    @Test fun xmlEventsKeepEntityTextAndSupplementaryCharacters() {
        val text = StringBuilder()
        visitCashWorkbookXml("<a>A&amp;B&#x10400;<![CDATA[Σ]]></a>".encodeToByteArray()) { reader, event ->
            if (event in setOf(EventType.TEXT, EventType.CDSECT, EventType.ENTITY_REF)) text.append(reader.text)
        }
        assertEquals("A&B𐐀Σ", text.toString())
    }
    @Test fun declarationsMalformedBytesAndDepthFailBeforePublishingAResult() {
        for (text in listOf("<!DOCTYPE a SYSTEM 'file:///never-read'><a/>",
            "<!DOCTYPE a [<!ENTITY secret SYSTEM 'https://never-read.invalid'>]><a>&secret;</a>",
            "<a><b></a>", "<a>".repeat(129) + "</a>".repeat(129), "<a>&unknown;</a>")) {
            assertEquals("statement_unreadable", assertFailsWith<StatementDocumentFailure>(text.take(30)) {
                visitCashWorkbookXml(text.encodeToByteArray()) { _, _ -> }
            }.code)
        }
        for (bytes in listOf(byteArrayOf(60, 97, 62, -1), byteArrayOf(-1, -2, 60, 0, 0))) {
            assertEquals("statement_unreadable", assertFailsWith<StatementDocumentFailure> { decodeCashWorkbookXml(bytes) }.code)
        }
    }
    @Test fun invalidXmlCharactersAndAttributeReferencesNeverBecomeCells() {
        for (text in listOf("", "<a x=\"&unknown;\"/>", "<a>&#0;</a>", "<a>&#xD800;</a>",
            "<a>&#x110000;</a>", "<a x=\"&#0;\"/>", "<a>\u0001</a>", "<a/><b/>", "<a>&#xGG;</a>",
            "<a>&#x4010000;</a>", "<a x=\"&#x4010000;\"/>", "<a>&#x80000000;</a>", "<a>&#999999999999999999999999;</a>",
            "<a x=\"<![CDATA[&#x4010000;]]>\"/>", "<a x=\"<!-- &#x4010000; -->\"/>", "<a x=\"<?fake &#x4010000;?>\"/>")) {
            assertEquals("statement_unreadable", assertFailsWith<StatementDocumentFailure>(text) {
                visitCashWorkbookXml(text.encodeToByteArray()) { _, _ -> }
            }.code)
        }
        val text = StringBuilder()
        visitCashWorkbookXml("<a><![CDATA[&unknown;]]></a>".encodeToByteArray()) { reader, event ->
            if (event == EventType.CDSECT) text.append(reader.text)
        }
        assertEquals("&unknown;", text.toString())
    }
    @Test fun numericReferenceValidationKeepsProtectedTextAndArbitraryLeadingZeroes() {
        val values = mutableListOf<String>()
        val source = "<a x=\"&#00000000000000000000000065;\"><![CDATA[&#x4010000;]]><!-- &#x80000000; -->&#x000000000000000000010400;</a>"
        visitCashWorkbookXml(source.encodeToByteArray()) { reader, event ->
            when (event) {
                EventType.START_ELEMENT -> values += reader.getAttributeValue(0)
                EventType.TEXT, EventType.CDSECT -> values += reader.text
                else -> Unit
            }
        }
        assertEquals(listOf("A", "&#x4010000;", "𐐀"), values)
    }
    @Test fun consumerProgramErrorsKeepTheirOriginalIdentity() {
        val cause = IllegalStateException("synthetic internal failure")
        assertSame(cause, assertFailsWith<IllegalStateException> {
            visitCashWorkbookXml("<a/>".encodeToByteArray()) { _, _ -> throw cause }
        })
    }
    @Test fun spreadsheetEscapesAreSinglePassUtf16AndDoNotDecodeSimilarPlainText() {
        assertEquals("\r", decodeCashWorkbookString("_x000D_"))
        assertEquals("_x000D_", decodeCashWorkbookString("_x005F_x000D_"))
        assertEquals("😀", decodeCashWorkbookString("_xD83D__xDE00_"))
        assertEquals("_X000D__x00gd_", decodeCashWorkbookString("_X000D__x00gd_"))
    }
}
