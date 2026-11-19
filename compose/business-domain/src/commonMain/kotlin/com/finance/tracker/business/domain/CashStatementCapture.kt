package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.StoredCashImportFile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64

fun parseCashStatementLocally(format: CashStatementFormat, bytes: ByteArray, source: String?): ParsedCashDocument = when (format) {
    CashStatementFormat.CSV -> parseLocalCashCsvStatement(bytes, source)
    CashStatementFormat.XLSX -> parseLocalCashXlsxStatement(bytes, source)
    CashStatementFormat.ICBC_ASIA -> parseLocalIcbcAsiaStatement(bytes, source)
    CashStatementFormat.PDF, CashStatementFormat.XLS -> error("statement_remote_format")
}

fun captureCashStatement(fileRef: String, filename: String, mediaType: String?, lastModified: Long?, source: String?,
    bytes: ByteArray, document: ParsedCashDocument?, failure: String? = null): StoredCashImportFile {
    val digest = sha256Hex(bytes)
    if (document != null) {
        require(failure == null)
        return StoredCashImportFile(1, fileRef, filename, mediaType, lastModified, source, bytes.size.toLong(), digest,
            Base64.encode(bytes), normalizedCashDocumentWire(document) { storedCashValue(it).jsonObject })
    }
    require(failure in localCashImportFailureCodes || failure in localCashImportPasswordCodes)
    return StoredCashImportFile(2, fileRef, filename, mediaType, lastModified, source, bytes.size.toLong(), digest,
        null, null, if (failure in localCashImportPasswordCodes) "needs_password" else "failed", failure)
}

/** Existing JSON tokens already passed their transport boundary; do not interpret them as Double. */
private fun storedCashValue(value: Any?): JsonElement = when (value) {
    is JsonElement -> value
    is Map<*, *> -> JsonObject(value.entries.associate { (key, item) ->
        (key as? String ?: throw InvalidCashValue()) to storedCashValue(item)
    })
    is List<*> -> JsonArray(value.map(::storedCashValue))
    else -> encodeLocalCashValue(value)
}

val localCashImportPasswordCodes = setOf("password_required", "password_incorrect")
val localCashImportFailureCodes = setOf("import_channel_unrecognized", "statement_source_unsupported",
    "statement_header_not_found", "statement_header_invalid", "statement_rows_too_large", "statement_input_invalid",
    "statement_unreadable", "statement_encoding_unsupported")
