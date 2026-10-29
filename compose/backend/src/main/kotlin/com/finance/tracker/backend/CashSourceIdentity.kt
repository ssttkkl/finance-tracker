package com.finance.tracker.backend

import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import kotlinx.serialization.json.*

/** 来源范围/文件/文档绑定键；只接受已解码文档和稳定定位。 */
internal class CashSourceScopeError(val code: String = "source_scope_invalid") : RuntimeException(code)

internal fun providerIdentityKey(providerId: String): String = sourceDigest(providerId)

internal data class SourceBindingKey(val sourceType: String, val scopeKind: String, val scopeKey: String,
    val identityKind: String, val identityKey: String) {
    val key: String get() = sourceDigest(sourceType, scopeKind, scopeKey, identityKind, identityKey)
}

internal data class SourceDocumentEvidence(val sourceType: String, val parserVersion: String,
    val coverage: CashSourceCoverage) {
    val namespace: DocumentNamespace get() = DocumentNamespace(sourceType, parserVersion, coverage.documentKey)
}

internal data class DocumentNamespace(val sourceType: String, val parserVersion: String, val documentKey: String?)

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

internal object CashSourceScope {
    const val WECHAT_DEFAULT_SCOPE = "wechat:default:v1"
    private const val WECHAT_NAMED_PREFIX = "wechat:named:v1:"

    fun normalizeScopeLabel(value: String?): String? {
        if (value == null) return null
        if (value.codePoints().anyMatch { categoryIsControl(it) }) throw CashSourceScopeError()
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC).trim { it.code in pythonWhitespace }
        if (normalized.codePointCount(0, normalized.length) > 64 ||
            normalized.toByteArray(StandardCharsets.UTF_8).size > 256) throw CashSourceScopeError()
        return normalized.ifEmpty { null }
    }

    fun sourceAccountKey(sourceType: String, owner: String? = null, scopeLabel: String? = null): String? {
        val label = normalizeScopeLabel(scopeLabel)
        if (sourceType == "wechat") return if (label != null) WECHAT_NAMED_PREFIX + label else WECHAT_DEFAULT_SCOPE
        if (label != null) throw CashSourceScopeError("source_scope_unsupported")
        if (owner != null && (owner.isEmpty() || owner.codePointCount(0, owner.length) > 256)) throw CashSourceScopeError()
        return owner
    }

    private fun categoryIsControl(code: Int): Boolean = when (Character.getType(code)) {
        Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.PRIVATE_USE.toInt(),
        Character.SURROGATE.toInt(), Character.UNASSIGNED.toInt() -> true
        else -> false
    }
}

internal object CashSourceDocuments {
    fun documentCompleteness(evidence: List<SourceDocumentEvidence>): Map<DocumentNamespace, Boolean> {
        val groups = linkedMapOf<DocumentNamespace, MutableList<CashSourceCoverage>>()
        for (item in evidence) if (!item.coverage.documentKey.isNullOrEmpty())
            groups.getOrPut(item.namespace) { mutableListOf() } += item.coverage
        val result = linkedMapOf<DocumentNamespace, Boolean>()
        for ((namespace, coverages) in groups) {
            val first = coverages.first()
            var consistent = true
            val pages = linkedMapOf<String, String>()
            for (coverage in coverages) {
                consistent = consistent && declaration(first) == declaration(coverage)
                for ((page, fingerprint) in coverage.pageFingerprints) {
                    val previous = pages.putIfAbsent(page, fingerprint)
                    consistent = consistent && (previous == null || previous == fingerprint)
                }
            }
            val bounded = first.start != null && first.end != null && try {
                pythonCashTimestamp(first.start) < pythonCashTimestamp(first.end)
            } catch (_: Exception) {
                false
            }
            val total = first.pageTotal
            result[namespace] = consistent && bounded && total != null &&
                pages.keys == (1..total).map { it.toString() }.toSet()
        }
        return result
    }

    fun currentDocumentCoverage(item: SourceDocumentEvidence, completeness: Map<DocumentNamespace, Boolean>): CashSourceCoverage {
        if (item.coverage.documentKey.isNullOrEmpty()) return item.coverage
        val complete = completeness[item.namespace] == true
        return item.coverage.copy(complete = complete,
            evidence = if (complete) "icbc-pdf-export-pages-count-v1" else "")
    }

    private fun declaration(coverage: CashSourceCoverage): List<Any?> = listOf(coverage.start, coverage.end,
        coverage.filters, coverage.startInclusive, coverage.endInclusive, coverage.timePrecision,
        coverage.strategyVersion, coverage.documentKey, coverage.pageTotal)
}

/** Python `json.dumps(ensure_ascii=False, sort_keys=True, separators=(",", ":"))` 的稳定摘要。 */
internal fun sourceDigest(vararg values: Any?): String = tokenDigest(sourceJson(values.toList()).toString())

private fun pythonStringOrder(first: String, second: String): Int {
    val left = first.codePoints().iterator()
    val right = second.codePoints().iterator()
    while (left.hasNext() && right.hasNext()) {
        val comparison = left.nextInt().compareTo(right.nextInt())
        if (comparison != 0) return comparison
    }
    return when {
        left.hasNext() -> 1
        right.hasNext() -> -1
        else -> 0
    }
}

private fun pythonDigestFloat(value: Double): String = when {
    value.isNaN() -> "NaN"
    value == Double.POSITIVE_INFINITY -> "Infinity"
    value == Double.NEGATIVE_INFINITY -> "-Infinity"
    else -> pythonFloatText(value)
}

internal fun sourceJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is BigDecimal -> JsonPrimitive(value.toString())
    is Double -> JsonUnquotedLiteral(pythonDigestFloat(value))
    is Float -> JsonUnquotedLiteral(pythonDigestFloat(value.toDouble()))
    is Number -> JsonUnquotedLiteral(value.toString())
    is List<*> -> JsonArray(value.map(::sourceJson))
    is Map<*, *> -> JsonObject(value.entries.sortedWith { first, second ->
        pythonStringOrder(first.key.toString(), second.key.toString())
    }.associate { it.key.toString() to sourceJson(it.value) })
    else -> JsonPrimitive(value.toString())
}
