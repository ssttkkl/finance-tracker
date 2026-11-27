package com.finance.tracker.client

import com.finance.tracker.business.contracts.StoredCashImportFile
import com.finance.tracker.business.domain.CASH_STATEMENT_MAX_BYTES
import com.finance.tracker.business.domain.CashStatementFormat
import com.finance.tracker.business.domain.ParsedCashDocument
import com.finance.tracker.business.domain.StatementDocumentFailure
import com.finance.tracker.business.domain.captureCashStatement
import com.finance.tracker.business.domain.cashStatementFormat
import com.finance.tracker.business.domain.localCashImportFailureCodes
import com.finance.tracker.business.domain.localCashImportPasswordCodes
import com.finance.tracker.business.domain.parseCashStatementLocally

sealed interface CashImportFileCapture {
    data class Prepared(val file: StoredCashImportFile) : CashImportFileCapture
    data class RequiresRemoteParser(val format: CashStatementFormat) : CashImportFileCapture
}

/** Interpret an already bounded file; the host owns file reads and any required remote transport. */
fun captureCashImportFile(file: CashImportFileInput, retainKnownFailure: Boolean): CashImportFileCapture {
    if (!validCashImportFileRef(file.ref) || file.name.any { it in "\r\n\u0000" } ||
        file.source?.matches(Regex("[a-z][a-z0-9_-]{0,63}")) == false)
        throw ClientOperationFailure("import_payload_invalid")
    if (file.bytes.size > CASH_STATEMENT_MAX_BYTES) throw ClientOperationFailure("import_file_too_large")
    return try {
        when (val format = cashStatementFormat(file.bytes, file.source)) {
            CashStatementFormat.PDF, CashStatementFormat.XLS -> CashImportFileCapture.RequiresRemoteParser(format)
            else -> CashImportFileCapture.Prepared(completeCashImportFile(file,
                parseCashStatementLocally(format, file.bytes, file.source?.replace('-', '_'))))
        }
    } catch (failure: StatementDocumentFailure) {
        val captured = retainCashImportFileFailure(file, retainKnownFailure, failure.code)
        if (captured == null) throw failure
        CashImportFileCapture.Prepared(captured)
    }
}

fun completeCashImportFile(file: CashImportFileInput, document: ParsedCashDocument): StoredCashImportFile =
    captureCashStatement(file.ref, file.name, file.mediaType, file.modifiedAtMillis, file.source, file.bytes, document)

fun retainCashImportFileFailure(file: CashImportFileInput, retain: Boolean, code: String, status: Int? = null): StoredCashImportFile? {
    if (!retain || !(status == null && code in localCashImportFailureCodes ||
            status == 422 && (code in localCashImportFailureCodes || code in localCashImportPasswordCodes))) return null
    return captureCashStatement(file.ref, file.name, file.mediaType, file.modifiedAtMillis, file.source, file.bytes, null, code)
}

fun validCashImportFileRef(ref: String): Boolean = ref.matches(Regex("[A-Za-z0-9_-]{1,128}"))

fun validCashImportFileSelection(refs: List<String>): Boolean =
    refs.size in 1..20 && refs.distinct().size == refs.size && refs.all(::validCashImportFileRef)
