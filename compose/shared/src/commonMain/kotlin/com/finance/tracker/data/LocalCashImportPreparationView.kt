package com.finance.tracker.data

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.*
import kotlinx.serialization.json.*

/** File and account preparation from one committed snapshot; no TTL, executable plan or side effects. */
internal fun localCashImportPreparationView(batchId: String, files: List<LocalCashImportFile>,
    source: LedgerSnapshotDto, review: LocalCashImportReview?): CashImportSessionDto {
    val parsed = files.map { it.parsedFile() }
    val groups = scanLocalCashFunding(parsed).groups
    val suggestions = suggestLocalCashFundingAccounts(groups, source)
    val selections = review?.inputJson()?.getValue("account_selections")?.jsonArray.orEmpty().map {
        apiJson.decodeFromJsonElement<CashImportAccountInput>(it)
    }.associateBy { it.sourceAccountRef }
    return CashImportSessionDto(batchId, state = "open", files = localCashImportContextFiles(files), sourceAccounts = groups.map { group ->
        val suggestion = suggestions.getValue(group.sourceAccountRef)
        val selection = selections[group.sourceAccountRef]
        CashImportSourceAccountDto(group.sourceAccountRef, group.sourceType, group.displayName, group.sessionLabel,
            if (group.sourceType in setOf("wechat", "alipay")) localCashImportDigest(listOf("cash-import-session-account-group-v1",
                batchId, group.sourceType, group.originKey)) else null,
            group.currencies, group.rowRefs.size,
            CashImportAccountSuggestionDto(suggestion.accountId, suggestion.expectedAccountRevision, suggestion.basis),
            selection?.let { CashImportAccountSelectionDto(it.accountId, it.expectedAccountRevision) })
    }, lastPreviewInput = review?.inputJson(), canPreview = parsed.any { it.document != null })
}

internal fun localCashImportContextFiles(files: List<LocalCashImportFile>): List<CashImportContextFileDto> = files.map { stored ->
    val file = stored.parsedFile()
    val view = cashImportFileView(file.copy(filename = importDisplayFilename(file.filename))) { it }
    apiJson.decodeFromJsonElement<CashImportContextFileDto>(encodeLocalCashValue(view + ("recognized_row_count" to file.document?.observedCount?.toLong())))
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
