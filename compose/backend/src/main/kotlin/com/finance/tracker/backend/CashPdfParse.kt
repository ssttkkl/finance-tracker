package com.finance.tracker.backend

import com.finance.tracker.business.contracts.CashPdfParseResultDto
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import com.finance.tracker.business.domain.normalizedCashDocumentWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Request-only parsing: no database, staging store, plans or mutation services. */
internal class CashPdfParseService(
    private val maxFileBytes: Int = CashImportProtocol.MAX_FILE_BYTES,
    private val maxResponseBytes: Int = 25 * 1024 * 1024,
    private val parseDocument: (ByteArray, String?, String?) -> ParsedCashDocument = { bytes, source, password ->
        CashStatementRowNormalization.normalizeDocument(IcbcPdfDocumentReader.readIcbcDocument(bytes, source, password))
    },
) {
    init {
        require(maxFileBytes in 1..CashImportProtocol.MAX_FILE_BYTES)
        require(maxResponseBytes in 1..25 * 1024 * 1024)
    }

    fun parse(upload: CashImportUpload): CashPdfParseResultDto {
        if (upload.content.size > maxFileBytes) throw CashImportProtocolError("upload_too_large")
        val document = parseDocument(upload.content, upload.sourceHint?.replace('-', '_'), upload.password)
        val result = CashPdfParseResultDto(CASH_RULES_VERSION,
            normalizedCashDocumentWire(document) { sourceJson(it).jsonObject })
        if (responseJson.encodeToString(result).encodeToByteArray().size > maxResponseBytes)
            throw StatementDocumentFailure("statement_rows_too_large")
        return result
    }

    private companion object {
        val responseJson = Json { encodeDefaults = true; explicitNulls = true }
    }
}
