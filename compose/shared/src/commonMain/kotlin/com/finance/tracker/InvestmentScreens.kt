package com.finance.tracker

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private val portfolioPeriods = listOf(
    "24h" to "近 24 小时",
    "week_to_date" to "本周至今",
    "month_to_date" to "本月至今",
    "30d" to "近 30 天",
    "90d" to "近 90 天",
    "year_to_date" to "年初至今",
    "365d" to "近 365 天",
)

@Composable
internal fun InvestmentHoldingsScreen(api: FinanceApiClient, sizeClass: WindowSizeClass) {
    var portfolio by remember(api) { mutableStateOf<PortfolioDto?>(null) }
    var accounts by remember(api) { mutableStateOf<List<AccountDto>>(emptyList()) }
    var accountsError by remember(api) { mutableStateOf(false) }
    var displayOptions by remember { mutableStateOf(loadInvestmentDisplayOptions()) }
    var currencyDraft by remember { mutableStateOf(displayOptions.currency) }
    var tickerFilter by remember { mutableStateOf("") }
    var loading by remember(api) { mutableStateOf(true) }
    var refreshing by remember(api) { mutableStateOf(false) }
    var error by remember(api) { mutableStateOf<String?>(null) }
    var refreshMessage by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var expandedPosition by remember { mutableStateOf<InvestmentHoldingRow?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(api, reloadKey) {
        accountsError = false
        try {
            val loadedAccounts = api.fetchInvestmentAccounts()
            accounts = loadedAccounts
            if (displayOptions.accountId.isNotBlank() && loadedAccounts.none { it.id.toString() == displayOptions.accountId }) {
                displayOptions = displayOptions.copy(accountId = "")
            }
        } catch (_: Throwable) {
            accountsError = true
        }
    }
    LaunchedEffect(api, displayOptions.currency, displayOptions.period, reloadKey) {
        loading = true
        error = null
        try {
            portfolio = api.fetchInvestmentPortfolio(displayOptions.currency.ifBlank { null }, displayOptions.period)
        } catch (cause: Throwable) {
            error = userError(cause)
        } finally {
            loading = false
        }
    }
    LaunchedEffect(api, displayOptions.currency, displayOptions.period) {
        api.streamInvestmentPortfolio(
            displayCurrency = displayOptions.currency.ifBlank { null },
            period = displayOptions.period,
            onPortfolio = { incoming ->
                portfolio = portfolio?.let { retainKnownInvestmentValuation(it, incoming) } ?: incoming
                refreshing = false
                error = null
                refreshMessage = null
            },
            onRefreshError = {
                if (refreshing) {
                    refreshing = false
                    refreshMessage = "暂时无法刷新估值，请稍后重试。"
                }
            },
        )
    }
    LaunchedEffect(refreshing) {
        if (!refreshing) return@LaunchedEffect
        delay(20_000)
        if (refreshing) {
            refreshing = false
            refreshMessage = "暂时没有收到最新估值，请稍后重试。"
        }
    }
    LaunchedEffect(displayOptions) { saveInvestmentDisplayOptions(displayOptions) }
    val display = investmentDisplayData(portfolio, accounts, displayOptions)
    val visibleRows = display.rows.filter { row ->
        tickerFilter.isBlank() || row.position.ticker.contains(tickerFilter.trim(), true) || row.position.displayName.orEmpty().contains(tickerFilter.trim(), true)
    }
    val accountOptions = listOf("" to "全部账户") + accounts.map { it.id.toString() to it.name }
    val currencyError = currencyDraft.isNotBlank() && !Regex("^[A-Z]{3}$").matches(currencyDraft)
    val summaryCurrency = displayOptions.currency.ifBlank { display.currencies.singleOrNull()?.currency ?: display.accounts.firstOrNull()?.currency.orEmpty() }

    FeaturePage("当前持仓", SemanticIds.investmentHoldingsScreen) {
        if (accountsError) StateMessage("暂时无法读取账户。", isError = true) {
            TextButton(onClick = { reloadKey++ }) { Text("重试") }
        }
        if (sizeClass == WindowSizeClass.COMPACT) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoicePicker("账户", displayOptions.accountId, accountOptions, { displayOptions = displayOptions.copy(accountId = it) })
                ChoicePicker("排序", displayOptions.sort, listOf("market_value_desc" to "市值倒序", "profit_desc" to "浮盈亏倒序", "ticker_asc" to "标的名称"), { displayOptions = displayOptions.copy(sort = it) })
                ChoicePicker("同一标的", displayOptions.grouping, listOf("split" to "分开显示", "merge" to "合并显示"), { displayOptions = displayOptions.copy(grouping = it) })
                LabeledInput(currencyDraft, { value ->
                    val next = value.uppercase()
                    currencyDraft = next
                    if (next.isEmpty() || Regex("^[A-Z]{3}$").matches(next)) displayOptions = displayOptions.copy(currency = next)
                }, "展示币种", isError = currencyError, onBlur = { if (currencyError) currencyDraft = displayOptions.currency })
                ChoicePicker("时间范围", displayOptions.period, portfolioPeriods, { displayOptions = displayOptions.copy(period = it) })
                LabeledInput(tickerFilter, { tickerFilter = it }, "搜索标的")
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoicePicker("账户", displayOptions.accountId, accountOptions, { displayOptions = displayOptions.copy(accountId = it) })
                ChoicePicker("排序", displayOptions.sort, listOf("market_value_desc" to "市值倒序", "profit_desc" to "浮盈亏倒序", "ticker_asc" to "标的名称"), { displayOptions = displayOptions.copy(sort = it) })
                ChoicePicker("同一标的", displayOptions.grouping, listOf("split" to "分开显示", "merge" to "合并显示"), { displayOptions = displayOptions.copy(grouping = it) })
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledInput(currencyDraft, { value ->
                    val next = value.uppercase()
                    currencyDraft = next
                    if (next.isEmpty() || Regex("^[A-Z]{3}$").matches(next)) displayOptions = displayOptions.copy(currency = next)
                }, "展示币种", isError = currencyError, onBlur = { if (currencyError) currencyDraft = displayOptions.currency })
                ChoicePicker("时间范围", displayOptions.period, portfolioPeriods, { displayOptions = displayOptions.copy(period = it) })
                LabeledInput(tickerFilter, { tickerFilter = it }, "搜索标的")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    refreshing = true
                    refreshMessage = null
                    try {
                        api.refreshInvestmentPortfolio(displayOptions.currency.ifBlank { null }, displayOptions.period)
                        refreshMessage = "正在更新估值。"
                    } catch (cause: Throwable) {
                        refreshMessage = userError(cause)
                        refreshing = false
                    }
                }
            }, enabled = !refreshing, modifier = Modifier.testTag("investment-holdings-refresh")) { Text(if (refreshing) "正在刷新…" else "刷新估值") }
            TextButton(onClick = { reloadKey++ }) { Text("重新读取") }
        }
        if (currencyError) InlineError("请输入三位币种代码。")
        InlineError(refreshMessage)
        if (loading && portfolio == null) StateMessage("正在读取持仓…")
        if (error != null) StateMessage(error.orEmpty(), isError = true) { TextButton(onClick = { reloadKey++ }) { Text("重试") } }
        portfolio?.let {
            SectionCard {
                Text("持仓概览", style = MaterialTheme.typography.titleLarge)
                if (displayOptions.currency.isBlank() && display.currencies.size > 1) {
                    Text("当前市值")
                    display.currencies.forEach { currency ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(currency.currency)
                            Text(formatPortfolioMoney(currency.marketValue, currency.currency))
                        }
                    }
                    Text("浮盈亏")
                    display.currencies.forEach { currency ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(currency.currency)
                            Text(formatPortfolioMoney(currency.profit, currency.currency, signed = true))
                        }
                    }
                } else {
                    PositionFact("当前总市值", formatPortfolioMoney(display.marketValue, summaryCurrency))
                    PositionFact("总浮盈亏", formatPortfolioMoney(display.profit, summaryCurrency, signed = true))
                    PositionFact("浮盈亏率", formatPortfolioPercent(display.profitRate))
                    val periodLabel = portfolioPeriods.firstOrNull { it.first == displayOptions.period }?.second ?: displayOptions.period
                    PositionFact("${periodLabel}盈亏", formatPortfolioMoney(display.periodProfit, summaryCurrency, signed = true))
                    PositionFact("${periodLabel}盈亏率", formatPortfolioPercent(display.periodProfitRate))
                }
                val baselineDates = display.periodBaselines.map { formatLocalDateTime(it.occurredAt) }.distinct()
                if (baselineDates.isNotEmpty()) Text("以 ${baselineDates.joinToString("、")} 的记录为基准，可能无法反映真实盈亏。", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!loading && error == null && visibleRows.isEmpty()) StateMessage("当前没有持仓。")
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
            val facts = if (position.isCash) listOf("当前市值" to formatPortfolioMoney(marketValue, rowCurrency)) else listOf(
                "当前单价" to formatPortfolioMoney(currentPrice, displayOptions.currency.ifBlank { position.quoteCurrency ?: "" }),
                "平均成本" to formatPortfolioMoney(averageCost, displayOptions.currency.ifBlank { position.costCurrency }),
                "持有数量" to "${formatInvestmentAmount(position.shares)} ${position.ticker}",
                "当前市值" to formatPortfolioMoney(marketValue, rowCurrency),
                "仓位" to formatPortfolioPercent(positionWeight),
                "浮盈亏" to formatPortfolioMoney(displayedProfit, displayOptions.currency.ifBlank { position.costCurrency }, signed = true),
                "浮盈亏率" to formatPortfolioPercent(positionRate),
                "${periodLabel}盈亏" to formatPortfolioMoney(displayedPeriodProfit, rowCurrency, signed = true),
                "${periodLabel}盈亏率" to formatPortfolioPercent(position.periodProfitRate),
            )
            SectionCard(modifier = Modifier.testTag("${SemanticIds.investmentHoldingsScreen}-${position.ticker}")) {
                Column {
                    Text(position.displayName?.takeIf(String::isNotBlank) ?: currencyDisplayName(position.ticker, position.isCash), style = MaterialTheme.typography.titleMedium)
                    Text("${position.ticker} · $accountName", style = MaterialTheme.typography.bodySmall)
                }
                PositionFacts(facts, sizeClass)
                if (!position.isCash) Text("${quoteStatusLabel(position.quoteStatus)} · ${position.quoteSession?.let(::quoteSessionLabel) ?: "时段未知"} · ${position.quoteObservedAt?.let(::formatRelativeQuoteTime) ?: "报价时间未知"}", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { expandedPosition = holding }) { Text("查看估值详情") }
                    Text(formatPortfolioMoney(marketValue, rowCurrency), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    expandedPosition?.let { holding ->
        val position = holding.position
        Dialog(onDismissRequest = { expandedPosition = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            SectionCard(Modifier.fillMaxWidth().padding(16.dp).widthIn(max = 720.dp).heightIn(max = 760.dp)) {
                Text(position.displayName ?: position.ticker, style = MaterialTheme.typography.headlineSmall)
                Text("${position.ticker} · ${holding.accountName}")
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    PositionFact("持有数量", "${formatInvestmentAmount(position.shares)} ${position.ticker}")
                    PositionFact("总成本", formatPortfolioMoney(position.totalCost, position.costCurrency))
                    PositionFact("当前单价", formatPortfolioMoney(position.currentPrice, position.quoteCurrency))
                    PositionFact("当前市值", formatPortfolioMoney(position.marketValue, position.quoteCurrency))
                    PositionFact("折算市值", formatPortfolioMoney(position.displayMarketValue, position.displayCurrency))
                    PositionFact("估值状态", quoteStatusLabel(position.quoteStatus))
                    PositionFact("报价时间", position.quoteObservedAt?.let(::formatLocalDateTime) ?: "报价时间未知")
                    position.quoteReason?.let { PositionFact("说明", readableValuationReason(it)) }
                    PositionFact("汇率状态", fxStatusLabel(position.fxStatus))
                    position.fxReason?.let { PositionFact("说明", readableValuationReason(it)) }
                    position.periodBaselines.forEach { baseline -> PositionFact("周期基准", "${baseline.account} · ${baseline.ticker} · ${formatLocalDateTime(baseline.occurredAt)}") }
                }
                TextButton(onClick = { expandedPosition = null }) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun PositionFact(label: String, value: String) {
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

private fun currencyDisplayName(ticker: String, isCash: Boolean): String {
    if (!isCash) return ticker.uppercase()
    return when (ticker.lowercase()) {
        "cny" -> "人民币"
        "eur" -> "欧元"
        "gbp" -> "英镑"
        "hkd" -> "港币"
        "jpy" -> "日元"
        "usd" -> "美元"
        else -> ticker.uppercase()
    }
}

private fun fxStatusLabel(value: String?): String = when (value) {
    "complete" -> "汇率完整"
    "stale" -> "汇率较旧"
    "partial" -> "汇率不完整"
    "unsupported" -> "暂不支持"
    else -> "暂无汇率"
}

private fun readableValuationReason(value: String): String = when (value) {
    "market_closed" -> "市场休市，沿用最近报价。"
    "quote_stale" -> "报价时间较早。"
    "quote_unavailable" -> "暂时无法取得报价。"
    "currency_unsupported" -> "暂不支持该币种换算。"
    "fx_unavailable" -> "暂时无法取得汇率。"
    else -> "暂时无法完整估值。"
}

private fun quoteSessionLabel(value: String): String = when (value) {
    "pre_market" -> "盘前"
    "regular" -> "盘中"
    "post_market" -> "盘后"
    "overnight" -> "夜盘"
    else -> "时段未知"
}

private fun formatRelativeQuoteTime(value: String): String = "报价时间 ${formatLocalDateTime(value)}"

@Composable
internal fun InvestmentEventsScreen(api: FinanceApiClient, sizeClass: WindowSizeClass) {
    var accounts by remember(api) { mutableStateOf<List<AccountDto>>(emptyList()) }
    var filters by remember { mutableStateOf(InvestmentFiltersDto()) }
    var page by remember(api) { mutableStateOf<InvestmentPageDto?>(null) }
    var events by remember(api) { mutableStateOf<List<InvestmentEventDto>>(emptyList()) }
    var selected by remember { mutableStateOf<InvestmentEventDto?>(null) }
    var evidence by remember { mutableStateOf<InvestmentEvidenceDto?>(null) }
    var evidenceLoading by remember { mutableStateOf(false) }
    var evidenceError by remember { mutableStateOf<String?>(null) }
    var loading by remember(api) { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var appendError by remember(api) { mutableStateOf<String?>(null) }
    var accountsError by remember(api) { mutableStateOf(false) }
    var error by remember(api) { mutableStateOf<String?>(null) }
    var nextCursor by remember(api) { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(api, reloadKey) {
        accountsError = false
        try { accounts = api.fetchInvestmentAccounts() }
        catch (_: Throwable) { accountsError = true }
    }
    LaunchedEffect(api, filters, reloadKey) {
        loading = true
        error = null
        appendError = null
        events = emptyList()
        try {
            val value = api.fetchInvestmentPage(filters)
            page = value
            events = value.items
            nextCursor = value.nextCursor
        } catch (cause: Throwable) {
            error = userError(cause)
        } finally { loading = false }
    }

    fun loadMoreEvents(retry: Boolean = false) {
        val cursor = nextCursor ?: return
        if (loadingMore || (!retry && appendError != null)) return
        scope.launch {
            loadingMore = true
            appendError = null
            try {
                val value = api.fetchInvestmentPage(filters, cursor)
                if (value.dataVersion != page?.dataVersion) reloadKey++ else {
                    events = events + value.items.filter { event -> events.none { it.eventId == event.eventId } }
                    page = value
                    nextCursor = value.nextCursor
                }
            } catch (cause: Throwable) {
                appendError = userError(cause)
            } finally { loadingMore = false }
        }
    }

    FeaturePage("投资事件", SemanticIds.investmentEventsScreen) {
        if (sizeClass == WindowSizeClass.COMPACT) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DatePickerInput(filters.dateFrom.orEmpty(), { filters = filters.copy(dateFrom = it.ifBlank { null }) }, "开始日期")
                DatePickerInput(filters.dateTo.orEmpty(), { filters = filters.copy(dateTo = it.ifBlank { null }) }, "结束日期")
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DatePickerInput(filters.dateFrom.orEmpty(), { filters = filters.copy(dateFrom = it.ifBlank { null }) }, "开始日期", Modifier.weight(1f))
            DatePickerInput(filters.dateTo.orEmpty(), { filters = filters.copy(dateTo = it.ifBlank { null }) }, "结束日期", Modifier.weight(1f))
        }
        val accountOptions = listOf("" to "全部账户") + accounts.map { it.id.toString() to it.name }
        val eventTypeOptions = listOf(
            "" to "全部事件类型", "funding" to "转入资金", "trade" to "买卖", "income" to "收入", "expense" to "支出",
            "reversal" to "撤销", "subscription" to "认购", "adjustment" to "调整", "snapshot" to "记录",
        )
        if (sizeClass == WindowSizeClass.COMPACT) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoicePicker("投资账户", filters.accountId.orEmpty(), accountOptions, { filters = filters.copy(accountId = it.ifBlank { null }) })
                ChoicePicker("事件类型", filters.recordType.orEmpty(), eventTypeOptions, { filters = filters.copy(recordType = it.ifBlank { null }) })
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ChoicePicker("投资账户", filters.accountId.orEmpty(), accountOptions, { filters = filters.copy(accountId = it.ifBlank { null }) }, Modifier.weight(1f))
            ChoicePicker("事件类型", filters.recordType.orEmpty(), eventTypeOptions, { filters = filters.copy(recordType = it.ifBlank { null }) }, Modifier.weight(1f))
        }
        LabeledInput(filters.ticker.orEmpty(), { filters = filters.copy(ticker = it.ifBlank { null }) }, "标的代码")
        if (accountsError) StateMessage("暂时无法读取账户。", isError = true) {
            TextButton(onClick = { reloadKey++ }) { Text("重试") }
        }
        if (loading && events.isEmpty()) StateMessage("正在读取投资事件…")
        if (error != null) StateMessage(error.orEmpty(), isError = true) { TextButton(onClick = { reloadKey++ }) { Text("重试") } }
        if (!loading && error == null && events.isEmpty()) StateMessage("没有匹配的投资事件。")
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
                    Text("手续费：${formatInvestmentAmount(event.commission.amount)} ${event.commission.asset ?: event.currency}")
                }
                if (event.note.isNotBlank() || event.sourceType != null) Text(event.note.ifBlank { "导入记录" })
                TextButton(onClick = {
                    selected = event
                    evidence = null
                    evidenceError = null
                    evidenceLoading = true
                    scope.launch {
                        try { evidence = api.fetchInvestmentEvidence(event.eventId) }
                        catch (cause: Throwable) { evidenceError = userError(cause) }
                        finally { evidenceLoading = false }
                    }
                }, modifier = Modifier.testTag(SemanticIds.investmentEventDetail)) { Text("查看详情") }
            }
        }
        if (appendError != null) StateMessage(appendError.orEmpty(), isError = true) {
            TextButton(onClick = { loadMoreEvents(retry = true) }, enabled = !loadingMore) { Text("重试加载") }
        }
        if (nextCursor != null && appendError == null) Button(onClick = { loadMoreEvents() }, enabled = !loadingMore, modifier = Modifier.testTag(SemanticIds.investmentEventsLoadMore)) {
            Text(if (loadingMore) "正在读取…" else "加载更多")
        }
    }

    selected?.let { event ->
        Dialog(onDismissRequest = { selected = null; evidence = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            SectionCard(Modifier.fillMaxWidth().padding(16.dp).widthIn(max = 720.dp).heightIn(max = 760.dp)) {
                Text(eventTitle(event), style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (evidenceLoading) Text("正在读取详情…")
                    InlineError(evidenceError)
                    PositionFact("发生时间", formatLocalDateTime(event.occurredAt))
                    PositionFact("账户", event.account.name)
                    PositionFact("事件类型", eventTitle(event))
                    investmentAssetLines(event).forEach { (label, value) -> PositionFact(label, value) }
                    if (event.commission.amount != null && displayDecimalSign(event.commission.amount) != 0) {
                        PositionFact("手续费", "${formatInvestmentAmount(event.commission.amount)} ${event.commission.asset ?: event.currency}")
                    }
                    evidence?.let { evidenceValue ->
                        investmentEvidenceFacts(evidenceValue.event, evidenceValue.relations).forEach { fact ->
                            HorizontalDivider()
                            PositionFact(fact.label, fact.value)
                        }
                    }
                    if (evidenceError != null) TextButton(onClick = {
                        scope.launch {
                            evidenceLoading = true
                            evidenceError = null
                            try { evidence = api.fetchInvestmentEvidence(event.eventId) }
                            catch (cause: Throwable) { evidenceError = userError(cause) }
                            finally { evidenceLoading = false }
                        }
                    }) { Text("重试") }
                }
                TextButton(onClick = { selected = null; evidence = null }, modifier = Modifier.testTag(SemanticIds.investmentEventDetailClose)) { Text("关闭") }
            }
        }
    }
}

internal fun eventTitle(event: InvestmentEventDto): String = when (event.recordType) {
    "trade" -> {
        val fromCash = event.fromAsset.ticker.isNullOrBlank() || event.fromAsset.ticker.equals(event.currency, true)
        val toCash = event.toAsset.ticker.isNullOrBlank() || event.toAsset.ticker.equals(event.currency, true)
        when {
            fromCash && !toCash -> "买入"
            !fromCash && toCash -> "卖出"
            else -> "买卖"
        }
    }
    "funding" -> "转入资金"
    "income" -> "收入"
    "expense" -> "支出"
    "reversal" -> "撤销"
    "subscription" -> "认购"
    "adjustment" -> "调整"
    "snapshot" -> if (event.recordSubtype == "cash") "记录余额" else "记录持仓"
    else -> "投资记录"
}

internal fun investmentAssetLines(event: InvestmentEventDto): List<Pair<String, String>> {
    fun hasValue(asset: InvestmentAssetDto): Boolean = asset.amount?.let { displayDecimalSign(it) != 0 } ?: !asset.ticker.isNullOrBlank()
    fun value(asset: InvestmentAssetDto, direction: String): String {
        val ticker = (asset.ticker ?: event.currency).uppercase()
        val amount = asset.amount ?: return "— $ticker"
        val formatted = formatInvestmentAmount(amount.removePrefix("-").removePrefix("+"))
        val sign = if (formatted == "0" || direction == "neutral") "" else if (direction == "outflow") "−" else "+"
        return "$sign$formatted $ticker"
    }
    if (event.recordType == "snapshot") {
        val asset = if (event.toAsset.amount != null || !event.toAsset.ticker.isNullOrBlank()) event.toAsset else event.fromAsset
        return listOf((if (event.recordSubtype == "cash") "余额" else "持有") to value(asset, "neutral"))
    }
    return buildList {
        if (hasValue(event.fromAsset)) add("流出" to value(event.fromAsset, "outflow"))
        if (hasValue(event.toAsset)) add("流入" to value(event.toAsset, "inflow"))
    }
}

private fun groupDecimalInteger(value: String): String {
    val negative = value.startsWith('-')
    val unsigned = value.removePrefix("-")
    val parts = unsigned.split('.', limit = 2)
    val whole = parts.first().reversed().chunked(3).joinToString(",").reversed()
    val fraction = parts.getOrNull(1)?.trimEnd('0').orEmpty()
    return (if (negative) "−" else "") + whole + if (fraction.isEmpty()) "" else ".$fraction"
}

private fun quoteStatusLabel(value: String?): String = when (value) {
    "complete" -> "行情完整"
    "stale" -> "行情较旧"
    "partial" -> "行情不完整"
    "unsupported" -> "暂不支持"
    else -> "暂无估值"
}
