package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*

/** File and account preparation from one committed snapshot; no TTL, executable plan or side effects. */
fun prepareLocalCashImportView(batchId: String, parsed: List<ParsedImportFile>,
    source: LedgerSnapshotDto, review: LocalCashImportReview?): CashImportSessionDto {
    val groups = scanLocalCashFunding(parsed).groups
    val suggestions = suggestLocalCashFundingAccounts(groups, source)
    val input = review?.inputJson()
    val selections = input?.getValue("account_selections")?.jsonArray.orEmpty().map {
        preparationJson.decodeFromJsonElement<CashImportAccountInput>(it)
    }.associateBy { it.sourceAccountRef }
    return CashImportSessionDto(batchId, state = "open", files = buildLocalCashImportContextFiles(parsed), sourceAccounts = groups.map { group ->
        val suggestion = suggestions.getValue(group.sourceAccountRef)
        val selection = selections[group.sourceAccountRef]
        CashImportSourceAccountDto(group.sourceAccountRef, group.sourceType, group.displayName, group.sessionLabel,
            if (group.sourceType in setOf("wechat", "alipay")) localCashImportDigest(listOf("cash-import-session-account-group-v1",
                batchId, group.sourceType, group.originKey)) else null,
            group.currencies, group.rowRefs.size,
            CashImportAccountSuggestionDto(suggestion.accountId, suggestion.expectedAccountRevision, suggestion.basis),
            selection?.let { CashImportAccountSelectionDto(it.accountId, it.expectedAccountRevision) })
    }, lastPreviewInput = input, canPreview = parsed.any { it.document != null })
}

fun buildLocalCashImportContextFiles(files: List<ParsedImportFile>): List<CashImportContextFileDto> = files.map { file ->
    val view = cashImportFileView(file.copy(filename = importDisplayFilename(file.filename))) { it }
    preparationJson.decodeFromJsonElement<CashImportContextFileDto>(encodeLocalCashValue(view + ("recognized_row_count" to file.document?.observedCount?.toLong())))
}

private fun importDisplayFilename(filename: String): String {
    val name = filename.replace('\\', '/').substringAfterLast('/').ifEmpty { "账单" }
    var index = 0
    var count = 0
    while (index < name.length && count < 255) {
        val first = name[index++]
        if (first.isHighSurrogate() && index < name.length && name[index].isLowSurrogate()) index++
        count++
    }
    return name.substring(0, index)
}

private val preparationJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
