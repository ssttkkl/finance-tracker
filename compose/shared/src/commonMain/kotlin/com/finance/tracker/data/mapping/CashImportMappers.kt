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

fun ImportComponentDto.toDomain(): ImportComponent = ImportComponent(
    ordinal = ordinal,
    sourceLabel = sourceLabel,
    accountKey = accountKey,
    accountId = accountId,
    accountName = accountName,
    amount = amount,
    amountRequired = amountRequired,
    kind = kind,
)

fun ImportComponent.toDto(): ImportComponentDto = ImportComponentDto(
    ordinal = ordinal,
    sourceLabel = sourceLabel,
    accountKey = accountKey,
    accountId = accountId,
    accountName = accountName,
    amount = amount,
    amountRequired = amountRequired,
    kind = kind,
)

fun ImportComponentAllocationDto.toDomain(): ImportComponentAllocation = ImportComponentAllocation(
    recordId = recordId,
    cashGranularity = cashGranularity,
    status = status,
    totalAmount = totalAmount,
    conserved = conserved,
    components = components.map { it.toDomain() },
)

fun ImportComponentAllocation.toDto(): ImportComponentAllocationDto = ImportComponentAllocationDto(
    recordId = recordId,
    cashGranularity = cashGranularity,
    status = status,
    totalAmount = totalAmount,
    conserved = conserved,
    components = components.map { it.toDto() },
)

fun ImportPreviewItemDto.toDomain(): ImportPreviewItem = ImportPreviewItem(
    recordId = recordId,
    relationRef = relationRef,
    occurredAt = occurredAt,
    amount = amount,
    currency = currency,
    accountName = accountName,
    counterparty = counterparty,
    counterpartyAccount = counterpartyAccount,
    recordType = recordType,
    recordSubtype = recordSubtype,
    category = category,
    note = note,
    channel = channel,
    status = status,
    message = message,
    components = components?.map { it.toDomain() },
    componentAllocation = componentAllocation?.toDomain(),
)

fun ImportPreviewItem.toDto(): ImportPreviewItemDto = ImportPreviewItemDto(
    recordId = recordId,
    relationRef = relationRef,
    occurredAt = occurredAt,
    amount = amount,
    currency = currency,
    accountName = accountName,
    counterparty = counterparty,
    counterpartyAccount = counterpartyAccount,
    recordType = recordType,
    recordSubtype = recordSubtype,
    category = category,
    note = note,
    channel = channel,
    status = status,
    message = message,
    components = components?.map { it.toDto() },
    componentAllocation = componentAllocation?.toDto(),
)

fun ImportRelationRecordDto.toDomain(): ImportRelationRecord = ImportRelationRecord(
    recordId = recordId,
    relationRef = relationRef,
    occurredAt = occurredAt,
    amount = amount,
    currency = currency,
    accountName = accountName,
    counterparty = counterparty,
    counterpartyAccount = counterpartyAccount,
    recordType = recordType,
    recordSubtype = recordSubtype,
    category = category,
    note = note,
    channel = channel,
    status = status,
    message = message,
    components = components?.map { it.toDomain() },
    componentAllocation = componentAllocation?.toDomain(),
    preview = preview,
    factId = factId,
)

fun ImportRelationRecord.toDto(): ImportRelationRecordDto = ImportRelationRecordDto(
    recordId = recordId,
    relationRef = relationRef,
    occurredAt = occurredAt,
    amount = amount,
    currency = currency,
    accountName = accountName,
    counterparty = counterparty,
    counterpartyAccount = counterpartyAccount,
    recordType = recordType,
    recordSubtype = recordSubtype,
    category = category,
    note = note,
    channel = channel,
    status = status,
    message = message,
    components = components?.map { it.toDto() },
    componentAllocation = componentAllocation?.toDto(),
    preview = preview,
    factId = factId,
)

fun ImportRelationDto.toDomain(): ImportRelation = ImportRelation(
    id = id,
    kind = kind,
    label = label,
    subtype = subtype,
    status = status,
    automatic = automatic,
    ruleId = ruleId,
    reason = reason,
    primary = primary.toDomain(),
    secondary = secondary?.toDomain(),
    candidates = candidates.map { it.toDomain() },
)

fun ImportRelation.toDto(): ImportRelationDto = ImportRelationDto(
    id = id,
    kind = kind,
    label = label,
    subtype = subtype,
    status = status,
    automatic = automatic,
    ruleId = ruleId,
    reason = reason,
    primary = primary.toDto(),
    secondary = secondary?.toDto(),
    candidates = candidates.map { it.toDto() },
)

fun ImportNewAccountDto.toDomain(): ImportNewAccount = ImportNewAccount(
    draftId = draftId,
    name = name,
    type = type,
    currencies = currencies,
)

fun ImportNewAccount.toDto(): ImportNewAccountDto = ImportNewAccountDto(
    draftId = draftId,
    name = name,
    type = type,
    currencies = currencies,
)

fun ImportMappingSuggestionDto.toDomain(): ImportMappingSuggestion = ImportMappingSuggestion(
    accountId = accountId,
    account = account?.toDomain(),
    missingCurrencies = missingCurrencies,
    mappingRevision = mappingRevision,
)

fun ImportMappingSuggestion.toDto(): ImportMappingSuggestionDto = ImportMappingSuggestionDto(
    accountId = accountId,
    account = account?.toDto(),
    missingCurrencies = missingCurrencies,
    mappingRevision = mappingRevision,
)

fun ImportSourceGroupDto.toDomain(): ImportSourceGroup = ImportSourceGroup(
    groupId = groupId,
    sourceType = sourceType,
    channel = channel,
    channelLabel = channelLabel,
    displayName = displayName,
    maskedEvidence = maskedEvidence,
    currencies = currencies,
    rowCount = rowCount,
    suggestion = suggestion.toDomain(),
)

fun ImportSourceGroup.toDto(): ImportSourceGroupDto = ImportSourceGroupDto(
    groupId = groupId,
    sourceType = sourceType,
    channel = channel,
    channelLabel = channelLabel,
    displayName = displayName,
    maskedEvidence = maskedEvidence,
    currencies = currencies,
    rowCount = rowCount,
    suggestion = suggestion.toDto(),
)

fun ImportMappingResultDto.toDomain(): ImportMappingResult = ImportMappingResult(
    groupId = groupId,
    sourceType = sourceType,
    channel = channel,
    channelLabel = channelLabel,
    accountId = accountId,
    missingCurrencies = missingCurrencies,
    newAccount = newAccount?.toDomain(),
)

fun ImportMappingResult.toDto(): ImportMappingResultDto = ImportMappingResultDto(
    groupId = groupId,
    sourceType = sourceType,
    channel = channel,
    channelLabel = channelLabel,
    accountId = accountId,
    missingCurrencies = missingCurrencies,
    newAccount = newAccount?.toDto(),
)

fun ImportAmountDto.toDomain(): ImportAmount = ImportAmount(
    amount = amount,
)

fun ImportAmount.toDto(): ImportAmountDto = ImportAmountDto(
    amount = amount,
)

fun ImportMappingDecisionDto.toDomain(): ImportMappingDecision = ImportMappingDecision(
    groupId = groupId,
    accountId = accountId,
    mappingRevision = mappingRevision,
    newAccount = newAccount?.toDomain(),
    componentAllocations = componentAllocations?.mapValues { (_, values) -> values.map { it.toDomain() } },
)

fun ImportMappingDecision.toDto(): ImportMappingDecisionDto = ImportMappingDecisionDto(
    groupId = groupId,
    accountId = accountId,
    mappingRevision = mappingRevision,
    newAccount = newAccount?.toDto(),
    componentAllocations = componentAllocations?.mapValues { (_, values) -> values.map { it.toDto() } },
)

fun ImportFileDescriptorDto.toDomain(): ImportFileDescriptor = ImportFileDescriptor(
    name = name,
    digest = digest,
)

fun ImportFileDescriptor.toDto(): ImportFileDescriptorDto = ImportFileDescriptorDto(
    name = name,
    digest = digest,
)

fun ImportFileScanDto.toDomain(): ImportFileScan = ImportFileScan(
    index = index,
    name = name,
    filename = filename,
    digest = digest,
    size = size,
    channel = channel,
    channelLabel = channelLabel,
    rowCount = rowCount,
    status = status,
    errorCode = errorCode,
)

fun ImportFileScan.toDto(): ImportFileScanDto = ImportFileScanDto(
    index = index,
    name = name,
    filename = filename,
    digest = digest,
    size = size,
    channel = channel,
    channelLabel = channelLabel,
    rowCount = rowCount,
    status = status,
    errorCode = errorCode,
)

fun ImportDetectionDto.toDomain(): ImportDetection = ImportDetection(
    channel = channel,
    channelLabel = channelLabel,
    file = file.toDomain(),
    digest = digest,
    rowCount = rowCount,
)

fun ImportDetection.toDto(): ImportDetectionDto = ImportDetectionDto(
    channel = channel,
    channelLabel = channelLabel,
    file = file.toDto(),
    digest = digest,
    rowCount = rowCount,
)

fun ImportScanDto.toDomain(): ImportScan = ImportScan(
    importToken = importToken,
    contract = contract,
    channel = channel,
    channelLabel = channelLabel,
    ready = ready,
    batchDigest = batchDigest,
    channels = channels,
    files = files.map { it.toDomain() },
    file = file.toDomain(),
    digest = digest,
    unresolvedCount = unresolvedCount,
    accounts = accounts.map { it.toDomain() },
    groups = groups.map { it.toDomain() },
)

fun ImportScan.toDto(): ImportScanDto = ImportScanDto(
    importToken = importToken,
    contract = contract,
    channel = channel,
    channelLabel = channelLabel,
    ready = ready,
    batchDigest = batchDigest,
    channels = channels,
    files = files.map { it.toDto() },
    file = file.toDto(),
    digest = digest,
    unresolvedCount = unresolvedCount,
    accounts = accounts.map { it.toDto() },
    groups = groups.map { it.toDto() },
)

fun ImportPreviewSummaryDto.toDomain(): ImportPreviewSummary = ImportPreviewSummary(
    total = total,
    new = new,
    existing = existing,
    unsupported = unsupported,
    unresolved = unresolved,
    requiresAllocation = requiresAllocation,
)

fun ImportPreviewSummary.toDto(): ImportPreviewSummaryDto = ImportPreviewSummaryDto(
    total = total,
    new = new,
    existing = existing,
    unsupported = unsupported,
    unresolved = unresolved,
    requiresAllocation = requiresAllocation,
)

fun ImportPreviewDto.toDomain(): ImportPreview = ImportPreview(
    importToken = importToken,
    channel = channel,
    channelLabel = channelLabel,
    file = file.toDomain(),
    batchDigest = batchDigest,
    channels = channels,
    files = files.map { it.toDomain() },
    relationDigest = relationDigest,
    columns = columns,
    items = items.map { it.toDomain() },
    summary = summary.toDomain(),
    mapping = mapping.map { it.toDomain() },
    relations = relations.map { it.toDomain() },
)

fun ImportPreview.toDto(): ImportPreviewDto = ImportPreviewDto(
    importToken = importToken,
    channel = channel,
    channelLabel = channelLabel,
    file = file.toDto(),
    batchDigest = batchDigest,
    channels = channels,
    files = files.map { it.toDto() },
    relationDigest = relationDigest,
    columns = columns,
    items = items.map { it.toDto() },
    summary = summary.toDto(),
    mapping = mapping.map { it.toDto() },
    relations = relations.map { it.toDto() },
)

fun ImportCommitResultDto.toDomain(): ImportCommitResult = ImportCommitResult(
    message = message,
    newRows = newRows,
    updatedRows = updatedRows,
    skippedRows = skippedRows,
    channel = channel,
    digest = digest,
    batchDigest = batchDigest,
    channels = channels,
    files = files.map { it.toDomain() },
    pendingRelations = pendingRelations,
)

fun ImportCommitResult.toDto(): ImportCommitResultDto = ImportCommitResultDto(
    message = message,
    newRows = newRows,
    updatedRows = updatedRows,
    skippedRows = skippedRows,
    channel = channel,
    digest = digest,
    batchDigest = batchDigest,
    channels = channels,
    files = files.map { it.toDto() },
    pendingRelations = pendingRelations,
)
