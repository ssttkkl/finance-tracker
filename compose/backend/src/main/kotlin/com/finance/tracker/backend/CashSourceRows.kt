package com.finance.tracker.backend

/** 已解析文件的最小批次视图；不查询账本、不重新解析。 */
internal data class ParsedImportFile(
    val fileRef: String,
    val digest: String,
    val status: String,
    val document: ParsedCashDocument? = null,
    val sourceScopeLabel: String? = null,
    val isSubset: Boolean = false,
    val lastModifiedAtMs: Long? = null,
    val clientFileRef: String = fileRef,
    val filename: String = "statement",
    val size: Long = 0,
    val sourceType: String? = document?.sourceType,
    val errorCode: String? = null,
    val diagnostic: Map<String, Any?>? = null,
)

internal class SourceBatchRows(
    val rows: LinkedHashMap<String, SourceOccurrence>,
    val documentEvidence: List<SourceDocumentEvidence>,
    val documentCoverages: List<CashSourceCoverage>,
    val fileMetadata: Map<String, Pair<Int, Long?>>,
)

/** 把已解析出现组合成批次；只做输入组合，不做去重或写入。 */
internal object CashSourceRows {
    fun sourceBatchRows(files: List<ParsedImportFile>,
        resolvedFields: Map<String, Map<Int, Map<String, Any?>>> = emptyMap(),
        knownDocumentEvidence: List<SourceDocumentEvidence> = emptyList()): SourceBatchRows {
        val evidence = fileDocumentEvidence(files) + knownDocumentEvidence
        val rows = linkedMapOf<String, SourceOccurrence>()
        val seen = mutableSetOf<String>()
        val coverages = combinedCoverages(files, evidence)
        for (original in files) {
            if (!seen.add(original.fileRef)) throw IllegalArgumentException("duplicate_source_file_ref")
            var file = original
            val combined = coverages[file.fileRef]
            if (combined != null) file = file.copy(document = file.document!!.copy(coverage = combined))
            rows.putAll(sourceRows(file, resolvedFields[file.fileRef]))
        }
        return SourceBatchRows(rows, evidence, evidence.map { it.coverage },
            files.mapIndexed { index, file -> file.fileRef to (index to file.lastModifiedAtMs) }.toMap())
    }

    fun fileDocumentEvidence(files: List<ParsedImportFile>): List<SourceDocumentEvidence> = files.mapNotNull { file ->
        val document = file.document
        if (file.status == "parsed" && document != null && !file.isSubset)
            SourceDocumentEvidence(document.sourceType, document.parserVersion, document.coverage) else null
    }

    fun sourceRows(file: ParsedImportFile,
        resolvedFields: Map<Int, Map<String, Any?>>? = null): LinkedHashMap<String, SourceOccurrence> {
        val result = linkedMapOf<String, SourceOccurrence>()
        val original = file.document
        if (file.status != "parsed" || original == null) return result
        val document = if (file.isSubset) original.copy(coverage = original.coverage.copy(complete = false, evidence = ""))
        else original
        for (row in document.rows) {
            if (row.disposition == "skipped" || row.fields == null) continue
            val values = LinkedHashMap<String, Any?>()
            row.fields.forEach { (key, value) -> values[key] = copyValue(value) }
            resolvedFields?.get(row.position)?.forEach { (key, value) -> values[key] = copyValue(value) }
            val scope = CashSourceScope.sourceAccountKey(document.sourceType,
                row.sourceAccountIdentifier?.takeIf { it.isNotEmpty() } ?: document.sourceAccountIdentifier, file.sourceScopeLabel)
            result["${file.fileRef}:r${row.position}"] = SourceOccurrence(
                document.sourceType, scope, file.digest, document.parserVersion, document.tableKey, row.position,
                values, document.coverage, row.providerId, null, row.documentPosition)
        }
        return result
    }

    private fun combinedCoverages(files: List<ParsedImportFile>,
        evidence: List<SourceDocumentEvidence>): Map<String, CashSourceCoverage> {
        val completeness = CashSourceDocuments.documentCompleteness(evidence)
        val result = linkedMapOf<String, CashSourceCoverage>()
        for (file in files) {
            val document = file.document ?: continue
            if (file.status == "parsed" && !document.coverage.documentKey.isNullOrEmpty() && !file.isSubset) {
                result[file.fileRef] = CashSourceDocuments.currentDocumentCoverage(
                    SourceDocumentEvidence(document.sourceType, document.parserVersion, document.coverage), completeness)
            }
        }
        return result
    }

    private fun copyValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { it.key to copyValue(it.value) }
        is List<*> -> value.map(::copyValue)
        else -> value
    }
}
