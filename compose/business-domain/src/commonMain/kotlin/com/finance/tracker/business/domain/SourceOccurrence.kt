package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashSourceCoverage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

fun providerIdentityKey(providerId: String): String = sourceIdentityDigest(providerId)

// Identity inputs are strings, nullable scope keys and integer source positions, never money.
private fun sourceIdentityDigest(vararg values: Any?): String = sha256Hex(
    JsonArray(values.map { value -> when (value) {
        null -> JsonNull
        // Some platform JSON writers escape isolated surrogates; validate the original text first.
        is String -> JsonPrimitive(value.also { it.encodeToByteArray(throwOnInvalidSequence = true) })
        is Int -> JsonPrimitive(value)
        else -> error("unsupported source identity input")
    } }).toString().encodeToByteArray(throwOnInvalidSequence = true),
)

data class SourceBindingKey(val sourceType: String, val scopeKind: String, val scopeKey: String,
    val identityKind: String, val identityKey: String) {
    val key: String get() = sourceIdentityDigest(sourceType, scopeKind, scopeKey, identityKind, identityKey)
}


data class SourceOccurrence(
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
        if (sourceType.isEmpty() || sourceType.pythonCodePointCount() > 64 ||
            fileDigest.isEmpty() || fileDigest.pythonCodePointCount() > 64 ||
            parserVersion.isEmpty() || parserVersion.pythonCodePointCount() > 64 ||
            tableKey.isEmpty() || tableKey.pythonCodePointCount() > 255 ||
            sourceAccountKey != null && (sourceAccountKey.isEmpty() ||
                sourceAccountKey.pythonCodePointCount() > 256) ||
            providerId != null && (providerId.isEmpty() || providerId.pythonCodePointCount() > 512) ||
            stableEvidence != null && (stableEvidence.isEmpty() ||
                stableEvidence.pythonCodePointCount() > 512) || position < 0)
            throw IllegalArgumentException("invalid_source_locator")
        if (documentPosition != null && !Regex("p[1-9][0-9]{0,3}:t[0-9]{1,2}:r[1-9][0-9]{0,5}").matches(documentPosition))
            throw IllegalArgumentException("invalid_source_locator")
        if (sourceType in setOf("alipay", "wechat") && !CashSourceScope.isValidPlatformTransactionId(providerId))
            throw IllegalArgumentException("platform_transaction_id_required")
    }

    val documentScopeKey: String? get() =
        coverage.documentKey?.takeIf { it.isNotEmpty() }?.let { sourceIdentityDigest("source-document-v1", sourceType, parserVersion, it) }

    val documentOccurrenceKey: String?
        get() {
            val scope = documentScopeKey ?: return null
            val position = documentPosition ?: return null
            if (position.substringBefore(':').drop(1) !in coverage.pageFingerprints) return null
            return sourceIdentityDigest(scope, tableKey, position)
        }

    val observationScopeKey: String get() = if (documentOccurrenceKey != null) documentScopeKey!! else fileScopeKey

    val documentBindingKey: String?
        get() = documentOccurrenceKey?.let {
            SourceBindingKey(sourceType, "file_local", documentScopeKey!!, "file_occurrence", it).key
        }

    val documentPage: Pair<String, String>?
        get() = documentOccurrenceKey?.let { coverage.documentKey!! to documentPosition!!.substringBefore(':').drop(1) }

    val documentPageScopeKey: String?
        get() = documentPage?.let { sourceIdentityDigest("source-document-page-v1", documentScopeKey, it.second) }

    val occurrenceKey: String
        get() = if (sourceType == "wechat" && sourceAccountKey != null)
            sourceIdentityDigest(sourceType, sourceAccountKey, fileDigest, parserVersion, tableKey, position)
        else sourceIdentityDigest(sourceType, fileDigest, parserVersion, tableKey, position)

    val fileScopeKey: String
        get() = if (sourceType == "wechat" && sourceAccountKey != null)
            sourceIdentityDigest(sourceType, sourceAccountKey, fileDigest, parserVersion)
        else sourceIdentityDigest(sourceType, fileDigest, parserVersion)

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
            sourceIdentityDigest(sourceType, sourceAccountKey, "verified", stableEvidence) else null

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

