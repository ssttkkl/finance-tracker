package com.finance.tracker.business.contracts

import kotlinx.serialization.Serializable

@Serializable
data class CashSourceCoverage(
    val start: String?, val end: String?, val complete: Boolean, val evidence: String,
    val filters: Map<String, String>, val strategyVersion: String,
    val startInclusive: Boolean = true, val endInclusive: Boolean = false,
    val timePrecision: String = "second", val documentKey: String? = null, val pageTotal: Int? = null,
    val pageFingerprints: Map<String, String> = emptyMap(),
) {
    init {
        val hasEvidence = documentKey != null || pageTotal != null || pageFingerprints.isNotEmpty()
        if (hasEvidence) {
            val key = documentKey
            val total = pageTotal
            if (key == null || !Regex("[0-9a-f]{64}").matches(key) ||
                total == null || total !in 1..2000 ||
                pageFingerprints.isEmpty() || pageFingerprints.size > total ||
                pageFingerprints.any { (page, fingerprint) ->
                    !Regex("[1-9][0-9]{0,3}").matches(page) || page.toInt() > total ||
                        !Regex("[0-9a-f]{64}").matches(fingerprint)
                })
                throw IllegalArgumentException("invalid_source_document_evidence")
        }
    }
}
