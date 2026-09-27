package com.finance.tracker.presentation

import com.finance.tracker.core.*
import com.finance.tracker.domain.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

@Composable
internal fun InvestmentEventsScreen(
    repository: InvestmentRepository,
    workspaceId: String,
    sizeClass: WindowSizeClass,
    diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
) {
    val viewModel: InvestmentEventsViewModel = viewModel(
        key = "investment-events:$workspaceId",
        factory = viewModelFactory { initializer { InvestmentEventsViewModel(repository, diagnostics) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.initialize() }

    val accounts = state.accounts
    val filters = state.filters
    val page = state.page
    val events = state.events
    val selected = state.selected
    val evidence = state.evidence
    val evidenceLoading = state.evidenceLoading
    val evidenceError = state.evidenceErrorCode?.let { investmentErrorText(it) }
    val loading = state.loading
    val loadingMore = state.loadingMore
    val appendError = state.appendErrorCode?.let { investmentErrorText(it) }
    val accountsError = state.accountsErrorCode != null
    val error = state.errorCode?.let { investmentErrorText(it) }
    val nextCursor = state.nextCursor
    FeaturePage(localizedText("copy_3228ddffe0"), SemanticIds.investmentEventsScreen) {
        if (sizeClass == WindowSizeClass.COMPACT) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DatePickerInput(filters.dateFrom.orEmpty(), { viewModel.updateFilters(filters.copy(dateFrom = it.ifBlank { null })) }, localizedText("copy_1f29196891"))
                DatePickerInput(filters.dateTo.orEmpty(), { viewModel.updateFilters(filters.copy(dateTo = it.ifBlank { null })) }, localizedText("copy_f4b9b2b5de"))
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DatePickerInput(filters.dateFrom.orEmpty(), { viewModel.updateFilters(filters.copy(dateFrom = it.ifBlank { null })) }, localizedText("copy_1f29196891"), Modifier.weight(1f))
            DatePickerInput(filters.dateTo.orEmpty(), { viewModel.updateFilters(filters.copy(dateTo = it.ifBlank { null })) }, localizedText("copy_f4b9b2b5de"), Modifier.weight(1f))
        }
        val accountOptions = listOf("" to localizedText("copy_c5e5d57837")) + accounts.map { it.id.toString() to it.name }
        val eventTypeOptions = listOf(
            "" to localizedText("copy_fc0eeefb7b"), "funding" to localizedText("copy_122622fc9d"), "trade" to localizedText("copy_adb63b6e93"), "income" to localizedText("copy_aaaf7ca11e"), "expense" to localizedText("copy_eb515982ac"),
            "reversal" to localizedText("copy_9fcefd8dc8"), "subscription" to localizedText("copy_48bb44cfc2"), "adjustment" to localizedText("copy_0a4d26e42b"), "snapshot" to localizedText("copy_9058a1c2c5"),
        )
        if (sizeClass == WindowSizeClass.COMPACT) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoicePicker(localizedText("copy_c66422962f"), filters.accountId.orEmpty(), accountOptions, { viewModel.updateFilters(filters.copy(accountId = it.ifBlank { null })) })
                ChoicePicker(localizedText("copy_5b2d75aa54"), filters.recordType.orEmpty(), eventTypeOptions, { viewModel.updateFilters(filters.copy(recordType = it.ifBlank { null })) })
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ChoicePicker(localizedText("copy_c66422962f"), filters.accountId.orEmpty(), accountOptions, { viewModel.updateFilters(filters.copy(accountId = it.ifBlank { null })) }, Modifier.weight(1f))
            ChoicePicker(localizedText("copy_5b2d75aa54"), filters.recordType.orEmpty(), eventTypeOptions, { viewModel.updateFilters(filters.copy(recordType = it.ifBlank { null })) }, Modifier.weight(1f))
        }
        LabeledInput(filters.ticker.orEmpty(), { viewModel.updateFilters(filters.copy(ticker = it.ifBlank { null })) }, localizedText("copy_47a935d1ef"))
        if (accountsError) StateMessage(localizedText("copy_13f8ed4623"), isError = true) {
            TextButton(onClick = { viewModel.reload() }) { Text(localizedText("copy_e2d53a6d3a")) }
        }
        if (loading && events.isEmpty()) StateMessage(localizedText("copy_dd1212991d"))
        if (error != null) StateMessage(error.orEmpty(), isError = true) { TextButton(onClick = { viewModel.reload() }) { Text(localizedText("copy_e2d53a6d3a")) } }
        if (!loading && error == null && events.isEmpty()) StateMessage(localizedText("copy_f60d48a9b9"))
        events.forEach { event ->
            SectionCard(modifier = Modifier.testTag("investment-event-${event.eventId}")) {
                if (sizeClass == WindowSizeClass.COMPACT) {
                    Column(Modifier.weight(1f)) {
                        Text(eventTitle(event), style = MaterialTheme.typography.titleMedium)
                        Text("${formatLocalDateTime(event.occurredAt)}　${event.account.name}", style = MaterialTheme.typography.bodySmall)
                    }
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(eventTitle(event), style = MaterialTheme.typography.titleMedium)
                        Text("${formatLocalDateTime(event.occurredAt)}　${event.account.name}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(event.currency, style = MaterialTheme.typography.labelLarge)
                }
                investmentAssetLines(event).forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (event.commission.amount != null && displayDecimalSign(event.commission.amount) != 0) {
                    Text(localizedText("copy_267bdc72ce", formatInvestmentAmount(event.commission.amount), event.commission.asset ?: event.currency))
                }
                if (event.note.isNotBlank() || event.sourceType != null) Text(event.note.ifBlank { localizedText("copy_6d33d6cd64") })
                TextButton(onClick = { viewModel.selectEvent(event) }, modifier = Modifier.testTag(SemanticIds.investmentEventDetail)) { Text(localizedText("copy_faea8c1db9")) }
            }
        }
        if (appendError != null) StateMessage(appendError.orEmpty(), isError = true) {
            TextButton(onClick = { viewModel.loadMore(retry = true) }, enabled = !loadingMore) { Text(localizedText("copy_b647ee0bdc")) }
        }
        if (nextCursor != null && appendError == null) Button(onClick = { viewModel.loadMore() }, enabled = !loadingMore, modifier = Modifier.testTag(SemanticIds.investmentEventsLoadMore)) {
            Text(if (loadingMore) localizedText("copy_fcabadb2a7") else localizedText("copy_3a0fab4978"))
        }
    }

    selected?.let { event ->
        Dialog(onDismissRequest = viewModel::closeEvidence, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            SectionCard(Modifier.fillMaxWidth().padding(16.dp).widthIn(max = 720.dp).heightIn(max = 760.dp)) {
                Text(eventTitle(event), style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (evidenceLoading) Text(localizedText("copy_1087df4607"))
                    InlineError(evidenceError)
                    PositionFact(localizedText("copy_51f85a78ca"), formatLocalDateTime(event.occurredAt))
                    PositionFact(localizedText("copy_c3d92b20c8"), event.account.name)
                    PositionFact(localizedText("copy_5b2d75aa54"), eventTitle(event))
                    investmentAssetLines(event).forEach { (label, value) -> PositionFact(label, value) }
                    if (event.commission.amount != null && displayDecimalSign(event.commission.amount) != 0) {
                        PositionFact(localizedText("copy_307d666742"), "${formatInvestmentAmount(event.commission.amount)} ${event.commission.asset ?: event.currency}")
                    }
                    evidence?.let { evidenceValue ->
                        investmentEvidenceFacts(evidenceValue.event, evidenceValue.relations).forEach { fact ->
                            HorizontalDivider()
                            PositionFact(localizedText(fact.labelResourceKey), fact.valueResourceKey?.let { localizedText(it) } ?: fact.value)
                        }
                    }
                    if (evidenceError != null) TextButton(onClick = viewModel::retryEvidence) { Text(localizedText("copy_e2d53a6d3a")) }
                }
                TextButton(onClick = viewModel::closeEvidence, modifier = Modifier.testTag(SemanticIds.investmentEventDetailClose)) { Text(localizedText("copy_6c14bd7f6f")) }
            }
        }
    }
}

@Composable
internal fun eventTitle(event: InvestmentEvent): String = localizedText(investmentEventTitleResourceKey(event))

internal fun investmentEventTitleResourceKey(event: InvestmentEvent): String = when (event.recordType) {
    "trade" -> {
        val fromCash = event.fromAsset.ticker.isNullOrBlank() || event.fromAsset.ticker.equals(event.currency, true)
        val toCash = event.toAsset.ticker.isNullOrBlank() || event.toAsset.ticker.equals(event.currency, true)
        when {
            fromCash && !toCash -> "copy_839b83828f"
            !fromCash && toCash -> "copy_9b8ddae2cd"
            else -> "copy_adb63b6e93"
        }
    }
    "funding" -> "copy_122622fc9d"
    "income" -> "copy_aaaf7ca11e"
    "expense" -> "copy_eb515982ac"
    "reversal" -> "copy_9fcefd8dc8"
    "subscription" -> "copy_48bb44cfc2"
    "adjustment" -> "copy_0a4d26e42b"
    "snapshot" -> if (event.recordSubtype == "cash") "copy_ce72d31434" else "copy_0621f29abb"
    else -> "copy_7d5f1065ae"
}

@Composable
internal fun investmentAssetLines(event: InvestmentEvent): List<Pair<String, String>> =
    investmentAssetLineFacts(event).map { (labelKey, value) -> localizedText(labelKey) to value }

internal fun investmentAssetLineFacts(event: InvestmentEvent): List<Pair<String, String>> {
    fun hasValue(asset: InvestmentAsset): Boolean = asset.amount?.let { displayDecimalSign(it) != 0 } ?: !asset.ticker.isNullOrBlank()
    fun value(asset: InvestmentAsset, direction: String): String {
        val ticker = (asset.ticker ?: event.currency).uppercase()
        val amount = asset.amount ?: return "— $ticker"
        val formatted = formatInvestmentAmount(amount.removePrefix("-").removePrefix("+"))
        val sign = if (formatted == "0" || direction == "neutral") "" else if (direction == "outflow") "−" else "+"
        return "$sign$formatted $ticker"
    }
    if (event.recordType == "snapshot") {
        val asset = if (event.toAsset.amount != null || !event.toAsset.ticker.isNullOrBlank()) event.toAsset else event.fromAsset
        return listOf((if (event.recordSubtype == "cash") "copy_51cc55073e" else "copy_08d3013927") to value(asset, "neutral"))
    }
    return buildList {
        if (hasValue(event.fromAsset)) add("copy_e3863e1f11" to value(event.fromAsset, "outflow"))
        if (hasValue(event.toAsset)) add("copy_db47592d34" to value(event.toAsset, "inflow"))
    }
}

internal fun groupDecimalInteger(value: String): String {
    val negative = value.startsWith('-')
    val unsigned = value.removePrefix("-")
    val parts = unsigned.split('.', limit = 2)
    val whole = parts.first().reversed().chunked(3).joinToString(",").reversed()
    val fraction = parts.getOrNull(1)?.trimEnd('0').orEmpty()
    return (if (negative) "−" else "") + whole + if (fraction.isEmpty()) "" else ".$fraction"
}

@Composable
internal fun quoteStatusLabel(value: String?): String = when (value) {
    "complete" -> localizedText("copy_70483de25f")
    "stale" -> localizedText("copy_0ee70aa683")
    "partial" -> localizedText("copy_8285e7b971")
    "unsupported" -> localizedText("copy_f9c565b337")
    else -> localizedText("copy_793f6d11e3")
}
@Composable
internal fun investmentErrorText(code: String): String = when (code) {
    "authentication_required" -> localizedText("copy_8e38717a57")
    "workspace_forbidden" -> localizedText("copy_dd9a4bdee0")
    "network_unavailable" -> localizedText("copy_e0a1c9fd69")
    "investment_refresh_started" -> localizedText("copy_b8c1d6f53c")
    "investment_refresh_timeout" -> localizedText("copy_7931c4ead5")
    "investment_refresh_unavailable" -> localizedText("copy_b5574e30c7")
    "unknown_error" -> localizedText("copy_382ef5a830")
    else -> localizedText("copy_e6a62f3e45")
}
