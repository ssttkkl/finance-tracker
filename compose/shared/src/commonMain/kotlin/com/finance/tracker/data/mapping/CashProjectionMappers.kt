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

fun CashProjectionDto.toDomain(): CashProjection = CashProjection(
    projectionId = projectionId,
    occurredAt = occurredAt,
    account = account?.toDomain(),
    counterparty = counterparty,
    category = category?.toDomain(),
    note = note,
    amount = amount,
    currency = currency,
    economicType = economicType,
    transferSubtype = transferSubtype,
    composition = composition,
    memberCount = memberCount,
    acceptedRelationSummary = acceptedRelationSummary.map { it.toDomain() },
    sourceType = sourceType,
    sourceTypes = sourceTypes,
    recordId = recordId,
    visible = visible,
    hiddenReason = hiddenReason,
    transfer = transfer?.toDomain(),
)

fun CashProjection.toDto(): CashProjectionDto = CashProjectionDto(
    projectionId = projectionId,
    occurredAt = occurredAt,
    account = account?.toDto(),
    counterparty = counterparty,
    category = category?.toDto(),
    note = note,
    amount = amount,
    currency = currency,
    economicType = economicType,
    transferSubtype = transferSubtype,
    composition = composition,
    memberCount = memberCount,
    acceptedRelationSummary = acceptedRelationSummary.map { it.toDto() },
    sourceType = sourceType,
    sourceTypes = sourceTypes,
    recordId = recordId,
    visible = visible,
    hiddenReason = hiddenReason,
    transfer = transfer?.toDto(),
)

fun CashEconomicTypeFilterOptionDto.toDomain(): CashEconomicTypeFilterOption = CashEconomicTypeFilterOption(
    economicType = economicType,
    transferSubtypes = transferSubtypes,
)

fun CashEconomicTypeFilterOption.toDto(): CashEconomicTypeFilterOptionDto = CashEconomicTypeFilterOptionDto(
    economicType = economicType,
    transferSubtypes = transferSubtypes,
)

fun CashFilterOptionsDto.toDomain(): CashFilterOptions = CashFilterOptions(
    categories = categories.map { it.toDomain() },
    currencies = currencies,
    economicTypes = economicTypes.map { it.toDomain() },
)

fun CashFilterOptions.toDto(): CashFilterOptionsDto = CashFilterOptionsDto(
    categories = categories.map { it.toDto() },
    currencies = currencies,
    economicTypes = economicTypes.map { it.toDto() },
)

fun CashMonthlyCurrencySummaryDto.toDomain(): CashMonthlyCurrencySummary = CashMonthlyCurrencySummary(
    currency = currency,
    income = income,
    expense = expense,
)

fun CashMonthlyCurrencySummary.toDto(): CashMonthlyCurrencySummaryDto = CashMonthlyCurrencySummaryDto(
    currency = currency,
    income = income,
    expense = expense,
)

fun CashMonthlySummaryDto.toDomain(): CashMonthlySummary = CashMonthlySummary(
    month = month,
    currencies = currencies.map { it.toDomain() },
)

fun CashMonthlySummary.toDto(): CashMonthlySummaryDto = CashMonthlySummaryDto(
    month = month,
    currencies = currencies.map { it.toDto() },
)

fun CashPageDto.toDomain(): CashPage = CashPage(
    projectionVersion = projectionVersion,
    items = items.map { it.toDomain() },
    nextCursor = nextCursor,
    pageSize = pageSize,
    filters = filters,
    filterOptions = filterOptions.toDomain(),
    monthlySummaries = monthlySummaries.map { it.toDomain() },
)

fun CashPage.toDto(): CashPageDto = CashPageDto(
    projectionVersion = projectionVersion,
    items = items.map { it.toDto() },
    nextCursor = nextCursor,
    pageSize = pageSize,
    filters = filters,
    filterOptions = filterOptions.toDto(),
    monthlySummaries = monthlySummaries.map { it.toDto() },
)

fun CashProjectionDeleteImpactDto.toDomain(): CashProjectionDeleteImpact = CashProjectionDeleteImpact(
    projectionCount = projectionCount,
    transactionCount = transactionCount,
    relationGroupCount = relationGroupCount,
)

fun CashProjectionDeleteImpact.toDto(): CashProjectionDeleteImpactDto = CashProjectionDeleteImpactDto(
    projectionCount = projectionCount,
    transactionCount = transactionCount,
    relationGroupCount = relationGroupCount,
)

fun CashProjectionDeleteResultDto.toDomain(): CashProjectionDeleteResult = CashProjectionDeleteResult(
    projectionCount = projectionCount,
    transactionCount = transactionCount,
    relationGroupCount = relationGroupCount,
    deleted = deleted,
    projectionVersion = projectionVersion,
)

fun CashProjectionDeleteResult.toDto(): CashProjectionDeleteResultDto = CashProjectionDeleteResultDto(
    projectionCount = projectionCount,
    transactionCount = transactionCount,
    relationGroupCount = relationGroupCount,
    deleted = deleted,
    projectionVersion = projectionVersion,
)
