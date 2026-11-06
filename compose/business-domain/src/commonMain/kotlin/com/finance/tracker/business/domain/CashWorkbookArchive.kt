package com.finance.tracker.business.domain

import no.synth.kmpzip.io.ByteArrayInputStream
import no.synth.kmpzip.io.ByteArrayOutputStream
import no.synth.kmpzip.io.IOException
import no.synth.kmpzip.zip.ZipInputStream

/** Validate archive members before worksheet syntax and cell decoding. The callback must not publish data. */
fun preflightCashWorkbookArchive(content: ByteArray, validateWorksheet: (ByteArray) -> Unit) {
    scanCashWorkbookArchive(content, MAX_WORKBOOK_INFLATED_BYTES, validateWorksheet)
}

/** Validate every XML/rels member, including worksheets outside conventional paths. */
fun preflightCashWorkbookXmlArchive(content: ByteArray, validateXml: (ByteArray) -> Unit) {
    scanCashWorkbookParts(content, MAX_WORKBOOK_INFLATED_BYTES, retain = { true }) { _, bytes -> validateXml(bytes) }
}

internal const val MAX_WORKBOOK_INFLATED_BYTES = 100L * 1024 * 1024

internal fun scanCashWorkbookArchive(
    content: ByteArray,
    maxInflatedBytes: Long,
    validateWorksheet: (ByteArray) -> Unit,
) {
    scanCashWorkbookParts(content, maxInflatedBytes,
        retain = { it.endsWith(".xml") && it.contains("xl/worksheets/") },
        consume = { _, bytes -> validateWorksheet(bytes) })
}

/** Every member is fully read for CRC/budget checks; callers retain only the parts they need. */
internal fun scanCashWorkbookParts(
    content: ByteArray,
    maxInflatedBytes: Long,
    retain: (String) -> Boolean,
    consume: (String, ByteArray) -> Unit,
) {
    require(maxInflatedBytes in 0..MAX_WORKBOOK_INFLATED_BYTES)
    val names = mutableSetOf<String>()
    var inflated = 0L
    var members = 0
    val buffer = ByteArray(65_536)
    try {
        ZipInputStream(ByteArrayInputStream(content), verifyCentralDirectory = true).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                members++
                if (members > 1000 || !names.add(entry.name)) archiveFailure("statement_rows_too_large")
                if (entry.isDirectory) {
                    if (entry.size > 0 || zip.read() != -1) archiveFailure("statement_unreadable")
                    continue
                }
                if (!entry.name.endsWith(".xml") && !entry.name.endsWith(".rels")) archiveFailure("statement_unreadable")
                val bytes = if (retain(entry.name)) ByteArrayOutputStream() else null
                while (true) {
                    // Read to EOF for every member's CRC, even when its contents are not retained.
                    val requested = minOf(buffer.size.toLong(), maxInflatedBytes - inflated + 1).toInt()
                    val count = zip.read(buffer, 0, requested)
                    if (count < 0) break
                    inflated += count
                    if (inflated > maxInflatedBytes) archiveFailure("statement_rows_too_large")
                    bytes?.write(buffer, 0, count)
                }
                if (bytes != null) consume(entry.name, bytes.toByteArray())
            }
        }
    } catch (failure: IOException) {
        throw StatementDocumentFailure("statement_unreadable").also { it.addSuppressed(failure) }
    }
}

private fun archiveFailure(code: String): Nothing = throw StatementDocumentFailure(code)
