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

private val mappingJson = Json { explicitNulls = false }

fun JsonElement.toStructuredValue(): StructuredValue = when (this) {
    JsonNull -> StructuredNull
    is JsonObject -> toStructuredObject()
    is JsonArray -> StructuredArray(map { it.toStructuredValue() })
    is JsonPrimitive -> when {
        isString -> StructuredText(content)
        booleanOrNull != null -> StructuredBoolean(booleanOrNull!!)
        else -> StructuredNumber(content)
    }
}

fun JsonObject.toStructuredObject(): StructuredObject = StructuredObject(mapValues { (_, value) -> value.toStructuredValue() })

fun StructuredValue.toJsonElement(): JsonElement = when (this) {
    StructuredNull -> JsonNull
    is StructuredObject -> toJsonObject()
    is StructuredArray -> JsonArray(values.map { it.toJsonElement() })
    is StructuredText -> JsonPrimitive(value)
    is StructuredNumber -> mappingJson.parseToJsonElement(value)
    is StructuredBoolean -> JsonPrimitive(value)
}

fun StructuredObject.toJsonObject(): JsonObject = JsonObject(fields.mapValues { (_, value) -> value.toJsonElement() })

fun AccountDto.toDomain(): Account = Account(
    id = id,
    name = name,
    type = type,
    active = active,
    currencies = currencies,
)

fun Account.toDto(): AccountDto = AccountDto(
    id = id,
    name = name,
    type = type,
    active = active,
    currencies = currencies,
)

fun SimpleOptionDto.toDomain(): SimpleOption = SimpleOption(
    value = value,
    label = label,
)

fun SimpleOption.toDto(): SimpleOptionDto = SimpleOptionDto(
    value = value,
    label = label,
)

fun RecordTypeOptionDto.toDomain(): RecordTypeOption = RecordTypeOption(
    value = value,
    label = label,
    subtypes = subtypes.map { it.toDomain() },
)

fun RecordTypeOption.toDto(): RecordTypeOptionDto = RecordTypeOptionDto(
    value = value,
    label = label,
    subtypes = subtypes.map { it.toDto() },
)

fun LedgerOptionsDto.toDomain(): LedgerOptions = LedgerOptions(
    recordTypes = recordTypes.map { it.toDomain() },
    relationTypes = relationTypes.map { it.toDomain() },
)

fun LedgerOptions.toDto(): LedgerOptionsDto = LedgerOptionsDto(
    recordTypes = recordTypes.map { it.toDto() },
    relationTypes = relationTypes.map { it.toDto() },
)

fun CashCategoryPathItemDto.toDomain(): CashCategoryPathItem = CashCategoryPathItem(
    id = id,
    name = name,
)

fun CashCategoryPathItem.toDto(): CashCategoryPathItemDto = CashCategoryPathItemDto(
    id = id,
    name = name,
)

fun CashCategoryDto.toDomain(): CashCategory = CashCategory(
    id = id,
    parentId = parentId,
    name = name,
    description = description,
    path = path.map { it.toDomain() },
    depth = depth,
    sortOrder = sortOrder,
    revision = revision,
)

fun CashCategory.toDto(): CashCategoryDto = CashCategoryDto(
    id = id,
    parentId = parentId,
    name = name,
    description = description,
    path = path.map { it.toDto() },
    depth = depth,
    sortOrder = sortOrder,
    revision = revision,
)

fun CashCategoryDirectoryItemDto.toWriteResult(): CashCategoryWriteResult = CashCategoryWriteResult(id, revision)

fun CashCategoryDirectoryDto.toDomain(): CashCategoryDirectory {
    val nodes = items.map { com.finance.tracker.business.domain.CategoryNode(it.id, it.parentId, it.name, it.sortOrder) }
    val byId = nodes.associateBy { it.id }
    require(byId.size == nodes.size) { "category.duplicate_id" }
    return CashCategoryDirectory(revision, items.map { item ->
        val ancestors = com.finance.tracker.business.domain.categoryAncestors(byId.getValue(item.id), byId)
        require(item.path == ancestors.map { it.name } + item.name) { "category.invalid_path" }
        CashCategory(item.id, item.parentId, item.name, item.description,
            ancestors.map { CashCategoryPathItem(it.id, it.name) }, item.depth, item.sortOrder, item.revision)
    })
}

fun CashCategoryDirectory.toDto(): CashCategoryDirectoryDto = CashCategoryDirectoryDto(
    revision = revision,
    items = items.map { item -> CashCategoryDirectoryItemDto(item.id, item.parentId, item.name,
        item.description, item.path.map { it.name } + item.name, item.depth, item.sortOrder, item.revision) },
)

fun AcceptedRelationSummaryDto.toDomain(): AcceptedRelationSummary = AcceptedRelationSummary(
    kind = kind,
    subtype = subtype,
    count = count,
)

fun AcceptedRelationSummary.toDto(): AcceptedRelationSummaryDto = AcceptedRelationSummaryDto(
    kind = kind,
    subtype = subtype,
    count = count,
)

fun CashTransferDto.toDomain(): CashTransfer = CashTransfer(
    fromAccount = fromAccount.toDomain(),
    fromAmount = fromAmount,
    fromCurrency = fromCurrency,
    toAccount = toAccount.toDomain(),
    toAmount = toAmount,
    toCurrency = toCurrency,
)

fun CashTransfer.toDto(): CashTransferDto = CashTransferDto(
    fromAccount = fromAccount.toDto(),
    fromAmount = fromAmount,
    fromCurrency = fromCurrency,
    toAccount = toAccount.toDto(),
    toAmount = toAmount,
    toCurrency = toCurrency,
)
