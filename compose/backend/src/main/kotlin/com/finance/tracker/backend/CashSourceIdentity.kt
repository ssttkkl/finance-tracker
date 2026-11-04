package com.finance.tracker.backend

import java.math.BigDecimal
import kotlinx.serialization.json.*
import com.finance.tracker.business.domain.pythonStringOrder as sharedPythonStringOrder
import com.finance.tracker.business.domain.sourceJson as sharedSourceJson

internal fun providerIdentityKey(providerId: String): String = sourceDigest(providerId)

internal data class SourceBindingKey(val sourceType: String, val scopeKind: String, val scopeKey: String,
    val identityKind: String, val identityKey: String) {
    val key: String get() = sourceDigest(sourceType, scopeKind, scopeKey, identityKind, identityKey)
}

internal typealias SourceDocumentEvidence = com.finance.tracker.business.domain.SourceDocumentEvidence
internal typealias DocumentNamespace = com.finance.tracker.business.domain.DocumentNamespace

internal data class SourceOccurrence(
    val sourceType: String,
    val sourceAccountKey: String?,
    val fileDigest: String,
    val parserVersion: String,
    val tableKey: String,
    val position: Int,
    val fields: Map<String, Any?>,
    val coverage: CashSourceCoverage = CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1",
        endInclusive = false),
    val providerId: String? = null,
    val stableEvidence: String? = null,
    val documentPosition: String? = null,
) {
    init {
        if (sourceType.isEmpty() || sourceType.codePointCount(0, sourceType.length) > 64 ||
            fileDigest.isEmpty() || fileDigest.codePointCount(0, fileDigest.length) > 64 ||
            parserVersion.isEmpty() || parserVersion.codePointCount(0, parserVersion.length) > 64 ||
            tableKey.isEmpty() || tableKey.codePointCount(0, tableKey.length) > 255 ||
            sourceAccountKey != null && (sourceAccountKey.isEmpty() ||
                sourceAccountKey.codePointCount(0, sourceAccountKey.length) > 256) ||
            providerId != null && (providerId.isEmpty() || providerId.codePointCount(0, providerId.length) > 512) ||
            stableEvidence != null && (stableEvidence.isEmpty() ||
                stableEvidence.codePointCount(0, stableEvidence.length) > 512) || position < 0)
            throw IllegalArgumentException("invalid_source_locator")
        if (documentPosition != null && !Regex("p[1-9][0-9]{0,3}:t[0-9]{1,2}:r[1-9][0-9]{0,5}").matches(documentPosition))
            throw IllegalArgumentException("invalid_source_locator")
        if (sourceType in setOf("alipay", "wechat") && !CashSourceScope.isValidPlatformTransactionId(providerId))
            throw IllegalArgumentException("platform_transaction_id_required")
    }

    val documentScopeKey: String? get() =
        coverage.documentKey?.takeIf { it.isNotEmpty() }?.let { sourceDigest("source-document-v1", sourceType, parserVersion, it) }

    val documentOccurrenceKey: String?
        get() {
            val scope = documentScopeKey ?: return null
            val position = documentPosition ?: return null
            if (position.substringBefore(':').drop(1) !in coverage.pageFingerprints) return null
            return sourceDigest(scope, tableKey, position)
        }

    val observationScopeKey: String get() = if (documentOccurrenceKey != null) documentScopeKey!! else fileScopeKey

    val documentBindingKey: String?
        get() = documentOccurrenceKey?.let {
            SourceBindingKey(sourceType, "file_local", documentScopeKey!!, "file_occurrence", it).key
        }

    val documentPage: Pair<String, String>?
        get() = documentOccurrenceKey?.let { coverage.documentKey!! to documentPosition!!.substringBefore(':').drop(1) }

    val documentPageScopeKey: String?
        get() = documentPage?.let { sourceDigest("source-document-page-v1", documentScopeKey, it.second) }

    val occurrenceKey: String
        get() = if (sourceType == "wechat" && sourceAccountKey != null)
            sourceDigest(sourceType, sourceAccountKey, fileDigest, parserVersion, tableKey, position)
        else sourceDigest(sourceType, fileDigest, parserVersion, tableKey, position)

    val fileScopeKey: String
        get() = if (sourceType == "wechat" && sourceAccountKey != null)
            sourceDigest(sourceType, sourceAccountKey, fileDigest, parserVersion)
        else sourceDigest(sourceType, fileDigest, parserVersion)

    val accountScopeKey: String? get() = sourceAccountKey

    val fileBindingKey: String
        get() = SourceBindingKey(sourceType, "file_local", fileScopeKey, "file_occurrence", occurrenceKey).key

    val providerIdentity: String? get() = providerId?.let(::providerIdentityKey)

    val identityBindingSpecs: List<SourceBindingKey>
        get() {
            val specs = mutableListOf<SourceBindingKey>()
            val scope = accountScopeKey
            if (scope != null) {
                val provider = providerIdentity
                if (provider != null)
                    specs += SourceBindingKey(sourceType, "account", scope, "provider_id", provider)
                specs += SourceBindingKey(sourceType, "account", scope, "file_occurrence", occurrenceKey)
            }
            specs += SourceBindingKey(sourceType, "file_local", fileScopeKey, "file_occurrence", occurrenceKey)
            return specs
        }

    val providerBindingKey: String? get() = identityBindingSpecs.firstOrNull { it.identityKind == "provider_id" }?.key

    val stableEvidenceBindingKey: String?
        get() = if (stableEvidence != null && sourceAccountKey != null)
            sourceDigest(sourceType, sourceAccountKey, "verified", stableEvidence) else null

    val bindingSpecs: List<SourceBindingKey>
        get() {
            val specs = identityBindingSpecs.toMutableList()
            val occurrence = documentOccurrenceKey
            if (occurrence != null) {
                specs += SourceBindingKey(sourceType, "file_local", documentScopeKey!!, "file_occurrence", occurrence)
                specs += SourceBindingKey(sourceType, "file_local", documentPageScopeKey!!, "file_occurrence", occurrence)
            }
            return specs
        }

    val bindingKeys: List<String> get() = bindingSpecs.map { it.key }
}

internal typealias CashSourceScope = com.finance.tracker.business.domain.CashSourceScope

internal typealias CashSourceDocuments = com.finance.tracker.business.domain.CashSourceDocuments

/** Python `json.dumps(ensure_ascii=False, sort_keys=True, separators=(",", ":"))` 的稳定摘要。 */
internal fun sourceDigest(vararg values: Any?): String = tokenDigest(sourceJson(values.toList()).toString())

/** 实现复用共享 KMP 模块，保持单一来源。 */
internal fun pythonStringOrder(first: String, second: String): Int = sharedPythonStringOrder(first, second)

private fun pythonDigestFloat(value: Double): String = when {
    value.isNaN() -> "NaN"
    value == Double.POSITIVE_INFINITY -> "Infinity"
    value == Double.NEGATIVE_INFINITY -> "-Infinity"
    else -> pythonFloatText(value)
}

private fun backendSourceDecimal(value: Any): String? = (value as? BigDecimal)?.toString()

/** 结构实现复用共享 KMP 模块，十进制与浮点口径由后端注入，digest 保持不变。 */
internal fun sourceJson(value: Any?): JsonElement =
    sharedSourceJson(value, ::backendSourceDecimal, ::pythonDigestFloat)
