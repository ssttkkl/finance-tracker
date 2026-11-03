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

fun CashFiltersDto.toDomain(): CashFilters = CashFilters(
    dateFrom = dateFrom,
    dateTo = dateTo,
    accountId = accountId,
    counterparty = counterparty,
    categoryId = categoryId,
    uncategorized = uncategorized,
    currency = currency,
    amountMin = amountMin,
    amountMax = amountMax,
    economicType = economicType,
    businessLabel = businessLabel,
    composition = composition,
    state = state, confirmationStatus = confirmationStatus,
    hideZeroAmount = hideZeroAmount?.toBooleanStrictOrNull() ?: true,
)

fun CashFilters.toDto(): CashFiltersDto = CashFiltersDto(
    dateFrom = dateFrom,
    dateTo = dateTo,
    accountId = accountId,
    counterparty = counterparty,
    categoryId = categoryId,
    uncategorized = uncategorized,
    currency = currency,
    amountMin = amountMin,
    amountMax = amountMax,
    economicType = economicType,
    businessLabel = businessLabel,
    composition = composition,
    state = state, confirmationStatus = confirmationStatus,
    hideZeroAmount = hideZeroAmount.toString(),
)

fun AccountListDto.toDomain(): AccountList = AccountList(
    items = items.map { it.toDomain() },
)

fun AccountList.toDto(): AccountListDto = AccountListDto(
    items = items.map { it.toDto() },
)

fun CashCategoryDeleteImpactDto.toDomain(): CashCategoryDeleteImpact = CashCategoryDeleteImpact(
    categoryId = categoryId,
    revision = revision,
    categoryRevision = categoryRevision,
    childCount = childCount,
    directUsageCount = directUsageCount,
)

fun CashCategoryDeleteImpact.toDto(): CashCategoryDeleteImpactDto = CashCategoryDeleteImpactDto(
    categoryId = categoryId,
    revision = revision,
    categoryRevision = categoryRevision,
    childCount = childCount,
    directUsageCount = directUsageCount,
)

fun CashCategoryDeleteResultDto.toDomain(): CashCategoryDeleteResult = CashCategoryDeleteResult(
    categoryId = categoryId,
    clearedTransactionCount = clearedTransactionCount,
    revision = revision,
)

fun CashCategoryDeleteResult.toDto(): CashCategoryDeleteResultDto = CashCategoryDeleteResultDto(
    categoryId = categoryId,
    clearedTransactionCount = clearedTransactionCount,
    revision = revision,
)

fun CashClassificationResultDto.toDomain(): CashClassificationResult = CashClassificationResult(
    projectionVersion = projectionVersion,
    projectionCount = projectionCount,
    updatedTransactionCount = updatedTransactionCount,
    categoryId = categoryId,
)

fun CashClassificationResult.toDto(): CashClassificationResultDto = CashClassificationResultDto(
    projectionVersion = projectionVersion,
    projectionCount = projectionCount,
    updatedTransactionCount = updatedTransactionCount,
    categoryId = categoryId,
)

fun CashRecordDeleteResultDto.toDomain(): CashRecordDeleteResult = CashRecordDeleteResult(
    deleted = deleted,
    relatedCount = relatedCount,
    deletedFactIds = deletedFactIds,
)

fun CashRecordDeleteResult.toDto(): CashRecordDeleteResultDto = CashRecordDeleteResultDto(
    deleted = deleted,
    relatedCount = relatedCount,
    deletedFactIds = deletedFactIds,
)
