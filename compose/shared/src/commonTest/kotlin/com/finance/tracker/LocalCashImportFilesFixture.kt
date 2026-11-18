package com.finance.tracker

import com.finance.tracker.business.contracts.CashSourceCoverage
import com.finance.tracker.business.domain.ParsedCashDocument
import com.finance.tracker.business.domain.ParsedCashRow
import com.finance.tracker.data.CashImportUploadInput
import com.finance.tracker.data.LocalCashImportFile
import com.finance.tracker.domain.BoundedFileSource
import kotlinx.serialization.json.Json

internal const val syntheticImportContent = "synthetic-private-statement-for-encrypted-recovery"

internal fun localCashImportFileFixture(ref: String = "stable"): LocalCashImportFile {
    val bytes = syntheticImportContent.encodeToByteArray()
    val source = object : BoundedFileSource {
        override val name = "synthetic-original.pdf"
        override val mediaType = "application/pdf"
        override val size = bytes.size.toLong()
        override val lastModifiedAtMillis = 1730000000000L
        override suspend fun read(): ByteArray = bytes.copyOf()
        override suspend fun readBounded(maxBytes: Int) = read()
    }
    val document = ParsedCashDocument("icbc_debit", "pdf", "v1", listOf(ParsedCashRow(7,
        mapOf("amount" to "-12.340000000000000001", "exact" to Json.parseToJsonElement("1.000000000000000001")),
        documentPosition = "0", sourceExplanation = mapOf("text" to "synthetic evidence"))),
        null, null, CashSourceCoverage(null, null, false, "", emptyMap(), "v1"), null, 1)
    return LocalCashImportFile.capture(CashImportUploadInput(ref, source), bytes, document)
}
