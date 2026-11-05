package com.finance.tracker.backend

import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.test.*
import kotlinx.serialization.json.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.util.Matrix

class IcbcPdfCoordinatesTest {
    private fun original(name: String): ByteArray {
        val cases = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/python-icbc-pdf-documents.json"))
            .bufferedReader().use { it.readText() }).jsonArray
        return Base64.getDecoder().decode(cases.map { it.jsonObject }.single {
            it.getValue("name").jsonPrimitive.content == name
        }.getValue("content").jsonPrimitive.content)
    }

    private fun transformed(content: ByteArray, rotation: Int, left: Float = 0f, bottom: Float = 0f): ByteArray =
        Loader.loadPDF(content).use { pdf ->
            for (page in pdf.pages) {
                val width = page.cropBox.width
                val height = page.cropBox.height
                val transform = when (rotation) {
                    0 -> Matrix()
                    90 -> Matrix(0f, 1f, -1f, 0f, height, 0f)
                    180 -> Matrix(-1f, 0f, 0f, -1f, width, height)
                    270 -> Matrix(0f, -1f, 1f, 0f, 0f, width)
                    else -> error("unsupported test rotation")
                }
                val rotatedWidth = if (rotation % 180 == 0) width else height
                val rotatedHeight = if (rotation % 180 == 0) height else width
                PDPageContentStream(pdf, page, PDPageContentStream.AppendMode.PREPEND, false, false).use {
                    it.transform(Matrix.getTranslateInstance(left, bottom))
                    it.transform(transform)
                }
                page.mediaBox = PDRectangle(rotatedWidth + left, rotatedHeight + bottom)
                page.cropBox = PDRectangle(left, bottom, rotatedWidth, rotatedHeight)
                page.rotation = rotation
            }
            ByteArrayOutputStream().also { pdf.save(it) }.toByteArray()
        }

    @Test fun pageAndContentRotationPreserveCreditAndDebitDocuments() {
        for (name in listOf("credit", "debit")) {
            val bytes = original(name)
            val expected = CashStatementDocuments.readDocument(bytes)
            for (rotation in listOf(0, 90, 180, 270)) {
                val actual = CashStatementDocuments.readDocument(transformed(bytes, rotation))
                assertEquals(expected, actual, "$name rotation $rotation")
            }
        }
    }

    @Test fun nonzeroCropOriginPreservesRowsAndCoverage() {
        for (name in listOf("credit", "debit")) {
            val bytes = original(name)
            val expected = CashStatementDocuments.readDocument(bytes)
            for (rotation in listOf(0, 90, 180, 270)) {
                val actual = CashStatementDocuments.readDocument(transformed(bytes, rotation, 17f, 23f))
                assertEquals(expected, actual, "$name crop origin and rotation $rotation")
            }
        }
    }
}
