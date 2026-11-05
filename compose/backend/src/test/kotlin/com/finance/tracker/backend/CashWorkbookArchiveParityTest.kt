package com.finance.tracker.backend

import com.finance.tracker.business.domain.preflightCashWorkbookArchive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.*

class CashWorkbookArchiveParityTest {
    private fun archive(members: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in members) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun rejected(content: ByteArray, code: String) = assertEquals(code,
        assertFailsWith<StatementDocumentFailure> { preflightCashWorkbookArchive(content) { } }.code)

    @Test fun actualJavaZipMembersPreserveWorksheetOrderAndContents() {
        val names = listOf("optional/", "_rels/.rels", "xl/worksheets/sheet2.xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml")
        val content = archive(names.map { it to if (it.endsWith('/')) byteArrayOf() else "<a>账单&amp;来源</a>".toByteArray() })
        val expected = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(content)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val bytes = input.readBytes()
                if (entry.name.endsWith(".xml") && entry.name.contains("xl/worksheets/")) expected += bytes.decodeToString()
            }
        }
        val actual = mutableListOf<String>()
        preflightCashWorkbookArchive(content) { actual += it.decodeToString() }
        assertEquals(expected, actual)
        assertEquals(2, actual.size)
    }

    @Test fun memberCountBoundaryAndDuplicateNamesKeepTheOriginalBudgetError() {
        preflightCashWorkbookArchive(archive(List(1000) { "$it.xml" to byteArrayOf() })) { fail("not a worksheet") }
        rejected(archive(List(1001) { "$it.xml" to byteArrayOf() }), "statement_rows_too_large")
        val duplicate = archive(listOf("a.xml" to byteArrayOf(), "b.xml" to byteArrayOf()))
        val name = "b.xml".toByteArray()
        for (i in 0..duplicate.size - name.size) {
            if (name.indices.all { duplicate[i + it] == name[it] }) duplicate[i] = 'a'.code.toByte()
        }
        rejected(duplicate, "statement_rows_too_large")
    }

    @Test fun nonXmlMembersAndNonemptyDirectoriesRetainTheirRejection() {
        rejected(archive(listOf("picture.png" to byteArrayOf())), "statement_unreadable")
        rejected(archive(listOf("directory/" to byteArrayOf(1))), "statement_unreadable")
        preflightCashWorkbookArchive(archive(listOf("directory/" to byteArrayOf()))) { fail("not a worksheet") }
    }

    @Test fun corruptUnusedDeflatePayloadAndMissingCentralDirectoryAreRejected() {
        val content = archive(listOf("unused.xml" to ByteArray(10000) { 'a'.code.toByte() }))
        val corrupt = content.copyOf()
        val offset = 30 + "unused.xml".length
        corrupt[offset] = (corrupt[offset].toInt() xor 0x40).toByte()
        rejected(corrupt, "statement_unreadable")
        rejected(content.copyOf(content.size - 22), "statement_unreadable")
    }
}
