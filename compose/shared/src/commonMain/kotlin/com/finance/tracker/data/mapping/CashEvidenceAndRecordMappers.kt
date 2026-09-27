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

fun CashComponentDetailDto.toDomain(): CashComponentDetail = CashComponentDetail(
    id = id,
    cashTransactionId = cashTransactionId,
    account = account?.toDomain(),
    accountName = accountName,
    accountId = accountId,
    accountType = accountType,
    amount = amount,
    currency = currency,
    ordinal = ordinal,
    label = label,
)

fun CashComponentDetail.toDto(): CashComponentDetailDto = CashComponentDetailDto(
    id = id,
    cashTransactionId = cashTransactionId,
    account = account?.toDto(),
    accountName = accountName,
    accountId = accountId,
    accountType = accountType,
    amount = amount,
    currency = currency,
    ordinal = ordinal,
    label = label,
)

fun EvidenceRecordDto.toDomain(): EvidenceRecord = EvidenceRecord(
    id = id,
    occurredAt = occurredAt,
    account = account?.toDomain(),
    accountName = accountName,
    accountId = accountId,
    accountType = accountType,
    counterparty = counterparty,
    counterpartyAccount = counterpartyAccount,
    category = category?.toDomain(),
    categoryId = categoryId,
    note = note,
    amount = amount,
    currency = currency,
    sourceType = sourceType,
    recordId = recordId,
    recordType = recordType,
    recordSubtype = recordSubtype,
    cashGranularity = cashGranularity,
    components = components?.map { it.toDomain() },
    sourceSnapshot = sourceSnapshot?.mapValues { (_, value) -> value.toStructuredValue() },
)

fun EvidenceRecord.toDto(): EvidenceRecordDto = EvidenceRecordDto(
    id = id,
    occurredAt = occurredAt,
    account = account?.toDto(),
    accountName = accountName,
    accountId = accountId,
    accountType = accountType,
    counterparty = counterparty,
    counterpartyAccount = counterpartyAccount,
    category = category?.toDto(),
    categoryId = categoryId,
    note = note,
    amount = amount,
    currency = currency,
    sourceType = sourceType,
    recordId = recordId,
    recordType = recordType,
    recordSubtype = recordSubtype,
    cashGranularity = cashGranularity,
    components = components?.map { it.toDto() },
    sourceSnapshot = sourceSnapshot?.mapValues { (_, value) -> value.toJsonElement() },
)

fun EvidenceMemberDto.toDomain(): EvidenceMember = EvidenceMember(
    id = id,
    occurredAt = occurredAt,
    account = account?.toDomain(),
    accountName = accountName,
    accountId = accountId,
    accountType = accountType,
    counterparty = counterparty,
    counterpartyAccount = counterpartyAccount,
    category = category?.toDomain(),
    categoryId = categoryId,
    note = note,
    amount = amount,
    currency = currency,
    sourceType = sourceType,
    recordId = recordId,
    recordType = recordType,
    recordSubtype = recordSubtype,
    cashGranularity = cashGranularity,
    components = components?.map { it.toDomain() },
    roles = roles,
)

fun EvidenceMember.toDto(): EvidenceMemberDto = EvidenceMemberDto(
    id = id,
    occurredAt = occurredAt,
    account = account?.toDto(),
    accountName = accountName,
    accountId = accountId,
    accountType = accountType,
    counterparty = counterparty,
    counterpartyAccount = counterpartyAccount,
    category = category?.toDto(),
    categoryId = categoryId,
    note = note,
    amount = amount,
    currency = currency,
    sourceType = sourceType,
    recordId = recordId,
    recordType = recordType,
    recordSubtype = recordSubtype,
    cashGranularity = cashGranularity,
    components = components?.map { it.toDto() },
    roles = roles,
)

fun EndpointRelationDto.toDomain(): EndpointRelation = EndpointRelation(
    id = id,
    kind = kind,
    subtype = subtype,
    primaryRecord = primaryRecord?.toDomain(),
    secondaryRecord = secondaryRecord?.toDomain(),
)

fun EndpointRelation.toDto(): EndpointRelationDto = EndpointRelationDto(
    id = id,
    kind = kind,
    subtype = subtype,
    primaryRecord = primaryRecord?.toDto(),
    secondaryRecord = secondaryRecord?.toDto(),
)

fun AcceptedEvidenceRelationDto.toDomain(): AcceptedEvidenceRelation = AcceptedEvidenceRelation(
    id = id,
    kind = kind,
    subtype = subtype,
    primaryRecord = primaryRecord?.toDomain(),
    secondaryRecord = secondaryRecord?.toDomain(),
    ruleId = ruleId,
    confidence = confidence,
    evidence = evidence.toStructuredObject(),
)

fun AcceptedEvidenceRelation.toDto(): AcceptedEvidenceRelationDto = AcceptedEvidenceRelationDto(
    id = id,
    kind = kind,
    subtype = subtype,
    primaryRecord = primaryRecord?.toDto(),
    secondaryRecord = secondaryRecord?.toDto(),
    ruleId = ruleId,
    confidence = confidence,
    evidence = evidence.toJsonObject(),
)

fun InactiveRelationHintDto.toDomain(): InactiveRelationHint = InactiveRelationHint(
    id = id,
    kind = kind,
    subtype = subtype,
    primaryRecord = primaryRecord?.toDomain(),
    secondaryRecord = secondaryRecord?.toDomain(),
    status = status,
)

fun InactiveRelationHint.toDto(): InactiveRelationHintDto = InactiveRelationHintDto(
    id = id,
    kind = kind,
    subtype = subtype,
    primaryRecord = primaryRecord?.toDto(),
    secondaryRecord = secondaryRecord?.toDto(),
    status = status,
)

fun RefundTimelineItemDto.toDomain(): RefundTimelineItem = RefundTimelineItem(
    recordId = recordId,
    occurredAt = occurredAt,
    amount = amount,
    currency = currency,
    sourceType = sourceType,
)

fun RefundTimelineItem.toDto(): RefundTimelineItemDto = RefundTimelineItemDto(
    recordId = recordId,
    occurredAt = occurredAt,
    amount = amount,
    currency = currency,
    sourceType = sourceType,
)

fun EvidenceDto.toDomain(): Evidence = Evidence(
    projectionVersion = projectionVersion,
    projection = projection.toDomain(),
    rootRecord = rootRecord.toDomain(),
    members = members.map { it.toDomain() },
    acceptedRelations = acceptedRelations.map { it.toDomain() },
    inactiveRelationHints = inactiveRelationHints.map { it.toDomain() },
    refundTimeline = refundTimeline.map { it.toDomain() },
)

fun Evidence.toDto(): EvidenceDto = EvidenceDto(
    projectionVersion = projectionVersion,
    projection = projection.toDto(),
    rootRecord = rootRecord.toDto(),
    members = members.map { it.toDto() },
    acceptedRelations = acceptedRelations.map { it.toDto() },
    inactiveRelationHints = inactiveRelationHints.map { it.toDto() },
    refundTimeline = refundTimeline.map { it.toDto() },
)

fun CashRecordDto.toDomain(): CashRecord = CashRecord(
    id = id,
    occurredAt = occurredAt,
    amount = amount,
    currency = currency,
    counterparty = counterparty,
    counterpartyAccount = counterpartyAccount,
    note = note,
    category = category?.toDomain(),
    categoryId = categoryId,
    recordType = recordType,
    recordSubtype = recordSubtype,
    accountName = accountName,
    accountId = accountId,
    accountType = accountType,
    sourceType = sourceType,
    cashGranularity = cashGranularity,
    components = components?.map { it.toDomain() },
)

fun CashRecord.toDto(): CashRecordDto = CashRecordDto(
    id = id,
    occurredAt = occurredAt,
    amount = amount,
    currency = currency,
    counterparty = counterparty,
    counterpartyAccount = counterpartyAccount,
    note = note,
    category = category?.toDto(),
    categoryId = categoryId,
    recordType = recordType,
    recordSubtype = recordSubtype,
    accountName = accountName,
    accountId = accountId,
    accountType = accountType,
    sourceType = sourceType,
    cashGranularity = cashGranularity,
    components = components?.map { it.toDto() },
)

fun CashRelationDto.toDomain(): CashRelation = CashRelation(
    id = id,
    kind = kind,
    label = label,
    subtype = subtype,
    status = status,
    primaryRecord = primaryRecord?.toDomain(),
    secondaryRecord = secondaryRecord?.toDomain(),
)

fun CashRelation.toDto(): CashRelationDto = CashRelationDto(
    id = id,
    kind = kind,
    label = label,
    subtype = subtype,
    status = status,
    primaryRecord = primaryRecord?.toDto(),
    secondaryRecord = secondaryRecord?.toDto(),
)

fun CashRecordDetailDto.toDomain(): CashRecordDetail = CashRecordDetail(
    record = record.toDomain(),
    relations = relations.map { it.toDomain() },
    options = options.toDomain(),
)

fun CashRecordDetail.toDto(): CashRecordDetailDto = CashRecordDetailDto(
    record = record.toDto(),
    relations = relations.map { it.toDto() },
    options = options.toDto(),
)

fun CashRecordPageDto.toDomain(): CashRecordPage = CashRecordPage(
    items = items.map { it.toDomain() },
    nextCursor = nextCursor,
)

fun CashRecordPage.toDto(): CashRecordPageDto = CashRecordPageDto(
    items = items.map { it.toDto() },
    nextCursor = nextCursor,
)

fun CashRecordWriteDto.toDomain(): CashRecordWrite = CashRecordWrite(
    occurredAt = occurredAt,
    amount = amount,
    currency = currency,
    counterparty = counterparty,
    counterpartyAccount = counterpartyAccount,
    note = note,
    accountName = accountName,
    recordType = recordType,
    recordSubtype = recordSubtype,
    categoryId = categoryId,
    projectionVersion = projectionVersion,
    confirmRelationImpact = confirmRelationImpact,
)

fun CashRecordWrite.toDto(): CashRecordWriteDto = CashRecordWriteDto(
    occurredAt = occurredAt,
    amount = amount,
    currency = currency,
    counterparty = counterparty,
    counterpartyAccount = counterpartyAccount,
    note = note,
    accountName = accountName,
    recordType = recordType,
    recordSubtype = recordSubtype,
    categoryId = categoryId,
    projectionVersion = projectionVersion,
    confirmRelationImpact = confirmRelationImpact,
)
