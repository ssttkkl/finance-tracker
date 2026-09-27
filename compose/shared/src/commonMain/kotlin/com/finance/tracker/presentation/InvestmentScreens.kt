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

private val portfolioPeriodResources = listOf(
    "24h" to "copy_05c6f396a2",
    "week_to_date" to "copy_32034e0ecf",
    "month_to_date" to "copy_ec1b7c8e50",
    "30d" to "copy_00a0a58be6",
    "90d" to "copy_0e9926eb9f",
    "year_to_date" to "copy_c92ce5eb5c",
    "365d" to "copy_7b32786fd2",
)

@Composable
internal fun InvestmentHoldingsScreen(
    repository: InvestmentRepository,
    workspaceId: String,
    sizeClass: WindowSizeClass,
    diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
) {
    val portfolioPeriods = portfolioPeriodResources.map { (value, key) -> value to localizedText(key) }
    val viewModel: InvestmentHoldingsViewModel = viewModel(
        key = "investment-holdings:$workspaceId",
        factory = viewModelFactory { initializer { InvestmentHoldingsViewModel(repository, diagnostics) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.initialize() }

    val portfolio = state.portfolio
    val accounts = state.accounts
    val accountsError = state.accountsErrorCode != null
    val displayOptions = state.displayOptions
    val currencyDraft = state.currencyDraft
    val tickerFilter = state.tickerFilter
    val loading = state.loading
    val refreshing = state.refreshing
    val error = state.errorCode?.let { investmentErrorText(it) }
    val refreshMessage = state.refreshMessageCode?.let { investmentErrorText(it) }
    val expandedPosition = state.expandedPosition
    val display = investmentDisplayData(portfolio, accounts, displayOptions)
    val visibleRows = display.rows.filter { row ->
        tickerFilter.isBlank() || row.position.ticker.contains(tickerFilter.trim(), true) ||
            row.position.displayName.orEmpty().contains(tickerFilter.trim(), true)
    }
    val accountOptions = listOf("" to localizedText("copy_c5e5d57837")) + accounts.map { it.id.toString() to it.name }
    val currencyError = currencyDraft.isNotBlank() && !Regex("^[A-Z]{3}$").matches(currencyDraft)
    val summaryCurrency = displayOptions.currency.ifBlank {
        display.currencies.singleOrNull()?.currency ?: display.accounts.firstOrNull()?.currency.orEmpty()
    }
    FeaturePage(localizedText("copy_724084b56b"), SemanticIds.investmentHoldingsScreen) {
        if (accountsError) StateMessage(localizedText("copy_13f8ed4623"), isError = true) {
            TextButton(onClick = { viewModel.reload() }) { Text(localizedText("copy_e2d53a6d3a")) }
        }
        if (sizeClass == WindowSizeClass.COMPACT) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoicePicker(localizedText("copy_c3d92b20c8"), displayOptions.accountId, accountOptions, { viewModel.updateDisplayOptions(displayOptions.copy(accountId = it)) })
                ChoicePicker(localizedText("copy_dc35af8d69"), displayOptions.sort, listOf("market_value_desc" to localizedText("copy_78cce8bd24"), "profit_desc" to localizedText("copy_b3d9d1f6e9"), "ticker_asc" to localizedText("copy_d93f4c9f05")), { viewModel.updateDisplayOptions(displayOptions.copy(sort = it)) })
                ChoicePicker(localizedText("copy_4f3f8fb53f"), displayOptions.grouping, listOf("split" to localizedText("copy_0f296cc8d7"), "merge" to localizedText("copy_3296293efd")), { viewModel.updateDisplayOptions(displayOptions.copy(grouping = it)) })
                LabeledInput(currencyDraft, { value ->
                    viewModel.updateCurrencyDraft(value)
                }, localizedText("copy_4b29018db3"), isError = currencyError, onBlur = { if (currencyError) viewModel.reportInvalidCurrencyDraft() })
                ChoicePicker(localizedText("copy_2be9040878"), displayOptions.period, portfolioPeriods, { viewModel.updateDisplayOptions(displayOptions.copy(period = it)) })
                LabeledInput(tickerFilter, viewModel::updateTickerFilter, localizedText("copy_d362355ab7"))
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoicePicker(localizedText("copy_c3d92b20c8"), displayOptions.accountId, accountOptions, { viewModel.updateDisplayOptions(displayOptions.copy(accountId = it)) })
                ChoicePicker(localizedText("copy_dc35af8d69"), displayOptions.sort, listOf("market_value_desc" to localizedText("copy_78cce8bd24"), "profit_desc" to localizedText("copy_b3d9d1f6e9"), "ticker_asc" to localizedText("copy_d93f4c9f05")), { viewModel.updateDisplayOptions(displayOptions.copy(sort = it)) })
                ChoicePicker(localizedText("copy_4f3f8fb53f"), displayOptions.grouping, listOf("split" to localizedText("copy_0f296cc8d7"), "merge" to localizedText("copy_3296293efd")), { viewModel.updateDisplayOptions(displayOptions.copy(grouping = it)) })
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledInput(currencyDraft, { value ->
                    viewModel.updateCurrencyDraft(value)
                }, localizedText("copy_4b29018db3"), isError = currencyError, onBlur = { if (currencyError) viewModel.reportInvalidCurrencyDraft() })
                ChoicePicker(localizedText("copy_2be9040878"), displayOptions.period, portfolioPeriods, { viewModel.updateDisplayOptions(displayOptions.copy(period = it)) })
                LabeledInput(tickerFilter, viewModel::updateTickerFilter, localizedText("copy_d362355ab7"))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::refresh, enabled = !refreshing, modifier = Modifier.testTag("investment-holdings-refresh")) { Text(if (refreshing) localizedText("copy_70c184c284") else localizedText("copy_be4cd1a365")) }
            TextButton(onClick = { viewModel.reload() }) { Text(localizedText("copy_7784972fc8")) }
        }
        if (currencyError) InlineError(localizedText("copy_9a648f5e4a"))
        InlineError(refreshMessage)
        if (loading && portfolio == null) StateMessage(localizedText("copy_ff4fcccbb6"))
        if (error != null) StateMessage(error.orEmpty(), isError = true) { TextButton(onClick = { viewModel.reload() }) { Text(localizedText("copy_e2d53a6d3a")) } }
        portfolio?.let {
            SectionCard {
                Text(localizedText("copy_cb290e2725"), style = MaterialTheme.typography.titleLarge)
                if (displayOptions.currency.isBlank() && display.currencies.size > 1) {
                    Text(localizedText("copy_d722b16702"))
                    display.currencies.forEach { currency ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(currency.currency)
                            Text(formatPortfolioMoney(currency.marketValue, currency.currency))
                        }
                    }
                    Text(localizedText("copy_abb2a6d4dd"))
                    display.currencies.forEach { currency ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(currency.currency)
                            Text(formatPortfolioMoney(currency.profit, currency.currency, signed = true))
                        }
                    }
                } else {
                    PositionFact(localizedText("copy_da5342f351"), formatPortfolioMoney(display.marketValue, summaryCurrency))
                    PositionFact(localizedText("copy_c752dd73a7"), formatPortfolioMoney(display.profit, summaryCurrency, signed = true))
                    PositionFact(localizedText("copy_c11a815ae0"), formatPortfolioPercent(display.profitRate))
                    val periodLabel = portfolioPeriods.firstOrNull { it.first == displayOptions.period }?.second ?: displayOptions.period
                    PositionFact(localizedText("copy_39f0350af6", periodLabel), formatPortfolioMoney(display.periodProfit, summaryCurrency, signed = true))
                    PositionFact(localizedText("copy_13cbd23050", periodLabel), formatPortfolioPercent(display.periodProfitRate))
                }
                val baselineDates = display.periodBaselines.map { formatLocalDateTime(it.occurredAt) }.distinct()
                if (baselineDates.isNotEmpty()) Text(localizedText("copy_7d7a0fad0f", baselineDates.joinToString("、")), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!loading && error == null && visibleRows.isEmpty()) StateMessage(localizedText("copy_ddbb43b6dd"))
        visibleRows.forEach { holding ->
            val position = holding.position
            val accountName = holding.accountName
            val marketValue = if (displayOptions.currency.isBlank()) position.marketValue else position.displayMarketValue
            val rowCurrency = displayOptions.currency.ifBlank { position.quoteCurrency ?: position.costCurrency }
            val currentPrice = if (displayOptions.currency.isNotBlank() && position.fxRate != null) displayDecimalMultiply(position.currentPrice, position.fxRate) else position.currentPrice
            val averageCost = displayDecimalDivide(position.totalCost, position.shares)?.let { average ->
                if (displayOptions.currency.isNotBlank() && position.fxRate != null) displayDecimalMultiply(average, position.fxRate) else average
            }
            val displayedProfit = if (displayOptions.currency.isNotBlank() && position.fxRate != null) displayDecimalMultiply(position.profit, position.fxRate) else position.profit
            val displayedPeriodProfit = if (displayOptions.currency.isNotBlank() && position.fxRate != null) displayDecimalMultiply(position.periodProfit, position.fxRate) else position.periodProfit
            val positionRate = if (position.profit == null) null else displayDecimalDivide(position.profit, displayDecimalAbs(position.totalCost))
            val positionWeight = displayDecimalDivide(usdMarketValue(position), display.usdMarketValue)
            val periodLabel = portfolioPeriods.firstOrNull { it.first == displayOptions.period }?.second ?: displayOptions.period
            val facts = if (position.isCash) listOf(localizedText("copy_d722b16702") to formatPortfolioMoney(marketValue, rowCurrency)) else listOf(
                localizedText("copy_48b17a0d07") to formatPortfolioMoney(currentPrice, displayOptions.currency.ifBlank { position.quoteCurrency ?: "" }),
                localizedText("copy_ca3cd3a270") to formatPortfolioMoney(averageCost, displayOptions.currency.ifBlank { position.costCurrency }),
                localizedText("copy_7988639007") to "${formatInvestmentAmount(position.shares)} ${position.ticker}",
                localizedText("copy_d722b16702") to formatPortfolioMoney(marketValue, rowCurrency),
                localizedText("copy_25a424cad9") to formatPortfolioPercent(positionWeight),
                localizedText("copy_abb2a6d4dd") to formatPortfolioMoney(displayedProfit, displayOptions.currency.ifBlank { position.costCurrency }, signed = true),
                localizedText("copy_c11a815ae0") to formatPortfolioPercent(positionRate),
                localizedText("copy_39f0350af6", periodLabel) to formatPortfolioMoney(displayedPeriodProfit, rowCurrency, signed = true),
                localizedText("copy_13cbd23050", periodLabel) to formatPortfolioPercent(position.periodProfitRate),
            )
            SectionCard(modifier = Modifier.testTag("${SemanticIds.investmentHoldingsScreen}-${position.ticker}")) {
                Column {
                    Text(position.displayName?.takeIf(String::isNotBlank) ?: currencyDisplayName(position.ticker, position.isCash), style = MaterialTheme.typography.titleMedium)
                    Text("${position.ticker} · ${displayInvestmentAccountName(accountName)}", style = MaterialTheme.typography.bodySmall)
                }
                PositionFacts(facts, sizeClass)
                if (!position.isCash) Text("${quoteStatusLabel(position.quoteStatus)} · ${position.quoteSession?.let { quoteSessionLabel(it) } ?: localizedText("copy_9778909c7e")} · ${position.quoteObservedAt?.let { formatRelativeQuoteTime(it) } ?: localizedText("copy_ff06d0514f")}", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { viewModel.showPosition(holding) }) { Text(localizedText("copy_54d721fae2")) }
                    Text(formatPortfolioMoney(marketValue, rowCurrency), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    expandedPosition?.let { holding ->
        val position = holding.position
        Dialog(onDismissRequest = viewModel::closePosition, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            SectionCard(Modifier.fillMaxWidth().padding(16.dp).widthIn(max = 720.dp).heightIn(max = 760.dp)) {
                Text(position.displayName ?: position.ticker, style = MaterialTheme.typography.headlineSmall)
                Text("${position.ticker} · ${displayInvestmentAccountName(holding.accountName)}")
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    PositionFact(localizedText("copy_7988639007"), "${formatInvestmentAmount(position.shares)} ${position.ticker}")
                    PositionFact(localizedText("copy_b87a0cfd10"), formatPortfolioMoney(position.totalCost, position.costCurrency))
                    PositionFact(localizedText("copy_48b17a0d07"), formatPortfolioMoney(position.currentPrice, position.quoteCurrency))
                    PositionFact(localizedText("copy_d722b16702"), formatPortfolioMoney(position.marketValue, position.quoteCurrency))
                    PositionFact(localizedText("copy_187f1c3db4"), formatPortfolioMoney(position.displayMarketValue, position.displayCurrency))
                    PositionFact(localizedText("copy_ab38f691a5"), quoteStatusLabel(position.quoteStatus))
                    PositionFact(localizedText("copy_f3ccf8829e"), position.quoteObservedAt?.let(::formatLocalDateTime) ?: localizedText("copy_ff06d0514f"))
                    position.quoteReason?.let { PositionFact(localizedText("copy_26670dda42"), readableValuationReason(it)) }
                    PositionFact(localizedText("copy_e6c5aeb5ef"), fxStatusLabel(position.fxStatus))
                    position.fxReason?.let { PositionFact(localizedText("copy_26670dda42"), readableValuationReason(it)) }
                    position.periodBaselines.forEach { baseline -> PositionFact(localizedText("copy_5c8868c56c"), "${baseline.account} · ${baseline.ticker} · ${formatLocalDateTime(baseline.occurredAt)}") }
                }
                TextButton(onClick = viewModel::closePosition) { Text(localizedText("copy_6c14bd7f6f")) }
            }
        }
    }
}

@Composable
internal fun PositionFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f).padding(start = 16.dp))
    }
}

@Composable
private fun PositionFacts(facts: List<Pair<String, String>>, sizeClass: WindowSizeClass) {
    if (sizeClass == WindowSizeClass.COMPACT) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            facts.forEach { (label, value) -> PositionFact(label, value) }
        }
    } else {
        val columns = facts.chunked((facts.size + 1) / 2)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            columns.forEach { values ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    values.forEach { (label, value) -> PositionFact(label, value) }
                }
            }
        }
    }
}

private fun formatPortfolioMoney(value: String?, currency: String?, signed: Boolean = false): String {
    if (value == null) return "—"
    val rounded = displayDecimalRound(value, 2) ?: return "—"
    val sign = if (!signed) "" else when (displayDecimalSign(rounded)) {
        -1 -> "−"
        0 -> ""
        else -> "+"
    }
    val amount = groupDecimalInteger(displayDecimalAbs(rounded) ?: rounded)
    return listOfNotNull("$sign$amount", currency?.takeIf(String::isNotBlank)).joinToString(" ")
}

internal fun formatPortfolioPercent(value: String?): String {
    val percentage = displayDecimalMultiply(value, "100") ?: return "—"
    return "${formatPortfolioMoney(percentage, null, signed = true)}%"
}

@Composable
private fun currencyDisplayName(ticker: String, isCash: Boolean): String {
    if (!isCash) return ticker.uppercase()
    return when (ticker.lowercase()) {
        "cny" -> localizedText("copy_52879c171a")
        "eur" -> localizedText("copy_e20007e4ea")
        "gbp" -> localizedText("copy_5efbad21bc")
        "hkd" -> localizedText("copy_39bde903fe")
        "jpy" -> localizedText("copy_8815d9569f")
        "usd" -> localizedText("copy_f2bf63b8f9")
        else -> ticker.uppercase()
    }
}

@Composable
private fun fxStatusLabel(value: String?): String = when (value) {
    "complete" -> localizedText("copy_1cd5f7185c")
    "stale" -> localizedText("copy_9a73457cf7")
    "partial" -> localizedText("copy_720b139a5d")
    "unsupported" -> localizedText("copy_f9c565b337")
    else -> localizedText("copy_6e6e04ffac")
}

@Composable
private fun readableValuationReason(value: String): String = when (value) {
    "market_closed" -> localizedText("copy_b772c3a8e1")
    "quote_stale" -> localizedText("copy_2669799b61")
    "quote_unavailable" -> localizedText("copy_4a59bc5dac")
    "currency_unsupported" -> localizedText("copy_5e68b1aa59")
    "fx_unavailable" -> localizedText("copy_23fcdcccda")
    else -> localizedText("copy_2848e0a00b")
}

@Composable
private fun quoteSessionLabel(value: String): String = when (value) {
    "pre_market" -> localizedText("copy_a975fba4ce")
    "regular" -> localizedText("copy_54d0f0f70b")
    "post_market" -> localizedText("copy_0ab9ca8d36")
    "overnight" -> localizedText("copy_e7a19cbe71")
    else -> localizedText("copy_9778909c7e")
}

@Composable
private fun displayInvestmentAccountName(value: String): String =
    if (value == MERGED_INVESTMENT_ACCOUNTS) localizedText("copy_50d1d690ec") else value

@Composable
private fun formatRelativeQuoteTime(value: String): String = localizedText("copy_3351231a84", formatLocalDateTime(value))
