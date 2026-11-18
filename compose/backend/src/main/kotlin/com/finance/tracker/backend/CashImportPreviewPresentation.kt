package com.finance.tracker.backend

import java.math.BigInteger

/** Native scalar boundaries for the shared frozen-preview renderer. */
internal object CashImportPreviewPresentation {
    private val rules = com.finance.tracker.business.domain.CashImportPreviewPresentationRules<BigInteger, java.time.Instant>(
        CashRecordPolicy, ::strictCashInteger,
        { text -> text.toBigIntegerOrNull()?.let { it >= BigInteger.ONE.shiftLeft(63) } ?: false },
        CashImportEntryChoices.rules, CashImportEntryChoices.edgeOrder)

    fun present(files: List<ParsedImportFile>, snapshot: CashImportPreviewSnapshot, build: CashProjectionBuild,
        groups: List<Map<String, Any?>>, failures: List<Map<String, Any?>>, accounts: Map<String, Map<String, Any?>>,
        includeUnchanged: Boolean = false, entryParentIds: Set<String> = emptySet()): MutableMap<String, Any?> =
        rules.present(files, snapshot, build, groups, failures, accounts, includeUnchanged, entryParentIds)
}
