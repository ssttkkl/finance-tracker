package com.finance.tracker.business.domain

import kotlin.time.Instant

/** Render computed local preview state; discovery, saved choices and persistence remain outside this boundary. */
fun presentLocalCashImportPreview(files: List<ParsedImportFile>,
    snapshot: CashImportPreviewSnapshot<CashImportPreviewInteger, Instant>,
    groups: List<Map<String, Any?>>, failures: List<Map<String, Any?>>, accounts: Map<String, Map<String, Any?>>,
    includeUnchanged: Boolean = false, entryParentIds: Set<String> = emptySet()): MutableMap<String, Any?> =
    localCashImportPresentation.present(files, snapshot, snapshot.build(), groups, failures, accounts,
        includeUnchanged, entryParentIds)

private fun previewIntegerOrNull(text: String): CashImportPreviewInteger? =
    try { CashImportPreviewInteger.parse(text) } catch (_: NumberFormatException) { null }

internal val localCashImportEntryEdgeOrder = Comparator<String> { first, second ->
    val a = previewIntegerOrNull(first); val b = previewIntegerOrNull(second)
    if (a != null && b != null) a.compareTo(b) else cashRelationStringOrder.compare(first, second)
}

private val localCashImportPresentation = CashImportPreviewPresentationRules<CashImportPreviewInteger, Instant>(
    LocalCashRecordValues, ::localCashPreviewInteger,
    { text -> previewIntegerOrNull(text)?.let { it >= CashImportPreviewInteger.parse("9223372036854775808") } ?: false },
    CashImportEntryChoiceRules(LocalCashRecordValues, ::localCashImportDigest), localCashImportEntryEdgeOrder)
