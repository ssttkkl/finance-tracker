package com.finance.tracker.data

import com.finance.tracker.domain.*
import kotlinx.serialization.json.JsonObject

data class ImportRequestOptions(
    val source: String = "",
    val currency: String? = null,
    val password: String? = null,
    val passwords: Map<String, String> = emptyMap(),
    val previewDigest: String? = null,
    val previewRelationDigest: String? = null,
    val previewChannel: String? = null,
    val relations: List<JsonObject>? = null,
    val mapping: List<ImportMappingDecisionDto>? = null,
    val importToken: String? = null,
    val idempotencyKey: String? = null,
    val batch: Boolean = false,
)

suspend fun FinanceApiClient.detectCashImport(file: FileSource, currency: String? = null, password: String? = null): ImportDetectionDto =
    importFileRequest("/api/v1/cash-import/detect", file, ImportRequestOptions(currency = currency, password = password))

suspend fun FinanceApiClient.scanCashImport(
    files: List<FileSource>,
    currency: String? = null,
    passwords: Map<String, String> = emptyMap(),
    importToken: String? = null,
): ImportScanDto {
    if (files.isEmpty() && importToken == null) throw ApiFailure("request_failed", 0)
    if (importToken != null) {
        return importJsonRequest("/api/v1/cash-import/scan", ImportRequestOptions(
            currency = currency,
            passwords = passwords,
            importToken = importToken,
            batch = files.size != 1,
        ))
    }
    val payload = ImportBatchUploadDto(
        files = files.map { file -> ImportFileUploadDto(file.name, base64(file.read())) },
        currency = currency,
    )
    return importJsonRequest("/api/v1/cash-import/scan", ImportRequestOptions(passwords = passwords), payload)
}

suspend fun FinanceApiClient.previewCashImport(
    importToken: String,
    source: String = "",
    currency: String? = null,
    passwords: Map<String, String> = emptyMap(),
    mapping: List<ImportMappingDecisionDto>? = null,
    batch: Boolean = false,
): ImportPreviewDto = importJsonRequest(
    "/api/v1/cash-import/preview",
    ImportRequestOptions(source = source, currency = currency, passwords = passwords, mapping = mapping, importToken = importToken, batch = batch),
)

suspend fun FinanceApiClient.commitCashImport(
    importToken: String,
    source: String = "",
    currency: String? = null,
    passwords: Map<String, String> = emptyMap(),
    previewDigest: String? = null,
    previewRelationDigest: String? = null,
    previewChannel: String? = null,
    relations: List<JsonObject>? = null,
    mapping: List<ImportMappingDecisionDto>? = null,
    idempotencyKey: String? = null,
    batch: Boolean = false,
): ImportCommitResultDto = importJsonRequest(
    "/api/v1/cash-import/commit",
    ImportRequestOptions(
        source = source,
        currency = currency,
        passwords = passwords,
        previewDigest = previewDigest,
        previewRelationDigest = previewRelationDigest,
        previewChannel = previewChannel,
        relations = relations,
        mapping = mapping,
        importToken = importToken,
        idempotencyKey = idempotencyKey,
        batch = batch,
    ),
)

private suspend fun FinanceApiClient.importFileRequest(path: String, file: FileSource, options: ImportRequestOptions): ImportDetectionDto =
    importRawRequest(path, file, options)
