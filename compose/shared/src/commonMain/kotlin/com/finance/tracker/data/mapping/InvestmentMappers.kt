package com.finance.tracker.data

import com.finance.tracker.core.*
import com.finance.tracker.domain.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

fun InvestmentAssetDto.toDomain(): InvestmentAsset = InvestmentAsset(
    ticker = ticker,
    amount = amount,
)

fun InvestmentAsset.toDto(): InvestmentAssetDto = InvestmentAssetDto(
    ticker = ticker,
    amount = amount,
)

fun InvestmentCommissionDto.toDomain(): InvestmentCommission = InvestmentCommission(
    amount = amount,
    asset = asset,
)

fun InvestmentCommission.toDto(): InvestmentCommissionDto = InvestmentCommissionDto(
    amount = amount,
    asset = asset,
)

fun InvestmentRelationDto.toDomain(): InvestmentRelation = InvestmentRelation(
    kind = kind,
    status = status,
    direction = direction,
    ruleId = ruleId,
    cashAccount = cashAccount.toDomain(),
    cashAmount = cashAmount,
    cashCurrency = cashCurrency,
    cashOccurredAt = cashOccurredAt,
    cashCounterparty = cashCounterparty,
    cashNote = cashNote,
    cashSourceType = cashSourceType,
    cashRecordId = cashRecordId,
    evidence = evidence.toStructuredObject(),
)

fun InvestmentRelation.toDto(): InvestmentRelationDto = InvestmentRelationDto(
    kind = kind,
    status = status,
    direction = direction,
    ruleId = ruleId,
    cashAccount = cashAccount.toDto(),
    cashAmount = cashAmount,
    cashCurrency = cashCurrency,
    cashOccurredAt = cashOccurredAt,
    cashCounterparty = cashCounterparty,
    cashNote = cashNote,
    cashSourceType = cashSourceType,
    cashRecordId = cashRecordId,
    evidence = evidence.toJsonObject(),
)

fun InvestmentEventDto.toDomain(): InvestmentEvent = InvestmentEvent(
    eventId = eventId,
    occurredAt = occurredAt,
    account = account.toDomain(),
    recordType = recordType,
    recordSubtype = recordSubtype,
    currency = currency,
    note = note,
    fromAsset = fromAsset.toDomain(),
    toAsset = toAsset.toDomain(),
    commission = commission.toDomain(),
    sourceType = sourceType,
    recordId = recordId,
    relations = relations.map { it.toDomain() },
)

fun InvestmentEvent.toDto(): InvestmentEventDto = InvestmentEventDto(
    eventId = eventId,
    occurredAt = occurredAt,
    account = account.toDto(),
    recordType = recordType,
    recordSubtype = recordSubtype,
    currency = currency,
    note = note,
    fromAsset = fromAsset.toDto(),
    toAsset = toAsset.toDto(),
    commission = commission.toDto(),
    sourceType = sourceType,
    recordId = recordId,
    relations = relations.map { it.toDto() },
)

fun InvestmentFiltersDto.toDomain(): InvestmentFilters = InvestmentFilters(
    dateFrom = dateFrom,
    dateTo = dateTo,
    accountId = accountId,
    recordType = recordType,
    ticker = ticker,
)

fun InvestmentFilters.toDto(): InvestmentFiltersDto = InvestmentFiltersDto(
    dateFrom = dateFrom,
    dateTo = dateTo,
    accountId = accountId,
    recordType = recordType,
    ticker = ticker,
)

fun InvestmentPageDto.toDomain(): InvestmentPage = InvestmentPage(
    dataVersion = dataVersion,
    items = items.map { it.toDomain() },
    nextCursor = nextCursor,
    pageSize = pageSize,
    filters = filters.mapValues { (_, value) -> value.toStructuredValue() },
)

fun InvestmentPage.toDto(): InvestmentPageDto = InvestmentPageDto(
    dataVersion = dataVersion,
    items = items.map { it.toDto() },
    nextCursor = nextCursor,
    pageSize = pageSize,
    filters = filters.mapValues { (_, value) -> value.toJsonElement() },
)

fun InvestmentEvidenceDto.toDomain(): InvestmentEvidence = InvestmentEvidence(
    dataVersion = dataVersion,
    event = event.toDomain(),
    sourceSnapshot = sourceSnapshot?.toStructuredObject(),
    relations = relations.map { it.toDomain() },
)

fun InvestmentEvidence.toDto(): InvestmentEvidenceDto = InvestmentEvidenceDto(
    dataVersion = dataVersion,
    event = event.toDto(),
    sourceSnapshot = sourceSnapshot?.toJsonObject(),
    relations = relations.map { it.toDto() },
)

fun PortfolioPeriodBaselineDto.toDomain(): PortfolioPeriodBaseline = PortfolioPeriodBaseline(
    account = account,
    ticker = ticker,
    occurredAt = occurredAt,
)

fun PortfolioPeriodBaseline.toDto(): PortfolioPeriodBaselineDto = PortfolioPeriodBaselineDto(
    account = account,
    ticker = ticker,
    occurredAt = occurredAt,
)

fun PortfolioPositionDto.toDomain(): PortfolioPosition = PortfolioPosition(
    ticker = ticker,
    displayName = displayName,
    shares = shares,
    totalCost = totalCost,
    costCurrency = costCurrency,
    isCash = isCash,
    currentPrice = currentPrice,
    marketValue = marketValue,
    profit = profit,
    quoteStatus = quoteStatus,
    quoteReason = quoteReason,
    quoteCurrency = quoteCurrency,
    quoteObservedAt = quoteObservedAt,
    quoteSession = quoteSession,
    displayCurrency = displayCurrency,
    displayMarketValue = displayMarketValue,
    usdMarketValue = usdMarketValue,
    fxRate = fxRate,
    fxStatus = fxStatus,
    fxReason = fxReason,
    periodProfit = periodProfit,
    periodProfitRate = periodProfitRate,
    periodBaselines = periodBaselines.map { it.toDomain() },
)

fun PortfolioPosition.toDto(): PortfolioPositionDto = PortfolioPositionDto(
    ticker = ticker,
    displayName = displayName,
    shares = shares,
    totalCost = totalCost,
    costCurrency = costCurrency,
    isCash = isCash,
    currentPrice = currentPrice,
    marketValue = marketValue,
    profit = profit,
    quoteStatus = quoteStatus,
    quoteReason = quoteReason,
    quoteCurrency = quoteCurrency,
    quoteObservedAt = quoteObservedAt,
    quoteSession = quoteSession,
    displayCurrency = displayCurrency,
    displayMarketValue = displayMarketValue,
    usdMarketValue = usdMarketValue,
    fxRate = fxRate,
    fxStatus = fxStatus,
    fxReason = fxReason,
    periodProfit = periodProfit,
    periodProfitRate = periodProfitRate,
    periodBaselines = periodBaselines.map { it.toDto() },
)

fun PortfolioAccountDto.toDomain(): PortfolioAccount = PortfolioAccount(
    name = name,
    currency = currency,
    positions = positions.map { it.toDomain() },
)

fun PortfolioAccount.toDto(): PortfolioAccountDto = PortfolioAccountDto(
    name = name,
    currency = currency,
    positions = positions.map { it.toDto() },
)

fun PortfolioDto.toDomain(): Portfolio = Portfolio(
    accounts = accounts.map { it.toDomain() },
    totalMarketValue = totalMarketValue,
    totalProfit = totalProfit,
    totalProfitRate = totalProfitRate,
    periodProfit = periodProfit,
    periodProfitRate = periodProfitRate,
    periodBaselines = periodBaselines.map { it.toDomain() },
)

fun Portfolio.toDto(): PortfolioDto = PortfolioDto(
    accounts = accounts.map { it.toDto() },
    totalMarketValue = totalMarketValue,
    totalProfit = totalProfit,
    totalProfitRate = totalProfitRate,
    periodProfit = periodProfit,
    periodProfitRate = periodProfitRate,
    periodBaselines = periodBaselines.map { it.toDto() },
)
