package com.finance.tracker.backend

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.test.*
import kotlinx.serialization.json.*
import org.apache.pdfbox.Loader

class CashImportSliceFixtureTest {
    private fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/cash-import-slice/$name")).use { it.readBytes() }
    private fun json(name: String) = Json.parseToJsonElement(bytes(name).decodeToString()).jsonObject
    private fun digest(content: ByteArray) = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

    @Test fun checkedInBytesAndIndependentExpectationsMatchTheirDeclaredSource() {
        val manifest = json("manifest.json")
        val files = manifest.getValue("files").jsonArray.map { it.jsonObject }
        assertEquals(11, files.size)
        assertEquals(759, files.sumOf { it.getValue("rows").jsonPrimitive.int })
        assertEquals(setOf("alipay", "wechat", "ccb_debit", "icbc_credit", "icbc_debit"), files.map { it.getValue("source").jsonPrimitive.content }.toSet())
        files.forEach { file ->
            assertEquals(file.getValue("sha256").jsonPrimitive.content, digest(bytes(file.getValue("file").jsonPrimitive.content)))
        }
        val expected = json("expected.json")
        assertEquals(expected.getValue("source_sha256").jsonPrimitive.content, digest(bytes("source.json")))
        assertEquals(377, expected.getValue("ledger").jsonArray.size)
        assertEquals("2026-05-01", manifest.getValue("period").jsonObject.getValue("start").jsonPrimitive.content)
        assertEquals("2026-05-31", manifest.getValue("period").jsonObject.getValue("end").jsonPrimitive.content)
    }

    @Test fun monthlySliceStillExercisesLargeXmlAndRotatedPdfBoundaries() {
        var worksheetBytes = 0
        ZipInputStream(ByteArrayInputStream(bytes("wechat-01.xlsx"))).use { zip ->
            while (true) {
                val part = zip.nextEntry ?: break
                val content = zip.readBytes()
                if (part.name.startsWith("xl/worksheets/") && part.name.endsWith(".xml")) worksheetBytes = maxOf(worksheetBytes, content.size)
            }
        }
        assertTrue(worksheetBytes > 65_536, "The integration fixture must retain the old truncated-XML regression")
        for (name in listOf("icbc-credit-01.pdf", "icbc-credit-02.pdf", "icbc-debit-01.pdf", "icbc-debit-02.pdf")) {
            Loader.loadPDF(bytes(name)).use { document ->
                assertTrue(document.numberOfPages > 0)
                document.pages.forEach { assertEquals(90, it.rotation, "The integration fixture must retain the real PDF orientation") }
            }
        }
        val alipay = bytes("alipay-01.csv")
        assertFalse(alipay.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
        assertTrue(alipay.toString(charset("GB18030")).contains("支付宝交易记录明细查询"))
    }
}
