package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.StoredCashImportFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64

const val CASH_STATEMENT_MAX_BYTES = 100 * 1024 * 1024

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

/** Read and verify a previously captured statement before using its normalized data. */
fun decodeStoredCashImportFile(encoded: String): StoredCashImportFile {
    val stored = Json.decodeFromString<StoredCashImportFile>(encoded)
    check(stored.version == 1 || stored.version == 2) { "cash_import_file_version_unsupported" }
    require(stored.size in 0..CASH_STATEMENT_MAX_BYTES.toLong()) { "cash_import_file_size_invalid" }
    require(stored.digest.length == 64 && stored.digest.all { it in '0'..'9' || it in 'a'..'f' }) { "cash_import_file_identity_invalid" }
    if (stored.version == 1) {
        require(stored.status == "parsed" && stored.document != null && stored.errorCode == null) { "cash_import_file_state_invalid" }
        val encodedBytes = requireNotNull(stored.bytes) { "cash_import_file_bytes_invalid" }
        require(encodedBytes.length.toLong() == (stored.size + 2) / 3 * 4) { "cash_import_file_bytes_invalid" }
        val bytes = Base64.decode(encodedBytes)
        require(bytes.size.toLong() == stored.size && sha256Hex(bytes) == stored.digest) { "cash_import_file_identity_invalid" }
    } else {
        require(stored.bytes == null && stored.document == null) { "cash_import_file_state_invalid" }
        require((stored.status == "failed" && stored.errorCode in localCashImportFailureCodes) ||
            (stored.status == "needs_password" && stored.errorCode in localCashImportPasswordCodes)) { "cash_import_file_state_invalid" }
    }
    return stored
}

fun StoredCashImportFile.toParsedImportFile(): ParsedImportFile =
    ParsedImportFile(clientFileRef, digest, status,
        document?.let(::localNormalizedCashDocument), lastModifiedAtMs = lastModifiedAtMs,
        filename = filename, size = size,
        sourceType = document?.sourceType ?: sourceHint?.replace('-', '_'), errorCode = errorCode)
