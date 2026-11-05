package com.finance.tracker.backend

import com.finance.tracker.business.contracts.CashXlsParseResultDto
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import kotlinx.serialization.json.Json

/** Request-only legacy XLS decoding. Normalization and matching stay on the client. */
internal class CashXlsParseService(
    private val maxFileBytes: Int = CashImportProtocol.MAX_FILE_BYTES,
    private val maxResponseBytes: Int = 25 * 1024 * 1024,
    private val parseDocument: (ByteArray) -> CashStatementDocument = PdfProcesses.client::readXls,
) {
    init {
        require(maxFileBytes in 1..CashImportProtocol.MAX_FILE_BYTES)
        require(maxResponseBytes in 1..25 * 1024 * 1024)
    }

    fun parse(upload: CashImportUpload): CashXlsParseResultDto {
        if (upload.content.size > maxFileBytes) throw CashImportProtocolError("upload_too_large")
        if (upload.password != null) throw CashImportProtocolError()
        if (upload.filename.substringAfterLast('.', "").lowercase() != "xls" ||
            upload.sourceHint?.replace('-', '_')?.let { it != "ccb_debit" } == true)
            throw StatementDocumentFailure("statement_source_unsupported")
        val result = CashXlsParseResultDto(CASH_RULES_VERSION, parseDocument(upload.content))
        if (responseJson.encodeToString(result).encodeToByteArray().size > maxResponseBytes)
            throw StatementDocumentFailure("statement_rows_too_large")
        return result
    }

    private companion object {
        val responseJson = Json { encodeDefaults = true; explicitNulls = true }
    }
}
